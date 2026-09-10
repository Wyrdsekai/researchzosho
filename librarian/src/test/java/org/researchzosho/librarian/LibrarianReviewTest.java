package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The review pipeline with a STUB judge — the model's answers are inputs here, so what these
 * tests pin is the MACHINE's half of the division of labor: the source-evidence gate, the
 * claim-type promotion arithmetic, dispute-marks-both, and duplicate-skips.
 */
class LibrarianReviewTest {

    @TempDir
    Path tmp;

    private LibraryStore store;
    private LibrarianIndex index;

    private Investigation admitted(String question, String body) throws Exception {
        store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        index = new LibrarianIndex(store);
        return Acquisitions.admit(store, index, question, body, "model:test");
    }

    private static LibrarianReview.Judge judge(String extractJson, String compareJson) {
        return new LibrarianReview.Judge() {
            @Override public String extract(String b) { return extractJson; }
            @Override public String compare(String c, String n) { return compareJson; }
        };
    }

    @Test
    void anExtractionWaitsAsADraftUntilASecondSourceCorroboratesIt_synthesisStaysDraft() throws Exception {
        Investigation inv = admitted("viable JA aligners?",
                "Model A aligns at word level. https://huggingface.co/a and https://huggingface.co/b");
        String extract = """
                [{"title": "Model A supports word-level output", "claim": "Model A emits word timestamps.",
                  "claim_type": "extraction", "confidence": "high", "volatility": "slow",
                  "sources": ["https://huggingface.co/a"]},
                 {"title": "A is the best choice overall", "claim": "Across the sources, A wins.",
                  "claim_type": "synthesis", "confidence": "medium", "volatility": "slow",
                  "sources": ["https://huggingface.co/b"]}]""";
        var out = new LibrarianReview(store, index, judge(extract, "{}"), "librarian:test").review(inv);
        assertEquals(0, out.accepted().size(), out.problems().toString());
        assertEquals(2, out.keptDraft().size(), "one source is never enough on its own: both wait");
        Finding waiting = store.scanFindings().findings().stream().filter(f -> f.title().startsWith("Model A supports")).findFirst().orElseThrow();
        assertEquals(Finding.State.draft, waiting.state());
        assertFalse(waiting.reviewStale(), "review hash must cover the written content");
        assertFalse(waiting.reviewBy().isEmpty(), "slow volatility sets a review-by horizon");
        assertNotNull(store.finding(out.keptDraft().get(0)).review(), "kept-draft still RECORDS the review");
        Investigation after = store.investigation(inv.id());
        assertEquals(Finding.State.accepted, after.state(), "the investigation was promoted and links its findings");
        assertTrue(after.findings().contains(waiting.id()));

        // a second write-up, the same claim from an independent source: the draft gains the source and is accepted
        Investigation inv2 = admitted("does A give word timestamps?", "Yes. https://docs.example/model-a");
        String extract2 = """
                [{"title": "Model A gives word-level timestamps", "claim": "Model A emits word timestamps.",
                  "claim_type": "extraction", "confidence": "high", "volatility": "slow",
                  "sources": ["https://docs.example/model-a"]}]""";
        var out2 = new LibrarianReview(store, index, judge(extract2, "{\"verdict\":\"duplicate\",\"id\":\"" + waiting.id() + "\"}"), "librarian:test").review(inv2);
        assertEquals(List.of(waiting.id()), out2.accepted(), out2.toString());
        Finding grown = store.finding(waiting.id());
        assertEquals(Finding.State.accepted, grown.state());
        assertEquals(2, grown.sources().size(), grown.sources().toString());
        assertTrue(grown.notes().stream().anyMatch(n -> n.kind().equals("corroborated") && n.text().contains("docs.example")), grown.notes().toString());
        assertFalse(grown.reviewStale(), "the review hash covers the grown finding");
        // the same source again is a plain duplicate, not corroboration
        Investigation inv3 = admitted("again?", "Yes. https://docs.example/model-a");
        var out3 = new LibrarianReview(store, index, judge(extract2, "{\"verdict\":\"duplicate\",\"id\":\"" + waiting.id() + "\"}"), "librarian:test").review(inv3);
        assertEquals(List.of(waiting.id()), out3.skippedDuplicates());
        assertEquals(2, store.finding(waiting.id()).sources().size());
    }

    @Test
    void candidateCitingUnknownSourceIsDroppedNotWritten() throws Exception {
        Investigation inv = admitted("q?", "Body cites https://example.org/only");
        String extract = """
                [{"title": "Minted claim", "claim": "Cites a source the run never had.",
                  "claim_type": "extraction", "confidence": "high", "volatility": "stable",
                  "sources": ["https://elsewhere.org/minted"]}]""";
        var out = new LibrarianReview(store, index, judge(extract, "{}"), "librarian:test")
                .review(inv);
        assertTrue(out.accepted().isEmpty());
        assertTrue(out.keptDraft().isEmpty());
        assertEquals(1, out.problems().size());
        assertTrue(out.problems().get(0).contains("no source present"), out.problems().get(0));
        assertTrue(store.scanFindings().findings().isEmpty(), "nothing may reach the shelf");
    }

    @Test
    void contradictionMarksBothDisputedAndFeedsFrontier() throws Exception {
        // seed canon: an accepted finding the new claim will contradict
        store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        index = new LibrarianIndex(store);
        Finding existing = new Finding("F-0001-model-a-word-level",
                "Model A supports word-level output", List.of(), Finding.State.accepted,
                Finding.ClaimType.extraction, Finding.Confidence.high, "person",
                Instant.now().toString(), "2026-09-01", Finding.Volatility.slow, "",
                List.of(new Finding.Source("https://example.org/a", "n/a", "s")),
                List.of(), null, "Model A emits word timestamps.\n");
        store.write(existing);
        index.upsert(existing);
        Investigation inv = Acquisitions.admit(store, index, "re-check A",
                "Actually A only emits utterance timestamps. https://example.org/c", "model:test");

        String extract = """
                [{"title": "Model A lacks word-level output", "claim": "A emits only utterance-level stamps.",
                  "claim_type": "extraction", "confidence": "medium", "volatility": "slow",
                  "sources": ["https://example.org/c"]}]""";
        String compare = "{\"verdict\": \"contradicts\", \"id\": \"F-0001-model-a-word-level\"}";
        var out = new LibrarianReview(store, index, judge(extract, compare), "librarian:test")
                .review(inv);

        assertEquals(1, out.disputed().size());
        Finding oldOne = store.finding("F-0001-model-a-word-level");
        assertEquals(Finding.State.disputed, oldOne.state(), "canon is never overwritten — both dispute");
        assertTrue(oldOne.body().contains("DISPUTED-BY: " + out.disputed().get(0).split(" ")[0]));
        Finding newOne = store.finding(out.disputed().get(0).split(" ")[0]);
        assertEquals(Finding.State.disputed, newOne.state());
        assertTrue(Files.readString(store.frontierFile()).contains("[dispute]"));
    }

    @Test
    void aTripleClashIsNominatedByArithmeticBeforeAnyNeighbour() throws Exception {
        store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        index = new LibrarianIndex(store);
        // canon: a triple-bearing finding whose TEXT shares nothing with the new claim (BM25 would not find it)
        Finding existing = new Finding("F-0001-keigo-equivalent", "Honorific register in English",
                List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, "person",
                Instant.now().toString(), "2026-09-01", Finding.Volatility.slow, "",
                List.of(new Finding.Source("https://example.org/a", "n/a", "s")), List.of(), null,
                "Politeness marking is dissolved into register.\n").withTriple(new Finding.Triple("keigo", "has direct English equivalent", "none"));
        store.write(existing);
        index.upsert(existing);
        // a decoy the words DO match, with no triple
        Finding decoy = new Finding("F-0002-subtitle-timing", "Subtitle timing", List.of(), Finding.State.accepted,
                Finding.ClaimType.extraction, Finding.Confidence.high, "person", Instant.now().toString(), "2026-09-01",
                Finding.Volatility.slow, "", List.of(new Finding.Source("https://example.org/b", "n/a", "s")), List.of(), null,
                "A subtitle line carries at most two rows; the yes-equivalent rule.\n");
        store.write(decoy);
        index.upsert(decoy);
        Investigation inv = Acquisitions.admit(store, index, "keigo again",
                "A subtitle line shows the equivalent yes marker. https://example.org/c", "model:test");
        String extract = """
                [{"title": "Subtitle line equivalent", "claim": "A subtitle line carries the yes-equivalent marker.",
                  "claim_type": "extraction", "confidence": "medium", "volatility": "slow",
                  "sources": ["https://example.org/c"],
                  "triple": {"subject": "Keigo", "predicate": "has direct English equivalent ", "object": "the yes-marker"}}]""";
        var seen = new java.util.concurrent.atomic.AtomicReference<String>("");
        var judge = new LibrarianReview.Judge() {
            @Override public String extract(String b) { return extract; }
            @Override public String compare(String c, String n) { seen.set(n); return "{\"verdict\": \"contradicts\", \"id\": \"F-0001-keigo-equivalent\"}"; }
        };
        var out = new LibrarianReview(store, index, judge, "librarian:test").review(inv);
        assertTrue(seen.get().startsWith("[F-0001-keigo-equivalent] (SAME SUBJECT AND PREDICATE, DIFFERENT OBJECT"), seen.get());
        assertTrue(seen.get().indexOf("F-0001-keigo-equivalent") < seen.get().indexOf("F-0002-subtitle-timing"), "the clash comes before the word-neighbour");
        assertEquals(1, out.disputed().size());
        Finding newOne = store.finding(out.disputed().get(0).split(" ")[0]);
        assertEquals("keigo", Finding.Triple.canon(newOne.triple().subject()));
        assertEquals("disputed", newOne.notes().get(0).kind());
        assertTrue(newOne.notes().get(0).text().contains("same subject and predicate"), newOne.notes().get(0).text());
        Finding old = store.finding("F-0001-keigo-equivalent");
        assertEquals("contradicted by " + newOne.id(), old.notes().get(0).text());
        assertEquals(Finding.State.disputed, old.state());
    }

    @Test
    void duplicateIsSkippedAndCirculated() throws Exception {
        store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        index = new LibrarianIndex(store);
        Finding existing = new Finding("F-0001-model-a-word-level",
                "Model A supports word-level output", List.of(), Finding.State.accepted,
                Finding.ClaimType.extraction, Finding.Confidence.high, "person",
                Instant.now().toString(), "2026-09-01", Finding.Volatility.slow, "",
                List.of(new Finding.Source("https://example.org/a", "n/a", "s")),
                List.of(), null, "Model A emits word timestamps.\n");
        store.write(existing);
        index.upsert(existing);
        // the same claim from the SAME source is a duplicate (a different source would corroborate instead)
        Investigation inv = Acquisitions.admit(store, index, "same again",
                "Model A does word timestamps. https://example.org/a", "model:test");
        String extract = """
                [{"title": "Model A supports word timestamps", "claim": "A emits word timestamps.",
                  "claim_type": "extraction", "confidence": "high", "volatility": "slow",
                  "sources": ["https://example.org/a"]}]""";
        String compare = "{\"verdict\": \"duplicate\", \"id\": \"F-0001-model-a-word-level\"}";
        var out = new LibrarianReview(store, index, judge(extract, compare), "librarian:test")
                .review(inv);
        assertEquals(List.of("F-0001-model-a-word-level"), out.skippedDuplicates());
        assertEquals(1, store.scanFindings().findings().size(), "no second copy on the shelf");
    }

    @Test
    void blogOnlyExtractionStaysDraftForThePerson() throws Exception {
        Investigation inv = admitted("q?", "A blog says X. https://note.com/someone/n/abc");
        String extract = """
                [{"title": "X per a blog", "claim": "X holds, per one blog post.",
                  "claim_type": "extraction", "confidence": "high", "volatility": "stable",
                  "sources": ["https://note.com/someone/n/abc"]}]""";
        var out = new LibrarianReview(store, index, judge(extract, "{}"), "librarian:test")
                .review(inv);
        assertTrue(out.accepted().isEmpty(), "a blog does not walk into canon alone");
        assertEquals(1, out.keptDraft().size());
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("blog sources only")), out.problems().toString());
        assertEquals(Finding.State.draft, store.finding(out.keptDraft().get(0)).state());
    }

    @Test
    void multiSourceClaimSelfTypedExtractionIsDemotedToSynthesis() throws Exception {
        Investigation inv = admitted("q?", "A says X https://example.org/a and B says X https://example.org/b");
        String extract = """
                [{"title": "X holds", "claim": "Both A and B establish X.",
                  "claim_type": "extraction", "confidence": "high", "volatility": "stable",
                  "sources": ["https://example.org/a", "https://example.org/b"]}]""";
        var out = new LibrarianReview(store, index, judge(extract, "{}"), "librarian:test")
                .review(inv);
        assertTrue(out.accepted().isEmpty(), "two sources = synthesis by construction, never auto-canon");
        Finding f = store.finding(out.keptDraft().get(0));
        assertEquals(Finding.ClaimType.synthesis, f.claimType());
        assertEquals(Finding.State.draft, f.state());
    }

    @Test
    void editionCitedExtractionFromAScholarlyVenueKeepsItsCitationAndWaitsForASecondSource() throws Exception {
        Investigation inv = admitted("what does Hosaka find?",
                "Hosaka finds deference deleted.\n\nSOURCES:\n- 保坂 敏子 (Hosaka), 字幕翻訳で失われる要素, 日本語と日本語教育 44, Keio University, 2016\n");
        String extract = """
                [{"title": "Subtitles delete deference markers", "claim": "Hosaka finds 敬意 and 呼称 are deleted in English subtitles.",
                  "claim_type": "extraction", "confidence": "high", "volatility": "stable",
                  "sources": ["Hosaka 2016"]}]""";
        var out = new LibrarianReview(store, index, judge(extract, "{}"), "librarian:test").review(inv);
        // one scholarly source is a good source, and still one source: the claim waits as a draft for a second one, or for the person
        assertEquals(0, out.accepted().size(), out.problems().toString());
        assertEquals(1, out.keptDraft().size());
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("one independent source")), out.problems().toString());
        Finding f = store.finding(out.keptDraft().get(0));
        assertTrue(f.sources().get(0).locator().startsWith("cite:"), f.sources().toString());
        assertTrue(f.sources().get(0).edition().contains("Keio"), "the edition text rides on the source");
        assertEquals(f, Finding.parse(f.format()), "cite: sources survive the file round-trip");
    }

    @Test
    void enumeratedSourceLabelsResolveAndThePromptListsThem() {
        var inv = java.util.List.of("https://arxiv.org/abs/2608.01913", "cite:保坂 敏子, Keio, 2016");
        assertEquals(inv.get(0), LibrarianReview.resolveCited(inv, "S1"));
        assertEquals(inv.get(1), LibrarianReview.resolveCited(inv, "[S2]"));
        assertNull(LibrarianReview.resolveCited(inv, "S9"), "a label off the list cannot anchor");
        String prompt = LibrarianReview.extractPrompt("body cites arXiv 2608.01913\n\nSOURCES\n- 保坂 敏子, Keio, 2016\n");
        assertTrue(prompt.contains("[S1] https://arxiv.org/abs/2608.01913"), prompt);
        assertTrue(prompt.contains("[S2] cite:保坂 敏子, Keio, 2016"), prompt);
        assertTrue(prompt.contains("copied exactly"), "the constraint is stated");
    }

    @Test
    void citedUrlVariantsAnchorButMintedSourcesStillCannot() {
        var inv = java.util.List.of("https://github.com/m-bain/whisperX/blob/main/whisperx/alignment.py",
                "https://example.org/paper/");
        // prefix of a recorded URL (the live WhisperX case) — anchors, and records the RECORD's url
        assertEquals(inv.get(0),
                LibrarianReview.resolveCited(inv, "https://github.com/m-bain/whisperX"));
        // http/https and trailing-slash variants anchor
        assertEquals(inv.get(1), LibrarianReview.resolveCited(inv, "http://example.org/paper"));
        // identifier anchoring: a bare arXiv id or a versioned variant anchors to the record's locator
        var inv2 = java.util.List.of("https://arxiv.org/abs/2608.01913", "https://example.org/x");
        assertEquals(inv2.get(0), LibrarianReview.resolveCited(inv2, "arXiv 2608.01913"));
        assertEquals(inv2.get(0), LibrarianReview.resolveCited(inv2, "https://arxiv.org/html/2608.01913v1"));
        assertNull(LibrarianReview.resolveCited(inv2, "arXiv 2511.99999"), "an id the record never carried cannot anchor");
        // edition anchoring: author + year shared with a cite: locator; a different year cannot anchor
        var inv3 = java.util.List.of("cite:保坂 敏子 (Hosaka, Toshiko), 字幕翻訳で失われる要素, Keio, 2016",
                "cite:Gilgamesh, tablet XI, trans. Andrew George (Penguin Classics, 2003)");
        assertEquals(inv3.get(0), LibrarianReview.resolveCited(inv3, "Hosaka 2016, p. 47"));
        assertEquals(inv3.get(0), LibrarianReview.resolveCited(inv3, "保坂 2016"));
        assertEquals(inv3.get(1), LibrarianReview.resolveCited(inv3, "George 2003 translation"));
        assertNull(LibrarianReview.resolveCited(inv3, "Hosaka 2019"), "year mismatch cannot anchor");
        assertNull(LibrarianReview.resolveCited(inv3, "Smith 2016"), "name mismatch cannot anchor");
        // a genuinely absent source still resolves to nothing
        assertNull(LibrarianReview.resolveCited(inv, "https://elsewhere.org/minted"));
        assertNull(LibrarianReview.resolveCited(inv, ""));
    }

    @Test
    void unparseableExtractionLeavesInvestigationInDraft() throws Exception {
        Investigation inv = admitted("q?", "body https://example.org/a");
        var out = new LibrarianReview(store, index, judge("no json here", "{}"), "librarian:test")
                .review(inv);
        assertFalse(out.problems().isEmpty());
        assertEquals(Finding.State.draft, store.investigation(inv.id()).state(),
                "an unreviewable run stays draft — never silently promoted");
    }

    @Test
    void oneSourceStaysDraft_refusedSourcesAreDropped_andAFirstSeenHostIsReadSideways() throws Exception {
        Investigation inv = admitted("who cut the gears?",
                "A paper says hand-cut. https://arxiv.org/abs/2401.00001 and https://content-farm.example/gears and https://newsite.example/story");
        SourceRules.set(store, "refuse", "content-farm.example", "made up");
        String extract = """
                [{"title": "Gears were hand cut", "claim": "The gears were cut by hand.",
                  "claim_type": "extraction", "confidence": "high", "volatility": "stable",
                  "sources": ["https://arxiv.org/abs/2401.00001"]},
                 {"title": "Gears were cast", "claim": "The gears were cast in bronze.",
                  "claim_type": "extraction", "confidence": "high", "volatility": "stable",
                  "sources": ["https://content-farm.example/gears"]},
                 {"title": "Gears were filed", "claim": "The gears were finished with files.",
                  "claim_type": "extraction", "confidence": "medium", "volatility": "stable",
                  "sources": ["https://newsite.example/story"]}]""";
        java.util.List<String> searched = new java.util.ArrayList<>();
        var out = new LibrarianReview(store, index, judge(extract, "{}"), "librarian:test")
                .searcher(q -> { searched.add(q); return "results:\n1. Newsite — about us\n   https://newsite.example/about  [web]\n2. Newsite on a directory\n   https://dir.example/newsite  [web]\n"; })
                .review(inv);
        assertEquals(0, out.accepted().size(), "one independent source is never enough on its own: " + out);
        assertEquals(2, out.keptDraft().size());
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("one independent source")), out.problems().toString());
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("refused list") && p.contains("content-farm.example")), out.problems().toString());
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.title().equals("Gears were cast")), "the refused-only claim was not written");
        Finding filed = store.scanFindings().findings().stream().filter(f -> f.title().equals("Gears were filed")).findFirst().orElseThrow();
        assertTrue(filed.notes().stream().anyMatch(n -> n.kind().equals("source-check") && n.text().contains("first time this library cites newsite.example") && n.text().contains("Newsite — about us")), filed.notes().toString());
        assertEquals(1, searched.size(), "one search, for the one first-seen web host (arxiv is scholarly and needs none): " + searched);
    }

    @Test
    void aReplyCutOffMidArrayKeepsItsWholeCandidates_andAnUnparseableOneIsLogged() throws Exception {
        String cut = """
            [{"title": "Model A aligns at word level", "claim": "Model A aligns at the word level.",
              "claim_type": "extraction", "confidence": "high", "volatility": "slow", "sources": ["https://huggingface.co/a"]},
             {"title": "Model B", "claim": "Model B aligns at character level.", "claim_type": "extraction", "confidence": "high", "volatility": "slow",
              "sources": ["https://huggingface.co/b"]},
             {"title": "Half a third", "claim": "cut off he""";
        var arr = LibrarianReview.salvageArray(cut);
        assertNotNull(arr); assertEquals(2, arr.size(), "the two whole objects, not the torn third");
        assertNull(LibrarianReview.salvageArray("I could not find any claims."));
        assertNull(LibrarianReview.salvageArray("[{\"title\": \"no closing brace"));
        // through the review: salvaged candidates become drafts, and the problem is on the crews log
        Investigation inv = admitted("viable JA aligners?", "Model A aligns at word level. https://huggingface.co/a and https://huggingface.co/b");
        var out = new LibrarianReview(store, index, judge(cut, "{\"verdict\":\"independent\"}"), "librarian:t").review(inv);
        assertEquals(2, out.keptDraft().size(), out.toString());
        assertTrue(out.problems().get(0).startsWith("extraction was cut off; 2 whole"), out.problems().toString());
        String log = java.nio.file.Files.readString(store.root().resolve("catalog").resolve("crews.log"));
        assertTrue(log.contains("review") && log.contains("extraction was cut off"), log);
        // nothing parseable at all: logged with the reply's head, nothing written
        var none = new LibrarianReview(store, index, judge("Sorry, here are the claims in prose: the model aligns words.", "{}"), "librarian:t").review(admitted("q2", "body https://x.example/1"));
        assertTrue(none.keptDraft().isEmpty() && none.problems().get(0).contains("the reply began: Sorry, here are"), none.problems().toString());
    }

    @Test
    void theRecordIsFittedSoTheReplyStillFitsTheWindow() {
        String dense = "https://example.org/a-long-url-with-numbers-12345 日本語の長い文章 ".repeat(3000);   // ~150k chars
        String small = LibrarianReview.forExtraction(dense, 8_192);
        int allowed = (int) ((8_192 - LibrarianReview.EXTRACT_TOKENS - LibrarianReview.PROMPT_TOKENS) * 2.8);
        assertTrue(small.length() <= allowed + 200, "fitted to " + small.length() + " chars for an 8k slot, allowed about " + allowed);
        assertTrue(small.contains("characters cut from the middle"), small.substring(0, 80));
        String big = LibrarianReview.forExtraction(dense, 131_072);
        assertEquals(dense.length(), big.length(), "a large window keeps the whole record");
        assertTrue(LibrarianReview.forExtraction(dense, 2_048).length() >= 4_000, "never below the floor, even for a tiny window");
    }
}
