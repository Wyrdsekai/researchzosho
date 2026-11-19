package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Gated;

/**
 * A library's side of a use under an account service (0.8.0, stage 3). Before a use (a research run or another job, a chat turn, a
 * page added by its address) the library asks the service under the request's statement, which says whose use it is; the use goes
 * on only on a yes, and the person is told the service's sentence on a no. When the use ends, the library tells the service. In a
 * library of one's own there is no one to ask, and nothing of this happens.
 */
final class ServiceUses {
    private ServiceUses() { }

    private static final Logger LOG = LoggerFactory.getLogger(ServiceUses.class);

    /** The folder a library keeps the ends of its uses in until the service has taken them (in its catalog); null: kept in memory only. */
    static final AtomicReference<Path> ENDING = new AtomicReference<>();
    static final String ENDING_DIR = "uses-ending";
    /** The first wait after an end was not taken; it doubles up to {@link #RETRY_MOST_MS}. A test shortens it. */
    static volatile long RETRY_MS = 2_000;
    static final long RETRY_MOST_MS = 10 * 60_000L;

    /**
     * An end not taken yet: how the use ended, the service it is told to (the one this process ran under when the use ended, which let
     * it start), when it is told next, and how long it waited after its last refusal. Each end keeps its own: one the service keeps
     * refusing, or one for a service that is gone, holds back no other, and is never told to another service.
     */
    private record Ending(String state, String at, long next, long waited, String key) { }

    /** The key the service gave with each use this process started, which its end and its state are told with (round C, SEC 12). */
    private static final Map<String, String> KEYS = new ConcurrentHashMap<>();
    static String keyOf(String use) { return use == null ? "" : KEYS.getOrDefault(use, ""); }

    /**
     * A use's key as its job's record kept it, put back at the library's start: a job resumed after a restart ends its use and asks its
     * state with it (round F, the second review: a key kept only in memory left every resumed job's use open for a day, its cap unwatched).
     */
    static void remember(String use, String key) { if (use != null && !use.isBlank() && key != null && !key.isBlank()) KEYS.putIfAbsent(use, key); }

    /** The ends not taken yet, by their use. */
    private static final Map<String, Ending> PENDING = new ConcurrentHashMap<>();
    private static final Object BELL = new Object();
    private static Thread ender;

    /** What a new process knows of the ends: nothing but its folder's (a test's restart of a library, in the same process). */
    static void forget() { PENDING.clear(); }

    /** In a library under a service, at its start: the ends it kept in {@code catalog} before it stopped are told again, as every end is until taken. */
    static void keepEndsIn(Path catalog) { ENDING.set(catalog.resolve(ENDING_DIR)); resume(); }

    /** The ends kept are told now, and from now until each is taken. */
    static synchronized void resume() {
        if (ender == null || !ender.isAlive()) ender = Thread.ofPlatform().daemon().name("uses-ending").start(ServiceUses::tellEnds);
        synchronized (BELL) { BELL.notifyAll(); }
    }

    /**
     * What the library's ender does: each end not taken is told to its service, and when refused told again a while later (twice as long
     * each time, ten minutes at most), until taken. The ends kept in the library's folder before this process started are told to the
     * service it runs under now.
     */
    private static void tellEnds() {
        while (true) {
            Path dir = ENDING.get();
            if (dir != null && Files.isDirectory(dir)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
                    for (Path f : files) {
                        try {
                            JsonNode j = Statement.M.readTree(Files.readString(f, StandardCharsets.UTF_8));
                            String use = j.path("use").asText(""), at = ServiceAccess.service;
                            if (use.matches("[A-Za-z0-9_-]{1,64}") && at != null) PENDING.putIfAbsent(use, new Ending(j.path("state").asText("done"), at, 0, 0, j.path("key").asText("")));
                        } catch (IOException | RuntimeException e) { /* half written: the next round */ }
                    }
                } catch (IOException e) { /* read next round */ }
            }
            long soonest = Long.MAX_VALUE;
            for (Map.Entry<String, Ending> e : new TreeMap<>(PENDING).entrySet()) {
                Ending d = e.getValue();
                long now = System.currentTimeMillis();
                if (d.next() > now) { soonest = Math.min(soonest, d.next()); continue; }
                if (told(e.getKey(), d.state(), d.at(), d.key())) {
                    PENDING.remove(e.getKey(), d);   // unless the same use ended again meanwhile
                    if (dir != null) { try { Files.deleteIfExists(dir.resolve(e.getKey() + ".json")); } catch (IOException ignored) { } }
                } else {
                    long waited = Math.min(RETRY_MOST_MS, d.waited() <= 0 ? RETRY_MS : d.waited() * 2);
                    Ending again = new Ending(d.state(), d.at(), System.currentTimeMillis() + waited, waited, d.key());
                    PENDING.replace(e.getKey(), d, again);
                    soonest = Math.min(soonest, again.next());
                }
            }
            long sleep = soonest == Long.MAX_VALUE ? 60_000 : Math.max(1, Math.min(60_000, soonest - System.currentTimeMillis()));
            try { synchronized (BELL) { BELL.wait(sleep); } }
            catch (InterruptedException ie) { return; }
        }
    }

    /** One end told to the service at {@code at}: whether it took it. */
    private static boolean told(String use, String state, String at, String key) {
        if (at == null) return false;
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(at + Uses.END)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Statement.M.createObjectNode().put("use", use).put("state", state).put("key", key).toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() == 200) KEYS.remove(use);
            return r.statusCode() == 200;
        } catch (IOException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    /** A use the service let start: its id, the most it may spend (dollars, minutes; -1 for no limit), and what the person is told. */
    record Granted(String id, double ceilingDollars, long ceilingMinutes, String said) { }

    /** What the person is told when the service could not be asked: nothing starts. */
    static String couldNotAsk() { return Pages.t("The service could not check this account's spending just now, so nothing was started. Ask again in a minute."); }

    /**
     * Asks before a use. Null in a library of one's own. In a library under a service: the use it may make, or an error that carries
     * the service's sentence (budget_exceeded), or one that says the service could not be asked (nothing starts either way).
     */
    static Granted start(LibraryStore store, String kind, String entry, double estimateDollars, double estimateMinutes, String job) {
        return start(store, kind, entry, estimateDollars, estimateMinutes, job, Vouching.CURRENT.get());
    }

    /** {@link #start}, under a statement taken earlier: the request's, for a call its work makes on another thread. */
    static Granted start(LibraryStore store, String kind, String entry, double estimateDollars, double estimateMinutes, String job, String statement) {
        if (!Hosted.on(store)) return null;
        String at = ServiceAccess.service;
        if (statement == null || at == null) throw ProtocolError.unavailable(couldNotAsk());
        ObjectNode body = Statement.M.createObjectNode();
        body.put("kind", kind); body.put("entry", entry == null ? "" : entry); body.put("estimate_dollars", Math.max(0, estimateDollars)); body.put("estimate_minutes", Math.max(0, Math.round(estimateMinutes)));
        body.put("job", job == null ? "" : job);
        body.put("lang", Chrome.now().code);   // what the person is told is said in their language
        JsonNode j;
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(at + Uses.START)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header(Statement.HEADER, statement)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) { LOG.warn("The service answered {} when asked for a {}: {}", r.statusCode(), kind, r.body()); throw ProtocolError.unavailable(couldNotAsk()); }
            j = Statement.M.readTree(r.body());
        } catch (IOException e) { LOG.warn("The service could not be asked for a {}: {}", kind, e.getMessage()); throw ProtocolError.unavailable(couldNotAsk()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw ProtocolError.unavailable(couldNotAsk()); }
        if (!"open".equals(j.path("state").asText())) throw ProtocolError.budgetExceeded(j.path("said").asText(couldNotAsk()));
        if (!j.path("key").asText("").isEmpty()) KEYS.put(j.path("use").asText(), j.path("key").asText());
        return new Granted(j.path("use").asText(), j.path("ceiling_dollars").asDouble(-1), j.path("ceiling_minutes").asLong(-1), j.path("said").asText(""));
    }

    /** The month of the account this request is, as the service gives it (its rows, caps, what is left, the words); a library under a service only. */
    static JsonNode accountMonth() {
        String statement = Vouching.CURRENT.get(), at = ServiceAccess.service;
        String couldNot = Pages.t("The service could not say this account's month just now. Ask again in a minute.");
        if (statement == null || at == null) throw ProtocolError.unavailable(couldNot);
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(at + AccountService.USAGE_INTERNAL)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header(Statement.HEADER, statement)
                    .POST(HttpRequest.BodyPublishers.ofString(Statement.M.createObjectNode().put("lang", Chrome.now().code).toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) { LOG.warn("The service answered {} when asked for the account's month: {}", r.statusCode(), r.body()); throw ProtocolError.unavailable(couldNot); }
            return Statement.M.readTree(r.body());
        } catch (IOException e) { throw ProtocolError.unavailable(couldNot); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw ProtocolError.unavailable(couldNot); }
    }

    /** Whether a use has reached its cap, as the person is told it; null while it has not, or when the service cannot be asked. */
    static String over(String use) { return over(use, 0); }

    /** The same, saying when the work began to run ({@code startedMs}, 0: unknown): its minutes are counted from then. */
    static String over(String use, long startedMs) {
        String at = ServiceAccess.service;
        if (use == null || use.isBlank() || at == null) return null;
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(at + Uses.STATE)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Statement.M.createObjectNode().put("use", use).put("key", keyOf(use)).put("lang", Chrome.now().code).put("started_ms", startedMs).toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) return null;
            JsonNode j = Statement.M.readTree(r.body());
            return j.path("over").asBoolean(false) ? j.path("said").asText("") : null;
        } catch (IOException e) { return null; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }

    /**
     * Tells the service a use ended ({@code done}, {@code stopped}, {@code failed}): kept in the library's folder first, so a library that
     * stops before the service has taken it tells it again at its next start, and told until the service takes it.
     */
    static void end(String use, String state) {
        String at = ServiceAccess.service;
        if (use == null || !use.matches("[A-Za-z0-9_-]{1,64}") || at == null) return;
        String key = keyOf(use);
        PENDING.put(use, new Ending(state, at, 0, 0, key));   // told at once, to the service that let it start
        Path dir = ENDING.get();
        if (dir != null) {
            try {
                Files.createDirectories(dir);
                Path half = dir.resolve(use + ".json.half");
                Files.writeString(half, Statement.M.createObjectNode().put("use", use).put("state", state).put("key", key).toString(), StandardCharsets.UTF_8);
                Files.move(half, dir.resolve(use + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) { LOG.warn("The end of the use {} could not be kept in the library's folder ({}); it is told from memory.", use, e.getMessage()); }
        }
        resume();
    }

    /**
     * The use a call that costs is part of, and its lane (0.8.0, stage 3, step 2), for the call to carry to the node's gate: the work
     * this thread does (a job, a chat turn: its purse says), else the request this thread answers, whose use is asked for at its
     * first call that costs (a page or a program's call that asks the model outside any run). Null when there is neither.
     */
    static String[] context() {
        DriveClient.Purse p = DriveClient.purse();
        String lang = Chrome.now().code;
        if (p != null && p.use() != null && !p.use().isBlank()) return new String[]{p.use(), p.lane(), lang};
        RequestUse r = REQUEST.get();
        return r == null ? null : new String[]{r.id(), Gated.WAITING, lang};
    }

    /** A call that costs with nothing it is part of: said in the library's log with where it came from, once a place. */
    static void unmarked(String url) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder where = new StringBuilder();
        for (StackTraceElement e : st) if (e.getClassName().startsWith("org.researchzosho") && !e.getClassName().contains("Gated") && !e.getClassName().contains("ServiceUses")) { if (where.length() > 0) where.append(" < "); where.append(e.getClassName().replaceAll(".*\\.", "")).append('.').append(e.getMethodName()); if (where.length() > 300) break; }
        if (SAID.add(where.toString())) LOG.warn("A call that costs, with nothing the service let start going on this thread ({}): {}", Thread.currentThread().getName(), where);
    }
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    /**
     * The use this thread's requests to the web are part of, for the node's web gate to count them against: the work's, or the
     * request's when it has asked for one already. None is asked for to fetch a page: a fetch with none is counted as the library's own.
     */
    static String webUse() {
        DriveClient.Purse p = DriveClient.purse();
        if (p != null && p.use() != null && !p.use().isBlank()) return p.use();
        RequestUse r = REQUEST.get();
        return r == null ? null : r.started();
    }

    /** {@code work} on this thread as part of {@code use} (a page added by its address): what it fetches and asks counts against that use. */
    static <T> T under(Granted use, Callable<T> work) throws Exception {
        return Gated.under(use == null ? null : use.id(), Gated.WAITING, Chrome.now().code, work);
    }

    /** What this thread's work is, to hand to a thread doing part of it: the work's use, or the request's (asked for there, if at all). */
    static Supplier<String[]> capture() {
        DriveClient.Purse p = DriveClient.purse();
        String lang = Chrome.now().code;
        if (p != null && p.use() != null && !p.use().isBlank()) { String[] c = {p.use(), p.lane(), lang}; return () -> c; }
        Supplier<String[]> carried = Gated.CARRIED.get();
        if (carried != null) return carried;
        RequestUse r = REQUEST.get();
        return r == null ? null : () -> new String[]{r.id(), Gated.WAITING, lang};
    }

    /**
     * The use of one request, asked for at its first call that costs, told ended when the request is answered. It keeps the request's
     * statement, so a call on another thread doing part of the request's work asks under it.
     */
    static final class RequestUse {
        private final LibraryStore store;
        private final String statement = Vouching.CURRENT.get();
        private String id;
        RequestUse(LibraryStore store) { this.store = store; }
        synchronized String id() {
            if (id == null) { Granted g = start(store, REQUEST_KIND, entryName(""), 0, 0, "", statement); id = g == null ? "" : g.id(); }   // a no is the service's sentence, thrown
            return id;
        }
        synchronized void end() { if (id != null && !id.isEmpty()) ServiceUses.end(id, "done"); }
        /** The use's id when it has been asked for already; null when not. */
        synchronized String started() { return id == null || id.isEmpty() ? null : id; }
    }

    /** The kind of use a request's own calls are asked for under: a page's or a program's call outside any run, which is not a job (BILL 8). */
    static final String REQUEST_KIND = "page";

    /** The request this thread answers, in a library under a service. */
    static final ThreadLocal<RequestUse> REQUEST = new ThreadLocal<>();

    /**
     * {@code work}, which a request starts and which goes on after the request is answered (an explanation or a sharpened question the
     * page asks for again until it is there), with a use of its own: asked for under the request's statement at its first call that
     * costs, told ended when the work ends. In a library of one's own, {@code work} as it is.
     */
    static Runnable beside(LibraryStore store, Runnable work) {
        if (store == null || !Hosted.on(store)) return work;
        RequestUse own = new RequestUse(store);
        return () -> {
            RequestUse had = REQUEST.get();
            REQUEST.set(own);
            try { work.run(); }
            finally { own.end(); if (had == null) REQUEST.remove(); else REQUEST.set(had); }
        };
    }

    /** The estimate for a use of this kind on the library's model entry: dollars and minutes, from this library's own history first. */
    static double[] estimate(LibraryStore store, String entryName, String kind) {
        ModelEntries.Entry e = entryName == null || entryName.isBlank() ? ModelEntries.defaultEntry() : ModelEntries.get(entryName);
        if (e == null) e = ModelEntries.defaultEntry();
        if (e == null) return new double[]{0, 0};
        Auto.Candidate c = Auto.candidate(store, e, new Auto.Job(kind.equals(SpendPurse.CHAT) ? "chat" : "research", "", kind.equals(SpendPurse.CHAT), 0));
        return new double[]{c.dollars(), c.minutes()};
    }

    /** The name of the model entry a use runs on: the one named, else the library's default. */
    static String entryName(String named) {
        if (named != null && !named.isBlank()) return named;
        ModelEntries.Entry e = ModelEntries.defaultEntry();
        return e == null ? "" : e.name();
    }
}
