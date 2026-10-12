package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The person's reactions (0.6.0): like, dislike, seen or own, on any row the library shows — a book on the shelf, a row of a "like X"
 * list, a browsing pick. Each is kept with the moment, the work's key and a snapshot of its DNA, and, when the row came from a list,
 * which X it was like: those pairs are the judged set that will one day learn the weights of "like X" (not before hundreds of them across
 * dozens of X, as the measurements said). The reactions feed the preferences file ({@link Prefs#learn}) and re-cut a list: a disliked
 * work leaves it, a seen or owned one is marked. They are the person's, kept beside the preferences and never in a work's document.
 */
public final class Reactions {
    private Reactions() { }

    public static final List<String> KINDS = List.of("like", "dislike", "seen", "own");

    public record Reaction(String at, String kind, String key, String title, String authors, String reaction, String aboutKey, String aboutTitle, ObjectNode dna) {
        public ObjectNode json() {
            ObjectNode o = Dna.M.createObjectNode();
            o.put("at", at); o.put("kind", kind); o.put("key", key == null ? "" : key); o.put("title", title); o.put("authors", authors == null ? "" : authors); o.put("reaction", reaction);
            o.put("about_key", aboutKey == null ? "" : aboutKey); o.put("about_title", aboutTitle == null ? "" : aboutTitle);
            o.set("dna", dna == null ? Dna.M.createObjectNode() : dna);
            return o;
        }
        static Reaction of(JsonNode n) {
            return new Reaction(n.path("at").asText(""), n.path("kind").asText(Dna.BOOK), n.path("key").asText(""), n.path("title").asText(""), n.path("authors").asText(""), n.path("reaction").asText(""),
                    n.path("about_key").asText(""), n.path("about_title").asText(""), n.path("dna").isObject() ? (ObjectNode) n.path("dna") : Dna.M.createObjectNode());
        }
    }

    static Path file(String kind) { return Prefs.dir().resolve("reactions-" + kind + "s.jsonl"); }

    /** What a reaction keeps of the work's DNA: enough to learn from, nothing to re-read. */
    public static ObjectNode snapshot(ObjectNode doc) {
        ObjectNode s = Dna.M.createObjectNode();
        if (doc == null) return s;
        JsonNode l = doc.path("labelled");
        if (!Dna.labelledCurrent(doc)) return s;
        s.put("kind", l.path("kind").asText(""));
        s.set("mood", l.path("mood").deepCopy());
        ArrayNode themes = s.putArray("themes"); int n = 0; for (JsonNode t : l.path("book").path("themes")) { if (n++ == 8) break; themes.add(t.asText()); }
        n = 0; ArrayNode topics = s.putArray("topics"); for (JsonNode t : l.path("topics")) { if (n++ == 8) break; topics.add(t.asText()); }
        for (String k : List.of("pace", "darkness", "style", "humour", "scale")) if (l.path("scales").path(k).has("mean")) s.put(k, l.path("scales").path(k).path("mean").asDouble());
        if (l.path("book").hasNonNull("audience")) s.put("audience", l.path("book").path("audience").asText());
        if (l.path("book").hasNonNull("demand")) s.put("demand", l.path("book").path("demand").asText());
        return s;
    }

    /** Writes one reaction down. */
    public static synchronized Reaction add(String kind, String reaction, ObjectNode doc, String title, String authors, ObjectNode about) throws IOException {
        String r = reaction == null ? "" : reaction.strip().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(r)) throw new IOException("a reaction is like, dislike, seen or own");
        Reaction x = new Reaction(Instant.now().toString(), kind, doc == null ? "" : doc.path("key").asText(""), title, authors, r,
                about == null ? "" : about.path("key").asText(""), about == null ? "" : about.path("record").path("title").asText(""), snapshot(doc));
        Files.createDirectories(file(kind).getParent());
        Files.writeString(file(kind), x.json().toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return x;
    }

    /** Every reaction of the kind, oldest first. */
    public static List<Reaction> all(String kind) {
        List<Reaction> out = new ArrayList<>();
        Path f = file(kind);
        if (!Files.isRegularFile(f)) return out;
        try { for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) { if (line.isBlank()) continue; try { out.add(Reaction.of(Dna.M.readTree(line))); } catch (Exception ignored) { } } } catch (IOException ignored) { }
        return out;
    }

    /** The latest reaction to each work, by key; a title's normalised form where the work had no key. */
    public static Map<String, Reaction> latest(String kind) {
        Map<String, Reaction> m = new LinkedHashMap<>();
        for (Reaction r : all(kind)) m.put(r.key().isEmpty() ? "title:" + norm(r.title()) : r.key(), r);
        return m;
    }

    /** The reactions given on rows of "like X" lists: X's key, the row's key, the reaction — the judged pairs. */
    public static List<Reaction> pairs(String kind) {
        List<Reaction> out = new ArrayList<>();
        for (Reaction r : all(kind)) if (!r.aboutKey().isEmpty() && !r.key().isEmpty()) out.add(r);
        return out;
    }

    public static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip(); }

    /** The reactions in a few sentences. */
    public static String text(String kind) {
        List<Reaction> all = all(kind);
        if (all.isEmpty()) return "No reactions yet. researchzosho react like \"<title>\" (or dislike, seen, own) writes one down; a reaction on a row of a \"like\" list is remembered against that X too.";
        int likes = 0, dislikes = 0, seen = 0, own = 0; for (Reaction r : all) { switch (r.reaction()) { case "like" -> likes++; case "dislike" -> dislikes++; case "seen" -> seen++; default -> own++; } }
        StringBuilder b = new StringBuilder(all.size() + " reaction(s): " + likes + " liked, " + dislikes + " disliked, " + seen + " seen, " + own + " owned; " + pairs(kind).size() + " given on rows of \"like\" lists (the judged pairs).\n");
        int n = 0;
        for (int i = all.size() - 1; i >= 0 && n < 15; i--, n++) { Reaction r = all.get(i); b.append("- ").append(r.at().length() > 10 ? r.at().substring(0, 10) : r.at()).append(' ').append(r.reaction()).append(": ").append(r.title()).append(r.authors().isEmpty() ? "" : " — " + r.authors()).append(r.aboutTitle().isEmpty() ? "" : " (on the list like \"" + r.aboutTitle() + "\")").append('\n'); }
        return b.toString().stripTrailing();
    }
}
