package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.researchzosho.librarian.FamilyResearchPlanTest.answers;
import static org.researchzosho.librarian.FamilyResearchPlanTest.cli;
import static org.researchzosho.librarian.FamilyResearchPlanTest.family;
import static org.researchzosho.librarian.FamilyResearchPlanTest.onLine;
import static org.researchzosho.librarian.FamilyResearchPlanTest.started;
import static org.researchzosho.librarian.FamilyResearchPlanTest.waitingList;

/**
 * `genealogy research` looks a person up on the web only when that person is about to be researched, after the person at the keyboard
 * picked them; a plan that is only shown sends nothing anywhere. What it says of the nightly waiting list is what happened, and a question
 * already on the list is never lost. A person whose last search was stopped or failed is on the plan again.
 */
class FamilyResearchLookupTest {

    static final ObjectMapper M = new ObjectMapper();

    @BeforeEach void nobodyAtTheKeyboard() { Interaction.OVERRIDE = false; }
    @AfterEach void back() { Interaction.OVERRIDE = null; Interaction.INPUT = null; }

    /** Ruth Ellis's pages were found and nobody has said yet which of them is hers. */
    static void ruthWaitsForAnAnswer(LibraryStore store) throws Exception {
        FamilyIdentity.find(store, Graph.build(store), "Ruth Ellis", q -> List.of(new FamilyIdentity.Page("https://example.org/a", "Ruth Ellis", "a singer"),
                new FamilyIdentity.Page("https://example.org/b", "Ruth Ellis", "a nurse")), null);
        assertTrue(FamilyIdentity.read(store, "Ruth Ellis").open());
    }

    @Test
    void aPersonWhoWaitsForTheWhoIsWhoAnswerIsOnThePlanAndNothingIsAskedBeforeThePick(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        ruthWaitsForAnAnswer(store);
        String said = cli(store, "research");
        assertTrue(said.contains("  4. "), "everybody is on the plan, the one who waits for the answer too: " + said);
        assertTrue(Pattern.compile("(?m)^  \\d\\. Ruth Ellis\\..*Waits for your answer on who is who").matcher(said).find(), said);
        assertFalse(said.contains("does not offer"), said);
        assertEquals(List.of(), started(store), said);
    }

    @Test
    void aStoppedOrFailedSearchPutsThePersonBackOnThePlan(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        Jobs jobs = new Jobs(store, j -> "");
        String tom = jobs.submit("research", "person", (ObjectNode) M.createObjectNode().put("question", "Tom Ellis: who were the parents?"));
        assertEquals("stopped", jobs.stop(tom, "person"));
        String said = cli(store, "research");
        assertTrue(said.contains("  4. "), "Tom is on the plan again: " + said);
        assertTrue(said.contains("The last search for this person, " + tom + ", was stopped on " + LocalDate.now(ZoneOffset.UTC) + "."), said);
        assertFalse(said.contains("a search that is finished, running or about to run"), said);
        // named, he is started without --again
        String named = cli(store, "research", "Tom Ellis");
        assertTrue(Pattern.compile("  Tom Ellis: started now, as J-\\d{4}\\. The last search for this person, " + tom + ", was stopped on ").matcher(named).find(), named);
    }

    /** Ruth's question on the waiting list was written before a fact about her was disputed. */
    static void ruthsQuestionIsOutdated(LibraryStore store) throws Exception {
        store.frontier("person me " + FamilyReset.FROM_TREE, "Ruth Ellis (died 1975, sister of Tom Ellis): who were her parents, as the old question put it?");
        Finding f = store.scanFindings().findings().stream().filter(x -> x.triple() != null && x.triple().subject().equals("Ruth Ellis") && x.triple().predicate().equals("died-on")).findFirst().orElseThrow();
        store.write(new Finding(f.id(), f.title(), f.subjects(), Finding.State.disputed, f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(),
                f.volatility(), f.reviewBy(), f.sources(), f.supersedes(), f.review(), f.body(), f.triple(), f.notes()));
    }

    @Test
    void withNobodyAtTheKeyboardAQuestionWrittenAgainStaysOnTheListAndTheOutputSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        ruthsQuestionIsOutdated(store);
        String said = cli(store, "research");
        assertFalse(said.contains("none was put on the nightly waiting list"), said);
        assertTrue(said.contains("nobody new was put on the nightly waiting list"), said);
        assertTrue(said.contains("1 person's question was on the nightly waiting list already and was written again from what the library holds now, so it stays on the list"), said);
        assertEquals(List.of("Ruth Ellis"), waitingList(store), said);
        assertFalse(waitingList(store).isEmpty() || Frontier.read(store).stream().anyMatch(l -> l.open() && l.text().contains("as the old question put it")), "the old question is gone, the new one is there");
    }

    @Test
    void aQuestionWrittenAgainIsNotLostWhenThePersonWaitsForTheWhoIsWhoAnswer(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        ruthsQuestionIsOutdated(store);
        ruthWaitsForAnAnswer(store);
        cli(store, "research");
        assertEquals(List.of("Ruth Ellis"), waitingList(store), "Ruth is still on the waiting list");
        // at the keyboard, picked, and still waiting for the answer: not started, and her question stays
        LibraryStore other = family(tmp.resolve("other"));
        ruthsQuestionIsOutdated(other);
        ruthWaitsForAnAnswer(other);
        answers("");
        String plan = cli(other, "research", "--list");
        int ruth = 0;
        for (int n = 1; n <= 4; n++) if (plan.contains("  " + n + ". Ruth Ellis")) ruth = n;
        assertTrue(ruth > 0, plan);
        answers(ruth + "\nstop\n\n");
        String said = cli(other, "research");
        assertFalse(started(other).contains("Ruth Ellis"), said);
        assertEquals(List.of("Ruth Ellis"), waitingList(other), "her question is not lost: " + said);
    }

    @Test
    void thePickedPersonIsStartedAndOnlyThePlanIsShownBeforeThePick(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        answers("2\n\n");
        String said = cli(store, "research");
        int plan = said.indexOf("THE PEOPLE THE LIBRARY CAN RESEARCH"), first = said.indexOf("First, the library searches the web");
        assertTrue(plan >= 0, said);
        assertTrue(first < 0 || first > plan, "nothing is looked up before the plan: " + said);
        assertEquals(List.of(onLine(said, 2)), started(store), said);
    }

    /** The same four people, none of them looked up on the web yet. */
    static LibraryStore notLookedUp(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Ellis", "born-on", "1880", "", "q"), new FamilyAccount.Fact("Tom Ellis", "died-on", "1950", "", "q"),
                new FamilyAccount.Fact("Ruth Ellis", "sibling-of", "Tom Ellis", "", "q"), new FamilyAccount.Fact("Ruth Ellis", "died-on", "1975", "", "q"), new FamilyAccount.Fact("Tom Ellis", "child-of", "Mary Ellis", "", "q"),
                new FamilyAccount.Fact("Mary Ellis", "died-on", "1930", "", "q"), new FamilyAccount.Fact("Mary Ellis", "child-of", "Ann Hart", "", "q"), new FamilyAccount.Fact("Ann Hart", "died-on", "1901", "", "q")), List.of()),
                "file:///family/notes.txt", "an aunt");
        return store;
    }

    @Test
    void nobodyIsLookedUpForAPlanThatIsOnlyShownAndOnlyThePickedAreLookedUp(@TempDir Path tmp) throws Exception {
        List<String> looked = new CopyOnWriteArrayList<>();
        GenealogyProfile.whoLookup(q -> { looked.add(q); return List.of(); }, null);
        try {
            LibraryStore store = notLookedUp(tmp);
            String said = cli(store, "research");
            assertEquals(List.of(), looked, "a plan only shown sends no name anywhere: " + said);
            assertFalse(said.contains("searches the web"), said);
            LibraryStore picked = notLookedUp(tmp.resolve("picked"));
            answers("2\n\n");
            said = cli(picked, "research");
            String who = onLine(said, 2);
            assertFalse(looked.isEmpty(), said);
            assertTrue(looked.stream().allMatch(q -> q.contains(who)), "only the person picked is looked up: " + looked);
            assertEquals(List.of(who), started(picked), said);
            looked.clear();
            LibraryStore queued = notLookedUp(tmp.resolve("queued"));
            answers("\ny\n");
            said = cli(queued, "research");
            assertEquals(4, waitingList(queued).size(), said);
            assertEquals(4, looked.stream().map(q -> q.replace("\"", "")).distinct().count(), "the people put on the waiting list are about to be researched, so they are looked up: " + looked);
        } finally { GenealogyProfile.whoLookup(null, null); }
    }
}
