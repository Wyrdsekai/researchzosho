package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.CareerCut.Cut;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Found;
import org.researchzosho.librarian.CareerSources.Hit;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hiring portals institutions use, and the way to them for a hospital a register listed with no site, against stand-ins: nothing here touches the web. */
class CareerPortalsTest {
    static final ObjectMapper M = new ObjectMapper();
    final Map<String, String> pages = new HashMap<>();
    final Map<String, List<Hit>> searches = new HashMap<>();
    final Map<String, String> posts = new HashMap<>();
    CareerPortals.Poster posterWas; java.util.function.Function<String, String> wikidataWas;

    static final String PHENOM = "{\"refineSearch\":{\"status\":200,\"hits\":3,\"totalHits\":3,\"data\":{\"jobs\":["
            + "{\"title\":\"Registered Nurse NICU\",\"city\":\"Austin\",\"state\":\"TX\",\"cityState\":\"Austin, TX\",\"postedDate\":\"2026-10-21T21:36:49.000+0000\",\"jobSeqNo\":\"AHEAHUUS20314EXTERNALENUS\",\"department\":\"Ascension Seton Medical Center Austin\",\"descriptionTeaser\":\"Nights, level IV NICU.\"},"
            + "{\"title\":\"RN Educator Surgical Services\",\"city\":\"Austin\",\"state\":\"TX\",\"cityState\":\"Austin, TX\",\"postedDate\":\"2026-10-22T20:12:59.000+0000\",\"jobSeqNo\":\"AHEAHUUS1310762EXTERNALENUS\",\"descriptionTeaser\":\"Teaches.\"},"
            + "{\"title\":\"Registered Nurse Labor and Delivery\",\"city\":\"Wichita\",\"state\":\"KS\",\"cityState\":\"Wichita, KS\",\"postedDate\":\"2026-10-14T00:48:03.000+0000\",\"jobSeqNo\":\"AHEAHUUS16228EXTERNALENUS\",\"descriptionTeaser\":\"\"}]}}}";
    static final String ORACLE = "{\"items\":[{\"TotalJobsCount\":2,\"requisitionList\":[{\"Id\":\"428463\",\"Title\":\"Licensed Vocational Nurse\",\"PrimaryLocation\":\"Humble, TX, United States\",\"PostedDate\":\"2026-10-30\",\"ShortDescriptionStr\":\"Our mission starts with our people.\",\"secondaryLocations\":[]},"
            + "{\"Id\":\"428066\",\"Title\":\"Patient Care Assistant - PICU\",\"PrimaryLocation\":\"Houston, TX, United States\",\"PostedDate\":\"2026-10-30\",\"ShortDescriptionStr\":\"\",\"secondaryLocations\":[{\"Name\":\"Austin, TX, United States\"}]}]}]}";
    static final String ICIMS = "<html><body><div class=\"row\"><a href=\"https://careers-vch.icims.com/jobs/173243/mental-health-nurse/job?mode=job\">Job Title\n\nMental Health Nurse (RN)</a><dl><dt>Job Locations</dt><dd>CA-BC-Vancouver</dd><dt>Posted Date</dt><dd>2 days ago</dd></dl></div>"
            + "<div class=\"row\"><a href=\"https://careers-vch.icims.com/jobs/173100/registered-nurse-med-surg/job\">Job Title\n\nRegistered Nurse (RN) Med/Surg</a><dl><dt>Job Locations</dt><dd>CA-BC-Richmond</dd></dl></div><a href=\"https://careers-vch.icims.com/jobs/173100/registered-nurse-med-surg/job\">View</a></body></html>";
    static final String TALEO_PAGE = "<html><body><a href=\"/careersection/10121/jobsearch.ftl?lang=en&portal=960110087\">Search</a></body></html>";
    static final String TALEO_REST = "{\"requisitionList\":[{\"jobId\":\"4306009\",\"contestNo\":\"2606002960\",\"column\":[\"Clinical Nurse Specialist\",\"[\\\"MI-Detroit\\\"]\",\"Oct 27, 2026\"]},{\"jobId\":\"4306010\",\"contestNo\":\"2606002961\",\"column\":[\"Registered Nurse ICU\",\"[\\\"TX-Austin\\\"]\",\"Oct 28, 2026\"]}],\"pagingData\":{\"currentPageNo\":1,\"pageSize\":25,\"totalCount\":2}}";
    static final String PEOPLEADMIN = "<html><body><a href=\"/postings/27274\">Occupational Health Director</a> <a href=\"/postings/27274\">View Details</a> <a href=\"/postings/27270\">Assistant Clinical Professor, DNP</a></body></html>";

    @BeforeEach void standIns() {
        CareerSources.reset(); CareerSources.POLITE_MS = 0;
        CareerSources.RAW = pages::get;
        CareerSources.RAW_POST = (u, j) -> posts.get(u);
        CareerSources.SEARCH = q -> searches.getOrDefault(q, List.of());
        posterWas = CareerPortals.POST_WITH_SESSION; wikidataWas = CareerPortals.WIKIDATA;
        CareerPortals.POST_WITH_SESSION = (page, url, json, headers) -> url.contains("portal=960110087") && headers.containsKey("tz") ? TALEO_REST : null;
        CareerPortals.WIKIDATA = u -> u.contains("wbsearchentities") ? "{\"search\":[]}" : null;
        posts.put("https://jobs.ascension.example/widgets", PHENOM);
        pages.put("https://eohh.fa.us2.oraclecloud.com/hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true&expand=requisitionList.secondaryLocations&finder=findReqs;siteNumber=CX,limit=200,sortBy=POSTING_DATES_DESC,keyword=Nurse", ORACLE);
        pages.put("https://careers-vch.icims.com/jobs/search?ss=1&in_iframe=1&searchKeyword=Nurse", ICIMS);
        pages.put("https://tenet.taleo.net/careersection/10121/jobsearch.ftl?lang=en", TALEO_PAGE);
        pages.put("https://une.peopleadmin.com/postings/search?query=Nurse", PEOPLEADMIN);
    }

    @AfterEach void back() { CareerSources.RAW = CareerSources::fetch; CareerSources.RAW_POST = CareerSources::post; CareerSources.SEARCH = CareerSources::search; CareerSources.POLITE_MS = 1000; CareerSources.reset(); CareerPortals.POST_WITH_SESSION = posterWas; CareerPortals.WIKIDATA = wikidataWas; }

    @Test void eachPortalIsReadInTheOpeningsShape() {
        Found ph = CareerPortals.read(new Feed("phenom", "jobs.ascension.example", "x"), "Ascension", List.of("Nurse"));
        assertNotNull(ph); assertEquals(3, ph.openings().size());
        assertEquals("Registered Nurse NICU", ph.openings().get(0).title()); assertEquals("Austin, TX", ph.openings().get(0).location()); assertEquals("2026-10-21", ph.openings().get(0).posted());
        assertTrue(ph.openings().get(0).text().startsWith("Ascension Seton Medical Center Austin. Nights"), "the department rides in the text, for the guards: " + ph.openings().get(0).text());
        assertEquals("https://jobs.ascension.example/us/en/job/AHEAHUUS20314EXTERNALENUS", ph.openings().get(0).url());
        Found or = CareerPortals.read(new Feed("oracle", "eohh.fa.us2.oraclecloud.com/CX", "x"), "Texas Children's", List.of("Nurse"));
        assertNotNull(or); assertEquals(2, or.openings().size());
        assertEquals("Houston, TX, United States; Austin, TX, United States", or.openings().get(1).location(), "the secondary locations ride along");
        assertEquals("https://eohh.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX/job/428066", or.openings().get(1).url());
        Found ic = CareerPortals.read(new Feed("icims", "careers-vch", "x"), "Vancouver Coastal Health", List.of("Nurse"));
        assertNotNull(ic); assertEquals(2, ic.openings().size(), "the View link is the same job");
        assertEquals("Mental Health Nurse (RN)", ic.openings().get(0).title()); assertEquals("CA-BC-Vancouver", ic.openings().get(0).location()); assertEquals("posted 2 days ago", ic.openings().get(0).postedNote());
        Found ta = CareerPortals.read(new Feed("taleo", "tenet.taleo.net/10121", "x"), "Tenet", List.of("Nurse"));
        assertNotNull(ta); assertEquals(2, ta.openings().size());
        assertEquals("Registered Nurse ICU", ta.openings().get(1).title()); assertEquals("TX-Austin", ta.openings().get(1).location()); assertEquals("https://tenet.taleo.net/careersection/10121/jobdetail.ftl?job=2606002961&lang=en", ta.openings().get(1).url());
        Found pa = CareerPortals.read(new Feed("peopleadmin", "une.peopleadmin.com", "x"), "A university", List.of("Nurse"));
        assertNotNull(pa); assertEquals(List.of("Occupational Health Director", "Assistant Clinical Professor, DNP"), pa.openings().stream().map(o -> o.title()).toList());
        assertEquals("own careers site", new Feed("phenom", "h", "").system()); assertEquals("Taleo", new Feed("taleo", "h/1", "").system());
    }

    @Test void aCareersPageLeadsToItsPortal() {
        assertEquals("oracle", CareerPortals.feedOfPage("https://www.texaschildrens.example/careers", "<a href=\"https://eohh.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX/jobs\">Search jobs</a>").kind());
        assertEquals("eohh.fa.us2.oraclecloud.com/CX", CareerPortals.feedOfPage("https://www.texaschildrens.example/careers", "<a href=\"https://eohh.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX/jobs\">Search jobs</a>").token());
        assertEquals("phenom", CareerPortals.feedOfPage("https://jobs.ascension.example/", "<script src=\"https://pp-cdn.phenompeople.com/x.js\"></script>").kind());
        assertEquals("jobs.ascension.example", CareerPortals.feedOfPage("https://jobs.ascension.example/", "<script src=\"https://pp-cdn.phenompeople.com/x.js\"></script>").token());
        assertEquals("icims", CareerPortals.feedOfPage("https://careers-vch.icims.com/", "<html></html>").kind());
        assertEquals("tenet.taleo.net/10121", CareerPortals.feedOfPage("https://www.tenet.example/careers", "<a href=\"https://tenet.taleo.net/careersection/10121/jobsearch.ftl?lang=en\">Jobs</a>").token());
        assertEquals("une.peopleadmin.com", CareerPortals.feedOfPage("https://www.une.example/hr", "<a href=\"https://une.peopleadmin.com/postings/search\">Postings</a>").token());
        assertNull(CareerPortals.feedOfPage("https://www.acme.example/careers", "<p>We hire.</p>"));
        List<Feed> inHits = CareerSources.boardsIn("https://eohh.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX/jobs", "x");
        assertEquals(1, inHits.size()); assertEquals("oracle", inHits.get(0).kind()); assertEquals("eohh.fa.us2.oraclecloud.com/CX", inHits.get(0).token());
    }

    @Test void theOrganisationsAnInstitutionBelongsToComeFromItsNamesLeadingPhrase() {
        assertEquals(List.of("Ascension Seton", "Ascension"), CareerPortals.systems("Ascension Seton Medical Center Austin"));
        assertEquals(List.of("St David's"), CareerPortals.systems("St David's Medical Center"));
        assertEquals(List.of("Northwest Hills", "Northwest"), CareerPortals.systems("Northwest Hills Surgical Hospital"));
        assertEquals(List.of("Dell Seton", "Dell"), CareerPortals.systems("Dell Seton Med Center At The University Of Texas"));
        assertEquals(List.of("Baylor Scott & White", "Baylor Scott", "Baylor"), CareerPortals.systems("Baylor Scott & White Medical Center- Austin"));
        assertEquals(List.of("Texas Childrens", "Texas"), CareerPortals.systems("Texas Childrens Hospital North Austin"), "the state is dropped later, as a place word");
        assertEquals(List.of(), CareerPortals.systems("University of Pune"));
        assertEquals(List.of(), CareerPortals.systems("Acme Widgets"));
    }

    @Test void aHospitalWithNoSiteGetsItsOpeningsThroughTheSystemItBelongsToCutToTheTown() {
        searches.put("Ascension careers jobs", List.of(new Hit("Ascension careers", "https://jobs.ascension.example/")));
        pages.put("https://jobs.ascension.example/", "<html><head><title>Careers at Ascension</title><script src=\"https://pp-cdn.phenompeople.com/x.js\"></script></head><body><h1>Jobs</h1><a href=\"/us/en/search-results\">Search jobs</a></body></html>");
        Found f = CareerPortals.find("Ascension Seton Medical Center Austin", List.of("Austin"), List.of("Nurse"), List.of("Austin", "TX"));
        assertNotNull(f.feed()); assertEquals("phenom", f.feed().kind()); assertEquals("jobs.ascension.example", f.feed().token());
        assertTrue(f.feed().how().endsWith("; found through Ascension, the organisation it belongs to"), f.feed().how());
        assertEquals(2, f.openings().size(), "the Wichita one is not in Austin");
        Found tx2 = CareerPortals.find("Ascension Seton Medical Center Austin", List.of("Austin", "Texas"), List.of("Nurse"), List.of());
        assertEquals(2, tx2.openings().size(), "the state in the towns does not let a Wichita opening through on the strength of an employer's name in its text");
        assertEquals("Its openings come from Ascension's own careers site board, 2 in Austin.", f.note());
        // through the search itself: a company a register listed, with no site
        ObjectNode c = M.createObjectNode().put("name", "Ascension Seton Medical Center Austin").put("from", "sources");
        Cut cut = new Cut(List.of("Nurse"), List.of(), List.of(), List.of(), List.of("Austin"), "no", List.of(), List.of(), List.of(), 0, 0, "person", LocalDate.now().toString(), "b1");
        Found viaSearch = CareerFind.readCompany(c, cut);
        assertEquals(2, viaSearch.openings().size()); assertEquals("phenom", viaSearch.feed().kind());
        // a board found through the organisation that lists nothing in the town is not taken as the hospital's
        posts.put("https://jobs.ascension.example/widgets", PHENOM.replace("Austin", "Dallas"));
        Found none = CareerPortals.find("Ascension Seton Medical Center Austin", List.of("Austin"), List.of("Nurse"), List.of());
        assertNull(none.feed()); assertEquals("Ascension's board (own careers site, 3 openings) lists none in Austin, so the search does not take it as this employer's", none.note());
        // a namesake: the name's leading word is a technology company whose careers site says nothing of a hospital, so its board is not taken
        searches.put("Dell careers jobs", List.of(new Hit("Dell careers", "https://jobs.dell.example/")));
        pages.put("https://jobs.dell.example/", "<html><head><title>Dell Technologies careers</title><script src=\"https://pp-cdn.phenompeople.com/x.js\"></script></head><body>We build computers.</body></html>");
        posts.put("https://jobs.dell.example/widgets", "{\"refineSearch\":{\"totalHits\":1,\"data\":{\"jobs\":[{\"title\":\"Software Engineer\",\"cityState\":\"Austin, TX\",\"jobSeqNo\":\"D1\",\"descriptionTeaser\":\"servers\"}]}}}");
        Found dell = CareerPortals.find("Dell Seton Med Center At The University Of Texas", List.of("Austin"), List.of("Nurse"), List.of());
        assertNull(dell.feed(), dell.note()); assertEquals("no hiring page turned up for it or for the organisation it belongs to; Dell's board (own careers site) says nothing of a place of its kind, so it is not taken as this employer's", dell.note());
        // a state's name in a hospital's name is where it is, not whom it belongs to: "Texas" is never searched as an organisation
        searches.put("Texas careers jobs", List.of(new Hit("Texas jobs", "https://jobs.texas.example/")));
        pages.put("https://jobs.texas.example/", "<html><head><script src=\"https://pp-cdn.phenompeople.com/x.js\"></script></head><body>Health and parks jobs in Austin</body></html>");
        posts.put("https://jobs.texas.example/widgets", "{\"refineSearch\":{\"totalHits\":1,\"data\":{\"jobs\":[{\"title\":\"Park Ranger\",\"cityState\":\"Austin, TX\",\"jobSeqNo\":\"T1\",\"descriptionTeaser\":\"health care benefits\"}]}}}");
        Found tx = CareerPortals.find("Texas Childrens Hospital North Austin", List.of("Austin", "Texas"), List.of("Nurse"), List.of());
        assertNull(tx.feed(), tx.note());
        // a university's board found through a shorter name than the hospital's: it must carry one of the name's other words, or it is left
        searches.put("Baylor careers jobs", List.of(new Hit("Baylor University careers", "https://hr.baylor.example/careers")));
        pages.put("https://hr.baylor.example/careers", "<html><head><script src=\"https://pp-cdn.phenompeople.com/x.js\"></script></head><body>Baylor University, a Christian university in Waco. Nurse faculty wanted.</body></html>");
        posts.put("https://hr.baylor.example/widgets", "{\"refineSearch\":{\"totalHits\":1,\"data\":{\"jobs\":[{\"title\":\"Nurse Faculty\",\"cityState\":\"Austin, TX\",\"jobSeqNo\":\"B1\",\"descriptionTeaser\":\"teaching\"}]}}}");
        Found bsw = CareerPortals.find("Baylor Scott & White Medical Center- Austin", List.of("Austin"), List.of("Nurse"), List.of());
        assertNull(bsw.feed(), bsw.note()); assertTrue(bsw.note().contains("Baylor's board (own careers site) says nothing of a place of its kind"), bsw.note());
    }
}
