package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What `genealogy research` and `genealogy log` say of a person's earlier searches: parked, asked again for a record, searched by hand. */
class FamilyResearchCommandTest {

    private interface Call { void run() throws Exception; }

    private static String out(Call c) throws Exception {
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { c.run(); } finally { System.setOut(was); }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String cli(LibraryStore store, String... words) throws Exception {
        String[] args = new String[words.length + 2];
        args[0] = "researchzosho"; args[1] = "genealogy";
        System.arraycopy(words, 0, args, 2, words.length);
        return out(() -> new GenealogyProfile().cli(store, args));
    }

    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", "q"); }

    @Test
    void aParkedQuestionIsCountedApartFromTheWaitingList(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Ellis", "born-on", "1880"), fact("Tom Ellis", "died-on", "1950"),
                fact("Ruth Ellis", "sibling-of", "Tom Ellis"), fact("Ruth Ellis", "died-on", "1975"), fact("Tom Ellis", "child-of", "Mary Ellis"), fact("Mary Ellis", "died-on", "1930")), List.of()), "file:///family/notes.txt", "an aunt");
        String ruth = FamilyQuestions.around(store, "Ruth Ellis", 6, 3, false).stream().filter(a -> a.person().equals("Ruth Ellis")).findFirst().orElseThrow().question();
        store.frontier("person me (from the family tree)", ruth);
        assertTrue(Frontier.park(store, ruth));
        String said = cli(store, "research", "--skip-living", "--list");
        assertTrue(said.contains("has a question that is parked: Ruth Ellis") && said.contains("researchzosho questions unpark"), said);
        assertFalse(said.contains("already on the waiting list"), "a parked question is not on the list the nightly research takes from: " + said);
    }

    @Test
    void aWaitingQuestionThatGivesAFactDisputedSinceIsTakenOffAndWrittenAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Kimie Endo", "born-in", "Sendai", "1928", "q"), fact("Kimie Endo", "died-on", "1990"),
                fact("Kimie Endo", "child-of", "Tom Ellis"), fact("Tom Ellis", "died-on", "1950"), fact("Ann Hart", "died-on", "1960")), List.of()), "file:///family/notes.txt", "an aunt");
        List<FamilyQuestions.Ask> asks = FamilyQuestions.around(store, "Kimie Endo", 6, 3, false);
        String kimie = asks.stream().filter(a -> a.person().equals("Kimie Endo")).findFirst().orElseThrow().question();
        String tom = asks.stream().filter(a -> a.person().equals("Tom Ellis")).findFirst().orElseThrow().question();
        assertTrue(kimie.contains("Sendai") && tom.contains("child Kimie Endo (born 1928 in Sendai"), "her birthplace is known, and a lead in her father's question: " + tom);
        String other = "Ann Hart (died 1960): who were the parents?";
        for (String q : List.of(kimie, tom, other)) store.frontier("person me (from the family tree)", q);
        // the family disputes the birthplace: the questions written with it would send it as known
        Finding born = store.scanFindings().findings().stream().filter(f -> f.triple().predicate().equals("born-in")).findFirst().orElseThrow();
        Council council = new Council(store);
        council.dispute(born.id(), "the town of her baptism, not of her birth");
        assertEquals(List.of(kimie, tom), council.takenOff());
        assertEquals(List.of(other), fromTree(store), "a question that does not name her waits on");
        // a question an older version wrote after the dispute, with the birthplace in it
        store.frontier("person me (from the family tree)", kimie);
        String said = cli(store, "research", "--list");
        assertTrue(said.contains("1 of them has a question on the waiting list that was written before a fact about them was disputed or retired"), said);
        assertTrue(fromTree(store).contains(kimie), "a list changes nothing");
        // the next research writes it again; she and her father were looked up on the web before, so nothing goes out
        for (String who : List.of("Kimie Endo", "Tom Ellis", "Ann Hart")) FamilyIdentity.find(store, Graph.build(store), who, q -> List.of(), null);
        cli(store, "research", "--now", "0", "--queue");
        List<String> waiting = fromTree(store);
        assertFalse(waiting.contains(kimie), waiting.toString());
        assertTrue(waiting.stream().anyMatch(q -> FamilyQuestions.asked(List.of(q), "Kimie Endo") && !q.contains("Sendai")), waiting.toString());
        assertTrue(waiting.stream().anyMatch(q -> FamilyQuestions.asked(List.of(q), "Tom Ellis") && !q.contains("Sendai")), waiting.toString());
    }

    /** The questions the family research put on the waiting list. */
    private static List<String> fromTree(LibraryStore store) throws Exception {
        return Frontier.read(store).stream().filter(l -> l.open() && !l.parked() && l.kind().contains(FamilyReset.FROM_TREE)).map(Frontier.Line::text).toList();
    }

    @Test
    void whatWasToldOnTheWhoIsWhoPageIsFiledAlsoWhenNobodyNewIsSearched(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Ellis", "died-on", "1950"), fact("Ann Hart", "parent-of", "Tom Ellis"), fact("Ann Hart", "died-on", "1920")), List.of()), "file:///family/notes.txt", "an aunt");
        // both were searched for already, and the family told the page something about Tom afterwards
        for (String who : List.of("Tom Ellis", "Ann Hart")) {
            String q = who + " (died 1950): who were the parents?";
            store.frontier("person me (from the family tree)", q);
            Frontier.markExplored(store, q, "J-0001");
            FamilyIdentity.find(store, Graph.build(store), who, query -> List.of(), null);
        }
        FamilyIdentity.told(store, "Tom Ellis", "Tom Ellis taught at York college from 1975.");
        GenealogyProfile.useReader(prompt -> "{\"people\": [{\"name\": \"Tom Ellis\", \"reading\": \"\", \"also\": []}], \"facts\": [{\"subject\": \"Tom Ellis\", \"relation\": \"occupation\", "
                + "\"object\": \"teacher at York college\", \"date\": \"1975\", \"quote\": \"Tom Ellis taught at York college from 1975.\"}]}");
        try {
            String said = cli(store, "research");
            assertTrue(said.contains("So there is nobody new to start a search for"), said);
            assertTrue(said.contains("you told it something about Tom Ellis"), said);
        } finally { GenealogyProfile.useReader(null); }
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.triple().object().equals("teacher at York college") && f.sources().get(0).locator().startsWith("told://who-is-who/")), "filed as the owner's account");
        assertTrue(FamilyIdentity.toldNotFiled(FamilyIdentity.read(store, "Tom Ellis")).isEmpty(), "and not filed a second time");
    }

    @Test
    void iCannotTellIsAskedByTheNextResearchAlsoForPeopleSearchedBefore(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Hale", "born-on", "1850"), fact("Tom Hale", "died-on", "1920"),
                fact("Tom Hales", "born-on", "1851"), fact("Tom Hales", "died-on", "1921")), List.of()), "file:///family/notes.txt", "an aunt");
        for (String who : List.of("Tom Hale", "Tom Hales")) {
            String q = who + " (born 1850): who were the parents?";
            store.frontier("person me (from the family tree)", q);
            Frontier.markExplored(store, q, "I-0001-an-earlier-report");
        }
        FamilyDecisions.Pair pair = FamilyDecisions.pairs(store).get(0);
        String done = URLDecoder.decode(DecisionsPage.post(store, Patrons.Patron.PERSON, Map.of("kind", "pair", "code", pair.code(), "do", "unsure")), StandardCharsets.UTF_8);
        assertTrue(done.contains("researchzosho genealogy research"), "the banner names the command that asks it: " + done);
        String said = cli(store, "research", "Tom Hale", "--list");
        assertTrue(said.contains("Which record shows whether"), "the next research asks for the record: " + said);
        assertFalse(said.contains("nobody new to start a search for"), said);
        // once asked, it is not asked again at every research
        String asked = FamilyQuestions.around(store, "Tom Hale", 6, 3, false).stream().filter(a -> a.person().equals("Tom Hale")).findFirst().orElseThrow().question();
        store.frontier("person me (from the family tree)", asked);
        Frontier.markExplored(store, asked, "I-0002-the-record-search");
        assertFalse(cli(store, "research", "Tom Hale", "--list").contains("Which record shows whether"));
    }

    @Test
    void aSearchWrittenDownByHandIsInTheLogAtOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Hale", "born-on", "1850"), fact("Tom Hale", "died-on", "1920")), List.of()), "file:///family/notes.txt", "an aunt");
        Looked.add(store, new Looked.Entry(LocalDate.now().toString(), "Tom Hale", "searched by hand for Tom Hale born 1850.", List.of("FamilySearch"), "", "Tom Hale"));
        String said = cli(store, "log", "Tom Hale");
        assertTrue(said.contains("searched by hand for Tom Hale born 1850"), said);
        assertFalse(said.contains("No search has been made"), said);
    }
}
