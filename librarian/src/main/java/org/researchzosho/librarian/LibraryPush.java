package org.researchzosho.librarian;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
/**
 * The PUSH half of retrieval (push before pull — measured repeatedly: small models do not call
 * recall tools) and the desk's deterministic answer package.
 *
 * <p>Every consumption site gets the authority-tier sentence (the GPD discipline — stated where
 * the content is CONSUMED, not once in a spec): library content is reviewed background; it
 * never overrides direct evidence or the task at hand. Disputed entries are pushed WITH their
 * dispute flag — a mapped disagreement is knowledge; hiding it would be the overwrite problem
 * wearing a different hat.
 */
public final class LibraryPush {

    private LibraryPush() { }

    /**
     * The context block pushed into decompose / the chat register, or "" when the library is
     * absent or holds nothing relevant. Relevance-selected top-K — never the whole index (the
     * index outgrows any context within months at real research rates).
     */
    public static String block(String question, int k) {
        if (!Acquisitions.libraryExists()) return "";
        return block(LibraryStore.open(), question, k);
    }

    /** A report named in the question ("Follow-up to I-0010 (…): …") is handed to the run whole, its answer first, before the claims. */
    static final Pattern NAMED_REPORT = Pattern.compile("\\b(I-\\d{3,}-[a-z0-9-]+)");
    static final int REPORT_CHARS = 14_000;

    static String reportNamed(LibraryStore store, String question) {
        Matcher m = NAMED_REPORT.matcher(question == null ? "" : question);
        while (m.find()) {
            try {
                Investigation inv = store.investigation(m.group(1));
                if (inv == null) continue;
                String body = inv.body() == null ? "" : inv.body();
                int cut = body.length();
                for (String h : List.of("\n## Evidence", "\n## References", "\n## Worker findings", "\n## Sources cited")) { int i = body.indexOf(h); if (i >= 0 && i < cut) cut = i; }
                String text = body.substring(0, Math.min(cut, REPORT_CHARS));
                return "THE EARLIER REPORT THIS QUESTION FOLLOWS (" + inv.id() + ", \"" + Acquisitions.compress(inv.title(), 120) + "\"; build on it, cite it as [" + inv.id() + "], and spend the run on what it did not settle):\n"
                        + Fence.wrap("EARLIER REPORT", text) + "\n" + Fence.rule("EARLIER REPORT") + "\n\n";
            } catch (Exception e) { return ""; }
        }
        return "";
    }

    static String block(LibraryStore store, String question, int k) {
        String named = reportNamed(store, question);
        try {
            LibrarianIndex index = new LibrarianIndex(store);
            List<LibrarianIndex.Hit> hits = index.searchStrict(question, k, null, null);   // never push a one-word match
            if (hits.isEmpty()) return named;
            StringBuilder sb = new StringBuilder(named).append(
                    "LIBRARY (reviewed background from earlier research — build on it, do not "
                    + "re-research it; it never overrides direct evidence you gather now):\n");
            int rendered = 0;
            for (var h : hits) {
                if (!"finding".equals(h.kind())) continue;
                Finding f = store.finding(h.id());
                if (f == null || f.state() == Finding.State.retired) continue;
                sb.append("- ");
                if (f.state() == Finding.State.disputed) sb.append("(DISPUTED) ");
                else if (f.state() == Finding.State.draft) sb.append("(unreviewed draft) ");
                sb.append(f.title()).append(": ")
                  .append(Acquisitions.compress(f.body(), 220));
                if (!f.sources().isEmpty()) {
                    sb.append("  [").append(f.sources().get(0).locator()).append(']');
                }
                sb.append('\n');
                rendered++;
            }
            if (rendered == 0) return named;
            store.circulate("push", Acquisitions.compress(question, 120));
            Heat.used(store, hits.stream().map(LibrarianIndex.Hit::id).toList());
            return sb.append('\n').toString();
        } catch (Exception e) {
            return named; // an unreadable library is an absent library, never a crashed run
        }
    }

    /**
     * The desk's answer PACKAGE (Causaly's shape, deterministic in v1 — no model composes here,
     * so what the patron receives is exactly what the shelves hold): full entries, their states
     * and sources, what the library does NOT hold, and open frontier threads that touch the
     * question. The patron's own model does any composing.
     */
    public static String answerPackage(String question, int k) {
        if (!Acquisitions.libraryExists()) {
            return "(no library — run `researchzosho init` to create one)";
        }
        return answerPackage(LibraryStore.open(), question, k);
    }

    static String answerPackage(LibraryStore store, String question, int k) {
        try {
            LibrarianIndex index = new LibrarianIndex(store);
            List<LibrarianIndex.Hit> hits = index.searchStrict(question, k, null, null);   // "holds NOTHING" must be able to fire
            StringBuilder sb = new StringBuilder();
            sb.append("THE LIBRARIAN — holdings relevant to: ")
              .append(Acquisitions.compress(question, 140)).append('\n');
            sb.append("(reviewed background; it never overrides direct evidence)\n\n");
            int rendered = 0;
            for (var h : hits) {
                if ("finding".equals(h.kind())) {
                    Finding f = store.finding(h.id());
                    if (f == null || f.state() == Finding.State.retired) continue;
                    sb.append("== ").append(f.id()).append(" [").append(f.state()).append(", ")
                      .append(f.claimType()).append(", confidence ").append(f.confidence())
                      .append(", sources ").append(SourceTier.strongest(f.sources()))
                      .append("]\n").append(f.title()).append('\n')
                      .append(f.body().strip()).append('\n');
                    for (var s : f.sources()) {
                        sb.append("  source: ").append(s.locator());
                        if (!"n/a".equals(s.edition())) sb.append(" (").append(s.edition()).append(')');
                        sb.append('\n');
                    }
                    if (f.reviewStale()) sb.append("  NOTE: content edited since last review\n");
                    if (!f.subjects().isEmpty()) sb.append("  subjects: ").append(String.join(", ", f.subjects())).append('\n');
                    var rel = Related.of(store, f, 4);
                    if (!rel.isEmpty()) {
                        sb.append("  related:");
                        for (var n : rel) sb.append(' ').append(n.id()).append(" (").append(String.join(",", n.shared())).append(')');
                        sb.append('\n');
                    }
                    sb.append('\n');
                    rendered++;
                } else if ("investigation".equals(h.kind())) {
                    Investigation inv = store.investigation(h.id());
                    if (inv == null) continue;
                    sb.append("== ").append(inv.id()).append(" [investigation, ")
                      .append(inv.state()).append("]\n").append(inv.title()).append('\n')
                      .append(Acquisitions.compress(inv.body(), 500)).append("\n\n");
                    rendered++;
                } else if ("article".equals(h.kind())) {
                    Path ap = store.articlesDir().resolve(h.id() + ".md");
                    if (!Files.exists(ap)) continue;
                    String text = Files.readString(ap);
                    int body = text.indexOf("\n---\n", 4);
                    sb.append("== ").append(h.id()).append(" [shelf article — generated from the findings it cites]\n")
                      .append(h.title()).append('\n')
                      .append(body < 0 ? text : text.substring(body + 5).strip()).append("\n\n");
                    rendered++;
                } else if ("raw".equals(h.kind())) {
                    // a captured DOCUMENT — the source itself, not a claim about it
                    Path rp = store.rawDir().resolve(h.id());
                    if (!Files.exists(rp)) continue;
                    String[] r = RawCapture.read(rp);
                    sb.append("== raw/").append(h.id()).append(" [captured document — quoted text, not the library's voice]\n")
                      .append(r[1].isEmpty() ? r[0] : r[1]).append('\n')
                      .append("  source: ").append(r[0]).append('\n')
                      .append(Fence.wrap("CAPTURED TEXT", Acquisitions.compress(h.snippet().isBlank() ? r[2] : h.snippet(), 500))).append("\n\n");
                    rendered++;
                }
            }
            if (rendered == 0) {
                sb.append("The library holds NOTHING on this. That is the answer — not a guess.\n");
            }
            String frontier = frontierMatches(store, question);
            if (!frontier.isEmpty()) sb.append("OPEN THREADS touching this:\n").append(frontier);
            store.circulate("desk", Acquisitions.compress(question, 120));
            return sb.toString();
        } catch (Exception e) {
            return "(library unreadable: " + e.getMessage() + ")";
        }
    }

    /** Frontier lines sharing a term with the question — cheap, deterministic, good enough for v1. */
    private static String frontierMatches(LibraryStore store, String question) {
        try {
            if (!Files.exists(store.frontierFile())) return "";
            var qTerms = new HashSet<String>();
            for (String w : question.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
                if (w.length() >= 4) qTerms.add(w);
            }
            StringBuilder sb = new StringBuilder();
            for (String line : Files.readAllLines(store.frontierFile())) {
                if (!line.startsWith("- ")) continue;
                String lower = line.toLowerCase();
                if (qTerms.stream().anyMatch(lower::contains)) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
