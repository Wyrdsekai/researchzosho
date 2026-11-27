package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Found;
import org.researchzosho.librarian.CareerSources.Hit;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.WebSearchTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a career search spends on looking employers up (0.8.4): the same words asked once in a search; nothing asked that the library
 * knows or a free source gives; what is learnt kept for the library with its day, used again while it serves, aged out, looked up
 * again when a kept board does not answer, and corrected by the person; at most a set number of paid look-ups, and a look-up that was
 * not made never kept as one that found nothing.
 */
class CareerLookupsTest {
    static final ObjectMapper M = new ObjectMapper();
    final Map<String, String> pages = new HashMap<>();
    final Map<String, List<Hit>> searches = new HashMap<>();
    final List<String> asked = new ArrayList<>();
    @TempDir Path tmp;
    LibraryStore store;
    CareerLookups.Run run;

    @BeforeEach void standIns() throws Exception {
        CareerSources.reset(); CareerSources.POLITE_MS = 0;
        CareerSources.RAW = pages::get;
        CareerSources.SEARCH = q -> { asked.add(q); return searches.getOrDefault(q, List.of()); };
        SearchTally.override = tmp.resolve("tally.json");
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        long day = LocalDate.now().minusDays(2).toEpochDay() * 86_400_000L;
        pages.put("https://api.lever.co/v0/postings/acme?mode=json", """
                [{"text": "Engineer", "categories": {"location": "Boston, MA"}, "createdAt": %d, "hostedUrl": "https://jobs.lever.co/acme/1", "descriptionPlain": "Acme builds things."}]""".formatted(day));
        searches.put("Acme careers jobs", List.of(new Hit("Acme - Jobs", "https://jobs.lever.co/acme"), new Hit("Careers at Acme", "https://acme.example/careers")));
        pages.put("https://acme.example/careers", "<html>Work with us. <a href=\"https://jobs.lever.co/acme\">Open roles</a></html>");
        pages.put("https://acme.example/about", "<html><p>We are a team of 40 people in Boston.</p></html>");
        searches.put("Nobody Inc careers jobs", List.of(new Hit("Nobody Inc in the news", "https://news.example/nobody")));   // an answer, with nothing readable in it
    }

    @AfterEach void back() {
        CareerLookups.end(run);
        CareerSources.RAW = CareerSources::fetch; CareerSources.SEARCH = CareerSources::search; CareerSources.POLITE_MS = 1000; CareerSources.reset();
        SearchTally.override = null;
    }

    CareerLookups.Run search() { CareerLookups.end(run); run = CareerLookups.begin(store); return run; }

    @Test void theSameWordsAreAskedOnceAndFindSiteAsksTheNameAloneOnlyWhenItsCareersSearchConfirmedNothing() {
        search();
        Found f = CareerSources.openingsByName("Acme", List.of());
        assertEquals("lever:acme", f.feed().label());
        assertEquals("acme.example", CareerSize.findSite("Acme", new Feed("lever", "acme", "found in a search for its careers page")), "its careers page links the same board");
        assertEquals(List.of("Acme careers jobs"), asked, "the careers page's search was asked once, for both, and the name alone not at all");

        searches.put("Initech careers jobs", List.of(new Hit("Initech is hiring", "https://news.example/initech")));
        searches.put("Initech", List.of(new Hit("Initech", "https://initech.example/")));
        pages.put("https://initech.example/", "<html><a href=\"https://boards.greenhouse.io/initech\">Jobs</a></html>");
        assertEquals("initech.example", CareerSize.findSite("Initech", new Feed("greenhouse", "initech", "found by its name")));
        assertEquals(List.of("Acme careers jobs", "Initech careers jobs", "Initech"), asked, "the name alone, when the first search confirmed no site");
    }

    @Test void wikidataComesBeforeTheEmployeesSearch() {
        search();
        pages.put("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&type=item&limit=5&search=Marble", "{\"search\": [{\"id\": \"Q2\", \"label\": \"Marble\"}]}");
        pages.put("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&entity=Q2&property=P856", "{\"claims\": {\"P856\": [{\"mainsnak\": {\"datavalue\": {\"value\": \"https://www.marble.example/\"}}}]}}");
        pages.put("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&entity=Q2&property=P1128", "{\"claims\": {\"P1128\": [{\"mainsnak\": {\"datavalue\": {\"value\": {\"amount\": \"+48\"}}}}]}}");
        CareerSize.People p = CareerSize.lookup("Marble", "marble.example");
        assertEquals(48, p.low()); assertEquals("Wikidata", p.source());
        assertEquals(List.of(), asked, "Wikidata gave the size: no search for its employees");
    }

    @Test void whatOneSearchLearntIsKeptForTheLibraryAndTheNextSearchAsksNothingOfIt() throws Exception {
        search();
        ObjectNode acme = M.createObjectNode().put("name", "Acme");
        Found f = CareerSources.openingsByName("Acme", List.of());
        CareerFind.sizeOf(store, acme, f, LocalDate.now().toString());
        Found none = CareerSources.openingsByName("Nobody Inc", List.of());
        assertNull(none.feed());
        assertEquals("acme.example", acme.path("site").asText()); assertEquals(40, acme.path("people").path("low").asInt());
        JsonNode kept = M.readTree(CareerLookups.file(store).toFile());
        assertEquals("lever", kept.path("acme").path("board").path("kind").asText());
        assertEquals(LocalDate.now().toString(), kept.path("acme").path("board").path("at").asText());
        assertEquals("acme.example", kept.path("acme").path("site").asText());
        assertEquals(40, kept.path("acme").path("people").path("low").asInt());
        assertEquals(LocalDate.now().toString(), kept.path("nobodyinc").path("board_none").asText(), "that a search found nothing readable is kept too: " + kept);
        assertEquals(List.of("Acme careers jobs", "Nobody Inc careers jobs"), asked);

        asked.clear();
        search();   // the next search, another person's list: the same employers
        ObjectNode again = M.createObjectNode().put("name", "Acme");
        Found g = CareerSources.openingsByName("Acme", List.of());
        CareerFind.sizeOf(store, again, g, LocalDate.now().toString());
        Found stillNone = CareerSources.openingsByName("Nobody Inc", List.of());
        assertEquals(List.of(), asked, "nothing asked again");
        assertEquals("lever:acme", g.feed().label()); assertEquals(1, g.openings().size(), "the kept board read straight");
        assertEquals("acme.example", again.path("site").asText()); assertEquals(40, again.path("people").path("low").asInt());
        assertEquals("a search for its careers page on " + LocalDate.now() + " found nothing the library can read, so it is searched for again after a month", stillNone.note());
        assertEquals(4, run.kept(), "the board, the site, the size and the nothing, each from what was kept");
    }

    @Test void aKeptBoardThatNoLongerAnswersIsLookedUpAgainAndAFactPastItsTimeIsLookedUpAgain() throws Exception {
        search();
        CareerSources.openingsByName("Acme", List.of());
        CareerSources.openingsByName("Nobody Inc", List.of());
        asked.clear();

        search();
        pages.remove("https://api.lever.co/v0/postings/acme?mode=json");   // the employer left Lever
        searches.put("Acme careers jobs", List.of(new Hit("Acme - Jobs", "https://boards.greenhouse.io/acme")));
        pages.put("https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true", "{\"jobs\": [{\"title\": \"Engineer\", \"location\": {\"name\": \"Boston\"}, \"absolute_url\": \"https://boards.greenhouse.io/acme/jobs/1\", \"content\": \"\"}]}");
        Found f = CareerSources.openingsByName("Acme", List.of());
        assertEquals("greenhouse:acme", f.feed().label(), "looked up again at once");
        assertEquals(List.of("Acme careers jobs"), asked);
        assertEquals("greenhouse", M.readTree(CareerLookups.file(store).toFile()).path("acme").path("board").path("kind").asText(), "and the new board kept");

        ObjectNode all = (ObjectNode) M.readTree(CareerLookups.file(store).toFile());   // a month and a day later
        ((ObjectNode) all.get("nobodyinc")).put("board_none", LocalDate.now().minusDays(CareerLookups.BOARD_DAYS + 1).toString());
        Files.writeString(CareerLookups.file(store), M.writeValueAsString(all));
        search();
        asked.clear();
        CareerSources.openingsByName("Nobody Inc", List.of());
        assertEquals(List.of("Nobody Inc careers jobs"), asked, "searched for again once its time is out");
    }

    @Test void twoSpellingsOfOneEmployerMeetThroughItsBoard() {
        search();
        Feed board = new Feed("workday", "kcura/wd1/External", "found in a search of the hiring sites");
        CareerLookups.boardSeen("Kcura", board);   // learnt under the Workday site's short name
        CareerLookups.siteFound("Kcura", "relativity.example");
        CareerLookups.peopleFound("Kcura", new CareerSize.People(1000, 5000, "its LinkedIn page", LocalDate.now().toString()));
        ObjectNode named = M.createObjectNode().put("name", "Relativity");   // the hiring organization its postings name
        CareerFind.sizeOf(store, named, new Found("Relativity", board, List.of(), ""), LocalDate.now().toString());
        assertEquals("relativity.example", named.path("site").asText()); assertEquals(1000, named.path("people").path("low").asInt());
        assertEquals(List.of(), asked, "what was kept under the other spelling answered");
        CareerLookups.boardSeen("Lever Implementation Training Environment", new Feed("lever", "leverdemo-8", ""));
        assertNull(CareerLookups.of(store, "Lever Implementation Training Environment"), "a hiring system's own demonstration board is kept as nobody's board");
    }

    @Test void thePersonSeesWhatIsKeptAndCorrectsIt() throws Exception {
        LibraryProtocol lp = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "tester").put("runtime", "test");
        String set = lp.career(a.deepCopy().put("op", "board").put("employer", "Acme").put("address", "https://jobs.lever.co/acme")).path("summary").asText();
        assertTrue(set.startsWith("The library reads Acme's openings from Lever (https://jobs.lever.co/acme) in every search from now on.\nAcme's openings come from the address you gave (lever): 1 opening."), set);
        assertTrue(set.contains("The library keeps of Acme: its board, Lever (lever:acme), as you set it. A search reads it from there; correct it with career board Acme and the board's address, --site, --size, or --forget."), set);
        assertEquals(List.of(), asked, "the person's board is read, not searched for");

        String size = lp.career(a.deepCopy().put("op", "board").put("employer", "Acme").put("people", 300)).path("summary").asText();
        assertTrue(size.startsWith("The library takes Acme as about 300 people.") && size.contains("about 300 people, as you set it"), size);
        search();
        ObjectNode acme = M.createObjectNode().put("name", "Acme").put("site", "acme.example").putObject("people").put("low", 40).put("high", 40).put("source", "its own site, acme.example").put("at", LocalDate.now().toString());
        ObjectNode c = M.createObjectNode().put("name", "Acme").put("site", "acme.example"); c.set("people", acme.path("people"));
        CareerFind.sizeOf(store, c, new Found("Acme", new Feed("lever", "acme", ""), List.of(), ""), LocalDate.now().toString());
        assertEquals(300, c.path("people").path("low").asInt(), "the person's word comes before every other size");
        pages.remove("https://api.lever.co/v0/postings/acme?mode=json");
        CareerSources.openingsByName("Acme", List.of());   // the person's board does not answer and the search finds nothing new: it stays as they set it
        assertTrue(CareerLookups.said(store, "Acme").contains("as you set it"), CareerLookups.said(store, "Acme"));

        assertEquals("Nothing the library can read is at https://acme.example/nothing: no hiring system's board, and no job list on the page; nothing was changed.",
                lp.career(a.deepCopy().put("op", "board").put("employer", "Acme").put("address", "https://acme.example/nothing")).path("summary").asText().split("\n")[0]);
        assertEquals("The library forgot what it kept of Acme; the next search looks it up again.", lp.career(a.deepCopy().put("op", "board").put("employer", "Acme").put("forget", true)).path("summary").asText());
        assertEquals("", CareerLookups.said(store, "Acme"));
    }

    @Test void oneSearchPaysForAtMostTwentyLookUpsAndALookUpNoEngineAnsweredIsNotKeptAsFoundNothing() {
        search();
        CareerSources.openingsByName("Down Co", List.of());   // no engine answered at all (down, or the search was stopped)
        assertNull(CareerLookups.of(store, "Down Co"), "nothing kept: the next search looks it up");
        assertTrue(CareerLookups.of(store, "Nobody Inc") == null && CareerSources.openingsByName("Nobody Inc", List.of()).feed() == null && CareerLookups.of(store, "Nobody Inc") != null, "an answer with nothing readable in it is kept");

        SearchTally.Work all = SearchTally.begin("this career search", 47);
        CareerLookups.Run r = search();   // inside the search's own count, as CareerFind.run begins them
        try {
            for (int i = 0; i < CareerLookups.PAID_MOST; i++) SearchTally.during(r.paid, () -> { SearchTally.count(WebSearchTool.BRAVE, 0); return null; });   // twenty paid look-ups made
            Found late = CareerSources.openingsByName("Late Co", List.of());   // the free engine gives nothing, and no paid look-up is left
            assertEquals("it was not looked up in this search: its paid look-ups were made and no free search engine answered, so the next search looks it up", late.note());
            assertNull(CareerLookups.of(store, "Late Co"), "nothing kept: the next search looks it up");
            CareerSources.openingsByName("Acme", List.of());
            assertEquals("Web searches this time: 20, 20 of them paid (Brave 20); a career search pays for at most 47. Looking up the employers took 20 searches, 20 paid (at most 20)."
                    + " 1 look-up was not made, since the paid ones were used and no free search engine answered; the next search makes them."
                    + " With a free search engine of the library's own (researchzosho search start), the look-ups cost nothing.", CareerLookups.line(all, r));
        } finally { SearchTally.end(all); }
        assertEquals("lever", CareerLookups.of(store, "Acme").path("board").path("kind").asText(), "a look-up that found its board is kept as ever");
    }
}
