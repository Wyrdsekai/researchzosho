package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules that let one person's sources meet, and where they stop: a read's romanised name meets its name in characters only through the
 * reading the same read gives, and never joins two entries the library holds apart; a tree file's record lands on the person another of
 * its names names, unless the birth years set them apart; a claim that says only how somebody left a family is dated by the leaving.
 * Invented names only.
 */
class FamilyAcrossSourcesTest {

    static FamilyAccount.Fact fact(String s, String r, String o, String d, String q) { return new FamilyAccount.Fact(s, r, o, d, q); }

    @Test
    void aRomanisedNameIsTheNameInCharactersWhereTheTextWritesThemTogetherButTwoEntriesTheLibraryHoldsStayTwo(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        List<FamilyAccount.Person> people = List.of(
                new FamilyAccount.Person("Morita Kenji", "", List.of(), "Morita", "Kenji"),
                new FamilyAccount.Person("森田健二", "もりた けんじ", List.of(), "森田", "健二"),
                new FamilyAccount.Person("Morita Isamu", "", List.of(), "Morita", "Isamu"));
        // the romanised name spells the reading the same read gives, and nothing more ties them: two entries, and the family is asked
        FamilyAccount.Read spelled = new FamilyAccount.Read(people, List.of(fact("Morita Kenji", "married-to", "Morita Haru", "1932", "Morita Kenji married Haru in 1932."),
                fact("森田健二", "occupation", "shopkeeper", "", "森田健二（もりた けんじ）ran the shop.")), List.of());
        assertEquals(3, FamilyAccount.oneNameEach(FamilyPeople.view(store), spelled).people().size(), "a spelling of a reading joins nobody by itself");
        // the text writes the two together, one in the brackets after the other
        FamilyAccount.Read read = new FamilyAccount.Read(people, List.of(fact("Morita Kenji", "married-to", "Morita Haru", "1932", "Morita Kenji (森田健二) married Haru in 1932."),
                fact("森田健二", "occupation", "shopkeeper", "", "森田健二（もりた けんじ）ran the shop.")), List.of());
        FamilyAccount.Read one = FamilyAccount.oneNameEach(FamilyPeople.view(store), read);
        FamilyAccount.Person kenji = one.people().stream().filter(p -> p.name().equals("森田健二")).findFirst().orElseThrow();
        assertTrue(kenji.also().contains("Morita Kenji"), "the romanised name is another name of the name in characters: " + one.people());
        assertEquals("森田", kenji.family(), "the parts are those of the name in characters, not of its romanised spelling");
        assertTrue(one.facts().stream().allMatch(f -> !f.subject().equals("Morita Kenji")), "every fact is filed under 森田健二: " + one.facts());
        assertTrue(one.people().stream().anyMatch(p -> p.name().equals("Morita Isamu")), "a romanised name no reading of this read spells stays as it is");

        // no reading given: the scripts never meet
        FamilyAccount.Read unread = new FamilyAccount.Read(List.of(new FamilyAccount.Person("Morita Kenji", "", List.of()), new FamilyAccount.Person("森田健二", "", List.of())),
                List.of(fact("Morita Kenji", "occupation", "shopkeeper", "", "Morita Kenji ran the shop.")), List.of());
        assertEquals(2, FamilyAccount.oneNameEach(FamilyPeople.view(store), unread).people().size(), "without a reading from the source, nobody guesses how 森田健二 is read");

        // two entries the library already holds: a read never joins them; that is a question for the family
        FamilyNameHistoryTest.file(store, "file:///family/a.txt", List.of(fact("Morita Kenji", "born-in", "広島", "1905", "Morita Kenji was born in 広島 in 1905.")), List.of());
        FamilyNameHistoryTest.file(store, "file:///family/b.txt", List.of(fact("森田健二", "born-in", "広島", "1905", "森田健二 1905年 広島生")), List.of());
        FamilyAccount.Read later = FamilyAccount.oneNameEach(FamilyPeople.view(store), read);
        assertTrue(later.people().stream().anyMatch(p -> p.name().equals("Morita Kenji")) && later.people().stream().anyMatch(p -> p.name().equals("森田健二")), "two entries stay two: " + later.people());
    }

    static final String TREE = """
            0 HEAD
            1 CHAR UTF-8
            0 @I1@ INDI
            1 NAME 健二 /遠藤/
            2 TYPE birth
            1 NAME 健二 /森田/
            2 _NAMEKIND mukoyoshi
            2 _NAMEDATE FROM 1932
            1 BIRT
            2 DATE 1905
            0 @I2@ INDI
            1 NAME 勇 /森田/
            2 ROMN Isamu /Morita/
            1 BIRT
            2 DATE 1870
            0 TRLR
            """;

    @Test
    void aTreeRecordLandsOnThePersonAnotherOfItsNamesNamesUnlessTheBirthYearsSetThemApart(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        // the book gives his birth year too: a second fact the tree agrees on, without which a shared name alone joins nobody
        FamilyNameHistoryTest.file(store, "file:///family/book.txt", List.of(fact("森田健二", "occupation", "shopkeeper", "", "森田健二 ran the shop."),
                fact("森田健二", "born-in", "広島", "1905", "森田健二 was born in 広島 in 1905."),
                fact("Morita Isamu", "born-in", "広島", "1840", "Morita Isamu was born in 広島 in 1840.")), List.of());
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, TREE, StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, ged);
        Graph g = FamilyPeople.view(store);
        String kenji = g.nodeIdOf("森田健二");
        assertEquals(kenji, g.nodeIdOf("遠藤健二"), "the record's first name is his birth name, and its second is the 森田健二 the book gave: one person");
        assertTrue(o.apart().stream().anyMatch(l -> l.startsWith("遠藤健二 in the file is filed under 森田健二, who is already in your library, because the file gives 遠藤健二 that name too. The birth years agree")), o.apart().toString());
        assertNotEquals(g.nodeIdOf("Morita Isamu"), g.nodeIdOf("森田勇"), "born 1870 in the file and 1840 in the book: the romanised name alone does not make them one");
        assertTrue(FamilyNameHistory.of(g).names(kenji).stream().anyMatch(n -> n.written().equals("遠藤健二") && n.kind().equals("birth")), "his birth name is a name of his");

        // the same file again lands on the same people
        Gedcom.importFile(store, ged);
        Graph again = FamilyPeople.view(store);
        assertEquals(again.nodeIdOf("森田健二"), again.nodeIdOf("遠藤健二"));
        assertEquals(2, store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("has-name") && f.triple().subject().equals("森田健二") && f.state() != Finding.State.retired).count(), "and files nothing twice");
        // the romanised form the tree gives 森田勇 is the name of the book's Morita Isamu, born 1840: it stays on his name, not on the book's man
        assertTrue(FamilyDetail.get(store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("has-name") && f.triple().subject().equals("森田勇")).findFirst().orElseThrow(), "forms").contains("Isamu Morita"));
    }

    @Test
    void aClaimThatSaysOnlyHowSomebodyLeftAFamilyIsDatedByTheLeaving(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q = "Isamu's son Morita Shōji (森田正二) left the Morita family in 1940, when he was adopted as heir into the Takahashi family (髙橋家).";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        new FamilyAccount.Fact("森田正二", "member-of", "the Morita family", "1940", q, Map.of("left", "adoption-out")),
                        new FamilyAccount.Fact("森田正二", "member-of", "the Takahashi family (髙橋家)", "1940", q, Map.of("how", "adoption", "role", "heir"))), List.of(), List.of(), List.of(),
                        List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q), new FamilyAccount.FamilyRead("髙橋", "the Takahashi family (髙橋家)", "", q))),
                "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        Graph g = FamilyPeople.view(store);
        List<FamilyHouses.Membership> ms = FamilyHouses.families(g, g.nodeIdOf("森田正二"));
        assertEquals(List.of("森田 family", "髙橋 family"), ms.stream().map(m -> FamilyHouses.labelOf(g, m.family())).toList(), "the family he left comes before the family he entered: " + ms);
        assertNull(ms.get(0).from(), "nothing says when he came into the 森田 family");
        assertEquals(1940, ms.get(0).to().year(), "1940 is when he left it");
        assertEquals(g.nodeIdOf("髙橋 family"), ms.get(0).wentTo());
        assertEquals(g.nodeIdOf("森田 family"), ms.get(1).cameFrom());
    }

    @Test
    void theReadingsOfTwoNamesOfOneLifeAreNoNameReadTwoWays(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyNameHistoryTest.file(store, "file:///family/tree.ged", List.of(fact("森田健二", "born-in", "広島", "1905", "森田健二 1905年 広島生")),
                List.of(FamilyNameHistoryTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "遠藤健二（えんどう けんじ）1905年生", "えんどう けんじ"),
                        FamilyNameHistoryTest.name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", "1932年 婿養子 森田健二（もりた けんじ）", "もりた けんじ")));
        assertTrue(FamilyChecks.check(store).stream().noneMatch(p -> p.kind().equals("read-two-ways")), FamilyChecks.render(FamilyChecks.check(store)));
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.kind().equals("reading")), "えんどう けんじ is how 遠藤健二 is read, and もりた けんじ how 森田健二 is");

        // one name with two readings is still one name read two ways, said of that name, and its answer is filed on that name
        FamilyNameHistoryTest.file(store, "file:///family/letter.txt", List.of(),
                List.of(FamilyNameHistoryTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "遠藤健二（えんど けんじ）", "えんど けんじ")));
        List<FamilyChecks.Problem> two = FamilyChecks.check(store).stream().filter(p -> p.kind().equals("read-two-ways")).toList();
        assertEquals(1, two.size(), FamilyChecks.render(FamilyChecks.check(store)));
        assertTrue(two.get(0).text().startsWith("The name 遠藤健二 of 森田健二 is read 2 ways in your sources: えんどう けんじ, えんど けんじ"), two.get(0).text());
        FamilyNameQuestions.Question q = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("reading")).findFirst().orElseThrow();
        assertTrue(q.text().startsWith("The name 遠藤健二 of 森田健二 (born 遠藤) is read 2 ways"), q.text());
        FamilyNameQuestions.answer(store, q.code(), "r1", "", "Ann");
        Finding word = store.scanFindings().findings().stream().filter(f -> f.sources().get(0).locator().startsWith(FamilyNameQuestions.SOURCE)).findFirst().orElseThrow();
        assertEquals("name: 遠藤健二", word.triple().object(), "the reading is filed on the name it is a reading of, not on the latest name");
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(x -> x.kind().equals("reading")), "and the question is answered");
    }
}
