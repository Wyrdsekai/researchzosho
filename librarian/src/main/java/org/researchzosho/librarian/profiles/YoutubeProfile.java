package org.researchzosho.librarian.profiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.researchzosho.librarian.Chrome;
import org.researchzosho.librarian.LibraryProtocol;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Patrons;
import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.ProtocolError;
import org.researchzosho.librarian.VideoRecall;
import org.researchzosho.librarian.VideoLook;
import org.researchzosho.librarian.VideoRecallPage;
import org.researchzosho.librarian.Video;
import org.researchzosho.librarian.Vocabulary;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The youtube field: questions about channels, videos and what is shown or said in them. What it adds is the collection a web engine
 * and YouTube's own search both bury — the small channels and the single videos on a subject — and the way a channel is judged: by what
 * it makes and whether it still posts, never by its subscribers. A run of this field reads videos as sources, cites moments in them,
 * and puts the people in them (practitioners, players, makers) into the library's graph. The tools come from the video helper
 * ({@code researchzosho video install}); this profile is the judgment that goes with them. Without the helper the field still joins a
 * run by its words, and its rules name the web tools the run does have: a worker told to call a tool it has not got spends its turns
 * on it (the same rule as {@code Researcher.workerRegister}).
 */
public final class YoutubeProfile implements Profile {

    @Override public String name() { return "youtube"; }

    @Override public String goalName() { return "choose"; }

    @Override public String sourceName() { return "videos"; }

    // ── the Video page, the chat's /video, finding what was seen ────────────────────────────────────────────

    @Override public List<Link> menu() { return List.of(new Link("/video", "Video", false, "▶")); }
    @Override public List<String> pagePaths() { return List.of("/video"); }

    @Override public Page page(LibraryStore store, Patrons.Patron patron, String path, String method, Map<String, String> query, Map<String, String> form) throws IOException {
        if (!path.equals("/video")) return null;
        Patrons.check(store, patron, Patrons.Level.read);
        boolean writer;
        try { Patrons.check(store, patron, Patrons.Level.write); writer = true; } catch (RuntimeException e) { writer = false; }
        if ("POST".equals(method)) {
            Patrons.check(store, patron, Patrons.Level.write);
            if (form.getOrDefault("op", "").equals("picture")) {
                List<String> kinds = new ArrayList<>();
                for (Map.Entry<String, String> e : form.entrySet()) if (e.getKey().equals("kinds") || e.getKey().startsWith("kinds")) for (String k : e.getValue().split(",")) if (!k.isBlank()) kinds.add(k.strip());
                try { VideoRecall.file(store, form.getOrDefault("description", ""), kinds, patron.did()); }
                catch (IllegalArgumentException e) { return new Page(Chrome.now().t("Video"), "<p>" + VideoRecallPage.esc(e.getMessage()) + "</p><p><a href=\"/video\">" + Chrome.now().t("Back") + "</a></p>", false, null); }
            }
            if (form.getOrDefault("op", "").equals("look")) {   // the look at a video at any address: the answer on its own page
                String said;
                try { said = VideoLook.look(store, form.getOrDefault("video", "").strip(), form.getOrDefault("what", "").strip(), form.containsKey("sparse"), patron.did()).path("summary").asText(); }
                catch (IllegalArgumentException | IllegalStateException e) { said = e.getMessage(); }
                return new Page(Chrome.now().t("Look at a video"), "<p>" + VideoRecallPage.esc(said).replace("\n", "<br>") + "</p><p><a href=\"/video\">" + Chrome.now().t("Back to the Video page") + "</a></p>", false, null);
            }
            if (form.getOrDefault("op", "").equals("candidates")) {   // the candidate pool for a remembered video: the thing's name per language in, the pool best fit first out
                ObjectNode a = new ObjectMapper().createObjectNode().put("op", "candidates").put("things", form.getOrDefault("things", "")).put("remembered", form.getOrDefault("remembered", "")).put("before", form.getOrDefault("before", "")).put("after", form.getOrDefault("after", "")).put("language", form.getOrDefault("language", ""));
                a.putObject("patron").put("did", patron.did()).put("name", patron.name()).put("runtime", "web");
                String said;
                try { said = new LibraryProtocol(store).video(a).path("summary").asText(); } catch (ProtocolError e) { said = e.getMessage(); }
                return new Page(Chrome.now().t("Candidates for a video you remember"), "<p>" + VideoRecallPage.esc(said).replace("\n", "<br>") + "</p><p><a href=\"/video\">" + Chrome.now().t("Back to the Video page") + "</a></p>", false, null);
            }
            if (form.getOrDefault("op", "").equals("sift")) {   // the funnel over a kept pool: the things remembered in, the survivors best first out
                ObjectNode a = new ObjectMapper().createObjectNode().put("op", "sift").put("what", form.getOrDefault("what", "")).put("from", form.getOrDefault("from", ""));
                if (form.getOrDefault("limit", "").matches("\\d+")) a.put("limit", Integer.parseInt(form.get("limit")));
                a.putObject("patron").put("did", patron.did()).put("name", patron.name()).put("runtime", "web");
                String said;
                try { said = new LibraryProtocol(store).video(a).path("summary").asText(); } catch (ProtocolError e) { said = e.getMessage(); }
                return new Page(Chrome.now().t("Sifted candidates"), "<p>" + VideoRecallPage.esc(said).replace("\n", "<br>") + "</p><p><a href=\"/video\">" + Chrome.now().t("Back to the Video page") + "</a></p>", false, null);
            }
            return new Page(Chrome.now().t("Video"), "", false, "/video#picture");
        }
        return new Page(Chrome.now().t("Video"), VideoRecallPage.body(store, writer, query.getOrDefault("q", ""), patron), false, null);
    }

    /** "remembered nobody speaks; a pocket on the front" at the end of a /video candidates line. */
    private static final Pattern REMEMBERED_WORD = Pattern.compile("(?i)\\bremembered\\s*:?\\s+(.+)$");
    /** "before 2025-11" or "after 2023" in a /video candidates line. */
    private static final Pattern DATE_WORD = Pattern.compile("\\b(before|after) (\\d{4}(?:-\\d{2}){0,2})");

    @Override public List<String> chatCommandWords() { return List.of("/video"); }
    @Override public String chatCommandHelp() { return "/video (what you saw in a video: saw <words> finds the moment in what was said or seen; picture <what you remember> [kinds: person, thing, place, text] looks through the frames; candidates <the thing, once per language, comma-separated> gathers the candidates for a video you remember; sift <what you remember> from <the pool's key> judges their thumbnails and first sheets; status)"; }

    @Override public String chatReply(LibraryStore store, Patrons.Patron patron, String chat, String line) throws IOException {
        if (line == null) return null;
        String l = line.strip();
        if (!(l.equals("/video") || l.startsWith("/video "))) return null;
        String rest = l.length() > 6 ? l.substring(6).strip() : "";
        String[] head = rest.split("\\s+", 2);
        String verb = head[0], tail = head.length > 1 ? head[1].strip() : "";
        switch (verb) {
            case "saw" -> {
                if (tail.isEmpty()) return "Type /video saw and what you remember hearing or seeing, and the library finds the moment in the videos it has read.";
                return VideoRecall.sawText(VideoRecall.saw(store, tail, 8), tail);
            }
            case "picture" -> {
                if (!patron.person() && !Patrons.mayWrite(store, patron)) return "Looking for a picture runs the library's model, so it is for those who may write to this library.";
                if (tail.isEmpty()) return "Type /video picture and what you remember seeing; add kinds: person, thing, place, text to say what is in it.";
                List<String> kinds = new ArrayList<>();
                String description = tail;
                int at = tail.toLowerCase(Locale.ROOT).lastIndexOf("kinds:");
                if (at >= 0) { for (String k : tail.substring(at + 6).split(",")) if (!k.isBlank()) kinds.add(k.strip()); description = tail.substring(0, at).strip(); }
                try { return VideoRecall.file(store, description, kinds, patron.did()); } catch (IllegalArgumentException e) { return e.getMessage(); }
            }
            case "candidates" -> {   // the candidate pool, in the turn: ten minutes of the program's own searches and listings
                if (tail.isEmpty()) return "Type /video candidates and the name of the thing the video makes or shows, two to four words, once in each language it may be in, separated by commas: /video candidates apron, エプロン, 앞치마. Add before 2025-11 when you know you watched it before then, and, last, remembered and what you remember of the video itself, one thing after another with a semicolon between: /video candidates apron, エプロン remembered nobody speaks; a pocket on the front.";
                ObjectNode a = new ObjectMapper().createObjectNode().put("op", "candidates");
                String things = tail;
                Matcher remembered = REMEMBERED_WORD.matcher(things);   // what is remembered of the video itself: everything after the word
                if (remembered.find()) { a.put("remembered", remembered.group(1).strip()); things = things.substring(0, remembered.start()); }
                Matcher m = DATE_WORD.matcher(tail);
                while (m.find()) { a.put(m.group(1), m.group(2)); things = things.replace(m.group(0), ""); }
                a.put("things", things.replaceAll(",\\s*,", ",").replaceAll("^[,\\s]+|[,\\s]+$", ""));
                a.putObject("patron").put("did", patron.did()).put("name", patron.name()).put("runtime", "chat");
                try { return new LibraryProtocol(store).video(a).path("summary").asText(); } catch (ProtocolError e) { return e.getMessage(); }
            }
            case "sift" -> {   // the funnel over a kept pool, in the turn: thumbnails and first sheets judged, the survivors best first
                if (tail.isEmpty()) return "Type /video sift and what you remember of the video, one thing after another, then from and the key the candidates answer gave: /video sift a striped apron; a red tag at the neck; the pattern pieces laid out at the start from 2026-11-05-0a1b2c3d4e5f6071. Without from, the newest pool is sifted.";
                ObjectNode a = new ObjectMapper().createObjectNode().put("op", "sift");
                Matcher m = Pattern.compile("\\bfrom\\s+((?:\\d{4}-\\d{2}-\\d{2}-)?[0-9a-f]{16})\\b").matcher(tail);
                String what = tail;
                if (m.find()) { a.put("from", m.group(1)); what = tail.replace(m.group(0), ""); }
                a.put("what", what.strip());
                a.putObject("patron").put("did", patron.did()).put("name", patron.name()).put("runtime", "chat");
                try { return new LibraryProtocol(store).video(a).path("summary").asText(); } catch (ProtocolError e) { return e.getMessage(); }
            }
            case "", "status" -> {
                List<VideoRecall.Held> held = VideoRecall.held(store);
                long said = held.stream().filter(VideoRecall.Held::said).count(), shown = held.stream().filter(VideoRecall.Held::shown).count(), seen = held.stream().filter(VideoRecall.Held::seen).count();
                String running = VideoRecallPage.running(store);
                return "The library has read " + held.size() + (held.size() == 1 ? " video" : " videos") + ", " + said + " with what was said, " + shown + " with the words on screen and " + seen + " with what is seen. "
                        + (running.isEmpty() ? "" : running + " ") + "/video saw <words> finds a moment; /video picture <what you remember> looks through the frames.";
            }
            default -> { return "That is not one of the video commands. They are: /video saw <words>, /video picture <what you remember> [kinds: person, thing, place, text], /video candidates <the thing, once per language, comma-separated>, /video sift <what you remember> from <the pool's key>, /video status."; }
        }
    }

    @Override public String description() { return "channels and videos: found by fit and by whether a channel still posts, never by subscribers; videos read as sources, moments cited"; }

    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("uploaded-by", "was uploaded by", List.of("posted by", "published by the channel"), ""),
                new Vocabulary.Term("shows", "shows", List.of("demonstrates", "features", "performs"), ""),
                new Vocabulary.Term("explains", "explains", List.of("teaches", "covers", "discusses"), ""),
                new Vocabulary.Term("appears-in", "appears in", List.of("is interviewed in", "is seen in", "speaks in"), ""),
                new Vocabulary.Term("practitioner-of", "is a practitioner of", List.of("practises", "teaches the art of", "competes in"), ""),
                new Vocabulary.Term("channel-about", "is a channel about", List.of("covers the subject", "makes videos on"), ""));
    }

    /** Words that mean a question is about YouTube or about videos as such. Not "film" or "watch": those belong to every field. */
    private static final List<String> WORDS = List.of(
            "youtube", "youtuber", "channel", "channels", "video", "videos", "vlog", "livestream", "live stream", "uploads", "subscriber",
            "ユーチューブ", "動画", "チャンネル", "유튜브", "동영상", "채널", "视频", "頻道", "频道", "影片", "vidéo", "vídeo", "kanal", "canal");

    @Override public boolean applies(String question) {
        String q = Vocabulary.norm(question);
        for (String w : WORDS) if (q.contains(w)) return true;
        return false;
    }

    /** The video tools are in the run only when the helper is installed on this machine. */
    private static boolean tools() { return Video.installed(); }

    /** Words that make a question one about a video or videos, and the words that make it one about channels instead. */
    private static final List<String> VIDEO_WORDS = List.of("video", "videos", "youtube", "tutorial", "clip", "vlog", "動画", "ユーチューブ", "영상", "동영상", "유튜브", "视频", "影片", "vidéo", "vídeo");
    private static final List<String> CHANNEL_WORDS = List.of("channel", "channels", "チャンネル", "채널", "频道", "頻道", "youtuber", "youtubers");

    /**
     * Whether a question under the choose goal is about a video or videos rather than about channels: the case whose candidates come
     * from {@code candidates} alone. The ranked searches leave such a run's table (Researcher): a worker told "never a search" with a
     * search in reach made three before it called candidates (the sixth live run, 2026-11-05), and the hits then sat in its context.
     */
    public static boolean findOne(String question) {
        String q = Vocabulary.norm(question == null ? "" : question);
        boolean video = false;
        for (String w : VIDEO_WORDS) if (q.contains(w)) { video = true; break; }
        if (!video) return false;
        for (String w : CHANNEL_WORDS) if (q.contains(w)) return false;
        return true;
    }

    @Override public String register() {
        String how = tools()
                ? "Judge a channel by what it makes and whether it still posts: its latest uploads and their days (channel_uploads; with match=<words>, every upload it made about a subject), what its videos "
                + "show or say (video_details), and who is in them. Subscribers and views are shown with their day and never ranked on: YouTube's own "
                + "search leans on them and hides the small and the new, so search channels as well as videos (video_search with kind=channels), "
                + "read past the first page, and search again in the languages the subject is spoken in. A video is a source like a page: cite "
                + "the moment, as https://www.youtube.com/watch?v=<id>&t=<seconds>s, from its transcript or its chapters, so that the claim can be "
                + "checked at that moment. "
                : "The video helper is not installed on this library (researchzosho video install adds video_search, channel_uploads and "
                + "video_details), so YouTube is read through web_search and web_fetch: search for channels and for videos by the subject's words, "
                + "and read a channel's page or a video's page as a web page. Judge a channel by what it makes and whether it still posts: its "
                + "latest uploads and their days, as its page shows them, and who is in its videos. Subscribers and views are shown with their day "
                + "and never ranked on: YouTube's own search leans on them and hides the small and the new, so search for channels as well as "
                + "videos, and search again in the languages the subject is spoken in. A video is a source like a page: cite it by its address, "
                + "and the moment as https://www.youtube.com/watch?v=<id>&t=<seconds>s when its description or chapters give one. ";
        return how + "Who is on screen is known from their name on screen or in the description, never from their face. "
                + "A channel with no upload in two years is noted as inactive, with the date of its last one. "
                + "A channel is named with its address (https://www.youtube.com/channel/<id> or https://www.youtube.com/@<handle>) wherever it is "
                + "named, so that the reader can open it.";
    }

    @Override public String planRules() {
        return FIND_ONE + " Otherwise this is a question about channels, videos or what is shown in them. Split it by the kinds of channel that could answer it (a "
                + "practitioner's own channel, a club's, a federation's, an explainer's, a broadcaster's) and by language, and give one sub-question "
                + "to the small and the recent channels, found by " + (tools() ? "a channel search" : "searching for channels") + " rather than by what is "
                + "popular. Where a technique, a performance or a way of doing something is asked about, one sub-question "
                + (tools() ? "watches it: the moments in the videos where it is shown." : "finds the videos where it is shown, from their pages and descriptions.");
    }

    /**
     * The one video a person remembers is one thing judged whole: a run asked for a remembered sewing video split it into six sub-questions,
     * one a remembered feature, and each worker searched YouTube with its feature in the words, which a title search does not match, so the
     * video was never among the hits and nobody gathered one shortlist to look at (2026-11-04).
     */
    public static final String FIND_ONE = "When the person is looking for ONE video they remember by what they saw or heard, the remembered features belong "
            + "to that one video and are checked together by looking, so this is the exception to splitting by facet: plan one sub-question carrying the whole "
            + "ask, and one only, since candidates names the thing in every language itself and two workers would gather and sift the same pool twice. The candidates come from candidates, never from a search: it is called once with "
            + "the name of the thing made or shown, only the thing, two to four words, in each language the video may be in, and what the person remembers of the "
            + "video itself in remembered, short phrases in their own words, such as {\"things\": [\"apron\", "
            + "\"エプロン\", \"앞치마\"], \"remembered\": [\"nobody speaks\", \"a pocket on the front\"]}, with the dates and the length when the person gave them, and it enumerates for itself, deep searches of its own plain "
            + "phrasings, the pages where other people describe a video in the words remembered, whole channels, the Data API in date order, the other indexes and the person's own watch history, filters by what a machine can check "
            + "and hands back the pool best fit first under a key; the remembered features (the material, a colour, that nobody speaks, a pocket, how it closes) go in remembered "
            + "as the person said them and are never typed into things, since a title seldom names them, and they are checked by looking. Then sift, given that key in from and the remembered features in what, "
            + "judges every candidate's thumbnail and the first storyboard sheet of those that pass, and hands back the survivors best first; it says when the time "
            + "cut it, and sift again with page=2 goes on. Then the first five survivors are given to one look together in its videos, sparse, for every remembered "
            + "feature at once; then the next five, down the survivors while the budget lasts, before any one is looked at again; and the best gets a full look. "
            + "A run of this kind has no ranked search on its table: candidates is the search, and the sift is the funnel.";

    @Override public String criticRules() {
        return "For a question about channels and videos the evidence is enough when the candidates include small and recent channels as well as the "
                + "well-known ones, each with its address, its last upload's day and what it actually makes from its own uploads; when a claim about "
                + "what is shown or said " + (tools() ? "cites the moment in the video" : "cites the video, and the moment when its page gives one") + "; and when "
                + "who is on screen is known from a name shown, not a face. A gap worth another search names a kind of channel, a language or a video not yet "
                + (tools() ? "watched." : "read.");
    }

    @Override public String writerRules() {
        return "Give the channels in a table: channel (its name as a link to its address, [name](https://www.youtube.com/channel/<id>), so that the "
                + "reader can open it from the table), what it makes, last upload (and how long ago), subscribers, language, why it fits the "
                + "question. Order it by fit and by how alive the channel is, not by subscribers. Say which channels are inactive, and since when. "
                + "Give the videos worth watching " + (tools() ? "with the moment to start at, as a link to that moment," : "as links, with the moment to start at when one is known,")
                + " and one line on what is shown there.";
    }
}
