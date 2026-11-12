package org.researchzosho.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;


import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/** A picture is a document when the model reads pictures, and says plainly why it is not when the model does not. */
class ImageTextTest {

    @AfterEach void restore() { ImageText.use(null); }

    private static byte[] picture(String format, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, out));
        return out.toByteArray();
    }

    /** A PNG that says it is {@code w} by {@code h} pixels in its header, with nothing to decode behind it. */
    static byte[] pngHeader(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws Exception {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t); out.write(data);
        CRC32 crc = new CRC32(); crc.update(t); crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    @Test
    void aPictureThatSaysItIsBillionsOfPixelsIsRefusedBeforeItIsDecoded() throws Exception {
        byte[] bomb = pngHeader(100_000, 100_000);
        assertTrue(ImageText.isImage(bomb));
        ImageText.use((png, hint) -> fail("a picture that large is never read"));
        ImageText.Reading r = ImageText.reading(bomb, "placeholder.png");
        assertEquals("", r.text());
        assertTrue(r.why().contains("the picture says it is 100000 by 100000 pixels, more than the 250 million pixels this program opens"), r.why());
        assertNull(ImageText.childCheck(bomb), "the check does not open it either");
    }

    @Test
    void aLargePictureIsDecodedAtAFractionOfItsPixels() throws Exception {
        BufferedImage big = new BufferedImage(4100, 4100, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(big, "png", out));
        int[] sent = new int[2];
        ImageText.use((png, hint) -> {
            try { BufferedImage i = ImageIO.read(new ByteArrayInputStream(png)); sent[0] = i.getWidth(); sent[1] = i.getHeight(); } catch (Exception e) { fail(e); }
            return "PHOTO: a placeholder grey square";
        });
        assertTrue(ImageText.reading(out.toByteArray(), "big.png").text().startsWith("PHOTO:"));
        assertArrayEquals(new int[]{2050, 2050}, sent, "read every second pixel: more than sixteen million pixels");
    }

    @Test
    void aPictureBecomesItsWritingAndAHugeOneIsSentSmaller() throws Exception {
        int[] sent = new int[2]; String[] hinted = new String[1];
        ImageText.use((png, hint) -> {
            try { BufferedImage i = ImageIO.read(new ByteArrayInputStream(png)); sent[0] = i.getWidth(); sent[1] = i.getHeight(); } catch (Exception e) { fail(e); }
            if (hinted[0] == null) hinted[0] = hint;
            return "本籍 広島県佐伯郡\n戸主 髙橋源三郎\n明治五年□月三日生";
        });
        DocText.Doc d = DocText.convert(picture("jpg", 4800, 3000), "register.jpg");
        assertEquals("image", d.kind());
        assertTrue(d.text().contains("戸主 髙橋源三郎") && d.text().contains("□"), "an unread character stays a mark, not a guess");
        assertArrayEquals(new int[]{2400, 1500}, sent);
        assertEquals("register.jpg", hinted[0], "the model is told the file's name");
        for (String f : new String[]{"png", "gif", "bmp", "tiff"}) assertEquals("image", DocText.convert(picture(f, 40, 30), "x." + f).kind(), f);
    }

    @Test
    void aModelThatDoesNotReadPicturesIsSaidSo() throws Exception {
        ImageText.use((png, hint) -> "");
        DocText.Doc d = DocText.convert(picture("png", 40, 30), "photo.png");
        assertTrue(d.text().isEmpty() && d.kind().startsWith("image: the model did not read the picture") && d.kind().contains("--mmproj"), d.kind());
        ImageText.use((png, hint) -> "PHOTO: a family of five in front of a farmhouse");
        DocText.Doc photo = DocText.convert(picture("png", 40, 30), "photo.png");
        assertEquals("a family of five in front of a farmhouse", photo.title());
        assertFalse(ImageText.isImage("plain text, long enough".getBytes()));
    }

    @Test
    void withNoModelThatSeesAPictureIsNotSentAndThePersonIsToldHowToSetOne() throws Exception {
        // the live reader finds the model that sees as everything else does (ModelEntries.seeing): here, in an empty library, none
        ImageText.use(null);
        StringBuilder why = new StringBuilder();
        assertEquals("", ImageText.read(picture("png", 40, 30), "photo.png", why));
        assertTrue(why.toString().startsWith("No model that sees pictures is set up.") && why.toString().contains("researchzosho models"), why.toString());
    }

    // a PDF's pages read as pictures, a declined page among them: PdfReadingTest (the reader of PDFs, 0.7.3)
}
