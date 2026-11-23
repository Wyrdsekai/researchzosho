package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.Helpers.Helper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A helper's container on the machine that serves the helpers, stopped by the idle rule (round C2), is started again by the request
 * that asks for it. Seen on a helpers machine: LaBSE's container stopped six minutes after its last request and stayed stopped for
 * three days, every request through the front answered 502, and a library elsewhere had no LaBSE. Docker is the suite's stand-in
 * ({@link Searx#runner}), and each container a stand-in embeddings server that answers only while its container runs and its load is
 * over (before that the connection closes with no reply, as nothing answers at a stopped container's port); nothing of the machine the
 * suite runs on is asked, started or stopped.
 */
class HelpersWakeTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String SECRET = "words only we know";
    final Searx.Runner realDocker = Searx.runner;
    final Duration wakeWait = ModelsAwake.WAKE_WAIT;
    final List<HttpServer> servers = new ArrayList<>();
    final List<AutoCloseable> closing = new ArrayList<>();
    final HttpClient client = HttpClient.newHttpClient();
    /** The stand-in Docker's containers by name, and every command it was given. */
    final Map<String, Container> docker = new ConcurrentHashMap<>();
    final List<String> ran = new CopyOnWriteArrayList<>();
    String home;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        Helpers.forget();
        Searx.runner = cmd -> {
            ran.add(String.join(" ", cmd));
            if (cmd.size() > 1 && cmd.get(1).equals("version")) return new Searx.Result(0, "27.0\n");
            Container c = docker.get(cmd.get(cmd.size() - 1));
            if (c == null) return new Searx.Result(1, "Error: No such object: " + cmd.get(cmd.size() - 1));
            return switch (cmd.get(1)) {
                case "inspect" -> new Searx.Result(0, c.running ? "true\n" : "false\n");
                case "start" -> c.start();
                case "stop" -> { c.running = false; yield new Searx.Result(0, c.name + "\n"); }
                default -> new Searx.Result(1, "not stood in: " + cmd);
            };
        };
    }

    @AfterEach void restore() throws Exception {
        for (AutoCloseable c : closing) c.close();
        for (HttpServer s : servers) s.stop(0);
        Searx.runner = realDocker; ModelsAwake.WAKE_WAIT = wakeWait;
        System.setProperty("user.home", home); Config.invalidate(); Helpers.forget();
    }

    /**
     * A stand-in container, stopped: once started it answers after {@code loadMs} (never, when negative), or Docker refuses to start it
     * with {@code refused} when that is set. Its starts are counted.
     */
    final class Container {
        final String name;
        final long loadMs;
        final HttpServer server;
        final AtomicInteger starts = new AtomicInteger(), silentToACall = new AtomicInteger();
        volatile boolean running;
        volatile long answersFrom = Long.MAX_VALUE;
        volatile String refused;

        Container(String name, long loadMs) throws IOException {
            this.name = name; this.loadMs = loadMs;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", x -> {
                try (x) {
                    String path = x.getRequestURI().getPath(), body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (!running || System.currentTimeMillis() < answersFrom) {   // closed with no reply: nothing answers here
                        if (body.contains("a sentence")) silentToACall.incrementAndGet();   // a test's own call, not the front's look whether it answers
                        return;
                    }
                    if (path.equals("/health")) { x.sendResponseHeaders(200, -1); return; }   // Text Embeddings Inference's health says nothing
                    if (path.equals("/info")) { reply(x, 200, "{\"model_id\":\"stand-in/" + name + "\"}"); return; }
                    if (path.equals("/v1/embeddings")) {
                        JsonNode in = M.readTree(body).path("input");
                        ObjectNode o = M.createObjectNode(); ArrayNode data = o.putArray("data");
                        for (int i = 0; i < (in.isArray() ? in.size() : 1); i++) { ObjectNode d = data.addObject(); d.put("index", i); d.putArray("embedding").add(0.6).add(0.8); }
                        reply(x, 200, o.toString()); return;
                    }
                    reply(x, 404, "{}");
                }
            });
            server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
            server.start();
            servers.add(server);
            docker.put(name, this);
        }

        Searx.Result start() {
            starts.incrementAndGet();
            if (refused != null) return new Searx.Result(1, refused + "\n");
            running = true;
            answersFrom = loadMs < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + loadMs;
            return new Searx.Result(0, name + "\n");
        }

        String at() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    }

    static void reply(HttpExchange x, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    /** The helpers' front on loopback, serving {@code helper} from the stand-in container {@code c}, as it serves LaBSE's or the embeddings server's. */
    String front(String helper, Container c) throws IOException {
        HelperServer h = HelperServer.serve("127.0.0.1", 0, SECRET, new HelperServer.Backends(Map.of(helper, c.at()), Map.of(), Map.of(), null, Map.of(helper, c.name)), List.of());
        closing.add(h);
        return "http://127.0.0.1:" + h.port();
    }

    HttpRequest embeddingsCall(String address) {
        return HttpRequest.newBuilder(URI.create(address + "/v1/embeddings")).timeout(Duration.ofMinutes(3)).header("Authorization", "Bearer " + SECRET)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"embed\",\"input\":[\"a sentence\",\"its translation\"]}")).build();
    }

    HttpResponse<String> embeddings(String address) throws Exception { return client.send(embeddingsCall(address), HttpResponse.BodyHandlers.ofString()); }

    /** What the front's health lists, by helper. */
    JsonNode helpersOf(String base) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(base + "/health")).header("Authorization", "Bearer " + SECRET).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return M.readTree(r.body()).path("helpers");
    }

    static String error(HttpResponse<String> r) throws IOException { return M.readTree(r.body()).path("error").asText(); }

    long starts() { return ran.stream().filter(c -> c.startsWith("docker start ")).count(); }

    @Test void theFrontKnowsLaBSEAndTheEmbeddingsServerByTheirAddressesAsTheContainersTheIdleRuleStops() {
        // the containers where a machine has them (the suite points them at ports where nothing listens, NoMachineTools): LaBSE at its port
        String labse = TranslationScores.LOCAL_LABSE; Map<String, String> containers = ModelsAwake.CONTAINERS;
        TranslationScores.LOCAL_LABSE = "http://127.0.0.1:" + TranslationScores.LABSE_PORT; ModelsAwake.CONTAINERS = ModelsAwake.machine();
        try {
            HelperServer.Backends b = new HelperServer.Backends(Map.of("labse", "http://127.0.0.1:" + TranslationScores.LABSE_PORT, "embed", "http://localhost:" + Embed.DEFAULT_PORT + "/",
                    "whisper", "http://127.0.0.1:" + Video.WHISPER_PORT), Map.of(), Map.of());
            assertEquals(Map.of("labse", TranslationScores.LABSE_CONTAINER, "embed", Embed.CONTAINER), b.containers(), "speech-to-text is not under the idle rule");
            assertTrue(new HelperServer.Backends(Map.of("embed", "http://127.0.0.1:8200"), Map.of(), Map.of()).containers().isEmpty(), "an embeddings server of another program is no container of ours");
        } finally { TranslationScores.LOCAL_LABSE = labse; ModelsAwake.CONTAINERS = containers; }
    }

    @Test void aContainerTheIdleRuleStoppedIsStartedByTheRequestForItAndTheIdleRuleGoesOnWorking() throws Exception {
        for (String[] h : new String[][]{{"labse", TranslationScores.LABSE_CONTAINER}, {"embed", Embed.CONTAINER}}) {
            Container c = new Container(h[1], 1_000);   // stopped; its load takes a second after a start
            String base = front(h[0], c);
            assertEquals(0, c.starts.get(), h[0] + ": the front's own start starts nothing; the first request does");
            HttpResponse<String> first = embeddings(base + "/" + h[0]);
            assertEquals(200, first.statusCode(), h[0] + ": the first request after the stop waits for the start and is answered: " + first.body());
            assertTrue(first.body().contains("\"embedding\""), first.body());
            assertEquals(1, c.starts.get(), h[0] + ": " + ran);
            HttpResponse<String> next = embeddings(base + "/" + h[0]);
            assertEquals(200, next.statusCode(), next.body());
            assertEquals(1, c.starts.get(), h[0] + ": the requests after it go straight through");
            // used: it stays; idle: stopped; asked: started
            long now = System.currentTimeMillis();
            ModelsAwake.tick(now + 60_000);
            assertTrue(c.running, h[0] + ": used a minute ago, it stays");
            ModelsAwake.tick(now + 6 * 60_000);
            assertFalse(c.running, h[0] + ": six minutes without use, the idle rule stops it");
            assertTrue(ran.contains("docker stop " + h[1]), ran.toString());
            assertEquals(200, embeddings(base + "/" + h[0]).statusCode(), h[0] + ": asked again, started again");
            assertEquals(2, c.starts.get(), h[0] + ": " + ran);
        }
    }

    @Test void requestsThatComeWhileItStartsWaitForTheSameStart() throws Exception {
        Container c = new Container(TranslationScores.LABSE_CONTAINER, 2_000);
        String base = front("labse", c);
        List<CompletableFuture<HttpResponse<String>>> asked = new ArrayList<>();
        for (int i = 0; i < 3; i++) asked.add(client.sendAsync(embeddingsCall(base + "/labse"), HttpResponse.BodyHandlers.ofString()));
        for (CompletableFuture<HttpResponse<String>> f : asked) { HttpResponse<String> r = f.get(3, TimeUnit.MINUTES); assertEquals(200, r.statusCode(), r.body()); }
        assertEquals(3, c.silentToACall.get(), "each of the three found it silent, so each waited for the start (not one after another, each going straight through)");
        assertEquals(1, c.starts.get(), "one docker start for every request that came meanwhile: " + ran);
        assertEquals(1, starts(), ran.toString());
    }

    @Test void aContainerThatDoesNotComeUpIsSaidInA503WithItsSentenceAndIsNotStartedAgainAndAgain() throws Exception {
        ModelsAwake.WAKE_WAIT = Duration.ofSeconds(2);
        Container silent = new Container(TranslationScores.LABSE_CONTAINER, -1);   // starts, and never answers
        String base = front("labse", silent);
        HttpResponse<String> r = embeddings(base + "/labse");
        assertEquals(503, r.statusCode(), r.body());
        assertEquals("not loaded: its container was stopped, this request started it, and it did not answer within 2 seconds (docker logs " + TranslationScores.LABSE_CONTAINER
                + " on the helpers machine says why)", error(r));
        assertEquals(1, silent.starts.get());
        // it runs now and still does not answer: said as before, and not started again
        HttpResponse<String> again = embeddings(base + "/labse");
        assertEquals(502, again.statusCode(), again.body());
        assertEquals("labse does not answer on this machine", error(again));
        assertEquals(1, silent.starts.get(), ran.toString());
        // a container Docker does not start: its words, in a line of the same kind
        Container refused = new Container(Embed.CONTAINER, 0);
        refused.refused = "Error response from daemon: Bind for 127.0.0.1:" + Embed.DEFAULT_PORT + " failed: port is already allocated";
        HttpResponse<String> no = embeddings(front("embed", refused) + "/embed");
        assertEquals(503, no.statusCode(), no.body());
        assertEquals("not loaded: its container is stopped and could not be started (" + refused.refused + ")", error(no));
    }

    @Test void theFrontsHealthAndTheLibrarysStatusSayAContainerRestsAndStartNothing() throws Exception {
        Container c = new Container(TranslationScores.LABSE_CONTAINER, 0);
        Path stamp = ModelsAwake.stamp(c.name);
        Files.createDirectories(stamp.getParent()); Files.writeString(stamp, "");
        Files.setLastModifiedTime(stamp, FileTime.fromMillis(System.currentTimeMillis() - 6 * 60_000));   // its last use six minutes ago; the idle rule stopped it
        String base = front("labse", c);
        String resting = "not loaded now: stopped after 5 minutes without use; the next request starts it again";
        assertEquals(resting, helpersOf(base).path("labse").asText(), "the front's health, from its start");
        // the library that uses this machine's helpers: a line of the kind a helper not loaded there gets, and nothing asked of the helper
        Config.set(Helpers.HELPERS, base); Config.set(Helpers.SECRET_NAME, SECRET); Config.set(Helper.LABSE.setting, "");
        String status = HelpersCli.statusSaid();
        assertTrue(status.contains("\n  LaBSE: at " + base + "/labse, which has it " + resting), status);
        assertFalse(status.contains("does not answer now"), status);
        assertEquals("scores at " + base + "/labse, which has it " + resting, TranslationScores.whereSaid(Helper.LABSE), "translate scorers status says the same");
        assertEquals(0, starts(), "a look at the status starts nothing: " + ran);
        // a request starts it, and then the front and the status say what it serves
        assertEquals(200, embeddings(base + "/labse").statusCode());
        Helpers.forget();
        assertEquals("stand-in/" + c.name, helpersOf(base).path("labse").asText());
        status = HelpersCli.statusSaid();
        assertTrue(status.contains("\n  LaBSE: at " + base + "/labse (stand-in/" + c.name + ")"), status);
        // stopped by something else than the idle rule (its last use is recent): said without the idle minutes
        c.running = false;
        Files.setLastModifiedTime(stamp, FileTime.fromMillis(System.currentTimeMillis()));
        Helpers.forget();
        assertEquals("not loaded now: its container is stopped; the next request starts it again", ModelsAwake.restingLine(c.name));
    }
}
