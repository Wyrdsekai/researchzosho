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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the library's runs cost (0.6.2): one line per run in {@code catalog/spend.jsonl} — when, which job and report, which named model,
 * host and model id, the tokens and the dollars at the entry's prices — so a person sees what a month on a host came to, and a cap on
 * an entry can refuse a run before it starts. The figures are the library's arithmetic on the servers' usage counts at list prices,
 * not the host's bill.
 */
public final class Spend {

    private Spend() { }

    private static final ObjectMapper M = new ObjectMapper();

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("spend.jsonl"); }

    /** One run's line. */
    public static synchronized void record(LibraryStore store, String jobId, String reportId, ModelEntries.Entry entry, long[] usage, double cost) throws IOException { record(store, jobId, reportId, entry, usage, cost, 0, null, null); }

    /** As above, with how long the run took, how it ended and its kind (research, quick, chat), which best judgement reads back. */
    public static synchronized void record(LibraryStore store, String jobId, String reportId, ModelEntries.Entry entry, long[] usage, double cost, long secs, String status, String kind) throws IOException {
        ObjectNode o = M.createObjectNode();
        if (secs > 0) o.put("secs", secs);
        if (status != null && !status.isBlank()) o.put("status", status);
        o.put("kind", kind == null || kind.isBlank() ? "research" : kind);
        o.put("t", Instant.now().toString()); o.put("job", jobId == null ? "" : jobId); o.put("report", reportId == null ? "" : reportId);
        o.put("entry", entry.name()); o.put("host", entry.host()); o.put("model", entry.model());
        o.put("prompt", usage[0]); o.put("cached", usage[1]); o.put("completion", usage[2]);
        o.put("cost", Math.round(cost * 10000) / 10000.0); o.put("priced", entry.prices() != null);
        Files.createDirectories(file(store).getParent());
        Files.writeString(file(store), o.toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Every line, oldest first; none when nothing was recorded. */
    public static List<JsonNode> lines(LibraryStore store) {
        Path f = file(store);
        if (!Files.isReadable(f)) return List.of();
        try {
            return Files.readAllLines(f, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).map(l -> { try { return (JsonNode) M.readTree(l); } catch (Exception e) { return null; } }).filter(n -> n != null).toList();
        } catch (IOException e) { return List.of(); }
    }

    /** What a model took and cost here for jobs of a kind: {minutes, dollars, jobs}, or null before three such jobs with their times are on record. */
    public static double[] average(LibraryStore store, String model, String kind) {
        double secs = 0, dollars = 0; int n = 0;
        for (JsonNode l : lines(store)) {
            if (!l.path("model").asText("").equals(model) || !l.path("kind").asText("research").equals(kind) || l.path("secs").asLong(0) <= 0) continue;
            secs += l.path("secs").asLong(0); dollars += l.path("cost").asDouble(0); n++;
        }
        return n >= 3 ? new double[]{secs / n / 60.0, dollars / n, n} : null;
    }

    /** Dollars spent this calendar month on an entry (by name) or on a host (by host name); the two names never collide in practice, both are summed. */
    public static double monthTotal(LibraryStore store, String entryOrHost) {
        String ym = YearMonth.now().toString();
        double sum = 0;
        for (JsonNode l : lines(store)) {
            if (!l.path("t").asText("").startsWith(ym)) continue;
            if (entryOrHost.equalsIgnoreCase(l.path("entry").asText("")) || entryOrHost.equalsIgnoreCase(l.path("host").asText(""))) sum += l.path("cost").asDouble(0);
        }
        return sum;
    }

    /** This month and all time, per host and per entry: for the settings page and the spend command. */
    public static ObjectNode summary(LibraryStore store) {
        String ym = YearMonth.now().toString();
        Map<String, double[]> byHost = new LinkedHashMap<>(), byEntry = new LinkedHashMap<>();   // {this month, all time, runs this month, runs all time}
        for (JsonNode l : lines(store)) {
            boolean thisMonth = l.path("t").asText("").startsWith(ym);
            for (Map.Entry<Map<String, double[]>, String> e : List.of(Map.entry(byHost, l.path("host").asText("")), Map.entry(byEntry, l.path("entry").asText("")))) {
                double[] v = e.getKey().computeIfAbsent(e.getValue(), k -> new double[4]);
                v[1] += l.path("cost").asDouble(0); v[3] += 1;
                if (thisMonth) { v[0] += l.path("cost").asDouble(0); v[2] += 1; }
            }
        }
        ObjectNode out = M.createObjectNode();
        out.put("month", ym);
        for (Map.Entry<String, Map<String, double[]>> part : List.of(Map.entry("hosts", byHost), Map.entry("entries", byEntry))) {
            ArrayNode a = out.putArray(part.getKey());
            for (Map.Entry<String, double[]> e : part.getValue().entrySet()) {
                ObjectNode o = a.addObject(); o.put("name", e.getKey());
                o.put("month", Math.round(e.getValue()[0] * 100) / 100.0); o.put("all", Math.round(e.getValue()[1] * 100) / 100.0);
                o.put("runs_month", (int) e.getValue()[2]); o.put("runs_all", (int) e.getValue()[3]);
                ModelEntries.Entry en = part.getKey().equals("entries") ? ModelEntries.get(e.getKey()) : null;
                if (en != null && en.cap() > 0) o.put("cap", en.cap());
            }
        }
        return out;
    }

    /** "$0.13" or "13 cents"-free plain dollars with two places. */
    public static String dollars(double d) { return String.format(Locale.ROOT, "$%.2f", d); }
}
