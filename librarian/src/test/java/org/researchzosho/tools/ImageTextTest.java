package org.researchzosho.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.Declined;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    /** A reader that declines the second page it is shown and reads the others. */
    private static ImageText.Reader decliningPageTwo(AtomicInteger shown) {
        return (png, hint) -> {
            if (shown.incrementAndGet() == 2) throw new Declined("placeholder-model", "I am not able to help with that request.", Declined.How.WORDS);
            return "the writing of page " + shown.get();
        };
    }

    @Test
    void aPageTheModelDeclinedIsSaidInTheTextAndThePagesAfterItAreRead(@TempDir Path tmp) throws Exception {
        // M4: page two declined; pages one and three are read, and the saved text says at page two that the model declined it
        List<Path> pages = List.of(tmp.resolve("p-1.png"), tmp.resolve("p-2.png"), tmp.resolve("p-3.png"));
        for (Path p : pages) Files.write(p, picture("png", 40, 30));
        ImageText.use(decliningPageTwo(new AtomicInteger()));
        StringBuilder why = new StringBuilder();
        String text = DocText.readPages(pages, "scan.pdf", why);
        assertTrue(text.contains("[page 1]\nthe writing of page 1"), text);
        assertTrue(text.contains("[page 3]\nthe writing of page 3"), "the pages after the declined one are read: " + text);
        assertTrue(text.contains("[page 2]\n(The model this library uses (placeholder-model) declined to read the writing on this page. ResearchZosho did not try to get around it."), text);
        // every page declined: no text, and why says so
        ImageText.use((png, hint) -> { throw new Declined("placeholder-model", "", Declined.How.FILTERED); });
        StringBuilder none = new StringBuilder();
        assertEquals("", DocText.readPages(pages, "scan.pdf", none));
        assertTrue(none.toString().startsWith("The model this library uses (placeholder-model) declined to read the writing on the pages of this document."), none.toString());
    }

    @Test
    void aScannedPdfIsNotCutShortAtAPageTheModelDeclined() throws Exception {
        // M4, through the whole conversion: a PDF of three pages with no text in it is drawn page by page (poppler) and read as pictures
        assumeTrue(DocText.pdftotext() != null, "poppler's pdftotext and pdftoppm draw the pages");
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) { for (int i = 0; i < 3; i++) doc.addPage(new PDPage()); doc.save(pdf); }
        ImageText.use(decliningPageTwo(new AtomicInteger()));
        DocText.Doc d = DocText.convert(pdf.toByteArray(), "scan.pdf");
        assertEquals("scanned pdf", d.kind(), d.kind());
        assertTrue(d.text().contains("the writing of page 3"), "the document is not cut at page two: " + d.text());
        assertTrue(d.text().contains("declined to read the writing on this page"), d.text());
    }
}
