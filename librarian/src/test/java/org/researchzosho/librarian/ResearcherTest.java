package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.researchzosho.tools.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The library's own overnight runner, scripted end to end without a model or a network: the plan
 * comes from the brief, workers note evidence with locators, the critic owns the second round, the
 * synthesis writes sections, the shared budget is honest, and the shelf admits or refuses.
 */
class ResearcherTest {

    private static final ObjectMapper J = new ObjectMapper();

    /** Fake web tools: a search that lists one URL, a fetch that returns text for it. */
    static final class FakeTools implements Researcher.Tools {
        final AtomicInteger fetches = new AtomicInteger();
        @Override public List<Tool> web(String focus) {
            Tool search = tool("web_search", "search", "query", a -> "1. Example source — https://example.org/" + a.path("query").asText("q").replaceAll("\\W+", "-"));
            Tool fetch = tool("web_fetch", "fetch", "url", a -> { fetches.incrementAndGet(); return "TEXT of " + a.path("url").asText() + ": the Antikythera gears were cut by hand with files."; });
            return List.of(search, fetch);
        }
        @Override public BooleanSupplier exhausted() { return () -> false; }
    }

    interface Exec { String run(JsonNode a) throws Exception; }

    static Tool tool(String name, String desc, String arg, Exec exec) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return desc; }
            @Override public ObjectNode parametersSchema(ObjectMapper j) {
                ObjectNode p = j.createObjectNode(); p.put("type", "object");
                p.putObject("properties").putObject(arg).put("type", "string");
                p.putArray("required").add(arg); return p;
            }
            @Override public String execute(JsonNode a) throws Exception { return exec.run(a); }
        };
    }

    /**
     * A scripted drive. Each worker: search → fetch → note → done. The synthesis: two sections → done.
     * Only-`done` turns are honoured. Every call is recorded (tool names offered + history size).
     */
    static class ScriptedDrive implements Researcher.Drive {
        final List<String> offered = new CopyOnWriteArrayList<>();
        final List<String> classifies = new CopyOnWriteArrayList<>();
        final List<ArrayNode> histories = new CopyOnWriteArrayList<>();
        int criticRounds = 0;
        boolean criticWantsMore = true;

        static List<String> names(ArrayNode tools) {
            List<String> n = new ArrayList<>();
            for (JsonNode t : tools) n.add(t.path("function").path("name").asText());
            return n;
        }

        static int assistantTurns(ArrayNode history) {
            int n = 0;
            for (JsonNode m : history) if ("assistant".equals(m.path("role").asText())) n++;
            return n;
        }

        static ObjectNode call(String name, ObjectNode args) {
            ObjectNode m = J.createObjectNode(); m.put("role", "assistant"); m.putNull("content");
            ObjectNode c = m.putArray("tool_calls").addObject();
            c.put("id", "c" + System.nanoTime()); c.put("type", "function");
            ObjectNode f = c.putObject("function"); f.put("name", name); f.put("arguments", args.toString());
            return m;
        }

        @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
            List<String> n = names(tools);
            offered.add(String.join(",", n));
            histories.add(history.deepCopy());
            if (n.equals(List.of("done"))) return call("done", J.createObjectNode().put("summary", "closed on the deadline"));
            int turn = assistantTurns(history) + 1;
            if (n.contains("write_section")) {
                return switch (turn) {
                    case 1 -> call("write_section", J.createObjectNode().put("heading", "Answer").put("text", "The gears were cut by hand (https://example.org/gears). "
                            + "The source describes files and a dividing plate; the tooth profiles are triangular and irregular, which is what hand "
                            + "cutting leaves. No source claims a machine, and none names the workshop, so who cut them stays open."));
                    case 2 -> call("write_section", J.createObjectNode().put("heading", "Sources").put("text", "https://example.org/gears"));
                    default -> call("done", J.createObjectNode().put("summary", "one source only"));
                };
            }
            return switch (turn) {
                case 1 -> call("web_search", J.createObjectNode().put("query", "antikythera gears"));
                case 2 -> call("web_fetch", J.createObjectNode().put("url", "https://example.org/gears"));
                case 3 -> call("note", J.createObjectNode().put("claim", "the gears were cut by hand with files").put("source", "https://example.org/gears").put("quote", "cut by hand with files"));
                default -> call("done", J.createObjectNode().put("summary", "hand-cut, one source"));
            };
        }

        @Override public String classify(ArrayNode messages, int maxTokens) {
            String prompt = messages.get(messages.size() - 1).path("content").asText();
            classifies.add(prompt.substring(0, Math.min(40, prompt.length())));
            if (prompt.contains("Decompose")) return "[\"how were the gears cut?\", \"who cut them?\"]";
            if (prompt.contains("reviewing research COVERAGE")) {
                criticRounds++;
                return criticWantsMore && criticRounds == 1 ? "{\"sufficient\": false, \"missing\": [\"what tools survive?\"]}" : "{\"sufficient\": true}";
            }
            return "FALLBACK PROSE: the gears were cut by hand (https://example.org/gears).";
        }

        @Override public int contextWindow() { return 32_000; }
    }

    @Test
    void briefSubQuestionsArePlanAndTheCriticOwnsTheSecondRound() {
        ScriptedDrive drive = new ScriptedDrive();
        FakeTools tools = new FakeTools();
        var r = new Researcher(drive, tools, null, 2).run(
                new Researcher.Ask("How were the Antikythera gears cut, and by whom?", "broad", 60,
                        List.of("how were the gears cut?", "who cut them?")), "");
        assertTrue(drive.classifies.stream().noneMatch(c -> c.contains("Decompose")), "the brief's sub-questions ARE the plan — no decompose call");
        assertEquals(2, r.rounds(), "the critic asked for a gap → a second round");
        assertEquals(3, r.subQuestions());
        assertTrue(r.done());
        assertTrue(r.answer().startsWith("## Answer"), r.answer());
        assertTrue(r.answer().contains("## Sources") && r.answer().contains("## Caveats"), r.answer());
        assertTrue(r.evidence().contains("SUB-QUESTION: who cut them?"));
        assertTrue(r.evidence().contains("SUB-QUESTION: what tools survive?"), "the critic's gap was researched");
        assertTrue(r.evidence().contains("- the gears were cut by hand with files — source: https://example.org/gears — quote: \"cut by hand with files\""));
        assertEquals(3, tools.fetches.get(), "one fetch per worker");
        // 3 workers × 4 turns + critic 1 + synthesis 3 = 16 turns, all counted
        assertEquals(16, r.turnsUsed());
    }

    @Test
    void withoutABriefTheRunnerDecomposes() {
        ScriptedDrive drive = new ScriptedDrive();
        drive.criticWantsMore = false;
        var r = new Researcher(drive, new FakeTools(), null, 2).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 60, List.of()), "");
        assertTrue(drive.classifies.stream().anyMatch(c -> c.contains("Decompose")));
        assertEquals(2, r.subQuestions());
        assertEquals(1, r.rounds());
        assertTrue(r.done());
    }

    @Test
    void theBudgetIsHonestAndTheDeadlineOffersOnlyDone() {
        ScriptedDrive drive = new ScriptedDrive();
        drive.criticWantsMore = false;
        var r = new Researcher(drive, new FakeTools(), null, 2).run(
                new Researcher.Ask("How were the Antikythera gears cut, and by whom?", "broad", 14,
                        List.of("how were the gears cut?", "who cut them?")), "");
        // 14 budgeted turns; the reserve scales to 7 for a tiny ask, so one worker at four turns plus bounces is affordable, not two
        assertTrue(r.turnsUsed() <= 14 + 3, "used " + r.turnsUsed());
        assertEquals(List.of("who cut them?"), r.openQuestions(), "the budget affords one worker at four turns; the other sub-question is an open question");
        assertTrue(r.done(), "the synthesis reserve was kept");
        assertTrue(r.evidence().contains("SUB-QUESTION: how were the gears cut?"));
        assertFalse(r.answer().isBlank(), "a spent budget still leaves an answer (fallback prose or sections)");
    }

    @Test
    void aRoundsCapIsFixedWhenItStartsAndUnaffordableSubQuestionsBecomeOpenQuestions() {
        ScriptedDrive drive = new ScriptedDrive();
        drive.criticWantsMore = false;
        // 30 turns: the reserve keeps 15, so 15 remain — two sub-questions at 4 turns + 2 bounce turns each fit, the third does not
        var r = new Researcher(drive, new FakeTools(), null, 3).run(new Researcher.Ask("How were the Antikythera gears cut, and by whom?", "broad", 30,
                List.of("how were the gears cut?", "who cut them?", "what tools survive?")), "");
        assertEquals(List.of("what tools survive?"), r.openQuestions(), "the third sub-question is an open question, not a two-turn worker");
        assertTrue(r.evidence().contains("SUB-QUESTION: who cut them?"));
        assertFalse(r.evidence().contains("SUB-QUESTION: what tools survive?"));
        long deadlineOnly = drive.offered.stream().filter("done"::equals).count();
        assertTrue(deadlineOnly <= 2, "each running worker had its fair four turns: " + drive.offered);
    }

    @Test
    void workersCannotEatTheSynthesisReserve() {
        // a worker that keeps asking for turns: the budget refuses it once only the synthesis reserve is left
        ScriptedDrive drive = new ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> n = names(tools);
                offered.add(String.join(",", n));
                if (n.equals(List.of("done"))) return call("done", J.createObjectNode().put("summary", "closed"));
                if (n.contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                return call("web_search", J.createObjectNode().put("query", "q" + assistantTurns(history)));
            }
        };
        drive.criticWantsMore = false;
        var r = new Researcher(drive, new FakeTools(), null, 3).run(new Researcher.Ask("How were the Antikythera gears cut, and by whom?", "broad", 20,
                List.of("a?", "b?", "c?")), "");
        assertTrue(r.done(), "the synthesis finished: its reserve was kept for it");
        assertTrue(r.answer().startsWith("## Answer"), r.answer());
    }

    @Test
    void aWorkerWithNoTurnLeftIsAnOpenQuestionNotASummaryFromMemory() {
        // measured (J-0007): the seventh worker started on a spent budget, got the done-only turn and summarised 444 chars from memory
        ScriptedDrive drive = new ScriptedDrive();
        var r = new Researcher(drive, new FakeTools(), null, 1);
        Researcher.Budget spent = new Researcher.Budget(20);
        while (spent.take()) { }
        Researcher.Ask ask = new Researcher.Ask("How were the Antikythera gears cut?", "broad", 20, List.of("how?"));
        assertNull(r.investigate(ask, "how?", spent), "no turn to read anything: no worker, no summary");
        assertTrue(drive.offered.isEmpty(), "the drive was never asked");
        Researcher.Budget fresh = new Researcher.Budget(40);
        assertNotNull(r.investigate(ask, "how?", fresh));
    }

    @Test
    void withNoCeilingEveryPlannedSubQuestionRunsAndNothingIsAnOpenQuestionForBudget() {
        ScriptedDrive drive = new ScriptedDrive();
        drive.criticWantsMore = false;
        var r = new Researcher(drive, new FakeTools(), null, 3).run(new Researcher.Ask("How were the Antikythera gears cut, and by whom?", "broad", 0,
                List.of("how were the gears cut?", "who cut them?", "what tools survive?")), "");
        assertEquals(List.of(), r.openQuestions(), "no ceiling: nothing is dropped for budget");
        assertTrue(r.evidence().contains("SUB-QUESTION: what tools survive?"));
        assertTrue(r.done());
        assertEquals(3 * 4 + 1 + 3, r.turnsUsed(), "the turns are still counted: 3 workers × 4, the critic, 3 synthesis");
        assertEquals(0, drive.offered.stream().filter("done"::equals).count(), "no deadline turn was ever forced");
    }

    @Test
    void aTimeCeilingStopsTheWorkersEarlyEnoughForTheWriteUp() {
        Researcher.Budget b = new Researcher.Budget(0, 1);   // one minute
        assertTrue(b.unbounded(), "no turn ceiling");
        assertFalse(b.workersTimeUp());
        b.wrapUp(60_000L * 2);   // the write-up would take two minutes at this run's turn time
        assertTrue(b.workersTimeUp(), "the workers stop when the write-up no longer fits before the deadline");
        assertFalse(b.takeWorker(), "a worker turn is refused");
        assertTrue(b.take(), "the synthesis and the cite-check still take their turns");
        Researcher.Budget none = new Researcher.Budget(0, 0);
        none.wrapUp(Long.MAX_VALUE / 4);
        assertFalse(none.workersTimeUp(), "no time ceiling: never up");
    }

    @Test
    void aWorkerCutOffWithNothingNotedGetsOneClosingTurnWithNoteBesideDone() {
        // measured (J-0011, time ceiling): seven of eight workers closed with zero notes — the deadline turn offered only done
        ScriptedDrive drive = new ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> n = names(tools);
                offered.add(String.join(",", n));
                if (n.equals(List.of("done"))) return call("done", J.createObjectNode().put("summary", "closed"));
                if (n.equals(List.of("note", "done"))) return call("note", J.createObjectNode().put("claim", "cut by hand").put("source", "https://example.org/gears").put("quote", "cut by hand with files"));
                if (n.contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                int turn = assistantTurns(history) + 1;
                return turn == 1 ? call("web_search", J.createObjectNode().put("query", "gears")) : call("web_fetch", J.createObjectNode().put("url", "https://example.org/gears?" + turn));
            }
        };
        drive.criticWantsMore = false;
        var r = new Researcher(drive, new FakeTools(), null, 1).run(new Researcher.Ask("How were the Antikythera gears cut?", "broad", 20, List.of("how?")), "");
        assertTrue(drive.offered.contains("note,done"), "the closing turn offered note beside done: " + drive.offered);
        assertTrue(r.evidence().contains("- cut by hand — source: https://example.org/gears"), r.evidence());
        assertEquals(1, drive.offered.stream().filter("note,done"::equals).count(), "one closing note turn, not a loop");
    }

    @Test
    void theReserveKeepsTheCiteCheckItsTurns() {
        Researcher.Budget b = new Researcher.Budget(40);
        assertEquals(Researcher.RESERVE, b.reserve());
        assertEquals(Researcher.CHECK_TURNS, b.checkReserve());
        int workerTurns = 0;
        while (b.takeWorker()) workerTurns++;
        assertEquals(40 - Researcher.RESERVE, workerTurns, "the workers stop at the reserve");
        int synthTurns = 0;
        while (b.takeSynthesis()) synthTurns++;
        assertEquals(Researcher.RESERVE - Researcher.CHECK_TURNS, synthTurns, "the synthesis stops at the cite-check's turns");
        int checks = 0;
        while (b.take()) checks++;
        assertEquals(Researcher.CHECK_TURNS, checks, "the cite-check gets exactly its turns");
        Researcher.Budget tiny = new Researcher.Budget(14);
        assertEquals(7, tiny.reserve(), "a tiny ask keeps half");
        assertEquals(2, tiny.checkReserve());
    }

    @Test
    void spinGuardAndNoToolCallsAreHandled() {
        ScriptedDrive drive = new ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> n = names(tools);
                offered.add(String.join(",", n));
                histories.add(history.deepCopy());
                if (n.equals(List.of("done"))) return call("done", J.createObjectNode().put("summary", "x"));
                if (n.contains("write_section")) return call("done", J.createObjectNode().put("summary", "nothing to add"));
                int turn = assistantTurns(history) + 1;
                if (turn == 1 || turn == 2) return call("web_search", J.createObjectNode().put("query", "same query"));
                if (turn == 3) { ObjectNode m = J.createObjectNode(); m.put("role", "assistant"); m.put("content", "thinking out loud"); return m; }
                return call("done", J.createObjectNode().put("summary", "gave up"));
            }
        };
        drive.criticWantsMore = false;
        new Researcher(drive, new FakeTools(), null, 1).run(new Researcher.Ask("Any question long enough to research", "broad", 20, List.of("only one")), "");
        // The history the drive saw on the LAST worker turn carries both guards' messages.
        String last = "";
        for (ArrayNode h : drive.histories) {
            String s = h.toString();
            if (s.contains("only one") && s.length() > last.length()) last = s;
        }
        assertTrue(last.contains("You already ran exactly this call"), last);
        assertTrue(last.contains("Act by calling a tool."), last);
    }

    @Test
    void anEmptyDoneOnAnEmptyNotebookIsBouncedOnce() {
        // Live defect: a truncated closing tool call left "SUMMARY:" blank and no notes — the worker vanished.
        AtomicInteger dones = new AtomicInteger();
        ScriptedDrive drive = new ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                if (names(tools).contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                offered.add(String.join(",", names(tools)));
                int turn = assistantTurns(history) + 1;
                if (turn == 1) return call("web_search", J.createObjectNode().put("query", "q"));
                dones.incrementAndGet();
                return call("done", J.createObjectNode().put("summary", dones.get() == 1 ? "" : "second time: the gears were hand-cut (https://example.org/gears)"));
            }
        };
        drive.criticWantsMore = false;
        var r = new Researcher(drive, new FakeTools(), null, 1).run(new Researcher.Ask("How were the Antikythera gears cut?", "broad", 40, List.of("how?")), "");
        assertEquals(2, dones.get(), "the empty done was bounced exactly once");
        assertTrue(r.evidence().contains("SUMMARY: second time"), r.evidence());
    }

    @Test
    void theReplyBudgetFollowsTheWindowAndOldObservationsAreTrimmed() {
        ArrayNode h = J.createArrayNode();
        h.addObject().put("role", "system").put("content", "x".repeat(400));
        assertEquals(16_384, Researcher.outBudget(h, 32_768), "half the window when the input is small");
        for (int i = 0; i < 12; i++) h.addObject().put("role", "tool").put("tool_call_id", "c" + i).put("content", "o".repeat(12_000));
        assertEquals(512, Researcher.outBudget(h, 32_768), "the floor when the input fills the window");
        int trimmed = Researcher.trimHistory(h, 32_768);
        assertTrue(trimmed >= 3 && trimmed < 12, "oldest first, only as many as needed: " + trimmed);
        assertTrue(h.get(1).path("content").asText().startsWith("[older observation trimmed"));
        assertEquals(12_000, h.get(12).path("content").asText().length(), "the newest observation is kept");
        assertTrue(Researcher.outBudget(h, 32_768) > 512);
    }

    @Test
    void aTransportFailureIsRetriedOnce() {
        AtomicInteger calls = new AtomicInteger();
        ScriptedDrive drive = new ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                if (calls.incrementAndGet() == 2) throw new RuntimeException("chat() failed against http://x", new java.io.IOException("EOF"));
                return super.chat(history, tools, maxTokens, toolChoice);
            }
        };
        drive.criticWantsMore = false;
        var r = new Researcher(drive, new FakeTools(), null, 1).run(new Researcher.Ask("How were the Antikythera gears cut?", "broad", 40, List.of("how?")), "");
        assertTrue(r.done());
        assertTrue(r.evidence().contains("SUMMARY: hand-cut, one source"), "the worker survived the wire failure: " + r.evidence());
    }

    @Test
    void aDeadDriveStillLeavesTheEvidenceAndAFallbackAnswer() {
        ScriptedDrive drive = new ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                if (names(tools).contains("write_section")) throw new IllegalStateException("drive gone");
                return super.chat(history, tools, maxTokens, toolChoice);
            }
        };
        drive.criticWantsMore = false;
        var r = new Researcher(drive, new FakeTools(), null, 1).run(new Researcher.Ask("How were the Antikythera gears cut?", "broad", 40, List.of("how?")), "");
        assertFalse(r.done());
        assertTrue(r.answer().startsWith("FALLBACK PROSE"), r.answer());
        assertTrue(r.evidence().contains("cut by hand with files"));
    }

    @Test
    void fileAdmitsARunWithSourcesAndRefusesOneWithout(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        ScriptedDrive drive = new ScriptedDrive();
        drive.criticWantsMore = false;
        var filed = Researcher.file(store, new Researcher(drive, new FakeTools(), null, 2),
                new Researcher.Ask("How were the Antikythera gears cut?", "broad", 60, List.of("how?")), "patron:did:key:test");
        assertTrue(filed.admitted(), filed.reason());
        var inv = store.investigation(filed.investigationId());
        assertEquals("patron:did:key:test", inv.writer());
        assertTrue(inv.body().contains("## Worker findings"), "the evidence rides with the answer");
        assertTrue(inv.body().contains("https://example.org/gears"));

        // A run that fetched nothing and cited nothing is refused: frontier gap, no investigation.
        ScriptedDrive mute = new ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                return call("done", J.createObjectNode().put("summary", "I remember it was hand-cut"));
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return "From memory: hand-cut."; }
        };
        var refused = Researcher.file(store, new Researcher(mute, new FakeTools(), null, 1),
                new Researcher.Ask("Who cut the Antikythera gears?", "broad", 20, List.of("who?")), "patron:did:key:test");
        assertFalse(refused.admitted());
        assertTrue(refused.reason().contains("zero sources"), refused.reason());
        assertTrue(Frontier.read(store).stream().anyMatch(l -> l.text().contains("refused at intake")));
    }

    @Test
    void theDaemonRunsAnAskOnTheLibrarysOwnRunner(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.write);
        // A drive that answers the liveness probe; the run itself is scripted through the factory.
        HttpServer probe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        probe.createContext("/", ex -> {
            byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        probe.start();
        String driveUrl = "http://127.0.0.1:" + probe.getAddress().getPort();
        LibrarianDaemon daemon = LibrarianDaemon.start(store, "127.0.0.1", 0, driveUrl, "scripted", -1);
        List<String> drivesSeen = new CopyOnWriteArrayList<>();
        daemon.researcherFactory = d -> { drivesSeen.add(d); ScriptedDrive s = new ScriptedDrive(); s.criticWantsMore = false; return new Researcher(s, new FakeTools(), null, 2); };
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + daemon.port();
            ObjectNode ask = J.createObjectNode();
            ask.put("question", "How were the Antikythera gears cut?");
            ask.putArray("sub_questions").add("how were they cut?").add("by whom?");
            HttpResponse<String> filedResp = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/research"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(ask.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, filedResp.statusCode(), filedResp.body());
            String jobId = J.readTree(filedResp.body()).get("job_id").asText();
            JsonNode job = null;
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                HttpResponse<String> jr = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/jobs/" + jobId)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                job = J.readTree(jr.body());
                if ("done".equals(job.path("state").asText()) || "failed".equals(job.path("state").asText())) break;
                Thread.sleep(100);
            }
            assertNotNull(job);
            assertEquals("done", job.path("state").asText(), job.toString());
            assertTrue(job.path("investigation").asText().startsWith("I-"), job.toString());
            assertEquals(List.of(driveUrl), drivesSeen);
            var inv = store.investigation(job.path("investigation").asText());
            assertEquals("patron:anonymous", inv.writer());
            assertTrue(inv.body().contains("SUB-QUESTION: by whom?"), "the brief's sub-questions reached the runner");
            String log = Files.readString(store.root().resolve("catalog").resolve("crews.log"));
            assertTrue(log.contains("research " + jobId), log);
        } finally {
            daemon.stop();
            probe.stop(0);
        }
    }
}
