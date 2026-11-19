package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.Version;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * People making their own libraries, and the operator's page completed (0.8.0, stage 4, step 4). A person makes libraries up to the
 * number an account may keep (3 unless set, an account's own cap above it), not when their storage is full, and lets one go after a
 * page of its own that asks; each is in the audit log. Whoever runs the service makes accounts (the join link shown once), suspends
 * them, lets them back, deletes them after the grace or at once, keeps one being deleted; makes a library for an account, moves one,
 * hands it over, lets it go, deletes it, takes one back in; each act asked on a page of its own first, nothing done until it is
 * confirmed, and each written to the log, which the page shows filtered by account, act and days. Nobody else opens the page. With
 * RZ_SCREENSHOTS naming a folder, the pages are written there as they were served, to be looked at.
 */
class OperatorPagesTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    AccountStore store; AccountService svc; Path root;

    @AfterEach void stop() throws Exception {
        if (svc != null) svc.stop();
        if (store != null) store.close();
        Config.set(AccountService.ROOT, "");
    }

    HttpResponse<String> get(String cookie, String path) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Cookie", AccountService.COOKIE + "=" + cookie).header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String cookie, String path, String form) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Cookie", AccountService.COOKIE + "=" + cookie).header("Sec-Fetch-Site", "same-origin")
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    /** Where a page sends the browser, decoded, without the mark that says the service wrote its note (round C, SEC 12). */
    static String note(HttpResponse<String> r) { return URLDecoder.decode(r.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8).replaceAll("&ns=[^#&]*", ""); }

    /** An act asked on the operator's page: first its question, which changes nothing, then the same with the yes. The answer to the yes. */
    HttpResponse<String> act(String cookie, String form, String asked) throws Exception {
        HttpResponse<String> q = post(cookie, AccountPages.OPERATOR, form);
        assertEquals(200, q.statusCode(), form + ": " + q.body());
        assertTrue(q.body().contains(asked) && q.body().contains("name=\"confirm\" value=\"yes\"") && q.body().contains("Back, and do nothing"), form + " asks first: " + q.body());
        return post(cookie, AccountPages.OPERATOR, form + "&confirm=yes");
    }

    List<String> acts(String account) throws Exception { return store.audits(account, 0, 500).stream().map(AccountStore.Audit::act).toList(); }

    @Test
    void aPersonMakesLibrariesUpToTheNumberAnAccountKeepsAndLetsOneGoAfterItIsAsked(@TempDir Path tmp) throws Exception {
        root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, issuer -> new Nodes(store, issuer, null));
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test"), bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String a = ann.account().id(), annCookie = store.newSession(a, "", "a browser", 1), boCookie = store.newSession(bo.account().id(), "", "a browser", 1);
        String page = get(annCookie, "/account").body();
        assertTrue(page.contains("Make a library") && page.contains("name=\"op\" value=\"library-make\"") && page.contains("You keep 1 of the 3 libraries an account may keep here.") && page.contains("What they hold together counts against your 2.0 GB of storage."), page);
        HttpResponse<String> made = post(annCookie, "/account", "op=library-make&title=" + enc("Harbour notes"));
        assertEquals("/account?note=The library Harbour notes is made. Open it from your libraries.", note(made));
        AccountStore.Library harbour = store.librariesKeptBy(a).stream().filter(l -> l.title().equals("Harbour notes")).findFirst().orElseThrow();
        assertTrue(Files.isRegularFile(LibraryPlaces.library(harbour).resolve("catalog").resolve("library.md")), "a library like any");
        assertTrue(store.audits(a, 0, 50).stream().anyMatch(e -> e.act().equals("library_made") && e.library().equals(harbour.id()) && e.actor().equals(a)));
        assertTrue(note(post(annCookie, "/account", "op=library-make&title=")).contains("Give the new library a title."));
        post(annCookie, "/account", "op=library-make&title=" + enc("Lighthouse letters"));
        assertEquals(3, store.librariesKeptBy(a).size());
        // at the number an account keeps: refused, nothing made, and the page says so in place of the form
        HttpResponse<String> fourth = post(annCookie, "/account", "op=library-make&title=" + enc("One too many"));
        assertTrue(note(fourth).contains("You keep as many libraries as an account may keep here (3). Let one go, or ask whoever runs this service for more."), note(fourth));
        assertEquals(3, store.librariesKeptBy(a).size());
        page = get(annCookie, "/account").body();
        assertFalse(page.contains("value=\"library-make\""));
        // an account's own cap above it; and a full storage refuses a new one
        store.setCap(a, Caps.LIBRARIES, 5);
        post(annCookie, "/account", "op=library-make&title=" + enc("A fourth"));
        assertEquals(4, store.librariesKeptBy(a).size());
        store.setCap(a, Caps.STORAGE_MB, 0);
        assertTrue(note(post(annCookie, "/account", "op=library-make&title=" + enc("A fifth"))).contains(CapsSpending.storageFull(0)));
        assertEquals(4, store.librariesKeptBy(a).size());
        store.unsetCap(a, Caps.STORAGE_MB);

        // letting one go: asked on a page of its own, nothing done until the yes; nobody else lets it go
        HttpResponse<String> asked = post(annCookie, "/account", "op=library-let-go&library=" + harbour.id());
        assertEquals(200, asked.statusCode());
        assertTrue(asked.body().contains("Let Harbour notes go?") && asked.body().contains("Harbour notes</b> will go out of service at once") && asked.body().contains("name=\"confirm\" value=\"yes\""), asked.body());
        assertTrue(store.library(harbour.id()).active(), "nothing is done by the question");
        assertTrue(note(post(boCookie, "/account", "op=library-let-go&library=" + harbour.id() + "&confirm=yes")).contains("Only the person who keeps a library lets it go."));
        HttpResponse<String> gone = post(annCookie, "/account", "op=library-let-go&library=" + harbour.id() + "&confirm=yes");
        assertTrue(note(gone).startsWith("/account?note=Harbour notes is let go. It is kept aside until "), note(gone));
        assertEquals("removed", store.library(harbour.id()).state());
        assertTrue(store.audits(a, 0, 50).stream().anyMatch(e -> e.act().equals("library_deleted") && e.library().equals(harbour.id())));
        assertEquals(3, store.librariesKeptBy(a).size());
    }

    @Test
    void whoeverRunsTheServiceActsOnAccountsAndLibrariesEachAskedFirstAndEachWrittenDown(@TempDir Path tmp) throws Exception {
        root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, issuer -> new Nodes(store, issuer, null));
        AccountService.Made olive = AccountService.newAccount(store, root, "Olive Operator", "", true, "the test"), ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test"),
                bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String o = olive.account().id(), a = ann.account().id(), b = bo.account().id();
        String oc = store.newSession(o, "", "a browser", 1), annCookie = store.newSession(a, "", "a browser", 1);
        store.nodeSeen("node_one", "http://10.0.0.3:4690", root.toString(), Version.string());
        store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), a, ann.library().id(), "", "storage", "operator", "", 0, 0, 0, 12, 12_000_000L, 0, false)));

        assertEquals(403, get(annCookie, AccountPages.OPERATOR).statusCode(), "nobody else opens it");
        assertEquals(403, post(annCookie, AccountPages.OPERATOR, "op=account-suspend&account=" + b + "&confirm=yes").statusCode());
        assertEquals("active", store.account(b).state());
        String page = get(oc, AccountPages.OPERATOR).body();
        for (String shown : List.of("href=\"#libraries\"", "id=\"accounts\"", "id=\"libraries\"", "id=\"nodes\"", "id=\"caps\"", "id=\"audit\"", "Make an account", "Make a library for", "Take in a library",
                "value=\"account-suspend\"", "value=\"library-move\"", "node_one", "http://10.0.0.3:4690", "answers (", "12 MB", "asleep", "no copy", "your own account", "libraries an account keeps"))
            assertTrue(page.contains(shown), "the operator's page shows " + shown);

        // an account made: asked, then made, its join link shown once
        HttpResponse<String> made = act(oc, "op=account-make&name=" + enc("Cy Example") + "&mail=&admin=", "Make an account for Cy Example?");
        assertEquals(200, made.statusCode());
        assertTrue(made.body().contains("/join?code=") && made.body().contains("It is not shown again."), made.body());
        AccountStore.Account cy = store.accountByLogin("Cy Example");
        assertNotNull(cy);
        assertTrue(store.audits(cy.id(), 0, 50).stream().anyMatch(e -> e.act().equals("account_made") && e.actor().equals(o)));
        assertTrue(act(oc, "op=account-link&account=" + b, "A new join link for Bo Example?").body().contains("/join?code="));
        assertTrue(acts(b).contains("join_link_made"));

        // suspend, let back, delete after the grace, keep, remove at once
        assertTrue(note(post(oc, AccountPages.OPERATOR, "op=account-suspend&account=" + o + "&confirm=yes")).contains("Your own account is suspended or deleted from the command line"));
        assertEquals("/operator?note=Bo Example is suspended.#accounts", note(act(oc, "op=account-suspend&account=" + b, "Suspend Bo Example")));
        assertEquals("suspended", store.account(b).state());
        act(oc, "op=account-unsuspend&account=" + b, "Let Bo Example back");
        assertEquals("active", store.account(b).state());
        // the operator's deletion is at once unless they give it the days of grace (the owner, 2026-11-18): here, with the grace
        assertTrue(note(act(oc, "op=account-delete&account=" + b + "&when=grace", "will be frozen at once and removed on")).startsWith("/operator?note=Bo Example is frozen, and removed on "));
        assertEquals("deleting", store.account(b).state());
        act(oc, "op=account-keep&account=" + b, "Keep Bo Example");
        assertEquals("active", store.account(b).state());
        assertTrue(acts(b).containsAll(List.of("account_suspended", "account_unsuspended", "deletion_asked", "deletion_cancelled")), acts(b).toString());

        // libraries: made for an account, moved, handed over, let go, deleted, taken back, deleted at once
        act(oc, "op=library-make&account=" + b + "&title=" + enc("Bo's second"), "Make a library for Bo Example?");
        AccountStore.Library second = store.librariesKeptBy(b).stream().filter(l -> l.title().equals("Bo's second")).findFirst().orElseThrow();
        HttpResponse<String> where = post(oc, AccountPages.OPERATOR, "op=library-move&library=" + second.id() + "&confirm=yes");
        assertEquals(200, where.statusCode(), "a move needs its folder: asked for it on its page");
        assertTrue(where.body().contains("name=\"to\""));
        Path other = tmp.resolve("other-root");
        assertTrue(note(act(oc, "op=library-move&library=" + second.id() + "&to=" + enc(other.toString()), "Move Bo's second")).contains("is at " + other), "moved");
        assertTrue(store.library(second.id()).folder().startsWith(other.toString()));
        store.setMember(second.id(), a, "read", o);
        act(oc, "op=library-handover&library=" + second.id() + "&to=" + a, "Hand Bo's second over");
        assertEquals(a, store.library(second.id()).owner());
        Path free = tmp.resolve("let-go");
        act(oc, "op=library-release&library=" + second.id() + "&to=" + enc(free.toString()), "Release Bo's second");
        assertEquals("released", store.library(second.id()).state());
        assertTrue(Files.isRegularFile(free.resolve("catalog").resolve("library.md")), "an ordinary library there");
        // a library with no copy is never offered to be brought back from one, and asking for it says so
        assertFalse(get(oc, AccountPages.OPERATOR).body().contains("name=\"op\" value=\"library-restore\""), "no copy, no button");
        HttpResponse<String> none = post(oc, AccountPages.OPERATOR, "op=library-restore&library=" + bo.library().id());
        assertEquals("/operator?note=Bo Example has no copy to be brought back from.#libraries", note(none), "no question page that would say \"its copy of no copy\"");
        AccountStore.Library bos = bo.library();
        act(oc, "op=library-delete&library=" + bos.id(), "Delete Bo Example");
        assertEquals("removed", store.library(bos.id()).state());
        page = get(oc, AccountPages.OPERATOR).body();
        assertTrue(page.contains("Out of service") && page.contains("value=\"library-take\""), "a deleted one can be taken back within its grace");
        String aside = LibraryPlaces.library(Path.of(store.asides(bos.id()).get(0).path())).toString();
        act(oc, "op=library-take&path=" + enc(aside) + "&account=" + b, "Take in a library for Bo Example?");
        assertTrue(store.library(bos.id()).active(), "taken back");
        assertEquals(b, store.library(bos.id()).owner());
        act(oc, "op=library-delete&library=" + bos.id() + "&when=now", "Remove Bo Example now");
        assertEquals("removed", store.library(bos.id()).state());
        assertFalse(Files.exists(LibraryPlaces.folder(store.library(bos.id()))), "removed at once");
        assertTrue(store.audits(null, 0, 500).stream().map(AccountStore.Audit::act).toList().containsAll(List.of("library_made", "library_moved", "library_handed_over", "library_released", "library_deleted", "library_taken_in")));

        // removed at once, without the grace
        act(oc, "op=account-delete&account=" + cy.id() + "&when=now", "Remove Cy Example now");
        assertEquals("removed", store.account(cy.id()).state());

        // the log, filtered by account, act and days
        String log = get(oc, AccountPages.OPERATOR + "?audit_account=" + b + "&audit_act=account_suspended&audit_days=1").body();
        String table = log.substring(log.indexOf("id=\"audit\""));
        assertEquals(1, table.split("<tr><td data-l=\"when\">", -1).length - 1, "one entry: Bo suspended, today");
        assertTrue(table.contains("account suspended") && table.contains("Bo Example") && table.contains("Olive Operator"), table);

        // every question page says what will happen; nothing it says is already so
        for (String form : List.of("op=account-suspend&account=" + a, "op=account-unsuspend&account=" + a, "op=account-delete&account=" + a, "op=account-delete&account=" + a + "&when=now",
                "op=account-make&name=" + enc("Dee Example") + "&mail=&admin=yes", "op=account-link&account=" + a, "op=library-make&account=" + a + "&title=" + enc("A third"),
                "op=library-move&library=" + ann.library().id(), "op=library-release&library=" + ann.library().id(), "op=library-delete&library=" + ann.library().id(),
                "op=library-delete&library=" + ann.library().id() + "&when=now", "op=library-take&path=" + enc(tmp.resolve("x").toString()) + "&account=" + a)) {
            String q = post(oc, AccountPages.OPERATOR, form).body();
            String said = q.substring(q.indexOf("<main>"), q.indexOf("<form method=\"post\""));
            assertTrue(said.contains(" will "), form + " says what will happen: " + said);
            assertFalse(said.matches("(?s).*</b> (is|goes|gets) .*"), form + ": " + said);
        }

        String dir = System.getenv("RZ_SCREENSHOTS");
        if (dir != null && !dir.isBlank()) {
            Files.createDirectories(Path.of(dir));
            Files.writeString(Path.of(dir, "operator.html"), get(oc, AccountPages.OPERATOR + "?note=" + enc("Bo Example is let back.")).body());
            Files.writeString(Path.of(dir, "operator-confirm.html"), post(oc, AccountPages.OPERATOR, "op=account-delete&account=" + a).body());
            Files.writeString(Path.of(dir, "account.html"), get(annCookie, "/account").body());
        }
    }

    /** The text of a page's cells, tags taken away and the escapes read: what a person reads there. */
    static List<String> cells(String html, String from, String to) {
        int a = html.indexOf(from), z = to == null ? html.length() : html.indexOf(to, a);
        String part = html.substring(a, z < 0 ? html.length() : z);
        List<String> out = new ArrayList<>();
        for (Matcher m = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL).matcher(part); m.find(); )
            out.add(m.group(1).replaceAll("<[^>]+>", "").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"));
        return out;
    }

    @Test
    void everyEntryOfTheLogSaysItsDetailInWordsWithNoBraceAndNoQuoteMark(@TempDir Path tmp) throws Exception {
        root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, issuer -> new Nodes(store, issuer, null));
        AccountService.Made olive = AccountService.newAccount(store, root, "Olive Operator", "", true, "the test"), ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test"),
                bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String a = ann.account().id(), lib = ann.library().id();
        store.nodeSeen("node_one", "http://10.0.0.3:4690", root.toString(), Version.string());
        AccountActs.By by = new AccountActs.By(olive.account().id(), "session", "127.0.0.1");
        // every detail as the code writes it, each with the act that writes it
        Object[][] entries = {
                {"key_made", a, lib, AccountActs.detail().put("level", "write"), "to read and write"},
                {"member_let_in", a, lib, AccountActs.detail().put("level", "read"), "to read only"},
                {"own_key_set", a, "", AccountActs.detail().put("host", "brave"), "for "},
                {"cap_changed", a, "", AccountActs.detail().put("name", Caps.MONTH_DOLLARS).put("value", 12_500_000L), "dollars a month set to $12.50"},
                {"cap_changed", a, "", AccountActs.detail().put("name", Caps.FETCHES).put("value", "default"), "pages fetched a month back to the service's"},
                {"deletion_asked", a, "", AccountActs.detail().put("days", 30), "with 30 days of grace"},
                {"deletion_asked", a, "", AccountActs.detail().put("days", 0), "removed at once"},
                {"library_deleted", a, lib, AccountActs.detail().put("now", true), "removed at once"},
                {"library_moved", a, lib, AccountActs.detail().put("to", "/srv/rz/other"), "to /srv/rz/other"},
                {"library_released", a, lib, AccountActs.detail().put("to", "/srv/rz/harbour"), "to /srv/rz/harbour"},
                {"library_handed_over", bo.account().id(), lib, AccountActs.detail().put("from", a), "kept before by Ann Example"},
                {"library_restored", a, lib, AccountActs.detail().put("node", "node_one"), "on http://10.0.0.3:4690"},
                {"library_restored", a, lib, AccountActs.detail().put("node", "node_gone"), "on another machine"},
                {"library_taken_in", a, lib, AccountActs.detail().put("archive_from", "https://research.example.org"), "from its archive of https://research.example.org"},
                {"limit_reached", a, "", AccountActs.detail().put("limit", "api"), "the limit on a program's requests"},
                {"signin", a, "", AccountActs.detail().put("something_kept", "{\"x\":1}"), "signed in"}};
        for (Object[] e : entries) AccountActs.audit(store, by, (String) e[0], (String) e[1], (String) e[2], (ObjectNode) e[3]);

        String op = get(store.newSession(olive.account().id(), "", "a browser", 1), AccountPages.OPERATOR + "?audit_days=1").body();
        List<String> what = cells(op, "id=\"audit\"", null);
        String mine = get(store.newSession(a, "", "a browser", 1), "/account").body();
        List<String> recent = cells(mine, "Recent activity", "</details>");
        AccountsCliTest.Ran cli = AccountsCliTest.run("audit", "--days", "1");
        assertEquals(0, cli.code(), cli.err());
        for (Object[] e : entries) {
            String words = (String) e[4];
            assertTrue(what.stream().anyMatch(t -> t.contains(words)), "the operator's page says " + words + ": " + what);
            if (!e[0].equals("signin")) assertTrue(cli.out().contains(words), "the command line says " + words);   // it names each act by its code, and says the detail in words
            if (!e[1].equals(a)) continue;
            assertTrue(recent.stream().anyMatch(t -> t.contains(words)), "Ann's Recent activity says " + words + ": " + recent);
        }
        for (List<String> surface : List.of(what, recent, List.of(cli.out().split("\n"))))
            for (String t : surface) {
                assertFalse(t.contains("{") || t.contains("}") || t.contains("\""), "a brace or a quote mark: " + t);
                assertFalse(t.contains(a) || t.contains("node_one") || t.contains("node_gone"), "an id: " + t);
            }
    }
}
