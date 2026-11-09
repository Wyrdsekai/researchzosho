package org.researchzosho.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.LibrarianDaemon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protocol document is wire text other programs pin to, so it is pinned to the code both ways: every tool the server lists is a
 * row of §3 and every row of §3 is a tool the server lists; every POST /v1 route the daemon serves is in §6 and every route §6 names
 * is served; every op a tool's schema declares is named in its row; and the research route's body names every field the research
 * tool takes. A tool, a route or an op added on one side without the other fails here, which is how the document drifted before.
 */
class ProtocolDocPinTest {

    static Path doc() {
        for (String c : new String[]{"../docs/public/LIBRARY_PROTOCOL.md", "docs/public/LIBRARY_PROTOCOL.md", "../docs/LIBRARY_PROTOCOL.md", "docs/LIBRARY_PROTOCOL.md"}) if (Files.exists(Path.of(c))) return Path.of(c);
        throw new AssertionError("LIBRARY_PROTOCOL.md not found from " + Path.of("").toAbsolutePath());
    }

    static String section(String text, String from, String to) {
        int a = text.indexOf(from), b = text.indexOf(to, a + 1);
        assertTrue(a >= 0 && b > a, "the document has " + from + " before " + to);
        return text.substring(a, b);
    }

    /** The tool rows of §3: name → the row's text. */
    static Map<String, String> toolRows(String text) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String line : section(text, "## 3. Tools", "## 4. Resources").split("\n")) {
            Matcher m = Pattern.compile("^\\| `(library_[a-z_]+)` \\|").matcher(line);
            if (m.find()) rows.put(m.group(1), line);
        }
        return rows;
    }

    /** The POST /v1 routes §6 names: the ones in a brace list, and the ones with a row of their own. */
    static Set<String> docRoutes(String text) {
        String six = section(text, "## 6. Transports", "## 7. Directories and peers");
        Set<String> out = new TreeSet<>();
        Matcher braces = Pattern.compile("POST /v1/\\{([^}]*)\\}").matcher(six);
        while (braces.find()) for (String one : braces.group(1).replace("\\|", "|").split("\\|")) if (!one.strip().isEmpty()) out.add(one.strip());
        Matcher one = Pattern.compile("POST /v1/([a-z_]+(?:/[a-z_{}]+)?)(?!/)").matcher(six);
        while (one.find()) out.add(one.group(1));
        return out;
    }

    @Test void everyToolIsARowOfTheTableAndEveryRowIsATool() throws Exception {
        String text = Files.readString(doc());
        Set<String> listed = new TreeSet<>();
        for (JsonNode t : McpServer.allTools()) listed.add(t.path("name").asText());
        Set<String> rows = new TreeSet<>(toolRows(text).keySet());
        Set<String> notInDoc = new TreeSet<>(listed); notInDoc.removeAll(rows);
        Set<String> notInCode = new TreeSet<>(rows); notInCode.removeAll(listed);
        assertEquals(Set.of(), notInDoc, "tools the server lists that §3 of LIBRARY_PROTOCOL.md has no row for");
        assertEquals(Set.of(), notInCode, "rows of §3 for tools the server does not list");
        assertTrue(listed.size() >= 66, "the tools (" + listed.size() + ")");
    }

    @Test void everyRouteTheDaemonServesIsInTheDocumentAndEveryRouteNamedIsServed() throws Exception {
        String text = Files.readString(doc());
        Set<String> served = new TreeSet<>(LibrarianDaemon.v1Routes()), named = docRoutes(text);
        Set<String> notInDoc = new TreeSet<>(served); notInDoc.removeAll(named);
        Set<String> notInCode = new TreeSet<>(named); notInCode.removeAll(served);
        assertEquals(Set.of(), notInDoc, "POST /v1 routes the daemon serves that §6 does not name");
        assertEquals(Set.of(), notInCode, "POST /v1 routes §6 names that the daemon does not serve");
        // and every tool's own route is among them
        for (JsonNode t : McpServer.allTools()) assertTrue(served.contains(t.path("name").asText().substring("library_".length())), t.path("name").asText() + " has its POST /v1 route");
    }

    @Test void everyOpAToolDeclaresIsNamedInItsRow() throws Exception {
        Map<String, String> rows = toolRows(Files.readString(doc()));
        List<String> missing = new ArrayList<>();
        for (JsonNode t : McpServer.allTools()) {
            String name = t.path("name").asText(), row = rows.getOrDefault(name, "");
            for (JsonNode v : t.path("inputSchema").path("properties").path("op").path("enum")) {
                if (!Pattern.compile("(?<![a-z_-])" + Pattern.quote(v.asText()) + "(?![a-z_-])").matcher(row).find()) missing.add(name + ": " + v.asText());
            }
        }
        assertEquals(List.of(), missing, "ops a tool's schema declares that its row of §3 does not name");
    }

    @Test void theResearchRouteNamesEveryFieldTheToolTakes() throws Exception {
        String text = Files.readString(doc());
        String row = section(text, "## 6. Transports", "## 7. Directories and peers").lines().filter(l -> l.startsWith("| `POST /v1/research`")).findFirst().orElse("");
        assertFalse(row.isEmpty(), "§6 has the POST /v1/research row");
        String body = row.split("\\|")[2];   // the body cell
        Set<String> fields = new LinkedHashSet<>();
        for (JsonNode t : McpServer.allTools()) if (t.path("name").asText().equals("library_research")) t.path("inputSchema").path("properties").fieldNames().forEachRemaining(fields::add);
        fields.remove("patron");
        List<String> missing = new ArrayList<>();
        for (String f : fields) if (!Pattern.compile("(?<![a-z_])" + Pattern.quote(f) + "(?![a-z_])").matcher(body).find()) missing.add(f);
        assertEquals(List.of(), missing, "fields library_research takes that the POST /v1/research body does not name: " + body);
        assertTrue(fields.containsAll(List.of("goal", "length", "citations", "shape", "field", "model", "quick", "sources", "collections", "allow")), fields.toString());
    }
}
