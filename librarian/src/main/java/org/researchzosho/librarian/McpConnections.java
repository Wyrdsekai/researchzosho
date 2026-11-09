package org.researchzosho.librarian;

import org.researchzosho.Config;
import org.researchzosho.mcp.McpClient;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * The connections to the keeper's MCP servers that are open now, one per server and library. A server's program is started on first use
 * and kept while it is used; one left idle for five minutes is stopped, and so is every one when the service stops or the program ends.
 * A server whose way of being reached changed, or that stopped answering, is started afresh at its next use.
 */
final class McpConnections {
    private McpConnections() { }

    /** How long an unused connection is kept; a test makes it short. */
    static volatile long IDLE_MS = 5 * 60_000L;
    /** The longest the handshake and the list of tools may take. */
    static final Duration HANDSHAKE = Duration.ofSeconds(20);
    /** Where an address is spoken to: itself; a test points a preset's address at its stand-in, so nothing reaches the network. */
    static volatile UnaryOperator<String> ADDRESS = UnaryOperator.identity();

    private static final class Live {
        final McpClient client; final String config; volatile long used = System.currentTimeMillis();
        Live(McpClient client, String config) { this.client = client; this.config = config; }
    }

    private static final Map<String, Live> LIVE = new ConcurrentHashMap<>();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static volatile ScheduledExecutorService reaper;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(McpConnections::closeAll, "mcp-close"));   // a program that ends takes the servers it started with it
    }

    private static String key(LibraryStore store, String name) { return store.root().toAbsolutePath().normalize() + "\n" + name; }

    /**
     * The server's connection, opened when there is none: the program started or the address greeted, the handshake made, and the tools it
     * lists now written down as the ones the tables carry from now on.
     */
    static McpClient open(LibraryStore store, McpServers.Server s) throws IOException {
        String key = key(store, s.name);
        synchronized (LOCKS.computeIfAbsent(key, k -> new Object())) {
            Live l = LIVE.get(key);
            if (l != null && l.client.alive() && l.config.equals(s.config())) { l.used = System.currentTimeMillis(); return l.client; }
            if (l != null) { LIVE.remove(key); l.client.close(); }
            McpClient c = start(store, s);
            try { c.initialize(HANDSHAKE); }
            catch (IOException e) { c.close(); McpServers.listed(store, s.name, null, null, e.getMessage()); throw e; }
            LIVE.put(key, new Live(c, s.config()));
            reap();
            try { McpServers.listed(store, s.name, c.tools(HANDSHAKE), c, ""); }
            catch (IOException e) { McpServers.listed(store, s.name, null, c, e.getMessage()); }
            return c;
        }
    }

    /** A new client for the server, not yet greeted: its program started with its environment and log, or its address with its key's header when a key is set. */
    static McpClient start(LibraryStore store, McpServers.Server s) throws IOException {
        if (s.stdio()) return McpClient.stdio(s.command, s.env, McpServers.log(store, s.name));
        return McpClient.http(ADDRESS.apply(s.url), headers(s));
    }

    /** The headers an address is sent: the key in its header, when the setting it lives in holds one; nothing otherwise. */
    static Map<String, String> headers(McpServers.Server s) {
        Map<String, String> h = new LinkedHashMap<>();
        if (s.keySetting.isBlank()) return h;
        String v = Config.get(s.keySetting);
        if (v == null || v.isBlank()) return h;
        v = v.strip();
        h.put(s.headerName(), s.bearer() && !v.regionMatches(true, 0, "Bearer ", 0, 7) ? "Bearer " + v : v);
        return h;
    }

    /** Closes the server's connection; its program, when it has one, is stopped. */
    static void close(LibraryStore store, String name) {
        Live l = LIVE.remove(key(store, name));
        if (l != null) l.client.close();
    }

    /** Takes the server's connection out at once and closes it in the background: a run that is stopping, or a server that stopped answering, does not wait for its program to end. */
    static void closeSoon(LibraryStore store, String name) {
        Live l = LIVE.remove(key(store, name));
        if (l == null) return;
        Thread t = new Thread(l.client::close, "mcp-close-" + name);
        t.setDaemon(true);
        t.start();
    }

    /** Closes every connection of this library: the service is stopping. */
    static void closeFor(LibraryStore store) {
        String prefix = store.root().toAbsolutePath().normalize() + "\n";
        for (String k : new ArrayList<>(LIVE.keySet())) if (k.startsWith(prefix)) { Live l = LIVE.remove(k); if (l != null) l.client.close(); }
    }

    static void closeAll() {
        for (String k : new ArrayList<>(LIVE.keySet())) { Live l = LIVE.remove(k); if (l != null) l.client.close(); }
    }

    /** How many connections are open now, for a test. */
    static int open() { return LIVE.size(); }

    /** Whether this server has a connection open now. */
    static boolean isOpen(LibraryStore store, String name) { Live l = LIVE.get(key(store, name)); return l != null && l.client.alive(); }

    private static void reap() {
        if (reaper != null) return;
        synchronized (McpConnections.class) {
            if (reaper != null) return;
            ScheduledExecutorService r = Executors.newSingleThreadScheduledExecutor(t -> { Thread x = new Thread(t, "mcp-idle"); x.setDaemon(true); return x; });
            r.scheduleWithFixedDelay(McpConnections::closeIdle, 1, 1, TimeUnit.SECONDS);
            reaper = r;
        }
    }

    /** Stops the connections nobody used for {@link #IDLE_MS}, and forgets the ones whose program ended. */
    static void closeIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Live> e : new ArrayList<>(LIVE.entrySet())) {
            Live l = e.getValue();
            if (!l.client.alive() || now - l.used > IDLE_MS) { if (LIVE.remove(e.getKey(), l)) l.client.close(); }
        }
    }

    /** For the commands: every connection the keeper's list no longer names, or names differently, closed. */
    static void forget(LibraryStore store, List<McpServers.Server> now) {
        String prefix = store.root().toAbsolutePath().normalize() + "\n";
        for (String k : new ArrayList<>(LIVE.keySet())) {
            if (!k.startsWith(prefix)) continue;
            String name = k.substring(prefix.length());
            Live l = LIVE.get(k);
            boolean same = l != null && now.stream().anyMatch(s -> s.name.equals(name) && s.config().equals(l.config));
            if (!same) { Live gone = LIVE.remove(k); if (gone != null) gone.client.close(); }
        }
    }
}
