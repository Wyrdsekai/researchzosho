package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The per-run trace: every model call a research run makes, with the EXACT request messages, the tools
 * offered, the reply's shape, the server's usage and the latency, one JSON line each, plus the runner's own
 * events (a compaction: what was fitted and how much was cut). One file per run under
 * {@code catalog/traces/<job>.jsonl}; the newest {@link #KEEP} are kept.
 *
 * <p>It is the instrument. "The writer never saw lanes four and five" (0.1.6) was found by a reader of the
 * write-up; with this file it is one grep. {@code RESEARCHZOSHO_TRACE=off} turns it off.
 */
public final class RunTrace implements AutoCloseable {
    private static final ObjectMapper M = new ObjectMapper();
    static final int KEEP = org.researchzosho.Config.getInt("RESEARCHZOSHO_TRACE_KEEP", 50);
    static boolean enabled() { return !"off".equalsIgnoreCase(org.researchzosho.Config.get("RESEARCHZOSHO_TRACE", "on")); }

    private final Path file;
    private final String runId;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicLong promptTokens = new AtomicLong(), completionTokens = new AtomicLong(), latencyMs = new AtomicLong();
    final AtomicInteger compactions = new AtomicInteger();

    private RunTrace(Path file, String runId) { this.file = file; this.runId = runId; }

    /** The trace for a run, or a silent one when tracing is off or the directory cannot be made. */
    public static RunTrace open(LibraryStore store, String runId) {
        if (store == null || !enabled()) return new RunTrace(null, runId);
        try {
            Path dir = store.root().resolve("catalog").resolve("traces");
            Files.createDirectories(dir);
            prune(dir, KEEP - 1);
            return new RunTrace(dir.resolve(runId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jsonl"), runId);
        } catch (IOException e) { return new RunTrace(null, runId); }
    }

    public static Path fileFor(LibraryStore store, String runId) {
        return store.root().resolve("catalog").resolve("traces").resolve(runId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jsonl");
    }

    /** Keep the newest {@code keep} trace files; the rest go. */
    static void prune(Path dir, int keep) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) { s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).forEach(files::add); }
        files.sort((a, b) -> { try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); } catch (IOException e) { return 0; } });
        for (int i = Math.max(0, keep); i < files.size(); i++) Files.deleteIfExists(files.get(i));
    }

    /** One line; never throws — the trace never breaks a run. */
    public synchronized void write(ObjectNode line) {
        if (file == null) return;
        line.put("t", Instant.now().toString());
        line.put("run", runId);
        try { Files.writeString(file, line.toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException ignored) { }
    }

    /** A runner event: {@code compaction} (pieces, chars before, chars after), and anything else worth a line. */
    public void event(String type, ObjectNode data) {
        if ("compaction".equals(type)) compactions.incrementAndGet();
        ObjectNode o = M.createObjectNode(); o.put("capture", type); o.setAll(data); write(o);
    }

    /** The counters a ledger row takes. */
    public ObjectNode totals() {
        ObjectNode o = M.createObjectNode();
        o.put("calls", calls.get()); o.put("prompt_tokens", promptTokens.get()); o.put("completion_tokens", completionTokens.get());
        o.put("latency_ms", latencyMs.get()); o.put("compactions", compactions.get());
        return o;
    }

    @Override public void close() { }

    /** The drive seen through the trace: every call is written before its reply is returned. {@code seat} names which drive (workers, judge). */
    public Researcher.Drive wrap(Researcher.Drive delegate, String seat) {
        if (file == null) return delegate;
        RunTrace trace = this;
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                long t0 = System.nanoTime();
                ObjectNode line = M.createObjectNode();
                line.put("capture", "llm_call"); line.put("seat", seat); line.put("kind", "chat");
                line.put("max_tokens", maxTokens); line.put("tool_choice", toolChoice == null ? "" : toolChoice);
                ArrayNode names = line.putArray("tools");
                if (tools != null) for (JsonNode t : tools) names.add(t.path("function").path("name").asText(t.path("name").asText("")));
                line.set("messages", messages == null ? M.createArrayNode() : messages.deepCopy());
                ObjectNode reply = null; String error = null;
                try { reply = delegate.chat(messages, tools, maxTokens, toolChoice); return reply; }
                catch (RuntimeException e) { error = e.getMessage(); throw e; }
                finally { finish(line, t0, reply, error); }
            }
            @Override public String classify(ArrayNode messages, int maxTokens) {
                long t0 = System.nanoTime();
                ObjectNode line = M.createObjectNode();
                line.put("capture", "llm_call"); line.put("seat", seat); line.put("kind", "classify"); line.put("max_tokens", maxTokens);
                line.set("messages", messages == null ? M.createArrayNode() : messages.deepCopy());
                String out = "";
                try { out = delegate.classify(messages, maxTokens); return out; }
                finally { ObjectNode r = M.createObjectNode(); r.put("content_chars", out == null ? 0 : out.length()); finish(line, t0, r, null); }
            }
            @Override public int contextWindow() { return delegate.contextWindow(); }
            private void finish(ObjectNode line, long t0, ObjectNode reply, String error) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                line.put("latency_ms", ms);
                trace.calls.incrementAndGet(); trace.latencyMs.addAndGet(ms);
                long[] u = org.researchzosho.drive.DriveClient.lastUsage();
                if (u != null) { line.putObject("usage").put("prompt_tokens", u[0]).put("completion_tokens", u[1]); trace.promptTokens.addAndGet(u[0]); trace.completionTokens.addAndGet(u[1]); }
                if (reply != null) {
                    ObjectNode r = line.putObject("reply");
                    if (reply.has("content_chars")) r.put("content_chars", reply.get("content_chars").asInt());
                    else {
                        r.put("content_chars", reply.path("content").asText("").length());
                        ArrayNode calls = r.putArray("tool_calls");
                        for (JsonNode c : reply.path("tool_calls")) {
                            ObjectNode tc = calls.addObject();
                            tc.put("name", c.path("function").path("name").asText(""));
                            tc.put("arguments", c.path("function").path("arguments").asText(""));
                        }
                    }
                }
                if (error != null) line.put("error", error);
                trace.write(line);
            }
        };
    }
}
