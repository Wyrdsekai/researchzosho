package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.HangingServer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A research run whose model took a request and never answers is stopped within seconds when a person asks for it (the owner's fourth run
 * of 2026-09-24 went on six hours after its stop). The run ends as stopped, says so, and the connection to the model server is closed.
 */
class RunStopTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String PROBE_ANSWER = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

    @Test
    void aStopEndsARunWhoseModelCallHangsWithinSeconds(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.write);
        // the probe that asks whether a model answers gets its answer; every request of the run itself hangs
        try (HangingServer model = new HangingServer(HangingServer.Mode.SILENT, r -> r.body().contains("\"max_tokens\":1,") || r.body().contains("\"max_tokens\":1}"), PROBE_ANSWER)) {
            LibrarianDaemon daemon = LibrarianDaemon.start(store, "127.0.0.1", 0, model.url(), "placeholder-model", -1);
            daemon.researcherFactory = d -> new Researcher(Researcher.drive(d, "placeholder-model"), new ResearcherTest.FakeTools(), null, 1);
            try {
                ObjectNode ask = M.createObjectNode();
                ask.put("question", "How were the placeholder gears cut?");
                ask.putArray("sub_questions").add("how were they cut?");
                String id = daemon.research(ask, Patrons.Patron.PERSON).path("job_id").asText();
                assertTrue(model.awaitHung(30), "the run sent its first request to the model");
                assertEquals("running", daemon.jobs().get(id).path("state").asText());
                long asked = System.currentTimeMillis();
                assertEquals("stopping", daemon.jobs().stop(id, "person"));
                JsonNode j = daemon.jobs().get(id);
                while (!"stopped".equals(j.path("state").asText()) && System.currentTimeMillis() - asked < 15_000) { Thread.sleep(50); j = daemon.jobs().get(id); }
                long took = System.currentTimeMillis() - asked;
                assertEquals("stopped", j.path("state").asText(), "the run ended as stopped: " + j);
                assertTrue(took < 5_000, "within seconds of the stop, while its model call hung: " + took + " ms");
                assertFalse(j.path("is_error").asBoolean(), "a stop is no failure");
                assertTrue(j.path("result").asText().startsWith("stopped by the person"), j.path("result").asText());
                assertTrue(j.path("result").asText().contains("nothing from this run was filed"), j.path("result").asText());
                assertTrue(model.awaitClosed(5), "the connection to the model server was closed");
                assertFalse(Files.exists(store.investigationsDir()) && Files.list(store.investigationsDir()).findAny().isPresent(), "a stopped run files nothing");
            } finally { daemon.stop(); }
        }
    }
}
