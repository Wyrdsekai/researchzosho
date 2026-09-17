package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.tools.Fetch;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** A paper, a website and an issue tracker as starting points: read, described, filed, and the kind told from the thing. */
class SurveysTest {

    static final ObjectMapper M = new ObjectMapper();
    static Supplier<Researcher.Drive> drives;
    @BeforeAll static void noModel() { drives = Explain.DRIVES; Explain.DRIVES = () -> null; }
    @AfterAll static void restore() { Explain.DRIVES = drives; }

    private static void setLoopback(boolean v) throws Exception { var f = Fetch.class.getDeclaredField("allowLoopback"); f.setAccessible(true); f.set(null, v); }

    static ObjectNode patron(ObjectNode a) { a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli"); return a; }

    @Test
    void theKindIsToldFromTheThing(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("proj"));
        assertEquals(Surveys.Kind.repo, Surveys.detect(home.resolve("proj").toString()));
        assertEquals(Surveys.Kind.repo, Surveys.detect("https://github.com/someone/tidebook"));
        assertEquals(Surveys.Kind.repo, Surveys.detect("https://github.com/someone/tidebook.git"));
        assertEquals(Surveys.Kind.repo, Surveys.detect("git@github.com:someone/tidebook.git"));
        assertEquals(Surveys.Kind.issues, Surveys.detect("https://github.com/someone/tidebook/issues"));
        assertEquals(Surveys.Kind.issues, Surveys.detect("https://github.com/someone/tidebook/issues?q=is%3Aopen"));
        assertEquals(Surveys.Kind.issues, Surveys.detect(home.resolve("export.json").toString()));
        assertEquals(Surveys.Kind.paper, Surveys.detect("https://doi.org/10.1000/xyz123"));
        assertEquals(Surveys.Kind.paper, Surveys.detect("https://arxiv.org/abs/2401.00001"));
        assertEquals(Surveys.Kind.paper, Surveys.detect("https://example.org/papers/tides.pdf"));
        assertEquals(Surveys.Kind.paper, Surveys.detect(home.resolve("tides.pdf").toString()));
        assertEquals(Surveys.Kind.paper, Surveys.detect(home.resolve("notes.md").toString()));
        assertEquals(Surveys.Kind.site, Surveys.detect("https://example.org/product"));
    }

    @Test
    void aPaperIsReadAndItsClaimsAndOpenQuestionsAreParsed(@TempDir Path home) throws Exception {
        Path paper = home.resolve("tides.md");
        Files.writeString(paper, "# Kalman smoothing of harmonic tide constituents\n\nJ. Doe, 2021\n\nWe show that a Kalman smoother over the IHO constituents lowers the residual variance of predicted water levels by 31% at twelve harbours. The method rests on Pugh (1987) and the NOAA CO-OPS constituent tables.\n\n## Future work\n\nWhether the gain holds in estuaries is untested.\n\n## References\n\n1. Pugh, D. T. (1987). Tides, Surges and Mean Sea-Level.\n");
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Surveys.Read r = Surveys.read(store, Surveys.Kind.paper, paper.toString());
        assertEquals(Surveys.Kind.paper, r.kind());
        assertEquals("tides", r.name());
        assertEquals("Kalman smoothing of harmonic tide constituents", r.title());
        assertTrue(r.text().contains("Origin: " + paper) && r.text().contains("Pugh (1987)"), r.text());
        // without a model: the first real paragraph and the paper's standing directions
        Surveys.Description d = Surveys.describe(r, null);
        assertTrue(d.whatItIs().startsWith("We show that a Kalman smoother"), d.whatItIs());
        assertEquals(3, d.options().size());
        assertTrue(d.options().get(0).startsWith("Do the central claims of \"Kalman smoothing"), d.options().get(0));
        // the model's reading: claims and what it leaves open are kept apart, NONE is nothing
        Surveys.Description m = Surveys.parse("WHAT IT IS\nA methods paper in physical oceanography by J. Doe (2021).\nWHAT IT SAYS\nA Kalman smoother lowers residual variance.\nCLAIMS\n- Residual variance falls by 31% at twelve harbours.\nRESTS ON\nPugh (1987)\nNOAA CO-OPS constituent tables\nLEAVES OPEN\nWhether the gain holds in estuaries.\nDIRECTIONS\nDoes a Kalman smoother lower tidal residual variance at other harbours?\nWhat does Pugh (1987) say about constituent stability?\n");
        assertNotNull(m);
        assertEquals(List.of("Residual variance falls by 31% at twelve harbours."), m.claims());
        assertEquals(List.of("Whether the gain holds in estuaries."), m.leavesOpen());
        assertEquals("A Kalman smoother lowers residual variance.", m.summary());
        // a list number comes off the front of a line; a number that is the claim's own stays
        assertEquals("10,000 businesses choose it", Surveys.unnumbered("1. 10,000 businesses choose it"));
        assertEquals("10,000 businesses choose it", Surveys.unnumbered("- 10,000 businesses choose it"));
        assertEquals("10,000 businesses choose it", Surveys.unnumbered("10,000 businesses choose it"));
        assertEquals("https://arxiv.org/pdf/2307.03172", Surveys.paperUrl("https://arxiv.org/abs/2307.03172"));
        assertEquals("https://arxiv.org/abs/2307.03172v3".replace("abs", "pdf"), Surveys.paperUrl("https://arxiv.org/abs/2307.03172v3"));
        assertEquals("https://doi.org/10.1/x", Surveys.paperUrl("https://doi.org/10.1/x"));
        Surveys.Description none = Surveys.parse("WHAT IT IS\nA page.\nCLAIMS\nNONE\nLEAVES OPEN\nNONE\nDIRECTIONS\nWho is behind the page, and what have they published?\n");
        assertTrue(none.claims().isEmpty() && none.leavesOpen().isEmpty());
        // filed through the protocol: the claim carries what it claims and leaves open, the collection is papers
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode s = p.survey(patron(M.createObjectNode().put("path", paper.toString())));
        assertEquals("paper", s.path("kind").asText()); assertEquals("tides", s.path("name").asText());
        assertTrue(s.path("summary").asText().startsWith("Read the document tides"), s.path("summary").asText());
        Finding claim = Surveys.claimFor(store, "tides");
        assertNotNull(claim); assertEquals(Surveys.Kind.paper, Surveys.kindOf(claim));
        assertEquals(paper.toString(), claim.sources().get(0).locator());
        assertEquals(3, Surveys.options(store, "tides").size());
        ObjectNode pick = p.survey(patron(M.createObjectNode().put("op", "pick").put("name", "tides").put("picks", "2")));
        assertEquals("paper", pick.path("kind").asText());
        var jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        String q = jobs.active().get(0).path("args").path("question").asText();
        assertTrue(q.startsWith("About the document tides (" + paper + ")"), q);
        assertTrue(Files.list(store.rawDir()).anyMatch(x -> true), "the text is shelved");
    }

    @Test
    void aWebsiteIsReadAsAPageAndAnIssuesExportAsIssues(@TempDir Path home) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/product", ex -> {
            byte[] b = "<html><head><title>Tidebook Pro</title></head><body><h1>Tidebook Pro</h1><p>Tidebook Pro ranks harbours by tide-table agreement and cuts survey time by 40% for coastal engineers. Built on the IHO constituent list.</p></body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        try {
            setLoopback(true);
            Surveys.Read site = Surveys.read(null, Surveys.Kind.site, base + "/product");
            assertEquals(Surveys.Kind.site, site.kind());
            assertEquals("Tidebook Pro", site.title());
            assertTrue(site.name().startsWith("127.0.0.1"), site.name());
            assertTrue(site.text().contains("cuts survey time by 40%"), site.text());
            Surveys.Description d = Surveys.describe(site, null);
            assertTrue(d.options().get(0).startsWith("Are the claims made at " + base + "/product backed"), d.options().get(0));
            assertThrows(java.io.IOException.class, () -> Surveys.read(null, Surveys.Kind.site, base + "/missing"), "HTTP 404 is said, not swallowed");
        } finally { setLoopback(false); srv.stop(0); }
        // an issues export: a JSON list in GitHub's shape; pull requests are left out; the newest hundred read
        Path export = home.resolve("tidebook-issues.json");
        Files.writeString(export, "[{\"number\":12,\"title\":\"Residuals blow up at Bristol\",\"state\":\"open\",\"comments\":5,\"labels\":[{\"name\":\"bug\"}],\"body\":\"The smoother diverges when the M2 constituent is missing. I think it is the Kalman gain.\"},"
                + "{\"number\":9,\"title\":\"Support RIS export\",\"state\":\"closed\",\"comments\":0,\"labels\":[],\"body\":null},"
                + "{\"number\":10,\"title\":\"PR: fix gain\",\"state\":\"open\",\"comments\":1,\"pull_request\":{\"url\":\"x\"},\"body\":\"fixes #12\"}]");
        assertEquals(Surveys.Kind.issues, Surveys.detect(export.toString()));
        Surveys.Read issues = Surveys.read(null, Surveys.Kind.issues, export.toString());
        assertEquals("2", issues.facts().get("issues")); assertEquals("1", issues.facts().get("open"));
        assertTrue(issues.text().contains("## #12 Residuals blow up at Bristol  [open, 5 comment(s); bug]") && issues.text().contains("1 pull request(s) left out"), issues.text());
        assertFalse(issues.text().contains("PR: fix gain"));
        assertEquals("tidebook-issues", issues.name());
        Surveys.Description d = Surveys.describe(issues, null);
        assertTrue(d.options().get(0).startsWith("Which problems recur in Issues: tidebook-issues"), d.options().get(0));
        // a GitHub issues page is read through the API url
        assertTrue(Surveys.GITHUB_ISSUES.matcher("https://github.com/someone/tidebook/issues").matches());
        // a plain-text export is read as text
        Path txt = home.resolve("thread.mbox");
        Files.writeString(txt, "From: a\nSubject: gain\n\nThe gain is wrong.\n");
        Surveys.Read t = Surveys.read(null, Surveys.Kind.issues, txt.toString());
        assertEquals("thread", t.name()); assertTrue(t.text().contains("The gain is wrong."));
    }
}
