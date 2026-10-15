package org.researchzosho.librarian;

import org.researchzosho.Config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The command line from elsewhere (0.6.1, docs/AFTER_0_6.md §5 item 4). {@code researchzosho connect <address> <token>} points this
 * machine's command at a library on another: the address and the token go in the config ({@code RESEARCHZOSHO_LIBRARY} takes an address
 * as well as a folder; {@code RESEARCHZOSHO_TOKEN}), and from then on the verbs that have a protocol call go over HTTP — ask, search, get,
 * read, established, research, jobs, status, changes, subjects, questions, map, perspectives, inbox — rendered in plain lines. The verbs
 * that touch the folder or the machine (setup, service, model, collection, vault, reader, share, settings, add…) refuse in a sentence that
 * names what to run on the library's computer. {@code researchzosho mcp} while connected relays stdio to the library's {@code /rpc} with
 * the token, so Claude Code or Codex on this machine get the library's tools without a service of their own. {@code connect off} returns
 * to the folder. The protocol contract is unchanged: this is a client of it.
 */
public final class Remote {
    private Remote() { }

    static final ObjectMapper M = new ObjectMapper();
    public static final String TOKEN = "RESEARCHZOSHO_TOKEN";
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    public static final String VERBS = "ask, search, get, read, established, research, jobs, status, changes, subjects, questions, map, perspectives, inbox and mcp";

    public static boolean connected() { String l = Config.get("RESEARCHZOSHO_LIBRARY"); return l != null && (l.strip().startsWith("http://") || l.strip().startsWith("https://")); }
    public static String address() { String l = Config.get("RESEARCHZOSHO_LIBRARY"); return l == null ? "" : l.strip().replaceAll("/+$", ""); }

    /** What a library answered with when it would not: its code and its sentence. */
    public static final class Refused extends IOException { public final String code; Refused(String code, String message) { super(message); this.code = code; } }

    static JsonNode post(String route, ObjectNode body) throws IOException {
        body.putObject("patron").put("runtime", "cli");   // the did comes from the token, never asserted here
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(address() + "/v1/" + route)).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)));
        return send(b);
    }

    static JsonNode get(String routeAndQuery) throws IOException { return send(HttpRequest.newBuilder(URI.create(address() + "/v1/" + routeAndQuery)).timeout(Duration.ofMinutes(5)).GET()); }

    static JsonNode send(HttpRequest.Builder b) throws IOException {
        String token = Config.get(TOKEN, "");
        if (!token.isBlank()) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r;
        try { r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted while waiting for " + address()); }
        catch (IOException e) { throw new IOException("The library at " + address() + " did not answer (" + e.getMessage() + "). Is it shared, on its computer (researchzosho share status there), and is this the address it printed?"); }
        JsonNode j;
        try { j = M.readTree(r.body()); } catch (IOException e) { throw new IOException("The library at " + address() + " answered with something that is not JSON (HTTP " + r.statusCode() + ")."); }
        if (r.statusCode() >= 400 || j.has("error")) throw new Refused(j.path("error").path("code").asText("unavailable"), j.path("error").path("message").asText("HTTP " + r.statusCode()));
        return j;
    }

    /** {@code connect <address> <token>} · {@code connect off} · {@code connect} (where the commands go now). */
    public static int connect(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length >= 3 && args[2].equals("off")) { Config.set("RESEARCHZOSHO_LIBRARY", ""); Config.set(TOKEN, ""); out.println("Back to the library on this computer."); return 0; }
        if (args.length < 3) {
            if (!connected()) { out.println("The commands use the library on this computer. researchzosho connect <address> <token> points them at a library elsewhere, as `researchzosho share` on its computer printed the address and `reader invite` or `reader token` made the token."); return 0; }
            try { JsonNode s = get("status"); out.println("Connected to " + s.path("library_name").asText("the library") + " at " + address() + " (" + counts(s) + "). researchzosho connect off returns to this computer's own."); return 0; }
            catch (IOException e) { out.println("Connected to " + address() + ", which did not answer: " + e.getMessage()); return 1; }
        }
        String addr = args[2].strip().replaceAll("/+$", ""), token = args.length > 3 ? args[3].strip() : "";
        if (!addr.startsWith("http://") && !addr.startsWith("https://")) { err.println("The address starts with https:// (or http:// on your own network), as `researchzosho share` printed it on the library's computer."); return 2; }
        String was = Config.get("RESEARCHZOSHO_LIBRARY"), wasToken = Config.get(TOKEN);
        Config.set("RESEARCHZOSHO_LIBRARY", addr); Config.set(TOKEN, token);
        try {
            JsonNode s = get("status");
            out.println("Connected to " + s.path("library_name").asText("the library") + " at " + addr + ": " + counts(s) + ".");
            out.println("From now on " + VERBS + " go there" + (token.isEmpty() ? ", as a visitor (no token: what the library lets anyone do)" : ", as the token's reader") + ". The verbs that touch a library's folder or its computer run only there. researchzosho connect off returns to this computer's own library.");
            return 0;
        } catch (IOException e) {
            Config.set("RESEARCHZOSHO_LIBRARY", was == null ? "" : was); Config.set(TOKEN, wasToken == null ? "" : wasToken);
            err.println("Not connected: " + e.getMessage()); return 1;
        }
    }

    static String counts(JsonNode s) { JsonNode c = s.path("counts"); return c.path("finding").path("total").asInt() + " claims, " + c.path("investigation").asInt() + " reports, " + c.path("raw").asInt() + " saved pages"; }

    static String rest(String[] args, int from) { return args.length > from ? String.join(" ", Arrays.asList(args).subList(from, args.length)).strip() : ""; }

    /** The connected verbs; everything else is refused with what to run on the library's computer. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        String verb = args.length > 1 ? args[1] : "";
        try {
            switch (verb) {
                case "status" -> {
                    JsonNode s = get("status");
                    out.println("library " + s.path("library_name").asText("") + " at " + address() + (s.path("version").asText("").isEmpty() ? "" : "  (ResearchZosho " + s.path("version").asText() + ")"));
                    out.println("  " + counts(s) + ", " + s.path("counts").path("subject").asInt() + " subjects" + (s.path("last_updated").asText("").isEmpty() ? "" : "; last updated " + s.path("last_updated").asText()));
                    return 0;
                }
                case "ask" -> {
                    String q = rest(args, 2);
                    if (q.isEmpty()) { err.println("usage: researchzosho ask <question…>"); return 2; }
                    JsonNode r = post("ask", M.createObjectNode().put("question", q).put("k", 6));
                    if (r.path("holds_nothing").asBoolean(false) || r.path("entries").isEmpty()) {
                        out.println("The library holds nothing on this yet." + (r.path("filed_as_demand").asBoolean(false) ? " The question is filed as an open question; researchzosho research ask \"" + q + "\" sends a run after it." : ""));
                        return 0;
                    }
                    for (JsonNode e : r.path("entries")) out.println(entryLine(e));
                    return 0;
                }
                case "search" -> {
                    String q = rest(args, 2);
                    if (q.isEmpty()) { err.println("usage: researchzosho search <words…>"); return 2; }
                    JsonNode r = post("search", M.createObjectNode().put("query", q).put("k", 10));
                    if (r.path("hits").isEmpty()) { out.println("Nothing in the library matches \"" + q + "\"."); return 0; }
                    for (JsonNode h : r.path("hits")) out.println("- [" + h.path("id").asText() + "] " + h.path("title").asText() + " (" + h.path("kind").asText() + ", " + h.path("state").asText() + ")" + (h.path("snippet").asText("").isEmpty() ? "" : ": " + h.path("snippet").asText().replace('\n', ' ')));
                    return 0;
                }
                case "get" -> {
                    String id = rest(args, 2);
                    if (id.isEmpty()) { err.println("usage: researchzosho get <id>"); return 2; }
                    JsonNode r = post("get", M.createObjectNode().put("id", id));
                    JsonNode e = r.path("entry").isObject() ? r.path("entry") : r;
                    out.println(e.path("title").asText("") + "  [" + e.path("id").asText(id) + "]" + (e.path("state").asText("").isEmpty() ? "" : "  " + e.path("state").asText() + (e.path("confidence").asText("").isEmpty() ? "" : ", " + e.path("confidence").asText())));
                    String text = e.path("text").asText(e.path("body").asText(""));
                    if (!text.isEmpty()) out.println(text);
                    for (JsonNode s : e.path("sources")) out.println("  source: " + (s.isTextual() ? s.asText() : s.path("locator").asText(s.path("url").asText(""))));
                    return 0;
                }
                case "read" -> {
                    String locator = rest(args, 2);
                    if (locator.isEmpty()) { err.println("usage: researchzosho read <address or id>"); return 2; }
                    JsonNode r = post("read", M.createObjectNode().put("locator", locator).put("max_chars", 20_000));
                    out.println(r.path("title").asText("") + "  (" + r.path("locator").asText(locator) + (r.path("truncated").asBoolean(false) ? ", the first " + r.path("chars").asInt() + " characters" : "") + ")");
                    out.println(r.path("text").asText(""));
                    return 0;
                }
                case "established" -> {
                    String claim = rest(args, 2);
                    if (claim.isEmpty()) { err.println("usage: researchzosho established <claim…>"); return 2; }
                    JsonNode r = post("established", M.createObjectNode().put("claim", claim).put("k", 6));
                    out.println("verdict: " + r.path("verdict").asText("") + "  (accepted " + r.path("accepted").size() + ", disputed " + r.path("disputes").size() + ", unreviewed " + r.path("unreviewed").size() + ")");
                    for (JsonNode e : r.path("accepted")) out.println("  " + entryLine(e));
                    return 0;
                }
                case "research" -> {
                    if (args.length < 4 || !args[2].equals("ask")) { err.println("usage: researchzosho research ask \"<question…>\" [--depth] [--quick|--priority] [--shelves|--web] [--max-turns N] [--max-minutes N]   (from elsewhere, the sending only; the run's settings are the library's own)"); return 2; }
                    ObjectNode a = M.createObjectNode(); List<String> words = new ArrayList<>(); String mode = "broad";
                    for (int i = 3; i < args.length; i++) {
                        switch (args[i]) {
                            case "--depth" -> mode = "depth";
                            case "--quick", "--now" -> a.put("quick", true);
                            case "--priority", "--first" -> a.put("priority", true);
                            case "--shelves" -> a.put("sources", "shelves");
                            case "--web" -> a.put("sources", "web");
                            case "--max-turns" -> { if (++i < args.length) a.put("max_turns", Integer.parseInt(args[i])); }
                            case "--max-minutes" -> { if (++i < args.length) a.put("max_minutes", Integer.parseInt(args[i])); }
                            default -> words.add(args[i]);
                        }
                    }
                    String q = String.join(" ", words).strip();
                    if (q.isEmpty()) { err.println("usage: researchzosho research ask \"<question…>\""); return 2; }
                    a.put("question", q); a.put("mode", mode);
                    JsonNode r = post("research", a);
                    out.println("Research run " + r.path("job_id").asText() + " is " + r.path("state").asText("queued") + (r.path("queued_ahead").asInt(0) > 0 ? ", behind " + r.path("queued_ahead").asInt() : "") + " at " + address() + ". researchzosho jobs " + r.path("job_id").asText() + " follows it; the report lands there.");
                    return 0;
                }
                case "jobs" -> {
                    String id = rest(args, 2);
                    if (!id.isEmpty()) {
                        JsonNode j = get("jobs/" + URLEncoder.encode(id, StandardCharsets.UTF_8));
                        out.println(j.path("job_id").asText(id) + "  " + j.path("state").asText() + "  " + j.path("kind").asText());
                        if (j.hasNonNull("question")) out.println("  question: " + j.path("question").asText());
                        if (j.hasNonNull("investigation")) out.println("  report: " + j.path("investigation").asText());
                        if (j.hasNonNull("result") && !j.path("result").asText().isBlank()) out.println("  " + (j.path("is_error").asBoolean(false) ? "error: " : "result: ") + j.path("result").asText());
                        return 0;
                    }
                    JsonNode r = get("jobs?limit=10");
                    out.println("research runs at " + address() + ": " + r.path("running_ids").size() + " running, " + r.path("queued").asInt(0) + " queued");
                    for (JsonNode j : r.path("active")) out.println("  " + jobRow(j));
                    if (r.path("finished").size() > 0) { out.println("finished (newest first):"); for (JsonNode j : r.path("finished")) out.println("  " + jobRow(j)); }
                    return 0;
                }
                case "changes" -> {
                    String since = "0"; int limit = 30;
                    for (int i = 2; i + 1 < args.length; i++) { if (args[i].equals("--since")) since = args[++i]; else if (args[i].equals("--limit")) limit = Integer.parseInt(args[++i]); }
                    JsonNode r = post("changes", M.createObjectNode().put("since", since).put("limit", limit));
                    if (r.path("changes").isEmpty()) { out.println("No changes" + (since.equals("0") ? " yet." : " since " + since + ".")); return 0; }
                    for (JsonNode c : r.path("changes")) out.println(c.path("seq").asText() + "  " + c.path("at").asText() + "  " + c.path("kind").asText() + " " + c.path("id").asText() + "  " + c.path("event").asText() + (c.path("detail").asText("").isEmpty() ? "" : " — " + c.path("detail").asText()));
                    if (r.path("more").asBoolean(false)) out.println("more: researchzosho changes --since " + r.path("next_cursor").asText());
                    return 0;
                }
                case "subjects" -> {
                    JsonNode r = post("subjects", M.createObjectNode());
                    if (r.path("subjects").isEmpty()) { out.println("No subjects yet."); return 0; }
                    for (JsonNode s : r.path("subjects")) out.println("- " + s.path("label").asText(s.path("id").asText()) + (s.path("count").asInt(0) > 0 ? " (" + s.path("count").asInt() + ")" : ""));
                    return 0;
                }
                case "questions" -> {
                    if (args.length > 3 && args[2].equals("add")) {
                        JsonNode r = post("frontier", M.createObjectNode().put("op", "add").put("question", rest(args, 3)));
                        out.println(r.path("already").asBoolean(false) ? "That question is already open there." : "Filed as an open question at " + address() + ".");
                        return 0;
                    }
                    JsonNode r = post("frontier", M.createObjectNode().put("op", "list"));
                    if (r.path("questions").isEmpty()) { out.println("No open questions."); return 0; }
                    int n = 0; for (JsonNode q : r.path("questions")) out.println((++n) + ". " + (q.isTextual() ? q.asText() : q.path("question").asText(q.path("text").asText())));
                    return 0;
                }
                case "map" -> {
                    String focus = rest(args, 2);
                    if (focus.isEmpty()) { err.println("usage: researchzosho map <name|id>"); return 2; }
                    JsonNode r = post("map", M.createObjectNode().put("focus", focus).put("depth", 1).put("k", 25));
                    if (r.path("holds_nothing").asBoolean(false)) { out.println("The map has nothing around \"" + focus + "\"."); for (JsonNode s : r.path("suggestions")) out.println("  perhaps: " + (s.isTextual() ? s.asText() : s.path("label").asText())); return 0; }
                    out.println("around " + r.path("focus").asText(focus) + ":");
                    for (JsonNode e : r.path("edges")) out.println("  " + e.path("from").asText() + " — " + e.path("predicate").asText() + " — " + e.path("to").asText() + (e.path("disputed").asBoolean(false) ? "  (disputed)" : ""));
                    if (r.path("edges").isEmpty()) for (JsonNode nd : r.path("nodes")) out.println("  " + nd.path("label").asText() + " (" + nd.path("kind").asText() + ")");
                    return 0;
                }
                case "perspectives" -> {
                    String q = rest(args, 2);
                    if (q.isEmpty()) { err.println("usage: researchzosho perspectives <question…>"); return 2; }
                    JsonNode r = post("perspectives", M.createObjectNode().put("question", q));
                    printAny(r, out);
                    return 0;
                }
                case "inbox" -> {
                    JsonNode r = post("inbox", M.createObjectNode().put("op", "list"));
                    if (r.path("items").isEmpty()) { out.println("No claims are waiting at " + address() + "."); return 0; }
                    for (JsonNode it : r.path("items")) out.println("- [" + it.path("id").asText() + "] " + it.path("title").asText() + "  (" + it.path("state").asText() + ")");
                    out.println("Decide them on the library's Inbox page, or on its computer.");
                    return 0;
                }
                case "mcp" -> { proxyStdio(); return 0; }
                default -> {
                    err.println((verb.isEmpty() ? "Which command?" : "`researchzosho " + verb + "` works on the computer where the library lives (" + address() + "), not from here: run it there.")
                            + " From here: " + VERBS + ". researchzosho connect off returns to this computer's own library.");
                    return 2;
                }
            }
        } catch (Refused e) {
            err.println(address() + " says: " + e.getMessage() + (e.code.equals("forbidden") ? "  (the token: researchzosho connect <address> <token>)" : ""));
            return 1;
        } catch (IOException e) { err.println(e.getMessage()); return 1; }
        catch (NumberFormatException e) { err.println("a number was expected: " + e.getMessage()); return 2; }
    }

    static String entryLine(JsonNode e) {
        return "- [" + e.path("id").asText() + "] " + e.path("title").asText("") + (e.path("state").asText("").isEmpty() ? "" : "  (" + e.path("state").asText() + (e.path("confidence").asText("").isEmpty() ? "" : ", " + e.path("confidence").asText()) + ")")
                + (e.path("claim").asText("").isEmpty() ? "" : "\n    " + e.path("claim").asText());
    }

    static String jobRow(JsonNode j) {
        return j.path("job_id").asText() + "  " + j.path("state").asText() + "  " + j.path("kind").asText() + (j.hasNonNull("question") ? "  " + Acquisitions.compress(j.path("question").asText(), 90) : "") + (j.hasNonNull("investigation") ? "  → " + j.path("investigation").asText() : "");
    }

    /** An answer whose shape this command does not know by heart, printed as its fields: lists one item a line, text as it is. */
    static void printAny(JsonNode r, PrintStream out) {
        for (Iterator<Map.Entry<String, JsonNode>> it = r.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (List.of("library_id", "library_name", "contract", "patron").contains(e.getKey())) continue;
            JsonNode v = e.getValue();
            if (v.isArray()) { out.println(e.getKey() + ":"); for (JsonNode x : v) out.println("  - " + (x.isTextual() ? x.asText() : line(x))); }
            else if (v.isObject()) out.println(e.getKey() + ": " + line(v));
            else out.println(e.getKey() + ": " + v.asText());
        }
    }

    static String line(JsonNode o) {
        StringBuilder b = new StringBuilder();
        for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) { Map.Entry<String, JsonNode> e = it.next(); if (e.getValue().isValueNode()) b.append(b.length() == 0 ? "" : "; ").append(e.getKey()).append(": ").append(e.getValue().asText()); }
        return b.toString();
    }

    /** `researchzosho mcp` while connected: each JSON-RPC line from stdin goes to the library's /rpc with the token, and the answer comes back on stdout; a notification gets no line. */
    public static void proxyStdio() throws IOException {
        PrintStream protocol = System.out;
        System.setOut(System.err);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.err.println("[researchzosho-mcp] relaying to " + address() + "/rpc");
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            String answer = rpc(line);
            if (answer != null) { protocol.println(answer); protocol.flush(); }
        }
    }

    /** One JSON-RPC line to the library, its envelope back; a refusal below the envelope becomes one; null for a notification. */
    static String rpc(String line) throws IOException {
        JsonNode req; try { req = M.readTree(line); } catch (IOException e) { return null; }
        JsonNode id = req.get("id");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(address() + "/rpc")).timeout(Duration.ofMinutes(30)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(line));
        String token = Config.get(TOKEN, "");
        if (!token.isBlank()) b.header("Authorization", "Bearer " + token);
        String body; int status;
        try { HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); body = r.body(); status = r.statusCode(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (IOException e) { body = ""; status = 0; if (id == null || id.isNull()) return null; return envelope(id, -32000, "The library at " + address() + " did not answer: " + e.getMessage()); }
        if (id == null || id.isNull()) return null;
        if (status == 202 || body.isBlank()) return null;
        try {
            JsonNode j = M.readTree(body);
            if (j.has("jsonrpc")) return body;
            return envelope(id, status == 403 ? -32001 : -32000, j.path("error").path("message").asText("HTTP " + status));
        } catch (IOException e) { return envelope(id, -32000, "not JSON: HTTP " + status); }
    }

    static String envelope(JsonNode id, int code, String message) throws IOException {
        ObjectNode env = M.createObjectNode(); env.put("jsonrpc", "2.0"); env.set("id", id); env.putObject("error").put("code", code).put("message", message);
        return M.writeValueAsString(env);
    }
}
