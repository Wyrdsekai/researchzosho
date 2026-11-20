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
        try { Files.createDirectories(own); } catch (IOException e) { throw new UncheckedIOException(e); }
        System.setProperty("user.home", own.toString());
    }
}
