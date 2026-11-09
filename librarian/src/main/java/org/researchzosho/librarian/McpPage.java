package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.researchzosho.mcp.McpClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.t;
import static org.researchzosho.librarian.Pages.n;

/**
 * The Keep page's section "Other MCP servers": each server the keeper set up, with what research may call of it, who may use it and the
 * month's calls; a button to test it, one to let readers use it or not, one to remove it; the two search engines the library knows by
 * name, one button each; and the form that adds a server by its command or its address. The keeper's alone, like the op it posts to.
 */
final class McpPage {
    private McpPage() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** The fold's gist: how many servers, and how many tools research may call. */
    static String gist(LibraryStore store) {
        try {
            List<McpServers.Server> all = McpServers.all(store);
            if (all.isEmpty()) return t("none yet");
            int on = 0;
            for (McpServers.Server s : all) for (McpClient.Tool tool : s.tools) if (McpSources.offBecause(s, tool).isEmpty()) on++;
            return t("{0}, {1} research may call", n(all.size(), "{0} server", "{0} servers"), n(on, "{0} tool", "{0} tools"));
        } catch (IOException e) { return t("the list could not be read"); }
    }

    static String section(LibraryStore store) throws IOException {
        StringBuilder b = new StringBuilder("<p class=\"k\">").append(t("Other programs' MCP servers whose tools research may use as sources, beside the library's own. Research calls the tools that read; a tool that may change things stays off unless you let it on. A program this computer starts is started when research first needs it and stopped after five idle minutes. Only you and the people who may write use a server, unless you let readers use it too.")).append(" ")
                .append(t("At the terminal: {0}.", "<code>researchzosho mcp list</code>")).append("</p>");
        List<McpServers.Server> all = McpServers.all(store);
        for (McpServers.Server s : all) {
            McpServers.Preset p = McpServers.presetOf(s);
            List<String> on = new ArrayList<>(), off = new ArrayList<>();
            for (McpClient.Tool tool : s.tools) { String why = McpSources.offBecause(s, tool); if (why.isEmpty()) on.add(tool.name()); else off.add(tool.name() + " (" + why + ")"); }
            b.append("<div class=\"card\"><h3>").append(esc(s.name)).append(" <span class=\"k\">").append(esc(p != null ? p.title() : s.stdio() ? t("a program this computer starts") : t("a server at an address"))).append("</span></h3>");
            b.append("<p class=\"small\"><code>").append(esc(s.where())).append("</code></p>");
            if (s.tools.isEmpty()) b.append("<p>").append(t("It has listed no tools yet. Test it to ask it.")).append("</p>");
            else b.append("<p>").append(on.isEmpty() ? t("Research may call none of its tools.") : t("Research may call {0} of its {1}: {2}.", on.size(), s.tools.size(), esc(String.join(", ", on))))
                    .append(off.isEmpty() ? "" : " " + t("Kept off, because research reads and does not act: {0}.", esc(String.join("; ", off))) + " " + t("{0} lets one on.", "<code>researchzosho mcp allow " + esc(s.name) + " &lt;tool&gt;</code>")).append("</p>");
            int used = McpSources.used(s), cap = McpSources.cap(s);
            b.append("<p class=\"k\">").append(s.lastError.isBlank() ? (s.listedAt.isBlank() ? t("Not asked yet.") : t("It answered at {0}.", esc(s.listedAt.replace("T", " ").replace("Z", " UTC")) + (s.protocol.isBlank() ? "" : " (MCP " + esc(s.protocol) + ")"))) : t("The last time it was asked, it did not answer: {0}", esc(s.lastError)))
             .append(" ").append(t("This month research has called it {0}{1}", n(used, "{0} time", "{0} times"), esc(McpSources.sharedWith(s)))).append(cap > 0 ? ", " + t("of the {0} it may.", cap) : "; " + t("there is no monthly cap."))
             .append(!s.stdio() && !s.keySetting.isBlank() ? " " + esc(McpSetup.keyLine(s)) : "").append(s.readers ? " " + t("Readers use it too.") : "").append("</p>");
            b.append(button("mcp-test", s.name, "", t("Test it"), "btn")).append(' ')
             .append(button("mcp-readers", s.name, s.readers ? "off" : "on", s.readers ? t("Keep it to you and the writers") : t("Let readers use it too"), "btn")).append(' ')
             .append(button("mcp-remove", s.name, "", t("Remove"), "quiet"));
            // its tools, each let on or kept off here; and the monthly cap (until 2026-11-07 the terminal's alone)
            if (!s.tools.isEmpty()) {
                b.append("<details><summary>").append(t("Its tools, and which research may call")).append("</summary><table><tr><th>").append(t("tool")).append("</th><th>").append(t("what it does")).append("</th><th></th></tr>");
                for (McpClient.Tool tool : s.tools) {
                    String why = McpSources.offBecause(s, tool);
                    b.append("<tr><td><code>").append(esc(tool.name())).append("</code></td><td>").append(esc(tool.description() == null ? "" : tool.description().length() > 140 ? tool.description().substring(0, 140) + "…" : tool.description())).append(why.isEmpty() ? "" : " <span class=\"k\">(" + esc(why) + ")</span>").append("</td><td>")
                     .append(toolButton(why.isEmpty() ? "mcp-forbid" : "mcp-allow", s.name, tool.name(), why.isEmpty() ? t("Keep it off") : t("Let it on"))).append("</td></tr>");
                }
                b.append("</table></details>");
            }
            b.append("<form method=\"post\" action=\"/keep\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"mcp-cap\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(s.name)).append("\"><label>").append(t("Calls a month at most")).append(" <input name=\"value\" size=\"6\" value=\"").append(cap > 0 ? String.valueOf(cap) : "").append("\" placeholder=\"off\"></label> <button class=\"quiet\">").append(t("Set the cap")).append("</button></form>");
            b.append("</div>");
        }
        for (McpServers.Preset p : McpServers.presets()) {
            if (all.stream().anyMatch(s -> p.name().equals(s.preset) || p.name().equals(s.name))) continue;
            b.append("<p>").append(button("mcp-preset", p.name(), "", t("Add {0}", p.name().substring(0, 1).toUpperCase() + p.name().substring(1)), "btn")).append(" <span class=\"k\">")
             .append(esc(p.title())).append(": ").append(t("it works without a key, slower and limited, and uses yours once the setting {0} holds one.", esc(McpSetup.shortName(p.keySetting())))).append("</span></p>");
        }
        b.append("<h3>").append(t("Add a server")).append("</h3><form method=\"post\" action=\"/keep\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"mcp-add\">")
         .append("<label>").append(t("A short name")).append(" <input name=\"name\" placeholder=\"docs\" size=\"16\" required></label>")
         .append("<label>").append(t("The command that starts it on this computer")).append(" <input name=\"command\" placeholder=\"npx -y @example/notes-mcp\" size=\"48\"></label>")
         .append("<label>").append(t("or its address")).append(" <input name=\"url\" placeholder=\"https://example.org/mcp\" size=\"40\"></label>")
         .append("<label>").append(t("The setting its key is kept in, when it wants one")).append(" <input name=\"header_key\" placeholder=\"mcp_docs_key\" size=\"20\"></label>")
         .append("<label>").append(t("The key")).append(" <input type=\"password\" name=\"header_value\" autocomplete=\"off\" size=\"24\"></label>")
         .append("<label>").append(t("The header the key goes in")).append(" <input name=\"header\" placeholder=\"Authorization\" size=\"16\"></label>")
         .append("<label><input type=\"checkbox\" name=\"readers\" value=\"on\"> ").append(t("readers may use it too")).append("</label>")
         .append("<button class=\"btn primary\">").append(t("Add")).append("</button></form>");
        return b.toString();
    }

    private static String toolButton(String op, String name, String tool, String label) {
        return "<form method=\"post\" action=\"/keep\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"" + op + "\"><input type=\"hidden\" name=\"name\" value=\"" + esc(name) + "\"><input type=\"hidden\" name=\"tool\" value=\"" + esc(tool) + "\"><button class=\"quiet\">" + esc(label) + "</button></form>";
    }

    private static String button(String op, String name, String value, String label, String cls) {
        return "<form method=\"post\" action=\"/keep\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"" + op + "\"><input type=\"hidden\" name=\"name\" value=\"" + esc(name) + "\">"
                + (value.isEmpty() ? "" : "<input type=\"hidden\" name=\"value\" value=\"" + esc(value) + "\">") + "<button class=\"" + cls + "\">" + esc(label) + "</button></form>";
    }

    /**
     * A form of the section: the op asked as the keeper, and its note for the page. The daemon refuses a form from another site before any
     * page reads it ({@link OwnPage}); a form that may start a program on this computer asks again here, whoever routes to it.
     */
    static String post(LibraryStore store, Patrons.Patron patron, Map<String, String> form, HttpExchange x) throws IOException {
        if (!OwnPage.allowed(x)) throw ProtocolError.forbidden(OwnPage.REFUSED);
        String op = form.getOrDefault("op", "").substring(4);
        ObjectNode a = M.createObjectNode();
        a.set("patron", LibrarianDaemon.patronNode(patron));
        a.put("name", form.getOrDefault("name", "").strip());
        switch (op) {
            case "test" -> a.put("op", "test");
            case "remove" -> a.put("op", "remove");
            case "readers" -> a.put("op", "readers").put("value", form.getOrDefault("value", "off"));
            case "allow", "forbid", "cap" -> { a.put("op", op).put("tool", form.getOrDefault("tool", "")).put("value", form.getOrDefault("value", "").strip().isEmpty() ? "off" : form.getOrDefault("value", "").strip()); }
            case "preset" -> a.put("op", "add");
            case "add" -> {
                a.put("op", "add");
                String command = form.getOrDefault("command", "").strip(), url = form.getOrDefault("url", "").strip();
                if (!command.isEmpty()) a.put("command", command);
                if (!url.isEmpty()) a.put("url", url);
                for (String k : new String[]{"header_key", "header", "header_value"}) { String v = form.getOrDefault(k, "").strip(); if (!v.isEmpty()) a.put(k, v); }
                if ("on".equals(form.get("readers"))) a.put("readers", true);
            }
            default -> { return t("That is not a thing this page does."); }
        }
        try {
            ObjectNode r = new LibraryProtocol(store).mcp(a);
            if (op.equals("test")) return r.path("text").asText("").split("\n", 2)[0];
            return r.path("note").asText(r.path("text").asText("")).replace("\n", " ");
        } catch (ProtocolError e) {
            if (e.code.equals("forbidden")) throw e;
            return e.getMessage();
        }
    }
}
