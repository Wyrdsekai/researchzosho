package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkingTest {

    @Test
    void paragraphsAccumulateUnderTheBudgetAndNeverOverlap() {
        String para = "word ".repeat(40).strip();             // 200 chars ≈ 50 tokens
        String text = String.join("\n\n", java.util.Collections.nCopies(30, para));  // ≈1500 tokens
        List<String> chunks = LibrarianIndex.chunk(text, 600);
        assertTrue(chunks.size() >= 3 && chunks.size() <= 4, "≈1500 tokens at 600 per chunk: " + chunks.size());
        for (String c : chunks) assertTrue(LibrarianIndex.estTokens(c) <= 600, "within budget");
        int total = chunks.stream().mapToInt(c -> { var m = java.util.regex.Pattern.compile("word").matcher(c); int n = 0; while (m.find()) n++; return n; }).sum();
        assertEquals(30 * 40, total, "no overlap, nothing dropped");
    }

    @Test
    void oversizeParagraphSplitsAtSentencesIncludingJapanese() {
        String ja = "字幕翻訳では敬意が削除される。".repeat(80);   // one paragraph, ~1200 CJK tokens
        List<String> chunks = LibrarianIndex.chunk(ja, 600);
        assertTrue(chunks.size() >= 2, "split: " + chunks.size());
        for (String c : chunks) assertTrue(c.endsWith("。"), "splits at sentence ends: " + c.substring(Math.max(0, c.length() - 10)));
    }

    @Test
    void rawCapturesAreChunkedAndCollapseToOneHitWithTheMatchingSnippet(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianIndex idx = new LibrarianIndex(store, Embeddings.none());
        String filler = "Unrelated filler sentence about nothing in particular. ".repeat(60);
        String text = filler + "\n\n" + "The keigo dissolution strategy renders honorifics as register.\n\n" + filler;
        idx.upsertRaw("2026-09-03-abc.md", "A long paper", "https://example.org/paper", text);
        var hits = idx.search("keigo dissolution honorifics", 5);
        assertEquals(1, hits.size(), "one hit per raw document, however many chunks matched");
        assertEquals("2026-09-03-abc.md", hits.get(0).id());
        assertEquals("raw", hits.get(0).kind());
        assertTrue(hits.get(0).snippet().contains("keigo dissolution"), "the matching chunk, not the head: " + hits.get(0).snippet());
        // re-upsert replaces the old chunks rather than piling them up
        idx.upsertRaw("2026-09-03-abc.md", "A long paper", "https://example.org/paper", "Now it says only wav2vec2.");
        assertTrue(idx.search("keigo dissolution", 5).isEmpty());
    }

    @Test
    void findingsOutrankRawAtEqualRank(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianIndex idx = new LibrarianIndex(store, Embeddings.none());
        idx.upsertRaw("2026-09-03-raw.md", "Raw page about keigo register", "https://example.org/r", "keigo register keigo register keigo register.");
        Finding f = new Finding("F-0001-keigo", "Keigo register finding", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "person", java.time.Instant.now().toString(), "2026-09-03", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://a.org", "n/a", "s")), List.of(), null, "keigo register.\n");
        idx.upsert(f);
        assertEquals("F-0001-keigo", idx.search("keigo register", 5).get(0).id(), "the reviewed atomic unit first");
    }
}
