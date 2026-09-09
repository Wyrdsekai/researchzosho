package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dense half, pinned with a STUB embedder whose vectors encode meaning the words do not
 * share: a query about "politeness" must find the keigo finding although no token matches,
 * and a subject filter must be exact. RRF fusion, not either side alone.
 */
class HybridSearchTest {

    @TempDir Path tmp;

    /** Two axes: [politeness/keigo, speech/alignment]. Texts map by keyword; the query "politeness" too. */
    private static final Embeddings.Embedder STUB = new Embeddings.Embedder() {
        @Override public float[] embed(String t) {
            String l = t.toLowerCase(Locale.ROOT);
            float a = (l.contains("keigo") || l.contains("honorific") || l.contains("politeness")) ? 1f : 0f;
            float b = (l.contains("wav2vec2") || l.contains("alignment") || l.contains("timestamps")) ? 1f : 0f;
            if (a == 0 && b == 0) return new float[]{0.01f, 0.01f};
            float n = (float) Math.sqrt(a * a + b * b);
            return new float[]{a / n, b / n};
        }
        @Override public String modelId() { return "stub"; }
    };

    private static Finding f(String id, String title, String body, String... subjects) {
        return new Finding(id, title, List.of(subjects), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "person", Instant.now().toString(), "2026-09-02", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://a.org", "n/a", "s")), List.of(), null, body);
    }

    @Test
    void denseFindsWhatSharesNoVocabulary_andSparseKeepsExactNames() throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianIndex idx = new LibrarianIndex(store, STUB);
        idx.upsert(f("F-0001-keigo", "Keigo dissolves into register", "Honorific markers become natural English.\n", "japanese--keigo"));
        idx.upsert(f("F-0002-align", "wav2vec2 word timestamps", "CTC alignment yields word timestamps.\n", "speech--wav2vec2"));
        // no token overlap with either entry — only the vector knows
        var hits = idx.search("politeness across languages", 5);
        assertFalse(hits.isEmpty(), "dense must find it");
        assertEquals("F-0001-keigo", hits.get(0).id());
        // an exact name the vector space knows nothing about — sparse still wins
        assertEquals("F-0002-align", idx.search("CTC", 5).get(0).id());
    }

    @Test
    void subjectFilterIsExactAndFacetsCount() throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianIndex idx = new LibrarianIndex(store, STUB);
        idx.upsert(f("F-0001-keigo", "Keigo dissolves into register", "Honorific markers.\n", "japanese--keigo", "translation--register"));
        idx.upsert(f("F-0002-align", "wav2vec2 word timestamps", "Alignment timestamps.\n", "speech--wav2vec2"));
        idx.upsert(f("F-0003-strategy", "Foreignization retains honorifics", "Keigo kept as -san.\n", "translation--strategy"));
        var hits = idx.search("keigo honorifics", 5, "translation--register");
        assertEquals(1, hits.size());
        assertEquals("F-0001-keigo", hits.get(0).id());
        assertTrue(idx.search("keigo", 5, "no--such-subject").isEmpty());
        var counts = idx.subjectCounts();
        assertEquals(1, counts.get("japanese--keigo"));
        assertEquals(1, counts.get("speech--wav2vec2"));
        assertEquals(4, counts.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void withoutAnEmbedderTheIndexIsPlainBm25() throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianIndex idx = new LibrarianIndex(store, Embeddings.none());
        idx.upsert(f("F-0001-keigo", "Keigo dissolves into register", "Honorific markers.\n", "japanese--keigo"));
        assertTrue(idx.search("politeness", 5).isEmpty(), "no vector, no token match, no hit — honestly");
        assertEquals("F-0001-keigo", idx.search("honorific", 5).get(0).id());
    }
}
