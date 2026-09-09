package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The push and the desk package are deterministic, so their honesty properties are pinnable:
 * the authority-tier sentence rides with every push, disputed/draft entries carry their flags,
 * and an empty library answers "nothing" — never a guess.
 */
class LibraryPushTest {

    @TempDir
    Path tmp;

    private LibraryStore store;
    private LibrarianIndex index;

    private void seed() throws Exception {
        store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        index = new LibrarianIndex(store);
        write("F-0001-accepted-claim", "Model A supports word-level output",
                Finding.State.accepted, "Model A emits word timestamps.\n");
        write("F-0002-contested-reading", "The flood narrative reading",
                Finding.State.disputed, "Reading one of the flood narrative.\n");
        // the push is relevance-floored (half the query's terms): this draft must be ABOUT the
        // flood narrative to ride along, not merely mention a flood
        write("F-0003-fresh-draft", "Unreviewed draft about flood myths",
                Finding.State.draft, "Flood narrative myths across cultures share a source.\n");
        write("F-0004-retired-entry", "Old flood claim, retired",
                Finding.State.retired, "Superseded flood-related claim.\n");
    }

    private void write(String id, String title, Finding.State state, String body) throws Exception {
        Finding f = new Finding(id, title, List.of(), state, Finding.ClaimType.interpretation,
                Finding.Confidence.medium, "person", Instant.now().toString(), "2026-09-01",
                Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/" + id, "trans. Example, 2001", "s")),
                List.of(), null, body);
        store.write(f);
        index.upsert(f);
    }

    @Test
    void blockCarriesAuthoritySentenceAndStateFlags() throws Exception {
        seed();
        String block = LibraryPush.block(store, "flood narrative reading", 6);
        assertTrue(block.contains("never overrides direct evidence"), "authority tier at the consumption site");
        assertTrue(block.contains("(DISPUTED) The flood narrative reading"), block);
        assertTrue(block.contains("(unreviewed draft) Unreviewed draft"), block);
        assertFalse(block.contains("retired"), "retired entries are not pushed");
        assertTrue(block.contains("https://example.org/F-0002"), "sources ride along");
    }

    @Test
    void blockIsEmptyWhenNothingRelevant() throws Exception {
        seed();
        assertEquals("", LibraryPush.block(store, "quantum chromodynamics lattice", 4));
    }

    @Test
    void noLibraryMeansEmptyPushAndHonestDeskAnswer() {
        // The default location is ~/codezaiku-library — which EXISTS on a developer's machine
        // once they use the thing (it did on 2026-09-01, and this test went red). Fake home to
        // an empty temp dir for the duration; restore the real value, never clear it.
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("nohome").toString());
        try {
            assertEquals("", LibraryPush.block("anything", 4));
            assertTrue(LibraryPush.answerPackage("anything", 4).contains("no library"));
        } finally {
            System.setProperty("user.home", realHome);
        }
    }

    @Test
    void answerPackageRendersFullEntriesWithSourcesAndEditions() throws Exception {
        seed();
        String pkg = LibraryPush.answerPackage(store, "flood narrative", 5);
        assertTrue(pkg.contains("THE LIBRARIAN"), pkg);
        assertTrue(pkg.contains("F-0002-contested-reading [disputed, interpretation"), pkg);
        assertTrue(pkg.contains("Reading one of the flood narrative."), "full body, not a summary");
        assertTrue(pkg.contains("(trans. Example, 2001)"), "edition discipline surfaces at the desk");
    }

    @Test
    void answerPackageSaysNothingHonestly() throws Exception {
        seed();
        String pkg = LibraryPush.answerPackage(store, "baroque harpsichord tuning", 5);
        assertTrue(pkg.contains("holds NOTHING"), pkg);
        assertFalse(pkg.contains("=="), "no entries rendered when none match");
    }

    @Test
    void answerPackageSurfacesFrontierThreads() throws Exception {
        seed();
        store.frontier("trajectory", "what does the next flood-myth synthesis need?");
        String pkg = LibraryPush.answerPackage(store, "flood myth synthesis", 5);
        assertTrue(pkg.contains("OPEN THREADS"), pkg);
        assertTrue(pkg.contains("[trajectory]"), pkg);
    }
}
