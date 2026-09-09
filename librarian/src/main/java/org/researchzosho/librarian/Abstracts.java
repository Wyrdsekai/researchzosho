package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ABSTRACTING crew — per-subject ARTICLES (the architecture notes, §crews; the WikiCrow /
 * STORM shape): the library writes its own condensed page for each subject from the findings
 * on that shelf — fixed sections, every sentence cited by finding id — and regenerates it when
 * the shelf changes. This is the condensed tier the desk should answer FROM: "what do we hold on
 * translation--register?" is a maintained page, not a pile of hits.
 *
 * <p>Division of labor: the MODEL writes prose over an ENUMERATED list of findings and cites by
 * id; the MACHINE decides which shelves need regenerating (a hash of the shelf's finding ids and
 * content hashes), verifies every cited id exists, counts uncited sentences and reports them,
 * and refuses an article that cites nothing. Drafts are included but LABELLED, accepted findings
 * carry the page. Articles are indexed (kind=article) and searchable like everything else.
 */
public final class Abstracts {

    public interface Writer {
        /** Prose for one subject over the enumerated findings; cites like [F-0012]. */
        String write(String subject, String description, String enumeratedFindings) throws Exception;
    }

    private Abstracts() { }

    public record Outcome(int written, int unchanged, List<String> problems) { }

    /** The shelf for a subject: non-retired findings carrying it, accepted first. */
    public static List<Finding> shelf(LibraryStore store, String subject) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : store.scanFindings().findings()) {
            if (f.state() == Finding.State.retired || !f.subjects().contains(subject)) continue;
            out.add(f);
        }
        out.sort((a, b) -> Integer.compare(rank(a.state()), rank(b.state())));
        return out;
    }

    private static int rank(Finding.State s) {
        return switch (s) { case accepted -> 0; case disputed -> 1; case superseded -> 2; default -> 3; };
    }

    /** What the article was generated from — changes when any finding on the shelf changes. */
    static String shelfHash(List<Finding> shelf) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : shelf) sb.append(f.id()).append(':').append(f.state()).append(':').append(f.contentHash()).append('\n');
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(d, 0, 8);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static Path articleFile(LibraryStore store, String subject) {
        return store.articlesDir().resolve("A-" + subject + ".md");
    }

    static String currentHash(Path article) {
        try {
            if (!Files.exists(article)) return null;
            Matcher m = Pattern.compile("(?m)^shelf_hash: (\\S+)$").matcher(Files.readString(article, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (IOException e) { return null; }
    }

    /** Regenerate every subject whose shelf changed (or only {@code only}, when given). */
    public static Outcome run(LibraryStore store, Writer writer, List<String> only) throws Exception {
        int written = 0, unchanged = 0;
        List<String> problems = new ArrayList<>();
        var vocab = Cataloger.vocabulary(store);
        LibrarianIndex index = new LibrarianIndex(store);
        for (var e : vocab.entrySet()) {
            String subject = e.getKey();
            if (only != null && !only.isEmpty() && !only.contains(subject)) continue;
            List<Finding> shelf = shelf(store, subject);
            if (shelf.isEmpty()) continue;
            String hash = shelfHash(shelf);
            Path file = articleFile(store, subject);
            if (hash.equals(currentHash(file))) { unchanged++; continue; }
            StringBuilder enumerated = new StringBuilder();
            for (Finding f : shelf) {
                enumerated.append("[").append(f.id()).append("] (").append(f.state()).append(", ")
                          .append(f.claimType()).append(") ").append(f.title()).append(": ")
                          .append(Acquisitions.compress(f.body(), 600)).append('\n');
            }
            String prose;
            try {
                prose = writer.write(subject, e.getValue(), enumerated.toString());
                // One retry when the first draft cites nothing at all (measured: two shelves out of
                // eighteen came back as uncited prose from the same model that cited the rest). The
                // retry is the same enumerated shelf with the citation rule restated and the ids
                // listed alone; a second refusal stands.
                if (!Pattern.compile("F-\\d+-[a-z0-9-]+").matcher(prose).find()) {
                    StringBuilder idsOnly = new StringBuilder();
                    for (Finding f : shelf) idsOnly.append('[').append(f.id()).append("] ");
                    prose = writer.write(subject, e.getValue(), enumerated
                            + "\nRETRY — your previous draft cited nothing. Every sentence that states a fact "
                            + "ENDS with one of exactly these ids, copied character for character: " + idsOnly);
                }
            } catch (Exception ex) {
                problems.add(subject + ": writer failed — " + ex.getMessage());
                continue;
            }
            // MACHINE checks: cited ids must exist on the shelf; count uncited sentences.
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Finding f : shelf) ids.add(f.id());
            // Citations may arrive bare (F-0012), parenthesised or bracketed — the id is what is
            // unmintable, not the brackets. Normalise every shelf id to [F-…] before checking.
            for (Finding f : shelf) {
                prose = prose.replaceAll("[\\[(]?\\b" + Pattern.quote(f.id()) + "\\b[\\])]?", "[" + f.id() + "]");
            }
            List<String> unknown = new ArrayList<>();
            Matcher cm = Pattern.compile("\\[(F-\\d+-[a-z0-9-]+)\\]").matcher(prose);
            int cited = 0;
            while (cm.find()) { cited++; if (!ids.contains(cm.group(1))) unknown.add(cm.group(1)); }
            if (cited == 0) {
                problems.add(subject + ": article cites nothing — refused (head: \""
                        + Acquisitions.compress(prose, 140) + "\")");
                continue;
            }
            if (!unknown.isEmpty()) problems.add(subject + ": cites ids not on the shelf " + unknown + " — written, flagged");
            int uncited = 0, sentences = 0;
            for (String line : prose.split("\\r?\\n")) {
                if (line.strip().startsWith("#")) continue;          // headings are not claims
                for (String sent : line.split("(?<=[.!?。])\\s+")) {
                    String t = sent.strip();
                    if (t.length() < 25) continue;
                    sentences++;
                    if (!t.contains("[F-")) uncited++;
                }
            }
            StringBuilder doc = new StringBuilder("---\n");
            doc.append("schema: 1\n");
            doc.append("subject: ").append(subject).append('\n');
            doc.append("title: ").append(e.getValue().isBlank() ? subject : e.getValue()).append('\n');
            doc.append("generated_at: ").append(Instant.now()).append('\n');
            doc.append("shelf_hash: ").append(hash).append('\n');
            doc.append("findings: [").append(String.join(", ", shelf.stream().map(Finding::id).toList())).append("]\n");
            doc.append("uncited_sentences: ").append(uncited).append('/').append(sentences).append('\n');
            doc.append("---\n").append(prose.strip()).append('\n');
            Files.createDirectories(file.getParent());
            Files.writeString(file, doc.toString(), StandardCharsets.UTF_8);
            index.upsertArticle(subject, e.getValue().isBlank() ? subject : e.getValue(), prose);
            written++;
        }
        store.circulate("abstract", written + " written, " + unchanged + " unchanged");
        return new Outcome(written, unchanged, problems);
    }

    /** The live seat: fixed sections, cite by the enumerated ids, drafts labelled. */
    public static Writer driveWriter(org.researchzosho.drive.DriveClient drive) {
        return (subject, description, enumerated) -> {
            var m = new com.fasterxml.jackson.databind.ObjectMapper();
            var msgs = m.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "You are The Librarian writing the shelf ARTICLE for the subject '" + subject
                    + "' (" + description + "). Write it ONLY from the findings listed below, in these "
                    + "sections, as markdown:\n\n## Summary\n## What the library holds\n## Disagreements and drafts\n"
                    + "## Open questions\n\nRules: every sentence that states a fact ends with the id of the "
                    + "finding it comes from, copied exactly, like [F-0012]. Findings marked draft or disputed "
                    + "are reported AS draft or disputed, never as settled. Do not add knowledge that is not "
                    + "on the shelf; if the shelf is thin, say so. 200-500 words.\n\nFINDINGS ON THE SHELF:\n"
                    + enumerated);
            return drive.classify(msgs, 1400);
        };
    }

    static void cli(LibraryStore store, String[] args, String baseUrl, String model) throws Exception {
        List<String> only = new ArrayList<>();
        String drive = baseUrl;
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("http")) drive = args[i]; else only.add(args[i]);
        }
        Outcome o = run(store, driveWriter(new org.researchzosho.drive.DriveClient(drive, model)), only);
        System.out.println("articles: " + o.written() + " written, " + o.unchanged() + " unchanged (shelf hash matched)");
        for (String p : o.problems()) System.out.println("  note: " + p);
        System.out.println("  " + store.articlesDir());
    }
}
