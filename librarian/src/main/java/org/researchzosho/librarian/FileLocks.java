package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A step two processes on one library must not run at once (take the lease, issue an id), run under a lock on a guard file beside what it
 * guards. The lock is the operating system's, held only while the step runs: it is let go when the step ends and, should the process die
 * in the step, when the process ends, so a crashed holder never blocks the next one for ever. A guard file is never renamed or rewritten;
 * what it guards may be (atomically, by rename), which is why the lock is not taken on that file itself. Threads of one process take
 * turns before the file lock, since a JVM holds one file's lock once.
 *
 * <p>Within one machine this holds for every process; across machines it holds where the file system passes the lock on (a local disk,
 * a Docker volume), and not on every network file system: one library is served from one machine.
 */
final class FileLocks {

    private FileLocks() { }

    /** How long a step waits for another process's step before it says so: far longer than any step takes. */
    static final long WAIT_MS = 30_000;

    private static final Map<Path, Object> IN_THIS_PROCESS = new ConcurrentHashMap<>();

    /** A step that may throw. */
    @FunctionalInterface interface Step<T> { T run() throws IOException; }

    /** Runs {@code step} holding the lock on {@code guard}, made when missing; waits for a holder in another process, at most {@link #WAIT_MS}. */
    static <T> T under(Path guard, Step<T> step) throws IOException {
        Path key = guard.toAbsolutePath().normalize();
        synchronized (IN_THIS_PROCESS.computeIfAbsent(key, k -> new Object())) {
            Files.createDirectories(key.getParent());
            try (FileChannel ch = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                long until = System.currentTimeMillis() + WAIT_MS;
                FileLock lock;
                while ((lock = ch.tryLock()) == null) {
                    if (System.currentTimeMillis() > until) throw new IOException("Another process has held " + key + " for " + WAIT_MS / 1000 + " s; the step it guards is not run.");
                    try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while waiting for " + key + ".", e); }
                }
                try { return step.run(); } finally { lock.release(); }
            }
        }
    }
}
