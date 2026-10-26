package org.researchzosho.records;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The collections a dossier searches (0.6.6): registers, filings, the scholarly and the public record, by field; the keyless ones usable at once. */
class PeopleAndOrganizationSourcesTest {

    private static RecordSource builtIn(String id) { return RecordSources.all(null).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(); }

    @Test
    void theCollectionsBelongToTheirFields() {
        List<RecordSource> all = RecordSources.all(null);
        List<String> person = RecordSources.forFields(all, List.of("person")).stream().map(RecordSource::id).toList();
        for (String id : new String[]{"wikidata", "openalex-authors", "orcid", "edgar-fulltext", "fec-contributions", "diet-record", "diet-speaker", "hacker-news", "bluesky-accounts", "courtlistener", "epo-inventor", "internet-archive", "opensanctions", "nsf-awards-people"}) assertTrue(person.contains(id), id);
        assertFalse(person.contains("gleif")); assertFalse(person.contains("propublica-nonprofits")); assertFalse(person.contains("ecfr"));
        List<String> org = RecordSources.forFields(all, List.of("organization")).stream().map(RecordSource::id).toList();
        for (String id : new String[]{"gleif", "propublica-nonprofits", "edgar-fulltext", "senate-lobbying", "openfda-devices", "nsf-awards", "college-scorecard", "gbizinfo", "houjin-bangou", "opencorporates", "crt-sh", "diet-record", "epo-applicant", "google-patents", "github-repos", "opensanctions"}) assertTrue(org.contains(id), id);
        assertFalse(org.contains("orcid")); assertFalse(org.contains("epo-inventor"));
        List<String> links = RecordSources.links(null).stream().map(RecordSources.SearchLink::id).toList();
        for (String id : new String[]{"shibusawa-shashi", "edinet", "usaspending", "uspto-assignments", "uspto-trademarks", "isfdb", "copyright-catalog", "kanpou", "hathitrust", "chm-oral-histories"}) assertTrue(links.contains(id), id);
        assertTrue(person.contains("google-books") && org.contains("google-books"), "the books are where the people and the stories are");
        assertTrue(person.contains("open-library-author") && !org.contains("open-library-author"), "a writer's complete works, for a person");
        assertFalse(builtIn("open-library-author").needsKey());
    }

    @Test
    void theKeylessOnesWorkAtOnceAndTheKeyedOnesSayWhereTheKeyComesFrom() {
        for (String id : new String[]{"openalex-authors", "openalex-works", "orcid", "gleif", "propublica-nonprofits", "edgar-fulltext", "senate-lobbying", "openfda-devices", "hacker-news", "bluesky-accounts", "nsf-awards", "diet-record", "crt-sh", "qiita", "google-books"})
            assertFalse(builtIn(id).needsKey(), id + " needs no key");
        for (String id : new String[]{"gbizinfo", "houjin-bangou", "opensanctions", "opencorporates", "college-scorecard", "fec-contributions"}) {
            assertTrue(builtIn(id).needsKey(), id); assertTrue(builtIn(id).keyFrom().startsWith("https://"), id + " says where");
        }
        assertEquals("RESEARCHZOSHO_DATAGOV_KEY", builtIn("fec-contributions").keyName(), "one api.data.gov key serves the FEC, the Scorecard and Regulations.gov");
        assertEquals("RESEARCHZOSHO_DATAGOV_KEY", builtIn("college-scorecard").keyName());
        assertEquals("X-hojinInfo-api-token: {key}", builtIn("gbizinfo").keyHeader());
        assertEquals("xml", builtIn("houjin-bangou").format());
    }

    @Test
    void theAddressesCarryTheQueryAndTheYears() {
        assertEquals("https://efts.sec.gov/LATEST/search-index?q=%22Lamina1%22&dateRange=custom&startdt=2022-01-01&enddt=2024-12-31", RecordSources.address(builtIn("edgar-fulltext"), "\"Lamina1\"", 2022, 2024, 5));
        assertEquals("https://api.gleif.org/api/v1/lei-records?filter%5Bfulltext%5D=Cisco+Systems&page%5Bsize%5D=5", RecordSources.address(builtIn("gleif"), "Cisco Systems", 0, 0, 5), "the brackets in GLEIF's parameters are not the optional parts of a template");
        assertEquals("https://kokkai.ndl.go.jp/api/speech?speaker=%E9%AB%98%E5%B8%82%E6%97%A9%E8%8B%97&maximumRecords=3&recordPacking=json&from=2025-01-01", RecordSources.address(builtIn("diet-speaker"), "高市早苗", 2025, 0, 3));
        assertEquals("https://www.googleapis.com/books/v1/volumes?q=Cisco+Systems+founders&maxResults=5", RecordSources.address(builtIn("google-books"), "Cisco Systems founders", 0, 0, 5), "without a key the key part is left out");
        assertTrue(builtIn("google-books").keyOptional(), "Google Books answers without a key until the shared allowance runs out");
    }
}
