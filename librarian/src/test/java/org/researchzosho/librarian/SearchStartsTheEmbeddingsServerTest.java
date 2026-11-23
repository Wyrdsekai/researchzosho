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
import org.researchzosho.LookupsSeen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library's own search that finds its embeddings server stopped by the idle rule (round C2): the search goes by words that once and
 * says so, the server's container is started in the background with no wait in the search, the searches that come while it starts
 * start nothing more, and the search after it answers is by meaning again. A search's question counts as a use, so a server somebody
 * keeps searching with is not stopped. A server that is no container of this machine is never asked of Docker. Docker is the suite's
 * stand-in ({@link Searx#runner}), the container a stand-in embeddings server that answers only while its container runs and its load
 * is let through, and the searches go through the protocol, as the MCP tools, the command line, the Search page and the chat ask.
 */
class SearchStartsTheEmbeddingsServerTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String QUESTION = "plucked keyboard instrument", BY_WORDS = "Searched by words this time: search by meaning is starting.";
    final Searx.Runner realDocker = Searx.runner;
    final Map<String, String> realContainers = ModelsAwake.CONTAINERS;
    final List<HttpServer> servers = new ArrayList<>();
    final List<Container> made = new ArrayList<>();
    final List<String> ran = new CopyOnWriteArrayList<>();
    Container docker;   // the one container the stand-in Docker knows
    String home;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        Helpers.forget();
        Searx.runner = cmd -> {
            ran.add(String.join(" ", cmd));
            Container c = docker;
            if (cmd.size() > 1 && cmd.get(1).equals("version")) return new Searx.Result(0, "27.0\n");
            if (c == null || !cmd.get(cmd.size() - 1).equals(Embed.CONTAINER)) return new Searx.Result(1, "Error: No such object: " + cmd.get(cmd.size() - 1));
            return switch (cmd.get(1)) {
                case "inspect" -> new Searx.Result(0, c.running ? "true\n" : "false\n");
                case "start" -> { c.starts.incrementAndGet(); c.running = true; yield new Searx.Result(0, Embed.CONTAINER + "\n"); }
                case "stop" -> { c.running = false; yield new Searx.Result(0, Embed.CONTAINER + "\n"); }
                default -> new Searx.Result(1, "not stood in: " + cmd);
            };
        };
    }

    @AfterEach void restore() throws Exception {
        // a start under way is let through and waited for, so that nothing of this test is still at work after it
        for (Container c : made) { c.held = false; until(() -> !ModelsAwake.starting(c.at()), "the start under way ends"); }
        for (HttpServer s : servers) s.stop(0);
        Searx.runner = realDocker; ModelsAwake.CONTAINERS = realContainers; Config.provide(Map.of());
        System.setProperty("user.home", home); Config.invalidate(); Helpers.forget();
    }

    /** The embeddings server's container, running and answering; once stopped and started again, it answers only when {@link #held} is let go. */
    final class Container {
        final HttpServer server;
        final AtomicInteger starts = new AtomicInteger();
        volatile boolean running = true, held;

        Container() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", x -> {
                try (x) {
                    String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (!running || held || !x.getRequestURI().getPath().equals("/v1/embeddings")) return;   // closed with no reply: nothing answers here
                    JsonNode in = M.readTree(body).path("input");
                    ObjectNode o = M.createObjectNode(); ArrayNode data = o.putArray("data");
                    List<String> texts = new ArrayList<>();
                    if (in.isArray()) for (JsonNode t : in) texts.add(t.asText()); else texts.add(in.asText());
                    for (int i = 0; i < texts.size(); i++) { ObjectNode d = data.addObject(); d.put("index", i); ArrayNode v = d.putArray("embedding"); for (float f : vector(texts.get(i))) v.add(f); }
                    reply(x, o.toString());
                }
            });
            server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
            server.start();
            servers.add(server);
            made.add(this);
        }

        String at() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    }

    /** A stand-in model: what is about the harpsichord, or a plucked keyboard, points one way; everything else another. */
    static float[] vector(String text) {
        String t = text.toLowerCase();
        return t.contains("harpsichord") || t.contains("plucked") ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
    }

    static void reply(HttpExchange x, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(200, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    static void until(BooleanSupplier done, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 30_000;
        while (!done.getAsBoolean()) { if (System.currentTimeMillis() > end) throw new AssertionError("not within 30 seconds: " + what); Thread.sleep(50); }
    }

    private static Finding f(String id, String title, String body) {
        return new Finding(id, title, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/" + id, "n/a", "s")), List.of(), null, body);
    }

    /**
     * Two claims on the shelves, indexed with vectors while the server answers; searches then go to {@code embed}, and the stand-in Docker
     * knows the container {@code c}, when given, as the embeddings server's.
     */
    LibraryStore library(Path tmp, Container c, String embed) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(f("F-0001-a", "Harpsichords of Antwerp", "The Ruckers family built harpsichords in Antwerp for two centuries.\n"));
        store.write(f("F-0002-b", "Tea in Kyoto", "Matcha is whisked with hot water, never steeped.\n"));
        assertEquals(2, new LibrarianIndex(store, Embeddings.http(c.at(), "embed")).rebuild());
        docker = c;
        ModelsAwake.CONTAINERS = Map.of(Embed.CONTAINER, c.at());   // this machine's embeddings container answers at the stand-in
        Config.provide(Map.of("RESEARCHZOSHO_EMBED", embed));
        return store;
    }

    static ObjectNode search(LibraryProtocol p) throws IOException { return p.search(M.createObjectNode().put("query", QUESTION).put("k", 5)); }

    static List<String> ids(JsonNode hits) { List<String> out = new ArrayList<>(); for (JsonNode h : hits) out.add(h.path("id").asText()); return out; }

    long count(String command) { return ran.stream().filter(c -> c.startsWith(command)).count(); }

    @Test void aSearchThatFindsTheServerStoppedGoesByWordsStartsItWithNoWaitAndTheSearchAfterItAnswersIsByMeaning(@TempDir Path tmp) throws Exception {
        Container c = new Container();
        LibraryStore store = library(tmp, c, c.at());
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode up = search(p);
        assertEquals(List.of("F-0001-a"), ids(up.path("hits")), "the instrument works: the question shares no word with the claim, and is found by meaning");
        assertFalse(up.has("note"), up.toString());
        // the idle rule stopped it; its next start loads until the test lets it through
        c.running = false; c.held = true;
        List<CompletableFuture<ObjectNode>> asked = new ArrayList<>();
        for (int i = 0; i < 3; i++) asked.add(CompletableFuture.supplyAsync(() -> { try { return search(p); } catch (IOException e) { throw new RuntimeException(e); } }));
        for (CompletableFuture<ObjectNode> f : asked) {
            ObjectNode r = f.get(60, TimeUnit.SECONDS);   // answered while the server still loads: no search waited for it
            assertTrue(r.path("hits").isEmpty(), "by words: the question's words are on no shelf: " + r);
            assertEquals(BY_WORDS, r.path("note").asText(), r.toString());
        }
        until(() -> c.starts.get() >= 1, "the start, filed in the background");
        assertEquals(1, c.starts.get(), "one start for the three searches: " + ran);
        Path stamp = ModelsAwake.stamp(Embed.CONTAINER);
        assertTrue(Files.exists(stamp) && System.currentTimeMillis() - Files.getLastModifiedTime(stamp).toMillis() < 60_000, "the searches' use is written down");
        // while it starts: by words, and not even a look at Docker
        long inspects = count("docker inspect");
        ObjectNode during = search(p);
        assertEquals(BY_WORDS, during.path("note").asText(), during.toString());
        ObjectNode asking = p.ask(M.createObjectNode().put("question", QUESTION).put("k", 5).put("peers", "none"));
        assertEquals(BY_WORDS, asking.path("note").asText(), "an ask says it too: " + asking);
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        assertEquals(0, Remote.run(new String[]{"researchzosho", "search", QUESTION}, new PrintStream(printed, true, StandardCharsets.UTF_8), new PrintStream(OutputStream.nullOutputStream()), Remote.local(store)));
        assertTrue(printed.toString(StandardCharsets.UTF_8).startsWith(BY_WORDS + System.lineSeparator()), "the command line says it first: " + printed.toString(StandardCharsets.UTF_8));
        assertEquals(inspects, count("docker inspect"), "a start under way: no docker call from a search: " + ran);
        assertEquals(1, c.starts.get());
        // it answers: the next search is by meaning again, and says nothing of it
        c.held = false;
        until(() -> !ModelsAwake.starting(c.at()), "the start ends once the server answers");
        ObjectNode after = search(p);
        assertEquals(List.of("F-0001-a"), ids(after.path("hits")), after.toString());
        assertFalse(after.has("note"), after.toString());
        ObjectNode askedAfter = p.ask(M.createObjectNode().put("question", QUESTION).put("k", 5).put("peers", "none"));
        assertEquals(List.of("F-0001-a"), ids(askedAfter.path("entries")), askedAfter.toString());
        assertEquals(1, c.starts.get(), ran.toString());
    }

    @Test void theIdleRuleDoesNotStopAServerThatSearchesKeepUsing(@TempDir Path tmp) throws Exception {
        Container c = new Container();
        LibraryProtocol p = new LibraryProtocol(library(tmp, c, c.at()));
        Path stamp = ModelsAwake.stamp(Embed.CONTAINER);
        Files.createDirectories(stamp.getParent()); Files.writeString(stamp, "");
        Files.setLastModifiedTime(stamp, FileTime.fromMillis(System.currentTimeMillis() - 6 * 60_000));   // its last use six minutes ago
        assertEquals(List.of("F-0001-a"), ids(search(p).path("hits")), "somebody searches now");
        ModelsAwake.tick(System.currentTimeMillis());
        assertTrue(c.running, "a search is a use: the idle rule leaves it running: " + ran);
        assertEquals(0, count("docker stop"), ran.toString());
    }

    @Test void aServerThatIsNoContainerOfThisMachineIsNeverAskedOfDocker(@TempDir Path tmp) throws Exception {
        Container c = new Container();
        LibraryProtocol p = new LibraryProtocol(library(tmp, c, c.at()));
        c.running = false; c.held = true;
        search(p);
        assertTrue(count("docker inspect -f {{.State.Running}} " + Embed.CONTAINER) >= 1, "the container of this machine is looked at, the positive control: " + ran);
        until(() -> c.starts.get() == 1, "its start");
        c.held = false;
        until(() -> !ModelsAwake.starting(c.at()), "its start ends");
        ran.clear();
        // one under llama-swap, at another port of this machine, loads by the request itself; it is not answering here
        int closed; try (ServerSocket s = new ServerSocket(0)) { closed = s.getLocalPort(); }
        Config.provide(Map.of("RESEARCHZOSHO_EMBED", "http://127.0.0.1:" + closed));
        ObjectNode swap = search(p);
        assertTrue(swap.path("hits").isEmpty() && !swap.has("note"), swap.toString());
        // one on another machine: its helpers front wakes its own
        LookupsSeen.watch(h -> h.equals("homebox"));
        try {
            Config.provide(Map.of("RESEARCHZOSHO_EMBED", "http://homebox:8217/embed"));
            ObjectNode remote = search(p);
            assertTrue(remote.path("hits").isEmpty() && !remote.has("note"), remote.toString());
        } finally { LookupsSeen.seen(); }
        assertTrue(ran.isEmpty(), "no docker call for either: " + ran);
    }
}
