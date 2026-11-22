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

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.concurrent.atomic.AtomicInteger;
import org.researchzosho.drive.Declined;
import org.researchzosho.drive.DriveClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every way a research run is started, with an address the person typed into the question (the second reading of 0.8.1: a reader
 * of the code said the explorer's entry point takes the person's words from every run). Each way files its run as the program
 * files it, the run's model tries the address, and a listener on the loopback counts what arrives. A question the library stored
 * (run again from the questions page, or taken by the nightly explorer) is nobody's words of now, and gives its run no address.
 */
class GuardReviewRunsTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String CANARY = "QX7731MOOR";

    @TempDir Path tmp;
    String realHome;
    HttpServer listener, probe;
    LibrarianDaemon service;
    String base, n;
    final List<String> arrived = new CopyOnWriteArrayList<>();
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        n = Long.toHexString(System.nanoTime());
        listener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        listener.createContext("/", x -> {
            arrived.add(x.getRequestURI().toString());
            byte[] b = "<html><head><title>A page</title></head><body><p>The ferry ran from 1911 to 1968.</p></body></html>".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        listener.start();
        base = "http://127.0.0.1:" + listener.getAddress().getPort();
        GivenAddressesTest.loopback(true);
        ContentJudge.use(new ContentJudge(null, m -> "no"));
        store = new LibraryStore(tmp.resolve("home").resolve("researchzosho-library")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        probe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        probe.createContext("/", x -> {
            byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"no\"}}]}".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        probe.start();
        Patrons.setDefault(store, Patrons.Level.write);
    }

    @AfterEach void down() throws Exception {
        if (service != null) service.stop();
        probe.stop(0); listener.stop(0);
        ContentJudge.use(null); GivenAddressesTest.loopback(false);
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    static String q(String s) { return M.valueToTree(s).toString(); }
    List<String> with(String what) { return arrived.stream().filter(a -> a.contains(what)).toList(); }

    /** Every run the service has is waited for; the states of all its jobs. */
    Map<String, String> allDone() throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        Map<String, String> states = new LinkedHashMap<>();
        for (long until = System.currentTimeMillis() + 120_000; System.currentTimeMillis() < until; Thread.sleep(150)) {
            JsonNode list = M.readTree(c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/v1/jobs")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            states.clear();
            for (JsonNode j : list.findValues("job_id")) {
                JsonNode job = M.readTree(c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/v1/jobs/" + j.asText())).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
                states.put(j.asText(), job.path("state").asText());
            }
            if (!states.isEmpty() && states.values().stream().allMatch(s -> List.of("done", "failed", "stopped").contains(s))) return states;
        }
        return states;
    }

    /**
     * A run whose model has nobody left to serve it (every provider of it is in a place the library keeps away from) ends with that
     * sentence, the person's to read, whether the first to meet it is a reader of the run or its writer; it does not end as a decline
     * of the model's, and it is not asked again reader after reader.
     */
    @Test
    void aRunWhoseModelNobodyServesEndsWithThatSentence() throws Exception {
        String sentence = "Every provider that serves the-model through OpenRouter is based or runs in a place this library keeps away from, so nothing was sent.";
        AtomicInteger askedOf = new AtomicInteger();
        Researcher.Drive nobody = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { askedOf.incrementAndGet(); throw new DriveClient.NotServed(sentence); }
            @Override public String classify(ArrayNode messages, int maxTokens) { askedOf.incrementAndGet(); throw new DriveClient.NotServed(sentence); }
            @Override public int contextWindow() { return 32_000; }
        };
        Researcher.Ask ask = new Researcher.Ask("When did the ferry across Kestrel Sound stop running?", "broad", 30, List.of("when did it stop?", "why did it stop?", "who ran it?"), "both", List.of());
        RuntimeException ended = assertThrows(RuntimeException.class, () -> new Researcher(nobody, nobody, HostileModelTest.fetches(), s -> { }, 3, store).run(ask, ""));
        System.out.println("[review] D-2364 a run whose model nobody serves ended with " + ended.getClass().getSimpleName() + ": " + ended.getMessage() + " (the model was asked " + askedOf.get() + " time(s) for three readers)");
        assertTrue(ended instanceof DriveClient.NotServed, "it ends as what it is, not as a decline or a failure of another kind: " + ended);
        assertEquals(sentence, ended.getMessage());
        assertFalse(Declined.class.isAssignableFrom(DriveClient.NotServed.class), "a model nobody serves is no decline");
    }

    @Test
    void anAddressThePersonTypedIntoAQuestionIsOpenedOnEveryWayARunIsStarted() throws Exception {
        // the address each way's question holds, and the ones no run may open
        Map<String, String> typed = new LinkedHashMap<>();
        for (String way : List.of("protocol", "mcp", "terminal", "webform", "chat", "queued-1", "queued-2")) typed.put(way, base + "/typed-" + way + "-" + n);
        String terminalTyped = "127.0.0.1:" + listener.getAddress().getPort() + "/typed-terminal-" + n;   // typed without its scheme, as a person types a machine of the house
        String stored = base + "/in-a-stored-question-" + n + "?d=" + CANARY, nightly = base + "/in-the-explorers-question-" + n + "?d=" + CANARY, modelWrote = base + "/the-chats-model-wrote-" + n + "?d=" + CANARY, made = base + "/nobody-" + n + "?d=" + CANARY;
        List<String> all = new ArrayList<>(typed.values());
        all.addAll(List.of(stored, nightly, modelWrote, made));
        // the run's model tries every one of them, each run: what was given by then is opened, the rest refused
        HostileModelTest.Scripted model = new HostileModelTest.Scripted(t -> t.n() <= all.size() ? HostileModelTest.call("web_fetch", "{\"url\":" + q(all.get(t.n() - 1)) + "}") : HostileModelTest.call("done", "{\"summary\":\"read\"}"), "The ferry ran from 1911 to 1968.");
        service = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + probe.getAddress().getPort(), "scripted", -1);
        service.researcherFactory = d -> new Researcher(model, model, HostileModelTest.fetches(), s -> { }, 1, store);
        Map<String, String> outcome = new LinkedHashMap<>();

        // first the two that must give nothing: a question the library stored, run from the questions page; and the nightly explorer's
        ObjectNode fromThePage = M.createObjectNode().put("question", "Is the crossing record at " + stored + " complete?").put("mode", "broad").put("sources", "both").put(LibraryProtocol.STORED_QUESTION, true);
        fromThePage.putArray("sub_questions").add("is it complete?");
        service.research(fromThePage, Librarian.person(), new LibraryProtocol.Way("explorer-line", "web", true));
        allDone();
        outcome.put("a stored question, from the questions page", with("in-a-stored-question").isEmpty() ? "not opened" : "OPENED");
        Researcher runner = new Researcher(model, model, HostileModelTest.fetches(), s -> { }, 1, store);
        assertNotNull(Researcher.forExplorer(store, runner, new ContentJudge(null, m -> "no"), "Who keeps the record listed at " + nightly + " today?", List.of("who keeps it?"), 30, 0, "", "crew:explorer"));
        outcome.put("a stored question, the nightly explorer", with("in-the-explorers-question").isEmpty() ? "not opened" : "OPENED");

        // the protocol, as a program posts it
        assertTrue(with("typed-protocol").isEmpty(), "nothing typed has arrived yet (the instrument)");
        HttpResponse<String> posted = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/v1/research")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":" + q("What does " + typed.get("protocol") + " say about the ferry?") + ",\"sub_questions\":[\"what does it say?\"]}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, posted.statusCode(), posted.body());
        allDone();
        outcome.put("the protocol", with("typed-protocol").isEmpty() ? "NOT OPENED" : "opened");

        // an MCP host's library_research: the protocol's own method, which files the run for the service to take
        new LibraryProtocol(store).research(M.readTree("{\"question\":" + q("What does " + typed.get("mcp") + " say about the ferry?") + ",\"sub_questions\":[\"what does it say?\"]}"));
        allDone();
        outcome.put("MCP library_research", with("typed-mcp").isEmpty() ? "NOT OPENED" : "opened");

        // the terminal: researchzosho research ask "…", the address typed without its scheme
        assertEquals(0, LibrarianCli.research(store, new String[]{"librarian", "research", "ask", "What does " + terminalTyped + " say about the ferry?"}));
        allDone();
        outcome.put("the terminal's research ask", with("typed-terminal").isEmpty() ? "NOT OPENED" : "opened");

        // the web page's form
        HttpResponse<String> form = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/research")).header("Content-Type", "application/x-www-form-urlencoded")
                .header("Sec-Fetch-Site", "same-origin").header("Origin", "http://127.0.0.1:" + service.port())
                .POST(HttpRequest.BodyPublishers.ofString("question=" + URLEncoder.encode("What does " + typed.get("webform") + " say about the ferry?", StandardCharsets.UTF_8) + "&mode=broad&told=1")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(form.statusCode() == 200 || form.statusCode() == 303, form.statusCode() + " " + form.body().substring(0, Math.min(300, form.body().length())));
        allDone();
        outcome.put("the web page's form", with("typed-webform").isEmpty() ? "NOT OPENED" : "opened");

        // the chat: the model files the run with a question of its own (an address of its own in it, written without a scheme: one
        // written with its scheme is refused before any run is put to the person); the person's words of that turn give the run its addresses
        var chatModel = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\":" + q("Why did the Kestrel Sound ferry close? The summary kept at " + modelWrote.replace("http://", "") + " says more.") + ",\"sub_questions\":[\"why did it close?\"]}"),
                LibrarianChatTest.say("It waits for your answer.")), new ArrayList<>());
        Librarian chat = new Librarian(store, chatModel, Librarian.person(), Librarian.Session.open(store));
        String held = chat.say("Find out why the Kestrel Sound ferry closed, from the board's minutes at " + typed.get("chat"));
        String yes = chat.say("yes");
        assertTrue(yes.startsWith("Done."), "the chat put the run to the person and filed it on their yes: " + held + " / " + yes);
        allDone();
        outcome.put("the chat", with("typed-chat").isEmpty() ? "NOT OPENED" : "opened");

        // two questions filed one after the other: the second waits its turn in the queue
        for (String w : List.of("queued-1", "queued-2")) new LibraryProtocol(store).research(M.readTree("{\"question\":" + q("What does " + typed.get(w) + " say about the ferry?") + ",\"sub_questions\":[\"what does it say?\"]}"));
        Map<String, String> states = allDone();
        outcome.put("a question that waited in the queue", with("typed-queued-2").isEmpty() ? "NOT OPENED" : "opened");

        System.out.println("[review] D-1312, D-1324 an address typed into a research question, by the way the run was started: " + outcome + "; requests for an address nobody typed: " + with(CANARY).size() + "; the runs: " + states.values());
        for (Map.Entry<String, String> e : outcome.entrySet()) assertEquals(e.getKey().startsWith("a stored question") ? "not opened" : "opened", e.getValue(), e.getKey());
        assertEquals(List.of(), with(CANARY), "a run opened an address nobody typed: a stored question's, the chat's model's, or a made-up one");
        assertFalse(states.isEmpty() || states.containsValue("failed"), "every run ended well: " + states);
    }
}
