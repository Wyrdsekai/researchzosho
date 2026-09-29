package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.Set;
import java.util.Collection;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.time.LocalDate;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Declined;
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

    /** How many claims the nightly housekeeping asks about at most: one model question each (RESEARCHZOSHO_CATALOG_PER_NIGHT). */
    public static final int PER_NIGHT = Config.getInt("RESEARCHZOSHO_CATALOG_PER_NIGHT", 200);

    /** Whether a field files this claim its own way ({@link Profile#filesUnderSubjects}), read over every field the build knows, on or off. */
    static boolean keptOut(Finding f, Map<String, Set<String>> runs) {
        for (String field : Fields.ofClaim(f, runs))
            for (Profile p : Profiles.known()) if (p.name().equals(field) && !p.filesUnderSubjects()) return true;
        return false;
    }

    /** The claims the cataloger would ask about now: without subjects, not retired, not a field's own, not answered already for this list. */
    public static int waiting(LibraryStore store) throws IOException {
        Map<String, Set<String>> runs = Fields.runs(store);
        Map<String, String> unmatched = readUnmatched(store);
        String list = listHash(vocabulary(store));
        int n = 0;
        for (Finding f : store.scanFindings().findings())
            if (f.subjects().isEmpty() && f.state() != Finding.State.retired && !keptOut(f, runs) && !(f.contentHash() + " " + list).equals(unmatched.get(f.id()))) n++;
        return n;
    }

    static String listHash(Map<String, String> vocab) { return Integer.toHexString(new TreeSet<>(vocab.keySet()).toString().hashCode()); }

    static Path unmatchedFile(LibraryStore store) { return store.subjectsFile().resolveSibling("subjects.unmatched.tsv"); }
    static Path tallyFile(LibraryStore store) { return store.subjectsFile().resolveSibling("subjects.tally.tsv"); }

    /** Claim id → "content-hash list-hash" of a claim that came back with proposals only. */
    static Map<String, String> readUnmatched(LibraryStore store) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        Path f = unmatchedFile(store);
        if (!Files.exists(f)) return m;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] t = line.split("\t");
            if (t.length == 3) m.put(t[0], t[1] + " " + t[2]);
        }
        return m;
    }

    static void writeUnmatched(LibraryStore store, Map<String, String> m) throws IOException {
        List<String> lines = new ArrayList<>();
        for (var e : m.entrySet()) lines.add(e.getKey() + "\t" + e.getValue().replace(' ', '\t'));
        AtomicWrite.lines(unmatchedFile(store), lines);
    }

    /** How many claims were proposed each new subject: slug → [count, description]. */
    static Map<String, String[]> readTally(LibraryStore store) throws IOException {
        Map<String, String[]> m = new LinkedHashMap<>();
        Path f = tallyFile(store);
        if (!Files.exists(f)) return m;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] t = line.split("\t", 3);
            if (t.length == 3) m.put(t[0], new String[]{t[1], t[2]});
        }
        return m;
    }

    /** One more claim was proposed this subject. */
    static void tally(LibraryStore store, String slug, String description) throws IOException {
        Map<String, String[]> m = readTally(store);
        String[] was = m.get(slug);
        m.put(slug, new String[]{String.valueOf(was == null ? 1 : Integer.parseInt(was[0]) + 1), was == null || was[1].isBlank() ? description : was[1]});
        List<String> lines = new ArrayList<>();
        for (var e : m.entrySet()) lines.add(e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1].replace('\t', ' ').replace('\n', ' '));
        AtomicWrite.lines(tallyFile(store), lines);
    }

    public record Seeded(int asked, List<String> added, int filed) { }

    /**
     * Start a subject list from the claims themselves: ask about every claim that would be filed (the proposals are counted afresh),
     * add each subject proposed for at least {@code min} claims to the list, then file the claims under the list. Family claims and
     * others a field files its own way are left out, as always.
     */
    public static Seeded seed(LibraryStore store, Judge judge, int min) throws Exception {
        Files.deleteIfExists(tallyFile(store));
        Files.deleteIfExists(unmatchedFile(store));
        int asked = waiting(store);
        run(store, judge, false, null, 0);
        List<String> added = new ArrayList<>();
        for (var e : readTally(store).entrySet())
            if (Integer.parseInt(e.getValue()[0]) >= min && !vocabulary(store).containsKey(e.getKey())) { addToVocabulary(store, e.getKey(), e.getValue()[1]); added.add(e.getKey()); }
        Outcome filed = added.isEmpty() ? new Outcome(0, 0, List.of()) : run(store, judge, false, null, 0);
        return new Seeded(asked, added, filed.grounded());
    }


    /**
     * Ground every finding that has no subjects. With {@code acceptAll}, proposed subjects are
     * added to the vocabulary first and then applied — the council delegating in bulk.
     */
    public static Outcome run(LibraryStore store, Judge judge, boolean acceptAll) throws Exception { return run(store, judge, acceptAll, null, 0); }

    /**
     * The same, for {@code only} the claims named (a run's own; null: every claim) and at most {@code limit} model calls (0: no limit).
     * Left alone: a claim a field files its own way ({@link Profile#filesUnderSubjects}: a family's claims), one the model declined, and
     * one that came back with proposals only and has not changed since while the subject list has not changed either — asking again
     * would only propose the same again (a library with an empty list once asked about all 2,296 of its claims after every run).
     */
    public static Outcome run(LibraryStore store, Judge judge, boolean acceptAll, Collection<String> only, int limit) throws Exception {
        int grounded = 0, proposals = 0, asked = 0;
        List<String> problems = new ArrayList<>();
        Map<String, Set<String>> runs = Fields.runs(store);
        Map<String, String> unmatched = readUnmatched(store);
        boolean unmatchedChanged = false;
        for (Finding f : store.scanFindings().findings()) {
            if (!f.subjects().isEmpty() || f.state() == Finding.State.retired) continue;
            if (only != null && !only.contains(f.id())) continue;
            if (keptOut(f, runs)) continue;
            Map<String, String> vocab = vocabulary(store);
            String unmatchedKey = f.contentHash() + " " + listHash(vocab);
            if (unmatchedKey.equals(unmatched.get(f.id()))) continue;
            if (Declines.declinedBefore(store, "catalog", f.id(), f.contentHash())) { problems.add(f.id() + ": " + Declines.notAskedAgain("to give this claim its subjects")); continue; }
            if (limit > 0 && asked >= limit) break;
            asked++;
            JsonNode v;
            try {
                v = parse(judge.ground(vocabList(vocab), f.title() + "\n\n" + f.body()));
            } catch (Declined d) {
                problems.add(f.id() + ": " + d.statement("to give this claim its subjects"));
                Declines.rememberDeclined(store, "catalog", f.id(), f.contentHash(), d);   // not asked again until the claim changes
                continue;
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
                else { propose(store, slug, desc); tally(store, slug, desc); }
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
            boolean inherited = false;
            if (chosen.isEmpty()) { chosen = fromItsRun(store, f); inherited = !chosen.isEmpty(); }   // else the claim has no subject, and a claim with no subject is in no area: invisible to the map, to a scoped search and to bridges (2026-09-15)
            if (chosen.isEmpty()) {   // proposals only: not asked again until the claim or the subject list changes
                unmatched.put(f.id(), unmatchedKey); unmatchedChanged = true;
                continue;
            }
            if (unmatched.remove(f.id()) != null) unmatchedChanged = true;
            Finding g = new Finding(f.id(), f.title(), chosen, f.state(), f.claimType(), f.confidence(),
                    f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(), f.sources(),
                    f.supersedes(), f.review(), f.body(), f.triple(), f.notes());   // the 15-arg form erased triple + notes (Wyrdsekai, 2026-09-07)
            if (inherited) g = g.withNote(new Finding.Note("catalog", "crew:cataloger", LocalDate.now().toString(),
                    "no vocabulary subject matched this claim; placed with the rest of " + origin(f) + ", the run it came out of"));
            store.write(g);
            new LibrarianIndex(store).upsert(g);
            grounded++;
        }
        if (grounded > 0) store.regenerateIndex();
        if (unmatchedChanged) writeUnmatched(store, unmatched);
        store.circulate("catalog", grounded + " grounded, " + proposals + " proposed");
        return new Outcome(grounded, proposals, problems);
    }

    static final Pattern FROM_RUN = Pattern.compile("\\b(I-[A-Za-z0-9_.-]+)");
    /** The note the review writes on a claim a run filed: "cited by I-…". Confirming a claim already held writes "corroborates, from I-…". */
    static final Pattern MADE_BY = Pattern.compile("\\bcited by (I-[A-Za-z0-9_.-]+)");

    /** The investigation a claim came out of: its sources carry "cited by I-…" (a run that only confirmed it is not where it came from). */
    static String origin(Finding f) {
        for (Finding.Source s : f.sources()) {
            Matcher m = MADE_BY.matcher(s.whyItMatters() == null ? "" : s.whyItMatters());
            if (m.find()) return m.group(1);
        }
        return "";
    }

    /** The subjects its sibling claims carry: what the run it came out of is filed under. Empty when there is no sibling. */
    static List<String> fromItsRun(LibraryStore store, Finding f) {
        String inv = origin(f);
        if (inv.isEmpty()) return List.of();
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Finding other : store.scanFindings().findings()) {
            if (other.id().equals(f.id()) || other.subjects().isEmpty() || !inv.equals(origin(other))) continue;
            for (String s : other.subjects()) count.merge(s, 1, Integer::sum);
        }
        if (count.isEmpty()) return List.of();
        int best = Collections.max(count.values());
        List<String> out = new ArrayList<>();
        for (var e : count.entrySet()) if (e.getValue() == best && out.size() < 2) out.add(e.getKey());
        return out;
    }

    static String vocabList(Map<String, String> vocab) {
        if (vocab.isEmpty()) return "  (empty — every subject you name will be a PROPOSAL)\n";
        StringBuilder sb = new StringBuilder();
        for (var e : vocab.entrySet()) sb.append("  ").append(e.getKey()).append(" — ").append(e.getValue()).append('\n');
        return sb.toString();
    }

    /** The live seat. The vocabulary is ENUMERATED and slugs are copied; new ones are proposals. */
    public static Judge driveJudge(DriveClient drive) {
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
        boolean acceptAll = false, seed = false;
        int min = 2;
        String drive = baseUrl;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--accept-all")) acceptAll = true;
            else if (args[i].equals("seed")) seed = true;
            else if (args[i].equals("--min") && i + 1 < args.length) min = Math.max(1, Integer.parseInt(args[++i]));
            else if (args[i].startsWith("http")) drive = args[i];
        }
        if (seed) {
            int n = waiting(store);
            System.out.println("Asking the model which subjects fit each of " + n + " claim(s), one question each, about " + Math.max(1, n * 3 / 60) + " minute(s). Family claims are left out: they are organised by person and relation.");
            Seeded s = seed(store, driveJudge(new DriveClient(drive, model)), min);
            if (s.added().isEmpty()) System.out.println("No subject was proposed for " + min + " or more claims, so the subject list is unchanged. The proposals are in " + store.subjectsFile().resolveSibling("subjects.proposed.md") + ".");
            else System.out.println("Added " + s.added().size() + " subject(s) that " + min + " or more claims were given: " + String.join(", ", s.added()) + ". " + s.filed() + " claim(s) are now filed under them. The subject list is " + store.subjectsFile() + "; edit it as you like.");
            return;
        }
        Outcome o = run(store, driveJudge(new DriveClient(drive, model)), acceptAll);
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
