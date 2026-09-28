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

import java.util.Collections;
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
            if (!also.isEmpty()) sb.append(" | also: ").append(list(also));
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
                    also.addAll(names(p.substring(5)));
                } else if (p.startsWith("wikidata:")) {
                    wd = p.substring(9).strip();   // (a dangling else once bound this to the isBlank check above)
                }
            }
            v.put(new Term(slug, desc, also, wd));
        }
        return v;
    }

    /**
     * The other names of a line's {@code also:} list, in order. Commas separate them, as they always have. A name with a comma in it, such as
     * a book index's "Hart, Tom" or a place "Leeds, Yorkshire", is written between double quotes, with a double quote inside it doubled
     * ({@link #list}), and read back whole. Anything else between two commas is read exactly as before, quotes and all: a line written
     * before names could have a comma in them reads as it always did.
     */
    public static List<String> names(String list) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        int i = 0, n = list.length();
        while (i <= n) {
            int start = i;
            while (i < n && Character.isWhitespace(list.charAt(i))) i++;
            String whole = null;
            int after = -1;
            if (i < n && list.charAt(i) == '"') {
                StringBuilder b = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    char c = list.charAt(j);
                    if (c == '"') {
                        if (j + 1 < n && list.charAt(j + 1) == '"') { b.append('"'); j += 2; continue; }
                        closed = true; j++; break;
                    }
                    b.append(c); j++;
                }
                int k = j;
                while (k < n && Character.isWhitespace(list.charAt(k))) k++;
                // a quoted name is one that needed its quotes: it has a comma in it, and nothing but spaces stands before the next comma
                if (closed && (k == n || list.charAt(k) == ',') && b.indexOf(",") >= 0) { whole = b.toString(); after = k; }
            }
            if (whole != null) {
                if (!whole.isBlank()) out.add(whole.strip());
                i = after + 1;
                continue;
            }
            int comma = list.indexOf(',', start);
            String part = comma < 0 ? list.substring(start) : list.substring(start, comma);
            if (!part.isBlank()) out.add(part.strip());
            if (comma < 0) break;
            i = comma + 1;
        }
        return out;
    }

    /** Other names as a line's {@code also:} list writes them, separated by commas; a name with a comma in it between double quotes ({@link #names}). */
    public static String list(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (String a : names) {
            if (b.length() > 0) b.append(", ");
            b.append(a.contains(",") ? "\"" + a.replace("\"", "\"\"") + "\"" : a);
        }
        return b.toString();
    }

    public void write(Path file, String defaultPreamble) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        List<String> pre = preamble.isEmpty() ? List.of(defaultPreamble.split("\n")) : preamble;
        for (String p : pre) sb.append(p).append('\n');
        if (!sb.toString().endsWith("\n\n")) sb.append('\n');
        for (Term t : bySlug.values()) sb.append(t.toLine()).append('\n');
        AtomicWrite.text(file, sb.toString());
    }

    /** Forget the preamble read from the file, so the next write uses the default one. */
    public void dropPreamble() { preamble.clear(); }

    /** The lines above the first entry, as the next write puts them. */
    public void preamble(List<String> lines) { preamble.clear(); preamble.addAll(lines); }

    /** A copy to add to, with the same terms and preamble; this one stays as it is. */
    public Vocabulary copy() {
        Vocabulary v = new Vocabulary();
        v.preamble.addAll(preamble);
        for (Term t : bySlug.values()) v.put(t);
        return v;
    }

    /** The lines above the first entry, as the file had them. */
    public List<String> preamble() { return List.copyOf(preamble); }

    public void put(Term t) {
        bySlug.put(t.slug(), t);
        byName.put(norm(t.slug()), t.slug());
        byName.put(norm(t.slug().replace('-', ' ')), t.slug());
        for (String a : t.also()) byName.put(norm(a), t.slug());
    }

    public Term get(String slug) { return bySlug.get(slug); }

    /** A term taken out, with every name that led to it. Returns whether it was there. */
    public boolean remove(String slug) {
        if (bySlug.remove(slug) == null) return false;
        byName.values().removeIf(slug::equals);
        return true;
    }
    public Map<String, Term> terms() { return Collections.unmodifiableMap(bySlug); }
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
