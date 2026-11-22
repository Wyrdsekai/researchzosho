package org.researchzosho.drive;

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
import org.researchzosho.librarian.ModelEntries;
import org.researchzosho.librarian.ModelPlaces;
import org.researchzosho.librarian.Setup;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression checks before 0.8.1 goes out, of a hosted model reached through OpenRouter: every request the program sends it for a
 * completion names the providers to leave out, whichever part of the program made the request and on whichever try; and the test
 * for "this host is OpenRouter", which decides whether the list is sent, is right for the real host and for names that look like it.
 * The router is a stand-in on the loopback, reached as a node's gate reaches the real one ({@link OpenRouterProvidersTest}), so the
 * client takes it for OpenRouter and no test asks the real one. Each test prints one line that begins {@code [check]} with what it
 * counted, and then asserts.
 */
class GuardChecksHostedTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String MODEL = "deepseek/deepseek-v4.1-flash";
    static final String READY = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ready\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}";

    @TempDir Path home;
    String realHome;
    HttpServer router; String drive, gate;
    /** What the test is doing now, and every completion body the stand-in had while it did. */
    volatile String doing = "";
    final List<String[]> had = new CopyOnWriteArrayList<>();   // {what was being done, the path asked, the body}
    /** What each thing done ended with, when it ended with a failure of its own. */
    final Map<String, String> threw = new LinkedHashMap<>();
    /** What the stand-in answers next, one a request: status, body, and a header's name and value or null; when empty, 200 and a word. */
    final Deque<String[]> next = new ArrayDeque<>();
    Function<String, String> fetch; LongSupplier now;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); Config.invalidate();
        fetch = OpenRouterProviders.FETCH; now = OpenRouterProviders.NOW;
        OpenRouterProviders.FETCH = url -> null;   // the live lists cannot be read: the copy that ships is used
        OpenRouterProviders.forget();
        DriveClient.OpenRouterModels.OVERRIDE = Map.of();
        router = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        router.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean completion = x.getRequestURI().getPath().endsWith("/chat/completions");
            if (completion) had.add(new String[]{doing, x.getRequestURI().getPath(), body});
            String[] answer;
            synchronized (next) { answer = completion && !next.isEmpty() ? next.poll() : null; }
            boolean streamed = completion && answer == null && body.contains("\"stream\":true");
            String said = !completion ? "{}" : answer != null ? answer[1] : streamed
                    ? "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"ready\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n" : READY;
            byte[] b = said.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", streamed ? "text/event-stream" : "application/json");
            if (answer != null && answer.length > 3 && answer[2] != null) x.getResponseHeaders().add(answer[2], answer[3]);
            x.sendResponseHeaders(!completion ? 404 : answer != null ? Integer.parseInt(answer[0]) : 200, b.length); x.getResponseBody().write(b); x.close();
        });
        router.start();
        gate = "http://127.0.0.1:" + router.getAddress().getPort() + Gated.PATH;
        drive = gate + "https/openrouter.ai/api/v1";
    }

    @AfterEach void down() {
        router.stop(0);
        DriveClient.streamHere(null);
        DriveClient.OpenRouterModels.OVERRIDE = null;
        OpenRouterProviders.FETCH = fetch; OpenRouterProviders.NOW = now; OpenRouterProviders.forget();
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    static ArrayNode hello() { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", "hi"); return m; }

    static ArrayNode aTool() {
        ArrayNode t = M.createArrayNode();
        ObjectNode f = t.addObject().put("type", "function").putObject("function").put("name", "done");
        f.putObject("parameters").put("type", "object").putObject("properties");
        return t;
    }

    /** A message with a picture in it, as the seeing model is asked. */
    static ArrayNode aPicture() {
        ArrayNode m = M.createArrayNode();
        ArrayNode content = m.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", "What does this picture show?");
        content.addObject().put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64,iVBORw0KGgo=");
        return m;
    }

    static List<String> ignored(String body) throws Exception { List<String> out = new ArrayList<>(); for (JsonNode s : M.readTree(body).path("provider").path("ignore")) out.add(s.asText()); return out; }

    interface Doing { void run() throws Exception; }

    /** Does one thing, with the stand-in's next answers; a failure of the thing itself is not this check's (what was sent is). */
    void doing(String what, List<String[]> answers, Doing it) {
        doing = what;
        synchronized (next) { next.clear(); next.addAll(answers); }
        try { it.run(); } catch (Exception | AssertionError failed) { threw.put(what, String.valueOf(failed)); }   // what the stand-in was sent is what is counted
        doing = "";
        synchronized (next) { next.clear(); }
    }

    static String[] answer(int status, String body) { return new String[]{String.valueOf(status), body}; }

    // ---- 8. hosted models

    /**
     * Expected: every request the program sends to OpenRouter for a completion carries the list of providers to leave out. Each place
     * a completion's body is built and sent is asked here to send one to the stand-in: a turn with tools and one of words, a decision
     * in words, a typed decision, the page check (of a text and of a picture), a picture shown to the seeing model, a streamed turn, a
     * stream that fails and is asked again without it, a turn answered 500 and sent again, one told to wait (429) and sent again, a
     * malformed tool call recovered in text mode, and the hello that setup and the Connect page send to a server a person names. The
     * control: the stand-in is taken for the router, it had a request for each of them, and the first one's list is the one that ships.
     */
    @Test
    void everyCompletionSentToTheRouterNamesTheProvidersToLeaveOut() throws Exception {
        assertTrue(new DriveClient(drive, MODEL).openRouter(), "the stand-in is taken for the router");
        List<String> list = OpenRouterProviders.ignored();
        assertTrue(list.size() >= 10 && list.contains("deepseek") && list.contains("moonshotai"), "the list that ships is in use: " + list);
        String parse = "{\"error\":{\"message\":\"Failed to parse tool call arguments as JSON\"}}";
        doing("a turn with tools", List.of(), () -> new DriveClient(drive, MODEL).chat(hello(), aTool(), 32, "auto"));
        doing("a turn of words", List.of(), () -> new DriveClient(drive, MODEL).chat(hello(), null, 32, "none"));
        doing("a decision in words", List.of(), () -> new DriveClient(drive, MODEL).classify(hello(), 8));
        doing("a typed decision", List.of(), () -> new DriveClient(drive, MODEL).postChat(M.createObjectNode().put("max_tokens", 4).<ObjectNode>set("messages", hello())));
        ContentJudge.use(null);   // the suite answers every content question with a stub (NoLiveChecks); here the check is asked of the drive, which is the stand-in
        try {
            doing("the page check of a text", List.of(), () -> ContentJudge.of(new DriveClient(drive, MODEL)).ask("A page about a ferry.", "Is this page about ferries?"));
            doing("the page check of a picture", List.of(), () -> ContentJudge.of(new DriveClient(drive, MODEL)).askImage("data:image/png;base64,iVBORw0KGgo=", "Does this picture show a ferry?"));
        } finally { ContentJudge.use(NoLiveChecks.NO); }
        doing("a picture shown to the seeing model", List.of(), () -> new DriveClient(drive, MODEL).classify(aPicture(), 64));
        doing("a streamed turn", List.of(), () -> { DriveClient.streamHere(s -> { }); try { new DriveClient(drive, MODEL).chat(hello(), aTool(), 32, "auto"); } finally { DriveClient.streamHere(null); } });
        doing("a stream that fails, asked again without it", List.<String[]>of(answer(500, "{\"error\":{\"message\":\"upstream closed the stream\"}}")), () -> { DriveClient.streamHere(s -> { }); try { new DriveClient(drive, MODEL).chat(hello(), null, 32, "none"); } finally { DriveClient.streamHere(null); } });
        doing("a turn answered 500, sent again", List.<String[]>of(answer(500, "{\"error\":{\"message\":\"busy\"}}")), () -> new DriveClient(drive, MODEL).chat(hello(), null, 32, "none"));
        doing("a turn told to wait (429), sent again", List.<String[]>of(new String[]{"429", "{\"error\":{\"message\":\"rate limited\"}}", "Retry-After", "1"}), () -> new DriveClient(drive, MODEL).chat(hello(), null, 32, "none"));
        doing("a malformed tool call, recovered in text mode", List.of(answer(500, parse), answer(500, parse), answer(500, parse), answer(500, parse), answer(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"name\\\":\\\"done\\\",\\\"arguments\\\":{}}\"},\"finish_reason\":\"stop\"}]}")),
                () -> new DriveClient(drive, MODEL).chat(hello(), aTool(), 32, "auto"));
        doing("the hello of setup and of the Connect page", List.of(), () -> Setup.liveProbe().chat(drive, MODEL, null));
        Map<String, int[]> by = new LinkedHashMap<>();   // what was done → {requests the stand-in had, of them without the list}
        List<String> without = new ArrayList<>();
        for (String[] h : had) {
            int[] n = by.computeIfAbsent(h[0], k -> new int[2]);
            n[0]++;
            if (!ignored(h[2]).equals(list)) { n[1]++; without.add(h[0] + ": " + (h[2].length() > 160 ? h[2].substring(0, 160) + "…" : h[2])); }
        }
        StringBuilder counted = new StringBuilder();
        by.forEach((what, n) -> counted.append(counted.length() == 0 ? "" : "; ").append(what).append(": ").append(n[0]).append(" sent, ").append(n[1]).append(" without the list"));
        // a body made for the status probe (Crews.driveState), which asks the real address and so is read here, not sent
        ObjectNode probe = DriveClient.hostedShape(M.createObjectNode().put("model", MODEL).put("max_tokens", 1024), "https://openrouter.ai/api/v1");
        System.out.println("[check] 8 completions sent to the router's stand-in, " + had.size() + " in all, " + without.size() + " without the list of providers to leave out. " + counted + ". The status probe's body, as it is shaped: " + (ignored(probe.toString()).equals(list) ? "with the list" : "without the list") + ". Ended with a failure of their own: " + threw);
        for (String what : List.of("a turn with tools", "a turn of words", "a decision in words", "a typed decision", "the page check of a text", "the page check of a picture", "a picture shown to the seeing model", "a streamed turn",
                "a stream that fails, asked again without it", "a turn answered 500, sent again", "a turn told to wait (429), sent again", "a malformed tool call, recovered in text mode", "the hello of setup and of the Connect page"))
            assertTrue(by.containsKey(what) && by.get(what)[0] >= 1, "the stand-in had a request for: " + what + " (the control); it had " + by.keySet());
        assertTrue(by.get("a stream that fails, asked again without it")[0] >= 2 && by.get("a turn answered 500, sent again")[0] >= 2 && by.get("a turn told to wait (429), sent again")[0] >= 2 && by.get("a malformed tool call, recovered in text mode")[0] >= 5, "each second try reached the stand-in too: " + counted);
        assertEquals(list, ignored(had.get(0)[2]), "a turn with tools names the providers to leave out (the control: the stand-in reads the list)");
        assertEquals(list, ignored(probe.toString()), "the status probe's body names them");
        assertEquals(List.of(), without, "a completion was sent to the router without the list of providers to leave out");
    }

    /**
     * Expected: the test for "this host is OpenRouter" says yes for the real host however its address is written, and no for a name
     * that only looks like it. A yes for another host hands it fields meant for the router and tells the person what the router's
     * providers are; a no for the real host sends it requests without the list of providers to leave out.
     */
    @Test
    void theTestForTheRoutersHostIsRightForTheRealHostAndForLookAlikes() throws Exception {
        Map<String, Boolean> expected = new LinkedHashMap<>();
        // the real host
        expected.put("https://openrouter.ai/api/v1", true);
        expected.put("https://openrouter.ai/api/v1/", true);
        expected.put("https://OpenRouter.AI/api/v1", true);
        expected.put("https://openrouter.ai:443/api/v1", true);
        expected.put("https://openrouter.ai./api/v1", true);   // the same host, written with the dot that ends a full name
        expected.put(gate + "https/openrouter.ai/api/v1", true);   // through a node's gate
        // names that look like it
        expected.put("https://notopenrouter.ai/api/v1", false);
        expected.put("https://my-openrouter.ai/api/v1", false);
        expected.put("https://openrouter.ai.example.org/api/v1", false);
        expected.put("https://openrouter.ai@example.org/api/v1", false);
        expected.put("https://example.org/openrouter.ai/api/v1", false);
        expected.put("https://example.org/api/v1?via=openrouter.ai", false);
        expected.put(gate + "https/notopenrouter.ai/api/v1", false);
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : expected.entrySet()) if (DriveClient.openRouter(e.getKey()) != e.getValue()) wrong.add(e.getKey() + " is taken " + (e.getValue() ? "for another host" : "for OpenRouter"));
        // what each mistake does. A look-alike: the person is told of the router's providers for a host that is not the router
        OpenRouterProviders.FETCH = url -> url.contains("/endpoints") ? "{\"data\":{\"id\":\"" + MODEL + "\",\"endpoints\":[{\"provider_name\":\"x\",\"tag\":\"baidu\"},{\"provider_name\":\"x\",\"tag\":\"fireworks\"}]}}" : null;
        OpenRouterProviders.forget();
        String saidOfALookAlike = ModelPlaces.said(new ModelEntries.Entry("other", "https://notopenrouter.ai/api/v1", MODEL, null, "", 0, ""));
        OpenRouterProviders.FETCH = url -> null;
        OpenRouterProviders.forget();
        // the real host with the dot: a completion goes to the stand-in, reached through the gate as before, and the list is counted
        List<String> list = OpenRouterProviders.ignored();
        doing("the real host written with its dot", List.of(), () -> new DriveClient(gate + "https/openrouter.ai./api/v1", MODEL).chat(hello(), null, 32, "none"));
        doing("the real host (the control)", List.of(), () -> new DriveClient(drive, MODEL).chat(hello(), null, 32, "none"));
        int withDot = 0, withDotWithout = 0, plain = 0, plainWithout = 0;
        for (String[] h : had) {
            if (h[0].startsWith("the real host written")) { withDot++; if (!ignored(h[2]).equals(list)) withDotWithout++; }
            else { plain++; if (!ignored(h[2]).equals(list)) plainWithout++; }
        }
        System.out.println("[check] 8 the test for the router's host, " + expected.size() + " addresses: " + wrong.size() + " wrong " + wrong + ". A completion for the real host written with its dot: " + withDot + " sent, " + withDotWithout
                + " without the list (the real host as usually written: " + plain + " sent, " + plainWithout + " without). What the person is told of a model at notopenrouter.ai: \"" + saidOfALookAlike + "\"");
        assertTrue(plain >= 1 && plainWithout == 0, "a completion for the real host reaches the stand-in with the list (the control)");
        assertTrue(DriveClient.openRouter("https://openrouter.ai/api/v1") && !DriveClient.openRouter("https://api.example.org/v1"), "the test says yes for the router and no for another host (the control)");
        assertEquals(List.of(), wrong, "the test for the router's host is wrong for an address");
        assertEquals(0, withDotWithout, "a completion for the real host, written with its dot, went without the list");
        assertEquals("", saidOfALookAlike, "the person is told of the router's providers for a host that is not the router");
    }
}
