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
 * Two real people of one name written two ways: a grandfather 山田 太郎 (1850-1910) and his grandson 山田太郎 (1905-1980). Facts are about
 * both entries, so neither is an empty entry to be joined into the other: a name typed exactly as one of them is that person, `different`
 * writes the two down as two people, `related` finds the line between them, and a name that is both, typed neither way, is said to be
 * both and left for the person to pick.
 */
class FamilyNameTwoPeopleTest {

    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", "q"); }

    static LibraryStore library(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                fact("Taro Yamada", "born-on", "1850"), fact("Taro Yamada", "died-on", "1910"),
                fact("Ichiro Yamada", "child-of", "Taro Yamada"), fact("Ichiro Yamada", "born-on", "1878"),
                fact("Taro Yamada (born 1905)", "child-of", "Ichiro Yamada"), fact("Taro Yamada (born 1905)", "born-on", "1905"), fact("Taro Yamada (born 1905)", "died-on", "1980"),
                fact("Hanako Takada", "born-on", "1852"), fact("Hanako Takada (born 1910)", "born-on", "1910")), List.of()), "file:///family/notes.txt", "an aunt");
        Path nodes = Graph.nodesFile(store);
        List<String> kept = new ArrayList<>();
        for (String l : Files.readAllLines(nodes, StandardCharsets.UTF_8)) if (!l.startsWith("- taro yamada") && !l.startsWith("- hanako takada")) kept.add(l);
        kept.add("- 山田 太郎 — person: 山田 太郎 | also: Taro Yamada");
        kept.add("- 山田太郎 — person: 山田太郎 | also: Taro Yamada (born 1905)");
        kept.add("- 髙田 花子 — person: 髙田 花子 | also: Hanako Takada");
        kept.add("- 高田花子 — person: 高田花子 | also: Hanako Takada (born 1910)");
        Files.write(nodes, kept, StandardCharsets.UTF_8);
        return store;
    }

    @Test
    void aNameTypedAsAnEntryThatFactsAreAboutIsThatPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        FamilyQuestions.Found grandson = FamilyQuestions.find(store, "山田太郎"), grandfather = FamilyQuestions.find(store, "山田 太郎");
        assertEquals("山田太郎", grandson.person(), "the grandson is reachable: " + grandson);
        assertEquals("山田 太郎", grandfather.person(), grandfather.toString());
        assertTrue(grandson.note().contains("\"山田 太郎\", which 3 facts are about (born 1850, died 1910)") && grandson.note().contains("genealogy different \"山田太郎\" \"山田 太郎\""), grandson.note());
        assertFalse(grandson.note().contains("tidy"), "tidy never joins two entries that facts are about: " + grandson.note());
        assertEquals(List.of("山田 太郎"), grandson.also());
    }

    @Test
    void aNameThatIsTwoEntriesWithFactsIsLeftToThePerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        FamilyQuestions.Found f = FamilyQuestions.find(store, "高田 花子");
        assertFalse(f.found(), f.toString());
        assertTrue(f.ambiguous());
        assertEquals(2, f.could().size(), f.toString());
        assertTrue(f.note().contains("\"髙田 花子\", which 1 fact is about (born 1852)") && f.note().contains("\"高田花子\", which 1 fact is about (born 1910)") && f.note().contains("does not choose"), f.note());
        String[] research = FamilyNameSpacingTest.cli(store, "research", "高田 花子", "--list");
        assertEquals("1", research[1], research[0]);
        assertTrue(research[0].contains("not started") && research[0].contains("髙田 花子") && research[0].contains("高田花子"), research[0]);
        String[] life = FamilyNameSpacingTest.cli(store, "life", "高田 花子");
        assertEquals("1", life[1], life[0]);
        assertTrue(life[0].contains("does not choose"), life[0]);
        assertEquals("0", FamilyNameSpacingTest.cli(store, "life", "高田花子")[1], "typed as one of them: that one");
    }

    @Test
    void differentRelatedSplitAndLifeWithKeepTheTwoApart(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        String[] related = FamilyNameSpacingTest.cli(store, "related", "山田 太郎", "山田太郎");
        assertEquals("0", related[1], related[0]);
        assertFalse(related[0].contains("finds no line"), related[0]);
        String[] life = FamilyNameSpacingTest.cli(store, "life", "山田太郎");
        assertTrue(life[0].contains("was born in 1905") && !life[0].contains("was born in 1850"), "the grandson's life, not the grandfather's: " + life[0]);
        String[] beside = FamilyNameSpacingTest.cli(store, "life", "山田 太郎", "--with", "山田太郎");
        assertEquals("0", beside[1], beside[0]);
        assertTrue(beside[0].contains("1850") && beside[0].contains("1905") && !beside[0].contains("Your library also has"), "the two lives, and no word that the other is another entry: " + beside[0]);
        String[] different = FamilyNameSpacingTest.cli(store, "different", "山田 太郎", "山田太郎", "--because", "born 55 years apart");
        assertEquals("0", different[1], different[0]);
        assertTrue(different[0].contains("Written down: 山田 太郎 and 山田太郎 are two different people"), different[0]);
        assertEquals("", FamilyQuestions.find(store, "山田太郎").note(), "written down as two people: not said again");
        assertEquals("山田太郎", FamilyQuestions.find(store, "山田太郎").person());
        String died = store.scanFindings().findings().stream().filter(x -> x.triple() != null && x.triple().subject().equals("Taro Yamada (born 1905)") && x.triple().predicate().equals("died-on")).findFirst().orElseThrow().id();
        String[] split = FamilyNameSpacingTest.cli(store, "split", "山田太郎", "--as", "山田太郎 (died 1980)", "--claims", died);
        assertEquals("0", split[1], "the grandson's own claim moves from the grandson: " + split[0]);
    }

    @Test
    void anEntryWithNoFactsThatTheFamilySaidIsSomebodyElseLeadsNowhere(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameSpacingTest.library(tmp);
        String[] different = FamilyNameSpacingTest.cli(store, "different", "山田太郎", "山田 太郎", "--because", "the register names another man");
        assertEquals("0", different[1], "an entry of the list of names with no fact yet can be told apart too: " + different[0]);
        assertNotEquals("山田 太郎", FamilyQuestions.find(store, "山田太郎").person(), "written down as two people: the empty entry no longer leads to the other");
        assertFalse(FamilyChecks.forPerson(store, FamilyChecks.check(store)).contains("graph merge \"山田太郎\""), "and the check does not propose joining them");
    }
}
