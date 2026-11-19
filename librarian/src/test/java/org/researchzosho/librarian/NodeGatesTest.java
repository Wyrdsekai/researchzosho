package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Gated;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.tools.Firecrawl;
import org.researchzosho.tools.TavilySearchTool;
import org.researchzosho.tools.WebSearchTool;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node's gates (0.8.0, stage 3, step 2), with a stand-in model and a stand-in search place, never a paid one: a library has no key,
 * the gate adds it; a call that costs is counted against the use it carries, from the server's answer in each shape (OpenAI's and
 * Anthropic's, streamed or not) or from its sizes when the answer says nothing, never as nothing; a call of no use is refused, and so
 * is a use past its ceiling or an account past its month; the searches count against the month's; and the person who waits on a
 * chat turn has a slot of the server kept for them while another account's run fills the rest.
 */
class NodeGatesTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    StandInModel model; AccountStore store; NodeGates gates;
    final List<String> set = new ArrayList<>();
    final Map<String, String> bases = new HashMap<>(NodeGates.BASES);

    void start(Path tmp) throws Exception {
        model = new StandInModel();
        set("RESEARCHZOSHO_DRIVE", model.url()); set("RESEARCHZOSHO_MODEL", "stand-in");
        set("RESEARCHZOSHO_API_KEY", "the-operators-model-key");
        set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "1/2/0.5");   // dollars a million: in, out, cached
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        gates = NodeGates.of(store);
    }

    void set(String key, String value) throws IOException { Config.set(key, value); set.add(key); }

    @AfterEach void stop() throws Exception {
        for (String k : set) Config.set(k, "");
        NodeGates.BASES.clear(); NodeGates.BASES.putAll(bases);
        NodeGates.LANES = true;
        Gated.CONTEXT = () -> null;
        if (gates != null) gates.close();
        if (store != null) store.close();
        if (model != null) model.close();
    }

    AccountStore.Use use(String account, long ceiling) throws IOException {
        AccountStore.Use u = new AccountStore.Use(AccountStore.id("use_"), account, "lib_" + account, SpendPurse.RESEARCH, "", "operator", "", 0, 0, ceiling, -1, "open", "", System.currentTimeMillis(), 0, 0);
        store.addUse(u);
        return u;
    }

    HttpResponse<String> call(String path, String use, String lane, String body) throws Exception {
        Map<String, String> env = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", model.url()));
        gates.rewrite(env, use == null || store.use(use) == null ? null : store.use(use).library());   // the address the use's library is given
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(env.get("RESEARCHZOSHO_DRIVE") + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (use != null) b.header(Gated.USE, use);
        if (lane != null) b.header(Gated.LANE, lane);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    List<AccountStore.MonthTotal> month(String account) throws IOException { return store.month(account, AccountStore.month(System.currentTimeMillis())); }

    long micros(String account, String kind) throws IOException { return month(account).stream().filter(t -> t.kind().equals(kind)).mapToLong(AccountStore.MonthTotal::micros).sum(); }
    long quantity(String account, String kind) throws IOException { return month(account).stream().filter(t -> t.kind().equals(kind)).mapToLong(AccountStore.MonthTotal::quantity).sum(); }

    @Test
    void theLanesAreAServerOfOnesOwnsOrAnEntrysWhoseCallsAreSet() {
        assertNull(NodeGates.lanes(null, null, true, null, false, 0), "an API on the internet, its calls not set: no lanes, the provider takes the load");
        assertArrayEquals(new int[]{6, 1, 3}, NodeGates.lanes(6, null, true, null, false, 0), "an entry whose calls are set has lanes, hosted or not");
        assertArrayEquals(new int[]{2, 1, 2}, NodeGates.lanes(null, null, false, 2, true, 0), "llama.cpp's own slots, one kept for a person who waits");
        assertArrayEquals(new int[]{2, 0, 2}, NodeGates.lanes(null, 0, false, 2, true, 0), "kept 0: none kept");
        assertArrayEquals(new int[]{2, 1, 2}, NodeGates.lanes(null, 5, false, 2, true, 0), "never every slot kept: the background has one");
        assertArrayEquals(new int[]{8, 3, 3}, NodeGates.lanes(null, 3, false, 8, true, 0));
        assertArrayEquals(new int[]{4, 1, 3}, NodeGates.lanes(null, null, false, null, true, 0), "a server of one's own that says nothing: four");
        assertArrayEquals(new int[]{1, 0, 1}, NodeGates.lanes(null, null, false, null, true, 1), "one that answers one at a time (Ollama's guess): nothing kept");
        assertNull(NodeGates.lanes(null, null, false, null, false, 0), "an address on the internet that says nothing: no lanes");
    }

    @Test
    void aSearchIsCountedAtItsPlacesPriceAndHeldToTheMonthsDollars(@TempDir Path tmp) throws Exception {
        start(tmp);
        set("RESEARCHZOSHO_BRAVE_KEY", "k1"); set(ExaSearchTool.KEY, "k2"); set(TavilySearchTool.KEY, "k3");
        HttpServer place = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        place.createContext("/", x -> {
            x.getRequestBody().readAllBytes();
            byte[] b = (x.getRequestURI().getPath().equals("/search") && x.getRequestHeaders().containsKey("x-api-key") ? "{\"results\":[],\"costDollars\":{\"total\":0.012}}" : "{}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        place.start();
        try {
            for (String p : List.of("brave", "exa", "tavily")) NodeGates.BASES.put(p, "http://127.0.0.1:" + place.getAddress().getPort());
            Map<String, String> env = new HashMap<>();
            gates.rewrite(env, "lib_acct_p");
            String search = env.get(NodeGates.SEARCH_GATE);
            AccountStore.Use u = use("acct_p", -1);
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(search + "/brave/res/v1/web/search?q=lighthouse")).header(Gated.USE, u.id()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(5_000, micros("acct_p", "search"), "Brave's published price, $5 a thousand requests");
            set(Settings.SEARCH_PRICE + "BRAVE", "0.004");
            c.send(HttpRequest.newBuilder(URI.create(search + "/brave/res/v1/web/search?q=again")).header(Gated.USE, u.id()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(9_000, micros("acct_p", "search"), "the setting's price for the next search");
            c.send(HttpRequest.newBuilder(URI.create(search + "/exa/search")).header(Gated.USE, u.id()).header("x-api-key", NodeGates.KEPT).POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"q\",\"type\":\"auto\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(21_000, micros("acct_p", "search"), "Exa's answer says what it cost: $0.012");
            c.send(HttpRequest.newBuilder(URI.create(search + "/tavily/search")).header(Gated.USE, u.id()).POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"q\",\"search_depth\":\"advanced\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(37_000, micros("acct_p", "search"), "Tavily's advanced search is two credits at $0.008");
            assertEquals(4, quantity("acct_p", "search"), "and still four searches against the searches cap");
            assertEquals(37_000, store.useSpent(u.id()), "the use's own dollars");
            // the month's dollars hold for a search as for the model: past the cap and its margin, the search is refused
            store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), "acct_p", "lib_acct_p", u.id(), "model", "operator", "default", 1, 0, 1, 1, 0, 300_000, false)));
            store.setCap("acct_p", Caps.MONTH_DOLLARS, 0);
            r = c.send(HttpRequest.newBuilder(URI.create(search + "/brave/res/v1/web/search?q=more")).header(Gated.USE, u.id()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(402, r.statusCode(), r.body());
            assertEquals(4, quantity("acct_p", "search"), "the refused search was not made");
        } finally { place.stop(0); }
    }

    @Test
    void aLibrarysFetchesAreCountedAgainstItsUseElseItsKeeperAndRefusedPastTheMonths(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = AccountService.newAccount(store, tmp.resolve("accounts"), "Ann Example", "", false, "the test");
        AccountService.Made bo = AccountService.newAccount(store, tmp.resolve("accounts"), "Bo Example", "", false, "the test");
        String lib = ann.library().id(), keeper = ann.account().id();
        AccountStore.Use bos = new AccountStore.Use(AccountStore.id("use_"), bo.account().id(), lib, SpendPurse.RESEARCH, "", "operator", "", 0, 0, -1, -1, "open", "", System.currentTimeMillis(), 0, 0);
        store.addUse(bos);   // Bo asks in Ann's library
        WebGate.Counting fetches = gates.fetches(lib);
        assertNull(fetches.refused(bos.id()));
        fetches.counted(bos.id(), 4_096);
        assertEquals(1, quantity(bo.account().id(), "fetch"), "against the use's account");
        assertEquals(4_096, month(bo.account().id()).stream().filter(t -> t.kind().equals("fetch")).mapToLong(AccountStore.MonthTotal::bytes).sum());
        fetches.counted(null, 100);
        fetches.counted("use_nobody", 100);
        assertEquals(2, quantity(keeper, "background"), "a request with no use, or one that is no use of this library's, is the library's own: its keeper's, as background");
        assertEquals(0, quantity(keeper, "fetch"), "and not against the fetches cap");
        AccountStore.Use elsewhere = new AccountStore.Use(AccountStore.id("use_"), bo.account().id(), bo.library().id(), SpendPurse.RESEARCH, "", "operator", "", 0, 0, -1, -1, "open", "", System.currentTimeMillis(), 0, 0);
        store.addUse(elsewhere);
        fetches.counted(elsewhere.id(), 100);
        assertEquals(3, quantity(keeper, "background"), "a use of another library is not this one's to spend");
        store.setCap(bo.account().id(), Caps.FETCHES, 1);
        assertEquals(CapsSpending.fetchesUsed(1), fetches.refused(bos.id()), "the account has fetched as many pages as it may this month");
        store.setCap(keeper, Caps.FETCHES, 0);
        assertNull(fetches.refused(null), "the library's own connections are never refused by the fetches cap");
    }

    @Test
    void aHelpersCallGoesThroughTheGateWithTheSecretAndIsCountedWithTheSpeechItHeard(@TempDir Path tmp) throws Exception {
        start(tmp);
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer helper = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        helper.createContext("/", x -> {
            x.getRequestBody().readAllBytes();
            seen.add(x.getRequestMethod() + " " + x.getRequestURI().getPath() + " " + x.getRequestHeaders().getFirst("Authorization"));
            byte[] b = (x.getRequestURI().getPath().endsWith("/audio/transcriptions") ? "{\"text\":\"the lighthouse was lit\",\"duration\":12.5}" : "{\"status\":\"ok\"}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        helper.start();
        try {
            set("RESEARCHZOSHO_WHISPER", "http://127.0.0.1:" + helper.getAddress().getPort() + "/whisper");
            set(Helpers.SECRET_NAME, "the-helpers-secret");
            AccountService.Made ann = AccountService.newAccount(store, tmp.resolve("accounts"), "Ann Example", "", false, "the test");
            String lib = ann.library().id();
            Map<String, String> env = new HashMap<>();
            gates.rewrite(env, lib);
            String whisper = env.get("RESEARCHZOSHO_WHISPER");
            assertEquals(gates.base() + Gated.LIB + lib + Gated.HELPER + lib + "/whisper", whisper, "the helper as the gate's address under the library's own path (round F)");
            assertEquals("off", env.get("RESEARCHZOSHO_METRICX"), "a helper the service has none of is off");
            assertEquals("off", env.get(Helpers.HELPERS), "and the library looks for no helpers machine of its own");
            assertTrue(!env.containsKey(Helpers.SECRET_NAME), "the secret stays at the node");
            AccountStore.Use u = new AccountStore.Use(AccountStore.id("use_"), ann.account().id(), lib, SpendPurse.RESEARCH, "", "operator", "", 0, 0, -1, -1, "open", "", System.currentTimeMillis(), 0, 0);
            store.addUse(u);
            assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(whisper + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(month(ann.account().id()).isEmpty(), "a look at it is not counted");
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(whisper + "/v1/audio/transcriptions")).header(Gated.USE, u.id()).POST(HttpRequest.BodyPublishers.ofString("audio")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("lighthouse"));
            assertEquals("POST /whisper/v1/audio/transcriptions Bearer the-helpers-secret", seen.get(1), "the gate added the secret the library does not have");
            assertEquals(13, quantity(ann.account().id(), "speech"), "the seconds of speech its answer says it heard");
            assertEquals(1, quantity(ann.account().id(), "helper"), "and the helper's seconds");
            assertEquals(2, store.month(ann.account().id(), AccountStore.month(System.currentTimeMillis())).stream().mapToLong(t -> t.payer().equals("operator") ? 1 : 0).sum());
            c.send(HttpRequest.newBuilder(URI.create(whisper + "/v1/models/small")).POST(HttpRequest.BodyPublishers.ofString("")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(2, quantity(ann.account().id(), "helper"), "a call with no use is the library's own: its keeper's");
            assertEquals(403, c.send(HttpRequest.newBuilder(URI.create(gates.base() + Gated.LIB + "lib_nobody" + Gated.HELPER + "lib_nobody/whisper/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "no library of the service's");
            // a helper called through one library's gate is that library's: another named in its path is refused (round F, money 9)
            assertEquals(403, c.send(HttpRequest.newBuilder(URI.create(gates.base() + Gated.LIB + "lib_nobody" + Gated.HELPER + lib + "/whisper/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, c.send(HttpRequest.newBuilder(URI.create(gates.base() + Gated.HELPER + lib + "/whisper/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "not outside a library's path");
        } finally { helper.stop(0); }
    }

    @Test
    void theLibrarysEmbedderGoesThroughTheGateWithTheUseOfTheWorkItIsPartOf(@TempDir Path tmp) throws Exception {
        start(tmp);   // the suite's own environment turns the embedder off in a library it starts: the library's client is asked here
        // an embeddings server is priced by a model entry that names it and its model, as any model is; without one its calls are counted at no dollars
        set(ModelEntries.key("embedder", "DRIVE"), model.url()); set(ModelEntries.key("embedder", "MODEL"), "stand-in-embed"); set(ModelEntries.key("embedder", "PRICES"), "20/0/0");
        Map<String, String> env = new HashMap<>(Map.of("RESEARCHZOSHO_EMBED", model.url()));
        gates.rewrite(env, "lib_acct_embed");
        assertTrue(env.get("RESEARCHZOSHO_EMBED").startsWith(gates.within("lib_acct_embed") + Gated.PATH), env.get("RESEARCHZOSHO_EMBED"));
        Embeddings.Embedder e = Embeddings.http(env.get("RESEARCHZOSHO_EMBED"), "stand-in-embed");
        AccountStore.Use u = use("acct_embed", -1);
        Gated.CONTEXT = () -> new String[]{u.id(), Gated.BACKGROUND, "en"};
        List<float[]> v = e.embedAll(List.of("The gear train was cut by hand.", "A dividing plate spaced the teeth."));
        assertEquals(2, v.size());
        assertTrue(v.get(0) != null && v.get(0).length == 8, "the vectors came back through the gate");
        Gated.CONTEXT = () -> null;
        assertTrue(e.embed("with nothing it is part of") == null, "a call of no use is refused at the gate: no vector");
        assertEquals(1, model.embeddings.get(), "the call of no use never reached the server");
        AccountStore.MonthTotal t = month(u.account()).get(0);
        assertEquals("model", t.kind()); assertEquals(1, t.quantity()); assertEquals(0, t.estimated(), "counted from the server's own usage");
        assertTrue(t.tokens() > 0, "the tokens the server said");
        assertEquals(Math.round(t.tokens() * 20.0), t.micros(), "at the prices of the entry that names the embedder");
        assertEquals("Bearer the-operators-model-key", model.lastAuthorization, "the gate added the key");
    }

    @Test
    void aCallIsCountedFromTheServersAnswerInEachShapeOrFromItsSizesAndTheGateHoldsTheKey(@TempDir Path tmp) throws Exception {
        start(tmp);
        String body = "{\"model\":\"stand-in\",\"messages\":[{\"role\":\"user\",\"content\":\"When was the lighthouse lit?\"}]}";
        record Case(String path, boolean stream, boolean usage) { }
        for (Case k : List.of(new Case("/v1/chat/completions", false, true), new Case("/v1/chat/completions", true, true), new Case("/v1/chat/completions", false, false),
                new Case("/v1/chat/completions", true, false), new Case("/v1/messages", false, true), new Case("/v1/messages", true, true), new Case("/v1/messages", false, false))) {
            model.usage = k.usage();
            AccountStore.Use u = use("acct_" + set.size() + k.hashCode(), -1);
            String b = k.stream() ? body.replace("{\"model\"", "{\"stream\":true,\"model\"") : body;
            HttpResponse<String> r = call(k.path(), u.id(), Gated.BACKGROUND, b);
            assertEquals(200, r.statusCode(), k + " " + r.body());
            assertTrue(r.body().contains("lighthouse"), "the answer passed on as it came: " + k);
            assertEquals("Bearer the-operators-model-key", model.lastAuthorization, "the gate added the key the library does not have");
            List<AccountStore.MonthTotal> m = month(u.account());
            assertEquals(1, m.size(), k.toString());
            AccountStore.MonthTotal t = m.get(0);
            assertEquals("model", t.kind()); assertEquals(1, t.quantity());
            assertTrue(t.tokens() > 0, "never none for a call that was made: " + k);
            assertEquals(k.usage() ? 0 : 1, t.estimated(), "estimated only when the answer said nothing: " + k);
            if (k.usage()) {
                long prompt = Math.max(1, b.length() / 4);
                assertEquals(prompt + 30, t.tokens(), "the server's own counts: " + k);
                assertEquals(Math.round((prompt * 1 + 30 * 2) * 1_000_000 / 1e6), t.micros(), "at the entry's prices: " + k);
            }
            assertEquals(t.micros(), store.useSpent(u.id()));
        }
        // Amazon Bedrock, a word and not an address, is not handed to a library: nothing would count it
        Map<String, String> words = new HashMap<>(Map.of("RESEARCHZOSHO_EMBED", "bedrock", "RESEARCHZOSHO_JUDGE_DRIVE", "off", "RESEARCHZOSHO_JOB_DRIVES", "bedrock," + model.url()));
        gates.rewrite(words);
        assertTrue(!words.containsKey("RESEARCHZOSHO_EMBED"), words.toString());
        assertEquals("off", words.get("RESEARCHZOSHO_JUDGE_DRIVE"));
        assertTrue(words.get("RESEARCHZOSHO_JOB_DRIVES").startsWith(gates.base() + Gated.PATH) && !words.get("RESEARCHZOSHO_JOB_DRIVES").contains("bedrock"), words.toString());
        // a probe that costs nothing passes with no use, and is not counted
        Map<String, String> env = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", model.url()));
        gates.rewrite(env);
        assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(env.get("RESEARCHZOSHO_DRIVE") + "/v1/models")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        // a server the settings do not name is not passed to
        HttpResponse<String> elsewhere = c.send(HttpRequest.newBuilder(URI.create(gates.base() + "/gate/https/example.org/v1/chat/completions")).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, elsewhere.statusCode());
    }

    @Test
    void aCallOfNoUseOrPastItsCeilingIsRefusedWithItsSentence(@TempDir Path tmp) throws Exception {
        start(tmp);
        String body = "{\"model\":\"stand-in\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
        HttpResponse<String> none = call("/v1/chat/completions", null, null, body);
        assertEquals(403, none.statusCode());
        assertTrue(none.body().contains("must be part of work the service let start") && none.body().contains(Gated.MARK), none.body());
        assertEquals(0, model.calls.get(), "nothing reached the model");
        // a use with a ceiling of $0.10: the gate lets it go a tenth past, at least $0.25, and then refuses
        AccountStore.Use u = use("acct_c", 100_000);
        store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), "acct_c", u.library(), u.id(), "model", "operator", "default", 1, 0, 1, 1, 0, 340_000, false)));
        assertEquals(200, call("/v1/chat/completions", u.id(), null, body).statusCode(), "under $0.35: its unit finishes");
        store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), "acct_c", u.library(), u.id(), "model", "operator", "default", 1, 0, 1, 1, 0, 20_000, false)));
        HttpResponse<String> over = call("/v1/chat/completions", u.id(), null, body);
        assertEquals(402, over.statusCode());
        assertTrue(over.body().contains("This work has spent the most it may ($0.10), so it stops here."), over.body());
        // the account's month: $5 and a tenth more, and then every use of it is refused
        AccountStore.Use v = use("acct_d", -1);
        store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), "acct_d", v.library(), "use_earlier", "model", "operator", "default", 1, 0, 1, 1, 0, 5_500_000, false)));
        HttpResponse<String> month = call("/v1/chat/completions", v.id(), null, body);
        assertEquals(402, month.statusCode()); assertTrue(month.body().contains("This account has used its $5.00 for this month."), month.body());
        // the library's own client says the gate's sentence, and asks no more
        Map<String, String> env = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", model.url()));
        gates.rewrite(env, v.library());
        Gated.CONTEXT = () -> new String[]{v.id(), Gated.WAITING, "en"};
        long before = model.calls.get();
        ArrayNode messages = M.createArrayNode();
        messages.addObject().put("role", "user").put("content", "hello");
        Gated.Refused refused = assertThrows(Gated.Refused.class, () -> new DriveClient(env.get("RESEARCHZOSHO_DRIVE"), "stand-in").chat(messages, M.createArrayNode(), 50));
        assertTrue(refused.getMessage().startsWith("This account has used its $5.00 for this month."), refused.getMessage());
        assertEquals(before, model.calls.get());
    }

    /** The paid search places, through the search gate: the key added where the place wants it, each search counted, the month's searches capped. */
    @Test
    void aSearchIsMadeWithTheKeyTheGateHoldsAndCountedAgainstTheMonth(@TempDir Path tmp) throws Exception {
        start(tmp);
        set("RESEARCHZOSHO_BRAVE_KEY", "the-operators-brave-key"); set("RESEARCHZOSHO_SERPAPI_KEY", "the-operators-serpapi-key");
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer place = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        place.createContext("/", x -> { seen.add(x.getRequestURI() + " token=" + x.getRequestHeaders().getFirst("X-Subscription-Token")); byte[] b = "{\"web\":{\"results\":[]}}".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        place.start();
        try {
            NodeGates.BASES.put("brave", "http://127.0.0.1:" + place.getAddress().getPort());
            NodeGates.BASES.put("serpapi", "http://127.0.0.1:" + place.getAddress().getPort());
            Map<String, String> env = new HashMap<>();
            gates.rewrite(env, "lib_acct_s");
            assertEquals(NodeGates.KEPT, env.get("RESEARCHZOSHO_BRAVE_KEY"), "the library is told the place may be used, and is given no key");
            AccountStore.Use u = use("acct_s", -1);
            store.setCap("acct_s", Caps.SEARCHES, 2);
            String search = env.get(NodeGates.SEARCH_GATE);
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(search + "/brave/res/v1/web/search?q=lighthouse")).header("X-Subscription-Token", NodeGates.KEPT).header(Gated.USE, u.id()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("/res/v1/web/search?q=lighthouse token=the-operators-brave-key", seen.get(0));
            r = c.send(HttpRequest.newBuilder(URI.create(search + "/serpapi/search.json?q=lighthouse&api_key=" + NodeGates.KEPT)).header(Gated.USE, u.id()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(seen.get(1).startsWith("/search.json?q=lighthouse&api_key=the-operators-serpapi-key"), seen.get(1));
            assertEquals(2, month("acct_s").stream().filter(t -> t.kind().equals("search")).mapToLong(AccountStore.MonthTotal::quantity).sum());
            r = c.send(HttpRequest.newBuilder(URI.create(search + "/brave/res/v1/web/search?q=again")).header(Gated.USE, u.id()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(402, r.statusCode());
            assertTrue(r.body().contains("This account has made as many searches this month as it may (2)."), r.body());
            assertEquals(2, seen.size(), "the third search never reached the place");
            // the library's own search places, pointed at the gate
            List<Map<String, String>> tables = List.of(WebSearchTool.GOOGLE, WebSearchTool.BRAVE_API, ExaSearchTool.BASE, TavilySearchTool.BASE, Firecrawl.BASE);
            List<Map<String, String>> were = new ArrayList<>();
            for (Map<String, String> t : tables) were.add(new HashMap<>(t));
            try {
                NodeGates.placesThrough(search);
                assertEquals(search + "/brave/res/v1/web/search", WebSearchTool.BRAVE_API.get("search"));
                assertTrue(ExaSearchTool.BASE.values().stream().allMatch(v -> v.startsWith(search + "/exa")), ExaSearchTool.BASE.toString());
                assertTrue(TavilySearchTool.BASE.values().stream().allMatch(v -> v.startsWith(search + "/tavily")), TavilySearchTool.BASE.toString());
                assertTrue(Firecrawl.BASE.values().stream().allMatch(v -> v.startsWith(search + "/firecrawl")), Firecrawl.BASE.toString());
            } finally {   // the tables are the whole program's: every later test reads them
                for (int i = 0; i < tables.size(); i++) { tables.get(i).clear(); tables.get(i).putAll(were.get(i)); }
            }
        } finally { place.stop(0); }
    }

    /**
     * Two lanes: account A's run fills the server's background (three readers at once, a server of two slots, each call a second),
     * and account B's chat turn, a person who waits, has the slot the waiting lane keeps: it is answered in about one call's time,
     * not after A's queue.
     */
    @Test
    void aPersonWhoWaitsIsAnsweredWhileAnotherAccountsRunFillsTheServer(@TempDir Path tmp) throws Exception {
        start(tmp);
        model.slots = 2; model.delayMs = 1_000;
        set(ModelEntries.key(ModelEntries.DEFAULT, "CALLS"), "2");
        String body = "{\"model\":\"stand-in\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
        AccountStore.Use run = use("acct_a", -1), chat = use("acct_b", -1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<HttpResponse<String>>> readers = new ArrayList<>();
            for (int i = 0; i < 6; i++) readers.add(pool.submit(() -> call("/v1/chat/completions", run.id(), Gated.BACKGROUND, body)));
            Thread.sleep(300);
            long t0 = System.currentTimeMillis();
            HttpResponse<String> answered = call("/v1/chat/completions", chat.id(), Gated.WAITING, body);
            long waited = System.currentTimeMillis() - t0;
            assertEquals(200, answered.statusCode());
            System.out.println("[lanes] the chat call of account B was answered in " + waited + " ms while account A's six background calls filled a server of two slots");
            assertTrue(waited < 1_800, "about one call's time, not behind A's queue: " + waited + " ms");
            for (Future<HttpResponse<String>> f : readers) assertEquals(200, f.get().statusCode());
            assertTrue(model.most.get() <= 2, "never more at once than the server has slots: " + model.most.get());
        } finally { pool.shutdownNow(); }
    }
}
