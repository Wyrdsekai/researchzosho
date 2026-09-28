package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The open questions as a queue: types, park, order, what the explorer takes, and bundles of related questions. */
class FrontierQueueTest {

    static List<Frontier.Line> open(LibraryStore store) throws Exception {
        List<Frontier.Line> out = new ArrayList<>();
        for (Frontier.Line l : Frontier.read(store)) if (l.open()) out.add(l);
        return out;
    }

    @Test
    void typesAreReadFromNewAndOldSpellings(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Files.createDirectories(store.frontierFile().getParent());
        Files.writeString(store.frontierFile(), String.join("\n",
                "# Frontier",
                "- 2026-09-01 [gap] How does X work? (left open by I-0001-x)",
                "- 2026-09-02 [gap person] What about Y?",
                "- 2026-09-03 [demand ×3 patron:did:key:z] Where is Z?",
                "- 2026-09-04 [dispute] F-0001 — why (what evidence would settle it?)",
                "- 2026-09-05 [dispute inventory] F-0002 — the cited source does not support it: …",
                "- 2026-09-06 [report ·parked] A parked one (left open by I-0001-x)",
                "- 2026-09-07 [asked ×1 patron:did:key:z] Asked once") + "\n");
        List<Frontier.Line> lines = open(store);
        assertEquals(List.of("report", "person", "asked", "dispute", "check", "report", "asked"), lines.stream().map(Frontier.Line::type).toList());
        assertTrue(lines.get(5).parked());
        assertEquals("I-0001-x", lines.get(0).origin());
        // what the explorer takes by default: report, person, and asked ≥ 2 times; never check, dispute, parked, or asked once
        assertEquals(List.of(true, true, true, false, false, false, false), lines.stream().map(Frontier.Line::researchable).toList());
    }

    @Test
    void nextLaterParkUnparkAndDropChangeTheQueue(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.frontier("person", "first"); store.frontier("person", "second"); store.frontier("person", "third");
        assertTrue(Frontier.next(store, "third"));
        assertEquals(List.of("third", "first", "second"), open(store).stream().map(Frontier.Line::text).toList());
        assertTrue(Frontier.later(store, "third"));
        assertEquals(List.of("first", "second", "third"), open(store).stream().map(Frontier.Line::text).toList());
        assertTrue(Frontier.park(store, "second"));
        assertTrue(open(store).get(1).parked() && !open(store).get(1).researchable());
        assertFalse(Frontier.park(store, "second"), "parking twice is a no-op");
        assertTrue(Frontier.unpark(store, "second"));
        assertEquals(List.of("first", "third", "second"), open(store).stream().map(Frontier.Line::text).toList(), "unparked: back at the tail");
        assertFalse(open(store).get(2).parked());
        assertTrue(Frontier.drop(store, "first", "person"));
        assertEquals(List.of("third", "second"), open(store).stream().map(Frontier.Line::text).toList());
        assertFalse(Frontier.next(store, "first"), "a closed line cannot be moved");
        assertTrue(Files.readString(store.frontierFile()).contains("# Frontier"), "the header line stays first");
    }

    @Test
    void relatedQuestionsShareARunAndTheBudgetCountsRuns(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.frontier("report", "What did the 1978 CPSC lead paint rule cover? (left open by I-0009-lead)");
        store.frontier("report", "Which paints did the 1978 lead rule exclude? (left open by I-0009-lead)");
        store.frontier("person", "How do Titan's methane lakes form?");
        store.frontier("report", "When was lead paint banned in Japan? (left open by I-0012-jp)");
        List<Frontier.Line> q = open(store);
        List<Crews.Bundle> plan = Crews.plan(q, 2);
        assertEquals(2, plan.size(), "two runs");
        assertEquals("What did the 1978 CPSC lead paint rule cover?", plan.get(0).question(), "the note a report appended is stripped");
        assertEquals(2, plan.get(0).all().size(), "the two left open by the same report ride together: " + plan.get(0).subQuestions());
        assertEquals("How do Titan's methane lakes form?", plan.get(1).question());
        assertTrue(plan.get(1).more().isEmpty(), "Titan has nothing related");
        // the explorer marks every question in a bundle explored with the same investigation, and takes only its budget
        List<String> runs = new ArrayList<>();
        Crews.Researcher fake = new Crews.Researcher() {
            @Override public String research(String question, String writer) { runs.add(question); return "I-0100"; }
            @Override public String research(String question, List<String> subs, String writer) { runs.add(question + " +" + (subs.size() - 1)); return "I-0100"; }
        };
        String out = Crews.explore(store, fake, 1);
        assertEquals(List.of("What did the 1978 CPSC lead paint rule cover? +1"), runs);
        assertTrue(out.startsWith("1 run(s) for 2 question(s), 1 admitted"), out);
        assertEquals(2, open(store).size(), "the two lead questions left the queue together; Titan and Japan remain");
        assertEquals("How do Titan's methane lakes form?", open(store).get(0).text());
    }

    @Test
    void aParkedQuestionKeepsThePersonsReasonAndTheDate(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.frontier("person", "Who were the parents of 森田正一?");
        assertTrue(Frontier.park(store, "Who were the parents of 森田正一?", "waits on the 戸籍 request to the town hall"));
        String why = Frontier.whyParked(store, "Who were the parents of 森田正一?");
        assertTrue(why.startsWith("waits on the 戸籍 request to the town hall (parked 20"), why);
        var listed = new LibraryProtocol(store).frontierList();
        assertEquals(why, listed.get(0).path("parked_why").asText(), "the list carries it, for the page and the command line");
        assertTrue(Frontier.unpark(store, "Who were the parents of 森田正一?"));
        assertEquals("", Frontier.whyParked(store, "Who were the parents of 森田正一?"), "back in the queue, the reason goes");
        assertTrue(Frontier.park(store, "Who were the parents of 森田正一?"));
        assertEquals("", Frontier.whyParked(store, "Who were the parents of 森田正一?"), "parked without a reason has none");
        assertFalse(new LibraryProtocol(store).frontierList().get(0).has("parked_why"));
    }

    @Test
    void aReasonGoesWithItsWaitAndParkingAParkedQuestionSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String q = "Who were the parents of 森田正一?";
        store.frontier("person", q);
        assertTrue(Frontier.park(store, q, "waits on the 戸籍 request to the town hall"));
        // parked again: it stays parked, and a reason given now replaces the old one
        assertFalse(Frontier.park(store, q, "the town hall answered that it needs a letter"));
        assertTrue(Frontier.isParked(store, q));
        assertTrue(Frontier.whyParked(store, q).startsWith("the town hall answered that it needs a letter (parked "), Frontier.whyParked(store, q));
        var proto = new LibraryProtocol(store);
        var again = proto.frontier(new ObjectMapper().createObjectNode().put("op", "park").put("question", q));
        assertTrue(again.path("parked").asBoolean() && again.path("already").asBoolean(), again.toString());
        // dropped, the reason goes with the question; filed again, it starts with none
        assertTrue(Frontier.drop(store, q, "person"));
        assertEquals("", Frontier.whyParked(store, q), "a dropped question keeps no reason");
        assertFalse(Frontier.park(store, q, "a reason"), "a dropped question is not open: nothing to park");
        assertEquals("", Frontier.whyParked(store, q));
        store.frontier("person", q);
        assertEquals("", Frontier.whyParked(store, q), "filed again, the question has no reason of the earlier one");
        assertTrue(Frontier.park(store, q, "waits on the letter"));
        store.frontier("person", q);
        assertEquals("", Frontier.whyParked(store, q), "filing the question again ends the reason its parked copy had");
        // the command says a parked question is parked already
        String real = System.getProperty("user.home");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LibraryStore home = new LibraryStore(tmp.resolve("home").resolve("researchzosho-library")); home.init();
        home.frontier("person", q);
        Frontier.park(home, q, "waits on the letter");
        System.setProperty("user.home", tmp.resolve("home").toString());
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int rc;
        try { rc = LibrarianCli.run(new String[]{"researchzosho", "questions", "park", q, "--why", "the letter was sent"}, "http://127.0.0.1:1", "m"); }
        finally { System.setOut(was); System.setProperty("user.home", real); }
        assertEquals(0, rc);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("This question is already parked: " + q + "\n  The reason kept with it is now: the letter was sent"), out.toString(StandardCharsets.UTF_8));
        assertTrue(Frontier.whyParked(home, q).startsWith("the letter was sent"));
    }
}
