package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A comic's pages read by the helper for a job (0.7.5): in order, the progress told page by page, each page's reading kept in the job's
 * work by the picture's own hash, so that a job a restart interrupted reads only the pages it had not read; a page the helper cannot
 * read is kept as unread with the helper's reason and the run goes on; a stop between pages ends the reading. The helper is the
 * stand-in, the pages drawn by the test.
 */
class ComicReadTest {
    String home;
    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); }

    @Test void thePagesAreReadInOrderKeptWithTheJobAndNotReadAgainWhenItResumes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        Path cbz = tmp.resolve("volume.cbz");
        List<byte[]> pictures = ComicStandIn.volume(cbz, 5);
        int[][] b = ComicStandIn.balloons(500, 700);
        ComicStandIn.says(script, pictures.get(0), ComicStandIn.read(500, 700, ComicStandIn.reading(ComicStandIn.block("b01", "speech", "斉藤先生はどこですか", b[0]), "斉藤", "さいとう"), ComicStandIn.block("b02", "sound", "ガラッ", b[1])));
        ComicStandIn.says(script, pictures.get(1), ComicStandIn.read(500, 700, ComicStandIn.block("b01", "speech", "手術は3時からです", b[0]), ComicStandIn.block("b02", "outside", "帝都大学附属病院", b[2])));
        List<String> told = new ArrayList<>();
        List<ComicTranslation.Read> first;
        // the job's first process reads three pages and is interrupted (a stop asked after the third)
        try (Checkpoint cp = Checkpoint.open(store, "J-0101", Translation.JOB_KIND, "b"); ComicHelper.Reader reader = ComicPlanTest.standIn(tmp, script)) {
            ComicPages.Volume volume = ComicPages.open(cbz, ComicTranslation.workFolder(store, cp));
            assertTrue(volume.pages().get(0).file().startsWith(Checkpoint.dir(store, "J-0101")), "a job's pages are unpacked in the job's own folder");
            int[] read = {0};
            IOException stopped = assertThrows(IOException.class, () -> ComicTranslation.read(volume.pages(), reader, cp, p -> { if (p.path("phase").asText().equals("pages")) { told.add(p.path("units_done").asInt() + "/" + p.path("units_total").asInt()); read[0] = p.path("units_done").asInt(); } }, () -> read[0] >= 3, "checked", "ja"));
            assertEquals("Stopped while the pages were being read; nothing was written.", stopped.getMessage());
        }
        assertEquals(List.of("0/5", "1/5", "2/5", "3/5"), told);
        assertEquals(3, ComicStandIn.asked(script).stream().filter("read"::equals).count());
        // the process that resumes it asks the helper for the two pages left, and gets the three back from the job's kept work
        told.clear();
        try (Checkpoint cp = Checkpoint.open(store, "J-0101", Translation.JOB_KIND, "b"); ComicHelper.Reader reader = ComicPlanTest.standIn(tmp, script)) {
            assertTrue(cp.resumed());
            ComicPages.Volume volume = ComicPages.open(cbz, ComicTranslation.workFolder(store, cp));
            first = ComicTranslation.read(volume.pages(), reader, cp, p -> told.add(p.path("units_done").asInt() + "/" + p.path("units_total").asInt()), () -> false, "checked", "ja");
        }
        assertEquals(5, ComicStandIn.asked(script).stream().filter("read"::equals).count(), "three before, two now: no page was read twice");
        assertEquals(List.of("0/5", "1/5", "2/5", "3/5", "4/5", "5/5"), told);
        assertEquals(5, first.size());
        for (int i = 0; i < 5; i++) assertEquals(i + 1, first.get(i).page().number());
        assertEquals("斉藤先生はどこですか", first.get(0).read().path("blocks").path(0).path("text").asText(), "the first page's reading came back from the job's kept work, its Japanese whole");
        assertEquals("さいとう", first.get(0).read().path("blocks").path(0).path("readings").path(0).path("reading").asText());
        assertEquals(1, first.get(0).blocks("speech")); assertEquals(1, first.get(0).blocks("sound")); assertEquals(1, first.get(0).readings());
        assertEquals(1, first.get(1).blocks("outside"));
        assertEquals(1, first.get(4).blocks("speech"), "a page with no script reads as the stand-in's one balloon");
        assertTrue(ComicStandIn.running().isEmpty() || ComicHelperProcessTest.gone(), "the helper's process ended with the job");
        // a run outside a job keeps nothing and works in a folder of the moment
        Path work = ComicTranslation.workFolder(store, Checkpoint.NONE);
        assertFalse(work.startsWith(store.root()), work.toString());
        Checkpoint.deleteTree(work);
    }

    @Test void thePagesAreReadInTheComicsLanguageAndAStripIsCutIntoPiecesThatAreEachAPage(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        // a folder of a page and a web comic's strip of four panels
        Path folder = Files.createDirectories(tmp.resolve("episode"));
        Files.write(folder.resolve("01.png"), ComicStandIn.png(ComicStandIn.page(1, 300, 420)));
        Files.write(folder.resolve("02.png"), ComicStandIn.png(ComicStandIn.strip(200, 4)));
        List<String> told = new ArrayList<>();
        try (Checkpoint cp = Checkpoint.open(store, "J-0103", Translation.JOB_KIND, "b"); ComicHelper.Reader reader = ComicPlanTest.standIn(tmp, script)) {
            Path work = ComicTranslation.workFolder(store, cp);
            ComicPages.Volume volume = ComicPages.open(folder, work);
            ComicTranslation.Cut cut = ComicTranslation.cut(volume, reader, cp, work, p -> told.add(p.path("phase").asText() + " " + p.path("units_done").asInt() + "/" + p.path("units_total").asInt()), () -> false, "checked");
            assertEquals(List.of("strips 2/2"), told, "the cutting is said once a strip is met, by the volume's pictures");
            assertEquals(5, cut.pages().size(), "the page, and the strip's four pieces in its place"); assertEquals(1, cut.strips().size()); assertEquals(4, cut.pieces());
            assertEquals(List.of("1", "2, part 1 of 4", "2, part 2 of 4", "2, part 3 of 4", "2, part 4 of 4"), cut.pages().stream().map(ComicPages.Page::label).toList());
            ComicTranslation.Strip strip = cut.strips().get(2);
            assertEquals(200, strip.width()); assertEquals(1200, strip.height()); assertEquals("02.png", strip.whole().name());
            assertEquals(List.of(0, 300, 600, 900), strip.pieces().stream().map(ComicPages.Page::top).toList());
            for (ComicPages.Page piece : strip.pieces()) { assertTrue(piece.file().startsWith(Checkpoint.dir(store, "J-0103").resolve("comic/cut/p0002")), "the pieces are kept in the job's own folder: " + piece.file()); assertEquals("png", piece.format()); assertTrue(piece.piece()); }
            assertFalse(cut.pages().get(0).piece());
            // read in the comic's language: the helper is told it on every page, and a piece is read as a page is
            List<ComicTranslation.Read> read = ComicTranslation.read(cut.pages(), reader, cp, null, () -> false, "checked", "ko");
            assertEquals(5, read.size());
            List<String> reads = ComicStandIn.requests(script, "read");
            assertEquals(5, reads.size()); for (String r : reads) assertTrue(r.contains("\"language\":\"ko\""), r);
            assertEquals("ko", read.get(1).read().path("language").asText());
            assertNotEquals(ComicTranslation.pageKey(cut.pages().get(0).file(), "ko"), ComicTranslation.pageKey(cut.pages().get(0).file(), "ja"), "a page read in another language is another reading");
            assertEquals(ComicTranslation.pageKey(cut.pages().get(0).file()), ComicTranslation.pageKey(cut.pages().get(0).file(), "ja"), "a Japanese page's key is the one it had before the other languages");
        }
        // a job that resumes cuts nothing again while its pieces are there, and cuts again when they are gone
        try (Checkpoint cp = Checkpoint.open(store, "J-0103", Translation.JOB_KIND, "b"); ComicHelper.Reader reader = ComicPlanTest.standIn(tmp, script)) {
            Path work = ComicTranslation.workFolder(store, cp);
            ComicTranslation.Cut again = ComicTranslation.cut(ComicPages.open(folder, work), reader, cp, work, null, () -> false, "checked");
            assertEquals(5, again.pages().size());
            assertEquals(2, ComicStandIn.asked(script).stream().filter("cut"::equals).count(), "each picture was asked about once, by the first process");
            Files.delete(again.pages().get(2).file());
            assertEquals(5, ComicTranslation.cut(ComicPages.open(folder, work), reader, cp, work, null, () -> false, "checked").pages().size());
            assertEquals(3, ComicStandIn.asked(script).stream().filter("cut"::equals).count(), "a strip whose piece is gone from the job's folder is cut again");
            assertTrue(Files.isRegularFile(again.pages().get(2).file()));
        }
    }

    @Test void aPageTheHelperCannotReadIsKeptAsUnreadWithItsReasonAndTheRunGoesOn(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        byte[] good = ComicStandIn.png(ComicStandIn.page(1, 300, 420));
        Path zip = ComicStandIn.zip(tmp.resolve("volume.zip"), "001.png", good, "002.png", "a file named as a picture that is none".getBytes(StandardCharsets.UTF_8), "003.png", ComicStandIn.png(ComicStandIn.page(3, 300, 420)));
        try (Checkpoint cp = Checkpoint.open(store, "J-0102", Translation.JOB_KIND, "b"); ComicHelper.Reader reader = ComicPlanTest.standIn(tmp, script)) {
            List<ComicTranslation.Read> pages = ComicTranslation.read(ComicPages.open(zip, ComicTranslation.workFolder(store, cp)).pages(), reader, cp, null, () -> false, "quick", "ja");
            assertEquals(3, pages.size());
            assertEquals("", pages.get(0).unread()); assertEquals("", pages.get(2).unread());
            assertTrue(pages.get(1).unread().contains("cannot identify image file"), pages.get(1).unread());
            assertEquals(0, pages.get(1).blocks("speech"));
            ObjectNode kept = (ObjectNode) cp.get(ComicTranslation.pageKey(pages.get(1).page().file()));
            assertTrue(kept.has("unread"), "kept as unread: a resumed job does not ask the helper for it again");
        }
    }
}
