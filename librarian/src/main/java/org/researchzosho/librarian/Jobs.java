package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.function.Supplier;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.researchzosho.Config;
import org.researchzosho.tools.ContentPolicy;
/**
 * The persisted job ledger and the ONE model worker.
 *
 * <p>Shaped so that nothing done per prompt or per request grows with the ledger (the operator,
 * 2026-09-03: "what if there are a billion of them"):
 * <pre>
 *   catalog/jobs/active/&lt;id&gt;.json        queued + running — small, scanned freely
 *   catalog/jobs/done/YYYY-MM/&lt;id&gt;.json   finished — listed newest-first, paged
 *   catalog/jobs/landed/&lt;id&gt;              markers: finished and not yet acknowledged to the person
 *   catalog/jobs/budget/YYYY-MM-DD.tsv     patron \t turns, appended when a research ask ends — the day's accounting (no cap)
 * </pre>
 * A daemon restart re-queues queued jobs and re-queues a job caught running once (marked
 * restarted), failing it the second time. Model jobs run one at a time.
 */
public final class Jobs {

    private static final ObjectMapper M = new ObjectMapper();

    /** Runs one job on one drive; returns the result text; throws on failure. */
    public interface Runner { String run(ObjectNode job, String driveUrl) throws Exception; }

    /** How many model jobs run at once — the drive's parallel slots minus what interactive chat needs. */
    static final int WORKERS = Config.getInt("RESEARCHZOSHO_JOB_WORKERS", 1);

    /** Drives, comma-separated; worker i takes drive i mod n, so more drives are more research per night. */
    public static List<String> drives(String fallback) {
        String v = Config.get("RESEARCHZOSHO_JOB_DRIVES", "");
        List<String> out = new ArrayList<>();
        for (String d : v.split(",")) if (!d.isBlank()) out.add(d.strip());
        if (out.isEmpty()) out.add(fallback == null ? "" : fallback);
        return out;
    }

    private final LibraryStore store;
    private final LinkedBlockingDeque<String> queue = new LinkedBlockingDeque<>();
    private final Runner runner;
    /** The drives, read again for every job: `model use` switches the library's model while the service runs. */
    private final Supplier<List<String>> drives;
    private final int workers;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final List<Thread> threads = new ArrayList<>();

    /** A read-only or single-drive ledger (the CLI, the chat, tests): one worker, no drive probe. */
    public Jobs(LibraryStore store, Function<ObjectNode, String> runner) {
        this(store, (j, d) -> runner.apply(j), List.of(""), 1);
    }

    public Jobs(LibraryStore store, Runner runner, List<String> drives, int workers) {
        this(store, runner, fixed(drives), workers);
    }

    /** {@code drives} is asked at the start of every job, so a job takes the model the library is set to when it starts. */
    public Jobs(LibraryStore store, Runner runner, Supplier<List<String>> drives, int workers) {
        this.store = store; this.runner = runner;
        this.drives = drives;
        this.workers = Math.max(1, workers);
    }

    private static Supplier<List<String>> fixed(List<String> drives) {
        List<String> d = drives == null || drives.isEmpty() ? List.of("") : List.copyOf(drives);
        return () -> d;
    }

    public List<String> drives() {
        List<String> d = drives.get();
        return d == null || d.isEmpty() ? List.of("") : List.copyOf(d);
    }

    /** The drive worker {@code index} takes now: i mod the number of drives. */
    String driveOf(int index) { List<String> d = drives(); return d.get(index % d.size()); }
    public int workers() { return workers; }

    public Path dir() { return store.root().resolve("catalog").resolve("jobs"); }
    Path activeDir() { return dir().resolve("active"); }
    Path doneDir() { return dir().resolve("done"); }
    Path landedDir() { return dir().resolve("landed"); }
    Path budgetDir() { return dir().resolve("budget"); }

    // ---- filing ----

    public synchronized String submit(String kind, String patronDid, ObjectNode args) throws IOException {
        String id = store.nextJobId();
        ObjectNode j = M.createObjectNode();
        j.put("job_id", id); j.put("kind", kind); j.put("patron", patronDid == null ? "" : patronDid);
        j.set("args", args); j.put("state", "queued"); j.put("queued_at", Instant.now().toString());
        j.put("restarted", 0);
        writeActive(j);
        if (args != null && args.path("quick").asBoolean(false)) queue.addFirst(id);   // "look it up now": ahead of whatever waits for the night
        else queue.add(id);
        return id;
    }

    /**
     * A job filed waiting for the person's answer to a question the chat put to them (whether to research a question in a field's mode):
     * state {@code offered}, never queued, until {@link #release} or until {@code untilMillis} passes, when it starts as it was filed.
     */
    public synchronized String submitOffered(String kind, String patronDid, ObjectNode args, String field, long untilMillis) throws IOException {
        return submitOffered(kind, patronDid, args, field, untilMillis, false);
    }

    /**
     * The same; {@code onlyOnYes}: the job runs only on a yes, and anything else (a no, no answer in time) stops it before it starts. A
     * second run of a question that already runs is offered this way.
     */
    public synchronized String submitOffered(String kind, String patronDid, ObjectNode args, String field, long untilMillis, boolean onlyOnYes) throws IOException {
        return submitOffered(kind, patronDid, args, new Offer(field, onlyOnYes, List.of()), untilMillis);
    }

    /**
     * What a job waits for the person's answer about: a field's mode ({@code field}, null for none), and what the library leaves out by
     * default ({@code content}, the categories the offer would let in; empty for none). Each is its own question, asked one after the
     * other, and each is answered on its own; the job starts when every question it waits on is answered, or when the wait ends.
     */
    public record Offer(String field, boolean onlyOnYes, List<String> content, boolean help) {
        public Offer { content = content == null ? List.of() : List.copyOf(content); }
        public Offer(String field, boolean onlyOnYes, List<String> content) { this(field, onlyOnYes, content, false); }
    }

    /** A job filed waiting for the person's answers to {@code offer}'s questions, until {@link #release}, {@link #releaseContent} or {@code untilMillis}. */
    public synchronized String submitOffered(String kind, String patronDid, ObjectNode args, Offer offer, long untilMillis) throws IOException {
        String id = store.nextJobId();
        ObjectNode j = M.createObjectNode();
        j.put("job_id", id); j.put("kind", kind); j.put("patron", patronDid == null ? "" : patronDid);
        j.set("args", args); j.put("state", OFFERED); j.put("queued_at", Instant.now().toString());
        if (offer.field() != null && !offer.field().isBlank()) j.put("offered", offer.field());
        if (!offer.content().isEmpty()) { ArrayNode c = j.putArray("offered_content"); offer.content().forEach(c::add); }
        if (offer.help()) j.put("offered_help", true);   // the question whether to research it at all, after the help was shown: no, or no answer, starts nothing
        j.put("offer_until", Instant.ofEpochMilli(untilMillis).toString());
        if (offer.onlyOnYes()) j.put("on_no", "stop");
        j.put("restarted", 0);
        writeActive(j);
        return id;
    }

    /** Whether the question about a field's mode still waits for the person's answer. */
    static boolean fieldWaits(JsonNode j) { return j.hasNonNull("offered") && !j.has("answered"); }

    /** Whether the question about letting in what the library leaves out still waits for the person's answer. */
    static boolean contentWaits(JsonNode j) { return j.path("offered_content").isArray() && !j.path("offered_content").isEmpty() && !j.has("content_answered"); }

    /** Whether the question whether to research it at all, asked after the help was shown, still waits for the person's answer. */
    static boolean helpWaits(JsonNode j) { return j.path("offered_help").asBoolean(false) && !j.has("help_answered"); }

    /** Whether any question still waits on this job. */
    static boolean anyWaits(JsonNode j) { return helpWaits(j) || fieldWaits(j) || contentWaits(j); }

    /** The job ends before it starts, for {@code why}; nobody needs telling beyond the result. */
    private void stopBeforeStart(ObjectNode j, String why) throws IOException {
        queue.remove(j.path("job_id").asText());
        j.put("state", "stopped"); j.remove("offer_until"); j.put("result", why); j.put("is_error", false);
        j.put("ended_at", Instant.now().toString()); j.put("acknowledged", true);
        finish(j);
    }

    /** A run that failed for a reason its runner states in plain words: the statement is the job's result, as it is. */
    public static final class Failure extends RuntimeException {
        public Failure(String statement) { super(statement); }
    }

    /** What a job the person said no to, after the help was shown, says as its result. */
    static final String NOT_RESEARCHED = "not started: the person said no to researching this question after the library showed where to find help";

    /**
     * The person answered whether to research a question that reads as them asking about harming themselves, after the library showed
     * where to find help: a yes starts it as ordinary research (its {@code allow} records the yes), anything else stops it before it starts.
     */
    public boolean releaseHelp(String id, boolean yes) throws IOException {
        return store.locked("job-" + id, () -> {
            synchronized (this) {
                ObjectNode j = get(id);
                if (j == null || !OFFERED.equals(j.path("state").asText()) || !helpWaits(j)) return false;
                if (!yes) { j.put("help_answered", "no"); stopBeforeStart(j, NOT_RESEARCHED); return true; }
                ObjectNode args = (ObjectNode) j.get("args");
                ArrayNode allow = args.path("allow").isArray() ? (ArrayNode) args.get("allow") : args.putArray("allow");
                boolean has = false; for (JsonNode a : allow) has |= a.asText().equals(ContentPolicy.SELF_HARM);
                if (!has) allow.add(ContentPolicy.SELF_HARM);
                if (!args.has("allow_how")) args.put("allow_how", "chat-yes");
                j.put("help_answered", "yes");
                if (anyWaits(j)) { writeActive(j); return true; }
                queueAnswered(j);
                return true;
            }
        });
    }

    /** Every question answered: the job is queued. */
    private void queueAnswered(ObjectNode j) throws IOException {
        String id = j.path("job_id").asText();
        j.put("state", "queued"); j.remove("offer_until");
        writeActive(j);
        if (!queue.contains(id)) queue.add(id);
    }

    /**
     * The person answered the question about letting in what the library leaves out: on a yes, the offered categories go into the job's
     * {@code allow}, never into its field; on anything else, nothing is let in. The job starts once no other question waits on it. False when
     * the question was not waiting (the wait had ended, or it was answered already).
     */
    public boolean releaseContent(String id, boolean yes) throws IOException {
        return store.locked("job-" + id, () -> {
            synchronized (this) {
                ObjectNode j = get(id);
                if (j == null || !OFFERED.equals(j.path("state").asText()) || !contentWaits(j)) return false;
                if (yes) {
                    ObjectNode args = (ObjectNode) j.get("args");
                    ArrayNode allow = args.path("allow").isArray() ? (ArrayNode) args.get("allow") : args.putArray("allow");
                    for (JsonNode c : j.path("offered_content")) { boolean has = false; for (JsonNode a : allow) has |= a.asText().equals(c.asText()); if (!has) allow.add(c.asText()); }
                    args.put("allow_how", "chat-yes");
                }
                j.put("content_answered", yes ? "yes" : "no");
                if (anyWaits(j)) { writeActive(j); return true; }
                queueAnswered(j);
                return true;
            }
        });
    }

    /** An offered job that runs only on a yes, and the answer was not yes: it ends before it starts, and nobody needs telling. */
    private boolean stopUnwanted(ObjectNode j, String why) throws IOException {
        if (!"stop".equals(j.path("on_no").asText())) return false;
        queue.remove(j.path("job_id").asText());
        j.put("state", "stopped"); j.remove("offer_until"); j.put("result", why); j.put("is_error", false);
        j.put("ended_at", Instant.now().toString()); j.put("acknowledged", true);
        finish(j);
        return true;
    }

    /** The state of a job that waits for the person's answer. */
    public static final String OFFERED = "offered";

    /**
     * The person answered: an offered job is queued, with {@code field} added to its arguments when they said yes (null: as it was
     * filed). Returns false when the job was not waiting any more, because the answer came after the wait ended and it started as it
     * was filed, or it was stopped. Two processes may race here (the chat's answer and the service's end of the wait): the job's own lock
     * decides, and exactly one of them moves it.
     */
    public boolean release(String id, String field, String how) throws IOException {
        return store.locked("job-" + id, () -> {
            synchronized (this) {
                ObjectNode j = get(id);
                if (j == null || !OFFERED.equals(j.path("state").asText()) || !fieldWaits(j)) return false;
                if ((field == null || field.isBlank()) && stopUnwanted(j, "not started: the person said no to a second run of this question")) return true;
                if (field != null && !field.isBlank()) { ((ObjectNode) j.get("args")).put("field", field); ((ObjectNode) j.get("args")).put("field_how", how); }
                j.put("answered", field != null && !field.isBlank() ? "yes" : "no");
                if (anyWaits(j)) { writeActive(j); return true; }   // the next question waits for its own answer
                queueAnswered(j);
                return true;
            }
        });
    }

    /**
     * A research job a field's own command filed in an older version and that did not finish (queued, or running when it stopped): run in
     * {@code field}'s mode. Under the job's own lock; false when the job has a field already or is not waiting or running.
     */
    public boolean backfillField(String id, String field, String how) throws IOException {
        return store.locked("job-" + id, () -> {
            synchronized (this) {
                ObjectNode j = get(id);
                if (j == null || !j.path("args").isObject() || j.path("args").has("field")) return false;
                String st = j.path("state").asText();
                if (!"queued".equals(st) && !"running".equals(st)) return false;
                ((ObjectNode) j.get("args")).put("field", field); ((ObjectNode) j.get("args")).put("field_how", how);
                writeActive(j);
                return true;
            }
        });
    }

    /** A waiting or running research job goes back to ordinary research: its field and how the field was chosen are taken off. False when it had none. */
    public boolean unbackfillField(String id) throws IOException {
        return store.locked("job-" + id, () -> {
            synchronized (this) {
                ObjectNode j = get(id);
                if (j == null || !j.path("args").isObject() || !j.path("args").has("field")) return false;
                String st = j.path("state").asText();
                if (!"queued".equals(st) && !"running".equals(st)) return false;
                ((ObjectNode) j.get("args")).remove("field"); ((ObjectNode) j.get("args")).remove("field_how");
                writeActive(j);
                return true;
            }
        });
    }

    /** An offered job whose wait has ended starts as it was filed, under the job's own lock; true when this call moved it. */
    boolean expire(ObjectNode j) throws IOException {
        String id = j.path("job_id").asText();
        return store.locked("job-" + id, () -> {
            synchronized (this) {
                ObjectNode now = get(id);
                if (now == null || !OFFERED.equals(now.path("state").asText())) return false;
                if (stopUnwanted(now, "not started: no answer came to the question whether to start a second run of this question")) return true;
                if (helpWaits(now)) { now.put("help_answered", "none"); stopBeforeStart(now, "not started: no answer came to the question whether to research this question, which reads as a person asking about harming themselves"); return true; }
                if (fieldWaits(now)) now.put("answered", "none");
                if (contentWaits(now)) now.put("content_answered", "none");   // no answer is no: nothing is let in
                queueAnswered(now);
                return true;
            }
        });
    }

    /** Whether an offered job's wait has ended. */
    static boolean offerEnded(ObjectNode j, long nowMillis) {
        try { return Instant.parse(j.path("offer_until").asText("")).toEpochMilli() <= nowMillis; } catch (Exception e) { return true; }
    }

    /** A research ask ended having spent {@code turns}: the day's accounting, by patron. Visible, never a refusal. */
    public synchronized void recordTurns(String patronDid, int turns) throws IOException {
        Files.createDirectories(budgetDir());
        Files.writeString(budgetDir().resolve(LocalDate.now(ZoneId.systemDefault()) + ".tsv"),
                (patronDid == null ? "" : patronDid) + "\t" + Math.max(0, turns) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Today's turns by patron, for the person to see who spent what. */
    public Map<String, Integer> turnsTodayByPatron() throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        Path f = budgetDir().resolve(LocalDate.now(ZoneId.systemDefault()) + ".tsv");
        if (!Files.exists(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", 2);
            if (p.length == 2) { try { out.merge(p[0].isEmpty() ? "anonymous" : p[0], Integer.parseInt(p[1].strip()), Integer::sum); } catch (NumberFormatException ignored) { } }
        }
        return out;
    }

    /** Turns this patron spent today — one small file, never the ledger. */
    public int turnsToday(String patronDid) throws IOException {
        Path f = budgetDir().resolve(LocalDate.now(ZoneId.systemDefault()) + ".tsv");
        if (!Files.exists(f)) return 0;
        int sum = 0;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", 2);
            if (p.length == 2 && p[0].equals(patronDid == null ? "" : patronDid)) {
                try { sum += Integer.parseInt(p[1].strip()); } catch (NumberFormatException ignored) { }
            }
        }
        return sum;
    }

    // ---- reading ----

    /** One job by id: active first, then the month folders newest-first, then a legacy flat file. */
    public ObjectNode get(String id) throws IOException {
        // a daemon on the old layout may have just finished a job into a flat file while a stale
        // copy sits in active/ (seen live 2026-09-03 during the layout change): sort first, one listing
        if (Files.exists(dir().resolve(id + ".json"))) migrate();
        Path a = activeDir().resolve(id + ".json");
        // a worker finishes a job by writing it into its month and then deleting it from active/: a job that finishes between the
        // check and the read is found in its month, where it already is
        if (Files.exists(a)) { try { return read(a); } catch (NoSuchFileException finishedJustNow) { } }
        for (Path month : months()) {
            Path p = month.resolve(id + ".json");
            if (Files.exists(p)) return read(p);
        }
        Path legacy = dir().resolve(id + ".json");
        return Files.exists(legacy) ? read(legacy) : null;
    }

    /** Queued and running jobs (the small set). */
    public List<ObjectNode> active() throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        if (!Files.isDirectory(activeDir())) return out;
        try (var s = Files.list(activeDir())) {
            for (Path p : s.sorted().toList()) {
                if (!p.toString().endsWith(".json")) continue;
                try { out.add(read(p)); }
                catch (NoSuchFileException gone) { }   // the job finished between the listing and the read: it is no longer active, which is the truth
            }
        }
        return out;
    }

    /** Finished jobs, newest first: {@code limit} of them after {@code cursor} (a job id, exclusive; null = the newest). */
    public List<ObjectNode> recent(int limit, String cursor) throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        boolean started = cursor == null || cursor.isBlank();
        for (Path month : months()) {   // newest month first
            List<Path> files;
            try (var s = Files.list(month)) { files = s.filter(p -> p.toString().endsWith(".json")).sorted(Comparator.reverseOrder()).toList(); }
            for (Path p : files) {
                String id = p.getFileName().toString().replace(".json", "");
                if (!started) { if (id.equals(cursor)) started = true; continue; }
                out.add(read(p));
                if (out.size() >= limit) return out;
            }
        }
        return out;
    }

    /** How many finished jobs there are — a count of files, for the "and N more" line. */
    public long finishedCount() throws IOException {
        long n = 0;
        for (Path month : months()) { try (var s = Files.list(month)) { n += s.filter(p -> p.toString().endsWith(".json")).count(); } }
        return n;
    }

    /** Finished, unacknowledged: the markers, oldest first. */
    public List<String> landed() throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(landedDir())) return out;
        try (var s = Files.list(landedDir())) { for (Path p : s.sorted().toList()) out.add(p.getFileName().toString()); }
        return out;
    }

    /** The person has seen it: drop the marker. */
    public synchronized void acknowledge(String id) throws IOException {
        Files.deleteIfExists(landedDir().resolve(id));
        ObjectNode j = get(id);
        if (j != null && !j.path("acknowledged").asBoolean(false)) { j.put("acknowledged", true); writeWhereItIs(j); }
    }

    /** Month folders, newest first. */
    private List<Path> months() throws IOException {
        if (!Files.isDirectory(doneDir())) return List.of();
        try (var s = Files.list(doneDir())) { return s.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList(); }
    }

    /** A job file, complete: a reader can meet a writer's half-written file (two workers, one ledger) — retry briefly. */
    private static ObjectNode read(Path p) throws IOException {
        IOException last = null;
        for (int i = 0; i < 20; i++) {
            try {
                JsonNode n = M.readTree(Files.readString(p, StandardCharsets.UTF_8));
                if (n != null && n.isObject()) return (ObjectNode) n;
            } catch (IOException e) { last = e; }
            try { Thread.sleep(25); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        if (last != null) throw last;
        throw new IOException("job file is not a JSON object: " + p);
    }

    /** The runner's progress (phase, round, workers, turns) onto a running job's record — what a client polls. */
    public synchronized void progress(String id, ObjectNode p) throws IOException {
        Path a = activeDir().resolve(id + ".json");
        if (!Files.exists(a)) return;
        ObjectNode j = read(a);
        if (!"running".equals(j.path("state").asText())) return;
        j.set("progress", p);
        writeActive(j);
    }

    /** The model declined the run, or parts of it ({@link Researcher.Result#declinedView}): onto the running job's record, for every view of it. */
    public synchronized void declined(String id, ObjectNode d) throws IOException {
        if (d == null) return;
        Path a = activeDir().resolve(id + ".json");
        if (!Files.exists(a)) return;
        ObjectNode j = read(a);
        j.set("declined", d);
        writeActive(j);
    }

    private synchronized void writeActive(ObjectNode j) throws IOException {
        Files.createDirectories(activeDir());
        Path target = activeDir().resolve(j.get("job_id").asText() + ".json");
        Path tmp = activeDir().resolve("." + j.get("job_id").asText() + ".json.part");
        Files.writeString(tmp, M.writerWithDefaultPrettyPrinter().writeValueAsString(j), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);   // never a half-written job file
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Move a finished job out of the active set into its month, and raise the landed marker. */
    private synchronized void finish(ObjectNode j) throws IOException {
        String id = j.get("job_id").asText();
        String month = Instant.parse(j.path("ended_at").asText(Instant.now().toString())).atZone(ZoneId.systemDefault()).toLocalDate().toString().substring(0, 7);
        Path target = doneDir().resolve(month);
        Files.createDirectories(target);
        Files.writeString(target.resolve(id + ".json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(j), StandardCharsets.UTF_8);
        Files.deleteIfExists(activeDir().resolve(id + ".json"));
        Files.deleteIfExists(dir().resolve(id + ".json"));
        if ("research".equals(j.path("kind").asText()) && !j.path("acknowledged").asBoolean(false)) {
            Files.createDirectories(landedDir());
            Files.writeString(landedDir().resolve(id), "", StandardCharsets.UTF_8);
        }
    }

    private synchronized void writeWhereItIs(ObjectNode j) throws IOException {
        String id = j.get("job_id").asText();
        Path a = activeDir().resolve(id + ".json");
        if (Files.exists(a)) { writeActive(j); return; }
        for (Path month : months()) {
            Path p = month.resolve(id + ".json");
            if (Files.exists(p)) { Files.writeString(p, M.writerWithDefaultPrettyPrinter().writeValueAsString(j), StandardCharsets.UTF_8); return; }
        }
        finish(j);
    }

    /** Flat files from the first ledger layout: sort them into active/ or done/. Idempotent, cheap when nothing is there. */
    public synchronized void migrate() throws IOException {
        if (!Files.isDirectory(dir())) return;
        List<Path> flat;
        try (var s = Files.list(dir())) { flat = s.filter(p -> p.toString().endsWith(".json")).toList(); }
        for (Path p : flat) {
            ObjectNode j = read(p);
            String st = j.path("state").asText();
            if ("done".equals(st) || "failed".equals(st)) finish(j);
            else { writeActive(j); Files.deleteIfExists(p); }
        }
    }

    // ---- the worker ----

    public synchronized void start() throws IOException {
        migrate();
        for (ObjectNode j : active()) {
            String st = j.path("state").asText();
            if ("queued".equals(st)) {
                queue.add(j.get("job_id").asText());
            } else if ("running".equals(st)) {
                int restarted = j.path("restarted").asInt(0) + 1;
                j.put("restarted", restarted);
                if (restarted > 1) {
                    j.put("state", "failed"); j.put("result", "the daemon was restarted twice while this job ran"); j.put("is_error", true);
                    j.put("ended_at", Instant.now().toString());
                    finish(j);
                } else {
                    j.put("state", "queued");
                    writeActive(j);
                    queue.add(j.get("job_id").asText());
                }
            }
        }
        for (int i = 0; i < workers; i++) {
            final int index = i;
            Thread t = new Thread(() -> loop(index), "librarian-jobs-" + i);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
    }

    public void stop() {
        for (Thread t : threads) t.interrupt();
        for (Thread t : threads) { try { t.join(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
    /** One running job id (the first), or null — kept for callers that think in ones. */
    public String current() { return running.isEmpty() ? null : running.iterator().next(); }
    /** Every running job id. */
    public Set<String> running() { return Set.copyOf(running); }
    public int queued() { return queue.size(); }

    public void pickUp() { pickUpFiled(); }

    /**
     * The marker a stop request leaves for a running job, so a CLI or a page can stop a daemon's run. The run looks at it before every turn
     * and four times a second while it waits for the model, a page or a search: it ends within seconds.
     */
    Path stopMarker(String id) { return activeDir().resolve(id + ".stop"); }

    /** Whether a stop was asked for {@code id}. */
    public boolean stopRequested(String id) { return Files.exists(stopMarker(id)); }

    /**
     * Stop a job. A queued one is finished at once as {@code stopped}; a running one gets the marker and ends within seconds, also in the
     * middle of a request to the model (its worker threads share the check). Returns the state it is in now: stopped, stopping, or null when no such
     * job is active.
     */
    public String stop(String id, String who) throws IOException { Stop s = stopRun(id, who); return s == null ? null : s.state(); }

    /**
     * What a stop did: {@code state} stopped or stopping; {@code kind} research or crews; {@code filed} true when a research run had begun
     * filing its report before the stop came ({@link #beginFiling}), so the report stays.
     */
    public record Stop(String state, String kind, boolean filed) { }

    /** A stop, as {@link #stop}, with what it stops. Null when no such job is active. */
    public Stop stopRun(String id, String who) throws IOException {
        return underFilingLock(id, () -> {
            synchronized (this) {
                ObjectNode j = get(id);
                if (j == null) return null;
                String st = j.path("state").asText(), kind = j.path("kind").asText();
                if ("queued".equals(st) || OFFERED.equals(st)) {
                    queue.remove(id);
                    j.put("state", "stopped"); j.put("result", "stopped by " + who + " before it started"); j.put("is_error", false);
                    j.put("ended_at", Instant.now().toString());
                    finish(j);
                    return new Stop("stopped", kind, false);
                }
                if ("running".equals(st)) {
                    Files.createDirectories(activeDir());
                    Files.writeString(stopMarker(id), who + " " + Instant.now() + "\n", StandardCharsets.UTF_8);
                    return new Stop("stopping", kind, j.hasNonNull("filing_since"));
                }
                return null;
            }
        });
    }

    /**
     * A research run is about to write down what it found. False when a stop was asked for first: then nothing of the run is filed. True
     * otherwise, and the job's record says since when it files, so that a stop that comes after it says the report stays. The filing and
     * a stop take one lock ({@link #underFilingLock}), so that one of them comes first, never both at once.
     */
    public boolean beginFiling(String id) throws IOException {
        return underFilingLock(id, () -> {
            if (stopRequested(id)) return false;
            synchronized (this) {
                Path a = activeDir().resolve(id + ".json");
                if (Files.exists(a)) { ObjectNode j = read(a); j.put("filing_since", Instant.now().toString()); writeActive(j); }
            }
            return true;
        });
    }

    private interface Locked<T> { T call() throws IOException; }

    /** One lock for this library's jobs in this process. */
    private static final Object FILING = new Object();

    /**
     * {@code work} under the job's filing lock: taken in this process first, then across processes as a lock on a file beside the job, so
     * that a stop from the command line and the service's run never cross.
     */
    private <T> T underFilingLock(String id, Locked<T> work) throws IOException {
        synchronized (FILING) {
            Files.createDirectories(activeDir());
            try (FileChannel ch = FileChannel.open(activeDir().resolve(id + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock held = ch.lock()) {
                return work.call();
            }
        }
    }

    private void pickUpFiled() {
        List<ObjectNode> ended = new ArrayList<>();
        synchronized (this) {
            try {
                migrate();
                long now = System.currentTimeMillis();
                for (ObjectNode j : active()) {
                    String id = j.path("job_id").asText();
                    // a job that waited for the person's answer and got none starts as it was filed; one still waiting is left alone
                    if (OFFERED.equals(j.path("state").asText())) { if (offerEnded(j, now)) ended.add(j); continue; }
                    if ("queued".equals(j.path("state").asText()) && !queue.contains(id) && !running.contains(id)) queue.add(id);
                }
            } catch (IOException ignored) { }
        }
        // outside this ledger's lock: the job's own lock comes first, as the answer from the chat takes it (the other order could deadlock)
        for (ObjectNode j : ended) { try { expire(j); } catch (IOException ignored) { } }
    }

    /**
     * Worker {@code index}'s loop. The CREWS run on worker 0 only — one pass over one library at a
     * time by construction (two queued nights once ran together on two workers and raced on the
     * index lock and the backup zip, 2026-09-06); research asks use every worker. A research ask
     * needs a live drive: a worker whose drive is down puts it back and waits, while the crews run
     * regardless — serials, refresh and the backup need no model, and the model steps skip
     * themselves (two nights' crews had sat queued behind a released GPU with no backup taken).
     */
    private void loop(int index) {
        while (!Thread.currentThread().isInterrupted()) {
            String id;
            try {
                id = queue.poll(10, TimeUnit.SECONDS);
                if (id == null) { pickUpFiled(); continue; }
            } catch (InterruptedException e) { return; }
            String drive = driveOf(index);   // the model the library is set to now: a job started after `model use` takes the new one
            long wait = 0;
            try {
                ObjectNode j;
                synchronized (this) {   // two workers must not take the same filed job
                    j = get(id);
                    if (j == null || !"queued".equals(j.path("state").asText()) || running.contains(id)) continue;
                    String kind = j.path("kind").asText();
                    boolean defer = false;
                    if ("crews".equals(kind) && index != 0) { defer = true; wait = 2_000; }
                    if ("research".equals(kind) && !drive.isEmpty() && !Crews.driveAnswers(drive)) {
                        defer = true; wait = 30_000;
                        // say so on the record: a model server that sleeps between uses (llama-swap, Ollama) answers after a
                        // start, and a run that sits a minute after a quiet night should read as waiting, not stuck
                        if (!j.has("waiting")) { j.put("waiting", "no model answers at " + drive + " yet; asked again every 30 s, the run starts the moment it does"); writeActive(j); }
                    }
                    boolean held = "research".equals(kind) && (ResearchSettings.paused() || !ResearchSettings.openNow());
                    if (held) { defer = true; wait = 30_000; }   // the person's pause or window: the ask waits, the crews still run
                    if (defer) {
                        queue.add(id);                          // back to the tail; whatever else is waiting runs first
                        if (queue.size() > 1) wait = held ? 5_000 : 0;   // something else to do: no wait — unless it is all held asks
                        j = null;
                    }
                    if (j != null && stopRequested(id)) {   // stopped while it waited: never starts
                        Files.deleteIfExists(stopMarker(id));
                        j.put("state", "stopped"); j.put("result", "stopped before it started"); j.put("is_error", false); j.put("ended_at", Instant.now().toString());
                        finish(j); j = null; wait = 0;
                        if (queue.isEmpty()) continue;
                    }
                    if (j != null) {
                        j.remove("waiting");
                        j.put("state", "running"); j.put("started_at", Instant.now().toString());
                        if (!drive.isEmpty()) j.put("drive", drive);
                        writeActive(j);
                        running.add(id);
                    }
                }
                if (j == null) {   // deferred: wait OUTSIDE the lock, then poll again
                    if (wait > 0) Thread.sleep(wait);
                    continue;
                }
                String result;
                boolean error = false;
                try { result = runner.run(j, drive); }
                catch (Failure f) { result = f.getMessage(); error = true; }   // the runner's own statement, in plain words
                catch (Throwable t) { result = "job error: " + t; error = true; }
                // the bookkeeping must never depend on the file still being where it was: another
                // process may have moved it (a client migrating the layout under a running daemon,
                // 2026-09-03 — the worker thread died on the null and took the queue with it)
                ObjectNode after = get(id);
                if (after != null) j = after;
                boolean stopped = stopRequested(id);
                Files.deleteIfExists(stopMarker(id));
                Files.deleteIfExists(activeDir().resolve(id + ".lock"));
                j.put("state", stopped ? "stopped" : error ? "failed" : "done"); j.put("result", stopped ? "stopped by the person" + (result == null || result.isBlank() ? "" : " — " + result) : result == null ? "" : result);
                j.put("is_error", error && !stopped); j.put("ended_at", Instant.now().toString());
                finish(j);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                // the ledger could not be written, or worse — the worker survives; the next job still runs
                Crews.log(store, "jobs", "bookkeeping failed for " + id + ": " + e, 0);
            } finally {
                running.remove(id);
            }
        }
    }

    // ---- views ----

    /** The investigation id a done research job produced, or null. */
    public static String investigationOf(ObjectNode j) {
        String r = j.path("result").asText("");
        var m = Pattern.compile("^investigation (I-\\d+-[a-z0-9-]+)").matcher(r);
        return m.find() ? m.group(1) : null;
    }

    /** How long a research run may show no progress before it is said to show none; a test shortens it. RESEARCHZOSHO_STALL_MINUTES, 15 unless set. */
    static volatile long STALL_MS = Math.max(1, Config.getInt("RESEARCHZOSHO_STALL_MINUTES", 15)) * 60_000L;

    /**
     * The time of a running research run's last progress (or its start, before any) when that was {@link #STALL_MS} or more before {@code
     * now}; null when the run shows progress, or is no running research run.
     */
    static String noProgressSince(ObjectNode j, long now) {
        if (!"running".equals(j.path("state").asText()) || !"research".equals(j.path("kind").asText())) return null;
        String last = j.path("progress").path("at").asText(j.path("started_at").asText(""));
        try { return now - Instant.parse(last).toEpochMilli() >= STALL_MS ? last : null; }
        catch (Exception unreadable) { return null; }
    }

    public static ObjectNode view(ObjectNode j) {
        ObjectNode r = M.createObjectNode();
        r.put("job_id", j.path("job_id").asText());
        r.put("kind", j.path("kind").asText());
        r.put("state", j.path("state").asText());
        r.put("patron", j.path("patron").asText());
        r.put("queued_at", j.path("queued_at").asText());
        if (j.hasNonNull("started_at")) r.put("started_at", j.get("started_at").asText());
        if (j.hasNonNull("ended_at")) r.put("ended_at", j.get("ended_at").asText());
        if (j.hasNonNull("drive")) r.put("drive", j.get("drive").asText());
        long start = j.hasNonNull("started_at") ? Instant.parse(j.get("started_at").asText()).toEpochMilli() : Instant.parse(j.path("queued_at").asText()).toEpochMilli();
        long end = j.hasNonNull("ended_at") ? Instant.parse(j.get("ended_at").asText()).toEpochMilli() : System.currentTimeMillis();
        r.put("elapsed_s", Math.max(0, (end - start) / 1000));
        r.put("restarted", j.path("restarted").asInt(0));
        if (j.has("progress") && j.get("progress").isObject()) r.set("progress", j.get("progress"));
        String stalled = noProgressSince(j, System.currentTimeMillis());
        if (stalled != null) r.put("no_progress_since", stalled);
        if (j.hasNonNull("waiting")) r.put("waiting", j.get("waiting").asText());
        if (OFFERED.equals(j.path("state").asText())) {
            List<String> waits = new ArrayList<>();
            if (helpWaits(j)) waits.add("waiting for your answer: the library showed where to find help and asked whether to research this question; with no answer by "
                    + j.path("offer_until").asText() + " it is not started");
            if (fieldWaits(j)) waits.add((waits.isEmpty() ? "waiting for your answer: " : "then ") + ("stop".equals(j.path("on_no").asText())
                ? "the library asked whether to start a second run of this question in " + j.path("offered").asText("another")
                  + " mode; it starts only on a yes, and with no answer by " + j.path("offer_until").asText() + " it is not started"
                : "the library asked whether to research this question in " + j.path("offered").asText("another")
                  + " mode; with no answer it starts as ordinary research at " + j.path("offer_until").asText()));
            if (contentWaits(j)) {
                List<String> offered = new ArrayList<>(); for (JsonNode c : j.path("offered_content")) offered.add(c.asText());
                waits.add((waits.isEmpty() ? "waiting for your answer: " : "then ") + "the library asked whether to let in " + ContentOffer.described(offered)
                        + " for this question's run; with no answer it starts with that material left out at " + j.path("offer_until").asText());
            }
            r.put("waiting", String.join("; ", waits));
        }
        if (j.path("args").hasNonNull("field")) r.put("field", j.path("args").path("field").asText());
        if (j.path("args").path("allow").isArray() && !j.path("args").path("allow").isEmpty()) r.set("allow", j.path("args").path("allow").deepCopy());   // what the run lets in, because the person asked
        if (j.path("declined").isObject()) r.set("declined", j.get("declined"));   // the model declined the run or parts of it: said, never as a failure
        JsonNode q = j.path("args").get("question");
        if (q != null) r.put("question", q.asText());
        String st = j.path("state").asText();
        if (!"queued".equals(st) && !"running".equals(st) && !OFFERED.equals(st)) {
            r.put("result", j.path("result").asText(""));
            r.put("is_error", j.path("is_error").asBoolean(false));
            String inv = investigationOf(j);
            if (inv != null) r.put("investigation", inv);
        }
        return r;
    }

    /** A patron may see its own jobs and the crews'. */
    public static boolean visibleTo(Patrons.Patron patron, ObjectNode j) {
        String owner = j.path("patron").asText();
        // the person, an anonymous caller and someone in the browser see every run; a named program sees its own.
        // The browser used to see only runs filed from the browser: the Runs page said "Nothing is running" while
        // a program's run had been going for half an hour (2026-09-11)
        return patron.anonymous() || patron.person() || patron.web() || owner.isEmpty() || patron.did().equals(owner);
    }
}
