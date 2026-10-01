package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `model use <address> [<model>]` switches the library's model while the service runs: the server is asked what it serves and the model
 * is asked to reply before any setting changes, and the service's workers take the setting at the start of each job.
 */
class ModelUseTest {

    static final ObjectMapper M = new ObjectMapper();

    /** A probe with a server at {@code base} serving {@code ids}, whose models reply unless named in {@code silent}. */
    static Setup.Probe server(String base, List<String> ids, List<String> silent, List<String> asked) {
        return new Setup.Probe() {
            @Override public List<String> models(String b, String key) { asked.add("models " + b); return b.equals(base) ? ids : null; }
            @Override public String chat(String b, String model, String key) { asked.add("chat " + model); return silent.contains(model) ? "!the server answered 500" : "ready"; }
            @Override public boolean embeds(String b, String key) { return false; }
            @Override public boolean searxng(String b) { return false; }
            @Override public boolean brave(String key) { return false; }
        };
    }

    static String run(String address, String name, Setup.Probe probe, int[] rc) throws Exception {
        var out = new ByteArrayOutputStream();
        rc[0] = ModelServer.use(address, name, probe, new PrintStream(out, true));
        return out.toString();
    }

    @Test
    void theSettingChangesOnlyWhenTheServerAndTheModelAnswer(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        try {
            Config.set("RESEARCHZOSHO_DRIVE", "http://before:8211");
            Config.set("RESEARCHZOSHO_MODEL", "old-model");
            List<String> asked = new ArrayList<>();
            int[] rc = {-1};
            Setup.Probe probe = server("http://box:8211", List.of("qwen3.8-27b", "gemma-4-e4b"), List.of("gemma-4-e4b"), asked);
            // nothing answers there
            String said = run("http://nowhere:1", null, probe, rc);
            assertEquals(1, rc[0]); assertTrue(said.contains("No model server answers at http://nowhere:1. Nothing was changed."), said);
            // several models and none named: the choices, as commands
            said = run("http://box:8211/v1", null, probe, rc);
            assertEquals(2, rc[0]); assertTrue(said.contains("researchzosho model use http://box:8211 qwen3.8-27b"), "the address without /v1: " + said);
            // a model the server does not serve
            said = run("http://box:8211", "llama-70b", probe, rc);
            assertEquals(1, rc[0]); assertTrue(said.contains("does not serve a model called llama-70b. It serves: qwen3.8-27b, gemma-4-e4b"), said);
            // a model that does not reply
            said = run("http://box:8211", "gemma-4-e4b", probe, rc);
            assertEquals(1, rc[0]); assertTrue(said.contains("the model did not reply") && said.contains("Nothing was changed."), said);
            assertEquals("http://before:8211", Config.get("RESEARCHZOSHO_DRIVE"), "nothing changed so far");
            assertEquals("old-model", Config.get("RESEARCHZOSHO_MODEL"));
            // the model replies: both settings written, and the service's next job reads them
            said = run("http://box:8211", "qwen3.8-27b", probe, rc);
            assertEquals(0, rc[0], said);
            assertEquals("http://box:8211", Config.get("RESEARCHZOSHO_DRIVE"));
            assertEquals("qwen3.8-27b", Config.get("RESEARCHZOSHO_MODEL"));
            assertTrue(said.contains("The library now uses qwen3.8-27b at http://box:8211. The service takes it for its next research run"), said);
            // a server with one model: that one, unnamed
            Setup.Probe one = server("http://solo:8080", List.of("only-model"), List.of(), asked);
            run("http://solo:8080", null, one, rc);
            assertEquals(0, rc[0]); assertEquals("only-model", Config.get("RESEARCHZOSHO_MODEL"));
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void aWorkerTakesTheDriveTheLibraryIsSetToWhenEachJobStarts(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        String[] now = {"http://first:8211"};
        Jobs jobs = new Jobs(store, (j, d) -> d, () -> Jobs.drives(now[0]), 1);
        assertEquals("http://first:8211", jobs.driveOf(0));
        now[0] = "http://second:8211";
        assertEquals("http://second:8211", jobs.driveOf(0), "read again, not kept from the start");
        assertEquals(List.of("http://second:8211"), jobs.drives());
    }

    @Test
    void aRestartWithNoServiceInstalledSaysSoAndDoesNothing(@TempDir Path home) throws Exception {
        var plan = Service.plan(Service.Os.linux, "researchzosho", "127.0.0.1", LibrarianDaemon.DEFAULT_PORT, 3, home);
        var out = new ByteArrayOutputStream();
        assertEquals(1, Service.run("restart", plan, new PrintStream(out, true)));
        assertTrue(out.toString().contains("The service is not installed, so there is nothing to restart. To install it: researchzosho service install"), out.toString());
    }

    @Test
    void theRestartNamesTheResearchRunGoingInTheLibraryAtItsDefaultPlace(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        try {
            assertNull(LibrarianCli.runningResearch(), "no library: nothing to name");
            LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
            Jobs jobs = new Jobs(store, x -> "");
            String id = jobs.submit("research", "did:key:someone", M.createObjectNode().put("question", "a placeholder question"));
            assertNull(LibrarianCli.runningResearch(), "a queued run is not stopped by a restart");
            Path file = jobs.dir().resolve("active").resolve(id + ".json");
            ObjectNode j = (ObjectNode) M.readTree(file.toFile());
            j.put("state", "running").put("started_at", Instant.now().minus(Duration.ofMinutes(12)).toString());
            Files.writeString(file, M.writeValueAsString(j));
            assertEquals(id + ", 12 min in", LibrarianCli.runningResearch());
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void anInstallThatChangesTheSettingsRestartsTheServiceOntoThem(@TempDir Path home) throws Exception {
        // a second Linux box, 2026-09-29: reinstalled with --host 0.0.0.0, the unit said so, and the running server kept listening on 127.0.0.1
        var loopback = Service.plan(Service.Os.linux, "researchzosho", "127.0.0.1", LibrarianDaemon.DEFAULT_PORT, 3, home);
        var lan = Service.plan(Service.Os.linux, "researchzosho", "0.0.0.0", LibrarianDaemon.DEFAULT_PORT, 3, home);
        assertFalse(Service.changes(loopback), "nothing installed yet");
        Files.createDirectories(loopback.definition().getParent());
        Files.writeString(loopback.definition(), loopback.text());
        assertFalse(Service.changes(loopback), "the same settings again");
        assertEquals(loopback.install(), Service.installSteps(loopback, false), "unchanged: enable --now, which leaves a running service alone");
        assertTrue(Service.changes(lan), "another host");
        var steps = Service.installSteps(lan, true);
        assertEquals(List.of("systemctl", "--user", "restart", "researchzosho"), steps.get(steps.size() - 1), "changed: restarted onto the new unit");
        var mac = Service.plan(Service.Os.macos, "researchzosho", "0.0.0.0", LibrarianDaemon.DEFAULT_PORT, 3, home);
        assertEquals(mac.install(), Service.installSteps(mac, true), "macOS unloads and loads the agent anyway");
    }
}
