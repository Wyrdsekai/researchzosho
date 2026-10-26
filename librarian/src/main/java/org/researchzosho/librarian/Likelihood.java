package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words a report's analysis uses for how likely something is, with the numbers they stand for (0.6.6): the table of the US
 * Intelligence Community's analytic standard (ICD 203, 2015), so that every conjecture and forecast carries one word from one scale and the
 * number beside it, and a reader never has to guess what "probably" meant. Confidence — how good the sources are — is a separate thing,
 * stated apart.
 */
public enum Likelihood {
    ALMOST_NO_CHANCE("almost no chance", "remote", 1, 5),
    VERY_UNLIKELY("very unlikely", "highly improbable", 5, 20),
    UNLIKELY("unlikely", "improbable", 20, 45),
    ROUGHLY_EVEN("roughly even chance", "roughly even odds", 45, 55),
    LIKELY("likely", "probable", 55, 80),
    VERY_LIKELY("very likely", "highly probable", 80, 95),
    ALMOST_CERTAIN("almost certain", "nearly certain", 95, 99);

    public final String term, alt;
    public final int low, high;
    Likelihood(String term, String alt, int low, int high) { this.term = term; this.alt = alt; this.low = low; this.high = high; }

    /** The band a percentage falls in; a shared boundary (5, 20, 45, 55, 80, 95) goes to the lower band. */
    public static Likelihood ofPercent(int percent) {
        int p = Math.max(1, Math.min(99, percent));
        for (Likelihood l : values()) if (p <= l.high) return l;
        return ALMOST_CERTAIN;
    }

    /** The band a text names, the longest term first so that "very unlikely" is not read as "likely"; null when it names none. */
    public static Likelihood ofWords(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.toLowerCase(Locale.ROOT);
        List<Likelihood> order = new ArrayList<>(List.of(values()));
        order.sort((a, b) -> Integer.compare(Math.max(b.term.length(), b.alt.length()), Math.max(a.term.length(), a.alt.length())));
        int best = Integer.MAX_VALUE; Likelihood found = null;
        for (Likelihood l : order) for (String w : new String[]{l.term, l.alt, l == ROUGHLY_EVEN ? "even chance" : l.term}) {
            int at = t.indexOf(w);
            if (at < 0) continue;
            boolean wordStart = at == 0 || !Character.isLetter(t.charAt(at - 1));
            boolean wordEnd = at + w.length() >= t.length() || !Character.isLetter(t.charAt(at + w.length()));
            if (wordStart && wordEnd && at < best) { best = at; found = l; }
        }
        return found;
    }

    /** The table as the writer is told it. */
    public static String table() {
        StringBuilder b = new StringBuilder();
        for (Likelihood l : values()) b.append(b.length() == 0 ? "" : ", ").append(l.term).append(" (").append(l.low).append("–").append(l.high).append("%)");
        return b.toString();
    }
}
