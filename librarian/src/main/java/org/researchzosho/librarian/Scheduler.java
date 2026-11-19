package org.researchzosho.librarian;

import org.researchzosho.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * The account service's scheduler (0.8.0, placement): what a library does by the clock is started from here, so a library that is
 * asleep still has its night, and the nights are spread instead of all beginning at 03:00. Each library has a minute of its own in
 * the four hours from 01:00 by the service's clock, the same every night; at that minute, or as soon after it as fewer nights run
 * than the setting allows (two), the library is started if it sleeps and told to run its nightly tasks. A night missed because the
 * service was not running at that minute is made up the same day, when a place is free; a library made after its minute had passed
 * has its first night the next day. A library whose own work is
 * due (research held until its hours open) is started at that hour. Of several processes of the service one does this at a time: it
 * holds the duty {@code scheduler} in the store.
 */
public final class Scheduler {
    private static final Logger LOG = LoggerFactory.getLogger(Scheduler.class);
    static final String DUTY = "scheduler";
    /** Whether hosted libraries have nights at all: {@code off} for none (the model is the operator's to pay for). Unset: they do. */
    public static final String NIGHTS = "RESEARCHZOSHO_ACCOUNTS_NIGHTS";
    /** How many libraries' nights run at once across the service. Unset: 2. */
    public static final String AT_ONCE = "RESEARCHZOSHO_ACCOUNTS_NIGHTS_AT_ONCE";
    /** The first minute of the day a night begins at (01:00), and how many minutes the libraries' own minutes are spread over (four hours). */
    static final int FIRST_MINUTE = 60, MINUTES = 240;
    /** How often it looks; a test shortens it. */
    static volatile long ROUND_MS = Long.getLong("researchzosho.accounts.round_ms", 60_000);

    /** What the scheduler asks of whoever runs the libraries: the node on one machine. */
    interface Libraries {
        /** The library's nightly tasks started, the library first when it sleeps. */
        void night(AccountStore.Library lib) throws IOException;
        /** A sleeping library started, for work of its own that is due. */
        void wake(AccountStore.Library lib) throws IOException;
        boolean awake(String libraryId);
        /** What the library last said it is doing; null when it sleeps. */
        Node.State state(String libraryId);
        /** The scheduler says when a library's night is next due, so the library is not stopped minutes before it. */
        void nextNight(ToLongFunction<String> at);
        /** The libraries that were awake on a machine that fell silent, started elsewhere. */
        default List<String> takeOver() throws IOException { return List.of(); }
    }

    private final AccountStore store;
    private final Libraries node;
    private final String holder;
    private final Supplier<ZonedDateTime> clock;
    /** The nights started a moment ago, which the libraries have not yet been heard to say they run. */
    private final Map<String, Long> begun = new ConcurrentHashMap<>();
    private volatile Thread thread;
    private volatile boolean stopping;

    public Scheduler(AccountStore store, Libraries node, String holder, Supplier<ZonedDateTime> clock) {
        this.store = store; this.node = node; this.holder = holder; this.clock = clock;
        node.nextNight(this::nextNight);
    }

    public static boolean nightsOn() { String v = Config.get(NIGHTS); return v == null || v.isBlank() || !(v.strip().equalsIgnoreCase("off") || v.strip().equalsIgnoreCase("no") || v.strip().equals("0")); }
    public static int atOnce() { return Math.max(1, Math.min(64, Config.getInt(AT_ONCE, 2))); }

    /** A library's own minute of the day, the same every night: spread over the four hours from 01:00 by its id. */
    static int minuteOf(String libraryId) { return FIRST_MINUTE + Math.floorMod(libraryId.hashCode(), MINUTES); }

    /** When a library's night is next due, in milliseconds of the clock; 0 when hosted libraries have no nights. */
    long nextNight(String libraryId) { return nextNight(store, libraryId, clock.get()); }

    /** The same, for whoever holds the library: a node does not put it to sleep minutes before its night. */
    static long nextNight(AccountStore store, String libraryId, ZonedDateTime now) {
        if (!nightsOn()) return 0;
        ZonedDateTime at = now.toLocalDate().atStartOfDay(now.getZone()).plusMinutes(minuteOf(libraryId));
        try {
            AccountStore.Placement p = store.placement(libraryId);
            if (p != null && p.nightDay().equals(now.toLocalDate().toString())) at = at.plusDays(1);   // tonight's is done
        } catch (IOException e) { /* today's minute it is */ }
        if (!at.isAfter(now)) at = at.plusDays(1);   // a night whose minute has passed is started by the scheduler, asleep or awake: nothing to stay up for
        return at.toInstant().toEpochMilli();
    }

    public void start() {
        Thread t = new Thread(() -> {
            while (!stopping) {
                try { Thread.sleep(ROUND_MS); } catch (InterruptedException e) { return; }
                try { round(); } catch (IOException | RuntimeException e) { LOG.warn("The scheduler's round failed: {}", e.toString()); }   // the next round is a minute away: nothing spins
            }
        }, "accounts-scheduler");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public void stop() { stopping = true; Thread t = thread; if (t != null) t.interrupt(); }

    /** One look at the clock: the nights that are due and have a place, and the sleeping libraries whose own work is due. What it started, a line each. */
    public List<String> round() throws IOException {
        List<String> did = new ArrayList<>();
        if (!store.duty(DUTY, holder, ROUND_MS * 3)) return did;   // another process of the service is the scheduler now
        ZonedDateTime now = clock.get();
        String today = now.toLocalDate().toString();
        int minute = now.getHour() * 60 + now.getMinute();
        List<AccountStore.Placement> all = store.placements();
        if (nightsOn()) {
            long t = System.currentTimeMillis();
            begun.values().removeIf(at -> t - at > Node.RENEW_MS * 3);
            begun.keySet().removeIf(id -> { Node.State heard = node.state(id); return heard != null && heard.night(); });   // heard to say so itself: counted by that from now
            int running = 0;
            for (AccountStore.Placement p : all) { Node.State s = node.state(p.id()); if ((s != null && s.night()) || begun.containsKey(p.id())) running++; }
            List<AccountStore.Placement> due = new ArrayList<>();
            for (AccountStore.Placement p : all) {
                if (today.equals(p.nightDay()) || minuteOf(p.id()) > minute) continue;
                if (p.nightDay().isEmpty()) { store.nightStarted(p.id(), today); continue; }   // made today after its minute had passed: its first night is tomorrow's
                due.add(p);
            }
            due.sort(Comparator.comparingInt((AccountStore.Placement p) -> minuteOf(p.id())).thenComparing(AccountStore.Placement::id));
            for (AccountStore.Placement p : due) {
                if (running >= atOnce()) break;   // both places taken: it waits its turn, and goes first at the next round
                AccountStore.Library lib = store.library(p.id());
                if (lib == null || !lib.active()) continue;
                if (!AccountActs.keeperOpen(store, lib)) continue;   // its keeper suspended or being deleted: no night (stage 4)
                try {
                    node.night(lib);
                    store.nightStarted(p.id(), today);
                    begun.put(p.id(), System.currentTimeMillis());
                    running++;
                    did.add("night " + p.id());
                } catch (IOException e) { LOG.warn("The night of {} did not start: {}; it is tried again at the next round.", lib.title(), e.getMessage()); }
            }
        }
        try { did.addAll(node.takeOver()); } catch (IOException e) { LOG.warn("The libraries of a silent machine were not all started elsewhere: {}", e.getMessage()); }
        AccountActs.due(store);     // the deleted accounts whose day has come, removed (stage 4)
        AccountActs.prune(store);   // and the audit log's oldest entries, once a day
        try { int gone = new LibraryPlaces(store).sweep(); if (gone > 0) LOG.info("{} folder(s) and copies set aside for 30 days are removed.", gone); }
        catch (IOException e) { LOG.warn("What was set aside could not all be removed: {}", e.getMessage()); }
        long at = store.clock();
        for (AccountStore.Placement p : all) {
            if (p.dueAt() <= 0 || p.dueAt() > at || node.awake(p.id())) continue;
            AccountStore.Library lib = store.library(p.id());
            if (lib == null || !lib.active() || !AccountActs.keeperOpen(store, lib)) continue;
            try { node.wake(lib); store.due(p.id(), 0); did.add("due " + p.id()); }
            catch (IOException e) { LOG.warn("The library {} did not start for its due work: {}", lib.title(), e.getMessage()); }
        }
        return did;
    }
}
