package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.researchzosho.Config;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the phone door's tests share: a home of the test's own, a library, a stand-in model on the loopback whose next turn's tool calls the
 * test names, a daemon with its phone door open, and a browser paired with it through the door itself.
 */
final class PhoneStandIns implements AutoCloseable {
    static final ObjectMapper M = new ObjectMapper();

    final String home;
    final LibraryStore store;
    final HttpServer model;
    final LibrarianDaemon d;
    final HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    /** The calls the model makes in its next turn, each {name, arguments as JSON}; after the tools answer, it says "Done." */
    final AtomicReference<List<String[]>> next = new AtomicReference<>(List.of());
    /** What the model was sent, each request's tool names. */
    final List<List<String>> tablesSeen = new ArrayList<>();

    PhoneStandIns(Path tmp) throws Exception {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        QuietHome.quiet(tmp.resolve("home"));
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        model = model();
        d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "stand-in", -1);
        d.openPhoneDoor(0);
    }

    @Override public void close() {
        try { d.stop(); } finally { model.stop(0); System.setProperty("user.home", home); Config.invalidate(); }
    }

    String door() { return "http://127.0.0.1:" + d.phoneDoorPort(); }
    String main() { return "http://127.0.0.1:" + d.port(); }

    private HttpServer model() throws Exception {
        HttpServer m = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        m.createContext("/", x -> {
            byte[] in = x.getRequestBody().readAllBytes();
            ObjectNode out = M.createObjectNode();
            boolean stream = false;
            if (x.getRequestURI().getPath().endsWith("/chat/completions")) {
                JsonNode req = M.readTree(in);
                stream = req.path("stream").asBoolean(false);
                ArrayNode msgs = (ArrayNode) req.path("messages");
                int last = -1;
                for (int i = 0; i < msgs.size(); i++) if ("user".equals(msgs.get(i).path("role").asText()) && !msgs.get(i).path("content").asText("").startsWith("(The library:")) last = i;
                int answered = 0; for (int i = last + 1; i < msgs.size(); i++) if ("tool".equals(msgs.get(i).path("role").asText())) answered++;
                List<String> table = new ArrayList<>(); for (JsonNode t : req.path("tools")) table.add(t.path("function").path("name").asText());
                synchronized (tablesSeen) { tablesSeen.add(table); }
                ObjectNode msg = M.createObjectNode().put("role", "assistant");
                List<String[]> calls = answered == 0 && req.path("tools").size() > 0 ? next.getAndSet(List.of()) : List.of();
                if (!calls.isEmpty()) {
                    ArrayNode cs = msg.putArray("tool_calls"); msg.put("content", "");
                    for (String[] k : calls) { ObjectNode c = cs.addObject(); c.put("id", "c" + cs.size()); c.put("type", "function"); c.putObject("function").put("name", k[0]).put("arguments", k[1]); }
                } else msg.put("content", "Done.");
                ObjectNode ch = out.putArray("choices").addObject(); ch.set("message", msg); ch.put("finish_reason", msg.has("tool_calls") ? "tool_calls" : "stop");
                out.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 5).put("total_tokens", 15);
            } else out.putArray("data").addObject().put("id", "stand-in");
            byte[] b;
            if (stream) {
                ObjectNode msg = (ObjectNode) out.path("choices").get(0).path("message");
                ObjectNode delta = msg.deepCopy();
                if (delta.has("tool_calls")) { int n = 0; for (JsonNode c : delta.path("tool_calls")) ((ObjectNode) c).put("index", n++); }
                ObjectNode c1 = M.createObjectNode(); c1.putArray("choices").addObject().put("index", 0).set("delta", delta);
                ObjectNode c2 = M.createObjectNode(); c2.putArray("choices").addObject().put("index", 0).put("finish_reason", out.path("choices").get(0).path("finish_reason").asText()).putObject("delta");
                c2.set("usage", out.path("usage"));
                b = ("data: " + c1 + "\n\ndata: " + c2 + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().set("Content-Type", "text/event-stream");
            } else { b = out.toString().getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); }
            x.sendResponseHeaders(200, b.length);
            try (OutputStream o = x.getResponseBody()) { o.write(b); }
        });
        m.start();
        return m;
    }

    /** The model's next turn: these calls, name then arguments, in pairs. */
    void calls(String... nameArgs) { List<String[]> l = new ArrayList<>(); for (int i = 0; i + 1 < nameArgs.length; i += 2) l.add(new String[]{nameArgs[i], nameArgs[i + 1]}); next.set(l); }

    // ---------------------------------------------------------------- a browser at the door

    /** A browser paired through the door with a fresh code: its cookie's value. */
    String pair(String name) throws Exception {
        Phones.Made m = Phones.pairingCode(name);
        HttpResponse<String> r = post(door(), "/m/pair", null, "c=" + m.code());
        if (r.statusCode() != 200) throw new IllegalStateException(r.statusCode() + " " + r.body());
        String set = r.headers().firstValue("Set-Cookie").orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    /** The phone a cookie is. */
    Phones.Phone phone(String cookie) throws Exception { return Phones.bySecret(cookie); }

    HttpResponse<String> get(String base, String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (cookie != null) b.header("Cookie", PhoneDoor.COOKIE + "=" + cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A POST as the phone page's own script sends it: the page's header and same-origin; {@code extra} header pairs after. */
    HttpResponse<String> post(String base, String path, String cookie, String form, String... extra) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form == null ? "" : form));
        Map<String, String> h = new LinkedHashMap<>();
        h.put(PhoneDoor.HEADER, "1"); h.put("Sec-Fetch-Site", "same-origin");
        for (int i = 0; i + 1 < extra.length; i += 2) { if (extra[i + 1] == null) h.remove(extra[i]); else h.put(extra[i], extra[i + 1]); }
        h.forEach(b::header);
        if (cookie != null) b.header("Cookie", PhoneDoor.COOKIE + "=" + cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** One message from the phone's box: the conversation that came back. */
    JsonNode ask(String cookie, String session, String words) throws Exception {
        HttpResponse<String> r = post(door(), "/m/ask", cookie, form("session", session == null ? "" : session, "say", words));
        if (r.statusCode() != 200) throw new IllegalStateException(r.statusCode() + " " + r.body());
        return M.readTree(r.body());
    }

    /** The words of the conversation's last reply. */
    String lastReply(String cookie, String session) throws Exception {
        Librarian.Session s = Librarian.Session.resume(store, session, PhoneRules.patron(phone(cookie)));
        // the reply of the person's last message: the first answer after it (a run's end the conversation announces later is not it)
        List<ObjectNode> ms = s.messages();
        int at = -1;
        for (int i = 0; i < ms.size(); i++) if ("user".equals(ms.get(i).path("role").asText()) && !ms.get(i).has("by")) at = i;
        for (int i = at + 1; i < ms.size(); i++) if ("assistant".equals(ms.get(i).path("role").asText()) && !ms.get(i).has("tool_calls")) return ms.get(i).path("content").asText("");
        return "";
    }

    static String form(String... kv) { StringBuilder s = new StringBuilder(); for (int i = 0; i + 1 < kv.length; i += 2) s.append(s.isEmpty() ? "" : "&").append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8)); return s.toString(); }

    /** The runs on the ledger, every state. */
    List<ObjectNode> jobs() throws Exception {
        Jobs j = new Jobs(store, x -> "");
        List<ObjectNode> all = new ArrayList<>(j.active()); all.addAll(j.recent(100, null));
        return all;
    }

    static String read(Path p) throws Exception { return Files.exists(p) ? Files.readString(p) : ""; }
}
