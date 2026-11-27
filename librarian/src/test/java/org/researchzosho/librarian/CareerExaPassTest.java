package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.CareerCut.Cut;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.tools.SearchTally;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The career search's pass through Exa (0.8.4): asked only with a key, only in a person's own search, as many times as the setting says and
 * the month's limit allows; sent the title and the place and nothing else of the person; only on the hiring systems of the one table and
 * for the last thirty days; and every hit through the checks any opening goes through. Exa stands in on the loopback, the hiring pages and
 * the model stand in too: no web, no model server, no key of anyone's.
 */
class CareerExaPassTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String RIVER = "https://jobs.ashbyhq.com/river/c3c320bf-0313-44fa-879c-b66b0c7385d9";
    static final String RECENT = LocalDate.now().minusDays(4).toString();

    final Map<String, String> pages = new HashMap<>();
    final List<String> exaBodies = new CopyOnWriteArrayList<>();
    volatile String exaAnswer = "{\"results\": []}";
    HttpServer server;
    String realHome;
    final Map<String, String> savedExa = new HashMap<>();

    final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String prose(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText();
            if (p.contains("Write a short reading of the opening")) return "Fits: the role.\nAgainst: nothing.\nUnknown: the team.";
            return CareerInterviewTest.READING;
        }
        @Override public String classify(ArrayNode m, int max) {
            if (!m.get(0).path("content").asText().startsWith("Turn what a person wants")) return "[]";
            return """
                    {"role_titles": ["Engineer", "Architect"], "ownership_titles": [], "titles_not": ["Sales"], "areas": [], "places": ["Columbus", "Ohio", "OH"], "remote": "fine",
                     "remote_where": ["US"], "max_people": 0, "min_people": 0, "search_titles": ["Security Engineer", "Security Architect", "Cloud Security Engineer"], "prefer_titles": [], "prefer_places": []}""";
        }
    }

    @BeforeEach void standIns(@TempDir Path tmp) throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", QuietHome.quiet(tmp.resolve("home")).toString());
        Config.invalidate();
        SearchTally.override = tmp.resolve("tally.json");
        CareerSources.reset();
        CareerSources.POLITE_MS = 0;
        CareerSources.RAW = pages::get;
        CareerSources.RAW_PAGE = (url, headers) -> { String b = pages.get(url); return b == null ? null : new CareerSources.Page(url, 200, b); };   // a posting's own page, as the readers of 0.8.4 ask it
        CareerSources.RAW_POST = (u, j) -> null;
        CareerSources.SEARCH = q -> List.of();   // the search by site finds nothing here: what the list holds came by Exa
        Career.DRIVE = StandIn::new;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/exa/search", x -> {
            exaBodies.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(x, exaAnswer);
        });
        server.start();
        savedExa.putAll(ExaSearchTool.BASE);
        ExaSearchTool.BASE.put("search", "http://127.0.0.1:" + server.getAddress().getPort() + "/exa/search");
        // River's own board still lists the posting Exa found; Electrosoft's no longer does
        pages.put("https://api.ashbyhq.com/posting-api/job-board/river?includeCompensation=true", """
                {"jobs": [{"title": "Staff Security Engineer", "location": "Remote - US", "isRemote": true, "jobUrl": "%s", "descriptionPlain": "River keeps bitcoin safe.", "publishedAt": "%sT00:00:00.000+00:00"}]}"""
                .formatted(RIVER, RECENT));
        pages.put("https://boards-api.greenhouse.io/v1/boards/electrosoft/jobs?content=true", """
                {"jobs": [{"id": 1, "title": "Network Engineer", "location": {"name": "Reston, VA"}, "absolute_url": "https://job-boards.greenhouse.io/electrosoft/jobs/1", "first_published": "%sT09:00:00Z", "content": ""}]}""".formatted(RECENT));
        pages.put("https://api.ashbyhq.com/posting-api/job-board/quilter?includeCompensation=true", """
                {"jobs": [{"title": "Sales Engineer", "location": "Remote - US", "isRemote": true, "jobUrl": "https://jobs.ashbyhq.com/quilter/d56fd7c9-d5ef-4601-a983-bcccd04a11b9", "descriptionPlain": "Sell.", "publishedAt": "%sT00:00:00.000+00:00"}]}""".formatted(RECENT));
        pages.put("https://api.lever.co/v0/postings/refusedco?mode=json", """
                [{"text": "Security Engineer", "categories": {"location": "Columbus, OH"}, "hostedUrl": "https://jobs.lever.co/refusedco/5c4b3a29-aa10-4f00-8d3e-000000000009", "descriptionPlain": "Refused Co."}]""");
        exaAnswer = """
                {"results": [
                  {"title": "Staff Security Engineer at River", "url": "%s", "publishedDate": "%sT00:00:00.000Z"},
                  {"title": "Cybersecurity Engineer - Intrusion Detection at Electrosoft", "url": "https://job-boards.greenhouse.io/electrosoft/jobs/4373461009", "publishedDate": "%sT00:00:00.000Z"},
                  {"title": "Avertium", "url": "https://jobs.lever.co/avertium"},
                  {"title": "Security Architect Job In Columbus, OH", "url": "https://www.glassdoor.com/job-listing/jv?jl=1010241957263"},
                  {"title": "Sales Engineer at Quilter", "url": "https://jobs.ashbyhq.com/quilter/d56fd7c9-d5ef-4601-a983-bcccd04a11b9"},
                  {"title": "Security Engineer at Refusedco", "url": "https://jobs.lever.co/refusedco/5c4b3a29-aa10-4f00-8d3e-000000000009"}],
                 "costDollars": {"total": 0.007}}""".formatted(RIVER, RECENT, RECENT);
    }

    static void send(HttpExchange x, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(200, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    @AfterEach void back() {
        server.stop(0);
        ExaSearchTool.BASE.putAll(savedExa);
        CareerSources.RAW = CareerSources::fetch; CareerSources.RAW_POST = CareerSources::post; CareerSources.SEARCH = CareerSources::search; CareerSources.RAW_PAGE = CareerSources::fetchPage;
        CareerSources.POLITE_MS = 1000; CareerSources.reset();
        Career.DRIVE = Explain::drive;
        SourceRules.OVERRIDE = null;
        SearchTally.override = null;
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    static final CareerShape.Shape POSTED = new CareerShape.Shape(List.of("posted"), List.of("none"), "", "", "", "", "security engineer", "Columbus, Ohio", "", "the model", "", LocalDate.now().toString());

    Career.Seeker seeker(LibraryStore store, Path tmp) throws Exception {
        Files.createDirectories(tmp);
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);   // Jane Doe, an engineering lead at Globex, before that at Acme Widgets, in Chicago
        Career.setWant(store, s, "role", "decided", "Security engineer or security architect, hands-on, without a team to run");
        Career.setWant(store, s, "place", "decided", "Columbus, Ohio, or remote in the US");
        return s;
    }

    @Test void exaIsSentTheTitleAndThePlaceOnlyOnTheTablesHostsForTheLastThirtyDays(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = seeker(store, tmp);
        Config.set(ExaSearchTool.KEY, "exa-test-key");
        Cut cut = CareerCut.make(store, s);
        CareerFind.companyList(store, s, cut, POSTED, x -> { }, () -> false, null, true);

        assertEquals(3, exaBodies.size(), "one search for each title, three by default");
        JsonNode first = M.readTree(exaBodies.get(0));
        assertEquals("Security Engineer job posting in Columbus, Ohio, or remote in US", first.path("query").asText());
        assertEquals("auto", first.path("type").asText());
        assertEquals(10, first.path("numResults").asInt());
        List<String> hosts = new ArrayList<>();
        for (JsonNode h : first.path("includeDomains")) hosts.add(h.asText());
        assertEquals(CareerSources.siteHosts(), hosts, "the hosts of the one table the search by site reads");
        assertEquals(LocalDate.now().minusDays(30) + "T00:00:00.000Z", first.path("startPublishedDate").asText());
        Set<String> fields = new TreeSet<>();
        for (Iterator<String> it = first.fieldNames(); it.hasNext(); ) fields.add(it.next());
        assertEquals(Set.of("query", "type", "numResults", "includeDomains", "startPublishedDate", "moderation"), fields, "no passages, no fresh fetch, no place hint: " + first);
        for (String b : exaBodies)
            for (String personal : List.of("Jane", "Doe", "Globex", "Acme Widgets", "State University", "Chicago", "Java", "hands-on", "team to run", "Engineering lead"))
                assertFalse(b.contains(personal), "nothing of the person but the title and the place goes to Exa: " + personal + " in " + b);
        assertEquals("Cloud Security Engineer job posting in Columbus, Ohio, or remote in US", M.readTree(exaBodies.get(2)).path("query").asText());
        assertEquals(List.of("Payroll Clerk job posting, fully remote, open to candidates in UK"),
                CareerFind.exaQueries(new Cut(List.of("Clerk"), List.of(), List.of(), List.of(), List.of(), "only", List.of("UK"), List.of(), List.of(), 0, 0, "library", "", ""), List.of("Payroll Clerk"), 3));
        assertEquals(List.of("Embedded Firmware Engineer job posting in Valencia"),
                CareerFind.exaQueries(new Cut(List.of("Engineer"), List.of(), List.of(), List.of(), List.of("Valencia"), "no", List.of(), List.of(), List.of(), 0, 0, "library", "", ""), List.of("Embedded Firmware Engineer"), 3));
    }

    @Test void everyExaHitGoesThroughTheChecksAnyOpeningGoesThrough(@TempDir Path tmp) throws Exception {
        Config.set(ExaSearchTool.KEY, "exa-test-key");
        LibraryStore control = new LibraryStore(tmp.resolve("control")); control.init();
        SourceRules.OVERRIDE = control;
        Career.Seeker c0 = seeker(control, tmp.resolve("c"));
        List<String> unrefused = CareerFind.companyList(control, c0, CareerCut.make(control, c0), POSTED, x -> { }, () -> false, null, true).stream()
                .filter(c -> c.path("from").asText().equals("exa")).map(c -> c.path("name").asText()).toList();
        assertEquals(List.of("River", "Refusedco"), unrefused, "the control: both postings their boards still list, whose titles pass the cut");

        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        SourceRules.OVERRIDE = store;
        SourceRules.set(store, "refuse", "jobs.lever.co", "a site the person refused");
        Career.Seeker s = seeker(store, tmp.resolve("s"));
        Cut cut = CareerCut.make(store, s);
        List<ObjectNode> list = CareerFind.companyList(store, s, cut, POSTED, x -> { }, () -> false, null, true);
        List<String> byExa = list.stream().filter(c -> c.path("from").asText().equals("exa")).map(c -> c.path("name").asText()).toList();
        assertEquals(List.of("River"), byExa, "the posting its board still lists; not the one Electrosoft's board no longer lists, not a board's front page, not a job board's page, not a title the cut leaves out, not a refused site's");
        ObjectNode river = list.stream().filter(c -> c.path("name").asText().equals("River")).findFirst().orElseThrow();
        assertEquals("It is hiring for Staff Security Engineer, found by a search by meaning through Exa.", river.path("why").asText(), "Exa's \"at River\" is not part of the title");
        assertEquals("ashby", river.path("feed").path("kind").asText());
        assertTrue(river.path("feed").path("how").asText().startsWith("found hiring: a search by meaning through Exa of Ashby's job pages gave Staff Security Engineer"), river.path("feed").path("how").asText());
        assertTrue(Career.logTail(store, s, 100).stream().anyMatch(l -> l.contains("Exa was asked 3 times for postings on the hiring systems the library reads, published in the last 30 days, and gave 1 employer more, each with a posting of a title you want that its own board still lists; whether their openings are in your place is judged when they are read with the rest.")), String.join("\n", Career.logTail(store, s, 100)));
    }

    @Test void theSystemsReadSince084AreReachedByExaAndCheckedOnTheirOwnBoards() throws Exception {
        Config.set(ExaSearchTool.KEY, "exa-test-key");
        pages.put("https://statsperform.teamtailor.com/jobs.rss", CareerSourcesTest.recorded("teamtailor-jobs.rss"));
        exaAnswer = """
                {"results": [
                  {"title": "Payroll Administrator at Stats Perform", "url": "https://statsperform.teamtailor.com/jobs/8621163-payroll-administrator", "publishedDate": "%sT00:00:00.000Z"},
                  {"title": "Payroll Clerk at Stats Perform", "url": "https://statsperform.teamtailor.com/jobs/8000001-payroll-clerk", "publishedDate": "%sT00:00:00.000Z"}],
                 "costDollars": {"total": 0.007}}""".formatted(RECENT, RECENT);
        CareerSources.ByMeaning bm = CareerSources.byMeaning(List.of("Payroll Clerk job posting, fully remote, open to candidates in UK"), 10, t -> true, d -> CareerSources.stillListed(d, List.of()));
        assertEquals(1, bm.searches());
        assertEquals(List.of("Stats Perform"), bm.found().stream().map(CareerSources.Discovered::company).toList(), "the posting its feed still lists; not the one it no longer does");
        assertEquals("teamtailor:statsperform", bm.found().get(0).feed().label());
        assertEquals("Payroll Administrator", bm.found().get(0).posting(), "Exa's \"at Stats Perform\" is not part of the title");
        assertEquals("exa", bm.found().get(0).way());
        assertTrue(exaBodies.get(0).contains("\"*.teamtailor.com\"") && exaBodies.get(0).contains("\"*.bamboohr.com\""), "every row of the table, a \"*.\" host as Exa takes it: " + exaBodies.get(0));
    }

    @Test void exaKeepsToItsSettingAndTheMonthsLimitAndIsOffWithoutAKeyAtZeroAndAtNight(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = seeker(store, tmp);
        Cut cut = CareerCut.make(store, s);
        CareerFind.companyList(store, s, cut, POSTED, x -> { }, () -> false, null, true);
        assertEquals(0, exaBodies.size(), "no key, no call");
        assertTrue(Career.logTail(store, s, 100).stream().anyMatch(l -> l.endsWith("Exa, the search by meaning, was not asked: no Exa key is set (exa_key).")), "and the log says why");

        Config.set(ExaSearchTool.KEY, "exa-test-key");
        Config.set(CareerFind.EXA_KEY, "0");
        CareerFind.companyList(store, s, cut, POSTED, x -> { }, () -> false, null, true);
        assertEquals(0, exaBodies.size(), "career_exa 0 turns it off");
        assertTrue(Career.logTail(store, s, 100).stream().anyMatch(l -> l.endsWith("Exa, the search by meaning, was not asked: career_exa is 0.")));

        Config.set(CareerFind.EXA_KEY, "2");
        CareerFind.companyList(store, s, cut, POSTED, x -> { }, () -> false, null, false);
        assertEquals(0, exaBodies.size(), "the nightly watch's list makes no search through Exa");
        CareerFind.companyList(store, s, cut, POSTED, x -> { }, () -> false, null, true);
        assertEquals(2, exaBodies.size(), "as many as the setting says, though three titles are searched by site");

        Config.set(ExaSearchTool.LIMIT, "3");   // two used this month: one is left
        CareerFind.companyList(store, s, CareerCut.kept(store, s), POSTED, x -> { }, () -> false, null, true);
        assertEquals(3, exaBodies.size(), "the month's limit stops the second");
        assertTrue(Career.logTail(store, s, 100).stream().anyMatch(l -> l.contains("Exa was asked 1 time") && l.contains("This month's 3 searches through Exa are used")), String.join("\n", Career.logTail(store, s, 100)));
        assertEquals(0.021, SearchTally.dollarsThisMonth(ExaSearchTool.TALLY), 1e-9, "each counted with its dollars as every Exa search is");
    }

    @Test void theSummaryTheRecordAndThePageSayHowManyOpeningsCameByEachWay(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = seeker(store, tmp);
        Config.set(ExaSearchTool.KEY, "exa-test-key");
        Config.set(CareerCompany.CARDS_KEY, "0");
        String summary = CareerFind.run(store, s, x -> { }, () -> false);
        assertTrue(summary.contains("2 of them came from Exa's search by meaning."), summary);
        Path runs = CareerFind.dir(store, s).resolve("runs");
        JsonNode run;
        try (var files = Files.list(runs)) { run = M.readTree(files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow().toFile()); }
        assertEquals(0, run.path("ways").path("search").asInt());
        assertEquals(2, run.path("ways").path("exa").asInt());
        assertTrue(CareerPage.body(store, s, true).contains("<span class=\"k\">(2 by Exa)</span>"), "the searches table says it");
        String stem;
        try (var files = Files.list(runs)) { stem = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow().getFileName().toString().replaceFirst("\\.json$", ""); }
        assertTrue(CareerOpeningsPage.body(store, s, true, stem).contains("what counted as a fit then (2 at employers found by Exa's search by meaning)"), "and so does the search's own page");
    }
}
