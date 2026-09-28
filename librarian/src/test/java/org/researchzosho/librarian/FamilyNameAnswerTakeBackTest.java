package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every answer to a question about names and families is taken back whole by the command it prints: after the take-back the library is as
 * before (its claims, its joins, the entries the answer made, the answered lines) and the question is asked again, with every answer it
 * offered before. An answer is filed under the entry the question was about, so it follows that entry through later joins and take-backs.
 */
class FamilyNameAnswerTakeBackTest {

    @AfterEach void restore() { FamilyNameQuestions.candidatesForTests = null; }

    static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return FamilyNameQuestionsTest.fact(s, r, o, date, quote); }

    static List<FamilyNameQuestions.Question> of(LibraryStore store, String kind) throws Exception { return FamilyNameQuestionsTest.of(store, kind); }

    /**
     * The question how Tom Hale's name came, as the family reaches it by naming him: his name is of the family part of the wife he married in
     * 1880 and nothing points to more, so the library works it out as the marriage's and asks only where the family names him.
     */
    static FamilyNameQuestions.Question tomsHow(LibraryStore store) throws Exception {
        return FamilyNameQuestions.byName(store, Set.of(FamilyPeople.view(store).nodeIdOf("Tom Hale"))).stream()
                .filter(x -> x.kind().equals("name-change-how") && x.text().contains("the name Tom Hale")).findFirst().orElseThrow();
    }

    static Set<String> nodeLines(LibraryStore store) throws Exception { return new TreeSet<>(Vocabulary.read(Graph.nodesFile(store)).terms().keySet()); }

    static String answered(LibraryStore store) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FamilyNameQuestions.cliAnswered(store, new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    static List<Finding> filedBy(LibraryStore store, String code) {
        return store.scanFindings().findings().stream().filter(f -> f.sources().stream().anyMatch(s -> s.locator().equals(FamilyNameQuestions.SOURCE + code))).toList();
    }

    @Test
    void takingBackAChosenCandidateAsksTheQuestionAgainWithEveryCandidate(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String q = "遠藤の子、森田健二は1905年に生まれた。";
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(
                fact("森田健二", "child-of", "森田健二's parent (written only as 遠藤)", "", q),
                fact("森田健二", "born-on", "1905", "", q),
                fact("遠藤正一", "born-on", "1875", "", "遠藤正一は1875年に生まれた。"),
                fact("遠藤勝", "born-on", "1878", "", "遠藤勝は1878年に生まれた。")), List.of());
        FamilyNameQuestions.Question first = of(store, "family-name-alone").get(0);
        assertEquals(List.of("c1", "c2", "person", "later"), first.options().stream().map(FamilyNameQuestions.Option::key).toList(), first.text());
        FamilyNameQuestions.answer(store, first.code(), "c1", "", "Ann");
        FamilyNameQuestions.reopen(store, first.code());
        FamilyNameQuestions.Question again = of(store, "family-name-alone").get(0);
        assertEquals(first.options().stream().map(FamilyNameQuestions.Option::says).toList(), again.options().stream().map(FamilyNameQuestions.Option::says).toList(),
                "the family's own take-back is no refusal of the person they chose: " + again.text());
        assertTrue(again.text().contains("2 people in your library bore the name 遠藤 then"), again.text());
    }

    @Test
    void aNameTheFamilySaysWasNeverTheirsGoesAndComesBackWithTheTakeBack(@TempDir Path tmp) throws Exception {
        // an older reading of a memoir filed its editor's name among the writer's names
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/letter.txt", List.of(
                        fact("Tom Hale", "sex", "male", "", "Tom Hale"), fact("Ruth Hale", "sex", "female", "", "Ruth Hale"),
                        fact("Tom Hale", "married-to", "Ruth Hale", "1880", "Tom Hale married Ruth Hale in 1880.")),
                List.of(FamilyNameQuestionsTest.name("Tom Hale", "Tom Ellis", "Ellis", "Tom", "birth", "1855", "Tom Hale was born Tom Ellis in 1855.")));
        Graph.alias(store, "Tom Hale", List.of("Walter Kreel", "Walter J. Kreel"), "file:///family/memoir.epub");
        String id = FamilyPeople.view(store).nodeIdOf("Tom Hale");
        FamilyNameQuestions.Question q = FamilyNameQuestions.byName(store, Set.of(id)).stream()
                .filter(x -> x.kind().equals("name-change-how") && x.text().contains("the name Walter Kreel")).findFirst().orElseThrow();
        assertTrue(q.options().stream().anyMatch(o -> o.key().equals("not-theirs") && o.says().equals("It was never a name of Tom Hale")), q.options().toString());
        FamilyNameQuestions.answer(store, q.code(), "not-theirs", "", "Ann");
        assertFalse(Files.readString(Graph.nodesFile(store)).contains("Kreel"), "the name is off his entry, with its other form");
        assertTrue(FamilyNameQuestions.byName(store, Set.of(id)).stream().noneMatch(x -> x.text().contains("Walter Kreel")), "and nothing asks about it");
        FamilyNameQuestions.reopen(store, q.code());
        assertTrue(Files.readString(Graph.nodesFile(store)).contains("Walter Kreel") && Files.readString(Graph.nodesFile(store)).contains("Walter J. Kreel"), "taking the answer back gives both back");
        assertTrue(FamilyNameQuestions.byName(store, Set.of(id)).stream().anyMatch(x -> x.text().contains("the name Walter Kreel")), "and the question is asked again");
        // the name the entry is filed under is not offered to take away
        LibraryStore plain = FamilyNameQuestionsTest.store(tmp.resolve("plain"));
        FamilyNameQuestionsTest.file(plain, "file:///family/letter.txt", List.of(
                        fact("Tom Hale", "sex", "male", "", "Tom Hale"), fact("Ruth Hale", "sex", "female", "", "Ruth Hale"),
                        fact("Tom Hale", "married-to", "Ruth Hale", "1880", "Tom Hale married Ruth Hale in 1880.")),
                List.of(FamilyNameQuestionsTest.name("Tom Hale", "Tom Ellis", "Ellis", "Tom", "birth", "1855", "Tom Hale was born Tom Ellis in 1855.")));
        assertTrue(tomsHow(plain).options().stream().noneMatch(o -> o.key().equals("not-theirs")), tomsHow(plain).options().toString());
    }

    @Test
    void takingBackAnAnswerThatMadeAFamilyTakesTheFamilyBackToo(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/letter.txt", List.of(
                        fact("Tom Hale", "sex", "male", "", "Tom Hale"), fact("Ruth Hale", "sex", "female", "", "Ruth Hale"),
                        fact("Tom Hale", "married-to", "Ruth Hale", "1880", "Tom Hale married Ruth Hale in 1880.")),
                List.of(FamilyNameQuestionsTest.name("Tom Hale", "Tom Ellis", "Ellis", "Tom", "birth", "1855", "Tom Hale was born Tom Ellis in 1855.")));
        Set<String> before = nodeLines(store);
        FamilyNameQuestions.Question q = tomsHow(store);
        FamilyNameQuestions.answer(store, q.code(), "marriage", "", "Ann");
        assertFalse(FamilyHouses.all(FamilyPeople.view(store)).isEmpty(), "the answer made the Hale family");
        FamilyNameQuestions.reopen(store, q.code());
        assertEquals(List.of(), FamilyHouses.all(FamilyPeople.view(store)), "no family is left that only the answer made");
        assertEquals(before, nodeLines(store), "nodes.md is as it was before the answer");
        assertEquals(q.code(), tomsHow(store).code(), "and the question is asked again where the family names him");
    }

    /**
     * f-married-later-hides-page-command: the names page says `genealogy who "Tom Hale"` asks how his worked-out name came. After the family
     * put that question off, naming him still asks it, so the command the page gives does what it says.
     */
    @Test
    void aQuestionPutOffIsAskedWhenTheFamilyNamesThePerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/letter.txt", List.of(
                        fact("Tom Hale", "sex", "male", "", "Tom Hale"), fact("Ruth Hale", "sex", "female", "", "Ruth Hale"),
                        fact("Tom Hale", "married-to", "Ruth Hale", "1880", "Tom Hale married Ruth Hale in 1880.")),
                List.of(FamilyNameQuestionsTest.name("Tom Hale", "Tom Ellis", "Ellis", "Tom", "birth", "1855", "Tom Hale was born Tom Ellis in 1855.")));
        FamilyNameQuestions.Question q = tomsHow(store);
        FamilyNameQuestions.answer(store, q.code(), "later", "", "Ann");
        assertTrue(FamilyNamesFilingTest.run(store, "names", "Tom Hale").contains("researchzosho genealogy who \"Tom Hale\" asks how this name came"), "the page still gives the command");
        assertEquals(q.code(), tomsHow(store).code(), "naming him asks the question that was put off");
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(x -> x.code().equals(q.code())), "the library does not ask it again by itself");
    }

    @Test
    void takingBackTheFamilyAnswerTakesBackTheLineItWroteForTheDescribedPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(
                fact("Morita Kenji", "child-of", "Endo", "", "Endo's son, Morita Kenji, went to the village school in 1920."),
                fact("Shoichi Endo", "born-in", "Hiroshima", "1875", "Shoichi Endo was born in Hiroshima in 1875.")), List.of(FamilyNameQuestionsTest.name("Shoichi Endo", "Shoichi Endo", "Endo", "Shoichi", "birth", "1875", "Shoichi Endo was born in Hiroshima in 1875.")));
        Set<String> before = nodeLines(store);
        Map<String, String> mergesBefore = Graph.merges(store);
        FamilyNameQuestions.Question q = of(store, "family-name-alone").get(0);
        FamilyNameQuestions.answer(store, q.code(), "family", "", "Ann");
        assertEquals(2, FamilyNameQuestions.asked(store).size(), "the answer and the described person's own line");
        FamilyNameQuestions.reopen(store, q.code());
        assertEquals(Map.of(), FamilyNameQuestions.asked(store), "both lines are taken back: " + answered(store));
        assertFalse(answered(store).contains("Part of this answer was taken back"), answered(store));
        assertEquals(before, nodeLines(store), "no family and no described person is left that only the answer made");
        assertEquals(mergesBefore, Graph.merges(store));
        assertTrue(FamilyNameQuestions.open(store).stream().anyMatch(x -> x.code().equals(q.code())));
    }

    @Test
    void anAnswerThatDisputedAClaimIsTakenBackByReopenAndCountingTheClaimAgainAsksAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/tree.txt", List.of(
                fact("Tom Hale", "child-of", "John Hale", "", "Tom Hale, son of John Hale."),
                fact("Tom Hale", "child-of", "John Ellis", "", "Tom Hale, son of John Ellis."),
                fact("John Hale", "sex", "male", "", "John Hale"), fact("John Ellis", "sex", "male", "", "John Ellis")), List.of());
        FamilyNameQuestions.Question q = of(store, "birth-or-adoptive").get(0);
        String disputed = q.findings().get(0);
        FamilyNameQuestions.answer(store, q.code(), "adoptive", "", "Ann");
        String list = answered(store);
        assertFalse(list.contains("researchzosho accept"), "accepting the claim makes it the family's word, which it never was: " + list);
        assertTrue(list.contains("researchzosho genealogy who --reopen " + q.code()), list);
        new Council(store).accept(disputed);   // the claim counts again by another way
        assertTrue(FamilyNameQuestions.open(store).stream().anyMatch(x -> x.code().equals(q.code())), "the claim it disputed counts again, so the answer no longer stands and the question is back");
    }

    /** The old library of the owner's review: a register's entry in characters, joined into the book's entry, then a question about the join's adoption. */
    @Test
    void anAnswerFollowsTheEntryItWasAboutWhenAnEarlierJoinIsTakenBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String reg = "森田健二、1932年森田勇の婿養子となる。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        fact("森田健二", "born-on", "1905", "", "森田健二、1905年生。"),
                        new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", reg, Map.of("kind", "mukoyoshi")),
                        new FamilyAccount.Fact("森田勇", "member-of", "森田家", "1907", "森田勇は森田家の当主。", Map.of("role", "head", "how", "succession"))),
                List.of(), List.of(), List.of(), List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", "森田勇は森田家の当主。"))),
                "an uncle", f -> List.of("file:///family/register.txt"), f -> List.of());
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(fact("Morita Kenji", "life-event", "went to the village school", "1920", "Morita Kenji went to the village school in 1920.")), List.of());
        Graph.merge(store, "森田健二", "Morita Kenji", "person", "the family's answer: one person");
        FamilyNameQuestions.Question q = of(store, "name-change-how").stream().filter(x -> x.text().contains("婿養子")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, q.code(), "adoptive", "", "Ann");
        List<Finding> filed = filedBy(store, q.code());
        assertFalse(filed.isEmpty());
        Graph.unmerge(store, "森田健二", "", "person", "the family took back its answer");
        Graph g = FamilyPeople.view(store);
        for (Finding f : filed)
            assertEquals(g.nodeIdOf("森田健二"), g.nodeIdOf(f.triple().subject()), "the answer was about the register's adoption of 森田健二, and it stays with him: " + f.triple());
    }

    // e-asking-answers-keyed-by-ids-lost-on-join: an answer that files nothing was lost when its entry was joined into another, and the
    // question was asked again about the entry it went into
    @Test
    void anAnswerThatFiledNothingFollowsItsEntryIntoTheEntryItIsJoinedInto(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(
                fact("Morita Kenji", "child-of", "Morita Isamu", "", "Morita Kenji, son of Morita Isamu."),
                fact("Morita Kenji", "child-of", "Endo Shoichi", "", "Morita Kenji, son of Endo Shoichi."),
                fact("Morita Isamu", "sex", "male", "", "Morita Isamu"), fact("Endo Shoichi", "sex", "male", "", "Endo Shoichi"),
                fact("Morita Kenji", "life-event", "went to the village school", "1920", "Morita Kenji went to the village school in 1920.")), List.of());
        String qr = "森田健二（もりた けんじ）、1932年に長野に住む。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("森田健二", "lived-in", "長野", "1932", qr)),
                List.of(), List.of(), List.of(new FamilyAccount.NameRead("森田健二", "森田健二", "森田", "健二", List.of("もりた けんじ"), "", "", "", qr)), List.of()),
                "an uncle", f -> List.of("file:///family/register.txt"), f -> List.of());
        FamilyNameQuestions.Question father = of(store, "birth-or-adoptive").stream().filter(x -> x.text().contains("Morita Isamu")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, father.code(), "birth", "", "Ann");
        FamilyNameQuestions.Question one = of(store, "one-person").get(0);
        FamilyNameQuestions.answer(store, one.code(), "one", "", "Ann");
        List<FamilyNameQuestions.Question> again = of(store, "birth-or-adoptive").stream().filter(x -> x.findings().equals(father.findings())).toList();
        assertEquals(List.of(), again, "the family said Morita Isamu is the birth father; the join does not ask it again");
    }

    // e-owner-10: "one person whose given name is not known" said it would not ask again, and named no command that asks it again
    @Test
    void keepingAnEntryWrittenByAFamilyNameAloneAsAPersonNamesTheCommandThatTakesItBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(
                fact("Morita Kenji", "child-of", "Endo", "", "Endo's son, Morita Kenji, went to the village school in 1920."),
                fact("Shoichi Endo", "born-in", "Hiroshima", "1875", "Shoichi Endo was born in Hiroshima in 1875.")), List.of(FamilyNameQuestionsTest.name("Shoichi Endo", "Shoichi Endo", "Endo", "Shoichi", "birth", "1875", "Shoichi Endo was born in Hiroshima in 1875.")));
        FamilyNameQuestions.Question q = of(store, "family-name-alone").get(0);
        String reopen = "researchzosho genealogy who --reopen " + q.code();
        assertTrue(q.options().stream().filter(o -> o.key().equals("person")).allMatch(o -> o.does().contains(reopen)), q.options().toString());
        assertTrue(FamilyNameQuestions.answer(store, q.code(), "person", "", "Ann").contains(reopen));
    }

    @Test
    void anAnswerThatFiledClaimsAndAJoinIsTakenBackByTheCommandItPrints(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(
                fact("Morita Kenji", "child-of", "Endo", "", "Endo's son, Morita Kenji, went to the village school in 1920."),
                fact("Shoichi Endo", "born-in", "Hiroshima", "1875", "Shoichi Endo was born in Hiroshima in 1875.")), List.of(FamilyNameQuestionsTest.name("Shoichi Endo", "Shoichi Endo", "Endo", "Shoichi", "birth", "1875", "Shoichi Endo was born in Hiroshima in 1875.")));
        FamilyNameQuestions.Question q = of(store, "family-name-alone").get(0);
        FamilyNameQuestions.Option family = q.options().stream().filter(o -> o.key().equals("family")).findFirst().orElseThrow();
        assertTrue(family.does().contains("researchzosho genealogy who --reopen " + q.code()), "the command that takes the whole answer back: " + family.does());
        String said = FamilyNameQuestions.answer(store, q.code(), "family", "", "Ann");
        assertTrue(said.contains("researchzosho genealogy who --reopen " + q.code()) && !said.contains("graph unmerge"), said);
        String list = answered(store);
        assertTrue(list.contains("It moved the fact ") && list.contains("researchzosho genealogy who --reopen " + q.code()) && !list.contains("graph unmerge"), list);

        // the family's own answer is no source's words about the described person
        for (FamilyNameQuestions.Question x : FamilyNameQuestions.open(store)) assertFalse(x.text().contains("family-answer"), x.text());
    }

    @Test
    void whatIsLeftOfATakenBackAnswerSaysSoAndAnAnswerOfTwoClaimsIsTakenBackWhole(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/a.txt", List.of(fact("遠藤健二", "child-of", "遠藤正一", "", "遠藤正一の子 健二")), List.of());
        FamilyNameQuestionsTest.file(store, "file:///family/b.txt", List.of(fact("森田健二", "child-of", "遠藤正一", "", "遠藤正一の子で、森田家に入った健二")),
                List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "森田健二は1905年に遠藤健二として生まれた。")));
        FamilyNameQuestions.Question one = of(store, "one-person").get(0);
        FamilyNameQuestions.answer(store, one.code(), "one", "", "Ann");
        Graph.unmerge(store, "遠藤健二", "", "person", "by hand");
        String list = answered(store);
        assertTrue(list.contains("All of this answer was taken back") && !list.contains("graph unmerge"), "all of it was taken back, and the command it offers was already given: " + list);

        // an answer that files two claims is taken back whole, by the command that takes back both
        LibraryStore other = FamilyNameQuestionsTest.store(tmp.resolve("other"));
        FamilyNameQuestionsTest.file(other, "file:///family/letter.txt", List.of(
                        fact("Tom Hale", "sex", "male", "", "Tom Hale"), fact("Ruth Hale", "sex", "female", "", "Ruth Hale"),
                        fact("Tom Hale", "married-to", "Ruth Hale", "1880", "Tom Hale married Ruth Hale in 1880.")),
                List.of(FamilyNameQuestionsTest.name("Tom Hale", "Tom Ellis", "Ellis", "Tom", "birth", "1855", "Tom Hale was born Tom Ellis in 1855.")));
        FamilyNameQuestions.Question how = tomsHow(other);
        String said = FamilyNameQuestions.answer(other, how.code(), "marriage", "", "Ann");
        assertTrue(filedBy(other, how.code()).size() >= 2, said);
        assertTrue(said.contains("researchzosho genealogy who --reopen " + how.code()) && !said.contains("researchzosho dispute"), said);

    }

    /**
     * A register's 婿養子 adoption and marriage of a man still filed under his birth name, 遠藤健二, with no word of the family he entered and
     * no name of his that carries its name: the question that asks how he came into it.
     */
    static FamilyNameQuestions.Question registerHalves(LibraryStore store) throws Exception {
        String reg = "遠藤健二、1932年森田勇の婿養子となり、ハルと結婚。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        new FamilyAccount.Fact("遠藤健二", "adopted-by", "森田勇", "1932", reg, Map.of("kind", "mukoyoshi")),
                        fact("遠藤健二", "married-to", "森田ハル", "1932", reg)),
                List.of(), List.of(), List.of(FamilyNameQuestionsTest.name("森田勇", "森田勇", "森田", "勇", "birth", "", "森田勇")), List.of()),
                "an uncle", f -> List.of("file:///family/register.txt"), f -> List.of());
        return of(store, "name-change-how").stream().filter(x -> x.text().contains("婿養子")).findFirst().orElseThrow();
    }

    @Test
    void aDisputedAnswerClaimCountedAgainKeepsTheFamilyItNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestions.Question halves = registerHalves(store);
        FamilyNameQuestions.answer(store, halves.code(), "mukoyoshi", "", "Ann");
        List<Finding> member = filedBy(store, halves.code());
        assertEquals(1, member.size(), member.toString());
        new Council(store).dispute(member.get(0).id(), "not so");
        assertTrue(FamilyNameQuestions.open(store).stream().anyMatch(x -> x.code().equals(halves.code())), "the disputed answer no longer stands, so the question is asked again");
        new Council(store).accept(member.get(0).id());
        Graph g = FamilyPeople.view(store);
        List<String> families = FamilyHouses.families(g, g.nodeIdOf("遠藤健二")).stream().map(m -> FamilyHouses.labelOf(g, m.family()) + " " + m.how()).toList();
        assertEquals(List.of("森田 family mukoyoshi"), families, "the claim counts again, and so does the family it names: " + FamilyHouses.all(g));
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(x -> x.code().equals(halves.code())), "and the answer stands again");
    }

    /**
     * f-owner-takeback-retires-source-fact: the family answered "as 婿養子" after the register was read, and the book read next says the same
     * membership, so its source was added to the answer's claim. Taking the answer back takes back only what the answer filed: the claim
     * stays, as a fact from the book, and he is still a member of the 森田 family.
     */
    @Test
    void takingBackAnAnswerKeepsTheFactABookGaveTheSameClaim(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestions.Question halves = registerHalves(store);
        FamilyNameQuestions.answer(store, halves.code(), "mukoyoshi", "", "Ann");
        Finding answer = filedBy(store, halves.code()).stream().filter(f -> f.triple().predicate().equals(FamilyHouses.MEMBER)).findFirst().orElseThrow();
        String book = "In 1932 Kenji entered the Morita family as mukoyōshi (婿養子) of Isamu.";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("遠藤健二", FamilyHouses.MEMBER, answer.triple().object(), "1932", book, Map.of("how", "mukoyoshi"))),
                List.of(), List.of(), List.of(), List.of()), "an aunt", f -> List.of("file:///family/morita-shop.txt"), f -> List.of());
        assertTrue(store.finding(answer.id()).sources().stream().anyMatch(s -> s.locator().endsWith("morita-shop.txt")), "the book's fact was added to the answer's claim: " + store.finding(answer.id()).sources());
        String listed = answered(store);
        assertTrue(listed.contains("A source you read later says " + answer.id().replaceFirst("^(F-\\d+).*", "$1") + " too.") && !listed.contains("researchzosho dispute"),
                "disputing the claim would take the book's fact out with it: " + listed);

        FamilyNameQuestions.Reopened r = FamilyNameQuestions.reopen(store, halves.code());
        Finding kept = store.finding(answer.id());
        assertEquals(Finding.State.draft, kept.state(), "the book's fact stays, no longer marked right by the family: " + r.said());
        assertEquals(List.of("file:///family/morita-shop.txt"), kept.sources().stream().map(Finding.Source::locator).toList(), "only the answer is taken off it");
        assertTrue(r.said().contains("stays, because morita-shop.txt says it too"), r.said());
        Graph g = FamilyPeople.view(store);
        assertEquals(List.of("森田 family mukoyoshi"), FamilyHouses.families(g, g.nodeIdOf("遠藤健二")).stream().map(m -> FamilyHouses.labelOf(g, m.family()) + " " + m.how()).toList());
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(x -> x.code().equals(halves.code())), "the book settles what the question asked");
    }

    @Test
    void anAnswerGivenAgainAfterADisputeJoinsTheFamilyItMadeTheFirstTime(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestions.Question halves = registerHalves(store);
        FamilyNameQuestions.answer(store, halves.code(), "mukoyoshi", "", "Ann");
        new Council(store).dispute(filedBy(store, halves.code()).get(0).id(), "the family was wrong");
        String again = FamilyNameQuestions.answer(store, halves.code(), "mukoyoshi", "", "Ann");
        Graph g = FamilyPeople.view(store);
        assertEquals(List.of("森田 family"), FamilyHouses.all(g).stream().map(f -> FamilyHouses.labelOf(g, f)).toList(), "one 森田 family, not a second one beside it: " + again);
    }
}
