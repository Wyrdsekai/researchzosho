package org.researchzosho.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.librarian.LibraryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The MCP surface of the library protocol: the nine tools, resources, structured results, error codes. */
class McpLibraryContractTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String REAL_HOME = System.getProperty("user.home");

    // LibraryStore.open() = RESEARCHZOSHO_LIBRARY (env, pinned off in the test task) else ~/codezaiku-library:
    // a fake user.home is the seam, as LibraryPushTest uses.
    @BeforeEach void point(@TempDir Path tmp) throws Exception {
        System.setProperty("user.home", tmp.toString());
        new LibraryStore(tmp.resolve("codezaiku-library")).init();
    }
    @AfterEach void restore() { System.setProperty("user.home", REAL_HOME); }

    private static JsonNode call(String name, String argsJson) throws Exception {
        return McpServer.handle("tools/call", M.readTree("{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}"));
    }

    @Test void theNineToolsAreListedWithAPatronArgument() throws Exception {
        JsonNode tools = McpServer.handle("tools/list", M.createObjectNode()).get("tools");
        Set<String> names = new HashSet<>();
        for (JsonNode t : tools) {
            names.add(t.get("name").asText());
            if (t.get("name").asText().startsWith("library_")) {
                assertTrue(t.get("inputSchema").get("properties").has("patron"), t.get("name").asText() + " takes a patron");
            }
        }
        for (String n : new String[]{"library_ask", "library_search", "library_get", "library_read", "library_established",
                "library_submit", "library_frontier", "library_subjects", "library_status", "library_changes", "library_research", "library_job"}) {
            assertTrue(names.contains(n), n);
        }
    }

    @Test void resultsAreStructuredAndCarryProvenance() throws Exception {
        JsonNode r = call("library_status", "{\"patron\":{\"did\":\"did:key:z1\",\"runtime\":\"wyrdsekai\"}}");
        assertFalse(r.get("isError").asBoolean());
        JsonNode s = r.get("structuredContent");
        assertEquals(org.researchzosho.librarian.LibraryProtocol.CONTRACT, s.get("contract").asText());
        assertTrue(s.get("library_id").asText().startsWith("lib_"));
        assertEquals(s.get("library_id").asText(), M.readTree(r.get("content").get(0).get("text").asText()).get("library_id").asText());
        JsonNode init = McpServer.handle("initialize", M.createObjectNode());
        assertTrue(init.get("capabilities").has("resources"));
    }

    @Test void protocolErrorsCarryAStableStringCode() throws Exception {
        String json = M.writeValueAsString(McpServer.handle("tools/list", M.createObjectNode()));
        assertNotNull(json);
        // a sourceless submission from a writing patron → JSON-RPC error with data.code = no_sources
        org.researchzosho.librarian.Patrons.set(LibraryStore.open(), "did:key:zW", "W", org.researchzosho.librarian.Patrons.Level.write);
        Exception e = assertThrows(Exception.class, () -> call("library_submit",
                "{\"claim\":\"A claim long enough to be a claim about something.\",\"sources\":[],\"patron\":{\"did\":\"did:key:zW\"}}"));
        assertEquals("RpcError", e.getClass().getSimpleName());
        assertTrue(e.getMessage().contains("at least one source"), e.getMessage());
        // and the envelope the wire carries
        JsonNode env = McpServer.envelopeFor(M.readTree("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"library_get\",\"arguments\":{\"id\":\"F-0000-nope\"}}}"));
        assertEquals("not_found", env.get("error").get("data").get("code").asText());
        assertEquals(-32004, env.get("error").get("code").asInt());
    }

    @Test void theLibrarianVerbServesOnlyLibraryTools() throws Exception {
        try {
            java.lang.reflect.Field f = McpServer.class.getDeclaredField("toolFilter");
            f.setAccessible(true);
            f.set(null, (java.util.function.Predicate<String>) n -> n.startsWith("library_"));
            JsonNode tools = McpServer.handle("tools/list", M.createObjectNode()).get("tools");
            for (JsonNode t : tools) assertTrue(t.get("name").asText().startsWith("library_"), t.get("name").asText());
            assertTrue(tools.size() >= 10);
            Exception e = assertThrows(Exception.class, () -> call("code", "{\"project\":\"/x\",\"goal\":\"y\"}"));
            assertTrue(e.getMessage().contains("unknown tool"));
        } finally {
            java.lang.reflect.Field f = McpServer.class.getDeclaredField("toolFilter");
            f.setAccessible(true);
            f.set(null, null);
        }
    }

    @Test void resourcesListAndRead() throws Exception {
        JsonNode list = McpServer.handle("resources/list", M.createObjectNode());
        assertEquals(0, list.get("resources").size());
        JsonNode t = McpServer.handle("resources/templates/list", M.createObjectNode());
        assertEquals("finding://{id}", t.get("resourceTemplates").get(0).get("uriTemplate").asText());
    }
}
