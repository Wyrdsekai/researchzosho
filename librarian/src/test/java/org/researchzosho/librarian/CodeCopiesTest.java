package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One file of a cloned repository is one source however a run reaches it: other lines of it, a forge's page of it, a captured copy of
 * its text, and the repository's own page for its README. So a claim on one file is never accepted because the same file came back
 * another way, and a report marks the other lines of a file as the same file.
 */
class CodeCopiesTest {

    static final String README = """
            # tidebook

            tidebook is a small embedded key value store for append heavy workloads.
            tidebook evicts the oldest entry first when its cache is full, so the most recent writes stay hot.
            It keeps an append only log on disk and replays the log at start up to rebuild its index.
            Compaction runs in the background and rewrites segments that are more than half garbage.
            The cache size is set in megabytes through the TIDEBOOK_CACHE variable and defaults to sixty four.
            Reads never block writes; writers take a short lock on the tail segment only.
            tidebook is released under the MIT license and welcomes pull requests with tests.
            """;
    static final String CODE = "raw/repos/tidebook/README.md:3";
    static final String RAWGH = "https://raw.githubusercontent.com/tide/tidebook/main/README.md";

    private static LibraryStore store(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path repo = Files.createDirectories(store.rawDir().resolve("repos").resolve("tidebook"));
        Files.writeString(repo.resolve("README.md"), README);
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src").resolve("cache.rs"), "// the cache\nfn evict(&mut self) {\n    self.entries.pop_front();\n}\n");
        return store;
    }

    /** A clone that says where it came from, as git writes it. */
    private static void origin(LibraryStore store, String name, String url) throws Exception {
        Path git = Files.createDirectories(store.rawDir().resolve("repos").resolve(name).resolve(".git"));
        Files.writeString(git.resolve("config"), "[core]\n\trepositoryformatversion = 0\n[remote \"origin\"]\n\turl = " + url + "\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n");
    }

    static String candidate(String source) {
        return """
                [{"title": "tidebook evicts the oldest entry first", "claim": "tidebook evicts the oldest entry first.", "claim_type": "extraction", "confidence": "high",
                  "volatility": "stable", "sources": ["%s"], "triple": {"subject": "tidebook", "predicate": "evicts", "object": "the oldest entry first"}}]""".formatted(source);
    }

    static LibrarianReview.Outcome review(LibraryStore store, String said, String source) throws Exception {
        LibrarianIndex index = new LibrarianIndex(store);
        Investigation inv = Acquisitions.admit(store, index, "How does tidebook evict its cache?", said, "model:test");
        return new LibrarianReview(store, index, new LibrarianReview.Judge() {
            @Override public String extract(String b) { return candidate(source); }
            @Override public String compare(String c, String n) { return "{\"verdict\": \"independent\"}"; }
        }, "librarian:test").review(inv);
    }

    private static Finding evicts(LibraryStore store) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("evicts")).findFirst().orElseThrow();
    }

    @Test
    void aCapturedCopyOfTheCitedFileIsTheSameSourceSoTheClaimStaysADraft(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        RawCapture.capture(store, RAWGH, README, "README.md", "test", "");
        assertEquals(1, Independence.independent(store, List.of(CODE, RAWGH)), "the file and a copy of its text are one text");

        review(store, "tidebook evicts the oldest entry first (" + CODE + ").", CODE);
        Finding f = evicts(store);
        assertEquals(Finding.State.draft, f.state());
        var again = review(store, "The README says tidebook evicts the oldest entry first. " + RAWGH, RAWGH);
        assertEquals(Finding.State.draft, store.finding(f.id()).state(), "one file seen twice is one source: " + again);
    }

    @Test
    void aForgesPageOfAClonedFileIsThatFileAndTheRepositorysPageIsItsReadme(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        origin(store, "tidebook", "https://github.com/tide/tidebook.git");
        assertEquals("raw/repos/tidebook/README.md", CodeTool.clonedFileOf(store, "https://github.com/tide/tidebook/blob/main/README.md"));
        assertEquals("raw/repos/tidebook/src/cache.rs", CodeTool.clonedFileOf(store, "https://github.com/tide/tidebook/blob/main/src/cache.rs#L2-L3"));
        assertEquals("raw/repos/tidebook/README.md", CodeTool.clonedFileOf(store, "https://github.com/Tide/TideBook"), "the repository's own page shows its README");
        assertEquals("raw/repos/tidebook/README.md", CodeTool.clonedFileOf(store, RAWGH));
        assertNull(CodeTool.clonedFileOf(store, "https://github.com/fork/tidebook/blob/main/README.md"), "another owner's copy is not the clone");
        assertNull(CodeTool.clonedFileOf(store, "https://github.com/tide/tidebook/issues/4"), "an issue is not a file of the code");

        assertEquals(1, Independence.independent(store, List.of(CODE, "https://github.com/tide/tidebook/blob/main/README.md")), "no capture is needed to know it");
        assertEquals(1, Independence.independent(store, List.of("raw/repos/tidebook/src/cache.rs:3", "https://github.com/tide/tidebook/blob/main/src/cache.rs#L3")));
        assertEquals(1, Independence.independent(store, List.of(CODE, "https://github.com/tide/tidebook")));
        assertEquals(2, Independence.independent(store, List.of(CODE, "https://github.com/tide/tidebook/blob/main/src/cache.rs")), "another file is another text");

        origin(store, "tidebook", "git@gitlab.com:tide/tools/tidebook.git");
        assertEquals("raw/repos/tidebook/src/cache.rs", CodeTool.clonedFileOf(store, "https://gitlab.com/tide/tools/tidebook/-/blob/main/src/cache.rs"));
        assertEquals("raw/repos/tidebook/README.md", CodeTool.clonedFileOf(store, "https://gitlab.com/tide/tools/tidebook/-/tree/main"));
        assertNull(CodeTool.clonedFileOf(store, "https://github.com/tide/tidebook/blob/main/README.md"), "the clone came from gitlab now");
        origin(store, "tidebook", "https://codeberg.org/tide/tidebook");
        assertEquals("raw/repos/tidebook/src/cache.rs", CodeTool.clonedFileOf(store, "https://codeberg.org/tide/tidebook/src/branch/main/src/cache.rs"));
        assertEquals("raw/repos/tidebook/README.md", CodeTool.clonedFileOf(store, "https://codeberg.org/tide/tidebook/raw/branch/main/README.md"));

        Files.delete(store.rawDir().resolve("repos").resolve("tidebook").resolve(".git").resolve("config"));
        assertNull(CodeTool.clonedFileOf(store, "https://codeberg.org/tide/tidebook/src/branch/main/src/cache.rs"), "a clone that does not say where it came from is matched by nothing");
    }

    @Test
    void aCitationOfAFileIsWrittenOneWay() {
        assertEquals("raw/repos/tidebook/src/cache.rs", CodeTool.fileOf("raw/repos/tidebook/./src//cache.rs#L12-L20"));
        assertEquals("raw/repos/tidebook/src/cache.rs", CodeTool.fileOf("raw/repos/tidebook/src/cache.rs:40-44"));
        assertEquals("raw/repos/tidebook/README.md", CodeTool.fileOf("raw/repos/tidebook/src/../README.md:3"));
        assertNull(CodeTool.fileOf("raw/repos/tidebook/../other/secret.txt"), "a path that climbs out of the repository is not a file of it");
        assertNull(CodeTool.fileOf("raw/repos/../x/y.rs"));
        assertNull(CodeTool.fileOf("https://github.com/tide/tidebook"));
    }

    private static Finding claimOn(String id, String predicate, String... sources) {
        List<Finding.Source> cited = new ArrayList<>();
        for (String s : sources) cited.add(new Finding.Source(s, "n/a", "cited by I-0001"));
        return new Finding(id, "tidebook " + predicate, List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "model:test",
                Instant.now().toString(), "2026-09-23", Finding.Volatility.stable, "", cited, List.of(), null, "tidebook " + predicate + ".\n",
                new Finding.Triple("tidebook", predicate, "yes"), List.of());
    }

    @Test
    void whatRestsOnAFileIsEveryClaimOnAnyOfItsLines(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        origin(store, "tidebook", "https://github.com/tide/tidebook");
        String docs = "https://docs.rs/tidebook/latest/tidebook/cache/index.html";
        store.write(claimOn("F-0001-disputed-now", "evicts-oldest", "raw/repos/tidebook/src/cache.rs:2"));
        store.write(claimOn("F-0002-other-lines", "caps-the-cache", "raw/repos/tidebook/src/cache.rs:3-4"));
        store.write(claimOn("F-0003-two-lines", "logs-evictions", "raw/repos/tidebook/src/cache.rs:2", "raw/repos/tidebook/src/cache.rs#L3"));
        store.write(claimOn("F-0004-forge-page", "is-a-cache", "https://github.com/tide/tidebook/blob/main/src/cache.rs"));
        store.write(claimOn("F-0005-with-docs", "drops-the-front", "raw/repos/tidebook/src/cache.rs:3", docs));
        store.write(claimOn("F-0006-another-file", "has-a-readme", "raw/repos/tidebook/README.md:1"));
        Evidence.Resting r = Evidence.restingOn(store, "raw/repos/tidebook/src/cache.rs:2", "F-0001-disputed-now");
        assertEquals(List.of("F-0002-other-lines", "F-0003-two-lines", "F-0004-forge-page"), r.alone().stream().map(Finding::id).sorted().toList(),
                "other lines of the file, and its page on the forge, are the same source: nothing else holds them up");
        assertEquals(List.of("F-0005-with-docs"), r.backed().stream().map(Finding::id).toList(), "the documentation is another source");
    }

    @Test
    void aReportMarksOtherLinesOfOneFileAsTheSameFileAndCountsThemOnce(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
            Path src = Files.createDirectories(store.rawDir().resolve("repos").resolve("tidebook").resolve("src"));
            StringBuilder code = new StringBuilder();
            for (int i = 1; i <= 60; i++) code.append("    // line ").append(i).append(" of the cache module, evicting entries in insertion order\n");
            Files.writeString(src.resolve("cache.rs"), code.toString());
            String docs = "https://docs.rs/tidebook/latest/tidebook/cache/index.html";
            RawCapture.capture(store, docs, "The cache evicts the oldest entry first when it is full. " + BestRunnerTest.lorem("doc", 300), "tidebook::cache - Rust documentation page", "test", "");
            List<String> cited = List.of("raw/repos/tidebook/src/cache.rs:12", "raw/repos/tidebook/src/cache.rs:40-44", docs);
            var clusters = Independence.clusters(store, cited);
            assertEquals(clusters.get(cited.get(0)), clusters.get(cited.get(1)), "two places in one file: " + clusters);
            assertEquals(2, new HashSet<>(clusters.values()).size(), clusters.toString());

            String evidence = "SUB-QUESTION: q\nSUMMARY: s\nEVIDENCE:\n"
                    + "- tidebook evicts the oldest entry first — source: raw/repos/tidebook/src/cache.rs:12 — quote: \"line 12\"\n"
                    + "- tidebook caps the cache — source: raw/repos/tidebook/src/cache.rs:40-44 — quote: \"line 40\"\n"
                    + "- the docs say the oldest goes first — source: " + docs + " — quote: \"evicts the oldest entry first\"\n";
            var r = new Researcher(BestRunnerTest.judgeSaying("{\"verdict\":\"supported\"}"), BestRunnerTest.judgeSaying("{\"verdict\":\"supported\"}"), BestRunnerTest.fakeTools(""), null, 1, store);
            var syn = new Researcher.Synthesis(true, List.of("## Answer\n\ntidebook evicts the oldest entry first [1] and caps the cache [2]; the docs agree [3]."));
            String out = r.assemble(new Researcher.Ask("How does tidebook evict its cache entries?", "broad", 20, List.of()), syn, evidence, new Researcher.Budget(20), new ArrayList<>());
            String refs = out.substring(out.indexOf("## References"));
            assertTrue(refs.contains("[2] raw/repos/tidebook/src/cache.rs:40-44  (the same file as [1])"), refs);
            assertTrue(refs.contains("3 source(s), 2 independent"), refs);
        } finally { System.setProperty("user.home", real); }
    }
}
