package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.CareerSources.Discovered;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Found;
import org.researchzosho.librarian.CareerSources.Opening;
import org.researchzosho.librarian.CareerSources.Page;
import org.researchzosho.librarian.CareerSources.Posting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The hiring systems read since 0.8.4, one posting read from its own address, gone postings and the robots rules, against answers
 * recorded once from the systems' real public postings on 2026-11-26 and trimmed (CareerSourcesTest reads their lists).
 */
class CareerSystemsTest {

    static final ObjectMapper M = new ObjectMapper();
    final Map<String, String> pages = new HashMap<>(), posts = new HashMap<>();
    final Map<String, Page> answers = new HashMap<>();
    final List<String> asked = new ArrayList<>();
    final List<Map<String, String>> headers = new ArrayList<>();

    @BeforeEach void standIns() {
        CareerSources.reset();
        CareerSources.POLITE_MS = 0;
        CareerSources.RAW = url -> { asked.add(url); return pages.get(url); };
        CareerSources.RAW_PAGE = (url, h) -> {
            asked.add(url); headers.add(h);
            if (answers.containsKey(url)) return answers.get(url);
            String b = pages.get(url);
            return b == null ? new Page(url, 404, "") : new Page(url, 200, b);
        };
        CareerSources.RAW_POST = (url, json) -> {
            asked.add("POST " + url);
            try { var j = M.readTree(json); return posts.get(url + "|" + j.path("searchText").asText() + "|" + j.path("offset").asInt()); } catch (Exception e) { return null; }
        };
        CareerSources.SEARCH = q -> List.of();
    }

    @AfterEach void back() {
        CareerSources.RAW = CareerSources::fetch; CareerSources.RAW_PAGE = CareerSources::fetchPage; CareerSources.RAW_POST = CareerSources::post; CareerSources.SEARCH = CareerSources::search;
        CareerSources.POLITE_MS = 1000; CareerSources.reset();
    }

    static String recorded(String name) { return CareerSourcesTest.recorded(name); }

    /** A cut for a seeker in Columbus, Ohio, on site: titles with Engineer, Nurse or Manager. */
    static CareerCut.Cut columbus() {
        return new CareerCut.Cut(List.of("Engineer", "Nurse", "Manager"), List.of(), List.of(), List.of(), List.of("Columbus", "OH", "Ohio"), "no", List.of(), List.of(), List.of(), 0, 0, "you", "", "");
    }

    @Test void aWorkdayRowInSeveralPlacesIsGivenThemFromItsRecordSoAPlaceCutHoldsIt() {
        // Relativity's Workday site (2026-11-26): "8 Locations" names none; its record says Kraków and seven Polish cities
        String api = "https://kcura.wd1.myworkdayjobs.com/wday/cxs/kcura/External_Career_Site";
        posts.put(api + "/jobs|Engineer|0", recorded("workday-list-several-places.json"));
        pages.put(api + "/job/Krakw/Staff-Software-Engineer---Engineering-Platform_26-0487-1", recorded("workday-record-several-places.json"));
        answers.put(api + "/job/Illinois/Software-Engineer_26-0246", null);   // its record does not answer this time
        Found f = CareerSources.read(new Feed("workday", "kcura/wd1/External_Career_Site", "t"), "Relativity", List.of("Engineer"));
        Opening krakow = f.openings().stream().filter(o -> o.title().startsWith("Staff Full Stack")).findFirst().orElseThrow();
        assertEquals("Kraków; Katowice; Bydgoszcz; Bialystok; Wroclaw; Warsaw; Łódź; Poznan", krakow.location());
        assertFalse(krakow.remote(), "a remoteType that is a department's name says nothing of remote work");
        assertEquals("Illinois (+22)", f.openings().stream().filter(o -> o.title().equals("Software Engineer")).findFirst().orElseThrow().location(),
                "a row whose record did not answer shows its first place, as its address spells it, and how many more");
        assertEquals("Illinois", f.openings().stream().filter(o -> o.title().equals("Principal Engineer")).findFirst().orElseThrow().location());
        assertTrue(f.note().contains("2 of its openings are in several places, and the library read the places of 1"), f.note());
        assertEquals(2, asked.stream().filter(u -> u.startsWith(api + "/job/")).count(), "one record a row in several places, none for a row in one");
        CareerSources.reset();
        answers.put(api + "/job/Illinois/Software-Engineer_26-0246", new Page("", 403, recorded("workday-refused-429.html")));
        Found refused = CareerSources.read(new Feed("workday", "kcura/wd1/External_Career_Site", "t"), "Relativity", List.of("Engineer"));
        assertEquals("Illinois (+22)", refused.openings().stream().filter(o -> o.title().equals("Software Engineer")).findFirst().orElseThrow().location(), "a refused record: the row as the list gave it, never dropped (0.8.4)");
        CareerSources.reset();
        answers.put(api + "/job/Illinois/Software-Engineer_26-0246", new Page("", 403, recorded("workday-record-gone-s22.json")));
        assertTrue(CareerSources.read(new Feed("workday", "kcura/wd1/External_Career_Site", "t"), "Relativity", List.of("Engineer")).openings().stream().noneMatch(o -> o.title().equals("Software Engineer")),
                "Workday's own word that it was taken down: the row goes");
        // the measured defect: a Columbus seeker was shown this seat, its place empty
        CareerCut.Verdict v = CareerCut.judge(columbus(), krakow, List.of());
        assertFalse(v.passes(), "a seat in Kraków and Poland is not in Columbus");
        // a row in several places whose title holds no word wanted is not asked about
        asked.clear();
        CareerSources.reset();
        posts.put(api + "/jobs|Designer|0", recorded("workday-list-several-places.json"));
        CareerSources.read(new Feed("workday", "kcura/wd1/External_Career_Site", "t"), "Relativity", List.of("Designer"));
        assertTrue(asked.stream().noneMatch(u -> u.startsWith(api + "/job/")), asked.toString());
    }

    @Test void aRowPastTheListIsReadOnlyWhenTheCutCanShowItAndASearchHasABound() {
        // seeker 34's run of 2026-11-26: 83 Workday records read for rows in several places, 2 for a row its cut lets through (0.8.4)
        String api = "https://kcura.wd1.myworkdayjobs.com/wday/cxs/kcura/External_Career_Site";
        posts.put(api + "/jobs|Engineer|0", recorded("workday-list-several-places.json"));
        pages.put(api + "/job/Krakw/Staff-Software-Engineer---Engineering-Platform_26-0487-1", recorded("workday-record-several-places.json"));
        CareerCut.Cut security = new CareerCut.Cut(List.of("Engineer"), List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), 0, 0, "library", "", "", List.of(), 0,
                List.of("Staff Full Stack Software Engineer"), List.of());
        Found f = CareerSources.wanting(CareerFind.wanted(security, List.of()), () -> CareerSources.read(new Feed("workday", "kcura/wd1/External_Career_Site", "t"), "Relativity", List.of("Engineer")));
        assertEquals(List.of(api + "/job/Krakw/Staff-Software-Engineer---Engineering-Platform_26-0487-1"), asked.stream().filter(u -> u.startsWith(api + "/job/")).toList(),
                "the record of the row the cut can show; not \"Software Engineer\", which holds the cut's single word but not its title");
        assertTrue(f.openings().stream().anyMatch(o -> o.location().startsWith("Kraków")));
        asked.clear(); CareerSources.reset();
        CareerSources.Wanted none = new CareerSources.Wanted(t -> true, p -> true, 0);
        CareerSources.wanting(none, () -> CareerSources.read(new Feed("workday", "kcura/wd1/External_Career_Site", "t"), "Relativity", List.of("Engineer")));
        assertTrue(asked.stream().noneMatch(u -> u.startsWith(api + "/job/")), "a search whose reads past the lists are spent reads none more: " + asked);
        asked.clear(); CareerSources.reset();
        CareerSources.read(new Feed("workday", "kcura/wd1/External_Career_Site", "t"), "Relativity", List.of("Engineer"));
        assertEquals(2, asked.stream().filter(u -> u.startsWith(api + "/job/")).count(), "outside a search, the reader's own words, as before");

        // SmartRecruiters: the row's place, where its list names one, is judged before its posting is read
        pages.put("https://careers.smartrecruiters.com/EVB", recorded("smartrecruiters-careers-by-location.html"));
        pages.put("https://jobs.smartrecruiters.com/EVB/744000157997570-registered-nurse", recorded("smartrecruiters-posting-by-location.json"));
        CareerCut.Cut nurseInOhio = new CareerCut.Cut(List.of("Nurse"), List.of(), List.of(), List.of(), List.of("Columbus", "Ohio"), "no", List.of(), List.of(), List.of(), 0, 0, "library", "", "",
                List.of(), 0, List.of("Registered Nurse"), List.of());
        asked.clear();
        CareerSources.wanting(CareerFind.wanted(nurseInOhio, List.of()), () -> CareerSources.read(new Feed("smartrecruiters", "EVB", "t"), "EVB", List.of("Nurse")));
        assertTrue(asked.stream().noneMatch(u -> u.startsWith("https://jobs.smartrecruiters.com/EVB/")), "the list puts it under Remote, USA: not in Columbus, on site, so not read: " + asked);
    }

    @Test void aBoardReadOnceInASearchServesTheFoundPostingsCheckAndTheEmployersRead() {
        pages.put("https://careers.smartrecruiters.com/EVB", recorded("smartrecruiters-careers-by-location.html"));
        CareerSources.Wanted w = new CareerSources.Wanted(t -> true, p -> true, 60);
        Feed evb = new Feed("smartrecruiters", "EVB", "t");
        CareerSources.wanting(w, () -> CareerSources.read(evb, "EVB", List.of("Nurse")));
        long lists = asked.stream().filter(u -> u.equals("https://careers.smartrecruiters.com/EVB")).count();
        Found again = CareerSources.wanting(w, () -> CareerSources.read(new Feed("smartrecruiters", "EVB", "found hiring"), "EVB", List.of("Nurse")));
        assertEquals(lists, asked.stream().filter(u -> u.equals("https://careers.smartrecruiters.com/EVB")).count(), "the second purpose reads nothing again");
        assertEquals("found hiring", again.feed().how(), "with the caller's own feed");
    }

    @Test void aWorkdayEmployerIsNamedAsItsPostingsOwnRecordNamesItAndTheRecordIsReadOnce() {
        String url = "https://kcura.wd1.myworkdayjobs.com/External_Career_Site/job/Krakw/Staff-Software-Engineer---Engineering-Platform_26-0487-1";
        String record = "https://kcura.wd1.myworkdayjobs.com/wday/cxs/kcura/External_Career_Site/job/Krakw/Staff-Software-Engineer---Engineering-Platform_26-0487-1";
        pages.put(record, recorded("workday-record-several-places.json"));
        CareerSources.Hit hit = new CareerSources.Hit("Staff Full Stack Software Engineer – Engineering Platform", url);
        assertEquals("Relativity Poland sp. z o.o.", CareerSources.nameFromHit(hit, CareerSources.boardsIn(url, "t").get(0)),
                "the hiring organisation its record names, without the code before it, not the site's name in its address (kcura)");
        Posting p = CareerSources.postingAt(url);
        assertEquals("Relativity Poland sp. z o.o.", p.opening().company());
        assertTrue(p.opening().location().startsWith("Kraków; Katowice"), p.opening().location());
        assertEquals(1, asked.stream().filter(u -> u.equals(record)).count(), "its record is read once for its employer and its posting");
        pages.put("https://procore.wd12.myworkdayjobs.com/wday/cxs/procore/Procore_External_Careers/job/Senior-Product-Designer_R0019104", recorded("workday-record-live.json"));
        assertEquals("Procore Technologies, Inc.", CareerSources.nameFromHit(new CareerSources.Hit("Senior Product Designer",
                "https://procore.wd12.myworkdayjobs.com/en-US/Procore_External_Careers/job/Senior-Product-Designer_R0019104"), new Feed("workday", "procore/wd12/Procore_External_Careers", "t")));
        assertEquals("Acme", CareerSources.nameFromHit(new CareerSources.Hit("Engineer", "https://acme.wd5.myworkdayjobs.com/Careers/job/Engineer_R1"), new Feed("workday", "acme/wd5/Careers", "t")),
                "a record that does not answer: the site's name in its address, as before");
    }

    @Test void aSmartRecruitersBoardGroupedByPlaceGivesTheGroupsPlaceAndEachPostingGivesItsDay() {
        // EVB's careers page (2026-11-26) groups by location; under each title it shows the kind of work ("Contract"), which was read as the place
        pages.put("https://careers.smartrecruiters.com/EVB", recorded("smartrecruiters-careers-by-location.html"));
        pages.put("https://jobs.smartrecruiters.com/EVB/744000157997570-registered-nurse", recorded("smartrecruiters-posting-by-location.json"));
        Found f = CareerSources.read(new Feed("smartrecruiters", "EVB", "t"), "EVB", List.of("Nurse"));
        Opening rn = f.openings().stream().filter(o -> o.title().equals("Registered Nurse")).findFirst().orElseThrow();
        assertEquals("Remote, USA, United States", rn.location()); assertTrue(rn.remote()); assertEquals("2026-10-27", rn.posted());
        assertTrue(rn.text().contains("Role Title: Registered Nurse"), rn.text());
        Opening tester = f.openings().stream().filter(o -> o.title().startsWith("AI Product Tester")).findFirst().orElseThrow();
        assertEquals("Remote, USA", tester.location(), "a row not read at its own address has its group's place, never the kind of work");
        assertEquals("Remote, REMOTE", f.openings().stream().filter(o -> o.title().equals("Forward Deployed Engineer")).findFirst().orElseThrow().location());
        assertTrue(f.openings().stream().noneMatch(o -> o.location().equals("Contract") || o.location().equals("Full-time")));
        assertEquals(1, asked.stream().filter(u -> u.startsWith("https://jobs.smartrecruiters.com/") && !u.endsWith("/robots.txt")).count(), "only the posting whose title holds a word wanted is read");
        // AEP Energy's page lists a posting whose own address answers 404: it is gone
        pages.put("https://careers.smartrecruiters.com/aepenergy", recorded("smartrecruiters-careers-gone-row.html"));
        Found aep = CareerSources.read(new Feed("smartrecruiters", "aepenergy", "t"), "AEP Energy", List.of("Manager"));
        assertTrue(aep.openings().isEmpty(), aep.openings().toString());
    }

    @Test void aHiringSystemsOwnTrainingBoardIsNoEmployer() {
        // the search's hit (2026-11-26) that brought Lever's training board as an employer to an Austin designer; its postings go back to 2019
        CareerSources.SEARCH = q -> q.contains("jobs.lever.co") ? List.of(new CareerSources.Hit("Lever Implementation Training Environment - UI/UX Designer",
                "https://jobs.lever.co/leverdemo-8/af0ca1a6-013b-447e-aaf1-2349ef23c353")) : List.of();
        pages.put("https://api.lever.co/v0/postings/leverdemo-8?mode=json", recorded("lever-demo-board.json"));
        List<Discovered> ds = CareerSources.discover(List.of("\"UI/UX Designer\""), List.of(), 5, t -> true, d -> CareerSources.stillListed(d, List.of("Designer")));
        assertTrue(ds.isEmpty(), ds.toString());
        Found demo = CareerSources.read(new Feed("lever", "leverdemo-8", "t"), "Lever Implementation Training Environment", List.of());
        assertTrue(demo.openings().isEmpty()); assertTrue(demo.note().contains("Lever's own demonstration board"), demo.note());
        assertFalse(asked.contains("https://api.lever.co/v0/postings/leverdemo-8?mode=json"), "not even read");
        // Lever's own careers board is an employer's, and so is a company whose name only begins with the system's
        pages.put("https://api.lever.co/v0/postings/lever?mode=json", recorded("lever-demo-board.json"));
        pages.put("https://api.lever.co/v0/postings/leverage?mode=json", recorded("lever-demo-board.json"));
        assertEquals(2, CareerSources.read(new Feed("lever", "lever", "t"), "Lever", List.of()).openings().size());
        assertEquals(2, CareerSources.read(new Feed("lever", "leverage", "t"), "Leverage Partners", List.of()).openings().size());
    }

    @Test void aPhenomPostingsDayIsNeverBeforeItsRecordWasMade() {
        // Battelle's careers site (2026-11-26) gives postedDate 2023-11-09 to a posting whose record was made 2026-11-10
        posts.put("https://jobs.battelle.org/widgets||0", recorded("phenom-stale-posted-day.json"));
        Found f = CareerPortals.read(new Feed("phenom", "jobs.battelle.org", "t"), "Battelle", List.of());
        assertEquals(List.of("2025-12-01", "2026-11-10"), f.openings().stream().map(Opening::posted).toList());
        assertEquals("Columbus, OH", f.openings().get(1).location());
    }

    @Test void anIcimsCardGivesThePlaceItShowsAboveTheTitle() {
        // Acadia Healthcare's iCIMS search page (2026-11-26): each card shows "Job Locations" above the job's title
        List<Opening> rows = CareerPortals.icimsRows(recorded("icims-cards.html"), "careers-acadiahealthcare", "Acadia Healthcare", "icims:careers-acadiahealthcare");
        assertEquals(List.of("Director of Quality and Accreditation", "RN Manager"), rows.stream().map(Opening::title).toList());
        assertEquals(List.of("US-IL-Chicago", "US-LA-Alexandria"), rows.stream().map(Opening::location).toList());
    }

    @Test void aWorkdayPostingIsReadFromWorkdaysOwnRecordAndAGoneOneSaysSo() {
        String live = "https://procore.wd12.myworkdayjobs.com/en-US/Procore_External_Careers/job/Senior-Product-Designer_R0019104";
        String record = "https://procore.wd12.myworkdayjobs.com/wday/cxs/procore/Procore_External_Careers/job/Senior-Product-Designer_R0019104";
        pages.put(record, recorded("workday-record-live.json"));
        Posting p = CareerSources.postingAt(live);
        assertEquals("workday:procore/wd12/Procore_External_Careers", p.feed().label());
        assertEquals(Boolean.TRUE, p.listed());
        Opening o = p.opening();
        assertEquals("Senior Product Designer", o.title()); assertEquals("Procore Technologies, Inc.", o.company()); assertEquals("US - Texas - Austin", o.location());
        assertFalse(o.remote()); assertEquals("2026-11-21", o.posted()); assertEquals(live, o.url());
        assertTrue(o.text().startsWith("What if your design work shaped how the hospitals"), o.text());
        assertEquals("application/json", headers.get(headers.size() - 1).get("Accept"));

        // Huntington's posting R0075778, taken down: Workday answers 403 S22; Expedia's R-110581-2, off its board: 404
        answers.put("https://huntington.wd12.myworkdayjobs.com/wday/cxs/huntington/HNBcareers/job/Cybersecurity-Engineer---Principal_R0075778", new Page("", 403, recorded("workday-record-gone-s22.json")));
        answers.put("https://expedia.wd108.myworkdayjobs.com/wday/cxs/expedia/search/job/Senior-Product-Designer---Vrbo_R-110581-2", new Page("", 404, recorded("workday-record-gone-404.json")));
        assertEquals(Boolean.FALSE, CareerSources.postingAt("https://huntington.wd12.myworkdayjobs.com/en-US/HNBcareers/job/Cybersecurity-Engineer---Principal_R0075778").listed());
        Posting gone = CareerSources.postingAt("https://expedia.wd108.myworkdayjobs.com/en-US/search/job/Senior-Product-Designer---Vrbo_R-110581-2");
        assertEquals(Boolean.FALSE, gone.listed()); assertNull(gone.opening());
        assertFalse(CareerSources.stillListed(new Discovered("Expedia", gone.feed(), "Senior Product Designer - Vrbo", "https://expedia.wd108.myworkdayjobs.com/en-US/search/job/Senior-Product-Designer---Vrbo_R-110581-2"), List.of("designer")),
                "the posting's own record decides, before the board is searched");
        assertTrue(asked.stream().noneMatch(u -> u.startsWith("POST")), "no search of the board was needed");

        // the board of a site named "search" is read like any other
        posts.put("https://expedia.wd108.myworkdayjobs.com/wday/cxs/expedia/search/jobs||0", recorded("workday-list-search-site.json"));
        answers.put("https://expedia.wd108.myworkdayjobs.com/wday/cxs/expedia/search/job/Austin-Domain-11---HomeAway/Sr-Manager--Software-Engineering_R-110856", null);   // the record of its row in two places does not answer
        Found f = CareerSources.read(gone.feed(), "Expedia", List.of());
        assertEquals(3, f.openings().size()); assertEquals("https://expedia.wd108.myworkdayjobs.com/search/job/UK---London/Mobile-Engineering-Intern---2027---London_R-110545-1", f.openings().get(0).url());
        assertEquals("Austin Domain 11 HomeAway (+1)", f.openings().get(2).location(), "\"2 Locations\" names no place; its address names the first");
    }

    @Test void onlyASystemsOwnGoneAnswerSaysAPostingIsGone() {
        // 2026-11-27: Workday refused this machine's bursts (403 with no S22, then 429) and the library took the postings for taken down (0.8.4)
        String post = "https://onedigital.wd5.myworkdayjobs.com/en-US/OneDigital/job/Senior-Data-Scientist---Remote_R6240";
        String record = "https://onedigital.wd5.myworkdayjobs.com/wday/cxs/onedigital/OneDigital/job/Senior-Data-Scientist---Remote_R6240";
        Object[][] cases = {{403, recorded("workday-record-gone-s22.json"), Boolean.FALSE, "403 S22: Workday's own word that it was taken down"},
                {404, recorded("workday-record-gone-404.json"), Boolean.FALSE, "404 HTTP_404: the same"},
                {403, "", null, "403 with nothing in it: a refusal"}, {403, recorded("workday-refused-429.html"), null, "403 with a page: a refusal"},
                {429, recorded("workday-refused-429.html"), null, "429"}, {422, recorded("workday-record-422.json"), null, "422 HTTP_422"},
                {404, "<html>Not Found</html>", null, "a 404 that is not Workday's answer"}};
        for (Object[] c : cases) {
            CareerSources.reset(); answers.clear();
            answers.put(record, new Page("", (Integer) c[0], (String) c[1]));
            assertEquals(c[2], CareerSources.postingAt(post).listed(), (String) c[3]);
        }
        // SmartRecruiters: its "gone too far" page is its not-found; its "Exception occurred" page (400) says nothing of the posting
        String sr = "https://jobs.smartrecruiters.com/Medable/743999735787019-data-scientist-remote";
        answers.put(sr, new Page(sr, 400, recorded("smartrecruiters-posting-400.html")));
        assertNull(CareerSources.postingAt(sr).listed(), "400: not known, its board decides");
        answers.put(sr, new Page(sr, 404, recorded("smartrecruiters-posting-404.html")));
        assertEquals(Boolean.FALSE, CareerSources.postingAt(sr).listed());
        // the posting says 400 and its board sends a reader to SmartRecruiters' front page: there is no list, so not known, never "not listed"
        answers.put(sr, new Page(sr, 400, recorded("smartrecruiters-posting-400.html")));
        answers.put("https://careers.smartrecruiters.com/Medable", new Page("https://jobs.smartrecruiters.com/", 200, "<html><head><title>SmartRecruiters Jobs</title></head></html>"));
        Discovered d = new Discovered("Medable", new Feed("smartrecruiters", "Medable", "t"), "Data Scientist - REMOTE", sr);
        CareerSources.ANY_LATER.set(false);
        assertFalse(CareerSources.stillListed(d, List.of()));
        assertTrue(CareerSources.ANY_LATER.get(), "carried unchecked");
        Found none = CareerSources.read(d.feed(), "Medable", List.of());
        assertEquals(CareerSystems.NO_CAREERS_PAGE, none.note());
        CareerSources.ANY_LATER.set(false);
        answers.put("https://careers.smartrecruiters.com/Medable", new Page("https://careers.smartrecruiters.com/Medable", 200, recorded("smartrecruiters-careers.html")));
        assertFalse(CareerSources.stillListed(d, List.of()));
        assertFalse(CareerSources.ANY_LATER.get(), "its own careers page, without the posting: not listed");
    }

    @Test void aRefusedRecordLeavesTheHitToItsBoardsList() {
        String hit = "https://aaaie.wd1.myworkdayjobs.com/en-US/CSAACareers/job/Principal-Enterprise-Architect---Remote_R7975";
        String record = "https://aaaie.wd1.myworkdayjobs.com/wday/cxs/aaaie/CSAACareers/job/Principal-Enterprise-Architect---Remote_R7975";
        String api = "https://aaaie.wd1.myworkdayjobs.com/wday/cxs/aaaie/CSAACareers";
        String listed = "{\"total\": 1, \"jobPostings\": [{\"title\": \"Principal Enterprise Architect - Remote\", \"externalPath\": \"/job/Arizona---Home-Teleworkers/Principal-Enterprise-Architect---Remote_R7975\", \"locationsText\": \"Arizona - Home Teleworkers\", \"postedOn\": \"Posted 3 Days Ago\"}]}";
        CareerSources.SEARCH = q -> List.of(new CareerSources.Hit("Enterprise AI Security Architect – Enterprise Architecture ...", hit));
        Predicate<Discovered> live = d -> CareerSources.stillListed(d, List.of("Architect"));
        answers.put(record, new Page(record, 403, recorded("workday-refused-429.html")));
        posts.put(api + "/jobs|Architect|0", listed);
        List<Discovered> ds = CareerSources.wanting(new CareerSources.Wanted(t -> true, p -> true, 60),
                () -> CareerSources.discover(List.of("\"Security Architect\""), List.of(), 5, t -> true, live, new int[2]));
        assertEquals(1, ds.size(), "on its board's list by its id: listed");
        assertTrue(ds.get(0).checked());
        assertEquals("Aaaie", ds.get(0).company(), "its record refused: the name its address gives, as when its record says later");
        CareerSources.reset(); posts.clear();
        posts.put(api + "/jobs|Architect|0", "{\"total\": 0, \"jobPostings\": []}");
        assertTrue(CareerSources.wanting(new CareerSources.Wanted(t -> true, p -> true, 60),
                () -> CareerSources.discover(List.of("\"Security Architect\""), List.of(), 5, t -> true, live, new int[2])).isEmpty(), "its board's list does not have it: not listed");
        CareerSources.reset(); posts.clear();   // the list does not answer either: not known, carried, never shown as checked
        List<Discovered> carried = CareerSources.wanting(new CareerSources.Wanted(t -> true, p -> true, 60),
                () -> CareerSources.discover(List.of("\"Security Architect\""), List.of(), 5, t -> true, live, new int[2]));
        assertEquals(1, carried.size());
        assertFalse(carried.get(0).checked());
        CareerSources.reset();
        answers.put(record, new Page(record, 403, recorded("workday-record-gone-s22.json")));
        assertTrue(CareerSources.wanting(new CareerSources.Wanted(t -> true, p -> true, 60),
                () -> CareerSources.discover(List.of("\"Security Architect\""), List.of(), 5, t -> true, live, new int[2])).isEmpty(), "Workday's own word that it was taken down: dropped");
    }

    @Test void aWorkdayPostingsAddressIsThePartBeforeItsApplyFlow() {
        // premierinc, cisco, sedgwick, ryan, lifespacecommunities (2026-11-27): a record asked by an apply page's address answered 406 or S21
        String record = "https://premierinc.wd1.myworkdayjobs.com/wday/cxs/premierinc/External_Professional/job/Remote/Senior-Data-Scientist_R0008456";
        pages.put(record, recorded("workday-record-live.json"));
        for (String tail : List.of("/apply", "/apply/applyManually", "/apply/useMyLastApplication", "/")) {
            String u = "https://premierinc.wd1.myworkdayjobs.com/en-US/External_Professional/job/Remote/Senior-Data-Scientist_R0008456" + tail;
            CareerSources.reset(); asked.clear();
            assertEquals(Boolean.TRUE, CareerSources.postingAt(u).listed(), tail);
            assertTrue(asked.contains(record), tail + ": " + asked);
            assertEquals("senior-data-scientist_r0008456", CareerSources.postingId(u), tail);
        }
        assertEquals("https://x.wd1.myworkdayjobs.com/S/job/Applied-Scientist_R1", CareerSystems.workdayPostingUrl("https://x.wd1.myworkdayjobs.com/S/job/Applied-Scientist_R1"),
                "a title that begins with Apply is no apply flow");
    }

    @Test void aSmartRecruitersPostingIsReadAtItsOwnAddressAndItsDataServiceIsNeverAsked() {
        String live = "https://jobs.smartrecruiters.com/Xplor/744000159338802-senior-software-engineer", expired = "https://jobs.smartrecruiters.com/xplor/744000152405629";
        pages.put(live, recorded("smartrecruiters-posting-live.json"));
        pages.put(expired, recorded("smartrecruiters-posting-expired.json"));
        Posting p = CareerSources.postingAt(live);
        assertEquals("smartrecruiters:Xplor", p.feed().label()); assertEquals(Boolean.TRUE, p.listed());
        assertEquals("Senior Software Engineer", p.opening().title()); assertEquals("Xplor", p.opening().company());
        assertEquals("97458 Remote, OR, United States", p.opening().location()); assertTrue(p.opening().remote()); assertEquals("2026-11-03", p.opening().posted());
        assertTrue(p.opening().text().startsWith("Job Description\nWe’re looking for a back end platform software engineer"), p.opening().text());
        assertEquals("application/json", headers.get(0).get("Accept"), "the posting's address answers JSON when JSON is asked");
        Posting x = CareerSources.postingAt(expired);
        assertEquals(Boolean.FALSE, x.listed(), "an expired posting: it takes no applications"); assertEquals("EMEA Payroll Specialist - 6 Month Fixed Term Contract", x.opening().title());
        assertFalse(CareerSources.stillListed(new Discovered("Xplor", x.feed(), x.opening().title(), expired), List.of()));
        assertEquals(Boolean.FALSE, CareerSources.postingAt("https://jobs.smartrecruiters.com/Xplor/744000000000001-taken-down").listed(), "a posting its address no longer answers for");
        // SmartRecruiters' data service lets only LinkedIn's reader in: the library reads the careers page and the postings, never it
        pages.put("https://api.smartrecruiters.com/robots.txt", recorded("robots-api.smartrecruiters.com.txt"));
        assertFalse(CareerSources.allowed("https://api.smartrecruiters.com/v1/companies/Xplor/postings"));
        assertTrue(asked.stream().noneMatch(u -> u.startsWith("https://api.smartrecruiters.com/v1")));
        // a careers page's opening gets its words from the posting's own answer
        Opening row = CareerSources.opening("Xplor", "Senior Software Engineer", "Remote, OR", true, live, "", "", "smartrecruiters:Xplor", "", live, "");
        assertTrue(CareerSources.detail(row).contains("Python and Django"), CareerSources.detail(row));
    }

    @Test void aTeamtailorPostingIsReadFromItsJobDataAndASiteWhoseRobotsRulesRefuseIsNeverRead() {
        String url = "https://statsperform.teamtailor.com/jobs/8621163-payroll-administrator";
        pages.put(url, recorded("teamtailor-posting.html"));
        pages.put("https://statsperform.teamtailor.com/robots.txt", recorded("robots-teamtailor-tenant.txt"));
        Posting p = CareerSources.postingAt(url);
        assertEquals("teamtailor:statsperform", p.feed().label()); assertEquals(Boolean.TRUE, p.listed());
        assertEquals("Payroll Administrator", p.opening().title()); assertEquals("Stats Perform", p.opening().company());
        assertEquals("London, GB", p.opening().location()); assertEquals("2026-10-27", p.opening().posted());
        // a posting Teamtailor took down answers 410 Gone (the trial's, 2026-11-26)
        answers.put("https://hireroo.teamtailor.com/jobs/8537154-senior-firmware-engineer", new Page("https://recruitment.hireroo.com/jobs/8537154-senior-firmware-engineer", 410, "<html>Gone</html>"));
        assertEquals(Boolean.FALSE, CareerSources.postingAt("https://hireroo.teamtailor.com/jobs/8537154-senior-firmware-engineer").listed());
        // a career site that moved to the employer's own domain, whose robots rules refuse every reader: its robots.txt is read there
        pages.put("https://erni.teamtailor.com/robots.txt", recorded("robots-teamtailor-own-domain.txt"));
        assertNull(CareerSources.postingAt("https://erni.teamtailor.com/jobs/8637537-embedded-firmware-engineer"));
        assertNull(CareerSources.read(new Feed("teamtailor", "erni", "t"), "ERNI", List.of()));
        assertTrue(asked.stream().noneMatch(u -> u.startsWith("https://erni.teamtailor.com/jobs")), asked.toString());
    }

    @Test void aRipplingPostingIsReadFromRipplingsBoardServiceAndAGoneOneAnswers404() {
        String url = "https://ats.rippling.com/audyence/jobs/ae2c5f14-7f62-414b-b679-89e7c77cf66a";
        pages.put("https://api.rippling.com/platform/api/ats/v1/board/audyence/jobs/ae2c5f14-7f62-414b-b679-89e7c77cf66a", recorded("rippling-job.json"));
        Posting p = CareerSources.postingAt(url);
        assertEquals("rippling:audyence", p.feed().label()); assertEquals(Boolean.TRUE, p.listed());
        assertEquals("Sr. Product Designer", p.opening().title()); assertEquals("Audyence", p.opening().company()); assertEquals("Austin, TX", p.opening().location());
        assertEquals("2026-09-29", p.opening().posted()); assertTrue(p.opening().text().contains("Role Overview"), p.opening().text());
        answers.put("https://api.rippling.com/platform/api/ats/v1/board/arkeuscareer/jobs/41dd90f5-9294-4c3e-97d1-0b81b6b97c2c", new Page("", 404, recorded("rippling-job-gone.json")));
        assertEquals(Boolean.FALSE, CareerSources.postingAt("https://ats.rippling.com/arkeuscareer/jobs/41dd90f5-9294-4c3e-97d1-0b81b6b97c2c").listed());
        // a host whose robots rules close the board service: nothing is read there
        pages.put("https://api.rippling.com/robots.txt", "User-agent: *\nDisallow: /platform/\n");
        CareerSources.reset();
        asked.clear();
        assertNull(CareerSources.postingAt(url));
        assertNull(CareerSources.read(new Feed("rippling", "audyence", "t"), "Audyence", List.of()));
        assertEquals(List.of("https://api.rippling.com/robots.txt"), asked);
    }

    @Test void aPaylocityPostingIsReadFromItsPageAndListedByItsEmployersList() {
        String url = "https://recruiting.paylocity.com/recruiting/jobs/Details/4506061/Gifthealth-Inc/Cloud-Security-Engineer";
        pages.put(url, recorded("paylocity-posting.html"));
        pages.put("https://recruiting.paylocity.com/recruiting/jobs/All/a341e991-6fa2-40f7-b961-8a5b22284904", recorded("paylocity-list.html"));
        Posting p = CareerSources.postingAt(url);
        assertEquals("paylocity:a341e991-6fa2-40f7-b961-8a5b22284904", p.feed().label(), "its page links its employer's list");
        assertEquals(Boolean.TRUE, p.listed());
        assertEquals("Cloud Security Engineer", p.opening().title()); assertEquals("Gifthealth Inc", p.opening().company()); assertEquals("Columbus, OH, US", p.opening().location());
        assertEquals("2026-09-15", p.opening().posted()); assertEquals("115000.0 to 150000.0 a year USD", p.opening().pay());
        assertEquals(List.of(p.feed()), CareerSources.feedsOf("https://recruiting.paylocity.com/recruiting/jobs/Details/4506061/Gifthealth-Inc/Cloud-Security-Engineer").stream()
                .map(f -> new Feed(f.kind(), f.token(), p.feed().how())).toList(), "a search hit's board, from the page its address leads to");
        // a posting its employer's list no longer carries is gone, though its page still opens
        String old = "https://recruiting.paylocity.com/recruiting/jobs/Details/4400001/Gifthealth-Inc/Data-Engineer";
        pages.put(old, recorded("paylocity-posting.html").replace("Cloud Security Engineer", "Data Engineer"));
        assertEquals(Boolean.FALSE, CareerSources.postingAt(old).listed());
    }

    @Test void aPinpointPostingIsReadFromItsJobDataAndAPostingItsRobotsRulesCloseIsNot() {
        pages.put("https://ravelin.pinpointhq.com/robots.txt", recorded("robots-pinpoint.txt"));
        String url = "https://ravelin.pinpointhq.com/en/postings/520579c4-e328-46e7-bed5-37e422daf4fe";
        pages.put(url, recorded("pinpoint-posting.html"));
        Posting p = CareerSources.postingAt(url);
        assertEquals("pinpoint:ravelin", p.feed().label()); assertEquals(Boolean.TRUE, p.listed());
        assertEquals("Senior Machine Learning Engineer", p.opening().title()); assertEquals("Ravelin Technology", p.opening().company());
        assertTrue(p.opening().remote(), "TELECOMMUTE"); assertEquals("2026-08-11", p.opening().posted());
        // Ravelin's robots rules close one posting by its address (2026-11-26): the library does not read it
        assertNull(CareerSources.postingAt("https://ravelin.pinpointhq.com/postings/ec294b3c-9481-4461-98b2-fe55b74d3ee4"));
        assertFalse(asked.contains("https://ravelin.pinpointhq.com/postings/ec294b3c-9481-4461-98b2-fe55b74d3ee4"));
        // a hit for a posting the postings feed no longer lists
        pages.put("https://ravelin.pinpointhq.com/postings.json", recorded("pinpoint-postings.json"));
        assertFalse(CareerSources.stillListed(new Discovered("Ravelin", new Feed("pinpoint", "ravelin", "t"), "Senior Machine Learning Engineer", "https://ravelin.pinpointhq.com/en/postings/00000000-1111-2222-3333-444444444444"), List.of()));
        assertTrue(CareerSources.stillListed(new Discovered("Ravelin", new Feed("pinpoint", "ravelin", "t"), "Senior Machine Learning Engineer", url), List.of()));
    }

    @Test void aBambooHrOpeningIsReadFromItsDetailAndAGoneOneIsNotFound() {
        pages.put("https://proximo.bamboohr.com/careers/152/detail", recorded("bamboohr-detail.json"));
        Posting p = CareerSources.postingAt("https://proximo.bamboohr.com/careers/152");
        assertEquals("bamboohr:proximo", p.feed().label()); assertEquals(Boolean.TRUE, p.listed());
        assertEquals("Claims Advisor", p.opening().title()); assertEquals("Chester, United Kingdom", p.opening().location()); assertEquals("2026-10-12", p.opening().posted());
        assertEquals("£27,285.00", p.opening().pay()); assertFalse(p.opening().remote(), "hybrid");
        answers.put("https://proximo.bamboohr.com/careers/155/detail", new Page("", 404, recorded("bamboohr-detail-gone.json")));
        assertEquals(Boolean.FALSE, CareerSources.postingAt("https://proximo.bamboohr.com/careers/155").listed());
        Opening row = CareerSources.opening("Proximo", "Claims Advisor", "Chester", false, "https://proximo.bamboohr.com/careers/152", "", "", "bamboohr:proximo", "", "https://proximo.bamboohr.com/careers/152/detail", "");
        assertTrue(CareerSources.detail(row).startsWith("Claims Advisor\nChester"), CareerSources.detail(row));
    }

    @Test void aJazzHrPostingIsReadFromItsJobDataAndAGoneOneAnswers410() {
        String url = "https://wrshealth.applytojob.com/apply/YVXpmbuHmi/Security-Engineer";
        pages.put(url, recorded("jazzhr-posting.html"));
        Posting p = CareerSources.postingAt(url);
        assertEquals("jazzhr:wrshealth", p.feed().label()); assertEquals(Boolean.TRUE, p.listed());
        assertEquals("Security Engineer", p.opening().title()); assertEquals("WRS Health", p.opening().company()); assertTrue(p.opening().remote());
        assertEquals("2026-11-17", p.opening().posted());
        answers.put("https://phoenixcybersecurity.applytojob.com/apply/nosHtFShzh/Cybersecurity-Engineer-JOB-ID-20260828", new Page("", 410, "<html><title>Cybersecurity Engineer - Phoenix Cyber - Career Page</title></html>"));
        assertEquals(Boolean.FALSE, CareerSources.postingAt("https://phoenixcybersecurity.applytojob.com/apply/nosHtFShzh/Cybersecurity-Engineer-JOB-ID-20260828").listed());
        pages.put(url.replace("YVXpmbuHmi", "ZZpast0001"), recorded("jazzhr-posting.html").replace("\"validThrough\": \"2027-02-15\"", "\"validThrough\": \"2026-01-15\""));
        assertEquals(Boolean.FALSE, CareerSources.postingAt(url.replace("YVXpmbuHmi", "ZZpast0001")).listed(), "its own data says it closed");
    }

    @Test void aLeverPostingInEuropeIsReadOnLeversEuropeanHost() {
        String url = "https://jobs.eu.lever.co/ivcevidensia/1ac7283a-9b6c-4e87-8579-f25813346852";
        pages.put("https://api.eu.lever.co/v0/postings/ivcevidensia/1ac7283a-9b6c-4e87-8579-f25813346852", recorded("levereu-posting.json"));
        Posting p = CareerSources.postingAt(url);
        assertEquals("levereu:ivcevidensia", p.feed().label()); assertEquals(Boolean.TRUE, p.listed());
        assertEquals("3PL & Carrier Manager", p.opening().title()); assertEquals("Keynsham, Bristol, England", p.opening().location());
        answers.put("https://api.eu.lever.co/v0/postings/ivcevidensia/6021eee4-cab3-4ba7-a09a-4acffe014422", new Page("", 404, recorded("levereu-posting-gone.json")));
        assertEquals(Boolean.FALSE, CareerSources.postingAt("https://jobs.eu.lever.co/ivcevidensia/6021eee4-cab3-4ba7-a09a-4acffe014422").listed());
    }

    @Test void workablesShortAddressIsReadThroughTheAccountItLeadsTo() {
        String url = "https://apply.workable.com/j/ECCDD755DA";
        answers.put(url, new Page("https://apply.workable.com/civica-uk-ltd-1/j/ECCDD755DA/", 200, "<html></html>"));
        pages.put("https://apply.workable.com/api/v1/widget/accounts/civica-uk-ltd-1", recorded("workable-widget.json"));
        Posting p = CareerSources.postingAt(url);
        assertEquals("workable:civica-uk-ltd-1", p.feed().label()); assertEquals(Boolean.TRUE, p.listed());
        assertEquals("NHS Payroll Controller", p.opening().title()); assertEquals("Civica", p.opening().company()); assertEquals("Birmingham, England, United Kingdom", p.opening().location());
        assertEquals("workable:civica-uk-ltd-1", CareerSources.feedsOf(url).get(0).label());
        assertEquals(Boolean.FALSE, CareerSources.postingAt("https://apply.workable.com/civica-uk-ltd-1/j/0000000000").listed(), "a code its account no longer lists");
    }

    @Test void anAddressOfNoSystemReadThisWayIsNotRead() {
        for (String u : List.of("https://jobs.example.org/careers/1", "https://job-boards.greenhouse.io/acme/jobs/1"))   // no system's, and one read through its board's list
            assertNull(CareerSources.postingAt(u), u);
        for (String u : List.of(   // hosts that only look like each system's: its name before another domain
                "https://acme.wd5.myworkdayjobs.com.example.org/Careers/job/Engineer_R1", "https://jobs.smartrecruiters.com.example.net/Xplor/744000159338802",
                "https://statsperform.teamtailor.com.example.net/jobs/8621163", "https://ats.rippling.com.example.org/audyence/jobs/ae2c5f14-7f62-414b-b679-89e7c77cf66a",
                "https://recruiting.paylocity.com.example.org/recruiting/jobs/Details/4506061", "https://ravelin.pinpointhq.com.example.org/en/postings/520579c4-e328-46e7-bed5-37e422daf4fe",
                "https://proximo.bamboohr.com.example.org/careers/152", "https://wrshealth.applytojob.com.example.net/apply/YVXpmbuHmi/Security-Engineer",
                "https://jobs.eu.lever.co.example.org/ivcevidensia/1ac7283a-9b6c-4e87-8579-f25813346852", "https://apply.workable.com.example.org/j/ECCDD755DA")) {
            assertNull(CareerSources.postingAt(u), u);
            assertFalse(CareerSources.posting(u), u);
            assertTrue(CareerSources.boardsIn(u, "t").isEmpty(), u);
        }
        assertTrue(asked.isEmpty(), asked.toString());
    }

    @Test void whereASystemsRobotsRulesRefuseEveryReaderNothingOfItIsRead() {
        // a posting's address and its employer's list on every system read since 0.8.4, each site's robots rules refusing everyone
        Map<String, Feed> each = new LinkedHashMap<>();
        each.put("https://procore.wd12.myworkdayjobs.com/en-US/Procore_External_Careers/job/Senior-Product-Designer_R0019104", new Feed("workday", "procore/wd12/Procore_External_Careers", "t"));
        each.put("https://jobs.smartrecruiters.com/Xplor/744000159338802-senior-software-engineer", new Feed("smartrecruiters", "Xplor", "t"));
        each.put("https://statsperform.teamtailor.com/jobs/8621163-payroll-administrator", new Feed("teamtailor", "statsperform", "t"));
        each.put("https://ats.rippling.com/audyence/jobs/ae2c5f14-7f62-414b-b679-89e7c77cf66a", new Feed("rippling", "audyence", "t"));
        each.put("https://recruiting.paylocity.com/recruiting/jobs/Details/4506061/Gifthealth-Inc/Cloud-Security-Engineer", new Feed("paylocity", "a341e991-6fa2-40f7-b961-8a5b22284904", "t"));
        each.put("https://ravelin.pinpointhq.com/en/postings/520579c4-e328-46e7-bed5-37e422daf4fe", new Feed("pinpoint", "ravelin", "t"));
        each.put("https://proximo.bamboohr.com/careers/152", new Feed("bamboohr", "proximo", "t"));
        each.put("https://wrshealth.applytojob.com/apply/YVXpmbuHmi/Security-Engineer", new Feed("jazzhr", "wrshealth", "t"));
        each.put("https://jobs.eu.lever.co/ivcevidensia/1ac7283a-9b6c-4e87-8579-f25813346852", new Feed("levereu", "ivcevidensia", "t"));
        each.put("https://apply.workable.com/j/ECCDD755DA", new Feed("workable", "civica-uk-ltd-1", "t"));
        CareerSources.RAW = url -> { asked.add(url); return url.endsWith("/robots.txt") ? "User-agent: *\nDisallow: /\n" : pages.get(url); };
        for (Map.Entry<String, Feed> e : each.entrySet()) {
            assertNull(CareerSources.postingAt(e.getKey()), e.getKey());
            assertNull(CareerSources.read(e.getValue(), "Acme", List.of("engineer")), e.getValue().label());
        }
        assertFalse(asked.isEmpty());
        assertTrue(asked.stream().allMatch(u -> u.endsWith("/robots.txt")), "only the robots rules were read: " + asked);
    }
}
