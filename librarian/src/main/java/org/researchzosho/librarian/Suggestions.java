package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.drive.Judge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the library suggests the person do next (0.6.6): candidates come from the shelves' own events, never from a model's free text — a
 * forecast whose date passed, a story the shelves have grown past, a question the person asked that a claim now answers, a question asked
 * again and again, a report that landed, a dossier not researched for months. Each is classified by a typed, bounded decision ("now",
 * "next" or "hold", with a probability) made by the drive's judge when it answers, else by rules; the person's marks (taken, dismissed,
 * later) are the outcomes a Brier score is kept over, so the library learns what resembles what the person took up. One file,
 * {@code suggestions/ledger.jsonl}, one JSON object a line.
 */
public final class Suggestions {
    private Suggestions() { }
    private static final ObjectMapper M = new ObjectMapper();

    public record Candidate(String kind, String subject, String text, String reason, String action, String cost, String trigger) { }
    public record Entry(String id, String kind, String subject, String text, String reason, String action, String cost, String made, String klass, Double pNow, Double pTake,
                        String judge, String outcome, String marked, String note) {
        /** Still in front of the person: unmarked, or marked later more than a week ago. */
        public boolean open() { return outcome == null || outcome.isEmpty() || (outcome.equals("later") && marked != null && LocalDate.parse(marked).plusDays(7).isBefore(LocalDate.now())); }
    }

    static final Pattern ARRIVAL = Pattern.compile("^- (\\d{4}-\\d{2}-\\d{2}) \\[(dossier-[^\\]]+)\\] (\\S+)(?: — (.*))?$");

    static Path ledger(LibraryStore store) { return store.root().resolve("suggestions").resolve("ledger.jsonl"); }

    /** The candidates the shelves give today, each with why and what it would take. */
    public static List<Candidate> candidates(LibraryStore store) throws IOException {
        List<Candidate> out = new ArrayList<>();
        LocalDate today = LocalDate.now(); String month = today.toString().substring(0, 7);
        int n = 0;
        for (Forecasts.Entry f : Forecasts.all(store)) {   // a forecast whose date passed wants scoring
            if (f.outcome() != null && !f.outcome().isEmpty() || f.by() == null || f.by().isEmpty()) continue;
            String by = f.by().length() >= 7 ? f.by().substring(0, 7) : f.by() + "-12";
            if (by.compareTo(month) <= 0 && n++ < 10) out.add(new Candidate("forecast-due", f.subject(), "Forecast " + f.id() + " came due: " + f.text(), "its date, " + f.by() + ", has passed; the report said " + f.likelihood() + (f.percent() == null ? "" : " (" + f.percent() + "%)"),
                    "researchzosho forecast " + f.id() + " happened|did-not|void", "a minute", f.by()));
        }
        n = 0;
        for (Dossiers.Entry d : Dossiers.list(store)) {
            if (d.story()) {   // a story the shelves have grown past
                Investigation st = Story.existing(store, d.subject());
                int grown = st == null ? 0 : d.claims() - st.findings().size();
                if (grown >= 5 && n++ < 10) out.add(new Candidate("story-grown", d.subject(), "The story of " + d.subject() + " is " + grown + " claims behind the shelves", "its last writing cited " + st.findings().size() + " claims; the dossier holds " + d.claims(),
                        "researchzosho story \"" + d.subject() + "\"", "a model run of minutes", d.lastRun()));
            }
            if (!d.lastRun().isEmpty() && LocalDate.parse(d.lastRun()).isBefore(today.minusDays(90)) && n++ < 10)   // a dossier not researched for months
                out.add(new Candidate("stale-dossier", d.subject(), d.subject() + " has not been researched since " + d.lastRun(), "the current state on its dossier is that old", "research the latest question on " + d.subject() + " again (the Refresh link on the dossier)", "a run of an hour or two", d.lastRun()));
        }
        LibrarianIndex index = new LibrarianIndex(store); n = 0; int m = 0;
        for (Frontier.Line l : Frontier.read(store)) {
            if (!l.open() || !l.type().equals("asked")) continue;
            String q = Frontier.bare(l.text());
            if (Frontier.looksLikeATask(q)) continue;   // a task that reached the frontier is not something the person waits on
            LibrarianIndex.Hit hit = answered(store, index, q);
            if (hit != null && n++ < 10) out.add(new Candidate("question-answered", "", "A question of yours may be answered: " + Acquisitions.compress(q, 90), "the claim " + hit.id() + " (" + Acquisitions.compress(hit.title(), 70) + ") carries most of its words", "read " + hit.id(), "a minute", l.date()));
            else if (Frontier.asks(l) >= 2 && m++ < 10) out.add(new Candidate("asked-again", "", "Asked " + Frontier.asks(l) + " times and still open: " + Acquisitions.compress(q, 90), "repeated demand is what the explorer acts on; a run would settle it", "researchzosho research ask \"" + q + "\"", "a run of an hour or two", l.date()));
        }
        n = 0;
        Path arrivals = Serials.arrivalsFile(store);   // what the watches on dossiers found this week
        if (Files.exists(arrivals)) {
            Map<String, String> queryOf = new HashMap<>(); for (Serials.Shelf sh : Serials.shelves(store)) queryOf.put(sh.slug(), sh.query());
            Map<String, List<String>> byShelf = new LinkedHashMap<>();
            for (String line : Files.readAllLines(arrivals, StandardCharsets.UTF_8)) {
                Matcher am = ARRIVAL.matcher(line.strip());
                if (!am.find() || LocalDate.parse(am.group(1)).isBefore(today.minusDays(7))) continue;
                byShelf.computeIfAbsent(am.group(2), k -> new ArrayList<>()).add(am.group(4) == null ? am.group(3) : am.group(4));
            }
            for (var e : byShelf.entrySet()) {
                String subject = queryOf.getOrDefault(e.getKey(), e.getKey().substring("dossier-".length()));
                if (n++ < 10) out.add(new Candidate("watched-arrival", subject, e.getValue().size() + (e.getValue().size() == 1 ? " new page arrived" : " new pages arrived") + " for the watch on " + subject + ": " + Acquisitions.compress(String.join("; ", e.getValue()), 120),
                        "the standing search on the subject found them this week; the arrival table lists them", "researchzosho add <url> admits one; a run on " + subject + " reads them", "a few minutes", today.toString()));
            }
        }
        if (Files.isDirectory(store.investigationsDir())) {   // a report that landed these two days
            List<Path> files; try (var s = Files.list(store.investigationsDir())) { files = s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList(); }
            for (Path p : files) {
                Investigation inv; try { inv = store.investigation(p.getFileName().toString().replaceFirst("\\.md$", "")); } catch (IOException | RuntimeException e) { continue; }
                if (inv == null || inv.title().startsWith("The story of ") || inv.recordedAt() == null || inv.recordedAt().length() < 10) continue;
                if (LocalDate.parse(inv.recordedAt().substring(0, 10)).isBefore(today.minusDays(2))) continue;
                if (n++ < 10) out.add(new Candidate("new-report", TimelineClaims.subjectOf(inv.title()) == null ? "" : TimelineClaims.subjectOf(inv.title()), "A report landed: " + Acquisitions.compress(inv.title(), 90), "written " + inv.recordedAt().substring(0, 10) + "; its claims are on the shelves and its questions on the frontier", "read " + inv.id(), "ten minutes", inv.recordedAt().substring(0, 10)));
            }
        }
        return out;
    }

    /** A question the person waits on: the frontier's own rule. */

    static final Set<String> SMALL = Set.of("what", "which", "when", "where", "does", "have", "this", "that", "with", "from", "their", "about", "into", "than", "then", "there", "these", "those", "should", "would", "could",
            "between", "since", "after", "before", "under", "over", "your", "today", "current", "status", "specific", "exact", "most", "common", "standard", "still", "already", "whether");
    /** The claim that answers a question: one whose title and text carry at least three of the question's words of substance and most of them — stricter than the frontier's hint, which only says "look here". */
    static LibrarianIndex.Hit answered(LibraryStore store, LibrarianIndex index, String question) {
        Set<String> want = new HashSet<>();
        for (String w : Frontier.terms(question)) if (w.length() >= 4 && !SMALL.contains(w) && !w.matches("\\d+")) want.add(w);
        if (want.size() < 3) return null;
        try {
            for (LibrarianIndex.Hit h : index.searchStrict(question, 3, null, "finding")) {
                if (!"accepted".equals(h.state()) && !"draft".equals(h.state())) continue;
                Finding f = store.finding(h.id()); if (f == null) continue;
                Set<String> has = Frontier.terms(f.title() + " " + f.body());
                int covered = 0; for (String w : want) if (has.contains(w)) covered++;
                if (covered >= 3 && covered * 10 >= want.size() * 6) return h;
            }
        } catch (IOException | RuntimeException ignored) { }
        return null;
    }

    /** The candidates not yet on the ledger, classified and filed; an open suggestion whose candidate is gone is withdrawn; the whole open list after. {@code judge} null: the rules decide. */
    public static List<Entry> refresh(LibraryStore store, Judge judge) throws IOException {
        List<Entry> have = all(store);
        List<Candidate> today = candidates(store);
        Set<String> todayKeys = new HashSet<>(); for (Candidate c : today) todayKeys.add(key(c.kind(), c.subject(), c.text()));
        Set<String> keys = new HashSet<>(); int max = 0; boolean withdrew = false;
        List<Entry> kept = new ArrayList<>();
        for (Entry e : have) {
            String k = key(e.kind(), e.subject(), e.text()); keys.add(k);
            try { max = Math.max(max, Integer.parseInt(e.id().substring(2))); } catch (NumberFormatException ignored) { }
            if (e.open() && !todayKeys.contains(k)) { kept.add(new Entry(e.id(), e.kind(), e.subject(), e.text(), e.reason(), e.action(), e.cost(), e.made(), e.klass(), e.pNow(), e.pTake(), e.judge(), "withdrawn", LocalDate.now().toString(), "the shelves no longer give this")); withdrew = true; }
            else kept.add(e);
        }
        Map<String, int[]> marks = marksByKind(have);
        for (Candidate c : today) {
            if (!keys.add(key(c.kind(), c.subject(), c.text()))) continue;
            String id = String.format(Locale.ROOT, "S-%04d", ++max);
            kept.add(classify(c, id, judge, marks.getOrDefault(c.kind(), new int[2])));
        }
        if (kept.size() != have.size() || withdrew) {
            StringBuilder lines = new StringBuilder(); for (Entry e : kept) lines.append(M.writeValueAsString(toJson(e))).append('\n');
            Files.createDirectories(ledger(store).getParent()); Files.writeString(ledger(store), lines.toString(), StandardCharsets.UTF_8);
        }
        return open(store);
    }

    static final List<String> OPTIONS = List.of("now — put it in front of them on their next visit", "next — keep it on the list for when they ask", "hold — not worth their time now");

    /** One bounded decision: now, next or hold, with the probability the person takes it up; the judge when it answers, else the rules. */
    static Entry classify(Candidate c, String id, Judge judge, int[] marks) {
        String klass = null; Double pNow = null, pTake = null; String by = "rules";
        if (judge != null) {
            try {
                String state = "CANDIDATE (" + c.kind() + "): " + c.text() + ". Why: " + c.reason() + ". What it would take: " + c.cost() + ". Trigger: " + c.trigger() + ".\n"
                        + "THE PERSON'S MARKS SO FAR on suggestions of this kind: " + marks[0] + " taken, " + marks[1] + " dismissed.\nTODAY: " + LocalDate.now() + ".";
                Judge.Answer a = judge.choice(state, "Should the library put this in front of the person now, keep it on the list for when they ask, or hold it?", OPTIONS);
                if (a.ran()) {
                    String ch = a.choice() == null ? "" : a.choice().toLowerCase(Locale.ROOT);
                    int idx = ch.startsWith("now") || ch.equals("a") ? 0 : ch.startsWith("next") || ch.equals("b") ? 1 : ch.startsWith("hold") || ch.equals("c") ? 2 : -1;
                    if (idx >= 0) {
                        klass = List.of("now", "next", "hold").get(idx); by = "judge";
                        pNow = prob(a, 0); pTake = Math.min(1.0, prob(a, 0) + prob(a, 1));
                    }
                }
            } catch (RuntimeException ignored) { }
        }
        if (klass == null) {
            switch (c.kind()) {
                case "forecast-due", "question-answered" -> { klass = "now"; pNow = 0.8; pTake = 0.9; }
                case "story-grown", "asked-again", "new-report", "watched-arrival" -> { klass = "next"; pNow = 0.2; pTake = 0.6; }
                default -> { klass = "hold"; pNow = 0.1; pTake = 0.3; }
            }
        }
        return new Entry(id, c.kind(), c.subject(), c.text(), c.reason(), c.action(), c.cost(), LocalDate.now().toString(), klass, pNow, pTake, by, null, null, null);
    }
    static double prob(Judge.Answer a, int i) {
        for (String k : new String[]{OPTIONS.get(i), String.valueOf((char) ('A' + i)), String.valueOf((char) ('a' + i)), OPTIONS.get(i).split(" ")[0]}) if (a.probabilities().containsKey(k)) return a.probabilities().get(k);
        return 0;
    }

    public static List<Entry> all(LibraryStore store) throws IOException {
        List<Entry> out = new ArrayList<>();
        Path p = ledger(store);
        if (!Files.exists(p)) return out;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n; try { n = M.readTree(line); } catch (IOException e) { continue; }
            out.add(new Entry(n.path("id").asText(""), n.path("kind").asText(""), n.path("subject").asText(""), n.path("text").asText(""), n.path("reason").asText(""), n.path("action").asText(""), n.path("cost").asText(""),
                    n.path("made").asText(""), n.path("class").asText(""), n.hasNonNull("p_now") ? n.get("p_now").asDouble() : null, n.hasNonNull("p_take") ? n.get("p_take").asDouble() : null, n.path("judge").asText("rules"),
                    n.hasNonNull("outcome") ? n.get("outcome").asText() : null, n.hasNonNull("marked") ? n.get("marked").asText() : null, n.hasNonNull("note") ? n.get("note").asText() : null));
        }
        return out;
    }

    /** The open suggestions, now first, then by the probability the person takes them up; an entry made more than thirty days ago is not open. */
    public static List<Entry> open(LibraryStore store) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all(store)) if (e.open() && !LocalDate.parse(e.made()).plusDays(30).isBefore(LocalDate.now())) out.add(e);
        out.sort((a, b) -> { int k = Integer.compare(rank(a.klass()), rank(b.klass())); return k != 0 ? k : Double.compare(b.pTake() == null ? 0 : b.pTake(), a.pTake() == null ? 0 : a.pTake()); });
        return out;
    }
    static int rank(String k) { return k.equals("now") ? 0 : k.equals("next") ? 1 : 2; }

    /** Marks one: taken, dismissed or later; the entry as it stands, or null when the ledger has no such id. */
    public static Entry mark(LibraryStore store, String id, String outcome, String note) throws IOException {
        String o = outcome == null ? "" : outcome.strip().toLowerCase(Locale.ROOT);
        if (!o.equals("taken") && !o.equals("dismissed") && !o.equals("later")) throw new IllegalArgumentException("A mark is taken, dismissed or later; \"" + outcome + "\" is none of them.");
        Entry found = null; StringBuilder lines = new StringBuilder();
        for (Entry e : all(store)) {
            Entry w = e;
            if (e.id().equalsIgnoreCase(id.strip())) { w = new Entry(e.id(), e.kind(), e.subject(), e.text(), e.reason(), e.action(), e.cost(), e.made(), e.klass(), e.pNow(), e.pTake(), e.judge(), o, LocalDate.now().toString(), note == null ? "" : note.strip()); found = w; }
            lines.append(M.writeValueAsString(toJson(w))).append('\n');
        }
        if (found == null) return null;
        Files.writeString(ledger(store), lines.toString(), StandardCharsets.UTF_8);
        store.circulate("suggestion-marked", found.id() + " " + o);
        return found;
    }

    /** The Brier score over the suggestions marked taken (1) or dismissed (0), against the probability given that the person would take them up; null when none is marked. */
    public static Double brier(List<Entry> entries) {
        double sum = 0; int n = 0;
        for (Entry e : entries) if (e.pTake() != null && ("taken".equals(e.outcome()) || "dismissed".equals(e.outcome()))) { double o = "taken".equals(e.outcome()) ? 1 : 0; sum += (e.pTake() - o) * (e.pTake() - o); n++; }
        return n == 0 ? null : sum / n;
    }

    static Map<String, int[]> marksByKind(List<Entry> entries) {
        Map<String, int[]> out = new TreeMap<>();
        for (Entry e : entries) { int[] m = out.computeIfAbsent(e.kind(), k -> new int[2]); if ("taken".equals(e.outcome())) m[0]++; else if ("dismissed".equals(e.outcome())) m[1]++; }
        return out;
    }

    /** The list as the command and the chat print it. */
    public static String text(List<Entry> open, List<Entry> all) {
        StringBuilder b = new StringBuilder();
        if (open.isEmpty()) b.append("Nothing to suggest right now: no forecast is due, no story is behind the shelves, no question of yours has an answer waiting, nothing new landed these two days.");
        else {
            b.append(open.size() == 1 ? "One suggestion" : open.size() + " suggestions").append(", the ones for now first. Mark one with: researchzosho suggest taken|dismissed|later <id>\n");
            for (Entry e : open) b.append("  ").append(e.id()).append(" [").append(e.klass()).append("] ").append(e.text()).append(" — ").append(e.reason()).append(". Do: ").append(e.action()).append(" (").append(e.cost()).append(").\n");
        }
        Double brier = brier(all);
        if (brier != null) b.append("Brier score over your marks: ").append(String.format(Locale.ROOT, "%.3f", brier)).append(" (0 is perfect, 0.25 is coin-tossing).");
        return b.toString().stripTrailing();
    }

    static ObjectNode toJson(Entry e) {
        ObjectNode n = M.createObjectNode();
        n.put("id", e.id()); n.put("kind", e.kind()); n.put("subject", e.subject()); n.put("text", e.text()); n.put("reason", e.reason()); n.put("action", e.action()); n.put("cost", e.cost()); n.put("made", e.made()); n.put("class", e.klass());
        if (e.pNow() != null) n.put("p_now", e.pNow()); if (e.pTake() != null) n.put("p_take", e.pTake()); n.put("judge", e.judge());
        if (e.outcome() != null) n.put("outcome", e.outcome()); if (e.marked() != null) n.put("marked", e.marked()); if (e.note() != null) n.put("note", e.note());
        return n;
    }
    static String key(String kind, String subject, String text) { return (kind + "|" + subject + "|" + text).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}|]+", " ").strip(); }
}
