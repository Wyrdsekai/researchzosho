package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Stopping a run: a queued one never starts; a running one ends at its next turn; the runner can be paused from the protocol. */
class JobStopTest {

    static final ObjectMapper M = new ObjectMapper();
    static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

    @Test
    void aQueuedRunIsStoppedAtOnceAndARunningOneAtItsNextTurn(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());   // the pause is a config setting: keep it off the developer's own file
        org.researchzosho.Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zA", "A", Patrons.Level.write);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Jobs[] holder = new Jobs[1];
        // a runner that checks the stop marker between "turns", as the researcher does
        Jobs jobs = new Jobs(store, (job, drive) -> {
            started.countDown();
            String id = job.path("job_id").asText();
            for (int turn = 0; turn < 200; turn++) {
                if (holder[0].stopRequested(id)) throw new Researcher.Stopped();
                Thread.sleep(20);
            }
            return "ran to the end";
        }, List.of(""), 1);
        holder[0] = jobs;
        String running = jobs.submit("research", "did:key:zA", args("{\"question\":\"first\"}"));
        String waiting = jobs.submit("research", "did:key:zA", args("{\"question\":\"second\"}"));
        jobs.start();
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS), "the first job runs");
            LibraryProtocol p = new LibraryProtocol(store);
            // the queued one: stopped before it starts
            var r = p.job(args("{\"op\":\"stop\",\"job_id\":\"" + waiting + "\",\"patron\":{\"did\":\"did:key:zA\"}}"));
            assertEquals("stopped", r.path("state").asText());
            assertEquals("stopped", jobs.get(waiting).path("state").asText());
            assertFalse(jobs.get(waiting).path("is_error").asBoolean());
            // the running one: marked, ends at its next turn
            r = p.job(args("{\"op\":\"stop\",\"job_id\":\"" + running + "\",\"patron\":{\"did\":\"did:key:zA\"}}"));
            assertEquals("stopping", r.path("state").asText());
            long t0 = System.currentTimeMillis();
            while (!"stopped".equals(jobs.get(running).path("state").asText()) && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(50);
            ObjectNode done = jobs.get(running);
            assertEquals("stopped", done.path("state").asText(), done.toString());
            assertFalse(done.path("is_error").asBoolean(), "stopped is not failed");
            assertTrue(done.path("result").asText().startsWith("stopped by the person at a turn"), done.path("result").asText());
            assertFalse(jobs.stopRequested(running), "the marker is cleared");
            // a finished job cannot be stopped; a reader may not stop anything
            assertThrows(ProtocolError.class, () -> p.job(args("{\"op\":\"stop\",\"job_id\":\"" + running + "\",\"patron\":{\"did\":\"did:key:zA\"}}")));
            assertThrows(ProtocolError.class, () -> p.job(args("{\"op\":\"stop\",\"job_id\":\"" + running + "\"}")), "anonymous: no write");
            // pause and resume the runner
            assertTrue(p.job(args("{\"op\":\"pause\",\"patron\":{\"did\":\"did:key:zA\"}}")).path("paused").asBoolean());
            assertTrue(p.job(args("{}")).path("paused").asBoolean(), "the listing says so");
            assertFalse(p.job(args("{\"op\":\"resume\",\"patron\":{\"did\":\"did:key:zA\"}}")).path("paused").asBoolean());
        } finally {
            jobs.stop();
            System.setProperty("user.home", realHome);
            org.researchzosho.Config.invalidate();
        }
    }

    @Test
    void progressLandsOnTheRunningJobAndItsView(@TempDir Path tmp) throws Exception {
        org.researchzosho.Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zA", "A", Patrons.Level.write);
        Jobs[] holder = new Jobs[1];
        Jobs jobs = new Jobs(store, (job, drive) -> {
            String id = job.path("job_id").asText();
            ObjectNode p = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            p.put("phase", "workers"); p.put("round", 1); p.put("rounds", 2); p.put("workers_done", 2); p.put("workers_total", 5); p.put("turns_used", 41); p.put("turns_ceiling", 200);
            holder[0].progress(id, p);
            ObjectNode seen = holder[0].get(id);
            assertEquals(41, seen.path("progress").path("turns_used").asInt(), "on the record while it runs");
            assertEquals("round 1 of 2 · workers 2 of 5 done · 41 of 200 turns · workers", Pages.progressLine(seen.get("progress")));
            return "done";
        }, List.of(""), 1);
        holder[0] = jobs;
        String id = jobs.submit("research", "did:key:zA", args("{\"question\":\"first\"}"));
        jobs.start();
        try {
            for (int i = 0; i < 200 && !"done".equals(jobs.get(id).path("state").asText()); i++) Thread.sleep(50);
            ObjectNode v = Jobs.view(jobs.get(id));
            assertEquals("done", v.get("state").asText());
            assertEquals("workers", v.path("progress").path("phase").asText(), "the last progress stays on the finished record");
        } finally { jobs.stop(); }
    }

    @Test
    void aRunWaitingForAModelThatDoesNotAnswerSaysSoOnItsRecord(@TempDir Path tmp) throws Exception {
        org.researchzosho.Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zA", "A", Patrons.Level.write);
        Jobs jobs = new Jobs(store, (job, drive) -> "ran", List.of("http://127.0.0.1:1"), 1);   // nothing listens on port 1
        String id = jobs.submit("research", "did:key:zA", args("{\"question\":\"first\"}"));
        jobs.start();
        try {
            ObjectNode j = null;
            for (int i = 0; i < 100 && (j == null || !j.hasNonNull("waiting")); i++) { Thread.sleep(100); j = jobs.get(id); }
            assertEquals("queued", j.path("state").asText(), "not started: the model never answered");
            assertTrue(j.get("waiting").asText().startsWith("no model answers at http://127.0.0.1:1"), j.get("waiting").asText());
            assertEquals(j.get("waiting").asText(), Jobs.view(j).get("waiting").asText(), "the view carries it");
            assertTrue(Pages.jobLine(Jobs.view(j)).contains("waiting for the model"), "the Runs page says it");
        } finally { jobs.stop(); }
    }
}
