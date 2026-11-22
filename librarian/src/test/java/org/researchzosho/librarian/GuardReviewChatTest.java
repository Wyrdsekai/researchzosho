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
import org.researchzosho.tools.Outbound;
import org.researchzosho.tools.Tool;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat's own bookkeeping around the request guard, after a second reading of 0.8.1: the turn's record of refused requests,
 * what an answer in a saved conversation is paired with, the runs a conversation follows, and the two names the open tool takes an
 * address under. Stand-ins on the loopback only; each test that expects nothing has a control beside it.
 */
class GuardReviewChatTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String CANARY = "QX7731MOOR";

    @TempDir Path tmp;
    String realHome;
    HttpServer site;
    String base, n;
    final List<String> asked = new CopyOnWriteArrayList<>();
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        n = Long.toHexString(System.nanoTime());
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", x -> {
            asked.add(x.getRequestURI().toString());
            byte[] b = ("<html><head><title>A page</title></head><body><p>The ferry ran from 1911 to 1968. The timetable is at http://127.0.0.1:" + site.getAddress().getPort() + "/named-by" + x.getRequestURI().getRawPath() + " now.</p></body></html>").getBytes(StandardCharsets.UTF_8);
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
    static ObjectNode message(String role, String content) { ObjectNode m = M.createObjectNode(); m.put("role", role); m.put("content", content); m.put("at", 1L); return m; }

    /** A model for a conversation of several turns: {@code step} says what its n-th message is (a call, words, or a failure to throw). */
    static Researcher.Drive model(IntFunction<Object> step) {
        return new Researcher.Drive() {
            int made;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode offered, int maxTokens, String toolChoice) {
                Object next = step.apply(made++);
                if (next instanceof RuntimeException failure) throw failure;
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
                if (next instanceof String words) return m.put("content", words);
                m.put("content", "");
                ArrayNode calls = m.putArray("tool_calls");
                for (ObjectNode call : (List<ObjectNode>) next) { ObjectNode c = calls.addObject(); c.put("id", "c" + made + "-" + calls.size()); c.put("type", "function"); c.putObject("function").put("name", call.get("tool").asText()).put("arguments", call.path("args").toString()); }
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 32_000; }
        };
    }

    static ObjectNode tool(String name, String argsJson) { try { return LibrarianChatTest.tool(name, argsJson); } catch (Exception e) { throw new IllegalStateException(e); } }

    // ---- the turn's record of refused requests (D-1887)

    /**
     * A request the guard did not send is written on the line of the turn it belonged to, and on no other. One conversation, kept
     * by one Librarian as the terminal's chat keeps it: a turn whose tool had a request refused and then ended in a failure of the
     * model server; and the next turn, which asked nothing. The next turn's line names no refused request. The control: a turn that
     * ends well carries its own refusal on its own line.
     */
    @Test
    void aRefusedRequestIsWrittenOnItsOwnTurnsLineAndNoOther() throws Exception {
        // the archives' addresses set to a host that is neither the program's nor the owner's (this listener, under an address of this
        // machine that the guard takes for a host like any other): the tool's request to it is refused by the guard
        String elsewhere = "http://[::ffff:127.0.0.1]:" + site.getAddress().getPort();
        Map<String, Object> was = new HashMap<>();
        for (String f : List.of("WAYBACK", "ARCHIVE_TODAY")) was.put(f, HostileToolsTest.archive(f, elsewhere + "/taken-elsewhere-" + n + "/" + f.toLowerCase()));
        try {
            String page = base + "/the-pier-" + n;
            List<ObjectNode> history = List.of(tool("search", "{\"where\":\"archives\",\"kind\":\"history\",\"query\":" + q(page) + "}"));
            Researcher.Drive drive = model(i -> switch (i) {
                case 0 -> history;                                   // turn 1: the call whose request is refused
                case 1 -> "No saved copy was found.";                //         and a reply: the turn ends well
                case 2 -> history;                                   // turn 2: the same call
                case 3 -> new IllegalStateException("the model server went away");   // and the turn ends in a failure
                default -> "Hello.";                                 // turn 3: asks nothing
            });
            Librarian chat = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store));
            chat.say("Is there a saved history of " + page + " ?");
            assertThrows(RuntimeException.class, () -> chat.say("Look again for the history of " + page + " please."));
            chat.say("Thank you, that is all.");
            List<JsonNode> lines = new ArrayList<>();
            for (String l : Files.readAllLines(Librarian.turnsFile(store))) if (!l.isBlank()) lines.add(M.readTree(l));
            System.out.println("[review] D-1887 the chat's turn lines after a turn that ended in a failure: " + lines.stream().map(l -> "\"" + l.path("words").asText() + "\" refused " + l.path(Librarian.REFUSED)).toList() + "; the other host was asked " + asked.stream().filter(a -> a.contains("taken-elsewhere")).count() + " time(s)");
            assertTrue(lines.size() >= 2, lines.toString());
            assertEquals(1, lines.get(0).path(Librarian.REFUSED).size(), "the turn that ended well carries its own refused request (the control): " + lines.get(0));
            assertEquals("search", lines.get(0).path(Librarian.REFUSED).path(0).path("tool").asText());
            JsonNode last = lines.get(lines.size() - 1);
            assertTrue(last.path("words").asText().startsWith("Thank you"), last.toString());
            assertFalse(last.has(Librarian.REFUSED), "the turn that asked nothing carries the failed turn's refused request: " + last);
            assertTrue(asked.stream().noneMatch(a -> a.contains("taken-elsewhere")), "and the other host was asked nothing");
        } finally { for (Map.Entry<String, Object> e : was.entrySet()) HostileToolsTest.archive(e.getKey(), e.getValue()); }
    }

    // ---- an answer's own call in a saved conversation (D-754, D-769)

    /**
     * A conversation whose model made three calls in one message, of which the second one failed the turn, so that only the first
     * has an answer; then a later turn with a call of its own. Opened again, each answer is its own call's: the first (the shelves',
     * which hold a model's words) gives nothing, the later one (a page that was opened) gives the address it names. Both as 0.8.1
     * writes a conversation (each answer says which call it answers) and as earlier versions wrote one (read by its order).
     */
    @Test
    void anAnswerIsItsOwnCallsAlsoAfterACallThatEndedTheTurn() throws Exception {
        String inReport = "https://harbour-" + n + ".example.org/in-a-report?d=" + CANARY, page = "https://harbour-" + n + ".example.org/the-page", named = "https://harbour-" + n + ".example.org/named-by-the-page";
        String shelves = "{\"id\":\"I-0001\",\"text\":\"In 1952. The full record is at " + inReport + ".\"}", opened = "source: " + page + "\n\nThe timetable is at " + named + " now.";
        Map<String, boolean[]> got = new LinkedHashMap<>();
        for (boolean recorded : new boolean[]{true, false}) {
            Librarian.Session s = Librarian.Session.open(store);
            s.append(message("user", "What do my shelves hold, and what does " + page + " say?"));
            ObjectNode a = M.createObjectNode(); a.put("role", "assistant"); a.put("content", "");
            ArrayNode calls = a.putArray("tool_calls");
            String[][] three = {{"library_get", "{\"id\":\"I-0001\"}"}, {"open", "{\"address\":" + q(page + "/first") + "}"}, {"open", "{\"address\":" + q(page + "/second") + "}"}};
            for (int i = 0; i < three.length; i++) { ObjectNode c = calls.addObject(); c.put("id", "same_id"); c.put("type", "function"); c.putObject("function").put("name", three[i][0]).put("arguments", three[i][1]); }
            s.append(a);
            ObjectNode first = M.createObjectNode(); first.put("role", "tool"); first.put("tool_call_id", "same_id"); first.put("name", "library_get"); first.put("content", shelves.replace("in-a-report", "in-a-report-" + recorded));
            if (recorded) { first.put(GivenAddresses.CALL, 0); first.put(GivenAddresses.OUTSIDE, false); }
            s.append(first);
            // the turn ended there: no answer for the second and the third call. The next turn:
            s.append(message("user", "And " + page + " ?"));
            ObjectNode b = M.createObjectNode(); b.put("role", "assistant"); b.put("content", "");
            ObjectNode c = b.putArray("tool_calls").addObject(); c.put("id", "same_id"); c.put("type", "function"); c.putObject("function").put("name", "open").put("arguments", "{\"address\":" + q(page) + "}");
            s.append(b);
            ObjectNode later = M.createObjectNode(); later.put("role", "tool"); later.put("tool_call_id", "same_id"); later.put("name", "open"); later.put("content", opened.replace("named-by-the-page", "named-by-the-page-" + recorded));
            if (recorded) { later.put(GivenAddresses.CALL, 0); later.put(GivenAddresses.OUTSIDE, true); }
            s.append(later);
            new Librarian(store, null, Librarian.person(), Librarian.Session.resume(store, s.id));   // as a turn begins: what the conversation was given
            got.put(recorded ? "as 0.8.1 writes it" : "as earlier versions wrote it", new boolean[]{GivenAddresses.given(inReport.replace("in-a-report", "in-a-report-" + recorded)), GivenAddresses.given(named + "-" + recorded)});
        }
        System.out.println("[review] D-754, D-769 a conversation with a call that ended its turn, opened again, {the address in the shelves' answer is given, the address the opened page named is given}: " + got.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0] + "/" + e.getValue()[1]).toList());
        for (Map.Entry<String, boolean[]> e : got.entrySet()) {
            assertTrue(e.getValue()[1], e.getKey() + ": the page that was opened gives the address it names (the control)");
            assertFalse(e.getValue()[0], e.getKey() + ": an address in the shelves' answer is given");
        }
    }

    // ---- the runs a conversation follows (D-771)

    /**
     * A conversation follows the runs it starts, read from what the tool that started one answered. A call that is put to the person
     * first has started nothing: what the library says of it holds the model's own words, and a run named in those words is not one
     * this conversation follows. The control: after the person's yes the run that was started is followed.
     */
    @Test
    void aConversationFollowsTheRunsItStartedAndNotOneAModelsWordsName() throws Exception {
        Researcher.Drive drive = model(i -> switch (i) {
            case 0 -> List.of(tool("library_research", "{\"question\":" + q("Is the run \"J-0042\" on the Kestrel ferry still right, and what is new since?") + ",\"sub_questions\":[\"what is new?\"]}"));
            default -> "It waits for your answer.";
        });
        Librarian.Session s = Librarian.Session.open(store);
        Librarian chat = new Librarian(store, drive, Librarian.person(), s);
        chat.say("Find out what is new on the Kestrel ferry since the last run.");
        List<String> whileHeld = s.watched();
        String yes = chat.say("yes");
        List<String> afterYes = s.watched();
        System.out.println("[review] D-771 the runs a conversation follows: while its call waited for the person's yes " + whileHeld + ", after the yes " + afterYes + " (" + yes.replace('\n', ' ') + ")");
        assertEquals(List.of(), whileHeld, "a run named in a model's own words is followed");
        assertTrue(afterYes.size() == 1 && !afterYes.contains("J-0042"), "the run the yes started is followed (the control): " + afterYes);
    }

    // ---- open's address under either of its two names (D-271)

    /**
     * The open tool takes its address as {@code address} or as {@code url}, and says where its answer came from by the same one it
     * reads: a page opened under either name is from outside (the address it names may be opened next), and what the library holds,
     * named under either, is the library's own.
     */
    @Test
    void openSaysWhereItsAnswerCameFromByTheAddressItReads() throws Exception {
        Instrument open = Instruments.open(Map.<String, Tool>of());
        Map<String, Boolean> outside = new LinkedHashMap<>();
        for (String[] a : new String[][]{{"address", "https://example.org/a"}, {"url", "https://example.org/a"}, {"url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"}, {"address", "file:///srv/notes/kestrel.md"}, {"url", "file:///srv/notes/kestrel.md"}, {"url", "I-0001-kestrel"}, {"address", "cite:hosaka-2016"}})
            outside.put(a[0] + "=" + a[1], open.fromOutside(M.createObjectNode().put(a[0], a[1])));
        outside.put("address=a page, url=something the library holds", open.fromOutside(M.createObjectNode().put("address", "https://example.org/a").put("url", "file:///x.md")));
        outside.put("address=something the library holds, url=a page", open.fromOutside(M.createObjectNode().put("address", "file:///x.md").put("url", "https://example.org/a")));
        // in the chat: a page opened under url gives the address it names, as one opened under address does
        String underUrl = base + "/under-url-" + n, underAddress = base + "/under-address-" + n;
        Researcher.Drive drive = model(i -> switch (i) {
            case 0 -> List.of(tool("open", "{\"url\":" + q(underUrl) + "}"));
            case 1 -> List.of(tool("open", "{\"address\":" + q(underAddress) + "}"));
            case 2 -> List.of(tool("open", "{\"address\":" + q(base + "/named-by/under-url-" + n) + "}"), tool("open", "{\"url\":" + q(base + "/named-by/under-address-" + n) + "}"), tool("open", "{\"url\":" + q(base + "/nobody-named-" + n + "?d=" + CANARY) + "}"));
            default -> "Done.";
        });
        Librarian.Session s = Librarian.Session.open(store);
        new Librarian(store, drive, Librarian.person(), s).say("Read " + underUrl + " and " + underAddress + " for me.");
        List<String> told = new ArrayList<>();
        for (ObjectNode m : s.messages()) if ("tool".equals(m.path("role").asText())) told.add(m.path("content").asText().startsWith("source: ") ? "read" : m.path("content").asText().startsWith("NOT OPENED") ? "not opened" : m.path("content").asText());
        System.out.println("[review] D-271 open, by the name its address came under, from outside: " + outside + "; in the chat: " + told);
        assertEquals(List.of(true, true, true, false, false, false, false, true, false), List.copyOf(outside.values()), outside.toString());
        assertEquals(List.of("read", "read", "read", "read", "not opened"), told, "a page opened under either name gives the address it names; one nobody named is refused (the control)");
        assertTrue(asked.stream().noneMatch(a -> a.contains(CANARY)), asked.toString());
    }

    // ---- the search instrument's place (D-228)

    /**
     * The search instrument says where its answer came from, and which of its arguments are addresses, by the place's own tool: the
     * same tool it then runs, for every name a place has (its own, its other names, the owner's own search service, capitals, a place
     * that is none). A search never reports one tool's word and runs another.
     */
    @Test
    void theSearchInstrumentSpeaksForTheToolItRuns() throws Exception {
        List<String> ran = new ArrayList<>();
        Map<String, Tool> byName = new LinkedHashMap<>();
        List<String> tools = new ArrayList<>(Instruments.PLACES.values());
        tools.add(Instruments.CUSTOM_TOOL);
        for (String name : tools) byName.put(name, new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public ObjectNode parametersSchema(ObjectMapper j) { return j.createObjectNode(); }
            @Override public String execute(JsonNode args) { ran.add(name); return "answered by " + name; }
            @Override public boolean fromOutside(JsonNode args) { return (name.hashCode() & 1) == 0; }
            @Override public Set<String> addressArguments(JsonNode args) { return (name.hashCode() & 2) == 0 ? Set.of() : Set.of("query"); }
        });
        Instrument search = Instruments.search(byName);
        List<String> wheres = new ArrayList<>(Instruments.PLACES.keySet());
        wheres.addAll(Instruments.ALIASES.keySet());
        wheres.addAll(List.of("custom", "WEB", " Scholar ", "nowhere-at-all", ""));
        List<String> differs = new ArrayList<>();
        int answered = 0, both = 0;
        for (String where : wheres) {
            ObjectNode args = M.createObjectNode().put("where", where).put("query", "ferry");
            boolean saidOutside = search.fromOutside(args);
            Set<String> saidAddresses = search.addressArguments(args);
            ran.clear();
            try { search.run(args, new Researcher.Budget(0), () -> false); } catch (Exception e) { /* a place that needs more than words: no tool ran */ }
            Tool did = ran.isEmpty() ? null : byName.get(ran.get(0));
            if (did != null) { answered++; if (did.fromOutside(args)) both++; }
            // where no tool ran (a place that is none, a paid place with no key), nothing was asked; where one ran, the instrument spoke for that one
            if (did != null && (saidOutside != did.fromOutside(args) || !saidAddresses.equals(did.addressArguments(args)))) differs.add(where + ": said " + saidOutside + "/" + saidAddresses + ", ran " + did.name());
            if (did == null && where.equals("nowhere-at-all") && (saidOutside || !saidAddresses.isEmpty())) differs.add(where + ": a place that is none is said to answer from outside");
        }
        System.out.println("[review] D-228 the search instrument, " + wheres.size() + " names of places: a tool ran for " + answered + " (" + both + " of them from outside by the stand-in's word); said one thing and ran another: " + differs);
        assertTrue(answered >= 15 && both > 0 && both < answered, "the places were run, some from outside and some not (the instrument): " + answered + " / " + both);
        assertEquals(List.of(), differs);
    }

    // ---- an MCP server's answers: the keeper's word, read in one place (D-61, D-514)

    /**
     * Whether a keeper's MCP server's answers name sources of their own is the keeper's word, and it is read in one place for both
     * things that depend on it: what an answer gives the model to open, and what the model is told of the addresses in it. With no
     * library, no such server, or a list of servers that cannot be read, the answer is no: nothing is opened from such an answer.
     */
    @Test
    void theKeepersWordOnAServersLinksIsReadInOnePlace() throws Exception {
        McpServers.Server finds = new McpServers.Server(); finds.name = "finds"; finds.transport = "http"; finds.url = "http://127.0.0.1:1/mcp"; finds.links = true;
        McpServers.Server computes = new McpServers.Server(); computes.name = "computes"; computes.transport = "http"; computes.url = "http://127.0.0.1:1/mcp"; computes.links = false;
        McpServers.put(store, finds); McpServers.put(store, computes);
        Map<String, Boolean> said = new LinkedHashMap<>();
        said.put("a server whose keeper says it finds things outside", McpSources.links(store, "finds"));
        said.put("a server whose keeper has not said so", McpSources.links(store, "computes"));
        said.put("no such server", McpSources.links(store, "nobody"));
        said.put("no library", McpSources.links(null, "finds"));
        Files.writeString(McpServers.file(store), "{ this is no list of servers");
        said.put("a list of servers that cannot be read", McpSources.links(store, "finds"));
        System.out.println("[review] D-61, D-514 the keeper's word on a server's links: " + said);
        assertEquals(List.of(true, false, false, false, false), List.copyOf(said.values()), said.toString());
    }
}
