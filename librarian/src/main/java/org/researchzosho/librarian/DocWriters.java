package org.researchzosho.librarian;

import org.researchzosho.librarian.DocUnits.Chapter;
import org.researchzosho.librarian.DocUnits.Doc;
import org.researchzosho.librarian.DocUnits.Unit;
import org.researchzosho.librarian.ExportDoc.Span;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Arrays;

/**
 * A document written out again from its units ({@link DocUnits}) in the same structure: Markdown keeps every block; plain text the words;
 * a PDF is a new PDF laid out from the text by the library's own writer ({@link ExportPdf}); a Word file is written by the library's own
 * writer ({@link ExportDocx}), with headings, lists and tables and none of the original's fonts and styles; an EPUB is a new package with
 * the chapters, the navigation, the pictures and the title, written here as the zip of XHTML an EPUB is, with no library.
 */
public final class DocWriters {
    private DocWriters() { }

    /** A file written: its name's extension, its content type, its bytes, and how many characters a PDF could not show. */
    public record Written(String extension, String contentType, byte[] bytes, int unshown) { }

    public static String contentType(String format) {
        return switch (format) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "epub" -> "application/epub+zip";
            case "md" -> "text/markdown; charset=utf-8";
            default -> "text/plain; charset=utf-8";
        };
    }

    /** The document in {@code format} (md, txt, pdf, docx, epub); {@code footer} goes on a PDF's and a Word file's pages. */
    public static Written write(Doc d, String format, String footer) throws IOException {
        switch (format) {
            case "md" -> { return new Written("md", contentType("md"), markdown(d).getBytes(StandardCharsets.UTF_8), 0); }
            case "txt" -> { return new Written("txt", contentType("txt"), text(d).getBytes(StandardCharsets.UTF_8), 0); }
            case "pdf" -> { ExportPdf.Result r = ExportPdf.write(ExportDoc.of(d.title(), d.lang(), footer, markdown(d, false), Map.of())); return new Written("pdf", contentType("pdf"), r.bytes(), r.unshown()); }
            case "docx" -> { return new Written("docx", contentType("docx"), ExportDocx.write(ExportDoc.of(d.title(), d.lang(), footer, markdown(d, false), Map.of())), 0); }
            case "epub" -> { return new Written("epub", contentType("epub"), epub(d), 0); }
            default -> throw new IOException("The translation writes md, txt, pdf, docx or epub, not " + format + ".");
        }
    }

    // ---- Markdown ----

    /** The document as Markdown, block by block; a table's first row is its header, as Markdown needs one. */
    public static String markdown(Doc d) { return markdown(d, true); }

    /** The same; {@code images} false leaves the pictures out, for a PDF or a Word file, which carry none. */
    static String markdown(Doc d, boolean images) {
        StringBuilder b = new StringBuilder();
        for (Chapter c : d.chapters()) {
            if (b.length() > 0) b.append("\n");
            blocks(images ? c.units() : c.units().stream().filter(u -> !u.kind().equals(DocUnits.IMAGE)).toList(), b, false);
        }
        String md = b.toString().strip() + "\n";
        return images ? md : superscriptMarks(md.replaceAll("(?m)^\\[\\^(\\d+)]: ", "[^$1] "));   // a PDF or a Word file shows a note's mark as a raised number, not Markdown's [^1]
    }

    private static final Pattern NOTE_MARK = Pattern.compile("\\[\\^(\\d+)]");

    /** Note marks [^12] as raised numbers ¹², for the formats that show the mark itself. */
    static String superscriptMarks(String s) {
        Matcher m = NOTE_MARK.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            StringBuilder sup = new StringBuilder();
            for (char c : m.group(1).toCharArray()) sup.append("⁰¹²³⁴⁵⁶⁷⁸⁹".charAt(c - '0'));
            m.appendReplacement(b, Matcher.quoteReplacement(sup.toString()));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** Note marks [^12] as [12], for plain text. */
    static String bracketMarks(String s) { return NOTE_MARK.matcher(s).replaceAll("[$1]"); }

    /** The words alone: headings as lines, lists with their marks, a table's rows with their cells side by side, the marks within a line taken off. */
    public static String text(Doc d) {
        StringBuilder b = new StringBuilder();
        for (Chapter c : d.chapters()) {
            if (b.length() > 0) b.append("\n");
            blocks(c.units(), b, true);
        }
        return bracketMarks(b.toString().strip()) + "\n";
    }

    private static void blocks(List<Unit> units, StringBuilder b, boolean plain) {
        int i = 0;
        int[] counters = new int[4];
        String prev = "";
        while (i < units.size()) {
            Unit u = units.get(i);
            String kind = u.kind();
            if (!kind.equals(DocUnits.ITEM)) Arrays.fill(counters, 0);
            if (!prev.isEmpty() && !(prev.equals(DocUnits.ITEM) && kind.equals(DocUnits.ITEM))) b.append('\n');
            switch (kind) {
                case DocUnits.TITLE -> { b.append(plain ? words(u.text()) : "# " + u.text()).append('\n'); i++; }
                case DocUnits.HEADING -> { b.append(plain ? words(u.text()) : "#".repeat(Math.max(1, Math.min(6, u.level()))) + " " + u.text()).append('\n'); i++; }
                case DocUnits.PARA -> { b.append(u.extra().equals("quote") && !plain ? "> " : "").append(plain ? words(u.text()) : u.text()).append('\n'); i++; }
                case DocUnits.ITEM -> {
                    int depth = Math.max(0, Math.min(3, u.level()));
                    for (int k = depth + 1; k < 4; k++) counters[k] = 0;
                    String mark = u.ordered() ? (++counters[depth]) + ". " : "- ";
                    b.append("  ".repeat(depth)).append(mark).append(plain ? words(u.text()) : u.text()).append('\n'); i++;
                }
                case DocUnits.CELL -> {
                    int table = u.table(); int cols = 0;
                    List<List<String>> rows = new ArrayList<>();
                    while (i < units.size() && units.get(i).kind().equals(DocUnits.CELL) && units.get(i).table() == table) {
                        Unit cell = units.get(i);
                        if (rows.isEmpty() || cell.row() != lastRow(units, i)) rows.add(new ArrayList<>());
                        rows.get(rows.size() - 1).add(plain ? words(cell.text()) : cell.text().replace("|", "\\|"));
                        cols = Math.max(cols, rows.get(rows.size() - 1).size());
                        i++;
                    }
                    for (int r = 0; r < rows.size(); r++) {
                        List<String> row = rows.get(r);
                        while (row.size() < cols) row.add("");
                        b.append(plain ? String.join(" | ", row) : "| " + String.join(" | ", row) + " |").append('\n');
                        if (r == 0 && !plain) { b.append("|"); for (int k = 0; k < cols; k++) b.append(" --- |"); b.append('\n'); }
                    }
                }
                case DocUnits.CAPTION -> { b.append(plain ? words(u.text()) : "*" + u.text() + "*").append('\n'); i++; }
                case DocUnits.CODE -> { if (plain) { String t = u.text().replaceAll("(?m)^```.*$\\n?", ""); b.append(t.endsWith("\n") ? t : t + "\n"); } else b.append(u.text()).append('\n'); i++; }
                case DocUnits.RULE -> { b.append(plain ? "----" : "---").append('\n'); i++; }
                case DocUnits.IMAGE -> { b.append(plain ? "[" + (u.text().isBlank() ? "picture" : u.text()) + ": " + u.extra() + "]" : "![" + u.text() + "](" + u.extra() + ")").append('\n'); i++; }
                case DocUnits.NOTE -> { b.append("[^").append(u.level()).append("]").append(plain ? " " + words(u.text()) : ": " + u.text()).append('\n'); i++; }   // Markdown's footnote; its number raised or bracketed for the other formats
                default -> { b.append(u.text()).append('\n'); i++; }
            }
            prev = kind;
        }
    }

    /** The row of the cell before {@code i} of the same table, or -2 for none. */
    private static int lastRow(List<Unit> units, int i) {
        Unit u = units.get(i);
        if (i == 0) return -2;
        Unit p = units.get(i - 1);
        return p.kind().equals(DocUnits.CELL) && p.table() == u.table() ? p.row() : -2;
    }

    static String words(String s) { return DocUnits.plainText(s); }

    // ---- EPUB ----

    static final String XHTML = "http://www.w3.org/1999/xhtml", EPUB_NS = "http://www.idpf.org/2007/ops";

    /**
     * The EPUB 3 package of a document: mimetype first and stored, the container, the package file with the title, the language, the
     * creator and the original's title as an alternative title, the navigation document, one XHTML file a chapter, the pictures the
     * chapters show. A reader opens it by the package's own rules; nothing of the original's styling is carried.
     */
    public static byte[] epub(Doc d) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, String> imageHref = new LinkedHashMap<>();   // the original's path → the new package's
        Map<String, String> used = new LinkedHashMap<>();
        for (String path : d.images().keySet()) {
            String base = path.replaceAll("^.*/", "").replaceAll("[^A-Za-z0-9._-]", "_");
            String name = base; int n = 2;
            while (used.containsKey(name)) name = (n++) + "-" + base;
            used.put(name, path); imageHref.put(path, "images/" + name);
        }
        List<String> chapterNames = new ArrayList<>();
        for (int i = 0; i < d.chapters().size(); i++) chapterNames.add(chapterTitle(d.chapters().get(i), d.lang(), i + 1));
        try (ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            byte[] mime = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            ZipEntry m = new ZipEntry("mimetype"); m.setMethod(ZipEntry.STORED); m.setSize(mime.length); m.setCompressedSize(mime.length);
            CRC32 crc = new CRC32(); crc.update(mime); m.setCrc(crc.getValue());
            z.putNextEntry(m); z.write(mime); z.closeEntry();
            put(z, "META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>\n");
            StringBuilder manifest = new StringBuilder(), spine = new StringBuilder();
            manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>");
            for (int i = 0; i < d.chapters().size(); i++) {
                String file = String.format(Locale.ROOT, "ch%03d.xhtml", i + 1);
                manifest.append("<item id=\"ch").append(i + 1).append("\" href=\"").append(file).append("\" media-type=\"application/xhtml+xml\"/>");
                spine.append("<itemref idref=\"ch").append(i + 1).append("\"/>");
                put(z, "OEBPS/" + file, chapterXhtml(d.chapters().get(i), chapterNames.get(i), d.lang(), imageHref));
            }
            int k = 0;
            for (var e : used.entrySet()) {
                k++;
                manifest.append("<item id=\"img").append(k).append("\" href=\"images/").append(xml(e.getKey())).append("\" media-type=\"").append(mediaType(e.getKey())).append("\"/>");
                z.putNextEntry(new ZipEntry("OEBPS/images/" + e.getKey())); z.write(d.images().get(e.getValue())); z.closeEntry();
            }
            String original = d.meta().getOrDefault("original_title", ""), source = d.meta().getOrDefault("source", ""), creator = d.meta().getOrDefault("creator", "");
            put(z, "OEBPS/content.opf", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\" xml:lang=\"" + xml(d.lang()) + "\">"
                    + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                    + "<dc:identifier id=\"uid\">urn:uuid:" + UUID.randomUUID() + "</dc:identifier>"
                    + "<dc:title>" + xml(d.title()) + "</dc:title>"
                    + "<dc:language>" + xml(d.lang()) + "</dc:language>"
                    + (creator.isEmpty() ? "" : "<dc:creator>" + xml(creator) + "</dc:creator>")
                    + (original.isEmpty() ? "" : "<meta property=\"dcterms:alternative\">" + xml(original) + "</meta>")
                    + (source.isEmpty() ? "" : "<dc:source>" + xml(source) + "</dc:source>")
                    + "<dc:contributor>ResearchZosho</dc:contributor>"
                    + "<meta property=\"dcterms:modified\">" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "</meta>"
                    + "</metadata><manifest>" + manifest + "</manifest><spine>" + spine + "</spine></package>\n");
            StringBuilder nav = new StringBuilder();
            for (int i = 0; i < d.chapters().size(); i++) nav.append("<li><a href=\"").append(String.format(Locale.ROOT, "ch%03d.xhtml", i + 1)).append("\">").append(xml(chapterNames.get(i))).append("</a></li>");
            put(z, "OEBPS/nav.xhtml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"" + XHTML + "\" xmlns:epub=\"" + EPUB_NS + "\" xml:lang=\"" + xml(d.lang()) + "\" lang=\"" + xml(d.lang()) + "\"><head><title>" + xml(d.title()) + "</title></head>"
                    + "<body><nav epub:type=\"toc\" id=\"toc\"><h1>" + xml(d.title()) + "</h1><ol>" + nav + "</ol></nav></body></html>\n");
        }
        return out.toByteArray();
    }

    /** A chapter's name for the navigation: its first heading, else its number in the document's language. */
    static String chapterTitle(Chapter c, String lang, int n) {
        for (Unit u : c.units()) if (u.kind().equals(DocUnits.TITLE) || u.kind().equals(DocUnits.HEADING)) return DocUnits.plainText(u.text());
        return switch (lang) { case "ja" -> "第" + n + "章"; case "zh" -> "第" + n + "章"; case "ko" -> "제" + n + "장"; case "de" -> "Kapitel " + n; case "fr" -> "Chapitre " + n; case "es" -> "Capítulo " + n; case "pt" -> "Capítulo " + n; case "it" -> "Capitolo " + n; case "ru" -> "Глава " + n; default -> "Chapter " + n; };
    }

    private static String chapterXhtml(Chapter c, String title, String lang, Map<String, String> imageHref) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"" + XHTML + "\" xmlns:epub=\"" + EPUB_NS + "\" xml:lang=\"" + xml(lang) + "\" lang=\"" + xml(lang) + "\"><head><title>" + xml(title) + "</title></head><body>\n");
        List<Unit> units = c.units();
        int i = 0;
        List<String> openLists = new ArrayList<>();   // "ul" or "ol" per open depth
        while (i < units.size()) {
            Unit u = units.get(i);
            if (!u.kind().equals(DocUnits.ITEM)) closeLists(b, openLists, 0);
            switch (u.kind()) {
                case DocUnits.TITLE -> { b.append("<h1>").append(inline(u.text())).append("</h1>\n"); i++; }
                case DocUnits.HEADING -> { int l = Math.max(1, Math.min(6, u.level())); b.append("<h").append(l).append(">").append(inline(u.text())).append("</h").append(l).append(">\n"); i++; }
                case DocUnits.PARA -> { b.append(u.extra().equals("quote") ? "<blockquote><p>" : "<p>").append(inline(u.text())).append(u.extra().equals("quote") ? "</p></blockquote>\n" : "</p>\n"); i++; }
                case DocUnits.ITEM -> {
                    int depth = Math.max(0, Math.min(3, u.level()));
                    String want = u.ordered() ? "ol" : "ul";
                    closeLists(b, openLists, depth + 1);
                    while (openLists.size() < depth + 1) { b.append("<").append(openLists.size() == depth ? want : "ul").append(">\n"); openLists.add(openLists.size() == depth ? want : "ul"); }
                    b.append("<li>").append(inline(u.text())).append("</li>\n"); i++;
                }
                case DocUnits.CELL -> {
                    int table = u.table(); int row = Integer.MIN_VALUE;
                    b.append("<table>\n");
                    while (i < units.size() && units.get(i).kind().equals(DocUnits.CELL) && units.get(i).table() == table) {
                        Unit cell = units.get(i);
                        if (cell.row() != row) { if (row != Integer.MIN_VALUE) b.append("</tr>\n"); b.append("<tr>"); row = cell.row(); }
                        String tag = cell.level() == 1 ? "th" : "td";
                        b.append("<").append(tag).append(">").append(inline(cell.text())).append("</").append(tag).append(">");
                        i++;
                    }
                    if (row != Integer.MIN_VALUE) b.append("</tr>\n");
                    b.append("</table>\n");
                }
                case DocUnits.CAPTION -> { b.append("<p class=\"caption\"><em>").append(inline(u.text())).append("</em></p>\n"); i++; }
                case DocUnits.CODE -> { String t = u.text().replaceAll("(?m)^```.*$\\n?", ""); b.append("<pre>").append(xml(t.replaceAll("\\s+$", ""))).append("</pre>\n"); i++; }
                case DocUnits.RULE -> { b.append("<hr/>\n"); i++; }
                case DocUnits.IMAGE -> { String href = imageHref.get(u.extra()); if (href != null) b.append("<p><img src=\"").append(xml(href)).append("\" alt=\"").append(xml(u.text())).append("\"/></p>\n"); i++; }
                case DocUnits.NOTE -> { b.append("<aside epub:type=\"footnote\" id=\"fn-").append(u.level()).append("\"><p>").append(u.level()).append(". ").append(inline(u.text())).append("</p></aside>\n"); i++; }
                default -> { b.append("<p>").append(inline(u.text())).append("</p>\n"); i++; }
            }
        }
        closeLists(b, openLists, 0);
        return b.append("</body></html>\n").toString();
    }

    private static void closeLists(StringBuilder b, List<String> open, int keep) {
        while (open.size() > keep) b.append("</").append(open.remove(open.size() - 1)).append(">\n");
    }

    /** A line's Markdown marks as XHTML: bold, italic, code and links; a note's mark a raised link to the note in the chapter. */
    static String inline(String text) {
        Matcher m = NOTE_MARK.matcher(text);
        if (m.find()) {
            StringBuilder out = new StringBuilder(); int at = 0;
            do { out.append(inlineMarks(text.substring(at, m.start()))).append("<sup><a epub:type=\"noteref\" href=\"#fn-").append(m.group(1)).append("\">").append(m.group(1)).append("</a></sup>"); at = m.end(); } while (m.find());
            return out.append(inlineMarks(text.substring(at))).toString();
        }
        return inlineMarks(text);
    }

    private static String inlineMarks(String text) {
        StringBuilder b = new StringBuilder();
        for (Span s : ExportDoc.inline(text, Map.of())) {
            String t = xml(s.text());
            if (s.code()) t = "<code>" + t + "</code>";
            if (s.bold()) t = "<b>" + t + "</b>";
            if (s.italic()) t = "<i>" + t + "</i>";
            if (s.href() != null) t = "<a href=\"" + xml(s.href()) + "\">" + t + "</a>";
            b.append(t);
        }
        return b.toString();
    }

    static String mediaType(String name) {
        String ext = name.toLowerCase(Locale.ROOT).replaceAll("^.*\\.", "");
        return switch (ext) { case "png" -> "image/png"; case "jpg", "jpeg" -> "image/jpeg"; case "gif" -> "image/gif"; case "svg" -> "image/svg+xml"; case "webp" -> "image/webp"; default -> "application/octet-stream"; };
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    static String xml(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : (s == null ? "" : s).toCharArray()) {
            switch (c) { case '&' -> b.append("&amp;"); case '<' -> b.append("&lt;"); case '>' -> b.append("&gt;"); case '"' -> b.append("&quot;"); default -> { if (c >= 0x20 || c == '\n' || c == '\t') b.append(c); } }
        }
        return b.toString();
    }
}
