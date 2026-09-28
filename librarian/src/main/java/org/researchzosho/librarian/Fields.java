package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which field a piece of work belongs to, told by where it came from and never guessed from its words. A profile that joins only when
 * asked ({@link Profile#joinsOnlyWhenAsked}) acts on its own work alone: the runs somebody asked of it, the claims its own intake wrote,
 * the claims those runs filed, and the open questions it filed. Everything else in the library is ordinary work and is read as if that
 * profile were off.
 *
 * <p>{@code catalog/run-fields.tsv}, one line per run of a field somebody asked for: the report (I-…), the job (J-…), the field, how it
 * was chosen, the date. The line is written before the report is, so nothing that reads the report can miss it. A line that starts with
 * {@code -} takes back the one it names. The file is small and read whole each time.
 *
 * <p>{@code catalog/suggestions.tsv}: each time a person was told, or asked, that a question looks like a field's, so the same question
 * is not told again; and each time the nightly research only wrote it into its log, which does not count as telling the person.
 */
public final class Fields {

    private Fields() { }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("run-fields.tsv"); }
    static Path suggestionsFile(LibraryStore store) { return store.root().resolve("catalog").resolve("suggestions.tsv"); }

    /** One run of a field: its report, its job, the field, how the field was chosen (cli-flag, web-box, mcp-field, chat-yes, …), the date. */
    public record Row(String investigation, String job, String field, String how, String date) { }

    /** A run of {@code field} files {@code investigationId}: written before the report, under the file's own lock. */
    public static void record(LibraryStore store, String investigationId, String jobId, String field, String how) throws IOException {
        String line = clean(investigationId) + "\t" + clean(jobId) + "\t" + clean(field).toLowerCase(Locale.ROOT) + "\t" + clean(how) + "\t" + LocalDate.now() + "\n";
        store.locked("run-fields", () -> {
            Files.createDirectories(file(store).getParent());
            if (!Files.exists(file(store))) Files.writeString(file(store), "# report\tjob\tfield\thow it was chosen\tdate — the runs a field was asked for (researchzosho profile runs)\n", StandardCharsets.UTF_8);
            Files.writeString(file(store), line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            return null;
        });
    }

    /** A run taken back: its report is no longer the field's work. */
    public static void forget(LibraryStore store, String investigationId, String field) throws IOException {
        String line = "-\t" + clean(investigationId) + "\t" + clean(field).toLowerCase(Locale.ROOT) + "\t" + LocalDate.now() + "\n";
        store.locked("run-fields", () -> {
            Files.createDirectories(file(store).getParent());
            Files.writeString(file(store), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return null;
        });
    }

    /** Every run in force, in the order they were written. */
    public static List<Row> rows(LibraryStore store) throws IOException {
        List<Row> out = new ArrayList<>();
        if (store == null || !Files.exists(file(store))) return out;
        for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t", -1);
            if (p[0].equals("-")) { if (p.length >= 3) out.removeIf(r -> r.investigation().equals(p[1]) && r.field().equals(p[2])); continue; }
            if (p.length >= 3 && !p[0].isBlank()) out.add(new Row(p[0], p[1], p[2], p.length > 3 ? p[3] : "", p.length > 4 ? p[4] : ""));
        }
        return out;
    }

    /** Report id → the fields asked of the run that filed it. */
    public static Map<String, Set<String>> runs(LibraryStore store) throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Row r : rows(store)) out.computeIfAbsent(r.investigation(), k -> new LinkedHashSet<>()).add(r.field());
        return out;
    }

    /** The fields asked of the run that filed this report; empty for an ordinary run. */
    public static Set<String> ofRun(LibraryStore store, String investigationId) {
        try { return runs(store).getOrDefault(investigationId, Set.of()); } catch (IOException e) { return Set.of(); }
    }

    static final Pattern CITED_BY = Pattern.compile("^cited by (I-\\d+[A-Za-z0-9-]*)");

    /**
     * The fields a claim is the work of: its writer is a field's own intake, that intake told it again (a {@code source-added} note by
     * one of its writers), or it was filed from a run the field was asked for. Read over every profile the build knows, enabled or not,
     * so a field switched off keeps its work its own.
     */
    public static Set<String> ofClaim(Finding f, Map<String, Set<String>> runs) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> p : Profiles.ownWriters().entrySet()) {
            List<String> own = p.getValue();
            if (own.contains(f.writer())) { out.add(p.getKey()); continue; }
            for (Finding.Note n : f.notes()) if ("source-added".equals(n.kind()) && own.contains(n.by())) { out.add(p.getKey()); break; }
        }
        for (Finding.Source s : f.sources()) {
            Matcher m = CITED_BY.matcher(s.whyItMatters() == null ? "" : s.whyItMatters());
            if (m.find()) { Set<String> r = runs.get(m.group(1)); if (r != null) out.addAll(r); }
        }
        return out;
    }

    /** The same, reading the runs from the library. */
    public static Set<String> ofClaim(LibraryStore store, Finding f) {
        try { return ofClaim(f, runs(store)); } catch (IOException e) { return ofClaim(f, Map.of()); }
    }

    /**
     * The runs whose work a run of {@code fields} may be shown (what they searched, what they looked for and did not find), by report:
     * every run but that of a field that joins only when asked, which only a run of that field is shown. Read over every profile the
     * build knows, on or off.
     */
    public static Predicate<String> shownTo(LibraryStore store, List<String> fields) throws IOException {
        Map<String, Set<String>> runs = runs(store);
        Set<String> modules = new LinkedHashSet<>();
        for (Profile p : Profiles.known()) if (p.joinsOnlyWhenAsked()) modules.add(p.name());
        return report -> runs.getOrDefault(report, Set.of()).stream().noneMatch(f -> modules.contains(f) && !fields.contains(f));
    }

    /** Whether the library holds any of this field's work: its intake's folder exists, or a run of it was asked for. Cheap: no claim is read. */
    public static boolean holdsWork(LibraryStore store, Profile p) {
        if (store == null || p == null) return false;
        Path folder = p.workFolder(store);
        if (folder != null && Files.isDirectory(folder)) return true;
        try { for (Row r : rows(store)) if (r.field().equalsIgnoreCase(p.name())) return true; } catch (IOException ignored) { }
        return false;
    }

    /** Whether the library holds the work of any enabled field that joins only when asked. */
    public static boolean holdsModuleWork(LibraryStore store) {
        for (Profile p : enabled(store)) if (p.joinsOnlyWhenAsked() && holdsWork(store, p)) return true;
        return false;
    }

    /** What the fields whose work this library holds add where the core tells a person what to do next ({@link Profile#hint}). */
    public static String hints(LibraryStore store, String situation) {
        StringBuilder b = new StringBuilder();
        for (Profile p : enabled(store)) if (p.joinsOnlyWhenAsked() && holdsWork(store, p) && !p.hint(situation).isBlank()) b.append(' ').append(p.hint(situation).strip());
        return b.toString();
    }

    static List<Profile> enabled(LibraryStore store) {
        try { return store == null ? Profiles.known() : Profiles.enabledProfiles(store); } catch (IOException e) { return Profiles.known(); }
    }

    /**
     * The fields whose rules join a run: those the ask names, and those that recognise their own questions ({@link Profile#applies}) and
     * do not join only when asked. A field that joins only when asked is never switched on by the words.
     */
    public static List<Profile> forRun(LibraryStore store, Researcher.Ask ask) {
        List<Profile> out = new ArrayList<>();
        for (Profile p : enabled(store)) {
            boolean asked = ask.fields().stream().anyMatch(f -> f.equalsIgnoreCase(p.name()));
            if (asked || (!p.joinsOnlyWhenAsked() && p.applies(ask.question()))) out.add(p);
        }
        return out;
    }

    /**
     * The fields a question's words (or the names in it) belong to: those that would join a run of it by themselves, and one that joins only
     * when asked and would be suggested. What the library recognises, not what it does with it.
     */
    static List<String> recognised(LibraryStore store, String question) {
        List<String> out = new ArrayList<>();
        for (Profile p : forRun(store, new Researcher.Ask(question, "broad", 0, List.of()))) out.add(p.name());
        Suggestion s = suggest(store, question);
        if (s != null && !out.contains(s.field())) out.add(s.field());
        return out;
    }

    /** The profile a field name names, enabled on this library; null when there is none or it is turned off. */
    public static Profile enabledNamed(LibraryStore store, String name) {
        if (name == null || name.isBlank()) return null;
        for (Profile p : enabled(store)) if (p.name().equalsIgnoreCase(name.strip())) return p;
        return null;
    }

    // ---- a library from before runs were recorded ----

    static Path marker(LibraryStore store) { return store.root().resolve("catalog").resolve("migrations").resolve("run-fields.txt"); }

    /**
     * What the upgrade found: runs recorded from the research log, runs recorded because a field's own command filed them, runs recorded
     * because they researched an open question a field's own command filed, and what it tells the owner.
     */
    public record Migrated(int fromLog, int byCommand, int fromQuestions, List<String> notes) { }

    /**
     * Once per library, when this version first runs on it (the service at its start, or {@code researchzosho profile runs upgrade}): the
     * runs of the enabled fields that join only when asked, from before runs were recorded, are written into the ledger, told by where they
     * came from and never by their words. A run is a field's when the research log says the field's rules joined it, when the field's
     * own command filed its job ({@link Profile#filedByOwnCommand}), or when the nightly research took an open question the field's own
     * command filed ({@link #fromExploredQuestions}); research such a command filed and that had not finished runs in the field's mode. Every other earlier run stays ordinary, and nothing is recorded for a field that is off. What each field once wrote for
     * every claim to read is taken back out whether it is on or off ({@link Profile#tidy}). Null when it ran before and found nothing to
     * take back.
     */
    public static Migrated migrate(LibraryStore store) throws IOException {
        return store.locked("migrate-fields", () -> {
            List<String> takenBack = takeBackWordRule(store);
            if (Files.exists(marker(store))) return takenBack.isEmpty() ? null : new Migrated(0, 0, 0, takenBack);
            List<String> notes = new ArrayList<>(takenBack);
            List<Profile> modules = new ArrayList<>();
            for (Profile p : enabled(store)) if (p.joinsOnlyWhenAsked()) modules.add(p);
            Set<String> names = new LinkedHashSet<>();
            for (Profile p : modules) names.add(p.name());
            Map<String, Set<String>> have = runs(store);
            int fromLog = 0, byCommand = 0, fromQuestions = 0;
            for (String[] r : fromCrewsLog(store, names)) {
                if (have.getOrDefault(r[0], Set.of()).contains(r[2])) continue;
                record(store, r[0], r[1], r[2], "backfill-log");
                have.computeIfAbsent(r[0], k -> new LinkedHashSet<>()).add(r[2]);
                fromLog++;
            }
            for (String[] r : fromExploredQuestions(store, modules)) {
                if (have.getOrDefault(r[0], Set.of()).contains(r[1])) continue;
                record(store, r[0], "", r[1], "backfill-question");
                have.computeIfAbsent(r[0], k -> new LinkedHashSet<>()).add(r[1]);
                fromQuestions++;
            }
            if (!modules.isEmpty()) {
                Jobs jobs = new Jobs(store, j -> "");
                for (ObjectNode j : jobs.recent(Integer.MAX_VALUE, null)) {
                    JsonNode a = j.path("args");
                    String report = Jobs.investigationOf(j);
                    if (!"research".equals(j.path("kind").asText()) || !a.isObject() || a.has("field") || report == null) continue;
                    for (Profile p : modules) {
                        if (!p.filedByOwnCommand(a) || have.getOrDefault(report, Set.of()).contains(p.name())) continue;
                        record(store, report, j.path("job_id").asText(), p.name(), "backfill-command");
                        have.computeIfAbsent(report, k -> new LinkedHashSet<>()).add(p.name());
                        byCommand++;
                        break;
                    }
                }
            }
            for (Profile p : Profiles.all()) if (p.joinsOnlyWhenAsked()) notes.addAll(p.tidy(store));
            for (Profile p : modules) {
                Set<String> recorded = new LinkedHashSet<>();
                for (var e : have.entrySet()) if (e.getValue().contains(p.name())) recorded.add(e.getKey());
                notes.addAll(p.upgrade(store, recorded));
            }
            int waiting = backfillWaiting(store, modules);
            if (waiting > 0) notes.add(waiting + (waiting == 1 ? " research run that a field's own command filed in an older version, and that had not finished, runs" : " research runs that a field's own command filed in an older version, and that had not finished, run") + " in that field's mode. To see them: researchzosho jobs");
            Files.createDirectories(marker(store).getParent());
            Files.writeString(marker(store), LocalDate.now() + "\tthe runs of fields that act only when asked were recorded: " + fromLog + " from the research log, " + byCommand + " filed by the field's own command, "
                    + fromQuestions + " that researched a question the field's own command filed\n" + (notes.isEmpty() ? "" : String.join("\n", notes) + "\n"), StandardCharsets.UTF_8);
            return new Migrated(fromLog, byCommand, fromQuestions, notes);
        });
    }

    /**
     * The research an older version filed and had not finished when this version first started (queued, or running when the service
     * stopped): a job one of {@code modules}' own commands filed is run in that field's mode. Every other job runs as it was filed. The
     * number of jobs changed.
     */
    static int backfillWaiting(LibraryStore store, List<Profile> modules) throws IOException {
        if (modules.isEmpty()) return 0;
        Jobs jobs = new Jobs(store, j -> "");
        int changed = 0;
        for (ObjectNode j : jobs.active()) {
            JsonNode a = j.path("args");
            if (!"research".equals(j.path("kind").asText()) || !a.isObject() || a.has("field")) continue;
            for (Profile p : modules) {
                if (!p.filedByOwnCommand(a)) continue;
                if (jobs.backfillField(j.path("job_id").asText(), p.name(), "backfill-command")) changed++;
                break;
            }
        }
        return changed;
    }

    /**
     * A build before 0.5.0 was released recorded earlier runs as a field's by the words of their titles, and gave waiting research a field
     * by the words of its question ({@code backfill-rule}). Words never decide a run's field, so those runs are ordinary again: the
     * reports go out of the ledger, and waiting research that has not yet run in the field's mode loses the field. A run that already ran
     * in the field's mode keeps it. What it tells the owner; nothing once it is done.
     */
    static List<String> takeBackWordRule(LibraryStore store) throws IOException {
        int reports = 0, waiting = 0;
        for (Row r : rows(store)) if ("backfill-rule".equals(r.how()) && r.job().isEmpty()) { forget(store, r.investigation(), r.field()); reports++; }
        Jobs jobs = new Jobs(store, j -> "");
        for (ObjectNode j : jobs.active()) if ("backfill-rule".equals(j.path("args").path("field_how").asText()) && jobs.unbackfillField(j.path("job_id").asText())) waiting++;
        List<String> out = new ArrayList<>();
        if (reports > 0) out.add(reports + (reports == 1 ? " earlier research run was" : " earlier research runs were") + " taken back out of a field's runs: an earlier build had recorded them by the words of their titles, and words do not decide a run's field. Their claims are ordinary claims again.");
        if (waiting > 0) out.add(waiting + (waiting == 1 ? " waiting research run" : " waiting research runs") + " that an earlier build had put into a field's mode by the words of the question will run as ordinary research. To run one in a field's mode, send the question again with --field <name>.");
        return out;
    }

    /** What the nightly research wrote on an open question it took: the date, then the report the run filed. */
    static final Pattern EXPLORED_BY_RUN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} (I-\\d+[A-Za-z0-9-]*)$");

    /**
     * {investigation, field} for each open question one of {@code modules}' own commands filed (the field's mark, or the bracket an older
     * version wrote for it: {@link Profile#ownsLine}) that the nightly research took before runs were recorded: the run that researched it
     * filed the report its line names. A line dropped, answered or sent elsewhere names no report and gives nothing.
     */
    static List<String[]> fromExploredQuestions(LibraryStore store, List<Profile> modules) throws IOException {
        List<String[]> out = new ArrayList<>();
        if (modules.isEmpty()) return out;
        for (Frontier.Line l : Frontier.read(store)) {
            if (l.open()) continue;
            Matcher run = EXPLORED_BY_RUN.matcher(l.explored().strip());
            if (!run.matches()) continue;
            String mark = marked(l.kind());
            for (Profile p : modules) if (p.name().equals(mark) || p.ownsLine(l.kind())) { out.add(new String[]{run.group(1), p.name()}); break; }
        }
        return out;
    }

    static final Pattern RULES_JOIN = Pattern.compile("^field: (.+?) — its rules join this run");
    static final Pattern RUN_FILED = Pattern.compile("^research (J-\\d+)$");
    static final Pattern FILED_AS = Pattern.compile("→ (I-\\d+[A-Za-z0-9-]*)");
    /** The line every run logs once, when its plan is made: "plan: 4 sub-question(s), 2 worker(s), …". */
    static final Pattern RUN_PLANNED = Pattern.compile("^plan: \\d+ sub-question\\(s\\)(?:,|$)");
    /** The first line of a run that was asked for a format; it comes before the run's plan. */
    static final Pattern RUN_FORMAT = Pattern.compile("^format asked for: ");
    /** The line a run logs when a person stops it; a stopped run files no report. */
    static final Pattern RUN_STOPPED = Pattern.compile("^stopped: ");

    /**
     * {investigation, job, field} for each run whose rules the research log says came from one of {@code fields}: the runner's "field: …
     * its rules join this run", then the job's line that names the report it filed. The runner's lines carry no job, so the two are one
     * run's only when that job was the only research running from the one line to the other, by the times in the job ledger
     * ({@link #alone}); with two job workers, a field line written while another run ran gives its field to nothing. A run that was
     * stopped, or that ended without a report (a second run's plan comes before a report), gives its field to nothing too: the report
     * after it is another run's.
     */
    static List<String[]> fromCrewsLog(LibraryStore store, Set<String> fields) throws IOException {
        List<String[]> out = new ArrayList<>();
        Path log = store.root().resolve("catalog").resolve("crews.log");
        if (!Files.exists(log)) return out;
        Map<String, long[]> ran = researchTimes(store);
        List<String> pending = new ArrayList<>();
        String joinedAt = "";
        boolean planned = false;
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", 4);
            if (p.length < 4) continue;
            Matcher joined = RULES_JOIN.matcher(p[3]);
            if (p[1].equals("research") && joined.find()) {
                pending.clear(); planned = false; joinedAt = p[0];
                for (String f : joined.group(1).split(",\\s*")) if (fields.contains(f.strip())) pending.add(f.strip());
                continue;
            }
            if (p[1].equals("research") && !pending.isEmpty()) {
                if (RUN_STOPPED.matcher(p[3]).find()) { pending.clear(); continue; }
                if (RUN_PLANNED.matcher(p[3]).find()) { if (planned) pending.clear(); else planned = true; continue; }
                if (RUN_FORMAT.matcher(p[3]).find() && planned) { pending.clear(); continue; }
            }
            Matcher run = RUN_FILED.matcher(p[1]);
            if (run.matches()) {
                Matcher inv = FILED_AS.matcher(p[3]);
                if (inv.find() && !pending.isEmpty() && alone(ran, run.group(1), joinedAt, p[0])) for (String f : pending) out.add(new String[]{inv.group(1), run.group(1), f});
                pending.clear();
            }
        }
        return out;
    }

    /** Each research job that started, by the job ledger: {started, ended} in epoch milliseconds, ended far ahead for one still running. */
    static Map<String, long[]> researchTimes(LibraryStore store) throws IOException {
        Map<String, long[]> out = new LinkedHashMap<>();
        Jobs jobs = new Jobs(store, j -> "");
        List<ObjectNode> all = new ArrayList<>(jobs.recent(Integer.MAX_VALUE, null));
        all.addAll(jobs.active());
        for (ObjectNode j : all) {
            if (!"research".equals(j.path("kind").asText()) || !j.hasNonNull("started_at")) continue;
            long start = at(j.path("started_at").asText()), end = j.hasNonNull("ended_at") ? at(j.path("ended_at").asText()) : Long.MAX_VALUE;
            if (start >= 0 && end >= 0) out.put(j.path("job_id").asText(), new long[]{start, end});
        }
        return out;
    }

    /**
     * Whether {@code job} was the only research job running from the runner's field line (at {@code from}) to the job's own line naming
     * its report (at {@code to}): it had started by the field line, and no other research job ran at any moment in between. False when a
     * time cannot be read.
     */
    static boolean alone(Map<String, long[]> ran, String job, String from, String to) {
        long a = at(from), b = at(to);
        if (a < 0 || b < 0) return false;
        long[] own = ran.get(job);
        if (own != null && own[0] > a) return false;
        for (Map.Entry<String, long[]> e : ran.entrySet()) if (!e.getKey().equals(job) && e.getValue()[0] < b && e.getValue()[1] > a) return false;
        return true;
    }

    /** A time the log or the job ledger wrote, in epoch milliseconds; -1 when it is not one. */
    private static long at(String instant) {
        try { return Instant.parse(instant.strip()).toEpochMilli(); } catch (RuntimeException e) { return -1; }
    }

    // ---- the open questions a field filed ----

    /** The mark a field's own open question carries inside its bracket: {@code [person … ·field:<name>]}. */
    public static String mark(String field) { return " ·field:" + field.toLowerCase(Locale.ROOT); }

    static final Pattern MARK = Pattern.compile("\\s*·field:([a-z0-9-]+)");

    /** The field mark in a bracket; null when it has none. */
    public static String marked(String kind) {
        Matcher m = MARK.matcher(kind == null ? "" : kind);
        return m.find() ? m.group(1) : null;
    }

    /** A bracket without its field mark, as a person reads it. */
    public static String unmarked(String kind) { return kind == null ? "" : MARK.matcher(kind).replaceAll("").strip(); }

    /**
     * The field an open question belongs to: its mark, else the field of the run that left it open, else a field that owns the words
     * an older version wrote in its bracket; "" for an ordinary question.
     */
    public static String ofLine(Frontier.Line l, Map<String, Set<String>> runs) {
        String m = marked(l.kind());
        if (m != null) return m;
        String origin = l.origin();
        if (origin != null) for (String f : runs.getOrDefault(origin, Set.of())) { Profile p = Profiles.named(f); if (p != null && p.joinsOnlyWhenAsked()) return p.name(); }
        for (Profile p : Profiles.known()) if (p.ownsLine(l.kind())) return p.name();
        return "";
    }

    public static String ofLine(LibraryStore store, Frontier.Line l) {
        try { return ofLine(l, runs(store)); } catch (IOException e) { return ofLine(l, Map.of()); }
    }

    // ---- telling the person a field is there ----

    /** A question looks like one of {@code field}'s: {@code offer} is the profile's own sentence of what its mode does differently. */
    public record Suggestion(String field, String offer) {
        /** The question a person who can answer is asked, with no as the answer given by Enter. */
        public String question() { return offer + " Use " + field + " mode for this question? (y/N)"; }
        /** The line for the command line when nobody can answer: the command that asks for the mode, ready to paste. */
        public String command(String question) {
            return offer + " To research it that way, send it again with --" + field + ": researchzosho research ask " + quoted(question) + " --" + field;
        }
        /** What a program is told: send the same question again with the field named. */
        public String how() { return "send the same question again with field: \"" + field + "\""; }
    }

    /** Tests switch suggestions off, to compare a library with a field on and one with it off, line for line. */
    private static volatile boolean suggesting = true;
    static void suggesting(boolean on) { suggesting = on; }

    /**
     * The field an enabled profile that joins only when asked would suggest for this question; null when none does. Nothing is recorded.
     */
    public static Suggestion suggest(LibraryStore store, String question) {
        if (!suggesting || question == null || question.isBlank()) return null;
        for (Profile p : enabled(store)) {
            if (!p.joinsOnlyWhenAsked()) continue;
            try { if (p.suggests(store, question)) return new Suggestion(p.name(), p.offer()); }
            catch (RuntimeException ignored) { }
        }
        return null;
    }

    /**
     * The field whose mode the words ask for by name ("in genealogy mode", "genealogy-mode"): an enabled field that joins only when asked.
     * The person's own choice, read from their words, which the person is still asked to confirm. Null when the words name no such mode.
     */
    public static Suggestion modeAskedFor(LibraryStore store, String words) {
        if (words == null || words.isBlank()) return null;
        String w = words.toLowerCase(Locale.ROOT);
        for (Profile p : enabled(store)) {
            if (!p.joinsOnlyWhenAsked()) continue;
            Pattern mode = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(p.name().toLowerCase(Locale.ROOT)) + "[\\s-]*(?:mode|モード)(?![\\p{L}\\p{N}])");
            if (mode.matcher(w).find()) return new Suggestion(p.name(), p.offer());
        }
        return null;
    }

    /**
     * {@link #suggest}, once per question: a question the person was already told or asked about is not told again. Records that it was
     * told, and where ({@code cli}, {@code program}, {@code web}).
     */
    public static Suggestion tell(LibraryStore store, String question, String where) {
        Suggestion s = suggest(store, question);
        if (s == null || store == null || seen(store, question, s.field())) return null;
        note(store, question, s.field(), "told", "", where);
        return s;
    }

    /**
     * The same where the suggestion is only written into a log nobody is shown (the nightly research's): once per question there, and
     * never counted as the person having been told, so where they ask the question themselves they are still told or asked.
     */
    public static Suggestion logged(LibraryStore store, String question, String where) {
        Suggestion s = suggest(store, question);
        if (s == null || store == null || rowFor(store, question, s.field(), p -> p[1].equals("logged") && p[4].equals(where))) return null;
        note(store, question, s.field(), "logged", "", where);
        return s;
    }

    /** What the person answered the last time they were asked about this question and this field: "yes", "no", or null when never. */
    public static String answered(LibraryStore store, String question, String field) {
        if (store == null || !Files.exists(suggestionsFile(store))) return null;
        String q = key(question), said = null;
        try {
            for (String line : Files.readAllLines(suggestionsFile(store), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", -1);
                if (p.length >= 6 && p[1].equals("asked") && p[2].equals(field) && p[5].equals(q) && (p[3].equals("yes") || p[3].equals("no"))) said = p[3];
            }
        } catch (IOException ignored) { }
        return said;
    }

    /** Whether the person was told or asked about this question and this field before; a line only the nightly research's log carried does not count. */
    public static boolean seen(LibraryStore store, String question, String field) {
        return rowFor(store, question, field, p -> !p[1].equals("logged") && !p[4].equals("explorer"));
    }

    /** Whether suggestions.tsv has a line for this question and this field that {@code which} takes. */
    private static boolean rowFor(LibraryStore store, String question, String field, Predicate<String[]> which) {
        if (store == null || !Files.exists(suggestionsFile(store))) return false;
        String q = key(question);
        try {
            for (String line : Files.readAllLines(suggestionsFile(store), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", -1);
                if (p.length >= 6 && p[2].equals(field) && p[5].equals(q) && which.test(p)) return true;
            }
        } catch (IOException ignored) { }
        return false;
    }

    /** One line in suggestions.tsv: the date, told or asked, the field, the answer (yes, no, or none), where, and the question. */
    public static void note(LibraryStore store, String question, String field, String kind, String answer, String where) {
        if (store == null) return;
        try {
            store.locked("suggestions", () -> {
                Files.createDirectories(suggestionsFile(store).getParent());
                Files.writeString(suggestionsFile(store), LocalDate.now() + "\t" + kind + "\t" + field + "\t" + clean(answer) + "\t" + clean(where) + "\t" + key(question) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return null;
            });
        } catch (IOException ignored) { }
    }

    /** A question as it is compared: folded, its spaces collapsed. */
    static String key(String question) { return clean(Vocabulary.norm(question == null ? "" : question)); }

    /** The text in single quotes for a shell, a quote inside it written as '\'' so the command can be pasted as it is. */
    public static String quoted(String s) { return "'" + (s == null ? "" : s).replace("'", "'\\''") + "'"; }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").strip(); }
}
