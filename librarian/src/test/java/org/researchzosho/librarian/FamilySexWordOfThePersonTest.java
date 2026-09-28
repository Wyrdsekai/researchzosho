package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A word for a man or a woman counts for the person it names: "my mother's uncle" makes nobody a woman but the mother, "my mother (Emi
 * Hale)" is Emi's word, and "My mother was born during his first stay" is the mother's, not the man's whose stay it was. Where a person's
 * sex claims disagree, the family's view keeps the one whose words say it of the person.
 */
class FamilySexWordOfThePersonTest {

    private static FamilyAccount.Fact said(String s, String r, String o, String quote) { return new FamilyAccount.Fact(s, r, o, "", quote); }

    private static LibraryStore store(Path tmp, String teller, FamilyAccount.Fact... facts) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(facts), List.of()), "file:///community.pdf", teller);
        return store;
    }

    @Test
    void aParentsOrAChildsWordCountsForThePersonItNames() {
        assertNull(FamilyKin.sexFromQuote("child-of", "the writer of community.pdf's mother", "Tom Hale", "My mother was born during his first stay"),
                "the mother is the writer's mother, the child of this claim; Tom's is \"his\"");
        assertNull(FamilyKin.sexFromQuote("parent-of", "Isamu Morita", "Ken Morita", "Isamu Morita - my mother (Emi Hale) and (Ken Morita)'s uncle"), "the mother is Emi");
        assertNull(FamilyKin.sexFromQuote("parent-of", "森田勇", "森田健二", "森田勇 - 母（遠藤ハル）の叔父"), "the mother is ハル, and 母 is a step of a chain");
        assertNull(FamilyKin.sexFromQuote("child-of", "Ken Hale", "Tom Hale", "Tom Hale, my mother's uncle, and his boy Ken"), "a step of a chain: the mother is somebody else");
        // a word that names the person, or nobody in particular, counts as before
        assertArrayEquals(new String[]{"Tom Hale", "male"}, FamilyKin.sexFromQuote("parent-of", "Tom Hale", "Kimie Hale", "Kimie Hale, whose father was Tom Hale"));
        assertArrayEquals(new String[]{"森田正一", "male"}, FamilyKin.sexFromQuote("child-of", "森田まり", "森田正一", "父：森田正一"));
        assertArrayEquals(new String[]{"Ann Hale", "female"}, FamilyKin.sexFromQuote("child-of", "Ann Hale", "Tom Hale", "Tom Hale's daughter Ann Hale"));
        assertArrayEquals(new String[]{"Ann Hale", "female"}, FamilyKin.sexFromQuote("child-of", "Ann Hale", "Tom Hale", "his daughter Ann was born in Leeds"), "Ann of Ann Hale");
        assertArrayEquals(new String[]{"森田正一", "male"}, FamilyKin.sexFromQuote("child-of", "森田正一", "森田勇", "長男 正一"));
        assertArrayEquals(new String[]{"Tom Hale", "male"}, FamilyKin.sexFromQuote("parent-of", "Tom Hale", "Kimie Hale", "her father kept a shop in Leeds"));
        assertNull(FamilyKin.sexFromQuote("parent-of", "Ann Hart", "Kimie Hale", "Kimie's mother and father came from Dunedin"), "both words: neither");
    }

    @Test
    void aStepOfAChainIsNobodysOwnWord() {
        assertEquals("male", FamilyKin.sexInWords("Ken Morita is my mother's brother.", List.of("Ken Morita"), List.of(), List.of()), "the mother is somebody else");
        assertEquals("female", FamilyKin.sexInWords("My mother's name was Ann Hale.", List.of("Ann Hale"), List.of(), List.of()), "a name of hers is hers");
        assertEquals("male", FamilyKin.sexInWords("森田勇は母の兄である。", List.of("森田勇"), List.of(), List.of()), "母の兄: the brother is 勇");
    }

    @Test
    void theViewKeepsTheSexTheWordsSayOfThePerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "the writer of community.pdf",
                said("the writer of community.pdf's mother", "child-of", "Tom Hale", "My mother was born during his first stay"),
                said("Tom Hale", "sex", "female", "My mother was born during his first stay"),
                said("Tom Hale", "parent-of", "Ned Hale", "Ned Hale's father Tom Hale kept a shop."),
                said("Tom Hale", "sex", "male", "Ned Hale's father Tom Hale kept a shop."),
                said("Mary Ellis", "sex", "female", "She was a teacher."),
                said("Mary Ellis", "sex", "male", "Mary Ellis was a teacher, and he was one too."));
        Graph g = FamilyPeople.view(store);
        Map<String, Map<String, List<String>>> sexes = FamilyKin.sexes(g);
        assertEquals(Map.of("male", 1), counts(sexes.get(g.nodeIdOf("Tom Hale"))), "the woman's claim rests on the mother's word");
        assertEquals("male", FamilyKin.sexOf(sexes, g.nodeIdOf("Tom Hale")));
        assertEquals(2, counts(sexes.get(g.nodeIdOf("Mary Ellis"))).size(), "where the words settle nothing both stay, for the checks");
        List<String> aside = FamilyDoubts.about(store, g, g.nodeIdOf("Tom Hale"));
        assertEquals(1, aside.size(), aside.toString());
        assertTrue(aside.get(0).contains("Tom Hale is recorded as female") && aside.get(0).contains("do not say it of Tom Hale"), aside.get(0));
        Graph core = Graph.build(store);
        assertEquals(2, counts(FamilyKin.sexes(core).get(core.nodeIdOf("Tom Hale"))).size(), "the claims themselves stay as they were");
        String page = FamilyWhoATextIsByTest.run(store, "names", "Tom Hale");
        assertTrue(page.contains(FamilyDoubts.HEADING + ":") && page.contains("do not say it of Tom Hale"), page);
    }

    @Test
    void anAcceptedClaimStandsWhateverTheWordsSay(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "the writer of community.pdf",
                said("Tom Hale", "sex", "female", "My mother was born during his first stay"),
                said("Tom Hale", "parent-of", "Ned Hale", "Ned Hale's father Tom Hale kept a shop."),
                said("Tom Hale", "sex", "male", "Ned Hale's father Tom Hale kept a shop."));
        Finding female = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().object().equals("female")).findFirst().orElseThrow();
        new Council(store).accept(female.id());
        Graph g = FamilyPeople.view(store);
        assertEquals(2, counts(FamilyKin.sexes(g).get(g.nodeIdOf("Tom Hale"))).size());
    }

    private static Map<String, Integer> counts(Map<String, List<String>> bySex) {
        Map<String, Integer> out = new TreeMap<>();
        if (bySex != null) bySex.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }
}
