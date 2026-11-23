package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.anthropic.Anthropic;
import org.researchzosho.drive.aws.Bedrock;
import org.researchzosho.drive.openai.OpenAI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The models kept loaded while work needs them, and let go when it does not (0.8.0 review, round C2). A model server of this
 * program's own (llama-swap) unloads a model after its idle time: the chat model after 10 minutes, the embeddings model after 5
 * ({@link ModelServer}). Work that runs (a research run, a translation, the night's work) holds the models it uses loaded however long
 * its gaps between calls: every {@link #PING_MS} it asks the server for the model's health through llama-swap's own way to a model
 * ({@code /upstream/<model>/health}), which loads the model when it is not running and counts as a use. Work that is about to start
 * (a run or a translation filed, a page added, the chat or the library's page opened) warms the same models in the background, so the
 * load overlaps its first steps. An API on the internet is neither held nor warmed; a server that is not llama-swap answers that ping
 * with a 404 and is left alone for an hour.
 *
 * <p>LaBSE and the standalone embeddings server are containers of their own, not under llama-swap: they also run where no model server
 * of this program's is installed (a machine that serves the helpers, a machine whose model is hosted). They stop by an idle rule of
 * their own ({@link #tick}): each use, and each round of a hold, writes the time into a stamp file in this program's state folder, which
 * every process of this machine shares; a container whose stamp is older than the embedding models' idle time, and that nothing in this
 * process holds, is stopped by its name. The next use, or an intent, starts it again. On a machine that serves the helpers no work
 * holds or warms them, so the front starts one when a request finds it silent and waits for it ({@link #wake}); a library's own search
 * that finds its embeddings server stopped goes by words that once and has it started in the background ({@link #startForCall}).
 */
final class ModelsAwake {
    private ModelsAwake() { }

    private static final Logger LOG = LoggerFactory.getLogger(ModelsAwake.class);

    /** How often a hold asks again, and the least time between two warmings of one model; a test shortens it. */
    static volatile long PING_MS = 60_000;
    /** The embedding models' idle time in minutes (the embeddings model under llama-swap, LaBSE, the standalone embeddings server); 0: never. */
    static final String EMBED_IDLE = "RESEARCHZOSHO_EMBED_IDLE_MINUTES";
    static final int EMBED_IDLE_DEFAULT = 5;

    static int embedIdleMinutes() {
        String v = Config.get(EMBED_IDLE);
        try { int n = v == null || v.isBlank() ? EMBED_IDLE_DEFAULT : Integer.parseInt(v.strip()); return n < 0 ? EMBED_IDLE_DEFAULT : n; }
        catch (NumberFormatException e) { return EMBED_IDLE_DEFAULT; }
    }

    /** A model to keep loaded: on a model server by its address and model id, or a container of this machine's by its name (and address). */
    record Target(String base, String model, String container) {
        String key() { return container != null ? "container " + container : base + " " + model; }
    }

    private static final HttpClient HTTP = Outbound.client(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());

    static boolean blank(String s) { return s == null || s.isBlank(); }
    static String base(String url) { return url.strip().replaceAll("/+$", "").replaceAll("/v1$", ""); }
    static boolean hosted(String url) { return DriveClient.hostedApi(url) || OpenAI.is(url) || Anthropic.is(url); }
    static boolean translation(String kind) { return kind != null && kind.toLowerCase(Locale.ROOT).startsWith("translat"); }

    /**
     * The containers of this machine the idle rule looks after, by name, with where they answer; LaBSE's is
     * {@link TranslationScores#LOCAL_LABSE}. A test points them at stand-ins; the test suite points them at ports where nothing listens.
     */
    static volatile Map<String, String> CONTAINERS = machine();

    /** The containers of this machine where they answer: LaBSE at {@link TranslationScores#LOCAL_LABSE}, the embeddings server at its port. */
    static Map<String, String> machine() { return Map.of(TranslationScores.LABSE_CONTAINER, TranslationScores.LOCAL_LABSE, Embed.CONTAINER, "http://127.0.0.1:" + Embed.DEFAULT_PORT); }

    static Map<String, String> containers() { return CONTAINERS; }

    /** The container a model server's address is, when it is one of this machine's containers; null otherwise. */
    static String containerAt(String url) {
        if (blank(url)) return null;
        String b = base(url).replace("://localhost:", "://127.0.0.1:");
        for (Map.Entry<String, String> c : containers().entrySet()) if (c.getValue().equals(b)) return c.getKey();
        return null;
    }

    /**
     * The models a kind of work uses: the chat model it runs on ({@code drive} and {@code model}, the library's own when blank), the
     * embeddings model, and LaBSE for a translation when this machine runs it.
     */
    static List<Target> forWork(String kind, String drive, String model) {
        List<Target> t = new ArrayList<>();
        String d = blank(drive) ? Config.get("RESEARCHZOSHO_DRIVE") : drive, m = blank(model) ? Config.get("RESEARCHZOSHO_MODEL") : model;
        if (!blank(d) && !blank(m) && !hosted(d) && containerAt(d) == null) t.add(new Target(base(d), m.strip(), null));
        String e = Config.get("RESEARCHZOSHO_EMBED");
        if (!blank(e) && !e.equalsIgnoreCase("off") && !e.equalsIgnoreCase("none") && !Bedrock.is(e) && !hosted(e)) {
            String c = containerAt(e);
            t.add(c != null ? new Target(base(e), null, c) : new Target(base(e), Config.get("RESEARCHZOSHO_EMBED_MODEL", "embed").strip(), null));
        }
        if (translation(kind) && Helpers.localAllowed() && !Helpers.off(Helpers.Helper.LABSE) && Helpers.address(Helpers.Helper.LABSE) == null)
            t.add(new Target(containers().get(TranslationScores.LABSE_CONTAINER), null, TranslationScores.LABSE_CONTAINER));
        return t;
    }

    // ---- holds ----

    /** A hold, let go when the work ends. */
    interface Hold extends AutoCloseable { @Override void close(); }

    private static final Map<String, Integer> HELD = new ConcurrentHashMap<>();
    private static final Map<String, Target> TARGETS = new ConcurrentHashMap<>();
    private static Thread pinger;
    /** Rung when a hold begins: the pinger looks again at once, at the gap {@link #PING_MS} says now. */
    private static final Object BELL = new Object();

    /** The models a kind of work uses, held loaded until the work ends. */
    static Hold hold(String kind, String drive, String model) {
        List<Target> ts;
        try { ts = forWork(kind, drive, model); } catch (RuntimeException e) { return () -> { }; }
        for (Target t : ts) { TARGETS.put(t.key(), t); HELD.merge(t.key(), 1, Integer::sum); touch(t); }
        if (!ts.isEmpty()) { startPinger(); synchronized (BELL) { BELL.notifyAll(); } Thread.ofVirtual().start(() -> { for (Target t : ts) ping(t, true); }); }
        return () -> { for (Target t : ts) HELD.computeIfPresent(t.key(), (k, n) -> n <= 1 ? null : n - 1); };
    }

    /** What this process holds now, by key. */
    static Set<String> held() { return Set.copyOf(HELD.keySet()); }

    private static synchronized void startPinger() {
        if (pinger != null && pinger.isAlive()) return;
        pinger = Thread.ofPlatform().daemon().name("models-awake").start(() -> {
            long last = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted()) {
                try { synchronized (BELL) { BELL.wait(Math.max(10, Math.min(1_000, PING_MS / 4))); } } catch (InterruptedException e) { return; }
                if (System.currentTimeMillis() - last < PING_MS) continue;
                last = System.currentTimeMillis();
                for (String k : HELD.keySet()) { Target t = TARGETS.get(k); if (t != null) { touch(t); Thread.ofVirtual().start(() -> ping(t, true)); } }
            }
        });
    }

    // ---- intents ----

    private static final Map<String, Long> WARMED = new ConcurrentHashMap<>();

    /** Work of this kind is about to start: its models warmed in the background, each at most once in {@link #PING_MS}. */
    static void intent(String kind) {
        List<Target> ts;
        try { ts = forWork(kind, null, null); } catch (RuntimeException e) { return; }
        long now = System.currentTimeMillis();
        for (Target t : ts) {
            Long was = WARMED.get(t.key());
            if (was != null && now - was < PING_MS) continue;
            WARMED.put(t.key(), now);
            touch(t);
            Thread.ofVirtual().start(() -> ping(t, false));
        }
    }

    // ---- one ping ----

    private static final Map<String, Long> NOT_SWAP = new ConcurrentHashMap<>();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * The model asked for its health through llama-swap ({@code /upstream/<model>/health}: loaded when it is not, and counted as a use);
     * a container of this machine started when it was stopped. A server that answers 404 or 403 is left alone for an hour.
     */
    static void ping(Target t, boolean held) {
        if (!IN_FLIGHT.add(t.key())) return;   // one at a time: a load can take minutes
        try {
            if (t.container() != null) { startContainer(t.container()); return; }
            Long no = NOT_SWAP.get(t.key());
            if (no != null && System.currentTimeMillis() - no < 3_600_000L) return;
            HttpResponse<Void> r = HTTP.send(HttpRequest.newBuilder(URI.create(t.base() + "/upstream/" + URLEncoder.encode(t.model(), StandardCharsets.UTF_8).replace("+", "%20") + "/health"))
                    .timeout(Duration.ofMinutes(15)).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() == 404 || r.statusCode() == 403 || r.statusCode() == 405) NOT_SWAP.put(t.key(), System.currentTimeMillis());
        } catch (IOException | RuntimeException e) { LOG.debug("The model {} could not be asked for its health: {}", t.key(), e.getMessage()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { IN_FLIGHT.remove(t.key()); }
    }

    /** A container of this machine's started again when it is stopped (by the idle rule, or by hand); nothing when it runs or is absent. */
    static void startContainer(String name) {
        try {
            Searx.Result r = Searx.runner.run(List.of("docker", "inspect", "-f", "{{.State.Running}}", name));
            if (r.code() == 0 && r.out().strip().equals("false")) { Searx.runner.run(List.of("docker", "start", name)); touchStamp(name); }   // started by this install: its idle rule is this install's
        } catch (Exception e) { LOG.debug("The container {} could not be started: {}", name, e.getMessage()); }
    }

    // ---- the containers' idle rule ----

    /** Where the last use of a container of this machine is written: its stamp's time, shared by every process of this machine. */
    static Path stamp(String container) { return Config.home().resolve("idle").resolve(container + ".used"); }

    private static void touch(Target t) { if (t.container() != null) touchStamp(t.container()); }

    private static void touchStamp(String container) {
        try {
            Path s = stamp(container);
            Files.createDirectories(s.getParent());
            if (!Files.exists(s)) Files.writeString(s, "");
            Files.setLastModifiedTime(s, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException | RuntimeException e) { /* the next use writes it */ }
    }

    /** A call made to a model server's address: when it is one of this machine's containers, its use written down. */
    static void used(String url) { String c = containerAt(url); if (c != null) touchStamp(c); }

    /** A call made to a container of this machine's, known by its name: its use written down. */
    static void usedContainer(String name) { touchStamp(name); }

    // ---- a container woken by a request (the helpers machine's front) ----

    /** How long a request waits for a container it started to answer; a test shortens it. */
    static volatile Duration WAKE_WAIT = Duration.ofMinutes(2);
    /** The wakes under way, by container: a request that finds one waits for it, so one start serves every request that came meanwhile. */
    private static final Map<String, CompletableFuture<String>> WAKING = new ConcurrentHashMap<>();

    /**
     * A container of this machine's that a request found silent, woken for that request: "" when it answers now (started, if it was
     * stopped, and its answer waited for at most {@link #WAKE_WAIT}); a line of the front's kind ("not loaded: …") when it was started
     * and did not answer in that time, or could not be started; null when it is not a stopped container (absent, or running and not
     * answering, as one that is still loading after the machine started), and nothing was done. Requests that come while a wake is
     * under way wait for that one: one {@code docker start}.
     */
    static String wake(String name, String address) {
        CompletableFuture<String> mine = new CompletableFuture<>(), under = WAKING.putIfAbsent(name, mine);
        if (under != null) return under.join();
        return wakeWith(name, address, mine);
    }

    /** The same on a thread of its own, unless a wake is under way: the caller goes on at once. */
    static void wakeInBackground(String name, String address) {
        CompletableFuture<String> mine = new CompletableFuture<>();
        if (WAKING.putIfAbsent(name, mine) == null) Thread.ofVirtual().start(() -> wakeWith(name, address, mine));
    }

    private static String wakeWith(String name, String address, CompletableFuture<String> mine) {
        String said = null;
        try { said = wakeNow(name, address); }
        catch (RuntimeException e) { LOG.debug("The container {} could not be woken: {}", name, e.getMessage()); }
        finally { WAKING.remove(name, mine); mine.complete(said); }
        return said;
    }

    /**
     * A call of a library's own work (a search's question, an entry's vectors) that found its embeddings server silent: when the server is
     * a container of this machine and is stopped, it is started on a thread of its own, and the call goes on without it, by words, as it
     * does when no embeddings server answers. Whether a start is under way now. At most one {@code docker inspect}, and none while a start
     * is under way or for a server that is no container of this machine (one under llama-swap loads by the request itself; another
     * machine's helpers front wakes its own).
     */
    static boolean startForCall(String url) {
        String name = containerAt(url);
        if (name == null) return false;
        if (WAKING.containsKey(name)) return true;
        try {
            Searx.Result r = Searx.runner.run(List.of("docker", "inspect", "-f", "{{.State.Running}}", name));
            if (r.code() != 0 || !r.out().strip().equals("false")) return false;   // absent, or running and not answering: nothing to start
        } catch (Exception e) { return false; }
        wakeInBackground(name, base(url));
        return true;
    }

    /** Whether the container at this address is being started now. */
    static boolean starting(String url) { String name = containerAt(url); return name != null && WAKING.containsKey(name); }

    private static String wakeNow(String name, String address) {
        if (Embed.answers(address)) return "";   // it answers already: a start that just ended, or one a library of this machine made
        try {
            Searx.Result r = Searx.runner.run(List.of("docker", "inspect", "-f", "{{.State.Running}}", name));
            if (r.code() != 0 || !r.out().strip().equals("false")) return null;
            Searx.Result s = Searx.runner.run(List.of("docker", "start", name));
            if (s.code() != 0) return "not loaded: its container is stopped and could not be started (" + s.out().strip() + ")";
        } catch (Exception e) { return null; }
        touchStamp(name);
        LOG.info("The container {} was stopped and was asked for, so it is started again.", name);
        long until = System.currentTimeMillis() + WAKE_WAIT.toMillis();
        while (!Embed.answers(address)) {
            if (System.currentTimeMillis() >= until) return "not loaded: its container was stopped, this request started it, and it did not answer within "
                    + TranslationScores.Metricx.said(WAKE_WAIT) + " (docker logs " + name + " on the helpers machine says why)";
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return "";
    }

    /**
     * The front's line for a container it serves that does not answer, of the kind its own helpers' lines are: starting now, when a
     * request is waking it; stopped, and started by the next request, when it is stopped (after its idle time, when its last use is
     * that old); null when it is neither, or Docker cannot say.
     */
    static String restingLine(String name) {
        if (WAKING.containsKey(name)) return "not loaded yet: starting now";
        try {
            Searx.Result r = Searx.runner.run(List.of("docker", "inspect", "-f", "{{.State.Running}}", name));
            if (r.code() != 0 || !r.out().strip().equals("false")) return null;
        } catch (Exception e) { return null; }
        int idle = embedIdleMinutes();
        long last;
        try { last = Files.getLastModifiedTime(stamp(name)).toMillis(); } catch (IOException e) { last = -1; }
        boolean idled = idle > 0 && last >= 0 && System.currentTimeMillis() - last >= idle * 60_000L;
        return "not loaded now: " + (idled ? "stopped after " + idle + (idle == 1 ? " minute" : " minutes") + " without use" : "its container is stopped") + "; the next request starts it again";
    }

    /**
     * One round of the containers' idle rule: each container of this machine's that runs, held by nothing in this process, whose last
     * use is older than the idle time, stopped by its name. Only a container this install started or used has a stamp in its home, and
     * one without is never stopped: on a machine with another install, its containers are that install's (a second install for a check
     * would have stopped the running LaBSE of the owner's library, 2026-11-18). How many were stopped.
     */
    static int tick(long now) {
        int idle = embedIdleMinutes(), stopped = 0;
        if (idle == 0) return 0;
        for (String name : containers().keySet()) {
            if (HELD.containsKey("container " + name)) continue;
            long last;
            try { last = Files.getLastModifiedTime(stamp(name)).toMillis(); } catch (IOException e) { continue; }   // never started nor used by this install: not its to stop
            if (now - last < idle * 60_000L) continue;
            try {
                Searx.Result r = Searx.runner.run(List.of("docker", "inspect", "-f", "{{.State.Running}}", name));
                if (r.code() != 0 || !r.out().strip().equals("true")) continue;
                Searx.runner.run(List.of("docker", "stop", name));
                stopped++;
                LOG.info("The container {} was not used for {} minutes, so it is stopped; its next use starts it again.", name, idle);
            } catch (Exception e) { LOG.debug("The container {} could not be looked at: {}", name, e.getMessage()); }
        }
        return stopped;
    }

    private static Thread watcher;

    /** The idle rule, every minute, in a process of this machine that may run the helpers (a library's service, `helpers serve`, a node). */
    static synchronized void watchContainers() {
        if (watcher != null && watcher.isAlive()) return;
        if (!Helpers.localAllowed() || !Searx.haveDocker()) return;
        watcher = Thread.ofPlatform().daemon().name("containers-idle").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
                try { tick(System.currentTimeMillis()); } catch (RuntimeException e) { LOG.debug("The containers' idle rule: {}", e.getMessage()); }
            }
        });
    }
}
