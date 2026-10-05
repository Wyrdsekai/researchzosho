package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Video;
import org.researchzosho.librarian.Vocabulary;

import java.util.List;

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

    @Override public String register() {
        String how = tools()
                ? "Judge a channel by what it makes and whether it still posts: its latest uploads and their days (channel_uploads), what its videos "
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
        return "This is a question about channels, videos or what is shown in them. Split it by the kinds of channel that could answer it (a "
                + "practitioner's own channel, a club's, a federation's, an explainer's, a broadcaster's) and by language, and give one sub-question "
                + "to the small and the recent channels, found by " + (tools() ? "a channel search" : "searching for channels") + " rather than by what is "
                + "popular. Where a technique, a performance or a way of doing something is asked about, one sub-question "
                + (tools() ? "watches it: the moments in the videos where it is shown." : "finds the videos where it is shown, from their pages and descriptions.");
    }

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
