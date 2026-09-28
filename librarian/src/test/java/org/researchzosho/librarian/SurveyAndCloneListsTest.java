package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.PageCheck;
import org.researchzosho.tools.SiteList;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A survey of the person's own address is the person's act, as an address they add is: no list stands in its way, and the page is
 * asked the always-dropped question before its text is saved. A repository the library clones is checked against the person's refused
 * sources and the site list first, as a fetch is. Placeholder pages and hosts only; a local server stands for the web.
 */
class SurveyAndCloneListsTest {

    @TempDir Path home;
    LibraryStore store;
    HttpServer web;
    String base;

    private static void setLoopback(boolean v) throws Exception { var f = Fetch.class.getDeclaredField("allowLoopback"); f.setAccessible(true); f.set(null, v); }

    @BeforeEach void up() throws Exception {
        store = new LibraryStore(home.resolve("lib")); store.init();
        web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        web.createContext("/ordinary", x -> send(x, "<html><head><title>A placeholder product</title></head><body><main><p>" + "The placeholder product ranks tide tables. ".repeat(12) + "</p></main></body></html>"));
        web.createContext("/marked", x -> send(x, "<html><head><title>A placeholder page</title></head><body><main><p>" + "MARKER-CHILD placeholder words. ".repeat(12) + "</p></main></body></html>"));
        web.start();
        base = "http://127.0.0.1:" + web.getAddress().getPort();
        setLoopback(true);
        SiteList.use(SiteList.of("test", "listed-placeholder.example"));
        ContentJudge.use(new ContentJudge(null, messages -> {
            String c = messages.get(0).path("content").asText();
            return c.contains(PageCheck.Category.CHILD.question()) && c.contains("MARKER-CHILD") ? "yes" : "no";
        }));
    }

    private static void send(HttpExchange x, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "text/html"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
    }

    @AfterEach void down() throws Exception { setLoopback(false); web.stop(0); SourceRules.OVERRIDE = null; ContentJudge.use(null); }

    @Test
    void aSurveyOfThePersonsOwnAddressReadsItAndAsksTheAlwaysDroppedQuestion() throws Exception {
        Surveys.Read read = Surveys.read(store, Surveys.Kind.site, base + "/ordinary");
        assertTrue(read.text().contains("ranks tide tables"));
        IOException e = assertThrows(IOException.class, () -> Surveys.read(store, Surveys.Kind.site, base + "/marked"));
        assertTrue(e.getMessage().contains("it was left out as sexual content involving a child"), e.getMessage());
        // the person's own site on the site list: read all the same, under the person's policy
        List<Fetch.Policy> asked = new CopyOnWriteArrayList<>();
        PageCheck.useGetter((url, timeout, lists) -> { asked.add(lists); return new Fetch.Result(url, 200, ("<html><head><title>A placeholder</title></head><body><main><p>" + "Placeholder words about a placeholder product. ".repeat(12) + "</p></main></body></html>").getBytes(StandardCharsets.UTF_8), "text/html"); });
        try {
            assertTrue(Surveys.read(store, Surveys.Kind.site, "https://listed-placeholder.example/product").text().contains("placeholder product"));
            assertEquals(List.of(Fetch.Policy.PERSON), asked);
        } finally { PageCheck.useGetter(null); }
    }

    @Test
    void aCloneIsCheckedAgainstTheRefusedSourcesAndTheSiteList() throws Exception {
        IOException listed = assertThrows(IOException.class, () -> Repos.obtain(store, "https://listed-placeholder.example/someone/tidebook.git"));
        assertEquals("The library did not clone https://listed-placeholder.example/someone/tidebook.git, because its site is on the list of pornography, shock and gore sites.", listed.getMessage());
        assertThrows(IOException.class, () -> Repos.obtain(store, "git@listed-placeholder.example:someone/tidebook.git"), "the scp form too");
        SourceRules.set(store, "refuse", "refused-placeholder.example", "placeholder reason");
        SourceRules.OVERRIDE = store;
        IOException refused = assertThrows(IOException.class, () -> Repos.obtain(store, "https://refused-placeholder.example/someone/tidebook"));
        assertTrue(refused.getMessage().contains("refused-sources list"), refused.getMessage());
    }
}
