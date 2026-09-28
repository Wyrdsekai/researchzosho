package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.records.RecordSource;
import org.researchzosho.records.RecordSources;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.ScholarSearch;
import org.researchzosho.tools.SiteList;
import org.researchzosho.tools.WebFetchTool;
import org.researchzosho.tools.WebSearchTool;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the site list and the person's refused list apply: search results before the model sees them (web search, scholarly search,
 * record search), and every fetch, at every redirect hop, through the fetch's policy. The person's own address is fetched under the
 * person's policy. Every domain here is a placeholder, and nothing leaves this machine.
 */
class SiteListAppliedTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String LISTED = "placeholder-adult.example", REFUSED = "refused-placeholder.example";

    @TempDir Path home;
    LibraryStore store;

    @BeforeEach void lists() throws Exception {
        SiteList.use(SiteList.of("test list", LISTED));
        store = new LibraryStore(home.resolve("lib")); store.init();
        SourceRules.set(store, "refuse", REFUSED, "placeholder reason");
        SourceRules.OVERRIDE = store;
    }

    @AfterEach void back() throws Exception {
        SourceRules.OVERRIDE = null;
        setLoopback(false);
    }

    static void setLoopback(boolean v) throws Exception { Field f = Fetch.class.getDeclaredField("allowLoopback"); f.setAccessible(true); f.set(null, v); }

    static void searxEndpoint(String v) throws Exception { Field f = WebSearchTool.class.getDeclaredField("endpointOverride"); f.setAccessible(true); f.set(null, v); }

    @Test
    void aSiteThePersonTrustsIsNeverStoppedByTheSiteList() throws Exception {
        SiteList.use(SiteList.of("test list", LISTED, "trusted-listed-placeholder.example"));
        SourceRules.set(store, "trust", "trusted-listed-placeholder.example", "placeholder reason: the person's own archive");
        String trusted = "https://trusted-listed-placeholder.example/page";
        assertNull(Fetch.Policy.DEFAULT.leftOut(trusted), "the person's own list outranks somebody else's");
        assertFalse(Fetch.Policy.DEFAULT.onSiteList(trusted));
        assertNotNull(Fetch.Policy.DEFAULT.leftOut("https://www." + LISTED + "/page"), "the rest of the list still applies");
    }

    @Test
    void thePoliciesSayWhichListsApply() {
        String listed = "https://www." + LISTED + "/page", refused = "https://" + REFUSED + "/page";
        assertEquals("its site is on the list of pornography, shock and gore sites", Fetch.Policy.DEFAULT.leftOut(listed));
        assertEquals("its site is on the person's refused-sources list", Fetch.Policy.DEFAULT.leftOut(refused));
        assertNull(Fetch.Policy.DEFAULT.leftOut("https://example.org/page"));
        assertNull(Fetch.Policy.SITE_LIST_OFF.leftOut(listed), "a run whose person let the material in");
        assertNotNull(Fetch.Policy.SITE_LIST_OFF.leftOut(refused), "the person's own refused list still applies to that run");
        assertNull(Fetch.Policy.PERSON.leftOut(listed), "the person's own address");
        assertNull(Fetch.Policy.PERSON.leftOut(refused));
    }

    @Test
    void searchResultsOnEitherListAreLeftOutBeforeTheModelSeesThem() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/search", ex -> {
            byte[] b = ("{\"results\":[{\"title\":\"Listed placeholder\",\"url\":\"https://www." + LISTED + "/a\",\"content\":\"placeholder\"},"
                    + "{\"title\":\"Refused placeholder\",\"url\":\"https://" + REFUSED + "/b\",\"content\":\"placeholder\"},"
                    + "{\"title\":\"Ordinary placeholder\",\"url\":\"https://example.org/c\",\"content\":\"placeholder\"}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        s.start();
        searxEndpoint("http://127.0.0.1:" + s.getAddress().getPort());
        try {
            String out = new WebSearchTool().execute(M.readTree("{\"query\":\"placeholder one\"}"));
            assertTrue(out.contains("https://example.org/c"), out);
            assertFalse(out.contains(LISTED) || out.contains("Listed placeholder"), "nothing of a listed result is shown: " + out);
            assertFalse(out.contains(REFUSED), out);
            assertTrue(out.contains("(1 result(s) left out: on the list of pornography, shock and gore sites)"), out);
            assertTrue(out.contains("(1 result(s) left out: on the person's refused-sources list)"), out);
            // the run whose person let the material in sees the listed result; the refused one stays out
            String letIn = new WebSearchTool().policy(Fetch.Policy.SITE_LIST_OFF).execute(M.readTree("{\"query\":\"placeholder two\"}"));
            assertTrue(letIn.contains("https://www." + LISTED + "/a"), letIn);
            assertFalse(letIn.contains(REFUSED), letIn);
        } finally {
            searxEndpoint(null);
            s.stop(0);
        }
    }

    @Test
    void scholarlyAndRecordResultsAreFilteredTheSameWay() {
        List<ScholarSearch.Row> rows = List.of(new ScholarSearch.Row("Listed placeholder", "https://" + LISTED + "/w", "", ""),
                new ScholarSearch.Row("Ordinary placeholder", "https://doi.org/10.9999/placeholder", "Placeholder Journal, 2001", "10.9999/placeholder"));
        String shown = ScholarSearch.render("placeholder", "", rows, 8);
        assertTrue(shown.contains("doi.org/10.9999/placeholder") && !shown.contains(LISTED), shown);
        assertTrue(shown.contains("(1 result(s) left out: on the list of pornography, shock and gore sites)"), shown);
        assertTrue(ScholarSearch.render("placeholder", "", rows, 8, Fetch.Policy.SITE_LIST_OFF).contains(LISTED));

        RecordSource src = RecordSources.all().get(0);
        List<RecordSources.Hit> hits = List.of(new RecordSources.Hit("Listed record", "1901", "https://" + LISTED + "/r", "", ""),
                new RecordSources.Hit("Refused record", "1902", "https://" + REFUSED + "/r", "", ""),
                new RecordSources.Hit("Ordinary record", "1903", "https://example.org/r", "", ""));
        String records = RecordSources.render(src, "placeholder name", hits);
        assertTrue(records.contains("Ordinary record") && !records.contains("Listed record") && !records.contains("Refused record"), records);
        assertTrue(records.contains("1 record(s) for: placeholder name"), "the count is of what is shown: " + records);
        assertTrue(records.contains("(1 record(s) left out: on the person's refused-sources list) (1 record(s) left out: on the list of pornography, shock and gore sites)"), records);
        String none = RecordSources.render(src, "placeholder name", hits.subList(0, 1));
        assertTrue(none.startsWith("Every record ") && none.contains("was left out") && !none.contains("That is a result"), "not said to be a search that found nothing: " + none);
    }

    @Test
    void everyRedirectHopIsCheckedAndWhatIsLeftOutIsNeverRequested() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/start", ex -> { requests.incrementAndGet(); ex.getResponseHeaders().add("Location", "https://www." + LISTED + "/page"); ex.sendResponseHeaders(302, -1); ex.close(); });
        s.start();
        setLoopback(true);
        try {
            String start = "http://127.0.0.1:" + s.getAddress().getPort() + "/start";
            Fetch.LeftOut left = assertThrows(Fetch.LeftOut.class, () -> Fetch.get(start, Duration.ofSeconds(5)));
            assertEquals("https://www." + LISTED + "/page", left.url(), "the hop the redirect named");
            assertEquals(1, requests.get(), "the first hop was fetched; the listed one never was");
            assertThrows(Fetch.LeftOut.class, () -> Fetch.get("https://" + REFUSED + "/page", Duration.ofSeconds(5)), "the person's refused list applies to a fetch too, not only to search results");
            // web_fetch says it plainly, fetches nothing and saves nothing
            String out = new WebFetchTool().execute(M.readTree("{\"url\":\"https://www." + LISTED + "/page\"}"));
            assertTrue(out.startsWith("ERROR: https://www." + LISTED + "/page was left out of this research, because its site is on the list of pornography, shock and gore sites."), out);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void anAddressThePersonGivesIsFetchedUnderTheirOwnPolicy() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/page", ex -> { byte[] b = "<html><head><title>Placeholder page</title></head><body><p>Placeholder text about gears, long enough to be a page.</p></body></html>".getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        s.start();
        setLoopback(true);
        try {
            SourceRules.set(store, "refuse", "127.0.0.1", "placeholder: the person refused this host as a source for research");
            String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/page";
            assertThrows(Fetch.LeftOut.class, () -> Fetch.get(url, Duration.ofSeconds(5)), "an address a model or a page names: the refused list applies");
            Object[] got = Corpus.addUrl(store, url, "");
            assertNotNull(got[0], "the person added it themselves: it is kept");
            assertTrue(Files.readString((Path) got[0]).contains("Placeholder text about gears"));
        } finally {
            s.stop(0);
        }
    }
}
