package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.HangingServer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A research run that has shown no progress for a quarter of an hour is said to be so by `researchzosho jobs`, and the service writes where
 * each of the run's threads waits to its log, once, so that the next run that hangs shows where.
 */
class StalledRunTest {

    static final ObjectMapper M = new ObjectMapper();

    static String jobs(LibraryStore store, String... more) throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        System.setOut(new PrintStream(o, true, StandardCharsets.UTF_8));
        String[] args = new String[2 + more.length];
        args[0] = "researchzosho"; args[1] = "jobs";
        System.arraycopy(more, 0, args, 2, more.length);
        try { LibrarianCli.jobs(store, args); } finally { System.setOut(out); }
        return o.toString(StandardCharsets.UTF_8);
    }

    @Test
    void jobsSaysSinceWhenARunHasShownNoProgress(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        CountDownLatch said = new CountDownLatch(1), done = new CountDownLatch(1);
        Jobs[] holder = new Jobs[1];
        String twentyMinutesAgo = Instant.now().minusSeconds(20 * 60).toString();
        Jobs jobs = new Jobs(store, (job, drive) -> {
            ObjectNode p = M.createObjectNode();
            p.put("phase", "workers"); p.put("round", 1); p.put("rounds", 2); p.put("turns_used", 3); p.put("turns_ceiling", 60); p.put("at", twentyMinutesAgo);
            holder[0].progress(job.path("job_id").asText(), p);
            said.countDown();
            done.await(20, TimeUnit.SECONDS);
            return "done";
        }, List.of(""), 1);
        holder[0] = jobs;
        String id = jobs.submit("research", "person", (ObjectNode) M.readTree("{\"question\":\"How were the placeholder gears cut?\"}"));
        jobs.start();
        try {
            assertTrue(said.await(10, TimeUnit.SECONDS));
            String one = jobs(store, id);
            assertTrue(one.contains("The run has shown no progress since"), one);
            assertTrue(one.contains("researchzosho research stop " + id), one);
            assertTrue(jobs(store).contains("no progress since"), "the list says it too");
        } finally {
            done.countDown();
            jobs.stop();
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void theServiceWritesWhereAStalledRunsThreadsWaitOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.write);
        List<String> written = new CopyOnWriteArrayList<>();
        long look = LibrarianDaemon.STALL_LOOK_MS, stall = Jobs.STALL_MS;
        LibrarianDaemon.STALL_LOOK_MS = 200; Jobs.STALL_MS = 1_500; LibrarianDaemon.stallLog = written::add;
        try (HangingServer model = new HangingServer(HangingServer.Mode.SILENT, r -> r.body().contains("\"max_tokens\":1,") || r.body().contains("\"max_tokens\":1}"), RunStopTest.PROBE_ANSWER)) {
            LibrarianDaemon daemon = LibrarianDaemon.start(store, "127.0.0.1", 0, model.url(), "placeholder-model", -1);
            daemon.researcherFactory = d -> new Researcher(Researcher.drive(d, "placeholder-model"), new ResearcherTest.FakeTools(), null, 1);
            try {
                ObjectNode ask = M.createObjectNode();
                ask.put("question", "How were the placeholder gears cut?");
                ask.putArray("sub_questions").add("how were they cut?");
                String id = daemon.research(ask, Patrons.Patron.PERSON).path("job_id").asText();
                assertTrue(model.awaitHung(30), "the run's request to the model hangs");
                for (int i = 0; i < 100 && written.isEmpty(); i++) Thread.sleep(100);
                assertEquals(1, written.size(), "written once the run showed no progress: " + written);
                String dump = written.get(0);
                assertTrue(dump.startsWith("research " + id + " has shown no progress since "), dump);
                assertTrue(dump.contains("\"librarian-jobs-") && dump.contains("Stopping.await("), "the run's own thread, waiting for the model: " + dump);
                Thread.sleep(1_000);
                assertEquals(1, written.size(), "once, not at every look");
                assertTrue(Jobs.view(daemon.jobs().get(id)).hasNonNull("no_progress_since"));
                assertTrue(Files.readString(store.root().resolve("catalog").resolve("crews.log")).contains("was written to the service's log"));
                daemon.jobs().stop(id, "person");
            } finally { daemon.stop(); }
        } finally {
            LibrarianDaemon.STALL_LOOK_MS = look; Jobs.STALL_MS = stall; LibrarianDaemon.stallLog = null;
        }
    }
}
