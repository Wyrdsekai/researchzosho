package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model servers (0.8.0 review, round C2): the chat model unloads after 10 idle minutes and the embedding models after 5, each a
 * setting; an install from before moves to that unless its person chose their own; work that runs holds its models loaded and its
 * intent warms them; LaBSE's container stops when idle; and the node's gate passes a hold's ping only for a model the library's
 * entries name, once a minute.
 */
class ModelsIdleTest {
    static final ObjectMapper M = new ObjectMapper();
    final ModelServer.Os realOs = ModelServer.os;
    final ModelServer.Runner realRunner = ModelServer.runner;
    final Predicate<String> realHealth = ModelServer.health;
    final Searx.Runner realDocker = Searx.runner;
    final long ping = ModelsAwake.PING_MS;
    final List<String> set = new ArrayList<>();
    HttpServer up; NodeGates gates; AccountStore store; String home;

    void set(String k, String v) throws Exception { Config.set(k, v); set.add(k); }

    @AfterEach void back() throws Exception {
        for (String k : set) Config.set(k, "");
        ModelServer.os = realOs; ModelServer.runner = realRunner; ModelServer.health = realHealth; Searx.runner = realDocker; ModelsAwake.PING_MS = ping;
        NodeGates.LANES = true;
        if (gates != null) gates.close();
        if (store != null) store.close();
        if (up != null) up.stop(0);
        if (home != null) { System.setProperty("user.home", home); Config.invalidate(); }
    }

    /** The ttl a model's block of config.yaml says, in seconds; -1 when none. */
    static int ttl(String yaml, String model) {
        Matcher m = Pattern.compile("(?m)^  \"" + Pattern.quote(model) + "\":\\n((?:    .*\\n)*)").matcher(yaml);
        if (!m.find()) return -1;
        Matcher t = Pattern.compile("(?m)^    ttl: (\\d+)$").matcher(m.group(1));
        return t.find() ? Integer.parseInt(t.group(1)) : -1;
    }

    // ---- idle times ----

    @Test
    void theChatModelIdlesOutAfterTenMinutesAndTheEmbeddingsModelAfterFive(@TempDir Path tmp) throws Exception {
        Path model = tmp.resolve("models/gpt-oss-20b-F16.gguf"), dir = tmp.resolve("model");
        ModelServer.Row row = ModelServer.rowFor(16, 16);
        String y = ModelServer.plan(ModelServer.Os.linux, row, model, dir, tmp.resolve("unit.service"), "all", ModelServer.DEFAULT_IDLE_MINUTES, false, "89-1.8").configYaml();
        assertEquals(600, ttl(y, row.name()), y);
        assertEquals(300, ttl(y, ModelServer.EMBED_NAME), "the embeddings model unloads too, staying in its own group: " + y);
        assertTrue(y.contains("persistent: true"), "the chat model still does not evict it");
        set(ModelsAwake.EMBED_IDLE, "0");   // a machine with memory to spare keeps it
        assertEquals(0, ttl(ModelServer.plan(ModelServer.Os.linux, row, model, dir, tmp.resolve("unit.service"), "all", 10, false, "89-1.8").configYaml(), ModelServer.EMBED_NAME));
    }

    @Test
    void anInstallFromBeforeMovesToTheNewIdleTimesUnlessItsPersonChoseTheirOwn(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString()); Config.invalidate();
        ModelServer.os = ModelServer.Os.linux;
        List<List<String>> ran = new CopyOnWriteArrayList<>();
        ModelServer.runner = cmd -> { ran.add(cmd); return new ModelServer.Result(0, ""); };
        ModelServer.health = base -> true;
        Path dir = ModelServer.dir(); Files.createDirectories(dir);
        ModelServer.Row row = ModelServer.rowFor(16, 16);
        Path model = ModelServer.modelsDir().resolve(row.file());
        for (Object[] c : new Object[][]{{20, "", 600}, {45, "", 2700}, {20, "idle_chosen=yours\n", 1200}}) {
            int idle = (int) c[0];
            // what an earlier release wrote: the chat model at its idle minutes, the embeddings model never idling out
            String old = ModelServer.plan(ModelServer.Os.linux, row, model, dir, ModelServer.unitPath(ModelServer.Os.linux), "all", idle, false, "89-1.8").configYaml()
                    .replaceAll("(?m)^(  \"embed\":\\n(?:    .*\\n)*?)    ttl: \\d+$", "$1    ttl: 0");
            Files.writeString(dir.resolve("config.yaml"), old);
            Files.writeString(dir.resolve("plan.properties"), ModelServer.planProperties(row, model, "all", idle, false) + "chosen=suggested\n" + c[1]);
            assertEquals(0, ttl(Files.readString(dir.resolve("config.yaml")), ModelServer.EMBED_NAME));
            ran.clear();
            ModelServer.upgrade(new PrintStream(new ByteArrayOutputStream(), true));
            String y = Files.readString(dir.resolve("config.yaml"));
            assertEquals((int) c[2], ttl(y, row.name()), "idle " + idle + " " + c[1] + ": " + y);
            assertEquals(300, ttl(y, ModelServer.EMBED_NAME), y);
            assertTrue(ran.contains(List.of("systemctl", "--user", "restart", ModelServer.proxyUnit())), "the proxy reads its new config: " + ran);
            ran.clear();
            assertEquals("", ModelServer.upgrade(new PrintStream(new ByteArrayOutputStream(), true)), "nothing the second time");
            assertTrue(ran.isEmpty(), "and no restart");
        }
    }

    // ---- held while work runs, warmed on intent ----

    /** A stand-in llama-swap: each GET /upstream/<model>/health counted by its model. */
    Map<String, AtomicInteger> swap() throws Exception {
        Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();
        up = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        up.createContext("/", x -> {
            String p = x.getRequestURI().getPath();
            Matcher m = Pattern.compile("/upstream/([^/]+)/health").matcher(p);
            byte[] b = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            if (m.matches()) seen.computeIfAbsent(m.group(1), k -> new AtomicInteger()).incrementAndGet();
            x.sendResponseHeaders(m.matches() || p.endsWith("/models") ? 200 : 404, b.length); x.getResponseBody().write(b); x.close();
        });
        up.start();
        return seen;
    }

    int count(Map<String, AtomicInteger> seen, String model) { AtomicInteger n = seen.get(model); return n == null ? 0 : n.get(); }

    @Test
    void workThatRunsHoldsItsModelLoadedUntilItEnds(@TempDir Path tmp) throws Exception {
        Map<String, AtomicInteger> seen = swap();
        String base = "http://127.0.0.1:" + up.getAddress().getPort();
        ModelsAwake.PING_MS = 100;
        try (ModelsAwake.Hold h = ModelsAwake.hold("research", base, "m1")) {
            Thread.sleep(550);
            assertTrue(ModelsAwake.held().contains(base + " m1"), ModelsAwake.held().toString());
        }
        Thread.sleep(150);   // a ping already on its way when the work ended
        int during = count(seen, "m1");
        assertTrue(during >= 3, "pinged while it ran: " + during);
        Thread.sleep(400);
        assertEquals(during, count(seen, "m1"), "and not after");
        assertFalse(ModelsAwake.held().contains(base + " m1"));
    }

    @Test
    void anIntentWarmsTheModelOnceAMinute(@TempDir Path tmp) throws Exception {
        Map<String, AtomicInteger> seen = swap();
        String base = "http://127.0.0.1:" + up.getAddress().getPort();
        set("RESEARCHZOSHO_DRIVE", base); set("RESEARCHZOSHO_MODEL", "m1");
        ModelsAwake.intent("research");
        ModelsAwake.intent("chat");
        long until = System.currentTimeMillis() + 3_000;
        while (count(seen, "m1") == 0 && System.currentTimeMillis() < until) Thread.sleep(20);
        Thread.sleep(300);
        assertEquals(1, count(seen, "m1"), "warmed in the background, once within the minute");
    }

    @Test
    void aTranslationHoldsTheModelAndLaBSEWhileItRuns(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        swap();
        String base = "http://127.0.0.1:" + up.getAddress().getPort();
        set("RESEARCHZOSHO_DRIVE", base); set("RESEARCHZOSHO_MODEL", "m1");
        LibraryStore lib = new LibraryStore(tmp.resolve("lib")); lib.init();
        Set<String>[] during = new Set[1];
        Jobs jobs = new Jobs(lib, j -> { during[0] = Set.copyOf(ModelsAwake.held()); return "done"; });
        jobs.start();
        try {
            String id = jobs.submit(Translation.JOB_KIND, "person", M.createObjectNode().put("path", "doc.md"));
            long until = System.currentTimeMillis() + 20_000;
            while (during[0] == null && System.currentTimeMillis() < until) Thread.sleep(50);
            assertTrue(during[0] != null && during[0].contains(base + " m1") && during[0].contains("container " + TranslationScores.LABSE_CONTAINER), "held while it ran: " + during[0] + " " + jobs.get(id));
        } finally { jobs.stop(); }
        assertFalse(ModelsAwake.held().contains(base + " m1"), "let go when it ended");
    }

    // ---- LaBSE's container ----

    @Test
    void laBSEsContainerStopsWhenIdleAndNotWhileHeldOrUsed(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        List<String> ran = new CopyOnWriteArrayList<>();
        Searx.runner = cmd -> {
            ran.add(String.join(" ", cmd));
            if (cmd.contains("inspect")) return new Searx.Result(0, cmd.get(cmd.size() - 1).equals(TranslationScores.LABSE_CONTAINER) ? "true\n" : "");
            return new Searx.Result(0, "");
        };
        String stop = "docker stop " + TranslationScores.LABSE_CONTAINER;
        Path stamp = ModelsAwake.stamp(TranslationScores.LABSE_CONTAINER);
        Files.createDirectories(stamp.getParent()); Files.writeString(stamp, "");
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(stamp, FileTime.fromMillis(now - 60_000));   // used a minute ago
        ModelsAwake.tick(now);
        assertFalse(ran.contains(stop), "used within its idle time: " + ran);
        Files.setLastModifiedTime(stamp, FileTime.fromMillis(now - 6 * 60_000));   // six minutes ago
        try (ModelsAwake.Hold h = ModelsAwake.hold(Translation.JOB_KIND, "", "")) { ModelsAwake.tick(now); }
        assertFalse(ran.contains(stop), "held by a translation that runs: " + ran);
        ModelsAwake.tick(System.currentTimeMillis() + 6 * 60_000);   // the hold's own round wrote its use; six minutes after that
        assertTrue(ran.contains(stop), "idle past five minutes and held by nothing: stopped by its name: " + ran);
        ran.clear();
        set(ModelsAwake.EMBED_IDLE, "0");
        Files.setLastModifiedTime(stamp, FileTime.fromMillis(now - 60 * 60_000));
        ModelsAwake.tick(System.currentTimeMillis() + 60 * 60_000);
        assertFalse(ran.contains(stop), "0: never");
    }

    @Test
    void aContainerThisInstallNeverStartedNorUsedIsNeverStopped(@TempDir Path tmp) throws Exception {
        // a second install on a machine whose containers another install runs: no stamp of theirs in this home (the Gemini check, 2026-11-18)
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        List<String> ran = new CopyOnWriteArrayList<>();
        Searx.runner = cmd -> {
            ran.add(String.join(" ", cmd));
            return new Searx.Result(0, cmd.contains("inspect") ? "true\n" : "");
        };
        for (String c : ModelsAwake.containers().keySet()) assertFalse(Files.exists(ModelsAwake.stamp(c)), c);
        long now = System.currentTimeMillis();
        ModelsAwake.tick(now);
        ModelsAwake.tick(now + 60 * 60_000);   // an hour on, both running all the while
        assertTrue(ran.stream().noneMatch(c -> c.startsWith("docker stop")), "what this install did not start or use is not its to stop: " + ran);
        // once this install uses one, the idle rule is its own for that one
        ModelsAwake.used(ModelsAwake.containers().get(TranslationScores.LABSE_CONTAINER));
        ModelsAwake.tick(System.currentTimeMillis() + 6 * 60_000);
        assertTrue(ran.contains("docker stop " + TranslationScores.LABSE_CONTAINER), ran.toString());
        assertTrue(ran.stream().filter(c -> c.startsWith("docker stop")).count() == 1, "the other, never used here, is left alone: " + ran);
    }

    // ---- the node's gate ----

    @Test
    void theGatePassesAHoldsPingOnlyForTheLibrarysModelsAndOnceAMinute(@TempDir Path tmp) throws Exception {
        Map<String, AtomicInteger> seen = swap();
        String upstream = "http://127.0.0.1:" + up.getAddress().getPort();
        set("RESEARCHZOSHO_DRIVE", upstream); set("RESEARCHZOSHO_MODEL", "m1");
        NodeGates.LANES = false;
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        gates = NodeGates.of(store);
        ModelsAwake.PING_MS = 400;
        Map<String, String> env = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", upstream));
        gates.rewrite(env, "lib_a");
        String gate = env.get("RESEARCHZOSHO_DRIVE");
        HttpClient c = HttpClient.newHttpClient();
        HttpResponse<String> first = c.send(HttpRequest.newBuilder(URI.create(gate + "/upstream/m1/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> again = c.send(HttpRequest.newBuilder(URI.create(gate + "/upstream/m1/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode()); assertEquals(200, again.statusCode());
        assertEquals(1, count(seen, "m1"), "the second within the minute is answered by the gate, not passed on");
        assertTrue(again.body().contains("\"held\""), again.body());
        HttpResponse<String> other = c.send(HttpRequest.newBuilder(URI.create(gate + "/upstream/someone-elses-model/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, other.statusCode(), "a model the library's entries do not name is not loaded through it");
        assertEquals(0, count(seen, "someone-elses-model"));
        Thread.sleep(500);
        c.send(HttpRequest.newBuilder(URI.create(gate + "/upstream/m1/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(2, count(seen, "m1"), "a minute later it passes again");
    }
}
