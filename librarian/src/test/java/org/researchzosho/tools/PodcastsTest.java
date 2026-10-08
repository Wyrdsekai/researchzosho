package org.researchzosho.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.RawCapture;
import org.researchzosho.librarian.Video;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Podcasts (0.5.5): the directories, the feed, the transcripts published and made, the budget, and the tool's rendering — against a stub server. */
class PodcastsTest {

    static final ObjectMapper J = new ObjectMapper();
    static HttpServer server; static String base;
    static final List<String> authSeen = new CopyOnWriteArrayList<>();

    static byte[] fixture(String name) {
        try (InputStream in = PodcastsTest.class.getResourceAsStream("/podcasts/" + name)) { assertNotNull(in, name); return in.readAllBytes(); }
        catch (Exception e) { throw new IllegalStateException(name, e); }
    }

    @BeforeAll static void serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", x -> {
            String path = x.getRequestURI().getPath(), query = x.getRequestURI().getRawQuery() == null ? "" : x.getRequestURI().getRawQuery();
            byte[] body; int status = 200; String type = "application/json";
            if (path.startsWith("/api/1.0/")) {
                authSeen.add(x.getRequestHeaders().getFirst("X-Auth-Key") + "|" + x.getRequestHeaders().getFirst("Authorization") + "|" + x.getRequestHeaders().getFirst("X-Auth-Date") + "|" + x.getRequestHeaders().getFirst("User-Agent"));
                if (x.getRequestHeaders().getFirst("Authorization") == null) { status = 401; body = "{\"status\":\"false\"}".getBytes(StandardCharsets.UTF_8); }
                else if (path.endsWith("/search/byterm")) body = fixture("pi-byterm.json");
                else if (path.endsWith("/search/byperson")) body = fixture("pi-byperson.json");
                else if (path.endsWith("/podcasts/byfeedurl")) body = fixture("pi-byfeedurl.json");
                else if (path.endsWith("/episodes/byfeedurl")) body = fixture("pi-episodes.json");
                else if (path.endsWith("/podcasts/trending")) body = fixture("pi-trending.json");
                else { status = 404; body = "{}".getBytes(StandardCharsets.UTF_8); }
            }
            else if (path.equals("/search")) body = fixture("apple-search.json");
            else if (path.equals("/pc20.xml")) { body = fixture("feed-pc20.xml"); type = "application/rss+xml"; }
            else if (path.equals("/captions.srt")) { body = fixture("transcript.srt"); type = "application/srt"; }
            else if (path.equals("/episode.mp3")) { body = new byte[4096]; type = "audio/mpeg"; }
            else if (path.equals("/health")) { body = "ok".getBytes(StandardCharsets.UTF_8); type = "text/plain"; }
            else if (path.equals("/v1/models")) body = ("{\"data\":[{\"id\":\"" + Video.whisperModel() + "\"}]}").getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/v1/audio/transcriptions")) { x.getRequestBody().readAllBytes(); body = "{\"segments\":[{\"start\":0,\"text\":\"The audio came through the redirects.\"}]}".getBytes(StandardCharsets.UTF_8); }
            else if (path.startsWith("/track/dead/")) { body = "gone".getBytes(StandardCharsets.UTF_8); status = 404; type = "text/plain"; }   // a shut-down measurement service: 404 for everything through it
            else if (path.matches("/hop[1-6]\\.mp3")) {   // six hops, one more than a Megaphone show's five: the last is a relative address
                int n = Integer.parseInt(path.substring(4, 5));
                x.getResponseHeaders().add("Location", n < 6 ? base + "/hop" + (n + 1) + ".mp3" : "/episode.mp3"); x.sendResponseHeaders(302, -1); x.close(); return;
            }
            else { status = 404; body = "no".getBytes(StandardCharsets.UTF_8); type = "text/plain"; }
            x.getResponseHeaders().add("Content-Type", type);
            x.sendResponseHeaders(status, body.length); x.getResponseBody().write(body); x.close();
        });
        server.start();
        Podcasts.APPLE = base; Podcasts.INDEX = base + "/api/1.0";
    }

    @AfterAll static void stop() { server.stop(0); Podcasts.APPLE = "https://itunes.apple.com"; Podcasts.INDEX = "https://api.podcastindex.org/api/1.0"; }

    static ObjectNode args(String json) throws Exception { return (ObjectNode) J.readTree(json); }

    /** A feed served by the stub whose transcript and audio point back at the stub. */
    static String feedUrl() { return base + "/pc20.xml"; }

    @Test
    void appleAnswersWithoutAKeyAndTheIndexSignsEveryCall() throws Exception {
        List<Podcasts.Show> apple = Podcasts.apple("iaido kendo", 5);
        assertNotNull(apple); assertFalse(apple.isEmpty());
        assertTrue(apple.get(0).feedUrl().startsWith("http"), apple.get(0).toString());
        assertEquals("Apple", apple.get(0).source());
        // no key: nothing is asked of the index
        assertNull(Podcasts.indexSearch("iaido", 3));
        assertFalse(Podcasts.indexConfigured());
        try (var env = new ConfigKeys(Map.of("RESEARCHZOSHO_PODCASTINDEX_KEY", "ABCDEFGHIJKLMNOPQRST", "RESEARCHZOSHO_PODCASTINDEX_SECRET", "s3cr3t"))) {
            assertTrue(Podcasts.indexConfigured());
            authSeen.clear();
            List<Podcasts.Show> shows = Podcasts.indexSearch("iaido", 3);
            assertNotNull(shows); assertEquals(2, shows.size());
            assertEquals("Podcast Index", shows.get(0).source());
            assertFalse(shows.get(0).categories().isEmpty(), "the index's categories ride on the show");
            String[] auth = authSeen.get(0).split("\\|");
            assertEquals("ABCDEFGHIJKLMNOPQRST", auth[0]);
            assertEquals(40, auth[1].length(), "a SHA-1, hex");
            assertTrue(auth[2].matches("\\d{10}"), "the time, in seconds");
            assertTrue(auth[3].contains("researchzosho.org"), "a User-Agent that names us, which the index requires");
            List<Podcasts.Episode> eps = Podcasts.indexByPerson("Dan Carlin", 3);
            assertNotNull(eps); assertEquals(2, eps.size());
            assertFalse(eps.get(0).feedTitle().isEmpty());
            Podcasts.Show f = Podcasts.indexFeed("https://feeds.podcastindex.org/pc20.xml");
            assertNotNull(f); assertEquals("Podcasting 2.0", f.title()); assertTrue(f.categories().contains("Technology"));
            List<Podcasts.Episode> ie = Podcasts.indexEpisodes("https://feeds.podcastindex.org/pc20.xml", 2);
            assertNotNull(ie); assertFalse(ie.get(0).transcripts().isEmpty(), "transcripts declared in the feed come through the index");
            assertFalse(ie.get(0).people().isEmpty());
        }
    }

    @Test
    void theFeedIsReadForEpisodesTranscriptsAndPeople() {
        var fe = Podcasts.feed(feedUrl(), 5);
        assertNotNull(fe);
        assertEquals("Podcasting 2.0", fe.getKey().title());
        assertFalse(fe.getKey().categories().isEmpty(), fe.getKey().toString());
        List<Podcasts.Episode> eps = fe.getValue();
        assertEquals(2, eps.size());
        Podcasts.Episode e = eps.get(0);
        assertFalse(e.title().isEmpty()); assertTrue(e.audioUrl().startsWith("http"), e.audioUrl()); assertTrue(e.seconds() > 1000, "" + e.seconds());
        assertTrue(e.date().matches("\\d{4}-\\d{2}-\\d{2}"), e.date());
        assertFalse(e.transcripts().isEmpty(), "the podcast:transcript tag"); assertEquals("application/srt", e.transcripts().get(0)[1]);
        assertFalse(e.people().isEmpty(), "the podcast:person tags");
        assertEquals(1, Podcasts.seconds("0:01")); assertEquals(3661, Podcasts.seconds("1:01:01")); assertEquals(90, Podcasts.seconds("90"));
        assertEquals("2026-10-02", Podcasts.date("Fri, 02 Oct 2026 12:00:00 GMT"));
    }

    @Test
    void srtVttJsonAndHtmlTranscriptsBecomeTimedLines() {
        List<VideoText.Line> srt = Podcasts.parseTranscript(new String(fixture("transcript.srt"), StandardCharsets.UTF_8), "application/srt");
        assertFalse(srt.isEmpty());
        assertTrue(srt.get(0).startSeconds() >= 0);
        assertFalse(srt.get(0).text().matches(".*\\d+:\\d+:\\d+.*"), "no cue times in the text: " + srt.get(0).text());
        List<VideoText.Line> vtt = Podcasts.parseTranscript("WEBVTT\n\n00:00.000 --> 00:04.000\nHello there.\n\n01:30.500 --> 01:33.000\nAnd a sentence later.\n", "text/vtt");
        assertEquals(2, vtt.size()); assertEquals(90, vtt.get(1).startSeconds()); assertEquals("And a sentence later.", vtt.get(1).text());
        List<VideoText.Line> json = Podcasts.parseTranscript("{\"version\":\"1.0.0\",\"segments\":[{\"startTime\":5.2,\"endTime\":8,\"body\":\"First words.\"},{\"startTime\":120,\"body\":\"Later words.\"}]}", "application/json");
        assertEquals(2, json.size()); assertEquals(5, json.get(0).startSeconds()); assertEquals(120, json.get(1).startSeconds());
        List<VideoText.Line> html = Podcasts.parseTranscript("<html><body><p>Only prose, no times.</p></body></html>", "text/html");
        assertEquals(1, html.size()); assertEquals("Only prose, no times.", html.get(0).text());
        assertEquals("srt", Podcasts.preferred(List.of(new String[]{"a", "text/html"}, new String[]{"b", "application/srt"})).get(0)[0].equals("b") ? "srt" : "html");
    }

    @Test
    void aPublishedTranscriptIsUsedAndKeptAndTheBudgetGuardsTranscription(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Podcasts.Episode ep = new Podcasts.Episode("Episode 1", "2026-10-02", "", base + "/ep1", base + "/episode.mp3", "audio/mpeg", 5385, "g1", "Show", feedUrl(), List.<String[]>of(new String[]{base + "/captions.srt", "application/srt"}), List.of());
        StringBuilder why = new StringBuilder();
        String srtBody = Archives.text(base + "/captions.srt", java.time.Duration.ofSeconds(5), "x");
        assertNotNull(srtBody, "the stub serves the SRT");
        assertFalse(Podcasts.parseTranscript(srtBody, "application/srt").isEmpty(), "the SRT parses: " + srtBody.substring(0, Math.min(120, srtBody.length())));
        Podcasts.Transcript t = Podcasts.transcript(store, ep, "", new Podcasts.Budget(), why);
        assertNotNull(t, why.toString());
        assertTrue(t.how().startsWith("published"), t.how());
        assertNotNull(RawCapture.find(store, base + "/episode.mp3"), "kept under the audio's address");
        Podcasts.Transcript again = Podcasts.transcript(store, ep, "", new Podcasts.Budget(0, 0, true), why);
        assertNotNull(again); assertEquals("saved in the library", again.how());
        assertEquals(t.lines().size(), again.lines().size());
        // no published transcript, and no transcription server (the setting points at a closed port): the reason says so
        Podcasts.Episode bare = new Podcasts.Episode("Episode 2", "", "", "", base + "/episode2.mp3", "audio/mpeg", 600, "g2", "Show", feedUrl(), List.of(), List.of());
        why.setLength(0);
        try (var env = new ConfigKeys(Map.of("RESEARCHZOSHO_WHISPER", "http://127.0.0.1:9"))) {
            assertEquals("http://127.0.0.1:9", Video.whisperBase(), "the setting names the server");
            assertNull(Podcasts.transcript(store, bare, "", new Podcasts.Budget(), why));
            assertTrue(why.toString().contains("no transcription server"), why.toString());
        }
        // the budget: an episode longer than the minutes left is refused with the reason
        Podcasts.Budget b = new Podcasts.Budget(2, 600, true);
        assertTrue(b.take(1200).contains("RESEARCHZOSHO_TRANSCRIBE_MINUTES"));
        assertEquals("", b.take(300)); assertEquals("", b.take(300)); assertTrue(b.take(10).contains("RESEARCHZOSHO_TRANSCRIBE_EPISODES"));
    }

    @Test
    void theAudioBehindTheHostsRedirectsIsHadAndTheClosestTitleIsFound(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        // the stub is also the transcription server: /health, /v1/models and /v1/audio/transcriptions answer as speaches does
        try (var env = new ConfigKeys(Map.of("RESEARCHZOSHO_WHISPER", base))) {
            Podcasts.Episode hop = new Podcasts.Episode("Behind Redirects", "", "", "", base + "/hop1.mp3", "audio/mpeg", 60, "g9", "Show", feedUrl(), List.of(), List.of());
            StringBuilder why = new StringBuilder();
            Podcasts.Transcript t = Podcasts.transcript(store, hop, "", new Podcasts.Budget(), why);
            assertNotNull(t, "six redirects are followed, the last relative, never 'the audio answered HTTP 302': " + why);
            assertEquals("transcribed by the library's server", t.how());
            assertEquals("The audio came through the redirects.", t.lines().get(0).text());
            assertNotNull(RawCapture.find(store, base + "/hop1.mp3"), "kept under the audio's address as the feed gives it");
        }
        // the audio behind a measurement service that has shut down: the address its path carries is tried
        try (var env = new ConfigKeys(Map.of("RESEARCHZOSHO_WHISPER", base))) {
            String host = base.replaceFirst("^https?://", "");
            Podcasts.Episode dead = new Podcasts.Episode("Behind A Dead Tracker", "", "", "", base + "/track/dead/" + host + "/episode.mp3", "audio/mpeg", 60, "g10", "Show", feedUrl(), List.of(), List.of());
            StringBuilder why = new StringBuilder();
            Podcasts.Transcript t = Podcasts.transcript(store, dead, "", new Podcasts.Budget(), why);
            assertNotNull(t, "the inner address answers: " + why);
            assertEquals(base + "/episode.mp3", Podcasts.innerAddress(base + "/track/dead/" + host + "/episode.mp3"));
            assertEquals("https://traffic.megaphone.fm/ABC.mp3", Podcasts.innerAddress("https://chtbl.com/track/G3DE1/traffic.megaphone.fm/ABC.mp3"));
            assertEquals("https://traffic.megaphone.fm/VMP1.mp3", Podcasts.innerAddress("https://www.podtrac.com/pts/redirect.mp3/pdst.fm/e/pscrb.fm/rss/p/mgln.ai/e/257/traffic.megaphone.fm/VMP1.mp3"), "the last host in a nest");
            assertNull(Podcasts.innerAddress("https://dcs.megaphone.fm/VMP1.mp3"));
        }
        List<Podcasts.Episode> eps = List.of(
                new Podcasts.Episode("Elon's Big Loss, Trump's Stock Trades, OpenAI vs. Apple", "", "", "", "", "", 0, "g1", "", "", List.of(), List.of()),
                new Podcasts.Episode("Is SpaceX Overvalued? Plus: Tesla's Robotaxi", "", "", "", "", "", 0, "g2", "", "", List.of(), List.of()));
        assertEquals("g1", PodcastSearchTool.find(eps, "Elon's Big Loss Trump's Stock Trades OpenAI vs Apple").guid(), "punctuation aside, every word");
        assertEquals("g2", PodcastSearchTool.find(eps, "Is SpaceX Overvalued").guid(), "a title's first words");
        assertEquals("g2", PodcastSearchTool.find(eps, "SpaceX overvalued robotaxi episode").guid(), "most of the words: the closest title");
        assertNull(PodcastSearchTool.find(eps, "something else entirely"), "half the words at least");
    }

    @Test
    void theToolRendersShowsEpisodesAndATranscript(@TempDir Path tmp) throws Exception {
        PodcastSearchTool tool = new PodcastSearchTool();
        String shows = tool.execute(args("{\"kind\":\"shows\",\"query\":\"iaido kendo\"}"));
        assertTrue(shows.contains("feed: http") && shows.contains("[Apple]"), shows);
        assertTrue(shows.contains("podcastindex_key"), "without a key the result says how to add one: " + shows);
        String eps = tool.execute(args("{\"kind\":\"episodes\",\"feed\":\"" + feedUrl() + "\"}"));
        assertTrue(eps.contains("episodes of Podcasting 2.0") && eps.contains("transcript: published") && eps.contains("guid:"), eps);
        String one = tool.execute(args("{\"kind\":\"transcript\",\"feed\":\"" + feedUrl() + "\",\"episode\":\"Advertising Horse\"}"));
        assertTrue(one.contains("transcript: published by the show") || one.contains("no transcript"), one);
        String people = tool.execute(args("{\"kind\":\"people\",\"query\":\"Dan Carlin\"}"));
        assertTrue(people.contains("needs the Podcast Index key"), people);
    }

    /** Settings for the span of a test, put back after. */
    static final class ConfigKeys implements AutoCloseable {
        final Map<String, String> before = new java.util.HashMap<>();
        ConfigKeys(Map<String, String> values) throws Exception { for (var e : values.entrySet()) { before.put(e.getKey(), Config.get(e.getKey())); Config.set(e.getKey(), e.getValue()); } }
        @Override public void close() throws Exception { for (var e : before.entrySet()) Config.set(e.getKey(), e.getValue() == null ? "" : e.getValue()); }
    }
}
