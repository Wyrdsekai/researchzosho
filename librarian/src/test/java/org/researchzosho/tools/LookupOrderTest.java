package org.researchzosho.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A look-up (a known employer's careers page, website or size) asks the free engine first and a paid place only for what it leaves
 * empty; a piece of work counts its searches by the engine that answered and holds to its most of paid ones, a work inside it to its
 * own most as well. Against stand-in servers, as {@link PaidLimitsTest}.
 */
class LookupOrderTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    HttpServer server; String base, realHome;
    final Map<String, AtomicInteger> hits = new HashMap<>();
    final Map<String, String> savedBrave = new HashMap<>();
    volatile String searx = "{\"results\":[{\"title\":\"Acme careers\",\"url\":\"https://acme.example/careers\",\"content\":\"jobs\"}]}";

    @BeforeEach void stub() throws Exception {
        WebSearchTool.searxUp();
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        SearchTally.override = tmp.resolve("tally.json");
        savedBrave.putAll(WebSearchTool.BRAVE_API);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        hits.put("/brave/search", new AtomicInteger()); hits.put("/searxng/search", new AtomicInteger());
        server.createContext("/brave/search", x -> { hits.get("/brave/search").incrementAndGet(); send(x, "{\"web\":{\"results\":[{\"title\":\"From Brave\",\"url\":\"https://brave.example/1\",\"description\":\"three\"}]}}"); });
        server.createContext("/searxng/search", x -> { hits.get("/searxng/search").incrementAndGet(); send(x, searx); });
        server.start();
        WebSearchTool.BRAVE_API.put("search", base + "/brave/search");
        Config.set("RESEARCHZOSHO_FALLBACK_SEARCH", "off");   // nothing past the stand-ins
        Config.set("RESEARCHZOSHO_BRAVE_KEY", "brave-test-key");
        Config.set("RESEARCHZOSHO_SEARXNG", base + "/searxng");
    }

    @AfterEach void restore() {
        server.stop(0);
        WebSearchTool.searxUp();
        WebSearchTool.BRAVE_API.putAll(savedBrave);
        SearchTally.override = null; System.setProperty("user.home", realHome); Config.invalidate();
    }

    static void send(HttpExchange x, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(200, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    static String search(String query) {
        try { return new WebSearchTool().execute(M.createObjectNode().put("query", query)); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test void aLookUpAsksTheFreeEngineFirstAndAPaidPlaceOnlyForWhatItLeavesEmpty() throws Exception {
        assertTrue(search("security engineer jobs").contains("From Brave"), "any other search: the paid place the keeper set, first");
        assertEquals(1, hits.get("/brave/search").get()); assertEquals(0, hits.get("/searxng/search").get());

        String found = WebSearchTool.lookup(() -> search("Acme careers jobs"));
        assertTrue(found.contains("https://acme.example/careers") && !found.contains("From Brave"), found);
        assertEquals(1, hits.get("/brave/search").get(), "the look-up cost no paid search");
        assertEquals(1, hits.get("/searxng/search").get());

        searx = "{\"results\":[]}";   // the free engine knows nothing of this one: the paid place answers it
        assertTrue(WebSearchTool.lookup(() -> search("Obscure Ltd careers jobs")).contains("From Brave"));
        assertEquals(2, hits.get("/brave/search").get());
        assertEquals(2, hits.get("/searxng/search").get(), "asked once: SearXNG is not asked again after the paid place");

        Config.set("RESEARCHZOSHO_BRAVE_KEY", "");   // and with no paid place either: nothing, never the fallback's encyclopedia pages, even with the fallback on
        Config.set("RESEARCHZOSHO_FALLBACK_SEARCH", "on");
        assertEquals("no results for: Nameless Co careers jobs", WebSearchTool.lookup(() -> search("Nameless Co careers jobs")).strip());
        Config.set("RESEARCHZOSHO_FALLBACK_SEARCH", "off"); Config.set("RESEARCHZOSHO_BRAVE_KEY", "brave-test-key");

        server.removeContext("/searxng/search");   // no free engine running: the look-up goes to the paid place, as before
        Config.set("RESEARCHZOSHO_SEARXNG", "http://127.0.0.1:1");
        assertTrue(WebSearchTool.lookup(() -> search("Other Co careers jobs")).contains("From Brave"));
        assertEquals(3, hits.get("/brave/search").get());
    }

    @Test void aWorkCountsItsSearchesByEngineAndHoldsToItsMostOfPaidOnesAndAWorkInsideItToItsOwn() {
        SearchTally.Work run = SearchTally.begin("this career search", 3);
        SearchTally.Work lookups = SearchTally.within("this career search's look-ups of employers", 1);
        try {
            assertTrue(search("one").contains("From Brave"));
            searx = "{\"results\":[]}";
            assertTrue(SearchTally.during(lookups, () -> WebSearchTool.lookup(() -> search("Obscure Ltd careers jobs"))).contains("From Brave"), "the look-ups' one paid search");
            String past = SearchTally.during(lookups, () -> WebSearchTool.lookup(() -> search("Other Ltd careers jobs")));
            assertTrue(past.startsWith("no results for: Other Ltd careers jobs"), "past the look-ups' most, the paid place is left off for them: " + past);
            assertTrue(past.contains("(This career search's look-ups of employers made the 1 paid search it may make, so Brave is left off for the rest of it.)"), past);
            assertTrue(search("two").contains("From Brave"), "the search itself has room left");
            String third = search("three");
            assertTrue(third.contains("(This career search made the 3 paid searches it may make, so Brave is left off for the rest of it.)") && !third.contains("From Brave"), third);
        } finally { SearchTally.end(run); }
        assertEquals(3, hits.get("/brave/search").get(), "no paid search past a most");
        assertEquals(3, run.paid()); assertEquals(Map.of("Brave", 3), run.calls(), "SearXNG gave nothing, so it answered none");
        assertEquals(1, lookups.paid()); assertEquals(Map.of("Brave", 1), lookups.calls());
        assertTrue(search("after").contains("From Brave"), "the work is over: its most holds no more");
        assertEquals(3, run.paid(), "and a search after it is not counted in it");
    }
}
