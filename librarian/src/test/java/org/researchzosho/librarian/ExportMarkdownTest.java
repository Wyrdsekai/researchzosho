package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A report as Markdown through library_export: the same document the PDF and the Word file hold, as text, kept under catalog/exports/. */
class ExportMarkdownTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void aReportIsExportedAsMarkdown(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String id = ReportExportTest.report(store);
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.put("id", id); a.put("format", "md");
        a.putObject("patron").put("did", "person").put("name", "the person").put("runtime", "local");
        ObjectNode r = p.export(a);
        assertEquals("md", r.path("format").asText(), r.toString());
        assertEquals(id + ".md", r.path("file").asText(), r.toString());
        assertEquals("/exports/" + id + ".md", r.path("url").asText(), r.toString());
        assertTrue(r.path("summary").asText().contains("Markdown file"), r.toString());
        String text = Files.readString(Path.of(r.path("path").asText()), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("# " + ReportExportTest.QUESTION + "\n\n*Report I-0001"), text);
        for (String want : new String[]{"## Question", "Two listings fit [50]", "## How each point was checked", "## Claims", "108 Example St listing details — accepted (F-0001)", "## Sources", "[2] Cambridge market"})
            assertTrue(text.contains(want), "the file says \"" + want + "\": " + text);
        for (String not : new String[]{"secret working notes", "hidden evidence row", "Sources cited"}) assertFalse(text.contains(not), "the run's working stays out: " + not);
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length, r.path("bytes").asInt());
        // markdown is the same format by its long name; a format that is none of the three is refused naming them
        a.put("format", "markdown");
        assertEquals(id + ".md", p.export(a).path("file").asText());
        a.put("format", "txt");
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.export(a));
        assertEquals("invalid_args", e.code); assertTrue(e.getMessage().contains("md (Markdown)"), e.getMessage());
        assertEquals("text/markdown; charset=utf-8", Export.contentType(Export.MARKDOWN));
    }
}
