package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.librarian.AtomicWrite;
import org.researchzosho.librarian.Shelf;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The preferences file (0.6.0): what the person wants more of and less of, as lines each saying where it came from — drafted from the
 * shelf, learned from the reactions, or said by the person. The weights per dimension of "like X" and browsing; what to leave out
 * (authors, subjects, kinds); what to prefer (moods, subjects, kinds, a pace); the roam knob (how far from the nearest a list wanders);
 * the popularity line (one well-known title in every list, or none, or known ones preferred); and a line in the person's own words. One
 * file per kind under {@code ~/.researchzosho/prefs}, exportable and importable as it is. Taste lives here and adapts; the works'
 * documents never hold it (docs/REC_DNA.md).
 */
public final class Prefs {

    public static final int VERSION = 1;
    /** The dimensions "like X" weighs, with the settled default weights: subject first, the fixed dimensions behind it, mood and scale small, form out. */
    public static final Map<String, Double> DEFAULT_WEIGHTS = new LinkedHashMap<>();
    static { DEFAULT_WEIGHTS.put("subject", 2.0); DEFAULT_WEIGHTS.put("kind", 0.5); DEFAULT_WEIGHTS.put("audience", 0.5); DEFAULT_WEIGHTS.put("demand", 0.5); DEFAULT_WEIGHTS.put("mood", 0.5); DEFAULT_WEIGHTS.put("scale", 0.25); DEFAULT_WEIGHTS.put("form", 0.0); }
    public static final List<String> POPULARITY = List.of("one", "none", "known");
    public static final List<String> PREFER_DIMS = List.of("mood", "subject", "kind", "pace", "author"), EXCLUDE_DIMS = List.of("author", "subject", "kind", "series");

    /** One line: what it is (weight, exclude, prefer, avoid, roam, popularity, words), the dimension, the value, where it came from and when. */
    public record Line(String what, String dimension, String value, String from, String at) {
        public ObjectNode json() { ObjectNode o = Dna.M.createObjectNode(); o.put("what", what); o.put("dimension", dimension); o.put("value", value); o.put("from", from); o.put("at", at); return o; }
        static Line of(JsonNode n) { return new Line(n.path("what").asText(""), n.path("dimension").asText(""), n.path("value").asText(""), n.path("from").asText(""), n.path("at").asText("")); }
        double number(double dflt) { try { return Double.parseDouble(value); } catch (Exception e) { return dflt; } }
    }

    final String kind;
    final List<Line> lines = new ArrayList<>();

    Prefs(String kind) { this.kind = kind; }

    /** A test's own folder. */
    static volatile Path override;
    public static void useDir(Path dir) { override = dir; }
    public static Path dir() { return override != null ? override : Config.home().resolve("prefs"); }
    static Path file(String kind) { return dir().resolve(kind + "s.json"); }

    /** The file as it stands; the defaults, unsaved, when there is none. */
    public static Prefs load(String kind) {
        Prefs p = new Prefs(kind);
        Path f = file(kind);
        if (Files.isRegularFile(f)) {
            try { p.read(Dna.M.readTree(Files.readString(f, StandardCharsets.UTF_8)), ""); return p; } catch (Exception e) { /* a broken file: the defaults, and the next save rewrites it */ }
        }
        p.addDefaults();
        return p;
    }

    void read(JsonNode n, String imported) {
        for (JsonNode l : n.path("lines")) { Line x = Line.of(l); if (!x.what().isEmpty()) lines.add(imported.isEmpty() ? x : new Line(x.what(), x.dimension(), x.value(), x.from() + " (imported from " + imported + ")", x.at())); }
        if (lines.stream().noneMatch(l -> l.what().equals("weight"))) addDefaults();
    }

    void addDefaults() {
        String today = LocalDate.now().toString();
        for (Map.Entry<String, Double> e : DEFAULT_WEIGHTS.entrySet()) if (lines.stream().noneMatch(l -> l.what().equals("weight") && l.dimension().equals(e.getKey()))) lines.add(new Line("weight", e.getKey(), Double.toString(e.getValue()), "the default, as the measurements settled it", today));
        if (lines.stream().noneMatch(l -> l.what().equals("roam"))) lines.add(new Line("roam", "", "0.2", "the default", today));
        if (lines.stream().noneMatch(l -> l.what().equals("popularity"))) lines.add(new Line("popularity", "", "one", "the default: one well-known title in every list, so you can say whether you want more or fewer of those", today));
    }

    public boolean exists() { return Files.isRegularFile(file(kind)); }

    public void save() throws IOException {
        Files.createDirectories(dir());
        AtomicWrite.text(file(kind), Dna.M.writerWithDefaultPrettyPrinter().writeValueAsString(json()));
    }

    public ObjectNode json() {
        ObjectNode o = Dna.M.createObjectNode();
        o.put("version", VERSION); o.put("kind", kind);
        ArrayNode a = o.putArray("lines"); for (Line l : lines) a.add(l.json());
        return o;
    }

    public List<Line> lines() { return List.copyOf(lines); }

    // ---- what "like X" and browsing read ----

    public double weight(String dimension) {
        for (Line l : lines) if (l.what().equals("weight") && l.dimension().equals(dimension)) return l.number(DEFAULT_WEIGHTS.getOrDefault(dimension, 0.0));
        return DEFAULT_WEIGHTS.getOrDefault(dimension, 0.0);
    }

    public double roam() { for (Line l : lines) if (l.what().equals("roam")) return Math.max(0, Math.min(1, l.number(0.2))); return 0.2; }

    public String popularity() { for (Line l : lines) if (l.what().equals("popularity")) return POPULARITY.contains(l.value()) ? l.value() : "one"; return "one"; }

    public String words() { for (Line l : lines) if (l.what().equals("words")) return l.value(); return ""; }

    public List<Line> of(String what) { List<Line> out = new ArrayList<>(); for (Line l : lines) if (l.what().equals(what)) out.add(l); return out; }

    /** Why a work is left out of a list, or null: an excluded author, subject word, kind or series. */
    public String excludes(ObjectNode doc) {
        JsonNode r = doc.path("record"), l = doc.path("labelled");
        for (Line x : of("exclude")) {
            String v = x.value().toLowerCase(Locale.ROOT).strip();
            if (v.isEmpty()) continue;
            switch (x.dimension()) {
                case "author" -> { if (LikeX.sameAuthor(v, r.path("authors").asText(""))) return "its author, " + x.value() + ", is on your exclusions"; }
                case "series" -> { if (Shelf.norm(r.path("series").asText("")).equals(Shelf.norm(x.value()))) return "its series, " + x.value() + ", is on your exclusions"; }
                case "kind" -> { if (l.path("kind").asText("").equalsIgnoreCase(v)) return "its kind, " + x.value() + ", is on your exclusions"; }
                case "subject" -> { if (LikeX.subjectTokens(doc).containsAll(LikeX.tokens(v)) && !LikeX.tokens(v).isEmpty()) return "its subject " + x.value() + " is on your exclusions"; }
                default -> { }
            }
        }
        return null;
    }

    /** The prefer lines a work answers to, as reasons; and the avoid lines, as reasons; each moves the distance a little. */
    public List<String> prefers(ObjectNode doc) { return matches(doc, "prefer", "you prefer "); }
    public List<String> avoids(ObjectNode doc) { return matches(doc, "avoid", "you tend to avoid "); }

    List<String> matches(ObjectNode doc, String what, String say) {
        List<String> out = new ArrayList<>();
        JsonNode r = doc.path("record"), l = doc.path("labelled");
        Set<String> moods = LikeX.moods(doc), subject = LikeX.subjectTokens(doc);
        for (Line x : of(what)) {
            String v = x.value().toLowerCase(Locale.ROOT).strip();
            if (v.isEmpty()) continue;
            boolean hit = switch (x.dimension()) {
                case "mood" -> moods.contains(v);
                case "subject" -> !LikeX.tokens(v).isEmpty() && subject.containsAll(LikeX.tokens(v));
                case "kind" -> l.path("kind").asText("").equalsIgnoreCase(v);
                case "author" -> LikeX.sameAuthor(v, r.path("authors").asText(""));
                case "pace" -> { double m = l.path("scales").path("pace").path("mean").asDouble(0); yield v.equals("faster") ? m >= 0.34 : v.equals("slower") ? m <= -0.34 : Math.abs(m) < 0.34; }
                default -> false;
            };
            if (hit) out.add(say + x.dimension() + " " + x.value());
        }
        return out;
    }

    // ---- what the person and the shelf write ----

    /** Sets one line from the person: {@code weight.<dimension> <number>}, {@code roam <0..1>}, {@code popularity one|none|known}, {@code words <text>}, {@code exclude.<author|subject|kind|series> <value>}, {@code prefer.<mood|subject|kind|pace|author> <value>}, {@code avoid.<…> <value>}. */
    public String set(String key, String value, String who) throws IOException {
        String k = key == null ? "" : key.strip().toLowerCase(Locale.ROOT), v = value == null ? "" : value.strip();
        String today = LocalDate.now().toString(), from = who + ", " + today;
        if (k.startsWith("weight.")) {
            String dim = k.substring(7);
            if (!DEFAULT_WEIGHTS.containsKey(dim)) throw new IOException("the dimensions with a weight are " + String.join(", ", DEFAULT_WEIGHTS.keySet()) + ".");
            double n; try { n = Double.parseDouble(v); } catch (Exception e) { throw new IOException("a weight is a number, such as 2 or 0.5."); }
            if (n < 0) throw new IOException("a weight is zero or more.");
            replace("weight", dim, new Line("weight", dim, Double.toString(n), from, today)); save(); return "The weight of " + dim + " is now " + n + ".";
        }
        switch (k) {
            case "roam" -> { double n; try { n = Double.parseDouble(v); } catch (Exception e) { throw new IOException("roam is a number from 0 (the nearest only) to 1 (wander far)."); } if (n < 0 || n > 1) throw new IOException("roam is between 0 and 1."); replace("roam", "", new Line("roam", "", Double.toString(n), from, today)); save(); return "Lists now roam " + n + " (0 keeps to the nearest, 1 wanders far)."; }
            case "popularity" -> { if (!POPULARITY.contains(v.toLowerCase(Locale.ROOT))) throw new IOException("popularity is one (one well-known title in every list), none (no such rule) or known (well-known titles preferred)."); replace("popularity", "", new Line("popularity", "", v.toLowerCase(Locale.ROOT), from, today)); save(); return "Popularity: " + popularitySaid(v.toLowerCase(Locale.ROOT)); }
            case "words" -> { replace("words", "", new Line("words", "", v, from, today)); save(); return v.isEmpty() ? "Your own words are cleared." : "In your words: " + v; }
            default -> { }
        }
        for (String what : List.of("exclude", "prefer", "avoid")) {
            if (k.startsWith(what + ".")) {
                String dim = k.substring(what.length() + 1);
                List<String> dims = what.equals("exclude") ? EXCLUDE_DIMS : PREFER_DIMS;
                if (!dims.contains(dim)) throw new IOException(what + " takes " + String.join(", ", dims) + ".");
                if (v.isEmpty()) throw new IOException(what + "." + dim + " needs a value.");
                if (lines.stream().anyMatch(l -> l.what().equals(what) && l.dimension().equals(dim) && l.value().equalsIgnoreCase(v))) return "That line is there already.";
                lines.add(new Line(what, dim, v, from, today)); save();
                return (what.equals("exclude") ? "Left out from now on: " : what.equals("prefer") ? "Preferred from now on: " : "Avoided from now on: ") + dim + " " + v + ".";
            }
        }
        throw new IOException("the settings are weight.<dimension>, roam, popularity, words, exclude.<author|subject|kind|series>, prefer.<mood|subject|kind|pace|author> and avoid.<the same>.");
    }

    /** Drops a line: {@code unset exclude.author <value>}, {@code unset prefer.mood <value>}, or {@code unset words}; a weight, roam or popularity goes back to its default. */
    public String unset(String key, String value) throws IOException {
        String k = key == null ? "" : key.strip().toLowerCase(Locale.ROOT), v = value == null ? "" : value.strip();
        int before = lines.size();
        if (k.startsWith("weight.") || k.equals("roam") || k.equals("popularity")) { String dim = k.startsWith("weight.") ? k.substring(7) : ""; lines.removeIf(l -> l.what().equals(k.startsWith("weight.") ? "weight" : k) && l.dimension().equals(dim)); addDefaults(); save(); return "Back to the default."; }
        if (k.equals("words")) { lines.removeIf(l -> l.what().equals("words")); save(); return "Your own words are cleared."; }
        for (String what : List.of("exclude", "prefer", "avoid")) if (k.startsWith(what + ".")) { String dim = k.substring(what.length() + 1); lines.removeIf(l -> l.what().equals(what) && l.dimension().equals(dim) && (v.isEmpty() || l.value().equalsIgnoreCase(v))); }
        if (lines.size() == before) throw new IOException("no such line.");
        save(); return (before - lines.size()) + " line(s) dropped.";
    }

    void replace(String what, String dimension, Line line) { lines.removeIf(l -> l.what().equals(what) && l.dimension().equals(dimension)); lines.add(line); }

    /** 0.25 as "0.25", 2.0 as "2". */
    static String plain(double v) { return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString(); }

    static String popularitySaid(String v) { return v.equals("none") ? "no rule about well-known titles." : v.equals("known") ? "well-known titles are preferred." : "one well-known title in every list, so you can say whether you want more or fewer of those."; }

    /**
     * A draft from the shelf: the subjects the person went for most, and from the labelled books among them the moods that recur, the
     * kind that dominates and a pace when it leans one way. Each line says how many books it rests on. The person's own lines are kept.
     */
    public static Prefs draft(String kind) throws IOException {
        Prefs p = load(kind);
        p.lines.removeIf(l -> l.from().startsWith("drafted from your shelf"));
        String today = LocalDate.now().toString();
        if (Shelf.path() == null) { p.addDefaults(); return p; }
        for (Map.Entry<String, Integer> e : Shelf.likedSubjects(8)) if (e.getValue() >= 3) p.lines.add(new Line("prefer", "subject", e.getKey(), "drafted from your shelf: " + e.getValue() + " books you went for carry it", today));
        Map<Long, Shelf.Book> liked = new HashMap<>(); for (Shelf.Book b : Shelf.books()) if (b.liked()) liked.put(b.id(), b);
        Map<String, Integer> moods = new HashMap<>(), kinds = new HashMap<>(); List<Double> paces = new ArrayList<>(); int labelled = 0;
        for (ObjectNode d : Dna.labelled(kind)) {
            if (!liked.containsKey(d.path("record").path("calibre_id").asLong(-1))) continue;
            labelled++;
            JsonNode l = d.path("labelled");
            l.path("mood").fields().forEachRemaining(m -> { if (m.getValue().asDouble() >= 0.4) moods.merge(m.getKey(), 1, Integer::sum); });
            if (!l.path("kind").asText("").isEmpty()) kinds.merge(l.path("kind").asText(), 1, Integer::sum);
            if (l.path("scales").path("pace").has("mean")) paces.add(l.path("scales").path("pace").path("mean").asDouble());
        }
        if (labelled >= 3) {
            final int n = labelled;
            moods.entrySet().stream().filter(e -> e.getValue() >= 3 && e.getValue() * 10 >= n * 3).sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
                    .forEach(e -> p.lines.add(new Line("prefer", "mood", e.getKey(), "drafted from your shelf: " + e.getValue() + " of the " + n + " labelled books you went for carry it", today)));
            kinds.entrySet().stream().filter(e -> e.getValue() * 2 >= n).findFirst().ifPresent(e -> p.lines.add(new Line("prefer", "kind", e.getKey(), "drafted from your shelf: " + e.getValue() + " of the " + n + " labelled books you went for", today)));
            if (!paces.isEmpty()) { double m = Py.fmean(paces); if (m >= 0.34) p.lines.add(new Line("prefer", "pace", "faster", "drafted from your shelf: the books you went for run faster than an ordinary book of their kind (" + Py.round(m, 2) + " on a scale of −1 to 1)", today)); else if (m <= -0.34) p.lines.add(new Line("prefer", "pace", "slower", "drafted from your shelf: the books you went for run slower than an ordinary book of their kind (" + Py.round(m, 2) + " on a scale of −1 to 1)", today)); }
        }
        p.addDefaults();
        p.save();
        return p;
    }

    /** Lines learned from the reactions: a mood, subject or kind that recurs in what was liked is preferred; a kind disliked three times and never liked is avoided. The earlier learned lines are replaced. */
    public void learn() throws IOException {
        lines.removeIf(l -> l.from().startsWith("learned from your reactions"));
        String today = LocalDate.now().toString();
        Map<String, Integer> moods = new HashMap<>(), subjects = new HashMap<>(), kindsLiked = new HashMap<>(), kindsDisliked = new HashMap<>();
        int likes = 0;
        for (Reactions.Reaction r : Reactions.all(kind)) {
            JsonNode s = r.dna();
            if (r.reaction().equals("like")) {
                likes++;
                s.path("mood").fields().forEachRemaining(m -> { if (m.getValue().asDouble() >= 0.4) moods.merge(m.getKey(), 1, Integer::sum); });
                for (JsonNode t : s.path("themes")) subjects.merge(t.asText().toLowerCase(Locale.ROOT), 1, Integer::sum);
                if (!s.path("kind").asText("").isEmpty()) kindsLiked.merge(s.path("kind").asText(), 1, Integer::sum);
            } else if (r.reaction().equals("dislike") && !s.path("kind").asText("").isEmpty()) kindsDisliked.merge(s.path("kind").asText(), 1, Integer::sum);
        }
        if (likes >= 3) {
            moods.entrySet().stream().filter(e -> e.getValue() >= 3).sorted((a, b) -> b.getValue() - a.getValue()).limit(4).forEach(e -> add("prefer", "mood", e.getKey(), "learned from your reactions: " + e.getValue() + " books you liked carry it", today));
            subjects.entrySet().stream().filter(e -> e.getValue() >= 3).sorted((a, b) -> b.getValue() - a.getValue()).limit(4).forEach(e -> add("prefer", "subject", e.getKey(), "learned from your reactions: " + e.getValue() + " books you liked are about it", today));
        }
        for (Map.Entry<String, Integer> e : kindsDisliked.entrySet()) if (e.getValue() >= 3 && !kindsLiked.containsKey(e.getKey())) add("avoid", "kind", e.getKey(), "learned from your reactions: " + e.getValue() + " disliked, none liked", today);
        save();
    }

    void add(String what, String dim, String value, String from, String at) { if (lines.stream().noneMatch(l -> l.what().equals(what) && l.dimension().equals(dim) && l.value().equalsIgnoreCase(value))) lines.add(new Line(what, dim, value, from, at)); }

    /** The file as a page of sentences, each with where it came from. */
    public String text() {
        StringBuilder b = new StringBuilder();
        b.append(exists() ? "Your preferences for " + kind + "s (" + file(kind) + "):\n" : "No preferences file yet; these are the defaults (researchzosho prefs draft writes one from your shelf):\n");
        if (!words().isEmpty()) b.append("In your words: ").append(words()).append("\n");
        b.append("Weights in \"like X\" and browsing: "); boolean f = true;
        for (String dim : DEFAULT_WEIGHTS.keySet()) { b.append(f ? "" : ", ").append(dim).append(' ').append(plain(weight(dim))); f = false; }
        b.append(" (form stays at 0 for \"like X\": it is what \"written like X\" uses).\n");
        for (Line l : of("prefer")) b.append("- prefer ").append(l.dimension()).append(' ').append(l.value()).append(" — from ").append(l.from()).append('\n');
        for (Line l : of("avoid")) b.append("- avoid ").append(l.dimension()).append(' ').append(l.value()).append(" — from ").append(l.from()).append('\n');
        for (Line l : of("exclude")) b.append("- leave out ").append(l.dimension()).append(' ').append(l.value()).append(" — from ").append(l.from()).append('\n');
        b.append("Roam ").append(roam()).append(" (0 keeps to the nearest, 1 wanders far). Popularity: ").append(popularitySaid(popularity())).append('\n');
        b.append("Change a line with researchzosho prefs set <setting> <value>: weight.subject 2, roam 0.4, popularity none, words \"…\", exclude.author \"…\", prefer.mood gentle, avoid.kind business; prefs unset drops one; prefs export <file> and prefs import <file> carry the file.");
        return b.toString();
    }

    /** The person's lines in a few sentences, for a run's model: their words and what they prefer, avoid and leave out; empty when there is nothing beyond the defaults. */
    public String brief() {
        StringBuilder b = new StringBuilder();
        if (!words().isEmpty()) b.append("In their words: ").append(words()).append(". ");
        List<String> prefer = new ArrayList<>(), avoid = new ArrayList<>(), out = new ArrayList<>();
        for (Line l : of("prefer")) prefer.add(l.dimension() + " " + l.value()); for (Line l : of("avoid")) avoid.add(l.dimension() + " " + l.value()); for (Line l : of("exclude")) out.add(l.dimension() + " " + l.value());
        if (!prefer.isEmpty()) b.append("They prefer: ").append(String.join(", ", prefer)).append(". ");
        if (!avoid.isEmpty()) b.append("They tend to avoid: ").append(String.join(", ", avoid)).append(". ");
        if (!out.isEmpty()) b.append("Left out at their word: ").append(String.join(", ", out)).append(". ");
        if (!popularity().equals("one")) b.append(popularity().equals("none") ? "No rule about well-known titles. " : "They prefer well-known titles. ");
        return b.toString().strip();
    }

    /** The file as it is, written to a path of the person's. */
    public void export(Path to) throws IOException { Files.createDirectories(to.toAbsolutePath().getParent()); Files.writeString(to, Dna.M.writerWithDefaultPrettyPrinter().writeValueAsString(json()), StandardCharsets.UTF_8); }

    /** A file of the person's read in, its lines added to this one's (a line already here is kept once). */
    public int importFrom(Path from) throws IOException {
        JsonNode n = Dna.M.readTree(Files.readString(from, StandardCharsets.UTF_8));
        if (!n.path("lines").isArray()) throw new IOException("not a preferences file: it has no lines");
        int before = lines.size();
        Prefs other = new Prefs(kind); other.read(n, from.getFileName().toString());
        for (Line l : other.lines) {
            if (l.what().equals("weight") || l.what().equals("roam") || l.what().equals("popularity") || l.what().equals("words")) replace(l.what(), l.dimension(), l);
            else add(l.what(), l.dimension(), l.value(), l.from(), l.at());
        }
        save();
        return lines.size() - before;
    }
}
