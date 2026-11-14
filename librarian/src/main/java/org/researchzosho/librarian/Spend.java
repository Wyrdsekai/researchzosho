package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the library's model calls cost (0.6.2): one line per run in {@code catalog/spend.jsonl} — when, which job and report, which named
 * model, host and model id, the tokens and the dollars at the entry's prices — so a person sees what a month on a host came to, and a cap
 * on an entry can refuse a run before it starts. The figures are the library's arithmetic on the servers' usage counts at list prices,
 * not the host's bill.
 *
 * <p>Since 0.7.5 the calls outside those runs are on it too ({@link SpendPurse}): a chat turn, the nightly work, the steps of a job around
 * its run, one line a piece of work and a model, kind {@code calls}; and every new line says what it was for ({@code for}: chat, nightly,
 * research, translation, picture, other). A line written before says it by its kind ({@link #purposeOf}).
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
        o.put("for", purposeOf(o));
        o.put("t", Instant.now().toString()); o.put("job", jobId == null ? "" : jobId); o.put("report", reportId == null ? "" : reportId);
        o.put("entry", entry.name()); o.put("host", entry.host()); o.put("model", entry.model());
        o.put("prompt", usage[0]); o.put("cached", usage[1]); o.put("completion", usage[2]);
        o.put("cost", Math.round(cost * 10000) / 10000.0); o.put("priced", entry.prices() != null);
        append(store, o);
    }

    /** The kind of the lines that hold the calls of a piece of work outside a run's own (a chat turn, the nightly work, a job's other steps). */
    static final String CALLS = "calls";

    /**
     * What a line's calls were for: its {@code for}; a line written before that field by its kind: research and quick are research,
     * translate is translation, pictures is picture, chat is chat.
     */
    static String purposeOf(JsonNode l) {
        String f = l.path("for").asText("");
        if (!f.isEmpty()) return f;
        return switch (l.path("kind").asText("research")) {
            case "translate" -> SpendPurse.TRANSLATION;
            case "pictures" -> SpendPurse.PICTURE;
            case "chat" -> SpendPurse.CHAT;
            default -> SpendPurse.RESEARCH;
        };
    }

    /**
     * The calls of one piece of work on one model: {@code u} is {prompt, cached, completion, calls}. Written only when they cost money: on a
     * hosted service, or on a named model with prices; the named model is the one with this server and model, else the host alone. Never
     * throws: the ledger is a record, not a gate.
     */
    static void calls(LibraryStore store, String purpose, String jobId, String drive, String model, long[] u) {
        try {
            if (u[0] + u[2] == 0) return;
            ModelEntries.Entry e = entryOf(drive, model);
            boolean hosted = e != null ? e.hosted() : new ModelEntries.Entry("", drive, model, null, "", 0, "").hosted();
            if (!hosted && (e == null || e.prices() == null)) return;   // a model on the library's own machine with no prices costs nothing
            if (e == null) e = new ModelEntries.Entry("", drive, model, null, "", 0, "");
            ObjectNode o = M.createObjectNode();
            o.put("kind", CALLS); o.put("for", purpose);
            o.put("t", Instant.now().toString()); o.put("job", jobId == null ? "" : jobId); o.put("report", "");
            o.put("entry", e.name()); o.put("host", e.host()); o.put("model", e.model());
            o.put("prompt", u[0]); o.put("cached", u[1]); o.put("completion", u[2]); o.put("calls", u.length > 3 ? u[3] : 1);
            o.put("cost", Math.round(e.cost(u[0], u[1], u[2]) * 10000) / 10000.0); o.put("priced", e.prices() != null);
            append(store, o);
        } catch (IOException | RuntimeException ignored) { /* a record, never a reason to lose the work */ }
    }

    /** The named model for a server and a model: one with prices first, then any other, the default entry last; null when none. */
    static ModelEntries.Entry entryOf(String drive, String model) {
        String d = Config.driveBase(drive == null ? "" : drive);
        ModelEntries.Entry found = null;
        for (ModelEntries.Entry e : ModelEntries.all().values()) {
            if (!Config.driveBase(e.drive()).equals(d) || !e.model().equals(model)) continue;
            if (found == null || (found.prices() == null && e.prices() != null) || (found.name().equals(ModelEntries.DEFAULT) && found.prices() == null)) found = e;
            if (e.prices() != null && !e.name().equals(ModelEntries.DEFAULT)) return e;
        }
        return found;
    }

    private static synchronized void append(LibraryStore store, ObjectNode o) throws IOException {
        NotHolder.check(file(store));
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

    /**
     * This month and all time, per host, per entry and per use (what the calls were for): for the settings page and the spend command. A
     * host's or an entry's dollars are every line's; its runs are the runs' lines alone, and what the calls outside a run cost is said
     * apart ({@code calls_month}, {@code calls_all}).
     */
    public static ObjectNode summary(LibraryStore store) {
        String ym = YearMonth.now().toString();
        // {this month, all time, runs this month, runs all time, then of those the runs at prices the entry did not state: this month, all time,
        // and the tokens their servers counted this month, sent and written; then the calls outside a run: dollars this month, all time, lines
        // at prices, lines without them this month, and their tokens this month, sent and written}: a run with no prices costs no dollars anyone can add up
        Map<String, double[]> byHost = new LinkedHashMap<>(), byEntry = new LinkedHashMap<>(), byUse = new LinkedHashMap<>();
        for (String u : List.of(SpendPurse.CHAT, SpendPurse.NIGHTLY, SpendPurse.RESEARCH, SpendPurse.TRANSLATION, SpendPurse.PICTURE, SpendPurse.OTHER)) byUse.put(u, null);
        for (JsonNode l : lines(store)) {
            boolean thisMonth = l.path("t").asText("").startsWith(ym), priced = l.path("priced").asBoolean(true), calls = CALLS.equals(l.path("kind").asText(""));
            double cost = l.path("cost").asDouble(0);
            List<Map.Entry<Map<String, double[]>, String>> parts = new ArrayList<>(List.of(Map.entry(byHost, l.path("host").asText(""))));
            if (!l.path("entry").asText("").isEmpty()) parts.add(Map.entry(byEntry, l.path("entry").asText("")));
            for (Map.Entry<Map<String, double[]>, String> e : parts) {
                double[] v = e.getKey().get(e.getValue());
                if (v == null) e.getKey().put(e.getValue(), v = new double[15]);
                v[1] += cost; if (thisMonth) v[0] += cost;
                if (calls) {
                    v[9] += cost; if (thisMonth) v[8] += cost;
                    if (priced) v[10] += 1;
                    else { v[14] += 1; if (thisMonth) { v[11] += 1; v[12] += l.path("prompt").asLong(0); v[13] += l.path("completion").asLong(0); } }
                    continue;
                }
                v[3] += 1; if (thisMonth) v[2] += 1;
                if (!priced) { v[5] += 1; if (thisMonth) { v[4] += 1; v[6] += l.path("prompt").asLong(0); v[7] += l.path("completion").asLong(0); } }
            }
            // {this month, all time, lines this month, lines all time, lines at prices all time, tokens at no prices this month, sent and written}
            double[] u = byUse.get(purposeOf(l));
            if (u == null) byUse.put(purposeOf(l), u = new double[7]);
            u[1] += cost; u[3] += 1; if (thisMonth) { u[0] += cost; u[2] += 1; }
            if (priced) u[4] += 1; else if (thisMonth) { u[5] += l.path("prompt").asLong(0); u[6] += l.path("completion").asLong(0); }
        }
        ObjectNode out = M.createObjectNode();
        out.put("month", ym);
        for (Map.Entry<String, Map<String, double[]>> part : List.of(Map.entry("hosts", byHost), Map.entry("entries", byEntry))) {
            ArrayNode a = out.putArray(part.getKey());
            for (Map.Entry<String, double[]> e : part.getValue().entrySet()) {
                ObjectNode o = a.addObject(); o.put("name", e.getKey());
                o.put("month", Math.round(e.getValue()[0] * 100) / 100.0); o.put("all", Math.round(e.getValue()[1] * 100) / 100.0);
                o.put("runs_month", (int) e.getValue()[2]); o.put("runs_all", (int) e.getValue()[3]);
                if (e.getValue()[5] > 0) {   // runs at prices the entry did not state: counted apart, in tokens, never added into the dollars as nothing
                    o.put("unpriced_runs_month", (int) e.getValue()[4]); o.put("unpriced_runs_all", (int) e.getValue()[5]);
                    o.put("unpriced_sent_month", (long) e.getValue()[6]); o.put("unpriced_written_month", (long) e.getValue()[7]);
                }
                if (e.getValue()[9] > 0 || e.getValue()[10] > 0) { o.put("calls_month", Math.round(e.getValue()[8] * 100) / 100.0); o.put("calls_all", Math.round(e.getValue()[9] * 100) / 100.0); }
                if (e.getValue()[14] > 0) {   // calls outside a run at prices the entry did not state: in tokens, as such runs are
                    o.put("unpriced_calls_month", (int) e.getValue()[11]); o.put("unpriced_calls_all", (int) e.getValue()[14]);
                    o.put("unpriced_calls_sent_month", (long) e.getValue()[12]); o.put("unpriced_calls_written_month", (long) e.getValue()[13]);
                }
                ModelEntries.Entry en = part.getKey().equals("entries") ? ModelEntries.get(e.getKey()) : null;
                if (en != null && en.cap() > 0) { o.put("cap", en.cap()); if (!ModelEntries.capCounts(en)) o.put("cap_counts", false); }
            }
        }
        // what the calls were for: the chat, the nightly work, research, translation, pictures, other work; a use with no line is left out
        ArrayNode uses = out.putArray("uses");
        for (Map.Entry<String, double[]> e : byUse.entrySet()) {
            if (e.getValue() == null) continue;
            double[] u = e.getValue();
            ObjectNode o = uses.addObject(); o.put("for", e.getKey());
            o.put("month", Math.round(u[0] * 100) / 100.0); o.put("all", Math.round(u[1] * 100) / 100.0);
            o.put("lines_month", (int) u[2]); o.put("lines_all", (int) u[3]);
            if (u[4] < u[3]) { o.put("unpriced_sent_month", (long) u[5]); o.put("unpriced_written_month", (long) u[6]); }
            if (u[4] == 0) o.put("priced", false);   // every line at prices no entry states: its dollars are not a sum anyone can use
        }
        return out;
    }

    /**
     * One row of the summary in words: the dollars and runs at list prices; the runs at prices the entry did not state, apart, with the
     * tokens counted this month; the cap, and that it counts nothing when the entry has no prices. "kimi: runs at prices the entry does not
     * state: 1 this month (695 tokens sent, 21 written), 1 in all; cap $0.05 a month, which counts nothing until the entry has prices".
     */
    public static String rowSaid(JsonNode r, Chrome c) {
        int runsMonth = r.path("runs_month").asInt(), runsAll = r.path("runs_all").asInt(), freeMonth = r.path("unpriced_runs_month").asInt(), freeAll = r.path("unpriced_runs_all").asInt();
        boolean pricedCalls = r.has("calls_all"); int freeCallsAll = r.path("unpriced_calls_all").asInt();
        List<String> parts = new ArrayList<>();
        if (runsAll - freeAll > 0 || pricedCalls || (freeAll == 0 && freeCallsAll == 0)) parts.add(c.t(runsMonth - freeMonth == 1 ? "{0} this month ({1} run), {2} in all ({3})" : "{0} this month ({1} runs), {2} in all ({3})", dollars(r.path("month").asDouble()), runsMonth - freeMonth, dollars(r.path("all").asDouble()), runsAll - freeAll));
        if (pricedCalls) parts.add(c.t("of which outside a run: {0}", c.t("{0} this month, {1} in all", dollars(r.path("calls_month").asDouble()), dollars(r.path("calls_all").asDouble()))));
        if (freeAll > 0) parts.add(c.t("runs at prices the entry does not state: {0} this month ({1} tokens sent, {2} written), {3} in all", freeMonth, c.number(r.path("unpriced_sent_month").asLong()), c.number(r.path("unpriced_written_month").asLong()), freeAll));
        if (freeCallsAll > 0) parts.add(c.t("outside a run, at prices the entry does not state: {0} tokens sent, {1} written this month", c.number(r.path("unpriced_calls_sent_month").asLong()), c.number(r.path("unpriced_calls_written_month").asLong())));
        String cap = !r.has("cap") ? "" : ", " + c.t("cap {0} a month", dollars(r.path("cap").asDouble())) + (r.path("cap_counts").asBoolean(true) ? "" : c.t(", which counts nothing until the entry has prices"));
        return r.path("name").asText() + ": " + String.join("; ", parts) + cap;
    }

    /** What a use is called: the chat, the nightly work, research, translation, pictures, other work. */
    static String useSaid(String purpose, Chrome c) {
        return switch (purpose) {
            case SpendPurse.CHAT -> c.t("the chat");
            case SpendPurse.NIGHTLY -> c.t("the nightly work");
            case SpendPurse.RESEARCH -> c.t("research");
            case SpendPurse.TRANSLATION -> c.t("translation");
            case SpendPurse.PICTURE -> c.t("pictures");
            default -> c.t("other work");
        };
    }

    /** One use in words: "the chat: $0.30 this month, $0.60 in all"; the tokens at prices no entry states, apart. */
    public static String useRowSaid(JsonNode u, Chrome c) {
        List<String> parts = new ArrayList<>();
        if (u.path("priced").asBoolean(true)) parts.add(c.t("{0} this month, {1} in all", dollars(u.path("month").asDouble()), dollars(u.path("all").asDouble())));
        if (u.has("unpriced_sent_month")) parts.add(c.t("at prices the entry does not state: {0} tokens sent, {1} written this month", c.number(u.path("unpriced_sent_month").asLong()), c.number(u.path("unpriced_written_month").asLong())));
        return useSaid(u.path("for").asText(), c) + ": " + String.join("; ", parts);
    }

    /** "$0.13" or "13 cents"-free plain dollars with two places. */
    public static String dollars(double d) { return String.format(Locale.ROOT, "$%.2f", d); }
}
