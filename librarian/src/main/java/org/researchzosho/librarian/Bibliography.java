package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BibTeX from the shelves: every source a finding or an investigation cites, one entry each, with the
 * record's metadata where {@link Citations} resolved it and the locator alone where it did not. Keys
 * are stable ({@code doi_10_1038_nature05357}, {@code arxiv_2504_00327}, {@code url_<hash>}), so a
 * regenerated file diffs cleanly against the last one.
 */
public final class Bibliography {

    private Bibliography() { }

    /** Entries for {@code sources}, deduplicated by key. */
    public static String bibtex(LibraryStore store, List<Finding.Source> sources, Citations.Source resolver) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (Finding.Source s : sources) {
            String ident = Citations.identify(s.locator());
            Citations.Meta m = ident == null ? null : Citations.resolve(store, s.locator(), resolver);
            String key = key(ident, s.locator());
            if (entries.containsKey(key)) continue;
            entries.put(key, m == null ? plain(key, s) : entry(key, m, s));
        }
        return String.join("\n", entries.values());
    }

    static String key(String ident, String locator) {
        if (ident != null) return ident.replace(':', '_').replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
        return "url_" + Integer.toHexString(locator.hashCode());
    }

    static String entry(String key, Citations.Meta m, Finding.Source s) {
        boolean article = !m.venue().isEmpty();
        StringBuilder sb = new StringBuilder("@").append(article ? "article" : "misc").append("{").append(key).append(",\n");
        field(sb, "title", m.title());
        field(sb, "author", String.join(" and ", m.authors()));
        if (article) field(sb, "journal", m.venue());
        field(sb, "year", m.year());
        field(sb, "volume", m.volume());
        field(sb, "pages", m.pages());
        field(sb, "doi", m.doi());
        if (m.kind().equals("arxiv")) {
            field(sb, "eprint", m.id() + m.version());
            field(sb, "archiveprefix", "arXiv");
        }
        field(sb, "url", s.locator());
        field(sb, "note", s.whyItMatters());
        return sb.append("}\n").toString();
    }

    static String plain(String key, Finding.Source s) {
        StringBuilder sb = new StringBuilder("@misc{").append(key).append(",\n");
        String loc = s.locator();
        if (loc.startsWith("cite:")) field(sb, "howpublished", loc.substring(5));
        else field(sb, "url", loc);
        if (s.edition() != null && !s.edition().isBlank() && !s.edition().equals("n/a")) field(sb, "note", s.edition() + " — " + s.whyItMatters());
        else field(sb, "note", s.whyItMatters());
        return sb.append("}\n").toString();
    }

    private static void field(StringBuilder sb, String name, String value) {
        if (value == null || value.isBlank()) return;
        sb.append("  ").append(name).append(" = {").append(value.replace("{", "\\{").replace("}", "\\}").replace("\n", " ")).append("},\n");
    }

    /** The sources of the entries named (finding or investigation ids), in order, for the bib verb. */
    public static List<Finding.Source> sourcesOf(LibraryStore store, List<String> ids) throws java.io.IOException {
        List<Finding.Source> out = new ArrayList<>();
        for (String id : ids) {
            Finding f = store.finding(id);
            if (f != null) { out.addAll(f.sources()); continue; }
            Investigation inv = store.investigation(id);
            if (inv != null) {
                for (String u : Acquisitions.urls(inv.body())) out.add(new Finding.Source(u, "n/a", "cited by " + inv.id()));
                for (String fid : inv.findings()) { Finding g = store.finding(fid); if (g != null) out.addAll(g.sources()); }
            }
        }
        return out;
    }
}
