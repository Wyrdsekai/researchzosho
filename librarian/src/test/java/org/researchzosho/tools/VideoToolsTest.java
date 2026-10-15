package org.researchzosho.tools;

import org.researchzosho.librarian.Video;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The three video tools against a stand-in for yt-dlp and the tunnel: what they ask for, and what a run reads back. */
class VideoToolsTest {

    private static final ObjectMapper J = new ObjectMapper();
    @TempDir Path home;
    private String realHome;
    private Video.Runner realRunner;
    private Video.Fetcher realFetcher;
    private java.util.function.BooleanSupplier realProbe;
    final List<List<String>> ran = new ArrayList<>();
    final List<String> fetched = new ArrayList<>();

    @BeforeEach
    void anInstalledHelperOfItsOwn() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Path dir = home.resolve(".researchzosho").resolve("video");
        Files.createDirectories(dir.resolve("venv").resolve("bin"));
        Files.writeString(dir.resolve("wgcf-profile.conf"), "[Interface]\n");
        Files.writeString(dir.resolve("warp.env"), "VPN_TYPE=wireguard\n");
        Files.writeString(dir.resolve("venv").resolve("bin").resolve("yt-dlp"), "#!/bin/sh\n");
        realRunner = Video.runner; realFetcher = Video.fetcher; realProbe = Video.providerProbe;
        Video.providerProbe = () -> true;
        Video.paceMs = 0; Video.readPaceMs = 0; // a stand-in for yt-dlp needs no pacing
        Video.fetcher = (url, t) -> { fetched.add(url); return url.contains("feeds/videos.xml")
                ? "<feed><author><name>IAIDO Archives</name></author><entry><yt:videoId>9RJGjAkFnag</yt:videoId><title>Okuiai 11</title><published>2025-06-14T10:00:00+00:00</published><media:group><media:statistics views=\"5195\"/></media:group></entry></feed>"
                : "{\"events\":[{\"tStartMs\":100000,\"dDurationMs\":2000,\"segs\":[{\"utf8\":\"let me start from the history\"}]}]}"; };
        Video.runner = (cmd, t) -> {
            ran.add(cmd);
            String line = String.join(" ", cmd);
            if (line.contains("ytsearch")) return new Video.Result(0, "{\"entries\":[{\"id\":\"Q_ZtgMnPsvw\",\"title\":\"Iaido vs Iaijutsu\",\"channel\":\"Shogo\",\"duration\":1343,\"view_count\":224840}]}");
            if (line.contains("sp=EgIQAg")) return new Video.Result(0, "{\"entries\":[{\"id\":\"UCsf2bCntZg9F-bSmE__j8BQ\",\"title\":\"Beyond the Sword Iaido\",\"channel_follower_count\":3,\"url\":\"https://www.youtube.com/channel/UCsf2bCntZg9F-bSmE__j8BQ\"}]}");
            if (line.contains("@IaidoArchives/videos")) return new Video.Result(0, "{\"channel\":\"IAIDO Archives\",\"channel_id\":\"UCGqRnq4nN_NLDxp5eQdqA4g\",\"entries\":[{\"id\":\"9RJGjAkFnag\",\"title\":\"Okuiai 11\"}]}");
            if (line.contains("/channel/UCGqRnq4nN_NLDxp5eQdqA4g/videos") && line.contains("--flat-playlist")) return new Video.Result(0, "{\"channel\":\"IAIDO Archives\",\"channel_id\":\"UCGqRnq4nN_NLDxp5eQdqA4g\",\"entries\":["
                    + "{\"id\":\"9RJGjAkFnag\",\"title\":\"Okuiai 11\",\"duration\":300},{\"id\":\"aaaaaaaaaa1\",\"title\":\"Mae, the first form\",\"duration\":200},{\"id\":\"aaaaaaaaaa2\",\"title\":\"Okuiai 10\",\"duration\":280},"
                    + "{\"id\":\"aaaaaaaaaa3\",\"title\":\"Seiza no bu\",\"duration\":500},{\"id\":\"aaaaaaaaaa4\",\"title\":\"A walk to the dojo\",\"duration\":120}]}");
            if (line.contains("watch?v=Fallback0id")) return new Video.Result(0, "{\"id\":\"Fallback0id\",\"title\":\"No track written\",\"channel\":\"Shogo\",\"upload_date\":\"20210615\",\"duration\":100,\"language\":\"en\","
                    + "\"subtitles\":{},\"automatic_captions\":{\"en\":[{\"ext\":\"json3\",\"url\":\"https://www.youtube.com/api/timedtext?v=Fallback0id\"}]}}");
            if (line.contains("--write-auto-subs") && line.contains("watch?v=Q_ZtgMnPsvw")) {
                int o = cmd.indexOf("-o"); String langs = cmd.get(cmd.indexOf("--sub-langs") + 1);
                if (langs.startsWith("en")) try { Files.writeString(Path.of(cmd.get(o + 1).replace("%(id)s", "Q_ZtgMnPsvw") + ".en.json3"), "{\"events\":[{\"tStartMs\":100000,\"dDurationMs\":2000,\"segs\":[{\"utf8\":\"let me start from the history\"}]}]}"); } catch (Exception e) { throw new RuntimeException(e); }
            }
            if (line.contains("watch?v=")) return new Video.Result(0, "{\"id\":\"Q_ZtgMnPsvw\",\"title\":\"Iaido vs Iaijutsu\",\"channel\":\"Shogo\",\"channel_url\":\"https://www.youtube.com/channel/UCn7\",\"upload_date\":\"20210615\",\"duration\":1343,\"language\":\"en\","
                    + "\"subtitles\":{},\"automatic_captions\":{\"en\":[{\"ext\":\"json3\",\"url\":\"https://www.youtube.com/api/timedtext?v=Q_ZtgMnPsvw\"}]}}");
            return new Video.Result(1, "ERROR: unexpected: " + line);
        };
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", realHome);
        Video.runner = realRunner; Video.fetcher = realFetcher; Video.providerProbe = realProbe; Video.paceMs = Video.PACE_MS; Video.readPaceMs = Video.READ_PACE_MS;
    }

    @Test
    void aSearchForVideosAndOneForChannelsOnALaterPage() throws Exception {
        String v = new VideoSearchTool().execute(J.createObjectNode().put("query", "iaido vs iaijutsu").put("limit", 5));
        assertTrue(v.contains("1. Iaido vs Iaijutsu — Shogo · 22:23 · 224K views\n   https://www.youtube.com/watch?v=Q_ZtgMnPsvw"), v);
        assertTrue(String.join(" ", ran.get(0)).contains("--playlist-start 1 --playlist-end 5 ytsearch5:iaido vs iaijutsu"), ran.get(0).toString());
        String c = new VideoSearchTool().execute(J.createObjectNode().put("query", "iaido").put("kind", "channels").put("limit", 5).put("page", 2));
        assertTrue(c.contains("channels for \"iaido\", page 2") && c.contains("1. Beyond the Sword Iaido — 3 subscribers"), c);
        assertTrue(String.join(" ", ran.get(1)).contains("--playlist-start 6 --playlist-end 10 https://www.youtube.com/results?search_query=iaido&sp=EgIQAg%253D%253D"), "page 2 of YouTube's channel filter: " + ran.get(1));
        assertTrue(new VideoSearchTool().execute(J.createObjectNode().put("query", "")).startsWith("ERROR"));
    }

    @Test
    void aChannelsUploadsComeFromItsFeed() throws Exception {
        String u = new ChannelUploadsTool().execute(J.createObjectNode().put("channel", "UCGqRnq4nN_NLDxp5eQdqA4g"));
        assertTrue(u.startsWith("uploads of IAIDO Archives (https://www.youtube.com/channel/UCGqRnq4nN_NLDxp5eQdqA4g), newest first; last upload 2025-06-14 ("), u);
        assertTrue(u.contains("1. Okuiai 11 · uploaded 2025-06-14 (") && u.contains("5,195 views\n   https://www.youtube.com/watch?v=9RJGjAkFnag"), u);
        assertEquals(List.of("https://www.youtube.com/feeds/videos.xml?channel_id=UCGqRnq4nN_NLDxp5eQdqA4g"), fetched, "one small fetch, no yt-dlp call");
        assertTrue(ran.isEmpty());
        assertTrue(new ChannelUploadsTool().execute(J.createObjectNode().put("channel", "https://www.youtube.com/watch?v=Q_ZtgMnPsvw")).startsWith("ERROR: not a channel"));
    }

    /** The yt-dlp calls made so far, each as one line (docker commands go through the same stand-in and are not these). */
    List<String> ytCalls() { return ran.stream().filter(c -> c.get(0).endsWith("yt-dlp")).map(c -> String.join(" ", c)).toList(); }

    @Test
    void aVideosDetailsWithATranscriptWindowFromItsCaptions() throws Exception {
        String d = new VideoDetailsTool().execute(J.createObjectNode().put("video", "https://www.youtube.com/watch?v=Q_ZtgMnPsvw").put("transcript", true).put("from_seconds", 90).put("to_seconds", 120));
        assertTrue(d.contains("title: Iaido vs Iaijutsu\naddress: https://www.youtube.com/watch?v=Q_ZtgMnPsvw\n"), d);
        assertTrue(d.contains("transcript (en):\n(from YouTube's automatic captions; each line starts at the moment shown; cite a moment as https://www.youtube.com/watch?v=Q_ZtgMnPsvw&t=<seconds>s)\n[1:40] let me start from the history\n"), d);
        // one call: the details and the captions together, written into the caption folder; no fetch of the track's address
        String first = ytCalls().get(0);
        assertTrue(first.contains("--skip-download --dump-single-json --no-simulate --write-subs --write-auto-subs --sub-format json3 --sub-langs en,en-orig,.*-orig -o ") && first.endsWith("%(id)s https://www.youtube.com/watch?v=Q_ZtgMnPsvw"), first);
        assertTrue(fetched.isEmpty(), "the captions came with the details: " + fetched);
        assertEquals(1, Video.captionsHeld()); assertNotNull(Video.keptCaption("Q_ZtgMnPsvw", "en"));
        String status = Video.status();
        assertTrue(status.contains("captions kept: 1 track ("), status);
        // the same video again, the language named: the track kept is read, and the call asks for no captions
        String again = new VideoDetailsTool().execute(J.createObjectNode().put("video", "Q_ZtgMnPsvw").put("transcript", true).put("language", "en"));
        assertTrue(again.contains("[1:40] let me start from the history"), again);
        String second = ytCalls().get(1);
        assertFalse(second.contains("--write-auto-subs"), "kept: not asked for again: " + second);
        assertTrue(fetched.isEmpty());
        // a language the video does not have is said
        String none = new VideoDetailsTool().execute(J.createObjectNode().put("video", "Q_ZtgMnPsvw").put("transcript", true).put("language", "de"));
        assertTrue(ytCalls().get(2).contains("--sub-langs de,de-orig"), ytCalls().get(2));
        assertTrue(none.contains("no captions in de; captions exist in en"), "a language the video does not have is said: " + none);
        // when the call writes no track, the track's address is fetched through the tunnel as before, and kept
        String fb = new VideoDetailsTool().execute(J.createObjectNode().put("video", "Fallback0id").put("transcript", true));
        assertTrue(fb.contains("[1:40] let me start from the history"), fb);
        assertEquals(List.of("https://www.youtube.com/api/timedtext?v=Fallback0id"), fetched, "the second try");
        assertNotNull(Video.keptCaption("Fallback0id", "en")); assertEquals(2, Video.captionsHeld());
        assertTrue(new VideoDetailsTool().execute(J.createObjectNode().put("video", "not a video")).startsWith("ERROR: not a YouTube video"));
    }

    @Test
    void aChannelsWholeListAndTheUploadsAboutASubject() throws Exception {
        String all = new ChannelUploadsTool().execute(J.createObjectNode().put("channel", "UCGqRnq4nN_NLDxp5eQdqA4g").put("all", true));
        assertTrue(all.startsWith("uploads of IAIDO Archives (https://www.youtube.com/channel/UCGqRnq4nN_NLDxp5eQdqA4g), newest first, all 5 — "), all);
        assertTrue(all.contains("5. A walk to the dojo · 2:00\n"), all);
        String call = ytCalls().get(0);
        assertTrue(call.endsWith("--flat-playlist --dump-single-json https://www.youtube.com/channel/UCGqRnq4nN_NLDxp5eQdqA4g/videos") && !call.contains("--playlist-end"), "the whole list in one call: " + call);
        assertTrue(fetched.isEmpty(), "no feed");
        String some = new ChannelUploadsTool().execute(J.createObjectNode().put("channel", "@IaidoArchives").put("match", "okuiai"));
        assertTrue(some.contains(", 2 of 5 titles carry \"okuiai\" — "), some);
        assertTrue(some.contains("1. Okuiai 11") && some.contains("2. Okuiai 10") && !some.contains("Mae, the first form"), some);
        assertTrue(ytCalls().get(1).contains("--playlist-end 1 --dump-single-json https://www.youtube.com/@IaidoArchives/videos"), "the handle resolved first: " + ytCalls().get(1));
        String pattern = new ChannelUploadsTool().execute(J.createObjectNode().put("channel", "UCGqRnq4nN_NLDxp5eQdqA4g").put("match", "/mae|seiza/").put("limit", 1));
        assertTrue(pattern.contains(", 2 of 5 titles carry \"/mae|seiza/\", the first 1 shown — ") && pattern.contains("1. Mae, the first form") && !pattern.contains("Seiza"), pattern);
        assertTrue(new ChannelUploadsTool().execute(J.createObjectNode().put("channel", "UCGqRnq4nN_NLDxp5eQdqA4g").put("match", "kendo")).contains("none of its 5 titles carries \"kendo\""));
        assertTrue(VideoText.titleMatches("Okuiai 11: Sōgiri", "okuiai sōgiri") && !VideoText.titleMatches("Okuiai 11", "okuiai 12") && VideoText.titleMatches("Mae", "/^ma/"));
    }
}
