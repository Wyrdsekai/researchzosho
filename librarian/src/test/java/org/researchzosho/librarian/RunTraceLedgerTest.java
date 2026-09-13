package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The instrument: every model call on the trace with its exact request, and one ledger row per run that `stats` reads. */
class RunTraceLedgerTest {
    @TempDir Path tmp;
    static final ObjectMapper M = new ObjectMapper();

    static Researcher.Drive fake() {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", "");
                ObjectNode tc = m.putArray("tool_calls").addObject(); tc.put("id", "c1"); tc.put("type", "function");
                tc.putObject("function").put("name", "web_fetch").put("arguments", "{\"url\":\"https://example.org/a\"}");
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return "yes"; }
            @Override public int contextWindow() { return 16384; }
        };
    }

    @Test
    void traceHoldsTheExactRequestAndTheReplyShapeAndCountsCalls() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        RunTrace t = RunTrace.open(store, "J-0001");
        Researcher.Drive d = t.wrap(fake(), "workers");
        ArrayNode msgs = M.createArrayNode(); msgs.addObject().put("role", "user").put("content", "find the gears");
        ArrayNode tools = M.createArrayNode(); tools.addObject().put("type", "function").putObject("function").put("name", "web_fetch");
        d.chat(msgs, tools, 500, "required");
        d.chat(msgs, tools, 500, "required");
        assertEquals("yes", d.classify(msgs, 20));
        ObjectNode ev = M.createObjectNode(); ev.put("step", "synthesis"); ev.put("chars_before", 90000); ev.put("chars_after", 40000);
        t.event("compaction", ev);
        List<String> lines = Files.readAllLines(RunTrace.fileFor(store, "J-0001"), StandardCharsets.UTF_8);
        assertEquals(4, lines.size(), String.join("\n", lines));
        var first = M.readTree(lines.get(0));
        assertEquals("llm_call", first.path("capture").asText());
        assertEquals("find the gears", first.path("messages").get(0).path("content").asText(), "the exact request is on the trace");
        assertEquals("web_fetch", first.path("tools").get(0).asText());
        assertEquals("web_fetch", first.path("reply").path("tool_calls").get(0).path("name").asText());
        assertTrue(first.has("latency_ms"));
        assertEquals("classify", M.readTree(lines.get(2)).path("kind").asText());
        assertEquals("compaction", M.readTree(lines.get(3)).path("capture").asText());
        assertEquals(3, t.totals().path("calls").asInt());
        assertEquals(1, t.totals().path("compactions").asInt());
        assertEquals(2, RunLedger.fetchCalls(store, "J-0001"), "web_fetch calls counted from the trace, repeats included");
    }

    @Test
    void pruneKeepsTheNewestTraces() throws Exception {
        Path dir = tmp.resolve("traces"); Files.createDirectories(dir);
        for (int i = 0; i < 5; i++) { Files.writeString(dir.resolve("J-000" + i + ".jsonl"), "{}\n"); Files.setLastModifiedTime(dir.resolve("J-000" + i + ".jsonl"), java.nio.file.attribute.FileTime.fromMillis(1_000_000L * (i + 1))); }
        RunTrace.prune(dir, 2);
        try (var s = Files.list(dir)) { assertEquals(List.of("J-0003.jsonl", "J-0004.jsonl"), s.map(p -> p.getFileName().toString()).sorted().toList()); }
    }

    @Test
    void ledgerRowsSummarise() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib2")); store.init();
        var ask = new Researcher.Ask("How were the gears cut?", "broad", 0, List.of(), "web", List.of(), 40);
        ObjectNode stats = M.createObjectNode();
        stats.put("cite_checked", 10); stats.put("cite_supported", 6); stats.put("cite_unsupported", 2); stats.put("sources_noted", 12); stats.put("fetches_distinct", 9); stats.put("critic_gaps", 0);
        var r = new Researcher.Result(true, "answer", "evidence", 80, 8, 1, List.of(), List.of(), stats);
        ObjectNode totals = M.createObjectNode(); totals.put("calls", 80); totals.put("prompt_tokens", 100000); totals.put("completion_tokens", 20000);
        ObjectNode row = RunLedger.row("J-0030", ask, r, "filed I-0029", 33 * 60_000L, "http://x:8211", "m", totals);
        row.put("fetches_total", 12);
        RunLedger.record(store, row);
        var r2 = new Researcher.Result(false, "a", "e", 120, 10, 2, List.of(), List.of("q"), M.createObjectNode().put("critic_gaps", 2).put("ceiling_cut", true));
        RunLedger.record(store, RunLedger.row("J-0031", ask, r2, "filed I-0030", 40 * 60_000L, "http://x:8211", "m", null));
        var rows = RunLedger.read(store, 20);
        assertEquals(2, rows.size());
        var sum = RunLedger.summary(rows);
        assertEquals("2", sum.get("runs"));
        assertEquals("1 of 2", sum.get("critic satisfied first time"));
        assertEquals("1 of 2", sum.get("cut by a ceiling"));
        assertTrue(sum.get("cite-check").startsWith("10 sentences read: 6 supported, 2 not, 2 undecidable (60% supported)"), sum.get("cite-check"));
        assertTrue(sum.get("fetches").startsWith("12 total, 9 distinct (25% repeats)"), sum.get("fetches"));
        assertTrue(RunLedger.line(rows.get(0)).contains("cite 6/10"), RunLedger.line(rows.get(0)));
    }
}
