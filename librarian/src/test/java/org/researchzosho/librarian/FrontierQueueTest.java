package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
