package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.researchzosho.Config;
import org.researchzosho.librarian.Helpers.Helper;

import java.util.function.Predicate;
import java.util.function.LongSupplier;
import java.util.function.IntFunction;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The heavy helpers served to other machines (0.7.4): {@code researchzosho helpers serve} on a machine that can carry them. One address,
 * one port ({@link Helpers#PORT} unless told), one secret: each helper under its own path ({@code /metricx}, {@code /labse},
 * {@code /likeness}, {@code /whisper}, {@code /embed}), and {@code GET /health} naming what is served and where it runs. Behind it the
 * helpers stay on this machine's loopback: MetricX-24 and the likeness helper in their own Python environments, in their serving mode
 * (started here, on the card when there is one with room, else the processor), and the LaBSE, embeddings and speech-to-text servers as
 * they already run here. Every request carries the secret ({@code Authorization: Bearer}); a server listening beyond this
 * machine refuses to start without one, so it is never open to the network it sits on.
 */
final class HelperServer implements AutoCloseable {
    private static final ObjectMapper M = new ObjectMapper();
    private final HttpServer server;
    private final Map<String, String> backends;
    /** The helpers this front started itself (MetricX-24, the likeness helper), listed whether they are loaded or not. */
    private final Map<String, Managed> managed;
    /** Helpers this set was installed to serve that are not set up here: listed, each with why. */
    private final Map<String, String> absent;
    private final Map<String, String> health;
    private volatile long healthAt = System.currentTimeMillis();
    private final List<Process> processes;
    private final byte[] secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HelperServer(HttpServer server, Map<String, String> backends, Map<String, Managed> managed, Map<String, String> absent, Map<String, String> health, List<Process> processes, String secret) {
        this.server = server; this.backends = backends; this.managed = managed; this.absent = absent; this.health = health; this.processes = processes;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    /** What the front answers a call without the secret, or with another: the reply a library's look knows this program's front by. */
    static final String REFUSED = "{\"error\":\"the secret is missing or wrong\"}";

    /** Whether a reply's body is the front's refusal ({@link #REFUSED}), and so the machine runs this program's front. */
    static boolean refusal(String body) {
        try { return M.readTree(body == null ? "" : body).path("error").asText("").equals("the secret is missing or wrong"); } catch (Exception e) { return false; }
    }

    /** The address it listens on. */
    int port() { return server.getAddress().getPort(); }

    /**
     * Serves the helpers in {@code backends} (a name, the loopback address that answers for it) on {@code bind}:{@code port}; the health
     * line of each is what its backend's own health says. A bind beyond loopback needs a secret.
     */
    static HelperServer serve(String bind, int port, String secret, Map<String, String> backends, List<Process> processes) throws IOException {
        return serve(bind, port, secret, new Backends(backends, new LinkedHashMap<>(), new LinkedHashMap<>()), processes);
    }

    /** The same for what {@link #startBackends} made: the servers it proxies, the helpers it started itself, the ones it was meant to serve and are not set up. */
    static HelperServer serve(String bind, int port, String secret, Backends b, List<Process> processes) throws IOException {
        if ((secret == null || secret.isBlank()) && !loopback(bind)) throw new IOException("A server listening beyond this machine needs a secret: --secret <words> or --secret-file <file>.");
        Map<String, String> health = new LinkedHashMap<>();
        for (var e : b.proxied().entrySet()) health.put(e.getKey(), healthOf(e.getValue()));
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind), port), 32);
        HelperServer h = new HelperServer(s, new LinkedHashMap<>(b.proxied()), new LinkedHashMap<>(b.managed()), new LinkedHashMap<>(b.absent()), health, processes, secret);
        s.createContext("/", h::handle);
        s.setExecutor(Executors.newFixedThreadPool(8, r -> { Thread t = new Thread(r, "helpers-serve"); t.setDaemon(true); return t; }));
        s.start();
        return h;
    }

    static boolean loopback(String bind) {
        try { return InetAddress.getByName(bind).isLoopbackAddress(); } catch (IOException e) { return false; }
    }

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            if (secret.length > 0) {
                String auth = x.getRequestHeaders().getFirst("Authorization");
                byte[] given = auth == null || !auth.startsWith("Bearer ") ? new byte[0] : auth.substring(7).strip().getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(given, secret)) { reply(x, 401, REFUSED); return; }
            }
            String path = x.getRequestURI().getRawPath();
            if (path.equals("/health") || path.equals("/health/") || path.equals("/")) {
                ObjectNode o = M.createObjectNode(); ObjectNode hs = o.putObject("helpers");
                // asked again when half a minute old: a container that was still starting when the front started answers now
                if (System.currentTimeMillis() - healthAt > 30_000) { synchronized (health) { for (var e : backends.entrySet()) health.put(e.getKey(), healthOf(e.getValue())); for (Managed m : managed.values()) m.healthLine = null; healthAt = System.currentTimeMillis(); } }
                for (var e : health.entrySet()) hs.put(e.getKey(), e.getValue());
                for (var e : managed.entrySet()) hs.put(e.getKey(), e.getValue().line());   // listed whether loaded or not, with why not
                for (var e : absent.entrySet()) hs.put(e.getKey(), e.getValue());
                reply(x, 200, o.toString());
                return;
            }
            String name = path.replaceFirst("^/", "").split("/", 2)[0], rest = path.substring(name.length() + 1);
            Managed own = managed.get(name);
            String to = own != null ? own.forRequest() : backends.get(name);
            if (own != null && to == null) { reply(x, 503, M.createObjectNode().put("error", own.line()).toString()); return; }   // not loaded now: why, and a new try when it is due
            if (to == null && absent.containsKey(name)) { reply(x, 503, M.createObjectNode().put("error", absent.get(name)).toString()); return; }
            if (to == null) { reply(x, 404, "{\"error\":\"this machine does not serve " + name.replaceAll("[^a-z]", "") + "\"}"); return; }
            String q = x.getRequestURI().getRawQuery();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(to + (rest.isEmpty() ? "/" : rest) + (q == null ? "" : "?" + q))).timeout(Duration.ofMinutes(30));
            String type = x.getRequestHeaders().getFirst("Content-Type");
            if (type != null) b.header("Content-Type", type);
            byte[] body;
            try (InputStream in = x.getRequestBody()) { body = in.readAllBytes(); }
            b.method(x.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> r;
            try { r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); reply(x, 503, "{\"error\":\"interrupted\"}"); return; }
            catch (IOException e) { reply(x, 502, "{\"error\":\"" + name + " does not answer on this machine\"}"); return; }
            x.getResponseHeaders().set("Content-Type", r.headers().firstValue("Content-Type").orElse("application/json"));
            x.sendResponseHeaders(r.statusCode(), r.body().length == 0 ? -1 : r.body().length);
            if (r.body().length > 0) try (OutputStream os = x.getResponseBody()) { os.write(r.body()); }
        }
    }

    private static void reply(HttpExchange x, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    /**
     * A backend's health line: "<model> on <device>" from its own /health; for a Text Embeddings Inference server, whose health is empty,
     * the model its /info names; "answering" for a server that says neither.
     */
    static String healthOf(String backend) {
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(backend + "/health")).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200 && !r.body().isBlank()) {
                var j = M.readTree(r.body());
                String line = healthLine(j.path("model").asText(""), j.path("device").asText(""), j.path("card").asText(""), j.path("card_uuid").asText(""), j.has("card_uuid") ? HelperCards.cards() : List.of());
                if (!line.isEmpty()) return line;
            }
            HttpResponse<String> info = c.send(HttpRequest.newBuilder(URI.create(backend + "/info")).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (info.statusCode() == 200 && !info.body().isBlank()) { String id = M.readTree(info.body()).path("model_id").asText(""); if (!id.isEmpty()) return id; }
            return "answering";
        } catch (Exception e) { return "not answering yet"; }
    }

    @Override public void close() {
        server.stop(0);
        for (Managed m : managed.values()) m.end();
        for (Process p : processes) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroy(); try { if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }

    // ---- what this machine can serve, started for the serve command ----

    /**
     * A helper's health line from what its own process says: "google/metricx-24-hybrid-large-v2p6 on cuda (card 2, NVIDIA RTX A6000)",
     * the card found by the UUID the process reports among nvidia-smi's cards (its index is what a person reads), or by its name alone
     * when it gives no UUID; "… on cpu" when it runs on the processor, also after a card that had no room. Never from the choice alone.
     */
    static String healthLine(String model, String device, String cardName, String cardUuid, List<HelperCards.Card> cards) {
        if (model.isEmpty()) return "";
        String line = model + (device.isEmpty() ? "" : " on " + device);
        if (!device.equals("cuda")) return line;
        HelperCards.Card c = cardUuid.isEmpty() ? null : HelperCards.named(cardUuid, cards);
        if (c != null) return line + " (" + c.brief() + ")";
        return cardName.isEmpty() ? line : line + " (" + cardName + ")";
    }

    /** A free port on loopback for a backend of our own. */
    static int freePort() throws IOException { try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return s.getLocalPort(); } }

    /** What a set serves: the servers it proxies by name, the helpers it starts itself, and the ones it was meant to serve that are not set up here, each with why. */
    record Backends(Map<String, String> proxied, Map<String, Managed> managed, Map<String, String> absent) {
        boolean isEmpty() { return proxied.isEmpty() && managed.isEmpty() && absent.isEmpty(); }
        List<String> names() { List<String> n = new ArrayList<>(proxied.keySet()); n.addAll(managed.keySet()); n.addAll(absent.keySet()); return n; }
    }

    /**
     * The backends this machine can serve of those asked for: MetricX-24 and the likeness helper started in their serving mode when they
     * are installed here (the wait for a model's load said), the LaBSE, embeddings and speech-to-text servers when they answer here.
     * What cannot be served is said, with why. The addresses of the ones that answer, as before 0.7.5.
     */
    static Map<String, String> startBackends(List<Helper> wanted, List<Process> started, Consumer<String> say) { return startBackends(wanted, started, say, HelperCards.Choice.AS_BEFORE); }

    static Map<String, String> startBackends(List<Helper> wanted, List<Process> started, Consumer<String> say, HelperCards.Choice card) {
        Backends b = backends(wanted, false, say, card, false);
        Map<String, String> out = new LinkedHashMap<>(b.proxied());
        for (var e : b.managed().entrySet()) { if (e.getValue().process != null) started.add(e.getValue().process); if (e.getValue().address != null) out.put(e.getKey(), e.getValue().address); }
        return out;
    }

    /**
     * What a set serves (0.7.5). MetricX-24 and the likeness helper are started here, each its first load waited for, and stay listed
     * when they do not load: the reason is their health line, and a request for one gets 503 with it and is tried again when it is due
     * ({@link Managed}). The LaBSE, embeddings and speech-to-text servers are proxied when they answer or their container exists.
     * {@code onlyGiven}: the set was installed to serve exactly {@code wanted}, so one of them that is not set up here is listed all
     * the same, as not set up; without it a helper not set up is left out, as before. {@code noFallback}: MetricX-24 never moves to the
     * processor when its card has no room ({@code --no-cpu-fallback}); a set on the processor ({@code --cpu}) is not touched by it.
     */
    static Backends backends(List<Helper> wanted, boolean onlyGiven, Consumer<String> say, HelperCards.Choice card, boolean noFallback) {
        Map<String, String> proxied = new LinkedHashMap<>(), absent = new LinkedHashMap<>();
        Map<String, Managed> managed = new LinkedHashMap<>();
        for (Helper h : wanted) {
            switch (h) {
                case METRICX -> {
                    if (!TranslationScores.metricxInstalled()) { say.accept("MetricX-24 is not installed here (researchzosho translate scorers install sets it up)"); if (onlyGiven) absent.put(h.path, NOT_SET_UP); continue; }
                    boolean refuse = noFallback && !card.cpu();
                    Managed m = new Managed(h.path, "MetricX-24", port -> refuse ? TranslationScores.pythonCommand(TranslationScores.metricxPython(), "--serve", String.valueOf(port), "--no-cpu-fallback")
                            : TranslationScores.pythonCommand(TranslationScores.metricxPython(), "--serve", String.valueOf(port)), card);
                    m.load(say);
                    managed.put(h.path, m);
                }
                case LIKENESS -> {
                    if (!LikenessHelper.installed()) { say.accept("the likeness helper is not installed here (researchzosho images likeness install sets it up)"); if (onlyGiven) absent.put(h.path, NOT_SET_UP); continue; }
                    Managed m = new Managed(h.path, "the likeness helper", port -> LikenessHelper.command(LikenessHelper.python(), "--serve", String.valueOf(port)), card);
                    m.load(say);
                    managed.put(h.path, m);
                }
                // a container of this program's that is still starting (the service and the containers start together after a reboot) is
                // served all the same: its calls fail until it answers, and the library waits for it as for any named helper
                case LABSE -> { String a = "http://127.0.0.1:" + TranslationScores.LABSE_PORT; if (Embed.answers(a) || !TranslationScores.labseState().equals("absent")) proxied.put(h.path, a); else { say.accept("LaBSE does not answer here (researchzosho translate scorers install starts it)"); if (onlyGiven) absent.put(h.path, NOT_SET_UP); } }
                case EMBED -> {
                    String set = Config.get("RESEARCHZOSHO_EMBED", "").strip(), a = set.matches("(?i)https?://(127\\.0\\.0\\.1|localhost)(:\\d+)?/?") ? set.replaceAll("/+$", "") : "http://127.0.0.1:" + Embed.DEFAULT_PORT;
                    if (Embed.answers(a) || !Embed.state().equals("absent")) proxied.put(h.path, a); else { say.accept("no embeddings server answers here (researchzosho embed start starts one)"); if (onlyGiven) absent.put(h.path, NOT_SET_UP); }
                }
                case WHISPER -> { String a = "http://127.0.0.1:" + Video.WHISPER_PORT; if (answers(a) || !Video.state(Video.CONTAINER_WHISPER).equals("absent")) proxied.put(h.path, a); else { say.accept("speech-to-text does not answer here (researchzosho video install sets it up)"); if (onlyGiven) absent.put(h.path, NOT_SET_UP); } }
            }
        }
        return new Backends(proxied, managed, absent);
    }

    /** The line of a helper the set was installed to serve that is not set up on this machine. */
    static final String NOT_SET_UP = "not set up on this machine: researchzosho helpers install sets it up";

    /** How long after a try a helper that did not load is tried again, when a request for it arrives; a test shortens it. */
    static volatile Duration RETRY = Duration.ofMinutes(3);
    /** How long one try waits for a model's first load. */
    static volatile Duration LOAD_WAIT = Duration.ofMinutes(20);
    /** The clock of the tries; a test moves it. */
    static volatile LongSupplier NOW = System::currentTimeMillis;
    /** Whether a backend answers its health; a test stands it in. */
    static volatile Predicate<String> ANSWERS = HelperServer::answers;

    /**
     * A helper this front starts itself (0.7.5): its process, whether it is loaded, and why not. Listed in the front's health whether it
     * is loaded or not, its line the reason when it is not ("not loaded: no room on card 3 (0.4 GB free)", "not loaded: its process
     * ended (exit 1)"). A request for it when it is not loaded, or when its process has died, gets 503 with that line, and starts a new
     * try when {@link #RETRY} has passed since the last one, one try at a time, in the background; once loaded it serves as usual.
     */
    static final class Managed {
        final String name, what;
        final IntFunction<List<String>> command;
        final HelperCards.Choice card;
        volatile Process process;
        volatile String address, notLoaded = "", healthLine;
        volatile boolean loading;
        volatile long lastTry;

        Managed(String name, String what, IntFunction<List<String>> command, HelperCards.Choice card) { this.name = name; this.what = what; this.command = command; this.card = card == null ? HelperCards.Choice.AS_BEFORE : card; }

        /** One try: started on a free port of loopback, its first load waited for; loaded, or not loaded and why. */
        void load(Consumer<String> say) {
            synchronized (this) { if (loading) return; loading = true; lastTry = NOW.getAsLong(); }
            try {
                int port = freePort();
                ProcessBuilder pb = new ProcessBuilder(command.apply(port)).redirectError(ProcessBuilder.Redirect.DISCARD);
                HelperCards.into(pb.environment(), card);
                say.accept("loading " + what + " (the first load of a model takes minutes)");
                Process p = START.start(pb);
                process = p;
                String[] told = {""};
                Thread reader = new Thread(() -> {   // its own lines: the one that says why it did not load, kept; the rest read so the pipe never fills
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = r.readLine()) != null; ) if (line.contains("\"not_loaded\"")) told[0] = line.strip();
                    } catch (IOException ignored) { }
                }, "helper-" + name + "-out");
                reader.setDaemon(true);
                reader.start();
                String a = "http://127.0.0.1:" + port;
                long until = System.currentTimeMillis() + LOAD_WAIT.toMillis();
                while (true) {
                    if (!p.isAlive()) {
                        try { reader.join(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        address = null; notLoaded = why(told[0], p.exitValue());
                        say.accept(what + " is not loaded: " + notLoaded);
                        return;
                    }
                    if (ANSWERS.test(a)) { address = a; notLoaded = ""; healthLine = null; return; }
                    if (System.currentTimeMillis() >= until) { p.destroyForcibly(); address = null; notLoaded = "it did not answer within " + LOAD_WAIT.toMinutes() + " minutes"; say.accept(what + " is not loaded: " + notLoaded); return; }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); address = null; notLoaded = "interrupted"; return; }
                }
            } catch (IOException e) {
                address = null; notLoaded = "it could not be started (" + e.getMessage() + ")";
                say.accept(what + " is not loaded: " + notLoaded);
            } finally { loading = false; }
        }

        /** Why it did not load, from its own line ({"not_loaded": "no room", "free_gb": 0.4}) and its exit code. */
        String why(String line, int exit) {
            try {
                var j = M.readTree(line.isEmpty() ? "{}" : line);
                String said = j.path("not_loaded").asText("");
                if (said.equals("no room")) return "no room on " + (card.card() == null ? "the card" : "card " + card.card().index()) + (j.has("free_gb") ? " (" + j.path("free_gb").asText() + " GB free)" : "");
                if (said.equals("no card")) return "torch sees no card (its processor build, or no card given), and the processor is refused (--no-cpu-fallback)";
                if (!said.isEmpty()) return said;
            } catch (IOException ignored) { }
            return "its process ended (exit " + exit + ")";
        }

        /** Whether it is loaded and its process lives. */
        boolean serving() { Process p = process; return address != null && p != null && p.isAlive(); }

        /**
         * The address for a request, or null: a process that died since it loaded is said so; one not loaded is tried again in the
         * background when {@link #RETRY} has passed since the last try and no try is under way.
         */
        String forRequest() {
            if (serving()) return address;
            if (address != null) { Process p = process; address = null; healthLine = null; notLoaded = "its process ended (exit " + (p == null ? "?" : String.valueOf(p.exitValue())) + ")"; }
            boolean due;
            synchronized (this) { due = !loading && NOW.getAsLong() - lastTry >= RETRY.toMillis(); }
            if (due) { Thread t = new Thread(() -> load(line -> { }), "helper-" + name + "-load"); t.setDaemon(true); t.start(); }
            return null;
        }

        /** Its line in the front's health: what its own process says when it is loaded; why it is not, when it is not. */
        String line() {
            if (serving()) { String h = healthLine; if (h == null) healthLine = h = healthOf(address); return h; }
            if (loading) return "not loaded yet: loading now";
            if (address != null) return "not loaded: its process ended (exit " + (process == null || process.isAlive() ? "?" : String.valueOf(process.exitValue())) + ")";
            return "not loaded: " + (notLoaded.isEmpty() ? "not started yet" : notLoaded);
        }

        void end() {
            Process p = process;
            if (p == null) return;
            try { p.descendants().forEach(ProcessHandle::destroyForcibly); } catch (UnsupportedOperationException ignored) { /* a process with no handle of its own has no descendants to end */ }
            p.destroy();
            try { if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    static boolean answers(String a) {
        try { return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(a + "/health")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
        catch (Exception e) { return false; }
    }

    /** How a helper's process is started; a test stands it in and reads the command and the environment it was given. */
    interface Starter { Process start(ProcessBuilder pb) throws IOException; }
    static volatile Starter START = ProcessBuilder::start;

    /** A secret of 24 random bytes, written as hex. */
    static String newSecret() { byte[] b = new byte[24]; new SecureRandom().nextBytes(b); return HexFormat.of().formatHex(b); }

    static List<Helper> all() { return new ArrayList<>(List.of(Helper.values())); }
}
