package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.Helpers.Helper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The library finds the helpers server on its main model's machine by itself (0.7.4): with no {@code helpers} set and the default
 * model served from another machine of the person's, {@code http://<that host>:8217/health} is asked. 200: used as if named. 401 from
 * our front: asked again with the secret, and used on 200; with none, or a wrong one, not used, and the status, a plan and setup say
 * it needs its secret. Never a hosted service's host, never loopback, never with {@code helpers off} or a set address; the secret
 * never to a machine that did not answer as our front.
 */
class HelpersFindTest {
    String home;
    Function<String, Helpers.Look> look;
    Function<String, String> at;
    final List<HttpServer> servers = new ArrayList<>();
    final List<AutoCloseable> closing = new ArrayList<>();
    final List<String> asked = new CopyOnWriteArrayList<>();

    @BeforeEach void isolate(@TempDir Path tmp) throws IOException {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        Helpers.forget(); look = Helpers.LOOK; at = Helpers.AT;
        Config.set("RESEARCHZOSHO_LABSE", "off");
        Helpers.LOOK = b -> { asked.add(b); return Helpers.lookNow(b); };   // the suite turns the look off; these tests make it, against stand-ins
    }
    @AfterEach void restore() throws Exception {
        for (AutoCloseable c : closing) c.close();
        for (HttpServer s : servers) s.stop(0);
        Helpers.LOOK = look; Helpers.AT = at;
        System.setProperty("user.home", home); Config.invalidate(); Helpers.forget();
    }

    record Seen(String path, String auth) { }

    static void reply(HttpExchange x, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    /** A stand-in that answers every call with {@code code} and {@code body}, and notes the path and the Authorization header. */
    String standIn(int code, String body, List<Seen> seen) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> { x.getRequestBody().readAllBytes(); seen.add(new Seen(x.getRequestURI().getPath(), x.getRequestHeaders().getFirst("Authorization"))); reply(x, code, body); });
        s.start(); servers.add(s);
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    /** Our front, with a stand-in MetricX-24 behind it. */
    String front(String secret) throws IOException {
        HttpServer b = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        b.createContext("/", x -> { x.getRequestBody().readAllBytes(); reply(x, 200, x.getRequestURI().getPath().equals("/health") ? "{\"ready\":true,\"model\":\"google/metricx-24-hybrid-large-v2p6\",\"device\":\"cpu\"}" : "{\"scores\":[1.5]}"); });
        b.start(); servers.add(b);
        HelperServer h = HelperServer.serve("127.0.0.1", 0, secret, Map.of("metricx", "http://127.0.0.1:" + b.getAddress().getPort()), List.of());
        closing.add(h);
        return "http://127.0.0.1:" + h.port();
    }

    /** The main model served from a machine called homebox, whose helpers port is {@code base} here. */
    void modelOn(String drive, String base) throws IOException {
        Config.set("RESEARCHZOSHO_DRIVE", drive);
        Helpers.AT = host -> host.equals("homebox") ? base : "http://" + host + ":" + Helpers.PORT;
    }

    @Test void aHelpersServerAnsweringOnTheModelsMachineIsUsedAsIfNamed() throws Exception {
        String base = front("");   // a front that asks no secret: answers 200 at once
        modelOn("http://homebox:8212", base);
        assertEquals(base, Helpers.base());
        assertEquals(base + "/metricx", Helpers.address(Helper.METRICX));
        assertInstanceOf(TranslationScores.Served.class, TranslationScores.metricxScorer());
        assertTrue(HelpersCli.statusSaid().contains("The machine that serves the helpers: " + base + ", found on the machine that serves the model."), HelpersCli.statusSaid());
        assertFalse(Helpers.ours(base + "/metricx"), "it asked no secret: none goes to it");
        for (int i = 0; i < 5; i++) Helpers.base();
        assertEquals(1, asked.size(), "kept five minutes: asked once");
        // a set address wins, and helpers off turns the look off
        Config.set(Helpers.HELPERS, "http://other:8217"); Helpers.forget(); asked.clear();
        assertEquals("http://other:8217", Helpers.base()); assertTrue(asked.isEmpty());
        Config.set(Helpers.HELPERS, "off"); Helpers.forget();
        assertNull(Helpers.base()); assertNull(Helpers.address(Helper.METRICX)); assertTrue(asked.isEmpty(), "helpers off: no machine is asked");
        assertTrue(HelpersCli.statusSaid().contains("No machine is asked for its helpers (helpers off)."));
    }

    @Test void ourFrontAsksForTheSecretAndIsUsedWithIt() throws Exception {
        String base = front("the words we share");
        modelOn("http://homebox:8212", base);
        Config.set(Helpers.SECRET_NAME, "the words we share");
        assertEquals(base, Helpers.base());
        assertTrue(Helpers.ours(base + "/metricx"), "it answered as our front: the secret goes with each call now");
        List<String[]> pairs = new ArrayList<>(); pairs.add(new String[]{"源", "a translation"});
        List<Double> got = TranslationScores.metricxScorer().score(pairs);
        assertEquals(1.5, got.get(0), 1e-9, "scored through the front, the secret accepted");
    }

    @Test void ourFrontWithNoSecretOrAWrongOneIsNotUsedAndSaysTheLineThatSetsIt() throws Exception {
        String base = front("the words we share");
        modelOn("http://homebox:8212", base);
        assertNull(Helpers.base(), "no secret: not used");
        assertEquals(base, Helpers.needsSecret());
        assertNull(Helpers.address(Helper.METRICX));
        String status = HelpersCli.statusSaid();
        assertTrue(status.contains("A helpers server answers on the machine that serves the model (" + base + ") and needs its secret: researchzosho helpers secret prints it there, and researchzosho settings set helpers_secret <the secret> sets it here."), status);
        var placed = Translation.placed("checked");
        assertEquals(Translation.Placed.NEEDS_SECRET, placed.get(0).state());
        assertTrue(HelpersTest.placedOf(placed).startsWith("a helpers server answers at " + base + ", on the machine that serves the model, and needs its secret: researchzosho helpers secret prints it there, and researchzosho settings set helpers_secret <the secret> sets it here"), HelpersTest.placedOf(placed));
        Config.set(Helpers.SECRET_NAME, "other words");   // a wrong one: a new look, and still not used
        assertNull(Helpers.base()); assertEquals(base, Helpers.needsSecret());
        assertFalse(Helpers.ours(base + "/metricx"));
    }

    @Test void theSecretNeverGoesToAMachineThatDidNotAnswerAsOurFront() throws Exception {
        Config.set(Helpers.SECRET_NAME, "the words we share");
        List<Seen> seen = new CopyOnWriteArrayList<>();
        String plain = standIn(200, "{\"helpers\":{\"metricx\":\"google/metricx-24-hybrid-large-v2p6 on cpu\"}}", seen);
        modelOn("http://homebox:8212", plain);
        assertEquals(plain, Helpers.base(), "a helpers server that asks no secret is used");
        Helpers.health(plain + "/metricx"); Helpers.SERVES.apply(plain); Helpers.request(plain + "/metricx/score").GET().build();
        assertTrue(seen.stream().allMatch(s -> s.auth() == null), "it never asked for one, so it never got one: " + seen);
        // a 401 that is not our front's refusal: nothing there, and no second ask with the secret
        List<Seen> other = new CopyOnWriteArrayList<>();
        String stranger = standIn(401, "{\"message\":\"Unauthorized\"}", other);
        Helpers.forget(); modelOn("http://homebox:8212", stranger);
        assertNull(Helpers.base()); assertNull(Helpers.needsSecret());
        assertEquals(1, other.size(), "asked once, without the secret");
        assertNull(other.get(0).auth());
    }

    @Test void neitherAHostedServiceNorLoopbackNorAPublicNameIsEverAsked() throws Exception {
        for (String drive : List.of("https://openrouter.ai/api/v1", "https://api.openai.com/v1", "http://127.0.0.1:8212", "http://localhost:8212", "http://[::1]:8212", "https://gpu.example.com:8212")) {
            Config.set("RESEARCHZOSHO_DRIVE", drive); Helpers.forget();
            assertNull(Helpers.base(), drive); assertNull(Helpers.candidate(), drive);
        }
        assertTrue(asked.isEmpty(), "never asked: " + asked);
        for (String drive : List.of("http://homebox:8212", "http://192.168.1.20:8212", "http://10.0.0.5:8080/v1", "http://homebox.local:8212", "http://gpu.tail1234.ts.net:8212", "http://100.101.102.103:8212")) {
            Config.set("RESEARCHZOSHO_DRIVE", drive);
            assertNotNull(Helpers.candidate(), drive);
        }
    }

    @Test void nothingThereIsSilentAndAFoundMachineThatGoesSilentStaysNamed() throws Exception {
        String base = front("");
        modelOn("http://homebox:8212", base);
        assertEquals(base, Helpers.base());
        // the machine's helpers server stops: within five minutes nothing is asked; after them the look finds nothing, and it stays named
        for (AutoCloseable c : closing) c.close();
        closing.clear();
        Helpers.forgetLooks();
        assertEquals(base, Helpers.base(), "found in this process: stays the one named");
        assertEquals(base + "/metricx", Helpers.address(Helper.METRICX), "named, and the work says it did not answer");
        // another process (a command line beside the service): what was found is kept in the state folder, so it says the same thing
        Helpers.forget();
        assertTrue(Files.isRegularFile(Helpers.foundFile()));
        assertEquals(base, Helpers.base(), "found by an earlier process: still the one named, not \"no address names it\"");
        assertTrue(HelpersCli.statusSaid().contains("MetricX-24: at " + base + "/metricx, which does not answer now"), HelpersCli.statusSaid());
        // a machine with nothing on its helpers port, never found: as with no helpers address, and nothing said
        Files.delete(Helpers.foundFile()); Helpers.forget();
        assertNull(Helpers.base()); assertNull(Helpers.needsSecret());
        assertFalse(HelpersCli.statusSaid().contains("helpers server"), HelpersCli.statusSaid());
    }

    @Test void anotherProgramAnsweringThereForgetsWhatWasFoundAndASetAddressOrOffMakesItIrrelevant() throws Exception {
        String base = front("");
        modelOn("http://homebox:8212", base);
        assertEquals(base, Helpers.base());
        assertTrue(Files.readString(Helpers.foundFile()).contains("\"base\":\"" + base + "\""), Files.readString(Helpers.foundFile()));
        // the helpers machine now answers with another program's page on that port: what was found goes
        List<Seen> seen = new CopyOnWriteArrayList<>();
        String other = standIn(200, "{\"status\":\"ok\"}", seen);
        Helpers.forget(); modelOn("http://homebox:8212", other);
        Files.writeString(Helpers.foundFile(), "{\"base\":\"" + other + "\",\"our_front\":false}\n");   // as if it had been ours there before
        assertNull(Helpers.base(), "a 200 that is no helpers list: another program's server");
        assertFalse(Files.exists(Helpers.foundFile()), "and what was found there is forgotten");
        // a set address, or off: no look, and the file is not read
        Files.writeString(Helpers.foundFile(), "{\"base\":\"" + base + "\",\"our_front\":false}\n");
        Config.set(Helpers.HELPERS, "off"); Helpers.forget();
        assertNull(Helpers.base());
        Config.set(Helpers.HELPERS, "http://other:8217"); Helpers.forget();
        assertEquals("http://other:8217", Helpers.base());
    }

    @Test void aMachineThatGivesNoConnectionIsAskedOnceInFiveMinutesUnlessItWasFoundBefore() throws Exception {
        long[] now = {1_000_000_000L};
        Helpers.NOW = () -> now[0];
        try {
            Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212");
            int[] asks = {0};
            Helpers.LOOK = b -> { asks[0]++; Helpers.failed(b, new ConnectException("no route"), now[0] - 3_000); return new Helpers.Look(Helpers.Look.NOTHING, b, null, false, true); };
            assertNull(Helpers.base());
            for (int s = 0; s < 290; s += 10) { now[0] += 10_000; assertNull(Helpers.base()); }
            assertEquals(1, asks[0], "never found: the silence is kept five minutes, not asked each half minute");
            now[0] += 20_000;
            Helpers.base();
            assertEquals(2, asks[0], "after five minutes, asked again");
            // a machine found before: asked again as soon as it is not away, half a minute
            Helpers.forget();
            Helpers.LOOK = b -> { asks[0]++; return new Helpers.Look(Helpers.Look.FOUND, b, Map.of("metricx", "x"), false, false); };
            Helpers.base(); Helpers.forgetLooks();
            Helpers.LOOK = b -> { asks[0]++; Helpers.failed(b, new ConnectException("no route"), now[0] - 3_000); return new Helpers.Look(Helpers.Look.NOTHING, b, null, false, true); };
            asks[0] = 0;
            assertEquals("http://homebox:8217", Helpers.base(), "found before: stays named while away");
            now[0] += 10_000; Helpers.base();
            assertEquals(1, asks[0], "away: not asked within the half minute");
            now[0] += 25_000; Helpers.base();
            assertEquals(2, asks[0], "past the half minute: asked again, so it comes back quickly");
        } finally { Helpers.NOW = System::currentTimeMillis; }
    }

    @Test void setupUsesAFoundOneAndOffersToSetOneUpOnTheModelsMachineWhenNoneIsThere(@TempDir Path tmp) throws Exception {
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212");
        Helpers.LOOK = b -> new Helpers.Look(Helpers.Look.NOTHING, b, null, false, false);
        List<String> onHost = new ArrayList<>();
        Setup.Acts withSsh = new Setup.Acts() {
            @Override public int installService(int port, PrintStream out) { return 0; }
            @Override public boolean haveClaude() { return false; }
            @Override public String claudeMcpAdd(List<String> a) { return "!"; }
            @Override public String launcher() { return "researchzosho"; }
            @Override public double cardGb() { return 0; }
            @Override public int installHelpersOn(String host, PrintStream out) { onHost.add(host); out.println("  (the install over ssh, stood in)"); return 0; }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Setup(new BufferedReader(new StringReader("n\ny\n")), new PrintStream(out, true), new SetupTest.FakeProbe("", List.of(), false), withSsh, false).helpersStep();
        String said = out.toString();
        assertTrue(said.contains("Your model is served from homebox. Set the heavy helpers up there too, over ssh? [y/N]"), said);
        assertEquals(List.of("homebox"), onHost, "run on the yes, never unasked");
        assertEquals("off", Config.get(Helpers.LOCAL));
        // a no: nothing is run there, and the address question follows
        onHost.clear(); Config.set(Helpers.LOCAL, ""); out.reset();
        new Setup(new BufferedReader(new StringReader("n\nn\n\n")), new PrintStream(out, true), new SetupTest.FakeProbe("", List.of(), false), withSsh, false).helpersStep();
        assertTrue(onHost.isEmpty());
        assertTrue(out.toString().contains("A machine that serves them?"), out.toString());
        // found there: said, used, and nothing asked
        Config.set(Helpers.LOCAL, ""); out.reset(); Helpers.forget();
        Helpers.LOOK = b -> new Helpers.Look(Helpers.Look.FOUND, b, Map.of("metricx", "google/metricx-24-hybrid-large-v2p6 on cuda"), false, false);
        new Setup(new BufferedReader(new StringReader("")), new PrintStream(out, true), new SetupTest.FakeProbe("", List.of(), false), withSsh, false).helpersStep();
        assertTrue(out.toString().contains("The machine that serves your model serves the heavy helpers too (http://homebox:8217): they are used from there, and none runs here (local_helpers off)."), out.toString());
        // there and needing its secret: the line, and the secret asked
        Config.set(Helpers.LOCAL, ""); out.reset(); Helpers.forget();
        Helpers.LOOK = b -> Config.get(Helpers.SECRET_NAME, "").isBlank() ? new Helpers.Look(Helpers.Look.NEEDS_SECRET, b, null, true, false) : new Helpers.Look(Helpers.Look.FOUND, b, Map.of("metricx", "x"), true, false);
        new Setup(new BufferedReader(new StringReader("the words\n")), new PrintStream(out, true), new SetupTest.FakeProbe("", List.of(), false), withSsh, false).helpersStep();
        assertTrue(out.toString().contains("needs its secret: researchzosho helpers secret prints it there, and researchzosho settings set helpers_secret <the secret> sets it here."), out.toString());
        assertEquals("the words", Config.get(Helpers.SECRET_NAME));
        assertTrue(out.toString().contains("they are used from there"), out.toString());
    }

    @Test void theSettingsPageTakesOffForTheHelpersAddress() {
        Settings.Setting helpers = Settings.known().stream().filter(s -> s.key().equals(Helpers.HELPERS)).findFirst().orElseThrow();
        assertEquals("", Settings.refuse(helpers, "off"));
        assertTrue(Settings.known().stream().anyMatch(s -> s.key().equals(Helpers.GPU_NAME)));
        assertEquals("off", System.getProperty("researchzosho.helpers.find"), "the suite keeps the look off the network (build.gradle.kts)");
    }
}
