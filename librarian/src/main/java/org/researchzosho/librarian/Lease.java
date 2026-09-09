package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * One daemon per library. The serving process holds {@code catalog/serve.lock}: its pid, its host, and
 * when the lease expires. It renews every four minutes; the lease lasts five, so a daemon that died
 * without releasing loses it in five minutes, and a daemon that is merely paused keeps it. A second
 * daemon started on the same library while the lease is live and its holder is alive is refused with the
 * holder's pid, instead of the two racing on the index, the changes feed and the backup.
 */
public final class Lease implements AutoCloseable {

    static final long TTL_MS = 5 * 60_000L;
    static final long RENEW_MS = 4 * 60_000L;

    private final Path file;
    private final long pid = ProcessHandle.current().pid();
    private volatile boolean held;
    private Thread renewer;

    private Lease(Path file) { this.file = file; }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("serve.lock"); }

    public record Holder(long pid, String host, Instant expires) {
        boolean live() { return Instant.now().isBefore(expires) && (!host.equals(hostName()) || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)); }
    }

    static String hostName() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "localhost"; }
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
        Holder h = holder(store);
        if (h != null && h.live()) {   // our own pid counts too: two daemons in one process are still two daemons
            throw new IOException("another daemon is serving this library: pid " + h.pid() + " on " + h.host() + " (lease until " + h.expires()
                    + "). Stop it, or wait for its lease to lapse. The library is " + store.root());
        }
        l.write();
        l.held = true;
        l.renewer = new Thread(() -> {
            while (l.held) {
                try { Thread.sleep(RENEW_MS); } catch (InterruptedException e) { return; }
                if (l.held) { try { l.write(); } catch (IOException ignored) { } }
            }
        }, "serve-lease");
        l.renewer.setDaemon(true);
        l.renewer.start();
        return l;
    }

    private void write() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, pid + "\t" + hostName() + "\t" + Instant.now().plusMillis(TTL_MS) + "\n", StandardCharsets.UTF_8);
    }

    public boolean held() { return held; }

    @Override public void close() {
        held = false;
        if (renewer != null) renewer.interrupt();
        try {
            Holder h = Files.exists(file) ? holder(file) : null;
            if (h == null || h.pid() == pid) Files.deleteIfExists(file);   // only our own
        } catch (IOException ignored) { }
    }

    private static Holder holder(Path f) throws IOException {
        String[] p = Files.readString(f, StandardCharsets.UTF_8).strip().split("\t");
        if (p.length < 3) return null;
        try { return new Holder(Long.parseLong(p[0]), p[1], Instant.parse(p[2])); } catch (Exception e) { return null; }
    }
}
