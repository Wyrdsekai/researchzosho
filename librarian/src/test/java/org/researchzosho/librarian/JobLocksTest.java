package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stop takes the job's lock, a file beside the job. A stop that came before the job started, or after it ended, left that empty file in
 * the active jobs for good (one library had eight of them from runs stopped on the web page). The service's pass over its jobs removes the
 * lock of any job no longer active, and leaves the lock of one that is.
 */
class JobLocksTest {

    static final ObjectMapper M = new ObjectMapper();

    @Test
    void theLocksOfJobsThatEndedGoAndTheLockOfAnActiveJobStays(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Jobs jobs = new Jobs(store, x -> "");
        Path active = jobs.dir().resolve("active");
        String stopped = jobs.submit("research", "did:key:someone", M.createObjectNode().put("question", "a placeholder question"));
        String waiting = jobs.submit("research", "did:key:someone", M.createObjectNode().put("question", "another placeholder question"));
        assertEquals("stopped", jobs.stop(stopped, "patron:web"), "stopped before it started");
        assertNull(jobs.stop("J-9999", "patron:web"), "a stop for a job that is not active does nothing");
        assertTrue(jobs.beginFiling(waiting), "takes the lock of a job that is still active");
        Files.writeString(active.resolve("J-0001.lock"), "");   // left by an earlier version
        assertTrue(Files.exists(active.resolve(stopped + ".lock")) && Files.exists(active.resolve("J-9999.lock")), "the stops left their locks");
        jobs.pickUp();
        assertFalse(Files.exists(active.resolve(stopped + ".lock")), "the stopped job's lock went");
        assertFalse(Files.exists(active.resolve("J-9999.lock")), "the lock of a job that was never active went");
        assertFalse(Files.exists(active.resolve("J-0001.lock")), "an old leftover went");
        assertTrue(Files.exists(active.resolve(waiting + ".lock")), "the active job keeps its lock");
        assertEquals("queued", jobs.get(waiting).path("state").asText());
    }
}
