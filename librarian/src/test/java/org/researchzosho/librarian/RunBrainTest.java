package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** A research run decides through the brain: the grouped names sit beside the old ones, a worker may pursue a sub-goal or file a question, and what it wanted to ask the person reaches the report. */
class RunBrainTest {
    static final ObjectMapper J = new ObjectMapper();

    static ObjectNode call(String name, ObjectNode args) {
        ObjectNode m = J.createObjectNode(); m.put("role", "assistant"); m.put("content", "because");
        ObjectNode c = m.putArray("tool_calls").addObject();
        c.put("id", "c" + System.nanoTime()); c.put("type", "function");
        ObjectNode f = c.putObject("function"); f.put("name", name); f.put("arguments", args.toString());
        return m;
    }

    /** Each worker: search by place, open the address, pursue a sub-goal, file a question, ask the person, note, done. The writer: one section, done. */
    static class Drive implements Researcher.Drive {
        final List<List<String>> offered = new CopyOnWriteArrayList<>();
        final List<ArrayNode> histories = new CopyOnWriteArrayList<>();
        @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
            List<String> n = new ArrayList<>(); for (JsonNode t : tools) n.add(t.path("function").path("name").asText());
            offered.add(n); histories.add(history.deepCopy());
            if (n.equals(List.of("done"))) return call("done", J.createObjectNode().put("summary", "closed on the deadline"));
            int turn = 0; for (JsonNode m : history) if ("assistant".equals(m.path("role").asText())) turn++;
            turn++;
            if (n.contains("write_section")) return turn == 1 ? call("write_section", J.createObjectNode().put("heading", "Answer").put("text", "The gears were cut by hand (https://example.org/gears).")) : call("done", J.createObjectNode().put("summary", "one source"));
            return switch (turn) {
                case 1 -> call("search", J.createObjectNode().put("where", "web").put("query", "antikythera gears"));
                case 2 -> call("open", J.createObjectNode().put("address", "https://example.org/gears"));
                case 3 -> call("pursue", J.createObjectNode().put("goal", "law").put("question", "Was the wreck's salvage disputed in court?"));
                case 4 -> call("file", J.createObjectNode().put("question", "Who owned the ship?").put("why", "it does not bear on the gears"));
                case 5 -> call("ask", J.createObjectNode().put("question", "Which Antikythera find do you mean?").set("choices", J.createArrayNode().add("the 1901 one").add("the 2012 one")));
                case 6 -> call("note", J.createObjectNode().put("claim", "the gears were cut by hand with files").put("source", "https://example.org/gears").put("quote", "cut by hand with files"));
                default -> call("done", J.createObjectNode().put("summary", "hand-cut, one source"));
            };
        }
        @Override public String classify(ArrayNode messages, int maxTokens) {
            String prompt = messages.get(messages.size() - 1).path("content").asText();
            if (prompt.contains("Decompose")) return "[\"how were the gears cut?\"]";
            if (prompt.contains("reviewing research COVERAGE")) return "{\"sufficient\": true}";
            return "FALLBACK PROSE: the gears were cut by hand (https://example.org/gears).";
        }
        @Override public int contextWindow() { return 32_000; }
        @Override public String model() { return "qwen3.8-27b"; }
    }

    @Test void aWorkerSearchesByPlacePursuesASubGoalAndFilesWhatItCannotSettle() {
        Drive drive = new Drive();
        List<String> logged = new CopyOnWriteArrayList<>();
        var r = new Researcher(drive, drive, new ResearcherTest.FakeTools(), logged::add, 1, null).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 80, List.of()), "");
        // the grouped names and the brain's own are offered beside the old ones on a worker's turn
        List<String> first = drive.offered.get(0);
        assertTrue(first.containsAll(List.of("search", "open", "look", "board", "web_search", "web_fetch", "note", "done", "ask", "pursue", "file")), first.toString());
        // search by place and open by address reached the old tools: the worker's history carries their results
        boolean searched = false, opened = false;
        for (ArrayNode hist : drive.histories) { String h = hist.toString(); if (h.contains("Example source")) searched = true; if (h.contains("TEXT of https://example.org/gears")) opened = true; }
        assertTrue(searched && opened, "search(where=web) and open(address) ran the fake tools");
        // the sub-goal went round as a sub-question of the next round, under its goal's rules
        boolean secondRound = false;
        for (ArrayNode hist : drive.histories) {
            String sys = hist.get(0).path("content").asText(), user = hist.size() > 1 ? hist.get(1).path("content").asText() : "";
            if (sys.contains("THIS SUB-QUESTION CARRIES THE GOAL law") && user.contains("Was the wreck's salvage disputed in court?") && !user.contains("[goal:law]")) secondRound = true;
        }
        assertTrue(secondRound, "the pursued sub-question ran in round two under the law goal, shown without its tag");
        // what was filed, and what the worker wanted to ask, are the report's open questions
        assertTrue(r.openQuestions().contains("filed for later: Who owned the ship? — it does not bear on the gears"), r.openQuestions().toString());
        assertTrue(r.openQuestions().contains("for you to answer: Which Antikythera find do you mean? (the 1901 one | the 2012 one)"), r.openQuestions().toString());
        // the report's section says what was followed and what was left, each with why
        assertTrue(r.answer().contains("- pursued now as law: Was the wreck's salvage disputed in court?") && r.answer().contains("- filed for later: Who owned the ship? — it does not bear on the gears\n"), r.answer());
        assertFalse(r.answer().contains("filed for later: Who owned the ship? — it does not bear on the gears — not researched"), "an entry with its own reason gets no second one");
        assertTrue(logged.stream().anyMatch(l -> l.contains("goal law: its rules join this run")), logged.toString());
        // the brain's own tools are never on a deadline turn (the only-done turn is pinned by the Researcher's own test)
        assertTrue(drive.offered.stream().noneMatch(n -> n.contains("done") && n.size() <= 2 && n.contains("ask")), drive.offered.toString());
    }

    /** A judge on a seat of its own: the plan, the critic and the writer as the workers' stand-in has them, and its answer on a side matter. */
    static class Judge implements Researcher.Drive {
        final Drive words = new Drive();
        final String model, onSideMatter;
        final List<String> asked = new CopyOnWriteArrayList<>();
        Judge(String model, String onSideMatter) { this.model = model; this.onSideMatter = onSideMatter; }
        @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) { return words.chat(history, tools, maxTokens, toolChoice); }
        @Override public String classify(ArrayNode messages, int maxTokens) {
            String prompt = messages.get(messages.size() - 1).path("content").asText();
            if (prompt.contains("proposes to pursue a side matter")) { asked.add(prompt); return onSideMatter; }
            return words.classify(messages, maxTokens);
        }
        @Override public int contextWindow() { return 32_000; }
        @Override public String model() { return model; }
    }

    static boolean wentRoundUnderLaw(Drive workers) {
        for (ArrayNode hist : workers.histories) if (hist.get(0).path("content").asText().contains("THIS SUB-QUESTION CARRIES THE GOAL law")) return true;
        return false;
    }

    @Test void aJudgeOnASeatOfItsOwnDecidesWhetherASideMatterIsPursued() {
        Drive workers = new Drive() { @Override public String model() { return "qwen3.5-9b"; } };
        Judge judge = new Judge("deepseek/deepseek-v4.1-flash", "{\"pursue\": false, \"why\": \"how the gears were cut does not depend on the court case.\"}");
        List<String> logged = new CopyOnWriteArrayList<>();
        var r = new Researcher(workers, judge, new ResearcherTest.FakeTools(), logged::add, 1, null).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 80, List.of()), "");
        assertEquals(1, judge.asked.size(), "the judge was asked once, for the one side matter");
        String prompt = judge.asked.get(0);
        assertTrue(prompt.contains("THE ASK: How were the Antikythera gears cut?") && prompt.contains("THE SIDE MATTER, as the step wrote it: Was the wreck's salvage disputed in court?") && prompt.contains("under the goal law"), prompt);
        assertFalse(wentRoundUnderLaw(workers), "filed by the judge, the side matter did not go round");
        assertTrue(r.openQuestions().contains("filed for later: Was the wreck's salvage disputed in court? — how the gears were cut does not depend on the court case."), r.openQuestions().toString());
        assertTrue(logged.stream().anyMatch(l -> l.contains("sub-goal: the judge files")), logged.toString());

        Drive again = new Drive() { @Override public String model() { return "qwen3.5-9b"; } };
        Judge yes = new Judge("deepseek/deepseek-v4.1-flash", "{\"pursue\": true, \"why\": \"a court record may say who cut them.\"}");
        new Researcher(again, yes, new ResearcherTest.FakeTools(), s -> { }, 1, null).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 80, List.of()), "");
        assertTrue(wentRoundUnderLaw(again), "pursued by the judge, the side matter went round under its goal");

        Drive silent = new Drive() { @Override public String model() { return "qwen3.5-9b"; } };
        Judge mute = new Judge("deepseek/deepseek-v4.1-flash", "");
        new Researcher(silent, mute, new ResearcherTest.FakeTools(), s -> { }, 1, null).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 80, List.of()), "");
        assertTrue(wentRoundUnderLaw(silent), "a judge that gives no answer leaves the step's choice standing");
    }

    @Test void oneModelOnBothSeatsIsNotAskedTwice() {
        Drive workers = new Drive();
        Judge same = new Judge("qwen3.8-27b", "{\"pursue\": false, \"why\": \"never asked\"}");
        new Researcher(workers, same, new ResearcherTest.FakeTools(), s -> { }, 1, null).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 80, List.of()), "");
        assertTrue(same.asked.isEmpty(), "the same model on another seat is no second judgment");
        assertTrue(wentRoundUnderLaw(workers), "the step's own choice stood");
    }
}

