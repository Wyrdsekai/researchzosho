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
import org.researchzosho.NoLiveChecks;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Judge;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.PageCheck;
import org.researchzosho.tools.Tool;
import org.researchzosho.tools.WebFetchTool;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the page check cannot run while the research model works: the check's requests carry the research drive's own temperature and
 * template settings, what the server did instead of answering is said, and a run whose check cannot answer for several pages in a row
 * stops with a plain statement of why and what to set, instead of leaving every page out and reading nothing. The stub judge here
 * answers nothing ({@link NoLiveChecks#NOTHING}), the case the suite's usual stub, which answers no, hides.
 */
class PageCheckCannotRunTest {

    static final ObjectMapper M = new ObjectMapper();

    @TempDir Path home;
    String realHome;

    @BeforeEach void scratchConfig() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());   // the settings below go to a scratch config, never the machine's own
        Config.invalidate();
    }

    @AfterEach void back() {
        System.setProperty("user.home", realHome);
        Config.invalidate();
        PageCheck.useGetter(null);
        ContentJudge.use(null);
    }

    @Test
    void theChecksRequestsCarryTheDrivesOwnTemperatureAndTemplateSettings() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        Judge judge = new Judge("m", body -> { bodies.add(body.deepCopy()); return DeclinesTest.judged(0.1); });
        judge.noul("placeholder", "Placeholder question?");
        assertEquals(0.0, bodies.get(0).path("temperature").asDouble(-1), "no setting: temperature 0");
        assertFalse(bodies.get(0).path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
        Config.set("RESEARCHZOSHO_TEMP", "none");
        Config.set("RESEARCHZOSHO_DRIVE_TEMPLATE_KWARGS", "{\"reasoning_effort\":\"low\"}");
        judge.noul("placeholder", "Placeholder question?");
        assertFalse(bodies.get(1).has("temperature"), "RESEARCHZOSHO_TEMP=none leaves the field out, as the research drive does: " + bodies.get(1));
        assertEquals("low", bodies.get(1).path("chat_template_kwargs").path("reasoning_effort").asText(), bodies.get(1).toString());
        assertFalse(bodies.get(1).path("chat_template_kwargs").path("enable_thinking").asBoolean(true), "thinking off on top");
        // the one-word question, to a server: the same settings
        List<String> sent = new CopyOnWriteArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            sent.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"no\"}}]}".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        s.start();
        try {
            Config.set("RESEARCHZOSHO_TEMP", "0.6");
            ArrayNode msgs = M.createArrayNode(); msgs.addObject().put("role", "user").put("content", "Placeholder question? Answer with one word: yes or no.");
            assertEquals("no", new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m").classify(msgs, 4));
            JsonNode body = M.readTree(sent.get(0));
            assertEquals(0.6, body.path("temperature").asDouble(), body.toString());
            assertEquals("low", body.path("chat_template_kwargs").path("reasoning_effort").asText(), body.toString());
        } finally { s.stop(0); }
    }

    @Test
    void whatTheServerDidInsteadOfAnsweringIsSaid() {
        ContentJudge.Reading thinking = new ContentJudge(null, m -> "Okay, the user wants").ask("placeholder", "Placeholder question?");
        assertFalse(thinking.judged());
        assertEquals("the one-word question: the model answered \"Okay, the user wants\" instead of yes or no", thinking.why());
        ContentJudge.Reading empty = NoLiveChecks.NOTHING.ask("placeholder", "Placeholder question?");
        assertEquals("the one-word question: the server gave an empty answer", empty.why());
        ContentJudge.Reading refused = new ContentJudge(new Judge("m", body -> { throw new IllegalStateException("HTTP 400: temperature is not supported"); }), null).ask("placeholder", "Placeholder question?");
        assertEquals("the typed question: the server answered: HTTP 400: temperature is not supported", refused.why());
    }

    @Test
    void aRunWhoseCheckCannotAnswerStopsWithAPlainStatement() throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        PageCheck.useGetter((url, timeout, lists) -> new Fetch.Result(url, 200, ("<html><head><title>Placeholder</title></head><body><main><p>"
                + "An ordinary placeholder page about the placeholder gears. ".repeat(10) + "</p></main></body></html>").getBytes(StandardCharsets.UTF_8), "text/html"));
        List<String> logged = new CopyOnWriteArrayList<>();
        Researcher.Tools tools = new Researcher.Tools() {
            @Override public List<Tool> web(String focus) { return web(focus, ContentPolicy.defaults()); }
            @Override public List<Tool> web(String focus, ContentPolicy policy) { return List.of(new WebFetchTool().focus(focus).policy(policy)); }
            @Override public BooleanSupplier exhausted() { return () -> false; }
        };
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                if (names(tools).contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                int turn = assistantTurns(history) + 1;
                return turn <= 6 ? call("web_fetch", M.createObjectNode().put("url", "https://example.org/placeholder-" + turn)) : call("done", M.createObjectNode().put("summary", "read"));
            }
        };
        drive.criticWantsMore = false;
        Researcher r = new Researcher(drive, drive, tools, logged::add, 1, store);
        r.contentJudge(NoLiveChecks.NOTHING);
        Researcher.CannotCheck stop = assertThrows(Researcher.CannotCheck.class,
                () -> r.run(new Researcher.Ask("How were the placeholder gears cut?", "depth", 40, List.of("how were the gears cut?")), ""));
        assertTrue(stop.getMessage().startsWith("The research stopped, because the page check cannot run on this model server."), stop.getMessage());
        assertTrue(stop.getMessage().contains("What the server did: the one-word question: the server gave an empty answer."), stop.getMessage());
        assertTrue(stop.getMessage().contains("set RESEARCHZOSHO_TEMP=none") && stop.getMessage().contains("RESEARCHZOSHO_DRIVE_TEMPLATE_KWARGS"), stop.getMessage());
        assertEquals(3, r.leftOut().size(), "it stopped after the third page, not after every page was left out: " + r.leftOut());
        assertTrue(logged.stream().anyMatch(l -> l.startsWith("stopped: The research stopped")), logged.toString());
    }

    @Test
    void theJobsResultIsTheStatement() throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        Jobs jobs = new Jobs(store, (job, drive) -> { throw new Jobs.Failure("The research stopped, because the page check cannot run on this model server."); }, List.of(""), 1);
        String id = jobs.submit("research", "", (ObjectNode) M.readTree("{\"question\":\"Placeholder question about the placeholder topic\"}"));
        jobs.start();
        try {
            long t0 = System.currentTimeMillis();
            while (!"failed".equals(jobs.get(id).path("state").asText()) && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(25);
        } finally { jobs.stop(); }
        JsonNode j = jobs.get(id);
        assertEquals("failed", j.path("state").asText());
        assertEquals("The research stopped, because the page check cannot run on this model server.", j.path("result").asText(), "the plain statement, not a stack trace");
    }
}
