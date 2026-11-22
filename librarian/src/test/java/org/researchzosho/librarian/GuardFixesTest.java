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
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.mcp.McpServer;
import org.researchzosho.tools.ArchiveSearchTool;
import org.researchzosho.tools.Outbound;
import org.researchzosho.tools.Tool;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mechanisms the address guard was changed in after its regression checks (0.8.1), each tried here with other inputs than the
 * checks' own ({@link GuardChecksTest}, {@link GuardChecksOrdinaryUseTest}). Two things are held together in every one of them: what
 * nobody gave is asked for nowhere, and what a person or an honest model ordinarily does is not refused. The site is a listener on
 * the loopback that keeps each address it is asked for, exactly as asked; no test asks a host outside this machine, and every test
 * that expects nothing to arrive first sees something arrive on the same listener.
 */
class GuardFixesTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String CANARY = "QX7731MOOR";

    @TempDir Path tmp;
    String realHome;
    HttpServer site;
    String base, n;
    final List<String> asked = new CopyOnWriteArrayList<>();
    /** Where the site sends a path on to (anything else is an ordinary page). */
    final Map<String, String> sendsOn = new HashMap<>();
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        n = Long.toHexString(System.nanoTime());
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", x -> {
            String target = x.getRequestURI().toString();
            asked.add(target);
            if (sendsOn.containsKey(target)) { x.getResponseHeaders().add("Location", sendsOn.get(target)); x.sendResponseHeaders(302, -1); x.close(); return; }
            byte[] b = ("<html><head><title>A page</title></head><body><p>The ferry ran from 1911 to 1968. This is the page at " + x.getRequestURI().getRawPath() + ".</p></body></html>").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        site.start();
        base = "http://127.0.0.1:" + site.getAddress().getPort();
        GivenAddressesTest.loopback(true);
        ContentJudge.use(new ContentJudge(null, m -> "no"));
        store = new LibraryStore(tmp.resolve("home").resolve("researchzosho-library")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        GivenWords.forget();
    }

    @AfterEach void down() throws Exception {
        ContentJudge.use(null); GivenAddressesTest.loopback(false);
        site.stop(0);
        Outbound.noStandIns();
        GivenWords.forget();
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    static String q(String s) { return M.valueToTree(s).toString(); }
    static JsonNode args(String json) { try { return M.readTree(json); } catch (Exception e) { throw new IllegalStateException(e); } }
    static ObjectNode message(String role, String content) { ObjectNode m = M.createObjectNode(); m.put("role", role); m.put("content", content); m.put("at", 1L); return m; }

    /** A model's message with one call, and the answer to it, as a conversation's file holds them; {@code record}: with what 0.8.1 writes beside an answer, or as earlier versions wrote it. */
    static void exchange(Librarian.Session s, String id, String tool, String argsJson, String answer, Boolean outside, String answersId) throws Exception {
        ObjectNode a = M.createObjectNode(); a.put("role", "assistant"); a.put("content", "");
        ObjectNode c = a.putArray("tool_calls").addObject(); c.put("id", id); c.put("type", "function");
        c.putObject("function").put("name", tool).put("arguments", argsJson);
        s.append(a);
        ObjectNode t = M.createObjectNode(); t.put("role", "tool"); t.put("tool_call_id", answersId); t.put("name", tool); t.put("content", answer);
        if (outside != null) { t.put(GivenAddresses.CALL, 0); t.put(GivenAddresses.OUTSIDE, outside); }
        s.append(t);
    }

    // ---- 1. a saved conversation opened again

    /**
     * A conversation saved by an earlier version holds no record beside its answers. It is read by the order it was written in, so
     * calls that share an id are told apart; and an answer whose id is not its call's gives nothing. With the record, the record
     * decides, whatever the ids say. The control: the page that was opened gives its address in each of them.
     */
    @Test
    void aConversationIsReadByItsRecordAndAnOlderOneByItsOrder() throws Exception {
        String inReport = "https://harbour-" + n + ".example.org/in-a-report?d=" + CANARY, onThePage = "https://harbour-" + n + ".example.org/named-by-the-page";
        String page = "https://harbour-" + n + ".example.org/the-page";
        String shelves = "{\"id\":\"I-0001\",\"text\":\"In 1952. The full record is at " + inReport + ".\"}", opened = "source: " + page + "\n\nThe timetable is at " + onThePage + " now.";
        Map<String, boolean[]> got = new LinkedHashMap<>();
        // every call under one id, as a recovered call had it before 0.8.1; no record
        Librarian.Session old = Librarian.Session.open(store);
        old.append(message("user", "What does " + page + " say, and what do my shelves hold?"));
        exchange(old, "recovered_1", "library_get", "{\"id\":\"I-0001\"}", shelves, null, "recovered_1");
        exchange(old, "recovered_1", "open", "{\"address\":" + q(page) + "}", opened, null, "recovered_1");
        // the same, an answer carrying the id of another call than the one before it: its place is not certain
        Librarian.Session mixed = Librarian.Session.open(store);
        mixed.append(message("user", "What does " + page + " say, and what do my shelves hold?"));
        exchange(mixed, "call_0", "open", "{\"address\":" + q(page) + "}", shelves.replace("in-a-report", "in-a-report-2"), null, "call_7");
        // with the record: the shelves' answer is the shelves', though its id is the id of a call that opened a page
        Librarian.Session now = Librarian.Session.open(store);
        now.append(message("user", "What does " + page + " say, and what do my shelves hold?"));
        exchange(now, "recovered_1", "open", "{\"address\":" + q(page) + "}", shelves.replace("in-a-report", "in-a-report-3"), false, "recovered_1");
        exchange(now, "recovered_1", "open", "{\"address\":" + q(page) + "}", opened.replace("named-by-the-page", "named-by-the-page-3"), true, "recovered_1");
        for (Map.Entry<String, Librarian.Session> e : Map.of("an older conversation, one id for every call", old, "an older one, an answer under another call's id", mixed, "with the record", now).entrySet()) {
            Librarian.Session again = Librarian.Session.resume(store, e.getValue().id);
            new Librarian(store, null, Librarian.person(), again);   // as the web chat makes one for a turn: it works out what the conversation was given
            String suffix = e.getValue() == mixed ? "-2" : e.getValue() == now ? "-3" : "";
            got.put(e.getKey(), new boolean[]{GivenAddresses.given(inReport.replace("in-a-report", "in-a-report" + suffix)), GivenAddresses.given(onThePage + suffix)});
        }
        System.out.println("[fix] 1 a conversation opened again, {the address in the shelves' answer is given, the address the opened page named is given}: " + got.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0] + "/" + e.getValue()[1]).toList());
        assertTrue(got.get("an older conversation, one id for every call")[1] && got.get("with the record")[1], "the page that was opened gives the address it names (the control)");
        for (Map.Entry<String, boolean[]> e : got.entrySet()) assertFalse(e.getValue()[0], e.getKey() + ": an address in an answer of the shelves is given");
        assertFalse(got.get("an older one, an answer under another call's id")[1], "an answer whose place is not certain gives nothing");
    }

    // ---- 2. an address goes out as it was given

    /**
     * The request itself, whatever built it: a client made through the guard, asked during a call for a given address in other
     * spellings than the given one. What the listener is asked for is the given spelling each time; an address given both with and
     * without a slash is asked for as written; and one nobody gave is refused (the control for the rule) while a given one arrives.
     */
    @Test
    void aRequestForAGivenAddressIsMadeInItsGivenSpellingWhateverBuiltIt() throws Exception {
        String given = base + "/Almanac/Tide-Tables-" + n + "?year=1968&port=Kestrel", both = base + "/both-" + n;
        GivenAddresses.received("The tide tables: " + given + " and " + both + " and " + both + "/ (two pages)");
        HttpClient http = Outbound.client(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
        Map<String, String> spellings = new LinkedHashMap<>();
        spellings.put("as given (the control)", given);
        spellings.put("a hyphen as an escape", given.replace("Tide-Tables", "Tide%2DTables"));
        spellings.put("the scheme in capitals", given.replace("http://", "HTTP://"));
        spellings.put("a slash after the path", given.replace("?year", "/?year"));
        spellings.put("an ampersand as an escape", given.replace("&port", "%26port"));
        Map<String, String> arrivedAs = new LinkedHashMap<>();
        try (Outbound.Call c = Outbound.call("a_tool", args("{\"things\":[[{\"where\":" + q(given.replace("Tide-Tables", "Tide%2DTables")) + "}]],\"note\":" + q(base + "/nobody-gave-" + n + "?d=" + CANARY) + "}"))) {
            for (Map.Entry<String, String> s : spellings.entrySet()) {
                asked.clear();
                http.send(HttpRequest.newBuilder(URI.create(s.getValue())).build(), HttpResponse.BodyHandlers.ofString());
                arrivedAs.put(s.getKey(), String.join(" | ", asked));
            }
            asked.clear();
            http.sendAsync(HttpRequest.newBuilder(URI.create(given.replace("Tide-Tables", "Tide%2DTables"))).build(), HttpResponse.BodyHandlers.ofString()).get();
            arrivedAs.put("the same on the path that answers later", String.join(" | ", asked));
            asked.clear();
            http.send(HttpRequest.newBuilder(URI.create(both)).build(), HttpResponse.BodyHandlers.ofString());
            http.send(HttpRequest.newBuilder(URI.create(both + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(List.of("/both-" + n, "/both-" + n + "/"), List.copyOf(asked), "an address given both ways is asked for the way it was written");
            assertThrows(Outbound.NotOpened.class, () -> http.send(HttpRequest.newBuilder(URI.create(base + "/nobody-gave-" + n + "?d=" + CANARY)).build(), HttpResponse.BodyHandlers.ofString()), "an address nobody gave (the control for the rule)");
        }
        System.out.println("[fix] 2 a request for a given address in another spelling, what the listener was asked for: " + arrivedAs);
        for (Map.Entry<String, String> a : arrivedAs.entrySet()) assertEquals("/Almanac/Tide-Tables-" + n + "?year=1968&port=Kestrel", a.getValue(), a.getKey());
        // given with https, asked with http: the request that leaves is the https one, so the plain listener hears nothing
        String secure = "https://127.0.0.1:" + site.getAddress().getPort() + "/secure-" + n;
        GivenAddresses.received("The secure page: " + secure);
        asked.clear();
        try (Outbound.Call c = Outbound.call("a_tool", args("{}"))) {
            assertThrows(Exception.class, () -> http.send(HttpRequest.newBuilder(URI.create(secure.replace("https://", "http://"))).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString()), "the listener speaks no TLS, so the https request fails");
        }
        assertEquals(List.of(), List.copyOf(asked), "an address given with https was asked for by plain http");
    }

    /** The early half, the arguments a tool is handed: deeper places than the check tried, and the values that are left alone. */
    @Test
    void theArgumentsAToolIsHandedHoldEachGivenAddressAsGivenAndLeaveWordsAlone() throws Exception {
        String host = "tide-office-" + n + ".example.org", given = "https://" + host + "/Tables/1968?port=Kestrel";
        GivenAddresses.received("The tide office: " + given);
        GivenAddresses.personSaid("search " + host + " for the ferry");
        String written = given.replace("/Tables", "/%54ables");
        JsonNode deep = GivenAddresses.asGiven(args("{\"batch\":{\"pages\":[{\"from\":[{\"page\":" + q(written) + "}]}]},\"note\":" + q("see " + written + ", then " + written + ".") + "}"));
        assertFalse(deep.toString().contains("%54ables"), "an object in a list in an object, and a sentence: " + deep);
        assertEquals(3, deep.toString().split(Pattern.quote(given), -1).length - 1, deep.toString());
        // a search's words are the model's own and stay as written; so does a site's name, which is no address though the site was given
        JsonNode words = GivenAddresses.asGiven(args("{\"where\":\"web\",\"query\":" + q(written + " ferry") + ",\"site\":" + q(host) + ",\"q\":" + q(written) + "}"));
        assertEquals(args("{\"where\":\"web\",\"query\":" + q(written + " ferry") + ",\"site\":" + q(host) + ",\"q\":" + q(written) + "}"), words, "a search's words and a site's name are left as the model wrote them");
        // an argument the tool says is an address is put back, with its scheme or without, whatever it is called
        assertEquals(given, GivenAddresses.asGiven(args("{\"kind\":\"copy\",\"query\":" + q(host + "/%54ables/1968?port=Kestrel") + "}"), Set.of("query")).path("query").asText());
        assertEquals(given, GivenAddresses.asGiven(args("{\"start_url\":" + q(host + "/%54ables/1968?port=Kestrel") + "}")).path("start_url").asText(), "an argument named as an address");
    }

    // ---- 3 and 5. an argument that is an address handed on

    static void scan(String tool, JsonNode props, Map<String, String> found) {
        for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String d = e.getValue().path("description").asText("");
            if (e.getKey().matches("(?i).*(url|uri|link|href|address|feed).*") || d.matches("(?is).*\\b(urls?|uris?|address(es)?|links?|feeds?|https?)\\b.*")) found.put(tool + "." + e.getKey(), d);
        }
    }

    /**
     * Every argument of every tool that speaks of an address, by its name or by its own description, read from the tools' schemas.
     * Each is either an address the rule holds (by its name in every tool, or by the tool's own declaration) or is listed here as
     * something else, with the reason. A tool added later with such an argument fails this until it says which it is.
     */
    @Test
    void everyArgumentThatSpeaksOfAnAddressIsDeclaredOneOrListedAsSomethingElse() throws Exception {
        Map<String, String> found = new TreeMap<>();
        Map<String, Set<String>> declared = new HashMap<>();
        // the arguments a probe call is made with beside the one probed: the kind for which the archive search takes an address, the op for which a home's address is a street
        ObjectNode probe = M.createObjectNode().put("where", "archives").put("kind", "copy").put("op", "records");
        for (JsonNode t : McpServer.allTools()) scan(t.path("name").asText(), t.path("inputSchema").path("properties"), found);
        for (Instrument i : Instruments.forChat(store)) { scan(i.name(), i.schema(M).path("properties"), found); declared.put(i.name(), i.addressArguments(probe)); }
        List<Tool> run = new ArrayList<>(Researcher.webTools().web(""));
        run.addAll(List.of(new Researcher.PagesTool(store), new Researcher.ShelfSearchTool(store, List.of()), new Researcher.DbQueryTool(store), new Researcher.DbSchemaTool(), new Researcher.Notebook()));
        for (Tool t : run) { scan(t.name(), t.parametersSchema(M).path("properties"), found); declared.put(t.name(), t.addressArguments(probe)); }
        Map<String, String> somethingElse = new TreeMap<>(Map.ofEntries(
                Map.entry("records.address", "a home's street address: words, held to the rule for a street (GivenWords)"),
                Map.entry("library_records.address", "the same"),
                Map.entry("library_home.address", "the same, with op=records"),
                Map.entry("library_sources.address", "an address when written with its scheme, else the words of a search to run again (GivenAddresses.WORDS_UNLESS_WRITTEN_AS_ONE)"),
                Map.entry("library_sources.for", "words"), Map.entry("library_sources.kind", "a word from a list"),
                Map.entry("library_models.drive", "a host's address or a word; stored only on the person's yes, the question naming where requests would go; written with its scheme it is read as every value is"),
                Map.entry("library_images.images", "addresses, file names, shelf entries or the found[] objects of a search: one with its scheme is read as every value is, and this program's own fetcher asks it, where the request is checked"),
                Map.entry("library_images.keep_full", "true or false"),
                Map.entry("library_look.video", "a YouTube link or id: only its eleven-letter id is used, on YouTube"),
                Map.entry("library_sift.videos", "the same"), Map.entry("library_candidates.exclude", "the same"),
                Map.entry("library_follow.which", "a show by its name or its feed, looked up among the shows followed; nothing is asked for"),
                Map.entry("library_read.locator", "the address a saved copy was fetched from: read from the shelves"),
                Map.entry("library_submit.sources", "citations kept with a claim; one with its scheme is read as every value is"),
                Map.entry("library_bookmarks.text", "lines pasted: every address in them is read as every value's is, and each must be given"),
                Map.entry("library_share.mail", "mail addresses for the tunnel's own sign-in"),
                Map.entry("library_mcp.header", "a header's name"), Map.entry("library_mcp.header_key", "a setting's name"), Map.entry("library_mcp.header_value", "a key"), Map.entry("library_mcp.op", "a word from a list"),
                Map.entry("library_add.collection", "a collection's name"), Map.entry("library_add.mode", "a word from a list"),
                Map.entry("library_owned.check", "true or false"), Map.entry("library_owned.op", "a word from a list"),
                Map.entry("library_research.shape", "a word from a list"), Map.entry("library_translate.direction", "the person's words for a translation's voice"),
                Map.entry("forum_search.id", "a thread's id, or its address from which only the id is read"),
                Map.entry("podcast_search.episode", "an episode by words of its title, its guid or its audio address, matched against the feed's own items"),
                Map.entry("software_archive.query", "words of a repository's address or name, sent to the archive's search as words")));
        List<String> undecided = new ArrayList<>();
        for (String k : found.keySet()) {
            String tool = k.substring(0, k.indexOf('.')), arg = k.substring(k.indexOf('.') + 1);
            // held as an address: a value nobody gave, written without its scheme, is refused there
            boolean address = GivenAddresses.notGiven(tool, probe.deepCopy().put(arg, "nobody-" + n + ".example.net/x"), true, false, declared.getOrDefault(tool, Set.of())) != null;
            if (address == somethingElse.containsKey(k)) undecided.add(k + (address ? " is held as an address and listed as something else" : " speaks of an address: declare it (Tool.addressArguments) or list it here with what it is"));
        }
        System.out.println("[fix] 3 arguments that speak of an address, from the tools' schemas: " + found.size() + "; held as addresses: " + found.keySet().stream().filter(k -> !somethingElse.containsKey(k)).toList());
        assertEquals(List.of(), undecided);
        assertTrue(found.containsKey("archive_search.query") && found.containsKey("podcast_search.feed") && found.containsKey("web_fetch.url") && found.size() >= 40, "the schemas were read (the control): " + found.keySet());
        // what each declaration says, by the call
        assertEquals(Set.of("query"), new ArchiveSearchTool().addressArguments(args("{\"kind\":\"history\",\"query\":\"x\"}")));
        assertEquals(Set.of(), new ArchiveSearchTool().addressArguments(args("{\"kind\":\"texts\",\"query\":\"x\"}")), "for the other kinds the query is words");
    }

    /**
     * The rule on such arguments, with no request: an address nobody gave is refused in an argument the tool declares, with its scheme
     * or without, alone or in a list; what was given is let through; and words stay words, a host's name among a search's words too.
     */
    @Test
    void anArgumentThatIsAnAddressIsHeldToTheRuleAndWordsAreNot() throws Exception {
        String host = "pier-records-" + n + ".example.org", given = "https://" + host + "/ledger/1911";
        GivenAddresses.received("The pier's ledger: " + given);
        String nobodys = "collector-" + n + ".example.net/x?d=" + CANARY;
        Set<String> query = Set.of("query");
        Map<String, String> refused = new LinkedHashMap<>(), letThrough = new LinkedHashMap<>();
        refused.put("the archive's history of an address nobody gave, no scheme", GivenAddresses.unopened("archive_search", args("{\"kind\":\"history\",\"query\":" + q(nobodys) + "}"), query));
        refused.put("the same with words after it", GivenAddresses.unopened("archive_search", args("{\"kind\":\"copy\",\"query\":" + q(nobodys + " saved") + "}"), query));
        refused.put("a reader's list, a given one and one nobody gave", GivenAddresses.unopened("docs.read_pages_for_me", args("{\"urls\":[" + q(given) + "," + q(nobodys) + "]}")));
        refused.put("a keeper's server, an argument named start_urls", GivenAddresses.unopened("crawler.crawl", args("{\"start_urls\":[" + q(nobodys) + "]}")));
        refused.put("a keeper's server, address written as a host and a path", GivenAddresses.unopened("reader.read", args("{\"address\":" + q(nobodys) + "}")));
        refused.put("a show's feed, no scheme", GivenAddresses.unopened("podcast_search", args("{\"kind\":\"episodes\",\"feed\":" + q(nobodys) + "}")));
        letThrough.put("the archive's copy of a given page, written without its scheme", GivenAddresses.unopened("archive_search", args("{\"kind\":\"copy\",\"query\":" + q(host + "/ledger/1911") + "}"), query));
        letThrough.put("the archive's texts for words that name a site", GivenAddresses.unopened("archive_search", args("{\"kind\":\"texts\",\"query\":\"example.com ferry ledgers\"}"), Set.of()));
        letThrough.put("the pages an archive holds under a site the model names", GivenAddresses.unopened("archive_search", args("{\"kind\":\"site\",\"query\":\"geocities.example.com/harbour\"}"), Set.of()));
        letThrough.put("a web search whose words hold a site's name", GivenAddresses.unopened("search", args("{\"where\":\"web\",\"query\":\"ferry timetable site:example.com OR example.org/timetables\"}")));
        letThrough.put("a reader's list of given pages", GivenAddresses.unopened("docs.read_pages_for_me", args("{\"urls\":[" + q(given) + "]}")));
        refused.put("a keeper's server, a user before the host in an argument named links", GivenAddresses.unopened("crawler.crawl", args("{\"links\":[" + q("reader@" + nobodys) + "]}")));
        letThrough.put("a keeper's notes server, a locator of its own kind in uri", GivenAddresses.unopened("notes.read_resource", args("{\"uri\":\"notion://page/1a2b3c\"}")));
        letThrough.put("a keeper's server, an id in link", GivenAddresses.unopened("tracker.open", args("{\"link\":\"abc123\",\"page_url\":\"summary.md\"}")));
        letThrough.put("a keeper's map server, a street in address", GivenAddresses.unopened("maps.geocode", args("{\"address\":\"12 Oak Street, Springfield\"}")));
        letThrough.put("a keeper's mail server, a mail address in address", GivenAddresses.unopened("mail.lookup", args("{\"address\":\"ann@example.org\"}")));
        letThrough.put("a saved search told to the library as a source", GivenAddresses.notGiven("library_sources", args("{\"op\":\"add\",\"address\":\"ferry timetables 1968 kestrel\"}"), true, true));
        System.out.println("[fix] 3 and 5 an argument that is an address: refused " + refused.values().stream().filter(v -> v != null).count() + " of " + refused.size() + "; words and given addresses let through " + letThrough.values().stream().filter(v -> v == null).count() + " of " + letThrough.size());
        for (Map.Entry<String, String> e : refused.entrySet()) assertTrue(e.getValue() != null && e.getValue().startsWith("NOT OPENED"), e.getKey() + ": " + e.getValue());
        for (Map.Entry<String, String> e : letThrough.entrySet()) assertNull(e.getValue(), e.getKey());
        assertTrue(GivenAddresses.notGiven("library_sources", args("{\"op\":\"add\",\"address\":" + q("https://" + nobodys) + "}"), true, true) != null, "a source written as an address nobody gave is refused");
    }

    /** In the chat, through the search instrument: the archives are not asked for a made-up page's copy, and are asked for a given one's. */
    @Test
    void theChatsSearchOfTheArchivesHoldsAPagesAddressToTheRule() throws Exception {
        List<String> archive = new CopyOnWriteArrayList<>();
        HttpServer stand = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stand.createContext("/", x -> { archive.add(x.getRequestURI().toString()); byte[] b = "not found".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(404, b.length); x.getResponseBody().write(b); x.close(); });
        stand.start();
        String own = "http://localhost:" + stand.getAddress().getPort();
        Outbound.standIn("localhost:" + stand.getAddress().getPort());
        Map<String, Object> was = new HashMap<>();
        for (String f : List.of("WAYBACK", "ARCHIVE_TODAY")) was.put(f, HostileToolsTest.archive(f, own + "/" + f.toLowerCase()));
        try {
            String page = base + "/the-pier-" + n, made = base + "/made-up-" + n + "?d=" + CANARY;
            List<String> told = chat("Is there a saved copy of " + page + " ?",
                    LibrarianChatTest.tool("search", "{\"where\":\"archives\",\"kind\":\"copy\",\"query\":" + q(page) + "}"),
                    LibrarianChatTest.tool("search", "{\"where\":\"archives\",\"kind\":\"copy\",\"query\":" + q(made) + "}"),
                    LibrarianChatTest.tool("search", "{\"where\":\"archives\",\"kind\":\"history\",\"query\":" + q(made.replace("http://", "")) + "}"));
            System.out.println("[fix] 3 the chat's search of the archives: the stand-in was asked " + archive.size() + " time(s), " + archive.stream().filter(a -> a.contains(CANARY)).count() + " of them holding an address nobody gave; the model was told: " + told.stream().map(t -> t.startsWith("NOT OPENED") ? "not opened" : "asked").toList());
            assertTrue(archive.stream().anyMatch(a -> a.contains("the-pier-" + n)), "the archive is asked for the page the person gave (the control): " + archive + " / " + told);
            assertTrue(told.get(1).startsWith("NOT OPENED") && told.get(2).startsWith("NOT OPENED"), told.toString());
            assertEquals(List.of(), archive.stream().filter(a -> a.contains(CANARY)).toList());
        } finally { for (Map.Entry<String, Object> e : was.entrySet()) HostileToolsTest.archive(e.getKey(), e.getValue()); stand.stop(0); }
    }

    // ---- 6. the records lookup's city and state

    /**
     * Where a city comes from, through the path every answer takes ({@code GivenAddresses.gave}): a search's answer that repeats the
     * words it was asked for gives no city; a page that states the address does, city and state; and the state then holds the call to
     * it. The control: the person's own street with the city the page stated is looked up.
     */
    @Test
    void aCityIsGivenByAPageThatStatesTheAddressAndNotByTheSearchThatAskedForIt() throws Exception {
        GivenAddresses.personSaid("What do the public records say about the harbour office at 5 Quay Road?");
        GivenAddresses.gave(true, null, "results for \"Marrow Lighthouse Keepers\":\n1. Placeholder page\n   https://example.org/a  [web]\n   placeholder text", GivenAddresses.Own.of(args("{\"where\":\"web\",\"query\":\"Marrow Lighthouse Keepers\"}")));
        String afterItsOwnSearch = GivenAddresses.notGiven("records", args("{\"address\":\"5 Quay Rd, Marrow Lighthouse Keepers, ME\"}"), true, false);
        String office = "https://harbour-office-" + n + ".example.org/contact";
        GivenAddresses.received("The harbour office: " + office);
        GivenAddresses.gave(true, null, "source: " + office + "\n\nThe harbour office is at 5 Quay Road, Marrow, Maine 04001. Open on weekdays.", GivenAddresses.Own.of(args("{\"address\":" + q(office) + "}")));
        String fromThePage = GivenAddresses.notGiven("records", args("{\"address\":\"5 Quay Rd, Marrow, ME\"}"), true, false);
        String anotherState = GivenAddresses.notGiven("records", args("{\"address\":\"5 Quay Rd, Marrow, NH\"}"), true, false);
        String stillNot = GivenAddresses.notGiven("records", args("{\"address\":\"5 Quay Rd, Marrow Lighthouse Keepers, ME\"}"), true, false);
        System.out.println("[fix] 6 a city: after the model's own search for it " + (afterItsOwnSearch == null ? "looked up" : "not looked up") + "; after a page stated the address " + (fromThePage == null ? "looked up" : "not looked up")
                + "; the same in another state than the page's " + (anotherState == null ? "looked up" : "not looked up"));
        assertNull(fromThePage, "the address a page stated is looked up (the control)");
        assertTrue(afterItsOwnSearch != null && afterItsOwnSearch.startsWith(GivenWords.NOT_LOOKED_UP) && stillNot != null, "a city only the model's own search words gave: " + afterItsOwnSearch);
        assertTrue(anotherState != null && anotherState.startsWith(GivenWords.NOT_LOOKED_UP + "this address was given in ME"), "a state that contradicts the page's: " + anotherState);
    }

    // ---- 7 and 10. where a request is sent on

    /**
     * On the wire, in the chat: a page the person gave sends the fetch on to another host, and that host on again. Each hop is
     * followed, the last page is read, and the address it was read at is one that came from outside from then on. The other host is
     * this listener under an address of this machine that the guard takes for a host like any other.
     */
    @Test
    void whereAGivenPagesServerSendsTheFetchIsFollowedAndMayBeOpenedAfterwards() throws Exception {
        String other = "http://[::ffff:127.0.0.1]:" + site.getAddress().getPort();
        sendsOn.put("/short-" + n, other + "/long/" + n + "/");
        sendsOn.put("/long/" + n + "/", "/long/" + n + "/page.html?visit=1");
        List<String> told = chat("What does " + base + "/short-" + n + " say?", open(base + "/short-" + n), open(other + "/long/" + n + "/page.html?visit=1"), open(other + "/long/" + n + "/nobody-gave-this?d=" + CANARY));
        System.out.println("[fix] 7 a given page sent on to another host and on again: " + told.stream().map(GuardFixesTest::said).toList() + "; the site was asked " + asked);
        assertEquals("read", said(told.get(0)), told.get(0));
        assertEquals(List.of("/short-" + n, "/long/" + n + "/", "/long/" + n + "/page.html?visit=1"), asked.subList(0, 3), "each hop was asked for as its server wrote it");
        assertEquals("read", said(told.get(1)), "where the fetch was sent is an address from outside, opened by itself afterwards: " + told.get(1));
        assertEquals("not opened", said(told.get(2)), "another page of that host, which nobody gave (the control for the rule)");
        assertTrue(asked.stream().noneMatch(a -> a.contains(CANARY)), asked.toString());
    }

    /**
     * A host of the program's list that sends a request on by what the request holds, in other shapes than the check tried: the
     * target in hexadecimal in the path, and a POST answered "see other". A call's words cannot choose where such a request ends:
     * it is followed within the listed host and nowhere else, on the blocking path and on the one that answers later. The control:
     * the same redirect arrives with no call, and a hop within the listed host arrives during the call.
     */
    @Test
    void aListedHostCannotBeMadeToSendACallsWordsToAnotherHost() throws Exception {
        String elsewhere = "http://[::ffff:127.0.0.1]:" + site.getAddress().getPort();
        HttpServer listed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> inside = new CopyOnWriteArrayList<>();
        listed.createContext("/r/", x -> {
            String hex = x.getRequestURI().getPath().substring(3);
            StringBuilder to = new StringBuilder();
            for (int i = 0; i + 1 < hex.length(); i += 2) to.append((char) Integer.parseInt(hex.substring(i, i + 2), 16));
            x.getRequestBody().readAllBytes();
            x.getResponseHeaders().add("Location", to.toString());
            x.sendResponseHeaders(x.getRequestMethod().equals("POST") ? 303 : 307, -1); x.close();
        });
        listed.createContext("/inside", x -> { inside.add(x.getRequestURI().toString()); byte[] b = "inside".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        listed.start();
        String list = "http://localhost:" + listed.getAddress().getPort();
        Outbound.standIn("localhost:" + listed.getAddress().getPort());
        HttpClient http = Outbound.client(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
        try {
            String out = elsewhere + "/taken-out-" + n + "?d=" + CANARY, in = list + "/inside?n=" + n;
            String toOut = list + "/r/" + hex(out), toIn = list + "/r/" + hex(in);
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(list + "/r/" + hex(elsewhere + "/no-call-" + n))).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(asked.contains("/no-call-" + n), "with no call the redirect arrives (the instrument's own check): " + asked);
            Map<String, String> outcome = new LinkedHashMap<>();
            try (Outbound.Call c = Outbound.call("search", args("{\"where\":\"web\",\"query\":" + q("ferry " + hex(out) + " " + hex(in)) + "}"))) {
                try { http.send(HttpRequest.newBuilder(URI.create(toOut)).build(), HttpResponse.BodyHandlers.ofString()); outcome.put("a GET sent on to another host", "followed"); } catch (Outbound.NotOpened e) { outcome.put("a GET sent on to another host", "refused (" + e.rule() + ")"); }
                try { http.send(HttpRequest.newBuilder(URI.create(toOut)).POST(HttpRequest.BodyPublishers.ofString("d=" + CANARY)).build(), HttpResponse.BodyHandlers.ofString()); outcome.put("a POST answered see other", "followed"); } catch (Outbound.NotOpened e) { outcome.put("a POST answered see other", "refused (" + e.rule() + ")"); }
                try { http.sendAsync(HttpRequest.newBuilder(URI.create(toOut)).build(), HttpResponse.BodyHandlers.ofString()).get(); outcome.put("the same on the path that answers later", "followed"); } catch (ExecutionException e) { outcome.put("the same on the path that answers later", e.getCause() instanceof Outbound.NotOpened no ? "refused (" + no.rule() + ")" : String.valueOf(e.getCause())); }
                assertEquals("inside", http.send(HttpRequest.newBuilder(URI.create(toIn)).build(), HttpResponse.BodyHandlers.ofString()).body(), "a hop within the listed host is followed (the control)");
                assertEquals(3, c.refusals().size(), "each refusal is kept for the trace");
            }
            System.out.println("[fix] 7 a listed host that sends on by what the request holds: " + outcome + "; requests with the canary at the other host: " + asked.stream().filter(a -> a.contains(CANARY)).count());
            for (Map.Entry<String, String> e : outcome.entrySet()) assertEquals("refused (" + Outbound.SENT_ON + ")", e.getValue(), e.getKey());
            assertTrue(asked.stream().noneMatch(a -> a.contains(CANARY)) && inside.size() == 1, asked + " / " + inside);
        } finally { listed.stop(0); }
    }

    static String hex(String s) { StringBuilder b = new StringBuilder(); for (byte x : s.getBytes(StandardCharsets.UTF_8)) b.append(String.format("%02x", x)); return b.toString(); }

    // ---- 10. what the person meant by a name or a number

    /**
     * The forms the library builds an address for, each with a name that was given (opened: the check lets the call through, and
     * what is requested is the address as the program builds it) and with one that was made up, filled with other text, or dressed
     * with more than the form (refused). A name that only a call's own search words hold is not given.
     */
    @Test
    void aNameOrANumberThatWasGivenOpensByItsFormAndAMadeUpOneDoesNot() throws Exception {
        String host = "tide-office-" + n + ".example.org";
        GivenAddresses.personSaid("Read arXiv 2401.12345v2 and hep-th/9901001, the paper 10.1000/xyz-123." + n + ", the ferry-tables-" + n + " repository of harbour-works, and the Japanese Wikipedia article 潮汐. Also https://" + host + ":8443/tables/1968 please.");
        GivenAddresses.gave(true, null, "1. Kestrel Sound (strait) " + n + " - a strait between two islands\n   https://example.org/straits", GivenAddresses.Own.of(args("{\"where\":\"web\",\"query\":\"strait near the harbour\"}")));
        GivenAddresses.gave(true, null, "results for \"2501.00001 door code " + n + "\": nothing was found.", GivenAddresses.Own.of(args("{\"where\":\"web\",\"query\":" + q("2501.00001 door code " + n) + "}")));
        Map<String, String> opened = new LinkedHashMap<>();   // what the model writes → what is requested
        opened.put("https://arxiv.org/abs/2401.12345v2", "https://arxiv.org/abs/2401.12345v2");
        opened.put("https://arxiv.org/abs/2401.12345", "https://arxiv.org/abs/2401.12345v2");                 // the version the person gave
        opened.put("https://export.arxiv.org/pdf/hep-th/9901001.pdf", "https://arxiv.org/pdf/hep-th/9901001");
        opened.put("https://doi.org/10.1000/xyz-123." + n, "https://doi.org/10.1000/xyz-123." + n);
        opened.put("http://dx.doi.org/10.1000/xyz-123." + n, "https://doi.org/10.1000/xyz-123." + n);         // the resolver's own name, with https
        opened.put("https://github.com/harbour-works/ferry-tables-" + n, "https://github.com/harbour-works/ferry-tables-" + n);   // the person's words in another order
        opened.put("https://www.github.com/harbour-works/ferry-tables-" + n + ".git", "https://github.com/harbour-works/ferry-tables-" + n);
        opened.put("https://ja.wikipedia.org/wiki/%E6%BD%AE%E6%B1%90", "https://ja.wikipedia.org/wiki/%E6%BD%AE%E6%B1%90");
        opened.put("https://en.wikipedia.org/wiki/Kestrel_Sound_(strait)_" + n, "https://en.wikipedia.org/wiki/Kestrel_Sound_(strait)_" + n);   // a title a search's answer gave
        opened.put("https://" + host + ":8443/", "https://" + host + ":8443/");
        opened.put("http://" + host + ":8443/robots.txt", "https://" + host + ":8443/robots.txt");            // with the scheme the site was given with
        List<String> refused = List.of(
                "https://arxiv.org/abs/2401.99999",                                              // a number nobody gave
                "https://arxiv.org/abs/2501.00001",                                              // one only the model's own search words held
                "https://arxiv.org/abs/2401.12345v2?d=" + CANARY,                                // the form and more
                "https://doi.org/10.1000/xyz-123." + n + "?urlappend=%2Fx",                     // a query, which the resolver adds to where it sends
                "https://doi.org/10.9999/" + CANARY,
                "https://github.com/harbour-works/tide-" + CANARY,                              // the owner given, the name made up
                "https://github.com/harbour-works/ferry-tables-" + n + "/settings/keys",         // deeper than the repository
                "https://gist.github.com/harbour-works/ferry-tables-" + n,                       // another host than the form's
                "https://en.wikipedia.org/wiki/" + CANARY + "_door_code",                        // a title nobody gave
                "https://en.wikipedia.org/wiki/Special:Search?search=Kestrel_Sound",             // a query
                "https://nobody-" + n + ".example.net/",                                         // the front page of a site of which nothing was given
                "https://nobody-" + n + ".example.net/robots.txt",
                "https://" + host + "/",                                                          // the given site at another port than it was given with
                "https://" + host + ":8443/sitemap.xml");                                        // a page of the site that is neither its front page nor its robots file
        Map<String, String> got = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : opened.entrySet()) {
            JsonNode call = args("{\"address\":" + q(e.getKey()) + "}");
            got.put(e.getKey(), GivenAddresses.notGiven("open", call, true, false) != null ? "NOT OPENED" : GivenAddresses.asGiven(call).path("address").asText());
        }
        List<String> letThrough = new ArrayList<>();
        for (String a : refused) if (GivenAddresses.notGiven("open", args("{\"address\":" + q(a) + "}"), true, false) == null || GivenAddresses.unopened("web_fetch", args("{\"url\":" + q(a) + "}")) == null) letThrough.add(a);
        System.out.println("[fix] 10 a name or a number that was given, by its form: " + got.values().stream().filter(v -> !v.equals("NOT OPENED")).count() + " of " + opened.size() + " opened; made up, filled or dressed: " + (refused.size() - letThrough.size()) + " of " + refused.size() + " refused");
        assertEquals(opened, got, "each is opened, and what is requested is the address as the program builds it");
        assertEquals(List.of(), letThrough, "an address that only looks like one of the forms was let through");
        // and at the request itself: the built address is a given one, the address as a model dressed it is not
        try (Outbound.Call c = Outbound.call("open", args("{\"address\":\"https://doi.org/10.1000/xyz-123." + n + "\"}"))) {
            Outbound.check(URI.create("https://doi.org/10.1000/xyz-123." + n));
        }
        try (Outbound.Call c = Outbound.call("open", args("{\"address\":\"https://doi.org/10.1000/xyz-123." + n + "?urlappend=%2Fx\"}"))) {
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://doi.org/10.1000/xyz-123." + n + "?urlappend=%2Fx")));
        }
    }

    /** A machine typed by its numbers, an address whose host is an IPv6 address, and an address that ends in a full stop where it is a value of its own. */
    @Test
    void aMachineByItsNumbersAnIpv6HostAndAnAddressThatEndsInAFullStopAreGivenAsTheyAre() throws Exception {
        GivenAddresses.personSaid("The media server is at 192.0.2.20:8096/web-" + n + " and the printer at 198.51.100.7/status-" + n + "; release 4.77.3." + (n.hashCode() & 0xff) + " is out, see http://[2001:db8::77]:8080/notes-" + n + " too.");
        assertNull(GivenAddresses.notGiven("open", args("{\"address\":\"192.0.2.20:8096/web-" + n + "\"}"), true, false), "numbers and a port");
        assertNull(GivenAddresses.notGiven("open", args("{\"address\":\"198.51.100.7/status-" + n + "\"}"), true, false), "numbers and a path");
        assertNull(GivenAddresses.notGiven("open", args("{\"address\":\"http://[2001:db8::77]:8080/notes-" + n + "\"}"), true, false), "an IPv6 host");
        // four numbers alone in the person's own words count as a machine they named, a version among them (the decision of 2026-11-21:
        // nothing is asked unless a call names exactly them); what comes after them in the sentence is not part of it
        assertTrue(GivenAddresses.given("https://4.77.3." + (n.hashCode() & 0xff)) && !GivenAddresses.given("https://4.77.3." + (n.hashCode() & 0xff) + "/is"), "four numbers the person wrote, and nothing more than them");
        assertTrue(GivenAddresses.notGiven("open", args("{\"address\":\"192.0.2.20:8096/admin-" + n + "\"}"), true, false) != null, "another page of that machine, which nobody gave");
        // a page's link, where a server sent a request and what a feed names are values of their own: the full stop at the end is the address's
        GivenAddresses.linksOf("<a href=\"/wiki/Marrow,_D.C.\">Marrow</a>", "https://gazetteer-" + n + ".example.org/index");
        assertEquals("https://gazetteer-" + n + ".example.org/wiki/Marrow,_D.C.", GivenAddresses.asRequested("https://gazetteer-" + n + ".example.org/wiki/Marrow,_D.C"), "asked for as the page's link wrote it, however the model ends it");
        Outbound.fromAnswer("https://cdn-" + n + ".example.net/t/ep1.vtt?sig=a.b.");
        assertEquals("https://cdn-" + n + ".example.net/t/ep1.vtt?sig=a.b.", GivenAddresses.asRequested("https://cdn-" + n + ".example.net/t/ep1.vtt?sig=a.b."));
    }

    /**
     * A reading list's lines: a DOI on a line of its own is a DOI. Until 0.8.1 its "10." was taken for the number of a list's tenth
     * item and the rest for a title, so a list of bare DOIs shelved nothing (found by the real-use run of the resolver's rule).
     */
    @Test
    void aDoiOnALineOfItsOwnIsReadAsADoiAndANumberedItemAsAnItem() {
        List<Reading.Entry> entries = Reading.lines("10.1371/journal.pone.0000308\n10. The tenth title\n2) The second title\n- doi:10.5555/given.1 — a note\n3.5 mm plugs, a history\n");
        assertEquals(5, entries.size(), entries.toString());
        assertEquals("https://doi.org/10.1371/journal.pone.0000308", entries.get(0).locator());
        assertTrue(!entries.get(1).located() && entries.get(1).title().equals("The tenth title") && entries.get(2).title().equals("The second title"), entries.toString());
        assertEquals("https://doi.org/10.5555/given.1", entries.get(3).locator());
        assertEquals("3.5 mm plugs, a history", entries.get(4).title(), "a number with a stop and no space after it is the item's own");
    }

    /** One turn of the chat in a conversation of its own: the person's words, then the model makes these calls, one a message. What the library told it for each. */
    List<String> chat(String person, ObjectNode... calls) throws Exception {
        Librarian.Session s = Librarian.Session.open(store);
        Researcher.Drive model = new Researcher.Drive() {
            int round;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode offered, int maxTokens, String toolChoice) {
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
                if (round >= calls.length || "none".equals(toolChoice)) return m.put("content", "Done.");
                m.put("content", "");
                ObjectNode c = m.putArray("tool_calls").addObject(); c.put("id", "same_id"); c.put("type", "function");
                c.putObject("function").put("name", calls[round].get("tool").asText()).put("arguments", calls[round].path("args").toString());
                round++;
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 32_000; }
        };
        new Librarian(store, model, Librarian.person(), s).say(person);
        List<String> told = new ArrayList<>();
        for (ObjectNode m : s.messages()) if ("tool".equals(m.path("role").asText())) told.add(m.path("content").asText());
        return told;
    }

    static ObjectNode open(String address) { try { return LibrarianChatTest.tool("open", "{\"address\":" + q(address) + "}"); } catch (Exception e) { throw new IllegalStateException(e); } }
    static String said(String told) { return told.startsWith("NOT OPENED: ") ? "not opened" : told.startsWith("source: ") ? "read" : told.substring(0, Math.min(told.length(), 160)).replace('\n', ' '); }

    /** A report on the shelves whose words a model wrote. */
    void aReport(String id, String words) throws Exception {
        store.write(new Investigation(id, "A report", Finding.State.accepted, "model:stand-in", Instant.now().toString(), List.of(), List.of(), "## Answer\n\n" + words + "\n"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
    }
}
