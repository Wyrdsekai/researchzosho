package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Letting people in (0.6.1, AFTER_0_6 §5): a join link that signs a browser in once within its week; the shared door, where a relay's
 * identity headers are trusted and on the main port are not, and where the cookie is Secure; asking to be let in from the sign-in page,
 * answered from the inbox, collected on the visitor's next load; the readers section on the Settings page; the one-step command.
 */
class LetPeopleInTest {

    static HttpClient client() { return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(); }

    static HttpResponse<String> get(HttpClient c, String url, String cookie, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (cookie != null) b.header("Cookie", cookie);
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient c, String url, String form, String cookie, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        if (cookie != null) b.header("Cookie", cookie);
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The value of a cookie the response set, or null. */
    static String setCookie(HttpResponse<String> r, String name) {
        for (String h : r.headers().allValues("Set-Cookie")) if (h.startsWith(name + "=")) return h;
        return null;
    }

    static String value(String setCookie) { return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')); }

    Boolean signIn, local;

    @BeforeEach void signInOn() { signIn = WebAccess.OVERRIDE; local = WebAccess.LOCAL_OVERRIDE; WebAccess.OVERRIDE = true; WebAccess.LOCAL_OVERRIDE = false; }   // a browser from elsewhere, though it comes from this machine
    @AfterEach void restore() { WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; }

    static LibraryStore lib(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.setDefault(store, Patrons.Level.deny);
        Patrons.set(store, "did:key:owner", "Owner", Patrons.Level.write);
        return store;
    }

    @Test
    void aJoinLinkSignsABrowserInOnceWithinItsWeek(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        Invites.Invite inv = Invites.make(store, "Ann Example", Patrons.Level.read);
        assertTrue(inv.did().startsWith("did:key:person-") && inv.link().startsWith("http://127.0.0.1:4649/join?code=R-0001."), inv.link());
        assertEquals(Patrons.Level.read, Patrons.byDid(store, inv.did()).level(), "listed at once, with a token held for the link");
        assertTrue(AccessRequests.list(store).get(0).note().equals(Invites.NOTE) && AccessRequests.pending(store) == 0, "an invite is not a stranger asking");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = client();
        String base = "http://127.0.0.1:" + d.port();
        try {
            // the link opens on a page that asks for a password; looking is not using it
            var opened = get(c, base + "/join?code=" + inv.code(), null);
            assertEquals(200, opened.statusCode(), opened.body());
            assertTrue(opened.body().contains("Welcome, Ann Example") && opened.body().contains("Choose a password") && opened.body().contains("name=\"again\""), opened.body());
            assertNull(setCookie(opened, Pages.COOKIE), "looking at the link signs nobody in");
            assertTrue(post(c, base + "/join", "code=" + inv.code() + "&password=short&again=short", null).body().contains("at least 8 characters"));
            assertTrue(post(c, base + "/join", "code=" + inv.code() + "&password=ann-secret-1&again=ann-secret-2", null).body().contains("The two passwords differ"));
            var joined = post(c, base + "/join", "code=" + inv.code() + "&password=ann-secret-1&again=ann-secret-1", null);
            assertEquals(303, joined.statusCode(), joined.body());
            String cookie = setCookie(joined, Pages.COOKIE);
            assertNotNull(cookie, "the password chosen, the browser is signed in");
            assertTrue(cookie.contains("SameSite=Lax") && !cookie.contains("Secure"), "Lax, so the redirect after a click from a message carries it; not Secure on the main port: " + cookie);
            var home = get(c, base + "/", Pages.COOKIE + "=" + value(cookie));
            assertTrue(home.body().contains("Ann Example · <a href=\"/account\">account</a> · <a href=\"/logout\">sign out</a>"), "signed in by name: " + home.body().substring(0, Math.min(1500, home.body().length())));
            // and from any other browser, the name and password sign her in
            var elsewhere = post(c, base + "/login", "name=ann+example&password=ann-secret-1", null);
            assertEquals(303, elsewhere.statusCode(), elsewhere.body());
            assertNotNull(setCookie(elsewhere, Pages.COOKIE));
            assertEquals(403, post(c, base + "/login", "name=Ann+Example&password=ann-secret-2", null).statusCode());
            var again = get(c, base + "/join?code=" + inv.code(), null);
            assertEquals(403, again.statusCode());
            assertTrue(again.body().contains("This link was already used"), again.body());
            assertTrue(get(c, base + "/join?code=R-0001.wrong", null).body().contains("This link is not right"));
            assertTrue(get(c, base + "/join?code=nonsense", null).body().contains("That is not a join link"));
            // a link older than its week
            Invites.Invite old = Invites.make(store, "Late Joiner", Patrons.Level.read);
            Path f = AccessRequests.file(store);
            Files.writeString(f, Files.readString(f).replace(LocalDate.now().toString(), LocalDate.now().minusDays(Invites.DAYS + 1).toString()));
            var late = get(c, base + "/join?code=" + old.code(), null);
            assertEquals(403, late.statusCode());
            assertTrue(late.body().contains("This link has expired"), late.body());
        } finally { d.stop(); }
    }

    @Test
    void theSharedDoorTrustsARelaysIdentityAndTheMainPortDoesNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        Patrons.set(store, Patrons.loginDid("Ann@Example.com"), "Ann", Patrons.Level.read);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1, 0);
        HttpClient c = client();
        String main = "http://127.0.0.1:" + d.port(), door = "http://127.0.0.1:" + d.doorPort();
        try {
            assertTrue(d.doorPort() > 0 && d.doorPort() != d.port(), "a second listener of its own");
            var viaDoor = get(c, door + "/", null, "Tailscale-User-Login", "ann@example.com", "Tailscale-User-Name", "Ann E.");
            assertEquals(200, viaDoor.statusCode());
            assertTrue(viaDoor.body().contains("Ann · <a href=\"/account\">account</a> · <a href=\"/logout\">sign out</a>"), "the tailnet login is the listed reader: " + viaDoor.body().substring(0, 1200));
            var viaMain = get(c, main + "/", null, "Tailscale-User-Login", "ann@example.com");
            assertTrue(viaMain.body().contains("<a href=\"/login\">sign in</a>") && !viaMain.body().contains("sign out"), "on the main port the header means nothing: " + viaMain.body().substring(0, 1200));
            var stranger = get(c, door + "/login", null, "Tailscale-User-Login", "bob@example.com", "Tailscale-User-Name", "Bob");
            assertTrue(stranger.body().contains("Ask to be let in") && stranger.body().contains("value=\"Bob\"") && stranger.body().contains("You are bob@example.com to the relay"), "a login the list does not hold: the ask form, with the name filled: " + stranger.body());
            var mail = get(c, door + "/", null, "Cf-Access-Authenticated-User-Email", "ANN@example.com");
            assertTrue(mail.body().contains("Ann · <a href=\"/account\">account</a> · <a href=\"/logout\">sign out</a>"), "a quick tunnel's mail check names the same reader");
            // the cookie set on the door is Secure, the relay in front being HTTPS; the same sign-in on the main port is not
            Patrons.set(store, "did:key:t", "Tee", Patrons.Level.read);
            String token = Patrons.issueToken(store, "did:key:t");
            String onDoor = setCookie(post(c, door + "/login", "token=" + token, null), Pages.COOKIE), onMain = setCookie(post(c, main + "/login", "token=" + token, null), Pages.COOKIE);
            assertTrue(onDoor.contains("; Secure") && onDoor.contains("SameSite=Lax"), onDoor);
            assertTrue(!onMain.contains("Secure") && onMain.contains("SameSite=Lax"), onMain);
            assertTrue(setCookie(get(c, door + "/logout", null), Pages.COOKIE).contains("; Secure"));
        } finally { d.stop(); }
    }

    @Test
    void aVisitorAsksToBeLetInAndIsSignedInOnceTheOwnerAnswers(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        String owner = Patrons.issueToken(store, "did:key:owner");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = client();
        String base = "http://127.0.0.1:" + d.port();
        try {
            assertTrue(post(c, base + "/login", "ask=1&name=Cho+Example&note=hello", null).body().contains("at least 8 characters"), "asking means choosing a password");
            var asked = post(c, base + "/login", "ask=1&name=Cho+Example&password=cho-secret-1&note=we+met+at+the+archive", null);
            assertEquals(200, asked.statusCode());
            assertTrue(asked.body().contains(Pages.WAITING), asked.body());
            String ask = setCookie(asked, Pages.ASK);
            assertNotNull(ask, "the claim stays in the browser"); assertTrue(ask.contains("HttpOnly"));
            String askCookie = Pages.ASK + "=" + value(ask);
            assertEquals(1, AccessRequests.pending(store));
            assertTrue(AccessRequests.list(store).get(0).did().startsWith("did:key:web-"), "a browser with no relay identity gets an id of its own");
            // waiting: the pages stay a visitor's, and the sign-in page says so
            assertTrue(get(c, base + "/login", askCookie).body().contains(Pages.WAITING));
            assertTrue(get(c, base + "/", askCookie).body().contains("<a href=\"/login\">sign in</a>"));
            // the owner sees it in the inbox and lets them in to read
            String ownerCookie = Pages.COOKIE + "=" + owner;
            var inbox = get(c, base + "/inbox", ownerCookie);
            assertTrue(inbox.body().contains("Asking to be let in") && inbox.body().contains("Cho Example") && inbox.body().contains("we met at the archive") && inbox.body().contains("value=\"let-in-read\""), inbox.body());
            var decided = post(c, base + "/inbox", "request=R-0001&do=let-in-read", ownerCookie);
            assertEquals(303, decided.statusCode());
            assertEquals(0, AccessRequests.pending(store));
            // the visitor's next load collects the token and loads again as them
            var next = get(c, base + "/subjects", askCookie);
            assertEquals(303, next.statusCode(), next.body());
            String signed = setCookie(next, Pages.COOKIE);
            assertNotNull(signed, "signed in"); assertTrue(setCookie(next, Pages.ASK).contains("Max-Age=0"), "the claim is spent");
            assertEquals("/subjects", next.headers().firstValue("Location").orElse(""));
            assertTrue(get(c, base + "/", Pages.COOKIE + "=" + value(signed)).body().contains("Cho Example · <a href=\"/account\">account</a>"));
            assertNotNull(Accounts.signIn(store, "Cho Example", "cho-secret-1"), "the password chosen when asking is the account's");
            assertEquals("", AccessRequests.byId(store, "R-0001").password(), "and its hash left the request once the account was made");
            // a second asker, refused with a reason: the next load clears the wait, and the sign-in page offers the form again
            var asked2 = post(c, base + "/login", "ask=1&name=Dee&password=dee-secret-1&note=", null);
            String ask2 = Pages.ASK + "=" + value(setCookie(asked2, Pages.ASK));
            post(c, base + "/inbox", "request=R-0002&do=refuse&why=unknown+to+me", ownerCookie);
            var after = get(c, base + "/login", ask2);
            assertEquals(200, after.statusCode());
            assertTrue(setCookie(after, Pages.ASK).contains("Max-Age=0"), "nothing more to wait for");
            assertEquals("denied", AccessRequests.list(store).stream().filter(r -> r.id().equals("R-0002")).findFirst().orElseThrow().state());
        } finally { d.stop(); }
    }

    @Test
    void theSettingsPageListsReadersAndInvitesOne(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        String owner = Patrons.issueToken(store, "did:key:owner");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = client();
        String base = "http://127.0.0.1:" + d.port(), ownerCookie = Pages.COOKIE + "=" + owner;
        try {
            var settings = get(c, base + "/settings", ownerCookie);
            assertTrue(settings.body().contains("<h2>Readers</h2>") && settings.body().contains("did:key:owner") && settings.body().contains("<i>anyone not listed</i></td><td>deny"), settings.body());
            var invited = post(c, base + "/settings", "op=invite&name=Eve+Example&level=write", ownerCookie);
            assertEquals(200, invited.statusCode());
            Matcher m = Pattern.compile("/join\\?code=(R-\\d+\\.[A-Za-z0-9_-]+)").matcher(invited.body());
            assertTrue(m.find(), "the link is on the page, once: " + invited.body());
            assertTrue(invited.body().contains("Eve Example <small>did:key:person-"), invited.body());
            assertEquals(200, get(c, base + "/join?code=" + m.group(1), null).statusCode());
            assertEquals(303, post(c, base + "/join", "code=" + m.group(1) + "&password=eve-secret-1&again=eve-secret-1", null).statusCode());
            String did = Patrons.load(store).listed().stream().filter(e -> e.name().equals("Eve Example")).findFirst().orElseThrow().did();
            assertTrue(get(c, base + "/settings", ownerCookie).body().contains("Eve Example <small>" + did + "</small></td><td>write</td><td>password</td>"), "the list says how she signs in");
            // a new link for a forgotten password: the old password stops, the link lets her choose another
            var renewed = post(c, base + "/settings", "op=reader-link&did=" + did, ownerCookie);
            Matcher m2 = Pattern.compile("/join\\?code=(R-\\d+\\.[A-Za-z0-9_-]+)").matcher(renewed.body());
            assertTrue(m2.find() && !m2.group(1).equals(m.group(1)), renewed.body());
            assertNull(Accounts.signIn(store, "Eve Example", "eve-secret-1"), "the old password is gone");
            assertEquals(303, post(c, base + "/join", "code=" + m2.group(1) + "&password=eve-secret-2&again=eve-secret-2", null).statusCode());
            assertNotNull(Accounts.signIn(store, "Eve Example", "eve-secret-2"));
            assertEquals(did, Accounts.byLogin(store, "eve example").did(), "the same reader, the same id");
            // a password the owner types for her
            assertEquals(303, post(c, base + "/settings", "op=reader-password&did=" + did + "&password=eve-secret-3", ownerCookie).statusCode());
            assertNotNull(Accounts.signIn(store, "Eve Example", "eve-secret-3"));
            var lowered = post(c, base + "/settings", "op=reader-read&did=" + did, ownerCookie);
            assertEquals(303, lowered.statusCode());
            assertEquals(Patrons.Level.read, Patrons.byDid(store, did).level());
            assertEquals(303, post(c, base + "/settings", "op=reader-remove&did=" + did, ownerCookie).statusCode());
            assertNull(Patrons.byDid(store, did));
            // a reader may not touch the list
            Patrons.set(store, "did:key:r", "Reader", Patrons.Level.read);
            String reader = Patrons.issueToken(store, "did:key:r");
            assertFalse(get(c, base + "/settings", Pages.COOKIE + "=" + reader).body().contains("<h2>Readers</h2>"));
            assertEquals(403, post(c, base + "/settings", "op=invite&name=Nobody&level=read", Pages.COOKIE + "=" + reader).statusCode());
        } finally { d.stop(); }
    }

    @Test
    void theInviteCommandPrintsTheLinkAndTheSignInPagesLineRuns(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        PrintStream o = System.out, e = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        int rc, rc2, rc3;
        try {
            rc = LibrarianCli.patron(store, new String[]{"librarian", "reader", "invite", "Fay Example", "write"});
            rc2 = LibrarianCli.patron(store, ReaderCommandsTest.typed(Patrons.INVITE_HOWTO));
            rc3 = LibrarianCli.patron(store, new String[]{"librarian", "reader", "invite"});
        } finally { System.setOut(o); System.setErr(e); }
        String out = bo.toString(StandardCharsets.UTF_8);
        assertEquals(0, rc, be.toString(StandardCharsets.UTF_8));
        assertTrue(out.contains("Fay Example may write. Send them this link; it works once, for 7 days:\n  http://127.0.0.1:4649/join?code=R-0001."), out);
        assertTrue(out.contains("Their id is did:key:person-"), out);
        assertEquals(0, rc2, "the sign-in page's invite line runs as written");
        assertEquals(Patrons.Level.read, Patrons.load(store).listed().stream().filter(x -> x.name().equals("A name")).findFirst().orElseThrow().level());
        assertEquals(2, rc3, "no name: the usage");
        assertTrue(be.toString(StandardCharsets.UTF_8).contains("researchzosho reader invite \"<name>\" [read|write]"));
    }
}
