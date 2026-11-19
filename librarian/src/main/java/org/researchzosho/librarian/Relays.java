package org.researchzosho.librarian;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import jdk.net.ExtendedSocketOptions;
import jdk.net.UnixDomainPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bytes passed between a TCP port and a Unix socket (0.8.0). A library in a container has no network at all; its node and it meet in
 * a folder of the library's own that the container sees at {@code /run/rz}, through Unix sockets: the node passes the service's
 * requests to the library's socket, and the library passes what it reaches outward (the service, the servers its settings name, the
 * web) to the node's sockets, one for each, where the node goes on to that one place and nowhere else. Both sides use this.
 */
final class Relays implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(Relays.class);
    /** The setting that tells a library in a container its ways out: {@code port=name,…}, a port of its own loopback for each of the node's sockets. */
    static final String SPEC = "RESEARCHZOSHO_RELAYS";
    /** The socket the node passes the service's requests to; the library makes it. */
    static final String IN = "library.sock";

    /**
     * The library's side, in its container: for each {@code port=name} of {@code spec}, a port of its own loopback that passes to the
     * node's socket {@code name.sock} in {@code dir}; and its own socket there, {@link #IN}, that passes the service's requests to
     * {@code host:port}, where it serves.
     */
    static Relays inside(Path dir, String spec, String host, int port) throws IOException {
        Relays r = new Relays();
        for (String pair : spec == null ? new String[0] : spec.split(",")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.strip().split("=", 2);
            r.tcpToUnix(InetAddress.getLoopbackAddress(), Integer.parseInt(kv[0]), dir.resolve(kv[1] + ".sock"));
        }
        r.unixToTcp(dir.resolve(IN), host, port);
        return r;
    }

    /** What the node does with one connection to a socket of its own: the web's gate reads the request and answers. */
    interface Handler { void handle(SocketChannel c) throws IOException; }

    private final List<Closeable> open = new CopyOnWriteArrayList<>();
    private final List<Path> files = new CopyOnWriteArrayList<>();

    /** A Unix socket at {@code sock} (a stale one there removed first) whose every connection goes on to {@code host:port}. */
    void unixToTcp(Path sock, String host, int port) throws IOException { unix(sock, c -> both(c, tcp(host, port))); }

    /** A Unix socket at {@code sock} whose every connection is the handler's. */
    void unix(Path sock, Handler h) throws IOException {
        Files.deleteIfExists(sock);
        ServerSocketChannel s = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        s.bind(UnixDomainSocketAddress.of(sock));
        files.add(sock);
        listen(s, h, "relay-" + sock.getFileName());
    }

    /** A TCP port at {@code bind:port} (0: any free one) whose every connection goes on to the Unix socket {@code sock}; the port. */
    int tcpToUnix(InetAddress bind, int port, Path sock) throws IOException { return tcpToUnix(bind, port, sock, null); }

    /** Who must be listening on a library's own socket: its own process, the user who owns its folder under the library's own group. */
    record Peer(UserPrincipal user, GroupPrincipal group) { }

    /**
     * The same, where each connection goes on only to a socket proven to be {@code peer}'s (0.8.0 review): the node's way in to a library
     * in a container, whose sockets' folder the container writes. The path must be a socket and not a link, and the process listening on
     * it must be the library's own (the kernel says who listens: SO_PEERCRED); else the connection is closed before a byte is written,
     * so a link put there by code in the container sends the service's requests nowhere (Docker's socket, another library's, the node's).
     */
    int tcpToUnix(InetAddress bind, int port, Path sock, Peer peer) throws IOException {
        ServerSocketChannel s = ServerSocketChannel.open();
        s.bind(new InetSocketAddress(bind, port), 50);
        listen(s, c -> both(c, peer == null ? unixAt(sock) : proven(sock, peer)), "relay-" + port);
        return ((InetSocketAddress) s.getLocalAddress()).getPort();
    }

    /** A connection to {@code sock} when it is proven to be {@code peer}'s; refused (and said once in the log) otherwise. */
    static SocketChannel proven(Path sock, Peer peer) throws IOException {
        BasicFileAttributes a = Files.readAttributes(sock, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.isSymbolicLink() || !a.isOther()) throw refused(sock, "it is not a socket of the library's own but " + (a.isSymbolicLink() ? "a link" : "a file or folder"));
        SocketChannel u = unixAt(sock);
        try {
            UnixDomainPrincipal p = u.getOption(ExtendedSocketOptions.SO_PEERCRED);
            if (p == null || !peer.user().equals(p.user()) || !peer.group().equals(p.group()))
                throw refused(sock, "what listens on it is " + (p == null ? "unknown" : p.user().getName() + ":" + p.group().getName()) + ", not the library's own process (" + peer.user().getName() + ":" + peer.group().getName() + ")");
            return u;
        } catch (IOException | RuntimeException e) { quietly(u); throw e; }
    }

    private static final Set<String> REFUSED_SAID = ConcurrentHashMap.newKeySet();

    private static IOException refused(Path sock, String why) {
        if (REFUSED_SAID.add(sock + why)) LOG.warn("A request to the library at {} was not passed on: {}. Nothing was written to it.", sock, why);
        return new IOException("not the library's own socket: " + why);
    }

    /** A TCP port at {@code bind:port} (0: any free one) whose every connection is the handler's; the port. */
    int tcp(InetAddress bind, int port, Handler h) throws IOException {
        ServerSocketChannel s = ServerSocketChannel.open();
        s.bind(new InetSocketAddress(bind, port), 50);
        listen(s, h, "gate-" + port);
        return ((InetSocketAddress) s.getLocalAddress()).getPort();
    }

    private void listen(ServerSocketChannel s, Handler h, String name) {
        open.add(s);
        Thread.ofPlatform().daemon().name(name).start(() -> {
            while (s.isOpen()) {
                SocketChannel c;
                try { c = s.accept(); } catch (IOException closed) { return; }
                Thread.ofVirtual().start(() -> { try { h.handle(c); } catch (IOException | RuntimeException e) { quietly(c); } });
            }
        });
    }

    /** A connection to {@code host:port} over TCP, given 10 s. */
    static SocketChannel tcp(String host, int port) throws IOException { return tcp(new InetSocketAddress(host, port)); }

    static SocketChannel tcp(InetSocketAddress at) throws IOException {
        SocketChannel c = SocketChannel.open();
        try { c.socket().connect(at, 10_000); return c; } catch (IOException e) { quietly(c); throw e; }
    }

    /** A connection to the Unix socket at {@code sock}. */
    static SocketChannel unixAt(Path sock) throws IOException { return SocketChannel.open(UnixDomainSocketAddress.of(sock)); }

    /** Bytes both ways until both ends are done; both closed after. How many went each way: {a to b, b to a}. */
    static long[] both(SocketChannel a, SocketChannel b) {
        long[] went = new long[2];
        try {
            Thread back = Thread.ofVirtual().start(() -> went[1] = pump(b, a));
            went[0] = pump(a, b);
            back.join();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { quietly(a); quietly(b); }
        return went;
    }

    /** One way, until its end; the other side is told the end. The bytes that went. */
    static long pump(SocketChannel from, SocketChannel to) {
        ByteBuffer buf = ByteBuffer.allocate(16 * 1024);
        long n = 0;
        try {
            while (from.read(buf) >= 0) { buf.flip(); n += buf.remaining(); while (buf.hasRemaining()) to.write(buf); buf.clear(); }
        } catch (IOException e) { /* one side went away */ }
        finally { try { to.shutdownOutput(); } catch (IOException ignored) { } }
        return n;
    }

    static void quietly(Closeable c) { try { if (c != null) c.close(); } catch (IOException ignored) { } }

    /** Every listener closed, and the Unix sockets it made removed. */
    @Override public void close() {
        for (Closeable c : open) quietly(c);
        open.clear();
        for (Path f : files) { try { Files.deleteIfExists(f); } catch (IOException ignored) { } }
        files.clear();
    }
}
