package org.researchzosho;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Settings read while another thread changes them (0.6.1, the CI failure of 2026-10-15): a reader never sees an empty file. Before, a
 * change emptied the one settings map and the next read refilled it, so a read in between found nothing — the share watcher read "off",
 * stopped the tunnel and started another. Now a change swaps in a whole new snapshot; the file is replaced whole; and {@code replace}
 * takes a value back only while it is still the one written.
 */
class ConfigSnapshotTest {

    String home;

    @BeforeEach void isolate(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.toString()); Config.invalidate();
        Files.createDirectories(tmp.resolve(".researchzosho"));
        Files.writeString(tmp.resolve(".researchzosho/config"), "drive = http://127.0.0.1:1\nshare = cloudflare\n"); Config.invalidate();
    }

    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); }

    @Test
    void aReaderNeverSeesAnEmptyFileWhileAnotherThreadWrites() throws Exception {
        // the overlap is made, not hoped for: the reader runs first, and the writer keeps writing until the reader has read 3,000 times
        // (on a fast Mac 600 writes once finished before the reader started, and the test proved nothing)
        AtomicInteger empty = new AtomicInteger(), reads = new AtomicInteger(), writes = new AtomicInteger();
        AtomicBoolean done = new AtomicBoolean();
        CountDownLatch reading = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            reading.countDown();
            while (!done.get()) {
                String kind = Config.live("RESEARCHZOSHO_SHARE"), drive = Config.get("RESEARCHZOSHO_DRIVE");
                if (kind == null || drive == null) empty.incrementAndGet();
                reads.incrementAndGet();
            }
        });
        reader.start();
        assertTrue(reading.await(30, TimeUnit.SECONDS));
        long until = System.currentTimeMillis() + 30_000;
        try {
            while ((reads.get() < 3_000 || writes.get() < 200) && System.currentTimeMillis() < until) {
                Config.set("RESEARCHZOSHO_SHARE_ADDRESS", writes.getAndIncrement() % 2 == 0 ? "https://a.example" : "");
            }
        } finally { done.set(true); reader.join(30_000); }
        assertEquals(0, empty.get(), "reads that saw no settings at all, of " + reads.get());
        assertTrue(reads.get() >= 3_000 && writes.get() >= 200, "the reader read while the writer wrote: " + reads.get() + " reads, " + writes.get() + " writes");
        assertEquals("cloudflare", Config.live("RESEARCHZOSHO_SHARE"));
    }

    @Test
    void theFileIsReplacedWholeAndReplaceTakesBackOnlyItsOwnValue() throws Exception {
        Config.set("RESEARCHZOSHO_SHARE_ADDRESS", "https://first.example");
        Path cfg = Config.userConfigPath();
        assertFalse(Files.exists(cfg.resolveSibling(cfg.getFileName() + ".writing-" + ProcessHandle.current().pid())), "the file written beside is moved over, not left");
        assertTrue(Files.readString(cfg).contains("drive = http://127.0.0.1:1"), "the other lines are kept");
        // a newer tunnel wrote its own address: the older one's ending must not wipe it
        Config.set("RESEARCHZOSHO_SHARE_ADDRESS", "https://second.example");
        assertFalse(Config.replace("RESEARCHZOSHO_SHARE_ADDRESS", "https://first.example", ""));
        assertEquals("https://second.example", Config.live("RESEARCHZOSHO_SHARE_ADDRESS"));
        assertTrue(Config.replace("RESEARCHZOSHO_SHARE_ADDRESS", "https://second.example", ""));
        assertNull(Config.live("RESEARCHZOSHO_SHARE_ADDRESS"));
        assertTrue(Config.replace("RESEARCHZOSHO_SHARE_ADDRESS", "", "https://third.example"), "an absent key counts as empty");
        assertEquals("https://third.example", Config.live("RESEARCHZOSHO_SHARE_ADDRESS"));
    }
}
