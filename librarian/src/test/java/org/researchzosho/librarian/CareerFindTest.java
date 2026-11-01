package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.CareerFind.Row;
import org.researchzosho.librarian.CareerFind.View;
import org.researchzosho.librarian.CareerSources.Hit;
import org.researchzosho.librarian.profiles.CareerProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The career search from end to end, on stand-in hiring pages and a stand-in model: no web, no model server. */
class CareerFindTest {

    static final ObjectMapper M = new ObjectMapper();
    final Map<String, String> pages = new HashMap<>();
    final Map<String, List<Hit>> searches = new HashMap<>();
    final List<String> prompts = new CopyOnWriteArrayList<>();
    volatile boolean proposeOnlyFormlabs = false;
    Function<String, String> usaJobsWas;
    BooleanSupplier serviceWas;

    static final String RECENT = LocalDate.now().minusDays(5).toString();

    /** The model: the reading and an empty draft for the record, the cut, the proposals, and a reading of each opening in the three lines. */
    final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String prose(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText();
            prompts.add(p);
            if (p.contains("Write a short reading of the opening")) {
                String title = p.replaceFirst("(?s).*Title: ([^\\n]*)\\n.*", "$1");
                return "Fits: " + title + " is the kind of role you decided on.\nAgainst: nothing.\nUnknown: the size of the team.";
            }
            return CareerInterviewTest.READING;
        }
        @Override public String classify(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText();
            prompts.add(p);
            if (p.startsWith("Turn what a person wants")) return """
                    {"role_titles": ["VP", "Head of", "Director"], "ownership_titles": [], "titles_not": ["Intern"], "places": ["Boston", "MA"], "remote": "fine",
                     "remote_where": ["US", "United States"], "prefer_titles": ["Hardware"], "prefer_places": ["Boston"]}""";
            if (p.startsWith("A person is looking for work. Propose")) return proposeOnlyFormlabs ? "[{\"company\": \"Formlabs\", \"why\": \"It makes hardware, which you lean towards.\"}]" : """
                    [{"company": "Formlabs", "why": "It makes hardware, which you lean towards."}, {"company": "DraftKings", "why": "A large Boston employer."},
                     {"company": "Acme", "why": "Remote-first engineering."}, {"company": "Whoop", "why": "Named again by the model."}]""";
            return "[]";
        }
    }

    @BeforeEach void standIns() {
        CareerSources.reset();
        CareerSources.POLITE_MS = 0;
        CareerSources.RAW = pages::get;
        CareerSources.RAW_POST = (u, j) -> null;
        CareerSources.SEARCH = q -> searches.getOrDefault(q, List.of());
        usaJobsWas = CareerSources.USAJOBS;
        CareerSources.USAJOBS = u -> null;
        Career.DRIVE = StandIn::new;
        serviceWas = Share.serviceRunning;
        Share.serviceRunning = () -> true;
        searches.put("Formlabs careers jobs", List.of(new Hit("Formlabs - Jobs", "https://boards.greenhouse.io/formlabs")));
        // a company found hiring by a search of the hiring sites, whose board no longer lists the posting the search turned up
        searches.put("\"VP\" \"Hardware\" (\"Boston\" OR \"MA\" OR \"Remote\") site:jobs.ashbyhq.com", List.of(new Hit("VP of Hardware @ Ghost Co", "https://jobs.ashbyhq.com/ghostco/5c4b3a29-aa10-4f00-8d3e-000000000001"),
                new Hit("VP of Hardware @ Live Co", "https://jobs.ashbyhq.com/liveco/5c4b3a29-aa10-4f00-8d3e-000000000002")));
        pages.put("https://api.ashbyhq.com/posting-api/job-board/ghostco?includeCompensation=true", """
                {"jobs": [{"title": "Software Engineer", "location": "Boston", "jobUrl": "https://jobs.ashbyhq.com/ghostco/1", "descriptionPlain": "Ghost Co builds things.", "publishedAt": "%sT00:00:00.000+00:00"}]}""".formatted(RECENT));
        pages.put("https://api.ashbyhq.com/posting-api/job-board/liveco?includeCompensation=true", """
                {"jobs": [{"title": "VP of Hardware", "location": "Boston, MA", "jobUrl": "https://jobs.ashbyhq.com/liveco/2", "descriptionPlain": "Live Co makes hardware in Boston.", "publishedAt": "%sT00:00:00.000+00:00"}]}""".formatted(RECENT));
        searches.put("Acme careers jobs", List.of(new Hit("Acme jobs", "https://jobs.lever.co/acme")));
        pages.put("https://boards-api.greenhouse.io/v1/boards/formlabs/jobs?content=true", formlabs(true));
        pages.put("https://api.lever.co/v0/postings/acme?mode=json", """
                [{"text": "Head of Platform", "categories": {"location": "Remote - US"}, "workplaceType": "remote", "createdAt": %d, "hostedUrl": "https://jobs.lever.co/acme/1", "descriptionPlain": "Run the platform team."},
                 {"text": "Sales Manager", "categories": {"location": "Boston, MA"}, "createdAt": %d, "hostedUrl": "https://jobs.lever.co/acme/2", "descriptionPlain": "Sell."}]"""
                .formatted(LocalDate.now().minusDays(2).toEpochDay() * 86_400_000L, LocalDate.now().minusDays(2).toEpochDay() * 86_400_000L));
        pages.put("https://hn.algolia.com/api/v1/search_by_date?tags=story,author_whoishiring&hitsPerPage=10", "{\"hits\": [{\"title\": \"Ask HN: Who is hiring? (October 2026)\", \"objectID\": \"9\"}]}");
        pages.put("https://hn.algolia.com/api/v1/items/9", """
                {"children": [{"id": 91, "created_at": "%sT10:00:00.000Z", "text": "BetCo | Director of Engineering | Boston, MA<p>We run a gambling platform for sports fans.</p>"}]}""".formatted(RECENT));
    }

    String formlabs(boolean withEmea) {
        return """
                {"jobs": [
                  {"title": "VP of Engineering", "location": {"name": "Boston, MA"}, "absolute_url": "https://formlabs.example/jobs/1", "first_published": "%sT09:00:00Z", "content": "&lt;p&gt;Lead hardware engineering.&lt;/p&gt;"},
                  {"title": "Engineering Intern", "location": {"name": "Boston, MA"}, "absolute_url": "https://formlabs.example/jobs/2", "first_published": "%sT09:00:00Z", "content": ""}%s]}"""
                .formatted(RECENT, RECENT, withEmea ? ",\n{\"title\": \"Director, Hardware\", \"location\": {\"name\": \"Remote, EMEA\"}, \"absolute_url\": \"https://formlabs.example/jobs/3\", \"first_published\": \"" + RECENT + "T09:00:00Z\", \"content\": \"\"}" : "");
    }

    @AfterEach void back() {
        CareerSources.RAW = CareerSources::fetch; CareerSources.RAW_POST = CareerSources::post; CareerSources.SEARCH = CareerSources::search;
        CareerSources.USAJOBS = usaJobsWas; CareerSources.POLITE_MS = 1000; CareerSources.reset();
        Career.DRIVE = Explain::drive;
        Share.serviceRunning = serviceWas;
    }

    Career.Seeker seeker(LibraryStore store, Path tmp) throws Exception {
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        Career.setWant(store, s, "role", "decided", "engineering leadership");
        Career.setWant(store, s, "place", "decided", "Boston, or remote in the US");
        Career.setWant(store, s, "domain", "guess", "hardware, as most of your roles were");
        CareerSearch.addCompany(store, s, "Whoop", "");
        CareerSearch.addKeepOut(store, s, "DraftKings", "");
        CareerSearch.addKeepOut(store, s, "gambling", "");
        // the companies of the place as the public records list them: what the model may choose among, and nothing else
        ObjectNode doc = M.createObjectNode().put("refreshed", LocalDate.now().toString()).put("place", "Boston, MA");
        ObjectNode all = doc.putObject("companies");
        for (String[] c : new String[][]{{"Formlabs", "Boston", "3D printers"}, {"DraftKings", "Boston", "sports betting"}, {"Acme", "Boston", "a platform"}, {"Whoop", "Boston", "wearables"}, {"Initech", "Boston", "software"}})
            all.putObject(CareerSearch.key(c[0])).put("name", c[0]).put("city", c[1]).put("about", c[2]).put("first_seen", LocalDate.now().toString());
        Files.createDirectories(CareerFind.dir(store, s));
        CareerFind.write(CareerFind.dir(store, s).resolve("companies-first.json"), doc);
        return s;
    }

    @Test void theSearchListsTheCompaniesReadsTheirOpeningsCutsThemByWhatWasDecidedAndReadsTheNearest(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = seeker(store, tmp);
        List<String> said = new ArrayList<>();
        String summary = CareerFind.run(store, s, said::add, () -> false);

        // the companies: the one the person named first, the library's proposals after, never one kept out
        List<String> names = CareerFind.companies(store, s).stream().map(c -> c.path("name").asText()).toList();
        assertEquals(List.of("Whoop", "Live Co", "Formlabs", "Acme"), names, "named first, then the company found hiring whose board still lists the posting, then the proposals; Ghost Co's board no longer lists it; DraftKings is kept out");
        JsonNode live = CareerFind.companies(store, s).get(1);
        assertTrue(live.path("why").asText().startsWith("It is hiring for VP of Hardware"), live.path("why").asText());
        ObjectNode ghost = M.createObjectNode().put("name", "Ghost Co").put("why", "It is hiring for VP of Hardware, found in a search of the hiring sites.");
        CareerFind.stillListed(ghost, new CareerSources.Found("Ghost Co", new CareerSources.Feed("ashby", "ghostco", ""), List.of(CareerSources.opening("Ghost Co", "Software Engineer", "Boston", false, "https://g/1", "", "", "ashby:ghostco", "", "", "")), ""));
        assertEquals("A search of the hiring sites found its page for VP of Hardware, but its own board no longer lists that opening.", ghost.path("why").asText(), "a posting that goes stale later is said to be stale");
        JsonNode whoop = CareerFind.companies(store, s).get(0), formlabs = CareerFind.companies(store, s).get(2);
        assertEquals("you", whoop.path("by").asText());
        assertFalse(whoop.has("feed")); assertEquals("the search found no careers page for it", whoop.path("note").asText());
        assertEquals("Greenhouse", formlabs.path("feed").path("system").asText()); assertEquals(3, formlabs.path("openings").asInt());
        assertEquals("It makes hardware, which you lean towards.", formlabs.path("why").asText());
        String proposal = prompts.stream().filter(p -> p.startsWith("A person is looking for work. Propose")).findFirst().orElseThrow();
        assertTrue(proposal.contains("Propose up to 5 companies, at most,") && proposal.contains("- DraftKings") && proposal.contains("ALREADY ON THE LIST\nWhoop, Live Co"), proposal);
        assertTrue(proposal.contains("THE COMPANIES OF THEIR PLACE\n") && proposal.contains("- Formlabs, Boston: 3D printers") && proposal.contains("- Initech, Boston: software") && !proposal.contains("- Whoop, Boston"), "the model chooses among the records' companies not yet listed: " + proposal);
        assertEquals("chosen", formlabs.path("from").asText(), "a chosen company says so");

        // the cut: only what was decided cuts; the guess orders
        View v = CareerFind.view(store, s);
        assertEquals("titles with VP, Head of or Director; titles without Intern; in Boston or MA; remote counts when based in US or United States; first in the order: titles with Hardware and places in Boston", CareerCut.summary(v.cut()));
        assertEquals(List.of("VP of Hardware", "VP of Engineering", "Head of Platform", "Director of Engineering"), v.passing().stream().map(r -> r.opening().title()).toList(),
                "the intern, the sales manager and the remote role based in EMEA are cut; the post that mentions gambling passes, last; the hardware role the guess leans to comes first");
        Row betco = v.passing().get(3);
        assertEquals(List.of("gambling"), betco.verdict().mentions());
        assertEquals("hn:October 2026", betco.opening().source());

        // the readings: each passing opening read, in the three lines, the opening fenced
        assertTrue(v.passing().stream().allMatch(Row::read));
        assertEquals("VP of Engineering is the kind of role you decided on.", v.passing().get(1).reading().path("fits").asText());
        assertEquals("the size of the team.", v.passing().get(1).reading().path("unknown").asText());
        String reading = prompts.stream().filter(p -> p.contains("Write a short reading") && p.contains("Title: VP of Engineering")).findFirst().orElseThrow();
        assertTrue(reading.contains(Fence.open("OPENING")) && reading.contains("Lead hardware engineering.") && reading.contains("- domain: a guess: hardware"), reading);
        assertEquals("The search looked at 4 employers and could read the openings of 3 of them, and 1 posts in Hacker News's Who is hiring. 4 openings fit what you decided, at 4 employers. The library read 4 of them nearest to what you want. The public records give 5 employers of your place; the model read 0 of them against what you want this time.", summary);
        assertTrue(said.contains("Reading the companies' openings, 4 of 4 done") && said.contains("Reading the openings nearest to what you want, 4 of 4 read"), said.toString());
        assertTrue(Files.list(CareerFind.dir(store, s).resolve("runs")).count() == 1, "a record of the search is kept");
        assertTrue(Files.readString(s.dir(store).resolve("log.jsonl")).contains("Globex is where you work now, so the search leaves it out."), "the employer of the role that runs to now is left out, and the log says so");
        String page = CareerPage.body(store, s, true);   // and the person's page lists it: when, what it looked at and could read, what passed and was read, the cut it used
        int searches = page.indexOf("<details class=\"sec\" id=\"searches\"><summary><h2>Searches</h2></summary>");
        assertTrue(searches > 0 && searches < page.indexOf("<details class=\"sec\" id=\"reading\">") && page.contains("<td>4</td><td>3</td><td>4</td><td>4</td>") && page.contains("The employers it looked at") && page.contains("<a href=\"#searches\">Searches</a>"), page.substring(Math.max(0, searches)));
        String stem = Files.list(CareerFind.dir(store, s).resolve("runs")).findFirst().orElseThrow().getFileName().toString().replaceFirst("\\.json$", "");
        assertTrue(page.contains("&amp;run=" + stem + "\">"), "the date opens that search's results");
        String past = CareerOpeningsPage.body(store, s, true, stem);
        assertTrue(past.contains("<h2>The search of ") && past.contains("<b>4 openings passed</b> what counted as a fit then; the library read 4. Of those, 4 still pass what you decided now. ") && past.contains("What counted as a fit then: ") && past.contains("VP of Engineering"), past);
        assertTrue(CareerOpeningsPage.body(store, s, true, "no-such-run").contains("There is no record of a search called no-such-run."));

        // what the person wants changes: the library's cut says it is out of date, and is made again without a new search
        assertFalse(CareerCut.stale(store, s));
        Career.setWant(store, s, "role", "decided", "engineering leadership, hands on");
        assertTrue(CareerCut.stale(store, s));
        assertTrue(CareerOpeningsPage.body(store, s, true).contains("What you want has changed since these openings were sorted") && CareerFind.text(store, s, "/career", 3).contains("type /career cut again"));
        CareerCut.make(store, s);
        assertFalse(CareerCut.stale(store, s));
        assertFalse(CareerOpeningsPage.body(store, s, true).contains("What you want has changed since these openings were sorted"));

        // reactions, keep and drop, the cut changed by the person: cut again at once, no new search
        String vp = v.passing().get(1).opening().id(), head = v.passing().get(2).opening().id();
        assertEquals("Liked: VP of Engineering at Formlabs.", CareerFind.react(store, s, vp.substring(0, 6), "like", "hardware and Boston"));
        assertEquals("Passed on: Head of Platform at Acme.", CareerFind.react(store, s, head, "pass", "platform work bores me"));
        assertTrue(CareerFind.companyState(store, s, "acme", "dropped").startsWith("Acme is dropped"));
        v = CareerFind.view(store, s);
        assertEquals(List.of("VP of Hardware", "VP of Engineering", "Director of Engineering"), v.passing().stream().map(r -> r.opening().title()).toList(), "a dropped company's openings leave the lists");
        assertEquals("like", v.passing().get(1).reactionWord());
        CareerCut.set(store, s, Map.of("titles", "Director", "places", ""));
        v = CareerFind.view(store, s);
        assertEquals(List.of("Director, Hardware", "Director of Engineering"), v.passing().stream().map(r -> r.opening().title()).toList(),
                "with no place cut, the EMEA role passes; the VP role no longer does");
        assertEquals("you", CareerCut.kept(store, s).by());
        assertFalse(v.passing().get(0).read(), "a newly passing opening waits for Read more");
        assertEquals(List.of("Director"), CareerCut.current(store, s).titles(), "the person's cut stays theirs");

        // a second search: what was seen keeps its first day; what left a company's whole list is gone; a company of the library's with an
        // opening still passing stays on the list although the model no longer proposes it
        CareerCut.make(store, s);
        pages.put("https://boards-api.greenhouse.io/v1/boards/formlabs/jobs?content=true", formlabs(false));
        CareerFind.companyState(store, s, "Acme", "");
        proposeOnlyFormlabs = true;
        List<ObjectNode> without = new ArrayList<>(); for (ObjectNode c : CareerFind.companies(store, s)) if (!c.path("name").asText().equals("Acme")) without.add(c);
        CareerFind.saveCompanies(store, s, without);   // as an earlier version left it: Acme gave way while its openings stayed
        CareerFind.run(store, s, x -> { }, () -> false);
        ObjectNode acme = CareerFind.companies(store, s).stream().filter(c -> c.path("name").asText().equals("Acme")).findFirst().orElse(null);
        assertNotNull(acme, "Acme's Head of Platform passes, so Acme is back on the list");
        assertEquals("lever", acme.path("feed").path("kind").asText(), "with the board its openings were read from"); assertEquals("Its openings passed an earlier search.", acme.path("why").asText());
        CareerCut.set(store, s, Map.of("titles", "Nothing-Matches"));   // nothing of Acme's passes now: it gives way, and stays known
        CareerFind.run(store, s, x -> { }, () -> false);
        ObjectNode gone = CareerFind.companies(store, s).stream().filter(c -> c.path("name").asText().equals("Acme")).findFirst().orElse(null);
        assertNotNull(gone, "a company that gave way stays on the record"); assertEquals("gone", gone.path("state").asText());
        assertFalse(CareerOpeningsPage.body(store, s, true).contains(">Acme<"), "and is not shown among the companies");
        Map<String, ObjectNode> all = CareerFind.openings(store, s);
        assertEquals(LocalDate.now().toString(), all.get(vp).path("first_seen").asText());
        String emea = all.values().stream().filter(o -> o.path("title").asText().equals("Director, Hardware")).findFirst().orElseThrow().path("gone").asText();
        assertEquals(LocalDate.now().toString(), emea, "an opening no longer on its company's list is marked gone today");
        assertEquals(1, CareerFind.view(store, s).gone());
    }

    @Test void thePageTheChatTheProtocolAndTheRunsShowAndChangeIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = seeker(store, tmp);
        String page0 = CareerOpeningsPage.body(store, s, true);
        assertTrue(page0.contains("There has been no search yet.") && page0.contains(">Search now</button>"), page0);
        String filed = CareerFind.file(store, s, "person", "career-search");
        assertTrue(filed.startsWith("The search looks at up to 60 employers") && filed.contains("It is filed as "), filed);
        assertTrue(CareerFind.file(store, s, "person", "career-search").startsWith("A search for "), "one search at a time for one person");
        assertTrue(CareerOpeningsPage.body(store, s, true).contains("A search is waiting to start."));
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
        ObjectNode job = jobs.active().get(0);
        assertEquals("career-search", job.path("kind").asText());
        List<ObjectNode> progress = new ArrayList<>();
        String done = CareerFind.runJob(store, job, progress::add, () -> false);
        assertTrue(done.startsWith("The search looked at 4 employers"), done);
        assertTrue(progress.stream().anyMatch(p -> p.path("phase").asText().equals("Making the cut from what you want")));

        String page = CareerOpeningsPage.body(store, s, true);
        assertTrue(page.contains("<b>4 openings fit</b>") && page.contains("<h2>Read by the library</h2>") && page.contains("<b>Fits:</b> VP of Engineering is the kind of role you decided on."), page);
        assertTrue(page.contains("posted " + RECENT) && page.contains("from Greenhouse") && page.contains("from Hacker News's Who is hiring, October 2026"), page);
        assertTrue(page.contains("<b>mentions gambling, which you keep out</b>") && page.contains("<h3>Their openings could not be read</h3>") && page.contains("The search found no careers page for it"), page);
        assertTrue(page.contains("name=\"reaction\" value=\"like\"") && page.contains("<summary>Change what counts as a fit</summary>"), "the forms, for one who may write");
        assertTrue(page.contains("<b>The card is running (J-"), "the search filed cards for the companies whose openings pass, and each opening says so: " + page);
        assertEquals("No card yet", CareerCompany.needs(M.createObjectNode().put("name", "Nobody Co")));
        String noCard = CareerOpeningsPage.card(CareerFind.view(store, s).passing().get(0), s, Map.of(), Map.of(CareerSearch.key(CareerFind.view(store, s).passing().get(0).opening().company()), M.createObjectNode().put("name", "X")), true);
        assertTrue(noCard.contains("<b>No card yet.</b>") && noCard.contains(">Build its card</button>") && noCard.contains(">Full dossier</button>"), "a company with no card says so, with both buttons: " + noCard);
        ObjectNode formlabsRec = CareerFind.companies(store, s).stream().filter(c -> c.path("name").asText().equals("Formlabs")).findFirst().orElseThrow();
        formlabsRec.put("shelves", true).put("stage", "series B").put("stage_by", "a board");
        ((ObjectNode) formlabsRec.get("card")).put("built", "2026-10-29");   // the card landed, with no size in it
        assertEquals("Needs a full run to get its size", CareerCompany.needs(formlabsRec));
        formlabsRec.putObject("people").put("low", 300).put("high", 300).put("source", "its company card, own publication").put("at", "2026-10-29");
        assertEquals("", CareerCompany.needs(formlabsRec), "size and stage known: nothing to ask for");
        formlabsRec.putObject("card").put("job_id", "J-9").put("filed", "2026-10-29").put("full", true).put("built", "");
        assertEquals("The full dossier is running (J-9)", CareerCompany.needs(formlabsRec));
        CareerFind.saveCompanies(store, s, CareerFind.companies(store, s));
        String reader = CareerOpeningsPage.body(store, s, false);
        assertFalse(reader.contains("name=\"reaction\"") || reader.contains("Change what counts as a fit") || reader.contains(">Search again<"), "a reader sees the openings, not the forms");
        assertTrue(CareerPage.body(store, s, true).contains("<details class=\"sec\" id=\"openings\"><summary><h2>Openings</h2></summary>") && CareerPage.body(store, s, true).contains("4 openings fit what you decided; the library read 4."));

        CareerProfile cp = new CareerProfile();
        String vp = CareerFind.view(store, s).passing().get(1).opening().id();
        String listed = cp.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career openings");
        assertTrue(listed.contains("[" + vp + "] VP of Engineering, Formlabs (Boston, MA; posted " + RECENT + ")") && listed.contains("   Fits: VP of Engineering is the kind of role you decided on."), listed);
        assertEquals("Liked: VP of Engineering at Formlabs.", cp.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career like " + vp + " hardware, and close to home"));
        assertEquals("hardware, and close to home", CareerFind.reactions(store, s).path(vp).path("why").asText());
        assertTrue(cp.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career keep Formlabs").startsWith("Formlabs is kept"));
        assertTrue(cp.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career cut").startsWith("The cut: titles with VP, Head of or Director;"));
        assertEquals("The cut, as you set it: titles with VP, Head of or Director; in Boston, Cambridge or MA; remote counts when based in US or United States; first in the order: titles with Hardware and places in Boston.",
                cp.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career cut places Boston, Cambridge, MA").replace("titles without Intern; ", ""));
        cp.page(store, Patrons.Patron.PERSON, "/career/openings", "POST", Map.of(), Map.of("op", "keep-out", "seeker", s.slug(), "name", "Acme"));
        assertTrue(CareerSearch.keepOut(store, s).contains("Acme"));
        assertFalse(CareerOpeningsPage.body(store, s, true).contains(">Acme<"), "a company kept out is never shown");
        var redirect = cp.page(store, Patrons.Patron.PERSON, "/career/openings", "POST", Map.of(), Map.of("op", "react", "seeker", s.slug(), "id", vp, "reaction", ""));
        assertEquals("", CareerFind.reactions(store, s).path(vp).path("reaction").asText(""), "undo takes the reaction back");
        assertNotNull(redirect);

        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "p").put("runtime", "cli");
        JsonNode r = new LibraryProtocol(store).career(a.put("op", "openings").put("seeker", s.name()));
        assertEquals(3, r.path("openings").size(), "Acme kept out: three pass");
        assertEquals("VP of Engineering", r.path("openings").get(0).path("title").asText(), "the liked one at the kept company comes first");
        assertTrue(r.path("openings").get(0).has("reading"));
    }
}
