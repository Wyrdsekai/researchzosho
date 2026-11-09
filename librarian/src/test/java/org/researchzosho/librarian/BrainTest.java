package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** The brain: a decision is the model's own call read into one of five kinds, recorded with its weight, seat and tier; the seats and the tiers come from the settings and the model's size. */
class BrainTest {
    static final ObjectMapper M = new ObjectMapper();

    /** A model that answers with the given tool calls (name and arguments json), or with words when the name is null. */
    static Brain.Caller scripted(List<ArrayNode> seenTools, String... nameAndArgs) {
        return (history, tools, maxTokens) -> {
            seenTools.add(tools.deepCopy());
            ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
            if (nameAndArgs.length == 1 && nameAndArgs[0] != null && !nameAndArgs[0].startsWith("{")) { m.put("content", nameAndArgs[0]); return m; }
            m.put("content", "because the question needs it");
            ArrayNode calls = m.putArray("tool_calls");
            for (int i = 0; i + 1 < nameAndArgs.length; i += 2) {
                ObjectNode tc = calls.addObject(); tc.put("id", "c" + i); tc.put("type", "function");
                tc.putObject("function").put("name", nameAndArgs[i]).put("arguments", nameAndArgs[i + 1]);
            }
            return m;
        };
    }

    static Researcher.Drive drive(String name) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", name); return m; }
            @Override public String classify(ArrayNode messages, int maxTokens) { return name; }
            @Override public int contextWindow() { return 8192; }
        };
    }

    static Instrument fake(String name, String example) {
        return new Instrument() {
            @Override public String name() { return name; }
            @Override public String words() { return name + " answers · pays when asked · costs nothing"; }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("query!", "string"); }
            @Override public String example() { return example; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) { return Result.of(name, "", "ran " + name); }
        };
    }

    static Brain.Context context(ArrayNode history) {
        return new Brain.Context(new Researcher.Ask("q", "broad", 0, List.of(), "web", List.of(), 0, List.of(), List.of()), "generic", List.of(), List.of(fake("search", "{\"query\": \"x\"}")), new Researcher.Budget(0), List.of(), history);
    }

    @Test void theSizeInTheModelsNameSetsTheTier() {
        assertEquals(Brain.Tier.SMALL, Brain.tierOf("qwen3.5-9b"));
        assertEquals(Brain.Tier.SMALL, Brain.tierOf("gemma-4-e4b"));
        assertEquals(Brain.Tier.SMALL, Brain.tierOf("Gemma-4-12b-it-Q4_K_M.gguf"));
        assertEquals(Brain.Tier.LARGE, Brain.tierOf("qwen3.8-27b"));
        assertEquals(Brain.Tier.LARGE, Brain.tierOf("qwen3.6-35b-a3b"), "a mixture names its whole size");
        assertEquals(Brain.Tier.LARGE, Brain.tierOf("gpt-oss-20b"));
        assertEquals(Brain.Tier.LARGE, Brain.tierOf("deepseek/deepseek-v4.1-flash"), "no size in the name: large");
        assertEquals(Brain.Tier.LARGE, Brain.tierOf(null));
    }

    @Test void everyKindOfDecisionIsReadFromTheModelsCall() {
        List<ObjectNode> trace = new ArrayList<>();
        Brain b = new Brain(drive("w"), drive("j"), "qwen3.8-27b", "judge-model", Brain.Tier.LARGE, trace::add);
        ArrayNode history = M.createArrayNode();
        Brain.Context c = context(history);
        Set<String> all = Brain.BRAIN_TOOLS;
        List<ArrayNode> seen = new ArrayList<>();
        Brain.Decided d = b.decide(c, Brain.Weight.LIGHT, scripted(seen, "search", "{\"query\":\"gears\"}"), M.createArrayNode(), 500, all);
        assertEquals(1, d.decisions().size());
        assertTrue(d.first() instanceof Brain.Run r && r.instrument().equals("search") && r.args().path("query").asText().equals("gears") && r.callId().equals("c0"), d.first().toString());
        d = b.decide(c, Brain.Weight.LIGHT, scripted(seen, "ask", "{\"question\":\"Which Cambridge?\",\"choices\":[\"Massachusetts\",\"England\"]}"), M.createArrayNode(), 500, all);
        assertTrue(d.first() instanceof Brain.AskPerson a && a.question().equals("Which Cambridge?") && a.choices().equals(List.of("Massachusetts", "England")), d.first().toString());
        d = b.decide(c, Brain.Weight.HEAVY, scripted(seen, "pursue", "{\"goal\":\"law\",\"question\":\"Is the sale disputed in court?\"}"), M.createArrayNode(), 500, all);
        assertTrue(d.first() instanceof Brain.Pursue p && p.goal().equals("law") && p.subQuestion().startsWith("Is the sale"), d.first().toString());
        d = b.decide(c, Brain.Weight.HEAVY, scripted(seen, "file", "{\"question\":\"Who owned it in 1950?\",\"why\":\"it does not bear on the ask\"}"), M.createArrayNode(), 500, all);
        assertTrue(d.first() instanceof Brain.File f && f.openQuestion().startsWith("Who owned") && f.why().contains("bear"), d.first().toString());
        d = b.decide(c, Brain.Weight.LIGHT, scripted(seen, "done", "{\"summary\":\"The gears were cut by hand.\"}"), M.createArrayNode(), 500, all);
        assertTrue(d.first() instanceof Brain.Write w && w.text().equals("The gears were cut by hand."), d.first().toString());
        d = b.decide(c, Brain.Weight.LIGHT, scripted(seen, "Here is my answer in words."), M.createArrayNode(), 500, all);
        assertTrue(d.first() instanceof Brain.Write w2 && w2.text().equals("Here is my answer in words."), d.first().toString());
        // several calls in one turn come back in order, each with its own id
        d = b.decide(c, Brain.Weight.LIGHT, scripted(seen, "note", "{\"claim\":\"a\"}", "note", "{\"claim\":\"b\"}", "done", "{\"summary\":\"s\"}"), M.createArrayNode(), 500, all);
        assertEquals(3, d.decisions().size());
        assertTrue(d.decisions().get(0) instanceof Brain.Run r0 && r0.callId().equals("c0") && d.decisions().get(2) instanceof Brain.Write);
        // every decision is recorded with its weight, seat and tier, and the model's reasons
        assertEquals(7, trace.size());
        ObjectNode first = trace.get(0);
        assertEquals("light", first.path("weight").asText()); assertEquals("qwen3.8-27b", first.path("seat").asText()); assertEquals("large", first.path("tier").asText());
        assertEquals("run", first.path("decisions").get(0).path("kind").asText()); assertEquals("search", first.path("decisions").get(0).path("instrument").asText());
        assertEquals("because the question needs it", first.path("reasons").asText());
        assertEquals("judge-model", trace.get(2).path("seat").asText(), "a heavy decision sits on the judge's seat");
        assertEquals("ask", trace.get(1).path("decisions").get(0).path("kind").asText());
    }

    @Test void theBrainsOwnToolsAreOfferedOnlyWhenTheTurnAllowsThem() {
        Brain b = new Brain(drive("w"), null, "qwen3.8-27b", null, Brain.Tier.LARGE, null);
        Brain.Context c = context(M.createArrayNode());
        ArrayNode loops = M.createArrayNode(); loops.add(Brain.fn("search", "s", Brain.props(M.createObjectNode().put("query", "string"), List.of("query"))));
        List<ArrayNode> seen = new ArrayList<>();
        b.decide(c, Brain.Weight.LIGHT, scripted(seen, "search", "{\"query\": \"x\"}"), loops, 100, Set.of());
        assertEquals(List.of("search"), names(seen.get(0)), "the deadline turn or a chat turn offers nothing of the brain's");
        b.decide(c, Brain.Weight.LIGHT, scripted(seen, "search", "{\"query\": \"x\"}"), loops, 100, Brain.BRAIN_TOOLS);
        assertEquals(Set.of("search", "ask", "pursue", "file"), Set.copyOf(names(seen.get(1))));
        b.decide(c, Brain.Weight.LIGHT, scripted(seen, "search", "{\"query\": \"x\"}"), loops, 100, Set.of(Brain.ASK));
        assertEquals(List.of("search", "ask"), names(seen.get(2)));
        assertEquals(1, loops.size(), "the loop's own list is not changed");
    }

    @Test void anEmptyPickIsRetriedWithTheThinkingOffAndThenSaid() {
        List<ObjectNode> trace = new ArrayList<>();
        List<String> seatCalls = new ArrayList<>();
        Researcher.Drive seat = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return M.createObjectNode(); }
            @Override public ObjectNode decide(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                seatCalls.add("decide");
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", "");
                if (seatCalls.size() == 1) { ObjectNode tc = m.putArray("tool_calls").addObject(); tc.put("id", "r1"); tc.putObject("function").put("name", "search").put("arguments", "{\"query\":\"x\"}"); }
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 8192; }
        };
        Brain b = new Brain(seat, null, "qwen3.5-9b", null, Brain.Tier.SMALL, trace::add);
        Brain.Caller emptyAnswer = (h, t, n) -> { ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", ""); return m; };
        Brain.Decided d = b.decide(context(M.createArrayNode()), Brain.Weight.LIGHT, emptyAnswer, M.createArrayNode(), 200, Set.of());
        assertTrue(d.first() instanceof Brain.Run r && r.instrument().equals("search") && r.callId().equals("r1"), "the retry with the thinking off picked: " + d.first());
        assertEquals(List.of("decide"), seatCalls);
        assertTrue(trace.stream().anyMatch(o -> o.path("retried_thinking_off").asBoolean(false)), trace.toString());
        d = b.decide(context(M.createArrayNode()), Brain.Weight.LIGHT, emptyAnswer, M.createArrayNode(), 200, Set.of());
        assertTrue(d.first() instanceof Brain.Write w && w.text().isEmpty(), "still nothing: no decision, said");
        assertTrue(trace.get(trace.size() - 1).path("no_decision").asBoolean(false), trace.get(trace.size() - 1).toString());
    }

    static List<String> names(ArrayNode tools) { List<String> out = new ArrayList<>(); for (var t : tools) out.add(t.path("function").path("name").asText()); return out; }

    @Test void theSeatsFollowTheWeightAndTheSettingsFallBackToTheWorkers() {
        Researcher.Drive workers = drive("w"), judge = drive("j");
        Brain b = Brain.of(workers, judge, "qwen3.8-27b", "moonshotai/kimi-k3", null);
        assertSame(judge, b.seat(Brain.Weight.HEAVY)); assertSame(workers, b.seat(Brain.Weight.LIGHT));
        assertEquals("moonshotai/kimi-k3", b.seatModel(Brain.Weight.HEAVY)); assertEquals("qwen3.8-27b", b.seatModel(Brain.Weight.LIGHT));
        Brain alone = Brain.of(workers, null, "qwen3.5-9b", null, null);
        assertSame(workers, alone.seat(Brain.Weight.HEAVY)); assertEquals("qwen3.5-9b", alone.seatModel(Brain.Weight.HEAVY));
        assertEquals(Brain.Tier.SMALL, alone.tier()); assertEquals(Brain.Tier.LARGE, b.tier());
    }

    @Test void eachSeatTakesTheWordsForItsOwnModelsSize() {
        List<ObjectNode> trace = new ArrayList<>();
        Brain mixed = Brain.of(drive("w"), drive("j"), "qwen3.5-9b", "deepseek/deepseek-v4.1-flash", trace::add);
        assertEquals(Brain.Tier.SMALL, mixed.tier(Brain.Weight.LIGHT), "a small model reading takes the small words");
        assertEquals(Brain.Tier.LARGE, mixed.tier(Brain.Weight.HEAVY), "and a large one judging takes the large words");
        assertEquals(Brain.Tier.SMALL, mixed.tier(), "the loop's own tier is the light seat's");
        mixed.note("plan", Brain.Weight.HEAVY, List.of("one"), "");
        assertEquals("large", trace.get(trace.size() - 1).path("tier").asText(), "a heavy decision is traced in its seat's tier");
        Brain alone = Brain.of(drive("w"), null, "qwen3.5-9b", null, null);
        assertEquals(Brain.Tier.SMALL, alone.tier(Brain.Weight.HEAVY), "with no judge, the workers' model takes both weights");
        Brain bench = new Brain(drive("w"), null, "qwen3.8-27b", null, Brain.Tier.SMALL, null);
        assertEquals(Brain.Tier.SMALL, bench.tier(Brain.Weight.HEAVY), "the bench's forced tier holds for both seats");
    }

    /** A caller that answers in turn from a list: a string is words, a JSON object is one call of that name. */
    static Brain.Caller sequence(List<ArrayNode> histories, List<String[]> answers) {
        return (history, tools, maxTokens) -> {
            histories.add(history.deepCopy());
            String[] a = answers.get(Math.min(histories.size() - 1, answers.size() - 1));
            ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
            if (a.length == 1) { m.put("content", a[0]); return m; }
            m.put("content", "");
            ObjectNode tc = m.putArray("tool_calls").addObject(); tc.put("id", "c" + histories.size()); tc.put("type", "function");
            tc.putObject("function").put("name", a[0]).put("arguments", a[1]);
            return m;
        };
    }

    static ArrayNode askHistory() {
        ArrayNode h = M.createArrayNode();
        h.addObject().put("role", "system").put("content", "the table");
        h.addObject().put("role", "user").put("content", "find the tote bag video");
        return h;
    }

    @Test void wordsWithNoCallAreAskedForOnceAndThenStandAsTheWrite() {
        List<ObjectNode> traced = new ArrayList<>();
        Brain brain = new Brain(drive("w"), null, "qwen3.8-27b", null, Brain.Tier.LARGE, traced::add);
        ArrayNode tools = M.createArrayNode(); tools.add(Brain.fn("search", "s", Instruments.schema("query!", "string")));
        List<ArrayNode> seen = new ArrayList<>();
        Brain.Decided d = brain.decide(context(askHistory()), Brain.Weight.LIGHT, sequence(seen, List.<String[]>of(new String[]{"I need to check the actual videos first."}, new String[]{"search", "{\"query\": \"tote bag\"}"})), tools, 400, Brain.BRAIN_TOOLS);
        assertTrue(d.first() instanceof Brain.Run r && r.instrument().equals("search"), "the second answer, a call, is the decision: " + d.first());
        assertEquals(2, seen.size(), "asked once more");
        String nudge = seen.get(1).get(seen.get(1).size() - 1).path("content").asText();
        assertEquals(Brain.REASK_CALL, nudge);
        assertEquals("words", traced.get(0).path("reasked").asText());
        // words twice stand as the write they are, as before
        seen.clear(); traced.clear();
        d = brain.decide(context(askHistory()), Brain.Weight.LIGHT, sequence(seen, List.<String[]>of(new String[]{"The answer is video two."}, new String[]{"Still the answer is video two."})), tools, 400, Brain.BRAIN_TOOLS);
        assertTrue(d.first() instanceof Brain.Write w && w.text().contains("Still the answer"), d.first().toString());
        // the chat passes retryEmpty false: its words are its reply, never asked again
        seen.clear();
        d = brain.decide(context(askHistory()), Brain.Weight.LIGHT, sequence(seen, List.<String[]>of(new String[]{"Here is my reply."})), tools, 400, Set.of(), false);
        assertEquals(1, seen.size()); assertTrue(d.first() instanceof Brain.Write);
    }

    @Test void anAskWithNoQuestionIsAskedForOnceAndThenDropped() {
        List<ObjectNode> traced = new ArrayList<>();
        Brain brain = new Brain(drive("w"), null, "qwen3.8-27b", null, Brain.Tier.LARGE, traced::add);
        ArrayNode tools = M.createArrayNode(); tools.add(Brain.fn("search", "s", Instruments.schema("query!", "string")));
        List<ArrayNode> seen = new ArrayList<>();
        Brain.Decided d = brain.decide(context(askHistory()), Brain.Weight.LIGHT, sequence(seen, List.<String[]>of(new String[]{"ask", "{}"}, new String[]{"ask", "{\"question\": \"Which city is it in?\", \"choices\": [\"Boston\", \"Cambridge\"]}"})), tools, 400, Brain.BRAIN_TOOLS);
        assertTrue(d.first() instanceof Brain.AskPerson a && a.question().startsWith("Which city") && a.choices().size() == 2, d.first().toString());
        assertEquals(Brain.REASK_ASK, seen.get(1).get(seen.get(1).size() - 1).path("content").asText(), "the note rides as the tool's result");
        seen.clear(); traced.clear();
        d = brain.decide(context(askHistory()), Brain.Weight.LIGHT, sequence(seen, List.<String[]>of(new String[]{"ask", "{}"}, new String[]{"ask", "{\"choices\": [\"a\"]}"})), tools, 400, Brain.BRAIN_TOOLS);
        assertTrue(d.first() instanceof Brain.Write, "no question twice: nothing is filed, the turn writes: " + d.first());
        assertFalse(d.decisions().stream().anyMatch(x -> x instanceof Brain.AskPerson));
    }

    @Test void aCallThatRepeatsOneAlreadyRunIsAskedForOnce() {
        List<ObjectNode> traced = new ArrayList<>();
        Brain brain = new Brain(drive("w"), null, "qwen3.8-27b", null, Brain.Tier.LARGE, traced::add);
        ArrayNode tools = M.createArrayNode(); tools.add(Brain.fn("search", "s", Instruments.schema("query!", "string"))); tools.add(Brain.fn("open", "o", Instruments.schema("address!", "string")));
        ArrayNode h = askHistory();
        ObjectNode past = h.addObject(); past.put("role", "assistant"); past.put("content", "");
        ObjectNode tc = past.putArray("tool_calls").addObject(); tc.put("id", "c0"); tc.put("type", "function"); tc.putObject("function").put("name", "search").put("arguments", "{\"query\": \"tote bag\", \"where\": \"videos\"}");
        h.addObject().put("role", "tool").put("tool_call_id", "c0").put("content", "1. a video — https://v.example/1");
        List<ArrayNode> seen = new ArrayList<>();
        Brain.Decided d = brain.decide(context(h), Brain.Weight.LIGHT, sequence(seen, List.<String[]>of(new String[]{"search", "{\"where\": \"videos\", \"query\": \"tote bag\"}"}, new String[]{"open", "{\"address\": \"https://v.example/1\"}"})), tools, 400, Brain.BRAIN_TOOLS);
        assertTrue(d.first() instanceof Brain.Run r && r.instrument().equals("open"), d.first().toString());
        String note = seen.get(1).get(seen.get(1).size() - 1).path("content").asText();
        assertTrue(note.startsWith("That result is already in hand: search {\"query\": \"tote bag\", \"where\": \"videos\"}"), note);
        assertEquals("repeat", traced.get(0).path("reasked").asText());
        // the evidence names what an instrument already answered: the same address again is a repeat too
        Brain.Context c = new Brain.Context(new Researcher.Ask("q", "broad", 0, List.of(), "web", List.of(), 0, List.of(), List.of()), "choose", List.of(new Brain.Evidence("look", "https://v.example/1", "silent: found")), List.of(fake("look", "{}")), new Researcher.Budget(0), List.of(), askHistory());
        seen.clear();
        d = brain.decide(c, Brain.Weight.LIGHT, sequence(seen, List.<String[]>of(new String[]{"look", "{\"video\": \"https://v.example/1\", \"what\": \"silent\"}"}, new String[]{"done", "{\"summary\": \"video one, silent, found\"}"})), tools, 400, Brain.BRAIN_TOOLS);
        assertTrue(d.first() instanceof Brain.Write w && w.text().contains("video one"), d.first().toString());
        assertEquals(2, seen.size());
    }

    @Test void aSmallModelSeesAnExampleTurnAndAHeavyDecisionTheGoalQuestionFirst() {
        Brain small = new Brain(drive("w"), null, "qwen3.5-9b", null, Brain.Tier.SMALL, null);
        ArrayNode tools = M.createArrayNode(); tools.add(Brain.fn("search", "s", Instruments.schema("query!", "string")));
        ArrayNode h = askHistory();
        List<ArrayNode> seen = new ArrayList<>();
        small.decide(context(h), Brain.Weight.LIGHT, sequence(seen, List.<String[]>of(new String[]{"search", "{\"query\": \"x\"}"})), tools, 400, Set.of());
        ArrayNode sent = seen.get(0);
        assertEquals("system", sent.get(0).path("role").asText());
        assertEquals("call_example", sent.get(1).path("tool_calls").get(0).path("id").asText(), "an example turn after the system words: a call the model made");
        assertEquals(Brain.EXAMPLE_NOTE, sent.get(2).path("content").asText());
        assertEquals("find the tote bag video", sent.get(3).path("content").asText());
        assertEquals(2, h.size(), "the loop's own history is untouched");
        // a large model gets no example turn; a heavy decision with the sub-goals on the table is asked the goal question first
        Brain large = new Brain(drive("w"), null, "qwen3.8-27b", null, Brain.Tier.LARGE, null);
        seen.clear();
        large.decide(context(askHistory()), Brain.Weight.HEAVY, sequence(seen, List.<String[]>of(new String[]{"search", "{\"query\": \"x\"}"})), tools, 400, Brain.BRAIN_TOOLS);
        sent = seen.get(0);
        assertEquals(3, sent.size());
        assertEquals(Brain.HEAVY_QUESTION, sent.get(2).path("content").asText());
        seen.clear();
        large.decide(context(askHistory()), Brain.Weight.LIGHT, sequence(seen, List.<String[]>of(new String[]{"search", "{\"query\": \"x\"}"})), tools, 400, Brain.BRAIN_TOOLS);
        assertEquals(2, seen.get(0).size(), "a light decision is the history as it is");
        String words = large.preamble(List.of(fake("search", "{}")));
        assertTrue(words.contains("where=shelves") && words.contains("pursued with pursue") && words.contains("A choice is one call"), words);
    }

    @Test void aSmallModelGetsLiteralExamplesAndALargeOneTheReasons() {
        List<Instrument> table = List.of(fake("search", "{\"where\": \"web\", \"query\": \"canvas tote bag\"}"), fake("open", "{\"address\": \"https://example.org/a\"}"));
        String small = new Brain(drive("w"), null, "qwen3.5-9b", null, Brain.Tier.SMALL, null).preamble(table);
        String large = new Brain(drive("w"), null, "qwen3.8-27b", null, Brain.Tier.LARGE, null).preamble(table);
        assertTrue(small.contains("search {\"where\": \"web\", \"query\": \"canvas tote bag\"}") && small.contains("Copy the shape of these calls exactly"), small);
        assertFalse(small.contains("<") , "no placeholder in a small model's words: " + small);
        assertTrue(large.contains("search: pays when asked") && !large.contains("costs nothing") && large.contains("weigh a costly instrument"), "the table says when each pays; the tools' own descriptions say the rest: " + large);
        assertFalse(large.contains("Copy the shape"), large);
        assertTrue(small.length() < large.length() || table.size() < 3, "the small tier's words are the shorter");
    }
}
