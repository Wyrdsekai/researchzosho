package org.researchzosho.librarian;

import org.researchzosho.records.RecordSource;
import org.researchzosho.records.RecordSources;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The class of a source as the reader of a dossier needs it (0.6.6): where a claim's evidence comes from. Computed from the locator,
 * never stored and never the model's opinion, the way {@link SourceTier} is: a record collection's host is read by the collection's tier
 * (a register, a filing, a court, a catalogue: public record; an account or a speaker's own words: own publication; a newspaper page or a
 * scanned book: third-party); a government host is public record; a page the person shelved themselves is supplied; everything else is
 * third-party; what a person told the library ({@code told://…}) is told; and with the subject known ({@link Own}), a site whose address
 * carries the subject's own name is its own publication, as researchzosho.org is ResearchZosho's. Canon, a fictional subject's own work, is not read from a host:
 * the writer keeps In-world and Real-world apart by section.
 * The first run of the person field put no class on anything, because the rule lived only in the writer's prose (2026-10-23).
 */
public enum SourceClass {
    publicRecord("public record"), ownPublication("own publication"), told("told"), supplied("supplied"), thirdParty("third-party"), canon("canon");

    private final String label;
    SourceClass(String label) { this.label = label; }
    public String label() { return label; }

    /** The class of one locator. */
    public static SourceClass of(String locator) {
        if (locator == null || locator.isBlank()) return thirdParty;
        String l = locator.strip();
        if (l.startsWith("told://")) return told;   // what a person told the library in conversation, in their words, dated
        if (SourceTier.of(l) == SourceTier.personal) return supplied;
        String host;
        try { host = URI.create(l.toLowerCase(Locale.ROOT)).getHost(); } catch (Exception e) { host = null; }
        if (host == null) return thirdParty;
        if (host.startsWith("www.")) host = host.substring(4);
        for (RecordSource s : RecordSources.all()) for (String h : s.hosts()) if (host.equals(h) || host.endsWith("." + h)) return ofSource(s);
        if (host.endsWith(".gov") || host.contains(".gov.") || host.endsWith(".go.jp") || host.endsWith(".lg.jp") || host.endsWith(".gc.ca") || host.endsWith(".europa.eu") || host.endsWith(".mil")) return publicRecord;
        return thirdParty;
    }

    /**
     * The places a subject speaks for itself: the web addresses whose own name is the subject's, as researchzosho.org is ResearchZosho's,
     * newschool.edu The New School's and harvard.edu Harvard University's. Read from the subject's names, never from the model. A person
     * has none by name, because a namesake's site would pass for theirs. A research run reads them from its question's own words.
     */
    public record Own(Set<String> labels) {
        public static final Own NONE = new Own(Set.of());

        /** From a subject's names and its kind; for a person, none. */
        public static Own of(Collection<String> names, String kind) {
            if (kind != null && kind.equalsIgnoreCase("person")) return NONE;
            Set<String> out = new LinkedHashSet<>();
            for (String n : names) out.addAll(squashes(n));
            return out.isEmpty() ? NONE : new Own(Set.copyOf(out));
        }

        /**
         * From the words of a question about an organisation or a product: each run of one to four words that neither begins nor ends with
         * a small word, so "who is" never names whois.com; a word of three letters counts only when it is written in capitals, as BBC is.
         */
        public static Own inText(String text) {
            if (text == null || text.isBlank()) return NONE;
            List<String> w = new ArrayList<>(); List<Boolean> caps = new ArrayList<>();
            for (String t : text.split("[^\\p{L}\\p{N}]+")) {
                if (t.isEmpty()) continue;
                w.add(t.toLowerCase(Locale.ROOT));
                caps.add(t.length() >= 2 && t.equals(t.toUpperCase(Locale.ROOT)) && t.chars().anyMatch(Character::isLetter));
            }
            Set<String> out = new LinkedHashSet<>();
            for (int i = 0; i < w.size(); i++) for (int k = 1; k <= 4 && i + k <= w.size(); k++) {
                if (SMALL.contains(w.get(i)) || SMALL.contains(w.get(i + k - 1))) continue;
                String s = String.join("", w.subList(i, i + k));
                if (!s.chars().allMatch(c -> c < 128)) continue;
                if (s.length() >= 4 || (k == 1 && caps.get(i) && s.length() >= 2)) out.add(s);
            }
            return out.isEmpty() ? NONE : new Own(Set.copyOf(out));
        }

        /** Whether a web address is one of the subject's own: its registered name is one of the subject's. */
        public boolean owns(String locator) {
            if (labels.isEmpty() || locator == null) return false;
            String host;
            try { host = URI.create(locator.strip().toLowerCase(Locale.ROOT)).getHost(); } catch (Exception e) { return false; }
            if (host == null) return false;
            String label = registrable(host.startsWith("www.") ? host.substring(4) : host);
            return !label.isEmpty() && labels.contains(label);
        }
    }

    /** Words a company's or a school's name ends with that its address leaves out: Mercari, Inc. is mercari.com; Harvard University, harvard.edu. */
    static final Set<String> GENERIC = Set.of("inc", "llc", "ltd", "limited", "corp", "corporation", "company", "co", "gmbh", "ag", "sa", "plc", "kk",
            "group", "holdings", "university", "college", "institute", "foundation", "us", "usa", "uk", "japan", "jp", "global", "international");
    /** Words a name in a question does not begin or end with. */
    static final Set<String> SMALL = Set.of("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "by", "at", "as", "is", "are", "was", "be",
            "who", "what", "how", "why", "when", "where", "which", "does", "do", "did", "it", "its", "this", "that", "about", "from", "into", "than", "then",
            "so", "if", "not", "no", "me", "my", "you", "your", "we", "our", "they", "their", "he", "she", "his", "her", "has", "have", "had", "will", "can",
            "could", "should", "would", "may", "might", "all", "any", "some", "more", "most", "other", "such", "only", "also", "just", "very", "now", "say", "says");
    /** Suffixes of two parts under which a name stands one place further left: bbc.co.uk is "bbc". */
    static final Set<String> SECOND = Set.of("co.uk", "org.uk", "ac.uk", "gov.uk", "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp", "com.au", "org.au", "edu.au",
            "net.au", "co.nz", "com.br", "com.cn", "com.tw", "co.kr", "co.in", "com.sg", "com.hk");

    /** A host's registered name: researchzosho for blog.researchzosho.org, bbc for news.bbc.co.uk; "" when it has none. */
    static String registrable(String host) {
        String[] p = host.split("\\.");
        if (p.length < 2) return "";
        String last2 = p[p.length - 2] + "." + p[p.length - 1];
        if (SECOND.contains(last2)) return p.length >= 3 ? p[p.length - 3] : "";
        return p[p.length - 2];
    }

    /** A name squashed as an address writes it, whole and without "the" and the generic words it ends with: "The New School", newschool. */
    static Set<String> squashes(String name) {
        Set<String> out = new LinkedHashSet<>();
        if (name == null) return out;
        List<String> w = new ArrayList<>();
        for (String t : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (!t.isEmpty()) w.add(t);
        if (w.isEmpty()) return out;
        keep(out, String.join("", w));
        List<String> core = new ArrayList<>(w);
        if (core.size() > 1 && core.get(0).equals("the")) core.remove(0);
        while (core.size() > 1 && GENERIC.contains(core.get(core.size() - 1))) core.remove(core.size() - 1);
        keep(out, String.join("", core));
        return out;
    }

    private static void keep(Set<String> out, String s) { if (s.length() >= 3 && s.chars().allMatch(c -> c < 128)) out.add(s); }

    /** The class of one locator, with the subject's own sites known: a third-party page on one of them is its own publication. */
    public static SourceClass of(String locator, Own own) {
        SourceClass c = of(locator);
        return c == thirdParty && own != null && own.owns(locator) ? ownPublication : c;
    }

    /** A finding's class with the subject's own sites known: the strongest among its sources. */
    public static SourceClass of(List<Finding.Source> sources, Own own) {
        SourceClass best = thirdParty;
        for (Finding.Source s : sources) { SourceClass c = of(s.locator(), own); if (c.ordinal() < best.ordinal()) best = c; }
        return best;
    }

    /** A record collection read as a class: by what kind of thing it holds first (a patent read through machine text is still the office's record), then by its tier. */
    static SourceClass ofSource(RecordSource s) {
        String kind = s.kind() == null ? "" : s.kind().toLowerCase(Locale.ROOT), t = s.tier() == null ? "" : s.tier().toLowerCase(Locale.ROOT);
        if (t.contains("own publication") || t.contains("the project itself") || kind.equals("code") || kind.equals("package") || kind.equals("model")) return ownPublication;
        switch (kind) {
            case "register", "filing", "case", "docket", "record", "patent", "catalogue", "law", "archive": return publicRecord;
            case "newspaper", "book", "index", "tree": return thirdParty;
            default: break;
        }
        if (t.contains("third-party") || t.contains("machine text") || t.startsWith("original")) return thirdParty;
        return publicRecord;
    }

    /** A finding's class: the strongest among its sources, public record, then own publication, then told, then supplied, then third-party. */
    public static SourceClass of(List<Finding.Source> sources) {
        SourceClass best = thirdParty;
        for (Finding.Source s : sources) { SourceClass c = of(s.locator()); if (c.ordinal() < best.ordinal()) best = c; }
        return best;
    }
}
