package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.management.OperatingSystemMXBean;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The comic helper served to the libraries of other machines (0.7.6): {@code /comic} of the helpers' front ({@link HelperServer}),
 * behind the same secret. The comic helper works on files, and a library's pages are files on the library's machine; so each request
 * carries its pictures and its reply the pictures the helper wrote ({@link ComicWire}), and the requests are the reader's own, one for
 * one ({@link ComicHelper.Reader}): {@code script}, {@code read}, {@code cut}, {@code join}, {@code set}, {@code crops},
 * {@code picture}.
 *
 * <p>A comic job holds one helper process for its whole run, as it does on its own machine: here that is a session, which the library
 * opens ({@code POST /comic/open}) and closes ({@code POST /comic/<session>/close}). A session is a process of its own (its models
 * loaded once, at its first request), one request at a time, and a folder of its own, where each request's pictures lie while the
 * helper works on them and are removed when its reply has gone. It ends when the library closes it, when a stop is asked
 * ({@code POST /comic/<session>/stop}: its process is ended at once), or when it has had no request for {@link #IDLE}; a request for
 * a session that has ended is answered "gone", and the library opens another. The session's name is 24 random bytes that only the
 * library that opened it is told, and nothing a request names is ever a path here: two libraries that use one machine never get each
 * other's pages.
 *
 * <p>At most {@link #most()} sessions run at once; an open beyond them is answered "busy" at once and the library asks again. The
 * number is the machine's: one per 8 GB of its memory and per 4 processor threads, at least one and at most four. A comic's process
 * takes 0.6 to 0.75 GB for the alphabets, Korean and Chinese, and 1.22 GB with Japanese (measured), and four threads; the machine also
 * keeps MetricX-24 (several gigabytes on its processor), LaBSE and the other helpers loaded beside it.
 */
final class ComicSessions implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ComicSessions.class);
    private static final ObjectMapper M = new ObjectMapper();

    /** The command a session's process is started with: the comic helper set up on this machine. A test stands in the stand-in. */
    static volatile Supplier<List<String>> COMMAND = ComicHelper::serveCommand;
    /** How long a session lives after its last request, and one that was opened and never asked anything; a test shortens them. */
    static volatile Duration IDLE = Duration.ofMinutes(10), UNUSED = Duration.ofMinutes(2);
    /** How many sessions run at once; a test sets it. */
    static volatile IntSupplier MOST = ComicSessions::mostHere;
    /** The clock of the sessions' idle times; a test moves it. */
    static volatile LongSupplier NOW = System::currentTimeMillis;

    /** The requests of a session, the reader's own. */
    static final Set<String> OPS = Set.of("script", "read", "cut", "join", "set", "crops", "picture");

    /** One per 8 GB of the machine's memory and per 4 processor threads (the threads a comic's process takes), at least one and at most four. */
    static int mostHere() {
        long gb = totalMemory() >> 30;
        int threads = Runtime.getRuntime().availableProcessors();
        long byMemory = gb <= 0 ? 1 : gb / 8, byThreads = threads / Math.max(1, ComicHelper.threads());
        return (int) Math.max(1, Math.min(4, Math.min(byMemory, byThreads)));
    }

    /** The machine's memory in bytes, or 0 when it cannot be read. */
    static long totalMemory() {
        try { if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os) return os.getTotalMemorySize(); } catch (RuntimeException | LinkageError e) { /* not readable here */ }
        return 0;
    }

    /** One library's comic job, here. */
    final class Session {
        final String id;
        final Path dir;
        final ComicHelper.Local local;
        final ReentrantLock busy = new ReentrantLock();
        final AtomicInteger requests = new AtomicInteger();
        volatile long last = NOW.getAsLong();
        volatile boolean used;
        Session(String id, Path dir) { this.id = id; this.dir = dir; this.local = new ComicHelper.Local(COMMAND); }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Path root;
    private final int most;
    private final ScheduledExecutorService reaper;
    private final String reads;

    /**
     * The sessions' folder: one under the comic helper's own for this server's process (a machine with a separate set of helpers runs
     * two servers), and what a server that is no longer running left there removed. The idle sessions are looked for every half minute.
     */
    ComicSessions() throws IOException {
        Path all = ComicHelper.dir().resolve("sessions");
        long self = ProcessHandle.current().pid();
        if (Files.isDirectory(all)) try (var left = Files.list(all)) {
            for (Path d : left.toList()) {
                String n = d.getFileName().toString();
                boolean alive = n.matches("\\d{1,19}") && ProcessHandle.of(Long.parseLong(n)).map(ProcessHandle::isAlive).orElse(false);
                if (!alive || n.equals(String.valueOf(self))) Checkpoint.deleteTree(d);   // a server's own folder from before is its own, and empty of sessions
            }
        }
        root = Files.createDirectories(all.resolve(String.valueOf(self)));
        most = Math.max(1, MOST.getAsInt());
        reads = readsSaid();
        reaper = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "comic-sessions"); t.setDaemon(true); return t; });
        reaper.scheduleWithFixedDelay(this::reap, 30, 30, TimeUnit.SECONDS);
    }

    int most() { return most; }
    int running() { return sessions.size(); }

    /** What it reads, from the readers set up here: "Japanese, Chinese, Korean and the Latin, Cyrillic and Greek alphabets". */
    static String readsSaid() {
        List<String> languages = new ArrayList<>(), alphabets = new ArrayList<>();
        for (ComicHelper.Item i : ComicHelper.manifest()) {
            if (i.reads().isEmpty() || !ComicHelper.have(i)) continue;
            switch (i.reads()) {
                case "japanese" -> languages.add("Japanese");
                case "chinese" -> languages.add("Chinese");
                case "korean" -> languages.add("Korean");
                case "latin" -> alphabets.add("Latin");
                case "cyrillic" -> alphabets.add("Cyrillic");
                case "greek" -> alphabets.add("Greek");
                default -> { }
            }
        }
        List<String> all = new ArrayList<>(languages);
        if (!alphabets.isEmpty()) all.add("the " + Chrome.ENGLISH.and(alphabets) + (alphabets.size() == 1 ? " alphabet" : " alphabets"));
        return Chrome.ENGLISH.and(all);
    }

    /** Its line in the front's health: where it runs, how many comics at once, and what it reads. */
    String line() {
        return "the comic helper on cpu, " + (most == 1 ? "one comic" : most + " comics") + " at once (" + ComicHelper.threads() + " threads each)" + (reads.isEmpty() ? "" : "; it reads " + reads);
    }

    /** A request under {@code /comic}: {@code rest} is the path after it. */
    void handle(HttpExchange x, String rest) throws IOException {
        String path = rest.replaceFirst("^/+", "").replaceFirst("/+$", "");
        if (path.isEmpty() || path.equals("health")) {
            ObjectNode o = M.createObjectNode();
            o.put("model", "the comic helper"); o.put("device", "cpu"); o.put("line", line()); o.put("threads", ComicHelper.threads()); o.put("most", most); o.put("running", sessions.size()); o.put("reads", reads);
            reply(x, 200, o, List.of(), List.of());
            return;
        }
        if (!x.getRequestMethod().equalsIgnoreCase("POST")) { reply(x, 405, error("a request to the comic helper is a POST"), List.of(), List.of()); return; }
        if (path.equals("open")) { open(x); return; }
        String[] p = path.split("/");
        if (p.length != 2 || !p[0].matches("[0-9a-f]{48}")) { reply(x, 404, error("no such request: open, then <session>/" + String.join(", ", OPS.stream().sorted().toList()) + ", stop or close"), List.of(), List.of()); return; }
        String id = p[0], op = p[1];
        if (op.equals("stop") || op.equals("close")) {
            Session s = sessions.remove(id);
            if (s != null) end(s, op.equals("stop"));
            ObjectNode o = M.createObjectNode(); o.put("ok", true);
            reply(x, 200, o, List.of(), List.of());
            return;
        }
        if (!OPS.contains(op)) { reply(x, 404, error("no such request: " + op.replaceAll("[^a-z]", "")), List.of(), List.of()); return; }
        Session s = sessions.get(id);
        if (s == null) { ObjectNode o = error("no such session: it was closed, stopped, or ended after " + minutes(IDLE) + " without a request"); o.put("gone", true); reply(x, 404, o, List.of(), List.of()); return; }
        if (!s.busy.tryLock()) { reply(x, 409, error("this session has a request under way already: one at a time"), List.of(), List.of()); return; }
        Path req = s.dir.resolve(String.valueOf(s.requests.incrementAndGet()));
        try {
            s.used = true; s.last = NOW.getAsLong();
            String length = x.getRequestHeaders().getFirst("Content-length");
            long declared = -1;
            try { declared = length == null ? -1 : Long.parseLong(length.strip()); } catch (NumberFormatException ignored) { }
            if (declared > ComicWire.BODY_MOST + ComicWire.LINE_MOST) { reply(x, 413, error("the request is " + ComicWire.mb(declared) + ", more than the " + ComicWire.mb(ComicWire.BODY_MOST) + " of pictures this machine takes at once"), List.of(), List.of()); return; }
            ComicWire.Body b;
            try (InputStream in = x.getRequestBody()) { b = ComicWire.read(in, req.resolve("in")); }
            catch (ComicWire.Refused r) { reply(x, r.tooLarge ? 413 : 400, error(r.getMessage()), List.of(), List.of()); return; }
            Path out = Files.createDirectories(req.resolve("out"));
            ObjectNode answer; List<Path> files = new ArrayList<>(); List<String> names = new ArrayList<>();
            try { answer = run(s, op, b, out, files, names); }
            catch (ComicHelper.Failed f) { answer = M.createObjectNode(); answer.put("ok", false); answer.putObject("error").put("type", f.type()).put("message", f.getMessage()); files.clear(); names.clear(); }
            catch (IOException e) { answer = M.createObjectNode(); answer.put("ok", false); answer.putObject("error").put("helper", true).put("message", String.valueOf(e.getMessage())); files.clear(); names.clear(); }
            ObjectNode helper = answer.putObject("helper"); helper.put("loaded", s.local.loaded()); ArrayNode ls = helper.putArray("languages"); for (String l : s.local.languages()) ls.add(l);
            reply(x, 200, answer, files, names);
        } finally {
            s.last = NOW.getAsLong();
            s.busy.unlock();
            try { Checkpoint.deleteTree(req); } catch (IOException ignored) { /* the session's folder goes when it ends */ }
        }
    }

    /** A request done by the session's process: what it answered, and the files it wrote that go back, each by its name in the answer. */
    private ObjectNode run(Session s, String op, ComicWire.Body b, Path out, List<Path> files, List<String> names) throws IOException {
        JsonNode j = b.json();
        List<Path> in = b.files();
        if (!op.equals("script") && !op.equals("join") && in.size() != 1) throw new ComicHelper.Failed("the request carries " + in.size() + " pictures, and " + op + " takes one");
        ObjectNode o;
        switch (op) {
            case "script" -> { o = ((ObjectNode) s.local.script(in)).deepCopy(); o.remove("id"); }
            case "read" -> { o = M.createObjectNode(); o.set("page", s.local.read(in.get(0), j.path("language").asText(""), j.path("order").asText(""))); }
            case "cut" -> {
                Path dir = out.resolve("cut");
                o = ((ObjectNode) s.local.cut(in.get(0), dir)).deepCopy(); o.remove("id");
                for (JsonNode piece : o.path("pieces")) {
                    Path f = Path.of(piece.path("file").asText("")).toAbsolutePath().normalize();
                    if (!f.startsWith(dir.toAbsolutePath().normalize()) || !Files.isRegularFile(f)) throw new ComicHelper.Failed("the helper named a piece it did not write: " + f.getFileName());
                    ((ObjectNode) piece).put("file", sent(f, files, names));
                }
            }
            case "join" -> {
                JsonNode tops = j.path("tops");
                if (tops.size() != in.size()) throw new ComicHelper.Failed("the request gives " + tops.size() + " tops for " + in.size() + " pieces");
                List<ComicHelper.Piece> pieces = new ArrayList<>();
                for (int i = 0; i < in.size(); i++) pieces.add(new ComicHelper.Piece(in.get(i), tops.get(i).asInt()));
                Path to = out.resolve(outName(j));
                s.local.join(pieces, j.path("width").asInt(), j.path("height").asInt(), to);
                o = M.createObjectNode(); o.put("out", sent(to, files, names));
            }
            case "set" -> {
                String script = j.path("script").asText("");
                if (!ComicHelper.ALPHABETS.contains(script) && !ComicHelper.CJK_SCRIPTS.contains(script)) throw new ComicHelper.Failed("no lettering font is named for the writing " + script.replaceAll("[^a-z-]", ""));
                if (!j.path("texts").isObject()) throw new ComicHelper.Failed("set needs the texts of the page's blocks");
                List<String> keep = new ArrayList<>(); for (JsonNode k : j.path("keep")) keep.add(k.asText());
                Path to = out.resolve(outName(j));
                JsonNode report = s.local.set(in.get(0), j.path("page"), (ObjectNode) j.path("texts"), script, keep, to);
                o = M.createObjectNode(); o.set("report", report); o.put("out", sent(to, files, names));
            }
            case "crops" -> {
                Path dir = out.resolve("crops");
                List<String> ids = new ArrayList<>(); for (JsonNode id : j.path("ids")) ids.add(id.asText());
                o = M.createObjectNode(); ArrayNode cs = o.putArray("crops");
                for (ComicHelper.Crop c : s.local.crops(in.get(0), j.path("page"), ids, dir)) {
                    Path f = c.file().toAbsolutePath().normalize();
                    if (!f.startsWith(dir.toAbsolutePath().normalize()) || !Files.isRegularFile(f)) throw new ComicHelper.Failed("the helper named a crop it did not write: " + f.getFileName());
                    cs.addObject().put("id", c.id()).put("file", sent(f, files, names));
                }
            }
            case "picture" -> {
                List<Map.Entry<String, double[]>> boxes = new ArrayList<>();
                for (JsonNode one : j.path("boxes")) { double[] box = new double[4]; for (int k = 0; k < 4; k++) box[k] = one.path(1).path(k).asDouble(); boxes.add(Map.entry(one.path(0).asText(""), box)); }
                Path to = out.resolve(outName(j));
                s.local.picture(in.get(0), boxes, to);
                o = M.createObjectNode(); o.put("out", sent(to, files, names));
            }
            default -> throw new ComicHelper.Failed("no such request: " + op);
        }
        o.put("ok", true);
        return o;
    }

    /** The name a request gives the picture it asks for, which decides its format by its ending; refused when it is not a safe one. */
    private static String outName(JsonNode j) throws ComicHelper.Failed {
        String n = j.path("out").asText("");
        if (!ComicWire.safeName(n)) throw new ComicHelper.Failed("the request names no picture to write, or one by a name that is not taken");
        return n;
    }

    /** A file the helper wrote, to go back with the reply: its name, made unique among the reply's. */
    private static String sent(Path f, List<Path> files, List<String> names) throws ComicHelper.Failed {
        if (!Files.isRegularFile(f)) throw new ComicHelper.Failed("the helper did not write " + f.getFileName());
        String n = f.getFileName().toString();
        if (!ComicWire.safeName(n)) throw new ComicHelper.Failed("the helper wrote a file by a name that does not go back: " + n.replaceAll("[^\\p{L}\\p{N}._ -]", "?"));
        if (names.contains(n)) throw new ComicHelper.Failed("the helper wrote two files of one name: " + n);
        files.add(f); names.add(n);
        return n;
    }

    /** {@code POST /comic/open}: a session, or "busy" at once when {@link #most} run already. */
    private void open(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody()) { in.readNBytes(4096); }
        Session s;
        synchronized (sessions) {
            if (sessions.size() >= most) {
                ObjectNode o = error("the comic helper here reads " + (most == 1 ? "one comic" : most + " comics") + " at once, the most it takes, and that many are being read now: ask again");
                o.put("busy", true); o.put("running", sessions.size()); o.put("most", most);
                reply(x, 503, o, List.of(), List.of());
                return;
            }
            byte[] b = new byte[24]; new SecureRandom().nextBytes(b);
            String id = HexFormat.of().formatHex(b);
            s = new Session(id, Files.createDirectories(root.resolve(id)));
            sessions.put(id, s);
        }
        log.info("comic helper: a session opened, {} of {} running", sessions.size(), most);
        ObjectNode o = M.createObjectNode(); o.put("ok", true); o.put("session", s.id); o.put("most", most);
        reply(x, 200, o, List.of(), List.of());
    }

    /** Ends a session: its process asked to quit (closed) or ended at once (stopped), and its folder removed. */
    private void end(Session s, boolean stop) {
        if (stop) s.local.abort(); else s.local.close();
        try { Checkpoint.deleteTree(s.dir); } catch (IOException ignored) { /* what a request still writes there goes with the next start's sweep */ }
        log.info("comic helper: a session {}, {} of {} running", stop ? "stopped" : "closed", sessions.size(), most);
    }

    /** Ends the sessions that have had no request for {@link #IDLE}, and the ones opened and never asked anything for {@link #UNUSED}. */
    void reap() {
        long now = NOW.getAsLong();
        for (Session s : List.copyOf(sessions.values())) {
            if (s.busy.isLocked()) continue;   // a request is under way: its own wait ends it if the helper never answers
            if (now - s.last < (s.used ? IDLE : UNUSED).toMillis()) continue;
            if (sessions.remove(s.id, s)) { end(s, true); log.info("comic helper: a session ended after {} without a request", minutes(s.used ? IDLE : UNUSED)); }
        }
    }

    static String minutes(Duration d) { long m = d.toMinutes(); return m >= 1 ? m + (m == 1 ? " minute" : " minutes") : d.toSeconds() + " seconds"; }

    private static ObjectNode error(String message) { ObjectNode o = M.createObjectNode(); o.put("ok", false); o.putObject("error").put("message", message); return o; }

    private static void reply(HttpExchange x, int code, ObjectNode json, List<Path> files, List<String> names) throws IOException {
        x.getResponseHeaders().set("Content-Type", ComicWire.TYPE);
        x.sendResponseHeaders(code, ComicWire.length(json, files, names));
        try (OutputStream os = x.getResponseBody()) { ComicWire.write(os, json, files, names); }
    }

    @Override public void close() {
        reaper.shutdownNow();
        for (Session s : List.copyOf(sessions.values())) if (sessions.remove(s.id, s)) end(s, true);
    }

    /** The sessions as they stand, for a test: each one's name and whether a request has come for it. */
    Map<String, Boolean> state() { Map<String, Boolean> m = new LinkedHashMap<>(); for (Session s : sessions.values()) m.put(s.id, s.used); return m; }
}
