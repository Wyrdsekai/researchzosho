package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The desk floors on relevance, search does not: a dense hit between the search floor (0.47)
 * and the desk floor (0.58) is a search result and NOT a holding; a one-word sparse match is
 * neither. Pinned after Wyrdsekai's "zebra crossings on the moon in 1740" (2026-09-03).
 */
class DeskFloorTest {

    /** Unit vectors at chosen angles: the query at 0°, "nearby" at cos 0.52, "on-topic" at cos 0.95. */
    private static final Embeddings.Embedder STUB = new Embeddings.Embedder() {
        @Override public float[] embed(String t) {
            String l = t.toLowerCase(Locale.ROOT);
            double cos = l.contains("harpsichord") ? 1.0 : l.contains("clavichord") ? 0.95 : l.contains("gamelan") ? 0.52 : -1.0;   // anything else points AWAY from the query
            return new float[]{(float) cos, (float) Math.sqrt(1 - cos * cos)};
        }
        @Override public String modelId() { return "stub"; }
    };

    private static Finding f(String id, String title, String body) {
        return new Finding(id, title, List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "person", Instant.now().toString(), "2026-09-03", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://a.org", "n/a", "s")), List.of(), null, body);
    }

    @Test
    void aMiddlingDenseHitIsASearchResultButNotAHolding(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        store.write(f("F-0001-clavichord", "Clavichord temperament", "Clavichord temperament notes.\n"));   // cos 0.95
        store.write(f("F-0002-gamelan", "Gamelan tuning", "Gamelan slendro tuning is not equal-tempered.\n")); // cos 0.52
        LibrarianIndex idx = new LibrarianIndex(store, STUB);
        idx.rebuild();
        var plain = idx.search("harpsichord temperament", 10);
        var strict = idx.searchStrict("harpsichord temperament", 10, null, null);
        assertTrue(plain.stream().anyMatch(h -> h.id().equals("F-0002-gamelan")), "search keeps the weak dense hit");
        assertTrue(strict.stream().anyMatch(h -> h.id().equals("F-0001-clavichord")), "the desk keeps the strong one");
        assertFalse(strict.stream().anyMatch(h -> h.id().equals("F-0002-gamelan")), "the desk drops the middling one");
        // a query that touches nothing: search may still return the nearest neighbours; the desk returns nothing
        assertTrue(idx.searchStrict("zebra crossings on the moon in 1740", 10, null, null).isEmpty());
    }

    @Test
    void oneWordOfManyIsNotAMatchAtTheDesk(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        store.write(f("F-0001-moon", "Lunar cartography", "Early maps of the moon were drawn by hand.\n"));
        LibrarianIndex idx = new LibrarianIndex(store, Embeddings.none());
        idx.rebuild();
        assertFalse(idx.search("zebra crossings on the moon in 1740", 10).isEmpty(), "search: 'moon' matches");
        assertTrue(idx.searchStrict("zebra crossings on the moon in 1740", 10, null, null).isEmpty(), "desk: one of four terms is not a holding");
        assertFalse(idx.searchStrict("maps of the moon", 10, null, null).isEmpty(), "desk: two of two terms is");
    }

    @Test
    void aConfiguredButSilentEmbedderBlocksARebuild() {
        assertNull(LibrarianIndex.rebuildBlocker(Embeddings.none()), "sparse-only by choice is fine");
        assertNull(LibrarianIndex.rebuildBlocker(STUB), "an answering embedder is fine");
        Embeddings.Embedder silent = new Embeddings.Embedder() {
            @Override public float[] embed(String t) { return null; }
            @Override public String modelId() { return "embed@http://down:1"; }
        };
        String why = LibrarianIndex.rebuildBlocker(silent);
        assertNotNull(why);
        assertTrue(why.contains("keeping the existing index"), why);
    }
}
