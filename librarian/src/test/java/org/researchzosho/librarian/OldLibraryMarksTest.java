package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A library that 0.4 or a build before 0.5.0 wrote: nodes.md carries words after a person's kind ("private", "kept private", "kept
 * public", "said to be living", "said to have died"), and merge-marks.tsv carries them in its rows. They are read and left out: the
 * kind, the label, the other names and the Wikidata id stay, whether a person may be living comes from the claims, and the words go
 * the next time the file is written.
 */
class OldLibraryMarksTest {

    private static final String OLD_NODES = "# Graph nodes — the things the findings are about\n\n"
            + "One per line. `private` in the kind marks a person who may be living. A private node is never shown to another patron.\n\n"
            + "- tom ellis — person, private: Tom Ellis | also: Thomas Ellis | wikidata: Q1\n"
            + "- ann hart — person, kept private: Ann Hart\n"
            + "- kimie hale — person, private, kept public: Kimie Hale | also: 圭子\n"
            + "- ken endo — person, said to have died: Ken Endo\n"
            + "- mari endo — person, private, said to be living: Mari Endo\n"
            + "- kure — place: Kure\n";

    private static Graph.Node node(LibraryStore store, String name) throws Exception { Graph g = Graph.build(store); return g.node(g.nodeIdOf(name)); }

    /** The node as genealogy reads it, where whether a person may be living is worked out; the core graph of a library that holds no family work leaves that alone. */
    private static Graph.Node inView(LibraryStore store, String name) throws Exception { Graph g = FamilyPeople.view(store); return g.node(g.nodeIdOf(name)); }

    private static LibraryStore library(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyLivingRuleTest.drafted(store, "Tom Ellis", "born-on", "1850", "Tom Ellis was born in 1850.");
        FamilyLivingRuleTest.drafted(store, "Ann Hart", "lived-in", "Kure", "Ann Hart lived in Kure.");
        FamilyLivingRuleTest.drafted(store, "Kimie Hale", "born-on", "1990", "Kimie Hale was born in 1990.");
        FamilyLivingRuleTest.drafted(store, "Ken Endo", "sibling-of", "Mari Endo", "Ken Endo is a brother of Mari Endo.");
        FamilyLivingRuleTest.drafted(store, "Mari Endo", "born-on", "1880", "Mari Endo was born in 1880.");
        Files.createDirectories(Graph.dir(store));
        Files.writeString(Graph.nodesFile(store), OLD_NODES, StandardCharsets.UTF_8);
        return store;
    }

    @Test
    void theOldWordsAreReadAndLeftOutAndNothingElseIsLost(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        Graph g = Graph.build(store);
        Graph.Node tom = g.node(g.nodeIdOf("Thomas Ellis"));
        assertEquals("tom ellis", tom.id(), "the other name still leads to him");
        assertEquals("person", tom.kind());
        assertEquals("Tom Ellis", tom.label());
        assertEquals(List.of("Thomas Ellis"), tom.aliases());
        assertEquals("Q1", tom.wikidata());
        assertEquals("place", g.node("kure").kind());
        for (String n : List.of("Ann Hart", "Kimie Hale", "Ken Endo", "Mari Endo")) assertEquals("person", node(store, n).kind(), n);
        // whether a person may be living comes from the claims, whatever the old line said
        assertFalse(inView(store, "Tom Ellis").mayBeLiving(), "born 1850, although the old line said private");
        assertTrue(inView(store, "Ann Hart").mayBeLiving(), "no dates");
        assertTrue(inView(store, "Kimie Hale").mayBeLiving(), "born 1990, although the old line said kept public");
        assertTrue(inView(store, "Ken Endo").mayBeLiving(), "a text's word without a date is not a claim: nothing places him in the past");
        assertFalse(inView(store, "Mari Endo").mayBeLiving(), "born 1880, although a text once said she was living");

        // the next write of the file leaves the words out, on every line, and the preamble says only what a line holds
        Graph.alias(store, "Ann Hart", List.of("Ann H. Hart"));
        String now = Files.readString(Graph.nodesFile(store));
        for (String w : List.of("private", "kept public", "said to")) assertFalse(now.contains(w), w + " is gone: " + now);
        assertTrue(now.contains("- tom ellis — person: Tom Ellis | also: Thomas Ellis | wikidata: Q1\n"), now);
        assertTrue(now.contains("- kimie hale — person: Kimie Hale | also: 圭子\n"), now);
        assertTrue(now.contains("- ann hart — person: Ann Hart | also: Ann H. Hart\n"), now);
        assertTrue(now.contains("- kure — place: Kure\n"), now);
    }

    @Test
    void everyWriteOfALineLeavesTheWordsOut(@TempDir Path tmp) throws Exception {
        for (String act : List.of("kind", "merge", "drop")) {
            LibraryStore store = library(tmp.resolve(act));
            switch (act) {
                case "kind" -> Graph.setKind(store, "Kure", "place");
                case "merge" -> Graph.merge(store, "Ann Hart", "Kimie Hale", "person");
                default -> Graph.dropAliases(store, List.<String[]>of(new String[]{"Tom Ellis", "Thomas Ellis"}));
            }
            String now = Files.readString(Graph.nodesFile(store));
            assertFalse(now.contains("private") || now.contains("kept public") || now.contains("said to"), act + ": " + now);
            assertTrue(now.contains("- kure — place: Kure"), act + ": " + now);
        }
    }

    @Test
    void anUnmergeReadsAnOldRowForTheKind(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyLivingRuleTest.drafted(store, "T. Hale", "lived-in", "Leeds", "T. Hale lived in Leeds (1880).");
        FamilyLivingRuleTest.drafted(store, "Tom Hale", "born-on", "1850", "Tom Hale was born in 1850.");
        Files.createDirectories(Graph.dir(store));
        Files.writeString(Graph.nodesFile(store), "# Graph nodes\n\n- t. hale — person, kept private: T. Hale\n- tom hale — person, private, kept private: Tom Hale | also: T. Hale\n", StandardCharsets.UTF_8);
        Files.writeString(Graph.mergesFile(store), "t. hale\ttom hale\tperson\t2026-09-20\n", StandardCharsets.UTF_8);
        Files.writeString(Graph.mergeMarksFile(store), "t. hale\ttom hale\tconcept, private: Tom Hale\tperson, private, kept private: Tom Hale\n", StandardCharsets.UTF_8);
        Graph.unmerge(store, "T. Hale", null, "person", "");
        Vocabulary nodes = Vocabulary.read(Graph.nodesFile(store));
        assertEquals("concept: Tom Hale", nodes.get("tom hale").description(), "the kind the merge set is taken back to the one before it");
        assertEquals("person: T. Hale", nodes.get("t. hale").description());
        assertFalse(Files.readString(Graph.nodesFile(store)).contains("private"), Files.readString(Graph.nodesFile(store)));
    }
}
