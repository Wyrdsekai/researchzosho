package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Sharing is a live setting, never a cap: the window, the throttle, and a config edit seen without a restart. */
class ResearchSettingsTest {

    @Test
    void aWindowMayCrossMidnightAndBlankMeansAlways() {
        assertTrue(ResearchSettings.inWindow("", LocalTime.of(12, 0)));
        assertTrue(ResearchSettings.inWindow(null, LocalTime.of(12, 0)));
        assertTrue(ResearchSettings.inWindow("22:00-07:00", LocalTime.of(23, 30)));
        assertTrue(ResearchSettings.inWindow("22:00-07:00", LocalTime.of(3, 0)));
        assertFalse(ResearchSettings.inWindow("22:00-07:00", LocalTime.of(12, 0)));
        assertTrue(ResearchSettings.inWindow("09:00-17:00", LocalTime.of(9, 0)));
        assertFalse(ResearchSettings.inWindow("09:00-17:00", LocalTime.of(17, 0)));
        assertTrue(ResearchSettings.inWindow("9:00-17:00", LocalTime.of(10, 0)), "a one-digit hour is accepted");
        assertTrue(ResearchSettings.inWindow("garbage", LocalTime.of(10, 0)), "a malformed window never closes the library");
        assertTrue(ResearchSettings.validWindow("22:00-07:00"));
        assertFalse(ResearchSettings.validWindow("22-07"));
    }

    @Test
    void theThrottleDropsToOneLaneWhenTheDriveSlowsAndRestoresWhenItRecovers() throws Exception {
        List<String> log = new ArrayList<>();
        var t = new ResearchSettings.Throttle(log::add);
        for (int i = 0; i < 5; i++) t.observe(1000, 1000);       // the run's own baseline: 1 s per 1000 weighted tokens
        assertFalse(t.slow());
        assertEquals(1000, t.typicalTurnMs());
        for (int i = 0; i < 3; i++) t.observe(4000, 4000);       // a later turn, four times the history: the same cost per token — NOT slow (J-0009 fired on this)
        assertFalse(t.slow(), "a longer turn with proportionally more tokens is the run's own growth");
        for (int i = 0; i < 3; i++) t.observe(2500, 1000);       // someone else on the card
        assertTrue(t.slow(), "sustained 2.5× per token is slow");
        assertTrue(log.get(0).startsWith("drive slowed"), log.toString());
        // one lane while slow: a second entrant waits until the first leaves
        t.enter(3);
        Thread second = new Thread(() -> { try { t.enter(3); t.leave(); } catch (InterruptedException ignored) { } });
        second.start();
        Thread.sleep(150);
        assertTrue(second.isAlive(), "the second turn waited behind the one lane");
        t.leave();
        second.join(5_000);
        assertFalse(second.isAlive());
        for (int i = 0; i < 3; i++) t.observe(1100, 1000);
        assertFalse(t.slow(), "recovered under 1.3×");
        for (int i = 0; i < 20; i++) t.observe(30_000, 30_000);   // late turns, long histories: the typical turn is what turns cost NOW
        assertEquals(30_000, t.typicalTurnMs(), "the median of recent turns, not the cheap first five");
        for (int i = 0; i < 10; i++) t.observe(90_000, 90_000);   // the last forty: 10 cheap, 20 × 30 s, 10 × 90 s
        assertEquals(90_000, t.longTurnMs(), "a long turn is the 80th percentile: what a closing summary or a section costs");
        assertEquals(30_000, t.typicalTurnMs());
        assertTrue(log.get(1).startsWith("drive recovered"), log.toString());
    }

    @Test
    void aSettingEditedInTheConfigFileIsSeenWithoutARestart(@TempDir Path tmp) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        try {
            org.researchzosho.Config.invalidate();
            Path cfg = tmp.resolve(".researchzosho").resolve("config");
            Files.createDirectories(cfg.getParent());
            assertEquals(3, ResearchSettings.workers(), "the default");
            assertFalse(ResearchSettings.paused());
            Files.writeString(cfg, "research.workers = 1\nresearch.pause = on\nresearch.window = 22:00-07:00\n");
            Files.setLastModifiedTime(cfg, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000));   // a same-second edit still counts
            assertEquals(1, ResearchSettings.workers(), "read live from the changed file");
            assertTrue(ResearchSettings.paused());
            assertEquals("22:00-07:00", ResearchSettings.window());
            org.researchzosho.Config.set(ResearchSettings.PAUSE, "off");
            assertFalse(ResearchSettings.paused(), "the CLI's write is seen at once");
            assertTrue(ResearchSettings.describe().contains("workers 1"), ResearchSettings.describe());
        } finally {
            System.setProperty("user.home", real);
            org.researchzosho.Config.invalidate();
        }
    }
}
