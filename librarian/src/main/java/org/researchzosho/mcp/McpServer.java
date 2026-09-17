package org.researchzosho.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.LibraryProtocol;
import org.researchzosho.librarian.ProtocolError;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The Librarian over MCP (JSON-RPC 2.0): the library protocol's fourteen tools and its three resource
 * schemes, over stdio ({@code researchzosho mcp}) and, through {@link #envelopeFor}, the daemon's
 * {@code /rpc}. The contract is {@code docs/LIBRARY_PROTOCOL.md}; every tool takes an optional patron and
 * every result carries library_id / library_name / contract. Protocol errors become JSON-RPC errors with
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
    public static final String SERVER_VERSION = org.researchzosho.Version.string();

    private McpServer() { }

    /** Only tools whose name passes this are listed or callable; null = all. */
    private static volatile java.util.function.Predicate<String> toolFilter = null;

    /** Restrict every entry point (stdio and the daemon's /rpc) to the tools {@code filter} admits. */
    public static void setToolFilter(java.util.function.Predicate<String> filter) { toolFilter = filter; }

    /** Serve MCP over stdio with only the tools {@code filter} admits. */
    public static void serveStdio(java.util.function.Predicate<String> filter) throws Exception {
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
    private static final ThreadLocal<org.researchzosho.librarian.LibraryStore> BOUND = new ThreadLocal<>();

    /**
     * The envelope for a request against {@code store}: the daemon's door. Without this the tool call
     * opened whichever library the config named, which is the daemon's own only by coincidence of config
     * (found by a test that started a daemon on a temp library and read the wrong circulation log).
     */
    public static ObjectNode envelopeFor(JsonNode req, org.researchzosho.librarian.LibraryStore store) {
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
            env.set("error", rpcError(e.code, e.getMessage(), e.data));
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
                return library(p -> p.resourcesList(params.path("cursor").asText(null)));
            case "resources/templates/list":
                return library(p -> p.resourceTemplates());
            case "resources/read":
                return library(p -> p.resourcesRead(params.path("uri").asText("")));
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
                        prop("claim_type", "string", "extraction | synthesis | interpretation | speculation (default synthesis)."),
                        arrayProp("sources", "Where it comes from: URLs, edition citations, or {locator, edition, why} objects."),
                        prop("confidence", "string", "low | medium | high (default medium)."),
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
                        prop("mode", "string", "keep | link | survey (default keep)."),
                        prop("register", "boolean", "For a folder: the housekeeping rescans it for new and changed files."),
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
                        prop("op", "string", "survey (default), pick, or do."),
                        prop("path", "string", "A folder or a file on this machine (absolute path). Keeper only."),
                        prop("url", "string", "A url: a git repository, a paper, a page, or a GitHub issues page."),
                        prop("kind", "string", "repo, paper, site or issues; detected from the thing when not given."),
                        prop("name", "string", "The name the survey gave the thing, for pick and do."),
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
                        prop("as", "string", "frontier | runs | none (default frontier)."),
                        prop("title", "string", "A name for the list (default: the file name)."),
                        prop("column", "string", "For a CSV: the column that holds the items (default: a column named title, name, item or book, else the first)."),
                        prop("collection", "string", "Where the list is shelved (default lists)."),
                        prop("limit", "integer", "How many of the selected items to look at, file or run in this call (default 200, the most). The whole list is shelved regardless."),
                        prop("match", "string", "Only items whose line holds this text (a tag, an author, a year); an array for several, all must hold."),
                        prop("sample", "integer", "Of the matched items, this many at random."),
                        patronProp())));
        tools.add(tool("library_holdings",
                "What the person's own lists hold: a Calibre library, an inventory, a reading list — every list shelved through "
                + "library_items. An exact lookup: every word of the query must appear in an entry (title words, an author, a year). "
                + "Ask it for each candidate to tell owned from not owned; a search snippet is a guess, this is not.",
                schema(new String[]{"query"},
                        prop("query", "string", "Words of a title, an author, a year: \"wizard earthsea le guin\"."),
                        prop("limit", "integer", "Matches to return (default 20, up to 100)."),
                        patronProp())));
        tools.add(tool("library_remove",
                "Take a report or a claim out of the library for good. id is a report (I-…) or a claim (F-…). For a report, what=all "
                + "(default) removes it and the claims that are its alone, what=report the report only, what=claims its claims only; a "
                + "claim another report also cites stays and is named. dry=true shows the plan and removes nothing. Files are deleted, "
                + "the index and the changes log updated; what the run fetched from the web stays. There is no undo — retire is the "
                + "gentler thing for a claim.",
                schema(new String[]{"id"},
                        prop("id", "string", "A report id (I-…) or a claim id (F-…)."),
                        prop("what", "string", "For a report: all (default), report, or claims."),
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
                        prop("as", "string", "frontier | runs (default frontier)."),
                        prop("title", "string", "A name for the list."),
                        prop("limit", "integer", "How many questions to take (default all, up to 200)."),
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
                        patronProp())));
        tools.add(tool("library_bridges",
                "Discovery by combination: two areas of the library that no source read together, joined by specific terms both "
                + "areas' claims share (Swanson's fish oil and Raynaud's). op=run computes the candidate pairs from an area (or the "
                + "hottest ones) and files up to a few proposals as open questions of type bridge; dry=true only shows the pairs. "
                + "op=list the open proposals; op=accept files the research run that tests one (its verified claims are the test); "
                + "op=dismiss drops it; op=settings reads or sets an area's dials (sources, reach, strict, toward, away, since, per_night); "
                + "op=measure reads the ledger: proposed, kept, dismissed, corroborated.",
                schema(new String[]{},
                        prop("op", "string", "run | list | accept | dismiss | settings | measure | distance (default list)."),
                        prop("area", "string", "For run and settings: the subject to start from (default: the hottest areas); for distance: one of the two areas."),
                        prop("other", "string", "For distance: the other area — returns hops on the map, shared terms, paths, sources naming both, and the nearest concepts by embedding with their cosine."),
                        prop("question", "string", "For accept and dismiss: the proposal, as listed."),
                        prop("sources", "string", "For run and settings: library, peers, web — comma-separated (default library). With web, a pair the shelves do not join is taken outside: the model names what could bear on both, and a web search has to carry each candidate together with EACH area before it is proposed."),
                        prop("reach", "string", "For run and settings: low (neighbours) | medium (a few hops, a tenth random) | high (any distance, a third random)."),
                        prop("strict", "boolean", "For run and settings: three shared terms and no source naming both (true, default), or one term and no claim joining them (false)."),
                        prop("toward", "string", "For run and settings: measure distance toward this area instead of outward."),
                        prop("away", "string", "For run and settings: words that rule an area out, comma-separated."),
                        prop("since", "string", "For run and settings: only areas with a finding dated on or after this (YYYY-MM-DD)."),
                        prop("via", "string", "For run and settings: terms (words both areas' claims are about) | graph (paths on the map through concepts in between) | both (default)."),
                        prop("propose", "integer", "For run: proposals to file this time (default the area's per_night, 3)."),
                        prop("per_night", "integer", "For settings: proposals a night for the crew (0 turns it off)."),
                        prop("dry", "boolean", "For run: show the pairs and questions, file nothing."),
                        patronProp())));
        tools.add(tool("library_frontier",
                "The queue of open questions the housekeeping's explorer researches a few of each night — op=list returns them in queue order with type, parked, position, tonight, "
                + "the report that left each (report, report_title, report_fate: kept | waiting | disputed | retired | none), perspective, subjects, language, and similar (the head of a group that reads alike); "
                + "list takes filters: type, show (queued | parked | all, default all), report (an id or its prefix), fate, who (perspective text), subject, language, q (words); hints=true adds answered {id, title, state} where a claim on the shelves already answers a question. "
                + "op=add queues one attributed to the patron; op=next moves one to the head; op=later to the tail; op=park keeps one out of the explorer's reach; op=unpark returns it; "
                + "op=drop closes one without researching it; op=tidy removes duplicate lines (write access). A report's leftover questions are filed parked.",
                schema(new String[]{},
                        prop("op", "string", "list (default) | add | next | later | park | unpark | drop | tidy."),
                        prop("question", "string", "For add, next, later, park, unpark, drop: the question, exactly as listed."),
                        prop("type", "string", "For list: report | asked | person | dispute | check."),
                        prop("show", "string", "For list: queued | parked | all (default all)."),
                        prop("report", "string", "For list: only questions left by this investigation (an id, or its prefix such as I-0016)."),
                        prop("fate", "string", "For list: kept | waiting | disputed | retired | none — what became of the report that left the question."),
                        prop("who", "string", "For list: the perspective the question was asked from, matched as text."),
                        prop("subject", "string", "For list: a subject slug."),
                        prop("language", "string", "For list: english, japanese, … — the language a question is in or asks for."),
                        prop("q", "string", "For list: words that must all appear in the question."),
                        prop("hints", "boolean", "For list: look each question up on the shelves and add answered when a claim already answers it."),
                        patronProp())));
        tools.add(tool("library_inbox",
                "The claims waiting for the keeper's decision — drafts, and accepted claims whose review went stale — and the decisions. op=list returns items[] {id, title, state, stale, kind, tier, confidence, writer, date, subjects, language, sources, report, report_title}, "
                + "oldest first, with filters report (an investigation id or its prefix), subject, kind (extraction | synthesis | interpretation | speculation), tier, confidence, writer, state (draft | stale), language, q (words in the title). "
                + "op=accept puts claims into every answer from now on; op=dispute files why; op=retire keeps them on disk and out of every answer — each takes ids[] (or id), or report for every waiting claim of one investigation (write access).",
                schema(new String[]{},
                        prop("op", "string", "list (default) | accept | dispute | retire."),
                        arrayProp("ids", "For accept, dispute, retire: the claim ids."),
                        prop("id", "string", "One claim id, instead of ids."),
                        prop("report", "string", "For list: only claims from this investigation; for a decision: every waiting claim of it."),
                        prop("why", "string", "For dispute: the reason (required)."),
                        prop("subject", "string", "For list: a subject slug."),
                        prop("kind", "string", "For list: the claim type — extraction | synthesis | interpretation | speculation."),
                        prop("tier", "string", "For list: the strongest source's tier."),
                        prop("confidence", "string", "For list: low | medium | high."),
                        prop("writer", "string", "For list: who wrote the claim, matched as text (crew:explorer, person, …)."),
                        prop("state", "string", "For list: draft | stale."),
                        prop("language", "string", "For list: the language of the claim's title."),
                        prop("q", "string", "For list: words that must all appear in the title."),
                        patronProp())));
        tools.add(tool("library_serials",
                "The searches the housekeeping keeps running on a cadence and reports what is new from — op=list (each with parked and due); op=add {name, query, every_days} keeps one; "
                + "op=every {name, every_days} changes its cadence; op=park {name} keeps it without running it, op=unpark {name} puts it back in the rotation; op=remove {name} stops keeping it (write access).",
                schema(new String[]{},
                        prop("op", "string", "list (default) | add | every | park | unpark | remove."),
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
                "What became of an access request: pending, denied (with the reason), or approved, in which case the token, once, when the claim secret is right.",
                schema(new String[]{"request_id", "claim"},
                        prop("request_id", "string", "The id library_request_access returned."),
                        prop("claim", "string", "The claim secret it returned."),
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
                + "Takes minutes to hours — do not wait inline.",
                schema(new String[]{"question"},
                        prop("question", "string", "The research question, as you would put it to a librarian."),
                        prop("mode", "string", "broad (survey the landscape, default) | depth (deep-read a narrow question)."),
                        prop("max_turns", "integer", "Optional ceiling: the most model turns the WHOLE run may spend (workers, critic, synthesis and cite-check together). Absent or 0 = no ceiling, the run goes until the work is done."),
                        prop("max_minutes", "integer", "Optional ceiling: the most wall-clock minutes the run may take ('two hours tops' = 120). The workers stop early enough for the write-up to fit. Absent or 0 = no ceiling."),
                        arrayProp("sub_questions", "Optional plan: up to 8 self-contained sub-questions, each researched by its own worker instead of a decompose step."),
                        prop("sources", "string", "both (the shelves first, then the web — default) | shelves (the person's own corpus only, no web) | web."),
                        arrayProp("collections", "Optional: names of the person's collections (folders shelved with `researchzosho add <dir>`) to search."),
                        prop("quick", "boolean", "Look it up now: the front of the line and short ceilings (12 turns, 6 minutes) unless the ask names its own."),
                        patronProp())));
        tools.add(tool("library_job",
                "One job from the ledger ({job}: state queued|running|done|failed|stopped, elapsed_s, result when finished), or "
                + "your jobs (active[], finished[], paused) when job_id is omitted. op=stop {job_id} stops one run: queued, it never starts; running, it ends at its next turn. "
                + "op=pause holds the runner (queued runs wait, a running one holds at its next turn); op=resume lets it go (write access).",
                schema(new String[]{},
                        prop("op", "string", "read (default) | stop | pause | resume."),
                        prop("job_id", "string", "The job to read or stop, e.g. J-0007."),
                        patronProp())));
        tools.add(tool("library_status",
                "library_id, library_name, contract, counts by kind and state, last_updated.",
                schema(new String[]{}, patronProp())));
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
                        prop("rung", "string", "beginner (default) | familiar | written."),
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

    private static JsonNode toolsCall(JsonNode params) {
        String name = params.path("name").asText("");
        if (toolFilter != null && !toolFilter.test(name)) throw new RpcError(-32601, "unknown tool: " + name);
        JsonNode args = params.path("arguments");
        // "person" is the keeper at the command line and the chat — in-process callers. An MCP client
        // is a patron and names itself by its did; letting it say "person" skipped the allow list
        // (found by Wyrdsekai, 2026-09-07).
        if ("person".equals(args.path("patron").path("did").asText(""))) {
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
            case "library_absorb" -> p.absorb(args);
            case "library_survey" -> p.survey(args);
            case "library_repo" -> p.repo(args);
            case "library_items" -> p.items(args);
            case "library_holdings" -> p.holdings(args);
            case "library_remove" -> p.remove(args);
            case "library_check" -> p.check(args);
            case "library_reading" -> p.reading(args);
            case "library_questions" -> p.questions(args);
            case "library_bookmarks" -> p.bookmarks(args);
            case "library_meeting" -> p.meeting(args);
            case "library_bridges" -> p.bridges(args);
            case "library_frontier" -> p.frontier(args);
            case "library_inbox" -> p.inbox(args);
            case "library_serials" -> p.serials(args);
            case "library_subjects" -> p.subjects(args);
            case "library_status" -> p.status(args);
            case "library_changes" -> p.changes(args);
            case "library_request_access" -> p.requestAccess(args);
            case "library_access" -> p.access(args);
            case "library_subscribe" -> p.subscribe(args);
            case "library_unsubscribe" -> p.unsubscribe(args);
            case "library_research" -> p.research(args);
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
            throw new RpcError(e.rpc, e.getMessage(), e.code);
        } catch (RpcError e) {
            throw e;
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

    private static ObjectNode arrayProp(String name, String description) {
        ObjectNode spec = M.createObjectNode();
        spec.put("type", "array");
        spec.putObject("items").put("type", "string");   // gpt-oss's chat template reads items.type and fails the whole request without it (2026-09-14)
        spec.put("description", description);
        ObjectNode wrap = M.createObjectNode();
        wrap.set(name, spec);
        return wrap;
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

    private static ObjectNode prop(String name, String type, String description) {
        ObjectNode spec = M.createObjectNode();
        spec.put("type", type);
        spec.put("description", description);
        ObjectNode wrap = M.createObjectNode();
        wrap.set(name, spec);
        return wrap;
    }

    private static ObjectNode rpcError(int code, String message) { return rpcError(code, message, null); }

    private static ObjectNode rpcError(int code, String message, String dataCode) {
        ObjectNode e = M.createObjectNode();
        e.put("code", code);
        e.put("message", message);
        if (dataCode != null) e.putObject("data").put("code", dataCode);
        return e;
    }

    static final class RpcError extends RuntimeException {
        final int code;
        final String data;
        RpcError(int code, String msg) { this(code, msg, null); }
        RpcError(int code, String msg, String data) { super(msg); this.code = code; this.data = data; }
    }
}
