package org.researchzosho.librarian;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An entry as a file: Markdown as it is, and a PDF that reads back, Japanese included when a font has it and said on the first page when
 * none does. The fonts come from a folder the test names (the suite names an empty one for every other test): a few glyphs of Droid Sans
 * Fallback (Apache License 2.0), the ones these tests write, kept as test/resources/fonts/cjk-sample.ttf.
 */
class ExportTest {

    private String fontsBefore;
    @BeforeEach void keep() { fontsBefore = System.getProperty("researchzosho.fonts"); }
    @AfterEach void restore() { if (fontsBefore == null) System.clearProperty("researchzosho.fonts"); else System.setProperty("researchzosho.fonts", fontsBefore); }

    /** A folder of fonts holding only the test's Japanese one. */
    static Path japaneseFont(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("fonts-" + System.nanoTime()));
        try (InputStream in = ExportTest.class.getResourceAsStream("/fonts/cjk-sample.ttf")) { Files.copy(in, dir.resolve("cjk-sample.ttf")); }
        return dir;
    }

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
        String text = new String(md.bytes(), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("# The gears were cut by hand\n"), text);
        assertTrue(text.contains("## Sources") && text.contains("https://museum.example/gears — the museum's page"), text);
        assertTrue(text.contains("歯車は手で切られた"));

        // a font that has the Japanese: it survives, and nothing is said about it
        System.setProperty("researchzosho.fonts", japaneseFont(tmp).toString());
        Export.File pdf = Export.pdf(store, "F-0001-gears", null);
        assertEquals("F-0001-gears.pdf", pdf.name());
        assertTrue(new String(pdf.bytes(), 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
        try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
            String got = new PDFTextStripper().getText(doc);
            assertTrue(got.contains("The gears were cut by hand"), got);
            assertTrue(got.contains("a dividing plate") && got.contains("Sources"), got);
            assertTrue(got.contains("page 1 of"), "the footer numbers the pages: " + got);
            assertTrue(got.contains("歯車は手で切られた"), "with a Japanese font the Japanese survives: " + got);
            assertFalse(got.contains("Not every character could be shown"), got);
        }
        // no font has it: question marks, and the first page says which characters, by their script and their code
        System.setProperty("researchzosho.fonts", Files.createDirectories(tmp.resolve("none")).toString());
        try (PDDocument doc = Loader.loadPDF(Export.pdf(store, "F-0001-gears", null).bytes())) {
            String first = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");   // the note wraps where the page's width says
            assertTrue(first.contains("???"), "the characters come out as question marks, never as boxes: " + first);
            assertTrue(first.contains("Not every character could be shown.") && first.contains("Chinese or Japanese characters (Han)") && first.contains("Japanese characters (kana)")
                    && first.contains("U+306F") && first.contains("Install a font for that language"), first);
        }
    }

    @Test
    void aReadingExportsWithItsCaveat(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(f("F-0001-gears", "The gears were cut by hand", "Body.\n"));
        Explain.Reading r = new Explain.Reading("F-0001-gears", "", Explain.Rung.beginner, "Someone cut each tooth with a file [F-0001-gears].", List.of(new Explain.Term("file", "a hand tool")), "shelves", 1, 0, Instant.now().toString(), true, null);
        Export.File md = Export.markdown(store, "F-0001-gears", r);
        assertEquals("F-0001-gears-beginner.md", md.name());
        String text = new String(md.bytes(), StandardCharsets.UTF_8);
        assertTrue(text.contains("A beginner reading of F-0001-gears") && text.contains("not a record") && text.contains("Words you may meet next") && text.contains("## The record it rests on"), text);
        assertThrows(ProtocolError.class, () -> Export.markdown(store, "F-9999-nope", null));
        Export.File word = Export.docx(store, "F-0001-gears", r);
        assertEquals("F-0001-gears-beginner.docx", word.name());
        assertEquals('P', (char) word.bytes()[0]);
    }
}
