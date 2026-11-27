package org.researchzosho.librarian;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A look at whether another server answers, made off the request that wants to know, its result kept (0.8.4). A request answers from
 * the last look at once; when that look is older than its interval, or there is none, a new one starts on a thread of its own and the
 * request still answers with what was known. Requests that come while a look is under way start nothing more.
 *
 * <p>Measured on 2026-11-26: the status made its look at the embeddings server while the request waited, at most once a minute, so with
 * that server down the first status of each minute took 6.2 seconds (the next ones 0.04), and a program that gives the status five
 * seconds took a running library for one that was not running.
 *
 * <p>A look may say which of its findings is a failure (the server did not answer). Such a finding is due again after a shorter
 * interval, and while somebody asked within {@link #WANTED_MS} the look is made again after that interval by itself: a person told that
 * a server does not answer, who mends it and asks again a few seconds later, is told what the library finds then (Help, 0.8.4).
 */
final class KeptLook<T> {
    private static final Logger LOG = LoggerFactory.getLogger(KeptLook.class);

    /** What a look found, and when it ended (milliseconds). */
    record Seen<T>(T value, long at) { }

    /** The clock of every kept look; a test moves it. */
    static volatile LongSupplier NOW = System::currentTimeMillis;

    /** How long after the last ask a look that found a failure goes on being made again by itself. */
    static final long WANTED_MS = 60_000;

    private final String name;
    private final long everyMs, againMs;
    private final Predicate<T> failed;   // null: no finding is a failure
    private final Supplier<T> look;
    private volatile long asked;   // when a request last asked what is known
    private final AtomicBoolean againSoon = new AtomicBoolean();   // one look made again by itself waits at a time
    private volatile Seen<T> seen;   // null: not looked at yet
    private final AtomicReference<CompletableFuture<Seen<T>>> underWay = new AtomicReference<>();
    private final AtomicLong forgotten = new AtomicLong();   // a look begun before a forget() keeps nothing it finds

    /** {@code look} is made at most once per {@code everyMs}, on a thread named {@code name}; it says what it found and does not throw. */
    KeptLook(String name, long everyMs, Supplier<T> look) { this(name, everyMs, everyMs, null, look); }

    /** The same; a finding {@code failed} says is a failure is due again after {@code againMs}, and made again by itself while it is wanted. */
    KeptLook(String name, long everyMs, long againMs, Predicate<T> failed, Supplier<T> look) { this.name = name; this.everyMs = everyMs; this.againMs = againMs; this.failed = failed; this.look = look; }

    private boolean failure(Seen<T> s) { return failed != null && s != null && failed.test(s.value()); }

    /** The last look's result, or null when none has ended; a look started when that one is older than the interval. Never waits. */
    Seen<T> known() {
        Seen<T> s = seen;
        long now = NOW.getAsLong();
        asked = now;
        if (s == null || now - s.at() >= (failure(s) ? againMs : everyMs)) start();
        return s;
    }

    /** Whether a look made again by itself waits to be made; for a test, which waits for what it asserts rather than for time. */
    boolean repeating() { return againSoon.get(); }

    /** What is known, without asking: no look is started and nothing counts as an ask; for a test. */
    Seen<T> last() { return seen; }

    /** A look started on a thread of its own, unless one is under way: the one under way, or the one started. Never waits. */
    CompletableFuture<Seen<T>> start() {
        while (true) {
            CompletableFuture<Seen<T>> f = underWay.get();
            if (f != null) return f;
            CompletableFuture<Seen<T>> mine = new CompletableFuture<>();
            if (!underWay.compareAndSet(null, mine)) continue;
            long round = forgotten.get();
            Thread.ofVirtual().name(name).start(() -> {
                Seen<T> found = null;
                try {
                    found = new Seen<>(look.get(), NOW.getAsLong());
                    if (round == forgotten.get()) seen = found;
                } catch (RuntimeException e) { LOG.debug("{}: the look failed: {}", name, e.toString()); }
                finally { underWay.compareAndSet(mine, null); mine.complete(found); }
                if (failure(found) && round == forgotten.get() && NOW.getAsLong() - asked < WANTED_MS) again(round);
            });
            return mine;
        }
    }

    /** After a look that found a failure, while it is wanted: the next one, {@code againMs} later, unless a forget() came between. */
    private void again(long round) {
        if (!againSoon.compareAndSet(false, true)) return;
        Thread.ofVirtual().name(name + "-again").start(() -> {
            try { Thread.sleep(againMs); } catch (InterruptedException e) { againSoon.set(false); return; }
            againSoon.set(false);
            if (round == forgotten.get()) start();
        });
    }

    /**
     * For a command run once, which knows nothing before it asks: a look started unless one is under way, and waited for up to
     * {@code wait}. What is known then: that look's result, or what was known before when it did not end in time (null for nothing).
     */
    Seen<T> lookAndWait(Duration wait) {
        try { start().get(wait.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { /* not ended in time: what was known before */ }
        return seen;
    }

    /** Forgets what was found: the next request starts a new look, and a look under way keeps nothing it finds. */
    void forget() { forgotten.incrementAndGet(); seen = null; underWay.set(null); }

    /** Waits up to {@code wait} for the look under way, if any, to end; for a test, so that no look of an earlier test runs into the next one. */
    void settle(Duration wait) {
        CompletableFuture<Seen<T>> f = underWay.get();
        if (f == null) return;
        try { f.get(wait.toMillis(), TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
    }
}
