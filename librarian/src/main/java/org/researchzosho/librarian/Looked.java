package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where somebody looked and found nothing, with the date. "No patents were found" is not a fact about the world: it is what one
 * search, on one day, in the places it could reach, came back with. Filed as a claim it reads as settled, and the next search
 * does not look, although a new collection, a new spelling or a new key has arrived since. So it is kept here, as what it is:
 * on this date, this was looked for, in these places, and nothing came back. The ledger grows: a later search that looks again
 * adds its own line, and a run is shown the lines about its subject as places already looked in, apart from the claims.
 */
public final class Looked {
    private Looked() { }

    /** One search that came back empty. {@code about}: whom or what; {@code what}: what was looked for, in the search's words; {@code where}: the collections and sites; {@code report}: the write-up it is from; {@code asked}: how that write-up's question began. */
    public record Entry(String date, String about, String what, List<String> where, String report, String asked) { }

    private static final ObjectMapper M = new ObjectMapper();

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("looked.jsonl"); }

    /** Add a line, unless the same report already said the same thing of the same subject. */
    public static synchronized boolean add(LibraryStore store, Entry e) throws IOException {
        if (e.about().isBlank() && e.what().isBlank()) return false;
        for (Entry had : all(store)) if (had.report().equals(e.report()) && fold(had.about()).equals(fold(e.about())) && fold(had.what()).equals(fold(e.what()))) return false;
        ObjectNode o = M.createObjectNode().put("date", e.date()).put("about", e.about()).put("what", e.what()).put("report", e.report()).put("asked", e.asked());
        e.where().forEach(o.putArray("where")::add);
        Files.createDirectories(file(store).getParent());
        Files.writeString(file(store), M.writeValueAsString(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return true;
    }

    public static List<Entry> all(LibraryStore store) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (!Files.exists(file(store))) return out;
        for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                JsonNode o = M.readTree(line);
                List<String> where = new ArrayList<>(); o.path("where").forEach(w -> where.add(w.asText()));
                out.add(new Entry(o.path("date").asText(""), o.path("about").asText(""), o.path("what").asText(""), where, o.path("report").asText(""), o.path("asked").asText("")));
            } catch (IOException ignored) { }   // a damaged line is skipped, the ledger is still read
        }
        return out;
    }

    /**
     * The lines about what a question is about, newest first. A line is about the question when the question carries its subject:
     * the whole name, or two of its words (one, when the name is one word), a bracketed note aside. A name in kanji is matched without its spaces.
     */
    public static List<Entry> about(LibraryStore store, String question, int k) throws IOException {
        String q = fold(question), packed = q.replace(" ", "");
        List<Entry> out = new ArrayList<>();
        for (Entry e : all(store)) {
            String name = fold(e.about().replaceAll("\\s*[(（][^)）]*[)）]\\s*", " "));
            if (name.isBlank()) continue;
            boolean hit = packed.contains(name.replace(" ", ""));
            if (!hit) {
                Set<String> words = new LinkedHashSet<>();
                for (String w : name.split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3) words.add(w);
                long found = words.stream().filter(w -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w) + "(?![\\p{L}\\p{N}])").matcher(q).find()).count();
                hit = !words.isEmpty() && found >= Math.min(2, words.size());
            }
            // the search wrote the name its own way (romanised, say) and this question writes it another: the same subject when both questions begin with it
            if (!hit && !lead(e.asked()).isEmpty()) hit = lead(e.asked()).equals(lead(question));
            if (hit) out.add(e);
        }
        out.sort(Comparator.comparing(Entry::date).reversed());
        return out.size() > k ? out.subList(0, k) : out;
    }

    /** What a starting run is told, apart from the library's claims. Empty when nothing was looked for before. */
    public static String block(LibraryStore store, String question, int k) { return block(store, question, k, List.of()); }

    /**
     * The same for a run of these fields: what a run of a field that joins only when asked looked for is left out of a run that is not of
     * that field, as the search log leaves out its searches ({@link Fields#shownTo}).
     */
    public static String block(LibraryStore store, String question, int k, List<String> fields) {
        try {
            var shown = Fields.shownTo(store, fields);
            List<Entry> lines = about(store, question, Integer.MAX_VALUE).stream().filter(e -> shown.test(e.report())).limit(k).toList();
            if (lines.isEmpty()) return "";
            StringBuilder b = new StringBuilder("LOOKED FOR BEFORE, AND NOT FOUND THEN (each line is one earlier search: its date, what it looked for, and where. "
                    + "It tells you where somebody has looked; it does not tell you what exists. Collections, spellings and access change: look in other places and under other written forms first, "
                    + "and look in the same place again when you have a form of the name or a collection that the earlier search did not have):\n");
            for (Entry e : lines) b.append("- ").append(line(e)).append('\n');
            return b.append('\n').toString();
        } catch (IOException e) { return ""; }
    }

    /** One line, as people and runs read it: "on 2026-09-20 a search found nothing: …". */
    public static String line(Entry e) {
        return "on " + e.date() + " a search found nothing" + (e.about().isBlank() ? "" : " about " + e.about()) + ": " + Acquisitions.compress(e.what(), 260)
                + (e.where().isEmpty() ? "" : " Looked in: " + String.join(", ", e.where()) + ".") + (e.report().isBlank() ? "" : " [" + e.report() + "]");
    }

    /**
     * The lines a report's own section "Searched and not found" holds. The library writes that section from what the searches
     * returned, so these are counted and not said: a collection, the words searched, nothing back.
     */
    public static List<Entry> fromReport(String reportId, String asked, String body, String date) {
        List<Entry> out = new ArrayList<>();
        Matcher section = Pattern.compile("(?ms)^#{2,4} Searched and not found\\s*$(.*?)(?=^#{1,4} |\\z)").matcher(body == null ? "" : body);
        if (!section.find()) return out;
        Matcher row = Pattern.compile("(?m)^- (.+?): (.+)$").matcher(section.group(1));
        while (row.find()) out.add(new Entry(date, row.group(2).strip(), "a search of this collection for these words returned nothing", List.of(row.group(1).strip()), reportId, asked));
        return out;
    }

    /** The places of a claim's sources, as short names: a site's host, a citation as it is. */
    public static List<String> places(List<String> locators) {
        Set<String> out = new LinkedHashSet<>();
        for (String l : locators) {
            try { String h = URI.create(l).getHost(); out.add(h == null ? l : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "")); } catch (RuntimeException e) { out.add(l); }
        }
        return new ArrayList<>(out);
    }

    /**
     * Move a claim that says "nothing was found" to this ledger: a line with the claim's own date, subject, words and sources, and the
     * claim retired. For the claims of this kind that were filed before the library kept them apart. Returns the line, or null when there is no such claim.
     */
    public static Entry move(LibraryStore store, String findingId) throws IOException {
        Finding f = store.finding(findingId);
        if (f == null) return null;
        String report = "";
        for (Finding.Source src : f.sources()) { Matcher m = Pattern.compile("cited by (I-\\S+)").matcher(src.whyItMatters() == null ? "" : src.whyItMatters()); if (m.find()) { report = m.group(1); break; } }
        Investigation inv = report.isEmpty() ? null : store.investigation(report);
        String date = f.recordedAt() != null && f.recordedAt().length() >= 10 ? f.recordedAt().substring(0, 10) : today();
        Entry e = new Entry(date, f.triple() == null ? f.title() : f.triple().subject(), f.body().strip(), places(f.sources().stream().map(Finding.Source::locator).toList()), report, inv == null ? "" : inv.title());
        add(store, e);
        if (f.state() != Finding.State.retired) new Council(store).retire(f.id());
        return e;
    }

    /** How a question begins: up to the first bracket or colon. A question about a person begins with their name. */
    static String lead(String question) { return fold((question == null ? "" : question).split("[(（:：]", 2)[0]).replace(" ", ""); }

    public static String today() { return LocalDate.now().toString(); }

    private static String fold(String s) { return KanjiForms.modern(Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip()); }
}
