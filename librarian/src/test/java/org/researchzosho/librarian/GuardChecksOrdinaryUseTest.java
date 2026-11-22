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
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.Outbound;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordinary use is not refused (the tenth check before 0.8.1 goes out). The last release refused every property-records lookup because
 * of a guard; these are the ordinary things a person or an honest model does that the address guard, as it is written, could refuse
 * or change. Every test expects the thing to WORK. The site is a listener on the loopback that keeps each address it is asked for,
 * exactly as asked; nothing is asked of a host outside this machine. Where the test is of what happens after a site sends a request
 * on to another host, which the loopback cannot stand in for by name, the steps the fetcher takes are made by hand with no request
 * at all ({@link #sentOn}). Where a tool asks a second host by itself, that host is this listener under an address of this machine
 * the guard takes neither for this machine nor for a host of the owner's ({@code ::ffff:127.0.0.1}): a host like any other, to the
 * guard. Each test prints one line that begins {@code [check]} before it asserts.
 */
class GuardChecksOrdinaryUseTest {
    static final ObjectMapper M = new ObjectMapper();

    @TempDir Path tmp;
    String realHome;
    HttpServer site, service; ServerSocket bare; Thread accepting;
    String base, other, own, n;
    /** Every address the site was asked for, exactly as asked. */
    final List<String> asked = new CopyOnWriteArrayList<>();
    final List<String> askedOfTheArchive = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    /** Where the site sends a path on to, and what it answers a path with (anything else is an ordinary page). */
    final Map<String, String> sendsOn = new HashMap<>();
    final Map<String, String[]> answers = new HashMap<>();
    final Map<String, Object> archives = new HashMap<>();
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        n = Long.toHexString(System.nanoTime());
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", x -> {
            String target = x.getRequestURI().toString(), path = x.getRequestURI().getRawPath();
            asked.add(target);
            if (sendsOn.containsKey(target)) { x.getResponseHeaders().add("Location", sendsOn.get(target)); x.sendResponseHeaders(301, -1); x.close(); return; }
            String[] a = answers.get(path);
            byte[] b = (a != null ? a[1] : "<html><head><title>A page</title></head><body><p>The ferry ran from 1911 to 1968. This is the page at " + path + ".</p></body></html>").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", a != null ? a[2] : "text/html; charset=utf-8");
            x.sendResponseHeaders(a != null ? Integer.parseInt(a[0]) : 200, b.length); x.getResponseBody().write(b); x.close();
        });
        site.start();
        base = "http://127.0.0.1:" + site.getAddress().getPort();
        // the same listener under an address of this machine that the guard takes neither for this machine nor for a host of the owner's: another host, to the guard
        other = "http://[::ffff:127.0.0.1]:" + site.getAddress().getPort();
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/", x -> {
            String target = x.getRequestURI().toString();
            askedOfTheArchive.add(target);
            boolean copy = target.startsWith("/wayback/web/") && target.contains("gone-" + n);
            byte[] b = (copy ? "<html><head><title>A saved copy</title></head><body><p>The ferry ran from 1911 to 1968 (a saved copy of the page).</p></body></html>" : "not found").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", copy ? "text/html; charset=utf-8" : "text/plain");
            x.sendResponseHeaders(copy ? 200 : 404, b.length); x.getResponseBody().write(b); x.close();
        });
        service.start();
        own = "http://127.0.0.1:" + service.getAddress().getPort();
        Outbound.standIn("127.0.0.1:" + service.getAddress().getPort());   // the stand-in for the web archives, which the tools are written for
        for (String f : List.of("WAYBACK", "ARCHIVE_TODAY", "COMMON_CRAWL", "IA", "IA_FTS")) archives.put(f, HostileToolsTest.archive(f, own + "/" + f.toLowerCase()));
        bare = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        accepting = new Thread(() -> { while (!bare.isClosed()) { try (Socket s = bare.accept()) { connections.incrementAndGet(); } catch (Exception closed) { return; } } }, "bare-listener");
        accepting.setDaemon(true); accepting.start();
        GivenAddressesTest.loopback(true);
        ContentJudge.use(new ContentJudge(null, m -> "no"));
        store = new LibraryStore(tmp.resolve("home").resolve("researchzosho-library")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
    }

    @AfterEach void down() throws Exception {
        for (Map.Entry<String, Object> e : archives.entrySet()) HostileToolsTest.archive(e.getKey(), e.getValue());
        ContentJudge.use(null); GivenAddressesTest.loopback(false);
        site.stop(0); service.stop(0); bare.close();
        Outbound.noStandIns();
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    static String q(String s) { return M.valueToTree(s).toString(); }

    /** One turn of the chat in a conversation of its own: the person's words, then the model makes these calls, one a message. What the library told it for each. */
    List<String> chat(String person, ObjectNode... calls) throws Exception {
        Librarian.Session s = Librarian.Session.open(store);
        Researcher.Drive model = new Researcher.Drive() {
            int round;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode offered, int maxTokens, String toolChoice) {
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
                if (round >= calls.length || "none".equals(toolChoice)) return m.put("content", "Done.");
                m.put("content", "");
                ObjectNode c = m.putArray("tool_calls").addObject(); c.put("id", "c" + System.nanoTime()); c.put("type", "function");
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

    /** In a word: what the library told the model of an open. */
    static String said(String told) { return told.startsWith("NOT OPENED: ") ? "not opened" : told.startsWith("source: ") ? "read" : told.startsWith("ERROR") ? "error: " + told.substring(0, Math.min(told.length(), 200)).replace('\n', ' ') : told.substring(0, Math.min(told.length(), 120)).replace('\n', ' '); }

    /** The addresses the site was asked for that hold {@code mark}, as asked. */
    List<String> askedFor(String mark) { return asked.stream().filter(a -> { try { return a.contains(mark) || URLDecoder.decode(a, StandardCharsets.UTF_8).contains(mark); } catch (IllegalArgumentException e) { return false; } }).toList(); }

    // ---- an address the person or a result gave, written a little differently

    /** The person gives a page; the model opens it with a slash after it, or without the one the person wrote. Both are what a careful reader would call the same page. */
    @Test
    void anAddressOpensWithOrWithoutTheSlashAfterIt() throws Exception {
        String plain = base + "/timetable-" + n, slashed = base + "/archive-" + n + "/";
        // the two ways of writing each address are opened in conversations of their own: within one conversation the second is the same
        // page asked for again, which the fetch answers with "ALREADY FETCHED … its content is above", as it does for any page opened twice
        String person = "What do " + plain + " and " + slashed + " say?";
        List<String> told = new ArrayList<>(chat(person, open(plain), open(slashed)));
        told.addAll(chat(person, open(plain + "/"), open(slashed.substring(0, slashed.length() - 1))));
        System.out.println("[check] 10 a slash after an address: as given " + said(told.get(0)) + ", " + said(told.get(1)) + "; given without it and opened with it: " + said(told.get(2)) + "; given with it and opened without it: " + said(told.get(3)) + "; the site was asked " + askedFor(n));
        assertTrue(said(told.get(0)).equals("read") && said(told.get(1)).equals("read"), "each opens as it was given (the control): " + told);
        assertEquals("read", said(told.get(2)), "given without a slash after it, opened with one");
        assertEquals("read", said(told.get(3)), "given with a slash after it, opened without it");
        assertEquals(List.of("/timetable-" + n, "/archive-" + n + "/", "/timetable-" + n, "/archive-" + n + "/"), askedFor(n), "and each time the site is asked for the address as the person gave it");
    }

    /** A place on a page (a fragment), capitals in the host, the address typed in brackets or with a full stop after it: all the given page. */
    @Test
    void anAddressOpensWithAFragmentOrAsASentenceWroteIt() throws Exception {
        String a = base + "/history-" + n, b = base + "/fares-" + n, c = base + "/crew-" + n;
        List<String> told = chat("See " + a + ". The fares are listed (" + b + "), and the crew at <" + c + ">.", open(a + "#the-1952-crossing"), open(b), open(c), open(a.replace("http://", "HTTP://")));
        System.out.println("[check] 10 a fragment, an address in brackets, in angle brackets, with a full stop after it, with capitals in its scheme: " + told.stream().map(GuardChecksOrdinaryUseTest::said).toList() + "; the site was asked " + askedFor(n));
        for (int i = 0; i < 3; i++) assertEquals("read", said(told.get(i)), "the given page, call " + (i + 1) + ": " + told.get(i));
        assertEquals(List.of("/history-" + n, "/fares-" + n, "/crew-" + n), askedFor(n).stream().distinct().toList(), "each asked for as the person gave it, the fragment and the sentence's marks left off");
    }

    /** A page's address in another script, which a model writes with its escapes: the same page, asked for as it was given. */
    @Test
    void anAddressInAnotherScriptOpensWhenTheModelWritesItsEscapes() throws Exception {
        String given = base + "/wiki/フェリー-" + n;
        List<String> told = chat("What does " + given + " say?", open(base + "/wiki/%E3%83%95%E3%82%A7%E3%83%AA%E3%83%BC-" + n));
        System.out.println("[check] 10 an address in Japanese, written by the model with escapes: " + said(told.get(0)) + "; the site was asked " + askedFor(n));
        assertEquals("read", said(told.get(0)), told.get(0));
        assertEquals(1, askedFor("フェリー-" + n).size(), "and the site was asked for it: " + asked);
    }

    /** A search result's link with tracking parameters opens as it was listed, and the address the fetch then names as its source opens too. */
    @Test
    void aResultsLinkWithTrackingParametersOpens() throws Exception {
        String listed = base + "/news-" + n + "?utm_source=feed&utm_medium=rss&id=7";
        GivenAddresses.received("1. The ferry closes: " + listed);   // as a search's results give it
        List<String> told = chat("What is the news of the ferry?", open(listed), open(base + "/news-" + n + "?id=7"));
        System.out.println("[check] 10 a result's link with tracking parameters: as listed " + said(told.get(0)) + "; then without them, as the fetch named its source: " + said(told.get(1)) + "; the site was asked " + askedFor(n));
        assertEquals("read", said(told.get(0)), told.get(0));
        assertEquals("/news-" + n + "?utm_source=feed&utm_medium=rss&id=7", askedFor(n).get(0), "asked for as it was listed");
        assertEquals("read", said(told.get(1)), "the address the fetch itself named as what it read: " + told.get(1));
    }

    // ---- where a page sends the fetch on

    /** A fetch with no model's call, to see that the site's own redirect works and the listener sees the last address (the control for the tests below). */
    String withNoCall(String address) throws Exception { return Fetch.get(address, Duration.ofSeconds(20)).url(); }

    /** A page that answers with a redirect to itself with a slash after it, as a folder's address does on most servers. */
    @Test
    void aPageThatRedirectsToItselfWithASlashIsRead() throws Exception {
        sendsOn.put("/guide-" + n, "/guide-" + n + "/");
        sendsOn.put("/control-" + n, "/control-" + n + "/");
        assertEquals(base + "/control-" + n + "/", withNoCall(base + "/control-" + n), "with no call, the redirect is followed and the last address is read (the control)");
        List<String> told = chat("What does " + base + "/guide-" + n + " say?", open(base + "/guide-" + n));
        System.out.println("[check] 10 a page that redirects to itself with a slash after it: " + said(told.get(0)) + "; the site was asked " + askedFor("guide-" + n));
        assertEquals("read", said(told.get(0)), told.get(0));
        assertTrue(askedFor("guide-" + n).contains("/guide-" + n + "/"), "the page it was sent on to was asked for: " + asked);
    }

    /** A page that moved, a document behind a redirect, and a page that adds a visit's number to its own address. (A short link to another host is among the redirects made by hand below.) */
    @Test
    void aMovedPageAndADocumentBehindARedirectAreRead() throws Exception {
        sendsOn.put("/moved-" + n, "/2026/the-new-place-" + n);
        sendsOn.put("/paper-" + n, "/files/report-" + n + ".pdf");
        sendsOn.put("/session-" + n, "/session-" + n + "?visit=1");
        List<String> told = chat("Read " + base + "/moved-" + n + " and " + base + "/paper-" + n + " and " + base + "/session-" + n + " for me.", open(base + "/moved-" + n), open(base + "/paper-" + n), open(base + "/session-" + n));
        System.out.println("[check] 10 redirects on the wire: a page that moved " + said(told.get(0)) + ", a document behind a redirect " + said(told.get(1)) + ", a page that adds a visit's number to its own address " + said(told.get(2)) + "; the site was asked " + askedFor(n));
        for (int i = 0; i < 3; i++) assertEquals("read", said(told.get(i)), told.get(i));
        assertTrue(askedFor(n).containsAll(List.of("/2026/the-new-place-" + n, "/files/report-" + n + ".pdf", "/session-" + n + "?visit=1")), "each last address was asked for: " + asked);
    }

    /**
     * What the fetcher does when a site sends it on, made by hand with no request: the first address is asked ({@code Outbound.check}),
     * the address in the redirect is handed to the guard as one a server sent ({@code Outbound.sentOnTo}, as {@code Fetch.get} does),
     * and the next request is checked. "followed" when the next request may go, else the rule that refused it.
     */
    static String sentOn(String tool, String given, String to) {
        GivenAddresses.personSaid("Please read " + given + " for me.");
        try (Outbound.Call c = Outbound.call(tool, M.createObjectNode().put("url", given))) {
            Outbound.check(URI.create(given));
            Outbound.sentOnTo(to);
            Outbound.check(URI.create(to));
            return "followed";
        } catch (Outbound.NotOpened refused) { return "refused (" + refused.rule() + ")"; }
    }

    /**
     * The redirects sites make every day, by the fetcher's steps with no request made; the names are example names, one site for each.
     * Between the forms of a site's own name: to https, to www and from it, from the name alone to the home page, to itself with a
     * slash after it, in other capitals, to the page for a phone. To another page of the site: its home page, its section's front
     * page, the page in a language, the page with an ending. And from a link that carries its target in itself, as the links a mail
     * program rewrites do. The control: a short link to another host altogether is followed, by the same steps.
     */
    @Test
    void theRedirectsSitesMakeEveryDayAreFollowed() {
        Map<String, String[]> redirects = new LinkedHashMap<>();
        redirects.put("a short link to another host altogether (the control)", new String[]{"https://short-%s.example.net/abc", "https://ferry-%s.example.org/long/the-article"});
        redirects.put("a page that moved to a new address", new String[]{"https://%s/moved", "https://%s/2026/the-new-place"});
        redirects.put("http to https", new String[]{"http://%s/timetable", "https://%s/timetable"});
        redirects.put("to www", new String[]{"https://%s/timetable", "https://www.%s/timetable"});
        redirects.put("from www", new String[]{"https://www.%s/fares", "https://%s/fares"});
        redirects.put("http to https at www", new String[]{"http://%s/fares", "https://www.%s/fares"});
        redirects.put("the name alone, as the person typed it, to the home page at www", new String[]{"https://%s", "https://www.%s/"});
        redirects.put("to itself with a slash after it", new String[]{"https://%s/guide", "https://%s/guide/"});
        redirects.put("to itself in other capitals", new String[]{"https://%s/Almanac/1968", "https://%s/almanac/1968"});
        redirects.put("to the page for a phone", new String[]{"https://%s/crew", "https://m.%s/crew"});
        redirects.put("a page that is gone, to the home page", new String[]{"https://%s/old-news", "https://%s/"});
        redirects.put("a page that is gone, to its section's front page", new String[]{"https://%s/2026/news/the-ferry-closes", "https://%s/news"});
        redirects.put("to the page in a language", new String[]{"https://%s/history", "https://%s/en/history"});
        redirects.put("to the same page with an ending", new String[]{"https://%s/almanac", "https://%s/almanac.html"});
        redirects.put("a link a mail program rewrote, which carries its target in itself", new String[]{"https://links-" + n + ".example.net/?url=https%%3A%%2F%%2F%s%%2Ftimetable&data=05", "https://%s/timetable"});
        redirects.put("to a page that asks for consent, on another host, with the address in its own", new String[]{"https://%s/weather", "https://consent-" + n + ".example.net/?continue=https%%3A%%2F%%2F%s%%2Fweather"});
        Map<String, String> outcome = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, String[]> r : redirects.entrySet()) {
            String site = (r.getKey().startsWith("a short link") ? "" : "ferry-") + n + "-" + (i++) + (r.getKey().startsWith("a short link") ? "" : ".example.org");
            outcome.put(r.getKey(), sentOn("web_fetch", String.format(r.getValue()[0], site), String.format(r.getValue()[1], site)));
        }
        List<String> refused = outcome.entrySet().stream().filter(e -> !e.getValue().equals("followed")).map(e -> e.getKey() + ": " + e.getValue()).toList();
        System.out.println("[check] 10 the redirects sites make every day, by the fetcher's steps with no request made: " + outcome + "; refused: " + refused.size() + " of " + redirects.size());
        assertEquals("followed", outcome.get("a short link to another host altogether (the control)"), "a redirect to another host is followed by these steps");
        assertEquals(List.of(), refused, "an everyday redirect is refused");
    }

    // ---- what a page or a feed names

    /** The next page of a list, by the page's own link to it; and the feed a page names in its head. */
    @Test
    void theNextPageOfAListAndAFeedAPageNamesOpen() throws Exception {
        answers.put("/results-" + n, new String[]{"200", "<html><head><title>Results</title><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/feed-" + n + ".xml\"></head><body><p>Ten results about the ferry.</p>"
                + "<p><a href=\"/results-" + n + "?page=2&amp;sort=new\">Next</a></p></body></html>", "text/html; charset=utf-8"});
        List<String> told = chat("What does " + base + "/results-" + n + " list?", open(base + "/results-" + n), open(base + "/results-" + n + "?page=2&sort=new"), open(base + "/feed-" + n + ".xml"));
        System.out.println("[check] 10 a page's own links: the page " + said(told.get(0)) + ", its next page " + said(told.get(1)) + ", the feed it names " + said(told.get(2)) + "; the site was asked " + askedFor(n));
        assertEquals("read", said(told.get(0)), told.get(0));
        assertEquals("read", said(told.get(1)), "the next page, by the page's own link: " + told.get(1));
        assertTrue(askedFor("feed-" + n).size() == 1 && !said(told.get(2)).equals("not opened"), "the feed the page names is asked for: " + told.get(2));
    }

    static String feed(String title, String transcriptAt) {
        return "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>" + title + "</title><language>en</language>"
                + "<item><title>The crossing</title><guid>ep-1</guid><pubDate>Mon, 02 Nov 2026 10:00:00 GMT</pubDate><podcast:transcript url=\"" + transcriptAt + "\" type=\"text/vtt\"/></item></channel></rss>";
    }

    /** A run of a model that makes these calls, one a turn, and is done; what the library told it for each. */
    List<String> run(String question, List<ObjectNode> calls) throws Exception {
        List<String> told = new CopyOnWriteArrayList<>();
        ResearcherTest.ScriptedDrive model = new ResearcherTest.ScriptedDrive() {
            { criticWantsMore = false; }
            @Override public ObjectNode chat(ArrayNode history, ArrayNode offered, int maxTokens, String toolChoice) {
                List<String> names = names(offered);
                if (names.equals(List.of("done"))) return call("done", M.createObjectNode().put("summary", "closed"));
                int turn = assistantTurns(history) + 1;
                if (names.contains("write_section")) return turn == 1 ? call("write_section", M.createObjectNode().put("heading", "Answer").put("text", "Nothing was found.")) : call("done", M.createObjectNode().put("summary", "written"));
                List<String> answersSoFar = new ArrayList<>();
                for (JsonNode m : history) if ("tool".equals(m.path("role").asText())) answersSoFar.add(m.path("content").asText());
                if (answersSoFar.size() > told.size()) { told.clear(); told.addAll(answersSoFar); }
                return turn <= calls.size() ? calls.get(turn - 1) : call("done", M.createObjectNode().put("summary", "done"));
            }
        };
        new Researcher(model, model, HostileToolsTest.tools(), s -> { }, 1, store).run(new Researcher.Ask(question, "depth", 30, List.of("what can be found?"), "both", List.of()), "");
        return told;
    }

    /**
     * A podcast's feed, given by the person: its episodes are listed, and an episode's published transcript is read. A show's feed
     * names where each transcript is, and that is often another host than the feed's own. The control: the same with the transcript
     * on the feed's own host, which is read.
     */
    @Test
    void aShowsEpisodesAndItsPublishedTranscriptAreRead() throws Exception {
        String vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nThe ferry crossed at dawn.\n";
        answers.put("/show-" + n + "/feed.xml", new String[]{"200", feed("The Harbour Hour", base + "/transcripts/same-host-" + n + ".vtt"), "application/rss+xml"});
        answers.put("/other-" + n + "/feed.xml", new String[]{"200", feed("The Pier Hour", other + "/transcripts/other-host-" + n + ".vtt"), "application/rss+xml"});
        answers.put("/transcripts/same-host-" + n + ".vtt", new String[]{"200", vtt, "text/vtt"});
        answers.put("/transcripts/other-host-" + n + ".vtt", new String[]{"200", vtt, "text/vtt"});
        String one = base + "/show-" + n + "/feed.xml", two = base + "/other-" + n + "/feed.xml";
        assertEquals(200, Fetch.get(other + "/transcripts/other-host-" + n + ".vtt?no-call", Duration.ofSeconds(20)).status(), "with no call, the transcript on the other host is fetched: the address works (the control)");
        List<String> told = run("What was said in The crossing? The feeds are " + one + " and " + two, List.of(
                HostileToolsTest.call("podcast_search", "{\"kind\":\"episodes\",\"feed\":" + q(one) + "}"),
                HostileToolsTest.call("podcast_search", "{\"kind\":\"transcript\",\"feed\":" + q(one) + ",\"episode\":\"The crossing\"}"),
                HostileToolsTest.call("podcast_search", "{\"kind\":\"episodes\",\"feed\":" + q(two) + "}"),
                HostileToolsTest.call("podcast_search", "{\"kind\":\"transcript\",\"feed\":" + q(two) + ",\"episode\":\"The crossing\"}")));
        boolean sameHost = told.size() > 1 && told.get(1).contains("The ferry crossed at dawn"), otherHost = told.size() > 3 && told.get(3).contains("The ferry crossed at dawn");
        System.out.println("[check] 10 a podcast: episodes listed " + (told.size() > 0 && told.get(0).contains("The crossing")) + "; its transcript on the feed's own host read: " + sameHost + "; a transcript on another host read: " + otherHost
                + (told.size() > 3 ? " (the model was told: " + told.get(3).substring(0, Math.min(told.get(3).length(), 220)).replace('\n', ' ') + ")" : "") + "; the site was asked " + askedFor(n));
        assertTrue(told.size() == 4 && told.get(0).contains("The crossing") && told.get(2).contains("The crossing"), "the episodes of a feed the person gave are listed: " + told);
        assertTrue(sameHost, "a transcript on the feed's own host is read (the control): " + told.get(1));
        assertTrue(otherHost, "a transcript the feed names on another host is read: " + told.get(3));
    }

    /** A page that is gone: the fetch finds the archive's saved copy of it by itself; and a saved copy the archive search names is opened. */
    @Test
    void aSavedCopyOfAGivenPageIsRead() throws Exception {
        answers.put("/gone-" + n, new String[]{"404", "gone", "text/plain"});
        String page = base + "/gone-" + n;
        List<String> told = run("What did " + page + " say?", List.of(
                HostileToolsTest.call("web_fetch", "{\"url\":" + q(page) + "}"),
                HostileToolsTest.call("archive_search", "{\"kind\":\"copy\",\"query\":" + q(page) + "}"),
                HostileToolsTest.call("web_fetch", "{\"url\":" + q(own + "/wayback/web/99999999id_/" + page) + "}")));
        System.out.println("[check] 10 a page that is gone: the fetch " + (told.get(0).contains("a saved copy") ? "read the archive's copy by itself" : said(told.get(0))) + "; the archive search " + (told.get(1).contains("/wayback/web/") ? "named a copy" : "named none")
                + "; the copy it named: " + (told.size() > 2 ? said(told.get(2)) : "not asked") + "; the archive's stand-in was asked " + askedOfTheArchive);
        assertTrue(told.get(0).contains("a saved copy"), "the fetch of a page that is gone reads the archive's copy: " + told.get(0));
        assertTrue(told.get(1).contains(own + "/wayback/web/99999999id_/" + page), "the archive search names the copy: " + told.get(1));
        assertTrue(told.size() > 2 && !told.get(2).startsWith("NOT OPENED"), "and the copy it named is opened (or said to be read already): " + told);
    }

    // ---- what the person meant, and did not write as an address

    /**
     * The check of a call's arguments alone, with no request. What an honest model opens next from what the person said: the address
     * as typed, without https://; a site by its name, and its robots file; the home page of a site of which the person gave one
     * page; and the address a model writes from a name or a number the person gave, on a host the program is written for (a
     * repository by owner and name, a paper by its DOI or its arXiv number, an encyclopedia's article by its title). The control: an
     * address the person wrote out is let through, and one nobody gave on a host nobody named is not.
     */
    @Test
    void whatThePersonMeantIsOpened() throws Exception {
        String h = "harbour-" + n + ".example.org", k = "pier-" + n + ".example.org";
        GivenAddresses.personSaid("Read " + h + "/history, and https://" + k + "/a/page. Is " + k + " open to crawlers? Tell me about the repository harbour-works/ferry-tables-" + n
                + ", the paper 10.5555/ferry." + n + ", the preprint arXiv:2611." + n.substring(0, 5) + " and the Wikipedia article on Kestrel Sound " + n + ".");
        Map<String, String> calls = new LinkedHashMap<>();
        calls.put("the address as the person typed it, without https://", h + "/history");
        calls.put("the same with https:// before it", "https://" + h + "/history");
        calls.put("a site the person named by its name alone", "https://" + k + "/");
        calls.put("the robots file of that site", "https://" + k + "/robots.txt");
        calls.put("the home page of a site of which the person gave one page", "https://" + h + "/");
        calls.put("a repository the person named by owner and name", "https://github.com/harbour-works/ferry-tables-" + n);
        calls.put("a paper the person named by its DOI", "https://doi.org/10.5555/ferry." + n);
        calls.put("a preprint the person named by its arXiv number", "https://arxiv.org/abs/2611." + n.substring(0, 5));
        calls.put("an encyclopedia's article the person named by its title", "https://en.wikipedia.org/wiki/Kestrel_Sound_" + n);
        Map<String, String> outcome = new LinkedHashMap<>();
        for (Map.Entry<String, String> c : calls.entrySet()) outcome.put(c.getKey(), GivenAddresses.notGiven("open", M.readTree("{\"address\":" + q(c.getValue()) + "}"), true, false) == null ? "let through" : "not opened");
        boolean written = GivenAddresses.notGiven("open", M.readTree("{\"address\":" + q("https://" + k + "/a/page") + "}"), true, false) == null;
        boolean nobodys = GivenAddresses.notGiven("open", M.readTree("{\"address\":" + q("https://nobody-" + n + ".example.net/x") + "}"), true, false) == null;
        List<String> refused = outcome.entrySet().stream().filter(e -> !e.getValue().equals("let through")).map(Map.Entry::getKey).toList();
        System.out.println("[check] 10 what the person meant, by the check of a call's arguments: " + outcome + "; not opened: " + refused.size() + " of " + calls.size());
        assertTrue(written && !nobodys, "an address the person wrote out is let through, and one nobody gave is not (the control)");
        assertEquals(List.of(), refused, "what the person meant is not opened");
    }

    /**
     * A machine at home, typed by its numbers and port without http:// (a media server, a printer): the library connects to it. With
     * https, which the bare listener here cannot answer, so the connection is what is counted. The control: the same address typed
     * with its scheme is asked for.
     */
    @Test
    void aMachineTypedByItsNumbersWithoutASchemeIsAskedFor() throws Exception {
        String typed = "127.0.0.1:" + bare.getLocalPort() + "/status-" + n, withScheme = base + "/status-" + n;
        List<String> control = chat("Read " + withScheme + " for me please.", open(withScheme));
        List<String> told = chat("Read " + typed + " for me please.", open(typed));
        System.out.println("[check] 10 a machine typed by its numbers and port: with http:// " + said(control.get(0)) + "; without a scheme " + (told.get(0).startsWith("NOT OPENED") ? "not opened" : "asked for") + ", " + connections.get() + " connection(s) to it");
        assertEquals("read", said(control.get(0)), "typed with its scheme, it is read (the control)");
        assertTrue(!told.get(0).startsWith("NOT OPENED") && connections.get() >= 1, "typed without one, it is asked for: " + told.get(0));
    }
}
