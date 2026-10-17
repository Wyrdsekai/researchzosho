package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Marks a test's home as freshly checked, so a command run in it starts no background download. The version check behind
 * {@code status} and the site-list download behind a page check each run on a daemon thread and write into the home when the
 * network answers; on a slow runner that was while JUnit deleted the test's temp folder, and the deletion failed (public CI,
 * 2026-10-17). A test that drives the command line in a temp home calls this once the home is chosen.
 */
public final class QuietHome {
    private QuietHome() { }

    /** The user home to quiet; returns it, for chaining. */
    public static Path quiet(Path userHome) throws IOException {
        Path state = Config.stateDir(userHome);
        Files.createDirectories(state.resolve("site-list"));
        Files.writeString(state.resolve("latest-version.txt"), "", StandardCharsets.UTF_8);   // fresh and empty: no refresh today, nothing to announce
        Files.writeString(state.resolve("site-list/last-try"), Instant.now() + "\n", StandardCharsets.UTF_8);   // tried just now: no download today
        return userHome;
    }
}
