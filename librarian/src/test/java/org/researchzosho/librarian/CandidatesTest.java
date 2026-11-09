package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.profiles.YoutubeProfile;
import org.researchzosho.mcp.McpServer;
import org.researchzosho.tools.SearchTally;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The candidate pool for a remembered video enumerates and filters by fit, never an engine's top twenty: the sources' union, deduped by
 * id with each source recorded; the machine's filters (the rejected, the dates, the length, the language); the order (the history first,
 * a title that names the thing, a channel's whole list before a ranked search, nearer the window; no count of sources or of appearances
 * moves anything); no popularity anywhere; the channels listed whole in the order they came; the Data API in date order over windows
 * under a cap, counted by the day; the words and the rules naming it. Nothing here touches the net or the box: yt-dlp, the Data API,
 * the web search and the home are all stood in for.
 */
class CandidatesTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    String realHome;
    Video.Runner realRunner;
    BooleanSupplier realInstalled, realProvider;
    long realPace, realReadPace;
    Candidates.Web realWeb;
    Described.Pages realPages;
    Described.Phraser realPhraser;
    long realDescribedPace, realSitePace;
    Candidates.Namer realNamer;
    String realApi;
    Path realTally;
    final List<List<String>> ran = new CopyOnWriteArrayList<>();

    @BeforeEach void standIns() {
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        realTally = SearchTally.override; SearchTally.override = tmp.resolve("tally.json");
        realRunner = Video.runner; realInstalled = Video.installedProbe; realProvider = Video.providerProbe; realPace = Video.paceMs; realReadPace = Video.readPaceMs;
        Video.installedProbe = () -> true; Video.providerProbe = () -> true; Video.paceMs = 0; Video.readPaceMs = 0;
        realWeb = Candidates.WEB; Candidates.WEB = q -> null;
        realPages = Described.PAGES; Described.PAGES = url -> null; realPhraser = Described.PHRASER; Described.PHRASER = (phrases, languages) -> null;
        realDescribedPace = Described.paceMs; realSitePace = Described.sitePaceMs; Described.paceMs = 0; Described.sitePaceMs = 0;
        realNamer = Candidates.NAMER; Candidates.NAMER = things -> List.of(new Candidates.Named("앞치마", "ko", false), new Candidates.Named("apron", "en", false), new Candidates.Named("delantal", "es", false));   // the model's names: one already given, two new
        realApi = Candidates.apiBase; Candidates.apiBase = "http://127.0.0.1:1/youtube/v3";   // nothing listens: a call there would fail, and none may be made without a key
    }

    @AfterEach void back() {
        System.setProperty("user.home", realHome); Config.invalidate();
        SearchTally.override = realTally;
        Video.runner = realRunner; Video.installedProbe = realInstalled; Video.providerProbe = realProvider; Video.paceMs = realPace; Video.readPaceMs = realReadPace;
        Candidates.WEB = realWeb; Candidates.NAMER = realNamer; Candidates.apiBase = realApi; Instruments.CANDIDATES = null;
        Described.PAGES = realPages; Described.PHRASER = realPhraser; Described.paceMs = realDescribedPace; Described.sitePaceMs = realSitePace;
    }

    static String entry(String id, String title, String channel, String channelId, int seconds, long views, String uploadDate) {
        ObjectNode e = M.createObjectNode().put("id", id).put("title", title).put("channel", channel).put("channel_id", channelId).put("duration", seconds).put("view_count", views);
        if (!uploadDate.isEmpty()) e.put("upload_date", uploadDate);
        return e.toString();
    }

    static final String A = "UCaaaaaaaaaaaaaaaaaaaaaa", B = "UCbbbbbbbbbbbbbbbbbbbbbb", C = "UCcccccccccccccccccccccc";

    /** The stand-in for yt-dlp: a deep search of forty-five results and a few more, a channel search, and three channels' whole lists. */
    Video.Runner ytdlp() {
        return (cmd, t) -> {
            ran.add(cmd);
            String line = String.join(" ", cmd);
            if (line.contains("ytsearch100:apron tutorial")) {
                List<String> es = new ArrayList<>();
                for (int i = 0; i < 45; i++) es.add(entry(String.format("apron%06d", i), "Apron idea " + i, i % 2 == 0 ? "Alpha sewing" : "Beta crafts", i % 2 == 0 ? A : B, 600 + i, 1_000_000L * (45 - i), ""));
                es.add(entry("sssssssss02", "Linen apron sewing", "Beta crafts", B, 900, 12, ""));
                es.add(entry("sssssssss03", "Something else entirely", "Gamma", "UCgggggggggggggggggggggg", 300, 9_000_000, ""));
                es.add(entry("sssssssss04", "Apron making, rejected already", "Alpha sewing", A, 1200, 99_000_000, ""));
                es.add(entry("sssssssss06", "Apron, three hours long", "Alpha sewing", A, 20_000, 5, ""));
                return new Video.Result(0, "{\"entries\":[" + String.join(",", es) + "]}");
            }
            if (line.contains("ytsearch100:apron ") || line.endsWith("ytsearch100:apron")) {
                return new Video.Result(0, "{\"entries\":[" + entry("apron000000", "Apron idea 0", "Alpha sewing", A, 600, 1, "") + "," + entry("sssssssss05", "An old apron", "Delta", "UCdddddddddddddddddddddd", 500, 7, "20190101") + "]}");
            }
            if (line.contains("sp=EgIQAg")) return new Video.Result(0, "{\"entries\":[{\"id\":\"" + C + "\",\"title\":\"homebox crafts\",\"channel_follower_count\":3,\"url\":\"https://www.youtube.com/channel/" + C + "\"}]}");
            if (line.contains("/channel/" + A + "/videos")) return new Video.Result(0, "{\"channel\":\"Alpha sewing\",\"channel_id\":\"" + A + "\",\"entries\":[" + entry("sssssssss04", "Apron making, rejected already", "", "", 1200, 99_000_000, "") + "," + entry("ccccccccc01", "Half apron for the garden", "", "", 500, 2, "") + "," + entry("xxxxxxxxx01", "A hat", "", "", 400, 3, "") + "]}");
            if (line.contains("/channel/" + C + "/videos")) return new Video.Result(0, "{\"channel\":\"homebox crafts\",\"channel_id\":\"" + C + "\",\"entries\":[" + entry("ccccccccc02", "エプロンの作り方", "", "", 700, 4, "") + "]}");
            if (line.contains("/channel/" + B + "/videos")) return new Video.Result(0, "{\"channel\":\"Beta crafts\",\"channel_id\":\"" + B + "\",\"entries\":[" + entry("bbbbbbbbb01", "Beta's apron", "", "", 100, 1, "") + "]}");
            return new Video.Result(0, "{\"entries\":[]}");
        };
    }

    static final String TAKEOUT = "[{\"header\":\"YouTube\",\"title\":\"Watched Making an apron from a sheet\",\"titleUrl\":\"https://www.youtube.com/watch?v=hhhhhhhhh01\",\"subtitles\":[{\"name\":\"Hale's workshop\",\"url\":\"https://www.youtube.com/channel/UChhhhhhhhhhhhhhhhhhhhhh\"}],\"time\":\"2025-10-02T14:03:11.123Z\",\"products\":[\"YouTube\"]},"
            + "{\"header\":\"YouTube\",\"title\":\"Watched A cat on a sofa\",\"titleUrl\":\"https://www.youtube.com/watch?v=hhhhhhhhh02\",\"time\":\"2025-10-03T09:00:00Z\",\"products\":[\"YouTube\"]},"
            + "{\"header\":\"YouTube\",\"title\":\"Watched a video that has been removed\",\"time\":\"2025-10-04T09:00:00Z\",\"products\":[\"YouTube\"]}]";

    @Test void theSourcesAreMergedDedupedFilteredAndOrderedByFitWithNoPopularityInIt() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path history = tmp.resolve("watch-history.json"); Files.writeString(history, TAKEOUT);
        assertEquals(2, WatchHistory.add(store, history), "two watched videos filed; the removed one has no address");
        Video.runner = ytdlp();
        Candidates.WEB = q -> q.startsWith("site:youtube.com apron") ? "results for \"" + q + "\":\n1. An apron on YouTube\n   https://www.youtube.com/watch?v=wwwwwwwww01  [other]\n2. Linen apron sewing\n   https://www.youtube.com/watch?v=sssssssss02  [other]\n" : null;
        Candidates.Ask ask = Candidates.Ask.of(M.readTree("{\"things\": [\"apron\", \"エプロン\"], \"after\": \"2020-01\", \"before\": \"2025-11\", \"minutes_max\": 60, \"exclude\": [\"https://www.youtube.com/watch?v=sssssssss04\"]}"));
        List<String> progress = new ArrayList<>();
        Candidates.Result r = Candidates.run(store, ask, progress::add, () -> false);
        List<String> ids = r.pool().stream().map(h -> h.id).toList();
        assertEquals("hhhhhhhhh01", ids.get(0), "the person's own history comes first: " + ids);
        assertEquals(ids.size(), Set.copyOf(ids).size(), "every id once: " + ids);
        assertFalse(ids.contains("hhhhhhhhh02"), "a watched video that names no thing is not a candidate");
        assertFalse(ids.contains("sssssssss04"), "the rejected id is dropped"); assertEquals(1, r.excluded());
        assertFalse(ids.contains("sssssssss05"), "uploaded before the window"); assertEquals(1, r.outsideDates());
        assertFalse(ids.contains("sssssssss06"), "longer than the window"); assertEquals(1, r.outsideLength());
        Candidates.Hit s2 = r.pool().stream().filter(h -> h.id.equals("sssssssss02")).findFirst().orElseThrow();
        assertEquals(List.of(Candidates.SEARCH, Candidates.WEB_INDEX), new ArrayList<>(s2.sources), "found by two sources, both recorded");
        assertTrue(ids.indexOf("apron000000") < ids.indexOf("sssssssss02"), "a count of sources moves nothing: the hit two sources found stays where it came, after the one found by one source before it: " + ids);
        assertTrue(ids.indexOf("ccccccccc01") < ids.indexOf("apron000000"), "a channel's whole list outranks a ranked search's hit: " + ids);
        // one channel at a time: Alpha's first (ccccccccc01, from its list), then Gamma's and Beta's firsts, then Alpha's second (apron000000, a search hit), then Beta's second, then Alpha's third
        assertTrue(ids.indexOf("ccccccccc02") < ids.indexOf("apron000000") && ids.indexOf("bbbbbbbbb01") < ids.indexOf("apron000000"), "every other channel's first before Alpha's second: " + ids.subList(0, 8));
        assertTrue(ids.indexOf("apron000000") < ids.indexOf("apron000001") && ids.indexOf("apron000001") < ids.indexOf("apron000002"), "then the seconds in turn, so no channel's catalogue fills the top: " + ids.subList(0, 8));
        assertTrue(ids.indexOf("apron000000") < ids.indexOf("sssssssss03"), "a title that names the thing comes before one that does not: " + ids);
        assertFalse(ids.contains("xxxxxxxxx01"), "a channel's upload that names no thing is not kept from its list");
        assertTrue(ids.contains("wwwwwwwww01"), "the web index's own find is in the pool");
        String text = r.text();
        assertTrue(text.startsWith("Candidates for apron, エプロン: " + r.pool().size() + " in the pool, best fit first, found 1 from your own watch history, "), text);
        assertTrue(text.contains("by deep YouTube searches") && text.contains("by listing channels' uploads whole") && text.contains("by other search indexes"), text);
        assertTrue(text.contains("Left out: 1 already rejected, 1 uploaded outside the dates, 1 outside the length."), text);
        assertTrue(text.contains("No YouTube Data API key is set"), "the skipped source is said in one sentence: " + text);
        assertFalse(text.contains("views") || text.contains("subscribers") || text.contains("likes"), "no popularity anywhere in the text: " + text);
        assertTrue(text.contains("you watched this on 2025-10-02"), text);
        assertTrue(text.contains("エプロンの作り方") && text.contains("title in Japanese") && text.contains("title in Latin"), "the script of each title is recorded: " + text);
        assertTrue(text.contains("Candidates 1 to 40 of " + r.pool().size()) && text.contains("The same call with page 2 shows the next"), text);
        assertTrue(text.contains("The pool is kept under the key " + r.key() + ". Next, sift it: sift with from=\"" + r.key() + "\" and what=the things remembered"), "the key is said and the sift is next: " + text);
        assertTrue(r.key().matches("\\d{4}-\\d{2}-\\d{2}-[0-9a-f]{16}") && Candidates.poolFileByKey(store, r.key()) != null && Candidates.poolFileByKey(store, r.key().substring(11)) != null, "the pool is found by its key, whole or the hash alone: " + r.key());
        // the channels listed whole, in the order they came and never by how often they were seen: the channel of every hit whose title names the
        // thing first, as the hits came, a channel seen once among them, then the channel search's name; a channel whose hit names no thing is not listed
        List<String> listed = ran.stream().map(c -> String.join(" ", c)).filter(l -> l.contains("/videos")).map(l -> l.replaceAll(".*/channel/(UC[A-Za-z0-9_-]{22})/videos.*", "$1")).toList();
        assertEquals(List.of(A, B, "UCdddddddddddddddddddddd", C), listed, "the hits' own channels first, Alpha, Beta and Delta in the order the hits came, since a hit's own channel is evidence that the channel makes the thing; the channel search's name (Gamma's, YouTube's ranking of channels) after them, where the ninth live run's turn-taking left the meant video's channel in the third round, unreached; Alpha and Beta are seen on twenty-odd results each and Delta on one, and no count moves them: " + listed);
        assertFalse(listed.contains("UCgggggggggggggggggggggg"), "a channel whose only hit names no thing is not listed");
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("4 channels' uploads were listed whole, of 4 to list (the 3 channels of hits whose title names the thing first, in the order the hits came, since a hit's own channel is evidence that the channel makes the thing; then the 1 named by the channel search alone, which is YouTube's ranking of channels).")), r.notes().toString());
        assertTrue(ran.stream().anyMatch(c -> String.join(" ", c).contains("--playlist-end 100 ytsearch100:apron tutorial")), "a deep search of a hundred, the program's own phrasing: " + ran.get(0));
        assertTrue(ran.stream().anyMatch(c -> String.join(" ", c).contains("ytsearch100:エプロン 作り方")), "and the Japanese phrasing from the table");
        assertTrue(ran.stream().anyMatch(c -> String.join(" ", c).contains("ytsearch100:앞치마 만들기")) && ran.stream().anyMatch(c -> String.join(" ", c).contains("ytsearch100:delantal tutorial")), "the model's names in other languages are searched too, under the program's own phrasings: " + ran);
        assertTrue(ran.stream().noneMatch(c -> String.join(" ", c).contains("ytsearch100:como hacer delantal")), "a name the model gave gets the plainest two phrasings, not the whole table");
        assertTrue(text.contains("Searched as apron, エプロン, and as the model named the thing in other languages: 앞치마, delantal."), "the names searched under are said, a name already given not twice: " + text);
        assertTrue(ran.stream().noneMatch(c -> String.join(" ", c).contains("nobody speaks") || String.join(" ", c).contains("pocket")), "no feature word is ever searched");
        // page two reads the kept pool and fetches nothing
        int calls = ran.size();
        Candidates.Result two = Candidates.run(store, Candidates.Ask.of(M.readTree("{\"things\": [\"apron\", \"エプロン\"], \"after\": \"2020-01\", \"before\": \"2025-11\", \"minutes_max\": 60, \"page\": 2}")), s -> { }, () -> false);
        assertEquals(calls, ran.size(), "no call for a later page");
        assertEquals(2, two.page());
        assertTrue(two.text().contains("Candidates 41 to " + r.pool().size() + " of " + r.pool().size()), two.text());
        assertNotNull(RawCapture.find(store, "researchzosho://candidates/" + ask.key()), "the whole pool is on the shelves as a capture");
        assertTrue(Files.isRegularFile(Candidates.poolFile(store, ask)), "and kept as data for the next page");
        // the same ask again today, page one, reads the whole pool back: the day's enumeration is the day's, and the Data API's units are too
        Candidates.Result back = Candidates.run(store, ask, s -> { }, () -> false);
        assertEquals(calls, ran.size(), "nothing fetched for a pool kept today");
        assertEquals(ids, back.pool().stream().map(h -> h.id).toList());
        assertTrue(back.text().contains("Read back from the pool kept today for the same ask; nothing was fetched."), back.text());
    }

    @Test void theLanguageKeepsTheTitlesInItsScriptAndNoCountOfAppearancesMovesAChannel() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib6")); store.init();
        Video.runner = ytdlp();
        // no key: the tunnel's Latin reading of a title may be a localisation, so a Latin title whose own title is unknown is kept, and a Japanese one is dropped for Korean
        Candidates.Result r = Candidates.run(store, Candidates.Ask.of(M.readTree("{\"things\": [\"apron\", \"エプロン\"], \"language\": \"ko\"}")), s -> { }, () -> false);
        List<String> ids = r.pool().stream().map(h -> h.id).toList();
        assertFalse(ids.contains("ccccccccc02"), "a Japanese title is not a Korean one: " + ids); assertTrue(ids.contains("apron000000"), "a Latin reading with no own title known is kept as unknown");
        assertTrue(r.outsideLanguage() >= 1 && r.text().contains("with a title in another language") && r.text().contains("Titles in Korean were kept, and " + (ids.size()) + " whose own title the library could not read, since a title read through the tunnel may be a localisation."), r.text());
        // with a key, every own title is known, and the filter is exact: ja keeps the one Japanese own title and drops the Latin ones
        Config.set(Candidates.KEY, "test-key-never-shown"); Config.set(Candidates.PAGES, "0"); Config.invalidate();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/youtube/v3/videos", x -> {
            String q = URLDecoder.decode(x.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            ObjectNode body = M.createObjectNode(); ArrayNode items = body.putArray("items");
            for (String id : q.replaceAll(".*id=([^&]*).*", "$1").split(",")) { ObjectNode it = items.addObject(); it.put("id", id); it.putObject("contentDetails").put("duration", "PT9M"); it.putObject("snippet").put("title", id.equals("apron000000") ? "エプロンの作り方（簡単）" : "same " + id).put("publishedAt", "2025-03-03T00:00:00Z").put("channelTitle", "Zeta"); }
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        server.start();
        try {
            Candidates.apiBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/youtube/v3";
            Candidates.Result ja = Candidates.run(store, Candidates.Ask.of(M.readTree("{\"things\": [\"apron\", \"エプロン\"], \"language\": \"Japanese\"}")), s -> { }, () -> false);
            assertEquals(List.of("apron000000"), ja.pool().stream().map(h -> h.id).toList(), "only the Japanese own title is kept: " + ja.text());
            assertTrue(ja.outsideLanguage() > 40 && ja.text().contains(ja.outsideLanguage() + " with a title in another language") && !ja.text().contains("could not read"), ja.text());
            assertEquals("ja", Candidates.languageCode("Japanese")); assertEquals("ja", Candidates.languageCode("ja")); assertEquals("zh", Candidates.languageCode("zh-TW")); assertEquals("", Candidates.languageCode("klingon"));
            assertEquals(Set.of("Japanese", "Chinese"), Candidates.scriptsOf("ja")); assertEquals(Set.of("Latin"), Candidates.scriptsOf("es")); assertEquals(Set.of(), Candidates.scriptsOf(""));
        } finally { server.stop(0); }
        // no count of appearances: a hit found by every source sits where its first source put it, and a channel seen once is listed like one seen twenty times
        Candidates.Hit many = new Candidates.Hit("mmmmmmmmm01"); many.title = "Apron, found everywhere"; many.sources.addAll(List.of(Candidates.SEARCH, Candidates.WEB_INDEX));
        Candidates.Hit one = new Candidates.Hit("ooooooooo01"); one.title = "Apron, found once"; one.sources.add(Candidates.SEARCH);
        assertTrue(Candidates.enumerated(many) == Candidates.enumerated(one) && Candidates.nearness(many, "") == Candidates.nearness(one, ""), "nothing in the order's keys reads the sources' count");
    }

    @Test void theDataApiIsAskedInDateOrderOverWindowsUnderACapAndCountedByTheDay() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib2")); store.init();
        Video.installedProbe = () -> false;   // no helper: only the API and the notes
        Config.set(Candidates.KEY, "test-key-never-shown"); Config.set(Candidates.PAGES, "3"); Config.invalidate();
        List<String> asked = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/youtube/v3/search", x -> {
            String q = URLDecoder.decode(x.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            asked.add("search " + q);
            ObjectNode body = M.createObjectNode().put("nextPageToken", "NEXT" + asked.size());
            ArrayNode items = body.putArray("items");
            for (int i = 0; i < 50; i++) {
                ObjectNode it = items.addObject(); it.putObject("id").put("videoId", String.format("api%08d", asked.size() * 100 + i));
                it.putObject("snippet").put("title", (q.contains("エプロン") ? "エプロン " : "apron ") + i).put("channelTitle", "Epsilon").put("channelId", "UCeeeeeeeeeeeeeeeeeeeeee").put("publishedAt", "2025-08-0" + (1 + i % 9) + "T10:00:00Z");
            }
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        server.createContext("/youtube/v3/videos", x -> {
            String q = URLDecoder.decode(x.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            asked.add("videos " + q);
            ObjectNode body = M.createObjectNode(); ArrayNode items = body.putArray("items");
            for (String id : q.replaceAll(".*id=([^&]*).*", "$1").split(",")) { ObjectNode it = items.addObject(); it.put("id", id); it.putObject("contentDetails").put("duration", "PT12M3S"); it.putObject("snippet").put("title", id.startsWith("api000002") ? "エプロン " + id : "apron " + id).put("publishedAt", "2025-08-09T10:00:00Z").put("channelTitle", "Epsilon"); }
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        server.start();
        try {
            Candidates.apiBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/youtube/v3";
            Candidates.Ask ask = Candidates.Ask.of(M.readTree("{\"things\": [\"apron\", \"エプロン\"], \"after\": \"2024-01\", \"before\": \"2025-11\"}"));
            Candidates.Result r = Candidates.run(store, ask, s -> { }, () -> false);
            List<String> searches = asked.stream().filter(a -> a.startsWith("search ")).toList();
            assertEquals(3, searches.size(), "the run's cap of three pages: " + asked);
            assertTrue(searches.get(0).contains("order=date") && searches.get(0).contains("type=video") && searches.get(0).contains("maxResults=50"), searches.get(0));
            assertTrue(searches.get(0).contains("regionCode=US") && searches.get(0).contains("relevanceLanguage=en") && searches.get(0).contains("q=apron tutorial"), "the English thing in the US: " + searches.get(0));
            assertTrue(searches.get(0).contains("publishedAfter=2025-06-01T00:00:00Z") && searches.get(0).contains("publishedBefore=2025-11-30T23:59:59Z"), "the newest half-year window first: " + searches.get(0));
            assertTrue(searches.stream().anyMatch(s -> s.contains("regionCode=JP") && s.contains("relevanceLanguage=ja") && s.contains("q=エプロン 作り方")), "the Japanese thing in Japan: " + searches);
            assertTrue(searches.stream().noneMatch(s -> s.contains("pageToken=")), "breadth before depth: the windows' first pages before any second page");
            assertTrue(searches.get(2).contains("regionCode=KR") && searches.get(2).contains("q=앞치마 만들기"), "the newest window in every language, the model's names among them, before the next window: " + searches.get(2));
            assertEquals(150, r.bySource().get(Candidates.API), "fifty a page, three pages");
            assertEquals(150, r.pool().size());
            assertEquals(723, r.pool().get(0).seconds, "the lengths came from videos.list");
            assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("The day, the length and the own title of 150 videos came from the Data API in 3 calls")), r.notes().toString());
            assertEquals("2025-08-09", r.pool().get(0).uploaded, "nearest the end of the person's window first");
            assertEquals(3 * Candidates.PAGE_UNITS + 3 * Candidates.LIST_UNITS, SearchTally.usedToday(Candidates.TALLY), "three pages at a hundred units and three list calls at one, counted under the day");
            assertEquals(6, SearchTally.thisMonth().get(Candidates.TALLY)[0], "and in the month's calls");
            String text = r.text();
            assertTrue(text.contains("3 pages of the Data API (300 of the day's 10000 units used"), "the pages' note is written before the list calls: " + text);
            assertTrue(text.contains("the run's 3 pages were used with windows still unread (" + Candidates.PAGES + " raises it)"), text);
            assertFalse(text.contains("test-key-never-shown"), "the key is never in the text");
            assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("The video helper is not installed")), r.notes().toString());
            assertTrue(text.contains("title in Japanese") && text.contains("found by date-api"), text);
        } finally { server.stop(0); }
    }

    @Test void everyHitsDayLengthAndOwnTitleComeFromTheDataApiSoTheFiltersApplyToSearchHitsToo() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib5")); store.init();
        Video.runner = ytdlp();
        Config.set(Candidates.KEY, "test-key-never-shown"); Config.set(Candidates.PAGES, "0"); Config.invalidate();   // no search pages: the list calls alone
        List<String> asked = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/youtube/v3/videos", x -> {
            String q = URLDecoder.decode(x.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            asked.add(q);
            ObjectNode body = M.createObjectNode(); ArrayNode items = body.putArray("items");
            for (String id : q.replaceAll(".*id=([^&]*).*", "$1").split(",")) {
                ObjectNode it = items.addObject(); it.put("id", id); it.putObject("contentDetails").put("duration", id.equals("sssssssss02") ? "PT2H" : "PT9M");
                it.putObject("snippet").put("title", id.equals("apron000000") ? "エプロンの作り方（簡単）" : "same " + id).put("publishedAt", id.equals("ccccccccc01") ? "2019-05-05T00:00:00Z" : "2025-03-03T00:00:00Z").put("channelTitle", "Zeta");
            }
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        server.start();
        try {
            Candidates.apiBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/youtube/v3";
            Candidates.Result r = Candidates.run(store, Candidates.Ask.of(M.readTree("{\"things\": [\"apron\"], \"after\": \"2020-01\", \"minutes_max\": 60}")), s -> { }, () -> false);
            assertTrue(asked.stream().allMatch(q -> q.contains("part=contentDetails,snippet")), asked.toString());
            List<String> ids = r.pool().stream().map(h -> h.id).toList();
            assertFalse(ids.contains("ccccccccc01"), "a channel's hit, undated from the listing, got its day from the API and fell outside the window: " + ids);
            assertTrue(ids.contains("sssssssss02") && r.pool().stream().filter(h -> h.id.equals("sssssssss02")).findFirst().orElseThrow().seconds == 900, "a length the listing gave is kept; the API fills only what is missing");
            assertTrue(r.outsideDates() >= 1, "the channel's undated hit, dated by the API, is out: " + r.outsideDates());
            Candidates.Hit a = r.pool().stream().filter(h -> h.id.equals("apron000000")).findFirst().orElseThrow();
            assertEquals("Japanese", a.script(), "the uploader's own title decides the script, not the tunnel's reading of it");
            assertEquals("2025-03-03", a.uploaded); assertEquals(600, a.seconds, "the listing's length is kept; the API's fills only a missing one");
            assertTrue(r.text().contains("\"エプロンの作り方（簡単） (read as: Apron idea 0)\""), "both titles on the line: " + r.text());
            assertTrue(r.text().contains("came from the Data API in"), r.text());
            assertTrue(SearchTally.usedToday(Candidates.TALLY) >= 1 && SearchTally.usedToday(Candidates.TALLY) < 10, "a unit a call");
        } finally { server.stop(0); }
    }

    @Test void theInstrumentsWordsAndTheTablesLiteralExampleAndTheRulesNameCandidates() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib3")); store.init();
        Instrument c = Instruments.candidates(store);
        assertEquals("candidates", c.name());
        assertTrue(c.words().contains("you give the things and what is remembered as the person said it, never a search") && c.words().contains("goes in remembered and never in things") && c.words().contains("no views, no subscribers, no likes"), c.words());
        assertTrue(c.words().contains("what the person remembers of the video itself as short phrases in their own words, one thing each, eight at most") && c.words().contains("found by web searches the program makes from the thing's name and each phrase") && c.words().contains("no engine's rank"), c.words());
        assertTrue(c.schema(M).path("properties").has("remembered") && c.example().contains("\"remembered\": [\"nobody speaks\""), "what is remembered is an argument of its own, with a literal to copy: " + c.example());
        assertTrue(c.words().contains("the person's own watch history before all of these") && c.words().contains("the YouTube Data API in date order"), c.words());
        assertEquals(Instruments.CANDIDATES_EXAMPLE, c.example());
        assertTrue(c.example().contains("\"things\": [\"apron\", \"エプロン\", \"앞치마\"]") && !c.example().contains("<"), "a complete literal call, no placeholder: " + c.example());
        assertTrue(c.schema(M).path("required").toString().contains("things") && c.schema(M).path("properties").has("exclude") && c.schema(M).path("properties").has("before") && c.schema(M).path("properties").has("minutes_max"), c.schema(M).toString());
        List<Instrument> table = Instruments.forChat(store);
        assertTrue(Instruments.isInstrument(table, "candidates"), table.stream().map(Instrument::name).toList().toString());
        String small = new Brain(BrainTest.drive("w"), null, "qwen3.5-9b", null, Brain.Tier.SMALL, null).preamble(table);
        assertTrue(small.contains("candidates " + Instruments.CANDIDATES_EXAMPLE), "the small tier's table shows the literal call: " + small);
        assertTrue(small.contains("candidates {\"things\": [\"apron\", \"エプロン\"], \"remembered\": [\"nobody speaks\", \"a pocket on the front\"]} first, then sift {") && small.contains("then look with the first five survivors in videos"), small);
        String large = new Brain(BrainTest.drive("w"), null, "qwen3.8-27b", null, Brain.Tier.LARGE, null).preamble(table);
        assertTrue(large.contains("candidates: the first move for a remembered video, before any search and before any look") && large.contains("begins with candidates, given the thing's name in each language and what is remembered of the video in the person's own words, never a search"), large);
        assertTrue(YoutubeProfile.FIND_ONE.contains("The candidates come from candidates, never from a search") && Goals.named("choose").decisionRules().contains("begins with candidates"), "the find-one rule and the choose goal name it");
        assertTrue(Goals.named("choose").instruments().contains("candidates"));
        assertTrue(c.asTool().execute(M.readTree("{}")).startsWith("Give the name of the thing made or shown"), "an empty call is told what to give");
        List<String> calls = new ArrayList<>();
        Instruments.CANDIDATES = InstrumentsTest.fake("candidates", calls);
        assertEquals("result of candidates", c.asTool().execute(M.readTree("{\"things\":[\"apron\"]}")));
        assertEquals("candidates {\"things\":[\"apron\"]}", calls.get(0));
    }

    @Test void theProtocolOpTheMcpToolAndTheWatchHistoryShelfAreThere(@TempDir Path other) throws Exception {
        LibraryStore store = new LibraryStore(other.resolve("lib")); store.init();
        Video.runner = ytdlp();
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode args = M.createObjectNode().put("op", "candidates").put("before", "2025-11"); args.putArray("things").add("apron");
        args.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode r = p.video(args);
        assertTrue(r.path("pool").asInt() >= 45, r.toString());
        assertEquals(Candidates.SHOWN, r.path("candidates").size(), "forty a page");
        assertEquals(1, r.path("page").asInt());
        assertTrue(r.path("candidates").get(0).has("link") && r.path("candidates").get(0).has("sources") && r.path("candidates").get(0).has("script"), r.path("candidates").get(0).toString());
        assertTrue(r.path("summary").asText().startsWith("Candidates for apron: "), r.path("summary").asText());
        assertTrue(r.path("key").asText().matches("\\d{4}-\\d{2}-\\d{2}-[0-9a-f]{16}") && !r.path("cut").asBoolean(), "the key the sift takes is in the answer: " + r.path("key"));
        assertEquals(List.of(Candidates.HISTORY, Candidates.SEARCH, Candidates.CHANNEL, Candidates.API, Candidates.WEB_INDEX, Candidates.DESCRIBED), new ArrayList<>(r.path("by_source").properties().stream().map(Map.Entry::getKey).toList()), "every source counted, the ones that answered nothing at zero");
        ObjectNode none = M.createObjectNode().put("op", "candidates"); none.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        assertThrows(ProtocolError.class, () -> p.video(none));
        JsonNode tools = McpServer.handle("tools/list", M.createObjectNode()).get("tools");
        JsonNode cand = null; for (JsonNode t : tools) if (t.get("name").asText().equals("library_candidates")) cand = t;
        assertNotNull(cand, "library_candidates is an MCP tool");
        assertTrue(cand.get("description").asText().startsWith("CANDIDATES FOR A VIDEO YOU REMEMBER") && cand.get("description").asText().contains("never a search"), cand.get("description").asText());
        assertTrue(cand.get("inputSchema").get("required").toString().contains("things") && cand.get("inputSchema").get("properties").has("exclude") && cand.get("inputSchema").get("properties").has("patron"));
        assertTrue(cand.get("inputSchema").get("properties").has("remembered") && !cand.get("inputSchema").get("required").toString().contains("remembered"), "what is remembered is the tool's too, and never required");
        assertTrue(McpServers.ONLY_READS.get("researchzosho").contains("library_candidates"), "a tool that only reads");
        // the watch history: filed by library_add from Takeout's JSON, once; the HTML form reads too
        Path history = other.resolve("watch-history.json"); Files.writeString(history, TAKEOUT);
        ObjectNode add = M.createObjectNode().put("path", history.toString()); add.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode added = p.add(add);
        assertEquals("watch-history", added.path("kind").asText()); assertEquals(2, added.path("added").asInt());
        assertTrue(added.path("summary").asText().startsWith("Filed 2 watched videos from the YouTube watch history. The library holds 2 watched videos watched between 2025-10-02 and 2025-10-03."), added.path("summary").asText());
        assertEquals(0, p.add(add).path("added").asInt(), "the same history added again files nothing new");
        assertNotNull(RawCapture.find(store, "file://" + history.toAbsolutePath().normalize()), "the history is on the shelves as text");
        List<WatchHistory.Watched> hits = WatchHistory.matching(store, List.of("apron"));
        assertEquals(1, hits.size()); assertEquals("hhhhhhhhh01", hits.get(0).id()); assertEquals("Making an apron from a sheet", hits.get(0).title()); assertEquals("Hale's workshop", hits.get(0).channel());
        String html = "<html><body><div class=\"outer-cell\"><div class=\"content-cell\">Watched&nbsp;<a href=\"https://www.youtube.com/watch?v=hhhhhhhhh03\">Apron with pockets &amp; ties</a><br><a href=\"https://www.youtube.com/channel/UCiiiiiiiiiiiiiiiiiiiiii\">Ellis sews</a><br>Nov 2, 2025, 10:15:03 PM EST</div></div></body></html>";
        List<WatchHistory.Watched> fromHtml = WatchHistory.parse(html, "watch-history.html");
        assertEquals(1, fromHtml.size()); assertEquals("hhhhhhhhh03", fromHtml.get(0).id()); assertEquals("Apron with pockets & ties", fromHtml.get(0).title()); assertEquals("Ellis sews", fromHtml.get(0).channel()); assertEquals("2025-11-02", fromHtml.get(0).watchedOn());
        assertEquals("エプロンの作り方", WatchHistory.plainTitle("エプロンの作り方 を視聴しました"));
        assertTrue(WatchHistory.looksLike(history) && !WatchHistory.looksLike(other.resolve("nothing.json")));
    }

    @Test void withoutAModelTheThingIsSearchedUnderTheNamesGivenAloneAndSaysSo() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib4")); store.init();
        Video.installedProbe = () -> false;
        Candidates.NAMER = null;
        Candidates.Result r = Candidates.run(store, Candidates.Ask.of(M.readTree("{\"things\": [\"apron\"]}")), s -> { }, () -> false);
        assertTrue(r.text().contains("Searched as apron (the names given, in their own languages).") && r.text().contains("No model answered to name the thing in other languages"), r.text());
        Candidates.NAMER = things -> { throw new IllegalStateException("the model is away"); };
        r = Candidates.run(store, Candidates.Ask.of(M.readTree("{\"things\": [\"apron\", \"エプロン\"]}")), s -> { }, () -> false);
        assertTrue(r.text().contains("No model answered to name the thing"), "a namer that fails is the same as none: " + r.text());
        // the model's lines are read into names, by the language word or its code, a name already given left out by the caller
        Candidates.drives = () -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return M.createObjectNode(); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return "English: apron\nJapanese: エプロン\nko: 앞치마\nChinese: 围裙\nSpanish: delantal\nPortuguese: avental\nGerman: Schürze\nFrench: tablier\nRussian: фартук\nKlingon: nothing here\n"; }
            @Override public int contextWindow() { return 8192; }
        };
        try {
            List<Candidates.Named> named = Candidates.nameLive(List.of("canvas apron"));
            assertEquals(9, named.size(), named.toString());
            assertEquals(new Candidates.Named("앞치마", "ko", false), named.get(2));
            assertEquals(new Candidates.Named("Schürze", "de", false), named.get(6));
        } finally { Candidates.drives = Explain::drive; }
    }

    @Test void thePhrasingsAreThePlainTableAndATitleNamesAThingInAnyLanguage() {
        assertEquals(List.of("apron tutorial", "apron", "how to make apron", "DIY apron"), Candidates.phrasings("apron"));
        assertEquals(List.of("エプロン 作り方", "エプロンの作り方", "エプロン", "エプロン 作り方 簡単", "エプロン 手作り"), Candidates.phrasings("エプロン"));
        assertEquals(List.of("앞치마 만들기", "앞치마", "앞치마 만드는 법", "앞치마 만드는 방법"), Candidates.phrasings("앞치마"));
        assertEquals(List.of("delantal tutorial", "delantal", "como hacer delantal", "delantal paso a paso"), Candidates.phrasings("delantal", "es"));
        assertEquals(List.of("tablier tutoriel", "tablier"), Candidates.plainest(new Candidates.Named("tablier", "fr", false), 2));
        assertTrue(Candidates.names("Linen Apron Sewing", List.of("apron")) && Candidates.names("簡単エプロンの作り方", List.of("apron", "エプロン")) && Candidates.names("A tote-bag tutorial", List.of("tote bag")));
        assertFalse(Candidates.names("A hat", List.of("apron", "エプロン")));
        assertEquals("Japanese", Candidates.scriptOf("エプロンの作り方")); assertEquals("Korean", Candidates.scriptOf("앞치마 만들기")); assertEquals("Latin", Candidates.scriptOf("Apron tutorial")); assertEquals("Chinese", Candidates.scriptOf("围裙 教程"));
        assertEquals("2025-11-30", Candidates.dayOf("2025-11", true)); assertEquals("2025-11-01", Candidates.dayOf("2025-11", false)); assertEquals("2025-11-05", Candidates.dayOf("2025-11-05", true)); assertEquals("", Candidates.dayOf("last year", true));
        assertEquals(723, Candidates.isoSeconds("PT12M3S")); assertEquals(3600, Candidates.isoSeconds("PT1H")); assertEquals(-1, Candidates.isoSeconds(""));
    }

    /** A page as a blog writes it: the words, and one embedded video. */
    static String blog(String words, String... ids) {
        StringBuilder b = new StringBuilder("<html><head><title>A blog</title></head><body><p>" + words + "</p>");
        for (String id : ids) b.append("<iframe src=\"https://www.youtube.com/embed/").append(id).append("\"></iframe>");
        return b.append("</body></html>").toString();
    }

    @Test void whatIsRememberedFindsVideosThroughThePagesThatDescribeThemAndOrdersThePoolByWhatAMachineFoundHeld() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib7")); store.init();
        Video.Runner base = ytdlp();
        List<String> detailsAsked = new CopyOnWriteArrayList<>();
        List<List<String>> printed = new CopyOnWriteArrayList<>();
        Video.runner = (cmd, t) -> {
            if (!cmd.contains("--print")) return base.run(cmd, t);
            printed.add(cmd);
            // the helper's reading of the videos the pages led to: one line each, with the day and the description
            StringBuilder out = new StringBuilder();
            for (String a : cmd) if (a.startsWith("https://www.youtube.com/watch?v=")) {
                String id = a.substring(a.length() - 11); detailsAsked.add(id);
                if (id.equals("quietvideo1")) out.append("{\"id\":\"quietvideo1\",\"title\":\"A half apron, sewn in silence\",\"description\":\"The measurements are written on screen at the start.\",\"channel\":\"Quiet sewing\",\"channel_id\":\"UCqqqqqqqqqqqqqqqqqqqqqq\",\"duration\":700,\"upload_date\":\"20240102\"}\n");
                if (id.equals("gonevideo01")) out.append("ERROR: [youtube] gonevideo01: Video unavailable. This video has been removed by the uploader\n");
                if (id.equals("oldvideo001")) out.append("{\"id\":\"oldvideo001\",\"title\":\"An apron from long ago\",\"description\":\"\",\"channel\":\"Old sewing\",\"channel_id\":\"UCoooooooooooooooooooooo\",\"duration\":650,\"upload_date\":\"20100505\"}\n");
            }
            return new Video.Result(0, out.toString());
        };
        List<String> asked = new CopyOnWriteArrayList<>();
        Candidates.WEB = q -> {
            asked.add(q);
            if (q.startsWith("site:youtube.com")) return "no results for: " + q;   // the stand-in answers the questions about what is remembered only
            return "results for \"" + q + "\":\n1. A popular haul - YouTube\n   https://www.youtube.com/watch?v=popularvid1  [other]\n   Ten million views.\n"
                    + "2. An old apron page\n   https://old.example/apron  [other]\n   Nobody speaks, and it has a pocket on the front.\n"
                    + "3. The quiet apron\n   https://quiet.example/apron  [other]\n   Nobody speaks; a pocket on the front.\n";
        };
        Described.PAGES = url -> url.startsWith("https://quiet.example") ? blog("Nobody speaks in it. There is a pocket on the front.", "quietvideo1", "gonevideo01")
                : url.startsWith("https://old.example") ? blog("Nobody speaks, and it has a pocket on the front.", "oldvideo001") : null;
        Candidates.Ask ask = Candidates.Ask.of(M.readTree("{\"things\": [\"apron\"], \"remembered\": [\"nobody speaks\", \"a pocket on the front\", \"the measurements are written on screen at the start\", \"linen apron sewing\"], \"after\": \"2020-01\"}"));
        assertEquals(4, ask.remembered().size());
        List<String> progress = new ArrayList<>();
        Candidates.Result r = Candidates.run(store, ask, progress::add, () -> false);
        List<String> ids = r.pool().stream().map(h -> h.id).toList();
        // the video a page describes is first: three of the four remembered phrases, two beside its link and one in its own description
        Candidates.Hit quiet = r.pool().get(0);
        assertEquals("quietvideo1", quiet.id, ids.subList(0, 5).toString());
        assertEquals(3, quiet.fit, "the page gave two, the description read through the helper the third: " + quiet.held);
        assertEquals(Set.of("web: quiet.example"), quiet.sources, "how it was found is the page's site"); assertEquals(Set.of("https://quiet.example/apron"), quiet.pages);
        assertEquals("A half apron, sewn in silence", quiet.title); assertEquals("2024-01-02", quiet.uploaded); assertEquals(700, quiet.seconds); assertEquals("UCqqqqqqqqqqqqqqqqqqqqqq", quiet.channelId);
        // a search hit whose own title holds a remembered phrase rises too, above every title that only names the thing
        assertEquals("sssssssss02", ids.get(1), "\"Linen apron sewing\" holds a phrase by its own title: " + ids.subList(0, 5));
        assertTrue(ids.indexOf("sssssssss02") < ids.indexOf("apron000000"), ids.subList(0, 8).toString());
        // an engine's order moves nothing: its first result holds nothing remembered, and sits with the hits that hold nothing
        assertTrue(ids.contains("popularvid1") && ids.indexOf("popularvid1") > ids.indexOf("apron000000"), "the first result of every search is behind the titles that name the thing: " + ids.indexOf("popularvid1"));
        // the dates rule out a video a page led to, once the helper read its day
        assertFalse(ids.contains("oldvideo001"), "uploaded in 2010, before the window"); assertEquals(2, r.outsideDates(), "that one and the search hit of 2019");
        assertEquals(Set.of("quietvideo1", "oldvideo001", "gonevideo01"), Set.copyOf(detailsAsked), "the videos the pages led to with something remembered beside them are read in one call; the one that holds nothing is not");
        assertFalse(ids.contains("gonevideo01"), "a video YouTube no longer has leaves the pool");
        assertEquals(1, printed.size(), "one call of the helper for all of them");
        assertEquals(4, r.bySource().get(Candidates.DESCRIBED), "four videos through the web's pages and results, the one that is gone among them");
        // the questions were the program's: the thing with each phrase, never a line of the model's
        assertTrue(asked.contains("apron nobody speaks") && asked.contains("nobody speaks a pocket on the front") && asked.contains("linen apron sewing"), asked.toString());
        String text = r.text();
        assertTrue(text.contains("4 through pages that describe a video in the words remembered"), text);
        assertTrue(text.contains("found by web: quiet.example · the page or its own title holds 3 of the things remembered (nobody speaks; a pocket on the front; the measurements are written on screen at the start)"), text);
        assertTrue(text.contains("The web was asked ") && text.contains("The title, the day, the length and the description of 2 of the 3 videos the pages led to with the most of what is remembered were read through the helper. 1 of them is gone from YouTube, and left the pool."), text);
        assertFalse(text.contains("views"), "no popularity in the text, and no snippet of an engine's either: " + text);
        assertTrue(progress.stream().anyMatch(l -> l.startsWith("web \"apron nobody speaks\": 3 results, 1 of them videos")) && progress.stream().anyMatch(l -> l.startsWith("described: 2 of 3 videos' titles")), progress.toString());
        // the kept pool carries what was remembered, each find's fit and pages, and the record of the questions and the pages
        JsonNode keptPool = M.readTree(Candidates.poolFile(store, ask).toFile());
        assertEquals(4, keptPool.path("remembered").size()); assertEquals(3, keptPool.path("pool").get(0).path("fit").asInt()); assertEquals("https://quiet.example/apron", keptPool.path("pool").get(0).path("pages").get(0).asText());
        assertTrue(keptPool.path("described").path("queries").size() >= 7 && keptPool.path("described").path("videos").size() >= 3, keptPool.path("described").toString());
        Candidates.Result back = Candidates.run(store, ask, s -> { }, () -> false);
        assertEquals(ids, back.pool().stream().map(h -> h.id).toList(), "read back, the order by fit stands"); assertEquals(3, back.pool().get(0).fit);
        // another thing remembered is another pool
        assertFalse(ask.key().equals(Candidates.Ask.of(M.readTree("{\"things\": [\"apron\"], \"remembered\": [\"somebody sings\"], \"after\": \"2020-01\"}")).key()));
        // the pool from other people's words alone: the same finds, and YouTube itself not searched
        ran.clear();
        LibraryStore second = new LibraryStore(tmp.resolve("lib7b")); second.init();
        Candidates.Result alone = Candidates.describedAlone(second, Candidates.Ask.of(M.readTree("{\"things\": [\"apron\"], \"remembered\": [\"nobody speaks\", \"a pocket on the front\"]}")), s -> { }, () -> false, 0);
        assertEquals(List.of("oldvideo001", "quietvideo1", "gonevideo01", "popularvid1"), alone.pool().stream().map(h -> h.id).toList(), "the ones a page describes, in the order they came since they hold the same, then the one that holds nothing; without the helper nothing says which is gone");
        assertEquals(0, ran.size(), "no search of YouTube, no channel listed, no helper");
        assertTrue(alone.text().contains("Only the pages that describe the video were asked for"), alone.text());
    }

    @Test void withoutAnythingRememberedOrAWebIndexThePoolIsAsBeforeAndSaysSoInOneSentence() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib8")); store.init();
        Video.runner = ytdlp();
        List<String> asked = new CopyOnWriteArrayList<>();
        Candidates.WEB = q -> { asked.add(q); return null; };
        Candidates.Result plain = Candidates.run(store, Candidates.Ask.of(M.readTree("{\"things\": [\"apron\"]}")), s -> { }, () -> false);
        assertTrue(plain.text().contains("Nothing remembered of the video itself was given (remembered), so no page that describes it was looked for."), plain.text());
        assertTrue(!asked.isEmpty() && asked.stream().allMatch(q -> q.startsWith("site:youtube.com ")), "only the other indexes' view of youtube.com was asked for: " + asked);
        assertEquals(0, plain.bySource().get(Candidates.DESCRIBED)); assertNull(plain.described());
        asked.clear();
        Candidates.Result none = Candidates.run(store, Candidates.Ask.of(M.readTree("{\"things\": [\"apron\"], \"remembered\": [\"nobody speaks\", \"a pocket on the front\"]}")), s -> { }, () -> false);
        assertTrue(none.text().contains("No web search index is set up (a Serper, SerpApi or Brave key, or a SearXNG of your own), so no page that describes the video was looked for."), none.text());
        assertEquals(plain.pool().stream().map(h -> h.id).toList(), none.pool().stream().map(h -> h.id).toList(), "nothing remembered is in any title here, so the pool and its order are the same");
        assertEquals(0, none.bySource().get(Candidates.DESCRIBED));
        // what is remembered is read from an array or from one line, eight phrases at most, each a line's length at most
        Candidates.Ask line = Candidates.Ask.of(M.readTree("{\"things\": \"apron\", \"remembered\": \"nobody speaks; a pocket, on the front | nobody speaks\"}"));
        assertEquals(List.of("nobody speaks", "a pocket, on the front"), line.remembered(), "a comma is part of a phrase");
        ObjectNode many = M.createObjectNode(); many.putArray("things").add("apron"); ArrayNode rs = many.putArray("remembered"); for (int i = 0; i < 12; i++) rs.add("thing " + i + " " + "x".repeat(300));
        Candidates.Ask capped = Candidates.Ask.of(many);
        assertEquals(Described.PHRASES, capped.remembered().size()); assertTrue(capped.remembered().get(0).length() <= 160);
    }

    @Test void thePoolGoesOneChannelAtATimeAndTheChannelsToListAreTakenInTurn() {
        List<Candidates.Hit> in = new ArrayList<>();
        String[][] rows = {{"big00000001", "UCbig"}, {"big00000002", "UCbig"}, {"big00000003", "UCbig"}, {"small0000001", "UCsmall"}, {"lone0000001", ""}, {"big00000004", "UCbig"}, {"small0000002", "UCsmall"}};
        for (String[] r : rows) { Candidates.Hit h = new Candidates.Hit(r[0].substring(0, 11)); h.channelId = r[1]; h.title = "apron " + r[0]; in.add(h); }
        List<String> out = Candidates.inTurn(in, h -> 0).stream().map(h -> h.id).toList();
        assertEquals(List.of("big00000001", "small000000", "lone0000001", "big00000002", "small000000", "big00000003", "big00000004").subList(0, 1), out.subList(0, 1));
        assertEquals("small000000", out.get(1), "the second channel's first before the first channel's second: " + out);
        assertEquals("lone0000001", out.get(2), "a hit with no channel is a channel of its own: " + out);
        assertEquals("big00000002", out.get(3), out.toString());
        assertEquals(7, out.size());
        // buckets keep their order: everything of bucket 0 before any of bucket 1, each in turn
        List<String> two = Candidates.inTurn(in, h -> h.channelId.equals("UCsmall") ? 0 : 1).stream().map(h -> h.id).toList();
        assertTrue(two.get(0).startsWith("small") && two.get(1).startsWith("small") && two.get(2).equals("big00000001"), two.toString());
    }
}

