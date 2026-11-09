package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpClient;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The keeper's operations on the other MCP servers the library uses as sources: list them (and whether each answers), show a server's
 * tools and which research may call, add one (a command this computer starts, an address, or a preset by its name), remove one, allow or
 * forbid a tool, let readers use a server, cap its calls a month, test it, and call one of its tools as research would. One op of the
 * protocol ({@code mcp}), which the command line, the Keep page and the MCP tool {@code library_mcp} are views of. The keeper only: a stdio
 * server is a program this computer runs.
 */
final class McpSetup {
    private McpSetup() { }
    private static final ObjectMapper M = new ObjectMapper();

    static final String OPS = "op is list, tools, test, add, remove, allow, forbid, readers, cap or call.";

    /** Throws {@code forbidden} for anyone but the keeper; a read key of the keeper's may look, not change. */
    static void keeper(Patrons.Patron patron, boolean change) {
        if (change && Patrons.KEY_READ.equals(patron.runtime())) throw ProtocolError.forbidden("This key reads only. A key that writes is made on the Keep page, or with researchzosho keys new --write.");
        if (patron.person() || (patron.web() && !WebAccess.signInRequired())) return;
        throw ProtocolError.forbidden("Only the person who keeps this library sets up the other MCP servers it uses, at the terminal of its computer or on its Keep page.");
    }

    /** The op: its result written into {@code r}, with {@code text} in sentences and {@code note} for a change. */
    static ObjectNode op(LibraryStore store, Patrons.Patron patron, JsonNode args, ObjectNode r) throws IOException {
        String op = args.path("op").asText("list").strip().toLowerCase(Locale.ROOT);
        keeper(patron, !Set.of("list", "tools", "test").contains(op));
        switch (op) {
            case "list" -> list(store, args.path("check").asBoolean(true), r);
            case "tools", "test" -> tools(store, server(store, args), r);
            case "add" -> add(store, args, r);
            case "remove" -> {
                McpServers.Server s = server(store, args);
                McpServers.remove(store, s.name);
                McpConnections.close(store, s.name);
                r.put("name", s.name).put("removed", true);
                say(r, "The MCP server " + s.name + " is removed. Research no longer calls it." + (s.keySetting.isBlank() || McpServers.preset(s.preset) != null ? "" : " Its key stays in the setting " + shortName(s.keySetting) + "; researchzosho settings unset " + shortName(s.keySetting) + " forgets it."));
            }
            case "allow", "forbid" -> {
                McpServers.Server s = server(store, args);
                String tool = args.path("tool").asText("").strip();
                if (tool.isEmpty()) throw ProtocolError.invalidArgs("Name the tool, one of " + names(s) + ".");
                boolean allow = op.equals("allow");
                McpServers.update(store, s.name, x -> { if (allow) { x.allowed.add(tool); x.forbidden.remove(tool); } else { x.forbidden.add(tool); x.allowed.remove(tool); } });
                boolean known = s.tools.stream().anyMatch(t -> t.name().equals(tool));
                r.put("name", s.name).put("tool", tool).put("on", allow);
                say(r, (allow ? "Research may now call " + s.name + "." + tool + ", although it may change things on that server: research calls it when the question needs it."
                        : "Research no longer calls " + s.name + "." + tool + ".") + (known ? "" : " The server has not listed a tool of that name yet; the choice holds when it does."));
            }
            case "readers" -> {
                McpServers.Server s = server(store, args);
                String v = args.path("value").asText(args.path("readers").asText("")).strip().toLowerCase(Locale.ROOT);
                if (!Set.of("on", "off", "true", "false", "yes", "no").contains(v)) throw ProtocolError.invalidArgs("readers takes on or off.");
                boolean on = Set.of("on", "true", "yes").contains(v);
                McpServers.update(store, s.name, x -> x.readers = on);
                r.put("name", s.name).put("readers", on);
                say(r, on ? "Readers' research and chats may now use " + s.name + " too." : "Only you and the people who may write use " + s.name + " now.");
            }
            case "cap" -> {
                McpServers.Server s = server(store, args);
                String v = args.path("value").asText(args.path("cap").asText("")).strip().toLowerCase(Locale.ROOT);
                int cap;
                if (v.equals("off") || v.equals("none") || v.equals("0")) cap = 0;
                else { try { cap = Integer.parseInt(v.replace(",", "")); } catch (NumberFormatException e) { throw ProtocolError.invalidArgs("The cap is a number of calls a month, or off."); } }
                if (cap < 0) throw ProtocolError.invalidArgs("The cap is a number of calls a month, or off.");
                McpServers.update(store, s.name, x -> x.cap = cap);
                r.put("name", s.name).put("cap", cap).put("used_this_month", McpSources.used(s));
                say(r, cap == 0 ? "Research may call " + s.name + " as often as it needs now; there is no monthly cap." : "Research may call " + s.name + " at most " + cap + " times a month. This month it has made " + McpSources.used(s) + " calls.");
            }
            case "call" -> call(store, args, r);
            default -> throw ProtocolError.invalidArgs(OPS);
        }
        return r;
    }

    private static void say(ObjectNode r, String note) { r.put("note", note); r.put("text", note); }

    static McpServers.Server server(LibraryStore store, JsonNode args) throws IOException {
        String name = args.path("name").asText("").strip().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) throw ProtocolError.invalidArgs("Name the server." + known(store));
        McpServers.Server s = McpServers.named(store, name);
        if (s == null) throw ProtocolError.invalidArgs("No MCP server is called " + name + "." + known(store));
        return s;
    }

    private static String known(LibraryStore store) throws IOException {
        List<McpServers.Server> all = McpServers.all(store);
        return all.isEmpty() ? " None is set up yet; researchzosho mcp add sets one up." : " The servers are: " + String.join(", ", all.stream().map(s -> s.name).toList()) + ".";
    }

    private static String names(McpServers.Server s) { return s.tools.isEmpty() ? "the tools it lists" : String.join(", ", s.tools.stream().map(McpClient.Tool::name).toList()); }

    static String shortName(String key) { return key.replaceFirst("^RESEARCHZOSHO_", "").toLowerCase(Locale.ROOT); }

    // ---------------------------------------------------------------- list, tools

    static void list(LibraryStore store, boolean check, ObjectNode r) throws IOException {
        List<McpServers.Server> all = McpServers.all(store);
        ArrayNode a = r.putArray("servers");
        StringBuilder text = new StringBuilder();
        if (all.isEmpty()) {
            text.append("No other MCP server is set up. To add one that runs on this computer: researchzosho mcp add <a short name> -- <the command that starts it>. "
                    + "To add one at an address: researchzosho mcp add <a short name> <its https address>. Exa and Tavily, two search engines, are added by their names alone: researchzosho mcp add exa");
            r.put("text", text.toString());
            return;
        }
        text.append("The library uses ").append(all.size()).append(all.size() == 1 ? " other MCP server" : " other MCP servers").append(" as sources.\n");
        for (McpServers.Server s : all) {
            String answer = "";
            if (check) answer = answer(store, s);
            McpServers.Server now = McpServers.named(store, s.name);
            if (now == null) now = s;
            ObjectNode v = view(store, now);
            if (check) { v.put("answers", answer.isEmpty()); v.put("said", answer.isEmpty() ? "It answers." : answer); }
            a.add(v);
            text.append('\n').append(describe(store, now, check ? answer : null));
        }
        r.put("text", text.toString().strip());
    }

    /** Whether the server answers now: "" when it does, else why not, in a sentence. Its tools are listed again on the way. */
    static String answer(LibraryStore store, McpServers.Server s) {
        try { McpConnections.close(store, s.name); McpConnections.open(store, s); return ""; }
        catch (IOException e) { return "It did not answer: " + e.getMessage(); }
    }

    static void tools(LibraryStore store, McpServers.Server s, ObjectNode r) throws IOException {
        String answer = answer(store, s);
        McpServers.Server now = McpServers.named(store, s.name);
        if (now == null) now = s;
        r.set("server", view(store, now));
        r.put("answers", answer.isEmpty());
        r.set("tools", view(store, now).get("tools"));
        r.put("text", describeTools(now, answer));
    }

    /** A server as the protocol returns it: how it is reached, its key's setting and whether it is set (never the key), its marks, this month's calls, its tools with whether research may call each and why not. */
    static ObjectNode view(LibraryStore store, McpServers.Server s) {
        ObjectNode o = M.createObjectNode();
        o.put("name", s.name).put("transport", s.transport);
        if (s.stdio()) { ArrayNode c = o.putArray("command"); s.command.forEach(c::add); if (!s.env.isEmpty()) { ObjectNode e = o.putObject("env"); s.env.forEach(e::put); } }
        else { o.put("url", s.url); if (!s.keySetting.isBlank()) { o.put("header", s.headerName()).put("key_setting", shortName(s.keySetting)).put("key_set", s.keySet()); } }
        if (!s.preset.isBlank()) o.put("preset", s.preset);
        o.put("readers", s.readers).put("cap", s.cap).put("used_this_month", McpSources.used(s)).put("timeout_seconds", s.timeoutSeconds);
        o.put("listed_at", s.listedAt).put("protocol", s.protocol).put("server", s.serverInfo);
        if (!s.lastError.isBlank()) o.put("last_error", s.lastError);
        McpServers.Preset p = McpServers.presetOf(s);
        ArrayNode ts = o.putArray("tools");
        for (McpClient.Tool t : s.tools) {
            String off = McpSources.offBecause(s, t);
            ObjectNode x = ts.addObject();
            x.put("name", t.name()).put("instrument", s.name + "." + t.name()).put("on", off.isEmpty());
            if (!off.isEmpty()) x.put("why", off);
            if (p != null && p.agents().contains(t.name())) x.put("agent", true);
            x.put("description", Untrusted.line(t.description(), 200));
        }
        return o;
    }

    /** One server in sentences: what and where it is, whether it answers, its key, what research may call, who may use it, the month's calls. */
    static String describe(LibraryStore store, McpServers.Server s, String answer) {
        StringBuilder b = new StringBuilder();
        McpServers.Preset p = McpServers.presetOf(s);
        b.append(s.name).append(" — ").append(p != null ? p.title() + ", at " + s.url : s.stdio() ? "a program this computer starts: " + s.where() : "a server at " + s.url).append(".\n");
        if (answer != null) b.append("  ").append(answer.isEmpty() ? "It answers" + (s.protocol.isBlank() ? "" : " (MCP " + s.protocol + (s.serverInfo.isBlank() ? "" : ", " + Untrusted.line(s.serverInfo, 60)) + ")") + "." : answer).append('\n');
        else if (!s.lastError.isBlank()) b.append("  The last time it was asked, it did not answer: ").append(s.lastError).append('\n');
        if (!s.stdio() && !s.keySetting.isBlank()) b.append("  ").append(keyLine(s)).append('\n');
        List<String> on = new ArrayList<>(), off = new ArrayList<>();
        for (McpClient.Tool t : s.tools) (McpSources.offBecause(s, t).isEmpty() ? on : off).add(t.name() + (p != null && p.agents().contains(t.name()) ? " (" + agentWords(p) + ")" : ""));
        if (s.tools.isEmpty()) b.append("  It has listed no tools yet.\n");
        else {
            b.append("  Research may call ").append(on.isEmpty() ? "none of its " + s.tools.size() + " tools" : on.size() + " of its " + s.tools.size() + (s.tools.size() == 1 ? " tool" : " tools") + ": " + String.join(", ", on)).append(".\n");
            if (!off.isEmpty()) b.append("  Kept off research: ").append(String.join(", ", off)).append(". researchzosho mcp tools ").append(s.name).append(" says why.\n");
        }
        b.append("  ").append(s.readers ? "You, the people who may write, and readers use it." : "Only you and the people who may write use it; researchzosho mcp readers " + s.name + " on lets readers use it too.").append('\n');
        int used = McpSources.used(s);
        b.append("  This month research has called it ").append(used).append(used == 1 ? " time" : " times").append(McpSources.sharedWith(s)).append(McpSources.cap(s) > 0 ? ", of the " + McpSources.cap(s) + " it may." : "; there is no monthly cap.").append('\n');
        return b.toString();
    }

    static String agentWords(McpServers.Preset p) { return "its own research: it costs more than a search and takes minutes"; }

    static String keyLine(McpServers.Server s) {
        McpServers.Preset p = McpServers.presetOf(s);
        String set = "researchzosho settings set " + shortName(s.keySetting) + " <the key>";
        if (s.keySet()) return "Its key is set, in the setting " + shortName(s.keySetting) + "; it is sent in the " + s.headerName() + " header to this server alone.";
        if (p != null) return "No key is set, so it is used without one, slower and limited. To use yours: " + set;
        return "Its key is read from the setting " + shortName(s.keySetting) + ", which is not set yet. To set it: " + set;
    }

    /** A server's tools in sentences: what research may call, and each one kept off with the reason and the command that lets it on. */
    static String describeTools(McpServers.Server s, String answer) {
        StringBuilder b = new StringBuilder();
        b.append(answer.isEmpty() ? "The MCP server " + s.name + " answers" + (s.protocol.isBlank() ? "" : " (MCP " + s.protocol + ")") + "." : "The MCP server " + s.name + " did not answer just now. " + answer.replaceFirst("^It did not answer: ", "") + (s.tools.isEmpty() ? "" : " These are the tools it listed the last time it did."))
         .append(s.tools.isEmpty() ? (answer.isEmpty() ? " It lists no tools." : "") : " It has " + s.tools.size() + (s.tools.size() == 1 ? " tool." : " tools.")).append('\n');
        McpServers.Preset p = McpServers.presetOf(s);
        List<McpClient.Tool> on = new ArrayList<>(), off = new ArrayList<>();
        for (McpClient.Tool t : s.tools) (McpSources.offBecause(s, t).isEmpty() ? on : off).add(t);
        if (!on.isEmpty()) {
            b.append("\nResearch may call these. Each is on the brain's table as ").append(s.name).append(".<tool>:\n");
            for (McpClient.Tool t : on) b.append("  - ").append(t.name()).append(": ").append(Untrusted.line(t.description().isBlank() ? "(no description)" : t.description(), 140))
                    .append(p != null && p.agents().contains(t.name()) ? " It runs the service's own research, costs more than a search and takes minutes, so a small model is not offered it." : "").append('\n');
        }
        if (!off.isEmpty()) {
            b.append("\nResearch reads; it does not act. These are kept off research:\n");
            for (McpClient.Tool t : off) {
                String why = McpSources.offBecause(s, t);
                b.append("  - ").append(t.name()).append(": ").append(why).append('.')
                 .append(why.startsWith("the keeper kept it off") ? " To let research call it again: researchzosho mcp allow " + s.name + " " + t.name() : why.startsWith("its name has characters") ? "" : " To let research call it anyway: researchzosho mcp allow " + s.name + " " + t.name()).append('\n');
            }
        }
        return b.toString().strip();
    }

    // ---------------------------------------------------------------- add

    static void add(LibraryStore store, JsonNode args, ObjectNode r) throws IOException {
        String name = args.path("name").asText("").strip().toLowerCase(Locale.ROOT);
        if (!McpServers.NAME.matcher(name).matches()) throw ProtocolError.invalidArgs(McpServers.NAME_RULE);
        List<String> command = new ArrayList<>();
        for (JsonNode c : args.path("command")) if (!c.asText("").isEmpty()) command.add(c.asText());
        if (command.isEmpty() && args.path("command").isTextual()) command.addAll(split(args.path("command").asText("")));
        String url = args.path("url").asText("").strip();
        McpServers.Preset preset = McpServers.preset(name);
        if (!command.isEmpty() && !url.isEmpty()) throw ProtocolError.invalidArgs("A server is either a command this computer starts or an address, not both.");
        if (command.isEmpty() && url.isEmpty() && preset == null)
            throw ProtocolError.invalidArgs("Say how to reach " + name + ": the command that starts it (researchzosho mcp add " + name + " -- <command>) or its address (researchzosho mcp add " + name + " https://…). Exa and Tavily are added by their names alone.");
        McpServers.Server old = McpServers.named(store, name);
        McpServers.Server s = new McpServers.Server();
        s.name = name;
        s.added = Instant.now().toString().replaceFirst("\\.\\d+Z$", "Z");
        if (old != null) { s.allowed = old.allowed; s.forbidden = old.forbidden; s.readers = old.readers; s.cap = old.cap; s.tools = old.tools; }
        if (!command.isEmpty()) {
            s.transport = "stdio"; s.command = command;
            args.path("env").properties().forEach(e -> s.env.put(e.getKey(), e.getValue().asText("")));
            for (String k : s.env.keySet()) if (!k.matches("[A-Za-z_][A-Za-z0-9_]*")) throw ProtocolError.invalidArgs("An environment variable's name is letters, digits and underscores: " + k + " is not one.");
        } else {
            if (url.isEmpty()) { url = preset.url(); s.preset = preset.name(); }
            McpServers.Preset byHost = McpServers.preset(s.preset) != null ? preset : hostPreset(url);
            if (!url.startsWith("https://") && !url.startsWith("http://")) throw ProtocolError.invalidArgs("The address starts with https:// (or http:// for a server on this computer or your own network).");
            try { if (URI.create(url).getHost() == null) throw new IllegalArgumentException(); } catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs("The address " + url + " is not one a request can go to."); }
            s.transport = "http"; s.url = url;
            String keyArg = args.path("header_key").asText(args.path("key_setting").asText("")).strip();
            s.keySetting = !keyArg.isEmpty() ? McpServers.keySettingName(keyArg) : byHost != null ? byHost.keySetting() : "";
            s.header = args.path("header").asText("").strip();
            if (s.header.isEmpty() && byHost != null) s.header = byHost.header();
            if (!s.header.isEmpty() && !s.header.matches("[A-Za-z0-9-]{1,64}")) throw ProtocolError.invalidArgs("A header's name is letters, digits and dashes, such as Authorization or X-Api-Key.");
            if (!s.keySetting.isEmpty()) {
                Settings.Setting setting = Settings.named(s.keySetting);
                if (setting == null || setting.kind() != Settings.Kind.secret) throw ProtocolError.invalidArgs(shortName(s.keySetting) + " is a setting that holds no key. Name a new one for this server's key, such as mcp_" + name.replace('-', '_') + "_key.");
                if (url.startsWith("http://") && !local(url)) throw ProtocolError.invalidArgs("The key would cross the network unencrypted. Give the server's https:// address.");
            }
            if (byHost != null && old == null) s.cap = byHost.cap();   // a preset's monthly cap, until the keeper sets another
        }
        if (args.has("readers")) s.readers = args.path("readers").asBoolean(false);
        if (args.has("cap")) s.cap = Math.max(0, args.path("cap").asInt(0));
        if (args.has("timeout")) s.timeoutSeconds = Math.max(1, Math.min(3600, args.path("timeout").asInt(60)));
        String value = args.path("header_value").asText("").strip();
        if (!value.isEmpty()) {
            if (s.keySetting.isEmpty()) throw ProtocolError.invalidArgs("A key needs the setting it is kept in: header_key, such as mcp_" + name.replace('-', '_') + "_key.");
            Config.set(s.keySetting, value);   // a secret: kept in the settings, never returned
        }
        McpServers.put(store, s);
        McpConnections.close(store, s.name);
        String answer = answer(store, s);
        McpServers.Server now = McpServers.named(store, s.name);
        if (now == null) now = s;
        r.set("server", view(store, now));
        r.put("answers", answer.isEmpty());
        say(r, added(now, answer, old != null));
    }

    /** What adding a server did, in sentences, with the next step first. */
    static String added(McpServers.Server s, String answer, boolean again) {
        StringBuilder b = new StringBuilder();
        McpServers.Preset p = McpServers.presetOf(s);
        b.append("The MCP server ").append(s.name).append(again ? " is set up again" : " is added").append(". ");
        if (s.stdio()) b.append("It is the program ").append(s.where()).append(", which the library starts when research first needs it and stops after five idle minutes.");
        else b.append(p != null ? p.title().substring(0, 1).toUpperCase(Locale.ROOT) + p.title().substring(1) + ", at " + s.url + "." : "It is reached at " + s.url + ".");
        b.append('\n');
        long on = s.tools.stream().filter(t -> McpSources.offBecause(s, t).isEmpty()).count();
        if (answer.isEmpty()) b.append("It answers and lists ").append(s.tools.size()).append(s.tools.size() == 1 ? " tool" : " tools").append("; research may call ").append(on).append(" of them. researchzosho mcp tools ").append(s.name).append(" shows which, and why the others are kept off.\n");
        else b.append("It did not answer: ").append(answer.replaceFirst("^It did not answer: ", "")).append(" researchzosho mcp tools ").append(s.name).append(" asks it again.\n");
        if (!s.stdio() && !s.keySetting.isBlank()) b.append(keyLine(s)).append('\n');
        if (s.cap > 0) b.append("Research may call it ").append(s.cap).append(" times a month; researchzosho mcp cap ").append(s.name).append(" <calls> changes that.\n");
        b.append(s.readers ? "Readers use it too." : "Only you and the people who may write use it. To let readers use it too: researchzosho mcp readers " + s.name + " on");
        return b.toString().strip();
    }

    static McpServers.Preset hostPreset(String url) {
        String h = McpServers.host(url);
        for (McpServers.Preset p : McpServers.presets()) if (h.equalsIgnoreCase(McpServers.host(p.url()))) return p;
        return null;
    }

    /** Whether an address is on this computer or the home network, where http:// is fine. */
    static boolean local(String url) {
        String h = McpServers.host(url).toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return false;
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true;
        try {
            if (!h.matches("[0-9.]+|\\[?[0-9a-f:]+\\]?")) return false;
            InetAddress a = InetAddress.getByName(h.replace("[", "").replace("]", ""));
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
        } catch (IOException e) { return false; }
    }

    /** A command line typed in one box, as its words: spaces split, quotes keep a word whole. */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        char quote = 0; boolean any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; else w.append(c); continue; }
            if (c == '"' || c == '\'') { quote = c; any = true; continue; }
            if (Character.isWhitespace(c)) { if (any || !w.isEmpty()) { out.add(w.toString()); w.setLength(0); any = false; } continue; }
            w.append(c);
        }
        if (any || !w.isEmpty()) out.add(w.toString());
        return out;
    }

    // ---------------------------------------------------------------- call

    /** One call of a tool research may call, made as research makes it: the result fenced and kept, the call counted. */
    static void call(LibraryStore store, JsonNode args, ObjectNode r) throws IOException {
        McpServers.Server s = server(store, args);
        String tool = args.path("tool").asText("").strip();
        McpClient.Tool t = s.tools.stream().filter(x -> x.name().equals(tool)).findFirst().orElse(null);
        if (t == null) {   // not listed yet, or listed under another name: the server is asked afresh
            answer(store, s);
            McpServers.Server now = McpServers.named(store, s.name);
            t = now == null ? null : now.tools.stream().filter(x -> x.name().equals(tool)).findFirst().orElse(null);
            if (t == null) throw ProtocolError.invalidArgs("The MCP server " + s.name + " lists no tool called " + tool + ". Its tools are: " + names(now == null ? s : now) + ".");
        }
        String off = McpSources.offBecause(s, t);
        if (!off.isEmpty()) throw ProtocolError.invalidArgs(s.name + "." + tool + " is kept off research, because " + off + ". The library calls only what research may call; researchzosho mcp allow " + s.name + " " + tool + " lets it on.");
        JsonNode a = args.path("arguments");
        if (a.isTextual()) { try { a = M.readTree(a.asText()); } catch (IOException e) { throw ProtocolError.invalidArgs("The arguments are not JSON: " + e.getMessage()); } }
        if (!a.isObject()) a = M.createObjectNode();
        McpServers.Preset p = McpServers.presetOf(s);
        McpSources.McpInstrument i = new McpSources.McpInstrument(store, s.name, t, p != null && p.agents().contains(t.name()), "");
        Instrument.Result out = McpSources.call(i, (ObjectNode) a, () -> false);
        String text = out.observation();
        r.put("name", s.name).put("tool", tool).put("instrument", i.name()).put("locator", McpSources.locator(s.name, tool, a)).put("error", text.startsWith("ERROR"));
        r.put("text", text);
    }

    // ---------------------------------------------------------------- the command line

    /** What the command line's verb hands an op to: the library here, or the one it is connected to. */
    interface Caller { ObjectNode call(ObjectNode args) throws IOException; }

    static final String USAGE = "usage: researchzosho mcp add <name> -- <command and its arguments…>   a server this computer starts\n"
            + "       researchzosho mcp add <name> <https address> [--header-key <setting>] [--header <name>]   a server at an address\n"
            + "       researchzosho mcp add exa | tavily   a search engine's server, by its name alone\n"
            + "       (add also takes --env NAME=VALUE, --readers, --cap <calls a month>, --timeout <seconds>)\n"
            + "       researchzosho mcp list · tools <name> · call <name> <tool> [<arguments as JSON>] · remove <name>\n"
            + "       researchzosho mcp allow <name> <tool> · forbid <name> <tool> · readers <name> on|off · cap <name> <calls>|off";

    static final Set<String> VERBS = Set.of("add", "list", "tools", "test", "call", "remove", "allow", "forbid", "readers", "cap");

    /** {@code researchzosho mcp <verb> …}: the op asked of {@code caller}, its sentences printed. */
    static int cli(String[] args, Caller caller, PrintStream out, PrintStream err) {
        String verb = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
        ObjectNode a = M.createObjectNode();
        a.put("op", verb);
        try {
            switch (verb) {
                case "list" -> { }
                case "tools", "test", "remove" -> { if (args.length < 4) { err.println(USAGE); return 2; } a.put("name", args[3]); }
                case "allow", "forbid" -> { if (args.length < 5) { err.println(USAGE); return 2; } a.put("name", args[3]).put("tool", args[4]); }
                case "readers", "cap" -> { if (args.length < 5) { err.println(USAGE); return 2; } a.put("name", args[3]).put("value", args[4]); }
                case "call" -> {
                    if (args.length < 5) { err.println(USAGE); return 2; }
                    a.put("name", args[3]).put("tool", args[4]);
                    String json = args.length > 5 ? String.join(" ", Arrays.copyOfRange(args, 5, args.length)) : "{}";
                    try { a.set("arguments", M.readTree(json)); } catch (IOException e) { err.println("The arguments are JSON, such as '{\"query\": \"tote bag\"}': " + e.getMessage()); return 2; }
                }
                case "add" -> { int rc = addArgs(args, a, err); if (rc != 0) return rc; }
                default -> { err.println(USAGE); return 2; }
            }
            ObjectNode r = caller.call(a);
            out.println(r.path("text").asText(r.path("note").asText("")));
            return verb.equals("call") && r.path("error").asBoolean(false) ? 1 : 0;
        } catch (ProtocolError e) { err.println(e.getMessage()); return e.code.equals("invalid_args") ? 2 : 1; }
        catch (IOException e) { err.println(e.getMessage()); return 1; }
    }

    static int addArgs(String[] args, ObjectNode a, PrintStream err) {
        if (args.length < 4) { err.println(USAGE); return 2; }
        a.put("name", args[3]);
        Map<String, String> env = new LinkedHashMap<>();
        int i = 4;
        for (; i < args.length; i++) {
            String x = args[i];
            if (x.equals("--")) { ArrayNode c = a.putArray("command"); for (int k = i + 1; k < args.length; k++) c.add(args[k]); i = args.length; break; }
            switch (x) {
                case "--header-key", "--key-setting" -> { if (++i >= args.length) { err.println(USAGE); return 2; } a.put("header_key", args[i]); }
                case "--header" -> { if (++i >= args.length) { err.println(USAGE); return 2; } a.put("header", args[i]); }
                case "--env" -> {
                    if (++i >= args.length || !args[i].contains("=")) { err.println("--env takes NAME=VALUE."); return 2; }
                    env.put(args[i].substring(0, args[i].indexOf('=')), args[i].substring(args[i].indexOf('=') + 1));
                }
                case "--readers" -> a.put("readers", true);
                case "--cap" -> { if (++i >= args.length) { err.println(USAGE); return 2; } try { a.put("cap", Integer.parseInt(args[i])); } catch (NumberFormatException e) { err.println("--cap takes a number of calls a month."); return 2; } }
                case "--timeout" -> { if (++i >= args.length) { err.println(USAGE); return 2; } try { a.put("timeout", Integer.parseInt(args[i])); } catch (NumberFormatException e) { err.println("--timeout takes a number of seconds."); return 2; } }
                default -> {
                    if ((x.startsWith("https://") || x.startsWith("http://")) && !a.has("url")) a.put("url", x);
                    else { err.println("researchzosho mcp add does not take " + x + ". " + USAGE); return 2; }
                }
            }
        }
        if (!env.isEmpty()) { ObjectNode e = a.putObject("env"); env.forEach(e::put); }
        // a key named for a server at an address: asked for here, without showing it, when the setting holds none yet
        String key = a.path("header_key").asText("");
        if (!key.isEmpty() && a.has("url")) {
            String setting = McpServers.keySettingName(key);
            String have = Config.get(setting);
            Console console = System.console();
            if ((have == null || have.isBlank()) && console != null) {
                char[] typed = console.readPassword("The key for %s (it is kept in the setting %s and never shown; press Enter to set it later): ", a.path("name").asText(), shortName(setting));
                if (typed != null && typed.length > 0) a.put("header_value", new String(typed));
            }
        }
        return 0;
    }
}
