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

    public record Line(String date, String kind, String text, String explored) {
        public boolean open() { return explored == null; }
        public boolean researchable() {
            if (!open()) return false;
            if (kind.startsWith("gap")) return true;
            return kind.startsWith("demand") && asks(this) >= MIN_ASKS;
        }
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
                    if (l.open() && l.kind().startsWith("demand")) bump(store, l);
                    return false;
                }
            }
            store.frontier("demand " + who, q);
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
                        : target.kind().replaceFirst("^demand", "demand ×" + n);
                out.add("- " + m.group(1) + " [" + kind + "] " + m.group(3));
                done = true;
            } else {
                out.add(raw);
            }
        }
        Files.write(store.frontierFile(), out, StandardCharsets.UTF_8);
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
