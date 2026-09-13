package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentTest {

    @TempDir Path tmp;

    @Test
    void contextsPersistPerChunkAndRideIntoTheIndex() throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        String filler = "Filler sentence about nothing. ".repeat(80);
        Files.writeString(store.rawDir().resolve("2026-09-03-doc.md"),
                "---\nurl: https://example.org/paper\ntitle: The paper\nfetched_at: t\nfetched_by: test\n---\n"
                + filler + "\n\nThe pivotal result concerns register loss.\n\n" + filler);
        int[] calls = {0};
        Enrichment.Contextualizer ctx = (title, head, chunk) -> {
            calls[0]++;
            return chunk.contains("register loss") ? "This chunk states the paper's central finding about keigo." : "Background filler.";
        };
        var out = Enrichment.run(store, ctx, 0);
        assertTrue(out.chunksGenerated() >= 2, "one context per chunk: " + out.chunksGenerated());
        assertEquals(0, out.chunksSkipped());
        // second run: nothing regenerated
        var again = Enrichment.run(store, ctx, 0);
        assertEquals(0, again.chunksGenerated());
        assertEquals(out.chunksGenerated(), again.chunksSkipped());
        // the generated context is searchable: 'keigo' appears ONLY in the context, never in the raw text
        // mode 'both' puts the context into the BM25 text (the default 'dense' keeps it vector-only,
        // measured: lexical crowding); tests run with RESEARCHZOSHO_ENRICH=both to pin the mechanism
        LibrarianIndex idx = new LibrarianIndex(store, Embeddings.none());
        idx.rebuild();
        var hits = idx.search("keigo central finding", 5);
        assertFalse(hits.isEmpty(), "the context made the chunk findable by a term the chunk never contains");
        assertEquals("2026-09-03-doc.md", hits.get(0).id());
        assertTrue(hits.get(0).snippet().contains("register loss"));
    }

    @Test
    void theNightlyCapAndAStopEndTheRunWhereItStands() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib2")); store.init();
        String filler = "Filler sentence about nothing. ".repeat(80);
        for (int i = 0; i < 4; i++) Files.writeString(store.rawDir().resolve("2026-09-13-doc" + i + ".md"),
                "---\nurl: https://example.org/p" + i + "\ntitle: Paper " + i + "\nfetched_at: t\nfetched_by: test\n---\n" + filler + "\n\nResult " + i + ".\n\n" + filler);
        int[] calls = {0};
        Enrichment.Contextualizer ctx = (title, head, chunk) -> { calls[0]++; return "Context."; };
        var capped = Enrichment.run(store, ctx, 2, () -> false);
        assertEquals(2, capped.files(), "two files a night, the rest wait");
        int after2 = calls[0];
        assertTrue(after2 > 0);
        // a stop after the next three calls: the run ends there, and what was written stays
        int[] left = {3};
        var stopped = Enrichment.run(store, ctx, 0, () -> left[0]-- <= 0);
        assertTrue(stopped.problems().stream().anyMatch(x -> x.startsWith("stopped")), stopped.problems().toString());
        assertTrue(calls[0] - after2 <= 4, "at most a few calls after the stop was raised: " + (calls[0] - after2));
        var rest = Enrichment.run(store, ctx, 0, () -> false);
        assertTrue(rest.chunksSkipped() >= after2, "contexts written before the stop are kept: " + rest.chunksSkipped());
    }
}
