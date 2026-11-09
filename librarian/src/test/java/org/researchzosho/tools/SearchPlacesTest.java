package org.researchzosho.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search places of 0.7.0 against stand-in servers: PubMed and PubMed Central through the E-utilities (search, the open articles' full
 * text, the abstract alone), Semantic Scholar (and its busy pool), Exa and Tavily (their keys, the month's limit, the dollars), the keeper's
 * own search service (its shapes), and Exa first in the web search. Nothing here reaches the web or reads the box's settings.
 */
class SearchPlacesTest {
    static final ObjectMapper M = new ObjectMapper();
    HttpServer server;
    String base, realHome;
    final List<String[]> seen = new CopyOnWriteArrayList<>();   // method, path and query, User-Agent, the key headers, the body
    final Map<String, String> savedPubmed = new HashMap<>(), savedS2 = new HashMap<>(), savedExa = new HashMap<>(), savedTavily = new HashMap<>(), savedGoogle = new HashMap<>();
    long gapFree, gapKeyed, gapS2;
    long[] busyWas;
    final AtomicInteger s2Busy = new AtomicInteger();   // how many 429s Semantic Scholar answers before it answers
    volatile int exaStatus = 200;

    static final String ESEARCH_PUBMED = "{\"header\":{\"type\":\"esearch\"},\"esearchresult\":{\"count\":\"1041\",\"retmax\":\"2\",\"retstart\":\"0\",\"idlist\":[\"39000001\",\"39000002\"]}}";
    static final String ESEARCH_PMC = "{\"esearchresult\":{\"count\":\"11495\",\"idlist\":[\"9000002\",\"9000003\"]}}";
    static final String ESUMMARY_PMC = "{\"result\":{\"uids\":[\"9000002\",\"9000003\"],"
            + "\"9000002\":{\"uid\":\"9000002\",\"title\":\"Metformin versus insulin in gestational diabetes: a randomised trial\",\"source\":\"J Invent Med\",\"pubdate\":\"2024 Apr 3\",\"authors\":[{\"name\":\"Endo A\"}],"
            + "\"articleids\":[{\"idtype\":\"pmid\",\"value\":\"39000002\"},{\"idtype\":\"pmcid\",\"value\":\"PMC9000002\"},{\"idtype\":\"doi\",\"value\":\"10.9999/jim.2024.002\"}]},"
            + "\"9000003\":{\"uid\":\"9000003\",\"title\":\"A preprint held only in PubMed Central\",\"source\":\"Invented Preprints\",\"pubdate\":\"2025\",\"authors\":[],\"articleids\":[{\"idtype\":\"pmid\",\"value\":\"0\"},{\"idtype\":\"pmcid\",\"value\":\"PMC9000003\"}]}}}";
    static final String PUBMED_HEAD = "<?xml version=\"1.0\" ?>\n<!DOCTYPE PubmedArticleSet PUBLIC \"-//NLM//DTD PubMedArticle, 1st January 2025//EN\" \"https://dtd.nlm.nih.gov/ncbi/pubmed/out/pubmed_250101.dtd\">\n<PubmedArticleSet>";
    static final String ART_2 = "<PubmedArticle><MedlineCitation Status=\"MEDLINE\"><PMID Version=\"1\">39000002</PMID><Article><Journal><JournalIssue><PubDate><Year>2024</Year></PubDate></JournalIssue>"
            + "<Title>Journal of Invented Medicine</Title><ISOAbbreviation>J Invent Med</ISOAbbreviation></Journal><ArticleTitle>Metformin versus insulin in gestational diabetes: a <i>randomised</i> trial.</ArticleTitle>"
            + "<Abstract><AbstractText Label=\"BACKGROUND\">Metformin is used&#160;in pregnancy.</AbstractText><AbstractText Label=\"RESULTS\">Fewer neonatal hypoglycaemia events (&lt;5%).</AbstractText></Abstract>"
            + "<AuthorList><Author><LastName>Endo</LastName><Initials>A</Initials></Author><Author><CollectiveName>The Invented Trial Group</CollectiveName></Author></AuthorList>"
            + "<PublicationTypeList><PublicationType>Journal Article</PublicationType><PublicationType>Randomized Controlled Trial</PublicationType><PublicationType>Research Support, Non-U.S. Gov't</PublicationType></PublicationTypeList>"
            + "<ELocationID EIdType=\"doi\">10.9999/jim.2024.002</ELocationID></Article></MedlineCitation>"
            + "<PubmedData><ArticleIdList><ArticleId IdType=\"pubmed\">39000002</ArticleId><ArticleId IdType=\"pmc\">PMC9000002</ArticleId><ArticleId IdType=\"doi\">10.9999/jim.2024.002</ArticleId></ArticleIdList>"
            + "<ReferenceList><Reference><Citation>Old.</Citation><ArticleIdList><ArticleId IdType=\"pmc\">PMC1111111</ArticleId></ArticleIdList></Reference></ReferenceList></PubmedData></PubmedArticle>";
    /** An article whose PubMed Central copy its publisher keeps from programs (efetch gives the front alone for 9000009). */
    static final String ART_1 = "<PubmedArticle><MedlineCitation><PMID>39000001</PMID><Article><Journal><JournalIssue><PubDate><MedlineDate>2019 Jan-Feb</MedlineDate></PubDate></JournalIssue><Title>Invented Reviews</Title></Journal>"
            + "<ArticleTitle>Oral agents in pregnancy: a review.</ArticleTitle><Abstract><AbstractText>A review of oral agents.</AbstractText></Abstract>"
            + "<AuthorList><Author><LastName>Hale</LastName><Initials>R</Initials></Author><Author><LastName>Ellis</LastName><Initials>T</Initials></Author><Author><LastName>Morita</LastName><Initials>K</Initials></Author><Author><LastName>Takahashi</LastName><Initials>Y</Initials></Author></AuthorList>"
            + "<PublicationTypeList><PublicationType>Review</PublicationType></PublicationTypeList></Article></MedlineCitation>"
            + "<PubmedData><ArticleIdList><ArticleId IdType=\"pubmed\">39000001</ArticleId><ArticleId IdType=\"pmc\">PMC9000009</ArticleId><ArticleId IdType=\"doi\">10.9999/ir.2019.1</ArticleId></ArticleIdList></PubmedData></PubmedArticle>";

    /** efetch's answer for these ids, in its own order (not the search's). */
    static String pubmedXml(String ids) {
        StringBuilder b = new StringBuilder(PUBMED_HEAD);
        if (ids.contains("39000002")) b.append(ART_2);
        if (ids.contains("39000001")) b.append(ART_1);
        return b.append("</PubmedArticleSet>").toString();
    }
    static final String JATS = "<?xml version=\"1.0\"  ?><!DOCTYPE pmc-articleset PUBLIC \"-//NLM//DTD ARTICLE SET 2.0//EN\" \"https://dtd.nlm.nih.gov/ncbi/pmc/articleset/nlm-articleset-2.0.dtd\">"
            + "<pmc-articleset><article xmlns:xlink=\"http://www.w3.org/1999/xlink\" article-type=\"research-article\"><front><journal-meta><journal-id journal-id-type=\"nlm-ta\">J Invent Med</journal-id>"
            + "<journal-title-group><journal-title>Journal of Invented Medicine</journal-title></journal-title-group></journal-meta><article-meta>"
            + "<article-id pub-id-type=\"pmcid\">PMC9000002</article-id><article-id pub-id-type=\"pmid\">39000002</article-id><article-id pub-id-type=\"doi\">10.9999/jim.2024.002</article-id>"
            + "<title-group><article-title>Metformin versus insulin in gestational diabetes: a randomised trial</article-title></title-group>"
            + "<contrib-group><contrib contrib-type=\"author\"><name><surname>Endo</surname><given-names>A</given-names></name></contrib><contrib contrib-type=\"author\"><name><surname>Hale</surname><given-names>R M</given-names></name></contrib></contrib-group>"
            + "<pub-date pub-type=\"epub\"><day>3</day><month>4</month><year>2024</year></pub-date><volume>12</volume><elocation-id>e101</elocation-id>"
            + "<permissions><license xlink:href=\"https://creativecommons.org/licenses/by/4.0/\"><license-p>Open under CC BY.</license-p></license></permissions>"
            + "<abstract><sec><title>Background</title><p>Metformin is used in pregnancy.</p></sec></abstract></article-meta></front>"
            + "<body><sec id=\"s1\"><title>1. Introduction</title><p>Gestational diabetes affects 10<sup>5</sup> pregnancies&nbsp;each year <xref ref-type=\"bibr\" rid=\"r1\">[1]</xref>.</p>"
            + "<p>Earlier trials<sup><xref ref-type=\"bibr\" rid=\"r2\">2</xref></sup> were small.<table-wrap id=\"t0\"><label>Table 0</label><caption><p>Inside a paragraph.</p></caption><table><tr><td>x</td><td>y</td></tr></table></table-wrap></p></sec>"
            + "<sec id=\"s2\"><title>2. Methods</title><sec id=\"s21\"><title>2.1. Participants</title><p>We enrolled 400 women.</p><list list-type=\"bullet\"><list-item><p>Aged 18 or over</p></list-item><list-item><p>Singleton pregnancy</p></list-item></list></sec></sec>"
            + "<sec id=\"s3\"><title>3. Results</title><fig id=\"f1\"><label>Figure 1</label><caption><title>Glucose by week.</title><p>Means and ranges.</p></caption><graphic xlink:href=\"f1.jpg\"/></fig>"
            + "<table-wrap id=\"t1\"><label>Table 1</label><caption><title>Outcomes.</title></caption><table><thead><tr><th>Outcome</th><th>Metformin</th><th>Insulin</th></tr></thead>"
            + "<tbody><tr><td>Hypoglycaemia</td><td>4%</td><td>9%</td></tr></tbody></table><table-wrap-foot><p>Values are shares of births.</p></table-wrap-foot></table-wrap></sec></body>"
            + "<back><ack><p>We thank the invented clinics.</p></ack><ref-list><title>References</title>"
            + "<ref id=\"r1\"><label>1</label><mixed-citation publication-type=\"journal\"><person-group person-group-type=\"author\"><name><surname>Ellis</surname> <given-names>T</given-names></name></person-group>. "
            + "<article-title>A first study</article-title>. <source>J Invent Med</source>. <year>2019</year>;<volume>3</volume>:<fpage>1</fpage>–<lpage>9</lpage>.</mixed-citation></ref>"
            + "<ref id=\"r2\"><label>2.</label><element-citation publication-type=\"journal\"><person-group person-group-type=\"author\"><name><surname>Morita</surname><given-names>K</given-names></name></person-group>"
            + "<article-title>A second study</article-title><source>Invented Rev</source><year>2021</year><volume>5</volume><fpage>10</fpage><lpage>20</lpage><pub-id pub-id-type=\"doi\">10.9999/ir.5.10</pub-id></element-citation></ref>"
            + "</ref-list></back></article></pmc-articleset>";
    /** What efetch gives for an article whose publisher keeps the text from programs: the front alone, and a comment saying so. */
    static final String JATS_FRONT_ONLY = "<?xml version=\"1.0\"?><pmc-articleset><article><!--The publisher of this article does not allow downloading of the full text in XML form.--><front><article-meta>"
            + "<article-id pub-id-type=\"doi\">10.9999/closed.1</article-id><title-group><article-title>A closed article</article-title></title-group>"
            + "<abstract><p>Only this abstract is given out.</p></abstract></article-meta></front></article></pmc-articleset>";

    @BeforeEach void stand(@TempDir Path tmp) throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
        Config.invalidate();
        SearchTally.override = tmp.resolve("tally.json");
        savedPubmed.putAll(PubMed.BASE); savedS2.putAll(SemanticScholarTool.BASE); savedExa.putAll(ExaSearchTool.BASE); savedTavily.putAll(TavilySearchTool.BASE); savedGoogle.putAll(WebSearchTool.GOOGLE);
        gapFree = PubMed.GAP_FREE; gapKeyed = PubMed.GAP_KEYED; gapS2 = SemanticScholarTool.GAP; busyWas = SemanticScholarTool.BUSY_WAITS;
        PubMed.GAP_FREE = 0; PubMed.GAP_KEYED = 0; SemanticScholarTool.GAP = 0; SemanticScholarTool.BUSY_WAITS = new long[]{1, 1};
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/eutils/", x -> {
            String q = x.getRequestURI().getRawQuery(), path = x.getRequestURI().getPath();
            note(x, "");
            String db = param(q, "db");
            String term = param(q, "term");
            if (path.endsWith("/esearch.fcgi") && term.contains("[uid]")) send(x, 200, "{\"esearchresult\":{\"count\":\"1\",\"idlist\":[" + (term.contains("9000002[uid]") ? "\"9000002\"" : "") + "]}}");   // which copies are open
            else if (path.endsWith("/esearch.fcgi")) send(x, 200, db.equals("pmc") ? ESEARCH_PMC : ESEARCH_PUBMED);
            else if (path.endsWith("/esummary.fcgi")) send(x, 200, ESUMMARY_PMC);
            else if (path.endsWith("/efetch.fcgi") && db.equals("pubmed")) send(x, 200, pubmedXml(param(q, "id")));
            else if (path.endsWith("/efetch.fcgi") && db.equals("pmc")) send(x, 200, param(q, "id").equals("9000009") ? JATS_FRONT_ONLY : JATS);
            else send(x, 404, "{}");
        });
        server.createContext("/s2/search", x -> {
            note(x, "");
            if (s2Busy.getAndDecrement() > 0) { send(x, 429, "{\"message\": \"Too Many Requests.\", \"code\": \"429\"}"); return; }
            send(x, 200, "{\"total\": 1234, \"offset\": 0, \"data\": [{\"paperId\": \"abc\", \"title\": \"Retrieval augmented generation reduces hallucination\", \"abstract\": \"We show that retrieval reduces it.\","
                    + "\"year\": 2023, \"venue\": \"Invented Conference\", \"citationCount\": 1234, \"openAccessPdf\": {\"url\": \"https://arxiv.org/pdf/2301.00001\", \"status\": \"GREEN\"},"
                    + "\"externalIds\": {\"DOI\": \"10.9999/ic.2023.1\", \"ArXiv\": \"2301.00001\"}, \"url\": \"https://www.semanticscholar.org/paper/abc\", \"authors\": [{\"name\": \"T Ellis\"}, {\"name\": \"R Hale\"}], \"publicationTypes\": [\"JournalArticle\", \"Conference\"]},"
                    + "{\"paperId\": \"def\", \"title\": \"A paper with no DOI\", \"externalIds\": {\"ArXiv\": \"2302.00002\"}, \"year\": 2023, \"citationCount\": 1, \"openAccessPdf\": {\"url\": \"\", \"status\": null}, \"authors\": []}]}");
        });
        server.createContext("/exa/search", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            note(x, body);
            if (exaStatus != 200) { send(x, exaStatus, "{\"error\": \"no\"}"); return; }
            send(x, 200, "{\"requestId\": \"r1\", \"results\": [{\"title\": \"Why small teams ship faster\", \"url\": \"https://essays.example/small-teams\", \"publishedDate\": \"2025-03-01T00:00:00.000Z\","
                    + "\"author\": \"A Endo\", \"highlights\": [\"Small teams decide faster.\", \"Fewer handoffs.\"]}], \"costDollars\": {\"total\": 0.007}}");
        });
        server.createContext("/tavily/search", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            note(x, body);
            send(x, 200, "{\"query\": \"q\", \"results\": [{\"title\": \"Noise bylaw\", \"url\": \"https://town.example/bylaw\", \"content\": \"Heat pumps must stay under 45 dB at night.\", \"score\": 0.8, \"raw_content\": null,"
                    + "\"published_date\": \"Tue, 11 Mar 2025 17:00:00 GMT\"}], \"usage\": {\"credits\": 1}}");
        });
        server.createContext("/serper/search", x -> { note(x, ""); send(x, 200, "{\"organic\":[{\"title\":\"From Google\",\"link\":\"https://google-hit.example/a\",\"snippet\":\"a hit\"}],\"credits\":1}"); });
        server.createContext("/custom/list", x -> { note(x, ""); send(x, 200, "[{\"url\": \"https://wiki.home.example/onboarding\", \"title\": \"Onboarding\", \"content\": \"The checklist for a first week.\"}]"); });
        server.createContext("/custom/results", x -> { note(x, ""); send(x, 200, "{\"results\": [{\"link\": \"https://wiki.home.example/a\", \"name\": \"Page A\", \"raw_content\": \"Raw text of page A.\"}]}"); });
        server.createContext("/custom/items", x -> { note(x, ""); send(x, 200, "{\"kind\": \"customsearch#search\", \"items\": [{\"link\": \"https://www.example.org/x\", \"title\": \"X\", \"snippet\": \"A snippet of X.\"}]}"); });
        server.createContext("/custom/other", x -> { note(x, ""); send(x, 200, "{\"answer\": \"forty-two\"}"); });
        server.createContext("/custom/html", x -> { note(x, ""); send(x, 200, "<html><body>a search page</body></html>"); });
        server.start();
        PubMed.BASE.put("eutils", base + "/eutils");
        SemanticScholarTool.BASE.put("search", base + "/s2/search");
        ExaSearchTool.BASE.put("search", base + "/exa/search");
        TavilySearchTool.BASE.put("search", base + "/tavily/search");
        WebSearchTool.GOOGLE.put("serper", base + "/serper/search");
    }

    @AfterEach void back() {
        server.stop(0);
        PubMed.BASE.putAll(savedPubmed); SemanticScholarTool.BASE.putAll(savedS2); ExaSearchTool.BASE.putAll(savedExa); TavilySearchTool.BASE.putAll(savedTavily); WebSearchTool.GOOGLE.putAll(savedGoogle);
        PubMed.GAP_FREE = gapFree; PubMed.GAP_KEYED = gapKeyed; SemanticScholarTool.GAP = gapS2; SemanticScholarTool.BUSY_WAITS = busyWas;
        SearchTally.override = null;
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    void note(HttpExchange x, String body) {
        var h = x.getRequestHeaders();
        String keys = "x-api-key=" + h.getFirst("x-api-key") + " authorization=" + h.getFirst("Authorization") + " x-custom-key=" + h.getFirst("X-Custom-Key");
        seen.add(new String[]{x.getRequestMethod(), x.getRequestURI().getPath() + "?" + x.getRequestURI().getRawQuery(), String.valueOf(h.getFirst("User-Agent")), keys, body});
    }

    static String param(String rawQuery, String name) {
        if (rawQuery == null) return "";
        for (String kv : rawQuery.split("&")) { int i = kv.indexOf('='); if (i > 0 && kv.substring(0, i).equals(name)) return URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8); }
        return "";
    }

    static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", body.startsWith("<") ? "text/xml; charset=utf-8" : "application/json");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    List<String[]> to(String pathStart) { List<String[]> out = new ArrayList<>(); for (String[] s : seen) if (s[1].startsWith(pathStart)) out.add(s); return out; }

    // ---------------------------------------------------------------- PubMed

    @Test void pubmedFindsArticlesByBestMatchWithTheirAbstractsAndGivesTheFullTextsAddress() throws Exception {
        String out = new PubMedSearchTool().execute(M.createObjectNode().put("query", "metformin gestational diabetes").put("limit", 5));
        assertTrue(out.startsWith("results for \"metformin gestational diabetes\" (PubMed: 2 of 1041, best match first):"), out);
        int first = out.indexOf("1. Oral agents in pregnancy: a review."), second = out.indexOf("2. Metformin versus insulin in gestational diabetes: a randomised trial.");
        assertTrue(first > 0 && second > first, "in the search's order, not efetch's: " + out);
        assertTrue(out.contains("   https://pubmed.ncbi.nlm.nih.gov/39000001/  ["), "a copy its publisher keeps from programs: the PubMed record: " + out);
        assertTrue(out.contains("Invented Reviews, 2019 · Hale R, Ellis T, Morita K et al. · Review · the abstract only; a person can read the full text at https://pmc.ncbi.nlm.nih.gov/articles/PMC9000009/"), out);
        assertTrue(out.contains("   https://pmc.ncbi.nlm.nih.gov/articles/PMC9000002/  ["), "the open full text's address, not a reference's: " + out);
        assertTrue(out.contains("J Invent Med, 2024 · Endo A, The Invented Trial Group · Randomized Controlled Trial · the full text can be read at this address"), out);
        assertTrue(out.contains("Background: Metformin is used in pregnancy. Results: Fewer neonatal hypoglycaemia events (<5%)."), out);
        assertFalse(out.contains("PMC1111111"), "a reference's id is not the article's");
        List<String[]> calls = to("/eutils/");
        assertEquals(3, calls.size(), "esearch, efetch for the abstracts, and which PubMed Central copies are open");
        assertTrue(param(calls.get(2)[1].substring(calls.get(2)[1].indexOf('?') + 1), "term").equals("(9000009[uid] OR 9000002[uid]) AND (open access[filter] OR author manuscript[filter])"), calls.get(2)[1]);
        for (String[] c : calls) {
            assertTrue(c[1].contains("tool=researchzosho") && !c[1].contains("email") && !c[1].contains("api_key"), c[1]);
            assertTrue(c[2].contains("https://researchzosho.org") && !c[2].contains("@"), "the library's site, nobody's address: " + c[2]);
        }
        assertTrue(calls.get(0)[1].contains("db=pubmed") && calls.get(0)[1].contains("sort=relevance"), calls.get(0)[1]);
        assertEquals(1, SearchTally.thisMonth().get(PubMed.TALLY)[0], "one search counted");
        Config.set(PubMed.KEY, "ncbi-test-key");
        new PubMedSearchTool().execute(M.createObjectNode().put("query", "metformin").put("limit", 2));
        assertTrue(to("/eutils/").get(3)[1].contains("api_key=ncbi-test-key"), "the key, when set, goes to NCBI with each request");
    }

    @Test void fullTextSearchesPubMedCentralsOpenArticlesAndBringsTheAbstractsFromPubMed() throws Exception {
        String out = new PubMedSearchTool().execute(M.createObjectNode().put("query", "neonatal hypoglycaemia").put("full_text", true));
        assertTrue(out.startsWith("results for \"neonatal hypoglycaemia\" (PubMed Central, inside the open articles' full text: 2 of 11495, best match first):"), out);
        assertTrue(out.contains("   https://pmc.ncbi.nlm.nih.gov/articles/PMC9000003/  [") && out.contains("A preprint held only in PubMed Central"), "a PubMed Central article with no PubMed record: " + out);
        assertTrue(out.contains("Background: Metformin is used in pregnancy."), "the abstract came from PubMed: " + out);
        List<String[]> calls = to("/eutils/");
        assertEquals(3, calls.size(), "esearch, esummary, efetch");
        String term = param(calls.get(0)[1].substring(calls.get(0)[1].indexOf('?') + 1), "term");
        assertEquals("(neonatal hypoglycaemia) AND (open access[filter] OR author manuscript[filter])", term);
        assertTrue(calls.get(0)[1].contains("db=pmc") && calls.get(2)[1].contains("db=pubmed") && calls.get(2)[1].contains("id=39000002") && !calls.get(2)[1].contains("id=0"), "only the article with a PMID is fetched from PubMed");
    }

    @Test void aPubMedCentralAddressIsReadAsTheFullTextWithItsSections() throws Exception {
        PubMed.Article a = PubMed.jats(JATS);
        assertNotNull(a);
        String t = a.text();
        assertTrue(t.startsWith("# Metformin versus insulin in gestational diabetes: a randomised trial\n\nEndo A, Hale RM. J Invent Med 2024;12:e101. doi:10.9999/jim.2024.002. PMID 39000002. PMCID PMC9000002.\n\nLicence CC BY 4.0.\n"), t);
        assertTrue(t.contains("\n## Abstract\n\n### Background\n\nMetformin is used in pregnancy.\n"), t);
        assertTrue(t.contains("\n## 1. Introduction\n\nGestational diabetes affects 10^5 pregnancies each year [1].\n"), "a power and a named entity read: " + t);
        assertTrue(t.contains("Earlier trials [2] were small.\n\nTable 0. Inside a paragraph.\n| x | y |\n"), "a superscript citation in brackets; a table inside a paragraph after it: " + t);
        assertTrue(t.contains("\n## 2. Methods\n\n### 2.1. Participants\n\nWe enrolled 400 women.\n\n- Aged 18 or over\n- Singleton pregnancy\n"), t);
        assertTrue(t.contains("Figure 1. Glucose by week. Means and ranges.") && t.contains("Table 1. Outcomes.\n| Outcome | Metformin | Insulin |\n| Hypoglycaemia | 4% | 9% |\nValues are shares of births."), t);
        assertTrue(t.contains("## Acknowledgements\n\nWe thank the invented clinics.") && t.contains("## References\n\n1. Ellis T. A first study. J Invent Med. 2019;3:1–9.\n2. Morita K. A second study. Invented Rev. 2021;5:10–20. doi:10.9999/ir.5.10\n"), t);
        assertTrue(a.hasBody());
        PubMed.Article worded = PubMed.jats("<article><front><article-meta><title-group><article-title>T</article-title></title-group><permissions><license><license-p>This is an open access article under the terms of the "
                + "<ext-link ext-link-type=\"uri\" xlink:href=\"http://creativecommons.org/licenses/by-nc/4.0/\">http://creativecommons.org/licenses/by-nc/4.0/</ext-link> License.</license-p></license></permissions></article-meta></front><body><p>Text.</p></body></article>");
        assertEquals("licence CC BY-NC 4.0", worded.license(), "a licence given in words is read off its link");
        // read through the fetch, as open and web_fetch do: no page is fetched, the E-utilities are
        String out = new WebFetchTool().execute(M.createObjectNode().put("url", "https://pmc.ncbi.nlm.nih.gov/articles/PMC9000002/").put("find", "hypoglycaemia outcomes"));
        assertTrue(out.startsWith("The full text of this article, read from PubMed Central through NCBI's E-utilities; licence CC BY 4.0.\nsource: https://pmc.ncbi.nlm.nih.gov/articles/PMC9000002/ — Metformin versus insulin in gestational diabetes: a randomised trial [full text from PubMed Central]"), out);
        assertTrue(out.contains("Hypoglycaemia | 4% | 9%"), "the passage asked for: " + out);
        assertTrue(to("/eutils/").get(0)[1].contains("efetch.fcgi?db=pmc&id=9000002&tool=researchzosho"), to("/eutils/").get(0)[1]);
        assertEquals(1, SearchTally.thisMonth().get(PubMed.TALLY)[0], "the article read is a call in the tally");
        // the other spellings of an article's address
        assertEquals("9000002", PubMed.pmcOf("https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9000002/"));
        assertEquals("9000002", PubMed.pmcOf("https://europepmc.org/article/PMC/PMC9000002"));
        assertEquals("9000002", PubMed.pmcOf("https://pmc.ncbi.nlm.nih.gov/articles/PMC9000002/pdf/jim-12-e101.pdf"));
        assertEquals("39000002", PubMed.pmidOf("https://pubmed.ncbi.nlm.nih.gov/39000002/"));
        assertNull(PubMed.pmcOf("https://pmc.ncbi.nlm.nih.gov/about/"));
        assertFalse(PubMed.reads("https://example.org/PMC9000002"));
    }

    @Test void aPubMedRecordOpensAsTheArticleWhenPubMedCentralHasItAndAnArticleKeptFromProgramsGivesItsAbstract() throws Exception {
        String out = new WebFetchTool().execute(M.createObjectNode().put("url", "https://pubmed.ncbi.nlm.nih.gov/39000002/"));
        assertTrue(out.startsWith("PubMed's record of this article links to its full text in PubMed Central; this is that text, read through NCBI's E-utilities.\nsource: https://pmc.ncbi.nlm.nih.gov/articles/PMC9000002/"), out);
        String kept = new WebFetchTool().execute(M.createObjectNode().put("url", "https://pubmed.ncbi.nlm.nih.gov/39000001/"));
        assertTrue(kept.startsWith("PubMed's record of this article, read through NCBI's E-utilities: the abstract. PubMed Central keeps its full text from programs at the publisher's wish; a person can read it at "
                + "https://pmc.ncbi.nlm.nih.gov/articles/PMC9000009/. The article itself is on its publisher's page, https://doi.org/10.9999/ir.2019.1.\nsource: https://pubmed.ncbi.nlm.nih.gov/39000001/"), kept);
        assertTrue(kept.contains("A review of oral agents.") && kept.contains("Kind: Review."), kept);
        PubMed.Read closed = PubMed.readPmc("9000009");
        assertNotNull(closed);
        assertFalse(closed.full());
        assertEquals("Only the abstract: the publisher of this article does not let PubMed Central give its full text to programs; a person can read it at this address. The article itself is on its publisher's page, https://doi.org/10.9999/closed.1.\n", closed.firstLine());
        assertTrue(closed.text().contains("## Abstract\n\nOnly this abstract is given out."), closed.text());
        // NCBI not answering: the address is fetched as a page instead, and nothing is said to have been read
        PubMed.BASE.put("eutils", "http://127.0.0.1:9/eutils");
        assertNull(PubMed.read("https://pmc.ncbi.nlm.nih.gov/articles/PMC9000002/"));
    }

    @Test void requestsArePacedToNcbisRate() {
        PubMed.GAP_FREE = 350; PubMed.GAP_KEYED = 110;
        long now = System.currentTimeMillis() + 60_000;   // after anything earlier in the suite
        assertEquals(0, PubMed.slot(now, false));
        assertEquals(350, PubMed.slot(now, false), "three a second without a key");
        assertEquals(700, PubMed.slot(now, true));
        assertEquals(810, PubMed.slot(now, true), "ten a second with one");
        PubMed.nextAt = 0;   // the next test's requests go at once
    }

    // ---------------------------------------------------------------- Semantic Scholar

    @Test void semanticScholarGivesPapersWithTheirCitationsAndAFreePdfAndWaitsOutABusyPool() throws Exception {
        s2Busy.set(2);
        String out = new SemanticScholarTool().execute(M.createObjectNode().put("query", "retrieval augmented generation hallucination"));
        assertTrue(out.startsWith("results for \"retrieval augmented generation hallucination\" (Semantic Scholar: 2 of 1234):"), out);
        assertTrue(out.contains("1. Retrieval augmented generation reduces hallucination\n   https://doi.org/10.9999/ic.2023.1  [") && out.contains("   free PDF: https://arxiv.org/pdf/2301.00001\n"), out);
        assertTrue(out.contains("Invented Conference, 2023 · T Ellis, R Hale · cited 1,234 times · conference"), out);
        assertTrue(out.contains("2. A paper with no DOI\n   https://arxiv.org/abs/2302.00002  [") && !out.contains("free PDF: \n"), "no empty PDF line: " + out);
        assertEquals(3, to("/s2/search").size(), "two busy answers waited out, then the answer");
        assertTrue(to("/s2/search").get(0)[3].contains("x-api-key=null"), "no key unless set");
        s2Busy.set(5);
        String busy = new SemanticScholarTool().execute(M.createObjectNode().put("query", "anything"));
        assertTrue(busy.startsWith("ERROR: Semantic Scholar is busy:") && busy.contains("This is not a search that found nothing"), busy);
        s2Busy.set(0);
        Config.set(SemanticScholarTool.KEY, "s2-test-key");
        new SemanticScholarTool().execute(M.createObjectNode().put("query", "anything"));
        String[] last = to("/s2/search").get(to("/s2/search").size() - 1);
        assertTrue(last[3].contains("x-api-key=s2-test-key"), last[3]);
        assertEquals(2, SearchTally.thisMonth().get(SemanticScholarTool.TALLY)[0], "the two searches that answered are counted, the busy one is not");
    }

    // ---------------------------------------------------------------- Exa and Tavily

    @Test void exaNeedsAKeySearchesByMeaningCountsTheDollarsAndStopsAtTheMonthsLimit() throws Exception {
        String none = new ExaSearchTool().execute(M.createObjectNode().put("query", "why small teams ship faster"));
        assertEquals("Exa needs a key: the library's keeper gets one at https://dashboard.exa.ai/api-keys and sets it as exa_key in the settings.", none);
        assertTrue(to("/exa/").isEmpty(), "no call without a key");
        Config.set(ExaSearchTool.KEY, "exa-test-key");
        Config.set(ExaSearchTool.LIMIT, "2");
        String out = new ExaSearchTool().execute(M.createObjectNode().put("query", "why small teams ship faster"));
        assertTrue(out.startsWith("results for \"why small teams ship faster\" (Exa, the web by meaning):"), out);
        assertTrue(out.contains("https://essays.example/small-teams") && out.contains("published 2025-03-01 · by A Endo") && out.contains("Small teams decide faster. … Fewer handoffs."), out);
        String[] call = to("/exa/").get(0);
        assertTrue(call[3].contains("x-api-key=exa-test-key") && call[2].contains("researchzosho.org"), call[3]);
        assertTrue(call[4].contains("\"type\":\"auto\"") && call[4].contains("\"highlights\":{\"maxCharacters\":600}") && call[4].contains("\"moderation\":true"), "safe unless the person let the site list's content in: " + call[4]);
        assertEquals(0.007, SearchTally.dollarsThisMonth(ExaSearchTool.TALLY), 1e-9);
        assertTrue(ExaSearchTool.open());
        new ExaSearchTool().execute(M.createObjectNode().put("query", "a second search"));
        assertFalse(ExaSearchTool.open(), "two of two used");
        String used = new ExaSearchTool().execute(M.createObjectNode().put("query", "a third search"));
        assertEquals("This month's 2 searches through Exa are used, so Exa is left off until the month turns; the library's keeper can raise exa_monthly_limit in the settings.", used);
        assertEquals(2, to("/exa/").size(), "no call past the limit");
        assertEquals(0.014, SearchTally.dollarsThisMonth(ExaSearchTool.TALLY), 1e-9);
        assertTrue(SearchTally.summary().contains("2 searches through Exa (the library stops at 2 a month), $0.014"), SearchTally.summary());
        Config.set(ExaSearchTool.LIMIT, "");
        assertEquals(714, SearchTally.limit(ExaSearchTool.TALLY), "unset: what keeps Exa under $5 a month");
        exaStatus = 402;
        String broke = new ExaSearchTool().execute(M.createObjectNode().put("query", "a fourth search"));
        assertEquals("ERROR: the Exa account's credits are used up. This is not a search that found nothing: search the web instead.", broke);
        assertEquals(2, SearchTally.thisMonth().get(ExaSearchTool.TALLY)[0], "a refused call is not counted");
    }

    @Test void tavilyCountsItsCreditsAndHasTheFreeAllowanceAsItsLimit() throws Exception {
        assertEquals(1000, SearchTally.limit(TavilySearchTool.TALLY));
        assertTrue(new TavilySearchTool().execute(M.createObjectNode().put("query", "x")).startsWith("Tavily needs a key: the library's keeper gets one at https://app.tavily.com/"));
        Config.set(TavilySearchTool.KEY, "tvly-test-key");
        String out = new TavilySearchTool().execute(M.createObjectNode().put("query", "heat pump noise limits bylaw").put("limit", 3));
        assertTrue(out.startsWith("results for \"heat pump noise limits bylaw\" (Tavily):") && out.contains("https://town.example/bylaw") && out.contains("Heat pumps must stay under 45 dB at night."), out);
        String[] call = to("/tavily/").get(0);
        assertTrue(call[3].contains("authorization=Bearer tvly-test-key"), call[3]);
        assertTrue(call[4].contains("\"search_depth\":\"basic\"") && call[4].contains("\"max_results\":3") && call[4].contains("\"include_usage\":true"), call[4]);
        assertEquals(1, SearchTally.thisMonth().get(TavilySearchTool.TALLY)[1], "the credit Tavily charged");
    }

    @Test void theMonthsLimitHoldsWhenWorkersSearchAtOnce() throws Exception {
        Config.set(TavilySearchTool.LIMIT, "3");
        int tries = 12;
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger let = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < tries; i++) {
            Thread t = new Thread(() -> { try { go.await(); } catch (InterruptedException e) { return; } if (SearchTally.reserve(TavilySearchTool.TALLY)) { let.incrementAndGet(); SearchTally.settle(TavilySearchTool.TALLY, true, 1, 0); } });
            ts.add(t); t.start();
        }
        go.countDown();
        for (Thread t : ts) t.join(10_000);
        assertEquals(3, let.get(), "never past the limit");
        assertEquals(3, SearchTally.thisMonth().get(TavilySearchTool.TALLY)[0]);
        assertFalse(SearchTally.reserve(TavilySearchTool.TALLY));
    }

    @Test void exaFirstInTheWebSearchOnlyWhenTheKeeperSaysSoAndTheLadderCarriesOnWhenItFails() throws Exception {
        Config.set(ExaSearchTool.KEY, "exa-test-key");
        Config.set(WebSearchTool.SERPER_KEY, "serper-test-key");
        String plain = new WebSearchTool().execute(M.createObjectNode().put("query", "small teams"));
        assertTrue(plain.startsWith("results for \"small teams\" (Google, through Serper):"), "unset: the ladder is unchanged: " + plain);
        assertTrue(to("/exa/").isEmpty(), "and Exa is not asked");
        Config.set(WebSearchTool.WEB_FIRST, "exa");
        int before = WebSearchTool.FIRST_USED.get();
        String first = new WebSearchTool().execute(M.createObjectNode().put("query", "small teams"));
        assertTrue(first.startsWith("results for \"small teams\" (Exa, the web by meaning, first in this library's web search):"), first);
        assertEquals(before + 1, WebSearchTool.FIRST_USED.get());
        exaStatus = 500;
        String fell = new WebSearchTool().execute(M.createObjectNode().put("query", "large teams"));
        assertTrue(fell.startsWith("results for \"large teams\" (Google, through Serper):"), "a failing first rung hands on to the next: " + fell);
    }

    // ---------------------------------------------------------------- the keeper's own search service

    @Test void theKeepersOwnSearchServiceIsReadInTheCommonShapesAndSaysPlainlyWhenItIsNone() throws Exception {
        assertTrue(new CustomSearchTool().execute(M.createObjectNode().put("query", "x")).startsWith("No search service of the library's own is set"));
        Config.set(CustomSearchTool.URL, base + "/custom/list?q={q}&n={n}");
        Config.set(CustomSearchTool.KEY, "own-test-key");
        CustomSearchTool tool = new CustomSearchTool("intranet");
        String out = tool.execute(M.createObjectNode().put("query", "onboarding checklist").put("limit", 5));
        assertTrue(out.startsWith("results for \"onboarding checklist\" (intranet, the search service at 127.0.0.1 that the keeper set up for this library):"), out);
        assertTrue(out.contains("1. Onboarding\n   https://wiki.home.example/onboarding  [") && out.contains("The checklist for a first week."), out);
        String[] call = to("/custom/").get(0);
        assertTrue(call[1].equals("/custom/list?q=onboarding%20checklist&n=5"), call[1]);
        assertTrue(call[3].contains("authorization=Bearer own-test-key"), call[3]);
        Config.set(CustomSearchTool.HEADER, "X-Custom-Key");
        Config.set(CustomSearchTool.URL, base + "/custom/results");
        String results = tool.execute(M.createObjectNode().put("query", "page a"));
        assertTrue(results.contains("1. Page A\n   https://wiki.home.example/a  [") && results.contains("Raw text of page A."), results);
        String[] second = to("/custom/").get(1);
        assertTrue(second[1].equals("/custom/results?q=page%20a") && second[3].contains("x-custom-key=own-test-key") && second[3].contains("authorization=null"), second[1] + " " + second[3]);
        Config.set(CustomSearchTool.URL, base + "/custom/items?cx=1");
        assertTrue(tool.execute(M.createObjectNode().put("query", "x")).contains("1. X\n   https://www.example.org/x  ["), "Google's own shape, items with link and snippet");
        Config.set(CustomSearchTool.URL, base + "/custom/other");
        assertEquals("ERROR: the search service at 127.0.0.1 answered, but not in a shape the library reads. " + CustomSearchTool.SHAPES, tool.execute(M.createObjectNode().put("query", "x")));
        Config.set(CustomSearchTool.URL, base + "/custom/html");
        assertTrue(tool.execute(M.createObjectNode().put("query", "x")).contains("not in a shape the library reads"));
        Config.set(CustomSearchTool.URL, "http://127.0.0.1:9/search?q={q}");
        assertTrue(tool.execute(M.createObjectNode().put("query", "x")).startsWith("ERROR: the search service at 127.0.0.1 did not answer."));
        Config.set(CustomSearchTool.NAME, "My Intranet");
        assertEquals("my-intranet", CustomSearchTool.rawName());
    }
}
