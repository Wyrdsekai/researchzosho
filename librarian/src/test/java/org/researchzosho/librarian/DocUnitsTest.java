package org.researchzosho.librarian;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.DocUnits.Doc;
import org.researchzosho.librarian.DocUnits.Unit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;

/** A document cut into its units in order, and written out again in the same structure: Markdown, Word, EPUB and a PDF's text. */
class DocUnitsTest {

    private String fontsBefore;
    @BeforeEach void keep() { fontsBefore = System.getProperty("researchzosho.fonts"); }
    @AfterEach void restore() { if (fontsBefore == null) System.clearProperty("researchzosho.fonts"); else System.setProperty("researchzosho.fonts", fontsBefore); }

    /** An invented note on an invented clock: a heading, paragraphs, a list with a nested item, a table, a code block, a quote, a rule, a picture. */
    static final String MARKDOWN = """
            ---
            author: an invented hand
            ---
            # The Saros dial

            The dial predicts eclipses over 223 lunar months, about 18 years. See https://example.org/saros for the drawing.

            ## What it shows

            - the month of the eclipse
            - the hour, as **bold** letters
              - a nested note
            1. the first glyph
            2. the second glyph

            | Glyph | Meaning |
            | --- | --- |
            | Σ | a lunar eclipse |
            | Η | a solar eclipse |

            > A quotation from the invented catalogue.

            ```
            code stays as it is
            ```

            ---

            ![the dial](dial.png)

            *The dial, drawn in 1902.*
            """;

    @Test
    void markdownIsCutByBlockAndWrittenBackBlockForBlock() throws Exception {
        Doc d = DocUnits.markdown(MARKDOWN, "saros");
        List<Unit> u = d.units();
        List<String> kinds = new ArrayList<>(); for (Unit x : u) kinds.add(x.kind() + (x.kind().equals(DocUnits.HEADING) ? x.level() : x.kind().equals(DocUnits.ITEM) ? "/" + x.level() + (x.ordered() ? "n" : "") : x.kind().equals(DocUnits.CELL) ? "/" + x.row() + (x.level() == 1 ? "h" : "") : ""));
        assertEquals(List.of("code", "heading1", "para", "heading2", "item/0", "item/0", "item/1", "item/0n", "item/0n", "cell/0h", "cell/0h", "cell/1", "cell/1", "cell/2", "cell/2", "para", "code", "rule", "image", "caption"), kinds, kinds.toString());
        assertEquals("The Saros dial", d.title()); assertEquals("en", d.lang()); assertEquals("md", d.format());
        assertEquals("front", u.get(0).extra()); assertEquals("quote", u.get(15).extra()); assertEquals("dial.png", u.get(18).extra());
        assertEquals("the hour, as **bold** letters", u.get(5).text()); assertEquals("a lunar eclipse", u.get(12).text());
        assertEquals(16, d.translatable().size(), "the front matter, the code, the rule and the picture carry no words to translate");
        assertTrue(d.chars() > 100);
        // written back: the same blocks, read again as the same units
        String again = DocWriters.markdown(d);
        assertEquals(u, DocUnits.markdown(again, "saros").units(), again);
        assertTrue(again.contains("| Glyph | Meaning |\n| --- | --- |\n| Σ | a lunar eclipse |") && again.contains("  - a nested note\n1. the first glyph\n2. the second glyph") && again.contains("> A quotation") && again.contains("![the dial](dial.png)") && again.contains("*The dial, drawn in 1902.*"), again);
        String plain = DocWriters.text(d);
        assertTrue(plain.contains("The Saros dial\n") && plain.contains("the hour, as bold letters") && plain.contains("Σ | a lunar eclipse") && !plain.contains("**"), plain);
        // the words of a unit with its marks off
        assertEquals("the hour, as bold letters", DocUnits.plainText("the hour, as **bold** letters"));
    }

    @Test
    void plainTextAndAPdfAreCutByParagraph(@TempDir Path tmp) throws Exception {
        String text = "The Saros dial\n\nThe dial predicts eclipses over 223 lunar\nmonths, about 18 years.\n\nWhat it shows\n\n- the month\n- the hour\n\nA last paragraph with https://example.org/saros in it.\n";
        Doc d = DocUnits.text(text, "notes", "txt");
        List<String> kinds = new ArrayList<>(); for (Unit x : d.units()) kinds.add(x.kind());
        assertEquals(List.of("heading", "para", "heading", "item", "item", "para"), kinds);
        assertEquals("The dial predicts eclipses over 223 lunar months, about 18 years.", d.units().get(1).text(), "the lines of a paragraph run together");
        assertEquals(1, d.units().get(0).level()); assertEquals(2, d.units().get(2).level());
        assertEquals(DocUnits.format(text.getBytes(StandardCharsets.UTF_8), "notes.txt"), "txt");
        // a PDF the library's own writer made, read back by its text: the heading and the paragraphs come out as units
        System.setProperty("researchzosho.fonts", ExportTest.japaneseFont(tmp).toString());
        byte[] pdf = DocWriters.write(DocUnits.markdown("# The Saros dial\n\nThe dial predicts eclipses over 223 lunar months.\n\nA second paragraph about the hour.\n", "saros"), "pdf", "").bytes();
        assertEquals("pdf", DocUnits.format(pdf, "saros.pdf"));
        Doc back = DocUnits.cut(pdf, "saros.pdf");
        assertEquals("pdf", back.format());
        String joined = String.join(" | ", back.units().stream().map(Unit::text).toList());
        assertTrue(joined.contains("The dial predicts eclipses over 223 lunar months.") && joined.contains("A second paragraph about the hour."), joined);
        assertTrue(back.units().stream().anyMatch(x -> x.kind().equals(DocUnits.HEADING) && x.text().equals("The Saros dial")), back.units().toString());
        try (PDDocument doc = Loader.loadPDF(pdf)) { assertTrue(new PDFTextStripper().getText(doc).contains("223 lunar months")); }
    }

    @Test
    void aWordFileIsCutByParagraphListItemAndCellAndWrittenBackThroughOurWriter() throws Exception {
        Doc d = DocUnits.markdown(MARKDOWN.substring(MARKDOWN.indexOf("# The")), "saros");   // no front matter: a Word file has none
        byte[] docx = DocWriters.write(d, "docx", "").bytes();
        assertEquals("docx", DocUnits.format(docx, "saros.docx"));
        Doc back = DocUnits.cut(docx, "saros.docx");
        assertEquals("docx", back.format());
        List<String> kinds = new ArrayList<>(); for (Unit x : back.units()) kinds.add(x.kind() + (x.kind().equals(DocUnits.HEADING) ? x.level() : x.kind().equals(DocUnits.ITEM) ? "/" + x.level() + (x.ordered() ? "n" : "") : x.kind().equals(DocUnits.CELL) ? "/" + x.row() : ""));
        // the picture and the rule are not in a Word file; the headings, the paragraphs, the list with its depths and numbering, the cells in their rows, the code by its style, the quote and the caption as paragraphs come through
        assertEquals(List.of("heading1", "para", "heading2", "item/0", "item/0", "item/1", "item/0n", "item/0n", "cell/0", "cell/0", "cell/1", "cell/1", "cell/2", "cell/2", "para", "code", "para"), kinds, kinds.toString());
        assertEquals("```\ncode stays as it is\n```", back.units().get(15).text());
        assertEquals("the hour, as **bold** letters", back.units().get(4).text(), "bold comes back as its mark");
        assertEquals("a lunar eclipse", back.units().get(11).text());
        assertTrue(back.units().get(1).text().contains("[https://example.org/saros](https://example.org/saros)") || back.units().get(1).text().contains("https://example.org/saros"), back.units().get(1).text());
        assertEquals("The Saros dial", back.title());
        // a second pass through the writer and the cutter is stable
        assertEquals(back.units(), DocUnits.cut(DocWriters.write(back, "docx", "").bytes(), "again.docx").units());
    }

    /** A small EPUB built by hand, as a publisher's tool writes one: two chapters, a nav, a picture, a table, a figure caption, a named entity, a nested list. */
    static byte[] handMadeEpub() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("META-INF/container.xml", "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"EPUB/package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
        files.put("EPUB/package.opf", "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"u\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"u\">urn:uuid:1</dc:identifier><dc:title>An Invented Clock</dc:title><dc:language>en</dc:language><dc:creator>An Invented Hand</dc:creator></metadata>"
                + "<manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"c1\" href=\"text/one.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"c2\" href=\"text/two.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"i1\" href=\"img/dial.png\" media-type=\"image/png\"/></manifest>"
                + "<spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>");
        files.put("EPUB/nav.xhtml", "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>nav</title></head><body><nav epub:type=\"toc\"><ol><li><a href=\"text/one.xhtml\">The dial</a></li><li><a href=\"text/two.xhtml\">Notes</a></li></ol></nav></body></html>");
        files.put("EPUB/text/one.xhtml", "<?xml version=\"1.0\"?><!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>one</title></head><body><section><h1>The Saros dial</h1><p>The dial predicts eclipses over 223&nbsp;lunar months, about <em>18 years</em>. See <a href=\"https://example.org/saros\">the drawing</a>.</p>"
                + "<figure><img src=\"../img/dial.png\" alt=\"the dial\"/><figcaption>The dial, drawn in 1902.</figcaption></figure><ul><li>the month<ul><li>a nested note</li></ul></li><li>the hour</li></ul>"
                + "<table><tr><th>Glyph</th><th>Meaning</th></tr><tr><td>Σ</td><td>a lunar eclipse</td></tr></table><blockquote><p>A quotation.</p></blockquote></section></body></html>");
        files.put("EPUB/text/two.xhtml", "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>two</title></head><body><div>Loose words with <strong>bold</strong> in them.</div><p>A second chapter's paragraph.</p></body></html>");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("mimetype")); z.write("application/epub+zip".getBytes(StandardCharsets.US_ASCII)); z.closeEntry();
            for (var e : files.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue().getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
            z.putNextEntry(new ZipEntry("EPUB/img/dial.png")); z.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0}); z.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void anEpubIsCutByChapterThenUnitAndWrittenBackAsAPackageAReaderOpens() throws Exception {
        byte[] epub = handMadeEpub();
        assertEquals("epub", DocUnits.format(epub, "clock.epub"));
        Doc d = DocUnits.cut(epub, "clock.epub");
        assertEquals("epub", d.format()); assertEquals("An Invented Clock", d.title()); assertEquals("An Invented Hand", d.meta().get("creator"));
        assertEquals(2, d.chapters().size());
        List<Unit> one = d.chapters().get(0).units();
        List<String> kinds = new ArrayList<>(); for (Unit x : one) kinds.add(x.kind() + (x.kind().equals(DocUnits.ITEM) ? "/" + x.level() : x.kind().equals(DocUnits.CELL) ? "/" + x.row() + (x.level() == 1 ? "h" : "") : ""));
        assertEquals(List.of("heading", "para", "image", "caption", "item/0", "item/1", "item/0", "cell/0h", "cell/0h", "cell/1", "cell/1", "para"), kinds, kinds.toString());
        assertEquals("The dial predicts eclipses over 223 lunar months, about *18 years*. See [the drawing](https://example.org/saros).", one.get(1).text(), "the entity is a space, the emphasis and the link keep their marks");
        assertEquals("EPUB/img/dial.png", one.get(2).extra()); assertTrue(d.images().containsKey("EPUB/img/dial.png"), "the picture is kept by the path the chapter names it by");
        assertEquals("quote", one.get(11).extra());
        List<Unit> two = d.chapters().get(1).units();
        assertEquals(DocUnits.TITLE, two.get(0).kind()); assertEquals("Notes", two.get(0).text(), "a chapter with no heading is named by the navigation: " + two);
        assertEquals("Loose words with **bold** in them.", two.get(1).text());
        // written back as an EPUB: the package a reader opens
        Doc translated = d.map(u -> u.translated() ? u.withText("訳: " + u.text()) : u).withTitle("訳: An Invented Clock");
        Map<String, String> meta = new LinkedHashMap<>(translated.meta()); meta.put("original_title", "An Invented Clock");
        translated = new Doc("epub", translated.title(), "ja", translated.chapters(), translated.images(), meta);
        byte[] out = DocWriters.epub(translated);
        checkEpubPackage(out);
        Map<String, byte[]> z = DocUnits.unzip(out);
        String opf = new String(z.get("OEBPS/content.opf"), StandardCharsets.UTF_8);
        assertTrue(opf.contains("<dc:title>訳: An Invented Clock</dc:title>") && opf.contains("<dc:language>ja</dc:language>") && opf.contains("<dc:creator>An Invented Hand</dc:creator>") && opf.contains("<meta property=\"dcterms:alternative\">An Invented Clock</meta>"), opf);
        String ch1 = new String(z.get("OEBPS/ch001.xhtml"), StandardCharsets.UTF_8);
        assertTrue(ch1.contains("<h1>訳: The Saros dial</h1>") && ch1.contains("<i>18 years</i>") && ch1.contains("<a href=\"https://example.org/saros\">the drawing</a>") && ch1.contains("<img src=\"images/dial.png\"") && ch1.contains("<ul>\n<li>訳: the month</li>\n<ul>\n<li>訳: a nested note</li>\n</ul>\n<li>訳: the hour</li>\n</ul>") && ch1.contains("<tr><th>訳: Glyph</th><th>訳: Meaning</th></tr>") && ch1.contains("<blockquote><p>訳: A quotation.</p></blockquote>"), ch1);
        assertTrue(z.containsKey("OEBPS/images/dial.png"));
        String nav = new String(z.get("OEBPS/nav.xhtml"), StandardCharsets.UTF_8);
        assertTrue(nav.contains("<a href=\"ch001.xhtml\">訳: The Saros dial</a>") && nav.contains("<a href=\"ch002.xhtml\">訳: Notes</a>"), nav);
        // and cut again: the same chapters and units
        Doc back = DocUnits.cut(out, "clock.ja.epub");
        assertEquals(2, back.chapters().size());
        assertEquals(translated.chapters().get(0).units().stream().map(Unit::kind).toList(), back.chapters().get(0).units().stream().map(Unit::kind).toList());
        assertEquals("訳: The dial predicts eclipses over 223 lunar months, about *18 years*. See [the drawing](https://example.org/saros).", back.chapters().get(0).units().get(1).text());
        assertEquals("訳: Notes", back.chapters().get(1).units().get(0).text());
    }

    /** The package structure a reader relies on: mimetype first and stored, the container pointing at the package file, every manifest item present, the spine naming manifest items, a nav marked toc, every XHTML well-formed. */
    static void checkEpubPackage(byte[] epub) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(epub))) {
            ZipEntry first = zin.getNextEntry();
            assertEquals("mimetype", first.getName(), "mimetype is the first entry");
            assertEquals(ZipEntry.STORED, first.getMethod(), "mimetype is stored, not compressed");
            assertEquals("application/epub+zip", new String(zin.readAllBytes(), StandardCharsets.US_ASCII));
        }
        Map<String, byte[]> z = DocUnits.unzip(epub);
        String container = new String(z.get("META-INF/container.xml"), StandardCharsets.UTF_8);
        Matcher root = Pattern.compile("full-path=\"([^\"]+)\"").matcher(container);
        assertTrue(root.find(), container);
        String opfPath = root.group(1);
        assertTrue(z.containsKey(opfPath), "the package file the container names exists: " + opfPath);
        String opf = new String(z.get(opfPath), StandardCharsets.UTF_8);
        DocUnits.parse(opf);
        String dir = opfPath.substring(0, opfPath.lastIndexOf('/') + 1);
        Map<String, String> items = new LinkedHashMap<>();
        Matcher im = Pattern.compile("<item\\b[^>]*>").matcher(opf);
        while (im.find()) { String id = DocUnits.attr(im.group(), "id"), href = DocUnits.attr(im.group(), "href"); assertNotNull(id); assertNotNull(href); assertTrue(z.containsKey(dir + href), "manifest item exists: " + href); items.put(id, href); }
        for (String name : z.keySet()) if (name.startsWith(dir) && !name.equals(opfPath)) assertTrue(items.containsValue(name.substring(dir.length())), "every file under the package is in the manifest: " + name);
        Matcher sm = Pattern.compile("<itemref\\b[^>]*idref=\"([^\"]+)\"").matcher(opf);
        int spine = 0; while (sm.find()) { assertTrue(items.containsKey(sm.group(1)), "spine names a manifest item: " + sm.group(1)); spine++; }
        assertTrue(spine > 0, "the spine has chapters");
        String nav = null; for (String id : items.keySet()) if (opf.contains("id=\"" + id + "\"") && opf.matches("(?s).*<item\\b[^>]*id=\"" + id + "\"[^>]*properties=\"[^\"]*nav[^\"]*\".*")) nav = items.get(id);
        assertNotNull(nav, "a manifest item carries properties=\"nav\"");
        String navXhtml = new String(z.get(dir + nav), StandardCharsets.UTF_8);
        assertTrue(navXhtml.contains("epub:type=\"toc\""), navXhtml);
        for (var e : items.entrySet()) if (e.getValue().endsWith(".xhtml")) DocUnits.parse(new String(z.get(dir + e.getValue()), StandardCharsets.UTF_8));   // well-formed, or parse throws
        assertTrue(opf.contains("<dc:identifier") && opf.contains("<dc:title>") && opf.contains("<dc:language>") && opf.contains("dcterms:modified"), opf);
    }

    @Test
    void aFormatIsReadOffTheBytesThenTheName() throws Exception {
        assertEquals("md", DocUnits.format("# x".getBytes(StandardCharsets.UTF_8), "notes.md"));
        assertEquals("txt", DocUnits.format("x".getBytes(StandardCharsets.UTF_8), "notes.txt"));
        assertEquals("txt", DocUnits.format("x".getBytes(StandardCharsets.UTF_8), "NOTES"));
        assertNull(DocUnits.format("x".getBytes(StandardCharsets.UTF_8), "notes.xlsx"));
        assertEquals("epub", DocUnits.format(handMadeEpub(), "whatever.bin"));
        assertThrows(IOException.class, () -> DocUnits.cut("x".getBytes(StandardCharsets.UTF_8), "notes.xlsx"));
        assertTrue(Files.isDirectory(Path.of(".")));
    }
}
