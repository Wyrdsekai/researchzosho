package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.tools.ImageText;
import org.researchzosho.tools.VideoDetailsTool;
import org.researchzosho.tools.VideoText;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finding something seen in a video a while ago. A video the library has read keeps three timed tracks, each a capture under the
 * video's own address, searched like any other text and answering with the moment and a link to it: what was SAID (the transcript),
 * the words SHOWN on screen (burned-in subtitles, labels, product names, the steps of a how-to), and what is SEEN (a line describing
 * each frame). The shown and seen tracks come from YouTube's own small frames of the video, its storyboards, one about every five
 * seconds and a few hundred kilobytes for the whole video, read and described by a model that reads pictures; a video without
 * storyboards has frames cut from a small copy instead. For the picture in a person's head, a description and the kinds of thing in
 * it, the library searches those lines, then has the model look again at the nearest moments at full size and say whether each is
 * the one. The captures of what is on screen are kept by the model that looked, so that two models can be compared. Without a model
 * that reads pictures it says so and answers from what was said.
 */
public final class VideoRecall {
    private VideoRecall() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** When a video has no storyboard: a frame every so many seconds, and the most frames cut for one video; the most videos one run looks through. */
    public static final int FRAME_EVERY_SECONDS = Config.getInt("RESEARCHZOSHO_FRAME_EVERY_SECONDS", 15);
    public static final int FRAMES_PER_VIDEO = Config.getInt("RESEARCHZOSHO_FRAMES_PER_VIDEO", 80);
    public static final int VIDEOS_PER_RUN = Config.getInt("RESEARCHZOSHO_FRAME_VIDEOS_PER_RUN", 12);
    public static final int JUDGED = 10;
    /** The shots between YouTube's small frames: the most cut for one video at its changes of scene, and how much a frame must differ from the last to count as a change (ffmpeg's scene score, 0 to 1). On unless the setting says off. */
    public static final int SCENES_PER_VIDEO = Config.getInt("RESEARCHZOSHO_SCENES_PER_VIDEO", 40);
    public static final String SCENE_CHANGE = Config.get("RESEARCHZOSHO_SCENE_CHANGE", "0.35");
    static boolean scenesOn() { return !Config.get("RESEARCHZOSHO_VIDEO_SCENES", "on").equalsIgnoreCase("off"); }
    public static final String JOB_KIND = "video-recall";
    /** A video's address, and after it the mark of a capture of what is on screen: the words shown, what is seen, or the shots between the frames, by the model that looked; "frames" is the older seen capture, before the model's name was kept. */
    static final Pattern WATCH = Pattern.compile("https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})(?:&(frames|shown|seen|scenes)(?:=([A-Za-z0-9._-]+))?)?");
    static final Pattern STAMP = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\]");
    /** The smallest picture track of a video: YouTube serves video and audio apart, so "worst" alone, which wants both in one file, finds nothing. */
    static final String SMALL = "wv*[height<=360][ext=mp4]/wv*[height<=360]/wv*/worst";
    static final String DESCRIBE = "What is on screen in this frame of a video? One line, plain, in this order: what kind of picture it is (a person on camera, a photograph, a drawing or animation, a diagram, "
            + "text on screen; in colour or black and white); the people (what they wear and do, never a name); the things; the place; and any words shown, exactly as written. Only what is visible.";

    /** A moment found: the video, the second, the line kept for it, whether it was said, shown or seen, and the search's score. */
    public record Hit(String videoId, String title, int seconds, String line, String kind, float score) {
        public String link() { return VideoText.moment(videoId, seconds); }
        public String clock() { return VideoText.clock(seconds); }
    }

    /** A video the library has read: its id, title, the day it was read, whether what was said is kept, and the models whose reading of the words shown, of what is seen, and of the shots between the frames is kept. */
    public record Held(String videoId, String title, String readOn, boolean said, Set<String> shownBy, Set<String> seenBy, Set<String> scenesBy) {
        public boolean shown() { return !shownBy.isEmpty(); }
        public boolean seen() { return !seenBy.isEmpty(); }
    }

    /** A model that reads pictures: a picture and a question in, its answer out; "" when it cannot. A test puts a stand-in here. */
    public interface Vision { String describe(byte[] image, String ask); }
    static volatile Vision VISION = VideoRecall::liveVision;
    /** What a storyboard reading returns when the model that reads pictures did not answer twice in a row: nothing kept, read again later. */
    static final int GAVE_UP = -2;
    /** Whether the last picture call on this thread went unanswered: no answer in time, or an error from the server. Tests stand in for it. */
    static volatile BooleanSupplier UNANSWERED = () -> DriveClient.lastClassifyUnanswered() != null || DriveClient.lastClassifyProblem().startsWith("the server");
    /** The embedder the frame lines are ranked by, the library's; a test puts the absent one here and the ranking goes by words. */
    static volatile Supplier<Embeddings.Embedder> EMBEDDER = Embeddings::configured;
    /** ffmpeg, which cuts frames out of a video file and opens a storyboard sheet; a test puts a stand-in here. */
    static volatile Video.Runner FFMPEG = VideoRecall::ffmpeg;
    /** Where the storyboards, the frames and the fetched copies go: the helper's folder; a test puts its own here. */
    static volatile Path workDir = null;
    static Path work() { return workDir != null ? workDir : Video.dir(); }

    /** The model that reads pictures, by the name it is configured under; and that name as it is kept in a capture's address. */
    public static String modelName() { DriveClient d = ModelEntries.seeingDrive(); return d != null ? d.model() : Config.get("RESEARCHZOSHO_VISION_MODEL", Config.get("RESEARCHZOSHO_MODEL", "local-model")); }
    static String modelKey() { String k = modelName().replaceAll("[^A-Za-z0-9._-]", "-"); return k.isEmpty() ? "model" : k; }

    // ── what was said, shown and seen: a search over the person's own viewing ─────────────────────────────────

    /** The moments in the videos the library holds that match the words, by words and by meaning, said, shown or seen, nearest first. */
    public static List<Hit> saw(LibraryStore store, String query, int k) throws IOException {
        if (query == null || query.isBlank()) return List.of();
        List<LibrarianIndex.Hit> found = new LibrarianIndex(store, Embeddings.configured()).search(query, Math.max(8, k * 5), null, null);
        List<Hit> out = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (LibrarianIndex.Hit h : found) {
            String locator = locatorOf(h.title());
            Matcher m = WATCH.matcher(locator);
            if (!m.matches()) continue;
            String id = m.group(1), kind = kindOf(m.group(2));
            String title = plainTitle(titleOf(h.title()));
            String text = rawText(store, h.id());   // the whole capture, not the passage the index matched: the moment is chosen among every line
            if (text.isBlank()) text = h.snippet() == null ? "" : h.snippet();
            int[] best = bestLine(text, query);
            if (best == null) continue;
            String key = id + "#" + kind + "#" + best[0];
            if (!seenKeys.add(key)) continue;
            out.add(new Hit(id, title, best[0], lineAt(text, best[1]), kind, h.score()));
            if (out.size() >= k) break;
        }
        return out;
    }

    /** The track a capture's mark names: none is the transcript, "frames" the older seen capture. */
    static String kindOf(String mark) { return mark == null ? "said" : mark.equals("frames") ? "seen" : mark.equals("scenes") ? "shot" : mark; }

    static String plainTitle(String title) { return title.replaceFirst(" \\((what is on screen|the words on screen|the shots between the frames)\\)$", ""); }

    static String label(String kind) { return kind.equals("seen") || kind.equals("shot") ? "on screen: " : kind.equals("shown") ? "words on screen: " : "said: "; }

    /** A hit's line with its track, as the page says it in {@code c}'s language: what was said, what is on screen, the words on screen. */
    static String labelled(String kind, String line, Chrome c) {
        return kind.equals("seen") || kind.equals("shot") ? c.t("on screen: {0}", line) : kind.equals("shown") ? c.t("words on screen: {0}", line) : c.t("said: {0}", line);
    }

    /** The hits as a person reads them: the moment, the video, what was said, shown or seen there, and the link to that moment. */
    public static String sawText(List<Hit> hits, String query) {
        if (hits.isEmpty()) return "Nothing in the videos the library has read matches \"" + query + "\". It searches what was said in them, and the words shown and what is seen on screen once the picture has been looked for.";
        StringBuilder b = new StringBuilder(hits.size() == 1 ? "One moment matches:\n" : hits.size() + " moments match, nearest first:\n");
        for (Hit h : hits) b.append("  [").append(h.clock()).append("] ").append(h.title()).append(" — ").append(label(h.kind())).append(h.line()).append("\n    ").append(h.link()).append("\n");
        return b.toString();
    }

    /** The videos the library has read, newest first: a capture under the video's own address made by the video field, with the captures of what is on screen that hang off it. */
    public static List<Held> held(LibraryStore store) throws IOException {
        Path raw = store.root().resolve("raw");
        if (!Files.isDirectory(raw)) return List.of();
        Map<String, Held> byId = new LinkedHashMap<>();
        Map<String, Set<String>> shownBy = new HashMap<>(), seenBy = new HashMap<>(), scenesBy = new HashMap<>();
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(raw)) { for (Path p : s.toList()) if (p.getFileName().toString().endsWith(".md")) files.add(p); }
        files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        for (Path p : files) {
            String head;
            try { head = headOf(p); } catch (IOException e) { continue; }
            if (!head.contains("fetched_by: researchzosho-video")) continue;
            Matcher m = WATCH.matcher(head);
            if (!m.find()) continue;
            String id = m.group(1);
            if (m.group(2) != null) {
                String model = m.group(3) == null ? "" : m.group(3);
                (m.group(2).equals("shown") ? shownBy : m.group(2).equals("scenes") ? scenesBy : seenBy).computeIfAbsent(id, x -> new TreeSet<>()).add(model);
                continue;
            }
            if (byId.containsKey(id)) continue;
            String title = after(head, "title: "), day = after(head, "fetched_at: ");
            boolean said;
            try { said = Files.readString(p, StandardCharsets.UTF_8).contains("transcript ("); } catch (IOException e) { said = false; }
            byId.put(id, new Held(id, title, day.length() >= 10 ? day.substring(0, 10) : day, said, Set.of(), Set.of(), Set.of()));
        }
        List<Held> out = new ArrayList<>();
        for (Held h : byId.values()) out.add(new Held(h.videoId(), h.title(), h.readOn(), h.said(), shownBy.getOrDefault(h.videoId(), Set.of()), seenBy.getOrDefault(h.videoId(), Set.of()), scenesBy.getOrDefault(h.videoId(), Set.of())));
        return out;
    }

    // ── the picture in your head ─────────────────────────────────────────────────────────────────────────────

    /** Files the search for the picture as one of the library's runs. A sentence for the person. */
    public static String file(LibraryStore store, String description, List<String> kinds, String patronDid) throws IOException {
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Say what you remember seeing: the things, the people, the place, any words on screen.");
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
        for (ObjectNode j : jobs.active()) if (j.path("kind").asText().equals(JOB_KIND))
            return "A search for a picture is already " + (j.path("state").asText().equals("running") ? "running" : "waiting to start") + " (" + j.path("job_id").asText() + "); the runs page shows how far it has come.";
        ObjectNode args = M.createObjectNode().put("description", description.strip());
        ArrayNode ks = args.putArray("kinds"); for (String k : kinds) if (k != null && !k.isBlank()) ks.add(k.strip().toLowerCase(Locale.ROOT));
        String id = jobs.submit(JOB_KIND, patronDid, args);
        return "The library looks for it in the videos it has read, newest first, up to " + VIDEOS_PER_RUN + " of them: it reads YouTube's own small frames of each video, one about every five seconds, has the model read the words "
                + "on screen and say what is seen in each, keeps those lines beside what was said, searches them for what you remember, and has the model look at the nearest moments at full size. It is filed as " + id
                + "; the runs page shows how far it has come, and the Video page shows the answer."
                + (Share.serviceRunning.getAsBoolean() ? "" : " The library's service is not running, so it starts when the service does: researchzosho service start.");
    }

    /** The job as the library's runs run it. */
    public static String runJob(LibraryStore store, ObjectNode job, Consumer<ObjectNode> progress, BooleanSupplier stop) throws IOException {
        JsonNode a = job.path("args");
        List<String> kinds = new ArrayList<>(); for (JsonNode k : a.path("kinds")) kinds.add(k.asText(""));
        return picture(store, a.path("description").asText(""), kinds, sentence -> progress.accept(M.createObjectNode().put("phase", sentence).put("at", Instant.now().toString())), stop);
    }

    /** What one search cost: the videos looked at for the first time, the small frames read and described, the frames judged at full size, the copies fetched, and the time. */
    static final class Tally {
        int videos, read, described, shots, judged, fetched;
        final long start = System.currentTimeMillis();
        String sentence() {
            long s = (System.currentTimeMillis() - start) / 1000;
            return "Looked with " + modelName() + ": " + (videos == 0 ? "no video new to it" : videos + (videos == 1 ? " video" : " videos") + " new to it, " + read + " small frames read for their words and " + described + " described")
                    + (shots > 0 ? ", " + shots + (shots == 1 ? " shot" : " shots") + " between the frames described" : "")
                    + "; " + judged + (judged == 1 ? " moment" : " moments") + " looked at again at full size" + (fetched > 0 ? "; " + fetched + (fetched == 1 ? " small copy" : " small copies") + " of a video fetched" : "")
                    + "; " + (s < 120 ? s + " seconds" : (s / 60) + " minutes") + ".";
        }
    }

    /**
     * The search itself: the videos held, newest first, each read once by the model that reads pictures (the words shown and what is
     * seen, from YouTube's own small frames) and kept beside the transcript; the description searched against those lines; the
     * nearest moments looked at again at full size and judged by the model against the description. The answer names the video, the
     * second and why; what it found is kept with the day and the model.
     */
    public static String picture(LibraryStore store, String description, List<String> kinds, CareerFind.Progress progress, BooleanSupplier stop) throws IOException {
        String what = description == null ? "" : description.strip();
        if (what.isEmpty()) return "Say what you remember seeing.";
        List<Held> videos = held(store);
        if (videos.isEmpty()) return "The library has read no video yet, so there is nothing to look through. A research run that reads videos, or researchzosho youtube video <address> --transcript, keeps one.";
        String answer;
        if (!visionAnswers()) {
            List<Hit> said = saw(store, what, 8);
            answer = "No model that reads pictures answers (set RESEARCHZOSHO_VISION_DRIVE and RESEARCHZOSHO_VISION_MODEL to one, or run the library's own model with its picture file), so the library looked through what was said instead.\n" + sawText(said, what);
            keep(store, what, kinds, answer);
            return answer;
        }
        Tally tally = new Tally();
        String model = modelKey();
        Map<String, Path> fetched = new LinkedHashMap<>();
        List<String> why = new ArrayList<>();
        Hit found = null, maybe = null;
        List<Hit> near;
        try {
            int n = 0;
            for (Held v : videos) {
                if (stop.getAsBoolean() || n >= VIDEOS_PER_RUN) break;
                n++;
                if (!v.seenBy().contains(model) && !v.shownBy().contains(model)) {   // this model has not read it
                    progress.at("Reading the frames of " + v.title() + ", " + n + " of " + Math.min(videos.size(), VIDEOS_PER_RUN));
                    int lines = readStoryboard(store, v, kinds, stop, tally);
                    if (lines < 0) lines = describeFrames(store, v, kinds, stop, tally);   // no storyboard: frames cut from a small copy, at each change of scene among them
                    if (lines > 0) tally.videos++;
                }
                if (scenesOn() && !v.scenesBy().contains(model) && !stop.getAsBoolean() && Storyboards.kept(v.videoId(), work()) != null) {   // the shots between the small frames, once per model
                    progress.at("Reading the shots between the frames of " + v.title());
                    readScenes(store, v, kinds, stop, tally, fetched);
                }
            }
            progress.at("Searching what is on screen for what you remember");
            near = nearest(store, what, JUDGED * 2);
            for (Hit h : near) {
                if (stop.getAsBoolean() || tally.judged >= JUDGED) break;
                progress.at("Looking again at " + h.clock() + " of " + h.title());
                byte[] frame = frameAt(h.videoId(), h.seconds(), h.kind().equals("shot"));
                if (frame == null) frame = cutFrame(h.videoId(), h.seconds(), fetched, tally);
                boolean small = false;
                if (frame == null) { frame = tileAt(h.videoId(), h.seconds()); small = frame != null; }   // the video could not be fetched: the small frame itself
                if (frame == null) continue;
                tally.judged++;
                String verdict = VISION.describe(frame, judgeAsk(what, kinds, small));
                String v = verdict == null ? "" : verdict.strip();
                String word = verdictOf(v);
                if (word.equals("found")) { found = h; why.add(v.replace("\n", " ")); break; }
                if (word.equals("maybe") && maybe == null) maybe = h;
                v = v.replace("\n", " ");
                why.add(h.clock() + " of " + h.title() + ": " + (v.isEmpty() ? "the model gave no answer" : v));
            }
        } finally { for (Path p : fetched.values()) { try { Files.deleteIfExists(p); } catch (IOException ignored) { } } }
        if (found != null) answer = "Found it: " + found.title() + ", at " + found.clock() + ". The model says: " + why.get(why.size() - 1) + "\n  " + found.link() + "\n  On screen there, " + (found.kind().equals("shown") ? "the words: " : "as described: ") + found.line();
        else if (near.isEmpty()) answer = "Nothing on screen in the videos the library has read matches \"" + what + "\"" + (tally.videos == 0 && videos.stream().noneMatch(Held::seen) ? "; no frames could be read this time" : "") + ". What was said:\n" + sawText(saw(store, what, 5), what);
        else if (maybe != null) {
            StringBuilder b = new StringBuilder("Maybe: " + maybe.title() + ", at " + maybe.clock() + " shows such a picture, and whether it is the one is yours to say; who a person is cannot be told from a face.\n  " + maybe.link() + "\n  On screen there: " + maybe.line() + "\nThe other moments the model looked at:\n");
            for (String w : why) b.append("  ").append(w).append("\n");
            answer = b.toString();
        } else {
            StringBuilder b = new StringBuilder("Nothing the model looked at shows it. The nearest moments, and what the model said of each:\n");
            for (String w : why) b.append("  ").append(w).append("\n");
            for (Hit h : near.subList(0, Math.min(near.size(), JUDGED))) b.append("  ").append(h.link()).append("\n");
            answer = b.toString();
        }
        answer = answer.stripTrailing() + "\n" + tally.sentence();
        keep(store, what, kinds, answer);
        return answer;
    }

    // ── the look at one video ────────────────────────────────────────────────────────────────────────

    /** One thing the person remembers, judged from the video: found, maybe or no, the second it was seen at (0 when the whole video answers), and why. */
    public record Answer(String feature, String verdict, int seconds, String why) {
        public String clock() { return VideoText.clock(seconds); }
    }

    /** The look at one video: the video, an answer per thing remembered, and what the reading cost in words. */
    public record Look(String videoId, String title, List<Answer> answers, String read) {
        public String link() { return address(videoId); }
        /** The look as a person reads it: the video, each thing with its verdict, the second and why, then the cost line. */
        public String text() {
            StringBuilder b = new StringBuilder("Looked at " + title + " (" + link() + "):\n");
            for (Answer a : answers) {
                b.append("  ").append(a.feature()).append(": ").append(a.verdict().isEmpty() ? "not looked at" : a.verdict()).append(a.seconds() > 0 ? " at " + a.clock() : "").append(" — ").append(a.why()).append('\n');
                if (a.seconds() > 0) b.append("    ").append(VideoText.moment(videoId, a.seconds())).append('\n');
            }
            return b.append(read).append('\n').toString();
        }
    }

    /** The library's model asked one thing in text, answering in text; "" when it cannot. A test puts a stand-in here. */
    public interface Words { String answer(String ask); }
    static volatile Words WORDS = VideoRecall::liveWords;

    /** The first minute of a video, read whole even in a sparse look; the most moments judged for one thing remembered. */
    static final int FIRST_MINUTE = 60, LOOK_JUDGED = 3;
    static final Pattern SILENCE = Pattern.compile("(?i)\\b(silent|silence|no (talking|talk|voice|voice-?over|narration|speech|speaking|commentary|spoken words)|without (talking|voice|voice-?over|narration|speech|words)|(nobody|no ?one|no person|no people) (speaks|talks|narrates|speaking|talking|narrating|is talking|is speaking|says anything)|no spoken narration|music only|only music|not narrated|unnarrated)\\b");
    /** A thing the person says is NOT in the video: "no snap closure", "without a zipper", "it does not have a lid". The thing itself is looked for and the verdict turned round. */
    static final Pattern ABSENT = Pattern.compile("(?i)^(?:there (?:is|are|was|were) )?(?:no|not any|without|(?:it |the bag |the video )?(?:does not|doesn't|did not|didn't|has no|have no)(?: have| show| use)?)\\s+(?:a |an |any |the )?(.+?)\\.?$");
    static final Pattern AT_START = Pattern.compile("(?i)\\b((at|from|near|right at) the (very )?(beginning|start|opening|outset)|at first|in the (first|opening) (minute|seconds|moments|frames?|shots?|scenes?)|opening (shot|frame|scene|screen|title)|early on|to begin with|first thing)\\b|冒頭|最初に|始めに");

    /**
     * The look at one video for what a person remembers of it, on the shelves or not: the video's page and transcript are read and
     * kept when the library does not hold it; its frames are read into the three tracks by the model that reads pictures, whole or
     * sparse (the first minute whole, then every fourth frame), and kept under the video's address as a shelf video's are, so the
     * recall finds them afterwards and a second look reads nothing again; then each thing remembered is judged: silence from what was
     * said, the rest from the nearest moments on screen looked at by the model, found, maybe or no, with the second and why.
     */
    public static Look lookAt(LibraryStore store, String video, String what, boolean sparse, CareerFind.Progress progress, BooleanSupplier stop) throws IOException {
        String id = VideoText.videoId(video == null ? "" : video);
        if (id == null) throw new IllegalArgumentException("Give the video's address (https://www.youtube.com/watch?v=…) or its 11-character id.");
        String ask = what == null ? "" : what.strip();
        if (ask.isEmpty()) throw new IllegalArgumentException("Say what you remember of the video, one thing after another.");
        Held v = heldOne(store, id);
        if (v == null) {
            progress.at("Reading the video's page and transcript");
            v = VideoDetailsTool.bring(store, id);
            if (v == null) throw new IOException("The video could not be read" + (Video.refusal().isEmpty() ? "" : " (" + Video.refusal() + ")") + ".");
        }
        List<String> features = features(ask);
        Tally tally = new Tally();
        String model = modelKey(), sparseKey = model + "-sparse";
        boolean fullRead = v.seenBy().contains(model) || v.shownBy().contains(model);
        boolean sparseRead = v.seenBy().contains(sparseKey) || v.shownBy().contains(sparseKey);
        boolean vision = visionAnswers();
        Map<String, Path> fetched = new LinkedHashMap<>();
        String read;
        boolean picturesDown = false;
        List<Answer> answers = new ArrayList<>();
        try {
            if (!vision) read = "No model that reads pictures answers (set RESEARCHZOSHO_VISION_DRIVE and RESEARCHZOSHO_VISION_MODEL to one, or run the library's own model with its picture file), so only what was said was looked at.";
            else if (fullRead || (sparse && sparseRead)) read = "Already read by " + modelName() + (fullRead ? "" : ", every fourth frame after the first minute") + "; answered from what it read.";
            else {
                progress.at("Reading the frames of " + v.title() + (sparse ? ", the first minute whole and then every fourth" : ""));
                String key = sparse ? sparseKey : model;
                int lines = readStoryboard(store, v, List.of(), stop, tally, sparse ? 4 : 1, key);
                if (lines == GAVE_UP) {
                    picturesDown = true;
                    read = "The model that reads pictures (" + modelName() + ") did not answer twice in a row, so the frames of " + v.title() + " were not read now and nothing was kept; the look can be asked for again later.";
                } else {
                    if (lines < 0) lines = describeFrames(store, v, List.of(), stop, tally, sparse ? 4 : 1, key);
                    if (lines > 0) tally.videos++;
                    if (!sparse && scenesOn() && !stop.getAsBoolean() && Storyboards.kept(id, work()) != null) { progress.at("Reading the shots between the frames of " + v.title()); readScenes(store, v, List.of(), stop, tally, fetched); }
                    long s = (System.currentTimeMillis() - tally.start) / 1000;
                    read = "Read " + tally.read + " small frames" + (tally.shots > 0 ? " and " + tally.shots + " shots between them" : "") + " in " + (s < 120 ? s + " seconds" : (s / 60) + " minutes") + " with " + modelName() + (sparse ? ", the first minute whole and then every fourth frame" : "") + ".";
                }
            }
            List<FrameLine> lines = frameLinesOf(store, id);
            String said = saidText(store, id);
            for (String f : features) {
                if (stop.getAsBoolean()) break;
                if (SILENCE.matcher(f).find()) answers.add(judgeSilence(f, said));
                else if (!vision) answers.add(new Answer(f, "", 0, "no model that reads pictures answers"));
                else if (picturesDown) answers.add(new Answer(f, "", 0, "the model that reads pictures did not answer, so this was not looked at"));
                else answers.add(judgeAbsence(f, id, lines, fetched, tally, progress, sparse, stop));
            }
        } finally { for (Path p : fetched.values()) { try { Files.deleteIfExists(p); } catch (IOException ignored) { } } }
        if (tally.judged > 0) read = read + " " + tally.judged + (tally.judged == 1 ? " moment" : " moments") + " looked at for what you remember.";
        return new Look(id, v.title(), answers, read);
    }

    /** The video the library holds under an id, with the models that read it; null when it is not on the shelves. */
    static Held heldOne(LibraryStore store, String videoId) throws IOException {
        for (Held h : held(store)) if (h.videoId().equals(videoId)) return h;
        return null;
    }

    /** What the person remembers, one thing per line: the model splits it; without the model, the clauses between semicolons, else commas, else "and". */
    static List<String> features(String what) {
        List<String> out = new ArrayList<>();
        String answer;
        try { answer = WORDS.answer("A person remembers these things about a video: \"" + what + "\". Write each separate thing they remember on a line of its own, copying their own words for it exactly as written; no numbering, no rewording, no other words."); }
        catch (RuntimeException e) { answer = ""; }
        if (answer != null) for (String l : answer.split("\n")) { String f = l.strip().replaceFirst("^[-*•\\d.)\\s]+", "").strip(); if (f.length() >= 3 && !out.contains(f)) out.add(f); }
        if (out.size() <= 1) {
            out.clear();
            for (String sep : new String[]{"\\s*;\\s*", "\\s*,\\s*", "\\s+and\\s+"}) {
                for (String f : what.split(sep)) { f = f.strip(); if (f.length() >= 3 && !out.contains(f)) out.add(f); }
                if (out.size() > 1) break;
                out.clear();
            }
        }
        if (out.isEmpty()) out.add(what);
        return out.size() > 12 ? new ArrayList<>(out.subList(0, 12)) : out;
    }

    /** The lines of what is on screen in one video, this model's reading first. */
    static List<FrameLine> frameLinesOf(LibraryStore store, String videoId) throws IOException {
        List<FrameLine> out = new ArrayList<>();
        for (FrameLine l : frameLines(store)) if (l.videoId().equals(videoId)) out.add(l);
        return out;
    }

    /** The text the library keeps under a video's own address: its details and what was said; "" when none. */
    static String saidText(LibraryStore store, String videoId) throws IOException {
        Path raw = store.root().resolve("raw");
        if (!Files.isDirectory(raw)) return "";
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(raw)) { for (Path p : s.toList()) if (p.getFileName().toString().endsWith(".md")) files.add(p); }
        files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        for (Path p : files) {
            String head;
            try { head = headOf(p); } catch (IOException e) { continue; }
            if (!head.contains("fetched_by: researchzosho-video")) continue;
            Matcher m = WATCH.matcher(head);
            if (m.find() && m.group(1).equals(videoId) && m.group(2) == null) return rawText(store, p.getFileName().toString());
        }
        return "";
    }

    /** The video's length in seconds from its details line ("length: 15:53"), else 0. */
    static int lengthOf(String said) {
        Matcher m = Pattern.compile("(?m)^length: (\\d+):(\\d{2})(?::(\\d{2}))?$").matcher(said);
        if (!m.find()) return 0;
        int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
        return m.group(3) != null ? a * 3600 + b * 60 + Integer.parseInt(m.group(3)) : a * 60 + b;
    }

    /**
     * Whether nobody talks, from what was said: the transcript's words that are speech (music and applause marks left out) against the
     * video's length. Fewer than twenty-five words a minute is music or a stray word, not talk; no transcript at all is silence unless
     * the details say captions exist that could not be read, which leaves it a maybe.
     */
    static Answer judgeSilence(String feature, String said) {
        int at = said.indexOf("\ntranscript (");
        String tr = at < 0 ? "" : said.substring(at);
        int words = 0, lines = 0, lastSecond = 0, firstSecond = 0; String sample = "";
        for (String line : tr.split("\n")) {
            Matcher m = STAMP.matcher(line);
            if (!m.find()) continue;
            String rest = m.replaceFirst("").replaceAll("\\[[^\\]]*\\]|♪|\\((music|applause|laughter)\\)", " ").strip();
            int n = rest.isEmpty() ? 0 : rest.split("\\s+").length;
            if (n == 0) continue;
            if (lines == 0) { sample = rest; firstSecond = seconds(m); }
            lines++; words += n; lastSecond = seconds(m);
        }
        int length = lengthOf(said);
        if (length <= 0) length = lastSecond;
        if (lines == 0) {
            boolean captions = said.contains("captions exist in") || said.contains("captions: by the uploader") || said.contains("; automatic in");
            return new Answer(feature, captions ? "maybe" : "found", 0, captions ? "no transcript could be read, though the video has captions, so whether anyone talks is not settled" : "no captions and no speech: nothing is said in it, as far as the library can tell");
        }
        double perMinute = length > 0 ? words * 60.0 / length : words;
        String bit = sample.length() > 60 ? sample.substring(0, 60) + "…" : sample;
        if (perMinute < 25) return new Answer(feature, "found", 0, "the captions hold " + words + (words == 1 ? " word" : " words") + " over " + VideoText.clock(length) + ", music or a stray word rather than talk (\"" + bit + "\")");
        return new Answer(feature, "no", firstSecond, "someone talks: " + lines + " lines of speech, about " + Math.round(perMinute) + " words a minute, from \"" + bit + "\"");
    }

    /**
     * One thing remembered, judged on screen: the nearest moments by the words of the lines read (the first minute and a half only
     * when the person says it was at the start; the first frames themselves when no line names it), each looked at by the model with
     * the person's own question, found at the first found, else maybe, else no with what the model saw instead.
     */
    static Answer judgeFeature(String videoId, String feature, List<FrameLine> lines, Map<String, Path> fetched, Tally tally, CareerFind.Progress progress, boolean sparse, BooleanSupplier stop) {
        boolean start = AT_START.matcher(feature).find();
        List<FrameLine> pool = new ArrayList<>();
        for (FrameLine l : lines) if (!start || l.seconds() <= 90) pool.add(l);
        List<Hit> near = byWords(pool, feature, LOOK_JUDGED);
        if (near.isEmpty() && start) {
            Set<Integer> secs = new TreeSet<>(); for (FrameLine l : pool) secs.add(l.seconds());
            for (int s : secs) { if (near.size() >= LOOK_JUDGED) break; near.add(new Hit(videoId, "", s, "", "seen", 0)); }
        }
        if (near.isEmpty()) return new Answer(feature, "no", 0, "nothing on screen in the " + lines.size() + " lines read mentions it");
        Hit maybe = null; String maybeWhy = ""; List<String> saw = new ArrayList<>();
        for (Hit h : near) {
            if (stop.getAsBoolean()) break;
            progress.at("Looking at " + h.clock() + " for: " + feature);
            byte[] frame = frameAt(videoId, h.seconds(), h.kind().equals("shot"));
            if (frame == null && !sparse) frame = cutFrame(videoId, h.seconds(), fetched, tally);
            boolean small = false;
            if (frame == null) { frame = tileAt(videoId, h.seconds()); small = frame != null; }
            if (frame == null) continue;
            tally.judged++;
            String raw = VISION.describe(frame, judgeAsk(feature, List.of(), small));
            String word = verdictOf(raw == null ? "" : raw);
            String v = withoutVerdict(raw == null ? "" : raw) + (h.line().isEmpty() ? "" : " On screen there: " + h.line());
            if (word.equals("found")) return new Answer(feature, "found", h.seconds(), v);
            if (word.equals("maybe") && maybe == null) { maybe = h; maybeWhy = v; }
            saw.add(h.clock() + ": " + (raw == null || raw.isBlank() ? "the model gave no answer" : v));
        }
        if (maybe != null) return new Answer(feature, "maybe", maybe.seconds(), maybeWhy);
        return new Answer(feature, "no", near.get(0).seconds(), "the nearest moments do not show it: " + String.join("; ", saw));
    }

    /** A thing said to be absent is looked for as itself: seen, the absence is a no at that second; not seen anywhere, the absence is found. Anything else is judged as it is. */
    static Answer judgeAbsence(String feature, String videoId, List<FrameLine> lines, Map<String, Path> fetched, Tally tally, CareerFind.Progress progress, boolean sparse, BooleanSupplier stop) {
        Matcher m = ABSENT.matcher(feature.strip());
        if (!m.matches() || m.group(1).isBlank()) return judgeFeature(videoId, feature, lines, fetched, tally, progress, sparse, stop);
        String thing = m.group(1).strip();
        Answer a = judgeFeature(videoId, thing, lines, fetched, tally, progress, sparse, stop);
        if (a.verdict().equals("found")) return new Answer(feature, "no", a.seconds(), "the video shows " + thing + ": " + a.why());
        if (a.verdict().equals("maybe")) return new Answer(feature, "maybe", a.seconds(), "something like " + thing + " may be shown: " + a.why());
        return new Answer(feature, "found", 0, "nothing on screen shows " + thing + (a.why().startsWith("nothing on screen") ? " in the " + lines.size() + " lines read" : "; the nearest moments looked at show none"));
    }

    /** The model's answer without the line that is only its word, found, maybe or no, flattened to one line. */
    static String withoutVerdict(String answer) {
        List<String> keep = new ArrayList<>();
        for (String l : answer.strip().split("\n")) { String w = l.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""); if (!(w.equals("found") || w.equals("maybe") || w.equals("no") || w.equals("yes"))) keep.add(l.strip()); }
        return String.join(" ", keep).replaceAll("\\s+", " ").strip();
    }

    static String ordinal(int n) { return n == 2 ? "second" : n == 3 ? "third" : n == 4 ? "fourth" : n == 5 ? "fifth" : n + "th"; }

    /** The library's own model asked in text, for the features. */
    static String liveWords(String ask) {
        DriveClient drive = new DriveClient(Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), Config.get("RESEARCHZOSHO_MODEL", "local-model"));
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", ask);
        try { return drive.classify(msgs, 400); } catch (RuntimeException e) { return ""; }
    }

    /** The person's own question of a frame, for the model: what the frame shows, then one word, found, maybe or no, with the rule that a face names nobody. */
    static String judgeAsk(String what, List<String> kinds, boolean small) {
        return "A person remembers seeing this in a video: \"" + what + "\"" + kindsSentence(kinds) + ". Does this frame show it? First, one line on what the frame "
                + "shows: the kind of picture, the things, the people, the place, the words shown, only what is plainly there. Then, on a line of its own, one word: found when the frame shows what the "
                + "person remembers, maybe when it shows something like it, no when it does not; a thing that is not plainly in the frame is not there. Who a person is cannot be told from their face: "
                + "when the memory names a person, say maybe when the frame shows such a picture of someone, and found only when that same name is shown on screen; another name shown is no."
                + (small ? " This is a small preview frame, so small writing may not be readable." : "");
    }

    /** The judge's word, found, maybe or no: the first word of its last line, else of its first ("yes" counts as found); "" when neither says. */
    static String verdictOf(String answer) {
        String[] lines = answer.strip().split("\n");
        for (String line : new String[]{lines[lines.length - 1], lines[0]}) {
            String w = line.strip().toLowerCase(Locale.ROOT).replaceAll("^[^a-z]+", "");
            if (w.startsWith("found") || w.startsWith("yes")) return "found";
            if (w.startsWith("maybe")) return "maybe";
            if (w.startsWith("no")) return "no";
        }
        return "";
    }

    /** One line of what is on screen: the video, the second, the words, and whether they were shown (read off the screen) or seen (described). */
    record FrameLine(String videoId, String title, int seconds, String line, String kind) { }

    /**
     * Every line of what is on screen the library holds, from the captures of the words shown, of what is seen and of the shots between
     * the frames: by the model that reads pictures now for a video it has read, else by whichever model read it. Two models' lines of one
     * video say the same moments twice in other words and crowd the nearest moments out, so a video has one model's lines in the search.
     */
    static List<FrameLine> frameLines(LibraryStore store) throws IOException {
        List<FrameLine> out = new ArrayList<>();
        Path raw = store.root().resolve("raw");
        if (!Files.isDirectory(raw)) return out;
        record Capture(Path file, String id, String mark, String model, String title) { }
        List<Capture> captures = new ArrayList<>();
        Set<String> readByThis = new HashSet<>(), readByThisSparse = new HashSet<>();
        String mine = modelKey(), mineSparse = mine + "-sparse";
        try (var s = Files.list(raw)) {
            for (Path p : s.toList()) {
                if (!p.getFileName().toString().endsWith(".md")) continue;
                String head;
                try { head = headOf(p); } catch (IOException e) { continue; }
                Matcher m = WATCH.matcher(head);
                if (!m.find() || m.group(2) == null) continue;
                if (m.group(2).equals("scenes") && !scenesOn()) continue;
                String model = m.group(3) == null ? "" : m.group(3);
                if (!m.group(2).equals("scenes")) { if (model.equals(mine)) readByThis.add(m.group(1)); else if (model.equals(mineSparse)) readByThisSparse.add(m.group(1)); }
                captures.add(new Capture(p, m.group(1), m.group(2), model, plainTitle(after(head, "title: "))));
            }
        }
        for (Capture c : captures) {
            String want = readByThis.contains(c.id()) ? mine : readByThisSparse.contains(c.id()) ? mineSparse : null;   // this model's full reading, else its sparse one, else whichever model read it
            if (want != null && !c.model().equals(want) && !(c.mark().equals("scenes") && c.model().equals(mine))) continue;
            for (String line : rawText(store, c.file().getFileName().toString()).split("\n")) {
                Matcher st = STAMP.matcher(line);
                if (!st.find()) continue;
                out.add(new FrameLine(c.id(), c.title(), seconds(st), st.replaceFirst("").strip(), kindOf(c.mark())));
            }
        }
        return out;
    }

    /**
     * The moments nearest a description, among every line of what is on screen: by meaning when the embedder answers, the description
     * and each line as vectors and their cosine; else by words, each word counting for its rarity among the lines. A picture is
     * remembered in other words than the model wrote, "a bee" for "a giant insect", which words alone miss; words shown on screen are
     * remembered as written, which the words catch.
     */
    static List<Hit> nearest(LibraryStore store, String description, int k) throws IOException {
        List<FrameLine> lines = frameLines(store);
        if (lines.isEmpty()) return List.of();
        List<Hit> out = new ArrayList<>();
        Embeddings.Embedder embedder = EMBEDDER.get();
        float[] q = embedder == null ? null : embedder.embed(description);
        if (q != null) {
            List<String> texts = new ArrayList<>(); for (FrameLine l : lines) texts.add(l.line());
            List<float[]> vs = embedder.embedAll(texts);
            for (int i = 0; i < lines.size() && i < vs.size(); i++) {
                float[] v = vs.get(i);
                if (v == null) continue;
                FrameLine l = lines.get(i);
                out.add(new Hit(l.videoId(), l.title(), l.seconds(), l.line(), l.kind(), (float) cosine(q, v)));
            }
        }
        out.sort(Comparator.comparingDouble((Hit h) -> -h.score()));
        List<Hit> words = byWords(lines, description, k);   // the words' own nearest: "cube" or "photo" said plainly is a cue the vectors may miss
        List<Hit> top = new ArrayList<>();
        for (int i = 0; top.size() < k && (i < out.size() || i < words.size()); i++) {   // the two arms take turns, so the moments looked at again come from both
            if (i < out.size()) add(top, out.get(i));
            if (i < words.size() && top.size() < k) add(top, words.get(i));
        }
        return top;
    }

    /** A moment once: the words shown and what is seen at one second are two lines of the same moment. */
    static void add(List<Hit> top, Hit h) {
        if (top.stream().noneMatch(x -> x.videoId().equals(h.videoId()) && Math.abs(x.seconds() - h.seconds()) <= 1)) top.add(h);
    }

    /** The lines nearest a description by words alone, each word weighted by its rarity among the lines. */
    static List<Hit> byWords(List<FrameLine> lines, String description, int k) {
        List<Hit> out = new ArrayList<>();
        List<String> words = new ArrayList<>();
        for (String w : description.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3 && !words.contains(w)) words.add(w);
        List<Set<String>> stems = new ArrayList<>();
        for (FrameLine l : lines) { Set<String> s = new HashSet<>(); for (String w : l.line().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3) s.add(stem(w)); stems.add(s); }
        Map<String, Integer> df = new LinkedHashMap<>();
        for (String w : words) { String st = stem(w); int n = 0; for (Set<String> s : stems) if (s.contains(st)) n++; df.put(w, n); }
        for (int i = 0; i < lines.size(); i++) {
            double score = 0;
            for (String w : words) { int n = df.get(w); if (n > 0 && stems.get(i).contains(stem(w))) score += Math.log(1.0 + (double) lines.size() / n); }
            if (score > 0) { FrameLine l = lines.get(i); out.add(new Hit(l.videoId(), l.title(), l.seconds(), l.line(), l.kind(), (float) score)); }
        }
        out.sort(Comparator.comparingDouble((Hit h) -> -h.score()));
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    /** What was looked for and what was answered, kept with the day and the model, for the Video page. */
    static void keep(LibraryStore store, String description, List<String> kinds, String answer) {
        try {
            Path f = store.root().resolve("catalog").resolve("video-recall.jsonl");
            Files.createDirectories(f.getParent());
            ObjectNode o = M.createObjectNode().put("at", Instant.now().toString()).put("description", description).put("answer", answer).put("model", modelName());
            ArrayNode ks = o.putArray("kinds"); for (String k : kinds) ks.add(k);
            Files.writeString(f, o.toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    /** The searches for a picture so far, newest first. */
    public static List<ObjectNode> kept(LibraryStore store) {
        List<ObjectNode> out = new ArrayList<>();
        Path f = store.root().resolve("catalog").resolve("video-recall.jsonl");
        if (!Files.isRegularFile(f)) return out;
        try { for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!line.isBlank()) out.add((ObjectNode) M.readTree(line)); } catch (IOException ignored) { }
        Collections.reverse(out);
        return out;
    }

    // ── the two tracks of what is on screen ──────────────────────────────────────────────────────────────────

    /** The head of a capture of what is on screen. */
    static String head(Held v, String what) { return "title: " + v.title() + "\naddress: " + address(v.videoId()) + "\n\n" + what + "\n"; }

    static String address(String videoId) { return VideoText.moment(videoId, 0).replace("&t=0s", ""); }

    /** The two tracks kept as captures of their own under the video's address, by the model that looked. The number of lines kept. */
    static int keepTracks(LibraryStore store, Held v, StringBuilder shown, int nShown, StringBuilder seen, int nSeen) { return keepTracks(store, v, shown, nShown, seen, nSeen, modelKey()); }

    /** The two tracks kept under the key they are read by: the model's name, or the model's name and "-sparse" for a look that read every fourth frame. */
    static int keepTracks(LibraryStore store, Held v, StringBuilder shown, int nShown, StringBuilder seen, int nSeen, String key) {
        if (nShown > 0) RawCapture.capture(store, address(v.videoId()) + "&shown=" + key, shown.toString(), v.title() + " (the words on screen)", "researchzosho-video", "", "");
        if (nSeen > 0) RawCapture.capture(store, address(v.videoId()) + "&seen=" + key, seen.toString(), v.title() + " (what is on screen)", "researchzosho-video", "", "");
        return nShown + nSeen;
    }

    /** The writing read off a frame as one line of the shown track; "" when the frame has none (the reader answers PHOTO: and what it shows) or it could not be read. */
    static String writingOf(byte[] png) {
        String writing = ImageText.read(png, "", new StringBuilder());
        if (writing == null || writing.isBlank() || writing.startsWith("PHOTO:")) return "";
        String one = writing.strip().replaceAll("\\s*\\n+\\s*", " / ").replaceAll("\\s+", " ");
        return one.length() > 400 ? one.substring(0, 400) : one;
    }

    /**
     * A video's storyboard read: each small frame, skipping one that shows the same picture as the last, has its writing read by the
     * model (the shown track) and is described by it (the seen track), each line with the frame's second. The lines kept; -1 when the
     * video has no storyboard or it could not be had.
     */
    static int readStoryboard(LibraryStore store, Held v, List<String> kinds, BooleanSupplier stop, Tally tally) { return readStoryboard(store, v, kinds, stop, tally, 1, modelKey()); }

    /** The storyboard read with a stride: every {@code stride}th small frame after the first minute (the first minute whole), kept under {@code key}. */
    static int readStoryboard(LibraryStore store, Held v, List<String> kinds, BooleanSupplier stop, Tally tally, int stride, String key) {
        Storyboards.Sheets sheets = Storyboards.fetch(v.videoId(), work(), FFMPEG);
        if (sheets == null) return -1;
        List<Storyboards.Tile> tiles;
        try { tiles = Storyboards.tiles(sheets, FFMPEG); } catch (IOException e) { return -1; }
        if (tiles.isEmpty()) return -1;
        String gap = "one about every " + sheets.gap() + (sheets.gap() == 1 ? " second" : " seconds") + (stride > 1 ? ", every " + ordinal(stride) + " after the first minute" : "");
        StringBuilder shown = new StringBuilder(head(v, "the words on screen (read off YouTube's own small frames of the video, " + gap + ", by " + modelName() + "):"));
        StringBuilder seen = new StringBuilder(head(v, "what is on screen (YouTube's own small frames of the video, " + gap + ", described by " + modelName() + "):"));
        int nShown = 0, nSeen = 0, skipped = 0, i = -1, misses = 0;
        BufferedImage last = null;
        for (Storyboards.Tile t : tiles) {
            i++;
            if (stop.getAsBoolean()) break;
            if (stride > 1 && t.seconds() > FIRST_MINUTE && i % stride != 0) continue;   // a sparse look: the first minute whole, then every so many frames
            if (last != null && skipped < 5 && Storyboards.same(last, t.image())) { skipped++; continue; }   // a still, a slide that stayed: its lines are the last frame's
            skipped = 0; last = t.image();
            byte[] png;
            try { png = t.png(400); } catch (IOException e) { continue; }
            String clock = VideoText.clock(t.seconds());
            DriveClient.forgetClassify();
            String writing = writingOf(png);
            misses = UNANSWERED.getAsBoolean() ? misses + 1 : 0;
            if (misses >= 2) return GAVE_UP;   // the give-up rule: twice unanswered in a row, the rest is not waited for, and nothing half read is kept
            tally.read++;
            if (!writing.isEmpty()) { shown.append("[").append(clock).append("] ").append(writing).append("\n"); nShown++; }
            DriveClient.forgetClassify();
            String line = VISION.describe(png, DESCRIBE + kindsSentence(kinds));
            misses = UNANSWERED.getAsBoolean() ? misses + 1 : 0;
            if (misses >= 2) return GAVE_UP;
            tally.described++;
            if (line != null && !line.isBlank()) { seen.append("[").append(clock).append("] ").append(line.strip().replace("\n", " ")).append("\n"); nSeen++; }
        }
        return keepTracks(store, v, shown, nShown, seen, nSeen, key);
    }

    /**
     * The shots between the small frames: a shot of a second or two falls between YouTube's frames, so the changes of scene are cut
     * from a small copy of the video, and each whose picture the nearest small frame does not show is read for its writing and
     * described, the lines kept as a capture of their own under the video's address. Nothing is cut twice: the frames are kept by their
     * second, and a video once read this way by a model is marked under its frames. The lines kept; -1 when the video could not be had.
     */
    static int readScenes(LibraryStore store, Held v, List<String> kinds, BooleanSupplier stop, Tally tally, Map<String, Path> fetched) {
        Storyboards.Sheets sheets = Storyboards.kept(v.videoId(), work());
        if (sheets == null) return -1;
        List<Storyboards.Tile> tiles;
        try { tiles = Storyboards.tiles(sheets, FFMPEG); } catch (IOException e) { return -1; }
        List<Path> shots = sceneFrames(v.videoId(), fetched, tally);
        if (shots.isEmpty() && fetched.get(v.videoId()) == null) return -1;
        StringBuilder seen = new StringBuilder(head(v, "the shots between the frames (cut from a small copy of the video at each change of scene whose picture YouTube's own small frames do not show, described by " + modelName() + "):"));
        int n = 0, looked = 0;
        for (Path shot : shots) {
            if (stop.getAsBoolean()) return n;   // read again next time, from the frames kept
            byte[] img;
            try { img = Files.readAllBytes(shot); } catch (IOException e) { continue; }
            int sec = secondOf(shot);
            Storyboards.Tile near = nearestTile(tiles, sec);
            if (near != null && sameAsTile(img, near)) continue;   // the small frame shows this shot already: its lines are in the tracks
            looked++;
            String writing = writingOf(img);
            String line = VISION.describe(img, DESCRIBE + kindsSentence(kinds));
            tally.shots++;
            if (line == null || line.isBlank()) continue;
            seen.append("[").append(VideoText.clock(sec)).append("] ").append(line.strip().replace("\n", " ")).append(writing.isEmpty() ? "" : " Words on screen: " + writing).append("\n");
            n++;
        }
        RawCapture.capture(store, address(v.videoId()) + "&scenes=" + modelKey(), seen.append(n == 0 ? "(every change of scene is shown by the small frames: " + shots.size() + " looked at)\n" : "").toString(), v.title() + " (the shots between the frames)", "researchzosho-video", "", "");
        return n;
    }

    /** The small frame nearest a second. */
    static Storyboards.Tile nearestTile(List<Storyboards.Tile> tiles, int seconds) {
        Storyboards.Tile best = null;
        for (Storyboards.Tile t : tiles) if (best == null || Math.abs(t.seconds() - seconds) < Math.abs(best.seconds() - seconds)) best = t;
        return best;
    }

    /** Whether a frame cut from the video shows the picture of a small frame: the frame scaled to the small frame's size and the two compared patch by patch, loosely, one being a JPEG of the video and the other YouTube's own. */
    static boolean sameAsTile(byte[] frame, Storyboards.Tile tile) {
        try {
            BufferedImage in = ImageIO.read(new ByteArrayInputStream(frame));
            if (in == null) return false;
            BufferedImage small = new BufferedImage(tile.image().getWidth(), tile.image().getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(in, 0, 0, small.getWidth(), small.getHeight(), null);
            g.dispose();
            return Storyboards.same(small, tile.image(), 28);
        } catch (IOException e) { return false; }
    }

    /**
     * The frames at each change of scene, cut from a small copy of the video and kept with the other frames by their second, each as
     * a shot's own file; more changes than the cap are thinned evenly over the video. The seconds are
     * kept under the frames ({@code scenes.txt}), so the video is fetched for them once, whichever library or model asks. Empty when
     * the video could not be had.
     */
    static List<Path> sceneFrames(String videoId, Map<String, Path> fetched, Tally tally) {
        try {
            Path dir = work().resolve("frames").resolve(videoId), known = dir.resolve("scenes.txt");
            Files.createDirectories(dir);
            if (Files.isRegularFile(known)) {
                List<Path> out = new ArrayList<>();
                for (String l : Files.readAllLines(known, StandardCharsets.UTF_8)) if (l.matches("\\d+")) { Path p = dir.resolve("t-" + l + "s.jpg"); if (Files.isRegularFile(p)) out.add(p); }
                return out;
            }
            Path file = smallCopy(videoId, fetched, tally);
            if (file == null) return List.of();
            Video.Result sc = FFMPEG.run(List.of("ffmpeg", "-nostdin", "-loglevel", "info", "-i", file.toString(), "-vf", "select='gt(scene," + SCENE_CHANGE + ")',scale=640:-2,showinfo", "-vsync", "vfr", "-frames:v", "2000", "-q:v", "4", dir.resolve("s-%04d.jpg").toString()), Duration.ofMinutes(10));
            List<Double> at = new ArrayList<>();
            Matcher pm = Pattern.compile("pts_time:\\s*([0-9.]+)").matcher(sc.out() == null ? "" : sc.out());
            while (pm.find()) at.add(Double.parseDouble(pm.group(1)));
            List<Path> cut = new ArrayList<>();
            try (var s = Files.list(dir)) { for (Path p : s.sorted().toList()) if (p.getFileName().toString().startsWith("s-")) cut.add(p); }
            int keepEvery = Math.max(1, (int) Math.ceil((double) Math.min(cut.size(), at.size()) / SCENES_PER_VIDEO));
            List<Path> out = new ArrayList<>();
            for (int i = 0; i < cut.size(); i++) {
                Path p = cut.get(i);
                if (i >= at.size() || i % keepEvery != 0) { Files.deleteIfExists(p); continue; }
                int sec = (int) Math.round(at.get(i));
                Path named = dir.resolve("t-" + sec + "s.jpg");   // a shot's own frame, apart from a frame cut at that whole second
                Files.move(p, named, StandardCopyOption.REPLACE_EXISTING);
                if (!out.contains(named)) out.add(named);
            }
            out.sort(Comparator.comparingInt(VideoRecall::secondOf));
            StringBuilder b = new StringBuilder(); for (Path p : out) b.append(secondOf(p)).append("\n");
            Files.writeString(known, b.toString(), StandardCharsets.UTF_8);
            return out;
        } catch (Exception e) { return List.of(); }
    }

    /**
     * For a video without a storyboard: its frames cut from a small copy fetched through the helper, one every so many seconds and at
     * each change of scene, each read for its writing and described, the lines kept as the two tracks. 0 when the video could not be
     * fetched or no frame was read.
     */
    static int describeFrames(LibraryStore store, Held v, List<String> kinds, BooleanSupplier stop, Tally tally) { return describeFrames(store, v, kinds, stop, tally, 1, modelKey()); }

    /** The frames cut from a small copy read with a stride, as the storyboard is, and kept under {@code key}. */
    static int describeFrames(LibraryStore store, Held v, List<String> kinds, BooleanSupplier stop, Tally tally, int stride, String key) {
        List<Path> frames = frames(v.videoId());
        if (frames.isEmpty()) return 0;
        String how = "a frame every " + FRAME_EVERY_SECONDS + " seconds and at each change of scene" + (stride > 1 ? ", every " + ordinal(stride) + " after the first minute" : "");
        StringBuilder shown = new StringBuilder(head(v, "the words on screen (" + how + ", read by " + modelName() + "):"));
        StringBuilder seen = new StringBuilder(head(v, "what is on screen (" + how + ", described by " + modelName() + "):"));
        int nShown = 0, nSeen = 0;
        for (int i = 0; i < frames.size() && !stop.getAsBoolean(); i++) {
            if (stride > 1 && secondOf(frames.get(i)) > FIRST_MINUTE && i % stride != 0) continue;
            byte[] img;
            try { img = Files.readAllBytes(frames.get(i)); } catch (IOException e) { continue; }
            String clock = VideoText.clock(secondOf(frames.get(i)));
            String writing = writingOf(img);
            tally.read++;
            if (!writing.isEmpty()) { shown.append("[").append(clock).append("] ").append(writing).append("\n"); nShown++; }
            String line = VISION.describe(img, DESCRIBE + kindsSentence(kinds));
            tally.described++;
            if (line == null || line.isBlank()) continue;
            seen.append("[").append(clock).append("] ").append(line.strip().replace("\n", " ")).append("\n");
            nSeen++;
        }
        return keepTracks(store, v, shown, nShown, seen, nSeen, key);
    }

    /**
     * The frames of a video, cut from a small copy fetched through the helper and kept in the helper's folder, each named by its second
     * ({@code t-<seconds>.jpg}): one every so many seconds, the gap widened so that a long video is covered whole within the cap, and
     * one at every change of scene, since a shot of a few seconds falls between the fixed ones; empty when the video could not be had.
     */
    static List<Path> frames(String videoId) {
        Path dir = work().resolve("frames").resolve(videoId);
        try {
            if (Files.isDirectory(dir)) { List<Path> have = framesIn(dir); if (!have.isEmpty()) return have; }
            Files.createDirectories(dir);
            Path file = work().resolve("tmp").resolve(videoId + ".mp4");
            Files.createDirectories(file.getParent());
            Video.Result r = Video.ytdlp(List.of("-f", SMALL, "--no-playlist", "-o", file.toString(), address(videoId)), Duration.ofMinutes(10), Video.readPaceMs);
            if (r.code() != 0 || !Files.exists(file)) return List.of();
            try {
                double duration = 0;
                try { duration = Double.parseDouble(FFMPEG.run(List.of("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", file.toString()), Duration.ofMinutes(2)).out().strip().split("\n")[0].strip()); } catch (Exception ignored) { }
                int half = Math.max(1, FRAMES_PER_VIDEO / 2);
                int every = Math.max(FRAME_EVERY_SECONDS, duration > 0 ? (int) Math.ceil(duration / half) : FRAME_EVERY_SECONDS);
                Video.Result f = FFMPEG.run(List.of("ffmpeg", "-nostdin", "-loglevel", "error", "-i", file.toString(), "-vf", "fps=1/" + every + ",scale=640:-2", "-frames:v", String.valueOf(half), "-q:v", "4", dir.resolve("f-%04d.jpg").toString()), Duration.ofMinutes(10));
                if (f.code() != 0) return List.of();
                List<Path> fixed = new ArrayList<>();
                try (var s = Files.list(dir)) { for (Path p : s.sorted().toList()) if (p.getFileName().toString().startsWith("f-")) fixed.add(p); }
                for (Path p : fixed) {
                    int n = Integer.parseInt(p.getFileName().toString().substring(2, 6));
                    Files.move(p, dir.resolve("t-" + ((n - 1) * every) + ".jpg"), StandardCopyOption.REPLACE_EXISTING);
                }
                // the changes of scene: showinfo says each kept frame's second; a frame within three seconds of a fixed one is not kept twice
                Video.Result sc = FFMPEG.run(List.of("ffmpeg", "-nostdin", "-loglevel", "info", "-i", file.toString(), "-vf", "select='gt(scene," + SCENE_CHANGE + ")',scale=640:-2,showinfo", "-vsync", "vfr", "-frames:v", String.valueOf(half), "-q:v", "4", dir.resolve("s-%04d.jpg").toString()), Duration.ofMinutes(10));
                List<Double> at = new ArrayList<>();
                Matcher pm = Pattern.compile("pts_time:\\s*([0-9.]+)").matcher(sc.out() == null ? "" : sc.out());
                while (pm.find()) at.add(Double.parseDouble(pm.group(1)));
                List<Path> scenes = new ArrayList<>();
                try (var s = Files.list(dir)) { for (Path p : s.sorted().toList()) if (p.getFileName().toString().startsWith("s-")) scenes.add(p); }
                for (int i = 0; i < scenes.size(); i++) {
                    Path p = scenes.get(i);
                    if (i >= at.size()) { Files.deleteIfExists(p); continue; }
                    int sec = (int) Math.round(at.get(i));
                    boolean near = false;
                    for (Path q : framesIn(dir)) if (Math.abs(secondOf(q) - sec) <= 3) near = true;
                    if (near) { Files.deleteIfExists(p); continue; }
                    Files.move(p, dir.resolve("t-" + sec + ".jpg"), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { try { Files.deleteIfExists(file); } catch (IOException ignored) { } }
            return framesIn(dir);
        } catch (Exception e) { return List.of(); }
    }

    /** The kept frames of a folder, by their second. */
    static List<Path> framesIn(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(dir)) { for (Path p : s.toList()) if (p.getFileName().toString().matches("t-\\d+s?\\.jpg")) out.add(p); }
        out.sort(Comparator.comparingInt(VideoRecall::secondOf));
        return out;
    }

    /** The second a kept frame is named by ({@code t-<second>.jpg}, or {@code t-<second>s.jpg} for a shot between the frames). */
    static int secondOf(Path frame) {
        String n = frame.getFileName().toString();
        return n.matches("t-\\d+s?\\.jpg") ? Integer.parseInt(n.replaceAll("\\D", "")) : 0;
    }

    /**
     * The frame kept for a moment, for the model to look at: a shot between the frames has its own ({@code t-<second>s.jpg}), any other
     * moment the frame cut at its very second; null when none is kept. A change of scene a second away is another picture, so a
     * moment's frame is never one nearby.
     */
    static byte[] frameAt(String videoId, int seconds, boolean shot) {
        Path dir = work().resolve("frames").resolve(videoId);
        try {
            Path p = dir.resolve("t-" + seconds + (shot ? "s" : "") + ".jpg");
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) { return null; }
    }

    /**
     * The frame at a second cut at full size from a small copy of the video, fetched through the helper once in a search (the copies
     * are in {@code fetched}, deleted when the search ends) and kept with the other frames; null when the video could not be had.
     */
    static byte[] cutFrame(String videoId, int seconds, Map<String, Path> fetched, Tally tally) {
        try {
            Path file = smallCopy(videoId, fetched, tally);
            if (file == null) return null;
            Path dir = work().resolve("frames").resolve(videoId);
            Files.createDirectories(dir);
            Path out = dir.resolve("t-" + seconds + ".jpg");
            Video.Result f = FFMPEG.run(List.of("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-ss", String.valueOf(seconds), "-i", file.toString(), "-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "3", out.toString()), Duration.ofMinutes(2));
            return f.code() == 0 && Files.isRegularFile(out) ? Files.readAllBytes(out) : null;
        } catch (Exception e) { return null; }
    }

    /** A small copy of a video, fetched through the helper once in a search and kept in {@code fetched} until the search ends; null when it could not be had. */
    static Path smallCopy(String videoId, Map<String, Path> fetched, Tally tally) {
        Path file = fetched.get(videoId);
        if (file != null) return file;
        try {
            file = work().resolve("tmp").resolve(videoId + ".mp4");
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(file);
            Video.Result r = Video.ytdlp(List.of("-f", SMALL, "--no-playlist", "-o", file.toString(), address(videoId)), Duration.ofMinutes(10), Video.readPaceMs);
            if (r.code() != 0 || !Files.exists(file)) return null;
            fetched.put(videoId, file);
            tally.fetched++;
            return file;
        } catch (Exception e) { return null; }
    }

    /** The storyboard's small frame nearest a second, scaled up, when the video itself could not be had; null when none is kept. */
    static byte[] tileAt(String videoId, int seconds) {
        Storyboards.Sheets sheets = Storyboards.kept(videoId, work());
        if (sheets == null) return null;
        try {
            Storyboards.Tile best = null;
            for (Storyboards.Tile t : Storyboards.tiles(sheets, FFMPEG)) if (best == null || Math.abs(t.seconds() - seconds) < Math.abs(best.seconds() - seconds)) best = t;
            return best == null ? null : best.png(640);
        } catch (IOException e) { return null; }
    }

    static String kindsSentence(List<String> kinds) {
        List<String> ks = new ArrayList<>(); for (String k : kinds) if (k != null && !k.isBlank()) ks.add(k.strip());
        return ks.isEmpty() ? "" : " They are looking for " + Career.and(ks) + ".";
    }

    // ── the model that reads pictures ─────────────────────────────────────────────────────────────────────────

    private static volatile long visionCheckedAt = 0;
    private static volatile boolean visionKnown = false;
    private static volatile Vision visionProbed = null;   // the model the answer was read from: a stand-in put in its place is asked afresh (a page's probe had been answering for a test's stand-in)
    /** Forgets what was found out about the model that reads pictures, so the next call asks again; a test uses it between stand-ins. */
    static void forgetVision() { visionCheckedAt = 0; visionKnown = false; }

    /** Whether a model that reads pictures answers: asked once with a small picture, remembered for ten minutes. */
    public static boolean visionAnswers() {
        long now = System.currentTimeMillis();
        if (VISION == visionProbed && now - visionCheckedAt < 600_000) return visionKnown;
        boolean ok;
        try {   // a text-only model accepts the message and answers something: the answer must name what is in the picture
            String r = VISION.describe(dot(), "This picture is two colours, one on the left and one on the right. Which two? Answer with the two colour names only.");
            String low = r == null ? "" : r.toLowerCase(Locale.ROOT);
            ok = low.contains("red") && low.contains("blue");
        } catch (RuntimeException e) { ok = false; }
        visionCheckedAt = now; visionKnown = ok; visionProbed = VISION;
        return ok;
    }

    /** A small picture, red on the left and blue on the right, to ask the model whether it reads pictures at all. */
    static byte[] dot() {
        try {
            BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 32; x++) for (int y = 0; y < 32; y++) img.setRGB(x, y, x < 16 ? 0xCC2222 : 0x2244CC);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) { return new byte[0]; }
    }

    /** The picture and the question to the model that sees, found as the library finds it everywhere; the library's own when none is known to see. */
    static String liveVision(byte[] image, String ask) {
        DriveClient eyes = ModelEntries.seeingDrive();
        DriveClient drive = eyes != null ? eyes : new DriveClient(Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), modelName());
        ArrayNode msgs = M.createArrayNode();
        ArrayNode content = msgs.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", ask);
        boolean png = image.length > 4 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G';
        content.addObject().put("type", "image_url").putObject("image_url").put("url", "data:image/" + (png ? "png" : "jpeg") + ";base64," + Base64.getEncoder().encodeToString(image));
        try { return drive.classify(msgs, 400); } catch (RuntimeException e) { return ""; }
    }

    /** ffmpeg as a program on this machine; its output is read so that a failure says why. */
    static Video.Result ffmpeg(List<String> cmd, Duration timeout) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return new Video.Result(124, "ffmpeg took too long"); }
        return new Video.Result(p.exitValue(), out);
    }

    // ── the lines ─────────────────────────────────────────────────────────────────────────────────────────────

    /** The address in an index hit's title ("title — locator"); "" when none. */
    static String locatorOf(String titleField) {
        if (titleField == null) return "";
        int at = titleField.lastIndexOf(" — ");
        return at < 0 ? "" : titleField.substring(at + 3).strip();
    }

    static String titleOf(String titleField) {
        if (titleField == null) return "";
        int at = titleField.lastIndexOf(" — ");
        return (at < 0 ? titleField : titleField.substring(0, at)).strip();
    }

    /** A raw capture's text by its file name; "" when it cannot be read. */
    static String rawText(LibraryStore store, String rawFile) {
        try { return Files.readString(store.root().resolve("raw").resolve(rawFile), StandardCharsets.UTF_8); } catch (IOException | RuntimeException e) { return ""; }
    }

    /**
     * The timed line of a text nearest the query: each query word counts for how rare it is among the text's timed lines (a word in
     * every line says nothing; a word in one line says everything), a word matches a line's word by its first five letters, so "shaking"
     * finds "shake"; {seconds, index}, or null when the text has no timed line.
     */
    static int[] bestLine(String text, String query) {
        if (text == null) return null;
        List<String> words = new ArrayList<>();
        for (String w : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3 && !words.contains(w)) words.add(w);
        String[] lines = text.split("\n");
        List<Integer> stamped = new ArrayList<>();
        List<Set<String>> stems = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (!STAMP.matcher(lines[i]).find()) continue;
            stamped.add(i);
            Set<String> s = new HashSet<>();
            for (String w : lines[i].toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3) s.add(stem(w));
            stems.add(s);
        }
        if (stamped.isEmpty()) return null;
        Map<String, Integer> df = new LinkedHashMap<>();
        for (String w : words) { String st = stem(w); int n = 0; for (Set<String> s : stems) if (s.contains(st)) n++; df.put(w, n); }
        int bestAt = -1; double bestScore = -1;
        for (int k = 0; k < stamped.size(); k++) {
            double score = 0;
            for (String w : words) { int n = df.get(w); if (n > 0 && stems.get(k).contains(stem(w))) score += Math.log(1.0 + (double) stamped.size() / n); }
            if (score > bestScore) { bestScore = score; bestAt = stamped.get(k); }
        }
        Matcher m = STAMP.matcher(lines[bestAt]); m.find();
        return new int[]{seconds(m), bestAt};
    }

    /** A word without its ending, cut to its first four letters when it is long: "shaking" and "shake" meet at "shak", "landing" and "lands" at "land". */
    static String stem(String w) {
        String s = w;
        for (String end : new String[]{"ing", "ed", "es", "ly", "s"}) if (s.length() > end.length() + 3 && s.endsWith(end)) { s = s.substring(0, s.length() - end.length()); break; }
        return s.length() >= 5 ? s.substring(0, 4) : s;
    }

    static String lineAt(String text, int index) {
        String[] lines = text.split("\n");
        if (index < 0 || index >= lines.length) return "";
        return STAMP.matcher(lines[index]).replaceFirst("").strip();
    }

    static int seconds(Matcher m) {
        int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
        if (m.group(3) != null) return a * 3600 + b * 60 + Integer.parseInt(m.group(3));
        return a * 60 + b;
    }

    static String headOf(Path p) throws IOException {
        StringBuilder b = new StringBuilder();
        try (var r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line; int n = 0;
            while ((line = r.readLine()) != null && n++ < 12) { b.append(line).append('\n'); if (n > 1 && line.equals("---")) break; }
        }
        return b.toString();
    }

    static String after(String head, String key) {
        for (String line : head.split("\n")) if (line.startsWith(key)) return line.substring(key.length()).strip();
        return "";
    }
}
