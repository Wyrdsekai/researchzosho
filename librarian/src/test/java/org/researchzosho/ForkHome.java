package org.researchzosho;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Each test JVM of a run with forks (the build's maxParallelForks) gets a home of its own, a folder under the suite's scratch home named
 * for its Gradle worker, before any class is loaded: the settings file, the state folder (pid records, logs, locks) and every helper's
 * folder under the home are then one JVM's, as they were when one JVM ran the whole suite. A JVM that Gradle did not start (an IDE's)
 * keeps the home it was given.
 */
public final class ForkHome implements LauncherSessionListener {
    @Override public void launcherSessionOpened(LauncherSession session) {
        String worker = System.getProperty("org.gradle.test.worker"), home = System.getProperty("user.home");
        if (worker == null || worker.isBlank() || home == null || home.contains("fork-")) return;
        Path own = Path.of(home, "fork-" + worker.strip());
        try {
            startedEmpty = !Files.exists(own) || isEmpty(own);
            Files.createDirectories(own);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        System.setProperty("user.home", own.toString());
    }

    /**
     * Whether this JVM's home was empty when the JVM began: true each run, as the test task empties the suite's scratch home first; null
     * in a JVM Gradle did not start. A home left from an earlier run holds what a test of that run set and never put back when the run
     * was stopped in its middle (2026-11-26: the nights off).
     */
    static volatile Boolean startedEmpty;

    public static Boolean startedEmpty() { return startedEmpty; }

    static boolean isEmpty(Path dir) throws IOException {
        try (var in = Files.list(dir)) { return in.findAny().isEmpty(); }
    }
}
