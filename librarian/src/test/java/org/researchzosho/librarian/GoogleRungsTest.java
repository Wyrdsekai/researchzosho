package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.Firecrawl;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.WebFetchTool;
import org.researchzosho.tools.WebSearchTool;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Google through Serper and SerpApi, pages through Firecrawl, and the month's tally, against stand-in servers: nothing here touches the web or the box's settings. */
class GoogleRungsTest {
    static final ObjectMapper M = new ObjectMapper();
    HttpServer server; String base, realHome; boolean loopbackWas; Map<String, String> savedGoogle = new HashMap<>(), savedFirecrawl = new HashMap<>();
    final AtomicInteger resolveCalls = new AtomicInteger();

    static boolean loopback() throws Exception { Field f = Fetch.class.getDeclaredField("allowLoopback"); f.setAccessible(true); return f.getBoolean(null); }
    static void setLoopback(boolean v) throws Exception { Field f = Fetch.class.getDeclaredField("allowLoopback"); f.setAccessible(true); f.set(null, v); }
    static void tallyAt(Path p) throws Exception { Field f = SearchTally.class.getDeclaredField("override"); f.setAccessible(true); f.set(null, p); }

    @BeforeEach void stub(@TempDir Path tmp) throws Exception {
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        loopbackWas = loopback(); tallyAt(tmp.resolve("tally.json"));
        savedGoogle.putAll(WebSearchTool.GOOGLE); savedFirecrawl.putAll(Firecrawl.BASE);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/serper/search", x -> send(x, 200, "{\"organic\":[{\"position\":1,\"title\":\"Seafile forum\",\"link\":\"https://forum.seafile.example/t/why\",\"snippet\":\"why do people say\"},"
                + "{\"position\":2,\"title\":\"XDA on Seafile\",\"link\":\"https://www.google.com/goto?url=CAESone\",\"displayedLink\":\"https://www.xda.example › completely\",\"snippet\":\"switched\"},"
                + "{\"position\":3,\"title\":\"Luma\",\"link\":\"https://www.google.com/goto?url=CAEStwo\",\"displayedLink\":\"https://lumadock.example › tutorials\",\"snippet\":\"compared\"},"
                + "{\"position\":4,\"title\":\"Video\",\"link\":\"https://www.google.com/goto?url=CAESthree\",\"displayedLink\":\"1.2K+ views\",\"snippet\":\"a video\"}],\"credits\":1}"));
        server.createContext("/serper/resolve", x -> {   // the first call releases one link and asks for a second try; the second releases one more; the third stays unreleased
            int n = resolveCalls.incrementAndGet();
            send(x, 200, n == 1 ? "{\"links\":[\"https://www.xda.example/completely-uprooted/\"],\"retryAfter\":1,\"credits\":0}" : "{\"links\":[\"https://lumadock.example/tutorials/seafile\"],\"credits\":0}");
        });
        server.createContext("/serpapi/search.json", x -> send(x, 200, "{\"search_metadata\":{\"status\":\"Success\"},\"organic_results\":[{\"position\":1,\"title\":\"Nextcloud\",\"link\":\"/goto?url=CAESa\",\"redirect_link\":\"https://www.google.com/url?sa=t&url=CAESb\",\"displayed_link\":\"https://nextcloud.example\",\"snippet\":\"the suite\"},"
                + "{\"position\":2,\"title\":\"Play\",\"link\":\"https://play.example/store/apps\",\"displayed_link\":\"https://play.example › store\",\"snippet\":\"the app\"}]}"));
        server.createContext("/wall", x -> send(x, 403, "<html><title>403 Forbidden</title><body>Forbidden</body></html>"));
        server.createContext("/firecrawl/scrape", x -> send(x, 200, "{\"success\":true,\"data\":{\"markdown\":\"All companies\\n\\n- Acme Corp\\n- Beta Ltd\\n- Gamma Inc\",\"metadata\":{\"title\":\"Companies\",\"statusCode\":200,\"sourceURL\":\"" + base + "/wall\",\"creditsUsed\":1}}}"));
        server.start();
        WebSearchTool.GOOGLE.put("serper", base + "/serper/search"); WebSearchTool.GOOGLE.put("serper_resolve", base + "/serper/resolve"); WebSearchTool.GOOGLE.put("serpapi", base + "/serpapi/search.json");
        Firecrawl.BASE.put("scrape", base + "/firecrawl/scrape");
    }

    @AfterEach void restore() throws Exception { server.stop(0); WebSearchTool.GOOGLE.putAll(savedGoogle); Firecrawl.BASE.putAll(savedFirecrawl); setLoopback(loopbackWas); tallyAt(null); System.setProperty("user.home", realHome); Config.invalidate(); }

    static void send(HttpExchange x, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", status == 403 ? "text/html; charset=utf-8" : "application/json");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    @Test void serperAnswersFirstItsUnreleasedLinksAreResolvedWithASecondTryAndTheMonthsTallyCountsIt() throws Exception {
        Config.set(WebSearchTool.SERPER_KEY, "serper-test-key");
        int before = WebSearchTool.SERPER_USED.get();
        String out = new WebSearchTool().execute(M.createObjectNode().put("query", "seafile vs nextcloud").put("limit", 5));
        assertTrue(out.startsWith("results for \"seafile vs nextcloud\" (Google, through Serper):"), out);
        assertTrue(out.contains("   https://www.xda.example/completely-uprooted/  [") && out.contains("   https://lumadock.example/tutorials/seafile  ["), "the two released on the first and second try: " + out);
        assertTrue(out.contains("   https://www.google.com/goto?url=CAESthree  [a Google redirect Google has not released yet, to 1.2K+ views; search for the page by that name to read it]"), out);
        assertTrue(out.contains("(1 address(es) are Google redirects Google has not released yet"), out);
        assertEquals(2, resolveCalls.get(), "a second try after retryAfter, then no third");
        assertEquals(before + 1, WebSearchTool.SERPER_USED.get());
        assertEquals(1, SearchTally.thisMonth().get("Serper")[0]); assertEquals(1, SearchTally.thisMonth().get("Serper")[1]);
        assertTrue(SearchTally.summary().startsWith("This month, ") && SearchTally.summary().contains(": 1 search through Serper, 1 credit."), SearchTally.summary());
    }

    @Test void serpApiAnswersWhenOnlyItsKeyIsSetAndShowsGooglesRelativeRedirectsWithTheSitesName() throws Exception {
        Config.set(WebSearchTool.SERPAPI_KEY, "serpapi-test-key");
        int before = WebSearchTool.SERPAPI_USED.get();
        String out = new WebSearchTool().execute(M.createObjectNode().put("query", "nextcloud").put("limit", 5));
        assertTrue(out.startsWith("results for \"nextcloud\" (Google, through SerpApi):"), out);
        assertTrue(out.contains("   https://www.google.com/goto?url=CAESa  [a Google redirect Google has not released yet, to https://nextcloud.example; search for the page by that name to read it]"), out);
        assertTrue(out.contains("   https://play.example/store/apps  [web]"), out);
        assertEquals(before + 1, WebSearchTool.SERPAPI_USED.get());
        assertEquals(1, SearchTally.thisMonth().get("SerpApi")[0]);
    }

    @Test void aPageThatTurnsAPlainFetchAwayIsReadThroughFirecrawlOnlyWithAKeyAndCounted() throws Exception {
        setLoopback(true);
        String without = new WebFetchTool().execute(M.createObjectNode().put("url", base + "/wall"));
        assertTrue(without.startsWith("ERROR: HTTP 403"), "no key: the 403 stands: " + without);
        Config.set(Firecrawl.KEY, "fc-test-key");
        int before = Firecrawl.USED.get();
        String out = new WebFetchTool().execute(M.createObjectNode().put("url", base + "/wall"));
        assertTrue(out.startsWith("Read through Firecrawl's browser, because a plain fetch got HTTP 403. "), out);
        assertTrue(out.contains("Acme Corp") && out.contains("Gamma Inc"), out);
        assertFalse(out.contains("Forbidden"), out);
        assertEquals(before + 1, Firecrawl.USED.get());
        assertTrue(SearchTally.summary().contains("1 page through Firecrawl, 1 credit"), SearchTally.summary());
    }

    @Test void withNoKeyNothingIsCountedAndTheSummarySaysSo() {
        assertTrue(SearchTally.summary().startsWith("Nothing counted this month yet"), SearchTally.summary());
    }
}
