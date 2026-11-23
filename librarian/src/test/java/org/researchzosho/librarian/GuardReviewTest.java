package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.tools.Outbound;
import org.researchzosho.Stopping;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.lang.reflect.InvocationTargetException;
import java.util.Random;
import java.util.function.Function;
import java.util.regex.Matcher;
import org.researchzosho.tools.Fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule on what a model's call may ask, after a second reading of 0.8.1: what a reader of the code said of it, each stated as
 * the behaviour that should hold and tried, with what an honest model and an ordinary person do beside it. The site is a listener on
 * the loopback that keeps what it is asked; no test asks a host outside this machine, and every test that expects nothing to arrive
 * first sees something arrive on the same listener.
 */
class GuardReviewTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String CANARY = "QX7731MOOR";

    @TempDir Path tmp;
    String realHome;
    HttpServer site;
    String base, n;
    final List<String> asked = new CopyOnWriteArrayList<>();
    /** What the site answers a path with (anything else is an ordinary page). */
    final Map<String, String> says = new LinkedHashMap<>();
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        n = Long.toHexString(System.nanoTime());
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", x -> {
            String target = x.getRequestURI().toString();
            asked.add(target);
            String page = says.getOrDefault(x.getRequestURI().getPath(), "The ferry ran from 1911 to 1968. This is the page at " + x.getRequestURI().getRawPath() + ".");
            byte[] b = ("<html><head><title>A page</title></head><body><p>" + page + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
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

    /** Everything this process was given, forgotten: what the next process starts with. */
    static void aNewProcess() throws Exception {
        for (String name : List.of("SEEN", "EXACT", "HOSTS", "INSIDE")) {
            Field f = GivenAddresses.class.getDeclaredField(name); f.setAccessible(true);
            Object held = f.get(null);
            if (held instanceof Map<?, ?> m) m.clear(); else ((Collection<?>) held).clear();
        }
        GivenWords.forget();
    }

    // ---- a machine's bare numbers in an argument that names an address (C-552)

    /**
     * An argument of a keeper's MCP server that names an address ({@code address}, {@code link}, {@code start_urls}) is held to
     * the rule when its value is written as an address of the web. A machine's four numbers are one, with a port or a path after
     * them or with nothing: numbers nobody wrote are refused either way. Numbers that somebody did write go on, as words: the
     * person asks where a machine is, a page lists one, and a keeper's tool is asked about it. The control: the same numbers with a
     * path after them were refused before and are now.
     */
    @Test
    void aMachinesBareNumbersNobodyWroteAreHeldInAnArgumentThatNamesAnAddress() throws Exception {
        GivenAddresses.personSaid("Where is the machine 198.51.100.7, and what answers there?");
        GivenAddresses.gave(true, null, "1. Hosts seen in the log: 203.0.113.9 and 203.0.113.10\n   https://example.org/log", GivenAddresses.Own.of(args("{\"where\":\"web\",\"query\":\"hosts in the harbour office log\"}")));
        Map<String, String> refused = new LinkedHashMap<>(), let = new LinkedHashMap<>();
        refused.put("with a path (the control)", GivenAddresses.unopened("reader.read", args("{\"address\":\"203.0.113.77/x\"}")));
        refused.put("bare, in address", GivenAddresses.unopened("reader.read", args("{\"address\":\"203.0.113.77\"}")));
        refused.put("bare, in link", GivenAddresses.unopened("reader.read", args("{\"link\":\"203.0.113.77\"}")));
        refused.put("bare, in a list of start_urls", GivenAddresses.unopened("crawler.crawl", args("{\"start_urls\":[\"203.0.113.78\"]}")));
        refused.put("bare, a user before it, in page_url", GivenAddresses.unopened("reader.read", args("{\"page_url\":\"reader@203.0.113.79/\"}")));
        refused.put("bare, in url, as in every tool", GivenAddresses.unopened("reader.read", args("{\"url\":\"203.0.113.77\"}")));
        let.put("numbers the person wrote, in address", GivenAddresses.unopened("whois.lookup", args("{\"address\":\"198.51.100.7\"}")));
        let.put("numbers a search's answer held, in address", GivenAddresses.unopened("whois.lookup", args("{\"address\":\"203.0.113.10\"}")));
        let.put("numbers in an argument that names no address", GivenAddresses.unopened("whois.lookup", args("{\"ip\":\"203.0.113.77\",\"version\":\"4.77.3.12\"}")));
        let.put("a street", GivenAddresses.unopened("maps.geocode", args("{\"address\":\"12 Oak Street, Springfield\"}")));
        let.put("a mail address", GivenAddresses.unopened("mail.lookup", args("{\"address\":\"ann@example.org\"}")));
        let.put("an id in link", GivenAddresses.unopened("tracker.open", args("{\"link\":\"1.2.3\"}")));
        System.out.println("[review] C-552 a machine's bare numbers in a keeper's server's argument that names an address: refused " + refused.values().stream().filter(v -> v != null).count() + " of " + refused.size()
                + " " + refused.entrySet().stream().filter(e -> e.getValue() == null).map(Map.Entry::getKey).toList() + "; numbers somebody wrote and other words let through " + let.values().stream().filter(v -> v == null).count() + " of " + let.size());
        for (Map.Entry<String, String> e : refused.entrySet()) assertTrue(e.getValue() != null && e.getValue().startsWith("NOT OPENED"), "numbers nobody wrote, " + e.getKey() + ": " + e.getValue());
        for (Map.Entry<String, String> e : let.entrySet()) assertNull(e.getValue(), e.getKey());
    }

    // ---- a machine's numbers typed alone by the person

    /**
     * "Open 192.0.2.1": a machine's four numbers standing alone in the person's OWN words are an address the person gave, as a
     * name without its scheme is. The call must name exactly those numbers, with a scheme before them, a slash after them, or
     * neither. Numbers nobody typed are refused; numbers that stand only in a page's text or in what the library stored are refused
     * (a text from outside gives an address only when it is written with its scheme); another page or another port of the typed
     * machine is nobody's. Four numbers that are a version count as typed too, which asks nothing unless a call names exactly them;
     * a dotted number with a fifth part, a part over 255, or letters against it is no address at all.
     */
    @Test
    void aMachinesNumbersThePersonTypedAloneAreAnAddressTheyGave() throws Exception {
        String a = "192.0.2." + (1 + (n.hashCode() & 0x7f)), b = "198.51.100." + (1 + (n.hashCode() & 0x7f)), inAPage = "203.0.113." + (1 + (n.hashCode() & 0x7f)), stored = "203.0.113." + (130 + (n.hashCode() & 0x3f));
        GivenAddresses.personSaid("Open " + a + " and tell me what the router says. Then " + b + ".");
        GivenAddresses.gave(true, null, "source: https://forum-" + n + ".example.org/t/1\n\nMy printer answers at " + inAPage + " since the update.", GivenAddresses.Own.of(args("{\"address\":\"https://forum-" + n + ".example.org/t/1\"}")));
        GivenAddresses.gave(false, store, "{\"id\":\"I-0001\",\"text\":\"The media server is at " + stored + " (a report's words).\"}", GivenAddresses.Own.of(args("{\"id\":\"I-0001\"}")));
        Map<String, Boolean> opened = new LinkedHashMap<>();
        for (String written : List.of(a, a + "/", "http://" + a, "http://" + a + "/", "https://" + a, "HTTPS://" + a + "/", b))
            opened.put(written, GivenAddresses.notGiven("open", args("{\"address\":" + q(written) + "}"), true, false) == null && GivenAddresses.unopened("web_fetch", args("{\"url\":" + q(written) + "}")) == null);
        Map<String, Boolean> refused = new LinkedHashMap<>();
        for (String written : List.of("192.0.2.200", "http://192.0.2.200/", inAPage, "http://" + inAPage, stored, a + "/admin", a + ":8080", "http://" + a + ":8080/", a + "/?d=" + CANARY, "http://" + a + ".example.net/", "http://" + a + "@collector.example.net/"))
            refused.put(written, GivenAddresses.notGiven("open", args("{\"address\":" + q(written) + "}"), true, false) != null);
        // what is asked: with no scheme by https, as every address without one; with http when the call writes http
        Map<String, String> asked = new LinkedHashMap<>();
        for (String written : List.of(a, a + "/", "http://" + a, "http://" + a + "/", "https://" + a + "/"))
            asked.put(written, GivenAddresses.asGiven(args("{\"address\":" + q(written) + "}")).path("address").asText());
        try (Outbound.Call c = Outbound.call("open", args("{\"address\":" + q("http://" + a) + ",\"find\":" + q("admin?d=" + CANARY) + "}"))) {
            Outbound.check(URI.create("http://" + a + "/"));
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("http://" + a + "/admin?d=" + CANARY)), "another page of the machine that the call's own words spell out, at the request");
        }
        // numbers that are part of something else
        GivenAddresses.personSaid("Release 4.77.3." + (n.hashCode() & 0x7f) + " is out; the build 11.1.2.3.4 failed, section 1.2.3.4.5 says why, and 300.1.2.3 is no machine, nor v9.8.7.6, nor 9.8.7.6-beta, nor 9.8.7.6rc1.");
        Map<String, Boolean> given = new LinkedHashMap<>();
        for (String numbers : List.of("4.77.3." + (n.hashCode() & 0x7f), "11.1.2.3", "1.2.3.4", "2.3.4.5", "300.1.2.3", "9.8.7.6", "0.1.2.3"))
            given.put(numbers, GivenAddresses.given("https://" + numbers));
        System.out.println("[review] a machine's bare numbers in the person's own words: opened " + opened + "; refused " + refused + "; asked as " + asked + "; four numbers among other things, given: " + given);
        for (Map.Entry<String, Boolean> e : opened.entrySet()) assertTrue(e.getValue(), "typed by the person, written by the call as " + e.getKey());
        for (Map.Entry<String, Boolean> e : refused.entrySet()) assertTrue(e.getValue(), "nobody typed it, or it is more than the numbers: " + e.getKey());
        assertEquals(Map.of(a, "https://" + a, a + "/", "https://" + a + "/", "http://" + a, "http://" + a, "http://" + a + "/", "http://" + a + "/", "https://" + a + "/", "https://" + a + "/"), asked);
        assertEquals(Map.of("4.77.3." + (n.hashCode() & 0x7f), true, "11.1.2.3", false, "1.2.3.4", false, "2.3.4.5", false, "300.1.2.3", false, "9.8.7.6", false, "0.1.2.3", false), given, "a version of four numbers counts; a part of a longer number, a part over 255 and numbers against letters do not");
        // on the wire, in the chat: a machine typed with its port and no scheme answers by plain http, and is read when the call writes http
        String typed = "127.0.0.1:" + site.getAddress().getPort() + "/status-" + n;
        List<String> told = chat("Read " + typed + " for me please.", open("http://" + typed), open("http://127.0.0.1:" + site.getAddress().getPort() + "/nobody-typed-" + n + "?d=" + CANARY));
        assertTrue(told.get(0).startsWith("source: ") && asked(n).contains("/status-" + n), "typed without a scheme, asked by http as the call wrote, and read: " + told.get(0));
        assertTrue(told.get(1).startsWith("NOT OPENED") && asked(n).stream().noneMatch(x -> x.contains(CANARY)), "another page of that machine (the control for the rule): " + told.get(1));
        // the fetcher's own refusals stand: this machine typed bare is given by the rule and refused by the fetcher; so is a link-local address, and the private network where the owner denies it
        GivenAddressesTest.loopback(false);
        try {
            asked.clear();
            List<String> own = chat("What does 127.0.0.1 show, and 169.254.169.254?", open("http://127.0.0.1"), open("http://169.254.169.254/"));
            assertTrue(own.get(0).contains("loopback") && own.get(1).contains("link-local"), "refused by the fetcher, each for what it is: " + own);
            Config.set("RESEARCHZOSHO_FETCH_PRIVATE", "deny");
            GivenAddresses.personSaid("And open 192.168.1.1 please.");
            assertNull(GivenAddresses.notGiven("open", args("{\"address\":\"192.168.1.1\"}"), true, false), "given by the rule");
            String why = Fetch.refusal(URI.create("http://192.168.1.1/"));
            assertTrue(why != null && why.contains("private network"), "and refused by the fetcher where the owner denies the private network: " + why);
            Config.set("RESEARCHZOSHO_FETCH_PRIVATE", "");
            assertNull(Fetch.refusal(URI.create("http://192.168.1.1/")), "at home, where the private network is the person's own, it is fetched");
        } finally { GivenAddressesTest.loopback(true); }
    }

    List<String> asked(String holding) { return asked.stream().filter(x -> x.contains(holding)).toList(); }

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

    static ObjectNode open(String address) { return GuardFixesTest.open(address); }

    // ---- the found pictures of one conversation and another (D-641)

    /**
     * What a picture search found is written on the line of the turn that searched, and on no other: the list is taken once, right
     * after the reply it belongs to, and is emptied before every reply is made. Two conversations on one thread, as a server's pool
     * runs them: the first one's search finds pictures; the second one's turn is the library's own but searches nothing, and its
     * line carries no found address. The control: the first conversation's line carries what its search found.
     */
    @Test
    void whatOneConversationsSearchFoundIsNotWrittenOnAnothersLine() throws Exception {
        var engines = Images.ENGINES;
        String picture = "https://img-" + n + ".example.org/ferry.png", page = "https://img-" + n + ".example.org/ferry.html";
        Images.ENGINES = () -> List.of(new Images.Engine("stand-in", (words, count) -> List.of(new Images.Found(picture, page, "The ferry", "stand-in", 800, 600, "", "", ""))));
        try {
            Librarian.Session first = Librarian.Session.open(store), second = Librarian.Session.open(store);
            String said = new Librarian(store, null, Librarian.person(), first).say("find pictures of the Kestrel ferry");
            // the same thread, another conversation: words the library answers itself, with no search
            String other = new Librarian(store, null, Librarian.person(), second).say("which model is the default?");
            List<String> onFirst = new ArrayList<>(), onSecond = new ArrayList<>();
            for (ObjectNode m : first.messages()) for (JsonNode a : m.path(Librarian.FOUND)) onFirst.add(a.asText());
            for (ObjectNode m : second.messages()) for (JsonNode a : m.path(Librarian.FOUND)) onSecond.add(a.asText());
            System.out.println("[review] D-641 found addresses on the lines of two conversations on one thread: the one that searched " + onFirst.size() + ", the one that did not " + onSecond.size() + " (its reply: " + (other == null ? "none" : other.substring(0, Math.min(60, other.length())).replace('\n', ' ')) + ")");
            assertTrue(onFirst.contains(picture) && onFirst.contains(page), "the line of the turn that searched carries what was found (the control): " + onFirst + " / " + said);
            assertTrue(second.messages().stream().anyMatch(m -> "library".equals(m.path("by").asText())), "the second turn was the library's own too, the path that takes the list: " + other);
            assertEquals(List.of(), onSecond, "another conversation's line carries the first one's found addresses");
            assertEquals(List.of(), ImagesTurn.foundNow(), "and nothing is left on the thread");
        } finally { Images.ENGINES = engines; }
    }

    // ---- what an answer gives when the call did not bring it (D-3161)

    /**
     * What the library itself says to a call gives nothing: the sentence that an address was not opened, the sentence that a street
     * was not looked up (which says the street back), the sentence that the same call was made before. Each goes the way every
     * answer goes ({@code GivenAddresses.gave}, as an answer from outside), and afterwards the same address and the same street are
     * still nobody's. The control: a page's answer in the same place gives the address it names.
     */
    @Test
    void whatTheLibraryItselfSaysToACallGivesNothing() throws Exception {
        String made = "https://collector-" + n + ".example.net/x?d=" + CANARY, named = "https://harbour-" + n + ".example.org/timetable";
        JsonNode fetch = args("{\"url\":" + q(made) + "}"), street = args("{\"address\":\"99 Lantern Row, Marrow, ME\"}");
        String notOpened = GivenAddresses.unopened("web_fetch", fetch), notLookedUp = GivenAddresses.unopened("records", street);
        assertTrue(notOpened != null && notOpened.startsWith("NOT OPENED") && notLookedUp != null && notLookedUp.startsWith(GivenWords.NOT_LOOKED_UP) && notLookedUp.contains("99 Lantern Row"), notOpened + " / " + notLookedUp);
        GivenAddresses.gave(true, store, notOpened, GivenAddresses.Own.of(fetch));
        GivenAddresses.gave(true, store, notLookedUp, GivenAddresses.Own.of(street));
        GivenAddresses.gave(true, store, "You already ran exactly this call; the result has not changed. Do something different: another query, another source, or note what you have and move on.", GivenAddresses.Own.of(fetch));
        GivenAddresses.gave(true, store, "ERROR: web_fetch failed — NOT OPENED: this call's own words name collector-" + n + ".example.net, and what was to be asked of it is not an address that the person gave.", GivenAddresses.Own.of(fetch));
        GivenAddresses.gave(true, store, "source: " + base + "/a-page\n\nThe timetable is at " + named + " now.", GivenAddresses.Own.of(args("{\"url\":" + q(base + "/a-page") + "}")));
        Map<String, Boolean> given = new LinkedHashMap<>();
        given.put("the address the refusal was about", GivenAddresses.given(made));
        given.put("the same in another call's argument", GivenAddresses.unopened("web_fetch", args("{\"url\":" + q(made) + ",\"find\":\"ferry\"}")) == null);
        given.put("the street the refusal said back", GivenAddresses.unopened("records", street) == null);
        given.put("the same street written out", GivenAddresses.unopened("records", args("{\"address\":\"99 Lantern Row, Marrow, Maine\"}")) == null);
        given.put("the same street in another call", GivenAddresses.unopened("library_records", args("{\"address\":\"99 Lantern Row, Marrow, ME\",\"note\":\"again\"}")) == null);
        System.out.println("[review] D-3161 after the library's own sentences went the way an answer goes, is it given: " + given + "; the address a page named: " + GivenAddresses.given(named));
        assertTrue(GivenAddresses.given(named), "a page's answer gives the address it names (the control)");
        for (Map.Entry<String, Boolean> e : given.entrySet()) assertFalse(e.getValue(), e.getKey());
    }

    /**
     * A run that was stopped and goes on in another process. What a call brought back before the stop is kept under the call
     * itself (its tool and its arguments), and is given back to the same call without asking again; it gives what it gave when it
     * was brought: the address the kept page names is opened afterwards, and an address nobody gave is refused as in any run. The
     * listener counts: the kept page is asked once in all, the named one once, the made-up one never.
     */
    @Test
    void aKeptAnswerOfAResumedRunGivesWhatItGaveAndNothingElse() throws Exception {
        String kept = base + "/kept-" + n, namedPath = "/named-by-the-kept-page-" + n, named = base + namedPath, made = base + "/made-up-" + n + "?d=" + CANARY;
        says.put("/kept-" + n, "The ferry ran from 1911 to 1968. The timetable is at " + named + " now.");
        Researcher.Ask ask = new Researcher.Ask("What does " + kept + " say about the ferry?", "depth", 30, List.of("what does the page say?"), "both", List.of());
        Function<HostileModelTest.Turn, ObjectNode> script = t -> switch (t.n()) {
            case 1 -> HostileModelTest.call("web_fetch", "{\"url\":" + q(kept) + "}");
            case 2 -> HostileModelTest.call("web_fetch", "{\"url\":" + q(named) + "}");
            case 3 -> HostileModelTest.call("web_fetch", "{\"url\":" + q(made) + "}");
            default -> HostileModelTest.call("done", "{\"summary\":\"from the page\"}");
        };
        // the run, stopped once the first page was read
        HostileModelTest.Scripted before = new HostileModelTest.Scripted(script, "The ferry ran from 1911 to 1968 (" + kept + ").");
        Checkpoint first = Checkpoint.open(store, "J-0001", "research", "q");
        Researcher r1 = new Researcher(before, before, HostileModelTest.fetches(), s -> { }, 1, store).checkpoint(first);
        // stopped when the page's answer is in the run's record, which is when the next process finds it kept. Stopped when the listener
        // had been asked for the page, the stop could be seen before the answer was kept, and the next process asked for the page again
        r1.stopWhen(() -> !first.valuesUnder("t|").isEmpty());
        assertThrows(Stopping.Requested.class, () -> r1.run(ask, ""));
        first.close();
        assertEquals(List.of("/kept-" + n), asked.stream().filter(a -> a.contains(n)).toList(), "the run read the page the person gave, and was stopped there");
        // the next process: nothing of the first one's memory
        aNewProcess();
        assertFalse(GivenAddresses.given(named), "the next process starts with nothing given (the instrument)");
        HostileModelTest.Scripted after = new HostileModelTest.Scripted(script, "The ferry ran from 1911 to 1968 (" + kept + ").");
        Checkpoint second = Checkpoint.open(store, "J-0001", "research", "q");
        assertTrue(second.resumed(), "the run goes on from what it kept");
        new Researcher(after, after, HostileModelTest.fetches(), s -> { }, 1, store).checkpoint(second).run(ask, "");
        second.close();
        List<String> ours = asked.stream().filter(a -> a.contains(n)).toList();
        String toldOfMade = after.told.stream().filter(a -> a.startsWith("NOT OPENED")).findFirst().orElse("");
        System.out.println("[review] D-3161 a run resumed in another process: the listener was asked " + ours + "; kept answers given back " + second.givenBack.get() + "; the made-up address: " + (toldOfMade.isEmpty() ? "not refused" : "not opened"));
        assertEquals(1, ours.stream().filter(a -> a.equals("/kept-" + n)).count(), "the kept page was not asked again");
        assertEquals(1, ours.stream().filter(a -> a.equals(namedPath)).count(), "the address the kept page named was opened after the resume (ordinary use)");
        assertTrue(ours.stream().noneMatch(a -> a.contains(CANARY)) && !toldOfMade.isEmpty(), "an address nobody gave is refused after a resume as before it: " + after.told);
    }

    // ---- a street's words (C-188, C-217, C-287)

    /**
     * A street, a city and a state are compared word by word: a made-up street does not pass because its letters stand inside
     * longer words of a given text (2 Oak St inside 12 Oak St, Oak inside Oakland, a city inside a longer city's name), and an
     * address that is cut short, empty or made of commas alone is answered with a sentence or let through to the lookup's own
     * "give the address as street, city, state", never with a failure of the turn. The control: the address the person gave is
     * looked up, written the short way too.
     */
    @Test
    void aStreetIsComparedWordByWordAndNoWrittenAddressFailsTheTurn() throws Exception {
        GivenAddresses.personSaid("What do the records say about 12 Oak Street, Portsmouth, New Hampshire, and about 110 Oakland Avenue in Marrowfield, Maine?");
        GivenAddresses.gave(true, null, "source: https://listings-" + n + ".example.org/a\n\nFor sale: 31 Harbour View Terrace, Kestrelmouth, ME 04001. Three bedrooms.", GivenAddresses.Own.of(args("{\"address\":\"https://listings-" + n + ".example.org/a\"}")));
        assertNull(GivenAddresses.notGiven("records", args("{\"address\":\"12 Oak St, Portsmouth, NH\"}"), true, false), "the person's own address, the short way (the control)");
        assertNull(GivenAddresses.notGiven("records", args("{\"address\":\"31 Harbour View Ter, Kestrelmouth, ME\"}"), true, false), "an address a page stated (the control)");
        // a word or a number nobody wrote, whose letters stand inside a word that was written
        List<String> madeUp = List.of("2 Oak St, Portsmouth, NH", "1 Oak St, Portsmouth, NH", "12 Oa St, Portsmouth, NH", "12 Oaks St, Portsmouth, NH", "10 Oak St, Portsmouth, NH", "12 Oak St, Port, NH", "12 Oak St, Ports, NH",
                "11 Oakland Ave, Marrowfield, ME", "110 Oakland Ave, Marrow, ME", "110 Oakland Ave, Field, ME", "1 Harbour View Ter, Kestrelmouth, ME", "31 Harbour View Ter, Kestrel, ME", "31 Harbour View Ter, Mouth, ME",
                "31 Harbour Ter, Kestrelmouth, ME", "3 Harbour View Ter, Kestrelmouth, ME", "31 Harbour Vie Ter, Kestrelmouth, ME");
        List<String> passed = new ArrayList<>();
        for (String a : madeUp) if (GivenAddresses.notGiven("records", args("{\"address\":" + q(a) + "}"), true, false) == null) passed.add(a);
        // the rule as it is written, said here so that it is seen: in the person's OWN words the parts of a street may stand close
        // together in any order ("on Oak Street, number 12"), so a street made of nothing but words the person wrote near each other
        // is the person's; in a page's text the parts must stand together as an address does
        assertNull(GivenAddresses.notGiven("records", args("{\"address\":\"12 Oak, Portsmouth, NH\"}"), true, false), "the person's own street, its last word left off");
        assertNull(GivenAddresses.notGiven("records", args("{\"address\":\"110 Oak Ave, Marrowfield, ME\"}"), true, false), "words the person wrote close together, in another order");
        // what a call may write in place of an address: none of it throws, here or in the lookup itself
        List<String> odd = new ArrayList<>(List.of("", " ", ",", ",,", ",,,,,", "a", "a,b", "a,,TX", ", Boston, MA", "12 Oak St,, NH", "12 Oak St, Portsmouth,", "12 Oak St, Portsmouth, NH, 03801, USA", ", , ", "\n,\n,\n", "12 Oak St, Portsmouth, N H", "１２ Oak St， Portsmouth， NH",
                "12 Oak St, " + "P".repeat(5000) + ", NH", "x".repeat(200_000) + ", Portsmouth, NH", "12 Oak St, Portsmouth, " + "9".repeat(5000)));
        List<String> threw = new ArrayList<>();
        for (String a : odd) {
            try { GivenAddresses.notGiven("records", args("{\"address\":" + q(a) + "}"), true, false); GivenWords.streetNotGiven(a); Instruments.streetParts(a); }
            catch (RuntimeException e) { threw.add((a.length() > 40 ? a.substring(0, 40) + "…" : a) + " → " + e); }
        }
        try { GivenWords.streetNotGiven(null); Instruments.streetParts(null); GivenAddresses.notGiven("records", args("{\"address\":null}"), true, false); GivenAddresses.notGiven("records", args("{\"address\":[\"12 Oak St\",\"Portsmouth\",\"NH\"]}"), true, false); }
        catch (RuntimeException e) { threw.add("no address at all → " + e); }
        System.out.println("[review] C-188, C-217, C-287 made-up streets and cities whose letters stand inside given words: looked up " + passed.size() + " of " + madeUp.size() + " " + passed + "; odd values in place of an address: " + odd.size() + ", failures " + threw.size() + " " + threw);
        assertEquals(List.of(), passed, "a made-up street or city passed");
        assertEquals(List.of(), threw, "a written address failed the check");
    }

    /**
     * A street address that ends in a ZIP code, the form the lookup's own example has: it is read as street, city and state (the
     * ZIP left off the state, five digits or nine, with the state's letters or its name), and it is looked up, whole, in the chat.
     * What the lookup hands on as the home's ZIP is the one its geocoder found for the address, as in 0.8.0 (the typed one was never
     * handed on: {@code recordsLive}, {@code p.zip()}), so nothing was lost there. The control: an address with no state is not
     * read as one.
     */
    @Test
    void aStreetAddressThatEndsInAZipIsLookedUp() throws Exception {
        Map<String, List<String>> read = new LinkedHashMap<>();
        for (String a : List.of("901 Bagby St, Houston, TX 77002", "901 Bagby St, Houston, TX 77002-1234", "901 Bagby St, Houston, Texas 77002", "901 Bagby St, Houston, tx  77002", "901 Bagby St, Houston, TX, 77002", "901 Bagby St, Houston, TX",
                "1 City Hall Square, Boston, Massachusetts 02201", "10 Main St, Charleston, West Virginia 25301", "10 Main St, Santa Fe, New Mexico 87501-2345", "500 E Capitol Ave, Pierre, S.D. 57501")) {
            String[] parts = Instruments.streetParts(a);
            read.put(a, parts == null ? null : List.of(parts));
        }
        System.out.println("[review] D-500, D-511 a street address that ends in a ZIP, as the lookup reads it: " + read);
        assertEquals(List.of("901 Bagby St", "Houston", "TX"), read.get("901 Bagby St, Houston, TX 77002"));
        for (Map.Entry<String, List<String>> e : read.entrySet()) if (!e.getKey().contains("S.D.")) assertTrue(e.getValue() != null && e.getValue().get(2).matches("[A-Z]{2}"), "read as street, city, state: " + e);
        assertEquals("WV", read.get("10 Main St, Charleston, West Virginia 25301").get(2));
        assertEquals("NM", read.get("10 Main St, Santa Fe, New Mexico 87501-2345").get(2));
        assertNull(Instruments.streetParts("901 Bagby St, Houston 77002"), "no state: not an address the lookup takes (the control)");
        // in the chat: the address the person typed, ZIP and all, reaches the lookup as typed
        List<String> lookedUp = new CopyOnWriteArrayList<>();
        Instruments.RecordsReader real = Instruments.RECORDS;
        Instruments.RECORDS = (st, address) -> { lookedUp.add(address); return "the assessor's values and the permits on the street."; };
        try {
            List<String> told = chat("What do the public records say about 901 Bagby St, Houston, TX 77002?", LibrarianChatTest.tool("records", "{\"address\":\"901 Bagby St, Houston, TX 77002\"}"),
                    LibrarianChatTest.tool("records", "{\"address\":\"902 Bagby St, Houston, TX 77002\"}"));
            assertEquals(List.of("901 Bagby St, Houston, TX 77002"), List.copyOf(lookedUp), "looked up with its ZIP as typed; the house next door, which nobody gave, is not (the control): " + told);
            assertTrue(told.get(1).startsWith(GivenWords.NOT_LOOKED_UP), told.get(1));
        } finally { Instruments.RECORDS = real; }
    }

    // ---- a text that repeats itself

    /**
     * A model that repeats itself writes "0.0.0.0." for as long as it is let, and a person pastes what they have. Such a text in a
     * person's words or in a call's arguments is read as any other: the turn does not end with the thread's stack used up (until
     * the second reading of 0.8.1 some 2,000 names joined by dots in a row did that, in the reading of addresses written without a
     * scheme and in the reading of the hosts a call names). The control: an address beside the long run is still read.
     */
    @Test
    void aLongRunOfNamesAndDotsEndsNoTurn() throws Exception {
        String host = "tide-office-" + n + ".example.org";
        List<String> runs = List.of("0.".repeat(60_000), "a.b-c.".repeat(20_000), "1.2.3.4.".repeat(15_000) + "com", "x".repeat(70) + "." + "y.".repeat(40_000) + "org/path", ("www." + host + ".").repeat(3_000));
        List<String> failed = new ArrayList<>();
        for (String run : runs) {
            try {
                GivenAddresses.personSaid("Read " + host + "/tables/1968 and then this: " + run + " please.");
                JsonNode call = args("{\"query\":" + q(run) + ",\"note\":" + q("see " + run) + ",\"link\":" + q(run) + ",\"urls\":[" + q(run) + "]}");
                GivenAddresses.unopened("docs.read", call);
                GivenAddresses.gave(true, null, "results for " + run + ": none", GivenAddresses.Own.of(call));
                GivenAddresses.asGiven(call);
                try (Outbound.Call c = Outbound.call("docs.read", call)) { c.names(host); }
            } catch (RuntimeException | Error e) { failed.add(run.substring(0, 12) + "… (" + run.length() + " characters) → " + e); }
        }
        System.out.println("[review] long runs of names and dots in a person's words and a call's arguments, " + runs.size() + " of them: failures " + failed.size() + " " + failed);
        assertEquals(List.of(), failed);
        assertNull(GivenAddresses.notGiven("open", args("{\"address\":" + q(host + "/tables/1968") + "}"), true, false), "the address beside the long run was read (the control)");
    }

    /** The pattern for an address written without its scheme, as it finds them one after the other in a text: what {@code GivenAddresses.bareIn} must find. */
    static List<String> bareByThePattern(String text) { List<String> out = new ArrayList<>(); Matcher m = GivenAddresses.BARE.matcher(text); while (m.find()) out.add(m.group()); return out; }

    /**
     * The addresses written without a scheme in a person's words are found as the pattern finds them, for every text whose runs of
     * names are no longer than a host's: tried on every text of up to seven characters over the letters that matter and on a hundred
     * thousand longer ones drawn by a fixed seed. A zero difference is the control that what a person gave before is given now.
     */
    @Test
    void theAddressesWithoutASchemeAreTheOnesThePatternFound() {
        char[] letters = {'a', 'B', '7', '.', '-', ' ', '/', ':'};
        List<String> differ = new ArrayList<>();
        long tried = 0, found = 0;
        for (int length = 0; length <= 7; length++) {
            int[] at = new int[length];
            for (boolean more = true; more; ) {
                StringBuilder b = new StringBuilder();
                for (int i : at) b.append(letters[i]);
                String text = b.toString();
                List<String> byPattern = bareByThePattern(text), byHand = GivenAddresses.bareIn(text);
                tried++; found += byPattern.size();
                if (!byPattern.equals(byHand) && differ.size() < 50) differ.add("\"" + text + "\": the pattern " + byPattern + ", now " + byHand);
                if (GivenAddresses.BARE.matcher(text).matches() != GivenAddresses.bareWhole(text) && differ.size() < 50) differ.add("\"" + text + "\" as a whole value");
                more = false;
                for (int i = length - 1; i >= 0; i--) { if (++at[i] < letters.length) { more = true; break; } at[i] = 0; }
            }
        }
        Random r = new Random(20261121);
        String[] pieces = {"example", "org", "com", "co", "uk", "www", "a", "x9", "-", ".", ".", ".", " ", "/", "/path", ":8443", "@", "_", "%", "é", "1", "22", "ORG", "\n", "?q=1", "(", ")", ",", "http://"};
        String[] names = {"example", "www", "a", "x9", "tide-office", "B", "7", "a-", "-a", "", "x".repeat(63), "y".repeat(64), "co", "é"}, ends = {"org", "com", "uk", "c", "c0m", "ORG", "museum", "x".repeat(25), "o-g", ""};
        String[] before = {"", " ", "see ", "@", "/", "x", ".", "-", ":", "(", "\n", "%"}, after = {"", " ", ".", ",", "/", "/path?q=1", ":8443", ":8443/p", ":x", "/a b", ") and", "2", ".example.org", "\n"};
        for (int k = 0; k < 300_000; k++) {
            StringBuilder b = new StringBuilder();
            if (k % 3 == 0) for (int i = r.nextInt(14); i > 0; i--) b.append(pieces[r.nextInt(pieces.length)]);
            else {   // the shape of an address, right and nearly right
                b.append(before[r.nextInt(before.length)]);
                for (int i = 1 + r.nextInt(4); i > 0; i--) b.append(names[r.nextInt(r.nextInt(3) == 0 ? names.length : 5)]).append(r.nextInt(12) == 0 ? ".." : ".");
                b.append(ends[r.nextInt(r.nextInt(3) == 0 ? ends.length : 3)]).append(after[r.nextInt(after.length)]);
                if (r.nextInt(4) == 0) b.append(' ').append(names[r.nextInt(5)]).append('.').append(ends[r.nextInt(3)]);
            }
            String text = b.toString();
            List<String> byPattern = bareByThePattern(text), byHand = GivenAddresses.bareIn(text);
            tried++; found += byPattern.size();
            if (!byPattern.equals(byHand) && differ.size() < 50) differ.add("\"" + text + "\": the pattern " + byPattern + ", now " + byHand);
            if (GivenAddresses.BARE.matcher(text).matches() != GivenAddresses.bareWhole(text) && differ.size() < 50) differ.add("\"" + text + "\" as a whole value");
        }
        System.out.println("[review] addresses written without a scheme, found now and by the pattern alone: " + tried + " texts, " + found + " addresses found by the pattern, differences " + differ.size() + " " + differ.stream().limit(5).toList());
        assertTrue(tried > 2_000_000 && found > 100_000, "the texts were tried and hold addresses (the control): " + tried + " / " + found);
        // and a run of more names than a host has is no address now, where the pattern alone used the stack up
        assertEquals(List.of(), GivenAddresses.bareIn("a.".repeat(GivenAddresses.MOST_NAMES + 2) + "org"));
        assertEquals(List.of("a.".repeat(GivenAddresses.MOST_NAMES - 1) + "org"), GivenAddresses.bareIn("a.".repeat(GivenAddresses.MOST_NAMES - 1) + "org"), "a run as long as a host's is read as before");
        assertEquals(List.of(), differ.stream().limit(20).toList());
    }

    // ---- a DOI: the form a model may write, and the resolver's redirect (C-34, C-111)

    /**
     * Two rules read a DOI, and each answers its own question. The form ({@code GivenIdentifiers.built}): may an address a model
     * WROTE, {@code https://doi.org/<DOI>}, be opened? Only when the DOI was given, and only in the plain shape (the registrants'
     * usual letters and marks, at most 200 of them). The resolver ({@code GivenIdentifiers.resolves}): may a request the program
     * made to doi.org be followed wherever the resolver sends it? When it asks for a DOI and nothing else, and the DOI was given or
     * the call's own words do not hold it (the tool read it in the person's file or in an answer from outside): then the model did
     * not choose it. The second reads any DOI's shape, since a person's reading list may hold one the first would not build.
     * What must hold across both: a DOI that only the call's own words hold is never followed off the resolver, long or short,
     * plain or not; and one the person gave is.
     */
    @Test
    void aDoiOnlyTheCallsOwnWordsHoldIsNeverFollowedOffTheResolver() throws Exception {
        String plain = "10.5555/given." + n, odd = "10.5555/(sici)1097-" + n + "(1997)79:2<385::aid-x>3.0.co;2-3", longOne = "10.5555/" + "l".repeat(240) + n;
        GivenAddresses.personSaid("Add these to my reading list: " + plain + " and " + odd + " and " + longOne);
        String madePlain = "10.5555/made." + n, madeOdd = "10.5555/made+" + n + "<x>", madeLong = "10.5555/" + "m".repeat(240) + n;
        URI publisher = URI.create("https://publisher-" + n + ".example.net/article/1");
        Map<String, String> got = new LinkedHashMap<>();
        for (String doi : List.of(plain, odd, longOne, madePlain, madeOdd, madeLong)) {
            String path = doi.replace("<", "%3C").replace(">", "%3E");
            boolean given = doi.equals(plain) || doi.equals(odd) || doi.equals(longOne);
            String byForm = GivenAddresses.notGiven("open", args("{\"address\":" + q("https://doi.org/" + path) + "}"), true, false) == null ? "opened by its form" : "not by its form";
            String followed, fromAFile;
            // a call whose own words hold the DOI (a model wrote it into a reading list's text)
            try (Outbound.Call c = Outbound.call("library_reading", args("{\"text\":" + q(doi) + "}"))) {
                Outbound.check(URI.create("https://doi.org/" + path));
                followed = hop(c, publisher) ? "followed off the resolver" : "kept on the resolver";
            }
            // a call whose words do not hold it (the tool read it in the person's file)
            try (Outbound.Call c = Outbound.call("library_reading", args("{\"file\":\"reading-list.txt\"}"))) {
                Outbound.check(URI.create("https://doi.org/" + path));
                fromAFile = hop(c, publisher) ? "followed" : "not followed";
            }
            got.put((given ? "given: " : "made up: ") + (doi.length() > 60 ? doi.substring(0, 24) + "…(" + doi.length() + " letters)" : doi), byForm + "; in a call that wrote it, " + followed + "; read from the person's file, " + fromAFile);
        }
        System.out.println("[review] C-34, C-111 a DOI by the two rules: " + got);
        for (Map.Entry<String, String> e : got.entrySet()) {
            if (e.getKey().startsWith("made up")) assertTrue(e.getValue().contains("not by its form") && e.getValue().contains("kept on the resolver"), "a DOI only the call's own words hold: " + e);
            else assertTrue(e.getValue().contains("followed off the resolver"), "a DOI the person gave is followed where the resolver sends it: " + e);
            assertTrue(e.getValue().endsWith("read from the person's file, followed"), "a DOI the call's words do not hold was read by the tool, not written by the model: " + e);
        }
        assertTrue(got.entrySet().stream().anyMatch(e -> e.getKey().startsWith("given: " + plain) && e.getValue().startsWith("opened by its form")), "the plain DOI the person gave opens by its form (the control): " + got);
    }

    /**
     * What a person means by a name or a number, in the spellings an honest model writes the address in: an arXiv number given with
     * its version and written without (opened in the given version), capitals in a category, an owner, a repository's name and an
     * article's title (kept as written: the address built is never put into small letters), the port a scheme has anyway written
     * out (the same site), and a DOI of the older kind with angle brackets in it, written with them or with their escapes. Beside
     * each: another port is another service, and letters or digits nobody gave are refused however they are dressed.
     */
    @Test
    void aNameOrANumberOpensInTheSpellingsAnHonestModelWrites() throws Exception {
        String host = "tide-office-" + n + ".example.org", sici = "10.1002/(SICI)1097-" + n + "(19970115)79:2<385::AID-CNCR22>3.0.CO;2-3";
        GivenAddresses.personSaid("Read arXiv:2401.12345v2 and math.AG/0601001v3, the paper " + sici + ", the Ferry-Tables-" + n + " repository of Harbour-Works, the Wikipedia article on Kestrel Sound " + n + ", and https://" + host + "/tables/1968 please.");
        String escaped = sici.replace("<", "%3C").replace(">", "%3E");
        Map<String, String> opened = new LinkedHashMap<>();   // what the model writes → what is requested
        opened.put("https://arxiv.org/abs/2401.12345", "https://arxiv.org/abs/2401.12345v2");                                   // the version the person gave (C-76)
        opened.put("https://arxiv.org/abs/2401.12345v2", "https://arxiv.org/abs/2401.12345v2");
        opened.put("https://arxiv.org/abs/math.AG/0601001", "https://arxiv.org/abs/math.AG/0601001v3");                         // capitals in the category, the version given
        opened.put("https://github.com/Harbour-Works/Ferry-Tables-" + n, "https://github.com/Harbour-Works/Ferry-Tables-" + n);     // capitals as written (C-57)
        opened.put("https://github.com/harbour-works/ferry-tables-" + n, "https://github.com/harbour-works/ferry-tables-" + n);     // and small letters when the model writes them so
        opened.put("https://en.wikipedia.org/wiki/Kestrel_Sound_" + n, "https://en.wikipedia.org/wiki/Kestrel_Sound_" + n);
        opened.put("https://arxiv.org:443/abs/2401.12345v2", "https://arxiv.org/abs/2401.12345v2");                               // the scheme's own port written out (C-67)
        opened.put("https://github.com:443/Harbour-Works/Ferry-Tables-" + n, "https://github.com/Harbour-Works/Ferry-Tables-" + n);
        opened.put("https://en.wikipedia.org:443/wiki/Kestrel_Sound_" + n, "https://en.wikipedia.org/wiki/Kestrel_Sound_" + n);
        opened.put("http://dx.doi.org:80/" + escaped, "https://doi.org/" + escaped);
        opened.put("https://" + host + ":443/", "https://" + host + "/");
        opened.put("https://" + host + ":443/robots.txt", "https://" + host + "/robots.txt");
        opened.put("https://doi.org/" + escaped, "https://doi.org/" + escaped);                                                   // a DOI with angle brackets, as an address holds them
        opened.put("https://doi.org/" + sici, "https://doi.org/" + escaped);                                                      // and as the person wrote them
        List<String> refused = List.of(
                "https://arxiv.org:8443/abs/2401.12345v2", "https://github.com:22/Harbour-Works/Ferry-Tables-" + n, "http://arxiv.org:443/abs/2401.12345v2x", "https://" + host + ":8443/",
                "https://doi.org/10.1002/made%3Cup%3E" + n, "https://doi.org/" + escaped + "%2Fextra" + n, "https://doi.org/" + escaped + "%3Fd%3D" + CANARY, "https://doi.org/" + escaped.replace("AID-CNCR22", "AID-" + CANARY),
                "https://doi.org/" + escaped + "?d=" + CANARY, "https://arxiv.org/abs/2401.12346", "https://github.com/Harbour-Works/Ferry-Tables-" + n + "-" + CANARY);
        Map<String, String> got = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : opened.entrySet()) {
            JsonNode call = args("{\"address\":" + q(e.getKey()) + "}");
            got.put(e.getKey(), GivenAddresses.notGiven("open", call, true, false) != null ? "NOT OPENED" : GivenAddresses.asGiven(call).path("address").asText());
        }
        List<String> letThrough = new ArrayList<>();
        for (String a : refused) if (GivenAddresses.notGiven("open", args("{\"address\":" + q(a) + "}"), true, false) == null) letThrough.add(a);
        List<String> notOpened = got.entrySet().stream().filter(e -> !e.getValue().equals(opened.get(e.getKey()))).map(e -> e.getKey() + " → " + e.getValue()).toList();
        System.out.println("[review] C-57, C-67, C-76 a name or a number in the spellings a model writes: " + (opened.size() - notOpened.size()) + " of " + opened.size() + " opened as built; not so: " + notOpened + "; dressed or made up: " + (refused.size() - letThrough.size()) + " of " + refused.size() + " refused " + letThrough);
        assertEquals(List.of(), letThrough, "an address nobody's name or number stands for was let through");
        assertEquals(opened, got, "each is opened, and what is requested is the address as the program builds it");
        // the request itself, and where the resolver sends it: the DOI with angle brackets was given, so it is followed
        try (Outbound.Call c = Outbound.call("open", args("{\"address\":" + q("https://doi.org/" + escaped) + "}"))) {
            Outbound.check(URI.create("https://doi.org/" + escaped));
            assertTrue(hop(c, URI.create("https://publisher-" + n + ".example.net/article/1")), "a given DOI's resolver is followed where it sends");
        }
    }

    /** Whether the hop the resolver sends the call's last request on to is let through. */
    static boolean hop(Outbound.Call c, URI to) throws Exception {
        var check = Outbound.class.getDeclaredMethod("check", Outbound.Call.class, URI.class, boolean.class);
        check.setAccessible(true);
        try { check.invoke(null, c, to, true); return true; }
        catch (InvocationTargetException e) { if (e.getCause() instanceof Outbound.NotOpened) return false; throw e; }
    }
}
