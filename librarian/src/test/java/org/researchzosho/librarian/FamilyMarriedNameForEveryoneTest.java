package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The married-name rule, the same for everyone (the owner, 2026-09-25: sex is not the switch, "those cases can happen to a woman too").
 * A later name of the family part the husband or wife carried at the marriage dates from the marriage. How it came is what a source's words
 * say. Otherwise, when anything points to more than a marriage (an adoption, an entry into that family, the spouse's parents recorded as the
 * person's own, being head or heir), how is asked, with the year shown. Otherwise it came with the marriage: shown as worked out, and one
 * answer changes it. Invented names only.
 */
class FamilyMarriedNameForEveryoneTest {

    @AfterEach void restore() { Interaction.OVERRIDE = null; Interaction.INPUT = null; }

    static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return FamilyNameQuestionsTest.fact(s, r, o, date, quote); }

    static FamilyAccount.NameRead name(String person, String name, String family, String given, String kind, String date, String quote) {
        return FamilyNameQuestionsTest.name(person, name, family, given, kind, date, quote);
    }

    static FamilyNameHistory.Name named(LibraryStore store, String person, String written) throws Exception {
        Graph g = FamilyPeople.view(store);
        return FamilyNameHistory.of(g).names(g.nodeIdOf(person)).stream().filter(n -> n.written().equals(written)).findFirst()
                .orElseThrow(() -> new AssertionError(person + " has no name " + written));
    }

    static List<FamilyNameQuestions.Question> asked(LibraryStore store, String person) throws Exception {
        String id = FamilyPeople.view(store).nodeIdOf(person);
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().startsWith("name-change") && q.people().contains(id)).toList();
    }

    static List<String> keys(FamilyNameQuestions.Question q) { return q.options().stream().map(FamilyNameQuestions.Option::key).toList(); }

    /** Mary, whose sex no source gives: born Hale, married Tom Ellis in 1875 and John Hart in 1890, and nothing points to an adoption. */
    static LibraryStore mary(Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String born = "Mary Hale was born in York in 1850.";
        String first = "In 1875 Mary married Tom Ellis; the letters of those years are signed Mary Ellis.";
        String second = "In 1890 Mary married John Hart, and was Mary Hart until 1920.";
        FamilyNameQuestionsTest.file(store, "file:///family/notes.txt", List.of(fact("Mary Hart", "married-to", "Tom Ellis", "1875", first), fact("Mary Hart", "married-to", "John Hart", "1890", second)),
                List.of(name("Mary Hart", "Mary Hale", "Hale", "Mary", "birth", "1850", born), name("Mary Hart", "Mary Ellis", "Ellis", "Mary", "unknown", "", first),
                        name("Mary Hart", "Mary Hart", "Hart", "Mary", "unknown", "", second)));
        return store;
    }

    @Test
    void aPersonOfNoFiledSexCarriesEachSpousesNameFromEachMarriageAndIsNotAsked(@TempDir Path tmp) throws Exception {
        LibraryStore store = mary(tmp);
        FamilyNameHistory.Name ellis = named(store, "Mary Hart", "Mary Ellis"), hart = named(store, "Mary Hart", "Mary Hart");
        assertEquals("marriage", ellis.kind(), "came with the marriage to Tom Ellis: " + ellis);
        assertEquals(1875, ellis.from().year(), ellis.toString());
        assertTrue(ellis.cameWithTheMarriage(), "shown as worked out: " + ellis);
        assertEquals("marriage", hart.kind(), hart.toString());
        assertEquals(1890, hart.from().year(), hart.toString());
        assertEquals(List.of(), asked(store, "Mary Hart").stream().map(FamilyNameQuestions.Question::text).toList(), "nothing points to more than the marriages");
        Graph g = FamilyPeople.view(store);
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        String id = g.nodeIdOf("Mary Hart");
        assertEquals("Mary Hale", idx.at(id, 1860).written());
        assertEquals("Mary Ellis", idx.at(id, 1880).written());
        assertEquals("Mary Hart", idx.at(id, 1895).written());
        assertEquals("Mary Hart (born Hale)", idx.heading(id));
    }

    @Test
    void aNameWorkedOutAsTheMarriagesIsChangedByOneAnswerWhereTheFamilyNamesThePerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = mary(tmp);
        ByteArrayOutputStream page = new ByteArrayOutputStream();
        FamilyNamePages.cliNames(store, "Mary Hart", new PrintStream(page, true, StandardCharsets.UTF_8));
        assertTrue(page.toString(StandardCharsets.UTF_8).contains("researchzosho genealogy who \"Mary Hart\" asks how this name came, if it came another way than with the marriage."), page.toString(StandardCharsets.UTF_8));

        // at a terminal: researchzosho genealogy who "Mary Hart", and one answer
        Interaction.OVERRIDE = true;
        Interaction.INPUT = new BufferedReader(new StringReader("5\n\nstop\n"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream was = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().cli(store, new String[]{"researchzosho", "genealogy", "who", "Mary Hart"}); } finally { System.setOut(was); }
        String said = out.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("had the name Mary Ellis from 1875") && said.contains("The library worked out that the name came with the marriage"), said);
        assertTrue(said.contains("1. At the marriage to Tom Ellis"), "the answer the library worked out is offered first: " + said);
        assertTrue(said.contains("Saved as your answer: Mary Hart was named Mary Ellis on adoption"), "the fifth answer, an adoption: " + said);
        FamilyNameHistory.Name ellis = named(store, "Mary Hart", "Mary Ellis");
        assertEquals("adoptive", ellis.kind(), "the family's answer changed it: " + ellis);
        assertTrue(ellis.accepted(), ellis.toString());
    }

    @Test
    void aManWhoTookHisWifesNameIsFiledAsTheWordsSayAndNotAsked(@TempDir Path tmp) throws Exception {
        String born = "John Hale was born in 1850.", married = "John Hale married Ann Ellis in 1880.", took = "In 1880 John Hale took his wife's name and was John Ellis from then on.";
        String json = """
                {"people": [{"name": "John Ellis", "family": "Ellis", "given": "John", "also": ["John Hale"]}, {"name": "Ann Ellis", "family": "Ellis", "given": "Ann"}],
                 "facts": [{"subject": "John Ellis", "relation": "married-to", "object": "Ann Ellis", "date": "1880", "quote": "%s"}],
                 "names": [{"person": "John Ellis", "name": "John Hale", "family": "Hale", "given": "John", "kind": "birth", "said": "born", "date": "1850", "quote": "%s"},
                           {"person": "John Ellis", "name": "John Ellis", "family": "Ellis", "given": "John", "kind": "marriage", "said": "took his wife's name", "date": "1880", "quote": "%s"}]}
                """.formatted(married, born, took);
        FamilyAccount.Read r = FamilyReaderNamesTest.read(born + " " + married + " " + took + "\n", json);
        FamilyAccount.NameRead later = r.names().stream().filter(n -> n.name().equals("John Ellis")).findFirst().orElseThrow(() -> new AssertionError(r.names() + " " + r.dropped()));
        assertEquals("marriage", later.kind(), "the words \"took his wife's name\" say how the name came: " + r.dropped());
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyAccount.file(store, r, "file:///family/notes.txt", "an aunt");
        FamilyNameQuestionsTest.file(store, "file:///family/register.txt", List.of(fact("John Ellis", "sex", "male", "", "John Ellis, male")), List.of());
        FamilyNameHistory.Name ellis = named(store, "John Ellis", "John Ellis");
        assertEquals("marriage", ellis.kind(), ellis.toString());
        assertEquals(1880, ellis.from().year(), ellis.toString());
        assertEquals(List.of(), asked(store, "John Ellis").stream().map(FamilyNameQuestions.Question::text).toList(), "filed as the words say; nothing points to more");
    }

    /** One who married into the 森田 family, as the words say without saying how, and the husband or wife of that family. */
    static LibraryStore marriedInto(Path tmp, String who, String whoSex, String born, String spouse, String spouseSex, String year) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String given = who.substring(2), q = "In " + year + " " + born + " married " + spouse + " and married into the Morita family (森田家).";
        FamilyNameHistoryTest.fileWith(store, "file:///family/book.txt", List.of(
                        fact(who, "married-to", spouse, year, q),
                        new FamilyAccount.Fact(who, "member-of", "森田家", year, q, Map.of("how", "marriage")),
                        fact(who, "sex", whoSex, "", q), fact(spouse, "sex", spouseSex, "", q)),
                List.of(name(who, born, "遠藤", given, "birth", "1905", born + " was born in 1905."), name(spouse, spouse, "森田", spouse.substring(2), "birth", "1908", spouse + " was born in 1908.")),
                List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", q)));
        return store;
    }

    @Test
    void aManAndAWomanWhoEachMarriedIntoTheMoritaFamilyAreBothAskedWithTheYearShown(@TempDir Path tmp) throws Exception {
        LibraryStore man = marriedInto(tmp.resolve("man"), "森田健二", "male", "遠藤健二", "森田ハル", "female", "1932");
        LibraryStore woman = marriedInto(tmp.resolve("woman"), "森田ハル", "female", "遠藤ハル", "森田勝", "male", "1930");
        for (Object[] c : new Object[][]{{man, "森田健二", 1932}, {woman, "森田ハル", 1930}}) {
            LibraryStore store = (LibraryStore) c[0];
            String who = (String) c[1];
            int year = (Integer) c[2];
            FamilyNameHistory.Name later = named(store, who, who);
            assertEquals("unknown", later.kind(), who + ": how is not worked out: " + later);
            assertEquals(year, later.from().year(), who + ": the year of the marriage is: " + later);
            List<FamilyNameQuestions.Question> how = asked(store, who).stream().filter(q -> q.kind().equals("name-change-how")).toList();
            assertEquals(1, how.size(), who + ": " + FamilyNameQuestions.open(store));
            assertTrue(how.get(0).text().contains("had the name " + who + " from " + year), how.get(0).text());
        }
        FamilyNameQuestions.Question his = asked(man, "森田健二").get(0), hers = asked(woman, "森田ハル").get(0);
        assertEquals(List.of("mukoyoshi", "nyufu", "adoptive", "marriage"), keys(his).subList(0, 4), "a husband: 婿養子, 入夫, an adoption, only the name at the marriage: " + keys(his));
        assertEquals(List.of("yojo", "adoptive", "marriage"), keys(hers).subList(0, 3), "a wife: 養女, an adoption, only the name at the marriage: " + keys(hers));
        assertFalse(keys(hers).contains("mukoyoshi") || keys(hers).contains("nyufu"), keys(hers).toString());
        // the woman's answer, 養女: the name came on the adoption, and she entered the family by it
        FamilyNameQuestions.answer(woman, hers.code(), "yojo", "", "Ann");
        assertEquals("adoptive", named(woman, "森田ハル", "森田ハル").kind());
        assertEquals("adoption", FamilyDetail.get(FamilyNameQuestionsTest.claim(woman, "森田ハル", "member-of"), "how"));
        assertEquals(List.of(), asked(woman, "森田ハル"), "answered");
    }

    @Test
    void aWomanAdoptedByHerHusbandsParentIsFiledAsAdoptive(@TempDir Path tmp) throws Exception {
        String q = "1930年、ハル（遠藤ハル）は森田勇の養女となり、勇の息子の勝と結婚した。";
        // the words say 養女: the name's kind is given
        LibraryStore said = FamilyNameQuestionsTest.store(tmp.resolve("said"));
        FamilyNameQuestionsTest.file(said, "file:///family/book.txt", List.of(
                        new FamilyAccount.Fact("森田ハル", "adopted-by", "森田勇", "1930", q, Map.of("kind", "ordinary")), fact("森田ハル", "married-to", "森田勝", "1930", q),
                        fact("森田勝", "child-of", "森田勇", "", q), fact("森田ハル", "sex", "female", "", q), fact("森田勝", "sex", "male", "", q)),
                List.of(name("森田ハル", "遠藤ハル", "遠藤", "ハル", "birth", "1910", "ハルは1910年に遠藤ハルとして生まれた。"), name("森田ハル", "森田ハル", "森田", "ハル", "adoptive", "1930", q),
                        name("森田勇", "森田勇", "森田", "勇", "birth", "", q)));
        assertEquals("adoptive", named(said, "森田ハル", "森田ハル").kind());
        assertEquals(1930, named(said, "森田ハル", "森田ハル").from().year());
        assertEquals(List.of(), asked(said, "森田ハル").stream().map(FamilyNameQuestions.Question::text).toList());
        // no kind with the name, the adoption by her husband's father alone: the adoption settles it, not the marriage
        LibraryStore link = FamilyNameQuestionsTest.store(tmp.resolve("link"));
        FamilyNameQuestionsTest.file(link, "file:///family/book.txt", List.of(
                        new FamilyAccount.Fact("森田ハル", "adopted-by", "森田勇", "1930", q, Map.of("kind", "ordinary")), fact("森田ハル", "married-to", "森田勝", "1930", q),
                        fact("森田勝", "child-of", "森田勇", "", q), fact("森田ハル", "sex", "female", "", q), fact("森田勝", "sex", "male", "", q)),
                List.of(name("森田ハル", "遠藤ハル", "遠藤", "ハル", "birth", "1910", "ハルは1910年に遠藤ハルとして生まれた。"), name("森田勇", "森田勇", "森田", "勇", "birth", "", q)));
        FamilyNameHistory.Name later = named(link, "森田ハル", "森田ハル");
        assertEquals("adoptive", later.kind(), "worked out from the adoption into the 森田 family: " + later);
        assertEquals(1930, later.from().year(), later.toString());
        assertEquals(List.of(), asked(link, "森田ハル").stream().map(FamilyNameQuestions.Question::text).toList());
    }

    @Test
    void aMukoyoshiTheWordsNameIsStillWorkedOutAsMukoyoshi(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String q = "In 1932 Kenji married Haru (森田ハル), the only daughter of Morita Isamu (森田勇), and entered the family as mukoyōshi (婿養子) of Isamu.";
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(
                        new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", q, Map.of("kind", "mukoyoshi")), fact("森田健二", "married-to", "森田ハル", "1932", q),
                        new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "mukoyoshi")), fact("森田ハル", "child-of", "森田勇", "", q),
                        fact("森田健二", "sex", "male", "", q)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "Kenji was born in 1905 as Endō Kenji (遠藤健二)."), name("森田勇", "森田勇", "森田", "勇", "birth", "", q)));
        FamilyNameHistory.Name later = named(store, "森田健二", "森田健二");
        assertEquals("mukoyoshi", later.kind(), later.toString());
        assertEquals(1932, later.from().year(), later.toString());
        assertEquals(List.of(), asked(store, "森田健二").stream().map(FamilyNameQuestions.Question::text).toList());
        assertEquals("森田健二 (born 遠藤)", FamilyNameHistory.of(FamilyPeople.view(store)).heading(FamilyPeople.view(store).nodeIdOf("森田健二")));
    }
}
