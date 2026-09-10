package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.researchzosho.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code researchzosho setup}: the first ten minutes, one question at a time, each with a sensible
 * answer already filled in. Where the library goes; which model reads (found on the usual local
 * ports, or named, then proved with one real call); whether search works by meaning; whether it runs
 * as a service; which programs to connect (Claude Code when it is installed; the lines any other MCP program needs); then the first document and the first answer, so
 * the last thing setup does is show the library working.
 *
 * <p>Every answer lands in the config file, and setup can be run again to change one thing. {@code
 * --yes} takes every default without asking, for a script. The probing and the acts that touch the
 * machine are behind small interfaces so a test can drive the whole conversation.
 */
public final class Setup {

    private static final ObjectMapper M = new ObjectMapper();
    static final String[] LOCAL_PORTS = {"8080", "8000", "11434", "1234", "8200", "8210", "5000"};

    /** What setup asks the outside world. */
    public interface Probe {
        /** Model ids a server offers at {@code base}, or null when nothing answers there. */
        List<String> models(String base, String key);
        /** One short chat with {@code model}; the reply text, or a one-line reason it failed prefixed with "!". */
        String chat(String base, String model, String key);
        /** Whether {@code base} answers an embeddings request. */
        boolean embeds(String base, String key);
        /** Whether a SearXNG at {@code base} answers a JSON search (it needs json in search.formats). */
        default boolean searxng(String base) { return false; }
        /** Whether the Brave Search API accepts {@code key}. */
        default boolean brave(String key) { return false; }
    }

    /** What setup does to the machine. */
    public interface Acts {
        int installService(int port, PrintStream out) throws Exception;
        boolean haveClaude();
        String claudeMcpAdd(List<String> args) throws Exception;
        String launcher();
        /** Whether a command (claude, codex, gemini…) is on this machine. */
        default boolean have(String command) { return "claude".equals(command) && haveClaude(); }
        /** Run a host's own registration command; "connected" or its output, "!reason" on failure. */
        default String register(String command, List<String> args) throws Exception { return "claude".equals(command) ? claudeMcpAdd(args) : "!" + command + " is not available"; }
        /** Whether docker is on this machine. */
        default boolean haveDocker() { return false; }
        /** Start SearXNG through docker on {@code port}; the address, or "!reason". */
        default String startSearxng(int port) { return "!docker is not on this machine"; }
        /** Start the embeddings server through docker on {@code port}; the address, or "!reason". */
        default String startEmbed(int port) { return "!docker is not on this machine"; }
        /** Whether docker here can run the GPU embeddings image (an NVIDIA card and its container runtime). */
        default boolean embedGpu() { return false; }
    }

    /** The programs that can be registered from the command line: their command, their name, and how they take an MCP server. */
    record Host(String command, String name) { }
    static final List<Host> HOSTS = List.of(new Host("claude", "Claude Code"), new Host("codex", "Codex"), new Host("gemini", "Gemini CLI"));

    private final BufferedReader in;
    private final PrintStream out;
    private final Probe probe;
    private final Acts acts;
    private final boolean yes;

    public Setup(BufferedReader in, PrintStream out, Probe probe, Acts acts, boolean yes) {
        this.in = in; this.out = out; this.probe = probe; this.acts = acts; this.yes = yes;
    }

    /** The default probe: HTTP, ten seconds, the key only to the server asked. */
    public static Probe liveProbe() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
        return new Probe() {
            @Override public List<String> models(String base, String key) {
                try {
                    var b = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/v1/models")).timeout(Duration.ofSeconds(6)).GET();
                    if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key);
                    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() / 100 != 2) return null;
                    List<String> ids = new ArrayList<>();
                    for (JsonNode m : M.readTree(r.body()).path("data")) if (m.hasNonNull("id")) ids.add(m.get("id").asText());
                    return ids;
                } catch (Exception e) { return null; }
            }
            @Override public String chat(String base, String model, String key) {
                try {
                    var body = M.createObjectNode();
                    body.put("model", model); body.put("max_tokens", 12);
                    ArrayNode msgs = body.putArray("messages");
                    msgs.addObject().put("role", "user").put("content", "Reply with the single word: ready");
                    var b = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/v1/chat/completions")).timeout(Duration.ofSeconds(60))
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
                    if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key);
                    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() / 100 != 2) return "!the server answered " + r.statusCode() + (r.statusCode() == 401 ? " (it wants a key)" : "");
                    JsonNode j = M.readTree(r.body());
                    String text = j.path("choices").path(0).path("message").path("content").asText("");
                    return text.isBlank() ? "!the server answered but said nothing" : text.strip();
                } catch (Exception e) { return "!" + plain(e); }
            }
            @Override public boolean searxng(String base) {
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/search?q=ready&format=json"))
                            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
                    return r.statusCode() == 200 && M.readTree(r.body()).has("results");
                } catch (Exception e) { return false; }
            }
            @Override public boolean brave(String key) {
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.search.brave.com/res/v1/web/search?count=1&q=ready"))
                            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").header("X-Subscription-Token", key.strip()).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    return r.statusCode() == 200;
                } catch (Exception e) { return false; }
            }
            @Override public boolean embeds(String base, String key) {
                try {
                    var body = M.createObjectNode(); body.put("input", "ready"); body.put("model", "default");
                    var b = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/v1/embeddings")).timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
                    if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key);
                    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    return r.statusCode() / 100 == 2 && M.readTree(r.body()).path("data").path(0).path("embedding").isArray();
                } catch (Exception e) { return false; }
            }
        };
    }

    /** The default acts: the real service installer and the real `claude` command. */
    public static Acts liveActs() {
        return new Acts() {
            @Override public int installService(int port, PrintStream out) throws Exception {
                var plan = Service.plan(Service.os(), Service.resolveExec(null), port, 3, Path.of(System.getProperty("user.home")));
                return Service.run("install", plan, out);
            }
            @Override public boolean haveDocker() { return Searx.haveDocker(); }
            @Override public String startSearxng(int port) { return Searx.start(port); }
            @Override public String startEmbed(int port) { return Embed.start(port, false); }
            @Override public boolean embedGpu() { return Embed.gpu(); }
            @Override public boolean haveClaude() { return have("claude"); }
            @Override public String claudeMcpAdd(List<String> args) throws Exception { return register("claude", args); }
            @Override public boolean have(String command) {
                String path = System.getenv("PATH");
                if (path == null) return false;
                for (String dir : path.split(java.io.File.pathSeparator)) {
                    if (Files.isRegularFile(Path.of(dir, command)) || Files.isRegularFile(Path.of(dir, command + ".exe")) || Files.isRegularFile(Path.of(dir, command + ".cmd"))) return true;
                }
                return false;
            }
            @Override public String register(String command, List<String> args) throws Exception {
                List<String> cmd = new ArrayList<>(); cmd.add(command); cmd.addAll(args);
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String o = new String(p.getInputStream().readAllBytes()).strip();
                int rc = p.waitFor();
                return rc == 0 ? (o.isEmpty() ? "connected" : o) : "!" + (o.isEmpty() ? command + " exited with " + rc : o);
            }
            @Override public String launcher() {
                try { return Service.resolveExec(null); } catch (Exception e) { return "researchzosho"; }
            }
        };
    }

    static String plain(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.ConnectException || (m != null && m.contains("Connection refused"))) return "nothing is answering at that address";
        if (e instanceof java.net.http.HttpTimeoutException) return "it did not answer in time";
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    // ---- the conversation ----

    String ask(String question, String dflt) throws IOException {
        String shown = dflt == null || dflt.isEmpty() ? "" : " [" + dflt + "]";
        out.print(question + shown + " ");
        out.flush();
        if (yes) { out.println(dflt == null ? "" : dflt); return dflt == null ? "" : dflt; }
        String line = in.readLine();
        if (line == null) return dflt == null ? "" : dflt;
        line = line.strip();
        return line.isEmpty() ? (dflt == null ? "" : dflt) : line;
    }

    /** The fallback of the embeddings step: an address the person has, or words only. */
    private void askEmbedAddress(String key) throws IOException {
        String typed = ask("  An embeddings server lets search work by meaning, not just by words. Address? (or leave blank for words only)", "");
        if (!typed.isBlank() && probe.embeds(typed, key)) { Config.set("RESEARCHZOSHO_EMBED", typed); out.println("  Search by meaning is on."); }
        else { Config.set("RESEARCHZOSHO_EMBED", "off"); out.println("  Search is by words. `researchzosho embed start` starts a server with Docker later, or run setup again with an address."); }
    }

    boolean yesNo(String question, boolean dflt) throws IOException {
        String a = ask(question, dflt ? "Y/n" : "y/N");
        if (a.equals("Y/n")) return true;
        if (a.equals("y/N")) return false;
        a = a.toLowerCase(Locale.ROOT);
        return a.startsWith("y") || a.equals("yes") ? true : a.startsWith("n") ? false : dflt;
    }

    static boolean local(String base) {
        try { String h = URI.create(base).getHost(); return h == null || h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.endsWith(".local"); }
        catch (Exception e) { return false; }
    }

    /** Run the whole thing. Returns the exit code. */
    public int run(int port, boolean offerService, boolean offerClaude) throws Exception {
        out.println("ResearchZosho setup. A few questions; press Enter to take the answer in brackets.");
        out.println();

        // 1. the library folder
        String currentLib = Config.get("RESEARCHZOSHO_LIBRARY");
        String dfltLib = currentLib != null && !currentLib.isBlank() ? currentLib : Path.of(System.getProperty("user.home"), "researchzosho-library").toString();
        String lib = ask("Where should the library live?", dfltLib);
        Path libPath = Path.of(lib.replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath().normalize();
        Config.set("RESEARCHZOSHO_LIBRARY", libPath.toString());
        LibraryStore store = new LibraryStore(libPath);
        if (Files.isDirectory(libPath.resolve("catalog"))) {
            out.println("  Using the library already at " + libPath + ".");
        } else {
            store.init();
            out.println("  Made a new library at " + libPath + ".");
        }
        out.println();

        // 2. the model
        String key = Config.get("RESEARCHZOSHO_API_KEY");
        String base = null, model = null;
        String configured = Config.get("RESEARCHZOSHO_DRIVE");
        List<String> tried = new ArrayList<>();
        if (configured != null && !configured.isBlank()) tried.add(configured);
        for (String p : LOCAL_PORTS) tried.add("http://localhost:" + p);
        for (String cand : tried) {
            List<String> ids = probe.models(cand, local(cand) ? null : key);
            if (ids == null) continue;
            String first = ids.isEmpty() ? Config.get("RESEARCHZOSHO_MODEL", "default") : ids.get(0);
            String preferred = Config.get("RESEARCHZOSHO_MODEL");
            if (preferred != null && ids.contains(preferred)) first = preferred;
            out.println("  Found a model server at " + cand + (ids.isEmpty() ? "." : " offering " + String.join(", ", ids.subList(0, Math.min(5, ids.size()))) + (ids.size() > 5 ? ", …" : "") + "."));
            if (yesNo("  Use it?", true)) {
                base = cand;
                model = ids.size() > 1 ? ask("  Which model?", first) : first;
            }
            break;
        }
        if (base == null) {
            out.println("  No model server was found on the usual local ports.");
            out.println("  A local server (llama.cpp, Ollama, LM Studio) or a hosted API that speaks the OpenAI chat API");
            out.println("  (OpenAI, DeepSeek, Gemini, OpenRouter…) both work. A hosted API needs its key.");
            String typed = ask("  Where is your model server? (an address such as http://localhost:11434 or https://api.openai.com/v1, or leave blank)", "");
            if (!typed.isBlank()) {
                base = typed;
                if (!local(base) && (key == null || key.isBlank())) {
                    String k = ask("  Does it need a key? (paste it, or leave blank)", "");
                    if (!k.isBlank()) key = k;
                }
                List<String> ids = probe.models(base, key);
                model = ask("  Which model?", ids != null && !ids.isEmpty() ? ids.get(0) : Config.get("RESEARCHZOSHO_MODEL", ""));
            }
        }
        if (base != null) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                out.print("  Asking it to say hello… "); out.flush();
                String reply = probe.chat(base, model, local(base) ? key : key);
                if (!reply.startsWith("!")) { out.println("it answered: \"" + reply + "\""); break; }
                out.println("no: " + reply.substring(1) + ".");
                if (attempt == 3 || !yesNo("  Try a different address?", true)) break;
                base = ask("  Address:", base);
                if (!local(base) && (key == null || key.isBlank())) { String k = ask("  Key (or leave blank):", ""); if (!k.isBlank()) key = k; }
                model = ask("  Model:", model == null ? "" : model);
            }
            Config.set("RESEARCHZOSHO_DRIVE", base);
            if (model != null && !model.isBlank()) Config.set("RESEARCHZOSHO_MODEL", model);
            if (key != null && !key.isBlank()) Config.set("RESEARCHZOSHO_API_KEY", key);
        } else {
            out.println("  Fine. Asking what the library holds works without a model; research runs wait for one.");
            out.println("  When you have one: researchzosho setup, or put RESEARCHZOSHO_DRIVE in " + Config.userConfigPath());
        }
        out.println();

        // 3. web search, in the order that serves the person best: the Brave Search API (best results, a key,
        //    no install) first; then SearXNG (free, private: one already running, or started through Docker);
        //    then the built-in fallback, which needs nothing and is the weakest. A run never comes back
        //    empty for want of a search backend without the wizard having said so.
        String braveKey = Config.get("RESEARCHZOSHO_BRAVE_KEY");
        boolean haveBrave = braveKey != null && !braveKey.isBlank() && probe.brave(braveKey);
        if (haveBrave) out.println("  Web search: the Brave Search API key you have works.");
        else {
            out.println("  Research runs search the web. The Brave Search API gives the best results and has a free plan");
            out.println("  (https://brave.com/search/api/).");
            String typed = ask("  Paste a Brave API key, or press Enter to skip", "");
            if (!typed.isBlank()) {
                if (probe.brave(typed)) { Config.set("RESEARCHZOSHO_BRAVE_KEY", typed); haveBrave = true; out.println("  Web search: Brave."); }
                else out.println("  Brave did not accept that key.");
            }
        }
        String searx = Config.get("RESEARCHZOSHO_SEARXNG");
        String searxAt = searx != null && !searx.isBlank() ? searx : "http://localhost:" + Searx.DEFAULT_PORT;
        boolean haveSearx = probe.searxng(searxAt);
        if (haveSearx) { out.println("  SearXNG answers at " + searxAt + (haveBrave ? ": the fallback behind Brave." : ": web search goes through it.")); Config.set("RESEARCHZOSHO_SEARXNG", searxAt); }
        else if (acts.haveDocker() && yesNo(haveBrave ? "  Also start SearXNG with Docker, as the fallback? (free, and your searches stay on this machine)"
                                                    : "  Start SearXNG with Docker? (free, and your searches stay on this machine)", !haveBrave)) {
            out.print("  Starting SearXNG… "); out.flush();
            String r = acts.startSearxng(Searx.DEFAULT_PORT);
            if (r.startsWith("!")) out.println("no: " + r.substring(1));
            else { haveSearx = true; Config.set("RESEARCHZOSHO_SEARXNG", r); out.println("it answers at " + r + "."); }
        }
        if (!haveBrave && !haveSearx) {
            out.println("  Web search will use the built-in fallback: Wikipedia plus the papers in Crossref and OpenAlex, no key");
            out.println("  and no install. It finds reference pages and the literature, not the whole web; a Brave key or SearXNG");
            out.println("  does that. Run setup again when you have one. RESEARCHZOSHO_FALLBACK_SEARCH=off turns the fallback off.");
        }
        out.println();

        // 4. embeddings
        String embed = Config.get("RESEARCHZOSHO_EMBED");
        boolean haveEmbed = embed != null && !embed.isBlank() && !embed.equalsIgnoreCase("off") && probe.embeds(embed, key);
        if (!haveEmbed && base != null && probe.embeds(base, key)) { embed = base; haveEmbed = true; }
        if (haveEmbed) {
            out.println("  Search by meaning is on: embeddings at " + embed + ".");
            Config.set("RESEARCHZOSHO_EMBED", embed);
        } else {
            // one already started this way answers on its port; else Docker can start one; else an address, or words only
            String own = "http://127.0.0.1:" + Embed.DEFAULT_PORT;
            if (probe.embeds(own, key)) {
                Config.set("RESEARCHZOSHO_EMBED", own); out.println("  Search by meaning is on: the embeddings server started with Docker answers at " + own + ".");
            } else if (acts.haveDocker() && acts.embedGpu() && yesNo("  Start an embeddings server with Docker on your GPU, so search works by meaning as well as by words? (the model downloads once, about 1.2 GB)", true)) {
                out.print("  Starting it… "); out.flush();
                String r = acts.startEmbed(Embed.DEFAULT_PORT);
                if (r.startsWith("!")) { out.println("no: " + r.substring(1)); askEmbedAddress(key); }
                else { Config.set("RESEARCHZOSHO_EMBED", r); out.println("it answers at " + r + ". Search by meaning is on."); }
            } else {
                askEmbedAddress(key);
            }
        }
        out.println();

        // 5. the service
        boolean serviceInstalled = false;
        if (offerService && yesNo("Run it as a service that starts when you log in? (needed for research runs and for other programs)", true)) {
            int rc = acts.installService(port, out);
            serviceInstalled = rc == 0;
            out.println(serviceInstalled ? "  The service is on, at http://127.0.0.1:" + port + "." : "  The service did not install (see above). You can try again with: researchzosho service install");
            out.println();
        }

        // 6. Programs that use the library. Claude Code, Codex and Gemini CLI can each be registered from the command
        //    line, so when one is installed the wizard offers to do that. Every other program gets the lines it needs, printed.
        boolean anyDone = false;
        if (offerClaude) for (Host h : HOSTS) {
            if (!acts.have(h.command()) || !yesNo(h.name() + " is installed. Connect it to the library?", true)) continue;
            String who = System.getProperty("user.name", "me");
            String did = "did:key:local-" + h.command() + "-" + Long.toHexString(Double.doubleToLongBits(Math.random())).substring(0, 12);
            Patrons.set(store, did, who + " (" + h.name() + ")", Patrons.Level.write);
            List<String> args = new ArrayList<>();
            String url = "http://127.0.0.1:" + port + "/rpc";
            boolean http = serviceInstalled && !h.command().equals("codex");   // codex keeps its tokens in environment variables: stdio is simpler
            String token = http ? Patrons.issueToken(store, did) : null;
            switch (h.command()) {
                case "claude" -> { args.addAll(List.of("mcp", "add", "--scope", "user")); if (http) args.addAll(List.of("--transport", "http", "librarian", url, "--header", "Authorization: Bearer " + token)); else args.addAll(List.of("librarian", "--", acts.launcher(), "mcp")); }
                case "codex" -> args.addAll(List.of("mcp", "add", "librarian", "--", acts.launcher(), "mcp"));
                case "gemini" -> { args.addAll(List.of("mcp", "add", "-s", "user")); if (http) args.addAll(List.of("-t", "http", "librarian", url, "-H", "Authorization: Bearer " + token)); else args.addAll(List.of("librarian", acts.launcher(), "mcp")); }
                default -> { }
            }
            String r = acts.register(h.command(), args);
            out.println(r.startsWith("!") ? "  Could not connect " + h.name() + ": " + r.substring(1) : "  " + h.name() + " is connected: every session can use the library.");
            anyDone = true;
            out.println();
        }
        boolean claudeDone = anyDone;
        if (offerClaude) {
            out.println("  Other programs. Any program that speaks MCP can use the library" + (claudeDone ? " the same way" : "") + ":");
            out.println("    over stdio:  command \"" + acts.launcher() + "\" with the argument \"mcp\"");
            out.println("    as JSON:     {\"mcpServers\": {\"librarian\": {\"command\": \"" + acts.launcher().replace("\\", "\\\\") + "\", \"args\": [\"mcp\"]}}}");
            if (serviceInstalled) out.println("    over HTTP:   http://127.0.0.1:" + port + "/rpc with a token from: researchzosho reader token <did>");
            out.println("  The web pages need no program: http://127.0.0.1:" + port + "/ once the service runs. codezaiku chat connects to the service (codezaiku install researchzosho sets it up).");
            out.println();
        }

        // 7. the first document and the first answer
        String doc = ask("Add a document now? (a file path, or leave blank)", "");
        if (!doc.isBlank()) {
            Path d = Path.of(doc.replaceFirst("^~", System.getProperty("user.home")));
            if (!Files.exists(d)) out.println("  There is no file at " + d + ".");
            else {
                int rc = LibrarianCli.add(store, new String[]{"researchzosho", "add", d.toString()});
                if (rc == 0) {
                    out.println("  Shelved " + d.getFileName() + ".");
                    String q = ask("  Ask a question about it? (or leave blank)", "");
                    if (!q.isBlank()) {
                        out.println();
                        out.println(LibraryPush.answerPackage(q, 6).stripTrailing());
                    }
                }
            }
            out.println();
        }

        out.println("Done. Your settings are in " + Config.userConfigPath() + "; run setup again any time to change one.");
        out.println("Next:  researchzosho ask \"…\"        what the library holds");
        out.println("       researchzosho add <file>      shelve a document, or a folder with --collection");
        out.println("       researchzosho status           what is there, and what is waiting for you");
        return 0;
    }
}
