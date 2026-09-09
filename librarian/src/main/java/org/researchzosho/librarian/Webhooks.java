package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
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
    /** Delivery threads are NOT daemon threads: a change made from the command line must still go out after the verb
     *  returns, so the JVM waits for them; they die two seconds after the last delivery so it does not wait longer. */
    private static final ExecutorService POOL = pool();
    private static ExecutorService pool() {
        java.util.concurrent.ThreadPoolExecutor p = (java.util.concurrent.ThreadPoolExecutor) Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "webhooks"); t.setDaemon(false); return t; });
        p.setKeepAliveTime(2, java.util.concurrent.TimeUnit.SECONDS);
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
    public static String refusal(String url) {
        URI u;
        try { u = URI.create(url); } catch (Exception e) { return "not a URL"; }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return "only http(s) addresses";
        if (u.getHost() == null || u.getHost().isBlank()) return "no host in " + url;
        try {
            for (var a : java.net.InetAddress.getAllByName(u.getHost())) {
                if (a.isLinkLocalAddress()) return "a link-local address";
                if (a.isAnyLocalAddress() || a.isMulticastAddress()) return "an unusable address";
                if (a.isLoopbackAddress() && !allowLoopback) return "a loopback address";
            }
        } catch (Exception e) { return "cannot resolve " + u.getHost(); }
        for (String own : org.researchzosho.tools.Fetch.ownServiceHosts()) if (own.equalsIgnoreCase(u.getHost())) return "one of the library's own services";
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

    /** Post one change to every subscription that wants it; asynchronous, retried, logged. Never throws. */
    public static void deliver(LibraryStore store, Changes.Change change) {
        List<Hook> hooks;
        try { hooks = list(store); } catch (IOException e) { return; }
        if (hooks.isEmpty()) return;
        LibraryStore.Identity id;
        try { id = store.identity(); } catch (IOException e) { id = new LibraryStore.Identity("", ""); }
        ObjectNode body = M.createObjectNode();
        body.put("library_id", id.id()); body.put("library_name", id.name());
        ObjectNode c = body.putObject("change");
        c.put("seq", change.seq()); c.put("at", change.at()); c.put("kind", change.kind()); c.put("id", change.id()); c.put("event", change.event()); c.put("detail", change.detail());
        String json = body.toString();
        for (Hook h : hooks) {
            if (!h.wants(change.event())) continue;
            POOL.submit(() -> post(store, h, json, change));
        }
    }

    static void post(LibraryStore store, Hook h, String json, Changes.Change change) {
        String sig = "sha256=" + hmac(h.secret(), json);
        String last = "";
        for (int attempt = 0; attempt < BACKOFF_MS.length; attempt++) {
            if (BACKOFF_MS[attempt] > 0) { try { Thread.sleep(BACKOFF_MS[attempt]); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; } }
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(h.url())).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-ResearchZosho-Signature", sig)
                        .header("X-ResearchZosho-Event", change.event())
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
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
