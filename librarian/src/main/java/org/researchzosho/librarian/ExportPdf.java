package org.researchzosho.librarian;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.researchzosho.Config;
import org.researchzosho.librarian.ExportDoc.Block;
import org.researchzosho.librarian.ExportDoc.Code;
import org.researchzosho.librarian.ExportDoc.Heading;
import org.researchzosho.librarian.ExportDoc.Image;
import org.researchzosho.librarian.ExportDoc.Item;
import org.researchzosho.librarian.ExportDoc.Mark;
import org.researchzosho.librarian.ExportDoc.Para;
import org.researchzosho.librarian.ExportDoc.Rule;
import org.researchzosho.librarian.ExportDoc.Span;
import org.researchzosho.librarian.ExportDoc.Table;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The PDF of an export (0.7.0), laid out here with Apache PDFBox: headings, paragraphs that wrap, bulleted and numbered lists and a
 * source's own line with its number hanging, tables with their borders, code in a fixed-width font, every link a link the reader can
 * click, the page's number at its foot, and the headings as the file's bookmarks. The fonts come from the computer: a Latin family with its
 * bold and italic, and for any other script a TrueType font that has its characters. When no font on the computer has a character, it is
 * printed as a question mark and the first page says which characters those were, by their script and their code.
 */
final class ExportPdf {
    private ExportPdf() { }

    /** The file, and what of it could not be shown: how many different characters, and the sentence the first page carries about them. */
    record Result(byte[] bytes, int unshown, String unshownSaid) { }

    static final float PAGE_W = PDRectangle.A4.getWidth(), PAGE_H = PDRectangle.A4.getHeight();
    static final float MARGIN = 56f, TOP = PAGE_H - MARGIN, BOTTOM = MARGIN + 14f, WIDTH = PAGE_W - 2 * MARGIN;
    static final float BODY = 10.5f, SMALL = 9f, TABLE = 9f, CODE = 8.5f, FOOT = 8f;
    static final float[] INK = {0.10f, 0.10f, 0.10f}, GREY = {0.36f, 0.36f, 0.36f}, LINK = {0.07f, 0.28f, 0.62f}, CODE_INK = {0.24f, 0.20f, 0.16f},
            LINE = {0.68f, 0.68f, 0.68f}, SHADE = {0.94f, 0.94f, 0.93f};

    /** The PDF of a document. */
    static Result write(ExportDoc d) throws IOException {
        try (PDDocument doc = new PDDocument(); Fonts fonts = Fonts.load(doc, d.text())) {
            ExportDoc shown = d;
            String said = fonts.unshown.isEmpty() ? "" : unshownSaid(fonts.unshown);
            if (!said.isEmpty()) shown = d.withAfterTitle(new Para(List.of(Span.bold("Not every character could be shown. "), Span.plain(said)), false));
            Layout l = new Layout(doc, fonts);
            l.newPage();
            Block prev = null;
            for (Block b : shown.blocks) {
                if (prev instanceof Item && !(b instanceof Item)) l.y -= 4;   // a list ends with a little room
                switch (b) {
                    case Heading h -> l.heading(h);
                    case Para p -> l.para(p.spans(), p.small() ? new Style(SMALL, false, true, GREY) : new Style(BODY, false, false, INK), 0, p.small() ? 8 : 6);
                    case Item i -> l.item(i);
                    case Table t -> l.table(t);
                    case Code c -> l.code(c);
                    case Rule r -> l.rule();
                    case Image i -> l.image(i);
                }
                prev = b;
            }
            l.close();
            l.footers(d.footer);
            for (var e : l.annotations.entrySet()) e.getKey().setAnnotations(e.getValue());
            if (l.outline.hasChildren()) { doc.getDocumentCatalog().setDocumentOutline(l.outline); l.outline.openNode(); }
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(d.title); info.setCreator("ResearchZosho");
            doc.getDocumentCatalog().setLanguage(d.lang);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return new Result(out.toByteArray(), fonts.unshown.size(), said);
        }
    }

    /** What the first page says of the characters no font could show: how many of each script, the first few by their code, and what helps. */
    static String unshownSaid(Set<Integer> cps) {
        Map<String, Integer> byScript = new LinkedHashMap<>();
        for (int cp : cps) byScript.merge(scriptOf(cp), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        for (var e : byScript.entrySet()) parts.add(e.getValue() + " " + e.getKey());
        String which = parts.size() == 1 ? parts.get(0) : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        List<String> codes = new ArrayList<>();
        for (int cp : cps) { if (codes.size() == 6) break; codes.add(String.format(Locale.ROOT, "U+%04X", cp)); }
        return "No font on this computer has these characters: " + which + " (" + String.join(", ", codes) + (cps.size() > codes.size() ? " and others" : "")
                + "). Each one is printed as a question mark. Install a font for that language, then make the file again to see them.";
    }

    static String scriptOf(int cp) {
        Character.UnicodeScript sc;
        try { sc = Character.UnicodeScript.of(cp); } catch (IllegalArgumentException e) { return "other characters"; }
        return switch (sc) {
            case HAN -> "Chinese or Japanese characters (Han)";
            case HIRAGANA, KATAKANA -> "Japanese characters (kana)";
            case HANGUL -> "Korean characters (Hangul)";
            case COMMON, INHERITED, UNKNOWN -> "symbols or emoji";
            default -> { String n = sc.name().toLowerCase(Locale.ROOT).replace('_', ' '); yield Character.toUpperCase(n.charAt(0)) + n.substring(1) + " characters"; }
        };
    }

    /** An address as a link carries it: every character beyond ASCII written as its UTF-8 bytes in %XX. */
    static String ascii(String href) {
        StringBuilder b = new StringBuilder();
        for (byte x : href.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if (c > 0x20 && c < 0x7f) b.append((char) c); else b.append('%').append(String.format(Locale.ROOT, "%02X", c));
        }
        return b.toString();
    }

    // ---- the fonts ----

    /** Where fonts are looked for: the folders {@code -Dresearchzosho.fonts} names (the test task names an empty one), else the computer's own. */
    static List<Path> fontDirs() {
        String seam = System.getProperty("researchzosho.fonts");
        List<Path> out = new ArrayList<>();
        if (seam != null) { for (String s : seam.split(File.pathSeparator)) if (!s.isBlank()) out.add(Path.of(s)); return out; }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), home = System.getProperty("user.home", "");
        if (os.contains("win")) {
            String win = System.getenv("WINDIR");
            out.add(Path.of(win == null || win.isBlank() ? "C:\\Windows" : win, "Fonts"));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) out.add(Path.of(local, "Microsoft", "Windows", "Fonts"));
        } else if (os.contains("mac")) {
            for (String d : new String[]{"/System/Library/Fonts", "/Library/Fonts"}) out.add(Path.of(d));
            if (!home.isEmpty()) out.add(Path.of(home, "Library", "Fonts"));
        } else {   // fontconfig's folders
            for (String d : new String[]{"/usr/share/fonts", "/usr/local/share/fonts"}) out.add(Path.of(d));
            String data = System.getenv("XDG_DATA_HOME");
            if (data != null && !data.isBlank()) out.add(Path.of(data, "fonts"));
            if (!home.isEmpty()) { out.add(Path.of(home, ".local", "share", "fonts")); out.add(Path.of(home, ".fonts")); }
        }
        return out;
    }

    /** The font files under some folders, in a fixed order; kept for the life of the program, as the fonts of a computer seldom change. */
    private static final Map<String, List<Path>> FILES = new ConcurrentHashMap<>();
    /** The characters no font under some folders has, found by a whole search: an emoji does not make every later file read every font again. */
    private static final Map<String, Set<Integer>> NONE_HAS = new ConcurrentHashMap<>();

    static List<Path> fontFiles(List<Path> dirs) {
        return FILES.computeIfAbsent(dirs.toString(), k -> {
            List<Path> out = new ArrayList<>();
            for (Path d : dirs) {
                if (!Files.isDirectory(d)) continue;
                try (Stream<Path> s = Files.walk(d, 8)) {
                    s.filter(p -> { String n = p.getFileName().toString().toLowerCase(Locale.ROOT); return n.endsWith(".ttf") || n.endsWith(".ttc") || n.endsWith(".otf") || n.endsWith(".otc"); })
                     .filter(Files::isRegularFile).sorted().limit(5000).forEach(out::add);
                } catch (IOException | RuntimeException ignored) { }
            }
            return List.copyOf(out);
        });
    }

    /** A serif family with its bold, italic and bold italic, by the files' names, first found first. */
    static final String[][] FAMILIES = {
        {"DejaVuSerif.ttf", "DejaVuSerif-Bold.ttf", "DejaVuSerif-Italic.ttf", "DejaVuSerif-BoldItalic.ttf"},
        {"LiberationSerif-Regular.ttf", "LiberationSerif-Bold.ttf", "LiberationSerif-Italic.ttf", "LiberationSerif-BoldItalic.ttf"},
        {"NotoSerif-Regular.ttf", "NotoSerif-Bold.ttf", "NotoSerif-Italic.ttf", "NotoSerif-BoldItalic.ttf"},
        {"Georgia.ttf", "Georgia Bold.ttf", "Georgia Italic.ttf", "Georgia Bold Italic.ttf"},
        {"georgia.ttf", "georgiab.ttf", "georgiai.ttf", "georgiaz.ttf"},
        {"Times New Roman.ttf", "Times New Roman Bold.ttf", "Times New Roman Italic.ttf", "Times New Roman Bold Italic.ttf"},
        {"times.ttf", "timesbd.ttf", "timesi.ttf", "timesbi.ttf"},
    };
    static final String[] MONOS = {"DejaVuSansMono.ttf", "LiberationMono-Regular.ttf", "NotoSansMono-Regular.ttf", "Courier New.ttf", "cour.ttf", "consola.ttf"};
    /** The fonts tried first for what the family lacks, the ones known to carry Japanese, Chinese and Korean, then the wide symbol sets. */
    static final String[] WIDE = {"fonts-japanese-gothic.ttf", "ipag.ttf", "ipaexg.ttf", "ipagp.ttf", "TakaoGothic.ttf", "TakaoPGothic.ttf", "VL-Gothic-Regular.ttf",
        "DroidSansFallbackFull.ttf", "DroidSansFallback.ttf", "wqy-microhei.ttc", "wqy-zenhei.ttc", "NanumGothic.ttf", "UnDotum.ttf",
        "YuGothM.ttc", "meiryo.ttc", "msgothic.ttc", "msyh.ttc", "simsun.ttc", "malgun.ttf", "Arial Unicode.ttf", "Arial Unicode MS.ttf", "ARIALUNI.TTF",
        "Hiragino Sans GB.ttc", "ヒラギノ角ゴシック W3.ttc", "AppleSDGothicNeo.ttc", "DejaVuSans.ttf", "NotoSans-Regular.ttf", "FreeSerif.ttf", "FreeSans.ttf", "seguisym.ttf"};
    /** What a mark is printed as when no font has it: close enough to read, and said in no note. */
    static final Map<Integer, String> STAND_IN = Map.ofEntries(Map.entry(0x25CF, "•"), Map.entry(0x25CB, "o"), Map.entry(0x25E6, "o"), Map.entry(0x25AA, "•"),
            Map.entry(0x25A0, "•"), Map.entry(0x25A1, "o"), Map.entry(0x2192, "->"), Map.entry(0x2190, "<-"), Map.entry(0x21D2, "=>"), Map.entry(0x2248, "~"),
            Map.entry(0x2264, "<="), Map.entry(0x2265, ">="), Map.entry(0x2260, "!="), Map.entry(0x2212, "-"), Map.entry(0x2011, "-"), Map.entry(0x2010, "-"),
            Map.entry(0x2713, "v"), Map.entry(0x2714, "v"), Map.entry(0x2717, "x"), Map.entry(0x2718, "x"), Map.entry(0x2605, "*"), Map.entry(0x2606, "*"),
            Map.entry(0x2726, "*"), Map.entry(0x2032, "'"), Map.entry(0x2033, "\""), Map.entry(0x00A0, " "), Map.entry(0x202F, " "), Map.entry(0x3000, " "));

    /** A character that takes no room and is left out: a control, a format mark (a soft hyphen, a zero width space, a direction mark), a variation selector. */
    static boolean dropped(int cp) {
        int t = Character.getType(cp);
        return (t == Character.CONTROL && cp != '\n' && cp != '\t') || t == Character.FORMAT || (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF);
    }

    /** A character a line may break before and after, as the scripts written without spaces do. */
    static boolean breaksAnywhere(int cp) {
        if (Character.isIdeographic(cp)) return true;
        Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
        return sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL
                || (cp >= 0x3000 && cp <= 0x30FF) || (cp >= 0xFF00 && cp <= 0xFFEF);
    }

    /** What a line never starts with, in Japanese and Chinese: a closing mark, a full stop or a comma, a small kana, the long vowel mark. */
    static final String NO_START = "、。，．：；？！）」』】〕〉》｝］〙〗ゝゞヽヾーぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ・…‥％" + "\uFF0C\uFF0E\uFF1A\uFF1B\uFF1F\uFF01\uFF09\uFF3D\uFF5D";
    /** What a line never ends with: an opening mark. */
    static final String NO_END = "（「『【〔〈《｛［〘〖" + "\uFF08\uFF3B\uFF5B";

    static boolean noStart(int cp) { return cp < 0x10000 && NO_START.indexOf(cp) >= 0; }
    static boolean noEnd(int cp) { return cp < 0x10000 && NO_END.indexOf(cp) >= 0; }

    /** The fonts of one document: the family, a fixed-width font, the wide ones for other scripts, and what none of them can show. */
    static final class Fonts implements Closeable {
        PDFont regular, bold, italic, boldItalic, mono;
        final List<PDFont> wide = new ArrayList<>();
        final List<String> wideNames = new ArrayList<>();
        final Set<Integer> unshown = new TreeSet<>();
        final Map<Integer, String> standIns = new HashMap<>();
        private final List<Closeable> open = new ArrayList<>();
        private final Map<PDFont, Map<Integer, Boolean>> encodes = new IdentityHashMap<>();

        static Fonts load(PDDocument doc, String text) {
            Fonts f = new Fonts();
            List<Path> dirs = fontDirs(), files = fontFiles(dirs);
            Map<String, Path> byName = new HashMap<>();
            for (Path p : files) byName.putIfAbsent(p.getFileName().toString().toLowerCase(Locale.ROOT), p);
            for (String[] fam : FAMILIES) {
                Path r = byName.get(fam[0].toLowerCase(Locale.ROOT));
                if (r == null) continue;
                try {
                    f.regular = PDType0Font.load(doc, r.toFile());
                    f.bold = member(doc, byName, fam[1], f.regular);
                    f.italic = member(doc, byName, fam[2], f.regular);
                    f.boldItalic = member(doc, byName, fam[3], f.bold);
                    break;
                } catch (IOException | RuntimeException e) { f.regular = null; }
            }
            if (f.regular == null) {
                f.regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA); f.bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                f.italic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE); f.boldItalic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE);
            }
            for (String m : MONOS) { Path p = byName.get(m.toLowerCase(Locale.ROOT)); if (p == null) continue; try { f.mono = PDType0Font.load(doc, p.toFile()); break; } catch (IOException | RuntimeException ignored) { } }
            if (f.mono == null) f.mono = new PDType1Font(Standard14Fonts.FontName.COURIER);
            // the characters the family cannot show, and the fonts that can
            Set<Integer> need = new TreeSet<>();
            text.codePoints().forEach(cp -> { if (!dropped(cp) && !Character.isWhitespace(cp) && !f.encodes(f.regular, cp)) need.add(cp); });
            String key = dirs + "|" + Config.get("RESEARCHZOSHO_PDF_FONT", "");
            Set<Integer> known = NONE_HAS.getOrDefault(key, Set.of());
            Set<Integer> none = new TreeSet<>(need); none.retainAll(known);
            need.removeAll(known);
            if (!need.isEmpty()) {
                List<Path> order = new ArrayList<>();
                String override = Config.get("RESEARCHZOSHO_PDF_FONT", "").strip();
                if (!override.isEmpty()) order.add(Path.of(override));
                for (String n : WIDE) { Path p = byName.get(n.toLowerCase(Locale.ROOT)); if (p != null && !order.contains(p)) order.add(p); }
                for (Path p : files) if (!order.contains(p)) order.add(p);
                long deadline = System.currentTimeMillis() + 8_000;
                int tried = 0; boolean whole = true;
                for (Path p : order) {
                    if (need.isEmpty()) break;
                    if (f.wide.size() >= 4 || tried >= 600 || System.currentTimeMillis() > deadline) { whole = false; break; }
                    tried++;
                    Candidate c = best(p, need);
                    if (c == null) continue;
                    try {
                        f.wide.add(PDType0Font.load(doc, c.font(), true)); f.wideNames.add(p.getFileName().toString());
                        f.open.add(c.closeable());
                        need.removeAll(c.covers());
                    } catch (IOException | RuntimeException e) { try { c.closeable().close(); } catch (IOException ignored) { } }
                }
                if (whole && !need.isEmpty()) NONE_HAS.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).addAll(need);
                none.addAll(need);
            }
            for (int cp : none) {   // a mark with a close stand-in the family has is printed as that; the rest are said on the first page
                String s = STAND_IN.get(cp);
                if (s != null && s.codePoints().allMatch(x -> f.encodes(f.regular, x))) f.standIns.put(cp, s);
                else f.unshown.add(cp);
            }
            return f;
        }

        private static PDFont member(PDDocument doc, Map<String, Path> byName, String name, PDFont otherwise) {
            Path p = byName.get(name.toLowerCase(Locale.ROOT));
            if (p == null) return otherwise;
            try { return PDType0Font.load(doc, p.toFile()); } catch (IOException | RuntimeException e) { return otherwise; }
        }

        /** The font in a file with the most of the needed characters, kept open for the document; null when it has none of them or cannot be used. */
        private record Candidate(TrueTypeFont font, Closeable closeable, Set<Integer> covers) { }

        private static Candidate best(Path p, Set<Integer> need) {
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            try {
                if (name.endsWith(".ttc") || name.endsWith(".otc")) {
                    TrueTypeCollection ttc = new TrueTypeCollection(p.toFile());
                    TrueTypeFont[] pick = {null};
                    List<Set<Integer>> got = new ArrayList<>(List.of(Set.of()));
                    ttc.processAllFonts(t -> {
                        if (t instanceof OpenTypeFont o && o.isPostScript()) return;   // CFF outlines: PDFBox cannot subset them, and a whole one is tens of megabytes a file
                        Set<Integer> c = covered(t, need);
                        String n = t.getName() == null ? "" : t.getName().toLowerCase(Locale.ROOT);
                        if (c.size() > got.get(0).size() || (!c.isEmpty() && c.size() == got.get(0).size() && n.contains("jp"))) { pick[0] = t; got.set(0, c); }
                    });
                    if (pick[0] == null || got.get(0).isEmpty()) { ttc.close(); return null; }
                    return new Candidate(pick[0], ttc, got.get(0));
                }
                TrueTypeFont t = name.endsWith(".otf") ? new OTFParser().parse(new RandomAccessReadBufferedFile(p.toFile())) : new TTFParser().parse(new RandomAccessReadBufferedFile(p.toFile()));
                if (t instanceof OpenTypeFont o && o.isPostScript()) { t.close(); return null; }
                Set<Integer> c = covered(t, need);
                if (c.isEmpty()) { t.close(); return null; }
                return new Candidate(t, t, c);
            } catch (IOException | RuntimeException e) { return null; }
        }

        private static Set<Integer> covered(TrueTypeFont t, Set<Integer> need) {
            Set<Integer> out = new TreeSet<>();
            try {
                CmapLookup cm = t.getUnicodeCmapLookup(false);
                if (cm == null) return out;
                for (int cp : need) if (cm.getGlyphId(cp) > 0) out.add(cp);
            } catch (IOException | RuntimeException ignored) { }
            return out;
        }

        boolean encodes(PDFont font, int cp) {
            return encodes.computeIfAbsent(font, k -> new HashMap<>()).computeIfAbsent(cp, c -> {
                try { font.encode(new String(Character.toChars(c))); return true; } catch (IOException | RuntimeException e) { return false; }
            });
        }

        /** The font that shows a character in this member of the family: the member, the regular one, a wide one; null when none can. */
        PDFont pick(PDFont member, int cp) {
            if (encodes(member, cp)) return member;
            if (member != regular && encodes(regular, cp)) return regular;
            for (PDFont w : wide) if (encodes(w, cp)) return w;
            return null;
        }

        /** Whether some font of this document shows the character. */
        boolean canShow(int cp) { return pick(regular, cp) != null; }

        @Override public void close() {
            for (Closeable c : open) { try { c.close(); } catch (IOException ignored) { } }
            open.clear();
        }
    }

    // ---- the layout ----

    /** How a block sets its text: the size, whether all of it is bold or italic, and its colour. */
    record Style(float size, boolean bold, boolean italic, float[] ink) { }

    /** A run of text in one font and size; its link when it has one. */
    record Piece(PDFont font, float size, String text, float width, String href, boolean code) { }

    /** A word: its pieces, its width, and whether a space comes before it. */
    record Word(List<Piece> pieces, float width, boolean spaced) { }

    static final class Layout {
        final PDDocument doc; final Fonts fonts;
        PDPage page; PDPageContentStream cs; float y;
        final List<PDPage> pages = new ArrayList<>();
        final Map<PDPage, List<PDAnnotation>> annotations = new LinkedHashMap<>();
        final PDDocumentOutline outline = new PDDocumentOutline();
        PDOutlineItem top;

        Layout(PDDocument doc, Fonts fonts) { this.doc = doc; this.fonts = fonts; }

        void newPage() throws IOException {
            if (cs != null) cs.close();
            page = new PDPage(PDRectangle.A4);
            doc.addPage(page); pages.add(page);
            cs = new PDPageContentStream(doc, page);
            y = TOP;
        }

        boolean atTop() { return y >= TOP - 0.01f; }

        /** A new page when what comes next does not fit above the foot. */
        void room(float h) throws IOException { if (y - h < BOTTOM && !atTop()) newPage(); }

        void close() throws IOException { if (cs != null) cs.close(); cs = null; }

        PDFont member(Span s, Style st) {
            boolean b = s.bold() || st.bold(), i = s.italic() || st.italic();
            return s.code() ? fonts.mono : b && i ? fonts.boldItalic : b ? fonts.bold : i ? fonts.italic : fonts.regular;
        }

        /**
         * The words of some spans; a word ends at a space, and every character of a script written without spaces is a word of its own,
         * except that a closing mark or a full stop stays with the character before it and an opening mark with the one after it.
         */
        List<Word> words(List<Span> spans, Style st) throws IOException {
            List<Word> out = new ArrayList<>();
            List<Piece> cur = new ArrayList<>();
            boolean spaced = false, pending = false, glue = false;
            for (Span s : spans) {
                PDFont member = member(s, st);
                float size = s.code() ? st.size() * 0.92f : st.size();
                String t = s.text();
                for (int i = 0; i < t.length(); ) {
                    int cp = t.codePointAt(i); i += Character.charCount(cp);
                    if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) && !fonts.encodes(fonts.regular, cp)) {
                        if (!cur.isEmpty()) { out.add(word(cur, spaced)); cur = new ArrayList<>(); }
                        pending = true; glue = false;
                        continue;
                    }
                    if (dropped(cp)) continue;
                    boolean alone = breaksAnywhere(cp);
                    if (alone && noStart(cp) && !pending && (!cur.isEmpty() || !out.isEmpty())) {   // a closing mark: onto the word before it
                        if (cur.isEmpty()) { Word last = out.remove(out.size() - 1); cur = new ArrayList<>(last.pieces()); spaced = last.spaced(); }
                        add(cur, member, size, cp, s.href(), s.code());
                        out.add(word(cur, spaced)); cur = new ArrayList<>(); glue = false;
                        continue;
                    }
                    if (alone && !cur.isEmpty() && !glue) { out.add(word(cur, spaced)); cur = new ArrayList<>(); }
                    if (cur.isEmpty()) { spaced = pending; pending = false; }
                    add(cur, member, size, cp, s.href(), s.code());
                    glue = noEnd(cp);
                    if (alone && !glue) { out.add(word(cur, spaced)); cur = new ArrayList<>(); }
                }
            }
            if (!cur.isEmpty()) out.add(word(cur, spaced));
            return out;
        }

        static Word word(List<Piece> pieces, boolean spaced) {
            float w = 0; for (Piece p : pieces) w += p.width();
            return new Word(List.copyOf(pieces), w, spaced);
        }

        /** One character onto a word, in the font that shows it, as its stand-in, or as a question mark. */
        void add(List<Piece> cur, PDFont member, float size, int cp, String href, boolean code) throws IOException {
            PDFont f = fonts.pick(member, cp);
            String ch = new String(Character.toChars(cp));
            if (f == null) {
                String s = fonts.standIns.get(cp);
                if (s != null) { for (int i = 0; i < s.length(); ) { int x = s.codePointAt(i); i += Character.charCount(x); add(cur, member, size, x, href, code); } return; }
                f = fonts.encodes(member, '?') ? member : fonts.regular;
                ch = "?";
            }
            float w = f.getStringWidth(ch) / 1000f * size;
            Piece last = cur.isEmpty() ? null : cur.get(cur.size() - 1);
            if (last != null && last.font() == f && last.size() == size && Objects.equals(last.href(), href) && last.code() == code)
                cur.set(cur.size() - 1, new Piece(f, size, last.text() + ch, last.width() + w, href, code));
            else cur.add(new Piece(f, size, ch, w, href, code));
        }

        float space(float size) throws IOException { return fonts.regular.getStringWidth(" ") / 1000f * size; }

        /** Words onto lines no wider than {@code width}; a word longer than a line (an address) breaks by character. */
        List<List<Piece>> lines(List<Word> words, float width, float size) throws IOException {
            List<List<Piece>> out = new ArrayList<>();
            List<Piece> line = new ArrayList<>();
            float w = 0, sp = space(size);
            for (Word word : words) {
                float gap = word.spaced() && !line.isEmpty() ? sp : 0;
                if (w + gap + word.width() <= width || line.isEmpty() && word.width() <= width) {
                    if (gap > 0) line.add(spacer(line.get(line.size() - 1), word.pieces().get(0), size, sp));
                    line.addAll(word.pieces()); w += gap + word.width();
                } else if (word.width() > width) {
                    if (!line.isEmpty()) { out.add(line); line = new ArrayList<>(); w = 0; }
                    for (Piece p : word.pieces()) {
                        for (int i = 0; i < p.text().length(); ) {
                            int cp = p.text().codePointAt(i); i += Character.charCount(cp);
                            String ch = new String(Character.toChars(cp));
                            float cw = p.font().getStringWidth(ch) / 1000f * p.size();
                            if (w + cw > width && !line.isEmpty()) { out.add(line); line = new ArrayList<>(); w = 0; }
                            line.add(new Piece(p.font(), p.size(), ch, cw, p.href(), p.code())); w += cw;
                        }
                    }
                } else { out.add(line); line = new ArrayList<>(word.pieces()); w = word.width(); }
            }
            if (!line.isEmpty()) out.add(line);
            if (out.isEmpty()) out.add(List.of());
            List<List<Piece>> merged = new ArrayList<>();
            for (List<Piece> l : out) merged.add(merge(l));
            return merged;
        }

        /** The space between two words: inside a link when both words are of it. */
        Piece spacer(Piece before, Piece after, float size, float sp) {
            String href = Objects.equals(before.href(), after.href()) ? before.href() : null;
            PDFont f = fonts.encodes(before.font(), ' ') ? before.font() : fonts.regular;
            return new Piece(f, before.font() == f ? before.size() : size, " ", sp, href, before.code() && after.code());
        }

        static List<Piece> merge(List<Piece> in) {
            List<Piece> out = new ArrayList<>();
            for (Piece p : in) {
                Piece last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && last.font() == p.font() && last.size() == p.size() && Objects.equals(last.href(), p.href()) && last.code() == p.code())
                    out.set(out.size() - 1, new Piece(p.font(), p.size(), last.text() + p.text(), last.width() + p.width(), p.href(), p.code()));
                else out.add(p);
            }
            return out;
        }

        /** One line at a baseline: its text, then each link underlined and made an area the reader clicks. */
        void draw(PDPageContentStream s, PDPage pg, List<Piece> line, float x, float baseline, float[] ink) throws IOException {
            if (line.isEmpty()) return;
            s.beginText();
            s.newLineAtOffset(x, baseline);
            float[] cur = null;
            for (Piece p : line) {
                s.setFont(p.font(), p.size());
                float[] c = p.href() != null ? LINK : p.code() ? CODE_INK : ink;
                if (c != cur) { s.setNonStrokingColor(c[0], c[1], c[2]); cur = c; }
                s.showText(p.text());
            }
            s.endText();
            float at = x, start = x, size = 0; String href = null;
            for (Piece p : line) {
                if (!Objects.equals(p.href(), href)) { if (href != null) link(s, pg, href, start, at, baseline, size); href = p.href(); start = at; size = p.size(); }
                at += p.width();
            }
            if (href != null) link(s, pg, href, start, at, baseline, size);
        }

        void link(PDPageContentStream s, PDPage pg, String href, float x0, float x1, float baseline, float size) throws IOException {
            if (x1 - x0 < 0.5f) return;
            s.setStrokingColor(LINK[0], LINK[1], LINK[2]); s.setLineWidth(0.4f);
            s.moveTo(x0, baseline - 1.4f); s.lineTo(x1, baseline - 1.4f); s.stroke();
            PDAnnotationLink a = new PDAnnotationLink();
            PDBorderStyleDictionary none = new PDBorderStyleDictionary(); none.setWidth(0); a.setBorderStyle(none);
            a.setRectangle(new PDRectangle(x0, baseline - size * 0.3f, x1 - x0, size * 1.2f));
            PDActionURI act = new PDActionURI(); act.setURI(ascii(href)); a.setAction(act);
            annotations.computeIfAbsent(pg, k -> new ArrayList<>()).add(a);
        }

        void para(List<Span> spans, Style st, float indent, float after) throws IOException {
            float leading = st.size() * 1.42f;
            for (List<Piece> l : lines(words(spans, st), WIDTH - indent, st.size())) {
                room(leading);
                draw(cs, page, l, MARGIN + indent, y - st.size(), st.ink());
                y -= leading;
            }
            y -= after;
        }

        void heading(Heading h) throws IOException {
            float size = switch (h.level()) { case 1 -> 18f; case 2 -> 13.5f; case 3 -> 11.5f; default -> 10.5f; };
            Style st = new Style(size, true, false, INK);
            List<List<Piece>> lines = lines(words(h.spans(), st), WIDTH, size);
            float leading = size * 1.3f;
            if (!atTop()) y -= h.level() == 1 ? 10 : h.level() == 2 ? 13 : 8;
            room(lines.size() * leading + BODY * 1.42f * 2);   // a heading keeps the next two lines with it
            if (h.level() <= 2) bookmark(h);
            for (List<Piece> l : lines) {
                room(leading);
                draw(cs, page, l, MARGIN, y - size, INK);
                y -= leading;
            }
            y -= h.level() == 1 ? 6 : 4;
        }

        void bookmark(Heading h) {
            String title = ExportDoc.plain(h.spans()).strip();
            if (title.isEmpty()) return;
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(title.length() > 120 ? title.substring(0, 119) + "…" : title);
            PDPageXYZDestination dest = new PDPageXYZDestination();
            dest.setPage(page); dest.setTop((int) Math.min(PAGE_H, y + 4)); dest.setLeft(0);
            item.setDestination(dest);
            if (h.level() == 1 || top == null) { outline.addLast(item); if (h.level() == 1) { top = item; item.openNode(); } }
            else top.addLast(item);
        }

        void item(Item it) throws IOException {
            Style st = new Style(BODY, false, false, INK);
            float indent = it.depth() * 16f;
            String mark = switch (it.mark()) {
                case BULLET -> it.depth() == 0 ? "•" : it.depth() == 1 ? (fonts.canShow(0x25E6) ? "◦" : "–") : (fonts.canShow(0x25AA) ? "▪" : "·");
                case NUMBER -> it.number() + ".";
                case SOURCE -> "[" + it.number() + "]";
            };
            Style ms = new Style(BODY, it.mark() == Mark.SOURCE, false, INK);
            List<Word> mw = words(List.of(Span.plain(mark)), ms);
            float markW = it.mark() == Mark.BULLET ? 12f : Math.max(lines(mw, WIDTH, BODY).get(0).stream().map(Piece::width).reduce(0f, Float::sum),
                    fonts.regular.getStringWidth(it.mark() == Mark.SOURCE ? "[00]" : "00.") / 1000f * BODY) + 5f;
            float leading = BODY * 1.42f;
            boolean first = true;
            for (List<Piece> l : lines(words(it.spans(), st), WIDTH - indent - markW, BODY)) {
                room(leading);
                if (first) draw(cs, page, lines(mw, WIDTH, BODY).get(0), MARGIN + indent, y - BODY, INK);
                draw(cs, page, l, MARGIN + indent + markW, y - BODY, INK);
                y -= leading; first = false;
            }
            y -= 2.5f;
        }

        void table(Table t) throws IOException {
            int cols = 0;
            for (List<List<Span>> row : t.rows()) cols = Math.max(cols, row.size());
            if (cols == 0) return;
            float pad = 3f, leading = TABLE * 1.35f;
            Style body = new Style(TABLE, false, false, INK), head = new Style(TABLE, true, false, INK);
            float[] natural = new float[cols], least = new float[cols];
            List<List<List<Word>>> words = new ArrayList<>();
            for (int r = 0; r < t.rows().size(); r++) {
                List<List<Word>> rw = new ArrayList<>();
                List<List<Span>> row = t.rows().get(r);
                for (int c = 0; c < cols; c++) {
                    List<Word> ws = words(c < row.size() ? row.get(c) : List.of(), t.header() && r == 0 ? head : body);
                    float all = 0, big = 0;
                    for (Word w : ws) { all += w.width() + (w.spaced() ? space(TABLE) : 0); big = Math.max(big, w.width()); }
                    natural[c] = Math.max(natural[c], all); least[c] = Math.max(least[c], big);
                    rw.add(ws);
                }
                words.add(rw);
            }
            float[] w = new float[cols];
            float sumN = 0, sumL = 0;
            for (int c = 0; c < cols; c++) { natural[c] = Math.min(natural[c], WIDTH * 0.6f) + 2 * pad; least[c] = Math.min(Math.max(least[c], 16f), 130f) + 2 * pad; sumN += natural[c]; sumL += least[c]; }
            if (sumN <= WIDTH) w = natural;
            else if (sumL >= WIDTH) for (int c = 0; c < cols; c++) w[c] = least[c] * WIDTH / sumL;
            else { float extra = WIDTH - sumL, want = sumN - sumL; for (int c = 0; c < cols; c++) w[c] = least[c] + (want <= 0 ? extra / cols : extra * (natural[c] - least[c]) / want); }
            if (!atTop()) y -= 3;
            List<List<List<Piece>>> header = null;
            for (int r = 0; r < words.size(); r++) {
                boolean isHead = t.header() && r == 0;
                List<List<List<Piece>>> cells = new ArrayList<>();
                int n = 1;
                for (int c = 0; c < cols; c++) { List<List<Piece>> ls = lines(words.get(r).get(c), w[c] - 2 * pad, TABLE); cells.add(ls); n = Math.max(n, ls.size()); }
                if (isHead) header = cells;
                float h = n * leading + 2 * pad;
                if (y - h < BOTTOM && h <= TOP - BOTTOM && !atTop()) { newPage(); if (header != null && !isHead) row(header, w, true, leading, pad); }
                row(cells, w, isHead, leading, pad);
            }
            y -= 7;
        }

        /** One row of a table; a row taller than the room left goes on over the page. */
        void row(List<List<List<Piece>>> cells, float[] w, boolean head, float leading, float pad) throws IOException {
            int n = 1;
            for (List<List<Piece>> c : cells) n = Math.max(n, c.size());
            int from = 0;
            while (from < n) {
                int fit = (int) ((y - BOTTOM - 2 * pad) / leading);
                if (fit < 1) { newPage(); continue; }
                int upto = Math.min(n, from + fit);
                float h = (upto - from) * leading + 2 * pad, total = 0;
                for (float x : w) total += x;
                if (head) { cs.setNonStrokingColor(SHADE[0], SHADE[1], SHADE[2]); cs.addRect(MARGIN, y - h, total, h); cs.fill(); }
                float x = MARGIN;
                for (int c = 0; c < cells.size(); c++) {
                    List<List<Piece>> ls = cells.get(c);
                    for (int k = from; k < upto && k < ls.size(); k++) draw(cs, page, ls.get(k), x + pad, y - pad - TABLE - (k - from) * leading, INK);
                    x += w[c];
                }
                cs.setStrokingColor(LINE[0], LINE[1], LINE[2]); cs.setLineWidth(0.5f);
                x = MARGIN;
                for (float cw : w) { cs.addRect(x, y - h, cw, h); x += cw; }
                cs.stroke();
                y -= h; from = upto;
                if (from < n) newPage();
            }
        }

        void code(Code c) throws IOException {
            float leading = CODE * 1.35f;
            if (!atTop()) y -= 2;
            for (String raw : c.lines()) {
                List<Piece> chars = new ArrayList<>();
                for (int i = 0; i < raw.length(); ) { int cp = raw.codePointAt(i); i += Character.charCount(cp); if (cp == ' ' || !dropped(cp)) add(chars, fonts.mono, CODE, cp, null, true); }
                List<List<Piece>> lines = new ArrayList<>();
                List<Piece> line = new ArrayList<>(); float lw = 0;
                for (Piece p : chars) {
                    for (int i = 0; i < p.text().length(); ) {
                        int cp = p.text().codePointAt(i); i += Character.charCount(cp);
                        String ch = new String(Character.toChars(cp)); float cw = p.font().getStringWidth(ch) / 1000f * CODE;
                        if (lw + cw > WIDTH - 8 && !line.isEmpty()) { lines.add(merge(line)); line = new ArrayList<>(); lw = 0; }
                        line.add(new Piece(p.font(), CODE, ch, cw, null, true)); lw += cw;
                    }
                }
                lines.add(merge(line));
                for (List<Piece> l : lines) {
                    room(leading);
                    cs.setNonStrokingColor(SHADE[0], SHADE[1], SHADE[2]); cs.addRect(MARGIN, y - leading, WIDTH, leading); cs.fill();
                    draw(cs, page, l, MARGIN + 4, y - CODE - 1, CODE_INK);
                    y -= leading;
                }
            }
            y -= 6;
        }

        /** The most a picture takes on the page: the column's width or 360 pt, 260 pt tall; a pixel is three quarters of a point at most. */
        static final float PICTURE_W = Math.min(WIDTH, 360f), PICTURE_H = 260f, POINTS_PER_PIXEL = 0.75f;

        /**
         * A picture the library holds, drawn from its bytes with its caption under it; one it does not hold, or one PDFBox cannot read
         * (WebP), leaves its caption in its place, the words and the page it came from.
         */
        void image(Image i) throws IOException {
            PDImageXObject img = null;
            if (i.bytes() != null) { try { img = PDImageXObject.createFromByteArray(doc, i.bytes(), "picture"); } catch (IOException | RuntimeException e) { img = null; } }
            Style cap = new Style(SMALL, false, true, GREY);
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) { para(List.of(Span.plain("[" + i.caption() + "]")), cap, 0, 8); return; }
            float w = img.getWidth(), h = img.getHeight();
            float scale = Math.min(POINTS_PER_PIXEL, Math.min(PICTURE_W / w, PICTURE_H / h));
            float dw = w * scale, dh = h * scale;
            room(dh + SMALL * 1.42f + 12);
            y -= 4;
            cs.drawImage(img, MARGIN, y - dh, dw, dh);
            y -= dh + 3;
            para(List.of(Span.plain(i.caption())), cap, 0, 8);
        }

        void rule() throws IOException {
            room(14);
            y -= 5;
            cs.setStrokingColor(LINE[0], LINE[1], LINE[2]); cs.setLineWidth(0.5f);
            cs.moveTo(MARGIN, y); cs.lineTo(PAGE_W - MARGIN, y); cs.stroke();
            y -= 8;
        }

        /** Every page's foot: the footer's words on the left, the page's number of all on the right. */
        void footers(String footer) throws IOException {
            int total = pages.size();
            Style st = new Style(FOOT, false, false, GREY);
            for (int i = 0; i < total; i++) {
                PDPage pg = pages.get(i);
                String num = "page " + (i + 1) + " of " + total;
                try (PDPageContentStream s = new PDPageContentStream(doc, pg, AppendMode.APPEND, true, true)) {
                    List<Piece> right = lines(words(List.of(Span.plain(num)), st), WIDTH, FOOT).get(0);
                    float rw = 0; for (Piece p : right) rw += p.width();
                    if (!footer.isBlank()) draw(s, pg, lines(words(List.of(Span.plain(footer)), st), WIDTH - rw - 12, FOOT).get(0), MARGIN, MARGIN / 2, GREY);
                    draw(s, pg, right, PAGE_W - MARGIN - rw, MARGIN / 2, GREY);
                }
            }
        }
    }
}
