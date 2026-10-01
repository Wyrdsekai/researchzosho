package org.researchzosho.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field with a fixed set of values declares it as an enum, so a model cannot fill it with prose. A companion model wrote
 * {@code sources: "community forums, bug trackers, news articles"} into library_research and was refused (2026-10-01); the allowed
 * values had been in the description only.
 */
class McpEnumSchemaTest {

    private static final Pattern CHOICES = Pattern.compile("\\b[a-z_]+ \\| [a-z_]+\\b|\\b[a-z]+ \\(default\\), [a-z]+, or [a-z]+\\b|\\b[a-z]+, [a-z]+, [a-z]+, [a-z]+ or [a-z]+\\b");

    private static JsonNode tools() {
        return McpServer.handle("tools/list", new ObjectMapper().createObjectNode()).get("tools");
    }

    @Test
    void everyFieldWhoseDescriptionListsItsValuesDeclaresThemAsAnEnum() {
        List<String> missing = new ArrayList<>();
        int enums = 0;
        for (JsonNode tool : tools()) {
            for (Map.Entry<String, JsonNode> e : tool.path("inputSchema").path("properties").properties()) {
                JsonNode spec = e.getValue();
                if (!"string".equals(spec.path("type").asText())) continue;
                String desc = spec.path("description").asText("");
                boolean listsValues = CHOICES.matcher(desc).find() && !desc.contains("comma-separated");
                if (spec.has("enum")) {
                    enums++;
                    for (JsonNode v : spec.get("enum")) {
                        assertTrue(Pattern.compile("\\b" + Pattern.quote(v.asText()) + "\\b").matcher(desc).find(),
                                tool.path("name").asText() + "." + e.getKey() + ": the enum value " + v + " is explained in the description");
                    }
                } else if (listsValues) {
                    missing.add(tool.path("name").asText() + "." + e.getKey() + ": " + desc);
                }
            }
        }
        assertTrue(missing.isEmpty(), "values in the description only:\n" + String.join("\n", missing));
        assertTrue(enums >= 25, "the fixed-choice fields carry enums (" + enums + ")");
    }

    @Test
    void theResearchToolsSourcesAreTheThreeTheServerAccepts() {
        for (JsonNode tool : tools()) {
            if (!tool.path("name").asText().equals("library_research")) continue;
            List<String> values = new ArrayList<>();
            for (JsonNode v : tool.path("inputSchema").path("properties").path("sources").path("enum")) values.add(v.asText());
            assertEquals(List.of("both", "shelves", "web"), values);
            values.clear();
            for (JsonNode v : tool.path("inputSchema").path("properties").path("mode").path("enum")) values.add(v.asText());
            assertEquals(List.of("broad", "depth"), values);
            return;
        }
        throw new AssertionError("library_research is not among the tools");
    }
}
