package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Version;
import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.DriveClient;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * A job's work kept as it goes, so that a job a restart, an update or a crash interrupts picks up where it was. Every job kind keeps
 * its work in one folder, {@code catalog/jobs/work/<job>/}:
 * <pre>
 *   artifacts.jsonl   append-only: one line for each thing the job got, the moment it has it — a model's answer, a search's results,
 *                     a page, a worker's notes, a unit's translation and its scores, a step's outcome. {"k": key, "v": value}
 *   checkpoint.json   small, rewritten after each unit of work: {"format", "version", "kind", "basis", "seq", "at", "elapsed_ms",
 *                     "said", "position", "resumes"} — where the job stands, in words ("unit 4,312 of 11,637") and as the runner's own state
 * </pre>
 * A runner reads what it needs back by its key ({@link #get}); a runner whose work is a sequence of model calls hands its seats to
 * {@link #seat}, and the calls come back in order from the record until the record ends, then go to the model. Each call is kept
 * under its lane ({@link #lane}: the run's own thread, a worker's sub-question), with a fingerprint of its shape (the kind of call, the
 * tools offered, how many messages, the system prompt with its numbers left out): a call whose shape differs from the one kept is a
 * job that went another way, and from there that lane asks the model again. So a resumed job loses at most the call in flight.
 *
 * <p>A checkpoint in another {@link #FORMAT}, of another kind of job, or kept for other inputs ({@code basis}: a hash of what the job
 * works on) is not read: the folder is emptied, the job starts over, and {@link #startedOver} is the sentence that says so. A line cut
 * off by the end of the program is passed over. The fence markers' nonce is the process's own: the markers in what is given back are
 * rewritten to this process's, so that the rule the model is given still names them.
 */
public final class Checkpoint implements AutoCloseable {

    private static final ObjectMapper M = new ObjectMapper();

    /** The form of the two files. A checkpoint in another form is not read, and the job starts over. */
    public static final int FORMAT = 1;
    static final String ARTIFACTS = "artifacts.jsonl", STATE = "checkpoint.json";

    /** Nothing kept, nothing read: a run outside a job (a command, a test). */
    public static final Checkpoint NONE = new Checkpoint(null, "", "", "");

    private final Path dir;
    private final String jobId, kind, basis;
    private final Map<String, JsonNode> kept = new HashMap<>();
    private BufferedWriter out;
    private boolean resumed;
    private String startedOver = "";
    private ObjectNode before;
    private long elapsedBefore;
    private final long openedAt = System.currentTimeMillis();
    private long seq;
    private int resumes;
    private ObjectNode position = M.createObjectNode();
    private String said = "";
    private Supplier<ObjectNode> positionOf;
    private Supplier<String> saidOf;
    /** Calls and tools given back from the record, and made anew. */
    final AtomicLong givenBack = new AtomicLong(), made = new AtomicLong();
    /** The next call's index in each lane, the lanes that left their record, and how often each tool call was made in its lane. */
    private final Map<String, Integer> next = new HashMap<>();
    private final Set<String> offRecord = new HashSet<>();
    private final Map<String, Integer> toolTimes = new HashMap<>();

    private Checkpoint(Path dir, String jobId, String kind, String basis) {
        this.dir = dir; this.jobId = jobId; this.kind = kind; this.basis = basis;
    }

    /** The folder a job keeps its work in. */
    public static Path dir(LibraryStore store, String jobId) {
        return store.root().resolve("catalog").resolve("jobs").resolve("work").resolve(jobId.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** What a job's checkpoint says now, or null when it has none: for the job's line after a restart. */
    public static ObjectNode stateOf(LibraryStore store, String jobId) {
        try {
            Path f = dir(store, jobId).resolve(STATE);
            if (!Files.isRegularFile(f)) return null;
            JsonNode n = M.readTree(Files.readString(f, StandardCharsets.UTF_8));
            return n != null && n.isObject() ? (ObjectNode) n : null;
        } catch (IOException | RuntimeException unreadable) { return null; }
    }

    /** The job is over: its kept work goes. */
    public static void discard(LibraryStore store, String jobId) {
        try { deleteTree(dir(store, jobId)); } catch (IOException ignored) { }
    }

    /**
     * The checkpoint of job {@code jobId}, a job of {@code kind} working on {@code basis}: what an earlier process of it kept is read, and
     * what this one gets is added. Never throws for a folder it cannot read: the job then starts over, and {@link #startedOver} says why.
     */
    public static Checkpoint open(LibraryStore store, String jobId, String kind, String basis) {
        if (store == null || jobId == null || jobId.isBlank()) return NONE;
        Checkpoint c = new Checkpoint(dir(store, jobId), jobId, kind == null ? "" : kind, basis == null ? "" : basis);
        try { c.load(); c.begin(); return c; }
        catch (IOException e) { return NONE; }   // the library cannot be written: the job runs as before, with nothing kept
    }

    /** A hash of what a job works on, for {@code basis}. */
    public static String basisOf(String... parts) { return sha(String.join("\u0000", parts)).substring(0, 16); }

    private void load() throws IOException {
        Path state = dir.resolve(STATE), artifacts = dir.resolve(ARTIFACTS);
        if (!Files.exists(state) && !Files.exists(artifacts)) return;
        JsonNode s = null;
        try { if (Files.isRegularFile(state)) s = M.readTree(Files.readString(state, StandardCharsets.UTF_8)); } catch (IOException | RuntimeException unreadable) { s = null; }
        String why = null;
        if (s == null || !s.isObject()) why = "the work kept before could not be read";
        else if (s.path("format").asInt(-1) != FORMAT) why = "the work kept before was written by ResearchZosho " + s.path("version").asText("of another version") + " in a form this version does not read";
        else if (!s.path("kind").asText("").equals(kind)) why = "the work kept before was for another kind of job";
        else if (!s.path("basis").asText("").equals(basis)) why = "what the job works on changed since its work was kept";
        if (why != null) {
            startedOver = capitalised(why) + ", so the job started over.";
            deleteTree(dir);
            return;
        }
        before = (ObjectNode) s;
        elapsedBefore = s.path("elapsed_ms").asLong(0);
        resumes = s.path("resumes").asInt(0);
        seq = s.path("seq").asLong(0);
        if (s.path("position").isObject()) position = (ObjectNode) s.get("position").deepCopy();
        said = s.path("said").asText("");
        if (Files.isRegularFile(artifacts)) {
            String nonce = "";
            for (String line : Files.readAllLines(artifacts, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String l = !nonce.isEmpty() && !nonce.equals(Fence.nonce()) ? line.replace(nonce, Fence.nonce()) : line;
                JsonNode n;
                try { n = M.readTree(l); } catch (IOException | RuntimeException cutOff) { continue; }   // a line the end of the program cut off
                if (n == null || !n.isObject()) continue;
                String k = n.path("k").asText("");
                if (k.equals("#nonce")) { nonce = n.path("v").asText(""); continue; }
                if (k.startsWith("x|")) { void_(k.substring(2), n.path("v").path("from").asInt(0), n.path("v").path("dfrom").asInt(Integer.MAX_VALUE)); continue; }
                if (!k.isEmpty()) kept.put(k, n.get("v"));
            }
        }
        resumed = !kept.isEmpty() || !position.isEmpty();
    }

    /** The calls of {@code lane} from {@code from} on, and its decisions from {@code dfrom} on, are void: the job went another way there. */
    private void void_(String lane, int from, int dfrom) {
        String calls = "m|" + lane + "|", decisions = "d|" + lane + "|";
        kept.keySet().removeIf(k -> {
            try {
                if (k.startsWith(calls)) return Integer.parseInt(k.substring(calls.length())) >= from;
                if (k.startsWith(decisions)) return Integer.parseInt(k.substring(decisions.length())) >= dfrom;
            } catch (NumberFormatException e) { return false; }
            return false;
        });
    }

    /** The lane leaves its record here, its calls from {@code from} and its decisions from {@code dfrom} void: what it does from here is new. */
    private void leave(String lane, int from, int dfrom) {
        offRecord.add(lane);
        ObjectNode v = M.createObjectNode(); v.put("from", from); v.put("dfrom", dfrom);
        try { line("x|" + lane, v); } catch (IOException ignored) { }
        void_(lane, from, dfrom);
    }

    private void begin() throws IOException {
        Files.createDirectories(dir);
        out = Files.newBufferedWriter(dir.resolve(ARTIFACTS), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        line("#nonce", M.getNodeFactory().textNode(Fence.nonce()));
        if (resumed) resumes++;
        writeState();
    }

    // ---- what a runner reads and adds ----

    /** Whether an earlier process of this job kept work this one reads. */
    public boolean resumed() { return resumed; }
    /** The sentence when work was kept and is not read ("…, so the job started over."); "" otherwise. */
    public String startedOver() { return startedOver; }
    /** Where the job stood when its last process ended, in words ("unit 4,312 of 11,637"); "" when nothing said it. */
    public String saidBefore() { return before == null ? "" : before.path("said").asText(""); }
    /** The runner's own state as it last wrote it; empty when there is none. */
    public ObjectNode positionBefore() { return before != null && before.path("position").isObject() ? (ObjectNode) before.get("position").deepCopy() : M.createObjectNode(); }
    /** The time the job has worked, its earlier processes' and this one's. */
    public long elapsedMs() { return elapsedBefore + (System.currentTimeMillis() - openedAt); }

    /** When this process took its first new step: a call made, a tool run or a decision made anew rather than given back; 0 before. */
    private volatile long newSince;
    private void markNew() { if (newSince == 0) newSince = System.currentTimeMillis(); }

    /**
     * The job's own clock, ms: the time it has worked, its earlier processes' and this one's. On a resumed job it stands at the time worked
     * before while the job gives back its kept work, and runs on from there with its first new step; a job that resumes nothing runs from
     * its start. Decisions made against it are kept ({@link #decided}).
     */
    public long runMs() {
        if (!resumed) return elapsedBefore + (System.currentTimeMillis() - openedAt);
        long since = newSince;
        return elapsedBefore + (since == 0 ? 0 : Math.max(0, System.currentTimeMillis() - since));
    }
    /** The time its earlier processes worked. */
    public long elapsedBeforeMs() { return elapsedBefore; }
    public boolean keeps() { return dir != null; }
    public String jobId() { return jobId; }

    /** What was kept under {@code key}, or null. */
    public synchronized JsonNode get(String key) { return kept.get(key); }
    public synchronized boolean has(String key) { return kept.containsKey(key); }
    /** Every value kept under a key that starts with {@code prefix}. */
    public synchronized List<JsonNode> valuesUnder(String prefix) {
        List<JsonNode> out = new ArrayList<>();
        for (var e : kept.entrySet()) if (e.getKey().startsWith(prefix)) out.add(e.getValue());
        return out;
    }

    /** {@code value} kept under {@code key}, written now. */
    public synchronized void put(String key, JsonNode value) {
        if (dir == null) return;
        kept.put(key, value);
        try { line(key, value); seq++; } catch (IOException e) { /* the work goes on; only the keeping stopped */ }
        if (positionOf != null || saidOf != null) writeQuietly();
    }

    private void line(String key, JsonNode value) throws IOException {
        if (out == null) return;
        ObjectNode o = M.createObjectNode(); o.put("k", key); o.set("v", value);
        out.write(M.writeValueAsString(o));
        out.write('\n');
        out.flush();   // on disk the moment it exists: a process that ends next keeps it
    }

    /** Where the job stands: {@code position} the runner's own state, {@code said} the words a person reads after a restart. */
    public synchronized void position(ObjectNode position, String said) {
        if (dir == null) return;
        if (position != null) this.position = position;
        if (said != null) this.said = said;
        writeQuietly();
    }

    /** The position read from the runner at each thing kept, for a runner whose state is easier asked than told. */
    public synchronized void positionBy(Supplier<ObjectNode> position, Supplier<String> said) { this.positionOf = position; this.saidOf = said; }

    private void writeQuietly() {
        try {
            if (positionOf != null) { ObjectNode p = positionOf.get(); if (p != null) position = p; }
            if (saidOf != null) { String s = saidOf.get(); if (s != null) said = s; }
            writeState();
        } catch (IOException | RuntimeException ignored) { }
    }

    private void writeState() throws IOException {
        ObjectNode s = M.createObjectNode();
        s.put("format", FORMAT); s.put("version", Version.string()); s.put("kind", kind); s.put("basis", basis); s.put("job", jobId);
        s.put("seq", seq); s.put("at", Instant.now().toString()); s.put("elapsed_ms", runMs()); s.put("said", said); s.put("resumes", resumes);
        s.set("position", position);
        AtomicWrite.text(dir.resolve(STATE), M.writeValueAsString(s) + "\n");
    }

    @Override public synchronized void close() {
        if (dir == null) return;
        writeQuietly();
        try { if (out != null) out.close(); } catch (IOException ignored) { }
        out = null;
    }

    /** One line for the log: what came back from the record and what was asked again. */
    public String resumedLine() {
        return "resumed" + (saidBefore().isEmpty() ? "" : " at " + saidBefore()) + ": " + givenBack.get() + " call(s) given back from the work kept, " + made.get() + " made anew";
    }

    // ---- lanes: the calls of one thread of the job, in order ----

    private static final ThreadLocal<String> LANE = new ThreadLocal<>();

    /** Closes a {@link #lane}: the lane before it is the lane again. */
    public interface Scope extends AutoCloseable { @Override void close(); }

    /** Until the scope closes, the calls on this thread are kept under {@code name}. A thread with no lane keeps nothing. */
    public static Scope lane(String name) {
        String outer = LANE.get();
        LANE.set(name);
        return () -> { if (outer == null) LANE.remove(); else LANE.set(outer); };
    }

    static String laneNow() { return LANE.get(); }

    /** A lane's name for a piece of work named by words: short, and the same in every process. */
    public static String laneFor(String prefix, String words) { return prefix + sha(words == null ? "" : words).substring(0, 10); }

    /**
     * The fingerprint of a call: its kind, the tools offered and the choice, the number of messages, and the system prompt with its numbers
     * and the fence's nonce left out (a date, a count of minutes left, the process's nonce are not the call's shape).
     */
    static String fingerprint(String kind, ArrayNode messages, ArrayNode tools, String choice) {
        StringBuilder b = new StringBuilder(kind).append('|').append(messages == null ? 0 : messages.size()).append('|');
        if (tools != null) for (JsonNode t : tools) b.append(t.path("function").path("name").asText(t.path("name").asText(""))).append(',');
        b.append('|').append(choice == null ? "" : choice).append('|');
        String system = messages != null && !messages.isEmpty() && "system".equals(messages.get(0).path("role").asText()) ? messages.get(0).path("content").asText("") : "";
        b.append(sha(system.replace(Fence.nonce(), "").replaceAll("\\d+", "#")).substring(0, 12));
        return b.toString();
    }

    /** What a call kept on this thread gives back: the kept answer when its shape matches, else null and the lane is off its record from here. */
    private synchronized JsonNode recall(String lane, int i, String fp) {
        if (offRecord.contains(lane)) return null;
        JsonNode e = kept.get("m|" + lane + "|" + i);
        if (e == null) { offRecord.add(lane); return null; }
        if (e.path("fp").asText("").equals(fp)) return e;
        leave(lane, i, decisions.getOrDefault(lane, 0));
        return null;
    }

    /** The index the lane's next call takes; it is taken only when the call comes back ({@link #advance}), so a call that failed and was asked again keeps its place. */
    private synchronized int nextIndex(String lane) { return next.getOrDefault(lane, 0); }
    private synchronized void advance(String lane) { next.merge(lane, 1, Integer::sum); }

    interface Call<T> { T call(); }

    private <T> T kept(String what, ArrayNode messages, ArrayNode tools, String choice, Class<T> type, long[] usage, Call<T> live) {
        String lane = LANE.get();
        if (dir == null || lane == null) return live.call();
        int i = nextIndex(lane);
        String fp = fingerprint(what, messages, tools, choice);
        JsonNode e = recall(lane, i, fp);
        if (e != null) {
            advance(lane);
            givenBack.incrementAndGet();
            long[] u = {e.path("u").path(0).asLong(0), e.path("u").path(1).asLong(0)};
            if (u[0] + u[1] > 0) {
                DriveClient.countReplayed(u);   // the run's tally counts what the call cost when it was made
                if (usage != null) synchronized (usage) { usage[0] += u[0]; usage[1] += u[1]; }
            }
            JsonNode r = e.get("r");
            if (r == null || r.isNull()) return null;
            return type == String.class ? type.cast(r.asText()) : type.cast(r.deepCopy());
        }
        markNew();
        if (type == String.class) DriveClient.forgetUnanswered();   // what the server says of this call, never of an earlier one on the thread
        T answer = live.call();   // a call that throws takes no place: the call asked again after it takes this one
        // a prose call the server never answered comes back empty: it is asked again (Researcher.whenBack), and that answer is the one kept here
        if (answer instanceof String str && str.isEmpty() && DriveClient.lastClassifyUnanswered() != null) return answer;
        advance(lane);
        made.incrementAndGet();
        ObjectNode v = M.createObjectNode();
        v.put("fp", fp); v.put("kind", what);
        if (answer instanceof JsonNode j) v.set("r", j.deepCopy()); else if (answer == null) v.putNull("r"); else v.put("r", answer.toString());
        long[] u = DriveClient.lastUsage();
        if (u != null && u.length >= 2) v.putArray("u").add(u[0]).add(u[1]);
        put("m|" + lane + "|" + i, v);
        return answer;
    }

    /** {@code d} with its calls kept and given back on a resumed job. */
    public Researcher.Drive seat(Researcher.Drive d) { return seat(d, null); }

    /** The same; a call given back adds what it cost to {@code usage} ({prompt, completion}), as a metered seat's call would. */
    public Researcher.Drive seat(Researcher.Drive d, long[] usage) {
        if (dir == null || d == null) return d;
        Checkpoint cp = this;
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int max, String c) { return cp.kept("chat", m, t, c, ObjectNode.class, usage, () -> d.chat(m, t, max, c)); }
            @Override public ObjectNode decide(ArrayNode m, ArrayNode t, int max, String c) { return cp.kept("decide", m, t, c, ObjectNode.class, usage, () -> d.decide(m, t, max, c)); }
            @Override public String classify(ArrayNode m, int max) { return cp.kept("classify", m, null, null, String.class, usage, () -> d.classify(m, max)); }
            @Override public String prose(ArrayNode m, int max) { return cp.kept("prose", m, null, null, String.class, usage, () -> d.prose(m, max)); }
            @Override public int contextWindow() { return d.contextWindow(); }
            @Override public String model() { return d.model(); }
            @Override public DeclineJudge declineJudge() { return d.declineJudge(); }
        };
    }

    // ---- decisions made against the clock ----

    /** Each lane's next decision's index. */
    private final Map<String, Integer> decisions = new HashMap<>();

    /**
     * A decision the job makes against its clock ({@code what}: "workers-time-up", "round-over"…), kept in the lane's order with the calls.
     * A resumed job gives back the decision it made at this point, instead of making it again on a clock that has moved, for as long as the
     * lane is on its record; from there the decision is made now, on {@link #runMs}, and kept. A thread with no lane decides now.
     */
    public boolean decided(String what, BooleanSupplier live) { return decision(what, () -> BooleanNode.valueOf(live.getAsBoolean())).asBoolean(); }

    /** The same for a number: the minutes left, the time a round ends. */
    public double decidedNumber(String what, DoubleSupplier live) { return decision(what, () -> DoubleNode.valueOf(live.getAsDouble())).asDouble(); }

    private JsonNode decision(String what, Supplier<JsonNode> live) {
        String lane = LANE.get();
        if (dir == null || lane == null) return live.get();
        int i;
        synchronized (this) {
            i = decisions.getOrDefault(lane, 0);
            decisions.put(lane, i + 1);
            if (!offRecord.contains(lane)) {
                JsonNode e = kept.get("d|" + lane + "|" + i);
                if (e != null && e.path("w").asText("").equals(what)) return e.get("v");
                if (e == null) offRecord.add(lane);   // the record ends here: what the lane does from here is new
                else leave(lane, next.getOrDefault(lane, 0), i);   // the job went another way here
            }
        }
        markNew();
        JsonNode v = live.get();
        ObjectNode d = M.createObjectNode(); d.put("w", what); d.set("v", v);
        put("d|" + lane + "|" + i, d);
        return v;
    }

    // ---- tools: what a call of a tool brought back, by the tool and its arguments within the lane ----

    /** What a tool call kept: what it said, and what it did beside its answer that the runner counts ({@code effects}, the runner's own shape). */
    public record Kept(String observation, JsonNode effects) { }

    /** The key a tool call is kept under on this thread, or null when nothing is kept here. The same call made twice is two keys. */
    public synchronized String toolKey(String name, JsonNode args) {
        String lane = LANE.get();
        if (dir == null || lane == null) return null;
        String base = "t|" + lane + "|" + name + "|" + sha(args == null ? "" : args.toString()).substring(0, 16);
        int n = toolTimes.merge(base, 1, Integer::sum);
        return base + "|" + n;
    }

    /** What the call under {@code key} brought back before, or null: then the call is made now, a new step. */
    public Kept kept(String key) {
        if (key == null) return null;
        JsonNode e = get(key);
        if (e == null) { markNew(); return null; }
        givenBack.incrementAndGet();
        return new Kept(e.path("o").asText(""), e.get("fx"));
    }

    /** What the call under {@code key} brought back, kept now. */
    public void keep(String key, String observation, JsonNode effects) {
        if (key == null) return;
        made.incrementAndGet();
        ObjectNode v = M.createObjectNode(); v.put("o", observation == null ? "" : observation);
        if (effects != null && !effects.isEmpty()) v.set("fx", effects);
        put(key, v);
    }

    // ---- helpers ----

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** A count as a person reads it: 11,637. */
    public static String count(long n) { return String.format(Locale.ROOT, "%,d", n); }

    private static String capitalised(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    static void deleteTree(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        try (var s = Files.walk(p)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
    }

    /** Every job's kept work whose job is not in {@code active}: a job that ended in a process that did not live to tidy it up. */
    static void sweep(LibraryStore store, Set<String> active) {
        Path work = store.root().resolve("catalog").resolve("jobs").resolve("work");
        if (!Files.isDirectory(work)) return;
        try (var s = Files.list(work)) {
            for (Path p : s.toList()) if (!active.contains(p.getFileName().toString())) { try { deleteTree(p); } catch (IOException ignored) { } }
        } catch (IOException ignored) { }
    }

    /** The lines of a job's record, in order: for a test, and for whoever looks into a job. */
    static List<String> lines(LibraryStore store, String jobId) throws IOException {
        Path f = dir(store, jobId).resolve(ARTIFACTS);
        return Files.isRegularFile(f) ? Files.readAllLines(f, StandardCharsets.UTF_8) : List.of();
    }
}
