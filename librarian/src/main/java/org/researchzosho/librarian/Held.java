package org.researchzosho.librarian;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * A hosted library's own side of its lease (0.8.0). The account service's registry says which node holds a library and counts its
 * starts; that count, the epoch, is given to the library when it is started and is carried by every statement made for it. The node
 * says "still yours" every few seconds ({@code POST /internal/held}) and gets back what the library is doing, from which it decides
 * whether the library has anything to do. A library that has not heard it for the length of a lease, or that meets a statement of a
 * later start, has lost the lease: it takes no more requests, leaves its jobs as they stand for the next holder, and ends.
 *
 * <p>What counts as use is every request but the ones a page or the service sends by itself: the health check, the service's own
 * calls, and the runs strip an open chat page asks for once a minute for as long as its tab is open.
 */
final class Held {
    /** How long a library goes on without hearing "still yours" before it takes the lease for lost; a test shortens it. */
    static volatile long LEASE_MS = Long.getLong("researchzosho.accounts.lease_ms", 30_000);   // the property: a test's, given to the node and handed to its libraries

    /** The wall clock; a test stands in for a machine that slept. */
    static volatile LongSupplier WALL = System::currentTimeMillis;

    final long epoch;
    private volatile long heard = System.nanoTime(), heardWall = WALL.getAsLong();
    /** The requests being answered now, the quiet ones left out. */
    final AtomicInteger open = new AtomicInteger();
    private volatile long used = System.currentTimeMillis();

    Held(long epoch) { this.epoch = epoch; }

    /** The node said the library is still its own (it says so only after the lease was renewed). */
    void heard() { heard = System.nanoTime(); heardWall = WALL.getAsLong(); }

    /**
     * Whether the node has been silent too long to be sure the lease is still this start's (0.8.0 review, ROB 8): two thirds of a lease,
     * so the library stops before the store's lease, renewed when it last heard, can run out and be taken by another machine. Both
     * clocks count: the monotonic one, which a change of the wall clock does not move, and the wall clock, which goes on while a
     * machine sleeps and its monotonic clock does not.
     */
    boolean silent() {
        long fence = LEASE_MS * 2 / 3;
        return (System.nanoTime() - heard) / 1_000_000 > fence || WALL.getAsLong() - heardWall > fence;
    }

    /** A request that counts as use begins or ends. */
    void used() { used = System.currentTimeMillis(); }

    /** How long ago the last request that counts as use began or ended. */
    long idleMs() { return Math.max(0, System.currentTimeMillis() - used); }

    /** A request a page or the service sends by itself: it neither keeps a library awake nor wakes one. */
    static boolean quiet(String path) { return path.equals("/healthz") || path.equals(RUNS_STRIP) || path.startsWith(INTERNAL); }

    /** The runs strip of the chat page, asked for by the page's own script once a minute while its tab is open. */
    static final String RUNS_STRIP = "/chat/runs";
    /** The paths that are the account service's own to call. */
    static final String INTERNAL = "/internal/", STILL_YOURS = "/internal/held", NIGHT = "/internal/night";
}
