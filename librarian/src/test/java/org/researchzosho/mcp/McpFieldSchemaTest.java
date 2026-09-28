package org.researchzosho.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every tool that files a research run takes the field that run is to be, and says so in its schema and in the protocol's table: the
 * protocol accepts `field` on all of them, and a program reads what it may send from those two places.
 */
class McpFieldSchemaTest {

    static final List<String> FILE_RUNS = List.of("library_research", "library_items", "library_questions", "library_absorb", "library_check", "library_meeting");

    @Test
    void everyToolThatFilesARunListsTheField() throws Exception {
        JsonNode tools = McpServer.handle("tools/list", new ObjectMapper().createObjectNode()).get("tools");
        for (String name : FILE_RUNS) {
            JsonNode tool = null;
            for (JsonNode t : tools) if (t.path("name").asText().equals(name)) tool = t;
            assertNotNull(tool, name);
            assertTrue(tool.path("inputSchema").path("properties").has("field"), name + " takes field in its schema");
        }
        Path doc = null;
        for (String c : new String[]{"../docs/public/LIBRARY_PROTOCOL.md", "docs/public/LIBRARY_PROTOCOL.md", "../docs/LIBRARY_PROTOCOL.md", "docs/LIBRARY_PROTOCOL.md"}) if (Files.exists(Path.of(c))) { doc = Path.of(c); break; }
        assertNotNull(doc, "LIBRARY_PROTOCOL.md not found from " + Path.of("").toAbsolutePath());
        String text = Files.readString(doc);
        for (String name : FILE_RUNS) {
            String row = text.lines().filter(l -> l.startsWith("| `" + name + "` |")).findFirst().orElse("");
            assertTrue(row.contains("`field`") || row.contains("`field?`"), name + ": the protocol's table lists field: " + row);
            if (!name.equals("library_research")) assertTrue(row.contains("suggestion"), name + ": the protocol's table says the result can carry a suggestion");
        }
        String job = text.lines().filter(l -> l.startsWith("| `library_job` |")).findFirst().orElse("");
        assertTrue(job.contains("offered"), "the job states include offered: " + job);
    }
}
