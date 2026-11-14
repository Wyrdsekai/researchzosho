package org.researchzosho.librarian;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.tools.Entities;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pictures inside a document the person reads in (0.7.0): an EPUB's, a PDF's and a Word file's. Each is kept under
 * {@code catalog/images/} with an entry of its own ({@link Images#keepBytes}: kind image, the document's locator as its page, the
 * caption or alt text as its title, the engine {@link Images#DOCUMENT}), and the document's text keeps a Markdown picture line
 * pointing at the library's copy, so a shelved book shows its pictures on its page and a research run finds them on the shelves
 * ({@link Images#shelves}). An EPUB's pictures stand where its chapters put them; a PDF's and a Word file's, whose places in the text
 * the readers do not know, are listed at the end under their own heading with the page each was on.
 *
 * <p>The model that sees describes a kept picture once, when the library has one ({@link ModelEntries#seeing()}): the first
 * {@link #DESCRIBE_AT_MOST} of a document, the rest kept with their alt text; a picture already on the shelves with a description is
 * not described again, so a rescan costs no call. {@code RESEARCHZOSHO_DESCRIBE_PICTURES=off} leaves descriptions out. Small pictures,
 * furniture (under {@link #MIN_SIDE} pixels a side or {@link #MIN_BYTES}), are left out; at most {@link #MOST} pictures a document.
 * A picture never stops a document: whatever goes wrong with one, the text stands as it was read.
 */
public final class DocPictures {
    private DocPictures() { }

    static final String SETTING = "RESEARCHZOSHO_DESCRIBE_PICTURES";
    static boolean describing() { return !"off".equalsIgnoreCase(Config.get(SETTING, "on")); }

    static final int MOST = 60, DESCRIBE_AT_MOST = 20, MIN_SIDE = 64, MIN_BYTES = 1024;
    static final String HEADING = "## Pictures in this document";

    /** What came of a document's pictures: the text with their lines, how many were kept, how many described. */
    public record Outcome(String text, int kept, int described, List<Images.Kept> pictures) {
        static Outcome none(String text) { return new Outcome(text, 0, 0, List.of()); }
        /** One sentence for the person; "" when the document had no picture worth keeping. */
        public String said() { return kept == 0 ? "" : kept + (kept == 1 ? " picture" : " pictures") + " kept from it" + (described > 0 ? ", " + described + " described by the model that sees" : "") + "."; }
    }

    /**
     * The document's text with its pictures kept and their lines pointing at the library's copies. {@code locator} is the document's;
     * {@code text} is what the reader gave, returned as it is when the document carries no picture or is a format whose pictures are not
     * read (plain text, Markdown, HTML).
     */
    public static Outcome withPictures(LibraryStore store, byte[] bytes, String name, String locator, String title, String text, String collection) {
        if (store == null || bytes == null || bytes.length == 0) return Outcome.none(text);
        String fmt;
        try { fmt = DocUnits.format(bytes, name); } catch (RuntimeException e) { return Outcome.none(text); }
        if (fmt == null) return Outcome.none(text);
        try {
            Keeper k = new Keeper(store, locator, title, collection);
            Outcome o = switch (fmt) {
                case "epub" -> epub(k, bytes, name, text);
                case "pdf" -> pdf(k, bytes, text);
                case "docx" -> docx(k, bytes, text);
                default -> Outcome.none(text);
            };
            k.tally();
            return o;
        } catch (Exception | LinkageError e) { return Outcome.none(text); }
    }

    /** The keeping of one document's pictures: the seeing model chosen once, the shelves read once, the counts. */
    private static final class Keeper {
        final LibraryStore store; final String locator, title, collection;
        final ModelEntries.Entry eyes;
        Map<String, Images.Shelved> held;   // the pictures already on the shelves, by locator; read when the first picture asks
        final List<Images.Kept> kept = new ArrayList<>();
        int described = 0;
        final long[] usage = new long[3];
        Keeper(LibraryStore store, String locator, String title, String collection) {
            this.store = store; this.locator = locator; this.title = title == null ? "" : title; this.collection = collection == null ? "" : collection;
            ModelEntries.Entry e = null;
            if (describing()) { try { e = ModelEntries.seeing(); } catch (RuntimeException x) { e = null; } }
            this.eyes = e;
        }
        boolean full() { return kept.size() >= MOST; }
        /** Whether the bytes are a picture worth keeping: a format the library keeps, not furniture. */
        static boolean worth(byte[] b) {
            if (b == null || b.length < MIN_BYTES || b.length > Images.MAX_BYTES || Images.extension("", b) == null) return false;
            int[] wh = Images.dimensions(b);
            return wh[0] == 0 || (wh[0] >= MIN_SIDE && wh[1] >= MIN_SIDE);
        }
        /** One picture kept, described when the model sees and it is new; null when it is not worth keeping or could not be kept. */
        Images.Kept keep(byte[] b, String within, String alt, String caption) {
            if (full() || !worth(b)) return null;
            String picLocator = locator + "#" + within;
            if (held == null) { held = new HashMap<>(); for (Images.Shelved s : Images.onShelves(store)) { try { String[] r = RawCapture.read(s.raw()); held.putIfAbsent(r[0], s); } catch (IOException ignored) { } } }
            Images.Shelved was = held.get(picLocator);
            String ext = Images.extension("", b), type = Images.contentType("x." + ext);
            String t = !alt.isBlank() ? alt : !caption.isBlank() ? caption : "Picture " + (kept.size() + 1) + (title.isBlank() ? "" : " of " + title);
            String description = was != null && !was.description().isBlank() ? was.description() : "";
            if (description.isEmpty() && eyes != null && described < DESCRIBE_AT_MOST) {
                DriveClient.forgetUsage();
                boolean own = eyes.hosted();   // a hosted seeing entry's calls go on the tally (pictures), and nowhere else
                if (own) DriveClient.meteredByCaller(true);
                try { description = Images.describe(eyes, b, type, ""); } finally { if (own) DriveClient.meteredByCaller(false); }
                long[] u = DriveClient.lastUsage(); if (u != null) { usage[0] += u[0]; usage[2] += u.length > 1 ? u[1] : 0; }
                if (!description.isEmpty()) described++;
            }
            if (!caption.isBlank() && !caption.equals(t)) description = description.isEmpty() ? caption : description + "\n\n" + caption;
            try {
                Map<String, String> more = new LinkedHashMap<>(); more.put("within", within);
                Images.Kept k = Images.keepBytes(store, b, type, t, description, picLocator, locator, collection, more);
                if (k != null) kept.add(k);
                return k;
            } catch (IOException e) { return null; }
        }
        /** A hosted seeing entry's calls on the tally, kind pictures. */
        void tally() {
            if (eyes == null || !eyes.hosted() || usage[0] + usage[2] == 0) return;
            try { Spend.record(store, "", "", eyes, usage.clone(), eyes.cost(usage[0], usage[1], usage[2]), 0, "filed", "pictures"); } catch (IOException ignored) { }
        }
    }

    /** An EPUB: the chapters cut by unit, each picture kept and its unit pointed at the library's copy, the text written back as Markdown. */
    static Outcome epub(Keeper k, byte[] bytes, String name, String text) throws IOException {
        DocUnits.Doc d = DocUnits.cut(bytes, name);
        if (d.images().isEmpty() || d.units().stream().noneMatch(u -> u.kind().equals(DocUnits.IMAGE))) return Outcome.none(text);
        List<DocUnits.Chapter> chapters = new ArrayList<>();
        for (DocUnits.Chapter c : d.chapters()) {
            List<DocUnits.Unit> units = new ArrayList<>();
            for (int i = 0; i < c.units().size(); i++) {
                DocUnits.Unit u = c.units().get(i);
                if (!u.kind().equals(DocUnits.IMAGE)) { units.add(u); continue; }
                byte[] b = d.images().get(u.extra());
                String caption = i + 1 < c.units().size() && c.units().get(i + 1).kind().equals(DocUnits.CAPTION) ? c.units().get(i + 1).text() : "";
                Images.Kept kept = b == null ? null : k.keep(b, u.extra(), u.text() == null ? "" : u.text().strip(), caption.strip());
                if (kept == null) continue;   // a picture not kept leaves no line, as the plain reader left none
                units.add(new DocUnits.Unit(DocUnits.IMAGE, u.text() == null || u.text().isBlank() ? kept.found().title() : u.text(), 0, -1, -1, false, kept.url()));
            }
            chapters.add(new DocUnits.Chapter(c.file(), units));
        }
        if (k.kept.isEmpty()) return Outcome.none(text);
        DocUnits.Doc placed = new DocUnits.Doc(d.format(), d.title(), d.lang(), chapters, Map.of(), d.meta());
        return new Outcome(DocWriters.markdown(placed), k.kept.size(), k.described, List.copyOf(k.kept));
    }

    /** A PDF: every picture drawn on its pages, by PDFBox, kept and listed at the end of the text with its page. */
    static Outcome pdf(Keeper k, byte[] bytes, String text) throws IOException {
        StringBuilder lines = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int p = 0;
            for (PDPage page : doc.getPages()) {
                p++;
                PDResources res = page.getResources();
                if (res == null) continue;
                int onPage = 0;
                for (COSName n : res.getXObjectNames()) {
                    if (k.full()) break;
                    PDXObject x;
                    try { x = res.getXObject(n); } catch (IOException e) { continue; }
                    if (!(x instanceof PDImageXObject img)) continue;
                    if (img.getWidth() < MIN_SIDE || img.getHeight() < MIN_SIDE) continue;
                    byte[] png;
                    try { png = png(img.getImage()); } catch (IOException | RuntimeException e) { continue; }
                    onPage++;
                    Images.Kept kept = k.keep(png, "page=" + p + "&picture=" + onPage, "", "");
                    if (kept == null) continue;
                    lines.append("![").append(kept.found().title().replace("]", ")")).append("](").append(kept.url()).append(")\n*Page ").append(p).append("*\n\n");
                }
            }
        }
        if (k.kept.isEmpty()) return Outcome.none(text);
        return new Outcome(text.stripTrailing() + "\n\n" + HEADING + "\n\n" + lines.toString().strip() + "\n", k.kept.size(), k.described, List.copyOf(k.kept));
    }

    static byte[] png(BufferedImage img) throws IOException {
        if (img == null) throw new IOException("no picture");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", out)) throw new IOException("the picture could not be written as PNG");
        return out.toByteArray();
    }

    private static final Pattern DRAWING = Pattern.compile("<w:drawing>(.*?)</w:drawing>", Pattern.DOTALL);
    private static final Pattern DESCR = Pattern.compile("<wp:docPr\\b[^>]*?\\bdescr=\"([^\"]*)\""), DOCPR_NAME = Pattern.compile("<wp:docPr\\b[^>]*?\\bname=\"([^\"]*)\""), EMBED = Pattern.compile("r:embed=\"([^\"]+)\"");
    private static final Pattern REL = Pattern.compile("<Relationship\\b[^>]*\\bId=\"([^\"]+)\"[^>]*\\bTarget=\"([^\"]+)\"|<Relationship\\b[^>]*\\bTarget=\"([^\"]+)\"[^>]*\\bId=\"([^\"]+)\"");

    /** A Word file: each drawing's picture by its relationship, its alt text from the drawing's description or name, kept and listed at the end. */
    static Outcome docx(Keeper k, byte[] bytes, String text) {
        Map<String, byte[]> z = DocUnits.unzip(bytes);
        byte[] docXml = z.get("word/document.xml"), relsXml = z.get("word/_rels/document.xml.rels");
        if (docXml == null || relsXml == null) return Outcome.none(text);
        Map<String, String> target = new HashMap<>();
        Matcher rm = REL.matcher(new String(relsXml, StandardCharsets.UTF_8));
        while (rm.find()) { if (rm.group(1) != null) target.put(rm.group(1), rm.group(2)); else target.put(rm.group(4), rm.group(3)); }
        StringBuilder lines = new StringBuilder();
        Matcher dm = DRAWING.matcher(new String(docXml, StandardCharsets.UTF_8));
        int n = 0;
        while (dm.find() && !k.full()) {
            String drawing = dm.group(1);
            Matcher em = EMBED.matcher(drawing);
            if (!em.find()) continue;
            String t = target.get(em.group(1));
            if (t == null) continue;
            String path = t.startsWith("/") ? t.substring(1) : "word/" + t;
            byte[] b = z.get(path);
            if (b == null) continue;
            Matcher d = DESCR.matcher(drawing), nm = DOCPR_NAME.matcher(drawing);
            String alt = d.find() ? Entities.decode(d.group(1)) : "";
            if (alt.isBlank() && nm.find() && !nm.group(1).toLowerCase(Locale.ROOT).matches("picture \\d+|image \\d+|graphic \\d+")) alt = Entities.decode(nm.group(1));
            n++;
            Images.Kept kept = k.keep(b, path, alt.strip(), "");
            if (kept == null) continue;
            lines.append("![").append(kept.found().title().replace("]", ")")).append("](").append(kept.url()).append(")\n\n");
        }
        if (k.kept.isEmpty()) return Outcome.none(text);
        return new Outcome(text.stripTrailing() + "\n\n" + HEADING + "\n\n" + lines.toString().strip() + "\n", k.kept.size(), k.described, List.copyOf(k.kept));
    }
}
