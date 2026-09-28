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
 * The rules by which a tree file's names meet the library: a record lands on a person another of its names names only when a second fact
 * agrees; an untyped first name before a typed later one is the birth name; a married name written as a family name alone keeps the given
 * name; an adoption with no family belongs to the one family that adopted; a spelling is a form, a nickname an alias; and the library's own
 * export read back files nothing twice and changes nothing it says. Invented names only.
 */
class GedcomNamesRulesTest {

    private static LibraryStore store(Path tmp, String name) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve(name)); store.init();
        return store;
    }

    private static Path ged(Path tmp, String name, String body) throws Exception {
        Path f = tmp.resolve(name);
        Files.writeString(f, "0 HEAD\n1 CHAR UTF-8\n" + body + "0 TRLR\n", StandardCharsets.UTF_8);
        return f;
    }

    private static List<Finding> claims(LibraryStore store, String subject, String predicate) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals(subject) && f.triple().predicate().equals(predicate)
                && f.state() != Finding.State.superseded).toList();
    }

    private static Finding name(LibraryStore store, String person, String name) {
        return claims(store, person, "has-name").stream().filter(f -> f.triple().object().equals("name: " + name)).findFirst().orElseThrow(() -> new AssertionError("no name " + name + " of " + person
                + ": " + claims(store, person, "has-name").stream().map(Finding::title).toList()));
    }

    static FamilyAccount.Fact fact(String s, String r, String o, String d, String q) { return new FamilyAccount.Fact(s, r, o, d, q); }

    /** Hart Ellis's wife, born Ruth Hale, who took the name Ruth Ellis on her marriage in 1884. */
    static final String WIFE = "0 @I10@ INDI\n1 NAME Ruth /Hale/\n1 NAME Ruth /Ellis/\n2 TYPE married\n1 SEX F\n1 BIRT\n2 DATE 1862\n1 FAMS @F10@\n"
            + "0 @I11@ INDI\n1 NAME Hart /Ellis/\n1 SEX M\n1 BIRT\n2 DATE 1858\n1 FAMS @F10@\n0 @F10@ FAM\n1 HUSB @I11@\n1 WIFE @I10@\n1 MARR\n2 DATE 1884\n";

    @Test
    void aRecordLandsOnAPersonByAnotherOfItsNamesOnlyWhenASecondFactAgrees(@TempDir Path tmp) throws Exception {
        // the library has Ruth Ellis, the mother of Hart Ellis, with no birth year; a second file has his wife, who took the same name
        LibraryStore mother = store(tmp, "mother");
        Gedcom.importFile(mother, ged(tmp, "ellis.ged", "0 @I1@ INDI\n1 NAME Hart /Ellis/\n1 SEX M\n1 BIRT\n2 DATE 1858\n1 FAMC @F1@\n0 @I2@ INDI\n1 NAME Ruth /Ellis/\n1 SEX F\n1 FAMS @F1@\n"
                + "0 @F1@ FAM\n1 WIFE @I2@\n1 CHIL @I1@\n"));
        Gedcom.Outcome o = Gedcom.importFile(mother, ged(tmp, "hale.ged", WIFE));
        Graph g = FamilyPeople.view(mother);
        assertNotEquals(g.nodeIdOf("Ruth Ellis"), g.nodeIdOf("Ruth Hale"), "his wife is not joined into his mother on the married name they share: " + o.apart());
        assertTrue(claims(mother, "Ruth Ellis", "born-on").isEmpty() && claims(mother, "Ruth Ellis", "married-to").isEmpty(), "the mother is given nothing of the wife's record");
        assertEquals("Ruth Hale", claims(mother, "Hart Ellis", "married-to").get(0).triple().object());
        assertTrue(o.apart().stream().anyMatch(l -> l.startsWith("Ruth Hale in the file also has the name Ruth Ellis, which is the name of another entry in your library.")), o.apart().toString());

        // a grandson named after his grandfather: a romanised form alone is a clue, and his father's birth year is the second fact for the father
        LibraryStore grand = store(tmp, "grand");
        Gedcom.importFile(grand, ged(tmp, "romaji.ged", "0 @I1@ INDI\n1 NAME Kenji /Morita/\n1 SEX M\n1 FAMS @F1@\n0 @I2@ INDI\n1 NAME Masaru /Morita/\n1 SEX M\n1 BIRT\n2 DATE 1935\n1 FAMC @F1@\n"
                + "0 @F1@ FAM\n1 HUSB @I1@\n1 CHIL @I2@\n"));
        Gedcom.importFile(grand, ged(tmp, "kanji.ged", "0 @I1@ INDI\n1 NAME 健二 /森田/\n2 ROMN Kenji /Morita/\n1 SEX M\n1 BIRT\n2 DATE 1960\n1 FAMC @F1@\n"
                + "0 @I2@ INDI\n1 NAME 勝 /森田/\n2 ROMN Masaru /Morita/\n1 SEX M\n1 BIRT\n2 DATE 1935\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I2@\n1 CHIL @I1@\n"));
        g = FamilyPeople.view(grand);
        assertNotEquals(g.nodeIdOf("Kenji Morita"), g.nodeIdOf("森田健二"), "the grandson is not his grandfather");
        assertEquals(g.nodeIdOf("Masaru Morita"), g.nodeIdOf("森田勝"), "the father, born 1935 in both files, is one man");
        assertTrue(FamilyChecks.check(grand).stream().noneMatch(p -> p.text().contains("own ancestor")), FamilyChecks.render(FamilyChecks.check(grand)));
        assertTrue(name(grand, "森田健二", "森田健二").body().contains("Kenji Morita") || FamilyDetail.get(name(grand, "森田健二", "森田健二"), "forms").contains("Kenji Morita"),
                "the file's romanised form of his name is kept on its name, for the question whether the two entries are one person");

        // a second fact that agrees: the same birth year, or the same husband, who is the same man by his birth year too
        LibraryStore born = store(tmp, "born");
        FamilyNameHistoryTest.file(born, "file:///family/letter.txt", List.of(fact("Ruth Ellis", "born-in", "York", "1862", "Ruth Ellis was born in York in 1862.")), List.of());
        Gedcom.importFile(born, ged(tmp, "hale2.ged", "0 @I1@ INDI\n1 NAME Ruth /Hale/\n1 NAME Ruth /Ellis/\n2 TYPE married\n1 BIRT\n2 DATE 1862\n"));
        g = FamilyPeople.view(born);
        assertEquals(g.nodeIdOf("Ruth Ellis"), g.nodeIdOf("Ruth Hale"), "born 1862 in both: the record is hers");
        LibraryStore wed = store(tmp, "wed");
        FamilyNameHistoryTest.file(wed, "file:///family/letter.txt", List.of(fact("Ruth Ellis", "married-to", "Hart Ellis", "1884", "Ruth Ellis married Hart Ellis in 1884."),
                fact("Hart Ellis", "born-in", "York", "1858", "Hart Ellis was born in York in 1858.")), List.of());
        o = Gedcom.importFile(wed, ged(tmp, "hale3.ged", WIFE));
        g = FamilyPeople.view(wed);
        assertEquals(g.nodeIdOf("Ruth Ellis"), g.nodeIdOf("Ruth Hale"), "married to Hart Ellis, born 1858, in both: the record is hers: " + o.apart());
        assertTrue(o.apart().stream().anyMatch(l -> l.contains("the same husband or wife, Hart Ellis.")), o.apart().toString());

        // nothing else agrees: an entry of its own, and the family is asked whether the two are one person
        LibraryStore ask = store(tmp, "ask");
        FamilyNameHistoryTest.file(ask, "file:///family/letter.txt", List.of(fact("Ruth Ellis", "occupation", "teacher", "", "Ruth Ellis was a teacher.")), List.of());
        Gedcom.importFile(ask, ged(tmp, "hale4.ged", "0 @I1@ INDI\n1 NAME Ruth /Hale/\n1 NAME Ruth /Ellis/\n2 TYPE married\n1 BIRT\n2 DATE 1862\n"));
        g = FamilyPeople.view(ask);
        String hale = g.nodeIdOf("Ruth Hale"), ellis = g.nodeIdOf("Ruth Ellis");
        assertNotEquals(ellis, hale);
        List<FamilyNameQuestions.Question> qs = FamilyNameQuestions.open(ask);
        assertTrue(qs.stream().anyMatch(q -> q.kind().equals("one-person") && q.people().containsAll(List.of(hale, ellis))), qs.toString());
    }

    /** Tom Ellis, born 1850, and his wife Ruth Ellis, married in 1875: the father's family as the library first has it. */
    static final String SENIOR = "0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 BIRT\n2 DATE 1850\n1 FAMS @F1@\n0 @I2@ INDI\n1 NAME Ruth /Ellis/\n1 SEX F\n1 FAMS @F1@\n"
            + "0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I2@\n1 MARR\n2 DATE 1875\n";

    /** A woman born Ruth Moore in 1882 who took the name Ruth Ellis, with the relative {@code kin} (a husband or a child) given as {@code as}. */
    static String moore(String as, String kin) {
        boolean husband = as.equals("HUSB");
        return "0 @I2@ INDI\n1 NAME Ruth /Moore/\n1 NAME Ruth /Ellis/\n2 TYPE married\n1 SEX F\n1 BIRT\n2 DATE 1882\n1 FAMS @F1@\n"
                + "0 @I1@ INDI\n" + kin + (husband ? "1 FAMS @F1@\n" : "1 FAMC @F1@\n")
                + "0 @F1@ FAM\n1 " + as + " @I1@\n1 WIFE @I2@\n" + (husband ? "1 MARR\n2 DATE 1905\n" : "");
    }

    @Test
    void aRelativeTheFileNamesLikeTheLibrarysIsASecondFactOnlyWhenItIsTheSameEntry(@TempDir Path tmp) throws Exception {
        // the son, born 1880 and named after his father, is filed apart; his wife, who carries his mother's married name, is not his mother
        LibraryStore junior = store(tmp, "junior");
        Gedcom.importFile(junior, ged(tmp, "sr.ged", SENIOR));
        Gedcom.Outcome o = Gedcom.importFile(junior, ged(tmp, "jr.ged", moore("HUSB", "1 NAME Tom /Ellis/\n1 SEX M\n1 BIRT\n2 DATE 1880\n")));
        Graph g = FamilyPeople.view(junior);
        String wife = g.nodeIdOf("Ruth Moore"), mother = g.nodeIdOf("Ruth Ellis");
        assertNotEquals(mother, wife, "the son's wife is not joined into his mother through a husband who is another man of his father's name: " + o.apart());
        assertTrue(claims(junior, "Ruth Ellis", "born-on").isEmpty(), "the mother is given nothing of the son's wife's record");
        assertEquals("Ruth Moore", claims(junior, "Tom Ellis (born 1880)", "married-to").get(0).triple().object());
        assertTrue(o.apart().stream().anyMatch(l -> l.startsWith("Ruth Moore in the file also has the name Ruth Ellis, which is the name of another entry in your library.")), o.apart().toString());
        List<FamilyNameQuestions.Question> qs = FamilyNameQuestions.open(junior);
        assertTrue(qs.stream().anyMatch(q -> q.kind().equals("one-person") && q.people().containsAll(List.of(wife, mother))), "the family is asked whether the two are one person: " + qs);

        // a son of the father's name: born in another year, or with no birth year, so only his name is the same
        for (String[] son : new String[][]{{"later", "1 NAME Tom /Ellis/\n1 SEX M\n1 BIRT\n2 DATE 1905\n"}, {"undated", "1 NAME Tom /Ellis/\n1 SEX M\n"}}) {
            LibraryStore store = store(tmp, "son-" + son[0]);
            Gedcom.importFile(store, ged(tmp, "mother-" + son[0] + ".ged", "0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 BIRT\n2 DATE 1850\n1 FAMC @F1@\n0 @I2@ INDI\n1 NAME Ruth /Ellis/\n1 SEX F\n1 FAMS @F1@\n"
                    + "0 @F1@ FAM\n1 WIFE @I2@\n1 CHIL @I1@\n"));
            o = Gedcom.importFile(store, ged(tmp, "moore-" + son[0] + ".ged", moore("CHIL", son[1])));
            g = FamilyPeople.view(store);
            List<String> pair = List.of(g.nodeIdOf("Ruth Moore"), g.nodeIdOf("Ruth Ellis"));
            assertNotEquals(pair.get(1), pair.get(0), son[0] + ": a child found by his name alone is no second fact: " + o.apart());
            assertTrue(claims(store, "Ruth Ellis", "born-on").isEmpty(), son[0]);
            assertTrue(FamilyNameQuestions.open(store).stream().anyMatch(q -> q.kind().equals("one-person") && q.people().containsAll(pair)), son[0] + ": " + FamilyNameQuestions.open(store));
        }

        // a husband found by his name alone, with no birth year to say he is the same man, is no second fact either: the family is asked
        LibraryStore letter = store(tmp, "letter");
        FamilyNameHistoryTest.file(letter, "file:///family/letter.txt", List.of(fact("Ruth Ellis", "married-to", "Hart Ellis", "1884", "Ruth Ellis married Hart Ellis in 1884.")), List.of());
        o = Gedcom.importFile(letter, ged(tmp, "hale5.ged", WIFE));
        g = FamilyPeople.view(letter);
        List<String> two = List.of(g.nodeIdOf("Ruth Hale"), g.nodeIdOf("Ruth Ellis"));
        assertNotEquals(two.get(1), two.get(0), "Hart Ellis found by his name alone: " + o.apart());
        qs = FamilyNameQuestions.open(letter);
        assertTrue(qs.stream().anyMatch(q -> q.kind().equals("one-person") && q.people().containsAll(two)), qs.toString());

        // the same husband by more than his name: the very record of the file that the library already holds under him (his birth year in
        // both is the other way, in the test above)
        LibraryStore again = store(tmp, "again");
        Path tree = ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME Hart /Ellis/\n1 SEX M\n1 FAMS @F1@\n0 @I2@ INDI\n1 NAME Ruth /Ellis/\n1 SEX F\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I2@\n");
        Gedcom.importFile(again, tree);
        Files.writeString(tree, "0 HEAD\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Hart /Ellis/\n1 SEX M\n1 FAMS @F1@\n0 @I2@ INDI\n1 NAME Ruth /Hale/\n1 NAME Ruth /Ellis/\n2 TYPE married\n1 SEX F\n1 FAMS @F1@\n"
                + "0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I2@\n0 TRLR\n", StandardCharsets.UTF_8);
        o = Gedcom.importFile(again, tree);
        g = FamilyPeople.view(again);
        assertEquals(g.nodeIdOf("Ruth Ellis"), g.nodeIdOf("Ruth Hale"), "the husband is the record the library already holds from this file: " + o.apart());
        assertTrue(o.apart().stream().anyMatch(l -> l.contains("the same husband or wife, Hart Ellis.")), o.apart().toString());
    }

    @Test
    void anUntypedFirstNameBeforeATypedLaterNameIsTheBirthName(@TempDir Path tmp) throws Exception {
        for (String[] style : new String[][]{
                {"typed", "1 NAME Mary /Hale/\n1 NAME Mary /Ellis/\n2 TYPE married\n"},
                {"marnm", "1 NAME Mary /Hale/\n2 _MARNM Ellis\n"},
                {"marnm-full", "1 NAME Mary /Hale/\n2 _MARNM Mary /Ellis/\n"},
                {"words", "1 NAME Mary /Hale/\n1 NAME Mary /Ellis/\n2 TYPE Married Name\n"}}) {
            LibraryStore store = store(tmp, style[0]);
            Gedcom.importFile(store, ged(tmp, style[0] + ".ged", "0 @I1@ INDI\n" + style[1] + "1 SEX F\n1 BIRT\n2 DATE 1860\n1 FAMS @F1@\n"
                    + "0 @I2@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I2@\n1 WIFE @I1@\n1 MARR\n2 DATE 1884\n"));
            Graph g = FamilyPeople.view(store);
            String mary = g.nodeIdOf("Mary Hale");
            FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
            assertEquals("Mary Ellis (born Hale)", idx.heading(mary), style[0] + ": " + idx.names(mary));
            assertEquals("Mary Hale", idx.names(mary).get(0).written(), style[0] + ": the birth name comes first in the order of the life");
            Finding birth = name(store, "Mary Hale", "Mary Hale");
            assertEquals("birth", FamilyDetail.get(birth, "kind"), style[0]);
            assertTrue(birth.sources().get(0).edition().contains("the name before the other name the file gives"), birth.sources().get(0).edition());
            List<FamilyNameQuestions.Question> qs = FamilyNameQuestions.open(store);
            assertTrue(qs.stream().noneMatch(q -> q.text().contains("get the name Mary Hale?")), style[0] + ": " + qs);
        }
        // a later name with no kind says nothing of the first one
        LibraryStore store = store(tmp, "untyped");
        Gedcom.importFile(store, ged(tmp, "untyped.ged", "0 @I1@ INDI\n1 NAME Tom /Hart/\n1 NAME John /Hart/\n1 BIRT\n2 DATE 1860\n"));
        assertTrue(claims(store, "Tom Hart", "has-name").stream().noneMatch(f -> f.triple().object().equals("name: Tom Hart")), "Tom is not made a birth name by a second name of no kind");
    }

    @Test
    void aMarriedNameWrittenAsAFamilyNameAloneKeepsTheGivenNameOfTheFirstName(@TempDir Path tmp) throws Exception {
        for (String[] style : new String[][]{
                {"webtrees", "1 NAME Ruth /Hale/\n2 TYPE BIRTH\n1 NAME /Ellis/\n2 TYPE MARRIED\n2 SURN Ellis\n"},
                {"marnm", "1 NAME Ruth /Hale/\n2 TYPE BIRTH\n2 _MARNM /Ellis/\n"}}) {
            LibraryStore store = store(tmp, style[0]);
            Gedcom.importFile(store, ged(tmp, style[0] + ".ged", "0 @I1@ INDI\n" + style[1] + "1 SEX F\n1 BIRT\n2 DATE 1862\n1 FAMS @F1@\n"
                    + "0 @I2@ INDI\n1 NAME Hart /Ellis/\n1 SEX M\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I2@\n1 WIFE @I1@\n1 MARR\n2 DATE 1884\n"));
            Finding married = name(store, "Ruth Hale", "Ruth Ellis");
            assertEquals("Ruth", FamilyDetail.get(married, "given"), style[0]);
            assertEquals("Ellis", FamilyDetail.get(married, "family"), style[0]);
            Graph g = FamilyPeople.view(store);
            assertEquals("Ruth Ellis (born Hale)", FamilyNameHistory.of(g).heading(g.nodeIdOf("Ruth Hale")), style[0]);
            String out = Gedcom.export(store, "Ruth Hale");
            assertTrue(out.contains("0 @I1@ INDI\n1 NAME Ruth /Ellis/\n"), style[0] + ": the name other programs show first is a whole name: " + out);
        }
    }

    @Test
    void anAdoptionWithNoFamilyBelongsToTheOneFamilyThatAdoptedThePerson(@TempDir Path tmp) throws Exception {
        String parents = "0 @I2@ INDI\n1 NAME 勇 /森田/\n1 SEX M\n1 FAMS @F2@\n0 @I3@ INDI\n1 NAME ハル /森田/\n1 SEX F\n1 FAMS @F2@\n0 @F2@ FAM\n1 HUSB @I2@\n1 WIFE @I3@\n1 CHIL @I1@\n";
        LibraryStore store = store(tmp, "one");
        Gedcom.Outcome o = Gedcom.importFile(store, ged(tmp, "one.ged", "0 @I1@ INDI\n1 NAME 健二 /遠藤/\n1 SEX M\n1 ADOP\n2 DATE 1932\n2 PLAC 岡山\n2 TYPE 婿養子\n1 FAMC @F2@\n2 PEDI adopted\n" + parents));
        List<Finding> adopted = claims(store, "遠藤健二", "adopted-by");
        assertEquals(2, adopted.size(), adopted.toString());
        for (Finding f : adopted) assertEquals(Map.of("kind", "mukoyoshi", "from", "1932", "said", "婿養子"), FamilyDetail.of(f), f.title() + " " + o.problems());

        // two families adopted him, and the adoption does not say which: the date and the kind are not given to either, and the import says so
        LibraryStore two = store(tmp, "two");
        Gedcom.Outcome t = Gedcom.importFile(two, ged(tmp, "two.ged", "0 @I1@ INDI\n1 NAME 健二 /遠藤/\n1 SEX M\n1 ADOP\n2 DATE 1932\n2 TYPE 婿養子\n1 FAMC @F2@\n2 PEDI adopted\n1 FAMC @F4@\n2 PEDI adopted\n"
                + parents + "0 @I4@ INDI\n1 NAME 正一 /髙橋/\n1 SEX M\n1 FAMS @F4@\n0 @F4@ FAM\n1 HUSB @I4@\n1 CHIL @I1@\n"));
        assertTrue(claims(two, "遠藤健二", "adopted-by").stream().allMatch(f -> FamilyDetail.of(f).isEmpty()), "neither adoption is given a date or a kind the file does not give it");
        assertTrue(t.problems().stream().anyMatch(p -> p.startsWith("The file records that 遠藤健二 was adopted (1932, 婿養子), but not by which of the 2 families that adopted 遠藤健二 in the file.")), t.problems().toString());
    }

    @Test
    void theLibrarysOwnExportReadBackIntoItFilesNothingTwice(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "lib");
        Gedcom.importFile(store, ged(tmp, "tree.ged", GedcomNamesTest.MUKOYOSHI));
        List<String> before = store.scanFindings().findings().stream().map(Finding::id).toList();
        Path back = tmp.resolve("out.ged");
        Files.writeString(back, Gedcom.export(store, "遠藤健二"), StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, back);
        List<String> added = store.scanFindings().findings().stream().filter(f -> !before.contains(f.id())).map(Finding::title).toList();
        assertEquals(List.of(), added, "every fact of the export is in the library already: " + o.problems());
        Graph g = FamilyPeople.view(store);
        assertEquals(2, FamilyHouses.named(g, "遠藤").size(), "the two 遠藤 families stay two: " + FamilyHouses.all(g));
    }

    /** Two families of one name with no seat, told apart by their first member; 正一 heads the first and 健二 was born into it. */
    static final String TWO = "0 @H1@ _HOUSE\n1 NAME 遠藤\n0 @H2@ _HOUSE\n1 NAME 遠藤\n"
            + "0 @I1@ INDI\n1 NAME 正一 /遠藤/\n1 SEX M\n1 BIRT\n2 DATE 1870\n1 FAMS @F1@\n1 _MEMBER @H1@\n2 _ROLE head\n"
            + "0 @I2@ INDI\n1 NAME 健二 /遠藤/\n1 SEX M\n1 BIRT\n2 DATE 1905\n1 FAMC @F1@\n1 _MEMBER @H1@\n2 _HOW birth\n"
            + "0 @I3@ INDI\n1 NAME 正二 /遠藤/\n1 SEX M\n1 BIRT\n2 DATE 1880\n1 _MEMBER @H2@\n2 _ROLE head\n"
            + "0 @F1@ FAM\n1 HUSB @I1@\n1 CHIL @I2@\n";

    @Test
    void aFamilyReadBackIsTheFamilyItsMembersAlreadyBelongTo(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "lib");
        Gedcom.importFile(store, ged(tmp, "two.ged", TWO));
        Graph g = FamilyPeople.view(store);
        List<String> families = FamilyHouses.named(g, "遠藤").stream().map(id -> FamilyHouses.labelOf(g, id)).sorted().toList();
        assertEquals(List.of("遠藤 family", "遠藤 family (of 遠藤正二)"), families);
        Path back = tmp.resolve("out.ged");
        Files.writeString(back, Gedcom.export(store, "遠藤正一"), StandardCharsets.UTF_8);
        Gedcom.importFile(store, back);
        Graph now = FamilyPeople.view(store);
        assertEquals(families, FamilyHouses.named(now, "遠藤").stream().map(id -> FamilyHouses.labelOf(now, id)).sorted().toList(), "no third 遠藤 family");
        assertEquals(List.of("遠藤 family"), FamilyHouses.families(now, now.nodeIdOf("遠藤正一")).stream().map(m -> FamilyHouses.labelOf(now, m.family())).toList());
    }

    @Test
    void aSpellingIsAFormOfTheNameItSpellsAndANicknameIsAnAlias(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "lib");
        Gedcom.importFile(store, ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME Mary /Hale/\n1 NAME Mary /Hail/\n2 TYPE Other Spelling\n1 BIRT\n2 DATE 1850\n"
                + "0 @I2@ INDI\n1 NAME John /Hart/\n1 NAME Tom /Hart/\n2 TYPE Nickname\n1 BIRT\n2 DATE 1860\n"
                + "0 @I3@ INDI\n1 NAME Ann /Ellis/\n2 TYPE birth\n1 NAME Ann /Hale/\n2 TYPE married\n1 NAME Ann /Hail/\n2 TYPE Variant Spelling\n1 BIRT\n2 DATE 1855\n"));
        Graph g = FamilyPeople.view(store);
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        String mary = g.nodeIdOf("Mary Hale");
        assertEquals(mary, g.nodeIdOf("Mary Hail"), "the spelling finds her");
        assertEquals(1, idx.names(mary).size(), "one name, spelt two ways: " + idx.names(mary));
        assertTrue(idx.names(mary).get(0).texts().contains("Mary Hail"), idx.names(mary).toString());
        assertEquals("aka", FamilyDetail.get(name(store, "John Hart", "Tom Hart"), "kind"), "a nickname is a name he was also known by");
        String ann = g.nodeIdOf("Ann Ellis");
        FamilyNameHistory.Name hale = idx.names(ann).stream().filter(n -> n.written().equals("Ann Hale")).findFirst().orElseThrow();
        assertTrue(hale.texts().contains("Ann Hail"), "a spelling is a form of the name it spells, not of the first name: " + idx.names(ann));
        List<FamilyNameQuestions.Question> qs = FamilyNameQuestions.open(store);
        assertTrue(qs.stream().noneMatch(q -> q.text().contains("Hail") || q.text().contains("Tom Hart")), qs.toString());
    }

    @Test
    void aNameTakenOnAnAdoptionByBothParentsIsLinkedToThatAdoption(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "lib");
        Gedcom.importFile(store, ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME 健二 /遠藤/\n2 TYPE BIRTH\n1 NAME 健二 /森田/\n2 TYPE OTHER\n3 PHRASE 婿養子\n1 SEX M\n1 FAMC @F2@\n2 PEDI ADOPTED\n"
                + "1 ADOP\n2 DATE 1932\n2 FAMC @F2@\n3 ADOP BOTH\n"
                + "0 @I2@ INDI\n1 NAME 勇 /森田/\n1 SEX M\n1 FAMS @F2@\n0 @I3@ INDI\n1 NAME ハル /森田/\n1 SEX F\n1 FAMS @F2@\n0 @F2@ FAM\n1 HUSB @I2@\n1 WIFE @I3@\n1 CHIL @I1@\n"));
        List<String> adoptions = claims(store, "遠藤健二", "adopted-by").stream().map(Finding::id).toList();
        assertEquals(2, adoptions.size());
        Finding named = name(store, "遠藤健二", "森田健二");
        assertEquals("mukoyoshi", FamilyDetail.get(named, "kind"));
        assertTrue(adoptions.contains(FamilyDetail.get(named, "event")), "one adoption by both parents is one event: " + FamilyDetail.of(named));
        // the name taken on that adoption is dated by it, and nobody is asked when it came
        Graph g = FamilyPeople.view(store);
        String kenji = g.nodeIdOf("遠藤健二");
        FamilyNameHistory.Name morita = FamilyNameHistory.of(g).names(kenji).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertTrue(morita.dated(), morita.toString());
        assertEquals(1932, morita.from() != null ? morita.from().year() : FamilyChecks.claimDate(FamilyNameHistory.of(g).finding(morita.event())).year(), morita.toString());
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.kind().equals("name-change-when") && q.people().contains(kenji)), FamilyNameQuestions.open(store).toString());
    }

    @Test
    void anExportReadBackKeepsWhatTheNamesAndMembershipsSay(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "lib");
        Gedcom.importFile(store, ged(tmp, "tree.ged", GedcomNamesTest.MUKOYOSHI + "0 @I9@ INDI\n1 NAME 勝 /森田/\n2 TYPE birth\n1 NAME 勝 /髙橋/\n2 _NAMEKIND succession\n2 _NAMEDATE FROM 1990\n1 BIRT\n2 DATE 1960\n"));
        assertEquals("", FamilyDetail.get(name(store, "森田勝", "髙橋勝"), "said"));
        String out = Gedcom.export(store, "森田勝");
        LibraryStore back = store(tmp, "back");
        Path file = tmp.resolve("back.ged");
        Files.writeString(file, out, StandardCharsets.UTF_8);
        Gedcom.importFile(back, file);
        Graph g = FamilyPeople.view(back);
        String there = g.node(g.nodeIdOf("森田勝")).label();
        assertEquals("", FamilyDetail.get(name(back, there, "髙橋勝"), "said"), "the library's own word for a kind is no word of the source's");
        assertTrue(out.contains("1 NAME 勝 /髙橋/\n2 TYPE succession\n2 _NAMEKIND succession\n"), "a kind GEDCOM has no word for is written as the library's own short word for it: " + out);

        // the membership by 婿養子 comes back with the adoption it came with
        Path kenji = tmp.resolve("kenji.ged");
        Files.writeString(kenji, Gedcom.export(store, "遠藤健二"), StandardCharsets.UTF_8);
        LibraryStore again = store(tmp, "again");
        Gedcom.importFile(again, kenji);
        Graph a = FamilyPeople.view(again);
        String him = a.node(a.nodeIdOf("遠藤健二")).label();
        Finding adoption = claims(again, him, "adopted-by").get(0);
        FamilyHouses.Membership entered = FamilyHouses.families(a, a.nodeIdOf(him)).stream().filter(m -> m.how().equals("mukoyoshi")).findFirst().orElseThrow();
        assertEquals(adoption.id(), FamilyDetail.get(again.finding(entered.claims().get(0)), "event"), "the membership by 婿養子 is linked to the adoption again");
    }
}
