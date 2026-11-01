package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Hit;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A company's size is taken only when it is tied to the company's own website; a namesake's never passes for it. */
class CareerSizeTest {
    final Map<String, String> pages = new HashMap<>();
    final Map<String, List<Hit>> searches = new HashMap<>();

    @BeforeEach void standIns() {
        CareerSources.reset(); CareerSources.POLITE_MS = 0;
        CareerSources.RAW = pages::get;
        CareerSources.SEARCH = q -> searches.getOrDefault(q, List.of());
    }

    @AfterEach void back() { CareerSources.RAW = CareerSources::fetch; CareerSources.SEARCH = CareerSources::search; CareerSources.POLITE_MS = 1000; CareerSources.reset(); }

    @Test void theSiteIsConfirmedByTheBoardItLinksAndTheSizeComesFromTheSiteLinkedInOrWikidata() {
        // the site: a search for the name gives a namesake first; only the page that links the same hiring board is the company's own
        searches.put("Marble careers jobs", List.of(new Hit("Marble.com - countertops", "https://marble.com/"), new Hit("Careers | Marble", "https://careers.marble.example/open-roles")));
        pages.put("https://marble.com/", "<html>Countertops. <a href=\"https://jobs.lever.co/marblecountertops\">Jobs</a></html>");
        pages.put("https://careers.marble.example/open-roles", "<html>Physical AI for process industries. <a href=\"https://jobs.ashbyhq.com/marble\">Apply</a></html>");
        Feed feed = new Feed("ashby", "marble", "found in a search for its careers page");
        assertEquals("marble.example", CareerSize.findSite("Marble", feed));
        assertEquals("acme.example", CareerSize.findSite("Acme", new Feed("jobposting", "https://www.acme.example/careers", "the job data on its careers page")), "a careers page of its own names the site");
        assertEquals("acme.example", CareerSize.findSite("Acme", new Feed("greenhouse", "acme", "linked from its careers page, https://acme.example/jobs")));

        // the size from the company's own pages: the data it carries for search engines
        pages.put("https://marble.example/about", "<html><script type=\"application/ld+json\">{\"@type\": \"Organization\", \"name\": \"Marble\", \"numberOfEmployees\": {\"@type\": \"QuantitativeValue\", \"minValue\": 11, \"maxValue\": 50}}</script></html>");
        CareerSize.People p = CareerSize.lookup("Marble", "marble.example");
        assertEquals(11, p.low()); assertEquals(50, p.high()); assertEquals("its own site, marble.example", p.source()); assertEquals("about 11 to 50 people", p.words());

        // or its words; a bare number on a whole page does not count, a phrase that says it does
        pages.put("https://marble.example/about", "<html><p>We are a team of 40 people in Boston.</p></html>");
        p = CareerSize.lookup("Marble", "marble.example"); assertEquals(40, p.low()); assertEquals(40, p.high());
        pages.put("https://marble.example/about", "<html><p>Founded in 2019. 40 employees of our customers use it daily.</p></html>");
        pages.put("https://marble.example/", "<html><p>Physical AI.</p></html>");
        // then LinkedIn's result, only when it names the same website
        searches.put("Marble employees", List.of(new Hit("Marble - LinkedIn", "https://www.linkedin.com/company/puchicon", "About us; Website: https://www.marble.com. 51-200 employees"),
                new Hit("Marble | LinkedIn", "https://www.linkedin.com/company/marble-physical-ai", "Website: https://marble.example. Industry: Robotics. 11-50 employees. Boston, MA")));
        p = CareerSize.lookup("Marble", "marble.example");
        assertEquals("its LinkedIn page", p.source()); assertEquals(11, p.low()); assertEquals(50, p.high());

        // then Wikidata, when the website it records is the company's
        searches.put("Marble employees", List.of());
        pages.put("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&type=item&limit=5&search=Marble",
                "{\"search\": [{\"id\": \"Q1\", \"label\": \"Marble\"}, {\"id\": \"Q2\", \"label\": \"Marble (company)\"}]}");
        pages.put("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&entity=Q1&property=P856", "{\"claims\": {\"P856\": [{\"mainsnak\": {\"datavalue\": {\"value\": \"https://marble.com/\"}}}]}}");
        pages.put("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&entity=Q2&property=P856", "{\"claims\": {\"P856\": [{\"mainsnak\": {\"datavalue\": {\"value\": \"https://www.marble.example/\"}}}]}}");
        pages.put("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&entity=Q2&property=P1128",
                "{\"claims\": {\"P1128\": [{\"mainsnak\": {\"datavalue\": {\"value\": {\"amount\": \"+35\"}}}, \"qualifiers\": {\"P585\": [{\"datavalue\": {\"value\": {\"time\": \"+2024-01-01T00:00:00Z\"}}}]}},"
                        + " {\"mainsnak\": {\"datavalue\": {\"value\": {\"amount\": \"+48\"}}}, \"qualifiers\": {\"P585\": [{\"datavalue\": {\"value\": {\"time\": \"+2026-01-01T00:00:00Z\"}}}]}}]}}");
        p = CareerSize.lookup("Marble", "marble.example");
        assertEquals(48, p.low()); assertEquals("Wikidata, as of 2026", p.source());
        assertNull(CareerSize.lookup("Marble", ""), "no site, no size");
    }

    @Test void aDecidedSizeCutsACompanyKnownToBeFarOutsideItAndOrdersOneThatMayBe() {
        CareerCut.Cut c = new CareerCut.Cut(List.of("CTO"), List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), 200, 0, "library", "", "");
        CareerSources.Opening o = CareerSources.opening("Big Co", "CTO", "Boston, MA", false, "https://big.example/1", "", "", "t", "", "", "");
        CareerCut.Verdict big = CareerCut.judge(c, o, List.of(), List.of(), new CareerCut.Company(new CareerSize.People(1001, 5000, "its LinkedIn page", "2026-10-29"), 12), 0);
        assertFalse(big.passes()); assertEquals("its company has about 1,001 to 5,000 people (its LinkedIn page), more than twice the 200 you decided", big.why());
        CareerCut.Verdict near = CareerCut.judge(c, o, List.of(), List.of(), new CareerCut.Company(new CareerSize.People(51, 200, "its LinkedIn page", "2026-10-29"), 12), 0);
        assertTrue(near.passes());
        CareerCut.Verdict maybe = CareerCut.judge(c, o, List.of(), List.of(), new CareerCut.Company(new CareerSize.People(201, 500, "its own site", "2026-10-29"), 12), 0);
        assertTrue(maybe.passes(), "within twice: passes, and the reading says what is known");
        assertTrue(CareerCut.rank(c, o, maybe, Set.of(), Set.of(), LocalDate.now(), new CareerCut.Company(new CareerSize.People(201, 500, "", ""), 12))
                < CareerCut.rank(c, o, near, Set.of(), Set.of(), LocalDate.now(), new CareerCut.Company(new CareerSize.People(51, 200, "", ""), 12)), "one that may be over comes after one within");
        CareerCut.Verdict busy = CareerCut.judge(c, o, List.of(), List.of(), new CareerCut.Company(null, 150), 0);
        assertFalse(busy.passes()); assertEquals("its own board lists 150 openings, more than a company of under 201 people has", busy.why());
        assertTrue(CareerCut.judge(c, o, List.of(), List.of(), new CareerCut.Company(null, 0), 0).passes(), "nothing known: passes");
        assertEquals("titles with CTO; at companies of up to 200 people, where the library knows the size", CareerCut.summary(c));
    }
}
