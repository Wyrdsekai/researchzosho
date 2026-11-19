package org.researchzosho.librarian;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * A claim that rests on one source says so (round E; the owner: "yes. to confirm we call that out yes?"). The probe saw a page that
 * called its own claim "the consensus of historians" stated as fact in research reports and filed as a finding, because the cite-check
 * asks only whether the cited page says it, and it did.
 *
 * <p>What is reused: the sources a finding keeps, and {@link Independence}, which already counted copies of one text once and a source
 * that cites another as no second voice, and on which the review's one-source rule keeps a claim with one independent source a draft
 * (LibrarianReview) and gives it its second source when another run brings the same claim from elsewhere ("corroborated"). What is
 * new: the publisher ({@link Independence#publisher}), so that two pages of one site are one voice; a report's sentence that cites one
 * voice, and that no other voice read in the run states, is written as that source's claim; a finding with one voice is marked, wherever
 * it is shown (the entry, its page, the answer package, the chat), and the mark goes by itself when a second voice joins its sources.
 * Words of consensus or authority inside the one source count for nothing: only sources are counted. A report's sentences are not
 * rewritten by the library (the owner, 2026-11-18, after the live check found most rewrites wrong); its writer is told to write a claim
 * that rests on one source as that source's claim ({@link #WRITE}), and the call-out comes back judged by a model in 0.8.1.
 */
final class OneSource {
    private OneSource() { }

    /** What a model that writes is told, in the report writer's prompt and the chat's: whose claim a claim is, and when it is plainly so. */
    static final String WRITE = "Write a claim that one source alone supports as that source's claim, naming it: \"coastal-facts.org says …\". "
            + "State plainly what two or more independent sources support (different publishers, not copies of one text). Words such as "
            + "\"historians agree\" inside a single source are that source's words.";

    /**
     * The one voice a finding rests on, as a reader names it (the publisher, or the work's own name), or null: two or more independent
     * voices stand behind it, it has no sources, or its source is the person's own document or the code itself, which are not a
     * publisher's claim.
     */
    static String of(LibraryStore store, Finding f) {
        if (f == null || f.sources().isEmpty()) return null;
        List<String> locs = new ArrayList<>();
        for (Finding.Source s : f.sources()) {
            SourceTier tier = SourceTier.of(s.locator());
            if (tier == SourceTier.personal || tier == SourceTier.code) return null;
            if (!s.locator().isBlank()) locs.add(s.locator());
        }
        if (locs.isEmpty()) return null;
        if (new LinkedHashSet<>(Independence.voices(store, locs).values()).size() != 1) return null;
        Finding.Source first = f.sources().get(0);
        return said(first.locator(), first.edition());
    }

    /** One source as a reader names it: its publisher, else its edition (a paper's citation), else the locator itself. */
    static String said(String locator, String edition) {
        String p = Independence.publisherSaid(locator);
        if (p != null) return p;
        if (edition != null && !edition.isBlank() && !"n/a".equals(edition)) return edition;
        return locator;
    }

    /** The finding ids a chat reply cites in brackets that rest on one source, each with the line the library says under the reply. */
    static List<String> chatLines(LibraryStore store, String reply, List<String> madeUp) {
        List<String> out = new ArrayList<>();
        if (store == null || reply == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        Matcher b = Librarian.BRACKETS.matcher(reply);
        while (b.find()) {
            Matcher m = Librarian.CITED_ID.matcher(b.group(1));
            while (m.find()) {
                String id = m.group();
                if (!id.startsWith("F-") || madeUp.contains(id) || !seen.add(id)) continue;
                try {
                    Finding f = id.matches("F-\\d+") ? store.findingByCode(id) : store.finding(id);
                    String one = of(store, f);
                    if (one != null) out.add(Chrome.now().t("{0} rests on one source, {1}; no other source found confirms it.", id, one));
                } catch (IOException | RuntimeException unreadable) { /* an id that names nothing readable is the made-up check's */ }
            }
        }
        return out;
    }
}
