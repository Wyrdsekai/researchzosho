package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.researchzosho.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * The phone door (2026-11-25): a listener on this machine's loopback that a relay points at (Tailscale Serve or Funnel, or a tunnel of the
 * person's own), which answers the phone page, the few calls it makes and its own two files, and nothing else of the library. It never
 * hands a request to the library's own routing ({@code LibrarianDaemon}'s pages, {@code /v1}, {@code /rpc}, A2A, the static files): its
 * table below is exact paths, and everything else is a plain 404. A phone gets in by a pairing code shown on the library's machine
 * ({@link Phones}); what its chat may do is {@link PhoneRules}'.
 *
 * <p>A flood slows this door and nothing else: its connections run on virtual threads of its own, at most {@link #OPEN_MAX} at once (the
 * JDK's server closes a connection its executor refuses, and goes on serving); the work takes one of {@link #WORKERS} permits after a wait
 * of at most {@link #LINE_WAIT_MS} in a line of at most {@link #LINE}; a sender without a pairing is held to {@link #UNPAIRED}, and wrong
 * codes to {@link #WRONG} a sender and {@link #WRONG_ALL} in all. A body is read under a watchdog that closes the exchange when it comes
 * slower than it should. The sender is known only by the header the configured relay sets ({@link #sender}).
 */
final class PhoneDoor implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(PhoneDoor.class);
    private static final ObjectMapper M = new ObjectMapper();

    // ---------------------------------------------------------------- the settings (set by `researchzosho phone` only, never by `settings set`)

    static final String KIND = "RESEARCHZOSHO_PHONE", PORT = "RESEARCHZOSHO_PHONE_DOOR", ADDRESS = "RESEARCHZOSHO_PHONE_ADDRESS", SENDER = "RESEARCHZOSHO_PHONE_SENDER";
    static final int DEFAULT_PORT = 4652;
    static final List<String> KINDS = List.of("off", "tailnet", "public", "own");
    /** The header Cloudflare's tunnel gives the visitor's address in, which an own relay is taken to set unless the person names another. */
    static final String CLOUDFLARE_SENDER = "CF-Connecting-IP";

    static String kind() { String k = Config.live(KIND); String v = k == null || k.isBlank() ? "off" : k.strip().toLowerCase(Locale.ROOT); return KINDS.contains(v) ? v : "off"; }
    static int configuredPort() { return Config.liveInt(PORT, DEFAULT_PORT); }
    static String address() { String a = Config.live(ADDRESS); return a == null ? "" : a.strip().replaceAll("/+$", ""); }

    // ---------------------------------------------------------------- the limits

    static volatile int OPEN_MAX = 256, WORKERS = 8, LINE = 32;
    static volatile long LINE_WAIT_MS = 3_000;
    /** A pairing's body comes whole within this; any other small body too. */
    static volatile long SMALL_BODY_MS = 5_000;
    /** A message's pictures: the first seconds free, then at least this many bytes a second on average. */
    static volatile long UPLOAD_GRACE_MS = 15_000;
    static volatile long UPLOAD_FLOOR = 32 * 1024;
    static final int PAIR_BYTES = 1024, SMALL_BYTES = 4096;
    static final long ASK_BYTES = 25L << 20;
    static final int ASK_FILES = 4;
    static final String UNPAIRED = "60 per minute, burst 20", WRONG = "10 per 10 minutes", WRONG_ALL = "50 per 10 minutes";
    /** How many messages one phone may send: 60 an hour (settable for a test that sends hundreds). */
    static volatile String ASKS = "60 per hour";

    /** The cookie a paired browser keeps, and the header the page's own script adds to every call that changes something. */
    static final String COOKIE = "__Host-rz_phone", HEADER = "X-RZ-Phone";
    /** 400 days, the longest a browser keeps a cookie (Chrome; RFC 6265bis). Set again at every open. */
    static final long COOKIE_SECONDS = 34_560_000L;

    /**
     * Every answer's policy: the web app's, no frame of another site may hold the page, and the page's manifest comes from the door
     * itself (the web app's default-src 'none' would refuse it: Chrome then reports the page as having no manifest).
     */
    static final String POLICY = Pages.POLICY + "; frame-ancestors 'none'; manifest-src 'self'";

    private final LibrarianDaemon d;
    private final LibraryStore store;
    private final HttpServer server;
    private final Semaphore work = new Semaphore(WORKERS, true);
    private final AtomicInteger open = new AtomicInteger(), waiting = new AtomicInteger();
    private final Buckets buckets = new Buckets();
    /** The phones with a turn going: one at a time each. */
    final Set<String> turning = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "phone-door-watchdog"); t.setDaemon(true); return t; });

    private PhoneDoor(LibrarianDaemon d, LibraryStore store, HttpServer server) { this.d = d; this.store = store; this.server = server; }

    /** The door, open at {@code port} on the loopback (0: any free one). */
    static PhoneDoor open(LibrarianDaemon d, LibraryStore store, int port) throws IOException {
        HttpServer s = HttpServers.create(new InetSocketAddress("127.0.0.1", port), 0);
        PhoneDoor door = new PhoneDoor(d, store, s);
        s.createContext("/", x -> door.handle(new PerRequest(x)));
        s.setExecutor(door.executor());
        s.start();
        return door;
    }

    int port() { return server.getAddress().getPort(); }

    @Override public void close() { server.stop(0); watchdog.shutdownNow(); }

    /** Virtual threads, at most {@link #OPEN_MAX} exchanges at once: past that the executor refuses, and the JDK's server closes the connection. */
    private Executor executor() {
        return r -> {
            if (open.incrementAndGet() > OPEN_MAX) { open.decrementAndGet(); throw new RejectedExecutionException("the phone door has " + OPEN_MAX + " exchanges open"); }
            try { Thread.ofVirtual().name("phone-door").start(() -> { try { r.run(); } finally { open.decrementAndGet(); } }); }
            catch (RuntimeException | Error e) { open.decrementAndGet(); throw e; }
        };
    }

    /** How many exchanges are open now, and how many wait for a permit (tests read them). */
    int openNow() { return open.get(); }
    int waitingNow() { return waiting.get(); }

    // ---------------------------------------------------------------- the table

    /** What a route needs: a pairing, and whether it changes something (a POST from the page's own script). */
    private enum Need { ANYONE, PAIRED }

    private record Route(String name, Need need, boolean change) { }

    /**
     * The home-screen code's address, /m/h/&lt;code&gt;, which the page made until 2026-11-25: a home-screen icon made there must keep
     * working, so it is now only a way back to /m. It reads no file, no cookie and no code.
     */
    private static final Pattern OLD_HOME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern ENTRY_ID = Pattern.compile("[IF]-\\d{4,}-[A-Za-z0-9_-]{1,120}");
    private static final Pattern PICTURE = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,150}");

    /** The route a request names, by its method and its path as it came (never decoded first); null for everything else. */
    static String routeOf(String method, String rawPath) {
        if (rawPath == null) return null;
        String m = method == null ? "" : method;
        if (m.equals("GET")) {
            switch (rawPath) {
                case "/m", "/m/pair" -> { return "page"; }
                case "/m/phone.js" -> { return "script"; }
                case "/m/icon.png", "/m/icon-192.png", "/m/icon-512.png", "/m/icon-512-maskable.png" -> { return "icon"; }   // PhonePage.ICONS
                case "/m/app.webmanifest" -> { return "manifest"; }
                case "/m/state" -> { return "state"; }
                case "/m/conv" -> { return "conv"; }
                case "/m/live" -> { return "live"; }
                case "/m/runs" -> { return "runs"; }
                case "/m/read" -> { return "reads"; }
                default -> { }
            }
            if (rawPath.startsWith("/m/h/") && OLD_HOME.matcher(rawPath.substring(5)).matches()) return "back";
            if (rawPath.startsWith("/m/read/") && ENTRY_ID.matcher(rawPath.substring(8)).matches()) return "read";
            if (rawPath.startsWith("/m/images/")) { String f = rawPath.substring(10); if (PICTURE.matcher(f).matches() && !f.contains("..")) return "image"; }
            return null;
        }
        if (m.equals("POST")) {
            return switch (rawPath) {
                case "/m/pair" -> "pair";
                case "/m/ask" -> "ask";
                case "/m/stop" -> "stop";
                case "/m/new" -> "new";
                case "/m/seen" -> "seen";
                case "/m/runs/stop" -> "runstop";
                default -> null;
            };
        }
        return null;
    }

    /** Every route of the door, by its name: who may ask it, and whether it changes something. */
    static final Map<String, String[]> ROUTES = Map.ofEntries(
            Map.entry("page", new String[]{"anyone", "read"}), Map.entry("script", new String[]{"anyone", "read"}), Map.entry("icon", new String[]{"anyone", "read"}),
            Map.entry("manifest", new String[]{"anyone", "read"}),
            Map.entry("state", new String[]{"anyone", "read"}), Map.entry("pair", new String[]{"anyone", "change"}),
            Map.entry("conv", new String[]{"paired", "read"}), Map.entry("live", new String[]{"paired", "read"}), Map.entry("runs", new String[]{"paired", "read"}),
            Map.entry("reads", new String[]{"paired", "read"}), Map.entry("read", new String[]{"paired", "read"}), Map.entry("image", new String[]{"paired", "read"}),
            Map.entry("ask", new String[]{"paired", "change"}), Map.entry("stop", new String[]{"paired", "change"}), Map.entry("new", new String[]{"paired", "change"}),
            Map.entry("seen", new String[]{"paired", "change"}), Map.entry("runstop", new String[]{"paired", "change"}),
            Map.entry("back", new String[]{"anyone", "read"}));

    // ---------------------------------------------------------------- one request

    void handle(HttpExchange x) {
        try {
            if (ModelNetworks.refused(x)) return;   // a model server's network with no way out asks nothing of the library, at any door
            Headers h = x.getResponseHeaders();
            h.set("Content-Security-Policy", POLICY);
            h.set("X-Frame-Options", "DENY");
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            String method = x.getRequestMethod(), raw = x.getRequestURI().getRawPath();
            String route = routeOf(method, raw);
            // the JDK's server sends no answer to a request until its body is read whole, and once an answer's head is sent nothing ends
            // that wait but the sender (tried 2026-11-25: a close from another thread drains too); a close made before any head is sent
            // ends the connection at once. So every body but a message's pictures is read here, under the watchdog, before anything is
            // answered, and one larger than the door takes is closed unanswered; a message's body is read by its route, or, when it is
            // refused first, read and dropped (dropUpload)
            if (!"ask".equals(route) && declaresBody(x)) {
                if (Uploads.declared(x.getRequestHeaders().getFirst("Content-Length")) > SMALL_BYTES) { x.close(); return; }
                try (InputStream in = body(x, SMALL_BYTES, false)) { x.setAttribute(READ, in.readAllBytes()); }
                catch (IOException tooSlowOrLarge) { x.close(); return; }
            }
            if (route == null) { plain(x, 404, "Not found."); return; }
            if (route.equals("back")) { h.set("Location", "/m"); h.set("Cache-Control", "no-store"); x.sendResponseHeaders(302, -1); return; }   // reads nothing, spends nothing
            boolean paired = "paired".equals(ROUTES.get(route)[0]), change = "change".equals(ROUTES.get(route)[1]);
            String sender = sender(x);
            Phones.Phone phone = phoneOf(x);
            if (phone == null) {
                long wait = buckets.take("unpaired|" + sender, UNPAIRED);
                if (wait > 0) { if (dropUpload(x)) tooMany(x, wait); return; }
                if (paired) { if (dropUpload(x)) json(x, 403, M.createObjectNode().put("error", "not paired")); return; }
            }
            if (change && !fromThePage(x)) { if (dropUpload(x)) plain(x, 403, "This came from another page, so it was not done."); return; }
            if (!permit()) { if (dropUpload(x)) { x.getResponseHeaders().set("Retry-After", "5"); plain(x, 503, "The door is busy. Try again in a few seconds."); } return; }
            Patrons.Patron patron = phone == null ? Patrons.Patron.ANONYMOUS : PhoneRules.patron(phone);
            Chrome.here(Chrome.choose(store, patron, null, Pages.cookie(x, Chrome.COOKIE), x.getRequestHeaders().getFirst("Accept-Language")));
            try { PhonePage.serve(this, d, store, x, route, phone, patron, sender); }
            finally { work.release(); Chrome.clear(); }
        } catch (ProtocolError e) {
            try { json(x, LibrarianDaemon.status(e.code), M.createObjectNode().put("error", e.getMessage())); } catch (IOException ignored) { }
        } catch (Exception e) {
            LOG.warn("The phone door could not answer {}: {}", x.getRequestURI().getRawPath(), e.toString());
            try { plain(x, 500, "The library could not answer."); } catch (IOException ignored) { }
        } finally { x.close(); }
    }

    /** The exchange's attribute that holds a small body, read whole before anything was answered. */
    private static final String READ = "rz.phone.read";

    static boolean declaresBody(HttpExchange x) {
        String length = x.getRequestHeaders().getFirst("Content-Length");
        return (length != null && !length.strip().equals("0")) || x.getRequestHeaders().getFirst("Transfer-Encoding") != null;
    }

    /** The most of a message's body that is read and dropped to answer a refusal; a larger one is closed unanswered. */
    static final long DROP_BYTES = 256 * 1024;

    /**
     * A message's body, refused before its route read it: read and dropped under the watchdog so the refusal can be answered (true); or,
     * larger than {@link #DROP_BYTES} or too slow, the connection closed with no answer (false). Any other request's body was read already.
     */
    boolean dropUpload(HttpExchange x) {
        if (x.getAttribute(READ) != null || !declaresBody(x) || x.getAttribute(DROPPED) != null) return true;
        x.setAttribute(DROPPED, Boolean.TRUE);
        if (Uploads.declared(x.getRequestHeaders().getFirst("Content-Length")) > DROP_BYTES) { x.close(); return false; }
        try (InputStream in = body(x, DROP_BYTES, false)) { in.transferTo(OutputStream.nullOutputStream()); return true; }
        catch (IOException tooSlowOrLarge) { x.close(); return false; }
    }

    private static final String DROPPED = "rz.phone.dropped";

    private boolean permit() {
        if (work.tryAcquire()) return true;
        if (waiting.incrementAndGet() > LINE) { waiting.decrementAndGet(); return false; }
        try { return work.tryAcquire(LINE_WAIT_MS, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        finally { waiting.decrementAndGet(); }
    }

    /**
     * Whether a call that changes something came from the phone page's own script: the browser says the request is same-origin (or, when
     * it sends no Sec-Fetch-Site, an Origin of this address), and the page's own header is there, which a form cannot send and a script of
     * another site can send only after a preflight this door never answers.
     */
    static boolean fromThePage(HttpExchange x) {
        Headers h = x.getRequestHeaders();
        if (!"1".equals(h.getFirst(HEADER))) return false;
        String site = h.getFirst("Sec-Fetch-Site");
        if (site != null && !site.isBlank()) return site.strip().equalsIgnoreCase("same-origin");
        String origin = h.getFirst("Origin");
        if (origin == null || origin.isBlank()) return false;
        String o = origin.strip().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
        String host = OwnPage.last(h.getFirst("X-Forwarded-Host"));
        if (host.isEmpty()) host = h.getFirst("Host") == null ? "" : h.getFirst("Host").strip();
        return !host.isEmpty() && OwnPage.withoutDefaultPort(o, "https").equals(OwnPage.withoutDefaultPort(host.toLowerCase(Locale.ROOT), "https"));
    }

    /** The phone a request's cookie proves, or null. */
    static Phones.Phone phoneOf(HttpExchange x) throws IOException {
        String secret = Pages.cookie(x, COOKIE);
        return secret == null || secret.isBlank() ? null : Phones.bySecret(secret);
    }

    /**
     * Who sent a request, by the header the relay that is configured sets: Tailscale's proxy (tailnet, public) sets X-Forwarded-For to
     * the address the connection came from and replaces any the client sent (tailscale ipn/ipnlocal/serve.go, v1.104.7,
     * addProxyForwardedHeaders); a relay of the person's own is read by the header it documents ({@link #SENDER}, Cloudflare's
     * CF-Connecting-IP unless said). With that header missing or not an address, every sender is the relay: one count for all.
     */
    static String sender(HttpExchange x) {
        String k = kind();
        String header = k.equals("own") ? Config.live(SENDER) : "X-Forwarded-For";
        if (header == null || header.isBlank()) header = CLOUDFLARE_SENDER;
        String v = OwnPage.last(x.getRequestHeaders().getFirst(header.strip()));
        InetAddress a = literal(v);
        return a == null ? "relay" : RateLimits.counted(a);
    }

    /** An address written as numbers, never a name looked up; null for anything else. */
    static InetAddress literal(String v) {
        if (v == null || v.isBlank() || v.length() > 45) return null;
        String s = v.strip();
        if (!s.matches("\\d{1,3}(\\.\\d{1,3}){3}") && !s.matches("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*")) return null;
        try { return InetAddress.getByName(s); } catch (IOException e) { return null; }
    }

    // ---------------------------------------------------------------- limits in memory

    /** Buckets by key, each filling again at its rule's pace ({@link RateLimits#parse}'s words). */
    static final class Buckets {
        private record Bucket(double[] tokens, long[] at) { }
        private final Map<String, Bucket> all = new ConcurrentHashMap<>();
        private final AtomicLong swept = new AtomicLong();

        /** One taken: 0 when it may go on, else the seconds until one fits (nothing taken). */
        long take(String key, String rule) { return use(key, rule, true); }

        /** Whether one would be refused now: the seconds, or 0; nothing taken. */
        long peek(String key, String rule) { return use(key, rule, false); }

        private long use(String key, String words, boolean take) {
            RateLimits.Rule r = RateLimits.parse(words);
            if (r == null) return 0;
            long now = System.currentTimeMillis();
            sweep(now, r);
            Bucket b = all.computeIfAbsent(key, k -> new Bucket(new double[]{r.burst()}, new long[]{now}));
            synchronized (b) {
                b.tokens()[0] = Math.min(r.burst(), b.tokens()[0] + (now - b.at()[0]) * r.perMs());
                b.at()[0] = now;
                if (b.tokens()[0] >= 1.0) { if (take) b.tokens()[0] -= 1.0; return 0; }
                return Math.max(1, (long) Math.ceil((1.0 - b.tokens()[0]) / r.perMs() / 1000.0));
            }
        }

        private void sweep(long now, RateLimits.Rule r) {
            if (all.size() < 5_000 || now - swept.get() < 10_000) return;
            swept.set(now);
            all.entrySet().removeIf(e -> { Bucket b = e.getValue(); synchronized (b) { return now - b.at()[0] > 3_600_000L; } });
        }
    }

    Buckets buckets() { return buckets; }

    // ---------------------------------------------------------------- bodies, under a watchdog

    /**
     * The request's body as a stream that the watchdog ends: a small body must come whole within {@link #SMALL_BODY_MS}; a message's
     * pictures get {@link #UPLOAD_GRACE_MS}, then must average {@link #UPLOAD_FLOOR} bytes a second. Past either, or past {@code max}
     * bytes, the exchange is closed and nothing of it is kept. Slow headers never reach here: Tailscale's proxy reads a request's headers
     * whole before it opens anything to this door.
     */
    InputStream body(HttpExchange x, long max, boolean upload) {
        long t0 = System.currentTimeMillis();
        AtomicLong got = new AtomicLong();
        boolean[] done = {false};
        var tick = watchdog.scheduleAtFixedRate(() -> {
            if (done[0]) return;
            long ms = System.currentTimeMillis() - t0;
            boolean late = upload ? ms > UPLOAD_GRACE_MS && got.get() < (ms - UPLOAD_GRACE_MS) / 1000.0 * UPLOAD_FLOOR : ms > SMALL_BODY_MS;
            if (late) { done[0] = true; LOG.info("The phone door closed a request whose body came too slowly ({} bytes in {} ms).", got.get(), ms); x.close(); }
        }, 250, 250, TimeUnit.MILLISECONDS);
        return new FilterInputStream(x.getRequestBody()) {
            private void count(long n) throws IOException { if (n > 0 && got.addAndGet(n) > max) throw new IOException("The request is larger than the door takes."); }
            @Override public int read() throws IOException { int b = super.read(); if (b >= 0) count(1); else end(); return b; }
            @Override public int read(byte[] buf, int off, int len) throws IOException { int n = super.read(buf, off, len); if (n > 0) count(n); else if (n < 0) end(); return n; }
            private void end() { done[0] = true; tick.cancel(false); }
            @Override public void close() throws IOException { end(); super.close(); }
        };
    }

    /** A small body read whole under the watchdog, as text; at most {@code max} bytes. */
    String small(HttpExchange x, int max) throws IOException {
        byte[] b = x.getAttribute(READ) instanceof byte[] r ? r : new byte[0];   // read whole before anything was answered
        if (b.length > max) throw ProtocolError.invalidArgs("The request is larger than the door takes.");
        return new String(b, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- answers

    static void plain(HttpExchange x, int status, String said) throws IOException {
        answer(x, status, "text/plain; charset=utf-8", "no-store", (said + "\n").getBytes(StandardCharsets.UTF_8));
    }

    static void json(HttpExchange x, int status, JsonNode body) throws IOException {
        answer(x, status, "application/json; charset=utf-8", "no-store", M.writeValueAsBytes(body));
    }

    static void bytes(HttpExchange x, String type, byte[] b, boolean cache) throws IOException {
        answer(x, 200, type, cache ? "public, max-age=86400" : "no-store", b);
    }

    /** A text answer longer than this goes compressed to a browser that takes gzip; a shorter one gains nothing from it. */
    static final int COMPRESS_FROM = 1024;

    /**
     * Every answer the door writes with a body. A text answer (JSON, HTML, the script, the manifest, SVG) longer than
     * {@link #COMPRESS_FROM} bytes goes gzipped when the request takes gzip, with its compressed length; every text answer says
     * {@code Vary: Accept-Encoding}, since it could go either way; the icons, already compressed, go as they are.
     *
     * <p>Compression is safe here against BREACH, the attack that reads a secret from compressed sizes: it needs another site to
     * make the phone ask many times, and a secret in the same body as text the attacker chose. Another site cannot make the
     * phone ask with its pairing (the cookie is SameSite=Strict, and every request that does something needs the page's own
     * header, which another site cannot send without a preflight the door never answers); and no answer of the door holds a
     * secret: the cookie's value goes only in the Set-Cookie header, which gzip does not touch; the pairing code is read by the
     * page's script from the address and never written into an answer; an error never repeats a code or a cookie. A test asks
     * every route and looks for both in the bodies ({@code PhoneCompressTest}).
     */
    static void answer(HttpExchange x, int status, String type, String cache, byte[] b) throws IOException {
        Headers h = x.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", cache);
        if (text(type)) {
            h.add("Vary", "Accept-Encoding");
            if (b.length > COMPRESS_FROM && takesGzip(x.getRequestHeaders().getFirst("Accept-Encoding"))) {
                b = gzip(b);
                h.set("Content-Encoding", "gzip");
            }
        }
        x.sendResponseHeaders(status, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    /** Whether an answer of this type is text, which compresses; a picture or anything else already compressed is not. */
    static boolean text(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return t.startsWith("text/") || t.startsWith("application/json") || t.startsWith("application/manifest+json")
                || t.startsWith("application/javascript") || t.startsWith("image/svg+xml");
    }

    /**
     * Whether an Accept-Encoding takes gzip: "gzip" (or "x-gzip") with a q above 0, or else "*" with a q above 0. A gzip named
     * with q=0 is refused whatever "*" says.
     */
    static boolean takesGzip(String acceptEncoding) {
        if (acceptEncoding == null || acceptEncoding.isBlank()) return false;
        Double gzip = null, any = null;
        for (String part : acceptEncoding.split(",")) {
            String[] p = part.split(";");
            String name = p[0].strip().toLowerCase(Locale.ROOT);
            double q = 1;
            for (int i = 1; i < p.length; i++) {
                String param = p[i].strip().toLowerCase(Locale.ROOT);
                if (param.startsWith("q=")) { try { q = Double.parseDouble(param.substring(2).strip()); } catch (NumberFormatException bad) { q = 0; } }
            }
            if (name.equals("gzip") || name.equals("x-gzip")) gzip = gzip == null ? q : Math.max(gzip, q);
            else if (name.equals("*")) any = q;
        }
        return gzip != null ? gzip > 0 : any != null && any > 0;
    }

    static byte[] gzip(byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length / 4 + 64);
        try (GZIPOutputStream z = new GZIPOutputStream(out)) { z.write(b); }
        return out.toByteArray();
    }

    static void tooMany(HttpExchange x, long seconds) throws IOException {
        x.getResponseHeaders().set("Retry-After", String.valueOf(seconds));
        json(x, 429, M.createObjectNode().put("error", Pages.t("Too many requests from this address. Try again in {0}.", RateLimits.in(seconds))));
    }

    /** The cookie that makes this browser the phone, for 400 days, set again at every open. */
    static void cookie(HttpExchange x, String secret) {
        x.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + secret + "; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=" + COOKIE_SECONDS);
    }

    /** A form's fields, from an urlencoded body or query. */
    static Map<String, String> form(String encoded) {
        Map<String, String> m = new HashMap<>();
        if (encoded == null || encoded.isBlank()) return m;
        for (String kv : encoded.split("&")) {
            int eq = kv.indexOf('=');
            try { m.put(URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8), eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException bad) { /* a field that is no field */ }
        }
        return m;
    }

    LibraryStore store() { return store; }
}
