package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A name that came at a marriage into a family, where something points to more than the marriage: the family is asked how it came, with
 * the year shown, and the answers offered cover what else it may have been. The rule is the same for everyone: an adoption word, or words
 * for entering or marrying into the family, in the name's claims, the marriage's or the membership's, mean the library asks. Invented names only.
 */
class FamilyNameMarriedIntoTest {

    static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return FamilyNameQuestionsTest.fact(s, r, o, date, quote); }

    static FamilyAccount.NameRead name(String person, String name, String family, String given, String kind, String date, String quote) {
        return FamilyNameQuestionsTest.name(person, name, family, given, kind, date, quote);
    }

    static FamilyAccount.Outcome file(LibraryStore store, String locator, List<FamilyAccount.Fact> facts, List<FamilyAccount.NameRead> names, List<FamilyAccount.FamilyRead> families) throws Exception {
        return FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), facts, List.of(), List.of(), names, families), "an aunt", f -> List.of(locator), f -> List.of());
    }

    static FamilyNameHistory.Name nameOf(LibraryStore store, String person, String written) throws Exception {
        Graph g = FamilyPeople.view(store);
        return FamilyNameHistory.of(g).names(g.nodeIdOf(person)).stream().filter(n -> n.written().equals(written)).findFirst()
                .orElseThrow(() -> new AssertionError(person + " has no name " + written + ": " + FamilyNameHistory.of(g).names(g.nodeIdOf(person))));
    }

    static List<FamilyNameQuestions.Question> howAbout(LibraryStore store, String person) throws Exception {
        String id = FamilyPeople.view(store).nodeIdOf(person);
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("name-change-how") && q.people().contains(id)).toList();
    }

    static List<String> keys(FamilyNameQuestions.Question q) { return q.options().stream().map(FamilyNameQuestions.Option::key).toList(); }

    /** f-married-words-not-read, case 1: the marriage the name is dated from says 婿養子 in its own words. */
    @Test
    void aMarriageWhoseWordsSay婿養子IsAskedAboutWithTheYear(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String wed = "In 1932 Kenji married Haru (森田ハル) and entered the family as mukoyōshi (婿養子).";
        FamilyNameQuestionsTest.file(store, "file:///family/notes.txt", List.of(fact("森田健二", "married-to", "森田ハル", "1932", wed)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "Kenji was born in 1905 as 遠藤健二."),
                        name("森田健二", "森田健二", "森田", "健二", "", "", "He ran the shop as 森田健二.")));
        FamilyNameHistory.Name later = nameOf(store, "森田健二", "森田健二");
        assertFalse(later.cameWithTheMarriage(), "婿養子 points to more than the marriage: " + later);
        assertEquals("unknown", later.kind(), later.toString());
        assertEquals(1932, later.from().year(), "the year is still worked out from the marriage: " + later);
        List<FamilyNameQuestions.Question> how = howAbout(store, "森田健二");
        assertEquals(1, how.size(), FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList().toString());
        assertTrue(how.get(0).text().contains("from 1932") && keys(how.get(0)).contains("mukoyoshi"), how.get(0).text() + " " + keys(how.get(0)));
    }

    /** f-married-words-not-read, case 2: the name's own words say he married into the Morita family, and no membership was filed. */
    @Test
    void aNameWhoseOwnWordsSayMarriedIntoTheFamilyIsAskedAbout(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/notes.txt", List.of(fact("森田健二", "married-to", "森田ハル", "1932", "Kenji married Haru in 1932.")),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "Kenji was born in 1905 as 遠藤健二."),
                        name("森田健二", "森田健二", "森田", "健二", "", "", "In 1932 Kenji married Haru (森田ハル) and married into the Morita family, and was 森田健二 from then on.")));
        FamilyNameHistory.Name later = nameOf(store, "森田健二", "森田健二");
        assertFalse(later.cameWithTheMarriage(), "married into the family points to more than the marriage: " + later);
        assertEquals(1, howAbout(store, "森田健二").size(), FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList().toString());
        // the same words in Japanese
        LibraryStore ja = FamilyNameQuestionsTest.store(tmp.resolve("ja"));
        FamilyNameQuestionsTest.file(ja, "file:///family/notes.txt", List.of(fact("森田健二", "married-to", "森田ハル", "1932", "1932年、健二は森田家に入り、ハルと結婚した。")),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。"),
                        name("森田健二", "森田健二", "森田", "健二", "", "", "その後は森田健二と名乗った。")));
        assertFalse(nameOf(ja, "森田健二", "森田健二").cameWithTheMarriage(), nameOf(ja, "森田健二", "森田健二").toString());
        // a plain marriage still comes with the marriage, and nobody is asked
        LibraryStore plain = FamilyNameQuestionsTest.store(tmp.resolve("plain"));
        FamilyNameQuestionsTest.file(plain, "file:///family/letter.txt", List.of(fact("Mary Ellis", "married-to", "Tom Ellis", "1875", "She married Tom Ellis, a printer, in York in 1875.")),
                List.of(name("Mary Ellis", "Mary Hale", "Hale", "Mary", "birth", "1850", "Mary Ellis was born Mary Hale in York in 1850.")));
        assertTrue(nameOf(plain, "Mary Ellis", "Mary Ellis").cameWithTheMarriage(), nameOf(plain, "Mary Ellis", "Mary Ellis").toString());
        assertEquals(List.of(), howAbout(plain, "Mary Ellis"));
    }

    /** f-final-5: a sole name dated from a stated marriage into the family: how it came is asked, with the year. */
    @Test
    void aSoleNameDatedFromMarryingIntoAFamilyIsAskedHowItCame(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String q = "Mary Ellis, the daughter of John Hale, married into the Ellis family when she married Tom Ellis in 1875.";
        file(store, "file:///family/letter.txt", List.of(fact("Mary Ellis", "child-of", "John Hale", "", q), fact("Mary Ellis", "married-to", "Tom Ellis", "1875", q),
                        new FamilyAccount.Fact("Mary Ellis", FamilyHouses.MEMBER, "the Ellis family", "1875", q, Map.of("how", "marriage"))),
                List.of(name("John Hale", "John Hale", "Hale", "John", "birth", "", q)), List.of(new FamilyAccount.FamilyRead("Ellis", "the Ellis family", "", q)));
        FamilyNameHistory.Name only = nameOf(store, "Mary Ellis", "Mary Ellis");
        assertEquals(1875, only.from() == null ? 0 : only.from().year(), only.toString());
        List<FamilyNameQuestions.Question> how = howAbout(store, "Mary Ellis");
        assertEquals(1, how.size(), "a marriage into a family points to more than a marriage: " + FamilyNameQuestions.open(store));
        FamilyNameQuestions.Question h = how.get(0);
        assertTrue(h.text().contains("1875"), h.text());
        assertTrue(keys(h).containsAll(List.of("marriage", "adoptive")), keys(h).toString());
        // answering "at the marriage" settles it
        FamilyNameQuestions.answer(store, h.code(), "marriage", "", "Ann");
        assertEquals(List.of(), howAbout(store, "Mary Ellis"));
        assertEquals("marriage", nameOf(store, "Mary Ellis", "Mary Ellis").kind());

        LibraryStore ja = FamilyNameQuestionsTest.store(tmp.resolve("ja"));
        String j = "森田ハル、遠藤正一の娘。1930年に森田家に嫁いだ。";
        file(ja, "file:///family/koseki.txt", List.of(fact("森田ハル", "child-of", "遠藤正一", "", j),
                        new FamilyAccount.Fact("森田ハル", FamilyHouses.MEMBER, "森田家", "1930", j, Map.of("how", "marriage"))),
                List.of(name("遠藤正一", "遠藤正一", "遠藤", "正一", "birth", "", j)), List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", j)));
        List<FamilyNameQuestions.Question> haru = howAbout(ja, "森田ハル");
        assertEquals(1, haru.size(), FamilyNameQuestions.open(ja).toString());
        assertTrue(haru.get(0).text().contains("1930"), haru.get(0).text());
    }

    /** f-married-options-missing, C1: a husband asked about because he is his wife's father's heir is offered the heir and the succession. */
    @Test
    void anHeirOfTheWifesFatherIsOfferedTheHeirAndTheSuccession(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String q = "健二は勇の跡を継いだ。";
        FamilyNameQuestionsTest.file(store, "file:///family/notes.txt", List.of(fact("森田健二", "married-to", "森田ハル", "1932", "1932年、健二はハルと結婚した。"),
                        fact("森田健二", "heir-of", "森田勇", "", q), fact("森田ハル", "child-of", "森田勇", "", "ハルは勇の娘。"), fact("森田健二", "sex", "male", "", "健二は次男。")),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。"), name("森田健二", "森田健二", "森田", "健二", "", "", q),
                        name("森田勇", "森田勇", "森田", "勇", "birth", "", "森田勇")));
        List<FamilyNameQuestions.Question> how = howAbout(store, "森田健二");
        assertEquals(1, how.size(), FamilyNameQuestions.open(store).toString());
        assertTrue(keys(how.get(0)).containsAll(List.of("heir", "succession", "mukoyoshi", "marriage")), keys(how.get(0)).toString());
        assertTrue(keys(how.get(0)).size() <= 9, "eight answers and later at most: " + keys(how.get(0)));
    }

    /** f-married-options-missing, C2: a name the library worked out as the marriage's can be answered as a change under a will. */
    @Test
    void aNameWorkedOutAsTheMarriagesCanBeAnsweredAsALegalChange(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/hart.txt", List.of(fact("John Hale", "married-to", "Ann Ellis", "1880", "John Hale married Ann Ellis in 1880.")),
                List.of(name("John Hale", "John Hale", "Hale", "John", "birth", "1855", "John Hale was born in 1855."),
                        name("John Hale", "John Ellis", "Ellis", "John", "", "", "The Ellis estate passed to John Ellis, as he was later known.")));
        assertTrue(nameOf(store, "John Hale", "John Ellis").cameWithTheMarriage(), nameOf(store, "John Hale", "John Ellis").toString());
        String john = FamilyPeople.view(store).nodeIdOf("John Hale");
        FamilyNameQuestions.Question how = FamilyNameQuestions.byName(store, Set.of(john)).stream().filter(q -> q.kind().equals("name-change-how")).findFirst().orElseThrow();
        assertTrue(keys(how).containsAll(List.of("marriage", "legal")), keys(how).toString());
        assertTrue(keys(how).size() <= 9, keys(how).toString());
    }
}
