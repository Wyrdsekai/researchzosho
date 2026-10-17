package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A research run on a named model: the ask names it, the job carries it, the run is on its drive and model, its report says who researched
 * it and what it cost, the ledger has the line, and a run past the entry's monthly cap is refused before it starts.
 */
class ModelRunTest {

    static final ObjectMapper M = new ObjectMapper();
    String home;

    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); }

    @Test
    void aRunOnANamedModelIsReportedAndCountedAndACapRefusesOne(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.write);
        // the library's own drive is a closed port; the named entry's drive is a server that answers the probe
        com.sun.net.httpserver.HttpServer answering = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        answering.createContext("/", ex -> { byte[] out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8); ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); ex.close(); });
        answering.start();
        String entryDrive = "http://127.0.0.1:" + answering.getAddress().getPort();
        Config.set("RESEARCHZOSHO_DRIVE", "http://127.0.0.1:2"); Config.set("RESEARCHZOSHO_MODEL", "placeholder-model");
        ModelEntries.put("cheap", entryDrive, "cheap-model", "1/2/0.1", null, "5", "a test entry");
        LibrarianDaemon daemon = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:2", "placeholder-model", -1);
        String[] driveSeen = {""};
        daemon.researcherFactory = d -> { driveSeen[0] = d; ResearcherTest.ScriptedDrive sd = new ResearcherTest.ScriptedDrive(); sd.criticWantsMore = false; return new Researcher(sd, new ResearcherTest.FakeTools(), null, 2); };
        try {
            ObjectNode ask = M.createObjectNode();
            ask.put("question", "How were the placeholder gears cut?"); ask.putArray("sub_questions").add("how were they cut?"); ask.put("model", "cheap");
            JsonNode filed = daemon.research(ask, Patrons.Patron.PERSON);
            String id = filed.path("job_id").asText();
            assertEquals("cheap", daemon.jobs().get(id).path("args").path("model_entry").asText(), "the job carries the name");
            JsonNode j = daemon.jobs().get(id);
            long t0 = System.currentTimeMillis();
            while (!java.util.Set.of("done", "failed", "stopped").contains(j.path("state").asText()) && System.currentTimeMillis() - t0 < 60_000) { Thread.sleep(100); j = daemon.jobs().get(id); }
            assertEquals(entryDrive, driveSeen[0], "the run was made for the entry's drive, not the library's: " + j);
            assertEquals("done", j.path("state").asText(), j.toString());
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("investigation (I-\\d{4}-[a-z0-9-]+)").matcher(j.path("result").asText());
            assertTrue(m.find(), "a report was filed: " + j);
            String inv = m.group(1);
            String body = Files.readString(store.root().resolve("investigations").resolve(inv + ".md"));
            assertTrue(body.contains("Researched by cheap (cheap-model via 127.0.0.1)"), "the report says who researched it: " + body.substring(0, Math.min(600, body.length())));
            assertTrue(body.contains("at the entry's prices"), "and what it cost at the entry's prices: " + body.substring(0, Math.min(600, body.length())));
            assertEquals(1, Spend.lines(store).size(), "one ledger line");
            assertEquals("cheap", Spend.lines(store).get(0).path("entry").asText());
            // best judgement: nothing named while two models are named → auto, which picks the tier-A entry over the free default and says why
            ModelEntries.put("cheap", null, null, null, null, null, null, "A");
            ObjectNode none = ask.deepCopy(); none.remove("model");
            String id3 = daemon.research(none, Patrons.Patron.PERSON).path("job_id").asText();
            assertEquals("auto", daemon.jobs().get(id3).path("args").path("model_entry").asText(), "auto is the default once a second model is named");
            JsonNode j3 = daemon.jobs().get(id3); t0 = System.currentTimeMillis();
            while (!java.util.Set.of("done", "failed", "stopped").contains(j3.path("state").asText()) && System.currentTimeMillis() - t0 < 60_000) { Thread.sleep(100); j3 = daemon.jobs().get(id3); }
            assertEquals("done", j3.path("state").asText(), j3.toString());
            java.util.regex.Matcher m3 = java.util.regex.Pattern.compile("investigation (I-\\d{4}-[a-z0-9-]+)").matcher(j3.path("result").asText());
            assertTrue(m3.find(), j3.toString());
            String body3 = Files.readString(store.root().resolve("investigations").resolve(m3.group(1) + ".md"));
            assertTrue(body3.contains("Researched by cheap (cheap-model via 127.0.0.1)") && body3.contains("Chosen by best judgement: auto chose cheap: tier A for a tier-A research job"), body3.substring(0, Math.min(900, body3.length())));
            assertEquals(2, Spend.lines(store).size()); assertEquals("cheap", Spend.lines(store).get(1).path("entry").asText()); assertTrue(Spend.lines(store).get(1).path("secs").asLong(0) >= 0 && Spend.lines(store).get(1).path("kind").asText().equals("research"));
            // a cap already reached: the next run on that entry is refused before any call, with the figures
            Spend.record(store, "J-manual", "", ModelEntries.get("cheap"), new long[]{1, 0, 0}, 5.0);
            String id2 = daemon.research(ask, Patrons.Patron.PERSON).path("job_id").asText();
            JsonNode j2 = daemon.jobs().get(id2); t0 = System.currentTimeMillis();
            while (!java.util.Set.of("done", "failed", "stopped").contains(j2.path("state").asText()) && System.currentTimeMillis() - t0 < 30_000) { Thread.sleep(100); j2 = daemon.jobs().get(id2); }
            assertEquals("failed", j2.path("state").asText(), j2.toString());
            assertTrue(j2.path("result").asText().contains("cap of $5.00") && j2.path("result").asText().contains("not started"), j2.path("result").asText());
            // a name nobody kept is refused at the ask
            ObjectNode bad = ask.deepCopy(); bad.put("model", "nobody");
            ProtocolError e = assertThrows(ProtocolError.class, () -> new LibraryProtocol(store).research(bad));
            assertTrue(e.getMessage().contains("No model is named nobody"), e.getMessage());
        } finally { daemon.stop(); answering.stop(0); }
    }
}
