package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.Shelf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * "Like X" (0.6.0; cut again in 0.6.1 on the first judged lists): the works nearest X by what the judge found alike — subject first (the
 * labels' themes, the windows' topics, the record's subjects and tags, kept as PHRASES: "artificial intelligence" is one subject and not
 * two words, and the back matter's words — index, bibliography, notes — are no subject at all), then kind, audience and demand, then the
 * setting and the era (the catalogue's places and times, the labelled setting), then the moods and the scales at a small weight; the form
 * left out (closer in form read as <em>less</em> alike), the dynamics kept for browsing. A row of another kind never stands above a row
 * of the same kind, and another kind is admitted only when it shares a subject, never on mood alone: a list ends short instead. At most
 * two rows per candidate author; the rest of that author's works go to a side list, as X's own maker's do, because "more by the same
 * author" is the old mechanism and not a similarity. Popularity shown as a cue on the row and never the order; the reason per dimension
 * on every row. The weights are the preferences file's, the settled defaults until the reactions hold enough judged pairs to learn them.
 * The setting and the era weigh nothing by default (judged 2026-10-13): at ½ each they put the one tail book's list from 1.30 to 0.80 on the
 * judge (its row judged 3 pushed out by rows judged 0 and 1 that shared "present day" and one weak subject word), since two three-step
 * dimensions at ½ add up to more than the subject distance spans; at 0 the eight lists score as 0.6.0's (1.43 against 1.38, noise).
 * They are named on every row, and a weight in the preferences orders by them. The header says the order the weights give.
 * Measured against the judge: the subject-bearing spaces beat the form by a clear margin on fresh books and are indistinguishable from one
 * another, and the first judged lists of the port named the cuts above (docs/REC_BOOKS.md).
 */
public final class LikeX {
    private LikeX() { }

    static final Set<String> STOP = Set.of("the", "and", "of", "a", "in", "to", "for", "on", "with", "an", "or", "its", "their", "his", "her", "life", "story", "stories", "fiction", "novel", "book", "books", "general", "etc");

    /** Labels that name a part of a book or a shelf's housekeeping, not a subject: the back matter's words, the catalogue's form words. */
    static final Set<String> NOT_SUBJECTS = Set.of("index", "indexes", "bibliography", "bibliographies", "notes", "endnotes", "footnotes", "references", "glossary", "acknowledgements", "acknowledgments",
            "appendix", "appendices", "contents", "table of contents", "sources", "further reading", "copyright", "about the author", "epilogue", "prologue", "preface", "foreword", "afterword", "introduction",
            "conclusion", "fiction", "nonfiction", "non fiction", "general", "literature", "novel", "novels", "book", "books", "ebook", "ebooks", "large type books", "accessible book", "protected daisy", "in library",
            "lending library", "overdrive", "open library staff picks", "bestseller", "new york times bestseller");

    /** The subject words of a phrase: lower case, letters and digits, three letters or more, the empty words out. */
    public static Set<String> tokens(String phrase) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : Shelf.norm(phrase).split(" ")) if (w.length() > 2 && !STOP.contains(w)) out.add(w);
        return out;
    }

    /** One subject phrase as the overlap keeps it: normalised, the leading article off; empty when it is no subject (a part of a book, a form word, empty words only). */
    static String phrase(String raw) {
        String p = Shelf.norm(raw);
        if (p.length() < 3 || NOT_SUBJECTS.contains(p) || p.startsWith("reading level")) return "";
        for (String w : p.split(" ")) if (w.length() > 2 && !STOP.contains(w)) return p;
        return "";
    }

    /** The phrases of one label: a catalogue heading's parts ("Artificial intelligence -- Moral and ethical aspects"), a list written with commas or slashes. */
    static List<String> phrases(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(" -- |--|[,;/]")) { String p = phrase(part); if (!p.isEmpty()) out.add(p); }
        return out;
    }

    /** What a work is about, as subject phrases: the labelled themes and topics, the record's subjects and tags. */
    public static Set<String> subjects(ObjectNode doc) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode l = doc.path("labelled"), r = doc.path("record");
        for (JsonNode t : l.path("book").path("themes")) out.addAll(phrases(t.asText()));
        for (JsonNode t : l.path("topics")) out.addAll(phrases(t.asText()));
        for (JsonNode t : r.path("subjects")) out.addAll(phrases(t.asText()));
        for (JsonNode t : r.path("tags")) out.addAll(phrases(t.asText()));
        return out;
    }

    /** What a work is about, as words: the preferences match on these, so a person's "ledgers" finds "harbour ledgers". */
    public static Set<String> subjectTokens(ObjectNode doc) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : subjects(doc)) out.addAll(tokens(p));
        return out;
    }

    /** The moods a work carries: those named among the two most in a quarter of its windows or more. */
    public static Set<String> moods(ObjectNode doc) {
        Set<String> out = new TreeSet<>();
        doc.path("labelled").path("mood").fields().forEachRemaining(e -> { if (e.getValue().asDouble() >= 0.25) out.add(e.getKey()); });
        return out;
    }

    static final Pattern DECADE = Pattern.compile("^\\d{3}0s$"), YEAR = Pattern.compile("^\\d{4}$"), ORDINAL = Pattern.compile("^\\d{1,2}(st|nd|rd|th)$");
    static final Set<String> PRESENT = Set.of("present", "today", "contemporary", "modern", "now", "current", "nowadays");
    static final Set<String> NO_SETTING = Set.of("none", "n a", "unknown", "unspecified", "various", "not applicable", "no setting", "nowhere");
    static final Set<String> TIME_WORDS = Set.of("century", "day", "era", "period", "age", "years", "year", "decade", "late", "early", "mid");

    /** Where a work is set: the catalogue's places and the labelled setting's place words. */
    public static Set<String> places(ObjectNode doc) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode p : doc.path("record").path("places")) for (String part : p.asText().split("[(),;/]")) { String n = Shelf.norm(part); if (n.length() >= 3 && !STOP.contains(n)) out.add(n); }
        String setting = Shelf.norm(doc.path("labelled").path("book").path("setting").asText(""));
        if (setting.isEmpty() || NO_SETTING.contains(setting)) return out;
        for (String w : setting.split(" ")) {
            if (w.length() < 3 || STOP.contains(w) || PRESENT.contains(w) || TIME_WORDS.contains(w) || DECADE.matcher(w).matches() || YEAR.matcher(w).matches() || ORDINAL.matcher(w).matches()) continue;
            out.add(w);
        }
        return out;
    }

    /** When a work is set: the catalogue's times and the labelled setting's era words — a decade for a year, its century with it, "20th century", "present day". */
    public static Set<String> eras(ObjectNode doc) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode t : doc.path("record").path("times")) for (String part : t.asText().split("[,;/]")) { String n = Shelf.norm(part); if (n.length() >= 3) era(n, out); }
        String setting = Shelf.norm(doc.path("labelled").path("book").path("setting").asText(""));
        if (setting.isEmpty() || NO_SETTING.contains(setting)) return out;
        String[] ws = setting.split(" ");
        for (int i = 0; i < ws.length; i++) {
            String w = ws[i];
            if (ORDINAL.matcher(w).matches() && i + 1 < ws.length && ws[i + 1].equals("century")) out.add(w + " century");
            else if (PRESENT.contains(w)) out.add("present day");
            else if (YEAR.matcher(w).matches() || DECADE.matcher(w).matches()) era(w, out);   // a place word is no era
        }
        return out;
    }

    /** A year gives its decade and its century; a decade its century too; anything else stands as it is. */
    static void era(String n, Set<String> out) {
        if (YEAR.matcher(n).matches()) { out.add(n.substring(0, 3) + "0s"); out.add(century(Integer.parseInt(n))); }
        else if (DECADE.matcher(n).matches()) { out.add(n); out.add(century(Integer.parseInt(n.substring(0, 4)))); }
        else if (!n.isEmpty()) out.add(n);
    }

    static String century(int year) {
        int c = year / 100 + 1;
        String suffix = c % 10 == 1 && c % 100 != 11 ? "st" : c % 10 == 2 && c % 100 != 12 ? "nd" : c % 10 == 3 && c % 100 != 13 ? "rd" : "th";
        return c + suffix + " century";
    }

    static double jaccardDistance(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 1.0;
        int both = 0; for (String x : a) if (b.contains(x)) both++;
        return 1.0 - both / (double) (a.size() + b.size() - both);
    }

    /** The members of {@code a} that are in {@code b}, in a's order. */
    static List<String> shared(Set<String> a, Set<String> b) { List<String> out = new ArrayList<>(); for (String x : a) if (b.contains(x)) out.add(x); return out; }

    static String join(List<String> xs, int n) { return String.join(", ", xs.subList(0, Math.min(n, xs.size()))); }

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
            if (x == null || !Dna.labelledCurrent(x)) return new OnShelf(b, x, null, "\"" + b.title() + "\" is not labelled yet" + (ShelfDna.epub(b) == null ? ": the shelf has no EPUB file for it, so there is no text to read." : m == null ? " and no model answers now; the night housekeeping labels it, or researchzosho myshelf dna \"" + b.title() + "\" when a model is up." : "."));
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

    static final Set<String> NOBODY = Set.of("", "unknown", "anonymous", "various", "various authors");

    /** Whether two works share a maker or a series: the old mechanism, set aside. */
    public static boolean sameMaker(ObjectNode x, ObjectNode y) {
        for (String a : authors(x).split(" & ")) { String n = Shelf.norm(a); if (!NOBODY.contains(n) && sameAuthor(n, authors(y))) return true; }
        String s = Shelf.norm(x.path("record").path("series").asText(""));
        return !s.isEmpty() && s.equals(Shelf.norm(y.path("record").path("series").asText("")));
    }

    /** The first author of a work, normalised, for the cap per author; empty when the work names nobody. */
    static String authorKey(ObjectNode d) { String n = Shelf.norm(authors(d).split(" & ")[0]); return NOBODY.contains(n) ? "" : n; }
    static String firstAuthor(ObjectNode d) { return authors(d).split(" & ")[0].strip(); }

    /** How well known a work is, from its record: edition and rating counts the catalogue fill brought; 0 without them. */
    public static int fame(ObjectNode d) { JsonNode r = d.path("record"); return Math.max(r.path("edition_count").asInt(0), r.path("ratings_count").asInt(0) / 5); }
    static final int WELL_KNOWN = 20;

    /** How many rows one candidate author may take in a list. */
    public static final int PER_AUTHOR = 2;

    public record Match(ObjectNode doc, double distance, List<String> alike, List<String> unlike, List<String> notes) {
        public String title() { return LikeX.title(doc); }
        boolean onSubject() { for (String a : alike) if (a.startsWith("subject")) return true; return false; }
    }

    /** The list: the rows; X's maker's works set aside; the listed authors' further works set aside (author → works); what was compared and left out; a note. */
    public record Result(ObjectNode x, List<Match> rows, List<ObjectNode> side, Map<String, List<ObjectNode>> more, int compared, int leftOut, String note, String order) { }

    /** The order the list is in, said from the weights: the dimensions that weigh anything, the heaviest first, equal weights together. */
    static String orderSaid(Prefs prefs) {
        Map<Double, List<String>> groups = new TreeMap<>(Comparator.reverseOrder());
        for (String dim : List.of("subject", "kind", "audience", "demand", "setting", "era", "mood", "scale")) {
            double w = prefs.weight(dim);
            if (w > 0) groups.computeIfAbsent(w, k -> new ArrayList<>()).add(dim.equals("scale") ? "pace and scale" : dim);
        }
        List<String> parts = new ArrayList<>();
        for (List<String> g : groups.values()) parts.add(g.size() == 1 ? g.get(0) : String.join(", ", g.subList(0, g.size() - 1)) + " and " + g.get(g.size() - 1));
        return parts.isEmpty() ? "nothing (every weight is 0)" : String.join(", then ", parts);
    }

    /** The list: X against every labelled work in the pool, under the preferences and the reactions. */
    public static Result like(ObjectNode x, List<ObjectNode> pool, Prefs prefs, int limit) {
        Map<String, Reactions.Reaction> reactions = Reactions.latest(x.path("kind").asText(Dna.BOOK));
        Set<String> xs = subjects(x), xm = moods(x), xp = places(x), xe = eras(x);
        String xk = kind(x), xa = audience(x), xd = demand(x);
        double wS = prefs.weight("subject"), wK = prefs.weight("kind"), wA = prefs.weight("audience"), wD = prefs.weight("demand"), wM = prefs.weight("mood"), wSc = prefs.weight("scale"), wP = prefs.weight("setting"), wE = prefs.weight("era");
        List<Match> all = new ArrayList<>(); List<ObjectNode> side = new ArrayList<>(); int leftOut = 0, compared = 0;
        for (ObjectNode c : pool) {
            if (c.path("key").asText().equals(x.path("key").asText())) continue;
            compared++;
            if (sameMaker(x, c)) { side.add(c); continue; }
            if (prefs.excludes(c) != null) { leftOut++; continue; }
            Reactions.Reaction re = reactions.get(c.path("key").asText());
            if (re != null && re.reaction().equals("dislike")) { leftOut++; continue; }
            List<String> alike = new ArrayList<>(), unlike = new ArrayList<>(), notes = new ArrayList<>();
            Set<String> cs = subjects(c);
            double dSubject = jaccardDistance(xs, cs);
            List<String> sharedSubjects = shared(xs, cs);
            if (!sharedSubjects.isEmpty()) alike.add("subject (" + join(sharedSubjects, 5) + ")"); else unlike.add("subject");
            String ck = kind(c); if (!xk.isEmpty() && xk.equals(ck)) alike.add("kind (both " + plural(ck) + ")"); else if (!ck.isEmpty() && !xk.isEmpty()) unlike.add("kind (" + ck + ", not " + xk + ")");
            String ca = audience(c); if (!xa.isEmpty() && xa.equals(ca)) alike.add("audience (" + ca + ")"); else if (!ca.isEmpty() && !xa.isEmpty()) unlike.add("audience (" + ca + ")");
            String cd = demand(c); if (!xd.isEmpty() && xd.equals(cd)) alike.add("demand (" + cd + ")"); else if (!cd.isEmpty() && !xd.isEmpty()) unlike.add("demand (" + cd + ", not " + xd + ")");
            // the setting and the era: alike when they share a place or a time, unlike when both are known and share none, unknown (half) otherwise
            Set<String> cp = places(c); List<String> sharedPlaces = shared(xp, cp);
            double dPlace = xp.isEmpty() || cp.isEmpty() ? 0.5 : sharedPlaces.isEmpty() ? 1 : 0;
            if (!sharedPlaces.isEmpty()) alike.add("setting (" + join(sharedPlaces, 3) + ")"); else if (dPlace == 1) unlike.add("setting (" + join(new ArrayList<>(cp), 2) + ")");
            Set<String> ce = eras(c); List<String> sharedEras = shared(xe, ce);
            double dEra = xe.isEmpty() || ce.isEmpty() ? 0.5 : sharedEras.isEmpty() ? 1 : 0;
            if (!sharedEras.isEmpty()) alike.add("era (" + join(sharedEras, 2) + ")"); else if (dEra == 1) unlike.add("era (" + join(new ArrayList<>(ce), 2) + ")");
            Set<String> cm = moods(c); double dMood = jaccardDistance(xm, cm);
            List<String> sharedMoods = shared(xm, cm);
            if (!sharedMoods.isEmpty()) alike.add("mood (" + String.join(", ", sharedMoods) + ")"); else if (!cm.isEmpty() && !xm.isEmpty()) unlike.add("mood (" + String.join(", ", cm) + ")");
            Double dScale = scaleDistance(x, c);
            if (dScale != null) { if (dScale < 0.15) alike.add("pace and scale"); else if (dScale > 0.4) unlike.add("pace and scale"); }
            double d = wS * dSubject + wK * (xk.isEmpty() || xk.equals(ck) ? 0 : 1) + wA * (xa.isEmpty() || xa.equals(ca) ? 0 : 1) + wD * (xd.isEmpty() || xd.equals(cd) ? 0 : 1)
                    + wP * dPlace + wE * dEra + wM * dMood + wSc * (dScale == null ? 0.5 : dScale);
            List<String> prefers = prefs.prefers(c), avoids = prefs.avoids(c);
            d -= Math.min(0.3, 0.1 * prefers.size()); d += Math.min(0.3, 0.15 * avoids.size());
            notes.addAll(prefers); notes.addAll(avoids);
            if (re != null) notes.add("you marked this " + (re.reaction().equals("own") ? "owned" : re.reaction().equals("like") ? "liked" : "seen"));
            if (fame(c) >= WELL_KNOWN) notes.add("well known: " + (c.path("record").path("edition_count").asInt(0) > 0 ? c.path("record").path("edition_count").asInt() + " editions" : c.path("record").path("ratings_count").asInt() + " ratings") + " in the catalogue");
            all.add(new Match(c, d, alike, unlike, notes));
        }
        all.sort(Comparator.comparingDouble(Match::distance).thenComparing(Match::title));
        // the same kind first, another kind only on a shared subject (never on mood alone); at most two rows an author, the rest set aside
        boolean kinds = !xk.isEmpty();
        List<Match> same = new ArrayList<>(), other = new ArrayList<>();
        Map<String, Integer> perAuthor = new HashMap<>(); Map<String, List<ObjectNode>> setAside = new LinkedHashMap<>(); Map<String, String> shown = new HashMap<>();
        for (Match m : all) {
            boolean sameKind = !kinds || xk.equals(kind(m.doc()));
            if (!sameKind && !m.onSubject()) continue;
            String who = authorKey(m.doc());
            if (!who.isEmpty() && perAuthor.merge(who, 1, Integer::sum) > PER_AUTHOR) { setAside.computeIfAbsent(who, k -> new ArrayList<>()).add(m.doc()); shown.putIfAbsent(who, firstAuthor(m.doc())); continue; }
            (sameKind ? same : other).add(m);
        }
        List<Match> eligible = new ArrayList<>(same); eligible.addAll(other);
        List<Match> rows = pick(same, limit, prefs.roam());
        for (Match m : other) { if (rows.size() >= limit) break; rows.add(m); }
        // the popularity line: one well-known title in every list when there is one near, so the person can say whether they want more or fewer of those
        if (prefs.popularity().equals("one") && rows.stream().noneMatch(m -> fame(m.doc()) >= WELL_KNOWN)) {
            for (int i = 0; i < Math.min(eligible.size(), limit * 4); i++) if (fame(eligible.get(i).doc()) >= WELL_KNOWN && !rows.contains(eligible.get(i))) { if (rows.size() >= limit) rows.remove(rows.size() - 1); rows.add(eligible.get(i)); break; }
        }
        Comparator<Match> order = Comparator.comparingInt((Match m) -> kinds && !xk.equals(kind(m.doc())) ? 1 : 0);
        if (prefs.popularity().equals("known")) order = order.thenComparingInt(m -> fame(m.doc()) >= WELL_KNOWN ? 0 : 1);
        rows.sort(order.thenComparingInt(eligible::indexOf));
        Map<String, List<ObjectNode>> more = new LinkedHashMap<>();
        for (Match m : rows) { String who = authorKey(m.doc()); if (setAside.containsKey(who) && !more.containsKey(shown.get(who))) more.put(shown.get(who), setAside.get(who)); }
        side.sort(Comparator.comparing(LikeX::title));
        String note = compared < 30 ? "Only " + compared + " labelled " + (compared == 1 ? "work is" : "works are") + " in the store so far besides this one; the night housekeeping labels more (researchzosho myshelf dna --minutes 60 reads the shelf now)." : "";
        if (!rows.isEmpty() && rows.stream().noneMatch(Match::onSubject)) note = (note.isEmpty() ? "" : note + " ") + "None of these shares a subject with it: the store holds nothing nearer yet. The night housekeeping labels more, and a pack from the shared pool widens the store.";
        return new Result(x, rows, side, more, compared, leftOut, note, orderSaid(prefs));
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
            b.append(Character.toUpperCase(what.charAt(0))).append(what.substring(1)).append(" like \"").append(x).append("\" among the ").append(r.compared()).append(" labelled on ").append(where)
                    .append(", nearest first by ").append(r.order()).append("; the same kind first, and at most two rows a maker (").append(makerRule)
                    .append("; what you excluded or disliked left out").append(r.leftOut() > 0 ? ", " + r.leftOut() + " here" : "").append("):\n");
            for (Match m : r.rows()) b.append("- ").append(line(m)).append('\n');
        }
        if (!r.side().isEmpty()) { b.append("Set aside, from the same maker or series (the old way of finding more): "); boolean f = true; for (ObjectNode s : r.side()) { if (!f) b.append("; "); b.append(title(s)); f = false; } b.append(".\n"); }
        if (!r.more().isEmpty()) {
            b.append("More by the makers above, set aside so the list spreads: "); boolean f = true;
            for (Map.Entry<String, List<ObjectNode>> e : r.more().entrySet()) { if (!f) b.append("; "); b.append(e.getKey()).append(": "); boolean g = true; for (ObjectNode s : e.getValue()) { if (!g) b.append(", "); b.append(title(s)); g = false; } f = false; }
            b.append(".\n");
        }
        if (!r.note().isEmpty()) b.append(r.note()).append('\n');
        return b.toString().stripTrailing();
    }
}
