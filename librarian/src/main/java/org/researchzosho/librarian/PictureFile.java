package org.researchzosho.librarian;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/**
 * What a picture file says about itself, read from its bytes by the program (0.8.0, picture collections): its width and height as shown
 * (a JPEG's EXIF orientation turns a 4000×3000 file into a 3000×4000 picture), its format, and whether the file declares it was made by
 * an image generator. The size is read from the first bytes where the format puts it, so a picture too small for what was asked is known
 * before the rest of it is downloaded. Never what a site claims: a page called "HD 4K wallpaper" served a 1600×844 file (2026-11-19).
 *
 * <p>The declaration is what generators and the standards write into the file: the IPTC digital source type trainedAlgorithmicMedia (in
 * XMP, and inside a C2PA manifest), the name of a generator as the program that made it (EXIF Software, XMP CreatorTool, a C2PA claim
 * generator), and the settings Stable Diffusion's tools and ComfyUI leave in a PNG's text. It is read, never guessed: a file that says
 * nothing is not called made by a person either.
 */
final class PictureFile {
    private PictureFile() { }

    /** A picture's own facts: width and height as shown (0 when the bytes at hand do not say), the format's extension ("" unknown), and what the file declares. */
    record Facts(int width, int height, String format, Declared declared) {
        boolean sized() { return width > 0 && height > 0; }
    }

    /** What a file declares about how it was made: whether it says an image generator made it, and the words that say so. */
    record Declared(boolean ai, String why) {
        static final Declared NOTHING = new Declared(false, "");
    }

    /** The facts of these bytes (the whole file or its first part). */
    static Facts read(byte[] b) {
        if (b == null || b.length < 12) return new Facts(0, 0, "", Declared.NOTHING);
        String ext = Images.extension("", b);
        if (ext == null) ext = "";
        Meta meta = new Meta();
        int[] wh = switch (ext) {
            case "jpg" -> jpeg(b, meta);
            case "png" -> png(b, meta);
            case "gif" -> b.length >= 10 ? new int[]{u16le(b, 6), u16le(b, 8)} : null;
            case "webp" -> webp(b, meta);
            case "bmp" -> b.length >= 26 ? new int[]{Math.abs(i32le(b, 18)), Math.abs(i32le(b, 22))} : null;
            case "avif" -> ispe(b);
            case "tif" -> { int[] d = Images.dimensions(b); yield d[0] > 0 ? d : null; }
            default -> null;
        };
        if (wh == null) wh = new int[]{0, 0};
        if (meta.orientation >= 5 && meta.orientation <= 8) wh = new int[]{wh[1], wh[0]};   // turned a quarter: shown the other way round
        return new Facts(wh[0], wh[1], ext, declared(meta));
    }

    /** The EXIF orientation of a JPEG (1 when it says none): how the picture is turned when shown. */
    static int orientation(byte[] b) {
        Meta m = new Meta();
        if (b != null && b.length > 4 && "jpg".equals(Images.extension("", b))) jpeg(b, m);
        return m.orientation;
    }

    // ---- what the file declares ----

    /** The generators' names as they write them where a file names the program that made it. */
    static final Pattern GENERATOR = Pattern.compile("(?i)\\b(midjourney|dall[·\\-. ]?e|stable[ -]?diffusion|novelai|adobe firefly|firefly|imagen|leonardo\\.?ai|comfyui|automatic1111|invokeai|bing image creator|image creator from microsoft designer|chatgpt|openai|gpt-4o|google gemini|ideogram|black forest labs)\\b");
    /** The IPTC digital source types that say an AI made the picture, or made part of it. */
    static final Pattern SOURCE_TYPE = Pattern.compile("(?i)(compositeWithTrainedAlgorithmicMedia|trainedAlgorithmicMedia)");
    static final Pattern CREATOR_TOOL = Pattern.compile("(?i)CreatorTool(?:=\"|>)([^\"<]{1,160})");
    static final Pattern CLAIM_GENERATOR = Pattern.compile("(?i)claim_generator[^A-Za-z0-9]{1,12}([ -~]{1,120})");

    /** The metadata gathered while reading the file: XMP packets, PNG text, EXIF Software, C2PA bytes, the orientation. */
    static final class Meta {
        int orientation = 1;
        final StringBuilder xmp = new StringBuilder(), text = new StringBuilder(), c2pa = new StringBuilder();
        String software = "";
    }

    static Declared declared(Meta m) {
        Matcher st = SOURCE_TYPE.matcher(m.xmp + " " + m.c2pa);
        if (st.find()) return new Declared(true, st.group(1).toLowerCase(Locale.ROOT).startsWith("composite")
                ? "the file says it was made partly by AI (IPTC digital source type " + st.group(1) + ")"
                : "the file says it was made by AI (IPTC digital source type " + st.group(1) + ")");
        Matcher g = GENERATOR.matcher(m.software);
        if (!m.software.isBlank() && g.find()) return new Declared(true, "the file names " + m.software.strip() + " as the program that made it");
        Matcher ct = CREATOR_TOOL.matcher(m.xmp);
        while (ct.find()) { if (GENERATOR.matcher(ct.group(1)).find()) return new Declared(true, "the file names " + ct.group(1).strip() + " as the program that made it"); }
        Matcher cg = CLAIM_GENERATOR.matcher(m.c2pa);
        while (cg.find()) { Matcher gg = GENERATOR.matcher(cg.group(1)); if (gg.find()) return new Declared(true, "the file's content credentials (C2PA) name " + gg.group(1) + " as what made it"); }
        String t = m.text.toString();
        if (t.contains("\nparameters\t") && Pattern.compile("(?i)\\b(steps|sampler|cfg scale|seed)\\s*:").matcher(t).find()) return new Declared(true, "the file carries an image generator's settings (the steps, sampler and seed Stable Diffusion's tools write)");
        if ((t.contains("\nprompt\t") || t.contains("\nworkflow\t")) && t.contains("class_type")) return new Declared(true, "the file carries an image generator's workflow (ComfyUI)");
        Matcher sw = Pattern.compile("\nSoftware\t([^\n]{1,120})").matcher(t);
        if (sw.find() && GENERATOR.matcher(sw.group(1)).find()) return new Declared(true, "the file names " + sw.group(1).strip() + " as the program that made it");
        return Declared.NOTHING;
    }

    // ---- JPEG ----

    static int[] jpeg(byte[] b, Meta meta) {
        int i = 2, n = b.length;
        int[] size = null;
        while (i + 4 <= n) {
            if ((b[i] & 0xff) != 0xff) { i++; continue; }
            int marker = b[i + 1] & 0xff;
            if (marker == 0xff) { i++; continue; }
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) { i += 2; continue; }
            if (marker == 0xd9 || marker == 0xda) break;   // the end, or the start of the picture's data: the segments that describe it come before
            int len = u16be(b, i + 2);
            if (len < 2) break;
            int start = i + 4, end = Math.min(n, i + 2 + len);
            boolean sof = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
            if (sof && start + 5 <= n) size = new int[]{u16be(b, start + 3), u16be(b, start + 1)};
            else if (marker == 0xe1 && end > start) app1(b, start, end, meta);
            else if (marker == 0xeb && end > start) meta.c2pa.append(latin(b, start, end));   // APP11: JUMBF, where C2PA puts its manifest
            i = i + 2 + len;
        }
        return size;
    }

    private static void app1(byte[] b, int start, int end, Meta meta) {
        if (startsWith(b, start, "Exif\0\0")) exif(b, start + 6, end, meta);
        else if (startsWith(b, start, "http://ns.adobe.com/xap/1.0/")) meta.xmp.append(new String(b, start, end - start, StandardCharsets.UTF_8));
    }

    /** A TIFF structure (EXIF's): the orientation (0x0112) and the software (0x0131) of the first directory. */
    static void exif(byte[] b, int t, int end, Meta meta) {
        if (t + 8 > end) return;
        boolean le = b[t] == 'I' && b[t + 1] == 'I';
        if (!le && !(b[t] == 'M' && b[t + 1] == 'M')) return;
        int ifd = t + i32(b, t + 4, le);
        if (ifd < t || ifd + 2 > end) return;
        int count = u16(b, ifd, le);
        for (int k = 0; k < count; k++) {
            int e = ifd + 2 + k * 12;
            if (e + 12 > end) break;
            int tag = u16(b, e, le), type = u16(b, e + 2, le), cnt = i32(b, e + 4, le);
            if (tag == 0x0112 && type == 3) meta.orientation = u16(b, e + 8, le);
            if (tag == 0x0131 && type == 2 && cnt > 0 && cnt < 256) {
                int at = cnt <= 4 ? e + 8 : t + i32(b, e + 8, le);
                if (at >= t && at + cnt <= end) meta.software = latin(b, at, at + cnt).replace("\0", "").strip();
            }
        }
    }

    // ---- PNG ----

    static int[] png(byte[] b, Meta meta) {
        if (b.length < 24) return null;
        int[] size = new int[]{i32(b, 16, false), i32(b, 20, false)};
        int i = 8;
        while (i + 8 <= b.length) {
            int len = i32(b, i, false);
            if (len < 0) break;
            String type = latin(b, i + 4, i + 8);
            int start = i + 8, end = start + len;
            if (end > b.length) { if (!type.equals("IDAT")) end = b.length; else break; }
            switch (type) {
                case "tEXt" -> { int z = zero(b, start, end); if (z > 0) meta.text.append('\n').append(latin(b, start, z)).append('\t').append(latin(b, z + 1, end)); }
                case "zTXt" -> { int z = zero(b, start, end); if (z > 0 && z + 2 <= end) meta.text.append('\n').append(latin(b, start, z)).append('\t').append(inflate(b, z + 2, end)); }
                case "iTXt" -> itxt(b, start, end, meta);
                case "caBX" -> meta.c2pa.append(latin(b, start, end));   // C2PA's chunk in a PNG
                case "IEND" -> { return size; }
                default -> { }
            }
            i = end + 4;   // the chunk's CRC
        }
        return size;
    }

    private static void itxt(byte[] b, int start, int end, Meta meta) {
        int z = zero(b, start, end);
        if (z < 0 || z + 3 > end) return;
        String key = latin(b, start, z);
        boolean compressed = b[z + 1] == 1;
        int lang = zero(b, z + 3, end); if (lang < 0) return;
        int trans = zero(b, lang + 1, end); if (trans < 0) return;
        String text = compressed ? inflate(b, trans + 1, end) : new String(b, trans + 1, end - trans - 1, StandardCharsets.UTF_8);
        if (key.equals("XML:com.adobe.xmp")) meta.xmp.append(text);
        else meta.text.append('\n').append(key).append('\t').append(text);
    }

    // ---- WebP, AVIF ----

    static int[] webp(byte[] b, Meta meta) {
        if (b.length < 30) return null;
        String chunk = latin(b, 12, 16);
        int[] size = switch (chunk) {
            case "VP8 " -> new int[]{u16le(b, 26) & 0x3fff, u16le(b, 28) & 0x3fff};
            case "VP8L" -> { int v = i32le(b, 21); yield new int[]{(v & 0x3fff) + 1, ((v >> 14) & 0x3fff) + 1}; }
            case "VP8X" -> new int[]{u24le(b, 24) + 1, u24le(b, 27) + 1};
            default -> null;
        };
        int i = 12;
        while (i + 8 <= b.length) {   // the chunks after: XMP and C2PA
            String type = latin(b, i, i + 4);
            int len = i32le(b, i + 4);
            if (len < 0) break;
            int start = i + 8, end = (int) Math.min((long) b.length, (long) start + len);
            if (type.equals("XMP ")) meta.xmp.append(new String(b, start, end - start, StandardCharsets.UTF_8));
            if (type.equals("C2PA")) meta.c2pa.append(latin(b, start, end));
            i = start + len + (len & 1);
        }
        return size;
    }

    /** AVIF and HEIF: the first image spatial extent ('ispe') box. */
    static int[] ispe(byte[] b) {
        for (int i = 4; i + 16 <= b.length; i++) {
            if (b[i] == 'i' && b[i + 1] == 's' && b[i + 2] == 'p' && b[i + 3] == 'e') return new int[]{i32(b, i + 8, false), i32(b, i + 12, false)};
        }
        return null;
    }

    // ---- bytes ----

    private static boolean startsWith(byte[] b, int at, String s) {
        byte[] p = s.getBytes(StandardCharsets.ISO_8859_1);
        if (at + p.length > b.length) return false;
        for (int k = 0; k < p.length; k++) if (b[at + k] != p[k]) return false;
        return true;
    }
    private static int zero(byte[] b, int from, int to) { for (int k = from; k < to; k++) if (b[k] == 0) return k; return -1; }
    private static String latin(byte[] b, int from, int to) { return from >= to ? "" : new String(b, from, to - from, StandardCharsets.ISO_8859_1); }
    private static String inflate(byte[] b, int from, int to) {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(b, from, Math.max(0, to - from)))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
    static int u16be(byte[] b, int i) { return i + 1 < b.length ? ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff) : 0; }
    static int u16le(byte[] b, int i) { return i + 1 < b.length ? ((b[i + 1] & 0xff) << 8) | (b[i] & 0xff) : 0; }
    static int u24le(byte[] b, int i) { return i + 2 < b.length ? ((b[i + 2] & 0xff) << 16) | ((b[i + 1] & 0xff) << 8) | (b[i] & 0xff) : 0; }
    static int i32le(byte[] b, int i) { return i + 3 < b.length ? ((b[i + 3] & 0xff) << 24) | ((b[i + 2] & 0xff) << 16) | ((b[i + 1] & 0xff) << 8) | (b[i] & 0xff) : 0; }
    static int u16(byte[] b, int i, boolean le) { return le ? u16le(b, i) : u16be(b, i); }
    static int i32(byte[] b, int i, boolean le) { return le ? i32le(b, i) : (i + 3 < b.length ? ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff) : 0); }
}
