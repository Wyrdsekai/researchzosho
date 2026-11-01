package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.CareerCut.Cut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Companies first: the companies of a place from stand-in public records, merged by name, cut by what was decided, read by a stand-in model. */
class CareerCompaniesTest {
    final Map<String, String> pages = new LinkedHashMap<>();
    final List<String> asked = new ArrayList<>();
    final List<String> prompts = new ArrayList<>();
    static final String TODAY = LocalDate.now().toString();
    static final Cut CUT = new Cut(List.of("CTO"), List.of(), List.of(), List.of(), List.of("Boston", "Cambridge", "MA"), "yes", List.of(), List.of(), List.of(), 200, 0, "person", TODAY, "b1");

    /** The model: judges the numbered companies' domain (and stage when asked) and answers by name. */
    final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText();
            prompts.add(p);
            if (!p.startsWith("A person is looking for work. For each numbered company below, judge")) return "[]";
            StringBuilder b = new StringBuilder("[");
            Matcher line = Pattern.compile("(?m)^(\\d+)\\. (.+)$").matcher(p.substring(p.indexOf("THE COMPANIES")));
            while (line.find()) {
                String name = line.group(2).strip();
                String a = name.equals("Sentonix") ? "\"domain\": \"fits\", \"domain_why\": [\"its words say sensors\"], \"stage\": \"fits\", \"stage_why\": \"seed, formed within five years\""   // a list, as the model sometimes writes
                        : "\"domain\": \"unknown\", \"domain_why\": \"software for solar says nothing of sensors or hardware\", \"stage\": \"unknown\", \"stage_why\": \"\"";
                b.append(b.length() > 1 ? "," : "").append("{\"n\": ").append(line.group(1)).append(", ").append(a).append("}");
            }
            return b.append("]").toString();
        }
    }

    static String hit(String display, String city, String adsh, String cik, String fileDate) {
        return "{\"_source\": {\"display_names\": [\"" + display + "  (CIK " + cik + ")\"], \"biz_locations\": [\"" + city + ", MA\"], \"adsh\": \"" + adsh + "\", \"ciks\": [\"" + cik + "\"], \"file_date\": \"" + fileDate + "\"}}";
    }

    static String filing(String entity, String city, boolean young, String industry, String revenue, String firstSale, String offered, String sold, String officers) {
        return "<edgarSubmission><primaryIssuer><entityName>" + entity + "</entityName><issuerAddress><city>" + city + "</city><stateOrCountry>MA</stateOrCountry></issuerAddress>"
                + "<yearOfInc><withinFiveYears>" + young + "</withinFiveYears></yearOfInc></primaryIssuer><relatedPersonsList>" + officers + "</relatedPersonsList>"
                + "<offeringData><industryGroup><industryGroupType>" + industry + "</industryGroupType></industryGroup><issuerSize><revenueRange>" + revenue + "</revenueRange></issuerSize>"
                + "<typeOfFiling><dateOfFirstSale>" + firstSale + "</dateOfFirstSale></typeOfFiling><offeringSalesAmounts><totalOfferingAmount>" + offered + "</totalOfferingAmount><totalAmountSold>" + sold + "</totalAmountSold></offeringSalesAmounts></offeringData></edgarSubmission>";
    }

    static String officer(String first, String last, String... roles) {
        StringBuilder r = new StringBuilder(); for (String x : roles) r.append("<relationship>").append(x).append("</relationship>");
        return "<relatedPersonInfo><relatedPersonName><firstName>" + first + "</firstName><lastName>" + last + "</lastName></relatedPersonName><relatedPersonRelationshipList>" + r + "</relatedPersonRelationshipList></relatedPersonInfo>";
    }

    @BeforeEach void standIns() {
        CareerSources.reset(); CareerSources.POLITE_MS = 0;
        CareerSources.RAW = url -> { asked.add(url); for (Map.Entry<String, String> e : pages.entrySet()) if (url.startsWith(e.getKey())) return e.getValue(); return null; };
        Career.DRIVE = StandIn::new;
        pages.put("https://efts.sec.gov/LATEST/search-index?q=Massachusetts&forms=D&dateRange=custom&startdt=", "{\"hits\": {\"total\": {\"value\": 5}, \"hits\": ["
                + hit("Sentonix, Inc.", "Arlington", "0002012345-26-000001", "0002012345", "2026-09-30") + "," + hit("Bain Capital Fund XV, L.P.", "Boston", "0001000001-26-000009", "0001000001", "2026-09-29") + ","
                + hit("Acme Pharma, Inc.", "Boston", "0001500000-26-000002", "0001500000", "2026-08-01") + "," + hit("Pooled Co, Inc.", "Newton", "0001600000-26-000003", "0001600000", "2026-07-01") + ","
                + hit("Old Mill REIT Corp", "Boston", "0001700000-26-000004", "0001700000", "2026-06-01") + "]}}");
        pages.put("https://www.sec.gov/Archives/edgar/data/2012345/000201234526000001/primary_doc.xml", filing("Sentonix, Inc.", "Arlington", true, "Other Technology", "No Revenues", "<value>2026-09-15</value>", "3000000", "2500000",
                officer("Rae", "Quill", "Executive Officer", "Director") + officer("Tomas", "Vane", "Director")));
        pages.put("https://www.sec.gov/Archives/edgar/data/1500000/000150000026000002/primary_doc.xml", filing("Acme Pharma, Inc.", "Boston", false, "Biotechnology", "Decline to Disclose", "<yetToOccur>true</yetToOccur>", "12000000", "0", ""));
        pages.put("https://www.sec.gov/Archives/edgar/data/1600000/000160000026000003/primary_doc.xml", filing("Pooled Co, Inc.", "Newton", false, "Pooled Investment Fund", "Not Applicable", "<value>2026-06-30</value>", "900000", "900000", ""));
        pages.put("https://yc-oss.github.io/api/companies/all.json", """
                [{"name": "Raptor Maps", "website": "http://www.raptormaps.com", "all_locations": "Boston, MA, USA", "regions": ["America / Canada"], "team_size": 75, "batch": "S16", "status": "Active", "stage": "Growth", "industries": ["Industrials", "Climate"], "one_liner": "Software for solar.", "launched_at": 1470000000, "isHiring": true},
                 {"name": "Sentonix", "website": "https://www.sentonix.example/", "all_locations": "Cambridge, MA, USA", "regions": ["America / Canada"], "team_size": 12, "batch": "W26", "status": "Active", "stage": "Early", "industries": ["B2B", "Engineering, Product and Design"], "one_liner": "Sensors that read themselves.", "launched_at": 1770000000, "isHiring": false},
                 {"name": "Gone Co", "website": "https://gone.example", "all_locations": "Boston, MA, USA", "regions": ["America / Canada"], "team_size": 5, "batch": "S19", "status": "Inactive", "industries": ["B2B"], "one_liner": "Gone."},
                 {"name": "Elsewhere", "website": "https://elsewhere.example", "all_locations": "London, England, United Kingdom", "regions": ["Europe"], "team_size": 40, "batch": "W22", "status": "Active", "industries": ["B2B"], "one_liner": "Not here."}]""");
        pages.put("https://query.wikidata.org/sparql?format=json&query=", """
                {"results": {"bindings": [
                  {"c": {"value": "http://www.wikidata.org/entity/Q1"}, "cLabel": {"value": "Big Systems"}, "site": {"value": "https://www.bigcorp.example/"}, "emp": {"value": "8000"}, "inc": {"value": "1951-01-01T00:00:00Z"}, "indLabel": {"value": "software industry"}},
                  {"c": {"value": "http://www.wikidata.org/entity/Q2"}, "cLabel": {"value": "Acme Pharma (United States)"}, "site": {"value": "http://acmepharma.example"}, "inc": {"value": "2009-01-01T00:00:00Z"}, "indLabel": {"value": "biotechnology"}}]}}""");
    }

    @AfterEach void back() { CareerSources.RAW = CareerSources::fetch; CareerSources.POLITE_MS = 1000; CareerSources.reset(); Career.DRIVE = Explain::drive; }

    Career.Seeker seeker(LibraryStore store, Path tmp) throws Exception {
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        Career.setWant(store, s, "role", "decided", "CTO or VP of Engineering");
        Career.setWant(store, s, "company size", "decided", "not bigger than 200");
        Career.setWant(store, s, "place", "decided", "Boston or Cambridge, MA");
        Career.setWant(store, s, "domain", "decided", "sensors and hardware");
        CareerSearch.addKeepOut(store, s, "biotechnology", "");
        CareerCut.save(store, s, CUT);
        return s;
    }

    @Test void thePlaceComesFromTheCutAndNamesTheCountryAndTheRegion() {
        CareerCompanies.Place p = CareerCompanies.placeOf(CUT);
        assertEquals("US", p.country()); assertEquals("MA", p.region()); assertEquals("Massachusetts", p.regionName()); assertEquals(List.of("Boston", "Cambridge"), p.cities());
        CareerCompanies.Place berlin = CareerCompanies.placeOf(new Cut(List.of(), List.of(), List.of(), List.of(), List.of("Berlin"), "", List.of(), List.of(), List.of(), 0, 0, "person", TODAY, "b"));
        assertEquals("", berlin.country()); assertEquals(List.of("Berlin"), berlin.cities());
        assertFalse(new CareerCompanies.SecFormD().covers(berlin), "the SEC's filings cover the United States, by state"); assertTrue(new CareerCompanies.WikidataCompanies().covers(berlin), "Wikidata covers any city");
        assertEquals("MA", CareerCompanies.placeOf(new Cut(List.of(), List.of(), List.of(), List.of(), List.of("Massachusetts"), "", List.of(), List.of(), List.of(), 0, 0, "person", TODAY, "b")).region(), "a state by its name");
        assertFalse(CareerCompanies.placeOf(Cut.NONE).known());
        assertEquals("Sentonix", CareerCompanies.shortName("Sentonix, Inc.")); assertEquals("ALZHEON", CareerCompanies.shortName("ALZHEON, INC.")); assertEquals("Acme Co.", CareerCompanies.shortName("Acme Co., Ltd."));
        assertEquals("Mutual Bancorp", CareerCompanies.shortName("Mutual Bancorp")); assertEquals(CareerCompanies.key("Sentonix, Inc."), CareerCompanies.key("Sentonix"), "two sources' spellings of one company share a key");
    }

    @Test void theSourcesAreMergedByNameCutByWhatWasDecidedReadByTheModelAndReactedTo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = seeker(store, tmp);
        int known = CareerCompanies.refresh(store, s, CUT, () -> false);
        assertEquals(4, known, "Sentonix (two sources), Acme Pharma (two sources), Raptor Maps, Big Corp; the fund and the REIT are skipped by name, the pooled fund by its industry, the inactive and the London company by the directory's own words");
        ObjectNode doc = CareerCompanies.kept(store, s);
        assertEquals(List.of("the SEC's Form D filings: 2 companies, 2 new", "Y Combinator's directory: 2 companies, 1 new", "Wikidata: 2 companies, 1 new"), List.of(doc.path("notes").get(0).asText(), doc.path("notes").get(1).asText(), doc.path("notes").get(2).asText()));
        assertEquals("Boston, Cambridge, Massachusetts", doc.path("place").asText());
        assertTrue(asked.stream().noneMatch(u -> u.contains("/1000001/")), "the fund's filing is never read: its name says what it is");
        assertEquals(1, asked.stream().filter(u -> u.startsWith("https://yc-oss.github.io/")).count());
        assertTrue(Files.isRegularFile(tmp.resolve("lib/catalog/career-cache/yc-companies.json")), "the directory's ten megabytes are kept with the library for a week");

        ObjectNode sx = (ObjectNode) doc.path("companies").get("sentonix");
        assertEquals("Sentonix", sx.path("name").asText()); assertEquals("Sentonix, Inc.", sx.path("legal").asText()); assertEquals("Arlington", sx.path("city").asText()); assertEquals("MA", sx.path("region").asText());
        assertEquals(12, sx.path("people").asInt()); assertEquals("Y Combinator's directory", sx.path("people_by").asText()); assertEquals("early, YC W26", sx.path("stage").asText()); assertEquals("sentonix.example", sx.path("site").asText());
        assertEquals("$2,500,000 sold", sx.path("raised").asText()); assertEquals("2026-09-15", sx.path("raised_on").asText()); assertEquals("no revenue yet", sx.path("revenue").asText()); assertEquals("formed within the last five years", sx.path("age").asText());
        assertEquals("Other Technology", sx.path("industry").asText(), "the first source's fact stands; the directory's industries would have filled an empty one");
        assertEquals("Rae Quill, executive officer and director", sx.path("officers").get(0).asText()); assertEquals("Tomas Vane, director", sx.path("officers").get(1).asText());
        assertEquals(2, sx.path("sources").size()); assertEquals("the SEC's Form D filings, filed 2026-09-30", sx.path("sources").get(0).asText());
        assertEquals("Sentonix (sentonix.example); in Arlington, MA; about 12 people (Y Combinator's directory); early, YC W26; founded 2026; formed within the last five years; no revenue yet; Other Technology; \"Sensors that read themselves.\"; raised $2,500,000 sold on 2026-09-15; officers: Rae Quill, executive officer and director; Tomas Vane, director; by the SEC's Form D filings, filed 2026-09-30 and Y Combinator's directory",
                CareerCompanies.facts(sx));
        ObjectNode acme = (ObjectNode) doc.path("companies").get("acmepharma");
        assertEquals("Acme Pharma", acme.path("name").asText(), "Wikidata's \"(United States)\" is not part of the name"); assertEquals("$12,000,000 offered", acme.path("raised").asText()); assertEquals("2026-08-01", acme.path("raised_on").asText(), "no sale yet: the filing's day");
        assertEquals("", acme.path("revenue").asText(), "declined to disclose is nothing"); assertEquals("acmepharma.example", acme.path("site").asText(), "Wikidata filled the site the filing lacks");
        assertEquals("Raptor Maps", doc.path("companies").path("raptormaps").path("name").asText()); assertEquals("Y Combinator's directory, which says it is hiring", doc.path("companies").path("raptormaps").path("sources").get(0).asText());

        int before = asked.size();
        assertEquals(4, CareerCompanies.refresh(store, s, CUT, () -> false)); assertEquals(before, asked.size(), "refreshed today: nothing is read again for a week");
        assertEquals(4, CareerCompanies.refresh(store, s, CUT, () -> false, true)); assertTrue(asked.size() > before, "asked for, the records are read again");
        assertEquals(1, asked.stream().filter(u -> u.startsWith("https://yc-oss.github.io/")).count(), "the directory comes from the week's cache");

        List<ObjectNode> standing = CareerCompanies.standing(store, s, CUT);
        assertEquals(List.of("Sentonix", "Raptor Maps"), standing.stream().map(e -> e.path("name").asText()).toList(), "newest raise first; Big Corp and Acme Pharma are out");
        assertEquals("about 8,000 people, by Wikidata, which gives its employees, more than twice the 200 you decided", CareerCompanies.cutBy(CUT, (ObjectNode) CareerCompanies.kept(store, s).path("companies").get("bigsystems"), List.of()));
        assertEquals("it is in biotechnology, which you keep out", CareerCompanies.cutBy(CUT, acme, List.of("biotechnology")));
        assertEquals("it is in healthcare, which you keep out", CareerCompanies.cutBy(CUT, new ObjectMapper().createObjectNode().put("name", "Miach").put("industry", "Other Health Care"), List.of("healthcare")), "a filing writes health care in two words");
        assertEquals("", CareerCompanies.cutBy(CUT, new ObjectMapper().createObjectNode().put("name", "Mailer").put("about", "email for teams"), List.of("ai")), "a short keep-out is matched only as a word");

        assertEquals(2, CareerCompanies.read(store, s, CUT, 60, () -> false));
        String p = prompts.get(prompts.size() - 1);
        assertTrue(p.contains("1. Sentonix\n   what it does: industry: Other Technology; in its words: \"Sensors that read themselves.\"\n") && p.contains("2. Raptor Maps\n   what it does: industry: Industrials, Climate; in its words: \"Software for solar.\""), p);
        assertTrue(p.contains("They want: sensors and hardware.") && p.contains("They keep out: biotechnology."), "the domain wanted and the keep-outs go with each company's own facts");
        assertFalse(p.contains("raised") || p.contains("people"), "the model sees no raise and no size: it judges what it does, from what it does");
        ObjectNode again = CareerCompanies.kept(store, s);
        JsonNode sx2 = again.path("companies").path("sentonix").path("reading"), rm = again.path("companies").path("raptormaps").path("reading");
        assertEquals("yes", sx2.path("approach").asText(), "nothing against, a raise this year, and what it does fits: the reading is in the file that was written");
        assertEquals("in Arlington, MA (the filing); about 12 people (Y Combinator's directory), within the 200 you decided; raised $2,500,000 sold on 2026-09-15 (the filing); formed within the last five years (the filing); no revenue yet (the filing); what it does fits what you decided, sensors and hardware: its words say sensors",
                sx2.path("fits").asText(), "the computed lines name their sources; the model's judgment is one line, a list joined");
        assertEquals("", sx2.path("against").asText()); assertEquals("", sx2.path("unknown").asText());
        assertEquals("maybe", rm.path("approach").asText(), "a small team is a reason to approach, but what it does is unknown");
        assertEquals("in Boston, MA (the directory); about 75 people (Y Combinator's directory, which says it is hiring), within the 200 you decided", rm.path("fits").asText());
        assertEquals("what it does: software for solar says nothing of sensors or hardware", rm.path("unknown").asText());
        assertTrue(Files.readString(CareerFind.dir(store, s).resolve("companies-reading.txt")).contains("## companies 1 to 2\n["), "every batch's raw answer is kept");
        assertTrue(Files.isRegularFile(CareerFind.dir(store, s).resolve("companies-reading.txt")), "the raw answer is kept beside the readings");
        assertEquals(0, CareerCompanies.read(store, s, CUT, 60, () -> false), "read once for this basis, read again only when what the person wants changes");

        List<ObjectNode> list = CareerFind.companyList(store, s, CUT);   // the search's list: a company worth approaching joins it as the library's, with the fits as the why
        ObjectNode onList = list.stream().filter(c -> c.path("name").asText().equals("Sentonix")).findFirst().orElseThrow();
        assertEquals("library", onList.path("by").asText()); assertEquals("companies", onList.path("from").asText()); assertTrue(onList.path("why").asText().startsWith("Among the companies of your place, worth approaching: in Arlington, MA (the filing); about 12 people"), onList.path("why").asText());
        assertEquals(12, onList.path("people").path("low").asInt()); assertEquals("early, YC W26", onList.path("stage").asText()); assertEquals("sentonix.example", onList.path("site").asText());
        assertTrue(list.stream().noneMatch(c -> c.path("name").asText().equals("Raptor Maps")), "a maybe is not on the list");
        onList.putObject("card").put("job_id", "J-0001").put("filed", TODAY).put("full", false).put("built", "");
        CareerFind.saveCompanies(store, s, list);
        ObjectNode again_ = CareerFind.companyList(store, s, CUT).stream().filter(c -> c.path("name").asText().equals("Sentonix")).findFirst().orElseThrow();
        assertEquals("J-0001", again_.path("card").path("job_id").asText(), "listed again, it keeps its card"); assertEquals("", again_.path("state").asText(), "and is not gone for want of openings");
        again_.putObject("people").put("low", 500).put("high", 600).put("source", "its company card, third-party, as of " + TODAY).put("at", TODAY); again_.put("stage", "series F");   // what its card said
        assertEquals(Set.of("sentonix"), CareerCompanies.learn(store, s, List.of(again_)), "the card's size and stage go back to the record");
        ObjectNode learned = (ObjectNode) CareerCompanies.kept(store, s).path("companies").get("sentonix");
        assertEquals(500, learned.path("people").asInt()); assertEquals(600, learned.path("people_high").asInt()); assertEquals("series F", learned.path("stage").asText()); assertFalse(learned.has("reading"), "and its reading is made again with them");
        assertEquals("about 500 to 600 people, by its company card, third-party, as of " + TODAY + ", more than twice the 200 you decided", CareerCompanies.cutBy(CUT, learned, List.of()), "the cut sees the size at once");
        assertTrue(CareerCompanies.learn(store, s, List.of(again_)).isEmpty(), "nothing new: nothing changes");
        again_.putObject("people").put("low", 12).put("high", 12).put("source", "Y Combinator's directory").put("at", TODAY); again_.put("stage", "early, YC W26");
        CareerCompanies.learn(store, s, List.of(again_));   // back to the directory's, for the rest of this test
        assertEquals(1, CareerCompanies.read(store, s, CUT, 60, () -> false, Set.of("sentonix")), "read again, this one alone");
        ObjectNode withCard = new ObjectMapper().createObjectNode().put("industry", "Other Technology").put("card_about", "Boltz builds AI molecular-modeling tools for drug discovery.");
        assertEquals("industry: Other Technology; its card says: \"Boltz builds AI molecular-modeling tools for drug discovery.\"", CareerCompanies.domainFacts(withCard), "the card's words join a filing's category, for the domain to be judged from words");
        assertTrue(CareerCompanies.facts(withCard.put("name", "Boltz")).contains("its card: \"Boltz builds AI"), "and the page shows them");
        assertEquals("", CareerCompany.whatTheyDo(store, "Sentonix"), "no card: no words");
        store.write(CareerCompanyTest.claim("F-9001-sentonix", "Sentonix shipped its first sensor platform", "Sentonix builds self-reading sensors and shipped its first platform in 2026.", "2026-06-01", "https://sentonix.example/news"));
        store.write(CareerCompanyTest.claim("F-9002-sentonix", "Sentonix raised $2.5M", "Sentonix raised a $2.5M seed round in September 2026.", "2026-09-15", "https://sentonix.example/news"));
        String what = CareerCompany.whatTheyDo(store, "Sentonix");
        assertTrue(what.startsWith("Sentonix shipped its first sensor platform: Sentonix builds self-reading sensors") && !what.contains("raised"), "a card with no dossier page: its claims on what it builds, not the money: " + what);
        again_.put("stage", "seed");   // a search that learned from the card: its words join the record
        assertEquals(Set.of("sentonix"), CareerCompanies.learn(store, s, List.of(again_)));
        assertTrue(((ObjectNode) CareerCompanies.kept(store, s).path("companies").get("sentonix")).path("card_about").asText().startsWith("Sentonix shipped its first sensor platform"), "the card's words are on the record for the domain judgment");
        assertEquals(1, CareerCompanies.read(store, s, CUT, 60, () -> false, Set.of("sentonix")), "and it is read again with them");
        assertTrue(prompts.get(prompts.size() - 1).contains("its card says: \"Sentonix shipped its first sensor platform"), "the model sees the card's words under what it does");
        store.write(CareerCompanyTest.claim("F-9003-raptor", "Raptor Maps builds solar software", "Raptor Maps builds software to manage solar power plants.", "2026-05-01", "https://raptormaps.example/about"));
        assertEquals(Set.of("raptormaps"), CareerCompanies.learnFromShelves(store, s), "a card on the shelves of a company the list does not carry reaches the record too");
        assertTrue(((ObjectNode) CareerCompanies.kept(store, s).path("companies").get("raptormaps")).path("card_about").asText().startsWith("Raptor Maps builds solar software"));
        assertEquals(1, CareerCompanies.read(store, s, CUT, 60, () -> false, Set.of("raptormaps")));
        assertTrue(CareerCompanies.learnFromShelves(store, s).isEmpty(), "words already on the record: nothing to learn");

        String text = CareerCompanies.text(store, s, CUT, "/career", 8);
        assertTrue(text.startsWith("Employers of Boston, Cambridge, Massachusetts from the public records, refreshed " + TODAY + ": 4 known, 2 fit what you decided, 2 set aside by it."), text);
        assertTrue(text.contains("To approach now:\n- Sentonix (sentonix.example)") && text.contains("   Fits: in Arlington, MA (the filing); about 12 people") && text.contains("what it does fits what you decided, sensors and hardware: its words say sensors"), text);
        assertTrue(text.contains("Maybe, something decided is unknown:\n- Raptor Maps"), text);

        assertEquals("Passed on: Sentonix. ", CareerCompanies.react(store, s, "Sentonix, Inc.", "pass", "too early for me"));
        assertEquals(List.of("Raptor Maps"), CareerCompanies.standing(store, s, CUT).stream().map(e -> e.path("name").asText()).toList());
        assertEquals("Taken back: your reaction to Sentonix.", CareerCompanies.react(store, s, "sentonix", "", ""));
        assertTrue(CareerCompanies.react(store, s, "Sentonix", "like", "").startsWith("Liked: Sentonix. "));
        assertThrows(IllegalArgumentException.class, () -> CareerCompanies.react(store, s, "Nobody Inc", "like", ""));
        String log = Files.readString(s.dir(store).resolve("log.jsonl"));
        assertTrue(log.contains("You passed on Sentonix: too early for me") && log.contains("The companies of your place, from the public records: the SEC's Form D filings: 2 companies, 2 new;"), log);

        String page = CareerCompaniesPage.body(store, s, true);
        assertTrue(page.contains("<h2>Liked</h2>") && page.contains("<b>Sentonix</b>") && page.contains("Fits:</b> in Arlington, MA (the filing)") && page.contains("Maybe: something you decided is unknown") && page.contains("Set aside by what you decided, or passed on <span class=\"k\">2</span>"), page);
        assertTrue(page.contains("name=\"reaction\" value=\"like\"") && page.contains("Search again"), "the owner reacts and searches");
        String viewer = CareerCompaniesPage.body(store, s, false);
        assertFalse(viewer.contains("<form"), "a reader sees the companies and no buttons");
        String career = CareerPage.companies(store, s, true);
        assertTrue(career.contains("4 employers of Boston, Cambridge, Massachusetts from the public records, refreshed " + TODAY + ": 2 fit what you decided, 1 worth approaching now. "), career);
    }

    static String greenhouse(String location) {
        return "{\"jobs\": [{\"title\": \"CTO\", \"location\": {\"name\": \"" + location + "\"}, \"absolute_url\": \"https://sentonix.example/jobs/1\", \"first_published\": \"" + TODAY + "T09:00:00Z\", \"content\": \"&lt;p&gt;Lead the team.&lt;/p&gt;\"}]}";
    }

    @Test void aBoardFoundForTheNameIsTakenOnlyWhenItNamesTheRecordsPlaceOrOfficers(@TempDir Path tmp) throws Exception {
        CareerSources.SEARCH = q -> q.equals("Sentonix careers jobs") ? List.of(new CareerSources.Hit("Sentonix - Jobs", "https://boards.greenhouse.io/sentonix")) : List.of();
        try {
            List<String> anchors = List.of("Arlington", "MA", "Quill", "Vane");
            assertEquals(List.of("Arlington", "MA", "Quill", "Vane"), CareerCompanies.anchors(new ObjectMapper().createObjectNode().put("city", "Arlington").put("region", "MA")
                    .set("officers", new ObjectMapper().createArrayNode().add("Rae Quill, executive officer and director").add("Tomas Vane, Jr., director"))));
            pages.put("https://boards-api.greenhouse.io/v1/boards/sentonix/jobs?content=true", greenhouse("Berlin, Germany"));
            CareerSources.Found f = CareerSources.openings("Sentonix", List.of("CTO"), anchors);
            assertNull(f.feed(), "a board whose postings name Berlin and nobody of the filing is a namesake's");
            assertEquals("a board found for its name (greenhouse:sentonix) names none of the record's city, state or officers in its postings, so it is not taken as its own", f.note());
            assertNotNull(CareerSources.openings("Sentonix", List.of("CTO")).feed(), "with no record to anchor to, the board is taken as before");
            pages.put("https://boards-api.greenhouse.io/v1/boards/sentonix/jobs?content=true", greenhouse("Arlington, MA"));
            assertEquals("greenhouse:sentonix", CareerSources.openings("Sentonix", List.of("CTO"), anchors).feed().label(), "a posting in the record's city: the board is its own");

            // a board kept on the list from before the rule, with the site and the size that came from it: dropped when it names none of the record's words
            ObjectNode c = new ObjectMapper().createObjectNode().put("name", "Sentonix").put("site", "sentonix.example").put("site_at", TODAY);
            c.putObject("feed").put("kind", "greenhouse").put("token", "sentonix").put("how", "found in a search for its careers page");
            c.putObject("people").put("low", 1001).put("high", 5000).put("source", "its own site, sentonix.example").put("at", TODAY);
            c.putArray("anchors").add("Arlington").add("MA").add("Quill");
            pages.put("https://boards-api.greenhouse.io/v1/boards/sentonix/jobs?content=true", greenhouse("Berlin, Germany"));
            CareerSources.Found g = CareerFind.readCompany(c, CUT);
            assertNull(g.feed()); assertFalse(c.has("feed") || c.has("site") || c.has("people"), "the namesake's board, site and size are gone");
            c.putObject("feed").put("kind", "greenhouse").put("token", "sentonix").put("how", "found in a search for its careers page");
            c.putObject("people").put("low", 12).put("high", 12).put("source", "its company card, third-party, as of " + TODAY).put("at", TODAY);
            CareerFind.readCompany(c, CUT);
            assertEquals(12, c.path("people").path("low").asInt(), "a size from its card is the company's, whatever the board");
        } finally { CareerSources.SEARCH = CareerSources::search; }
    }

    @Test void withNoPlaceDecidedTheSetSaysSoAndNothingIsRead(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = seeker(store, tmp);
        CareerCut.save(store, s, Cut.NONE);
        assertEquals(0, CareerCompanies.refresh(store, s, Cut.NONE, () -> false));
        assertTrue(asked.isEmpty());
        assertEquals("no place decided: the company sources need one", CareerCompanies.kept(store, s).path("notes").get(0).asText());
        assertTrue(CareerCompanies.text(store, s, Cut.NONE, "/career", 8).startsWith("The library has not gathered the employers of your place yet."));
        assertTrue(CareerCompaniesPage.body(store, s, true).contains("It needs a place"));
    }
}
