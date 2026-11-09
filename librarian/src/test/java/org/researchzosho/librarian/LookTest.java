package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.tools.ImageText;
import org.researchzosho.tools.LookTool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The look at one video not on the shelves: brought in, read sparsely then whole, each thing remembered judged, the tracks kept for the recall; the helper and the models stood in for. */
class LookTest {
    final Video.Runner realRunner = Video.runner;
    final BooleanSupplier realInstalled = Video.installedProbe, realProvider = Video.providerProbe;
    final long realPace = Video.readPaceMs;
    final VideoRecall.Vision realVision = VideoRecall.VISION;
    final VideoRecall.Words realWords = VideoRecall.WORDS;
    final Video.Runner realFfmpeg = VideoRecall.FFMPEG;
    final Supplier<Embeddings.Embedder> realEmbedder = VideoRecall.EMBEDDER;
    final Path realWork = VideoRecall.workDir;
    @AfterEach void back() { Video.runner = realRunner; Video.installedProbe = realInstalled; Video.providerProbe = realProvider; Video.readPaceMs = realPace; VideoRecall.VISION = realVision; VideoRecall.WORDS = realWords; VideoRecall.FFMPEG = realFfmpeg; VideoRecall.EMBEDDER = realEmbedder; VideoRecall.workDir = realWork; ImageText.use(null); VideoRecall.forgetVision(); }

    static final String ID = "ccccccccccc", TOTE = "https://www.youtube.com/watch?v=" + ID;
    static final int[] COLOURS = {VideoRecallTest.BLUE, VideoRecallTest.GREEN, VideoRecallTest.RED, VideoRecallTest.YELLOW, VideoRecallTest.WHITE, VideoRecallTest.BLACK, VideoRecallTest.GRAY, VideoRecallTest.ORANGE};

    @Test void aVideoNotOnTheShelvesIsBroughtInReadSparselyThenWholeAndEachThingRememberedIsJudged(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        VideoRecall.workDir = tmp.resolve("video");
        VideoRecall.EMBEDDER = Embeddings::none;
        Video.installedProbe = () -> true; Video.providerProbe = () -> true; Video.readPaceMs = 0;
        // a two-and-a-half-minute video: eight tiles twenty seconds apart (0, 20, 40, 60 in the first minute; 80, 100, 120, 140 after), each its own picture
        byte[] sb = VideoRecallTest.mhtml(VideoRecallTest.webp(VideoRecallTest.sheet(COLOURS[0], COLOURS[1], COLOURS[2], COLOURS[3])), VideoRecallTest.webp(VideoRecallTest.sheet(COLOURS[4], COLOURS[5], COLOURS[6], COLOURS[7])));
        List<List<String>> ran = new ArrayList<>();
        Video.runner = (cmd, t) -> {
            ran.add(cmd);
            if (cmd.contains("--dump-single-json")) return new Video.Result(0, "{\"id\": \"" + ID + "\", \"title\": \"How to sew a simple bag\", \"duration\": 150, \"upload_date\": \"20240330\", \"channel\": \"homebox crafts\", \"language\": \"en\", \"subtitles\": {}, \"automatic_captions\": {}}");
            Path o = Path.of(cmd.get(cmd.indexOf("-o") + 1));
            Files.createDirectories(o.getParent());
            if (cmd.contains("sb0")) {
                Files.write(o.resolveSibling("sb.mhtml"), sb);
                Files.writeString(o.resolveSibling("sb.info.json"), "{\"duration\": 150, \"formats\": [{\"format_id\": \"sb0\", \"rows\": 2, \"columns\": 2, \"fps\": 0.05, \"width\": 64, \"height\": 36}]}");
            } else Files.writeString(o, "not really a video");
            return new Video.Result(0, "");
        };
        VideoRecall.FFMPEG = (cmd, t) -> {
            if (cmd.get(cmd.indexOf("-i") + 1).endsWith(".webp")) return VideoRecallTest.decode(cmd);
            if (cmd.stream().anyMatch(c -> c.startsWith("select="))) return new Video.Result(0, "");   // no change of scene between the frames
            int ss = cmd.indexOf("-ss");
            if (ss < 0) return new Video.Result(1, "not expected: " + cmd);
            int c = VideoRecallTest.CUT + Integer.parseInt(cmd.get(ss + 1));
            Files.write(Path.of(cmd.get(cmd.size() - 1)), VideoRecallTest.sheet(c, c, c, c));
            return new Video.Result(0, "");
        };
        List<Integer> read = new ArrayList<>();
        ImageText.use((png, hint) -> {
            try { int c = VideoRecallTest.colourOf(png); read.add(c); return c == VideoRecallTest.RED ? "40cm × 35cm\nfabric" : "PHOTO: a plain " + VideoRecallTest.name(c) + " picture"; }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        List<String> asks = new ArrayList<>();
        VideoRecall.VISION = (img, ask) -> {
            asks.add(ask);
            if (ask.startsWith("This picture is two colours")) return "Red and blue.";
            try {
                int c = VideoRecallTest.colourOf(img);
                if (ask.startsWith("What is on screen")) return switch (c) {
                    case VideoRecallTest.RED -> "text on screen over a piece of fabric: 40cm × 35cm";
                    case VideoRecallTest.GREEN -> "hands sewing a pocket onto the inside of a bag";
                    case VideoRecallTest.BLUE -> "a sewing machine on a table";
                    default -> "a plain " + VideoRecallTest.name(c) + " picture";
                };
                if (ask.contains("dimensions") && (c == VideoRecallTest.RED || VideoRecallTest.cutAt(img) == 40)) return "The frame shows a piece of fabric with 40cm × 35cm written over it.\nFound";
                if (ask.contains("pocket") && (c == VideoRecallTest.GREEN || VideoRecallTest.cutAt(img) == 20)) return "Hands sew a pocket onto the inside of the bag.\nFound";
            } catch (Exception ignored) { }
            return "A plain picture, nothing of the kind.\nNo";
        };
        VideoRecall.WORDS = ask -> "no talking or voiceover\nthe fabric dimensions on screen at the beginning\nan inside pocket\nno snap closure";
        List<String> phases = new ArrayList<>();
        String what = "no talking or voiceover; the fabric dimensions on screen at the beginning; an inside pocket; no snap closure";
        VideoRecall.Look look = VideoRecall.lookAt(store, TOTE, what, true, phases::add, () -> false);
        assertEquals(ID, look.videoId()); assertEquals("How to sew a simple bag", look.title());
        assertEquals(4, look.answers().size(), look.text());
        VideoRecall.Answer silent = look.answers().get(0), dims = look.answers().get(1), pocket = look.answers().get(2), snap = look.answers().get(3);
        assertEquals("found", silent.verdict(), silent.toString()); assertTrue(silent.why().startsWith("no captions and no speech"), silent.why());
        assertEquals("found", dims.verdict(), dims.toString()); assertEquals(40, dims.seconds()); assertTrue(dims.why().contains("40cm × 35cm") && dims.why().contains(" On screen there: "), dims.why());
        assertEquals("found", pocket.verdict(), pocket.toString()); assertEquals(20, pocket.seconds());
        assertEquals("found", snap.verdict(), snap.toString()); assertTrue(snap.why().startsWith("nothing on screen shows snap closure"), snap.why());   // a thing said to be absent: not seen anywhere, so the absence is found
        // sparse: the first minute whole (0, 20, 40, 60), then every fourth (80): five of the eight frames read; the reading is kept under the model's sparse key
        assertEquals(5, read.size(), "the first minute whole and then every fourth: " + read);
        assertTrue(look.read().startsWith("Read 5 small frames in ") && look.read().contains("the first minute whole and then every fourth frame") && look.read().contains("looked at for what you remember"), look.read());
        VideoRecall.Held held = VideoRecall.heldOne(store, ID);
        assertTrue(held != null && held.seenBy().contains(VideoRecall.modelKey() + "-sparse") && !held.seenBy().contains(VideoRecall.modelKey()), String.valueOf(held));
        assertEquals(1, ran.stream().filter(c -> c.contains("--dump-single-json")).count(), "the video was brought in once");
        assertTrue(phases.stream().anyMatch(p -> p.startsWith("Reading the video's page")) && phases.stream().anyMatch(p -> p.startsWith("Reading the frames of")) && phases.stream().anyMatch(p -> p.startsWith("Looking at 0:40 for:")), phases.toString());
        // the recall finds what the look read
        assertTrue(VideoRecall.saw(store, "sewing a pocket inside", 5).stream().anyMatch(h -> h.videoId().equals(ID) && h.seconds() == 20), "the look's lines are the recall's");
        // a second sparse look reads nothing again; a whole look reads every frame and is kept under the model's own key
        read.clear();
        VideoRecall.Look again = VideoRecall.lookAt(store, ID, what, true, phases::add, () -> false);
        assertTrue(again.read().startsWith("Already read by " + VideoRecall.modelName() + ", every fourth frame"), again.read());
        assertEquals(0, read.size(), "nothing read again");
        VideoRecall.Look whole = VideoRecall.lookAt(store, TOTE, what, false, phases::add, () -> false);
        assertEquals(8, read.size(), "every frame read whole: " + read);
        assertTrue(whole.read().startsWith("Read 8 small frames in ") && !whole.read().contains("every fourth"), whole.read());
        held = VideoRecall.heldOne(store, ID);
        assertTrue(held.seenBy().contains(VideoRecall.modelKey()), String.valueOf(held));
        assertEquals("found", whole.answers().get(1).verdict(), whole.text());
        // the text a tool or a page shows
        String text = whole.text();
        assertTrue(text.startsWith("Looked at How to sew a simple bag (" + TOTE + "):") && text.contains("the fabric dimensions on screen at the beginning: found at 0:40 — ") && text.contains(TOTE + "&t=40s") && text.contains("no snap closure: found — nothing on screen shows snap closure"), text);
        // the tool
        ObjectNode args = new ObjectMapper().createObjectNode().put("video", TOTE).put("what", what).put("sparse", true);
        String out = LookTool.run(store, args);
        assertTrue(out.startsWith("Looked at How to sew a simple bag") && out.contains("Already read by"), out);
        assertTrue(LookTool.run(store, new ObjectMapper().createObjectNode().put("video", "not a video").put("what", what)).startsWith("ERROR: Give the video's address"));
        Video.installedProbe = () -> false;
        assertTrue(LookTool.run(store, args).startsWith("ERROR: the video helper is not installed"));
        LookTool tool = new LookTool();
        assertEquals("look", tool.name()); assertTrue(tool.description().contains("Costs about seven minutes per ten minutes of video") && tool.description().contains("Example: {"));
        assertTrue(tool.parametersSchema(new ObjectMapper()).path("required").toString().contains("what"));
    }

    @Test void aLookWhosePictureReaderStopsAnsweringGivesUpAfterTwoAndKeepsNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        VideoRecall.workDir = tmp.resolve("video");
        VideoRecall.EMBEDDER = Embeddings::none;
        Video.installedProbe = () -> true; Video.providerProbe = () -> true; Video.readPaceMs = 0;
        byte[] sb = VideoRecallTest.mhtml(VideoRecallTest.webp(VideoRecallTest.sheet(COLOURS[0], COLOURS[1], COLOURS[2], COLOURS[3])), VideoRecallTest.webp(VideoRecallTest.sheet(COLOURS[4], COLOURS[5], COLOURS[6], COLOURS[7])));
        Video.runner = (cmd, t) -> {
            if (cmd.contains("--dump-single-json")) return new Video.Result(0, "{\"id\": \"" + ID + "\", \"title\": \"How to sew a simple bag\", \"duration\": 150, \"upload_date\": \"20240330\", \"channel\": \"homebox crafts\"}");
            Path o = Path.of(cmd.get(cmd.indexOf("-o") + 1));
            Files.createDirectories(o.getParent());
            if (cmd.contains("sb0")) {
                Files.write(o.resolveSibling("sb.mhtml"), sb);
                Files.writeString(o.resolveSibling("sb.info.json"), "{\"duration\": 150, \"formats\": [{\"format_id\": \"sb0\", \"rows\": 2, \"columns\": 2, \"fps\": 0.05, \"width\": 64, \"height\": 36}]}");
            } else Files.writeString(o, "not really a video");
            return new Video.Result(0, "");
        };
        VideoRecall.FFMPEG = (cmd, t) -> cmd.get(cmd.indexOf("-i") + 1).endsWith(".webp") ? VideoRecallTest.decode(cmd) : new Video.Result(0, "");
        List<Integer> read = new ArrayList<>();
        ImageText.use((png, hint) -> { read.add(1); return ""; });
        List<String> described = new ArrayList<>();
        VideoRecall.VISION = (img, ask) -> {
            if (ask.startsWith("This picture is two colours")) return "Red and blue.";   // the reader answered the probe, then stopped answering
            described.add(ask);
            return "";
        };
        VideoRecall.WORDS = ask -> "no talking or voiceover\nan inside pocket";
        BooleanSupplier realUnanswered = VideoRecall.UNANSWERED;
        VideoRecall.UNANSWERED = () -> true;   // every picture call after the probe went unanswered
        try {
            VideoRecall.Look look = VideoRecall.lookAt(store, TOTE, "no talking or voiceover; an inside pocket", true, p -> { }, () -> false);
            assertTrue(look.read().startsWith("The model that reads pictures (") && look.read().contains("did not answer twice in a row") && look.read().contains("nothing was kept"), look.read());
            assertEquals(1, read.size(), "the frame's words asked for once");
            assertEquals(1, described.size(), "and the frame described once: two unanswered in a row, and nothing more is waited for");
            VideoRecall.Answer pocket = look.answers().get(1);
            assertEquals("", pocket.verdict(), pocket.toString());
            assertEquals("the model that reads pictures did not answer, so this was not looked at", pocket.why());
            VideoRecall.Held held = VideoRecall.heldOne(store, ID);
            assertTrue(held == null || (!held.seenBy().contains(VideoRecall.modelKey() + "-sparse") && !held.shownBy().contains(VideoRecall.modelKey() + "-sparse")), "nothing half read is kept, so a later look reads it again: " + held);
        } finally { VideoRecall.UNANSWERED = realUnanswered; }
    }

    @Test void severalCandidatesAreLookedAtTogetherAndListedBestFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Video.installedProbe = () -> true;
        LookTool.Looker real = LookTool.LOOKER;
        List<String> looked = new ArrayList<>();
        LookTool.LOOKER = (st, video, what, sparse, stop) -> {
            looked.add(video + (sparse ? " sparse" : " whole"));
            String id = video.substring(video.length() - 11);
            boolean right = id.equals("bbbbbbbbbbb");
            return new VideoRecall.Look(id, right ? "The right bag" : "Another bag", List.of(
                    new VideoRecall.Answer("no talking", "found", 0, "no speech"),
                    new VideoRecall.Answer("the dimensions on screen at the start", right ? "found" : "no", right ? 30 : 0, right ? "40cm × 35cm at 0:30" : "first sizes at 1:20"),
                    new VideoRecall.Answer("an inside pocket", right ? "found" : "maybe", right ? 300 : 0, "")), "Read 20 small frames.");
        };
        try {
            String out = LookTool.runMany(store, List.of("https://www.youtube.com/watch?v=aaaaaaaaaaa", "https://www.youtube.com/watch?v=bbbbbbbbbbb", "https://www.youtube.com/watch?v=aaaaaaaaaaa",
                    "ccccccccccc", "ddddddddddd", "eeeeeeeeeee", "fffffffffff"), "no talking; the dimensions on screen at the start; an inside pocket", true);
            assertEquals(5, looked.size(), "five at most, the same one once: " + looked);
            assertTrue(looked.stream().allMatch(l -> l.endsWith(" sparse")), "sparse unless told");
            assertTrue(out.startsWith("Looked at 5 videos for: no talking; the dimensions on screen at the start; an inside pocket. Best first"), out);
            assertTrue(out.indexOf("The right bag") < out.indexOf("Another bag"), "the one with the most things found leads: " + out);
            assertTrue(out.contains("3 of 3 found") && out.contains("1 of 3 found, 1 maybe"), out);
            assertTrue(out.contains("Not looked at: fffffffffff (one look takes 5 at most)"), out);
            // the brain's look takes the candidates together in videos
            ObjectNode args = new ObjectMapper().createObjectNode().put("what", "no talking").put("sparse", true);
            args.putArray("videos").add("https://www.youtube.com/watch?v=bbbbbbbbbbb").add("https://www.youtube.com/watch?v=aaaaaaaaaaa");
            looked.clear();
            Instrument look = Instruments.look(store);
            assertTrue(look.schema(new ObjectMapper()).path("properties").path("videos").path("type").asText().equals("array"));
            String text = look.run(args, null, () -> false).observation();
            assertEquals(2, looked.size(), looked.toString());
            assertTrue(text.startsWith("Looked at 2 videos for: no talking."), text);
        } finally { LookTool.LOOKER = real; }
    }

    /** The thing a feature says is absent, or null when the feature says no such thing. */
    static String absent(String feature) { var m = VideoRecall.ABSENT.matcher(feature); return m.matches() ? m.group(1) : null; }

    @Test void whetherAnyoneTalksIsReadFromWhatWasSaid() {
        String talk = "title: A talk\naddress: https://www.youtube.com/watch?v=ddddddddddd\nlength: 2:00\n\ntranscript (en, from the captions):\n" + "[0:03] today we sew a bag from canvas and interfacing and a pocket\n".repeat(20);
        VideoRecall.Answer a = VideoRecall.judgeSilence("no talking", talk);
        assertEquals("no", a.verdict()); assertEquals(3, a.seconds()); assertTrue(a.why().startsWith("someone talks: 20 lines of speech, about 120 words a minute"), a.why());
        String music = "title: Music only\naddress: https://www.youtube.com/watch?v=eeeeeeeeeee\nlength: 10:00\n\ntranscript (en, from YouTube's automatic captions):\n[0:01] [Music]\n[4:10] ♪\n[8:00] thank you\n";
        a = VideoRecall.judgeSilence("silent, no voiceover", music);
        assertEquals("found", a.verdict()); assertTrue(a.why().startsWith("the captions hold 2 words over 10:00, music or a stray word"), a.why());
        a = VideoRecall.judgeSilence("no talking", "title: No words\naddress: https://www.youtube.com/watch?v=fffffffffff\nlength: 3:00\ncaptions: none by the uploader\n");
        assertEquals("found", a.verdict()); assertTrue(a.why().startsWith("no captions and no speech"), a.why());
        a = VideoRecall.judgeSilence("no talking", "title: Unread\naddress: https://www.youtube.com/watch?v=ggggggggggg\nlength: 3:00\ncaptions: none by the uploader; automatic in 3 languages\n");
        assertEquals("maybe", a.verdict(), a.why());
        // the features, without the model: the clauses between semicolons
        VideoRecall.WORDS = ask -> "";
        try { assertEquals(List.of("no talking", "a pocket inside", "no snap"), VideoRecall.features("no talking; a pocket inside; no snap")); assertEquals(List.of("a red kettle", "a blue cup"), VideoRecall.features("a red kettle, a blue cup")); }
        finally { VideoRecall.WORDS = realWords; }
        assertEquals("snap closure", absent("no snap closure")); assertEquals("zipper", absent("it does not have a zipper.")); assertEquals("lining", absent("without a lining")); assertEquals(null, absent("an inside pocket"));
        assertFalse(VideoRecall.SILENCE.matcher("an inside pocket").find()); assertTrue(VideoRecall.SILENCE.matcher("no talking or voiceover").find()); assertTrue(VideoRecall.AT_START.matcher("the dimensions on screen at the beginning").find());
    }
}
