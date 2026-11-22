package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the chat's model reads of a tool, where it differs from what the protocol, MCP and the command line take (0.8.1). The chat sends
 * its whole table with every round of every turn: 18,332 tokens of a 32,768-token window on the release's own model (measured
 * 2026-11-21 in the server's own count), more than the whole window of a model served with 16,384. Every argument stays in the
 * protocol; here the chat leaves out what the program fills itself or a chat has no use for, and says shortly what an argument's
 * protocol sentence says at length. Nothing a rule says is lost: a rule of the tool's own description stays there.
 */
final class ChatTable {

    private ChatTable() { }

    /** The fields of a collection's form, which the chat's model no longer fills: the program reads them from the person's request. */
    static final List<String> FORM_RULES = List.of("kind", "name", "shows", "min_width", "min_height", "orientation", "format", "ai", "differ_by", "differ_quote", "minutes", "keep_full");

    /** The arguments the chat's table leaves out, by tool. */
    static final Map<String, List<String>> LEFT_OUT = Map.of(
            // a collection's form: in the chat it is read from the person's own words (Collection.fill) when a call carries none of its
            // rules (Librarian.held), and only a field whose words are in the request is kept (CollectionForm.grounded)
            "library_images", FORM_RULES,
            // back_translation sets itself (on when the same model checks its own work); the translation is shelved under the original's collection
            "library_translate", List.of("back_translation", "collection"),
            // a planner's input: a run makes its own sub-questions
            "library_research", List.of("sub_questions"));

    /**
     * An argument's words in the chat, "tool.argument" → the words, where the protocol's sentence explains a run's or a judge's
     * internals, or repeats what the tool's own description says. The protocol keeps its own sentence.
     */
    static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("library_images.images", "For keep, describe and judge: the pictures, as web addresses, kept pictures' file names or shelf entries."),
            Map.entry("library_images.ask", "For collect: the person's request in their own words; the library reads the form from it."),
            Map.entry("library_images.count", "How many: for find the candidates (default 8, at most 40); for collect the pictures the person asked for."),
            Map.entry("library_translate.quality", "checked (default: one pass and the program's checks) | quick (one pass) | reviewed (a second reading of what the checks flag) | thorough (every unit read again)."),
            Map.entry("library_translate.judge", "For checked: the model entry that reads source and translation side by side, or auto."),
            Map.entry("library_translate.notes", "Where what the checks doubt is written: off (the translation note) | end (also numbered at the end of the document)."),
            Map.entry("library_translate.direction", "The person's own words for the voice, tone or wording, copied as they wrote them, at most 400 characters."),
            Map.entry("library_translate.reads", "For a comic: right-to-left | left-to-right, when the person says which."),
            Map.entry("library_translate.comic", "on (a comic, whatever the program finds; a PDF of page pictures needs this) | off (a document)."),
            Map.entry("library_translate.setup", "The person's yes to setting the comic helper up, after a call answered with confirm."),
            Map.entry("library_research.max_turns", "Optional: the most model turns the whole run may spend."),
            Map.entry("library_research.max_minutes", "Optional: the most minutes the run may take ('two hours tops' = 120)."),
            Map.entry("library_research.collections", "Optional: the person's collections to search, by name."),
            Map.entry("library_research.model", "Optional: one of the library's model entries by name (library_models op=list names them), or auto."),
            Map.entry("library_research.paid", "Optional: false keeps the run to the free search places and readers."),
            Map.entry("library_research.goal", "Optional: the goal the run serves: home, work, dossier, buy, prior-art, family, choose, learn-a-craft or generic."),
            Map.entry("library_research.shape", "Optional: report (the default), reading-list (an annotated bibliography) or outline (headings with one line each)."),
            Map.entry("library_frontier.fate", "For list: what became of the report that left the question."),
            Map.entry("library_frontier.hints", "For list: true marks the questions a claim on the shelves already answers."),
            Map.entry("library_bridges.other", "For distance: the other area."),
            Map.entry("library_bridges.reach", "For run and settings: low (neighbours) | medium (a few hops) | high (any distance)."),
            Map.entry("library_bridges.strict", "For run and settings: true (three shared terms, the default) | false (one term)."),
            Map.entry("library_bridges.via", "For run and settings: terms | graph (paths on the map) | both (default)."),
            Map.entry("library_my_shelf.op", "profile (default) | have | liked | refresh (read the catalogues of the authors rated well) | dna (a book's make-up; no title: the shelf read now) | like (title: the books on the shelf like it) | browse (a spread across kind, mood and pace)."),
            Map.entry("library_owned.op", "status (default) | find | list | import | recalls (check=true reads the feeds now; hit=<id> puts one away) | forget."),
            Map.entry("library_owned.kind", "For import: amazon | mail | bank | google | apple; left out, the file's shape decides."),
            Map.entry("library_dossier.op", "read (default) | list | story (write the subject's story from its claims) | claims (file a report's dated events as claims) | forecasts (with the Brier score) | score (one forecast's outcome: id, outcome) | watch (a standing search; every)."));

    /** An array argument's items in the chat, where the protocol leaves them open: with no shape the chat's model sent [] or made-up objects (3 of 12 right, 2026-11-21). */
    static final Map<String, String> ITEMS = Map.of("library_images.images", "string");

    /**
     * The field instruments the chat's table does not carry (Instruments.forChat): candidates and sift, which the research run the chat
     * files for a remembered video calls; dossier, which library_dossier on the table does; shelf, which library_ask and library_search
     * do. The chat's model chose none of the last two in 954 turns on 0.8.0. Their code stays: a run's table and a goal name them.
     */
    static final Set<String> NOT_IN_CHAT = Set.of("candidates", "sift", "dossier", "shelf");

    /** The instruments whose words begin their third clause with what a run spends (minutes, megabytes, the office's allowance): the chat reads that clause from its next part on. */
    static final Set<String> RUN_COST_FIRST = Set.of("patents", "patent_abstracts", "patent_claims");

    /** One function of the chat's table, as {@code Librarian.tools} built it from the protocol's schema, shaped for the chat in place. */
    static void shape(ObjectNode fn) {
        String name = fn.path("name").asText("");
        JsonNode props = fn.path("parameters").path("properties");
        if (!props.isObject()) return;
        ObjectNode p = (ObjectNode) props;
        for (String a : LEFT_OUT.getOrDefault(name, List.of())) p.remove(a);
        var it = p.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (!e.getValue().isObject()) continue;
            ObjectNode arg = (ObjectNode) e.getValue();
            String key = name + "." + e.getKey();
            if (WORDS.containsKey(key)) arg.put("description", WORDS.get(key));
            if (ITEMS.containsKey(key)) arg.putObject("items").put("type", ITEMS.get(key));
        }
        JsonNode req = fn.path("parameters").path("required");
        if (req.isArray()) { ArrayNode kept = ((ObjectNode) fn.path("parameters")).putArray("required"); for (JsonNode r : req) if (p.has(r.asText())) kept.add(r); }
    }

    /** An instrument's words on the chat's table: its own, with a run's spending taken from the front of the third clause where it stands there. */
    static String words(Instrument i) {
        String w = i.words();
        if (!RUN_COST_FIRST.contains(i.name())) return w;
        String[] parts = w.split(" · ", -1);
        if (parts.length != 3) return w;
        int cut = parts[2].indexOf("; ");
        return cut < 0 ? w : parts[0] + " · " + parts[1] + " · " + parts[2].substring(cut + 2);
    }

    /** Whether a collect call carries any rule of the form (an older table's call, or a program's): then the call's own form is used. */
    static boolean carriesRules(JsonNode args) {
        for (String f : FORM_RULES) if (args != null && args.hasNonNull(f) && !args.path(f).asText("").isBlank()) return true;
        return false;
    }

    /** The arguments that name a file or a folder of this machine, as the chat's tools take them: a path, and pictures named by their path. */
    static final Set<String> PATHS = Set.of("path", "images", "image");

    /**
     * The arguments with a path that begins with {@code ~} read as the person's home ({@code ~/Documents/notes.md}), as the person
     * wrote it. Without it the tools looked for a folder named "~" where the library was started, and the chat's model went looking
     * through /home/&lt;user&gt;, /root and /Users with five or six reads of this machine's folders a turn (2026-11-21).
     */
    static JsonNode homeIn(JsonNode args, String home) {
        if (args == null || !args.isObject() || home == null || home.isBlank()) return args;
        ObjectNode out = ((ObjectNode) args).deepCopy();
        for (String k : PATHS) {
            JsonNode v = out.get(k);
            if (v == null) continue;
            if (v.isTextual()) out.put(k, home(v.asText(), home));
            else if (v.isArray()) { ArrayNode a = (ArrayNode) v; for (int n = 0; n < a.size(); n++) if (a.get(n).isTextual()) a.set(n, out.textNode(home(a.get(n).asText(), home))); }
        }
        return out;
    }

    static String home(String path, String home) {
        String s = path == null ? "" : path.strip();
        String h = home.length() > 1 ? home.replaceAll("[/\\\\]+$", "") : home;
        if (s.equals("~")) return h;
        if (s.startsWith("~/") || s.startsWith("~\\")) return h + s.substring(1);
        return path;
    }

    // ---- the two levels, and the form chosen from the model's own window (0.8.1, step 2) ----

    /**
     * The tool that opens the others: called with a tool's name, that tool joins the table with its arguments, at the end of the table
     * (the server keeps every token before it), for the rest of the conversation. It is no tool of the protocol's: the chat answers it.
     */
    static final String OPENER = "library_tool";

    /**
     * The tools in full when the table lists the others: the look-ups the chat's instructions name first and the model's own fallbacks (24
     * of its 26 wrong first calls on 0.8.0 went to the first five), a question about ResearchZosho itself, and the search and the page
     * outside. On a 16,384-token model these eight in full found the right tool in 100 of 472 asks, five in full in 67 (twice, the same
     * on every ask), 2026-11-22.
     */
    static final List<String> EIGHT = List.of("library_ask", "library_search", "library_get", "library_research", "library_job", "library_help", "search", "open");

    /**
     * The table's forms: the whole, or {@link #EIGHT} in full with every other tool in one line under {@link #OPENER}. Measured and not
     * kept (2026-11-21/22, the 27B at 32,768): seventeen in full with the list found the right tool in 11 fewer of 226 asks than the whole
     * table, the model looking on the shelves before under library_tool; each other tool as a stub of its own (name, line, arguments not
     * described) in 190 of 202 against 200, and drew calls without arguments.
     */
    enum Form {
        WHOLE("whole", null), EIGHT("8", ChatTable.EIGHT);
        final String said; final List<String> inFull;
        Form(String said, List<String> inFull) { this.said = said; this.inFull = inFull; }
        static Form named(String s) { for (Form f : values()) if (f.said.equalsIgnoreCase(s == null ? "" : s.strip())) return f; return null; }
    }

    /**
     * Characters a token for the table, which is JSON, as the model server counted it on 2026-11-21: 3.76 (57,744 characters, 15,362
     * tokens); taken a little under, so the estimate errs large and is the same in every process (the chat's own estimate is corrected by
     * the server's counts as a run goes, and the form would change with it).
     */
    static final double CHARS_PER_TOKEN = 3.6;

    /** A form the tests and the battery set (RESEARCHZOSHO_CHAT_TABLE: whole or 8), in place of the rule; not a setting for a person. */
    static volatile Form forced = null;

    static int tokens(String text) { return (int) Math.ceil(text.length() / CHARS_PER_TOKEN); }

    /**
     * The form a window too small for the whole table gets: eight in full with the list. A turn that does not fit even so is answered by
     * the library's own sentence that it does not fit (Librarian, as in 0.8.0). A method, not a constant: the enum reads this class's
     * lists when it is made, and a constant of it here was null whenever the enum was touched first.
     */
    static Form fallback() { return Form.EIGHT; }

    /**
     * Whether the whole table fits a window: what the turn's retry on an overflow leaves beside the table and the reply (the window, less
     * the table, Librarian.REPLY_TOKENS and Librarian.FIT_MARGIN) is at least the share that retry keeps for the instructions and the
     * history (Librarian.RETRY_HISTORY_SHARE). With 0.8.1's table that holds from a window of about 25,800: 32,768 gets the whole table,
     * and its first round is 57 % of the window (18,671 tokens, the server's count).
     */
    static boolean wholeFits(int window, ArrayNode full) {
        int room = window - tokens(full.toString()) - Librarian.REPLY_TOKENS - Librarian.FIT_MARGIN;
        return room >= window * Librarian.RETRY_HISTORY_SHARE;
    }

    /**
     * The form for a model whose server gives each conversation {@code window} tokens: the whole table when it fits ({@link #wholeFits}),
     * else {@link #fallback()}, where the choice is between that and no chat at all. Chosen from the window the server reports
     * (DriveClient.contextWindow), never from a list of model names.
     */
    static Form formFor(int window, ArrayNode full) {
        Form set = forced != null ? forced : Form.named(Config.get("RESEARCHZOSHO_CHAT_TABLE", ""));
        if (set != null) return set;
        return wholeFits(window, full) ? Form.WHOLE : fallback();
    }

    /**
     * The table a turn sends: the whole; or the form's tools and {@code inFull} (a keeper's MCP tools picked for the turn's words) in
     * full, then the tools opened in this conversation in full, in the order they were opened, then {@link #OPENER} with every other tool
     * in one line. The protocol's names and schemas are {@code full}'s: nothing is renamed.
     */
    static ArrayNode sent(ArrayNode full, Form form, Set<String> inFull, Collection<String> opened) {
        if (form == null || form == Form.WHOLE) return full.deepCopy();
        ArrayNode out = M.createArrayNode();
        List<String> rest = new ArrayList<>();
        for (JsonNode t : full) { String n = name(t); if (form.inFull.contains(n) || inFull.contains(n)) out.add(t.deepCopy()); }
        for (String o : opened) { JsonNode t = find(full, o); if (t != null && !has(out, o)) out.add(t.deepCopy()); }
        StringBuilder lines = new StringBuilder();
        for (JsonNode t : full) {
            String n = name(t);
            if (has(out, n)) continue;
            rest.add(n);
            lines.append(lines.isEmpty() ? "" : "; ").append(n).append(": ").append(line(n, t));
        }
        if (rest.isEmpty()) return out;
        ObjectNode params = M.createObjectNode().put("type", "object");
        ObjectNode name = params.putObject("properties").putObject("name").put("type", "string").put("description", "The tool's name, as listed.");
        ArrayNode e = name.putArray("enum"); rest.forEach(e::add);
        params.putArray("required").add("name");
        out.add(Brain.fn(OPENER, "The library's other tools, one line each. Call library_tool with a tool's name and that tool joins your table with its arguments; then call it. " + lines + ".", params));
        return out;
    }

    /** The sentence the instructions carry when the table lists tools: the instructions name tools that are not on the table yet. */
    static final String OPEN_SENTENCE = "A tool these instructions name that is not on your table is opened with library_tool, by its name.";

    /** What an opening answers: the tool is on the table in full now, with its words. */
    static String opened(String name, JsonNode fn) {
        return name + " is on your table now, with its arguments: " + fn.path("function").path("description").asText("") + " Call it.";
    }

    /**
     * A call to library_tool: the named tool joins the table at its end, and the answer says so with its words. A name on the full list
     * only: any other is refused as a name on no list is.
     */
    static String open(ArrayNode table, ArrayNode full, String name, Set<String> offered) {
        String n = name == null ? "" : name.strip();
        JsonNode t = find(full, n);
        if (t == null) return Librarian.notOffered(n.isEmpty() ? "(no name)" : n, offered);
        if (has(table, n)) return n + " is on your table already: call it with its arguments.";
        table.add(t.deepCopy());
        return opened(n, t);
    }

    /**
     * A call to a tool of the full list that is not on the turn's table yet (called directly, not opened first). With the tool's own
     * arguments (every argument one of its schema's, the required ones there) it joins the table and is carried out as on the whole
     * table: null. Otherwise nothing is run; the tool joins the table and the answer is its form.
     */
    static String unopened(ArrayNode table, ArrayNode full, String name, JsonNode args) {
        if (has(table, name)) return null;
        JsonNode t = find(full, name);
        if (t == null) return null;
        table.add(t.deepCopy());
        JsonNode props = t.path("function").path("parameters").path("properties"), req = t.path("function").path("parameters").path("required");
        Set<String> takes = protocolArguments(name);   // the tool's own arguments as the protocol takes them: the chat's table leaves some out (LEFT_OUT)
        List<String> wrong = new ArrayList<>();
        if (args != null && args.isObject()) args.fieldNames().forEachRemaining(k -> { if (!k.equals("patron") && !props.has(k) && !takes.contains(k)) wrong.add(k); });
        for (JsonNode r : req) if (args == null || !args.hasNonNull(r.asText())) wrong.add(r.asText() + " (required)");
        if (wrong.isEmpty()) return null;
        return "NOT RUN: " + name + " takes other arguments (" + String.join(", ", wrong) + "); it is on your table now, with its arguments: " + t.path("function").path("description").asText("") + " Call it again with them.";
    }

    private static volatile Map<String, Set<String>> protocol = null;

    /** The arguments a library tool takes in the protocol (McpServer.allTools); none for a tool the protocol does not have (an instrument). */
    static Set<String> protocolArguments(String name) {
        Map<String, Set<String>> p = protocol;
        if (p == null) {
            Map<String, Set<String>> m = new HashMap<>();
            for (JsonNode t : McpServer.allTools()) { Set<String> a = new LinkedHashSet<>(); t.path("inputSchema").path("properties").fieldNames().forEachRemaining(a::add); m.put(t.path("name").asText(), a); }
            protocol = p = m;
        }
        return p.getOrDefault(name, Set.of());
    }

    /** The tools of the full list this conversation has opened or called before: they stay on the table. */
    static Set<String> openedIn(List<? extends JsonNode> messages, ArrayNode full) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode m : messages) {
            if (!"assistant".equals(m.path("role").asText())) continue;
            for (JsonNode c : m.path("tool_calls")) {
                String n = c.path("function").path("name").asText("");
                if (OPENER.equals(n)) { JsonNode a = Researcher.parseArgs(c.path("function").path("arguments")); n = a.path("name").asText("").strip(); }
                if (find(full, n) != null) out.add(n);
            }
        }
        return out;
    }

    /**
     * Each tool in one line, for the list under library_tool: what it is, and every action it has (each value of
     * its op argument, by that word: ChatTwoLevelsTest checks it from the tool's own schema). Written so a tool is found by what a person
     * asks of it (the line of a tool with no line here is its description's first sentence).
     */
    static final Map<String, String> ONE = Map.ofEntries(
            Map.entry("library_ask", "a question to the shelves: the entries that answer it"),
            Map.entry("library_search", "search the shelves for a term: hits with ids"),
            Map.entry("library_get", "open one entry by its id (F-…, I-…, A-…, a raw file)"),
            Map.entry("library_established", "whether the shelves establish a claim: the verdict and the entries behind it"),
            Map.entry("library_submit", "file a claim with its sources as a draft"),
            Map.entry("library_add", "read a file, a folder or a page's address into the library; a folder's counts first"),
            Map.entry("library_translate", "translate a document or a comic into a language as a run; a glossary entry"),
            Map.entry("library_images", "pictures: find on the web, collect a number with rules, keep, describe, judge"),
            Map.entry("library_absorb", "a conversation the person had with another assistant: shelved, its claims to check"),
            Map.entry("library_survey", "a code repository, a paper, a website or an issue tracker: survey it (what it is, numbered directions to research), pick directions to run, do the person's own question on it"),
            Map.entry("library_items", "a list of things: checked against the shelves, a question per item"),
            Map.entry("library_db", "the person's own databases, read-only: list, schema, query (one SELECT)"),
            Map.entry("library_holdings", "whether the person's own lists hold a title, an author or a year"),
            Map.entry("library_check", "the person's own draft: its claims to check and its citations"),
            Map.entry("library_reading", "a reading list (BibTeX, RIS, DOIs, addresses) fetched onto the shelves"),
            Map.entry("library_questions", "a list of questions onto the open questions, or out as runs now"),
            Map.entry("library_sources", "the sources the library mines: list, show, add, remove, restore"),
            Map.entry("library_models", "the models runs can use: list, add, remove, a host's key, test"),
            Map.entry("library_settings", "the library's settings and keys: list, set, unset, read_in (the language reports are read in), check the keys"),
            Map.entry("library_share", "reaching the library from outside the house: status, Tailscale, a public link, Cloudflare, off"),
            Map.entry("library_my_shelf", "the person's own book shelf: profile (their taste), have, liked, refresh the authors' catalogues, dna (a book's make-up), like (books like one), browse (a spread)"),
            Map.entry("library_owned", "what the person bought, from their purchase records: status, find, list, import, recalls, forget"),
            Map.entry("library_bookmarks", "a browser's bookmarks fetched onto the shelves"),
            Map.entry("library_meeting", "a meeting transcript: decisions kept, questions filed, claims to check"),
            Map.entry("library_seeks", "groups of the same question researched several times: list, read, merge, drop"),
            Map.entry("library_dossier", "everything held on a person or an organisation: read, list, story, claims from a report, forecasts, score a forecast, watch"),
            Map.entry("library_export", "a report or a dossier saved as a PDF, Word or Markdown file"),
            Map.entry("library_tell", "what the person knows of a subject, filed in their words"),
            Map.entry("library_bridges", "discovery by combination: run from an area, list the proposals, accept or dismiss one, settings, measure the tally, distance between areas"),
            Map.entry("library_frontier", "the open questions: list, add, next, later, park, unpark, drop, tidy"),
            Map.entry("library_inbox", "drafts that wait for the person: list, accept, dispute, retire"),
            Map.entry("library_serials", "saved searches run again on a schedule: list, add, every, park, unpark, remove"),
            Map.entry("library_changes", "what is new on the shelves"),
            Map.entry("library_research", "file a research run on a question"),
            Map.entry("library_job", "a research run by its id: read how it goes, stop, pause, resume, choose (answer its question), allow, decline, limit (a time limit)"),
            Map.entry("library_status", "the library's counts and the version running"),
            Map.entry("library_help", "questions about ResearchZosho itself, from its own documentation"),
            Map.entry("library_sharpen", "turn a vague question into precise ones"),
            Map.entry("library_explain", "an entry or a term explained at a reading level"),
            Map.entry("library_perspectives", "who studies a question and what each would ask"),
            Map.entry("library_map", "the graph around a name: what connects to it"),
            Map.entry("library_who", "family history: which person the web shows under a relative's name is the relative"),
            Map.entry("search", "search one place: the web, studies, records, forums, videos, podcasts, shops, books, software, the shelves"),
            Map.entry("open", "read one address: a page, a PDF, a video's page"),
            Map.entry("look", "what a video shows, read from its frames"),
            Map.entry("patents", "the pool of patents for a prior-art question, by class, years and features"),
            Map.entry("patent_abstracts", "the abstracts of a patent pool read for its features"),
            Map.entry("patent_claims", "what patents claim, by number or from a pool"),
            Map.entry("records", "a United States home's public records by its street address"),
            Map.entry("homes", "the home search: buy or rent, where, the budget"),
            Map.entry("board", "an employer's own careers board and its openings"),
            Map.entry("who", "family history: who is who among the people the web shows under a relative's name"));

    static String line(String name, JsonNode fn) {
        String l = ONE.get(name);
        return l != null ? l : Librarian.firstSentence(fn.path("function").path("description").asText(name)).replaceAll("[.]$", "");
    }

    private static final ObjectMapper M = new ObjectMapper();

    static String name(JsonNode t) { return t.path("function").path("name").asText(""); }
    static JsonNode find(ArrayNode table, String name) { for (JsonNode t : table) if (name(t).equals(name)) return t; return null; }
    static boolean has(ArrayNode table, String name) { return find(table, name) != null; }
}
