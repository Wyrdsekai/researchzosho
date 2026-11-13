package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.researchzosho.Config;
import org.researchzosho.librarian.Helpers.Helper;

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
    private final Map<String, String> health;
    private volatile long healthAt = System.currentTimeMillis();
    private final List<Process> processes;
    private final byte[] secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HelperServer(HttpServer server, Map<String, String> backends, Map<String, String> health, List<Process> processes, String secret) {
        this.server = server; this.backends = backends; this.health = health; this.processes = processes;
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
        if ((secret == null || secret.isBlank()) && !loopback(bind)) throw new IOException("A server listening beyond this machine needs a secret: --secret <words> or --secret-file <file>.");
        Map<String, String> health = new LinkedHashMap<>();
        for (var e : backends.entrySet()) health.put(e.getKey(), healthOf(e.getValue()));
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind), port), 32);
        HelperServer h = new HelperServer(s, new LinkedHashMap<>(backends), health, processes, secret);
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
                if (System.currentTimeMillis() - healthAt > 30_000) { synchronized (health) { for (var e : backends.entrySet()) health.put(e.getKey(), healthOf(e.getValue())); healthAt = System.currentTimeMillis(); } }
                for (var e : health.entrySet()) hs.put(e.getKey(), e.getValue());
                reply(x, 200, o.toString());
                return;
            }
            String name = path.replaceFirst("^/", "").split("/", 2)[0], rest = path.substring(name.length() + 1);
            String to = backends.get(name);
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

    /**
     * The backends this machine can serve of those asked for: MetricX-24 and the likeness helper started in their serving mode when they
     * are installed here (the wait for a model's load said), the LaBSE, embeddings and speech-to-text servers when they answer here.
     * What cannot be served is said, with why.
     */
    static Map<String, String> startBackends(List<Helper> wanted, List<Process> started, Consumer<String> say) { return startBackends(wanted, started, say, HelperCards.Choice.AS_BEFORE); }

    /** The same, the Python helpers started on {@code card}'s choice (CUDA_VISIBLE_DEVICES set to the card, or to none for the processor). */
    static Map<String, String> startBackends(List<Helper> wanted, List<Process> started, Consumer<String> say, HelperCards.Choice card) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Helper h : wanted) {
            try {
                switch (h) {
                    case METRICX -> {
                        if (!TranslationScores.metricxInstalled()) { say.accept("MetricX-24 is not installed here (researchzosho translate scorers install sets it up)"); continue; }
                        int p = freePort();
                        out.put(h.path, startPython(TranslationScores.pythonCommand(TranslationScores.metricxPython(), "--serve", String.valueOf(p)), card, p, started, say, "MetricX-24"));
                    }
                    case LIKENESS -> {
                        if (!LikenessHelper.installed()) { say.accept("the likeness helper is not installed here (researchzosho images likeness install sets it up)"); continue; }
                        int p = freePort();
                        out.put(h.path, startPython(LikenessHelper.command(LikenessHelper.python(), "--serve", String.valueOf(p)), card, p, started, say, "the likeness helper"));
                    }
                    // a container of this program's that is still starting (the service and the containers start together after a reboot) is
                    // served all the same: its calls fail until it answers, and the library waits for it as for any named helper
                    case LABSE -> { String a = "http://127.0.0.1:" + TranslationScores.LABSE_PORT; if (Embed.answers(a) || !TranslationScores.labseState().equals("absent")) out.put(h.path, a); else say.accept("LaBSE does not answer here (researchzosho translate scorers install starts it)"); }
                    case EMBED -> {
                        String set = Config.get("RESEARCHZOSHO_EMBED", "").strip(), a = set.matches("(?i)https?://(127\\.0\\.0\\.1|localhost)(:\\d+)?/?") ? set.replaceAll("/+$", "") : "http://127.0.0.1:" + Embed.DEFAULT_PORT;
                        if (Embed.answers(a) || !Embed.state().equals("absent")) out.put(h.path, a); else say.accept("no embeddings server answers here (researchzosho embed start starts one)");
                    }
                    case WHISPER -> { String a = "http://127.0.0.1:" + Video.WHISPER_PORT; if (answers(a) || !Video.state(Video.CONTAINER_WHISPER).equals("absent")) out.put(h.path, a); else say.accept("speech-to-text does not answer here (researchzosho video install sets it up)"); }
                }
            } catch (IOException e) { say.accept(h.said + " could not be started: " + e.getMessage()); }
        }
        return out;
    }

    private static boolean answers(String a) {
        try { return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(a + "/health")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
        catch (Exception e) { return false; }
    }

    /** How a helper's process is started; a test stands it in and reads the command and the environment it was given. */
    interface Starter { Process start(ProcessBuilder pb) throws IOException; }
    static volatile Starter START = ProcessBuilder::start;

    /**
     * Starts a helper's Python in its serving mode on {@code port}, on the card {@code card} chose (CUDA_VISIBLE_DEVICES; its own
     * {@code cuda} is that card then, and MetricX-24 still falls back to the processor when the card has no room), and waits for its
     * health, up to twenty minutes for a model's first load.
     */
    static String startPython(List<String> cmd, HelperCards.Choice card, int port, List<Process> started, Consumer<String> say, String what) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        HelperCards.into(pb.environment(), card);
        Process p = START.start(pb);
        started.add(p);
        String a = "http://127.0.0.1:" + port;
        say.accept("loading " + what + " (the first load of a model takes minutes)");
        long until = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20);
        while (System.currentTimeMillis() < until) {
            if (!p.isAlive()) throw new IOException("its process ended with exit code " + p.exitValue());
            if (answers(a)) return a;
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        }
        p.destroyForcibly();
        throw new IOException("it did not answer within twenty minutes");
    }

    /** A secret of 24 random bytes, written as hex. */
    static String newSecret() { byte[] b = new byte[24]; new SecureRandom().nextBytes(b); return HexFormat.of().formatHex(b); }

    static List<Helper> all() { return new ArrayList<>(List.of(Helper.values())); }
}
