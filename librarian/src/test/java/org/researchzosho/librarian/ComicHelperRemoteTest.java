package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.Stopping;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The comic helper served by the helpers machine (0.7.6), through its seams: a helpers' front on loopback serving the comic helper, its
 * sessions' processes the stand-in that speaks the helper's lines ({@link ComicStandIn}), and a library that uses it. Every reader's
 * request there and back, the pictures byte for byte as the process here writes them; a small comic run through it the same as through
 * the process here; a stop that reaches it within seconds; two libraries' sessions apart; the cap, beyond which a job waits; the
 * limits the front keeps; a session ended after its idle time and opened again; and what is said, before any work, where that machine
 * does not answer, has not set the helper up, refuses the secret, or is of an earlier release. No Python, no model, no network.
 */
class ComicHelperRemoteTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String SECRET = "words the two machines share";
    String home;
    Supplier<ComicHelper.Reader> reader; BooleanSupplier installed; Supplier<List<String>> command; Duration idle, unused, roomWait, roomEvery, stall, first; IntSupplier most; LongSupplier now; Function<ModelEntries.Entry, Vision.Probe> probe;
    final List<AutoCloseable> closing = new ArrayList<>();

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        Helpers.forget(); ComicHelper.forgetThere();
        reader = ComicHelper.READER; installed = ComicHelper.INSTALLED; command = ComicSessions.COMMAND; idle = ComicSessions.IDLE; unused = ComicSessions.UNUSED; most = ComicSessions.MOST; now = ComicSessions.NOW;
        roomWait = ComicHelper.ROOM_WAIT; roomEvery = ComicHelper.ROOM_EVERY; stall = ComicHelper.STALL; first = ComicHelper.FIRST; probe = LibraryProtocol.VISION_PROBE;
        ComicHelper.ROOM_EVERY = Duration.ofMillis(100);
        LibraryProtocol.VISION_PROBE = e -> new Vision.Probe(true, "red / blue", "it named a red square and a blue one");
    }
    @AfterEach void restore() throws Exception {
        for (AutoCloseable c : closing) c.close();
        System.setProperty("user.home", home); Config.invalidate(); Helpers.forget(); ComicHelper.forgetThere();
        ComicHelper.READER = reader; ComicHelper.INSTALLED = installed; ComicSessions.COMMAND = command; ComicSessions.IDLE = idle; ComicSessions.UNUSED = unused; ComicSessions.MOST = most; ComicSessions.NOW = now;
        ComicHelper.ROOM_WAIT = roomWait; ComicHelper.ROOM_EVERY = roomEvery; ComicHelper.STALL = stall; ComicHelper.FIRST = first; LibraryProtocol.VISION_PROBE = probe;
        ComicHelper.manifestFrom(null);
    }

    /** A helpers' front on loopback that serves the comic helper, its sessions' processes the stand-in with {@code script}: its address. */
    record Front(String base, ComicSessions sessions) { String comic() { return base + "/comic"; } }

    Front front(Path tmp, Path script, String secret) throws IOException {
        ComicSessions.COMMAND = () -> { try { return ComicStandIn.command(tmp, script); } catch (IOException e) { throw new UncheckedIOException(e); } };
        ComicSessions s = new ComicSessions();
        HelperServer h = HelperServer.serve("127.0.0.1", 0, secret, new HelperServer.Backends(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), s), List.of());
        closing.add(0, h);
        return new Front("http://127.0.0.1:" + h.port(), s);
    }

    /** This library uses the front: its address and its secret, set as helpers install --on sets them. */
    static void uses(Front f) throws IOException { Config.set(Helpers.HELPERS, f.base()); Config.set(Helpers.SECRET_NAME, SECRET); Helpers.forget(); ComicHelper.forgetThere(); }

    static ComicHelper.Reader local(Path tmp, Path script) { return ComicPlanTest.standIn(tmp, script); }

    /** Waits until no stand-in process of this test is alive; false after ten seconds. */
    static boolean gone() throws InterruptedException { return ComicHelperProcessTest.gone(); }

    static int closedPort() throws IOException { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }

    /** The files under a folder by their names, each one's bytes. */
    static Map<String, byte[]> files(Path dir) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> all = Files.walk(dir)) { for (Path p : all.filter(Files::isRegularFile).sorted().toList()) out.put(dir.relativize(p).toString(), Files.readAllBytes(p)); }
        return out;
    }

    static void sameFiles(Map<String, byte[]> a, Map<String, byte[]> b, String what) {
        assertEquals(a.keySet(), b.keySet(), what);
        for (String k : a.keySet()) assertArrayEquals(a.get(k), b.get(k), what + ": " + k + " byte for byte");
    }

    /** A reply as it is compared: the files it names by their names alone, wherever each side wrote them. */
    static String plain(JsonNode j, Path a, Path b) { ObjectNode c = j.deepCopy(); c.remove("id"); return c.toString().replace(a.toAbsolutePath().toString(), "<out>").replace(b.toAbsolutePath().toString(), "<out>"); }

    // ---- every request there and back ----

    @Test void everyRequestOfTheReaderGoesThereAndItsPicturesComeBackByteForByte(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("script");
        byte[] one = ComicStandIn.png(ComicStandIn.page(1, 500, 700)), two = ComicStandIn.png(ComicStandIn.page(2, 500, 700));
        Path in = Files.createDirectories(tmp.resolve("in"));
        Path p1 = in.resolve("p1.png"), p2 = in.resolve("p2.png"), strip = in.resolve("ep01.jpg"), notAPicture = in.resolve("p3.png");
        Files.write(p1, one); Files.write(p2, two); Files.write(strip, ComicStandIn.jpg(ComicStandIn.strip(200, 3))); Files.writeString(notAPicture, "this is no picture");
        int[][] b = ComicStandIn.balloons(500, 700);
        ComicStandIn.says(script, one, ComicStandIn.read(500, 700, ComicStandIn.block("b01", "speech", "의사 선생님은 어디에 계세요?", b[0]), ComicStandIn.block("b02", "speech", "수술은 3시부터입니다", b[1])));
        Front f = front(tmp, script, SECRET);
        uses(f);
        ObjectNode texts = M.createObjectNode(); texts.putObject("b01").put("text", "Where is the doctor?").put("lang", "en"); texts.putObject("b02").put("text", "The operation is at 3.").put("lang", "en");
        Path a = tmp.resolve("here"), r = tmp.resolve("there");
        List<String> said = new ArrayList<>();
        for (Path out : List.of(a, r)) {
            try (ComicHelper.Reader h = out == a ? local(tmp, script) : new ComicHelper.Remote(f.comic())) {
                said.add(plain(h.script(List.of(p1, p2)), a, r));
                JsonNode page = h.read(p1, "ko", "ltr");
                said.add(plain(page, a, r));
                said.add(plain(h.cut(p1, out.resolve("cut/p1")), a, r));
                JsonNode cut = h.cut(strip, out.resolve("cut/ep01"));
                said.add(plain(cut, a, r));
                List<ComicHelper.Piece> pieces = new ArrayList<>();
                for (JsonNode piece : cut.path("pieces")) pieces.add(new ComicHelper.Piece(Path.of(piece.path("file").asText()), piece.path("top").asInt()));
                Files.createDirectories(out.resolve("out"));
                h.join(pieces, 200, 900, out.resolve("out/ep01.jpg"));
                said.add(plain(h.set(p1, page, texts, "latin", List.of("Saito"), out.resolve("out/p1.png")), a, r));
                said.add(h.crops(p1, page, List.of("b02", "b01"), out.resolve("crops/p1")).toString().replace(a.toAbsolutePath().toString(), "<out>").replace(r.toAbsolutePath().toString(), "<out>"));
                Files.createDirectories(out.resolve("shown"));
                h.picture(p1, List.of(Map.entry("1", new double[]{300, 90, 425, 265})), out.resolve("shown/p1.jpg"));
                ComicHelper.Failed failed = assertThrows(ComicHelper.Failed.class, () -> h.read(notAPicture));
                said.add(failed.getMessage());
                assertTrue(h.languages().containsAll(List.of("ja", "ko", "en")), "the languages the helper reads come with its answers: " + h.languages());
                assertTrue(h.loaded().startsWith("version stand-in"), h.loaded());
                assertEquals(out == a ? "" : "127.0.0.1", h.where());
            }
        }
        assertTrue(gone(), "both processes end when their readers are closed: the session there is closed, its process asked to quit");
        for (int i = 0; i < said.size() / 2; i++) assertEquals(said.get(i), said.get(i + said.size() / 2), "answer " + i + " is the same through the helpers machine");
        assertFalse(Files.exists(r.resolve("cut/p1")), "an ordinary page is no strip, and nothing is written for it, there as here");
        // what the helper wrote comes back byte for byte: the strip's pieces, the joined strip, the set page, the crops, the numbered picture; and nothing else
        Map<String, byte[]> here = files(a), there = files(r);
        here.keySet().removeIf(k -> k.endsWith(".set.json") || k.endsWith(".join.json") || k.endsWith(".boxes.json"));   // the stand-in's own notes of what it was asked, beside what it wrote: not the helper's pictures
        sameFiles(here, there, "the pictures written through the helpers machine");
        assertEquals(List.of("crops/p1/b01.png", "crops/p1/b02.png", "cut/ep01/ep01_001.png", "cut/ep01/ep01_002.png", "cut/ep01/ep01_003.png", "out/ep01.jpg", "out/p1.png", "shown/p1.jpg"), new ArrayList<>(there.keySet()));
        assertEquals(List.of("script", "read", "cut", "cut", "join", "set", "crops", "picture", "read", "quit"), ComicStandIn.asked(script).subList(0, 10), "the process here was asked these");
        assertEquals(ComicStandIn.asked(script).subList(0, 10), ComicStandIn.asked(script).subList(10, 20), "and the process there the same, one for one");
        // and the files the request carried are gone from that machine when its reply has gone: no page of a library stays there
        try (Stream<Path> left = Files.walk(ComicHelper.dir().resolve("sessions"))) { assertEquals(List.of(), left.filter(Files::isRegularFile).toList()); }
    }

    // ---- a small comic, the same either way ----

    @Test void aSmallComicIsTranslatedThroughTheHelpersMachineAsItIsThroughTheProcessHere(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        Path cbz = ComicTranslationTest.volume(tmp, script);
        Path here = Files.createDirectories(tmp.resolve("a")).resolve("volume.cbz"), there = Files.createDirectories(tmp.resolve("b")).resolve("volume.cbz");
        Files.copy(cbz, here); Files.copy(cbz, there);
        // here: the process of this machine
        ComicHelper.INSTALLED = () -> true;
        ComicHelper.READER = () -> local(tmp, script);
        Translation.Outcome a = Translation.run(store, here, "English", "", "checked", null, null, ComicTranslationTest.seats(ComicTranslationTest.doctor()).withBack(false), p -> { }, () -> false, Checkpoint.NONE, "", "", "", ComicTranslation.Said.NOTHING);
        // there: nothing set up here, the machine set to run no heavy helper, and the helpers machine serving the comic helper
        ComicHelper.INSTALLED = () -> false;
        Config.set(Helpers.LOCAL, "off");
        Front f = front(tmp, script, SECRET);
        uses(f);
        ComicHelper.READER = reader;   // the program's own choice
        assertInstanceOf(ComicHelper.Remote.class, ComicHelper.reader(), "the helpers machine serves it: the reader is the one there");
        Translation.Plan plan = Translation.plan(store, there, "English", "", "checked", "stand-in", "", "", "", "");
        assertEquals(ComicTranslation.READY, plan.comic().helper(), "nothing to set up here, and no question");
        assertEquals("127.0.0.1", plan.comic().helperOn());
        assertEquals("127.0.0.1", plan.comic().json().path("helper_on").asText(), "the API's plan names the machine");
        assertTrue(TranslationWho.seatsSaid(TranslationWho.facts(job(TranslationWho.filed(plan, ""))), Chrome.ENGLISH).startsWith("The pages are read by the comic helper on 127.0.0.1."), TranslationWho.seatsSaid(TranslationWho.facts(job(TranslationWho.filed(plan, ""))), Chrome.ENGLISH));
        Translation.Outcome b = Translation.run(store, there, "English", "", "checked", null, null, ComicTranslationTest.seats(ComicTranslationTest.doctor()).withBack(false), p -> { }, () -> false, Checkpoint.NONE, "", "", "", ComicTranslation.Said.NOTHING);
        assertEquals("127.0.0.1", b.comic().helperOn()); assertEquals("", a.comic().helperOn());
        // the archives the same, page for page and byte for byte
        try (ZipFile za = new ZipFile(a.output().toFile()); ZipFile zb = new ZipFile(b.output().toFile())) {
            assertEquals(za.size(), zb.size());
            for (var e = za.entries(); e.hasMoreElements(); ) { var x = e.nextElement(); assertArrayEquals(za.getInputStream(x).readAllBytes(), zb.getInputStream(zb.getEntry(x.getName())).readAllBytes(), x.getName()); }
        }
        // the notes the same, but for where the file was and the machine that read its pages
        String na = Files.readString(a.note()).replace(here.getParent().toString(), "<dir>").replaceAll("(?m)^- When: .*$", "- When: <when>").replaceAll("\\d{4}-\\d{2}-\\d{2}-[0-9a-f]{12}\\.md", "<entry>"), nb = Files.readString(b.note()).replace(there.getParent().toString(), "<dir>").replaceAll("(?m)^- When: .*$", "- When: <when>").replaceAll("\\d{4}-\\d{2}-\\d{2}-[0-9a-f]{12}\\.md", "<entry>");   // when, and the shelves' entries of each run
        assertTrue(nb.contains("- Pages read by: the comic helper on 127.0.0.1 (version stand-in"), nb);
        List<String> la = na.replace("- Pages read by: the comic helper on this machine (", "- Pages read by: the comic helper on 127.0.0.1 (").lines().toList(), lb = nb.lines().toList();
        for (int i = 0; i < Math.min(la.size(), lb.size()); i++) assertEquals(la.get(i), lb.get(i), "line " + (i + 1) + " of the notes");
        assertEquals(la.size(), lb.size());
        assertEquals(a.translated(), b.translated()); assertEquals(a.comic().set(), b.comic().set());
        assertTrue(gone());
        assertTrue(f.sessions().state().isEmpty(), "the job's session there is closed with the job");
    }

    /** A job as its record holds it when filed: its args with the facts of who does what. */
    static ObjectNode job(ObjectNode who) { ObjectNode j = M.createObjectNode(); ObjectNode a = j.putObject("args"); a.set("who", who); a.put("to", "en"); a.put("quality", "checked"); return j; }

    // ---- the stop, two libraries, the cap, the idle time ----

    @Test void aStopReachesTheHelpersMachineWithinSecondsAndEndsItsProcess(@TempDir Path tmp) throws Exception {
        Path script = Files.createDirectories(tmp.resolve("script"));
        Files.writeString(script.resolve("hang"), "");   // a read that never answers, as a page on a loaded processor
        Path p1 = tmp.resolve("p1.png"); Files.write(p1, ComicStandIn.png(ComicStandIn.page(1, 300, 420)));
        Front f = front(tmp, script, SECRET);
        uses(f);
        AtomicBoolean stop = new AtomicBoolean(); AtomicLong stoppedAt = new AtomicLong();
        Thread asker = new Thread(() -> {
            long until = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < until && !Files.exists(script.resolve("requests.log"))) { try { Thread.sleep(50); } catch (InterruptedException e) { return; } }
            try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            stoppedAt.set(System.currentTimeMillis()); stop.set(true);
        });
        asker.setDaemon(true); asker.start();
        try (ComicHelper.Remote h = new ComicHelper.Remote(f.comic())) {
            assertTimeoutPreemptively(Duration.ofSeconds(90), () -> assertThrows(Stopping.Requested.class, () -> Stopping.within(stop::get, () -> { try { return h.read(p1); } catch (IOException e) { throw new UncheckedIOException(e); } })));
            long took = System.currentTimeMillis() - stoppedAt.get();
            assertTrue(stoppedAt.get() > 0 && took < 5_000, "the stop took " + took + " ms here");
            assertTrue(f.sessions().state().isEmpty(), "the session there is ended by the stop");
            assertTrue(gone(), "and the helper's process there with it, not left reading");
            assertTrue(System.currentTimeMillis() - stoppedAt.get() < 15_000, "within seconds of the stop");
            assertTrue(assertThrows(IOException.class, () -> h.read(p1)).getMessage().contains("was stopped"), "nothing more is asked after a stop");
        }
    }

    @Test void twoLibrariesHaveTwoSessionsAndNeitherReachesTheOthers(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("script");
        byte[] one = ComicStandIn.png(ComicStandIn.page(1, 500, 700)), two = ComicStandIn.png(ComicStandIn.page(2, 500, 700));
        Path p1 = tmp.resolve("p1.png"), p2 = tmp.resolve("p2.png"); Files.write(p1, one); Files.write(p2, two);
        int[][] b = ComicStandIn.balloons(500, 700);
        ComicStandIn.says(script, one, ComicStandIn.read(500, 700, ComicStandIn.block("b01", "speech", "the first library's page", b[0])));
        ComicStandIn.says(script, two, ComicStandIn.read(500, 700, ComicStandIn.block("b01", "speech", "the second library's page", b[0])));
        ComicSessions.MOST = () -> 2;
        Front f = front(tmp, script, SECRET);
        uses(f);
        try (ComicHelper.Remote first = new ComicHelper.Remote(f.comic()); ComicHelper.Remote second = new ComicHelper.Remote(f.comic())) {
            assertEquals("the first library's page", first.read(p1, "en").path("blocks").path(0).path("text").asText());
            assertEquals("the second library's page", second.read(p2, "en").path("blocks").path(0).path("text").asText());
            assertEquals(2, f.sessions().state().size(), "a session each");
            assertEquals(2, ComicStandIn.running().size(), "a process each: nothing one library's process holds is the other's");
            List<String> ids = new ArrayList<>(f.sessions().state().keySet());
            assertNotEquals(ids.get(0), ids.get(1));
            for (String id : ids) assertTrue(id.matches("[0-9a-f]{48}"), "24 random bytes, told only to the library that opened it");
            // a session's name nobody was told reaches nothing, and a name that is no session's is answered "gone"
            HttpResponse<String> guessed = post(f.comic() + "/" + "0".repeat(48) + "/read", SECRET, "{}\n");
            assertEquals(404, guessed.statusCode()); assertTrue(guessed.body().contains("\"gone\":true"), guessed.body());
            HttpResponse<String> path = post(f.comic() + "/../" + ids.get(0) + "/read", SECRET, "{}\n");
            assertNotEquals(200, path.statusCode());
            // nothing of either page stays there between requests
            try (Stream<Path> left = Files.walk(ComicHelper.dir().resolve("sessions"))) { assertEquals(List.of(), left.filter(Files::isRegularFile).toList()); }
        }
        assertTrue(gone());
    }

    @Test void beyondTheMostItReadsAtOnceAJobWaitsForRoomAndGoesOnWhenThereIs(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("script");
        Path p1 = tmp.resolve("p1.png"); Files.write(p1, ComicStandIn.png(ComicStandIn.page(1, 300, 420)));
        ComicSessions.MOST = () -> 1;
        Front f = front(tmp, script, SECRET);
        uses(f);
        assertEquals(1, f.sessions().most());
        HttpResponse<String> health = get(f.base() + "/health", SECRET);
        assertTrue(health.body().contains("\"comic\":\"the comic helper on cpu, one comic at once (4 threads each)"), "listed in the front's health with what it is: " + health.body());
        ComicHelper.Remote first = new ComicHelper.Remote(f.comic());
        first.read(p1);
        AtomicReference<String> second = new AtomicReference<>();
        AtomicLong doneAt = new AtomicLong();
        Thread waiting = new Thread(() -> { try (ComicHelper.Remote r = new ComicHelper.Remote(f.comic())) { second.set(r.read(p1).path("blocks").path(0).path("text").asText()); doneAt.set(System.currentTimeMillis()); } catch (IOException e) { second.set("failed: " + e.getMessage()); } });
        waiting.setDaemon(true); waiting.start();
        Thread.sleep(1_500);
        assertNull(second.get(), "the second job waits while the first holds the one session");
        assertEquals(1, f.sessions().state().size());
        long closedAt = System.currentTimeMillis();
        first.close();
        waiting.join(30_000);
        assertEquals("こんにちは", second.get(), "and goes on when there is room");
        assertTrue(doneAt.get() - closedAt < 10_000, "within seconds of the room");
        // one that waits longer than a job waits is told so, plainly
        ComicHelper.ROOM_WAIT = Duration.ofMillis(300);
        try (ComicHelper.Remote holder = new ComicHelper.Remote(f.comic()); ComicHelper.Remote late = new ComicHelper.Remote(f.comic())) {
            holder.read(p1);
            IOException e = assertThrows(IOException.class, () -> late.read(p1));
            assertEquals("The comic helper on 127.0.0.1 was reading 1 other comic, as many as it reads at once, for 1 second: send this comic again when they are done.", e.getMessage());
        }
        assertTrue(gone());
    }

    @Test void aSessionEndedAfterItsIdleTimeIsOpenedAgainForTheJobsNextRequest(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("script");
        Path p1 = tmp.resolve("p1.png"); Files.write(p1, ComicStandIn.png(ComicStandIn.page(1, 300, 420)));
        long[] clock = {1_000_000};
        ComicSessions.NOW = () -> clock[0];
        Front f = front(tmp, script, SECRET);
        uses(f);
        try (ComicHelper.Remote h = new ComicHelper.Remote(f.comic())) {
            h.read(p1);
            String id = f.sessions().state().keySet().iterator().next();
            clock[0] += ComicSessions.IDLE.toMillis() - 1_000; f.sessions().reap();
            assertEquals(List.of(id), new ArrayList<>(f.sessions().state().keySet()), "a session used within its idle time stays");
            clock[0] += 2_000; f.sessions().reap();
            assertTrue(f.sessions().state().isEmpty(), "ended after ten minutes without a request");
            assertTrue(gone(), "its process with it: the machine's memory is free again");
            assertEquals("こんにちは", h.read(p1).path("blocks").path(0).path("text").asText(), "the job's next request opens another and is answered");
            assertEquals(1, f.sessions().state().size());
            assertNotEquals(id, f.sessions().state().keySet().iterator().next());
        }
        // one opened and never asked anything ends sooner
        HttpResponse<String> opened = post(f.comic() + "/open", SECRET, "");
        assertEquals(200, opened.statusCode());
        clock[0] += ComicSessions.UNUSED.toMillis() + 1; f.sessions().reap();
        assertTrue(f.sessions().state().isEmpty());
    }

    // ---- the limits, the secret ----

    @Test void theFrontKeepsItsLimitsAndTheLibrarySendsNothingPastThem(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("script");
        Front f = front(tmp, script, SECRET);
        uses(f);
        String id = M.readTree(post(f.comic() + "/open", SECRET, "").body()).path("session").asText();
        String at = f.comic() + "/" + id + "/read";
        HttpResponse<String> big = post(at, SECRET, "{\"files\":[{\"name\":\"p1.png\",\"size\":" + (ComicWire.PICTURE_MOST + 1) + "}]}\n");
        assertEquals(413, big.statusCode()); assertTrue(big.body().contains("the picture p1.png is 65 MB, more than the 64 MB a picture taken"), big.body());
        StringBuilder many = new StringBuilder("{\"files\":[");
        for (int i = 0; i <= ComicWire.FILES_MOST; i++) many.append(i == 0 ? "" : ",").append("{\"name\":\"p").append(i).append(".png\",\"size\":1}");
        HttpResponse<String> tooMany = post(f.comic() + "/" + id + "/script", SECRET, many + "]}\n");
        assertEquals(413, tooMany.statusCode()); assertTrue(tooMany.body().contains("501 pictures in one request is more than the 500 taken"), tooMany.body());
        StringBuilder together = new StringBuilder("{\"files\":[");
        for (int i = 0; i < 5; i++) together.append(i == 0 ? "" : ",").append("{\"name\":\"s").append(i).append(".png\",\"size\":").append(60L << 20).append("}");
        HttpResponse<String> all = post(f.comic() + "/" + id + "/join", SECRET, together + "]}\n");
        assertEquals(413, all.statusCode()); assertTrue(all.body().contains("the pictures of the request are 300 MB together, more than the 256 MB taken at once"), all.body());
        HttpResponse<String> named = post(at, SECRET, "{\"files\":[{\"name\":\"../../evil.png\",\"size\":1}]}\nx");
        assertEquals(400, named.statusCode()); assertTrue(named.body().contains("a name that is not taken"), named.body());
        HttpResponse<String> line = post(at, SECRET, "{\"x\":\"" + "a".repeat((int) ComicWire.LINE_MOST) + "\"}\n");
        assertEquals(413, line.statusCode()); assertTrue(line.body().contains("longer than 16 MB"), line.body());
        HttpResponse<String> shortBody = post(at, SECRET, "{\"files\":[{\"name\":\"p1.png\",\"size\":100}]}\nonly some bytes");
        assertEquals(400, shortBody.statusCode()); assertTrue(shortBody.body().contains("the request ended inside its file p1.png"), shortBody.body());
        assertTrue(ComicStandIn.asked(script).isEmpty(), "nothing past a limit reached the helper");
        try (Stream<Path> left = Files.walk(ComicHelper.dir().resolve("sessions"))) { assertEquals(List.of(), left.filter(Files::isRegularFile).toList(), "and nothing of it is left on the disk"); }
        // the library holds itself to the same: a picture past the limit is that page's failure, said, and never sent
        Path huge = tmp.resolve("huge.png");
        try (RandomAccessFile r = new RandomAccessFile(huge.toFile(), "rw")) { r.setLength(ComicWire.PICTURE_MOST + 1); }
        try (ComicHelper.Remote h = new ComicHelper.Remote(f.comic())) {
            ComicHelper.Failed e = assertThrows(ComicHelper.Failed.class, () -> h.read(huge));
            assertEquals("the picture huge.png is 65 MB, more than the 64 MB a picture the comic helper on 127.0.0.1 takes", e.getMessage());
        }
        assertTrue(ComicStandIn.asked(script).isEmpty());
    }

    @Test void theSecretIsAskedForAndAWrongOneIsSaidBeforeAnyWork(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("script");
        Front f = front(tmp, script, SECRET);
        HttpResponse<String> bare = post(f.comic() + "/open", null, "");
        assertEquals(401, bare.statusCode(), "every request carries the secret, as every helpers request does");
        assertTrue(f.sessions().state().isEmpty());
        Config.set(Helpers.HELPERS, f.base()); Config.set(Helpers.SECRET_NAME, "other words"); Config.set(Helpers.LOCAL, "off");
        assertEquals(f.comic(), ComicHelper.remote(), "a machine that refuses the secret stays the one named, as for every helper");
        assertEquals("127.0.0.1, the machine that serves this library's comic helper, refuses this library's secret: researchzosho settings set helpers_secret <the secret it serves with> sets it here (researchzosho helpers secret prints it on 127.0.0.1).", ComicHelper.barred(Chrome.ENGLISH));
        assertEquals("at " + f.comic() + ", which refuses this library's secret", ComicHelper.statusLine());
        try (ComicHelper.Remote h = new ComicHelper.Remote(f.comic())) {
            Path p = tmp.resolve("p.png"); Files.write(p, ComicStandIn.png(ComicStandIn.page(1, 300, 420)));
            IOException e = assertThrows(IOException.class, () -> h.read(p));
            assertTrue(e.getMessage().startsWith("127.0.0.1, the machine that serves this library's comic helper, refuses this library's secret"), e.getMessage());
        }
        assertTrue(f.sessions().state().isEmpty() && ComicStandIn.asked(script).isEmpty());
    }

    // ---- what is said, before any work ----

    @Test void aHelpersMachineThatDoesNotAnswerIsSaidInOneSentenceBeforeAnyWork(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path cbz = tmp.resolve("volume.cbz"); ComicStandIn.volume(cbz, 2);
        String dead = "http://127.0.0.1:" + closedPort();
        Config.set(Helpers.HELPERS, dead); Config.set(Helpers.SECRET_NAME, SECRET); Config.set(Helpers.LOCAL, "off");
        ComicHelper.INSTALLED = () -> false;
        String said = "127.0.0.1, the machine that serves this library's comic helper, does not answer now (" + dead + "/comic), so no comic can be read: researchzosho helpers install --on 127.0.0.1 starts its helpers again from here.";
        assertEquals(said, ComicHelper.barred(Chrome.ENGLISH));
        Translation.Refused refused = assertThrows(Translation.Refused.class, () -> Translation.plan(store, cbz, "English", "", "checked", "stand-in", "", "", "", ""));
        assertEquals(said, refused.getMessage(), "the plan says it before any work, and asks nothing about a set-up here");
        assertEquals("at " + dead + "/comic, which does not answer now", ComicHelper.statusLine());
        assertTrue(HelpersCli.statusSaid().contains("\n  the comic helper: at " + dead + "/comic, which does not answer now"), HelpersCli.statusSaid());
        assertFalse(ComicHelper.usable());
        assertEquals("", ComicHelper.setupInBackground(), "nothing is set up here in the background");
        int[] rc = {0};
        String out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "install"}), false);
        assertEquals(1, rc[0]); assertEquals(said + "\n", out);
        assertFalse(out.contains("Set the comic helper up now?") || out.contains("Setting it up fetches"), "never a question about fetching onto a machine that runs no heavy helper");
        assertFalse(Files.exists(ComicHelper.dir()), "and nothing of it is made here");
    }

    @Test void aHelpersMachineWhoseComicHelperIsNotSetUpOrThatIsOfAnEarlierReleaseIsSaidWithTheCommand(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path cbz = tmp.resolve("volume.cbz"); ComicStandIn.volume(cbz, 2);
        ComicHelper.INSTALLED = () -> false;
        // a set that was installed to serve it, and it is not set up there: listed, as not set up
        HelperServer absent = HelperServer.serve("127.0.0.1", 0, SECRET, new HelperServer.Backends(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(Map.of("comic", HelperServer.NOT_SET_UP))), List.of());
        closing.add(absent);
        String base = "http://127.0.0.1:" + absent.port();
        Config.set(Helpers.HELPERS, base); Config.set(Helpers.SECRET_NAME, SECRET); Config.set(Helpers.LOCAL, "off");
        String notThere = "The comic helper is not set up on 127.0.0.1, the machine that serves this library's heavy helpers: researchzosho helpers install --on 127.0.0.1 sets it up there with the others.";
        assertEquals(notThere, ComicHelper.barred(Chrome.ENGLISH));
        assertEquals(notThere, assertThrows(Translation.Refused.class, () -> Translation.plan(store, cbz, "English", "", "checked", "stand-in", "", "", "", "")).getMessage());
        assertEquals("at " + base + "/comic, where it is not set up (researchzosho helpers install --on 127.0.0.1 sets it up)", ComicHelper.statusLine());
        int[] rc = {0};
        String status = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "status"}), false);
        assertEquals(1, rc[0]); assertEquals(notThere + "\n", status);
        // a helpers machine of an earlier release: it serves the others and lists no comic helper
        HelperServer older = HelperServer.serve("127.0.0.1", 0, SECRET, Map.of("labse", "http://127.0.0.1:" + closedPort()), List.of());
        closing.add(older);
        Config.set(Helpers.HELPERS, "http://127.0.0.1:" + older.port()); Helpers.forget(); ComicHelper.forgetThere();
        assertNull(ComicHelper.remote(), "it does not say it serves the comic helper: none is named there");
        assertEquals("This machine is set to run no heavy helper (local_helpers off), and 127.0.0.1, which serves this library's heavy helpers, does not serve the comic helper (an earlier release there, or one set up without it): researchzosho helpers install --on 127.0.0.1 sets it up there.", ComicHelper.barred(Chrome.ENGLISH));
        assertTrue(ComicHelper.statusLine().startsWith("not run here (local_helpers off), and 127.0.0.1 does not serve it"), ComicHelper.statusLine());
        // and no machine at all
        Config.set(Helpers.HELPERS, "off"); Helpers.forget();
        assertEquals("This machine is set to run no heavy helper (local_helpers off), and no machine serves this library the comic helper: researchzosho helpers install --on <user@host> sets it up on a machine of yours with the other helpers, or researchzosho settings set local_helpers on allows it here.", ComicHelper.barred(Chrome.ENGLISH));
        assertEquals("not run here (local_helpers off), and no machine serves it to this library", ComicHelper.statusLine());
        // a machine that may run its own, with no helpers machine: its own, exactly as before
        Config.set(Helpers.LOCAL, "");
        assertNull(ComicHelper.remote()); assertEquals("", ComicHelper.barred(Chrome.ENGLISH));
        assertEquals("on this machine, not set up yet (researchzosho translate comics install)", ComicHelper.statusLine());
        assertTrue(ComicHelper.reader() instanceof ComicHelper.Local);
    }

    @Test void aHelperServedThereIsSaidWhereItIsByTheStatusLinesAndInstallSaysTheCommand(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        // the readers set up there, as the front's line says what it reads
        for (ComicHelper.Item i : ComicHelper.manifest()) for (String p : i.paths()) { Path f = ComicHelper.models().resolve(p); Files.createDirectories(f.getParent()); Files.writeString(f, ""); }
        Front f = front(tmp, script, SECRET);
        uses(f);
        Config.set(Helpers.LOCAL, "off");
        String line = "the comic helper on cpu, " + (f.sessions().most() == 1 ? "one comic" : f.sessions().most() + " comics") + " at once (4 threads each); it reads Japanese, Korean, Chinese and the Latin, Cyrillic and Greek alphabets";
        assertEquals(line, f.sessions().line());
        assertEquals("", ComicHelper.barred(Chrome.ENGLISH));
        assertTrue(ComicHelper.usable());
        assertEquals("at " + f.comic() + " (" + line + ")", ComicHelper.statusLine());
        assertTrue(HelpersCli.statusSaid().contains("\n  the comic helper: at " + f.comic() + " (" + line + ")"), HelpersCli.statusSaid());
        assertEquals("The comic helper of this library is served by 127.0.0.1, at " + f.comic() + " (" + line + "): a comic's pages are read and lettered there, and nothing of it is set up on this machine. researchzosho translate comics status, run on 127.0.0.1, says how it stands there.", ComicHelper.status());
        int[] rc = {0};
        String out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "install"}), false);
        assertEquals(0, rc[0]);
        assertTrue(out.endsWith("The comic helper of this library is served by 127.0.0.1 (" + f.comic() + "), where it is set up: nothing of it is set up on this machine. researchzosho helpers install --on 127.0.0.1 brings it to this release there with the other helpers.\n"), out);
        String status = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "status"}), false);
        assertEquals(0, rc[0]); assertEquals(ComicHelper.status() + "\n", status);
        assertEquals("", ComicHelper.setupInBackground());
        // a page the helper there cannot read because a piece of it does not load names that machine's command, never this one's
        ComicHelper.Failed unloadable = new ComicHelper.Failed("the reader's file is not as it was fetched", "Unloadable", "127.0.0.1");
        assertEquals("The comic helper on 127.0.0.1 cannot read this comic: the reader's file is not as it was fetched. researchzosho translate comics install, run on 127.0.0.1, loads every piece of the helper there once and puts right what does not load; then send the comic again.", ComicTranslation.unloadableSaid(unloadable));
        // the who-does-what line in every language the program has it in
        ObjectNode facts = M.createObjectNode(); facts.put("pages", TranslationWho.COMIC_HELPER); facts.put("pages_on", "homebox"); facts.putObject("translator").put("model", "m");
        assertTrue(TranslationWho.seatsSaid(facts, Chrome.ENGLISH).startsWith("The pages are read by the comic helper on homebox."));
        facts.put("second_reading", true);
        assertTrue(TranslationWho.seatsSaid(facts, Chrome.ENGLISH).startsWith("The pages are read by the comic helper on homebox, and the translator reads the lettering a second time."));
    }

    // ---- raw requests ----

    static HttpResponse<String> post(String url, String secret, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Content-Type", ComicWire.TYPE).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (secret != null) b.header("Authorization", "Bearer " + secret);
        try { return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (IOException e) { throw new IOException(url + ": " + e.getMessage(), e); }
    }

    static HttpResponse<String> get(String url, String secret) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET();
        if (secret != null) b.header("Authorization", "Bearer " + secret);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
