package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.DocUnits.Doc;
import org.researchzosho.librarian.DocUnits.Unit;
import org.researchzosho.tools.DocText;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A document's notes are never lost (0.7.3): a Word file's footnotes, endnotes and comments and its text boxes, read by the one reader of
 * Word files every caller uses, with each note's mark where the text has it and its words a unit of their own at the end; written out in
 * every format and through a translation; an EPUB's notes in an aside and a web page's notes in its footer kept.
 */
class DocNotesTest {

    static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue().getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        }
        return out.toByteArray();
    }

    static String entry(byte[] zip, String name) throws Exception {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) if (e.getName().equals(name)) return new String(z.readAllBytes(), StandardCharsets.UTF_8);
        }
        return "";
    }

    /** A Word file with a heading, a footnote, an endnote, a comment, a text box given twice (its choice and its fallback), and a running head. */
    static byte[] wordFile() throws Exception {
        Map<String, String> z = new LinkedHashMap<>();
        z.put("[Content_Types].xml", "<Types/>");
        z.put("word/styles.xml", "<w:styles " + W + "><w:style w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/></w:style></w:styles>");
        z.put("word/document.xml", "<w:document " + W + " xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\" xmlns:v=\"urn:schemas-microsoft-com:vml\"><w:body>"
                + "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>The town clock</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>The clock was wound every week.</w:t></w:r><w:r><w:footnoteReference w:id=\"1\"/></w:r><w:r><w:t xml:space=\"preserve\"> Its keeper lived in the hall.</w:t></w:r><w:r><w:endnoteReference w:id=\"2\"/></w:r></w:p>"
                + "<w:p><w:commentRangeStart w:id=\"0\"/><w:r><w:t>A third paragraph.</w:t></w:r><w:commentRangeEnd w:id=\"0\"/><w:r><w:commentReference w:id=\"0\"/></w:r></w:p>"
                + "<w:p><w:r><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing><wps:wsp><wps:txbx><w:txbxContent><w:p><w:r><w:t>Words in a box.</w:t></w:r></w:p></w:txbxContent></wps:txbx></wps:wsp></w:drawing></mc:Choice>"
                + "<mc:Fallback><w:pict><v:shape><v:textbox><w:txbxContent><w:p><w:r><w:t>Words in a box.</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></mc:Fallback></mc:AlternateContent></w:r><w:r><w:t>The anchoring paragraph.</w:t></w:r></w:p>"
                + "</w:body></w:document>");
        z.put("word/footnotes.xml", "<w:footnotes " + W + "><w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote><w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>"
                + "<w:footnote w:id=\"1\"><w:p><w:r><w:footnoteRef/></w:r><w:r><w:t xml:space=\"preserve\"> The key hung by the door.</w:t></w:r></w:p></w:footnote></w:footnotes>");
        z.put("word/endnotes.xml", "<w:endnotes " + W + "><w:endnote w:id=\"2\"><w:p><w:r><w:endnoteRef/></w:r><w:r><w:t xml:space=\"preserve\"> The hall was built in 1890.</w:t></w:r></w:p></w:endnote></w:endnotes>");
        z.put("word/comments.xml", "<w:comments " + W + "><w:comment w:id=\"0\" w:author=\"An editor\"><w:p><w:r><w:t>Check this date.</w:t></w:r></w:p></w:comment></w:comments>");
        z.put("word/header1.xml", "<w:hdr " + W + "><w:p><w:r><w:t>Running head of the file</w:t></w:r></w:p></w:hdr>");
        return zip(z);
    }

    @Test
    void aWordFilesNotesAndTextBoxesAreKeptWithTheirMarksWhereTheTextHasThem() throws Exception {
        Doc d = DocUnits.docx(wordFile(), "clock");
        List<String> got = d.units().stream().map(u -> u.kind() + ":" + u.text() + (u.kind().equals(DocUnits.NOTE) ? ":" + u.level() + ":" + u.extra() : "")).toList();
        assertEquals(List.of("heading:The town clock",
                "para:The clock was wound every week.[^1] Its keeper lived in the hall.[^2]",
                "para:A third paragraph.[^3]",
                "para:The anchoring paragraph.",
                "para:Words in a box.",
                "rule:",
                "note:The key hung by the door.:1:footnote", "note:The hall was built in 1890.:2:endnote", "note:Check this date.:3:comment"), got);
        assertTrue(d.translatable().stream().anyMatch(u -> u.kind().equals(DocUnits.NOTE)), "a note is translated");
        // the shelves read the same: Markdown's footnotes, the text box once, the running head left out as the page's furniture
        DocText.Doc t = DocText.convert(wordFile(), "clock.docx");
        assertEquals("docx", t.kind());
        assertTrue(t.text().contains("Its keeper lived in the hall.[^2]") && t.text().contains("[^1]: The key hung by the door.") && t.text().contains("[^3]: Check this date."), t.text());
        assertEquals(t.text().indexOf("Words in a box."), t.text().lastIndexOf("Words in a box."), "a text box's choice and its fallback are one box");
        assertFalse(t.text().contains("Running head"), t.text());
    }

    @Test
    void theNotesAreWrittenInEveryFormat() throws Exception {
        Doc d = DocUnits.docx(wordFile(), "clock");
        String md = new String(DocWriters.write(d, "md", "").bytes(), StandardCharsets.UTF_8);
        assertTrue(md.contains("week.[^1] Its keeper") && md.contains("[^2]: The hall was built in 1890."), md);
        String txt = new String(DocWriters.write(d, "txt", "").bytes(), StandardCharsets.UTF_8);
        assertTrue(txt.contains("week.[1] Its keeper") && txt.contains("[1] The key hung by the door."), txt);
        String word = entry(DocWriters.write(d, "docx", "").bytes(), "word/document.xml");
        assertTrue(word.contains("week.¹") && word.contains("The key hung by the door.") && !word.contains("[^"), "the translated Word file carries the notes, its marks raised: " + word);
        byte[] epub = DocWriters.write(d, "epub", "").bytes();
        String chapters = "";
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(epub))) { ZipEntry e; while ((e = z.getNextEntry()) != null) if (e.getName().endsWith(".xhtml")) chapters += new String(z.readAllBytes(), StandardCharsets.UTF_8); }
        assertTrue(chapters.contains("<sup><a epub:type=\"noteref\" href=\"#fn-1\">1</a></sup>") && chapters.contains("<aside epub:type=\"footnote\" id=\"fn-1\">"), chapters);
    }

    @BeforeEach void standInTheScorers() { TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null); Translation.SETUP_SAID = () -> ""; }
    @AfterEach void restore() {
        TranslationScores.SCORERS = TranslationScores::configured;
        Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; };
    }

    @Test
    void aTranslationCarriesTheNotesAndTheirMarks(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = tmp.resolve("clock.docx"); Files.write(in, wordFile());
        Translation.Outcome o = Translation.run(store, in, "ja", "md", "quick", null, null, TranslationTest.seats(new TranslationTest.Script()), null, () -> false);
        String out = Files.readString(o.output());
        assertTrue(out.contains("訳 The clock was wound every week.[^1] Its keeper lived in the hall.[^2]") && out.contains("[^1]: 訳 The key hung by the door.") && out.contains("[^3]: 訳 Check this date."), out);
        assertTrue(Translate.CITE.matcher("[^12]").matches(), "a note's mark is kept as it stands, as a citation is");
    }

    @Test
    void anEpubsNotesAndAWebPagesNotesInItsFooterAreKept() throws Exception {
        byte[] epub = zip(Map.of(
                "mimetype", "application/epub+zip",
                "OEBPS/content.opf", "<package><metadata><dc:title>Clocks</dc:title></metadata><manifest><item id=\"c1\" href=\"ch1.xhtml\"/></manifest><spine><itemref idref=\"c1\"/></spine></package>",
                "OEBPS/ch1.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body><p>The clock struck.<a epub:type=\"noteref\" href=\"#n1\">1</a></p>"
                        + "<footer><aside epub:type=\"footnote\" id=\"n1\"><p>It struck twelve.</p></aside></footer></body></html>"));
        DocText.Doc e = DocText.convert(epub, "clocks.epub");
        assertTrue(e.text().contains("It struck twelve."), e.text());
        assertTrue(DocUnits.cut(epub, "clocks.epub").units().stream().anyMatch(u -> u.text().equals("It struck twelve.")));
        String page = "<html><head><title>Clocks</title></head><body><nav>Home About</nav><main><article><p>" + "The town clock has stood in the square since the hall was built, and its keeper winds it every week. ".repeat(4)
                + "<sup><a href=\"#fn1\">1</a></sup></p></article></main><footer><section role=\"doc-endnotes\"><ol><li id=\"fn1\">The keeper is paid by the town.</li></ol></section></footer>"
                + "<footer><p>Site links and copyright</p></footer></body></html>";
        DocText.Doc h = DocText.convert(page.getBytes(StandardCharsets.UTF_8), "https://example.org/clocks");
        assertTrue(h.text().contains("The keeper is paid by the town."), "a page's notes in its footer are its own: " + h.text());
        assertFalse(h.text().contains("Site links") || h.text().contains("Home About"), "the site's own footer and navigation are not: " + h.text());
    }

    @Test
    void aNoteUnitsTextHasNoMarkOfItsOwn() {
        Unit n = new Unit(DocUnits.NOTE, "A note.", 4, -1, -1, false, "footnote");
        assertTrue(n.translated());
        assertTrue(DocWriters.markdown(new Doc("docx", "t", "en", List.of(new DocUnits.Chapter("", List.of(Unit.of(DocUnits.PARA, "Text.[^4]"), n))), Map.of(), Map.of())).contains("[^4]: A note."));
    }
}
