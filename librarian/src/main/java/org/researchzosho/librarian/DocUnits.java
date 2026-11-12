package org.researchzosho.librarian;

import org.researchzosho.tools.Entities;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.function.UnaryOperator;
import java.util.HashSet;
import org.w3c.dom.Document;

/**
 * A document a person hands over, cut into its units in order: headings, paragraphs, list items, table cells, captions; an EPUB by chapter
 * and then unit; Markdown by block; a Word file by paragraph, list item and cell; a PDF's text by paragraph. Each unit is what one
 * translation translates, and the writers ({@link DocWriters}) rebuild a document from the units in the same structure. The structure is
 * kept, the layout is not: the fonts, the styles and the pages of the original are never read.
 *
 * <p>Within a unit the words carry Markdown's own marks for bold, italic, code and a link, read from the original's marks where the format
 * has them, so that they pass through a translation and come out again in every format.
 */
public final class DocUnits {
    private DocUnits() { }

    /** The kinds of unit. {@link #translated(String)} says which carry words to translate. */
    public static final String TITLE = "title", HEADING = "heading", PARA = "para", ITEM = "item", CELL = "cell", CAPTION = "caption", CODE = "code", RULE = "rule", IMAGE = "image", NOTE = "note";

    /**
     * One unit: its kind; its text (Markdown's inline marks within it); its level (a heading's 1–6, an item's depth from 0, 1 for a
     * header cell; a note's number); for a cell the table it belongs to and its row; for an item whether its list is numbered; and {@code
     * extra}: an image's path, "quote" on a paragraph from a block quote, "front" on front matter, a note's kind (footnote, endnote,
     * comment). A note's mark stands in the text where the reader meets it as {@code [^n]}, Markdown's own; its words are a unit of their
     * own at the end of the document, after a rule.
     */
    public record Unit(String kind, String text, int level, int table, int row, boolean ordered, String extra) {
        public static Unit of(String kind, String text) { return new Unit(kind, text, 0, -1, -1, false, ""); }
        public static Unit heading(int level, String text) { return new Unit(HEADING, text, level, -1, -1, false, ""); }
        public static Unit item(int depth, boolean ordered, String text) { return new Unit(ITEM, text, depth, -1, -1, ordered, ""); }
        public static Unit cell(int table, int row, boolean header, String text) { return new Unit(CELL, text, header ? 1 : 0, table, row, false, ""); }
        public Unit withText(String t) { return new Unit(kind, t, level, table, row, ordered, extra); }
        /** Whether the unit has words to translate: its kind is one that is translated, and it holds a letter outside markup (an image line, a rule or a blank has none: the translator returned nothing for five of them, 2026-11-07). */
        public boolean translated() { return DocUnits.translated(kind) && !text.isBlank() && plainText(text.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")).codePoints().anyMatch(Character::isLetter); }
    }

    /** A chapter: an EPUB's, or the one chapter of any other format. {@code file} is the chapter's own name in the original, "" otherwise. */
    public record Chapter(String file, List<Unit> units) { }

    /**
     * The document: its format as cut (md, txt, pdf, docx, epub), its title, the language of its words as read off its letters, its
     * chapters, the pictures an EPUB carries (by the path the chapters name them by), and the original's metadata an EPUB keeps (creator).
     */
    public record Doc(String format, String title, String lang, List<Chapter> chapters, Map<String, byte[]> images, Map<String, String> meta, PdfReading.Read reading, Languages.Guess language) {
        public Doc(String format, String title, String lang, List<Chapter> chapters, Map<String, byte[]> images, Map<String, String> meta) { this(format, title, lang, chapters, images, meta, null, null); }
        public Doc(String format, String title, String lang, List<Chapter> chapters, Map<String, byte[]> images, Map<String, String> meta, PdfReading.Read reading) { this(format, title, lang, chapters, images, meta, reading, null); }
        /** How the language was worked out: the text, the file's own tag, or the person's word; a guess by the script when none was kept. */
        public Languages.Guess guess() { return language != null ? language : new Languages.Guess(lang, "", 1, 0, Languages.Guess.SCRIPT); }
        /** The same document, said by the person to be written in {@code language}. */
        public Doc saidIn(String language) { Languages.Guess g = Languages.said(language, guess()); return new Doc(format, title, g.code(), chapters, images, meta, reading, g); }
        /** Every unit, across the chapters, in order. */
        public List<Unit> units() { List<Unit> out = new ArrayList<>(); for (Chapter c : chapters) out.addAll(c.units()); return out; }
        /** The units that carry words to translate. */
        public List<Unit> translatable() { List<Unit> out = new ArrayList<>(); for (Unit u : units()) if (u.translated()) out.add(u); return out; }
        /** The characters of the translatable units. */
        public int chars() { int n = 0; for (Unit u : translatable()) n += u.text().length(); return n; }
        /** The same document with its units replaced, chapter by chapter, by {@code f} applied to each. */
        public Doc map(UnaryOperator<Unit> f) {
            List<Chapter> cs = new ArrayList<>();
            for (Chapter c : chapters) { List<Unit> us = new ArrayList<>(); for (Unit u : c.units()) us.add(f.apply(u)); cs.add(new Chapter(c.file(), us)); }
            return new Doc(format, title, lang, cs, images, meta, reading, language);
        }
        public Doc withTitle(String t) { return new Doc(format, t, lang, chapters, images, meta, reading, language); }
    }

    public static boolean translated(String kind) { return kind.equals(TITLE) || kind.equals(HEADING) || kind.equals(PARA) || kind.equals(ITEM) || kind.equals(CELL) || kind.equals(CAPTION) || kind.equals(NOTE); }

    public static final Set<String> IN = Set.of("txt", "md", "pdf", "docx", "epub"), OUT = Set.of("md", "txt", "pdf", "docx", "epub");

    /** A document's format by its bytes, then its name: md, txt, pdf, docx, epub; null when it is none of them. */
    public static String format(byte[] bytes, String name) {
        String ext = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceFirst("^.*\\.", "");
        if (bytes != null && bytes.length > 4) {
            if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') return "pdf";
            if (bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4) {
                Map<String, byte[]> z = unzip(bytes);
                if (z.containsKey("word/document.xml")) return "docx";
                if (z.containsKey("META-INF/container.xml") || z.keySet().stream().anyMatch(n -> n.endsWith(".opf"))) return "epub";
                return null;
            }
        }
        return switch (ext) { case "md", "markdown" -> "md"; case "txt", "text" -> "txt"; case "pdf", "docx", "epub" -> ext; default -> ext.isEmpty() || !name.contains(".") ? "txt" : null; };
    }

    /** The document cut into its units; an IOException names what could not be read. A PDF's pages that are pictures of text are read by eye, as many as the shelves read. */
    public static Doc cut(byte[] bytes, String name) throws IOException { return cut(bytes, name, PdfReading.Options.reading(PdfReading.SHELF_EYE_PAGES)); }

    /** The same, a PDF read as {@code pdf} says: its preflight alone (a plan), or every page by eye a long job reads with its checkpoint. */
    public static Doc cut(byte[] bytes, String name, PdfReading.Options pdf) throws IOException {
        String fmt = format(bytes, name);
        if (fmt == null) throw new IOException("The file " + name + " is not a format the translation reads: txt, md, pdf, docx or epub.");
        return switch (fmt) {
            case "md" -> markdown(new String(bytes, StandardCharsets.UTF_8), stem(name));
            case "txt" -> text(new String(bytes, StandardCharsets.UTF_8), stem(name), "txt");
            case "pdf" -> pdf(PdfReading.read(bytes, pdf), stem(name));
            case "docx" -> docx(bytes, stem(name));
            case "epub" -> epub(bytes, stem(name));
            default -> throw new IOException("The file " + name + " is not a format the translation reads.");
        };
    }

    /**
     * A PDF cut by the reader's blocks ({@link PdfReading}): its title and headings as headings, its paragraphs, its captions; a figure's
     * labels and its pictures are not units (a translation carries no pictures), and a page that needed the eye and was not read gives
     * none. The reading goes with the document, so that a plan and a summary say how its pages were read.
     */
    static Doc pdf(PdfReading.Read r, String name) {
        List<Unit> out = new ArrayList<>();
        for (PdfLayout.Page p : r.pages()) {
            for (PdfLayout.Block b : p.blocks()) {   // a page not read holds no blocks, or only the lines of a garbled page that are words
                String t = b.text().strip();
                if (t.isEmpty()) continue;
                switch (b.kind()) {
                    case PdfLayout.TITLE -> out.add(Unit.heading(1, t));
                    case PdfLayout.HEADING -> out.add(Unit.heading(b.level() <= 1 ? 2 : 3, t));
                    case PdfLayout.CAPTION -> out.add(Unit.of(CAPTION, t));
                    case PdfLayout.PARA -> out.add(Unit.of(PARA, t));
                    default -> { }
                }
            }
        }
        String title = r.title();
        StringBuilder all = new StringBuilder(); for (Unit u : out) if (u.translated()) all.append(u.text()).append('\n');
        Languages.Guess g = Languages.ofDocument(all.length() > 0 ? all.toString() : "", r.lang());   // the text, and the PDF's own Lang where the text agrees or is too short to say
        String lang = !g.code().isEmpty() ? g.code() : !r.preflight().language().isEmpty() ? r.preflight().language() : "en";
        return new Doc("pdf", title.isBlank() ? name : title, lang, List.of(new Chapter("", out)), Map.of(), Map.of(), r, g);
    }

    static String stem(String name) { return name == null ? "document" : name.replaceAll("^.*[\\\\/]", "").replaceAll("\\.[A-Za-z0-9]+$", ""); }

    // ---- Markdown, by block ----

    private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+•]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,5})[.)]\\s+(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?");
    private static final Pattern RULE_LINE = Pattern.compile("^(?:-\\s*){3,}$|^(?:\\*\\s*){3,}$|^(?:_\\s*){3,}$");
    private static final Pattern IMAGE_LINE = Pattern.compile("^!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)$");

    /** Markdown cut by block: every heading, paragraph, list item, table cell, code block, rule and image is a unit; front matter is kept as it is. */
    public static Doc markdown(String markdown, String name) {
        List<Unit> out = new ArrayList<>();
        String text = (markdown == null ? "" : markdown).replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n", -1);
        int i = 0;
        if (lines.length > 2 && lines[0].strip().equals("---")) {   // front matter: kept, not translated
            int end = -1; for (int k = 1; k < lines.length; k++) if (lines[k].strip().equals("---")) { end = k; break; }
            if (end > 0) { out.add(new Unit(CODE, String.join("\n", List.of(lines).subList(0, end + 1)), 0, -1, -1, false, "front")); i = end + 1; }
        }
        StringBuilder para = new StringBuilder(); boolean quote = false;
        List<Integer> indents = new ArrayList<>();
        int table = 0, row = -1; boolean inTable = false, headerNext = false; List<Unit> pendingRow = new ArrayList<>();
        for (; i < lines.length; i++) {
            String raw = lines[i], line = raw.stripTrailing(), t = line.strip();
            if (t.startsWith("```")) {
                flush(out, para, quote); quote = false;
                StringBuilder code = new StringBuilder(raw);
                for (i++; i < lines.length; i++) { code.append('\n').append(lines[i]); if (lines[i].strip().startsWith("```")) break; }
                out.add(Unit.of(CODE, code.toString())); indents.clear();
                continue;
            }
            if (t.length() > 1 && t.startsWith("|") && t.endsWith("|")) {
                flush(out, para, quote); quote = false; indents.clear();
                if (TABLE_SEP.matcher(t).matches()) { if (row == 0) for (int k = 0; k < pendingRow.size(); k++) pendingRow.set(k, new Unit(CELL, pendingRow.get(k).text(), 1, table, 0, false, "")); continue; }
                if (!inTable) { inTable = true; table++; row = -1; }
                out.addAll(pendingRow); pendingRow.clear();
                row++;
                for (String cell : t.substring(1, t.length() - 1).split("(?<!\\\\)\\|", -1)) pendingRow.add(Unit.cell(table, row, false, cell.strip().replace("\\|", "|")));
                continue;
            } else if (inTable) { out.addAll(pendingRow); pendingRow.clear(); inTable = false; row = -1; }
            if (t.isEmpty()) { flush(out, para, quote); quote = false; indents.clear(); continue; }
            Matcher h = HEADING_LINE.matcher(line);
            if (line.startsWith("#") && h.matches()) { flush(out, para, quote); quote = false; indents.clear(); out.add(Unit.heading(h.group(1).length(), h.group(2))); continue; }
            if (RULE_LINE.matcher(t).matches()) { flush(out, para, quote); quote = false; indents.clear(); out.add(Unit.of(RULE, "")); continue; }
            Matcher img = IMAGE_LINE.matcher(t);
            if (img.matches()) { flush(out, para, quote); quote = false; out.add(new Unit(IMAGE, img.group(1), 0, -1, -1, false, img.group(2))); continue; }
            Matcher b = BULLET.matcher(line), n = NUMBERED.matcher(line);
            boolean bullet = b.matches() && !RULE_LINE.matcher(t).matches(), numbered = !bullet && n.matches();
            if (bullet || numbered) {
                flush(out, para, quote); quote = false;
                int indent = (bullet ? b.group(1) : n.group(1)).replace("\t", "    ").length();
                while (!indents.isEmpty() && indent < indents.get(indents.size() - 1)) indents.remove(indents.size() - 1);
                if (indents.isEmpty() || indent > indents.get(indents.size() - 1)) indents.add(indent);
                out.add(Unit.item(Math.min(3, indents.size() - 1), numbered, bullet ? b.group(2) : n.group(3)));
                continue;
            }
            if (!indents.isEmpty() && raw.startsWith(" ") && !out.isEmpty() && out.get(out.size() - 1).kind().equals(ITEM) && para.length() == 0) {   // a line indented under a list line continues it
                Unit last = out.get(out.size() - 1); out.set(out.size() - 1, last.withText(last.text() + " " + t)); continue;
            }
            indents.clear();
            if (t.startsWith(">")) { if (para.length() > 0 && !quote) flush(out, para, false); quote = true; t = t.replaceFirst("^>\\s?", ""); if (t.isEmpty()) continue; }
            else if (quote && para.length() > 0) { flush(out, para, true); quote = false; }
            if (para.length() > 0) para.append(' ');
            para.append(t);
        }
        flush(out, para, quote);
        out.addAll(pendingRow);
        return doc("md", titleOf(out, name), out, Map.of(), Map.of());
    }

    private static final Pattern ITALIC_LINE = Pattern.compile("^\\*([^*]+)\\*$|^_([^_]+)_$");

    private static void flush(List<Unit> out, StringBuilder para, boolean quote) {
        if (para.length() == 0) return;
        String text = para.toString();
        para.setLength(0);
        Matcher it = ITALIC_LINE.matcher(text);
        String last = out.isEmpty() ? "" : out.get(out.size() - 1).kind();
        if (!quote && it.matches() && (last.equals(IMAGE) || last.equals(CELL))) { out.add(Unit.of(CAPTION, it.group(1) != null ? it.group(1) : it.group(2))); return; }   // a line in italics under a picture or a table is its caption
        out.add(new Unit(PARA, text, 0, -1, -1, false, quote ? "quote" : ""));
    }

    private static Doc doc(String format, String title, List<Unit> units, Map<String, byte[]> images, Map<String, String> meta) { return doc(format, title, units, images, meta, ""); }

    /** The document, its language worked out from its text and, where the file names one, that ({@link Languages#ofDocument}). */
    private static Doc doc(String format, String title, List<Unit> units, Map<String, byte[]> images, Map<String, String> meta, String fileSays) {
        StringBuilder all = new StringBuilder(); for (Unit u : units) if (u.translated()) all.append(u.text()).append('\n');
        Languages.Guess g = Languages.ofDocument(all.toString(), fileSays);
        return new Doc(format, title, g.code().isEmpty() ? "en" : g.code(), List.of(new Chapter("", units)), images, meta, null, g);
    }

    private static String titleOf(List<Unit> units, String name) {
        for (Unit u : units) if (u.kind().equals(HEADING) || u.kind().equals(TITLE)) return plainText(u.text());
        return name;
    }

    /** The words of a unit's text with Markdown's inline marks taken off. */
    public static String plainText(String s) { return ExportDoc.plain(ExportDoc.inline(s, Map.of())); }

    // ---- plain text and a PDF's text, by paragraph ----

    private static final Pattern LIST_LINE = Pattern.compile("^\\s*(?:[-*•‣▪]|\\d{1,3}[.)]|[a-z][.)])\\s+(.*)$");

    /**
     * Text cut by paragraph, the paragraphs by blank lines: the lines of a paragraph run together; a line that is a list's line is an item;
     * a short line on its own with no sentence's end is taken for a heading, the first for the title. A PDF's page marks are dropped.
     */
    public static Doc text(String text, String name, String format) {
        List<Unit> out = new ArrayList<>();
        String t = (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n').replace("\f", "\n\n").replaceAll("(?m)^\\[page \\d+]\\s*$", "");
        boolean first = true;
        for (String block : t.split("\n\\s*\n")) {
            List<String> ls = new ArrayList<>();
            for (String l : block.split("\n")) if (!l.isBlank() && !PAGE_NUMBER.matcher(l.strip()).matches()) ls.add(l.strip());
            if (ls.isEmpty()) continue;
            // a short first line with no sentence's end above longer lines is the heading of what follows (a PDF's text keeps no blank line under a heading)
            if (ls.size() > 1 && headingLike(ls.get(0)) && ls.get(0).length() * 5 <= longest(ls) * 3) { out.add(Unit.heading(first ? 1 : 2, ls.get(0))); first = false; ls = ls.subList(1, ls.size()); }
            boolean list = ls.stream().allMatch(l -> LIST_LINE.matcher(l).matches());
            if (list) { for (String l : ls) { Matcher m = LIST_LINE.matcher(l); m.matches(); out.add(Unit.item(0, l.strip().matches("^\\d.*"), m.group(1))); } first = false; continue; }
            if (ls.size() == 1 && headingLike(ls.get(0))) {
                out.add(Unit.heading(first ? 1 : 2, ls.get(0))); first = false; continue;
            }
            first = false;
            StringBuilder p = new StringBuilder();
            for (String l : ls) {
                if (p.length() > 0) { char last = p.charAt(p.length() - 1); p.append(last == '-' ? "" : cjk(last) && cjk(l.charAt(0)) ? "" : " "); if (last == '-') p.setLength(p.length() - 1); }
                p.append(l);
            }
            out.add(Unit.of(PARA, p.toString()));
        }
        return doc(format, titleOf(out, name), out, Map.of(), Map.of());
    }

    private static final Pattern PAGE_NUMBER = Pattern.compile("(?i)^(?:page\\s+)?\\d{1,4}(?:\\s*(?:of|/)\\s*\\d{1,4})?$|^-\\s*\\d{1,4}\\s*-$|^\\d{1,4}\\s*ページ$");

    static boolean headingLike(String l) { return l.length() <= 80 && !l.matches(".*[.。!?！？:;,]$") && !LIST_LINE.matcher(l).matches(); }
    private static int longest(List<String> ls) { int n = 0; for (String l : ls) n = Math.max(n, l.length()); return n; }

    private static boolean cjk(char c) { Character.UnicodeScript s = Character.UnicodeScript.of(c); return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL; }

    // ---- Word: the paragraphs, list items and cells of word/document.xml ----

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** A Word file cut by paragraph: the heading styles give headings, the numbering gives list items, the tables give cells; the pictures are not kept. */
    public static Doc docx(byte[] bytes, String name) throws IOException {
        Map<String, byte[]> z = unzip(bytes);
        byte[] main = z.get("word/document.xml");
        if (main == null) throw new IOException("The file has no word/document.xml: it is not a Word document.");
        Map<String, String> links = new LinkedHashMap<>();
        if (z.containsKey("word/_rels/document.xml.rels")) {
            Matcher m = Pattern.compile("<Relationship\\b[^>]*>").matcher(new String(z.get("word/_rels/document.xml.rels"), StandardCharsets.UTF_8));
            while (m.find()) { String tag = m.group(); Matcher id = Pattern.compile("\\bId=\"([^\"]+)\"").matcher(tag), target = Pattern.compile("\\bTarget=\"([^\"]+)\"").matcher(tag); if (id.find() && target.find() && tag.contains("hyperlink")) links.put(id.group(1), Entities.decode(target.group(1))); }
        }
        Set<String> bullets = bulletNums(z.get("word/numbering.xml"));
        Map<String, String> styles = styleNames(z.get("word/styles.xml"));
        Element body = parse(new String(main, StandardCharsets.UTF_8)).getDocumentElement();
        List<Unit> out = new ArrayList<>();
        int[] table = {0};
        TITLE_USED.set(false);
        Notes notes = new Notes(notesOf(z.get("word/footnotes.xml"), "footnote"), notesOf(z.get("word/endnotes.xml"), "endnote"), notesOf(z.get("word/comments.xml"), "comment"));
        NOTES.set(notes);
        try {
            for (Element b : children(body)) if (local(b).equals("body")) walkDocx(b, out, links, bullets, styles, table);
            // the notes the text marks, in the order it marks them: footnotes, endnotes and comments, each a unit after a rule at the end
            List<Unit> said = new ArrayList<>();
            for (int i = 0; i < notes.found.size(); i++) {
                Object[] f = notes.found.get(i);
                StringBuilder t = new StringBuilder();
                for (Element p : descendants((Element) f[1], "p", null)) { String r = runs(p, links).strip(); if (!r.isEmpty()) t.append(t.length() == 0 ? "" : " ").append(r); }
                said.add(new Unit(NOTE, t.toString().strip(), (int) f[0], -1, -1, false, (String) f[2]));
            }
            if (!said.isEmpty()) { out.add(Unit.of(RULE, "")); out.addAll(said); }
        } finally { TITLE_USED.remove(); NOTES.remove(); }
        String title = "", language = "";
        if (z.containsKey("docProps/core.xml")) {
            String core = new String(z.get("docProps/core.xml"), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<dc:title>(.*?)</dc:title>").matcher(core); if (m.find()) title = Entities.decode(m.group(1)).strip();
            Matcher l = Pattern.compile("<dc:language>(.*?)</dc:language>").matcher(core); if (l.find()) language = l.group(1).strip();
        }
        if (language.isEmpty() && z.containsKey("word/styles.xml")) {   // the document's default language, as Word sets it for its text
            Matcher l = Pattern.compile("<w:docDefaults>.*?<w:lang\\b[^>]*\\bw:val=\"([^\"]+)\"", Pattern.DOTALL).matcher(new String(z.get("word/styles.xml"), StandardCharsets.UTF_8));
            if (l.find()) language = l.group(1);
        }
        return doc("docx", title.isEmpty() ? titleOf(out, name) : title, out, Map.of(), Map.of(), language);
    }

    /** Whether the document used the Title style before this paragraph: then "heading 1" is a section under it, a level down, as our own writer sets them. */
    private static final ThreadLocal<Boolean> TITLE_USED = ThreadLocal.withInitial(() -> false);

    /** A Word file's notes by their id (footnotes, endnotes, comments), and the ones its text marks, numbered in the order it marks them: [number, element, kind]. */
    static final class Notes {
        final Map<String, Element> footnotes, endnotes, comments;
        final List<Object[]> found = new ArrayList<>();
        Notes(Map<String, Element> f, Map<String, Element> e, Map<String, Element> c) { footnotes = f; endnotes = e; comments = c; }
        /** The mark for the note {@code id} of {@code kind}, its number given the first time the text marks it; "" for a note the file does not hold. */
        String mark(String kind, String id) {
            Map<String, Element> m = kind.equals("footnote") ? footnotes : kind.equals("endnote") ? endnotes : comments;
            Element e = m.get(id);
            if (e == null) return "";
            for (Object[] f : found) if (f[1] == e) return "[^" + f[0] + "]";
            found.add(new Object[]{found.size() + 1, e, kind});
            return "[^" + found.size() + "]";
        }
    }
    private static final ThreadLocal<Notes> NOTES = new ThreadLocal<>();

    /** The notes of word/footnotes.xml, endnotes.xml or comments.xml by their id; the separators Word keeps among the footnotes left out. */
    static Map<String, Element> notesOf(byte[] xml, String kind) {
        Map<String, Element> out = new LinkedHashMap<>();
        if (xml == null) return out;
        try {
            Element root = parse(new String(xml, StandardCharsets.UTF_8)).getDocumentElement();
            for (Element n : descendants(root, kind, null)) {
                String type = n.getAttributeNS(W, "type");
                if (type.equals("separator") || type.equals("continuationSeparator") || type.equals("continuationNotice")) continue;
                out.put(n.getAttributeNS(W, "id"), n);
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** The text boxes a paragraph anchors, the first of each choice an alternate-content block gives (its fallback is the same box again). */
    static List<Element> textBoxes(Element e) {
        List<Element> out = new ArrayList<>();
        for (Element c : children(e)) {
            String l = local(c);
            if (l.equals("Fallback")) continue;
            if (l.equals("txbxContent")) { out.add(c); continue; }
            out.addAll(textBoxes(c));
        }
        return out;
    }

    private static void walkDocx(Element e, List<Unit> out, Map<String, String> links, Set<String> bullets, Map<String, String> styles, int[] table) {
        for (Element c : children(e)) {
            switch (local(c)) {
                case "p" -> {
                    Unit u = docxParagraph(c, links, bullets, styles);
                    Unit last = out.isEmpty() ? null : out.get(out.size() - 1);
                    if (u != null && u.extra().equals("docx-code") && last != null && last.extra().equals("docx-code")) out.set(out.size() - 1, last.withText(last.text().replaceAll("\\n```$", "") + "\n" + u.text().replaceFirst("^```\\n", "")));   // the lines of one code block
                    else if (u != null) out.add(u);
                    for (Element box : textBoxes(c)) walkDocx(box, out, links, bullets, styles, table);   // a text box's words after the paragraph that anchors it
                }
                case "tbl" -> {
                    table[0]++; int row = -1;
                    for (Element tr : descendants(c, "tr", "tbl")) {
                        row++;
                        boolean header = !descendants(tr, "tblHeader", "tc").isEmpty() || row == 0;
                        for (Element tc : children(tr)) {
                            if (!local(tc).equals("tc")) continue;
                            StringBuilder cell = new StringBuilder();
                            for (Element p : descendants(tc, "p", "tbl")) { String s = runs(p, links); if (!s.isBlank()) cell.append(cell.length() == 0 ? "" : " ").append(s.strip()); }
                            out.add(Unit.cell(table[0], row, header, cell.toString()));
                        }
                    }
                }
                case "sdt", "sdtContent", "smartTag", "customXml", "ins" -> walkDocx(c, out, links, bullets, styles, table);
                default -> { }
            }
        }
    }

    /** Word names its styles in English whatever the locale ("heading 1", "Title", "caption"); the ids vary. The names by id, from word/styles.xml. */
    static Map<String, String> styleNames(byte[] stylesXml) {
        Map<String, String> out = new LinkedHashMap<>();
        if (stylesXml == null) return out;
        try {
            Element root = parse(new String(stylesXml, StandardCharsets.UTF_8)).getDocumentElement();
            for (Element s : descendants(root, "style", null)) { String id = s.getAttributeNS(W, "styleId"); for (Element n : children(s)) if (local(n).equals("name")) out.put(id, n.getAttributeNS(W, "val")); }
        } catch (IOException ignored) { }
        return out;
    }

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^(?:heading|berschrift|titre|t[ií]tulo|見出し|标题|제목)\\s*(\\d)$");

    private static Unit docxParagraph(Element p, Map<String, String> links, Set<String> bullets, Map<String, String> styles) {
        String text = runs(p, links).strip();
        if (text.isEmpty()) return null;
        String style = ""; int ilvl = -1; String numId = "";
        for (Element pPr : children(p)) {
            if (!local(pPr).equals("pPr")) continue;
            for (Element x : children(pPr)) {
                if (local(x).equals("pStyle")) style = x.getAttributeNS(W, "val");
                if (local(x).equals("numPr")) { ilvl = 0; for (Element y : children(x)) { if (local(y).equals("ilvl")) ilvl = num(y.getAttributeNS(W, "val")); if (local(y).equals("numId")) numId = y.getAttributeNS(W, "val"); } }
            }
        }
        String name = styles.getOrDefault(style, style).replaceAll("\\s+", " ").strip();
        Matcher h = HEADING_STYLE.matcher(name);
        int shift = TITLE_USED.get() ? 1 : 0;
        if (h.matches()) return Unit.heading(Math.max(1, Math.min(6, num(h.group(1)) + shift)), text);
        if (name.equalsIgnoreCase("Title")) { TITLE_USED.set(true); return Unit.heading(1, text); }
        if (name.equalsIgnoreCase("Subtitle")) return Unit.heading(2, text);
        if (name.equalsIgnoreCase("caption")) return Unit.of(CAPTION, text);
        if (name.matches("(?i)^(code|source code|plain text|html code)$")) return new Unit(CODE, "```\n" + runsPlain(p) + "\n```", 0, -1, -1, false, "docx-code");
        if (ilvl >= 0 && !numId.equals("0")) return Unit.item(Math.min(3, Math.max(0, ilvl)), !bullets.contains(numId), text);
        if (name.toLowerCase(Locale.ROOT).contains("quote")) return new Unit(PARA, text, 0, -1, -1, false, "quote");
        return Unit.of(PARA, text);
    }

    /** The numIds whose list is bulleted, from word/numbering.xml; an empty set when there is none. */
    static Set<String> bulletNums(byte[] numbering) {
        Set<String> out = new HashSet<>();
        if (numbering == null) return out;
        try {
            Element root = parse(new String(numbering, StandardCharsets.UTF_8)).getDocumentElement();
            Set<String> bulletAbstract = new HashSet<>();
            for (Element a : descendants(root, "abstractNum", null)) {
                for (Element lvl : descendants(a, "lvl", null)) { if (!"0".equals(lvl.getAttributeNS(W, "ilvl"))) continue; for (Element f : children(lvl)) if (local(f).equals("numFmt") && "bullet".equals(f.getAttributeNS(W, "val"))) bulletAbstract.add(a.getAttributeNS(W, "abstractNumId")); }
            }
            for (Element n : descendants(root, "num", null)) for (Element ref : children(n)) if (local(ref).equals("abstractNumId") && bulletAbstract.contains(ref.getAttributeNS(W, "val"))) out.add(n.getAttributeNS(W, "numId"));
        } catch (IOException ignored) { }
        return out;
    }

    /** A paragraph's words as they are, for a code block. */
    static String runsPlain(Element p) {
        StringBuilder b = new StringBuilder();
        for (Element t : descendants(p, "t", null)) b.append(t.getTextContent());
        return b.toString();
    }

    /** A paragraph's words with its marks: bold and italic runs as Markdown's, a hyperlink as a link, a tab as a space. */
    static String runs(Element p, Map<String, String> links) {
        StringBuilder b = new StringBuilder();
        runs(p, links, b);
        return b.toString().replaceAll("[ \\t]+", " ");
    }

    private static void runs(Element e, Map<String, String> links, StringBuilder b) {
        for (Element c : children(e)) {
            switch (local(c)) {
                case "r" -> {
                    boolean bold = false, italic = false; StringBuilder t = new StringBuilder();
                    for (Element x : children(c)) {
                        switch (local(x)) {
                            case "rPr" -> { for (Element y : children(x)) { String v = y.getAttributeNS(W, "val"); boolean on = v.isEmpty() || v.equals("1") || v.equals("true") || v.equals("on"); if (local(y).equals("b") && on) bold = true; if (local(y).equals("i") && on) italic = true; } }
                            case "t" -> t.append(x.getTextContent());
                            case "tab" -> t.append(' ');
                            case "br", "cr" -> t.append(' ');
                            case "footnoteReference", "endnoteReference" -> { Notes n = NOTES.get(); if (n != null) t.append(n.mark(local(x).equals("footnoteReference") ? "footnote" : "endnote", x.getAttributeNS(W, "id"))); }
                            case "commentReference" -> { Notes n = NOTES.get(); if (n != null) t.append(n.mark("comment", x.getAttributeNS(W, "id"))); }
                            default -> { }
                        }
                    }
                    String s = t.toString();
                    if (s.isBlank()) { b.append(s); break; }
                    String lead = s.replaceAll("^(\\s*).*$", "$1"), trail = s.replaceAll("^.*?(\\s*)$", "$1"), core = s.strip();
                    if (bold && italic) core = "***" + core + "***"; else if (bold) core = "**" + core + "**"; else if (italic) core = "*" + core + "*";
                    b.append(lead).append(core).append(trail);
                }
                case "hyperlink" -> {
                    StringBuilder inner = new StringBuilder(); runs(c, links, inner);
                    String href = links.get(c.getAttributeNS(R, "id"));
                    b.append(href != null && !inner.isEmpty() ? "[" + inner.toString().strip() + "](" + href + ")" : inner);
                }
                case "pPr", "rPr", "txbxContent", "Fallback", "del" -> { }   // a text box is read after its paragraph; a fallback repeats its choice; deleted text is gone
                default -> runs(c, links, b);
            }
        }
    }

    // ---- EPUB: the chapters in spine order, each cut as XHTML ----

    /** An EPUB cut chapter by chapter in spine order, each chapter by unit; its pictures kept by the path the chapters name them by, its creator kept for the package. */
    public static Doc epub(byte[] bytes, String name) throws IOException {
        Map<String, byte[]> z = unzip(bytes);
        String opfPath = null;
        if (z.containsKey("META-INF/container.xml")) { Matcher m = Pattern.compile("full-path=\"([^\"]+)\"").matcher(new String(z.get("META-INF/container.xml"), StandardCharsets.UTF_8)); if (m.find()) opfPath = m.group(1); }
        if (opfPath == null || !z.containsKey(opfPath)) for (String n : z.keySet()) if (n.endsWith(".opf")) { opfPath = n; break; }
        if (opfPath == null) throw new IOException("The EPUB has no package file (.opf).");
        String opf = new String(z.get(opfPath), StandardCharsets.UTF_8);
        String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        Map<String, String[]> items = new LinkedHashMap<>();   // id → [href, media-type, properties]
        Matcher im = Pattern.compile("<(?:opf:)?item\\b[^>]*>").matcher(opf);
        while (im.find()) {
            String tag = im.group();
            String id = attr(tag, "id"), href = attr(tag, "href");
            if (id != null && href != null) items.put(id, new String[]{resolve(opfDir, Entities.decode(href)), attr(tag, "media-type") == null ? "" : attr(tag, "media-type"), attr(tag, "properties") == null ? "" : attr(tag, "properties")});
        }
        List<String> spine = new ArrayList<>();
        Matcher sm = Pattern.compile("<(?:opf:)?itemref\\b[^>]*idref=\"([^\"]+)\"").matcher(opf);
        while (sm.find()) { String[] it = items.get(sm.group(1)); if (it != null && !it[2].contains("nav")) spine.add(it[0]); }
        Map<String, String> meta = new LinkedHashMap<>();
        Matcher tm = Pattern.compile("<dc:title[^>]*>(.*?)</dc:title>").matcher(opf); String title = tm.find() ? Entities.decode(tm.group(1)).strip() : "";
        Matcher cm = Pattern.compile("<dc:creator[^>]*>(.*?)</dc:creator>").matcher(opf); if (cm.find()) meta.put("creator", Entities.decode(cm.group(1)).strip());
        Matcher lm = Pattern.compile("<dc:language[^>]*>(.*?)</dc:language>").matcher(opf); if (lm.find()) meta.put("language", lm.group(1).strip());
        // the navigation's names for the chapters: a chapter with no heading of its own is named by it
        Map<String, String> navNames = new LinkedHashMap<>();
        for (String[] it : items.values()) if (it[2].contains("nav") && z.containsKey(it[0])) navNames.putAll(navLabels(new String(z.get(it[0]), StandardCharsets.UTF_8), it[0]));
        for (String n : z.keySet()) if (n.endsWith(".ncx")) navNames.putAll(ncxLabels(new String(z.get(n), StandardCharsets.UTF_8), n));
        Map<String, byte[]> images = new LinkedHashMap<>();
        List<Chapter> chapters = new ArrayList<>();
        int[] table = {0};
        for (String href : spine) {
            byte[] xhtml = z.get(href);
            if (xhtml == null) continue;
            List<Unit> units = new ArrayList<>();
            Element root = parse(new String(xhtml, StandardCharsets.UTF_8)).getDocumentElement();
            Element body = null;
            for (Element c : children(root)) if (local(c).equals("body")) body = c;
            if (body != null) walkHtml(body, units, href, z, images, table, 0, false);
            if (units.stream().noneMatch(u -> u.kind().equals(HEADING)) && navNames.containsKey(href)) units.add(0, Unit.of(TITLE, navNames.get(href)));
            if (units.isEmpty()) continue;
            chapters.add(new Chapter(href, units));
        }
        if (chapters.isEmpty()) throw new IOException("The EPUB has no chapter with words in it.");
        StringBuilder all = new StringBuilder(); for (Chapter c : chapters) for (Unit u : c.units()) if (u.translated()) all.append(u.text()).append('\n');
        if (title.isEmpty()) title = name;
        Languages.Guess g = Languages.ofDocument(all.toString(), meta.getOrDefault("language", ""));   // the package's language where the text agrees or is too short to say; the text where it plainly says another
        return new Doc("epub", title, g.code().isEmpty() ? "en" : g.code(), chapters, images, meta, null, g);
    }

    /** The nav document's labels by the file each points at (the fragment dropped). */
    static Map<String, String> navLabels(String navXhtml, String navPath) {
        Map<String, String> out = new LinkedHashMap<>();
        String dir = navPath.contains("/") ? navPath.substring(0, navPath.lastIndexOf('/') + 1) : "";
        Matcher m = Pattern.compile("<a\\b[^>]*href=\"([^\"#]+)(?:#[^\"]*)?\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(navXhtml);
        while (m.find()) { String href = resolve(dir, Entities.decode(m.group(1))); String label = Entities.decode(m.group(2).replaceAll("<[^>]+>", "")).replaceAll("\\s+", " ").strip(); if (!label.isEmpty()) out.putIfAbsent(href, label); }
        return out;
    }

    /** An NCX's labels by file. */
    static Map<String, String> ncxLabels(String ncx, String ncxPath) {
        Map<String, String> out = new LinkedHashMap<>();
        String dir = ncxPath.contains("/") ? ncxPath.substring(0, ncxPath.lastIndexOf('/') + 1) : "";
        Matcher m = Pattern.compile("<navPoint\\b.*?<text>(.*?)</text>.*?<content\\b[^>]*src=\"([^\"#]+)(?:#[^\"]*)?\"", Pattern.DOTALL).matcher(ncx);
        while (m.find()) out.putIfAbsent(resolve(dir, Entities.decode(m.group(2))), Entities.decode(m.group(1)).strip());
        return out;
    }

    /** The elements that stand inside a line: their words join the words around them. */
    private static final Set<String> INLINE = Set.of("span", "a", "em", "strong", "b", "i", "u", "s", "q", "small", "sup", "sub", "font", "code", "kbd", "samp", "tt", "cite", "dfn", "abbr", "del", "ins", "mark", "time", "br");

    private static void walkHtml(Element e, List<Unit> out, String file, Map<String, byte[]> z, Map<String, byte[]> images, int[] table, int depth, boolean quote) {
        StringBuilder loose = new StringBuilder();   // words directly in a container, outside any paragraph
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) { loose.append(n.getTextContent()); continue; }
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element c = (Element) n;
            String tag = local(c).toLowerCase(Locale.ROOT);
            if (INLINE.contains(tag)) { inlineNode(c, loose); continue; }
            looseFlush(loose, out, quote);
            switch (tag) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> { String t = inlineMd(c, file); if (!t.isBlank()) out.add(Unit.heading(tag.charAt(1) - '0', t)); }
                case "p" -> {
                    String t = inlineMd(c, file);
                    boolean caption = c.getAttribute("class").matches("(?i).*\\bcaption\\b.*") || (!out.isEmpty() && out.get(out.size() - 1).kind().equals(IMAGE) && t.matches("^\\*[^*]+\\*$"));   // a paragraph marked as a caption, or one in italics under a picture
                    if (!t.isBlank()) out.add(caption ? Unit.of(CAPTION, t.replaceAll("^\\*([^*]+)\\*$", "$1")) : new Unit(PARA, t, 0, -1, -1, false, quote ? "quote" : ""));
                    imagesIn(c, out, file, z, images);
                }
                case "li" -> {
                    boolean ordered = local(e).equalsIgnoreCase("ol");
                    String t = ownText(c, file);
                    if (!t.isBlank()) out.add(Unit.item(Math.min(3, depth), ordered, t));
                    for (Element sub : children(c)) { String st = local(sub).toLowerCase(Locale.ROOT); if (st.equals("ul") || st.equals("ol")) walkHtml(sub, out, file, z, images, table, depth + 1, quote); }
                }
                case "ul", "ol" -> walkHtml(c, out, file, z, images, table, local(e).equalsIgnoreCase("li") ? depth + 1 : depth, quote);
                case "table" -> {
                    table[0]++; int row = -1;
                    for (Element tr : descendants(c, "tr", "table")) {
                        row++;
                        for (Element td : children(tr)) {
                            String tt = local(td).toLowerCase(Locale.ROOT);
                            if (!tt.equals("td") && !tt.equals("th")) continue;
                            out.add(Unit.cell(table[0], row, tt.equals("th") || (row == 0 && local(tr.getParentNode()).equalsIgnoreCase("thead")), inlineMd(td, file)));
                        }
                    }
                    for (Element cap : children(c)) if (local(cap).equalsIgnoreCase("caption")) { String t = inlineMd(cap, file); if (!t.isBlank()) out.add(Unit.of(CAPTION, t)); }
                }
                case "figcaption", "caption" -> { String t = inlineMd(c, file); if (!t.isBlank()) out.add(Unit.of(CAPTION, t)); }
                case "img", "image" -> image(c, out, file, z, images);
                case "pre" -> { String t = c.getTextContent(); if (!t.isBlank()) out.add(Unit.of(CODE, "```\n" + t.replaceAll("\\s+$", "") + "\n```")); }
                case "hr" -> out.add(Unit.of(RULE, ""));
                case "blockquote" -> walkHtml(c, out, file, z, images, table, depth, true);
                case "svg" -> image(c, out, file, z, images);
                case "script", "style", "head", "title" -> { }
                default -> walkHtml(c, out, file, z, images, table, depth, quote);   // a container: its parts are read in turn
            }
        }
        looseFlush(loose, out, quote);
    }

    private static void looseFlush(StringBuilder loose, List<Unit> out, boolean quote) {
        String t = loose.toString().replaceAll("\\s+", " ").strip();
        loose.setLength(0);
        if (!t.isEmpty()) out.add(new Unit(PARA, t, 0, -1, -1, false, quote ? "quote" : ""));
    }

    /** A list item's own words, the nested lists left out. */
    private static String ownText(Element li, String file) {
        StringBuilder b = new StringBuilder();
        NodeList kids = li.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE) b.append(n.getTextContent());
            else if (n.getNodeType() == Node.ELEMENT_NODE) { String t = local((Element) n).toLowerCase(Locale.ROOT); if (!t.equals("ul") && !t.equals("ol")) b.append(inlineMd((Element) n, file)); }
        }
        return b.toString().replaceAll("\\s+", " ").strip();
    }

    private static void imagesIn(Element p, List<Unit> out, String file, Map<String, byte[]> z, Map<String, byte[]> images) {
        for (Element img : descendants(p, "img", null)) image(img, out, file, z, images);
    }

    private static void image(Element img, List<Unit> out, String file, Map<String, byte[]> z, Map<String, byte[]> images) {
        String src = img.getAttribute("src");
        if (src.isEmpty()) src = img.getAttributeNS("http://www.w3.org/1999/xlink", "href");
        if (src.isEmpty()) for (Element c : descendants(img, "image", null)) { src = c.getAttributeNS("http://www.w3.org/1999/xlink", "href"); if (src.isEmpty()) src = c.getAttribute("href"); if (!src.isEmpty()) break; }
        if (src.isEmpty()) return;
        String path = resolve(file.contains("/") ? file.substring(0, file.lastIndexOf('/') + 1) : "", Entities.decode(src));
        byte[] bytes = z.get(path);
        if (bytes != null) images.put(path, bytes);
        out.add(new Unit(IMAGE, img.getAttribute("alt"), 0, -1, -1, false, path));
    }

    /** An element's words with Markdown's inline marks for its emphasis, code and links; the pictures inside left out. */
    static String inlineMd(Element e, String file) {
        StringBuilder b = new StringBuilder();
        inlineMd(e, b);
        return b.toString().replaceAll("[ \\t\\n\\r]+", " ").strip();
    }

    private static void inlineMd(Element e, StringBuilder b) {
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) { b.append(n.getTextContent()); continue; }
            if (n.getNodeType() == Node.ELEMENT_NODE) inlineNode((Element) n, b);
        }
    }

    /** One element within a line, with its own mark: emphasis, code, a link, a line break; its parts inside. */
    private static void inlineNode(Element c, StringBuilder b) {
        {
            String tag = local(c).toLowerCase(Locale.ROOT);
            switch (tag) {
                case "em", "i", "cite", "dfn" -> mark(c, b, "*");
                case "strong", "b" -> mark(c, b, "**");
                case "code", "kbd", "samp", "tt" -> { String t = c.getTextContent().strip(); if (!t.isEmpty()) b.append('`').append(t).append('`'); }
                case "a" -> {
                    StringBuilder inner = new StringBuilder(); inlineMd(c, inner);
                    String href = c.getAttribute("href"), t = inner.toString().strip();
                    if (!t.isEmpty() && href.matches("(?i)^(https?://|mailto:).*")) b.append('[').append(t).append("](").append(href).append(')'); else b.append(inner);
                }
                case "br" -> b.append(' ');
                case "img", "svg", "script", "style" -> { }
                case "sup" -> { String t = c.getTextContent().strip(); if (!t.isEmpty()) b.append(t.matches("\\[?\\d+\\]?") ? (t.startsWith("[") ? t : "[" + t + "]") : t); }
                default -> inlineMd(c, b);
            }
        }
    }

    private static void mark(Element c, StringBuilder b, String m) {
        StringBuilder inner = new StringBuilder(); inlineMd(c, inner);
        String t = inner.toString();
        if (t.isBlank()) { b.append(t); return; }
        String lead = t.replaceAll("^(\\s*).*$", "$1"), trail = t.replaceAll("^.*?(\\s*)$", "$1");
        b.append(lead).append(m).append(t.strip()).append(m).append(trail);
    }

    // ---- XML and zip helpers ----

    private static final Pattern NAMED_ENTITY = Pattern.compile("&([A-Za-z][A-Za-z0-9]{1,8});");

    /** An XML document parsed with no outside reading: no DTD fetched, the named entities of HTML written as numbers first. */
    static Document parse(String xml) throws IOException {
        String s = xml;
        int bom = s.indexOf('<'); if (bom > 0) s = s.substring(bom);
        Matcher m = NAMED_ENTITY.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String rep;
            if (name.equals("amp") || name.equals("lt") || name.equals("gt") || name.equals("quot") || name.equals("apos")) rep = m.group();
            else { String d = Entities.decode(m.group()); rep = d.equals(m.group()) ? "&amp;" + name + ";" : "&#" + d.codePointAt(0) + ";"; }
            m.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        m.appendTail(b);
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);
            DocumentBuilder db = f.newDocumentBuilder();
            db.setEntityResolver((pub, sys) -> new InputSource(new StringReader("")));
            db.setErrorHandler(null);
            return db.parse(new InputSource(new StringReader(b.toString())));
        } catch (Exception e) {
            throw new IOException("The XML could not be read: " + e.getMessage(), e);
        }
    }

    static String local(Node n) { String l = n.getLocalName(); return l != null ? l : n.getNodeName().replaceAll("^.*:", ""); }

    static List<Element> children(Element e) {
        List<Element> out = new ArrayList<>();
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) if (kids.item(i).getNodeType() == Node.ELEMENT_NODE) out.add((Element) kids.item(i));
        return out;
    }

    /** The descendants named {@code name}, not looking inside one named {@code stopAt} (a nested table's rows are its own). */
    static List<Element> descendants(Element e, String name, String stopAt) {
        List<Element> out = new ArrayList<>();
        for (Element c : children(e)) {
            if (local(c).equalsIgnoreCase(name)) { out.add(c); continue; }
            if (stopAt != null && local(c).equalsIgnoreCase(stopAt)) continue;
            out.addAll(descendants(c, name, stopAt));
        }
        return out;
    }

    static int num(String s) { try { return Integer.parseInt(s.strip()); } catch (Exception e) { return 0; } }

    static String attr(String tag, String name) { Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag); return m.find() ? m.group(1) : null; }

    /** A path relative to a folder inside the zip, with its ../ and ./ resolved. */
    static String resolve(String dir, String href) {
        String h = href.replaceAll("[?#].*$", "");
        if (h.startsWith("/")) return h.substring(1);
        List<String> parts = new ArrayList<>();
        for (String p : (dir + h).split("/")) { if (p.isEmpty() || p.equals(".")) continue; if (p.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); } else parts.add(p); }
        return String.join("/", parts);
    }

    /** Every entry of a zip, by name; capped as it inflates. */
    static Map<String, byte[]> unzip(byte[] bytes) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] part = zin.readNBytes(64 << 20);
                total += part.length;
                if (total > 256L << 20) break;
                out.put(e.getName(), part);
            }
        } catch (IOException ignored) { }
        return out;
    }
}
