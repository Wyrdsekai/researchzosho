package org.researchzosho.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Safe search is moderate on every backend that has the setting: Brave's {@code safesearch=moderate} and SearXNG's {@code safesearch=1}
 * in the request ({@code SearxTest} checks the settings ResearchZosho writes for its own instance). Wikipedia, Crossref and OpenAlex have
 * no such setting.
 */
class SafeSearchTest {

    @Test
    void braveIsAskedForModerateSafeSearch() {
        String url = WebSearchTool.braveUrl("placeholder topic", 8);
        assertTrue(url.startsWith("https://api.search.brave.com/res/v1/web/search?"), url);
        assertTrue(url.contains("&safesearch=moderate"), url);
        assertTrue(WebSearchTool.braveUrl("日本語の資料", 8).contains("&search_lang=ja&safesearch=moderate"), "the language stays beside it");
    }

    @Test
    void searxngIsAskedForModerateSafeSearchInEveryRequest() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/search", ex -> {
            asked.add(ex.getRequestURI().getRawQuery());
            byte[] b = "{\"results\":[{\"title\":\"Placeholder page\",\"url\":\"https://example.org/a\",\"content\":\"placeholder text\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        s.start();
        WebSearchTool.endpointOverride = "http://127.0.0.1:" + s.getAddress().getPort();
        try {
            String out = new WebSearchTool().execute(new ObjectMapper().readTree("{\"query\":\"placeholder topic\"}"));
            assertTrue(out.contains("https://example.org/a"), out);
            assertEquals(1, asked.size());
            assertTrue(asked.get(0).contains("safesearch=1"), asked.get(0));
            assertTrue(WebSearchTool.searxUrl("placeholder topic").endsWith("&safesearch=1"));
        } finally {
            WebSearchTool.endpointOverride = null;
            s.stop(0);
        }
    }

    @Test
    void aRunThatLetsInPornographyAndGoreSearchesWithSafeSearchOffForThatRunOnly() throws Exception {
        assertTrue(WebSearchTool.braveUrl("placeholder topic", 8, false).endsWith("&safesearch=off"));
        assertTrue(WebSearchTool.searxUrl("placeholder topic", false).endsWith("&safesearch=0"));
        List<String> asked = new CopyOnWriteArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/search", ex -> {
            asked.add(ex.getRequestURI().getRawQuery());
            byte[] b = "{\"results\":[{\"title\":\"Placeholder page\",\"url\":\"https://example.org/a\",\"content\":\"placeholder text\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        s.start();
        WebSearchTool.endpointOverride = "http://127.0.0.1:" + s.getAddress().getPort();
        try {
            ObjectMapper m = new ObjectMapper();
            new WebSearchTool().policy(ContentPolicy.run(List.of(ContentPolicy.EXPLICIT), null, null).fetchPolicy()).execute(m.readTree("{\"query\":\"placeholder topic\"}"));
            new WebSearchTool().policy(ContentPolicy.run(List.of(ContentPolicy.HOWTO), null, null).fetchPolicy()).execute(m.readTree("{\"query\":\"placeholder topic two\"}"));
            new WebSearchTool().execute(m.readTree("{\"query\":\"placeholder topic three\"}"));
            assertEquals(3, asked.size());
            assertTrue(asked.get(0).endsWith("&safesearch=0"), "the yes to explicit material: off for that run: " + asked.get(0));
            assertTrue(asked.get(1).endsWith("&safesearch=1"), "a yes to instructions only: moderate");
            assertTrue(asked.get(2).endsWith("&safesearch=1"), "every other run: moderate");
        } finally {
            WebSearchTool.endpointOverride = null;
            s.stop(0);
        }
    }
}
