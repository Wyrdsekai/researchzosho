package org.researchzosho.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.OutsideServices;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monthly limits of the paid keys that had none, Serper, SerpApi, Brave and Firecrawl, against stand-in servers: no limit unless one
 * is set; a call reserved before it goes and counted when made; at the limit the service is passed over until the month turns, and the
 * answer that went past it says so once.
 */
class PaidLimitsTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    HttpServer server; String base, realHome;
    final Map<String, AtomicInteger> hits = new HashMap<>();
    final Map<String, String> savedGoogle = new HashMap<>(), savedBrave = new HashMap<>(), savedFirecrawl = new HashMap<>();

    @BeforeEach void stub() throws Exception {
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        SearchTally.override = tmp.resolve("tally.json");
        savedGoogle.putAll(WebSearchTool.GOOGLE); savedBrave.putAll(WebSearchTool.BRAVE_API); savedFirecrawl.putAll(Firecrawl.BASE);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        answer("/serper/search", 200, "{\"organic\":[{\"position\":1,\"title\":\"From Serper\",\"link\":\"https://serper.example/1\",\"snippet\":\"one\"}],\"credits\":1}");
        answer("/serpapi/search.json", 200, "{\"organic_results\":[{\"position\":1,\"title\":\"From SerpApi\",\"link\":\"https://serpapi.example/1\",\"snippet\":\"two\"}]}");
        answer("/brave/search", 200, "{\"web\":{\"results\":[{\"title\":\"From Brave\",\"url\":\"https://brave.example/1\",\"description\":\"three\"}]}}");
        answer("/searxng/search", 200, "{\"results\":[{\"title\":\"From SearXNG\",\"url\":\"https://searxng.example/1\",\"content\":\"four\"}]}");
        answer("/firecrawl/scrape", 200, "{\"success\":true,\"data\":{\"markdown\":\"All companies\\n\\n- Acme Corp\\n- Beta Ltd\",\"metadata\":{\"title\":\"Companies\",\"statusCode\":200,\"creditsUsed\":1}}}");
        server.createContext("/", x -> send(x, x.getRequestURI().getPath().startsWith("/wall") ? 403 : 404, "<html><title>403 Forbidden</title><body>Forbidden</body></html>"));   // the walled pages, and no archived copy of anything
        server.start();
        WebSearchTool.GOOGLE.put("serper", base + "/serper/search"); WebSearchTool.GOOGLE.put("serpapi", base + "/serpapi/search.json");
        WebSearchTool.BRAVE_API.put("search", base + "/brave/search");
        Firecrawl.BASE.put("scrape", base + "/firecrawl/scrape");
        Archives.WAYBACK = base; Archives.ARCHIVE_TODAY = base;
        Fetch.allowLoopback = true;
        Config.set("RESEARCHZOSHO_FALLBACK_SEARCH", "off");   // nothing past the stand-ins
    }

    @AfterEach void restore() {
        server.stop(0);
        WebSearchTool.GOOGLE.putAll(savedGoogle); WebSearchTool.BRAVE_API.putAll(savedBrave); Firecrawl.BASE.putAll(savedFirecrawl);
        Archives.WAYBACK = "https://web.archive.org"; Archives.ARCHIVE_TODAY = "https://archive.ph"; Fetch.allowLoopback = false;
        SearchTally.override = null; System.setProperty("user.home", realHome); Config.invalidate();
    }

    void answer(String path, int status, String body) {
        hits.put(path, new AtomicInteger());
        server.createContext(path, x -> { hits.get(path).incrementAndGet(); send(x, status, body); });
    }

    static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "text/html; charset=utf-8");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    static String search(WebSearchTool t, String query) throws Exception { return t.execute(M.createObjectNode().put("query", query)); }

    static int calls(String service) { return SearchTally.thisMonth().getOrDefault(service, new int[]{0, 0})[0]; }

    @Test void withNoLimitSetThereIsNoneAndThePageShowsTheUseAlone() throws Exception {
        Config.set(WebSearchTool.SERPER_KEY, "serper-test-key");
        WebSearchTool t = new WebSearchTool();
        for (int i = 1; i <= 3; i++) assertTrue(search(t, "query " + i).contains("From Serper"));
        assertEquals(3, hits.get("/serper/search").get());
        for (String s : new String[]{WebSearchTool.SERPER, WebSearchTool.SERPAPI, WebSearchTool.BRAVE, Firecrawl.TALLY}) assertEquals(-1, SearchTally.limit(s), s + " has no limit unless one is set");
        assertEquals("3 calls, 3 credits", use("Serper"));
        Config.set(WebSearchTool.SERPER_LIMIT, "10");
        assertEquals("3 of the 10 a month the library stops at, 3 credits", use("Serper"), "the month's use beside the limit, on Sources and keys");
        assertTrue(SearchTally.summary().contains("3 searches through Serper (the library stops at 10 a month), 3 credits"), SearchTally.summary());
    }

    static String use(String service) {
        for (OutsideServices.Row r : OutsideServices.rows(null)) if (r.service().name().equals(service)) return OutsideServices.use(r);
        return "";
    }

    @Test void serperStopsAtItsLimitAndTheSearchGoesOnToSerpApiSayingSoOnce() throws Exception {
        Config.set(WebSearchTool.SERPER_KEY, "serper-test-key"); Config.set(WebSearchTool.SERPAPI_KEY, "serpapi-test-key"); Config.set(WebSearchTool.SERPER_LIMIT, "1");
        Config.set("RESEARCHZOSHO_SEARXNG", base + "/nosearx");   // a SearXNG that is not there, at the stand-in, never the box's own
        WebSearchTool t = new WebSearchTool();
        String first = search(t, "first query");
        assertTrue(first.contains("(Google, through Serper)") && !first.contains("This month's"), first);
        String second = search(t, "second query");
        assertTrue(second.contains("(Google, through SerpApi)"), second);
        assertTrue(second.contains("(This month's 1 search through Serper is used, so Serper is left off until the month turns; the library's keeper can raise serper_monthly_limit in the settings.)"), second);
        String third = search(t, "third query");
        assertTrue(third.contains("(Google, through SerpApi)") && !third.contains("This month's"), "said once by each search tool: " + third);
        assertTrue(search(new WebSearchTool(), "fourth query").contains("This month's 1 search through Serper is used"), "another worker's tool says it once too");
        assertEquals(1, hits.get("/serper/search").get(), "no call went to Serper past its limit");
        assertEquals(1, calls(WebSearchTool.SERPER)); assertEquals(3, calls(WebSearchTool.SERPAPI));
        Config.set(WebSearchTool.SERPAPI_LIMIT, "3");
        String none = search(t, "fifth query");
        assertTrue(none.startsWith("ERROR: search returned HTTP 404"), "both Google keys at their limits: the next way of searching, here a SearXNG that is not there: " + none);
        assertTrue(none.contains("through SerpApi are used"), none);
        assertEquals(3, hits.get("/serpapi/search.json").get());
    }

    @Test void braveStopsAtItsLimitAndSearXngAnswers() throws Exception {
        Config.set("RESEARCHZOSHO_BRAVE_KEY", "brave-test-key"); Config.set(WebSearchTool.BRAVE_LIMIT, "1"); Config.set("RESEARCHZOSHO_SEARXNG", base + "/searxng");
        WebSearchTool t = new WebSearchTool();
        assertTrue(search(t, "first query").contains("From Brave"));
        String second = search(t, "second query");
        assertTrue(second.contains("From SearXNG") && !second.contains("From Brave"), second);
        assertTrue(second.contains("(This month's 1 search through Brave is used, so Brave is left off until the month turns; the library's keeper can raise brave_monthly_limit in the settings.)"), second);
        assertEquals(1, hits.get("/brave/search").get());
        assertEquals(1, calls(WebSearchTool.BRAVE));
    }

    @Test void firecrawlStopsAtItsLimitAndTheFetchSaysSo() throws Exception {
        Config.set(Firecrawl.KEY, "fc-test-key"); Config.set(Firecrawl.LIMIT, "1");
        String first = new WebFetchTool().execute(M.createObjectNode().put("url", base + "/wall-one"));
        assertTrue(first.startsWith("Read through Firecrawl's browser, because a plain fetch got HTTP 403. "), first);
        String second = new WebFetchTool().execute(M.createObjectNode().put("url", base + "/wall-two"));
        assertTrue(second.startsWith("ERROR: HTTP 403 for " + base + "/wall-two"), second);
        assertTrue(second.contains("This month's 1 page through Firecrawl is used, so Firecrawl is left off until the month turns; the library's keeper can raise firecrawl_monthly_limit in the settings."), second);
        assertEquals(1, hits.get("/firecrawl/scrape").get(), "no page went to Firecrawl past its limit");
        assertEquals(1, calls(Firecrawl.TALLY));
        String notWalled = new WebFetchTool().execute(M.createObjectNode().put("url", base + "/nowhere"));
        assertFalse(notWalled.contains("Firecrawl"), "a page that is not there was never Firecrawl's to read: " + notWalled);
    }

    @Test void workersSearchingAtOnceNeverPassTheLimitTogether() throws Exception {
        Config.set(WebSearchTool.SERPER_KEY, "serper-test-key"); Config.set(WebSearchTool.SERPER_LIMIT, "3"); Config.set("RESEARCHZOSHO_SEARXNG", base + "/searxng");
        Thread[] workers = new Thread[8];
        for (int i = 0; i < workers.length; i++) {
            int n = i;
            workers[i] = new Thread(() -> { try { search(new WebSearchTool(), "worker " + n); } catch (Exception e) { throw new IllegalStateException(e); } });
            workers[i].start();
        }
        for (Thread w : workers) w.join(30_000);
        assertEquals(3, hits.get("/serper/search").get(), "a call is reserved before it goes");
        assertEquals(3, calls(WebSearchTool.SERPER));
        assertEquals(5, hits.get("/searxng/search").get(), "the rest went on to the next way of searching");
    }
}
