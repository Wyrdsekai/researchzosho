package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.Declined;
import org.researchzosho.drive.Judge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.researchzosho.tools.Tool;
import org.researchzosho.tools.WebSearchTool;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When the model the person chose declines, the run says so and does not try to get around it: a declined sub-question is not sent
 * round again in other words, a declined plan or write-up ends the run as declined, the writer's words never come from another model
 * because of a decline, and the report says in its own section what was declined, by which model, and what it said. The questions and
 * the declines here are neutral placeholders.
 */
class ResearcherDeclineTest {
    static final ObjectMapper J = new ObjectMapper();
    static final String DECLINE = "I am not able to help with that request.";
    @TempDir Path tmp;

    static Declined declined() { return new Declined("placeholder-model", DECLINE, Declined.How.WORDS); }

    /** The scripted drive, declining every turn of the worker whose sub-question is {@code declinedSub}. */
    static class DecliningWorker extends ResearcherTest.ScriptedDrive {
        final String declinedSub;
        final AtomicInteger askedOfDeclined = new AtomicInteger();
        DecliningWorker(String declinedSub) { this.declinedSub = declinedSub; }
        @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
            if (!names(tools).contains("write_section") && history.toString().contains(declinedSub)) { askedOfDeclined.incrementAndGet(); histories.add(history.deepCopy()); throw declined(); }
            return super.chat(history, tools, maxTokens, toolChoice);
        }
    }

    @Test
    void aDeclinedPlanEndsTheRunAsDeclinedAndNoWorkerIsAsked() {
        ResearcherTest.ScriptedDrive workers = new ResearcherTest.ScriptedDrive();
        ResearcherTest.ScriptedDrive judge = new ResearcherTest.ScriptedDrive() {
            @Override public String classify(ArrayNode messages, int maxTokens) { classifies.add("x"); throw declined(); }
        };
        var r = new Researcher(workers, judge, new ResearcherTest.FakeTools(), null, 2, null).run(new Researcher.Ask("Placeholder question about the placeholder topic", "broad", 60, List.of()), "");
        assertTrue(workers.offered.isEmpty(), "no worker was asked: the question was not researched as its own sub-question");
        assertEquals(1, judge.classifies.size(), "asked once: the plan is not tried another way");
        assertNotNull(r.ended(), "the run ended because the model declined");
        assertEquals("to plan this research", r.ended().step());
        assertEquals("", r.answer());
        assertEquals(List.of(r.ended()), r.declines());
    }

    @Test
    void aDeclinedSubQuestionStopsThatSubQuestionOnlyAndIsNeverSentRoundAgain() {
        DecliningWorker drive = new DecliningWorker("who cut them?");
        drive.criticWantsMore = false;
        var r = new Researcher(drive, new ResearcherTest.FakeTools(), null, 2).run(new Researcher.Ask("How were the placeholder gears cut, and by whom?", "broad", 60,
                List.of("how were the gears cut?", "who cut them?")), "");
        assertNull(r.ended(), "one sub-question declined: the others go on");
        assertEquals(1, drive.askedOfDeclined.get(), "the declined sub-question was asked once, and never again in other words");
        assertFalse(r.evidence().contains("SUB-QUESTION: who cut them?"), r.evidence());
        assertTrue(r.evidence().contains("SUB-QUESTION: how were the gears cut?"));
        assertTrue(drive.histories.stream().noneMatch(h -> h.toString().contains("search differently")), "the critic did not send it round again");
        assertEquals(1, r.declines().size());
        assertEquals("to research the sub-question \"who cut them?\"", r.declines().get(0).step());
        assertTrue(r.done());
        assertTrue(r.answer().contains("## Declined"), r.answer());
        assertTrue(r.answer().contains("placeholder-model") && r.answer().contains("\"who cut them?\"") && r.answer().contains(DECLINE) && r.answer().contains("did not try to get around"), r.answer());
    }

    @Test
    void aGapInTheWordsOfADeclinedSubQuestionIsNotSentRound() {
        DecliningWorker drive = new DecliningWorker("who cut them?") {
            @Override public String classify(ArrayNode messages, int maxTokens) {
                if (messages.get(messages.size() - 1).path("content").asText().contains("reviewing research COVERAGE"))
                    return "{\"sufficient\": false, \"missing\": [\"who was it that cut them?\", \"what tools survive?\"]}";
                return super.classify(messages, maxTokens);
            }
        };
        var r = new Researcher(drive, new ResearcherTest.FakeTools(), null, 2).run(new Researcher.Ask("How were the placeholder gears cut, and by whom?", "broad", 60,
                List.of("how were the gears cut?", "who cut them?")), "");
        assertTrue(drive.histories.stream().noneMatch(h -> h.toString().contains("who was it that cut them?")), "the declined sub-question in other words was not researched");
        assertTrue(r.evidence().contains("SUB-QUESTION: what tools survive?"), "a gap the model did not decline is researched: " + r.evidence());
    }

    @Test
    void aGapThatAsksForADeclinedSubQuestionInOtherWordsIsNotSentRound() {
        // M8: "which craftsmen made the gears?" shares no words with the declined "who cut them?"; the typed judge of the judge seat is asked
        // whether it asks for the same thing, is sure it does, and the gap is not researched. The other gap is.
        DecliningWorker workers = new DecliningWorker("who cut them?");
        List<String> asked = new CopyOnWriteArrayList<>();
        ResearcherTest.ScriptedDrive critic = new ResearcherTest.ScriptedDrive() {
            @Override public String classify(ArrayNode messages, int maxTokens) {
                if (messages.get(messages.size() - 1).path("content").asText().contains("reviewing research COVERAGE"))
                    return criticRounds++ == 0 ? "{\"sufficient\": false, \"missing\": [\"which craftsmen made the gears?\", \"what tools survive?\"]}" : "{\"sufficient\": true}";
                return super.classify(messages, maxTokens);
            }
        };
        DeclineJudge typed = new DeclineJudge(new Judge("m", body -> {
            String state = body.path("messages").path(0).path("content").asText();
            if (!state.contains(DeclineJudge.SAME_QUESTION)) return DeclinesTest.judged(0.02);
            asked.add(state);
            return DeclinesTest.judged(state.contains("craftsmen") ? 0.96 : 0.03);
        }), null);
        var r = new Researcher(workers, Declines.watch(critic, "judge", "placeholder-model", typed, null), new ResearcherTest.FakeTools(), null, 2, null)
                .run(new Researcher.Ask("How were the placeholder gears cut, and by whom?", "broad", 60, List.of("how were the gears cut?", "who cut them?")), "");
        assertTrue(workers.histories.stream().noneMatch(h -> h.toString().contains("which craftsmen made the gears?")), "the declined sub-question in other words was not researched");
        assertTrue(r.evidence().contains("SUB-QUESTION: what tools survive?"), "a gap that asks for something else is researched: " + r.evidence());
        assertTrue(asked.stream().anyMatch(st -> st.contains("THE DECLINED QUESTION:\nwho cut them?") && st.contains("THE NEW QUESTION:\nwhich craftsmen made the gears?")), asked.toString());
    }

    @Test
    void aDeclineIsNeverRetriedWhateverWordsItQuotes() {
        // a decline that quotes words the retry for an over-long request looks for is still a decline: asked once
        AtomicInteger asked = new AtomicInteger();
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                if (!names(tools).contains("write_section") && history.toString().contains("who cut them?")) { asked.incrementAndGet(); throw new Declined("placeholder-model", "The prompt is too long for me to help with.", Declined.How.WORDS); }
                return super.chat(history, tools, maxTokens, toolChoice);
            }
        };
        drive.criticWantsMore = false;
        new Researcher(drive, new ResearcherTest.FakeTools(), null, 2).run(new Researcher.Ask("How were the placeholder gears cut, and by whom?", "broad", 60,
                List.of("how were the gears cut?", "who cut them?")), "");
        assertEquals(1, asked.get());
    }

    @Test
    void aDeclineInWordsIsNotNudgedAndOrdinaryProseStillIs() {
        // the worker seat as a run builds it, watched, with a judge that is sure of the decline and sure the other prose is not one
        ResearcherTest.ScriptedDrive inner = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                histories.add(history.deepCopy());
                if (!names(tools).contains("write_section") && assistantTurns(history) == 0) {
                    ObjectNode m = J.createObjectNode(); m.put("role", "assistant");
                    m.put("content", history.toString().contains("who cut them?") ? DECLINE : "Thinking about where to look first.");
                    return m;
                }
                return super.chat(history, tools, maxTokens, toolChoice);
            }
        };
        inner.criticWantsMore = false;
        DeclineJudge judge = new DeclineJudge(new Judge("m", body -> DeclinesTest.judged(body.toString().contains(DECLINE) ? 0.95 : 0.03)), null);
        Researcher.Drive watched = Declines.watch(inner, "workers", "placeholder-model", judge, null);
        var r = new Researcher(watched, new ResearcherTest.FakeTools(), null, 2).run(new Researcher.Ask("How were the placeholder gears cut, and by whom?", "broad", 60,
                List.of("how were the gears cut?", "who cut them?")), "");
        List<ArrayNode> declinedHistories = inner.histories.stream().filter(h -> h.toString().contains("who cut them?") && !h.toString().contains("THE ASK")).toList();
        assertEquals(1, declinedHistories.size(), "the declined worker was asked once: " + declinedHistories);
        assertTrue(inner.histories.stream().filter(h -> h.toString().contains("how were the gears cut?") && !h.toString().contains("who cut them?")).anyMatch(h -> h.toString().contains("Act by calling a tool.")),
                "prose that is not a decline is still asked to act");
        assertEquals(1, r.declines().size());
    }

    @Test
    void whatTheModelCouldNotEstablishIsNotADecline() {
        // H2: a worker that reports what it could not establish, a writer whose caveats say so, and a section about refusals that cites its
        // sources: with a judge leaning yes (p 0.7, short of the bar a run-ending decline needs), the run is not declined and the question
        // the judge is asked separates "will not" from "could not"
        List<String> sent = new CopyOnWriteArrayList<>();
        ResearcherTest.ScriptedDrive inner = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                histories.add(history.deepCopy());
                int turn = assistantTurns(history) + 1;
                if (names(tools).contains("write_section") || history.toString().contains("THE ASK:")) {
                    return switch (turn) {
                        case 1 -> call("write_section", J.createObjectNode().put("heading", "Answer").put("text", "The placeholder gears were cut by hand (https://example.org/gears)."));
                        case 2 -> call("write_section", J.createObjectNode().put("heading", "Limitations").put("text", "Two pages refused access to automated readers, and the court refused the appeal [1]."));
                        default -> call("done", J.createObjectNode().put("summary", "I could not establish who cut them; I cannot tell from these sources."));
                    };
                }
                if (history.toString().contains("who cut them?")) return call("done", J.createObjectNode().put("summary", "I cannot tell from these sources who cut them; the page refused automated readers."));
                return super.chat(history, tools, maxTokens, toolChoice);
            }
        };
        inner.criticWantsMore = false;
        DeclineJudge judge = new DeclineJudge(new Judge("m", body -> { sent.add(body.path("messages").path(0).path("content").asText()); return DeclinesTest.judged(0.7); }), null);
        var r = new Researcher(Declines.watch(inner, "workers", "placeholder-model", judge, null), new ResearcherTest.FakeTools(), null, 2)
                .run(new Researcher.Ask("How were the placeholder gears cut, and by whom?", "broad", 60, List.of("how were the gears cut?", "who cut them?")), "");
        assertNull(r.ended(), "not a declined run");
        assertEquals(List.of(), r.declines(), "nothing was declined");
        assertTrue(r.answer().contains("cut by hand"), r.answer());
        assertTrue(sent.stream().allMatch(st -> st.contains("QUESTION: Is this reply the model declining to do the task it was given?")), sent.toString());
        assertTrue(sent.stream().noneMatch(st -> st.contains("I could not establish who cut them")), "the writer's caveats were not asked about: " + sent);
        assertTrue(sent.stream().noneMatch(st -> st.contains("refused the appeal")), "a section that cites its source was not asked about: " + sent);
    }

    @Test
    void aDeclinedWriteUpEndsTheRunAndNoOtherModelWritesIt() {
        ResearcherTest.ScriptedDrive workers = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                if (names(tools).contains("write_section")) throw declined();
                return super.chat(history, tools, maxTokens, toolChoice);
            }
        };
        workers.criticWantsMore = false;
        ResearcherTest.ScriptedDrive judge = new ResearcherTest.ScriptedDrive();
        judge.criticWantsMore = false;
        var r = new Researcher(workers, judge, new ResearcherTest.FakeTools(), null, 1, null).run(new Researcher.Ask("How were the placeholder gears cut?", "broad", 40, List.of("how?")), "");
        assertTrue(judge.classifies.stream().noneMatch(c -> c.startsWith("Answer the ask")), "the judge seat did not write prose in its place: " + judge.classifies);
        assertNotNull(r.ended());
        assertEquals("to write the report", r.ended().step());
        assertEquals("", r.answer());
        assertTrue(r.evidence().contains("cut by hand with files"), "the workers' evidence is kept on the result");
    }

    @Test
    void everySubQuestionDeclinedIsADeclinedRun() {
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                if (!names(tools).contains("write_section")) throw declined();
                return super.chat(history, tools, maxTokens, toolChoice);
            }
        };
        var r = new Researcher(drive, new ResearcherTest.FakeTools(), null, 2).run(new Researcher.Ask("How were the placeholder gears cut, and by whom?", "broad", 60,
                List.of("how were the gears cut?", "who cut them?")), "");
        assertTrue(drive.classifies.stream().noneMatch(c -> c.startsWith("You are reviewing")), "no critic was asked to find other words for what was declined: " + drive.classifies);
        assertTrue(drive.offered.stream().noneMatch(o -> o.contains("write_section")), "nothing to write from: the writer was not asked");
        assertNotNull(r.ended(), "nothing was researched: the model declined every part");
        assertEquals(2, r.declines().size());
    }

    @Test
    void aDeclinedCriticIsSaidAndStopsTheRounds() {
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public String classify(ArrayNode messages, int maxTokens) {
                if (messages.get(messages.size() - 1).path("content").asText().contains("reviewing research COVERAGE")) { classifies.add("critic"); throw declined(); }
                return super.classify(messages, maxTokens);
            }
        };
        var r = new Researcher(drive, new ResearcherTest.FakeTools(), null, 2).run(new Researcher.Ask("How were the placeholder gears cut?", "broad", 60, List.of("how?")), "");
        assertNull(r.ended());
        assertEquals(1, r.rounds());
        assertEquals("to check whether the research covers the question", r.declines().get(0).step());
        assertTrue(r.answer().contains("## Declined"), r.answer());
    }

    @Test
    void theCiteChecksDeclineIsNotCannotTell() {
        Researcher.Drive judge = new ResearcherTest.ScriptedDrive() {
            @Override public String classify(ArrayNode messages, int maxTokens) { throw declined(); }
        };
        assertThrows(Declined.class, () -> CiteCheck.judge(judge, "The placeholder gears were cut by hand.", "The placeholder gears were cut by hand with files."));
        assertThrows(Declined.class, () -> CiteCheck.supportingQuote(judge, "The placeholder gears were cut by hand.", "The placeholder gears were cut by hand with files."));
    }

    @Test
    void aSentenceTheModelDeclinedToCheckKeepsTheMarksAlreadyPlaced() throws Exception {
        // M1: the first sentence's source says otherwise and is marked; the model declines to check the second. The mark stays, the declined
        // sentence is unchecked (not marked), and the outcome carries the decline for the report's Declined section.
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        RawCapture.capture(store, "https://example.org/a", "The placeholder gears were cast in bronze moulds. " + "filler text ".repeat(40), "A", "test", "");
        RawCapture.capture(store, "https://example.org/b", "The placeholder workshop stood by the harbour. " + "filler text ".repeat(40), "B", "test", "");
        var refs = List.of(new CiteCheck.Ref(1, "https://example.org/a", "", "A"), new CiteCheck.Ref(2, "https://example.org/b", "", "B"));
        Researcher.Drive judge = new ResearcherTest.ScriptedDrive() {
            @Override public String classify(ArrayNode messages, int maxTokens) {
                String prompt = messages.get(0).path("content").asText();
                if (prompt.contains("workshop sat near the old town")) throw declined();
                return prompt.contains("\"quote\"") ? "{\"quote\": \"none\"}" : "{\"verdict\": \"unsupported\"}";
            }
        };
        String text = "The placeholder gears were cut by hand with small files and patience [1]. The placeholder workshop sat near the old town square [2].";
        CiteCheck.Outcome out = assertDoesNotThrow(() -> CiteCheck.run(store, text, refs, judge, new Researcher.Budget(20)));
        assertTrue(out.text().contains("patience [1]. [not supported by the cited source on check]"), "the mark placed before the decline stands: " + out.text());
        assertTrue(out.text().contains("old town square [2]."), out.text());
        assertFalse(out.text().contains("square [2]. [not supported"), "the declined sentence is not marked: " + out.text());
        assertEquals(1, out.unsupported());
        assertTrue(out.problems().stream().anyMatch(p -> p.startsWith("the model declined to check the citation of this sentence") && p.contains("old town square")), out.problems().toString());
        assertEquals(DECLINE, out.declined().said());
    }

    @Test
    void aDeclinedRunIsFiledAsDeclinedAndNotAsARefusalToRunAgain() throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        ResearcherTest.ScriptedDrive judge = new ResearcherTest.ScriptedDrive() {
            @Override public String classify(ArrayNode messages, int maxTokens) { throw declined(); }
        };
        var filed = Researcher.file(store, new Researcher(new ResearcherTest.ScriptedDrive(), judge, new ResearcherTest.FakeTools(), null, 1, store),
                new Researcher.Ask("Placeholder question about the placeholder topic", "broad", 40, List.of()), "patron:did:key:test");
        assertFalse(filed.admitted());
        assertTrue(filed.declined());
        assertTrue(filed.reason().startsWith("The model this library uses (placeholder-model) declined to research this question. ResearchZosho did not try to get around it."), filed.reason());
        assertTrue(filed.reason().contains(DECLINE), filed.reason());
        Path frontier = store.frontierFile();   // the queue the explorer reads (a check of catalog/frontier.md read a file nobody writes)
        String open = Files.exists(frontier) ? Files.readString(frontier, StandardCharsets.UTF_8) : "";
        assertFalse(open.contains("re-run on a healthy substrate"), "a decline is not put back to be run again: " + open);
        assertFalse(open.contains("answered from memory"), open);
    }

    @Test
    void aPartlyDeclinedRunTheGateRefusedPutsBackOnlyThePartsTheModelDidNotDecline() throws Exception {
        // L8: the gate refused the run (it read no source), and the whole question went back on the open questions, declined part and all
        LibraryStore store = new LibraryStore(tmp); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        DecliningWorker drive = new DecliningWorker("who cut them?");
        drive.criticWantsMore = false;
        // the search backend degrades while nothing is fetched: the gate's substrate rule refuses the run
        Researcher.Tools degraded = new Researcher.Tools() {
            @Override public List<Tool> web(String focus) {
                return List.of(ResearcherTest.tool("web_search", "search", "query", q -> { WebSearchTool.DEGRADED_EVENTS.incrementAndGet(); return "1. Example source — https://example.org/gears"; }),
                        ResearcherTest.tool("web_fetch", "fetch", "url", u -> "TEXT of " + u.path("url").asText() + ": the placeholder gears were cut by hand with files."));
            }
            @Override public BooleanSupplier exhausted() { return () -> false; }
        };
        var filed = Researcher.file(store, new Researcher(drive, drive, degraded, null, 2, store),
                new Researcher.Ask("How were the placeholder gears cut, and by whom?", "broad", 60, List.of("how were the gears cut?", "who cut them?")), "patron:did:key:test");
        assertFalse(filed.admitted(), "the gate refused it: " + filed.reason());
        assertEquals(1, filed.result().declines().size());
        String open = Files.exists(store.frontierFile()) ? Files.readString(store.frontierFile(), StandardCharsets.UTF_8) : "";
        assertTrue(open.contains("re-run on a healthy substrate — \"how were the gears cut?\""), "the part the model did not decline goes back: " + open);
        assertFalse(open.contains("\"who cut them?\""), "the declined part does not: " + open);
        assertFalse(open.contains("\"How were the placeholder gears cut, and by whom?\" (refused"), "nor the question whole: " + open);
    }

    @Test
    void theStepsOfARunNameWhatWasDeclined() {
        List<String> seen = new CopyOnWriteArrayList<>();
        ResearcherTest.ScriptedDrive judge = new ResearcherTest.ScriptedDrive() {
            @Override public String classify(ArrayNode messages, int maxTokens) {
                String p = messages.get(messages.size() - 1).path("content").asText();
                seen.add(p.substring(0, Math.min(30, p.length())));
                if (p.contains("Split this research request")) throw declined();
                return super.classify(messages, maxTokens);
            }
        };
        var r = new Researcher(new ResearcherTest.ScriptedDrive(), judge, new ResearcherTest.FakeTools(), null, 1, null)
                .run(new Researcher.Ask("Placeholder topic, in a table", "broad", 40, List.of("how?")), "");
        assertEquals(1, seen.size(), "nothing was asked after the declined format split: " + seen);
        assertNotNull(r.ended(), "the format split is part of the plan: " + seen);
        assertEquals("to plan this research", r.ended().step());
    }
}
