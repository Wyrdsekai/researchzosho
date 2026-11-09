package org.researchzosho.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The stand-in MCP server over Streamable HTTP, in this process on 127.0.0.1: the session it names on initialize is asked for on every
 * request after, and so is the protocol version agreed; a call of a tool is answered as a stream of events with a progress notification and
 * a ping from the server before the answer; a key, when one is set, is wanted in the header named. It writes down every request it got.
 */
public final class StandInHttp implements AutoCloseable {
    static final ObjectMapper M = new ObjectMapper();

    /** One request as the stand-in saw it: the method, the JSON-RPC method or "", the headers it cares about, the body. */
    public record Seen(String httpMethod, String rpcMethod, String session, String version, String auth, String apiKey, String body) { }

    public final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final String key, keyHeader, version;
    /** After this many calls of tools/call, the session is forgotten once (404), as a server that restarted. */
    public volatile int forgetAfter = -1;
    private final AtomicInteger calls = new AtomicInteger(), sessions = new AtomicInteger();
    private volatile String session = null, agreed = null;

    /** {@code key} and {@code keyHeader}: the key wanted and the header it is wanted in (null: none); {@code version}: what to answer to initialize whatever is asked (null: negotiate). */
    public StandInHttp(String key, String keyHeader, String version) throws IOException {
        this.key = key; this.keyHeader = keyHeader; this.version = version;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "stand-in-mcp-http"); t.setDaemon(true); return t; }));
        server.start();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"; }

    private void handle(HttpExchange x) throws IOException {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = x.getRequestHeaders().getFirst("Authorization"), apiKey = x.getRequestHeaders().getFirst("x-api-key");
        JsonNode req = null;
        try { if (!body.isBlank()) req = M.readTree(body); } catch (IOException ignored) { }
        String rpc = req == null ? "" : req.path("method").asText(req.has("result") ? "(answer)" : "");
        seen.add(new Seen(x.getRequestMethod(), rpc, x.getRequestHeaders().getFirst("Mcp-Session-Id"), x.getRequestHeaders().getFirst("MCP-Protocol-Version"), auth, apiKey, body));
        if (key != null) {
            String got = x.getRequestHeaders().getFirst(keyHeader);
            String want = keyHeader.equalsIgnoreCase("Authorization") ? "Bearer " + key : key;
            if (!want.equals(got)) { send(x, 401, "application/json", "{\"error\":\"unauthorized\"}"); return; }
        }
        if (x.getRequestMethod().equals("DELETE")) { session = null; x.sendResponseHeaders(200, -1); x.close(); return; }
        if (!x.getRequestMethod().equals("POST") || req == null) { send(x, 405, "text/plain", "POST only"); return; }
        if (rpc.equals("initialize")) {
            ObjectNode a = StandInMcp.answer(req, version);
            session = "session-" + sessions.incrementAndGet();
            agreed = a.path("result").path("protocolVersion").asText();
            x.getResponseHeaders().set("Mcp-Session-Id", session);
            send(x, 200, "application/json", M.writeValueAsString(a));
            return;
        }
        if (session == null || !session.equals(x.getRequestHeaders().getFirst("Mcp-Session-Id"))) { send(x, session == null ? 404 : 400, "text/plain", "no such session"); return; }
        if (agreed != null && !agreed.equals(x.getRequestHeaders().getFirst("MCP-Protocol-Version"))) { send(x, 400, "text/plain", "the protocol version header is missing"); return; }
        if (!req.hasNonNull("id") || rpc.equals("(answer)")) { x.sendResponseHeaders(202, -1); x.close(); return; }   // a notification, or the client's answer to the ping
        if (rpc.equals("tools/call")) {
            if (forgetAfter >= 0 && calls.incrementAndGet() > forgetAfter) { forgetAfter = -1; session = null; send(x, 404, "text/plain", "session gone"); return; }
            ObjectNode a = StandInMcp.answer(req, version);
            String events = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":1,\"progress\":0.5}}\n\n"
                    + ": keep the line open\n\n"
                    + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"server-ping-1\",\"method\":\"ping\"}\n\n"
                    + "event: message\ndata: " + M.writeValueAsString(a) + "\n\n";
            send(x, 200, "text/event-stream", events);
            return;
        }
        send(x, 200, "application/json", M.writeValueAsString(StandInMcp.answer(req, version)));
    }

    private static void send(HttpExchange x, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(status, b.length);
        try (OutputStream o = x.getResponseBody()) { o.write(b); }
    }

    /** The requests that carried this JSON-RPC method. */
    public List<Seen> of(String rpcMethod) { return seen.stream().filter(s -> s.rpcMethod().equals(rpcMethod)).toList(); }

    public Map<String, Integer> counts() {
        Map<String, Integer> m = new TreeMap<>();
        for (Seen s : seen) m.merge(s.httpMethod() + " " + s.rpcMethod(), 1, Integer::sum);
        return m;
    }

    @Override public void close() { server.stop(0); }
}
