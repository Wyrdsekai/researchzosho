package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A job runs once (0.7.6). `researchzosho translate` files its job with the library's service and waits for it; a job left queued a minute
 * it ran itself. After a service restart, the job the service put back in line to resume was queued again a minute after it was filed, and
 * the waiting command ran it while the restarted service resumed it: two runs, two archives, a paid model charged twice (found on a real
 * comic, 2026-11-14). Now the command runs a job only where no live process can be running it: it waits while a service holds the
 * library's lease, and takes the lease, and the job under the job's own lock, where none does. A worker claims a queued job under the same
 * lock, so that a claim and a stop from another process are one after the other, never both.
 */
class TranslationWaiterTest {
    String home; long poll, takeOver; Translation.SeatMaker seats; Predicate<String> answers; Consumer<String> claiming;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        poll = TranslateCli.WAIT_POLL_MS; takeOver = TranslateCli.TAKE_OVER_MS; seats = Translation.SEATS; answers = Translation.DRIVE_ANSWERS; claiming = Jobs.CLAIMING;
        TranslateCli.WAIT_POLL_MS = 50; TranslateCli.TAKE_OVER_MS = 300;
        Translation.SEATS = (drive, model, judge, back) -> TranslationTest.seats(new TranslationTest.Script()).withBack(false);
        Translation.DRIVE_ANSWERS = d -> true;
    }
    @AfterEach void restore() {
        System.setProperty("user.home", home); Config.invalidate();
        TranslateCli.WAIT_POLL_MS = poll; TranslateCli.TAKE_OVER_MS = takeOver; Translation.SEATS = seats; Translation.DRIVE_ANSWERS = answers; Jobs.CLAIMING = claiming;
    }

    /** A library a running process says it serves: its pid recorded in the library, as a daemon records it; the command files its job there. */
    static LibraryStore served(Path tmp) throws IOException {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path pid = Service.pidFileIn(store.root());
        Files.createDirectories(pid.getParent());
        Files.writeString(pid, ProcessHandle.current().pid() + "\n", StandardCharsets.UTF_8);
        return store;
    }

    /** The translations written beside the document: its own name with the language before the ending, and the second run's "-2". */
    static List<String> outputs(Path dir) throws IOException { try (Stream<Path> s = Files.list(dir)) { return s.map(p -> p.getFileName().toString()).filter(n -> n.matches(".*\\.ja(-\\d+)?\\.md")).sorted().toList(); } }

    /** The command in a thread of its own, its output kept: what it printed and its exit code, when it ends. */
    record Waiting(Thread thread, AtomicReference<String> said, AtomicInteger rc) { }

    static Waiting translate(LibraryStore store, Path doc) {
        AtomicReference<String> said = new AtomicReference<>(""); AtomicInteger rc = new AtomicInteger(-9);
        Thread t = new Thread(() -> {
            AtomicReference<String> err = new AtomicReference<>("");
            try { said.set(TranslationDirectionTest.out(() -> { err.set(TranslationDirectionTest.out(() -> { rc.set(TranslateCli.translate(store, new String[]{"researchzosho", "translate", doc.toString(), "--to", "Japanese", "--quality", "quick"})); return rc.get(); }, true)); return rc.get(); }, false) + err.get()); }
            catch (Exception e) { said.set("failed: " + e); }
        }, "waiting-command");
        t.setDaemon(true); t.start();
        return new Waiting(t, said, rc);
    }

    static ObjectNode until(Jobs jobs, String id, Predicate<ObjectNode> what, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { ObjectNode j = jobs.get(id); if (j != null && what.test(j)) return j; Thread.sleep(20); }
        fail("the job " + id + " did not come to that within " + ms + " ms: " + jobs.get(id));
        return null;
    }

    /**
     * The shape seen on the real run: a job filed, the service killed in the middle of it and started again, and the waiting command's
     * minute long past while the restarted service has the job back in its line. One run, the service's; one translation written; the
     * command waits for it, says once what it waits for, and never runs the job itself.
     */
    @Test void aJobTheRestartedServiceResumesIsNeverRunAgainByTheWaitingCommand(@TempDir Path tmp) throws Exception {
        LibraryStore store = served(tmp);
        Path doc = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        // the first service: it holds the library, takes the job, and is killed in the middle of it
        Lease first = Lease.acquire(store);
        AtomicBoolean killed = new AtomicBoolean(); CountDownLatch started = new CountDownLatch(1);
        Jobs before = new Jobs(store, (job, drive) -> { started.countDown(); while (!killed.get()) Thread.sleep(20); return ""; }, List.of(""), 1);
        before.start();
        Waiting w = translate(store, doc);
        Path active = new Jobs(store, j -> "").dir().resolve("active");
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end && !(Files.isDirectory(active) && Files.exists(active.resolve("J-0001.json")))) Thread.sleep(20);
        before.pickUp();
        assertTrue(started.await(20, TimeUnit.SECONDS), "the first service started the job");
        before.giveUp("killed"); killed.set(true); before.stop(); first.close();   // the process ends: the job's record stays running
        assertEquals("running", new Jobs(store, j -> "").get("J-0001").path("state").asText());
        // the second service: it takes the library and puts the job back in line to resume; it does not start it yet
        Lease second = Lease.acquire(store);
        AtomicInteger resumed = new AtomicInteger();
        Jobs after = new Jobs(store, (job, drive) -> { resumed.incrementAndGet(); return Translation.runJob(store, job, "", "stand-in", p -> { }, () -> false); }, List.of(""), 1);
        after.pickUpLeftovers();
        assertEquals("queued", after.get("J-0001").path("state").asText());
        Thread.sleep(TranslateCli.TAKE_OVER_MS * 4);   // the command's minute passes with the job in the service's line
        assertEquals("queued", after.get("J-0001").path("state").asText(), "the waiting command took nothing: " + w.said().get());
        after.start();
        w.thread().join(60_000);
        try {
            assertFalse(w.thread().isAlive(), "the command ended when the service's run did");
            String said = w.said().get();
            assertEquals(0, w.rc().get(), said);
            assertFalse(said.contains("No service picked it up"), "the command never ran the job itself: " + said);
            assertTrue(said.contains("  The service (pid " + ProcessHandle.current().pid() + ") holds this library and has the job in its line; waiting for it to run it (Ctrl-C leaves it there).\n"), said);
            assertEquals(1, said.split("holds this library and has the job in its line", -1).length - 1, "said once: " + said);
            assertEquals(1, resumed.get(), "one run, the restarted service's");
            assertEquals(List.of(doc.getFileName().toString().replaceFirst("\\.md$", ".ja.md")), outputs(doc.getParent()), "one translation written");
            ObjectNode j = after.get("J-0001");
            assertEquals("done", j.path("state").asText()); assertEquals(1, j.path("restarted").asInt());
        } finally { after.stop(); second.close(); }
    }

    /**
     * What the waiting exists for: a pid recorded by a service that does not serve this library (none holds its lease), and the job stays
     * queued. After the minute the command takes the lease, stops the job as filed under its lock, runs it itself, and lets the lease go.
     */
    @Test void aJobNoServiceHoldsIsRunByTheCommandAfterTheMinute(@TempDir Path tmp) throws Exception {
        LibraryStore store = served(tmp);
        Path doc = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        Waiting w = translate(store, doc);
        w.thread().join(60_000);
        assertFalse(w.thread().isAlive());
        String said = w.said().get();
        assertEquals(0, w.rc().get(), said);
        assertTrue(said.contains("Filed as J-0001; the service runs it.") && said.contains("No service picked it up in a minute. Running it here, in this terminal.\n"), said);
        assertFalse(said.contains("holds this library"), said);
        assertEquals(List.of(doc.getFileName().toString().replaceFirst("\\.md$", ".ja.md")), outputs(doc.getParent()));
        ObjectNode j = new Jobs(store, x -> "").get("J-0001");
        assertEquals("stopped", j.path("state").asText()); assertEquals("stopped by the terminal before it started", j.path("result").asText(), "no service can start it after");
        assertNull(Lease.holder(store), "the lease taken for the claim is let go");
    }

    /**
     * Two processes and one queued job: a worker that has looked at the job, and a stop that reaches it from another process before the
     * worker claims it. The claim reads the record again under the job's lock and finds it stopped: the job is not run.
     */
    @Test void aWorkerClaimsAJobUnderItsLockAndAStopFromAnotherProcessInBetweenWins(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        AtomicInteger ran = new AtomicInteger();
        Jobs worker = new Jobs(store, (job, drive) -> { ran.incrementAndGet(); return "ran"; }, List.of(""), 1), other = new Jobs(store, j -> "");
        String id = other.submit(Translation.JOB_KIND, "person", new ObjectNode(ComicStandIn.M.getNodeFactory()));
        CountDownLatch looked = new CountDownLatch(1);
        Jobs.CLAIMING = x -> { if (x.equals(id)) { try { other.stopRun(id, "the terminal"); } catch (IOException e) { throw new IllegalStateException(e); } looked.countDown(); } };
        worker.start();
        try {
            assertTrue(looked.await(20, TimeUnit.SECONDS));
            Thread.sleep(300);
            assertEquals(0, ran.get(), "the worker's claim found the job stopped");
            assertEquals("stopped", other.get(id).path("state").asText());
            assertEquals(0, worker.running().size());
        } finally { worker.stop(); }
        // and with nothing in between, a queued job is claimed and run once
        Jobs.CLAIMING = x -> { };
        String next = other.submit(Translation.JOB_KIND, "person", new ObjectNode(ComicStandIn.M.getNodeFactory()));
        Jobs again = new Jobs(store, (job, drive) -> { ran.incrementAndGet(); return "ran"; }, List.of(""), 2);
        again.start();
        try { assertEquals("done", until(again, next, j -> "done".equals(j.path("state").asText()), 30_000).path("state").asText()); assertEquals(1, ran.get()); }
        finally { again.stop(); }
    }
}
