package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.researchzosho.Config;
import org.researchzosho.drive.Declined;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The one reader of PDFs (0.7.3): adding to the shelves, research reading, a translation's units and the pictures all read a PDF here.
 * It gives the document as a person reads it, page by page, paragraphs whole and in reading order ({@link PdfLayout}), and says what it
 * could not read.
 *
 * <p>First the preflight, from the PDF's structure and glyphs alone, no model and no drawing unless a page has no glyphs: each page is
 * read by its text layer; or is a picture of text (no glyphs over a picture that covers the page, or only a hidden text layer that
 * reads badly); or its text layer is there but fails the sensor; or it is empty. The sensor's thresholds were measured on real files:
 * a garbled layer (a Type 3 font whose glyphs map to control characters) has three quarters of its glyphs bad and under a tenth
 * letters, where good pages have under 3% bad; a layer whose order could not be recovered ends under 15% of its paragraphs of twenty
 * characters or more with a sentence's end, where good prose ends 35% to 100%; a scanner's hidden layer (the file's own OCR) is judged
 * like any text layer, more strictly: in a spaced script nine words in ten must look like words, in Japanese or Chinese 35% of its
 * paragraphs must end a sentence and fewer than 35% may be fragments of five characters or less. Then the pages that need it are read
 * by eye: drawn, and transcribed by the model that sees, when the library has one; without one they are named as not read, and the
 * junk of a broken text layer is never given as the text.
 */
public final class PdfReading {
    private PdfReading() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** How a page is read: by its text layer, by the scan's own recognised text (OCR), by eye, or not at all because it is empty. */
    public enum Way { TEXT, OCR, EYE, BLANK }

    /** Why a page needs the eye. */
    public static final String NO_TEXT = "no-text", GARBLED = "garbled", OCR_POOR = "ocr-poor", OCR_SPARSE = "ocr-sparse", ORDER = "order", DRAWN = "drawn";

    /** One page's preflight: how it is read, why it needs the eye (""), its measurements, the way its lines run, the pictures on a typed page that may hold writing. */
    public record Check(int page, Way way, String why, PdfLayout.Stats stats, String direction, int textPictures) { }

    // ---- the sensor ----

    static final double GARBLED_BAD = 0.10, GARBLED_LETTERS = 0.30, ORDER_ENDED = 0.15, OCR_WORDS = 0.90, OCR_CJK_ENDED = 0.35, OCR_CJK_FRAGMENTS = 0.35, SCAN_COVER = 0.5, OCR_SPARSE_CHARS = 200;
    static final int ORDER_LONG = 5;

    /** How a page is read, from its measurements; {@code ink} is asked only of a page with no glyphs and no picture. */
    static Check check(PdfLayout.Page p, Supplier<Double> ink) {
        PdfLayout.Stats s = p.stats();
        int textPictures = 0;
        for (PdfLayout.Block b : p.blocks()) if (b.kind().equals(PdfLayout.PICTURE) && b.box().area() >= 0.04 * p.width() * p.height()) textPictures++;
        if (s.glyphs() == 0 || s.chars() < 20 && s.invisible() == 0) {
            if (s.pictureCover() >= 0.3) return new Check(p.number(), Way.EYE, NO_TEXT, s, p.direction(), 0);
            if (s.glyphs() == 0) return ink.get() > 0.01 ? new Check(p.number(), Way.EYE, DRAWN, s, p.direction(), 0) : new Check(p.number(), Way.BLANK, "", s, p.direction(), 0);
        }
        if (s.badShare() >= GARBLED_BAD || (s.letterShare() < GARBLED_LETTERS && s.glyphs() >= 50)) return new Check(p.number(), Way.EYE, GARBLED, s, p.direction(), 0);
        boolean ocr = s.invisibleShare() >= 0.5 && s.pictureCover() >= SCAN_COVER;
        if (ocr) {
            if (s.chars() < OCR_SPARSE_CHARS) return new Check(p.number(), Way.EYE, OCR_SPARSE, s, p.direction(), 0);
            boolean spaced = s.tokens() >= 30;
            boolean poor = spaced ? s.wordShare() < OCR_WORDS || (s.longUnits() >= ORDER_LONG && s.endedShare() < ORDER_ENDED)
                    : s.endedShare() < OCR_CJK_ENDED || s.fragmentShare() >= OCR_CJK_FRAGMENTS;
            return poor ? new Check(p.number(), Way.EYE, OCR_POOR, s, p.direction(), 0) : new Check(p.number(), Way.OCR, "", s, p.direction(), 0);
        }
        if (s.longUnits() >= ORDER_LONG && s.endedShare() < ORDER_ENDED) return new Check(p.number(), Way.EYE, ORDER, s, p.direction(), textPictures);
        return new Check(p.number(), Way.TEXT, "", s, p.direction(), textPictures);
    }

    // ---- the preflight ----

    /**
     * What the preflight found: the pages, each page's check, the pages that cannot be drawn on this machine (their pictures are JBIG2 or
     * JPEG 2000 and no reader for them is here), and the title the PDF's own information gives.
     */
    public record Preflight(int pages, List<Check> checks, List<Integer> undrawable, String language) {
        public List<Integer> pagesBy(Way w) { List<Integer> out = new ArrayList<>(); for (Check c : checks) if (c.way() == w) out.add(c.page()); return out; }
        public List<Integer> eyePages() { return pagesBy(Way.EYE); }
        public int byText() { return pagesBy(Way.TEXT).size() + pagesBy(Way.OCR).size(); }
        public int content() { return pages - pagesBy(Way.BLANK).size(); }
        public List<Integer> eyePagesFor(String why) { List<Integer> out = new ArrayList<>(); for (Check c : checks) if (c.way() == Way.EYE && c.why().equals(why)) out.add(c.page()); return out; }
        /** The typed pages with pictures that may hold writing of their own, and how many pictures. */
        public int textPictures() { int n = 0; for (Check c : checks) if (c.way() != Way.EYE) n += c.textPictures(); return n; }
        public List<Integer> textPicturePages() { List<Integer> out = new ArrayList<>(); for (Check c : checks) if (c.way() != Way.EYE && c.textPictures() > 0) out.add(c.page()); return out; }
        /** The pages that need the eye and can be drawn. */
        public List<Integer> drawable() { List<Integer> out = new ArrayList<>(eyePages()); out.removeAll(undrawable); return out; }

        /** The sentences a person reads, said when a PDF is added or attached and in a job's plan; {@code eyes} the seeing model, null when none is set. */
        public String said(Chrome c, String eyes, double secondsPerPage) {
            List<String> out = new ArrayList<>();
            int text = byText(), ocr = pagesBy(Way.OCR).size();
            List<Integer> eye = eyePages();
            if (text > 0) out.add(text == pages ? (pages == 1 ? c.t("Its one page reads by its text.") : c.t("All {0} of its pages read by their text.", c.number(pages)))
                    : c.t(text == 1 ? "One of its {1} pages reads by its text." : "{0} of its {1} pages read by their text.", c.number(text), c.number(pages)));
            if (ocr > 0) out.add(c.n(ocr, "{0} of them is a scan whose text comes from the file's own text recognition, which reads well enough to use.", "{0} of them are scans whose text comes from the file's own text recognition, which reads well enough to use."));
            for (String why : List.of(NO_TEXT, DRAWN, GARBLED, OCR_SPARSE, OCR_POOR, ORDER)) {
                List<Integer> ps = eyePagesFor(why);
                if (ps.isEmpty()) continue;
                String pages = PdfReading.pages(ps, c);
                out.add(switch (why) {
                    case NO_TEXT -> c.t(ps.size() == 1 ? "Page {0} is a picture of a page, with no text in the file." : "Pages {0} are pictures of pages, with no text in the file.", pages);
                    case DRAWN -> c.t(ps.size() == 1 ? "Page {0} is drawn without any text in the file." : "Pages {0} are drawn without any text in the file.", pages);
                    case GARBLED -> c.t(ps.size() == 1 ? "The text of page {0} is garbled: its characters are not mapped to letters." : "The text of pages {0} is garbled: their characters are not mapped to letters.", pages);
                    case OCR_SPARSE -> c.t(ps.size() == 1 ? "Page {0} is a scan on which the file's own text recognition found almost nothing." : "Pages {0} are scans on which the file's own text recognition found almost nothing.", pages);
                    case OCR_POOR -> c.t(ps.size() == 1 ? "Page {0} is a scan whose recognised text reads badly." : "Pages {0} are scans whose recognised text reads badly.", pages);
                    default -> c.t(ps.size() == 1 ? "The order of the words on page {0} could not be worked out from its text." : "The order of the words on pages {0} could not be worked out from its text.", pages);
                });
            }
            List<Integer> drawable = drawable();
            List<Integer> needed = new ArrayList<>(drawable);   // the pages lost without the eye: a page whose order alone is in doubt keeps its words
            needed.removeAll(eyePagesFor(ORDER));
            if (!undrawable.isEmpty()) out.add(c.t(undrawable.size() == 1 ? "Page {0} cannot be drawn on this machine (its picture is stored as JBIG2 or JPEG 2000), so it cannot be read by eye." : "Pages {0} cannot be drawn on this machine (their pictures are stored as JBIG2 or JPEG 2000), so they cannot be read by eye.", PdfReading.pages(undrawable, c)));
            if (!drawable.isEmpty()) {
                // the pages read by eye named, not counted: they are the pages above that can be drawn, which may be fewer
                if (eyes != null) out.add(secondsPerPage > 0 ? c.t(drawable.size() == 1 ? "Page {0} is read by eye by {1}, which takes {2}." : "Pages {0} are read by eye by {1}, which takes {2}.", PdfReading.pages(drawable, c), eyes, Translation.duration(Math.round(drawable.size() * secondsPerPage), c))
                        : c.t(drawable.size() == 1 ? "Page {0} is read by eye by {1}; reading by eye has not been timed on that model yet." : "Pages {0} are read by eye by {1}; reading by eye has not been timed on that model yet.", PdfReading.pages(drawable, c), eyes));
                else {
                    if (!needed.isEmpty()) out.add(c.t(needed.size() == 1 ? "Reading page {0} needs a model that sees, and none is set: without one, the other pages are read and page {0} is named as not read." : "Reading pages {0} needs a model that sees, and none is set: without one, the other pages are read and these are named as not read.", PdfReading.pages(needed, c)));
                    if (!eyePagesFor(ORDER).isEmpty()) out.add(c.t(eyePagesFor(ORDER).size() == 1 ? "Without a model that sees, the words of page {0} are given in the order found." : "Without a model that sees, the words of pages {0} are given in the order found.", PdfReading.pages(eyePagesFor(ORDER), c)));
                    out.add(c.t("A model that sees is set with researchzosho models add (a local model with its picture file, or a hosted one) or on the Settings page under Models."));
                }
            }
            int pics = textPictures();
            if (pics > 0) {   // chosen by the pictures and by the pages they are on: several pictures may stand on one page
                String what = pics == 1 ? (eyes != null ? c.t("the model that sees describes it, quoting its writing, when it is kept on the shelves") : c.t("it is kept as a picture on the shelves and its writing is not read"))
                        : (eyes != null ? c.t("the model that sees describes them, quoting their writing, when they are kept on the shelves") : c.t("they are kept as pictures on the shelves and their writing is not read"));
                out.add(c.t(pics == 1 ? "One picture on page {0} may hold writing of its own; {1}." : textPicturePages().size() == 1 ? "{2} pictures on page {0} may hold writing of their own; {1}." : "{2} pictures on pages {0} may hold writing of their own; {1}.",
                        PdfReading.pages(textPicturePages(), c), what, c.number(pics)));
            }
            int blank = pagesBy(Way.BLANK).size();
            if (blank > 0 && blank < pages) out.add(c.n(blank, "{0} page is blank.", "{0} pages are blank."));
            return sentences(c, out);
        }

        /** One line for the document's record on the shelves: the pages by each way, by page number. */
        public String line() {
            StringBuilder b = new StringBuilder();
            b.append(pages).append(pages == 1 ? " page" : " pages").append(": text ").append(PdfReading.pages(pagesBy(Way.TEXT)));
            if (!pagesBy(Way.OCR).isEmpty()) b.append("; scan's own OCR ").append(PdfReading.pages(pagesBy(Way.OCR)));
            for (String why : List.of(NO_TEXT, DRAWN, GARBLED, OCR_SPARSE, OCR_POOR, ORDER)) if (!eyePagesFor(why).isEmpty()) b.append("; eye (").append(why).append(") ").append(PdfReading.pages(eyePagesFor(why)));
            if (!undrawable.isEmpty()) b.append("; cannot be drawn ").append(PdfReading.pages(undrawable));
            if (!pagesBy(Way.BLANK).isEmpty()) b.append("; blank ").append(PdfReading.pages(pagesBy(Way.BLANK)));
            if (textPictures() > 0) b.append("; pictures that may hold writing on ").append(PdfReading.pages(textPicturePages()));
            return b.toString();
        }

        /** The sensor's numbers, page by page, for the document's record. */
        public ObjectNode json() {
            ObjectNode o = M.createObjectNode();
            o.put("pages", pages); o.put("line", line());
            ArrayNode u = o.putArray("undrawable"); for (int p : undrawable) u.add(p);
            ArrayNode a = o.putArray("checks");
            for (Check c : checks) {
                PdfLayout.Stats s = c.stats();
                ObjectNode x = a.addObject();
                x.put("page", c.page()).put("way", c.way().name().toLowerCase(Locale.ROOT)).put("why", c.why()).put("direction", c.direction()).put("text_pictures", c.textPictures());
                if (s == null) continue;
                x.put("glyphs", s.glyphs()).put("chars", s.chars()).put("units", s.units()).put("fragments", round(s.fragmentShare())).put("ended", round(s.endedShare()))
                        .put("bad", round(s.badShare())).put("letters", round(s.letterShare())).put("words", round(s.wordShare())).put("hidden", round(s.invisibleShare()))
                        .put("picture_cover", round(s.pictureCover())).put("furniture", s.furniture()).put("ruby", s.ruby()).put("marks", s.marks()).put("text_pictures", c.textPictures());
            }
            return o;
        }
    }

    /** Sentences one after another as the language sets them: a space between in English, none in Japanese or Chinese. */
    static String sentences(Chrome c, List<String> out) { return String.join(c.code.equals("ja") || c.code.equals("zh") ? "" : " ", out); }

    /**
     * A preflight kept on a document's record or a job's arguments ({@link Preflight#json()}), read back: enough to say the same sentences
     * again without reading the PDF (the measurements themselves are not brought back); null when the record holds none.
     */
    public static Preflight fromJson(JsonNode j) {
        if (j == null || !j.path("checks").isArray()) return null;
        List<Check> checks = new ArrayList<>();
        for (JsonNode c : j.path("checks")) {
            Way w;
            try { w = Way.valueOf(c.path("way").asText("text").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { w = Way.TEXT; }
            checks.add(new Check(c.path("page").asInt(), w, c.path("why").asText(""), null, c.path("direction").asText("ltr"), c.path("text_pictures").asInt(0)));
        }
        List<Integer> undrawable = new ArrayList<>();
        for (JsonNode u : j.path("undrawable")) undrawable.add(u.asInt());
        return new Preflight(j.path("pages").asInt(checks.size()), checks, undrawable, "");
    }

    /** The same from the JSON text a record keeps. */
    public static Preflight fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return fromJson(M.readTree(json)); } catch (IOException e) { return null; }
    }

    static double round(double d) { return Math.round(d * 1000) / 1000.0; }

    /** Page numbers as a person writes them, in the language's own way of listing: 3, 5–9 and 12; 3、5–9と12. */
    static String pages(List<Integer> ps, Chrome c) {
        if (ps.isEmpty()) return "";
        List<String> runs = new ArrayList<>();
        int start = ps.get(0), prev = start;
        for (int i = 1; i <= ps.size(); i++) {
            int n = i < ps.size() ? ps.get(i) : Integer.MIN_VALUE;
            if (n == prev + 1) { prev = n; continue; }
            runs.add(start == prev ? String.valueOf(start) : start + "–" + prev);
            start = prev = n;
        }
        return c.and(runs);
    }

    /** Page numbers in English, for the document's record. */
    static String pages(List<Integer> ps) {
        if (ps.isEmpty()) return "";
        List<String> runs = new ArrayList<>();
        int start = ps.get(0), prev = start;
        for (int i = 1; i <= ps.size(); i++) {
            int n = i < ps.size() ? ps.get(i) : Integer.MIN_VALUE;
            if (n == prev + 1) { prev = n; continue; }
            runs.add(start == prev ? String.valueOf(start) : start + "–" + prev);
            start = prev = n;
        }
        return runs.size() == 1 ? runs.get(0) : String.join(", ", runs.subList(0, runs.size() - 1)) + " and " + runs.get(runs.size() - 1);
    }

    // ---- the eye ----

    /** The model that sees, as the reader asks it: its name for a person, and a picture's writing for an ask; a decline raises {@link Declined}. */
    public interface Eyes { String name(); String read(byte[] png, String ask); }

    /** The eyes the library has, or null: the seeing entry ({@link ModelEntries#seeing()}), on its drive. Tests stand one in. */
    static volatile Supplier<Eyes> EYES = PdfReading::liveEyes;
    public static void useEyes(Supplier<Eyes> e) { EYES = e == null ? PdfReading::liveEyes : e; }

    static Eyes liveEyes() {
        ModelEntries.Entry e;
        try { e = ModelEntries.seeing(); } catch (RuntimeException x) { return null; }
        if (e == null) return null;
        String name = e.name().equals(ModelEntries.DEFAULT) || e.name().equals("vision") ? e.model() : e.name() + " (" + e.model() + ")";
        return new Eyes() {
            @Override public String name() { return name; }
            @Override public String read(byte[] png, String ask) {
                Researcher.Drive d = Vision.drive(e);
                ArrayNode msgs = M.createArrayNode();
                ArrayNode content = msgs.addObject().put("role", "user").putArray("content");
                content.addObject().put("type", "text").put("text", ask);
                content.addObject().put("type", "image_url").putObject("image_url").put("url", Vision.dataUrl(png, "image/png"));
                String said = d.prose(msgs, 6000);
                return said == null ? "" : said.strip();
            }
        };
    }

    /** What the seeing model is asked of a page: its writing, as a reader reads it, in its own language, nothing else. */
    static String pageAsk(String language) {
        return "Transcribe all the writing on this page of a document, in the order a reader reads it. Keep the page's own language and script: write the words exactly as they are printed, untranslated and unsummarised. "
                + "Write each paragraph as one line, joining the lines the page breaks it into, with a blank line between paragraphs; a heading on a line of its own. "
                + "For text set vertically (Japanese, Chinese), read each column from the top and the columns from right to left. "
                + "Keep footnote marks where they stand; leave out the page number, the running head, and ruby (the small reading aids beside characters). "
                + "Write a table one row to a line, its cells separated by \" | \". Write □ for a character you cannot read. "
                + (language == null || language.isBlank() ? "" : "The document is written in " + ReadIn.name(language) + ". ")
                + "Answer with the transcription only. If the page has no writing, answer: (no writing)";
    }

    /** What the seeing model is asked of a picture inside a typed page. */
    static final String PICTURE_ASK = "This picture is inside a page of a document. If it holds writing (a table, a scanned text, labels), transcribe the writing exactly, in its own language and script, untranslated: a table one row to a line, its cells separated by \" | \". "
            + "If it holds no writing beyond a word or two, answer only: (no writing)";

    /** The page drawn for the eye: the long side about 2,000 pixels (170 dpi for A4), at most 220 dpi; poppler's pdftoppm draws a page whose pictures PDFBox cannot decode. */
    static byte[] drawn(PDDocument doc, byte[] bytes, int page, boolean undecodable) {
        try {
            var box = doc.getPage(page - 1).getCropBox();
            float dpi = (float) Math.min(220, 2000 * 72.0 / Math.max(box.getWidth(), box.getHeight()));
            BufferedImage img = undecodable ? poppler(bytes, page, dpi) : PdfGlyphs.render(doc, page, dpi);
            if (img == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) { return null; }
    }

    /** A page drawn by poppler's pdftoppm, when the machine has it; null otherwise. */
    static BufferedImage poppler(byte[] bytes, int page, float dpi) {
        String tool = popplerTool();
        if (tool == null) return null;
        Path dir = null;
        try {
            dir = Files.createTempDirectory("researchzosho-page");
            Path pdf = dir.resolve("in.pdf");
            Files.write(pdf, bytes);
            Process pr = new ProcessBuilder(tool, "-r", String.valueOf(Math.round(dpi)), "-png", "-f", String.valueOf(page), "-l", String.valueOf(page), "-singlefile", pdf.toString(), dir.resolve("p").toString()).redirectErrorStream(true).start();
            try (InputStream in = pr.getInputStream()) { in.readAllBytes(); }
            if (!pr.waitFor(120, TimeUnit.SECONDS)) { pr.destroyForcibly(); return null; }
            Path png = dir.resolve("p.png");
            return Files.isRegularFile(png) ? ImageIO.read(png.toFile()) : null;
        } catch (IOException | InterruptedException | RuntimeException e) { return null; }
        finally { if (dir != null) try (var ls = Files.walk(dir)) { ls.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } }); } catch (IOException ignored) { } }
    }

    private static volatile String popplerTool = "?";
    /** poppler's pdftoppm on the PATH, or null; {@code RESEARCHZOSHO_PDFTOTEXT=off} keeps to PDFBox alone. */
    static String popplerTool() {
        if (!"?".equals(popplerTool)) return popplerTool;
        String found = null;
        if (!"off".equalsIgnoreCase(Config.get("RESEARCHZOSHO_PDFTOTEXT", ""))) {
            for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
                for (String n : new String[]{"pdftoppm", "pdftoppm.exe"}) { Path c = Path.of(dir.isBlank() ? "." : dir).resolve(n); if (Files.isExecutable(c)) { found = c.toString(); break; } }
                if (found != null) break;
            }
        }
        popplerTool = found;
        return found;
    }

    // ---- reading ----

    /**
     * How a document is read: whether pages are read by eye at all (the preflight reads none), at most how many, a memory of pages
     * already read (a long job's checkpoint), who is told the progress (pages read so far, pages to read), and when to stop.
     */
    public record Options(boolean eye, int mostEyePages, PageMemo memo, BiConsumer<Integer, Integer> progress, BooleanSupplier stop) {
        public static Options preflightOnly() { return new Options(false, 0, null, null, null); }
        public static Options reading(int most) { return new Options(true, most, null, null, null); }
        public Options with(PageMemo m, BiConsumer<Integer, Integer> p, BooleanSupplier s) { return new Options(eye, mostEyePages, m, p, s); }
    }

    /** A page's transcription kept between runs of a job: {@code get} gives null for a page not read yet. */
    public interface PageMemo { String get(String key); void put(String key, String text); }

    /**
     * The document as read: its preflight; its pages laid out, a page read by eye holding the model's paragraphs; the pages read by eye
     * and by which model; the pages that needed the eye and were not read, each with why; the pictures inside typed pages read for their
     * writing; the seconds reading by eye took.
     */
    public record Read(Preflight preflight, List<PdfLayout.Page> pages, List<Integer> byEye, String eyes, Map<Integer, String> unread, int picturesRead, long eyeSeconds, String infoTitle, String lang) {
        public Read(Preflight preflight, List<PdfLayout.Page> pages, List<Integer> byEye, String eyes, Map<Integer, String> unread, int picturesRead, long eyeSeconds, String infoTitle) { this(preflight, pages, byEye, eyes, unread, picturesRead, eyeSeconds, infoTitle, ""); }
        /** The title: the PDF's own when it is one and not a file's name, else the first page's title, else its first heading. */
        public String title() {
            String t = infoTitle == null ? "" : infoTitle.strip();
            if (!t.isEmpty() && !t.matches("(?i).*\\.(indd|docx?|pdf|qxd|qxp|tex|odt|rtf|pages|ai|psd)$") && !t.matches("(?i)^(microsoft word - |untitled|document\\d*$).*")) return t;
            for (PdfLayout.Page p : pages) for (PdfLayout.Block b : p.blocks()) if (b.kind().equals(PdfLayout.TITLE)) return b.text();
            for (PdfLayout.Page p : pages) for (PdfLayout.Block b : p.blocks()) if (b.kind().equals(PdfLayout.HEADING)) return cut(b.text());
            for (PdfLayout.Page p : pages) for (PdfLayout.Block b : p.blocks()) if (!b.text().isBlank()) return cut(b.text());
            return "";
        }

        /**
         * The text: the paragraphs in order with a blank line between; a page that needed the eye and was not read said at its place, after
         * what of it does read (the lines of a partly garbled page that are words).
         */
        public String text() {
            StringBuilder b = new StringBuilder();
            for (PdfLayout.Page p : pages) {
                for (PdfLayout.Block k : p.blocks()) if (!k.text().isBlank()) b.append(b.length() == 0 ? "" : "\n\n").append(k.text());
                if (unread.containsKey(p.number())) b.append(b.length() == 0 ? "" : "\n\n").append("[Page ").append(p.number()).append(" was not read: ").append(unread.get(p.number())).append(".]");
            }
            return b.toString();
        }

        /** Whether any text was read at all. */
        public boolean any() { for (PdfLayout.Page p : pages) for (PdfLayout.Block k : p.blocks()) if (!k.text().isBlank()) return true; return false; }

        /** What the reading came to, for a person, first in a long job's summary: the pages by text, by eye and by whom, the pages not read and why. */
        public String said(Chrome c) {
            List<String> out = new ArrayList<>();
            int text = preflight.byText();
            if (text > 0) out.add(text == preflight.pages() ? (text == 1 ? c.t("Its one page was read by its text.") : c.t("All {0} pages of the PDF were read by their text.", c.number(text)))
                    : c.n(text, "One page of the PDF was read by its text.", "{0} pages of the PDF were read by their text."));
            if (!byEye.isEmpty()) out.add(c.t(byEye.size() == 1 ? "Page {0} was read by eye by {1}." : "Pages {0} were read by eye by {1}.", PdfReading.pages(byEye, c), eyes));
            Map<String, List<Integer>> whys = new LinkedHashMap<>();
            for (var e : unread.entrySet()) whys.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            for (var e : whys.entrySet()) out.add(e.getValue().size() == 1 ? c.t("Page {0} was not read: {1}.", PdfReading.pages(e.getValue(), c), c.t(e.getKey()))
                    : c.t("Pages {0} were not read: {1}.", PdfReading.pages(e.getValue(), c), c.t(MANY.getOrDefault(e.getKey(), e.getKey()))));
            return sentences(c, out);
        }
    }

    static String cut(String t) { String s = t.replaceAll("\\s+", " ").strip(); return s.length() > 120 ? s.substring(0, 117) + "..." : s; }

    /** Why a page was not read, in the words of the text and the summary: whole sentences without their full stop, translated by {@link Chrome}. */
    static final String NOT_READ_NO_EYES = "it is a picture of text and no model that sees is set",
            NOT_READ_UNDRAWABLE = "its picture is stored in a form this machine cannot draw",
            NOT_READ_EMPTY = "the model that sees gave nothing for it",
            NOT_READ_DECLINED = "the model that sees declined to read it",
            NOT_READ_OVER = "it is past the pages read by eye for one document";

    /** The same reasons for several pages, after "Pages {0} were not read:". */
    static final Map<String, String> MANY = Map.of(
            NOT_READ_NO_EYES, "they are pictures of text and no model that sees is set",
            NOT_READ_UNDRAWABLE, "their pictures are stored in a form this machine cannot draw",
            NOT_READ_EMPTY, "the model that sees gave nothing for them",
            NOT_READ_DECLINED, "the model that sees declined to read them",
            NOT_READ_OVER, "they are past the pages read by eye for one document");

    /** The pages read at most by eye for a document added to the shelves or read in research: a long scan is read in a job of its own. */
    public static final int SHELF_EYE_PAGES = 30;

    private static final int BATCH = 12;
    /** The documents read lately, by their bytes and the eyes that read them: a plan, its run and the shelving after read a PDF once. */
    private record Cached(Read read, int most) { }
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(8, 0.75f, true) { @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> e) { return size() > 6; } };

    /** The preflight of a PDF: no model, no drawing beyond a page with nothing on it. */
    public static Preflight preflight(byte[] pdf) throws IOException { return read(pdf, Options.preflightOnly()).preflight(); }

    /** The PDF read: its layout and preflight, then the pages that need it read by eye as {@code o} allows. */
    public static Read read(byte[] pdf, Options o) throws IOException {
        Eyes eyes = o.eye() ? EYES.get() : null;
        String key = sha(pdf) + "|" + (eyes == null ? "" : eyes.name());
        synchronized (CACHE) {
            Cached c = CACHE.get(key);   // read before with as many pages by eye, or with every page that needed it read
            if (c != null && (c.most() >= o.mostEyePages() || !c.read().unread().containsValue(NOT_READ_OVER))) return c.read();
        }
        Read r = readNow(pdf, o, eyes);
        synchronized (CACHE) { CACHE.put(key, new Cached(r, o.mostEyePages())); }
        return r;
    }

    static Read readNow(byte[] pdf, Options o, Eyes eyes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int n = doc.getNumberOfPages();
            List<PdfLayout.Prepared> prepared = new ArrayList<>();
            for (int first = 1; first <= n; first += BATCH) {
                for (PdfLayout.PageIn p : PdfGlyphs.read(doc, first, Math.min(n, first + BATCH - 1))) prepared.add(PdfLayout.prepare(p));
            }
            List<PdfLayout.Page> pages = PdfLayout.finish(prepared);
            List<Check> checks = new ArrayList<>();
            List<Integer> undrawable = new ArrayList<>();
            StringBuilder sample = new StringBuilder();
            for (PdfLayout.Page p : pages) {
                int number = p.number();
                Check c = check(p, () -> PdfGlyphs.ink(doc, number));
                checks.add(c);
                if (c.way() == Way.EYE && !PdfGlyphs.undecodable(doc.getPage(number - 1)).isEmpty() && popplerTool() == null) undrawable.add(number);
                if ((c.way() == Way.TEXT || c.way() == Way.OCR) && sample.length() < 4000) sample.append(p.text()).append('\n');
            }
            String language = sample.length() > 40 ? Translate.languageOf(sample.toString()) : "";
            Preflight pre = new Preflight(n, checks, undrawable, language);
            String info = "";
            try { if (doc.getDocumentInformation() != null && doc.getDocumentInformation().getTitle() != null) info = doc.getDocumentInformation().getTitle(); } catch (RuntimeException ignored) { }
            String lang = "";   // the language the PDF's catalogue names (its Lang), when it names one
            try { if (doc.getDocumentCatalog().getLanguage() != null) lang = doc.getDocumentCatalog().getLanguage(); } catch (RuntimeException ignored) { }
            // the pages that need the eye: read when there are eyes, else named as not read
            Map<Integer, String> unread = new LinkedHashMap<>();
            List<Integer> byEye = new ArrayList<>();
            List<PdfLayout.Page> out = new ArrayList<>(pages);
            long t0 = System.currentTimeMillis();
            List<Integer> need = pre.eyePages();
            int done = 0;
            for (int number : need) {
                if (o.stop() != null && o.stop().getAsBoolean()) throw new IOException("Stopped while reading the PDF's pages by eye.");
                Check c = checks.get(number - 1);
                boolean keepText = c.why().equals(ORDER);   // a text layer whose order is in doubt is still the page's words: kept when there is no eye
                if (undrawable.contains(number)) { if (!keepText) unread.put(number, NOT_READ_UNDRAWABLE); continue; }
                if (eyes == null) { if (!keepText) unread.put(number, NOT_READ_NO_EYES); continue; }
                if (done >= o.mostEyePages()) { if (!keepText) unread.put(number, NOT_READ_OVER); continue; }
                String memoKey = "pdf-page-" + number;
                String said = o.memo() == null ? null : o.memo().get(memoKey);
                if (said == null) {
                    byte[] png = drawn(doc, pdf, number, !PdfGlyphs.undecodable(doc.getPage(number - 1)).isEmpty());
                    if (png == null) { if (!keepText) unread.put(number, NOT_READ_UNDRAWABLE); continue; }
                    try { said = eyes.read(png, pageAsk(pre.language())); }
                    catch (Declined d) { if (!keepText) unread.put(number, NOT_READ_DECLINED); continue; }
                    catch (RuntimeException e) { said = ""; }
                    if (said == null) said = "";
                    if (o.memo() != null && !said.isBlank()) o.memo().put(memoKey, said);
                }
                done++;
                if (o.progress() != null) o.progress().accept(done, need.size());
                List<PdfLayout.Block> blocks = transcribed(said, number);
                if (blocks.isEmpty() && said.strip().matches("(?i)\\(?no writing\\)?\\.?")) { out.set(number - 1, withBlocks(out.get(number - 1), List.of())); byEye.add(number); continue; }
                if (blocks.isEmpty()) { if (!keepText) unread.put(number, NOT_READ_EMPTY); continue; }
                out.set(number - 1, withBlocks(out.get(number - 1), blocks));
                byEye.add(number);
            }
            for (int number : unread.keySet()) {
                Check c = checks.get(number - 1);
                out.set(number - 1, withBlocks(out.get(number - 1), c.why().equals(GARBLED) ? readable(out.get(number - 1).blocks()) : List.of()));
            }
            long eyeSeconds = byEye.isEmpty() ? 0 : (System.currentTimeMillis() - t0) / 1000;
            if (eyes != null && !byEye.isEmpty() && o.memo() == null) recordSpeed(eyes.name(), byEye.size(), eyeSeconds);
            return new Read(pre, out, byEye, eyes == null ? "" : eyes.name(), unread, 0, eyeSeconds, info, lang);
        } catch (IOException e) { throw e; }
        catch (Exception | LinkageError e) { throw new IOException("The PDF could not be read: " + e.getMessage(), e); }
    }

    /**
     * The blocks of a garbled page that read as words: nine in ten of their characters letters, digits or spaces or common punctuation, none
     * of them a bad code point, and in a spaced script four words in five shaped like words. The rest is the junk a broken layer gives,
     * never handed on as text.
     */
    static List<PdfLayout.Block> readable(List<PdfLayout.Block> bs) {
        List<PdfLayout.Block> out = new ArrayList<>();
        for (PdfLayout.Block b : bs) {
            String t = b.text();
            if (t.isBlank() || t.codePoints().anyMatch(PdfLayout::bad)) continue;
            long n = t.codePoints().filter(c -> !Character.isWhitespace(c)).count(), good = t.codePoints().filter(c -> Character.isLetterOrDigit(c) || ".,:;'’\"“”()-–/%&".indexOf(c) >= 0).count();
            if (n < 8 || good < 0.9 * n) continue;
            String[] words = t.split("\\s+");
            long wordy = Arrays.stream(words).filter(w -> w.replaceAll("[\\p{P}\\p{S}]", "").codePoints().allMatch(Character::isLetterOrDigit)).count();
            if (words.length >= 3 && wordy < 0.8 * words.length) continue;
            out.add(b);
        }
        return out;
    }

    static PdfLayout.Page withBlocks(PdfLayout.Page p, List<PdfLayout.Block> blocks) {
        return new PdfLayout.Page(p.number(), p.width(), p.height(), p.direction(), blocks, p.stats());
    }

    private static final Pattern CJK_SPACE = Pattern.compile("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー、。「」『』（）・])[ \\u3000]+(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー、。「」『』（）・])");

    /** The model's transcription as the page's blocks: a paragraph per blank-line-separated part, its lines joined; a short line with no sentence's end a heading. */
    static List<PdfLayout.Block> transcribed(String said, int page) {
        List<PdfLayout.Block> out = new ArrayList<>();
        if (said == null) return out;
        String t = said.replace("\r\n", "\n").strip();
        if (t.matches("(?i)\\(?no writing\\)?\\.?")) return out;
        t = t.replaceAll("(?s)^```[a-z]*\\n(.*?)\\n```$", "$1");
        PdfLayout.Box none = new PdfLayout.Box(0, 0, 0, 0);
        // the paragraphs: the parts between blank lines, as asked; a model that wrote one paragraph to a line with no blank line between
        // (measured on the 27B: a page of three paragraphs came back as three long lines) gives a paragraph a line
        t = t.replaceAll("(?m)^[ \\t]+$", "");
        String[] all = t.split("\\n");
        int longLines = 0; for (String l : all) if (l.strip().length() >= 40) longLines++;
        String[] parts = t.contains("\n\n") || longLines * 2 < all.length ? t.split("\\n\\s*\\n") : all;
        for (String part : parts) {
            String[] lines = part.strip().split("\\n");
            boolean table = lines.length > 1 && Arrays.stream(lines).allMatch(l -> l.contains(" | "));
            if (table) { for (String l : lines) out.add(new PdfLayout.Block(PdfLayout.PARA, l.strip(), page, none, true, 0, 0, 1, none)); continue; }
            String joined = "";
            for (String l : lines) joined = PdfLayout.join(joined, l.strip());
            joined = CJK_SPACE.matcher(joined.strip()).replaceAll("");   // a space the model put where a column ended, between two characters of a script that has none
            if (joined.isEmpty()) continue;
            boolean heading = lines.length == 1 && joined.length() <= 60 && !PdfLayout.endsSentence(joined) && !joined.contains(" | ");
            out.add(new PdfLayout.Block(heading ? PdfLayout.HEADING : PdfLayout.PARA, joined, page, none, true, heading ? 2 : 0, 0, lines.length, none));
        }
        return out;
    }

    // ---- the measured speed of reading by eye, per model ----

    static Path speedFile() { return Config.home().resolve("pdf-eye-speed.json"); }

    /** Seconds a page the model took when last measured; 0 when it has not been timed. */
    public static double secondsPerPage(String model) {
        if (model == null) return 0;
        try {
            Path f = speedFile();
            if (!Files.isRegularFile(f)) return 0;
            JsonNode j = M.readTree(Files.readString(f, StandardCharsets.UTF_8)).path(model);
            long pages = j.path("pages").asLong(0), seconds = j.path("seconds").asLong(0);
            return pages > 0 && seconds > 0 ? seconds / (double) pages : 0;
        } catch (IOException | RuntimeException e) { return 0; }
    }

    static synchronized void recordSpeed(String model, int pages, long seconds) {
        if (model == null || pages <= 0 || seconds <= 0) return;
        try {
            Path f = speedFile();
            ObjectNode j = Files.isRegularFile(f) ? (ObjectNode) M.readTree(Files.readString(f, StandardCharsets.UTF_8)) : M.createObjectNode();
            ObjectNode m = j.has(model) ? (ObjectNode) j.get(model) : j.putObject(model);
            m.put("pages", m.path("pages").asLong(0) + pages); m.put("seconds", m.path("seconds").asLong(0) + seconds);
            Files.createDirectories(f.getParent());
            AtomicWrite.text(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(j) + "\n");
        } catch (IOException | RuntimeException ignored) { /* the speed only sharpens the next estimate */ }
    }

    /** The eyes' name and measured speed for a person's sentence: [name or null, seconds a page or 0]. */
    public static Object[] eyesNow() {
        Eyes e;
        try { e = EYES.get(); } catch (RuntimeException x) { e = null; }
        return new Object[]{e == null ? null : e.name(), e == null ? 0.0 : secondsPerPage(e.name())};
    }

    /** The preflight said in a person's language, with the eyes the library has now. */
    public static String said(Preflight p, Chrome c) {
        Object[] e = eyesNow();
        return p.said(c, (String) e[0], (Double) e[1]);
    }

    static String sha(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Exception e) { return String.valueOf(Arrays.hashCode(b)); }
    }
}
