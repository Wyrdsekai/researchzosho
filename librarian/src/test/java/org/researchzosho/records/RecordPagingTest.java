package org.researchzosho.records;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.researchzosho.tools.RecordSearchTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A collection that pages gives its whole page and the pages after it, so that a class, a name or a span of years is taken in whole and never
 * as an engine's first page; the first page's address is what it always was, and an empty later page ends the search without a line in the
 * ledger of what was searched for and not found.
 */
class RecordPagingTest {
    static final ObjectMapper J = new ObjectMapper();
    static RecordSource builtIn(String id) { return RecordSources.all().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError("no built-in source " + id)); }
    static RecordSource source(String json) throws Exception { return RecordSources.parse(J.readTree(json), false); }
    static final String PAGED = """
            {"id": "paged", "name": "Paged Papers", "holds": "pages of papers", "kind": "newspaper", "max": 50,
             "url": "https://papers.example/api?q={query}&n={limit}[&page={page}]", "items": "/results",
             "title": "{/paper}", "date": "{/date}", "link": "{/id}", "snippet": "", "where": "{/paper}, Paged Papers"}""";
    static final String ONE_PAGE = PAGED.replace("\"paged\"", "\"one\"").replace("Paged Papers", "One Page").replace("[&page={page}]", "").replace("\"max\": 50,", "");

    @Test
    void thePageSlotsAreEmptyOnTheFirstPageAndCountFromThere() throws Exception {
        RecordSource s = source(PAGED);
        assertEquals("https://papers.example/api?q=hale&n=8", RecordSources.address(s, "hale", 0, 0, 8), "the first page's address is what it always was");
        assertEquals("https://papers.example/api?q=hale&n=8", RecordSources.address(s, "hale", 0, 0, 8, 1));
        assertEquals("https://papers.example/api?q=hale&n=8&page=2", RecordSources.address(s, "hale", 0, 0, 8, 2));
        assertEquals("https://papers.example/api?q=hale&n=8&page=3", RecordSources.address(s, "hale", 0, 0, 8, 3));
        RecordSource zero = source(PAGED.replace("{page}", "{page0}"));
        assertEquals("https://papers.example/api?q=hale&n=8", RecordSources.address(zero, "hale", 0, 0, 8, 1), "counted from 0, the first page still names none");
        assertEquals("https://papers.example/api?q=hale&n=8&page=1", RecordSources.address(zero, "hale", 0, 0, 8, 2));
        RecordSource offset = source(PAGED.replace("[&page={page}]", "[&offset={offset0}]"));
        assertEquals("https://papers.example/api?q=hale&n=8", RecordSources.address(offset, "hale", 0, 0, 8, 1));
        assertEquals("https://papers.example/api?q=hale&n=8&offset=16", RecordSources.address(offset, "hale", 0, 0, 8, 3), "the records before the page");
        RecordSource range = source(PAGED.replace("&n={limit}[&page={page}]", "&range={first}-{last}"));
        assertEquals("https://papers.example/api?q=hale&range=1-8", RecordSources.address(range, "hale", 0, 0, 8, 1));
        assertEquals("https://papers.example/api?q=hale&range=9-16", RecordSources.address(range, "hale", 0, 0, 8, 2));
        assertTrue(s.pages() && zero.pages() && offset.pages() && range.pages() && !source(ONE_PAGE).pages());
        assertEquals(50, s.pageSize()); assertEquals(20, source(ONE_PAGE).pageSize(), "twenty when the collection says nothing");
    }

    @Test
    void theBuiltInCollectionsPageAsTheirOfficesDo() {
        // the EPO: a page is a range of its records, up to a hundred
        assertTrue(RecordSources.address(builtIn("epo-inventor"), "\"Robert Hale\"", 1900, 1930, 5).endsWith("&Range=1-5"), "the first page as it always was");
        assertTrue(RecordSources.address(builtIn("epo-inventor"), "\"Robert Hale\"", 1900, 1930, 5, 2).endsWith("&Range=6-10"));
        assertTrue(RecordSources.address(builtIn("epo-query"), "cpc=A01K5/01 and pd within \"2012 2022\"", 0, 0, 100, 3).endsWith("&Range=201-300"));
        assertEquals(100, builtIn("epo-query").pageSize()); assertEquals(100, builtIn("epo-applicant").pageSize());
        assertTrue(builtIn("epo-query").holds().contains("cpc=A01K5/01 and pd within \"2012 2022\""), "the query that enumerates a classification in its years is a literal the model copies: " + builtIn("epo-query").holds());
        assertFalse(builtIn("epo-query").holds().contains("pd>=1990 and pd<=2005"), "the office refuses pd>= with pd<= together (413 CLIENT.FuzzyDateRanges): no example writes it so");
        assertTrue(RecordSources.address(builtIn("epo-inventor"), "\"Robert Hale\"", 1900, 1930, 5).contains("pd%20within%20%221900%201930%22"), "a span of years is written pd within");
        assertFalse(RecordSources.address(builtIn("epo-inventor"), "\"Robert Hale\"", 1900, 0, 5).contains("pd"), "half a span is no span");
        // Google Patents counts its pages from 0; its page of more than twenty is unverified, since it refused this box on the third request
        assertEquals("https://patents.google.com/xhr/query?url=q%3Dsolid+state+battery%26num%3D5&exp=", RecordSources.address(builtIn("google-patents"), "solid state battery", 0, 0, 5), "the first page as it always was");
        assertEquals("https://patents.google.com/xhr/query?url=q%3Dsolid+state+battery%26num%3D5%26page%3D1&exp=", RecordSources.address(builtIn("google-patents"), "solid state battery", 0, 0, 5, 2));
        assertEquals(20, builtIn("google-patents").pageSize());
        // SerpApi: a page of a hundred, each page one of the month's searches, and the run's eight at most
        assertTrue(RecordSources.address(builtIn("serpapi-patents"), "folding bowl", 2012, 2022, 100, 2).contains("&num=100&page=2&"), RecordSources.address(builtIn("serpapi-patents"), "folding bowl", 2012, 2022, 100, 2));
        assertEquals(8, builtIn("serpapi-patents").perRun());
        // the law collections
        assertEquals("https://www.federalregister.gov/api/v1/documents.json?conditions%5Bterm%5D=forensic+audit&per_page=5&conditions%5Bpublication_date%5D%5Bgte%5D=2020-01-01",
                RecordSources.address(builtIn("federal-register"), "forensic audit", 2020, 0, 5), "the first page as it always was");
        assertTrue(RecordSources.address(builtIn("federal-register"), "forensic audit", 2020, 0, 5, 2).contains("&per_page=5&page=2&conditions"));
        assertEquals("https://www.ecfr.gov/api/search/v1/results?query=forensic+audit&per_page=5&page=2", RecordSources.address(builtIn("ecfr"), "forensic audit", 0, 0, 5, 2));
        assertEquals("https://laws.e-gov.go.jp/api/2/laws?law_title=%E7%89%B9%E8%A8%B1&limit=20&offset=40", RecordSources.address(builtIn("egov-laws"), "\"特許\"", 0, 0, 20, 3));
        assertEquals("https://laws.e-gov.go.jp/api/2/laws?law_title=%E7%89%B9%E8%A8%B1&limit=5", RecordSources.address(builtIn("egov-laws"), "\"特許\"", 0, 0, 5), "the first page as it always was");
        assertFalse(builtIn("courtlistener").pages(), "CourtListener's fourth interface pages by a cursor, which this build does not follow");
        for (String id : List.of("epo-query", "epo-applicant", "epo-inventor", "google-patents", "serpapi-patents", "serpapi-inventor", "federal-register", "ecfr", "egov-laws", "egov-keyword")) assertTrue(builtIn(id).pages(), id + " pages");
    }

    /** A reader that answers from a table of addresses and keeps what it was asked. */
    static class Pages implements RecordSources.Reader {
        final List<String> asked = new ArrayList<>();
        final Map<String, String> answers;
        Pages(Map<String, String> answers) { this.answers = answers; }
        @Override public String read(String url) { asked.add(url); return answers.getOrDefault(url, "{\"results\": []}"); }
    }

    static ObjectNode call(String source, String query, int page, int limit) {
        ObjectNode a = J.createObjectNode(); a.put("source", source); a.put("query", query);
        if (page > 0) a.put("page", page); if (limit > 0) a.put("limit", limit);
        return a;
    }

    @Test
    void theToolTakesAPageCutsTheLimitToThePageAndEndsOnAnEmptyLaterPage() throws Exception {
        String one = "{\"results\": [{\"paper\": \"The Leeds Mercury\", \"date\": \"1901-03-02\", \"id\": \"https://papers.example/a\"}, {\"paper\": \"The Leeds Mercury\", \"date\": \"1901-03-09\", \"id\": \"https://papers.example/b\"}]}";
        Pages reader = new Pages(Map.of("https://papers.example/api?q=hale&n=2", one, "https://papers.example/api?q=hale&n=2&page=2", one.replace("/a", "/c").replace("/b", "/d"), "https://papers.example/api?q=hale&n=50&page=2", "{\"results\": []}"));
        List<RecordSearchTool.Searched> logged = new ArrayList<>();
        RecordSearchTool tool = new RecordSearchTool(List.of(source(PAGED), source(ONE_PAGE)), reader).listen(logged::add);
        assertTrue(tool.parametersSchema(J).path("properties").has("page"), "the tool takes a page");
        assertTrue(tool.description().contains("A collection that pages gives its whole page for limit and the next page for page=2") && tool.description().contains("Paged Papers. pages of papers. Pages, up to 50 a page."), tool.description());
        String first = tool.execute(call("paged", "hale", 0, 2));
        assertTrue(first.contains("https://papers.example/a") && !first.startsWith("Page 1") && !first.startsWith("Page 2"), first);
        String second = tool.execute(call("paged", "hale", 2, 2));
        assertTrue(second.startsWith("Page 2 of the search.") && second.contains("https://papers.example/c"), second);
        assertEquals(List.of("https://papers.example/api?q=hale&n=2", "https://papers.example/api?q=hale&n=2&page=2"), reader.asked);
        assertEquals(2, logged.size()); assertEquals("hale (page 2)", logged.get(1).query(), "the ledger says which page was searched");
        // a limit above the page is cut to the page; an empty later page ends the search and writes nothing down as not found
        String empty = tool.execute(call("paged", "hale", 2, 500));
        assertEquals("https://papers.example/api?q=hale&n=50&page=2", reader.asked.get(2), "the limit is the page's");
        assertTrue(empty.startsWith("Page 2 of Paged Papers for hale is empty"), empty);
        assertEquals(2, logged.size(), "an empty later page is not a search that found nothing");
        // a collection that does not page has no page 2, and nothing is sent for it
        String none = tool.execute(call("one", "hale", 2, 2));
        assertTrue(none.startsWith("One Page gives one page only: there is no page 2 of it."), none);
        assertEquals(3, reader.asked.size(), "nothing was asked of it");
        assertEquals("https://papers.example/api?q=hale&n=20", RecordSources.address(source(ONE_PAGE), "hale", 0, 0, 20), "a collection that says nothing of its page still gives twenty at most");
    }
}
