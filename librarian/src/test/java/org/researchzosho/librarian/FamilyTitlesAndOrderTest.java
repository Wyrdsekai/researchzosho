package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A title is not part of a name, and neither is the comma of an index: "Mr. Hart", "Sir Tom", "Hart, Tom", 遠藤さん and 子爵 遠藤健二 are ways of
 * writing a name, never names of their own and never a reason to ask how a name came. A family name alone finds nobody: an index form kept
 * with its comma in the list of names came back as two names, and the family name alone then led every source's "Hart" to one person. A
 * Japanese name in Latin letters is written in either order, so a word alone of one is offered as that person, never taken for a family.
 * Who is who is asked first, and a question another would settle comes after it. A full name heads a person over a one-word name.
 */
class FamilyTitlesAndOrderTest {

    @AfterEach void restore() {
        GenealogyProfile.useReader(null);
        Interaction.OVERRIDE = null;
        Interaction.INPUT = null;
        Config.invalidate();
    }

    private static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return new FamilyAccount.Fact(s, r, o, date, quote); }

    private static FamilyAccount.NameRead name(String person, String name, String family, String quote) {
        return new FamilyAccount.NameRead(person, name, family, "", List.of(), "", "", "", quote);
    }

    private static void file(LibraryStore store, String locator, List<FamilyAccount.Fact> facts, List<FamilyAccount.NameRead> names) throws Exception {
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), facts, List.of(), List.of(), names, List.of()), "an aunt", f -> List.of(locator), f -> List.of());
    }

    private static List<FamilyNameQuestions.Question> about(LibraryStore store, String kind, String person) throws Exception {
        String id = FamilyPeople.view(store).nodeIdOf(person);
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals(kind) && q.people().contains(id)).toList();
    }

    private static String texts(List<FamilyNameQuestions.Question> qs) { return String.join("\n", qs.stream().map(FamilyNameQuestions.Question::text).toList()); }

    // ── (a) titles and the index form are forms of the name ──

    @Test
    void aTitleOrAnIndexFormIsAFormOfTheNameAndRaisesNoQuestionOfHowItCame(@TempDir Path tmp) throws Exception {
        // an English family: the knight's title with his given name, the lady's with hers, and a book's index
        LibraryStore en = FamilyNameHistoryTest.store(tmp.resolve("en"));
        String q1 = "Sir Tom, as the village called Tom Hart, was born in Bath in 1850.", q2 = "Lady Ann, Ann Hart, kept the house.", q3 = "Hart, Tom. See also Bath.";
        file(en, "file:///family/hart.txt", List.of(fact("Tom Hart", "born-in", "Bath", "1850", q1), fact("Tom Hart", "married-to", "Ann Hart", "", q2)),
                List.of(name("Tom Hart", "Sir Tom", "", q1), name("Tom Hart", "Hart, Tom", "", q3), name("Tom Hart", "Mr. Hart", "Hart", q3), name("Ann Hart", "Lady Ann", "", q2)));
        assertEquals(List.of(), about(en, "name-change-how", "Tom Hart"), "Sir Tom, Mr. Hart and Hart, Tom are ways of writing Tom Hart");
        assertEquals(List.of(), about(en, "name-change-how", "Ann Hart"), "Lady Ann is Ann Hart");
        // a Japanese family written family name first in Latin letters, with the title an English book gives
        LibraryStore ja = FamilyNameHistoryTest.store(tmp.resolve("ja"));
        String q4 = "Morita Shoichi came to Leeds in 1905.", q5 = "Mr. Morita opened a bakery in the city.";
        file(ja, "file:///family/london.txt", List.of(fact("Morita Shoichi", "migrated-to", "Leeds", "1905", q4)), List.of(name("Morita Shoichi", "Mr. Morita", "Morita", q5)));
        assertEquals(List.of(), about(ja, "name-change-how", "Morita Shoichi"), "Mr. Morita is Morita Shoichi's family name with a title: no name of its own");
        // in characters: an honorific after the name, a rank before it, the numeral of a hereditary name
        LibraryStore kanji = FamilyNameHistoryTest.store(tmp.resolve("kanji"));
        String q6 = "子爵 遠藤健二は1905年に小樽で生まれた。遠藤さんと呼ばれた。", q7 = "初代 森田勇は店を開いた。";
        file(kanji, "file:///family/記録.txt", List.of(fact("遠藤健二", "born-in", "小樽", "1905", q6), fact("森田勇", "occupation", "商人", "", q7)),
                List.of(name("遠藤健二", "子爵 遠藤健二", "", q6), name("遠藤健二", "遠藤さん", "遠藤", q6), name("森田勇", "初代 森田勇", "", q7)));
        assertEquals(List.of(), about(kanji, "name-change-how", "遠藤健二"), "子爵 遠藤健二 and 遠藤さん are ways of writing 遠藤健二");
        assertEquals(List.of(), about(kanji, "name-change-how", "森田勇"), "初代 森田勇 is 森田勇, the first of that name");
        // a real change of family name is still asked, a title on one of its forms or not
        LibraryStore real = FamilyNameHistoryTest.store(tmp.resolve("real"));
        String q8 = "Ruth Ellis was born in Bath in 1850.", q9 = "Mrs. Ruth Hale kept the bakery after 1880.";
        file(real, "file:///family/ruth.txt", List.of(fact("Ruth Ellis", "born-in", "Bath", "1850", q8)), List.of(name("Ruth Ellis", "Mrs. Ruth Hale", "", q9)));
        assertFalse(about(real, "name-change-how", "Ruth Ellis").isEmpty(), "Ruth Hale is another family name than Ellis: how it came is the family's to say");
    }

    @Test
    void theIndexFormIsKeptWithoutItsCommaSoTheFamilyNameAloneFindsNobody(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q1 = "Hart, Tom. A carpenter of Bath.", q2 = "Tom Hart was born in Bath in 1850.";
        file(store, "file:///family/index.txt", List.of(fact("Tom Hart", "born-in", "Bath", "1850", q2)), List.of(name("Tom Hart", "Hart, Tom", "", q1)));
        Graph g = FamilyPeople.view(store);
        Graph.Node tom = g.node(g.nodeIdOf("Tom Hart"));
        assertTrue(tom.aliases().stream().noneMatch(a -> a.contains(",")), "no other name with a comma, which the list of names would part: " + tom.aliases());
        assertTrue(tom.aliases().contains("Hart Tom"), "the index form, kept as one name: " + tom.aliases());
        // another source writes another man by the family name alone: he is not Tom Hart
        String q3 = "Mr. Hart, the town's baker, opened a bakery in 1890.";
        file(store, "file:///family/bakery.txt", List.of(fact("Hart", "occupation", "baker", "1890", q3)), List.of(name("Hart", "Viscount Hart", "Hart", q3)));
        Graph after = FamilyPeople.view(store);
        String tomId = after.nodeIdOf("Tom Hart");
        assertNotEquals(tomId, after.nodeIdOf("Hart"), "the family name alone leads to nobody else's entry");
        assertFalse(after.node(tomId).aliases().contains("Viscount Hart"), "nor is a title with the family name written to anybody: " + after.node(tomId).aliases());
        assertTrue(after.edges().stream().noneMatch(e -> e.from().equals(tomId) && e.predicate().equals("occupation")), "the baker's work is not Tom Hart's");
    }

    @Test
    void aTitleWithOneWordIsNeverKeptAsAnOtherNameNorWrittenToAPersonFoundByOneWord(@TempDir Path tmp) throws Exception {
        assertFalse(FamilyNames.keepAsOtherName("Tom Hart", "Mr. Hart"), "Mr. Hart is the family name alone");
        assertFalse(FamilyNames.keepAsOtherName("Tom Hart", "Viscount Hart"));
        assertFalse(FamilyNames.keepAsOtherName("遠藤健二", "遠藤さん"), "遠藤さん is 遠藤, a part of his own name");
        assertTrue(FamilyNames.keepAsOtherName("Tom Hart", "Sir John Hart"), "a title with a whole name is kept");
        // an older library kept the family name alone among a person's other names: a title with that name is written to nobody
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        file(store, "file:///family/a.txt", List.of(fact("Tom Hart", "born-in", "Bath", "1850", "Tom Hart was born in Bath in 1850.")), List.of());
        Graph.alias(store, "Tom Hart", List.of("Hart"));
        String q = "Viscount Hart built a bakery in Leeds.";
        file(store, "file:///family/b.txt", List.of(fact("Hart", "life-event", "built a bakery", "", q)), List.of(name("Hart", "Viscount Hart", "Hart", q)));
        Graph g = FamilyPeople.view(store);
        assertFalse(g.node(g.nodeIdOf("Tom Hart")).aliases().contains("Viscount Hart"), "the lookup of Hart found Tom Hart, and nothing more was written to him: " + g.node(g.nodeIdOf("Tom Hart")).aliases());
        // and genealogy tidy offers the family name alone among the other names that strangers share
        assertFalse(FamilyNames.keepAsOtherName("Tom Hart", "Hart"));
    }

    // ── (b) a family name alone, with a title, is asked about as one ──

    @Test
    void anEntryWrittenAsATitleWithAFamilyNameIsAskedAboutAsAFamilyNameAlone(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        file(store, "file:///family/a.txt", List.of(fact("Tom Hart", "born-in", "Bath", "1850", "Tom Hart was born in Bath in 1850.")), List.of(name("Tom Hart", "Tom Hart", "Hart", "Tom Hart was born in Bath in 1850.")));
        file(store, "file:///family/b.txt", List.of(fact("Mr. Hart", "occupation", "baker", "1890", "Mr. Hart's bakery opened in 1890.")), List.of());
        List<FamilyNameQuestions.Question> qs = about(store, "family-name-alone", "Mr. Hart");
        assertEquals(1, qs.size(), "who Mr. Hart is, is the family's to say: " + texts(FamilyNameQuestions.open(store)));
        assertTrue(qs.get(0).text().contains("“Hart” is written in your library as a person with no given name"), qs.get(0).text());
    }

    // ── (c) a Japanese name in Latin letters is written in either order ──

    @Test
    void aWordAloneOfARomanisedJapaneseNameIsOfferedAsThatPersonNeverAsAFamily(@TempDir Path tmp) throws Exception {
        // family name first: Morita Shoichi, and a book that writes Shoichi alone, possessive
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        file(store, "file:///family/a.txt", List.of(fact("Morita Shoichi", "born-in", "Nara", "", "Morita Shoichi was born in Nara.")), List.of());
        String q = "Shoichi's bakery was the first in the street.";
        file(store, "file:///family/b.txt", List.of(fact("Shoichi", "life-event", "opened a bakery", "", q)), List.of());
        assertEquals(List.of(), about(store, "family-name-alone", "Shoichi"), "nothing says Shoichi is a family name: " + texts(FamilyNameQuestions.open(store)));
        List<FamilyNameQuestions.Question> one = about(store, "one-person", "Shoichi");
        assertEquals(1, one.size(), texts(FamilyNameQuestions.open(store)));
        String id = FamilyPeople.view(store).nodeIdOf("Morita Shoichi");
        assertTrue(one.get(0).people().contains(id), "offered as Morita Shoichi: " + one.get(0).text());
        assertTrue(one.get(0).text().contains("Morita Shoichi is the only person in your library whose name has the word Shoichi"), one.get(0).text());
        assertEquals("one", one.get(0).options().get(0).key(), "that person first");
        // given name first: Isamu Takahashi, and a book that writes Takahashi alone. Neither word is asserted: the one person is offered
        LibraryStore given = FamilyNameHistoryTest.store(tmp.resolve("given"));
        file(given, "file:///family/a.txt", List.of(fact("Isamu Takahashi", "born-in", "Nara", "", "Isamu Takahashi was born in Nara.")), List.of());
        file(given, "file:///family/b.txt", List.of(fact("Takahashi", "life-event", "worked as a carpenter", "", "Takahashi worked as a carpenter in Leeds.")), List.of());
        List<FamilyNameQuestions.Question> t = about(given, "one-person", "Takahashi");
        assertEquals(1, t.size(), texts(FamilyNameQuestions.open(given)));
        assertFalse(t.get(0).text().contains("given name alone"), "the order is not known, so Takahashi is not called a given name: " + t.get(0).text());
        // where a claim gives the parts, the order is known and said
        LibraryStore known = FamilyNameHistoryTest.store(tmp.resolve("known"));
        file(known, "file:///family/a.txt", List.of(fact("Morita Shoichi", "born-in", "Nara", "", "Morita Shoichi was born in Nara.")),
                List.of(new FamilyAccount.NameRead("Morita Shoichi", "Morita Shoichi", "Morita", "Shoichi", List.of(), "birth", "", "", "Morita Shoichi was born in Nara.")));
        file(known, "file:///family/b.txt", List.of(fact("Shoichi", "life-event", "opened a bakery", "", q)), List.of());
        List<FamilyNameQuestions.Question> k = about(known, "one-person", "Shoichi");
        assertEquals(1, k.size(), texts(FamilyNameQuestions.open(known)));
        assertTrue(k.get(0).text().contains("is written with a given name alone"), k.get(0).text());
        // the source's own words come with it, so the family judges whom the name alone means from what the source says of it
        assertTrue(k.get(0).text().contains("Where “Shoichi” is written: “" + q + "”"), k.get(0).text());
        assertTrue(t.get(0).text().contains("Where “Takahashi” is written: “Takahashi worked as a carpenter in Leeds.”"), t.get(0).text());
        // an English name and a name of two family names are read as before: the family name alone written as a family is asked about as one
        LibraryStore en = FamilyNameHistoryTest.store(tmp.resolve("en"));
        file(en, "file:///family/a.txt", List.of(fact("Tom Hart", "born-in", "Bath", "1850", "Tom Hart was born in Bath in 1850."),
                fact("Mary Hale Ellis", "born-in", "Leeds", "1852", "Mary Hale Ellis was born in Leeds in 1852.")), List.of());
        file(en, "file:///family/b.txt", List.of(fact("Hart", "occupation", "carpenter", "", "Hart's workshop lay north of the town.")), List.of());
        assertEquals(1, about(en, "family-name-alone", "Hart").size(), texts(FamilyNameQuestions.open(en)));
        assertFalse(FamilyForms.romajiName("Mary Hale Ellis"));
        assertFalse(FamilyForms.romajiName("Tom Hart"));
        assertTrue(FamilyForms.romajiName("Morita Shoichi") && FamilyForms.romajiName("Endō Ken'ichi") && FamilyForms.romajiName("Endoh Masaji"));
    }

    // ── (d) who is who first ──

    @Test
    void whetherTwoFathersAreOnePersonIsAskedBeforeWhichOfThemIsTheBirthFather(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyAskingTest.store(tmp);
        String a = "森田勇の長女、花子。", b = "森田家の当主、森田勇。";
        file(store, "file:///family/a.txt", List.of(fact("花子", "child-of", "森田勇", "", a), fact("森田勇", "member-of", "森田家", "", b), fact("森田勇", "sex", "male", "", b)), List.of());
        file(store, "file:///family/b.txt", List.of(fact("勇", "sex", "male", "", "勇は男であった。"), fact("勇", "born-in", "奈良", "", "勇は奈良で生まれた。")), List.of());
        assertEquals(1, about(store, "one-person", "勇").size(), "whether 勇 is 森田勇 waits before the read: " + texts(FamilyNameQuestions.open(store)));
        // the read gives 花子 a second father, 勇: whether 勇 is 森田勇 settles which is her birth father, and is put first
        GenealogyProfile.useReader(prompt -> "{\"people\": [], \"facts\": [{\"subject\": \"花子\", \"relation\": \"child-of\", \"object\": \"勇\", \"date\": \"\", \"quote\": \"勇の娘、花子。\"}]}");
        FamilyAskingTest.seconds(tmp, 5);
        Interaction.OVERRIDE = true;
        Interaction.INPUT = new BufferedReader(new StringReader("1\n"));
        Path book = tmp.resolve("book.txt");
        Files.writeString(book, "勇の娘、花子。\n", StandardCharsets.UTF_8);
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().cli(store, new String[]{"researchzosho", "genealogy", "read", book.toString(), "--by", "an aunt"}); } finally { System.setOut(was); }
        String said = out.toString(StandardCharsets.UTF_8);
        int one = said.indexOf("ONE PERSON OR TWO?"), which = said.indexOf("WHICH KIND OF PARENT?");
        assertTrue(one >= 0, "whether 勇 is 森田勇 is put: " + said);
        assertTrue(which < 0 || one < which, "and before which of them is 花子's birth father: " + said);
        Graph g = FamilyPeople.view(store);
        assertEquals(g.nodeIdOf("森田勇"), g.nodeIdOf("勇"), "the answer joined them");
        assertEquals(List.of(), about(store, "birth-or-adoptive", "花子"), "one father: nothing left to ask about him");
        // the order a sitting asks them in: the question that settles another first
        FamilyNameQuestions.Question first = new FamilyNameQuestions.Question("aaaaaa", "one-person", List.of("x", "y"), "", List.of(), List.of());
        FamilyNameQuestions.Question then = new FamilyNameQuestions.Question("bbbbbb", "birth-or-adoptive", List.of("c", "x"), "", List.of(), List.of(), List.of("y"));
        assertTrue(FamilyNameQuestions.settles(first, then));
        assertEquals(List.of(first, then), FamilyNameQuestions.ordered(List.of(then, first)));
        assertFalse(FamilyNameQuestions.settles(then, first), "only who is who settles another question");
    }

    // ── (e) a full name heads a person ──

    @Test
    void aFullNameHeadsAPersonFiledUnderAOneWordName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q2 = "Ann was born Mary Hale in Bath.";
        file(store, "file:///family/village.txt", List.of(fact("Ann", "born-in", "Bath", "", q2)), List.of(name("Ann", "Mary Hale", "", q2)));
        Graph g = FamilyPeople.view(store);
        String id = g.nodeIdOf("Ann");
        assertEquals("Mary Hale (Ann)", FamilyNameHistory.of(g).heading(id), "the full name heads her, and the entry's own name follows it");
        LibraryStore full = FamilyNameHistoryTest.store(tmp.resolve("full"));
        file(full, "file:///family/a.txt", List.of(fact("Mary Hale", "born-in", "Bath", "", "Mary Hale was born in Bath.")), List.of(name("Mary Hale", "Mary Hale", "Hale", "Mary Hale was born in Bath.")));
        Graph fg = FamilyPeople.view(full);
        assertEquals("Mary Hale", FamilyNameHistory.of(fg).heading(fg.nodeIdOf("Mary Hale")), "a full name heads as it did");
        // an entry of a family name alone is somebody of that family: an older library's full name among its other names does not head it
        LibraryStore alone = FamilyNameHistoryTest.store(tmp.resolve("alone"));
        file(alone, "file:///family/a.txt", List.of(fact("Ann Hart", "born-in", "Bath", "", "Ann Hart was born in Bath.")), List.of(name("Ann Hart", "Ann Hart", "Hart", "Ann Hart was born in Bath.")));
        file(alone, "file:///family/b.txt", List.of(fact("Hart", "occupation", "baker", "", "Hart's bakery stood by the bridge.")), List.of());
        Graph.alias(alone, "Hart", List.of("Tom Hart"));
        Graph ag = FamilyPeople.view(alone);
        assertEquals("Hart", FamilyNameHistory.of(ag).heading(ag.nodeIdOf("Hart")), "the family name alone heads its entry");
    }
}
