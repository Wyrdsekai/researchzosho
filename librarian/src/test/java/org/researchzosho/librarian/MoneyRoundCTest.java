package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.Gated;
import org.researchzosho.tools.Firecrawl;
import org.researchzosho.tools.TavilySearchTool;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Counting and money (0.8.0 review, round C): a job's minutes held by the cap watch (BILL 4), a removed account's libraries named no
 * more (BILL 7), jobs at once counted and written in one step and a page's own use no job (BILL 8), a hosted model with no prices
 * counted at an estimate and flagged (BILL 9), and the lows: Anthropic's cache writes at their price, the search gate counting only
 * searches (Firecrawl's search a search), "Keep my account" giving back the libraries handed over, the account's own removal written
 * without its address, and a use the store could not write told to the spending as ended at nothing.
 */
class MoneyRoundCTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    AccountStore store; NodeGates gates; HttpServer up;
    final List<String> set = new ArrayList<>();

    void set(String key, String value) throws Exception { Config.set(key, value); set.add(key); }

    @AfterEach void stop() throws Exception {
        for (String k : set) Config.set(k, "");
        NodeGates.LANES = true;
        NodeGates.HOSTED = NodeGates.HOSTED_BY_ADDRESS;
        NodeGates.BASES.clear(); for (NodeGates.Place p : NodeGates.PLACES) NodeGates.BASES.put(p.name(), p.base());
        if (gates != null) gates.close();
        if (store != null) store.close();
        if (up != null) up.stop(0);
    }

    AccountStore.Use use(String account, String kind, long ceilingMinutes, long openedAt) throws Exception {
        AccountStore.Use u = new AccountStore.Use(AccountStore.id("use_"), account, "lib_" + account, kind, "", "operator", "", 0, 0, -1, ceilingMinutes, "open", "", openedAt, 0, 0);
        store.addUse(u);
        return u;
    }

    // ---- BILL 4 ----

    @Test
    void aJobThatHasRunItsMinutesIsStoppedByTheCapWatch(@TempDir Path tmp) throws Exception {
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        AccountStore.Use within = use("acct_a", SpendPurse.RESEARCH, 30, System.currentTimeMillis() - 60_000), over = use("acct_b", SpendPurse.RESEARCH, 1, System.currentTimeMillis() - 120_000);
        assertNull(NodeGates.reached(store, within), "within its minutes");
        String said = NodeGates.reached(store, over);
        assertNotNull(said, "past its minutes, the watch stops it");
        assertTrue(said.contains("1 minute"), said);
    }

    // ---- BILL 7 ----

    @Test
    void aRemovedAccountsLibrariesKeepNoNameTitleOrFolder(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString()); set.add(AccountService.ROOT);
        store = AccountStore.open(root.resolve("accounts.db").toString());
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountActs.remove(store, ann.account(), AccountActs.By.cli());
        AccountStore.Library l = store.library(ann.library().id());
        assertFalse(l.name().contains("ann") || l.title().contains("Ann") || l.folder().contains("ann"), "nothing of the name: " + l);
    }

    // ---- BILL 8 ----

    @Test
    void jobsAtOnceHoldWhenTwoAskAtTheSameMoment(@TempDir Path tmp) throws Exception {
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        store.setCap("*", Caps.JOBS_AT_ONCE, 1);
        CapsSpending caps = new CapsSpending(store);
        int both = 0;
        for (int trial = 0; trial < 40; trial++) {
            String account = "acct_" + trial;
            CountDownLatch go = new CountDownLatch(1);
            List<AccountStore.Use> made = new CopyOnWriteArrayList<>();
            List<Thread> ts = new ArrayList<>();
            for (int i = 0; i < 2; i++) ts.add(Thread.ofPlatform().start(() -> { try { go.await(); made.add(Uses.start(store, caps, account, "lib_" + account, SpendPurse.RESEARCH, "", "operator", 0, 0, "")); } catch (Exception e) { throw new IllegalStateException(e); } }));
            go.countDown();
            for (Thread t : ts) t.join();
            if (made.stream().filter(AccountStore.Use::open).count() > 1) both++;
        }
        assertEquals(0, both, "two jobs at once under a cap of one, in so many of 40 trials");
    }

    @Test
    void aPagesOwnUseIsNotAJob(@TempDir Path tmp) throws Exception {
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        store.setCap("*", Caps.JOBS_AT_ONCE, 1);
        store.setCap("*", Caps.JOB_DOLLARS, 1_500_000);
        CapsSpending caps = new CapsSpending(store);
        assertTrue(Uses.start(store, caps, "acct_ann", "lib_ann", SpendPurse.RESEARCH, "", "operator", 0, 0, "job_1").open(), "a run");
        AccountStore.Use page = Uses.start(store, caps, "acct_ann", "lib_ann", ServiceUses.REQUEST_KIND, "", "operator", 0, 0, "");
        assertTrue(page.open(), "a page's own call while the run goes: " + page.said());
        assertTrue(page.ceilingMicros() < 0 || page.ceilingMicros() > 1_500_000, "not a job's ceiling: " + page.ceilingMicros());
    }

    // ---- BILL 9, and Anthropic's cache writes ----

    void open(Path tmp, String upstream, String model, String prices) throws Exception {
        set("RESEARCHZOSHO_DRIVE", upstream); set("RESEARCHZOSHO_MODEL", model);
        if (prices != null) set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), prices);
        NodeGates.LANES = false;
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        gates = NodeGates.of(store);
    }

    String gateFor(String upstream, String libraryId) {
        Map<String, String> env = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", upstream));
        gates.rewrite(env, libraryId);
        return env.get("RESEARCHZOSHO_DRIVE");
    }

    void upstream(String reply) throws Exception {
        up = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        up.createContext("/", x -> { x.getRequestBody().readAllBytes(); byte[] b = reply.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        up.start();
    }

    long call(String upstream, AccountStore.Use u, String path) throws Exception {
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(gateFor(upstream, u.library()) + path)).header(Gated.USE, u.id()).POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"stand-in\",\"messages\":[]}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        Thread.sleep(300);
        return store.usageOfUse(u.id()).stream().mapToLong(AccountStore.Usage::micros).sum();
    }

    @Test
    void aHostedModelWithNoPricesIsCountedAtAnEstimateAndFlagged(@TempDir Path tmp) throws Exception {
        upstream("{\"choices\":[{\"message\":{\"content\":\"hi\"}}],\"usage\":{\"prompt_tokens\":100000,\"completion_tokens\":1000}}");
        String upstream = "http://127.0.0.1:" + up.getAddress().getPort();
        open(tmp, upstream, "stand-in", null);
        NodeGates.HOSTED = d -> true;   // as an API on the internet is
        AccountStore.Use u = use("acct_a", SpendPurse.CHAT, -1, System.currentTimeMillis());
        long micros = call(upstream, u, "/v1/chat/completions");
        assertTrue(micros > 0, "not counted as nothing: " + store.usageOfUse(u.id()));
        assertTrue(store.usageOfUse(u.id()).get(0).estimated(), "marked as an estimate");
        assertFalse(store.unpriced().isEmpty(), "written down for the operator's page");
    }

    @Test
    void anthropicsCacheWritesAreCountedAtTheirOwnPrice(@TempDir Path tmp) throws Exception {
        upstream("{\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"usage\":{\"input_tokens\":1000,\"cache_creation_input_tokens\":10000,\"cache_read_input_tokens\":0,\"output_tokens\":100}}");
        String upstream = "http://127.0.0.1:" + up.getAddress().getPort();
        open(tmp, upstream, "stand-in", "3/15");
        AccountStore.Use u = use("acct_a", SpendPurse.CHAT, -1, System.currentTimeMillis());
        assertEquals(1000 * 3 + 10000 * 3.75 + 100 * 15, call(upstream, u, "/v1/messages"), 1, "written to the cache at a quarter more than reading");
    }

    // ---- the search gate counts searches ----

    @Test
    void theSearchGateCountsOnlySearchesAndFirecrawlsSearchIsOne(@TempDir Path tmp) throws Exception {
        up = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        up.createContext("/", x -> { x.getRequestBody().readAllBytes(); byte[] b = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        up.start();
        String base = "http://127.0.0.1:" + up.getAddress().getPort();
        set(TavilySearchTool.KEY, "tvly-test"); set(Firecrawl.KEY, "fc-test");
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        gates = NodeGates.of(store);
        NodeGates.BASES.put("tavily", base); NodeGates.BASES.put("firecrawl", base);
        AccountStore.Use u = use("acct_a", SpendPurse.RESEARCH, -1, System.currentTimeMillis());
        Map<String, String> env = new HashMap<>();
        gates.rewrite(env, u.library());
        String gate = env.get(NodeGates.SEARCH_GATE);
        // the calls the program makes pass and are counted; an account's usage, and a call the program never makes, are refused (round F)
        for (String[] call : new String[][]{{"tavily", "/usage", "GET", "403"}, {"tavily", "/search", "POST", "200"}, {"firecrawl", "/v2/search", "POST", "403"}, {"firecrawl", "/v2/scrape", "POST", "200"}}) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(gate + "/" + call[0] + call[1])).header(Gated.USE, u.id());
            int status = c.send(call[2].equals("GET") ? b.GET().build() : b.POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
            assertEquals(Integer.parseInt(call[3]), status, call[0] + call[1]);
        }
        Thread.sleep(300);
        List<String> kinds = store.usageOfUse(u.id()).stream().map(r -> r.entry() + ":" + r.kind()).sorted().toList();
        assertEquals(List.of("firecrawl:fetch", "tavily:search"), kinds, "each call that passed is counted");
    }

    // ---- the account's acts ----

    @Test
    void keepingTheAccountGivesBackTheLibrariesHandedOverAndItsOwnRemovalKeepsNoAddress(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString()); set.add(AccountService.ROOT);
        store = AccountStore.open(root.resolve("accounts.db").toString());
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test"), bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        store.setMember(ann.library().id(), bo.account().id(), "write", ann.account().id());
        AccountActs.By byAnn = new AccountActs.By(ann.account().id(), "session", "203.0.113.5");
        AccountActs.handOver(store, ann.library(), bo.account(), byAnn);   // as the deletion request does, for a library with people in it
        AccountActs.delete(store, ann.account(), 30, byAnn);
        assertTrue(AccountActs.cancelDeletion(store, store.account(ann.account().id()), byAnn));
        assertEquals(ann.account().id(), store.library(ann.library().id()).owner(), "the library handed over in the request comes back");
        // the account removed at its own word: the entry that says so keeps no address
        AccountService.Made cat = AccountService.newAccount(store, root, "Cat Example", "", false, "the test");
        AccountActs.delete(store, cat.account(), 0, new AccountActs.By(cat.account().id(), "session", "198.51.100.7"));
        AccountStore.Audit removed = store.audits(cat.account().id(), "account_removed", 0, Long.MAX_VALUE, 5).get(0);
        assertEquals("", removed.address());
    }

    @Test
    void aUseTheStoreCouldNotWriteIsToldToTheSpendingAsEndedAtNothing(@TempDir Path tmp) throws Exception {
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        List<String> ended = new CopyOnWriteArrayList<>();
        Spending yes = new Spending() {
            @Override public Answer mayStart(Use use) { return new Answer(true, "", -1, -1); }
            @Override public void spent(Use use, Actual actual) { ended.add(use.id() + " " + actual.micros()); }
        };
        store.close();   // the store cannot be written: the use the spending said yes to is not written down
        try { Uses.start(store, yes, "acct_a", "lib_a", SpendPurse.RESEARCH, "", "operator", 0, 0, ""); }
        catch (IOException | RuntimeException refused) { /* said to the caller */ }
        store = null;
        assertEquals(1, ended.size(), "the plug-in, which said yes, is told it ended: " + ended);
        assertTrue(ended.get(0).endsWith(" 0"), ended.toString());
    }
}
