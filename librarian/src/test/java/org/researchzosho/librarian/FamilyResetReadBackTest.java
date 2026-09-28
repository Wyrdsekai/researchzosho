package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.PageCheck;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plain reset takes back what was read from the folder, which a new read of the folder brings back, and keeps what came from Geni
 * profiles and web pages, and says so. --all takes those too, and first prints the commands that read them again, a Geni profile with
 * the largest --steps it was read with and a list of addresses as a list. The list of reads compares an address however it is written,
 * `genealogy read --again` with no folder reads the folder read last, and a name nobody has now is found in every copy a reset kept and
 * in the library itself.
 */
class FamilyResetReadBackTest {

    static final String TOM = "https://www.geni.com/people/Tom-Ellis/6000000000001";
    static final String MARY = "https://www.geni.com/people/Mary-Ellis/6000000000002";
    static final String PAGE = "https://example.org/hale-family";

    /** Tom Ellis on Geni, with his wife Mary and their son Tom Hart; and Mary's own profile, with her parents Mary Hart and Tom Hale. */
    static final String TOM_ANSWER = """
            {"focus": {"id": "profile-1", "name": "Tom Ellis", "is_alive": false, "profile_url": "%s",
               "birth": {"date": {"formatted_date": "1901"}, "location": {"city": "York"}}},
             "nodes": {"profile-1": {"id": "profile-1"}, "profile-2": {"id": "profile-2", "name": "Mary Ellis", "is_alive": false},
               "profile-3": {"id": "profile-3", "name": "Tom Hart", "is_alive": false},
               "union-1": {"edges": {"profile-1": {"rel": "partner"}, "profile-2": {"rel": "partner"}, "profile-3": {"rel": "child"}}}}}""".formatted(TOM);
    static final String MARY_ANSWER = """
            {"focus": {"id": "profile-2", "name": "Mary Ellis", "is_alive": false, "profile_url": "%s",
               "birth": {"date": {"formatted_date": "1905"}, "location": {"city": "Hull"}}},
             "nodes": {"profile-2": {"id": "profile-2"}, "profile-5": {"id": "profile-5", "name": "Mary Hart", "is_alive": false},
               "profile-6": {"id": "profile-6", "name": "Tom Hale", "is_alive": false},
               "union-2": {"edges": {"profile-5": {"rel": "partner"}, "profile-6": {"rel": "partner"}, "profile-2": {"rel": "child"}}}}}""".formatted(MARY);

    static final String NOTES = "Tom Ellis worked as a miner in York.\nRuth Ellis is the sister of Tom Ellis.\n";
    static final String HALE_PAGE = "Tom Hale was a teacher in Leeds.";

    private static final List<String> asked = new ArrayList<>(), prompts = new ArrayList<>();

    @BeforeEach void readers() {
        asked.clear(); prompts.clear();
        GenealogyProfile.geniForTests = url -> {
            asked.add(url);
            String body = url.contains("/profile-g6000000000001/") ? TOM_ANSWER : url.contains("/profile-2/") ? MARY_ANSWER : "{\"focus\": {}, \"nodes\": {}}";
            return new Fetch.Result(url, 200, body.getBytes(StandardCharsets.UTF_8), "application/json");
        };
        // the family reader: the notes and the web page each say what they say, and anything else says nothing
        GenealogyProfile.useReader(prompt -> {
            prompts.add(prompt);
            if (prompt.contains("worked as a miner")) return """
                    {"people": [], "facts": [
                     {"subject": "Tom Ellis", "relation": "occupation", "object": "miner", "date": "", "quote": "Tom Ellis worked as a miner in York."},
                     {"subject": "Ruth Ellis", "relation": "sibling-of", "object": "Tom Ellis", "date": "", "quote": "Ruth Ellis is the sister of Tom Ellis."}]}""";
            if (prompt.contains("teacher in Leeds")) return """
                    {"people": [], "facts": [
                     {"subject": "Tom Hale", "relation": "occupation", "object": "teacher", "date": "", "quote": "Tom Hale was a teacher in Leeds."}]}""";
            return "{\"people\": [], \"facts\": []}";
        });
        PageCheck.useGetter((url, timeout, lists) -> url.equals(PAGE)
                ? new Fetch.Result(url, 200, ("<html><head><title>The Hale family</title></head><body><main><p>" + HALE_PAGE + " " + "The page says more about the town. ".repeat(8) + "</p></main></body></html>").getBytes(StandardCharsets.UTF_8), "text/html")
                : new Fetch.Result(url, 404, new byte[0], "text/html"));
    }

    @AfterEach void restore() { GenealogyProfile.geniForTests = null; GenealogyProfile.useReader(null); PageCheck.useGetter(null); }

    static String run(LibraryStore store, String... rest) throws Exception {
        String[] args = new String[rest.length + 2];
        args[0] = "researchzosho"; args[1] = "genealogy";
        System.arraycopy(rest, 0, args, 2, rest.length);
        PrintStream was = System.out, wasErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream both = new PrintStream(out, true, StandardCharsets.UTF_8);
        System.setOut(both); System.setErr(both);
        try { new GenealogyProfile().cli(store, args); } finally { System.setOut(was); System.setErr(wasErr); }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static LibraryStore store(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    /** The family's notes in a folder, read as a whole. */
    private static Path folder(Path tmp, LibraryStore store) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("family-sources"));
        Files.writeString(dir.resolve("notes.txt"), NOTES, StandardCharsets.UTF_8);
        run(store, "read", dir.toString());
        return dir;
    }

    /** A list of one web address, outside the folder, read on its own. */
    private static Path links(Path tmp, LibraryStore store) throws Exception {
        Path list = tmp.resolve("links.txt");
        Files.writeString(list, PAGE + "\n", StandardCharsets.UTF_8);
        run(store, "read", list.toString());
        return list;
    }

    /** The notes read from a folder, Tom Ellis's Geni profile read with --steps 2, and a web page read from a list of addresses. */
    private static Path library(Path tmp, LibraryStore store) throws Exception {
        Path dir = folder(tmp, store);
        run(store, "read", TOM, "--steps", "2");
        links(tmp, store);
        return dir;
    }

    private static List<Finding> from(LibraryStore store, String where) {
        return store.scanFindings().findings().stream().filter(f -> f.sources().stream().allMatch(s -> s.locator().contains(where))).toList();
    }

    private static String norm(Path p) { return p.toAbsolutePath().normalize().toString(); }

    @Test
    void theListOfReadsHasEverySourceWithWhatItWasReadWith(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = library(tmp, store);
        List<String> lines = Files.readAllLines(FamilyReads.ledger(store));
        String notes = norm(dir.resolve("notes.txt")), list = norm(tmp.resolve("links.txt"));
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("\t" + notes) && l.contains("\tfile;folder=" + norm(dir))), "the notes, with the folder they were read with: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("\t" + TOM) && l.contains("\tgeni;") && l.contains(";steps=2")), "the Geni profile, with --steps 2: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("\t" + MARY) && l.contains(";from=")), "the relative's profile read on the way, with the profile it was reached from: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("\t" + PAGE) && l.contains("\tpage;list=" + list)), "the web page, with the list it came from: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("\t" + list) && l.contains("\tlist")), "the list itself: " + lines);
        // a line an older version wrote still says the file was read, and a folder read skips it while it has not changed
        Path letter = Files.writeString(dir.resolve("letter.txt"), "Ruth Ellis is the sister of Tom Ellis.\n", StandardCharsets.UTF_8);
        Files.writeString(FamilyReads.ledger(store), FamilyFolder.sha(letter) + "\t2026-09-20\t" + letter.toAbsolutePath().normalize() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertTrue(FamilyFolder.plan(store, dir).stream().allMatch(FamilyFolder.Item::readBefore), "both files were read before: " + FamilyFolder.plan(store, dir));
        FamilyReads.Index idx = FamilyReads.Index.of(store);
        assertEquals(new FamilyReads.Reread(FamilyReads.FOLDER, norm(dir), 0, "", ""), idx.readOf("file://" + letter.toAbsolutePath().normalize()), "a file an older version read comes back with its folder");
        assertEquals(new FamilyReads.Reread(FamilyReads.GENI, TOM, 2, "", ""), idx.readOf(MARY), "a relative's profile comes back with the first profile and its --steps");
        assertEquals(new FamilyReads.Reread(FamilyReads.LIST, list, 0, "", ""), idx.readOf(PAGE), "a page comes back with its list");
    }

    @Test
    void aPlainResetKeepsWhatCameFromGeniAndWebPagesAndSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = library(tmp, store);
        int all = store.scanFindings().findings().size(), geni = from(store, "geni.com").size();
        assertTrue(geni >= 6, "Tom's birth, his marriage, his son, Mary's birth and parents: " + geni);
        assertEquals(1, from(store, PAGE).size());
        assertEquals(2, from(store, "notes.txt").size());
        String said = run(store, "reset", "--yes");
        int done = said.indexOf("\nDone.");
        assertTrue(done > 0, said);
        String before = said.substring(0, done), after = said.substring(done);
        String folder = norm(dir);
        assertTrue(before.startsWith("This takes out what the library read from your folder " + folder + ", so you can read the folder again from the start.\n"), said);
        assertTrue(before.contains("It will take out:\n  2 facts read from the folder that you have not checked yet.\n"), said);
        assertTrue(before.contains("It keeps:\n  " + (geni + 1) + " facts from Geni and web pages. Reading the folder again would not bring them back.\n"
                + "    " + geni + " facts from 2 Geni profiles.\n"
                + "    1 fact from 1 web page on example.org.\n"
                + "  (To take those out too, use: researchzosho genealogy reset --all)\n"), said);
        assertTrue(before.contains("The library saves a copy first, in your library's family folder: " + store.root().toAbsolutePath().normalize().resolve("family")), said);
        assertFalse(before.contains(" 0 "), "a count of nothing is left out: " + before);
        assertTrue(after.startsWith("\nDone. 2 facts and "), after);
        assertTrue(after.contains(" were taken out. The " + (geni + 1) + " facts that reading the folder again would not bring back stay.\nThe copy is saved in " + store.root().toAbsolutePath().normalize().resolve("family").resolve("backup-")), after);
        assertTrue(after.contains("Next, read the folder again:\n    researchzosho genealogy read \"" + folder + "\"\n"), after);
        assertEquals(geni, from(store, "geni.com").size(), "the Geni facts stay");
        assertEquals(1, from(store, PAGE).size(), "the web page's fact stays");
        assertTrue(from(store, "notes.txt").isEmpty(), "the notes' facts go");
        assertTrue(Files.exists(store.root().resolve("family").resolve("geni-people.tsv")), "which name each Geni profile is filed under stays");
        List<String> lines = Files.readAllLines(FamilyReads.ledger(store));
        assertTrue(lines.stream().noneMatch(l -> l.endsWith("\t" + norm(dir.resolve("notes.txt")))), "the notes are forgotten, so the folder is read again: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("\t" + TOM)) && lines.stream().anyMatch(l -> l.endsWith("\t" + PAGE)), "what stays stays read: " + lines);
        // the folder read again brings back what went, and nothing twice
        asked.clear();
        run(store, "read", dir.toString());
        assertEquals(all, store.scanFindings().findings().size());
        assertTrue(asked.isEmpty(), "nobody asked Geni: " + asked);
    }

    @Test
    void resetAllTakesThemAndPrintsTheCommandsWithTheLargestSteps(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = library(tmp, store);
        run(store, "read", TOM);   // the same profile read again, by itself: the command keeps the larger --steps
        int geni = from(store, "geni.com").size();
        String said = run(store, "reset", "--all", "--yes");
        int done = said.indexOf("\nDone.");
        assertTrue(done > 0, said);
        String before = said.substring(0, done), after = said.substring(done);
        String folder = norm(dir), list = norm(tmp.resolve("links.txt"));
        assertTrue(before.startsWith("This takes out everything the library read for your family: from your folder, from Geni, from web pages and from files elsewhere."), said);
        assertTrue(before.contains("From outside your folder " + folder + ":\n  " + (geni + 1) + " of these facts came from Geni and web pages:\n    " + geni + " facts from 2 Geni profiles.\n    1 fact from 1 web page on example.org.\n"
                + "  Reading the folder again will not bring them back. To get them back after the reset, give these commands. Each one reads one source again:\n"), said);
        String login = "researchzosho records login geni\n        signs you in to Geni. Geni shows nothing without a sign-in, and the sign-in lasts a day. Give this command first.\n";
        String read = "researchzosho genealogy read \"" + TOM + "\" --steps 2\n        reads this Geni profile again and goes on through the relatives' families, 2 profiles in all, as the first read did.";
        String again = "researchzosho genealogy read \"" + list + "\"\n        reads this list of web addresses again, and every page and Geni profile in it.";
        assertTrue(before.contains(login) && before.contains(read) && before.contains(again) && before.indexOf(login) < before.indexOf(read), "the sign-in first, then the profile with its largest steps, and the list: " + said);
        assertTrue(before.indexOf("From outside") < before.indexOf("The library saves a copy first") && !before.contains("Nothing was changed"), said);
        assertTrue(after.contains("researchzosho genealogy read \"" + folder + "\"\n") && after.contains(login) && after.contains(read) && after.contains(again), "the folder and the commands again at the end: " + after);
        assertTrue(after.indexOf("researchzosho genealogy read \"" + folder + "\"") < after.indexOf(read), "the folder first: " + after);
        assertTrue(store.scanFindings().findings().isEmpty(), "everything the family reader wrote is taken back");
        assertFalse(Files.exists(FamilyReads.ledger(store)), "and the list of reads, which the copy keeps");
    }

    @Test
    void anAddressIsOneAddressHoweverItIsWritten(@TempDir Path tmp) throws Exception {
        String inCharacters = "https://www.geni.com/people/森田健二/6000000000003";
        String encoded = "https://www.geni.com/people/" + URLEncoder.encode("森田健二", StandardCharsets.UTF_8) + "/6000000000003";
        assertTrue(encoded.contains("%E6%A3%AE"), encoded);
        assertEquals(FamilyReads.addressKey(inCharacters), FamilyReads.addressKey(encoded));
        assertEquals(FamilyReads.addressKey(inCharacters), FamilyReads.addressKey("http://geni.com/people/Kenji-Morita/6000000000003/"), "a Geni profile is its number");
        assertEquals(FamilyReads.addressKey("https://www.example.org/森田家/"), FamilyReads.addressKey("https://example.org/" + URLEncoder.encode("森田家", StandardCharsets.UTF_8) + "#top"));
        assertNotEquals(FamilyReads.addressKey("https://example.org/a"), FamilyReads.addressKey("https://example.org/b"));
        assertEquals(FamilyReads.addressKey("file:///home/me/family%20sources/notes.txt"), FamilyReads.addressKey("/home/me/family sources/notes.txt"));
        // the profile given written with %-escapes and read with --steps 3, Geni's own address for it in characters, read again with --steps 1
        FamilyReads.Index idx = new FamilyReads.Index(List.of(
                FamilyReads.parse("-\t2026-09-24T10:00:00\tgeni;steps=3\t" + encoded),
                FamilyReads.parse("-\t2026-09-24T11:00:00\tgeni\t" + inCharacters)));
        FamilyReads.Reread r = idx.readOf(inCharacters);
        assertEquals(FamilyReads.GENI, r.kind());
        assertEquals(3, r.steps(), "the same profile read twice keeps the larger --steps");
        assertTrue(r.command().endsWith(" --steps 3"), r.command());
        // through the command: the claims cite Geni's address in characters, the list of reads the address as it was typed
        LibraryStore store = store(tmp);
        GenealogyProfile.geniForTests = url -> new Fetch.Result(url, 200, (url.contains("/profile-g6000000000003/") ? """
                {"focus": {"id": "profile-1", "name": "森田健二", "is_alive": false, "profile_url": "%s", "birth": {"date": {"formatted_date": "1905"}, "location": {"city": "Hiroshima"}}},
                 "nodes": {"profile-1": {"id": "profile-1"}}}""".formatted(inCharacters) : "{\"focus\": {}, \"nodes\": {}}").getBytes(StandardCharsets.UTF_8), "application/json");
        run(store, "read", encoded, "--steps", "2");
        assertFalse(store.scanFindings().findings().isEmpty());
        assertTrue(store.scanFindings().findings().stream().allMatch(f -> f.sources().stream().allMatch(s -> s.locator().equals(inCharacters))), "the claims cite Geni's own address");
        String said = run(store, "reset", "--all");
        assertTrue(said.contains(" from 1 Geni profile.") && said.contains("researchzosho genealogy read \"" + encoded + "\" --steps 2\n"), "read as a Geni profile with its steps, not as a web page: " + said);
        assertTrue(said.contains("Nothing was changed. To go ahead without being asked, add --yes at the end of the command."), "nobody at the keyboard said yes: " + said);
    }

    @Test
    void aGeniLinkInAListOfAddressesIsAGeniReadWithItsSteps(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path list = tmp.resolve("geni-links.txt");
        Files.writeString(list, TOM + "   # my grandfather's profile\n", StandardCharsets.UTF_8);
        run(store, "read", list.toString(), "--steps", "2");
        List<String> lines = Files.readAllLines(FamilyReads.ledger(store));
        assertTrue(lines.stream().anyMatch(l -> l.endsWith("\t" + TOM) && l.contains("\tgeni;list=" + norm(list)) && l.contains(";steps=2")), "a Geni read, with its list and its steps: " + lines);
        assertEquals(new FamilyReads.Reread(FamilyReads.LIST, norm(list), 2, "", ""), FamilyReads.Index.of(store).readOf(MARY));
        String said = run(store, "reset", "--all");
        assertTrue(said.contains(" facts from 2 Geni profiles.\n"), said);
        assertTrue(said.contains("researchzosho records login geni\n") && said.contains("researchzosho genealogy read \"" + norm(list) + "\" --steps 2\n        reads this list of web addresses again, and every page and Geni profile in it. Each Geni profile goes on through the relatives' families, 2 profiles in all"), said);
        assertTrue(said.indexOf("researchzosho records login geni") < said.indexOf("researchzosho genealogy read \"" + norm(list)), said);
    }

    @Test
    void readAgainWithNoFolderReadsTheFolderReadLast(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        assertTrue(run(store, "read", "--again").contains("The library has not read a folder of your family material yet, so there is no folder to read again."));
        Path first = Files.createDirectories(tmp.resolve("aunt-papers"));
        Files.writeString(first.resolve("letter.txt"), "Mary Hart wrote to her sister Ruth Hart.\n", StandardCharsets.UTF_8);
        run(store, "read", first.toString());
        Path dir = folder(tmp, store);
        prompts.clear();
        String said = run(store, "read", "--again");
        assertTrue(said.startsWith("You gave no folder, so the library reads the folder it read last once more: " + norm(dir) + ". It reads every file in it again"), said);
        assertTrue(prompts.stream().anyMatch(p -> p.contains("worked as a miner")) && prompts.stream().noneMatch(p -> p.contains("wrote to her sister")), "the notes of the last folder, and not the other folder: " + prompts.size());
        // after a reset, whose copy keeps the list of reads, the folder read last is still known
        run(store, "reset", "--yes");
        assertTrue(Files.readAllLines(FamilyReads.ledger(store)).stream().noneMatch(l -> l.contains(norm(dir))), "the reset forgot the folder's files");
        prompts.clear();
        run(store, "read", "--again");
        assertTrue(prompts.stream().anyMatch(p -> p.contains("worked as a miner")), "read from the copy's list: " + prompts.size());
        assertEquals(2, from(store, "notes.txt").size());
    }

    @Test
    void theFolderReadLastIsTheFolderThatReadWasGiven(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Files.createDirectories(FamilyReads.ledger(store).getParent());
        Files.writeString(FamilyReads.ledger(store), "a1\t2026-09-20\t/home/me/family/notes.txt\nb2\t2026-09-20\t/home/me/family/letters/letter.txt\n", StandardCharsets.UTF_8);
        assertEquals("/home/me/family", FamilyReads.lastFolder(store), "the lines an older version wrote: the folder that holds them all");
        Files.writeString(FamilyReads.ledger(store), "c3\t2026-09-21T10:00:00\tfile;folder=/home/me/family/letters\t/home/me/family/letters/letter.txt\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertEquals("/home/me/family/letters", FamilyReads.lastFolder(store), "a later read of the inner folder by itself is that folder");
        Files.writeString(FamilyReads.ledger(store), "-\t2026-09-22T10:00:00\tgeni;steps=2\t" + TOM + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertEquals("/home/me/family/letters", FamilyReads.lastFolder(store), "a Geni read is no folder");
    }

    @Test
    void aNameNobodyHasNowIsFoundInAnOlderCopyAndInTheLibrary(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = folder(tmp, store);
        run(store, "read", TOM, "--steps", "2");
        run(store, "reset", "--all", "--yes");   // the first copy holds Tom Hart, whom only Geni gave
        run(store, "read", dir.toString());
        run(store, "reset", "--yes");            // the second copy holds only the notes' facts
        List<Path> copies = FamilyReads.backups(store);
        assertEquals(2, copies.size(), copies.toString());
        assertTrue(FamilyReads.copies(copies.get(0)).stream().noneMatch(f -> f.title().contains("Tom Hart")), "the newest copy does not hold him");
        run(store, "read", dir.toString());
        String gone = "Tom Hart was in your library until the reset on " + LocalDate.now() + ". The facts about Tom Hart came from the Geni profile " + TOM + " and the relatives' profiles read from it. "
                + "To get them back, give researchzosho records login geni, which signs you in to Geni for a day, then researchzosho genealogy read \"" + TOM + "\" --steps 2, which reads this Geni profile again and goes on through the relatives' families, 2 profiles in all, as the first read did.";
        String research = run(store, "research", "Tom Hart");
        assertTrue(research.contains("\"Tom Hart\": not found. Your library has nobody of that name. " + gone), research);
        assertTrue(run(store, "life", "Tom Hart").contains("There is nobody named \"Tom Hart\" in your library. " + gone));
        assertFalse(run(store, "research", "山田太郎").contains("the reset on"), "a name no copy holds says nothing of a reset");
        // in the library itself: the facts about Ruth Ellis are there, retired, so she is nobody of the family now
        Finding ruth = store.scanFindings().findings().stream().filter(f -> f.triple().subject().equals("Ruth Ellis")).findFirst().orElseThrow();
        new Council(store).retire(ruth.id());
        String code = ruth.id().replaceFirst("^(F-\\d+).*", "$1");
        String life = run(store, "life", "Ruth Ellis");
        assertTrue(life.contains("There is nobody named \"Ruth Ellis\" in your library. You retired the 1 fact your library has about Ruth Ellis (" + code + "), so the library leaves Ruth Ellis out of your family."
                + " It came from the file notes.txt in your folder " + norm(dir) + ". Reading that source again will not bring back a fact you retired. If it is right after all, give researchzosho accept " + code + ". The library then uses it again."), life);
        assertFalse(life.contains("the reset on"), "the copies' facts about her are the ones the library holds: " + life);
    }

    @Test
    void aResetKeepsTheFamilysAnswersToTheQuestionsAboutNames(@TempDir Path tmp) throws Exception {
        for (boolean everything : List.of(false, true)) {
            LibraryStore store = store(tmp.resolve(everything ? "all" : "plain"));
            Path dir = folder(tmp.resolve(everything ? "all" : "plain"), store);
            // the family's answer: filed as their word, with the question as its source, and accepted
            Finding.Triple t = new Finding.Triple("Tom Ellis", FamilyNameHistory.PREDICATE, FamilyNameHistory.VALUE + "Tom Hale");
            String id = store.nextFindingId(t.subject() + " " + t.predicate() + " " + t.object());
            store.write(new Finding(id, "Tom Ellis was born Tom Hale", List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.low, "family-account",
                    Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "", List.of(new Finding.Source(FamilyNameQuestions.SOURCE + "N-0001", "as told by the owner", "the family's answer to a question about names and families")),
                    List.of(), null, "Tom Ellis was born Tom Hale.\n", t, List.of()));
            new Council(store).accept(id);
            run(store, "reset", everything ? "--all" : "--yes", "--yes");
            Finding kept = store.finding(id);
            assertNotNull(kept, "the answer stays (" + (everything ? "--all" : "plain") + ")");
            assertEquals(Finding.State.accepted, kept.state());
            assertTrue(from(store, "notes.txt").isEmpty(), "the folder's drafts go");
            assertNotNull(dir);
        }
    }

    @Test
    void aPlainResetTakesBackTheNamesTheFolderGaveAndTheLinksThatRestOnThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        // the folder's two files, which are there to be read again
        Path family = Files.createDirectories(tmp.resolve("family"));
        Path notesFile = Files.writeString(family.resolve("notes.txt"), "遠藤健二 1905年生\n", StandardCharsets.UTF_8), bookFile = Files.writeString(family.resolve("book.txt"), "遠藤の子、森田健二は村の学校に通った。\n", StandardCharsets.UTF_8);
        String notes = "file://" + norm(notesFile), book = "file://" + norm(bookFile), tree = "file:///home/me/elsewhere/tree.ged", geni = "https://www.geni.com/people/Haru-Morita/6000000000009";
        Path ledger = FamilyReads.ledger(store);
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, "a1\t2026-09-20\t" + norm(notesFile) + "\nb2\t2026-09-20\t" + norm(bookFile) + "\n", StandardCharsets.UTF_8);   // as an older version wrote a folder read
        // the tree file elsewhere: 森田健二, born 1905, a child of 遠藤正一, born 1875; the folder's notes: he was born 遠藤健二
        FamilyNameHistoryTest.file(store, tree, List.of(new FamilyAccount.Fact("森田健二", "born-in", "広島", "1905", "森田健二 1905年 広島生"),
                new FamilyAccount.Fact("森田健二", "child-of", "遠藤正一", "", "FAMC @F1@ 遠藤正一"), new FamilyAccount.Fact("遠藤正一", "born-in", "広島", "1875", "遠藤正一 1875年 広島生")), List.of());
        FamilyNameHistoryTest.file(store, notes, List.of(), List.of(FamilyNameHistoryTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "遠藤健二 1905年生")));
        // Geni gives his wife ハル her birth name
        FamilyNameHistoryTest.file(store, geni, List.of(new FamilyAccount.Fact("森田ハル", "married-to", "森田健二", "1930", "Geni: 森田ハル married 森田健二 in 1930")),
                List.of(FamilyNameHistoryTest.name("森田ハル", "髙橋ハル", "髙橋", "ハル", "birth", "1908", "Geni: born 髙橋ハル 1908")));
        // the folder's book writes his father only as 遠藤: the library links the two, and the link rests on the book's words
        FamilyAccount.Outcome son = FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("森田健二", "child-of", "遠藤", "", "遠藤の子、森田健二は村の学校に通った。")), List.of()), book, "an aunt");
        assertEquals(1, son.linked().size(), son.linked().toString());
        String parent = "森田健二's parent (written only as 遠藤)";
        assertEquals(FamilyPeople.view(store).nodeIdOf("遠藤正一"), FamilyPeople.view(store).nodeIdOf(parent));
        String nodesBefore = Files.readString(Graph.nodesFile(store));

        FamilyReset.Plan plan = FamilyReset.folder(store, List.of("family-account", "gedcom-import"));
        assertTrue(plan.folderOnly() && plan.folders().contains(norm(family)), plan.toString());
        FamilyReset.apply(store, plan);
        Graph g = FamilyPeople.view(store);
        assertNotEquals(g.nodeIdOf("遠藤正一"), g.nodeIdOf(parent), "the link that rested on the book's words is taken back");
        assertTrue(Files.readString(Graph.mergesFile(store)).contains("\tgenealogy reset\t"), "by a line of its own");
        List<Finding> left = store.scanFindings().findings();
        assertTrue(left.stream().noneMatch(f -> f.sources().stream().anyMatch(s -> s.locator().equals(notes) || s.locator().equals(book))), "nothing the folder gave stays: " + left.stream().map(Finding::title).toList());
        assertTrue(left.stream().anyMatch(f -> f.triple().predicate().equals(FamilyNameHistory.PREDICATE) && f.triple().object().contains("髙橋ハル")), "Geni's name stays");
        assertTrue(left.stream().anyMatch(f -> f.triple().object().equals("遠藤正一")), "the tree file elsewhere stays");
        String nodes = Files.readString(Graph.nodesFile(store));
        assertTrue(nodesBefore.contains("遠藤健二"), nodesBefore);
        assertFalse(nodes.contains("name: 遠藤健二"), "the name only the notes gave goes: " + nodes);
        assertFalse(nodes.contains("written only as 遠藤"), "and the described person only the book made: " + nodes);
        if (nodesBefore.contains("name: 髙橋ハル")) assertTrue(nodes.contains("name: 髙橋ハル"), "Geni's name keeps its entry: " + nodes);
        assertTrue(FamilyHouses.named(g, "遠藤").stream().noneMatch(id -> FamilyHouses.members(g, id).stream().anyMatch(m -> m.person().contains("written only as"))), "no family keeps the book's member");
    }
}
