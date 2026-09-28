package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.profiles.GenealogyProfile;
import org.researchzosho.tools.Fetch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Names over a life and families as things, end to end, with invented names only. A family book, a tree file and a Geni profile tell of
 * one man: born 遠藤健二 in 1905, 婿養子 into the 森田 family in 1932 and 森田健二 from then on; the book calls him "Endo's son, Morita
 * Kenji", writes him three ways (Morita Kenji, 森田健二（もりた けんじ）, Kenji) and writes his father only by the family name. Which person
 * "Kenji" alone is, the book does not say: the library leaves that to the family. The book
 * also tells of 森田正二, adopted as heir into the 髙橋 family in 1940, and of Kenji's grandson 森田勝, who became 髙橋勝 in 1990 without
 * saying how. The sources are read in this order: the book, the tree file, Geni.
 *
 * <p>The reader's model is scripted: it answers the book as the prompt asks (resources/names/book-read.json), and it answers every yes-or-no
 * check with no, so everything the library keeps from the book rests on the book's own words. Each assertion says the failure it guards.
 */
class FamilyNamesEndToEndTest {

    static final String GENI = "https://www.geni.com/people/Kenji-Morita/6000000000001";

    private String home;

    static String resource(String name) {
        try (InputStream in = FamilyNamesEndToEndTest.class.getResourceAsStream("/names/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @BeforeEach void sources() {
        String read = resource("book-read.json"), geni = resource("geni-kenji.json");
        GenealogyProfile.useReader(prompt -> prompt.endsWith("Answer with one word: yes or no.") ? "no" : read);
        GenealogyProfile.geniForTests = url -> new Fetch.Result(url, 200, geni.getBytes(StandardCharsets.UTF_8), "application/json");
        home = System.getProperty("user.home");
    }

    @AfterEach void restore() {
        GenealogyProfile.useReader(null);
        GenealogyProfile.geniForTests = null;
        Interaction.OVERRIDE = null;
        Interaction.INPUT = null;
        System.setProperty("user.home", home);
        Config.invalidate();
    }

    static LibraryStore library(Path dir) throws Exception {
        LibraryStore s = new LibraryStore(dir.resolve("lib")); s.init();
        new LibrarianIndex(s, Embeddings.none()).rebuild();
        return s;
    }

    static String readBook(LibraryStore store, Path dir) throws Exception {
        Path book = dir.resolve("book.txt");
        Files.writeString(book, resource("book.txt"), StandardCharsets.UTF_8);
        return FamilyNamesFilingTest.run(store, "read", book.toString(), "--by", "an aunt");
    }

    static String importTree(LibraryStore store, Path dir) throws Exception {
        Path ged = dir.resolve("tree.ged");
        Files.writeString(ged, resource("tree.ged"), StandardCharsets.UTF_8);
        return FamilyNamesFilingTest.run(store, "import", ged.toString());
    }

    static String readGeni(LibraryStore store) throws Exception { return FamilyNamesFilingTest.run(store, "read", GENI); }

    static List<Finding> claims(LibraryStore store, String predicate) throws Exception {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals(predicate) && f.state() != Finding.State.retired).toList();
    }

    static String code(Finding f) { return f.id().replaceFirst("^(F-\\d+).*", "$1"); }

    static FamilyHouses.Membership membership(Graph g, String person, String family) {
        return FamilyHouses.families(g, g.nodeIdOf(person)).stream().filter(m -> FamilyHouses.labelOf(g, m.family()).equals(family)).findFirst().orElse(null);
    }

    static FamilyNameHistory.Name name(FamilyNameHistory.Index idx, String id, String written) {
        return idx.names(id).stream().filter(n -> n.written().equals(written)).findFirst().orElse(null);
    }

    @Test
    void aBookATreeFileAndGeniGiveOneManWithTwoNamesHisFamiliesAndTheFatherTheBookNamesOnlyByTheFamilyName(@TempDir Path tmp) throws Exception {
        Interaction.OVERRIDE = false;
        LibraryStore store = library(tmp);
        String read = readBook(store, tmp);
        assertTrue(read.contains("One person is written in this text only by a family name"), "the read says it kept \"Endo\" as somebody described, not as a person named Endo: " + read);
        // the tree's record gives his birth name first and 森田健二 second, and his wife and the man who adopted him by the names the book
        // gives them, with no birth year the book gives too: a name is no second fact, so the tree's people are entries of their own until the
        // family says who is who, and the family is asked
        String imported = importTree(store, tmp);
        assertTrue(imported.contains("遠藤健二 in the file also has the name 森田健二, which is the name of another entry in your library."), "the tree's record is kept apart, and the import says so: " + imported);
        Graph kept = FamilyPeople.view(store);
        // the import says the library will ask about each record it kept apart, the one in the other word order (Isamu Morita) too
        for (String[] two : new String[][]{{"遠藤健二", "森田健二"}, {"森田勇", "Morita Isamu"}, {"森田ハル", "Morita Haru"}}) {
            List<String> pair = List.of(kept.nodeIdOf(two[0]), kept.nodeIdOf(two[1]));
            assertTrue(FamilyNameQuestions.open(store).stream().anyMatch(q -> q.kind().equals("one-person") && q.people().containsAll(pair)), "the family is asked whether " + two[0] + " and " + two[1] + " are one person: " + FamilyNameQuestions.open(store));
        }
        // the family says each is one person, under the names the book gives them (researchzosho graph merge), and the next read lets the
        // tree's record of his father settle who the book's "Endo" is
        for (String[] one : new String[][]{{"遠藤健二", "森田健二"}, {"森田勇", "Morita Isamu"}, {"森田ハル", "Morita Haru"}}) Graph.merge(store, one[0], one[1], "person", "the family: one person");
        String geni = readGeni(store);
        assertTrue(geni.contains("The library linked \"森田健二's parent (written only as Endo)\" to 遠藤正一."), "the tree settles who the book's \"Endo\" is: " + geni);
        Graph before = FamilyPeople.view(store);
        String kenji = before.nodeIdOf("森田健二");

        // 1. one entry for the man, however a source writes him (was three to five entries: the romaji name, the kanji with its kana, the
        //    given name alone, the tree's birth name, Geni's English name). The book writes "Kenji" alone, and names one Kenji in full, whom it
        //    also writes Morita Kenji: genealogy's view links the given name alone to him by that evidence ({@link FamilyLinks}), and nobody is asked
        assertNotEquals(kenji, FamilyPeople.unlinkedView(store).nodeIdOf("Kenji"), "the read files the given name alone as it is written");
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.kind().equals("one-person") && q.people().contains(before.nodeIdOf("Kenji"))), FamilyNameQuestions.open(store).toString());
        Graph g = FamilyPeople.view(store);
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        for (String written : List.of("Morita Kenji", "もりた けんじ", "Kenji Endo", "遠藤健二", "Kenji Morita"))
            assertEquals(kenji, g.nodeIdOf(written), written + " is 森田健二, not an entry of its own");
        for (String alone : List.of("Kenji", "健二", "Morita Kenji", "遠藤健二")) {
            Graph.Node n = g.node(Vocabulary.norm(alone));
            assertTrue(n == null || n.id().equals(kenji) || !"person".equals(n.kind()), "no entry of its own for " + alone);
        }
        Finding grandson = claims(store, "relative-of").stream().filter(f -> f.body().contains("Kenji's grandson")).findFirst().orElseThrow();
        assertEquals(kenji, g.nodeOf(grandson, false), "\"Kenji's grandson\" is 森田健二's grandson: the book's Kenji is he");
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.kind().equals("one-person")), "the family said the tree's people are the book's and the book's Kenji is 森田健二, and one read and one Geni profile each link his names, so nothing more is asked whether two are one person");

        // 2. no person called Endo: the book's "Endo" is 遠藤正一, linked by the library's rule with a reason that cites the tree's record
        //    (was a father named "Endo")
        for (String endo : List.of("Endo", "endo", "遠藤")) {
            Graph.Node n = g.node(Vocabulary.norm(endo));
            assertTrue(n == null || !"person".equals(n.kind()), "no person called " + endo);
        }
        String shoichi = g.nodeIdOf("遠藤正一");
        assertEquals(shoichi, g.nodeIdOf("森田健二's parent (written only as Endo)"), "the described father is 遠藤正一");
        Finding treeParent = claims(store, "parent-of").stream().filter(f -> f.sources().get(0).locator().endsWith("tree.ged") && g.nodeIdOf(f.triple().subject()).equals(shoichi)).findFirst().orElseThrow();
        String link = Files.readAllLines(Graph.mergesFile(store), StandardCharsets.UTF_8).stream().filter(l -> l.startsWith(Vocabulary.norm("森田健二's parent (written only as Endo)"))).findFirst().orElseThrow();
        String[] col = link.split("\t");
        assertEquals("genealogy names", col[2], "the link is the library's own, by its rule, and says so");
        assertTrue(col[4].contains("only person in the library") && col[4].contains(code(treeParent)), "the reason says why, citing the tree's record of the parent: " + link);

        // 3. his names, each with its kind and years; the later one came with the adoption (was one label and a flat list of other names)
        FamilyNameHistory.Name born = name(idx, kenji, "遠藤健二"), later = name(idx, kenji, "森田健二");
        assertNotNull(born, idx.names(kenji).toString());
        assertEquals("birth", born.kind());
        assertEquals(1905, born.from().year(), "born 1905");
        assertEquals(1932, born.to().year(), "the birth name ends where the later one begins");
        assertNotNull(later, idx.names(kenji).toString());
        assertEquals("mukoyoshi", later.kind(), "the tree says 婿養子");
        assertEquals(1932, later.from().year());
        Finding event = store.finding(later.event());
        assertNotNull(event, "the name says the event that caused it: " + later);
        assertEquals("adopted-by", event.triple().predicate());
        assertEquals(g.nodeIdOf("森田勇"), g.nodeIdOf(event.triple().object()), "the adoption by 森田勇");
        assertEquals("森田健二 (born 遠藤)", idx.heading(kenji));
        assertTrue(idx.names(kenji).indexOf(born) < idx.names(kenji).indexOf(later), "in the order of the life");

        // 4. 婿養子 is its three parts from one sentence of the book: the adoption, the marriage and the family he entered (was a plain adoption)
        String isamu = g.nodeIdOf("森田勇"), haru = g.nodeIdOf("森田ハル");
        assertTrue(claims(store, "adopted-by").stream().anyMatch(f -> g.nodeIdOf(f.triple().subject()).equals(kenji) && g.nodeIdOf(f.triple().object()).equals(isamu) && "mukoyoshi".equals(FamilyDetail.get(f, "kind"))),
                "adopted by 森田勇 as 婿養子");
        assertTrue(claims(store, "married-to").stream().anyMatch(f -> f.sources().get(0).locator().endsWith("book.txt")
                        && (g.nodeIdOf(f.triple().subject()).equals(kenji) && g.nodeIdOf(f.triple().object()).equals(haru) || g.nodeIdOf(f.triple().subject()).equals(haru) && g.nodeIdOf(f.triple().object()).equals(kenji))),
                "married to 森田ハル, from the book's words");
        FamilyHouses.Membership entered = membership(g, "森田健二", "森田 family");
        assertNotNull(entered, FamilyHouses.families(g, kenji).toString());
        assertEquals("mukoyoshi", entered.how());
        assertEquals(1932, entered.from().year());
        List<FamilyHouses.Membership> heads = FamilyHouses.heads(g, g.nodeIdOf("森田 family"));
        assertTrue(heads.stream().anyMatch(m -> m.person().equals(isamu) && m.role().equals("head")), "森田勇 is the head of the 森田 family: " + heads);

        // 5. a dated line of his life says the name he carried then, and the book's "Morita Kenji" in 1920 did not date the change
        String life = FamilyNamesFilingTest.run(store, "life", "森田健二");
        assertTrue(life.startsWith("森田健二 (born 遠藤)"), life);
        assertTrue(life.contains("1920  as 遠藤健二: went to the village school (1920)."), "the 1920 school line reads the name he carried in 1920: " + life);
        assertEquals("遠藤健二", idx.at(kenji, 1920).written());
        assertEquals("森田健二", idx.at(kenji, 1935).written());

        // 6. 森田正二: two names, the later one taken on his adoption as heir; the family he left and the family he entered, each dated;
        //    no adoptive parent is invented, and "Isamu's son" stays a child as the book says it
        String shoji = g.nodeIdOf("森田正二");
        assertEquals(List.of("森田正二", "髙橋正二"), idx.names(shoji).stream().map(FamilyNameHistory.Name::written).toList(), "his names in the order of the life");
        FamilyNameHistory.Name heir = name(idx, shoji, "髙橋正二");
        assertEquals("adoptive", heir.kind(), "the book says \"adopted as heir\"");
        assertEquals(1940, heir.from().year());
        assertTrue(claims(store, "adopted-by").stream().noneMatch(f -> g.nodeIdOf(f.triple().subject()).equals(shoji)), "the book names nobody who adopted him, so no adoption by anybody is filed");
        FamilyHouses.Membership left = membership(g, "森田正二", "森田 family"), into = membership(g, "森田正二", "髙橋 family");
        assertNotNull(left); assertNotNull(into);
        assertEquals("adoption-out", left.left());
        assertEquals(1940, left.to().year(), "he left the 森田 family in 1940; the year is when he left, not when he came in");
        assertNull(left.from(), "the book does not say when he came into the 森田 family");
        assertEquals("adoption", into.how());
        assertEquals("heir", into.role());
        assertEquals(1940, into.from().year());
        assertEquals(g.nodeIdOf("森田 family"), into.cameFrom(), "he came to the 髙橋 family from the 森田 family");
        assertFalse(into.workedOut(), "a source says where he came from, so it is not worked out");
        assertTrue(claims(store, "child-of").stream().anyMatch(f -> g.nodeIdOf(f.triple().subject()).equals(shoji) && g.nodeIdOf(f.triple().object()).equals(isamu) && FamilyDetail.of(f).isEmpty()),
                "\"Isamu's son\" is a child of 森田勇, untyped");

        // review item 5: a change whose way the book does not give is a name of unknown kind, dated, and a question, and nobody is asked how
        // he came by the name he carried before it
        String masaru = g.nodeIdOf("森田勝");
        FamilyNameHistory.Name taken = name(idx, masaru, "髙橋勝");
        assertEquals("unknown", taken.kind(), "\"became\" does not say how");
        assertEquals(1990, taken.from().year(), "the book dates the change in words: In 1990 … became");
        FamilyHouses.Membership line = membership(g, "森田勝", "髙橋 family");
        assertNotNull(line, "\"the Takahashi line\" is the 髙橋 family the same book writes as the Takahashi family (髙橋家): " + FamilyHouses.families(g, masaru));
        assertTrue(line.how().isEmpty() || line.how().equals("unstated"), "the book does not say how he came into it: " + line);
        assertEquals("heir", line.role(), "\"carried on the Takahashi line\"");
        List<FamilyNameQuestions.Question> how = FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("name-change-how") && q.people().contains(masaru)).toList();
        assertEquals(1, how.size(), "one question, how 髙橋勝 came, and none about 森田勝, the name before it: " + how);
        assertTrue(how.get(0).text().contains("How did 森田勝 get the name 髙橋勝?"), how.get(0).text());
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.kind().equals("name-change-how") && q.people().contains(shoji)), "the book says how 髙橋正二 came, so nothing is asked about him");

        // 7. the family pages drill in and out: the 森田 family's head and members, where each came from and went to, each a command
        String morita = FamilyNamesFilingTest.run(store, "family", "森田 family");
        assertTrue(morita.contains("1. " + FamilyNamePages.heading(g, "Morita Isamu") + ", head"), "the head, under his heading: " + morita);
        assertTrue(morita.contains("森田健二 (born 遠藤): married into it as 婿養子 in 1932, coming from 遠藤 family (the library worked this out from a parent's family at the birth"),
                "he came from the 遠藤 family, worked out through 遠藤正一, whom the book's \"Endo\" was linked to: " + morita);
        assertTrue(morita.contains("髙橋正二 (born 森田): a member; how they came in is not written down; left it in 1940 on being adopted into another family, going to 髙橋 family"), morita);
        for (String command : List.of("researchzosho genealogy names \"森田健二\"", "researchzosho genealogy family \"遠藤 family\"", "researchzosho genealogy names \"森田正二\"", "researchzosho genealogy family \"髙橋 family\""))
            assertTrue(morita.contains(command), command + " opens the next page: " + morita);
        assertFalse(morita.contains("Morita Haru") || morita.contains("森田ハル"), "nobody is a member for sharing the family name; no source says 森田ハル was one: " + morita);
        String takahashi = FamilyNamesFilingTest.run(store, "family", "髙橋 family");
        assertTrue(takahashi.contains("髙橋正二 (born 森田): adopted into it as its heir in 1940, coming from 森田 family") && takahashi.contains("researchzosho genealogy family \"森田 family\""), takahashi);
        assertTrue(takahashi.contains("髙橋勝: its heir from 1990"), takahashi);
        assertTrue(FamilyNamesFilingTest.run(store, "names", "森田健二").contains("researchzosho genealogy family \"森田 family\""), "and back from the person to the family");
        String endoFamily = FamilyNamesFilingTest.run(store, "family", "遠藤");
        assertTrue(endoFamily.contains("遠藤 family") && endoFamily.contains("遠藤正一: as 遠藤正一") && endoFamily.contains("森田健二 (born 遠藤): as 遠藤健二, from 1905 to 1932."),
                "who bore the family name 遠藤, and in which years: " + endoFamily);
        assertTrue(FamilyHouses.named(g, "Endo").stream().allMatch(f -> FamilyHouses.labelOf(g, f).equals("遠藤 family")), "the family made for the book's \"Endo\" is the 遠藤 family, written in characters once the link was made");
        String web = FamilyPages.page(store, Patrons.Patron.PERSON, "/family", "GET", Map.of("name", "森田 family"), Map.of()).body();
        assertTrue(web.contains("<a href=\"/person?name=" + FamilyNamePages.enc("森田健二") + "\">") && web.contains("<a href=\"/family?name=" + FamilyNamePages.enc("遠藤 family") + "\">")
                && web.contains("<a href=\"/family?name=" + FamilyNamePages.enc("髙橋 family") + "\">"), "on the web page each is a link: " + web);
        String person = FamilyPages.page(store, Patrons.Patron.PERSON, "/person", "GET", Map.of("name", "森田正二"), Map.of()).body();
        assertTrue(person.contains("<a href=\"/family?name=" + FamilyNamePages.enc("森田 family") + "\">") && person.contains("<a href=\"/family?name=" + FamilyNamePages.enc("髙橋 family") + "\">"), person);

        // what waits for the family at the end is the one question only the family can answer
        assertEquals(List.of("name-change-how"), FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::kind).toList(), FamilyNameQuestions.open(store).toString());
    }

    /** The book read at a terminal and elsewhere, before the tree file; the question about "Endo" and what becomes of it. */
    @Test
    void theBookAsksWhoEndoIsWhereSomebodyCanAnswerAndTheTreeFileSettlesItAfterwards(@TempDir Path tmp) throws Exception {
        String endo = "family-name-alone";

        // 8a. nobody at the keyboard (a script, a pipe, the service): nothing waits, and the question is open for the sitting
        Path a = tmp.resolve("a");
        FamilyAskingTest.seconds(a, 60);
        LibraryStore quiet = library(a);
        Interaction.OVERRIDE = false;
        long start = System.nanoTime();
        String out = readBook(quiet, a);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 30_000, "nothing waited");
        assertFalse(out.contains("A PERSON WRITTEN ONLY BY A FAMILY NAME") || out.contains("Your answer:"), "nothing is asked where nobody can answer: " + out);
        assertTrue(FamilyNameQuestions.open(quiet).stream().anyMatch(q -> q.kind().equals(endo) && q.text().contains("only by the family name Endo")), "the question about \"Endo\" is open");
        settledByTheTree(quiet, a, "nobody at the keyboard");

        // 8b. at a terminal, nobody answers within the time: each question the book raised is put with its own wait and left open, the
        //     first unanswered one does not end the asking of the rest, and none is put twice: one file, so nothing was read while they waited
        Path b = tmp.resolve("b");
        FamilyAskingTest.seconds(b, 1);
        LibraryStore silent = library(b);
        Interaction.OVERRIDE = true;
        Interaction.INPUT = FamilyAskingTest.silent();
        start = System.nanoTime();
        out = readBook(silent, b);
        long took = (System.nanoTime() - start) / 1_000_000;
        int put = FamilyAskingTest.count(out, "Your answer:");
        assertTrue(put > 1, "every question the book raised is put, not only the first: " + out);
        assertEquals(put, FamilyAskingTest.count(out, "No answer came within 1 second. The question waits for you: researchzosho genealogy who"), "each is left open after its own wait: " + out);
        assertTrue(out.indexOf("A PERSON WRITTEN ONLY BY A FAMILY NAME") >= 0 && out.indexOf("A PERSON WRITTEN ONLY BY A FAMILY NAME") < out.indexOf("Your answer:"), "the first question put is the one about \"Endo\": " + out);
        assertFalse(out.contains("asks once more"), "the reading was finished when they were put: " + out);
        assertTrue(out.contains(put + " questions about names and families that this read raised wait for you."), "the summary says how many wait: " + out);
        assertTrue(out.contains("WHAT HAPPENS NEXT"), "the read finished: " + out);
        assertTrue(took >= put * 1000L && took < 30_000, "one wait of one second per question: " + took + " ms");
        assertTrue(FamilyNameQuestions.open(silent).stream().anyMatch(q -> q.kind().equals(endo)), "the question about \"Endo\" is still open");
        assertFalse(Files.exists(FamilyNameQuestions.askedFile(silent)), "nothing was answered or put off");
        settledByTheTree(silent, b, "a silent terminal");

        // 8c. at a terminal, the family answers "1": a person whose given name is not known, written down as the family's answer
        Path c = tmp.resolve("c");
        FamilyAskingTest.seconds(c, 5);
        LibraryStore answered = library(c);
        Interaction.OVERRIDE = true;
        Interaction.INPUT = new BufferedReader(new StringReader("1\n"));
        out = readBook(answered, c);
        int asked = out.indexOf("A PERSON WRITTEN ONLY BY A FAMILY NAME");
        assertTrue(asked >= 0 && asked < out.indexOf("Your answer:"), "the question about \"Endo\" is put first: " + out);
        assertTrue(out.contains("1. Somebody whose given name is not known"), out);
        List<String> lines = Files.readAllLines(FamilyNameQuestions.askedFile(answered), StandardCharsets.UTF_8);
        String teller = System.getProperty("user.name", "the owner of this library");
        assertTrue(lines.stream().anyMatch(l -> l.split("\t")[1].equals("answered") && l.split("\t")[3].equals(teller) && l.contains("is somebody whose given name is not known")),
                "the family's answer is written down, by the person who gave it at the terminal: " + lines);
        assertTrue(FamilyNameQuestions.open(answered).stream().noneMatch(q -> q.kind().equals(endo)), "an answered question is not open");
        settledByTheTree(answered, c, "an answer at the terminal");
    }

    /**
     * Importing the tree file afterwards, and the family's answer that its 遠藤健二 is the book's 森田健二, let the library link the book's "Endo"
     * itself, and the question about him is gone.
     */
    private void settledByTheTree(LibraryStore store, Path dir, String how) throws Exception {
        Interaction.OVERRIDE = false;
        Interaction.INPUT = null;
        String out = importTree(store, dir);
        Graph kept = FamilyPeople.view(store);
        List<String> pair = List.of(kept.nodeIdOf("遠藤健二"), kept.nodeIdOf("森田健二"));
        FamilyNameQuestions.Question same = FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("one-person") && q.people().containsAll(pair)).findFirst()
                .orElseThrow(() -> new AssertionError(how + ": the family is asked whether the tree's 遠藤健二 is the book's 森田健二: " + out));
        FamilyNameQuestions.answer(store, same.code(), "one", "", "an aunt");
        List<String> linked = FamilyMentions.again(store);   // what the next read or import does first
        Graph g = FamilyPeople.view(store);
        assertEquals(g.nodeIdOf("遠藤正一"), g.nodeIdOf("森田健二's parent (written only as Endo)"), how + ": the tree file settles who \"Endo\" is: " + out + linked);
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.kind().equals("family-name-alone")), how + ": and the question about him is gone");
    }
}
