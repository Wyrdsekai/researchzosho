package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An account's life under the service (0.8.0, stage 4, step 1): every sign-in, key, member, cap and change of an account is written to
 * the audit log, which whoever runs the service reads whole and a person reads for their own account; a suspended account is frozen
 * at once, its work stopped and kept, its libraries closed to everyone and put to sleep, and given back as it was; a person deletes
 * their own account, a library with people in it handed to one of them, keeps it within the grace, and at its day it is removed:
 * what it leaves behind holds its id, its uses and its usage, and its audit entries no address and no detail.
 */
class AccountLifecycleTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    long lease = Node.LEASE_MS, renew = Node.RENEW_MS, soon = Node.DUE_SOON_MS;
    StandInModel model; AccountStore store; AccountService svc; volatile Node node; Path root;

    void start(Path tmp, long delayMs) throws Exception {
        Node.LEASE_MS = 4_000; Node.RENEW_MS = 250; Node.SLEEP_AFTER_MS = 0L; Node.DUE_SOON_MS = 0;   // nothing sleeps for being idle here: only a frozen keeper's library does
        model = new StandInModel(); model.delayMs = delayMs;
        Config.set("RESEARCHZOSHO_DRIVE", model.url()); Config.set("RESEARCHZOSHO_MODEL", "stand-in"); Config.set(Scheduler.NIGHTS, "off");
        Config.set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "1/2/0.5");
        Config.set(OwnKeys.FILE, tmp.resolve("keys").resolve("own.key").toString());
        root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());   // the command line's store is this one
        store = AccountStore.open(root.resolve("accounts.db").toString());
        store.setCap("*", Caps.JOBS_AT_ONCE, -1);
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new LibraryRunner() {
            @Override public String ensure(AccountStore.Library lib) throws IOException { return node.ensure(lib); }
            @Override public void lost(String id) { node.lost(id); }
            @Override public void stopAll() { node.stopAll(); }
            @Override public long epoch(String id) { return node.epoch(id); }
            @Override public boolean awake(String id) { return node.awake(id); }
            @Override public String ifAwake(AccountStore.Library lib) throws IOException { return node.ifAwake(lib); }
            @Override public void done(String id) { node.done(id); }
        });
        node = new Node(store, svc.url(), new ChildLibraries(store, svc.url()), "node_test", root);
        node.start();
    }

    @AfterEach void stop() throws Exception {
        try { if (svc != null) svc.stop(); if (store != null) store.close(); if (model != null) model.close(); }
        finally {
            Node.LEASE_MS = lease; Node.RENEW_MS = renew; Node.SLEEP_AFTER_MS = null; Node.DUE_SOON_MS = soon;
            for (String k : List.of("RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_MODEL", ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), Scheduler.NIGHTS, OwnKeys.FILE, AccountService.ROOT)) Config.set(k, "");
            OwnKeys.forget();
        }
    }

    HttpResponse<String> api(String key, String path, String body) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> page(String cookie, String path) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Cookie", AccountService.COOKIE + "=" + cookie).header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> form(String cookie, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Sec-Fetch-Site", "same-origin").header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) b.header("Cookie", AccountService.COOKIE + "=" + cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    /** Signs in with a name and a password; the session's secret, or null when refused. */
    String signIn(String name, String password) throws Exception {
        HttpResponse<String> r = form(null, "/login", "name=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8));
        for (String h : r.headers().allValues("Set-Cookie")) if (h.startsWith(AccountService.COOKIE + "=") && !h.startsWith(AccountService.COOKIE + "=;")) return h.substring(AccountService.COOKIE.length() + 1, h.indexOf(';'));
        return null;
    }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static void until(String what, long ms, BooleanSupplier is) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!is.getAsBoolean()) { assertTrue(System.currentTimeMillis() < end, "within " + ms + " ms: " + what); Thread.sleep(100); }
    }

    List<String> acts(String account) throws IOException { return store.audits(account, 0, 500).stream().map(AccountStore.Audit::act).toList(); }

    @Test
    void everyActIsWrittenToTheAuditLogAndEachPersonSeesTheirOwn(@TempDir Path tmp) throws Exception {
        start(tmp, 0);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test"), bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String a = ann.account().id();
        store.setPassword(a, Passwords.hash("a long password"));
        assertNull(signIn("Ann Example", "not her password"));
        assertNull(signIn("Nobody Here", "a long password"));
        String cookie = signIn("Ann Example", "a long password");
        assertTrue(cookie != null);
        form(cookie, "/account", "op=key-new&library=" + ann.library().id() + "&level=write&label=" + enc("my agent"));
        HttpResponse<String> link = form(cookie, "/account", "op=member-let-in&library=" + ann.library().id() + "&name=" + enc("Bo Example") + "&level=read");
        store.setPassword(bo.account().id(), Passwords.hash("bo's long password"));
        String boCookie = signIn("Bo Example", "bo's long password");
        String code = link.body().substring(link.body().indexOf("/join?code=") + 11).replaceAll("(?s)[^A-Za-z0-9_-].*$", "");
        assertEquals(303, form(boCookie, "/join", "code=" + enc(code) + "&op=take").statusCode(), "Bo takes the link Ann sent");
        form(cookie, "/account", "op=own-key&host=brave&secret=" + enc("a-brave-key"));
        assertEquals(0, AccountsCliTest.run("caps", "Ann Example", "searches", "20").code());
        List<String> acts = acts(a);
        for (String act : List.of("signin_failed", "signin", "key_made", "member_invited", "own_key_set", "cap_changed")) assertTrue(acts.contains(act), act + " in " + acts);
        AccountStore.Audit failed = store.audits(a, 0, 500).stream().filter(e -> e.act().equals("signin_failed")).findFirst().orElseThrow();
        assertEquals("127.0.0.1", failed.address(), "from where");
        assertTrue(store.audits(null, 0, 500).stream().noneMatch(e -> e.detail().contains("Nobody Here")), "a name that is nobody's is not written down");
        AccountStore.Audit letIn = store.audits(bo.account().id(), 0, 500).stream().filter(e -> e.act().equals("member_let_in")).findFirst().orElseThrow();
        assertEquals(bo.account().id(), letIn.actor(), "Bo's entry: he took the link Ann sent");

        // whoever runs the service reads it whole; Ann reads her own
        AccountsCliTest.Ran all = AccountsCliTest.run("audit", "--days", "1");
        assertTrue(all.out().contains("signin_failed") && all.out().contains("cap_changed") && all.out().contains("by the command line (cli)"), all.out());
        String recent = page(cookie, "/account").body();
        assertTrue(recent.contains("Recent activity") && recent.contains("signed in") && recent.contains("a sign-in that failed") && recent.contains("from 127.0.0.1") && recent.contains("by whoever runs this service"), "Ann's own entries");
        assertFalse(recent.contains("Nobody Here"));
    }

    @Test
    void aSuspendedAccountIsFrozenAtOnceAndGivenBackAsItWas(@TempDir Path tmp) throws Exception {
        start(tmp, 250);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test"), bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String a = ann.account().id();
        store.setPassword(a, Passwords.hash("a long password"));
        store.setMember(ann.library().id(), bo.account().id(), "read", "the test");
        String key = store.mintKey(a, ann.library().id(), "write", "the test").secret(), boKey = store.mintKey(bo.account().id(), ann.library().id(), "read", "the test").secret();
        String cookie = signIn("Ann Example", "a long password");
        String job = M.readTree(api(key, "/v1/research", "{\"question\":\"When was the lighthouse of the harbour first lit?\"}").body()).path("job_id").asText();
        until("Ann's run under way", 60_000, () -> model.calls.get() > 3);

        AccountsCliTest.Ran r = AccountsCliTest.run("suspend", "Ann Example");
        assertEquals(0, r.code(), r.err());
        String said = AccountActs.suspendedSaid();
        assertEquals(302 / 100, page(cookie, "/account").statusCode() / 100, "her browser is signed out");
        HttpResponse<String> byKey = api(key, "/v1/status", "{}");
        assertEquals(403, byKey.statusCode());
        assertEquals(said, M.readTree(byKey.body()).path("error").path("message").asText(), "her key is kept and refused, with the sentence");
        HttpResponse<String> signin = form(null, "/login", "name=" + enc("Ann Example") + "&password=" + enc("a long password"));
        assertTrue(signin.body().contains(Pages.esc(said)), "and told so at sign-in");
        HttpResponse<String> boIn = api(boKey, "/v1/status", "{}");
        assertEquals(403, boIn.statusCode());
        assertEquals(AccountActs.closedSaid(store.library(ann.library().id())), M.readTree(boIn.body()).path("error").path("message").asText(), "her library opens for nobody, members included");
        // her running work stops after its call, kept to resume; her library sleeps once quiet; nothing is deleted
        AccountsCliTest.Ran list = AccountsCliTest.run("list");
        assertTrue(list.out().contains("Ann Example") && list.out().contains("(suspended)"), list.out());
        until("her library asleep", 120_000, () -> !node.awake(ann.library().id()));
        long callsAsleep = model.calls.get();
        Thread.sleep(1_500);
        assertEquals(callsAsleep, model.calls.get(), "nothing more is asked");
        assertTrue(Files.isDirectory(Path.of(store.library(ann.library().id()).folder())), "nothing deleted");
        assertTrue(acts(a).contains("account_suspended"));

        assertEquals(0, AccountsCliTest.run("unsuspend", "Ann Example").code());
        assertEquals(200, api(key, "/v1/status", "{}").statusCode(), "her key works again");
        JsonNode j = M.readTree(api(key, "/v1/job", "{\"job_id\":\"" + job + "\"}").body()).path("job");
        assertEquals(Jobs.CAPPED, j.path("state").asText(), "her run waits, stopped: " + j);
        assertEquals(said, j.path("waiting").asText(), "with the suspension's sentence");
        model.delayMs = 0;   // the delay held her run in flight for the suspension; the rest of it only waited it out, some 30 s
        assertEquals(200, api(key, "/v1/job", "{\"op\":\"resume\",\"job_id\":\"" + job + "\"}").statusCode());
        until("her run finished", 300_000, () -> { try { return "done".equals(M.readTree(api(key, "/v1/job", "{\"job_id\":\"" + job + "\"}").body()).path("job").path("state").asText()); } catch (Exception e) { return false; } });
        assertTrue(acts(a).contains("account_unsuspended"));
    }

    @Test
    void aPersonDeletesTheirOwnAccountHandsALibraryOverAndWhatIsLeftAfterItsDayHoldsNothingOfThem(@TempDir Path tmp) throws Exception {
        start(tmp, 0);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "ann@example.org", false, "the test"), bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String a = ann.account().id(), b = bo.account().id();
        AccountStore.Library shared = ann.library(), hers = AccountService.newLibrary(store, root, ann.account(), "Ann's notebook");
        store.setMember(shared.id(), b, "write", "the test");
        store.setPassword(a, Passwords.hash("a long password"));
        String key = store.mintKey(a, shared.id(), "write", "the test").secret();
        api(key, "/v1/status", "{}");
        store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), a, shared.id(), "use_1", "model", "operator", "default", 100, 0, 50, 1, 600, 120_000, false)));
        String cookie = signIn("Ann Example", "a long password");
        form(cookie, "/account", "op=own-key&host=brave&secret=" + enc("a-brave-key"));
        String p = page(cookie, "/account").body();
        assertTrue(p.contains("Delete this account") && p.contains("hand it to Bo Example"), "the page asks what becomes of the library Bo is in");

        HttpResponse<String> wrong = form(cookie, "/account", "op=delete-account&confirm=" + enc("Someone Else") + "&handto." + shared.id() + "=" + b);
        assertTrue(wrong.headers().firstValue("Location").orElse("").contains("note="), "her name must be typed");
        assertTrue(store.account(a).active());
        HttpResponse<String> asked = form(cookie, "/account", "op=delete-account&confirm=" + enc("ann example") + "&handto." + shared.id() + "=" + b);
        assertTrue(asked.headers().firstValue("Location").orElse("").startsWith("/login?note="), asked.headers().map().toString());
        assertEquals(b, store.library(shared.id()).owner(), "the shared library is Bo's now");
        assertEquals("deleting", store.account(a).state());
        assertEquals(403, api(key, "/v1/status", "{}").statusCode());
        // within the grace: signing in shows the day and a way to keep it
        String again = signIn("Ann Example", "a long password");
        assertTrue(again != null);
        String deleting = page(again, "/account").body();
        assertTrue(deleting.contains("This account is to be deleted on") && deleting.contains("Keep my account"), deleting);
        assertEquals(302 / 100, page(again, "/").statusCode() / 100, "no library while it is being deleted");
        form(again, "/account", "op=cancel-deletion");
        assertTrue(store.account(a).active(), "kept, as it was");
        assertEquals(a, store.library(shared.id()).owner(), "what was handed over in the request comes back with the account kept (round C)");
        AccountActs.handOver(store, store.library(shared.id()), store.account(b), AccountActs.By.cli());   // handed over again, for good

        // deleted again, and its day comes
        assertEquals(0, AccountsCliTest.run("remove", "Ann Example", "--grace").code());   // the operator's deletion with its days of grace (at once without)
        assertTrue(store.audits(a, 0, 500).stream().anyMatch(e -> !e.address().isEmpty()), "before its day, its entries say from where");
        store.deleting(a, System.currentTimeMillis() - 1);
        AccountActs.due(store);
        AccountStore.Account gone = store.account(a);
        assertEquals("removed", gone.state());
        assertEquals(AccountStore.DELETED_NAME, gone.name());
        assertEquals("", gone.mail()); assertEquals("", gone.passwordHash());
        assertEquals(0, store.sessions(a)); assertTrue(store.keys(a).isEmpty()); assertTrue(store.ownKeys(a).isEmpty());
        assertNull(store.accountByMail("ann@example.org")); assertNull(store.accountByLogin("Ann Example"));
        assertFalse(Files.exists(Path.of(hers.folder())), "the library she kept is removed with her");
        assertEquals("removed", store.library(hers.id()).state());
        assertTrue(Files.isDirectory(Path.of(shared.folder())), "the one she handed over stays, Bo's");
        assertEquals(120_000, store.month(a, AccountStore.month(System.currentTimeMillis())).stream().mapToLong(AccountStore.MonthTotal::micros).sum(), "her usage stays, under her id, for the bills");
        List<AccountStore.Audit> left = store.audits(a, 0, 500);
        assertTrue(left.stream().allMatch(e -> e.address().isEmpty() && e.detail().isEmpty()), "her entries keep act, day and id, and nothing else: " + left);
        assertTrue(left.stream().anyMatch(e -> e.act().equals("account_removed")));
        assertEquals(403, api(key, "/v1/status", "{}").statusCode());
    }

    /** The account page says when a deletion is carried out as the setting says it: a number of days, a day, or at once (stage 4, step 2). */
    @Test
    void theDeletionSaysItsDayAsTheSettingSetsIt(@TempDir Path tmp) throws Exception {
        root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, issuer -> new Nodes(store, issuer, null));
        try {
            AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
            store.setPassword(ann.account().id(), Passwords.hash("a long password"));
            String cookie = signIn("Ann Example", "a long password");
            assertTrue(page(cookie, "/account").body().contains("Your account is removed 30 days after you ask"), "unset: 30");
            Config.set(AccountActs.DELETE_DAYS, "1");
            String one = page(cookie, "/account").body();
            assertTrue(one.contains("Your account is removed a day after you ask") && !one.contains("1 days"), "one day");
            Config.set(AccountActs.DELETE_DAYS, "0");
            String now = page(cookie, "/account").body();
            assertTrue(now.contains("Your account is removed when you confirm, with the libraries you keep and their copies.") && !now.contains("Until that day"), "at once");
            HttpResponse<String> done = form(cookie, "/account", "op=delete-account&confirm=" + enc("Ann Example"));
            assertTrue(done.headers().firstValue("Location").orElse("").startsWith("/login?note=" + URLEncoder.encode("Your account is removed, with the libraries you kept.", StandardCharsets.UTF_8) + "&ns="), done.headers().firstValue("Location").orElse(""));
            assertEquals("removed", store.account(ann.account().id()).state(), "removed when she confirmed");
            assertFalse(Files.exists(Path.of(ann.library().folder())));
        } finally { Config.set(AccountActs.DELETE_DAYS, ""); }
    }
}
