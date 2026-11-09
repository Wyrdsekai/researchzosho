package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The helpers installed from the build follow the release (0.7.0): an installed likeness helper with an older script is refreshed at the
 * daemon's start — the build's script copied over, the models loaded once, the marker rewritten, one line said — and an up-to-date one is
 * left alone with nothing said; the marker is keyed on the script and the packages, so a release that changes them sets up what changed;
 * MetricX's script follows the same rule.
 */
class HelperRefreshTest {
    String home; Supplier<String> check;
    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); check = LikenessHelper.CHECK; }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); LikenessHelper.CHECK = check; }

    /** A helper as an earlier release left it: its Python, a script of that release, a marker of that release's form. */
    static void olderHelper(Path dir, Path script, Path marker, String oldScript, String oldMarker) throws Exception {
        Files.createDirectories(script.getParent()); Files.createDirectories(dir.resolve("venv").resolve("bin"));
        Files.writeString(dir.resolve("venv").resolve("bin").resolve("python"), "#!/bin/sh\n", StandardCharsets.UTF_8);
        Files.writeString(script, oldScript, StandardCharsets.UTF_8);
        Files.writeString(marker, oldMarker, StandardCharsets.UTF_8);
    }

    @Test void anInstalledLikenessHelperWithAnOlderScriptIsRefreshedAtStartAndAnUpToDateOneIsLeftAlone() throws Exception {
        assertEquals("", LikenessHelper.refresh(), "nothing installed: nothing to refresh");
        olderHelper(LikenessHelper.dir(), LikenessHelper.script(), LikenessHelper.ready(), "# the 0.7.0 release's first helper\n", "open_clip ViT-B-32 with the LAION-2B weights (laion2b_s34b_b79k); OpenCV's YuNet face detector and SFace face recogniser\n");
        assertTrue(LikenessHelper.present()); assertFalse(LikenessHelper.installed(), "an older marker is not this release's");
        int[] checks = {0}; LikenessHelper.CHECK = () -> { checks[0]++; return "ready"; };
        assertEquals("The likeness helper's script is from an earlier release; refreshed.", LikenessHelper.refresh());
        assertArrayEquals(LikenessHelper.scriptBytes(), Files.readAllBytes(LikenessHelper.script()), "the build's script is in place");
        assertEquals(LikenessHelper.markerText(), Files.readString(LikenessHelper.ready(), StandardCharsets.UTF_8), "the marker names this script, these packages and these models");
        assertTrue(Files.readString(LikenessHelper.ready(), StandardCharsets.UTF_8).startsWith("script " + LikenessHelper.scriptHash() + "\npackages torch torchvision"), Files.readString(LikenessHelper.ready(), StandardCharsets.UTF_8));
        assertEquals(1, checks[0], "the models were loaded once to be sure"); assertTrue(LikenessHelper.installed());
        // up to date: nothing copied, nothing said, the models not loaded again
        FileTime before = Files.getLastModifiedTime(LikenessHelper.script());
        Files.setLastModifiedTime(LikenessHelper.script(), FileTime.fromMillis(before.toMillis() - 60_000));
        assertEquals("", LikenessHelper.refresh());
        assertEquals(before.toMillis() - 60_000, Files.getLastModifiedTime(LikenessHelper.script()).toMillis(), "the script was not written again"); assertEquals(1, checks[0]);
        // a release that changed the packages and not the script: said, and the background setup installs them
        Files.writeString(LikenessHelper.ready(), LikenessHelper.markerText().replace("packages " + LikenessHelper.PACKAGES, "packages torch (an earlier list)"), StandardCharsets.UTF_8);
        assertFalse(LikenessHelper.installed()); assertFalse(LikenessHelper.packagesCurrent());
        assertEquals("The likeness helper's packages are from an earlier release; set up again in the background.", LikenessHelper.refresh());
        // a refresh whose load fails says so and leaves the marker stale, so the setup tries again
        Files.writeString(LikenessHelper.script(), "# older again\n", StandardCharsets.UTF_8);
        LikenessHelper.CHECK = () -> "!the models could not be loaded: no module named open_clip";
        assertEquals("The likeness helper's script is from an earlier release; refreshing it failed: the models could not be loaded: no module named open_clip", LikenessHelper.refresh());
        assertFalse(LikenessHelper.installed());
        Config.set(LikenessHelper.SETTING, "off"); assertEquals("", LikenessHelper.refresh(), "off: nothing is touched"); Config.set(LikenessHelper.SETTING, "");
    }

    @Test void theMetricXScriptFollowsTheReleaseTheSameWay() throws Exception {
        assertEquals("", TranslationScores.refreshMetricxScript(), "not installed: nothing to refresh");
        olderHelper(TranslationScores.metricxDir(), TranslationScores.metricxScript(), TranslationScores.metricxReady(), "# an earlier release's scorer\n", TranslationScores.METRICX_MODEL + "\n");
        assertEquals("The translation scorers' MetricX script is from an earlier release; refreshed.", TranslationScores.refreshMetricxScript());
        try (var in = TranslationScores.class.getResourceAsStream("/org/researchzosho/librarian/metricx_score.py")) { assertArrayEquals(in.readAllBytes(), Files.readAllBytes(TranslationScores.metricxScript())); }
        assertEquals("", TranslationScores.refreshMetricxScript(), "up to date: nothing said");
        assertTrue(TranslationScores.metricxInstalled(), "the marker is the model's name, as before: not keyed");
    }
}
