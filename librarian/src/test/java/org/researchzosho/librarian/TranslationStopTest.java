package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.DriveClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stop is honoured inside a step (0.7.4): asked while MetricX-24 scores, the translation ends within seconds, the scorer's process is
 * ended, and the job's kept work is left as a stop between steps leaves it, with nothing of the step kept; asked while a call to the model
 * waits (the checks, the reader's pass, a back-translation), the call is closed and the translation ends within seconds. Before 0.7.4 the
 * stop was looked at only between steps: a scoring step on a processor ran on for minutes, and a model call for as long as it took.
 */
class TranslationStopTest {
    @BeforeEach void standIns() { Translation.SETUP_SAID = () -> ""; }
    @AfterEach void back() {
        TranslationScores.SCORERS = TranslationScores::configured;
        Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; };
    }

    static Path document(Path tmp) throws IOException {
        Path in = tmp.resolve("doc.md");
        Files.writeString(in, "# 見出し\n\n" + "これは試験のための段落で、翻訳されて点数をつけられるための十分な長さがあります。\n\n".repeat(4), StandardCharsets.UTF_8);
        return in;
    }

    /** The processes of this test that run {@code marker}'s program. */
    static List<ProcessHandle> running(String marker) {
        return ProcessHandle.current().descendants().filter(p -> p.isAlive() && p.info().commandLine().orElse("").contains(marker)).toList();
    }

    @Test void aStopInTheMiddleOfTheScoringEndsTheJobWithinSecondsAndEndsTheScorersProcess(@TempDir Path tmp) throws Exception {
        // MetricX-24's process as a slow processor runs it: it loads, and gives no score for minutes
        Path sleeper = tmp.resolve("SlowMetricx.java");
        Files.writeString(sleeper, "public class SlowMetricx { public static void main(String[] a) throws Exception { System.out.println(\"loading\"); System.out.flush(); Thread.sleep(600_000); } }", StandardCharsets.UTF_8);
        String java = ProcessHandle.current().info().command().orElse("java");
        TranslationScores.Metricx mx = new TranslationScores.Metricx(() -> List.of(java, sleeper.toString()));
        TranslationScores.SCORERS = () -> new TranslationScores.Scorers(null, mx);
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = document(tmp);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong stoppedAt = new AtomicLong();
        Thread asker = new Thread(() -> {   // the person's stop, once the scorer's process is up and the step waits on it
            long until = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < until && running("SlowMetricx").isEmpty()) { try { Thread.sleep(50); } catch (InterruptedException e) { return; } }
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            stoppedAt.set(System.currentTimeMillis()); stop.set(true);
        });
        asker.setDaemon(true); asker.start();
        try (Checkpoint cp = Checkpoint.open(store, "J-0071", Translation.JOB_KIND, "b")) {
            IOException e = assertTimeoutPreemptively(Duration.ofSeconds(90), () -> assertThrows(IOException.class,
                    () -> Translation.run(store, in, "en", "", "checked", tmp.resolve("doc.en.md"), null, TranslationTest.seats(new TranslationTest.Script()), null, stop::get, cp)),
                    "the scoring step waited on its scorer past the stop");
            long took = System.currentTimeMillis() - stoppedAt.get();
            assertTrue(stoppedAt.get() > 0 && took < 5_000, "the stop took " + took + " ms");
            assertEquals(Translation.STOPPED_IN_STEP, e.getMessage());
            assertNull(cp.get("score|all|0"), "nothing of the step that was stopped is kept");
        }
        long until = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < until && !running("SlowMetricx").isEmpty()) Thread.sleep(50);
        assertTrue(running("SlowMetricx").isEmpty(), "the scorer's process is ended with the stop");
        assertFalse(Files.exists(tmp.resolve("doc.en.md")), "nothing was written");
    }

    @Test void aStopWhileACallToTheModelWaitsClosesItAndEndsTheJobWithinSeconds(@TempDir Path tmp) throws Exception {
        // the checker's model on a server that takes the call and gives no answer for minutes
        CountDownLatch release = new CountDownLatch(1), asked = new CountDownLatch(1);
        HttpServer h = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        h.createContext("/", x -> {
            x.getRequestBody().readAllBytes();
            if (!x.getRequestMethod().equals("POST")) { x.sendResponseHeaders(404, -1); x.close(); return; }
            asked.countDown();
            try { release.await(120, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            byte[] out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"1. OK\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            try { x.sendResponseHeaders(200, out.length); try (OutputStream os = x.getResponseBody()) { os.write(out); } } catch (IOException gone) { /* the caller closed it */ }
            x.close();
        });
        h.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        h.start();
        try {
            TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null);
            LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
            Path in = document(tmp);
            Researcher.Drive local = TranslationTest.standIn(new TranslationTest.Script(), "local");
            Researcher.Drive slow = Researcher.drive(new DriveClient("http://127.0.0.1:" + h.getAddress().getPort(), "slow-reader"));
            Translation.Seats seats = new Translation.Seats(local, "local", slow, "slow-reader").withBack(true);
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong stoppedAt = new AtomicLong();
            Thread asker = new Thread(() -> {
                try { if (!asked.await(60, TimeUnit.SECONDS)) return; Thread.sleep(500); } catch (InterruptedException e) { return; }
                stoppedAt.set(System.currentTimeMillis()); stop.set(true);
            });
            asker.setDaemon(true); asker.start();
            IOException e = assertTimeoutPreemptively(Duration.ofSeconds(90), () -> assertThrows(IOException.class,
                    () -> Translation.run(store, in, "en", "", "reviewed", tmp.resolve("doc.en.md"), null, seats, null, stop::get)),
                    "the call to the model was waited for past the stop");
            assertTrue(stoppedAt.get() > 0, "the checker's model was asked");
            long took = System.currentTimeMillis() - stoppedAt.get();
            assertTrue(took < 5_000, "the stop took " + took + " ms");
            assertEquals(Translation.STOPPED_IN_STEP, e.getMessage());
        } finally { release.countDown(); h.stop(0); }
    }
}
