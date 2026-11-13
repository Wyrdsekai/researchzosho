package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stop holds (0.7.4): a job asked to stop that the service left running before the stop took effect ends stopped at the next start and is
 * never resumed; `jobs <id> --stop` stops a job of any kind with the sentence the other surfaces say; `service stop` says what becomes of a
 * running job, and with --stop-jobs stops it for good, which holds through the service's end and its next start.
 */
class JobsStopTest {
    static final ObjectMapper M = new ObjectMapper();
    String home;
    PrintStream out;
    final ByteArrayOutputStream said = new ByteArrayOutputStream();

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        out = System.out; System.setOut(new PrintStream(said, true, StandardCharsets.UTF_8));
    }
    @AfterEach void restore() { System.setOut(out); System.setProperty("user.home", home); Config.invalidate(); LibrarianCli.JOBS_SETTLE = Duration.ofSeconds(15); }

    String said() { String s = said.toString(StandardCharsets.UTF_8); said.reset(); return s.strip(); }

    @Test void aStopAskedBeforeTheServiceEndedHoldsAtTheNextStartAndNothingResumes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Jobs before = new Jobs(store, j -> { throw new IllegalStateException("must not run"); });
        String stopped = JobsResumeTest.leftRunning(before, store, Translation.JOB_KIND, "unit 12 of 40");
        String going = JobsResumeTest.leftRunning(before, store, Translation.JOB_KIND, "unit 3 of 40");
        assertEquals("stopping", before.stop(stopped, "a person"), "running: the marker is left, and the run ends within seconds");
        // the service ends before the run does; at the next start:
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("must not run"); });
        jobs.pickUpLeftovers();
        ObjectNode j = jobs.get(stopped);
        assertEquals("stopped", j.path("state").asText(), "a stop not yet honoured is remembered: " + j);
        assertEquals("stopped by the person — " + Jobs.STOPPED_WHILE_AWAY, j.path("result").asText());
        assertFalse(j.path("is_error").asBoolean(true));
        assertFalse(Files.exists(jobs.stopMarker(stopped)), "the marker is taken away with it");
        assertFalse(Files.exists(Checkpoint.dir(store, stopped)), "its kept work goes as a stopped job's does");
        assertEquals("queued", jobs.get(going).path("state").asText(), "a job nobody stopped resumes as before");
        assertEquals(1, jobs.queued(), "only the one nobody stopped is in line");
    }

    @Test void jobsStopStopsAJobOfAnyKindWithTheSentenceTheOtherSurfacesSay(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("must not run"); });
        String queued = jobs.submit(Translation.JOB_KIND, "person", M.createObjectNode().put("path", "doc.md"));
        assertEquals(0, LibrarianCli.jobs(store, new String[]{"researchzosho", "jobs", queued, "--stop"}));
        assertEquals(queued + " was waiting to start. It is stopped and will not start.", said());
        assertEquals("stopped", jobs.get(queued).path("state").asText());
        String running = JobsResumeTest.leftRunning(jobs, store, Translation.JOB_KIND, null);
        assertEquals(0, LibrarianCli.jobs(store, new String[]{"researchzosho", "jobs", running, "--stop"}));
        assertEquals("Stopping " + running + ". It ends within a few seconds, also in the middle of a step: a call to the model is closed and a scorer's work is ended. Nothing from it is written. "
                + "If the service stops first, the job stays stopped and is not started again. The command researchzosho jobs shows it as stopped.", said());
        assertTrue(jobs.stopRequested(running));
        String research = JobsResumeTest.leftRunning(jobs, store, "research", null);
        LibrarianCli.jobs(store, new String[]{"researchzosho", "jobs", research, "--stop"});
        assertTrue(said().endsWith("Nothing from it is filed. The command researchzosho jobs shows it as stopped."), "a research run says what research stop says");
    }

    @Test void serviceStopSaysWhatBecomesOfARunningJobAndStopJobsStopsItForGood(@TempDir Path tmp) throws Exception {
        Path lib = tmp.resolve("lib");
        Config.set("RESEARCHZOSHO_LIBRARY", lib.toString());
        LibraryStore store = new LibraryStore(lib); store.init();
        Jobs before = new Jobs(store, j -> { throw new IllegalStateException("must not run"); });
        String id = JobsResumeTest.leftRunning(before, store, Translation.JOB_KIND, "unit 7 of 40");
        LibrarianCli.serviceStopJobs(false);
        String s = said();
        assertTrue(s.startsWith("A job is running: " + id + " (" + Translation.JOB_KIND + ", "), s);
        assertTrue(s.endsWith("It stops with the service and resumes where it was at the next start, unless you stop it (researchzosho jobs " + id + " --stop). researchzosho service stop --stop-jobs stops it for good with the service."), s);
        assertFalse(before.stopRequested(id), "said, not done");
        // with the flag: asked to stop, and the service ends before the run does (nothing runs it here)
        LibrarianCli.JOBS_SETTLE = Duration.ofMillis(300);
        LibrarianCli.serviceStopJobs(true);
        assertEquals("Stopped for good, with the service: " + id + ". It is not started again at the next start.", said());
        Jobs next = new Jobs(store, j -> { throw new IllegalStateException("must not run: " + j.path("job_id").asText()); });
        next.pickUpLeftovers();
        assertEquals("stopped", next.get(id).path("state").asText());
        assertEquals(0, next.queued(), "nothing resumes");
    }
}
