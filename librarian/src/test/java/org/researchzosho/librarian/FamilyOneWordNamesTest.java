package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A family name or a given name written alone is shared by strangers. In the family's work it leads no claim to a person through another
 * name of theirs, and a family's answer about a name written alone is about the words the question quotes: it moves their claims, never
 * every claim of every source written with that one word.
 */
class FamilyOneWordNamesTest {

    @AfterEach void restore() { FamilyNameQuestions.candidatesForTests = null; }

    /** A claim of the family's own reading, as a read files it: written by family-account from a file of the family, with the file's words. */
    static String told(LibraryStore store, String file, String s, String p, String o, String quote) throws Exception {
        Files.createDirectories(store.root().resolve("family"));
        String id = store.nextFindingId(s + " " + p + " " + o);
        String sentence = s + " " + p + " " + o + ".";
        store.write(new Finding(id, sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.low, "family-account",
                Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("file:///family/" + file, "as told by the writer of " + file, "the family's own account")), List.of(), null,
                sentence + "\n\nThe account says: \"" + quote + "\"\n", new Finding.Triple(s, p, o), List.of()));
        return id;
    }

    /** The claims whose edges touch a node, by their ids. */
    static Set<String> claimsOf(Graph g, String id) {
        Set<String> out = new TreeSet<>();
        for (Graph.Edge e : g.edges()) if ((e.from().equals(id) || e.to().equals(id)) && !e.predicate().equals("is filed under") && !e.predicate().equals("mentions")) out.add(e.findingId());
        return out;
    }

    // ── a name written alone finds nobody through another name ──

    /**
     * f-owner-one-word-other-names: a book's index gave a person the other name "Hart, Tom", which the list of names read back as "Hart" and
     * "Tom", and every claim of every family source written "Hart" then landed on that one person.
     */
    @Test
    void aFamilyNameOrAGivenNameAloneThatAPersonCarriesLeadsNoClaimOfTheFamilyToThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String born = told(store, "letters.txt", "Tom Hart", "born-in", "Leeds", "Tom Hart was born in Leeds in 1850.");
        String sex = told(store, "letters.txt", "Tom Hart", "sex", "male", "Tom Hart, a cooper, was born in Leeds.");
        // the other names as older versions left them: the index's "Hart, Tom" read back as two, and a title with the family name
        Graph.alias(store, "Tom Hart", List.of("Hart", "Tom", "Mr. Hart", "Thomas Hart"));
        String mill = told(store, "mill-book.txt", "Hart", "occupation", "miller", "Hart owned a mill near Leeds in 1900.");
        String port = told(store, "port-book.txt", "Hart", "married-to", "Ruth Hale", "Hart married Ruth Hale in York in 1899.");
        String shop = told(store, "port-book.txt", "Mr. Hart", "occupation", "baker", "Mr. Hart kept a bakery in Hull in 1905.");
        String york = told(store, "port-book.txt", "Tom", "lived-in", "York", "Tom lived in York in 1920.");
        String died = told(store, "letters.txt", "Thomas Hart", "died-in", "Leeds", "Thomas Hart died in Leeds in 1911.");
        for (Graph g : List.of(FamilyPeople.view(store), Graph.build(store))) {
            String tom = g.nodeIdOf("Tom Hart");
            assertEquals(new TreeSet<>(List.of(born, sex, died)), claimsOf(g, tom), "his own claims, and the one of his whole other name");
            for (String other : List.of(mill, port, shop, york)) assertFalse(claimsOf(g, tom).contains(other), other);
        }
        assertNotEquals(FamilyPeople.view(store).nodeIdOf("Tom Hart"), FamilyPeople.view(store).nodeIdOf("Hart"), "Hart alone is somebody the family is asked about");
    }

    /** Japanese names, family name first: a family name or a given name alone is a part of the whole name, not a word of it. */
    @Test
    void inCharactersAFamilyNameOrAGivenNameAloneIsAPartOfTheWholeName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String born = told(store, "register.txt", "遠藤健二", "born-on", "1905", "遠藤健二、1905年生。");
        Graph.alias(store, "遠藤健二", List.of("遠藤", "健二", "Endō Kenji", "遠藤 健二"));
        String family = told(store, "notes.txt", "遠藤", "lived-in", "広島", "遠藤は1930年に広島に住んでいた。");
        String given = told(store, "notes.txt", "健二", "occupation", "教師", "健二は教師だった。");
        String latin = told(store, "letter.txt", "Endō Kenji", "lived-in", "東京", "Endō Kenji lived in Tokyo in 1940.");
        String spaced = told(store, "letter.txt", "遠藤 健二", "died-on", "1970", "遠藤 健二、1970年没。");
        // a person filed under one word keeps it, and their whole name leads to them
        String isamu = told(store, "temple.txt", "勇", "born-on", "1880", "勇、1880年生。");
        Graph.alias(store, "勇", List.of("森田勇"));
        String whole = told(store, "temple.txt", "森田勇", "died-on", "1940", "森田勇、1940年没。");
        for (Graph g : List.of(FamilyPeople.view(store), Graph.build(store))) {
            assertEquals(new TreeSet<>(List.of(born, latin, spaced)), claimsOf(g, g.nodeIdOf("遠藤健二")), "not " + family + " or " + given);
            assertEquals(new TreeSet<>(List.of(isamu, whole)), claimsOf(g, g.nodeIdOf("勇")));
        }
    }

    /** An ordinary library reads another name of one word as it always did, with genealogy on and off; in a library with family work too, so does an ordinary claim. */
    @Test
    void anOrdinaryClaimReadsAnotherNameOfOneWordAsItAlwaysDid(@TempDir Path tmp) throws Exception {
        List<String> seen = new ArrayList<>();
        for (boolean on : new boolean[]{false, true}) {
            LibraryStore store = new LibraryStore(tmp.resolve(on ? "on" : "off"));
            store.init();
            if (!on) Profiles.disable(store, "genealogy");
            OtherNamesWithACommaTest.claim(store, "F-0001-a", "Mary Hale", "wrote", "a history of Leeds");
            OtherNamesWithACommaTest.claim(store, "F-0002-b", "Hale", "founded", "the Leeds Library");
            Graph.setKind(store, "Mary Hale", "person");
            Graph.alias(store, "Mary Hale", List.of("Hale"));
            Graph g = Graph.build(store);
            assertEquals("mary hale", g.nodeIdOf("Hale"));
            List<String> edges = new ArrayList<>();
            for (Graph.Edge e : g.edges()) edges.add(e.from() + " -" + e.predicate() + "-> " + e.to());
            edges.sort(null);
            seen.add(String.join("\n", edges));
        }
        assertEquals(seen.get(0), seen.get(1), "genealogy on changes nothing in an ordinary library");
        assertTrue(seen.get(1).contains("mary hale -founded-> the leeds library"), seen.get(1));

        LibraryStore mixed = new LibraryStore(tmp.resolve("mixed"));
        mixed.init();
        OtherNamesWithACommaTest.claim(mixed, "F-0001-a", "Mary Hale", "wrote", "a history of Leeds");
        OtherNamesWithACommaTest.claim(mixed, "F-0002-b", "Hale", "founded", "the Leeds Library");
        Graph.setKind(mixed, "Mary Hale", "person");
        Graph.alias(mixed, "Mary Hale", List.of("Hale"));
        String lived = told(mixed, "book.txt", "Hale", "lived-in", "York", "Hale lived in York in 1890.");
        Graph core = Graph.build(mixed);
        assertTrue(core.edges().stream().anyMatch(e -> e.findingId().equals("F-0002-b") && e.from().equals("mary hale")), "the ordinary claim: " + core.edges());
        assertTrue(core.edges().stream().anyMatch(e -> e.findingId().equals(lived) && e.from().equals("hale")), "the family's claim: " + core.edges());
    }

    // ── an answer about a name written alone moves the claims of the words it quotes ──

    /**
     * f-owner-family-name-alone-answer-joined-every-source: the answer "somebody of the Hart family whom the source does not name" joined the
     * entry "Hart" into the described person, so every claim of every source written "Hart" landed on that one person. The answer moves the
     * claims of the words the question quotes, which are one source's words written one way ("Hart" in port-book.txt); the entry keeps the
     * other sources' words, and taking the answer back restores it.
     */
    @Test
    void theFamilyAnswerMovesOnlyTheQuotedWordsClaimsAndTakingItBackRestoresThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        told(store, "letters.txt", "Tom Hart", "born-in", "Leeds", "Tom Hart was born in Leeds in 1850.");
        told(store, "letters.txt", "Ruth Hale", "sex", "female", "Ruth Hale, a teacher.");
        String married = told(store, "port-book.txt", "Hart", "married-to", "Ruth Hale", "Hart married Ruth Hale in York in 1899.");
        String male = told(store, "port-book.txt", "Hart", "sex", "male", "Hart married Ruth Hale in York in 1899.");
        String works = told(store, "port-book.txt", "Hart", "occupation", "baker", "Hart kept a bakery in Hull in 1905.");
        String mill = told(store, "mill-book.txt", "Hart", "lived-in", "Leeds", "The Hart family's farm lay near Leeds, and Hart worked it.");
        Set<String> before = new TreeSet<>(Vocabulary.read(Graph.nodesFile(store)).terms().keySet());
        String entry = FamilyPeople.view(store).nodeIdOf("Hart");
        List<FamilyNameQuestions.Question> asked = FamilyNameQuestions.open(store);
        FamilyNameQuestions.Question q = asked.stream().filter(x -> x.kind().equals("family-name-alone") && x.people().contains(entry)).findFirst()
                .orElseThrow(() -> new AssertionError(asked.stream().map(FamilyNameQuestions.Question::text).toList().toString()));
        // one question for what port-book.txt writes as "Hart": it quotes the words that write the relative first, and the bakery is the same book's
        assertTrue(q.text().contains("In port-book.txt, “Hart” is written in 2 passages: “Hart married Ruth Hale in York in 1899.” and “Hart kept a bakery in Hull in 1905.”"), "the question quotes the words that write the relative: " + q.text());
        String placeholder = "Ruth Hale's husband (written only as Hart)";
        FamilyNameQuestions.Option family = q.options().stream().filter(o -> o.key().equals("family")).findFirst().orElseThrow(() -> new AssertionError(q.options().toString()));

        String said = FamilyNameQuestions.answer(store, q.code(), "family", "", "Ann");
        Graph g = FamilyPeople.view(store);
        String described = g.nodeIdOf(placeholder), hart = g.nodeIdOf("Hart");
        assertTrue(claimsOf(g, described).containsAll(List.of(married, male, works)), "the words of port-book.txt are about the described person: " + said);
        assertFalse(claimsOf(g, described).contains(mill), "the farmer of another book is not the described person: " + claimsOf(g, described));
        assertEquals(new TreeSet<>(List.of(mill)), claimsOf(g, hart), "the entry of the family name alone keeps the other book's words");
        assertNotEquals(described, hart);
        assertTrue(Graph.merges(store).isEmpty(), "nothing is joined: " + Graph.merges(store));
        Vocabulary.Term t = g.curated().get(described);
        assertTrue(t == null || t.also().stream().noneMatch(a -> a.equalsIgnoreCase("Hart")), "the described person carries no family name alone: " + t);
        Finding moved = store.finding(married);
        assertEquals(placeholder, moved.triple().subject());
        assertTrue(moved.body().contains("The account says: \"Hart married Ruth Hale"), "the words stay the source's: " + moved.body());
        assertTrue(FamilyNameQuestions.open(store).stream().anyMatch(x -> x.kind().equals("family-name-alone") && x.people().contains(hart)), "the other book's words about Hart are asked about on their own");
        assertTrue(family.does().contains("“" + placeholder + "”") && family.does().contains("elsewhere in your sources stays as it is"), "the answer says what it does: " + family.does());

        FamilyNameQuestions.reopen(store, q.code());
        Graph back = FamilyPeople.view(store);
        assertEquals(new TreeSet<>(List.of(married, male, works, mill)), claimsOf(back, back.nodeIdOf("Hart")), "taken back, every claim is about Hart again");
        assertEquals("Hart", store.finding(married).triple().subject());
        assertEquals(before, new TreeSet<>(Vocabulary.read(Graph.nodesFile(store)).terms().keySet()), "the described person the answer made is gone");
        assertTrue(FamilyNameQuestions.open(store).stream().anyMatch(x -> x.code().equals(q.code())), "and the question is asked again");
    }

    /**
     * An older version answered by joining the entry "Hart" into the described person it made. Taking that join back by hand leaves the
     * described person with only the family's own answer, and no source's words: it is not asked about, and "Hart" is asked about again.
     */
    @Test
    void aDescribedPersonThatOnlyAnOlderAnswerMadeIsNotAskedAboutOnceItsJoinIsTakenBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        told(store, "letters.txt", "Tom Hart", "born-in", "Leeds", "Tom Hart was born in Leeds in 1850.");
        told(store, "letters.txt", "Ruth Hale", "sex", "female", "Ruth Hale, a teacher.");
        told(store, "port-book.txt", "Hart", "married-to", "Ruth Hale", "Hart married Ruth Hale in York in 1899.");
        told(store, "mill-book.txt", "Hart", "lived-in", "Leeds", "The Hart family's farm lay near Leeds, and Hart worked it.");
        String placeholder = "Ruth Hale's husband (written only as Hart)";
        // the older answer: a join, and the family's word that the described person is of the Hart family
        Graph.merge(store, "Hart", placeholder, "person", "the family's answer to the question abc123 about names");
        String id = store.nextFindingId(placeholder + " member-of Hart family");
        store.write(new Finding(id, placeholder + " belonged to Hart family.", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.low, "family-account",
                Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "", List.of(new Finding.Source(FamilyNameQuestions.SOURCE + "abc123", "as told by Ann", "the family's answer")),
                List.of(), null, placeholder + " belonged to Hart family.\n", new Finding.Triple(placeholder, FamilyHouses.MEMBER, "Hart family"), List.of()));
        Graph.unmerge(store, "Hart", "", "person", "by hand");
        Graph g = FamilyPeople.view(store);
        List<FamilyNameQuestions.Question> asked = FamilyNameQuestionsTest.of(store, "family-name-alone");
        assertTrue(asked.stream().noneMatch(q -> q.people().contains(g.nodeIdOf(placeholder))), "no source writes the described person: " + asked.stream().map(FamilyNameQuestions.Question::text).toList());
        assertTrue(asked.stream().anyMatch(q -> q.people().contains(g.nodeIdOf("Hart"))), asked.stream().map(FamilyNameQuestions.Question::text).toList().toString());
    }

    /** Japanese names, family name first: the answer that the name is a person the library holds moves the quoted words' claim to that person alone. */
    @Test
    void choosingAPersonForAFamilyNameAloneMovesTheQuotedWordsClaimAndLeavesAnotherSourcesAsItIs(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/register.txt", List.of(FamilyNameQuestionsTest.fact("遠藤正一", "born-on", "1875", "", "遠藤正一は1875年に生まれた。")),
                List.of(FamilyNameQuestionsTest.name("遠藤正一", "遠藤正一", "遠藤", "正一", "birth", "1875", "遠藤正一は1875年に生まれた。")));
        String child = told(store, "book-a.txt", "森田健二", "child-of", "遠藤", "遠藤の子、森田健二は1905年に生まれた。");
        String lived = told(store, "book-b.txt", "遠藤", "lived-in", "広島", "遠藤家の遠藤は1930年に広島に住んでいた。");
        FamilyNameQuestions.candidatesForTests = (graph, placeholder) -> placeholder.startsWith("森田健二's") ? List.of("遠藤正一") : List.of();
        List<FamilyNameQuestions.Question> asked = FamilyNameQuestions.open(store);
        FamilyNameQuestions.Question q = asked.stream().filter(x -> x.kind().equals("family-name-alone") && x.text().startsWith("“遠藤” is written in your library") && x.text().contains("In book-a.txt")).findFirst()
                .orElseThrow(() -> new AssertionError(asked.stream().map(FamilyNameQuestions.Question::text).toList().toString()));
        FamilyNameQuestions.Option c1 = q.options().stream().filter(o -> o.says().startsWith("遠藤正一")).findFirst().orElseThrow(() -> new AssertionError(q.options().toString()));
        FamilyNameQuestions.answer(store, q.code(), c1.key(), "", "Ann");
        Graph g = FamilyPeople.view(store);
        assertTrue(claimsOf(g, g.nodeIdOf("遠藤正一")).contains(child), "森田健二's parent in book-a is 遠藤正一");
        assertFalse(claimsOf(g, g.nodeIdOf("遠藤正一")).contains(lived), "book-b's 遠藤 is not joined to him: " + claimsOf(g, g.nodeIdOf("遠藤正一")));
        assertEquals(Set.of(lived), claimsOf(g, g.nodeIdOf("遠藤")));
    }
}
