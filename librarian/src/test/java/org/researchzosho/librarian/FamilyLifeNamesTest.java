package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A life under a person's names: the heading is the latest name with the birth name beside it, a dated line says which name the person
 * carried then, a relative's line names the relative as they were named then, and the life ends with the names and the families, each
 * family with the command that opens it. Invented names only.
 */
class FamilyLifeNamesTest {

    @Test
    void aLifeIsHeadedByTheLatestNameAndAnEarlierLineSaysTheNameCarriedThen(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        assertEquals("森田健二 (born 遠藤)", FamilyLife.heading(store, "森田健二"));
        List<FamilyLife.Line> life = FamilyLife.of(store, "森田健二");
        String shown = FamilyLife.render("森田健二", FamilyLife.heading(store, "森田健二"), life);
        assertTrue(shown.startsWith("森田健二 (born 遠藤)\n"), shown);
        FamilyLife.Line school = life.stream().filter(l -> l.text().contains("village school")).findFirst().orElseThrow(() -> new AssertionError(shown));
        assertEquals("遠藤健二", school.as(), "the book writes Morita Kenji for 1920; he carried 遠藤健二 until 1932");
        assertTrue(shown.contains("1920  as 遠藤健二: went to the village school (1920)."), shown);
        assertTrue(shown.contains("1905  as 遠藤健二: was born in 1905."), shown);
        assertTrue(shown.contains("1932  was adopted by 森田勇 as 婿養子"), "a line under the latest name says nothing more: " + shown);
        assertTrue(shown.contains("1905  was named 遠藤健二 at birth (1905)."), "a name's own line names it already: " + shown);
        FamilyNameHistory.Index idx = FamilyNameHistory.of(FamilyPeople.view(store));
        String kenji = FamilyPeople.view(store).nodeIdOf("森田健二");
        assertEquals(1932, idx.latest(kenji).from().year(), "the narrative's Morita in 1920 did not date the change");
        assertTrue(FamilyLife.render("森田健二", life).startsWith("森田健二\n"), "a life rendered without a heading is headed by the name it is given");
    }

    @Test
    void theLifeEndsWithTheNamesAndTheFamiliesAndTheCommandsThatOpenEachFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        String end = FamilyLife.otherNames(store, "森田健二");
        assertTrue(end.startsWith("  Names:\n"), end);
        assertTrue(end.contains("    遠藤健二: the name at birth, from 1905 to 1932. From book.txt (a clue only). [F-"), end);
        assertTrue(end.contains("    森田健二: the name he took when he married into the 森田 family as 婿養子, in 1932. It came with the adoption. From book.txt (a clue only). [F-"), end);
        assertTrue(end.indexOf("遠藤健二: the name at birth") < end.indexOf("森田健二: the name he took"), "in the order of the life: " + end);
        assertTrue(end.contains("      also written もりた けんじ (from book.txt, a clue only)"), "each form with where it came from: " + end);
        assertTrue(end.contains("  Families:\n    森田 family: married into it as 婿養子 in 1932, coming from 遠藤 family (広島県安芸郡) (the library worked this out from a parent's family at the birth"), end);
        assertTrue(end.contains("      researchzosho genealogy family \"森田 family\" shows that family"), end);
        assertTrue(end.contains("      researchzosho genealogy family \"遠藤 family (広島県安芸郡)\" shows the family they came from."), end);
        assertEquals("", FamilyLife.otherNames(store, "森田ハル"), "one name, written one way, and no family: nothing to add to the life");

        String cli = FamilyNamesFilingTest.run(store, "life", "森田健二");
        assertTrue(cli.startsWith("森田健二 (born 遠藤)\n") && cli.contains("1920  as 遠藤健二: went to the village school") && cli.contains("  Families:"), cli);
        assertTrue(FamilyNamesFilingTest.run(store, "life", "髙橋正二").startsWith("髙橋正二 (born 森田)\n"), "any of a person's names finds their life");
    }

    @Test
    void aRelativeIsNamedInALifeByTheNameTheyCarriedThen(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        String father = FamilyLife.render("遠藤正一", FamilyLife.of(store, "遠藤正一"));
        assertTrue(father.contains("1905  The son 遠藤健二 was born in 1905."), "the birth is filed under 森田健二, the name he took in 1932: " + father);
        assertFalse(father.contains("The son 森田健二"), father);
        String wife = FamilyLife.render("森田ハル", FamilyLife.of(store, "森田ハル"));
        assertTrue(wife.contains("1932  森田健二 was married to 森田ハル"), "a line in the year he carried the name keeps it: " + wife);
    }

    @Test
    void twoLivesSideBySideAreHeadedByTheirHeadingsAndSayTheNameCarried(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        String both = FamilyNamesFilingTest.run(store, "life", "森田健二", "--with", "森田正二");
        assertTrue(both.contains("森田健二 (born 遠藤)") && both.contains("髙橋正二 (born 森田)"), both);
        assertTrue(both.contains("1905    as 遠藤健二: was born in 1905."), both);
        assertTrue(both.contains("as 森田正二: was born in 1912."), "正二 carried 森田正二 until 1940: " + both);
        String plain = FamilyLife.beside("A", FamilyLife.of(store, "森田健二"), "B", FamilyLife.of(store, "森田正二"));
        assertTrue(plain.contains("as 遠藤健二: went to the village school"), plain);
    }
}
