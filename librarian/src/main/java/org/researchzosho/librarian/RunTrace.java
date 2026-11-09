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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.DeclineJudge;
/**
 * The per-run trace: every model call a research run makes, with the EXACT request messages, the tools
 * offered, the reply's shape, the server's usage and the latency, one JSON line each, plus the runner's own
 * events (a compaction: what was fitted and how much was cut). One file per run under
 * {@code catalog/traces/<job>.jsonl}; the newest {@link #KEEP} are kept.
 *
 * <p>It is the instrument. "The writer never saw lanes four and five" (0.1.6) was found by a reader of the
 * write-up; with this file it is one grep. {@code RESEARCHZOSHO_TRACE=off} turns it off.
 *
 * <p>Every call's {@code seat} is the STAGE of the run it was made in ({@link #stage}): plan, workers, critic, write-up, topic-check,
 * analysis, cite-check, revise; a call made outside a named stage keeps the name of the drive it went to (workers, judge). {@code drive}
 * says which of the two drives took it, whatever the stage. The first chat call of each stage (each worker's first, the writer's first,
 * a second write-up's first) keeps the model's reasoning text, when the server returns it, and what it answered in words, each up to
 * {@link #REASONING_CHARS} characters: a write-up whose first visible act was a fetch on another subject had spent 1,600 tokens on
 * reasoning the trace did not hold, so its cause could not be read back (2026-11-05).
 */
public final class RunTrace implements AutoCloseable, Declines.Notes {
    private static final ObjectMapper M = new ObjectMapper();
    static final int KEEP = Config.getInt("RESEARCHZOSHO_TRACE_KEEP", 50);
    static boolean enabled() { return !"off".equalsIgnoreCase(Config.get("RESEARCHZOSHO_TRACE", "on")); }

    private final Path file;
    private final String runId;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicLong promptTokens = new AtomicLong(), completionTokens = new AtomicLong(), latencyMs = new AtomicLong();
    final AtomicInteger compactions = new AtomicInteger();

    private RunTrace(Path file, String runId) { this.file = file; this.runId = runId; }

    /** The stages of a run, as the trace names them in {@code seat}. */
    public static final String PLAN = "plan", WORKERS = "workers", CRITIC = "critic", WRITE_UP = "write-up", TOPIC_CHECK = "topic-check",
            ANALYSIS = "analysis", CITE_CHECK = "cite-check", REVISE = "revise";

    /** How much of the reasoning, and of the words, of a stage's first call the trace keeps; 0 keeps none (RESEARCHZOSHO_TRACE_REASONING). */
    static final int REASONING_CHARS = 8_000;
    static int reasoningChars() { return Math.max(0, Config.getInt("RESEARCHZOSHO_TRACE_REASONING", REASONING_CHARS)); }

    /** One stage as it runs on one thread: its name, and whether its first chat call has been written. */
    private static final class Stage {
        final String name;
        final AtomicBoolean first = new AtomicBoolean(true);
        Stage(String name) { this.name = name; }
    }

    private static final ThreadLocal<Stage> STAGE = new ThreadLocal<>();

    /** Closes a {@link #stage}: the stage named before it is the stage again. */
    public interface Scope extends AutoCloseable { @Override void close(); }

    /**
     * Until the returned scope closes, the model calls on this thread belong to the stage {@code name}: the trace writes it as their
     * seat, and keeps the reasoning of the first of them. Opening a stage again (a second write-up, another worker) starts a new one
     * with a first call of its own. It costs nothing where no trace is open.
     */
    public static Scope stage(String name) {
        Stage outer = STAGE.get();
        STAGE.set(new Stage(name == null || name.isBlank() ? "" : name.strip()));
        return () -> { if (outer == null) STAGE.remove(); else STAGE.set(outer); };
    }

    /** The stage named on this thread; "" when none is. */
    static String stageNow() { Stage s = STAGE.get(); return s == null ? "" : s.name; }

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
    /** A reply the decline judge was not sure of, as a {@code decline_unsure} line. */
    @Override public void unsure(ObjectNode note) { event("decline_unsure", note); }
    /** A decline as it passed a watched seat, as a {@code declined} line. */
    @Override public void declined(ObjectNode note) { event("declined", note); }

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
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return traced(false, messages, tools, maxTokens, toolChoice); }
            /** The decision turn goes to the drive's own (thinking off, sampling cold), not to its chat: seen through the trace it was a chat turn with the thinking on. */
            @Override public ObjectNode decide(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return traced(true, messages, tools, maxTokens, toolChoice); }
            /** The model's name is the drive's: the brain reads its tier off it, and seen through the trace every model was nameless, so every one was taken for a large one. */
            @Override public String model() { return delegate.model(); }
            private ObjectNode traced(boolean decide, ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                long t0 = System.nanoTime();
                ObjectNode line = M.createObjectNode();
                Stage stage = STAGE.get();
                line.put("capture", "llm_call"); seated(line, seat); line.put("kind", "chat");
                if (decide) line.put("decide", true);
                if (stage != null && !stage.name.isEmpty() && stage.first.compareAndSet(true, false)) line.put("first_of_stage", true);
                line.put("max_tokens", maxTokens); line.put("tool_choice", toolChoice == null ? "" : toolChoice);
                ArrayNode names = line.putArray("tools");
                if (tools != null) for (JsonNode t : tools) names.add(t.path("function").path("name").asText(t.path("name").asText("")));
                line.set("messages", messages == null ? M.createArrayNode() : messages.deepCopy());
                ObjectNode reply = null; String error = null;
                DriveClient.forgetUsage();
                try { reply = decide ? delegate.decide(messages, tools, maxTokens, toolChoice) : delegate.chat(messages, tools, maxTokens, toolChoice); return reply; }
                catch (RuntimeException e) { error = e.getMessage(); throw e; }
                finally { finish(line, t0, reply, error); }
            }
            @Override public String classify(ArrayNode messages, int maxTokens) {
                long t0 = System.nanoTime();
                ObjectNode line = M.createObjectNode();
                line.put("capture", "llm_call"); seated(line, seat); line.put("kind", "classify"); line.put("max_tokens", maxTokens);
                line.set("messages", messages == null ? M.createArrayNode() : messages.deepCopy());
                String out = ""; String error = null;
                DriveClient.forgetUsage();
                try { out = delegate.classify(messages, maxTokens); return out; }
                catch (RuntimeException e) { error = e.getMessage(); throw e; }   // a decline among them: the line says what the model declined
                finally { ObjectNode r = M.createObjectNode(); r.put("content_chars", out == null ? 0 : out.length()); r.put("head", head(out)); finish(line, t0, r, error); }
            }
            @Override public String prose(ArrayNode messages, int maxTokens) {
                long t0 = System.nanoTime();
                ObjectNode line = M.createObjectNode();
                line.put("capture", "llm_call"); seated(line, seat); line.put("kind", "prose"); line.put("max_tokens", maxTokens);
                line.set("messages", messages == null ? M.createArrayNode() : messages.deepCopy());
                String out = ""; String error = null;
                DriveClient.forgetUsage();
                try { out = delegate.prose(messages, maxTokens); return out; }
                catch (RuntimeException e) { error = e.getMessage(); throw e; }
                finally { ObjectNode r = M.createObjectNode(); r.put("content_chars", out == null ? 0 : out.length()); r.put("head", head(out)); finish(line, t0, r, error); }
            }
            @Override public int contextWindow() { return delegate.contextWindow(); }
            @Override public DeclineJudge declineJudge() { return delegate.declineJudge(); }
            /** The seat is the stage the call was made in, else the drive's own name; the drive is named beside it either way. */
            private void seated(ObjectNode line, String drive) {
                String stage = stageNow();
                line.put("seat", stage.isEmpty() ? drive : stage); line.put("drive", drive);
            }
            /** The start of a decision's reply, so a plan read as one sub-question can be read back (MiniMax M3 through OpenRouter, 2026-10-16: 4,000 characters the planner could not read, and only their count in the trace). */
            private static String head(String s) { return s == null ? "" : s.length() > 400 ? s.substring(0, 400) : s; }
            private void finish(ObjectNode line, long t0, ObjectNode reply, String error) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                line.put("latency_ms", ms);
                trace.calls.incrementAndGet(); trace.latencyMs.addAndGet(ms);
                long[] u = DriveClient.lastUsage();
                if (u != null) { line.putObject("usage").put("prompt_tokens", u[0]).put("completion_tokens", u[1]); trace.promptTokens.addAndGet(u[0]); trace.completionTokens.addAndGet(u[1]); }
                if (reply != null) {
                    ObjectNode r = line.putObject("reply");
                    if (reply.has("content_chars")) { r.put("content_chars", reply.get("content_chars").asInt()); if (reply.has("head")) r.put("head", reply.get("head").asText()); }
                    else {
                        String content = reply.path("content").asText(""), reasoning = reply.path("reasoning_content").asText(reply.path("reasoning").asText(""));   // OpenRouter's name for it is reasoning
                        r.put("content_chars", content.length());
                        if (!reasoning.isEmpty()) r.put("reasoning_chars", reasoning.length());
                        // the first call of a stage: what the model thought and what it said in words, so a stage that began on another subject can be read back
                        int keep = line.path("first_of_stage").asBoolean(false) ? reasoningChars() : 0;
                        if (keep > 0 && !reasoning.isEmpty()) r.put("reasoning", reasoning.length() > keep ? reasoning.substring(0, keep) : reasoning);
                        if (keep > 0 && !content.isEmpty()) r.put("content", content.length() > keep ? content.substring(0, keep) : content);
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
