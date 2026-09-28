package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A family name used alone, resolved in the order the spec gives: a family or a person; the same passage first; the people who bore the
 * name at that date; the relation in the phrase; a second fact before any link; otherwise a described person linked to the family and a
 * question; "son" untyped; and every link the library makes can be taken back.
 */
class FamilyMentionsTest {

    static final String TREE = "file:///family/tree.ged", BOOK = "file:///family/book.txt";
    static final String SON = "遠藤の子、森田健二は村の学校に通った。";
    static final String PARENT = "森田健二's parent (written only as 遠藤)";

    private static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return new FamilyAccount.Fact(s, r, o, date, quote); }

    /** 森田健二, born 遠藤健二 in 1905, so that 遠藤 is a family name the library knows. */
    private static void kenji(LibraryStore store) throws Exception {
        FamilyNameHistoryTest.file(store, TREE, List.of(fact("森田健二", "born-in", "広島", "1905", "森田健二 1905年 広島生")),
                List.of(FamilyNameHistoryTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "遠藤健二 1905年生")));
    }

    private static void born(LibraryStore store, String who, String year) throws Exception {
        FamilyNameHistoryTest.file(store, TREE, List.of(fact(who, "born-in", "広島", year, who + " " + year + "年 広島生")), List.of());
    }

    /** The book's words, read with the reader's checks: 森田健二 is a child of somebody written only as 遠藤. */
    private static FamilyAccount.Outcome readSon(LibraryStore store) throws Exception {
        return FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田健二", "child-of", "遠藤", "", SON)), List.of()), BOOK, "an aunt");
    }

    private static Graph view(LibraryStore store) throws Exception { return FamilyPeople.view(store); }

    @Test
    void oneAFamilyWordMakesAFamilyAndAPossessiveMakesSomebodyKnownByTheFamilyName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        Graph g = view(store);
        Set<String> parts = Set.of("遠藤", "Endo", "Hale", "Ellis");
        assertEquals("family", FamilyMentions.kind(g, "遠藤", "健二は遠藤家の次男として生まれた。", false, "an aunt", parts, Set.of()));
        assertEquals("family", FamilyMentions.kind(g, "Endo", "Kenji was born into the Endo family.", false, "an aunt", parts, Set.of()));
        assertEquals("person", FamilyMentions.kind(g, "Endo", "Endo's son, Morita Kenji", false, "an aunt", parts, Set.of()));
        assertEquals("person", FamilyMentions.kind(g, "遠藤", SON, false, "an aunt", parts, Set.of()));
        assertEquals("person", FamilyMentions.kind(g, "Hale", "Father: Hale | Mother: Mary Hale", false, "an aunt", parts, Set.of()), "a table's kin label beside the family name alone");
        assertEquals("person", FamilyMentions.kind(g, "遠藤", "父 遠藤 母 ハル", false, "an aunt", parts, Set.of()));
        assertEquals("person", FamilyMentions.kind(g, "Endo", "Geni: Kenji is a child of Endo and Haru Endo", false, "an aunt", parts, Set.of()));
        assertNull(FamilyMentions.kind(g, "遠藤", "父 遠藤正一", false, "an aunt", parts, Set.of()), "the label is beside a whole name");
        assertEquals("person", FamilyMentions.kind(g, "Hale", "a letter to Hale, who was Tom's father", true, "an aunt", parts, Set.of()), "the model's mark, with a family name the library knows");
        assertNull(FamilyMentions.kind(g, "Hart", "Hart's son Tom", false, "an aunt", parts, Set.of()), "Hart is no family name the library or the read knows");
        assertNull(FamilyMentions.kind(g, "Ruth", "Ruth's son Tom Hale", true, "an aunt", Set.of("Hale", "Ruth"), Set.of("Ruth")), "a given name alone is never a family name");
        assertNull(FamilyMentions.kind(g, "Hale", "Hale taught Tom", false, "an aunt", parts, Set.of()), "no possessive, no family word, no mark");
        assertNull(FamilyMentions.kind(g, "Hale", "Hale's son Tom", false, "Hale", parts, Set.of()), "the teller is somebody");
        // filed: a relation to a family is a membership of it, and nobody is named 遠藤
        kenji(store);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田健二", "child-of", "遠藤", "", "健二は遠藤家の次男として生まれた。")), List.of()), BOOK, "an aunt");
        Graph after = view(store);
        assertNull(after.node(after.nodeIdOf("遠藤")), "no person named 遠藤");
        List<FamilyHouses.Membership> ms = FamilyHouses.families(after, after.nodeIdOf("森田健二"));
        assertEquals(1, ms.size(), ms.toString());
        assertEquals("遠藤 family", FamilyHouses.labelOf(after, ms.get(0).family()));
        assertEquals("birth", ms.get(0).how());
    }

    @Test
    void twoTheNearestFullNameOfTheSamePassageIsOnlyACandidate(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String teacher = "Shoichi Endo taught at the village school.", son = "Endo's son, Morita Kenji, went to the village school in 1920.";
        String json = """
                {"people": [{"name": "Shoichi Endo", "family": "Endo", "given": "Shoichi"}, {"name": "Morita Kenji", "family": "Morita", "given": "Kenji"}, {"name": "Endo", "family": "Endo"}],
                 "facts": [{"subject": "Shoichi Endo", "relation": "occupation", "object": "teacher", "date": "", "quote": "%s"},
                           {"subject": "Morita Kenji", "relation": "child-of", "object": "Endo", "date": "", "quote": "%s", "only_family_name": true}]}
                """.formatted(teacher, son);
        FamilyAccount.Read r = FamilyAccount.read(teacher + "\n\n" + son + "\n", "an aunt", new GenealogyProfile().predicates(), prompt -> json);
        assertEquals("Shoichi Endo", r.facts().stream().filter(f -> f.relation().equals("child-of")).findFirst().orElseThrow().detail().get("near"));
        FamilyAccount.Outcome o = FamilyAccount.file(store, r, BOOK, "an aunt");
        assertTrue(o.linked().isEmpty(), "a name nearby is not a second fact: " + o.linked());
        Graph g = view(store);
        String parent = "Morita Kenji's parent (written only as Endo)";
        assertEquals(List.of(g.nodeIdOf("Shoichi Endo")), FamilyMentions.candidates(FamilyNameHistory.of(g), g, parent), "but it is a candidate for the family to choose");
        assertNotEquals(g.nodeIdOf(parent), g.nodeIdOf("Shoichi Endo"));
    }

    @Test
    void threeAManWhoTookTheNameOnlyLaterIsNoCandidateForAnEarlierYear(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        kenji(store);
        born(store, "遠藤正一", "1875");
        born(store, "髙橋勇", "1870");
        FamilyNameHistoryTest.file(store, TREE, List.of(), List.of(FamilyNameHistoryTest.name("髙橋勇", "遠藤勇", "遠藤", "勇", "adoptive", "1940", "髙橋勇は1940年に遠藤家の養子となり遠藤勇と改名")));
        Graph g = view(store);
        assertEquals(List.of(g.nodeIdOf("遠藤正一")), FamilyMentions.candidates(FamilyNameHistory.of(g), g, PARENT), "in 1905 only 遠藤正一 bore the name; 髙橋勇 took it in 1940");
    }

    @Test
    void fourThePersonThemselvesTheirBrothersAndSistersAndAnybodyOfTheWrongGenerationAreOut(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        kenji(store);
        born(store, "遠藤正一", "1875");
        born(store, "遠藤勇", "1880");
        born(store, "遠藤勝", "1898");
        born(store, "遠藤正二", "1903");
        FamilyNameHistoryTest.file(store, TREE, List.of(fact("遠藤正二", "sibling-of", "森田健二", "", "健二の兄 遠藤正二")), List.of());
        Graph g = view(store);
        List<String> c = FamilyMentions.candidates(FamilyNameHistory.of(g), g, PARENT);
        assertEquals(Set.of(g.nodeIdOf("遠藤勇"), g.nodeIdOf("遠藤正一")), Set.copyOf(c), "not 森田健二 himself, not his brother 遠藤正二, not 遠藤勝, seven years older: " + c);
        // a husband or a wife is of the same generation, within the span a marriage allows: then 遠藤勝 fits too
        List<String> spouse = FamilyMentions.candidates(FamilyNameHistory.of(g), g, "森田健二's husband or wife (written only as 遠藤)");
        assertTrue(spouse.contains(g.nodeIdOf("遠藤勝")) && !spouse.contains(g.nodeIdOf("遠藤正二")), spouse.toString());
    }

    @Test
    void fiveOneCandidateWithASecondFactIsLinkedWithItsReasonAndEightTheLinkIsTakenBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        kenji(store);
        born(store, "遠藤正一", "1875");
        born(store, "遠藤勇", "1880");
        FamilyNameHistoryTest.file(store, TREE, List.of(fact("森田健二", "child-of", "遠藤正一", "", "FAMC @F1@ 遠藤正一")), List.of());
        FamilyAccount.Outcome o = readSon(store);
        assertEquals(1, o.mentions());
        assertEquals(1, o.linked().size(), "遠藤勇 bore the name too, and only 遠藤正一 has a fact that agrees: " + o.linked());
        String said = o.linked().get(0);
        assertTrue(said.startsWith("The library linked \"" + PARENT + "\" to 遠藤正一. 遠藤正一 bore the family name 遠藤 in 1905 and fits as 森田健二's parent. Of the 2 people in the library who do, only 遠藤正一 has a second fact that agrees: both are parents of 森田健二"), said);
        assertTrue(said.endsWith("If that is wrong, this command takes the link back: researchzosho graph unmerge \"" + PARENT + "\""), said);
        Graph g = view(store);
        assertEquals(g.nodeIdOf("遠藤正一"), g.nodeIdOf(PARENT));
        String line = Files.readAllLines(Graph.mergesFile(store), StandardCharsets.UTF_8).stream().filter(l -> l.contains("genealogy names")).findFirst().orElseThrow();
        String[] c = line.split("\t");
        assertEquals(Vocabulary.norm(PARENT), c[0]);
        assertEquals("genealogy names", c[2]);
        assertTrue(c[4].matches(".*both are parents of 森田健二 \\(born 遠藤\\) \\(F-\\d{4}, F-\\d{4}\\).*The words are in F-\\d{4}.*"), "the reason names the child by his heading and cites the claims: " + c[4]);
        String found = GenealogyProfile.found(1, 2, 0, 0, "this text", false, 0, 0, o.mentions(), o.linked().size());
        assertTrue(found.contains("One person is written in this text only by a family name") && found.contains(" It linked that person to somebody already in your library"), found);
        // taken back by the family: the link goes, and the library does not make it again
        Graph.unmerge(store, PARENT, null, "person", "not him");
        assertNotEquals(view(store).nodeIdOf("遠藤正一"), view(store).nodeIdOf(PARENT));
        assertTrue(FamilyMentions.again(store).isEmpty());
        assertNotEquals(view(store).nodeIdOf("遠藤正一"), view(store).nodeIdOf(PARENT));
    }

    @Test
    void sixTwoCandidatesLeaveADescribedPersonOfTheFamilyAndBothAreListed(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        kenji(store);
        born(store, "遠藤正一", "1875");
        born(store, "遠藤勝", "1878");
        FamilyAccount.Outcome o = readSon(store);
        assertTrue(o.linked().isEmpty());
        Graph g = view(store);
        assertEquals(Set.of(g.nodeIdOf("遠藤正一"), g.nodeIdOf("遠藤勝")), Set.copyOf(FamilyMentions.candidates(FamilyNameHistory.of(g), g, PARENT)), "never the most prominent bearer: both are listed");
        List<FamilyHouses.Membership> ms = FamilyHouses.families(g, g.nodeIdOf(PARENT));
        assertEquals(1, ms.size());
        assertEquals("遠藤 family", FamilyHouses.labelOf(g, ms.get(0).family()));
        assertEquals("unstated", ms.get(0).how());
        Finding member = store.finding(ms.get(0).claims().get(0));
        assertEquals("family-account", member.writer());
        assertEquals(BOOK, member.sources().get(0).locator(), "from the book's words and source");
        assertTrue(member.body().contains("The account says: \"" + SON + "\""), member.body());
        String found = GenealogyProfile.found(1, 2, 0, 0, "this text", false, 0, 0, o.mentions(), 0);
        assertTrue(found.contains("To say who that person is, give researchzosho genealogy who. It asks you its questions one at a time."), found);
    }

    @Test
    void sevenSonStaysAChildNeitherBirthNorAdoption(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        kenji(store);
        readSon(store);
        List<Finding> about = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().object().equals(PARENT)).toList();
        assertEquals(1, about.size());
        assertEquals("child-of", about.get(0).triple().predicate());
        assertEquals(Map.of(), FamilyDetail.of(about.get(0)), "no kind is added to what the words say");
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.triple() != null && f.triple().predicate().equals("adopted-by")));
    }

    @Test
    void newEvidenceSettlesAnOlderDescribedPersonAndOnlyOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        kenji(store);
        assertTrue(readSon(store).linked().isEmpty(), "nobody bore the name yet");
        born(store, "遠藤正一", "1875");
        FamilyNameHistoryTest.file(store, TREE, List.of(fact("森田健二", "child-of", "遠藤正一", "", "FAMC @F1@ 遠藤正一")), List.of());
        List<String> linked = FamilyMentions.again(store);
        assertEquals(1, linked.size(), linked.toString());
        assertTrue(linked.get(0).contains("and is the only person in the library who does. A second fact agrees: both are parents of 森田健二"), linked.get(0));
        assertEquals(view(store).nodeIdOf("遠藤正一"), view(store).nodeIdOf(PARENT));
        assertTrue(FamilyMentions.again(store).isEmpty(), "said once");
    }

    @Test
    void aFamilyNameInRomajiMeetsItsCharactersOnlyThroughAReadingASourceGave(@TempDir Path tmp) throws Exception {
        for (boolean reading : List.of(false, true)) {
            LibraryStore store = FamilyNameHistoryTest.store(tmp.resolve(reading ? "with" : "without"));
            FamilyNameHistoryTest.file(store, TREE, List.of(fact("森田健二", "born-in", "広島", "1905", "森田健二 1905年 広島生"), fact("森田健二", "child-of", "遠藤正一", "", "FAMC @F1@ 遠藤正一")),
                    List.of(reading ? FamilyNameHistoryTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "遠藤健二 1905年生", "Kenji Endo") : FamilyNameHistoryTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "遠藤健二 1905年生")));
            born(store, "遠藤正一", "1875");
            Graph.alias(store, "森田健二", List.of("Morita Kenji"));
            FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endo", "", List.of(), "Endo", "")),
                    List.of(new FamilyAccount.Fact("Morita Kenji", "child-of", "Endo", "", "Endo's son, Morita Kenji", Map.of("only-family-name", "true"))), List.of()), BOOK, "an aunt");
            assertEquals(1, o.mentions());
            assertEquals(reading ? 1 : 0, o.linked().size(), (reading ? "Kenji Endo, a reading a source gave for 遠藤健二, reads 遠藤 as Endo: " : "nothing says 遠藤 is read Endo: ") + o.linked());
        }
    }

    @Test
    void aResetTakesBackTheLinksTheLibraryMadeWithTheFilesTheyRestOn(@TempDir Path tmp) throws Exception {
        for (String from : List.of("", "book.txt", "notes.txt")) {
            LibraryStore store = FamilyNameHistoryTest.store(tmp.resolve(from.isEmpty() ? "all" : from));
            kenji(store);
            born(store, "遠藤正一", "1875");
            FamilyNameHistoryTest.file(store, TREE, List.of(fact("森田健二", "child-of", "遠藤正一", "", "FAMC @F1@ 遠藤正一")), List.of());
            FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "born-in", "呉", "1870", "森田勇は1870年に呉で生まれた。")), List.of()), "file:///family/notes.txt", "an aunt");
            assertEquals(1, readSon(store).linked().size());
            FamilyReset.apply(store, FamilyReset.plan(store, List.of("family-account", "gedcom-import"), from));
            boolean gone = !view(store).nodeIdOf(PARENT).equals(view(store).nodeIdOf("遠藤正一"));
            assertEquals(!from.equals("notes.txt"), gone, "a fresh start, and the file the link's words came from, take it back; another file does not (" + from + ")");
        }
    }

    @Test
    void aGivenNameWrittenBothWaysRoundIsNoFamilyName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.Read r = new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "", List.of("Morita Kenji", "Kenji Morita"), "森田", "健二"), new FamilyAccount.Person("Morita Masaru", "", List.of(), "Morita", "Masaru")),
                List.of(new FamilyAccount.Fact("Morita Masaru", "child-of", "Kenji", "1962", "Kenji's son Morita Masaru was born in 1962.", Map.of("only-family-name", "true"))), List.of());
        Set<String> parts = FamilyMentions.familyParts(view(store), r);
        assertFalse(parts.stream().anyMatch(p -> FamilyForms.sameForm(p, "Kenji")), "a name written both ways round makes no family name of its given name: " + parts);
        FamilyAccount.file(store, r, BOOK, "an aunt");
        Graph g = view(store);
        assertTrue(FamilyHouses.named(g, "Kenji").isEmpty(), "no Kenji family: " + FamilyHouses.all(g));
        assertTrue(g.nodes().stream().noneMatch(n -> n.label().contains("written only as Kenji")), "Kenji is a given name, never a family mention");
        // a library whose people carry their names both ways round: a later letter's "Haru's son" is no Haru family either
        LibraryStore lib = FamilyNameHistoryTest.store(tmp.resolve("lib"));
        born(lib, "Morita Haru", "1910");
        born(lib, "Morita Isamu", "1880");
        Graph.alias(lib, "Morita Haru", List.of("Haru Morita"));
        Graph.alias(lib, "Morita Isamu", List.of("Isamu Morita"));
        FamilyAccount.file(lib, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Morita Masaru", "", List.of(), "Morita", "Masaru")),
                List.of(new FamilyAccount.Fact("Morita Masaru", "child-of", "Haru", "1936", "Haru's son Morita Masaru was born in 1936.", Map.of("only-family-name", "true"))), List.of()), BOOK, "an aunt");
        Graph lg = view(lib);
        assertTrue(FamilyHouses.named(lg, "Haru").isEmpty(), "no Haru family: " + FamilyHouses.all(lg));
    }

    @Test
    void aResetsTakeBackIsNoRefusalAndTheReadAgainLinksAgain(@TempDir Path tmp) throws Exception {
        for (String from : List.of("", "book.txt")) {
            LibraryStore store = FamilyNameHistoryTest.store(tmp.resolve(from.isEmpty() ? "all" : "book"));
            kenji(store);
            born(store, "遠藤正一", "1875");
            FamilyNameHistoryTest.file(store, TREE, List.of(fact("森田健二", "child-of", "遠藤正一", "", "FAMC @F1@ 遠藤正一")), List.of());
            assertEquals(1, readSon(store).linked().size());
            FamilyReset.apply(store, FamilyReset.plan(store, List.of("family-account", "gedcom-import"), from));
            if (from.isEmpty()) {   // a fresh start takes the tree's claims too: it is read again, as the reset is meant for
                kenji(store);
                born(store, "遠藤正一", "1875");
                FamilyNameHistoryTest.file(store, TREE, List.of(fact("森田健二", "child-of", "遠藤正一", "", "FAMC @F1@ 遠藤正一")), List.of());
            }
            FamilyAccount.Outcome again = readSon(store);
            assertEquals(1, again.linked().size(), "the fresh read links the mention again (" + from + "): " + again.linked());
            assertEquals(view(store).nodeIdOf("遠藤正一"), view(store).nodeIdOf(PARENT));
        }
    }

    @Test
    void aSpouseOrSiblingWordDescribesThePartyItStandsBeside(@TempDir Path tmp) throws Exception {
        assertEquals("husband", FamilyMentions.role("married-to", true, "森田ハル", "遠藤", "遠藤の妻、森田ハルは店を守った。"), "Endo's wife is Haru: Endo is her husband");
        assertEquals("husband", FamilyMentions.role("married-to", true, "Haru", "Endo", "Endo's wife, Haru, kept the shop."));
        assertEquals("wife", FamilyMentions.role("married-to", true, "Tom Hale", "Ellis", "Tom Hale's wife, Ellis, kept the inn."), "the word stands beside the one written only by the family name");
        assertEquals("brother or sister", FamilyMentions.role("sibling-of", true, "森田健二", "遠藤", "遠藤の弟、森田健二は店を継いだ。"), "Kenji is the younger brother: Endo's sex is not said");
        assertEquals("brother", FamilyMentions.role("sibling-of", true, "Ruth", "Hale", "Ruth's brother Hale kept the inn."));
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        born(store, "森田ハル", "1880");
        born(store, "遠藤正一", "1875");
        FamilyNameHistoryTest.file(store, TREE, List.of(fact("遠藤正一", "sex", "male", "", "SEX M"), fact("遠藤正一", "married-to", "森田ハル", "", "FAMS @F2@")), List.of());
        FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("遠藤", "", List.of(), "遠藤", "")),
                List.of(new FamilyAccount.Fact("森田ハル", "married-to", "遠藤", "", "遠藤の妻、森田ハルは店を守った。", Map.of("only-family-name", "true"))), List.of()), BOOK, "an aunt");
        assertEquals(1, o.linked().size(), "her husband bore the name and is married to her in the tree: " + o.linked());
        assertEquals(view(store).nodeIdOf("遠藤正一"), view(store).nodeIdOf("森田ハル's husband (written only as 遠藤)"));
    }

    @Test
    void aMentionOfAChildOrASiblingFindsItsCandidates(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        born(store, "Mary Ellis", "1850");
        born(store, "Tom Hale", "1880");
        FamilyNameHistoryTest.file(store, TREE, List.of(fact("Tom Hale", "child-of", "Mary Ellis", "", "FAMC @F3@ Mary Ellis")), List.of());
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Hale", "", List.of(), "Hale", "")),
                List.of(new FamilyAccount.Fact("Mary Ellis", "parent-of", "Hale", "", "Hale's mother, Mary Ellis, kept the inn.", Map.of("only-family-name", "true"))), List.of()), BOOK, "an aunt");
        Graph g = view(store);
        String child = "Mary Ellis's child (written only as Hale)";
        assertEquals(g.nodeIdOf("Tom Hale"), g.nodeIdOf(child), "her son bore the name Hale when he was born, and is her child in the tree: " + (Files.exists(Graph.mergesFile(store)) ? Files.readString(Graph.mergesFile(store)) : "no link"));
        // a brother or a sister: the person's recorded brothers and sisters are the ones it can be
        LibraryStore sib = FamilyNameHistoryTest.store(tmp.resolve("sib"));
        kenji(sib);
        born(sib, "遠藤ハル", "1901");
        FamilyNameHistoryTest.file(sib, TREE, List.of(fact("遠藤ハル", "sibling-of", "森田健二", "", "健二の姉 遠藤ハル")), List.of());
        Graph sg = view(sib);
        for (String role : List.of("brother or sister", "sister"))
            assertEquals(List.of(sg.nodeIdOf("遠藤ハル")), FamilyMentions.candidates(FamilyNameHistory.of(sg), sg, "森田健二's " + role + " (written only as 遠藤)"), role);
        assertTrue(FamilyMentions.candidates(FamilyNameHistory.of(sg), sg, PARENT).stream().noneMatch(c -> c.equals(sg.nodeIdOf("遠藤ハル"))), "his sister is never his parent");
    }

    // ── the second review ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void endosSonIsSomebodyAlsoWhenTheLibraryHoldsEndoAsTheFamilysOtherName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        // an older library: an entry "Endo", which the family answered to be the Endo family itself
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Endo", "lived-in", "Kōfu", "", "Endo lived in Kōfu.")), List.of()), "file:///family/old.txt", "an aunt");
        String fam = FamilyHouses.family(store, view(store), "Endo", "", "", List.of());
        Graph.merge(store, "Endo", fam, "person", "the family's answer", new GenealogyProfile());
        assertTrue(FamilyHouses.isFamily(view(store), view(store).nodeIdOf("Endo")));
        FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endo", "", List.of(), "Endo", ""), new FamilyAccount.Person("Morita Kenji", "", List.of(), "Morita", "Kenji")),
                List.of(new FamilyAccount.Fact("Morita Kenji", "child-of", "Endo", "", "Endo's son, Morita Kenji, went to the village school.", Map.of("only-family-name", "true")),
                        new FamilyAccount.Fact("Morita Haru", "married-to", "Endo", "", "Endo's wife, Morita Haru, kept the shop.", Map.of("only-family-name", "true"))), List.of()), BOOK, "an aunt");
        assertEquals(2, o.mentions(), "his parent and her husband are somebody written only as Endo, never the family: "
                + store.scanFindings().findings().stream().map(Finding::triple).toList());
        Graph g = view(store);
        assertTrue(FamilyHouses.families(g, g.nodeIdOf("Morita Kenji")).isEmpty(), "Endo's son was not born into the Endo family by these words");
        assertEquals(g.nodeIdOf("Endo family"), FamilyHouses.families(g, g.nodeIdOf("Morita Kenji's parent (written only as Endo)")).get(0).family(), "his parent is of the Endo family");
        assertNotNull(g.node(g.nodeIdOf("Morita Haru's husband (written only as Endo)")));
        // the other way the word comes to lead to the family: a read that wrote a membership's family by the word alone
        LibraryStore two = FamilyNameHistoryTest.store(tmp.resolve("two"));
        FamilyAccount.file(two, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endō Haru", "", List.of(), "Endō", "Haru")),
                List.of(new FamilyAccount.Fact("Endō Haru", "member-of", "Endo", "", "Endō Haru was born into the Endo family.", Map.of("how", "birth"))), List.of()), "file:///family/a.txt", "an aunt");
        FamilyAccount.Outcome t = FamilyAccount.file(two, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Morita Kenji", "", List.of(), "Morita", "Kenji")),
                List.of(new FamilyAccount.Fact("Morita Kenji", "child-of", "Endo", "", "Endo's son, Morita Kenji, went to the village school.", Map.of("only-family-name", "true"))), List.of()), "file:///family/b.txt", "an aunt");
        assertEquals(1, t.mentions());
        Graph tg = view(two);
        assertTrue(FamilyHouses.families(tg, tg.nodeIdOf("Morita Kenji")).isEmpty(), FamilyHouses.families(tg, tg.nodeIdOf("Morita Kenji")).toString());
    }

    @Test
    void theSecondSonOfTheEndoHouseIsBornIntoItAndHisParentIsSomebodyOfIt(@TempDir Path tmp) throws Exception {
        String sen = "健二は遠藤家の次男として明治三十八年に生まれた。";
        String json = """
                {"people": [{"name": "遠藤正一", "family": "遠藤", "given": "正一"}, {"name": "遠藤健二", "family": "遠藤", "given": "健二"}],
                 "facts": [{"subject": "遠藤正一", "relation": "born-on", "object": "明治八年", "date": "", "quote": "遠藤正一は明治八年に生まれた。"},
                           {"subject": "遠藤健二", "relation": "child-of", "object": "遠藤正一", "date": "", "quote": "%s"}],
                 "families": [{"name": "遠藤", "written": "遠藤家", "seat": "", "quote": "%s"}]}
                """.formatted(sen, sen);
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.Outcome o = FamilyAccount.file(store, FamilyAccount.read("遠藤正一は明治八年に生まれた。" + sen + "\n", "an aunt", new GenealogyProfile().predicates(), prompt -> json), BOOK, "an aunt");
        List<Finding> childOf = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("child-of")).toList();
        assertTrue(childOf.stream().noneMatch(f -> f.triple().object().equals("遠藤正一")), "the words name no father, only the house: the model's pick is no link by itself: " + childOf.stream().map(Finding::triple).toList());
        String parent = "遠藤健二's parent (written only as 遠藤)";
        assertTrue(childOf.stream().anyMatch(f -> f.triple().object().equals(parent)), childOf.stream().map(Finding::triple).toList().toString());
        assertEquals(1, o.mentions());
        Graph g = view(store);
        List<FamilyHouses.Membership> ms = FamilyHouses.families(g, g.nodeIdOf("遠藤健二"));
        assertEquals(List.of("birth"), ms.stream().map(FamilyHouses.Membership::how).toList(), "born into the 遠藤 house: " + ms);
        assertEquals(g.nodeIdOf("遠藤正一"), FamilyMentions.candidates(FamilyNameHistory.of(g), g, parent).get(0), "the model's pick is offered first");
        // the same words in English: the membership, and the parent as somebody of the family
        String en = "Kenji was born in 1905, the second son of the Endo family.";
        LibraryStore e = FamilyNameHistoryTest.store(tmp.resolve("en"));
        FamilyAccount.file(e, FamilyAccount.read("Endō Shōichi was born in 1875. " + en + "\n", "an aunt", new GenealogyProfile().predicates(), prompt -> """
                {"people": [{"name": "Endō Shōichi", "family": "Endō", "given": "Shōichi"}, {"name": "Endō Kenji", "family": "Endō", "given": "Kenji"}],
                 "facts": [{"subject": "Endō Kenji", "relation": "child-of", "object": "Endō Shōichi", "date": "", "quote": "%s"}]}
                """.formatted(en)), BOOK, "an aunt");
        Graph eg = view(e);
        assertEquals(List.of("birth"), FamilyHouses.families(eg, eg.nodeIdOf("Endō Kenji")).stream().map(FamilyHouses.Membership::how).toList());
        assertTrue(e.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().predicate().equals("child-of") && f.triple().object().equals("Endō Kenji's parent (written only as Endo)")),
                e.scanFindings().findings().stream().map(Finding::triple).toList().toString());
    }

    @Test
    void aFamilyMadeForAMentionIsNotWrittenAsAFamilyWithAnotherSeat(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String a = "Endo's son, Morita Kenji, went to the village school in Kōfu.", fa = "The Endo family farmed at Kōfu for four generations.";
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endo", "", List.of(), "Endo", ""), new FamilyAccount.Person("Morita Kenji", "", List.of(), "Morita", "Kenji")),
                List.of(new FamilyAccount.Fact("Morita Kenji", "child-of", "Endo", "", a, Map.of("only-family-name", "true"))), List.of(), List.of(), List.of(),
                List.of(new FamilyAccount.FamilyRead("Endo", "the Endo family", "Kōfu", fa))), BOOK, "an aunt");
        String b = "遠藤正一（Endō Shōichi）は広島の遠藤家に明治八年に生まれた。長男森田健二は明治三十八年生。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(),
                List.of(fact("遠藤正一", "born-on", "1875", "", b), fact("Morita Kenji", "born-on", "1905", "", b), fact("遠藤正一", "parent-of", "Morita Kenji", "", b),
                        new FamilyAccount.Fact("遠藤正一", "member-of", "遠藤家", "", b, Map.of("how", "birth"))),
                List.of(), List.of(), List.of(new FamilyAccount.NameRead("遠藤正一", "遠藤正一", "遠藤", "正一", List.of("Endō Shōichi"), "birth", "", "1875", b)),
                List.of(new FamilyAccount.FamilyRead("遠藤", "遠藤家", "広島", b))), TREE, "an aunt");
        List<String> said = FamilyMentions.again(store);
        assertTrue(said.stream().noneMatch(x -> x.contains("The library wrote the Endo family (Kōfu) as")), "two families of two seats are two families: " + said);
        Graph g = view(store);
        assertEquals(List.of("Endo family (Kōfu)", "遠藤 family (広島)"), FamilyHouses.all(g).stream().map(id -> FamilyHouses.labelOf(g, id)).sorted().toList());
        assertEquals("広島", FamilyHouses.seat(g, g.nodeIdOf("遠藤 family (広島)")));
    }

    @Test
    void endosSonBesideTheWordsForTheEndoFamilyIsStillEndosSon(@TempDir Path tmp) throws Exception {
        String q = "Endo's son, Morita Kenji, left the Endo family for the Morita family in 1932.";
        assertEquals("person", FamilyMentions.kind(view(FamilyNameHistoryTest.store(tmp.resolve("k"))), "Endo", q, true, "an aunt", Set.of("Endo"), Set.of()), "the kin word stands on Endo itself");
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endo", "", List.of(), "Endo", ""), new FamilyAccount.Person("Morita Kenji", "", List.of(), "Morita", "Kenji")),
                List.of(new FamilyAccount.Fact("Morita Kenji", "child-of", "Endo", "", q, Map.of("only-family-name", "true"))), List.of()), BOOK, "an aunt");
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().predicate().equals("child-of") && f.triple().object().equals("Morita Kenji's parent (written only as Endo)")),
                "his parent is written down, as the words write them: " + store.scanFindings().findings().stream().map(Finding::triple).toList());
    }
    /**
     * f-final-7: 森田健二, known only by 森田健二, entered the 森田 family as 婿養子 in 1932 and was born in 1905 to a father of the 遠藤 family.
     * His one name dates from 1932, so he did not bear 森田 at his birth, and a child, a brother or a sister written only as 森田 is not him.
     */
    @Test
    void aNameTakenAfterTheBirthIsNotTheNameBorneAtIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "Kenji (森田健二), the son of 遠藤正一, was born in 1905 and entered the Morita family (森田家) as mukoyōshi (婿養子) of 森田勇 in 1932.";
        FamilyNameHistoryTest.fileWith(store, BOOK, List.of(fact("森田健二", "child-of", "遠藤正一", "", q), fact("森田健二", "born-on", "1905", "", q),
                        new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "mukoyoshi")), new FamilyAccount.Fact("森田勇", "member-of", "森田家", "", q, Map.of("role", "head"))),
                List.of(FamilyNameHistoryTest.name("遠藤正一", "遠藤正一", "遠藤", "正一", "birth", "", q), FamilyNameHistoryTest.name("森田勇", "森田勇", "森田", "勇", "birth", "", q)),
                List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q)));
        FamilyNameHistory.Index idx = FamilyNameHistoryTest.index(store);
        String kenji = FamilyNameHistoryTest.id(store, "森田健二");
        assertNull(idx.at(kenji, 1905), idx.names(kenji).toString());
        assertFalse(idx.bearers("森田", 1905).contains(kenji));
        assertFalse(FamilyMentions.boreAtBirth(idx, kenji, "森田"), "his one name is from 1932: " + idx.names(kenji));
    }
}
