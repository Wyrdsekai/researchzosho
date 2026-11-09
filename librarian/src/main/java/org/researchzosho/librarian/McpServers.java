package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The other MCP servers whose tools the library's research may use as sources: the keeper's list, kept in {@code catalog/mcp-servers.json}.
 * Each server has a name, the way it is reached (a command this computer starts, or an address spoken to over Streamable HTTP), what the
 * keeper decided about its tools (allowed onto research tables, kept off), whether readers' runs and chats may use it, its monthly call cap,
 * and the tools it listed the last time it answered, which the tables are built from without starting it. A key a server wants lives in the
 * settings as a secret ({@link #keySetting}); this file holds only the setting's name.
 */
public final class McpServers {
    private McpServers() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** A server's name: one short word of small letters, digits and dashes. */
    static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,23}$");
    static final String NAME_RULE = "A server's name is one short word of small letters, digits and dashes, such as docs or my-notes.";
    /** The prefix of the settings that hold the keys the keeper's own servers want. */
    static final String KEY_PREFIX = "RESEARCHZOSHO_MCP_";

    /** One server, as the keeper set it up and as it last answered. */
    public static final class Server {
        public String name = "", transport = "stdio", url = "", header = "", keySetting = "", preset = "", added = "", listedAt = "", protocol = "", serverInfo = "", lastError = "";
        public List<String> command = new ArrayList<>();
        public Map<String, String> env = new LinkedHashMap<>();
        public boolean readers = false;
        public Set<String> allowed = new LinkedHashSet<>(), forbidden = new LinkedHashSet<>();
        public int timeoutSeconds = 60, cap = 0;
        public List<McpClient.Tool> tools = new ArrayList<>();

        public boolean stdio() { return "stdio".equals(transport); }

        /** How it is reached, as one line: what the connection pool compares to know a change. */
        String config() { return transport + "\n" + String.join("\u0000", command) + "\n" + env + "\n" + url + "\n" + header + "\n" + keySetting + "\n" + timeoutSeconds; }

        /** The header the key goes in, and whether its value is "Bearer <key>". */
        String headerName() { return header.isBlank() ? "Authorization" : header; }
        boolean bearer() { return headerName().equalsIgnoreCase("Authorization"); }
        /** Whether a key is set for it (the setting it names holds a value). */
        public boolean keySet() { if (keySetting.isBlank()) return false; String v = Config.get(keySetting); return v != null && !v.isBlank(); }

        ObjectNode json() {
            ObjectNode o = M.createObjectNode();
            o.put("name", name).put("transport", transport);
            if (stdio()) { ArrayNode c = o.putArray("command"); command.forEach(c::add); if (!env.isEmpty()) { ObjectNode e = o.putObject("env"); env.forEach(e::put); } }
            else { o.put("url", url); if (!header.isBlank()) o.put("header", header); if (!keySetting.isBlank()) o.put("key_setting", keySetting); }
            if (!preset.isBlank()) o.put("preset", preset);
            o.put("readers", readers);
            ArrayNode a = o.putArray("allowed"); allowed.forEach(a::add);
            ArrayNode f = o.putArray("forbidden"); forbidden.forEach(f::add);
            o.put("timeout_seconds", timeoutSeconds).put("cap", cap).put("added", added);
            o.set("tools", McpClient.json(tools));
            o.put("listed_at", listedAt).put("protocol", protocol).put("server_info", serverInfo).put("last_error", lastError);
            return o;
        }

        static Server of(JsonNode j) {
            Server s = new Server();
            s.name = j.path("name").asText(""); s.transport = j.path("transport").asText("stdio");
            for (JsonNode c : j.path("command")) s.command.add(c.asText());
            j.path("env").properties().forEach(e -> s.env.put(e.getKey(), e.getValue().asText("")));
            s.url = j.path("url").asText(""); s.header = j.path("header").asText(""); s.keySetting = j.path("key_setting").asText(""); s.preset = j.path("preset").asText("");
            s.readers = j.path("readers").asBoolean(false);
            for (JsonNode x : j.path("allowed")) s.allowed.add(x.asText());
            for (JsonNode x : j.path("forbidden")) s.forbidden.add(x.asText());
            s.timeoutSeconds = Math.max(1, j.path("timeout_seconds").asInt(60)); s.cap = Math.max(0, j.path("cap").asInt(0)); s.added = j.path("added").asText("");
            for (JsonNode t : j.path("tools")) { McpClient.Tool x = McpClient.Tool.of(t); if (x != null) s.tools.add(x); }
            s.listedAt = j.path("listed_at").asText(""); s.protocol = j.path("protocol").asText(""); s.serverInfo = j.path("server_info").asText(""); s.lastError = j.path("last_error").asText("");
            return s;
        }

        /** Where it is, in a few words: the command or the address. */
        public String where() { return stdio() ? String.join(" ", command) : url; }
    }

    // ---------------------------------------------------------------- presets

    /**
     * A hosted MCP server the library knows by name, so {@code mcp add exa} needs no address: where it is, the setting its key lives in and
     * the header the key goes in (it works without a key, slower and rate-limited, and uses the key once one is set), the default monthly
     * cap, the tools that only read (on research tables whatever their words say), and the tools that run the service's own research agent,
     * which cost more, take minutes and stay off a small model's table.
     */
    public record Preset(String name, String title, String url, String keySetting, String header, int cap, Set<String> reads, Set<String> agents) { }

    /**
     * The default monthly call caps of the two presets: the limits of the search places of the same services (exa_monthly_limit, 714, which
     * keeps Exa under $5 a month at $7 per 1,000 searches; tavily_monthly_limit, 1,000, Tavily's free allowance; both read on 2026-11-04).
     */
    static final int EXA_CAP = 714, TAVILY_CAP = 1000;

    static final List<Preset> PRESETS = List.of(
            new Preset("exa", "Exa, a search engine for the web with a reader of pages", "https://mcp.exa.ai/mcp", "RESEARCHZOSHO_EXA_KEY", "x-api-key", EXA_CAP,
                    Set.of("web_search_exa", "web_fetch_exa", "web_search_advanced_exa", "agent_run"), Set.of("agent_run")),
            new Preset("tavily", "Tavily, a search engine for the web with a reader and a crawler of pages", "https://mcp.tavily.com/mcp/", "RESEARCHZOSHO_TAVILY_KEY", "Authorization", TAVILY_CAP,
                    Set.of("tavily_search", "tavily_extract", "tavily_crawl", "tavily_map", "tavily_research"), Set.of("tavily_research")));

    public static List<Preset> presets() { return PRESETS; }

    /** The preset of this name, or null. */
    public static Preset preset(String name) {
        for (Preset p : PRESETS) if (p.name().equalsIgnoreCase(name == null ? "" : name.strip())) return p;
        return null;
    }

    /** The preset a server is: the one it was added as, else the one whose host its address is at; null for none. */
    static Preset presetOf(Server s) {
        if (s == null) return null;
        Preset p = preset(s.preset);
        if (p != null) return p;
        if (s.stdio() || s.url.isBlank()) return null;
        String host = host(s.url);
        for (Preset x : PRESETS) if (host.equalsIgnoreCase(host(x.url()))) return x;
        return null;
    }

    static String host(String url) { try { String h = URI.create(url).getHost(); return h == null ? "" : h; } catch (IllegalArgumentException e) { return ""; } }

    /**
     * Servers known by the name they give themselves, with the tools of theirs that only read: everything else of theirs stays off research
     * tables. A ResearchZosho library is one: its tools that shelve, file, tell or send are written as nouns, which no rule of words reads.
     */
    static final Map<String, Set<String>> ONLY_READS = Map.of("researchzosho", Set.of("library_ask", "library_search", "library_get", "library_read", "library_established",
            "library_subjects", "library_status", "library_changes", "library_map", "library_perspectives", "library_explain", "library_sharpen", "library_help",
            "library_dossier", "library_holdings", "library_look", "library_candidates", "library_sift", "library_records", "library_board", "library_patents", "library_patent_abstracts", "library_patent_claims"));

    /** The tools that only read of a server known by its own name, or null for a server not known so. */
    static Set<String> onlyReads(Server s) {
        if (s == null || s.serverInfo.isBlank()) return null;
        return ONLY_READS.get(s.serverInfo.split(" ", 2)[0].toLowerCase(Locale.ROOT));
    }

    /** The setting a key of an MCP server lives in, as a secret setting: the keeper's own (RESEARCHZOSHO_MCP_…) or a preset's; null for any other name. */
    static Settings.Setting keySetting(String key) {
        if (key == null) return null;
        if (key.startsWith(KEY_PREFIX) && key.length() > KEY_PREFIX.length())
            return new Settings.Setting(key, Settings.Kind.secret, "A key an MCP server of yours wants, sent in a header to that server and nowhere else (researchzosho mcp list names the server).", "");
        for (Preset p : PRESETS) if (p.keySetting().equals(key))
            return new Settings.Setting(key, Settings.Kind.secret, "A key for " + p.title() + " (" + host(p.url()) + "): its MCP server answers without one, slower and limited, and uses the key once it is set; the key is sent to that server alone.", "");
        return null;
    }

    /** The name a key setting is set by: RESEARCHZOSHO_MCP_DOCS_KEY for "docs_key" or "mcp_docs_key"; a preset's or a known secret setting kept as it is. */
    static String keySettingName(String given) {
        String n = given == null ? "" : given.strip().replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        if (n.isEmpty()) return "";
        if (!n.startsWith(Config.PREFIX)) n = Config.PREFIX + n;
        for (Settings.Setting s : Settings.known()) if (s.key().equals(n)) return n;
        for (Preset p : PRESETS) if (p.keySetting().equals(n)) return n;
        return n.startsWith(KEY_PREFIX) ? n : KEY_PREFIX + n.substring(Config.PREFIX.length());
    }

    // ---------------------------------------------------------------- the file

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("mcp-servers.json"); }
    /** Where a server's program writes what it says on stderr. */
    static Path log(LibraryStore store, String name) { return store.root().resolve("catalog").resolve("mcp").resolve(name + ".log"); }

    private static final Object LOCK = new Object();

    /** Every server, in the order they were added. */
    public static List<Server> all(LibraryStore store) throws IOException {
        Path f = file(store);
        if (!Files.isRegularFile(f)) return new ArrayList<>();
        JsonNode j;
        try { j = M.readTree(f.toFile()); } catch (IOException e) { throw new IOException("The list of MCP servers in " + f + " could not be read: " + e.getMessage()); }
        List<Server> out = new ArrayList<>();
        for (JsonNode s : j.path("servers")) { Server x = Server.of(s); if (NAME.matcher(x.name).matches()) out.add(x); }
        return out;
    }

    /** The server of this name, or null. */
    public static Server named(LibraryStore store, String name) throws IOException {
        for (Server s : all(store)) if (s.name.equals(name == null ? "" : name.strip().toLowerCase(Locale.ROOT))) return s;
        return null;
    }

    static void write(LibraryStore store, List<Server> servers) throws IOException {
        ObjectNode o = M.createObjectNode();
        ArrayNode a = o.putArray("servers");
        for (Server s : servers) a.add(s.json());
        Path f = file(store);
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".writing-" + ProcessHandle.current().pid() + "-" + Thread.currentThread().threadId());
        Files.writeString(tmp, M.writerWithDefaultPrettyPrinter().writeValueAsString(o), StandardCharsets.UTF_8);
        try { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING); }
    }

    /** Adds the server, or replaces the one of its name. */
    static void put(LibraryStore store, Server s) throws IOException {
        synchronized (LOCK) {
            List<Server> all = all(store);
            all.removeIf(x -> x.name.equals(s.name));
            all.add(s);
            write(store, all);
        }
    }

    /** Changes one server in place; false when there is none of that name. */
    static boolean update(LibraryStore store, String name, Consumer<Server> change) throws IOException {
        synchronized (LOCK) {
            List<Server> all = all(store);
            boolean found = false;
            for (Server s : all) if (s.name.equals(name)) { change.accept(s); found = true; }
            if (found) write(store, all);
            return found;
        }
    }

    static boolean remove(LibraryStore store, String name) throws IOException {
        synchronized (LOCK) {
            List<Server> all = all(store);
            boolean gone = all.removeIf(x -> x.name.equals(name));
            if (gone) write(store, all);
            return gone;
        }
    }

    /** What a server said the last time it was asked: the tools it listed and the version agreed, or why it did not answer (the tools of before kept). */
    static void listed(LibraryStore store, String name, List<McpClient.Tool> tools, McpClient client, String error) {
        try {
            update(store, name, s -> {
                if (tools != null) { s.tools = new ArrayList<>(tools); s.listedAt = Instant.now().toString().replaceFirst("\\.\\d+Z$", "Z"); }
                if (client != null && !client.protocol().isBlank()) { s.protocol = client.protocol(); s.serverInfo = (client.serverName() + " " + client.serverVersion()).strip(); }
                s.lastError = error == null ? "" : error;
            });
        } catch (IOException ignored) { /* the list is a record of the last answer; the call itself stands */ }
    }
}
