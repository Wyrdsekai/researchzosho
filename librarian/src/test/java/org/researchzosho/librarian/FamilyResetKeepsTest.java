package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.PageCheck;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A plain reset takes back only what a new read of the folder brings back, and keeps the rest, saying so: a file inside the folder read
 * on its own, a file that is no longer there, and what the owner typed or said yes to. Every command it prints works as printed.
 */
class FamilyResetKeepsTest {

    static final String NOTES = "Tom Ellis worked as a miner in York.\nRuth Ellis is the sister of Tom Ellis.\n";
    static final String TEACHER = "Tom Hale worked as a teacher in Leeds.";
    static final String BAKER = "Mary Hart wrote that her brother John Hart was a baker in Hull.";
    static final String JOHN = "John Ellis was born in York in 1901.";
    static final String RUTH = "Ruth Ellis was born in Hull in 1903.";
    static final String PAGE = "https://example.org/hale-family";

    private static final List<String> prompts = new ArrayList<>();

    @BeforeEach void reader() {
        prompts.clear();
        GenealogyProfile.useReader(prompt -> {
            prompts.add(prompt);
            List<String> facts = new ArrayList<>();
            if (prompt.contains("worked as a miner")) {
                facts.add("{\"subject\": \"Tom Ellis\", \"relation\": \"occupation\", \"object\": \"miner\", \"date\": \"\", \"quote\": \"Tom Ellis worked as a miner in York.\"}");
                facts.add("{\"subject\": \"Ruth Ellis\", \"relation\": \"sibling-of\", \"object\": \"Tom Ellis\", \"date\": \"\", \"quote\": \"Ruth Ellis is the sister of Tom Ellis.\"}");
            }
            if (prompt.contains("teacher in Leeds")) facts.add("{\"subject\": \"Tom Hale\", \"relation\": \"occupation\", \"object\": \"teacher\", \"date\": \"\", \"quote\": \"" + TEACHER + "\"}");
            if (prompt.contains("baker in Hull")) facts.add("{\"subject\": \"John Hart\", \"relation\": \"occupation\", \"object\": \"baker\", \"date\": \"\", \"quote\": \"" + BAKER + "\"}");
            if (prompt.contains("John Ellis was born")) facts.add("{\"subject\": \"John Ellis\", \"relation\": \"born-in\", \"object\": \"York\", \"date\": \"1901\", \"quote\": \"" + JOHN + "\"}");
            if (prompt.contains("Ruth Ellis was born")) facts.add("{\"subject\": \"Ruth Ellis\", \"relation\": \"born-in\", \"object\": \"Hull\", \"date\": \"1903\", \"quote\": \"" + RUTH + "\"}");
            return "{\"people\": [], \"facts\": [" + String.join(",\n", facts) + "]}";
        });
        PageCheck.useGetter((url, timeout, lists) -> url.equals(PAGE)
                ? new Fetch.Result(url, 200, ("<html><head><title>The Hale family</title></head><body><main><p>" + TEACHER + " " + "The page says more about the town. ".repeat(8) + "</p></main></body></html>").getBytes(StandardCharsets.UTF_8), "text/html")
                : new Fetch.Result(url, 404, new byte[0], "text/html"));
    }

    @AfterEach void restore() { GenealogyProfile.useReader(null); GenealogyProfile.geniForTests = null; PageCheck.useGetter(null); }

    static String run(LibraryStore store, String... rest) throws Exception { return FamilyResetReadBackTest.run(store, rest); }

    private static LibraryStore store(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    /** The family's notes in a folder of this name, read as a whole. */
    private static Path folder(Path tmp, LibraryStore store, String name, String notes) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Files.writeString(dir.resolve("notes.txt"), notes, StandardCharsets.UTF_8);
        run(store, "read", dir.toString());
        return dir;
    }

    /** The facts whose every source is this file or page. */
    private static List<Finding> from(LibraryStore store, String where) {
        return store.scanFindings().findings().stream().filter(f -> !f.sources().isEmpty() && f.sources().stream().allMatch(s -> s.locator().contains(where))).toList();
    }

    private static String norm(Path p) { return p.toAbsolutePath().normalize().toString(); }

    private static List<String> ledger(LibraryStore store) throws Exception { return Files.exists(FamilyReads.ledger(store)) ? Files.readAllLines(FamilyReads.ledger(store)) : List.of(); }

    // f-reset-1
    @Test
    void aFileInsideTheFolderThatWasReadOnItsOwnStaysWithItsOwnCommand(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = folder(tmp, store, "family", NOTES);
        // a table the folder read does not read, and a letter put in the folder after it, each read on its own with --by
        Path census = Files.writeString(dir.resolve("census.csv"), "name,note\nTom Hale,\"" + TEACHER + "\"\n", StandardCharsets.UTF_8);
        Path letter = Files.writeString(dir.resolve("letter.txt"), BAKER + "\n", StandardCharsets.UTF_8);
        run(store, "read", census.toString(), "--by", "Ann Hale");
        run(store, "read", letter.toString(), "--by", "Ann Hale");
        assertEquals(1, from(store, "census.csv").size());
        assertEquals(1, from(store, "letter.txt").size());
        String said = run(store, "reset", "--yes");
        assertEquals(1, from(store, "census.csv").size(), "a folder read never reads a .csv, so the reset keeps its fact: " + said);
        assertEquals(1, from(store, "letter.txt").size(), "the letter was read with --by, which a folder read does not give: " + said);
        assertTrue(from(store, "notes.txt").isEmpty(), "the notes, which the folder read read, go: " + said);
        String keeps = said.substring(said.indexOf("It keeps:"));
        assertTrue(keeps.contains("census.csv") && keeps.contains("letter.txt"), "both are named under It keeps: " + said);
        List<String> lines = ledger(store);
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("\t" + norm(census))) && lines.stream().anyMatch(l -> l.endsWith("\t" + norm(letter))), "they stay on the list of reads: " + lines);
        assertTrue(lines.stream().noneMatch(l -> l.endsWith("\t" + norm(dir.resolve("notes.txt")))), "the notes are forgotten: " + lines);
        // --all takes them, and gives each the command that reads it as it was read; each command, given as printed, brings its fact back
        String all = run(store, "reset", "--all", "--yes");
        for (Path p : List.of(census, letter)) assertTrue(all.contains("researchzosho genealogy read \"" + norm(p) + "\" --by \"Ann Hale\""), all);
        assertTrue(from(store, "census.csv").isEmpty() && from(store, "letter.txt").isEmpty(), all);
        run(store, "read", norm(census), "--by", "Ann Hale");
        run(store, "read", norm(letter), "--by", "Ann Hale");
        assertEquals(1, from(store, "census.csv").size());
        assertEquals(1, from(store, "letter.txt").size());
        assertTrue(prompts.get(prompts.size() - 1).contains("Ann Hale"), "read as Ann Hale's account again");
    }

    // f-reset-2
    @Test
    void aFileThatIsNoLongerInTheFolderKeepsItsFacts(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = Files.createDirectories(tmp.resolve("family"));
        Files.writeString(dir.resolve("notes.txt"), NOTES, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("letter.txt"), BAKER + "\n", StandardCharsets.UTF_8);
        run(store, "read", dir.toString());
        assertEquals(1, from(store, "letter.txt").size());
        Files.move(dir.resolve("letter.txt"), Files.createDirectories(tmp.resolve("elsewhere")).resolve("letter.txt"));
        String said = run(store, "reset", "--yes");
        assertEquals(1, from(store, "letter.txt").size(), "no read of the folder brings the letter back, so its fact stays: " + said);
        assertTrue(from(store, "notes.txt").isEmpty(), said);
        String keeps = said.substring(said.indexOf("It keeps:"));
        assertTrue(keeps.contains("1 fact from a file that is no longer in the folder: letter.txt."), said);
        // a reset of everything takes it: the name is not found, and the text says where the file was, not to read the folder again
        run(store, "reset", "--all", "--yes");
        run(store, "read", dir.toString());
        String life = run(store, "life", "John Hart");
        assertTrue(life.contains("the file " + norm(dir.resolve("letter.txt")) + ", which is no longer there"), life);
        assertFalse(life.contains("researchzosho genealogy read \"" + norm(dir) + "\""), "reading the folder does not bring it back: " + life);
    }

    // f-reset-3
    @Test
    void aFolderWhoseNameIsInDecomposedCharactersIsTheFolder(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String nfd = Normalizer.normalize("家族のデータ", Normalizer.Form.NFD);
        assertNotEquals("家族のデータ", nfd);
        Path dir = folder(tmp, store, nfd, NOTES);
        assertEquals(2, from(store, "notes.txt").size());
        String said = run(store, "reset", "--yes");
        assertFalse(said.contains("outside the folder"), said);
        assertTrue(said.contains("It will take out:\n  2 facts read from the folder that you have not checked yet."), said);
        assertTrue(from(store, "notes.txt").isEmpty(), "the folder's own facts are taken: " + said);
        assertNotNull(dir);
    }

    // f-reset-4
    @Test
    void aFileNameWithAPercentSignDoesNotStopTheReset(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = Files.createDirectories(tmp.resolve("family"));
        Files.writeString(dir.resolve("100% sure notes.txt"), NOTES, StandardCharsets.UTF_8);
        run(store, "read", dir.toString());
        assertEquals(2, from(store, "sure notes.txt").size());
        String one = run(store, "reset", "--from", "100% sure", "--yes");
        assertTrue(one.contains("Done."), one);
        run(store, "read", dir.toString());
        assertEquals(2, from(store, "sure notes.txt").size());
        String said = run(store, "reset", "--yes");
        assertTrue(said.contains("Done."), said);
        assertTrue(from(store, "sure notes.txt").isEmpty(), said);
    }

    // f-reset-5
    @Test
    void theFamilyJoinStaysWithThePersonLinkItCameWith(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        Path dir = Files.createDirectories(tmp.resolve("family"));
        Path notes = Files.writeString(dir.resolve("notes.txt"), "遠藤正一 (Endo Shoichi)\n", StandardCharsets.UTF_8);
        FamilyFolder.markRead(store, notes, FamilyReads.FILE, norm(dir));
        String tree = "file:///elsewhere/tree.ged", page = "https://example.org/morita";
        FamilyNameHistoryTest.file(store, tree, List.of(new FamilyAccount.Fact("森田健二", "born-in", "広島", "1905", "森田健二 1905年 広島生"), new FamilyAccount.Fact("森田健二", "child-of", "遠藤正一", "", "FAMC @F1@ 遠藤正一"),
                new FamilyAccount.Fact("遠藤正一", "born-in", "広島", "1875", "遠藤正一 1875年 広島生")), List.of(FamilyNameHistoryTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "遠藤健二 1905年生")));
        Graph.alias(store, "森田健二", List.of("Morita Kenji"));
        // a page outside the folder writes his father only as Endo: nothing says yet that 遠藤 is read Endo
        FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endo", "", List.of(), "Endo", "")),
                List.of(new FamilyAccount.Fact("Morita Kenji", "child-of", "Endo", "", "Endo's son, Morita Kenji", Map.of("only-family-name", "true"))), List.of()), page, "an aunt");
        assertEquals(1, o.mentions());
        // the folder's notes read 遠藤正一's family name as Endo, and the library links the mention
        FamilyNameHistoryTest.file(store, "file://" + norm(notes), List.of(), List.of(FamilyNameHistoryTest.name("遠藤正一", "遠藤正一", "遠藤", "正一", "birth", "1875", "遠藤正一 (Endo Shoichi)", "Shoichi Endo")));
        List<String> linked = FamilyMentions.again(store);
        assertFalse(linked.isEmpty(), "linked once the reading is known");
        Graph g = FamilyPeople.view(store);
        String mention = "Morita Kenji's parent (written only as Endo)";
        assertEquals(g.nodeIdOf("遠藤正一"), g.nodeIdOf(mention));
        Map<String, String> joined = Graph.merges(store);
        assertEquals(g.nodeIdOf("遠藤 family"), joined.get(Vocabulary.norm("Endo family")), "the Endo family is written as the 遠藤 family: " + joined);
        FamilyReset.Plan plan = FamilyReset.folder(store, List.of("family-account", "gedcom-import"));
        assertFalse(plan.claims().isEmpty(), plan.toString());
        FamilyReset.apply(store, plan);
        Graph after = FamilyPeople.view(store);
        assertEquals(after.nodeIdOf("遠藤正一"), after.nodeIdOf(mention), "the person link rests on the page's words and stays");
        assertEquals(Vocabulary.norm("遠藤 family"), Graph.merges(store).get(Vocabulary.norm("Endo family")), "and so does the family join that came with it: " + Graph.merges(store));
    }

    // f-reset-6
    @Test
    void resetAllSaysTheFolderReadBringsBackTheGeniProfilesOfItsListOfLinks(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        GenealogyProfile.geniForTests = url -> {
            String body = url.contains("/profile-g6000000000001/") ? FamilyResetReadBackTest.TOM_ANSWER : url.contains("/profile-2/") ? FamilyResetReadBackTest.MARY_ANSWER : "{\"focus\": {}, \"nodes\": {}}";
            return new Fetch.Result(url, 200, body.getBytes(StandardCharsets.UTF_8), "application/json");
        };
        Path dir = Files.createDirectories(tmp.resolve("family"));
        Files.writeString(dir.resolve("notes.txt"), NOTES, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("links.txt"), FamilyResetReadBackTest.TOM + "\n", StandardCharsets.UTF_8);
        run(store, "read", dir.toString(), "--steps", "2");
        assertFalse(from(store, "geni.com").isEmpty());
        String said = run(store, "reset", "--all");
        assertFalse(said.contains("will not bring them back"), "the folder read does bring them back: " + said);
        assertTrue(said.contains("researchzosho genealogy read \"" + norm(dir) + "\" --steps 2"), said);
        assertTrue(said.contains("researchzosho records login geni"), said);
    }

    // f-reset-7
    @Test
    void readAgainTakesTheFolderAfterTheFlagToo(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = folder(tmp, store, "family", NOTES);
        prompts.clear();
        String said = run(store, "read", "--again", dir.toString());
        assertFalse(said.contains("no such file"), said);
        assertTrue(prompts.stream().anyMatch(p -> p.contains("worked as a miner")), "the notes are read once more: " + said);
        assertTrue(run(store, "read", "--list", dir.toString()).contains("The library will read these files"));
    }

    // f-reset-8
    @Test
    void severalRetiredFactsEachGetTheirOwnAcceptCommand(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        folder(tmp, store, "family", NOTES + RUTH + "\n");
        List<Finding> ruth = store.scanFindings().findings().stream().filter(f -> f.triple().subject().equals("Ruth Ellis")).toList();
        assertEquals(2, ruth.size(), ruth.toString());
        for (Finding f : ruth) new Council(store).retire(f.id());
        String life = run(store, "life", "Ruth Ellis");
        for (Finding f : ruth) assertTrue(life.contains("researchzosho accept " + FamilyMentions.code(f.id())), "each fact has its own accept command: " + life);
    }

    // f-words-reset-drops-owner-alias
    @Test
    void theOtherNamesTheOwnerGaveStayThroughEveryReset(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = folder(tmp, store, "family", NOTES);
        Graph.alias(store, "Ruth Ellis", List.of("Ruth Hale"));   // researchzosho graph alias "Ruth Ellis" "Ruth Hale"
        String said = run(store, "reset", "--yes");
        assertTrue(said.contains("Everything you checked, answered or told the library yourself."), said);
        run(store, "read", dir.toString());
        Graph g = FamilyPeople.view(store);
        assertEquals(g.nodeIdOf("Ruth Ellis"), g.nodeIdOf("Ruth Hale"), "the owner's other name still leads to her: " + Files.readString(Graph.nodesFile(store)));
        // --all, with Tom's fact checked
        Finding tom = store.scanFindings().findings().stream().filter(f -> f.triple().subject().equals("Tom Ellis")).findFirst().orElseThrow();
        new Council(store).accept(tom.id());
        Graph.alias(store, "Tom Ellis", List.of("Tom Hart"));
        run(store, "reset", "--all", "--yes");
        run(store, "read", dir.toString());
        Graph h = FamilyPeople.view(store);
        assertEquals(h.nodeIdOf("Tom Ellis"), h.nodeIdOf("Tom Hart"), Files.readString(Graph.nodesFile(store)));
        assertEquals(h.nodeIdOf("Ruth Ellis"), h.nodeIdOf("Ruth Hale"), Files.readString(Graph.nodesFile(store)));
    }

    // f-words-resetall-tidy-joins
    @Test
    void resetAllKeepsWhatTheOwnerJoinedInTidy(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = folder(tmp, store, "family", NOTES + JOHN + "\n");
        Graph.merge(store, "John Ellis", "Tom Ellis", "genealogy tidy", "the owner said yes");
        String said = run(store, "reset", "--all", "--yes");
        assertTrue(said.contains("Everything you checked, answered or told the library yourself."), said);
        run(store, "read", dir.toString());
        Graph g = FamilyPeople.view(store);
        assertEquals(g.nodeIdOf("Tom Ellis"), g.nodeIdOf("John Ellis"), "the owner's yes holds: " + said);
    }

    // f-words-service-restart
    @Test
    void theResetPrintsNoCommandThatDoesNotExist(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = folder(tmp, store, "family", NOTES);
        String plain = run(store, "reset", "--yes");
        assertFalse(plain.contains("service restart"), plain);
        run(store, "read", dir.toString());
        String all = run(store, "reset", "--all", "--yes");
        assertFalse(all.contains("service restart"), all);
    }

    // f-words-nothing-to-take-out
    @Test
    void aResetWithNothingToTakeOutSaysSoFirstAndTheListOfReadsIsForgottenAsPromised(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = folder(tmp, store, "family", NOTES);
        for (Finding f : store.scanFindings().findings()) new Council(store).accept(f.id());
        String said = run(store, "reset", "--yes");
        assertFalse(said.contains("There is nothing to take out"), "the list of reads is still there to forget: " + said);
        assertTrue(said.contains("The list of files the library read in the folder"), said);
        assertTrue(ledger(store).stream().noneMatch(l -> l.endsWith("\t" + norm(dir.resolve("notes.txt")))), "the list is forgotten as promised: " + ledger(store));
        String again = run(store, "reset", "--yes");
        assertTrue(again.contains("There is nothing to take out") && !again.contains("It will take out:"), again);
        LibraryStore empty = store(tmp.resolve("empty"));
        String all = run(empty, "reset", "--all", "--yes");
        assertTrue(all.contains("There is nothing to take out") && !all.contains("It will take out:"), all);
        String none = run(store, "reset", "--from", "nosuch", "--yes");
        assertTrue(none.contains("nosuch") && !none.contains("It will take out:"), none);
    }

    // f-words-from-page-note
    @Test
    void resetFromAPageSaysHowToReadThatPageAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = Files.createDirectories(tmp.resolve("family"));
        Files.writeString(dir.resolve("notes.txt"), NOTES, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("links.txt"), PAGE + "\n", StandardCharsets.UTF_8);
        run(store, "read", dir.toString());
        assertEquals(1, from(store, PAGE).size());
        String said = run(store, "reset", "--from", "example.org", "--yes");
        assertFalse(said.contains("the next read of your folder reads it again"), said);
        String command = "researchzosho genealogy read \"" + PAGE + "\"";
        assertTrue(said.contains(command), said);
        assertTrue(from(store, PAGE).isEmpty());
        run(store, "read", PAGE);
        assertEquals(1, from(store, PAGE).size(), "the printed command brings the page's fact back");
    }

    // f-owner-geni-kept-people-split-unasked
    @Test
    void aPersonWhoStaysKeepsTheOtherNamesTheFolderGaveSoTheReadAgainFindsThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        Path dir = Files.createDirectories(tmp.resolve("family"));
        Path notes = Files.writeString(dir.resolve("notes.txt"), "森田ハル was born in 広島 in 1910.\n", StandardCharsets.UTF_8);
        FamilyFolder.markRead(store, notes, FamilyReads.FILE, norm(dir));
        String geni = "https://www.geni.com/people/Haru-Morita/6000000000009";
        FamilyAccount.Read book = new FamilyAccount.Read(List.of(new FamilyAccount.Person("Morita Haru", "", List.of("森田ハル"))),
                List.of(new FamilyAccount.Fact("Morita Haru", "born-in", "広島", "1910", "森田ハル was born in 広島 in 1910.")), List.of());
        FamilyAccount.file(store, book, "file://" + norm(notes), "an aunt");
        FamilyNameHistoryTest.file(store, geni, List.of(new FamilyAccount.Fact("Morita Haru", "child-of", "Morita Isamu", "", "Geni: Morita Haru, child of Morita Isamu")), List.of());
        Graph g = FamilyPeople.view(store);
        assertEquals(g.nodeIdOf("Morita Haru"), g.nodeIdOf("森田ハル"), "one person before the reset");
        FamilyReset.apply(store, FamilyReset.folder(store, List.of("family-account", "gedcom-import")));
        Graph after = FamilyPeople.view(store);
        assertEquals(after.nodeIdOf("Morita Haru"), after.nodeIdOf("森田ハル"), "Geni's person keeps the name the folder gave: " + Files.readString(Graph.nodesFile(store)));
        // the folder read again, as today's reader writes it: the name in characters
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田ハル", "", List.of("Morita Haru"))),
                List.of(new FamilyAccount.Fact("森田ハル", "born-in", "広島", "1910", "森田ハル was born in 広島 in 1910.")), List.of()), "file://" + norm(notes), "an aunt");
        Graph read = FamilyPeople.view(store);
        assertEquals(read.nodeIdOf("Morita Haru"), read.nodeIdOf("森田ハル"), "still one person after the read: " + Files.readString(Graph.nodesFile(store)));
    }

    // f-owner-gone-message-after-reread
    @Test
    void aNameWhoseFileWasReadAgainSinceTheResetIsNotSentRoundInALoop(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = Files.createDirectories(tmp.resolve("family"));
        Path shop = Files.writeString(dir.resolve("morita-shop.txt"), "Endo's son, 森田健二, kept the shop.\n", StandardCharsets.UTF_8);
        String loc = "file://" + norm(shop);
        FamilyFolder.markRead(store, shop, FamilyReads.FILE, norm(dir));
        // as an older build filed it: Endo as a person of that one name
        write(store, "Endo", "parent-of", "森田健二", loc);
        run(store, "reset", "--yes");
        assertTrue(store.scanFindings().findings().isEmpty());
        // read again since: the words now stand under the described person, whom the family said is 遠藤正一
        FamilyFolder.markRead(store, shop, FamilyReads.FILE, norm(dir));
        String mention = "森田健二's parent (written only as Endo)";
        write(store, "森田健二", "child-of", mention, loc);
        write(store, "遠藤正一", "born-in", "広島", "https://www.geni.com/people/Shoichi-Endo/6000000000010");
        Graph.merge(store, mention, "遠藤正一", "genealogy who", "the family said so");
        String life = run(store, "life", "Endo");
        assertFalse(life.contains("--again --only"), "reading the file once more adds nothing: " + life);
        assertTrue(life.contains("morita-shop.txt") && life.contains("遠藤正一"), "it says where the words stand now: " + life);
        assertTrue(life.contains("researchzosho genealogy source \"morita-shop.txt\""), life);
        assertTrue(run(store, "source", "morita-shop.txt").contains(mention), "the command it gives lists what the file gives now");
    }

    private static void write(LibraryStore store, String s, String p, String o, String locator) throws Exception {
        Finding.Triple t = new Finding.Triple(s, p, o);
        String id = store.nextFindingId(s + " " + p + " " + o);
        store.write(new Finding(id, s + " " + p + " " + o, List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.low, "family-account",
                Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "", List.of(new Finding.Source(locator, "n/a", "the family's account")),
                List.of(), null, s + " " + p + " " + o + ".\n", t, List.of()));
    }
}
