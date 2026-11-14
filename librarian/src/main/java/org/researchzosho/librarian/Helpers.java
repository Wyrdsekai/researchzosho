package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;

import java.util.function.LongSupplier;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Where the heavy helpers run (0.7.4): MetricX-24, LaBSE, the likeness helper, speech-to-text and the embeddings server. Each can be
 * served by another machine and named by an address, in one way: its own setting ({@code RESEARCHZOSHO_METRICX}, {@code _LABSE},
 * {@code _LIKENESS}, {@code _WHISPER}, {@code _EMBED}: an address, or off), else the machine that serves several at once
 * ({@code RESEARCHZOSHO_HELPERS}, the address of {@code researchzosho helpers serve} there), each helper under its own path
 * ({@code http://homebox:8217/metricx}) when that machine says it serves it. The helpers' secret ({@code RESEARCHZOSHO_HELPERS_SECRET})
 * goes with every call to a helper this program serves. {@code RESEARCHZOSHO_LOCAL_HELPERS=off} says this machine runs none of them:
 * a helper no address names is then not started here, and the work goes on without it and says so.
 *
 * <p>Left out, from the inventory of what runs here: the video route (this machine's own way out to YouTube, and light) and ffmpeg
 * (frames of a file this machine has just fetched, seconds to minutes a video; moving it means moving the file).
 */
public final class Helpers {
    private Helpers() { }

    private static final ObjectMapper M = new ObjectMapper();
    public static final String HELPERS = "RESEARCHZOSHO_HELPERS", SECRET_NAME = "RESEARCHZOSHO_HELPERS_SECRET", LOCAL = "RESEARCHZOSHO_LOCAL_HELPERS";
    /** On a machine that serves the helpers: the card they run on, as {@code --gpu} or {@code --cpu} chose it last (an index, a UUID, or cpu). */
    public static final String GPU_NAME = "RESEARCHZOSHO_HELPERS_GPU";
    /**
     * On a machine that serves the helpers: whether MetricX-24 may move to the processor when its card has no room (0.7.5). Unset or on:
     * as before; off ({@code --no-cpu-fallback}): it never does, and waits for room on its card. The service's unit carries the flag.
     */
    public static final String CPU_FALLBACK_NAME = "RESEARCHZOSHO_HELPERS_CPU_FALLBACK";

    /** Whether this machine is set to refuse MetricX-24's move to the processor. */
    static boolean noCpuFallback() { return Config.get(CPU_FALLBACK_NAME, "").strip().equalsIgnoreCase("off"); }
    /** The port {@code helpers serve} listens on unless told otherwise. */
    public static final int PORT = 8217;

    /** A heavy helper: its name (the path under a helpers address), its own setting, and what a person calls it. */
    public enum Helper {
        METRICX("metricx", "RESEARCHZOSHO_METRICX", "MetricX-24"),
        LABSE("labse", "RESEARCHZOSHO_LABSE", "LaBSE"),
        LIKENESS("likeness", "RESEARCHZOSHO_LIKENESS", "the likeness helper"),
        WHISPER("whisper", "RESEARCHZOSHO_WHISPER", "speech-to-text"),
        EMBED("embed", "RESEARCHZOSHO_EMBED", "the embeddings server");
        public final String path, setting, said;
        Helper(String path, String setting, String said) { this.path = path; this.setting = setting; this.said = said; }
        public static Helper of(String name) { for (Helper h : values()) if (h.path.equalsIgnoreCase(name)) return h; return null; }
        public static Helper ofSetting(String key) { for (Helper h : values()) if (h.setting.equals(key)) return h; return null; }
    }

    /** Whether this machine may run the heavy helpers itself: {@code RESEARCHZOSHO_LOCAL_HELPERS} not off. */
    public static boolean localAllowed() { return !Config.get(LOCAL, "").strip().equalsIgnoreCase("off"); }

    /** The line a command prints when this machine is set to run none of them and is asked to start one (in English, as the commands' other lines). */
    static String localRefusedSaid(String what) {
        return "This machine is set to run no heavy helper (local_helpers off), so " + what + " is not started here: name a machine that serves it with researchzosho settings set helpers http://<that machine>:" + PORT + ", or allow it here with researchzosho settings set local_helpers on.";
    }

    /** Whether the helper's own setting says off. */
    public static boolean off(Helper h) { return Config.get(h.setting, "").strip().equalsIgnoreCase("off"); }

    /**
     * The helper's address on another machine, without a trailing slash, or null: its own setting when that is an address; else the
     * helpers address with the helper's path, when that machine says it serves it, or when it does not answer at all (it is named, and
     * the work says it did not answer rather than starting this machine's own in its place). Off, or neither set: null.
     */
    public static String address(Helper h) {
        String own = Config.get(h.setting, "").strip();
        if (own.equalsIgnoreCase("off")) return null;
        if (own.matches("(?i)https?://.+")) return own.replaceAll("/+$", "");
        String base = base();
        if (base == null) return null;
        Map<String, String> serves = SERVES.apply(base);
        return serves == null || !serves.getOrDefault(h.path, "").isEmpty() ? base + "/" + h.path : null;
    }

    /**
     * The helpers address, or null: the one {@code helpers} names; else, when {@code helpers} is not set at all, the helpers server
     * found on the machine that serves the main model ({@link #found}), when it was found and may be used. {@code helpers off}: none.
     */
    public static String base() {
        String set = explicitBase();
        if (set != null) return set.equals("off") ? null : set;
        Look l = found();
        return l != null && l.state().equals(Look.FOUND) ? l.base() : null;
    }

    /** What {@code helpers} names: an address without a trailing slash, "off", or null when it names nothing (the look is made then). */
    static String explicitBase() {
        String b = Config.get(HELPERS, "").strip();
        if (b.equalsIgnoreCase("off")) return "off";
        return b.matches("(?i)https?://.+") ? b.replaceAll("/+$", "") : null;
    }

    // ---- the helpers server on the machine that serves the main model, found by itself (0.7.4) ----

    /**
     * What a look at a machine's helpers port found: FOUND, with what it serves (null when a machine found before has gone silent: it
     * stays the one named) and whether it answered as this program's front asking for the secret ({@code ourFront}, the one condition on
     * which the secret goes to it); NEEDS_SECRET, our front, which refused the secret this library has or has none to give; NOTHING.
     * {@code away}: the machine gave no connection, so nothing is kept of the look and {@link #away} answers the next asks.
     */
    record Look(String state, String base, Map<String, String> serves, boolean ourFront, boolean away, boolean stranger) {
        static final String FOUND = "found", NEEDS_SECRET = "needs secret", NOTHING = "nothing";
        Look(String state, String base, Map<String, String> serves, boolean ourFront, boolean away) { this(state, base, serves, ourFront, away, false); }
    }

    /**
     * The look itself; a test stands it in. The test task turns it off for the whole suite (the property researchzosho.helpers.find=off),
     * as it keeps the search and the drive off the network: a test that names a model server by a machine's name would otherwise ask
     * that machine's helpers port.
     */
    static volatile Function<String, Look> LOOK = "off".equals(System.getProperty("researchzosho.helpers.find")) ? b -> new Look(Look.NOTHING, b, null, false, false) : Helpers::lookNow;

    /** How long a look is kept: what it found, and that it found nothing. A machine that gave no connection is {@link #away} instead. */
    static final long LOOK_KEPT_MS = 300_000;
    private static final Map<String, Object[]> LOOKED = new ConcurrentHashMap<>();
    /** The machines found in this process, each with whether it was our front: one that goes silent later stays the one named. */
    private static final Map<String, Boolean> FOUND_ONCE = new ConcurrentHashMap<>();

    /**
     * Where a helpers server of the machine that serves the main model would answer: {@code http://<its host>:8217}, or null. Only the
     * default entry's host, only when that is another machine of the person's: never loopback (this machine's own helpers are not looked
     * for this way), never a hosted service (its host is never asked), and only a private network address or a name of the local
     * network (no dot, or .local, .lan, .home, .home.arpa, .internal, .localdomain, .ts.net), so that a hosted service this program does
     * not know by name is never asked either.
     */
    static String candidate() { return candidate(ModelEntries.defaultEntry()); }

    static String candidate(ModelEntries.Entry e) {
        if (e == null || e.hosted()) return null;
        String host = hostOf(e.drive());
        if (host == null || loopbackHost(host) || !personsMachine(host)) return null;
        return AT.apply(host);
    }

    /** The helpers address a machine's host gives: {@code http://<host>:8217}; a test points a made-up machine at a stand-in. */
    static volatile Function<String, String> AT = host -> "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + PORT;

    /** The host of an address, lower-cased, without brackets; null when it has none. */
    static String hostOf(String address) {
        try { URI u = URI.create(address == null ? "" : address.strip()); String h = u.getHost(); return h == null ? null : h.replaceAll("^\\[|]$", "").toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException e) { return null; }
    }

    static boolean loopbackHost(String host) {
        return host.equals("localhost") || host.endsWith(".localhost") || host.startsWith("127.") || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1") || host.equals("0.0.0.0");
    }

    /** A private network address (10/8, 172.16/12, 192.168/16, 100.64/10, 169.254/16, fc00::/7, fe80::/10), or a name of the local network. */
    static boolean personsMachine(String host) {
        if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            String[] p = host.split("\\."); int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254);
        }
        if (host.contains(":")) return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb");
        if (!host.contains(".")) return true;
        for (String local : List.of(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".ts.net")) if (host.endsWith(local)) return true;
        return false;
    }

    /**
     * The look at the main model's machine, when {@code helpers} names nothing and there is a machine to look at; null otherwise. Kept
     * five minutes (what it found, or that nothing is there; a change of the secret is a new look), a machine that gave no connection
     * not looked at again while it is {@link #away}. A machine found before in this process that answers nothing now stays found, with
     * nothing known of what it serves, as a set address that goes silent stays named.
     */
    static Look found() {
        if (explicitBase() != null) return null;
        String base = candidate();
        if (base == null) return null;
        String key = base + "|" + Integer.toHexString(Config.get(SECRET_NAME, "").strip().hashCode());
        Object[] kept = LOOKED.get(key);
        Look l;
        if (kept != null && NOW.getAsLong() - (long) kept[0] < LOOK_KEPT_MS) l = (Look) kept[1];
        else {
            l = away(base) ? new Look(Look.NOTHING, base, null, false, true) : LOOK.apply(base);
            // kept five minutes, also when the machine gave no connection: a laptop away from a machine never found asks it once in five
            // minutes, not once in each half minute; a machine found before is asked again as soon as it is not away, so it comes back quickly
            if (!(l.away() && knownFound(base) != null)) LOOKED.put(key, new Object[]{NOW.getAsLong(), l});
        }
        if (l.state().equals(Look.FOUND)) { FOUND_ONCE.put(base, l.ourFront()); keepFound(base, l.ourFront()); }
        else if (l.state().equals(Look.NOTHING) && l.stranger()) { FOUND_ONCE.remove(base); dropFound(base); }   // another program answers there now
        else if (l.state().equals(Look.NOTHING)) { Boolean front = knownFound(base); if (front != null) return new Look(Look.FOUND, base, null, front, l.away()); }
        return l;
    }

    /** The clock of the looks and of {@link #away}; a test moves it. */
    static volatile LongSupplier NOW = System::currentTimeMillis;

    // ---- what was found, kept across processes ----

    /**
     * The helpers server found on the model's machine, kept in the state folder ({@code helpers-found.json}: its address and whether it
     * answered as our front), so that a command run while that machine is away says what the service says, that it does not answer,
     * and does not take it for a machine that has none. Written when found; removed when another program answers there; read when a
     * look finds nothing or no connection. A set address, or {@code helpers off}, makes it irrelevant: no look is made then.
     */
    static Path foundFile() { return Config.home().resolve("helpers-found.json"); }

    /** Whether {@code base} was found, in this process or one before; its front flag, or null when it was not. */
    static Boolean knownFound(String base) {
        Boolean f = FOUND_ONCE.get(base);
        if (f != null) return f;
        try {
            if (!Files.isRegularFile(foundFile())) return null;
            JsonNode j = M.readTree(Files.readString(foundFile(), StandardCharsets.UTF_8));
            return base.equals(j.path("base").asText("")) ? j.path("our_front").asBoolean(false) : null;
        } catch (IOException | RuntimeException e) { return null; }
    }

    static void keepFound(String base, boolean ourFront) {
        try {
            String text = M.createObjectNode().put("base", base).put("our_front", ourFront).toString() + "\n";
            if (Files.isRegularFile(foundFile()) && Files.readString(foundFile(), StandardCharsets.UTF_8).equals(text)) return;
            Files.createDirectories(foundFile().getParent());
            AtomicWrite.text(foundFile(), text);
        } catch (IOException | RuntimeException ignored) { /* kept in this process all the same */ }
    }

    static void dropFound(String base) {
        try {
            if (Files.isRegularFile(foundFile()) && base.equals(M.readTree(Files.readString(foundFile(), StandardCharsets.UTF_8)).path("base").asText(""))) Files.deleteIfExists(foundFile());
        } catch (IOException | RuntimeException ignored) { }
    }

    /** The found helpers server that needs this library's secret, or null: what the status, a plan and setup say with the line that sets it. */
    static String needsSecret() { Look l = found(); return l != null && l.state().equals(Look.NEEDS_SECRET) ? l.base() : null; }

    /** Whether the helpers address in use was found on the model's machine rather than set. */
    static boolean foundInUse() { Look l = explicitBase() == null ? found() : null; return l != null && l.state().equals(Look.FOUND); }

    /**
     * One look at {@code base}/health. 200 with the list of a helpers server: found. 401 with our front's own refusal: asked again with
     * the secret, when this library has one, and found when that gives 200; else it needs the secret. The secret is sent only after the
     * machine answered as our front does, never on the first ask. Anything else, or no answer: nothing there.
     */
    static Look lookNow(String base) {
        long t0 = NOW.getAsLong();
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) { Map<String, String> m = helpersIn(r.body()); return m == null ? new Look(Look.NOTHING, base, null, false, false, true) : new Look(Look.FOUND, base, m, false, false); }
            if (r.statusCode() != 401 || !HelperServer.refusal(r.body())) return new Look(Look.NOTHING, base, null, false, false, true);   // another program's server there
            String secret = Config.get(SECRET_NAME, "").strip();
            if (secret.isEmpty()) return new Look(Look.NEEDS_SECRET, base, null, true, false);
            HttpResponse<String> again = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + secret).GET().build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> m = again.statusCode() == 200 ? helpersIn(again.body()) : null;
            return m == null ? new Look(Look.NEEDS_SECRET, base, null, true, false) : new Look(Look.FOUND, base, m, true, false);
        } catch (Exception e) {
            failed(base, e, t0);
            return new Look(Look.NOTHING, base, null, false, away(base));
        }
    }

    /** The helpers a helpers server's health lists, by name; null for a reply that is not one. */
    static Map<String, String> helpersIn(String body) {
        try {
            JsonNode h = M.readTree(body == null ? "" : body).path("helpers");
            if (!h.isObject()) return null;
            Map<String, String> out = new LinkedHashMap<>();
            h.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
            return out;
        } catch (Exception e) { return null; }
    }

    /**
     * Whether an address is one this program serves, so the helpers' secret goes with it: under the helpers address {@code helpers}
     * names; under the one found on the model's machine only when that answered as our front asking for it; a MetricX-24 or likeness
     * address (only this program serves those); or a helper's own address in the form {@code helpers serve} gives it (…/labse,
     * …/whisper, …/embed). A server of another program that a setting names, such as an embeddings server, never gets it.
     */
    static boolean ours(String address) {
        if (address == null) return false;
        String a = address.replaceAll("/+$", ""), b = explicitBase();
        if (b != null && !b.equals("off") && (a.equals(b) || a.startsWith(b + "/"))) return true;
        Look l = b == null ? found() : null;
        if (l != null && l.state().equals(Look.FOUND) && l.ourFront() && (a.equals(l.base()) || a.startsWith(l.base() + "/"))) return true;
        for (Helper h : Helper.values()) {
            String own = Config.get(h.setting, "").strip().replaceAll("/+$", "");
            if (!own.matches("(?i)https?://.+") || !(a.equals(own) || a.startsWith(own + "/"))) continue;
            if (h == Helper.METRICX || h == Helper.LIKENESS || own.endsWith("/" + h.path)) return true;
        }
        return false;
    }

    /** A request to a helper, with the secret when the address is one of ours and a secret is set. */
    public static HttpRequest.Builder request(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        String secret = Config.get(SECRET_NAME, "").strip();
        if (!secret.isEmpty() && ours(url)) b.header("Authorization", "Bearer " + secret);
        return b;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    // ---- a machine that is away ----

    /**
     * How long a machine that gave no connection is taken as away, in milliseconds. A laptop off its home network names a machine it
     * cannot reach, and every call to it waited out its own connection: a search took sixteen seconds, measured, each time. Now the
     * first call that finds the machine away is the only one that waits, and the calls of the next half minute know it already.
     */
    static final long AWAY_MS = 30_000;
    /** A failure quicker than this marks nothing: a refused connection costs no wait, and a server that is starting is asked again at once. */
    static final long SLOW_FAILURE_MS = 1_000;
    private static final Map<String, Long> AWAY = new ConcurrentHashMap<>();

    /** The machine and port of an address ("http://homebox:8217"), or "" for what is no address. */
    static String origin(String address) {
        try {
            URI u = URI.create(address == null ? "" : address);
            if (u.getScheme() == null || u.getHost() == null) return "";
            return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT) + (u.getPort() < 0 ? "" : ":" + u.getPort());
        } catch (IllegalArgumentException e) { return ""; }
    }

    /** Whether the machine at an address gave no connection a moment ago, so a call to it now would only wait again. */
    public static boolean away(String address) {
        Long at = AWAY.get(origin(address));
        return at != null && NOW.getAsLong() - at < AWAY_MS;
    }

    /**
     * Notes a call that failed, begun at {@code startedMs}: when no connection was made (it timed out, or the machine could not be
     * reached or found) and the failure took its time, the machine is taken as away for {@link #AWAY_MS}. An answer of any kind, a
     * refusal at once, and a failure after the connection mark nothing.
     */
    public static void failed(String address, Throwable e, long startedMs) {
        if (NOW.getAsLong() - startedMs < SLOW_FAILURE_MS) return;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException || t instanceof UnresolvedAddressException) {
                String o = origin(address);
                if (!o.isEmpty()) AWAY.put(o, NOW.getAsLong());
                return;
            }
        }
    }

    /**
     * What a helpers address says it serves, each name with its health line; null when it does not answer (or refuses the secret). An
     * answer is kept five minutes, a silence half a minute; a test stands it in.
     */
    static volatile Function<String, Map<String, String>> SERVES = Helpers::servesNow;
    private static final Map<String, Object[]> CACHE = new LinkedHashMap<>();

    static Map<String, String> servesNow(String base) {
        synchronized (CACHE) {
            Object[] c = CACHE.get(base);
            if (c != null && System.currentTimeMillis() - (long) c[0] < (c[1] == null ? 30_000 : 300_000)) { @SuppressWarnings("unchecked") Map<String, String> m = (Map<String, String>) c[1]; return m; }
        }
        Map<String, String> out = null;
        long t0 = NOW.getAsLong();
        if (!away(base)) try {
            HttpResponse<String> r = HTTP.send(request(base + "/health").timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) { Map<String, String> got = new LinkedHashMap<>(); M.readTree(r.body()).path("helpers").fields().forEachRemaining(e -> got.put(e.getKey(), e.getValue().asText(""))); out = got; }
        } catch (Exception e) { failed(base, e, t0); /* not answering */ }
        synchronized (CACHE) { CACHE.put(base, new Object[]{System.currentTimeMillis(), out}); }
        return out;
    }

    /** Forgets what the helpers addresses said; for tests and after a setting changes. */
    static void forget() { synchronized (CACHE) { CACHE.clear(); } AWAY.clear(); LOOKED.clear(); FOUND_ONCE.clear(); }

    /** Forgets the looks alone, as five minutes do, and keeps what was found in this process; for a test. */
    static void forgetLooks() { synchronized (CACHE) { CACHE.clear(); } LOOKED.clear(); }

    /** Whether a helper's address refuses this library's secret (HTTP 401 at its health). */
    static boolean refusesSecret(String address) {
        if (away(address)) return false;
        long t0 = NOW.getAsLong();
        try { return HTTP.send(request(address + "/health").timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 401; }
        catch (Exception e) { failed(address, e, t0); return false; }
    }

    /** A helper's health line at its address: what the model is and where it runs ("google/metricx-24-hybrid-large-v2p6 on cpu"), or null when it does not answer. */
    static String health(String address) {
        if (away(address)) return null;
        long t0 = NOW.getAsLong();
        try {
            HttpResponse<String> r = HTTP.send(request(address + "/health").timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            if (r.body() == null || r.body().isBlank()) return "";   // a server whose health says nothing more than that it answers (TEI's)
            JsonNode j;
            try { j = M.readTree(r.body()); }
            catch (IOException plainWords) { return ""; }   // a proxy that answers its health in plain words ("OK", llama-swap's): it answers, and says nothing more
            String model = j.path("model").asText(""), device = j.path("device").asText("");
            if (model.isEmpty()) return device.isEmpty() ? "" : "on " + device;
            return HelperServer.healthLine(model, device, j.path("card").asText(""), "", List.of());   // the card by its name: its index is the other machine's to say
        } catch (Exception e) { failed(address, e, t0); return null; }
    }

    /**
     * A helper's health line at its address, as {@link #health(String)} gives it; and for the two that answer the embeddings call
     * (LaBSE, the embeddings server), whose server may be another program's with no health page at all, "" when that call answers.
     * Null when it does not answer. A status line that said "does not answer now" of an embeddings server in use came from here: its
     * proxy answered the health in plain words, which read as no answer.
     */
    /**
     * Why a helper the helpers machine lists does not serve now, as its front says it (0.7.5): the text of a 503 there ("not loaded: no
     * room on card 3 (0.4 GB free)", "not loaded: its process ended (exit 1)"), or null when it answers otherwise or not at all.
     */
    static String unavailable(String address) {
        if (away(address)) return null;
        long t0 = NOW.getAsLong();
        try {
            HttpResponse<String> r = HTTP.send(request(address + "/health").timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 503) return null;
            String why = M.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body()).path("error").asText("");
            return why.isBlank() ? null : why;
        } catch (Exception e) { failed(address, e, t0); return null; }
    }

    static String health(Helper h, String address) {
        String line = health(address);
        if (line == null && (h == Helper.LABSE || h == Helper.EMBED) && !away(address) && Embed.answers(address)) return "";
        return line;
    }
}
