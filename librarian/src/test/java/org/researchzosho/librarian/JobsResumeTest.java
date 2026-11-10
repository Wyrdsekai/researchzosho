package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Version;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The service picks up on start: a job the last service left running is marked interrupted, with when and why, and put back in line to
 * resume from its kept work; its record and every view of it say where it picked up; a queued job stays queued; a job stopped three times
 * in a row with nothing new kept between ends as failed, with the sentence.
 */
class JobsResumeTest {
    static final ObjectMapper M = new ObjectMapper();

    static String leftRunning(Jobs jobs, LibraryStore store, String kind, String said) throws Exception {
        String id = jobs.submit(kind, "person", M.createObjectNode().put("question", "q " + kind));
        ObjectNode j = jobs.get(id); j.put("state", "running"); j.put("started_at", "2026-11-09T01:00:00Z");
        Files.writeString(jobs.activeDir().resolve(id + ".json"), M.writeValueAsString(j));
        if (said != null) {
            Checkpoint cp = Checkpoint.open(store, id, kind, "b");
            cp.put("unit", M.getNodeFactory().textNode("kept"));
            cp.position(M.createObjectNode().put("units_done", 4312), said);
            cp.close();
        }
        return id;
    }

    static void waitDone(Jobs jobs, List<String> ids) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            boolean all = true;
            for (String id : ids) all &= !jobs.get(id).path("state").asText().matches("queued|running");
            if (all) return;
            Thread.sleep(25);
        }
        fail("the jobs did not end");
    }

    @Test void anInterruptedJobResumesAndSaysWhereAndWhy(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Jobs before = new Jobs(store, j -> { throw new IllegalStateException("must not run"); });
        String translation = leftRunning(before, store, Translation.JOB_KIND, "unit 4,312 of 11,637");
        String research = leftRunning(before, store, "research", null);
        String queued = before.submit("crews", "", M.createObjectNode());
        before.stopping(Jobs.UPDATE, "0.7.3");   // the last service wrote why it stopped
        Map<String, String> seenRunning = new ConcurrentHashMap<>();
        Jobs jobs = new Jobs(store, (j, d) -> { seenRunning.put(j.path("job_id").asText(), Jobs.view(j).path("resumed").asText("")); return "done " + j.path("kind").asText(); }, List.of(""), 1);
        // as the service starts: the two left running are interrupted and back in line, before any runs
        ObjectNode t = jobs.get(translation);
        assertEquals("running", t.path("state").asText(), "nothing moves until the service starts");
        jobs.start();
        try {
            waitDone(jobs, List.of(translation, research, queued));
        } finally { jobs.stop(); }
        ObjectNode done = jobs.get(translation);
        assertEquals("done", done.path("state").asText());
        assertEquals(1, done.path("interruptions").size());
        assertEquals("update", done.path("interruptions").get(0).path("why").asText());
        assertEquals("0.7.3", done.path("interruptions").get(0).path("to").asText());
        assertFalse(done.path("interruptions").get(0).path("at").asText().isEmpty());
        assertEquals("Resumed after an update to 0.7.3, at unit 4,312 of 11,637.", seenRunning.get(translation), "the record says it while it runs");
        assertEquals("Resumed after an update to 0.7.3, at unit 4,312 of 11,637.", Jobs.view(done).path("resumed").asText(), "and after");
        assertEquals("unit 4,312 of 11,637", Jobs.view(done).path("resume").path("said").asText());
        assertEquals("2026-11-09T01:00:00Z", done.path("started_at").asText(), "it keeps the time it first started");
        assertTrue(done.hasNonNull("resumed_at"));
        assertEquals("Started again after an update to 0.7.3; nothing had been kept yet.", seenRunning.get(research));
        assertEquals("", seenRunning.get(queued), "a queued job was not interrupted: it simply runs");
        assertFalse(Files.exists(Checkpoint.dir(store, translation)), "a job that ended takes its kept work with it");
        assertFalse(Files.exists(jobs.stoppedFile()), "why the service stopped is read once");
        // the person's line on the command line says it
        assertTrue(LibrarianCli.jobRow(Jobs.view(done)).contains(" · resumed after an update to 0.7.3, at unit 4,312 of 11,637"), LibrarianCli.jobRow(Jobs.view(done)));
    }

    @Test void aStopNobodyWroteDownIsACrashAndAJobThatResumesAndKeepsNothingNewEndsAfterThree(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Jobs before = new Jobs(store, j -> { throw new IllegalStateException("must not run"); });
        Files.createDirectories(before.dir());
        Files.writeString(before.versionFile(), Version.string() + "\n");   // this version ran before, and wrote nothing as it stopped
        String id = leftRunning(before, store, "research", "turn 14");
        for (int round = 1; round <= 3; round++) {
            Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("must not run"); });
            // the service's start without its workers: as a service that stops again before the job gets anywhere
            jobs.pickUpLeftovers();
            ObjectNode j = jobs.get(id);
            if (round < 3) {
                assertEquals("queued", j.path("state").asText());
                assertEquals("Resumes after a stop the service did not finish (a crash, or the computer went off), at turn 14.", Jobs.view(j).path("resumed").asText());
                j.put("state", "running");   // it ran, kept nothing new, and the service stopped again
                Files.writeString(jobs.activeDir().resolve(id + ".json"), M.writeValueAsString(j));
            } else {
                assertEquals("failed", j.path("state").asText(), "three stops in a row with nothing new kept between");
                assertTrue(j.path("result").asText().contains("may be what stopped the service"), j.path("result").asText());
            }
        }
        // a job that keeps something new between stops is never failed for them
        String moving = leftRunning(before, store, "research", "turn 2");
        for (int round = 1; round <= 4; round++) {
            Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("must not run"); });
            jobs.pickUpLeftovers();
            ObjectNode j = jobs.get(moving);
            assertEquals("queued", j.path("state").asText(), "round " + round);
            Checkpoint cp = Checkpoint.open(store, moving, "research", "b");
            cp.put("turn " + round, M.getNodeFactory().textNode("new"));
            cp.position(null, "turn " + (2 + round));
            cp.close();
            j.put("state", "running");
            Files.writeString(jobs.activeDir().resolve(moving + ".json"), M.writeValueAsString(j));
        }
    }

    @Test void aServiceOfAnotherVersionBeforeThisOneIsAnUpdateAndOneFromBeforeTheRecordIsARestart(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Jobs before = new Jobs(store, j -> { throw new IllegalStateException("must not run"); });
        String first = leftRunning(before, store, "research", "turn 3");
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("must not run"); });
        jobs.pickUpLeftovers();
        assertEquals("Resumes after a restart, at turn 3.", Jobs.view(jobs.get(first)).path("resumed").asText(), "a version before 0.7.2 ran: it wrote down nothing");
        assertEquals(Version.string(), Files.readString(jobs.versionFile()).strip(), "each start writes down its version");
        Files.writeString(jobs.versionFile(), "0.7.1\n");
        ObjectNode j = jobs.get(first); j.put("state", "running"); Files.writeString(jobs.activeDir().resolve(first + ".json"), M.writeValueAsString(j));
        new Jobs(store, x -> "").pickUpLeftovers();
        assertEquals("Resumes after an update to " + Version.string() + ", at turn 3.", Jobs.view(jobs.get(first)).path("resumed").asText(), "another version ran before: the person updated by hand");
    }
}
