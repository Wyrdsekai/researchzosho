package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The auto-update's quiet moment comes after the housekeeping the nightly queued has ended, and a night that does nothing says why. */
class NightlyUpdateTest {
    @Test void theNightlyWaitsForTheDaemonToGoIdleAndGivesUpAtTheBound() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        assertTrue(Crews.waitForIdle(() -> asked.incrementAndGet() >= 3, 5_000, 1), "idle on the third look");
        assertEquals(3, asked.get());
        long t0 = System.currentTimeMillis();
        assertFalse(Crews.waitForIdle(() -> false, 60, 5), "never idle: false at the bound");
        assertTrue(System.currentTimeMillis() - t0 >= 60, "the bound was waited out");
        assertTrue(Crews.waitForIdle(() -> true, 0, 1), "idle at once needs no wait");
    }

    @Test void aNightThatDoesNothingSaysWhy() {
        assertEquals("this is not an installed release, so nothing is swapped", Updater.whyNot(false, true, "0.6.9", "0.6.8-dev"));
        assertEquals("a run was still active after the wait; the next try is tomorrow night, or researchzosho update now", Updater.whyNot(true, false, null, "0.6.8"));
        assertEquals("the latest release could not be read from GitHub; the next try is tomorrow night", Updater.whyNot(true, true, null, "0.6.8"));
        assertEquals("up to date (0.6.8)", Updater.whyNot(true, true, "0.6.8", "0.6.8"));
        assertEquals("up to date (0.6.8)", Updater.whyNot(true, true, "0.6.7", "0.6.8"));
        assertEquals("", Updater.whyNot(true, true, "0.6.9", "0.6.8"), "a newer release, idle: swap it in");
    }
}
