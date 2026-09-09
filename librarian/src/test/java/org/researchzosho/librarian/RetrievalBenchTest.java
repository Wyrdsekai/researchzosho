package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalBenchTest {

    @TempDir Path tmp;

    @Test
    void casesComeFromSubQuestionsAndMissesAreNamed() throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianIndex idx = new LibrarianIndex(store, Embeddings.none());
        Finding f = new Finding("F-0001-ctc", "CTC models enable forced alignment", List.of(), Finding.State.accepted,
                Finding.ClaimType.extraction, Finding.Confidence.high, "person", Instant.now().toString(), "2026-09-02",
                Finding.Volatility.stable, "", List.of(new Finding.Source("https://a.org", "n/a", "s")), List.of(), null,
                "CTC posteriors allow Viterbi alignment.\n");
        store.write(f); idx.upsert(f);
        store.write(new Investigation("I-0001-run", "which ones are viable?", Finding.State.accepted, "model:t",
                Instant.now().toString(), List.of("F-0001-ctc"), List.of(),
                "SUB-QUESTION: which architectures emit frame posteriors for forced alignment?\nFINDINGS: ...\n"
                + "SUB-QUESTION: (timed out)\nFINDINGS: unavailable\n"
                + "SUB-QUESTION: what colour is the sky in Osaka in spring?\nFINDINGS: ...\n"));
        var cases = RetrievalBench.cases(store);
        assertEquals(3, cases.size(), "two real sub-questions + the top-level question; '(timed out)' skipped");
        var r = RetrievalBench.run(idx, cases, 10);
        assertEquals(1, r.hits(), "the alignment sub-question hits; the sky question and the bare title miss");
        assertEquals(2, r.misses().size());
        assertTrue(r.misses().get(0).contains("I-0001-run"));
    }
}
