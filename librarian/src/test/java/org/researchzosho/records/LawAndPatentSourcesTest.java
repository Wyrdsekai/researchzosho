package org.researchzosho.records;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The law and patent collections (0.6.6): they belong to their fields, their addresses carry the query and the years, and their answers
 * read as hits with a citation, each tried against the shape its server answered with on 2026-10-22.
 */
class LawAndPatentSourcesTest {

    static RecordSource builtIn(String id) {
        return RecordSources.all(null).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError("no built-in source " + id));
    }

    static List<RecordSources.Hit> hits(String id, String query, String body) throws Exception {
        return RecordSources.search(builtIn(id), query, 0, 0, 5, url -> body);
    }

    @Test
    void theCollectionsBelongToTheirFields() {
        List<RecordSource> all = RecordSources.all(null);
        List<String> law = RecordSources.forFields(all, List.of("law")).stream().map(RecordSource::id).toList();
        for (String id : new String[]{"courtlistener", "ecfr", "federal-register", "regulations-gov", "egov-laws", "egov-keyword"}) assertTrue(law.contains(id), id);
        assertFalse(law.contains("github-repos")); assertFalse(law.contains("google-patents"));
        List<String> patents = RecordSources.forFields(all, List.of("patents")).stream().map(RecordSource::id).toList();
        for (String id : new String[]{"google-patents", "epo-query", "epo-applicant", "epo-inventor", "serpapi-patents"}) assertTrue(patents.contains(id), id);
        assertFalse(patents.contains("courtlistener"));
        List<String> genealogy = RecordSources.forFields(all, List.of("genealogy")).stream().map(RecordSource::id).toList();
        assertTrue(genealogy.contains("epo-inventor") && genealogy.contains("google-patents"), "the inventor collections still serve family history");
        List<String> links = RecordSources.links(null).stream().map(RecordSources.SearchLink::id).toList();
        for (String id : new String[]{"hanrei-jp", "ppubs", "jplatpat", "patentscope"}) assertTrue(links.contains(id), id);
        assertTrue(builtIn("courtlistener").keyOptional(), "CourtListener answers without a token");
        assertFalse(builtIn("regulations-gov").keyOptional(), "Regulations.gov needs its key");
        assertEquals(8, builtIn("serpapi-patents").perRun(), "a collection with 250 searches a month is searched at most eight times in one run");
        assertEquals(8, builtIn("serpapi-inventor").perRun());
        assertFalse(builtIn("google-patents").hasAllowance() || builtIn("epo-query").hasAllowance(), "the keyless and the registered collections have no such cap");
    }

    @Test
    void theAddressesCarryTheQueryAndTheYears() {
        assertEquals("https://www.courtlistener.com/api/rest/v4/search/?type=o&q=benford+law&filed_after=01%2F01%2F2020&filed_before=12%2F31%2F2024",
                RecordSources.address(builtIn("courtlistener"), "benford law", 2020, 2024, 5));
        assertEquals("https://www.ecfr.gov/api/search/v1/results?query=forensic+audit&per_page=5", RecordSources.address(builtIn("ecfr"), "forensic audit", 0, 0, 5));
        assertEquals("https://www.federalregister.gov/api/v1/documents.json?conditions%5Bterm%5D=forensic+audit&per_page=5&conditions%5Bpublication_date%5D%5Bgte%5D=2020-01-01",
                RecordSources.address(builtIn("federal-register"), "forensic audit", 2020, 0, 5));
        assertEquals("https://laws.e-gov.go.jp/api/2/laws?law_title=%E7%89%B9%E8%A8%B1&limit=5", RecordSources.address(builtIn("egov-laws"), "\"特許\"", 0, 0, 5), "the quotes come off");
        assertEquals("https://patents.google.com/xhr/query?url=q%3Dsolid+state+battery%26num%3D5&exp=", RecordSources.address(builtIn("google-patents"), "solid state battery", 0, 0, 5));
    }

    @Test
    void theAnswersReadAsHitsWithACitation() throws Exception {
        var cl = hits("courtlistener", "benford", """
            {"count":1017,"results":[{"absolute_url":"/opinion/1359532/united-states-v-benford/","caseName":"United States v. Benford","citation":["574 F.3d 1228","2009 WL 2357774"],
              "cluster_id":1359532,"court":"Court of Appeals for the Ninth Circuit","court_id":"ca9","dateFiled":"2009-08-03","opinions":[{"snippet":"the district court held…"}]}]}""");
        assertEquals(1, cl.size());
        assertEquals("United States v. Benford", cl.get(0).title()); assertEquals("https://www.courtlistener.com/opinion/1359532/united-states-v-benford/", cl.get(0).link());
        assertEquals("United States v. Benford, 574 F.3d 1228, 2009 WL 2357774, Court of Appeals for the Ninth Circuit, 2009-08-03, CourtListener", cl.get(0).where());
        assertEquals("the district court held…", cl.get(0).snippet());

        var cfr = hits("ecfr", "forensic", """
            {"results":[{"starts_on":"2022-11-07","type":"Section","hierarchy":{"title":"48","part":"1252","section":"1252.239-76"},
              "hierarchy_headings":{"title":"Title 48","part":"Part 1252","section":"1252.239-76"},"headings":{"title":"Federal Acquisition Regulations System","part":"Solicitation Provisions","section":"Cloud computing services."},
              "full_text_excerpt":"…forensic audit…"}]}""");
        assertEquals("1252.239-76 Cloud computing services.", cfr.get(0).title());
        assertEquals("https://www.ecfr.gov/current/title-48/section-1252.239-76", cfr.get(0).link());
        assertEquals("Federal Acquisition Regulations System, Solicitation Provisions, 1252.239-76, in force since 2022-11-07, eCFR", cfr.get(0).where());

        var fr = hits("federal-register", "forensic", """
            {"count":486,"results":[{"title":"Agency Information Collection Activities","type":"Notice","abstract":"The Department of Justice…","document_number":"2026-12345",
              "html_url":"https://www.federalregister.gov/documents/2026/10/20/2026-12345/agency","publication_date":"2026-10-20","agencies":[{"name":"Justice Department"}]}]}""");
        assertEquals("Notice 2026-12345, Justice Department, Federal Register 2026-10-20", fr.get(0).where());
        assertEquals("https://www.federalregister.gov/documents/2026/10/20/2026-12345/agency", fr.get(0).link());

        var laws = hits("egov-laws", "特許", """
            {"total_count":21,"laws":[{"law_info":{"law_type":"Act","law_id":"334AC0000000121","law_num":"昭和三十四年法律第百二十一号","promulgation_date":"1959-04-13"},
              "revision_info":{"law_title":"特許法","category":"産業通則","amendment_enforcement_date":"2026-06-24"}}]}""");
        assertEquals("特許法", laws.get(0).title()); assertEquals("https://laws.e-gov.go.jp/law/334AC0000000121", laws.get(0).link());
        assertEquals("特許法（昭和三十四年法律第百二十一号）, 公布 1959-04-13, 改正施行 2026-06-24, e-Gov法令検索", laws.get(0).where());

        var kw = hits("egov-keyword", "実施権", """
            {"total_count":630,"items":[{"law_info":{"law_id":"322AC0000000054","law_num":"昭和二十二年法律第五十四号","promulgation_date":"1947-04-14"},
              "revision_info":{"law_title":"私的独占の禁止及び公正取引の確保に関する法律"},"sentences":[{"position":"mainprovision","text":"…専用<span>実施権</span>…"}]}]}""");
        assertEquals("私的独占の禁止及び公正取引の確保に関する法律", kw.get(0).title());
        assertTrue(kw.get(0).snippet().contains("実施権"), kw.get(0).snippet());
        assertEquals("私的独占の禁止及び公正取引の確保に関する法律（昭和二十二年法律第五十四号）, mainprovision, e-Gov法令検索", kw.get(0).where());

        var gp = hits("google-patents", "benford", """
            {"results":{"total_num_results":85,"cluster":[{"result":[{"id":"patent/US12450652B1/en","rank":0,"patent":{"title":" Parameter-based computer evaluation of user accounts","snippet":" A user account evaluation system…",
              "priority_date":"2015-12-28","filing_date":"2022-07-26","grant_date":"2025-10-21","publication_date":"2025-10-21","inventor":"William Hockey","assignee":"Plaid Inc.","publication_number":"US12450652B1"}}]}]}}""");
        assertEquals("Parameter-based computer evaluation of user accounts", gp.get(0).title());
        assertEquals("https://patents.google.com/patent/US12450652B1/en", gp.get(0).link());
        assertEquals("US12450652B1, Plaid Inc., William Hockey, filed 2022-07-26, published 2025-10-21, Google Patents", gp.get(0).where());
        assertEquals(0, hits("google-patents", "nothing", "{\"results\":{\"total_num_results\":0,\"cluster\":[{\"result\":[]}]}}").size());
    }
}
