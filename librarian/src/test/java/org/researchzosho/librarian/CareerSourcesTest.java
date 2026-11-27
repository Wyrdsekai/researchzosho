package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Found;
import org.researchzosho.librarian.CareerSources.Hit;
import org.researchzosho.librarian.CareerSources.Opening;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The career search's readers, against stand-in pages shaped like each hiring system's own (read from the real ones, 2026-10-28). */
class CareerSourcesTest {

    static final ObjectMapper M = new ObjectMapper();
    final Map<String, String> pages = new HashMap<>(), posts = new HashMap<>();
    final Map<String, List<Hit>> searches = new HashMap<>();
    final List<String> asked = new ArrayList<>(), queries = new ArrayList<>();
    Function<String, String> usaJobsWas;

    @BeforeEach void standIns() {
        CareerSources.reset();
        CareerSources.POLITE_MS = 0;
        CareerSources.RAW = url -> { asked.add(url); return pages.get(url); };
        CareerSources.RAW_POST = (url, json) -> {
            asked.add("POST " + url);
            try { var j = M.readTree(json); return posts.get(url + "|" + j.path("searchText").asText() + "|" + j.path("offset").asInt()); } catch (Exception e) { return null; }
        };
        CareerSources.SEARCH = q -> { queries.add(q); return searches.getOrDefault(q, List.of()); };
        usaJobsWas = CareerSources.USAJOBS;
        CareerSources.RAW_PAGE = (url, headers) -> { asked.add(url); String b = pages.get(url); return b == null ? null : new CareerSources.Page(url, 200, b); };   // 0.8.4
    }

    @AfterEach void back() {
        CareerSources.RAW_PAGE = CareerSources::fetchPage;   // 0.8.4
        CareerSources.RAW = CareerSources::fetch; CareerSources.RAW_POST = CareerSources::post; CareerSources.SEARCH = CareerSources::search;
        CareerSources.USAJOBS = usaJobsWas; CareerSources.POLITE_MS = 1000; CareerSources.reset();
    }

    @Test void aFeedInTheSearchResultsUnderTheCompanysOwnNameIsReadAndJobBoardsNever() {
        searches.put("Toast careers jobs", List.of(new Hit("Toast jobs - Indeed", "https://www.indeed.com/cmp/Toast"), new Hit("Toast Careers", "https://boards.greenhouse.io/toast")));
        pages.put("https://boards-api.greenhouse.io/v1/boards/toast/jobs?content=true", """
                {"jobs": [
                  {"id": 1, "title": " Engineering Manager, Payments ", "location": {"name": "Boston, MA"}, "absolute_url": "https://careers.toasttab.com/jobs?gh_jid=1",
                   "first_published": "2026-10-20T10:00:00-04:00", "updated_at": "2026-10-21T10:00:00-04:00", "company_name": "Toast",
                   "content": "&lt;p&gt;Lead a team of &lt;b&gt;eight&lt;/b&gt;.&lt;/p&gt;&lt;ul&gt;&lt;li&gt;Payments&lt;/li&gt;&lt;/ul&gt;"},
                  {"id": 2, "title": "Staff Engineer", "location": {"name": "Remote, US"}, "absolute_url": "https://careers.toasttab.com/jobs?gh_jid=2",
                   "updated_at": "2026-09-01T10:00:00-04:00", "company_name": "Toast", "content": ""}]}""");
        Found f = CareerSources.openings("Toast", List.of());
        assertEquals("greenhouse:toast", f.feed().label());
        assertEquals("found in a search for its careers page", f.feed().how());
        assertEquals(2, f.openings().size());
        Opening a = f.openings().get(0);
        assertEquals("Engineering Manager, Payments", a.title());
        assertEquals("Boston, MA", a.location()); assertFalse(a.remote());
        assertEquals("2026-10-20", a.posted(), "the day it was first published, not the day it was last touched");
        assertEquals("Lead a team of eight.\nPayments", a.text());
        assertTrue(f.openings().get(1).remote()); assertEquals("2026-09-01", f.openings().get(1).posted());
        assertEquals(a.id(), CareerSources.openings("Toast", List.of()).openings().get(0).id(), "an opening's short name is the same on every read");
        assertTrue(asked.stream().noneMatch(u -> u.contains("indeed")), "a job board is never read");
    }

    @Test void aFeedUnderALongerNameIsSomeoneElsesUntilTheCompanysOwnCareersPageLinksIt() {
        searches.put("Ramp careers jobs", List.of(new Hit("Ramp Network - Jobs", "https://jobs.lever.co/rampnetwork"), new Hit("Careers | Ramp", "https://ramp.com/careers")));
        pages.put("https://ramp.com/careers", "<html><a href=\"https://jobs.ashbyhq.com/ramp\">Open roles</a></html>");
        pages.put("https://api.ashbyhq.com/posting-api/job-board/ramp?includeCompensation=true", """
                {"jobs": [
                  {"title": "Director of Engineering", "location": "New York, NY", "isRemote": false, "workplaceType": "Hybrid", "jobUrl": "https://jobs.ashbyhq.com/ramp/1",
                   "publishedAt": "2026-10-25T00:00:00.000+00:00", "descriptionPlain": "Build the platform.", "isListed": true,
                   "compensation": {"compensationTierSummary": "$250K – $300K"}},
                  {"title": "Not listed", "isListed": false, "location": "NY", "jobUrl": "https://jobs.ashbyhq.com/ramp/2"}]}""");
        pages.put("https://api.lever.co/v0/postings/rampnetwork?mode=json", "[{\"text\": \"Should never be read\", \"hostedUrl\": \"https://jobs.lever.co/rampnetwork/1\"}]");
        Found f = CareerSources.openings("Ramp", List.of());
        assertEquals("ashby:ramp", f.feed().label());
        assertTrue(f.feed().how().startsWith("linked from its careers page"), f.feed().how());
        assertEquals(1, f.openings().size(), "an opening the board does not list is not read");
        assertEquals("$250K – $300K", f.openings().get(0).pay());
        assertEquals("2026-10-25", f.openings().get(0).posted());
        assertFalse(asked.contains("https://api.lever.co/v0/postings/rampnetwork?mode=json"));
    }

    @Test void workdayIsSearchedForTheTitlesWantedAndAPostingsWordsReadWhenNeeded() {
        searches.put("Acme careers jobs", List.of(new Hit("Careers at Acme", "https://www.acme.com/careers")));
        pages.put("https://www.acme.com/careers", "<a href=\"https://acme.wd1.myworkdayjobs.com/en-US/AcmeCareers\">Search jobs</a>");
        String api = "https://acme.wd1.myworkdayjobs.com/wday/cxs/acme/AcmeCareers";
        posts.put(api + "/jobs|director|0", """
                {"total": 2, "jobPostings": [
                  {"title": "Director, Platform Engineering", "externalPath": "/job/Boston-MA/Director-Platform_R1", "locationsText": "Boston, MA", "postedOn": "Posted 3 Days Ago"},
                  {"title": "Director of Data", "externalPath": "/job/Remote-US/Director-Data_R2", "locationsText": "Remote - US", "postedOn": "Posted 30+ Days Ago"}]}""");
        posts.put(api + "/jobs|vp|0", "{\"total\": 0, \"jobPostings\": []}");
        pages.put(api + "/job/Boston-MA/Director-Platform_R1", "{\"jobPostingInfo\": {\"jobDescription\": \"<p>Own the platform.</p>\", \"remoteType\": \"Hybrid\"}}");
        Found f = CareerSources.openings("Acme", List.of("director", "vp"));
        assertEquals("workday:acme/wd1/AcmeCareers", f.feed().label());
        assertEquals(2, f.openings().size());
        Opening d = f.openings().get(0);
        assertEquals("https://acme.wd1.myworkdayjobs.com/AcmeCareers/job/Boston-MA/Director-Platform_R1", d.url());
        assertEquals(LocalDate.now().minusDays(3).toString(), d.posted());
        assertEquals("", f.openings().get(1).posted());
        assertTrue(f.openings().get(1).postedNote().startsWith("more than 30 days before "), f.openings().get(1).postedNote());
        assertTrue(f.openings().get(1).remote());
        assertTrue(f.note().endsWith("the titles you want: director, vp."), f.note());
        assertEquals("", d.text(), "the list gives no words");
        assertEquals("Own the platform.\nWorkplace: Hybrid", CareerSources.detail(d));
    }

    @Test void robotsRulesAreKeptAndAPageTheyCloseIsNeverRead() {
        CareerSources.Robots r = CareerSources.Robots.parse("""
                User-agent: Googlebot
                Disallow:

                User-agent: *
                Disallow: /careers
                Allow: /careers/open$
                Crawl-delay: 2
                """);
        assertFalse(r.allows("/careers/jobs")); assertTrue(r.allows("/careers/open")); assertFalse(r.allows("/careers/open/1")); assertTrue(r.allows("/about"));
        assertEquals(2000, r.delayMs());
        CareerSources.Robots ours = CareerSources.Robots.parse("User-agent: *\nDisallow: /\n\nUser-agent: ResearchZosho\nAllow: /jobs\nDisallow: /\n");
        assertTrue(ours.allows("/jobs/1"), "the group that names the library is the one it keeps"); assertFalse(ours.allows("/team"));
        assertTrue(CareerSources.Robots.parse("<html>a page, not robots rules</html>").allows("/anything"));
        assertTrue(CareerSources.Robots.parse(null).allows("/"));

        searches.put("Closed careers jobs", List.of(new Hit("Closed - Careers", "https://closed.example/careers")));
        pages.put("https://closed.example/robots.txt", "User-agent: *\nDisallow: /careers\n");
        pages.put("https://closed.example/careers", "<a href=\"https://boards.greenhouse.io/closed\">jobs</a>");
        Found f = CareerSources.openings("Closed", List.of());
        assertNull(f.feed());
        assertFalse(asked.contains("https://closed.example/careers"), "a page its robots rules close is never read");
        assertTrue(f.note().startsWith("no hiring feed or job list turned up"), f.note());
    }

    @Test void aCareersPagesJobDataAndItsJobLinksAreReadWhenItHasNoFeed() {
        String html = """
                <html><script type="application/ld+json">{"@context": "https://schema.org", "@graph": [{"@type": "JobPosting", "title": "Head of Engineering",
                 "datePosted": "2026-10-01", "hiringOrganization": {"name": "Widget Co"},
                 "jobLocation": {"address": {"addressLocality": "Cambridge", "addressRegion": "MA", "addressCountry": "US"}},
                 "description": "<p>Lead us.</p>", "url": "https://widget.example/jobs/head",
                 "baseSalary": {"currency": "USD", "value": {"minValue": 200000, "maxValue": 250000, "unitText": "YEAR"}}}]}</script></html>""";
        List<Opening> jp = CareerSources.jobPostings(html, "https://widget.example/careers", "Widget");
        assertEquals(1, jp.size());
        assertEquals("Cambridge, MA, US", jp.get(0).location()); assertEquals("Widget Co", jp.get(0).company());
        assertEquals("200000 to 250000 a year USD", jp.get(0).pay()); assertEquals("Lead us.", jp.get(0).text()); assertEquals("2026-10-01", jp.get(0).posted());
        String links = """
                <a href="/careers/jobs">View all jobs</a> <a href="/careers/jobs/123-vp-eng">VP of Engineering</a>
                <a href="https://widget.example/jobs/456">Senior Product Designer</a> <a href="/careers/benefits">Benefits</a> <a href="/jobs/789">Life at Widget</a>
                <a href="https://other.example/jobs/1">Engineer Elsewhere</a> <a href="/careers/jobs/999">Engineering (12 jobs)</a>""";
        List<Opening> ls = CareerSources.jobLinks(links, "https://widget.example/careers", "Widget");
        assertEquals(List.of("VP of Engineering", "Senior Product Designer"), ls.stream().map(Opening::title).toList());
        assertEquals("https://widget.example/careers/jobs/123-vp-eng", ls.get(0).url());
        assertEquals(ls.get(0).url(), ls.get(0).detail(), "its words are read from its own page when needed");

        searches.put("Widget careers jobs", List.of(new Hit("Widget careers", "https://widget.example/careers")));
        pages.put("https://widget.example/careers", links);
        assertNull(CareerSources.openings("Widget", List.of()).feed(), "two job links make a landing page with a featured role or two, not the company's list");
        CareerSources.reset();
        pages.put("https://widget.example/careers", links + "<a href=\"/careers/jobs/124\">Staff Data Engineer</a>");
        Found f = CareerSources.openings("Widget", List.of());
        assertEquals("links", f.feed().kind()); assertEquals("the job links on its careers page", f.feed().system());
        assertEquals(3, f.openings().size(), "three links are the company's list");
    }

    @Test void byNameOnlyWhenTheFeedGivesTheCompanysName() {
        pages.put("https://boards-api.greenhouse.io/v1/boards/klaviyo", "{\"name\": \"Klaviyo\", \"content\": \"\"}");
        pages.put("https://boards-api.greenhouse.io/v1/boards/klaviyo/jobs?content=true",
                "{\"jobs\": [{\"title\": \"VP, Engineering\", \"location\": {\"name\": \"Boston, MA\"}, \"absolute_url\": \"https://www.klaviyo.com/careers/jobs/1\", \"updated_at\": \"2026-10-02T00:00:00Z\"}]}");
        Found k = CareerSources.openings("Klaviyo", List.of());
        assertEquals("greenhouse:klaviyo", k.feed().label());
        assertTrue(k.feed().how().startsWith("found by its name; the board calls itself Klaviyo"), k.feed().how());
        assertEquals("Klaviyo", k.openings().get(0).company());
        pages.put("https://boards-api.greenhouse.io/v1/boards/ramp", "{\"name\": \"Ramp Network\"}");
        assertNull(CareerSources.byName("Ramp", List.of()), "a board that calls itself by another name is someone else's");
        Found none = CareerSources.openings("Nowhere", List.of());
        assertNull(none.feed()); assertEquals("the search found no careers page for it", none.note());
    }

    @Test void aBoardFoundByNameIsUsedOnlyWhenItHasOpeningsAndNamesItselfAndItBeatsAFewFeaturedLinks() {
        // Databricks, 2026-10-28: its careers page carried seven featured links, and its Greenhouse board has hundreds of openings
        searches.put("Datalake careers jobs", List.of(new Hit("Careers at Datalake", "https://www.datalake.example/company/careers")));
        pages.put("https://www.datalake.example/company/careers", """
                <a href="/company/careers/open-positions/job?gh_jid=1">Staff Engineer</a> <a href="/company/careers/open-positions/job?gh_jid=2">Product Manager, Data</a>
                <a href="/company/careers/open-positions/job?gh_jid=3">Solutions Architect</a>""");
        pages.put("https://boards-api.greenhouse.io/v1/boards/datalake", "{\"name\": \"Datalake\"}");
        StringBuilder jobs = new StringBuilder("{\"jobs\": [");
        for (int i = 0; i < 12; i++) jobs.append(i == 0 ? "" : ",").append("{\"title\": \"Engineer ").append(i).append("\", \"location\": {\"name\": \"Remote\"}, \"absolute_url\": \"https://datalake.example/j/").append(i).append("\"}");
        pages.put("https://boards-api.greenhouse.io/v1/boards/datalake/jobs?content=true", jobs + "]}");
        Found d = CareerSources.openings("Datalake", List.of());
        assertEquals("greenhouse:datalake", d.feed().label(), "the board by its own name before three featured links");
        assertEquals(12, d.openings().size());

        // Confluent, 2026-10-28: a Workable account under its name, with nothing on it; and an Ashby board whose postings name the company
        pages.put("https://apply.workable.com/api/v1/widget/accounts/streamco", "{\"name\": \"Streamco\", \"jobs\": []}");
        pages.put("https://api.ashbyhq.com/posting-api/job-board/streamco?includeCompensation=true", """
                {"jobs": [{"title": "Staff Engineer", "location": "Remote", "jobUrl": "https://jobs.ashbyhq.com/streamco/1", "descriptionPlain": "At Streamco we move data."},
                          {"title": "Engineering Manager", "location": "Austin", "jobUrl": "https://jobs.ashbyhq.com/streamco/2", "descriptionPlain": "Streamco is hiring a manager."}]}""");
        Found sc = CareerSources.openings("Streamco", List.of());
        assertEquals("ashby:streamco", sc.feed().label(), "an empty account is not the company's list");
        assertTrue(sc.feed().how().contains("most of its postings name the company"));
        pages.put("https://api.lever.co/v0/postings/otherco?mode=json", """
                [{"text": "Engineer", "hostedUrl": "https://jobs.lever.co/otherco/1", "descriptionPlain": "Join Someone Else Inc."},
                 {"text": "Designer", "hostedUrl": "https://jobs.lever.co/otherco/2", "descriptionPlain": "Someone Else Inc. is hiring."}]""");
        assertNull(CareerSources.openings("Otherco", List.of()).feed(), "a board under the name whose postings name another company is a namesake's");
    }

    @Test void companiesHiringForTheTitlesWantedAreFoundOnTheHiringSitesOwnPages() {
        // one search names three systems of the table (0.8.4): Ashby, Greenhouse by its domain (both its hosts) and Lever first, then Workable, Workday and Recruitee
        searches.put("\"Founding\" (\"Boston\" OR \"Remote\") (site:jobs.ashbyhq.com OR site:greenhouse.io OR site:jobs.lever.co)", List.of(
                new Hit("Executive Assistant - Founder & CEO @ Resolve", "https://jobs.ashbyhq.com/resolve/7d1c2b3a-1111"),
                new Hit("Founding Engineer @ Tiny Robots", "https://jobs.ashbyhq.com/tinyrobots/3f2a9c1b-0aa1"),
                new Hit("Founding Designer @ Tiny Robots", "https://jobs.ashbyhq.com/tinyrobots/9e8d7c6b-0bb2"),
                new Hit("Jobs at Xapo Bank", "https://job-boards.greenhouse.io/xapo61"),
                new Hit("Job Application for Founding Engineer at Kiln Labs", "https://job-boards.greenhouse.io/kilnlabs/jobs/77"),
                new Hit("Gearbox - Founding Engineer", "https://jobs.lever.co/gearbox/5c4b3a29-aa10")));
        searches.put("\"Founding\" (\"Boston\" OR \"Remote\") (site:apply.workable.com OR site:myworkdayjobs.com OR site:recruitee.com)", List.of(new Hit("Some blog post", "https://blog.example/founding")));
        pages.put("https://boards-api.greenhouse.io/v1/boards/kilnlabs", "{\"name\": \"Kiln Labs, Inc.\"}");
        List<CareerSources.Discovered> ds = CareerSources.discover(List.of("\"Founding\""), List.of("Boston", "Remote"), 10);
        assertEquals(List.of("Tiny Robots", "Kiln Labs, Inc.", "Gearbox"), ds.stream().map(CareerSources.Discovered::company).toList(),
                "one company once; a posting whose own title lacks the word, and a board's front page, are not hiring for it; a board's own name first");
        assertEquals("ashby:tinyrobots", ds.get(0).feed().label());
        assertEquals("found hiring: a search of Ashby's job pages for Founding in Boston or Remote gave Founding Engineer", ds.get(0).feed().how());
        assertEquals(2, CareerSources.discover(List.of("\"Founding\""), List.of("Boston", "Remote"), 2).size(), "at most as many as asked");
        assertEquals(List.of("Tiny Robots", "Kiln Labs, Inc.", "Gearbox"), CareerSources.discover(List.of("\"Founding\""), List.of("Boston", "Remote"), 10, t -> !t.contains("Designer")).stream().map(CareerSources.Discovered::company).toList(),
                "a posting the cut leaves out does not bring its company: Tiny Robots comes by its engineer, not its designer");
        CareerSources.reset();
        List<CareerSources.Discovered> designers = CareerSources.discover(List.of("\"Founding\""), List.of("Boston", "Remote"), 10, t -> !t.equals("Founding Engineer"));
        assertEquals(List.of("Tiny Robots"), designers.stream().map(CareerSources.Discovered::company).toList(), "with engineers cut, only the company hiring a founding designer");
        assertTrue(designers.get(0).feed().how().endsWith("gave Founding Designer"), designers.get(0).feed().how());
        assertEquals("Acme", CareerSources.nameFromHit(new Hit("Something odd", "https://jobs.ashbyhq.com/acme/1"), new Feed("ashby", "acme", "")));
    }

    @Test void leverRecruiteePersonioAndWorkableAreReadInTheirOwnShapes() {
        pages.put("https://api.lever.co/v0/postings/acme?mode=json", """
                [{"text": "Head of Platform", "categories": {"location": "Boston, MA", "commitment": "Full-time"}, "workplaceType": "remote", "createdAt": 1793000000000,
                  "hostedUrl": "https://jobs.lever.co/acme/1", "descriptionPlain": "Build it.", "lists": [{"text": "What you'll do", "content": "<li>Lead</li>"}],
                  "additionalPlain": "Benefits.", "salaryRange": {"currency": "USD", "interval": "per-year-salary", "min": 200000, "max": 240000}}]""");
        Opening l = CareerSources.read(new Feed("lever", "acme", "t"), "Acme", List.of()).openings().get(0);
        assertTrue(l.remote());
        assertEquals(Instant.ofEpochMilli(1793000000000L).toString().substring(0, 10), l.posted());
        assertEquals("USD 200000 to 240000 per year salary", l.pay());
        assertEquals("Build it.\nWhat you'll do\nLead\nBenefits.", l.text());

        pages.put("https://centre.recruitee.com/api/offers/", """
                {"offers": [{"title": "Engineering Manager", "location": "Paris, France", "remote": false, "careers_url": "https://centre.recruitee.com/o/em",
                  "published_at": "2026-10-28 13:57:19 UTC", "company_name": "CENTRE", "description": "<p>Manage.</p>", "requirements": "<p>Experience.</p>",
                  "salary": {"min": "70000", "max": "90000", "currency": "EUR", "period": "year"}}]}""");
        Opening rc = CareerSources.read(new Feed("recruitee", "centre", "t"), "Centre", List.of()).openings().get(0);
        assertEquals("2026-10-28", rc.posted()); assertEquals("Manage.\nExperience.", rc.text()); assertEquals("EUR 70000 to 90000 a year", rc.pay()); assertEquals("CENTRE", rc.company());

        pages.put("https://adac.jobs.personio.de/xml?language=en", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workzag-jobs><position><id>266</id><office>Berlin</office><additionalOffices><office>Remote</office></additionalOffices><department>Web</department>
                <name>Senior Product Manager (m/f/d)</name><jobDescriptions><jobDescription><name>Your role</name><value><![CDATA[<p>Lead the web.</p>]]></value></jobDescription></jobDescriptions>
                <createdAt>2026-10-02T09:00:00+00:00</createdAt></position></workzag-jobs>""");
        Opening pz = CareerSources.read(new Feed("personio", "adac.jobs.personio.de", "t"), "ADAC", List.of()).openings().get(0);
        assertEquals("Senior Product Manager (m/f/d)", pz.title());
        assertEquals("Berlin, Remote", pz.location()); assertTrue(pz.remote());
        assertEquals("https://adac.jobs.personio.de/job/266?language=en", pz.url()); assertEquals("Your role\nLead the web.", pz.text()); assertEquals("2026-10-02", pz.posted());

        pages.put("https://apply.workable.com/api/v1/widget/accounts/hf", """
                {"name": "Hugging Face", "jobs": [{"title": "ML Engineer", "city": "Paris", "country": "France", "telecommuting": true,
                  "url": "https://apply.workable.com/j/ABC", "published_on": "2026-05-29"}]}""");
        Opening wk = CareerSources.read(new Feed("workable", "hf", "t"), "HF", List.of()).openings().get(0);
        assertEquals("Hugging Face", wk.company()); assertTrue(wk.remote()); assertEquals("Paris, France", wk.location()); assertEquals("https://apply.workable.com/j/ABC", wk.detail());
        assertNull(CareerSources.read(new Feed("lever", "silent", "t"), "Silent", List.of()), "a feed that does not answer gives nothing, not an empty list");
    }

    @Test void theMonthsWhoIsHiringThreadIsReadOnePostAnOpening() {
        pages.put("https://hn.algolia.com/api/v1/search_by_date?tags=story,author_whoishiring&hitsPerPage=10", """
                {"hits": [{"title": "Ask HN: Who wants to be hired? (October 2026)", "objectID": "1"}, {"title": "Ask HN: Who is hiring? (October 2026)", "objectID": "2"}]}""");
        pages.put("https://hn.algolia.com/api/v1/items/2", """
                {"children": [
                  {"id": 21, "created_at": "2026-10-01T15:00:00.000Z", "text": "Formlabs | Engineering Manager | Somerville, MA | ONSITE | $200k<p>We make 3D printers.</p>"},
                  {"id": 22, "created_at": "2026-10-02T15:00:00.000Z", "text": "Acme (https://acme.example) | Staff Engineer | REMOTE (US)<p>Rust.</p>"},
                  {"id": 23, "created_at": "2026-10-02T16:00:00.000Z", "text": "Is anyone hiring juniors this month?"},
                  {"id": 24, "created_at": "2026-10-03T16:00:00.000Z", "text": null}]}""");
        List<Opening> hn = CareerSources.hackerNews();
        assertEquals(2, hn.size(), "a reply that is not a post, and a deleted one, are not openings");
        assertEquals("Formlabs", hn.get(0).company()); assertEquals("Engineering Manager", hn.get(0).title());
        assertEquals("Somerville, MA", hn.get(0).location()); assertFalse(hn.get(0).remote());
        assertEquals("hn:October 2026", hn.get(0).source()); assertEquals("https://news.ycombinator.com/item?id=21", hn.get(0).url()); assertEquals("2026-10-01", hn.get(0).posted());
        assertEquals("Formlabs | Engineering Manager | Somerville, MA | ONSITE | $200k\nWe make 3D printers.", hn.get(0).text());
        assertEquals("Acme", hn.get(1).company()); assertTrue(hn.get(1).remote()); assertEquals("", hn.get(1).location());
    }

    @Test void usaJobsOnlyWithAKeyOfThePersonsOwnAndEachOpeningOnce(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
        Config.invalidate();
        try {
            assertFalse(CareerSources.usaJobsReady());
            assertTrue(CareerSources.usaJobs(List.of("director")).isEmpty());
            Config.set("RESEARCHZOSHO_USAJOBS_KEY", "k");
            Config.set("RESEARCHZOSHO_USAJOBS_EMAIL", "seeker@example.org");
            List<String> urls = new ArrayList<>();
            CareerSources.USAJOBS = url -> {
                urls.add(url);
                return """
                        {"SearchResult": {"SearchResultItems": [{"MatchedObjectDescriptor": {"PositionTitle": "Director of Engineering", "PositionURI": "https://www.usajobs.gov/job/1",
                          "PositionLocationDisplay": "Washington, District of Columbia", "OrganizationName": "Digital Service", "DepartmentName": "Executive Office",
                          "PublicationStartDate": "2026-10-10T00:00:00.0000", "PositionRemuneration": [{"MinimumRange": "180000", "MaximumRange": "200000", "Description": "Per Year"}],
                          "UserArea": {"Details": {"JobSummary": "Lead.", "RemoteIndicator": true}}}}]}}""";
            };
            List<Opening> us = CareerSources.usaJobs(List.of("director", "head of"));
            assertEquals(2, urls.size()); assertTrue(urls.get(1).endsWith("&Keyword=head+of"), urls.get(1));
            assertEquals(1, us.size(), "the same opening from two searches is one");
            assertEquals("$180000 to $200000 per year", us.get(0).pay()); assertTrue(us.get(0).remote());
            assertEquals("2026-10-10", us.get(0).posted()); assertTrue(us.get(0).text().contains("Department: Executive Office"));
        } finally { System.setProperty("user.home", realHome); Config.invalidate(); }
    }

    /** An answer recorded once from a hiring system's real public posting or list (2026-11-26) and trimmed to what the readers need. */
    static String recorded(String name) {
        try (InputStream in = CareerSourcesTest.class.getResourceAsStream("/careersystems/" + name)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        catch (Exception e) { throw new IllegalStateException(name, e); }
    }

    @Test void theSystemsReadSince084AreKnownByTheirAddressesAndALookAlikeHostIsNot() {
        // the addresses the trial of 2026-11-26 met on systems the library could not read, each naming its employer's board
        List<Feed> fs = CareerSources.boardsIn("""
                https://jobs.smartrecruiters.com/Xplor/744000159338802-senior-software-engineer
                https://statsperform.teamtailor.com/jobs/8621163-payroll-administrator
                https://ats.rippling.com/audyence/jobs/ae2c5f14-7f62-414b-b679-89e7c77cf66a
                https://recruiting.paylocity.com/recruiting/jobs/All/a341e991-6fa2-40f7-b961-8a5b22284904/Gifthealth-Inc
                https://ravelin.pinpointhq.com/en/postings/520579c4-e328-46e7-bed5-37e422daf4fe
                https://proximo.bamboohr.com/careers/152
                https://wrshealth.applytojob.com/apply/YVXpmbuHmi/Security-Engineer
                https://jobs.eu.lever.co/ivcevidensia/1ac7283a-9b6c-4e87-8579-f25813346852
                https://job-boards.eu.greenhouse.io/feverup/jobs/4964072101
                https://expedia.wd108.myworkdayjobs.com/en-US/search/job/Senior-Product-Designer---Vrbo_R-110581-2
                https://cnoinc.wd5.myworkdayjobs.com/en-US/Careers/job/Lead-IT-Security-Architect_JR170493""", "t");
        assertEquals(List.of("greenhouse:feverup", "workday:expedia/wd108/search", "workday:cnoinc/wd5/Careers", "smartrecruiters:Xplor", "teamtailor:statsperform", "rippling:audyence",
                        "paylocity:a341e991-6fa2-40f7-b961-8a5b22284904", "pinpoint:ravelin", "bamboohr:proximo", "jazzhr:wrshealth", "levereu:ivcevidensia"),
                fs.stream().map(Feed::label).toList(), "a Workday site may be named search or Careers; in a posting's address it is the site");
        assertEquals("SmartRecruiters", fs.get(3).system()); assertEquals("Lever", fs.get(10).system());
        for (String u : List.of("https://jobs.smartrecruiters.com/Xplor/744000159338802-senior-software-engineer", "https://statsperform.teamtailor.com/jobs/8621163-payroll-administrator",
                "https://ats.rippling.com/audyence/jobs/ae2c5f14-7f62-414b-b679-89e7c77cf66a", "https://recruiting.paylocity.com/Recruiting/Jobs/Details/4506061",
                "https://ravelin.pinpointhq.com/en/postings/520579c4-e328-46e7-bed5-37e422daf4fe", "https://proximo.bamboohr.com/careers/152", "https://wrshealth.applytojob.com/apply/YVXpmbuHmi/Security-Engineer",
                "https://jobs.eu.lever.co/ivcevidensia/1ac7283a-9b6c-4e87-8579-f25813346852", "https://apply.workable.com/j/ECCDD755DA",
                "https://expedia.wd108.myworkdayjobs.com/en-US/search/job/Senior-Product-Designer---Vrbo_R-110581-2"))
            assertTrue(CareerSources.posting(u), "one posting: " + u);
        for (String u : List.of("https://careers.smartrecruiters.com/Xplor", "https://statsperform.teamtailor.com/jobs", "https://ats.rippling.com/audyence/jobs", "https://ravelin.pinpointhq.com/",
                "https://proximo.bamboohr.com/careers", "https://wrshealth.applytojob.com/apply", "https://www.smartrecruiters.com/blog/1234567-hiring"))
            assertFalse(CareerSources.posting(u), "a board's front page or the system's own site is no posting: " + u);
        // hosts that only look like a system's: its name before another domain, or inside another site's name
        for (String u : List.of("https://jobs.smartrecruiters.com.jobs-mirror.example/Xplor/744000159338802", "https://statsperform.teamtailor.com.example.net/jobs/8621163",
                "https://ats.rippling.com.example.org/audyence/jobs/ae2c5f14-7f62-414b-b679-89e7c77cf66a", "https://notpaylocity.example/recruiting/jobs/Details/4506061",
                "https://acme.wd5.myworkdayjobs.com.example.org/Careers/job/Engineer_R1", "https://proximo.bamboohr.com.example.org/careers/152")) {
            assertFalse(CareerSources.posting(u), u);
            assertTrue(CareerSources.boardsIn(u, "t").isEmpty(), u);
        }
    }

    @Test void theListsOfTheSystemsReadSince084AreReadInTheirOwnShapes() {
        pages.put("https://statsperform.teamtailor.com/jobs.rss", recorded("teamtailor-jobs.rss"));
        Found tt = CareerSources.read(new Feed("teamtailor", "statsperform", "t"), "Stats", List.of());
        assertEquals(3, tt.openings().size());
        Opening p = tt.openings().get(0);
        assertEquals("Stats Perform", p.company(), "the career site's own name"); assertEquals("Payroll Administrator", p.title());
        assertEquals("London, United Kingdom", p.location()); assertEquals("2026-10-27", p.posted()); assertFalse(p.remote(), "hybrid is not remote");
        assertEquals("https://statsperform.teamtailor.com/jobs/8621163-payroll-administrator", p.url()); assertTrue(p.text().startsWith("Overview\nStats Perform is the market leader"), p.text());

        pages.put("https://api.rippling.com/platform/api/ats/v1/board/arkeuscareer/jobs", recorded("rippling-board.json"));
        Found rp = CareerSources.read(new Feed("rippling", "arkeuscareer", "t"), "Arkeus", List.of());
        assertEquals(4, rp.openings().size(), "a posting listed once a place is one opening");
        assertTrue(rp.openings().get(0).remote()); assertEquals("Remote (United States)", rp.openings().get(0).location());
        assertEquals("Port Melbourne, Australia; Ormeau, Australia", rp.openings().get(2).location());

        pages.put("https://recruiting.paylocity.com/recruiting/jobs/All/a341e991-6fa2-40f7-b961-8a5b22284904", recorded("paylocity-list.html"));
        Found pl = CareerSources.read(new Feed("paylocity", "a341e991-6fa2-40f7-b961-8a5b22284904", "t"), "Gifthealth", List.of());
        assertEquals(4, pl.openings().size());
        assertEquals("Gifthealth Inc", pl.openings().get(0).company()); assertEquals("Machine Learning Operations Engineer", pl.openings().get(0).title());
        assertEquals("2026-11-24", pl.openings().get(0).posted()); assertTrue(pl.openings().get(0).remote()); assertFalse(pl.openings().get(1).remote());
        assertEquals("https://recruiting.paylocity.com/Recruiting/Jobs/Details/4690762", pl.openings().get(0).url());

        pages.put("https://ravelin.pinpointhq.com/postings.json", recorded("pinpoint-postings.json"));
        Found pp = CareerSources.read(new Feed("pinpoint", "ravelin", "t"), "Ravelin", List.of());
        assertEquals(List.of("Front End Engineer", "Senior Machine Learning Engineer", "Channel Partner Manager"), pp.openings().stream().map(Opening::title).toList());
        assertFalse(pp.openings().get(0).remote()); assertTrue(pp.openings().get(1).remote(), "fully remote");
        assertEquals("London", pp.openings().get(0).location(), "a hybrid post in London whose location record is named Remote is in London");
        assertTrue(pp.openings().get(1).text().contains("Key Responsibilities\nLead the design"), pp.openings().get(1).text());

        pages.put("https://proximo.bamboohr.com/careers/list", recorded("bamboohr-list.json"));
        Found bh = CareerSources.read(new Feed("bamboohr", "proximo", "t"), "Proximo", List.of());
        assertEquals(3, bh.openings().size()); assertEquals("Claims Advisor", bh.openings().get(1).title()); assertEquals("Chester", bh.openings().get(1).location());
        assertEquals("https://proximo.bamboohr.com/careers/152", bh.openings().get(1).url());

        pages.put("https://wrshealth.applytojob.com/apply", recorded("jazzhr-list.html"));
        Found jz = CareerSources.read(new Feed("jazzhr", "wrshealth", "t"), "WRS", List.of());
        assertEquals(3, jz.openings().size(), "two postings with one title are two openings");
        assertEquals("WRS Health", jz.openings().get(0).company()); assertEquals("Security Engineer", jz.openings().get(0).title()); assertTrue(jz.openings().get(0).remote());
        assertEquals("Goshen, NY", jz.openings().get(1).location());

        pages.put("https://careers.smartrecruiters.com/Xplor", recorded("smartrecruiters-careers.html"));
        pages.put("https://careers.smartrecruiters.com/Xplor/api/groups?page=1", recorded("smartrecruiters-careers-page1.html"));
        Found sr = CareerSources.read(new Feed("smartrecruiters", "Xplor", "t"), "Xplor", List.of());
        assertEquals(11, sr.openings().size(), "the first page's ten and the second page's one");
        Opening remote = sr.openings().stream().filter(o -> o.location().equals("Remote, OR")).findFirst().orElseThrow();
        assertTrue(remote.remote()); assertEquals("Senior Software Engineer", remote.title()); assertEquals("Xplor", remote.company());
        assertEquals("Senior IT Support Engineer", sr.openings().get(10).title());

        pages.put("https://api.eu.lever.co/v0/postings/ivcevidensia?mode=json", "[" + recorded("levereu-posting.json") + "]");
        Found lv = CareerSources.read(new Feed("levereu", "ivcevidensia", "t"), "IVC Evidensia", List.of());
        assertEquals("3PL & Carrier Manager", lv.openings().get(0).title()); assertEquals("Keynsham, Bristol, England", lv.openings().get(0).location());
        assertFalse(asked.contains("https://api.lever.co/v0/postings/ivcevidensia?mode=json"), "a European board is read on Lever's European host");

        pages.put("https://boards-api.greenhouse.io/v1/boards/feverup/jobs?content=true", recorded("greenhouse-eu-board.json"));
        List<Feed> eu = CareerSources.boardsIn("https://job-boards.eu.greenhouse.io/feverup/jobs/4964072101", "t");
        assertEquals(1, eu.size(), "a board on Greenhouse's European host");
        assertEquals("Senior Hardware Engineer", CareerSources.read(eu.get(0), "Fever", List.of()).openings().get(0).title(), "Greenhouse serves its European boards through the same service");
    }

    @Test void aRemotePostingOpenOnlyToSomeCountriesSaysWhichAndIsNotRemoteFromAnywhere() {
        // WRS Health's JazzHR posting (2026-11-26): remote, applicants from PH; Ravelin's Pinpoint posting: London, remote from the United Kingdom
        Opening ph = CareerSources.jobPostings(recorded("jazzhr-posting.html"), "https://wrshealth.applytojob.com/apply/YVXpmbuHmi/Security-Engineer", "WRS Health").get(0);
        assertTrue(ph.remote()); assertEquals("Remote, Philippines", ph.location());
        Opening uk = CareerSources.jobPostings(recorded("pinpoint-posting.html"), "https://ravelin.pinpointhq.com/en/postings/520579c4-e328-46e7-bed5-37e422daf4fe", "Ravelin").get(0);
        assertEquals("London, London, GB; Remote, United Kingdom", uk.location());
        CareerCut.Cut fromUk = new CareerCut.Cut(List.of("Engineer"), List.of(), List.of(), List.of(), List.of(), "only", List.of("United Kingdom", "UK"), List.of(), List.of(), 0, 0, "you", "", "");
        assertFalse(CareerCut.judge(fromUk, ph, List.of()).passes(), "a seat open only to the Philippines is not one to work remotely from the UK");
        assertTrue(CareerCut.judge(fromUk, uk, List.of()).passes());
    }

    @Test void aPostingIsStillListedByItsOwnIdNotByAnotherPostingWithTheSameTitle() {
        // a board that lists other postings under the same title: JazzHR lists two "ABA Therapist" postings, and a third one taken down is not listed by theirs
        pages.put("https://wrshealth.applytojob.com/apply", recorded("jazzhr-list.html"));
        assertFalse(CareerSources.stillListed(new CareerSources.Discovered("WRS Health", new Feed("jazzhr", "wrshealth", "t"), "ABA Therapist", "https://wrshealth.applytojob.com/apply/ZZZZ999999/ABA-Therapist"), List.of()));
        assertTrue(CareerSources.stillListed(new CareerSources.Discovered("WRS Health", new Feed("jazzhr", "wrshealth", "t"), "ABA Therapist", "https://wrshealth.applytojob.com/apply/v2GgMRLFpS/ABA-Therapist"), List.of()));
        // the same on a system read before 0.8.4: a Greenhouse posting whose number the board no longer lists
        pages.put("https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true", """
                {"jobs": [{"id": 222, "title": "Staff Engineer", "location": {"name": "Remote"}, "absolute_url": "https://job-boards.greenhouse.io/acme/jobs/222"}]}""");
        assertFalse(CareerSources.stillListed(new CareerSources.Discovered("Acme", new Feed("greenhouse", "acme", "t"), "Staff Engineer", "https://job-boards.greenhouse.io/acme/jobs/111"), List.of()));
        assertTrue(CareerSources.stillListed(new CareerSources.Discovered("Acme", new Feed("greenhouse", "acme", "t"), "Staff Engineer", "https://job-boards.greenhouse.io/acme/jobs/222"), List.of()));
        assertTrue(CareerSources.stillListed(new CareerSources.Discovered("Acme", new Feed("greenhouse", "acme", "t"), "Staff Engineer", "https://acme.example/careers/staff"), List.of()),
                "an address with no id of the board's is matched by its title, as before");
    }

    @Test void theSearchBySiteNamesTheCommonestSystemsThreeAtATimeAndLeavesTheRestToExa() {
        // the bound (0.8.4): a title gets three searches, each naming three systems of the table, Greenhouse's two hosts as one domain, so
        // SmartRecruiters is the ninth; the systems after it are not named: Exa's includeDomains does (CareerExaPassTest), and their postings
        // are checked on their boards the same way (theSystemsReadSince084AreReachedByExaAndCheckedOnTheirOwnBoards there)
        searches.put("\"Payroll\" (site:apply.workable.com OR site:myworkdayjobs.com OR site:recruitee.com)", List.of(new Hit("Payroll Administrator - Stats Perform", "https://statsperform.teamtailor.com/jobs/8621163-payroll-administrator")));
        pages.put("https://statsperform.teamtailor.com/jobs.rss", recorded("teamtailor-jobs.rss"));
        List<CareerSources.Discovered> ds = CareerSources.discover(List.of("\"Payroll\""), List.of(), 5, t -> true, d -> CareerSources.stillListed(d, List.of()));
        assertEquals(List.of("\"Payroll\" (site:jobs.ashbyhq.com OR site:greenhouse.io OR site:jobs.lever.co)",
                "\"Payroll\" (site:apply.workable.com OR site:myworkdayjobs.com OR site:recruitee.com)",
                "\"Payroll\" (site:jobs.personio.de OR site:jobs.personio.com OR site:jobs.smartrecruiters.com)"), queries, "three searches, whatever the table holds");
        for (String host : List.of("teamtailor.com", "ats.rippling.com", "recruiting.paylocity.com", "pinpointhq.com", "applytojob.com", "bamboohr.com"))
            assertTrue(queries.stream().noneMatch(q -> q.contains(host)), "not searched by site: " + host);
        assertEquals(List.of("Stats Perform"), ds.stream().map(CareerSources.Discovered::company).toList(), "a posting a search turns up on a system read since 0.8.4 is taken all the same, its board read now and listing it");
        assertEquals("teamtailor:statsperform", ds.get(0).feed().label());
        assertTrue(ds.get(0).feed().how().startsWith("found hiring: a search of Teamtailor's job pages for Payroll"), ds.get(0).feed().how());
        assertTrue(CareerSources.siteHosts().containsAll(List.of("*.myworkdayjobs.com", "jobs.smartrecruiters.com", "*.teamtailor.com", "ats.rippling.com", "recruiting.paylocity.com", "*.pinpointhq.com", "*.applytojob.com", "*.bamboohr.com")),
                "every row's host goes to Exa, a \"*.\" host as Exa takes it: " + CareerSources.siteHosts());
    }

    @Test void theFeedAddressesAPageNamesAreFound() {
        List<Feed> fs = CareerSources.boardsIn("""
                <a href="https://boards.greenhouse.io/embed/job_board?for=toast">a</a> <a href="https://job-boards.greenhouse.io/toast/jobs/9">b</a>
                <a href="https://jobs.lever.co/palantir/abc">c</a> https://apply.workable.com/j/ABC https://apply.workable.com/huggingface/
                https://www.recruitee.com https://centreon.recruitee.com/o/x https://nvidia.wd5.myworkdayjobs.com/wday/cxs/nvidia/Site/jobs
                https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/1""", "t");
        assertEquals(List.of("greenhouse:toast", "lever:palantir", "workable:huggingface", "recruitee:centreon", "workday:nvidia/wd5/NVIDIAExternalCareerSite"),
                fs.stream().map(Feed::label).toList());
        assertTrue(CareerSources.tokenIs(new Feed("greenhouse", "toasttab", "t"), "Toast"), "a short tail on the name is the company's");
        assertFalse(CareerSources.tokenIs(new Feed("lever", "rampnetwork", "t"), "Ramp"));
        assertTrue(CareerSources.tokenIs(new Feed("ashby", "notion", "t"), "Notion Labs, Inc."));
    }
}
