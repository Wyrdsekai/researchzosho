package org.researchzosho.librarian;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A claim's machine reading beyond its triple: the kind of a name, the dates of a membership, how somebody entered a family. Kept as one
 * note of kind {@code detail} on the claim, {@code key=value; key=value}, outside the content hash as the triple is: the substance a
 * person approves is in the claim's words, and the note is how the program reads them. A changed reading is a new claim, never an edit of
 * this note. Values are escaped so that a {@code ;}, a {@code =} or a {@code %} inside one reads back as written. A key the program does not
 * know is kept, so a later version can add one.
 */
public final class FamilyDetail {

    private FamilyDetail() { }

    /** The kind of note that carries the reading. */
    public static final String NOTE = "detail";

    /** The reading of a claim: its newest detail note, key by key in the order written; empty when it has none. */
    public static Map<String, String> of(Finding f) {
        if (f == null) return Map.of();
        Finding.Note newest = null;
        for (Finding.Note n : f.notes()) if (NOTE.equals(n.kind())) newest = n;
        return newest == null ? Map.of() : parse(newest.text());
    }

    /** One value of a claim's reading, "" when it has none. */
    public static String get(Finding f, String key) { return of(f).getOrDefault(key, ""); }

    /** {@code k=v; k=v} read back, each value unescaped. A part without {@code =} is left out. */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = unescape(part.substring(0, eq).strip()), v = unescape(part.substring(eq + 1).strip());
            if (!k.isEmpty()) out.put(k, v);
        }
        return out;
    }

    /** The reading written as the note's text: every key in its order, each value escaped. */
    public static String text(Map<String, String> detail) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : detail.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            if (b.length() > 0) b.append("; ");
            b.append(escape(e.getKey().strip())).append('=').append(escape(e.getValue() == null ? "" : e.getValue()));
        }
        return b.toString();
    }

    /** The note that carries a reading, written by {@code by} today. */
    public static Finding.Note note(Map<String, String> detail, String by) {
        return new Finding.Note(NOTE, by == null || by.isBlank() ? "family-account" : by, LocalDate.now().toString(), text(detail));
    }

    /** The claim with this reading added as its newest detail note. */
    public static Finding with(Finding f, Map<String, String> detail, String by) { return f.withNote(note(detail, by)); }

    /**
     * A value as the note keeps it: {@code %}, {@code ;} and {@code =} as {@code %25}, {@code %3B}, {@code %3D}, and a line break or a tab as a
     * space, because a note is one line.
     */
    static String escape(String v) {
        return v.replace("%", "%25").replace(";", "%3B").replace("=", "%3D").replaceAll("[\\t\\r\\n]+", " ").strip();
    }

    static String unescape(String v) {
        return v.replace("%3D", "=").replace("%3d", "=").replace("%3B", ";").replace("%3b", ";").replace("%25", "%");
    }
}
