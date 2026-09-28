package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.researchzosho.tools.Fetch;
/**
 * Push for the changes feed. A reader registers an address and a secret; every change that goes on
 * the feed (retired, disputed, revised, supplied, and the rest) is also posted there, signed with the
 * secret, so a program that cites findings hears about a recall without polling. The feed stays the
 * source of truth: a delivery that fails after three tries is logged and the next poll of
 * {@code library_changes} catches it.
 *
 * <p>One file, {@code catalog/webhooks.md}, one line per subscription: who, where, the secret, which
 * events. The secret is in the clear there because it signs outgoing posts; the file is the owner's.
 */
public final class Webhooks {

    private static final ObjectMapper M = new ObjectMapper();
    static final int[] BACKOFF_MS = {0, 2_000, 8_000};
    /** Delivery threads are NOT daemon threads, and they die two seconds after the last delivery. The command line ends with
     *  {@code System.exit}, which does not wait for them: it calls {@link #flush} first. */
    private static final ExecutorService POOL = pool();
    /** Every task given to the pool and not yet done that {@link #flush} waits for: a post to an address whose last post did not fail. */
    private static final AtomicInteger PENDING = new AtomicInteger();
    /** The posts under way: what {@link #flush} writes in the log when the process ends before they are done. */
    private static final Set<Object[]> POSTING = ConcurrentHashMap.newKeySet();
    /** The posts under way that {@link #flush} does not wait for, because the last post to their address failed. */
    private static final Set<Object[]> UNWAITED = ConcurrentHashMap.newKeySet();

    /**
     * Whether this process gave a post to the pool. Its own class, so a command that changed nothing never loads {@link Webhooks} (its
     * client and its pool) just to learn there is nothing to wait for.
     */
    public static final class Sent {
        private static volatile boolean any;
        private Sent() { }
        public static boolean any() { return any; }
    }
    private static ExecutorService pool() {
        ThreadPoolExecutor p = (ThreadPoolExecutor) Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "webhooks"); t.setDaemon(false); return t; });
        p.setKeepAliveTime(2, TimeUnit.SECONDS);
        return p;
    }
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** Tests point deliveries at a local server; production never does. */
    static volatile boolean allowLoopback = true;

    public record Hook(String did, String url, String secret, List<String> kinds) {
        /** The feed's events are {@code state:<from>→<to>}, {@code supersedes:<ids>}, {@code added}, {@code edited}, {@code revised},
         *  {@code supplied}…; a subscription names them the short way ({@code retired}, {@code disputed}, {@code supersedes}) or in full. */
        boolean wants(String event) {
            if (kinds.isEmpty() || kinds.contains("all") || kinds.contains(event)) return true;
            int arrow = event.indexOf('→'), colon = event.indexOf(':');
            if (arrow > 0 && kinds.contains(event.substring(arrow + 1))) return true;      // state:accepted→retired  ~  retired
            return colon > 0 && kinds.contains(event.substring(0, colon));                 // supersedes:F-1,F-2  ~  supersedes
        }
    }

    private Webhooks() { }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("webhooks.md"); }
    static Path log(LibraryStore store) { return store.root().resolve("catalog").resolve("webhooks.log"); }

    /** Why {@code url} may not be a webhook address, or null when it may. Loopback is allowed: a program on this box is the common case. */
    public static String refusal(String url) { return refusal(url, Fetch.ownServiceAuthorities()); }

    /**
     * The rule over a given list of the library's own {@code host:port}s. Only the same host AND
     * port is refused: on one machine the library's embedder and a reader's program share
     * loopback, and refusing the whole host refused every program on that machine.
     */
    static String refusal(String url, List<String> ownAuthorities) {
        URI u;
        try { u = URI.create(url); } catch (Exception e) { return "not a URL"; }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return "only http(s) addresses";
        if (u.getHost() == null || u.getHost().isBlank()) return "no host in " + url;
        try {
            for (var a : InetAddress.getAllByName(u.getHost())) {
                if (a.isLinkLocalAddress()) return "a link-local address";
                if (a.isAnyLocalAddress() || a.isMulticastAddress()) return "an unusable address";
                if (a.isLoopbackAddress() && !allowLoopback) return "a loopback address";
            }
        } catch (Exception e) { return "cannot resolve " + u.getHost(); }
        String target = Fetch.authority(u);
        for (String own : ownAuthorities) if (own.equalsIgnoreCase(target)) return "one of the library's own services";
        return null;
    }

    public static synchronized List<Hook> list(LibraryStore store) throws IOException {
        List<Hook> out = new ArrayList<>();
        if (!Files.exists(file(store))) return out;
        for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
            if (!line.startsWith("- ")) continue;
            String[] p = line.substring(2).split(" — ", 4);
            if (p.length < 3) continue;
            List<String> kinds = p.length == 4 && !p[3].isBlank() ? List.of(p[3].strip().split("\\s*,\\s*")) : List.of();
            out.add(new Hook(p[0].strip(), p[1].strip(), p[2].strip(), kinds));
        }
        return out;
    }

    public static synchronized void add(LibraryStore store, String did, String url, String secret, List<String> kinds) throws IOException {
        String why = refusal(url);
        if (why != null) throw new IOException("cannot post to " + url + ": " + why);
        if (url.contains(" — ") || secret.contains(" — ")) throw new IOException("the address and the secret may not contain ' — '");
        List<Hook> all = new ArrayList<>();
        for (Hook h : list(store)) if (!(h.did().equals(did) && h.url().equals(url))) all.add(h);
        all.add(new Hook(did, url, secret, kinds == null ? List.of() : kinds));
        write(store, all);
    }

    public static synchronized boolean remove(LibraryStore store, String did, String url) throws IOException {
        List<Hook> kept = new ArrayList<>(); boolean found = false;
        for (Hook h : list(store)) { if (h.did().equals(did) && h.url().equals(url)) found = true; else kept.add(h); }
        if (found) write(store, kept);
        return found;
    }

    private static void write(LibraryStore store, List<Hook> hooks) throws IOException {
        Files.createDirectories(file(store).getParent());
        StringBuilder sb = new StringBuilder("# Webhooks — where changes are pushed, signed with each secret (the reader's own file)\n\nOne per line: `- <did> — <url> — <secret> — <events, or all>`.\n\n");
        for (Hook h : hooks) sb.append("- ").append(h.did()).append(" — ").append(h.url()).append(" — ").append(h.secret()).append(" — ").append(h.kinds().isEmpty() ? "all" : String.join(",", h.kinds())).append('\n');
        Files.writeString(file(store), sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Post one change to every subscription that wants it; asynchronous, retried, logged. Never throws. A subscriber is asked whether
     * they may still read the library each time: one the owner denied, or left out when the library was closed, is sent nothing.
     */
    public static void deliver(LibraryStore store, Changes.Change change) {
        List<Hook> hooks;
        try { hooks = list(store); } catch (IOException e) { return; }
        if (hooks.isEmpty()) return;
        String json = null;
        for (Hook h : hooks) {
            if (!h.wants(change.event())) continue;
            if (!Patrons.mayRead(store, new Patrons.Patron(h.did(), "", ""))) {
                logLine(store, "withheld", h, change, "not sent, because this subscriber may no longer read the library; the owner can remove the webhook with: researchzosho reader webhook remove " + h.did() + " " + h.url());
                continue;
            }
            if (json == null) json = body(store, change);
            String j = json;
            if (lastFailed(store, h)) {
                // the receiver did not answer last time: the post is still made, but a command that ends does not wait for it
                Object[] under = {store, h, change};
                UNWAITED.add(under);
                Sent.any = true;
                POOL.submit(() -> { try { post(store, h, j, change); } finally { UNWAITED.remove(under); } });
            } else submit(() -> post(store, h, j, change));
        }
    }

    private static void submit(Runnable task) {
        PENDING.incrementAndGet();
        Sent.any = true;
        try {
            POOL.submit(() -> {
                try { task.run(); }
                finally { if (PENDING.decrementAndGet() == 0) synchronized (PENDING) { PENDING.notifyAll(); } }
            });
        } catch (RuntimeException e) { PENDING.decrementAndGet(); throw e; }
    }

    /** How much of the end of the log is read to find how the last post to an address went. */
    private static final int LOG_TAIL = 64 * 1024;

    /** Whether the last post to this subscription that the log records failed. A subscription never posted to has not. */
    static boolean lastFailed(LibraryStore store, Hook h) {
        Path f = log(store);
        if (!Files.exists(f)) return false;
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            long len = raf.length(), from = Math.max(0, len - LOG_TAIL);
            byte[] buf = new byte[(int) (len - from)];
            raf.seek(from);
            raf.readFully(buf);
            String[] lines = new String(buf, StandardCharsets.UTF_8).split("\n");
            String who = "\t" + h.did() + "\t" + h.url() + "\t";
            for (int i = lines.length - 1; i >= 0; i--) {
                String l = lines[i];
                if (!l.contains(who)) continue;
                if (l.contains("\tdelivered" + who)) return false;
                if (l.contains("\tfailed" + who)) return true;
            }
        } catch (IOException e) { return false; }
        return false;
    }

    /**
     * Before the process ends: the posts under way get up to {@code maxMs} to finish. A post still under way after that is written in
     * the log as not delivered; the changes feed still has it. The command line calls this before it exits, because {@code System.exit} ends every delivery thread where it stands.
     */
    public static void flush(long maxMs) {
        long end = System.currentTimeMillis() + Math.max(0, maxMs);
        synchronized (PENDING) {
            while (PENDING.get() > 0) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try { PENDING.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        // an address whose last post failed gets a short wait for its first try: one that answers again is written down as delivered, so
        // the next command waits for it as for any other; one that is still down costs this command no more than the short wait
        long grace = Math.min(end, System.currentTimeMillis() + UNWAITED_GRACE_MS);
        while (!UNWAITED.isEmpty() && System.currentTimeMillis() < grace) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        for (Object[] p : UNWAITED) logLine((LibraryStore) p[0], "not waited for", (Hook) p[1], (Changes.Change) p[2], "the command ended without waiting for this post, because the last post to this address failed; the feed still has it (seq " + ((Changes.Change) p[2]).seq() + ")");
        for (Object[] p : POSTING) if (UNWAITED.stream().noneMatch(u -> u[1] == p[1] && u[2] == p[2]))
            logLine((LibraryStore) p[0], "failed", (Hook) p[1], (Changes.Change) p[2], "the command that made the change ended before the post was done; the feed still has it (seq " + ((Changes.Change) p[2]).seq() + ")");
    }

    /** How long the command line waits, at the most, for the posts under way when it ends. */
    public static final long FLUSH_MS = 5_000;

    /** How long a command waits at its end for the first try of a post to an address whose last post failed. */
    static final long UNWAITED_GRACE_MS = 1_000;

    private static String body(LibraryStore store, Changes.Change change) {
        LibraryStore.Identity id;
        try { id = store.identity(); } catch (IOException e) { id = new LibraryStore.Identity("", ""); }
        ObjectNode body = M.createObjectNode();
        body.put("library_id", id.id()); body.put("library_name", id.name());
        ObjectNode c = body.putObject("change");
        c.put("seq", change.seq()); c.put("at", change.at()); c.put("kind", change.kind()); c.put("id", change.id()); c.put("event", change.event()); c.put("detail", change.detail());
        return body.toString();
    }

    static void post(LibraryStore store, Hook h, String json, Changes.Change change) {
        Object[] under = {store, h, change};
        POSTING.add(under);
        try { postNow(store, h, json, change); } finally { POSTING.remove(under); }
    }

    private static void postNow(LibraryStore store, Hook h, String json, Changes.Change change) {
        String sig = "sha256=" + hmac(h.secret(), json);
        String last = "";
        for (int attempt = 0; attempt < BACKOFF_MS.length; attempt++) {
            if (BACKOFF_MS[attempt] > 0) { try { Thread.sleep(BACKOFF_MS[attempt]); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; } }
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(h.url())).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-ResearchZosho-Signature", sig)
                        .header("X-ResearchZosho-Event", headerForm(change.event()))
                        .header("X-ResearchZosho-Seq", Long.toString(change.seq()))
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 == 2) { logLine(store, "delivered", h, change, "attempt " + (attempt + 1) + ", " + r.statusCode()); return; }
                last = "status " + r.statusCode();
            } catch (Exception e) {
                last = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
        }
        logLine(store, "failed", h, change, last + " after " + BACKOFF_MS.length + " tries; the feed still has it (seq " + change.seq() + ")");
    }

    /**
     * The event as a header carries it. A header value is ASCII, and the feed's state events are
     * {@code state:<from>→<to>}: the HTTP client refused the arrow, so every state change failed to
     * post, three tries each. The header writes the arrow {@code ->}; the body keeps the event exactly.
     */
    static String headerForm(String event) {
        if (event == null) return "";
        var s = event.replace("→", "->");
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            sb.append(ch >= 0x20 && ch < 0x7f ? ch : '?');
        }
        return sb.toString();
    }

    static synchronized void logLine(LibraryStore store, String what, Hook h, Changes.Change change, String note) {
        try {
            Files.createDirectories(log(store).getParent());
            Files.writeString(log(store), Instant.now() + "\t" + what + "\t" + h.did() + "\t" + h.url() + "\t" + change.event() + " " + change.id() + "\t" + note + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    /** HMAC-SHA256 of {@code body} with {@code secret}, hex. A receiver recomputes it to know the post is this library's. */
    public static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
