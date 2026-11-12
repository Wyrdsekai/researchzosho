package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.tools.TavilySearchTool;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.mcp.McpClient;
import org.researchzosho.tools.SearchTally;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tools of the keeper's MCP servers as instruments on the brain's table (DESIGN_BRAIN.md §3, §4, §10). A tool joins a run's and a
 * chat's table as {@code <server>.<tool>}, with the server's own description and input schema, clipped, and the words saying it is from
 * that server, configured by the keeper. Research reads; it does not act: a tool whose name, description or operations say it changes
 * things is left off the tables unless the keeper allowed it, which constrains the choice and never refuses one. A small model is shown a
 * few of them, the ones whose names and descriptions best match the question; a large one more. Every result is untrusted evidence:
 * fenced and labelled with the server and the tool, its instruction-shaped sentences set aside, kept in the library under its locator
 * {@code mcp://<server>/<tool>?<arguments>}, and the addresses it names listed as sources of their own.
 */
public final class McpSources {
    private McpSources() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** A tool name a model's list of tools can carry. */
    static final Pattern TOOL_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    /** At most this many MCP tools on a small model's table, and on a large one's. */
    static final int SMALL_MAX = 3, LARGE_MAX = 8;
    /** The longest a service's own research agent may take. */
    static final int AGENT_SECONDS = 600;
    /** The most of a result the model is shown; the library keeps the rest. */
    static final int SHOWN = 12_000;

    /** One tool of another MCP server on the brain's table. */
    static final class McpInstrument implements Instrument {
        final LibraryStore store; final String server; final McpClient.Tool tool; final boolean agent; final String question; final ObjectNode schema;
        McpInstrument(LibraryStore store, String server, McpClient.Tool tool, boolean agent, String question) {
            this.store = store; this.server = server; this.tool = tool; this.agent = agent; this.question = question == null ? "" : question; this.schema = clipSchema(tool.inputSchema());
        }
        McpInstrument forQuestion(String q) { return new McpInstrument(store, server, tool, agent, q); }
        @Override public String name() { return server + "." + tool.name(); }
        @Override public String words() { return McpSources.words(server, tool, agent); }
        @Override public ObjectNode schema(ObjectMapper m) { return schema.deepCopy(); }
        @Override public String example() { return question.isBlank() ? "" : McpSources.example(schema, question); }
        @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) { return call(this, args, stop); }
    }

    // ---------------------------------------------------------------- who may use a server

    /** Whether this patron's runs and chats may use the server: the keeper and writers always, a reader only when the keeper marked it for readers. */
    static boolean mayUse(LibraryStore store, Patrons.Patron who, McpServers.Server s) {
        if (who == null) return false;
        if (who.person() && !Patrons.KEY_READ.equals(who.runtime())) return true;   // the keeper
        if (who.web() && !WebAccess.signInRequired()) return true;   // the pages are open: a browser is the keeper
        Patrons.Level level;
        if (who.web()) level = Patrons.Level.read;   // a browser that has not signed in reads at most
        else if (who.person()) level = Patrons.Level.read;   // the keeper's own key that reads only
        else { try { level = Patrons.load(store).levelFor(who); } catch (IOException e) { return false; } }
        if (Patrons.KEY_READ.equals(who.runtime()) && level == Patrons.Level.write) level = Patrons.Level.read;
        return level == Patrons.Level.write || (level == Patrons.Level.read && s.readers);
    }

    /** The patron a job was filed by, from its did: the keeper ("person"), a browser ("web"), a listed reader, or anyone ("") at the library's default. */
    static Patrons.Patron patronOf(String did) { return new Patrons.Patron(did == null ? "" : did.strip(), "", ""); }

    // ---------------------------------------------------------------- the table

    /** Every tool of the servers this patron may use that is on research tables, from what each server listed last; a server whose month's cap is used up waits for next month. */
    public static List<Instrument> instruments(LibraryStore store, Patrons.Patron who) {
        if (store == null) return List.of();
        List<McpServers.Server> servers;
        try { servers = McpServers.all(store); } catch (IOException e) { return List.of(); }
        if (servers.isEmpty()) return List.of();
        Map<String, int[]> month = SearchTally.thisMonth();
        List<Instrument> out = new ArrayList<>();
        for (McpServers.Server s : servers) {
            if (!mayUse(store, who, s)) continue;
            if (cap(s) > 0 && month.getOrDefault(tally(s), new int[]{0, 0})[0] >= cap(s)) continue;
            McpServers.Preset p = McpServers.presetOf(s);
            for (McpClient.Tool t : s.tools) if (offBecause(s, t).isEmpty()) out.add(new McpInstrument(store, s.name, t, p != null && p.agents().contains(t.name()), ""));
        }
        return out;
    }

    /**
     * The MCP tools a table of this tier shows for this question: on a small model's, at most {@link #SMALL_MAX}, only those whose names or
     * descriptions share a word with the question, best first, and never a service's own research agent; on a large one's, at most
     * {@link #LARGE_MAX}, best first. Each carries the question, for the literal call a small model copies.
     */
    public static List<Instrument> pick(List<Instrument> all, String question, Brain.Tier tier) {
        if (all == null || all.isEmpty()) return List.of();
        Set<String> want = words(question);
        record Scored(McpInstrument i, int score, int at) { }
        List<Scored> scored = new ArrayList<>();
        int at = 0;
        for (Instrument x : all) if (x instanceof McpInstrument i) scored.add(new Scored(i, score(i.tool, want), at++));
        scored.sort(Comparator.comparingInt((Scored s) -> -s.score()).thenComparingInt(Scored::at));
        List<Instrument> out = new ArrayList<>();
        for (Scored s : scored) {
            if (tier == Brain.Tier.SMALL) {
                if (out.size() >= SMALL_MAX) break;
                if (s.i().agent || s.score() <= 0) continue;
            } else if (out.size() >= LARGE_MAX) break;
            out.add(s.i().forQuestion(question));
        }
        return out;
    }

    /** Whether {@code name} is one of the MCP tools on this table. */
    public static boolean isMcp(List<Instrument> table, String name) {
        for (Instrument i : table) if (i instanceof McpInstrument && i.name().equals(name)) return true;
        return false;
    }

    /**
     * The count a server's calls go in: the Exa and Tavily presets count with their search places, so one vendor account has one count and
     * one cap whichever way research reaches it (counted apart, the two could take twice a cap in a month, 2026-11-04); any other server
     * has its own.
     */
    static String tally(McpServers.Server s) {
        McpServers.Preset p = McpServers.presetOf(s);
        if (p != null && p.name().equals("exa")) return ExaSearchTool.TALLY;
        if (p != null && p.name().equals("tavily")) return TavilySearchTool.TALLY;
        return "MCP " + s.name;
    }

    /** The calls research may make to a server this month: its own cap, and for a preset its search place's limit too, the lower; 0 for none. */
    static int cap(McpServers.Server s) {
        int own = s.cap > 0 ? s.cap : Integer.MAX_VALUE, place = SearchTally.limit(tally(s)) > 0 ? SearchTally.limit(tally(s)) : Integer.MAX_VALUE;
        int c = Math.min(own, place);
        return c == Integer.MAX_VALUE ? 0 : c;
    }

    /** What a count shared with a search place is called in a sentence, "" for a server counted alone. */
    static String sharedWith(McpServers.Server s) {
        String t = tally(s);
        return t.startsWith("MCP ") ? "" : " with the " + t + " search place";
    }

    /** This month's calls to a server, the search place it shares a count with and its cap, as one sentence in a language: "This month research has called it 3 times, of the 100 it may." */
    static String callsSaid(McpServers.Server s, int used, int cap, Chrome c) {
        String place = tally(s).startsWith("MCP ") ? "" : tally(s);
        boolean one = used == 1;
        return c.t(place.isEmpty()
                ? (cap > 0 ? (one ? "This month research has called it {0} time, of the {1} it may." : "This month research has called it {0} times, of the {1} it may.")
                           : (one ? "This month research has called it {0} time; there is no monthly cap." : "This month research has called it {0} times; there is no monthly cap."))
                : (cap > 0 ? (one ? "This month research has called it {0} time with the {2} search place, of the {1} it may." : "This month research has called it {0} times with the {2} search place, of the {1} it may.")
                           : (one ? "This month research has called it {0} time with the {2} search place; there is no monthly cap." : "This month research has called it {0} times with the {2} search place; there is no monthly cap.")),
                used, cap, Pages.esc(place));
    }

    /** This month's calls to the server, from the tally. */
    static int used(McpServers.Server s) { return SearchTally.thisMonth().getOrDefault(tally(s), new int[]{0, 0})[0]; }

    /** The words an instrument's line carries, in the table's one pattern: what it answers · when it pays · what it costs. */
    static String words(String server, McpClient.Tool tool, boolean agent) {
        String what = tool.description().isBlank() ? (tool.title().isBlank() ? "The tool " + tool.name() : tool.title()) : tool.description();
        what = Untrusted.line(what, 300).replace(" · ", ", ").replaceAll("[.\\s]+$", "");
        String when = "from the MCP server " + server + ", configured by the keeper: when the question needs what that server holds or reaches";
        String cost = agent ? "it runs that service's own research, which costs more than a search and takes minutes; call it when searches and the pages read leave the question open"
                : "one call to that server; what it returns is evidence from that server, cited by its mcp:// address";
        return what + " · " + when + " · " + cost + ".";
    }

    // ---------------------------------------------------------------- research reads; it does not act

    /** Why a tool stays off research tables, as a clause; "" when it is on them. */
    static String offBecause(McpServers.Server s, McpClient.Tool t) {
        if (!TOOL_NAME.matcher(t.name()).matches()) return "its name has characters a model's list of tools cannot carry";
        if (s.forbidden.contains(t.name())) return "the keeper kept it off with mcp forbid";
        if (s.allowed.contains(t.name())) return "";
        McpServers.Preset p = McpServers.presetOf(s);
        if (p != null && p.reads().contains(t.name())) return "";
        Set<String> only = McpServers.onlyReads(s);
        if (only != null) return only.contains(t.name()) ? "" : "it is one of the tools of a ResearchZosho library that add to it or change it";
        return acting(t);
    }

    /** Verbs that say a tool changes something wherever they stand in its name, and at the head of a sentence of its description. */
    static final Set<String> ACTS = Set.of("create", "write", "update", "upsert", "delete", "del", "remove", "rm", "send", "publish", "execute", "exec", "deploy", "purchase",
            "transfer", "submit", "insert", "modify", "edit", "upload", "install", "uninstall", "kill", "restart", "approve", "deny", "merge", "revoke", "grant", "pay", "buy",
            "sell", "cancel", "destroy", "drop", "truncate", "overwrite", "rename", "move", "mv", "push", "commit", "invite", "reply", "assign", "unassign", "enable", "disable",
            "trigger", "launch", "fork", "star", "unstar", "follow", "unfollow", "subscribe", "unsubscribe", "unarchive", "reopen", "lock", "unlock", "reset", "revert",
            "rollback", "terminate", "shutdown", "reboot", "mint", "withdraw", "deposit", "reserve", "rsvp", "notify", "wipe", "erase", "purge", "reject", "vote", "upvote",
            "downvote", "enroll", "register", "tweet", "retweet", "email", "sms", "checkout", "refund", "charge", "replace", "append", "attach", "detach", "dismiss", "transact",
            "absorb", "shelve", "ingest");
    /** Verbs that are often nouns too (a post, an order, a run, a file): in a name, only as the word itself and with no reading verb beside it; in a description, only before what they act on. */
    static final Set<String> MAYBE_ACTS = Set.of("add", "set", "put", "patch", "post", "run", "order", "share", "tag", "label", "mark", "comment", "apply", "import", "sync", "save",
            "store", "change", "start", "stop", "clear", "flush", "empty", "book", "schedule", "archive", "close", "message", "fill", "click", "type", "press", "select", "drag",
            "make", "react", "like", "pin", "unpin", "mute", "block", "report", "flag", "accept", "dispute", "retire", "pause", "resume", "toggle", "tell", "file", "request", "do", "pick");
    /** Verbs that say a tool reads. */
    static final Set<String> READS = Set.of("get", "list", "search", "find", "read", "fetch", "query", "lookup", "look", "describe", "show", "view", "count", "retrieve", "browse",
            "explore", "inspect", "summarize", "summarise", "check", "download", "export", "scan", "analyze", "analyse", "compare", "explain", "extract", "parse", "preview",
            "stat", "stats", "info", "status", "ls", "cat", "grep", "head", "tail", "peek", "discover", "locate", "recall", "ask", "answer", "estimate", "calculate", "compute",
            "convert", "translate", "transcribe", "open", "navigate", "snapshot", "screenshot", "ping", "whoami", "help", "version", "crawl", "map", "research", "scrape", "resolve");
    /** The words that open what a verb acts on: "add one", "post a message", "run the query". */
    static final Set<String> OBJECT = Set.of("a", "an", "the", "one", "new", "this", "that", "these", "those", "it", "them", "your", "my", "our", "their", "his", "her", "its",
            "some", "any", "each", "every", "all", "to", "into", "onto", "multiple", "several");

    static final Pattern LEAD_IN = Pattern.compile("(?i)^(?:\\s*(?:this\\s+tool|the\\s+tool|a\\s+tool\\s+(?:to|that|which)|tool\\s+(?:to|that|which)|use\\s+(?:this\\s+)?(?:tool\\s+)?to|used\\s+to|allows?\\s+(?:you\\s+)?to|lets?\\s+you|helps?\\s+(?:you\\s+)?(?:to\\s+)?|function\\s+to|helper\\s+to|it|will|can|may|also|you\\s+can)\\b\\s*)+");
    static final Pattern SUBJECT_ACTS = Pattern.compile("(?i)\\b(?:this|the)\\s+tool\\s+(?:will\\s+|can\\s+|may\\s+|also\\s+|then\\s+)?(?:permanently\\s+|directly\\s+)?([a-z]+)");
    static final Pattern OP_ACTS = Pattern.compile("(?i)\\b(?:op|operation|action|mode|command)\\s*[=:]\\s*\"?([a-z_-]+)");
    static final Set<String> OPS = Set.of("op", "operation", "action", "command", "method", "mode", "as");

    /** Why a tool's words say it changes things, as a clause; "" when they do not. */
    static String acting(McpClient.Tool t) {
        if (t.annotations().path("destructiveHint").asBoolean(false)) return "the server marks it as one that changes or deletes things";
        List<String> name = nameWords(t.name());
        for (String w : name) if (ACTS.contains(w)) return "its name says it changes things (" + w + ")";
        if (name.stream().noneMatch(READS::contains)) for (String w : name) if (MAYBE_ACTS.contains(w)) return "its name says it changes things (" + w + ")";
        String d = t.description() == null ? "" : t.description().strip();
        if (!d.isEmpty()) {
            String first = d.split("(?<=[.!?])\\s+|\\n", 2)[0];
            for (String clause : first.split("\\s*[:;—–]\\s*|\\s+-\\s+")) {
                String c = LEAD_IN.matcher(clause).replaceFirst("").strip();
                String[] ws = c.split("[^\\p{L}]+", 3);
                if (ws.length == 0 || ws[0].isEmpty()) continue;
                if (heads(ws[0], ws.length > 1 ? ws[1] : "")) return "its description says it changes things (\"" + Untrusted.line(c, 60) + "\")";
            }
            Matcher m = SUBJECT_ACTS.matcher(d);
            while (m.find()) if (verb(m.group(1)) != null) return "its description says it changes things (\"" + Untrusted.line(m.group(), 60) + "\")";
            Matcher op = OP_ACTS.matcher(d);
            while (op.find()) for (String w : nameWords(op.group(1))) if (verb(w) != null) return "one of its operations changes things (" + op.group(1).toLowerCase(Locale.ROOT) + ")";
        }
        for (Map.Entry<String, JsonNode> e : t.inputSchema().path("properties").properties()) {
            if (!OPS.contains(e.getKey().toLowerCase(Locale.ROOT))) continue;
            for (JsonNode v : e.getValue().path("enum")) for (String w : nameWords(v.asText(""))) if (verb(w) != null) return "one of its operations changes things (" + v.asText() + ")";
        }
        return "";
    }

    /** Whether a clause that opens with {@code w}, then {@code next}, says the tool does something that changes things: "Creates a page", "Add one", "Post a message". */
    static boolean heads(String w, String next) {
        String x = w.toLowerCase(Locale.ROOT);
        String base = x;
        if (!ACTS.contains(x) && !MAYBE_ACTS.contains(x)) {
            base = x.endsWith("ies") ? x.substring(0, x.length() - 3) + "y" : x.endsWith("es") && (ACTS.contains(x.substring(0, x.length() - 2)) || MAYBE_ACTS.contains(x.substring(0, x.length() - 2))) ? x.substring(0, x.length() - 2) : x.endsWith("s") ? x.substring(0, x.length() - 1) : x;
        }
        if (ACTS.contains(base)) return true;
        return MAYBE_ACTS.contains(base) && OBJECT.contains(next.toLowerCase(Locale.ROOT));
    }

    /** The verb a word is a form of, when it says a tool changes things; null otherwise. Runs, running, ran-style forms are read back to their verb. */
    static String verb(String w) {
        if (w == null || w.length() < 2) return null;
        String x = w.toLowerCase(Locale.ROOT);
        if (ACTS.contains(x) || MAYBE_ACTS.contains(x)) return x;
        List<String> forms = new ArrayList<>();
        if (x.endsWith("ies")) forms.add(x.substring(0, x.length() - 3) + "y");
        if (x.endsWith("es")) forms.add(x.substring(0, x.length() - 2));
        if (x.endsWith("s")) forms.add(x.substring(0, x.length() - 1));
        if (x.endsWith("ied")) forms.add(x.substring(0, x.length() - 3) + "y");
        if (x.endsWith("ed")) { String b = x.substring(0, x.length() - 2); forms.add(b); forms.add(b + "e"); if (b.length() > 2 && b.charAt(b.length() - 1) == b.charAt(b.length() - 2)) forms.add(b.substring(0, b.length() - 1)); }
        if (x.endsWith("ing")) { String b = x.substring(0, x.length() - 3); forms.add(b); forms.add(b + "e"); if (b.length() > 2 && b.charAt(b.length() - 1) == b.charAt(b.length() - 2)) forms.add(b.substring(0, b.length() - 1)); }
        for (String f : forms) if (ACTS.contains(f) || MAYBE_ACTS.contains(f)) return f;
        return null;
    }

    /** A tool's name as words: snake_case, kebab-case, dotted and camelCase split, small letters. */
    static List<String> nameWords(String name) {
        List<String> out = new ArrayList<>();
        if (name == null) return out;
        for (String part : name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").split("[^A-Za-z0-9]+")) if (!part.isBlank()) out.add(part.toLowerCase(Locale.ROOT));
        return out;
    }

    // ---------------------------------------------------------------- ranking

    static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "what", "which", "about", "into", "your", "have", "has", "are", "was", "were",
            "how", "why", "who", "when", "where", "does", "did", "can", "could", "would", "should", "will", "there", "their", "them", "they", "its", "not", "but", "all", "any",
            "some", "one", "out", "use", "you", "find", "get", "give", "want", "need", "like", "also", "than", "then", "more", "most", "such", "only", "very", "just", "over", "tool");

    /** The words a ranking compares: small letters, three letters or more, no common words, a plural read as its singular; a run of CJK characters as its pairs. */
    static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String w : text.toLowerCase(Locale.ROOT).replaceAll("([a-z0-9])([A-Z])", "$1 $2").split("[^\\p{L}\\p{N}]+")) {
            if (w.isBlank()) continue;
            if (w.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA)) {
                for (int i = 0; i + 2 <= w.length(); i++) out.add(w.substring(i, i + 2));
                continue;
            }
            if (w.length() < 3 || STOP.contains(w)) continue;
            out.add(stem(w));
        }
        return out;
    }

    static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    /** How well a tool matches the question's words: three for a word in its name, one for a word in its description. */
    static int score(McpClient.Tool t, Set<String> want) {
        if (want.isEmpty()) return 0;
        Set<String> name = new HashSet<>();
        for (String w : nameWords(t.name())) name.add(stem(w));
        Set<String> desc = words(t.title() + " " + t.description());
        int s = 0;
        for (String w : want) { if (name.contains(w)) s += 3; else if (desc.contains(w)) s += 1; }
        return s;
    }

    // ---------------------------------------------------------------- the schema and the example

    /** The server's input schema as a table takes it: an object of at most 24 properties, four levels deep, references read in, descriptions short and neutral, every array with its items' type. */
    static ObjectNode clipSchema(JsonNode schema) {
        JsonNode root = schema != null && schema.isObject() ? schema : M.createObjectNode();
        ObjectNode out = clip(root, root, 0);
        out.put("type", "object");
        if (!out.has("properties")) out.putObject("properties");
        if (!out.has("required")) out.putArray("required");
        return out;
    }

    private static ObjectNode clip(JsonNode n, JsonNode root, int depth) {
        ObjectNode o = M.createObjectNode();
        if (n == null || !n.isObject()) { o.put("type", "string"); return o; }
        String desc = n.path("description").asText("");
        if (desc.length() > 120) desc = Untrusted.line(desc, 120);
        if (n.hasNonNull("$ref")) {
            JsonNode target = ref(root, n.get("$ref").asText(""));
            if (target == null || depth >= 4) { o.put("type", depth >= 4 ? "object" : "string"); if (!desc.isBlank()) o.put("description", Untrusted.line(desc, 200)); return o; }
            ObjectNode in = clip(target, root, depth + 1);
            if (!desc.isBlank()) in.put("description", Untrusted.line(desc, 200));
            return in;
        }
        for (String alt : new String[]{"anyOf", "oneOf", "allOf"}) {
            if (!n.path("type").isMissingNode() || !n.path(alt).isArray()) continue;
            for (JsonNode a : n.path(alt)) {
                if ("null".equals(a.path("type").asText(""))) continue;
                ObjectNode in = clip(a, root, depth + 1);
                if (!desc.isBlank()) in.put("description", Untrusted.line(desc, 200));
                return in;
            }
        }
        String type = "";
        JsonNode t = n.path("type");
        if (t.isTextual()) type = t.asText();
        else if (t.isArray()) for (JsonNode x : t) if (!"null".equals(x.asText())) { type = x.asText(); break; }
        if (type.isBlank()) type = n.has("properties") ? "object" : n.has("items") ? "array" : "string";
        if (!Set.of("object", "array", "string", "integer", "number", "boolean").contains(type)) type = "string";
        o.put("type", type);
        if (!desc.isBlank()) o.put("description", Untrusted.line(desc, 200));
        if (n.path("enum").isArray()) { ArrayNode e = o.putArray("enum"); int k = 0; for (JsonNode v : n.path("enum")) if (v.isValueNode() && !v.isNull() && k++ < 30) e.add(v.isTextual() ? M.getNodeFactory().textNode(Untrusted.line(v.asText(), 80)) : v); }
        if (n.has("default") && n.get("default").isValueNode() && !n.get("default").isNull()) o.set("default", n.get("default"));
        for (String k : new String[]{"minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength"}) if (n.path(k).isNumber()) o.set(k, n.get(k));
        switch (type) {
            case "object" -> {
                ObjectNode props = o.putObject("properties");
                if (depth < 4) {
                    int k = 0;
                    for (Map.Entry<String, JsonNode> e : n.path("properties").properties()) { if (k++ >= 16) break; if (e.getKey().matches("[A-Za-z0-9_.$-]{1,64}")) props.set(e.getKey(), clip(e.getValue(), root, depth + 1)); }
                }
                ArrayNode req = o.putArray("required");
                for (JsonNode r : n.path("required")) if (props.has(r.asText(""))) req.add(r.asText());
            }
            case "array" -> o.set("items", depth < 4 && n.path("items").isObject() ? clip(n.get("items"), root, depth + 1) : M.createObjectNode().put("type", "string"));
            default -> { }
        }
        return o;
    }

    /** A reference within the schema itself, #/$defs/… or #/definitions/…; null for any other. */
    private static JsonNode ref(JsonNode root, String ref) {
        if (!ref.startsWith("#/")) return null;
        JsonNode at = root;
        for (String part : ref.substring(2).split("/")) { at = at.path(part.replace("~1", "/").replace("~0", "~")); if (at.isMissingNode()) return null; }
        return at.isObject() ? at : null;
    }

    static final Set<String> QUERYISH = Set.of("query", "q", "search", "search_query", "text", "keywords", "keyword", "term", "terms", "question", "topic", "prompt", "input", "objective", "instructions");

    /** A complete call a small model copies: each required argument a literal, the question's own words for a query; "" when an argument has no literal to give. */
    static String example(ObjectNode schema, String question) {
        JsonNode props = schema.path("properties");
        List<String> keys = new ArrayList<>();
        for (JsonNode r : schema.path("required")) keys.add(r.asText());
        if (keys.isEmpty()) for (String k : QUERYISH) if (props.has(k)) { keys.add(k); break; }
        if (keys.isEmpty()) return "";
        ObjectNode ex = M.createObjectNode();
        for (String k : keys) {
            JsonNode p = props.path(k);
            if (p.path("enum").isArray() && !p.path("enum").isEmpty()) { ex.set(k, p.path("enum").get(0)); continue; }
            if (p.has("default")) { ex.set(k, p.get("default")); continue; }
            String type = p.path("type").asText("string");
            if (type.equals("string") && QUERYISH.contains(k.toLowerCase(Locale.ROOT))) { String q = queryWords(question); if (q.isEmpty()) return ""; ex.put(k, q); continue; }
            if (type.equals("integer") || type.equals("number")) { ex.put(k, p.path("minimum").isNumber() ? Math.max(p.path("minimum").asInt(), Math.min(5, p.path("maximum").asInt(5))) : 5); continue; }
            if (type.equals("boolean")) { ex.put(k, false); continue; }
            return "";
        }
        return ex.toString();
    }

    /** The question's content words, eight at most, as a search would take them. */
    static String queryWords(String question) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String w : (question == null ? "" : question).split("\\s+")) {
            String x = w.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
            if (x.isEmpty() || (x.length() < 3 && !x.codePoints().anyMatch(Character::isIdeographic)) || STOP.contains(x.toLowerCase(Locale.ROOT)) || !seen.add(x.toLowerCase(Locale.ROOT))) continue;
            out.add(x);
            if (out.size() >= 8) break;
        }
        return String.join(" ", out);
    }

    // ---------------------------------------------------------------- a call, and what the model sees of it

    /** The locator a result is cited by: mcp://<server>/<tool> and the arguments, sorted, as a query string. */
    static String locator(String server, String tool, JsonNode args) {
        StringBuilder b = new StringBuilder("mcp://").append(server).append('/').append(tool);
        List<String> keys = new ArrayList<>();
        if (args != null && args.isObject()) args.fieldNames().forEachRemaining(keys::add);
        keys.sort(null);
        char sep = '?';
        for (String k : keys) {
            JsonNode v = args.get(k);
            b.append(sep).append(enc(k)).append('=').append(enc(v.isTextual() ? v.asText() : v.toString()));
            sep = '&';
        }
        String l = b.toString();
        return l.length() <= 600 ? l : l.substring(0, 600);
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    /** One call of the tool, through the server's connection: the result fenced and kept, or what went wrong in a sentence. */
    static Instrument.Result call(McpInstrument i, ObjectNode argsIn, BooleanSupplier stop) {
        ObjectNode args = argsIn == null ? M.createObjectNode() : argsIn.deepCopy();
        String name = i.name(), locator = locator(i.server, i.tool.name(), args);
        if (stop != null && stop.getAsBoolean()) return Instrument.Result.of(name, locator, "The run is stopping; nothing was asked of " + name + ".");
        McpServers.Server s;
        try { s = McpServers.named(i.store, i.server); } catch (IOException e) { return Instrument.Result.of(name, locator, "ERROR: " + name + " — " + e.getMessage()); }
        if (s == null) return Instrument.Result.of(name, locator, "ERROR: the MCP server " + i.server + " is no longer set up in this library; choose another instrument.");
        McpClient.Tool now = s.tools.stream().filter(t -> t.name().equals(i.tool.name())).findFirst().orElse(i.tool);
        String off = offBecause(s, now);
        if (!off.isEmpty()) return Instrument.Result.of(name, locator, "ERROR: " + name + " is off research tables now, because " + off + "; choose another instrument.");
        if (cap(s) > 0 && used(s) >= cap(s)) return Instrument.Result.of(name, locator, capReached(s));
        Duration timeout = Duration.ofSeconds(i.agent ? Math.max(s.timeoutSeconds, AGENT_SECONDS) : s.timeoutSeconds);
        McpClient.Result r;
        try {
            McpClient c = McpConnections.open(i.store, s);
            SearchTally.count(tally(s), 0);   // every call to a server counts, answered or not
            r = c.call(i.tool.name(), args, timeout, stop);
        } catch (McpClient.Failure f) {
            if (f.kind == McpClient.Failure.Kind.STOPPED) { McpConnections.closeSoon(i.store, s.name); return Instrument.Result.of(name, locator, "The run is stopping; the call to " + name + " was given up."); }
            if (f.kind == McpClient.Failure.Kind.TIMEOUT || f.kind == McpClient.Failure.Kind.GONE) McpConnections.closeSoon(i.store, s.name);   // a server that stopped answering starts afresh at its next use
            return Instrument.Result.of(name, locator, "ERROR: " + name + " — " + failed(s.name, i.tool.name(), f));
        } catch (IOException e) {
            return Instrument.Result.of(name, locator, "ERROR: " + name + " — the MCP server " + s.name + " could not be reached: " + e.getMessage());
        }
        return Instrument.Result.of(name, locator, observation(i.store, s.name, i.tool.name(), locator, r, args));
    }

    static String capReached(McpServers.Server s) {
        return "The monthly allowance the keeper set for the MCP server " + s.name + sharedWith(s) + ", " + cap(s) + (cap(s) == 1 ? " call" : " calls") + ", is used up for " + LocalDate.now().toString().substring(0, 7)
                + "; its tools are called again next month. Go on with the other instruments.";
    }

    /** What went wrong with a call, in a sentence; the server's own words fenced beside it. */
    static String failed(String server, String tool, McpClient.Failure f) {
        String head = switch (f.kind) {
            case TIMEOUT -> "the MCP server " + server + " gave no answer in time: " + f.getMessage() + " It is started afresh at its next use.";
            case REFUSED -> "the MCP server " + server + " refused the call: " + f.getMessage();
            case ERROR -> "the MCP server " + server + " answered the call with an error" + (f.code != 0 ? " (code " + f.code + ")" : "") + ".";
            case GONE -> "the MCP server " + server + " stopped: " + f.getMessage() + " It is started afresh at its next use.";
            default -> "the MCP server " + server + " could not be reached: " + f.getMessage();
        };
        if (f.said.isBlank()) return head;
        String label = "MCP ERROR " + server + "." + tool;
        return head + "\n" + Fence.wrap(label, Untrusted.neutralise(f.said.length() > 800 ? f.said.substring(0, 800) : f.said)) + "\n" + Fence.rule(label);
    }

    /** The result as the model sees it: where it came from, the text fenced and labelled, how to cite it, and the addresses it names. */
    static String observation(LibraryStore store, String server, String tool, String locator, McpClient.Result r, JsonNode args) {
        String label = "MCP RESULT " + server + "." + tool;
        String text = Untrusted.neutralise(r.text());
        List<String> links = new ArrayList<>();
        for (String u : r.links()) if (McpClient.urls(u).size() == 1 && McpClient.urls(u).get(0).equals(u) && !links.contains(u)) links.add(u);
        if (!r.error() && store != null && !text.isBlank()) keep(store, server, tool, locator, text, links, args);
        String shown = text.length() <= SHOWN ? text : text.substring(0, SHOWN) + "\n[" + (text.length() - SHOWN) + " more characters are kept in the library under " + locator + "]";
        StringBuilder b = new StringBuilder("source: ").append(locator).append(" — ")
                .append(r.error() ? "the tool " + tool + " of the MCP server " + server + " answered that it could not do this" : "what the tool " + tool + " of the MCP server " + server + " answered")
                .append("; the keeper set that server up\n");
        b.append(Fence.wrap(label, shown.isBlank() ? "(it answered with no text)" : shown)).append('\n').append(Fence.rule(label)).append('\n');
        if (!r.error()) b.append("Cite what it shows as ").append(locator).append('.');
        if (!links.isEmpty()) b.append(" The addresses it names are sources of their own, to open and cite: ").append(String.join(" ", links.subList(0, Math.min(links.size(), 12)))).append('.');
        return b.toString().strip();
    }

    /** The result kept in the library under its locator, with the addresses it names, so a claim cited to it can be checked against it. */
    static void keep(LibraryStore store, String server, String tool, String locator, String text, List<String> links, JsonNode args) {
        String a = args == null || args.isEmpty() ? "" : ": " + Acquisitions.compress(args.toString(), 120);
        String body = text + (links.isEmpty() ? "" : "\n\nAddresses it names:\n- " + String.join("\n- ", links));
        RawCapture.capture(store, locator, body, server + " · " + tool + a, "researchzosho-mcp", "", "");
    }
}
