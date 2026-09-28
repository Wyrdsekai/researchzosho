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
 * Where the evidence leaves room for a wrong answer, the family is asked and the library decides nothing: whether a man who married into
 * his wife's family was 婿養子 or only took her name, how a name the entry is filed under in Latin letters came, which of two entries a
 * record under another word order means. Where the evidence settles it, nothing is asked: a name that came with its marriage is not asked
 * when it came, a reading the family chose leaves the names in the order of the life, and the checks count every pair the questions ask
 * about. The words say what happened: an answer is never shown as a source's own words.
 */
class FamilyNameAskingWhoDecidesTest {

    static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return FamilyNameQuestionsTest.fact(s, r, o, date, quote); }

    static List<FamilyNameQuestions.Question> about(LibraryStore store, String kind, String person) throws Exception {
        Graph g = FamilyPeople.view(store);
        String id = g.nodeIdOf(person);
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals(kind) && q.people().contains(id)).toList();
    }

    static Path ged(Path tmp, String name, String body) throws Exception {
        Path f = tmp.resolve(name);
        Files.writeString(f, "0 HEAD\n1 GEDC\n2 VERS 5.5.1\n1 CHAR UTF-8\n" + body + "0 TRLR\n", StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void aManWhoMarriedIntoHisWifesFamilyIsAskedWhetherHeWasMukoyoshiOrTookHerName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String q = "In 1932 Kenji married Haru and joined the Morita family (森田家).";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        fact("森田健二", "married-to", "森田ハル", "1932", q),
                        new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "marriage", "from", "1932")),
                        fact("森田健二", "sex", "male", "", q), fact("森田ハル", "sex", "female", "", q)),
                List.of(), List.of(), List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤健二として生まれた。")),
                List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", q))), "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        List<FamilyNameQuestions.Question> how = about(store, "name-change-how", "森田健二");
        assertEquals(1, how.size(), "the library does not decide 婿養子 or only took her name: " + FamilyNameQuestions.open(store));
        List<String> keys = how.get(0).options().stream().map(FamilyNameQuestions.Option::key).toList();
        assertTrue(keys.contains("mukoyoshi") && keys.contains("marriage"), keys.toString());
        FamilyNameQuestions.answer(store, how.get(0).code(), "marriage", "", "Ann");
        assertTrue(about(store, "name-change-how", "森田健二").isEmpty(), "the family said which");
    }

    /**
     * A tree file's married name, of the family part the husband or wife carried at the marriage, came with that marriage and is dated by it,
     * for a man, a woman and a person whose sex the file does not give alike: nothing points to more, so nobody is asked. (Before the owner's
     * rule of 2026-09-25, "sex is not the switch", a man's and an unsexed person's were asked.)
     */
    @Test
    void aTreeFilesMarriedNameIsDatedByItsMarriageForAManAWomanOrAPersonWhoseSexIsNotFiled(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        Gedcom.importFile(store, ged(tmp, "man.ged", "0 @I1@ INDI\n1 NAME John /Hale/\n1 NAME John /Ellis/\n2 TYPE married\n1 SEX M\n1 BIRT\n2 DATE 1850\n1 FAMS @F1@\n"
                + "0 @I2@ INDI\n1 NAME Ruth /Ellis/\n1 SEX F\n1 BIRT\n2 DATE 1852\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I1@\n1 WIFE @I2@\n1 MARR\n2 DATE 1880\n"
                // a person whose sex the file does not give, married to a woman whose family name the married name is
                + "0 @I3@ INDI\n1 NAME Kenji /Ellis/\n1 NAME Kenji /Hale/\n2 TYPE married\n1 FAMS @F2@\n0 @I4@ INDI\n1 NAME Ann /Hale/\n1 SEX F\n1 FAMS @F2@\n"
                + "0 @F2@ FAM\n1 HUSB @I3@\n1 WIFE @I4@\n1 MARR\n2 DATE 1888\n"
                // and one married to a man
                + "0 @I5@ INDI\n1 NAME Mary /Moore/\n1 NAME Mary /Lee/\n2 TYPE married\n1 FAMS @F3@\n0 @I6@ INDI\n1 NAME Tom /Lee/\n1 SEX M\n1 FAMS @F3@\n"
                + "0 @F3@ FAM\n1 HUSB @I6@\n1 WIFE @I5@\n1 MARR\n2 DATE 1890\n"));
        Graph g = FamilyPeople.view(store);
        String john = g.nodeIdOf("John Ellis"), kenji = g.nodeIdOf("Kenji Hale"), mary = g.nodeIdOf("Mary Lee");
        List<FamilyNameQuestions.Question> qs = FamilyNameQuestions.open(store);
        for (String p : List.of(john, kenji, mary))
            assertTrue(qs.stream().noneMatch(x -> x.kind().startsWith("name-change") && x.people().contains(p)), "a married name that came with its marriage is not asked: " + qs.stream().map(FamilyNameQuestions.Question::text).toList());
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        assertEquals("John Ellis", idx.at(john, 1900).written(), "the name follows the marriage of 1880");
        assertEquals("John Hale", idx.at(john, 1870).written());
        assertEquals("Kenji Hale", idx.at(kenji, 1890).written(), "the name follows the marriage of 1888");
        assertEquals("Mary Lee", idx.at(mary, 1895).written());
    }

    @Test
    void aNameTheEntryIsFiledUnderInLatinLettersIsAskedHowItCame(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/letter.txt", List.of(
                        fact("Kenji Morita", "sex", "male", "", "Kenji Morita"), fact("Haru Morita", "sex", "female", "", "Haru Morita"),
                        fact("Kenji Morita", "married-to", "Haru Morita", "1932", "Kenji Morita married Haru Morita in 1932.")),
                List.of(FamilyNameQuestionsTest.name("Kenji Morita", "遠藤健二", "遠藤", "健二", "birth", "1905", "Kenji Morita was born 遠藤健二 in 1905.")));
        List<FamilyNameQuestions.Question> how = about(store, "name-change-how", "Kenji Morita");
        assertEquals(1, how.size(), FamilyNameQuestions.open(store).toString());
        assertTrue(how.get(0).text().endsWith("How did Kenji Morita get the name Kenji Morita?"), how.get(0).text());
    }

    @Test
    void aMarriedNameThatCameWithAnUndatedMarriageIsNotAskedWhenItCame(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        Gedcom.importFile(store, ged(tmp, "undated.ged", "0 @W1@ INDI\n1 NAME Mary /Hale/\n1 NAME Mary /Ellis/\n1 SEX F\n1 FAMS @F1@\n0 @H1@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 FAMS @F1@\n"
                + "0 @F1@ FAM\n1 HUSB @H1@\n1 WIFE @W1@\n1 MARR\n2 PLAC York\n"
                + "0 @W2@ INDI\n1 NAME Ann /Hart/\n1 NAME Ann /Moore/\n2 TYPE married\n1 SEX F\n1 FAMS @F2@\n0 @H2@ INDI\n1 NAME John /Moore/\n1 SEX M\n1 FAMS @F2@\n"
                + "0 @F2@ FAM\n1 HUSB @H2@\n1 WIFE @W2@\n1 MARR\n2 PLAC York\n"
                + "0 @W3@ INDI\n1 NAME Ruth /Lee/\n2 _MARNM Hale\n1 SEX F\n1 FAMS @F3@\n0 @H3@ INDI\n1 NAME John /Hale/\n1 SEX M\n1 FAMS @F3@\n"
                + "0 @F3@ FAM\n1 HUSB @H3@\n1 WIFE @W3@\n1 MARR\n2 PLAC York\n"));
        List<FamilyNameQuestions.Question> when = FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("name-change-when")).toList();
        assertEquals(List.of(), when.stream().map(FamilyNameQuestions.Question::text).toList(), "each name came with its marriage; when the marriage was is no question about the name");
    }

    @Test
    void aReadingTheFamilyChoseKeepsTheNamesInTheOrderOfTheLife(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田正二", "もりた しょうじ", List.of())),
                List.of(fact("森田正二", "lived-in", "広島", "1935", "森田正二（もりた しょうじ）は広島に住んだ。")), List.of()), "file:///family/a.txt", "an aunt");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田正二", "もりた まさじ", List.of())),
                List.of(fact("森田正二", "occupation", "shopkeeper", "", "森田正二（もりた まさじ）は店を営んだ。")), List.of()), "file:///family/b.txt", "an uncle");
        FamilyNameQuestionsTest.file(store, "file:///family/c.txt", List.of(),
                List.of(FamilyNameQuestionsTest.name("森田正二", "髙橋正二", "髙橋", "正二", "adoptive", "1940", "1940年に髙橋家の養子となり、髙橋正二となった。")));
        String id = FamilyPeople.view(store).nodeIdOf("森田正二");
        assertEquals("髙橋正二", FamilyNameHistory.of(FamilyPeople.view(store)).at(id, 1945).written());
        FamilyNameQuestions.Question q = about(store, "reading", "森田正二").get(0);
        FamilyNameQuestions.answer(store, q.code(), "r1", "", "Ann");
        FamilyNameHistory.Index idx = FamilyNameHistory.of(FamilyPeople.view(store));
        assertNotNull(idx.at(id, 1945), "the answer said how the name is read, nothing of when: " + idx.names(id));
        assertEquals("髙橋正二", idx.at(id, 1945).written());
        assertEquals(List.of("森田正二", "髙橋正二"), idx.names(id).stream().map(FamilyNameHistory.Name::written).toList(), "the names in the order of the life");
        assertTrue(about(store, "reading", "森田正二").isEmpty(), "the family said which");
        assertTrue(FamilyChecks.check(store).stream().noneMatch(p -> p.kind().equals("read-two-ways")), "and the checks know it");
    }

    @Test
    void anEntryATreeFileMetUnderAnotherWordOrderIsAskedAboutAsTheImportPromised(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        Gedcom.importFile(store, ged(tmp, "a5.ged", "0 @I1@ INDI\n1 NAME /Morita/ Kenji\n1 SEX M\n1 OCCU shopkeeper\n"));
        Gedcom.Outcome o = Gedcom.importFile(store, ged(tmp, "b5.ged", "0 @I1@ INDI\n1 NAME 健二 /森田/\n2 ROMN Kenji /Morita/\n3 TYPE romaji\n1 SEX M\n1 BIRT\n2 DATE 1905\n"));
        Graph g = FamilyPeople.view(store);
        String a = g.nodeIdOf("Morita Kenji"), b = g.nodeIdOf("森田健二");
        assertNotEquals(a, b, "the import kept them apart");
        List<FamilyNameQuestions.Question> one = FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("one-person") && q.people().containsAll(List.of(a, b))).toList();
        assertEquals(1, one.size(), "the import said the library would ask: " + o + " " + FamilyNameQuestions.open(store));
    }

    /** The reading pair of the owner's review: 森田健二 with the reading もりた けんじ, and the book's Morita Kenji. */
    static LibraryStore readingPair(Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of())), List.of(
                        new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", "森田健二、1932年森田勇の婿養子となる。", Map.of("kind", "mukoyoshi")),
                        fact("森田健二", "sex", "male", "", "森田健二")), List.of()), "file:///family/register.txt", "an uncle");
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(
                fact("Morita Kenji", "life-event", "went to the village school", "1920", "Morita Kenji went to the village school in 1920."),
                fact("Morita Kenji", "occupation", "silk merchant", "", "Morita Kenji was a silk merchant."),
                fact("Morita Kenji", "lived-in", "Hiroshima", "1930", "Morita Kenji lived in Hiroshima in 1930."),
                fact("Morita Kenji", "sex", "male", "", "Morita Kenji")), List.of());
        return store;
    }

    // e-asking-own-name-namesake-question: a record's own name, written the same way as another entry, was asked about as if a record linked
    // the two; the checks list such a pair as written the same way, once
    @Test
    void anEntrysOwnNameIsNoRecordThatLinksItToANamesake(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/letter.txt", List.of(fact("山田 太郎", "lived-in", "広島", "1930", "山田 太郎は1930年に広島に住んでいた。")), List.of());
        Gedcom.importFile(store, ged(tmp, "t.ged", "0 @I1@ INDI\n1 NAME 太郎 /山田/\n2 TYPE birth\n1 NAME Taro /Yamada/\n1 SEX M\n1 BIRT\n2 DATE 1905\n"));
        assertEquals(List.of(), FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("one-person")).map(FamilyNameQuestions.Question::text).toList(),
                "only the name, written the same way, is the same");
        assertEquals(List.of("same-person?"), FamilyChecks.check(store).stream().map(FamilyChecks.Problem::kind).filter(k -> k.endsWith("person?")).toList());
    }

    // e-asking-han-fold-given-alone: the rule that keeps the entry in characters sent a whole name in Latin letters into a given name alone
    @Test
    void aGivenNameAloneInCharactersGoesIntoTheWholeNameInLatinLetters(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String q = "森田家は広島の家である。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", q))),
                "an aunt", f -> List.of("file:///family/fam.txt"), f -> List.of());
        Gedcom.importFile(store, ged(tmp, "t.ged", "0 @I1@ INDI\n1 NAME Kenji /Morita/\n2 TRAN 健二 /森田/\n3 LANG ja\n1 SEX M\n1 BIRT\n2 DATE 1905\n"));
        FamilyNameQuestionsTest.file(store, "file:///family/letter.txt", List.of(fact("健二", "lived-in", "広島", "1930", "健二は1930年に広島に住んでいた。")), List.of());
        List<FamilyNameQuestions.Question> asked = FamilyNameQuestions.open(store);
        FamilyNameQuestions.Question one = about(store, "one-person", "健二").stream().findFirst().orElseThrow(() -> new AssertionError("asked: " + asked));
        assertTrue(one.options().get(0).does().startsWith("joins “健二” into “Kenji Morita”"), "a given name alone goes into the whole name: " + one.options().get(0).does());
        FamilyNameQuestions.answer(store, one.code(), "one", "", "Ann");
        Graph g = FamilyPeople.view(store);
        assertEquals("Kenji Morita", g.node(g.nodeIdOf("健二")).label(), "the entry keeps the whole name");
        assertEquals("Kenji Morita", FamilyNameHistory.of(g).heading(g.nodeIdOf("健二")), "and is headed by it");
    }

    /**
     * Once the family joined 健二 into Kenji Morita, whose tree file also writes him 森田健二, 健二 is that name's given part alone: a way of
     * writing it, not a name of its own whose reason the family is asked.
     */
    @Test
    void aGivenNameAloneJoinedIntoANameInLatinLettersIsTheGivenPartOfItsFormInCharacters(@TempDir Path tmp) throws Exception {
        // the tree file gives the name as it stands, and as his name at birth
        for (String type : List.of("", "2 TYPE birth\n")) {
            Path dir = tmp.resolve(type.isEmpty() ? "plain" : "typed");
            LibraryStore store = FamilyNameQuestionsTest.store(dir);
            String q = "森田家は広島の家である。";
            FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", q))),
                    "an aunt", f -> List.of("file:///family/fam.txt"), f -> List.of());
            Files.createDirectories(dir);
            Gedcom.importFile(store, ged(dir, "t.ged", "0 @I1@ INDI\n1 NAME Kenji /Morita/\n" + type + "2 TRAN 健二 /森田/\n3 LANG ja\n1 SEX M\n1 BIRT\n2 DATE 1905\n"));
            FamilyNameQuestionsTest.file(store, "file:///family/letter.txt", List.of(fact("健二", "lived-in", "広島", "1930", "健二は1930年に広島に住んでいた。")), List.of());
            List<FamilyNameQuestions.Question> asked = FamilyNameQuestions.open(store);
            FamilyNameQuestions.Question one = about(store, "one-person", "健二").stream().findFirst().orElseThrow(() -> new AssertionError(type + asked));
            FamilyNameQuestions.answer(store, one.code(), "one", "", "Ann");
            Graph g = FamilyPeople.view(store);
            String kenji = g.nodeIdOf("Kenji Morita");
            List<FamilyNameHistory.Name> names = FamilyNameHistory.of(g).names(kenji);
            assertEquals(1, names.size(), type + "健二 is a way of writing his one name: " + names);
            List<FamilyNameQuestions.Question> how = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("name-change-how") && x.people().contains(kenji)).toList();
            assertTrue(how.isEmpty(), type + "nothing asks how he came to carry 健二: " + how.stream().map(FamilyNameQuestions.Question::text).toList());
        }
    }

    @Test
    void oneNameInTwoScriptsIsJoinedIntoTheEntryInCharacters(@TempDir Path tmp) throws Exception {
        LibraryStore store = readingPair(tmp);
        FamilyNameQuestions.Question q = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("one-person")).findFirst().orElseThrow();
        assertTrue(q.options().get(0).does().startsWith("joins “Morita Kenji” into “森田健二”"), "the characters decide which family: " + q.options().get(0).does());
        FamilyNameQuestions.answer(store, q.code(), "one", "", "Ann");
        Graph g = FamilyPeople.view(store);
        assertEquals("森田健二", g.node(g.nodeIdOf("Morita Kenji")).label());
    }

    @Test
    void aGivenNameAloneIsStillAskedAboutAfterItsWholeNameWasJoinedIntoAnEntryInLatinLetters(@TempDir Path tmp) throws Exception {
        LibraryStore store = readingPair(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/letter.txt", List.of(fact("健二", "lived-in", "Kure", "1940", "健二 lived in Kure in 1940.")), List.of());
        Graph.merge(store, "森田健二", "Morita Kenji", "person", "one person, joined by hand");
        Graph g = FamilyPeople.view(store);
        String kenji = g.nodeIdOf("健二"), whole = g.nodeIdOf("Morita Kenji");
        List<FamilyNameQuestions.Question> one = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("one-person") && x.people().containsAll(List.of(kenji, whole))).toList();
        assertEquals(1, one.size(), "健二 and the one whole name with it, written 森田健二 among its forms: " + FamilyNameQuestions.open(store));
    }

    @Test
    void aGivenNameAloneInLatinLettersIsAskedAboutBesideTheWholeNameInCharactersThatASourceSpellsInLatinLetters(@TempDir Path tmp) throws Exception {
        // the reader no longer joins "Kenji" into 森田健二 when nothing in the text pairs them; then the family is asked, never left out
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String q = "Morita Kenji (森田健二, もりた けんじ) ran the silk shop. Kenji lived in Kure in 1940.";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of("Morita Kenji"), "森田", "健二")), List.of(
                        fact("森田健二", "occupation", "silk merchant", "", q), fact("Kenji", "lived-in", "Kure", "1940", q)), List.of(), List.of(),
                        List.of(new FamilyAccount.NameRead("森田健二", "森田健二", "森田", "健二", List.of("もりた けんじ", "Morita Kenji"), "", "", "", q)), List.of()),
                "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        Graph g = FamilyPeople.unlinkedView(store);
        String kenji = g.nodeIdOf("Kenji"), whole = g.nodeIdOf("森田健二");
        assertNotEquals(kenji, whole, "nothing in the text pairs them, so the read keeps two entries");
        // the same text spells 森田健二 Morita Kenji: genealogy's view links the given name alone to the one person it names so ({@link FamilyLinks})
        Finding kure = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals("Kenji")).findFirst().orElseThrow();
        Graph linked = FamilyPeople.view(store);
        assertEquals(linked.nodeIdOf("森田健二"), linked.nodeOf(kure, true));
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(x -> x.kind().equals("one-person") && x.people().contains(kenji)), "linked by the evidence, not asked: " + FamilyNameQuestions.open(store));
    }

    @Test
    void anEntryWrittenByAFamilyNameAloneThatTheFamilyAnsweredIsNotSaidToBeAsked(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(
                fact("Morita Kenji", "child-of", "Endo", "", "Endo's son, Morita Kenji, went to the village school in 1920."),
                fact("Shoichi Endo", "born-in", "Hiroshima", "1875", "Shoichi Endo was born in Hiroshima in 1875.")), List.of(FamilyNameQuestionsTest.name("Shoichi Endo", "Shoichi Endo", "Endo", "Shoichi", "birth", "1875", "Shoichi Endo was born in Hiroshima in 1875.")));
        Graph g = FamilyPeople.view(store);
        assertTrue(FamilyQuestions.find(store, "Endo").note().contains("the questions about names ask who that is"), FamilyQuestions.find(store, "Endo").note());
        FamilyNameQuestions.Question q = FamilyNameQuestionsTest.of(store, "family-name-alone").get(0);
        FamilyNameQuestions.answer(store, q.code(), "person", "", "Ann");
        String note = FamilyQuestions.find(store, "Endo").note();
        assertFalse(note.contains("the questions about names ask who that is"), "answered: nothing asks it any more: " + note + g.nodes().size());
    }

    // e-asking-twoways-not-on-decisions, e-owner-9: the checks listed one name written two ways as a pair for the Decisions page, which never
    // showed it, and offered "check accept" for it, which hid it from the checks while the question kept waiting. The checks now say that
    // questions about names wait and where they are answered
    @Test
    void theChecksListNoPairForTheDecisionsPageThatItCannotShow(@TempDir Path tmp) throws Exception {
        List<FamilyChecks.Problem> problems = FamilyChecks.check(readingPair(tmp));
        assertTrue(problems.stream().filter(p -> p.kind().equals("same-person?")).allMatch(p -> p.people().size() == 2), "a pair the Decisions page offers carries its two names: " + problems);
    }

    @Test
    void theChecksSayWhichQuestionsAboutNamesWaitAndOfferNoOtherWayToAnswerThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = readingPair(tmp);
        List<FamilyChecks.Problem> problems = FamilyChecks.check(store);
        String said = FamilyChecks.forPerson(store, problems);
        for (FamilyChecks.Problem p : problems)
            if (p.text().contains("[question ")) assertFalse(said.contains("genealogy check accept " + p.id()), "a question is answered in genealogy who, not accepted in the checks: " + said);
        long waiting = FamilyNameQuestions.open(store).size();
        assertTrue(waiting > 0);
        assertTrue(said.contains((waiting == 1 ? "One question" : waiting + " questions") + " about names and families " + (waiting == 1 ? "waits" : "wait") + " for your family's answer"), said);
        assertTrue(said.contains("researchzosho genealogy who asks"), said);
    }

    @Test
    void theWordsSayWhatHappens(@TempDir Path tmp) throws Exception {
        LibraryStore store = readingPair(tmp);
        FamilyNameQuestions.Question q = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("one-person")).findFirst().orElseThrow();
        assertFalse(q.options().get(0).does().contains("asks how and when the name changed"), "one name in two scripts is no change of name: " + q.options().get(0).does());

        LibraryStore two = FamilyNameQuestionsTest.store(tmp.resolve("two"));
        FamilyNameQuestionsTest.file(two, "file:///family/a.txt", List.of(fact("遠藤健二", "child-of", "遠藤正一", "", "遠藤正一の子 健二")), List.of());
        FamilyNameQuestionsTest.file(two, "file:///family/b.txt", List.of(fact("森田健二", "child-of", "遠藤正一", "", "遠藤正一の子で、森田家に入った健二")),
                List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "森田健二は1905年に遠藤健二として生まれた。")));
        FamilyNameQuestions.Question named = FamilyNameQuestions.open(two).stream().filter(x -> x.kind().equals("one-person")).findFirst().orElseThrow();
        assertTrue(named.options().get(0).does().contains("asks how and when the name changed"), "two family names: " + named.options().get(0).does());

        // an answer is the family's word, never a source's own words for how a name came
        LibraryStore born = FamilyNameQuestionsTest.store(tmp.resolve("born"));
        FamilyNameQuestionsTest.file(born, "file:///family/book.txt", List.of(fact("森田健二", "born-on", "1905", "", "健二 1905年生")),
                List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。")));
        FamilyNameQuestions.Question how = FamilyNameQuestions.open(born).stream().filter(x -> x.kind().equals("name-change-how")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(born, how.code(), "mukoyoshi", "", "Ann");
        Finding filed = born.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("has-name") && f.sources().get(0).locator().startsWith(FamilyNameQuestions.SOURCE)).findFirst().orElseThrow();
        assertEquals("", FamilyDetail.get(filed, "said"), "the option's words are no source's words: " + FamilyDetail.of(filed));
    }
}
