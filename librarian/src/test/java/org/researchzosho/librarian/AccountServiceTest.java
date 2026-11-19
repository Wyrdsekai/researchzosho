package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The account service through its own port, as a browser and a program meet it. Two people get an account, each with a library, and
 * neither opens the other's. A library has a reader and a writer. A key reaches the library it was made for and no other. A sign-out
 * and a removal hold from the next request. People sign up through an identity provider where the service lets them. The libraries
 * here run inside the test's own process (a {@link LibraryRunner} of the test's), each under a statement-checking daemon as a real
 * one is; ChildLibrariesTest starts a real one.
 */
class AccountServiceTest {
    static final ObjectMapper M = new ObjectMapper();

    /** Libraries in this process: the daemon a real library runs, on a port of its own, answering to the service's statements. */
    static final class Here implements LibraryRunner {
        final String service; final Map<String, LibrarianDaemon> up = new ConcurrentHashMap<>();
        Here(String service) { this.service = service; }
        @Override public synchronized String ensure(AccountStore.Library lib) throws IOException {
            LibrarianDaemon d = up.get(lib.id());
            if (d == null) { d = LibrarianDaemon.start(new LibraryStore(Path.of(lib.folder()).resolve("library")), "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1, -1, new Vouching(service)); up.put(lib.id(), d); }
            return d.url();
        }
        @Override public void lost(String libraryId) { }
        @Override public void stopAll() { up.values().forEach(LibrarianDaemon::stop); up.clear(); }
    }

    /** A browser or a program: what it sends to prove who it is, and no redirect followed, so each answer is seen as it is. */
    static final class Caller {
        final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
        final String base; String cookie = "", key = "";
        Caller(String base) { this.base = base; }
        HttpRequest.Builder to(String path) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
            if (!cookie.isEmpty()) b.header("Cookie", cookie);
            if (!key.isEmpty()) b.header("Authorization", "Bearer " + key);
            return b;
        }
        HttpResponse<String> get(String path) throws Exception { return keep(c.send(to(path).header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString())); }
        HttpResponse<String> form(String path, String body) throws Exception { return keep(c.send(to(path).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())); }
        HttpResponse<String> json(String path, String body) throws Exception { return c.send(to(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()); }
        JsonNode status() throws Exception { HttpResponse<String> r = c.send(to("/v1/status").GET().build(), HttpResponse.BodyHandlers.ofString()); assertEquals(200, r.statusCode(), r.body()); return M.readTree(r.body()); }
        HttpResponse<String> keep(HttpResponse<String> r) {
            for (String sc : r.headers().allValues("Set-Cookie")) if (sc.startsWith(AccountService.COOKIE + "=")) { String v = sc.substring(0, sc.indexOf(';')); cookie = v.endsWith("=") ? "" : v; }
            return r;
        }
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String note(HttpResponse<String> r) { return URLDecoder.decode(r.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8); }
    static String found(String text, String regex) { Matcher m = Pattern.compile(regex).matcher(text); assertTrue(m.find(), regex + " in " + text.substring(0, Math.min(600, text.length()))); return m.group(1); }

    /** The join link opened and a password chosen: the person is signed in, in a browser of their own. */
    static Caller join(AccountService svc, String code, String password) throws Exception {
        Caller b = new Caller(svc.url());
        HttpResponse<String> page = b.get("/join?code=" + code);
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.body().contains("Choose a password"), page.body());
        HttpResponse<String> done = b.form("/join", "code=" + enc(code) + "&password=" + enc(password) + "&again=" + enc(password));
        assertEquals(303, done.statusCode(), done.body()); assertEquals("/", note(done));
        assertFalse(b.cookie.isEmpty(), "signed in");
        return b;
    }

    /** An invitation's link opened by someone new, who makes an account there with a name and a password: signed in, in a browser of their own. */
    static Caller joinAsNew(AccountService svc, String code, String name, String password) throws Exception {
        Caller b = new Caller(svc.url());
        HttpResponse<String> page = b.get("/join?code=" + code);
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.body().contains("New here? Choose the name you will sign in with, and a password."), page.body());
        HttpResponse<String> done = b.form("/join", "code=" + enc(code) + "&op=new&name=" + enc(name) + "&password=" + enc(password) + "&again=" + enc(password));
        assertEquals(303, done.statusCode(), done.body()); assertEquals("/", note(done));
        assertFalse(b.cookie.isEmpty(), "signed in");
        return b;
    }

    AccountStore store; AccountService svc; Here runner;

    void start(Path tmp) throws Exception {
        Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
        store = AccountStore.open(AccountService.root().resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> runner = new Here(self));
    }

    @AfterEach void stop() throws Exception {
        if (svc != null) svc.stop();
        if (store != null) store.close();
        Oidc.OVERRIDE = null; Oidc.forget();
        AccountService.SIGNUP_OVERRIDE = null;
        Config.set(AccountService.ROOT, "");
    }

    static final String ADD = "{\"op\":\"add\",\"question\":\"What did the first reader ask for at the desk on the day the library opened?\"}";

    @Test
    void twoAccountsASharedLibraryKeysSignOutAndRemoval(@TempDir Path tmp) throws Exception {
        start(tmp);
        // ---- two people, each a library, neither opens the other's ----
        AccountService.Made ann = svc.newAccount("Ann Example", "", true, "the test"), ben = svc.newAccount("Ben Example", "", false, "the test");
        Caller a = join(svc, ann.code(), "ann's long password"), b = join(svc, ben.code(), "ben's long password");
        String annLib = ann.library().id(), benLib = ben.library().id();
        assertNotEquals(annLib, benLib);
        assertEquals(annLib, a.status().path("library_id").asText()); assertEquals("Ann Example", a.status().path("library_name").asText());
        assertEquals(benLib, b.status().path("library_id").asText());
        HttpResponse<String> home = a.get("/");
        assertEquals(200, home.statusCode()); assertTrue(home.body().contains("Ann Example") && home.body().contains("href=\"/account\"") && home.body().contains("href=\"/logout\""), "the library's own page, naming the person");
        HttpResponse<String> open = a.form("/account", "op=open&library=" + benLib);
        assertTrue(note(open).contains("not one you can open"), note(open));
        assertEquals(annLib, a.status().path("library_id").asText(), "still in her own");
        assertTrue(note(a.form("/account", "op=key-new&library=" + benLib + "&level=read&label=x")).contains("not one you can open"), "no key for a library she cannot open");
        // a statement made for her library is no use at his, sent straight to it
        AccountStore.Signing k = store.signing();
        String forAnn = Statement.sign(k.key(), k.kid(), Statement.Claims.now(svc.url(), annLib, ann.account().id(), "Ann Example", Statement.KEEPER, Statement.SESSION));
        HttpResponse<String> direct = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(runner.up.get(benLib).url() + "/v1/status")).header(Statement.HEADER, forAnn).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, direct.statusCode()); assertTrue(direct.body().contains("made for another library"), direct.body());
        // nobody signed in: a program is told, a browser is sent to the sign-in page
        Caller nobody = new Caller(svc.url());
        assertEquals(403, nobody.json("/v1/frontier", ADD).statusCode());
        HttpResponse<String> toLogin = nobody.get("/");
        assertEquals(303, toLogin.statusCode()); assertEquals("/login", note(toLogin));
        assertTrue(nobody.get("/login").body().contains("An account here is made for you by whoever runs this service"));
        // ---- a library with a reader and a writer ----
        HttpResponse<String> forBen = a.form("/account", "op=member-let-in&library=" + annLib + "&name=" + enc("ben example") + "&level=read");
        assertTrue(forBen.body().contains("Send <b>ben example</b> this link"), "a link, whoever the name is: " + forBen.body());
        assertEquals(303, b.form("/join", "code=" + enc(found(forBen.body(), "/join\\?code=([A-Za-z0-9_-]+)")) + "&op=take").statusCode(), "Ben, signed in, takes it");
        HttpResponse<String> invited = a.form("/account", "op=member-let-in&library=" + annLib + "&name=" + enc("Cat New") + "&level=write");
        assertEquals(200, invited.statusCode()); assertTrue(invited.body().contains("Send <b>Cat New</b> this link"), invited.body());
        Caller c = joinAsNew(svc, found(invited.body(), "/join\\?code=([A-Za-z0-9_-]+)"), "Cat New", "cat's long password");
        assertEquals(annLib, c.status().path("library_id").asText(), "a new person starts in the library they were let into");
        assertEquals(AccountService.signup().equals("anyone") ? 1 : 0, store.librariesKeptBy(store.accountByLogin("Cat New").id()).size(), "and a library of their own only where anyone may sign up (the owner, 2026-11-18)");
        assertEquals(303, b.form("/account", "op=open&library=" + annLib).statusCode());
        assertEquals(303, c.form("/account", "op=open&library=" + annLib).statusCode());
        assertEquals(annLib, b.status().path("library_id").asText()); assertEquals(annLib, c.status().path("library_id").asText());
        HttpResponse<String> readerWrites = b.json("/v1/frontier", ADD);
        assertEquals(403, readerWrites.statusCode()); assertTrue(readerWrites.body().contains("may read this library and not write"), readerWrites.body());
        assertEquals(200, c.json("/v1/frontier", ADD).statusCode(), "the writer writes");
        assertTrue(a.json("/v1/frontier", "{\"op\":\"list\"}").body().contains("first reader ask for"), "and the keeper sees it in her library");
        // the library's own call for its people is answered by the service: the keeper sees them, a reader does not
        JsonNode people = M.readTree(a.json("/v1/access", "{\"op\":\"list\"}").body());
        assertEquals("Ann Example", people.path("keeper").asText(), people.toString());
        Set<String> who = new HashSet<>(); for (JsonNode p : people.path("people")) who.add(p.path("name").asText() + ":" + p.path("level").asText());
        assertEquals(Set.of("Ben Example:read", "Cat New:write"), who);
        assertEquals(403, b.json("/v1/access", "{\"op\":\"list\"}").statusCode());
        // what a writer puts in is shown as text, never as a page's own script, to the keeper who reads it
        HttpResponse<String> planted = c.json("/v1/frontier", "{\"op\":\"add\",\"question\":\"Is <script>fetch('/account')</script><img src=x onerror=alert(1)> a question the page would run?\"}");
        assertEquals(200, planted.statusCode(), planted.body());
        String questions = a.get("/questions").body();
        assertTrue(questions.contains("a question the page would run"), "the question is on the page");
        assertFalse(questions.contains("<script>fetch('/account')") || questions.contains("<img src=x onerror"), "as text, escaped");
        // ---- keys ----
        HttpResponse<String> made = a.form("/account", "op=key-new&library=" + annLib + "&level=write&label=" + enc("an agent"));
        Caller agent = new Caller(svc.url()); agent.key = found(made.body(), "<pre>(rz_[A-Za-z0-9_-]+)</pre>");
        assertFalse(a.get("/account").body().contains(agent.key), "shown once, never again");
        assertEquals(annLib, agent.status().path("library_id").asText());
        assertEquals(200, agent.json("/v1/frontier", ADD).statusCode());
        Caller reads = new Caller(svc.url()); reads.key = found(a.form("/account", "op=key-new&library=" + annLib + "&level=read&label=reads").body(), "<pre>(rz_[A-Za-z0-9_-]+)</pre>");
        assertEquals(annLib, reads.status().path("library_id").asText());
        HttpResponse<String> readKey = reads.json("/v1/frontier", ADD);
        assertEquals(403, readKey.statusCode()); assertTrue(readKey.body().contains("This key reads only"), readKey.body());
        assertTrue(note(b.form("/account", "op=key-new&library=" + annLib + "&level=write&label=x")).contains("reads only"), "a reader's key for it reads only");
        Caller bensKey = new Caller(svc.url()); bensKey.key = found(b.form("/account", "op=key-new&library=" + annLib + "&level=read&label=mine").body(), "<pre>(rz_[A-Za-z0-9_-]+)</pre>");
        assertEquals(annLib, bensKey.status().path("library_id").asText());
        Caller wrong = new Caller(svc.url()); wrong.key = "rz_" + "x".repeat(43);
        assertEquals(403, wrong.json("/v1/frontier", ADD).statusCode());
        // over MCP with the key: the library's tools, without the machine's
        JsonNode tools = M.readTree(agent.json("/rpc", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").body()).path("result").path("tools");
        Set<String> names = new HashSet<>(); for (JsonNode t : tools) names.add(t.path("name").asText());
        assertTrue(names.contains("library_ask") && !names.contains("library_share") && !names.contains("library_models"), names.toString());
        assertTrue(agent.json("/rpc", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"library_status\",\"arguments\":{}}}").body().contains(annLib));
        // a form sent from another site with her cookie is not carried out
        HttpResponse<String> crossSite = a.c.send(a.to("/account").header("Content-Type", "application/x-www-form-urlencoded").header("Origin", "https://elsewhere.example").POST(HttpRequest.BodyPublishers.ofString("op=key-new&library=" + annLib + "&level=write&label=stolen")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, crossSite.statusCode()); assertFalse(crossSite.body().contains("rz_"));
        assertEquals(403, a.c.send(a.to("/v1/frontier").header("Content-Type", "text/plain").header("Sec-Fetch-Site", "cross-site").POST(HttpRequest.BodyPublishers.ofString(ADD)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        // ---- a removal and a sign-out hold from the next request ----
        assertTrue(note(a.form("/account", "op=member-remove&library=" + annLib + "&account=" + ben.account().id())).contains("Removed"));
        assertEquals(benLib, b.status().path("library_id").asText(), "his browser is back in his own library");
        HttpResponse<String> goneKey = bensKey.json("/v1/frontier", "{\"op\":\"list\"}");
        assertEquals(403, goneKey.statusCode(), goneKey.body());
        assertTrue(note(a.form("/account", "op=key-revoke&id=" + store.keys(ann.account().id()).stream().filter(x -> x.label().equals("an agent")).findFirst().orElseThrow().id())).contains("revoked"));
        assertEquals(403, agent.json("/v1/frontier", "{\"op\":\"list\"}").statusCode(), "a revoked key is refused at once");
        String was = a.cookie;
        assertEquals(200, a.get("/logout").statusCode(), "a GET asks; it signs nobody out");
        assertEquals(303, a.form("/logout", "").statusCode());
        a.cookie = was;
        assertEquals(403, a.json("/v1/frontier", "{\"op\":\"list\"}").statusCode(), "signed out: the old cookie is nobody");
        assertEquals(303, a.get("/").statusCode());
        // signing in again with the name and the password
        a.cookie = "";
        assertEquals(403, a.form("/login", "name=" + enc("Ann Example") + "&password=wrong-password").statusCode());
        assertEquals(303, a.form("/login", "name=" + enc("ann example") + "&password=" + enc("ann's long password")).statusCode());
        assertEquals(annLib, a.status().path("library_id").asText());
    }

    @Test
    void peopleSignUpThroughTheProviderWhereTheServiceLetsThem(@TempDir Path tmp) throws Exception {
        start(tmp);
        OidcTest.Stub stub = new OidcTest.Stub();
        Oidc.OVERRIDE = OidcTest.settings(stub, "example.org");
        try {
            Caller one = new Caller(svc.url());
            assertTrue(one.get("/login").body().contains("Sign in with Example Workspace"));
            // by invitation (as shipped): a stranger the provider vouches for gets no account
            HttpResponse<String> refused = through(stub, one, "sub-ann", "ann@example.org", "Ann Example");
            assertEquals(303, refused.statusCode(), "back on the sign-in page, with the reason");
            assertTrue(one.get(refused.headers().firstValue("Location").orElse("")).body().contains("No account here is yours yet"), refused.headers().map().toString());
            assertTrue(store.accounts().isEmpty());
            // anyone: two people sign up, each gets an account and a library of their own
            AccountService.SIGNUP_OVERRIDE = "anyone";
            assertEquals(303, through(stub, one, "sub-ann", "ann@example.org", "Ann Example").statusCode());
            Caller two = new Caller(svc.url());
            assertEquals(303, through(stub, two, "sub-ben", "ben@elsewhere.example", "Ben Example").statusCode());
            assertEquals(2, store.accounts().size()); assertEquals(2, store.libraries().size());
            String annLib = one.status().path("library_id").asText(), benLib = two.status().path("library_id").asText();
            assertNotEquals(annLib, benLib);
            assertEquals("Ann Example", one.status().path("library_name").asText());
            assertTrue(note(one.form("/account", "op=open&library=" + benLib)).contains("not one you can open"));
            // the same person signing in again is the same account, in the same library
            Caller again = new Caller(svc.url());
            assertEquals(303, through(stub, again, "sub-ann", "ann@example.org", "Ann Example").statusCode());
            assertEquals(annLib, again.status().path("library_id").asText()); assertEquals(2, store.accounts().size());
            // domain: only the allowed mail domains
            AccountService.SIGNUP_OVERRIDE = "domain";
            refusedToSignIn(through(stub, new Caller(svc.url()), "sub-cat", "cat@elsewhere.example", "Cat"));
            assertEquals(303, through(stub, new Caller(svc.url()), "sub-dan", "dan@example.org", "Dan").statusCode());
            // an account made for a person by their mail address is theirs at their first sign-in, whatever the sign-up rule
            AccountService.SIGNUP_OVERRIDE = "invited";
            AccountService.Made eve = svc.newAccount("Eve Invited", "eve@elsewhere.example", false, "the test");
            Caller e = new Caller(svc.url());
            assertEquals(303, through(stub, e, "sub-eve", "eve@elsewhere.example", "Eve").statusCode());
            assertEquals(eve.library().id(), e.status().path("library_id").asText());
            // a mail address the provider did not verify proves nothing
            stub.verified = false;
            AccountService.Made fay = svc.newAccount("Fay Unverified", "fay@elsewhere.example", false, "the test");
            refusedToSignIn(through(stub, new Caller(svc.url()), "sub-fay", "fay@elsewhere.example", "Fay"));
            // but her join link, opened in the browser that then signs in through the provider from it, makes that sign-in hers
            Caller f = new Caller(svc.url());
            assertEquals(200, f.get("/join?code=" + fay.code()).statusCode());
            HttpResponse<String> joinPage = f.form("/join", "code=" + fay.code() + "&op=provider");   // "Sign in with Example Workspace" pressed on it (round C, SEC 12)
            String join = joinPage.headers().allValues("Set-Cookie").stream().filter(sc -> sc.startsWith(AccountPages.JOIN + "=")).findFirst().orElseThrow();
            f.cookie = join.substring(0, join.indexOf(';'));
            assertEquals(303, through(stub, f, "sub-fay", "fay@elsewhere.example", "Fay").statusCode());
            assertEquals(fay.library().id(), f.status().path("library_id").asText());
        } finally { stub.s.stop(0); }
    }

    /** One sign-in through the stub provider as this person; the callback's answer. */
    /** A sign-in through the provider refused: back on the sign-in page with the reason, and nobody signed in (round C). */
    static void refusedToSignIn(HttpResponse<String> r) {
        assertEquals(303, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Location").orElse("").startsWith("/login?note="), r.headers().map().toString());
        assertTrue(r.headers().allValues("Set-Cookie").stream().noneMatch(c -> c.startsWith(AccountService.COOKIE + "=") && !c.startsWith(AccountService.COOKIE + "=;")), "nobody signed in");
    }

    static HttpResponse<String> through(OidcTest.Stub stub, Caller b, String sub, String email, String name) throws Exception {
        stub.sub = sub; stub.email = email; stub.name = name;
        String keep = b.cookie;
        HttpResponse<String> go = b.get("/login/oidc");
        assertEquals(303, go.statusCode(), go.body());
        Map<String, String> q = OidcTest.query(note(go).isEmpty() ? go.headers().firstValue("Location").orElse("") : go.headers().firstValue("Location").orElse(""));
        stub.nonce = q.get("nonce");
        // the browser that began it: the cookie with its state's hash comes back with the provider's answer (round C, SEC 4)
        String begun = go.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith(AccountPages.SIGNIN + "=")).map(v -> v.substring(0, v.indexOf(';'))).findFirst().orElse("");
        b.cookie = keep.isEmpty() ? begun : begun.isEmpty() ? keep : keep + "; " + begun;
        HttpResponse<String> back = b.get("/login/oidc/callback?code=code-1&state=" + q.get("state"));
        if (b.cookie.contains(AccountPages.SIGNIN + "=")) b.cookie = keep;
        return back;
    }
}
