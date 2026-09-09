package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LibrarianIndexTest {

    @TempDir
    Path tmp;

    private static Finding finding(String id, String title, String body, String... subjects) {
        return new Finding(id, title, List.of(subjects), Finding.State.draft,
                Finding.ClaimType.extraction, Finding.Confidence.medium, "person",
                "2026-09-01T10:00:00Z", "2026-09-01", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org", "n/a", "src")),
                List.of(), null, body);
    }

    @Test
    void upsertSearchAndIncrementalReplace() throws Exception {
        LibraryStore store = new LibraryStore(tmp);
        store.init();
        LibrarianIndex idx = new LibrarianIndex(store);
        idx.upsert(finding("F-0001-consistent-hashing", "Consistent hashing distributes load",
                "Ring topology, virtual nodes, rebalancing on membership change.\n", "distributed--systems"));
        idx.upsert(finding("F-0002-raft-leases", "Raft leader leases",
                "Leases avoid read quorums; clock skew is the hazard.\n", "distributed--systems"));

        List<LibrarianIndex.Hit> hits = idx.search("virtual nodes rebalancing", 5);
        assertFalse(hits.isEmpty());
        assertEquals("F-0001-consistent-hashing", hits.get(0).id());
        assertEquals("finding", hits.get(0).kind());

        // upsert replaces by id — no duplicate documents
        idx.upsert(finding("F-0001-consistent-hashing", "Consistent hashing distributes load",
                "Now the body says jump consistent hash instead.\n", "distributed--systems"));
        assertTrue(idx.search("virtual nodes rebalancing", 5).stream()
                .noneMatch(h -> h.id().equals("F-0001-consistent-hashing")));
        assertEquals("F-0001-consistent-hashing",
                idx.search("jump consistent hash", 5).get(0).id());
    }

    @Test
    void cjkTextIsSearchable() throws Exception {
        // The analyzer decision this pins: StandardAnalyzer would unigram 字幕 and this JA
        // query would drown; CJKAnalyzer bigrams it and the match is exact and top-ranked.
        LibraryStore store = new LibraryStore(tmp);
        store.init();
        LibrarianIndex idx = new LibrarianIndex(store);
        idx.upsert(finding("F-0001-ja-subtitles", "JA subtitle register",
                "ヤクザの話し方は直訳すると語調が失われる。字幕の翻訳には文体の知識が必要。\n",
                "translation--register"));
        idx.upsert(finding("F-0002-unrelated", "Unrelated English entry",
                "Nothing about Japan here at all.\n", "test--other"));

        List<LibrarianIndex.Hit> hits = idx.search("字幕の翻訳", 5);
        assertFalse(hits.isEmpty(), "JA query must match the JA entry");
        assertEquals("F-0001-ja-subtitles", hits.get(0).id());
    }

    @Test
    void rebuildRecoversFromDeletedIndex() throws Exception {
        LibraryStore store = new LibraryStore(tmp);
        store.init();
        store.write(finding(store.nextFindingId("Alpha topic"), "Alpha topic", "About alpha.\n", "t--a"));
        store.write(new Investigation("I-0001-alpha-run", "The alpha investigation",
                Finding.State.draft, "person", "2026-09-01T10:00:00Z", List.of(), List.of(),
                "We investigated alpha thoroughly.\n"));
        LibrarianIndex idx = new LibrarianIndex(store);
        assertEquals(2, idx.rebuild());
        assertEquals("I-0001-alpha-run", idx.search("investigated alpha thoroughly", 5).get(0).id());
    }

    @Test
    void hostileQuerySyntaxIsDataNotSyntax() throws Exception {
        LibraryStore store = new LibraryStore(tmp);
        store.init();
        LibrarianIndex idx = new LibrarianIndex(store);
        idx.upsert(finding("F-0001-plain", "Plain entry", "Some content.\n", "t--a"));
        // must not throw, and must not be interpreted as boolean/range operators
        assertDoesNotThrow(() -> idx.search("content AND (id:[a TO z] OR \"", 5));
        assertTrue(idx.search("", 5).isEmpty());
    }
}
