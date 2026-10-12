package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Browsing (0.6.0): "I'll know it when I see it". A list spread across the dimensions by construction — kind, the leading mood, pace,
 * scale, audience and subject all differing from row to row — with the preferences file as the prior (a prefer line lifts a work, an
 * avoid line lowers it), one well-known title in every list, and the reactions re-cutting it: what was disliked, seen or owned does
 * not come back. Each row says in a sentence what the work is, so the person can say "more of this, less of that" and the next list
 * moves. The dynamics live here and in the preferences, not in "like X" (docs/REC_BOOKS.md).
 */
public final class Browse {
    private Browse() { }

    public record Pick(ObjectNode doc, String why, double prior) { public String title() { return LikeX.title(doc); } }

    /** The spread: how alike two works are on the browsing dimensions, 0 to 1 — the kind counting double, a dimension counted only when both have it. */
    static double alike(ObjectNode a, ObjectNode b) {
        double same = 0, n = 0;
        if (!LikeX.kind(a).isEmpty() && !LikeX.kind(b).isEmpty()) { n += 2; if (LikeX.kind(a).equals(LikeX.kind(b))) same += 2; }
        if (!LikeX.audience(a).isEmpty() && !LikeX.audience(b).isEmpty()) { n++; if (LikeX.audience(a).equals(LikeX.audience(b))) same++; }
        if (!leadMood(a).isEmpty() && !leadMood(b).isEmpty()) { n++; if (leadMood(a).equals(leadMood(b))) same++; }
        for (String sc : new String[]{"pace", "scale"}) { int x = step(a, sc), y = step(b, sc); if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE) { n++; if (x == y) same++; } }
        Set<String> sa = LikeX.subjectTokens(a), sb = LikeX.subjectTokens(b);
        if (!sa.isEmpty() && !sb.isEmpty()) { n++; same += 1 - LikeX.jaccardDistance(sa, sb); }
        return n == 0 ? 0 : same / n;
    }

    static String leadMood(ObjectNode d) {
        String best = ""; double bestShare = -1;
        for (Map.Entry<String, JsonNode> e : (Iterable<Map.Entry<String, JsonNode>>) () -> d.path("labelled").path("mood").fields()) if (e.getValue().asDouble() > bestShare) { bestShare = e.getValue().asDouble(); best = e.getKey(); }
        return best;
    }

    static int step(ObjectNode d, String scale) { JsonNode s = d.path("labelled").path("scales").path(scale); return s.has("mean") ? (int) Math.round(s.path("mean").asDouble()) : Integer.MIN_VALUE; }

    /** The list: {@code limit} works spread across the dimensions, the preferences as the prior, the reactions out, one well-known title in; {@code seed} varies the draw. */
    public static List<Pick> browse(List<ObjectNode> pool, Prefs prefs, int limit, long seed) {
        Map<String, Reactions.Reaction> reactions = Reactions.latest(pool.isEmpty() ? Dna.BOOK : pool.get(0).path("kind").asText(Dna.BOOK));
        Random rnd = new Random(seed);
        List<Pick> cands = new ArrayList<>();
        for (ObjectNode d : pool) {
            if (reactions.containsKey(d.path("key").asText())) continue;   // disliked, seen, owned or liked already: the person has met it
            if (prefs.excludes(d) != null) continue;
            List<String> prefers = prefs.prefers(d), avoids = prefs.avoids(d);
            double prior = 0.1 * prefers.size() - 0.15 * avoids.size() + rnd.nextDouble() * 0.05;
            cands.add(new Pick(d, why(d, prefers), prior));
        }
        List<Pick> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        while (out.size() < limit && out.size() < cands.size()) {
            Pick best = null; double bestScore = -Double.MAX_VALUE;
            for (Pick c : cands) {
                if (used.contains(c.doc().path("key").asText())) continue;
                double spread = 0; for (Pick p : out) spread = Math.max(spread, alike(c.doc(), p.doc()));
                double score = c.prior() - spread;
                if (score > bestScore) { bestScore = score; best = c; }
            }
            if (best == null) break;
            out.add(best); used.add(best.doc().path("key").asText());
        }
        if (prefs.popularity().equals("one") && out.stream().noneMatch(p -> LikeX.fame(p.doc()) >= LikeX.WELL_KNOWN)) {
            Pick known = null; for (Pick c : cands) if (!used.contains(c.doc().path("key").asText()) && LikeX.fame(c.doc()) >= LikeX.WELL_KNOWN && (known == null || c.prior() > known.prior())) known = c;
            if (known != null) { if (out.size() >= limit) out.remove(out.size() - 1); out.add(known); }
        }
        return out;
    }

    /** What the work is, in one sentence from its DNA. */
    static String why(ObjectNode d, List<String> prefers) {
        JsonNode l = d.path("labelled"), book = l.path("book");
        StringBuilder b = new StringBuilder();
        String kind = l.path("kind").asText(""); if (!kind.isEmpty()) b.append(article(kind)).append(' ').append(kind);
        List<String> moods = new ArrayList<>(LikeX.moods(d)); if (!moods.isEmpty()) b.append(b.length() > 0 ? ", " : "").append(String.join(" and ", moods.subList(0, Math.min(2, moods.size()))));
        int pace = step(d, "pace"); if (pace == 1) b.append(", faster than most"); else if (pace == -1) b.append(", slower than most");
        int scale = step(d, "scale"); if (scale == 0) b.append(", one person's inner life"); else if (scale == 3) b.append(", a nation, a world or an age");
        List<String> themes = new ArrayList<>(); for (JsonNode t : book.path("themes")) { if (themes.size() == 3) break; themes.add(t.asText()); }
        if (themes.isEmpty()) for (JsonNode t : l.path("topics")) { if (themes.size() == 3) break; themes.add(t.asText()); }
        if (!themes.isEmpty()) b.append(", about ").append(String.join(", ", themes));
        if (!book.path("audience").asText("").isEmpty() && !book.path("audience").asText().equals("adult")) b.append(", for ").append(book.path("audience").asText()).append(" readers");
        if (LikeX.fame(d) >= LikeX.WELL_KNOWN) b.append(" (well known)");
        for (String p : prefers) b.append("; ").append(p);
        return b.toString();
    }

    static String article(String kind) { return "aeiou".indexOf(Character.toLowerCase(kind.charAt(0))) >= 0 ? "an" : "a"; }

    /** The list as the person reads it. */
    public static String text(List<Pick> picks, int poolSize, String kindWord) {
        if (picks.isEmpty()) return "Nothing to browse yet: no " + kindWord + " is labelled, or every labelled one has had a reaction or is excluded. The night housekeeping labels more.";
        StringBuilder b = new StringBuilder("A spread across the " + poolSize + " labelled " + kindWord + "s, each as different from the last as the store allows; say more of this or less of that (researchzosho react like|dislike|seen \"<title>\"), and the next list moves:\n");
        for (Pick p : picks) {
            JsonNode r = p.doc().path("record");
            b.append("- ").append(r.path("title").asText("")).append(r.path("authors").asText("").isEmpty() ? "" : " — " + r.path("authors").asText()).append(r.path("year").asText("").isEmpty() ? "" : " (" + r.path("year").asText() + ")").append(": ").append(p.why()).append('\n');
        }
        return b.toString().stripTrailing();
    }
}
