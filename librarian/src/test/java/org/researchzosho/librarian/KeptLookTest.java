package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A kept look whose finding is a failure is due again sooner, and while somebody asked within a minute it is made again by itself:
 * a person told that a server does not answer, who mends it and asks again a few seconds later, is told what the library finds then.
 */
class KeptLookTest {

    private final LongSupplier realClock = KeptLook.NOW;

    @AfterEach
    void restore() { KeptLook.NOW = realClock; }

    @Test
    void aFailureIsLookedAtAgainByItselfWhileItIsWantedAndTheServerThatCameBackIsSeen() throws Exception {
        AtomicInteger looks = new AtomicInteger();
        AtomicBoolean up = new AtomicBoolean(false);
        KeptLook<Boolean> l = new KeptLook<>("test-look", 60_000, 50, ok -> !ok, () -> { looks.incrementAndGet(); return up.get(); });
        assertNull(l.known(), "nothing known before the first look");
        l.settle(Duration.ofSeconds(60));
        assertFalse(l.known().value());   // the ask that starts the repeats
        // the server comes back; nobody asks again, and within a few of its short intervals the look has seen it by itself
        up.set(true);
        long until = System.currentTimeMillis() + 60_000;
        while (!Boolean.TRUE.equals(peek(l)) && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals(Boolean.TRUE, peek(l), "a look made again by itself found the server answering");
        l.settle(Duration.ofSeconds(60));
        assertFalse(l.repeating(), "a look that found it answering is not repeated: the next is a minute later, on an ask");
    }

    @Test
    void noLookIsMadeAgainByItselfAMinuteAfterTheLastAsk() throws Exception {
        long t = System.currentTimeMillis();
        KeptLook.NOW = () -> t;
        AtomicInteger looks = new AtomicInteger();
        KeptLook<Boolean> l = new KeptLook<>("test-look", 60_000, 50, ok -> !ok, () -> { looks.incrementAndGet(); return false; });
        l.known(); l.settle(Duration.ofSeconds(60));
        KeptLook.NOW = () -> t + KeptLook.WANTED_MS + 1;   // a minute since anybody asked
        // the one repeat that may wait already is made, and finds nobody has asked: it schedules none
        long until = System.currentTimeMillis() + 60_000;
        while (l.repeating() && System.currentTimeMillis() < until) Thread.sleep(10);
        l.settle(Duration.ofSeconds(60));
        assertFalse(l.repeating(), "nobody has asked for a minute: the failing look stops being made by itself");
        assertTrue(looks.get() <= 2, "at most the one repeat that waited before the minute was over: " + looks.get());
    }

    @Test
    void manyAsksWhileAFailureIsRepeatedStartOneLookAtATime() throws Exception {
        AtomicInteger inFlight = new AtomicInteger(), most = new AtomicInteger(), looks = new AtomicInteger();
        KeptLook<Boolean> l = new KeptLook<>("test-look", 60_000, 20, ok -> !ok, () -> {
            looks.incrementAndGet(); most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            inFlight.decrementAndGet(); return false;
        });
        long until = System.currentTimeMillis() + 400;
        while (System.currentTimeMillis() < until) { l.known(); Thread.sleep(1); }
        l.forget();   // the repeats end: a look made before a forget starts nothing more
        assertEquals(1, most.get(), "never two looks at once, however many asks and repeats");
        assertTrue(looks.get() <= 400 / 20 + 2, "one look per short interval at most, not one per ask: " + looks.get());
    }

    private static Boolean peek(KeptLook<Boolean> l) { KeptLook.Seen<Boolean> s = l.last(); return s == null ? null : s.value(); }
}
