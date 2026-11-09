package org.researchzosho.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Version;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small MCP client: JSON-RPC 2.0 to another program's MCP server, over stdio (the program started here, one message a line on its
 * stdin and its stdout, its stderr appended to a log and never read back as content) or over Streamable HTTP (each message POSTed, the
 * answer a JSON body or a stream of server-sent events, the session the server names carried on every request after it). It speaks
 * {@code initialize} with the protocol version negotiated, {@code tools/list} page by page and {@code tools/call}; every call has a
 * timeout. What a server says is data: this class shows none of it to a model, and the library fences it before anything does.
 */
public final class McpClient implements AutoCloseable {
    private static final ObjectMapper M = new ObjectMapper();

    /** The protocol versions this client speaks, newest first: it asks for the first and takes any of them in the server's answer. */
    public static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    /** A call that got no usable answer, said in a sentence a person can read; {@code said} is the server's own words, when it gave some. */
    public static final class Failure extends IOException {
        public enum Kind { UNREACHABLE, TIMEOUT, REFUSED, PROTOCOL, ERROR, GONE, STOPPED }
        public final Kind kind;
        public final int code;
        public final String said;
        public Failure(Kind kind, String message) { this(kind, 0, message, ""); }
        public Failure(Kind kind, int code, String message, String said) { super(message); this.kind = kind; this.code = code; this.said = said == null ? "" : said; }
    }

    /** One tool a server lists: its name, title, description, input schema and annotations, as the server gave them. */
    public record Tool(String name, String title, String description, ObjectNode inputSchema, ObjectNode annotations) {
        public Tool {
            name = name == null ? "" : name; title = title == null ? "" : title; description = description == null ? "" : description;
            inputSchema = inputSchema == null ? M.createObjectNode().put("type", "object") : inputSchema;
            annotations = annotations == null ? M.createObjectNode() : annotations;
        }
        public static Tool of(JsonNode t) {
            if (t == null || !t.isObject() || t.path("name").asText("").isBlank()) return null;
            return new Tool(t.path("name").asText(), t.path("title").asText(t.path("annotations").path("title").asText("")), t.path("description").asText(""),
                    t.path("inputSchema").isObject() ? (ObjectNode) t.get("inputSchema").deepCopy() : null, t.path("annotations").isObject() ? (ObjectNode) t.get("annotations").deepCopy() : null);
        }
        public ObjectNode json() {
            ObjectNode o = M.createObjectNode();
            o.put("name", name); if (!title.isEmpty()) o.put("title", title); o.put("description", description);
            o.set("inputSchema", inputSchema.deepCopy()); if (!annotations.isEmpty()) o.set("annotations", annotations.deepCopy());
            return o;
        }
    }

    /** A tool's answer: its text (each text block, a resource's text, a link as its address), whether the tool said it failed, the addresses it names, and its structured part. */
    public record Result(String text, boolean error, List<String> links, JsonNode structured) {
        public Result { text = text == null ? "" : text; links = links == null ? List.of() : List.copyOf(links); }
    }

    /** How the messages travel: a program's stdio, or Streamable HTTP. */
    interface Transport {
        /** Sends one request and returns the message that answers it, waiting at most {@code timeout}, and no longer once {@code stop} says so. */
        JsonNode exchange(ObjectNode request, long id, Duration timeout, BooleanSupplier stop) throws IOException;
        void notify(ObjectNode notification) throws IOException;
        boolean alive();
        /** The version agreed in initialize, which Streamable HTTP carries on every request after it. */
        void protocol(String version);
        void close();
    }

    private final Transport transport;
    private final AtomicLong ids = new AtomicLong();
    private volatile String protocol = "", serverName = "", serverVersion = "";

    McpClient(Transport transport) { this.transport = transport; }

    /** A server that is a program: started with {@code command} and the environment named, minus the library's own settings; its stderr appended to {@code log}. */
    public static McpClient stdio(List<String> command, Map<String, String> env, Path log) throws IOException { return new McpClient(new Stdio(command, env, log)); }

    /** A server at an address, spoken to over Streamable HTTP with these headers on every request. */
    public static McpClient http(String url, Map<String, String> headers) throws IOException { return new McpClient(new Http(url, headers)); }

    public String protocol() { return protocol; }
    public String serverName() { return serverName; }
    public String serverVersion() { return serverVersion; }
    public boolean alive() { return transport.alive(); }

    /** The handshake: the newest version this client speaks offered, the server's choice taken when it is one of them, then notifications/initialized. */
    public synchronized void initialize(Duration timeout) throws IOException {
        ObjectNode p = M.createObjectNode();
        p.put("protocolVersion", VERSIONS.get(0));
        p.putObject("capabilities");
        String v = Version.number();
        p.putObject("clientInfo").put("name", "researchzosho").put("version", v == null ? "dev" : v);
        JsonNode r = rpc("initialize", p, timeout, false);
        String agreed = r.path("protocolVersion").asText("");
        if (!VERSIONS.contains(agreed)) {
            throw new Failure(Failure.Kind.PROTOCOL, 0, "The server speaks MCP version " + (agreed.isBlank() ? "(it named none)" : agreed) + ", and this library speaks " + String.join(", ", VERSIONS) + ".", "");
        }
        protocol = agreed;
        transport.protocol(agreed);
        serverName = r.path("serverInfo").path("name").asText("");
        serverVersion = r.path("serverInfo").path("version").asText("");
        ObjectNode n = M.createObjectNode(); n.put("jsonrpc", "2.0"); n.put("method", "notifications/initialized");
        transport.notify(n);
    }

    /** Every tool the server lists, page by page, at most fifty pages, stopping at a cursor seen before. */
    public List<Tool> tools(Duration timeout) throws IOException {
        List<Tool> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            ObjectNode p = M.createObjectNode();
            if (cursor != null) p.put("cursor", cursor);
            JsonNode r = rpc("tools/list", p, timeout, true);
            for (JsonNode t : r.path("tools")) { Tool x = Tool.of(t); if (x != null) out.add(x); }
            String next = r.hasNonNull("nextCursor") ? r.get("nextCursor").asText("") : "";
            if (next.isEmpty() || !seen.add(next)) break;
            cursor = next;
        }
        return out;
    }

    /** One call of a tool with these arguments. A JSON-RPC error is a {@link Failure} of kind ERROR; a tool that answered that it failed is a result with {@code error}. */
    public Result call(String tool, JsonNode arguments, Duration timeout) throws IOException { return call(tool, arguments, timeout, NEVER); }

    /** The same, given up as soon as {@code stop} says so: a person who stops a run is not kept waiting on another program. */
    public Result call(String tool, JsonNode arguments, Duration timeout, BooleanSupplier stop) throws IOException {
        ObjectNode p = M.createObjectNode();
        p.put("name", tool);
        p.set("arguments", arguments != null && arguments.isObject() ? arguments.deepCopy() : M.createObjectNode());
        return result(rpc("tools/call", p, timeout, true, stop == null ? NEVER : stop));
    }

    static final BooleanSupplier NEVER = () -> false;
    /** How often a wait looks whether it was stopped. */
    static final long SLICE_MS = 250;

    JsonNode rpc(String method, ObjectNode params, Duration timeout, boolean again) throws IOException { return rpc(method, params, timeout, again, NEVER); }

    JsonNode rpc(String method, ObjectNode params, Duration timeout, boolean again, BooleanSupplier stop) throws IOException {
        long id = ids.incrementAndGet();
        ObjectNode req = M.createObjectNode();
        req.put("jsonrpc", "2.0"); req.put("id", id); req.put("method", method);
        if (params != null) req.set("params", params);
        JsonNode m;
        try { m = transport.exchange(req, id, timeout, stop); }
        catch (Failure f) {
            // a Streamable HTTP server that forgot the session: a new one, and the call once more
            if (f.kind == Failure.Kind.GONE && again && transport instanceof Http) { initialize(timeout); return rpc(method, params, timeout, false, stop); }
            throw f;
        }
        if (m.has("error") && !m.get("error").isNull()) {
            JsonNode e = m.get("error");
            String said = e.path("message").asText("");
            throw new Failure(Failure.Kind.ERROR, e.path("code").asInt(0), "The server answered the call with an error" + (e.has("code") ? " (code " + e.path("code").asInt() + ")" : "") + ".", said);
        }
        return m.path("result");
    }

    static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'`\\]\\[{}|\\\\^]+");

    /** The addresses a text names, in order, each once. */
    public static List<String> urls(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = URL.matcher(text);
        while (m.find() && out.size() < 60) {
            String u = m.group().replaceAll("[.,;:!?)]+$", "");
            if (u.length() > 10 && !out.contains(u)) out.add(u);
        }
        return out;
    }

    static Result result(JsonNode r) {
        StringBuilder text = new StringBuilder();
        List<String> links = new ArrayList<>();
        for (JsonNode c : r.path("content")) {
            switch (c.path("type").asText("")) {
                case "text" -> append(text, c.path("text").asText(""));
                case "resource" -> {
                    JsonNode res = c.path("resource");
                    String uri = res.path("uri").asText("");
                    if (!uri.isBlank() && !links.contains(uri)) links.add(uri);
                    if (res.has("text")) append(text, (uri.isBlank() ? "" : uri + "\n") + res.path("text").asText(""));
                    else append(text, "[a file at " + uri + (res.path("mimeType").asText("").isBlank() ? "" : ", " + res.path("mimeType").asText()) + ", not read]");
                }
                case "resource_link" -> {
                    String uri = c.path("uri").asText("");
                    if (!uri.isBlank() && !links.contains(uri)) links.add(uri);
                    append(text, c.path("title").asText(c.path("name").asText("a link")) + ": " + uri + (c.path("description").asText("").isBlank() ? "" : " — " + c.path("description").asText()));
                }
                case "image" -> append(text, "[a picture (" + c.path("mimeType").asText("image") + "), not read]");
                case "audio" -> append(text, "[a sound (" + c.path("mimeType").asText("audio") + "), not read]");
                default -> { if (c.has("text")) append(text, c.path("text").asText("")); }
            }
        }
        JsonNode structured = r.hasNonNull("structuredContent") ? r.get("structuredContent") : null;
        if (text.isEmpty() && structured != null) text.append(structured.toString());
        for (String u : urls(text.toString())) if (!links.contains(u)) links.add(u);
        return new Result(text.toString(), r.path("isError").asBoolean(false), links.size() > 40 ? links.subList(0, 40) : links, structured);
    }

    private static void append(StringBuilder b, String s) { if (s == null || s.isEmpty()) return; if (!b.isEmpty()) b.append("\n\n"); b.append(s); }

    static String seconds(Duration d) { long s = Math.max(1, d.toSeconds()); return s + (s == 1 ? " second" : " seconds"); }

    @Override public void close() { transport.close(); }

    // ---------------------------------------------------------------- stdio

    /** A server that is a program on this machine: one JSON message a line, each way. */
    static final class Stdio implements Transport {
        private final Process process;
        private final BufferedWriter toServer;
        private final Map<Long, CompletableFuture<JsonNode>> waiting = new ConcurrentHashMap<>();
        private volatile String gone = null;

        Stdio(List<String> command, Map<String, String> env, Path log) throws IOException {
            if (command == null || command.isEmpty()) throw new Failure(Failure.Kind.UNREACHABLE, "No command is set to start the server with.");
            ProcessBuilder pb = new ProcessBuilder(command);
            Map<String, String> e = pb.environment();
            e.keySet().removeIf(k -> k.startsWith("RESEARCHZOSHO_") || k.startsWith("CODEZAIKU_"));   // the library's own settings, its keys among them, are none of another program's business
            if (env != null) e.putAll(env);
            Files.createDirectories(log.toAbsolutePath().getParent());
            rotate(log);
            Files.writeString(log, "---- " + Instant.now() + " started: " + String.join(" ", command) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            pb.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));   // what the program says on stderr is kept, never read back as content
            try { process = pb.start(); }
            catch (IOException ex) { throw new Failure(Failure.Kind.UNREACHABLE, "The command " + command.get(0) + " could not be started: " + ex.getMessage() + "."); }
            toServer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread t = new Thread(this::read, "mcp-stdio-" + process.pid());
            t.setDaemon(true);
            t.start();
        }

        /** A log past a megabyte starts again, the old one kept beside it once. */
        static void rotate(Path log) {
            try { if (Files.isRegularFile(log) && Files.size(log) > 1_000_000) Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) { }
        }

        private void read() {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode m;
                    try { m = M.readTree(line); } catch (Exception notJson) { continue; }   // a program that prints other things on stdout: not the protocol, never shown
                    if (m.isArray()) { for (JsonNode x : m) take(x); } else take(m);
                }
            } catch (IOException ignored) { }
            int code = -1;
            try { if (process.waitFor(2, TimeUnit.SECONDS)) code = process.exitValue(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            gone = code >= 0 ? "The server's program ended (exit code " + code + ")." : "The server's program closed its output.";
            for (CompletableFuture<JsonNode> f : waiting.values()) f.completeExceptionally(new Failure(Failure.Kind.GONE, gone));
            waiting.clear();
        }

        private void take(JsonNode m) {
            if (m.has("method")) { if (m.hasNonNull("id")) answer(m); return; }   // a request from the server is answered; a notification needs nothing
            if (!m.hasNonNull("id")) return;
            CompletableFuture<JsonNode> f = waiting.remove(m.get("id").asLong(-1));
            if (f != null) f.complete(m);
        }

        /** The server asked something: ping is answered, anything else is a method this client does not offer. */
        private void answer(JsonNode req) {
            try { write(reply(req)); } catch (IOException ignored) { }
        }

        private synchronized void write(ObjectNode m) throws IOException {
            toServer.write(M.writeValueAsString(m));
            toServer.write('\n');
            toServer.flush();
        }

        @Override public JsonNode exchange(ObjectNode request, long id, Duration timeout, BooleanSupplier stop) throws IOException {
            if (gone != null) throw new Failure(Failure.Kind.GONE, gone);
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            waiting.put(id, f);
            try { write(request); }
            catch (IOException e) { waiting.remove(id); throw new Failure(Failure.Kind.GONE, gone != null ? gone : "The server's program does not take input any more."); }
            long end = System.nanoTime() + timeout.toNanos();
            try {
                while (true) {
                    long left = end - System.nanoTime();
                    if (left <= 0) throw new TimeoutException();
                    try { return f.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(SLICE_MS)), TimeUnit.NANOSECONDS); }
                    catch (TimeoutException slice) {
                        if (!stop.getAsBoolean()) continue;
                        waiting.remove(id);
                        try { write(cancelled(id, "stopped by the person")); } catch (IOException ignored) { }
                        throw new Failure(Failure.Kind.STOPPED, "The call was stopped before the server answered.");
                    }
                }
            }
            catch (TimeoutException e) {
                waiting.remove(id);
                try { write(cancelled(id, "no answer in time")); } catch (IOException ignored) { }
                throw new Failure(Failure.Kind.TIMEOUT, "The server gave no answer within " + seconds(timeout) + ".");
            }
            catch (ExecutionException e) { if (e.getCause() instanceof IOException io) throw io; throw new Failure(Failure.Kind.GONE, gone == null ? "The server's program stopped answering." : gone); }
            catch (InterruptedException e) { waiting.remove(id); Thread.currentThread().interrupt(); throw new Failure(Failure.Kind.TIMEOUT, "The call was stopped before the server answered."); }
        }

        @Override public void notify(ObjectNode notification) throws IOException { if (gone == null) write(notification); }
        @Override public boolean alive() { return gone == null && process.isAlive(); }
        @Override public void protocol(String version) { }

        /** The program is asked to end by closing its input, then told, then made to; the programs it started go with it. */
        @Override public void close() {
            List<ProcessHandle> kids = new ArrayList<>();
            try { process.descendants().forEach(kids::add); } catch (RuntimeException ignored) { }
            try { toServer.close(); } catch (IOException ignored) { }
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            for (ProcessHandle k : kids) if (k.isAlive()) k.destroy();
            gone = gone == null ? "The server's program was stopped." : gone;
        }
    }

    static ObjectNode reply(JsonNode req) {
        ObjectNode r = M.createObjectNode(); r.put("jsonrpc", "2.0"); r.set("id", req.get("id"));
        if ("ping".equals(req.path("method").asText(""))) r.putObject("result");
        else r.putObject("error").put("code", -32601).put("message", "This client offers no " + req.path("method").asText("such") + ".");
        return r;
    }

    static ObjectNode cancelled(long id, String reason) {
        ObjectNode n = M.createObjectNode(); n.put("jsonrpc", "2.0"); n.put("method", "notifications/cancelled");
        n.putObject("params").put("requestId", id).put("reason", reason);
        return n;
    }

    // ---------------------------------------------------------------- Streamable HTTP

    /** A server at an address: each message POSTed, the answer a JSON body or a stream of events; the session id the server gives carried after. */
    static final class Http implements Transport {
        private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();   // a key in a header never follows a redirect to another host
        private static final ExecutorService READERS = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "mcp-http"); t.setDaemon(true); return t; });
        static final String UA = "ResearchZosho/" + (Version.number() == null ? "dev" : Version.number()) + " (+https://researchzosho.org)";
        private final URI url;
        private final Map<String, String> headers;
        private volatile String session = null, protocol = null;
        private volatile boolean closed = false;

        Http(String address, Map<String, String> headers) throws IOException {
            try { this.url = URI.create(address); } catch (IllegalArgumentException e) { throw new Failure(Failure.Kind.UNREACHABLE, "The address " + address + " is not one a request can go to."); }
            if (url.getHost() == null || !(address.startsWith("https://") || address.startsWith("http://"))) throw new Failure(Failure.Kind.UNREACHABLE, "The address " + address + " is not an http or https address.");
            this.headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        }

        @Override public JsonNode exchange(ObjectNode request, long id, Duration timeout, BooleanSupplier stop) throws IOException {
            if (closed) throw new Failure(Failure.Kind.GONE, "The connection to the server was closed.");
            Set<InputStream> open = ConcurrentHashMap.newKeySet();
            Future<JsonNode> f = READERS.submit(() -> post(request, id, timeout, open));
            long end = System.nanoTime() + timeout.toNanos();
            try {
                while (true) {
                    long left = end - System.nanoTime();
                    if (left <= 0) throw new TimeoutException();
                    try { return f.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(SLICE_MS)), TimeUnit.NANOSECONDS); }
                    catch (TimeoutException slice) {
                        if (!stop.getAsBoolean()) continue;
                        f.cancel(true);
                        for (InputStream in : open) { try { in.close(); } catch (IOException ignored) { } }
                        throw new Failure(Failure.Kind.STOPPED, "The call was stopped before the server answered.");
                    }
                }
            }
            catch (TimeoutException e) {
                f.cancel(true);
                for (InputStream in : open) { try { in.close(); } catch (IOException ignored) { } }
                throw new Failure(Failure.Kind.TIMEOUT, "The server gave no answer within " + seconds(timeout) + ".");
            }
            catch (ExecutionException e) {
                if (e.getCause() instanceof IOException io) throw io;
                throw new Failure(Failure.Kind.UNREACHABLE, "The server could not be reached: " + e.getCause() + ".");
            }
            catch (InterruptedException e) { f.cancel(true); Thread.currentThread().interrupt(); throw new Failure(Failure.Kind.TIMEOUT, "The call was stopped before the server answered."); }
        }

        private HttpRequest.Builder builder(Duration timeout, boolean initialize) {
            HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout).header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream").header("User-Agent", UA);
            if (!initialize && protocol != null) b.header("MCP-Protocol-Version", protocol);
            if (!initialize && session != null) b.header("Mcp-Session-Id", session);
            headers.forEach(b::header);
            return b;
        }

        private JsonNode post(ObjectNode request, long id, Duration timeout, Set<InputStream> open) throws IOException {
            boolean init = "initialize".equals(request.path("method").asText(""));
            HttpResponse<InputStream> r = send(builder(timeout, init).POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(request), StandardCharsets.UTF_8)).build(), timeout);
            InputStream in = r.body();
            open.add(in);
            try {
                r.headers().firstValue("Mcp-Session-Id").ifPresent(s -> { if (!s.isBlank()) session = s.strip(); });
                int st = r.statusCode();
                if (st == 404 && !init && session != null) { session = null; throw new Failure(Failure.Kind.GONE, "The server no longer knows the session."); }
                if (st < 200 || st >= 300) throw refusal(st, in, r);
                String type = r.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if (type.startsWith("text/event-stream")) return events(in, id);
                byte[] body = in.readAllBytes();
                if (body.length == 0) throw new Failure(Failure.Kind.PROTOCOL, "The server answered with nothing.");
                JsonNode j;
                try { j = M.readTree(body); } catch (IOException e) { throw new Failure(Failure.Kind.PROTOCOL, "The server's answer is not JSON."); }
                JsonNode m = mine(j, id);
                if (m == null) throw new Failure(Failure.Kind.PROTOCOL, "The server's answer was not the answer to this call.");
                return m;
            } finally {
                open.remove(in);
                try { in.close(); } catch (IOException ignored) { }
            }
        }

        private static HttpResponse<InputStream> send(HttpRequest req, Duration timeout) throws IOException {
            try { return CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream()); }
            catch (HttpTimeoutException e) { throw new Failure(Failure.Kind.TIMEOUT, "The server gave no answer within " + seconds(timeout) + "."); }
            catch (ConnectException e) { throw new Failure(Failure.Kind.UNREACHABLE, "Nothing answers at " + req.uri().getHost() + (req.uri().getPort() > 0 ? ":" + req.uri().getPort() : "") + "."); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure(Failure.Kind.TIMEOUT, "The call was stopped before the server answered."); }
            catch (IOException e) { throw new Failure(Failure.Kind.UNREACHABLE, "The server could not be reached: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "."); }
        }

        /** A status that is no answer, in a sentence; the server's own words beside it, short. */
        private Failure refusal(int st, InputStream in, HttpResponse<InputStream> r) {
            String said = "";
            try { byte[] b = in.readNBytes(600); said = new String(b, StandardCharsets.UTF_8).replaceAll("\\s+", " ").strip(); } catch (IOException ignored) { }
            if (st == 401 || st == 403) return new Failure(Failure.Kind.REFUSED, st, "The server refused the call (HTTP " + st + "): " + (headers.isEmpty() ? "it may want a key, and none is set." : "the key it was sent is not accepted.") , said);
            if (st == 429) return new Failure(Failure.Kind.REFUSED, st, "The server says too many calls were made for now (HTTP 429).", said);
            if (st >= 300 && st < 400) return new Failure(Failure.Kind.PROTOCOL, st, "The server sends the call elsewhere (HTTP " + st + (r.headers().firstValue("Location").isPresent() ? ", to " + r.headers().firstValue("Location").get() : "") + "); set it up by that address.", said);
            if (st == 405 || st == 406) return new Failure(Failure.Kind.PROTOCOL, st, "The server does not take an MCP call by POST at this address (HTTP " + st + "); it may speak only the older SSE way, which this library does not.", said);
            return new Failure(Failure.Kind.PROTOCOL, st, "The server answered HTTP " + st + ".", said);
        }

        /** The events of a stream until the one that answers {@code id}; a request from the server on the way is answered by a POST of its own. */
        private JsonNode events(InputStream in, long id) throws IOException {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) { JsonNode m = event(data.toString(), id); data.setLength(0); if (m != null) return m; }
                    continue;
                }
                if (line.startsWith(":")) continue;   // a comment, to keep the line open
                if (line.startsWith("data:")) { if (!data.isEmpty()) data.append('\n'); data.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5)); }
            }
            if (!data.isEmpty()) { JsonNode m = event(data.toString(), id); if (m != null) return m; }
            throw new Failure(Failure.Kind.PROTOCOL, "The server's stream ended before it answered the call.");
        }

        private JsonNode event(String data, long id) {
            JsonNode j;
            try { j = M.readTree(data); } catch (IOException e) { return null; }
            for (JsonNode m : j.isArray() ? j : M.createArrayNode().add(j)) {
                if (m.has("method")) { if (m.hasNonNull("id")) { ObjectNode answer = reply(m); READERS.submit(() -> { try { deliver(answer); } catch (IOException ignored) { } }); } continue; }
                if (m.hasNonNull("id") && m.get("id").asLong(-1) == id) return m;
            }
            return null;
        }

        private static JsonNode mine(JsonNode j, long id) {
            if (j.isArray()) { for (JsonNode m : j) if (m.hasNonNull("id") && m.get("id").asLong(-1) == id) return m; return null; }
            return j.hasNonNull("id") && j.get("id").asLong(-1) == id ? j : (j.has("error") && !j.has("id") ? j : null);
        }

        /** A notification or an answer to the server: POSTed, a 202 expected, the body not read. */
        private void deliver(ObjectNode message) throws IOException {
            HttpResponse<InputStream> r = send(builder(Duration.ofSeconds(15), false).POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(message), StandardCharsets.UTF_8)).build(), Duration.ofSeconds(15));
            try (InputStream in = r.body()) { in.readNBytes(4096); }
        }

        @Override public void notify(ObjectNode notification) throws IOException { if (!closed) deliver(notification); }
        @Override public boolean alive() { return !closed; }
        @Override public void protocol(String version) { this.protocol = version; }

        /** The session is ended at the server when it named one; the connection is closed here whatever it says. */
        @Override public void close() {
            if (closed) return;
            closed = true;
            String s = session;
            if (s == null) return;
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).header("Mcp-Session-Id", s).header("User-Agent", UA);
                if (protocol != null) b.header("MCP-Protocol-Version", protocol);
                headers.forEach(b::header);
                CLIENT.send(b.DELETE().build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException e) { /* the server forgets it in its own time */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** For a test: the arguments of a call as one line. */
    static String line(JsonNode n) { return n == null ? "" : n.toString(); }

    /** Every tool as JSON, for the catalog. */
    public static ArrayNode json(List<Tool> tools) { ArrayNode a = M.createArrayNode(); for (Tool t : tools) a.add(t.json()); return a; }
}
