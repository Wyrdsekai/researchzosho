package org.researchzosho.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A hosted API takes a tool's name only as letters, digits, underscores and dashes: another MCP server's server.tool travels in such a form and comes back as it was. */
class ToolNamesTest {
    static final ObjectMapper M = new ObjectMapper();

    @Test
    void aDottedNameTravelsSafelyAndComesBack() throws Exception {
        ArrayNode tools = (ArrayNode) M.readTree("[{\"type\":\"function\",\"function\":{\"name\":\"notes.search_notes\"}},{\"type\":\"function\",\"function\":{\"name\":\"search\"}},"
                + "{\"type\":\"function\",\"function\":{\"name\":\"a-very-long-server-name.a_tool_whose_name_runs_on_and_on_past_what_a_hosted_api_takes\"}}]");
        ArrayNode history = (ArrayNode) M.readTree("[{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"notes.search_notes\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"name\":\"notes.search_notes\",\"content\":\"x\"}]");
        ObjectNode body = M.createObjectNode();
        body.set("tools", tools); body.set("messages", history);
        ToolNames.Wire w = ToolNames.of(tools);
        assertEquals("notes__search_notes", w.out().get("notes.search_notes"));
        String longWire = w.out().get("a-very-long-server-name.a_tool_whose_name_runs_on_and_on_past_what_a_hosted_api_takes");
        assertTrue(ToolNames.SAFE.matcher(longWire).matches() && longWire.length() <= 64, longWire);
        assertFalse(w.out().containsKey("search"), "a name a hosted API takes travels as it is");
        ToolNames.apply(body, w);
        assertEquals("notes__search_notes", body.path("tools").get(0).path("function").path("name").asText());
        assertEquals("notes__search_notes", body.path("messages").get(0).path("tool_calls").get(0).path("function").path("name").asText());
        assertEquals("notes__search_notes", body.path("messages").get(1).path("name").asText());
        assertEquals("notes.search_notes", tools.get(0).path("function").path("name").asText(), "the loop's own tools keep the library's names");
        assertEquals("notes.search_notes", history.get(0).path("tool_calls").get(0).path("function").path("name").asText(), "and so does its history");
        ObjectNode answer = (ObjectNode) M.readTree("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c2\",\"type\":\"function\",\"function\":{\"name\":\"notes__search_notes\",\"arguments\":\"{}\"}}]}");
        assertEquals("notes.search_notes", w.restore(answer).path("tool_calls").get(0).path("function").path("name").asText(), "the model's call comes back under the library's name");
        assertTrue(ToolNames.of((ArrayNode) M.readTree("[{\"function\":{\"name\":\"search\"}}]")).none(), "nothing to carry: nothing changes");
    }
}
