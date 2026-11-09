package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.mcp.McpServer;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A report and a dossier as files (0.7.0): a PDF and a Word file that hold what the page shows, with links the reader clicks; in the
 * language the page is read in; through the protocol, kept under catalog/exports/ and served at /exports/; on the pages; at the command
 * line. Every file is read back: the PDF with PDFBox, its text and its link annotations; the Word file unzipped and every part parsed.
 */
class ReportExportTest {

    static final ObjectMapper M = new ObjectMapper();

    static final String QUESTION = "Which homes in Cambridge fit a family of four, under $800 a square foot?";
    static final String BODY = "## Question\n\n" + QUESTION + "\n\nResearched by default (a test model): 1 M prompt tokens.\n\n"
            + "## Answer (as submitted by the run — draft until reviewed)\n\n"
            + "## Answer\n\nTwo listings fit [50]. The market is tight [2].\n\n"
            + "- [108 Example St, Cambridgeport](https://example.org/listing/108) — 4 bd, $1,670,000 [50]\n"
            + "- 159 Sample St #1 — condo, 4 bd [28]\n\n"
            + "1. First look at Porter Square [2]\n2. Then look at Inman Square\n\n"
            + "| Home | Price | Per sq ft |\n|---|---|---|\n| 108 Example St | $1,670,000 | $718 |\n| 159 Sample St #1 | $1,575,000 | $729 |\n\n"
            + "**Bold words** and *italic words* and `code words`.\n\n"
            + "## How each point was checked\n\n- Four bedrooms: read in the listing, not checked [50]\n\n"
            + "## Open questions and sub-goals\n\nQuestions this run left for later, each with why.\n\n- filed for later: which schools are near — the budget ran out\n\n"
            + "## Checks\n\nThe write-up against the evidence it was written from.\n\n- number 1.95 M — not in any note or source read this run\n\n"
            + "## Evidence\n\n| fact | source |\n|---|---|\n| hidden evidence row | [50] |\n\n"
            + "## References\n\n[2] Cambridge market — https://example.org/market  (dated September 2026, from its own text)\n"
            + "[28] Four bedrooms — https://example.org/4-bedrooms\n[50] 108 Example St — https://example.org/listing/108\n\n"
            + "## Worker findings (fan sub-investigations, verbatim)\n\nSUB-QUESTION: secret working notes\n\n"
            + "## Sources cited\n\n- https://example.org/market\n";

    private String fontsBefore;
    @BeforeEach void keep() { fontsBefore = System.getProperty("researchzosho.fonts"); }
    @AfterEach void restore() { if (fontsBefore == null) System.clearProperty("researchzosho.fonts"); else System.setProperty("researchzosho.fonts", fontsBefore); }

    static Finding claim(LibraryStore store, String title, Finding.State state) throws Exception {
        String id = store.nextFindingId(title);
        Finding f = new Finding(id, title, List.of(), state, Finding.ClaimType.extraction, Finding.Confidence.medium, "patron:test", Instant.now().toString(), "2026-10-24",
                Finding.Volatility.slow, "", List.of(new Finding.Source("https://example.org/listing/108", "n/a", "the listing")), List.of(), null, title + " [50].\n");
        store.write(f);
        return f;
    }

    /** A library with one report in the shape a run files: its answer, its checks, its working, its references, and two claims. */
    static String report(LibraryStore store) throws Exception {
        Finding a = claim(store, "108 Example St listing details", Finding.State.accepted);
        Finding b = claim(store, "Porter Square median price per square foot", Finding.State.draft);
        String id = store.nextInvestigationId(QUESTION);
        store.write(new Investigation(id, QUESTION, Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(a.id(), b.id()), List.of("which schools are near"), BODY));
        return id;
    }

    static List<String> links(PDDocument doc) throws Exception {
        List<String> out = new ArrayList<>();
        for (PDPage page : doc.getPages()) for (PDAnnotation a : page.getAnnotations()) if (a instanceof PDAnnotationLink l && l.getAction() instanceof PDActionURI u) out.add(u.getURI());
        return out;
    }

    @Test
    void aReportsPdfHoldsWhatItsPageShowsWithLinksTheReaderClicks(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String id = report(store);
        Export.Made m = Export.report(store, id, "pdf", "");
        assertEquals(id + ".pdf", m.file().name());
        assertEquals("application/pdf", m.file().contentType());
        assertFalse(m.translated()); assertEquals("en", m.writtenIn()); assertEquals(0, m.unshown());
        try (PDDocument doc = Loader.loadPDF(m.file().bytes())) {
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            for (String want : new String[]{QUESTION, "Report I-0001", "2 claims, 1 open question", "Question", "Two listings fit [50]", "108 Example St, Cambridgeport",
                    "First look at Porter Square", "Then look at Inman Square", "$1,670,000", "$718", "Bold words", "italic words", "code words",
                    "How each point was checked", "Open questions and sub-goals", "which schools are near", "Checks", "number 1.95 M",
                    "Claims", "established: accepted", "108 Example St listing details — accepted (F-0001)", "Porter Square median price per square foot — waiting for review (F-0002)",
                    "Sources", "[2] Cambridge market", "[50] 108 Example St", "page 1 of"})
                assertTrue(text.contains(want), "the file says \"" + want + "\": " + text);
            for (String not : new String[]{"secret working notes", "hidden evidence row", "Sources cited", "Not every character"})
                assertFalse(text.contains(not), "the run's working stays out of the file: " + not);
            List<String> links = links(doc);
            assertTrue(links.stream().filter("https://example.org/listing/108"::equals).count() >= 3, "the listing is linked from the line, from its number [50] and from the sources: " + links);
            assertTrue(links.contains("https://example.org/market") && links.contains("https://example.org/4-bedrooms"), links.toString());
            PDOutlineItem top = doc.getDocumentCatalog().getDocumentOutline().getFirstChild();
            assertEquals(QUESTION, top.getTitle(), "the title is the first bookmark");
            List<String> sections = new ArrayList<>(); for (PDOutlineItem i : top.children()) sections.add(i.getTitle());
            assertTrue(sections.containsAll(List.of("Question", "Answer", "How each point was checked", "Claims", "Sources")), sections.toString());
            assertEquals(QUESTION, doc.getDocumentInformation().getTitle());
        }
    }

    /** Every part of a .docx, by name, as text; each parses as XML. */
    static Map<String, String> unzip(byte[] docx) throws Exception {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) parts.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        for (var e : parts.entrySet()) {
            Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue().getBytes(StandardCharsets.UTF_8)));
            assertNotNull(d.getDocumentElement(), e.getKey());
        }
        return parts;
    }

    static int count(String s, String what) { int n = 0; for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++; return n; }

    @Test
    void aReportsWordFileIsAWholeDocxWithHeadingsListsATableAndLinks(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String id = report(store);
        Export.Made m = Export.report(store, id, "docx", "");
        assertEquals(id + ".docx", m.file().name());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", m.file().contentType());
        Map<String, String> parts = unzip(m.file().bytes());
        assertEquals("[Content_Types].xml", parts.keySet().iterator().next(), "the content types come first in the zip");
        assertTrue(parts.keySet().containsAll(List.of("_rels/.rels", "word/document.xml", "word/styles.xml", "word/numbering.xml", "word/settings.xml", "word/footer1.xml",
                "word/_rels/document.xml.rels", "docProps/core.xml", "docProps/app.xml")), parts.keySet().toString());
        String doc = parts.get("word/document.xml"), rels = parts.get("word/_rels/document.xml.rels");
        assertTrue(doc.contains("<w:pStyle w:val=\"Title\"/>") && doc.contains(">" + QUESTION + "<") && doc.contains("<w:pStyle w:val=\"Heading1\"/>"), "a title and the sections as headings");
        assertTrue(doc.contains("Two listings fit") && doc.contains("How each point was checked") && doc.contains("108 Example St listing details") && !doc.contains("secret working notes"), doc);
        assertEquals(1, count(doc, "<w:tbl>"), "one table, the run's evidence left out");
        assertTrue(doc.contains("<w:tblHeader/>") && doc.contains("$1,670,000"), "the table's header row repeats on each page");
        assertTrue(count(doc, "<w:numId w:val=\"1\"/>") >= 3, "the bullets are a Word list");
        assertTrue(doc.contains("<w:numId w:val=\"2\"/>") && parts.get("word/numbering.xml").contains("<w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/>"), "the numbered list counts from its own first number");
        assertEquals(3, count(doc, "<w:pStyle w:val=\"Source\"/>"), "each source on its own line with its number hanging");
        assertTrue(doc.contains("<w:b/>") && doc.contains("<w:i/>") && doc.contains("<w:rStyle w:val=\"CodeChar\"/>"), "bold, italic and code");
        // every link points at an address the relationships carry, outside the document
        Matcher h = Pattern.compile("<w:hyperlink r:id=\"([^\"]+)\"").matcher(doc);
        int n = 0;
        while (h.find()) { n++; assertTrue(Pattern.compile("Id=\"" + h.group(1) + "\" Type=\"[^\"]+/hyperlink\" Target=\"https://[^\"]+\" TargetMode=\"External\"").matcher(rels).find(), h.group(1) + " in " + rels); }
        assertTrue(n >= 5, "the links of the line, the numbers and the sources: " + n);
        assertTrue(rels.contains("Target=\"https://example.org/listing/108\" TargetMode=\"External\""), rels);
        assertTrue(parts.get("word/footer1.xml").contains("w:instr=\" PAGE \""), "the page's number at its foot");
        assertTrue(parts.get("docProps/core.xml").contains("<dc:title>" + QUESTION + "</dc:title>"));
    }

    @Test
    void aReportReadInAnotherLanguageIsSavedInThatLanguageAndOneNotYetTranslatedSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String id = report(store);
        Translate.reportIn(store, id, "ja", TranslateTest.standIn(text -> "【訳】" + text));
        System.setProperty("researchzosho.fonts", ExportTest.japaneseFont(tmp).toString());
        Export.Made ja = Export.report(store, id, "pdf", "Japanese");
        assertTrue(ja.translated()); assertEquals("ja", ja.readIn()); assertEquals(id + ".ja.pdf", ja.file().name());
        try (PDDocument doc = Loader.loadPDF(ja.file().bytes())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("【訳】") && text.contains("written in English, read in Japanese"), text);
            assertTrue(links(doc).contains("https://example.org/listing/108"), "the links stand in the translation");
        }
        Map<String, String> word = unzip(Export.report(store, id, "docx", "ja").file().bytes());
        assertTrue(word.get("word/document.xml").contains("【訳】") && word.get("word/styles.xml").contains("w:val=\"en-GB\" w:eastAsia=\"ja-JP\""), "the Word file is the translation, marked Japanese");
        Export.Made de = Export.report(store, id, "docx", "de");
        assertFalse(de.translated()); assertEquals(id + ".docx", de.file().name());
        assertTrue(de.note().startsWith("There is no translation of this report into German yet, so this file is the report as it was written."), de.note());
        assertTrue(unzip(de.file().bytes()).get("word/document.xml").contains("There is no translation of this report into German yet"), "the file says so under its title");
        assertEquals("", Export.report(store, id, "pdf", "written").readIn(), "as written is as written");
    }

    /** A dossier with a report that has a Reading and a claim with a year. */
    static void dossier(LibraryStore store) throws Exception {
        CrossLinksTest.report(store, "Who is Ada Example? A full dossier.", "\n## Reading\n\nAda Example leads the widget work at Acme Corp [1].\n\n## What they do\n\nAda Example builds widgets at Acme Corp [1].\n\n"
                + "## References\n\n[1] Acme's own page — https://example.org/acme\n");
        CrossLinksTest.claim(store, "Ada Example, 2009: joined Acme Corp", "Ada Example joined Acme Corp in Boston in 2009 [1].");
    }

    @Test
    void aDossierIsSavedWithItsSectionsTheReadingFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        dossier(store);
        Export.Made m = Export.dossier(store, "Ada Example", "pdf", "");
        assertEquals("dossier-ada-example.pdf", m.file().name());
        try (PDDocument doc = Loader.loadPDF(m.file().bytes())) {
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertTrue(text.contains("Dossier: Ada Example") && text.contains("Reading, for that question") && text.contains("Ada Example leads the widget work")
                    && text.contains("What they do") && text.contains("Timeline") && text.contains("2009"), text);
            assertTrue(text.indexOf("Reading, for that question") < text.indexOf("What they do"), "the Reading first, as the page opens with it");
            assertTrue(links(doc).contains("https://example.org/acme"), "the Reading's source is a link");
        }
        assertTrue(unzip(Export.dossier(store, "Ada Example", "docx", "").file().bytes()).get("word/document.xml").contains("Ada Example builds widgets"));
        ProtocolError none = assertThrows(ProtocolError.class, () -> Export.dossier(store, "Nobody Heldhere", "pdf", ""));
        assertEquals("not_found", none.code);
        assertEquals("x" + Translate.sha256("東京").substring(0, 10), Export.fileSlug("東京"), "a name with no ASCII letters is a short hash");
    }

    @Test
    void theProtocolKeepsTheFileUnderExportsAndThePagesServeIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String id = report(store);
        dossier(store);
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.put("id", id); a.put("format", "docx"); a.set("patron", LibrarianDaemon.patronNode(Patrons.Patron.PERSON));
        ObjectNode r = p.export(a);
        Path kept = Path.of(r.path("path").asText());
        assertEquals(store.root().resolve("catalog").resolve("exports").resolve(id + ".docx"), kept);
        assertTrue(Files.isRegularFile(kept)); assertEquals(Files.size(kept), r.path("bytes").asLong());
        assertEquals("/exports/" + id + ".docx", r.path("url").asText());
        assertEquals("report", r.path("kind").asText()); assertEquals("docx", r.path("format").asText()); assertEquals("en", r.path("written_in").asText());
        assertTrue(r.path("summary").asText().startsWith("The report is saved as a Word file at "), r.toString());
        assertTrue(r.has("library_id"), "the result carries the library's provenance");
        ObjectNode d = M.createObjectNode(); d.put("subject", "Ada Example"); d.set("patron", LibrarianDaemon.patronNode(Patrons.Patron.PERSON));
        JsonNode dr = p.export(d);
        assertEquals("dossier-ada-example.pdf", dr.path("file").asText()); assertEquals("pdf", dr.path("format").asText());
        for (String bad : new String[]{"{}", "{\"id\":\"" + id + "\",\"subject\":\"Ada Example\"}", "{\"id\":\"" + id + "\",\"format\":\"odt\"}"}) {
            ProtocolError e = assertThrows(ProtocolError.class, () -> p.export(M.readTree(bad)));
            assertEquals("invalid_args", e.code, bad);
        }
        assertEquals("not_found", assertThrows(ProtocolError.class, () -> p.export(M.readTree("{\"id\":\"I-0999-nothing-here\"}"))).code);
        // the MCP tool, with the patron as every library tool takes it
        boolean listed = false;
        for (JsonNode t : McpServer.allTools()) if (t.path("name").asText().equals("library_export")) { listed = true; assertTrue(t.path("inputSchema").path("properties").has("patron") && t.path("inputSchema").path("properties").path("format").has("enum")); }
        assertTrue(listed, "library_export is an MCP tool");
        // the pages: two links on the report and on the dossier, the files at /download, the kept file at /exports/
        LibrarianDaemon daemon = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            String base = "http://127.0.0.1:" + daemon.port();
            String page = get(c, base + "/entry/" + id).body();
            assertTrue(page.contains("<a href=\"/download?id=" + id + "&amp;as=pdf\">Download PDF</a> · <a href=\"/download?id=" + id + "&amp;as=docx\">Download Word</a> · <a href=\"/download?id=" + id + "&amp;as=md\">Download Markdown</a>"), page);
            HttpResponse<byte[]> md = getBytes(c, base + "/download?id=" + id + "&as=md");
            assertEquals(200, md.statusCode(), "the Markdown the library keeps downloads too");
            assertTrue(md.headers().firstValue("Content-Type").orElse("").startsWith("text/markdown"), md.headers().map().toString());
            assertTrue(new String(md.body(), StandardCharsets.UTF_8).contains("## "), "the report's own Markdown");
            assertTrue(Pages.entry(store, p, Patrons.Patron.PERSON, id, Map.of("in", "ja")).contains("/download?id=" + id + "&amp;as=docx&amp;in=ja\">Download Word</a>"), "read in Japanese, the links save it in Japanese");
            HttpResponse<byte[]> word = getBytes(c, base + "/download?id=" + id + "&as=docx");
            assertEquals(200, word.statusCode());
            assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", word.headers().firstValue("Content-Type").orElse(""));
            assertTrue(word.headers().firstValue("Content-Disposition").orElse("").contains("filename=\"" + id + ".docx\""));
            assertEquals("PK", new String(word.body(), 0, 2, StandardCharsets.ISO_8859_1));
            assertEquals("%PDF", new String(getBytes(c, base + "/download?id=" + id + "&as=pdf").body(), 0, 4, StandardCharsets.ISO_8859_1));
            assertEquals("%PDF", new String(getBytes(c, base + "/download?subject=Ada%20Example&as=pdf").body(), 0, 4, StandardCharsets.ISO_8859_1));
            assertTrue(get(c, base + "/dossier?subject=Ada%20Example").body().contains("as=docx\">Download Word</a>"), "the dossier's page has the links too");
            HttpResponse<byte[]> kept2 = getBytes(c, base + "/exports/" + id + ".docx");
            assertEquals(200, kept2.statusCode());
            assertArrayEquals(Files.readAllBytes(kept), kept2.body());
            assertEquals(404, getBytes(c, base + "/exports/nothing-here.pdf").statusCode());
            assertEquals(404, getBytes(c, base + "/exports/..%2Fcatalog%2Fpatrons.md").statusCode(), "only the kept files, nothing beside them");
            assertEquals("%PDF", new String(getBytes(c, base + "/download?id=" + id + "&as=pdf&in=written").body(), 0, 4, StandardCharsets.ISO_8859_1));
        } finally { daemon.stop(); }
    }

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<byte[]> getBytes(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void theCommandLineSavesAReportAndADossierAndSaysWhatItSaved(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        PrintStream realOut = System.out;
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        try {
            System.setProperty("user.home", tmp.toString());
            LibraryStore store = new LibraryStore(tmp.resolve("codezaiku-library")); store.init();
            String id = report(store);
            dossier(store);
            System.setOut(new PrintStream(said, true, StandardCharsets.UTF_8));
            Path word = tmp.resolve("homes.docx"), pdf = tmp.resolve("ada.pdf");
            assertEquals(0, LibrarianCli.run(new String[]{"librarian", "report", "export", "I-0001", "--docx", "--out", word.toString()}, "http://none", "m"), "the number alone finds the report");
            assertEquals("PK", new String(Files.readAllBytes(word), 0, 2, StandardCharsets.ISO_8859_1));
            assertEquals(0, LibrarianCli.run(new String[]{"librarian", "dossier", "Ada", "Example", "--pdf", "--out", pdf.toString()}, "http://none", "m"));
            assertEquals("%PDF", new String(Files.readAllBytes(pdf), 0, 4, StandardCharsets.ISO_8859_1));
            assertEquals(2, LibrarianCli.run(new String[]{"librarian", "report", "export"}, "http://none", "m"));
            assertEquals(1, LibrarianCli.run(new String[]{"librarian", "report", "export", "I-0999", "--pdf", "--out", tmp.resolve("x.pdf").toString()}, "http://none", "m"));
            String out = said.toString(StandardCharsets.UTF_8);
            assertTrue(out.contains("Saved the report as a Word file: " + word.toAbsolutePath()) && out.contains("It opens in Word, LibreOffice, Pages or Google Docs. The links in it open the sources."), out);
            assertTrue(out.contains("Saved the dossier as a PDF file: " + pdf.toAbsolutePath()) && out.contains("Open it with any PDF reader."), out);
            assertTrue(LibrarianCli.USAGE.contains("report export <I-…> --pdf|--docx") && LibrarianCli.USAGE.contains("[--pdf | --docx] [--out FILE]"), "the help says so");
            assertEquals(id, Export.reportId(store, "1"));
        } finally {
            System.setOut(realOut);
            System.setProperty("user.home", realHome);
        }
    }

    @Test
    void aLineNeverStartsWithAClosingMarkNorEndsWithAnOpeningOne(@TempDir Path tmp) throws Exception {
        System.setProperty("researchzosho.fonts", ExportTest.japaneseFont(tmp).toString());
        String text = "歯車は【手】で切られた。";
        try (PDDocument doc = new PDDocument(); ExportPdf.Fonts f = ExportPdf.Fonts.load(doc, text)) {
            List<ExportPdf.Word> ws = new ExportPdf.Layout(doc, f).words(List.of(ExportDoc.Span.plain(text)), new ExportPdf.Style(10.5f, false, false, ExportPdf.INK));
            List<String> words = new ArrayList<>();
            for (ExportPdf.Word w : ws) { StringBuilder b = new StringBuilder(); for (ExportPdf.Piece p : w.pieces()) b.append(p.text()); words.add(b.toString()); }
            assertEquals(List.of("歯", "車", "は", "【手】", "で", "切", "ら", "れ", "た。"), words, "a line may break between any two of these, never before 】 or 。, never after 【");
        }
    }

    @Test
    void theMarkdownIsReadAsThePageReadsIt() {
        Map<Integer, String> cites = Map.of(3, "https://example.org/three");
        List<ExportDoc.Block> b = ExportDoc.parse("# Title\n\nOne line\nruns on [3].\n\n- a\n  - b, deeper\n    more of b\n\n3. three\n4. four\n\n[12] A source — https://example.org/s12.\n\n"
                + "```\ncode  kept\n```\n\n---\n\nhttps://example.org/alone (a note)\n\nSee (https://en.example.org/wiki/Foo_(bar)), and \\*not italic\\* but _italic_ and [a page](/entry/I-0001-x).", cites);
        assertEquals("Title", ExportDoc.plain(((ExportDoc.Heading) b.get(0)).spans()));
        ExportDoc.Para p = (ExportDoc.Para) b.get(1);
        assertEquals("One line runs on [3].", ExportDoc.plain(p.spans()));
        assertTrue(p.spans().stream().anyMatch(s -> "[3]".equals(s.text()) && "https://example.org/three".equals(s.href())), "a source's number links to its address: " + p.spans());
        ExportDoc.Item a = (ExportDoc.Item) b.get(2), deeper = (ExportDoc.Item) b.get(3);
        assertEquals(0, a.depth()); assertEquals(1, deeper.depth()); assertEquals("b, deeper more of b", ExportDoc.plain(deeper.spans()), "an indented line continues its item");
        ExportDoc.Item three = (ExportDoc.Item) b.get(4);
        assertEquals(ExportDoc.Mark.NUMBER, three.mark()); assertEquals(3, three.number());
        ExportDoc.Item source = (ExportDoc.Item) b.get(6);
        assertEquals(ExportDoc.Mark.SOURCE, source.mark()); assertEquals(12, source.number());
        assertTrue(source.spans().stream().anyMatch(s -> "https://example.org/s12".equals(s.href()) && "https://example.org/s12".equals(s.text())), "the closing full stop is not the address's: " + source.spans());
        assertEquals(List.of("code  kept"), ((ExportDoc.Code) b.get(7)).lines());
        assertInstanceOf(ExportDoc.Rule.class, b.get(8));
        assertEquals("https://example.org/alone", ((ExportDoc.Para) b.get(9)).spans().get(0).href(), "an address on its own line stays on its own line");
        List<ExportDoc.Span> last = ((ExportDoc.Para) b.get(10)).spans();
        assertTrue(last.stream().anyMatch(s -> "https://en.example.org/wiki/Foo_(bar)".equals(s.href())), "a parenthesis that is the address's stays: " + last);
        assertTrue(ExportDoc.plain(last).contains("*not italic*") && last.stream().anyMatch(s -> s.italic() && s.text().equals("italic")), last.toString());
        assertTrue(ExportDoc.plain(last).endsWith(" and a page.") && last.stream().noneMatch(s -> s.href() != null && s.href().contains("/entry/")), "a page of this library is its words, not a link that goes nowhere: " + last);
        assertEquals("&lt;a &amp; b&gt;", ExportDocx.xml("<a & b>" + (char) 1));
    }
}
