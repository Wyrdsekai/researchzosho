package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Gated;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the node's model gate passes and counts (0.8.0 review, round A): a reply cut short (by the caller, or by the server) is counted
 * from what passed; only the calls the program itself makes are passed, a call that costs only as part of a use of the library that
 * calls; and an answer streamed without its usage is counted from its text, not from its framing. Stand-in servers only.
 */
class GateCountingTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    AccountStore store; NodeGates gates; HttpServer up;
    final List<String> set = new ArrayList<>();

    void set(String key, String value) throws Exception { Config.set(key, value); set.add(key); }

    @AfterEach void stop() throws Exception {
        for (String k : set) Config.set(k, "");
        NodeGates.LANES = true;
        if (gates != null) gates.close();
        if (store != null) store.close();
        if (up != null) up.stop(0);
    }

    List<AccountStore.Usage> rows(String use) throws Exception { return store.usageOfUse(use); }
    long micros(String use) throws Exception { return rows(use).stream().mapToLong(AccountStore.Usage::micros).sum(); }

    AccountStore.Use use(String account) throws Exception {
        AccountStore.Use u = new AccountStore.Use(AccountStore.id("use_"), account, "lib_" + account, SpendPurse.CHAT, "", "operator", "", 0, 0, -1, -1, "open", "", System.currentTimeMillis(), 0, 0);
        store.addUse(u);
        return u;
    }

    /** The model server's address as the library {@code libraryId} is given it. */
    String gateFor(String upstream, String libraryId) {
        Map<String, String> env = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", upstream));
        gates.rewrite(env, libraryId);
        return env.get("RESEARCHZOSHO_DRIVE");
    }

    void open(Path tmp, String upstream, String model, String prices) throws Exception {
        set("RESEARCHZOSHO_DRIVE", upstream); set("RESEARCHZOSHO_MODEL", model);
        set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), prices);
        NodeGates.LANES = false;
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        gates = NodeGates.of(store);
    }

    static final String CHAT = "{\"model\":\"stand-in\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @Test
    void aReplyCutShortIsCountedFromWhatPassed(@TempDir Path tmp) throws Exception {
        up = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        up.createContext("/", x -> {
            x.getRequestBody().readAllBytes();
            x.getResponseHeaders().set("Content-Type", "text/event-stream");
            x.sendResponseHeaders(200, 0);
            OutputStream o = x.getResponseBody();
            try {
                o.write("data: {\"choices\":[{\"delta\":{\"content\":\"the first piece of the answer\"}}]}\n\n".getBytes(StandardCharsets.UTF_8)); o.flush();
                Thread.sleep(1500);
                for (int i = 0; i < 50; i++) { o.write(("data: {\"choices\":[{\"delta\":{\"content\":\"word " + i + "\"}}]}\n\n").getBytes(StandardCharsets.UTF_8)); o.flush(); }
                o.write("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100000,\"completion_tokens\":1000}}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); o.flush();
            } catch (Exception e) { /* the gate went away */ }
            x.close();
        });
        up.start();
        String upstream = "http://127.0.0.1:" + up.getAddress().getPort();
        open(tmp, upstream, "stand-in", "3/15");

        // read to its end: counted from the usage it says
        AccountStore.Use whole = use("acct_whole");
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(gateFor(upstream, whole.library()) + "/v1/chat/completions")).header(Gated.USE, whole.id()).POST(HttpRequest.BodyPublishers.ofString(CHAT)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        Thread.sleep(500);
        assertEquals(315_000, micros(whole.id()));

        // the caller goes away after the first piece (a stopped run, a time limit): counted from what passed, marked estimated
        AccountStore.Use cut = use("acct_cut");
        HttpClient own = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<InputStream> s = own.send(HttpRequest.newBuilder(URI.create(gateFor(upstream, cut.library()) + "/v1/chat/completions")).header(Gated.USE, cut.id()).POST(HttpRequest.BodyPublishers.ofString(CHAT)).build(), HttpResponse.BodyHandlers.ofInputStream());
        s.body().read(new byte[20]);
        s.body().close();
        own.close();
        Thread.sleep(4000);
        assertEquals(1, rows(cut.id()).size(), "the cut reply is counted: " + rows(cut.id()));
        AccountStore.Usage counted = rows(cut.id()).get(0);
        assertTrue(counted.estimated() && counted.tokensIn() > 0 && counted.micros() > 0, counted.toString());
    }

    @Test
    void aReplyTheServerBreaksOffIsCountedWithTheUsageItSaid(@TempDir Path tmp) throws Exception {
        ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!ss.isClosed()) {
                try (Socket so = ss.accept()) {
                    InputStream in = so.getInputStream();
                    StringBuilder head = new StringBuilder();
                    while (!head.toString().endsWith("\r\n\r\n")) head.append((char) in.read());
                    int len = 0;
                    for (String l : head.toString().split("\r\n")) if (l.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(l.substring(15).strip());
                    in.readNBytes(len);
                    OutputStream o = so.getOutputStream();
                    String ev = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":100000,\"output_tokens\":1}}}\n\n";
                    o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(ev.getBytes(StandardCharsets.UTF_8).length) + "\r\n" + ev + "\r\n").getBytes(StandardCharsets.UTF_8));
                    o.flush();
                    Thread.sleep(300);
                    so.setSoLinger(true, 0);   // reset: the provider's connection dropped mid-answer
                } catch (Exception e) { /* next */ }
            }
        });
        try {
            String upstream = "http://127.0.0.1:" + ss.getLocalPort();
            open(tmp, upstream, "stand-in", "3/15");
            AccountStore.Use u = use("acct_broken");
            try { c.send(HttpRequest.newBuilder(URI.create(gateFor(upstream, u.library()) + "/v1/messages")).header(Gated.USE, u.id()).POST(HttpRequest.BodyPublishers.ofString(CHAT)).build(), HttpResponse.BodyHandlers.ofString()); }
            catch (Exception e) { /* the caller sees the break */ }
            Thread.sleep(1000);
            assertTrue(micros(u.id()) >= 300_000, "the 100,000 tokens it read are counted: " + rows(u.id()));
        } finally { ss.close(); }
    }

    @Test
    void onlyTheCallsTheProgramMakesPassAndACallThatCostsOnlyUnderAUseOfTheLibraryThatCalls(@TempDir Path tmp) throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        up = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        up.createContext("/", x -> {
            x.getRequestBody().readAllBytes();
            seen.add(x.getRequestMethod() + " " + x.getRequestURI().getPath());
            byte[] b = "{\"id\":\"x\",\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        up.start();
        String upstream = "http://127.0.0.1:" + up.getAddress().getPort();
        open(tmp, upstream, "stand-in", "1/2");
        set("RESEARCHZOSHO_API_KEY", "the-operators-model-key");
        AccountStore.Use ann = use("acct_ann");
        String annGate = gateFor(upstream, ann.library()), boGate = gateFor(upstream, "lib_acct_bo");
        // what the program never calls: refused, with a use or without, and the server never hears of it
        for (String p : List.of("/v1/messages/batches", "/v1/batches", "/v1/images/generations", "/v1/audio/speech", "/v1/fine_tuning/jobs", "/v1/chat/completions/", "/v1/files")) {
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(annGate + p)).header(Gated.USE, ann.id()).POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode(), p + ": " + r.body());
        }
        assertEquals(403, c.send(HttpRequest.newBuilder(URI.create(annGate + "/v1/chat/completions")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "a stored answer is not read through the gate");
        assertEquals(List.of(), seen);
        // what it calls to look at a server: passed, with no use
        assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(annGate + "/v1/models")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(annGate + "/props")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        // an answer: only under a use, and only the use of the library that calls
        String ask = "{\"model\":\"stand-in\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        assertEquals(403, c.send(HttpRequest.newBuilder(URI.create(annGate + "/v1/chat/completions")).POST(HttpRequest.BodyPublishers.ofString(ask)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> borrowed = c.send(HttpRequest.newBuilder(URI.create(boGate + "/v1/chat/completions")).header(Gated.USE, ann.id()).POST(HttpRequest.BodyPublishers.ofString(ask)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, borrowed.statusCode(), "another library's use is not this one's to spend: " + borrowed.body());
        assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(annGate + "/v1/chat/completions")).header(Gated.USE, ann.id()).POST(HttpRequest.BodyPublishers.ofString(ask)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(List.of("GET /v1/models", "GET /props", "POST /v1/chat/completions"), seen);
    }

    @Test
    void anAnswerStreamedWithoutItsUsageIsCountedFromItsText(@TempDir Path tmp) throws Exception {
        assertTrue(DriveClient.hostedApi("http://127.0.0.1:41234/gate/https/openrouter.ai/api/v1"), "a hosted API through the gate is one: its stream is asked for its usage");
        up = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        up.createContext("/", x -> {
            x.getRequestBody().readAllBytes();
            x.getResponseHeaders().set("Content-Type", "text/event-stream");
            x.sendResponseHeaders(200, 0);
            try (OutputStream o = x.getResponseBody()) {
                for (int i = 0; i < 200; i++) o.write(("data: {\"id\":\"chatcmpl-abcdefabcdefabcdefabcdef\",\"object\":\"chat.completion.chunk\",\"created\":1731900000,\"model\":\"deepseek/deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" word\"},\"logprobs\":null,\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8));
                o.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        up.start();
        String upstream = "http://127.0.0.1:" + up.getAddress().getPort();
        open(tmp, upstream, "deepseek/deepseek-chat", "0.27/1.10");
        AccountStore.Use u = use("acct_est");
        c.send(HttpRequest.newBuilder(URI.create(gateFor(upstream, u.library()) + "/v1/chat/completions")).header(Gated.USE, u.id()).POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"deepseek/deepseek-chat\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")).build(), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(500);
        AccountStore.Usage row = rows(u.id()).get(0);
        assertTrue(row.estimated());
        assertTrue(row.tokensOut() >= 200 && row.tokensOut() <= 400, "200 words of five characters, about 250 tokens, not the stream's framing: " + row);
    }
}
