package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.net.InetAddress;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(Lease.class);
    static final long TTL_MS = 5 * 60_000L;
    static final long RENEW_MS = 4 * 60_000L;
    /** How often the holder looks at the lease file; a test shortens it, and the property shortens it for a library a test starts as a process. */
    static volatile long WATCH_MS = Long.getLong("researchzosho.lease.watch_ms", 10_000);

    private final Path file;
    private final long pid = ProcessHandle.current().pid();
    /** The start of the library this lease is for, and the service it runs under (0 and "": a library of a person's own). */
    private volatile long epoch;
    private volatile String service = "";
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
    /** The most a lease file holds: a pid, a host, a time, an epoch and a service's name. */
    static final int MOST_BYTES = 4096;

    static Path guard(Path leaseFile) { return leaseFile.resolveSibling(leaseFile.getFileName() + ".guard"); }

    /**
     * Who holds the lease, as its file says, and {@code written}: when the file was last written (null: not known). A process of this
     * machine is the holder only when it lives and started no later than that ({@link ProcessProof#startedBy}): the system gives a dead
     * holder's number to another process, and the number alone proves nothing (round G: on Windows a server killed by its number left
     * its lease, the number went to another process within seconds, and the service's server, started next, took that process for a
     * live holder and refused to serve for five minutes).
     */
    public record Holder(long pid, String host, Instant expires, long epoch, String service, Instant written) {
        public Holder(long pid, String host, Instant expires, long epoch, String service) { this(pid, host, expires, epoch, service, null); }
        /** A holder whose file says nothing of a start under an account service (a library of a person's own, or written before 0.8.0). */
        public Holder(long pid, String host, Instant expires) { this(pid, host, expires, 0, ""); }
        /**
         * Whether a start with {@code epoch} under {@code service} may take the lease from this holder while it lives: it is an earlier
         * start of the same library by the same service (0.8.0). The registry gave the library to the later start; the earlier one, frozen
         * or cut off, gives up when it next looks.
         */
        boolean replacedBy(long epoch, String service) { return epoch > 0 && this.epoch > 0 && this.epoch < epoch && service != null && !service.isEmpty() && service.equals(this.service); }
        boolean live() {
            if (!Instant.now().isBefore(expires)) return false;
            if (!host.equals(hostName())) return true;   // another machine's process: its lease is all there is to go by
            ProcessHandle h = ProcessHandle.of(pid).orElse(null);
            if (h == null || !h.isAlive()) return false;
            Instant started = h.info().startInstant().orElse(null);
            if (written == null || started == null || ProcessProof.startedBy(started, written)) return true;   // a start time the system does not give: alive is all there is
            LOG.info("lease: pid {} named in {} lives, but started at {}, after the lease was written at {}: another process got the number, and the lease is free", pid, host, started, written);
            return false;
        }
    }

    static String hostName() {
        String named = System.getProperty("researchzosho.accounts.machine");   // a test's two machines on one: each node, and its libraries, named apart
        if (named != null && !named.isBlank()) return named.strip();
        try { return InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "localhost"; }
    }

    public static Holder holder(LibraryStore store) throws IOException {
        Path f = file(store);
        if (!Files.exists(f)) return null;
        return holder(f);
    }

    /** Take the lease, or throw naming who holds it. */
    public static Lease acquire(LibraryStore store) throws IOException { return acquire(store, 0, ""); }

    /**
     * The same for one start of a library under an account service: {@code epoch}, the registry's count of its starts, and the
     * {@code service} it runs under are written into the lease, and a live holder that is an earlier start of it by the same service
     * gives the lease to this one at once (it gives up when it next looks) instead of after its five minutes.
     */
    public static Lease acquire(LibraryStore store, long epoch, String service) throws IOException {
        Lease l = new Lease(file(store));
        l.epoch = epoch; l.service = service == null ? "" : service.strip();
        FileLocks.under(guard(l.file), () -> {   // read and written as one step: no second process between the two
            Holder h = holder(store);
            if (h != null && h.live() && !h.replacedBy(l.epoch, l.service)) {   // our own pid counts too (two daemons in one process are still two), unless the lease is older than this process
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
        AtomicWrite.text(file, pid + "\t" + hostName() + "\t" + Instant.now().plusMillis(TTL_MS) + (epoch > 0 ? "\t" + epoch + "\t" + service : "") + "\n");   // whole or not at all: a reader never sees half
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
        // the file is read as itself, never through a link, and only when it is the size a lease is: a link to /dev/zero or a huge file in
        // a library's folder is no holder (round F, the second review)
        BasicFileAttributes a;
        try { a = Files.readAttributes(f, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); } catch (NoSuchFileException gone) { return null; }
        if (!a.isRegularFile() || a.size() > MOST_BYTES) return null;
        String[] p = Files.readString(f, StandardCharsets.UTF_8).strip().split("\t");
        if (p.length < 3) return null;
        try { return new Holder(Long.parseLong(p[0]), p[1], Instant.parse(p[2]), p.length > 3 ? Long.parseLong(p[3]) : 0, p.length > 4 ? p[4] : "", a.lastModifiedTime().toInstant()); } catch (Exception e) { return null; }
    }
}
