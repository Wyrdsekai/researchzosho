package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.Stopping;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The comic helper's process as the program keeps it (0.7.5): one process, a request a line and an answer a line; a page it cannot do is
 * that page's failure and the process lives on; a process that dies is started once more, and a second death ends the job with a reason;
 * a stop ends the wait and the process within seconds; a helper that gives no answer is ended after its wait. The process here is a
 * stand-in that speaks the helper's lines ({@link ComicStandIn}); no Python, no model.
 */
class ComicHelperProcessTest {
    static final ObjectMapper M = new ObjectMapper();
    String home; Duration stall, first;
    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); stall = ComicHelper.STALL; first = ComicHelper.FIRST; }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); ComicHelper.STALL = stall; ComicHelper.FIRST = first; }

    static ComicHelper.Local helper(Path tmp, Path script) {
        return new ComicHelper.Local(() -> { try { return ComicStandIn.command(tmp, script); } catch (IOException e) { throw new UncheckedIOException(e); } });
    }

    /** Waits until no stand-in process of this test is alive; false after ten seconds. */
    static boolean gone() throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) { if (ComicStandIn.running().isEmpty()) return true; Thread.sleep(50); }
        return false;
    }

    @Test void oneProcessAnswersReadSetAndPictureAndIsAskedToQuitWhenTheJobClosesIt(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("script");
        byte[] one = ComicStandIn.png(ComicStandIn.page(1, 500, 700)), two = ComicStandIn.png(ComicStandIn.page(2, 500, 700));
        Path p1 = tmp.resolve("p1.png"), p2 = tmp.resolve("p2.png"), notAPicture = tmp.resolve("p3.png");
        Files.write(p1, one); Files.write(p2, two); Files.writeString(notAPicture, "this is no picture");
        int[][] b = ComicStandIn.balloons(500, 700);
        ComicStandIn.says(script, one, ComicStandIn.read(500, 700, ComicStandIn.reading(ComicStandIn.block("b01", "speech", "山田さんを呼んでください", b[0]), "山田", "やまだ"), ComicStandIn.block("b02", "sound", "ドドド", b[1]), ComicStandIn.block("b03", "outside", "東京駅", b[2])));
        try (ComicHelper.Local h = helper(tmp, script)) {
            assertEquals("", h.loaded(), "nothing is started before the first page");
            JsonNode page = h.read(p1);
            assertEquals(1, ComicStandIn.running().size(), "one process");
            assertTrue(h.loaded().startsWith("version stand-in"), h.loaded());
            assertEquals(3, page.path("blocks").size()); assertEquals(500, page.path("width").asInt());
            assertEquals("山田さんを呼んでください", page.path("blocks").path(0).path("text").asText(), "what the helper read comes back as it wrote it, its Japanese whole");
            assertEquals("やまだ", page.path("blocks").path(0).path("readings").path(0).path("reading").asText());
            assertEquals("sound", page.path("blocks").path(1).path("kind").asText());
            assertEquals("こんにちは", h.read(p2).path("blocks").path(0).path("text").asText());
            // a file it cannot open is that page's failure, in the helper's own words; the process lives on
            ComicHelper.Failed f = assertThrows(ComicHelper.Failed.class, () -> h.read(notAPicture));
            assertTrue(f.getMessage().contains("cannot identify image file p3.png"), f.getMessage());
            assertEquals(1, ComicStandIn.running().size());
            // set: the texts by block, the font for the target's alphabet first and the other after it
            ObjectNode texts = M.createObjectNode(); texts.putObject("b01").put("text", "Please call Mr Yamada.").put("lang", "en"); texts.putObject("b02").put("text", "rumble").put("lang", "en");
            Path out = tmp.resolve("set").resolve("p1.png"); Files.createDirectories(out.getParent());
            JsonNode report = h.set(p1, page, texts, "cyrillic", out);
            assertTrue(Files.exists(out));
            assertTrue(report.path("blocks").path("b01").path("set").asBoolean()); assertEquals(28, report.path("blocks").path("b01").path("px").asInt());
            assertEquals("a sound word: left as art", report.path("blocks").path("b02").path("why_not").asText());
            JsonNode sent = M.readTree(Files.readString(Path.of(out + ".set.json"), StandardCharsets.UTF_8));
            assertTrue(sent.path("font").asText().endsWith("ComicRelief-Bold.ttf"), "a Cyrillic target is set in the font that has Cyrillic: " + sent);
            assertTrue(sent.path("more_fonts").path(0).asText().endsWith("ComicNeue-Bold.ttf"), sent.toString());
            assertEquals("Please call Mr Yamada.", sent.path("texts").path("b01").path("text").asText());
            // picture: the numbered boxes
            Path pic = tmp.resolve("p1-boxes.jpg");
            h.picture(p1, List.of(Map.entry("1", new double[]{300, 90, 425, 265}), Map.entry("2", new double[]{83, 110, 208, 285})), pic);
            assertTrue(Files.exists(pic));
            assertEquals("[[\"1\",[300.0,90.0,425.0,265.0]],[\"2\",[83.0,110.0,208.0,285.0]]]", Files.readString(Path.of(pic + ".boxes.json"), StandardCharsets.UTF_8));
        }
        assertTrue(gone(), "the process ends when the job closes the helper");
        assertEquals(List.of("read", "read", "read", "set", "picture", "quit"), ComicStandIn.asked(script), "asked to quit, as its protocol has it");
    }

    @Test void theProcessSaysTheWritingReadsInALanguageCutsAStripAndJoinsItAgain(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("script");
        byte[] one = ComicStandIn.png(ComicStandIn.page(1, 500, 700)), two = ComicStandIn.png(ComicStandIn.page(2, 500, 700));
        Path p1 = tmp.resolve("p1.png"), p2 = tmp.resolve("p2.png"), strip = tmp.resolve("ep01.jpg");
        Files.write(p1, one); Files.write(p2, two); Files.write(strip, ComicStandIn.jpg(ComicStandIn.strip(200, 3)));
        int[][] b = ComicStandIn.balloons(500, 700);
        ComicStandIn.says(script, one, ComicStandIn.read(500, 700, ComicStandIn.block("b01", "speech", "의사 선생님은 어디에 계세요?", b[0])));
        ComicStandIn.says(script, two, ComicStandIn.read(500, 700, ComicStandIn.block("b01", "speech", "수술은 3시부터입니다", b[0]), ComicStandIn.block("b02", "sound", "쾅", b[1])));
        try (ComicHelper.Local h = helper(tmp, script)) {
            assertEquals(List.of(), h.languages(), "nothing is known of the helper before its process has started");
            // script: the writing system of the lettering, whether it runs in columns, and the text read, for the program to tell a language by
            JsonNode said = h.script(List.of(p1, p2));
            assertEquals("korean", said.path("script").asText()); assertFalse(said.path("vertical").asBoolean()); assertEquals(1.0, said.path("share").asDouble());
            assertEquals("의사 선생님은 어디에 계세요?", said.path("texts").path(0).asText()); assertEquals(3, said.path("texts").size());
            assertTrue(h.languages().containsAll(List.of("ja", "en", "zh", "ko", "de", "fr", "es", "pt", "it", "ru")), "the helper's first line lists the languages it reads: " + h.languages());
            ComicTranslation.Source found = ComicTranslation.Source.found(said, 2);
            assertEquals("ko", found.code()); assertEquals(ComicTranslation.SAMPLED, found.how()); assertEquals(2, found.sampled()); assertTrue(found.clear());
            // read: the language is named in the request; a read that names none asks for the helper's own default
            assertEquals("ko", h.read(p1, "ko").path("language").asText());
            assertEquals("ja", h.read(p2).path("language").asText());
            List<String> reads = ComicStandIn.requests(script, "read");
            assertTrue(reads.get(0).contains("\"language\":\"ko\"") && !reads.get(1).contains("language"), reads.toString());
            // cut: an ordinary page is no strip and nothing is written; a strip comes back as pieces that cover it from the top
            JsonNode page = h.cut(p1, tmp.resolve("cut/p1"));
            assertFalse(page.path("strip").asBoolean()); assertEquals(0, page.path("pieces").size()); assertFalse(Files.exists(tmp.resolve("cut/p1")));
            JsonNode cut = h.cut(strip, tmp.resolve("cut/ep01"));
            assertTrue(cut.path("strip").asBoolean()); assertEquals(200, cut.path("width").asInt()); assertEquals(900, cut.path("height").asInt()); assertEquals(3, cut.path("pieces").size());
            assertEquals(tmp.resolve("cut/ep01/ep01_002.png").toString(), cut.path("pieces").path(1).path("file").asText()); assertEquals(300, cut.path("pieces").path(1).path("top").asInt()); assertEquals(300, cut.path("pieces").path(1).path("height").asInt());
            // join: the pieces at their tops, one picture of the strip's size, in the format its name ends in
            List<ComicHelper.Piece> pieces = new ArrayList<>();
            for (JsonNode piece : cut.path("pieces")) pieces.add(new ComicHelper.Piece(Path.of(piece.path("file").asText()), piece.path("top").asInt()));
            Path joined = tmp.resolve("out").resolve("ep01.jpg"); Files.createDirectories(joined.getParent());
            h.join(pieces, 200, 900, joined);
            BufferedImage whole = ImageIO.read(joined.toFile());
            assertEquals(200, whole.getWidth()); assertEquals(900, whole.getHeight());
            JsonNode sent = M.readTree(Files.readString(Path.of(joined + ".join.json"), StandardCharsets.UTF_8));
            assertEquals(3, sent.path("pieces").size()); assertEquals(600, sent.path("pieces").path(2).path("top").asInt()); assertEquals(200, sent.path("width").asInt()); assertEquals(900, sent.path("height").asInt());
            // a set names columns only for the blocks asked in columns, and the target's own font first
            ObjectNode texts = M.createObjectNode(); texts.putObject("b01").put("text", "医生在哪里？").put("lang", "zh").put("vertical", true);
            Path out = tmp.resolve("set").resolve("p1.png"); Files.createDirectories(out.getParent());
            ComicStandIn.grown();
            try { h.set(p1, h.read(p1, "ko"), texts, "chinese-simplified", out); } finally { ComicHelper.manifestFrom(null); }
            JsonNode set = M.readTree(Files.readString(Path.of(out + ".set.json"), StandardCharsets.UTF_8));
            assertTrue(set.path("texts").path("b01").path("vertical").asBoolean()); assertEquals("zh", set.path("texts").path("b01").path("lang").asText());
            assertTrue(set.path("font").asText().endsWith("SansSC-Bold.otf") && set.path("more_fonts").path(0).asText().endsWith("SansTC-Bold.otf"), set.toString());
        }
        assertTrue(gone());
        assertEquals(List.of("script", "read", "read", "cut", "cut", "join", "read", "set", "quit"), ComicStandIn.asked(script));
    }

    @Test void aProcessThatDiesIsStartedOnceMoreAndASecondDeathEndsTheJobWithAReason(@TempDir Path tmp) throws Exception {
        Path script = Files.createDirectories(tmp.resolve("script"));
        Path p1 = tmp.resolve("p1.png"); Files.write(p1, ComicStandIn.png(ComicStandIn.page(1, 300, 420)));
        try (ComicHelper.Local h = helper(tmp, script)) {
            Files.writeString(script.resolve("die-once"), "");
            JsonNode page = h.read(p1);   // the first process dies under the request; a second answers it
            assertEquals("こんにちは", page.path("blocks").path(0).path("text").asText());
            assertEquals(List.of("read", "read"), ComicStandIn.asked(script), "the request the process died under was asked again");
            assertEquals(1, ComicStandIn.running().size());
            Files.writeString(script.resolve("die-always"), "");
            IOException e = assertThrows(IOException.class, () -> h.read(p1));
            assertFalse(e instanceof ComicHelper.Failed, "not one page's failure: the helper is gone");
            assertTrue(e.getMessage().startsWith("The comic helper's process ended twice") && e.getMessage().endsWith("researchzosho translate comics install sets it up again."), e.getMessage());
        }
        assertTrue(gone());
    }

    @Test void aStopEndsTheWaitAndTheProcessWithinSeconds(@TempDir Path tmp) throws Exception {
        Path script = Files.createDirectories(tmp.resolve("script"));
        Files.writeString(script.resolve("hang"), "");   // a read that never answers, as a page on a loaded processor
        Path p1 = tmp.resolve("p1.png"); Files.write(p1, ComicStandIn.png(ComicStandIn.page(1, 300, 420)));
        AtomicBoolean stop = new AtomicBoolean(); AtomicLong stoppedAt = new AtomicLong();
        Thread asker = new Thread(() -> {
            long until = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < until && !Files.exists(script.resolve("requests.log"))) { try { Thread.sleep(50); } catch (InterruptedException e) { return; } }
            try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            stoppedAt.set(System.currentTimeMillis()); stop.set(true);
        });
        asker.setDaemon(true); asker.start();
        try (ComicHelper.Local h = helper(tmp, script)) {
            assertTimeoutPreemptively(Duration.ofSeconds(90), () -> assertThrows(Stopping.Requested.class, () -> Stopping.within(stop::get, () -> { try { return h.read(p1); } catch (IOException e) { throw new UncheckedIOException(e); } })));
            long took = System.currentTimeMillis() - stoppedAt.get();
            assertTrue(stoppedAt.get() > 0 && took < 5_000, "the stop took " + took + " ms");
            assertTrue(gone(), "the helper's process is ended by the stop, not left reading");
            assertTrue(assertThrows(IOException.class, () -> h.read(p1)).getMessage().contains("was stopped"), "nothing more is asked after a stop");
        }
    }

    @Test void aHelperThatGivesNoAnswerIsEndedAfterItsWaitAndSaidSo(@TempDir Path tmp) throws Exception {
        Path script = Files.createDirectories(tmp.resolve("script"));
        Files.writeString(script.resolve("hang"), "");
        Path p1 = tmp.resolve("p1.png"); Files.write(p1, ComicStandIn.png(ComicStandIn.page(1, 300, 420)));
        ComicHelper.STALL = Duration.ofSeconds(1);
        try (ComicHelper.Local h = helper(tmp, script)) {
            IOException e = assertTimeoutPreemptively(Duration.ofSeconds(90), () -> assertThrows(IOException.class, () -> h.read(p1)));
            assertEquals("The comic helper gave no answer for 1 second on one page.", e.getMessage());
            assertTrue(gone(), "its process is ended, never left behind");
        }
        // a command that cannot be started at all
        try (ComicHelper.Local h = new ComicHelper.Local(() -> List.of(tmp.resolve("no-such-python").toString(), "-I", "comic_helper.py", "serve"))) {
            IOException e = assertThrows(IOException.class, () -> h.read(p1));
            assertTrue(e.getMessage().startsWith("The comic helper's process could not be started: ") && e.getMessage().endsWith("researchzosho translate comics status says how it stands."), e.getMessage());
        }
    }
}
