package org.researchzosho.librarian;

import org.researchzosho.drive.Gated;
import org.researchzosho.tools.Addresses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web of a library in a container (0.8.0): an HTTP proxy its node runs for it, the only way out it has. {@code CONNECT host:port}
 * for https, and a plain request with the whole address for http. The gate resolves the name itself, refuses the request when any
 * address the name has is one {@link Addresses} lists (this machine, a private network, link-local where the cloud's metadata answers,
 * multicast and the like), and connects to the address it checked, so no second lookup can give another one. A redirect is a new
 * request through the gate, checked the same way. The addresses the service's own settings name (a model on the private network)
 * are let through by name and port, since whoever runs the service named them.
 *
 * <p>Each library has a gate of its own (its own socket, or in the process form a port of this machine's loopback), so the gate knows
 * whose every connection is: it logs a refusal at once, and a count per library every ten minutes. Under an account service it counts
 * each request to the web against the use it carries ({@link Gated#WEB_USE}), or as the library's own work when it carries none, with
 * the bytes both ways, and refuses one when its account has fetched as many pages this month as it may (0.8.0, stage 3).
 */
final class WebGate implements Relays.Handler {
    private static final Logger LOG = LoggerFactory.getLogger(WebGate.class);
    /** The longest request head taken. */
    static final int MAX_HEAD = 64 * 1024;
    private static final long TALLY_MS = 10 * 60_000;

    /** What a library's web gate does with what passes it: refuse a request whose account may fetch no more this month, and count each one. */
    interface Counting {
        /** Why a request of {@code use} (null: one that carries none, the library's own) may not go out, as the person is told it; null when it may. */
        String refused(String use);
        /** A request of {@code use} that went out, with the bytes both ways: written as it opens (the count) and as it closes (its bytes). */
        void counted(String use, long bytes);
        /** The connection of a request of {@code use} is open: it counts from now, so a cap holds for connections made at one moment. */
        default void opened(String use) { }
        /** The connection closed, with the bytes both ways. */
        default void closed(String use, long bytes) { counted(use, bytes); }
    }

    private final String library;
    private final Set<String> named;
    private final Counting counting;
    private final Function<String, InetAddress[]> resolve;
    private final Function<InetAddress, Addresses.Kind> rule;
    private final AtomicLong opened = new AtomicLong(), refused = new AtomicLong();
    private volatile long since = System.currentTimeMillis();
    /** The libraries' counts, for whoever runs the service: by library, connections and refusals since its gate began. */
    static final Map<String, long[]> TOTALS = new ConcurrentHashMap<>();

    /** The gate of {@code library}, letting through the {@code host:port}s in {@code named} (lower case) by name. */
    WebGate(String library, Set<String> named) { this(library, named, null); }

    /** The same, counting what passes ({@code counting}; null: nothing counted). */
    WebGate(String library, Set<String> named, Counting counting) { this(library, named, WebGate::lookup, Addresses::kind, counting); }

    /** The same with the name's lookup and the list given (a test's). */
    WebGate(String library, Set<String> named, Function<String, InetAddress[]> resolve, Function<InetAddress, Addresses.Kind> rule) { this(library, named, resolve, rule, null); }

    WebGate(String library, Set<String> named, Function<String, InetAddress[]> resolve, Function<InetAddress, Addresses.Kind> rule, Counting counting) {
        this.library = library; this.named = named; this.resolve = resolve; this.rule = rule; this.counting = counting;
    }

    private static InetAddress[] lookup(String host) {
        try { return InetAddress.getAllByName(host); } catch (IOException e) { return new InetAddress[0]; }
    }

    @Override public void handle(SocketChannel c) throws IOException {
        byte[] head;
        ByteBuffer rest;
        try {
            ByteBuffer buf = ByteBuffer.allocate(MAX_HEAD);
            int end;
            while ((end = headEnd(buf)) < 0) {
                if (!buf.hasRemaining() || c.read(buf) < 0) { answer(c, 400, "The request's head did not end."); return; }
            }
            buf.flip();
            head = new byte[end];
            buf.get(head);
            rest = buf.slice();
        } catch (IOException e) { Relays.quietly(c); return; }
        String text = new String(head, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length != 3) { answer(c, 400, "Not a request."); return; }
        String method = first[0], target = first[1], version = first[2];
        boolean connect = method.equalsIgnoreCase("CONNECT");
        String host; int port; String path = null;
        try {
            if (connect) {
                URI u = URI.create("//" + target);
                host = u.getHost(); port = u.getPort();
            } else {
                URI u = URI.create(target);
                if (!"http".equalsIgnoreCase(u.getScheme())) { answer(c, 400, "Only http is asked for in full; https goes by CONNECT."); return; }
                host = u.getHost(); port = u.getPort() < 0 ? 80 : u.getPort();
                path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            }
        } catch (RuntimeException e) { answer(c, 400, "Not an address: " + target); return; }
        if (host == null || port <= 0) { answer(c, 400, "Not an address: " + target); return; }
        String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        Decision d = check(bare, port);
        if (d.to() == null) { refused.incrementAndGet(); tally(); answer(c, 403, d.why()); return; }
        String use = null;
        for (int i = 1; i < lines.length; i++) if (lines[i].regionMatches(true, 0, Gated.WEB_USE + ":", 0, Gated.WEB_USE.length() + 1)) use = lines[i].substring(Gated.WEB_USE.length() + 1).strip();
        boolean counts = counting != null && !d.named();   // a server the service's settings name is the service's own, not a page of the web
        if (counts) {
            String no = counting.refused(use);
            if (no != null) { refused.incrementAndGet(); tally(); answer(c, 402, no); return; }
        }
        InetAddress to = d.to();
        opened.incrementAndGet(); tally();
        SocketChannel out;
        try { out = Relays.tcp(new InetSocketAddress(to, port)); }
        catch (IOException e) { answer(c, 502, host + ":" + port + " did not answer."); return; }
        if (counts) counting.opened(use);
        if (connect) {
            write(c, "HTTP/1.1 200 Connection established\r\n\r\n");
            if (rest.hasRemaining()) while (rest.hasRemaining()) out.write(rest);
        } else {
            StringBuilder h = new StringBuilder(method).append(' ').append(path).append(' ').append(version).append("\r\n");
            boolean hostSent = false;
            for (int i = 1; i < lines.length; i++) {
                String l = lines[i], k = l.contains(":") ? l.substring(0, l.indexOf(':')).strip().toLowerCase(Locale.ROOT) : "";
                if (k.startsWith("proxy-") || k.equals("connection") || k.equals("keep-alive")) continue;   // the proxy's own, the use among them
                if (k.equals("host")) hostSent = true;
                h.append(l).append("\r\n");
            }
            if (!hostSent) h.append("Host: ").append(host).append(port == 80 ? "" : ":" + port).append("\r\n");
            h.append("Connection: close\r\n\r\n");   // one request a connection: the next one is checked again
            write(out, h.toString());
            if (rest.hasRemaining()) while (rest.hasRemaining()) out.write(rest);
        }
        long[] went = Relays.both(c, out);
        if (counts) counting.closed(use, went[0] + went[1]);
    }

    /** The address to connect to, or null and why not; whether it is one the settings name. */
    record Decision(InetAddress to, String why, boolean named) { Decision(InetAddress to, String why) { this(to, why, false); } }

    /** The address to connect to when every address of {@code host} may be reached, or {@code host:port} is one the settings name. */
    Decision check(String host, int port) {
        InetAddress[] all = resolve.apply(host);
        if (all.length == 0) return new Decision(null, "The name " + host + " was not found.");
        if (named.contains(host.toLowerCase(Locale.ROOT) + ":" + port)) return new Decision(all[0], null, true);
        for (InetAddress a : all) {
            Addresses.Kind k = rule.apply(a);
            if (k != null) {
                LOG.info("The web gate of the library {} refused {}:{}: {}.", library, host, port, said(k));
                return new Decision(null, host + (host.equals(a.getHostAddress()) ? "" : " (" + a.getHostAddress() + ")") + " is " + said(k) + ", which a library of this service does not reach.");
            }
        }
        return new Decision(all[0], null);
    }

    static String said(Addresses.Kind k) {
        return switch (k) {
            case LOOPBACK -> "this machine's own address";
            case LINK_LOCAL -> "a link-local address (the cloud's metadata answers there)";
            case UNUSABLE -> "an address nothing is reached at";
            case PRIVATE -> "an address on a private network";
        };
    }

    /** Every ten minutes, what each library's gate let through and refused. */
    private void tally() {
        TOTALS.computeIfAbsent(library, k -> new long[2]);
        long[] t = TOTALS.get(library);
        synchronized (t) { t[0] = opened.get(); t[1] = refused.get(); }
        long now = System.currentTimeMillis();
        if (now - since < TALLY_MS) return;
        since = now;
        LOG.info("The web gate of the library {}: {} connections, {} refused, since it began.", library, opened.get(), refused.get());
    }

    private static int headEnd(ByteBuffer b) {
        byte[] a = b.array();
        for (int i = 3; i < b.position(); i++) if (a[i - 3] == '\r' && a[i - 2] == '\n' && a[i - 1] == '\r' && a[i] == '\n') return i + 1;
        return -1;
    }

    private static void write(SocketChannel c, String s) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
        while (b.hasRemaining()) c.write(b);
    }

    private static void answer(SocketChannel c, int code, String said) {
        byte[] body = (said + "\n").getBytes(StandardCharsets.UTF_8);
        String reason = code == 403 ? "Forbidden" : code == 402 ? "Payment Required" : code == 502 ? "Bad Gateway" : "Bad Request";
        try {
            write(c, "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n");
            ByteBuffer b = ByteBuffer.wrap(body);
            while (b.hasRemaining()) c.write(b);
        } catch (IOException ignored) { }
        finally { Relays.quietly(c); }
    }

    private static final Pattern ADDRESS = Pattern.compile("https?://(\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9.-]+)(?::(\\d{1,5}))?", Pattern.CASE_INSENSITIVE);

    /**
     * The {@code host:port}s the settings handed to a library name (a model, a search server, a helpers machine), which the gate lets
     * through by name: whoever runs the service named them. This machine's own are not among them: those are reached by relays.
     */
    static Set<String> named(Map<String, String> env) {
        Set<String> out = ConcurrentHashMap.newKeySet();
        for (Map.Entry<String, String> e : env.entrySet()) {
            // only the settings the program itself hands a library: a process inherits the node's whole environment, whose proxy or other
            // addresses nobody chose for a library (round F, the second review)
            if (!e.getKey().startsWith("RESEARCHZOSHO_")) continue;
            String v = e.getValue() == null ? "" : e.getValue();
            Matcher m = ADDRESS.matcher(v);
            while (m.find()) {
                String h = m.group(1).replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
                if (h.equals("localhost") || h.startsWith("127.") || h.equals("::1")) continue;
                int port = m.group(2) != null ? Integer.parseInt(m.group(2)) : m.group(0).toLowerCase(Locale.ROOT).startsWith("https") ? 443 : 80;
                out.add(h + ":" + port);
            }
        }
        return out;
    }
}
