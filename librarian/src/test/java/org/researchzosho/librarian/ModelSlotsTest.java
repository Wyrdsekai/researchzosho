package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How many runs and readers the model server can carry (0.6.1): the rule from the slots; the server asked (llama.cpp's props page, also
 * through llama-swap; a guess for Ollama and for a vLLM-style server; the usual numbers when nothing answers); a number set by hand wins.
 */
class ModelSlotsTest {

    String home; HttpServer server, second;

    @AfterEach void restore() { ModelSlots.OVERRIDE = null; ModelSlots.forget(); if (home != null) { System.setProperty("user.home", home); Config.invalidate(); } if (server != null) server.stop(0); if (second != null) second.stop(0); }

    @Test
    void theRuleGivesUpToThreeReadersAndOneRunPerThreeSlots() {
        int[][] want = {{1, 1, 1}, {2, 1, 2}, {3, 1, 3}, {4, 1, 3}, {5, 1, 3}, {6, 2, 3}, {8, 2, 3}, {12, 4, 3}, {32, 10, 3}};
        for (int[] w : want) { ModelSlots.Plan p = ModelSlots.plan(w[0], "test"); assertEquals(w[1], p.runs(), w[0] + " slots: runs"); assertEquals(w[2], p.readers(), w[0] + " slots: readers"); }
        ModelSlots.Plan none = ModelSlots.plan(0, "nothing answered");
        assertFalse(none.known()); assertEquals(1, none.runs()); assertEquals(3, none.readers());
        assertEquals("1 run at a time, 3 readers each (the model server answers 4 calls at once, llama.cpp says so)", ModelSlots.plan(4, "llama.cpp says so").describe());
        assertEquals("2 runs at a time, 3 readers each (the model server answers 8 calls at once, a guess)", ModelSlots.plan(8, "a guess").describe());
        assertEquals("1 run at a time, 3 readers each (nothing answered)", none.describe());
    }

    private String serve(String propsAt, int slots, boolean ollama, boolean models) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            String p = x.getRequestURI().getPath(), body; int code = 404;
            if (p.equals(propsAt)) { body = "{\"default_generation_settings\":{\"n_ctx\":32768},\"total_slots\":" + slots + "}"; code = 200; }
            else if (ollama && p.equals("/api/tags")) { body = "{\"models\":[]}"; code = 200; }
            else if (models && p.equals("/v1/models")) { body = "{\"data\":[{\"id\":\"m\"}]}"; code = 200; }
            else body = "{\"error\":\"no\"}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(code, b.length);
            try (OutputStream o = x.getResponseBody()) { o.write(b); }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void theServerIsAskedAndGuessedWhenItDoesNotSay() throws Exception {
        ModelSlots.Plan p = ModelSlots.probe(serve("/props", 4, false, true), "qwen3.8-27b");
        assertEquals(4, p.slots()); assertEquals("llama.cpp says so", p.source()); assertEquals(1, p.runs()); assertEquals(3, p.readers());
        server.stop(0);
        p = ModelSlots.probe(serve("/upstream/qwen3.8-27b/props", 6, false, true), "qwen3.8-27b");
        assertEquals(6, p.slots()); assertEquals(2, p.runs());   // through llama-swap, by the model's name
        server.stop(0);
        p = ModelSlots.probe(serve("/none", 0, true, false), "m");
        assertEquals(1, p.slots()); assertTrue(p.source().startsWith("a guess for Ollama"), p.source()); assertEquals(1, p.readers());
        server.stop(0);
        p = ModelSlots.probe(serve("/none", 0, false, true), "m");
        assertEquals(8, p.slots()); assertEquals("a guess for a vLLM-style server", p.source()); assertEquals(2, p.runs());
        server.stop(0);
        p = ModelSlots.probe("http://127.0.0.1:1", "m");
        assertFalse(p.known()); assertEquals(1, p.runs()); assertEquals(3, p.readers()); assertTrue(p.source().startsWith("the model server did not answer"));
        assertFalse(ModelSlots.probe("", "m").known());
    }

    @Test
    void aNumberSetByHandWinsAndAutoFollowsTheServer(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.toString()); Config.invalidate();
        Files.createDirectories(tmp.resolve(".researchzosho"));
        Files.writeString(tmp.resolve(".researchzosho/config"), "job.workers = 2\nresearch.workers = auto\n"); Config.invalidate();
        ModelSlots.OVERRIDE = ModelSlots.plan(4, "llama.cpp says so");
        assertEquals(2, ModelSlots.runs(), "set by hand"); assertEquals(3, ModelSlots.readers(), "auto: the rule");
        assertTrue(ModelSlots.describe().startsWith("2 runs at a time, 3 readers each (set by hand; the model server answers 4 calls at once, which would give 1 run and 3 readers)"), ModelSlots.describe());
        Files.writeString(tmp.resolve(".researchzosho/config"), "research.workers = 2\n"); Config.invalidate();
        assertEquals(1, ModelSlots.runs()); assertEquals(2, ModelSlots.readers());
        assertEquals(2, ResearchSettings.workers(), "the live setting reads the same");
        Files.writeString(tmp.resolve(".researchzosho/config"), ""); Config.invalidate();
        assertEquals("1 run at a time, 3 readers each (the model server answers 4 calls at once, llama.cpp says so); set job.workers or research.workers to choose", ModelSlots.describe());
        ModelSlots.OVERRIDE = ModelSlots.plan(1, "a guess for Ollama");
        assertEquals(1, ModelSlots.readers()); assertEquals(1, Jobs.workersNow());
        assertTrue(ModelSlots.auto(null) && ModelSlots.auto(" ") && ModelSlots.auto("Auto") && !ModelSlots.auto("2"));
    }

    @Test
    void severalDrivesAddUpAndEachCarriesItsShare(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.toString()); Config.invalidate();
        Files.createDirectories(tmp.resolve(".researchzosho"));
        String a = serve("/props", 8, false, true); HttpServer first = server;
        String b = serve("/none", 0, true, false); second = server; server = first;
        Files.writeString(tmp.resolve(".researchzosho/config"), "job.drives = " + a + "," + b + "\n"); Config.invalidate();
        ModelSlots.Fleet f = ModelSlots.current();
        assertEquals(List.of(a, b), f.drives()); assertEquals(9, f.slots()); assertTrue(f.known() && f.allKnown());
        assertEquals(3, f.runs(), "two runs on the eight-slot server and one on the one-slot server");
        assertEquals(List.of(a, a, b), f.lanes(), "the workers take the drives by their slots");
        assertEquals(List.of(a, a, b), ModelSlots.lanes("http://127.0.0.1:1"), "the service's own drive counts for nothing while drives are listed");
        assertEquals(3, f.readers(a)); assertEquals(1, f.readers(b)); assertEquals(1, f.readers(b + "/"), "the address with a trailing slash is the same server");
        assertEquals(3, f.readers("http://nowhere:1"), "a server not in the fleet: the most any allows");
        assertEquals("3 runs at a time, 3 readers each (2 model servers answer 9 calls at once between them: 8 calls at " + a + ", llama.cpp says so; 1 call at " + b
                + ", a guess for Ollama, which answers one call at a time unless OLLAMA_NUM_PARALLEL says more)", f.describe());
        assertEquals(3, ModelSlots.runs()); assertEquals(3, Jobs.workersNow()); assertEquals(1, ModelSlots.readers(b)); assertEquals(1, ResearchSettings.workers(b)); assertEquals(3, ResearchSettings.workers());
        assertTrue(ModelSlots.describe().startsWith("3 runs at a time, 3 readers each (2 model servers answer 9 calls"), ModelSlots.describe());
        Files.writeString(tmp.resolve(".researchzosho/config"), "job.drives = " + a + "," + b + "\njob.workers = 2\n"); Config.invalidate();
        assertEquals(2, ModelSlots.runs());
        assertEquals("2 runs at a time, 3 readers each (set by hand; 2 model servers answer 9 calls at once, which would give 3 runs and 3 readers)", ModelSlots.describe());
        // nothing listed: the library's own drive, as before, and the service's own drive is the one lane, unprobed
        Files.writeString(tmp.resolve(".researchzosho/config"), "drive = " + a + "\n"); Config.invalidate();
        f = ModelSlots.current();
        assertEquals(List.of(a), f.drives()); assertEquals(8, f.slots()); assertEquals(2, f.runs()); assertEquals(List.of(a, a), f.lanes());
        assertEquals(ModelSlots.plan(8, "llama.cpp says so").describe(), f.describe());
        assertEquals(List.of("http://127.0.0.1:1"), ModelSlots.lanes("http://127.0.0.1:1"), "one lane, the drive given, and nothing asked of it");
        // a server that does not answer keeps one lane and is asked again within a minute
        Files.writeString(tmp.resolve(".researchzosho/config"), "job.drives = " + a + ",http://127.0.0.1:1\n"); Config.invalidate();
        f = ModelSlots.current();
        assertEquals(3, f.runs()); assertEquals(List.of(a, a, "http://127.0.0.1:1"), f.lanes()); assertTrue(f.known()); assertFalse(f.allKnown());
        assertEquals(3, f.readers("http://127.0.0.1:1"), "unknown: the usual three");
        assertTrue(f.describe().startsWith("3 runs at a time, 3 readers each (2 model servers answer 8 calls at once between them: 8 calls at " + a + ", llama.cpp says so; unknown at http://127.0.0.1:1, the model server did not answer"), f.describe());
    }
}
