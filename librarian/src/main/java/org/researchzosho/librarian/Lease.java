package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetAddress;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One daemon per library. The serving process holds {@code catalog/serve.lock}: its pid, its host, and
 * when the lease expires. It renews every four minutes; the lease lasts five, so a daemon that died
 * without releasing loses it in five minutes, and a daemon that is merely paused keeps it. A second
 * daemon started on the same library while the lease is live and its holder is alive is refused with the
 * holder's pid, instead of the two racing on the index, the changes feed and the backup.
 *
 * <p>The lease is read and written under a lock between processes ({@link FileLocks}, on {@code catalog/serve.lock.guard}), and
 * written whole by a rename: two processes starting together never both take it, a stale holder's lease goes to one of them, and a
 * reader never sees half a file.
 *
 * <p>The holder looks at the file every {@link #WATCH_MS} ({@link #check}): when it names another process that is alive, the library is
 * that process's now (this one was paused past its lease, or a second service took it after the lapse) and this one has lost it, said
 * once to {@link #whenTaken}; the daemon then stops working on the library. When the lease merely ran out with nobody else holding it (a
 * laptop that slept), or its file is gone, or it names a process that is not alive, it is written again for five more minutes and work
 * goes on. Otherwise it is renewed every {@link #RENEW_MS}.
 */
public final class Lease implements AutoCloseable {

    static final long TTL_MS = 5 * 60_000L;
    static final long RENEW_MS = 4 * 60_000L;
    /** How often the holder looks at the lease file; a test shortens it. */
    static volatile long WATCH_MS = 10_000;

    private final Path file;
    private final long pid = ProcessHandle.current().pid();
    private volatile boolean held;
    /** The live process found holding the lease in this one's place; null while this one holds it. */
    private volatile Holder takenBy;
    /** When this process last wrote the lease. */
    private volatile long written;
    private volatile Consumer<Holder> onTaken;
    private final AtomicBoolean told = new AtomicBoolean();
    private Thread watcher;

    private Lease(Path file) { this.file = file; }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("serve.lock"); }

    /** The guard the lease is taken and renewed under, never renamed: see {@link FileLocks}. */
    static Path guard(Path leaseFile) { return leaseFile.resolveSibling(leaseFile.getFileName() + ".guard"); }

    public record Holder(long pid, String host, Instant expires) {
        boolean live() { return Instant.now().isBefore(expires) && (!host.equals(hostName()) || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)); }
    }

    static String hostName() {
        try { return InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "localhost"; }
    }

    public static Holder holder(LibraryStore store) throws IOException {
        Path f = file(store);
        if (!Files.exists(f)) return null;
        String[] p = Files.readString(f, StandardCharsets.UTF_8).strip().split("\t");
        if (p.length < 3) return null;
        try { return new Holder(Long.parseLong(p[0]), p[1], Instant.parse(p[2])); } catch (Exception e) { return null; }
    }

    /** Take the lease, or throw naming who holds it. */
    public static Lease acquire(LibraryStore store) throws IOException {
        Lease l = new Lease(file(store));
        FileLocks.under(guard(l.file), () -> {   // read and written as one step: no second process between the two
            Holder h = holder(store);
            if (h != null && h.live()) {   // our own pid counts too: two daemons in one process are still two daemons
                throw new IOException("another daemon is serving this library: pid " + h.pid() + " on " + h.host() + " (lease until " + h.expires()
                        + "). Stop it, or wait for its lease to lapse. The library is " + store.root());
            }
            l.write();
            return null;
        });
        l.held = true;
        l.watcher = new Thread(() -> {
            while (l.held) {
                try { Thread.sleep(WATCH_MS); } catch (InterruptedException e) { return; }
                if (l.held) { try { l.check(); } catch (IOException ignored) { /* looked at again at the next turn */ } }
            }
        }, "serve-lease");
        l.watcher.setDaemon(true);
        l.watcher.start();
        return l;
    }

    /** Said once, on the thread that finds it, when another live process is found holding the lease. */
    void whenTaken(Consumer<Holder> c) { onTaken = c; }

    /**
     * The lease looked at and kept, under the lock: another live process holding it → this one has lost it, and the holder is returned
     * (and said once to {@link #whenTaken}); the file names this process and was written less than {@link #RENEW_MS} ago → nothing to do;
     * otherwise (due for renewal, lapsed, gone, or naming a process that is not alive) it is written again for five more minutes, and null
     * is returned: this process holds the library.
     */
    Holder check() throws IOException {
        if (takenBy != null) return takenBy;
        if (!held) return null;
        Holder[] other = {null};
        FileLocks.under(guard(file), () -> {
            Holder h = Files.exists(file) ? holder(file) : null;
            if (h != null && !ours(h) && h.live()) { other[0] = h; return null; }   // another process holds the library now: not written over
            boolean fresh = h != null && ours(h) && Instant.now().isBefore(h.expires()) && System.currentTimeMillis() - written < RENEW_MS;
            if (!fresh) write();
            return null;
        });
        if (other[0] == null) return null;
        takenBy = other[0]; held = false;
        Consumer<Holder> c = onTaken;
        if (c != null && told.compareAndSet(false, true)) c.accept(other[0]);
        return other[0];
    }

    private void write() throws IOException {
        Files.createDirectories(file.getParent());
        AtomicWrite.text(file, pid + "\t" + hostName() + "\t" + Instant.now().plusMillis(TTL_MS) + "\n");   // whole or not at all: a reader never sees half
        written = System.currentTimeMillis();
    }

    private boolean ours(Holder h) { return h.pid() == pid && h.host().equals(hostName()); }

    public boolean held() { return held; }

    /**
     * Whether this process holds the lease now, as the file says: it names this process and has not lapsed. Null when it does; else why
     * not, in a sentence ("pid 4242 on homebox holds it until …", "no process holds it", "it lapsed at …").
     */
    public String lost() {
        Holder t = takenBy;
        if (t != null) return "pid " + t.pid() + " on " + t.host() + " holds it (until " + t.expires() + ")";
        try {
            Holder h = Files.exists(file) ? holder(file) : null;
            if (!held) return "this process let the lease go";
            if (h == null) return "no process holds it: the lease file is gone or cannot be read";
            if (!ours(h)) return "pid " + h.pid() + " on " + h.host() + " holds it (until " + h.expires() + ")";
            if (!Instant.now().isBefore(h.expires())) return "this process's lease lapsed at " + h.expires();
            return null;
        } catch (IOException e) { return "the lease file cannot be read: " + e.getMessage(); }
    }

    /** Whether this process holds the lease now ({@link #lost} is null). */
    public boolean holds() { return lost() == null; }

    @Override public void close() {
        held = false;
        if (watcher != null) watcher.interrupt();
        try {
            FileLocks.under(guard(file), () -> {
                Holder h = Files.exists(file) ? holder(file) : null;
                if (h == null || ours(h)) Files.deleteIfExists(file);   // only our own
                return null;
            });
        } catch (IOException ignored) { }
    }

    private static Holder holder(Path f) throws IOException {
        String[] p = Files.readString(f, StandardCharsets.UTF_8).strip().split("\t");
        if (p.length < 3) return null;
        try { return new Holder(Long.parseLong(p[0]), p[1], Instant.parse(p[2])); } catch (Exception e) { return null; }
    }
}
