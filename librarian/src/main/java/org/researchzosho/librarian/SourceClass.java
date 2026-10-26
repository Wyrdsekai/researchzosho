package org.researchzosho.librarian;

import org.researchzosho.records.RecordSource;
import org.researchzosho.records.RecordSources;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * The class of a source as the reader of a dossier needs it (0.6.6): where a claim's evidence comes from. Computed from the locator,
 * never stored and never the model's opinion, the way {@link SourceTier} is: a record collection's host is read by the collection's tier
 * (a register, a filing, a court, a catalogue: public record; an account or a speaker's own words: own publication; a newspaper page or a
 * scanned book: third-party); a government host is public record; a page the person shelved themselves is supplied; everything else is
 * third-party; what a person told the library ({@code told://…}) is told. Canon, a fictional subject's own work, is not read from a host:
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
