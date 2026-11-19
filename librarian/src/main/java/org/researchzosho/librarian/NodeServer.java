package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.researchzosho.Version;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A node's own calls, for the account service (0.8.0, nodes): start a library here and say where it answers, bring a library here
 * from its copy, put one to sleep, and say what this node holds. Each call carries a statement the service signed, made out to this
 * node; the node checks it against the service's keys in the store both read. This port belongs on the private network between the
 * service and its machines.
 */
public final class NodeServer {
    private final Node node;
    private final AccountStore store;
    private final String issuer;
    private final HttpServer server;

    private NodeServer(Node node, AccountStore store, String issuer, HttpServer server) { this.node = node; this.store = store; this.issuer = issuer; this.server = server; }

    /** Listens for the service's calls ({@code port} 0: any free one) and tells the node where, so it writes that into the registry. */
    public static NodeServer start(Node node, AccountStore store, String issuer, String host, int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(host, port), 0);
        NodeServer n = new NodeServer(node, store, issuer, s);
        s.createContext("/", x -> { try { n.handle(x); } finally { x.close(); } });
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        node.address("http://" + (host.equals("0.0.0.0") ? "127.0.0.1" : host.contains(":") ? "[" + host + "]" : host) + ":" + s.getAddress().getPort());
        return n;
    }

    public String url() { return node.address(); }
    public void stop() { server.stop(0); }

    /** The service's key by its id, from the store. */
    private PublicKey key(String kid) throws IOException {
        for (String k : store.publicJwks()) { JsonNode j = Statement.M.readTree(k); if (j.path("kid").asText("").equals(kid)) return Statement.fromJwk(j); }
        return null;
    }

    private void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        if (path.equals("/healthz")) { ObjectNode o = Statement.M.createObjectNode(); o.put("ok", true); o.put("node", node.id()); o.put("version", Version.string()); json(x, 200, o); return; }
        try {
            Statement.Claims c = Statement.verify(x.getRequestHeaders().getFirst(Statement.HEADER), this::key, issuer, node.id());
            if (!c.via().equals(Statement.SERVICE)) throw new IOException("A node takes the account service's own statements only.");
        } catch (IOException e) { json(x, 403, error("forbidden", e.getMessage())); return; }
        if (path.equals("/node/take") && x.getRequestMethod().equals("POST")) {   // a library's archive, streamed here, taken in under this node's root
            try { json(x, 200, Statement.M.createObjectNode().put("said", node.take(x.getRequestBody(), x.getRequestHeaders().getFirst(Nodes.FOR)))); }
            catch (IOException e) { json(x, 400, error("invalid_args", e.getMessage())); }
            return;
        }
        byte[] body = x.getRequestBody().readAllBytes();
        try {
            if (path.equals("/node/held") && x.getRequestMethod().equals("GET")) {
                ObjectNode o = Statement.M.createObjectNode(); o.put("node", node.id());
                var list = o.putArray("libraries");
                for (Map<String, Object> m : node.holding()) list.add(Statement.M.valueToTree(m));
                json(x, 200, o);
                return;
            }
            if (!x.getRequestMethod().equals("POST")) { json(x, 405, error("invalid_args", "POST")); return; }
            JsonNode args = body.length == 0 ? Statement.M.createObjectNode() : Statement.M.readTree(body);
            if (path.equals("/node/make")) {   // a new library on this node's disk, for a service whose own machine runs none (stage 4, step 5)
                Node.Made m = node.make(args.path("name").asText(""), args.path("title").asText(""));
                ObjectNode o = Statement.M.createObjectNode(); o.put("library_id", m.id()); o.put("folder", m.folder());
                json(x, 200, o);
                return;
            }
            if (path.equals("/node/places")) {   // the library's folder moved, removed, released or brought back here (ROB 4): whatever its state
                AccountStore.Library any = store.library(args.path("library").asText(""));
                if (any == null) { json(x, 404, error("not_found", "No library is named so.")); return; }
                String to = args.path("to").asText("");
                json(x, 200, Statement.M.createObjectNode().put("said", node.places(any, args.path("op").asText(""), to.isEmpty() ? null : Path.of(to), args.path("now").asBoolean(false))));
                return;
            }
            if (path.equals("/node/copy")) {   // the library's copy, for a node of another machine it moves to: it is out of service meanwhile
                AccountStore.Library moving = store.library(args.path("library").asText(""));
                if (moving == null) { json(x, 404, error("not_found", "No library is named so.")); return; }
                LazyBody out = new LazyBody(x, "application/zip");
                try { node.copyOut(moving, out); out.flush(); }
                catch (IOException | RuntimeException e) { if (out.started()) return; throw e; }
                return;
            }
            if (path.equals("/node/done")) {   // a request the service routed here ended (ROB 14): whatever the library's state is now
                node.routedEnded(args.path("library").asText(""));
                json(x, 200, Statement.M.createObjectNode().put("ok", true));
                return;
            }
            AccountStore.Library lib = store.library(args.path("library").asText(""));
            if (lib == null || !lib.active()) { json(x, 404, error("not_found", "No library in service is named so.")); return; }
            if (path.equals("/node/archive")) {   // the library's archive to take home, streamed as it is made (stage 4)
                LazyBody out = new LazyBody(x, "application/zip");
                try { node.archive(lib, out, args.path("from").asText("")); out.flush(); }
                catch (IOException | RuntimeException e) { if (out.started()) return; throw e; }   // begun: the archive ends short, and the service says so
                return;
            }
            ObjectNode o = Statement.M.createObjectNode();
            switch (path) {
                case "/node/ensure" -> {
                    String address = node.ensure(lib);
                    node.routed(lib.id());   // the service's request goes on here until it says it ended (/node/done): the library does not sleep under it
                    o.put("address", address); o.put("epoch", node.epoch(lib.id()));
                }
                case "/node/bring" -> o.put("said", node.bring(lib));
                case "/node/stop" -> o.put("stopped", node.sleep(lib.id(), "the account service asked"));
                default -> { json(x, 404, error("not_found", "No such call.")); return; }
            }
            json(x, 200, o);
        } catch (Node.NotHere e) { json(x, 409, error("not_here", e.getMessage()));
        } catch (Node.Packing e) { json(x, 423, error("packing", e.getMessage()));
        } catch (IOException | RuntimeException e) { json(x, 503, error("unavailable", e.getMessage() == null ? e.toString() : e.getMessage())); }
    }

    private static ObjectNode error(String code, String message) { ObjectNode o = Statement.M.createObjectNode(); o.putObject("error").put("code", code).put("message", message); return o; }

    private static void json(HttpExchange x, int code, ObjectNode o) throws IOException {
        byte[] b = Statement.M.writeValueAsBytes(o);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream out = x.getResponseBody()) { out.write(b); }
    }
}
