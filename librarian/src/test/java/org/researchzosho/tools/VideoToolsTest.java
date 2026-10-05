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
        Video.paceMs = 0; // a stand-in for yt-dlp needs no pacing
        Video.fetcher = (url, t) -> { fetched.add(url); return url.contains("feeds/videos.xml")
                ? "<feed><author><name>IAIDO Archives</name></author><entry><yt:videoId>9RJGjAkFnag</yt:videoId><title>Okuiai 11</title><published>2025-06-14T10:00:00+00:00</published><media:group><media:statistics views=\"5195\"/></media:group></entry></feed>"
                : "{\"events\":[{\"tStartMs\":100000,\"dDurationMs\":2000,\"segs\":[{\"utf8\":\"let me start from the history\"}]}]}"; };
        Video.runner = (cmd, t) -> {
            ran.add(cmd);
            String line = String.join(" ", cmd);
            if (line.contains("ytsearch")) return new Video.Result(0, "{\"entries\":[{\"id\":\"Q_ZtgMnPsvw\",\"title\":\"Iaido vs Iaijutsu\",\"channel\":\"Shogo\",\"duration\":1343,\"view_count\":224840}]}");
            if (line.contains("sp=EgIQAg")) return new Video.Result(0, "{\"entries\":[{\"id\":\"UCsf2bCntZg9F-bSmE__j8BQ\",\"title\":\"Beyond the Sword Iaido\",\"channel_follower_count\":3,\"url\":\"https://www.youtube.com/channel/UCsf2bCntZg9F-bSmE__j8BQ\"}]}");
            if (line.contains("watch?v=")) return new Video.Result(0, "{\"id\":\"Q_ZtgMnPsvw\",\"title\":\"Iaido vs Iaijutsu\",\"channel\":\"Shogo\",\"channel_url\":\"https://www.youtube.com/channel/UCn7\",\"upload_date\":\"20210615\",\"duration\":1343,\"language\":\"en\","
                    + "\"subtitles\":{},\"automatic_captions\":{\"en\":[{\"ext\":\"json3\",\"url\":\"https://www.youtube.com/api/timedtext?v=Q_ZtgMnPsvw\"}]}}");
            return new Video.Result(1, "ERROR: unexpected: " + line);
        };
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", realHome);
        Video.runner = realRunner; Video.fetcher = realFetcher; Video.providerProbe = realProbe; Video.paceMs = Video.PACE_MS;
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

    @Test
    void aVideosDetailsWithATranscriptWindowFromItsCaptions() throws Exception {
        String d = new VideoDetailsTool().execute(J.createObjectNode().put("video", "https://www.youtube.com/watch?v=Q_ZtgMnPsvw").put("transcript", true).put("from_seconds", 90).put("to_seconds", 120));
        assertTrue(d.contains("title: Iaido vs Iaijutsu\naddress: https://www.youtube.com/watch?v=Q_ZtgMnPsvw\n"), d);
        assertTrue(d.contains("transcript (en):\n(from YouTube's automatic captions; each line starts at the moment shown; cite a moment as https://www.youtube.com/watch?v=Q_ZtgMnPsvw&t=<seconds>s)\n[1:40] let me start from the history\n"), d);
        assertEquals(List.of("https://www.youtube.com/api/timedtext?v=Q_ZtgMnPsvw"), fetched);
        String none = new VideoDetailsTool().execute(J.createObjectNode().put("video", "Q_ZtgMnPsvw").put("transcript", true).put("language", "de"));
        assertTrue(none.contains("no captions in de; captions exist in en"), "a language the video does not have is said: " + none);
        assertTrue(new VideoDetailsTool().execute(J.createObjectNode().put("video", "not a video")).startsWith("ERROR: not a YouTube video"));
    }
}
