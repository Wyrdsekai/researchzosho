package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The basis every report has, under any field: the claims with their marks, the disputes, the checks, the cites and the sources as
 * before, and three things the writer is asked for and the library checks mechanically — a link on the line of every named thing that
 * has a page, a line per asked point saying how it was checked, and a section of what was left for later. The writer gets {@link
 * #RULES}; {@link #check} reads the finished write-up and puts the link on a cited line that lacks one, says which lines of the answer
 * carry no source, and marks a checked-by line that does not say how.
 */
public final class ReportBasis {
    private ReportBasis() { }

    public static final String CHECKED_HEADING = "How each point was checked";
    public static final String OPEN_HEADING = "Open questions and sub-goals";

    /** The writer's rules, positive, with one literal example of a line with its link and one of a checked-by line. */
    public static final String RULES = " THE REPORT'S BASIS, under every field. Every thing the answer names that has a page of its own, a home, a "
            + "video, a product, a filing, an opening, a person's public page, carries that page's link ON ITS OWN LINE, written as [name](url) with the "
            + "address from the SOURCES list, and the Sources section lists the address again; a footnote number alone is never the link. For example: "
            + "- [108 Example St, Cambridge](https://example.org/listing/108) — 4 bedrooms, $1,670,000 [50]. "
            + "After the answer, write a section '" + CHECKED_HEADING + "': one line per feature or requirement the ask named, saying how it was "
            + "checked, by an instrument that looked (the frames at a second, the transcript, the records, a catalogue) or only read in a page's "
            + "description. For example: - An inside pocket: checked by looking at the frames at 4:12 [3]. - Silent, no voiceover: read in the "
            + "description, not checked. A point no instrument checked says 'not checked' in those words. Questions you leave for later, and "
            + "parts of the ask you followed as a sub-question of their own, go in a section '" + OPEN_HEADING + "', one a line with the reason.";

    /** The write-up after the check, the check's lines for the report's Checks section, and the counts. */
    public record Check(String text, List<String> lines, int linked, int added, int unlinked, boolean checkedSection, int unsaid) { }

    private static final Pattern LIST = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s+(.*)$");
    private static final Pattern CITE = Pattern.compile("\\[(\\d+)\\]");
    private static final Pattern CHECKED_WORDS = Pattern.compile("(?i)checked by|not checked|read in|the records say|the catalogue says|by looking|by reading");

    /**
     * The write-up read for the basis. {@code locators} maps a source number to its address. A list line of the Answer that cites a
     * source with an address and carries no link gets the address as a link at its end; a list line of the Answer that cites nothing and
     * links nothing is counted and named; the checked-by section is looked for, and a line in it that does not say how it was checked
     * is marked so. The Sources and References sections are left as they are.
     */
    public static Check check(String text, Map<Integer, String> locators) {
        String[] lines = text.split("\n", -1);
        boolean inSources = false, inAnswer = false, inChecked = false, checkedSection = false;
        int linked = 0, added = 0, unlinked = 0, unsaid = 0;
        List<String> noSource = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.startsWith("#")) {
                String h = l.replaceFirst("^#+\\s*", "").strip().toLowerCase(Locale.ROOT);
                inSources = h.startsWith("sources") || h.startsWith("references") || h.startsWith("cite-check") || h.startsWith("checks");
                inAnswer = h.equals("answer");
                inChecked = h.equals(CHECKED_HEADING.toLowerCase(Locale.ROOT));
                if (inChecked) checkedSection = true;
                continue;
            }
            if (inSources) continue;
            Matcher li = LIST.matcher(l);
            if (!li.matches()) continue;
            String item = li.group(1);
            if (inChecked) {
                if (!CHECKED_WORDS.matcher(item).find()) { lines[i] = l + " (how it was checked is not said)"; unsaid++; }
                continue;
            }
            if (item.contains("](http")) { linked++; continue; }
            String url = null;
            Matcher c = CITE.matcher(item);
            while (c.find()) {
                String loc = locators == null ? null : locators.get(Integer.parseInt(c.group(1)));
                if (loc != null && loc.startsWith("http")) { url = loc; break; }
            }
            if (url != null) { lines[i] = l + " — [page](" + url + ")"; linked++; added++; }
            else if (inAnswer) { unlinked++; if (noSource.size() < 3) noSource.add(Acquisitions.compress(item, 80)); }
        }
        List<String> out = new ArrayList<>();
        if (added > 0) out.add("link: " + added + " line" + (added == 1 ? "" : "s") + " of the answer named a thing with a source and no link; the source's address is now the link on the line");
        if (unlinked > 0) out.add("link: " + unlinked + " line" + (unlinked == 1 ? "" : "s") + " of the answer name something with no source and no link: " + String.join("; ", noSource.stream().map(s -> "\"" + s + "\"").toList()));
        if (!checkedSection) out.add("checked-by: the report has no section '" + CHECKED_HEADING + "', so what it says of the ask's points is as read, not as looked at");
        if (unsaid > 0) out.add("checked-by: " + unsaid + " line" + (unsaid == 1 ? "" : "s") + " of '" + CHECKED_HEADING + "' do" + (unsaid == 1 ? "es" : "") + " not say how the point was checked, and " + (unsaid == 1 ? "is" : "are") + " marked so");
        return new Check(String.join("\n", lines), out, linked, added, unlinked, checkedSection, unsaid);
    }

    /** The section of what the library left for later, written by the library: each sub-question the run could not afford, with the reason. */
    public static String openSection(List<String> open, boolean headingAlready) { return openSection(open, List.of(), headingAlready); }

    /**
     * The section's lines: the sub-goals pursued now first ("pursued now as home: …"), then every question left for later, each with
     * its reason — an entry that carries its own (filed for later, for you to answer, pursued late) stands as it is, the rest were the
     * sub-questions the budget could not afford.
     */
    public static String openSection(List<String> open, List<String> pursuedNow, boolean headingAlready) {
        StringBuilder b = new StringBuilder();
        if (!headingAlready) b.append("## ").append(OPEN_HEADING).append("\n\nQuestions this run left for later, each with why.\n\n");
        else b.append("The library adds the sub-questions the run could not afford:\n\n");
        for (String q : pursuedNow) b.append("- ").append(q).append("\n");
        for (String q : open) b.append("- ").append(q).append(saysWhy(q) ? "" : " — not researched this run: the budget ran out before it.").append("\n");
        return b.toString().stripTrailing();
    }

    /** An open question written with its own reason by the brain's decisions. */
    static boolean saysWhy(String q) {
        String l = q.toLowerCase(Locale.ROOT);
        return l.startsWith("filed for later:") || l.startsWith("for you to answer:") || l.startsWith("pursued late");
    }
}
