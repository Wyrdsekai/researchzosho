package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Seeks (0.6.6): the same question researched several times apart, and one report merged from them. Each seek is an ordinary research
 * run on its OWN copy of the shelves, made under {@code seeks/<group>/<job>/} when the run starts: the library's findings, reports and
 * pages as they stand, indexed. What a seek writes stays there, so two seeks of a group never read each other's fresh shelving, and a
 * weak seek's claims never reach the library. The merge reads the seeks' reports, puts their claims to the judge in pairs (the same thing,
 * a contradiction, or different things), and writes one report on the library's shelves: what the seeks agree on, filed as findings; where
 * they contradict each other, filed as disputed findings with the question on the frontier; what only one found, listed and not filed.
 * {@code drop} removes a group's seeks; the merged report stays.
 *
 * <p>A trial feature: everything lives here, with small hooks in the daemon (a seek's run and the merge run), the protocol ({@code seeks}),
 * the CLI ({@code researchzosho seeks …}, {@code research ask --seek}) and the MCP tool {@code library_seeks}.
 */
public final class Seeks {
    private Seeks() { }

    static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");
    static final Pattern JOB = Pattern.compile("^J-\\d+$");
    /** The job kind of a merge run. */
    public static final String KIND_MERGE = "merge";
    private static final ObjectMapper M = new ObjectMapper();
    /** Claim pairs with less of their words in common are not put to the judge; at most this many pairs per two seeks are. */
    static final double OVERLAP_MIN = 0.08;
    static final int PAIRS_MAX = 60;

    /** The embedder a seek's shelves are indexed with: the library's, as set; a test gives none. */
    static volatile java.util.function.Supplier<Embeddings.Embedder> EMBEDDER = Embeddings::configured;

    static Path dir(LibraryStore store) { return store.root().resolve("seeks"); }
    static Path groupDir(LibraryStore store, String group) { return dir(store).resolve(group); }
    static Path groupFile(LibraryStore store, String group) { return groupDir(store, group).resolve("group.json"); }

    /** A group's name as it may be used, or the refusal. */
    public static String checkedName(String name) {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(n).matches()) throw ProtocolError.invalidArgs("A seek group's name is letters, digits and dashes, up to 40 of them, such as gedcom-7 or q1.");
        return n;
    }

    static String checkedJob(String job) {
        String j = job == null ? "" : job.strip();
        if (!JOB.matcher(j).matches()) throw ProtocolError.invalidArgs("A seek is named by its run's id, such as J-0126.");
        return j;
    }

    // ---- a seek's own shelves ----

    /**
     * The seek's own shelves, made on first use: the library's findings, reports, pages and subjects copied as they stand, then indexed.
     * The group's record notes the seek with its model and sources.
     */
    public static synchronized LibraryStore scratchFor(LibraryStore lead, String group, String jobId, String question, String model, String sources) throws IOException {
        checkedName(group); checkedJob(jobId);
        Path root = groupDir(lead, group).resolve(jobId);
        LibraryStore scratch = new LibraryStore(root);
        if (!Files.isDirectory(root.resolve("catalog"))) {
            scratch.init();
            scratch.startSerialsAt(seekSerials(jobId));
            copyFiles(lead.findingsDir(), scratch.findingsDir());
            copyFiles(lead.investigationsDir(), scratch.investigationsDir());
            copyFiles(lead.rawDir(), scratch.rawDir());
            if (Files.isRegularFile(lead.subjectsFile())) { Files.createDirectories(scratch.subjectsFile().getParent()); Files.copy(lead.subjectsFile(), scratch.subjectsFile(), StandardCopyOption.REPLACE_EXISTING); }
            // the search index is copied, not rebuilt: a rebuild embeds every page and claim again (14 minutes on a library of 4,500 pages,
            // measured 2026-10-22); a copy that does not open, or none to copy, is rebuilt
            boolean indexed = false;
            if (Files.isDirectory(lead.luceneDir())) {
                try {
                    copyFiles(lead.luceneDir(), scratch.luceneDir());
                    Files.deleteIfExists(scratch.luceneDir().resolve("write.lock"));
                    new LibrarianIndex(scratch, EMBEDDER.get()).search("a", 1);
                    indexed = true;
                } catch (Exception e) { indexed = false; }
            }
            if (!indexed) new LibrarianIndex(scratch, EMBEDDER.get()).rebuild();
        }
        ObjectNode g = group(lead, group);
        if (g.path("question").asText("").isEmpty()) g.put("question", question == null ? "" : question);
        if (!g.has("created")) g.put("created", Instant.now().toString());
        ObjectNode s = seek(g, jobId);
        s.put("model", model == null ? "" : model); s.put("sources", sources == null ? "" : sources);
        if (!s.has("started")) s.put("started", Instant.now().toString());
        save(lead, group, g);
        return scratch;
    }

    /** Where a seek's own ids start: its run's number × 10,000, so no seek's id is the library's or another seek's (J-0157 → I-1570001 first). */
    static int seekSerials(String jobId) {
        long n = Long.parseLong(jobId.replaceAll("\\D", ""));
        return (int) Math.min(n * 10_000L, Integer.MAX_VALUE - 1_000_000L);
    }

    /** The seek's own shelves as they are; null when the seek has none. */
    static LibraryStore scratchOf(LibraryStore lead, String group, String jobId) {
        Path root = groupDir(lead, group).resolve(jobId);
        return Files.isDirectory(root.resolve("catalog")) ? new LibraryStore(root) : null;
    }

    private static void copyFiles(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> s = Files.list(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(p.getFileName().toString());
                if (Files.isDirectory(p)) copyFiles(p, t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** After a seek landed: the id of the report it wrote on its own shelves, and the run's last word. */
    public static synchronized void landed(LibraryStore lead, String group, String jobId, String investigationId, String result) throws IOException {
        ObjectNode g = group(lead, group);
        ObjectNode s = seek(g, jobId);
        if (investigationId != null && !investigationId.isBlank()) s.put("investigation", investigationId);
        s.put("result", result == null ? "" : result);
        s.put("landed", Instant.now().toString());
        save(lead, group, g);
    }

    static ObjectNode group(LibraryStore lead, String group) throws IOException {
        Path f = groupFile(lead, group);
        if (!Files.isRegularFile(f)) return M.createObjectNode().put("group", group);
        JsonNode n = M.readTree(Files.readString(f, StandardCharsets.UTF_8));
        return n.isObject() ? (ObjectNode) n : M.createObjectNode().put("group", group);
    }

    private static ObjectNode seek(ObjectNode g, String jobId) {
        ObjectNode seeks = g.has("seeks") && g.get("seeks").isObject() ? (ObjectNode) g.get("seeks") : g.putObject("seeks");
        return seeks.has(jobId) && seeks.get(jobId).isObject() ? (ObjectNode) seeks.get(jobId) : seeks.putObject(jobId);
    }

    private static void save(LibraryStore lead, String group, ObjectNode g) throws IOException {
        Files.createDirectories(groupDir(lead, group));
        Files.writeString(groupFile(lead, group), g.toPrettyString() + "\n", StandardCharsets.UTF_8);
    }

    /** Every group, with its record: question, seeks (model, sources, report, landed) and the merge when there was one. */
    public static List<ObjectNode> list(LibraryStore lead) throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        if (!Files.isDirectory(dir(lead))) return out;
        try (Stream<Path> s = Files.list(dir(lead))) {
            for (Path p : s.sorted().toList()) if (Files.isRegularFile(p.resolve("group.json"))) out.add(group(lead, p.getFileName().toString()));
        }
        return out;
    }

    /** The ids of a group's seeks, in the order they were filed. */
    public static List<String> jobsOf(ObjectNode g) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = g.path("seeks").fieldNames(); it.hasNext(); ) out.add(it.next());
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** A seek's report from its own shelves, or the merged report from the library's when {@code jobId} is blank; null when there is none yet. */
    public static Investigation read(LibraryStore lead, String group, String jobId) throws IOException {
        checkedName(group);
        ObjectNode g = group(lead, group);
        if (jobId == null || jobId.isBlank()) {
            String m = g.path("merged").path("investigation").asText("");
            return m.isEmpty() ? null : lead.investigation(m);
        }
        checkedJob(jobId);
        String inv = g.path("seeks").path(jobId).path("investigation").asText("");
        LibraryStore sc = scratchOf(lead, group, jobId);
        return inv.isEmpty() || sc == null ? null : sc.investigation(inv);
    }

    /** Drops a group: its seeks' shelves and its record. A merged report stays on the library's shelves. False when there is no such group. */
    public static synchronized boolean drop(LibraryStore lead, String group) throws IOException {
        checkedName(group);
        Path d = groupDir(lead, group);
        if (!Files.isDirectory(d)) return false;
        try (Stream<Path> s = Files.walk(d)) { for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        return true;
    }

    // ---- the merge ----

    /** One seek's report and the claims it filed on its own shelves. */
    record SeekClaims(String job, String model, String sources, Investigation report, List<Finding> claims) {
        String label() { return job + (model.isEmpty() ? "" : " (" + model + ")"); }
    }

    /** What the merge wrote: the report's id and the counts. */
    public record Merged(String investigationId, int agreed, int disputed, int lone) { }

    /**
     * One report from a group's seeks. The judge reads claim pairs that share words (at most {@link #PAIRS_MAX} per two seeks) and says
     * whether they are the same thing, a contradiction, or different things; the writer seat writes the answer. Agreed claims are filed
     * as findings with every seek's sources; contradictions as disputed findings and a frontier question; what only one seek found is
     * listed in the report and not filed. Every pair put to the judge is written in the report with its verdict, so the person can see
     * what the judge saw and what it could not read.
     */
    public static Merged merge(LibraryStore lead, String group, Researcher.Drive judge, String writer) throws IOException {
        checkedName(group);
        ObjectNode g = group(lead, group);
        String question = g.path("question").asText("");
        List<SeekClaims> seeks = new ArrayList<>();
        for (String job : jobsOf(g)) {
            JsonNode s = g.path("seeks").path(job);
            Investigation inv = read(lead, group, job);
            LibraryStore sc = scratchOf(lead, group, job);
            if (inv == null || sc == null) continue;
            List<Finding> claims = new ArrayList<>();
            for (String fid : inv.findings()) { Finding f = sc.finding(fid); if (f != null) claims.add(f); }
            seeks.add(new SeekClaims(job, s.path("model").asText(""), s.path("sources").asText(""), inv, claims));
        }
        if (seeks.size() < 2) throw ProtocolError.invalidArgs("A merge needs at least two seeks that landed; " + group + " has " + seeks.size() + ".");

        // the pairs: a claim of one seek against the claims of every later seek
        Map<String, String> parent = new HashMap<>();          // union-find over "job|finding"
        Map<String, Finding> byKey = new LinkedHashMap<>();
        Map<String, SeekClaims> seekOf = new HashMap<>();
        for (SeekClaims s : seeks) for (Finding f : s.claims()) { String k = s.job() + "|" + f.id(); parent.put(k, k); byKey.put(k, f); seekOf.put(k, s); }
        List<String[]> contradictions = new ArrayList<>();     // keyA, keyB, why
        Set<String> contradicted = new HashSet<>();
        List<String[]> judgments = new ArrayList<>();          // keyA, keyB, verdict, why — every pair put to the judge, for the person to check
        int unread = 0;
        for (int i = 0; i < seeks.size(); i++) for (int j = i + 1; j < seeks.size(); j++) {
            List<double[]> candidates = new ArrayList<>();      // overlap, a index, b index
            List<Finding> as = seeks.get(i).claims(), bs = seeks.get(j).claims();
            for (int a = 0; a < as.size(); a++) for (int b = 0; b < bs.size(); b++) {
                double o = overlap(as.get(a), bs.get(b));
                if (o >= OVERLAP_MIN) candidates.add(new double[]{o, a, b});
            }
            candidates.sort((x, y) -> Double.compare(y[0], x[0]));
            int asked = 0;
            for (double[] c : candidates) {
                if (asked++ >= PAIRS_MAX) break;
                Finding fa = as.get((int) c[1]), fb = bs.get((int) c[2]);
                String ka = seeks.get(i).job() + "|" + fa.id(), kb = seeks.get(j).job() + "|" + fb.id();
                String[] verdict = ask(judge, question, fa, fb);
                judgments.add(new String[]{ka, kb, verdict[0], verdict[1]});
                switch (verdict[0]) {
                    case "SAME" -> union(parent, ka, kb);
                    case "CONTRADICT" -> { contradictions.add(new String[]{ka, kb, verdict[1]}); contradicted.add(ka); contradicted.add(kb); }
                    case UNREAD -> unread++;
                    default -> { }
                }
            }
        }
        // the sets of claims that say the same thing, by the seeks that said them
        Map<String, List<String>> sets = new LinkedHashMap<>();
        for (String k : byKey.keySet()) sets.computeIfAbsent(find(parent, k), x -> new ArrayList<>()).add(k);

        LibrarianIndex index = new LibrarianIndex(lead);
        String now = Instant.now().toString();
        List<String> filed = new ArrayList<>();
        StringBuilder agreedMd = new StringBuilder(), disputedMd = new StringBuilder(), loneMd = new StringBuilder();
        int agreed = 0, disputed = 0, lone = 0;
        Set<String> inAgreedSet = new HashSet<>();
        for (List<String> set : sets.values()) {
            Set<String> jobs = new LinkedHashSet<>();
            for (String k : set) jobs.add(seekOf.get(k).job());
            if (jobs.size() < 2) continue;
            Finding first = byKey.get(set.get(0));
            List<Finding.Source> sources = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String k : set) for (Finding.Source src : byKey.get(k).sources()) if (seen.add(src.locator())) sources.add(src);
            String id = lead.nextFindingId(first.title());
            Finding copy = new Finding(id, first.title(), first.subjects(), Finding.State.draft, first.claimType(), first.confidence(), "seeks:" + group, now,
                    first.validAsOf(), first.volatility(), first.reviewBy(), sources, List.of(), null, first.body());
            lead.write(copy); index.upsert(copy); filed.add(id); agreed++;
            inAgreedSet.addAll(set);
            agreedMd.append("- ").append(first.title()).append(" — found by ").append(String.join(", ", jobs)).append(" [").append(id).append("]\n");
        }
        Set<String> written = new HashSet<>();
        Map<String, String> disputedIds = new HashMap<>();
        for (String[] c : contradictions) {
            String[] ids = new String[2];
            for (int n = 0; n < 2; n++) {
                String k = c[n];
                if (disputedIds.containsKey(k)) { ids[n] = disputedIds.get(k); continue; }
                Finding f = byKey.get(k);
                String id = lead.nextFindingId(f.title());
                Finding copy = new Finding(id, f.title(), f.subjects(), Finding.State.disputed, f.claimType(), f.confidence(), "seek:" + seekOf.get(k).job(), now,
                        f.validAsOf(), f.volatility(), f.reviewBy(), f.sources(), List.of(), null, f.body());
                lead.write(copy); index.upsert(copy); filed.add(id); disputedIds.put(k, id); ids[n] = id; written.add(k);
            }
            lead.frontier("dispute", ids[0] + " vs " + ids[1] + " — what evidence would settle it? (seeks " + group + ": " + seekOf.get(c[0]).job() + " vs " + seekOf.get(c[1]).job() + ")");
            disputed++;
            disputedMd.append("- ").append(byKey.get(c[0]).title()).append(" (").append(seekOf.get(c[0]).label()).append(") against ").append(byKey.get(c[1]).title())
                    .append(" (").append(seekOf.get(c[1]).label()).append(")").append(c[2].isEmpty() ? "" : ": " + c[2]).append(" [").append(ids[0]).append(", ").append(ids[1]).append("]\n");
        }
        for (String k : byKey.keySet()) {
            if (inAgreedSet.contains(k) || contradicted.contains(k)) continue;
            Finding f = byKey.get(k);
            lone++;
            loneMd.append("- ").append(f.title()).append(" — only ").append(seekOf.get(k).label());
            if (!f.sources().isEmpty()) loneMd.append(" (").append(f.sources().get(0).locator()).append(")");
            loneMd.append('\n');
        }
        // open questions: every seek's, once
        Set<String> open = new LinkedHashSet<>();
        for (SeekClaims s : seeks) for (String q : s.report().open()) if (q != null && !q.isBlank()) open.add(q.strip());

        StringBuilder judgedMd = new StringBuilder();
        for (String[] j : judgments) judgedMd.append("- ").append(byKey.get(j[0]).title()).append(" (").append(seekOf.get(j[0]).job()).append(") ↔ ").append(byKey.get(j[1]).title()).append(" (").append(seekOf.get(j[1]).job()).append("): ")
                .append(j[2]).append(j[3].isEmpty() ? "" : " — " + j[3]).append('\n');
        String answer = answer(judge, question, seeks, agreedMd.toString(), disputedMd.toString(), loneMd.toString());
        StringBuilder body = new StringBuilder();
        body.append("## Question\n\n").append(question).append("\n\nMerged from ").append(seeks.size()).append(" seeks (").append(group).append(")")
                .append(": ").append(agreed).append(" claim").append(agreed == 1 ? "" : "s").append(" agreed, ").append(disputed).append(" disputed, ").append(lone).append(" found by one seek.\n\n");
        body.append("## Answer\n\n").append(answer.isBlank() ? "(the writer seat gave no answer; read the seeks' reports below)" : answer.strip()).append("\n\n");
        body.append("## Agreed\n\n").append(agreedMd.length() == 0 ? "Nothing the seeks said alike.\n" : agreedMd).append('\n');
        body.append("## Disputed\n\n").append(disputedMd.length() == 0 ? "No contradictions between the seeks.\n" : disputedMd).append('\n');
        body.append("## Found by one seek\n\n").append(loneMd.length() == 0 ? "Nothing that only one seek found.\n" : "Not filed on the shelves; the seek's own report has the evidence.\n\n" + loneMd).append('\n');
        body.append("## Judgments\n\n").append(judgments.isEmpty() ? "No pair of claims shared enough words to be put to the judge.\n" : judgments.size() + " pair" + (judgments.size() == 1 ? "" : "s") + " put to the judge"
                + (unread == 0 ? "" : ", " + unread + " it could not read (listed as UNREAD, counted as nothing)") + ".\n\n" + judgedMd).append('\n');
        body.append("## Open questions\n\n");
        if (open.isEmpty()) body.append("None left by the seeks.\n"); else for (String q : open) body.append("- ").append(q).append('\n');
        body.append("\n## Seeks\n\n| seek | model | sources | report | claims |\n|---|---|---|---|---|\n");
        for (SeekClaims s : seeks) body.append("| ").append(s.job()).append(" | ").append(s.model().isEmpty() ? "the library's" : s.model()).append(" | ").append(s.sources()).append(" | ").append(s.report().id()).append(" | ").append(s.claims().size()).append(" |\n");
        String invId = lead.nextInvestigationId(question.isEmpty() ? group : question);
        Investigation merged = new Investigation(invId, Acquisitions.compress(question.isEmpty() ? "Seeks " + group : question, 120), Finding.State.draft, writer, now, filed, new ArrayList<>(open), body.toString());
        lead.write(merged); index.upsert(merged);
        ObjectNode m = g.putObject("merged");
        m.put("investigation", invId); m.put("at", now); m.put("agreed", agreed); m.put("disputed", disputed); m.put("lone", lone);
        save(lead, group, g);
        return new Merged(invId, agreed, disputed, lone);
    }

    private static String find(Map<String, String> parent, String k) { String p = parent.get(k); while (!p.equals(k)) { k = p; p = parent.get(k); } return p; }
    private static void union(Map<String, String> parent, String a, String b) { String ra = find(parent, a), rb = find(parent, b); if (!ra.equals(rb)) parent.put(ra, rb); }

    /** Words two claims share, as a share of the words of either: the cheap sieve before the judge. */
    static double overlap(Finding a, Finding b) {
        Set<String> wa = words(a), wb = words(b);
        if (wa.isEmpty() || wb.isEmpty()) return 0;
        int both = 0; for (String w : wa) if (wb.contains(w)) both++;
        return (double) both / Math.min(wa.size(), wb.size());
    }

    private static Set<String> words(Finding f) {
        Set<String> out = new HashSet<>();
        for (String w : (f.title() + " " + (f.body() == null ? "" : f.body())).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 4) out.add(w);
        return out;
    }

    /** What the judge may say of a pair, and what it says when its reply could not be read. */
    static final String UNREAD = "UNREAD";

    /**
     * The judge on two claims: SAME, CONTRADICT or DIFFERENT, and its sentence why. The reply is cleaned of a model's thinking and of a tool
     * call it should not have made, and read for the one verdict it names; a reply that names none is asked once more, and one that still
     * names none is UNREAD, which the merge lists as such and never counts as a verdict (a merge once took an empty reply for DIFFERENT and
     * two claims that agreed were filed as found by one seek each, 2026-10-22).
     */
    static String[] ask(Researcher.Drive judge, String question, Finding a, Finding b) {
        String prompt = "Two researchers answered the same question separately: " + question + "\n\nHere is one claim from each.\n\nA: " + a.title() + "\n" + clip(a.body(), 600)
                + "\n\nB: " + b.title() + "\n" + clip(b.body(), 600)
                + "\n\nDo A and B say the same thing (SAME), contradict each other so that they cannot both be true (CONTRADICT), or speak of different things (DIFFERENT)? "
                + "Two claims about the same subject and the same property are SAME when they agree and CONTRADICT when they differ, even when one gives more detail. "
                + "Answer with exactly one word on the first line: SAME, CONTRADICT or DIFFERENT. Then one sentence why.";
        String[] read = judged(judge, prompt);
        if (read == null) read = judged(judge, prompt + "\n\nThe one word first, on a line of its own, then the sentence.");
        return read == null ? new String[]{UNREAD, "the judge gave no reading"} : read;
    }

    private static String[] judged(Researcher.Drive judge, String prompt) {
        ArrayNode messages = M.createArrayNode();
        messages.addObject().put("role", "user").put("content", prompt);
        String reply;
        try { reply = clean(judge.classify(messages, 300)); } catch (RuntimeException e) { reply = ""; }
        String verdict = verdictOf(reply);
        if (verdict == null) return null;
        String why = reply.contains("\n") ? reply.substring(reply.indexOf('\n') + 1).strip() : reply.replaceFirst("(?i)^\\W*" + verdict + "\\W*", "").strip();
        return new String[]{verdict, clip(why, 200)};
    }

    /** A reply without the model's thinking and without a tool call it should not have made; "" when nothing readable is left. */
    static String clean(String reply) {
        if (reply == null) return "";
        String s = reply.replaceAll("(?s)<think>.*?</think>", "").replaceAll("(?s)<tool_call>.*?</tool_call>", "").replaceAll("(?s)<tool_call>.*$", "").replaceAll("(?s)<function=.*$", "");
        int t = s.lastIndexOf("</think>");
        if (t >= 0) s = s.substring(t + "</think>".length());
        return s.strip();
    }

    /** The one verdict a cleaned reply names: on its first line, else once in the whole of it; null when it names none or more than one. */
    static String verdictOf(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) return null;
        String[] lines = cleaned.split("\n");
        String first = "";
        for (String l : lines) if (!l.isBlank()) { first = l; break; }
        String v = oneVerdict(first);
        return v != null ? v : oneVerdict(cleaned);
    }

    private static String oneVerdict(String text) {
        String up = " " + text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", " ") + " ";
        String found = null; int n = 0;
        for (String v : new String[]{"CONTRADICT", "DIFFERENT", "SAME"}) if (up.contains(" " + v + " ") || up.contains(" " + v + "S ")) { n++; found = v; }
        return n == 1 ? found : null;
    }

    /** The writer seat's merged answer, from the seeks' own answers and the sorted claims. */
    static String answer(Researcher.Drive judge, String question, List<SeekClaims> seeks, String agreed, String disputed, String lone) {
        StringBuilder p = new StringBuilder("The question: ").append(question).append("\n\n").append(seeks.size()).append(" researchers answered it separately, without seeing each other's work.\n");
        for (SeekClaims s : seeks) {
            p.append("\nResearcher ").append(s.label()).append(" answered:\n").append(clip(answerOf(s.report().body()), 2500)).append('\n');
            if (!s.claims().isEmpty()) { p.append("Its claims, with their sources:\n"); for (Finding f : s.claims()) p.append("- ").append(f.title()).append(": ").append(clip(f.body(), 240)).append(f.sources().isEmpty() ? "" : " (" + f.sources().get(0).locator() + ")").append('\n'); }
        }
        p.append("\nClaims they agree on:\n").append(agreed.isEmpty() ? "(none)\n" : agreed);
        p.append("\nClaims where they contradict each other:\n").append(disputed.isEmpty() ? "(none)\n" : disputed);
        p.append("\nClaims only one of them made:\n").append(lone.isEmpty() ? "(none)\n" : lone);
        p.append("\nWrite the merged answer for the person who asked, in plain prose, at most 300 words: what is agreed; where they contradict each other, "
                + "which side has the better source, if that can be told, a primary and more recent source counting for more; and what only one found, naming that "
                + "researcher. The lists above are the whole of what was found: call nothing a disagreement that is not listed under contradictions, and say of a claim only "
                + "one made that it is one researcher's finding, not that the other denied it. Keep the sources' names as they appear. No headings.");
        ArrayNode messages = M.createArrayNode();
        messages.addObject().put("role", "user").put("content", p.toString());
        for (int attempt = 0; attempt < 2; attempt++) {
            String a;
            try { a = clean(judge.prose(messages, 900)); } catch (RuntimeException e) { a = ""; }
            if (!a.isBlank()) return a;
        }
        return "";
    }

    /** The answer section of a report's body: from its first "## Answer" heading to the next heading that is not one. */
    static String answerOf(String body) {
        if (body == null) return "";
        body = "\n" + body;
        int at = body.indexOf("\n## Answer");
        if (at < 0) return clip(body, 2500);
        int start = body.indexOf('\n', at + 1);
        if (start < 0) return "";
        String rest = body.substring(start + 1);
        int next = 0;
        while (true) {
            int h = rest.indexOf("\n## ", next);
            if (h < 0) return rest.strip();
            if (rest.startsWith("\n## Answer", h)) { next = h + 1; continue; }
            return rest.substring(0, h).strip();
        }
    }

    private static String clip(String s, int max) { if (s == null) return ""; s = s.strip(); return s.length() <= max ? s : s.substring(0, max) + "…"; }
}
