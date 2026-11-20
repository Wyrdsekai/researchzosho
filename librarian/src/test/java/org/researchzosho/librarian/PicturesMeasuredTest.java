package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the program reads from a picture's own bytes (0.8.0, collections): its size as shown, read from the first bytes where the format
 * keeps it and turned as its EXIF orientation says; its format; what the file declares about AI, in the tags generators and the standards
 * write; and whether two pictures are one picture by the perceptual hash, through the resizes, crops, recolourings and mirrorings that
 * copies go through, while different pictures stay apart.
 */
class PicturesMeasuredTest {

    // ---- pictures made for the test ----

    /** A made picture: a background from one seed and shapes from another, so two pictures can share a background and differ in what stands on it. */
    static BufferedImage art(long background, long shapes, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Random rb = new Random(background), rs = new Random(shapes);
        g.setPaint(new GradientPaint(0, 0, new Color(rb.nextInt(256), rb.nextInt(256), rb.nextInt(256)), w, h, new Color(rb.nextInt(256), rb.nextInt(256), rb.nextInt(256))));
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < 18; i++) {
            g.setColor(new Color(rs.nextInt(256), rs.nextInt(256), rs.nextInt(256)));
            int x = rs.nextInt(w), y = rs.nextInt(h), sw = w / 12 + rs.nextInt(w / 4), sh = h / 12 + rs.nextInt(h / 4);
            switch (rs.nextInt(3)) {
                case 0 -> g.fillRect(x - sw / 2, y - sh / 2, sw, sh);
                case 1 -> g.fillOval(x - sw / 2, y - sh / 2, sw, sh);
                default -> { g.setStroke(new BasicStroke(Math.max(2, w / 80f))); g.drawLine(x, y, rs.nextInt(w), rs.nextInt(h)); }
            }
        }
        g.dispose();
        return img;
    }

    static byte[] jpg(BufferedImage img, float quality) {
        try {
            ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream mo = new MemoryCacheImageOutputStream(out)) {
                w.setOutput(mo); ImageWriteParam p = w.getDefaultWriteParam(); p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); p.setCompressionQuality(quality);
                w.write(null, new IIOImage(img, null, null), p);
            } finally { w.dispose(); }
            return out.toByteArray();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    static byte[] encoded(BufferedImage img, String format) { try { ByteArrayOutputStream out = new ByteArrayOutputStream(); assertTrue(ImageIO.write(img, format, out), format); return out.toByteArray(); } catch (Exception e) { throw new AssertionError(e); } }

    static BufferedImage crop(BufferedImage img, int x, int y, int w, int h) { BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); Graphics2D g = o.createGraphics(); g.drawImage(img, -x, -y, null); g.dispose(); return o; }
    static BufferedImage resized(BufferedImage img, int w, int h) { return PictureHash.scaled(img, Math.max(w, h)); }
    static BufferedImage mirrored(BufferedImage img) { BufferedImage o = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB); Graphics2D g = o.createGraphics(); g.drawImage(img, img.getWidth(), 0, -img.getWidth(), img.getHeight(), null); g.dispose(); return o; }
    static BufferedImage grey(BufferedImage img) { BufferedImage o = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY); Graphics2D g = o.createGraphics(); g.drawImage(img, 0, 0, null); g.dispose(); return PictureHash.scaled(o, Math.max(o.getWidth(), o.getHeight())); }
    static BufferedImage brighter(BufferedImage img, float f) {
        BufferedImage o = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) { int c = img.getRGB(x, y); int r = Math.min(255, (int) (((c >> 16) & 0xff) * f)), gg = Math.min(255, (int) (((c >> 8) & 0xff) * f)), b = Math.min(255, (int) ((c & 0xff) * f)); o.setRGB(x, y, (r << 16) | (gg << 8) | b); }
        return o;
    }

    /** The picture set in plain bands of a colour on every side, {@code band} of its size: a lantern slide's black mask, a print's mat. */
    static BufferedImage framed(BufferedImage img, Color colour, double band) {
        int bw = (int) (img.getWidth() * band / 2), bh = (int) (img.getHeight() * band / 2);
        BufferedImage o = new BufferedImage(img.getWidth() + 2 * bw, img.getHeight() + 2 * bh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics(); g.setColor(colour); g.fillRect(0, 0, o.getWidth(), o.getHeight()); g.drawImage(img, bw, bh, null); g.dispose();
        return o;
    }

    /** A JPEG with one more segment just after its start: an EXIF or XMP block as a camera or an editor writes it. */
    static byte[] withSegment(byte[] jpeg, int marker, byte[] payload) {
        byte[] out = new byte[jpeg.length + 4 + payload.length];
        out[0] = (byte) 0xff; out[1] = (byte) 0xd8;
        out[2] = (byte) 0xff; out[3] = (byte) marker; out[4] = (byte) ((payload.length + 2) >> 8); out[5] = (byte) (payload.length + 2);
        System.arraycopy(payload, 0, out, 6, payload.length);
        System.arraycopy(jpeg, 2, out, 6 + payload.length, jpeg.length - 2);
        return out;
    }

    /** EXIF with one directory: the orientation, and the software when given. */
    static byte[] exif(int orientation, String software) {
        byte[] sw = software == null ? new byte[0] : (software + "\0").getBytes(StandardCharsets.ISO_8859_1);
        int entries = software == null ? 1 : 2;
        int ifdSize = 2 + entries * 12 + 4;
        ByteBuffer b = ByteBuffer.allocate(6 + 8 + ifdSize + sw.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        b.putShort((short) entries);
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        if (software != null) b.putShort((short) 0x0131).putShort((short) 2).putInt(sw.length).putInt(8 + ifdSize);
        b.putInt(0);
        b.put(sw);
        return b.array();
    }

    static byte[] xmp(String inner) { return ("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description " + inner + "/></rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8); }

    /** A PNG with a text chunk put in just after its header chunk, as image generators write their settings. */
    static byte[] pngWithText(byte[] png, String keyword, String text) {
        byte[] data = (keyword + "\0" + text).getBytes(StandardCharsets.ISO_8859_1), type = "tEXt".getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32(); crc.update(type); crc.update(data);
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length); chunk.putInt(data.length).put(type).put(data).putInt((int) crc.getValue());
        int at = 8 + 25;   // the signature and IHDR
        byte[] out = new byte[png.length + chunk.capacity()];
        System.arraycopy(png, 0, out, 0, at); System.arraycopy(chunk.array(), 0, out, at, chunk.capacity()); System.arraycopy(png, at, out, at + chunk.capacity(), png.length - at);
        return out;
    }

    // ---- the size and the format ----

    @Test void theSizeIsReadFromThePicturesOwnFirstBytesAndTurnedAsItsExifSays() {
        BufferedImage wide = art(1, 2, 1600, 844);
        byte[] j = jpg(wide, 0.9f);
        PictureFile.Facts f = PictureFile.read(j);
        assertEquals(1600, f.width()); assertEquals(844, f.height()); assertEquals("jpg", f.format());
        assertTrue(PictureFile.read(Arrays.copyOf(j, 4096)).sized(), "the size is in the first bytes: a picture too small is known before the rest comes");
        assertEquals(1600, PictureFile.read(Arrays.copyOf(j, 4096)).width());
        // a phone's photograph: stored 1600×844 and shown turned a quarter, so it is a vertical picture
        byte[] turned = withSegment(j, 0xe1, exif(6, null));
        assertEquals(844, PictureFile.read(turned).width()); assertEquals(1600, PictureFile.read(turned).height());
        assertArrayEquals(new int[]{1600, 844}, Images.dimensions(turned), "control: the reader that ignores the orientation measures it the other way round");
        assertEquals(6, PictureFile.orientation(turned));
        BufferedImage shown = PictureHash.small(turned, 400);
        assertNotNull(shown); assertTrue(shown.getHeight() > shown.getWidth(), "the small copy is turned too: " + shown.getWidth() + "×" + shown.getHeight());
        for (String fmt : new String[]{"png", "gif", "bmp"}) {
            PictureFile.Facts g = PictureFile.read(encoded(art(3, 4, 640, 480), fmt));
            assertEquals(640, g.width(), fmt); assertEquals(480, g.height(), fmt);
        }
        // WebP: its size from the extended header, before any picture data has come
        ByteBuffer webp = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        webp.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(22).put("WEBP".getBytes(StandardCharsets.ISO_8859_1)).put("VP8X".getBytes(StandardCharsets.ISO_8859_1)).putInt(10).putInt(0);
        int w1 = 3840 - 1, h1 = 2160 - 1;
        webp.put((byte) w1).put((byte) (w1 >> 8)).put((byte) (w1 >> 16)).put((byte) h1).put((byte) (h1 >> 8)).put((byte) (h1 >> 16));
        PictureFile.Facts wp = PictureFile.read(webp.array());
        assertEquals("webp", wp.format()); assertEquals(3840, wp.width()); assertEquals(2160, wp.height());
        assertNull(PictureHash.small(webp.array(), 200), "a header with no picture data in it is not opened as a blank picture (WebP itself opens since 0.8.0: CollectionTest)");
    }

    // ---- what a file declares ----

    @Test void whatAFileDeclaresAboutAIIsReadFromTheTagsGeneratorsWrite() {
        byte[] plain = jpg(art(5, 6, 320, 200), 0.8f);
        assertFalse(PictureFile.read(plain).declared().ai(), "control: a file that says nothing is not called made by AI");
        PictureFile.Declared iptc = PictureFile.read(withSegment(plain, 0xe1, xmp("xmlns:Iptc4xmpExt=\"http://iptc.org/std/Iptc4xmpExt/2008-02-29/\" Iptc4xmpExt:DigitalSourceType=\"http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia\""))).declared();
        assertTrue(iptc.ai()); assertTrue(iptc.why().contains("trainedAlgorithmicMedia"), iptc.why());
        PictureFile.Declared composite = PictureFile.read(withSegment(plain, 0xe1, xmp("Iptc4xmpExt:DigitalSourceType=\"http://cv.iptc.org/newscodes/digitalsourcetype/compositeWithTrainedAlgorithmicMedia\""))).declared();
        assertTrue(composite.ai()); assertTrue(composite.why().contains("partly"), composite.why());
        assertTrue(PictureFile.read(withSegment(plain, 0xe1, xmp("xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:CreatorTool=\"Midjourney v6\""))).declared().ai(), "a generator named as the program that made it");
        assertFalse(PictureFile.read(withSegment(plain, 0xe1, xmp("xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:CreatorTool=\"Adobe Photoshop 25.0 (Windows)\""))).declared().ai(), "control: an editor is not a generator");
        PictureFile.Declared sw = PictureFile.read(withSegment(plain, 0xe1, exif(1, "NovelAI"))).declared();
        assertTrue(sw.ai(), "EXIF Software naming a generator"); assertTrue(sw.why().contains("NovelAI"), sw.why());
        assertFalse(PictureFile.read(withSegment(plain, 0xe1, exif(1, "GIMP 2.10"))).declared().ai(), "control: EXIF Software naming an editor");
        byte[] png = encoded(art(5, 6, 320, 200), "png");
        assertTrue(PictureFile.read(pngWithText(png, "parameters", "a lighthouse at dusk\nNegative prompt: blurry\nSteps: 28, Sampler: DPM++ 2M, CFG scale: 6, Seed: 4021")).declared().ai(), "Stable Diffusion's settings in a PNG");
        assertTrue(PictureFile.read(pngWithText(png, "prompt", "{\"3\": {\"class_type\": \"KSampler\", \"inputs\": {}}}")).declared().ai(), "ComfyUI's workflow in a PNG");
        assertFalse(PictureFile.read(pngWithText(png, "parameters", "exposure 1/250, f/8")).declared().ai(), "control: a text called parameters with no generator's settings");
        assertFalse(PictureFile.read(pngWithText(png, "Comment", "Taken at the harbour, 1987")).declared().ai(), "control: an ordinary comment");
    }

    // ---- one picture or two ----

    /** The distances between a picture and its copies, and between it and different pictures, by the hash the job uses. */
    @Test void aCopyResizedCroppedRecolouredOrMirroredIsOnePictureAndDifferentPicturesStayApart() {
        BufferedImage a = art(11, 12, 1920, 1080);
        PictureHash.Print pa = PictureHash.print(PictureHash.small(jpg(a, 0.92f), 1024));
        String[] names = {"resized to half", "recompressed hard", "cropped to 16:10 in the middle", "cropped to 16:10 at the left", "zoomed into its middle", "grey", "mirrored", "brighter", "cropped to 4:3", "in a black mask", "on a white mat"};
        BufferedImage[] copies = {resized(a, 960, 540), a, crop(a, 96, 0, 1728, 1080), crop(a, 0, 0, 1728, 1080), resized(crop(a, 144, 81, 1632, 918), 1632, 918), grey(a), mirrored(a), brighter(a, 1.15f), crop(a, 240, 0, 1440, 1080), framed(a, Color.BLACK, 0.35), framed(a, new Color(245, 243, 238), 0.15)};
        float[] quality = {0.9f, 0.3f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f};
        StringBuilder said = new StringBuilder();
        for (int i = 0; i < copies.length; i++) {
            PictureHash.Print pc = PictureHash.print(PictureHash.small(jpg(copies[i], quality[i]), 1024));
            int d = PictureHash.distance(pa, pc);
            said.append(names[i]).append(' ').append(d).append("; ");
            assertTrue(d <= PictureHash.SAME, names[i] + " is the same picture: " + d + " bits differ (" + said + ")");
        }
        int worstDifferent = 64;
        for (long s = 20; s < 40; s++) {
            int d1 = PictureHash.distance(pa, PictureHash.print(PictureHash.small(jpg(art(s, s + 100, 1920, 1080), 0.9f), 1024)));
            int d2 = PictureHash.distance(pa, PictureHash.print(PictureHash.small(jpg(art(11, s + 200, 1920, 1080), 0.9f), 1024)));   // the same background, other shapes on it
            worstDifferent = Math.min(worstDifferent, Math.min(d1, d2));
            assertTrue(d1 > PictureHash.SAME && d2 > PictureHash.SAME, "a different picture stays apart: " + d1 + ", " + d2);
        }
        System.out.println("[pictures] copies: " + said + "nearest different picture: " + worstDifferent + " bits");
        // control: the whole picture's hash alone does not see a crop to another shape, or a zoom: the hashes of the crops are what does
        PictureHash.Print cropped = PictureHash.print(PictureHash.small(jpg(copies[2], 0.9f), 1024)), zoomed = PictureHash.print(PictureHash.small(jpg(copies[4], 0.9f), 1024));
        assertTrue(Long.bitCount(pa.full() ^ cropped.full()) > PictureHash.SAME || Long.bitCount(pa.full() ^ zoomed.full()) > PictureHash.SAME,
                "control: whole against whole, " + Long.bitCount(pa.full() ^ cropped.full()) + " and " + Long.bitCount(pa.full() ^ zoomed.full()) + " bits");
        PictureHash.Print masked = PictureHash.print(PictureHash.small(jpg(copies[9], 0.9f), 1024));
        assertTrue(Long.bitCount(pa.full() ^ masked.full()) > PictureHash.SAME, "control: the mask hashed with the picture, " + Long.bitCount(pa.full() ^ masked.full()) + " bits; without its bands it is the picture");
    }
}
