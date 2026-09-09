package org.researchzosho.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The built-in fallback reads Wikipedia's search JSON: title, the page's URL, the snippet without markup. */
class FallbackSearchTest {

    static final String PAGE = """
        {"batchcomplete":"","query":{"searchinfo":{"totalhits":1235},"search":[
          {"ns":0,"title":"Lead paint","pageid":1,"snippet":"<span class=\\"searchmatch\\">Lead</span> <span class=\\"searchmatch\\">paint</span> or lead-based paint is paint containing lead."},
          {"ns":0,"title":"Lead-based paint in the United States","pageid":2,"snippet":"artists&#039; paints &amp; more in the <span class=\\"searchmatch\\">United</span> <span class=\\"searchmatch\\">States</span>"}
        ]}}
        """;

    @Test
    void rowsComeOutAsTitleUrlSnippet() {
        List<String[]> rows = WebSearchTool.parseFallback(PAGE, "en");
        assertEquals(2, rows.size());
        assertEquals("Lead paint", rows.get(0)[0]);
        assertEquals("https://en.wikipedia.org/wiki/Lead_paint", rows.get(0)[1]);
        assertEquals("Lead paint or lead-based paint is paint containing lead.", rows.get(0)[2], "the search-match spans are stripped");
        assertEquals("https://en.wikipedia.org/wiki/Lead-based_paint_in_the_United_States", rows.get(1)[1]);
        assertEquals("artists' paints & more in the United States", rows.get(1)[2], "numeric and named entities are decoded");
    }

    @Test
    void aJapaneseQueryGoesToTheJapaneseWikipedia() {
        assertEquals("https://ja.wikipedia.org/wiki/%E5%A4%A7%E9%98%AA%E5%B8%82", WebSearchTool.parseFallback("{\"query\":{\"search\":[{\"title\":\"大阪市\",\"snippet\":\"\"}]}}", "ja").get(0)[1]);
    }

    @Test
    void anEmptyOrBrokenAnswerIsNoRows() {
        assertTrue(WebSearchTool.parseFallback("{\"query\":{\"search\":[]}}", "en").isEmpty());
        assertTrue(WebSearchTool.parseFallback("<html>not json</html>", "en").isEmpty());
    }

    @Test
    void theFallbackIsOnUnlessTurnedOff() {
        assertTrue(WebSearchTool.fallbackOn(), "on by default");
    }
}
