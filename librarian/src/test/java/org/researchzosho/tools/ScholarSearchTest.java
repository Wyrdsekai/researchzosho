package org.researchzosho.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.LibrarianCli;
import org.researchzosho.librarian.LibraryStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
/** Crossref and OpenAlex answers become rows with a DOI URL, a venue/year/author line, and merge one row per work. */
class ScholarSearchTest {

    static final String CROSSREF = """
        {"status":"ok","message":{"items":[
          {"DOI":"10.1177/000992287801700602","URL":"https://doi.org/10.1177/000992287801700602","title":["Ban on Lead-Containing Paint"],
           "container-title":["Clinical Pediatrics"],"issued":{"date-parts":[[1978,6]]},"type":"journal-article","author":[]},
          {"DOI":"10.1016/s0140-6736(04)16017-0","URL":"https://doi.org/10.1016/s0140-6736(04)16017-0","title":["MMR—responding to retraction"],
           "container-title":["The Lancet"],"issued":{"date-parts":[[2004]]},"type":"journal-article",
           "author":[{"given":"Richard","family":"Horton"},{"family":"Smith"},{"family":"Jones"},{"family":"Fourth"}]}
        ]}}
        """;
    static final String OPENALEX = """
        {"results":[
          {"display_name":"MMR—responding to retraction","doi":"https://doi.org/10.1016/S0140-6736(04)16017-0",
           "publication_year":2004,"primary_location":{"landing_page_url":"https://doi.org/10.1016/s0140-6736(04)16017-0","source":{"display_name":"The Lancet"}},
           "authorships":[{"author":{"display_name":"Richard Horton"}}]},
          {"display_name":"Melatonin decreases delirium in elderly patients: A randomized, placebo-controlled trial","doi":"https://doi.org/10.1002/gps.2582",
           "publication_year":2010,"primary_location":{"landing_page_url":"https://doi.org/10.1002/gps.2582","source":{"display_name":"International Journal of Geriatric Psychiatry"}},
           "authorships":[{"author":{"display_name":"Tareef Alaama"}},{"author":{"display_name":"Christopher D. Brymer"}}]}
        ]}
        """;

    @Test
    void crossrefRowsCarryTheDoiVenueYearAndAuthors() {
        List<ScholarSearch.Row> rows = ScholarSearch.parseCrossref(CROSSREF);
        assertEquals(2, rows.size());
        assertEquals("Ban on Lead-Containing Paint", rows.get(0).title());
        assertEquals("https://doi.org/10.1177/000992287801700602", rows.get(0).url());
        assertEquals("Clinical Pediatrics, 1978 (journal article)", rows.get(0).snippet());
        assertEquals("The Lancet, 2004: Horton, Smith, Jones et al. (journal article)", rows.get(1).snippet(), "three authors, then et al.");
    }

    @Test
    void openAlexRowsCarryTheLandingPageAndSource() {
        List<ScholarSearch.Row> rows = ScholarSearch.parseOpenAlex(OPENALEX);
        assertEquals(2, rows.size());
        assertEquals("10.1002/gps.2582", rows.get(1).doi(), "the DOI without the resolver prefix");
        assertEquals("International Journal of Geriatric Psychiatry, 2010: Tareef Alaama, Christopher D. Brymer", rows.get(1).snippet());
    }

    @Test
    void theSameWorkFromBothSourcesIsOneRow() {
        var a = ScholarSearch.parseCrossref(CROSSREF).get(1);
        var b = ScholarSearch.parseOpenAlex(OPENALEX).get(0);
        assertEquals(ScholarSearch.key(a), ScholarSearch.key(b), "DOIs match regardless of case: " + ScholarSearch.key(a) + " / " + ScholarSearch.key(b));
    }

    @Test
    void renderedRowsCarryTheSourceTier() {
        String out = ScholarSearch.render("mmr", " (test)", ScholarSearch.parseCrossref(CROSSREF), 8);
        assertTrue(out.contains("1. Ban on Lead-Containing Paint") && out.contains("https://doi.org/10.1177/000992287801700602  ["), out);
        assertTrue(out.contains("SEARCH RESULTS"), "fenced like every search result");
    }

    @Test
    void brokenAnswersAreEmpty() {
        assertTrue(ScholarSearch.parseCrossref("<html>").isEmpty());
        assertTrue(ScholarSearch.parseOpenAlex("{}").isEmpty());
    }

    @Test
    void neitherSourceAnsweringIsNotASearchThatFoundNothing() throws Exception {
        var args = new ObjectMapper().createObjectNode().put("query", "Endo Genzaburo silk");
        try {
            ScholarSearch.reader = url -> { throw new IllegalStateException("connection refused"); };
            String down = new ScholarSearchTool().execute(args);
            assertTrue(down.startsWith("ERROR: neither Crossref nor OpenAlex answered"), down);
            ScholarSearch.reader = url -> url.contains("crossref") ? "{\"message\":{\"items\":[]}}" : "{\"results\":[]}";
            String empty = new ScholarSearchTool().execute(args);
            assertTrue(empty.startsWith("no works found for: Endo Genzaburo silk (Crossref and OpenAlex both answered with nothing)"), empty);
            ScholarSearch.reader = url -> { if (url.contains("crossref")) throw new IllegalStateException("timeout"); return "{\"results\":[]}"; };
            assertTrue(new ScholarSearchTool().execute(args).contains("the other did not answer"));
        } finally { ScholarSearch.reader = null; }
    }

    @Test
    void theCommandAndTheToolNameTheOneSourceThatAnsweredWhenTheOtherDidNot(@TempDir Path home) throws Exception {
        new LibraryStore(home.resolve("researchzosho-library")).init();
        String real = System.getProperty("user.home");
        PrintStream out = System.out;
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        System.setProperty("user.home", home.toString());   // the command opens ~/researchzosho-library
        System.setOut(new PrintStream(said, true, StandardCharsets.UTF_8));
        int rc;
        String tool;
        try {
            ScholarSearch.reader = url -> { if (url.contains("crossref")) throw new IllegalStateException("timeout"); return OPENALEX; };
            rc = LibrarianCli.run(new String[]{"researchzosho", "search", "papers", "mmr", "retraction"}, "http://127.0.0.1:1", "m");
            tool = new ScholarSearchTool().execute(new ObjectMapper().createObjectNode().put("query", "mmr retraction"));
        } finally { ScholarSearch.reader = null; System.setOut(out); System.setProperty("user.home", real); }
        String o = said.toString(StandardCharsets.UTF_8);
        assertEquals(0, rc);
        assertFalse(o.contains("Crossref and OpenAlex"), "nothing says both answered: " + o);
        assertTrue(o.startsWith("The search took ") && o.contains("Only OpenAlex answered; Crossref did not, so the list may be short.") && o.contains("(scholarly literature: OpenAlex)"), o);
        assertTrue(tool.contains("(scholarly literature: OpenAlex)") && !tool.contains("Crossref and OpenAlex"), tool);
    }

    @Test
    void theCommandTellsAPersonWhenNeitherSourceAnsweredAndFails(@TempDir Path home) throws Exception {
        new LibraryStore(home.resolve("researchzosho-library")).init();
        String real = System.getProperty("user.home");
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream said = new ByteArrayOutputStream(), errors = new ByteArrayOutputStream();
        System.setProperty("user.home", home.toString());   // the command opens ~/researchzosho-library
        System.setOut(new PrintStream(said, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
        int down, empty;
        try {
            ScholarSearch.reader = url -> { throw new IllegalStateException("connection refused"); };
            down = LibrarianCli.run(new String[]{"researchzosho", "search", "papers", "Endo", "Genzaburo", "silk"}, "http://127.0.0.1:1", "m");
            ScholarSearch.reader = url -> url.contains("crossref") ? "{\"message\":{\"items\":[]}}" : "{\"results\":[]}";
            empty = LibrarianCli.run(new String[]{"researchzosho", "search", "papers", "Endo", "Genzaburo", "silk"}, "http://127.0.0.1:1", "m");
        } finally { ScholarSearch.reader = null; System.setOut(out); System.setErr(err); System.setProperty("user.home", real); }
        String e = errors.toString(StandardCharsets.UTF_8), o = said.toString(StandardCharsets.UTF_8);
        assertEquals(1, down, "a search that could not run fails");
        assertTrue(e.contains("Neither Crossref nor OpenAlex answered, so nothing was searched.") && !e.contains("ERROR") && !e.contains("web_search"), e);
        assertEquals(0, empty, "a search that found nothing did run");
        assertTrue(o.contains("No papers or books were found for \"Endo Genzaburo silk\"."), o);
    }
}
