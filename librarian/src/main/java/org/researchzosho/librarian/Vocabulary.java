package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A controlled list with equivalences — the librarian's authority file, in one markdown file:
 *
 * <pre>
 * - keigo — the Japanese honorific system | also: 敬語, honorific language, honorifics | wikidata: Q1350768
 * </pre>
 *
 * One canonical slug per line; {@code also:} lists the other names it goes by in any language;
 * {@code wikidata:} is an optional anchor so two libraries can agree they hold the same thing without
 * agreeing on any hierarchy. {@link #resolve} maps any name to its slug by normalised match, which is
 * how "the same thing under different words" is settled mechanically — for subjects, for the graph's
 * predicates, for its node names — before a model ever proposes a new one.
 */
public final class Vocabulary {

    public record Term(String slug, String description, List<String> also, String wikidata) {
        String toLine() {
            StringBuilder sb = new StringBuilder("- ").append(slug).append(" — ").append(description);
            if (!also.isEmpty()) sb.append(" | also: ").append(String.join(", ", also));
            if (wikidata != null && !wikidata.isBlank()) sb.append(" | wikidata: ").append(wikidata);
            return sb.toString();
        }
    }

    private final Map<String, Term> bySlug = new LinkedHashMap<>();
    private final Map<String, String> byName = new LinkedHashMap<>();   // normalised name → slug
    private final List<String> preamble = new ArrayList<>();

    /** The normalised form names are matched on: NFKC, lowercase, whitespace collapsed, edge punctuation dropped. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip()
                .replaceAll("\\s+", " ").replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "");
        return n.replace('_', '-');
    }

    public static Vocabulary read(Path file) throws IOException {
        Vocabulary v = new Vocabulary();
        if (!Files.exists(file)) return v;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.startsWith("- ")) { if (v.bySlug.isEmpty()) v.preamble.add(line); continue; }
            String body = line.substring(2);
            String[] parts = body.split(" \\| ");
            String[] head = parts[0].split(" — ", 2);
            String slug = head[0].strip();
            String desc = head.length > 1 ? head[1].strip() : "";
            List<String> also = new ArrayList<>();
            String wd = "";
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].strip();
                if (p.startsWith("also:")) {
                    for (String a : p.substring(5).split(",")) if (!a.isBlank()) also.add(a.strip());
                } else if (p.startsWith("wikidata:")) {
                    wd = p.substring(9).strip();   // (a dangling else once bound this to the isBlank check above)
                }
            }
            v.put(new Term(slug, desc, also, wd));
        }
        return v;
    }

    public void write(Path file, String defaultPreamble) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        List<String> pre = preamble.isEmpty() ? List.of(defaultPreamble.split("\n")) : preamble;
        for (String p : pre) sb.append(p).append('\n');
        if (!sb.toString().endsWith("\n\n")) sb.append('\n');
        for (Term t : bySlug.values()) sb.append(t.toLine()).append('\n');
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    public void put(Term t) {
        bySlug.put(t.slug(), t);
        byName.put(norm(t.slug()), t.slug());
        byName.put(norm(t.slug().replace('-', ' ')), t.slug());
        for (String a : t.also()) byName.put(norm(a), t.slug());
    }

    public Term get(String slug) { return bySlug.get(slug); }
    public Map<String, Term> terms() { return java.util.Collections.unmodifiableMap(bySlug); }
    public boolean isEmpty() { return bySlug.isEmpty(); }

    /** The slug a name resolves to (by slug, by variant, normalised), or null when the vocabulary has no such thing. */
    public String resolve(String name) {
        if (name == null) return null;
        String n = norm(name);
        if (n.isEmpty()) return null;
        String s = byName.get(n);
        if (s != null) return s;
        return byName.get(n.replace(' ', '-'));
    }

    /** Add variants to a term (creating it when absent). */
    public void alias(String slug, String description, List<String> variants) {
        Term t = bySlug.get(slug);
        List<String> also = new ArrayList<>(t == null ? List.of() : t.also());
        for (String v : variants) if (!v.isBlank() && also.stream().noneMatch(x -> norm(x).equals(norm(v))) && !norm(v).equals(norm(slug))) also.add(v.strip());
        put(new Term(slug, t == null ? description : (description == null || description.isBlank() ? t.description() : description), also, t == null ? "" : t.wikidata()));
    }

    public void link(String slug, String wikidata) {
        Term t = bySlug.get(slug);
        if (t == null) t = new Term(slug, "", List.of(), "");
        put(new Term(t.slug(), t.description(), t.also(), wikidata));
    }
}
