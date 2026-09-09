package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CATALOGING crew — authority control (the architecture notes, §crews): every finding gets
 * subjects from the CONTROLLED VOCABULARY in {@code catalog/subjects.md}, never free-text topics.
 * The pattern is SPIRES (extraction must land on canonical identifiers, validated against a
 * vocabulary external to the model) and Causaly (the ontology is human-curated even at 500M
 * facts): the model PROPOSES which existing subjects fit and may PROPOSE new ones; the machine
 * writes only slugs that exist in the vocabulary; new vocabulary goes to
 * {@code catalog/subjects.proposed.md} for the person, or is accepted wholesale with
 * {@code --accept-all} — vocabulary is the council's, never minted silently.
 *
 * <p>Subjects are catalog metadata, not reviewed substance: assigning them does not stale an
 * approval (they are outside {@link Finding#contentHash()}).
 */
public final class Cataloger {

    private static final ObjectMapper M = new ObjectMapper();

    /** The model seat: given the vocabulary and a finding, which slugs fit, and what is missing. */
    public interface Judge {
        String ground(String vocabularyList, String findingText) throws Exception;
    }

    private Cataloger() { }

    /** slug → description, from catalog/subjects.md ("- slug — description" lines). */
    public static Map<String, String> vocabulary(LibraryStore store) {
        Map<String, String> v = new LinkedHashMap<>();
        try {
            if (!Files.exists(store.subjectsFile())) return v;
            for (String line : Files.readAllLines(store.subjectsFile(), StandardCharsets.UTF_8)) {
                String l = line.strip();
                if (!l.startsWith("-")) continue;
                l = l.substring(1).strip();
                String[] parts = l.split("\\s+[—-]+\\s+", 2);
                String slug = parts[0].strip();
                if (slug.matches("[a-z0-9]+(?:--?[a-z0-9]+)*")) v.put(slug, parts.length > 1 ? parts[1].strip() : "");
            }
        } catch (IOException ignored) { }
        return v;
    }

    public static void addToVocabulary(LibraryStore store, String slug, String description) throws IOException {
        if (vocabulary(store).containsKey(slug)) return;
        Files.writeString(store.subjectsFile(), "- " + slug + " — " + description + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Record a proposal for the person; duplicates collapse. */
    static void propose(LibraryStore store, String slug, String description) throws IOException {
        var f = store.subjectsFile().resolveSibling("subjects.proposed.md");
        if (Files.exists(f) && Files.readString(f, StandardCharsets.UTF_8).contains("- " + slug + " ")) return;
        if (!Files.exists(f)) {
            Files.writeString(f, "# Proposed subjects — the cataloger's suggestions, yours to approve\n\n"
                    + "Move a line into subjects.md to accept it, or `researchzosho catalog --accept-all`.\n\n",
                    StandardCharsets.UTF_8);
        }
        Files.writeString(f, "- " + slug + " — " + description + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    public record Outcome(int grounded, int proposals, List<String> problems) { }

    /**
     * Ground every finding that has no subjects. With {@code acceptAll}, proposed subjects are
     * added to the vocabulary first and then applied — the council delegating in bulk.
     */
    public static Outcome run(LibraryStore store, Judge judge, boolean acceptAll) throws Exception {
        int grounded = 0, proposals = 0;
        List<String> problems = new ArrayList<>();
        for (Finding f : store.scanFindings().findings()) {
            if (!f.subjects().isEmpty() || f.state() == Finding.State.retired) continue;
            Map<String, String> vocab = vocabulary(store);
            JsonNode v;
            try {
                v = parse(judge.ground(vocabList(vocab), f.title() + "\n\n" + f.body()));
            } catch (Exception e) {
                problems.add(f.id() + ": cataloger call failed — " + e.getMessage());
                continue;
            }
            if (v == null) { problems.add(f.id() + ": cataloger answer unparseable"); continue; }
            // proposals first, so accept-all can apply them in the same pass
            for (JsonNode p : v.path("proposed")) {
                String line = p.asText("").strip();
                int colon = line.indexOf(':');
                String slug = (colon > 0 ? line.substring(0, colon) : line).strip().toLowerCase();
                String desc = colon > 0 ? line.substring(colon + 1).strip() : "";
                if (!slug.matches("[a-z0-9]+(?:--?[a-z0-9]+)*") || vocab.containsKey(slug)) continue;
                if (acceptAll) { addToVocabulary(store, slug, desc); vocab = vocabulary(store); }
                else propose(store, slug, desc);
                proposals++;
            }
            List<String> chosen = new ArrayList<>();
            for (JsonNode s : v.path("subjects")) {
                String slug = s.asText("").strip().toLowerCase();
                if (vocab.containsKey(slug) && !chosen.contains(slug)) chosen.add(slug);   // ONLY vocabulary slugs
            }
            if (acceptAll) {
                for (JsonNode p : v.path("proposed")) {
                    String line = p.asText("").strip();
                    String slug = (line.contains(":") ? line.substring(0, line.indexOf(':')) : line).strip().toLowerCase();
                    if (vocab.containsKey(slug) && !chosen.contains(slug)) chosen.add(slug);
                }
            }
            if (chosen.isEmpty()) continue;
            Finding g = new Finding(f.id(), f.title(), chosen, f.state(), f.claimType(), f.confidence(),
                    f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(), f.sources(),
                    f.supersedes(), f.review(), f.body(), f.triple(), f.notes());   // the 15-arg form erased triple + notes (Wyrdsekai, 2026-09-07)
            store.write(g);
            new LibrarianIndex(store).upsert(g);
            grounded++;
        }
        if (grounded > 0) store.regenerateIndex();
        store.circulate("catalog", grounded + " grounded, " + proposals + " proposed");
        return new Outcome(grounded, proposals, problems);
    }

    static String vocabList(Map<String, String> vocab) {
        if (vocab.isEmpty()) return "  (empty — every subject you name will be a PROPOSAL)\n";
        StringBuilder sb = new StringBuilder();
        for (var e : vocab.entrySet()) sb.append("  ").append(e.getKey()).append(" — ").append(e.getValue()).append('\n');
        return sb.toString();
    }

    /** The live seat. The vocabulary is ENUMERATED and slugs are copied; new ones are proposals. */
    public static Judge driveJudge(org.researchzosho.drive.DriveClient drive) {
        return (vocabList, findingText) -> {
            var msgs = M.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "You are The Librarian's cataloger. Assign this finding its SUBJECTS from the "
                    + "controlled vocabulary below — copy slugs EXACTLY as listed, 1 to 3 of them. "
                    + "When no listed subject fits a facet of the finding, PROPOSE a new slug "
                    + "(lowercase, words joined by hyphens, a double hyphen between facet and topic, "
                    + "e.g. translation--register) with a one-line description.\n\n"
                    + "Answer with JSON ONLY: {\"subjects\": [\"slug\", ...], \"proposed\": [\"slug: description\", ...]}\n\n"
                    + "VOCABULARY:\n" + vocabList + "\nFINDING:\n" + findingText);
            return drive.classify(msgs, 300);
        };
    }

    static void cli(LibraryStore store, String[] args, String baseUrl, String model) throws Exception {
        boolean acceptAll = false;
        String drive = baseUrl;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--accept-all")) acceptAll = true;
            else if (args[i].startsWith("http")) drive = args[i];
        }
        Outcome o = run(store, driveJudge(new org.researchzosho.drive.DriveClient(drive, model)), acceptAll);
        System.out.println("cataloged: " + o.grounded() + " finding(s) grounded, " + o.proposals()
                + " subject proposal(s)" + (acceptAll ? " accepted into the vocabulary" : " → catalog/subjects.proposed.md"));
        for (String p : o.problems()) System.out.println("  note: " + p);
        System.out.println("vocabulary now: " + vocabulary(store).size() + " subject(s) in " + store.subjectsFile());
    }

    private static JsonNode parse(String raw) {
        try {
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            return a < 0 || b <= a ? null : M.readTree(raw.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
