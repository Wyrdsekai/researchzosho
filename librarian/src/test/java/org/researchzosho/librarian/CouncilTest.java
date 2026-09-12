package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CouncilTest {

    @TempDir Path tmp;

    private LibraryStore store;

    private Finding draft(String id, String title, Finding.State state, Finding.Review review) throws Exception {
        Finding f = new Finding(id, title, List.of(), state, Finding.ClaimType.synthesis,
                Finding.Confidence.medium, "model:test", Instant.now().toString(), "2026-09-02",
                Finding.Volatility.slow, "", List.of(new Finding.Source("https://note.com/x", "n/a", "s")),
                List.of(), review, "Claim body.\n");
        store.write(f);
        return f;
    }

    @Test
    void inboxListsDraftsAndStaleReviewsOldestFirst() throws Exception {
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        Finding a = draft("F-0001-a", "First draft", Finding.State.draft, null);
        Finding ok = draft("F-0002-b", "Accepted and current", Finding.State.accepted, null);
        Finding fresh = new Finding(ok.id(), ok.title(), ok.subjects(), Finding.State.accepted, ok.claimType(),
                ok.confidence(), ok.writer(), ok.recordedAt(), ok.validAsOf(), ok.volatility(), ok.reviewBy(),
                ok.sources(), ok.supersedes(), new Finding.Review(1, "librarian:t", "accepted", ok.contentHash(), "t"), ok.body());
        store.write(fresh);
        // an accepted finding whose body was edited after approval → stale → in the inbox
        Finding edited = new Finding(fresh.id(), fresh.title(), fresh.subjects(), fresh.state(), fresh.claimType(),
                fresh.confidence(), fresh.writer(), fresh.recordedAt(), fresh.validAsOf(), fresh.volatility(),
                fresh.reviewBy(), fresh.sources(), fresh.supersedes(), fresh.review(), fresh.body() + "edited\n");
        store.write(edited);
        var rows = new Council(store).inbox();
        assertEquals(2, rows.size());
        assertEquals("F-0001-a", rows.get(0).id());
        assertTrue(rows.stream().anyMatch(r -> r.id().equals("F-0002-b") && r.stale()));
        assertEquals(SourceTier.blog, rows.get(0).tier());
    }

    @Test
    void acceptSignsAsPersonWithTheCurrentHash() throws Exception {
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        draft("F-0001-a", "A draft", Finding.State.draft, new Finding.Review(1, "librarian:t", "draft", "sha256:old", "t"));
        Finding f = new Council(store).accept("F-0001-a");
        assertEquals(Finding.State.accepted, f.state());
        assertEquals("person", f.review().reviewer());
        assertEquals(2, f.review().round(), "a new round on top of the librarian's");
        assertFalse(f.reviewStale());
        assertEquals(f, store.finding("F-0001-a"));
        assertTrue(Files.readString(store.circulationFile()).contains("council-accepted"));
    }

    @Test
    void acceptTakesSeveralIdsOrAll() throws Exception {
        String realHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());
            store = new LibraryStore(tmp.resolve("codezaiku-library")); store.init();
            draft("F-0001-a", "A", Finding.State.draft, null);
            draft("F-0002-b", "B", Finding.State.draft, null);
            draft("F-0003-c", "C", Finding.State.draft, null);
            draft("F-0004-d", "D", Finding.State.accepted, new Finding.Review(1, "person", "accepted", "sha256:x", "t"));
            assertEquals(0, LibrarianCli.run(new String[]{"librarian", "accept", "F-0001-a", "F-0002-b"}, "http://none", "m"));
            assertEquals(Finding.State.accepted, store.finding("F-0001-a").state());
            assertEquals(Finding.State.accepted, store.finding("F-0002-b").state());
            assertEquals(Finding.State.draft, store.finding("F-0003-c").state());
            assertEquals(0, LibrarianCli.run(new String[]{"librarian", "accept", "--all"}, "http://none", "m"));
            assertEquals(Finding.State.accepted, store.finding("F-0003-c").state());
            assertEquals(1, store.finding("F-0004-d").review().round(), "an already-accepted entry is not re-signed by --all");
        } finally {
            System.setProperty("user.home", realHome);
        }
    }

    @Test
    void disputeKeepsTheEntryAndFeedsTheFrontier() throws Exception {
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        draft("F-0001-a", "A contested reading", Finding.State.accepted, null);
        Finding f = new Council(store).dispute("F-0001-a", "the edition cited is a paraphrase");
        assertEquals(Finding.State.disputed, f.state());
        assertTrue(f.body().contains("DISPUTED-BY: person"));
        assertTrue(Files.readString(store.frontierFile()).contains("[dispute] F-0001-a"));
        assertTrue(Files.exists(store.findingsDir().resolve("F-0001-a.md")), "never deleted");
    }

    @Test
    void retireIsAStateNotADelete() throws Exception {
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        draft("F-0001-a", "Old claim", Finding.State.accepted, null);
        assertEquals(Finding.State.retired, new Council(store).retire("F-0001-a").state());
        assertTrue(Files.exists(store.findingsDir().resolve("F-0001-a.md")));
        assertThrows(java.io.IOException.class, () -> new Council(store).accept("F-9999-absent"));
    }

    @Test
    void mcpOnAMachineWithNoLibraryMakesOne() throws Exception {
        String realHome = System.getProperty("user.home");
        java.io.InputStream realIn = System.in;
        try {
            System.setProperty("user.home", tmp.toString());
            System.setIn(new java.io.ByteArrayInputStream(new byte[0]));   // the client closes at once: the server ends on EOF
            assertFalse(Files.isDirectory(tmp.resolve("researchzosho-library")));
            assertEquals(0, LibrarianCli.run(new String[]{"librarian", "mcp"}, "http://none", "m"));
            assertTrue(Files.isDirectory(tmp.resolve("researchzosho-library").resolve("catalog")), "made on the way in");
        } finally {
            System.setProperty("user.home", realHome);
            System.setIn(realIn);
        }
    }
}
