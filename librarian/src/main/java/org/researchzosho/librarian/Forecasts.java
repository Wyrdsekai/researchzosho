package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The forecast ledger (0.6.6): every dated forecast a dossier report made, with the likelihood it gave, kept until its date has passed
 * and a person scores it — happened, did not, or void. A forecast is never a claim on the shelves; the ledger is where a library learns
 * how well its reports foresee, the Brier score over the scored ones saying so (0 is perfect, 0.25 is coin-tossing). One file,
 * {@code forecasts/ledger.jsonl}, one JSON object a line.
 */
public final class Forecasts {
    private Forecasts() { }
    private static final ObjectMapper M = new ObjectMapper();

    public record Entry(String id, String subject, String report, String filed, String by, String text, String likelihood, Integer percent, String confidence,
                        String indicators, String outcome, String scored, String note) {
        boolean scoredAsOutcome() { return "happened".equals(outcome) || "did-not".equals(outcome); }
    }

    static Path ledger(LibraryStore store) { return store.root().resolve("forecasts").resolve("ledger.jsonl"); }

    public static List<Entry> all(LibraryStore store) throws IOException {
        List<Entry> out = new ArrayList<>();
        Path p = ledger(store);
        if (!Files.exists(p)) return out;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n; try { n = M.readTree(line); } catch (IOException e) { continue; }
            out.add(new Entry(n.path("id").asText(""), n.path("subject").asText(""), n.path("report").asText(""), n.path("filed").asText(""), n.path("by").asText(""), n.path("text").asText(""),
                    n.path("likelihood").asText(""), n.hasNonNull("percent") ? n.get("percent").asInt() : null, n.path("confidence").asText(""), n.path("indicators").asText(""),
                    n.hasNonNull("outcome") ? n.get("outcome").asText() : null, n.hasNonNull("scored") ? n.get("scored").asText() : null, n.hasNonNull("note") ? n.get("note").asText() : null));
        }
        return out;
    }

    public static List<Entry> of(LibraryStore store, String subject) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all(store)) if (e.subject().equalsIgnoreCase(subject.strip())) out.add(e);
        return out;
    }

    /** The report's forecasts onto the ledger, each once (the same subject, date and words is the same forecast); the ids filed. */
    public static List<String> file(LibraryStore store, Investigation inv, String subject, List<Analysis.Forecast> forecasts) throws IOException {
        List<String> filed = new ArrayList<>();
        if (forecasts.isEmpty()) return filed;
        List<Entry> have = all(store);
        Set<String> keys = new HashSet<>(); int max = 0;
        for (Entry e : have) { keys.add(key(e.subject(), e.by(), e.text())); try { max = Math.max(max, Integer.parseInt(e.id().replaceFirst("^P-", ""))); } catch (NumberFormatException ignored) { } }
        Files.createDirectories(ledger(store).getParent());
        StringBuilder lines = new StringBuilder();
        for (Analysis.Forecast f : forecasts) {
            if (!keys.add(key(subject, f.by(), f.text()))) continue;
            String id = String.format(Locale.ROOT, "P-%04d", ++max);
            ObjectNode o = M.createObjectNode();
            o.put("id", id); o.put("subject", subject); o.put("report", inv.id()); o.put("filed", LocalDate.now().toString()); o.put("by", f.by()); o.put("text", f.text());
            o.put("likelihood", f.likelihood()); if (f.percent() != null) o.put("percent", f.percent()); o.put("confidence", f.confidence()); o.put("indicators", f.indicators());
            lines.append(M.writeValueAsString(o)).append('\n');
            filed.add(id);
        }
        if (!filed.isEmpty()) Files.writeString(ledger(store), lines.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return filed;
    }

    /** Scores one: happened, did-not or void, with a note; the entry as it stands now, or null when the ledger has no such id. */
    public static Entry score(LibraryStore store, String id, String outcome, String note) throws IOException {
        String o = outcome == null ? "" : outcome.strip().toLowerCase(Locale.ROOT).replace("did not", "did-not").replace("didnot", "did-not");
        if (!o.equals("happened") && !o.equals("did-not") && !o.equals("void")) throw new IllegalArgumentException("An outcome is happened, did-not or void; \"" + outcome + "\" is none of them.");
        List<Entry> all = all(store); Entry found = null;
        StringBuilder lines = new StringBuilder();
        for (Entry e : all) {
            Entry w = e;
            if (e.id().equalsIgnoreCase(id.strip())) { w = new Entry(e.id(), e.subject(), e.report(), e.filed(), e.by(), e.text(), e.likelihood(), e.percent(), e.confidence(), e.indicators(), o, LocalDate.now().toString(), note == null ? "" : note.strip()); found = w; }
            ObjectNode n = M.createObjectNode();
            n.put("id", w.id()); n.put("subject", w.subject()); n.put("report", w.report()); n.put("filed", w.filed()); n.put("by", w.by()); n.put("text", w.text()); n.put("likelihood", w.likelihood());
            if (w.percent() != null) n.put("percent", w.percent()); n.put("confidence", w.confidence()); n.put("indicators", w.indicators());
            if (w.outcome() != null) n.put("outcome", w.outcome()); if (w.scored() != null) n.put("scored", w.scored()); if (w.note() != null) n.put("note", w.note());
            lines.append(M.writeValueAsString(n)).append('\n');
        }
        if (found == null) return null;
        Files.writeString(ledger(store), lines.toString(), StandardCharsets.UTF_8);
        store.circulate("forecast-scored", found.id() + " " + found.outcome() + (found.note().isEmpty() ? "" : ": " + found.note()));
        return found;
    }

    /** The Brier score over the forecasts scored happened or did-not that gave a number; null when none has. */
    public static Double brier(List<Entry> entries) {
        double sum = 0; int n = 0;
        for (Entry e : entries) if (e.scoredAsOutcome() && e.percent() != null) { double p = e.percent() / 100.0, o = "happened".equals(e.outcome()) ? 1 : 0; sum += (p - o) * (p - o); n++; }
        return n == 0 ? null : sum / n;
    }

    /** The ledger as the command prints it. */
    public static String text(List<Entry> entries) {
        if (entries.isEmpty()) return "No forecasts on the ledger yet. A research run on a person or an organisation writes them, dated, in its Forecasts section.";
        StringBuilder b = new StringBuilder(entries.size() + (entries.size() == 1 ? " forecast" : " forecasts") + " on the ledger. Score one once its date has passed: researchzosho forecast <id> happened|did-not|void [a note]\n");
        for (Entry e : entries) b.append("  ").append(e.id()).append(" — ").append(e.subject()).append(", by ").append(e.by().isEmpty() ? "no date" : e.by()).append(": ").append(e.text())
                .append(e.likelihood().isEmpty() ? "" : " — " + e.likelihood()).append(e.percent() == null ? "" : " (" + e.percent() + "%)").append(e.confidence().isEmpty() ? "" : ", confidence " + e.confidence())
                .append(" — ").append(e.outcome() == null || e.outcome().isEmpty() ? "open" : e.outcome() + (e.scored() == null ? "" : " (" + e.scored() + ")")).append('\n');
        Double brier = brier(entries);
        if (brier != null) b.append("Brier score over the scored forecasts: ").append(String.format(Locale.ROOT, "%.3f", brier)).append(" (0 is perfect, 0.25 is coin-tossing).\n");
        return b.toString().stripTrailing();
    }

    static String key(String subject, String by, String text) { return (subject + "|" + by + "|" + text).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}|]+", " ").strip(); }
}
