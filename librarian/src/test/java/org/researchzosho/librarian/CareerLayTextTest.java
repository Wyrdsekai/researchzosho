package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the pages say to a person who is not a programmer, in each empty state: why it is empty, and what to do next. */
class CareerLayTextTest {
    static final ObjectMapper M = new ObjectMapper();

    /** A model that writes one reading and drafts nothing: the pages are text, and need no more. */
    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode m, int max) { return "[]"; }
        @Override public String prose(ArrayNode m, int max) { return "## The arc\nYou lead a team [E2][E3].\n## Questions for you\n1. Which size of company suited you best, and why? (preference) [E2][E3]"; }
    }

    @BeforeEach void drive() { Career.DRIVE = StandIn::new; }
    @AfterEach void back() { Career.DRIVE = Explain::drive; }

    @Test void beforeAnySearchThePagesSayWhatASearchDoesAndWhatItNeeds(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        String openings = CareerOpeningsPage.body(store, s, true);
        assertTrue(openings.contains("There has been no search yet. A search lists the employers for you, the ones you named first, then those the public records and the lists for your work and place name, reads their openings from their own hiring pages, keeps the ones that fit what you decided"), openings);
        assertTrue(openings.contains(">Search now</button>"), openings);
        String companies = CareerCompaniesPage.body(store, s, true);
        assertTrue(companies.contains("The library has not gathered the employers of your place yet. It needs a place: decide where you work on"), companies);
        String page = CareerPage.body(store, s, true);
        assertTrue(page.contains("<details class=\"sec\" id=\"employers\"><summary><h2>Employers of your place</h2></summary>") && page.contains("It needs a place first: decide where you work, above."), page);
        assertTrue(page.contains("No search yet. A search lists the employers for you"), page);
        assertTrue(page.contains("<details class=\"sec\" id=\"filled\"><summary><h2>How jobs like yours are filled</h2></summary>") && page.contains("The library has not yet worked out how jobs like yours are filled; the next search does."), page);
        for (String jargon : List.of("the cut", "stand after", "seats", "feed", "yield")) assertFalse(page.toLowerCase().contains(jargon) || openings.toLowerCase().contains(jargon) || companies.toLowerCase().contains(jargon), "no " + jargon + " on the pages before a search");
    }

    @Test void whenEveryEmployersOpeningsCouldNotBeReadThePageSaysWhyAndWhatToDo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        List<ObjectNode> cs = new ArrayList<>();
        for (String name : List.of("Northgate Medical Center", "Harrowgate Surgical Hospital", "Quillon Children's"))
            cs.add(M.createObjectNode().put("name", name).put("by", "library").put("from", "sources").put("state", "").put("first_listed", LocalDate.now().toString()).put("read_at", LocalDate.now().toString())
                    .put("why", "On the US hospital and care lists (Medicare), in Austin, Texas, a hospital.").put("note", "no hiring feed or job list turned up on its careers pages; some companies build their job lists in the browser, which the library does not run"));
        CareerFind.saveCompanies(store, s, cs);
        String page = CareerOpeningsPage.body(store, s, true);
        assertTrue(page.contains("3 employers on the list; the library could read the openings of 0. <b>0 openings fit</b> what you decided, at 0 employers; the library read 0."), page);
        assertTrue(page.contains("The library found employers for you but could read the openings of none of them: their hiring pages are built in the browser or kept by a hiring system the library does not read yet. The list below names each employer; open its own page for its openings"), page);
        assertTrue(page.contains("<h3>Their openings could not be read</h3>") && page.contains("Northgate Medical Center"), page);
        assertTrue(page.contains("<h2>What counts as a fit</h2>") && !page.contains("<h2>The cut</h2>"), page);
    }
}
