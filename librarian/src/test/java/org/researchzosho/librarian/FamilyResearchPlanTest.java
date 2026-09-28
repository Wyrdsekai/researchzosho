package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `genealogy research` starts and queues nothing by itself (the owner, 2026-09-24: "it should not start automatically once the going
 * through each person thing is done. the person should get to pick who to start with"): it shows its plan and asks whom to start with.
 * Named people are researched, not their relatives, and each gets one line saying what happened.
 */
class FamilyResearchPlanTest {

    static final ObjectMapper M = new ObjectMapper();

    @BeforeEach void nobodyAtTheKeyboard() { Interaction.OVERRIDE = false; }
    @AfterEach void back() { Interaction.OVERRIDE = null; Interaction.INPUT = null; }

    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", "q"); }

    /** Four people who have died, each looked up on the web before, so that nothing goes out. */
    static LibraryStore family(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Ellis", "born-on", "1880"), fact("Tom Ellis", "died-on", "1950"),
                fact("Ruth Ellis", "sibling-of", "Tom Ellis"), fact("Ruth Ellis", "died-on", "1975"), fact("Tom Ellis", "child-of", "Mary Ellis"), fact("Mary Ellis", "died-on", "1930"),
                fact("Mary Ellis", "child-of", "Ann Hart"), fact("Ann Hart", "died-on", "1901")), List.of()), "file:///family/notes.txt", "an aunt");
        for (String who : List.of("Tom Ellis", "Ruth Ellis", "Mary Ellis", "Ann Hart")) FamilyIdentity.find(store, Graph.build(store), who, q -> List.of(), null);
        return store;
    }

    private interface Call { int run() throws Exception; }

    static String out(Call c) throws Exception {
        PrintStream was = System.out, err = System.err;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        PrintStream both = new PrintStream(o, true, StandardCharsets.UTF_8);
        System.setOut(both); System.setErr(both);
        try { c.run(); } finally { System.setOut(was); System.setErr(err); }
        return o.toString(StandardCharsets.UTF_8);
    }

    static String cli(LibraryStore store, String... words) throws Exception {
        String[] args = new String[words.length + 2];
        args[0] = "researchzosho"; args[1] = "genealogy";
        System.arraycopy(words, 0, args, 2, words.length);
        return out(() -> new GenealogyProfile().cli(store, args));
    }

    /** At the keyboard, answering these lines in turn. */
    static void answers(String typed) { Interaction.OVERRIDE = true; Interaction.INPUT = new BufferedReader(new StringReader(typed)); }

    /** The people a research run was started for, by the first words of its question. */
    static List<String> started(LibraryStore store) throws Exception {
        return new Jobs(store, j -> "").active().stream().map(j -> j.path("args").path("question").asText("")).map(q -> q.replaceFirst("\\s*[(:].*$", "")).toList();
    }

    /** The people on the nightly waiting list, from the family research. */
    static List<String> waitingList(LibraryStore store) throws Exception {
        return Frontier.read(store).stream().filter(l -> l.open() && !l.parked() && l.kind().contains(FamilyReset.FROM_TREE)).map(l -> l.text().replaceFirst("\\s*[(:].*$", "")).toList();
    }

    /** The name on the plan's line {@code n}. */
    static String onLine(String said, int n) {
        Matcher m = Pattern.compile("(?m)^  " + n + "\\. (\\S+ \\S+)\\. ").matcher(said);
        assertTrue(m.find(), "line " + n + " of the plan: " + said);
        return m.group(1);
    }

    @Test
    void withNobodyAtTheKeyboardThePlanIsShownAndNothingStartsOrWaits(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        String said = cli(store, "research");
        assertTrue(said.contains("THE PEOPLE THE LIBRARY CAN RESEARCH") && said.contains("  1. ") && said.contains("  4. "), said);
        assertTrue(said.contains("Not known yet: "), "each line says what is not known yet: " + said);
        assertTrue(said.contains("None of these people was started, and nobody new was put on the nightly waiting list, because nobody was at the keyboard to choose."), said);
        assertTrue(said.contains("    researchzosho genealogy research \"" + onLine(said, 1) + "\""), "the exact command to start chosen people: " + said);
        assertTrue(said.contains("    researchzosho genealogy research --queue"), said);
        assertEquals(List.of(), started(store), "nothing started by itself");
        assertEquals(List.of(), waitingList(store), "nothing put on the waiting list by itself");
    }

    @Test
    void thePersonPicksWhomToStartWithAndEnterPutsNobodyOnTheWaitingList(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        answers("2\n\n");
        String said = cli(store, "research");
        assertTrue(said.contains("Which of them should the library research now?"), said);
        assertEquals(List.of(onLine(said, 2)), started(store), "only the one picked: " + said);
        assertTrue(said.contains("Put the other 3 people on the nightly waiting list?") && said.contains("(y/N)"), said);
        assertEquals(List.of(), waitingList(store), "Enter is no");
        assertTrue(said.contains("THE SEARCH HAS STARTED"), said);
    }

    @Test
    void enterStartsNobodyAndYesPutsTheOthersOnTheWaitingList(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        answers("\ny\n");
        String said = cli(store, "research");
        assertEquals(List.of(), started(store), "Enter is nobody: " + said);
        assertTrue(said.contains("Put these 4 people on the nightly waiting list?"), said);
        assertEquals(4, waitingList(store).size(), "a yes puts them on the list: " + said);
    }

    @Test
    void allStartsEverybodyAndAsksNothingMore(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        answers("all\n");
        String said = cli(store, "research");
        assertEquals(4, started(store).size(), said);
        assertFalse(said.contains("on the nightly waiting list?"), "nobody is left to put on the list: " + said);
    }

    @Test
    void severalNumbersStartThoseAndAYesQueuesTheRest(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        answers("1,3\ny\n");
        String said = cli(store, "research");
        assertEquals(List.of(onLine(said, 1), onLine(said, 3)), started(store), said);
        assertEquals(List.of(onLine(said, 2), onLine(said, 4)), waitingList(store), said);
    }

    @Test
    void anAnswerTheLibraryCannotReadIsAskedAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        answers("9\n1\n\n");
        String said = cli(store, "research");
        assertTrue(said.contains("The library could not read that. Type numbers from 1 to 4"), said);
        assertEquals(List.of(onLine(said, 1)), started(store), said);
        assertEquals(List.of(), waitingList(store), said);
    }

    @Test
    void nowStartsTheFirstOnesWithoutAskingAndQueuesOnlyWithQueue(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        String said = cli(store, "research", "--now", "1");
        assertEquals(List.of(onLine(said, 1)), started(store), said);
        assertEquals(List.of(), waitingList(store), "without --queue nothing waits: " + said);
        assertTrue(said.contains("with --queue at the end"), said);
        LibraryStore other = family(tmp.resolve("other"));
        String queued = cli(other, "research", "--now", "1", "--queue");
        assertEquals(1, started(other).size(), queued);
        assertEquals(3, waitingList(other).size(), queued);
    }

    @Test
    void theNamedPeopleAreResearchedAndNotTheirRelativesEachWithALine(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        // Tom Ellis was researched; Ann Hart's run is running; Ruth Ellis waits on the nightly list; Mary Ellis has nothing yet
        Jobs finisher = new Jobs(store, (job, drive) -> "investigation I-0001-placeholder", List.of(""), 1);
        String tom = finisher.submit("research", "person", (ObjectNode) M.createObjectNode().put("question", "Tom Ellis: who were the parents?"));
        finisher.start();
        try { for (int i = 0; i < 200 && !"done".equals(finisher.get(tom).path("state").asText()); i++) Thread.sleep(25); } finally { finisher.stop(); }
        assertEquals("done", finisher.get(tom).path("state").asText());
        Jobs jobs = new Jobs(store, j -> "");
        String ann = jobs.submit("research", "person", (ObjectNode) M.createObjectNode().put("question", "Ann Hart: who were the parents?"));
        ObjectNode running = jobs.get(ann); running.put("state", "running"); running.put("started_at", Instant.now().toString());
        Files.writeString(jobs.activeDir().resolve(ann + ".json"), M.writeValueAsString(running), StandardCharsets.UTF_8);
        store.frontier("person me " + FamilyReset.FROM_TREE, "Ruth Ellis: who were the parents?");
        String said = cli(store, "research", "Tom Ellis", "Ann Hart", "Ruth Ellis", "Mary Ellis", "Tom Elis");
        assertTrue(said.contains("  Tom Ellis: already researched, as " + tom + ", finished on " + LocalDate.now(ZoneOffset.UTC) + ". To research this person again, add --again at the end of the command."), said);
        assertTrue(said.contains("  Ann Hart: already running, as " + ann + ". Nothing new was started."), said);
        assertTrue(Pattern.compile("  Ruth Ellis: was on the nightly waiting list\\. Started now, as J-\\d{4}, and taken off the list\\.").matcher(said).find(), said);
        assertTrue(Pattern.compile("  Mary Ellis: started now, as J-\\d{4}\\.").matcher(said).find(), said);
        assertTrue(said.contains("  \"Tom Elis\": not found. Your library has nobody of that name.") && said.contains("\"Tom Ellis\""), said);
        assertTrue(said.contains("and not their relatives") && said.contains("--family"), said);
        assertEquals(List.of("Ann Hart", "Ruth Ellis", "Mary Ellis"), started(store), "the two named, beside the run already running; no relative: " + said);
        assertEquals(List.of(), waitingList(store), "Ruth's question is taken off the list, and nobody is put on it: " + said);
        assertFalse(said.contains("THE PEOPLE THE LIBRARY CAN RESEARCH") || said.contains("THE RELATIVES"), "no plan without --family: " + said);
        // --again researches Tom once more
        String again = cli(store, "research", "Tom Ellis", "--again");
        assertTrue(Pattern.compile("  Tom Ellis: started now, as J-\\d{4}\\.").matcher(again).find(), again);
    }

    @Test
    void familyShowsTheRelativesAsAPlanAndStartsOnlyTheNamed(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        String said = cli(store, "research", "Mary Ellis", "--family");
        assertTrue(Pattern.compile("  Mary Ellis: started now, as J-\\d{4}\\.").matcher(said).find(), said);
        assertTrue(said.contains("THE RELATIVES THE LIBRARY CAN RESEARCH") && said.contains("  3. "), said);
        assertTrue(said.contains("researchzosho genealogy research \"Mary Ellis\" --family --now 3"), "the command keeps the names and --family: " + said);
        assertEquals(List.of("Mary Ellis"), started(store), said);
        assertEquals(List.of(), waitingList(store), said);
        // --only, as older versions wrote it, is what the command does anyway
        LibraryStore other = family(tmp.resolve("other"));
        String only = cli(other, "research", "Ann Hart", "--only");
        assertEquals(List.of("Ann Hart"), started(other), only);
    }
}
