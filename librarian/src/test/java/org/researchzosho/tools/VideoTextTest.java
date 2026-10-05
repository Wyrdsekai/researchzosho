package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The shapes the video tools write: addresses and moments, figures with their day, listings, a channel's feed, a video's details, a transcript from captions. */
class VideoTextTest {

    private static final ObjectMapper J = new ObjectMapper();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

    @Test
    void addressesIdsAndMoments() {
        assertEquals("Q_ZtgMnPsvw", VideoText.videoId("https://www.youtube.com/watch?v=Q_ZtgMnPsvw&t=12s"));
        assertEquals("Q_ZtgMnPsvw", VideoText.videoId("https://youtu.be/Q_ZtgMnPsvw"));
        assertEquals("Q_ZtgMnPsvw", VideoText.videoId("Q_ZtgMnPsvw"));
        assertNull(VideoText.videoId("https://www.youtube.com/@LetsAskShogo"));
        assertEquals("https://www.youtube.com/channel/UCn7DCb9ttrcw9h3vh9dfnVw", VideoText.channelUrl("UCn7DCb9ttrcw9h3vh9dfnVw"));
        assertEquals("https://www.youtube.com/@LetsAskShogo", VideoText.channelUrl("@LetsAskShogo"));
        assertEquals("https://www.youtube.com/channel/UCn7DCb9ttrcw9h3vh9dfnVw", VideoText.channelUrl("https://www.youtube.com/channel/UCn7DCb9ttrcw9h3vh9dfnVw/videos"));
        assertNull(VideoText.channelUrl("https://www.youtube.com/watch?v=Q_ZtgMnPsvw"));
        assertEquals("https://www.youtube.com/watch?v=Q_ZtgMnPsvw&t=312s", VideoText.moment("Q_ZtgMnPsvw", 312));
        assertEquals("22:23", VideoText.clock(1343));
        assertEquals("1:01:40", VideoText.clock(3700));
        assertEquals("2021-06-15", VideoText.day("20210615"));
        assertEquals("5 years ago", VideoText.age("2021-06-15", TODAY));
        assertEquals("3 days ago", VideoText.age("2026-09-30", TODAY));
        assertEquals("1.85M", VideoText.count(1_850_000));
        assertEquals("224K", VideoText.count(224_840));
        assertEquals("2,380", VideoText.count(2380));
    }

    @Test
    void aMomentInAVideoAndTheTranscriptAroundIt() {
        assertEquals(312, VideoText.momentSeconds("https://www.youtube.com/watch?v=Q_ZtgMnPsvw&t=312s"));
        assertEquals(312, VideoText.momentSeconds("https://youtu.be/Q_ZtgMnPsvw?t=5m12s"));
        assertEquals(312, VideoText.momentSeconds("https://www.youtube.com/watch?v=Q_ZtgMnPsvw&t=312"));
        assertEquals(-1, VideoText.momentSeconds("https://www.youtube.com/watch?v=Q_ZtgMnPsvw"));
        assertEquals(-1, VideoText.momentSeconds("https://example.org/page?t=312s"), "only a video address names a moment");
        assertEquals("https://www.youtube.com/watch?v=Q_ZtgMnPsvw", VideoText.withoutMoment("https://www.youtube.com/watch?v=Q_ZtgMnPsvw&t=312s"));
        assertEquals("https://youtu.be/Q_ZtgMnPsvw", VideoText.withoutMoment("https://youtu.be/Q_ZtgMnPsvw?t=5m12s"));
        assertEquals("https://example.org/page?t=312s", VideoText.withoutMoment("https://example.org/page?t=312s"));
        String source = "title: Iaido vs Iaijutsu\naddress: https://www.youtube.com/watch?v=Q_ZtgMnPsvw\ntranscript (en, from YouTube's automatic captions):\n"
                + "[0:05] the opening\n[4:50] before the moment\n[5:12] the sword is drawn and cut in one motion\n[5:30] after it\n[9:00] far away\n";
        String window = VideoText.momentWindow(source, 312, 90);
        assertTrue(window.contains("[4:50] before the moment") && window.contains("[5:12] the sword is drawn") && window.contains("[5:30] after it"), window);
        assertFalse(window.contains("[0:05]") || window.contains("[9:00]"), "lines away from the moment are left out: " + window);
        assertTrue(window.startsWith("title: Iaido vs Iaijutsu\n"), "the header stays: " + window);
        assertEquals("plain page text", VideoText.momentWindow("plain page text", 312, 90), "a source without clocks is read whole");
    }

    @Test
    void listingsOfVideosAndOfChannels() throws Exception {
        JsonNode videos = J.readTree("{\"entries\":[{\"id\":\"Q_ZtgMnPsvw\",\"title\":\"Iaido vs Iaijutsu\",\"channel\":\"Shogo\",\"duration\":1343,\"view_count\":224840,\"upload_date\":\"20210615\"},"
                + "{\"url\":\"https://www.youtube.com/watch?v=SG-7TK8JCc0\",\"title\":\"Kenjutsu\",\"uploader\":\"The Dojo\"}]}");
        String v = VideoText.searchLines(videos, false, TODAY);
        assertTrue(v.contains("1. Iaido vs Iaijutsu — Shogo · 22:23 · 224K views · uploaded 2021-06-15 (5 years ago)\n   https://www.youtube.com/watch?v=Q_ZtgMnPsvw\n"), v);
        assertTrue(v.contains("2. Kenjutsu — The Dojo\n   https://www.youtube.com/watch?v=SG-7TK8JCc0\n"), "an entry with only an address still gets its id: " + v);
        JsonNode channels = J.readTree("{\"entries\":[{\"id\":\"UCsf2bCntZg9F-bSmE__j8BQ\",\"title\":\"Beyond the Sword Iaido\",\"channel_follower_count\":3,\"url\":\"https://www.youtube.com/channel/UCsf2bCntZg9F-bSmE__j8BQ\",\"description\":\"Iaido notes from a practitioner in Japan.\"}]}");
        String c = VideoText.searchLines(channels, true, TODAY);
        assertTrue(c.contains("1. Beyond the Sword Iaido — 3 subscribers\n   https://www.youtube.com/channel/UCsf2bCntZg9F-bSmE__j8BQ\n   Iaido notes from a practitioner in Japan.\n"), c);
    }

    @Test
    void aChannelsFeedGivesItsUploadsWithTheirDays() {
        String atom = "<feed><title>IAIDO Archives</title><author><name>IAIDO Archives</name></author>"
                + "<entry><yt:videoId>abcdefghijk</yt:videoId><title>Nukitsuke &amp; noto</title><published>2026-09-30T10:00:00+00:00</published><media:group><media:statistics views=\"1234\"/></media:group></entry>"
                + "<entry><yt:videoId>lmnopqrstuv</yt:videoId><title>Seitei</title><published>2025-01-02T10:00:00+00:00</published></entry></feed>";
        String lines = VideoText.feedLines(atom, TODAY, 15);
        assertTrue(lines.contains("1. Nukitsuke & noto · uploaded 2026-09-30 (3 days ago) · 1,234 views\n   https://www.youtube.com/watch?v=abcdefghijk\n"), lines);
        assertTrue(lines.contains("2. Seitei · uploaded 2025-01-02 (21 months ago)\n"), lines);
        assertEquals(1, VideoText.feedLines(atom, TODAY, 1).split("\n   ").length - 0 > 0 ? VideoText.feedLines(atom, TODAY, 1).split("\\d\\. ").length - 1 : 0, "the limit holds");
        assertArrayEquals(new String[]{"IAIDO Archives", "2026-09-30"}, VideoText.feedHead(atom));
    }

    @Test
    void aVideosDetailsAndItsCaptionTrack() throws Exception {
        JsonNode d = J.readTree("{\"id\":\"Q_ZtgMnPsvw\",\"title\":\"Iaido vs Iaijutsu\",\"channel\":\"Shogo\",\"channel_url\":\"https://www.youtube.com/channel/UCn7\",\"channel_follower_count\":1850000,"
                + "\"upload_date\":\"20210615\",\"duration\":1343,\"view_count\":224840,\"like_count\":9981,\"comment_count\":525,\"language\":\"en\","
                + "\"chapters\":[{\"start_time\":0,\"title\":\"Let's START!\",\"end_time\":105},{\"start_time\":105,\"title\":\"The history\",\"end_time\":300}],"
                + "\"description\":\"The All Japan Battodo federation\\nhttp://example.org\",\"subtitles\":{},"
                + "\"automatic_captions\":{\"en\":[{\"ext\":\"json3\",\"url\":\"https://www.youtube.com/api/timedtext?v=Q_ZtgMnPsvw&fmt=json3\"},{\"ext\":\"srv1\",\"url\":\"x\"}],\"ja\":[{\"ext\":\"json3\",\"url\":\"https://www.youtube.com/api/timedtext?v=Q_ZtgMnPsvw&lang=ja\"}]}}");
        String lines = VideoText.detailLines(d, TODAY);
        assertTrue(lines.contains("title: Iaido vs Iaijutsu\naddress: https://www.youtube.com/watch?v=Q_ZtgMnPsvw\nchannel: Shogo (1.85M subscribers) — https://www.youtube.com/channel/UCn7\nuploaded: 2021-06-15 (5 years ago)\nlength: 22:23\n"), lines);
        assertTrue(lines.contains("figures on 2026-10-03: 224K views, 9,981 likes, 525 comments\n"), "figures carry their day: " + lines);
        assertTrue(lines.contains("  1:45  The history  https://www.youtube.com/watch?v=Q_ZtgMnPsvw&t=105s\n"), "chapters are moments: " + lines);
        assertTrue(lines.contains("captions: none by the uploader; automatic in 2 languages\n"), lines);
        assertEquals("https://www.youtube.com/api/timedtext?v=Q_ZtgMnPsvw&fmt=json3", VideoText.captionUrl(d, "en"));
        assertEquals("https://www.youtube.com/api/timedtext?v=Q_ZtgMnPsvw&lang=ja", VideoText.captionUrl(d, "ja"));
        assertNull(VideoText.captionUrl(d, "de"));
        assertEquals(List.of("en", "ja"), VideoText.captionLanguages(d));
    }

    @Test
    void captionsBecomeTimedLinesAndATranscriptComesInWindows() {
        String json3 = "{\"events\":[{\"tStartMs\":0,\"dDurationMs\":1344640,\"id\":1},"
                + "{\"tStartMs\":80,\"dDurationMs\":3119,\"segs\":[{\"utf8\":\"as\"},{\"utf8\":\" you\"},{\"utf8\":\" listen\"}]},"
                + "{\"tStartMs\":2470,\"dDurationMs\":729,\"aAppend\":1,\"segs\":[{\"utf8\":\"\\n\"}]},"
                + "{\"tStartMs\":3200,\"dDurationMs\":2000,\"segs\":[{\"utf8\":\"to my stories\"}]},"
                + "{\"tStartMs\":12500,\"dDurationMs\":2000,\"segs\":[{\"utf8\":\"a new sentence after ten seconds\"}]},"
                + "{\"tStartMs\":20000,\"dDurationMs\":1000,\"segs\":[{\"utf8\":\"after a pause\"}]}]}";
        List<VideoText.Line> lines = VideoText.linesFromJson3(json3);
        assertEquals(3, lines.size(), lines.toString());
        assertEquals(new VideoText.Line(0, "as you listen to my stories"), lines.get(0));
        assertEquals(new VideoText.Line(12, "a new sentence after ten seconds"), lines.get(1));
        assertEquals(new VideoText.Line(20, "after a pause"), lines.get(2));
        String all = VideoText.transcript(lines, 0, -1, 10_000);
        assertEquals("[0:00] as you listen to my stories\n[0:12] a new sentence after ten seconds\n[0:20] after a pause\n", all);
        assertEquals("[0:12] a new sentence after ten seconds\n", VideoText.transcript(lines, 10, 15, 10_000), "a window");
        String cut = VideoText.transcript(lines, 0, -1, 40);
        assertTrue(cut.startsWith("[0:00] as you listen to my stories\n… 2 more lines after this point; ask for the window from 0:00 on."), cut);
    }
}
