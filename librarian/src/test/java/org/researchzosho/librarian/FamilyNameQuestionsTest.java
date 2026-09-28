package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The questions about names and families: each kind is raised by its evidence and not otherwise, each answer is the family's word (a
 * claim written by family-account from told://family-answer/<code> and accepted by the person, or a merge or a "two people" by the
 * person), the question goes once answered, comes back when the answer is disputed, and --answered lists it with the command that takes
 * it back.
 */
class FamilyNameQuestionsTest {

    @AfterEach void restore() { FamilyNameQuestions.candidatesForTests = null; }

    static LibraryStore store(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init();
        new LibrarianIndex(s, Embeddings.none()).rebuild();
        return s;
    }

    static FamilyAccount.Outcome file(LibraryStore store, String locator, List<FamilyAccount.Fact> facts, List<FamilyAccount.NameRead> names) throws Exception {
        return FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), facts, List.of(), List.of(), names, List.of()), "an aunt", f -> List.of(locator), f -> List.of());
    }

    static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return new FamilyAccount.Fact(s, r, o, date, quote); }

    static FamilyAccount.NameRead name(String person, String name, String family, String given, String kind, String date, String quote) {
        return new FamilyAccount.NameRead(person, name, family, given, List.of(), kind, "", date, quote);
    }

    static List<FamilyNameQuestions.Question> of(LibraryStore store, String kind) throws Exception {
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals(kind)).toList();
    }

    static Finding claim(LibraryStore store, String subject, String predicate) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals(subject) && f.triple().predicate().equals(predicate)
                && f.sources().stream().anyMatch(s -> s.locator().startsWith(FamilyNameQuestions.SOURCE))).findFirst().orElse(null);
    }

    /** A claim the family's answer filed: written by family-account, from the question, and accepted by the person. */
    static void theFamilysWord(Finding f, String code) {
        assertNotNull(f, "the answer filed a claim");
        assertEquals("family-account", f.writer());
        assertEquals(FamilyNameQuestions.SOURCE + code, f.sources().get(0).locator());
        assertTrue(f.sources().get(0).edition().startsWith("as told by "), f.sources().toString());
        assertEquals(Finding.State.accepted, f.state());
        assertEquals("person", f.review().reviewer());
    }

    // e-owner-8: "he took his wife's family name, without an adoption" left the register's adoption by her father counting beside the answer
    @Test
    void takingTheWifesNameWithoutAnAdoptionDisputesTheAdoptionIntoHerFamilyAndReopenCountsItAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/register.txt", List.of(fact("森田健二", "adopted-by", "森田勇", "1932", "森田健二、1932年森田勇の養子となる。")), List.of());
        String geni = "Geni: 森田健二, husband of 森田ハル, daughter of 森田勇";
        file(store, "https://www.geni.com/people/Kenji-Morita/6000000000021", List.of(
                fact("森田健二", "married-to", "森田ハル", "", geni), fact("森田ハル", "child-of", "森田勇", "", geni),
                fact("森田健二", "sex", "male", "", geni), fact("森田ハル", "sex", "female", "", geni)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "", geni), name("森田健二", "森田健二", "森田", "健二", "marriage", "", geni)));
        String adoption = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("adopted-by")).findFirst().orElseThrow().id();
        FamilyNameQuestions.Question how = of(store, "name-change-how").get(0);
        FamilyNameQuestions.Option took = how.options().stream().filter(o -> o.key().equals("marriage")).findFirst().orElseThrow();
        assertTrue(took.does().contains("his adoption by 森田勇 is marked as disputed, with your answer as the reason"), took.does());
        FamilyNameQuestions.answer(store, how.code(), "marriage", "", "Ann");
        assertEquals(Finding.State.disputed, store.finding(adoption).state(), "the family said there was no adoption");
        FamilyNameQuestions.reopen(store, how.code());
        assertEquals(Finding.State.draft, store.finding(adoption).state(), "taking the answer back counts the adoption again");
    }

    // e-boundary-3: a family whose label begins with its article read "entered the the Hart family"
    @Test
    void aFamilyWhoseNameBeginsWithTheIsNotGivenASecondThe(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String line = "Tom Hart was a member of the Hart family.";
        store.write(new Finding("F-0001-claim", line, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "reviewer",
                "2026-09-01T00:00:00Z", "2026-09-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/page-1", "n/a", "a page")),
                List.of(), null, line + "\n", new Finding.Triple("Tom Hart", "member of", "The Hart family"), List.of()));
        Graph.setKind(store, "The Hart family", "family");
        String q = "Tom Hart, born 1850, married Ruth Hale, born 1855, in 1880.";
        file(store, "file:///family/tree.txt", List.of(fact("Tom Hart", "married-to", "Ruth Hale", "1880", q), fact("Tom Hart", "born-on", "1850", "", q), fact("Ruth Hale", "born-on", "1855", "", q)), List.of());
        Graph.alias(store, "Ruth Hale", List.of("Ruth Hart"));
        List<String> said = new ArrayList<>();
        // Ruth Hart, of the family part of the husband she married in 1880, is worked out as the marriage's; naming her asks how it came
        for (FamilyNameQuestions.Question x : FamilyNameQuestions.byName(store, Set.of(FamilyPeople.view(store).nodeIdOf("Ruth Hale")))) { said.add(x.text()); for (FamilyNameQuestions.Option o : x.options()) said.add(o.says() + " => " + o.does()); }
        assertTrue(said.stream().anyMatch(t -> t.contains("entered The Hart family") || t.contains("entered the Hart family")), String.join("\n", said));
        assertTrue(said.stream().noneMatch(t -> t.toLowerCase(Locale.ROOT).contains("the the ")), String.join("\n", said));
    }

    /** A book's Morita Haru, daughter of Morita Isamu, and a register's 森田ハル, daughter of 森田勇, that gives both their names in Latin letters too. */
    static LibraryStore twoPairs(Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(fact("Morita Haru", "child-of", "Morita Isamu", "", "Morita Haru, the daughter of Morita Isamu.")), List.of());
        String reg = "森田ハル、森田勇の長女。";
        file(store, "file:///family/register.txt", List.of(fact("森田ハル", "child-of", "森田勇", "", reg)), List.of(
                new FamilyAccount.NameRead("森田ハル", "森田ハル", "森田", "ハル", List.of("Morita Haru"), "birth", "", "", reg),
                new FamilyAccount.NameRead("森田勇", "森田勇", "森田", "勇", List.of("Morita Isamu"), "birth", "", "", reg)));
        return store;
    }

    // e-owner-7: an answer about one pair changed what agrees for another pair, the other's code changed with it, and the sitting dropped it;
    // a pair put off came back the same way
    @Test
    void aSittingAsksAPairWhoseEvidenceAnEarlierAnswerChanged(@TempDir Path tmp) throws Exception {
        LibraryStore off = twoPairs(tmp.resolve("off"));
        List<FamilyNameQuestions.Question> pairs = of(off, "one-person");
        FamilyNameQuestions.answer(off, pairs.get(0).code(), "later", "", "Ann");
        FamilyNameQuestions.answer(off, pairs.get(1).code(), "one", "", "Ann");
        assertEquals(List.of(), of(off, "one-person"), "the pair put off stays put off when what agrees for it changes");

        LibraryStore store = twoPairs(tmp.resolve("sitting"));
        List<FamilyNameQuestions.Question> first = of(store, "one-person");
        assertEquals(2, first.size(), first.toString());
        ByteArrayOutputStream shown = new ByteArrayOutputStream();
        FamilyNameQuestions.Sat sat = FamilyNameQuestions.sitting(store, FamilyNameQuestions.ordered(first), null,
                new BufferedReader(new StringReader("1\n1\n")), new PrintStream(shown, true, StandardCharsets.UTF_8), "Ann");
        assertEquals(2, sat.answered(), "both pairs are asked and answered: " + shown.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(), of(store, "one-person"));
    }

    /**
     * f-owner-stale-question-text-in-sitting: the first answer of a sitting joins Morita Kenji into 森田健二, which brings his marriage along.
     * The next question is worked out again before it is shown, so it no longer says that no fact says he married into the family.
     */
    @Test
    void aSittingShowsEachQuestionAsItStandsAfterTheAnswersBeforeIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String reg = "森田健二（もりた けんじ）、1932年森田勇の婿養子となる。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", reg, Map.of("kind", "mukoyoshi"))), List.of(), List.of(),
                        List.of(new FamilyAccount.NameRead("森田健二", "森田健二", "森田", "健二", List.of("もりた けんじ"), "", "", "", reg), name("森田勇", "森田勇", "森田", "勇", "birth", "", reg)), List.of()),
                "an uncle", f -> List.of("file:///family/register.txt"), f -> List.of());
        file(store, "file:///family/book.txt", List.of(fact("Morita Kenji", "married-to", "Morita Haru", "1932", "Morita Kenji married Morita Haru in 1932.")), List.of());
        List<FamilyNameQuestions.Question> first = FamilyNameQuestions.ordered(FamilyNameQuestions.open(store));
        assertEquals(List.of("one-person", "name-change-how"), first.stream().map(FamilyNameQuestions.Question::kind).toList(), first.stream().map(FamilyNameQuestions.Question::text).toList().toString());
        assertTrue(first.get(1).text().contains("married into the family"), first.get(1).text());
        ByteArrayOutputStream shown = new ByteArrayOutputStream();
        FamilyNameQuestions.sitting(store, first, null, new BufferedReader(new StringReader("1\n")), new PrintStream(shown, true, StandardCharsets.UTF_8), "Ann");
        String out = shown.toString(StandardCharsets.UTF_8);
        // the join brought his marriage, and the adoption by a 森田 is his entry into the 森田 family: worked out again after the answer, the
        // second question is gone, and is not shown as it stood before
        assertFalse(out.contains("Question 2 of"), "the second question was not shown stale: " + out);
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(x -> x.kind().equals("name-change-how")), FamilyNameQuestions.open(store).toString());
    }

    /**
     * The owner's old library, re-read after a plain reset: the accepted "Morita Kenji is a child of Endo" stays, and the re-read writes the
     * same sentence's parent as "森田健二's parent (written only as Endo)". Tests give the described person the candidate 遠藤正一; the old
     * "Endo", beside an entry of the romanised name with no birth year, has none of its own.
     */
    static LibraryStore oldEndo(Path tmp) throws Exception { return oldEndo(tmp, true); }

    /**
     * With the romanised form listed in 森田健二's name claim, the old "Morita Kenji" entry joins 森田健二 by the book's own words before anything
     * is asked; without it (an older read that listed only the kana), the two stay apart until the family says they are one person.
     */
    static LibraryStore oldEndo(Path tmp, boolean romanisedForm) throws Exception {
        LibraryStore store = store(tmp);
        String q = "Endo's son, Morita Kenji, told us in 1998 that his father kept silkworms.";
        file(store, "file:///family/morita-shop.txt", List.of(fact("Morita Kenji", "child-of", "Endo", "", q)), List.of());
        new Council(store).accept(store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().object().equals("Endo")).findFirst().orElseThrow().id());
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("森田健二", "child-of", "森田健二's parent (written only as Endo)", "", q),
                        fact("森田健二", "born-on", "1905", "", "Kenji was born in 1905 as Endō Kenji (遠藤健二)."), fact("遠藤正一", "born-on", "1875", "", "Endō Shōichi (遠藤正一) was born in 1875.")), List.of(), List.of(),
                        List.of(new FamilyAccount.NameRead("森田健二", "森田健二", "森田", "健二", romanisedForm ? List.of("もりた けんじ", "Morita Kenji") : List.of("もりた けんじ"), "", "", "", "Morita Kenji (森田健二, もりた けんじ)"),
                                new FamilyAccount.NameRead("遠藤正一", "遠藤正一", "遠藤", "正一", List.of("Endō Shōichi"), "birth", "", "", "Endō Shōichi (遠藤正一)")), List.of()),
                "an aunt", f -> List.of("file:///family/morita-shop.txt"), f -> List.of());
        FamilyNameQuestions.candidatesForTests = (g, placeholder) -> {
            String[] p = FamilyMentions.parts(placeholder);
            return p != null && g.nodeIdOf(p[0]).equals(g.nodeIdOf("森田健二")) ? List.of("遠藤正一") : List.of();
        };
        return store;
    }

    /** The parents 森田健二 has, by the claims that stand. */
    static List<String> parentsOf(LibraryStore store, String child) throws Exception {
        Graph g = FamilyPeople.view(store);
        String c = g.nodeIdOf(child);
        return g.edges().stream().filter(e -> !FamilyKin.gone(e) && e.predicate().equals("child-of") && e.from().equals(c)).map(e -> g.node(e.to()).label()).distinct().sorted().toList();
    }

    /** f-owner-accepted-endo-two-parents, 1: the question about the old "Endo" offers the described person the same sentence gave, and its candidates. */
    @Test
    void theOldFamilyNameEntryIsOfferedTheDescribedPersonTheSameWordsGave(@TempDir Path tmp) throws Exception {
        LibraryStore store = oldEndo(tmp);
        List<FamilyNameQuestions.Question> all = FamilyNameQuestions.open(store);
        FamilyNameQuestions.Question endo = all.stream().filter(x -> x.kind().equals("family-name-alone") && x.text().startsWith("“Endo” is written in your library as a person")).findFirst()
                .orElseThrow(() -> new AssertionError(all.stream().map(FamilyNameQuestions.Question::text).toList().toString()));
        FamilyNameQuestions.Option same = endo.options().stream().filter(o -> o.says().contains("森田健二's parent (written only as Endo)")).findFirst()
                .orElseThrow(() -> new AssertionError(endo.options().toString()));
        assertTrue(endo.options().stream().anyMatch(o -> o.says().startsWith("遠藤正一")), "and the people it may be: " + endo.options());
        FamilyNameQuestions.answer(store, endo.code(), same.key(), "", "Ann");
        FamilyNameQuestions.Question who = of(store, "family-name-alone").get(0);
        FamilyNameQuestions.answer(store, who.code(), who.options().stream().filter(o -> o.says().startsWith("遠藤正一")).findFirst().orElseThrow().key(), "", "Ann");
        // the old "Morita Kenji" entry is 森田健二 by the book's own name claim, which lists that form: nothing is left to ask
        assertEquals(List.of(), of(store, "one-person"), "the romanised entry joined by the book's name claim");
        assertEquals(List.of("遠藤正一"), parentsOf(store, "森田健二"), "one parent from one sentence");
    }

    /** f-owner-accepted-endo-two-parents, 3: with the romanised form in the book's name claim, one sentence gives one parent, however "Endo" is answered. */
    @Test
    void theRomanisedEntryJoinsByTheBooksOwnNameClaimSoOneSentenceGivesOneParent(@TempDir Path tmp) throws Exception {
        LibraryStore store = oldEndo(tmp);
        Graph g = FamilyPeople.view(store);
        assertEquals(g.nodeIdOf("森田健二"), g.nodeIdOf("Morita Kenji"), "the book writes 森田健二's name as Morita Kenji too");
        FamilyNameQuestions.Question endo = of(store, "family-name-alone").stream().filter(x -> x.text().startsWith("“Endo” is written in your library as a person")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, endo.code(), "family", "", "Ann");
        assertEquals(List.of("森田健二's parent (written only as Endo)"), parentsOf(store, "森田健二"), "the person the same words already gave");
        assertEquals(List.of(), FamilyNameQuestions.open(store), "the family said the source does not name the parent: nothing is asked about the same words again");
    }

    /** f-owner-accepted-endo-two-parents, 2: answered the other way, the two parents one sentence gives are asked about as one person after the join. */
    @Test
    void twoParentsWrittenOnlyByOneFamilyNameInOneSentenceAreAskedAboutAfterAJoin(@TempDir Path tmp) throws Exception {
        LibraryStore store = oldEndo(tmp, false);
        FamilyNameQuestions.Question endo = of(store, "family-name-alone").stream().filter(x -> x.text().startsWith("“Endo” is written in your library as a person")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, endo.code(), "family", "", "Ann");
        FamilyNameQuestions.Question who = of(store, "family-name-alone").stream().filter(x -> x.text().contains("森田健二's parent")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, who.code(), who.options().stream().filter(o -> o.says().startsWith("遠藤正一")).findFirst().orElseThrow().key(), "", "Ann");
        FamilyNameQuestions.Question kenji = of(store, "one-person").get(0);
        FamilyNameQuestions.answer(store, kenji.code(), "one", "", "Ann");
        assertEquals(2, parentsOf(store, "森田健二").size(), parentsOf(store, "森田健二").toString());
        List<FamilyNameQuestions.Question> pair = of(store, "one-person");
        assertEquals(1, pair.size(), "the two parents one sentence gives are asked about: " + FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList());
        assertTrue(pair.get(0).text().contains("Endo's son, Morita Kenji"), pair.get(0).text());
        FamilyNameQuestions.answer(store, pair.get(0).code(), "one", "", "Ann");
        assertEquals(List.of("遠藤正一"), parentsOf(store, "森田健二"));
    }

    @Test
    void aFamilyNameAloneThatASourceWritesAsAFamilyIsAskedAndAGivenNameAloneIsNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(
                fact("Morita Kenji", "child-of", "Endo", "", "Endo's son, Morita Kenji, went to the village school in 1920."),
                fact("Shoichi Endo", "born-in", "Hiroshima", "1875", "Shoichi Endo was born in Hiroshima in 1875."),
                fact("Ruth", "lived-in", "Leeds", "1920", "Aunt Ruth lived in Leeds in 1920."),
                fact("Ruth Ellis", "born-in", "York", "1890", "Ruth Ellis was born in York in 1890.")),
                // a name in Latin letters written like a Japanese name has no known order until something gives its parts: this claim does
                List.of(name("Shoichi Endo", "Shoichi Endo", "Endo", "Shoichi", "birth", "1875", "Shoichi Endo was born in Hiroshima in 1875.")));
        Graph g = FamilyPeople.view(store);
        List<FamilyNameQuestions.Question> qs = of(store, "family-name-alone");
        assertEquals(1, qs.size(), "Endo, which the book writes as whose son somebody is and which is Shoichi Endo's family name; never Ruth, a given name: " + qs);
        FamilyNameQuestions.Question q = qs.get(0);
        assertEquals(List.of(g.nodeIdOf("Endo")), q.people());
        assertTrue(q.text().startsWith("“Endo” is written in your library as a person with no given name, and Endo is a family name here, as in Shoichi Endo."), q.text());
        assertTrue(q.text().contains("In book.txt, “Endo” is written in 1 passage: “Endo's son, Morita Kenji, went to the village school in 1920.”"), "the record is quoted, with where it is from: " + q.text());
        // nobody fits as his parent by the years, and Shoichi Endo, who bore the name, is somebody the words do not contradict
        assertTrue(q.text().contains("Nobody in your library bore the name Endo then and fits as Morita Kenji's parent. One other person in your library who bore the name Endo fits these words."), q.text());
        assertEquals(List.of("c1", FamilyNameQuestions.SOMEONE, "person", "family", "later"), q.options().stream().map(FamilyNameQuestions.Option::key).toList());
        assertEquals("Shoichi Endo (born 1875)", q.options().get(0).says());
        assertTrue(q.options().get(3).does().contains("“Morita Kenji's parent (written only as Endo)”") && q.options().get(3).does().contains("researchzosho genealogy who --reopen " + q.code()), q.options().get(3).does());
        assertEquals(q.code(), FamilyNameQuestions.code("family-name-alone", q.people(), q.findings()), "the code is the kind, the people and the claims");

        // the same entry with nothing that writes it as a family is somebody called Endo, not a question
        LibraryStore plain = store(tmp.resolve("plain"));
        file(plain, "file:///family/notes.txt", List.of(fact("Endo", "lived-in", "Kure", "1930", "Endo lived in Kure in 1930."),
                fact("Shoichi Endo", "born-in", "Hiroshima", "1875", "Shoichi Endo was born in Hiroshima in 1875.")), List.of());
        assertTrue(of(plain, "family-name-alone").isEmpty(), "no source writes it as a family");

        // the family says: somebody of the Endo family whom the book does not name
        String said = FamilyNameQuestions.answer(store, q.code(), "family", "", "Ann");
        assertTrue(said.contains("“Endo” in these words is now “Morita Kenji's parent (written only as Endo)”, a member of the Endo family"), said);
        Graph now = FamilyPeople.view(store);
        Finding moved = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("child-of")).findFirst().orElseThrow();
        assertEquals(now.nodeIdOf("Morita Kenji's parent (written only as Endo)"), now.nodeIdOf(moved.triple().object()), "the book's claim is about the described person now");
        assertTrue(moved.body().contains("Endo's son, Morita Kenji"), "and its words are still the book's: " + moved.body());
        assertNotEquals(now.nodeIdOf("Morita Kenji's parent (written only as Endo)"), now.nodeIdOf("Endo"), "the entry of the family name alone is no join into anybody");
        theFamilysWord(claim(store, "Morita Kenji's parent (written only as Endo)", "member-of"), q.code());
        assertTrue(FamilyHouses.isFamily(now, now.nodeIdOf("Endo family")));
        assertTrue(!Files.exists(Graph.mergesFile(store)) || Graph.merges(store).isEmpty(), "nothing is joined");
        assertTrue(of(store, "family-name-alone").isEmpty(), "answered, and the described person is not asked about again: " + FamilyNameQuestions.open(store));
    }

    @Test
    void aDescribedPersonIsAskedWithTheCandidatesTheRuleFoundAndTheAnswerJoinsThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(
                fact("森田健二", "child-of", "森田健二's father (written only as Endo)", "", "Endo's son, Morita Kenji"),
                fact("遠藤正一", "born-in", "広島", "1875", "遠藤正一は1875年に広島で生まれた。")), List.of());
        Graph g = FamilyPeople.view(store);
        String shoichi = g.nodeIdOf("遠藤正一");
        FamilyNameQuestions.candidatesForTests = (graph, placeholder) -> placeholder.equals("森田健二's father (written only as Endo)") ? List.of(shoichi) : List.of();
        FamilyNameQuestions.Question q = of(store, "family-name-alone").get(0);
        assertTrue(q.text().startsWith("A source writes 森田健二's father only by the family name Endo: “Endo's son, Morita Kenji” (book.txt)."), q.text());
        assertTrue(q.text().contains("One person in your library bore the name Endo then and fits as 森田健二's father."), q.text());
        assertEquals(List.of("c1", "person", "later"), q.options().stream().map(FamilyNameQuestions.Option::key).toList());
        assertEquals("遠藤正一 (born 1875)", q.options().get(0).says());
        FamilyNameQuestions.answer(store, q.code(), "1", "", "Ann");   // an answer's number is its key too
        Graph now = FamilyPeople.view(store);
        assertEquals(now.nodeIdOf("遠藤正一"), now.nodeIdOf("森田健二's father (written only as Endo)"), "the described person is 遠藤正一");
        String merges = Files.readString(Graph.mergesFile(store));
        assertTrue(merges.contains("\tperson\t") && merges.contains("the family's answer to the question " + q.code()), merges);
        assertTrue(of(store, "family-name-alone").isEmpty());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FamilyNameQuestions.cliAnswered(store, new PrintStream(out, true, StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("researchzosho graph unmerge \"森田健二's father (written only as Endo)\""), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aRecordThatGivesOneEntryTheNameOfAnotherAsksOnePersonFirstAndTheJoinedPersonsNamesAreAskedNext(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/a.txt", List.of(fact("遠藤健二", "child-of", "遠藤正一", "", "遠藤正一の子 健二")), List.of());
        file(store, "file:///family/b.txt", List.of(fact("森田健二", "child-of", "遠藤正一", "", "遠藤正一の子で、森田家に入った健二")),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "森田健二は1905年に遠藤健二として生まれた。")));
        List<FamilyNameQuestions.Question> qs = of(store, "one-person");
        assertEquals(1, qs.size(), qs.toString());
        FamilyNameQuestions.Question q = qs.get(0);
        assertTrue(q.text().startsWith("“森田健二は1905年に遠藤健二として生まれた。” (b.txt) gives 森田健二 (born 遠藤) the name 遠藤健二, and your library has another person of that name, “遠藤健二”."), q.text());
        assertTrue(q.text().contains("What else agrees: both are children of 遠藤正一") && q.text().endsWith("Are they one person?"), q.text());
        assertEquals(List.of("one", "two", "later"), q.options().stream().map(FamilyNameQuestions.Option::key).toList(), "one person is offered first");
        assertTrue(q.options().get(0).does().startsWith("joins “遠藤健二” into “森田健二”"), q.options().get(0).does());

        String said = FamilyNameQuestions.answer(store, q.code(), "one", "", "Ann");
        assertTrue(said.startsWith("“遠藤健二” is joined into “森田健二”."), said);
        Graph g = FamilyPeople.view(store);
        assertEquals(g.nodeIdOf("森田健二"), g.nodeIdOf("遠藤健二"));
        assertTrue(of(store, "one-person").isEmpty());
        // the joined person carries 森田健二 besides the birth name, and how that came is asked next
        List<FamilyNameQuestions.Question> how = FamilyNameQuestions.about(store, Set.of(g.nodeIdOf("森田健二")));
        assertTrue(how.stream().anyMatch(x -> x.kind().equals("name-change-how") && x.text().contains("was born 遠藤健二 and later had the name 森田健二.")), how.toString());

        // taken back, the question is back with the same code
        Graph.unmerge(store, "遠藤健二", "", "person", "a test");
        assertEquals(List.of(q.code()), of(store, "one-person").stream().map(FamilyNameQuestions.Question::code).toList(), "the answer no longer stands");
        FamilyNameQuestions.answer(store, q.code(), "two", "", "Ann");
        assertTrue(Graph.differentPairs(store).contains(Graph.pair(g.nodeIdOf("森田健二"), FamilyPeople.view(store).nodeIdOf("遠藤健二"))));
        assertTrue(of(store, "one-person").isEmpty());
    }

    /**
     * e-owner-4: an older Geni read wrote names in characters given name first, 勇 森田, and its key 勇森田 never meets 森田勇. The two are
     * asked about as one person, quoting both; nothing is joined until the family answers, and the key of neither changes.
     */
    @Test
    void aNameInCharactersGivenNameFirstBesideTheSameNameFamilyNameFirstIsAskedAbout(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "https://www.geni.com/people/Isamu-Morita/6000000000003", List.of(fact("勇 森田", "born-in", "広島県安芸郡", "1870", "勇 森田, born 1870 in 広島県安芸郡")), List.of());
        file(store, "file:///family/book.txt", List.of(fact("森田勇", "member-of", "森田家", "", "森田勇, the head of the 森田 family")), List.of());
        List<FamilyNameQuestions.Question> qs = of(store, "one-person");
        assertEquals(1, qs.size(), FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList().toString());
        FamilyNameQuestions.Question q = qs.get(0);
        assertTrue(q.text().startsWith("“勇 森田” and “森田勇” are one name written in two orders:") && q.text().endsWith("Are they one person?"), q.text());
        assertTrue(q.options().get(0).does().startsWith("joins “勇 森田” into “森田勇”"), q.options().get(0).does());
        Graph g = FamilyPeople.view(store);
        assertNotEquals(g.nodeIdOf("勇 森田"), g.nodeIdOf("森田勇"), "nothing is joined before the family answers");
        assertEquals(Set.of("勇森田"), FamilyNames.keys("勇 森田"), "the key stays as it was");
        FamilyNameQuestions.answer(store, q.code(), "one", "", "Ann");
        g = FamilyPeople.view(store);
        assertEquals(g.nodeIdOf("勇 森田"), g.nodeIdOf("森田勇"), "joined on the family's answer");

        // a name given name first beside a name that is not the same name is no question
        LibraryStore other = store(tmp.resolve("other"));
        file(other, "https://www.geni.com/people/Isamu-Morita/6000000000003", List.of(fact("勇 森田", "born-in", "広島県安芸郡", "1870", "勇 森田, born 1870 in 広島県安芸郡")), List.of());
        file(other, "file:///family/book.txt", List.of(fact("森田勝", "member-of", "森田家", "", "森田勝, the head of the 森田 family")), List.of());
        assertTrue(of(other, "one-person").isEmpty(), of(other, "one-person").toString());
    }

    /**
     * f-final-3: a book read first writes 森田 勇, family name first with a space, and an older Geni read writes 勇 森田. The question says
     * which one writes the given name first by the family names the library knows, whichever was filed first, and "one person" keeps the
     * name written family name first.
     */
    @Test
    void theNameWrittenGivenNameFirstIsToldByTheFamilyNamesNotByWhichWasFiledFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(fact("森田 勇", "member-of", "森田家", "", "森田 勇, the head of the 森田 family")), List.of());
        file(store, "https://www.geni.com/people/Isamu-Morita/6000000000003", List.of(fact("勇 森田", "born-in", "広島県安芸郡", "1870", "勇 森田, born 1870 in 広島県安芸郡")), List.of());
        List<FamilyNameQuestions.Question> qs = of(store, "one-person");
        assertEquals(1, qs.size(), qs.toString());
        FamilyNameQuestions.Question q = qs.get(0);
        assertTrue(q.text().contains("“勇 森田” writes the given name first"), q.text());
        assertTrue(q.options().get(0).does().startsWith("joins “勇 森田” into “森田 勇”"), q.options().get(0).does());
        FamilyNameQuestions.answer(store, q.code(), "one", "", "Ann");
        Graph g = FamilyPeople.view(store);
        assertEquals("森田 勇", g.node(g.nodeIdOf("勇 森田")).label(), "the name written family name first is kept");
    }

    /**
     * Round 3, open item 6: the family accepted a letter's plain adoption into the 森田 family beside a book's 婿養子, then answers the 婿養子
     * question "as 婿養子". The answer files the adoption as the family's word, of kind 婿養子, with the command that takes it back, and the
     * question how the name came is not asked again.
     */
    @Test
    void answering婿養子WhereAPlainAdoptionWasAcceptedFilesItAsTheFamilysWord(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String q = "In 1932 he entered the Morita family as mukoyōshi (婿養子) of Isamu.";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("森田健二", "adopted-by", "森田勇", "1932", q),
                        new FamilyAccount.Fact("森田勇", "member-of", "森田家", "", q, Map.of("role", "head"))), List.of(), List.of(),
                        List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。")), List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q))),
                "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        String q2 = "Kenji was adopted into the Morita family (森田家) in 1932.";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q2, Map.of("how", "adoption"))), List.of(), List.of(),
                        List.of(), List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q2))), "an aunt", f -> List.of("file:///family/letter.txt"), f -> List.of());
        Finding letter = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("member-of")
                && f.sources().stream().anyMatch(s -> s.locator().contains("letter.txt"))).findFirst().orElseThrow();
        new Council(store).accept(letter.id());
        String kenji = FamilyPeople.view(store).nodeIdOf("森田健二");
        FamilyNameQuestions.Question halves = of(store, "name-change-how").stream().filter(x -> x.text().contains("as 婿養子 (adopted and married)")).findFirst().orElseThrow();
        String said = FamilyNameQuestions.answer(store, halves.code(), "mukoyoshi", "", "Ann");
        Finding word = claim(store, "森田健二", "adopted-by");
        theFamilysWord(word, halves.code());
        assertEquals("mukoyoshi", FamilyDetail.get(word, "kind"), said);
        assertTrue(said.startsWith("Saved as your answer: ") && said.endsWith("To take it back: researchzosho genealogy who --reopen " + halves.code()), said);
        assertTrue(halves.options().get(0).does().startsWith("saves as your answer: 森田健二 was adopted by 森田勇 as 婿養子"), halves.options().get(0).does());
        FamilyNameHistory.Name later = FamilyNameHistory.of(FamilyPeople.view(store)).names(kenji).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertEquals("mukoyoshi", later.kind(), later.toString());
        assertTrue(of(store, "name-change-how").stream().noneMatch(x -> x.people().contains(kenji)), of(store, "name-change-how").stream().map(FamilyNameQuestions.Question::text).toList().toString());
        // taken back: the claim is retired and both questions wait again
        FamilyNameQuestions.reopen(store, halves.code());
        assertNotEquals(Finding.State.accepted, store.finding(word.id()).state());
        assertTrue(of(store, "name-change-how").stream().anyMatch(x -> x.code().equals(halves.code())), of(store, "name-change-how").toString());
    }

    @Test
    void oneGivenNameUnderTwoFamilyNamesIsAskedOnlyWhenSomethingAgreesAndNothingDiffers(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/letters.txt", List.of(
                fact("Kenji Endo", "child-of", "Shoichi Endo", "", "Kenji Endo, son of Shoichi Endo"),
                fact("Kenji Morita", "child-of", "Shoichi Endo", "", "Kenji Morita, who was a son of Shoichi Endo")), List.of());
        List<FamilyNameQuestions.Question> qs = of(store, "one-person");
        assertEquals(1, qs.size(), qs.toString());
        assertTrue(qs.get(0).text().startsWith("“Kenji Endo” and “Kenji Morita” carry one given name under two family names. What else agrees: both are children of Shoichi Endo."), qs.get(0).text());

        LibraryStore apart = store(tmp.resolve("apart"));
        file(apart, "file:///family/letters.txt", List.of(
                fact("Kenji Endo", "child-of", "Shoichi Endo", "", "Kenji Endo, son of Shoichi Endo"), fact("Kenji Endo", "born-on", "1905", "", "Kenji Endo, born 1905"),
                fact("Kenji Morita", "child-of", "Shoichi Endo", "", "Kenji Morita, who was a son of Shoichi Endo"), fact("Kenji Morita", "born-on", "1931", "", "Kenji Morita, born 1931")), List.of());
        assertTrue(of(apart, "one-person").isEmpty(), "two birth years: two people");

        LibraryStore nothing = store(tmp.resolve("nothing"));
        file(nothing, "file:///family/letters.txt", List.of(fact("Kenji Endo", "lived-in", "Kure", "1930", "Kenji Endo lived in Kure"), fact("Kenji Morita", "lived-in", "Leeds", "1950", "Kenji Morita lived in Leeds")), List.of());
        assertTrue(of(nothing, "one-person").isEmpty(), "nothing else agrees: never by the names alone");
    }

    @Test
    void aReadingASourceGaveLinksTwoEntriesAndAGivenNameAloneFindsItsOneWholeName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of())),
                List.of(fact("森田健二", "lived-in", "広島", "1935", "森田健二（もりた けんじ）は広島に住んだ。")), List.of()), "file:///family/register.txt", "an aunt");
        file(store, "file:///family/letter.txt", List.of(fact("Morita Kenji", "lived-in", "Hiroshima", "1936", "Morita Kenji lived in Hiroshima.")), List.of());
        List<FamilyNameQuestions.Question> qs = of(store, "one-person");
        assertEquals(1, qs.size(), qs.toString());
        assertTrue(qs.get(0).text().startsWith("もりた けんじ is the reading of 森田健二's name that register.txt gives, and in Latin letters it is Morita Kenji, the name of another person in your library, “Morita Kenji”."), qs.get(0).text());

        // no reading: characters never meet Latin letters by a guess
        LibraryStore guess = store(tmp.resolve("guess"));
        file(guess, "file:///family/register.txt", List.of(fact("森田健二", "lived-in", "広島", "1935", "森田健二は広島に住んだ。")), List.of());
        file(guess, "file:///family/letter.txt", List.of(fact("Morita Kenji", "lived-in", "Hiroshima", "1936", "Morita Kenji lived in Hiroshima.")), List.of());
        assertTrue(of(guess, "one-person").isEmpty());

        // 健二 alone, and 森田健二 the one whole name with that given name
        LibraryStore given = store(tmp.resolve("given"));
        file(given, "file:///family/notes.txt", List.of(fact("健二", "lived-in", "広島", "1935", "健二は広島に住んだ。"), fact("森田健二", "occupation", "shopkeeper", "", "森田健二は店を営んだ。")),
                List.of(name("森田健二", "森田健二", "森田", "健二", "unknown", "", "森田健二は店を営んだ。")));
        // the same text names 森田健二 in full: genealogy's view links 健二 to him ({@link FamilyLinks}), so nothing is asked
        Finding hiroshima = given.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals("健二")).findFirst().orElseThrow();
        assertEquals(FamilyPeople.view(given).nodeIdOf("森田健二"), FamilyPeople.view(given).nodeOf(hiroshima, true));
        assertTrue(of(given, "one-person").isEmpty(), of(given, "one-person").toString());
        // another text that writes 健二 alone and names nobody in full: the family is asked
        file(given, "file:///family/letter.txt", List.of(fact("健二", "occupation", "shopkeeper", "", "健二は店を営んだ。")), List.of());
        List<FamilyNameQuestions.Question> one = of(given, "one-person");
        assertEquals(1, one.size(), one.toString());
        assertTrue(one.get(0).text().startsWith("“健二” is written with a given name alone, and 森田健二 is the only person in your library whose given name is 健二."), one.get(0).text());
        assertTrue(one.get(0).options().get(0).does().startsWith("joins “健二” into “森田健二”"), "the given name alone goes into the whole name");
        // a second whole name with that given name: nothing is asked
        file(given, "file:///family/notes2.txt", List.of(fact("遠藤健二", "occupation", "teacher", "", "遠藤健二は教師だった。")), List.of(name("遠藤健二", "遠藤健二", "遠藤", "健二", "unknown", "", "遠藤健二は教師だった。")));
        assertTrue(of(given, "one-person").stream().noneMatch(q -> q.text().contains("given name alone")), FamilyNameQuestions.open(given).toString());
    }

    @Test
    void howAndWhenANameChangedAreAskedAndTheAnswersAreTheFamilysWord(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String q1 = "森田健二は1905年に遠藤健二として生まれた。";
        // he entered the 森田 family, and the words do not say how: more than a marriage may lie behind his name, so how it came is asked
        file(store, "file:///family/book.txt", List.of(fact("森田健二", "married-to", "森田ハル", "", "森田健二は森田ハルと結婚した。"), fact("森田ハル", "child-of", "森田勇", "", "勇の娘ハル"),
                        fact("森田健二", "sex", "male", "", "森田健二は男"), fact("森田健二", "member-of", "森田家", "", "健二は森田家に入った。")),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", q1), name("森田ハル", "森田ハル", "森田", "ハル", "birth", "", "勇の娘ハル")));
        Graph g = FamilyPeople.view(store);
        String kenji = g.nodeIdOf("森田健二");
        List<FamilyNameQuestions.Question> how = of(store, "name-change-how");
        assertEquals(1, how.size(), how.toString());
        FamilyNameQuestions.Question q = how.get(0);
        assertEquals(List.of(kenji), q.people());
        assertTrue(q.text().contains("森田健二 was married to 森田ハル, whose family name was 森田.") && q.text().endsWith("How did 森田健二 get the name 森田健二?"), q.text());
        List<String> keys = q.options().stream().map(FamilyNameQuestions.Option::key).toList();
        assertEquals(List.of("mukoyoshi", "nyufu", "adoptive", "marriage"), keys.subList(0, 4), "a husband whose later family part is his wife's: 婿養子, 入夫, an adoption, only the name at the marriage: " + keys);
        assertTrue(of(store, "name-change-when").isEmpty(), "how first");

        // took his wife's name without an adoption: the name, the family he entered, and her parent is his parent-in-law, never his adoptive parent
        FamilyNameQuestions.answer(store, q.code(), "marriage", "", "Ann");
        Finding named = claim(store, "森田健二", "has-name");
        theFamilysWord(named, q.code());
        assertEquals("marriage", FamilyDetail.get(named, "kind"));
        assertEquals("name: 森田健二", named.triple().object());
        theFamilysWord(claim(store, "森田健二", "member-of"), q.code());
        theFamilysWord(claim(store, "森田勇", "parent-in-law-of"), q.code());
        assertNull(claim(store, "森田健二", "adopted-by"));
        assertTrue(of(store, "name-change-how").isEmpty(), "answered: " + of(store, "name-change-how"));
        FamilyNameHistory.Name latest = FamilyNameHistory.of(FamilyPeople.view(store)).latest(kenji);
        assertEquals("marriage", latest.kind());

        // the name came with the marriage, whose date nobody gives: that is the marriage's date, no question about the name, for a husband
        // as for a wife
        assertTrue(of(store, "name-change-when").isEmpty(), FamilyNameQuestions.open(store).toString());

        // disputing the answer brings the question of how back
        new Council(store).dispute(named.id(), "we are not sure after all");
        assertEquals(1, of(store, "name-change-how").size(), FamilyNameQuestions.open(store).toString());

        // a name a source says came as 婿養子, with no year: when is asked, and a year typed is the answer
        LibraryStore undated = store(tmp.resolve("undated"));
        file(undated, "file:///family/book.txt", List.of(), List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", q1),
                name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "", "健二は森田家の婿養子となった。")));
        String kenji2 = FamilyPeople.view(undated).nodeIdOf("森田健二");
        List<FamilyNameQuestions.Question> when = of(undated, "name-change-when");
        assertEquals(1, when.size(), FamilyNameQuestions.open(undated).toString());
        assertTrue(when.get(0).text().endsWith("In which year did 森田健二 take the name 森田健二?"), when.get(0).text());
        assertThrows(IllegalArgumentException.class, () -> FamilyNameQuestions.answer(undated, when.get(0).code(), "year", "", "Ann"), "a year is needed");
        FamilyNameQuestions.answer(undated, when.get(0).code(), "1932", "", "Ann");   // a year typed is the answer
        FamilyNameHistory.Index idx = FamilyNameHistory.of(FamilyPeople.view(undated));
        assertEquals("遠藤健二", idx.at(kenji2, 1920).written());
        assertEquals("森田健二", idx.at(kenji2, 1933).written());
        assertEquals("森田健二 (born 遠藤)", idx.heading(kenji2));
        assertTrue(of(undated, "name-change-when").isEmpty());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FamilyNameQuestions.cliAnswered(store, new PrintStream(out, true, StandardCharsets.UTF_8));
        String listed = out.toString(StandardCharsets.UTF_8);
        assertTrue(listed.contains("[code " + q.code()) && listed.contains("researchzosho dispute " + named.id().replaceFirst("^(F-\\d+).*", "$1")) && listed.contains("researchzosho genealogy who --reopen " + q.code()), listed);
    }

    @Test
    void aMukoyoshiAdoptionWithoutItsHalvesIsAskedAndTheAnswerFilesTheFamilyHeEntered(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(fact("森田健二", "adopted-by", "森田勇", "1932", "In 1932 森田健二 was adopted by 森田勇 as 婿養子.")),
                List.of(name("森田勇", "森田勇", "森田", "勇", "birth", "", "森田勇")));
        List<FamilyNameQuestions.Question> qs = of(store, "name-change-how");
        assertEquals(1, qs.size(), qs.toString());
        FamilyNameQuestions.Question q = qs.get(0);
        // his name carries 森田, the adopter's family part: the adoption is his entry into the family, and only the marriage is missing
        assertTrue(q.text().contains("森田健二 was adopted by 森田勇 as 婿養子 (adopted and married). The library has no fact that says 森田健二 married into the family. How did"), q.text());
        FamilyNameQuestions.answer(store, q.code(), "mukoyoshi", "", "Ann");
        Finding member = claim(store, "森田健二", "member-of");
        theFamilysWord(member, q.code());
        assertEquals("mukoyoshi", FamilyDetail.get(member, "how"));
        assertEquals("1932", FamilyDetail.get(member, "from"));
        assertTrue(of(store, "name-change-how").isEmpty(), "answered; the marriage waits for a source that names his wife");

        // an ordinary adoption after all: the family's kind wins, and nothing is asked
        LibraryStore other = store(tmp.resolve("other"));
        file(other, "file:///family/book.txt", List.of(fact("森田健二", "adopted-by", "森田勇", "1932", "In 1932 森田健二 was adopted by 森田勇 as 婿養子.")), List.of());
        FamilyNameQuestions.Question o = of(other, "name-change-how").get(0);
        FamilyNameQuestions.answer(other, o.code(), "adoptive", "", "Ann");
        assertEquals("ordinary", FamilyDetail.get(claim(other, "森田健二", "adopted-by"), "kind"));
        new Council(other).dispute(claim(other, "森田健二", "adopted-by").id(), "not so");
        assertEquals(1, of(other, "name-change-how").size(), "disputed: asked again");
    }

    @Test
    void aParentOfAnotherFamilyThanTheChildsBirthNameIsAskedAboutAndAnAdoptiveAnswerIsTyped(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(fact("森田健二", "child-of", "森田勇", "", "勇の子 健二"), fact("森田勇", "born-on", "1870", "", "勇 明治三年生"),
                        fact("森田健二", "born-on", "1905", "", "健二 1905年生")),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は遠藤家に生まれた"), name("森田勇", "森田勇", "森田", "勇", "birth", "1870", "勇 明治三年生")));
        List<FamilyNameQuestions.Question> qs = of(store, "birth-or-adoptive");
        assertEquals(1, qs.size(), qs.toString());
        FamilyNameQuestions.Question q = qs.get(0);
        assertTrue(q.text().contains("was born 遠藤健二 in 1905, and 森田勇 carried the family name 森田 then."), q.text());
        assertEquals(List.of("birth", "adoptive", "step", "foster", "in-law", "later"), q.options().stream().map(FamilyNameQuestions.Option::key).toList());
        Finding childOf = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("child-of")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, q.code(), "adoptive", "", "Ann");
        assertEquals(Finding.State.disputed, store.finding(childOf.id()).state(), "the claim as written stays on record, disputed with the family's reason");
        theFamilysWord(claim(store, "森田健二", "adopted-by"), q.code());
        assertTrue(of(store, "birth-or-adoptive").isEmpty());

        // the same parent of the child's own family is no question
        LibraryStore same = store(tmp.resolve("same"));
        file(same, "file:///family/book.txt", List.of(fact("遠藤健二", "child-of", "遠藤正一", "", "正一の子 健二"), fact("遠藤正一", "born-on", "1875", "", "正一 1875年生"), fact("遠藤健二", "born-on", "1905", "", "健二 1905年生")),
                List.of(name("遠藤健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二"), name("遠藤正一", "遠藤正一", "遠藤", "正一", "birth", "1875", "正一")));
        assertTrue(of(same, "birth-or-adoptive").isEmpty());
    }

    @Test
    void aFamilyWithNoSeatBesideOthersOfItsNameIsAskedAboutAndKeptApartOnTheAnswer(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(fact("遠藤正一", "lived-in", "広島", "1900", "遠藤正一は広島に住んだ。")), List.of());
        FamilyHouses.family(store, FamilyPeople.view(store), "遠藤", "広島県安芸郡", "");
        FamilyHouses.family(store, FamilyPeople.view(store), "遠藤", "山口県", "");
        String third = FamilyHouses.family(store, FamilyPeople.view(store), "遠藤", "", "遠藤正一");
        assertEquals("遠藤 family (of 遠藤正一)", third);
        // the family was made for its first member, whose membership the read files beside it
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("遠藤正一", "member-of", third, "", "遠藤正一は遠藤家の人。")), List.of()), "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        List<FamilyNameQuestions.Question> qs = of(store, "which-family");
        assertEquals(1, qs.size(), "only the family with no seat is in question: " + qs);
        assertEquals(List.of("f1", "f2", "own", "later"), qs.get(0).options().stream().map(FamilyNameQuestions.Option::key).toList());
        FamilyNameQuestions.answer(store, qs.get(0).code(), "own", "", "Ann");
        assertTrue(of(store, "which-family").isEmpty());
    }

    @Test
    void aRecordUnderANameNotCarriedThenIsKeptAndAsked(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。"), name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", "1932年、森田家に婿養子として入った。")));
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("森田健二", "lived-in", "広島", "1920", "森田健二 広島 1920")), List.of()), "a register", f -> List.of("cite:register of 1920, p. 4"), f -> List.of());
        List<FamilyNameQuestions.Question> qs = of(store, "name-at-date");
        assertEquals(1, qs.size(), FamilyNameQuestions.open(store).toString());
        assertTrue(qs.get(0).text().contains("writes 森田健二 (born 遠藤) as “森田健二”. By the names in your library, 森田健二 carried the name 遠藤健二 from 1905 to 1932, and 森田健二 from 1932."), qs.get(0).text());
        assertEquals(List.of("written-later", "year", "another", "later"), qs.get(0).options().stream().map(FamilyNameQuestions.Option::key).toList());
        assertTrue(FamilyChecks.check(store).stream().anyMatch(p -> p.kind().equals("name-at-date")), "the check lists it too");
        FamilyNameQuestions.answer(store, qs.get(0).code(), "written-later", "", "Ann");
        assertTrue(of(store, "name-at-date").isEmpty());
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.notes().stream().anyMatch(n -> n.kind().equals(FamilyNameQuestions.NOTE))), "a note on the record");

        // a book's narrative (a clue) is never such a record
        LibraryStore book = store(tmp.resolve("book"));
        file(book, "file:///family/book.txt", List.of(fact("森田健二", "lived-in", "広島", "1920", "Morita Kenji went to the village school in 1920.")),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。"), name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", "1932年、森田家に婿養子として入った。")));
        assertTrue(of(book, "name-at-date").isEmpty());
    }

    @Test
    void aNameReadTwoWaysIsAskedAndTheChosenReadingSettlesItHereAndInTheCheck(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of())), List.of(fact("森田健二", "lived-in", "広島", "1935", "森田健二は広島に住んだ。")), List.of()), "file:///family/a.txt", "an aunt");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりだ けんじ", List.of())), List.of(fact("森田健二", "occupation", "shopkeeper", "", "森田健二は店を営んだ。")), List.of()), "file:///family/b.txt", "an uncle");
        List<FamilyNameQuestions.Question> qs = of(store, "reading");
        assertEquals(1, qs.size());
        assertTrue(qs.get(0).text().contains("もりた けんじ (a.txt); もりだ けんじ (b.txt)"), qs.get(0).text());
        assertTrue(FamilyChecks.check(store).stream().anyMatch(p -> p.kind().equals("read-two-ways")));
        FamilyNameQuestions.answer(store, qs.get(0).code(), "r1", "", "Ann");
        assertTrue(of(store, "reading").isEmpty());
        assertTrue(FamilyChecks.check(store).stream().noneMatch(p -> p.kind().equals("read-two-ways")), "the family said which");
        assertEquals("もりた けんじ", FamilyNameHistory.formsOf(FamilyDetail.get(claim(store, "森田健二", "has-name"), "forms")).get(1).text());
    }

    @Test
    void laterPutsAQuestionOffUntilReopenedAndAnAnswerThatIsNotOfferedIsRefusedInASentence(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(), List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。")));
        FamilyNameQuestions.Question q = of(store, "name-change-how").get(0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FamilyNameQuestions.answer(store, q.code(), "perhaps", "", "Ann"));
        assertTrue(e.getMessage().startsWith("\"perhaps\" is not one of the answers to this question. The answers are: 1 (marriage), 2 (mukoyoshi)"), e.getMessage());
        assertTrue(FamilyNameQuestions.answer(store, q.code(), "later", "", "Ann").contains("researchzosho genealogy who --reopen " + q.code()));
        assertTrue(FamilyNameQuestions.open(store).isEmpty());
        assertEquals("later", FamilyNameQuestions.asked(store).get(q.code())[0]);
        assertTrue(FamilyNameQuestions.reopen(store, q.code()).open());
        assertEquals(1, FamilyNameQuestions.open(store).size());
        assertThrows(IllegalArgumentException.class, () -> FamilyNameQuestions.answer(store, "ffffff", "1", "", "Ann"));
        assertTrue(Files.readString(FamilyNameQuestions.askedFile(store)).lines().allMatch(l -> l.split("\t", -1).length >= 2));
    }
}
