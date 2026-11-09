package org.researchzosho.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.LibraryProtocol;
import org.researchzosho.librarian.Patrons;
import org.researchzosho.librarian.ProtocolError;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import java.util.function.Predicate;
import org.researchzosho.Version;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.drive.Declined;
/**
 * The Librarian over MCP (JSON-RPC 2.0): the library protocol's tools ({@link #allTools}, every one of them a row of
 * {@code docs/public/LIBRARY_PROTOCOL.md} §3, which a test pins both ways) and its three resource schemes, over stdio
 * ({@code researchzosho mcp}) and, through {@link #envelopeFor}, the daemon's {@code /rpc}. Every tool takes an optional
 * patron and every result carries library_id / library_name / contract. Protocol errors become JSON-RPC errors with
 * the stable string code in {@code data.code}.
 *
 * <p><b>stdout is the protocol channel.</b> Everything else the process prints goes to stderr for the
 * server's lifetime (logback already targets stderr), so replies are never corrupted.
 */
public final class McpServer {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";
    public static final String SERVER_NAME = "researchzosho";
    /** The release version from the jar manifest, so the server introduces itself as what it is (it said 0.1.2 through 0.1.7). */
    public static final String SERVER_VERSION = Version.string();

    private McpServer() { }

    /** Only tools whose name passes this are listed or callable; null = all. */
    private static volatile Predicate<String> toolFilter = null;

    /** Restrict every entry point (stdio and the daemon's /rpc) to the tools {@code filter} admits. */
    public static void setToolFilter(Predicate<String> filter) { toolFilter = filter; }
    /** The mark the daemon puts on a patron it proved by a key or a session, and whether to believe it: only the daemon sets this, never the stdio server. */
    public static final String PROVEN = "proven";
    private static volatile boolean trustProven;
    public static void trustProven(boolean on) { trustProven = on; }
    static boolean assertedKeeper(JsonNode patron) { return "person".equals(patron.path("did").asText("")) && !(trustProven && patron.path(PROVEN).asBoolean(false)); }

    /** Serve MCP over stdio with only the tools {@code filter} admits. */
    public static void serveStdio(Predicate<String> filter) throws Exception {
        toolFilter = filter;
        serveStdio();
    }

    /** Serve MCP over stdio until stdin closes. */
    public static void serveStdio() throws Exception {
        PrintStream protocol = System.out;
        System.setOut(System.err);
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.err.println("[researchzosho-mcp] ready on stdio (protocol " + PROTOCOL_VERSION + ", library contract " + LibraryProtocol.CONTRACT + ")");
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode req;
            try { req = M.readTree(line); } catch (Exception e) { continue; }
            ObjectNode env = envelopeFor(req);
            if (env == null) continue;
            protocol.println(M.writeValueAsString(env));
            protocol.flush();
        }
    }

    /** One request → its JSON-RPC envelope (result or error), or null for a notification. */
    /** The library a call is served from when a daemon binds one; otherwise the configured library. */
    private static final ThreadLocal<LibraryStore> BOUND = new ThreadLocal<>();

    /**
     * The envelope for a request against {@code store}: the daemon's door. Without this the tool call
     * opened whichever library the config named, which is the daemon's own only by coincidence of config
     * (found by a test that started a daemon on a temp library and read the wrong circulation log).
     */
    public static ObjectNode envelopeFor(JsonNode req, LibraryStore store) {
        BOUND.set(store);
        try { return envelopeFor(req); } finally { BOUND.remove(); }
    }

    public static ObjectNode envelopeFor(JsonNode req) {
        JsonNode id = req.get("id");
        if (id == null || id.isNull()) return null;
        String method = req.path("method").asText("");
        ObjectNode env = M.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.set("id", id);
        try {
            env.set("result", handle(method, req.path("params")));
        } catch (RpcError e) {
            env.set("error", rpcError(e.code, e.getMessage(), e.data, e.extra));
        } catch (Exception e) {
            env.set("error", rpcError(-32603, "internal error: " + e));
        }
        return env;
    }

    public static JsonNode handle(String method, JsonNode params) {
        switch (method) {
            case "initialize": {
                ObjectNode r = M.createObjectNode();
                r.put("protocolVersion", PROTOCOL_VERSION);
                ObjectNode caps = M.createObjectNode();
                caps.set("tools", M.createObjectNode());
                caps.set("resources", M.createObjectNode());
                r.set("capabilities", caps);
                ObjectNode info = M.createObjectNode();
                info.put("name", SERVER_NAME);
                info.put("version", SERVER_VERSION);
                r.set("serverInfo", info);
                return r;
            }
            case "ping":
            case "notifications/initialized":
            case "initialized":
                return M.createObjectNode();
            case "tools/list":
                return toolsList();
            case "tools/call":
                return toolsCall(params);
            case "resources/list":
                return library(p -> p.resourcesList(params.path("cursor").asText(null), resourcePatron(params)));
            case "resources/templates/list":
                return library(p -> p.resourceTemplates());
            case "resources/read":
                return library(p -> p.resourcesRead(params.path("uri").asText(""), resourcePatron(params)));
            default:
                throw new RpcError(-32601, "method not found: " + method);
        }
    }

    private static JsonNode toolsList() {
        ArrayNode all = allTools();
        ObjectNode r = M.createObjectNode();
        if (toolFilter == null) { r.set("tools", all); return r; }
        ArrayNode kept = M.createArrayNode();
        for (JsonNode t : all) if (toolFilter.test(t.get("name").asText())) kept.add(t);
        r.set("tools", kept);
        return r;
    }

    /** The library protocol's tools, in the order the contract lists them. */
    public static ArrayNode allTools() {
        ArrayNode tools = M.createArrayNode();
        tools.add(tool("library_ask",
                "Ask The Librarian — the long-term research library. Returns the answer PACKAGE: full relevant "
                + "entries (findings, shelf articles, captured documents) with states, claim types, sources and "
                + "disputes, open frontier threads, and holds_nothing=true with NO entries when the library has "
                + "nothing. Reviewed background: it never overrides direct evidence you gather yourself.",
                schema(new String[]{"question"},
                        prop("question", "string", "What you want the library's holdings on."),
                        prop("k", "integer", "Optional max entries (default 6)."),
                        prop("peers", "string", "Optional: also ask other libraries — a peer's name, a group, or all; none = only this one. Their answers come back under peers[], each labelled with its library, never merged."),
                        patronProp())));
        tools.add(tool("library_search",
                "Search The Librarian's catalog (hybrid BM25 + dense, CJK-aware). Returns hits {id, kind, title, "
                + "snippet, score, state, subjects[]} and next_cursor; use library_get for one entry in full.",
                schema(new String[]{"query"},
                        prop("query", "string", "Search terms (any language)."),
                        prop("k", "integer", "Optional page size (default 10)."),
                        prop("subject", "string", "Optional: restrict to entries carrying this subject slug."),
                        prop("cursor", "string", "Optional: next_cursor from the previous page."),
                        patronProp())));
        tools.add(tool("library_get",
                "One entry in full by id: body, every source with locator and edition, supersedes / superseded_by, "
                + "subjects, related entries, and the review record {round, reviewer, decision, stale}. An investigation also "
                + "carries sections[] {heading, chars}, sources[] as rows {n, locator, title, edition, published, fetched, language, same_as}, "
                + "claims[] (its findings with bodies) and open_questions[]. A write-up can run past what one call may carry: "
                + "pass section (a heading; \"answer\" = the write-up without the harness's sections; \"workers\", \"references\", "
                + "\"evidence\") to get one section, and offset / max_chars for a window of the body.",
                schema(new String[]{"id"},
                        prop("id", "string", "F-… finding, I-… investigation, A-… article, or a raw file name."),
                        prop("section", "string", "Optional: one section by heading, or \"answer\" for the write-up alone."),
                        prop("offset", "integer", "Optional: start of the body window, in characters."),
                        prop("max_chars", "integer", "Optional: at most this many characters of the body; chars and truncated say what was left."),
                        prop("in", "string", "Optional: a language (ja, English…); a report comes translated into it by the library's model, kept until the report changes, with written_in and read_in."),
                        patronProp())));
        tools.add(tool("library_read",
                "The captured raw text behind a source locator — the verbatim path: read the evidence, not only "
                + "the claim. Returns title, edition, captured_at and the text (truncated at max_chars).",
                schema(new String[]{"locator"},
                        prop("locator", "string", "The source URL as cited, or raw/<file>."),
                        prop("max_chars", "integer", "Optional cap on the text (default 20000)."),
                        patronProp())));
        tools.add(tool("library_established",
                "What the shelves hold on a claim: accepted entries bearing on it, open disputes touching it, and a "
                + "verdict computed from their states — established | disputed | not_established. No model composes it.",
                schema(new String[]{"claim"},
                        prop("claim", "string", "The claim, as a sentence."),
                        patronProp())));
        tools.add(tool("library_submit",
                "Submit one finding as a DRAFT. It enters the librarian's review like any research run and never "
                + "touches canon directly. A claim with no sources is refused (error no_sources). Requires a patron "
                + "with write access.",
                schema(new String[]{"claim", "sources"},
                        prop("claim", "string", "The claim itself, one to three self-contained sentences."),
                        enumProp("claim_type", "extraction | synthesis | interpretation | speculation (default synthesis).", "extraction", "synthesis", "interpretation", "speculation"),
                        arrayProp("sources", "Where it comes from: URLs, edition citations, or {locator, edition, why} objects."),
                        enumProp("confidence", "low | medium | high (default medium).", "low", "medium", "high"),
                        prop("title", "string", "Optional short title (default: the claim's first words)."),
                        prop("triple", "object", "Optional {subject, predicate, object}: the claim as an edge of the graph (library_map). Without it the nightly triples crew derives one."),
                        patronProp())));
        tools.add(tool("library_add",
                "Read material into the library: a file, a folder (a mounted network share is a folder), or a url. "
                + "mode=keep (default) copies the extracted text onto the shelves; the file itself is never copied. "
                + "mode=link reads the files where they are and keeps only a pointer and a hash: nothing is copied, "
                + "and if the drive is not mounted the library says so when it reads. mode=survey counts a folder "
                + "and says what keeping it would take (files, text size, free disk) without shelving anything. "
                + "For a folder, survey first, then ask keep or link, unless the person has already said. "
                + "Paths are only accepted from the keeper (the terminal, the pages); a url from any patron with write access. "
                + "No model runs; to ask a question of what was added, library_research with sources=shelves and the collection.",
                schema(new String[]{},
                        prop("path", "string", "A file or a folder on this machine (absolute path; a mounted share is a path)."),
                        prop("url", "string", "A page or document on the web; always kept."),
                        prop("collection", "string", "The collection to shelve under (default: the folder's name; none for a single file or url)."),
                        enumProp("mode", "keep | link | survey (default keep).", "keep", "link", "survey"),
                        prop("register", "boolean", "For a folder: the housekeeping rescans it for new and changed files."),
                        patronProp())));
        tools.add(tool("library_translate",
                "Translate a document the person has (txt, md, pdf, docx or epub) into a language and write it out in the same format or another, as a job. "
                + "The document is cut into its units (headings, paragraphs, list items, table cells, captions) and each is translated in order with the library's glossary; "
                + "the structure is kept, the layout is not. quality=quick (default) is one pass; quality=checked adds the program's checks (every number, address, citation "
                + "and glossary term on both sides, a length band, no unit dropped), a second reading of every unit by the judge's model when two are set, a revision of what "
                + "was flagged, and a mark on what still stands. The answer is the plan: the units, the characters and the time; library_job follows the run, whose result "
                + "names the file, the translation note beside it and both documents on the shelves. op=glossary lists the glossary, or with term and rendering adds to it. "
                + "Paths are only accepted from the keeper (the terminal, the pages); raw names a document on the shelves.",
                schema(new String[]{},
                        enumProp("op", "translate (default) | glossary.", "translate", "glossary"),
                        prop("path", "string", "The file to translate, on this machine (absolute path)."),
                        prop("raw", "string", "A document on the shelves, by its entry (the raw name library_search or library_read gives)."),
                        prop("to", "string", "The language to translate into: a name (Japanese) or a code (ja)."),
                        enumProp("as", "The format written: md | txt | pdf | docx | epub (default: the input's).", "md", "txt", "pdf", "docx", "epub"),
                        enumProp("quality", "checked (default: one pass, the program's checks, the scores, a revision of what they flag) | quick (one pass) | reviewed (checked, then a second reading of the flagged units and a sample of the rest) | thorough (checked, then every unit read).", "checked", "quick", "reviewed", "thorough"),
                        prop("model", "string", "The model entry that translates (researchzosho models names them); default the library's model. A hosted entry is a paid call: the answer says its cost first (model_cost_dollars), and the run stays inside the entry's monthly cap."),
                        prop("judge", "string", "For checked: the model entry that reads source and translation side by side, or auto for the strongest other entry, local before hosted; default the library's judge seat, else the translator's model in fresh calls with a sample translated back and compared. A hosted entry is a paid call: the answer says its cost first (judge_cost_dollars)."),
                        prop("back_translation", "boolean", "For checked: translate a sample of units back and compare them with the source; on by itself when the same model checks its own work, off otherwise."),
                        prop("collection", "string", "The collection the translation is shelved under (default: the original's)."),
                        prop("term", "string", "For op=glossary: the term as it stands in a source."),
                        prop("rendering", "string", "For op=glossary: the rendering chosen for the term in the language (to)."),
                        prop("remove", "boolean", "For op=glossary with term: remove the entry."),
                        patronProp())));
        tools.add(tool("library_images",
                "Pictures: find candidates on the web for words, keep them on the shelves, and have the model that sees pictures describe or judge them. "
                + "op=find with words returns found[] (each picture's address, the page it stands on, its title, the engine, its size, a small copy's address and when it was found) from Brave when its key is kept and from the library's SearXNG, merged and told apart; "
                + "with criteria each is judged (verdicts[]: yes, no or unsure and one sentence why); with keep=true (write access) each is fetched onto the shelves under catalog/images/, given an entry on the shelves with its page and shown at /images/<file>. "
                + "op=keep with images (addresses, or the found[] objects) keeps them. op=describe with images returns the seeing model's words about each (what it shows, the people, the place, the time, any writing), and its answer to question. "
                + "op=judge with criteria and images returns one typed verdict per picture. An image is a web address, a kept picture's file name or shelf entry, or (from the keeper) a path on this machine. "
                + "When no model sees pictures the answer is unavailable, with the sentence that says what the library has and how one is added (researchzosho models test <name> probes an entry).",
                schema(new String[]{},
                        enumProp("op", "find (default) | keep | describe | judge.", "find", "keep", "describe", "judge"),
                        prop("words", "string", "For find: what the pictures should be of, as a search engine takes it (an event's name, a person's name, a thing)."),
                        prop("count", "integer", "For find: how many candidates at most (default 8, at most 40)."),
                        prop("keep", "boolean", "For find: fetch the candidates onto the shelves (write access)."),
                        prop("criteria", "string", "For find and judge: what a picture should show, in words; each is judged against it."),
                        prop("images", "array", "For keep, describe and judge: the pictures, as web addresses, kept pictures' file names or shelf entries, or the found[] objects of a find."),
                        prop("question", "string", "For describe: what to answer about the picture after describing it."),
                        prop("collection", "string", "For keep: the collection the kept pictures are shelved under."),
                        patronProp())));
        tools.add(tool("library_absorb",
                "Absorb a conversation the person had with another assistant (ChatGPT, Claude, any chat) as a starting point for "
                + "research. Give a path (a saved transcript or an export such as conversations.json), a url, or the pasted text. "
                + "The transcript is shelved as it is; the person's questions join the open questions; the claims the assistant "
                + "made come back as a list to check and are filed as nothing, since an assistant's say-so is not a source. "
                + "verify=true files one research run that checks those claims against sources. An export with many conversations "
                + "takes the first `limit` (default 25) and reports how many remain. Paths from the keeper only (the terminal, the pages).",
                schema(new String[]{},
                        prop("path", "string", "A transcript or export file on this machine (absolute path)."),
                        prop("url", "string", "A shared conversation page or an export on the web."),
                        prop("text", "string", "The conversation itself, pasted."),
                        prop("title", "string", "A title for the thread (default: the file name or the export's own title)."),
                        prop("collection", "string", "Where the transcript is shelved (default conversations)."),
                        prop("verify", "boolean", "File one research run that checks the assistant's claims."),
                        prop("limit", "integer", "How many conversations of an export to take (default 25)."),
                        prop("field", "string", "Optional, with verify=true: the field the checking run is to be, one of the library's fields by name."),
                        allowProp(),
                        localeProp(),
                        patronProp())));
        tools.add(tool("library_survey",
                "A thing the person already has, read as a starting point for research: a code repository (a folder, or a git url "
                + "cloned when git is installed — it says so when git is missing), a paper (a PDF or document file, a DOI, an arXiv page), "
                + "a website or product page (any other url), or an issue tracker (a GitHub issues page, or an export file). The kind "
                + "is detected from the thing, or given. op=survey (the default) reads it, shelves the text, files one draft claim saying "
                + "what it is, what it says or does, what it claims and what it rests on, and returns numbered directions: research "
                + "questions the library could answer about it. Nothing runs at that point. op=pick with picks=\"1,3\" files a research "
                + "run for each direction named; op=do with question files the person's own direction and its run. pick and do take "
                + "the thing by name (as surveyed).",
                schema(new String[]{},
                        enumProp("op", "survey (default), pick, or do.", "survey", "pick", "do"),
                        prop("path", "string", "A folder or a file on this machine (absolute path). Keeper only."),
                        prop("url", "string", "A url: a git repository, a paper, a page, or a GitHub issues page."),
                        prop("kind", "string", "repo, paper, site or issues; detected from the thing when not given."),
                        prop("name", "string", "The name the survey gave the thing, for pick and do."),
                        prop("picks", "string", "For pick: the direction numbers, like \"1,3\"."),
                        prop("question", "string", "For do: what to research about it, in your own words."),
                        patronProp())));
        tools.add(tool("library_repo",
                "A code repository as a starting point for research: library_survey with kind=repo. A folder on this machine (keeper only) or a git url, cloned when git is "
                + "installed; op=survey (the default) reads it, shelves the text, files one draft claim saying what it is and does, and returns numbered directions; "
                + "op=pick with picks=\"1,3\" files a research run for each direction named; op=do with question files the person's own direction.",
                schema(new String[]{},
                        enumProp("op", "survey (default), pick, or do.", "survey", "pick", "do"),
                        prop("path", "string", "A folder on this machine (absolute path). Keeper only."),
                        prop("url", "string", "A git repository's url."),
                        prop("name", "string", "The name the survey gave the repository, for pick and do."),
                        prop("picks", "string", "For pick: the direction numbers, like \"1,3\"."),
                        prop("question", "string", "For do: what to research about it, in your own words."),
                        patronProp())));
        tools.add(tool("library_items",
                "A list of things — books, tools, places, an inventory of line items — as a starting point for research. Give a path "
                + "(one item per line, a markdown table, or a CSV), a url, or the list as text. The list is shelved as it is so the library "
                + "knows what the person has; each item is checked against the shelves (held or not); then, through the lens "
                + "(\"{item}: what it is, who made or wrote it, what it is for, and what is known about it\" unless the person says what "
                + "they want to know), as=frontier (default) files a question per item on the open questions, as=runs sends them out as "
                + "research runs with a lane per item (batches of 8), as=none only reports what is held. Paths from the keeper only.",
                schema(new String[]{},
                        prop("path", "string", "A list file on this machine (absolute path): one item per line, a markdown table, or a CSV (a header with a title column picks that column). A Calibre library folder gives its books, each with authors, year, series and tags."),
                        prop("url", "string", "A list on the web."),
                        prop("text", "string", "The list itself, pasted."),
                        prop("lens", "string", "What to find out about each item; {item} marks where the item goes (default: what it is, who made it, what it is for)."),
                        enumProp("as", "frontier | runs | none (default frontier).", "frontier", "runs", "none"),
                        prop("field", "string", "Optional, with as=runs: the field every run is to be, one of the library's fields by name."),
                        allowProp(),
                        localeProp(),
                        prop("title", "string", "A name for the list (default: the file name)."),
                        prop("column", "string", "For a CSV: the column that holds the items (default: a column named title, name, item or book, else the first)."),
                        prop("collection", "string", "Where the list is shelved (default lists)."),
                        prop("limit", "integer", "How many of the selected items to look at, file or run in this call (default 200, the most). The whole list is shelved regardless."),
                        prop("match", "string", "Only items whose line holds this text (a tag, an author, a year); an array for several, all must hold."),
                        prop("sample", "integer", "Of the matched items, this many at random."),
                        patronProp())));
        tools.add(tool("library_db",
                "The databases the library owner gave read access to (SQLite, PostgreSQL, MySQL or MariaDB, SQL Server, MongoDB, DuckDB and data files). "
                + "op=list names them. op=schema with `database` gives tables, columns, row counts and sample rows: read it before writing a query. "
                + "op=query runs ONE reading statement: `sql` (a single SELECT or WITH), or for MongoDB a `collection` with a `filter` or a `pipeline` as JSON. "
                + "Rows are capped, so count and group in the query. The result is saved as a page; cite its locator. Only the owner may use this, and connections are added from the command line.",
                schema(new String[]{},
                        enumProp("op", "list (default), schema, or query.", "list", "schema", "query"),
                        prop("database", "string", "The database's name, from op=list."),
                        prop("sql", "string", "For query: one SELECT or WITH statement."),
                        prop("collection", "string", "For a MongoDB query: the collection."),
                        prop("filter", "string", "For a MongoDB query: a find filter as JSON."),
                        prop("pipeline", "string", "For a MongoDB query: an aggregation pipeline as a JSON array. Only stages that read."),
                        prop("limit", "integer", "Rows to return (default 100, at most 500)."),
                        patronProp())));
        tools.add(tool("library_holdings",
                "What the person's own lists hold: a Calibre library, an inventory, a reading list — every list shelved through "
                + "library_items. An exact lookup: every word of the query must appear in an entry (title words, an author, a year). "
                + "Ask it for each candidate to tell owned from not owned; a search snippet is a guess, this is not.",
                schema(new String[]{"query"},
                        prop("query", "string", "Words of a title, an author, a year: \"wizard earthsea le guin\"."),
                        prop("limit", "integer", "Matches to return (default 20, up to 100)."),
                        patronProp())));
        tools.add(tool("library_who",
                "Family history: who, of the people the web shows under a name, is the person in the family. A name is shared, and only somebody "
                + "in the family can say which entry is their relative. op=list: who waits for an answer. op=show: one person's entries, numbered, "
                + "each with who they are, their pages, and what those pages say that the family's facts also say. op=answer: the family's word. "
                + "op=tell: something said of the person, kept in those words for their search. op=find: look one person up now (about half a minute). "
                + "The answer is the family's own word: show the entries and ask.",
                schema(new String[]{"op"},
                        enumProp("op", "list, show, answer, tell or find.", "list", "show", "answer", "tell", "find"),
                        prop("person", "string", "The person in the family, as the library writes the name. For show: empty takes the first who waits."),
                        prop("is", "string", "For answer: the numbers of the entries who ARE the person, as \"1\" or \"1,3\" when two entries are the same person."),
                        prop("none", "boolean", "For answer: none of the entries is the person."),
                        prop("later", "boolean", "For answer: they cannot tell now; the question stays answered as unsure."),
                        prop("text", "string", "For tell: what was said of the person, as they said it."),
                        patronProp())));
        tools.add(tool("library_remove",
                "Take a report or a claim out of the library for good. id is a report (I-…) or a claim (F-…). For a report, what=all "
                + "(default) removes it and the claims that are its alone, what=report the report only, what=claims its claims only; a "
                + "claim another report also cites stays and is named. dry=true shows the plan and removes nothing. Files are deleted, "
                + "the index and the changes log updated; what the run fetched from the web stays. There is no undo — retire is the "
                + "gentler thing for a claim.",
                schema(new String[]{"id"},
                        prop("id", "string", "A report id (I-…) or a claim id (F-…)."),
                        enumProp("what", "For a report: all (default), report, or claims.", "all", "report", "claims"),
                        prop("dry", "boolean", "Show the plan only."),
                        patronProp())));
        tools.add(tool("library_check",
                "Check the person's own draft — a memo, a chapter, notes (text, markdown, Word, PDF) — as a starting point. One voice: "
                + "its definite statements come back as claims to check, its questions join the open questions, and the citations it carries "
                + "(urls, DOIs, arXiv ids) are fetched onto the shelves; one that cannot be read becomes a source request. Nothing becomes a finding. "
                + "verify=true files one research run that checks the claims against sources, the draft's own citations included. Paths from the keeper only.",
                schema(new String[]{},
                        prop("path", "string", "The draft on this machine (absolute path)."),
                        prop("url", "string", "The draft on the web."),
                        prop("text", "string", "The draft itself, pasted."),
                        prop("title", "string", "A title (default: the document's own, or the file name)."),
                        prop("collection", "string", "Where the draft and its citations are shelved (default drafts)."),
                        prop("fetch_citations", "boolean", "Fetch the citations onto the shelves (default true)."),
                        prop("verify", "boolean", "File one research run that checks the claims."),
                        prop("field", "string", "Optional, with verify=true: the field the checking run is to be, one of the library's fields by name."),
                        allowProp(),
                        localeProp(),
                        patronProp())));
        tools.add(tool("library_reading",
                "A reading list as a starting point: BibTeX, RIS (Zotero, EndNote), a CSV export, or lines of DOIs, urls and titles. Every entry "
                + "with a locator is fetched onto the shelves as a collection; one that cannot be read becomes a source request; an entry with a title "
                + "only is listed so the person can give it a locator or research it by name (library_items). watch=true has the housekeeping "
                + "re-read the pages. Then library_research with sources=shelves and the collection reads from them. Paths from the keeper only.",
                schema(new String[]{},
                        prop("path", "string", "The list on this machine (absolute path)."),
                        prop("url", "string", "The list on the web."),
                        prop("text", "string", "The list itself, pasted."),
                        prop("title", "string", "A name for the list (default: the file name)."),
                        prop("collection", "string", "The collection the entries are shelved in (default: from the title)."),
                        prop("watch", "boolean", "Re-read the pages nightly and keep a new copy when one changes."),
                        prop("limit", "integer", "How many entries to take (default all, up to 200)."),
                        patronProp())));
        tools.add(tool("library_questions",
                "A list of questions — a syllabus, an exam, what the person needs answered — onto the open questions in order (as=frontier, the "
                + "default; the housekeeping's explorer works through them at night), or as research runs one each up to 10 (as=runs; the rest are "
                + "filed as open questions). One question per line. Paths from the keeper only.",
                schema(new String[]{},
                        prop("path", "string", "The file on this machine (absolute path)."),
                        prop("url", "string", "The list on the web."),
                        prop("text", "string", "The questions, pasted, one per line."),
                        enumProp("as", "frontier | runs (default frontier).", "frontier", "runs"),
                        prop("field", "string", "Optional, with as=runs: the field every run is to be, one of the library's fields by name."),
                        allowProp(),
                        localeProp(),
                        prop("title", "string", "A name for the list."),
                        prop("limit", "integer", "How many questions to take (default all, up to 200)."),
                        patronProp())));
        tools.add(tool("library_mcp",
                "The OTHER MCP servers whose tools this library's research may use as sources, the keeper's alone: list them, with whether each answers and which of its tools research may call; "
                + "show one's tools; add one (a command this computer starts, an address, or exa or tavily by name alone); remove one; allow or forbid a tool; let readers use a server; cap its calls a month; "
                + "test one; call one of its tools as research would. Research calls the tools that read; a tool that may change things stays off unless allowed. A key is never returned. Since 0.7.0.",
                schema(new String[]{},
                        enumProp("op", "list (default) | tools | test | add | remove | allow | forbid | readers | cap | call.", "list", "tools", "test", "add", "remove", "allow", "forbid", "readers", "cap", "call"),
                        prop("name", "string", "The server's short name: small letters, digits and dashes. With add, exa or tavily alone adds that search engine's server."),
                        arrayProp("command", "With add: the command that starts a server on this computer, and its arguments, one word an item."),
                        prop("env", "object", "With add and a command: environment variables for it, as names and values."),
                        prop("url", "string", "With add: the address of a server spoken to over Streamable HTTP."),
                        prop("header_key", "string", "With add and an address: the setting its key is kept in, such as mcp_docs_key."),
                        prop("header", "string", "With add and an address: the header the key is sent in; Authorization (as Bearer) when absent."),
                        prop("header_value", "string", "With add and an address: the key itself, kept in that setting and never returned."),
                        prop("readers", "boolean", "With add: readers' research and chats may use it too."),
                        prop("cap", "integer", "With add: the most calls research makes to it a month; 0 for no cap."),
                        prop("timeout", "integer", "With add: seconds a call may take before it is given up (60 unless set)."),
                        prop("tool", "string", "With allow, forbid and call: the tool's name as the server lists it."),
                        prop("value", "string", "With readers: on or off. With cap: calls a month, or off."),
                        prop("arguments", "object", "With call: the tool's arguments."),
                        prop("check", "boolean", "With list: ask each server now whether it answers (true unless set)."),
                        patronProp())));
        tools.add(tool("library_sources",
                "The places the library mines for a field — registers, directories, boards, list pages, feeds, saved searches — each with what it yields and for whom. "
                + "op=list (field, for, off=true to include the ones taken off); op=show with id; op=add with address (and for, field, kind, yields, note), after the person said so; "
                + "op=remove with id takes one off (a saved search is parked); op=restore with id brings it back. Read access for list and show, write for the rest.",
                schema(new String[]{},
                        enumProp("op", "list (default) | show | add | remove | restore.", "list", "show", "add", "remove", "restore"),
                        prop("id", "string", "With show, remove and restore: the source's id, as list gives it."),
                        prop("field", "string", "With list: only the sources of this field; with add: the field the source is for."),
                        prop("for", "string", "With list: words the source's address, subject, place or note must carry; with add: whom or what the source is for."),
                        prop("off", "boolean", "With list: include the sources taken off."),
                        prop("address", "string", "With add: the source's address, or the words of a search to run again."),
                        prop("kind", "string", "With add: the kind of place (register, directory, board, list, feed, search…); read off the address when left out."),
                        prop("yields", "string", "With add: what it yields (openings, companies, listings…)."),
                        prop("note", "string", "With add: a note shown beside it."),
                        patronProp())));
        tools.add(tool("library_models",
                "The models this library can run on, by name: list them; add one (a name, a host address or word — anthropic, openai, openrouter, gemini, nebius, huggingface — a model id, prices per million tokens as in/out/cache, an effort, a monthly cap in dollars); remove one; keep a host's key; "
                + "test one (whether its server answers now: answers, starting, or down, with a note when no key is kept for its host). The list states the default outright: default (name, model, host, drive) is the entry runs and chats that name no model use, summary says it in one sentence, and each entry's key is a word, kept, missing or not needed (a local server). A key is never returned. Write access.",
                schema(new String[]{},
                        enumProp("op", "list (default) | add | remove | key | test", "list", "add", "remove", "key", "test"),
                        prop("name", "string", "With add, remove or test: the entry's name — letters, digits and hyphens."),
                        prop("drive", "string", "With add: the host's address, or a word: anthropic, openai, openrouter, gemini, nebius, huggingface."),
                        prop("model", "string", "With add: the model id on that host."),
                        prop("prices", "string", "With add, optional: dollars per million tokens as in/out or in/out/cache, for example 0.024/0.72/0.005."),
                        prop("effort", "string", "With add, optional: the reasoning effort the host takes (low, medium, high …)."),
                        prop("cap", "string", "With add, optional: dollars a month; a run that would start past it is refused."),
                        prop("note", "string", "With add, optional: a note shown beside the entry."),
                        prop("host", "string", "With key: the host (openrouter.ai) or its word."),
                        prop("value", "string", "With key: the key; blank forgets it."),
                        patronProp())));
        tools.add(tool("library_settings",
                "The library's settings (write access): op=list shows every setting with what it is for and its value, a key only as set or not; "
                + "op=set with name and value writes one (name as the key, or without its RESEARCHZOSHO_ prefix; a blank value, or op=unset, unsets it). "
                + "A program sets a key or an address only when the person gave it in this conversation. "
                + "op=read_in with value sets the language the person reads reports in (a blank unsets it); read access is enough. "
                + "op=check asks every outside service whose key is set whether it is accepted and what is left, keeps the answers and returns checks[].",
                schema(new String[]{},
                        enumProp("op", "list (default) | set | unset | read_in | check.", "list", "set", "unset", "read_in", "check"),
                        prop("name", "string", "The setting, e.g. podcastindex_key or RESEARCHZOSHO_WHISPER."),
                        prop("value", "string", "The value to write (set)."),
                        patronProp())));
        tools.add(tool("library_share",
                "The library reached from outside the house (write access): op=status says how it stands; op=tailscale, op=public (Tailscale Funnel: anyone with the link) "
                + "or op=cloudflare (a quick tunnel; mail=… lets only listed addresses through) turns the relay on when its program is on the machine, only when the person "
                + "asked for it in this conversation; op=off turns it off. A missing program is installed by the person on their own screen: researchzosho share, or /share in the chat.",
                schema(new String[]{},
                        enumProp("op", "status (default) | tailscale | public | cloudflare | off.", "status", "tailscale", "public", "cloudflare", "off"),
                        prop("mail", "string", "For cloudflare: the addresses or *@domains let through, comma-separated."),
                        patronProp())));
        tools.add(tool("library_my_shelf",
                "The person's own shelf: their Calibre library (the calibre setting), the podcasts they follow, what they bought. op=profile: what they went for — the books they added one by one (a day of fifty or more additions is an import: holdings, not taste) and any they rated themselves — "
                + "the subject mix among the liked books with its tilt against the whole shelf, the authors rated well more than once, decades, languages, sample "
                + "titles, podcasts followed, shops bought from; an imported rating is set aside. op=have with title (and author): whether a book is "
                + "already on the shelf, in the purchase ledger or on a holdings list. op=liked with words: the rated books on a subject or by an author, best first. "
                + "op=dna with title: a shelf book's DNA — the text counted (length, sentences, dialogue, reading grade, the feeling's arc) and the model's reading of it over twelve windows (kind, themes, moods against an ordinary book of its kind, pace, darkness, scale, humour, style, drive, topics), made now when missing; without a title (the owner) the night's reading of the shelf runs now.",
                schema(new String[]{},
                        enumProp("op", "profile (default) | have | liked | refresh (the owner: the catalogues of the authors rated well read now, for the what-next pool) | dna (a book's DNA; the owner without a title: the shelf read now) | like (with title: the books on the shelf like it by their DNA — subject first, then kind, audience, demand, mood, pace; the author's own works set aside; under the person's preferences file and reactions) | browse (a spread of labelled books across kind, mood, pace, scale and subject, the preferences as the prior, one well-known title in, what the person reacted to left out; seed for the same list again).", "profile", "have", "liked", "refresh", "dna", "like", "browse"),
                        prop("minutes", "integer", "With op=refresh: how long to spend (default 2, up to 30); with op=dna and no title: minutes to read the shelf (default 20)."),
                        prop("title", "string", "The book's title, or an ISBN (have)."),
                        prop("author", "string", "The author, to tell books of one title apart (have)."),
                        prop("words", "string", "A subject, a tag, an author or title words (liked)."),
                        prop("stars", "number", "liked: only the books the person rated at least this themselves; left out, every book they went for."),
                        prop("limit", "integer", "How many books (liked; default 20, up to 60; like: default 10, up to 40)."),
                        patronProp())));
        tools.add(tool("library_dna",
                "The DNA store beyond the shelf. op=status: how many documents and how many labelled, per kind, and whether the person shares. op=pack-export (the owner) with path writes a pack of the labelled documents (the shared pool's format: the works' layers and provenance, nothing of the person); op=pack-import (the owner) with path reads one in; op=pack-refresh fetches the pack researchzosho.org announces, when there is one. "
                + "op=podcast-like with words: the podcast episodes like one, among the transcripts the library holds, the same show set aside; op=podcast-read (the owner) reads the transcripts for their DNA now.",
                schema(new String[]{},
                        enumProp("op", "status (default) | pack-export | pack-import | pack-refresh | records (the owner: the catalogue's subjects, editions and ratings joined to the shelf's documents now, the labelled first) | podcast-like | podcast-read.", "status", "pack-export", "pack-import", "pack-refresh", "records", "podcast-like", "podcast-read"),
                        enumProp("kind", "book (default) | podcast.", "book", "podcast"),
                        prop("path", "string", "The pack file to write or read."),
                        prop("words", "string", "Words of the episode's show or title (podcast-like)."),
                        prop("limit", "integer", "How many rows (podcast-like; default 10)."),
                        prop("minutes", "integer", "How long to read (podcast-read; default 5) or to fill records (records; default 2)."),
                        patronProp())));
        tools.add(tool("library_follow",
                "The podcasts the person follows: a show's feed kept as a search, so its new episodes land on the arrival table and the housekeeping transcribes them at night. "
                + "op=list the shows followed; op=follow with feed (the feed's address; library_search or the podcast search gives it) and every (days, 1 unless set), only when the person asked for it in this conversation; "
                + "op=unfollow with which (the show's name as listed, or its feed address). The transcripts already in the library stay. Read access for list, write for the rest.",
                schema(new String[]{},
                        enumProp("op", "list (default) | follow | unfollow.", "list", "follow", "unfollow"),
                        prop("feed", "string", "With follow: the podcast feed's address."),
                        prop("every", "integer", "With follow: how often to look for new episodes, in days (default 1)."),
                        prop("which", "string", "With unfollow: the show's name as listed, or its feed address."),
                        patronProp())));
        tools.add(tool("library_prefs",
                "The person's preferences file for books: what they want more and less of, each line with its source (drafted from the shelf, learned from the reactions, or said by the person). op=show (default) the lines; "
                + "op=draft (the owner) writes one from the shelf; op=set with name and value changes a line (weight.subject 2 | roam 0.4 | popularity one|none|known | words \"…\" | exclude.author|subject|kind|series <value> | prefer.mood|subject|kind|pace|author <value> | avoid.<the same> <value>); "
                + "op=unset drops one; op=export and op=import with path carry the file; op=learn reads the reactions into it. \"Like X\" and browsing read it. A program sets a line only when the person said so in this conversation.",
                schema(new String[]{},
                        enumProp("op", "show (default) | draft | set | unset | export | import | learn.", "show", "draft", "set", "unset", "export", "import", "learn"),
                        prop("name", "string", "The line to set or unset, e.g. prefer.mood, exclude.author, weight.subject, roam, popularity, words."),
                        prop("value", "string", "Its value (set); the value to drop (unset, optional)."),
                        prop("path", "string", "The file to write or read (export, import)."),
                        patronProp())));
        tools.add(tool("library_react",
                "Writes the person's reaction to a book down: like, dislike, seen or own, by title (and author); about=<title> when the book was a row of a \"like\" list for that title. A disliked book leaves later lists, a seen or owned one is marked, and the preferences file learns from the likes and dislikes. The owner, and only when the person said so in this conversation.",
                schema(new String[]{"reaction", "title"},
                        enumProp("reaction", "like | dislike | seen | own.", "like", "dislike", "seen", "own"),
                        prop("title", "string", "The book's title."),
                        prop("author", "string", "Its author, to tell books of one title apart."),
                        prop("about", "string", "The X whose \"like\" list the book was on, by title."),
                        patronProp())));
        tools.add(tool("library_owned",
                "What the person owns: their purchases, imported from their own records (the zip Amazon's data request returns, a mailbox of order "
                + "confirmations, a bank or card statement as CSV). op=status says what the ledger holds and where each file's records begin; op=find with "
                + "words (and year, shop) looks something up: what was bought, when, where, for how much; op=import with path reads a file on this machine "
                + "(the owner only); op=forget with file drops what one file added. The ledger is a reference, never a requirement.",
                schema(new String[]{},
                        enumProp("op", "status (default) | find | list | import | recalls (the recall notices that may concern what is owned; check=true reads the feeds now, hit=<id> puts one away) | forget.", "status", "find", "list", "import", "recalls", "forget"),
                        prop("check", "boolean", "With op=recalls: read the safety feeds now instead of waiting for the night (write)."),
                        prop("hit", "string", "With op=recalls: the id of a hit to put away as not yours or dealt with (write)."),
                        prop("words", "string", "Words the thing's name, shop or order number carry (find)."),
                        prop("year", "string", "A year, such as 2019 (find, list)."),
                        prop("shop", "string", "A shop's name or part of it (find, list)."),
                        prop("limit", "integer", "How many lines (default 50, up to 500)."),
                        prop("path", "string", "The file or folder to read (import)."),
                        enumProp("kind", "amazon (the zip a data request returns) | mail (a mailbox file or folder, or .eml files) | bank (a statement as CSV) | google (Takeout's Play Store orders) | apple (the store transaction history from Apple's download); left out, the file's shape decides (import).", "amazon", "mail", "bank", "google", "apple"),
                        prop("currency", "string", "The currency to assume when a record has none, such as USD or JPY (import)."),
                        prop("file", "string", "The imported file to forget, as op=status lists it (forget)."),
                        patronProp())));
        tools.add(tool("library_bookmarks",
                "A browser's bookmarks as a starting point: the exported bookmarks file, Chrome's Bookmarks JSON, or lines of urls. Each page is "
                + "fetched onto the shelves as a collection (folder=… takes one folder); one that cannot be read becomes a source request. "
                + "watch=true has the housekeeping re-read the pages and keep a new copy when one changes. Paths from the keeper only.",
                schema(new String[]{},
                        prop("path", "string", "The bookmarks file on this machine (absolute path)."),
                        prop("url", "string", "The list on the web."),
                        prop("text", "string", "The urls, pasted, one per line."),
                        prop("folder", "string", "Only the bookmarks in this folder."),
                        prop("collection", "string", "The collection (default bookmarks, or the folder's name)."),
                        prop("watch", "boolean", "Re-read the pages nightly."),
                        prop("limit", "integer", "How many to take (default all, up to 200)."),
                        patronProp())));
        tools.add(tool("library_meeting",
                "A meeting transcript as a starting point: WebVTT (Zoom, Teams, Otter) or \"Name: words\" lines. The transcript goes on the "
                + "shelves with its decisions; the questions raised join the open questions; the claims made come back to check, with who said them. "
                + "Nothing becomes a finding. verify=true files one research run that checks the claims. Paths from the keeper only.",
                schema(new String[]{},
                        prop("path", "string", "The transcript on this machine (absolute path)."),
                        prop("url", "string", "The transcript on the web."),
                        prop("text", "string", "The transcript, pasted."),
                        prop("title", "string", "The meeting's name (default: the file name)."),
                        prop("collection", "string", "Where it is shelved (default meetings)."),
                        prop("verify", "boolean", "File one research run that checks the claims."),
                        prop("field", "string", "Optional, with verify=true: the field the checking run is to be, one of the library's fields by name."),
                        allowProp(),
                        localeProp(),
                        patronProp())));
        tools.add(tool("library_seeks",
                "Seeks: the same question researched several times apart (library_research with seek=<group>, each on its own copy of the shelves, "
                + "on any model), and one report merged from them. op=list the groups and their seeks; op=read a seek's report (group, job_id) or "
                + "the merged one (group); op=merge files the merge run: the judge reads the seeks' claims in pairs, agreed claims are filed as findings, "
                + "contradictions as disputed findings with the question on the frontier, what only one seek found is listed; op=drop removes a "
                + "group's seeks and their shelves (the merged report stays).",
                schema(new String[]{},
                        enumProp("op", "list | read | merge | drop (default list).", "list", "read", "merge", "drop"),
                        prop("group", "string", "The seek group, for read, merge and drop."),
                        prop("job_id", "string", "For read: one seek's run id (J-…); leave out for the merged report."),
                        patronProp())));
        tools.add(tool("library_dossier",
                "Everything the library holds on a person or an organisation, as one document: the relations the graph has, the claims by the "
                + "class of their source (public record, own publication, told, supplied, third-party), a timeline, what was told in conversation, "
                + "the reports to dive into, the open questions, what was looked for and not found, and the subjects it links to. A view over the "
                + "shelves, written fresh each time; a research run adds to it.",
                schema(new String[]{}, prop("subject", "string", "The person or the organisation, by name; leave it out to list the dossiers the shelves hold."),
                        enumProp("op", "read (default): the dossier. list: every dossier the shelves hold, with its kind, counts, last run and whether a story is written. story: write the story of the subject as a report, chapter by chapter from the claims the library holds, every sentence citing its claim; listed on the dossier, a page to dive into. claims: file the dated events of the subject's reports as claims, for reports that landed before this step existed. forecasts: the ledger of dated forecasts the reports made (all, or the subject's), each with its likelihood, confidence and outcome, and the Brier score over the scored ones. score (write): the outcome of one forecast once its date has passed — id, outcome (happened, did-not, void), note. watch (write): keep a standing search on the subject, every seven days unless every says otherwise; its arrivals land in the inbox.", "read", "list", "story", "claims", "forecasts", "score", "watch"),
                        prop("every", "integer", "For watch: the days between searches (default 7)."),
                        prop("id", "string", "For score: the forecast's id, P-…."),
                        prop("outcome", "string", "For score: happened, did-not or void."),
                        prop("note", "string", "For score: what settled it, in a sentence."),
                        patronProp())));
        tools.add(tool("library_export",
                "A REPORT OR A DOSSIER AS A FILE: a PDF or a Word file holding what its page shows (a report's title, question, the answer's sections, "
                + "how each point was checked, the open questions and sub-goals, the checks, the claims with their marks and the sources as links; "
                + "a dossier's sections), written under the library's catalog/exports/ and served by its web pages at /exports/<file>. Returns path, "
                + "url, file, bytes. A report comes in the language in names, else in the caller's own language to read in, when the library keeps "
                + "that translation; otherwise as written, and note says so. format=md is the same document as Markdown text. Read access. Since 0.7.0.",
                schema(new String[]{},
                        prop("id", "string", "A report's id (I-…). Give id or subject."),
                        prop("subject", "string", "A dossier's person or organisation, by name. Give id or subject."),
                        enumProp("format", "pdf (default) | docx (a Word file) | md (Markdown text).", "pdf", "docx", "md"),
                        prop("in", "string", "Optional: a language (ja, English…) the file is in, from the translation the library keeps."),
                        patronProp())));
        tools.add(tool("library_suggest",
                "What the library suggests the person do next, from the shelves' own events: a forecast whose date passed, a story the shelves have grown past, a question of theirs a claim "
                + "now answers, a question asked again and again, a report that landed, a dossier not researched for months. Each is classed now, next or hold with the probability they take it "
                + "up; their marks are the outcomes a Brier score is kept over. op=list (default): the open suggestions, now first. op=mark (write): id, outcome (taken, dismissed, later), note.",
                schema(new String[]{}, enumProp("op", "list (default) | mark", "list", "mark"), prop("id", "string", "For mark: the suggestion's id, S-…."), prop("outcome", "string", "For mark: taken, dismissed or later."),
                        prop("note", "string", "For mark: a word on why, optional."), patronProp())));
        tools.add(tool("library_tell",
                "What a person tells the library about a subject from their own knowledge: what it was like, who was there, an incident, a date. "
                + "Filed as a claim of class told, in their words, dated, with them as the writer; the questions it raises that the public record "
                + "could answer join the open questions, so an account can open a search. Answer with 'noted:' and what it raised.",
                schema(new String[]{"subject", "text"}, prop("subject", "string", "Who or what this is about."), prop("text", "string", "What they said, in their words, a sentence or more."), patronProp())));
        tools.add(tool("library_bridges",
                "Discovery by combination: two areas of the library that no source read together, joined by specific terms both "
                + "areas' claims share (Swanson's fish oil and Raynaud's). op=run computes the candidate pairs from an area (or the "
                + "hottest ones) and files up to a few proposals as open questions of type bridge; dry=true only shows the pairs. "
                + "op=list the open proposals; op=accept files the research run that tests one (its verified claims are the test); "
                + "op=dismiss drops it; op=settings reads or sets an area's dials (sources, reach, strict, toward, away, since, per_night); "
                + "op=measure reads the ledger: proposed, kept, dismissed, corroborated.",
                schema(new String[]{},
                        enumProp("op", "run | list | accept | dismiss | settings | measure | distance (default list).", "run", "list", "accept", "dismiss", "settings", "measure", "distance"),
                        prop("area", "string", "For run and settings: the subject to start from (default: the hottest areas); for distance: one of the two areas."),
                        prop("other", "string", "For distance: the other area — returns hops on the map, shared terms, paths, sources naming both, and the nearest concepts by embedding with their cosine."),
                        prop("question", "string", "For accept and dismiss: the proposal, as listed."),
                        prop("sources", "string", "For run and settings: library, peers, web — comma-separated (default library). With web, a pair the shelves do not join is taken outside: the model names what could bear on both, and a web search has to carry each candidate together with EACH area before it is proposed."),
                        enumProp("reach", "For run and settings: low (neighbours) | medium (a few hops, a tenth random) | high (any distance, a third random).", "low", "medium", "high"),
                        prop("strict", "boolean", "For run and settings: three shared terms and no source naming both (true, default), or one term and no claim joining them (false)."),
                        prop("toward", "string", "For run and settings: measure distance toward this area instead of outward."),
                        prop("away", "string", "For run and settings: words that rule an area out, comma-separated."),
                        prop("since", "string", "For run and settings: only areas with a finding dated on or after this (YYYY-MM-DD)."),
                        enumProp("via", "For run and settings: terms (words both areas' claims are about) | graph (paths on the map through concepts in between) | both (default).", "terms", "graph", "both"),
                        prop("propose", "integer", "For run: proposals to file this time (default the area's per_night, 3)."),
                        prop("per_night", "integer", "For settings: proposals a night for the crew (0 turns it off)."),
                        prop("dry", "boolean", "For run: show the pairs and questions, file nothing."),
                        patronProp())));
        tools.add(tool("library_frontier",
                "The queue of open questions the housekeeping's explorer researches a few of each night — op=list returns them in queue order with type, parked, position, tonight, "
                + "the report that left each (report, report_title, report_fate: kept | waiting | disputed | retired | none), perspective, subjects, language, and similar (the head of a group that reads alike); "
                + "list takes filters: type, show (queued | parked | all, default all), report (an id or its prefix), fate, who (perspective text), subject, language, q (words); hints=true adds answered {id, title, state} where a claim on the shelves already answers a question. "
                + "op=add queues one attributed to the patron; op=next moves one to the head; op=later to the tail; op=park keeps one out of the explorer's reach, with why (the reason, listed back as parked_why with the date); op=unpark returns it; "
                + "op=drop closes one without researching it; op=tidy removes duplicate lines (write access). A report's leftover questions are filed parked.",
                schema(new String[]{},
                        enumProp("op", "list (default) | add | next | later | park | unpark | drop | tidy.", "list", "add", "next", "later", "park", "unpark", "drop", "tidy"),
                        prop("question", "string", "For add, next, later, park, unpark, drop: the question, exactly as listed."),
                        prop("why", "string", "For park: why it waits, for example: waits on the record office's answer."),
                        enumProp("type", "For list: report | asked | person | dispute | check.", "report", "asked", "person", "dispute", "check"),
                        enumProp("show", "For list: queued | parked | all (default all).", "queued", "parked", "all"),
                        prop("report", "string", "For list: only questions left by this investigation (an id, or its prefix such as I-0016)."),
                        enumProp("fate", "For list: kept | waiting | disputed | retired | none — what became of the report that left the question.", "kept", "waiting", "disputed", "retired", "none"),
                        prop("who", "string", "For list: the perspective the question was asked from, matched as text."),
                        prop("subject", "string", "For list: a subject slug."),
                        prop("language", "string", "For list: english, japanese, … — the language a question is in or asks for."),
                        prop("q", "string", "For list: words that must all appear in the question."),
                        prop("hints", "boolean", "For list: look each question up on the shelves and add answered when a claim already answers it."),
                        patronProp())));
        tools.add(tool("library_inbox",
                "The claims waiting for the keeper's decision — drafts, and accepted claims whose review went stale — and the decisions. op=list returns items[] {id, title, state, stale, kind, tier, confidence, writer, date, subjects, language, sources, report, report_title}, "
                + "oldest first, with filters report (an investigation id or its prefix), subject, kind (extraction | synthesis | interpretation | speculation), tier, confidence, writer, state (draft | stale), language, q (words in the title). "
                + "op=accept puts claims into every answer from now on; op=dispute files why; op=retire keeps them on disk and out of every answer — each takes ids[] (or id), or report for every waiting claim of one investigation (write access). "
                + "op=list also returns reports[] {id, title, state, held_by, date, topic, reasons}: the reports that wait for the keeper's eye because one of their own checks stands against them (the write-up did not answer the question, or part of it; "
                + "flagged sentences never sent for revision; most cited sources not read); op=accept with such a report's id (I-…) in id or ids accepts it as it stands.",
                schema(new String[]{},
                        enumProp("op", "list (default) | accept | dispute | retire.", "list", "accept", "dispute", "retire"),
                        arrayProp("ids", "For accept, dispute, retire: the claim ids."),
                        prop("id", "string", "One claim id, instead of ids."),
                        prop("report", "string", "For list: only claims from this investigation; for a decision: every waiting claim of it."),
                        prop("why", "string", "For dispute: the reason (required)."),
                        prop("subject", "string", "For list: a subject slug."),
                        enumProp("kind", "For list: the claim type — extraction | synthesis | interpretation | speculation.", "extraction", "synthesis", "interpretation", "speculation"),
                        prop("tier", "string", "For list: the strongest source's tier."),
                        enumProp("confidence", "For list: low | medium | high.", "low", "medium", "high"),
                        prop("writer", "string", "For list: who wrote the claim, matched as text (crew:explorer, person, …)."),
                        enumProp("state", "For list: draft | stale.", "draft", "stale"),
                        prop("language", "string", "For list: the language of the claim's title."),
                        prop("q", "string", "For list: words that must all appear in the title."),
                        patronProp())));
        tools.add(tool("library_serials",
                "The searches the housekeeping keeps running on a cadence and reports what is new from — op=list (each with parked and due); op=add {name, query, every_days} keeps one; "
                + "op=every {name, every_days} changes its cadence; op=park {name} keeps it without running it, op=unpark {name} puts it back in the rotation; op=remove {name} stops keeping it (write access).",
                schema(new String[]{},
                        enumProp("op", "list (default) | add | every | park | unpark | remove.", "list", "add", "every", "park", "unpark", "remove"),
                        prop("name", "string", "A short name for the search (letters, digits, dashes)."),
                        prop("query", "string", "For add: the search query."),
                        prop("every_days", "integer", "For add: how often to re-run it (default 7)."),
                        patronProp())));
        tools.add(tool("library_subjects",
                "The controlled vocabulary: {id, label, broader, narrower[], count}. Facets (the part before '--') "
                + "are the broader terms. Use an id with library_search's subject filter.",
                schema(new String[]{}, patronProp())));
        tools.add(tool("library_changes",
                "Recall notices: everything that happened to findings and investigations after a cursor — added, "
                + "state changes (retired, disputed, accepted), edits, supersessions. Re-check what you cited with one call. "
                + "Pass the previous next_cursor as since; 0 = from the beginning.",
                schema(new String[]{},
                        prop("since", "string", "Cursor from the previous call (default 0)."),
                        prop("limit", "integer", "Max changes per page (default 200)."),
                        patronProp())));
        tools.add(tool("library_request_access",
                "Ask to be let into this library. No access is needed to ask. Returns a request id and a claim secret (shown once); the owner "
                + "approves or denies; collect the outcome with library_access.",
                schema(new String[]{"did"},
                        prop("did", "string", "Who is asking, as a did."),
                        prop("name", "string", "A name the owner will recognise."),
                        prop("note", "string", "Why, in a sentence or two."),
                        patronProp())));
        tools.add(tool("library_access",
                "What became of an access request (op=collect, the default, with request_id and claim): pending, denied (with the reason), or approved, in which case the token, once, when the claim secret is right. "
                + "The keeper's side, for the person who keeps the library alone (anyone else is refused): op=readers lists who may read or write and the default; op=allow with did, level (deny, read or write) and name sets a reader's level; "
                + "op=remove with did removes one; op=invite with name and level makes a link that signs a new person in once, shown once; op=requests lists who asked to be let in; op=approve with request_id and level, or op=deny with request_id and reason (or did), answers one; "
                + "op=keys lists the keeper's keys for programs; op=key with label and level makes one, shown once; op=revoke with key_id revokes one. Nothing here is done unless the person asked for it in this conversation.",
                schema(new String[]{},
                        enumProp("op", "collect (default) | readers | allow | remove | invite | requests | approve | deny | keys | key | revoke.", "collect", "readers", "allow", "remove", "invite", "requests", "approve", "deny", "keys", "key", "revoke"),
                        prop("request_id", "string", "With collect, approve and deny: the id library_request_access returned."),
                        prop("claim", "string", "With collect: the claim secret it returned."),
                        prop("did", "string", "With allow, remove and deny: the reader's did."),
                        enumProp("level", "With allow, invite, approve and key: deny | read | write.", "deny", "read", "write"),
                        prop("name", "string", "With allow: the reader's name; with invite: the new person's name, as they will sign in."),
                        prop("reason", "string", "With deny: why, in a sentence."),
                        prop("label", "string", "With key: what the key is for."),
                        prop("key_id", "string", "With revoke: the key's id or prefix, as keys lists it."),
                        patronProp())));
        tools.add(tool("library_subscribe",
                "Register a webhook: every change on this library's feed (retired, disputed, revised, supplied, …) is POSTed to your address, "
                + "signed with a secret (X-ResearchZosho-Signature: sha256=hmac). The changes feed stays the source of truth. Needs a named patron.",
                schema(new String[]{"url"},
                        prop("url", "string", "Where to POST. http(s); a program on this machine is fine."),
                        prop("secret", "string", "Optional. The signing secret; one is made for you when absent and returned once."),
                        arrayProp("events", "Optional: only these events (retired, disputed, revised, supplied, accepted, …). Absent = all."),
                        patronProp())));
        tools.add(tool("library_unsubscribe",
                "Remove a webhook you registered.",
                schema(new String[]{"url"}, prop("url", "string", "The address you registered."), patronProp())));
        tools.add(tool("library_research",
                "A RESEARCH RUN: send a question to The Librarian's workers. The library's own runner plans "
                + "sub-questions (yours, if you pass them), researches each in parallel with web search + fetch noting every "
                + "fact with its source, has a critic decide on a second round, and writes the investigation in sections; the "
                + "result enters the library as a draft investigation attributed to you and the review crew extracts findings "
                + "from it. Returns a job_id to poll with library_job. Write access. "
                + "Takes minutes to hours — do not wait inline. A field that acts only when asked for, such as family history, joins the run only when "
                + "field names it; a question that looks like one of its questions is researched as ordinary research, and the result then carries "
                + "suggestion {field, why, how}: what the field's mode does and how to ask for it.",
                schema(new String[]{"question"},
                        prop("question", "string", "The research question, as you would put it to a librarian."),
                        enumProp("mode", "broad (survey the landscape, default) | depth (deep-read a narrow question).", "broad", "depth"),
                        prop("max_turns", "integer", "Optional ceiling: the most model turns the WHOLE run may spend (workers, critic, synthesis and cite-check together). Absent or 0 = no ceiling, the run goes until the work is done."),
                        prop("max_minutes", "integer", "Optional ceiling: the most wall-clock minutes the run may take ('two hours tops' = 120). The workers stop early enough for the write-up to fit. Absent or 0 = no ceiling."),
                        arrayProp("sub_questions", "Optional plan: up to 8 self-contained sub-questions, each researched by its own worker instead of a decompose step."),
                        enumProp("sources", "both (the shelves first, then the web — default) | shelves (the person's own corpus only, no web) | web.", "both", "shelves", "web"),
                        arrayProp("collections", "Optional: names of the person's collections (folders shelved with `researchzosho add <dir>`) to search."),
                        prop("quick", "boolean", "Look it up now: the front of the line and short ceilings (12 turns, 6 minutes) unless the ask names its own."),
                        prop("model", "string", "Optional: the named model to run on, one of the library's model entries by name (library_models op=list names them), or auto for best judgement, which is the default when more than one is named. Each entry is a host, a model id and its prices; the run's report says which ran it and what it cost."),
                        prop("paid", "boolean", "Optional: false leaves the paid search places and the paid page reader off for this run (Exa, Tavily, the keeper's own search service, Serper, SerpApi, Brave, Firecrawl); the free places answer. True unless given. Since 0.7.0."),
                        prop("seek", "string", "Optional: a seek group's name (letters, digits, dashes). The run reads and writes its own copy of the shelves under that name, so several seeks of one question never see each other's work; library_seeks op=merge writes one report from them."),
                        prop("goal", "string", "Optional: the goal the run serves, by name (home, work, dossier, buy, prior-art, family, choose, learn-a-craft, generic), which chooses the report's shape and the instruments the run brings; a field's name is taken as its goal until the goals land. Since 0.7.0."),
                        prop("length", "string", "Optional: how long the report is: short (about 400 words), medium (about 1,200), long (about 3,000), or a number of words. Without it, the library's setting report_length, else as long as the question needs. Since 0.7.0."),
                        enumProp("citations", "Optional: how the report's sources are written: numbered (the default), apa, mla, chicago or ieee. The numbers in the text stay, so every cited sentence is still checked. Without it, the library's setting citation_style. Since 0.7.0.", "numbered", "apa", "mla", "chicago", "ieee"),
                        enumProp("shape", "Optional: report (the default); reading-list, an annotated bibliography, each source with what it is, why it matters for the question, how strong it is and its link; outline, headings with one line each. A question that asks for a format in its own words gets that format. Since 0.7.0.", "report", "reading-list", "outline"),
                        prop("field", "string", "Optional: the field the run is to be, one of the library's fields by name (researchzosho profile list shows them). An unknown or switched-off field is refused with a sentence saying which fields there are."),
                        allowProp(),
                        localeProp(),
                        patronProp())));
        tools.add(tool("library_candidates",
                "CANDIDATES FOR A VIDEO YOU REMEMBER but cannot name: give the name of the thing made or shown, two to four words, in each language the video may be in, and what you remember of the video itself as short phrases in your own words (remembered), never a search, "
                + "and the library enumerates for itself: deep YouTube searches of its own plain phrasings, the pages where other people describe a video in the words remembered, every upload of the channels those surface, the YouTube "
                + "Data API in date order when a key is set, other search indexes, and your own watch history first. It drops what a machine can check (rejected ids, uploads outside the dates, "
                + "lengths outside the minutes) and answers with the pool best fit first, a video whose title, description or linking page holds more of what is remembered before one that holds less, forty a page, with no popularity in it. About ten minutes; the pool is kept, so page 2 fetches nothing. "
                + "Then give the first five to library_look. Since 0.7.0.",
                schema(new String[]{"things"},
                        arrayProp("things", "The thing made or shown, two to four words, once per language the video may be in: [\"apron\", \"エプロン\", \"앞치마\"]."),
                        arrayProp("remembered", "Optional: what is remembered of the video itself, short phrases in the person's own words, one thing each (what happens, what is seen or heard, who or what appears): [\"nobody speaks\", \"a pocket on the front\"]. The library makes its own web searches from them; it is never a search itself. Since 0.7.0."),
                        prop("before", "string", "Optional: the video was watched before this month or day, 2025-11 or 2025-11-05; uploads after it are dropped."),
                        prop("after", "string", "Optional: uploaded after this month or day."),
                        prop("minutes_min", "integer", "Optional: the shortest the video can be, in minutes."),
                        prop("minutes_max", "integer", "Optional: the longest the video can be, in minutes."),
                        arrayProp("exclude", "Optional: video ids or links the person has already rejected."),
                        prop("language", "string", "Optional: the language of the video's title or on-screen text when the person said it, as a code (ja, ko, zh, ru, en); titles in another script are dropped."),
                        prop("page", "integer", "Optional: the page of a pool already made today with the same things, 2 for the second forty."),
                        patronProp())));
        tools.add(tool("library_sift",
                "SIFT THE CANDIDATES for a video you remember, the funnel between the pool and the five a look can afford: every candidate's thumbnail, then the first storyboard sheet of those that "
                + "pass, each judged yes, maybe or no by the model that reads pictures, keeping yes and maybe; the thumbnail for what one still can show (the thing, its colours, a tag or label, a "
                + "person shown or not), the first sheet for what you remember about the start; what no picture shows is left to the look. Give the pool's key from "
                + "library_candidates (from) and what you remember (what), or the videos themselves (videos). The thumbnails go nine to a call as one numbered grid, under a second each, and a sheet takes about ten seconds on a local model; it says how many "
                + "were not reached, and the same call with page=2 goes on; nothing is judged twice; one sift runs in a library at a time, and a second call while one runs comes back at once saying so. Then give the first five survivors to library_look. Since 0.7.0.",
                schema(new String[]{"what"},
                        prop("what", "string", "What you remember of the video, one thing after another, separated by semicolons."),
                        prop("from", "string", "The key of the kept pool, as library_candidates answered it; the newest pool when absent and no videos are given."),
                        arrayProp("videos", "Optional: the videos to sift instead of a pool, by link or id."),
                        arrayProp("things", "Optional, with videos: the thing made or shown, once per language, as library_candidates took it."),
                        prop("limit", "integer", "Optional: the most candidates taken anew in this call (200 outside a research run)."),
                        prop("page", "integer", "Optional: 2 to go on after a call the time cut short."),
                        patronProp())));
        tools.add(tool("library_patents",
                "PRIOR ART, THE POOL: every patent publication of a classification in a span of years, enumerated from the European Patent Office's own records page by page to the end, "
                + "never a search engine's first page. Give the classification symbols the invention sits in (each a CPC group with its slash, like A47G19/22), the years, and the invention's "
                + "features, each a short phrase with the words and synonyms that would name it in a title or an abstract, in every language you can give; never a query. The library says the "
                + "office's own count, keeps one line a patent family with the other members on it, counts which features each family's titles and abstracts name, and answers with the pool "
                + "ordered by more features named, then the rarer feature, then the older, forty a page, with no citation count, family size or other popularity in the order. A class of a "
                + "thousand publications takes two to five minutes and about ten megabytes of the office's free four gigabytes a week; every page is kept, so page 2 and the reading fetch "
                + "nothing. It needs the office's free key (researchzosho records key epo-query <key> <secret>). Then give the key to library_patent_abstracts. Since 0.7.0.",
                schema(new String[]{"classes", "features"},
                        arrayProp("classes", "The classification symbols, each a CPC group with its slash: [\"A47G19/22\"]. A class is found from the office's classification, or read off a known patent with library_patent_claims."),
                        prop("from_year", "integer", "Optional: the first year of publication; the twenty years before to_year when absent."),
                        prop("to_year", "integer", "Optional: the last year of publication; this year when absent."),
                        arrayProp("features", "The invention's features, each a short phrase with the words and synonyms that would name it: [\"lid, cover, cap, Deckel, 蓋\", \"straw, drinking tube, ストロー\"]."),
                        arrayProp("exclude", "Optional: publication numbers already rejected; their families are left out."),
                        prop("page", "integer", "Optional: the page of the pool, 2 for the second forty; a pool the time cut goes on with it."),
                        patronProp())));
        tools.add(tool("library_patent_abstracts",
                "PRIOR ART, THE ABSTRACTS: the cheap reading between a pool of patents and their claims. The library's model reads the abstract of every family in the pool, best fit first, "
                + "twenty to a call, and says of each feature yes, maybe or no; the families that pass come back best first with the counts. Give the pool's key from library_patents (from); "
                + "the features are the pool's. About ten seconds for twenty abstracts on a local model; it says how many were not read, and the same call with page=2 goes on; nothing is "
                + "read twice; one reading runs in a library at a time. Then give the key to library_patent_claims. Since 0.7.0.",
                schema(new String[]{},
                        prop("from", "string", "The key of the kept pool, as library_patents answered it; the newest pool when absent."),
                        prop("limit", "integer", "Optional: the most families read anew in this call (400 outside a research run)."),
                        prop("page", "integer", "Optional: 2 to go on after a call that did not read them all."),
                        patronProp())));
        tools.add(tool("library_patent_claims",
                "PRIOR ART, THE CLAIMS: the claims of patents from the patent office's own full text. For a pool (from), the claims of the families that pass by their abstracts, best first, "
                + "and for each feature the claim that comes closest, quoted with its number and the office's link, or that none that was read meets it. For publications given by number "
                + "(publications), each one's record, its classes and its claims. Where the office holds no text for a publication (it holds none for the United States, China, Japan or Korea) "
                + "the family member whose text it does hold is read, and the answer says which; where no member has a text there, the claims are read from Google Patents' copy of the office's "
                + "record at the publication's own address, never by a search there, and the answer says so; a publication with claims at neither is read by its abstract, said to be an abstract. "
                + "A quotation is kept only when its words stand in the text as read; a status worded on Google's page is given as Google's own reading. About twenty seconds a family; "
                + "the same call with page=2 goes on; nothing is read twice. Since 0.7.0.",
                schema(new String[]{},
                        prop("from", "string", "The key of the kept pool, as library_patents answered it; the newest pool when absent and no publications are given."),
                        arrayProp("publications", "Optional: publication numbers to read instead of a pool: [\"EP1000000A1\"]."),
                        arrayProp("features", "Optional, with publications: the features to read the claims for, as library_patents takes them."),
                        prop("limit", "integer", "Optional: the most families read anew in this call (20 outside a research run)."),
                        prop("page", "integer", "Optional: 2 to go on after a call that did not read them all."),
                        patronProp())));
        tools.add(tool("library_look",
                "LOOK AT A VIDEO at any address: the library reads its frames and transcript into three tracks, what was said, the words shown on screen and what is seen, keeps them, "
                + "and answers what you ask of it with found, maybe or no and the second. About seven minutes per ten minutes of video on a local model; sparse=true reads a third of the "
                + "frames. Since 0.7.0.",
                schema(new String[]{"video"},
                        prop("video", "string", "The video's address (a YouTube link)."),
                        prop("what", "string", "What to look for, in your words: a thing, a moment, words on screen."),
                        prop("sparse", "boolean", "true: every fourth frame and the first minute in full, a third of the time."),
                        patronProp())));
        tools.add(tool("library_records",
                "THE PUBLIC RECORDS OF A HOME by its address: one line saying which source answered (the city's or county's own portal, a state's parcel layer, a written reader) or that "
                + "none is known and what was looked at; the sales or values and the permits on the street; the Census tract's figures when a key is set. Seconds to a minute; once a county, "
                + "the library researches where it publishes its records. Since 0.7.0.",
                schema(new String[]{"address"},
                        prop("address", "string", "The address as street, city, state, for example 108 Example St, Cambridge, MA."),
                        patronProp())));
        tools.add(tool("library_board",
                "AN EMPLOYER'S OWN CAREERS BOARD and its openings: the hiring system the employer uses, found from its careers page or from the organisation it belongs to, and the openings "
                + "read from it, cut to the towns you name. Seconds to a minute. Since 0.7.0.",
                schema(new String[]{"employer"},
                        prop("employer", "string", "The employer's or institution's name."),
                        arrayProp("towns", "Optional: towns the openings are cut to."),
                        arrayProp("words", "Optional: words an opening's title must carry."),
                        patronProp())));
        tools.add(tool("library_video",
                "THE VIDEOS THE LIBRARY HAS READ, beyond the three tools that find one (library_candidates, library_sift, library_look): op=status (default) says how many videos are held and with which tracks; "
                + "op=saw with query finds the moments where words were said or shown across every video held, with the second and a link; op=picture with description files the picture in the person's head "
                + "(what they remember seeing) as a run that reads the held videos' frames for it (write). The ops look, candidates and sift are taken too, as those tools take them.",
                schema(new String[]{},
                        enumProp("op", "status (default) | saw | picture | look | candidates | sift.", "status", "saw", "picture", "look", "candidates", "sift"),
                        prop("query", "string", "With saw: the words remembered hearing or seeing."),
                        prop("k", "integer", "With saw: how many moments (default 8, up to 50)."),
                        prop("description", "string", "With picture: what the person remembers seeing, in their words."),
                        arrayProp("kinds", "With picture, optional: the kinds of thing remembered (a place, a person, an object…)."),
                        patronProp())));
        tools.add(tool("library_home",
                "THE HOME SEARCH beyond one address's records: a person looking for a home to buy or rent. op=list the searches; op=start with name; op=read (search) the topics decided, guessed and open; "
                + "op=tell with words, read by the model for the topics they settle; op=want with dimension, state (decided, guess, open) and want; op=keep_out with words (drop=true takes it back); op=search files the search run "
                + "(more=true: the next page of listings); op=listings (show) the kept listings through the cut, in tiers, with the ones set aside and why; op=react with id and reaction (like, pass, or blank) and why; "
                + "op=card with id reads the records and researches one listing; op=add with words (url, tenure) keeps a listing the person found; op=records with address is library_records. Read access for list, read, listings and records; write for the rest.",
                schema(new String[]{},
                        enumProp("op", "list | start | read (default) | tell | want | keep_out | search | listings | react | card | add | records.", "list", "start", "read", "tell", "want", "keep_out", "search", "listings", "react", "card", "add", "records"),
                        prop("search", "string", "The search's name; left out when the library holds one."),
                        prop("name", "string", "With start: the new search's name."),
                        prop("words", "string", "With tell, keep_out and add: the words."),
                        prop("dimension", "string", "With want: the topic (place, price, beds, tenure, walk, floor…)."),
                        enumProp("state", "With want: decided | guess | open.", "decided", "guess", "open"),
                        prop("want", "string", "With want: what is wanted for the topic."),
                        prop("drop", "boolean", "With keep_out: take the words back."),
                        prop("more", "boolean", "With search: the next page of the portal's listings for the cut as it stands."),
                        prop("show", "integer", "With listings: how many to show in the text (default 12)."),
                        prop("id", "string", "With react and card: the listing's id."),
                        prop("reaction", "string", "With react: like, pass, or blank to take a reaction back."),
                        prop("why", "string", "With react: why, in a few words."),
                        prop("url", "string", "With add: the listing's page."),
                        prop("tenure", "string", "With add: buy or rent."),
                        prop("address", "string", "With records: the address as street, city, state."),
                        patronProp())));
        tools.add(tool("library_career",
                "THE CAREER SEARCH beyond one employer's board: a person looking for work. op=list the people kept; op=provide with what (a resume, a LinkedIn file, a path for the keeper, or words) and name; "
                + "op=evidence the facts kept; op=reading the model's reading of the working life (write); op=shape (words) how seats are filled for them; op=wants what they want, drafted again; op=questions the interview, "
                + "op=answers with answers (a number to its answer) and op=resettle; op=company and op=keep_out with words (drop=true takes it back); op=search files the search run and op=more reads more; "
                + "op=openings (show) and op=companies (show) what was found; op=react with id or company, reaction (like, pass, or blank) and why; op=company_state with name and state (kept, dropped, or blank); "
                + "op=watch (on) the standing search; op=cut (titles, titles_also, titles_not, places, remote, remote_where, prefer_titles, prefer_places, again) the cut; op=want with dimension, state and want; op=board with employer is library_board. "
                + "Read access for list, evidence, questions, openings, companies, cut and watch without changes, and board; write for the rest.",
                schema(new String[]{},
                        enumProp("op", "list | provide | evidence | reading | shape | wants | questions | answers | resettle | company | keep_out | search | more | openings | react | companies | company_state | watch | cut | want | board (default read).", "list", "provide", "evidence", "reading", "shape", "wants", "questions", "answers", "resettle", "company", "keep_out", "search", "more", "openings", "react", "companies", "company_state", "watch", "cut", "want", "board"),
                        prop("seeker", "string", "The person, by name; left out when the library keeps one."),
                        prop("what", "string", "With provide: the resume or file (a path, for the keeper), or the words."),
                        prop("name", "string", "With provide: the person's name; with company_state: the company's."),
                        prop("words", "string", "With shape, company and keep_out: the words."),
                        prop("drop", "boolean", "With company and keep_out: take the words back."),
                        prop("answers", "object", "With answers: the interview's question numbers to the answers."),
                        prop("vary", "string", "With search and more: how to vary the search, when asked."),
                        prop("show", "integer", "With openings and companies: how many to show in the text."),
                        prop("id", "string", "With react: the opening's id."),
                        prop("company", "string", "With react: a company of the place instead of an opening."),
                        prop("reaction", "string", "With react: like, pass, or blank to take a reaction back."),
                        prop("why", "string", "With react: why, in a few words."),
                        prop("state", "string", "With company_state: kept, dropped, or blank; with want: decided, guess or open."),
                        prop("on", "boolean", "With watch: the standing search on or off."),
                        prop("again", "boolean", "With cut: make the cut again from what is kept."),
                        prop("dimension", "string", "With want: the topic."),
                        prop("want", "string", "With want: what is wanted for the topic."),
                        prop("employer", "string", "With board: the employer's or institution's name."),
                        arrayProp("towns", "With board, optional: towns the openings are cut to."),
                        patronProp())));
        tools.add(tool("library_job",
                "One job from the ledger ({job}: state queued|running|done|failed|stopped|offered, elapsed_s, result when finished), or "
                + "your jobs (active[], finished[], paused) when job_id is omitted. op=stop {job_id} stops one run: queued, it never starts; running, it ends within seconds. "
                + "op=pause holds the runner (queued runs wait, a running one holds at its next turn); op=resume lets it go (write access). "
                + "op=choose {job_id, choice} answers a run held for one of its named choices, by number or by name; op=allow and op=decline answer for the person on a run the service held for their yes about help: "
                + "a program sends any of the three only after it asked the person (write access).",
                schema(new String[]{},
                        enumProp("op", "read (default) | stop | pause | resume | choose | allow | decline.", "read", "stop", "pause", "resume", "choose", "allow", "decline"),
                        prop("job_id", "string", "The job to read, stop or answer for, e.g. J-0007."),
                        prop("choice", "string", "With choose: the choice, by its number or its name as offered."),
                        prop("limit", "integer", "Without a job id: how many finished runs a page holds (default 20)."),
                        prop("cursor", "string", "Without a job id: next_cursor from the previous page."),
                        patronProp())));
        tools.add(tool("library_crews",
                "THE HOUSEKEEPING: op=plan (default) says what its next run will do, from the same files and plan it uses — the kept searches due, the open questions the explorer takes and in what bundles, the counts, "
                + "the weekly and monthly extras — as runs[] and as text; op=run files its tasks as a job now, which the service runs behind any run in progress (write).",
                schema(new String[]{},
                        enumProp("op", "plan (default) | run.", "plan", "run"),
                        patronProp())));
        tools.add(tool("library_spend",
                "WHAT RESEARCH RUNS COST, at each model's list prices: the library's arithmetic on the servers' usage counts, not a bill. This month and in all, by host and by named model, "
                + "with the cap where one is set, as hosts[] and entries[] and as text. Write access, as the settings.",
                schema(new String[]{}, patronProp())));
        tools.add(tool("library_status",
                "library_id, library_name, contract, counts by kind and state, last_updated.",
                schema(new String[]{}, patronProp())));
        tools.add(tool("library_help",
                "The library on ITSELF: what ResearchZosho is, which version is running, what it can and cannot do, how to do something in it, "
                + "and why something does not work. Returns its live state (version, what this library holds, the model and whether it answers, "
                + "the web search, the fields that are on, the chat's commands, the pages) and the sections of the program's own documentation "
                + "that answer the question, with where to ask what neither answers. Use it for any question about the library or the program "
                + "rather than answering from memory.",
                schema(new String[]{}, prop("question", "string", "What the person wants to know about ResearchZosho, in English words; empty for the state alone."), patronProp())));
        tools.add(tool("library_sharpen",
                "SHARPEN a rough research question BEFORE running it: the library looks at what it already holds, asks who studies "
                + "the question and what each would ask, and answers with the question rewritten, the assumptions it made (read them: a model "
                + "sharpens by narrowing), a brief (scope, sub_questions, sources, deliverables), what is already held, at most three "
                + "questions_for_you, a mode and a size (quick | full), and research_question — the text to pass to library_research as it "
                + "is or after the person edits it. Proposes only; never runs research. Needs a model drive; two or three calls.",
                schema(new String[]{"question"},
                        prop("question", "string", "The rough question, as the person typed it."),
                        patronProp())));
        tools.add(tool("library_explain",
                "A READING AID, never a record. id + rung re-explains an entry for a reader at that rung: beginner (new to the "
                + "field), familiar (knows the field but not this work) or written (the sources' own register). term (+ in, the entry it "
                + "appears in) explains a term as it is used there. Shelves only: written from the entry, its findings and its captured "
                + "sources; every paragraph is read back against what it cites and marked when the shelves do not support it. grounding "
                + "is shelves | thin | none; when none, offer carries the library_research call that would fill the gap — pass it as it "
                + "is (quick: true runs it now, ahead of the night's work), then call library_explain again. terms[] are words the reader "
                + "may need next; each is a further library_explain. Needs a model drive the first time; cached after.",
                schema(new String[]{},
                        prop("id", "string", "The entry to explain (F-…, I-…, A-…)."),
                        prop("term", "string", "A term to explain instead; with in, as it is used in that entry."),
                        prop("in", "string", "The entry id a term appears in."),
                        enumProp("rung", "beginner (default) | familiar | written.", "beginner", "familiar", "written"),
                        prop("fresh", "boolean", "Regenerate instead of using the cached reading."),
                        patronProp())));
        tools.add(tool("library_perspectives",
                "Before researching: WHO studies this question and what would each of them insist on asking (STORM's move). "
                + "Returns perspectives [{perspective, why, questions[]}] and sub_questions[] ready to pass to library_research. "
                + "One or two searches and one judge call; needs a model drive.",
                schema(new String[]{"question"},
                        prop("question", "string", "The research question."),
                        prop("max", "integer", "Max perspectives (default 5)."),
                        patronProp())));
        tools.add(tool("library_map",
                "The GRAPH around a focus: a person, place, event, document, organisation, work or concept "
                + "by name, or an entry id. Returns the neighbourhood — nodes {id, kind, label, also[], wikidata} and edges, "
                + "where every edge IS a finding {from, predicate, to, finding, state, confidence, disputed} — plus the open "
                + "questions touching it. This is how a family shelf and a computer-science shelf meet at one person.",
                schema(new String[]{"focus"},
                        prop("focus", "string", "A node name (any language, any alias) or an entry id."),
                        prop("depth", "integer", "Hops from the focus (default 1, max 3)."),
                        prop("k", "integer", "Max nodes (default 25)."),
                        patronProp())));
        return tools;
    }

    /**
     * Who reads a resource: the {@code patron} in the params, as a tool call names it in its arguments (the daemon's /rpc puts the
     * caller its token proved there). A caller who names nobody reads at the library's default level.
     */
    private static Patrons.Patron resourcePatron(JsonNode params) {
        if (assertedKeeper(params.path("patron"))) {
            throw new RpcError(403, "The did \"person\" is the keeper's own and cannot be asserted by a client; name your patron by its did.", "forbidden");
        }
        return Patrons.Patron.from(params);
    }

    private static JsonNode toolsCall(JsonNode params) {
        String name = params.path("name").asText("");
        if (toolFilter != null && !toolFilter.test(name)) throw new RpcError(-32601, "unknown tool: " + name);
        JsonNode args = params.path("arguments");
        // "person" is the keeper at the command line and the chat — in-process callers. An MCP client
        // is a patron and names itself by its did; letting it say "person" skipped the allow list
        // (found by Wyrdsekai, 2026-09-07).
        if (assertedKeeper(args.path("patron"))) {
            throw new RpcError(403, "The did \"person\" is the keeper's own and cannot be asserted by a client; name your patron by its did.", "forbidden");
        }
        return library(p -> switch (name) {
            case "library_ask" -> p.ask(args);
            case "library_search" -> p.search(args);
            case "library_get" -> p.get(args);
            case "library_read" -> p.read(args);
            case "library_established" -> p.established(args);
            case "library_submit" -> p.submit(args);
            case "library_add" -> p.add(args);
            case "library_translate" -> p.translate(args);
            case "library_images" -> p.images(args);
            case "library_absorb" -> p.absorb(args);
            case "library_survey" -> p.survey(args);
            case "library_repo" -> p.repo(args);
            case "library_items" -> p.items(args);
            case "library_holdings" -> p.holdings(args);
            case "library_who" -> p.who(args);
            case "library_db" -> p.db(args);
            case "library_remove" -> p.remove(args);
            case "library_check" -> p.check(args);
            case "library_reading" -> p.reading(args);
            case "library_questions" -> p.questions(args);
            case "library_bookmarks" -> p.bookmarks(args);
            case "library_settings" -> p.settings(args);
            case "library_models" -> p.models(args);
            case "library_mcp" -> p.mcp(args);
            case "library_sources" -> p.sources(args);
            case "library_video" -> p.video(args);
            case "library_home" -> p.home(args);
            case "library_career" -> p.career(args);
            case "library_crews" -> p.crews(args);
            case "library_spend" -> p.spend(args);
            case "library_follow" -> p.follow(args);
            case "library_share" -> p.share(args);
            case "library_owned" -> p.owned(args);
            case "library_my_shelf" -> p.myShelf(args);
            case "library_prefs" -> p.prefs(args);
            case "library_dna" -> p.dna(args);
            case "library_react" -> p.react(args);
            case "library_meeting" -> p.meeting(args);
            case "library_bridges" -> p.bridges(args);
            case "library_seeks" -> p.seeks(args);
            case "library_dossier" -> p.dossier(args);
            case "library_export" -> p.export(args);
            case "library_suggest" -> p.suggest(args);
            case "library_tell" -> p.tell(args);
            case "library_frontier" -> p.frontier(args);
            case "library_inbox" -> p.inbox(args);
            case "library_serials" -> p.serials(args);
            case "library_subjects" -> p.subjects(args);
            case "library_status" -> p.status(args);
            case "library_help" -> p.help(args);
            case "library_changes" -> p.changes(args);
            case "library_request_access" -> p.requestAccess(args);
            case "library_access" -> p.access(args);
            case "library_subscribe" -> p.subscribe(args);
            case "library_unsubscribe" -> p.unsubscribe(args);
            case "library_research" -> p.research(args);
            case "library_look" -> p.video(withOp(args, "look"));
            case "library_candidates" -> p.video(withOp(args, "candidates"));
            case "library_sift" -> p.video(withOp(args, "sift"));
            case "library_patents" -> p.patents(withOp(args, "pool"));
            case "library_patent_abstracts" -> p.patents(withOp(args, "abstracts"));
            case "library_patent_claims" -> p.patents(withOp(args, "claims"));
            case "library_records" -> p.home(withOp(args, "records"));
            case "library_board" -> p.career(withOp(args, "board"));
            case "library_job" -> p.job(args);
            case "library_map" -> p.map(args);
            case "library_perspectives" -> p.perspectives(args);
            case "library_explain" -> p.explain(args);
            case "library_sharpen" -> p.sharpen(args);
            default -> throw new RpcError(-32601, "unknown tool: " + name);
        }, true);
    }

    private interface LibraryCall { ObjectNode apply(LibraryProtocol p) throws Exception; }

    private static JsonNode library(LibraryCall call) { return library(call, false); }

    /** JSON result as structuredContent, and the same JSON as the text block — the MCP convention. */
    private static JsonNode library(LibraryCall call, boolean asToolResult) {
        try {
            ObjectNode out = call.apply(BOUND.get() != null ? new LibraryProtocol(BOUND.get()) : LibraryProtocol.open());
            if (!asToolResult) return out;
            ObjectNode content = M.createObjectNode();
            content.put("type", "text");
            content.put("text", M.writeValueAsString(out));
            ObjectNode r = M.createObjectNode();
            r.set("content", M.createArrayNode().add(content));
            r.set("structuredContent", out);
            r.put("isError", false);
            return r;
        } catch (ProtocolError e) {
            throw new RpcError(e.rpc, e.getMessage(), e.code, e.extra);
        } catch (RpcError e) {
            throw e;
        } catch (Declined d) {
            ProtocolError e = ProtocolError.declined(d.statement());   // the model declined: its own code, the statement, never "the library could not answer"
            throw new RpcError(e.rpc, e.getMessage(), e.code);
        } catch (Exception e) {
            throw new RpcError(-32002, "The library could not answer: " + e.getMessage(), "unavailable");
        }
    }

    // ---- schema builders ----

    private static ObjectNode patronProp() {
        ObjectNode spec = M.createObjectNode();
        spec.put("type", "object");
        spec.put("description", "Optional: who is asking — {did, name, runtime}. Anonymous when absent.");
        ObjectNode props = M.createObjectNode();
        props.setAll(prop("did", "string", "The patron's decentralized identifier."));
        props.setAll(prop("name", "string", "Human name."));
        props.setAll(prop("runtime", "string", "e.g. wyrdsekai."));
        spec.set("properties", props);
        ObjectNode wrap = M.createObjectNode();
        wrap.set("patron", spec);
        return wrap;
    }

    /**
     * {@code allow}: what the caller lets into this run that the library leaves out by default, for this run only. The person's to give:
     * the chat never offers it to its model, and strips it from any call its model makes.
     */
    private static ObjectNode localeProp() {
        return prop("locale", "string", "Optional: the person's language and country as a language tag, such as en-US, es-MX or ja-JP. When a "
                + "question reads as a person asking about harming themselves, where to find help comes in that language (English, Spanish or "
                + "Japanese; English otherwise) with that country's services first, in the message and as data (error.data.helplines, or "
                + "help.helplines beside a run).");
    }

    private static ObjectNode allowProp() {
        return arrayProp("allow", "Optional: what to let into this run that the library leaves out by default, for this run only: \"explicit\" "
                + "(pornography, and gore) and \"howto\" (step-by-step instructions for making a weapon or an explosive, "
                + "making an illegal drug, or running working exploit code against a system). Without it a question that may need such material is researched with it left out, and the result carries "
                + "content_suggestion {allow, why, how}: show it to the person and ask them, and send allow only if the person says yes. \"self-harm\" is the person's yes to researching a question that reads as them asking about harming themselves: "
                + "without it such a question starts nothing, and the call is answered with the error confirm, whose message is where to find help. "
                + "Show that message to the person and ask them; send \"self-harm\" only if the person says yes.");
    }

    private static ObjectNode arrayProp(String name, String description) {
        ObjectNode spec = M.createObjectNode();
        spec.put("type", "array");
        spec.putObject("items").put("type", "string");   // gpt-oss's chat template reads items.type and fails the whole request without it (2026-09-14)
        spec.put("description", description);
        ObjectNode wrap = M.createObjectNode();
        wrap.set(name, spec);
        return wrap;
    }

    /** The tool's arguments with the protocol operation the tool stands for. */
    private static ObjectNode withOp(JsonNode args, String op) {
        ObjectNode a = args != null && args.isObject() ? ((ObjectNode) args).deepCopy() : M.createObjectNode();
        a.put("op", op);
        return a;
    }

    private static ObjectNode tool(String name, String desc, ObjectNode inputSchema) {
        ObjectNode t = M.createObjectNode();
        t.put("name", name);
        t.put("description", desc);
        t.set("inputSchema", inputSchema);
        return t;
    }

    private static ObjectNode schema(String[] required, ObjectNode... properties) {
        ObjectNode s = M.createObjectNode();
        s.put("type", "object");
        ObjectNode props = M.createObjectNode();
        for (ObjectNode p : properties) props.setAll(p);
        s.set("properties", props);
        ArrayNode req = M.createArrayNode();
        for (String r : required) req.add(r);
        s.set("required", req);
        return s;
    }

    /**
     * A string field with a fixed set of values, declared as a JSON-schema {@code enum}. The description still says what each value
     * means; the enum says which values exist. A description is advice to a model, an enum is a rule: a model called through a
     * grammar cannot write a value outside it, and a client that checks the schema refuses one before the call is sent. On 2026-10-01
     * a companion model filled {@code sources} with "community forums, bug trackers, news articles" and got a refusal in place of a
     * research run.
     */
    private static ObjectNode enumProp(String name, String description, String... values) {
        ObjectNode wrap = prop(name, "string", description);
        ArrayNode allowed = ((ObjectNode) wrap.get(name)).putArray("enum");
        for (String v : values) allowed.add(v);
        return wrap;
    }

    private static ObjectNode prop(String name, String type, String description) {
        ObjectNode spec = M.createObjectNode();
        spec.put("type", type);
        spec.put("description", description);
        ObjectNode wrap = M.createObjectNode();
        wrap.set(name, spec);
        return wrap;
    }

    private static ObjectNode rpcError(int code, String message) { return rpcError(code, message, null); }

    private static ObjectNode rpcError(int code, String message, String dataCode) { return rpcError(code, message, dataCode, null); }

    /** {@code extra}: fields beside the code in {@code data}, such as the helplines of a {@code confirm}. */
    private static ObjectNode rpcError(int code, String message, String dataCode, ObjectNode extra) {
        ObjectNode e = M.createObjectNode();
        e.put("code", code);
        e.put("message", message);
        if (dataCode != null || extra != null) {
            ObjectNode d = e.putObject("data");
            if (dataCode != null) d.put("code", dataCode);
            if (extra != null) d.setAll(extra.deepCopy());
        }
        return e;
    }

    static final class RpcError extends RuntimeException {
        final int code;
        final String data;
        final ObjectNode extra;
        RpcError(int code, String msg) { this(code, msg, null); }
        RpcError(int code, String msg, String data) { this(code, msg, data, null); }
        RpcError(int code, String msg, String data, ObjectNode extra) { super(msg); this.code = code; this.data = data; this.extra = extra; }
    }
}
