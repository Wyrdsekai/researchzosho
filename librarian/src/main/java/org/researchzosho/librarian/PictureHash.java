package org.researchzosho.librarian;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Whether two pictures are the same picture (0.8.0, picture collections): a perceptual hash over a small grey copy, the program's
 * measure, so that the same photograph resized, recompressed, recoloured or mirrored counts once. Wallpaper sites and archives also crop
 * one picture to another shape (16:9, 16:10, 21:9, a phone's tall shape) and zoom into it, so a picture is hashed whole and in the crops
 * those habits make, and two pictures are compared over the crops that line them up ({@link #distance}). The distance is the number of
 * the 64 bits that differ; at or under {@link #SAME} the two are one picture. The threshold was set on a pinned set of pairs, never on
 * one ask ({@link #SAME}).
 *
 * <p>Also here: a picture decoded small without decoding it whole (a 7680×4320 file is read a pixel in eight), turned as its EXIF says,
 * and written as the small JPEG copy a collection keeps.
 */
final class PictureHash {
    private PictureHash() { }

    /**
     * At or under {@link #SURE} differing bits two pictures are one picture; over {@link #SAME} they are two; between, the model that sees
     * compares them. Calibrated 2026-11-19 on 64 public-domain pictures of eight subjects and 20 kinds of copy each (1,280 pairs) against
     * every pair of different pictures (2,016): resized, recompressed, recoloured, mirrored, captioned and watermarked copies measure 0,
     * crops to another shape 0 to 6, zooms to 85 and 70 % 2 to 8, a zoom to 60 % 16 or more (beyond; the likeness helper's embedding is
     * for those); different pictures of different subjects 12 or more. Different pictures with a plain ground and one figure on it come
     * closer: two photographs of a fox in snow measured 6 on a live run, which is why the middle is the model's.
     */
    static volatile int SURE = 2, SAME = 10;

    /** The shapes a picture is cropped to, width over height: the screens' and the prints'. */
    static final double[] ASPECTS = {21.0 / 9, 16.0 / 9, 16.0 / 10, 3.0 / 2, 4.0 / 3, 5.0 / 4, 1.0, 4.0 / 5, 3.0 / 4, 2.0 / 3, 10.0 / 16, 9.0 / 16, 9.0 / 21};

    // ---- reading a picture small ----

    /** The picture decoded at most {@code max} pixels on its long side, turned as its EXIF orientation says; null when the program cannot read its format (WebP, AVIF, a CMYK JPEG). */
    static BufferedImage small(byte[] bytes, int max) {
        if (bytes == null || bytes.length == 0) return null;
        if (webpWithoutPicture(bytes)) return null;   // the WebP reader draws a header with no picture data in it as a blank picture of that size
        BufferedImage img = null;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader r = readers.next();
            try {
                r.setInput(in, true, true);
                int w = r.getWidth(0), h = r.getHeight(0);
                int step = Math.max(1, Math.max(w, h) / (max * 2));   // decoded at twice the size wanted at least, then drawn down smoothly
                ImageReadParam p = r.getDefaultReadParam();
                if (step > 1) p.setSourceSubsampling(step, step, 0, 0);
                img = r.read(0, p);
            } finally { r.dispose(); }
        } catch (Exception | OutOfMemoryError e) { return null; }
        if (img == null) return null;
        img = scaled(img, max);
        return turned(img, PictureFile.orientation(bytes));
    }

    /** A WebP file (RIFF … WEBP) that holds no picture data: no VP8 or VP8L chunk anywhere in it, only a header, as a cut download can be. */
    static boolean webpWithoutPicture(byte[] b) {
        if (b.length < 12 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F' || b[8] != 'W' || b[9] != 'E' || b[10] != 'B' || b[11] != 'P') return false;
        for (int at = 12; at + 8 <= b.length; ) {
            int size = (b[at + 4] & 0xff) | (b[at + 5] & 0xff) << 8 | (b[at + 6] & 0xff) << 16 | (b[at + 7] & 0xff) << 24;
            if (b[at] == 'V' && b[at + 1] == 'P' && b[at + 2] == '8' && (b[at + 3] == ' ' || b[at + 3] == 'L')) return size <= 0 || at + 8 + Math.min(size, 16) > b.length;
            if (size < 0) return true;
            at += 8 + size + (size & 1);
        }
        return true;
    }

    /** The picture drawn at most {@code max} on its long side, in RGB on white (a transparent PNG's background), smoothly. */
    static BufferedImage scaled(BufferedImage src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        double f = Math.min(1.0, (double) max / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * f)), nh = Math.max(1, (int) Math.round(h * f));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, nw, nh);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // halving steps first, so a large reduction averages its pixels instead of skipping them
            BufferedImage cur = src;
            while (cur.getWidth() / 2 >= nw && cur.getHeight() / 2 >= nh && cur.getWidth() > 2 * nw) {
                BufferedImage half = new BufferedImage(Math.max(1, cur.getWidth() / 2), Math.max(1, cur.getHeight() / 2), BufferedImage.TYPE_INT_RGB);
                Graphics2D hg = half.createGraphics();
                hg.setColor(Color.WHITE); hg.fillRect(0, 0, half.getWidth(), half.getHeight());
                hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                hg.drawImage(cur, 0, 0, half.getWidth(), half.getHeight(), null); hg.dispose();
                cur = half;
            }
            g.drawImage(cur, 0, 0, nw, nh, null);
        } finally { g.dispose(); }
        return out;
    }

    /** The picture turned as an EXIF orientation (1 to 8) says it is shown. */
    static BufferedImage turned(BufferedImage img, int orientation) {
        if (orientation <= 1 || orientation > 8) return img;
        int w = img.getWidth(), h = img.getHeight();
        boolean swap = orientation >= 5;
        // each as the matrix (m00, m10, m01, m11, m02, m12) that puts a pixel where the orientation shows it
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // mirrored
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // upside down
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // mirrored upside down
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // mirrored and turned
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // turned a quarter clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // mirrored and turned the other way
            default -> new AffineTransform(0, -1, 1, 0, 0, w);  // 8: turned a quarter anticlockwise
        };
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try { g.drawImage(img, t, null); } finally { g.dispose(); }
        return out;
    }

    /** The picture as a JPEG at most {@code max} on its long side: the small copy a collection keeps, and what the seeing model is shown. */
    static byte[] jpeg(BufferedImage img, int max, float quality) {
        BufferedImage s = img.getWidth() > max || img.getHeight() > max ? scaled(img, max) : img;
        if (s.getType() != BufferedImage.TYPE_INT_RGB) s = scaled(s, Math.max(s.getWidth(), s.getHeight()));
        Iterator<ImageWriter> ws = ImageIO.getImageWritersByFormatName("jpeg");
        if (!ws.hasNext()) return null;
        ImageWriter w = ws.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream mo = new MemoryCacheImageOutputStream(out)) {
            w.setOutput(mo);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); p.setCompressionQuality(quality);
            w.write(null, new IIOImage(s, null, null), p);
        } catch (IOException e) { return null; }
        finally { w.dispose(); }
        return out.toByteArray();
    }

    // ---- the hash ----

    /** A picture's grey copy at most 256 on its long side, as rows of brightness. */
    static float[][] grey(BufferedImage img) {
        BufferedImage s = img.getWidth() > 256 || img.getHeight() > 256 ? scaled(img, 256) : img;
        int w = s.getWidth(), h = s.getHeight();
        float[][] g = new float[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { int rgb = s.getRGB(x, y); g[y][x] = 0.299f * ((rgb >> 16) & 0xff) + 0.587f * ((rgb >> 8) & 0xff) + 0.114f * (rgb & 0xff); }
        return g;
    }

    /** The 64-bit DCT hash of a region of a grey copy: averaged down to 32×32, the 8×8 lowest frequencies, each bit whether it stands above their median. */
    static long hash(float[][] g, double x0, double y0, double x1, double y1) {
        int h = g.length, w = g[0].length;
        float[][] s = new float[32][32];
        for (int j = 0; j < 32; j++) {
            for (int i = 0; i < 32; i++) {
                double ax = x0 + (x1 - x0) * i / 32.0, bx = x0 + (x1 - x0) * (i + 1) / 32.0, ay = y0 + (y1 - y0) * j / 32.0, by = y0 + (y1 - y0) * (j + 1) / 32.0;
                int xa = Math.max(0, (int) Math.floor(ax)), xb = Math.min(w, Math.max(xa + 1, (int) Math.ceil(bx))), ya = Math.max(0, (int) Math.floor(ay)), yb = Math.min(h, Math.max(ya + 1, (int) Math.ceil(by)));
                double sum = 0; int n = 0;
                for (int y = ya; y < yb; y++) for (int x = xa; x < xb; x++) { sum += g[y][x]; n++; }
                s[j][i] = n == 0 ? 0 : (float) (sum / n);
            }
        }
        double[] coef = new double[64];
        for (int v = 0; v < 8; v++) {
            for (int u = 0; u < 8; u++) {
                double sum = 0;
                for (int y = 0; y < 32; y++) { double cy = COS[v][y]; for (int x = 0; x < 32; x++) sum += s[y][x] * COS[u][x] * cy; }
                coef[v * 8 + u] = sum;
            }
        }
        double[] ac = new double[63];
        System.arraycopy(coef, 1, ac, 0, 63);
        Arrays.sort(ac);
        double median = ac[31];
        long bits = 0;
        for (int k = 1; k < 64; k++) if (coef[k] > median) bits |= 1L << k;
        return bits;
    }

    private static final double[][] COS = new double[8][32];
    static { for (int u = 0; u < 8; u++) for (int x = 0; x < 32; x++) COS[u][x] = Math.cos((2 * x + 1) * u * Math.PI / 64.0); }

    /** A picture's hashes: whole ("full"), mirrored ("flip"), zoomed into its centre ("z85", "z70"), and cropped to each shape narrower or wider than its own at the start, the middle and the end ("a1.600@0.5"). */
    record Print(Map<String, Long> hashes, double aspect) {
        long full() { return hashes.get("full"); }
    }

    static Print print(BufferedImage img) { return print(grey(img)); }

    static Print print(float[][] g) {
        float[][] t = trimmed(g);
        int h = g.length, w = g[0].length;
        double aspect = (double) w / h;
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("full", hash(g, 0, 0, w, h));
        if (t != g) m.put("trim", hash(t, 0, 0, t[0].length, t.length));   // without its plain bands: matched against the other's whole, or its own
        m.put("flip", hash(flipped(g), 0, 0, w, h));
        for (double z : new double[]{0.85, 0.7}) { double cw = w * z, ch = h * z; m.put("z" + Math.round(z * 100), hash(g, (w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2)); }
        for (double a : ASPECTS) {
            if (Math.abs(a - aspect) / Math.max(a, aspect) < 0.03) continue;
            for (double off : new double[]{0, 0.5, 1}) {
                String key = String.format(Locale.ROOT, "a%.3f@%.1f", a, off);
                if (a < aspect) { double cw = h * a; double x0 = (w - cw) * off; m.put(key, hash(g, x0, 0, x0 + cw, h)); }
                else { double ch = w / a; double y0 = (h - ch) * off; m.put(key, hash(g, 0, y0, w, y0 + ch)); }
            }
        }
        return new Print(m, aspect);
    }

    /**
     * The grey copy without the plain bands at its edges: the mat of a scanned print, the black mask of a lantern slide, the bars that
     * letterbox a wallpaper. A row or a column whose brightness hardly varies is a band; up to 30 % is taken from each side. One
     * photograph, printed and as a slide in its black mask, was kept twice on 2026-11-19 because the mask was hashed with it.
     */
    static float[][] trimmed(float[][] g) {
        int h = g.length, w = g[0].length;
        int top = 0, bottom = h, left = 0, right = w, mostY = (int) (h * 0.3), mostX = (int) (w * 0.3);
        while (top < mostY && plain(g, top, true, left, right)) top++;
        while (h - bottom < mostY && bottom - 1 > top && plain(g, bottom - 1, true, left, right)) bottom--;
        while (left < mostX && plain(g, left, false, top, bottom)) left++;
        while (w - right < mostX && right - 1 > left && plain(g, right - 1, false, top, bottom)) right--;
        if (top == 0 && bottom == h && left == 0 && right == w) return g;
        if (bottom - top < 16 || right - left < 16) return g;
        float[][] t = new float[bottom - top][right - left];
        for (int y = top; y < bottom; y++) System.arraycopy(g[y], left, t[y - top], 0, right - left);
        return t;
    }

    /** Whether a row (or a column) of the grey copy is one plain band: its brightness varies by under 6 of 255. */
    private static boolean plain(float[][] g, int at, boolean row, int from, int to) {
        double sum = 0, sq = 0; int n = 0;
        for (int i = from; i < to; i++) { float v = row ? g[at][i] : g[i][at]; sum += v; sq += v * v; n++; }
        if (n == 0) return false;
        double mean = sum / n, var = sq / n - mean * mean;
        return var < 36;
    }

    private static float[][] flipped(float[][] g) {
        int h = g.length, w = g[0].length;
        float[][] f = new float[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) f[y][x] = g[y][w - 1 - x];
        return f;
    }

    /**
     * How far apart two pictures are, in differing bits: the least over the pairs that line one up with the other — the whole of one
     * against each of the other's hashes (one is the other resized, mirrored, zoomed, cropped to another shape, or set in a mat or bands),
     * a crop of each to the same shape at the same place (both are crops of a wider picture), and each without its plain bands.
     */
    static int distance(Print a, Print b) {
        int best = 64;
        long af = a.full(), bf = b.full();
        for (Map.Entry<String, Long> e : b.hashes().entrySet()) best = Math.min(best, Long.bitCount(af ^ e.getValue()));
        for (Map.Entry<String, Long> e : a.hashes().entrySet()) best = Math.min(best, Long.bitCount(bf ^ e.getValue()));
        for (Map.Entry<String, Long> e : a.hashes().entrySet()) {
            if (!e.getKey().startsWith("a") && !e.getKey().equals("trim")) continue;
            Long other = b.hashes().get(e.getKey());
            if (other != null) best = Math.min(best, Long.bitCount(e.getValue() ^ other));
        }
        return best;
    }

    /** "f0e1…" for the manifest. */
    static String hex(long h) { return String.format(Locale.ROOT, "%016x", h); }
}
