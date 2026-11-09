package org.researchzosho.tools;

import org.researchzosho.librarian.Fence;
import org.researchzosho.librarian.SourceRules;
import org.researchzosho.librarian.SourceTier;

import java.util.List;

/**
 * The hits of a search place as the model sees them, in the search-results fence like every other search: the title, the address with
 * its source tier, a further address where there is one (a free PDF), and the lines that say what the hit is. A hit on the person's
 * refused-sources list or on the site list is left out and counted, as in the web search; so is a further address on either list.
 */
public final class PlaceRows {
    private PlaceRows() { }

    /** One hit: its title, its address, the lines under it, and further addresses, each as {what it is, the address}. */
    public record Hit(String title, String url, List<String> lines, List<String[]> more) {
        public Hit {
            title = title == null ? "" : title;
            url = url == null ? "" : url;
            lines = lines == null ? List.of() : List.copyOf(lines);
            more = more == null ? List.of() : List.copyOf(more);
        }
        public Hit(String title, String url, List<String> lines) { this(title, url, lines, List.of()); }
    }

    public static String render(String query, String heading, List<Hit> hits, int limit, Fetch.Policy policy) {
        Fetch.Policy p = policy == null ? Fetch.Policy.DEFAULT : policy;
        StringBuilder sb = new StringBuilder("results for \"" + query + "\"" + heading + ":\n" + Fence.open("SEARCH RESULTS") + "\n");
        int shown = 0, refused = 0, listed = 0;
        SourceRules rules = SourceRules.live();
        for (Hit h : hits) {
            if (shown >= limit) break;
            if (p.refused(h.url())) { refused++; continue; }
            if (p.onSiteList(h.url())) { listed++; continue; }
            shown++;
            sb.append(shown).append(". ").append(h.title().isBlank() ? h.url() : h.title()).append('\n')
              .append("   ").append(h.url()).append("  [").append(SourceTier.of(h.url())).append(rules.trusted(h.url()) ? ", trusted by the person" : "").append("]\n");
            for (String[] m : h.more()) if (m.length == 2 && !m[1].isBlank() && !p.refused(m[1]) && !p.onSiteList(m[1])) sb.append("   ").append(m[0]).append(": ").append(m[1]).append('\n');
            for (String l : h.lines()) if (l != null && !l.isBlank()) sb.append("   ").append(l).append('\n');
        }
        sb.append(WebSearchTool.leftOutLines(refused, listed));
        sb.append(Fence.close("SEARCH RESULTS")).append('\n').append(Fence.rule("SEARCH RESULTS")).append('\n');
        return sb.toString();
    }

    /** Any run of white space, the no-break and thin spaces too, which the plain white-space class of a pattern leaves alone. */
    public static final String SPACES = "[\\s\\u00A0\\u2007\\u2009\\u202F]+";

    /** A text with its spaces run together, cut to {@code max} characters at a word, with an ellipsis when it was cut. */
    public static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll(SPACES, " ").strip();
        if (t.length() <= max) return t;
        int at = t.lastIndexOf(' ', max);
        return (at > max / 2 ? t.substring(0, at) : t.substring(0, max)).strip() + "…";
    }

    /** Up to three names, then "et al.". */
    public static String authors(List<String> names) {
        if (names == null || names.isEmpty()) return "";
        return String.join(", ", names.subList(0, Math.min(3, names.size()))) + (names.size() > 3 ? " et al." : "");
    }
}
