package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cross-run ledger: one JSON line per research run in {@code catalog/runs.jsonl} — turns, rounds, whether
 * the critic was satisfied first time, whether a ceiling cut the run, the cite-check's counts, sources read
 * and cited, fetches total and distinct, tokens, wall time. {@code researchzosho stats} reads it. This is what
 * tunes ROUNDS and WORKER_TURNS from evidence instead of belief, and what shows a change moved a number.
 */
public final class RunLedger {
    private static final ObjectMapper M = new ObjectMapper();

    public static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("runs.jsonl"); }

    /** One row for a finished run. Never throws. */
    public static void record(LibraryStore store, ObjectNode row) {
        if (store == null) return;
        try {
            Files.createDirectories(file(store).getParent());
            row.put("recorded_at", java.time.Instant.now().toString());
            Files.writeString(file(store), row.toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    /** The row for a run: the ask, its result, the trace's totals, and what the runner counted. */
    public static ObjectNode row(String jobId, Researcher.Ask ask, Researcher.Result r, String outcome, long wallMs, String drive, String model, ObjectNode traceTotals) {
        ObjectNode o = M.createObjectNode();
        o.put("job_id", jobId);
        o.put("question", ask.question());
        o.put("mode", ask.mode()); o.put("sources", ask.sources());
        o.put("max_turns", ask.maxTurns()); o.put("max_minutes", ask.maxMinutes());
        o.put("outcome", outcome);
        o.put("wall_ms", wallMs);
        o.put("drive", drive == null ? "" : drive); o.put("model", model == null ? "" : model);
        o.put("turns_used", r.turnsUsed()); o.put("sub_questions", r.subQuestions()); o.put("rounds", r.rounds());
        o.put("synthesis_done", r.done());
        o.put("open_questions", r.openQuestions().size());
        o.put("answer_chars", r.answer() == null ? 0 : r.answer().length());
        o.put("evidence_chars", r.evidence() == null ? 0 : r.evidence().length());
        if (r.stats() != null) o.setAll(r.stats());
        if (traceTotals != null) o.set("trace", traceTotals);
        return o;
    }

    /** How many web_fetch calls the run's trace holds (repeats included); -1 when there is no trace. */
    public static int fetchCalls(LibraryStore store, String jobId) {
        try {
            Path t = RunTrace.fileFor(store, jobId);
            if (!Files.exists(t)) return -1;
            int n = 0;
            for (String line : Files.readAllLines(t, StandardCharsets.UTF_8)) {
                if (!line.contains("\"tool_calls\"")) continue;
                JsonNode j = M.readTree(line);
                for (JsonNode c : j.path("reply").path("tool_calls")) if ("web_fetch".equals(c.path("name").asText())) n++;
            }
            return n;
        } catch (IOException e) { return -1; }
    }

    /** The last {@code n} rows, newest last. */
    public static List<ObjectNode> read(LibraryStore store, int n) throws IOException {
        Path f = file(store);
        if (!Files.exists(f)) return List.of();
        List<ObjectNode> rows = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try { JsonNode j = M.readTree(line); if (j.isObject()) rows.add((ObjectNode) j); } catch (IOException ignored) { }
        }
        if (n > 0 && rows.size() > n) rows = rows.subList(rows.size() - n, rows.size());
        return rows;
    }

    /** The aggregates over rows: counts, rates and medians a person reads in ten seconds. */
    public static Map<String, String> summary(List<ObjectNode> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rows.isEmpty()) { out.put("runs", "0"); return out; }
        int n = rows.size(), firstTry = 0, noCritic = 0, cut = 0, refused = 0, checked = 0, supported = 0, unsupported = 0, withCheck = 0, mechanical = 0, overruled = 0, unretrieved = 0;
        long fetchTotal = 0, fetchDistinct = 0, tokens = 0; int withTokens = 0;
        List<Long> turns = new ArrayList<>(), wall = new ArrayList<>(), sources = new ArrayList<>();
        for (ObjectNode r : rows) {
            turns.add(r.path("turns_used").asLong()); wall.add(r.path("wall_ms").asLong() / 60000); sources.add(r.path("sources_noted").asLong(-1));
            if (r.path("critic_ran").asBoolean(r.has("critic_gaps")) && r.path("critic_gaps").asInt(0) == 0) firstTry++;
            if (!r.path("critic_ran").asBoolean(r.has("critic_gaps"))) noCritic++;
            if (!r.path("synthesis_done").asBoolean(true) || r.path("ceiling_cut").asBoolean(false)) cut++;
            if (r.path("outcome").asText("").startsWith("refused")) refused++;
            if (r.has("cite_checked")) { withCheck++; checked += r.path("cite_checked").asInt(); supported += r.path("cite_supported").asInt(); unsupported += r.path("cite_unsupported").asInt(); mechanical += r.path("cite_mechanical").asInt(0); overruled += r.path("cite_overruled").asInt(0); unretrieved += r.path("cite_unretrieved").asInt(0); }
            if (r.has("fetches_total")) { fetchTotal += r.path("fetches_total").asLong(); fetchDistinct += r.path("fetches_distinct").asLong(); }
            long tk = r.path("trace").path("prompt_tokens").asLong(0) + r.path("trace").path("completion_tokens").asLong(0);
            if (tk > 0) { tokens += tk; withTokens++; }
        }
        out.put("runs", String.valueOf(n));
        out.put("turns (median)", String.valueOf(median(turns)));
        out.put("minutes (median)", String.valueOf(median(wall)));
        out.put("critic satisfied first time", firstTry + " of " + (n - noCritic) + (noCritic > 0 ? " (" + noCritic + " run(s) never reached the critic: the ceiling left no room)" : ""));
        out.put("cut by a ceiling", cut + " of " + n);
        if (refused > 0) out.put("refused at the gate", refused + " of " + n);
        if (withCheck > 0) out.put("cite-check", checked + " sentences read: " + supported + " supported, " + unsupported + " not, " + (checked - supported - unsupported) + " undecidable"
                + (checked > 0 ? " (" + Math.round(100.0 * supported / checked) + "% supported)" : "") + (mechanical + overruled + unretrieved > 0 ? "; " + mechanical + " settled mechanically, " + overruled + " judge verdict(s) overruled, " + unretrieved + " citation(s) of unread sources" : ""));
        sources.removeIf(v -> v < 0);
        if (!sources.isEmpty()) out.put("sources noted (median)", String.valueOf(median(sources)));
        if (fetchTotal > 0) out.put("fetches", fetchTotal + " total, " + fetchDistinct + " distinct (" + Math.round(100.0 * (fetchTotal - fetchDistinct) / fetchTotal) + "% repeats)");
        if (withTokens > 0) out.put("tokens per run (mean)", String.valueOf(tokens / withTokens));
        return out;
    }

    static long median(List<Long> v) { if (v.isEmpty()) return 0; List<Long> s = new ArrayList<>(v); Collections.sort(s); return s.get(s.size() / 2); }

    /** One line per run for the CLI. */
    public static String line(ObjectNode r) {
        StringBuilder b = new StringBuilder();
        b.append(String.format("%-8s", r.path("job_id").asText("?")));
        b.append(String.format(" %4d turns", r.path("turns_used").asInt()));
        b.append(String.format(" %2d rnd", r.path("rounds").asInt()));
        b.append(String.format(" %3d min", r.path("wall_ms").asLong() / 60000));
        if (r.has("cite_checked")) b.append(String.format(" cite %d/%d", r.path("cite_supported").asInt(), r.path("cite_checked").asInt()));
        if (r.has("sources_noted")) b.append(String.format(" src %d", r.path("sources_noted").asInt()));
        if (r.has("fetches_total")) b.append(String.format(" fetch %d/%d", r.path("fetches_distinct").asInt(), r.path("fetches_total").asInt()));
        String out = r.path("outcome").asText("");
        b.append("  ").append(out.length() > 24 ? out.substring(0, 24) : out);
        b.append("  ").append(Acquisitions.compress(r.path("question").asText(""), 60));
        return b.toString();
    }
}
