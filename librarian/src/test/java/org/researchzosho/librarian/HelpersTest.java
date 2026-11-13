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
import org.researchzosho.librarian.TranslationScores.Scorer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The heavy helpers served by another machine (0.7.4), with stand-in servers for each path: MetricX-24 served through {@code helpers serve}
 * and scored from a library that names it, the secret refused, a named helper that does not answer at the plan and one that stops
 * answering in flight, the setting that runs none of them here, and the helpers address the others default from.
 */
class HelpersTest {
    static final ObjectMapper M = new ObjectMapper();
    String home;
    Duration retry;
    final List<HttpServer> servers = new ArrayList<>();
    final List<AutoCloseable> closing = new ArrayList<>();

    @BeforeEach void isolate(@TempDir Path tmp) throws IOException {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        Helpers.forget(); retry = TranslationScores.SERVED_RETRY;
        Config.set("RESEARCHZOSHO_LABSE", "off");   // no test here may start a LaBSE container on the machine it runs on
    }
    @AfterEach void restore() throws Exception {
        for (AutoCloseable c : closing) c.close();
        for (HttpServer s : servers) s.stop(0);
        System.setProperty("user.home", home); Config.invalidate(); Helpers.forget(); Helpers.SERVES = Helpers::servesNow;
        TranslationScores.SERVED_RETRY = retry;
    }

    /** The calls a stand-in backend saw: the path, the Authorization header, the body. */
    record Seen(String path, String auth, String body) { }

    /** A stand-in MetricX-24 in its serving mode: its health, and a score for each pair (the hypothesis's length, a tenth). */
    HttpServer metricxStandIn(List<Seen> seen) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(x.getRequestURI().getPath(), x.getRequestHeaders().getFirst("Authorization"), body));
            if (x.getRequestURI().getPath().equals("/health")) { reply(x, 200, "{\"ready\":true,\"model\":\"google/metricx-24-hybrid-large-v2p6\",\"device\":\"cpu\"}"); return; }
            if (x.getRequestURI().getPath().equals("/score")) {
                ArrayNode scores = M.createArrayNode();
                for (JsonNode p : M.readTree(body).path("pairs")) scores.add(p.path("hypothesis").asText().length() / 10.0);
                ObjectNode o = M.createObjectNode(); o.set("scores", scores); reply(x, 200, o.toString()); return;
            }
            reply(x, 404, "{}");
        });
        s.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        s.start();
        servers.add(s);
        return s;
    }

    static void reply(HttpExchange x, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    static String at(HttpServer s) { return "http://127.0.0.1:" + s.getAddress().getPort(); }

    /** The helpers' front on this machine's loopback, serving {@code backends} with {@code secret}. */
    String front(Map<String, String> backends, String secret) throws IOException {
        HelperServer h = HelperServer.serve("127.0.0.1", 0, secret, backends, List.of());
        closing.add(h);
        return "http://127.0.0.1:" + h.port();
    }

    static int closedPort() throws IOException { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }

    static List<String[]> pairs(int n) { List<String[]> out = new ArrayList<>(); for (int i = 0; i < n; i++) out.add(new String[]{"源 " + i, "translation number " + i}); return out; }

    @Test void metricxServedByAnotherMachineScoresTheRunAndTheNoteNamesWhereItRan() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer backend = metricxStandIn(seen);
        String base = front(Map.of("metricx", at(backend)), "words only we know");
        Config.set(Helpers.HELPERS, base); Config.set(Helpers.SECRET_NAME, "words only we know");
        assertEquals(base + "/metricx", Helpers.address(Helper.METRICX), "the helpers machine says it serves MetricX-24: it is used from there");
        assertNull(Helpers.address(Helper.WHISPER), "a helper it does not serve is not named by it");
        Scorer s = TranslationScores.metricxScorer();
        assertInstanceOf(TranslationScores.Served.class, s);
        List<Double> got = s.score(pairs(11));
        assertEquals(11, got.size());
        assertEquals("translation number 10".length() / 10.0, got.get(10), 1e-9);
        long calls = seen.stream().filter(x -> x.path().equals("/score")).count();
        assertEquals(2, calls, "eleven pairs go in calls of " + TranslationScores.SERVED_AT_ONCE + ", so a stop or a failure costs at most those");
        assertTrue(seen.stream().allMatch(x -> x.auth() == null), "the secret stops at the front: the backend on loopback never sees it");
        assertEquals("", s.stopped());
        assertEquals("google/metricx-24-hybrid-large-v2p6 in QE mode, at " + base + "/metricx, on cpu", s.model(), "the note says where it ran and on what");
        String shortSaid = new TranslationScores.Scorers(null, s).said().replaceAll(" \\(.*?\\)", "");   // as the result's "Scored by" line shortens it
        assertFalse(shortSaid.contains(")"), "the bracket comes off whole: " + shortSaid);
        TranslationScores.Scorers sc = new TranslationScores.Scorers(null, s);
        assertTrue(sc.said().contains("at " + base + "/metricx"), sc.said());
        // the plan says where it runs before the job
        var placed = Translation.placed("checked");
        assertEquals(List.of(new Translation.Placed("MetricX-24", base + "/metricx", Translation.Placed.AT)), placed);
        assertEquals("MetricX-24 scores it at " + base + "/metricx", placedOf(placed));
        assertTrue(Translation.placed("quick").isEmpty(), "the quick tier scores nothing");
    }

    static String placedOf(List<Translation.Placed> placed) {
        Translation.Plan p = new Translation.Plan("doc.md", "md", "ja", "en", "md", "checked", 10, 1000, 10, 60, 120, 240, true, "", false, 0, 0, "", false, 0, 0, "", null, null, 0, 0, null, 0, placed);
        return p.placedSaid(Chrome.ENGLISH);
    }

    @Test void aWrongSecretIsRefusedAtOnceAndAServerBeyondLoopbackNeedsOne() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer backend = metricxStandIn(seen);
        String base = front(Map.of("metricx", at(backend)), "the right words");
        seen.clear();   // the front's own look at its backend's health, as it starts
        // asked with no secret or another one, the front answers 401 and the backend hears nothing
        HttpResponse<String> bare = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/metricx/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, bare.statusCode());
        assertTrue(seen.isEmpty(), "nothing reached the backend: " + seen);
        Config.set(Helpers.Helper.METRICX.setting, base + "/metricx"); Config.set(Helpers.SECRET_NAME, "the wrong words");
        TranslationScores.SERVED_RETRY = Duration.ofMinutes(5);
        Scorer s = TranslationScores.metricxScorer();
        long t0 = System.currentTimeMillis();
        List<Double> got = s.score(pairs(3));
        assertTrue(System.currentTimeMillis() - t0 < 10_000, "a refused secret is not waited out");
        assertTrue(got.stream().allMatch(v -> v == null));
        assertEquals("MetricX-24 at " + base + "/metricx refused this library's secret (" + Helpers.SECRET_NAME + "), so the translation went on without it", s.stopped());
        assertTrue(seen.isEmpty());
        var placed = Translation.placed("checked");
        assertEquals(Translation.Placed.REFUSED, placed.get(0).state());
        assertEquals("MetricX-24 at " + base + "/metricx refuses this library's secret, so the run goes without it; researchzosho settings set helpers_secret <the secret it serves with> sets it", placedOf(placed));
        // a front on every address of the machine without a secret is refused before it listens
        IOException e = assertThrows(IOException.class, () -> HelperServer.serve("0.0.0.0", 0, "", Map.of("metricx", at(backend)), List.of()));
        assertTrue(e.getMessage().contains("needs a secret"), e.getMessage());
    }

    @Test void aNamedHelperThatDoesNotAnswerIsSaidAtThePlanAndWaitedForABoundedTimeInFlight() throws Exception {
        String dead = "http://127.0.0.1:" + closedPort() + "/metricx";
        Config.set(Helpers.Helper.METRICX.setting, dead);
        var placed = Translation.placed("reviewed");
        assertEquals(Translation.Placed.DOWN, placed.get(0).state());
        assertEquals("MetricX-24 at " + dead + " does not answer now: the run waits a while for it, then goes on without it and says so in its note", placedOf(placed));
        TranslationScores.SERVED_RETRY = Duration.ofSeconds(2);
        Scorer s = TranslationScores.metricxScorer();
        long t0 = System.currentTimeMillis();
        List<Double> got = s.score(pairs(3));
        long took = System.currentTimeMillis() - t0;
        assertTrue(took >= 1_900 && took < 15_000, "waited about the bound: " + took);
        assertTrue(got.stream().allMatch(v -> v == null));
        assertTrue(s.stopped().startsWith("MetricX-24 at " + dead + " gave no scores: it did not answer for 2 seconds ("), s.stopped());
        assertTrue(s.stopped().endsWith("; the translation went on without it"), s.stopped());
        t0 = System.currentTimeMillis();
        s.score(pairs(3));
        assertTrue(System.currentTimeMillis() - t0 < 1_000, "once stopped, the rest of the run does not wait again");
    }

    @Test void aHelperThatStopsAnsweringInFlightGoesOnWithoutItAndTheRunsNoteSaysSo(@TempDir Path tmp) throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer backend = metricxStandIn(seen);
        Config.set(Helpers.LOCAL, "off");   // a small machine: what does not answer is never replaced by one of its own
        Config.set(Helpers.Helper.METRICX.setting, at(backend));
        TranslationScores.SERVED_RETRY = Duration.ofSeconds(1);
        Scorer s = TranslationScores.metricxScorer();
        assertEquals(8, s.score(pairs(8)).stream().filter(v -> v != null).count());
        backend.stop(0);
        List<Double> after = s.score(pairs(8));
        assertTrue(after.stream().allMatch(v -> v == null));
        assertTrue(s.stopped().startsWith("MetricX-24 at " + at(backend) + " gave no more scores after 8 units: it did not answer for 1 second"), s.stopped());
        assertInstanceOf(TranslationScores.Served.class, TranslationScores.metricxScorer(), "the named one stays the scorer; no process of this machine is started in its place");
        // a whole checked run with the configured scorers: the note's scorers line says what happened
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = tmp.resolve("doc.md");
        Files.writeString(in, "# 見出し\n\n" + "これは試験のための段落で、翻訳されて点数をつけられるための十分な長さがあります。\n\n".repeat(3), StandardCharsets.UTF_8);
        Translation.SETUP_SAID = () -> "";
        try {
            Translation.Outcome o = Translation.run(store, in, "en", "", "checked", tmp.resolve("doc.en.md"), null, TranslationTest.seats(new TranslationTest.Script()), null, () -> false);
            String note = Files.readString(o.note(), StandardCharsets.UTF_8);
            assertTrue(note.contains("MetricX-24 at " + at(backend) + " gave no scores: it did not answer for 1 second"), note);
            assertTrue(note.contains("LaBSE is off (RESEARCHZOSHO_LABSE=off)"), note);
        } finally { Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; }; }
    }

    @Test void aMachineSetToRunNoHeavyHelperStartsNoneAndSaysWhatTheRunGoesWithout() throws Exception {
        Config.set("RESEARCHZOSHO_LABSE", "");   // unset: on an ordinary machine this would start a container; here nothing may start
        Config.set(Helpers.LOCAL, "off");
        assertFalse(Helpers.localAllowed());
        assertNull(TranslationScores.labseAddress(true), "no LaBSE container is started");
        assertNull(TranslationScores.metricxScorer(), "no MetricX-24 process");
        assertEquals("", TranslationScores.setupInBackground("start"), "nothing is set up in the background");
        assertEquals("", LikenessHelper.setupInBackground("start"));
        assertNull(LikenessHelper.scorer());
        assertNull(Video.whisperBase(), "no transcription container either");
        var placed = Translation.placed("checked");
        assertEquals(2, placed.size());
        assertEquals("LaBSE and MetricX-24 are not run on this machine and no address names them, so the run goes without them; researchzosho settings set helpers http://<machine>:"
                + Helpers.PORT + " names a machine that serves them", placedOf(placed));
        TranslationScores.Scorers sc = TranslationScores.configured();
        assertFalse(sc.any());
        assertEquals("no scorer: LaBSE is not run on this machine (local_helpers off) and no address names one and MetricX-24 is not run on this machine (local_helpers off) and no address names one", sc.said());
        assertEquals("", TranslationScores.metricxSetupSaid(), "the plan's own clause says it, not the setup's");
        assertTrue(Helpers.localRefusedSaid("LaBSE and MetricX-24").contains("researchzosho settings set local_helpers on"));
    }

    @Test void theHelpersAddressIsWhatTheOthersDefaultFromAndASilentOneStaysNamed() throws Exception {
        Config.set(Helper.LABSE.setting, "");
        Config.set(Helpers.HELPERS, "http://homebox:8217/");
        Helpers.SERVES = b -> Map.of("metricx", "google/metricx-24-hybrid-large-v2p6 on cuda", "labse", "sentence-transformers/LaBSE", "embed", "answering");
        assertEquals("http://homebox:8217", Helpers.base());
        assertEquals("http://homebox:8217/metricx", Helpers.address(Helper.METRICX));
        assertEquals("http://homebox:8217/labse", Helpers.address(Helper.LABSE));
        assertNull(Helpers.address(Helper.LIKENESS), "not served there: this machine's own, as before");
        Config.set(Helper.LABSE.setting, "http://other:9000");
        assertEquals("http://other:9000", Helpers.address(Helper.LABSE), "a helper's own address comes first");
        Config.set(Helper.METRICX.setting, "off");
        assertNull(Helpers.address(Helper.METRICX)); assertTrue(Helpers.off(Helper.METRICX));
        Config.set(Helper.METRICX.setting, "");
        Helpers.SERVES = b -> null;   // the helpers machine does not answer: each helper stays named there, and the work says it did not answer
        assertEquals("http://homebox:8217/likeness", Helpers.address(Helper.LIKENESS));
        assertInstanceOf(TranslationScores.Served.class, TranslationScores.metricxScorer(), "never this machine's own in its place");
    }

    @Test void theLikenessHelperServedByAnotherMachineMeasuresAndItsNoteSaysWhere() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(x.getRequestURI().getPath(), x.getRequestHeaders().getFirst("Authorization"), body));
            if (x.getRequestURI().getPath().equals("/health")) { reply(x, 200, "{\"ready\":true,\"model\":\"open_clip ViT-B-32 laion2b_s34b_b79k; OpenCV YuNet + SFace\",\"device\":\"cpu\"}"); return; }
            reply(x, 200, "{\"replies\":[{\"id\":\"1\",\"picture\":0.8123,\"face\":0.61,\"faces\":[true,true],\"face_px\":[120,130],\"face_conf\":[0.9,0.95]}]}");
        });
        backend.start(); servers.add(backend);
        String base = front(Map.of("likeness", at(backend)), "pictures");
        Config.set(Helpers.HELPERS, base); Config.set(Helpers.SECRET_NAME, "pictures"); Config.set(Helpers.LOCAL, "off");
        LikenessHelper.Scorer s = LikenessHelper.scorer();
        assertInstanceOf(LikenessHelper.Served.class, s, "served there, on a machine that runs none itself");
        LikenessHelper.Measure m = s.score(new byte[]{1, 2, 3}, "image/png", new byte[]{4, 5}, "image/jpeg");
        assertEquals(0.8123, m.picture(), 1e-9); assertEquals(0.61, m.face(), 1e-9); assertTrue(m.refFace() && m.candFace()); assertEquals(130, m.candPx());
        JsonNode sent = M.readTree(seen.stream().filter(x -> x.path().equals("/measure")).findFirst().orElseThrow().body()).path("requests").path(0);
        assertEquals("AQID", sent.path("ref").asText(), "the pictures go as the helper reads them");
        assertTrue(LikenessHelper.said(Chrome.ENGLISH).contains("), at " + base + "/likeness, measured each candidate"), LikenessHelper.said(Chrome.ENGLISH));
        assertTrue(LikenessHelper.status().startsWith("The likeness helper is served at " + base + "/likeness (open_clip"), LikenessHelper.status());
    }

    /** A stand-in embeddings server, as Text Embeddings Inference answers: a health that says nothing, its model at /info, a vector for each input. */
    HttpServer embedStandIn(List<Seen> seen) throws IOException { return embedStandIn(seen, ""); }

    /** The same, its health as {@code health} says: "" for a 200 that says nothing, words for a 200 in plain words, null for no health page (404). */
    HttpServer embedStandIn(List<Seen> seen, String health) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), path = x.getRequestURI().getPath();
            seen.add(new Seen(path, x.getRequestHeaders().getFirst("Authorization"), body));
            if (path.equals("/health") && health == null) { reply(x, 404, "{}"); return; }
            if (path.equals("/health") && !health.isEmpty()) { byte[] b = health.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8"); x.sendResponseHeaders(200, b.length); try (OutputStream os = x.getResponseBody()) { os.write(b); } return; }
            if (path.equals("/health")) { x.sendResponseHeaders(200, -1); x.close(); return; }
            if (path.equals("/info")) { reply(x, 200, "{\"model_id\":\"stand-in/embedder\"}"); return; }
            if (path.equals("/v1/embeddings")) {
                JsonNode in = M.readTree(body).path("input");
                ObjectNode o = M.createObjectNode(); ArrayNode data = o.putArray("data");
                for (int i = 0; i < (in.isArray() ? in.size() : 1); i++) { ObjectNode d = data.addObject(); d.put("index", i); d.putArray("embedding").add(0.6).add(0.8); }
                reply(x, 200, o.toString()); return;
            }
            reply(x, 404, "{}");
        });
        s.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        s.start();
        servers.add(s);
        return s;
    }

    /**
     * Embeddings from the helpers machine, for a search and for an add. A search embeds its question alone and an add embeds entries
     * together, two calls of the embedder; the first went without the secret, so the front refused it, search by meaning said the server
     * did not answer and every search was by words alone, found by running a library against a served embedder.
     */
    @Test void embeddingsServedByAnotherMachineCarryTheSecretForASearchAndForAnAdd() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer backend = embedStandIn(seen);
        String base = front(Map.of("embed", at(backend), "labse", at(backend)), "words only we know");
        Config.set(Helpers.HELPERS, base); Config.set(Helpers.SECRET_NAME, "words only we know");
        seen.clear();
        var embedder = Embeddings.http(base + "/embed", "embed");   // the embedder of a library that names this machine (the suite's own environment turns the setting off)
        float[] question = embedder.embed("what a person searches for");
        assertNotNull(question, "a search's question gets its vector: the secret went with the call");
        List<float[]> entries = embedder.embedAll(List.of("one entry", "another entry"));
        assertEquals(2, entries.size());
        assertNotNull(entries.get(0)); assertNotNull(entries.get(1));
        assertEquals(2, seen.stream().filter(x -> x.path().equals("/v1/embeddings")).count(), "both calls reached the embedder behind the front: " + seen);
        // where a helper runs, for a person: the model the helpers machine names, never an empty bracket for a server whose own health says nothing
        Config.set(Helper.LABSE.setting, "");
        String status = HelpersCli.statusSaid();
        assertTrue(status.contains("LaBSE: at " + base + "/labse (stand-in/embedder)"), status);
        assertFalse(status.contains("()"), status);
    }

    /**
     * A laptop off its home network names a machine it cannot reach. Each call to it waited out its own connection, sixteen seconds a
     * search, measured; now the call that finds the machine away is the one that waits, and the next half minute's calls know.
     */
    @Test void aMachineThatGaveNoConnectionIsNotWaitedForAgainForAWhile() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer backend = embedStandIn(seen);
        String address = at(backend);
        var embedder = Embeddings.http(address, "embed");
        assertNotNull(embedder.embed("a question"));
        // a refusal at once marks nothing: a server that is starting is asked again at once
        Helpers.failed(address, new ConnectException("refused"), System.currentTimeMillis());
        assertFalse(Helpers.away(address));
        // an answer that failed after the connection marks nothing either, however long it took
        Helpers.failed(address, new IOException("HTTP 500"), System.currentTimeMillis() - 5_000);
        assertFalse(Helpers.away(address));
        // a connection that timed out after its wait: the machine is away, whatever the path under it
        Helpers.failed(address + "/v1/embeddings", new IOException(new HttpConnectTimeoutException("timed out")), System.currentTimeMillis() - 3_000);
        assertTrue(Helpers.away(address));
        assertTrue(Helpers.away(address + "/health"));
        assertFalse(Helpers.away("http://127.0.0.1:" + closedPort()), "another port of the machine is another server");
        seen.clear();
        assertNull(embedder.embed("a question"), "a search goes by words until the machine is back");
        assertNull(embedder.embedAll(List.of("an entry")).get(0));
        assertNull(Helpers.health(address));
        assertTrue(seen.isEmpty(), "nothing was sent to a machine taken as away: " + seen);
        Helpers.forget();   // as the half minute passing
        assertNotNull(embedder.embed("a question"), "it is asked again after that");
    }

    /**
     * An embeddings server of another program, named by its own address. One behind a proxy answers its health in plain words ("OK"),
     * and one has no health page at all; the status said of both "does not answer now" while searches were using them, seen on a real
     * library. Both answer the embeddings call, and that is what the status and a plan go by.
     */
    @Test void anEmbeddingsServerWhoseHealthIsPlainWordsOrMissingIsSaidToAnswer() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        for (String health : new String[]{"OK", null}) {
            HttpServer server = embedStandIn(seen, health);
            String at = at(server);
            assertEquals("", Helpers.health(Helper.LABSE, at), "it answers, and says nothing more of itself (health: " + health + ")");
            assertEquals("", Helpers.health(Helper.EMBED, at));
            Config.set(Helper.LABSE.setting, at);
            String status = HelpersCli.statusSaid();
            assertTrue(status.contains("LaBSE: at " + at + " (answering)"), status);
            assertFalse(status.contains("does not answer"), status);
            assertEquals(Translation.Placed.AT, Translation.placed("checked").get(0).state(), "a plan says it scores there, not that it is silent");
        }
        // a helper that only this program serves has no such second question: no health page is no answer
        HttpServer bare = embedStandIn(seen, null);
        assertNull(Helpers.health(Helper.METRICX, at(bare)));
        // and a server that answers neither is silent
        assertNull(Helpers.health(Helper.EMBED, "http://127.0.0.1:" + closedPort()));
    }

    @Test void theSecretGoesOnlyToAddressesThisProgramServes() throws IOException {
        Config.set(Helpers.SECRET_NAME, "s"); Config.set(Helpers.HELPERS, "http://homebox:8217");
        Config.set(Helper.EMBED.setting, "http://embedder:30001"); Config.set(Helper.LABSE.setting, "http://homebox2:8217/labse"); Config.set(Helper.METRICX.setting, "http://gpu:9100");
        assertTrue(Helpers.ours("http://homebox:8217/whisper/v1/audio/transcriptions"));
        assertTrue(Helpers.ours("http://homebox2:8217/labse/v1/embeddings"), "a helper's own address in the form helpers serve gives");
        assertTrue(Helpers.ours("http://gpu:9100/score"), "only this program serves MetricX-24");
        assertFalse(Helpers.ours("http://embedder:30001/v1/embeddings"), "another program's embeddings server never gets it");
        assertFalse(Helpers.request("http://embedder:30001/v1/embeddings").GET().build().headers().firstValue("Authorization").isPresent());
        assertEquals("Bearer s", Helpers.request("http://homebox:8217/health").GET().build().headers().firstValue("Authorization").orElse(""));
    }

    @Test void theSettingsPageTakesAnAddressOrOffForAHelper() {
        Settings.Setting metricx = Settings.known().stream().filter(s -> s.key().equals(Helper.METRICX.setting)).findFirst().orElseThrow();
        assertEquals("", Settings.refuse(metricx, "off"));
        assertEquals("", Settings.refuse(metricx, "http://homebox:8217/metricx"));
        assertFalse(Settings.refuse(metricx, "homebox").isEmpty());
        Settings.Setting local = Settings.known().stream().filter(s -> s.key().equals(Helpers.LOCAL)).findFirst().orElseThrow();
        assertEquals("", Settings.refuse(local, "off"));
        assertTrue(Settings.known().stream().anyMatch(s -> s.key().equals(Helpers.SECRET_NAME) && s.kind() == Settings.Kind.secret));
    }
}
