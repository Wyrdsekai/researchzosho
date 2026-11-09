package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.YoutubeProfile;
import org.researchzosho.tools.ImageText;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding what was seen in a video: the moment from what was said, and the picture in your head from the storyboard's frames and from frames cut from a copy, with the model and the helper stood in for. */
class VideoRecallTest {
    final Video.Runner realRunner = Video.runner;
    final BooleanSupplier realInstalled = Video.installedProbe, realProvider = Video.providerProbe;
    final long realPace = Video.readPaceMs;
    final VideoRecall.Vision realVision = VideoRecall.VISION;
    final Video.Runner realFfmpeg = VideoRecall.FFMPEG;
    final Supplier<Embeddings.Embedder> realEmbedder = VideoRecall.EMBEDDER;

    @AfterEach void back() { Video.runner = realRunner; Video.installedProbe = realInstalled; Video.providerProbe = realProvider; Video.readPaceMs = realPace; VideoRecall.VISION = realVision; VideoRecall.FFMPEG = realFfmpeg; VideoRecall.EMBEDDER = realEmbedder; VideoRecall.workDir = null; VideoRecall.forgetVision(); ImageText.use(null); }

    static final String TEA = "https://www.youtube.com/watch?v=aaaaaaaaaaa", BIKE = "https://www.youtube.com/watch?v=bbbbbbbbbbb";

    static LibraryStore storeWithTwoVideos(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        RawCapture.capture(store, TEA, "title: Tea at home\naddress: " + TEA + "\n\ntranscript (en, from the uploader's captions):\n[0:12] first the kettle boils on the stove\n[1:05] then we pour the tea into the blue cups\n[2:40] and we sit by the window\n", "Tea at home", "researchzosho-video", "", "2026-01-02");
        RawCapture.capture(store, BIKE, "title: Fixing a bicycle chain\naddress: " + BIKE + "\n\ntranscript (en, from YouTube's automatic captions):\n[0:03] today the chain came off again\n[0:48] you need a chain tool for this\n", "Fixing a bicycle chain", "researchzosho-video", "", "2026-02-03");
        return store;
    }

    @Test void whatWasSaidIsFoundWithTheMomentAndALinkToIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = storeWithTwoVideos(tmp);
        List<VideoRecall.Hit> hits = VideoRecall.saw(store, "pouring tea into blue cups", 5);
        assertFalse(hits.isEmpty(), "the moment is found");
        VideoRecall.Hit h = hits.get(0);
        assertEquals("aaaaaaaaaaa", h.videoId()); assertEquals(65, h.seconds()); assertEquals("said", h.kind()); assertEquals("Tea at home", h.title());
        assertEquals("https://www.youtube.com/watch?v=aaaaaaaaaaa&t=65s", h.link());
        assertEquals("then we pour the tea into the blue cups", h.line());
        String said = VideoRecall.sawText(hits, "pouring tea into blue cups");
        assertTrue(said.contains("[1:05] Tea at home — said: then we pour the tea into the blue cups") && said.contains("&t=65s"), said);
        assertTrue(VideoRecall.saw(store, "chain tool", 5).get(0).link().endsWith("bbbbbbbbbbb&t=48s"));
        assertTrue(VideoRecall.sawText(VideoRecall.saw(store, "zebra crossings on the moon", 5), "zebra crossings on the moon").startsWith("Nothing in the videos the library has read matches"));
        List<VideoRecall.Held> held = VideoRecall.held(store);
        assertEquals(2, held.size()); assertTrue(held.stream().allMatch(VideoRecall.Held::said) && held.stream().noneMatch(VideoRecall.Held::seen) && held.stream().noneMatch(VideoRecall.Held::shown));
        // the page, the menu, the chat
        String page = VideoRecallPage.body(store, true, "blue cups");
        assertTrue(page.contains("<h2 id=\"found\">What matches</h2>") && page.contains("&amp;t=65s") && page.contains("name=\"description\"") && page.contains("2 videos read, 2 with what was said, 0 with the words on screen, 0 with what is seen"), page);
        YoutubeProfile f = new YoutubeProfile();
        assertTrue(f.menu().get(0).href().equals("/video") && f.pagePaths().contains("/video"));
        assertTrue(f.chatReply(store, Patrons.Patron.PERSON, "terminal", "/video saw blue cups").contains("&t=65s"));
        assertTrue(f.chatReply(store, Patrons.Patron.PERSON, "terminal", "/video").startsWith("The library has read 2 videos, 2 with what was said, 0 with the words on screen and 0 with what is seen."));
        assertEquals(null, f.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career read"), "not its line");
        var pg = f.page(store, Patrons.Patron.PERSON, "/video", "GET", Map.of("q", "chain tool"), Map.of());
        assertTrue(pg.body().contains("Fixing a bicycle chain") && pg.body().contains("&amp;t=48s"), pg.body());
    }

    // ── a storyboard of two sheets, two rows of two tiles each, a tile every five seconds of a thirty-second video ──

    static final int BLUE = 0x2244CC, GREEN = 0x22AA44, RED = 0xCC2222, YELLOW = 0xEEDD22, WHITE = 0xFFFFFF, BLACK = 0x000000, GRAY = 0x808080, ORANGE = 0xEE8822;

    static byte[] sheet(int... colours) throws Exception {
        BufferedImage img = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 4; i++) for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) img.setRGB((i % 2) * 32 + x, (i / 2) * 18 + y, colours[i]);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(img, "png", out); return out.toByteArray();
    }

    /** A sheet as YouTube serves it, as far as the code can tell: a WebP header, then the PNG the stand-in ffmpeg "decodes". */
    static byte[] webp(byte[] png) {
        byte[] out = new byte[12 + png.length];
        System.arraycopy("RIFF0000WEBP".getBytes(StandardCharsets.ISO_8859_1), 0, out, 0, 12);
        System.arraycopy(png, 0, out, 12, png.length);
        return out;
    }

    /** ffmpeg stood in for: a WebP of the test's making is opened by copying the PNG after its header to the output file. */
    static Video.Result decode(List<String> cmd) throws Exception {
        int i = cmd.indexOf("-i");
        byte[] b = Files.readAllBytes(Path.of(cmd.get(i + 1)));
        Files.write(Path.of(cmd.get(cmd.size() - 1)), Arrays.copyOfRange(b, 12, b.length));
        return new Video.Result(0, "");
    }

    static byte[] mhtml(byte[]... sheets) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String b = "ee036633312241aebebc7910df025798";
        out.write(("MIME-Version: 1.0\r\nContent-type: multipart/related; boundary=\"" + b + "\"; type=\"text/html\"\r\n\r\n--" + b + "\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<html>sheets</html>\r\n").getBytes(StandardCharsets.ISO_8859_1));
        for (int i = 0; i < sheets.length; i++) {
            out.write(("--" + b + "\r\nContent-ID: <" + i + "." + b + "@x>\r\nContent-type: image/webp\r\nContent-length: " + sheets[i].length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(sheets[i]); out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        out.write(("--" + b + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    /** The colour at the middle of a picture, as the stand-ins tell the tiles apart. */
    static int colourOf(byte[] png) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        return img.getRGB(img.getWidth() / 2, img.getHeight() / 2) & 0xFFFFFF;
    }

    /** The colour of a frame the stand-in ffmpeg cuts at full size: this and the second. */
    static final int CUT = 0x100000;

    /** The second a stand-in frame was cut at, by its colour; -1 for any other picture. */
    static int cutAt(byte[] png) {
        try { int c = colourOf(png); return (c & 0xFFF000) == CUT ? c & 0xFFF : -1; } catch (Exception e) { return -1; }
    }

    static String name(int colour) {
        return switch (colour) { case BLUE -> "blue"; case GREEN -> "green"; case RED -> "red"; case YELLOW -> "yellow"; case WHITE -> "white"; case BLACK -> "black"; default -> "gray"; };
    }

    @Test void theStoryboardIsReadIntoTheWordsShownAndWhatIsSeenAndThePictureIsFoundAtFullSize(@TempDir Path tmp) throws Exception {
        LibraryStore store = storeWithTwoVideos(tmp);
        VideoRecall.workDir = tmp.resolve("video");
        VideoRecall.EMBEDDER = Embeddings::none;
        Video.installedProbe = () -> true; Video.providerProbe = () -> true; Video.readPaceMs = 0;
        byte[] sb = mhtml(webp(sheet(BLUE, GREEN, RED, RED)), webp(sheet(YELLOW, WHITE, BLACK, GRAY)));   // tiles at 0, 5, 10, 15 (the same as 10), 20, 25, 30 and one past the end
        List<List<String>> ran = new ArrayList<>();
        Video.runner = (cmd, t) -> {
            ran.add(cmd);
            Path o = Path.of(cmd.get(cmd.indexOf("-o") + 1));
            Files.createDirectories(o.getParent());
            if (cmd.contains("sb0")) {
                Files.write(o.resolveSibling("sb.mhtml"), sb);
                Files.writeString(o.resolveSibling("sb.info.json"), "{\"duration\": 30, \"formats\": [{\"format_id\": \"sb1\", \"rows\": 10, \"columns\": 10, \"fps\": 0.1}, {\"format_id\": \"sb0\", \"rows\": 2, \"columns\": 2, \"width\": 32, \"height\": 18, \"fps\": 0.2}]}");
            } else Files.writeString(o, "not really a video");
            return new Video.Result(0, "");
        };
        VideoRecall.FFMPEG = (cmd, t) -> {   // a WebP sheet opened; the changes of scene cut from the small copy; or a frame cut at full size: -ss <second>
            if (cmd.get(cmd.indexOf("-i") + 1).endsWith(".webp")) return decode(cmd);
            if (cmd.stream().anyMatch(c -> c.startsWith("select="))) {   // one change of scene, at 12 s, a shot the small frames do not show
                Files.write(Path.of(cmd.get(cmd.size() - 1)).resolveSibling("s-0001.jpg"), sheet(ORANGE, ORANGE, ORANGE, ORANGE));
                return new Video.Result(0, "[Parsed_showinfo_2 @ 0x1] n:   0 pts:  12000 pts_time:12.0 pos: 1 fmt:yuv420p");
            }
            int ss = cmd.indexOf("-ss");
            if (ss < 0) return new Video.Result(1, "not expected: " + cmd);
            int c = CUT + Integer.parseInt(cmd.get(ss + 1));   // a frame cut at full size: a picture whose colour says its second
            Files.write(Path.of(cmd.get(cmd.size() - 1)), sheet(c, c, c, c));
            return new Video.Result(0, "");
        };
        List<Integer> read = new ArrayList<>();
        ImageText.use((png, hint) -> {
            try {
                int c = colourOf(png);
                read.add(c); return c == RED ? "STEP 2\nAttach the lid" : "PHOTO: a plain " + name(c) + " picture";
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        List<String> asks = new ArrayList<>();
        VideoRecall.VISION = (img, ask) -> {
            asks.add(ask);
            if (ask.startsWith("This picture is two colours")) return "Red and blue.";
            if (ask.startsWith("What is on screen")) {
                try { return switch (colourOf(img)) { case RED -> "text on screen over a red kettle on a stove: STEP 2 Attach the lid"; case BLUE -> "a kitchen window and a garden beyond it"; case GREEN -> "a man in a blue apron holding a cup"; case YELLOW -> "a lemon on a plate"; case ORANGE -> "a close shot of a zipper with chewing gum stuck between its teeth"; default -> "a plain wall"; }; } catch (Exception e) { throw new RuntimeException(e); }
            }
            try { if (colourOf(img) == ORANGE) return "A zipper with chewing gum between its teeth.\nFound"; } catch (Exception ignored) { }
            int at = cutAt(img);
            if (at == 10) return "A red kettle stands on the stove, as remembered.\nFound";
            return "A plain wall.\nNo";
        };
        List<String> phases = new ArrayList<>();
        String answer = VideoRecall.picture(store, "a red kettle on a stove", List.of("thing"), phases::add, () -> false);
        assertTrue(answer.startsWith("Found it: Tea at home, at 0:10.") || answer.startsWith("Found it: Fixing a bicycle chain, at 0:10."), answer);
        assertTrue(answer.contains("&t=10s") && answer.contains("A red kettle stands on the stove") && answer.contains("Looked with " + VideoRecall.modelName() + ": 2 videos new to it, 12 small frames read for their words and 12 described, 2 shots between the frames described"), answer);
        assertEquals(List.of(BLUE, GREEN, RED, YELLOW, WHITE, BLACK), read.subList(0, 6), "every tile read but the one that shows the same picture as the last, and the one past the end: " + read);
        assertTrue(asks.stream().anyMatch(a -> a.startsWith("What is on screen") && a.contains("They are looking for thing.")), "the kinds steer the model");
        assertTrue(asks.stream().anyMatch(a -> a.contains("one word: found when the frame shows what the person remembers")), "the second look asks the person's own question");
        assertEquals("found", VideoRecall.verdictOf("The frame shows a kettle.\n**Found**")); assertEquals("no", VideoRecall.verdictOf("No, there is a zipper but no gum.")); assertEquals("maybe", VideoRecall.verdictOf("A photo of a man.\nmaybe - the name is not shown")); assertEquals("", VideoRecall.verdictOf(""));
        assertTrue(phases.stream().anyMatch(p -> p.startsWith("Reading the frames of")) && phases.stream().anyMatch(p -> p.startsWith("Looking again at 0:10 of")), phases.toString());
        // the storyboards fetched once per video, one small copy fetched for the second look, the frame kept at full size
        assertEquals(2, ran.stream().filter(c -> c.contains("sb0")).count(), "the storyboard of each video, once");
        assertEquals(2, ran.stream().filter(c -> c.contains(VideoRecall.SMALL)).count(), "a small copy of each video, fetched once for the shots between the frames, and not again for the second look: " + ran);
        assertEquals("12\n", Files.readString(tmp.resolve("video").resolve("frames").resolve("aaaaaaaaaaa").resolve("scenes.txt")), "the changes of scene are kept by their second, found once per video");
        Path kept = tmp.resolve("video").resolve("storyboards").resolve("aaaaaaaaaaa");
        assertTrue(Files.exists(kept.resolve("sheet-001.webp")) && Files.exists(kept.resolve("sheets.json")) && !Files.exists(kept.resolve("sheet-001.png")) && !Files.exists(kept.resolve("sheet-001.opened.png")), "the sheets are kept as they came, opened when read: " + kept);
        assertFalse(Files.exists(tmp.resolve("video").resolve("tmp").resolve("aaaaaaaaaaa.mp4")) || Files.exists(tmp.resolve("video").resolve("tmp").resolve("bbbbbbbbbbb.mp4")), "the fetched copy is deleted when the search ends");
        // the three tracks, searched the same way; the videos held say which model read them
        List<VideoRecall.Held> held = VideoRecall.held(store);
        assertTrue(held.stream().allMatch(h -> h.shownBy().contains(VideoRecall.modelKey()) && h.seenBy().contains(VideoRecall.modelKey()) && h.scenesBy().contains(VideoRecall.modelKey())), held.toString());
        assertTrue(VideoRecall.saw(store, "zipper with chewing gum", 5).stream().anyMatch(h -> h.kind().equals("shot") && h.seconds() == 12), "a shot between the frames is at its own second, searched like the rest");
        assertTrue(Files.isRegularFile(tmp.resolve("video").resolve("frames").resolve("aaaaaaaaaaa").resolve("t-12s.jpg")), "a shot's frame is kept as its own, apart from a frame cut at that second");
        List<VideoRecall.Hit> shown = VideoRecall.saw(store, "attach the lid", 5);
        assertTrue(shown.stream().anyMatch(h -> h.kind().equals("shown") && h.seconds() == 10 && h.line().equals("STEP 2 / Attach the lid")), shown.toString());
        assertTrue(VideoRecall.sawText(shown, "attach the lid").contains("words on screen: STEP 2 / Attach the lid"));
        assertTrue(VideoRecall.saw(store, "lemon on a plate", 5).stream().anyMatch(h -> h.kind().equals("seen") && h.seconds() == 20), "a tile on the second sheet is at its own second");
        assertTrue(VideoRecallPage.body(store, true, "").contains("2 with the words on screen, 2 with what is seen") && VideoRecallPage.body(store, true, "").contains("<td>" + VideoRecall.modelKey() + "</td>"));
        assertEquals(VideoRecall.modelName(), VideoRecall.kept(store).get(0).path("model").asText());
        // a second search reads nothing again: the tracks are kept, and so is the frame cut at full size
        ran.clear(); read.clear();
        String again = VideoRecall.picture(store, "a man in a blue apron", List.of("person"), phases::add, () -> false);
        assertTrue(ran.stream().noneMatch(c -> c.contains("sb0")) && read.isEmpty(), "no second reading");
        assertTrue(again.contains("no video new to it"), again);
        assertTrue(ran.stream().filter(c -> c.contains(VideoRecall.SMALL)).count() <= 2, "a copy fetched again only for a video with a moment not yet cut, once per video: " + ran);
        // a shot between the frames is found like any moment, its frame already kept at full size
        String shot = VideoRecall.picture(store, "a zipper with chewing gum", List.of("thing"), phases::add, () -> false);
        assertTrue(shot.startsWith("Found it:") && shot.contains("at 0:12") && shot.contains("&t=12s"), shot);
        // another model's reading of the same video stays out of the search for a video this model has read, and counts for one it has not
        RawCapture.capture(store, TEA + "&seen=other-model", "title: Tea at home (what is on screen)\naddress: " + TEA + "\n\nwhat is on screen:\n[0:05] a lemon on a plate, as the other model puts it\n", "Tea at home (what is on screen)", "researchzosho-video", "", "");
        RawCapture.capture(store, "https://www.youtube.com/watch?v=ccccccccccc&seen=other-model", "title: Lemons (what is on screen)\naddress: https://www.youtube.com/watch?v=ccccccccccc\n\nwhat is on screen:\n[0:09] a bowl of lemons\n", "Lemons (what is on screen)", "researchzosho-video", "", "");
        List<VideoRecall.FrameLine> lines = VideoRecall.frameLines(store);
        assertTrue(lines.stream().noneMatch(l -> l.line().contains("as the other model puts it")) && lines.stream().anyMatch(l -> l.videoId().equals("ccccccccccc") && l.seconds() == 9), lines.toString());
    }

    @Test void aVideoWithoutAStoryboardHasFramesCutFromASmallCopy(@TempDir Path tmp) throws Exception {
        LibraryStore store = storeWithTwoVideos(tmp);
        VideoRecall.workDir = tmp.resolve("video");
        VideoRecall.EMBEDDER = Embeddings::none;   // no embedder here: the frames are ranked by words
        Video.installedProbe = () -> true; Video.providerProbe = () -> true; Video.readPaceMs = 0;
        List<List<String>> ran = new ArrayList<>();
        Video.runner = (cmd, t) -> {
            ran.add(cmd);
            if (cmd.contains("sb0")) return new Video.Result(1, "ERROR: [youtube] aaaaaaaaaaa: Requested format is not available.");
            int o = cmd.indexOf("-o"); Files.createDirectories(Path.of(cmd.get(o + 1)).getParent()); Files.writeString(Path.of(cmd.get(o + 1)), "not really a video"); return new Video.Result(0, "");
        };
        VideoRecall.FFMPEG = (cmd, t) -> {
            if (cmd.get(0).equals("ffprobe")) return new Video.Result(0, "45.0\n");   // a 45-second video: a frame every 15 s, three of them
            Path pattern = Path.of(cmd.get(cmd.size() - 1));
            if (pattern.getFileName().toString().startsWith("s-")) { Files.writeString(pattern.resolveSibling("s-0001.jpg"), "frame-scene"); return new Video.Result(0, "[Parsed_showinfo_2 @ 0x1] n:   0 pts:  77 pts_time:38.5 pos:1\n"); }
            for (int i = 1; i <= 3; i++) Files.writeString(pattern.resolveSibling(String.format("f-%04d.jpg", i)), "frame" + i);
            return new Video.Result(0, "");
        };
        List<String> asks = new ArrayList<>();
        VideoRecall.VISION = (img, ask) -> {
            asks.add(ask);
            String frame = new String(img, StandardCharsets.UTF_8);
            if (ask.startsWith("This picture is two colours")) return "Red and blue.";
            if (ask.startsWith("What is on screen")) return switch (frame) { case "frame1" -> "a kitchen window and a garden beyond it"; case "frame2" -> "a red kettle on a wooden table, steam rising"; case "frame-scene" -> "a close-up of a teapot lid"; default -> "a man in a blue apron holding a cup"; };
            return frame.equals("frame2") ? "Found. A red kettle stands on a wooden table, as remembered." : "No. There is no kettle in this frame.";
        };
        List<String> phases = new ArrayList<>();
        String answer = VideoRecall.picture(store, "a red kettle on a wooden table", List.of("thing"), phases::add, () -> false);
        assertTrue(answer.startsWith("Found it: Tea at home, at 0:15.") || answer.startsWith("Found it: Fixing a bicycle chain, at 0:15."), answer);
        assertTrue(answer.contains("&t=15s") && answer.contains("A red kettle stands on a wooden table"), answer);
        assertTrue(Files.exists(tmp.resolve("video").resolve("storyboards").resolve("aaaaaaaaaaa").resolve("none.txt")), "a video with no storyboard is remembered as such");
        assertEquals(2, ran.stream().filter(c -> c.contains(VideoRecall.SMALL)).count(), "each video fetched once, small");
        List<VideoRecall.Held> held = VideoRecall.held(store);
        assertTrue(held.stream().allMatch(VideoRecall.Held::seen) && held.stream().noneMatch(VideoRecall.Held::shown), "what is seen is kept for both; the frames here are not pictures, so no words were read: " + held);
        List<VideoRecall.Hit> seen = VideoRecall.saw(store, "red kettle wooden table", 5);
        assertTrue(seen.stream().anyMatch(h -> h.kind().equals("seen") && h.seconds() == 15 && h.line().startsWith("a red kettle on a wooden table")), seen.toString());
        assertTrue(VideoRecall.saw(store, "teapot lid", 5).stream().anyMatch(h -> h.kind().equals("seen") && h.seconds() == 39), "a frame at a change of scene is kept by its own second, 38.5 rounded");
        assertTrue(Files.exists(tmp.resolve("video").resolve("frames").resolve("aaaaaaaaaaa").resolve("t-39.jpg")) && Files.exists(tmp.resolve("video").resolve("frames").resolve("aaaaaaaaaaa").resolve("t-15.jpg")));
        assertEquals(1, VideoRecall.kept(store).size()); assertTrue(VideoRecall.kept(store).get(0).path("answer").asText().startsWith("Found it"));
        // a second look does not fetch again: the frames are kept, their lines are on the shelves
        ran.clear();
        VideoRecall.picture(store, "a man in a blue apron", List.of("person"), phases::add, () -> false);
        assertTrue(ran.isEmpty(), "no second fetch: " + ran);
        assertFalse(Files.exists(tmp.resolve("video").resolve("tmp").resolve("aaaaaaaaaaa.mp4")), "the fetched copy is deleted once the frames are cut");
    }

    @Test void withoutAModelThatReadsPicturesItSaysSoAndAnswersFromWhatWasSaid(@TempDir Path tmp) throws Exception {
        LibraryStore store = storeWithTwoVideos(tmp);
        VideoRecall.workDir = tmp.resolve("video");
        VideoRecall.VISION = (img, ask) -> "The picture shows a sunset.";   // an answer that does not name the colours: a model that does not read pictures, answering the words
        VideoRecall.forgetVision();
        String answer = VideoRecall.picture(store, "the blue cups on the table", List.of(), sentence -> { }, () -> false);
        assertTrue(answer.startsWith("No model that reads pictures answers") && answer.contains("&t=65s"), answer);
        assertTrue(VideoRecallPage.body(store, true, "").contains("none answers now, so it looks through what was said instead"));
        // filed as a run, the sentence says what it will do; a second filing says one is waiting
        String filed = VideoRecall.file(store, "a red kettle", List.of("thing"), "person");
        assertTrue(filed.contains("It is filed as J-") && filed.contains("reads YouTube's own small frames"), filed);
        assertTrue(VideoRecall.file(store, "a red kettle", List.of(), "person").startsWith("A search for a picture is already waiting to start"));
        assertTrue(VideoRecallPage.running(store).startsWith("A search for a picture is waiting to start"));
    }

    @Test void theStoryboardFileIsCutIntoTilesEachWithItsSecond(@TempDir Path tmp) throws Exception {
        byte[] first = sheet(BLUE, GREEN, RED, RED), second = sheet(YELLOW, WHITE, BLACK, GRAY);
        List<byte[]> images = Storyboards.images(mhtml(first, second));
        assertEquals(2, images.size());
        assertTrue(Arrays.equals(first, images.get(0)) && Arrays.equals(second, images.get(1)), "each sheet's bytes whole, the line ending after it left out");
        Files.createDirectories(tmp);
        Path a = Storyboards.keepSheet(first, tmp, 0), b = Storyboards.keepSheet(webp(second), tmp, 1);
        assertEquals("sheet-000.png", a.getFileName().toString()); assertEquals("sheet-001.webp", b.getFileName().toString());
        assertEquals(second.length + 12, Files.size(b), "a WebP is kept as its own bytes");
        assertNull(Storyboards.keepSheet("not a picture".getBytes(StandardCharsets.UTF_8), tmp, 2));
        Video.Runner ffmpeg = (cmd, t) -> cmd.get(cmd.indexOf("-i") + 1).endsWith(".webp") ? decode(cmd) : new Video.Result(1, "not needed for a PNG");
        List<Storyboards.Tile> tiles = Storyboards.tiles(new Storyboards.Sheets(2, 2, 0.2, 30, List.of(a, b)), ffmpeg);
        assertFalse(Files.exists(tmp.resolve("sheet-001.opened.png")), "the opened copy is deleted once read");
        assertEquals(List.of(0, 5, 10, 15, 20, 25, 30), tiles.stream().map(Storyboards.Tile::seconds).toList(), "the eighth tile is past the end");
        assertEquals(RED, tiles.get(2).image().getRGB(5, 5) & 0xFFFFFF);
        assertTrue(Storyboards.same(tiles.get(2).image(), tiles.get(3).image()) && !Storyboards.same(tiles.get(1).image(), tiles.get(2).image()));
        assertEquals(5, new Storyboards.Sheets(3, 3, 0.2017, 704, List.of()).gap());
        assertEquals(5.0, new Storyboards.Sheets(5, 5, 0.2042, 431, List.of()).step(), "frames are taken at whole seconds; the rate reported drifts");
        assertEquals(2.0, new Storyboards.Sheets(3, 3, 0.5132, 152, List.of()).step()); assertEquals(10.0, new Storyboards.Sheets(5, 5, 0.1006, 2366, List.of()).step());
        assertEquals(1 / 0.3, new Storyboards.Sheets(3, 3, 0.3, 100, List.of()).step(), 1e-9, "a rate that is not near a whole second is kept as it is");
        List<Storyboards.Tile> drifted = Storyboards.tiles(new Storyboards.Sheets(2, 2, 0.2042, 431, List.of(a, b)), ffmpeg);
        assertEquals(List.of(0, 5, 10, 15, 20, 25, 30, 35), drifted.stream().map(Storyboards.Tile::seconds).toList());
        assertTrue(tiles.get(0).png(400).length > 0 && colourOf(tiles.get(0).png(400)) == BLUE, "scaled up, the tile is still its colour");
        assertNull(Storyboards.kept("zzzzzzzzzzz", tmp));
        Path dir = Storyboards.dir(tmp, "yyyyyyyyyyy"); Files.createDirectories(dir);
        Files.copy(a, dir.resolve("sheet-000.png")); Files.copy(b, dir.resolve("sheet-001.webp")); Files.writeString(dir.resolve("sheets.json"), "{\"rows\":2,\"columns\":2,\"fps\":0.2,\"duration\":30,\"sheets\":2}");
        assertEquals(List.of("sheet-000.png", "sheet-001.webp"), Storyboards.kept("yyyyyyyyyyy", tmp).files().stream().map(p -> p.getFileName().toString()).toList(), "sheets kept as PNG by an earlier reading and as WebP sit side by side");
    }
}
