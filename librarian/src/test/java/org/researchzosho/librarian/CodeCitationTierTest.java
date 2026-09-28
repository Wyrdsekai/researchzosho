package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A citation of a cloned repository's code, raw/repos/<repository>/<path>:<line>, is not the person's own document. It has a tier of its
 * own, and a claim that rests on one file of the repository waits for a second, independent source as a claim from any other source does,
 * while a claim from the person's own document is still their word.
 */
class CodeCitationTierTest {

    static final String CODE = "raw/repos/tidebook/src/cache.rs:12";
    static final String OWN = "file:///reader/tidebook-notes.md";

    private static LibraryStore store(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    static String candidate(String predicate, String object, String source) {
        return """
                {"title": "tidebook %s %s", "claim": "tidebook %s %s.", "claim_type": "extraction", "confidence": "high", "volatility": "stable",
                  "sources": ["%s"], "triple": {"subject": "tidebook", "predicate": "%s", "object": "%s"}}""".formatted(predicate, object, predicate, object, source, predicate, object);
    }

    static LibrarianReview.Outcome review(LibraryStore store, String said, String... candidates) throws Exception {
        LibrarianIndex index = new LibrarianIndex(store);
        Investigation inv = Acquisitions.admit(store, index, "How does tidebook evict its cache?", said, "model:test");
        String extract = "[" + String.join(",\n", candidates) + "]";
        return new LibrarianReview(store, index, new LibrarianReview.Judge() {
            @Override public String extract(String b) { return extract; }
            @Override public String compare(String c, String n) { return "{\"verdict\": \"independent\"}"; }
        }, "librarian:test").review(inv);
    }

    static Finding claim(LibraryStore store, String predicate) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals(predicate)).findFirst().orElseThrow();
    }

    @Test
    void aClaimOnOneFileOfARepositoryWaitsForASecondSourceAndTheirOwnDocumentIsStillTheirWord(@TempDir Path tmp) throws Exception {
        assertEquals("code", SourceTier.of(CODE).name(), "a file of a cloned repository");
        assertEquals("code", SourceTier.of("raw/repos/tidebook/README.md").name());
        assertEquals(SourceTier.personal, SourceTier.of(OWN));
        assertEquals(SourceTier.personal, SourceTier.of("raw/2026-09-01-0123456789ab.md"), "a document the person added is still theirs");
        assertEquals(Evidence.clue, Evidence.of(CODE));

        LibraryStore store = store(tmp);
        var out = review(store, "tidebook evicts the oldest entry first (" + CODE + "). My own notes say it writes a log of each eviction (" + OWN + ").",
                candidate("evicts", "the oldest entry first", CODE), candidate("writes", "a log of each eviction", OWN));
        Finding code = claim(store, "evicts");
        assertEquals(List.of(CODE), code.sources().stream().map(Finding.Source::locator).toList(), "the citation passed the evidence gate as it was written");
        assertEquals(Finding.State.draft, code.state(), "one file of a cloned repository is one source, not the person's word: " + out.problems());
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("tidebook evicts the oldest entry first") && p.contains("has one independent source")), out.problems().toString());
        assertEquals(Finding.State.accepted, claim(store, "writes").state(), "the person's own document is their word");

        ObjectNode row = new LibraryProtocol(store).inboxList().stream().filter(o -> o.path("id").asText().equals(code.id())).findFirst().orElseThrow();
        assertEquals("code", row.path("tier").asText(), row.toString());
    }

    @Test
    void twoLinesOfOneFileAreOneSourceAndASecondIndependentSourceStillAcceptsTheClaim(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        store.write(new Finding("F-0001-tidebook-evicts-oldest", "tidebook evicts the oldest entry first", List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "model:test", Instant.now().toString(), "2026-09-23", Finding.Volatility.stable, "", List.of(new Finding.Source(CODE, "n/a", "cited by I-0001")),
                List.of(), null, "tidebook evicts the oldest entry first.\n", new Finding.Triple("tidebook", "evicts", "the oldest entry first"), List.of()));
        String otherLines = "raw/repos/tidebook/src/cache.rs:40-44";
        review(store, "tidebook evicts the oldest entry first (" + otherLines + ").", candidate("evicts", "the oldest entry first", otherLines));
        assertEquals(Finding.State.draft, store.finding("F-0001-tidebook-evicts-oldest").state(), "other lines of the same file are the same source");
        assertEquals(1, Independence.independent(store, List.of(CODE, otherLines, "raw/repos/tidebook/src/cache.rs")));
        assertEquals(2, Independence.independent(store, List.of(CODE, "raw/repos/tidebook/src/store.rs:3")), "another file is another text, as another page is");

        String docs = "https://docs.rs/tidebook/latest/tidebook/cache/index.html";
        review(store, "The documentation says tidebook evicts the oldest entry first. " + docs, candidate("evicts", "the oldest entry first", docs));
        Finding f = store.finding("F-0001-tidebook-evicts-oldest");
        assertEquals(Finding.State.accepted, f.state(), "the code and its documentation are two independent sources: " + f.sources());
    }

    @Test
    void theInventoryReadsTheCitedLinesOfTheCode(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path src = Files.createDirectories(store.rawDir().resolve("repos").resolve("tidebook").resolve("src"));
        Files.writeString(src.resolve("cache.rs"), "// the cache\nfn evict(&mut self) {\n    // drops the oldest entry first\n    self.entries.pop_front();\n}\n");
        Finding f = new Finding("F-0001-tidebook-evicts-oldest", "tidebook evicts the oldest entry first", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "model:test", Instant.now().toString(), "2026-09-23", Finding.Volatility.stable, "",
                List.of(new Finding.Source("raw/repos/tidebook/src/cache.rs:2-4", "n/a", "cited by I-0001")), List.of(), null, "tidebook evicts the oldest entry first.\n",
                new Finding.Triple("tidebook", "evicts", "the oldest entry first"), List.of());
        store.write(f);
        Inventory.Checker checker = (claim, text) -> text.contains("drops the oldest entry first") ? "{\"verdict\": \"supported\", \"reason\": \"the code says so\"}" : "{\"verdict\": \"unsupported\", \"reason\": \"not in these lines\"}";
        Inventory.Check c = Inventory.checkOne(store, new LibrarianIndex(store), checker, f);
        assertEquals("supported", c.verdict(), c.toString());
        assertTrue(c.reason().contains("raw/repos/tidebook/src/cache.rs:2-4"), c.reason());
        Finding gone = new Finding(f.id(), f.title(), f.subjects(), f.state(), f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(),
                List.of(new Finding.Source("raw/repos/tidebook/src/missing.rs:3", "n/a", "cited by I-0001")), f.supersedes(), f.review(), f.body(), f.triple(), f.notes());
        assertEquals("no-capture", Inventory.checkOne(store, new LibrarianIndex(store), checker, gone).verdict(), "a file that is not in the repository is not read");
    }
}
