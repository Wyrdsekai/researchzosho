package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
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
    static final int WORKERS = org.researchzosho.Config.getInt("RESEARCHZOSHO_JOB_WORKERS", 1);

    /** Drives, comma-separated; worker i takes drive i mod n, so more drives are more research per night. */
    public static List<String> drives(String fallback) {
        String v = org.researchzosho.Config.get("RESEARCHZOSHO_JOB_DRIVES", "");
        List<String> out = new ArrayList<>();
        for (String d : v.split(",")) if (!d.isBlank()) out.add(d.strip());
        if (out.isEmpty()) out.add(fallback == null ? "" : fallback);
        return out;
    }

    private final LibraryStore store;
    private final java.util.concurrent.LinkedBlockingDeque<String> queue = new java.util.concurrent.LinkedBlockingDeque<>();
    private final Runner runner;
    private final List<String> drives;
    private final int workers;
    private final java.util.Set<String> running = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final List<Thread> threads = new ArrayList<>();

    /** A read-only or single-drive ledger (the CLI, the chat, tests): one worker, no drive probe. */
    public Jobs(LibraryStore store, Function<ObjectNode, String> runner) {
        this(store, (j, d) -> runner.apply(j), List.of(""), 1);
    }

    public Jobs(LibraryStore store, Runner runner, List<String> drives, int workers) {
        this.store = store; this.runner = runner;
        this.drives = drives == null || drives.isEmpty() ? List.of("") : List.copyOf(drives);
        this.workers = Math.max(1, workers);
    }

    public List<String> drives() { return drives; }
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

    /** A research ask ended having spent {@code turns}: the day's accounting, by patron. Visible, never a refusal. */
    public synchronized void recordTurns(String patronDid, int turns) throws IOException {
        Files.createDirectories(budgetDir());
        Files.writeString(budgetDir().resolve(LocalDate.now(ZoneId.systemDefault()) + ".tsv"),
                (patronDid == null ? "" : patronDid) + "\t" + Math.max(0, turns) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Today's turns by patron, for the person to see who spent what. */
    public java.util.Map<String, Integer> turnsTodayByPatron() throws IOException {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
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
        if (Files.exists(a)) return read(a);
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
            for (Path p : s.sorted().toList()) if (p.toString().endsWith(".json")) out.add(read(p));
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

    private synchronized void writeActive(ObjectNode j) throws IOException {
        Files.createDirectories(activeDir());
        Path target = activeDir().resolve(j.get("job_id").asText() + ".json");
        Path tmp = activeDir().resolve("." + j.get("job_id").asText() + ".json.part");
        Files.writeString(tmp, M.writerWithDefaultPrettyPrinter().writeValueAsString(j), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);   // never a half-written job file
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
            String drive = drives.get(i % drives.size());
            final int index = i;
            Thread t = new Thread(() -> loop(drive, index), "librarian-jobs-" + i);
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
    public java.util.Set<String> running() { return java.util.Set.copyOf(running); }
    public int queued() { return queue.size(); }

    public void pickUp() { pickUpFiled(); }

    private synchronized void pickUpFiled() {
        try {
            migrate();
            for (ObjectNode j : active()) {
                String id = j.path("job_id").asText();
                if ("queued".equals(j.path("state").asText()) && !queue.contains(id) && !running.contains(id)) queue.add(id);
            }
        } catch (IOException ignored) { }
    }

    /**
     * Worker {@code index}'s loop. The CREWS run on worker 0 only — one pass over one library at a
     * time by construction (two queued nights once ran together on two workers and raced on the
     * index lock and the backup zip, 2026-09-06); research asks use every worker. A research ask
     * needs a live drive: a worker whose drive is down puts it back and waits, while the crews run
     * regardless — serials, refresh and the backup need no model, and the model steps skip
     * themselves (two nights' crews had sat queued behind a released GPU with no backup taken).
     */
    private void loop(String drive, int index) {
        while (!Thread.currentThread().isInterrupted()) {
            String id;
            try {
                id = queue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                if (id == null) { pickUpFiled(); continue; }
            } catch (InterruptedException e) { return; }
            long wait = 0;
            try {
                ObjectNode j;
                synchronized (this) {   // two workers must not take the same filed job
                    j = get(id);
                    if (j == null || !"queued".equals(j.path("state").asText()) || running.contains(id)) continue;
                    String kind = j.path("kind").asText();
                    boolean defer = false;
                    if ("crews".equals(kind) && index != 0) { defer = true; wait = 2_000; }
                    if ("research".equals(kind) && !drive.isEmpty() && !Crews.driveAnswers(drive)) { defer = true; wait = 30_000; }
                    boolean held = "research".equals(kind) && (ResearchSettings.paused() || !ResearchSettings.openNow());
                    if (held) { defer = true; wait = 30_000; }   // the person's pause or window: the ask waits, the crews still run
                    if (defer) {
                        queue.add(id);                          // back to the tail; whatever else is waiting runs first
                        if (queue.size() > 1) wait = held ? 5_000 : 0;   // something else to do: no wait — unless it is all held asks
                        j = null;
                    }
                    if (j != null) {
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
                catch (Throwable t) { result = "job error: " + t; error = true; }
                // the bookkeeping must never depend on the file still being where it was: another
                // process may have moved it (a client migrating the layout under a running daemon,
                // 2026-09-03 — the worker thread died on the null and took the queue with it)
                ObjectNode after = get(id);
                if (after != null) j = after;
                j.put("state", error ? "failed" : "done"); j.put("result", result == null ? "" : result);
                j.put("is_error", error); j.put("ended_at", Instant.now().toString());
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
        var m = java.util.regex.Pattern.compile("^investigation (I-\\d+-[a-z0-9-]+)").matcher(r);
        return m.find() ? m.group(1) : null;
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
        JsonNode q = j.path("args").get("question");
        if (q != null) r.put("question", q.asText());
        String st = j.path("state").asText();
        if (!"queued".equals(st) && !"running".equals(st)) {
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
        return patron.anonymous() || patron.person() || owner.isEmpty() || patron.did().equals(owner);
    }
}
