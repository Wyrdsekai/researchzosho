package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The settings surface (0.5.5): one list, read and written through the protocol by the command, the page, the chat and a program. */
class SettingsTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String PERSON = "\"patron\":{\"did\":\"person\",\"name\":\"\",\"runtime\":\"cli\"}";

    @AfterEach void clean() throws Exception { Config.set("RESEARCHZOSHO_PODCASTINDEX_KEY", ""); Config.set("RESEARCHZOSHO_WHISPER", ""); Config.set("RESEARCHZOSHO_TRANSCRIBE_EPISODES", ""); }

    static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

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
    }

    @Test
    void theProtocolListsMaskedAndWritesChecked(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode set = p.settings(args("{\"op\":\"set\",\"name\":\"podcastindex_key\",\"value\":\"ABCDEFGHIJKLMNOPQRST\"," + PERSON + "}"));
        assertTrue(set.path("set").asBoolean()); assertEquals("set (ends with …RST)", set.path("value").asText());
        assertEquals("ABCDEFGHIJKLMNOPQRST", Config.get("RESEARCHZOSHO_PODCASTINDEX_KEY"));
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
}
