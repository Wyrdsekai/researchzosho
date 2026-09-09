package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An entry, or a reading of it, as a file to keep or send: Markdown as it is, or a PDF laid out here with
 * PDFBox — headings, paragraphs, lists, page numbers — in a system font that can show Japanese and the
 * rest of the world's scripts when one is installed (Noto CJK, Hiragino, Meiryo…), Helvetica otherwise.
 * The PDF is a rendering of the record; the record stays the file in the library.
 */
public final class Export {

    private Export() { }

    public record File(String name, String contentType, byte[] bytes) { }

    /** The Markdown document for an entry (as written) or a reading of it at a rung. */
    public static File markdown(LibraryStore store, String id, Explain.Reading reading) throws IOException {
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode e = p.entry(id, p.kindOf(id), true);
        if (e == null) throw ProtocolError.notFound("entry " + id);
        String md = document(store, e, reading);
        String stem = id + (reading == null || reading.rung() == Explain.Rung.written ? "" : "-" + reading.rung().name());
        return new File(stem + ".md", "text/markdown; charset=utf-8", md.getBytes(StandardCharsets.UTF_8));
    }

    public static File pdf(LibraryStore store, String id, Explain.Reading reading) throws IOException {
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode e = p.entry(id, p.kindOf(id), true);
        if (e == null) throw ProtocolError.notFound("entry " + id);
        String md = document(store, e, reading);
        String stem = id + (reading == null || reading.rung() == Explain.Rung.written ? "" : "-" + reading.rung().name());
        String footer = store.identity().name() + " · " + id + " · " + LocalDate.now();
        return new File(stem + ".pdf", "application/pdf", render(md, footer));
    }

    /** The document: a title, a line of facts, the text, then the sources; a reading says what it is and rests on. */
    static String document(LibraryStore store, ObjectNode e, Explain.Reading reading) throws IOException {
        StringBuilder sb = new StringBuilder();
        String kind = e.path("kind").asText();
        sb.append("# ").append(e.path("title").asText()).append("\n\n");
        if (reading != null && reading.rung() != Explain.Rung.written) {
            sb.append("*A ").append(reading.rung().name()).append(" reading of ").append(e.path("id").asText()).append(", written by the library from what it holds, ")
              .append("based on the library: ").append("shelves".equals(reading.grounding()) ? "fully" : "thin".equals(reading.grounding()) ? "partly" : reading.grounding())
              .append(reading.checked() > 0 ? " (" + reading.checked() + " paragraphs checked against their sources, " + reading.unsupported() + " not backed up)" : "")
              .append(". A plain-language version, not a record.*\n\n");
            sb.append(reading.text()).append("\n\n");
            if (!reading.terms().isEmpty()) {
                sb.append("## Words you may meet next\n\n");
                for (Explain.Term t : reading.terms()) sb.append("- ").append(t.term()).append(" — ").append(t.gloss()).append('\n');
                sb.append('\n');
            }
            sb.append("## The record it rests on\n\n");
        }
        sb.append("*").append(e.path("id").asText()).append(" · ").append(kind).append(" · ").append(e.path("state").asText());
        if (!"raw".equals(kind)) sb.append(" · confidence ").append(e.path("confidence").asText());
        sb.append(" · by ").append(e.path("writer").asText()).append(" · ").append(e.path("recorded_at").asText().replace('T', ' ').replaceAll("\\.\\d+Z$", " UTC"));
        if (e.path("subjects").size() > 0) { sb.append(" · subjects: "); for (JsonNode s : e.path("subjects")) sb.append(s.asText()).append(' '); }
        sb.append("*\n\n");
        if (reading == null || reading.rung() == Explain.Rung.written) sb.append(e.path("body").asText().strip()).append("\n\n");
        else sb.append(Explain.cap(e.path("body").asText().strip(), 1_200)).append("\n\n");
        if (e.path("sources").size() > 0 && !"raw".equals(kind)) {
            sb.append("## Sources\n\n");
            for (JsonNode s : e.path("sources")) {
                sb.append("- ").append(s.path("locator").asText());
                if (s.hasNonNull("edition") && !s.path("edition").asText().isEmpty()) sb.append(" (").append(s.path("edition").asText()).append(")");
                if (!s.path("why").asText().isEmpty()) sb.append(" — ").append(s.path("why").asText());
                sb.append('\n');
            }
            sb.append('\n');
        }
        if (e.path("findings").size() > 0) {
            sb.append("## Claims that came out of it\n\n");
            LibraryProtocol p = new LibraryProtocol(store);
            for (JsonNode f : e.path("findings")) {
                ObjectNode fe = p.entry(f.asText(), "finding", false);
                sb.append("- ").append(f.asText()).append(fe == null ? "" : " — " + fe.path("title").asText() + " (" + fe.path("state").asText() + ")").append('\n');
            }
            sb.append('\n');
        }
        sb.append("---\n\n*From the ResearchZosho library \"").append(store.identity().name()).append("\", ").append(LocalDate.now()).append(". Every claim above names its sources; nothing is from a model's memory.*\n");
        return sb.toString();
    }

    // ---- PDF ----

    static final float PAGE_W = PDRectangle.A4.getWidth(), PAGE_H = PDRectangle.A4.getHeight(), MARGIN = 56f;

    static byte[] render(String markdown, String footer) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            Fonts fonts = Fonts.load(doc);
            Writer w = new Writer(doc, fonts, footer);
            for (String raw : markdown.split("\n")) {
                String line = raw.stripTrailing();
                if (line.isBlank()) { w.gap(6); continue; }
                if (line.startsWith("---")) { w.rule(); continue; }
                if (line.startsWith("#")) {
                    int n = 0; while (n < line.length() && line.charAt(n) == '#') n++;
                    w.paragraph(plain(line.substring(n).strip()), fonts.bold, n == 1 ? 17f : n == 2 ? 13f : 11.5f, 0, n == 1 ? 10 : 8);
                    continue;
                }
                if (line.strip().startsWith("|") && line.strip().endsWith("|")) {
                    // a table row: the separator line is dropped; a row becomes one indented block, cells joined
                    String body = line.strip();
                    if (body.matches("\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?")) continue;
                    String[] cells = body.substring(1, body.length() - 1).split("(?<!\\\\)\\|");
                    StringBuilder row = new StringBuilder();
                    for (String cell : cells) { if (row.length() > 0) row.append("   ·   "); row.append(plain(cell.strip())); }
                    w.paragraph(row.toString(), fonts.regular, 9.5f, 14, 4);
                    continue;
                }
                if (line.matches("^\\s*[-*•]\\s+.*")) { w.paragraph("•  " + plain(line.replaceFirst("^\\s*[-*•]\\s+", "")), fonts.regular, 10.5f, 14, 2); continue; }
                if (line.matches("^\\s*\\d+[.)]\\s+.*")) { w.paragraph(plain(line.strip()), fonts.regular, 10.5f, 14, 2); continue; }
                boolean italic = line.startsWith("*") && line.endsWith("*") && line.length() > 2 && !line.startsWith("**");
                w.paragraph(plain(line), italic ? fonts.italic : fonts.regular, italic ? 9.5f : 10.5f, 0, 3);
            }
            w.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Markdown marks off, for a line of PDF text. */
    static String plain(String s) {
        return s.replaceAll("\\*\\*([^*]+)\\*\\*", "$1").replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "$1").replace("`", "")
                .replaceAll("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)", "$1 <$2>").replaceAll("\\[\\[([^\\]|]+)(?:\\|[^\\]]+)?\\]\\]", "$1");
    }

    /** A font family that can show the text: a system CJK-capable TrueType when one is installed, Helvetica otherwise. */
    static final class Fonts {
        final PDFont regular, bold, italic; final boolean unicode;
        /** For the characters the Latin family lacks — Japanese, Chinese, Korean — a wide font, when the box has one. */
        PDFont fallback;
        Fonts(PDFont r, PDFont b, PDFont i, boolean unicode) { regular = r; bold = b; italic = i; this.unicode = unicode; }

        static final String[][] LATIN = {
            {"/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Italic.ttf"},
            {"/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf", "/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf", "/usr/share/fonts/truetype/liberation/LiberationSerif-Italic.ttf"},
            {"/System/Library/Fonts/Supplemental/Georgia.ttf", "/System/Library/Fonts/Supplemental/Georgia Bold.ttf", "/System/Library/Fonts/Supplemental/Georgia Italic.ttf"},
            {"/System/Library/Fonts/Supplemental/Times New Roman.ttf", "/System/Library/Fonts/Supplemental/Times New Roman Bold.ttf", "/System/Library/Fonts/Supplemental/Times New Roman Italic.ttf"},
            {"C:\\Windows\\Fonts\\georgia.ttf", "C:\\Windows\\Fonts\\georgiab.ttf", "C:\\Windows\\Fonts\\georgiai.ttf"},
            {"C:\\Windows\\Fonts\\times.ttf", "C:\\Windows\\Fonts\\timesbd.ttf", "C:\\Windows\\Fonts\\timesi.ttf"},
        };

        // TrueType (glyf) fonts only: PDFBox subsets those, so a PDF stays small. The Noto CJK files are OpenType CFF,
        // which it cannot subset — embedding one whole is 25 MB per PDF — so they are not on this list.
        static final String[][] CANDIDATES = {
            {"/usr/share/fonts/truetype/fonts-japanese-gothic.ttf", null},
            {"/usr/share/fonts/opentype/ipafont-gothic/ipag.ttf", null},
            {"/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf", null},
            {"/usr/share/fonts/truetype/wqy/wqy-microhei.ttc", null},
            {"/System/Library/Fonts/Hiragino Sans GB.ttc", null},
            {"/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc", "/System/Library/Fonts/ヒラギノ角ゴシック W6.ttc"},
            {"/Library/Fonts/Arial Unicode.ttf", null},
            {"C:\\Windows\\Fonts\\YuGothM.ttc", "C:\\Windows\\Fonts\\YuGothB.ttc"},
            {"C:\\Windows\\Fonts\\meiryo.ttc", "C:\\Windows\\Fonts\\meiryob.ttc"},
            {"C:\\Windows\\Fonts\\msgothic.ttc", null},
            {"/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"},
        };

        static Fonts load(PDDocument doc) {
            Fonts f = null;
            for (String[] trio : LATIN) {
                try {
                    PDFont r = open(doc, trio[0]);
                    if (r == null) continue;
                    PDFont b = open(doc, trio[1]), i = open(doc, trio[2]);
                    f = new Fonts(r, b == null ? r : b, i == null ? r : i, true);
                    break;
                } catch (Exception ignored) { }
            }
            if (f == null) f = new Fonts(new PDType1Font(Standard14Fonts.FontName.HELVETICA), new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE), false);
            String override = org.researchzosho.Config.get("RESEARCHZOSHO_PDF_FONT", "");
            List<String[]> tries = new ArrayList<>();
            if (!override.isBlank()) tries.add(new String[]{override, null});
            tries.addAll(java.util.Arrays.asList(CANDIDATES));
            for (String[] pair : tries) {
                try { PDFont w = open(doc, pair[0]); if (w != null) { f.fallback = w; break; } } catch (Exception ignored) { }
            }
            return f;
        }

        /** The font that shows this character: the family's own, the wide fallback, or null when nothing can. */
        PDFont fontFor(PDFont family, int cp) {
            String ch = new String(Character.toChars(cp));
            try { family.encode(ch); return family; } catch (Exception e) { }
            if (fallback != null) { try { fallback.encode(ch); return fallback; } catch (Exception e) { } }
            return null;
        }

        static PDFont open(PDDocument doc, String path) throws IOException {
            java.io.File f = new java.io.File(path);
            if (!f.isFile()) return null;
            if (path.toLowerCase(Locale.ROOT).endsWith(".ttc")) {
                TrueTypeCollection ttc = new TrueTypeCollection(f);
                final TrueTypeFont[] pick = {null, null};
                ttc.processAllFonts(t -> { if (pick[0] == null) pick[0] = t; if (pick[1] == null && t.getName() != null && t.getName().toLowerCase(Locale.ROOT).contains("jp")) pick[1] = t; });
                TrueTypeFont chosen = pick[1] != null ? pick[1] : pick[0];
                if (chosen == null || (chosen instanceof org.apache.fontbox.ttf.OpenTypeFont o && o.isPostScript())) return null;   // CFF: cannot be subset
                return PDType0Font.load(doc, chosen, true);
            }
            TrueTypeFont ttf = new org.apache.fontbox.ttf.TTFParser().parse(new org.apache.pdfbox.io.RandomAccessReadBufferedFile(f));
            if (ttf instanceof org.apache.fontbox.ttf.OpenTypeFont o && o.isPostScript()) { ttf.close(); return null; }
            return PDType0Font.load(doc, ttf, true);
        }
    }

    /** Lines onto pages: word-wrapped, CJK breaks anywhere, a footer with the page number. */
    static final class Writer {
        final PDDocument doc; final Fonts fonts; final String footer;
        PDPageContentStream cs; float y; int pageNo = 0;
        Writer(PDDocument doc, Fonts fonts, String footer) throws IOException { this.doc = doc; this.fonts = fonts; this.footer = footer; newPage(); }

        void newPage() throws IOException {
            if (cs != null) { foot(); cs.close(); }
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            pageNo++;
            y = PAGE_H - MARGIN;
        }
        void foot() throws IOException {
            cs.beginText();
            cs.setFont(fonts.regular, 8f);
            cs.newLineAtOffset(MARGIN, MARGIN / 2);
            cs.showText(safe(fonts.regular, footer + " · page " + pageNo));
            cs.endText();
        }
        void gap(float pts) { y -= pts; }
        void rule() throws IOException {
            if (y < MARGIN + 20) newPage();
            y -= 6;
            cs.setLineWidth(0.5f); cs.moveTo(MARGIN, y); cs.lineTo(PAGE_W - MARGIN, y); cs.stroke();
            y -= 8;
        }
        /** A piece of text in one font. */
        record Run(PDFont font, String text, float width) { }

        void paragraph(String text, PDFont family, float size, float indent, float after) throws IOException {
            float leading = size * 1.42f;
            float width = PAGE_W - 2 * MARGIN - indent;
            for (List<Run> line : wrap(text, family, size, width)) {
                if (y < MARGIN + leading) newPage();
                cs.beginText();
                cs.newLineAtOffset(MARGIN + indent, y - size);
                for (Run r : line) { cs.setFont(r.font(), size); cs.showText(r.text()); }
                cs.endText();
                y -= leading;
            }
            y -= after;
        }
        void close() throws IOException { foot(); cs.close(); }

        /** The footer's text in the family font, with anything it cannot show as a question mark. */
        String safe(PDFont font, String s) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i); i += Character.charCount(cp);
                String ch = new String(Character.toChars(cp));
                try { font.encode(ch); b.append(ch); } catch (Exception e) { b.append('?'); }
            }
            return b.toString();
        }

        /**
         * Lines of runs. A token is a word (kept whole), a space, or one CJK character (breaks anywhere, as the
         * script does); each character takes the family font when it has the glyph, the wide fallback when not,
         * and a question mark when nothing on the box can show it. An overlong token (a URL) breaks by character.
         */
        List<List<Run>> wrap(String text, PDFont family, float size, float width) throws IOException {
            List<List<Run>> out = new ArrayList<>();
            List<Run> line = new ArrayList<>();
            float lineW = 0;
            List<String> tokens = new ArrayList<>();
            StringBuilder tok = new StringBuilder();
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i); i += Character.charCount(cp);
                if (cp == '\t') cp = ' ';
                boolean cjk = Character.isIdeographic(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(cp) == Character.UnicodeScript.KATAKANA || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL;
                if (cp == ' ') { if (tok.length() > 0) { tokens.add(tok.toString()); tok.setLength(0); } tokens.add(" "); }
                else if (cjk) { if (tok.length() > 0) { tokens.add(tok.toString()); tok.setLength(0); } tokens.add(new String(Character.toChars(cp))); }
                else tok.appendCodePoint(cp);
            }
            if (tok.length() > 0) tokens.add(tok.toString());
            for (String t : tokens) {
                List<Run> runs = runs(t, family, size);
                float w = 0; for (Run r : runs) w += r.width();
                if (w > width) {   // a URL or the like: character by character
                    for (int i = 0; i < t.length(); ) {
                        int cp = t.codePointAt(i); i += Character.charCount(cp);
                        List<Run> one = runs(new String(Character.toChars(cp)), family, size);
                        float cw = one.get(0).width();
                        if (lineW + cw > width && !line.isEmpty()) { out.add(line); line = new ArrayList<>(); lineW = 0; }
                        append(line, one.get(0)); lineW += cw;
                    }
                    continue;
                }
                if (lineW + w > width && !line.isEmpty()) {
                    out.add(trim(line)); line = new ArrayList<>(); lineW = 0;
                    if (t.equals(" ")) continue;
                }
                for (Run r : runs) append(line, r);
                lineW += w;
            }
            if (!line.isEmpty()) out.add(trim(line));
            if (out.isEmpty()) out.add(List.of());
            return out;
        }

        List<Run> runs(String t, PDFont family, float size) throws IOException {
            List<Run> runs = new ArrayList<>();
            for (int i = 0; i < t.length(); ) {
                int cp = t.codePointAt(i); i += Character.charCount(cp);
                PDFont f = fonts.fontFor(family, cp);
                String ch = f == null ? "?" : new String(Character.toChars(cp));
                if (f == null) f = family;
                float w = f.getStringWidth(ch) / 1000f * size;
                Run last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
                if (last != null && last.font() == f) runs.set(runs.size() - 1, new Run(f, last.text() + ch, last.width() + w));
                else runs.add(new Run(f, ch, w));
            }
            return runs;
        }
        static void append(List<Run> line, Run r) {
            Run last = line.isEmpty() ? null : line.get(line.size() - 1);
            if (last != null && last.font() == r.font()) line.set(line.size() - 1, new Run(r.font(), last.text() + r.text(), last.width() + r.width()));
            else line.add(r);
        }
        static List<Run> trim(List<Run> line) {
            if (line.isEmpty()) return line;
            Run last = line.get(line.size() - 1);
            String t = last.text().stripTrailing();
            if (t.isEmpty()) { line.remove(line.size() - 1); return trim(line); }
            if (t.length() != last.text().length()) line.set(line.size() - 1, new Run(last.font(), t, last.width()));
            return line;
        }
    }
}
