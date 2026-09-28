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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.text.Normalizer;

/**
 * The research log a genealogist keeps, and every field needs: every search a run made for a subject, with the date, where it looked, the exact
 * words, the years, and how many records came back. Not only the findings are worth writing down: the searches are what tell
 * the next run, and the next person, what has been tried, with which spelling and in which collection, so that nobody repeats it
 * and a gap is a gap in the searching, not a guess. Written by the library from the tool calls a run made, never by the model.
 */
public final class SearchLog {
    private SearchLog() { }

    /**
     * One search: {@code where} is the collection's name or "web"; {@code found} how many results came back; {@code status} {@link #OK}
     * when the collection answered (a count of 0 is then a real miss) or {@link #FAILED} when it did not answer, which says nothing
     * about what it holds.
     */
    public record Entry(String date, String about, String where, String query, int fromYear, int toYear, int found, String job, String status) {
        public Entry(String date, String about, String where, String query, int fromYear, int toYear, int found, String job) { this(date, about, where, query, fromYear, toYear, found, job, OK); }
        public boolean failed() { return FAILED.equals(status); }
    }

    public static final String OK = "ok", FAILED = "failed";

    /** How a search tool says the backend was reached and every engine behind it was blocked or rate-limited. */
    public static final String DEGRADED = "SEARCH BACKEND DEGRADED";
    /** How a search tool says the exact query already ran in this worker: no search was made. */
    public static final String ALREADY = "ALREADY SEARCHED";

    /**
     * What a search tool's answer means for the log, from the tool's own words: {@link #OK}, {@link #FAILED}, or null when no search
     * was made at all (the same query again, a collection that does not exist, an empty query), which is not written down.
     */
    public static String outcome(String observation) {
        String o = observation == null ? "" : observation.strip();
        if (o.startsWith(ALREADY) || o.startsWith("ERROR: no source") || o.startsWith("ERROR: empty query")) return null;
        if (o.isEmpty() || o.startsWith("ERROR") || o.startsWith(DEGRADED)) return FAILED;
        return OK;
    }

    private static final ObjectMapper M = new ObjectMapper();

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("searches.jsonl"); }

    public static synchronized void add(LibraryStore store, List<Entry> entries) throws IOException {
        if (entries.isEmpty()) return;
        Files.createDirectories(file(store).getParent());
        StringBuilder b = new StringBuilder();
        for (Entry e : entries) {
            ObjectNode o = M.createObjectNode().put("date", e.date()).put("about", e.about()).put("where", e.where()).put("query", e.query()).put("from", e.fromYear()).put("to", e.toYear()).put("found", e.found()).put("job", e.job());
            if (e.failed()) o.put("status", FAILED);   // a line without it answered: the older lines are read that way
            b.append(M.writeValueAsString(o)).append('\n');
        }
        Files.writeString(file(store), b.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static List<Entry> all(LibraryStore store) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (!Files.exists(file(store))) return out;
        for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                JsonNode o = M.readTree(line);
                out.add(new Entry(o.path("date").asText(""), o.path("about").asText(""), o.path("where").asText(""), o.path("query").asText(""), o.path("from").asInt(0), o.path("to").asInt(0), o.path("found").asInt(0), o.path("job").asText(""), FAILED.equals(o.path("status").asText(OK)) ? FAILED : OK));
            } catch (IOException ignored) { }
        }
        return out;
    }

    /** The searches made for a subject: the lines whose subject is how this question begins, newest first. */
    public static List<Entry> about(LibraryStore store, String question) throws IOException {
        String lead = Looked.lead(question);
        List<Entry> out = new ArrayList<>();
        if (lead.isEmpty()) return out;
        for (Entry e : all(store)) if (Looked.lead(e.about()).equals(lead)) out.add(e);
        out.sort(Comparator.comparing(Entry::date).reversed());
        return out;
    }

    /** One line, as a person reads it. */
    public static String line(Entry e) {
        String years = e.fromYear() > 0 || e.toYear() > 0 ? " (" + (e.fromYear() > 0 ? e.fromYear() : "…") + "-" + (e.toYear() > 0 ? e.toYear() : "…") + ")" : "";
        return e.date() + "  " + e.where() + ": " + e.query() + years + "  → " + (e.failed() ? "did not answer" : e.found() == 0 ? "nothing" : e.found() + (e.found() == 1 ? " result" : " results")) + (e.job().isEmpty() ? "" : "  [" + e.job() + "]");
    }

    /** What a starting run is told about the searches already made for its subject. Empty when there were none. */
    public static String block(LibraryStore store, String question, int k) { return block(store, question, k, List.of()); }

    /**
     * The same for a run of these fields. The searches of a run somebody asked a field of are left out of a run that is not of that field:
     * an ordinary run on a name is not told what a family-history run searched for it. Then each of the run's fields adds what it works
     * out from the log ({@link Profile#knownBlock}).
     */
    public static String block(LibraryStore store, String question, int k, List<String> fields) {
        try {
            List<Entry> answered = new ArrayList<>(), unanswered = new ArrayList<>();
            for (Entry e : ofTheseFields(store, about(store, question), fields)) (e.failed() ? unanswered : answered).add(e);
            if (answered.isEmpty() && unanswered.isEmpty()) return "";
            // a search that did not answer once and answered later is an answered search
            unanswered.removeIf(f -> answered.stream().anyMatch(a -> a.where().equalsIgnoreCase(f.where()) && plain(a.query()).equals(plain(f.query()))));
            StringBuilder b = new StringBuilder();
            if (!answered.isEmpty()) {
                b.append("SEARCHED BEFORE ON THIS SUBJECT (the library's log of every search an earlier run made: where, the exact words, the years, and what came back. "
                        + "Search first where nobody has looked, and with words, a spelling or years that no line here has; repeat a search only with something the earlier one did not have):\n");
                for (Entry e : answered.subList(0, Math.min(k, answered.size()))) b.append("- ").append(line(e)).append('\n');
                if (answered.size() > k) b.append("- and ").append(answered.size() - k).append(" more\n");
            }
            if (!unanswered.isEmpty()) {
                b.append(b.isEmpty() ? "" : "\n").append("TRIED BEFORE, AND THE COLLECTION DID NOT ANSWER (the service was down, blocked or over its limit, so these lines say nothing about what it holds. "
                        + "Run these searches again, with the same words):\n");
                for (Entry e : unanswered.subList(0, Math.min(k, unanswered.size()))) b.append("- ").append(line(e)).append('\n');
                if (unanswered.size() > k) b.append("- and ").append(unanswered.size() - k).append(" more\n");
            }
            for (Profile p : Fields.enabled(store)) {
                if (!fields.contains(p.name())) continue;
                String more = p.knownBlock(store, question, answered);
                if (more != null && !more.isEmpty()) b.append('\n').append(more);
            }
            return b.append('\n').toString();
        } catch (IOException e) { return ""; }
    }

    /**
     * The searches a run of {@code fields} may be told of: all but those of runs of a field that joins only when asked, which only a run of
     * that field is told of.
     */
    static List<Entry> ofTheseFields(LibraryStore store, List<Entry> entries, List<String> fields) throws IOException {
        var shown = Fields.shownTo(store, fields);
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (shown.test(e.job())) out.add(e);
        return out;
    }

    /** Whether a query has every word of a name in it; a name in kanji or kana is looked for without its spaces. */
    static boolean carries(String query, String name) {
        if (name.isBlank()) return true;
        if (name.codePoints().anyMatch(c -> c >= 0x2E80)) return query.replaceAll("[\\s　\"“”]+", "").contains(name.replaceAll("[\\s　]+", ""));
        Set<String> words = new HashSet<>(Arrays.asList(query.split("[^\\p{L}\\p{N}]+")));
        return Arrays.stream(name.split("[^\\p{L}\\p{N}]+")).filter(w -> !w.isEmpty()).allMatch(words::contains);
    }

    /** A query as it is compared: accents and case folded, spaces collapsed, old kanji forms as today's. */
    static String plain(String s) { return KanjiForms.modern(Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip()); }
}
