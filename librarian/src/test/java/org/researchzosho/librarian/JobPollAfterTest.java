package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A program that follows a run is told how long to wait before it asks again (0.8.0): library_job's {job} carries poll_after while the
 * run may still change, 10 s running, 5 s for a quick ask, 15 s in line, 30 s waiting for the person's answer, 60 s stopped at a cap,
 * and none once it has ended.
 */
class JobPollAfterTest {
    static final ObjectMapper M = new ObjectMapper();
    static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

    @Test
    void aRunSaysHowLongToWaitBeforeItIsAskedForAgain(@TempDir Path tmp) throws Exception {
        Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zA", "A", Patrons.Level.write);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Jobs jobs = new Jobs(store, (job, drive) -> { started.countDown(); release.await(20, TimeUnit.SECONDS); return "ran"; }, List.of(""), 1);
        String running = jobs.submit("research", "did:key:zA", args("{\"question\":\"first\"}"));
        String waiting = jobs.submit("research", "did:key:zA", args("{\"question\":\"second\"}"));
        jobs.start();
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            LibraryProtocol p = new LibraryProtocol(store);
            JsonNode r = p.job(args("{\"job_id\":\"" + running + "\",\"patron\":{\"did\":\"did:key:zA\"}}")).path("job");
            assertEquals("running", r.path("state").asText()); assertEquals(10, r.path("poll_after").asInt());
            JsonNode q = p.job(args("{\"job_id\":\"" + waiting + "\",\"patron\":{\"did\":\"did:key:zA\"}}")).path("job");
            assertEquals("queued", q.path("state").asText()); assertEquals(15, q.path("poll_after").asInt());
            release.countDown();
            for (int i = 0; i < 200 && !"done".equals(jobs.get(running).path("state").asText()); i++) Thread.sleep(50);
            JsonNode done = p.job(args("{\"job_id\":\"" + running + "\",\"patron\":{\"did\":\"did:key:zA\"}}")).path("job");
            assertEquals("done", done.path("state").asText());
            assertFalse(done.has("poll_after"), "nothing to wait for once it has ended");
        } finally { release.countDown(); jobs.stop(); }
        assertEquals(5, Jobs.pollAfter(args("{\"state\":\"running\",\"args\":{\"quick\":true}}")));
        assertEquals(30, Jobs.pollAfter(args("{\"state\":\"" + Jobs.OFFERED + "\"}")));
        assertEquals(60, Jobs.pollAfter(args("{\"state\":\"" + Jobs.CAPPED + "\"}")));
        for (String ended : List.of("done", "failed", "stopped")) assertEquals(0, Jobs.pollAfter(args("{\"state\":\"" + ended + "\"}")));
    }
}
