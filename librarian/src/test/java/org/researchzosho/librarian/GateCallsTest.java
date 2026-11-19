package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.Gated;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.tools.Firecrawl;
import org.researchzosho.tools.WebSearchTool;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gates pass only the calls the program makes, and count each at what it costs (round F, the second review, S3 and money 1, 3, 4
 * and 7, each proved on c8e057ed): a path written another way ("/./v1") reached the operator's priced server and was counted at nothing; a
 * model no entry names went out on the operator's key; SerpApi's account call handed the operator's key to the library and a crawl of
 * ten thousand pages counted as one fetch; a call that got no slot kept what it had put aside; work on the person's own key was stopped
 * by the operator's month; and a model with no prices put aside nothing.
 */
class GateCallsTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final List<String> set = new ArrayList<>();
    final Map<String, String> bases = new HashMap<>(NodeGates.BASES);
    StandInModel model; AccountStore store; NodeGates gates; HttpServer place;
    long waitWas = NodeGates.WAIT_MS;

    void set(String key, String value) throws Exception { Config.set(key, value); set.add(key); }

    @AfterEach void stop() throws Exception {
        for (String k : set) Config.set(k, "");
        NodeGates.WAIT_MS = waitWas;
        NodeGates.HOSTED = NodeGates.HOSTED_BY_ADDRESS;
        NodeGates.BASES.clear(); NodeGates.BASES.putAll(bases);
        if (place != null) place.stop(0);
        if (gates != null) gates.close();
        if (store != null) store.close();
        if (model != null) model.close();
    }

    AccountStore.Use use(String account, long ceiling, String payer) throws Exception {
        AccountStore.Use u = new AccountStore.Use(AccountStore.id("use_"), account, "lib_" + account, SpendPurse.RESEARCH, "", payer, "", 0, 0, ceiling, -1, "open", "", System.currentTimeMillis(), 0, 0);
        store.addUse(u);
        return u;
    }

    long modelMicros(String account) throws Exception {
        return store.month(account, AccountStore.month(System.currentTimeMillis())).stream().filter(t -> t.kind().equals("model")).mapToLong(AccountStore.MonthTotal::micros).sum();
    }

    HttpResponse<String> post(String url, String use, String body) throws Exception {
        try { return c.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").header(Gated.USE, use).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()); }
        finally { Thread.sleep(500); }   // the gate writes what a call cost after it has passed the answer on
    }

    @Test
    void aPathWrittenAnotherWayIsTheServersPathAndAModelNoEntryNamesIsRefused(@TempDir Path tmp) throws Exception {
        model = new StandInModel();
        String drive = model.url() + "/v1";
        set("RESEARCHZOSHO_DRIVE", drive); set("RESEARCHZOSHO_MODEL", "stand-in");
        set("RESEARCHZOSHO_API_KEY", "the-operators-model-key");
        set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "1/2/0.5");
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        gates = NodeGates.of(store);
        AccountStore.Use u = use("acct_a", -1, "operator");
        Map<String, String> env = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", drive));
        gates.rewrite(env, u.library());
        String d = env.get("RESEARCHZOSHO_DRIVE");
        String body = "{\"model\":\"stand-in\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(40_000) + "\"}]}";
        assertEquals(200, post(d + "/chat/completions", u.id(), body).statusCode());
        long once = modelMicros("acct_a");
        assertTrue(once > 0, "the call is priced: " + once);
        String port = String.valueOf(URI.create(model.url()).getPort());
        for (String other : List.of(d.replace(":" + port + "/v1", ":" + port + "/./v1"), d.replace(":" + port + "/v1", ":" + port + "/x/../v1"))) {
            long before = modelMicros("acct_a");
            assertEquals(200, post(other + "/chat/completions", u.id(), body).statusCode(), other);
            assertEquals(once, modelMicros("acct_a") - before, "the same call by " + other + " is counted as the call it is");
        }
        // a model no entry of the service names is not called on the operator's key
        long calls = model.calls.get();
        HttpResponse<String> r = post(d + "/chat/completions", u.id(), body.replace("stand-in", "a-much-bigger-model"));
        assertEquals(403, r.statusCode(), r.body());
        assertEquals(calls, model.calls.get(), "it never reached the server");
    }

    @Test
    void theSearchGatePassesOnlyTheCallsTheProgramMakes(@TempDir Path tmp) throws Exception {
        set(WebSearchTool.SERPAPI_KEY, "the-operators-serpapi-key");
        set(ExaSearchTool.KEY, "the-operators-exa-key");
        set(Firecrawl.KEY, "the-operators-firecrawl-key");
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        gates = NodeGates.of(store);
        List<String> seen = new CopyOnWriteArrayList<>();
        place = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        place.createContext("/", x -> { x.getRequestBody().readAllBytes(); seen.add(x.getRequestMethod() + " " + x.getRequestURI().getPath()); byte[] b = "{\"api_key\":\"leaked\"}".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        place.start();
        String base = "http://127.0.0.1:" + place.getAddress().getPort();
        for (String p : List.of("serpapi", "exa", "firecrawl")) NodeGates.BASES.put(p, base);
        Map<String, String> env = new HashMap<>();
        gates.rewrite(env, "lib_acct_s");
        String search = env.get(NodeGates.SEARCH_GATE);
        AccountStore.Use u = use("acct_s", -1, "operator");
        HttpResponse<String> account = c.send(HttpRequest.newBuilder(URI.create(search + "/serpapi/account.json")).header(Gated.USE, u.id()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, account.statusCode());
        assertFalse(account.body().contains("leaked"));
        assertEquals(403, c.send(HttpRequest.newBuilder(URI.create(search + "/serpapi/searches/abc.json")).header(Gated.USE, u.id()).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, post(search + "/exa/websets/v0/websets", u.id(), "{\"search\":{\"query\":\"q\",\"count\":1000}}").statusCode());
        assertEquals(403, post(search + "/firecrawl/v2/crawl", u.id(), "{\"url\":\"https://example.org\",\"limit\":10000}").statusCode());
        assertEquals(403, post(search + "/serpapi/./search.json", u.id(), "{}").statusCode(), "a path written another way is not the program's");
        assertEquals(List.of(), seen, "nothing reached the place: " + seen);
        // the program's own calls pass and are counted
        assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(search + "/serpapi/search.json?q=ferry")).header(Gated.USE, u.id()).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, post(search + "/exa/search", u.id(), "{\"query\":\"ferry\"}").statusCode());
        assertEquals(200, post(search + "/firecrawl/v2/scrape", u.id(), "{\"url\":\"https://example.org\"}").statusCode());
        assertEquals(List.of("GET /search.json", "POST /search", "POST /v2/scrape"), seen);
        assertEquals(3, store.usageOfUse(u.id()).size(), store.usageOfUse(u.id()).toString());
    }

    @Test
    void aCallThatGetsNoSlotGivesBackWhatItPutAside(@TempDir Path tmp) throws Exception {
        model = new StandInModel();
        set("RESEARCHZOSHO_DRIVE", model.url()); set("RESEARCHZOSHO_MODEL", "stand-in");
        set("RESEARCHZOSHO_API_KEY", "the-operators-model-key");
        set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "1/2/0.5");
        set(ModelEntries.key(ModelEntries.DEFAULT, "CALLS"), "1");
        set(ModelEntries.key(ModelEntries.DEFAULT, "KEPT"), "0");
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        gates = NodeGates.of(store);
        NodeGates.WAIT_MS = 300;
        String origin = NodeGates.origin(URI.create(model.url()));
        store.laneWait("w_hold", origin, Gated.BACKGROUND, "acct_other");
        assertNotNull(store.laneTake("w_hold", origin, Gated.BACKGROUND, "acct_other", 1, 0, 1, 600_000), "another account's call holds the only slot");
        AccountStore.Use u = use("acct_b", 1_000_000, "operator");
        Map<String, String> env = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", model.url()));
        gates.rewrite(env, u.library());
        assertEquals(503, post(env.get("RESEARCHZOSHO_DRIVE") + "/v1/chat/completions", u.id(), "{\"model\":\"stand-in\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}").statusCode());
        assertNotEquals("!use", store.reserve("acct_b", u.id(), 0, 1, -1, AccountStore.month(System.currentTimeMillis())), "nothing is held against the use after the refusal");
    }

    @Test
    void workOnTheirOwnKeyIsNotStoppedByTheOperatorsMonthAndAnUnpricedModelPutsAsideItsEstimate(@TempDir Path tmp) throws Exception {
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        store.setCap("acct_c", Caps.MONTH_DOLLARS, 1_000_000);
        store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), "acct_c", "lib_acct_c", "", "search", "operator", "brave", 0, 0, 0, 1, 0, 1_000_000, false)));
        AccountStore.Use own = use("acct_c", -1, "own"), theirs = use("acct_c", -1, "operator");
        assertNull(NodeGates.reached(store, own), "the month is the operator's dollars");
        assertNotNull(NodeGates.reached(store, theirs), "work on the operator's keys stops at it");
        // a hosted model with no prices puts aside what it is counted at, so a call made meanwhile sees it
        String drive = "http://127.0.0.1:9/v1";
        NodeGates.HOSTED = d -> d != null && d.startsWith("http://127.0.0.1:9");
        set("RESEARCHZOSHO_DRIVE", drive); set("RESEARCHZOSHO_MODEL", "an-unpriced-model");
        gates = NodeGates.of(store);
        AccountStore.Use u = use("acct_d", 1_000, "operator");
        String id = gates.reserve(u, drive, "{\"model\":\"an-unpriced-model\",\"max_tokens\":40000,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}".getBytes(StandardCharsets.UTF_8));
        assertFalse(id.startsWith("!"), id);
        assertEquals("!use", store.reserve(u.account(), u.id(), 0, 1_000, -1, AccountStore.month(System.currentTimeMillis())),
                "40,000 tokens at the unpriced $15 a million, $0.60, is held against the use while the call runs");
    }
}
