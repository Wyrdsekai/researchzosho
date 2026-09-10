package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The frontier as the DEMAND side of the library. A desk answer of holds_nothing is a patron
 * saying what to acquire; it becomes a {@code [demand …]} line here (deduplicated — the same
 * question asked ten times is one gap), and the explorer crew researches open lines overnight,
 * marking each {@code ⇒ explored <date> <investigation>} so it is never researched twice.
 * Today's gap is tomorrow's shelf.
 */
public final class Frontier {

    private Frontier() { }

    /**
     * The types a line can be. New lines are filed with these names; older files used {@code gap …} and {@code demand …}
     * and are read by shape ({@link #typeOf}).
     * <ul>
     * <li>{@code report}: a sub-question a research run left open;</li>
     * <li>{@code asked}: a question the library could not answer at the desk (counts how often, ×N);</li>
     * <li>{@code person}: added by a person, at the command line, on the page, or through a program;</li>
     * <li>{@code dispute}: what would settle a disputed claim;</li>
     * <li>{@code check}: a source re-read failed; a chore for the inbox, never a research question.</li>
     * </ul>
     */
    public static final List<String> TYPES = List.of("report", "asked", "person", "dispute", "check");

    /** Which types the explorer may take: RESEARCHZOSHO_EXPLORER_TYPES, default asked, person and report; never check. */
    public static Set<String> explorerTypes() {
        String v = org.researchzosho.Config.get("RESEARCHZOSHO_EXPLORER_TYPES", "asked,person,report");
        Set<String> out = new HashSet<>();
        for (String t : v.toLowerCase(Locale.ROOT).split("[,\\s]+")) if (TYPES.contains(t) && !t.equals("check")) out.add(t);
        return out;
    }

    public record Line(String date, String kind, String text, String explored) {
        public boolean open() { return explored == null; }
        /** Parked: kept, never taken by the explorer until unparked. The mark is {@code ·parked} inside the brackets. */
        public boolean parked() { return kind.contains("·parked"); }
        public String type() { return typeOf(this); }
        /** The investigation that left this open, when a report did. */
        public String origin() {
            Matcher m = ORIGIN.matcher(text);
            return m.find() ? m.group(1) : null;
        }
        /** What the explorer may take tonight: open, not parked, of a type it researches, and asked often enough if asked. */
        public boolean researchable() {
            if (!open() || parked()) return false;
            String t = type();
            if (!explorerTypes().contains(t)) return false;
            return !t.equals("asked") || asks(this) >= MIN_ASKS;
        }
    }

    static final Pattern ORIGIN = Pattern.compile("\\((?:left open by|from) (I-[A-Za-z0-9_.-]+)\\)");

    /** The type of a line from its bracket, old spellings included. */
    public static String typeOf(Line l) {
        String k = l.kind().replace("·parked", "").strip().toLowerCase(Locale.ROOT);
        String head = k.split("[\\s×]+")[0];
        if (head.equals("dispute")) return k.contains("inventory") ? "check" : "dispute";   // the old inventory spelling, before "check"
        if (TYPES.contains(head)) return head;
        if (head.equals("demand")) return "asked";
        if (head.equals("gap")) {
            if (k.contains("person") || k.contains("patron") || k.contains("did:")) return "person";
            return "report";
        }
        return "report";
    }

    static final Pattern LINE = Pattern.compile("^- (\\d{4}-\\d{2}-\\d{2}) \\[([^\\]]*)\\] (.*?)(?: ⇒ explored (.*))?$");
    static final Pattern ASKS = Pattern.compile("×(\\d+)");

    /** How many times a demand line has been asked for (1 when unmarked). */
    public static int asks(Line l) {
        Matcher m = ASKS.matcher(l.kind());
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    /**
     * Demand the explorer may act on: asked at least this many times, by anyone. One patron's
     * one-off question — a conformance suite's "zebra crossings on the moon in 1740" cost the
     * explorer 67 minutes and put two trivia findings into canon (2026-09-04) — is not
     * acquisition demand; a question that keeps coming back is. Gaps ([gap], the desk's own
     * refusals) are always eligible. The person's own asks go through /research go, never here.
     */
    static final int MIN_ASKS = org.researchzosho.Config.getInt("RESEARCHZOSHO_EXPLORER_MIN_ASKS", 2);

    public static List<Line> read(LibraryStore store) throws IOException {
        List<Line> out = new ArrayList<>();
        if (!Files.exists(store.frontierFile())) return out;
        for (String raw : Files.readAllLines(store.frontierFile(), StandardCharsets.UTF_8)) {
            Matcher m = LINE.matcher(raw);
            if (m.matches()) out.add(new Line(m.group(1), m.group(2), m.group(3), m.group(4)));
        }
        return out;
    }

    /**
     * Record demand: a question the library could not answer. A new line is filed for a new
     * question; a near-duplicate (Jaccard ≥ 0.6 on terms) of an OPEN line bumps that line's ask
     * count instead ({@code [demand ×3 patron:…]}) — repeated demand is what the explorer acts on.
     * Returns true when a new line was filed.
     */
    public static boolean demand(LibraryStore store, String question, String who) throws IOException {
        String q = question == null ? "" : question.strip().replaceAll("\\s+", " ");
        if (q.length() < 12) return false;
        Set<String> qt = terms(q);
        return store.locked("frontier", () -> {
            for (Line l : read(store)) {
                if (jaccard(qt, terms(l.text())) >= 0.6) {
                    if (l.open() && l.type().equals("asked")) bump(store, l);
                    return false;
                }
            }
            store.frontier("asked " + who, q);
            return true;
        });
    }

    /** Rewrite one open demand line with its ask count incremented. */
    private static void bump(LibraryStore store, Line target) throws IOException {
        List<String> lines = Files.readAllLines(store.frontierFile(), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        boolean done = false;
        for (String raw : lines) {
            Matcher m = LINE.matcher(raw);
            if (!done && m.matches() && m.group(4) == null && m.group(3).equals(target.text()) && m.group(2).equals(target.kind())) {
                int n = asks(target) + 1;
                String kind = ASKS.matcher(target.kind()).find()
                        ? ASKS.matcher(target.kind()).replaceFirst("×" + n)
                        : target.kind().replaceFirst("^(demand|asked)", "$1 ×" + n);
                out.add("- " + m.group(1) + " [" + kind + "] " + m.group(3));
                done = true;
            } else {
                out.add(raw);
            }
        }
        Files.write(store.frontierFile(), out, StandardCharsets.UTF_8);
    }

    /** Move an open question to the head of the queue (the explorer takes the queue in file order). False when it is not open. */
    public static boolean next(LibraryStore store, String text) throws IOException { return reorder(store, text, true); }

    /** Move an open question to the tail of the queue. False when it is not open. */
    public static boolean later(LibraryStore store, String text) throws IOException { return reorder(store, text, false); }

    private static boolean reorder(LibraryStore store, String text, boolean toHead) throws IOException {
        if (!Files.exists(store.frontierFile())) return false;
        return store.locked("frontier", () -> {
            List<String> lines = Files.readAllLines(store.frontierFile(), StandardCharsets.UTF_8);
            String moving = null; List<String> rest = new ArrayList<>();
            for (String raw : lines) {
                Matcher m = LINE.matcher(raw);
                if (moving == null && m.matches() && m.group(4) == null && m.group(3).equals(text)) moving = raw; else rest.add(raw);
            }
            if (moving == null) return false;
            // the header (non-line text) stays on top; the moved line goes first or last among the lines
            int firstLine = 0; while (firstLine < rest.size() && !LINE.matcher(rest.get(firstLine)).matches()) firstLine++;
            if (toHead) rest.add(firstLine, moving); else rest.add(moving);
            Files.write(store.frontierFile(), rest, StandardCharsets.UTF_8);
            return true;
        });
    }

    /** Park an open question: kept, shown, never taken by the explorer. False when it is not open or already parked. */
    public static boolean park(LibraryStore store, String text) throws IOException { return mark(store, text, true); }

    /** Put a parked question back in the queue, at the tail. False when it is not parked. */
    public static boolean unpark(LibraryStore store, String text) throws IOException { return mark(store, text, false) && later(store, text); }

    private static boolean mark(LibraryStore store, String text, boolean park) throws IOException {
        if (!Files.exists(store.frontierFile())) return false;
        return store.locked("frontier", () -> {
            List<String> lines = Files.readAllLines(store.frontierFile(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(); boolean done = false;
            for (String raw : lines) {
                Matcher m = LINE.matcher(raw);
                if (!done && m.matches() && m.group(4) == null && m.group(3).equals(text) && m.group(2).contains("·parked") != park) {
                    String kind = park ? m.group(2).strip() + " ·parked" : m.group(2).replace("·parked", "").strip();
                    out.add("- " + m.group(1) + " [" + kind + "] " + m.group(3)); done = true;
                } else out.add(raw);
            }
            if (done) Files.write(store.frontierFile(), out, StandardCharsets.UTF_8);
            return done;
        });
    }

    /** Whether two questions belong in one run: the same report left them open, or their terms overlap enough. */
    public static boolean related(Line a, Line b) {
        if (a.origin() != null && a.origin().equals(b.origin())) return true;
        return jaccard(terms(strip(a.text())), terms(strip(b.text()))) >= 0.35;
    }

    /** The question without the "(left open by I-…)" note a report appends. */
    public static String strip(String text) { return ORIGIN.matcher(text).replaceAll("").replaceAll("\\s+", " ").strip(); }

    /**
     * How a report's leftover questions are filed: {@code RESEARCHZOSHO_REPORT_QUESTIONS} = {@code parked} (the default:
     * kept on the list, never researched until a person unparks them) or {@code queued} (straight into the explorer's
     * queue). A report leaves five to ten questions, one per perspective; parked by default keeps the record without
     * spending nights on questions nobody asked for.
     */
    public static boolean reportQuestionsParked() {
        return !org.researchzosho.Config.get("RESEARCHZOSHO_REPORT_QUESTIONS", "parked").trim().equalsIgnoreCase("queued");
    }

    /**
     * File the questions a report left open, ONCE: a question already on the ledger for the same report — open, explored
     * or dropped, or one the other's cut-short form — is not filed again. Two writers used to file them (the run when it
     * finished, the review when it ran), so every report's leftovers appeared twice (2026-09-09, 79 lines for 40 questions).
     * Returns how many were filed.
     */
    public static int fromReport(LibraryStore store, String invId, List<String> questions) throws IOException {
        if (questions == null || questions.isEmpty()) return 0;
        String kind = "report" + (reportQuestionsParked() ? " ·parked" : "");
        return store.locked("frontier", () -> {
            List<Line> have = new ArrayList<>();
            for (Line l : read(store)) if (invId.equals(l.origin())) have.add(l);
            int filed = 0;
            for (String q : questions) {
                String text = Acquisitions.compress(q, 400);
                if (text.length() < 12) continue;
                boolean dup = false;
                for (Line l : have) if (sameQuestion(l.text(), text)) { dup = true; break; }
                if (dup) continue;
                String full = text + " (left open by " + invId + ")";
                store.frontier(kind, full);
                have.add(new Line(LocalDate.now().toString(), kind, full, null));
                filed++;
            }
            return filed;
        });
    }

    /** The same question twice: equal once the origin note is removed, or one is the other's cut-short form ({@code …}). */
    static boolean sameQuestion(String a, String b) {
        String x = strip(a), y = strip(b);
        if (x.equalsIgnoreCase(y)) return true;
        if (x.endsWith("…") && y.length() > x.length() - 1 && y.toLowerCase(Locale.ROOT).startsWith(x.substring(0, x.length() - 1).toLowerCase(Locale.ROOT))) return true;
        return y.endsWith("…") && x.length() > y.length() - 1 && x.toLowerCase(Locale.ROOT).startsWith(y.substring(0, y.length() - 1).toLowerCase(Locale.ROOT));
    }

    /** The open lines {@link #tidy} would remove: a second copy of a question from the same report, or the same text twice. */
    public static List<Line> duplicates(LibraryStore store) throws IOException {
        List<Line> open = new ArrayList<>();
        for (Line l : read(store)) if (l.open()) open.add(l);
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) {
            Line a = open.get(i);
            for (int j = 0; j < i; j++) {
                Line b = open.get(j);
                if (out.contains(b)) continue;
                boolean same = a.origin() != null ? a.origin().equals(b.origin()) && sameQuestion(a.text(), b.text())
                        : b.origin() == null && strip(a.text()).equalsIgnoreCase(strip(b.text()));
                if (same) { out.add(strip(a.text()).length() > strip(b.text()).length() ? b : a); break; }
            }
        }
        return out;
    }

    /** Remove duplicate open lines (the shorter copy goes; a cut-short copy loses to the full one). Returns how many were removed. */
    public static int tidy(LibraryStore store) throws IOException {
        if (!Files.exists(store.frontierFile())) return 0;
        return store.locked("frontier", () -> {
            List<Line> dups = duplicates(store);
            if (dups.isEmpty()) return 0;
            java.util.Map<String, Integer> gone = new java.util.HashMap<>();   // counted: two identical lines are two copies to remove
            for (Line d : dups) gone.merge("- " + d.date() + " [" + d.kind() + "] " + d.text(), 1, Integer::sum);
            List<String> out = new ArrayList<>();
            int removed = 0;
            for (String raw : Files.readAllLines(store.frontierFile(), StandardCharsets.UTF_8)) {
                Integer n = gone.get(raw);
                if (n != null && n > 0) { gone.put(raw, n - 1); removed++; continue; }
                out.add(raw);
            }
            Files.write(store.frontierFile(), out, StandardCharsets.UTF_8);
            return removed;
        });
    }

    static final Pattern PERSPECTIVE = Pattern.compile("\\[([^\\[\\]]{2,60})\\]\\s*$");
    static final Pattern NAMED_LANGUAGE = Pattern.compile("(?i)\\bin ([A-Z][a-z]+)-language sources");

    /** The perspective a report's planner asked from, the {@code [Historian of Science]} tag at the end; null when there is none. */
    public static String perspective(String text) {
        Matcher m = PERSPECTIVE.matcher(strip(text));
        return m.find() ? m.group(1).strip() : null;
    }

    /** The question without its perspective tag and origin note. */
    public static String bare(String text) { return PERSPECTIVE.matcher(strip(text)).replaceAll("").strip(); }

    /**
     * The language a question is in, or asks for: a "sources written in X" question is that language; otherwise the script
     * it is written in (Japanese, Korean, Chinese, Russian, Greek, Arabic, Hebrew, Thai, Hindi), else English.
     */
    public static String language(String text) {
        String t = strip(text);
        Matcher m = NAMED_LANGUAGE.matcher(t);
        if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        int kana = 0, hangul = 0, han = 0, cyr = 0, greek = 0, arabic = 0, hebrew = 0, thai = 0, deva = 0;
        for (int cp : t.codePoints().toArray()) {
            switch (Character.UnicodeScript.of(cp)) {
                case HIRAGANA, KATAKANA -> kana++;
                case HANGUL -> hangul++;
                case HAN -> han++;
                case CYRILLIC -> cyr++;
                case GREEK -> greek++;
                case ARABIC -> arabic++;
                case HEBREW -> hebrew++;
                case THAI -> thai++;
                case DEVANAGARI -> deva++;
                default -> { }
            }
        }
        if (kana > 0) return "japanese";
        if (hangul > 0) return "korean";
        if (han > 0) return "chinese";
        if (cyr > 2) return "russian";
        if (greek > 2) return "greek";
        if (arabic > 2) return "arabic";
        if (hebrew > 2) return "hebrew";
        if (thai > 2) return "thai";
        if (deva > 2) return "hindi";
        return "english";
    }

    /** Groups of open questions that read alike (terms overlap, the origin not counted): each group's head is its first line, in queue order. */
    public static java.util.Map<String, List<Line>> similar(List<Line> open) {
        java.util.Map<String, List<Line>> out = new java.util.LinkedHashMap<>();
        List<Set<String>> terms = new ArrayList<>();
        for (Line l : open) terms.add(terms(bare(l.text())));
        int[] head = new int[open.size()];
        for (int i = 0; i < open.size(); i++) {
            head[i] = i;
            for (int j = 0; j < i; j++) if (head[j] == j && jaccard(terms.get(i), terms.get(j)) >= 0.35) { head[i] = j; break; }
        }
        for (int i = 0; i < open.size(); i++) out.computeIfAbsent(open.get(head[i]).text(), k -> new ArrayList<>()).add(open.get(i));
        out.values().removeIf(g -> g.size() < 2);
        return out;
    }

    /** Close an open question without researching it: it stays on record, marked dropped by {@code who}. False when it is not open. */
    public static boolean drop(LibraryStore store, String text, String who) throws IOException {
        boolean open = false;
        for (Line l : read(store)) if (l.open() && l.text().equals(text)) { open = true; break; }
        if (!open) return false;
        markExplored(store, text, "(dropped by " + who + ")");
        return true;
    }

    /** Mark a line explored, in place: {@code ⇒ explored <date> <result>}. */
    public static void markExplored(LibraryStore store, String text, String result) throws IOException {
        if (!Files.exists(store.frontierFile())) return;
        store.locked("frontier", () -> { markExploredLocked(store, text, result); return null; });
    }

    private static void markExploredLocked(LibraryStore store, String text, String result) throws IOException {
        List<String> lines = Files.readAllLines(store.frontierFile(), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        boolean done = false;
        for (String raw : lines) {
            Matcher m = LINE.matcher(raw);
            if (!done && m.matches() && m.group(4) == null && m.group(3).equals(text)) {
                out.add(raw + " ⇒ explored " + LocalDate.now() + " " + result);
                done = true;
            } else {
                out.add(raw);
            }
        }
        Files.write(store.frontierFile(), out, StandardCharsets.UTF_8);
    }

    static Set<String> terms(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3) out.add(w);
            else if (w.length() >= 1 && w.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) out.add(w);
        }
        // CJK runs: bigrams, so a JA question compares by more than one long token
        Matcher m = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]{2,}").matcher(s);
        while (m.find()) { String run = m.group(); for (int i = 0; i + 2 <= run.length(); i++) out.add(run.substring(i, i + 2)); }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a); inter.retainAll(b);
        Set<String> union = new HashSet<>(a); union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
