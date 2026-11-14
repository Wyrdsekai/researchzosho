package org.researchzosho.librarian;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A comic's pages out of what holds them and back (0.7.5): a CBZ, a ZIP, a folder, a CBR through a program of the machine, a PDF
 * whose pages are pictures. The pages are drawn by the test; no archive of a real comic is in the repository.
 */
class ComicPagesTest {
    Searx.Runner runner = Searx.runner;
    @AfterEach void back() { Searx.runner = runner; }

    static List<String> names(ComicPages.Volume v) { List<String> out = new ArrayList<>(); for (ComicPages.Page p : v.pages()) out.add(p.name()); return out; }

    @Test void thePagesComeInTheNaturalOrderOfTheirNamesWhateverTheArchivesOwn(@TempDir Path tmp) throws Exception {
        List<String> names = new ArrayList<>(List.of("page10.png", "Page2.png", "page1.png", "page02b.png", "ch2/001.png", "ch10/001.png", "ch2/010.png", "ch2/2.png"));
        names.sort(ComicPages.NATURAL);
        assertEquals(List.of("ch2/001.png", "ch2/2.png", "ch2/010.png", "ch10/001.png", "page1.png", "Page2.png", "page02b.png", "page10.png"), names, "a run of digits by its number, capitals of no account, a folder read through in its place");
        // twelve pages written to the archive last page first, named 1.png … 12.png
        Path cbz = tmp.resolve("volume.cbz");
        List<byte[]> pages = ComicStandIn.volume(cbz, 12);
        assertEquals(ComicPages.CBZ, ComicPages.container(cbz)); assertTrue(ComicPages.byItself(cbz)); assertEquals(12, ComicPages.count(cbz));
        assertEquals(List.of("1.png", "2.png", "3.png", "4.png", "5.png", "6.png", "7.png", "8.png", "9.png", "10.png", "11.png", "12.png"), ComicPages.names(cbz));
        ComicPages.Volume v = ComicPages.open(cbz, Files.createDirectories(tmp.resolve("work")));
        assertEquals(12, v.pages().size()); assertEquals(ComicPages.CBZ, v.container());
        for (int i = 0; i < 12; i++) { assertEquals(i + 1, v.pages().get(i).number()); assertArrayEquals(pages.get(i), Files.readAllBytes(v.pages().get(i).file()), "page " + (i + 1) + " is the picture drawn as page " + (i + 1)); assertEquals("png", v.pages().get(i).format()); }
        assertTrue(v.pages().get(0).file().startsWith(tmp.resolve("work")), "the pages are unpacked under the job's own folder");
    }

    @Test void anArchiveIsNotTrustedItsLeftoversArePassedOverAndWhatIsNoPictureIsSaid(@TempDir Path tmp) throws Exception {
        byte[] one = ComicStandIn.png(ComicStandIn.page(1, 300, 420)), two = ComicStandIn.jpg(ComicStandIn.page(2, 300, 420));
        Path zip = ComicStandIn.zip(tmp.resolve("Volume 3.zip"), "vol/002.jpg", two, "vol/001.png", one, "../../outside.png", one, "/abs.png", one, "__MACOSX/vol/._001.png", new byte[]{1, 2}, "vol/.DS_Store", new byte[]{1},
                "ComicInfo.xml", "<ComicInfo/>".getBytes(StandardCharsets.UTF_8), "vol/Thumbs.db", new byte[]{3}, "vol/credits.rtf", "who".getBytes(StandardCharsets.UTF_8), "inner.zip", new byte[]{'P', 'K', 3, 4});
        assertTrue(ComicPages.byItself(zip), "a .zip of page pictures is a comic by itself, as a .cbz is");
        Path work = Files.createDirectories(tmp.resolve("job").resolve("work"));
        ComicPages.Volume v = ComicPages.open(zip, work);
        assertEquals(List.of("vol/001.png", "vol/002.jpg"), names(v), "the pages; the entry that pointed outside the folder is not one of them");
        assertEquals("jpg", v.pages().get(1).format());
        assertFalse(Files.exists(tmp.resolve("outside.png")), "nothing was written outside the work folder"); assertFalse(Files.exists(tmp.resolve("job").resolve("outside.png")));
        String left = String.join("\n", v.leftOut());
        assertTrue(left.contains("outside.png") && left.contains("points outside the folder"), left);
        assertTrue(left.contains("credits.rtf") && left.contains("not a page picture"), left);
        assertTrue(left.contains("inner.zip") && left.contains("an archive inside the archive"), left);
        assertFalse(left.contains("ComicInfo") || left.contains("DS_Store") || left.contains("Thumbs") || left.contains("__MACOSX"), "a reader's own file and a system's leftovers are passed over without a word: " + left);
    }

    @Test void aZipWhoseNamesAreNotUtf8IsStillOpened(@TempDir Path tmp) throws Exception {
        Path cbz = tmp.resolve("old.cbz");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(cbz), Charset.forName("MS932"))) {   // names as an older Japanese system wrote them
            for (int p = 1; p <= 2; p++) { z.putNextEntry(new ZipEntry("第" + p + "話.png")); z.write(ComicStandIn.png(ComicStandIn.page(p, 200, 280))); z.closeEntry(); }
        }
        assertTrue(ComicPages.byItself(cbz)); assertEquals(2, ComicPages.count(cbz));
        ComicPages.Volume v = ComicPages.open(cbz, Files.createDirectories(tmp.resolve("work")));
        assertEquals(2, v.pages().size());
        assertNotNull(ImageIO.read(v.pages().get(0).file().toFile()), "the picture came out whole");
    }

    @Test void whatIsAComicByItselfAndWhatIsNot(@TempDir Path tmp) throws Exception {
        byte[] pic = ComicStandIn.png(ComicStandIn.page(1, 200, 280));
        // a folder of pictures, with a subfolder
        Path folder = Files.createDirectories(tmp.resolve("My Manga").resolve("chapter 2"));
        Files.write(folder.resolve("10.png"), pic); Files.write(folder.resolve("9.png"), pic); Files.write(tmp.resolve("My Manga").resolve("cover.jpg"), ComicStandIn.jpg(ComicStandIn.page(0, 200, 280)));
        assertEquals(ComicPages.FOLDER, ComicPages.container(tmp.resolve("My Manga"))); assertTrue(ComicPages.byItself(tmp.resolve("My Manga")));
        ComicPages.Volume v = ComicPages.open(tmp.resolve("My Manga"), Files.createDirectories(tmp.resolve("work")));
        assertEquals(List.of("chapter 2/9.png", "chapter 2/10.png", "cover.jpg"), names(v));
        assertEquals(folder.resolve("9.png"), v.pages().get(0).file(), "a folder's pictures are read where they lie, not copied");
        // a folder of documents with one picture among them is no comic
        Path docs = Files.createDirectories(tmp.resolve("papers"));
        Files.writeString(docs.resolve("a.md"), "# a"); Files.writeString(docs.resolve("b.md"), "# b"); Files.writeString(docs.resolve("c.pdf"), "%PDF-1.4"); Files.write(docs.resolve("figure.png"), pic);
        assertFalse(ComicPages.byItself(docs));
        // a Word file and an EPUB are zips, and never comics
        Path docx = ComicStandIn.zip(tmp.resolve("a.docx"), "word/document.xml", "<w/>".getBytes(StandardCharsets.UTF_8), "word/media/image1.png", pic, "word/media/image2.png", pic);
        Path epub = ComicStandIn.zip(tmp.resolve("a.epub"), "META-INF/container.xml", "<c/>".getBytes(StandardCharsets.UTF_8), "OEBPS/content.opf", "<p/>".getBytes(StandardCharsets.UTF_8), "OEBPS/1.jpg", pic, "OEBPS/2.jpg", pic, "OEBPS/3.jpg", pic);
        assertFalse(ComicPages.byItself(docx)); assertFalse(ComicPages.byItself(epub));
        // a zip with no picture, a text file, nothing at all
        assertFalse(ComicPages.byItself(ComicStandIn.zip(tmp.resolve("notes.zip"), "a.txt", "a".getBytes(StandardCharsets.UTF_8))));
        Path txt = tmp.resolve("notes.txt"); Files.writeString(txt, "notes");
        assertNull(ComicPages.container(txt)); assertFalse(ComicPages.byItself(txt)); assertFalse(ComicPages.byItself(tmp.resolve("missing.cbz")));
        // a PDF is never a comic by itself
        Path pdf = pictures(tmp.resolve("scan.pdf"), 2);
        assertEquals(ComicPages.PDF, ComicPages.container(pdf)); assertFalse(ComicPages.byItself(pdf));
        // a file named .cbz that is a RAR inside is a CBR
        Path rar = tmp.resolve("mislabelled.cbz"); Files.write(rar, new byte[]{'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00, 1, 2, 3});
        assertEquals(ComicPages.CBR, ComicPages.container(rar));
    }

    /** A PDF of {@code n} pages, each one picture that fills the page. */
    static Path pictures(Path file, int n) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int p = 1; p <= n; p++) {
                BufferedImage img = ComicStandIn.page(p, 300, 420);
                PDPage page = new PDPage(new PDRectangle(300, 420));
                doc.addPage(page);
                PDImageXObject x = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) { cs.drawImage(x, 0, 0, 300, 420); }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /**
     * A scanned comic in a PDF holds one picture a page at the scan's own resolution. Drawn at a fixed number of dots an inch the
     * lettering is thrown away (read live, 2026-11-14: a page of 577 pt came out 1,763 px high whatever its scan held). A page that
     * is one picture is that picture, pixel for pixel; any other page is drawn with its long side about 2,000 px.
     */
    @Test void aPdfPageThatIsOnePictureIsThatPictureAndAnyOtherIsDrawnTwoThousandPixelsLong(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("scan.pdf");
        BufferedImage one = ComicStandIn.page(1, 1240, 1754), two = ComicStandIn.page(2, 1240, 1754);   // A5 at 300 dots an inch
        float w = 1240 * 72f / 300, h = 1754 * 72f / 300;
        try (PDDocument doc = new PDDocument()) {
            PDPage a = new PDPage(new PDRectangle(w, h)); doc.addPage(a);                                   // a picture kept without loss
            try (PDPageContentStream cs = new PDPageContentStream(doc, a)) { cs.drawImage(LosslessFactory.createFromImage(doc, one), 0, 0, w, h); }
            PDPage b = new PDPage(new PDRectangle(w, h)); doc.addPage(b);                                   // a JPEG
            try (PDPageContentStream cs = new PDPageContentStream(doc, b)) { cs.drawImage(JPEGFactory.createFromImage(doc, two, 0.9f), 0, 0, w, h); }
            PDPage c = new PDPage(new PDRectangle(w, h)); doc.addPage(c);                                   // two pictures, the page's halves
            try (PDPageContentStream cs = new PDPageContentStream(doc, c)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, one.getSubimage(0, 0, 1240, 877)), 0, h / 2, w, h / 2);
                cs.drawImage(LosslessFactory.createFromImage(doc, one.getSubimage(0, 877, 1240, 877)), 0, 0, w, h / 2);
            }
            PDPage d = new PDPage(new PDRectangle(w, h)); doc.addPage(d);                                   // one picture with the page's margin round it (a scan set on a sheet)
            float mw = 36 * 72f / 300, mh = 51 * 72f / 300;                                                  // 36 and 51 px of margin at the picture's own 300 dots an inch
            try (PDPageContentStream cs = new PDPageContentStream(doc, d)) { cs.drawImage(LosslessFactory.createFromImage(doc, one.getSubimage(36, 51, 1168, 1652)), mw, mh, w - 2 * mw, h - 2 * mh); }
            PDPage e = new PDPage(new PDRectangle(w, h)); doc.addPage(e);                                   // a picture and a drawn line over it: a page that is more than its pictures
            try (PDPageContentStream cs = new PDPageContentStream(doc, e)) { cs.drawImage(LosslessFactory.createFromImage(doc, one), 0, 0, w, h); cs.setLineWidth(3); cs.moveTo(20, 20); cs.lineTo(w - 20, h - 20); cs.stroke(); }
            doc.save(pdf.toFile());
        }
        ComicPages.Volume v = ComicPages.open(pdf, Files.createDirectories(tmp.resolve("work")));
        assertEquals(List.of("1", "2", "3", "4", "5"), names(v)); assertTrue(v.leftOut().isEmpty());
        BufferedImage first = ImageIO.read(v.pages().get(0).file().toFile());
        assertEquals("1240x1754", first.getWidth() + "x" + first.getHeight(), "the picture's own pixels");
        for (int y = 0; y < 1754; y += 7) for (int x = 0; x < 1240; x += 5) assertEquals(one.getRGB(x, y) & 0xffffff, first.getRGB(x, y) & 0xffffff, "pixel for pixel, at " + x + "," + y);
        assertEquals("jpg", v.pages().get(1).format(), "a JPEG is taken as the file it is");
        BufferedImage second = ImageIO.read(v.pages().get(1).file().toFile());
        assertEquals("1240x1754", second.getWidth() + "x" + second.getHeight());
        // a page that holds pictures alone, all at one resolution, is drawn at that resolution: two halves, and a picture with a margin (read live: story pages of
        // 1,246 x 1,635 px set on A4 were drawn at 1,413 x 1,999, and a cover of 1,627 strips came out with a light line at every seam)
        for (int k : new int[]{2, 3}) {
            BufferedImage own = ImageIO.read(v.pages().get(k).file().toFile());
            assertEquals("1240x1754", own.getWidth() + "x" + own.getHeight(), "page " + (k + 1) + " at its pictures' own 300 dots an inch");
            int off = 0, seen = 0;
            for (int y = 60; y < 1700; y += 7) for (int x = 40; x < 1200; x += 5) { seen++; if ((one.getRGB(x, y) & 0xffffff) != (own.getRGB(x, y) & 0xffffff)) off++; }
            assertEquals(0, off, "pixel for pixel on page " + (k + 1) + ", of " + seen + " looked at");
        }
        assertEquals(0xffffff, ImageIO.read(v.pages().get(3).file().toFile()).getRGB(10, 10) & 0xffffff, "and the margin is the sheet's white");
        BufferedImage drawn = ImageIO.read(v.pages().get(4).file().toFile());
        assertEquals(2000, Math.max(drawn.getWidth(), drawn.getHeight()), 2, "a page that is more than its pictures is drawn about 2,000 px long: " + drawn.getWidth() + "x" + drawn.getHeight());
        assertEquals(1240.0 / 1754, drawn.getWidth() / (double) drawn.getHeight(), 0.002);
        // the PDF that goes out has its pages the size they were in the PDF that came in, whatever the pictures' pixels
        assertArrayEquals(new float[]{w, h}, ComicPages.pagePoints(pdf).get("3"), 0.01f); assertEquals(5, ComicPages.pagePoints(pdf).size()); assertTrue(ComicPages.pagePoints(tmp.resolve("none.pdf")).isEmpty());
        Path out = tmp.resolve("out.pdf");
        List<Path> pictures = new ArrayList<>(); List<float[]> points = new ArrayList<>();
        for (ComicPages.Page page : v.pages()) { pictures.add(page.file()); points.add(ComicPages.pagePoints(pdf).get(page.name())); }
        points.set(3, null);                                                                               // a page whose size is not known: as large as its picture at 150 dots an inch, as before
        ComicPages.writePdf(pictures, out, points);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(5, doc.getNumberOfPages());
            for (int k : new int[]{0, 1, 2, 4}) { assertEquals(w, doc.getPage(k).getMediaBox().getWidth(), 0.01f, "page " + (k + 1)); assertEquals(h, doc.getPage(k).getMediaBox().getHeight(), 0.01f); }
            BufferedImage last = ImageIO.read(pictures.get(3).toFile());
            assertEquals(last.getWidth() * 72f / 150f, doc.getPage(3).getMediaBox().getWidth(), 0.01f);
        }
    }

    @Test void aPdfsPagesAreDrawnAsPictures(@TempDir Path tmp) throws Exception {
        Path pdf = pictures(tmp.resolve("volume.pdf"), 3);
        assertEquals(3, ComicPages.count(pdf));
        ComicPages.Volume v = ComicPages.open(pdf, Files.createDirectories(tmp.resolve("work")));
        assertEquals(ComicPages.PDF, v.container()); assertEquals(List.of("1", "2", "3"), names(v)); assertTrue(v.leftOut().isEmpty());
        BufferedImage first = ImageIO.read(v.pages().get(0).file().toFile());
        assertEquals("png", v.pages().get(0).format());
        assertEquals("300x420", first.getWidth() + "x" + first.getHeight(), "a page that is one picture is that picture, as large as it is");
    }

    @Test void aCbrIsOpenedByAProgramOfTheMachineAndOneSentenceSaysWhatToInstallWhenThereIsNone(@TempDir Path tmp) throws Exception {
        Path cbr = tmp.resolve("volume.cbr"); Files.write(cbr, new byte[]{'R', 'a', 'r', '!', 0x1A, 0x07, 0x00, 9, 9, 9});
        assertTrue(ComicPages.byItself(cbr), "a RAR is taken for a comic by its kind, so the plan can say what opening it takes");
        // no program on the machine
        Searx.runner = cmd -> { throw new IOException("Cannot run program \"" + cmd.get(0) + "\": error=2, No such file or directory"); };
        IOException none = assertThrows(IOException.class, () -> ComicPages.count(cbr));
        assertEquals(ComicPages.NO_RAR_TOOL, none.getMessage());
        assertTrue(none.getMessage().contains("bsdtar") && none.getMessage().contains("7z") && none.getMessage().contains("unrar") && none.getMessage().contains("CBZ"), none.getMessage());
        assertEquals(ComicPages.NO_RAR_TOOL, assertThrows(IOException.class, () -> ComicPages.open(cbr, tmp.resolve("work0"))).getMessage());
        // the machine has 7z and neither of the others: it lists, and it unpacks into the folder it is given
        byte[] pic = ComicStandIn.png(ComicStandIn.page(1, 200, 280));
        List<List<String>> ran = new ArrayList<>();
        Searx.runner = cmd -> {
            ran.add(cmd);
            if (!cmd.get(0).equals("7z")) throw new IOException("Cannot run program \"" + cmd.get(0) + "\"");
            if (cmd.get(1).equals("i")) return new Searx.Result(0, "7-Zip");
            if (cmd.get(1).equals("l")) return new Searx.Result(0, "Path = v1\\002.png\nSize = 10\n\nPath = v1\\001.png\nSize = 10\n\nPath = v1\\info.txt\n");
            Path dir = Path.of(cmd.stream().filter(a -> a.startsWith("-o")).findFirst().orElseThrow().substring(2));
            Files.createDirectories(dir.resolve("v1")); Files.write(dir.resolve("v1").resolve("002.png"), pic); Files.write(dir.resolve("v1").resolve("001.png"), pic); Files.writeString(dir.resolve("v1").resolve("info.txt"), "x");
            return new Searx.Result(0, "Everything is Ok");
        };
        assertEquals(List.of("v1/001.png", "v1/002.png"), ComicPages.names(cbr));
        ComicPages.Volume v = ComicPages.open(cbr, Files.createDirectories(tmp.resolve("work")));
        assertEquals(ComicPages.CBR, v.container()); assertEquals(List.of("v1/001.png", "v1/002.png"), names(v));
        assertTrue(ran.stream().anyMatch(c -> c.get(0).equals("7z") && c.contains("x") && c.contains(cbr.toAbsolutePath().toString())), ran.toString());
        // a program that fails says so in its own last words
        Searx.runner = cmd -> cmd.get(1).equals("--version") && cmd.get(0).equals("bsdtar") ? new Searx.Result(0, "bsdtar 3.7") : new Searx.Result(1, "bsdtar: Error opening archive: Unrecognized archive format");
        assertTrue(assertThrows(IOException.class, () -> ComicPages.names(cbr)).getMessage().contains("Unrecognized archive format"));
    }

    @Test void theSetPagesGoBackIntoACbzUnderThePagesOwnNames(@TempDir Path tmp) throws Exception {
        Path cbz = tmp.resolve("volume.cbz");
        List<byte[]> pages = ComicStandIn.volume(cbz, 3);
        ComicPages.Volume v = ComicPages.open(cbz, Files.createDirectories(tmp.resolve("work")));
        List<Path> files = new ArrayList<>(); for (ComicPages.Page p : v.pages()) files.add(p.file());
        Path out = tmp.resolve("volume.en.cbz");
        ComicPages.writeCbz(files, names(v), out);
        assertFalse(Files.exists(tmp.resolve("volume.en.cbz.part")), "written whole or not at all");
        try (ZipFile z = new ZipFile(out.toFile())) {
            assertEquals(3, z.size());
            for (int i = 0; i < 3; i++) assertArrayEquals(pages.get(i), z.getInputStream(z.getEntry((i + 1) + ".png")).readAllBytes());
        }
        assertEquals(List.of("1.png", "2.png", "3.png"), ComicPages.names(out), "the output is itself a comic's archive, its pages in the same order");
    }

    @Test void anEmptyFolderIsNoComicAndAFileThatHoldsNoPagesIsSaidToBeNone(@TempDir Path tmp) throws Exception {
        assertEquals(5_000, ComicPages.MAX_PAGES);
        Path folder = Files.createDirectories(tmp.resolve("empty"));
        assertFalse(ComicPages.byItself(folder)); assertEquals(0, ComicPages.count(folder));
        IOException e = assertThrows(IOException.class, () -> ComicPages.open(tmp.resolve("nothing.txt"), tmp));
        assertTrue(e.getMessage().contains("is not a comic this program opens"), e.getMessage());
    }
}
