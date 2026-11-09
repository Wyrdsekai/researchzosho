package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.tools.VideoText;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The funnel between a candidate pool of thousands and the five a look can afford. A look costs minutes a video, so the seventh live
 * run's five looks went to the top of a title-fit order over 4,690 candidates and never reached the video (2026-11-05). Two cheap passes
 * come first, each a picture the machine fetches and the model that reads pictures judges yes, maybe or no, keeping yes and maybe:
 * every candidate's thumbnail, YouTube's cover picture of it, judged for the things remembered that one still can show (the thing
 * itself, its colours or pattern, a tag or label, a person shown or not; never words, which are the video's steps and titles, not the
 * cover's: the eighth live run's judge said no to thirty of fifty-four cover pictures of the thing because they lacked the steps'
 * text), nine to a call as one numbered grid (the ninth live run judged 678 of 3,320 one to a call, at 3.9 seconds each, and never
 * reached the video, which stood at 1,224); then, for those that pass, the first storyboard
 * sheet, the first minutes of the video as small frames, judged whole in one call for the things remembered about the start. What no
 * picture can show, that nobody speaks, a step in the middle, is left to the look. Nothing is judged twice: every judgement is kept
 * under the library, every picture under the helper's folder, so a second sift reads and fetches nothing it has, and goes on from
 * where the time cut the first; a grid's judgement of a thumbnail and a single call's are kept alike, under the thumbnail and the
 * question asked of it. One sift runs in a library at a time, whatever its wording or its pool.
 */
public final class Sift {
    private Sift() { }
    static final ObjectMapper M = new ObjectMapper();

    /** How many thumbnails one call to the model that reads pictures judges, as one numbered grid; 1 judges them one to a call. */
    public static final String GRID_KEY = "RESEARCHZOSHO_SIFT_GRID";
    static final int GRID_DEFAULT = 9, GRID_MOST = 9;
    static int gridCells() { return Math.max(1, Math.min(GRID_MOST, Config.getInt(GRID_KEY, GRID_DEFAULT))); }
    /** A grid's cell, the shape of YouTube's cover picture; the gap between cells; the box each cell's number is drawn in, and the digit's scale within it. */
    static final int CELL_W = 400, CELL_H = 300, GAP = 8, LABEL = 54, DIGIT = 9;
    static final Color GRID_BACK = new Color(0x60, 0x60, 0x60);
    /** How many grids in a row answered with one word for every picture, or with no numbered line, before the model is taken as one that cannot read a grid. */
    static final int SAME_GRIDS = 3;

    /** YouTube's cover picture of a video, keyless. */
    public static String thumbnailUrl(String id) { return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"; }

    /** A picture fetched plainly by its address; a test stands in. Throws with "HTTP <code>" when the server answered otherwise. */
    public interface Pictures { byte[] fetch(String url) throws Exception; }
    public static volatile Pictures PICTURES = Sift::fetchPlain;
    /** A video's first storyboard sheet: the grid of its tiles, the seconds between them, the video's length, and the sheet's own bytes (WebP or PNG). */
    public record FirstSheet(int rows, int columns, double step, double duration, byte[] image) { }
    /** The first sheets of several videos at once, by id: those YouTube keeps one for, as {@link FirstSheet}; a video YouTube keeps none for as null; a video that could not be asked absent. A test stands in for the helper and the fetch. */
    public interface Sheets { Map<String, FirstSheet> first(List<String> ids) throws Exception; }
    public static volatile Sheets SHEETS = Sift::firstSheetsLive;
    /** The pace between two plain fetches of a picture, in milliseconds; a test sets it to nothing. */
    public static volatile long paceMs = 250;
    /** How many videos one yt-dlp call asks the storyboard specs of: the call's start and the tunnel's pace are paid once for all of them. */
    static final int SPEC_BATCH = 5;
    /** How many first sheets the fetcher may have ready ahead of the sheet pass: enough that a judgement never waits on a fetch, few enough that the time's end wastes little. */
    static final int SHEETS_AHEAD = 60;
    /** The most candidates a sift takes anew when no run's budget says otherwise: a page's form or a command, which cannot wait an hour. */
    public static final int LIMIT_DEFAULT = 200;
    /** The share of the sift's time the thumbnail pass may take, the rest being the sheets': half, since the thumbnails go nine to a call and the sheets are fetched while they are judged. */
    static final double THUMB_SHARE = 0.5;
    /** The width a first sheet is sent at, scaled up in whole steps, so that writing on its tiles can be read; how many survivors are shown in the text. */
    static final int SHEET_WIDTH = 1280, SHOWN = 40;
    public static final String THUMB = "thumbnail", SHEET = "first sheet";

    /** What the model gives: the things remembered, the pool's key or the videos themselves, the thing's names for a videos list, the most taken anew, and the page. */
    public record Ask(String what, String from, List<String> videos, List<String> things, int limit, int page) {
        public static Ask of(JsonNode args) {
            List<String> videos = new ArrayList<>();
            JsonNode v = args.path("videos");
            if (v.isArray()) { for (JsonNode x : v) { String id = VideoText.videoId(x.asText("")); if (id != null && !videos.contains(id)) videos.add(id); } }
            else for (String x : v.asText("").split("[,;\\s]+")) { String id = VideoText.videoId(x); if (id != null && !videos.contains(id)) videos.add(id); }
            List<String> things = new ArrayList<>();
            JsonNode t = args.path("things");
            if (t.isArray()) { for (JsonNode x : t) Candidates.Ask.addThing(things, x.asText("")); }
            else for (String x : t.asText("").split("[,;|\\n]")) Candidates.Ask.addThing(things, x);
            return new Ask(args.path("what").asText("").strip(), args.path("from").asText("").strip(), videos, things, args.path("limit").asInt(0), Math.max(1, args.path("page").asInt(1)));
        }
    }

    /** One candidate through the funnel: the hit, the thumbnail's verdict and line, the first sheet's verdict and line ("" while not judged). */
    public static final class Sifted {
        public final Candidates.Hit hit;
        public String thumbVerdict = "", thumbLine = "", sheetVerdict = "", sheetLine = "";
        Sifted(Candidates.Hit hit) { this.hit = hit; }
        boolean thumbKept() { return thumbVerdict.equals("yes") || thumbVerdict.equals("maybe"); }
        boolean sheetKept() { return sheetVerdict.equals("yes") || sheetVerdict.equals("maybe"); }
        ObjectNode json() {
            ObjectNode o = M.createObjectNode().put("id", hit.id).put("link", hit.link()).put("title", hit.shownTitle()).put("channel", hit.channel);
            o.putObject("thumbnail").put("verdict", thumbVerdict).put("line", thumbLine);
            o.putObject("first_sheet").put("verdict", sheetVerdict).put("line", sheetLine);
            return o;
        }
    }

    /** What came back: the survivors best first, the counts of each pass, what was not reached, the paces, how the thumbnails were judged (nine to a call or one), the key, and the text for the model. */
    public record Result(List<Sifted> survivors, int pool, int reached, int thumbsJudged, int thumbsNew, int thumbsKept, int sheetsJudged, int sheetsNew, int sheetsKept, int noSheet, int notSifted, int unchecked,
                         double thumbSeconds, double sheetSeconds, String thumbMode, String key, String poolKey, List<String> notes, String text) {
        /** Whether this is the answer to a call made while another sift ran in the library: nothing judged, one sentence. */
        public boolean busy() { return pool == 0 && key.isEmpty() && !text.isEmpty(); }
    }

    /** The things remembered that one still can show: a colour, a pattern, a tag or label, a person shown or not. Words are not among them: text on screen is the video's steps and titles, which a cover picture seldom carries, and a judge reads its absence as a no. */
    static final Pattern STILL = Pattern.compile("(?i)\\b(colou?rs?|coloured|colored|red|blue|green|yellow|black|white|gr[ae]y|brown|beige|navy|pink|purple|orange|cream|tan|khaki|natural|denim|stripes?|striped|plaid|check(ed|ered)?|floral|dots?|dotted|pattern(ed)?|tag|label|logo|brand|badge|patch|person|people|face|hands?|man|woman|girl|boy|nobody|no one|anyone|on camera)\\b|色|柄|タグ|ラベル|人|顔");

    /** A thing remembered as writing on screen: words, a caption, sizes, measurements, figures. The eighth live run's sheet judge said yes to "measuring with rulers" for sizes written on screen, so such a thing must be read as writing, and quoted. */
    static final Pattern WRITTEN = Pattern.compile("(?i)\\b(text|words?|writing|written|captions?|subtitles?|titles?|labels?|letters?|numbers?|figures?|sizes?|dimensions?|measurements?|cm|mm|inch(es)?)\\b|文字|寸法|サイズ|字幕");
    /** Writing read off the frames: a quotation, or a figure with its unit or beside another. */
    static final Pattern READS = Pattern.compile("[\"“”„「」『』«»]|\\d+(?:\\.\\d+)?\\s?(?:cm|mm|m|in|inch|inches|″|′|×|x\\s?\\d)");

    /** Whether a thing remembered about the start is writing on screen, which the frames must show as writing. */
    static boolean written(List<String> start) { for (String f : start) if (WRITTEN.matcher(f).find()) return true; return false; }

    /** Whether the judge's line quotes writing or figures it read. */
    static boolean reads(String line) { return line != null && READS.matcher(line).find(); }

    /** The things remembered sorted by the picture that can show them. */
    record Split(List<String> still, List<String> start, List<String> unseen, List<String> rest) { }

    static Split split(List<String> features) {
        List<String> still = new ArrayList<>(), start = new ArrayList<>(), unseen = new ArrayList<>(), rest = new ArrayList<>();
        for (String f : features) {
            if (VideoRecall.SILENCE.matcher(f).find()) unseen.add(f);
            else if (VideoRecall.AT_START.matcher(f).find()) start.add(f);
            else if (STILL.matcher(f).find()) still.add(f);
            else rest.add(f);
        }
        return new Split(still, start, unseen, rest);
    }

    // ---- the run

    public static Result run(LibraryStore store, Ask ask, Consumer<String> progress, BooleanSupplier stop) { return run(store, ask, progress, stop, 0); }

    /**
     * One sift at a time in a library, whatever its wording or its pool: the library's root, and the pool the running sift is over. A worker
     * that called sift twice in one turn, in two wordings of the same memory, ran both at once over the same pool, each at half the model's
     * pace and each reaching half as far (the eighth and ninth live runs; a lock on the pool's key did not hold across wordings). The second
     * call now comes back at once with one sentence, and the same call after the first ends reads the first's judgements from the ledger.
     */
    private static final ConcurrentHashMap<String, String> RUNNING = new ConcurrentHashMap<>();

    /** The sentence a call made while another sift runs in the library comes back with. */
    static String busySentence(String pool) { return "A sift is already running in this library on " + pool + "; its result comes to this run when it ends; ask for it again rather than starting another."; }

    /**
     * The funnel over a kept pool or a list of videos, until {@code untilMs} (epoch milliseconds; 0 for none): the thumbnail pass over the
     * candidates in their order, at most {@code limit} anew, within its share of the time; then the first-sheet pass over those it kept,
     * until the time; the survivors best first. What the time cut is counted and said; the same call again goes on from there. A call made
     * while another sift runs in the same library comes back at once, with nothing judged and the sentence saying so.
     */
    public static Result run(LibraryStore store, Ask ask, Consumer<String> progress, BooleanSupplier stopWhen, long untilMs) {
        if (ask.what().isEmpty()) throw new IllegalArgumentException("Say what you remember of the video, one thing after another (what).");
        if (store == null) throw new IllegalArgumentException("The sift needs a library to keep what it judges.");
        String root = store.root().toAbsolutePath().normalize().toString();
        String over = !ask.videos().isEmpty() ? "the videos given" : ask.from().isEmpty() ? "the newest pool" : "the pool " + ask.from();
        String running = RUNNING.putIfAbsent(root, over);
        if (running != null) return new Result(List.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "", "", List.of(busySentence(running)), busySentence(running));
        try { return sift(store, ask, progress, stopWhen, untilMs, root); }
        finally { RUNNING.remove(root); }
    }

    private static Result sift(LibraryStore store, Ask ask, Consumer<String> progress, BooleanSupplier stopWhen, long untilMs, String root) {
        List<String> things = new ArrayList<>(ask.things());
        List<Candidates.Hit> pool;
        String poolKey;
        if (!ask.videos().isEmpty()) {
            pool = new ArrayList<>();
            for (String id : ask.videos()) pool.add(new Candidates.Hit(id));
            poolKey = "videos-" + hash(String.join(",", ask.videos()));
        } else {
            Path f = ask.from().isEmpty() ? newestPool(store) : Candidates.poolFileByKey(store, ask.from());
            Candidates.Kept kept = f == null ? null : Candidates.read(f);
            if (kept == null) throw new IllegalArgumentException(ask.from().isEmpty() ? "No candidate pool is kept in this library: run candidates first, then sift with its key in from." : "No candidate pool is kept under the key " + ask.from() + ": candidates says the key of the pool it kept.");
            pool = kept.pool(); poolKey = kept.key();
            if (things.isEmpty()) things.addAll(kept.things());
            RUNNING.put(root, "the pool " + poolKey);   // the pool by its whole key, for the sentence a second call comes back with
        }
        long start = System.currentTimeMillis();
        BooleanSupplier stop = () -> stopWhen.getAsBoolean() || (untilMs > 0 && System.currentTimeMillis() > untilMs);
        int limit = ask.limit() > 0 ? ask.limit() : untilMs > 0 ? Integer.MAX_VALUE : LIMIT_DEFAULT;
        List<String> notes = new ArrayList<>();
        Split s = split(VideoRecall.features(ask.what()));
        String thumbAsk = thumbAsk(things, s.still()), sheetFeatures = s.start().isEmpty() ? forThumb(things, s.still()) : String.join("; ", s.start());
        boolean written = written(s.start());
        String key = poolKey + "-" + hash(ask.what());
        Map<String, Judged> ledger = ledger(store);
        List<Sifted> all = new ArrayList<>();
        for (Candidates.Hit h : pool) all.add(new Sifted(h));
        boolean vision = VideoRecall.visionAnswers();
        if (!vision) {
            notes.add("No model that reads pictures answers (set RESEARCHZOSHO_VISION_DRIVE and RESEARCHZOSHO_VISION_MODEL to one, or run the library's own model with its picture file), so nothing was sifted: give the first five of the pool to the look.");
            Result r = new Result(List.of(), pool.size(), 0, 0, 0, 0, 0, 0, 0, 0, pool.size(), 0, 0, 0, "", key, poolKey, notes, "");
            return withText(store, r, ask, things, s, sheetFeatures);
        }
        // the thumbnail pass, in the pool's order: the cover pictures judged for what one still can show, nine to a call as one numbered grid,
        // the next grid's pictures fetched while the model reads this one; one to a call when the setting says so, or when the model answered
        // three grids in a row with one word for every picture or with no numbered line, as a model that cannot read a grid does
        String sheetHash = hash(sheetFeatures);
        // the first sheets of the thumbnails kept are fetched ahead, in the order the sheet pass takes them, from the moment a thumbnail passes:
        // one yt-dlp call for five specs at a time, while the model judges; a judgement then seldom waits on a fetch
        SheetFetcher fetcher = new SheetFetcher(stop);
        Predicate<String> needsSheet = id -> !ledger.containsKey(id + "|" + SHEET + "|" + sheetHash) && keptFirstSheet(id) == null;
        ThumbPass t = new ThumbPass(store, ledger, all, thumbAsk, things, s.still(), limit, thumbUntil(start, untilMs), stop, progress);
        t.onYes = c -> { if (needsSheet.test(c.hit.id)) fetcher.offer(c.hit.id); };
        int reached, thumbsNew, thumbsJudged = 0, thumbsKept = 0, misses = 0, sheetsJudged = 0, sheetsNew = 0, sheetsKept = 0, noSheet = 0, unchecked = 0;
        long thumbMs, sheetMs = 0;
        boolean gaveUp;
        List<Sifted> passed = new ArrayList<>();
        try {
        t.run();
        reached = t.reached; thumbsNew = t.thumbsNew; thumbMs = t.thumbMs; gaveUp = t.gaveUp;
        for (Sifted c : all.subList(0, reached)) { if (!c.thumbVerdict.isEmpty()) thumbsJudged++; if (c.thumbKept()) thumbsKept++; }
        if (gaveUp) notes.add("The model that reads pictures (" + VideoRecall.modelName() + ") did not answer twice in a row, so the sift stopped at candidate " + (reached + 1) + "; the rest waits for a later sift.");
        if (!t.fellBack.isEmpty()) notes.add(t.fellBack);
        // the first-sheet pass over what the thumbnails kept, the thumbnail's yes before its maybe and each in the pool's order, since the time
        // seldom reaches every survivor and the yes ones are the better bets: the first minutes as small frames, judged whole
        for (Sifted c : all.subList(0, reached)) if (c.thumbVerdict.equals("yes")) passed.add(c);
        for (Sifted c : all.subList(0, reached)) if (c.thumbVerdict.equals("maybe")) passed.add(c);
        for (Sifted c : passed) if (needsSheet.test(c.hit.id)) fetcher.offer(c.hit.id);   // the yes ones were offered as they passed; the maybe ones follow
        Map<String, FirstSheet> fetched = new LinkedHashMap<>();
        for (int i = 0; i < passed.size(); i++) {
            Sifted c = passed.get(i);
            if (gaveUp || stop.getAsBoolean() || sheetsNew >= limit) { unchecked++; continue; }
            Judged j = ledger.get(c.hit.id + "|" + SHEET + "|" + sheetHash);
            if (j == null) {
                long t0 = System.currentTimeMillis();
                if (keptFirstSheet(c.hit.id) == null && !fetched.containsKey(c.hit.id)) {
                    if (fetcher.willDeliver(c.hit.id)) fetcher.await(c.hit.id, Duration.ofMinutes(6));
                    if (keptFirstSheet(c.hit.id) == null && !fetcher.done(c.hit.id)) {
                        // the next few whose sheet is neither judged nor kept nor on the fetcher's way, asked of the helper together
                        List<String> toFetch = new ArrayList<>();
                        for (int k = i; k < passed.size() && toFetch.size() < SPEC_BATCH; k++) {
                            Sifted x = passed.get(k);
                            if (!ledger.containsKey(x.hit.id + "|" + SHEET + "|" + sheetHash) && keptFirstSheet(x.hit.id) == null && !fetched.containsKey(x.hit.id) && !fetcher.done(x.hit.id) && !(k > i && fetcher.willDeliver(x.hit.id))) toFetch.add(x.hit.id);
                        }
                        Map<String, FirstSheet> got;
                        try { got = SHEETS.first(toFetch); } catch (Exception e) { got = Map.of(); }
                        for (var e : got.entrySet()) { fetched.put(e.getKey(), e.getValue()); keepFirstSheet(e.getKey(), e.getValue()); }
                    }
                }
                Path file = keptFirstSheet(c.hit.id);
                String verdict, line;
                boolean keep = true;
                if (file == null && ((fetched.containsKey(c.hit.id) && fetched.get(c.hit.id) == null) || fetcher.none(c.hit.id))) { verdict = "maybe"; line = "YouTube keeps no storyboard for this video, so its start was not judged"; noSheet++; }
                else if (file == null) { verdict = "maybe"; line = "the storyboard could not be had this time, so its start was not judged"; noSheet++; keep = false; }
                else {
                    byte[] img = sheetPicture(file);
                    if (img == null) { verdict = "maybe"; line = "the first sheet could not be opened, so its start was not judged"; noSheet++; keep = false; }
                    else {
                        DriveClient.forgetClassify();
                        String raw = VideoRecall.VISION.describe(img, sheetAsk(gridOf(c.hit.id), sheetFeatures, written));
                        if (VideoRecall.UNANSWERED.getAsBoolean() || raw == null || raw.isBlank()) {
                            sheetMs += System.currentTimeMillis() - t0;
                            misses++;
                            if (misses >= 2) { gaveUp = true; notes.add("The model that reads pictures (" + VideoRecall.modelName() + ") did not answer twice in a row, so the first-sheet pass stopped; the rest waits for a later sift."); }
                            unchecked++;
                            continue;
                        }
                        misses = 0;
                        verdict = verdict(raw); line = Untrusted.line(VideoRecall.withoutVerdict(raw), 200);
                    }
                }
                sheetMs += System.currentTimeMillis() - t0;
                sheetsNew++;
                j = new Judged(c.hit.id, SHEET, sheetHash, verdict, line);
                if (keep) record(store, ledger, j);
            }
            c.sheetVerdict = j.verdict(); c.sheetLine = j.line();
            sheetsJudged++;
            if (c.sheetKept()) sheetsKept++;
            fetcher.consumed(c.hit.id);
            if (sheetsJudged % 10 == 0) progress.accept("first sheets: " + sheetsJudged + " judged, " + sheetsKept + " kept");
        }
        } finally { fetcher.quit(); }
        // the survivors: judged on their first sheet and kept, yes before maybe, a line that quotes the writing read before one that does not when
        // what is remembered is writing, a thumbnail's yes before its maybe, then the pool's order
        List<Sifted> survivors = new ArrayList<>();
        for (Sifted c : passed) if (c.sheetKept()) survivors.add(c);
        survivors.sort((a, b) -> {
            int c = rank(a.sheetVerdict) - rank(b.sheetVerdict);
            if (c == 0 && written) c = Boolean.compare(reads(b.sheetLine), reads(a.sheetLine));
            if (c == 0) c = rank(a.thumbVerdict) - rank(b.thumbVerdict);
            return c;
        });
        Result r = new Result(survivors, pool.size(), reached, thumbsJudged, thumbsNew, thumbsKept, sheetsJudged, sheetsNew, sheetsKept, noSheet, pool.size() - reached, unchecked,
                thumbsNew > 0 ? thumbMs / 1000.0 / thumbsNew : 0, sheetsNew > 0 ? sheetMs / 1000.0 / sheetsNew : 0, t.mode(), key, poolKey, notes, "");
        return withText(store, r, ask, things, s, sheetFeatures);
    }

    static int rank(String verdict) { return verdict.equals("yes") ? 0 : verdict.equals("maybe") ? 1 : 2; }

    /** When the thumbnail pass must end: its share of the time to {@code untilMs}; 0 for no time. */
    static long thumbUntil(long start, long untilMs) { return untilMs > 0 ? start + (long) ((untilMs - start) * THUMB_SHARE) : 0; }

    // ---- the thumbnail pass: nine cover pictures to a call, as one numbered grid

    /**
     * The thumbnail pass over the pool in its order. The candidates not yet judged are taken a grid's worth at a time, those judged before
     * read from the ledger as they are passed; each grid's pictures are fetched while the model reads the one before; a cover picture that
     * could not be fetched is a maybe, not kept, and not in the grid. A grid's answer is one line per number; a number the answer lacks is
     * asked alone, once; a line whose word cannot be read is a maybe. Three grids in a row answered with one word for every picture, or
     * with no numbered line, say the model cannot read a grid: the rest go one to a call, and those three grids' pictures are judged
     * again one by one, in place of the grid's word.
     */
    static final class ThumbPass {
        final LibraryStore store; final Map<String, Judged> ledger; final List<Sifted> all;
        final String thumbAsk, thumbHash; final List<String> things, still;
        final int limit; final long until; final BooleanSupplier stop; final Consumer<String> progress;
        final int cells = gridCells();
        boolean grids = cells > 1, gaveUp = false;
        int reached = 0, thumbsNew = 0, rejudged = 0, gridCalls = 0, singleCalls = 0, misses = 0, sameInARow = 0;
        long thumbMs = 0;
        String fellBack = "";
        final Deque<List<Sifted>> lastGrids = new ArrayDeque<>();
        /** Told of each candidate judged yes, in the pool's order, as its batch ends: the sheet fetcher starts on it. */
        Consumer<Sifted> onYes = c -> { };

        ThumbPass(LibraryStore store, Map<String, Judged> ledger, List<Sifted> all, String thumbAsk, List<String> things, List<String> still, int limit, long until, BooleanSupplier stop, Consumer<String> progress) {
            this.store = store; this.ledger = ledger; this.all = all; this.thumbAsk = thumbAsk; this.thumbHash = hash(thumbAsk); this.things = things; this.still = still;
            this.limit = limit; this.until = until; this.stop = stop; this.progress = progress;
        }

        Judged kept(Sifted c) { return ledger.get(c.hit.id + "|" + THUMB + "|" + thumbHash); }

        /** The candidates from {@code from} not yet judged, up to {@code most} of them; {@code to[0]} is set to where the walk ended. */
        List<Sifted> gather(int from, int most, int[] to) {
            List<Sifted> batch = new ArrayList<>();
            int j = from;
            while (j < all.size() && batch.size() < most) { Sifted c = all.get(j); if (kept(c) == null) batch.add(c); j++; }
            to[0] = j;
            return batch;
        }

        void run() {
            ExecutorService ahead = Executors.newSingleThreadExecutor(r -> { Thread th = new Thread(r, "sift-fetch"); th.setDaemon(true); return th; });
            Future<?> fetching = null;
            try {
                int i = 0, judgedSoFar = 0;
                while (i < all.size()) {
                    if (stop.getAsBoolean() || thumbsNew >= limit || (until > 0 && System.currentTimeMillis() > until)) break;
                    int[] to = {i};
                    List<Sifted> batch = gather(i, Math.min(grids ? cells : 1, limit - thumbsNew), to);
                    if (fetching != null) { try { fetching.get(); } catch (Exception ignored) { } fetching = null; }   // this batch's pictures, fetched while the last was read
                    int[] after = {to[0]};
                    List<Sifted> next = gather(to[0], grids ? cells : 1, after);
                    if (!next.isEmpty()) { List<String> ids = new ArrayList<>(); for (Sifted c : next) ids.add(c.hit.id); fetching = ahead.submit(() -> { for (String id : ids) { if (stop.getAsBoolean()) return; thumbnail(id); } }); }
                    long t0 = System.currentTimeMillis();
                    boolean answered = batch.isEmpty() || judge(batch);
                    thumbMs += System.currentTimeMillis() - t0;
                    if (!answered) {
                        misses++;
                        if (misses >= 2) { gaveUp = true; break; }   // the give-up rule: twice unanswered in a row, and the rest waits for a later sift
                        continue;   // the same batch once more
                    }
                    misses = 0;
                    for (int k = i; k < to[0]; k++) { Sifted c = all.get(k); if (c.thumbVerdict.isEmpty()) { Judged j = kept(c); if (j != null) { c.thumbVerdict = j.verdict(); c.thumbLine = j.line(); } } }
                    for (int k = i; k < to[0]; k++) { Sifted c = all.get(k); if (c.thumbVerdict.equals("yes")) onYes.accept(c); }
                    reached = to[0]; i = to[0];
                    int judged = 0, keptNow = 0;
                    for (Sifted c : all.subList(0, reached)) { if (!c.thumbVerdict.isEmpty()) judged++; if (c.thumbKept()) keptNow++; }
                    if (judged / 25 > judgedSoFar / 25) progress.accept("thumbnails: " + judged + " judged, " + keptNow + " kept");
                    judgedSoFar = judged;
                }
            } finally { ahead.shutdownNow(); }
        }

        /** The batch judged, as a grid or one by one; false when the model did not answer the call, and nothing of the batch is kept. */
        boolean judge(List<Sifted> batch) {
            List<Sifted> shown = new ArrayList<>();
            List<byte[]> pictures = new ArrayList<>();
            for (Sifted c : batch) {
                byte[] img = thumbnail(c.hit.id);
                if (img == null) { c.thumbVerdict = "maybe"; c.thumbLine = "the thumbnail could not be fetched, so it was not judged"; continue; }   // not kept: a fetch that failed is tried again next time
                shown.add(c); pictures.add(img);
            }
            if (shown.isEmpty()) return true;
            if (!grids || shown.size() == 1) {
                for (int k = 0; k < shown.size(); k++) { if (!one(shown.get(k), pictures.get(k))) return k > 0; }
                return true;
            }
            DriveClient.forgetClassify();
            String raw = VideoRecall.VISION.describe(grid(pictures), gridAsk(shown.size(), things, still));
            if (VideoRecall.UNANSWERED.getAsBoolean() || raw == null || raw.isBlank()) return false;
            gridCalls++;
            Map<Integer, Cell> got = parseGrid(raw, shown.size());
            boolean oneWord = got.size() == shown.size() && got.values().stream().map(Cell::verdict).distinct().count() == 1;
            sameInARow = oneWord || got.isEmpty() ? sameInARow + 1 : 0;
            List<Sifted> byGrid = new ArrayList<>();
            for (int k = 0; k < shown.size(); k++) {
                Sifted c = shown.get(k);
                Cell cell = got.get(k + 1);
                if (cell == null) { one(c, pictures.get(k)); continue; }   // the number the answer lacks, asked alone once; unanswered, it is not judged this time
                keep(c, cell.verdict(), Untrusted.line(cell.line(), 200));
                byGrid.add(c);
            }
            lastGrids.add(byGrid);
            while (lastGrids.size() > SAME_GRIDS) lastGrids.removeFirst();
            if (sameInARow >= SAME_GRIDS) {
                grids = false;
                int again = 0;
                for (List<Sifted> g : lastGrids) for (Sifted c : g) { byte[] img = thumbnail(c.hit.id); if (img != null && one(c, img)) { again++; thumbsNew--; } }
                rejudged = again;
                fellBack = "The model that reads pictures (" + VideoRecall.modelName() + ") answered " + SAME_GRIDS + " grids in a row with one word for every picture or with no numbered line, as a model that cannot read a grid does, so the thumbnails went one to a call from there, and the " + again + " of those grids were judged again one by one.";
            }
            return true;
        }

        /** One thumbnail judged in a call of its own; false when the model did not answer. */
        boolean one(Sifted c, byte[] img) {
            DriveClient.forgetClassify();
            String raw = VideoRecall.VISION.describe(img, thumbAsk);
            if (VideoRecall.UNANSWERED.getAsBoolean() || raw == null || raw.isBlank()) return false;
            singleCalls++;
            keep(c, verdict(raw), Untrusted.line(VideoRecall.withoutVerdict(raw), 200));
            return true;
        }

        void keep(Sifted c, String verdict, String line) {
            c.thumbVerdict = verdict; c.thumbLine = line;
            thumbsNew++;
            record(store, ledger, new Judged(c.hit.id, THUMB, thumbHash, verdict, line));
        }

        /** How the thumbnails were judged, for the text: nine to a call as one numbered grid, or one to a call, and why. */
        String mode() {
            if (thumbsNew == 0) return "";
            if (cells == 1) return "one to a call";
            if (!fellBack.isEmpty()) return "one to a call after " + SAME_GRIDS + " grids in a row came back with one word for every picture or with no numbered line (" + rejudged + " judged again one by one)";
            return few(cells) + " to a call as one numbered grid" + (singleCalls > 0 ? ", " + singleCalls + " alone where a grid's answer left them out or the pool's end left one" : "");
        }
    }

    /** A grid's count in words, for the text. */
    static String few(int n) { return n >= 2 && n <= 9 ? new String[]{"", "", "two", "three", "four", "five", "six", "seven", "eight", "nine"}[n] : String.valueOf(n); }

    // ---- the sheet fetcher: the first sheets of the thumbnails kept, fetched ahead of the sheet pass in its order

    /**
     * One thread that fetches the first sheets of the thumbnails kept, in the order the sheet pass will take them, from the moment the
     * thumbnail pass keeps one: one yt-dlp call for {@link #SPEC_BATCH} specs at a time, each sheet kept under the helper's folder, at most
     * {@link #SHEETS_AHEAD} ahead of what the sheet pass has taken. The sheet pass waits for a sheet on the fetcher's way and fetches for
     * itself what is not; a video YouTube keeps no storyboard for is remembered as such. The ninth live run paid the fetch inside every
     * sheet's judgement, 13.8 seconds a sheet beside another sift; the tenth, with the fetch ahead, paid 8.9, the model's reading of a sheet.
     */
    static final class SheetFetcher implements Runnable {
        private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private final Set<String> queued = ConcurrentHashMap.newKeySet(), done = ConcurrentHashMap.newKeySet(), none = ConcurrentHashMap.newKeySet();
        private final Semaphore ahead = new Semaphore(SHEETS_AHEAD);
        private final BooleanSupplier stop;
        private volatile boolean quit = false, ended = false, held = false;
        private Thread thread;

        SheetFetcher(BooleanSupplier stop) { this.stop = stop; }

        /** A video whose first sheet the sheet pass will want, in the order offered; starts the thread at the first. */
        synchronized void offer(String id) {
            if (!queued.add(id)) return;
            queue.add(id);
            if (thread == null) { thread = new Thread(this, "sift-sheets"); thread.setDaemon(true); thread.start(); }
        }

        boolean willDeliver(String id) { return queued.contains(id) && !ended && !done.contains(id); }
        boolean done(String id) { return done.contains(id); }
        boolean none(String id) { return none.contains(id); }
        /** The sheet pass took this one: the fetcher may go one further ahead. */
        void consumed(String id) { if (queued.contains(id)) ahead.release(); }
        /** The sift is over: the fetcher stops at its next step; a call to the helper under way ends on its own. */
        void quit() { quit = true; }

        /** Waits until the fetcher has dealt with {@code id}, or has ended, or is held at its lead while the pass waits on one beyond its present batch, or the time is up. */
        void await(String id, Duration most) {
            long until = System.currentTimeMillis() + most.toMillis();
            while (!done.contains(id) && !ended && !(held && !current.contains(id)) && !stop.getAsBoolean() && System.currentTimeMillis() < until) {
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }

        /** The batch in hand. */
        private final Set<String> current = ConcurrentHashMap.newKeySet();

        @Override public void run() {
            try {
                while (!quit && !stop.getAsBoolean()) {
                    String first = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (first == null) continue;
                    List<String> batch = new ArrayList<>(List.of(first));
                    long fill = System.currentTimeMillis() + 3000;   // a moment for the thumbnail pass to keep a few more, so that one call asks five
                    while (batch.size() < SPEC_BATCH && System.currentTimeMillis() < fill) { String x = queue.poll(200, TimeUnit.MILLISECONDS); if (x != null) batch.add(x); }
                    current.clear(); current.addAll(batch);
                    for (int i = 0; i < batch.size(); i++) {   // a permit per sheet, held while the pass is behind
                        while (!ahead.tryAcquire(1, TimeUnit.SECONDS)) { held = true; if (quit || stop.getAsBoolean()) return; }
                        held = false;
                    }
                    if (quit || stop.getAsBoolean()) return;
                    List<String> toFetch = new ArrayList<>();
                    for (String id : batch) { if (keptFirstSheet(id) == null) toFetch.add(id); else done.add(id); }
                    if (!toFetch.isEmpty()) {
                        Map<String, FirstSheet> got;
                        try { got = SHEETS.first(toFetch); } catch (Exception e) { got = Map.of(); }
                        for (var e : got.entrySet()) { if (e.getValue() == null) none.add(e.getKey()); else keepFirstSheet(e.getKey(), e.getValue()); }
                        done.addAll(toFetch);
                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { ended = true; }
        }
    }

    static int columns(int count) { return count <= 1 ? 1 : count <= 4 ? 2 : 3; }
    static int rows(int count) { return (count + columns(count) - 1) / columns(count); }
    /** The top-left corner of cell {@code index} (from 0) in a grid of {@code count} pictures. */
    static int[] cellOrigin(int index, int count) { int c = columns(count); return new int[]{GAP + (index % c) * (CELL_W + GAP), GAP + (index / c) * (CELL_H + GAP)}; }

    /** The digits 1 to 9 as three by five dots, drawn by the program itself so that no font on the machine is needed. */
    static final String[] DIGITS = {"111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001", "111100111001111", "111100111101111", "111001001001001", "111101111101111", "111101111001111"};

    /**
     * The pictures as one grid, the model's one call: each scaled whole to a cell of the cover picture's shape, a thin grey gap between
     * cells, and each cell's number from 1 in a black box in its top-left corner, white dots on black; a PNG.
     */
    static byte[] grid(List<byte[]> pictures) {
        int n = pictures.size(), cols = columns(n), rows = rows(n);
        BufferedImage out = new BufferedImage(GAP + cols * (CELL_W + GAP), GAP + rows * (CELL_H + GAP), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(GRID_BACK); g.fillRect(0, 0, out.getWidth(), out.getHeight());
        for (int i = 0; i < n; i++) {
            int[] o = cellOrigin(i, n);
            BufferedImage p = null;
            try { p = ImageIO.read(new ByteArrayInputStream(pictures.get(i))); } catch (IOException | RuntimeException ignored) { }
            if (p == null) { g.setColor(Color.BLACK); g.fillRect(o[0], o[1], CELL_W, CELL_H); }
            else g.drawImage(p, o[0], o[1], CELL_W, CELL_H, null);
            g.setColor(Color.BLACK); g.fillRect(o[0], o[1], LABEL, LABEL);
            g.setColor(Color.WHITE);
            String dots = DIGITS[(i + 1) % 10];
            int x0 = o[0] + (LABEL - 3 * DIGIT) / 2, y0 = o[1] + (LABEL - 5 * DIGIT) / 2;
            for (int k = 0; k < 15; k++) if (dots.charAt(k) == '1') g.fillRect(x0 + (k % 3) * DIGIT, y0 + (k / 3) * DIGIT, DIGIT, DIGIT);
        }
        g.dispose();
        try { ByteArrayOutputStream bo = new ByteArrayOutputStream(); ImageIO.write(out, "png", bo); return bo.toByteArray(); }
        catch (IOException e) { return new byte[0]; }
    }

    /** The question of a grid of cover pictures: one line per number, the word first, then a few words on what that picture shows. */
    static String gridAsk(int n, List<String> things, List<String> still) {
        int cols = columns(n);
        return "This picture holds " + n + " cover pictures (thumbnails) of " + n + " different videos in a grid of " + cols + (cols == 1 ? " column" : " columns") + ", numbered 1 to " + n + " left to right and then row by row; each one's number is in the black box in its top-left corner. "
                + looking(things, still)
                + ". For each numbered picture, on a line of its own: its number, a colon, one word, yes, maybe or no, a dash, and in at most six words what that picture shows, like \"3: maybe — a cat on a sofa\". "
                + thumbRule(things, still) + " " + GRID_EACH + " " + n + " lines, one per picture, in order, nothing else.";
    }

    /**
     * Said to the grid alone: asked about nine at once, the model ranked the cells against each other and said no to a plain picture of
     * the thing that lacked a remembered detail, keeping 6 and 13 of 27 where the same question asked of each picture alone kept 15 and
     * 23; with this sentence it kept 17 and 22, one yes-to-no flip in each 27 (measured 2026-11-05 on the ninth run's pool, the 27B).
     */
    static final String GRID_EACH = "Judge each picture on its own, not against the others. The word no is only for a picture of something that is not that thing at all; a picture of that thing that lacks what they remember is maybe.";

    /** One cell's answer as parsed: the word, and the line without it. */
    record Cell(String verdict, String line) { }

    static final Pattern GRID_LINE = Pattern.compile("^\\W*(\\d{1,2})\\W+(.*)$");
    static final Pattern VERDICT_HEAD = Pattern.compile("(?i)^[\\s*_]*(yes|maybe|no)\\b"), VERDICT_TAIL = Pattern.compile("(?i)[\\s*_\\-–—:,.(]+(yes|maybe|no)[\\s*_.!)]*$");

    /**
     * A grid's answer read strictly, by number: a line that begins with a number from 1 to {@code n} answers that cell, its word the first
     * word after the number (or the last word of the line); a line whose word cannot be read is a maybe; a number no line begins with is
     * absent, for the caller to ask alone. A second line for a number is ignored.
     */
    static Map<Integer, Cell> parseGrid(String raw, int n) {
        Map<Integer, Cell> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String l : raw.split("\\R")) {
            Matcher m = GRID_LINE.matcher(l.strip());
            if (!m.matches()) continue;
            int k = Integer.parseInt(m.group(1));
            if (k < 1 || k > n || out.containsKey(k)) continue;
            String rest = m.group(2).strip(), verdict = "maybe", line = rest;
            Matcher head = VERDICT_HEAD.matcher(rest), tail = VERDICT_TAIL.matcher(rest);
            if (head.find()) { verdict = head.group(1).toLowerCase(Locale.ROOT); line = rest.substring(head.end()); }
            else if (tail.find()) { verdict = tail.group(1).toLowerCase(Locale.ROOT); line = rest.substring(0, tail.start()); }
            line = line.replaceFirst("^[\\s*_:\\-–—]+", "").replaceFirst("[\\s*_:\\-–—]+$", "").strip();
            out.put(k, new Cell(verdict, line));
        }
        return out;
    }

    /** The newest kept pool in the library, when the call names none; null when there is none. */
    static Path newestPool(LibraryStore store) {
        Path dir = Candidates.poolDir(store);
        if (!Files.isDirectory(dir)) return null;
        try (var s = Files.list(dir)) { return s.filter(p -> p.getFileName().toString().endsWith(".json")).max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString())).orElse(null); }
        catch (IOException e) { return null; }
    }

    // ---- the asks and the verdict

    /** The things a thumbnail is judged for, as one phrase: the thing itself by its names, and what one still can show. */
    static String forThumb(List<String> things, List<String> still) {
        String thing = things.isEmpty() ? "" : things.size() == 1 ? things.get(0) : things.get(0) + " (" + String.join(" / ", things.subList(1, things.size())) + ")";
        if (thing.isEmpty()) return String.join("; ", still);
        return still.isEmpty() ? thing : thing + "; " + String.join("; ", still);
    }

    /** The thing by its names, for the asks: the first, and the others as what it is also called. */
    static String thingNamed(List<String> things) { return things.isEmpty() ? "" : things.size() == 1 ? things.get(0) : things.get(0) + " (also called " + String.join(", ", things.subList(1, things.size())) + ")"; }

    /** Who is looking for what: the thing by its names and the things remembered that a still can show, the first sentence of both asks. */
    static String looking(List<String> things, List<String> still) {
        String thing = thingNamed(things);
        return "A person is looking for a video" + (thing.isEmpty() ? "" : " that makes or shows " + thing) + (still.isEmpty() ? "" : (thing.isEmpty() ? " and" : ", and") + " remembers of it: " + String.join("; ", still));
    }

    /**
     * The one rule of the thumbnail's word, the same whether the picture is asked alone or in a grid: yes for the thing with what is
     * remembered, maybe for the thing without it or too little to tell, no only for a different thing; a cover picture is one moment. Asked
     * in a grid without the middle clause, the model said no to a plain picture of the thing that lacked a remembered detail, and dropped
     * eight of twenty-seven that it kept when asked alone (measured 2026-11-05).
     */
    static String thumbRule(List<String> things, List<String> still) {
        boolean thing = !things.isEmpty();
        return "yes when the picture shows " + (thing ? "that thing" + (still.isEmpty() ? "" : " with what they remember") : "what they remember")
                + ", maybe when it " + (thing && !still.isEmpty() ? "shows that thing without what they remember, or " : "") + "may be that or shows too little to tell, no only when the picture plainly shows a different thing"
                + (thing ? " than " + things.get(0) : "") + ". A cover picture is one moment of the video, so what it does not show is not absent from the video: say no only for what it shows.";
    }

    /** The question of a thumbnail asked alone: what it shows, then its word by the rule. */
    static String thumbAsk(List<String> things, List<String> still) {
        return looking(things, still) + ". This is the video's cover picture, its thumbnail. First, in at most twelve words, what the picture shows. Then, on a line of its own, one word: " + thumbRule(things, still);
    }

    /** The question of a first sheet: the frames in order from the start, then yes, maybe or no for the things remembered about the start; a thing remembered as writing must be read as writing, and quoted. */
    static String sheetAsk(FirstSheetGrid g, String features, boolean written) {
        int n = g == null ? 0 : g.tiles();
        String step = g == null || g.step() <= 0 ? "a few seconds" : (Math.round(g.step()) == 1 ? "one second" : Math.round(g.step()) + " seconds");
        return "These are the first " + (n > 0 ? n + " " : "") + "small frames of a video, one every " + step + " from its very start, in order left to right and then row by row. A person remembers of the start of the video: " + features
                + ". First, in at most twenty words, what the frames show" + (written ? ", and quote, exactly as written, the writing and figures you can read in them" : ", any words shown among them")
                + ". Then, on a line of its own, one word: yes when the frames show it, maybe when they show something like it or too little to tell, no when they plainly show something else at the start."
                + (written ? " What the person remembers as writing, sizes or figures on screen must be readable in the frames as writing: a ruler, a tape or a piece being measured with no figures readable is not writing on screen, and is maybe, not yes." : "")
                + " The frames are small, so small writing may not be readable.";
    }

    /** The model's word as yes, maybe or no; an answer with no word in it is a maybe, never a no. */
    static String verdict(String raw) {
        String w = VideoRecall.verdictOf(raw == null ? "" : raw);
        return w.equals("found") ? "yes" : w.equals("no") ? "no" : "maybe";
    }

    // ---- the ledger: every judgement kept, so that nothing is judged twice

    record Judged(String id, String pass, String askHash, String verdict, String line) { }

    static Path ledgerFile(LibraryStore store) { return store.root().resolve("catalog").resolve("sift").resolve("judged.jsonl"); }

    static Map<String, Judged> ledger(LibraryStore store) {
        Map<String, Judged> out = new LinkedHashMap<>();
        Path f = ledgerFile(store);
        if (!Files.isRegularFile(f)) return out;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode j = M.readTree(line);
                Judged x = new Judged(j.path("id").asText(""), j.path("pass").asText(""), j.path("ask").asText(""), j.path("verdict").asText(""), j.path("line").asText(""));
                out.put(x.id() + "|" + x.pass() + "|" + x.askHash(), x);
            }
        } catch (IOException | RuntimeException ignored) { }
        return out;
    }

    static void record(LibraryStore store, Map<String, Judged> ledger, Judged j) {
        ledger.put(j.id() + "|" + j.pass() + "|" + j.askHash(), j);
        try {
            Path f = ledgerFile(store);
            Files.createDirectories(f.getParent());
            ObjectNode o = M.createObjectNode().put("id", j.id()).put("pass", j.pass()).put("ask", j.askHash()).put("verdict", j.verdict()).put("line", j.line()).put("model", VideoRecall.modelName()).put("at", Instant.now().toString());
            Files.writeString(f, o.toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toLowerCase(Locale.ROOT).strip().getBytes(StandardCharsets.UTF_8)), 0, 8); }
        catch (Exception e) { return "0"; }
    }

    // ---- the pictures: fetched once, kept under the helper's folder

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static volatile long lastFetchAt = 0;

    static byte[] fetchPlain(String url) throws Exception {
        HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", Candidates.USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() == 200) return r.body();
        throw new IOException("HTTP " + r.statusCode());
    }

    /** A picture by its address: plainly, paced; through the helper's tunnel only when the plain fetch was refused; null when it could not be had. */
    static byte[] picture(String url) {
        synchronized (Sift.class) {
            long wait = lastFetchAt + paceMs - System.currentTimeMillis();
            if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; } }
            lastFetchAt = System.currentTimeMillis();
        }
        try { return PICTURES.fetch(url); }
        catch (Exception e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("HTTP 403") || m.contains("HTTP 429") || m.contains("HTTP 401")) return Video.fetchBytesThroughTunnel(url, Duration.ofSeconds(30));
            return null;
        }
    }

    static Path thumbFile(String id) { return VideoRecall.work().resolve("thumbs").resolve(id + ".jpg"); }

    /** A video's thumbnail, kept once fetched; null when it could not be had. */
    static byte[] thumbnail(String id) {
        Path f = thumbFile(id);
        try {
            if (Files.isRegularFile(f)) return Files.readAllBytes(f);
            byte[] b = picture(thumbnailUrl(id));
            if (b == null || b.length < 100) return null;
            Files.createDirectories(f.getParent());
            Files.write(f, b);
            return b;
        } catch (IOException e) { return null; }
    }

    /** The grid of a first sheet as kept: how many tiles it holds of the video, and the seconds between them. */
    record FirstSheetGrid(int rows, int columns, double step, double duration) {
        int tiles() { int all = rows * columns; return duration > 0 && step > 0 ? Math.min(all, (int) Math.ceil(duration / step)) : all; }
    }

    /** The first sheet kept for a video: the one of a whole storyboard the look fetched, else the one the sift fetched; null when none. */
    static Path keptFirstSheet(String id) {
        Path dir = Storyboards.dir(VideoRecall.work(), id);
        if (Files.isRegularFile(dir.resolve("sheets.json"))) for (String ext : new String[]{"webp", "png"}) { Path p = dir.resolve("sheet-000." + ext); if (Files.isRegularFile(p)) return p; }
        for (String ext : new String[]{"webp", "png"}) { Path p = dir.resolve("first." + ext); if (Files.isRegularFile(p)) return p; }
        return null;
    }

    static FirstSheetGrid gridOf(String id) {
        Path dir = Storyboards.dir(VideoRecall.work(), id);
        for (String name : new String[]{"sheets.json", "first.json"}) {
            Path p = dir.resolve(name);
            if (!Files.isRegularFile(p)) continue;
            try {
                JsonNode o = M.readTree(Files.readString(p, StandardCharsets.UTF_8));
                double fps = o.path("fps").asDouble(0);
                double step = o.has("step") ? o.path("step").asDouble(0) : fps > 0 ? new Storyboards.Sheets(1, 1, fps, 0, List.of()).step() : 0;
                return new FirstSheetGrid(o.path("rows").asInt(1), o.path("columns").asInt(1), step, o.path("duration").asDouble(0));
            } catch (IOException | RuntimeException ignored) { }
        }
        return null;
    }

    static void keepFirstSheet(String id, FirstSheet s) {
        if (s == null || s.image() == null) return;
        try {
            Path dir = Storyboards.dir(VideoRecall.work(), id);
            Files.createDirectories(dir);
            Path file = Storyboards.keepSheet(s.image(), dir, 0);
            if (file == null) return;
            Path named = dir.resolve("first." + (file.getFileName().toString().endsWith(".webp") ? "webp" : "png"));
            Files.move(file, named, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(dir.resolve("first.json"), M.createObjectNode().put("rows", s.rows()).put("columns", s.columns()).put("step", s.step()).put("duration", s.duration()).toString() + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    /** A kept first sheet as a PNG at least {@link #SHEET_WIDTH} wide, for the model, as the tiles go to it; null when it cannot be opened. */
    static byte[] sheetPicture(Path file) {
        try {
            BufferedImage in = Storyboards.sheet(file, VideoRecall.FFMPEG);
            if (in == null) return null;
            int k = Math.max(1, (int) Math.ceil((double) SHEET_WIDTH / in.getWidth()));
            BufferedImage out = new BufferedImage(in.getWidth() * k, in.getHeight() * k, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(in, 0, 0, out.getWidth(), out.getHeight(), null);
            g.dispose();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bo);
            return bo.toByteArray();
        } catch (IOException | RuntimeException e) { return null; }
    }

    /**
     * The first sheets of several videos through the helper: one yt-dlp call for their storyboard specs (the grid, the rate, the sheets'
     * addresses), then the first sheet of each fetched plainly, through the tunnel when refused. A video YouTube keeps no storyboard for
     * maps to null; one whose spec did not come back is absent, and asked again another time.
     */
    static Map<String, FirstSheet> firstSheetsLive(List<String> ids) {
        Map<String, FirstSheet> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        List<String> cmd = new ArrayList<>(List.of("--dump-single-json", "--no-playlist", "--ignore-errors"));
        for (String id : ids) cmd.add(VideoRecall.address(id));
        Video.Result r = Video.ytdlp(cmd, Duration.ofMinutes(5), Video.readPaceMs);
        if (r.out() == null) return out;
        for (String line : r.out().split("\\R")) {
            String l = line.strip();
            if (!l.startsWith("{")) continue;
            JsonNode j;
            try { j = M.readTree(l); } catch (IOException e) { continue; }
            String id = j.path("id").asText("");
            if (!ids.contains(id)) continue;
            JsonNode sb = null;
            for (JsonNode f : j.path("formats")) if (f.path("format_id").asText("").equals("sb0")) sb = f;
            if (sb == null) { out.put(id, null); continue; }
            String url = sb.path("fragments").path(0).path("url").asText("");
            if (url.isEmpty()) url = sb.path("url").asText("").replace("M$M", "M0").replace("$M", "0");
            int rows = sb.path("rows").asInt(0), columns = sb.path("columns").asInt(0);
            double fps = sb.path("fps").asDouble(0), duration = j.path("duration").asDouble(0);
            if (url.isEmpty() || rows <= 0 || columns <= 0 || fps <= 0) { out.put(id, null); continue; }
            byte[] img = picture(url);
            if (img == null || img.length < 100) continue;
            out.put(id, new FirstSheet(rows, columns, new Storyboards.Sheets(rows, columns, fps, duration, List.of()).step(), duration, img));
        }
        return out;
    }

    // ---- the text

    static Result withText(LibraryStore store, Result r, Ask ask, List<String> things, Split s, String sheetFeatures) {
        String text = text(r, ask, things, s, sheetFeatures);
        String key = r.key();
        Result out = new Result(r.survivors(), r.pool(), r.reached(), r.thumbsJudged(), r.thumbsNew(), r.thumbsKept(), r.sheetsJudged(), r.sheetsNew(), r.sheetsKept(), r.noSheet(), r.notSifted(), r.unchecked(), r.thumbSeconds(), r.sheetSeconds(), r.thumbMode(), key, r.poolKey(), r.notes(), text);
        if (store != null) {
            try {
                StringBuilder whole = new StringBuilder(text);
                int i = 0;
                for (Sifted c : r.survivors()) { if (i++ >= SHOWN) whole.append(i).append(". ").append(line(c)).append('\n'); }
                RawCapture.capture(store, "researchzosho://sift/" + key, whole.toString(), "Sifted candidates for " + Acquisitions.compress(ask.what(), 80), "researchzosho-sift", "");
            } catch (RuntimeException ignored) { }
        }
        return out;
    }

    static String seconds(double s) { return s >= 10 ? String.valueOf(Math.round(s)) : String.format(Locale.ROOT, "%.1f", s); }

    static String line(Sifted c) {
        List<String> facts = new ArrayList<>();
        facts.add(c.hit.link());
        facts.add(c.hit.shownTitle().isEmpty() ? "(no title read)" : "\"" + c.hit.shownTitle().replace("\n", " ") + "\"");
        if (!c.hit.channel.isEmpty()) facts.add(c.hit.channel);
        facts.add("thumbnail " + c.thumbVerdict + (c.thumbLine.isEmpty() ? "" : ": " + c.thumbLine));
        facts.add("first sheet " + c.sheetVerdict + (c.sheetLine.isEmpty() ? "" : ": " + c.sheetLine));
        return String.join(" · ", facts);
    }

    static String text(Result r, Ask ask, List<String> things, Split s, String sheetFeatures) {
        StringBuilder b = new StringBuilder();
        b.append("Sifted ").append(r.reached()).append(" of ").append(r.pool()).append(" candidates for: ").append(ask.what()).append(" (").append(ask.videos().isEmpty() ? "the pool " + r.poolKey() : "the videos given").append(").");
        for (String n : r.notes()) b.append(' ').append(n);
        b.append('\n');
        if (r.thumbsJudged() > 0 || r.reached() > 0) {
            b.append("The thumbnail pass judged ").append(r.thumbsJudged()).append(r.thumbsNew() < r.thumbsJudged() ? " (" + (r.thumbsJudged() - r.thumbsNew()) + " from earlier judgements)" : "").append(": kept ").append(r.thumbsKept()).append(", dropped ").append(r.thumbsJudged() - r.thumbsKept())
             .append(r.thumbsNew() > 0 ? "; " + r.thumbMode() + "; about " + seconds(r.thumbSeconds()) + " seconds a thumbnail, fetch and judgement, with " + VideoRecall.modelName() : "").append(".\n");
            b.append("The first-sheet pass judged ").append(r.sheetsJudged()).append(" of the ").append(r.thumbsKept()).append(r.sheetsNew() < r.sheetsJudged() ? " (" + (r.sheetsJudged() - r.sheetsNew()) + " from earlier judgements)" : "").append(": kept ").append(r.sheetsKept()).append(", dropped ").append(r.sheetsJudged() - r.sheetsKept())
             .append(r.noSheet() > 0 ? "; " + r.noSheet() + " had no storyboard to judge and are kept as maybe" : "").append(r.sheetsNew() > 0 ? "; about " + seconds(r.sheetSeconds()) + " seconds a sheet" : "").append(".\n");
        }
        b.append("Judged on the thumbnail: ").append(forThumb(things, s.still()).isEmpty() ? "what you remember" : forThumb(things, s.still())).append(". Judged on the first sheet, the first minutes as small frames: ").append(sheetFeatures.isEmpty() ? "the same" : sheetFeatures).append('.');
        List<String> left = new ArrayList<>(s.rest());
        if (!left.isEmpty()) b.append(" Left to the look, since no one picture shows them: ").append(String.join("; ", left)).append('.');
        if (!s.unseen().isEmpty()) b.append(" That nobody speaks cannot be seen in a picture; the look reads it from the captions.");
        b.append('\n');
        if (r.survivors().isEmpty()) b.append(r.reached() == 0 ? "Nothing was sifted." : "No candidate survived both passes among those sifted.").append('\n');
        else {
            int to = Math.min(SHOWN, r.survivors().size());
            b.append("Survivors, best first (yes on the first sheet before maybe, ").append(written(s.start()) ? "a line that quotes the writing read before one that does not, " : "").append("then yes on the thumbnail before maybe; YouTube's titles and the model's lines, fenced), ").append(to).append(" of ").append(r.survivors().size()).append(":\n").append(Fence.open("SIFTED")).append('\n');
            for (int i = 0; i < to; i++) b.append(i + 1).append(". ").append(line(r.survivors().get(i))).append('\n');
            b.append(Fence.close("SIFTED")).append('\n').append(Fence.rule("SIFTED")).append('\n');
            if (r.survivors().size() > to) b.append("The other ").append(r.survivors().size() - to).append(" survivors are kept under the library with these.\n");
        }
        List<String> cut = new ArrayList<>();
        if (r.notSifted() > 0) cut.add(r.notSifted() + " candidates were not sifted");
        if (r.unchecked() > 0) cut.add(r.unchecked() + " thumbnail survivors were not checked on their first sheet");
        if (!cut.isEmpty()) b.append(String.join(", and ", cut)).append(" before the time ran out: sift again with page=").append(ask.page() + 1).append(" to go on from there; nothing is judged twice.\n");
        if (!r.survivors().isEmpty()) b.append("Give the first five to one look together (videos), sparse, for every remembered thing at once; then the next five, while the budget lasts; the best gets a full look.");
        return b.toString().stripTrailing();
    }

    /** The result as the protocol answers it. */
    public static ObjectNode json(Result r) {
        ObjectNode o = M.createObjectNode();
        o.put("key", r.key()).put("pool_key", r.poolKey()).put("pool", r.pool()).put("reached", r.reached()).put("not_sifted", r.notSifted()).put("unchecked_on_sheet", r.unchecked());
        o.putObject("thumbnails").put("judged", r.thumbsJudged()).put("new", r.thumbsNew()).put("kept", r.thumbsKept()).put("seconds_each", r.thumbSeconds()).put("mode", r.thumbMode());
        o.putObject("first_sheets").put("judged", r.sheetsJudged()).put("new", r.sheetsNew()).put("kept", r.sheetsKept()).put("none", r.noSheet()).put("seconds_each", r.sheetSeconds());
        ArrayNode notes = o.putArray("notes"); for (String n : r.notes()) notes.add(n);
        ArrayNode a = o.putArray("survivors"); for (Sifted c : r.survivors()) a.add(c.json());
        o.put("summary", r.text());
        return o;
    }

    /** The ids of the survivors best first, for whoever looks next. */
    public static List<String> ids(Result r) { List<String> out = new ArrayList<>(); for (Sifted c : r.survivors()) out.add(c.hit.id); return out; }

}
