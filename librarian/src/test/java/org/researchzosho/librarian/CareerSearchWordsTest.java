package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.CareerCut.Cut;
import org.researchzosho.librarian.CareerSources.Hit;
import org.researchzosho.tools.WebSearchTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The search words of the career search (0.8.4): the search of the hiring systems' own job pages looks for the whole titles of the role, in
 * the person's own words, on every system of the one table, a few seconds apart; a title it looks for passes the cut. Stand-in pages, a
 * stand-in search engine and a stand-in model: no web, no model server.
 */
class CareerSearchWordsTest {

    final Map<String, String> pages = new HashMap<>();
    final Map<String, List<Hit>> searches = new HashMap<>();
    final List<String> asked = new CopyOnWriteArrayList<>();
    final List<Long> at = new CopyOnWriteArrayList<>();
    volatile String cutAnswer = "";

    static final String RECENT = LocalDate.now().minusDays(4).toString();

    /** The model: the reading, an empty draft, and the cut as the test sets it. */
    final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String prose(ArrayNode m, int max) { return CareerInterviewTest.READING; }
        @Override public String classify(ArrayNode m, int max) { return m.get(0).path("content").asText().startsWith("Turn what a person wants") ? cutAnswer : "[]"; }
    }

    @BeforeEach void standIns() {
        CareerSources.reset();
        CareerSources.POLITE_MS = 0;
        CareerSources.RAW = pages::get;
        CareerSources.RAW_PAGE = (url, headers) -> { String b = pages.get(url); return b == null ? null : new CareerSources.Page(url, 200, b); };   // a posting's own page, as the readers of 0.8.4 ask it
        CareerSources.RAW_POST = (u, j) -> null;
        CareerSources.SEARCH = q -> { asked.add(q); at.add(System.currentTimeMillis()); return searches.getOrDefault(q, List.of()); };
        Career.DRIVE = StandIn::new;
    }

    @AfterEach void back() {
        CareerSources.RAW = CareerSources::fetch; CareerSources.RAW_POST = CareerSources::post; CareerSources.SEARCH = CareerSources::search; CareerSources.RAW_PAGE = CareerSources::fetchPage;
        CareerSources.POLITE_MS = 1000; CareerSources.SEARCH_GAP_MS = 3000; CareerSources.reset();
        Career.DRIVE = Explain::drive;
    }

    static final CareerShape.Shape POSTED = new CareerShape.Shape(List.of("posted"), List.of("none"), "", "", "", "", "security engineer", "Columbus, Ohio", "", "the model", "", LocalDate.now().toString());

    Career.Seeker seeker(LibraryStore store, Path tmp, String role) throws Exception {
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        Career.setWant(store, s, "role", "decided", role);
        Career.setWant(store, s, "place", "decided", "Columbus, Ohio, or remote in the US");
        return s;
    }

    @Test void theSearchLooksForTheRolesOwnTitlesOnEverySystemOfTheTableAFewSecondsApart(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        cutAnswer = """
                {"role_titles": ["Engineer", "Architect"], "ownership_titles": [], "titles_not": ["Sales"], "areas": [], "places": ["Columbus", "Ohio", "OH"], "remote": "fine",
                 "remote_where": ["US"], "max_people": 0, "min_people": 0, "search_titles": ["Security Engineer", "Security Architect"], "prefer_titles": [], "prefer_places": []}""";
        Career.Seeker s = seeker(store, tmp, "Security engineer or security architect, hands-on, without a team to run");
        Cut cut = CareerCut.make(store, s);
        assertEquals(List.of("Security Engineer", "Security Architect"), cut.searchTitles(), "the model's titles are kept with the cut");
        assertEquals(List.of("Security Engineer", "Security Architect"), CareerCut.kept(store, s).searchTitles(), "and read back from its file");
        searches.put("\"Security Engineer\" (\"Columbus\" OR \"Ohio\" OR \"Remote\") (site:jobs.ashbyhq.com OR site:greenhouse.io OR site:jobs.lever.co)", List.of(new Hit("Gearbox - Security Engineer", "https://jobs.lever.co/gearbox/5c4b3a29-aa10-4f00-8d3e-000000000003")));
        pages.put("https://api.lever.co/v0/postings/gearbox?mode=json", """
                [{"text": "Security Engineer", "categories": {"location": "Columbus, OH"}, "createdAt": %d, "hostedUrl": "https://jobs.lever.co/gearbox/5c4b3a29-aa10-4f00-8d3e-000000000003", "descriptionPlain": "Gearbox builds gearboxes in Columbus."}]"""
                .formatted(LocalDate.now().minusDays(3).toEpochDay() * 86_400_000L));
        CareerSources.POLITE_MS = 1;   // the pace in force, at a test's scale
        CareerSources.SEARCH_GAP_MS = 30;
        List<ObjectNode> list = CareerFind.companyList(store, s, cut, POSTED, x -> { }, () -> false, null, false);

        List<String> bySite = asked.stream().filter(q -> q.contains("site:")).toList();
        assertEquals("\"Security Engineer\" (\"Columbus\" OR \"Ohio\" OR \"Remote\") (site:jobs.ashbyhq.com OR site:greenhouse.io OR site:jobs.lever.co)", bySite.get(0),
                "the role's own title, whole, with the place, on three systems of the table at once");
        assertTrue(bySite.stream().noneMatch(q -> q.startsWith("\"Engineer\"") || q.startsWith("\"Architect\"")), "never the cut's single words: " + bySite);
        assertEquals(6, bySite.size(), "each title in three searches: " + bySite);
        assertTrue(bySite.contains("\"Security Architect\" (\"Columbus\" OR \"Ohio\" OR \"Remote\") (site:apply.workable.com OR site:myworkdayjobs.com OR site:recruitee.com)"), "a system whose employers have hosts of their own, by its domain: " + bySite);
        assertEquals(9, CareerSources.discoverQueries(), "three titles, three searches each, whatever the table holds (15 before 0.8.4)");
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < asked.size(); i++) if (asked.get(i).contains("site:")) times.add(at.get(i));
        for (int i = 1; i < times.size(); i++) assertTrue(times.get(i) - times.get(i - 1) >= 28, "the searches by site are paced: " + (times.get(i) - times.get(i - 1)) + " ms between two");
        ObjectNode gearbox = list.stream().filter(c -> c.path("name").asText().equals("Gearbox")).findFirst().orElseThrow();
        assertEquals("search", gearbox.path("from").asText(), "the way it was found is kept with the company");
        assertTrue(Career.logTail(store, s, 200).stream().anyMatch(l -> l.contains("The search looked for Security Engineer and Security Architect on the hiring systems' own job pages (Ashby, Greenhouse, Lever, Workable, Workday, Recruitee, Personio, SmartRecruiters)")
                && l.contains("6 searches, 0.03 seconds apart, and they gave 1 employer with a posting of a title you want that its own board still lists.")), String.join("\n", Career.logTail(store, s, 200)));
    }

    @Test void withNoTitlesFromTheModelTheTitlesInTheWordsThePersonDecidedAreSearched(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = seeker(store, tmp, "Security engineer or security architect, hands-on, without a team to run");
        Cut cut = CareerCut.set(store, s, Map.of("titles", "Engineer, Architect", "places", "Columbus, Ohio", "remote", "fine"));   // the person's own cut: no titles of the model's
        assertTrue(cut.searchTitles().isEmpty());
        CareerFind.companyList(store, s, cut, POSTED, x -> { }, () -> false, null, false);
        assertEquals("\"Security engineer\" (\"Columbus\" OR \"Ohio\" OR \"Remote\") (site:jobs.ashbyhq.com OR site:greenhouse.io OR site:jobs.lever.co)", asked.stream().filter(q -> q.contains("site:")).findFirst().orElseThrow());
        assertTrue(asked.contains("\"security architect\" (\"Columbus\" OR \"Ohio\" OR \"Remote\") (site:apply.workable.com OR site:myworkdayjobs.com OR site:recruitee.com)"), asked.toString());
        assertTrue(asked.stream().noneMatch(q -> q.contains("hands-on") || q.contains("team to run")), "only the parts that are titles");

        Cut words = new Cut(List.of("VP", "CTO", "Engineer"), List.of(), List.of(), List.of("Engineering"), List.of(), "", List.of(), List.of(), List.of(), 0, 0, "you", "", "");
        assertEquals(List.of("VP of Engineering", "CTO"), CareerFind.titlesIn("VP of Engineering or CTO at a seed-stage startup", words), "a rank with its area; a title cut where the sentence goes on");
        assertEquals(List.of("embedded firmware engineer"), CareerFind.titlesIn("Senior embedded firmware engineer.", words), "no level at the head");
        assertEquals(List.of(), CareerFind.titlesIn("I would like to keep doing what an engineer does but somewhere much calmer", words), "a sentence is not a title");
        assertEquals(List.of("Ingeniero de firmware"), CareerFind.titlesIn("Ingeniero de firmware", Cut.NONE), "with no title words in the cut, any part that is short enough");
        assertEquals(List.of("Payroll clerk"), CareerFind.searchTitles(Cut.NONE, "Payroll clerk"));
        assertEquals(List.of(), CareerFind.searchTitles(Cut.NONE, ""), "nothing decided and no title held: the cut's words are searched, as before");
    }

    @Test void aTitleTheSearchLooksForPassesTheCutWholeAndOnlyWhole() {
        Cut cut = new Cut(List.of("Clerk"), List.of(), List.of("Manager"), List.of(), List.of(), "only", List.of("UK"), List.of(), List.of(), 0, 0, "library", "", "", List.of(), 0, List.of("Payroll Administrator"));
        assertTrue(CareerCut.judge(cut, CareerSources.opening("Harbor", "Payroll Administrator", "UK - Remote", true, "https://x/1", "", "", "", "", "", ""), List.of()).passes(),
                "the title the search looked for, though the cut's words have only Clerk");
        assertTrue(CareerCut.judge(cut, CareerSources.opening("Harbor", "Senior Payroll Administrator II", "UK - Remote", true, "https://x/6", "", "", "", "", "", ""), List.of()).passes(), "a level aside");
        assertFalse(CareerCut.judge(cut, CareerSources.opening("Harbor", "Data Clerk", "UK - Remote", true, "https://x/2", "", "", "", "", "", ""), List.of()).passes(),
                "with whole titles, the cut's single word no longer passes a title of other work (0.8.4)");
        assertFalse(CareerCut.judge(cut, CareerSources.opening("Harbor", "Accounts Administrator", "UK - Remote", true, "https://x/3", "", "", "", "", "", ""), List.of()).passes(), "a part of it is not enough");
        assertFalse(CareerCut.judge(cut, CareerSources.opening("Harbor", "Payroll Administrator Manager", "UK - Remote", true, "https://x/4", "", "", "", "", "", ""), List.of()).passes(), "the words kept out still cut");
        assertFalse(CareerCut.judge(cut, CareerSources.opening("Harbor", "Payroll Administrator", "Leeds, UK", false, "https://x/5", "", "", "", "", "", ""), List.of()).passes(), "and so does the place");
        assertTrue(CareerCut.summary(cut).startsWith("titles that hold every word of Payroll Administrator, in any order, a level such as Senior aside"), CareerCut.summary(cut));
    }

    @Test void theSearchBySiteHasAPartOfTheListOfItsOwnAndASourceNotAskedIsSaid(@TempDir Path tmp) throws Exception {
        // of a list of 60, the sources before it left the search by site room for 8, or none (0.8.4's measurement): it keeps a third of its own
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        cutAnswer = """
                {"role_titles": ["Engineer"], "places": ["Columbus", "Ohio"], "remote": "fine", "remote_where": ["US"], "search_titles": ["Security Engineer"]}""";
        Career.Seeker s = seeker(store, tmp, "Security engineer");
        Cut cut = CareerCut.make(store, s);
        ObjectNode doc = new ObjectMapper().createObjectNode(), all = doc.putObject("companies");
        for (int i = 1; i <= 6; i++) all.putObject("placeco" + i).put("name", "Place Co " + i).putObject("reading").put("approach", "yes").put("fits", "Near you.");
        Files.writeString(CareerCompanies.file(store, s), doc.toString());   // the companies of the place worth approaching: enough to fill the list
        searches.put("\"Security Engineer\" (\"Columbus\" OR \"Ohio\" OR \"Remote\") (site:jobs.ashbyhq.com OR site:greenhouse.io OR site:jobs.lever.co)", List.of(new Hit("Gearbox - Security Engineer", "https://jobs.lever.co/gearbox/5c4b3a29-aa10-4f00-8d3e-000000000003")));
        pages.put("https://api.lever.co/v0/postings/gearbox?mode=json", """
                [{"text": "Security Engineer", "categories": {"location": "Columbus, OH"}, "createdAt": %d, "hostedUrl": "https://jobs.lever.co/gearbox/5c4b3a29-aa10-4f00-8d3e-000000000003", "descriptionPlain": "Gearbox builds gearboxes in Columbus."}]"""
                .formatted(LocalDate.now().minusDays(3).toEpochDay() * 86_400_000L));
        CareerSources.SEARCH_GAP_MS = 0;
        Config.set(CareerFind.COMPANIES_KEY, "6");
        try {
            List<String> names = CareerFind.companyList(store, s, cut, POSTED, x -> { }, () -> false, null, false).stream().map(c -> c.path("name").asText()).toList();
            assertEquals(5, names.size(), names.toString());
            assertEquals(Set.of("Place Co 1", "Place Co 2", "Place Co 3", "Place Co 4", "Gearbox"), Set.copyOf(names), "a third of the six is the search by site's, which the companies of the place cannot take: " + names);
            assertTrue(Career.logTail(store, s, 100).stream().anyMatch(l -> l.contains("3 searches, 0 seconds apart, and they gave 1 employer with a posting of a title you want")), String.join("\n", Career.logTail(store, s, 100)));

            asked.clear();
            CareerShape.Shape referral = new CareerShape.Shape(List.of("referral"), List.of("none"), "", "", "", "", "carpenter", "Columbus, Ohio", "", "the model", "", LocalDate.now().toString());
            CareerFind.companyList(store, s, cut, referral, x -> { }, () -> false, null, false);
            assertTrue(asked.stream().noneMatch(q -> q.contains("site:")), "seats not posted openly: no search of the hiring sites");
            assertTrue(Career.logTail(store, s, 100).stream().anyMatch(l -> l.endsWith("The hiring sites were not searched, since seats like yours are not posted openly, by how the search reads such seats are filled.")), String.join("\n", Career.logTail(store, s, 100)));

            searches.clear();
            CareerFind.SAID.remove();
            CareerFind.companyList(store, s, cut, POSTED, x -> { }, () -> false, null, false);
            assertEquals(List.of("The search of the hiring sites for the titles you want gave no employer this time."), CareerFind.SAID.get(), "searched and gave nothing: the run's summary says so");
            CareerSources.SEARCH = q -> { CareerSources.LAST_ENGINE.set(WebSearchTool.FALLBACK_NAME); return List.of(); };
            CareerFind.SAID.remove();
            CareerFind.companyList(store, s, cut, POSTED, x -> { }, () -> false, null, false);
            assertEquals(List.of("The hiring sites could not be searched this time: no search engine answered."), CareerFind.SAID.get(), "not searched, never \"gave nothing\"");
            assertTrue(Career.logTail(store, s, 100).stream().anyMatch(l -> l.contains("3 searches, 0 seconds apart, none of them answered by a search engine, and they gave no employer")), String.join("\n", Career.logTail(store, s, 100)));
        } finally { Config.set(CareerFind.COMPANIES_KEY, ""); CareerFind.SAID.remove(); }
    }

    @Test void theSummarySaysWhyItsSearchesWentWhereTheyWentAndTheSearchBySiteTakesNoFallback() {
        assertEquals("No key for a paid search service is set, so the free one answered them.", CareerFind.whyLine(Set.of(), 9, 0, false));
        assertEquals("", CareerFind.whyLine(Set.of(), 63, 63, true), "a paid search made: the look-ups' line already says which and how many");
        assertEquals("", CareerFind.whyLine(Set.of(), 0, 0, false), "nothing searched, nothing said");
        assertEquals("This month's 1,000 searches through Brave are used; the web search goes on with SearXNG until the month turns.",
                CareerFind.whyLine(Set.of("this month's 1,000 searches through Brave are used; the web search goes on with SearXNG until the month turns"), 4, 0, true), "a paid engine at its limit, in the web search's words");
        CareerSources.forgetNotes();
        CareerSources.noted("results for \"x\":\n<<<SEARCH RESULTS>>>\n1. a\n<<<END>>>\n(The text between the SEARCH RESULTS markers is quoted material from a source.)\n");
        CareerSources.noted("results\n(This month's 1,000 searches through Brave are used, so Brave is left off until the month turns.)\n");
        assertEquals(Set.of("This month's 1,000 searches through Brave are used, so Brave is left off until the month turns."), CareerSources.notesSoFar(),
                "the web search's own note, not the quoted-material line of its results");
        CareerSources.forgetNotes();

        // when no search engine answered, the web search's built-in fallback (Wikipedia and papers) does: a search by site takes nothing from it
        CareerSources.SEARCH_GAP_MS = 0;
        CareerSources.SEARCH = q -> { CareerSources.LAST_ENGINE.set(WebSearchTool.FALLBACK_NAME); return List.of(new Hit("Gearbox - Security Engineer", "https://jobs.lever.co/gearbox/5c4b3a29-aa10-4f00-8d3e-000000000003")); };
        int[] made = new int[2];
        assertEquals(List.of(), CareerSources.discover(List.of("\"Security Engineer\""), List.of(), 5, t -> true, d -> true, made));
        assertArrayEquals(new int[]{3, 3}, made, "three searches made, none answered by a search engine");
        CareerSources.ROWS.set(10);
        List<Integer> rows = new ArrayList<>();
        CareerSources.SEARCH = q -> { rows.add(CareerSources.ROWS.get()); return List.of(); };
        CareerSources.SEARCH_GAP_MS = 0;
        CareerSources.discover(List.of("\"Payroll Clerk\""), List.of(), 5);
        assertTrue(!rows.isEmpty() && rows.stream().allMatch(r -> r == 20), "a search of the hiring sites asks for 20 hits, Brave's most in one request, so the third host of a group is not crowded out: " + rows);
        assertEquals(10, CareerSources.ROWS.get(), "the other searches as before");
        CareerSources.ROWS.remove();
    }

    @Test void theSummaryCountsAsFitWhatThePageListsAsFitAndAnOpeningWithNoPlaceAsANearMiss() {
        Cut cut = new Cut(List.of("Engineer"), List.of(), List.of(), List.of(), List.of("Columbus", "Ohio"), "fine", List.of("US"), List.of(), List.of(), 0, 0, "library", "", "", List.of(), 0,
                List.of("Security Engineer"), List.of());
        List<CareerFind.Row> rows = new ArrayList<>();
        for (CareerSources.Opening o : List.of(CareerSources.opening("Gearbox", "Security Engineer", "Columbus, OH", false, "https://x/1", "", "", "t", "", "", ""),
                CareerSources.opening("Kiln", "Senior Security Engineer", "", false, "https://x/2", "", "", "hn", "", "", "")))
            rows.add(new CareerFind.Row(o, null, CareerCut.judge(cut, o, List.of()), 0, null, null));
        assertTrue(rows.stream().allMatch(r -> r.verdict().passes()), "an opening whose place is not given stays on the page");
        CareerFind.View v = new CareerFind.View(cut, List.of(), rows, 2, 0, Map.of(), "", List.of());
        String said = CareerFind.summary(v, 2, 0, 0, 0);
        assertTrue(said.contains("1 opening fits what you decided, at 1 employer. 1 more is a near miss: the role elsewhere or remote, or the place with another role, or no place given."), said);
    }

    @Test void aSystemAddedToTheTableAddsNoSearchBySite() {
        List<CareerSources.Site> sixteen = new ArrayList<>(CareerSources.SITES);
        for (int i = 0; i < 7; i++) sixteen.add(new CareerSources.Site("*.system" + i + ".example", "System " + i, Pattern.compile("system" + i + "\\.example/job/\\d+")));
        List<List<CareerSources.Site>> groups = CareerSources.siteGroups(sixteen);
        assertEquals(CareerSources.siteGroups(CareerSources.SITES).size(), groups.size(), "as many searches with sixteen systems as with nine");
        assertEquals(3, groups.size());
        assertTrue(groups.stream().allMatch(g -> g.size() <= 3), "three sites a search: what Bing reads of a query holds them");
        assertEquals("(site:jobs.ashbyhq.com OR site:greenhouse.io OR site:jobs.lever.co)", CareerSources.sitesOf(groups.get(0)));
        assertEquals("site:jobs.lever.co", CareerSources.sitesOf(List.of(CareerSources.SITES.get(3))), "one site alone, as before");
        assertTrue(CareerSources.siteHosts().size() == CareerSources.SITES.stream().map(CareerSources.Site::host).distinct().count(), "Exa is given every host of the table");
    }

    @Test void theTableHasOnePostingShapeForEachSystemAndTheOldFiveStillCount() {
        assertTrue(CareerSources.posting("https://jobs.ashbyhq.com/river/c3c320bf-0313-44fa-879c-b66b0c7385d9"));
        assertTrue(CareerSources.posting("https://job-boards.greenhouse.io/radiclehealth/jobs/5284641007"));
        assertTrue(CareerSources.posting("https://job-boards.greenhouse.io/embed/job_app?for=acme&gh_jid=7767161"), "a Greenhouse posting by its number");
        assertTrue(CareerSources.posting("https://huntington.wd12.myworkdayjobs.com/en-US/HNBcareers/job/Information-Security-Platform-Architect_R0076694"));
        assertTrue(CareerSources.posting("https://vicentra.recruitee.com/o/embedded-software-engineer"));
        assertTrue(CareerSources.posting("https://acme.jobs.personio.de/job/1234567"));
        assertFalse(CareerSources.posting("https://jobs.lever.co/avertium"), "a board's front page");
        assertFalse(CareerSources.posting("https://huntington.wd12.myworkdayjobs.com/en-US/HNBcareers"), "a Workday site's front page");
        assertFalse(CareerSources.posting("https://www.glassdoor.com/job-listing/jv?jl=1010241957263"), "a job board");
        assertTrue(CareerSources.siteHosts().containsAll(List.of("jobs.ashbyhq.com", "job-boards.greenhouse.io", "boards.greenhouse.io", "jobs.lever.co", "apply.workable.com", "*.myworkdayjobs.com")));
    }
}
