package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A job a worker has taken from the line and not yet claimed is work the library has (2026-11-18). It was in neither the line nor the
 * running set then, so the library told its node it was quiet; the node put a suspended keeper's library to sleep in that moment, the
 * job was claimed and stopped with the library, left running, and at the next start it was put back in line and ran on its own, where
 * it should have waited, stopped at the suspension, for its person (AccountLifecycleTest, full suite, "restarted":1, "why":"restart").
 */
class JobsInHandTest {
    static final ObjectMapper M = new ObjectMapper();
    final Consumer<String> claiming = Jobs.CLAIMING;

    @AfterEach void back() { Jobs.CLAIMING = claiming; }

    @Test
    void aJobBetweenTheLineAndItsClaimKeepsTheLibraryFromReadingQuiet(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Jobs jobs = new Jobs(store, (job, drive) -> "ran", List.of(""), 1);
        CountDownLatch looked = new CountDownLatch(1), go = new CountDownLatch(1);
        // the worker held where the race was: it has taken the job from the line and looked at it, and has not claimed it
        Jobs.CLAIMING = x -> { looked.countDown(); try { go.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        jobs.start();   // running, as a library's service is, before the job is filed: the job is in the line once
        String id = jobs.submit(Translation.JOB_KIND, "person", M.createObjectNode());
        try {
            assertTrue(looked.await(20, TimeUnit.SECONDS));
            Jobs.Line held = jobs.line();
            assertEquals(1, held.running() + held.waiting(), "the job in the worker's hand is still the library's work: it does not read quiet");
            go.countDown();
            long end = System.currentTimeMillis() + 20_000;
            while (!"done".equals(jobs.get(id).path("state").asText()) && System.currentTimeMillis() < end) Thread.sleep(50);
            assertEquals("done", jobs.get(id).path("state").asText());
            end = System.currentTimeMillis() + 5_000;
            while (jobs.line().running() > 0 && System.currentTimeMillis() < end) Thread.sleep(20);
            Jobs.Line after = jobs.line();
            assertEquals(0, after.running() + after.waiting(), "and once it has ended, nothing is left counted");
        } finally { go.countDown(); jobs.stop(); }
    }
}
