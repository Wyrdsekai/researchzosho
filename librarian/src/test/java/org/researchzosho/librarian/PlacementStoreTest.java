package org.researchzosho.librarian;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placement in the account store (0.8.0): one row, one holder. A library's lease is taken by one node at a time and each take is the
 * library's next epoch; the holder holds it on, and nobody else takes it until it is given back or runs out. A duty has one holder.
 * And the scheduler on that store: every library's night at its own minute, so many at once and no more, none when the operator
 * turned nights off, a sleeping library started when its own work is due, and one scheduler of several.
 */
class PlacementStoreTest {
    static AccountStore.Library library(AccountStore store, Path tmp, String name) throws Exception {
        AccountStore.Account a = store.createAccount(name, "", false);
        String id = AccountStore.id("lib_");
        store.addLibrary(id, name.toLowerCase().replace(' ', '-'), name, a.id(), tmp.resolve(id).toString());
        return store.library(id);
    }

    @Test
    void aLeaseHasOneHolderAndEachTakeIsTheNextEpoch(@TempDir Path tmp) throws Exception {
        try (AccountStore store = AccountStore.open(tmp.resolve("accounts.db").toString())) { leases(store, tmp, "Ann Example"); assertEquals(1, store.placements().size()); }
    }

    /** The same leases and duties on PostgreSQL, where the clock they are read by is the database's own. Names carry a random tail, so a test database that is not emptied takes them again. */
    @Test
    void theLeasesAndTheDutiesOnPostgres(@TempDir Path tmp) throws Exception {
        String where = System.getenv("RESEARCHZOSHO_TEST_POSTGRES");
        Assumptions.assumeTrue(where != null && !where.isBlank(), "no test database is named (RESEARCHZOSHO_TEST_POSTGRES)");
        String tail = AccountStore.id("");
        try (AccountStore store = AccountStore.open(where); AccountStore other = AccountStore.open(where)) {
            assertFalse(store.sqlite());
            long theirs = store.clock();
            assertTrue(Math.abs(theirs - System.currentTimeMillis()) < 3_600_000, "the database's clock, in milliseconds: " + theirs);
            leases(store, tmp, "Ann Example " + tail);
            duties(store, other, "scheduler " + tail);
        }
    }

    static void leases(AccountStore store, Path tmp, String name) throws Exception {
        {
            String lib = library(store, tmp, name).id();
            AccountStore.Placement fresh = store.placement(lib);
            assertEquals("", fresh.node()); assertEquals(0, fresh.epoch()); assertEquals(0, fresh.leaseUntil()); assertEquals(0, fresh.dueAt()); assertEquals("", fresh.nightDay());
            assertEquals(1, store.take(lib, "node-a", 60_000), "nobody held it: taken, and it is the library's first start");
            AccountStore.Placement held = store.placement(lib);
            assertEquals("node-a", held.node()); assertTrue(held.leaseUntil() > store.clock());
            assertEquals(-1, store.take(lib, "node-b", 60_000), "held by another node: not taken");
            assertEquals(1, store.placement(lib).epoch(), "and nothing of the row changed");
            assertTrue(store.renew(lib, "node-a", 1, 60_000)); assertFalse(store.renew(lib, "node-b", 1, 60_000)); assertFalse(store.renew(lib, "node-a", 2, 60_000), "only this node's own start is held on");
            assertEquals(2, store.take(lib, "node-a", 60_000), "the holder starts its library again: the next epoch");
            assertFalse(store.renew(lib, "node-a", 1, 60_000), "the earlier start's lease is gone with it");
            store.release(lib, "node-b", 2);
            assertTrue(store.placement(lib).leaseUntil() > 0, "nobody but the holder gives a lease back");
            store.release(lib, "node-a", 2);
            assertEquals(0, store.placement(lib).leaseUntil()); assertEquals("", store.placement(lib).address());
            assertEquals(3, store.take(lib, "node-b", 60_000), "given back: the next to ask takes it");
            assertFalse(store.renew(lib, "node-a", 2, 60_000), "and the old holder finds it is not its own any more");
            // a lease that ran out is anybody's
            assertEquals(4, store.take(lib, "node-b", 1));
            Thread.sleep(20);
            assertEquals(5, store.take(lib, "node-a", 60_000));
            // when the library's own work is due, and the day of its night
            store.due(lib, 1234); store.nightStarted(lib, "2026-11-17");
            assertEquals(1234, store.placement(lib).dueAt()); assertEquals("2026-11-17", store.placement(lib).nightDay());
            assertTrue(store.placements().stream().anyMatch(x -> x.id().equals(lib)));
            // a node writes itself down, twice
            String node = "node-" + AccountStore.id("");
            store.nodeSeen(node, "http://127.0.0.1:1", tmp.toString(), "1.0"); store.nodeSeen(node, "http://127.0.0.1:2", tmp.toString(), "1.1");
        }
    }

    @Test
    void aDutyHasOneHolderUntilItsTimeRunsOut(@TempDir Path tmp) throws Exception {
        try (AccountStore store = AccountStore.open(tmp.resolve("accounts.db").toString()); AccountStore other = AccountStore.open(tmp.resolve("accounts.db").toString())) { duties(store, other, "scheduler"); }
    }

    static void duties(AccountStore store, AccountStore other, String duty) throws Exception {
        assertTrue(store.duty(duty, "one", 60_000));
        assertTrue(store.duty(duty, "one", 60_000), "the holder has it still");
        assertFalse(other.duty(duty, "two", 60_000), "another process of the service does not");
        assertTrue(other.duty(duty + ", another", "two", 60_000), "a duty of another name is another duty");
        assertTrue(store.duty(duty, "one", 1));
        Thread.sleep(50);
        assertTrue(other.duty(duty, "two", 60_000), "the holder's time ran out: the next to ask has it");
        assertFalse(store.duty(duty, "one", 60_000));
    }

    /** Whoever runs the libraries, as the scheduler sees it: which nights it started, which libraries it woke, and what each says it is doing. */
    static final class Recorded implements Scheduler.Libraries {
        final List<String> nights = new ArrayList<>(), woken = new ArrayList<>();
        final Map<String, Node.State> states = new ConcurrentHashMap<>();
        @Override public void night(AccountStore.Library lib) { nights.add(lib.title()); states.put(lib.id(), new Node.State("x", 0, 0, 1, 0, 0, true, false, 0)); }
        @Override public void wake(AccountStore.Library lib) { woken.add(lib.title()); }
        @Override public boolean awake(String libraryId) { return states.containsKey(libraryId); }
        @Override public Node.State state(String libraryId) { return states.get(libraryId); }
        @Override public void nextNight(ToLongFunction<String> at) { }
        void nightEnds(AccountStore.Library lib) { states.put(lib.id(), new Node.State("x", 0, 0, 0, 0, 0, false, false, 0)); }
    }

    static ZonedDateTime at(int day, int hour, int minute) { return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneId.of("UTC")); }

    @Test
    void theNightsAreSpreadTwoAtOnceOnceADayAndNoneWhenTurnedOff(@TempDir Path tmp) throws Exception {
        try (AccountStore store = AccountStore.open(tmp.resolve("accounts.db").toString())) {
            List<AccountStore.Library> libs = new ArrayList<>();
            for (String n : new String[]{"Ann Example", "Ben Example", "Cat Example", "Dan Example", "Eve Example"}) libs.add(library(store, tmp, n));
            // the scheduler's own order: by minute, and two libraries on one minute by their ids (ids are random, so a run can hold a tie)
            libs.sort((x, y) -> { int c = Integer.compare(Scheduler.minuteOf(x.id()), Scheduler.minuteOf(y.id())); return c != 0 ? c : x.id().compareTo(y.id()); });
            for (AccountStore.Library l : libs) { int m = Scheduler.minuteOf(l.id()); assertTrue(m >= 60 && m < 300, "a minute between 01:00 and 05:00: " + m); assertEquals(m, Scheduler.minuteOf(l.id())); }
            Recorded node = new Recorded();
            ZonedDateTime[] now = {at(16, 23, 0)};
            Scheduler s = new Scheduler(store, node, "one", () -> now[0]);
            // made on the 16th at 23:00, after every library's minute: none has a night that day, the first is the next day's
            assertTrue(s.round().isEmpty()); assertTrue(node.nights.isEmpty());
            for (AccountStore.Library l : libs) assertEquals("2026-11-16", store.placement(l.id()).nightDay());
            now[0] = at(17, 0, 30);
            assertTrue(s.round().isEmpty(), "00:30: no library's minute has come");
            int first = Scheduler.minuteOf(libs.get(0).id());
            now[0] = at(17, first / 60, first % 60);
            s.round();
            assertEquals(libs.get(0).title(), node.nights.get(0), "the library whose minute it is has its night");
            assertEquals("2026-11-17", store.placement(libs.get(0).id()).nightDay());
            assertTrue(s.nextNight(libs.get(0).id()) > now[0].toInstant().toEpochMilli() + 20 * 3_600_000L, "its next night is tomorrow's");
            // 06:00: every minute has come. Two nights at once: one more now, the others as places come free, in the order of their minutes
            now[0] = at(17, 6, 0);
            s.round();
            assertEquals(List.of(libs.get(0).title(), libs.get(1).title()), node.nights, "two at once: the second starts, the third waits");
            s.round();
            assertEquals(2, node.nights.size(), "both places taken: nothing more");
            node.nightEnds(libs.get(0));
            s.round();
            assertEquals(List.of(libs.get(0).title(), libs.get(1).title(), libs.get(2).title()), node.nights, "a place came free: the next by its minute");
            s.round();   // the third is heard to run its night; both places are taken again
            assertEquals(3, node.nights.size());
            node.nightEnds(libs.get(1)); node.nightEnds(libs.get(2));
            s.round();
            assertEquals(5, node.nights.size(), "the last two");
            s.round();   // heard to run
            for (AccountStore.Library l : libs) node.nightEnds(l);
            s.round(); s.round();
            assertEquals(5, node.nights.size(), "one night a day each");
            // the next day they are due again; with nights turned off by whoever runs the service, none starts
            now[0] = at(18, 6, 0);
            Config.set(Scheduler.NIGHTS, "off");
            try {
                assertFalse(Scheduler.nightsOn()); assertEquals(0, s.nextNight(libs.get(0).id()));
                s.round();
                assertEquals(5, node.nights.size(), "nights are off: none");
                Config.set(Scheduler.NIGHTS, ""); Config.set(Scheduler.AT_ONCE, "4");
                assertTrue(Scheduler.nightsOn()); assertEquals(4, Scheduler.atOnce());
                s.round();
                assertEquals(9, node.nights.size(), "four at once, as the setting says");
            } finally { Config.set(Scheduler.NIGHTS, ""); Config.set(Scheduler.AT_ONCE, ""); }
            assertEquals(2, Scheduler.atOnce(), "two unless set");
        }
    }

    @Test
    void aSleepingLibraryIsStartedWhenItsOwnWorkIsDueAndOneSchedulerOfSeveralDoesIt(@TempDir Path tmp) throws Exception {
        try (AccountStore store = AccountStore.open(tmp.resolve("accounts.db").toString())) {
            AccountStore.Library asleep = library(store, tmp, "Ann Example"), awake = library(store, tmp, "Ben Example"), later = library(store, tmp, "Cat Example");
            Recorded node = new Recorded(), other = new Recorded();
            node.nightEnds(awake);
            long t = store.clock();
            store.due(asleep.id(), t - 1000); store.due(awake.id(), t - 1000); store.due(later.id(), t + 3_600_000);
            Config.set(Scheduler.NIGHTS, "off");
            try {
                Scheduler one = new Scheduler(store, node, "one", () -> at(17, 12, 0)), two = new Scheduler(store, other, "two", () -> at(17, 12, 0));
                assertEquals(List.of("due " + asleep.id()), one.round());
                assertEquals(List.of("Ann Example"), node.woken, "the one that sleeps and whose hour has come; not the one awake, not the one due later");
                assertEquals(0, store.placement(asleep.id()).dueAt(), "once");
                store.due(asleep.id(), t - 1000);
                assertTrue(two.round().isEmpty()); assertTrue(other.woken.isEmpty(), "another process of the service is not the scheduler while the first holds the duty");
                assertNotEquals(0, store.placement(asleep.id()).dueAt());
            } finally { Config.set(Scheduler.NIGHTS, ""); }
        }
    }
}
