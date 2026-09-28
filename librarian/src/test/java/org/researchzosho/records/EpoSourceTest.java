package org.researchzosho.records;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.researchzosho.Config;
/** A source that hands out a token for a key and a secret, answers "nothing found" with a 404, and writes its answer the way XML turned into JSON looks. */
class EpoSourceTest {

    private static final String ONE = """
        {"ops:world-patent-data": {"ops:biblio-search": {"@total-result-count": "1", "ops:search-result": {"exchange-documents":
          {"exchange-document": {"@country": "US", "@doc-number": "1599930", "@kind": "A", "bibliographic-data": {
             "publication-reference": {"document-id": [{"@document-id-type": "docdb", "country": {"$": "US"}, "doc-number": {"$": "1599930"}, "kind": {"$": "A"}, "date": {"$": "19260914"}},
                                                       {"@document-id-type": "epodoc", "doc-number": {"$": "US1599930"}, "date": {"$": "19260914"}}]},
             "invention-title": [{"$": "PREPARATION DE PAIN", "@lang": "fr"}, {"$": "Manufacture of bread", "@lang": "en"}],
             "parties": {"inventors": {"inventor": [{"@data-format": "epodoc", "inventor-name": {"name": {"$": "HALE ROBERT JR"}}}, {"@data-format": "original", "inventor-name": {"name": {"$": "Robert Hale, Jr."}}},
                                                   {"@data-format": "epodoc", "inventor-name": {"name": {"$": "ELLIS ANN"}}}]}}}}}}}}}""";

    private static RecordSource epo() { return RecordSources.all(Path.of("/nonexistent-record-sources.json")).stream().filter(s -> s.id().equals("epo-inventor")).findFirst().orElseThrow(); }

    @Test
    void aTokenIsFetchedOnceAndTheAnswerIsReadWhateverItsShape() throws Exception {
        List<String> seen = new ArrayList<>();
        RecordSources.Reader reader = new RecordSources.Reader() {
            @Override public String read(String url) { throw new AssertionError("headers expected"); }
            @Override public String read(String url, Map<String, String> headers) { seen.add("GET " + url + " " + headers.get("Authorization") + " " + headers.get("Accept")); return ONE; }
            @Override public String post(String url, Map<String, String> headers, String body) { seen.add("POST " + url + " " + headers.get("Authorization").substring(0, 6) + " " + body); return "{\"access_token\":\"tok-1\",\"expires_in\":\"1199\"}"; }
        };
        RecordSources.settings = k -> k.equals("RESEARCHZOSHO_EPO_KEY") ? "the-key" : k.equals("RESEARCHZOSHO_EPO_SECRET") ? "the-secret" : null;
        try {
            RecordSource epo = epo();
            assertTrue(RecordSources.usable(epo));
            List<RecordSources.Hit> hits = RecordSources.search(epo, "\"Robert Hale\"", 1900, 1930, 5, reader);
            RecordSources.search(epo, "Ann Ellis", 0, 0, 5, reader);
            assertEquals(1, seen.stream().filter(x -> x.startsWith("POST")).count(), "one token serves until it runs out: " + seen);
            assertEquals("POST https://ops.epo.org/3.2/auth/accesstoken Basic  grant_type=client_credentials", seen.get(0));
            assertTrue(seen.get(1).startsWith("GET https://ops.epo.org/3.2/rest-services/published-data/search/biblio?q=in%3D%22Robert+Hale%22%20and%20pd%3E%3D1900%20and%20pd%3C%3D1930&Range=1-5 Bearer tok-1 application/json"), seen.get(1));
            assertFalse(seen.get(2).contains("pd%3E"), "no years asked for, none in the address: " + seen.get(2));
            RecordSources.Hit h = hits.get(0);
            assertEquals("Manufacture of bread", h.title(), "the title in English, of the two it came in");
            assertEquals("1926-09-14", h.date());
            assertEquals("https://worldwide.espacenet.com/patent/search?q=pn%3DUS1599930", h.link());
            assertEquals("inventors: HALE ROBERT JR, ELLIS ANN", h.snippet(), "a name said twice, in two forms, is said once");
            assertTrue(h.where().startsWith("patent US 1599930 A, published 1926-09-14"), h.where());
        } finally { RecordSources.settings = Config::get; }
    }

    @Test
    void nothingFoundIsNotAFailureAndWithoutTheSecretTheSourceIsNotOffered() throws Exception {
        RecordSources.Reader none = new RecordSources.Reader() {
            @Override public String read(String url) { return ""; }
            @Override public String read(String url, Map<String, String> headers) { throw new IllegalStateException("HTTP 404"); }
            @Override public String post(String url, Map<String, String> headers, String body) { return "{\"access_token\":\"tok-2\",\"expires_in\":\"1199\"}"; }
        };
        RecordSources.settings = k -> k.equals("RESEARCHZOSHO_EPO_KEY") ? "the-key" : null;
        try {
            assertFalse(RecordSources.usable(epo()), "a key without its secret is not enough");
            RecordSources.settings = k -> k.equals("RESEARCHZOSHO_EPO_KEY") ? "the-key" : k.equals("RESEARCHZOSHO_EPO_SECRET") ? "the-secret" : null;
            assertTrue(RecordSources.search(epo(), "Nobody Atall", 0, 0, 5, none).isEmpty());
        } finally { RecordSources.settings = Config::get; }
    }
}
