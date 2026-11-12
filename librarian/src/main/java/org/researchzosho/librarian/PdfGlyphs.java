package org.researchzosho.librarian;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The adapter between PDFBox and {@link PdfLayout} (0.7.3): each page's glyphs with the box of their em in the page as it is shown
 * (its rotation applied), their size, the way their line runs, their weight and whether they are drawn invisibly; and the boxes of the
 * pictures the page draws. The box is computed from the glyph's own text rendering matrix: for a font that writes vertically PDFBox
 * gives the glyph's horizontal origin, and its em square runs from there one em across and from 0.12 below to 0.88 above (measured
 * against the rendered page: a vertical paper's first character drawn at 175.8–185.0 pt across, where poppler's word box says
 * 180.4–189.6). Nothing here decides an order; nothing here needs a font the machine has.
 */
public final class PdfGlyphs {
    private PdfGlyphs() { }

    /** The pages of a document, from {@code first} to {@code last} (1-based, inclusive). */
    public static List<PdfLayout.PageIn> read(PDDocument doc, int first, int last) throws IOException {
        Collector c = new Collector();
        c.setStartPage(first); c.setEndPage(last);
        c.setSortByPosition(false);
        c.setSuppressDuplicateOverlappingText(false);
        c.writeText(doc, new StringWriter());
        List<PdfLayout.PageIn> out = new ArrayList<>();
        for (int i = 0; i < c.pages.size(); i++) {
            int number = first + i;
            Collector.PageGlyphs pg = c.pages.get(i);
            List<PdfLayout.Box> pictures = new ArrayList<>();
            try { pictures = pictures(doc.getPage(number - 1), pg.view); } catch (IOException | RuntimeException e) { /* the pictures only place transcriptions and tell a scan */ }
            out.add(new PdfLayout.PageIn(number, pg.view.width, pg.view.height, pg.glyphs, pictures));
        }
        return out;
    }

    /** How a page is shown: its crop box and its rotation, mapping a point of the page's user space to points from the shown page's top left. */
    record View(double llx, double lly, double urx, double ury, int rotation, double width, double height) {
        static View of(PDPage page) {
            PDRectangle box = page.getCropBox();
            int r = ((page.getRotation() % 360) + 360) % 360;
            double w = box.getWidth(), h = box.getHeight();
            return new View(box.getLowerLeftX(), box.getLowerLeftY(), box.getUpperRightX(), box.getUpperRightY(), r, r == 90 || r == 270 ? h : w, r == 90 || r == 270 ? w : h);
        }
        double[] map(double x, double y) {
            return switch (rotation) {
                case 90 -> new double[]{y - lly, x - llx};
                case 180 -> new double[]{urx - x, y - lly};
                case 270 -> new double[]{ury - y, urx - x};
                default -> new double[]{x - llx, ury - y};
            };
        }
    }

    private static final Pattern BOLD_NAME = Pattern.compile("(?i).*(bold|black|heavy|demi|semibold|extrabold|ultra|-medi$|-b$|,b$|w[6-9]\\b|w[6-9]$|futo|gothic-?b).*");

    /** The text stripper turned to collecting: every glyph with the matrix it is drawn with, no text written. */
    static final class Collector extends PDFTextStripper {
        static final class PageGlyphs { final View view; final List<PdfLayout.Glyph> glyphs = new ArrayList<>(); PageGlyphs(View v) { view = v; } }
        final List<PageGlyphs> pages = new ArrayList<>();
        private PageGlyphs current;
        private Matrix lastMatrix; private PDFont lastFont; private int lastCode; private Vector lastDisplacement; private boolean lastInvisible, lastStroked;

        Collector() throws IOException { super(); }

        @Override public void processPage(PDPage page) throws IOException {
            int n = getCurrentPageNo();
            if (n < getStartPage() || n > getEndPage()) { current = null; super.processPage(page); return; }
            current = new PageGlyphs(View.of(page));
            pages.add(current);
            super.processPage(page);
        }

        @Override protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
            lastMatrix = textRenderingMatrix.clone(); lastFont = font; lastCode = code; lastDisplacement = displacement;
            RenderingMode mode = getGraphicsState().getTextState().getRenderingMode();
            lastInvisible = mode == RenderingMode.NEITHER || mode == RenderingMode.NEITHER_CLIP;
            lastStroked = mode == RenderingMode.FILL_STROKE || mode == RenderingMode.FILL_STROKE_CLIP;
            super.showGlyph(textRenderingMatrix, font, code, displacement);
            // a glyph of a composite font with no character: PDFBox makes no text position for it; it is counted, never guessed
            if (!(font instanceof PDSimpleFont) && font.toUnicode(code) == null) add("\uFFFD");
        }

        /** The text stripper's own handling first (a span's ActualText given to its first glyph, the others emptied), then the glyph kept. */
        @Override protected void processTextPosition(TextPosition p) {
            super.processTextPosition(p);
            String text = p.getUnicode();
            if (text == null) text = "\uFFFD";
            if (text.isEmpty()) return;   // a glyph whose span's ActualText the first glyph carries
            add(text);
        }

        /** Nothing is written: the glyphs are what is wanted. */
        @Override protected void writePage() { }

        private void add(String text) {
            if (current == null || lastMatrix == null || lastFont == null) return;
            boolean vertical = lastFont.isVertical();
            double w = vertical ? widthOf(lastFont, lastCode) : Math.abs(lastDisplacement.getX());
            if (w <= 0) w = vertical ? 1.0 : 0.5;
            double down = vertical ? -0.12 : -0.2, up = vertical ? 0.88 : 0.8;
            double[][] corners = {{0, down}, {w, down}, {0, up}, {w, up}};
            double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            for (double[] c : corners) {
                Point2D.Float q = lastMatrix.transformPoint((float) c[0], (float) c[1]);
                double[] m = current.view.map(q.x, q.y);
                x0 = Math.min(x0, m[0]); y0 = Math.min(y0, m[1]); x1 = Math.max(x1, m[0]); y1 = Math.max(y1, m[1]);
            }
            // the axis: the way the glyph's advance runs on the shown page
            Point2D.Float o = lastMatrix.transformPoint(0, 0), adv = vertical ? lastMatrix.transformPoint(0, -1) : lastMatrix.transformPoint(1, 0);
            double[] mo = current.view.map(o.x, o.y), ma = current.view.map(adv.x, adv.y);
            // the size: the glyph's em as an area, so that a dash drawn narrow and long (a two-em dash scaled 6.45 by 13.82 in a 9.2-point
            // column) is of its column's size
            double size = Math.sqrt(Math.abs((double) lastMatrix.getScaleX() * lastMatrix.getScaleY() - (double) lastMatrix.getShearX() * lastMatrix.getShearY()));
            double dx = ma[0] - mo[0], dy = ma[1] - mo[1];
            PdfLayout.Axis axis = Math.abs(dx) >= Math.abs(dy) ? PdfLayout.Axis.H : dy > 0 ? PdfLayout.Axis.V : PdfLayout.Axis.U;
            if (size <= 0.01 || !Double.isFinite(size) || !Double.isFinite(x0)) return;
            current.glyphs.add(new PdfLayout.Glyph(text, x0, y0, x1, y1, size, axis, lastStroked || bold(lastFont), lastInvisible));
        }

        static double widthOf(PDFont f, int code) {
            try { double w = f.getWidth(code) / 1000.0; return w > 0.05 && w < 3 ? w : 1.0; } catch (IOException | RuntimeException e) { return 1.0; }
        }

        static boolean bold(PDFont f) {
            PDFontDescriptor d = f.getFontDescriptor();
            if (d != null && (d.isForceBold() || d.getFontWeight() >= 600)) return true;
            String n = f.getName();
            return n != null && BOLD_NAME.matcher(n.replaceFirst("^[A-Z]{6}\\+", "")).matches();
        }
    }

    /** The boxes of the pictures a page draws, its forms' included, in points of the shown page. */
    static List<PdfLayout.Box> pictures(PDPage page, View view) throws IOException {
        List<PdfLayout.Box> out = new ArrayList<>();
        PDFGraphicsStreamEngine e = new PDFGraphicsStreamEngine(page) {
            @Override public void drawImage(PDImage pdImage) {
                Matrix m = getGraphicsState().getCurrentTransformationMatrix();
                double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
                for (float[] c : new float[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}}) {
                    Point2D.Float q = m.transformPoint(c[0], c[1]);
                    double[] p = view.map(q.x, q.y);
                    x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]); x1 = Math.max(x1, p[0]); y1 = Math.max(y1, p[1]);
                }
                x0 = Math.max(0, x0); y0 = Math.max(0, y0); x1 = Math.min(view.width, x1); y1 = Math.min(view.height, y1);
                if (x1 > x0 && y1 > y0) out.add(new PdfLayout.Box(x0, y0, x1, y1));
            }
            @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { }
            @Override public void clip(int windingRule) { }
            @Override public void moveTo(float x, float y) { }
            @Override public void lineTo(float x, float y) { }
            @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { }
            @Override public Point2D getCurrentPoint() { return new Point2D.Float(0, 0); }
            @Override public void closePath() { }
            @Override public void endPath() { }
            @Override public void strokePath() { }
            @Override public void fillPath(int windingRule) { }
            @Override public void fillAndStrokePath(int windingRule) { }
            @Override public void shadingFill(COSName shadingName) { }
            @Override protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) { }
        };
        e.processPage(page);
        return out;
    }

    /** The filters of the pictures on a page that PDFBox cannot decode on this machine (JBIG2, JPEG 2000 without their ImageIO readers). */
    static Set<String> undecodable(PDPage page) {
        Set<String> out = new TreeSet<>();
        try {
            var res = page.getResources();
            if (res == null) return out;
            for (COSName n : res.getXObjectNames()) {
                if (!(res.getXObject(n) instanceof PDImageXObject img)) continue;
                List<COSName> filters = img.getStream().getFilters();
                for (COSName f : filters) {
                    if (f.equals(COSName.JBIG2_DECODE) && !ImageIO.getImageReadersByFormatName("JBIG2").hasNext()) out.add("JBIG2");
                    if (f.equals(COSName.JPX_DECODE) && !ImageIO.getImageReadersByFormatName("JPEG2000").hasNext()) out.add("JPEG 2000");
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return out;
    }

    /** The page drawn at {@code dpi}, in colour; null when it could not be drawn. */
    static BufferedImage render(PDDocument doc, int page, float dpi) {
        try { return new PDFRenderer(doc).renderImageWithDPI(page - 1, dpi, ImageType.RGB); }
        catch (IOException | RuntimeException | OutOfMemoryError e) { return null; }
    }

    /** The share of a page that is ink, from a small drawing of it: tells a page of outlined text or a drawing from an empty page. */
    static double ink(PDDocument doc, int page) {
        BufferedImage img;
        try { img = new PDFRenderer(doc).renderImageWithDPI(page - 1, 24, ImageType.GRAY); }
        catch (IOException | RuntimeException | OutOfMemoryError e) { return 0; }
        long dark = 0, all = (long) img.getWidth() * img.getHeight();
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) & 0xFF) < 160) dark++;
        return all == 0 ? 0 : dark / (double) all;
    }
}
