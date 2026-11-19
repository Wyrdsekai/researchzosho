package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an account spends, counted and capped (0.8.0, stage 3, step 1): the store keeps each use and what was spent, and the month's
 * totals in the same transaction; the caps come from the account, else the service, else the defaults; the built-in caps answer
 * whether a use may start; a use that cannot be asked about does not start; and a library under the service asks before each use
 * (a job, a chat turn, a page added by its address) and tells the service when the use ends.
 */
class UsesTest {
    static final ObjectMapper M = new ObjectMapper();
    AccountStore store; AccountService svc; StandInModel model;

    /** The libraries of the service in this process, on a stand-in model. */
    final class Here implements LibraryRunner {
        final String service; final Map<String, LibrarianDaemon> up = new ConcurrentHashMap<>();
        Here(String service) { this.service = service; }
        @Override public synchronized String ensure(AccountStore.Library lib) throws IOException {
            LibrarianDaemon d = up.get(lib.id());
            if (d == null) { d = LibrarianDaemon.start(new LibraryStore(Path.of(lib.folder()).resolve("library")), "127.0.0.1", 0, model.url(), "stand-in", -1, -1, new Vouching(service)); up.put(lib.id(), d); }
            return d.url();
        }
        @Override public void lost(String libraryId) { }
        @Override public void stopAll() { up.values().forEach(LibrarianDaemon::stop); up.clear(); }
    }

    void start(Path tmp) throws Exception {
        model = new StandInModel();
        Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
        Config.set("RESEARCHZOSHO_DRIVE", model.url());
        Config.set("RESEARCHZOSHO_MODEL", "stand-in");
        store = AccountStore.open(AccountService.root().resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, Here::new);
    }

    @AfterEach void stop() throws Exception {
        if (svc != null) svc.stop();
        if (store != null) store.close();
        if (model != null) model.close();
        Config.set(AccountService.ROOT, ""); Config.set("RESEARCHZOSHO_DRIVE", ""); Config.set("RESEARCHZOSHO_MODEL", "");
    }

    static AccountStore.Usage model(String account, String library, String use, String payer, long micros, boolean estimated) {
        return new AccountStore.Usage(System.currentTimeMillis(), account, library, use, "model", payer, "some-model", 1000, 200, 100, 1, 0, micros, estimated);
    }

    @Test
    void theMonthsTotalsAreKeptWithWhatWasSpent(@TempDir Path tmp) throws Exception {
        try (AccountStore s = AccountStore.open(tmp.resolve("a.db").toString())) {
            s.addUsage(List.of(model("acct_a", "lib_a", "use_1", "operator", 120_000, false), model("acct_a", "lib_a", "use_1", "operator", 30_000, true),
                    model("acct_a", "lib_a", "use_2", "own", 50_000, false), new AccountStore.Usage(System.currentTimeMillis(), "acct_a", "lib_a", "use_1", "search", "operator", "brave", 0, 0, 0, 3, 0, 15_000, false),
                    model("acct_b", "lib_b", "use_3", "operator", 7_000, false)));
            Map<String, AccountStore.MonthTotal> m = new HashMap<>();
            for (AccountStore.MonthTotal t : s.month("acct_a", AccountStore.month(System.currentTimeMillis()))) m.put(t.kind() + "/" + t.payer(), t);
            assertEquals(150_000, m.get("model/operator").micros());
            assertEquals(2, m.get("model/operator").quantity());
            assertEquals(2 * 1300, m.get("model/operator").tokens());
            assertEquals(1, m.get("model/operator").estimated(), "one row worked out from sizes");
            assertEquals(50_000, m.get("model/own").micros(), "the person's own key's work, apart");
            assertEquals(3, m.get("search/operator").quantity());
            assertEquals(165_000, s.useSpent("use_1"));
            assertEquals(List.of("acct_a", "acct_b"), s.accountsWithUse(AccountStore.month(System.currentTimeMillis())));
        }
    }

    @Test
    void anAccountsCapsAreItsOwnElseTheServicesElseTheDefault(@TempDir Path tmp) throws Exception {
        try (AccountStore s = AccountStore.open(tmp.resolve("a.db").toString())) {
            assertEquals(Caps.DEFAULTS, Caps.of(s, "acct_a"));
            s.setCap("*", Caps.MONTH_DOLLARS, 20_000_000);
            s.setCap("acct_a", Caps.MONTH_DOLLARS, -1);
            assertEquals(-1L, Caps.of(s, "acct_a").get(Caps.MONTH_DOLLARS), "no cap for this one");
            assertEquals(20_000_000L, Caps.of(s, "acct_b").get(Caps.MONTH_DOLLARS), "the service's for the others");
            s.unsetCap("acct_a", Caps.MONTH_DOLLARS);
            assertEquals(20_000_000L, Caps.of(s, "acct_a").get(Caps.MONTH_DOLLARS));
            assertEquals(5_000_000L, Caps.parse(Caps.MONTH_DOLLARS, "$5"));
            assertEquals(-1L, Caps.parse(Caps.SEARCHES, "off"));
            assertEquals("$1.50", Caps.said(Caps.JOB_DOLLARS, 1_500_000));
        }
    }

    Spending.Use use(String kind, String payer, long estimate) { return new Spending.Use("use_x", "acct_a", "lib_a", kind, "frontier", payer, estimate, 10, ""); }

    @Test
    void theBuiltInCapsAnswer(@TempDir Path tmp) throws Exception {
        try (AccountStore s = AccountStore.open(tmp.resolve("a.db").toString())) {
            CapsSpending caps = new CapsSpending(s);
            Spending.Answer a = caps.mayStart(use(SpendPurse.RESEARCH, "operator", 400_000));
            assertTrue(a.yes());
            assertEquals(1_500_000, a.ceilingMicros(), "a job: its own cap, less than the month's $5");
            assertEquals(30, a.ceilingMinutes());
            assertEquals("About $0.40 on frontier; $5.00 left this month.", a.said());
            s.addUsage(List.of(model("acct_a", "lib_a", "use_0", "operator", 4_200_000, false)));
            a = caps.mayStart(use(SpendPurse.CHAT, "operator", 10_000));
            assertTrue(a.yes()); assertEquals(800_000, a.ceilingMicros(), "a chat turn: what is left of the month"); assertEquals(-1, a.ceilingMinutes());
            a = caps.mayStart(use(SpendPurse.RESEARCH, "operator", 400_000));
            assertEquals(800_000, a.ceilingMicros(), "a job: what is left, being less than its own cap");
            s.addUsage(List.of(model("acct_a", "lib_a", "use_0", "operator", 900_000, false)));
            a = caps.mayStart(use(SpendPurse.CHAT, "operator", 10_000));
            assertFalse(a.yes());
            String next = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1).plusMonths(1).toString();
            assertEquals("This account has used its $5.00 for this month. It starts again on " + Chrome.in("en").dayTime(next) + ", or sooner if whoever runs the service raises it.", a.said());
            assertFalse(a.said().contains(next), "a day as a person reads it, not as a program writes it");
            assertTrue(caps.mayStart(use(SpendPurse.CHAT, "own", 10_000)).yes(), "the person's own key: not against the dollars");
            // jobs at once
            s.setCap("acct_a", Caps.MONTH_DOLLARS, -1);
            s.addUse(new AccountStore.Use("use_going", "acct_a", "lib_a", SpendPurse.TRANSLATION, "", "operator", "", 0, 0, 0, 0, "open", "", System.currentTimeMillis(), 0, 0));
            a = caps.mayStart(use(SpendPurse.RESEARCH, "operator", 0));
            assertFalse(a.yes()); assertEquals("This account already has as many jobs going as it may have at once (1). Ask again when one has ended.", a.said());
            assertTrue(caps.mayStart(use(SpendPurse.CHAT, "operator", 0)).yes(), "a chat turn is not a job");
            assertTrue(caps.mayStart(use(SpendPurse.NIGHTLY, "operator", 0)).yes(), "nor is the night");
            s.closeUse("use_going", "done");
            s.addUse(new AccountStore.Use("use_old", "acct_a", "lib_a", SpendPurse.RESEARCH, "", "operator", "", 0, 0, 0, 0, "open", "", System.currentTimeMillis() - 25 * 3_600_000L, 0, 0));
            assertTrue(caps.mayStart(use(SpendPurse.RESEARCH, "operator", 0)).yes(), "a use its library never said ended holds nothing after a day");
            // a page added by its address, against the month's fetches
            s.setCap("acct_a", Caps.FETCHES, 2);
            s.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), "acct_a", "lib_a", "use_0", "fetch", "operator", "", 0, 0, 0, 2, 9000, 0, false)));
            a = caps.mayStart(use(CapsSpending.ADD, "operator", 0));
            assertFalse(a.yes()); assertTrue(a.said().startsWith("This account has fetched as many pages this month as it may (2)."), a.said());
        }
    }

    /** The day a cap starts again is written as the person's language writes a day (pinned in English and Japanese). */
    @Test
    void theDayACapStartsAgainIsWrittenAsThePersonsLanguageWritesIt(@TempDir Path tmp) throws Exception {
        LocalDate first = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1).plusMonths(1);
        try {
            Chrome.here("en");
            String en = CapsSpending.monthUsed(5_000_000);
            String day = first.getDayOfMonth() + " " + first.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + (first.getYear() == LocalDate.now().getYear() ? "" : " " + first.getYear());
            assertTrue(en.contains("starts again on " + day + ","), en);
            Chrome.here("ja");
            String ja = CapsSpending.monthUsed(5_000_000);
            assertTrue(ja.contains(first.getMonthValue() + "月" + first.getDayOfMonth() + "日"), ja);
            assertFalse(ja.contains(first.toString()), ja);
            assertTrue(CapsSpending.searchesUsed(300).contains(first.getMonthValue() + "月" + first.getDayOfMonth() + "日"));
        } finally { Chrome.clear(); }
    }

    @Test
    void aUseThatCannotBeAskedAboutDoesNotStart(@TempDir Path tmp) throws Exception {
        try (AccountStore s = AccountStore.open(tmp.resolve("a.db").toString())) {
            Spending fails = new Spending() {
                @Override public Answer mayStart(Use use) throws IOException { throw new IOException("nobody home"); }
                @Override public void spent(Use use, Actual actual) { }
            };
            AccountStore.Use u = Uses.start(s, fails, "acct_a", "lib_a", SpendPurse.RESEARCH, "frontier", "operator", 400_000, 10, "J-0001");
            assertEquals("refused", u.state());
            assertEquals("The service could not check this account's spending just now, so nothing was started. Ask again in a minute.", u.said());
            Spending silent = new Spending() {
                @Override public Answer mayStart(Use use) { return null; }
                @Override public void spent(Use use, Actual actual) { }
            };
            assertEquals("refused", Uses.start(s, silent, "acct_a", "lib_a", SpendPurse.CHAT, "", "operator", 0, 0, "").state());
            AccountStore.Use open = Uses.start(s, new CapsSpending(s), "acct_a", "lib_a", SpendPurse.CHAT, "", "operator", 0, 0, "");
            assertEquals("open", open.state());
            s.addUsage(List.of(model("acct_a", "lib_a", open.id(), "operator", 33_000, false)));
            AccountStore.Use closed = Uses.end(s, new CapsSpending(s), open.id(), "done");
            assertEquals("done", closed.state()); assertEquals(33_000, closed.actualMicros());
            assertNull(Uses.end(s, new CapsSpending(s), open.id(), "done"), "closed once");
        }
    }

    HttpResponse<String> api(String path, String key, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static void until(String what, long ms, BooleanSupplier is) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!is.getAsBoolean()) { assertTrue(System.currentTimeMillis() < end, "within " + ms + " ms: " + what); Thread.sleep(50); }
    }

    /** A library under the service asks before each use, and the service's answer is what the person gets. */
    @Test
    void aLibraryUnderTheServiceAsksBeforeEachUse(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = AccountService.newAccount(store, AccountService.root(), "Ann Example", "", false, "the test");
        String key = store.mintKey(ann.account().id(), ann.library().id(), "write", "the test").secret();
        String acct = ann.account().id();
        // no job may be going: a research run is refused, with the sentence, and nothing is filed
        store.setCap(acct, Caps.JOBS_AT_ONCE, 0);
        HttpResponse<String> r = api("/v1/research", key, "{\"question\":\"When was the harbour built?\"}");
        assertEquals(429, r.statusCode(), r.body());
        assertTrue(r.body().contains("This account already has as many jobs going as it may have at once (0)."), r.body());
        List<AccountStore.Use> uses = store.uses(acct, 0);
        assertEquals(1, uses.size()); assertEquals("refused", uses.get(0).state()); assertEquals(SpendPurse.RESEARCH, uses.get(0).kind());
        // one may: it is filed with its use, and the use is closed when the job ends (with no model it fails at once)
        store.unsetCap(acct, Caps.JOBS_AT_ONCE);
        r = api("/v1/research", key, "{\"question\":\"When was the harbour built?\"}");
        assertEquals(200, r.statusCode(), r.body());
        String job = M.readTree(r.body()).path("job_id").asText();
        AccountStore.Use filed = store.uses(acct, 0).stream().filter(u -> u.job().equals(job)).findFirst().orElseThrow();
        assertEquals(SpendPurse.RESEARCH, filed.kind()); assertEquals(30, filed.ceilingMinutes());
        until("the job's use closed when the job ended", 30_000, () -> { try { return !store.use(filed.id()).open(); } catch (IOException e) { return false; } });
        // the month's dollars used: a chat turn is refused with the sentence
        store.addUsage(List.of(model(acct, ann.library().id(), "use_earlier", "operator", 5_000_000, false)));
        HttpResponse<String> chat = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(svc.url() + "/chat")).header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("say=hello")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(chat.body().contains("This account has used its $5.00 for this month."), chat.statusCode() + " " + chat.body());
        assertTrue(store.uses(acct, 0).stream().anyMatch(u -> u.kind().equals(SpendPurse.CHAT) && u.state().equals("refused")));
        // a page by its address, against the month's fetches
        store.setCap(acct, Caps.MONTH_DOLLARS, -1); store.setCap(acct, Caps.FETCHES, 0);
        r = api("/v1/add", key, "{\"url\":\"https://example.org/\"}");
        assertTrue(r.body().contains("This account has fetched as many pages this month as it may (0)."), r.statusCode() + " " + r.body());
        // the service's own work in a library (its night) is the keeper's
        AccountStore.Signing k = store.signing();
        String asService = Statement.sign(k.key(), k.kid(), Statement.Claims.now(svc.issuer, ann.library().id(), "service", "the account service", Statement.READ, Statement.SERVICE));
        JsonNode night = M.readTree(internal(Uses.START, asService, "{\"kind\":\"nightly\"}").body());
        assertEquals("open", night.path("state").asText());
        assertEquals(acct, store.use(night.path("use").asText()).account());
        // a person who may not open the library is not answered
        AccountService.Made bo = AccountService.newAccount(store, AccountService.root(), "Bo Example", "", false, "the test");
        String asBo = Statement.sign(k.key(), k.kid(), Statement.Claims.now(svc.issuer, ann.library().id(), bo.account().id(), "Bo Example", Statement.READ, Statement.SESSION));
        assertEquals(403, internal(Uses.START, asBo, "{\"kind\":\"chat\"}").statusCode());
        HttpResponse<String> end = internal(Uses.END, null, "{\"use\":\"" + night.path("use").asText() + "\",\"state\":\"done\",\"key\":\"" + night.path("key").asText() + "\"}");
        assertTrue(M.readTree(end.body()).path("closed").asBoolean(), end.body());
    }

    HttpResponse<String> internal(String path, String statement, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (statement != null) b.header(Statement.HEADER, statement);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The command line: this month's use, every account's or one account's with its caps; the caps, set and put back. */
    @Test
    void theCommandLineShowsTheMonthAndSetsTheCaps(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = AccountService.newAccount(store, AccountService.root(), "Ann Example", "", false, "the test");
        AccountsCliTest.Ran r = AccountsCliTest.run("usage");
        assertTrue(r.out().contains("Nothing was spent and nothing asked for this month"), r.out());
        store.addUsage(List.of(model(ann.account().id(), ann.library().id(), "use_1", "operator", 1_250_000, true)));
        r = AccountsCliTest.run("usage");
        assertTrue(r.out().contains("Ann Example: $1.25 of $5.00 on the service's model and searches; 0 searches of 300; 0 pages fetched of 3000; 0 MB stored of 2.0 GB; 0 jobs going of 1 at once; a job at most 30 minutes and $1.50; 1 of the month's counts are estimated from sizes"), r.out());
        r = AccountsCliTest.run("caps", "month_dollars", "20");
        assertEquals(0, r.code(), r.err()); assertTrue(r.out().contains("The service's month_dollars is now $20.00."), r.out());
        r = AccountsCliTest.run("caps", "Ann Example", "searches", "off");
        assertTrue(r.out().contains("Ann Example's searches is now no cap."), r.out());
        r = AccountsCliTest.run("caps", "Ann Example");
        assertTrue(r.out().contains("month_dollars  $20.00  (the service's)") && r.out().contains("searches  no cap  (its own)") && r.out().contains("jobs_at_once  1  (the default)"), r.out());
        r = AccountsCliTest.run("usage", "Ann Example");
        assertTrue(r.out().contains("$1.25 of $20.00 on the service's model and searches"), r.out());
        assertEquals(2, AccountsCliTest.run("caps", "month_dollars", "lots").code());
    }

    @Test
    void aLibraryOfOnesOwnAsksNobody(@TempDir Path tmp) throws Exception {
        LibraryStore own = new LibraryStore(tmp.resolve("lib")); own.init();
        assertNull(ServiceUses.start(own, SpendPurse.RESEARCH, "", 1, 1, "J-0001"), "nothing to ask, nothing asked");
    }
}
