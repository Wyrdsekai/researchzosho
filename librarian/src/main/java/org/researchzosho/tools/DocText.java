package org.researchzosho.tools;


import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import org.researchzosho.librarian.Calibre;
import org.researchzosho.librarian.Chrome;
import org.researchzosho.librarian.DocUnits;
import org.researchzosho.librarian.DocWriters;
import org.researchzosho.librarian.PdfReading;
/**
 * Document → text, for everything the research path and the library shelve: a fetched page, a
 * downloaded paper, a colleague's DOCX, an EPUB edition.
 *
 * <p>Formats are detected from the BYTES (magic numbers, zip contents), never from a URL's
 * extension or a server's content-type — both lie (a paper served as {@code text/html} with a
 * PDF body; a DOCX behind a download handler). One dependency (PDFBox) for PDF; the office and
 * ebook formats are zip archives of XML, read with {@code java.util.zip} and a tag-stripper —
 * the same "a dependency-free store that always works beats a clever one" rule the research
 * pool follows.
 *
 * <p>Caught live 2026-09-01: two Japanese academic papers on the keigo shelf (Keio, JASS) were
 * found by author-name search and could not be read — "BINARY PDF — body text unreadable" —
 * exactly the JA-only scholarship the bilingual steering exists to reach.
 */
public final class DocText {

    /** Converted document: the text, a title if the format carries one, what it was, and for a PDF how its pages were read. */
    public record Doc(String text, String title, String kind, PdfReading.Read reading) {
        public Doc(String text, String title, String kind) { this(text, title, kind, null); }
    }

    /** Text budget per document — beyond this a paper is a dump, not a source. */
    public static final int MAX_TEXT = 1_500_000;

    private DocText() { }

    /** Convert by sniffing the bytes. {@code nameHint} (a URL or filename) only breaks ties. */
    public static Doc convert(byte[] bytes, String nameHint) {
        if (bytes == null || bytes.length == 0) return new Doc("", "", "empty");
        if (isPdf(bytes)) return pdf(bytes);
        if (ImageText.isImage(bytes)) {
            StringBuilder why = new StringBuilder();
            String text = ImageText.read(bytes, nameHint, why);
            return text.isEmpty() ? new Doc("", "", "image: " + why) : new Doc(text, text.startsWith("PHOTO:") ? text.substring(6).strip() : "", "image");
        }
        if (isSqlite(bytes)) return sqlite(bytes, nameHint);
        if (isZip(bytes)) {
            Doc z = zipDocument(bytes);
            if (z != null) return z;
        }
        String s = new String(bytes, StandardCharsets.UTF_8);
        String lower = s.substring(0, Math.min(s.length(), 2000)).toLowerCase(Locale.ROOT);
        if (lower.contains("<html") || lower.contains("<!doctype html") || lower.contains("<body")
                || (nameHint != null && nameHint.toLowerCase(Locale.ROOT).matches(".*\\.html?$"))) {
            return new Doc(WebFetchTool.readable(s), WebFetchTool.pageTitle(s), "html");
        }
        return new Doc(s, "", "text");
    }

    static final byte[] SQLITE_MAGIC = "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);

    /** A SQLite file: sixteen fixed bytes at the front. */
    public static boolean isSqlite(byte[] bytes) {
        if (bytes.length < SQLITE_MAGIC.length) return false;
        for (int i = 0; i < SQLITE_MAGIC.length; i++) if (bytes[i] != SQLITE_MAGIC[i]) return false;
        return true;
    }

    /**
     * A SQLite database as text. Calibre's metadata.db becomes the CSV of its books (title, authors, year, series, tags,
     * ISBN, publisher, formats), which the list starting point reads as it is; any other database becomes its tables
     * and their row counts, so a reader sees what it holds instead of fragments of the binary (a research run on a
     * Calibre database recovered eleven titles out of hundreds by reading it as text, 2026-09-17).
     */
    static Doc sqlite(byte[] bytes, String nameHint) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("researchzosho-", ".db");
            Files.write(tmp, bytes);
            try {
                List<Calibre.Book> books = Calibre.fromDatabase(tmp);
                return new Doc(Calibre.csv(books), "Calibre library (" + books.size() + " books)", "calibre");
            } catch (Exception notCalibre) {
                return new Doc(tables(tmp), (nameHint == null || nameHint.isBlank() ? "SQLite database" : nameHint) + " (SQLite)", "sqlite");
            }
        } catch (Exception e) {
            return new Doc("An SQLite database that could not be opened: " + e.getMessage(), "", "sqlite");
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /** The tables of a database and how many rows each holds, one per line. */
    static String tables(Path db) throws Exception {
        Class.forName("org.sqlite.JDBC");
        StringBuilder sb = new StringBuilder("An SQLite database. Tables and rows:\n");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db.toAbsolutePath().toString().replace("\\", "/") + "?immutable=1&mode=ro");
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            List<String> names = new ArrayList<>();
            while (rs.next()) names.add(rs.getString(1));
            for (String n : names) {
                try (Statement s2 = c.createStatement(); ResultSet r2 = s2.executeQuery("SELECT count(*) FROM \"" + n.replace("\"", "\"\"") + "\"")) { sb.append("- ").append(n).append(": ").append(r2.next() ? r2.getLong(1) : 0).append(" row(s)\n"); }
                catch (Exception e) { sb.append("- ").append(n).append('\n'); }
            }
        }
        return sb.toString();
    }

    // ---- sniffing -----------------------------------------------------------------

    static boolean isPdf(byte[] b) {
        return b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }

    static boolean isZip(byte[] b) {
        return b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    // ---- PDF --------------------------------------------------------------------------

    /**
     * A PDF's text, as a person reads it ({@link PdfReading}): the pages in order, each page's paragraphs whole and in reading order, a
     * page that is a picture of text read by the model that sees (at most {@link PdfReading#SHELF_EYE_PAGES} of them), a page that could
     * not be read said at its place. The kind says why when nothing could be read.
     */
    static Doc pdf(byte[] bytes) {
        PdfReading.Read r;
        try { r = PdfReading.read(bytes, PdfReading.Options.reading(PdfReading.SHELF_EYE_PAGES)); }
        catch (IOException e) { return new Doc("", "", "pdf-unreadable: " + e.getMessage()); }
        if (!r.any()) {
            String why = PdfReading.said(r.preflight(), Chrome.in("en"));
            return new Doc("", r.title(), "pdf: " + (why.isBlank() ? "no text could be read from it" : why.replaceAll("\\.$", "")), r);
        }
        return new Doc(cap(r.text()), r.title(), "pdf", r);
    }

    // ---- zip-of-XML formats: DOCX, PPTX, ODT, EPUB ---------------------------------------

    /** Read every entry once; dispatch on the names present. Null when it is not a document zip. */
    static Doc zipDocument(byte[] bytes) {
        List<String[]> entries = new ArrayList<>(); // [name, content]
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (n.endsWith(".xml") || n.endsWith(".xhtml") || n.endsWith(".html")
                        || n.endsWith(".htm") || n.endsWith(".opf") || n.equals("mimetype")) {
                    // capped as it inflates: a hostile archive is small on disk and huge in memory
                    byte[] part = zin.readNBytes(MAX_TEXT * 4);
                    total += part.length;
                    if (total > MAX_TEXT * 8) return null;
                    entries.add(new String[]{n, new String(part, StandardCharsets.UTF_8)});
                }
            }
        } catch (Exception ex) {
            return null;
        }
        // a Word file and an EPUB are read by the one reader the translation reads them with: headings, lists, tables, notes, text boxes
        if (has(entries, "word/document.xml")) { Doc d = unitsOf(bytes, "docx"); if (d != null) return d; return docx(entries); }
        if (entries.stream().anyMatch(x -> x[0].matches("ppt/slides/slide\\d+\\.xml"))) return pptx(entries);
        if (has(entries, "content.xml") && entries.stream()
                .anyMatch(x -> x[0].equals("mimetype") && x[1].contains("opendocument"))) return odt(entries);
        if (entries.stream().anyMatch(x -> x[0].endsWith(".opf"))
                || entries.stream().anyMatch(x -> x[0].equals("mimetype") && x[1].contains("epub"))) {
            Doc d = unitsOf(bytes, "epub");
            return d != null ? d : epub(entries);
        }
        return null;
    }

    /** A Word file or an EPUB through {@link DocUnits}, written as Markdown (a note's mark [^n] where it stands, the notes at the end); null when it could not be cut. */
    static Doc unitsOf(byte[] bytes, String kind) {
        try {
            DocUnits.Doc d = kind.equals("docx") ? DocUnits.docx(bytes, "") : DocUnits.epub(bytes, "");
            String text = DocWriters.markdown(d);
            return new Doc(cap(text), d.title().isBlank() ? firstLine(text.replaceAll("(?m)^#+\\s+", "")) : d.title(), kind);
        } catch (IOException | RuntimeException e) { return null; }
    }

    private static boolean has(List<String[]> entries, String name) {
        return entries.stream().anyMatch(x -> x[0].equals(name));
    }

    private static String get(List<String[]> entries, String name) {
        for (String[] x : entries) if (x[0].equals(name)) return x[1];
        return "";
    }

    static Doc docx(List<String[]> entries) {
        String xml = get(entries, "word/document.xml");
        String text = xml.replaceAll("<w:tab/>", "\t").replaceAll("</w:p>", "\n");
        String title = "";
        String core = get(entries, "docProps/core.xml");
        Matcher m = Pattern.compile("<dc:title>(.*?)</dc:title>").matcher(core);
        if (m.find()) title = unescape(m.group(1)).strip();
        String body = stripTags(text);
        if (title.isEmpty()) title = firstLine(body);
        return new Doc(cap(body), title, "docx");
    }

    static Doc pptx(List<String[]> entries) {
        List<String[]> slides = new ArrayList<>();
        for (String[] x : entries) if (x[0].matches("ppt/slides/slide\\d+\\.xml")) slides.add(x);
        slides.sort(Comparator.comparingInt(x -> Integer.parseInt(x[0].replaceAll("\\D", ""))));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String[] s : slides) {
            n++;
            sb.append("--- slide ").append(n).append(" ---\n")
              .append(stripTags(s[1].replaceAll("</a:p>", "\n"))).append('\n');
        }
        String title = "";
        String core = get(entries, "docProps/core.xml");
        Matcher m = Pattern.compile("<dc:title>(.*?)</dc:title>").matcher(core);
        if (m.find()) title = unescape(m.group(1)).strip();
        String body = sb.toString();
        if (title.isEmpty()) title = firstLine(body.replaceFirst("--- slide 1 ---\n", ""));
        return new Doc(cap(body), title, "pptx");
    }

    static Doc odt(List<String[]> entries) {
        String xml = get(entries, "content.xml")
                .replaceAll("</text:p>|</text:h>", "\n").replaceAll("<text:tab/>", "\t");
        String body = stripTags(xml);
        String title = "";
        Matcher m = Pattern.compile("<dc:title>(.*?)</dc:title>").matcher(get(entries, "meta.xml"));
        if (m.find()) title = unescape(m.group(1)).strip();
        if (title.isEmpty()) title = firstLine(body);
        return new Doc(cap(body), title, "odt");
    }

    /** Chapters in spine order when the OPF is readable, else by entry name. */
    static Doc epub(List<String[]> entries) {
        String opf = "";
        String opfDir = "";
        for (String[] x : entries) {
            if (x[0].endsWith(".opf")) {
                opf = x[1];
                int slash = x[0].lastIndexOf('/');
                opfDir = slash < 0 ? "" : x[0].substring(0, slash + 1);
                break;
            }
        }
        String title = "";
        Matcher tm = Pattern.compile("<dc:title[^>]*>(.*?)</dc:title>").matcher(opf);
        if (tm.find()) title = unescape(tm.group(1)).strip();

        List<String> order = new ArrayList<>();
        if (!opf.isEmpty()) {
            var hrefById = new HashMap<String, String>();
            Matcher im = Pattern.compile("<item\\b[^>]*>").matcher(opf);
            while (im.find()) {
                String tag = im.group();
                Matcher id = Pattern.compile("\\bid=\"([^\"]+)\"").matcher(tag);
                Matcher href = Pattern.compile("\\bhref=\"([^\"]+)\"").matcher(tag);
                if (id.find() && href.find()) hrefById.put(id.group(1), opfDir + href.group(1));
            }
            Matcher sm = Pattern.compile("<itemref\\b[^>]*idref=\"([^\"]+)\"").matcher(opf);
            while (sm.find()) {
                String h = hrefById.get(sm.group(1));
                if (h != null) order.add(h);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!order.isEmpty()) {
            for (String h : order) {
                String html = get(entries, h);
                if (!html.isEmpty()) sb.append(WebFetchTool.readable(html)).append("\n\n");
            }
        } else {
            entries.stream().filter(x -> x[0].matches(".*\\.(xhtml|html|htm)$"))
                    .sorted(Comparator.comparing(x -> x[0]))
                    .forEach(x -> sb.append(WebFetchTool.readable(x[1])).append("\n\n"));
        }
        String body = sb.toString();
        if (title.isEmpty()) title = firstLine(body);
        return new Doc(cap(body), title, "epub");
    }

    // ---- helpers ----------------------------------------------------------------------

    static String stripTags(String xml) {
        String t = xml.replaceAll("<[^>]+>", "");
        t = unescape(t);
        // collapse runs of spaces and blank lines; KEEP tabs (table cells) and paragraph breaks
        return t.replaceAll("[ \\x0B\\f\\r]+", " ").replaceAll(" *\\n *", "\n")
                .replaceAll(" *\\t *", "\t").replaceAll("\\n{3,}", "\n\n").strip();
    }

    static String unescape(String s) { return Entities.decode(s); }

    static String firstLine(String text) {
        for (String line : text.split("\n")) {
            String l = line.strip();
            if (l.length() >= 4) return l.length() > 120 ? l.substring(0, 117) + "..." : l;
        }
        return "";
    }

    private static String cap(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "\n\n[truncated at conversion]" : text;
    }
}
