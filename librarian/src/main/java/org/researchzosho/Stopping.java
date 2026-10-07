package org.researchzosho;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A stop a person asked for, seen by every wait inside the work it is for. A research run used to look at its stop only between two
 * turns, so a request to the model that never came back kept the run going for hours after the person stopped it (six hours, on the
 * owner's library, 2026-09-24). Now the work runs {@link #within} its stop, and every request it makes waits through {@link #send}
 * or {@link #await}: the wait looks at the stop four times a second, and when it is asked for, the request is cancelled, its connection
 * closed, and {@link Requested} is thrown. The same wait has a total time limit that holds even when a server took the request and never
 * answers, which the HTTP client's own limit does not do once the server has begun its answer.
 *
 * <p>The stop belongs to the thread doing the work; a thread the work starts carries it over with {@link #carried}.
 */
public final class Stopping {

    private Stopping() { }

    /** How often a wait looks whether the work was stopped, in milliseconds. */
    static final long LOOK_MS = 250;

    private static final ThreadLocal<BooleanSupplier> CURRENT = new ThreadLocal<>();

    /** Thrown where a wait ended because a person asked for the work to stop. */
    public static class Requested extends RuntimeException {
        public Requested() { super("stopped, because a person asked for this run to stop"); }
        public Requested(String message) { super(message); }
    }

    /** The stop the work on this thread is under; one that is never asked for when there is none. */
    public static BooleanSupplier current() { BooleanSupplier s = CURRENT.get(); return s == null ? () -> false : s; }

    /** Whether a person asked for the work on this thread to stop. */
    public static boolean requested() {
        BooleanSupplier s = CURRENT.get();
        if (s == null) return false;
        try { return s.getAsBoolean(); } catch (RuntimeException e) { return false; }
    }

    /** Throws {@link Requested} when the work on this thread was asked to stop. */
    public static void check() { if (requested()) throw new Requested(); }

    /** The stops this thread works under, as {@link #within} was given them: what {@link #threadsUnder} finds the thread by. */
    private static final ThreadLocal<List<BooleanSupplier>> UNDER = new ThreadLocal<>();
    /** The threads working under each stop now, the ones the work started among them. */
    private static final Map<BooleanSupplier, Set<Thread>> WORKING = new ConcurrentHashMap<>();

    /** {@code work} under {@code stop}, and under the stop this thread was under already: either one stops it. */
    public static <T> T within(BooleanSupplier stop, Supplier<T> work) {
        BooleanSupplier outer = CURRENT.get();
        BooleanSupplier both = stop == null ? outer : outer == null ? stop : () -> stop.getAsBoolean() || outer.getAsBoolean();
        List<BooleanSupplier> had = UNDER.get();
        List<BooleanSupplier> keys = new ArrayList<>(had == null ? List.of() : had);
        if (stop != null) keys.add(stop);
        CURRENT.set(both);
        UNDER.set(keys);
        Thread me = Thread.currentThread();
        if (stop != null) WORKING.computeIfAbsent(stop, k -> ConcurrentHashMap.newKeySet()).add(me);
        try { return work.get(); }
        finally {
            if (outer == null) CURRENT.remove(); else CURRENT.set(outer);
            if (had == null) UNDER.remove(); else UNDER.set(had);
            if (stop != null) leave(stop, me);
        }
    }

    private static void leave(BooleanSupplier stop, Thread t) { WORKING.computeIfPresent(stop, (k, threads) -> { threads.remove(t); return threads.isEmpty() ? null : threads; }); }

    /** The threads working under {@code stop} now: the one {@link #within} runs on, and those it started with {@link #carried}. */
    public static List<Thread> threadsUnder(BooleanSupplier stop) {
        Set<Thread> t = stop == null ? null : WORKING.get(stop);
        return t == null ? List.of() : List.copyOf(t);
    }

    /** Where each thread waits, as a thread dump writes it: its name and state, then its calls, the innermost first. */
    public static String dump(Collection<Thread> threads) {
        StringBuilder b = new StringBuilder();
        for (Thread t : threads) {
            b.append('"').append(t.getName()).append("\" ").append(t.getState()).append('\n');
            for (StackTraceElement f : t.getStackTrace()) b.append("    at ").append(f).append('\n');
        }
        return b.toString();
    }

    /** The same for work that returns nothing. */
    public static void within(BooleanSupplier stop, Runnable work) { within(stop, () -> { work.run(); return null; }); }

    /** {@code work}, to be run on another thread under the stop this thread is under now. */
    public static <T> Callable<T> carried(Callable<T> work) {
        BooleanSupplier stop = CURRENT.get();
        if (stop == null) return work;
        List<BooleanSupplier> keys = UNDER.get() == null ? List.of() : List.copyOf(UNDER.get());
        return () -> {
            BooleanSupplier had = CURRENT.get();
            List<BooleanSupplier> hadKeys = UNDER.get();
            CURRENT.set(stop);
            UNDER.set(keys);
            Thread me = Thread.currentThread();
            for (BooleanSupplier k : keys) WORKING.computeIfAbsent(k, x -> ConcurrentHashMap.newKeySet()).add(me);
            try { return work.call(); }
            finally {
                if (had == null) CURRENT.remove(); else CURRENT.set(had);
                if (hadKeys == null) UNDER.remove(); else UNDER.set(hadKeys);
                for (BooleanSupplier k : keys) leave(k, me);
            }
        };
    }

    /**
     * One HTTP request, waited for until its answer is complete, the work is stopped, or {@code limit} has passed, whichever comes first.
     * {@code what} names the other side in the message when the limit passes ("the model server at http://…"). A request with a time limit
     * of its own is given up by the HTTP client when that passes and no answer has begun; that says so in the same words, since the client's
     * own text is "request timed out". Which of the two limits comes first is a matter of milliseconds when they are the same: on Linux the
     * wait here, on macOS mostly the client's, because a timed wait there wakes a few milliseconds late.
     */
    public static <T> HttpResponse<T> send(HttpClient http, HttpRequest request, HttpResponse.BodyHandler<T> body, Duration limit, String what)
            throws IOException, InterruptedException {
        check();
        long started = System.nanoTime();
        try { return await(http.sendAsync(request, body), limit, what, request.timeout().orElse(null)); }
        catch (IOException e) {
            // the pooled connection had been closed by the server while it was idle: nothing of the request was answered, so it goes
            // once more, on a fresh connection, within what is left of the limit (HttpSettings says why this happens)
            if (!staleConnection(e)) throw e;
            STALE_RETRIES.incrementAndGet();
            Duration left = limit.minus(Duration.ofNanos(System.nanoTime() - started));
            if (left.isNegative() || left.isZero()) throw e;
            return await(http.sendAsync(request, body), left, what, request.timeout().orElse(null));
        }
    }

    /** Requests sent again because the pooled connection they first went down was dead (tests read it). */
    public static final AtomicLong STALE_RETRIES = new AtomicLong();

    /**
     * A failure that means the server had closed the pooled connection while it was idle, so the request never reached it. A request
     * the server took and is slow on (a timeout) is not this, and is never sent twice.
     */
    public static boolean staleConnection(IOException e) {
        if (e instanceof HttpTimeoutException) return false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().equals("ConnectionExpiredException")) return true;
            String m = t.getMessage() == null ? "" : t.getMessage();
            if (m.contains("header parser received no bytes") || m.contains("connection closed locally") || m.contains("Connection reset")
                    || m.contains("Broken pipe") || m.contains("EOF reached while reading")) return true;
        }
        return false;
    }

    /**
     * Waits for {@code f}. The work stopped: {@code f} is cancelled (a request's connection is closed) and {@link Requested} is thrown. The
     * limit passed: {@code f} is cancelled and an {@link HttpTimeoutException} is thrown, which every caller already takes for a request
     * that did not get through. A failure of {@code f} itself is thrown as it is.
     */
    public static <T> T await(CompletableFuture<T> f, Duration limit, String what) throws IOException, InterruptedException { return await(f, limit, what, null); }

    /** The same, for a request whose own time limit is {@code own} (null: none): the client's giving it up is said as the limit passing here is. */
    private static <T> T await(CompletableFuture<T> f, Duration limit, String what, Duration own) throws IOException, InterruptedException {
        long end = System.nanoTime() + limit.toNanos();
        while (true) {
            if (requested()) { f.cancel(true); throw new Requested(); }
            long left = end - System.nanoTime();
            if (left <= 0) { f.cancel(true); throw unanswered(what, limit, null); }
            try { return f.get(Math.min(TimeUnit.MILLISECONDS.toNanos(LOOK_MS), left), TimeUnit.NANOSECONDS); }
            catch (TimeoutException notYet) { /* look again */ }
            catch (InterruptedException e) { f.cancel(true); throw e; }
            catch (ExecutionException e) {
                Throwable c = e.getCause();
                // a connection never made stays what it is: callers tell a server that is down from one that is slow by it
                if (own != null && c instanceof HttpTimeoutException t && !(t instanceof HttpConnectTimeoutException)) throw unanswered(what, own, t);
                if (c instanceof IOException io) throw io;
                if (c instanceof RuntimeException r) throw r;
                if (c instanceof Error er) throw er;
                throw new IOException(c);
            }
        }
    }

    /** A request that got no answer within {@code limit}, said in words a person reads; {@code client} is the HTTP client's own failure, when it gave the request up. */
    private static HttpTimeoutException unanswered(String what, Duration limit, HttpTimeoutException client) {
        HttpTimeoutException t = new HttpTimeoutException(what + " gave no answer within " + said(limit) + ", so the request was given up and its connection closed");
        if (client != null) t.initCause(client);
        return t;
    }

    /** A limit as a sentence says it: "300 seconds", "2 minutes". */
    static String said(Duration d) {
        long s = Math.max(1, d.toSeconds());
        return s % 60 == 0 && s >= 120 ? s / 60 + " minutes" : s == 1 ? "1 second" : s + " seconds";
    }

    /** Sleeps {@code ms}, and throws {@link Requested} as soon as the work is stopped. */
    public static void sleep(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        for (long left = ms; left > 0; left = end - System.currentTimeMillis()) {
            check();
            Thread.sleep(Math.min(LOOK_MS, left));
        }
        check();
    }

    /**
     * Watches a stream being read: {@code c} is closed when the work is stopped or {@code limit} has passed, so that a read which waits for
     * a server that sends nothing more ends. Close the guard when the reading is done; then ask it why it closed the stream, if it did.
     */
    public static Guard guard(Closeable c, Duration limit) {
        Guard g = new Guard(c, CURRENT.get(), System.nanoTime() + limit.toNanos(), limit);
        GUARDS.add(g);
        startWatch();
        return g;
    }

    /**
     * Watches a body that may come slowly but steadily, such as a scanned register from an archive's server: the reading ends when the work
     * is stopped, when nothing has come for {@code idle}, or when the body comes slower than {@code bytesPerSecond} on average. At any
     * moment the reading may have taken {@code idle} plus the time the bytes read so far take at that speed, and never more than {@code
     * idle} plus {@code most} bytes at it. A server that keeps the connection open and sends nothing is given up after {@code idle}. Read
     * through {@link Guard#stream}; close the guard when the reading is done, then ask it why it ended the reading, if it did.
     */
    public static Guard body(InputStream in, Duration idle, long bytesPerSecond, long most) {
        Guard g = new Guard(in, CURRENT.get(), System.nanoTime() + idle.toNanos(), idle);
        g.paced(in, bytesPerSecond, most);
        GUARDS.add(g);
        startWatch();
        return g;
    }

    /** A stream under watch ({@link #guard}). */
    public static final class Guard implements AutoCloseable {
        private final Closeable stream;
        private final BooleanSupplier stop;
        private final long end, start = System.nanoTime();
        private final Duration limit;
        private volatile String fired = "";
        // a paced body ({@link #body}): what has come, when it last came, and the slowest pace allowed
        private InputStream counted;
        private final AtomicLong bytes = new AtomicLong();
        private volatile long lastByte = System.nanoTime();
        private long rate, most;

        private Guard(Closeable stream, BooleanSupplier stop, long end, Duration limit) { this.stream = stream; this.stop = stop; this.end = end; this.limit = limit; }

        private void paced(InputStream in, long bytesPerSecond, long mostBytes) {
            this.rate = Math.max(1, bytesPerSecond); this.most = Math.max(0, mostBytes);
            this.counted = new FilterInputStream(in) {
                @Override public int read() throws IOException { int b = super.read(); if (b >= 0) came(1); return b; }
                @Override public int read(byte[] buf, int off, int len) throws IOException { int n = super.read(buf, off, len); if (n > 0) came(n); return n; }
                @Override public long skip(long n) throws IOException { long k = super.skip(n); if (k > 0) came(k); return k; }
            };
        }

        private void came(long n) { bytes.addAndGet(n); lastByte = System.nanoTime(); }

        /** The body to read, counted as it comes; for a guard of {@link #guard}, the stream it watches, when that is one. */
        public InputStream stream() { return counted != null ? counted : stream instanceof InputStream in ? in : null; }

        /** Whether the guard closed the stream because the work was stopped. */
        public boolean stopped() { return fired.equals("stopped"); }
        /** Whether the guard closed the stream because a limit passed: the whole limit, nothing coming for a while, or a body too slow. */
        public boolean timedOut() { return fired.equals("limit") || fired.equals("idle") || fired.equals("slow"); }

        /** Throws what the reading ended on, when the guard ended it: {@link Requested}, or the limit as an {@link HttpTimeoutException}. */
        public void rethrow(String what) throws HttpTimeoutException {
            if (stopped()) throw new Requested();
            if (fired.equals("idle")) throw new HttpTimeoutException(what + " sent nothing for " + said(limit) + " in the middle of its answer, so the reading was given up and its connection closed");
            if (fired.equals("slow")) throw new HttpTimeoutException(what + " sent its answer too slowly: " + bytes.get() + " bytes in " + said(Duration.ofNanos(System.nanoTime() - start))
                    + ", less than " + rate + " bytes a second, so the reading was given up and its connection closed");
            if (timedOut()) throw new HttpTimeoutException(what + " did not finish its answer within " + said(limit) + ", so the reading was given up and its connection closed");
        }

        void look() {
            if (!fired.isEmpty()) return;
            boolean stopNow;
            try { stopNow = stop != null && stop.getAsBoolean(); } catch (RuntimeException e) { stopNow = false; }
            long now = System.nanoTime();
            if (stopNow) fired = "stopped";
            else if (counted == null && now - end >= 0) fired = "limit";
            else if (counted != null && now - lastByte >= limit.toNanos()) fired = "idle";
            else if (counted != null && now - (start + limit.toNanos() + Math.min(bytes.get(), most) * 1_000_000_000L / rate) >= 0) fired = "slow";
            else return;
            GUARDS.remove(this);
            try { stream.close(); } catch (IOException | RuntimeException ignored) { }
        }

        @Override public void close() { GUARDS.remove(this); }
    }

    private static final Set<Guard> GUARDS = ConcurrentHashMap.newKeySet();
    private static volatile ScheduledExecutorService watch;

    private static void startWatch() {
        if (watch != null) return;
        synchronized (Stopping.class) {
            if (watch != null) return;
            ScheduledExecutorService w = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "stop-watch"); t.setDaemon(true); return t; });
            w.scheduleWithFixedDelay(() -> { for (Guard g : GUARDS) g.look(); }, LOOK_MS, LOOK_MS, TimeUnit.MILLISECONDS);
            watch = w;
        }
    }
}
