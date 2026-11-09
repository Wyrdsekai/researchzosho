package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chat's own commands go one way for both fronts ({@link ChatCommands}), every tool the chat is given has a dispatch, and the research
 * form sends the goal. Found by the surfaces audit of 2026-11-07: library_tell and library_dossier were offered to the model and ended at
 * "no tool named"; the terminal refused /sources, /patents and /video; the page had no /new, /runs, /sessions, /resume or /share; the
 * form never sent a goal.
 */
class ChatCommandsTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    String realHome;
    BiFunction<String, String, List<String>> leads;

    @BeforeEach void scratch() throws Exception {
        QuietHome.quiet(tmp.resolve("home"));
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        leads = LibraryProtocol.LEADS; LibraryProtocol.LEADS = (subject, text) -> List.of();   // what a told account raises is the model's reading; none here
    }
    @AfterEach void back() { System.setProperty("user.home", realHome); Config.invalidate(); LibraryProtocol.LEADS = leads; }

    LibraryStore library() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Finding f = new Finding("F-0001-gears", "The gears were cut by hand with a file", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "model:test", "2026-09-13T00:00:00Z", "2026-09-13", Finding.Volatility.slow, "",
                List.of(new Finding.Source("https://example.org/gears", "n/a", "the gears")), List.of(), null, "The gears were cut by hand; tooth spacing varies by 0.3 mm.\n");
        store.write(f);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    /** A front that stands for the terminal: it opens and resumes conversations in the keeper's own folder. */
    static ChatCommands.Front terminal(LibraryStore store, Librarian.Session[] current) {
        return new ChatCommands.Front() {
            @Override public Librarian.Session session() { return current[0]; }
            @Override public String open() throws IOException { current[0] = Librarian.Session.open(store); return current[0].id; }
            @Override public Librarian.Session resume(String id) throws IOException { Librarian.Session s = Librarian.Session.resume(store, id); if (s != null) current[0] = s; return s; }
            @Override public boolean atTheTerminal() { return true; }
            @Override public String helpExtra() { return "the arrow keys go through what you typed"; }
        };
    }

    @Test
    void everyToolTheChatCanBeGivenHasADispatch() throws Exception {
        LibraryStore store = library();
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.say("x")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        List<String> missing = new ArrayList<>();
        for (String name : Librarian.allToolNames()) {
            String r = lib.call(name, M.createObjectNode());
            if (r.startsWith("ERROR: no tool named")) missing.add(name);
        }
        assertTrue(missing.isEmpty(), "offered to the model and not dispatched: " + missing);
        assertTrue(Librarian.allToolNames().containsAll(List.of("library_tell", "library_dossier", "library_explain", "library_perspectives", "library_established", "library_serials", "library_export")), Librarian.allToolNames().toString());
    }

    @Test
    void theChatFilesWhatThePersonTellsAndReadsTheDossier() throws Exception {
        LibraryStore store = library();
        List<ArrayNode> seen = new ArrayList<>();
        var drive = LibrarianChatTest.scripted(List.of(
                LibrarianChatTest.tool("library_tell", "{\"subject\":\"Ada Example\",\"text\":\"She ran the workshop from 1990 to 2005 and had the gears cut by hand.\"}"),
                LibrarianChatTest.tool("library_dossier", "{\"subject\":\"Ada Example\"}"),
                LibrarianChatTest.say("noted: the workshop years, 1990 to 2005.")), seen);
        Librarian.Session s = Librarian.Session.open(store);
        Librarian lib = new Librarian(store, drive, Librarian.person(), s);
        String reply = lib.say("Ada Example ran the workshop from 1990 to 2005 and had the gears cut by hand.");
        assertTrue(reply.startsWith("noted:"), reply);
        List<String> results = new ArrayList<>();
        for (ObjectNode m : s.messages()) if ("tool".equals(m.path("role").asText())) results.add(m.path("content").asText());
        assertEquals(2, results.size(), results.toString());
        assertFalse(results.get(0).contains("no tool named"), results.get(0));
        assertTrue(results.get(0).contains("\"noted\"") && results.get(0).contains("\"class\":\"told\""), "library_tell filed the account: " + results.get(0));
        assertFalse(results.get(1).contains("no tool named"), results.get(1));
        assertTrue(results.get(1).contains("Ada Example"), "library_dossier read it back: " + results.get(1));
        boolean told = false;
        for (Finding f : store.scanFindings().findings()) if (f.body().contains("ran the workshop from 1990 to 2005")) told = true;
        assertTrue(told, "the told claim is on the shelves");
    }

    @Test
    void theTerminalChatTakesEveryCommandThePageTakes() throws Exception {
        LibraryStore store = library();
        Librarian.Session[] current = {Librarian.Session.open(store)};
        ChatCommands.Front front = terminal(store, current);
        Patrons.Patron me = Patrons.Patron.PERSON;
        String sources = ChatCommands.reply(store, me, "terminal", "/sources", false, front);
        assertNotNull(sources); assertFalse(sources.contains("not one of the chat's commands"), sources);
        assertTrue(sources.contains("No sources") || sources.contains("S-"), "the sources list, from the terminal: " + sources);
        assertEquals("Type /video saw and what you remember hearing or seeing, and the library finds the moment in the videos it has read.", ChatCommands.reply(store, me, "terminal", "/video saw", false, front));
        String patents = ChatCommands.reply(store, me, "terminal", "/patents pool", false, front);
        assertNotNull(patents); assertTrue(patents.toLowerCase().contains("pool"), "the patents field answers at the terminal: " + patents);
        String help = ChatCommands.reply(store, me, "terminal", "/help", false, front);
        for (String word : List.of("/sources", "/runs", "/new", "/sessions", "/resume", "/share", "/video", "/patents", "/interview", "the arrow keys")) assertTrue(help.contains(word), word + " in /help: " + help);
        assertEquals("That is not one of the chat's commands. /help lists them.", ChatCommands.reply(store, me, "terminal", "/dance", false, front));
        assertTrue(ChatCommands.reply(store, me, "terminal", "/interview", false, front).startsWith("Whom shall I interview you about?"));
        assertEquals("No research run is being followed in this conversation.", ChatCommands.reply(store, me, "terminal", "/runs", false, front));
        String first = current[0].id;
        ObjectNode said = M.createObjectNode(); said.put("role", "user"); said.put("content", "hello"); current[0].append(said);   // a conversation with a turn in it, as when a person says /new
        String opened = ChatCommands.reply(store, me, "terminal", "/new", false, front);
        assertTrue(opened.startsWith("A fresh conversation, C-"), opened);
        assertNotEquals(first, current[0].id, "the front opened a new one");
        assertTrue(ChatCommands.reply(store, me, "terminal", "/resume nope", false, front).startsWith("No conversation of yours has the id nope."));
        assertTrue(ChatCommands.reply(store, me, "terminal", "/resume " + first, false, front).startsWith("Continuing " + first));
        assertEquals(first, current[0].id);
        assertNull(ChatCommands.reply(store, me, "terminal", "how were the gears cut?", false, front), "a plain line is the model's");
        assertNull(ChatCommands.reply(store, me, "terminal", "yes", true, front), "while the library waits for an answer, a plain line is that answer");
    }

    @Test
    void thePageTakesNewRunsSessionsResumeAndShare() throws Exception {
        LibraryStore store = library();
        Librarian.Session s = Librarian.Session.open(store);
        Patrons.Patron me = Patrons.Patron.PERSON;
        Pages.chatPost(null, store, me, Map.of("session", s.id, "say", "/runs"));
        assertEquals("No research run is being followed in this conversation.", CareerInterviewTest.lastReply(store, s.id));
        Pages.chatPost(null, store, me, Map.of("session", s.id, "say", "/sessions"));
        assertTrue(CareerInterviewTest.lastReply(store, s.id).contains(s.id + "  "), CareerInterviewTest.lastReply(store, s.id));
        Librarian.Session fresh = Pages.chatPost(null, store, me, Map.of("session", s.id, "say", "/new"));
        assertNotNull(fresh); assertNotEquals(s.id, fresh.id, "the page is told to open the fresh one");
        assertTrue(CareerInterviewTest.lastReply(store, s.id).startsWith("A fresh conversation, " + fresh.id), CareerInterviewTest.lastReply(store, s.id));
        Librarian.Session back = Pages.chatPost(null, store, me, Map.of("session", fresh.id, "say", "/resume " + s.id));
        assertEquals(s.id, back.id);
        Librarian.Session same = Pages.chatPost(null, store, me, Map.of("session", s.id, "say", "/share status"));
        assertEquals(s.id, same.id);
        assertTrue(CareerInterviewTest.lastReply(store, s.id).contains("off") || CareerInterviewTest.lastReply(store, s.id).contains("share"), CareerInterviewTest.lastReply(store, s.id));
        Pages.chatPost(null, store, me, Map.of("session", s.id, "say", "/video saw"));
        assertTrue(CareerInterviewTest.lastReply(store, s.id).startsWith("Type /video saw"), CareerInterviewTest.lastReply(store, s.id));
        // a conversation the page opened and nothing was said in yet: its first turn goes to it by its id, not to the latest one
        Thread.sleep(1100);
        Librarian.Session empty = Librarian.Session.open(store, me);
        Librarian.Session shown = Pages.chatPost(null, store, me, Map.of("session", empty.id, "say", "/runs"));
        assertEquals(empty.id, shown.id);
        assertEquals("No research run is being followed in this conversation.", CareerInterviewTest.lastReply(store, empty.id), "the turn is in the new conversation");
        assertTrue(CareerInterviewTest.lastReply(store, s.id).startsWith("Type /video saw"), "and the previous conversation did not get it");
        Pages.chatPost(null, store, me, Map.of("session", s.id, "say", "/help"));
        String help = CareerInterviewTest.lastReply(store, s.id);
        for (String word : List.of("/sources", "/runs", "/new", "/share", "/video")) assertTrue(help.contains(word), word + " in the page's /help: " + help);
        assertFalse(help.contains("arrow keys"), "the terminal's keys are the terminal's: " + help);
    }

    static HttpResponse<String> post(HttpClient c, String url, String origin, String form) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        if (origin != null) b.header("Origin", origin);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    @Test
    void thePageIsToldWhichConversationToOpenAndTheFormSendsTheGoal() throws Exception {
        LibraryStore store = library();
        // a stand-in model that answers "ok" to everything: the Research page files nothing while no model answers
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", x -> { byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        model.start();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "local-model", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store, Patrons.Patron.PERSON);
            Pages.chatReplied(s, "hello", "Hello.");   // a conversation with a turn in it
            // the page's script: the reply names the conversation a command opened, and the script goes there
            HttpResponse<String> scripted = post(c, base + "/chat", base, "page=1&session=" + enc(s.id) + "&say=" + enc("/new"));
            assertEquals(200, scripted.statusCode(), scripted.body());
            assertTrue(scripted.body().contains("data-open-session=\"C-"), scripted.body());
            assertTrue(Pages.CHAT_PAGE_JS.contains("data-open-session"), "the script reads the marker");
            // without the script: the plain form lands on the conversation that was opened
            HttpResponse<String> plain = post(c, base + "/chat", base, "session=" + enc(s.id) + "&say=" + enc("/new"));
            assertEquals(303, plain.statusCode(), plain.body());
            assertTrue(plain.headers().firstValue("Location").orElse("").startsWith("/chat?session=C-"), plain.headers().map().toString());
            HttpResponse<String> runs = post(c, base + "/chat", base, "session=" + enc(s.id) + "&say=" + enc("/runs"));
            assertEquals("/", runs.headers().firstValue("Location").orElse(""), "a reply in the same conversation goes back to the chat");
            // the research form carries the goal, and the run it files carries it too
            String form = c.send(HttpRequest.newBuilder(URI.create(base + "/research")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(form.contains("<select name=\"goal\">") && form.contains("<option value=\"home\">") && form.contains("<option value=\"prior-art\">") && !form.contains("<option value=\"generic\">"), form);
            HttpResponse<String> sent = post(c, base + "/research", base, "question=" + enc("Which flats are for rent in Lyon under 900 euros a month?") + "&mode=broad&sources=both&goal=home&content_told=1&told=1");
            assertEquals(200, sent.statusCode(), sent.body());
            assertTrue(sent.body().contains("J-"), "the run was filed: " + sent.body().substring(Math.max(0, sent.body().indexOf("<main"))).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " "));
            Jobs jobs = new Jobs(store, j -> "");
            String goal = "";
            for (ObjectNode j : jobs.active()) goal = j.path("args").path("goal").asText("");
            if (goal.isEmpty()) for (ObjectNode j : jobs.recent(5, null)) goal = j.path("args").path("goal").asText("");
            assertEquals("home", goal, "the goal the form sent is on the run");
        } finally { d.stop(); model.stop(0); }
    }
}
