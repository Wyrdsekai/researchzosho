package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the questions about names ask, and what they leave alone: a name the evidence explains (a married woman's name, a name that
 * follows a dated marriage or adoption) is worked out and never asked about; a woman is never offered 婿養子; a person whose sex is not
 * filed is offered the marriage in words that fit a man or a woman; a record still written under the earlier name is asked the other
 * way round; a reading goes on the name it is a reading of; the bracket that tells namesakes apart is no part of a name; every answer can
 * be taken back, and --reopen takes it back and asks the question again; an older library is told once of its questions.
 */
class FamilyNameAskingRulesTest {

    private static Path ged(Path tmp, String name, String body) throws Exception {
        Path f = tmp.resolve(name);
        Files.writeString(f, "0 HEAD\n1 CHAR UTF-8\n" + body + "0 TRLR\n", StandardCharsets.UTF_8);
        return f;
    }

    private static List<FamilyNameQuestions.Question> about(LibraryStore store, String kind, String person) throws Exception {
        Graph g = FamilyPeople.view(store);
        String id = g.nodeIdOf(person);
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals(kind) && q.people().contains(id)).toList();
    }

    private static List<FamilyNameQuestions.Question> howOrWhen(LibraryStore store) throws Exception {
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("name-change-how") || q.kind().equals("name-change-when")).toList();
    }

    private static List<String> keys(FamilyNameQuestions.Question q) { return q.options().stream().map(FamilyNameQuestions.Option::key).toList(); }

    /** What a command prints, on its output and on its error stream, as a person at the terminal sees both. */
    private static String out(Runnable r) {
        PrintStream was = System.out, wasErr = System.err;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PrintStream both = new PrintStream(b, true, StandardCharsets.UTF_8);
        System.setOut(both);
        System.setErr(both);
        try { r.run(); } finally { System.setOut(was); System.setErr(wasErr); }
        return b.toString(StandardCharsets.UTF_8);
    }

    private static int cli(LibraryStore store, StringBuilder printed, String... words) {
        String[] args = new String[words.length + 2];
        args[0] = "researchzosho"; args[1] = "genealogy";
        System.arraycopy(words, 0, args, 2, words.length);
        int[] rc = {0};
        printed.append(out(() -> { try { rc[0] = new GenealogyProfile().cli(store, args); } catch (Exception e) { throw new RuntimeException(e); } }));
        return rc[0];
    }

    // ── asking-1: a tree of married women ───────────────────────────────────────────────────────────────────────────

    @Test
    void aTreeOfMarriedWomenWithTheirMarriedNamesRaisesNoQuestionAboutTheirNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        Gedcom.importFile(store, ged(tmp, "couples.ged",
                "0 @I1@ INDI\n1 NAME Ruth /Hale/\n2 _MARNM Ellis\n1 SEX F\n1 BIRT\n2 DATE 1850\n1 FAMS @F1@\n0 @I2@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 FAMS @F1@\n"
                        + "0 @F1@ FAM\n1 HUSB @I2@\n1 WIFE @I1@\n1 MARR\n2 DATE 1875\n"
                        + "0 @I3@ INDI\n1 NAME Mary /Hart/\n2 _MARNM Moore\n1 SEX F\n1 BIRT\n2 DATE 1860\n1 FAMS @F2@\n0 @I4@ INDI\n1 NAME John /Moore/\n1 SEX M\n1 FAMS @F2@\n"
                        + "0 @F2@ FAM\n1 HUSB @I4@\n1 WIFE @I3@\n1 MARR\n2 DATE 1882\n"
                        + "0 @I5@ INDI\n1 NAME Ann /Lee/\n2 _MARNM Hale\n1 SEX F\n1 BIRT\n2 DATE 1870\n1 FAMS @F3@\n0 @I6@ INDI\n1 NAME Tom /Hale/\n1 SEX M\n1 FAMS @F3@\n"
                        + "0 @F3@ FAM\n1 HUSB @I6@\n1 WIFE @I5@\n1 MARR\n2 DATE 1894\n"));
        List<FamilyNameQuestions.Question> qs = howOrWhen(store);
        assertEquals(List.of(), qs.stream().map(FamilyNameQuestions.Question::text).toList(),
                "each wife's married name follows her dated marriage to a man of that name: worked out, not asked");
    }

    // ── boundary-5: the same, as a tree that types the names, as an older library, and for a 婿養子 with a dated adoption ──

    @Test
    void aMarriedNameATreeTypesOrAnOlderLibraryKeepsAndANameThatFollowsADatedAdoptionAreWorkedOutNotAsked(@TempDir Path tmp) throws Exception {
        LibraryStore typed = FamilyNameQuestionsTest.store(tmp.resolve("typed"));
        Gedcom.importFile(typed, ged(tmp, "small.ged",
                "0 @I1@ INDI\n1 NAME Mary /Hale/\n2 TYPE birth\n1 NAME Mary /Ellis/\n2 TYPE married\n1 SEX F\n1 BIRT\n2 DATE 1850\n1 FAMS @F1@\n"
                        + "0 @I2@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I2@\n1 WIFE @I1@\n1 MARR\n2 DATE 1875\n"
                        + "0 @I3@ INDI\n1 NAME ハル /遠藤/\n2 TYPE birth\n1 NAME ハル /森田/\n2 TYPE married\n1 SEX F\n1 BIRT\n2 DATE 1910\n1 FAMS @F2@\n"
                        + "0 @I4@ INDI\n1 NAME 勝 /森田/\n1 SEX M\n1 FAMS @F2@\n0 @F2@ FAM\n1 HUSB @I4@\n1 WIFE @I3@\n1 MARR\n2 DATE 1930\n"));
        assertEquals(List.of(), howOrWhen(typed).stream().map(FamilyNameQuestions.Question::text).toList(), "a married name dated by its marriage");

        // an older library: the label is her maiden name, her married name only another name of the entry, the marriage dated
        LibraryStore old = FamilyNameQuestionsTest.store(tmp.resolve("old"));
        FamilyAccount.fileAsRead(old, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Ruth Hale", "", List.of("Ruth Ellis"))),
                List.of(FamilyNameQuestionsTest.fact("Ruth Hale", "married-to", "Tom Ellis", "1875", "Ruth Hale married Tom Ellis in 1875, and was Ruth Ellis from then on."),
                        FamilyNameQuestionsTest.fact("Ruth Hale", "sex", "female", "", "Ruth Hale married Tom Ellis")), List.of()), "file:///family/letter.txt", "an aunt");
        assertEquals(List.of(), howOrWhen(old).stream().map(FamilyNameQuestions.Question::text).toList(), "her maiden name is the name before the marriage");

        // a 婿養子 whose later name follows the adoption the file dates
        LibraryStore muko = FamilyNameQuestionsTest.store(tmp.resolve("muko"));
        Gedcom.importFile(muko, ged(tmp, "muko.ged",
                "0 @I1@ INDI\n1 NAME 健二 /遠藤/\n2 TYPE birth\n1 NAME 健二 /森田/\n2 TYPE 婿養子\n2 _NAMEKIND mukoyoshi\n1 SEX M\n1 BIRT\n2 DATE 1905\n1 FAMC @F2@\n2 PEDI adopted\n"
                        + "1 ADOP\n2 DATE 1932\n2 TYPE 婿養子\n2 FAMC @F2@\n3 ADOP HUSB\n1 FAMS @F3@\n"
                        + "0 @I2@ INDI\n1 NAME 勇 /森田/\n1 SEX M\n1 FAMS @F2@\n0 @I3@ INDI\n1 NAME ハル /森田/\n1 SEX F\n1 FAMC @F2@\n1 FAMS @F3@\n"
                        + "0 @F2@ FAM\n1 HUSB @I2@\n1 CHIL @I1@\n1 CHIL @I3@\n0 @F3@ FAM\n1 HUSB @I1@\n1 WIFE @I3@\n1 MARR\n2 DATE 1932\n"));
        assertEquals(List.of(), howOrWhen(muko).stream().filter(q -> q.kind().equals("name-change-when")).map(FamilyNameQuestions.Question::text).toList(),
                "the 婿養子 name follows the adoption of 1932");

        // conflicting evidence stays a question: a register writes her married name years before the marriage
        FamilyAccount.fileAsRead(typed, new FamilyAccount.Read(List.of(), List.of(FamilyNameQuestionsTest.fact("Mary Ellis", "lived-in", "York", "1868", "Mary Ellis, York, 1868")), List.of()),
                "a register", f -> List.of("cite:register of York, 1868"), f -> List.of());
        Graph g = FamilyPeople.view(typed);
        String mary = g.nodeIdOf("Mary Hale");
        assertTrue(FamilyNameQuestions.open(typed).stream().anyMatch(q -> q.people().contains(mary)), "a record under the married name before the marriage: " + FamilyNameQuestions.open(typed));
    }

    // ── asking-2: a woman ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aWomanIsNeverOffered婿養子AndIsOfferedHerHusbandsNameOnlyForTheNameHeCarried(@TempDir Path tmp) throws Exception {
        // her birth name beside her married name: nothing to ask, and above all not "she took her husband's name" for the name of her birth
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Ruth Ellis", "", List.of("Ruth Hale"))),
                List.of(FamilyNameQuestionsTest.fact("Ruth Ellis", "married-to", "Tom Ellis", "1875", "Ruth married Tom Ellis in 1875."),
                        FamilyNameQuestionsTest.fact("Ruth Ellis", "sex", "female", "", "Ruth married Tom Ellis")), List.of()), "file:///family/letter.txt", "an aunt");
        for (FamilyNameQuestions.Question q : about(store, "name-change-how", "Ruth Ellis")) {
            assertFalse(keys(q).contains("mukoyoshi") || keys(q).contains("nyufu"), "婿養子 and 入夫 are a man's: " + q.options());
            if (q.text().endsWith("the name Ruth Hale?")) assertNotEquals("marriage", keys(q).get(0), "Hale is not her husband's name: " + q.options());
        }

        // no husband in the library: the married name is hers by a marriage, and 婿養子 is still not offered
        LibraryStore alone = FamilyNameQuestionsTest.store(tmp.resolve("alone"));
        FamilyNameQuestionsTest.file(alone, "file:///family/notes.txt", List.of(FamilyNameQuestionsTest.fact("Ruth Ellis", "sex", "female", "", "Ruth Ellis, a daughter of the Hales")),
                List.of(FamilyNameQuestionsTest.name("Ruth Ellis", "Ruth Hale", "Hale", "Ruth", "birth", "1850", "Ruth Ellis was born Ruth Hale in 1850.")));
        List<FamilyNameQuestions.Question> qs = about(alone, "name-change-how", "Ruth Ellis");
        assertEquals(1, qs.size(), qs.toString());
        FamilyNameQuestions.Question q = qs.get(0);
        assertEquals("marriage", keys(q).get(0), q.options().toString());
        assertEquals("She took her husband's family name, Ellis, when she married", q.options().get(0).says());
        assertFalse(keys(q).contains("mukoyoshi") || keys(q).contains("nyufu"), q.options().toString());

        // a husband of another family name: her later name is not his, so it is not offered as his
        LibraryStore later = FamilyNameQuestionsTest.store(tmp.resolve("later"));
        FamilyNameQuestionsTest.file(later, "file:///family/notes.txt", List.of(FamilyNameQuestionsTest.fact("Ruth Ellis", "sex", "female", "", "Ruth Ellis"),
                        FamilyNameQuestionsTest.fact("Ruth Ellis", "married-to", "Tom Ellis", "1875", "Ruth married Tom Ellis in 1875.")),
                List.of(FamilyNameQuestionsTest.name("Ruth Ellis", "Ruth Hart", "Hart", "Ruth", "unknown", "1890", "From 1890 she was Ruth Hart.")));
        FamilyNameQuestions.Question hart = about(later, "name-change-how", "Ruth Ellis").stream().filter(x -> x.text().endsWith("the name Ruth Hart?")).findFirst().orElseThrow();
        assertFalse(keys(hart).contains("mukoyoshi") || keys(hart).contains("nyufu"), hart.options().toString());
        for (FamilyNameQuestions.Option o : hart.options()) assertFalse(o.says().contains("her husband's family name") && !o.says().contains("does not have"), "Tom Ellis does not carry Hart: " + o);
    }

    // ── asking-10: a person whose sex is not filed ─────────────────────────────────────────────────────────────────

    /**
     * A person whose sex is not filed, whose later name is of the family part the husband or wife carried at the marriage, and nothing points
     * to more: the name came with the marriage, dated by it, and nobody is asked. Naming the person asks how it came, the marriage first, in
     * words for a man who took his wife's name or a woman who took her husband's; that one answer changes it. Whoever took the other's
     * family name at the marriage entered that family, a man and a woman alike.
     */
    @Test
    void aPersonWhoseSexIsNotFiledIsOfferedTheMarriageInWordsForAManOrAWomanFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/notes.txt", List.of(FamilyNameQuestionsTest.fact("Ruth Ellis", "married-to", "Tom Ellis", "1875", "Ruth Ellis married Tom Ellis in 1875.")),
                List.of(FamilyNameQuestionsTest.name("Ruth Ellis", "Ruth Hale", "Hale", "Ruth", "birth", "1850", "Ruth Ellis, born Ruth Hale in 1850, married Tom Ellis.")));
        assertEquals(List.of(), about(store, "name-change-how", "Ruth Ellis"), "worked out from the marriage of 1875, not asked");
        String ruth = FamilyPeople.view(store).nodeIdOf("Ruth Ellis");
        assertEquals("Ruth Ellis", FamilyNameHistory.of(FamilyPeople.view(store)).at(ruth, 1880).written());
        List<FamilyNameQuestions.Question> qs = FamilyNameQuestions.byName(store, Set.of(ruth)).stream().filter(x -> x.kind().equals("name-change-how")).toList();
        assertEquals(1, qs.size(), qs.toString());
        FamilyNameQuestions.Question q = qs.get(0);
        assertEquals("marriage", keys(q).get(0), "the answer the library worked out first: " + q.options());
        String says = q.options().get(0).says();
        assertTrue(says.contains("Tom Ellis") && !says.startsWith("He ") && !says.startsWith("She "), "words for a man who took his wife's name or a woman who took her husband's: " + says);
        assertTrue(keys(q).containsAll(List.of("mukoyoshi", "yojo", "nyufu", "adoptive")), "婿養子 and 養女 both, where neither one's sex is filed: " + keys(q));
        FamilyNameQuestions.answer(store, q.code(), "1", "", "Ann");
        Finding named = FamilyNameQuestionsTest.claim(store, "Ruth Ellis", "has-name");
        FamilyNameQuestionsTest.theFamilysWord(named, q.code());
        assertEquals("marriage", FamilyDetail.get(named, "kind"));
        FamilyNameQuestionsTest.theFamilysWord(FamilyNameQuestionsTest.claim(store, "Ruth Ellis", "member-of"), q.code());
    }

    // ── asking-3: a record under the earlier name after the change ─────────────────────────────────────────────────

    @Test
    void aRecordStillWrittenUnderTheEarlierNameAfterTheChangeIsAskedTheOtherWayRound(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(),
                List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。"),
                        FamilyNameQuestionsTest.name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", "1932年、森田家に婿養子として入った。")));
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(FamilyNameQuestionsTest.fact("遠藤健二", "lived-in", "広島", "1940", "遠藤健二 広島 1940")), List.of()),
                "a register", f -> List.of("cite:register of 1940, p. 9"), f -> List.of());
        Graph g = FamilyPeople.view(store);
        String kenji = g.nodeIdOf("森田健二");
        List<FamilyNameQuestions.Question> qs = about(store, "name-at-date", "森田健二");
        assertEquals(1, qs.size(), FamilyNameQuestions.open(store).toString());
        FamilyNameQuestions.Question q = qs.get(0);
        assertEquals(List.of("still-used", "year", "another", "later"), keys(q), "the answers of a record under the name before the change: " + q.options());
        assertTrue(q.options().get(1).says().contains("later"), q.options().get(1).says());
        assertThrows(IllegalArgumentException.class, () -> FamilyNameQuestions.answer(store, q.code(), "year", "1938", "Ann"), "a change later than a record of 1940 is after 1940");
        FamilyNameQuestions.answer(store, q.code(), "year", "1941", "Ann");
        FamilyNameHistory.Index idx = FamilyNameHistory.of(FamilyPeople.view(store));
        assertEquals("遠藤健二", idx.at(kenji, 1910).written(), "the birth name still starts at the birth");
        assertEquals("遠藤健二", idx.at(kenji, 1940).written());
        assertEquals("森田健二", idx.at(kenji, 1945).written(), "the change year went on the later name");
    }

    // ── asking-4: a reading of the label's own name ────────────────────────────────────────────────────────────────

    @Test
    void aReadingOfTheNameTheEntryIsFiledUnderIsAskedAndFiledOnThatName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田正二", "もりた しょうじ", List.of())),
                List.of(FamilyNameQuestionsTest.fact("森田正二", "lived-in", "広島", "1935", "森田正二（もりた しょうじ）は広島に住んだ。")), List.of()), "file:///family/a.txt", "an aunt");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田正二", "もりた まさじ", List.of())),
                List.of(FamilyNameQuestionsTest.fact("森田正二", "occupation", "shopkeeper", "", "森田正二（もりた まさじ）は店を営んだ。")), List.of()), "file:///family/b.txt", "an uncle");
        FamilyNameQuestionsTest.file(store, "file:///family/c.txt", List.of(),
                List.of(FamilyNameQuestionsTest.name("森田正二", "髙橋正二", "髙橋", "正二", "adoptive", "1940", "1940年に髙橋家の養子となり、髙橋正二となった。")));
        List<FamilyNameQuestions.Question> qs = about(store, "reading", "森田正二");
        assertEquals(1, qs.size(), qs.toString());
        FamilyNameQuestions.Question q = qs.get(0);
        assertTrue(q.text().startsWith("The name 森田正二 of 髙橋正二 is read 2 ways"), q.text());
        FamilyNameQuestions.answer(store, q.code(), "r1", "", "Ann");
        FamilyNameHistory.Index idx = FamilyNameHistory.of(FamilyPeople.view(store));
        String id = FamilyPeople.view(store).nodeIdOf("森田正二");
        FamilyNameHistory.Name takahashi = idx.names(id).stream().filter(n -> n.written().equals("髙橋正二")).findFirst().orElseThrow();
        assertFalse(takahashi.texts().contains("もりた しょうじ"), "a reading of 森田 is no form of the 髙橋 name: " + takahashi);
        FamilyNameHistory.Name morita = idx.names(id).stream().filter(n -> n.written().equals("森田正二")).findFirst().orElseThrow();
        assertTrue(morita.texts().contains("もりた しょうじ"), morita.toString());
        assertTrue(about(store, "reading", "森田正二").isEmpty(), "the family said which");
    }

    /**
     * An older library's other name in Latin letters that could be the romaji of either of two names in characters is no name of its own
     * to ask how it came: it is a way of writing one of them. The name in characters is asked about.
     */
    @Test
    void aRomanisedOtherNameThatMayWriteEitherOfTwoNamesInCharactersIsNotAskedHowItCame(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "", List.of("遠藤健二", "Kenji Endo"))),
                List.of(FamilyNameQuestionsTest.fact("森田健二", "born-in", "広島県安芸郡", "1905", "q")), List.of()), "file:///family/old.txt", "an aunt");
        List<FamilyNameQuestions.Question> how = about(store, "name-change-how", "森田健二");
        assertTrue(how.stream().noneMatch(q -> q.text().contains("Kenji Endo")), how.toString());
        assertTrue(how.stream().anyMatch(q -> q.text().contains("遠藤健二")), "the name in characters is still asked about: " + how);
    }

    /** A family and its branch share the family name, and the source says which is which: nobody is asked whether they are one family. */
    @Test
    void aFamilyAndItsBranchAreNotAskedAboutAsTwoFamiliesOfOneName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String q = "森田分家は森田本家の分家である。森田正一は森田分家の当主であった。";
        FamilyHousesTest.file(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田分家", "branch-of", "森田本家", "", q),
                FamilyHousesTest.member("森田正一", "森田分家", "", q, Map.of("role", "head")), FamilyHousesTest.member("森田勇", "森田本家", "", q, Map.of())), List.of());
        List<FamilyNameQuestions.Question> which = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("which-family")).toList();
        assertTrue(which.isEmpty(), which.toString());
    }

    /** Somebody of a family whom the words tie to nobody is asked about as somebody of that family, in words a person reads as a sentence. */
    @Test
    void somebodyOfAFamilyWrittenOnlyByTheFamilyNameIsAskedAboutAsSomebodyOfThatFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endo", "", List.of(), "Endo", "")),
                List.of(new FamilyAccount.Fact("Endo", "died-on", "1921", "", "Endo died in 1921.", Map.of("only-family-name", "true"))), List.of()), "file:///family/letter1.txt", "an aunt");
        List<FamilyNameQuestions.Question> alone = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("family-name-alone")).toList();
        assertEquals(1, alone.size(), FamilyNameQuestions.open(store).toString());
        String text = alone.get(0).text();
        assertTrue(text.startsWith("A source writes somebody of the Endo family only by the family name Endo"), text);
        assertFalse(text.contains("writes Endo family's member") || text.contains("fits as Endo family's member"), "the label is quoted as it is, and the sentence says who: " + text);
    }

    // ── asking-5: the bracket that tells namesakes apart ───────────────────────────────────────────────────────────

    @Test
    void theBracketThatTellsTwoEntriesOfOneNameApartIsNoPartOfTheName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/notes.txt", List.of(FamilyNameQuestionsTest.fact("Tom Hart (born 1850)", "sex", "male", "", "Tom Hart was a son of the Ellises")),
                List.of(FamilyNameQuestionsTest.name("Tom Hart (born 1850)", "Tom Ellis", "Ellis", "Tom", "birth", "1850", "Tom Hart was born Tom Ellis in 1850.")));
        List<FamilyNameQuestions.Question> qs = about(store, "name-change-how", "Tom Hart (born 1850)");
        assertEquals(1, qs.size(), FamilyNameQuestions.open(store).toString());
        FamilyNameQuestions.Question q = qs.get(0);
        assertTrue(q.text().endsWith("get the name Tom Hart?"), q.text());
        for (FamilyNameQuestions.Option o : q.options()) assertFalse(o.does().contains("named Tom Hart (born 1850)") || o.does().contains("known as Tom Hart (born 1850)"), o.toString());
        FamilyNameQuestions.answer(store, q.code(), "legal", "", "Ann");
        Finding named = FamilyNameQuestionsTest.claim(store, "Tom Hart (born 1850)", "has-name");
        FamilyNameQuestionsTest.theFamilysWord(named, q.code());
        assertEquals("name: Tom Hart", named.triple().object());
        assertFalse(FamilyDetail.get(named, "forms").contains("(born 1850)"), FamilyDetail.get(named, "forms"));
    }

    // ── asking-7: Enter in the year field ──────────────────────────────────────────────────────────────────────────

    @Test
    void enterInTheYearFieldOfTheWhoPageAnswersWithTheYear(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(),
                List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。"),
                        FamilyNameQuestionsTest.name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", "1932年、森田家に婿養子として入った。")));
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(FamilyNameQuestionsTest.fact("森田健二", "lived-in", "広島", "1920", "森田健二 広島 1920")), List.of()),
                "a register", f -> List.of("cite:register of 1920, p. 4"), f -> List.of());
        FamilyNameQuestions.Question q = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("name-at-date")).findFirst().orElseThrow();
        String page = WhoPage.body(store, Patrons.Patron.PERSON, "", "names-" + q.code());
        // implicit submission: Enter in a text field presses the first submit button of the form that holds the field
        Matcher forms = Pattern.compile("(?s)<form\\b.*?</form>").matcher(page);
        String withYear = null;
        while (forms.find()) if (forms.group().contains("name=\"year\"")) withYear = forms.group();
        assertNotNull(withYear, page);
        Matcher first = Pattern.compile("<button[^>]*value=\"([^\"]*)\"").matcher(withYear);
        assertTrue(first.find(), withYear);
        assertEquals("year", first.group(1), "the button Enter presses in the form with the year field: " + withYear);
    }

    // ── asking-8: "written later" taken back ──────────────────────────────────────────────────────────────────────

    @Test
    void theAnswerThatARecordWasWrittenLaterCanBeTakenBackAndTheQuestionComesBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(),
                List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。"),
                        FamilyNameQuestionsTest.name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", "1932年、森田家に婿養子として入った。")));
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(FamilyNameQuestionsTest.fact("森田健二", "lived-in", "広島", "1920", "森田健二 広島 1920")), List.of()),
                "a register", f -> List.of("cite:register of 1920, p. 4"), f -> List.of());
        FamilyNameQuestions.Question q = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("name-at-date")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, q.code(), "written-later", "", "Ann");
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(x -> x.kind().equals("name-at-date")));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        FamilyNameQuestions.cliAnswered(store, new PrintStream(b, true, StandardCharsets.UTF_8));
        String listed = b.toString(StandardCharsets.UTF_8);
        assertTrue(listed.contains("note") && listed.contains("researchzosho genealogy who --reopen " + q.code()), "the note and the command that takes it back: " + listed);
        FamilyNameQuestions.Reopened r = FamilyNameQuestions.reopen(store, q.code());
        assertTrue(r.open(), r.said());
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.notes().stream().anyMatch(n -> n.kind().equals(FamilyNameQuestions.NOTE))), "the note is taken off the record");
        assertEquals(List.of(q.code()), FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("name-at-date")).map(FamilyNameQuestions.Question::code).toList(), "asked again");
    }

    // ── asking-9: --reopen of an answer that stands ───────────────────────────────────────────────────────────────

    @Test
    void reopenTakesBackAJoinThatStillStandsAndTheQuestionIsAskedAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/a.txt", List.of(FamilyNameQuestionsTest.fact("遠藤健二", "child-of", "遠藤正一", "", "遠藤正一の子 健二")), List.of());
        FamilyNameQuestionsTest.file(store, "file:///family/b.txt", List.of(FamilyNameQuestionsTest.fact("森田健二", "child-of", "遠藤正一", "", "遠藤正一の子で、森田家に入った健二")),
                List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "森田健二は1905年に遠藤健二として生まれた。")));
        FamilyNameQuestions.Question q = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("one-person")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, q.code(), "one", "", "Ann");

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        FamilyNameQuestions.cliAnswered(store, new PrintStream(b, true, StandardCharsets.UTF_8));
        String listed = b.toString(StandardCharsets.UTF_8);
        assertTrue(listed.contains("researchzosho graph unmerge \"遠藤健二\"") && listed.contains("researchzosho genealogy who --reopen " + q.code()), listed);

        StringBuilder printed = new StringBuilder();
        int rc = cli(store, printed, "who", "--reopen", q.code());
        assertEquals(0, rc, printed.toString());
        assertTrue(printed.toString().startsWith("The question " + q.code() + " is open again.") && printed.toString().contains("are separate again"), "it says what it took back: " + printed);
        Graph g = FamilyPeople.view(store);
        assertNotEquals(g.nodeIdOf("森田健二"), g.nodeIdOf("遠藤健二"), "the join is taken back");
        assertEquals(List.of(q.code()), FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("one-person")).map(FamilyNameQuestions.Question::code).toList(),
                "and the question is asked again, as --reopen said");
        assertFalse(FamilyNameQuestions.asked(store).containsKey(q.code()));
    }

    @Test
    void reopenTakesBackClaimsAnAnswerFiledAndAClaimItDisputedCountsAsBefore(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(FamilyNameQuestionsTest.fact("森田健二", "child-of", "森田勇", "", "勇の子 健二"), FamilyNameQuestionsTest.fact("森田勇", "born-on", "1870", "", "勇 明治三年生"),
                        FamilyNameQuestionsTest.fact("森田健二", "born-on", "1905", "", "健二 1905年生")),
                List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は遠藤家に生まれた"), FamilyNameQuestionsTest.name("森田勇", "森田勇", "森田", "勇", "birth", "1870", "勇 明治三年生")));
        FamilyNameQuestions.Question q = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("birth-or-adoptive")).findFirst().orElseThrow();
        Finding childOf = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("child-of")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, q.code(), "adoptive", "", "Ann");
        Finding adopted = FamilyNameQuestionsTest.claim(store, "森田健二", "adopted-by");
        assertEquals(Finding.State.disputed, store.finding(childOf.id()).state());
        FamilyNameQuestions.Reopened r = FamilyNameQuestions.reopen(store, q.code());
        assertTrue(r.open(), r.said());
        assertEquals(Finding.State.retired, store.finding(adopted.id()).state(), "the family's claim is taken back");
        assertEquals(Finding.State.draft, store.finding(childOf.id()).state(), "the source's claim counts as it did before the answer");
        assertEquals(List.of(q.code()), FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("birth-or-adoptive")).map(FamilyNameQuestions.Question::code).toList());
    }

    // ── boundary-10: an older library is told once ─────────────────────────────────────────────────────────────────

    @Test
    void aLibraryAnOlderBuildUpgradedIsToldOnceOfItsQuestionsAboutNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyWhoNamesSittingTest.family(tmp);
        // an older build ran the upgrade before questions about names existed: its marker is there, and no note about names was given
        Path marker = store.root().resolve("catalog").resolve("migrations").resolve("run-fields.txt");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "2026-09-23\tthe runs of fields that act only when asked were recorded\n");
        StringBuilder first = new StringBuilder(), second = new StringBuilder();
        cli(store, first, "family");
        assertTrue(first.toString().contains("One question about names and families waits for the family: how a name changed (1).") && first.toString().contains("researchzosho genealogy who"), first.toString());
        cli(store, second, "family");
        assertFalse(second.toString().contains("about names and families wait"), "once: " + second);

        // a library the upgrade tells is not told again by the commands
        LibraryStore fresh = FamilyWhoNamesSittingTest.family(tmp.resolve("fresh"));
        List<String> notes = new GenealogyProfile().upgrade(fresh, Set.of());
        assertTrue(notes.stream().anyMatch(n -> n.contains("about names and families waits")), notes.toString());
        Path m2 = fresh.root().resolve("catalog").resolve("migrations").resolve("run-fields.txt");
        Files.createDirectories(m2.getParent());
        Files.writeString(m2, "2026-09-24\tthe runs of fields that act only when asked were recorded\n");
        StringBuilder again = new StringBuilder();
        cli(fresh, again, "family");
        assertFalse(again.toString().contains("about names and families wait"), again.toString());
    }
}
