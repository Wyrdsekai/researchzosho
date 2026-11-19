package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Letting someone into a library is an invitation (0.8.0 review, round A, the owner's word): a link for the library at a level, named
 * by what the keeper typed, that matches no account and makes none. The keeper is told the same whoever the name is; whoever opens the
 * link joins (signed in), or signs in first, or makes an account of their own choosing; it works once, while it stands; a withdrawn
 * or expired one does nothing; the keeper opening it lets nobody in. Accounts mode only: a library of one's own invites as before.
 */
class InvitationTest {
    AccountStore store; AccountService svc; Path root;

    @AfterEach void stop() throws Exception {
        if (svc != null) svc.stop();
        if (store != null) store.close();
        AccountService.SIGNUP_OVERRIDE = null;
        Config.set(AccountService.ROOT, "");
    }

    void start(Path tmp) throws Exception {
        root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        AccountService.SIGNUP_OVERRIDE = "invited";
    }

    /** An account, signed in in a browser of its own through its join link. */
    AccountServiceTest.Caller person(String name) throws Exception {
        AccountService.Made m = svc.newAccount(name, "", false, "the test");
        return AccountServiceTest.join(svc, m.code(), name.toLowerCase().replace(' ', '-') + "'s long password");
    }

    static String enc(String s) { return AccountServiceTest.enc(s); }
    static String code(String body) { return AccountServiceTest.found(body, "/join\\?code=([A-Za-z0-9_-]+)"); }

    /** The keeper's page after letting {@code name} in, with the name and the link's code taken out: what tells one let-in from another. */
    static String shown(HttpResponse<String> r, String name) { return r.body().replace(name, "NAME").replaceAll("code=[A-Za-z0-9_-]+", "code=CODE"); }

    Set<String> people(String library) throws Exception { return store.members(library).stream().map(AccountStore.Member::account).collect(Collectors.toSet()); }

    /** What the keeper is shown and answered when letting {@code typed} into her library, on a service of its own where Bo has an account or not. */
    String[] toldTheKeeper(Path dir, boolean boHere, String typed) throws Exception {
        start(dir);
        try {
            AccountService.Made ann = svc.newAccount("Ann Example", "", false, "the test");
            AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
            if (boHere) svc.newAccount("Bo Example", "bo@example.org", false, "the test");
            int accounts = store.accounts().size();
            HttpResponse<String> r = a.form("/account", "op=member-let-in&library=" + ann.library().id() + "&name=" + enc(typed) + "&level=read");
            assertEquals(200, r.statusCode(), r.body());
            // through a library, a program's key lets people in; a browser's session does it on the account page (round F, S17)
            ObjectNode j = AccountPages.access(svc, ann.library(), ann.account(), Statement.KEEPER, Statement.KEY, Statement.M.createObjectNode().put("op", "invite").put("name", typed).put("level", "write"));
            assertThrows(ProtocolError.class, () -> AccountPages.access(svc, ann.library(), ann.account(), Statement.KEEPER, Statement.SESSION, Statement.M.createObjectNode().put("op", "invite").put("name", typed).put("level", "write")));
            assertEquals(Set.of(), people(ann.library().id()), "nobody is let in by a name");
            assertEquals(accounts, store.accounts().size(), "no account is made");
            return new String[]{same(r.body()), same(j.toString()), r.body()};
        } finally { svc.stop(); svc = null; store.close(); store = null; }
    }

    /** A page or an answer with what differs between two services of their own taken out: the libraries' ids, the address, the codes, the times. */
    static String same(String s) {
        // the keeper's recent acts, the same in both, in the order of their times: two of the same millisecond (her sign-in and her
        // password chosen through her join link) come in either order, so the rows are compared sorted
        int at = s.indexOf("<details class=\"fold\"><summary><h2>Recent activity"), body = at < 0 ? -1 : s.indexOf("<tbody>", at), end = body < 0 ? -1 : s.indexOf("</tbody>", body);
        if (end > 0) {
            List<String> rows = new ArrayList<>(List.of(s.substring(body + 7, end).split("(?=<tr>)")));
            Collections.sort(rows);
            s = s.substring(0, body + 7) + String.join("", rows) + s.substring(end);
        }
        return s.replaceAll("lib_[0-9a-f]{16}", "LIB").replaceAll("127\\.0\\.0\\.1:\\d+", "HOST").replaceAll("code=[A-Za-z0-9_-]+", "code=CODE")
                .replaceAll("value=\"[0-9a-f]{64}\"", "value=\"ID\"").replaceAll("\\d{1,2}:\\d{2}", "T");
    }

    @Test
    void theKeeperIsToldTheSameWhoeverTheNameIsAndNothingIsMatchedOrMade(@TempDir Path tmp) throws Exception {
        for (String typed : List.of("Bo Example", "bo@example.org", "bo example")) {
            String[] known = toldTheKeeper(tmp.resolve("with-bo-" + typed.hashCode()), true, typed), unknown = toldTheKeeper(tmp.resolve("without-bo-" + typed.hashCode()), false, typed);
            assertEquals(unknown[0], known[0], "the keeper's page, whether \"" + typed + "\" is an account here or not");
            assertEquals(unknown[1], known[1], "the library's call, the same");
            assertTrue(known[2].contains("Send <b>" + typed + "</b> this link. It works once, for 7 days."), known[2]);
        }
    }

    @Test
    void anInvitationLetsInOnceWhileItStandsAndTheKeeperOpeningItLetsNobodyIn(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = svc.newAccount("Ann Example", "", false, "the test");
        AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
        String lib = ann.library().id(), title = ann.library().title();
        AccountServiceTest.Caller bo = person("Bo Example"), cat = person("Cat Example");
        String boId = store.accountByLogin("Bo Example").id(), catId = store.accountByLogin("Cat Example").id();

        // signed in: one button, and in
        String c1 = code(a.form("/account", "op=member-let-in&library=" + lib + "&name=" + enc("Bo") + "&level=read").body());
        HttpResponse<String> page = bo.get("/join?code=" + c1);
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.body().contains("Ann Example invites you to " + title) && page.body().contains("name=\"op\" value=\"take\""), page.body());
        assertEquals(Set.of(), people(lib), "opening the link lets nobody in");
        HttpResponse<String> taken = bo.form("/join", "code=" + enc(c1) + "&op=take");
        assertEquals(303, taken.statusCode(), taken.body());
        assertEquals("read", store.level(store.library(lib), boId));
        assertEquals(lib, bo.status().path("library_id").asText(), "and the library opens");
        // once: the same link does nothing for another
        assertEquals(403, cat.get("/join?code=" + c1).statusCode());
        cat.form("/join", "code=" + enc(c1) + "&op=take");
        assertEquals(Set.of(boId), people(lib));

        // withdrawn: does nothing
        String c2 = code(a.form("/account", "op=member-let-in&library=" + lib + "&name=" + enc("Cat") + "&level=write").body());
        String id = store.invitations(lib).stream().filter(i -> i.label().equals("Cat")).findFirst().orElseThrow().id();
        assertTrue(a.get("/account").body().contains("value=\"invite-withdraw\""), "an open invitation is listed with Withdraw");
        HttpResponse<String> withdrawn = a.form("/account", "op=invite-withdraw&library=" + lib + "&id=" + id);
        assertEquals(303, withdrawn.statusCode());
        assertTrue(store.invitations(lib).isEmpty());
        assertEquals(403, cat.get("/join?code=" + c2).statusCode());
        cat.form("/join", "code=" + enc(c2) + "&op=take");
        assertEquals(Set.of(boId), people(lib));

        // expired: does nothing
        String c3 = store.newInvitation(lib, "read", "Late", ann.account().id(), 0);
        Thread.sleep(20);
        assertEquals(403, cat.get("/join?code=" + c3).statusCode());
        cat.form("/join", "code=" + enc(c3) + "&op=take");
        assertNull(store.level(store.library(lib), catId));

        // the keeper opens her own link: it lets nobody in, and stays open for the person it was made for
        String c4 = code(a.form("/account", "op=member-let-in&library=" + lib + "&name=" + enc("Cat") + "&level=write").body());
        HttpResponse<String> own = a.get("/join?code=" + c4);
        assertEquals(200, own.statusCode());
        assertFalse(own.body().contains("value=\"take\""), "no button for the keeper: " + own.body());
        a.form("/join", "code=" + enc(c4) + "&op=take");
        assertEquals(Set.of(boId), people(lib), "the keeper is not let in to her own library, and nobody else is");
        assertEquals(1, store.invitations(lib).size(), "the link still stands");
        assertEquals(303, cat.form("/join", "code=" + enc(c4) + "&op=take").statusCode());
        assertEquals(Set.of(boId, catId), people(lib));
        a.form("/join", "code=" + enc(c4) + "&op=take");
        bo.form("/join", "code=" + enc(c4) + "&op=take");
        assertEquals("write", store.level(store.library(lib), catId));
        assertEquals("read", store.level(store.library(lib), boId), "nobody let in twice, nothing changed by a used link");
        List<String> acts = store.audits(null, 0, 500).stream().map(AccountStore.Audit::act).toList();
        assertEquals(2, acts.stream().filter(x -> x.equals("member_let_in")).count(), acts.toString());
        assertTrue(acts.contains("member_invited") && acts.contains("invitation_withdrawn"), acts.toString());
    }

    @Test
    void someoneNewMakesAnAccountOfTheirOwnChoosingOrSignsInAndIsLetIn(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = svc.newAccount("Ann Example", "", false, "the test");
        AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
        String lib = ann.library().id();
        person("Bo Example");

        // not signed in: sign in, or a new account
        String c1 = code(a.form("/account", "op=member-let-in&library=" + lib + "&name=" + enc("Eve") + "&level=write").body());
        AccountServiceTest.Caller eve = new AccountServiceTest.Caller(svc.url());
        HttpResponse<String> page = eve.get("/join?code=" + c1);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Ann Example invites you to " + ann.library().title()) && page.body().contains("href=\"/login\"") && page.body().contains("value=\"new\""), page.body());
        HttpResponse<String> taken = eve.form("/join", "code=" + enc(c1) + "&op=new&name=" + enc("Bo Example") + "&password=" + enc("eve's long password") + "&again=" + enc("eve's long password"));
        assertEquals(200, taken.statusCode());
        assertTrue(taken.body().contains("Someone signs in here as Bo Example already. Choose another name."), taken.body());
        assertEquals(1, store.invitations(lib).size(), "the link still stands");
        HttpResponse<String> made = eve.form("/join", "code=" + enc(c1) + "&op=new&name=" + enc("Eve Example") + "&password=" + enc("eve's long password") + "&again=" + enc("eve's long password"));
        assertEquals(303, made.statusCode(), made.body());
        AccountStore.Account e = store.accountByLogin("Eve Example");
        assertEquals("", e.mail(), "no mail: nobody verified one");
        assertTrue(Passwords.check(e.passwordHash(), "eve's long password"));
        assertEquals("write", store.level(store.library(lib), e.id()));
        assertEquals(lib, eve.status().path("library_id").asText(), "signed in, in the library she was let into");
        assertTrue(store.invitations(lib).isEmpty());

        // a person with an account signs in from the link's page, and comes back to it
        String c2 = code(a.form("/account", "op=member-let-in&library=" + lib + "&name=" + enc("Bo") + "&level=read").body());
        AccountServiceTest.Caller bo = new AccountServiceTest.Caller(svc.url());
        bo.get("/join?code=" + c2);
        String joinCookie = "";
        HttpResponse<String> opened = new AccountServiceTest.Caller(svc.url()).form("/join", "code=" + c2 + "&op=signin");   // "Sign in" pressed on the link's page
        for (String sc : opened.headers().allValues("Set-Cookie")) if (sc.startsWith(AccountPages.JOIN + "=")) joinCookie = sc.substring(0, sc.indexOf(';'));
        assertFalse(joinCookie.isEmpty());
        bo.cookie = joinCookie;
        HttpResponse<String> signin = bo.form("/login", "name=" + enc("Bo Example") + "&password=" + enc("bo-example's long password"));
        assertEquals(303, signin.statusCode(), signin.body());
        assertEquals("/join?code=" + c2, AccountServiceTest.note(signin), "back to the link");

        // through the provider: an account of its own (sign-up closed, but invited), and in
        String c3 = code(a.form("/account", "op=member-let-in&library=" + lib + "&name=" + enc("Gus") + "&level=read").body());
        ObjectNode claims = Statement.M.createObjectNode().put("sub", "idp-gus").put("email", "gus@example.org").put("email_verified", true).put("name", "Gus Example");
        AccountStore.Account gus = AccountPages.fromProvider(svc, new Oidc.Proven("https://idp.example", claims), c3, "addr|203.0.113.9");
        assertEquals("Gus Example", gus.name());
        assertEquals("read", store.level(store.library(lib), gus.id()));
        assertNotEquals(ann.account().id(), gus.id());
        assertTrue(store.invitations(lib).stream().noneMatch(i -> i.label().equals("Gus")), "used");
    }

    @Test
    void theKeepersPageAndTheLibrarysCallListTheOpenInvitations(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = svc.newAccount("Ann Example", "", false, "the test");
        AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
        String lib = ann.library().id();
        a.form("/account", "op=member-let-in&library=" + lib + "&name=" + enc("Dee <b>Example</b>") + "&level=write");
        String page = a.get("/account").body();
        assertTrue(page.contains("Dee &lt;b&gt;Example&lt;/b&gt;") && !page.contains("Dee <b>Example</b>"), "listed, as text");
        JsonNode listed = AccountPages.access(svc, ann.library(), ann.account(), Statement.KEEPER, Statement.SESSION, Statement.M.createObjectNode().put("op", "list"));
        assertEquals(1, listed.path("invited").size(), listed.toString());
        assertEquals("Dee <b>Example</b>", listed.path("invited").get(0).path("name").asText());
        assertEquals("write", listed.path("invited").get(0).path("level").asText());
        assertTrue(listed.path("invited").get(0).path("until").asText().matches("\\d{4}-\\d{2}-\\d{2}"), listed.toString());
        assertEquals(0, listed.path("people").size());
    }
}
