package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How long the write-up takes on the model this library runs on, learned run by run (0.7.0). The write-up's estimate is built from the
 * run's own turn times ({@link Researcher#wrapUpEstimate}); on eight live runs it stood at four to six times what the write-up then took
 * (22.5 min against 4, 31.8 against 5, 2026-11-08), and it bounds four things: when the workers stop, whether the picture steps may run,
 * whether the critic's round runs, and whether a second write-up is tried. So every run records the pair, the estimate before any scaling
 * and what the write-up took, in {@code catalog/write-up-times.jsonl}, the last {@link #KEEP} runs; the next run multiplies its estimate by
 * the median of the recorded actual-over-estimate ratios, clamped to [{@link #LOWEST}, {@link #HIGHEST}], 1.0 before any run is recorded.
 * The floor keeps a run whose estimate was close to right (J-0011) its margin.
 */
final class WriteUpTimes {
    private static final ObjectMapper J = new ObjectMapper();
    static final int KEEP = 20;
    static final double LOWEST = 0.5, HIGHEST = 1.5;
    /** A write-up or an estimate under this says nothing of a model (a stand-in's run of milliseconds): not recorded. A differential test saw its second run scaled by the first's few milliseconds (2026-11-08). */
    static final long FLOOR_MS = 100;

    private WriteUpTimes() { }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("write-up-times.jsonl"); }

    /** One run's pair recorded; the file keeps the last {@link #KEEP}. Nothing without a library, or with an estimate or a measured time under {@link #FLOOR_MS}. */
    static void record(LibraryStore store, String jobId, long estimateMs, long actualMs) {
        if (store == null || estimateMs < FLOOR_MS || actualMs < FLOOR_MS) return;
        try {
            List<String> lines = new ArrayList<>(Files.exists(file(store)) ? Files.readAllLines(file(store), StandardCharsets.UTF_8) : List.of());
            lines.removeIf(String::isBlank);
            ObjectNode o = J.createObjectNode();
            o.put("when", Instant.now().toString()); o.put("job", jobId == null ? "" : jobId); o.put("estimate_ms", estimateMs); o.put("actual_ms", actualMs); o.put("ratio", Math.round(actualMs * 1000.0 / estimateMs) / 1000.0);
            lines.add(o.toString());
            while (lines.size() > KEEP) lines.remove(0);
            Files.createDirectories(file(store).getParent());
            Files.writeString(file(store), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) { /* a record, not a gate */ }
    }

    /** The recorded ratios, actual over estimate, oldest first. */
    static List<Double> ratios(LibraryStore store) {
        List<Double> out = new ArrayList<>();
        if (store == null || !Files.exists(file(store))) return out;
        try {
            for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode n = J.readTree(line);
                long e = n.path("estimate_ms").asLong(), a = n.path("actual_ms").asLong();
                if (e > 0 && a > 0) out.add(a / (double) e);
            }
        } catch (IOException ignored) { /* an unreadable record is no record */ }
        return out;
    }

    /** What the next run multiplies its estimate by: the median of the recorded ratios, clamped; 1.0 with nothing recorded. */
    static double correction(LibraryStore store) {
        List<Double> r = ratios(store);
        if (r.isEmpty()) return 1.0;
        Collections.sort(r);
        double median = r.size() % 2 == 1 ? r.get(r.size() / 2) : (r.get(r.size() / 2 - 1) + r.get(r.size() / 2)) / 2;
        return Math.max(LOWEST, Math.min(HIGHEST, median));
    }

    /** How many runs are recorded. */
    static int recorded(LibraryStore store) { return ratios(store).size(); }
}
