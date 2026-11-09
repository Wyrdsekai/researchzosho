package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.profiles.YoutubeProfile;
import org.researchzosho.mcp.McpServer;
import org.researchzosho.tools.ImageText;
import org.researchzosho.tools.Tool;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The funnel between the pool and the five a look can afford: every candidate's thumbnail, nine to a call as one numbered grid, then the
 * first sheet of those that pass, each judged yes, maybe or no; a thumbnail's no never reaches the sheet pass; yes before maybe; nothing
 * judged or fetched twice, a grid's judgement and a single call's kept alike; the grid's answer read strictly, a number it lacks asked
 * alone; a model that cannot read a grid found out; one sift in a library at a time; the cut at the time said with the way on; the pace
 * and the way the thumbnails went said; the words on the table and the rules naming it. Nothing here touches the net, the helper or the
 * box: the pictures, the sheets, the model that reads them and the one that splits the words are all stood in for.
 */
class SiftTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    String realHome;
    final Sift.Pictures realPictures = Sift.PICTURES;
    final Sift.Sheets realSheets = Sift.SHEETS;
    final long realPace = Sift.paceMs;
    final VideoRecall.Vision realVision = VideoRecall.VISION;
    final VideoRecall.Words realWords = VideoRecall.WORDS;
    final Video.Runner realFfmpeg = VideoRecall.FFMPEG;
    final Supplier<Embeddings.Embedder> realEmbedder = VideoRecall.EMBEDDER;
    final Path realWork = VideoRecall.workDir;
    final BooleanSupplier realUnanswered = VideoRecall.UNANSWERED;

    static final String V1 = "aaaaaaaaaa1", V2 = "aaaaaaaaaa2", V3 = "aaaaaaaaaa3", V4 = "aaaaaaaaaa4", V5 = "aaaaaaaaaa5", V6 = "aaaaaaaaaa6";
    /** The thumbnail's colour per video, and the first sheet's: red is a yes, yellow and green a maybe, blue a no. */
    static final Map<String, Integer> THUMB = Map.of(V1, VideoRecallTest.RED, V2, VideoRecallTest.YELLOW, V3, VideoRecallTest.BLUE, V4, VideoRecallTest.GREEN, V5, VideoRecallTest.RED, V6, VideoRecallTest.RED);
    static final Map<String, Integer> SHEET = Map.of(V1, VideoRecallTest.RED, V2, VideoRecallTest.RED, V4, VideoRecallTest.GREEN, V6, VideoRecallTest.BLUE);   // V5 has no storyboard; V3 must never be asked
    final List<String> fetched = new CopyOnWriteArrayList<>(), sheetsAsked = new CopyOnWriteArrayList<>(), thumbAsks = new CopyOnWriteArrayList<>(), sheetAsks = new CopyOnWriteArrayList<>();
    /** The cover pictures judged, within grids and alone. */
    final AtomicInteger thumbCells = new AtomicInteger(), singles = new AtomicInteger();

    @BeforeEach void standIns() throws Exception {
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        VideoRecall.workDir = tmp.resolve("video");
        VideoRecall.EMBEDDER = Embeddings::none;
        VideoRecall.UNANSWERED = () -> false;
        Sift.paceMs = 0;
        Sift.PICTURES = url -> {
            fetched.add(url);
            String id = url.replaceAll(".*/vi/([A-Za-z0-9_-]{11})/.*", "$1");
            Integer c = THUMB.get(id);
            if (c == null) throw new IOException("HTTP 404");
            return VideoRecallTest.sheet(c, c, c, c);
        };
        Sift.SHEETS = ids -> {
            sheetsAsked.addAll(ids);
            Map<String, Sift.FirstSheet> out = new LinkedHashMap<>();
            for (String id : ids) {
                if (id.equals(V5)) { out.put(id, null); continue; }   // YouTube keeps no storyboard for it
                Integer c = SHEET.get(id);
                if (c == null) continue;   // could not be had this time
                out.put(id, new Sift.FirstSheet(2, 2, 5, 30, VideoRecallTest.webp(VideoRecallTest.sheet(c, c, c, c))));
            }
            return out;
        };
        VideoRecall.FFMPEG = (cmd, t) -> cmd.get(cmd.indexOf("-i") + 1).endsWith(".webp") ? VideoRecallTest.decode(cmd) : new Video.Result(1, "not expected: " + cmd);
        VideoRecall.VISION = this::vision;
        VideoRecall.WORDS = ask -> "a red apron\nnobody speaks\nthe pattern pieces laid out at the start\nan inside pocket";
        ImageText.use(null);
        VideoRecall.forgetVision();
    }

    @AfterEach void back() {
        System.setProperty("user.home", realHome); Config.invalidate();
        Sift.PICTURES = realPictures; Sift.SHEETS = realSheets; Sift.paceMs = realPace;
        VideoRecall.VISION = realVision; VideoRecall.WORDS = realWords; VideoRecall.FFMPEG = realFfmpeg; VideoRecall.EMBEDDER = realEmbedder; VideoRecall.workDir = realWork; VideoRecall.UNANSWERED = realUnanswered;
        VideoRecall.forgetVision(); Instruments.SIFT = null;
    }

    /** The stand-in model that reads pictures: a grid of cover pictures answered one line per number by each cell's colour, a cover picture alone by its colour, a first sheet by its colour. */
    String vision(byte[] img, String ask) {
        if (ask.startsWith("This picture is two colours")) return "Red and blue.";
        if (ask.startsWith("This picture holds ")) {
            thumbAsks.add(ask);
            StringBuilder b = new StringBuilder();
            int n = cellsAsked(ask);
            for (int k = 0; k < n; k++) { thumbCells.incrementAndGet(); b.append(k + 1).append(": ").append(byColour(cellColour(img, k, n), true)).append('\n'); }
            return b.toString();
        }
        int c;
        try { c = VideoRecallTest.colourOf(img); } catch (Exception e) { throw new RuntimeException(e); }
        if (ask.contains("cover picture")) { thumbAsks.add(ask); thumbCells.incrementAndGet(); singles.incrementAndGet(); return byColour(c, false); }
        sheetAsks.add(ask); sheetColours.add(c);
        return switch (c) { case VideoRecallTest.RED -> "The frames show pattern pieces laid on a table.\nyes"; case VideoRecallTest.GREEN -> "Fabric on a table, perhaps pieces.\nmaybe"; default -> "A person talking to the camera.\nno"; };
    }

    /** The colour of each first sheet judged, in the order judged. */
    final List<Integer> sheetColours = new CopyOnWriteArrayList<>();

    static String byColour(int c, boolean inGrid) {
        return switch (c) {
            case VideoRecallTest.RED -> inGrid ? "yes — A red apron on a table." : "A red apron on a table.\nyes";
            case VideoRecallTest.GREEN -> inGrid ? "yes — A green apron held up." : "A green apron held up.\nYes";
            case VideoRecallTest.YELLOW -> inGrid ? "maybe — A yellow cloth, hard to tell." : "A yellow cloth, hard to tell.\nmaybe";
            default -> inGrid ? "no — A cat on a sofa." : "A cat on a sofa.\nNo";
        };
    }

    static int cellsAsked(String gridAsk) { return Integer.parseInt(gridAsk.replaceAll("^This picture holds (\\d+) .*$", "$1")); }

    /** The colour at the middle of cell {@code k} of a grid of {@code n}, as the stand-in tells the pictures apart. */
    static int cellColour(byte[] png, int k, int n) {
        try {
            BufferedImage g = ImageIO.read(new ByteArrayInputStream(png));
            int[] o = Sift.cellOrigin(k, n);
            return g.getRGB(o[0] + Sift.CELL_W / 2, o[1] + Sift.CELL_H / 2) & 0xFFFFFF;
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    static final String WHAT = "a red apron; nobody speaks; the pattern pieces laid out at the start; an inside pocket";

    /** A pool of six kept under the library, as candidates keeps one, in this order. */
    static String pool(LibraryStore store) { return pool(store, List.of(V1, V2, V3, V4, V5, V6)); }

    static String pool(LibraryStore store, List<String> ids) {
        Candidates.Ask ask = Candidates.Ask.of(M.createObjectNode().set("things", M.createArrayNode().add("apron").add("エプロン")));
        List<Candidates.Hit> hits = new ArrayList<>();
        for (String id : ids) { Candidates.Hit h = new Candidates.Hit(id); h.title = "Apron " + id; h.channel = "homebox crafts"; h.sources.add(Candidates.SEARCH); hits.add(h); }
        String stem = Candidates.stem(ask);
        Candidates.keep(store, ask, new Candidates.Result(hits, Map.of(Candidates.SEARCH, ids.size()), List.of(), 0, 0, 0, 0, 1, stem, false, ""));
        return stem;
    }

    /** Ids for a pool of {@code n}: the first six as above, then aaaaaaaa007 and on; a thumbnail red (a yes) or blue (a no) by the id's last figure. */
    static List<String> ids(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(String.format("aaaaaaaa%03d", i));
        return out;
    }

    static int colourOfId(String id) { return (id.charAt(10) - '0') % 2 == 1 ? VideoRecallTest.RED : VideoRecallTest.BLUE; }

    /** Pictures for a larger pool: a thumbnail for every id, by its last figure. */
    void picturesByLastFigure() {
        Sift.PICTURES = url -> { fetched.add(url); String id = url.replaceAll(".*/vi/([A-Za-z0-9_-]{11})/.*", "$1"); int c = colourOfId(id); return VideoRecallTest.sheet(c, c, c, c); };
        Sift.SHEETS = ids -> { sheetsAsked.addAll(ids); Map<String, Sift.FirstSheet> out = new LinkedHashMap<>(); for (String id : ids) out.put(id, new Sift.FirstSheet(2, 2, 5, 30, VideoRecallTest.webp(VideoRecallTest.sheet(VideoRecallTest.RED, VideoRecallTest.RED, VideoRecallTest.RED, VideoRecallTest.RED)))); return out; };
    }

    @Test void aThumbnailsNoNeverReachesTheSheetPassYesComesBeforeMaybeAndNothingIsJudgedTwice() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String key = pool(store);
        List<String> progress = new ArrayList<>();
        Sift.Result r = Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key)), progress::add, () -> false);
        List<String> ids = Sift.ids(r);
        assertEquals(List.of(V1, V2, V4, V5), ids, "yes on the sheet before maybe, then yes on the thumbnail before maybe, then the pool's order: " + r.text());
        assertFalse(sheetsAsked.contains(V3), "a thumbnail's no never reaches the sheet pass: " + sheetsAsked);
        assertEquals(Set.of(V1, V2, V4, V5, V6), Set.copyOf(sheetsAsked), "the sheets of the five kept were asked of the helper, ahead of the sheet pass: " + sheetsAsked);
        assertEquals(5, sheetsAsked.size(), "each once");
        assertEquals(List.of(VideoRecallTest.RED, VideoRecallTest.GREEN, VideoRecallTest.BLUE, VideoRecallTest.RED), sheetColours, "the sheet pass takes the thumbnail's yes ones first (V1, V4, V6; V5 has no sheet) and the maybe (V2) after them, each in the pool's order");
        assertEquals(1, thumbAsks.size(), "six candidates' thumbnails judged in one call, as one grid");
        assertEquals(6, thumbCells.get(), "every candidate's thumbnail judged once");
        assertEquals(4, sheetAsks.size(), "the four with a sheet judged once; the one without is kept as maybe without a call");
        assertTrue(thumbAsks.get(0).startsWith("This picture holds 6 cover pictures (thumbnails) of 6 different videos in a grid of 3 columns, numbered 1 to 6 left to right and then row by row; each one's number is in the black box in its top-left corner. A person is looking for a video that makes or shows apron (also called エプロン), and remembers of it: a red apron") && thumbAsks.get(0).contains("say no only for what it shows") && thumbAsks.get(0).endsWith("6 lines, one per picture, in order, nothing else."), "the grid is asked only what one still can show, with the thing's names, one line per number: " + thumbAsks.get(0));
        assertTrue(sheetAsks.get(0).startsWith("These are the first 4 small frames of a video, one every 5 seconds from its very start") && sheetAsks.get(0).contains("remembers of the start of the video: the pattern pieces laid out at the start"), sheetAsks.get(0));
        assertTrue(thumbAsks.stream().noneMatch(a -> a.contains("nobody speaks") || a.contains("inside pocket")), "what no picture shows is never asked of a picture");
        assertEquals(6, r.reached()); assertEquals(6, r.thumbsJudged()); assertEquals(5, r.thumbsKept()); assertEquals(5, r.sheetsJudged()); assertEquals(4, r.sheetsKept()); assertEquals(1, r.noSheet()); assertEquals(0, r.notSifted()); assertEquals(0, r.unchecked());
        String text = r.text();
        assertTrue(text.startsWith("Sifted 6 of 6 candidates for: " + WHAT + " (the pool " + key + ")."), text);
        assertTrue(text.contains("The thumbnail pass judged 6: kept 5, dropped 1; nine to a call as one numbered grid; about ") && text.contains(" seconds a thumbnail, fetch and judgement, with "), "the way the thumbnails went and the pace are said: " + text);
        assertTrue(text.contains("The first-sheet pass judged 5 of the 5: kept 4, dropped 1; 1 had no storyboard to judge and are kept as maybe; about ") && text.contains(" seconds a sheet."), text);
        assertTrue(text.contains("Judged on the thumbnail: apron (エプロン); a red apron. Judged on the first sheet, the first minutes as small frames: the pattern pieces laid out at the start. Left to the look, since no one picture shows them: an inside pocket. That nobody speaks cannot be seen in a picture"), text);
        assertTrue(text.contains("1. https://www.youtube.com/watch?v=" + V1 + " · \"Apron " + V1 + "\" · homebox crafts · thumbnail yes: A red apron on a table. · first sheet yes: The frames show pattern pieces laid on a table."), "each pass's line is kept on the survivor, the grid's line as a single call's: " + text);
        assertTrue(text.contains("4. https://www.youtube.com/watch?v=" + V5 + " · ") && text.contains("first sheet maybe: YouTube keeps no storyboard for this video"), text);
        assertTrue(text.endsWith("Give the first five to one look together (videos), sparse, for every remembered thing at once; then the next five, while the budget lasts; the best gets a full look."), text);
        assertFalse(text.contains("not sifted") || text.contains("page=2"), "nothing was cut: " + text);
        assertNotNull(RawCapture.find(store, "researchzosho://sift/" + r.key()), "the sift is kept on the shelves");
        assertTrue(Files.isRegularFile(Sift.thumbFile(V1)) && Files.isRegularFile(Storyboards.dir(VideoRecall.work(), V1).resolve("first.webp")), "the pictures are kept under the helper's folder");
        assertEquals(11, Files.readAllLines(Sift.ledgerFile(store)).size(), "six thumbnails and five sheets in the ledger (the one YouTube keeps no storyboard for is a judgement too)");
        assertEquals("nine to a call as one numbered grid", Sift.json(r).path("thumbnails").path("mode").asText());
        // the same sift again: nothing fetched, nothing judged, the same answer
        int fetches = fetched.size(), thumbs = thumbCells.get(), sheets = sheetAsks.size(), sheetCalls = sheetsAsked.size();
        Sift.Result again = Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key).put("page", 2)), progress::add, () -> false);
        assertEquals(fetches, fetched.size(), "no thumbnail fetched twice"); assertEquals(thumbs, thumbCells.get(), "no thumbnail judged twice"); assertEquals(sheets, sheetAsks.size(), "no sheet judged twice"); assertEquals(sheetCalls, sheetsAsked.size(), "no sheet fetched twice");
        assertEquals(ids, Sift.ids(again));
        assertTrue(again.text().contains("The thumbnail pass judged 6 (6 from earlier judgements): kept 5, dropped 1.") && again.text().contains("judged 5 of the 5 (5 from earlier judgements)"), again.text());
        // the same pool by the hash alone, and the newest pool when none is named
        assertEquals(ids, Sift.ids(Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key.substring(11))), progress::add, () -> false)));
        assertEquals(ids, Sift.ids(Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT)), progress::add, () -> false)));
        assertThrows(IllegalArgumentException.class, () -> Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", "2026-01-01-ffffffffffffffff")), progress::add, () -> false), "a key no pool is kept under is said");
    }

    @Test void theGridHoldsTheCoverPicturesNumberedInTheirCornersAndAShortOneTheRestOfThePool() throws Exception {
        int[] colours = {VideoRecallTest.RED, VideoRecallTest.BLUE, VideoRecallTest.GREEN, VideoRecallTest.YELLOW, VideoRecallTest.WHITE, VideoRecallTest.GRAY, VideoRecallTest.ORANGE, VideoRecallTest.RED, VideoRecallTest.BLUE};
        List<byte[]> nine = new ArrayList<>();
        for (int c : colours) nine.add(VideoRecallTest.sheet(c, c, c, c));
        BufferedImage g = ImageIO.read(new ByteArrayInputStream(Sift.grid(nine)));
        assertEquals(Sift.GAP + 3 * (Sift.CELL_W + Sift.GAP), g.getWidth()); assertEquals(Sift.GAP + 3 * (Sift.CELL_H + Sift.GAP), g.getHeight());
        List<Set<Integer>> digits = new ArrayList<>();
        for (int k = 0; k < 9; k++) {
            int[] o = Sift.cellOrigin(k, 9);
            assertEquals(colours[k], g.getRGB(o[0] + Sift.CELL_W / 2, o[1] + Sift.CELL_H / 2) & 0xFFFFFF, "cell " + (k + 1) + " holds its picture, scaled whole");
            assertEquals(colours[k], g.getRGB(o[0] + Sift.CELL_W - 2, o[1] + Sift.CELL_H - 2) & 0xFFFFFF, "to its far corner");
            assertEquals(0x000000, g.getRGB(o[0] + 1, o[1] + 1) & 0xFFFFFF, "the number's box in the top-left corner is black");
            Set<Integer> white = new HashSet<>();
            for (int y = 0; y < Sift.LABEL; y++) for (int x = 0; x < Sift.LABEL; x++) if ((g.getRGB(o[0] + x, o[1] + y) & 0xFFFFFF) == 0xFFFFFF) white.add(y * Sift.LABEL + x);
            assertTrue(white.size() > 5 * Sift.DIGIT * Sift.DIGIT, "the number is drawn in white dots in the box of cell " + (k + 1));
            assertFalse(digits.contains(white), "each cell's number differs from the ones before it");
            digits.add(white);
            if (k % 3 > 0) assertEquals(Sift.GRID_BACK.getRGB() & 0xFFFFFF, g.getRGB(o[0] - Sift.GAP / 2, o[1] + Sift.CELL_H / 2) & 0xFFFFFF, "a grey gap between cells");
        }
        // the pool's end: four as two by two, five as three by two, one as its own cell
        BufferedImage four = ImageIO.read(new ByteArrayInputStream(Sift.grid(nine.subList(0, 4))));
        assertEquals(Sift.GAP + 2 * (Sift.CELL_W + Sift.GAP), four.getWidth()); assertEquals(Sift.GAP + 2 * (Sift.CELL_H + Sift.GAP), four.getHeight());
        assertEquals(VideoRecallTest.YELLOW, four.getRGB(Sift.cellOrigin(3, 4)[0] + Sift.CELL_W / 2, Sift.cellOrigin(3, 4)[1] + Sift.CELL_H / 2) & 0xFFFFFF, "the fourth picture in the second row's second cell");
        assertEquals(2, Sift.columns(4)); assertEquals(3, Sift.columns(5)); assertEquals(2, Sift.rows(5)); assertEquals(1, Sift.columns(1)); assertEquals(3, Sift.rows(9));
        assertTrue(Sift.gridAsk(4, List.of("apron"), List.of()).startsWith("This picture holds 4 cover pictures (thumbnails) of 4 different videos in a grid of 2 columns, numbered 1 to 4"), Sift.gridAsk(4, List.of("apron"), List.of()));
    }

    @Test void theGridsAnswerIsReadStrictlyByNumber() {
        String raw = "Here are the pictures:\n1: yes — A red apron on a table.\n2: Maybe — a yellow cloth\n**3**: no – a cat on a sofa\n4: a bag with handles\n5: A green apron held up — yes\n6. NO - nothing like it\n1: no — a second line for one\n10: yes — past the grid\n8) maybe: too small to tell\n";
        Map<Integer, Sift.Cell> got = Sift.parseGrid(raw, 9);
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 8), new ArrayList<>(got.keySet()), "every numbered line within the grid, once: " + got);
        assertEquals(new Sift.Cell("yes", "A red apron on a table."), got.get(1));
        assertEquals(new Sift.Cell("maybe", "a yellow cloth"), got.get(2), "the word in any case");
        assertEquals(new Sift.Cell("no", "a cat on a sofa"), got.get(3), "marks around the number are passed over");
        assertEquals(new Sift.Cell("maybe", "a bag with handles"), got.get(4), "a line whose word cannot be read is a maybe, its words kept");
        assertEquals(new Sift.Cell("yes", "A green apron held up"), got.get(5), "the word may end the line");
        assertEquals(new Sift.Cell("no", "nothing like it"), got.get(6));
        assertEquals(new Sift.Cell("maybe", "too small to tell"), got.get(8));
        assertNull(got.get(7), "a number no line begins with is absent, for the caller to ask alone");
        assertNull(got.get(10)); assertTrue(Sift.parseGrid("", 9).isEmpty() && Sift.parseGrid(null, 9).isEmpty());
        assertEquals("no", Sift.parseGrid("2: no — a bag with no handles", 9).get(2).verdict(), "the word first, not a no inside the words");
        assertEquals("maybe", Sift.parseGrid("2: a bag with no handles", 9).get(2).verdict(), "a no inside the words, with no word first or last, is a maybe");
    }

    @Test void aNumberTheAnswerLacksIsAskedAloneAndAGridsJudgementAndASingleCallsShareTheLedger() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib6")); store.init();
        picturesByLastFigure();
        String key = pool(store, ids(13));
        // the first grid's answer leaves number 5 out and says nothing readable for number 4
        VideoRecall.VISION = (img, ask) -> {
            String a = vision(img, ask);
            if (ask.startsWith("This picture holds 9 ")) { StringBuilder b = new StringBuilder(); for (String l : a.split("\n")) { if (l.startsWith("5:")) continue; b.append(l.startsWith("4:") ? "4: a bag with handles" : l).append('\n'); } return b.toString(); }
            return a;
        };
        Sift.Result r = Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key)), s -> { }, () -> false);
        assertEquals(13, r.reached()); assertEquals(13, r.thumbsJudged()); assertEquals(13, r.thumbsNew());
        assertEquals(2, thumbAsks.stream().filter(a -> a.startsWith("This picture holds ")).count(), "a grid of nine, then a short grid of four at the pool's end");
        assertTrue(thumbAsks.stream().anyMatch(a -> a.startsWith("This picture holds 4 cover pictures")), thumbAsks.toString());
        assertEquals(1, singles.get(), "the one number the answer lacked was asked alone, once");
        Map<String, Sift.Judged> ledger = Sift.ledger(store);
        assertEquals(13, ledger.values().stream().filter(j -> j.pass().equals(Sift.THUMB)).count(), "every thumbnail in the ledger once");
        Sift.Judged five = ledger.values().stream().filter(j -> j.id().equals("aaaaaaaa005") && j.pass().equals(Sift.THUMB)).findFirst().orElseThrow();
        assertEquals("yes", five.verdict()); assertEquals("A red apron on a table.", five.line());
        Sift.Judged four = ledger.values().stream().filter(j -> j.id().equals("aaaaaaaa004") && j.pass().equals(Sift.THUMB)).findFirst().orElseThrow();
        assertEquals("maybe", four.verdict()); assertEquals("a bag with handles", four.line());
        assertTrue(r.text().contains("; nine to a call as one numbered grid, 1 alone where a grid's answer left them out or the pool's end left one; about "), r.text());
        // judged one to a call from here, the same ledger: nothing judged again
        Config.set(Sift.GRID_KEY, "1"); Config.invalidate();
        int cells = thumbCells.get();
        Sift.Result one = Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key)), s -> { }, () -> false);
        assertEquals(cells, thumbCells.get(), "a grid's judgements are read by a sift that goes one to a call");
        assertTrue(one.text().contains("The thumbnail pass judged 13 (13 from earlier judgements)"), one.text());
        assertEquals("", Sift.json(one).path("thumbnails").path("mode").asText(), "nothing new was judged, so no way is said");
        // and the other way round: singles first, then a grid reads them
        LibraryStore store2 = new LibraryStore(tmp.resolve("lib7")); store2.init();
        String key2 = pool(store2, ids(5));
        VideoRecall.VISION = this::vision;
        Sift.Result s1 = Sift.run(store2, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key2)), s -> { }, () -> false);
        assertTrue(s1.text().contains("; one to a call; about "), s1.text());
        assertEquals(cells + 5, thumbCells.get());
        Config.set(Sift.GRID_KEY, "9"); Config.invalidate();
        Sift.Result s2 = Sift.run(store2, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key2)), s -> { }, () -> false);
        assertEquals(cells + 5, thumbCells.get(), "a single call's judgements are read by a sift that goes nine to a call");
        assertEquals(Sift.ids(s1), Sift.ids(s2));
    }

    @Test void aModelThatCannotReadAGridIsFoundOutAndTheThumbnailsGoOneToACall() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib8")); store.init();
        picturesByLastFigure();
        String key = pool(store, ids(40));
        // every grid comes back as no for every picture, whatever it shows; alone, each picture is read for what it is
        VideoRecall.VISION = (img, ask) -> {
            if (ask.startsWith("This picture holds ")) { thumbAsks.add(ask); StringBuilder b = new StringBuilder(); int n = cellsAsked(ask); for (int k = 0; k < n; k++) { thumbCells.incrementAndGet(); b.append(k + 1).append(": no — A cat on a sofa.\n"); } return b.toString(); }
            return vision(img, ask);
        };
        Sift.Result r = Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key)), s -> { }, () -> false);
        assertEquals(3, thumbAsks.stream().filter(a -> a.startsWith("This picture holds ")).count(), "three grids in a row with one word for every picture, and no more");
        assertEquals(40, singles.get(), "those three grids' twenty-seven judged again one by one, then the thirteen left one to a call");
        assertEquals(40, r.reached()); assertEquals(40, r.thumbsJudged()); assertEquals(40, r.thumbsNew()); assertEquals(20, r.thumbsKept(), "the twenty red ones, as a single call reads them: " + r.text());
        assertTrue(r.text().contains("; one to a call after 3 grids in a row came back with one word for every picture or with no numbered line (27 judged again one by one); about "), r.text());
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("The model that reads pictures (") && n.contains(") answered 3 grids in a row with one word for every picture or with no numbered line, as a model that cannot read a grid does, so the thumbnails went one to a call from there, and the 27 of those grids were judged again one by one.")), r.notes().toString());
        Map<String, Sift.Judged> ledger = Sift.ledger(store);
        assertEquals("yes", ledger.get("aaaaaaaa001|" + Sift.THUMB + "|" + Sift.hash(Sift.thumbAsk(List.of("apron", "エプロン"), List.of("a red apron")))).verdict(), "the single call's word stands in the ledger in place of the grid's");
        assertEquals(40 + 27, Files.readAllLines(Sift.ledgerFile(store)).stream().filter(l -> l.contains("\"pass\":\"" + Sift.THUMB + "\"")).count(), "the ledger keeps both lines; the later is read");
        int cells = thumbCells.get();
        Sift.Result again = Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key)), s -> { }, () -> false);
        assertEquals(cells, thumbCells.get()); assertEquals(Sift.ids(r), Sift.ids(again));
    }

    @Test void oneSiftRunsInALibraryAtATimeAndTheCallAfterItReadsItsJudgements() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib9")); store.init();
        String key = pool(store);
        CountDownLatch entered = new CountDownLatch(1), gate = new CountDownLatch(1);
        VideoRecall.VISION = (img, ask) -> {
            if (ask.startsWith("This picture holds ")) { entered.countDown(); try { assertTrue(gate.await(20, TimeUnit.SECONDS), "the test lets the first sift go on"); } catch (InterruptedException e) { throw new RuntimeException(e); } }
            return vision(img, ask);
        };
        AtomicReference<Sift.Result> first = new AtomicReference<>();
        Thread t = new Thread(() -> first.set(Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key)), s -> { }, () -> false)));
        t.start();
        assertTrue(entered.await(20, TimeUnit.SECONDS), "the first sift is at the model");
        String other = "an apron in red; the pieces laid out at the start; a pocket inside; no one talks";
        Sift.Result second = Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", other).put("from", key)), s -> { }, () -> false);
        assertTrue(second.busy(), "the second call comes back at once: " + second);
        assertEquals("A sift is already running in this library on the pool " + key + "; its result comes to this run when it ends; ask for it again rather than starting another.", second.text());
        assertEquals(List.of(second.text()), second.notes()); assertEquals(0, second.pool()); assertTrue(second.survivors().isEmpty());
        assertEquals(second.text(), Sift.json(second).path("summary").asText());
        assertEquals(0, thumbCells.get(), "the second call judged nothing, and the first is still at the model");
        // another library is not held by it
        LibraryStore elsewhere = new LibraryStore(tmp.resolve("lib10")); elsewhere.init();
        String key2 = pool(elsewhere, List.of(V1, V3));
        VideoRecall.Vision blocking = VideoRecall.VISION;
        VideoRecall.VISION = this::vision; VideoRecall.forgetVision();
        assertFalse(Sift.run(elsewhere, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key2)), s -> { }, () -> false).busy(), "a sift in another library runs");
        VideoRecall.VISION = blocking;
        gate.countDown(); t.join(20_000);
        assertNotNull(first.get()); assertFalse(first.get().busy()); assertEquals(List.of(V1, V2, V4, V5), Sift.ids(first.get()));
        // the first over, the second wording's call runs, and reads the first's judgements where its words ask the same of the pictures
        VideoRecall.VISION = this::vision; VideoRecall.forgetVision();
        int cells = thumbCells.get();
        Sift.Result third = Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", other).put("from", key)), s -> { }, () -> false);
        assertFalse(third.busy());
        assertEquals(cells, thumbCells.get(), "nothing judged twice: the second wording asks the same of the thumbnails");
        assertTrue(third.text().contains("The thumbnail pass judged 6 (6 from earlier judgements)"), third.text());
        assertEquals(List.of(V1, V2, V4, V5), Sift.ids(third));
        // and the same library is free again for the fourth
        assertFalse(Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key)), s -> { }, () -> false).busy());
    }

    @Test void theTimeCutsItCleanlyAndTheSameCallGoesOnFromThere() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib2")); store.init();
        String key = pool(store);
        Config.set(Sift.GRID_KEY, "3"); Config.invalidate();   // six candidates as two grids of three
        AtomicInteger calls = new AtomicInteger();
        VideoRecall.Vision v = VideoRecall.VISION;
        VideoRecall.VISION = (img, ask) -> { if (ask.startsWith("This picture holds ")) calls.incrementAndGet(); return v.describe(img, ask); };
        Sift.Result cut = Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key)), s -> { }, () -> calls.get() >= 1);
        assertEquals(3, cut.reached()); assertEquals(3, cut.notSifted());
        assertTrue(cut.text().contains("Sifted 3 of 6 candidates"), cut.text());
        assertTrue(cut.text().contains("; three to a call as one numbered grid; about "), cut.text());
        assertTrue(cut.text().contains("3 candidates were not sifted, and 2 thumbnail survivors were not checked on their first sheet before the time ran out: sift again with page=2 to go on from there; nothing is judged twice."), "the cut is said with the way on: " + cut.text());
        assertTrue(cut.survivors().isEmpty() && cut.text().contains("No candidate survived both passes among those sifted."), cut.text());
        VideoRecall.VISION = v;
        Sift.Result on = Sift.run(store, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key).put("page", 2)), s -> { }, () -> false);
        assertEquals(List.of(V1, V2, V4, V5), Sift.ids(on), on.text());
        assertTrue(on.text().contains("The thumbnail pass judged 6 (3 from earlier judgements)"), "the three judged before the cut are read, not judged again: " + on.text());
        assertEquals(6, thumbCells.get(), "six thumbnails judged in all over the two calls");
        // the limit bounds what one call takes anew, outside a run
        LibraryStore store3 = new LibraryStore(tmp.resolve("lib3")); store3.init();
        String key3 = pool(store3);
        Sift.Result two = Sift.run(store3, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key3).put("limit", 2)), s -> { }, () -> false);
        assertEquals(2, two.reached()); assertEquals(4, two.notSifted());
        // the model that reads pictures stops answering: the sift gives up after two unanswered in a row and keeps nothing of them
        LibraryStore store4 = new LibraryStore(tmp.resolve("lib4")); store4.init();
        String key4 = pool(store4);
        VideoRecall.UNANSWERED = () -> true;
        VideoRecall.VISION = (img, ask) -> ask.startsWith("This picture is two colours") ? "Red and blue." : "";
        VideoRecall.forgetVision();
        Sift.Result gave = Sift.run(store4, Sift.Ask.of(M.createObjectNode().put("what", WHAT).put("from", key4)), s -> { }, () -> false);
        assertTrue(gave.notes().stream().anyMatch(n -> n.contains("did not answer twice in a row, so the sift stopped at candidate 1")), gave.notes().toString());
        assertEquals(0, gave.thumbsJudged()); assertFalse(Files.exists(Sift.ledgerFile(store4)), "nothing unanswered is kept as judged");
    }

    @Test void theWordsSortedByThePictureThatCanShowThem() {
        Sift.Split s = Sift.split(List.of("nobody speaks", "a red bag with a brown tag", "the sizes on screen at the start", "an inside pocket", "no snap closure", "a person's hands only, no face", "no person speaking, only music", "the cut list right at the very start"));
        assertEquals(List.of("nobody speaks", "no person speaking, only music"), s.unseen(), "a person not speaking is silence, not a person shown (the eighth live run sent it to the thumbnail)");
        assertEquals(List.of("the sizes on screen at the start", "the cut list right at the very start"), s.start(), "at the very start is the start too");
        assertEquals(List.of("a red bag with a brown tag", "a person's hands only, no face"), s.still());
        assertEquals(List.of("an inside pocket", "no snap closure"), s.rest());
        assertEquals("yes", Sift.verdict("A red apron.\nYes")); assertEquals("yes", Sift.verdict("Found")); assertEquals("no", Sift.verdict("A cat.\nno")); assertEquals("maybe", Sift.verdict("Hard to tell.\nmaybe")); assertEquals("maybe", Sift.verdict("Something with no word on its own line"), "an answer without the word is a maybe, never a no");
        assertEquals("apron (エプロン); a red tag", Sift.forThumb(List.of("apron", "エプロン"), List.of("a red tag")));
        assertTrue(Sift.sheetAsk(null, "the pieces laid out", false).startsWith("These are the first small frames of a video, one every a few seconds") && !Sift.sheetAsk(null, "the pieces laid out", false).contains("quote, exactly as written"));
        assertTrue(Sift.written(List.of("the sizes on screen at the start")) && !Sift.written(List.of("the pattern pieces laid out at the start")), "sizes on screen are writing; pieces laid out are not");
        assertTrue(Sift.sheetAsk(null, "the sizes on screen at the start", true).contains("quote, exactly as written, the writing and figures you can read") && Sift.sheetAsk(null, "the sizes on screen at the start", true).contains("a ruler, a tape or a piece being measured with no figures readable is not writing on screen"), "writing must be read as writing");
        assertTrue(Sift.reads("Fabric pieces with \"35 cm\" written on them") && Sift.reads("pieces marked 40cm × 35cm") && Sift.reads("the overlay reads 46 cm") && !Sift.reads("hands measuring fabric with a ruler on a table") && !Sift.reads("the second frame shows 3 pieces"), "a quotation or a figure with its unit is writing read; a count is not");
    }

    @Test void theInstrumentTheTableTheRulesTheProtocolAndTheRunsMinutesNameTheSift() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib5")); store.init();
        Instrument s = Instruments.sift(store);
        assertEquals("sift", s.name());
        assertTrue(s.words().contains("every candidate's thumbnail, then the first storyboard sheet of those that pass") && s.words().contains("yes before maybe") && s.words().contains("the thumbnails go nine to a call as one numbered grid, under a second a thumbnail, and a sheet takes about ten seconds") && s.words().contains("one sift runs in a library at a time"), s.words());
        assertTrue(s.words().contains("· after candidates and before any look"), "when it pays, in the middle clause: " + s.words());
        assertEquals(Instruments.SIFT_EXAMPLE, s.example());
        assertTrue(s.example().contains("\"what\": \"a striped apron") && s.example().contains("\"from\": \"2026-11-05-0a1b2c3d4e5f6071\"") && !s.example().contains("<"), "a complete literal call, no placeholder: " + s.example());
        JsonNode schema = s.schema(M);
        assertTrue(schema.path("required").toString().contains("what") && schema.path("properties").has("from") && schema.path("properties").has("videos") && schema.path("properties").has("limit") && schema.path("properties").has("page"), schema.toString());
        assertTrue(s.asTool().execute(M.readTree("{}")).startsWith("Say what you remember of the video"), "an empty call is told what to give");
        List<Instrument> chat = Instruments.forChat(store);
        assertTrue(Instruments.isInstrument(chat, "sift") && Instruments.isInstrument(chat, "candidates"), chat.stream().map(Instrument::name).toList().toString());
        Map<String, Tool> none = new LinkedHashMap<>();
        List<Instrument> run = Instruments.forRun(store, none, new Researcher.Ask("q", "broad", 0, List.of(), "web", List.of(), 0, List.of(), List.of()));
        assertEquals(List.of("search", "open", "look", "candidates", "sift", "records", "homes", "dossier", "board"), run.stream().map(Instrument::name).toList(), "the sift sits after candidates on a run's table");
        String small = new Brain(BrainTest.drive("w"), null, "qwen3.5-9b", null, Brain.Tier.SMALL, null).preamble(chat);
        assertTrue(small.contains("sift " + Instruments.SIFT_EXAMPLE) && small.contains("then sift {\"what\": \"a striped apron; a red tag; the pattern pieces laid out at the start\", \"from\": \"the key candidates gave\"}, then look with the first five survivors in videos"), small);
        String large = new Brain(BrainTest.drive("w"), null, "qwen3.8-27b", null, Brain.Tier.LARGE, null).preamble(chat);
        assertTrue(large.contains("sift: after candidates and before any look") && large.contains("then sift over the pool's key; then look on the first five survivors together"), large);
        assertTrue(Goals.named("choose").instruments().equals(List.of("candidates", "sift", "look")) && Goals.named("choose").decisionRules().contains("Then sift, given that key in from and the remembered things in what") && Goals.named("choose").decisionRules().contains("sift again with page=2"), Goals.named("choose").decisionRules());
        assertTrue(YoutubeProfile.FIND_ONE.contains("Then sift, given that key in from") && YoutubeProfile.FIND_ONE.contains("the sift is the funnel"), YoutubeProfile.FIND_ONE);
        assertTrue(Instruments.candidates(store).words().contains("then the pool goes to sift by its key") && Instruments.candidates(store).schema(M).path("properties").has("language"), Instruments.candidates(store).words());
        List<String> calls = new ArrayList<>();
        Instruments.SIFT = InstrumentsTest.fake("sift", calls);
        assertEquals("result of sift", s.asTool().execute(M.readTree("{\"what\":\"a red apron\",\"from\":\"k\"}")));
        assertEquals("sift {\"what\":\"a red apron\",\"from\":\"k\"}", calls.get(0));
        Instruments.SIFT = null;
        // the protocol's op, the MCP tool, and the tools that only read
        String key = pool(store);
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode args = M.createObjectNode().put("op", "sift").put("what", WHAT).put("from", key);
        args.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode r = p.video(args);
        assertEquals(6, r.path("pool").asInt()); assertEquals(4, r.path("survivors").size()); assertEquals(key, r.path("pool_key").asText());
        assertEquals(V1, r.path("survivors").get(0).path("id").asText()); assertEquals("yes", r.path("survivors").get(0).path("first_sheet").path("verdict").asText()); assertEquals("maybe", r.path("survivors").get(1).path("thumbnail").path("verdict").asText());
        assertTrue(r.path("thumbnails").path("judged").asInt() == 6 && r.path("thumbnails").path("mode").asText().equals("nine to a call as one numbered grid") && r.path("first_sheets").path("kept").asInt() == 4 && r.path("summary").asText().startsWith("Sifted 6 of 6"), r.toString());
        ObjectNode none2 = M.createObjectNode().put("op", "sift"); none2.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        assertThrows(ProtocolError.class, () -> p.video(none2));
        JsonNode tools = McpServer.handle("tools/list", M.createObjectNode()).get("tools");
        JsonNode sift = null; for (JsonNode t : tools) if (t.get("name").asText().equals("library_sift")) sift = t;
        assertNotNull(sift, "library_sift is an MCP tool");
        assertTrue(sift.get("description").asText().startsWith("SIFT THE CANDIDATES") && sift.get("description").asText().contains("nine to a call as one numbered grid") && sift.get("inputSchema").get("required").toString().contains("what") && sift.get("inputSchema").get("properties").has("from"), sift.toString());
        assertTrue(McpServers.ONLY_READS.get("researchzosho").contains("library_sift"), "a tool that only reads");
        // the chat's command and the page's form
        YoutubeProfile f = new YoutubeProfile();
        String reply = f.chatReply(store, Patrons.Patron.PERSON, "c", "/video sift " + WHAT + " from " + key);
        assertTrue(reply.startsWith("Sifted 6 of 6 candidates for: " + WHAT), reply);
        assertTrue(f.chatReply(store, Patrons.Patron.PERSON, "c", "/video sift").startsWith("Type /video sift and what you remember"), "an empty command is told what to give");
        String page = f.page(store, Patrons.Patron.PERSON, "/video", "GET", Map.of(), Map.of()).body();
        assertTrue(page.contains("<h2 id=\"sift\">Sift the candidates</h2>") && page.contains("name=\"op\" value=\"sift\"") && page.contains("name=\"language\""), "the Video page offers the sift, and the candidates form the language");
        // a run about a video the person remembers has ninety minutes when they set none
        ObjectNode ask = M.createObjectNode().put("question", "Find the sewing video I watched a while ago: a striped apron, nobody talks, the pattern pieces laid out on screen at the start.").put("goal", "choose");
        ask.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "mcp");
        ObjectNode filed = p.research(ask);
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
        assertEquals(LibraryProtocol.FIND_ONE_MINUTES, jobs.get(filed.path("job_id").asText()).path("args").path("max_minutes").asInt(), "the job carries the ninety minutes");
        assertTrue(filed.path("note").asText().startsWith("A run about a video you remember has 90 minutes by default"), filed.toString());
        ObjectNode own = ask.deepCopy().put("max_minutes", 30).put("question", "Find the sewing video I watched last spring: a striped apron, nobody talks, the pattern pieces laid out on screen at the start.");   // another question: a program's same ask filed again is the first run
        assertEquals(30, jobs.get(p.research(own).path("job_id").asText()).path("args").path("max_minutes").asInt(), "the person's own ceiling stands");
        ObjectNode channels = ask.deepCopy().put("question", "Which channels make videos about sewing aprons and still post?");
        assertEquals(0, jobs.get(p.research(channels).path("job_id").asText()).path("args").path("max_minutes").asInt(), "a question about channels keeps no ceiling");
    }
}
