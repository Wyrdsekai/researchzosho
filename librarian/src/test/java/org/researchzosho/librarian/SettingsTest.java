package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The settings surface (0.5.5): one list, read and written through the protocol by the command, the page, the chat and a program. */
class SettingsTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String PERSON = "\"patron\":{\"did\":\"person\",\"name\":\"\",\"runtime\":\"cli\"}";

    @TempDir Path home;
    String realHome;

    // settings are written to the config file under user.home: a scratch one here, never the developer's
    @BeforeEach void scratchHome() { realHome = System.getProperty("user.home"); System.setProperty("user.home", home.toString()); Config.invalidate(); }
    @AfterEach void restoreHome() { System.setProperty("user.home", realHome); Config.invalidate(); }

    static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

    @Test
    void bestJudgementsKnobsAreSettingsAsTheGuideSays() throws Exception {
        assertEquals("RESEARCHZOSHO_AUTO", Settings.named("auto").key());
        assertEquals("RESEARCHZOSHO_AUTO_WAIT_MINUTES", Settings.named("auto.wait_minutes").key());
        assertEquals("RESEARCHZOSHO_AUTO_MAX_DOLLARS", Settings.named("auto.max_dollars").key());
        assertEquals("RESEARCHZOSHO_AUTO_LOCAL_ONLY", Settings.named("auto.local_only").key());
        assertEquals("", Settings.refuse(Settings.named("auto"), "off"));
        assertFalse(Settings.refuse(Settings.named("auto"), "maybe").isEmpty(), "on or off");
        Settings.set(Settings.named("auto"), "off");
        assertFalse(Config.isOn("RESEARCHZOSHO_AUTO", true), "settings set auto off reaches the knob best judgement reads");
    }

    @Test
    void namesAreFoundWithOrWithoutThePrefixAndValuesAreChecked() {
        assertEquals("RESEARCHZOSHO_PODCASTINDEX_KEY", Settings.named("podcastindex_key").key());
        assertEquals("RESEARCHZOSHO_WHISPER", Settings.named("RESEARCHZOSHO_WHISPER").key());
        assertEquals("RESEARCHZOSHO_JOB_WORKERS", Settings.named("job.workers").key());
        assertNull(Settings.named("no_such_thing"));
        assertEquals("", Settings.refuse(Settings.named("whisper"), "http://gpu-box:18890"));
        assertTrue(Settings.refuse(Settings.named("whisper"), "gpu-box:18890").contains("http://"));
        assertTrue(Settings.refuse(Settings.named("transcribe_minutes"), "two hours").contains("number"));
        assertTrue(Settings.refuse(Settings.named("update"), "sometimes").contains("check, auto, off"));
        assertEquals("", Settings.refuse(Settings.named("update"), "AUTO"));
        assertEquals("set (ends with …xyz)", Settings.shown(Settings.named("brave_key"), "abcxyz"));
        assertEquals("http://x", Settings.shown(Settings.named("searxng"), "http://x"));
        assertEquals("RESEARCHZOSHO_MARKET", Settings.named("market").key());
        assertTrue(Settings.refuse(Settings.named("market"), "Japan").contains("two letters"));
        assertEquals("", Settings.refuse(Settings.named("market"), "jp"));
    }

    @Test
    void theMarketIsTheCountryThePersonBuysInUpperCaseOrTheMachinesOwn() throws Exception {
        String own = java.util.Locale.getDefault().getCountry();
        assertEquals(own != null && own.matches("^[A-Z]{2}$") ? own : "US", Settings.market(), "unset: this machine's country");
        Settings.set(Settings.named("market"), "jp");
        assertEquals("JP", Config.get("RESEARCHZOSHO_MARKET")); assertEquals("JP", Settings.market());
    }

    @Test
    void theProtocolListsMaskedAndWritesChecked(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode set = p.settings(args("{\"op\":\"set\",\"name\":\"podcastindex_key\",\"value\":\"ABCDEFGHIJKLMNOPQRST\"," + PERSON + "}"));
        assertTrue(set.path("set").asBoolean()); assertEquals("set (ends with …RST)", set.path("value").asText());
        assertEquals("ABCDEFGHIJKLMNOPQRST", Config.get("RESEARCHZOSHO_PODCASTINDEX_KEY"));
        assertTrue(Config.userConfigPath().startsWith(home), "written under the scratch home, never the developer's: " + Config.userConfigPath());
        JsonNode list = p.settings(args("{\"op\":\"list\"," + PERSON + "}"));
        JsonNode key = null;
        for (JsonNode s : list.path("settings")) if (s.path("key").asText().equals("RESEARCHZOSHO_PODCASTINDEX_KEY")) key = s;
        assertNotNull(key); assertTrue(key.path("set").asBoolean()); assertEquals("set (ends with …RST)", key.path("value").asText(), "never the key itself");
        assertFalse(list.toString().contains("ABCDEFGHIJKLMNOPQRST"), "the listing never carries a secret");
        assertTrue(list.path("config_file").asText().endsWith("config"));
        ProtocolError bad = assertThrows(ProtocolError.class, () -> p.settings(args("{\"op\":\"set\",\"name\":\"whisper\",\"value\":\"gpu-box\"," + PERSON + "}")));
        assertEquals("invalid_args", bad.code); assertTrue(bad.getMessage().contains("http://"), bad.getMessage());
        ProtocolError unknown = assertThrows(ProtocolError.class, () -> p.settings(args("{\"op\":\"set\",\"name\":\"colour\",\"value\":\"blue\"," + PERSON + "}")));
        assertTrue(unknown.getMessage().contains("No setting is named colour"), unknown.getMessage());
        ObjectNode unset = p.settings(args("{\"op\":\"unset\",\"name\":\"podcastindex_key\"," + PERSON + "}"));
        assertFalse(unset.path("set").asBoolean());
        assertTrue(Config.get("RESEARCHZOSHO_PODCASTINDEX_KEY") == null || Config.get("RESEARCHZOSHO_PODCASTINDEX_KEY").isBlank());
        ObjectNode n = p.settings(args("{\"op\":\"set\",\"name\":\"transcribe_episodes\",\"value\":\"3\"," + PERSON + "}"));
        assertEquals("3", n.path("value").asText());
        // several model servers for the runs: addresses with commas between, each one checked
        p.settings(args("{\"op\":\"set\",\"name\":\"job_drives\",\"value\":\"http://127.0.0.1:8081, http://127.0.0.1:8082\"," + PERSON + "}"));
        assertEquals(List.of("http://127.0.0.1:8081", "http://127.0.0.1:8082"), Jobs.listed(), "the runs' drives, as the queue reads them");
        ProtocolError half = assertThrows(ProtocolError.class, () -> p.settings(args("{\"op\":\"set\",\"name\":\"job_drives\",\"value\":\"http://127.0.0.1:8081,gpu-box\"," + PERSON + "}")));
        assertTrue(half.getMessage().contains("several with commas"), half.getMessage());
        p.settings(args("{\"op\":\"unset\",\"name\":\"job_drives\"," + PERSON + "}"));
        assertTrue(Jobs.listed().isEmpty());
    }

    @Test
    void aReaderCannotChangeSettingsAndTheToolIsOfferedToProgramsAndTheChat(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        Patrons.setDefault(store, Patrons.Level.read);   // readers may read; settings need write
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.settings(args("{\"op\":\"list\",\"patron\":{\"did\":\"did:key:zReader\",\"name\":\"r\",\"runtime\":\"x\"}}")));
        assertEquals("forbidden", e.code);
        boolean mcp = false;
        for (JsonNode t : McpServer.allTools()) if (t.path("name").asText().equals("library_settings")) mcp = true;
        assertTrue(mcp, "the MCP server offers library_settings");
        assertTrue(Librarian.TOOLS.contains("library_settings"), "the chat may use it");
    }

    @Test
    void thePageIsServedAndTheRouteAnswers(@TempDir Path tmp) throws Exception {
        assertTrue(Pages.isPage("/settings"), "0.5.5 left the page off the list the service serves, so it answered not found");
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            HttpResponse<String> page = c.send(HttpRequest.newBuilder(URI.create(base + "/settings")).build(), HttpResponse.BodyHandlers.ofString());
            assertNotEquals(404, page.statusCode(), page.body());
            assertFalse(page.body().contains("not found"), page.body());
            HttpResponse<String> route = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/settings")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"list\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertNotEquals(404, route.statusCode(), route.body());
            assertFalse(route.body().contains("route /v1/settings"), "the route exists like the other tools': " + route.body());
        } finally { d.stop(); }
    }
}
