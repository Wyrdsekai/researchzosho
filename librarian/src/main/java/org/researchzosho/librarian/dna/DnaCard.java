package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A DNA document said in plain sentences, for the person at the command line and for the model in a run. */
public final class DnaCard {
    private DnaCard() { }

    /** One line: the book and which layers it has. */
    public static String summary(ObjectNode d) {
        JsonNode r = d.path("record");
        String who = r.path("title").asText("") + (r.path("authors").asText("").isEmpty() ? "" : " — " + r.path("authors").asText()) + (r.path("year").asText("").isEmpty() ? "" : " (" + r.path("year").asText() + ")");
        List<String> has = new ArrayList<>();
        if (Dna.measuredCurrent(d)) has.add("its measured layer");
        if (Dna.labelledCurrent(d)) has.add("its labelled layer (" + d.path("labelled").path("windows").asInt() + " windows" + (d.path("labelled").path("model").asText("").isEmpty() ? "" : ", " + d.path("labelled").path("model").asText()) + ")");
        return who + ": " + (has.isEmpty() ? "the record only." : String.join(" and ", has) + ".");
    }

    /** The card. */
    public static String text(ObjectNode d) {
        StringBuilder b = new StringBuilder(summary(d)).append('\n');
        JsonNode r = d.path("record");
        if (!r.path("tags").isEmpty()) { b.append("The shelf's subjects: "); boolean f = true; for (JsonNode t : r.path("tags")) { b.append(f ? "" : ", ").append(t.asText()); f = false; } b.append(".\n"); }
        if (!r.path("subjects").isEmpty()) { b.append("The catalogue's subjects: "); boolean f = true; for (JsonNode t : r.path("subjects")) { if (!f && b.length() > 2000) break; b.append(f ? "" : ", ").append(t.asText()); f = false; } b.append(".\n"); }
        JsonNode m = d.path("measured").path("features");
        if (m.isObject() && !m.has("note")) {
            b.append("The text, counted: ").append(String.format(Locale.ROOT, "%,d", m.path("words").asInt())).append(" words");
            if (m.path("chapters").asInt() > 0) b.append(" in ").append(m.path("chapters").asInt()).append(" chapters");
            if (m.has("sentence_words_mean")) b.append("; sentences of ").append(m.path("sentence_words_mean").asText()).append(" words on average");
            if (m.has("sentence_chars_mean")) b.append("; sentences of ").append(m.path("sentence_chars_mean").asText()).append(" characters on average");
            if (m.has("dialogue_share")) b.append("; ").append(pct(m.path("dialogue_share").asDouble())).append(" of the words inside quotation marks");
            if (m.hasNonNull("flesch_kincaid_grade")) b.append("; reading grade ").append(m.path("flesch_kincaid_grade").asText());
            if (m.hasNonNull("first_person_share")) b.append("; first person ").append(pct(m.path("first_person_share").asDouble())).append(" of the personal pronouns");
            if (m.hasNonNull("past_tense_share")) b.append("; past tense ").append(pct(m.path("past_tense_share").asDouble())).append(" of the tensed verbs");
            if (m.hasNonNull("kanji_density")) b.append("; kanji ").append(pct(m.path("kanji_density").asDouble())).append(" of the characters");
            if (m.hasNonNull("arc_sd")) b.append("; the feeling of the words swings ").append(m.path("arc_sd").asDouble() >= 0.06 ? "a lot" : m.path("arc_sd").asDouble() >= 0.035 ? "a fair amount" : "little").append(" over the book and ends ").append(m.path("arc_end_minus_mean").asDouble() > 0.01 ? "above" : m.path("arc_end_minus_mean").asDouble() < -0.01 ? "below" : "near").append(" its average");
            b.append(".\n");
        } else if (m.has("note")) b.append("The text: ").append(m.path("note").asText()).append(".\n");
        JsonNode l = d.path("labelled");
        if (Dna.labelledCurrent(d)) {
            JsonNode book = l.path("book");
            if (book.isObject()) {
                b.append("The book, from its excerpt: ").append(nz(book, "kind", "kind unknown"));
                if (book.hasNonNull("audience")) b.append(", for ").append(book.path("audience").asText()).append(" readers");
                if (book.hasNonNull("demand")) b.append(", ").append(book.path("demand").asText()).append(" to read");
                if (book.hasNonNull("setting") && !book.path("setting").asText().equalsIgnoreCase("none")) b.append(", set in ").append(book.path("setting").asText());
                b.append(". ");
                if (book.path("themes").isArray() && !book.path("themes").isEmpty()) { b.append("Themes: "); boolean f = true; for (JsonNode t : book.path("themes")) { b.append(f ? "" : ", ").append(t.asText()); f = false; } b.append(". "); }
                if (book.hasNonNull("what_it_feels_like")) b.append(book.path("what_it_feels_like").asText());
                b.append('\n');
            }
            int k = l.path("windows").asInt();
            String ordinary = l.path("fiction").asBoolean(true) ? "an ordinary novel" : "an ordinary book of its kind";
            b.append("Over ").append(k).append(" windows of the text, against ").append(ordinary).append(": ");
            List<String> moods = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = l.path("mood").fields(); it.hasNext(); ) { Map.Entry<String, JsonNode> e = it.next(); moods.add(e.getKey() + " in " + Math.round(e.getValue().asDouble() * k) + " of " + k); }
            if (!moods.isEmpty()) b.append("more ").append(l.path("fiction").asBoolean(true) ? "" : "of the tone ").append(String.join(", ", moods));
            if (!l.path("least").asText("").isEmpty()) b.append("; least ").append(l.path("least").asText());
            b.append(". ");
            scale(b, l, "pace", "pace", k); scale(b, l, "darkness", "darkness", k); scale(b, l, "style", "style", k); scale(b, l, "humour", "humour", k); scale(b, l, "scale", "scale", k);
            if (l.path("drive").isArray() && !l.path("drive").isEmpty()) b.append("Carried by ").append(l.path("drive").get(0).get(0).asText()).append(" in ").append(l.path("drive").get(0).get(1).asInt()).append(" of ").append(k).append(". ");
            if (l.path("topics").isArray() && !l.path("topics").isEmpty()) { b.append("Topics: "); int n = 0; for (JsonNode t : l.path("topics")) { if (n++ == 12) break; b.append(n == 1 ? "" : ", ").append(t.asText()); } b.append(". "); }
            JsonNode dyn = l.path("signature").path("dyn.mood");
            if (dyn.isObject()) b.append("The leading mood changes at ").append(pct(dyn.path("switches").asDouble())).append(" of the steps from one window to the next").append(dyn.path("follows").isEmpty() ? "" : ", most often " + dyn.path("follows").get(0).asText().replace(">", " then ")).append(". ");
            if (l.path("windows_unparsed").asInt() > 0) b.append(l.path("windows_unparsed").asInt()).append(" window reply(ies) could not be read.");
            b.append('\n');
        } else if (l.has("note")) b.append("The labelled layer: ").append(l.path("note").asText()).append(".\n");
        return b.toString().stripTrailing();
    }

    static final Map<String, String[]> STEPS = Map.of("pace", new String[]{"slower", "about the same", "faster"}, "darkness", new String[]{"lighter", "about the same", "darker"}, "style", new String[]{"plainer", "about the same", "more ornate"},
            "humour", new String[]{"no humour", "a little humour", "frequent humour"}, "scale", new String[]{"one person's inner life", "a few people", "a community or an institution", "a nation, a world or an age"});

    static void scale(StringBuilder b, JsonNode l, String key, String name, int k) {
        JsonNode s = l.path("scales").path(key);
        if (!s.isObject()) return;
        String[] steps = STEPS.get(key);
        int offset = key.equals("humour") || key.equals("scale") ? 0 : 1;
        // the commonest step, and how many windows agree with it
        int[] counts = new int[steps.length]; for (JsonNode v : s.path("arc")) { int i = v.asInt() + offset; if (i >= 0 && i < counts.length) counts[i]++; }
        int best = 0; for (int i = 1; i < counts.length; i++) if (counts[i] > counts[best]) best = i;
        b.append(Character.toUpperCase(name.charAt(0))).append(name.substring(1)).append(": ").append(steps[best]).append(" in ").append(counts[best]).append(" of ").append(k);
        double trend = l.path("signature").path("scale." + key).path("t").asDouble(0);
        if (Math.abs(trend) >= 0.5) b.append(", ").append(trend > 0 ? "rising" : "falling").append(" towards the end");
        b.append(". ");
    }

    static String nz(JsonNode n, String key, String fallback) { String v = n.path(key).asText(""); return v.isEmpty() ? fallback : v; }
    static String pct(double share) { return Math.round(share * 100) + "%"; }
}
