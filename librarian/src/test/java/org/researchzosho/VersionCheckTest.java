package org.researchzosho;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The update check behind every page (0.8.4): one at a time, where two pages at once each started one; and none for an hour after one
 * failed, where an offline machine started one at every page.
 */
class VersionCheckTest {

    @Test
    void oneCheckAtATimeAndNoneSoonAfterOneFailed(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        Supplier<String> realLatest = Version.LATEST;
        LongSupplier realClock = Version.NOW;
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        AtomicInteger checks = new AtomicInteger();
        CountDownLatch held = new CountDownLatch(1), began = new CountDownLatch(1);   // the check runs on a thread of its own
        Version.LATEST = () -> { checks.incrementAndGet(); began.countDown(); try { held.await(120, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return null; };   // offline
        Version.refreshes = true;
        ExecutorService pages = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> shown = new ArrayList<>();
            for (int i = 0; i < 16; i++) shown.add(pages.submit(Version::latestCached));
            for (Future<String> f : shown) assertNull(f.get(60, TimeUnit.SECONDS), "a page never waits on the check");
            assertTrue(began.await(60, TimeUnit.SECONDS), "the check began");   // and is held: another would have begun by now only beside it
            assertEquals(1, checks.get(), "sixteen pages at once: one check");
            held.countDown();
            Version.settleCheck();
            for (int i = 0; i < 20; i++) Version.latestCached();
            Version.settleCheck();
            assertEquals(1, checks.get(), "a moment after a check failed, no page starts another");
            long hourOn = System.currentTimeMillis() + Version.FAILED_WAIT_MS + 1_000;
            Version.NOW = () -> hourOn;
            Version.LATEST = () -> { checks.incrementAndGet(); return "9.9.9"; };   // back online
            Version.latestCached();
            Version.settleCheck();
            assertEquals(2, checks.get(), "an hour later, one check");
            assertEquals("9.9.9", Version.latestCached(), "what it found is kept");
            for (int i = 0; i < 5; i++) Version.latestCached();
            Version.settleCheck();
            assertEquals(2, checks.get(), "a check that answered is not made again for a day");
        } finally {
            held.countDown(); pages.shutdownNow(); Version.settleCheck();
            Version.refreshes = false; Version.LATEST = realLatest; Version.NOW = realClock;
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }
}
