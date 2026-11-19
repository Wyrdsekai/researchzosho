package org.researchzosho.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every array in every tool's arguments says what it holds. gpt-oss's chat template (llama.cpp, --jinja) fails the whole request on an
 * array with no items: on 2026-11-18 library_images' images had none, and every chat turn on gpt-oss-20b, the model the program
 * recommends for a 16 GB card, answered "drive HTTP 500 … Function is not a bool value" before the model read a word.
 */
class McpArrayItemsTest {

    @Test
    void everyArrayInEveryToolSaysWhatItHolds() {
        List<String> without = new ArrayList<>();
        for (JsonNode t : McpServer.allTools()) walk(t.path("name").asText(), "", t.path("inputSchema"), without);
        assertTrue(without.isEmpty(), "arrays with no items: " + without);
    }

    static void walk(String tool, String path, JsonNode node, List<String> out) {
        if (!node.isObject()) return;
        if ("array".equals(node.path("type").asText()) && !node.has("items")) out.add(tool + path);
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) { var e = it.next(); walk(tool, path + "/" + e.getKey(), e.getValue(), out); }
    }
}
