package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.Shelf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * "Like X" (0.6.0): the works nearest X by what the judge found alike — subject first (the labels' themes, the windows' topics, the
 * record's subjects and tags, as a set), then kind, audience and demand, then the moods and the scales at a small weight; the form left
 * out (closer in form read as <em>less</em> alike), the dynamics kept for browsing; X's own maker set aside into a side list, because
 * "more by the same author" is the old mechanism and not a similarity; popularity shown as a cue on the row and never the order; the
 * reason per dimension on every row. The weights are the preferences file's, the settled defaults until the reactions hold enough
 * judged pairs to learn them. Measured against the judge: the subject-bearing spaces beat the form by a clear margin on fresh books and
 * are indistinguishable from one another (docs/REC_BOOKS.md), so no finer order is claimed than the list.
 */
public final class LikeX {
    private LikeX() { }

    static final Set<String> STOP = Set.of("the", "and", "of", "a", "in", "to", "for", "on", "with", "an", "or", "its", "their", "his", "her", "life", "story", "stories", "fiction", "novel", "book", "books", "general", "etc");

    /** The subject words of a phrase: lower case, letters and digits, three letters or more, the empty words out. */
    public static Set<String> tokens(String phrase) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : Shelf.norm(phrase).split(" ")) if (w.length() > 2 && !STOP.contains(w)) out.add(w);
        return out;
    }

    /** What a work is about, as words: the labelled themes and topics, the record's subjects and tags. */
    public static Set<String> subjectTokens(ObjectNode doc) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode l = doc.path("labelled"), r = doc.path("record");
        for (JsonNode t : l.path("book").path("themes")) out.addAll(tokens(t.asText()));
        for (JsonNode t : l.path("topics")) out.addAll(tokens(t.asText()));
        for (JsonNode t : r.path("subjects")) out.addAll(tokens(t.asText()));
        for (JsonNode t : r.path("tags")) out.addAll(tokens(t.asText()));
        return out;
    }

    /** The moods a work carries: those named among the two most in a quarter of its windows or more. */
    public static Set<String> moods(ObjectNode doc) {
        Set<String> out = new TreeSet<>();
        doc.path("labelled").path("mood").fields().forEachRemaining(e -> { if (e.getValue().asDouble() >= 0.25) out.add(e.getKey()); });
        return out;
    }

    static double jaccardDistance(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 1.0;
        int both = 0; for (String x : a) if (b.contains(x)) both++;
        return 1.0 - both / (double) (a.size() + b.size() - both);
    }

    static final Map<String, Integer> RANGE = Map.of("pace", 2, "darkness", 2, "style", 2, "humour", 2, "scale", 3);

    /** The mean distance over the scales both works have, each as a share of its range; null when they share none. */
    static Double scaleDistance(ObjectNode x, ObjectNode y) {
        double sum = 0; int n = 0;
        for (Map.Entry<String, Integer> e : RANGE.entrySet()) {
            JsonNode a = x.path("labelled").path("scales").path(e.getKey()), b = y.path("labelled").path("scales").path(e.getKey());
            if (!a.has("mean") || !b.has("mean")) continue;
            sum += Math.abs(a.path("mean").asDouble() - b.path("mean").asDouble()) / e.getValue(); n++;
        }
        return n == 0 ? null : sum / n;
    }

    static String kind(ObjectNode d) { return d.path("labelled").path("kind").asText("").toLowerCase(Locale.ROOT); }
    static String audience(ObjectNode d) { return d.path("labelled").path("book").path("audience").asText("").toLowerCase(Locale.ROOT).strip(); }
    static String demand(ObjectNode d) { return d.path("labelled").path("book").path("demand").asText("").toLowerCase(Locale.ROOT).strip(); }
    static String title(ObjectNode d) { return d.path("record").path("title").asText(""); }
    public static String authors(ObjectNode d) { return d.path("record").path("authors").asText(""); }

    /** What a "like <title>" on the shelf comes to: the book, its document, the list — or the sentence that says why not. */
    public record OnShelf(Shelf.Book book, ObjectNode x, Result result, String message) { public boolean ok() { return result != null; } }

    /** "Like <title>" over the shelf: the book found by its title (and author), labelled now when a model answers and it is not yet, then the list. */
    public static OnShelf onShelf(String title, String author, int limit, boolean labelNow) {
        if (Shelf.path() == null) return new OnShelf(null, null, null, "No Calibre library is set: the setting calibre names its folder or its metadata.db.");
        Shelf.Book b = Shelf.onShelf(title, author);
        if (b == null) return new OnShelf(null, null, null, "No book on the shelf has the title \"" + title + "\".");
        ObjectNode x;
        try {
            Labels.Model m = labelNow ? ShelfDna.driveModel() : null;
            x = m == null ? ShelfDna.doc(b) : ShelfDna.label(b, m, null);
            if (x == null && m != null) x = ShelfDna.doc(b);
            if (x == null || !Dna.labelledCurrent(x)) return new OnShelf(b, x, null, "\"" + b.title() + "\" is not labelled yet" + (ShelfDna.epub(b) == null ? ": the shelf has no EPUB file for it, so there is no text to read." : m == null ? " and no model answers now; the night housekeeping labels it, or researchzosho myshelf dna \"" + b.title() + "\" when the model is up." : "."));
        } catch (Exception e) { return new OnShelf(b, null, null, "\"" + b.title() + "\" could not be read for its DNA: " + e.getMessage()); }
        return new OnShelf(b, x, like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), limit), "");
    }

    /** Whether a wanted author (normalised) is among a work's authors: the whole name, or the surname when it is three letters or more. */
    public static boolean sameAuthor(String wantNorm, String authors) {
        String want = Shelf.norm(wantNorm);
        if (want.isEmpty()) return false;
        String[] parts = want.split(" "); String last = parts[parts.length - 1];
        for (String a : authors.split(" & ")) {
            String n = Shelf.norm(a);
            if (n.isEmpty()) continue;
            if (n.equals(want)) return true;
            String[] np = n.split(" ");
            if (last.length() >= 3 && np[np.length - 1].equals(last) && (parts.length == 1 || np.length == 1 || np[0].charAt(0) == parts[0].charAt(0))) return true;
        }
        return false;
    }

    /** Whether two works share a maker or a series: the old mechanism, set aside. */
    public static boolean sameMaker(ObjectNode x, ObjectNode y) {
        for (String a : authors(x).split(" & ")) { String n = Shelf.norm(a); if (!n.isEmpty() && !n.equals("unknown") && !n.equals("anonymous") && !n.equals("various") && sameAuthor(n, authors(y))) return true; }
        String s = Shelf.norm(x.path("record").path("series").asText(""));
        return !s.isEmpty() && s.equals(Shelf.norm(y.path("record").path("series").asText("")));
    }

    /** How well known a work is, from its record: edition and rating counts the catalogue fill brought; 0 without them. */
    public static int fame(ObjectNode d) { JsonNode r = d.path("record"); return Math.max(r.path("edition_count").asInt(0), r.path("ratings_count").asInt(0) / 5); }
    static final int WELL_KNOWN = 20;

    public record Match(ObjectNode doc, double distance, List<String> alike, List<String> unlike, List<String> notes) {
        public String title() { return LikeX.title(doc); }
    }

    public record Result(ObjectNode x, List<Match> rows, List<ObjectNode> side, int compared, int leftOut, String note) { }

    /** The list: X against every labelled work in the pool, under the preferences and the reactions. */
    public static Result like(ObjectNode x, List<ObjectNode> pool, Prefs prefs, int limit) {
        Map<String, Reactions.Reaction> reactions = Reactions.latest(x.path("kind").asText(Dna.BOOK));
        Set<String> xs = subjectTokens(x), xm = moods(x);
        String xk = kind(x), xa = audience(x), xd = demand(x);
        double wS = prefs.weight("subject"), wK = prefs.weight("kind"), wA = prefs.weight("audience"), wD = prefs.weight("demand"), wM = prefs.weight("mood"), wSc = prefs.weight("scale");
        List<Match> all = new ArrayList<>(); List<ObjectNode> side = new ArrayList<>(); int leftOut = 0, compared = 0;
        for (ObjectNode c : pool) {
            if (c.path("key").asText().equals(x.path("key").asText())) continue;
            compared++;
            if (sameMaker(x, c)) { side.add(c); continue; }
            if (prefs.excludes(c) != null) { leftOut++; continue; }
            Reactions.Reaction re = reactions.get(c.path("key").asText());
            if (re != null && re.reaction().equals("dislike")) { leftOut++; continue; }
            List<String> alike = new ArrayList<>(), unlike = new ArrayList<>(), notes = new ArrayList<>();
            Set<String> cs = subjectTokens(c);
            double dSubject = jaccardDistance(xs, cs);
            List<String> shared = new ArrayList<>(); for (String t : xs) if (cs.contains(t)) shared.add(t);
            if (!shared.isEmpty()) alike.add("subject (" + String.join(", ", shared.subList(0, Math.min(5, shared.size()))) + ")"); else unlike.add("subject");
            String ck = kind(c); if (!xk.isEmpty() && xk.equals(ck)) alike.add("kind (both " + plural(ck) + ")"); else if (!ck.isEmpty() && !xk.isEmpty()) unlike.add("kind (" + ck + ", not " + xk + ")");
            String ca = audience(c); if (!xa.isEmpty() && xa.equals(ca)) alike.add("audience (" + ca + ")"); else if (!ca.isEmpty() && !xa.isEmpty()) unlike.add("audience (" + ca + ")");
            String cd = demand(c); if (!xd.isEmpty() && xd.equals(cd)) alike.add("demand (" + cd + ")"); else if (!cd.isEmpty() && !xd.isEmpty()) unlike.add("demand (" + cd + ", not " + xd + ")");
            Set<String> cm = moods(c); double dMood = jaccardDistance(xm, cm);
            List<String> sharedMoods = new ArrayList<>(); for (String m : xm) if (cm.contains(m)) sharedMoods.add(m);
            if (!sharedMoods.isEmpty()) alike.add("mood (" + String.join(", ", sharedMoods) + ")"); else if (!cm.isEmpty() && !xm.isEmpty()) unlike.add("mood (" + String.join(", ", cm) + ")");
            Double dScale = scaleDistance(x, c);
            if (dScale != null) { if (dScale < 0.15) alike.add("pace and scale"); else if (dScale > 0.4) unlike.add("pace and scale"); }
            double d = wS * dSubject + wK * (xk.isEmpty() || xk.equals(ck) ? 0 : 1) + wA * (xa.isEmpty() || xa.equals(ca) ? 0 : 1) + wD * (xd.isEmpty() || xd.equals(cd) ? 0 : 1) + wM * dMood + wSc * (dScale == null ? 0.5 : dScale);
            List<String> prefers = prefs.prefers(c), avoids = prefs.avoids(c);
            d -= Math.min(0.3, 0.1 * prefers.size()); d += Math.min(0.3, 0.15 * avoids.size());
            notes.addAll(prefers); notes.addAll(avoids);
            if (re != null) notes.add("you marked this " + (re.reaction().equals("own") ? "owned" : re.reaction().equals("like") ? "liked" : "seen"));
            if (fame(c) >= WELL_KNOWN) notes.add("well known: " + (c.path("record").path("edition_count").asInt(0) > 0 ? c.path("record").path("edition_count").asInt() + " editions" : c.path("record").path("ratings_count").asInt() + " ratings") + " in the catalogue");
            all.add(new Match(c, d, alike, unlike, notes));
        }
        all.sort(Comparator.comparingDouble(Match::distance).thenComparing(Match::title));
        List<Match> rows = pick(all, limit, prefs.roam());
        // the popularity line: one well-known title in every list when there is one near, so the person can say whether they want more or fewer of those
        if (prefs.popularity().equals("one") && rows.stream().noneMatch(m -> fame(m.doc()) >= WELL_KNOWN)) {
            for (int i = 0; i < Math.min(all.size(), limit * 4); i++) if (fame(all.get(i).doc()) >= WELL_KNOWN && !rows.contains(all.get(i))) { if (rows.size() >= limit) rows.remove(rows.size() - 1); rows.add(all.get(i)); break; }
        } else if (prefs.popularity().equals("known")) rows.sort(Comparator.comparingInt((Match m) -> fame(m.doc()) >= WELL_KNOWN ? 0 : 1).thenComparingDouble(Match::distance));
        side.sort(Comparator.comparing(LikeX::title));
        String note = compared < 30 ? "Only " + compared + " labelled " + (compared == 1 ? "work is" : "works are") + " in the store so far besides this one; the night housekeeping labels more (researchzosho myshelf dna --minutes 60 reads the shelf now)." : "";
        return new Result(x, rows, side, compared, leftOut, note);
    }

    /** The nearest, with the roam knob letting some of the list come from farther down: at 0 the nearest only, at 1 half the list spread over the next few hundred. */
    static List<Match> pick(List<Match> sorted, int limit, double roam) {
        if (sorted.size() <= limit || roam <= 0) return new ArrayList<>(sorted.subList(0, Math.min(limit, sorted.size())));
        int near = Math.max(1, (int) Math.round(limit * (1 - roam / 2)));
        List<Match> out = new ArrayList<>(sorted.subList(0, near));
        int far = limit - near, span = Math.min(sorted.size() - near, (int) Math.round(limit * 4 * roam) + far);
        for (int i = 0; i < far && span > 0; i++) { int idx = near + (int) Math.floor((i + 0.5) * span / (double) far); if (idx < sorted.size() && !out.contains(sorted.get(idx))) out.add(sorted.get(idx)); }
        return out;
    }

    static String plural(String kind) { return switch (kind) { case "stories", "poetry", "history", "science", "business", "self-help", "reference", "other" -> kind; default -> kind + "s"; }; }

    /** A row in one line: title — authors (year): alike on …; unlike on …; notes. */
    public static String line(Match m) {
        JsonNode r = m.doc().path("record");
        StringBuilder b = new StringBuilder(r.path("title").asText(""));
        if (!r.path("authors").asText("").isEmpty()) b.append(" — ").append(r.path("authors").asText());
        if (!r.path("year").asText("").isEmpty()) b.append(" (").append(r.path("year").asText()).append(')');
        b.append(": ");
        if (!m.alike().isEmpty()) b.append("alike on ").append(String.join(", ", m.alike()));
        if (!m.unlike().isEmpty()) b.append(m.alike().isEmpty() ? "" : "; ").append("unlike on ").append(String.join(", ", m.unlike()));
        if (m.alike().isEmpty() && m.unlike().isEmpty()) b.append("little to compare on");
        for (String n : m.notes()) b.append("; ").append(n);
        return b.toString();
    }

    /** The list as the person and the model read it. */
    public static String text(Result r) { return text(r, "books", "your shelf", "the author's own works set aside"); }

    /** The list for any kind: what the rows are called, where the labelled ones are, and how the maker rule is said. */
    public static String text(Result r, String what, String where, String makerRule) {
        StringBuilder b = new StringBuilder();
        String x = title(r.x()) + (authors(r.x()).isEmpty() ? "" : " — " + authors(r.x()));
        if (r.rows().isEmpty()) b.append("Nothing in the store compares with \"").append(x).append("\" yet").append(r.compared() == 0 ? ": nothing else is labelled" : "").append(". ");
        else {
            b.append(Character.toUpperCase(what.charAt(0))).append(what.substring(1)).append(" like \"").append(x).append("\" among the ").append(r.compared()).append(" labelled on ").append(where).append(", nearest first by subject, then kind, audience, demand, mood and pace (").append(makerRule).append("; what you excluded or disliked left out").append(r.leftOut() > 0 ? ", " + r.leftOut() + " here" : "").append("):\n");
            for (Match m : r.rows()) b.append("- ").append(line(m)).append('\n');
        }
        if (!r.side().isEmpty()) { b.append("Set aside, from the same maker or series (the old way of finding more): "); boolean f = true; for (ObjectNode s : r.side()) { if (!f) b.append("; "); b.append(title(s)); f = false; } b.append(".\n"); }
        if (!r.note().isEmpty()) b.append(r.note()).append('\n');
        return b.toString().stripTrailing();
    }
}
