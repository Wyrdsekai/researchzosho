package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The intake gates are ARITHMETIC — no model involved — so their contract is fully testable:
 * a run that never concluded, fetched nothing, or ran on a degraded backend is refused with a
 * reason naming the substrate, and the refusal lands on the frontier instead of vanishing.
 */
class AcquisitionsTest {

    @TempDir
    Path tmp;

    @Test
    void capEndedRunIsRefused() {
        var g = Acquisitions.gate(false, "a fine summary https://example.org", 5, 0);
        assertFalse(g.admitted());
        assertTrue(g.reason().contains("turn cap"), g.reason());
    }

    @Test
    void zeroSourcesIsRefused() {
        var g = Acquisitions.gate(true, "confident prose with no links", 0, 0);
        assertFalse(g.admitted());
        assertTrue(g.reason().contains("memory"), g.reason());
    }

    @Test
    void degradedBackendWithThinFetchingIsRefused() {
        var g = Acquisitions.gate(true, "partial https://example.org", 1, 3);
        assertFalse(g.admitted());
        assertTrue(g.reason().contains("degraded"), g.reason());
    }

    private static final String REAL = "A real answer with enough substance to be an answer, "
            + "naming three concrete things and citing where they came from. ".repeat(3)
            + "https://example.org/a";

    @Test
    void healthyCompletedRunIsAdmitted() {
        assertTrue(Acquisitions.gate(true, REAL, 4, 0).admitted());
        // degraded events happened but the run still fetched plenty — the substrate recovered
        assertTrue(Acquisitions.gate(true, REAL, 6, 1).admitted());
    }

    @Test
    void emptySummaryIsRefusedEvenWithSourcesFetched() {
        var g = Acquisitions.gate(true, "(done)", 12, 0);
        assertFalse(g.admitted(), "sources fetched do not make '(done)' an answer");
        assertTrue(g.reason().contains("substance"), g.reason());
    }

    @Test
    void emptySynthesisWithRealWorkerNotesIsAdmittedAndMarked() throws Exception {
        String notes = "SUB-QUESTION: a\nFINDINGS: " + "real worker text with substance. ".repeat(10)
                + "https://arxiv.org/abs/2608.01913";
        assertTrue(Acquisitions.gate(true, "(done)", notes, 20, 0).admitted(),
                "seven workers' findings are a record even when the synthesis died");
        assertFalse(Acquisitions.gate(true, "(done)", "thin", 20, 0).admitted());
        LibraryStore store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        Investigation inv = Acquisitions.admit(store, new LibrarianIndex(store), "q?", "(done)", "model:test", notes);
        assertTrue(inv.body().contains("synthesis step did not produce an answer"), inv.body());
        assertTrue(inv.body().contains("## Worker findings"), inv.body());
    }

    @Test
    void workerNotesRideIntoTheInvestigationAndItsSources() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        Investigation inv = Acquisitions.admit(store, new LibrarianIndex(store), "q?",
                "synthesis https://example.org/s", "model:test",
                "SUB-QUESTION: a\nFINDINGS: worker text https://example.org/w1");
        assertTrue(inv.body().contains("## Worker findings"), inv.body());
        assertTrue(inv.body().contains("https://example.org/w1"), "worker sources are the record's sources too");
    }

    @Test
    void admitWritesDraftInvestigationWithSourcesAndIndexesIt() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        LibrarianIndex idx = new LibrarianIndex(store);
        Investigation inv = Acquisitions.admit(store, idx,
                "Which JA wav2vec2 alignment models are viable for word-level subtitle timing?",
                "Three candidates stand out for word-level alignment quality.\n\nSOURCES:\n"
                        + "https://example.org/paper-a\nhttps://example.org/model-b\n",
                "model:test-drive");
        assertEquals(Finding.State.draft, inv.state());
        assertTrue(inv.body().contains("https://example.org/paper-a"));
        assertEquals(inv, store.investigation(inv.id()));
        assertEquals(inv.id(), idx.search("word-level alignment", 3).get(0).id());
        assertTrue(Files.readString(store.circulationFile()).contains("intake-admitted"));
    }

    @Test
    void refusalLandsOnFrontierNotNowhere() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        Acquisitions.refuse(store, "what are the viable JA aligners?", "zero sources fetched");
        String frontier = Files.readString(store.frontierFile());
        assertTrue(frontier.contains("[report] re-run on a healthy substrate"), frontier);
        assertTrue(frontier.contains("viable JA aligners"), frontier);
        assertTrue(Files.readString(store.circulationFile()).contains("intake-refused"));
    }

    @Test
    void bareIdentifiersResolveToCanonicalLocators() {
        var urls = Acquisitions.urls("per the meta-analysis (arXiv 2608.01913) and DeepSearchQA arXiv: 2601.20975v2; "
                + "also doi:10.1145/3696410.3714902 and a real link https://arxiv.org/html/2608.01913v1");
        assertTrue(urls.contains("https://arxiv.org/html/2608.01913v1"), "the real link stays as written");
        assertFalse(urls.contains("https://arxiv.org/abs/2608.01913"), "an id already covered by a URL is not duplicated");
        assertTrue(urls.contains("https://arxiv.org/abs/2601.20975"), urls.toString());
        assertTrue(urls.contains("https://doi.org/10.1145/3696410.3714902"), urls.toString());
    }

    @Test
    void editionCitationsInTheSourcesSectionBecomeLocators() {
        String rec = "Body text mentions Hosaka 2016 in passing (not a source line).\n\n"
                + "## SOURCES\n- 保坂 敏子 (Hosaka, Toshiko), 字幕翻訳で失われる要素, 日本語と日本語教育 44, Keio, 2016\n"
                + "- Gilgamesh, tablet XI, trans. Andrew George (Penguin Classics, 2003)\n"
                + "- https://example.org/a\n- short 2016\n\n## Next heading\n- Not a source 2020 line\n";
        var locs = Acquisitions.urls(rec);
        assertTrue(locs.stream().anyMatch(l -> l.startsWith("cite:保坂")), locs.toString());
        assertTrue(locs.stream().anyMatch(l -> l.startsWith("cite:Gilgamesh")), locs.toString());
        assertTrue(locs.contains("https://example.org/a"));
        assertFalse(locs.stream().anyMatch(l -> l.contains("short 2016")), "too short to be a citation");
        assertFalse(locs.stream().anyMatch(l -> l.contains("Not a source")), "outside the SOURCES section");
        assertEquals(SourceTier.scholarly, SourceTier.of(locs.stream().filter(l -> l.startsWith("cite:保坂")).findFirst().get()));
        assertEquals(SourceTier.reference, SourceTier.of(locs.stream().filter(l -> l.startsWith("cite:Gilgamesh")).findFirst().get()));
    }

    @Test
    void urlExtractionStripsTrailingPunctuationAndDedupes() {
        var urls = Acquisitions.urls("see https://a.org/x), then (https://b.org/y]. "
                + "again https://a.org/x.");
        assertEquals(java.util.List.of("https://a.org/x", "https://b.org/y"), urls);
    }

    @Test
    void onlyListItemsUnderASourcesHeadingAreEditionCitations() {
        // a worker's summary: "Sources:" with a URL, then prose naming years — none of it is a citation
        String summary = "SUMMARY: The ban took effect in 1978.\nSources: https://www.cpsc.gov/ban\nThe 1971 Act (Pub. L. 91-695) came first and the 1978 rule (16 CFR 1303) followed.\nA 1999 JCI paper reviewed both.\n";
        assertEquals(java.util.List.of(), Acquisitions.editionCitations(summary), "prose after a Sources line is not a list of citations");
        // the classicist's list: items under the heading, until the list ends
        String sources = "## Sources\n\n- Hosaka Toshiko, 字幕翻訳で失われる要素, 日本語と日本語教育 44, Keio University, 2016\n- Gilgamesh, tablet XI, trans. George (Penguin 2003)\n- https://example.org/a-url-is-not-an-edition\n\nThe discussion resumed in 2020 with more work.\n";
        var cites = Acquisitions.editionCitations(sources);
        assertEquals(2, cites.size(), cites.toString());
        assertTrue(cites.get(0).startsWith("cite:Hosaka Toshiko") && cites.get(1).startsWith("cite:Gilgamesh"), cites.toString());
    }
}
