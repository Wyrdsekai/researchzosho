package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Function;
/**
 * The instruments of the design (DESIGN_BRAIN.md §7) reachable natively on every surface: the protocol's video op=look, home op=records
 * and career op=board, research with a goal, the three MCP tools, and the pages' forms. Every call here is answered by a stand-in.
 */
class InterfacesTest {
    static final ObjectMapper M = new ObjectMapper();
    final Map<String, String> answers = new HashMap<>(), pages = new HashMap<>(), posts = new HashMap<>();
    final Map<String, List<CareerSources.Hit>> searches = new HashMap<>();
    CareerPortals.Poster posterWas; Function<String, String> wikidataWas;

    static ObjectNode patron() { ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "person").put("runtime", "test"); return a; }

    @BeforeEach void standIns() {
        VideoLook.LOOKER = VideoLook.REAL;
        HomeCensus.KEY_VALUE = () -> null;
        HomeSensors.FETCH = u -> { for (Map.Entry<String, String> e : answers.entrySet()) if (u.contains(e.getKey())) return e.getValue(); return null; };
        HomeSensors.BYTES = u -> null;
        HomeRecords.SEARCH = q -> List.of();
        HomeSensors.MAP_POINT = q -> null;
        Career.DRIVE = () -> null;
        CareerSources.reset(); CareerSources.POLITE_MS = 0;
        CareerSources.RAW = pages::get;
        CareerSources.RAW_POST = (u, j) -> posts.get(u);
        CareerSources.SEARCH = q -> searches.getOrDefault(q, List.of());
        posterWas = CareerPortals.POST_WITH_SESSION; wikidataWas = CareerPortals.WIKIDATA;
        CareerPortals.POST_WITH_SESSION = (page, url, json, headers) -> null;
        CareerPortals.WIKIDATA = u -> u.contains("wbsearchentities") ? "{\"search\":[]}" : null;
    }

    @AfterEach void back() {
        VideoLook.LOOKER = VideoLook.REAL;
        HomeCensus.KEY_VALUE = () -> Config.get(HomeCensus.KEY); HomeSensors.FETCH = CareerSources::get; HomeSensors.BYTES = HomeSensors::fetchBytes; HomeRecords.SEARCH = q -> HomeSources.SEARCH.apply(q); HomeSensors.MAP_POINT = HomeSensors::pointFromMap; Career.DRIVE = Explain::drive;
        CareerSources.RAW = CareerSources::fetch; CareerSources.RAW_POST = CareerSources::post; CareerSources.SEARCH = CareerSources::search; CareerSources.POLITE_MS = 1000; CareerSources.reset();
        CareerPortals.POST_WITH_SESSION = posterWas; CareerPortals.WIKIDATA = wikidataWas;
    }

    @Test void theLookAtAVideoIsAnOp(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        assertSame(VideoLook.REAL, VideoLook.LOOKER, "the op answers through the video field's reading unless a test stands one in");
        VideoLook.LOOKER = (s, video, what, sparse, did) -> { ObjectNode o = M.createObjectNode(); o.put("video", "abc123def45"); o.put("summary", "Found at 0:42: " + what + (sparse ? " (a quick look)" : "")); o.putArray("answers").addObject().put("feature", what).put("verdict", "found").put("seconds", 42); return o; };
        JsonNode looked = p.video(patron().put("op", "look").put("video", "https://www.youtube.com/watch?v=abc123def45").put("what", "a red kettle").put("sparse", true));
        assertTrue(looked.path("available").asBoolean(false));
        assertEquals("Found at 0:42: a red kettle (a quick look)", looked.path("summary").asText());
        assertEquals("found", looked.path("answers").get(0).path("verdict").asText());
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.video(patron().put("op", "look")));
        assertTrue(e.getMessage().startsWith("Give the video's address"), e.getMessage());
        VideoLook.LOOKER = (s, video, what, sparse, did) -> { throw new IllegalArgumentException("Say what you remember of the video, one thing after another."); };
        assertTrue(assertThrows(ProtocolError.class, () -> p.video(patron().put("op", "look").put("video", "https://www.youtube.com/watch?v=abc123def45"))).getMessage().startsWith("Say what you remember"), "the reading's own words reach the caller as invalid_args");
        assertTrue(assertThrows(ProtocolError.class, () -> p.video(patron().put("op", "nonsense"))).getMessage().contains("saw, look, candidates, sift, picture or status"));
    }

    @Test void theRecordsOfAnAddressAreAnOp(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("geocoder/geographies/onelineaddress", "{\"result\": {\"addressMatches\": []}}");
        answers.put("geocoder/geographies/coordinates?x=-70.2574&y=43.6592", "{\"result\": {\"geographies\": {\"Counties\": [{\"NAME\": \"Cumberland County\"}], \"Census Tracts\": [{\"GEOID\": \"23005000300\"}]}}}");
        HomeSensors.MAP_POINT = q -> q.startsWith("389 Congress St") ? new double[]{43.6592, -70.2574} : null;
        LibraryProtocol p = new LibraryProtocol(store);
        JsonNode r = p.home(patron().put("op", "records").put("address", "389 Congress St, Portland, ME"));
        assertEquals("389 Congress St, Portland, ME", r.path("address").asText());
        assertTrue(r.path("place").asText().contains("Portland"), r.path("place").asText());
        assertTrue(r.path("line").asText().startsWith("No records source is known for Portland"), r.path("line").asText());
        assertTrue(r.path("summary").asText().contains("Sales or values on the street: none found."), r.path("summary").asText());
        assertTrue(r.path("census").asText().contains("Census"), "the tract's line says the figures want a key: " + r.path("census").asText());
        assertEquals(0, r.path("sales").size()); assertEquals(0, r.path("permits").size());
        assertTrue(r.path("looked").size() > 0, "what was looked at is given");
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.home(patron().put("op", "records").put("address", "389 Congress St")));
        assertTrue(e.getMessage().startsWith("Give the address as street, city, state"), e.getMessage());
        assertTrue(assertThrows(ProtocolError.class, () -> p.home(patron().put("op", "records"))).getMessage().startsWith("Give the address (address)"));
        // the same address with the zip after the state, and with no comma between city and state
        assertEquals("389 Congress St, Portland, ME 04101", p.home(patron().put("op", "records").put("address", "389 Congress St, Portland, ME 04101")).path("address").asText());
        assertTrue(p.home(patron().put("op", "records").put("address", "389 Congress St, Portland ME")).path("line").asText().startsWith("No records source is known for Portland"));
    }

    static final String PHENOM = "{\"refineSearch\":{\"status\":200,\"hits\":2,\"totalHits\":2,\"data\":{\"jobs\":["
            + "{\"title\":\"Registered Nurse NICU\",\"city\":\"Austin\",\"state\":\"TX\",\"cityState\":\"Austin, TX\",\"postedDate\":\"2026-10-21T21:36:49.000+0000\",\"jobSeqNo\":\"A1\",\"department\":\"Example Medical Center Austin\",\"descriptionTeaser\":\"Nights.\"},"
            + "{\"title\":\"Registered Nurse Labor and Delivery\",\"city\":\"Wichita\",\"state\":\"KS\",\"cityState\":\"Wichita, KS\",\"postedDate\":\"2026-10-14T00:48:03.000+0000\",\"jobSeqNo\":\"A2\",\"descriptionTeaser\":\"\"}]}}}";

    @Test void anEmployersBoardIsAnOp(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        searches.put("Example Health careers jobs", List.of(new CareerSources.Hit("Example Health careers", "https://jobs.examplehealth.example/", "")));
        pages.put("https://jobs.examplehealth.example/", "<html><head><title>Careers at Example Health</title><script src=\"https://pp-cdn.phenompeople.com/x.js\"></script></head><body><h1>Jobs</h1><a href=\"/us/en/search-results\">Search jobs</a></body></html>");
        posts.put("https://jobs.examplehealth.example/widgets", PHENOM);
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = patron().put("op", "board").put("employer", "Example Health"); a.putArray("towns").add("Austin"); a.putArray("words").add("Nurse");
        JsonNode r = p.career(a);
        assertEquals("Example Health", r.path("employer").asText());
        assertEquals("phenom", r.path("board").path("system").asText(), r.toString());
        assertEquals(1, r.path("openings").size(), "the Wichita one is not in Austin: " + r);
        assertEquals("Registered Nurse NICU", r.path("openings").get(0).path("title").asText());
        assertTrue(r.path("summary").asText().startsWith("Example Health's openings come from "), r.path("summary").asText());
        assertTrue(r.path("summary").asText().contains("1 opening in Austin.") && r.path("summary").asText().contains("- Registered Nurse NICU"), r.path("summary").asText());
        JsonNode none = p.career(patron().put("op", "board").put("employer", "Nobody Known"));
        assertTrue(none.path("summary").asText().startsWith("No careers board of Nobody Known's could be found"), none.path("summary").asText());
        assertTrue(assertThrows(ProtocolError.class, () -> p.career(patron().put("op", "board"))).getMessage().startsWith("Give the employer's"));
    }

    @Test void aGoalNamesTheRunsFieldThroughTheGoalsRegistry(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.research(patron().put("question", "Which homes for sale in Cambridge, Massachusetts have three bedrooms and a garden under $1,800,000?").put("goal", "no-such-goal")));
        assertTrue(e.getMessage().startsWith("This library has no goal called \"no-such-goal\". Its goals are: "), e.getMessage());
        assertEquals("home", LibraryProtocol.goalField("home"), "a goal backed by one field names it");
        assertEquals("", LibraryProtocol.goalField("dossier"), "a goal backed by several fields names none: the run's fields choose by the question");
        assertEquals("", LibraryProtocol.goalField(""));
    }

    @Test void theThreeInstrumentsAreMcpToolsAndResearchTakesAGoal() throws Exception {
        JsonNode tools = McpServer.handle("tools/list", M.createObjectNode()).get("tools");
        Set<String> names = new HashSet<>(); JsonNode research = null;
        for (JsonNode t : tools) { names.add(t.get("name").asText()); if (t.get("name").asText().equals("library_research")) research = t; }
        for (String n : List.of("library_look", "library_records", "library_board")) assertTrue(names.contains(n), n);
        assertNotNull(research);
        assertTrue(research.get("inputSchema").get("properties").has("goal"), "library_research takes a goal");
        for (JsonNode t : tools) {
            String n = t.get("name").asText();
            if (n.equals("library_look")) { assertTrue(t.get("inputSchema").get("properties").has("video") && t.get("inputSchema").get("properties").has("patron")); assertTrue(t.get("description").asText().contains("LOOK AT A VIDEO")); }
            if (n.equals("library_records")) assertTrue(t.get("inputSchema").get("properties").has("address"));
            if (n.equals("library_board")) assertTrue(t.get("inputSchema").get("properties").has("employer"));
        }
    }

    static HttpResponse<String> get(HttpClient c, String url) throws Exception { return c.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    static HttpResponse<String> post(HttpClient c, String url, String form) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void thePagesCarryTheFormsAndTheLookAnswersOnItsOwnPage(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            String video = get(c, base + "/video").body();
            assertTrue(video.contains("<h2 id=\"look\">Look at a video</h2>") && video.contains("name=\"op\" value=\"look\""), "the Video page offers the look");
            VideoLook.LOOKER = (s, v, what, sparse, did) -> { ObjectNode o = M.createObjectNode(); o.put("summary", "Looked at the kettle video: " + what + ": found at 0:42"); return o; };
            String looked = post(c, base + "/video", "op=look&video=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dabc123def45&what=a+red+kettle").body();
            assertTrue(looked.contains("Looked at the kettle video: a red kettle: found at 0:42"), looked.substring(0, Math.min(600, looked.length())));
            String homes = get(c, base + "/homes").body();
            assertTrue(homes.contains("<h2 id=\"records\">Records of an address</h2>") && homes.contains("name=\"op\" value=\"records\""), "the Home page offers the records of an address");
            String career = get(c, base + "/career").body();
            assertTrue(career.contains("<h2 id=\"board\">An employer's board</h2>") && career.contains("name=\"op\" value=\"board\""), "the Career page offers an employer's board");
        } finally { d.stop(); }
    }
}
