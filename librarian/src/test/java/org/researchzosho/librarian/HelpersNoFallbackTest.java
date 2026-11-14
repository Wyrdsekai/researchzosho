package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.Helpers.Helper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetricX-24 never on the processor, as an option (0.7.5), through the seams: the command a set starts it with, with and without the
 * option and on a set on the processor; a card with no room keeps it listed in the front's health with why, and a request for it gets
 * 503 with that reason; it is tried again on a request once three minutes have passed (by the clock seam) and not before, and loaded
 * again it serves; a process that dies stays listed and is started again; a library with MetricX-24 of its own and local_helpers on
 * never scores on its own processor when the helpers machine lists MetricX-24 as not loaded, and its plan and note say what happened;
 * the first load at install says no room and goes on; `translate scorers status` in its three states.
 */
class HelpersNoFallbackTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String NO_ROOM = "{\"ready\": false, \"not_loaded\": \"no room\", \"free_gb\": 0.4}";
    static final HelperCards.Card THREE = new HelperCards.Card("3", "GPU-dddd3333-3333-3333-3333-333333333333", "NVIDIA RTX 6000 Ada Generation", 49140, 409);
    static final HelperCards.Choice CARD = new HelperCards.Choice(THREE, false, "", false), CPU = new HelperCards.Choice(null, true, "", false);
    String home;
    HelperServer.Starter start;
    HelperCards.Probe probe;
    BiPredicate<Path, HelperCards.Choice> sees;
    Predicate<String> answers;
    Duration retry, served;
    final List<List<String>> started = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new ArrayList<>();
    final List<AutoCloseable> closing = new ArrayList<>();
    long[] now = {1_000_000_000L};
    /** What the next started process does: "no room" (ends with 3), "loads" (a stand-in MetricX-24 on its port), "dies" (loads, then is ended by the test). */
    volatile String next = "no room";
    final List<FakeProcess> processes = new CopyOnWriteArrayList<>();

    @BeforeEach void isolate(@TempDir Path tmp) throws IOException {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); Helpers.forget();
        start = HelperServer.START; answers = HelperServer.ANSWERS; retry = HelperServer.RETRY; served = TranslationScores.SERVED_RETRY; probe = HelperCards.PROBE; sees = TranslationScores.TORCH_SEES;
        HelperCards.PROBE = new HelperCardsTest.Fake();   // four made-up cards: nvidia-smi of the machine the tests run on is never asked
        TranslationScores.TORCH_SEES = (py, card) -> true;
        HelperServer.NOW = () -> now[0];
        Config.set("RESEARCHZOSHO_LABSE", "off");
        // MetricX-24 installed on this machine: the helpers machine's, and the library's own in the library tests
        Files.createDirectories(TranslationScores.metricxPython().getParent());
        Files.writeString(TranslationScores.metricxReady(), "x"); Files.writeString(TranslationScores.metricxScript(), "#"); Files.writeString(TranslationScores.metricxPython(), "#!/bin/sh\n");
        HelperServer.START = pb -> {
            started.add(new ArrayList<>(pb.command()));
            FakeProcess p = switch (next) {
                case "no room" -> new FakeProcess(NO_ROOM, false);
                default -> { int port = Integer.parseInt(pb.command().get(pb.command().indexOf("--serve") + 1)); metricxOn(port); yield new FakeProcess("", true); }
            };
            processes.add(p);
            return p;
        };
    }
    @AfterEach void restore() throws Exception {
        for (AutoCloseable c : closing) { try { c.close(); } catch (RuntimeException e) { /* the seams are put back below whatever a close says */ } }
        for (HttpServer s : servers) s.stop(0);
        HelperServer.START = start; HelperServer.ANSWERS = answers; HelperServer.RETRY = retry; HelperServer.NOW = System::currentTimeMillis; TranslationScores.SERVED_RETRY = served;
        HelperCards.PROBE = probe; TranslationScores.TORCH_SEES = sees;
        System.setProperty("user.home", home); Config.invalidate(); Helpers.forget();
    }

    /** A process as a test needs it: its output, and alive until the test ends it. */
    static class FakeProcess extends Process {
        final byte[] out; final AtomicBoolean alive;
        FakeProcess(String out, boolean alive) { this.out = (out.isEmpty() ? "" : out + "\n").getBytes(StandardCharsets.UTF_8); this.alive = new AtomicBoolean(alive); }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(out); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exitValue(); }
        @Override public boolean waitFor(long t, TimeUnit u) { return !alive.get(); }
        @Override public int exitValue() { if (alive.get()) throw new IllegalThreadStateException(); return out.length > 0 ? 3 : 1; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public void destroy() { alive.set(false); }
        @Override public Process destroyForcibly() { alive.set(false); return this; }
    }

    /** A stand-in MetricX-24 in its serving mode on {@code port}: its health on card 3, a score for each pair. */
    void metricxOn(int port) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            s.createContext("/", x -> {
                x.getRequestBody().readAllBytes();
                reply(x, 200, x.getRequestURI().getPath().equals("/health") ? "{\"ready\":true,\"model\":\"google/metricx-24-hybrid-large-v2p6\",\"device\":\"cuda\",\"card\":\"NVIDIA RTX 6000 Ada Generation\",\"card_uuid\":\"" + THREE.uuid() + "\"}" : "{\"scores\":[1.5]}");
            });
            s.start(); servers.add(s);
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    static void reply(HttpExchange x, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    /** A front serving MetricX-24 on card 3, as a set with the option starts it. */
    String front(boolean noFallback, HelperCards.Choice card) throws IOException {
        HelperServer.Backends b = HelperServer.backends(List.of(Helper.METRICX), false, line -> { }, card, noFallback);
        HelperServer h = HelperServer.serve("127.0.0.1", 0, "the words", b, List.of());
        closing.add(h);
        return "http://127.0.0.1:" + h.port();
    }

    static HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer the words").timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    String healthOf(String base, String helper) throws Exception { return M.readTree(get(base + "/health").body()).path("helpers").path(helper).asText(); }

    void waitFor(int starts) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (started.size() < starts && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals(starts, started.size());
    }

    @Test void theOptionReachesTheCommandOnlyOnACard() throws Exception {
        front(true, CARD); front(false, CARD); front(true, CPU);
        assertTrue(started.get(0).contains("--no-cpu-fallback"), "with the option, on a card: never the processor: " + started.get(0));
        assertFalse(started.get(1).contains("--no-cpu-fallback"), "without it: as before");
        assertFalse(started.get(2).contains("--no-cpu-fallback"), "a set on the processor has no card to fall back from");
    }

    @Test void notLoadedIsListedWithWhyARequestGets503AndItIsTriedAgainAfterThreeMinutesAndThenServes() throws Exception {
        String base = front(true, CARD);
        assertEquals("not loaded: no room on card 3 (0.4 GB free)", healthOf(base, "metricx"), "listed, with why");
        HttpResponse<String> r = get(base + "/metricx/score");
        assertEquals(503, r.statusCode());
        assertEquals("not loaded: no room on card 3 (0.4 GB free)", M.readTree(r.body()).path("error").asText());
        now[0] += 120_000;
        get(base + "/metricx/score");
        Thread.sleep(200);
        assertEquals(1, started.size(), "two minutes after the try: not tried again");
        next = "loads";
        now[0] += 61_000;
        assertEquals(503, get(base + "/metricx/score").statusCode(), "the request that finds it due starts a try, and is told why meanwhile");
        waitFor(2);
        long until = System.currentTimeMillis() + 10_000;
        while (!healthOf(base, "metricx").startsWith("google/") && System.currentTimeMillis() < until) Thread.sleep(50);
        assertEquals("google/metricx-24-hybrid-large-v2p6 on cuda (card 3, NVIDIA RTX 6000 Ada Generation)", healthOf(base, "metricx"), "loaded: the usual line");
        r = get(base + "/metricx/score");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("1.5"));
    }

    @Test void aProcessThatDiesStaysListedAndIsStartedAgain() throws Exception {
        next = "loads";
        String base = front(true, CARD);
        assertEquals(200, get(base + "/metricx/score").statusCode());
        processes.get(0).destroy();
        assertTrue(healthOf(base, "metricx").startsWith("not loaded: its process ended (exit"), healthOf(base, "metricx"));
        now[0] += 200_000;
        HttpResponse<String> r = get(base + "/metricx/score");
        assertEquals(503, r.statusCode());
        assertTrue(M.readTree(r.body()).path("error").asText().startsWith("not loaded"), r.body());
        waitFor(2);
        long until = System.currentTimeMillis() + 10_000;
        while (get(base + "/metricx/score").statusCode() != 200 && System.currentTimeMillis() < until) Thread.sleep(50);
        assertEquals(200, get(base + "/metricx/score").statusCode(), "started again, and serving");
        assertTrue(M.readTree(get(base + "/health").body()).path("helpers").has("metricx"), "never dropped from the list");
    }

    @Test void aLibraryWithItsOwnMetricxNeverScoresLocallyWhenTheHelpersMachineHasItNotLoaded(@TempDir Path tmp) throws Exception {
        String base = front(true, CARD);
        Config.set(Helpers.HELPERS, base); Config.set(Helpers.SECRET_NAME, "the words");
        assertTrue(Helpers.localAllowed() && TranslationScores.metricxInstalled(), "a library that could run its own");
        HelperServer.RETRY = Duration.ofHours(1);
        assertEquals(base + "/metricx", Helpers.address(Helper.METRICX), "listed there: named there");
        assertInstanceOf(TranslationScores.Served.class, TranslationScores.metricxScorer(), "never its own process in its place");
        var placed = Translation.placed("checked");
        assertEquals(new Translation.Placed("MetricX-24", base + "/metricx", Translation.Placed.NOT_LOADED, "no room on card 3 (0.4 GB free)"), placed.get(0));
        assertEquals("MetricX-24 at " + base + "/metricx is not loaded there now (no room on card 3 (0.4 GB free)): the run waits a while for it, then goes on without it and says so in its note", HelpersTest.placedOf(placed));
        assertEquals("scores at " + base + "/metricx, which has it not loaded: no room on card 3 (0.4 GB free)", TranslationScores.whereSaid(Helper.METRICX));
        // a checked run: it waits its bounded time, goes on without it, and the note says why
        TranslationScores.SERVED_RETRY = Duration.ofSeconds(1);
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = tmp.resolve("doc.md");
        Files.writeString(in, "# 見出し\n\n" + "これは試験のための段落で、翻訳されて点数をつけられるための十分な長さがあります。\n\n".repeat(3), StandardCharsets.UTF_8);
        Translation.SETUP_SAID = () -> "";
        try {
            Translation.Outcome o = Translation.run(store, in, "en", "", "checked", tmp.resolve("doc.en.md"), null, TranslationTest.seats(new TranslationTest.Script()), null, () -> false);
            String note = Files.readString(o.note(), StandardCharsets.UTF_8);
            assertTrue(note.contains("MetricX-24 at " + base + "/metricx gave no scores: it did not answer for 1 second (not loaded: no room on card 3 (0.4 GB free)); the translation went on without it"), note);
        } finally { Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; }; }
        assertEquals(1, started.size(), "the one start is the helpers machine's; the library started none of its own");
    }

    @Test void aHelperTheSetWasInstalledToServeStaysListedAndOneItWasNeverMeantToServeDoesNot() throws Exception {
        // the likeness helper is not set up on this machine
        HelperServer.Backends only = HelperServer.backends(List.of(Helper.METRICX, Helper.LIKENESS), true, line -> { }, CARD, true);
        assertEquals(HelperServer.NOT_SET_UP, only.absent().get("likeness"), "named by the unit's --only: listed, as not set up");
        HelperServer.Backends all = HelperServer.backends(List.of(Helper.METRICX, Helper.LIKENESS), false, line -> { }, CARD, true);
        assertFalse(all.names().contains("likeness"), "a helper the set was never meant to serve stays unlisted, as before");
        assertTrue(all.names().contains("metricx"), "MetricX-24 is listed, loaded or not");
        HelperServer h = HelperServer.serve("127.0.0.1", 0, "the words", only, List.of());
        closing.add(h);
        HttpResponse<String> r = get("http://127.0.0.1:" + h.port() + "/likeness/measure");
        assertEquals(503, r.statusCode());
        assertEquals(HelperServer.NOT_SET_UP, M.readTree(r.body()).path("error").asText());
        for (HelperServer.Managed m : all.managed().values()) m.end();
    }

    @Test void theFirstLoadAtInstallSaysNoRoomAndGoesOnNeverOnTheProcessor() throws Exception {
        HelperServer.Starter check = TranslationScores.CHECK;
        List<List<String>> commands = new ArrayList<>(); List<String> said = new ArrayList<>();
        try {
            TranslationScores.CHECK = pb -> { commands.add(new ArrayList<>(pb.command())); return new FakeProcess(NO_ROOM, false); };
            Files.delete(TranslationScores.metricxReady());
            assertEquals("ready", TranslationScores.installMetricx(CARD, said::add, true));
            assertTrue(commands.get(0).contains("--no-cpu-fallback"));
            assertEquals(List.of("MetricX-24 is set up, and not loaded now: no room on card 3 (0.4 GB free). It is not loaded on the processor (--no-cpu-fallback); the service loads it when the card has room."), said);
            assertTrue(TranslationScores.metricxInstalled(), "set up: the service loads it when there is room");
            TranslationScores.CHECK = pb -> { commands.add(new ArrayList<>(pb.command())); return new FakeProcess("{\"ready\": true, \"device\": \"cpu\"}", false) { @Override public int exitValue() { return 0; } }; };
            TranslationScores.installMetricx(CARD, said::add, false);
            assertFalse(commands.get(1).contains("--no-cpu-fallback"), "without the option, as before");
        } finally { TranslationScores.CHECK = check; }
    }

    @Test void translateScorersStatusSaysWhereEachScoresForThisLibraryAsAPair() throws Exception {
        // on this machine
        assertTrue(TranslationScores.whereSaid(Helper.METRICX).startsWith("scores on this machine, installed in "), TranslationScores.whereSaid(Helper.METRICX));
        // left out, and why (LaBSE asked only here: a local LaBSE is never called by this test)
        Config.set("RESEARCHZOSHO_LABSE", "");
        Config.set(Helpers.LOCAL, "off");
        assertEquals("left out: it is not run on this machine (local_helpers off) and no address names one", TranslationScores.whereSaid(Helper.METRICX));
        assertEquals("left out: it is not run on this machine (local_helpers off) and no address names one", TranslationScores.whereSaid(Helper.LABSE));
        // at the helpers address, with the front's own line; also with this machine's folder gone (the real case)
        next = "loads";
        String base = front(true, CARD);
        Config.set(Helpers.HELPERS, base); Config.set(Helpers.SECRET_NAME, "the words"); Helpers.forget();
        Files.delete(TranslationScores.metricxReady());
        assertEquals("scores at " + base + "/metricx (google/metricx-24-hybrid-large-v2p6 on cuda (card 3, NVIDIA RTX 6000 Ada Generation))", TranslationScores.whereSaid(Helper.METRICX));
        assertEquals("off (RESEARCHZOSHO_METRICX=off)", offed());
    }

    String offed() throws IOException { Config.set(Helper.METRICX.setting, "off"); return TranslationScores.whereSaid(Helper.METRICX); }
}
