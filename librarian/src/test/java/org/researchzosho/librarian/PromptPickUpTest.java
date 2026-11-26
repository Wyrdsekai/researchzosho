package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A job filed through any door of the daemon starts at once, not at its workers' next look at the ledger (up to ten seconds later): a
 * translation asked by a route of the HTTP API, by a tool over MCP, by a turn of the web chat, by the Translate page's form. What a door takes up is what the look
 * takes up: the same pick-up ({@code Jobs.pickUp}, the look's own {@code pickUpFiled}), so what holds a run at the look holds it here:
 * the person's pause and hours, a run waiting for the person's yes, a run a cap stopped, the nightly tasks on the first worker only. Two
 * doors picking up at once start no job twice, and put none in the line twice. Every wait here is for a job's state, never for a time.
 */
class PromptPickUpTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    String realHome;
    LibrarianDaemon d;
    Jobs started;

    @AfterEach void down() throws Exception {
        try {
            if (d != null) d.stop();
            if (started != null) started.stop();
        } finally {
            Jobs.CLAIMING = id -> { };
            Jobs.DEFERRED = (id, why) -> { };
            Config.set(ResearchSettings.PAUSE, "");
            Config.set(ResearchSettings.WINDOW, "");
            TranslationScores.SCORERS = TranslationScores::configured;
            Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; };
            Translation.SEATS = Translation::seatsFor; Translation.DRIVE_ANSWERS = Crews::driveAnswers; Translation.ANY_MODEL = Translation::anyModelNow;
            if (realHome != null) { System.setProperty("user.home", realHome); Config.invalidate(); }
        }
    }

    static void until(String what, BooleanSupplier is) throws InterruptedException {
        long end = System.currentTimeMillis() + 60_000;
        while (!is.getAsBoolean()) { assertTrue(System.currentTimeMillis() < end, "within a minute: " + what); Thread.sleep(20); }
    }

    static String state(LibraryStore store, String id) {
        try { ObjectNode j = new Jobs(store, x -> "").get(id); return j == null ? "" : j.path("state").asText(); } catch (Exception e) { return ""; }
    }

    static boolean ended(LibraryStore store, String id) { String s = state(store, id); return s.equals("done") || s.equals("failed") || s.equals("stopped"); }

    // ---- the three doors, through a daemon that runs what is filed, as the service does ----

    /** A daemon on a library of the test's own, the translation's model and scorers stood in (as TranslationNotesParityTest has them), and a program's token. */
    LibraryStore daemon(Path tmp) throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString()); Config.invalidate();
        TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null); Translation.SETUP_SAID = () -> "";
        Translation.SEATS = (drive, model, judge, back) -> TranslationTest.seats(TranslationNotesTest.dropping());
        Translation.DRIVE_ANSWERS = url -> true; Translation.ANY_MODEL = () -> true;
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zPickUp", "a program at homebox", Patrons.Level.write);
        d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        return store;
    }

    String token(LibraryStore store) throws Exception { return Patrons.issueToken(store, "did:key:zPickUp"); }

    String shelved(LibraryStore store, Path tmp, String name) throws Exception {
        Path f = TranslationNotesTest.clock(tmp.resolve("docs").resolve(name));
        return Corpus.addFile(store, Files.move(f, f.resolveSibling(name + ".md")), "", false).getFileName().toString();
    }

    JsonNode post(String path, String token, ObjectNode body) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(d.url() + path)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return M.readTree(r.body());
    }

    /**
     * Whether the daemon holds the library's one job at this moment: in its line, in a worker's hand, running, or past running by its
     * record. The line is read first: a job leaves the line before its record says it ran, never after.
     */
    static boolean takenUp(LibrarianDaemon d, LibraryStore store, String id) {
        Jobs.Line l = d.jobs().line();
        if (l.running() + l.waiting() + l.held() > 0) return true;
        String s = state(store, id);
        return !s.isEmpty() && !s.equals("queued");
    }

    /** Waits for the job to end, and says it ran to its end. */
    static void ran(LibraryStore store, String id) throws Exception {
        until(id + " ends", () -> ended(store, id));
        assertEquals("done", state(store, id), String.valueOf(new Jobs(store, x -> "").get(id)));
    }

    @Test
    void aTranslationAskedByARouteOfTheApiIsTakenUpBeforeTheAnswer(@TempDir Path tmp) throws Exception {
        LibraryStore store = daemon(tmp);
        String raw = shelved(store, tmp, "route"), token = token(store);
        JsonNode r = post("/v1/translate", token, M.createObjectNode().put("raw", raw).put("to", "ja").put("quality", "checked"));
        String id = r.path("job_id").asText();
        assertTrue(id.startsWith("J-"), r.toString());
        assertTrue(takenUp(d, store, id), "when POST /v1/translate has answered, the daemon has the job in hand, not only the ledger: " + d.jobs().line() + " " + state(store, id));
        ran(store, id);
    }

    @Test
    void aTranslationAskedByAToolOverMcpIsTakenUpBeforeTheAnswer(@TempDir Path tmp) throws Exception {
        LibraryStore store = daemon(tmp);
        String raw = shelved(store, tmp, "mcp"), token = token(store);
        ObjectNode call = M.createObjectNode().put("jsonrpc", "2.0").put("id", 7).put("method", "tools/call");
        call.putObject("params").put("name", "library_translate").putObject("arguments").put("raw", raw).put("to", "ja").put("quality", "checked");
        JsonNode env = post("/rpc", token, call);
        String id = env.path("result").path("structuredContent").path("job_id").asText();
        assertTrue(id.startsWith("J-"), env.toString());
        assertTrue(takenUp(d, store, id), "when /rpc has answered tools/call, the daemon has the job in hand: " + d.jobs().line() + " " + state(store, id));
        ran(store, id);
    }

    @Test
    void aTranslationAskedInATurnOfTheWebChatIsTakenUpBeforeTheAnswer(@TempDir Path tmp) throws Exception {
        LibraryStore store = daemon(tmp);
        Librarian.Session s = Librarian.Session.open(store);
        Path f = TranslationNotesTest.clock(tmp.resolve("docs").resolve("chat"));
        HttpResponse<String> r = ChatAttachmentTest.post(http, d.url() + "/chat", "----rzpickup", ChatAttachmentTest.multipart("----rzpickup", s.id, "translate this file into Japanese, checked, no notes", "chat.md", Files.readString(f, StandardCharsets.UTF_8)), null);
        assertEquals(200, r.statusCode(), r.body());
        Matcher m = Pattern.compile("Translating it now, checked \\((J-\\d+)\\)").matcher(r.body());
        assertTrue(m.find(), r.body());
        String id = m.group(1);
        assertTrue(takenUp(d, store, id), "when the chat's turn has answered, the daemon has the job in hand: " + d.jobs().line() + " " + state(store, id));
        ran(store, id);
    }

    @Test
    void aTranslationAskedByTheTranslateFormIsTakenUpBeforeTheAnswer(@TempDir Path tmp) throws Exception {
        LibraryStore store = daemon(tmp);
        Path f = TranslationNotesTest.clock(tmp.resolve("docs").resolve("form"));
        String boundary = "----rzpickupform";
        StringBuilder b = new StringBuilder();
        for (String[] field : new String[][]{{"to", "ja"}, {"quality", "checked"}}) b.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(field[0]).append("\"\r\n\r\n").append(field[1]).append("\r\n");
        b.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"form.md\"\r\nContent-Type: text/markdown\r\n\r\n").append(Files.readString(f, StandardCharsets.UTF_8)).append("\r\n--").append(boundary).append("--\r\n");
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(d.url() + "/translate")).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        Matcher m = Pattern.compile("href=\"/jobs/(J-\\d+)\"").matcher(r.body());
        assertTrue(m.find(), r.body());
        String id = m.group(1);
        assertTrue(takenUp(d, store, id), "when the Translate form has answered, the daemon has the job in hand: " + d.jobs().line() + " " + state(store, id));
        ran(store, id);
    }

    // ---- what holds a run at the look holds it at a door's pick-up: a ledger and its workers, the runner a stand-in ----

    final Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
    final Set<String> claimed = ConcurrentHashMap.newKeySet();
    final List<String> deferred = Collections.synchronizedList(new ArrayList<>());

    /** A ledger with {@code workers} workers whose runner counts each run and ends it at once; the other ledger files, as a door's call files. */
    Jobs workers(LibraryStore store, int workers) throws Exception {
        Jobs.CLAIMING = claimed::add;
        Jobs.DEFERRED = (id, why) -> deferred.add(id + " " + why);
        started = new Jobs(store, (j, drive) -> { runs.computeIfAbsent(j.path("job_id").asText(), k -> new AtomicInteger()).incrementAndGet(); return "ran"; }, List.of(""), workers);
        started.start();
        return started;
    }

    static Jobs filing(LibraryStore store) { return new Jobs(store, j -> { throw new IllegalStateException("filed only"); }); }

    static ObjectNode ask(String q) { return M.createObjectNode().put("question", q); }

    LibraryStore library(Path tmp) throws Exception { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; }

    /** The research run filed first, a translation after it: both picked up at once; the translation ran, so the run ahead of it was looked at. */
    void heldWhileTheNextRuns(LibraryStore store, String why) throws Exception {
        Jobs jobs = workers(store, 2);
        String research = filing(store).submit("research", "did:key:zPerson", ask("Why did the ferry close?"));
        String translation = filing(store).submit(Translation.JOB_KIND, "did:key:zPerson", M.createObjectNode().put("to", "ja"));
        jobs.pickUp();   // a door's pick-up, as a request makes it when it answers
        until("the worker looked at the run and put it back (" + why + "), and ran the translation behind it", () -> deferred.contains(research + " held") && ended(store, translation));
        assertEquals("done", state(store, translation), "the job the hold does not touch ran");
        assertEquals("queued", state(store, research), why + ": the run waits");
        assertFalse(claimed.contains(research) || runs.containsKey(research) || jobs.running().contains(research), why + ": it never reached its claim, and nothing ran it");
    }

    @Test
    void thePersonsPauseHoldsARunThePickUpTakes(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        Config.set(ResearchSettings.PAUSE, "on");
        heldWhileTheNextRuns(store, "research paused");
    }

    @Test
    void theResearchHoursHoldARunThePickUpTakes(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        LocalTime opens = LocalTime.now().plusHours(2), closes = opens.plusHours(1);   // two hours from now, for an hour: closed now, whatever the hour
        Config.set(ResearchSettings.WINDOW, String.format("%02d:%02d-%02d:%02d", opens.getHour(), opens.getMinute(), closes.getHour(), closes.getMinute()));
        assertFalse(ResearchSettings.openNow(), "the instrument: the hours are closed now");
        heldWhileTheNextRuns(store, "outside the research hours");
    }

    @Test
    void aRunWaitingForThePersonsYesAndARunACapStoppedAreLeftAsTheyAre(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        Jobs jobs = workers(store, 2);
        String offered = filing(store).submitOffered("research", "did:key:zPerson", ask("Who built the ferry?"), new Jobs.Offer(null, true, List.of()), System.currentTimeMillis() + 3_600_000L);
        String capped = filing(store).submit("research", "did:key:zPerson", ask("What did the ferry cost?"));
        // as a cap leaves a run it stopped: waiting as capped, with the sentence, until the person resumes it (Jobs.capStopped)
        Path record = jobs.activeDir().resolve(capped + ".json");
        ObjectNode c = (ObjectNode) M.readTree(Files.readString(record, StandardCharsets.UTF_8));
        c.put("state", Jobs.CAPPED); c.put("waiting", "the month's cap is reached");
        Files.writeString(record, M.writeValueAsString(c), StandardCharsets.UTF_8);
        String control = filing(store).submit(Translation.JOB_KIND, "did:key:zPerson", M.createObjectNode().put("to", "ja"));
        jobs.pickUp();
        jobs.pickUp();   // and another door's, after it
        until("the control job filed after them ran", () -> ended(store, control));
        assertEquals("done", state(store, control));
        assertEquals(Jobs.OFFERED, state(store, offered), "a run that waits for the person's yes still waits");
        assertEquals(Jobs.CAPPED, state(store, capped), "a run a cap stopped still waits for the person to resume it");
        for (String id : List.of(offered, capped)) assertFalse(claimed.contains(id) || runs.containsKey(id) || deferred.stream().anyMatch(x -> x.startsWith(id + " ")), id + " was never in the line");
        assertEquals(0, jobs.queued(), "nothing left in the line");
    }

    @Test
    void theNightlyTasksRunOnTheFirstWorkerOnlyWhenTheyArePickedUpTogether(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger going = new AtomicInteger(), most = new AtomicInteger();
        Jobs.CLAIMING = claimed::add;
        Jobs.DEFERRED = (id, why) -> deferred.add(id + " " + why);
        started = new Jobs(store, (j, drive) -> {
            most.accumulateAndGet(going.incrementAndGet(), Math::max);
            try { release.await(60, TimeUnit.SECONDS); } finally { going.decrementAndGet(); }
            runs.computeIfAbsent(j.path("job_id").asText(), k -> new AtomicInteger()).incrementAndGet();
            return "ran";
        }, List.of(""), 2);
        started.start();
        String a = filing(store).submit("crews", "did:key:zPerson", M.createObjectNode()), b = filing(store).submit("crews", "did:key:zPerson", M.createObjectNode());
        started.pickUp();
        until("one night's tasks run and the other is put back by the second worker", () -> going.get() == 1 && (deferred.contains(a + " crews") || deferred.contains(b + " crews")));
        assertEquals(1, started.running().size(), "one at a time: " + started.running());
        release.countDown();
        until("both ran", () -> ended(store, a) && ended(store, b));
        assertEquals(1, most.get(), "never two at once");
        assertEquals(1, runs.get(a).get()); assertEquals(1, runs.get(b).get());
    }

    // ---- two doors at once ----

    @Test
    void aPickUpWhileAWorkerHasTheJobInHandPutsItInTheLineNoSecondTime(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        Jobs jobs = workers(store, 1);
        List<Integer> lineThen = Collections.synchronizedList(new ArrayList<>());
        Jobs.CLAIMING = id -> { jobs.pickUp(); lineThen.add(jobs.queued()); claimed.add(id); };   // a request's pick-up between the worker's look and its claim
        String id = filing(store).submit(Translation.JOB_KIND, "did:key:zPerson", M.createObjectNode().put("to", "ja"));
        jobs.pickUp();
        until(id + " ran", () -> ended(store, id));
        assertEquals(List.of(0), lineThen, "while the worker had it in hand, the pick-up left the line empty");
        assertEquals(1, runs.get(id).get(), "it ran once");
        assertEquals(0, jobs.queued());
    }

    @Test
    void manyPickUpsAtOnceStartEachJobOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        Jobs jobs = workers(store, 2);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) ids.add(filing(store).submit(Translation.JOB_KIND, "did:key:zPerson", M.createObjectNode().put("to", "ja").put("n", i)));
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> doors = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread door = new Thread(() -> { try { go.await(); for (int k = 0; k < 25; k++) jobs.pickUp(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }, "door-" + t);
            door.start(); doors.add(door);
        }
        go.countDown();
        for (Thread door : doors) door.join(60_000);
        until("every job ran", () -> ids.stream().allMatch(id -> ended(store, id)));
        for (String id : ids) { assertEquals("done", state(store, id), id); assertEquals(1, runs.get(id).get(), id + " ran once"); }
        assertEquals(0, jobs.queued(), "the line is empty: no job was put in it twice");
    }
}
