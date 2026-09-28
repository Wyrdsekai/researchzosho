package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `genealogy tidy` and one name written two ways, one entry with no fact about it. A pair the person keeps apart is written down as two
 * people and not shown again, as the question promises. A name of two characters, 李明 and 李 明, is offered like a longer one, as the
 * note a command prints about it promises.
 */
class FamilyTidyTwinsTest {

    @Test
    void aPairKeptApartIsWrittenDownAsTwoPeopleAndNotShownAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameSpacingTest.library(tmp);
        String first = FamilyNameSpacingTest.cli(store, "tidy")[0];
        assertTrue(first.contains("  1. 山田 太郎   ←   山田太郎") && first.contains("  2. 高橋 正一   ←   髙橋正一"), first);
        String done = FamilyNameSpacingTest.cli(store, "tidy", "--yes", "--apart", "1")[0];
        assertTrue(done.contains("1 pair was written down as two people"), done);
        String again = FamilyNameSpacingTest.cli(store, "tidy")[0];
        assertFalse(again.contains("山田太郎"), "not shown again: " + again);
        assertFalse(FamilyChecks.forPerson(store, FamilyChecks.check(store)).contains("graph merge \"山田太郎\""), "nor by the check");
    }

    /** 李 明 is the person the facts are about; 李明 is an entry of the list of names that only a claim's concepts name. */
    static LibraryStore twoCharacters(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Li Ming", "born-on", "1890", "", "q"), new FamilyAccount.Fact("Li Ming", "died-on", "1950", "", "q")), List.of()),
                "file:///family/notes.txt", "an aunt");
        Path nodes = Graph.nodesFile(store);
        List<String> kept = new ArrayList<>();
        for (String l : Files.readAllLines(nodes, StandardCharsets.UTF_8)) if (!l.startsWith("- li ming")) kept.add(l);
        kept.add("- 李 明 — person: 李 明 | also: Li Ming");
        kept.add("- 李明 — person: 李明");
        Files.write(nodes, kept, StandardCharsets.UTF_8);
        Finding born = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("born-on")).findFirst().orElseThrow();
        List<Finding.Note> notes = new ArrayList<>(born.notes());
        notes.add(new Finding.Note(Concepts.NOTE, "librarian:test", "2026-09-24", "李明"));
        store.write(new Finding(born.id(), born.title(), born.subjects(), born.state(), born.claimType(), born.confidence(), born.writer(), born.recordedAt(), born.validAsOf(),
                born.volatility(), born.reviewBy(), born.sources(), born.supersedes(), born.review(), born.body(), born.triple(), notes));
        return store;
    }

    @Test
    void aTwoCharacterNameWrittenTwoWaysIsOfferedAsTheNoteSays(@TempDir Path tmp) throws Exception {
        LibraryStore store = twoCharacters(tmp);
        Graph g = FamilyPeople.view(store);
        assertNotNull(g.node(g.nodeIdOf("李明")), "an entry the graph holds");
        assertEquals(0, FamilyQuestions.facts(g, g.nodeIdOf("李明")), "and no fact is about it");
        FamilyQuestions.Found f = FamilyQuestions.find(store, "李明");
        assertEquals("李 明", f.person(), f.toString());
        assertTrue(f.note().contains("genealogy tidy offers to join"), f.note());
        String asked = FamilyNameSpacingTest.cli(store, "tidy")[0];
        assertTrue(asked.contains("李 明   ←   李明"), "tidy offers it, as the note says: " + asked);
        assertTrue(FamilyChecks.forPerson(store, FamilyChecks.check(store)).contains("graph merge \"李明\" \"李 明\""), "the check too");
        FamilyNameSpacingTest.cli(store, "tidy", "--yes");
        FamilyQuestions.Found after = FamilyQuestions.find(store, "李明");
        assertEquals("李 明", after.person());
        assertEquals("", after.note(), "one entry now: " + after.note());
    }
}
