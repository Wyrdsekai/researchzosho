package org.researchzosho.librarian;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** An entry as a file: Markdown as it is, and a PDF that reads back, Japanese included when a font is there. */
class ExportTest {

    private static Finding f(String id, String title, String body) {
        return new Finding(id, title, List.of("gears--cutting"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://museum.example/gears", "n/a", "the museum's page")), List.of(), null, body);
    }

    @Test
    void markdownAndPdf(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String body = "The museum says the **gears** were cut with files.\n\n- a dividing plate\n- 歯車は手で切られた (the gears were cut by hand)\n\n"
                + "A very long token to wrap: https://museum.example/a/very/long/path/that/goes/on/and/on/and/on/and/on/and/on/and/on/and/on/and/on/and/on/end\n";
        store.write(f("F-0001-gears", "The gears were cut by hand", body));

        Export.File md = Export.markdown(store, "F-0001-gears", null);
        assertEquals("F-0001-gears.md", md.name());
        String text = new String(md.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.startsWith("# The gears were cut by hand\n"), text);
        assertTrue(text.contains("## Sources") && text.contains("https://museum.example/gears — the museum's page"), text);
        assertTrue(text.contains("歯車は手で切られた"));

        Export.File pdf = Export.pdf(store, "F-0001-gears", null);
        assertEquals("F-0001-gears.pdf", pdf.name());
        assertTrue(new String(pdf.bytes(), 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("%PDF"));
        try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
            String got = new PDFTextStripper().getText(doc);
            assertTrue(got.contains("The gears were cut by hand"), got);
            assertTrue(got.contains("a dividing plate") && got.contains("Sources"), got);
            assertTrue(got.contains("page 1"), "the footer numbers the pages");
            // a box may have a Latin TrueType family and still no CJK font (GitHub's ubuntu runner): judge by the glyph
            boolean cjk = Export.Fonts.load(doc).canShow('歯');
            if (cjk) assertTrue(got.contains("歯車"), "with a CJK font on the box the Japanese survives: " + got);
            else assertTrue(got.contains("???"), "without one it degrades to question marks, not a crash: " + got);
        }
    }

    @Test
    void aReadingExportsWithItsCaveat(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(f("F-0001-gears", "The gears were cut by hand", "Body.\n"));
        Explain.Reading r = new Explain.Reading("F-0001-gears", "", Explain.Rung.beginner, "Someone cut each tooth with a file [F-0001-gears].", List.of(new Explain.Term("file", "a hand tool")), "shelves", 1, 0, Instant.now().toString(), true, null);
        Export.File md = Export.markdown(store, "F-0001-gears", r);
        assertEquals("F-0001-gears-beginner.md", md.name());
        String text = new String(md.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("A beginner reading of F-0001-gears") && text.contains("not a record") && text.contains("Words you may meet next") && text.contains("## The record it rests on"), text);
        assertThrows(ProtocolError.class, () -> Export.markdown(store, "F-9999-nope", null));
    }
}
