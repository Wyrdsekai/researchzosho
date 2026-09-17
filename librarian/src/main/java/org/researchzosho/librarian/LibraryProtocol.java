package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The library protocol, contract 1.0 (docs/LIBRARY_PROTOCOL.md) — what a patron runtime speaks
 * to The Librarian. Transport-free: JSON in, JSON out, {@link ProtocolError} for the stable error
 * codes. {@code McpServer} wraps these as tools and resources; a later http/sse transport, or a
 * zone-to-zone federation, wraps the same class.
 *
 * <p>Every result carries the library's provenance ({@code library_id}, {@code library_name},
 * {@code contract}) and every entry is self-describing (id, kind, state, claim_type, confidence,
 * writer, recorded_at, sources[] with locator and edition) — a patron cites "F-0412 in lib_…"
 * months from now and must be able to say what it was.
 */
public final class LibraryProtocol {

    public static final String CONTRACT = "1.0";   // the wire protocol; it moves only when something on the wire changes shape
    private static final ObjectMapper M = new ObjectMapper();
    private static final int PAGE = 200;

    private final LibraryStore store;

    public LibraryProtocol(LibraryStore store) { this.store = store; }

    /** The configured library, or {@code unavailable} when the person has not created one. */
    public static LibraryProtocol open() {
        if (!Acquisitions.libraryExists()) {
            throw ProtocolError.unavailable("This machine has no library yet; the person creates one with `researchzosho init`.");
        }
        return new LibraryProtocol(LibraryStore.open());
    }

    public LibraryStore store() { return store; }

    // ---- envelope ----

    ObjectNode envelope() throws IOException {
        var id = store.identity();
        ObjectNode r = M.createObjectNode();
        r.put("library_id", id.id());
        r.put("library_name", id.name());
        r.put("contract", CONTRACT);
        return r;
    }

    // ---- tools ----

    /**
     * library_ask: the answer package. {@code holds_nothing} with NO entries when there is nothing.
     *
     * <p>INTENT-ROUTED first (kiroku-memory's take, 2026-09-05), deterministically: an entry id →
     * that entry; a URL → the captured document; "what changed since …" → the changes feed; a
     * question that names a subject or facet → that shelf; anything else → the floored hybrid
     * search. {@code routed} says which.
     */
    public ObjectNode ask(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String question = reqStr(args, "question");
        int k = args.path("k").asInt(6);
        ObjectNode r = envelope();
        r.put("question", question);
        ArrayNode entries = r.putArray("entries");
        Route route = route(question);
        r.put("routed", route.kind());
        switch (route.kind()) {
            case "id" -> {
                ObjectNode e = entry(route.arg(), kindOf(route.arg()), true);
                if (e != null && !"retired".equals(e.path("state").asText())) entries.add(e);
            }
            case "locator" -> {
                Path p = rawFor(route.arg());
                if (p != null) { ObjectNode e = entry(p.getFileName().toString(), "raw", true); if (e != null) entries.add(e); }
            }
            case "changes" -> {
                ArrayNode ch = r.putArray("changes");
                for (var c : Changes.tail(store, 500)) {
                    if (c.at().compareTo(route.arg()) < 0) continue;
                    ObjectNode o = ch.addObject();
                    o.put("seq", c.seq()); o.put("at", c.at()); o.put("kind", c.kind()); o.put("id", c.id()); o.put("event", c.event()); o.put("detail", c.detail());
                    if ("finding".equals(c.kind()) && entries.size() < k) {
                        boolean have = false;
                        for (JsonNode e : entries) if (e.path("id").asText().equals(c.id())) have = true;
                        if (!have) { ObjectNode e = entry(c.id(), "finding", true); if (e != null) entries.add(e); }
                    }
                }
                r.put("since", route.arg());
            }
            case "subject" -> {
                for (var h : new LibrarianIndex(store).search(route.arg().replace("--", " "), k, route.arg(), null)) {
                    ObjectNode e = entry(h.id(), h.kind(), true);
                    if (e == null || "retired".equals(e.path("state").asText())) continue;
                    e.put("score", h.score());
                    entries.add(e);
                }
                r.put("subject", route.arg());
            }
            default -> {
                for (var h : new LibrarianIndex(store).searchStrict(question, k, null, null)) {   // floored: absence is an answer
                    ObjectNode e = entry(h.id(), h.kind(), true);
                    if (e == null) continue;
                    if ("retired".equals(e.path("state").asText())) continue;
                    e.put("score", h.score());
                    entries.add(e);
                }
            }
        }
        r.put("holds_nothing", entries.isEmpty() && !route.kind().equals("changes"));
        Heat.used(store, ids(entries));
        if (entries.isEmpty() && route.kind().equals("search")) {
            // DEMAND: an unanswered QUESTION is what to acquire next — file it (deduplicated) for the explorer.
            // An id, a URL or a changes query that finds nothing is not demand.
            boolean filed = Frontier.demand(store, question, patron.writer());
            r.put("filed_as_demand", filed);
            store.circulate("holds-nothing", patron.label() + " :: " + Acquisitions.compress(question, 160));
        }
        ArrayNode threads = r.putArray("open_threads");
        for (var t : frontierList()) {
            if (touches(t.get("text").asText(), question)) threads.add(t);
        }
        r.put("rendered", LibraryPush.answerPackage(store, question, k));
        store.circulate("desk", patron.label() + " :: " + Acquisitions.compress(question, 120));
        // other libraries: the group the ask names, or the default group when this library holds nothing
        // and the ask did not say "none". One hop: a peer is asked with peers=none.
        String which = args.path("peers").asText("");
        if (which.isEmpty() && entries.isEmpty() && route.kind().equals("search")) which = Peers.defaultGroup();
        if (!which.isEmpty() && !which.equals("none")) {
            List<Peers.Peer> peers = Peers.select(store, which);
            ArrayNode answers = Peers.ask(peers, question, k);
            r.set("peers", answers);
            if (answers.size() > 0) store.circulate("peers", patron.label() + " :: " + which + " (" + answers.size() + ") :: " + Acquisitions.compress(question, 100));
        }
        return r;
    }

    /** library_search: hits with a cursor. */
    public ObjectNode search(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String query = reqStr(args, "query");
        int k = args.path("k").asInt(args.path("limit").asInt(10));
        int offset = cursor(args);
        String subject = args.hasNonNull("subject") ? args.get("subject").asText() : null;
        var all = new LibrarianIndex(store).search(query, offset + k + 1, subject);
        ObjectNode r = envelope();
        ArrayNode hits = r.putArray("hits");
        int end = Math.min(all.size(), offset + k);
        for (int i = offset; i < end; i++) {
            var h = all.get(i);
            ObjectNode o = hits.addObject();
            o.put("id", h.id());
            o.put("kind", h.kind());
            o.put("title", h.title());
            o.put("snippet", h.snippet() == null ? "" : h.snippet());
            o.put("score", h.score());
            o.put("state", h.state());
            ArrayNode subs = o.putArray("subjects");
            for (String s : subjectsOf(h.id(), h.kind())) subs.add(s);
        }
        if (all.size() > offset + k) r.put("next_cursor", Integer.toString(offset + k));
        else r.putNull("next_cursor");
        Heat.used(store, ids(hits));
        store.circulate("search", patron.label() + " :: " + Acquisitions.compress(query, 120));
        return r;
    }

    /** library_get: one entry in full, or {@code not_found}. */
    public ObjectNode get(JsonNode args) throws IOException {
        Patrons.check(store, Patrons.Patron.from(args), Patrons.Level.read);
        String id = reqStr(args, "id");
        if (!LibraryStore.safeName(id)) throw ProtocolError.invalidArgs("An id is a shelf name (F-…, I-…, A-…, or a raw file name), not a path.");
        String section = args.path("section").asText("").strip();
        int offset = args.path("offset").asInt(0);
        int max = args.path("max_chars").asInt(0);
        if (offset < 0 || max < 0) throw ProtocolError.invalidArgs("offset and max_chars are zero or positive numbers.");
        ObjectNode r = envelope();
        ObjectNode e = entry(id, kindOf(id), true);
        if (e == null) throw ProtocolError.notFound("id " + id);
        // A record can run past what a tool call may carry (an investigation of 150,000 characters, measured 2026-09-11):
        // a caller asks for one section, or a window of the body, and gets the whole list of sections either way.
        String body = e.path("body").asText("");
        if (!section.isEmpty()) {
            String cut = sectionOf(body, section);
            if (cut == null) throw ProtocolError.invalidArgs("No section \"" + section + "\" in " + id + "; the sections are: "
                    + String.join(", ", sectionHeadings(body)) + (sectionHeadings(body).isEmpty() ? "(none: the body has no headings)" : "") + ". \"answer\" is the write-up without the harness's own sections.");
            body = cut;
            e.put("section", section);
        }
        e.put("chars", body.length());
        if (offset > 0 || max > 0) {
            int from = Math.min(offset, body.length());
            int to = max > 0 ? Math.min(body.length(), from + max) : body.length();
            e.put("offset", from);
            e.put("truncated", to < body.length());
            body = body.substring(from, to);
        }
        e.put("body", body);
        r.set("entry", e);
        return r;
    }

    /** The sections the harness itself appends to a write-up; "answer" is everything else. */
    static final java.util.Set<String> HARNESS_SECTIONS = java.util.Set.of("question", "sources", "caveats", "cite-check", "coverage check", "evidence",
            "references", "languages of the sources", "web search", "source requests", "worker findings", "sources cited", "answer (as submitted");

    /** The "## " headings of a body, in order. */
    static List<String> sectionHeadings(String body) {
        List<String> out = new ArrayList<>();
        for (String line : body.split("\n")) if (line.startsWith("## ")) out.add(line.substring(3).strip());
        return out;
    }

    /**
     * One section of a body by heading — exact, then by prefix, then by a word it contains, case-insensitively — or,
     * for {@code answer}, every section that is the write-up's own; null when nothing matches.
     */
    static String sectionOf(String body, String name) {
        String want = name.toLowerCase(Locale.ROOT).strip();
        String[] parts = body.split("(?m)^(?=## )");
        if (want.equals("answer") || want.equals("synthesis")) {
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (!part.startsWith("## ")) continue;
                String h = headingOf(part);
                boolean harness = false;
                for (String x : HARNESS_SECTIONS) if (h.equals(x) || h.startsWith(x + " ") || h.startsWith(x + " (") || (x.endsWith("(") && h.startsWith(x))) { harness = true; break; }
                if (!harness) sb.append(part.strip()).append("\n\n");
            }
            return sb.length() == 0 ? null : sb.toString().strip();
        }
        if (want.equals("workers")) want = "worker findings";
        for (String part : parts) if (part.startsWith("## ") && headingOf(part).equals(want)) return part.strip();
        for (String part : parts) if (part.startsWith("## ") && headingOf(part).startsWith(want)) return part.strip();
        for (String part : parts) if (part.startsWith("## ") && headingOf(part).contains(want)) return part.strip();
        return null;
    }

    private static String headingOf(String part) {
        int nl = part.indexOf('\n');
        return (nl < 0 ? part : part.substring(0, nl)).substring(3).strip().toLowerCase(Locale.ROOT);
    }

    /** library_read: the captured raw text behind a source locator — the verbatim path. */
    public ObjectNode read(JsonNode args) throws IOException {
        Patrons.check(store, Patrons.Patron.from(args), Patrons.Level.read);
        String locator = reqStr(args, "locator");
        int max = args.path("max_chars").asInt(20_000);
        if (max <= 0) throw ProtocolError.invalidArgs("max_chars must be a positive number.");
        Path p = rawFor(locator);
        if (p == null) throw ProtocolError.notFound("a captured document for " + locator);
        String[] raw = RawCapture.read(p);
        ObjectNode r = envelope();
        r.put("locator", raw[0].isEmpty() ? locator : raw[0]);
        r.put("raw_id", p.getFileName().toString());
        r.put("title", raw[1]);
        String captured = capturedAt(p);
        r.put("captured_at", captured);
        r.putNull("edition");   // a capture date is not an edition; none is known for a fetched page
        String text = raw[2];
        r.put("chars", text.length());
        r.put("truncated", text.length() > max);
        r.put("text", text.length() > max ? text.substring(0, max) : text);
        return r;
    }

    /**
     * library_established: what the shelves hold on a claim, and a verdict computed from their
     * STATES — no model composes it. Any disputed entry bearing on the claim → disputed; an
     * accepted one → established; nothing bearing on it → not_established.
     */
    public ObjectNode established(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String claim = reqStr(args, "claim");
        int k = args.path("k").asInt(8);
        ObjectNode r = envelope();
        r.put("claim", claim);
        ArrayNode accepted = r.putArray("accepted");
        ArrayNode disputes = r.putArray("disputes");
        ArrayNode unreviewed = r.putArray("unreviewed");   // held as drafts: not established, and not absent either
        // findings only: the verdict is about claims, and on a real shelf articles, investigations and
        // raw documents outrank the finding that states the very claim (measured 2026-09-03)
        for (var h : new LibrarianIndex(store).searchStrict(claim, k, null, "finding")) {
            Finding f = store.finding(h.id());
            if (f == null) continue;
            if (f.state() == Finding.State.accepted) accepted.add(entry(f, false));
            else if (f.state() == Finding.State.disputed) disputes.add(entry(f, false));
            else if (f.state() == Finding.State.draft) unreviewed.add(entry(f, false));
        }
        r.put("verdict", !disputes.isEmpty() ? "disputed" : !accepted.isEmpty() ? "established" : "not_established");
        store.circulate("established", patron.label() + " :: " + Acquisitions.compress(claim, 120));
        return r;
    }

    /**
     * library_add: read material into the library — a file, a folder (a mounted share is a folder), or a URL.
     * mode=keep copies the extracted text onto the shelves (the file itself is never copied); mode=link
     * reads the files where they are and keeps only a pointer and a hash; mode=survey counts the folder and
     * says what keeping it would take, and shelves nothing. Paths are the keeper's to give (the person at
     * the terminal or the pages); a URL any patron with write access may add. No model runs: the material is
     * shelved as it is, and a question put to it is a research run with sources=shelves.
     */
    public ObjectNode add(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String path = args.path("path").asText("").strip();
        String url = args.path("url").asText("").strip();
        if (path.isEmpty() && url.isEmpty()) throw ProtocolError.invalidArgs("Give a path (a file or a folder) or a url.");
        if (!path.isEmpty() && !url.isEmpty()) throw ProtocolError.invalidArgs("A path or a url, not both.");
        if (path.startsWith("http://") || path.startsWith("https://")) { url = path; path = ""; }
        String mode = args.path("mode").asText("keep").strip().toLowerCase(Locale.ROOT);
        if (!mode.equals("keep") && !mode.equals("link") && !mode.equals("survey")) throw ProtocolError.invalidArgs("mode must be keep, link or survey.");
        boolean register = args.path("register").asBoolean(false);
        String collection = args.path("collection").asText("").strip();
        ObjectNode r = envelope();
        if (!url.isEmpty()) {
            if (!mode.equals("keep")) throw ProtocolError.invalidArgs("A url is always kept: the web changes under a link. Use mode=keep.");
            Object[] got;
            try { got = Corpus.addUrl(store, url, collection); }
            catch (Exception e) { throw ProtocolError.unavailable("could not read " + url + ": " + e.getMessage()); }
            Path raw = (Path) got[0];
            if (raw == null) throw ProtocolError.unavailable("the page was read but not shelved (no library, or the capture was refused)");
            r.put("kind", "url"); r.put("mode", "keep"); r.put("url", url); r.put("title", String.valueOf(got[1])); r.put("format", String.valueOf(got[2]));
            r.put("raw", raw.getFileName().toString());
            if (!collection.isEmpty()) r.put("collection", collection);
            r.put("added", 1);
            store.circulate("add", patron.label() + " :: " + url);
            return r;
        }
        // paths are the keeper's: the terminal, or the pages while they are open (the same rule Patrons.check applies to a browser)
        if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Paths on this machine are the keeper's to give: add files and folders from the terminal or the library's own pages. A url can be added from here.");
        Path target = Path.of(path).toAbsolutePath().normalize();
        if (!Files.exists(target)) throw ProtocolError.notFound(target + " (is the drive mounted?)");
        if (Files.isDirectory(target)) {
            if (collection.isEmpty()) collection = Corpus.nameFor(target);
            r.put("kind", "folder"); r.put("path", target.toString()); r.put("collection", collection); r.put("mode", mode);
            if (mode.equals("survey")) {
                Corpus.Survey sv = Corpus.survey(store, target, true);
                r.put("files", sv.files()); r.put("bytes", sv.bytes()); r.put("text_bytes_if_kept", sv.textBytes()); r.put("disk_free_bytes", sv.freeBytes()); r.put("unreadable_sampled", sv.unreadable());
                ObjectNode bt = r.putObject("by_type"); sv.byType().forEach(bt::put);
                r.put("summary", sv.line());
                r.put("next", "add it with mode=keep (the text is copied onto the shelves) or mode=link (read in place, nothing copied)");
                return r;
            }
            Corpus.Outcome o = Corpus.addFolder(store, target, collection, true, mode.equals("link"));
            if (register) Corpus.register(store, collection, target, mode.equals("link"));
            r.put("seen", o.seen()); r.put("added", o.added()); r.put("unchanged", o.unchanged()); r.put("changed", o.changed()); r.put("skipped", o.skipped());
            r.put("registered", register);
            ArrayNode pr = r.putArray("problems"); for (String x : o.problems()) pr.add(x);
            r.put("summary", "collection " + collection + ": " + o.line() + (mode.equals("link") ? "; read in place, nothing copied" : "") + (register ? "; registered, the housekeeping rescans it" : ""));
            r.put("next", "research it with library_research {sources: \"shelves\", collections: [\"" + collection + "\"]}");
            store.circulate("add", patron.label() + " :: " + target + " (" + mode + ")");
            return r;
        }
        if (mode.equals("survey")) throw ProtocolError.invalidArgs("survey is for a folder; a single file is added with keep or link.");
        Path raw = Corpus.addFile(store, target, collection, mode.equals("link"));
        if (raw == null) throw ProtocolError.unavailable("no text could be read from " + target.getFileName());
        r.put("kind", "file"); r.put("path", target.toString()); r.put("mode", mode); r.put("raw", raw.getFileName().toString());
        r.put("title", RawCapture.read(raw)[1]);
        if (!collection.isEmpty()) r.put("collection", collection);
        r.put("added", 1);
        r.put("next", "ask about it with library_ask, or research it with library_research {sources: \"shelves\"}");
        store.circulate("add", patron.label() + " :: " + target + " (" + mode + ")");
        return r;
    }

    /**
     * library_survey: a thing the person has, read as a starting point — a code repository, a paper, a website
     * or product page, an issue tracker. op=survey (default) reads it (kind detected from the thing, or given),
     * shelves the text, files one draft claim (what it is, what it says, what it rests on) and offers numbered
     * directions on the open questions; nothing runs. op=pick with picks="1,3" files a research run per direction
     * named. op=do with question files the person's own direction and its run. pick and do take the thing by name.
     */
    public ObjectNode survey(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String op = args.path("op").asText("survey").strip().toLowerCase(Locale.ROOT);
        String path = args.path("path").asText("").strip(), url = args.path("url").asText("").strip(), name = args.path("name").asText("").strip();
        if (path.startsWith("http://") || path.startsWith("https://") || Repos.isUrl(path)) { url = path; path = ""; }
        if (!path.isEmpty() && !patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Paths on this machine are the keeper's to give: survey a file or a folder from the terminal or the library's own pages. A url works from here.");
        String spec = path.isEmpty() ? url : path;
        ObjectNode r = envelope();
        r.put("op", op);
        if (op.equals("survey")) {
            if (spec.isEmpty()) throw ProtocolError.invalidArgs("Give the thing to read: a path on this machine (a repository folder, a paper, an export), or a url (a repository, a paper, a page, a GitHub issues page).");
            Surveys.Kind kind;
            String kindGiven = args.path("kind").asText("").strip().toLowerCase(Locale.ROOT);
            try { kind = kindGiven.isEmpty() ? Surveys.detect(spec) : Surveys.Kind.valueOf(kindGiven); } catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs("kind is repo, paper, site or issues"); }
            Surveys.Read read;
            try { read = Surveys.read(store, kind, spec); } catch (IOException e) { throw ProtocolError.unavailable(e.getMessage()); }
            Surveys.Description d = Surveys.describe(read, Explain.drive());
            Surveys.Filed f = Surveys.file(store, read, d, patron.writer());
            r.put("kind", kind.name()); r.put("name", read.name()); r.put("origin", read.origin()); r.put("title", read.title());
            ObjectNode facts = r.putObject("facts"); read.facts().forEach(facts::put);
            r.put("what_it_is", d.whatItIs()); r.put("summary_text", d.summary());
            ArrayNode cl = r.putArray("claims"); d.claims().forEach(cl::add);
            ArrayNode ro = r.putArray("rests_on"); d.restsOn().forEach(ro::add);
            ArrayNode lo = r.putArray("leaves_open"); d.leavesOpen().forEach(lo::add);
            r.put("described_by", d.byModel() ? "model" : "mechanical");
            r.put("raw", f.raw()); r.put("claim_id", f.claimId());
            ArrayNode opts = r.putArray("options"); for (Surveys.Option o : f.options()) opts.addObject().put("n", o.n()).put("question", o.question());
            ArrayNode already = r.putArray("already_open"); f.alreadyOpen().forEach(already::add);
            String about = switch (kind) { case repo -> read.facts().getOrDefault("files", "?") + " files" + (read.facts().getOrDefault("languages", "").isEmpty() ? "" : "; " + read.facts().get("languages")); case paper, site -> read.facts().getOrDefault("characters", "?") + " characters"; case issues -> read.facts().getOrDefault("issues", "?") + " issues"; };
            r.put("summary", "read the " + Surveys.noun(kind) + " " + read.name() + " (" + about + "): the text is on the shelves (collection " + Surveys.collection(kind) + "), one draft claim says what it is (" + f.claimId() + "), " + f.options().size() + " direction(s) are offered on the open questions; nothing runs until one is picked");
            r.put("next", "op=pick with picks=\"1,3\" and name=\"" + read.name() + "\" files a research run per direction; op=do with question=\"…\" files your own");
            return r;
        }
        if (name.isEmpty() && !spec.isEmpty()) name = Repos.nameOf(spec);
        if (name.isEmpty()) throw ProtocolError.invalidArgs("Name the thing (name=…, as the survey called it) that was surveyed.");
        Finding claim = Surveys.claimFor(store, name);
        if (claim == null) throw ProtocolError.notFound("no survey named " + name + "; op=survey reads it first");
        Surveys.Kind kind = Surveys.kindOf(claim);
        String origin = claim.sources().isEmpty() ? name : claim.sources().get(0).locator();
        r.put("kind", kind.name()); r.put("name", name); r.put("origin", origin); r.put("claim_id", claim.id());
        ArrayNode runs = r.putArray("runs");
        if (op.equals("pick")) {
            String picks = args.path("picks").asText("").strip();
            if (picks.isEmpty()) throw ProtocolError.invalidArgs("Say which directions to run: picks=\"1,3\" as the survey numbered them.");
            List<Surveys.Option> options = Surveys.options(store, name);
            List<Integer> wanted = new ArrayList<>();
            for (String t : picks.split("[,\\s]+")) if (t.matches("\\d+")) wanted.add(Integer.parseInt(t));
            if (wanted.isEmpty()) throw ProtocolError.invalidArgs("picks must be numbers, like \"1,3\"");
            for (int n : wanted) {
                Surveys.Option o = options.stream().filter(x -> x.n() == n).findFirst().orElse(null);
                if (o == null) { runs.addObject().put("n", n).put("error", "no open direction numbered " + n + " for " + name); continue; }
                ObjectNode ask = M.createObjectNode();
                ask.put("question", Surveys.runQuestion(kind, name, origin, o.question())); ask.put("mode", "depth"); ask.put("sources", "both");
                ask.set("patron", args.path("patron").deepCopy());
                String job = research(ask).path("job_id").asText();
                Frontier.markExplored(store, o.question(), job);
                runs.addObject().put("n", n).put("question", o.question()).put("job_id", job);
            }
        } else if (op.equals("do")) {
            String q = args.path("question").asText("").strip();
            if (q.isEmpty()) throw ProtocolError.invalidArgs("Say what to research about " + name + ": question=\"…\"");
            store.frontier(Surveys.kind(name, patron.writer(), 0), q);
            ObjectNode ask = M.createObjectNode();
            ask.put("question", Surveys.runQuestion(kind, name, origin, q)); ask.put("mode", "depth"); ask.put("sources", "both");
            ask.set("patron", args.path("patron").deepCopy());
            String job = research(ask).path("job_id").asText();
            Frontier.markExplored(store, q, job);
            runs.addObject().put("question", q).put("job_id", job);
        } else throw ProtocolError.invalidArgs("op is survey, pick or do");
        int filed = 0; for (JsonNode j : runs) if (j.hasNonNull("job_id")) filed++;
        ArrayNode left = r.putArray("options"); for (Surveys.Option o : Surveys.options(store, name)) left.addObject().put("n", o.n()).put("question", o.question());
        r.put("summary", filed + " research run(s) filed about " + name + (left.size() > 0 ? "; " + left.size() + " direction(s) still open" : ""));
        r.put("next", "library_job follows each run; op=pick or op=do files more");
        store.circulate("survey", patron.label() + " :: " + name + " " + op + " — " + filed + " run(s)");
        return r;
    }

    /** library_repo: library_survey with kind=repo (the first kind built; kept as a name). */
    public ObjectNode repo(JsonNode args) throws IOException { ObjectNode a = args.deepCopy(); if (!a.hasNonNull("kind")) a.put("kind", "repo"); return survey(a); }

    /**
     * library_remove: a report or a claim out of the library for good. id is an investigation (I-…) or a claim
     * (F-…); what=all (default) takes a report and the claims that are its alone, what=report the report only,
     * what=claims its claims only. dry=true returns the plan and removes nothing. A claim another report cites
     * stays and is named. What the run fetched from the web stays on the shelves. Retiring is the gentler thing.
     */
    public ObjectNode remove(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String id = args.path("id").asText("").strip();
        if (id.isEmpty()) throw ProtocolError.invalidArgs("Name what to remove: id = a report (I-…) or a claim (F-…).");
        Removal.What what;
        try { what = Removal.What.valueOf(args.path("what").asText("all").strip().toLowerCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs("what is all, report or claims"); }
        boolean dry = args.path("dry").asBoolean(false);
        Removal.Plan plan;
        try { plan = Removal.plan(store, id, what); } catch (IOException e) { throw ProtocolError.notFound(e.getMessage()); }
        ObjectNode r = envelope();
        r.put("id", plan.id()); r.put("kind", plan.kind()); r.put("title", plan.title()); r.put("what", plan.what().name()); r.put("dry", dry);
        r.put("report_goes", plan.reportGoes());
        ArrayNode go = r.putArray("claims_go"); for (int i = 0; i < plan.claimsGo().size(); i++) go.addObject().put("id", plan.claimsGo().get(i)).put("title", plan.titles().get(i));
        ArrayNode stay = r.putArray("claims_stay"); plan.claimsStay().forEach(stay::add);
        r.put("plan", Removal.describe(plan));
        if (dry) { r.put("summary", "would remove " + plan.count() + " entr" + (plan.count() == 1 ? "y" : "ies") + (plan.claimsStay().isEmpty() ? "" : ", keeping " + plan.claimsStay().size() + " cited elsewhere") + "; nothing removed"); r.put("next", "the same call with dry=false removes them"); return r; }
        Removal.Done done = Removal.apply(store, plan, patron.writer());
        ArrayNode removed = r.putArray("removed"); done.removed().forEach(removed::add);
        r.put("summary", "removed " + done.removed().size() + " entr" + (done.removed().size() == 1 ? "y" : "ies") + (plan.claimsStay().isEmpty() ? "" : "; " + plan.claimsStay().size() + " claim(s) kept, cited by another report") + "; the changes log says so");
        r.put("next", "library_changes lists the removals; there is no undo");
        return r;
    }

    /**
     * library_holdings: what the person's own lists hold — a Calibre library, an inventory, a reading list, every list
     * shelved through items. An exact lookup: every word of the query must appear in the entry (title words, an
     * author, a year). Cheap enough to ask for each candidate, so "which of these do I own" is answered by the
     * shelves, not guessed.
     */
    public ObjectNode holdings(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String q = args.path("query").asText("").strip();
        if (q.isEmpty()) throw ProtocolError.invalidArgs("Give a query: words of a title, an author, a year.");
        int limit = Math.max(1, Math.min(100, args.path("limit").asInt(20)));
        List<Holdings.Match> found = Holdings.find(store, q, limit, null);
        ObjectNode r = envelope();
        r.put("query", q); r.put("lists_hold", Holdings.size(store));
        ArrayNode out = r.putArray("matches");
        for (Holdings.Match m : found) { ObjectNode n = out.addObject(); n.put("item", m.item()); if (!m.note().isEmpty()) n.put("note", m.note()); n.put("list", m.list()); n.put("raw", m.raw()); }
        r.put("count", found.size());
        r.put("summary", found.isEmpty() ? "the person's lists hold nothing matching \"" + q + "\" (" + Holdings.size(store) + " entries in all)" : found.size() + " entr" + (found.size() == 1 ? "y" : "ies") + " match \"" + q + "\"" + (found.size() >= limit ? " (the first " + limit + ")" : ""));
        return r;
    }

    /**
     * library_absorb: a conversation the person had with another assistant, as a starting point. The
     * transcript is shelved as it is (collection "conversations" unless named); the person's questions join
     * the open questions; the assistant's claims come back as a list to check and are filed as nothing.
     * verify=true files one research run that checks the claims against sources. Input: path (the keeper's),
     * url (any write patron), or text (pasted). An export holding many conversations takes the first
     * {@code limit} (default 25) and says how many remain.
     */
    public ObjectNode absorb(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String path = args.path("path").asText("").strip(), url = args.path("url").asText("").strip(), text = args.path("text").asText("");
        if (path.startsWith("http://") || path.startsWith("https://")) { url = path; path = ""; }
        int given = (path.isEmpty() ? 0 : 1) + (url.isEmpty() ? 0 : 1) + (text.isBlank() ? 0 : 1);
        if (given != 1) throw ProtocolError.invalidArgs("Give one of: a path (a saved transcript or an export file), a url, or the text of the conversation.");
        String collection = args.path("collection").asText("conversations").strip();
        String titleGiven = args.path("title").asText("").strip();
        int limit = Math.max(1, args.path("limit").asInt(Conversations.DEFAULT_THREADS));
        boolean verify = args.path("verify").asBoolean(false);
        byte[] bytes; String source, nameHint;
        if (!path.isEmpty()) {
            if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Paths on this machine are the keeper's to give: absorb a file from the terminal or the library's own pages. A url or the pasted text works from here.");
            Path f = Path.of(path).toAbsolutePath().normalize();
            if (!Files.isRegularFile(f)) throw ProtocolError.notFound(f + " (a file; for a folder of documents use library_add)");
            bytes = Files.readAllBytes(f); source = f.toString(); nameHint = f.getFileName().toString();
        } else if (!url.isEmpty()) {
            try {
                var resp = org.researchzosho.tools.Fetch.get(url, java.time.Duration.ofSeconds(60));
                if (resp.status() >= 400) throw new IOException("HTTP " + resp.status());
                String ct = resp.contentType() == null ? "" : resp.contentType();
                bytes = ct.contains("json") ? resp.body() : org.researchzosho.tools.DocText.convert(resp.body(), url).text().getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) { throw ProtocolError.unavailable("could not read " + url + ": " + e.getMessage()); }
            source = url; nameHint = titleGiven.isEmpty() ? url : titleGiven;
        } else {
            bytes = text.getBytes(StandardCharsets.UTF_8); source = "pasted"; nameHint = titleGiven.isEmpty() ? "pasted conversation" : titleGiven;
        }
        List<Conversations.Thread> threads = Conversations.parse(bytes, nameHint);
        if (!titleGiven.isEmpty() && threads.size() == 1) threads = List.of(new Conversations.Thread(titleGiven, threads.get(0).turns()));
        var extractor = Conversations.modelExtractor(Explain.drive());
        ObjectNode r = envelope();
        r.put("source", source); r.put("collection", collection);
        r.put("threads_found", threads.size());
        ArrayNode out = r.putArray("threads");
        int filed = 0, claimsAll = 0;
        List<String> allClaims = new ArrayList<>(); String firstTitle = ""; String mainQuestion = "";
        for (Conversations.Thread t : threads.subList(0, Math.min(limit, threads.size()))) {
            Conversations.Outcome o = Conversations.absorb(store, t, source, collection, patron.writer(), extractor);
            ObjectNode tn = out.addObject();
            tn.put("title", o.title()); tn.put("raw", o.raw()); tn.put("turns", o.turns());
            ArrayNode qf = tn.putArray("questions_filed"); o.questionsFiled().forEach(qf::add);
            ArrayNode qh = tn.putArray("questions_already_open"); o.questionsHeld().forEach(qh::add);
            ArrayNode cl = tn.putArray("claims_to_check"); o.claims().forEach(cl::add);
            filed += o.questionsFiled().size(); claimsAll += o.claims().size();
            if (firstTitle.isEmpty()) { firstTitle = o.title(); mainQuestion = Conversations.mainQuestion(t); }
            if (allClaims.size() < Conversations.MAX_CLAIMS) for (String c : o.claims()) { if (allClaims.size() >= Conversations.MAX_CLAIMS) break; allClaims.add(c); }
        }
        r.put("absorbed", Math.min(limit, threads.size()));
        r.put("remaining", Math.max(0, threads.size() - limit));
        r.put("questions_filed", filed); r.put("claims_to_check", claimsAll);
        r.put("claims_by", extractor == null ? "mechanical" : "model");
        if (verify && !allClaims.isEmpty()) {
            ObjectNode ask = M.createObjectNode();
            ask.put("question", Conversations.verifyQuestion(firstTitle, allClaims)); ask.put("mode", "depth"); ask.put("sources", "both");
            ask.set("patron", args.path("patron").deepCopy());
            ObjectNode job = research(ask);
            r.put("verify_job_id", job.path("job_id").asText());
        }
        r.put("main_question", mainQuestion);
        r.put("summary", (threads.size() == 1 ? "absorbed \"" + Acquisitions.compress(firstTitle, 60) + "\"" : "absorbed " + Math.min(limit, threads.size()) + " of " + threads.size() + " conversations")
                + ": the transcript is on the shelves (collection " + collection + "), " + filed + " question(s) joined the open questions, " + claimsAll + " claim(s) by the assistant are listed to check"
                + (verify && !allClaims.isEmpty() ? "; a run is checking them (" + r.path("verify_job_id").asText() + ")" : "; none is filed as a finding"));
        r.put("next", verify ? "library_job to follow the check; library_research {question: <the thread's main question>, sources: both} to research the subject itself"
                : "verify=true files one research run that checks the claims; library_research {question: \"" + Acquisitions.compress(mainQuestion, 200).replace("\"", "'") + "\", sources: both} researches the subject itself");
        return r;
    }

    /**
     * library_items: a list of things (books, tools, places — an inventory of line items) as a starting point.
     * The list is shelved as it is (collection "lists"); each item is checked against the shelves; then, through
     * the lens ("{item}: what it is…" unless given), each item becomes a question on the frontier (as=frontier,
     * the default), or the items go out as research runs in batches with a lane per item (as=runs), or
     * nothing is filed and the person just sees what is held (as=none). Input: path (the keeper's), url, or text.
     */
    public ObjectNode items(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String path = args.path("path").asText("").strip(), url = args.path("url").asText("").strip(), text = args.path("text").asText("");
        if (path.startsWith("http://") || path.startsWith("https://")) { url = path; path = ""; }
        int given = (path.isEmpty() ? 0 : 1) + (url.isEmpty() ? 0 : 1) + (text.isBlank() ? 0 : 1);
        if (given != 1) throw ProtocolError.invalidArgs("Give one of: a path (a file with one item per line, a table or a CSV), a url, or the list as text.");
        String as = args.path("as").asText("frontier").strip().toLowerCase(Locale.ROOT);
        if (!as.equals("frontier") && !as.equals("runs") && !as.equals("none")) throw ProtocolError.invalidArgs("as must be frontier (a question per item on the open questions), runs (research runs, a lane per item) or none (only see what is held).");
        String lens = args.path("lens").asText("");
        String collection = args.path("collection").asText("lists").strip();
        String column = args.hasNonNull("column") ? args.get("column").asText() : null;
        int limit = Math.max(1, args.path("limit").asInt(Items.MAX_ITEMS));
        String title = args.path("title").asText("").strip(), source, body, readFrom = "";
        if (!path.isEmpty()) {
            if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Paths on this machine are the keeper's to give: hand a list file over from the terminal or the library's own pages. A url or the pasted list works from here.");
            Path f = Path.of(path).toAbsolutePath().normalize();
            if (Files.isDirectory(f)) {
                // a Calibre library: its books as the list, each with its authors, year, series and tags as the note
                if (!Calibre.isLibrary(f)) throw ProtocolError.notFound(f + " is a folder but not a Calibre library (no metadata.db, no metadata.opf); for a folder of documents use library_add");
                Calibre.Shelf shelf;
                try { shelf = Calibre.read(f); } catch (IOException e) { throw ProtocolError.unavailable(e.getMessage()); }
                body = Calibre.csv(shelf.books()); readFrom = shelf.readFrom();
                source = f.toString(); if (title.isEmpty()) title = "Calibre library " + f.getFileName();
            } else {
                if (!Files.isRegularFile(f)) throw ProtocolError.notFound(f.toString());
                org.researchzosho.tools.DocText.Doc doc = org.researchzosho.tools.DocText.convert(Files.readAllBytes(f), f.getFileName().toString());
                body = doc.text();
                if (Corpus.ext(f).equals("csv") || Corpus.ext(f).equals("txt") || Corpus.ext(f).equals("md")) body = Files.readString(f, StandardCharsets.UTF_8);
                if ("calibre".equals(doc.kind())) { readFrom = "metadata.db"; if (title.isEmpty()) title = doc.title(); }   // a bare metadata.db is the library's books
                source = f.toString(); if (title.isEmpty()) title = Conversations.titleFrom(f.getFileName().toString());
            }
        } else if (!url.isEmpty()) {
            try {
                var resp = org.researchzosho.tools.Fetch.get(url, java.time.Duration.ofSeconds(60));
                if (resp.status() >= 400) throw new IOException("HTTP " + resp.status());
                body = org.researchzosho.tools.DocText.convert(resp.body(), url).text();
            } catch (Exception e) { throw ProtocolError.unavailable("could not read " + url + ": " + e.getMessage()); }
            source = url; if (title.isEmpty()) title = Conversations.titleFrom(url);
        } else { body = text; source = "pasted"; if (title.isEmpty()) title = "list"; }
        List<Items.Item> all = Items.parse(body, column);
        if (all.isEmpty()) throw ProtocolError.invalidArgs("No items found: one per line, a markdown table, or a CSV.");
        // the whole list goes on the shelves; what is looked at, filed or run is the selection: --match texts, --sample n, then the first limit
        List<String> match = new ArrayList<>();
        if (args.path("match").isArray()) for (JsonNode m : args.get("match")) match.add(m.asText()); else if (args.hasNonNull("match") && !args.get("match").asText().isBlank()) match.add(args.get("match").asText());
        int sample = Math.max(0, args.path("sample").asInt(0));
        List<Items.Item> matched = match.isEmpty() ? all : Items.select(all, match, 0, 0);
        List<Items.Item> chosen = sample > 0 ? Items.select(matched, List.of(), sample, args.path("seed").asLong(System.nanoTime())) : matched;
        List<Items.Item> items = chosen.subList(0, Math.min(Math.min(limit, Items.MAX_ITEMS), chosen.size()));
        Path raw = Items.shelve(store, title, all, source, collection);
        String rawName = raw == null ? "" : raw.getFileName().toString();
        String listName = Items.listFileName(store, raw);
        LibrarianIndex index = new LibrarianIndex(store);
        List<Frontier.Line> open = Frontier.read(store);
        ObjectNode r = envelope();
        r.put("title", title); r.put("source", source); r.put("raw", rawName); r.put("collection", collection);
        if (!readFrom.isEmpty()) r.put("read_from", readFrom);
        r.put("items_found", all.size()); r.put("matched", matched.size()); r.put("taken", items.size()); r.put("remaining", chosen.size() - items.size());
        if (!match.isEmpty()) { ArrayNode mm = r.putArray("match"); match.forEach(mm::add); }
        if (sample > 0) r.put("sample", sample);
        r.put("lens", lens.isBlank() ? Items.DEFAULT_LENS : lens); r.put("as", as);
        ArrayNode out = r.putArray("items");
        int held = 0, filed = 0, already = 0;
        List<String> questions = new ArrayList<>();
        for (Items.Item it : items) {
            ObjectNode n = out.addObject();
            n.put("item", it.name()); if (!it.note().isEmpty()) n.put("note", it.note());
            LibrarianIndex.Hit h = Items.held(store, index, it, rawName, listName);
            if (h != null) { held++; n.put("held", h.id()); n.put("held_title", h.title()); } else n.putNull("held");
            String q = Items.question(it, lens);
            n.put("question", q); questions.add(q);
            if (as.equals("frontier")) {
                boolean ok = Items.file(store, open, q, patron.writer(), title);
                n.put("filed", ok); if (ok) filed++; else already++;
            }
        }
        r.put("held", held); r.put("not_held", items.size() - held);
        ArrayNode jobsOut = r.putArray("jobs");
        if (as.equals("runs")) {
            for (int i = 0; i < questions.size(); i += Items.BATCH) {
                List<String> batch = questions.subList(i, Math.min(i + Items.BATCH, questions.size()));
                ObjectNode ask = M.createObjectNode();
                ask.put("question", "For each of these, " + (lens.isBlank() ? "what it is, who made or wrote it, what it is for, and what is known about it" : lens.replace("{item}", "the item")) + " — from the list \"" + Acquisitions.compress(title, 60) + "\": " + String.join("; ", batch.stream().map(q -> Acquisitions.compress(q, 80)).toList()));
                ArrayNode subs = ask.putArray("sub_questions"); batch.forEach(subs::add);
                ask.put("mode", "broad"); ask.put("sources", args.path("sources").asText("both"));
                ask.set("patron", args.path("patron").deepCopy());
                jobsOut.add(research(ask).path("job_id").asText());
            }
        }
        r.put("questions_filed", filed); r.put("questions_already_open", already);
        String what = items.size() + " item(s)" + (matched.size() < all.size() ? " of " + matched.size() + " matched" + (sample > 0 && matched.size() > items.size() ? ", a sample" : "") : all.size() > items.size() ? " of " + all.size() + (sample > 0 ? ", a sample" : "") : "")
                + (all.size() > items.size() ? " (all " + all.size() + " are on the shelves)" : "") + ", " + held + " already on the shelves";
        r.put("summary", "list \"" + Acquisitions.compress(title, 60) + "\": " + what
                + (as.equals("frontier") ? "; " + filed + " question(s) joined the open questions" + (already > 0 ? ", " + already + " were open already" : "")
                : as.equals("runs") ? "; " + jobsOut.size() + " research run(s) filed, a lane per item" : "; nothing filed, the list is on the shelves")
                + (rawName.isEmpty() ? "" : "; the list itself is " + rawName));
        r.put("next", as.equals("frontier") ? "the housekeeping's explorer works through them at night; library_research on one of the questions looks it up now; as=runs sends them out as runs"
                : as.equals("runs") ? "library_job follows each run; library_get reads the write-ups"
                : "as=frontier files a question per item; as=runs sends them out as research runs");
        store.circulate("items", patron.writer() + " :: " + Acquisitions.compress(title, 80) + " — " + what + " (" + as + ")");
        return r;
    }

    /** What a launching point was given: the bytes, where they came from, a name to title it by. */
    private record Given(byte[] bytes, String source, String nameHint, boolean pasted) { }

    /** path (the keeper's), url, or text — exactly one. {@code pastedName} titles a pasted text. */
    private Given given(JsonNode args, Patrons.Patron patron, String pastedName, String what) throws IOException {
        String path = args.path("path").asText("").strip(), url = args.path("url").asText("").strip(), text = args.path("text").asText("");
        if (path.startsWith("http://") || path.startsWith("https://")) { url = path; path = ""; }
        int n = (path.isEmpty() ? 0 : 1) + (url.isEmpty() ? 0 : 1) + (text.isBlank() ? 0 : 1);
        if (n != 1) throw ProtocolError.invalidArgs("Give one of: a path (" + what + "), a url, or the text itself.");
        String title = args.path("title").asText("").strip();
        if (!path.isEmpty()) {
            if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Paths on this machine are the keeper's to give: hand the file over from the terminal or the library's own pages. A url or the pasted text works from here.");
            Path f = Path.of(path).toAbsolutePath().normalize();
            if (!Files.isRegularFile(f)) throw ProtocolError.notFound(f.toString());
            return new Given(Files.readAllBytes(f), f.toString(), title.isEmpty() ? f.getFileName().toString() : title, false);
        }
        if (!url.isEmpty()) {
            try {
                var resp = org.researchzosho.tools.Fetch.get(url, java.time.Duration.ofSeconds(60));
                if (resp.status() >= 400) throw new IOException("HTTP " + resp.status());
                return new Given(resp.body(), url, title.isEmpty() ? url : title, false);
            } catch (Exception e) { throw ProtocolError.unavailable("could not read " + url + ": " + e.getMessage()); }
        }
        return new Given(text.getBytes(StandardCharsets.UTF_8), "pasted", title.isEmpty() ? pastedName : title, true);
    }

    /** Text out of whatever was given: a document is converted, a text file is read as it is. */
    private static String textOf(Given g) {
        String name = g.nameHint().toLowerCase(Locale.ROOT);
        if (g.pasted() || name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv") || name.endsWith(".bib") || name.endsWith(".ris") || name.endsWith(".vtt") || name.endsWith(".json") || name.endsWith(".html") || name.endsWith(".htm")) return new String(g.bytes(), StandardCharsets.UTF_8);
        return org.researchzosho.tools.DocText.convert(g.bytes(), g.nameHint()).text();
    }

    /**
     * library_check: the person's own draft — a memo, a chapter, notes — as a starting point. One voice: its
     * definite statements are claims to check, its questions are theirs and join the open questions, and the
     * citations it carries (urls, DOIs, arXiv ids) are fetched onto the shelves. verify=true files the run.
     */
    public ObjectNode check(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        Given g = given(args, patron, "draft", "a draft: text, markdown, a Word file or a PDF");
        String text = org.researchzosho.tools.DocText.convert(g.bytes(), g.nameHint()).text();
        if (text.isBlank()) text = new String(g.bytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) throw ProtocolError.invalidArgs("No text could be read from the draft.");
        String title = args.path("title").asText("").strip();
        if (title.isEmpty()) {
            var doc = org.researchzosho.tools.DocText.convert(g.bytes(), g.nameHint());
            title = doc.title().isBlank() ? "" : doc.title();
            if (title.isEmpty()) { java.util.regex.Matcher h = java.util.regex.Pattern.compile("(?m)^#\\s+(.+)$").matcher(text); if (h.find()) title = h.group(1).strip(); }   // a markdown draft's first heading
            if (title.isEmpty()) title = Conversations.titleFrom(g.nameHint());
        }
        String collection = args.path("collection").asText("drafts").strip();
        boolean fetch = args.path("fetch_citations").asBoolean(true);
        Drafts.Outcome o = Drafts.check(store, text, title, g.source(), collection, patron.writer(), Conversations.modelExtractor(Explain.drive()), fetch);
        ObjectNode r = envelope();
        r.put("title", o.title()); r.put("raw", o.raw()); r.put("source", g.source()); r.put("collection", collection);
        ArrayNode cl = r.putArray("claims_to_check"); o.claims().forEach(cl::add);
        ArrayNode qf = r.putArray("questions_filed"); o.questionsFiled().forEach(qf::add);
        ArrayNode qh = r.putArray("questions_already_open"); o.questionsHeld().forEach(qh::add);
        ArrayNode ci = r.putArray("citations");
        int shelved = 0, requested = 0;
        for (Shelving.Got got : o.citations()) {
            ObjectNode c = ci.addObject(); c.put("locator", got.locator());
            if (got.shelved()) { shelved++; c.put("raw", got.raw().getFileName().toString()); c.put("title", got.title()); c.put("state", "held".equals(got.kind()) ? "already held" : "shelved"); }
            else { c.put("state", got.requested() ? "requested" : "not read"); c.put("problem", got.problem()); if (got.requested()) requested++; }
        }
        r.put("claims_by", Explain.drive() == null ? "mechanical" : "model");
        if (args.path("verify").asBoolean(false) && !o.claims().isEmpty()) {
            ObjectNode ask = M.createObjectNode();
            ask.put("question", Drafts.verifyQuestion(title, o.claims())); ask.put("mode", "depth"); ask.put("sources", "both");
            ask.set("patron", args.path("patron").deepCopy());
            r.put("verify_job_id", research(ask).path("job_id").asText());
        }
        r.put("summary", "draft \"" + Acquisitions.compress(title, 60) + "\": " + o.claims().size() + " claim(s) to check, " + o.citations().size() + " citation(s) found (" + shelved + " on the shelves" + (requested > 0 ? ", " + requested + " could not be read and are requested" : "") + "), " + o.questionsFiled().size() + " question(s) joined the open questions"
                + (r.hasNonNull("verify_job_id") ? "; a run is checking the claims (" + r.path("verify_job_id").asText() + ")" : "; nothing is filed as a finding"));
        r.put("next", r.hasNonNull("verify_job_id") ? "library_job follows the check; library_get reads the write-up" : "verify=true files one research run that checks the claims against sources, the draft's own citations included; a requested citation can be supplied with researchzosho add <file> --for <url>");
        return r;
    }

    /**
     * library_reading: a reading list — BibTeX, RIS, a CSV export, or lines of DOIs, urls and titles — fetched
     * onto the shelves as a collection. Entries with a title only are reported; watch=true registers the list
     * so the housekeeping re-reads the pages.
     */
    public ObjectNode reading(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        Given g = given(args, patron, "reading list", "a reading list: .bib, .ris, .csv, or one entry per line");
        String text = textOf(g);
        List<Reading.Entry> entries = Reading.parse(text);
        if (entries.isEmpty()) throw ProtocolError.invalidArgs("No entries found: BibTeX, RIS, a CSV with a title/doi/url column, or one DOI, url or title per line.");
        String title = args.path("title").asText("").strip(); if (title.isEmpty()) title = Conversations.titleFrom(g.nameHint());
        String collection = args.path("collection").asText("").strip(); if (collection.isEmpty()) collection = Corpus.nameFor(Path.of(title.replaceAll("[^\\p{L}\\p{N}]+", "-")));
        int limit = Math.max(1, args.path("limit").asInt(Reading.MAX_ENTRIES));
        List<Reading.Entry> take = entries.subList(0, Math.min(limit, entries.size()));
        List<Items.Item> asItems = new ArrayList<>(); for (Reading.Entry e : entries) asItems.add(new Items.Item(e.title(), (e.located() ? e.locator() : "") + (e.note().isEmpty() ? "" : (e.located() ? " — " : "") + e.note())));
        Path list = Items.shelve(store, title, asItems, g.source(), collection);
        ObjectNode r = envelope();
        r.put("title", title); r.put("collection", collection); r.put("source", g.source()); r.put("raw", list == null ? "" : list.getFileName().toString());
        r.put("entries_found", entries.size()); r.put("taken", take.size()); r.put("remaining", entries.size() - take.size());
        ArrayNode out = r.putArray("entries");
        int shelved = 0, requested = 0, unlocated = 0;
        List<String> titlesOnly = new ArrayList<>();
        for (Reading.Entry e : take) {
            ObjectNode n = out.addObject(); n.put("title", e.title()); if (!e.note().isEmpty()) n.put("note", e.note());
            if (!e.located()) { unlocated++; n.put("state", "no locator"); titlesOnly.add(e.title()); continue; }
            n.put("locator", e.locator());
            Shelving.Got got = Shelving.fetch(store, e.locator(), collection, "on the reading list \"" + Acquisitions.compress(title, 60) + "\"");
            if (got.shelved()) { shelved++; n.put("raw", got.raw().getFileName().toString()); n.put("state", "held".equals(got.kind()) ? "already held" : "shelved"); if (!got.title().isEmpty()) n.put("title_read", got.title()); }
            else { n.put("state", got.requested() ? "requested" : "not read"); n.put("problem", got.problem()); if (got.requested()) requested++; }
        }
        boolean watch = args.path("watch").asBoolean(false);
        if (watch && list != null) Corpus.registerUrls(store, collection, list.getFileName().toString());
        r.put("shelved", shelved); r.put("requested", requested); r.put("no_locator", unlocated); r.put("watching", watch && list != null);
        ArrayNode to = r.putArray("titles_without_locator"); titlesOnly.forEach(to::add);
        r.put("summary", "reading list \"" + Acquisitions.compress(title, 60) + "\": " + take.size() + " entr" + (take.size() == 1 ? "y" : "ies") + (entries.size() > take.size() ? " of " + entries.size() : "") + ", " + shelved + " on the shelves in collection " + collection
                + (requested > 0 ? ", " + requested + " could not be read and are requested" : "") + (unlocated > 0 ? ", " + unlocated + " have a title but no DOI or url" : "") + (watch ? "; the housekeeping re-reads the pages" : ""));
        r.put("next", "research from them: library_research {sources: \"shelves\", collections: [\"" + collection + "\"]}" + (unlocated > 0 ? "; the titles without a locator can go through library_items to be researched by name" : "") + (requested > 0 ? "; a requested entry can be supplied with researchzosho add <file> --for <url>" : ""));
        store.circulate("reading", patron.writer() + " :: " + Acquisitions.compress(title, 80) + " — " + shelved + " shelved, " + requested + " requested, " + unlocated + " unlocated");
        return r;
    }

    /**
     * library_questions: a list of questions — a syllabus, an exam, what the person needs answered — onto the
     * open questions in order (as=frontier), or as research runs, one each, up to a few (as=runs; the rest are
     * filed as open questions).
     */
    public ObjectNode questions(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        Given g = given(args, patron, "questions", "a file with one question per line");
        List<String> qs = Questions.parse(textOf(g));
        if (qs.isEmpty()) throw ProtocolError.invalidArgs("No questions found: one per line, ending in a question mark or starting like a question.");
        String as = args.path("as").asText("frontier").strip().toLowerCase(Locale.ROOT);
        if (!as.equals("frontier") && !as.equals("runs")) throw ProtocolError.invalidArgs("as must be frontier (the open questions, in order) or runs (a research run each, up to " + Questions.MAX_RUNS + "; the rest go to the open questions).");
        String title = args.path("title").asText("").strip(); if (title.isEmpty()) title = Conversations.titleFrom(g.nameHint());
        int limit = Math.max(1, args.path("limit").asInt(Questions.MAX));
        List<String> take = qs.subList(0, Math.min(limit, qs.size()));
        List<Frontier.Line> open = Frontier.read(store);
        ObjectNode r = envelope();
        r.put("title", title); r.put("source", g.source()); r.put("as", as);
        r.put("questions_found", qs.size()); r.put("taken", take.size()); r.put("remaining", qs.size() - take.size());
        ArrayNode out = r.putArray("questions"); ArrayNode jobs = r.putArray("jobs");
        int filed = 0, already = 0;
        for (int i = 0; i < take.size(); i++) {
            String q = take.get(i);
            ObjectNode n = out.addObject(); n.put("question", q);
            if (as.equals("runs") && jobs.size() < Questions.MAX_RUNS) {
                ObjectNode ask = M.createObjectNode(); ask.put("question", q); ask.put("mode", "broad"); ask.put("sources", args.path("sources").asText("both")); ask.set("patron", args.path("patron").deepCopy());
                String id = research(ask).path("job_id").asText(); jobs.add(id); n.put("job_id", id);
                continue;
            }
            boolean ok = Items.file(store, open, q, patron.writer(), title);
            n.put("filed", ok); if (ok) filed++; else already++;
        }
        r.put("questions_filed", filed); r.put("questions_already_open", already);
        r.put("summary", "questions \"" + Acquisitions.compress(title, 60) + "\": " + take.size() + (qs.size() > take.size() ? " of " + qs.size() : "") + " question(s); " + (jobs.size() > 0 ? jobs.size() + " sent out as research runs, " : "") + filed + " joined the open questions in order" + (already > 0 ? ", " + already + " were open already" : ""));
        r.put("next", jobs.size() > 0 ? "library_job follows each run; the rest wait on the open questions for the housekeeping" : "the housekeeping's explorer works through them at night in this order; library_frontier op=next moves one to the front; as=runs sends the first " + Questions.MAX_RUNS + " out now");
        store.circulate("questions", patron.writer() + " :: " + Acquisitions.compress(title, 80) + " — " + filed + " filed, " + jobs.size() + " run(s)");
        return r;
    }

    /**
     * library_bookmarks: a browser's bookmarks (the exported bookmarks file, Chrome's Bookmarks JSON, or lines
     * of urls) fetched onto the shelves as a collection; watch=true registers the list so the housekeeping
     * re-reads the pages and keeps a new capture when one changes.
     */
    public ObjectNode bookmarks(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        Given g = given(args, patron, "bookmarks", "a bookmarks export (.html), Chrome's Bookmarks file, or one url per line");
        List<Bookmarks.Mark> marks = Bookmarks.parse(new String(g.bytes(), StandardCharsets.UTF_8));
        String folder = args.path("folder").asText("").strip();
        if (!folder.isEmpty()) marks = marks.stream().filter(m -> m.folder().equalsIgnoreCase(folder)).toList();
        if (marks.isEmpty()) throw ProtocolError.invalidArgs(folder.isEmpty() ? "No bookmarks found: a browser's exported bookmarks file, Chrome's Bookmarks JSON, or one url per line." : "No bookmarks in a folder named " + folder + ".");
        String title = args.path("title").asText("").strip(); if (title.isEmpty()) title = folder.isEmpty() ? "bookmarks" : "bookmarks: " + folder;
        String collection = args.path("collection").asText("").strip(); if (collection.isEmpty()) collection = folder.isEmpty() ? "bookmarks" : Corpus.nameFor(Path.of(folder));
        int limit = Math.max(1, args.path("limit").asInt(Bookmarks.MAX));
        List<Bookmarks.Mark> take = marks.subList(0, Math.min(limit, marks.size()));
        List<Items.Item> asItems = new ArrayList<>(); for (Bookmarks.Mark m : marks) asItems.add(new Items.Item(m.title().isEmpty() ? m.url() : m.title(), m.url() + (m.folder().isEmpty() ? "" : " — " + m.folder())));
        Path list = Items.shelve(store, title, asItems, g.source(), collection);
        ObjectNode r = envelope();
        r.put("title", title); r.put("collection", collection); r.put("source", g.source()); r.put("raw", list == null ? "" : list.getFileName().toString());
        r.put("bookmarks_found", marks.size()); r.put("taken", take.size()); r.put("remaining", marks.size() - take.size());
        ArrayNode out = r.putArray("bookmarks");
        int shelved = 0, requested = 0;
        for (Bookmarks.Mark m : take) {
            ObjectNode n = out.addObject(); n.put("title", m.title()); n.put("url", m.url()); if (!m.folder().isEmpty()) n.put("folder", m.folder());
            Shelving.Got got = Shelving.fetch(store, m.url(), collection, "a bookmark in \"" + Acquisitions.compress(title, 60) + "\"");
            if (got.shelved()) { shelved++; n.put("raw", got.raw().getFileName().toString()); n.put("state", "held".equals(got.kind()) ? "already held" : "shelved"); }
            else { n.put("state", got.requested() ? "requested" : "not read"); n.put("problem", got.problem()); if (got.requested()) requested++; }
        }
        boolean watch = args.path("watch").asBoolean(false);
        if (watch && list != null) Corpus.registerUrls(store, collection, list.getFileName().toString());
        r.put("shelved", shelved); r.put("requested", requested); r.put("watching", watch && list != null);
        r.put("summary", "bookmarks" + (folder.isEmpty() ? "" : " in " + folder) + ": " + take.size() + (marks.size() > take.size() ? " of " + marks.size() : "") + " page(s), " + shelved + " on the shelves in collection " + collection + (requested > 0 ? ", " + requested + " could not be read and are requested" : "") + (watch ? "; the housekeeping re-reads them and keeps a new copy when one changes" : ""));
        r.put("next", "research from them: library_research {sources: \"shelves\", collections: [\"" + collection + "\"]}" + (watch ? "" : "; watch=true has the housekeeping re-read the pages"));
        store.circulate("bookmarks", patron.writer() + " :: " + title + " — " + shelved + " shelved, " + requested + " requested" + (watch ? ", watching" : ""));
        return r;
    }

    /**
     * library_meeting: a meeting transcript (WebVTT, "Name: words" lines) as a starting point. The transcript
     * goes on the shelves with its decisions; the questions raised join the open questions; the claims made
     * come back to check with who said them. verify=true files the run.
     */
    public ObjectNode meeting(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        Given g = given(args, patron, "meeting", "a transcript: .vtt, .txt or .md with a speaker per line");
        String text = textOf(g);
        if (text.isBlank()) throw ProtocolError.invalidArgs("No text could be read from the transcript.");
        String title = args.path("title").asText("").strip(); if (title.isEmpty()) title = Conversations.titleFrom(g.nameHint());
        String collection = args.path("collection").asText("meetings").strip();
        Meetings.Transcript t = Meetings.parse(text, title);
        Meetings.Outcome o = Meetings.absorb(store, t, g.source(), collection, patron.writer(), Conversations.modelExtractor(Explain.drive()));
        ObjectNode r = envelope();
        r.put("title", o.title()); r.put("raw", o.raw()); r.put("source", g.source()); r.put("collection", collection);
        ArrayNode sp = r.putArray("speakers"); o.speakers().forEach(sp::add);
        ArrayNode qf = r.putArray("questions_filed"); o.questionsFiled().forEach(qf::add);
        ArrayNode qh = r.putArray("questions_already_open"); o.questionsHeld().forEach(qh::add);
        ArrayNode cl = r.putArray("claims_to_check"); o.claims().forEach(cl::add);
        ArrayNode de = r.putArray("decisions"); o.decisions().forEach(de::add);
        r.put("claims_by", Explain.drive() == null ? "mechanical" : "model");
        if (args.path("verify").asBoolean(false) && !o.claims().isEmpty()) {
            ObjectNode ask = M.createObjectNode();
            ask.put("question", Meetings.verifyQuestion(title, o.claims())); ask.put("mode", "depth"); ask.put("sources", "both");
            ask.set("patron", args.path("patron").deepCopy());
            r.put("verify_job_id", research(ask).path("job_id").asText());
        }
        r.put("summary", "meeting \"" + Acquisitions.compress(title, 60) + "\": " + o.speakers().size() + " speaker(s); " + o.questionsFiled().size() + " question(s) raised joined the open questions, " + o.claims().size() + " claim(s) to check, " + o.decisions().size() + " decision(s) kept with the transcript"
                + (r.hasNonNull("verify_job_id") ? "; a run is checking the claims (" + r.path("verify_job_id").asText() + ")" : "; nothing is filed as a finding"));
        r.put("next", r.hasNonNull("verify_job_id") ? "library_job follows the check" : "verify=true files one research run that checks the claims; library_ask finds the decisions later");
        return r;
    }

    /**
     * library_bridges: discovery by combination. op=run computes candidate pairs from an area (or the hottest) and
     * files up to per_night proposals as open questions of type bridge (dry=true only shows them); op=list the open
     * proposals; op=accept files the research run that tests one; op=dismiss drops it; op=settings reads or sets
     * an area's dials; op=measure reads the ledger: proposed, kept, dismissed, corroborated, by settings.
     */
    public ObjectNode bridges(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("list").strip().toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        switch (op) {
            case "list" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                ArrayNode items = r.putArray("proposals");
                for (Frontier.Line l : Bridges.open(store)) { ObjectNode o = items.addObject(); o.put("question", l.text()); o.put("date", l.date()); o.put("found_by", l.kind()); o.put("evidence", Bridges.evidenceFor(store, l.text())); }
                r.put("count", items.size());
                r.put("next", items.size() == 0 ? "op=run proposes some; op=run with dry=true only shows the pairs" : "op=accept with the question files the research run that tests it; op=dismiss drops it");
            }
            case "run" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String area = args.path("area").asText("").strip();
                Bridges.Settings s = Bridges.settings(store, area.isEmpty() ? null : area);
                for (String k : new String[]{"sources", "reach", "toward", "away", "since", "via"}) if (args.hasNonNull(k) && !args.get(k).asText().isBlank()) s = s.with(k, args.get(k).asText());
                if (args.hasNonNull("strict")) s = s.with("strict", args.get("strict").asBoolean() ? "true" : "false");
                if (args.hasNonNull("propose")) s = s.with("propose", String.valueOf(args.get("propose").asInt()));
                boolean dry = args.path("dry").asBoolean(false);
                ObjectNode out = Bridges.propose(store, Explain.drive(), Researcher.webTools(), s, area.isEmpty() ? null : area, dry, patron.writer(), new java.util.Random());
                r.setAll(out);
                r.put("next", dry ? "the same call without dry files the proposals as open questions of type bridge" : "op=list shows them; op=accept files the run that tests one");
            }
            case "accept", "dismiss" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String q = reqStr(args, "question").strip();
                Frontier.Line found = null;
                for (Frontier.Line l : Bridges.open(store)) if (Frontier.sameQuestion(l.text(), q) || l.text().toLowerCase(Locale.ROOT).startsWith(q.toLowerCase(Locale.ROOT))) { found = l; break; }
                if (found == null) throw ProtocolError.notFound("no open bridge proposal reads \"" + Acquisitions.compress(q, 80) + "\"");
                if (op.equals("dismiss")) {
                    Frontier.drop(store, found.text(), patron.writer());
                    Bridges.fate(store, found.text(), "dismissed", patron.writer());
                    r.put("dismissed", found.text());
                } else {
                    ObjectNode ask = M.createObjectNode();
                    // the run starts from what the proposal rests on: the paths read, the outside sources that backed the middle
                    String ev = Bridges.evidenceFor(store, found.text());
                    ask.put("question", found.text() + (ev.isEmpty() ? "" : "\n\nWhat this question rests on (start here, then go further):\n" + ev)); ask.put("mode", "broad"); ask.put("sources", "both");
                    ask.set("patron", args.path("patron").deepCopy());
                    String job = research(ask).path("job_id").asText();
                    Frontier.markExplored(store, found.text(), job);
                    Bridges.fate(store, found.text(), "kept", job);
                    r.put("accepted", found.text()); r.put("job_id", job);
                    r.put("next", "library_job follows the run; its verified claims are the test of the bridge");
                }
            }
            case "settings" -> {
                String area = args.path("area").asText("").strip();
                boolean set = false;
                Bridges.Settings s = Bridges.settings(store, area.isEmpty() ? null : area);
                for (String k : new String[]{"sources", "reach", "toward", "away", "since", "per_night", "via"}) if (args.hasNonNull(k)) { s = s.with(k, args.get(k).asText()); set = true; }
                if (args.hasNonNull("strict")) { s = s.with("strict", args.get("strict").asBoolean() ? "true" : "false"); set = true; }
                if (set) { Patrons.check(store, patron, Patrons.Level.write); Bridges.setSettings(store, area.isEmpty() ? null : area, s); }
                r.put("area", area.isEmpty() ? "default" : area); r.put("settings", s.line()); r.put("per_night", s.perNight()); r.put("changed", set);
            }
            case "measure" -> { Patrons.check(store, patron, Patrons.Level.read); r.setAll(Bridges.measure(store)); }
            case "distance" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                r.setAll(Bridges.distance(store, reqStr(args, "area"), reqStr(args, "other")));
            }
            default -> throw ProtocolError.invalidArgs("op must be run, list, accept, dismiss, settings, measure or distance.");
        }
        return r;
    }

    /** library_submit: a DRAFT into the acquisitions desk. Refuses a claim with no sources. */
    public ObjectNode submit(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String claim = reqStr(args, "claim").strip();
        if (claim.length() < 20) throw ProtocolError.invalidArgs("The claim is too short to be a claim; write one to three self-contained sentences.");
        List<Finding.Source> sources = new ArrayList<>();
        JsonNode src = args.get("sources");
        if (src != null && src.isArray()) {
            for (JsonNode s : src) {
                if (s.isTextual() && !s.asText().isBlank()) sources.add(new Finding.Source(s.asText().strip(), "n/a", "patron submission"));
                else if (s.isObject() && !s.path("locator").asText("").isBlank()) {
                    sources.add(new Finding.Source(s.path("locator").asText().strip(),
                            s.path("edition").asText("n/a").strip(), s.path("why").asText("patron submission").strip()));
                }
            }
        }
        if (args.hasNonNull("source") && !args.get("source").asText().isBlank()) {   // the 0.2 single-source form
            sources.add(new Finding.Source(args.get("source").asText().strip(), "n/a", "patron submission"));
        }
        if (sources.isEmpty()) throw ProtocolError.noSources();
        sources = Citations.enrich(store, sources, Citations.LIVE);
        Finding.ClaimType ct;
        try { ct = Finding.ClaimType.valueOf(args.path("claim_type").asText("synthesis").toLowerCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs("claim_type must be extraction, synthesis, interpretation or speculation."); }
        Finding.Confidence conf;
        try { conf = Finding.Confidence.valueOf(args.path("confidence").asText("medium").toLowerCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs("confidence must be low, medium or high."); }
        String title = args.path("title").asText("").strip();
        if (title.isEmpty()) title = Acquisitions.compress(claim, 80);
        String writer = patron.anonymous() ? args.path("writer").asText("patron:mcp") : patron.writer();
        Finding.Triple triple = null;
        JsonNode tj = args.path("triple");
        if (tj.isObject()) {
            String ts = tj.path("subject").asText("").strip(), tp = tj.path("predicate").asText("").strip(), to = tj.path("object").asText("").strip();
            if (ts.isEmpty() || tp.isEmpty() || to.isEmpty()) throw ProtocolError.invalidArgs("triple needs subject, predicate and object.");
            triple = new Finding.Triple(ts, tp, to);
        }
        String id = store.nextFindingId(title);
        Finding f = new Finding(id, title, List.of(), Finding.State.draft, ct, conf, writer,
                Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.slow, "",
                sources, List.of(), null, claim + "\n", triple, List.of());
        store.write(f);
        new LibrarianIndex(store).upsert(f);
        store.circulate("submit", id + " by " + writer);
        ObjectNode r = envelope();
        r.put("id", id);
        r.put("state", "draft");
        return r;
    }

    /** library_frontier: list the open questions, or file a gap attributed to the patron. */
    public ObjectNode frontier(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("list").toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        switch (op) {
            case "list" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                Map<String, String> f = new HashMap<>();
                for (String k : List.of("type", "show", "report", "fate", "who", "subject", "language", "q")) if (args.hasNonNull(k)) f.put(k, args.path(k).asText());
                f.putIfAbsent("show", "all");
                ArrayNode qs = r.putArray("questions");
                for (var t : frontierList(args.path("hints").asBoolean(false))) if (matches(t, f)) qs.add(t);
            }
            case "tidy" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                r.put("removed", Frontier.tidy(store));
            }
            case "add" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String q = reqStr(args, "question").strip();
                if (q.length() < 12) throw ProtocolError.invalidArgs("The question is too short to file.");
                store.frontier("person " + patron.writer(), q);
                r.put("filed", true);
                r.put("question", q);
            }
            case "drop", "next", "later", "park", "unpark" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String q = reqStr(args, "question").strip();
                boolean ok = switch (op) {
                    case "drop" -> Frontier.drop(store, q, patron.writer());
                    case "next" -> Frontier.next(store, q);
                    case "later" -> Frontier.later(store, q);
                    case "park" -> Frontier.park(store, q);
                    default -> Frontier.unpark(store, q);
                };
                if (!ok) throw ProtocolError.notFound("No open question reads exactly: " + q + (op.equals("unpark") ? " (or it is not parked)" : ""));
                r.put(op.equals("drop") ? "dropped" : op.equals("park") ? "parked" : op.equals("unpark") ? "unparked" : "moved", true);
                r.put("question", q);
            }
            default -> throw ProtocolError.invalidArgs("op must be list, add, drop, next, later, park, unpark or tidy.");
        }
        return r;
    }

    /**
     * library_serials: the searches the housekeeping keeps running. op=list; op=add files {name, query, every_days};
     * op=remove stops one. A kept search is re-run on its cadence and whatever is new is listed for the person.
     */
    public ObjectNode serials(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("list").toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        switch (op) {
            case "list" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                ArrayNode arr = r.putArray("searches");
                for (Serials.Shelf sh : Serials.shelves(store)) {
                    ObjectNode o = arr.addObject();
                    o.put("name", sh.slug()); o.put("query", sh.query()); o.put("every_days", sh.everyDays()); o.put("last", sh.lastChecked());
                    o.put("parked", sh.parked()); o.put("due", sh.due(java.time.LocalDate.now()));
                }
            }
            case "add" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String name = reqStr(args, "name").strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
                String query = reqStr(args, "query").strip();
                int every = args.path("every_days").asInt(7);
                if (name.isEmpty() || query.length() < 3) throw ProtocolError.invalidArgs("A kept search needs a name and a query.");
                if (every < 1 || every > 365) throw ProtocolError.invalidArgs("every_days must be between 1 and 365.");
                Serials.add(store, name, query, every);
                r.put("kept", true); r.put("name", name); r.put("query", query); r.put("every_days", every);
            }
            case "remove" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String name = reqStr(args, "name").strip();
                if (!Serials.remove(store, name)) throw ProtocolError.notFound("No kept search is named " + name);
                r.put("removed", true); r.put("name", name);
            }
            case "park", "unpark" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String name = reqStr(args, "name").strip();
                if (!Serials.setParked(store, name, op.equals("park"))) throw ProtocolError.notFound("No kept search is named " + name + (op.equals("park") ? " (or it is parked already)" : " (or it is not parked)"));
                r.put("name", name); r.put("parked", op.equals("park"));
            }
            case "every" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String name = reqStr(args, "name").strip();
                int every = args.path("every_days").asInt(0);
                if (every < 1 || every > 365) throw ProtocolError.invalidArgs("every_days must be between 1 and 365.");
                if (!Serials.setEvery(store, name, every)) throw ProtocolError.notFound("No kept search is named " + name);
                r.put("name", name); r.put("every_days", every);
            }
            default -> throw ProtocolError.invalidArgs("op must be list, add, every, park, unpark or remove.");
        }
        return r;
    }

    /** library_subjects: the controlled vocabulary; broader = the facet before {@code --}. */
    public ObjectNode subjects(JsonNode args) throws IOException {
        Patrons.check(store, Patrons.Patron.from(args), Patrons.Level.read);
        ObjectNode r = envelope();
        ArrayNode out = r.putArray("subjects");
        Map<String, Integer> counts = Related.counts(store);
        Map<String, List<String>> facets = new TreeMap<>();
        List<String[]> terms = new ArrayList<>();
        Vocabulary vocab = Vocabulary.read(store.subjectsFile());
        if (Files.exists(store.subjectsFile())) {
            for (Vocabulary.Term t : vocab.terms().values()) {
                String slug = t.slug();
                String label = t.description().isBlank() ? slug : t.description();
                terms.add(new String[]{slug, label});
                int cut = slug.indexOf("--");
                if (cut > 0) facets.computeIfAbsent(slug.substring(0, cut), x -> new ArrayList<>()).add(slug);
            }
        }
        for (var f : facets.entrySet()) {
            ObjectNode o = out.addObject();
            o.put("id", f.getKey());
            o.put("label", f.getKey());
            o.put("facet", f.getKey());
            o.putNull("broader");
            ArrayNode n = o.putArray("narrower");
            for (String s : f.getValue()) n.add(s);
            o.put("count", 0);
        }
        for (String[] t : terms) {
            ObjectNode o = out.addObject();
            o.put("id", t[0]);
            o.put("label", t[1]);
            int cut = t[0].indexOf("--");
            o.put("facet", cut > 0 ? t[0].substring(0, cut) : t[0]);
            if (cut > 0) o.put("broader", t[0].substring(0, cut)); else o.putNull("broader");
            o.putArray("narrower");
            o.put("count", counts.getOrDefault(t[0], 0));
            Vocabulary.Term vt = vocab.get(t[0]);
            ArrayNode also = o.putArray("also"); if (vt != null) for (String a : vt.also()) also.add(a);
            if (vt == null || vt.wikidata().isBlank()) o.putNull("wikidata"); else o.put("wikidata", vt.wikidata());
        }
        return r;
    }

    /**
     * library_changes: everything that happened to findings and investigations
     * after a cursor — added, state changes, edits, supersessions — so a patron can re-check what
     * it cited. {@code since} = the cursor from the previous call (0 = from the beginning).
     */
    /**
     * Ask to be let in. No access is needed to ask, and the did comes in its own field because the caller has
     * no token yet to prove a patron object with. Returns the request id and the claim secret, once.
     */
    public ObjectNode requestAccess(JsonNode args) throws IOException {
        String did = args.path("did").asText("").strip();
        if (did.isEmpty()) did = args.path("patron").path("did").asText("").strip();
        String name = args.path("name").asText("").strip();
        String note = args.path("note").asText("").strip();
        if (did.isEmpty()) throw ProtocolError.invalidArgs("Say who is asking: a did.");
        if (note.length() > 400 || name.length() > 120) throw ProtocolError.invalidArgs("Keep the name under 120 characters and the note under 400.");
        AccessRequests.Filed f;
        try { f = AccessRequests.request(store, did, name, note); }
        catch (IOException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
        ObjectNode r = envelope();
        r.put("request_id", f.id()); r.put("state", "pending");
        if (f.fresh()) { r.put("claim", f.claim()); r.put("note", "Keep the claim secret: it is what collects the token once the request is approved. It is shown once."); }
        else r.put("note", "A request from this did is already waiting; the claim secret was given when it was filed.");
        return r;
    }

    /** What became of a request. Approved and claimed with the right secret: the token, once. */
    public ObjectNode access(JsonNode args) throws IOException {
        String id = reqStr(args, "request_id").strip();
        String claim = args.path("claim").asText("");
        AccessRequests.Outcome o;
        try { o = AccessRequests.claim(store, id, claim); }
        catch (IOException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
        ObjectNode r = envelope();
        r.put("request_id", o.id()); r.put("state", o.state());
        if (!o.token().isEmpty()) { r.put("token", o.token()); r.put("level", o.level()); r.put("note", "Use it as Authorization: Bearer <token>. It is shown once."); }
        if (!o.reason().isEmpty()) r.put("reason", o.reason());
        return r;
    }

    /** Register a webhook for this patron: every change is posted there, signed with the secret. Read access is enough. */
    public ObjectNode subscribe(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        if (patron.anonymous()) throw ProtocolError.forbidden("A subscription needs a named patron: the posts are signed for you.");
        String url = reqStr(args, "url").strip();
        String secret = args.path("secret").asText("").strip();
        if (secret.isEmpty()) { byte[] b = new byte[24]; new java.security.SecureRandom().nextBytes(b); secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
        List<String> kinds = new ArrayList<>();
        for (JsonNode k : args.path("events")) if (k.isTextual() && !k.asText().isBlank()) kinds.add(k.asText().strip());
        try { Webhooks.add(store, patron.did(), url, secret, kinds); }
        catch (IOException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
        store.circulate("subscribe", patron.label() + " :: " + url);
        ObjectNode r = envelope();
        r.put("url", url); r.put("secret", secret);
        ArrayNode ev = r.putArray("events"); for (String k : kinds) ev.add(k);
        r.put("signature", "X-ResearchZosho-Signature: sha256=<hmac-sha256(secret, body)>");
        return r;
    }

    public ObjectNode unsubscribe(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String url = reqStr(args, "url").strip();
        boolean removed = Webhooks.remove(store, patron.did(), url);
        ObjectNode r = envelope();
        r.put("url", url); r.put("removed", removed);
        return r;
    }

    public ObjectNode changes(JsonNode args) throws IOException {
        Patrons.check(store, Patrons.Patron.from(args), Patrons.Level.read);
        long since = 0;
        if (args.hasNonNull("since") && !args.get("since").asText().isBlank()) {
            try { since = Long.parseLong(args.get("since").asText()); }
            catch (NumberFormatException e) { throw ProtocolError.invalidArgs("The cursor is not one this library issued."); }
        }
        int limit = Math.min(1000, Math.max(1, args.path("limit").asInt(200)));
        ObjectNode r = envelope();
        ArrayNode out = r.putArray("changes");
        long last = since;
        for (var c : Changes.since(store, since, limit)) {
            ObjectNode o = out.addObject();
            o.put("seq", c.seq()); o.put("at", c.at()); o.put("kind", c.kind()); o.put("id", c.id());
            o.put("event", c.event()); o.put("detail", c.detail());
            last = c.seq();
        }
        r.put("next_cursor", Long.toString(last));
        r.put("latest", Long.toString(Changes.latest(store)));
        r.put("more", Changes.latest(store) > last);
        return r;
    }

    /**
     * library_research: a research run. Files a job in the ledger — the daemon's
     * one model worker runs it (a stdio session files it; the daemon picks it up within seconds)
     * and the result enters the library as a draft investigation attributed to the patron. Write
     * access; counted against the patron's daily turn budget.
     */
    /** Model turns an ask may spend when it does not say: shared by every worker, the critic and the synthesis. */
    /** No ceiling unless the ask names one: the run goes until the work is done. */
    public static final int DEFAULT_TURNS = 0;

    /** The argument checks, separately so a transport can run them before its own drive check. */
    public static void validateResearch(JsonNode args) {
        String question = reqStr(args, "question").strip();
        if (question.length() < 12) throw ProtocolError.invalidArgs("The question is too short to research.");
        String mode = args.path("mode").asText("broad");
        if (!mode.equals("broad") && !mode.equals("depth")) throw ProtocolError.invalidArgs("mode must be broad or depth.");
        for (String ceiling : new String[]{"max_turns", "max_minutes"}) {
            JsonNode c = args.path(ceiling);
            if (!c.isMissingNode() && !c.isNull() && (!c.isNumber() || c.asInt() < 0)) throw ProtocolError.invalidArgs(ceiling + ", when given, is a whole number (0 = no ceiling).");
        }
        String sources = args.path("sources").asText("both");
        if (!sources.equals("both") && !sources.equals("shelves") && !sources.equals("web")) throw ProtocolError.invalidArgs("sources must be both, shelves or web.");
        JsonNode quick = args.path("quick");
        if (!quick.isMissingNode() && !quick.isNull() && !quick.isBoolean()) throw ProtocolError.invalidArgs("quick, when given, is true or false.");
        JsonNode colls = args.path("collections");
        if (!colls.isMissingNode() && !colls.isNull() && (!colls.isArray() || colls.size() > 8)) throw ProtocolError.invalidArgs("collections must be an array of at most 8 names.");
        JsonNode subs = args.path("sub_questions");
        if (!subs.isMissingNode() && !subs.isNull()) {
            if (!subs.isArray() || subs.size() > 8) throw ProtocolError.invalidArgs("sub_questions must be an array of at most 8 strings.");
            for (JsonNode s : subs) if (!s.isTextual() || s.asText().isBlank()) throw ProtocolError.invalidArgs("sub_questions must be an array of non-empty strings.");
        }
    }

    public ObjectNode research(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        validateResearch(args);
        String question = reqStr(args, "question").strip();
        String mode = args.path("mode").asText("broad");
        boolean quick = args.path("quick").asBoolean(false);   // "look it up now": short ceilings unless the ask names its own, and the front of the line
        int maxTurns = args.path("max_turns").asInt(quick ? Explain.QUICK_TURNS : DEFAULT_TURNS);
        int maxMinutes = args.path("max_minutes").asInt(quick ? Explain.QUICK_MINUTES : 0);
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
        String who = patron.anonymous() ? "anonymous" : patron.did();
        ObjectNode a = M.createObjectNode();
        a.put("question", question); a.put("mode", mode); a.put("max_turns", maxTurns); a.put("max_minutes", maxMinutes);
        if (quick) a.put("quick", true);
        if (args.path("sub_questions").isArray() && args.path("sub_questions").size() > 0) {
            ArrayNode subs = a.putArray("sub_questions");
            for (JsonNode s : args.path("sub_questions")) subs.add(s.asText().strip());
        }
        a.put("sources", args.path("sources").asText("both"));
        if (args.path("collections").isArray() && args.path("collections").size() > 0) {
            ArrayNode cs = a.putArray("collections");
            for (JsonNode c : args.path("collections")) if (c.isTextual() && !c.asText().isBlank()) cs.add(c.asText().strip());
        }
        String id = jobs.submit("research", who, a);
        store.circulate("research-job", id + " by " + patron.writer() + " :: " + Acquisitions.compress(question, 120));
        ObjectNode r = envelope();
        r.put("job_id", id);
        r.put("state", "queued");
        if (quick) r.put("quick", true);
        return r;
    }

    /**
     * library_sharpen: a rough question in, a better one out — before anything runs. What the shelves hold
     * on it, who studies it and what each would ask, then one judge call: the question rewritten, its assumptions owned up
     * to, a brief (scope, sub-questions, sources wanted, deliverables), at most three questions back, a mode and a size.
     * {@code research_question} is the text to pass to library_research as it is, or after the person edits it. Read
     * access; needs a model drive. Two or three model calls.
     */
    public ObjectNode sharpen(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String question = reqStr(args, "question").strip();
        Sharpen.Sharpened s = Sharpen.run(store, Explain.drive(), Researcher.webTools(), question);
        ObjectNode r = envelope();
        r.setAll(s.json());
        store.circulate("sharpen", patron.label() + " :: " + Acquisitions.compress(question, 120));
        return r;
    }

    /**
     * library_explain: a reading aid, never a record. {@code id} + {@code rung} re-explains an
     * entry at beginner, familiar or written; {@code term} (+ {@code in}, an entry id) explains a term as it is
     * used there. Shelves only: the model writes from the entry, its findings and its captured sources and
     * from nothing else, every paragraph is read back against what it cites, and when the shelves do not
     * explain a term the reading says so and carries {@code offer}, the research ask that would fill the gap
     * — pass it to library_research as it is ({@code quick: true} runs it now, ahead of the night's work).
     * Read access; needs a model drive the first time, cached after.
     */
    public ObjectNode explain(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        Explain.Rung rung = Explain.Rung.of(args.path("rung").asText(""));
        boolean fresh = args.path("fresh").asBoolean(false);
        String term = args.path("term").asText("").strip();
        String id = args.path(term.isEmpty() ? "id" : "in").asText("").strip();
        if (term.isEmpty() && id.isEmpty()) throw ProtocolError.invalidArgs("Give an id to explain, or a term (with in = the entry it appears in).");
        Explain.Reading r = term.isEmpty()
                ? Explain.entry(store, rung == Explain.Rung.written ? null : Explain.drive(), id, rung, fresh)
                : Explain.term(store, Explain.drive(), term, id, rung, fresh);
        ObjectNode out = envelope();
        out.setAll(r.json());
        store.circulate("explain", patron.label() + " :: " + (term.isEmpty() ? id : term + (id.isEmpty() ? "" : " in " + id)) + " @ " + rung.name());
        return out;
    }

    /**
     * library_map: the graph around a focus — a node name (a person, a place, a work, a
     * concept) or an entry id — out to {@code depth} hops (default 1, max 3), at most {@code k} nodes
     * (default 25). Nodes carry kind, label, aliases, wikidata; edges ARE findings (id, state,
     * confidence, disputed). Private nodes are shown only to the person.
     */
    public ObjectNode map(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String focus = args.path("focus").asText("").strip();
        int depth = Math.min(3, Math.max(1, args.path("depth").asInt(1)));
        int k = Math.min(200, Math.max(1, args.path("k").asInt(25)));
        Graph g = Graph.build(store);
        ObjectNode r = envelope();
        r.put("focus", focus);
        // the names the map knows best, most connected first: what to try when a name finds nothing, or when the page opens empty
        ArrayNode suggestions = r.putArray("suggestions");
        g.nodes().stream().filter(n -> !n.privateNode() || patron.person()).sorted((x, y) -> Integer.compare(y.degree(), x.degree())).limit(12)
                .forEach(n -> suggestions.addObject().put("label", n.label()).put("kind", n.kind()).put("degree", n.degree()));
        if (focus.isEmpty()) { r.putNull("node"); r.putArray("nodes"); r.putArray("edges"); r.putArray("open"); r.put("holds_nothing", g.nodes().isEmpty()); return r; }
        Graph.Neighbourhood nb = g.around(focus, depth, k, patron.person());
        if (nb.focus() == null) {
            // no node of exactly that name: the nearest names — a label or alias containing the words typed — most connected first;
            // the best one becomes the focus ("Tokyo" → "Tokyo Vice"; "Akasaka" → "Akasaka district"), and the rest are offered
            String needle = focus.toLowerCase(Locale.ROOT);
            List<Graph.Node> near = g.nodes().stream().filter(n -> !n.privateNode() || patron.person())
                    .filter(n -> n.label().toLowerCase(Locale.ROOT).contains(needle) || n.aliases().stream().anyMatch(al -> al.toLowerCase(Locale.ROOT).contains(needle)))
                    .sorted((x, y) -> Integer.compare(y.degree(), x.degree())).toList();
            if (!near.isEmpty()) {
                nb = g.around(near.get(0).label(), depth, k, patron.person());
                r.put("resolved_from", focus);
                suggestions.removeAll();
                near.stream().limit(12).forEach(n -> suggestions.addObject().put("label", n.label()).put("kind", n.kind()).put("degree", n.degree()));
            }
        }
        if (nb.focus() == null) { r.putNull("node"); r.putArray("nodes"); r.putArray("edges"); r.putArray("open"); r.put("holds_nothing", true); return r; }
        r.set("node", nodeJson(nb.focus()));
        ArrayNode ns = r.putArray("nodes"); for (Graph.Node n : nb.nodes()) ns.add(nodeJson(n));
        ArrayNode es = r.putArray("edges");
        for (Graph.Edge e : nb.edges()) {
            ObjectNode o = es.addObject();
            o.put("from", e.from()); o.put("predicate", e.predicate()); o.put("to", e.to());
            o.put("finding", e.findingId()); o.put("state", e.state()); o.put("confidence", e.confidence()); o.put("disputed", e.disputed());
        }
        ArrayNode open = r.putArray("open"); for (String q : nb.openQuestions()) open.add(q);
        r.put("holds_nothing", false);
        return r;
    }

    private static ObjectNode nodeJson(Graph.Node n) {
        ObjectNode o = M.createObjectNode();
        o.put("id", n.id()); o.put("kind", n.kind()); o.put("label", n.label()); o.put("degree", n.degree());
        ArrayNode a = o.putArray("also"); for (String s : n.aliases()) a.add(s);
        if (n.wikidata().isEmpty()) o.putNull("wikidata"); else o.put("wikidata", n.wikidata());
        if (n.privateNode()) o.put("private", true);
        return o;
    }

    /**
     * library_perspectives: STORM's pre-writing move for the refine phase — who studies this
     * question and what each would insist on asking. One or two searches and one judge call; read access.
     */
    public ObjectNode perspectives(JsonNode args) throws IOException {
        Patrons.check(store, Patrons.Patron.from(args), Patrons.Level.read);
        String question = reqStr(args, "question").strip();
        if (question.length() < 12) throw ProtocolError.invalidArgs("The question is too short.");
        String driveUrl = org.researchzosho.Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200");
        String model = org.researchzosho.Config.get("RESEARCHZOSHO_MODEL", "local-model");
        if (!Crews.driveAnswers(driveUrl)) throw ProtocolError.unavailable("No model drive answers at " + driveUrl + "; perspectives need one.");
        List<Perspectives.Perspective> ps = Perspectives.discover(question, Researcher.judgeDrive(driveUrl, model), Researcher.webTools(), Math.min(8, Math.max(1, args.path("max").asInt(5))));
        ObjectNode r = envelope();
        r.put("question", question);
        ArrayNode out = r.putArray("perspectives");
        for (Perspectives.Perspective p : ps) {
            ObjectNode o = out.addObject();
            o.put("perspective", p.name()); o.put("why", p.why());
            ArrayNode qs = o.putArray("questions"); for (String q : p.questions()) qs.add(q);
        }
        ArrayNode subs = r.putArray("sub_questions"); for (String q : Perspectives.questions(ps, 8)) subs.add(q);
        return r;
    }

    /**
     * library_job: one job by id; or a PAGE of the patron's jobs — the active ones
     * always, then finished ones newest-first, {@code limit} (default 20) after {@code cursor}.
     */
    public ObjectNode job(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        jobs.migrate();
        ObjectNode r = envelope();
        String id = args.hasNonNull("job_id") ? args.get("job_id").asText().strip() : "";
        String op = args.path("op").asText("").toLowerCase(Locale.ROOT);
        if (op.equals("pause") || op.equals("resume")) {   // the runner: queued runs wait, a running one holds at its next turn
            Patrons.check(store, patron, Patrons.Level.write);
            org.researchzosho.Config.set(ResearchSettings.PAUSE, op.equals("pause") ? "on" : "off");
            r.put("paused", op.equals("pause"));
            return r;
        }
        if (op.equals("stop")) {   // one run: a queued one never starts, a running one ends at its next turn
            Patrons.check(store, patron, Patrons.Level.write);
            if (id.isEmpty()) throw ProtocolError.invalidArgs("stop needs job_id.");
            ObjectNode j = jobs.get(id);
            if (j == null || !Jobs.visibleTo(patron, j)) throw ProtocolError.notFound("job " + id);
            String now = jobs.stop(id, patron.writer());
            if (now == null) throw ProtocolError.invalidArgs("Job " + id + " is not queued or running; it is " + j.path("state").asText() + ".");
            r.put("job_id", id); r.put("state", now);
            return r;
        }
        r.put("paused", ResearchSettings.paused());
        if (!id.isEmpty()) {
            ObjectNode j = jobs.get(id);
            if (j == null) throw ProtocolError.notFound("job " + id);
            r.set("job", Jobs.view(j));
            return r;
        }
        int limit = Math.min(200, Math.max(1, args.path("limit").asInt(20)));
        String cursor = args.hasNonNull("cursor") && !args.get("cursor").asText().isBlank() ? args.get("cursor").asText() : null;
        ArrayNode active = r.putArray("active");
        if (cursor == null) for (ObjectNode j : jobs.active()) if (Jobs.visibleTo(patron, j)) active.add(Jobs.view(j));
        ArrayNode finished = r.putArray("finished");
        String last = null;
        // page through finished ones, skipping those the patron may not see, until the page is full
        String c = cursor;
        while (finished.size() < limit) {
            var page = jobs.recent(limit, c);
            if (page.isEmpty()) break;
            for (ObjectNode j : page) {
                last = j.path("job_id").asText();
                if (Jobs.visibleTo(patron, j)) { finished.add(Jobs.view(j)); if (finished.size() >= limit) break; }
            }
            c = last;
        }
        r.put("next_cursor", finished.size() >= limit && last != null ? last : null);
        r.put("finished_total", jobs.finishedCount());
        return r;
    }

    /** library_status: identity, contract, counts by kind and state, last update. */
    public ObjectNode status(JsonNode args) throws IOException {
        Patrons.check(store, Patrons.Patron.from(args), Patrons.Level.read);
        ObjectNode r = envelope();
        ObjectNode counts = r.putObject("counts");
        ObjectNode findings = counts.putObject("finding");
        Map<String, Integer> byState = new TreeMap<>();
        var scanned = store.scanFindings().findings();
        for (Finding f : scanned) byState.merge(f.state().name(), 1, Integer::sum);
        for (var e : byState.entrySet()) findings.put(e.getKey(), e.getValue());
        findings.put("total", scanned.size());
        counts.put("investigation", countFiles(store.investigationsDir(), ".md"));
        counts.put("article", countFiles(store.articlesDir(), ".md"));
        counts.put("raw", countFiles(store.rawDir(), ".md"));
        r.put("version", org.researchzosho.Version.string());
        counts.put("subject", (int) Related.counts(store).size());
        r.put("last_updated", lastUpdated());
        return r;
    }

    // ---- resources ----

    /** resources/list — paged: finding://, article://, raw:// (locator = the captured URL). */
    public ObjectNode resourcesList(String cursorText) throws IOException {
        int offset = 0;
        if (cursorText != null && !cursorText.isBlank()) {
            try { offset = Integer.parseInt(cursorText); } catch (NumberFormatException e) { throw ProtocolError.invalidArgs("The cursor is not one this library issued."); }
        }
        List<ObjectNode> all = new ArrayList<>();
        for (Finding f : store.scanFindings().findings()) {
            all.add(resource("finding://" + f.id(), f.title(), "text/markdown"));
        }
        for (Path p : sorted(store.articlesDir(), ".md")) {
            String id = stem(p);
            all.add(resource("article://" + id, articleMeta(p).getOrDefault("title", id), "text/markdown"));
        }
        for (Path p : sorted(store.rawDir(), ".md")) {
            String[] raw = RawCapture.read(p);
            String loc = raw[0].isEmpty() ? p.getFileName().toString() : raw[0];
            all.add(resource("raw://" + loc, raw[1].isEmpty() ? loc : raw[1], "text/plain"));
        }
        ObjectNode r = M.createObjectNode();
        ArrayNode res = r.putArray("resources");
        int end = Math.min(all.size(), offset + PAGE);
        for (int i = offset; i < end; i++) res.add(all.get(i));
        if (end < all.size()) r.put("nextCursor", Integer.toString(end));
        return r;
    }

    /** resources/templates/list. */
    public ObjectNode resourceTemplates() {
        ObjectNode r = M.createObjectNode();
        ArrayNode t = r.putArray("resourceTemplates");
        ObjectNode a = t.addObject();
        a.put("uriTemplate", "finding://{id}"); a.put("name", "A finding, in full"); a.put("mimeType", "text/markdown");
        ObjectNode b = t.addObject();
        b.put("uriTemplate", "raw://{locator}"); b.put("name", "The captured text behind a source locator"); b.put("mimeType", "text/plain");
        ObjectNode c = t.addObject();
        c.put("uriTemplate", "article://{id}"); c.put("name", "A shelf article"); c.put("mimeType", "text/markdown");
        return r;
    }

    /** resources/read — the same body library_get / library_read return. */
    public ObjectNode resourcesRead(String uri) throws IOException {
        if (uri == null || uri.isBlank()) throw ProtocolError.invalidArgs("A resource uri is required.");
        int scheme = uri.indexOf("://");
        String name = scheme < 0 ? uri : uri.substring(scheme + 3);
        // finding:// and article:// name a shelf entry; raw:// names a LOCATOR (a URL as cited, or a file
        // name) — its direct file hit is guarded in RawCapture.find, a URL is matched, never joined.
        if (!uri.startsWith("raw://") && !LibraryStore.safeName(name)) throw ProtocolError.invalidArgs("A resource name is a shelf name, not a path: " + uri);
        String text, mime;
        if (uri.startsWith("finding://")) {
            Path p = LibraryStore.under(store.findingsDir(), name + ".md");
            if (p == null || !Files.exists(p)) throw ProtocolError.notFound(uri);
            text = Files.readString(p, StandardCharsets.UTF_8); mime = "text/markdown";
        } else if (uri.startsWith("article://")) {
            Path p = LibraryStore.under(store.articlesDir(), name + ".md");
            if (p == null || !Files.exists(p)) throw ProtocolError.notFound(uri);
            text = Files.readString(p, StandardCharsets.UTF_8); mime = "text/markdown";
        } else if (uri.startsWith("raw://")) {
            Path p = rawFor(name);
            if (p == null) throw ProtocolError.notFound(uri);
            text = RawCapture.read(p)[2]; mime = "text/plain";
        } else {
            throw ProtocolError.notFound(uri + " (schemes: finding://, article://, raw://)");
        }
        ObjectNode r = M.createObjectNode();
        ObjectNode c = r.putArray("contents").addObject();
        c.put("uri", uri);
        c.put("mimeType", mime);
        c.put("text", text);
        return r;
    }

    // ---- entries ----

    /** The self-describing entry for any id of any kind; null when nothing is there. */
    ObjectNode entry(String id, String kind, boolean full) throws IOException {
        switch (kind == null ? "" : kind) {
            case "finding" -> {
                Finding f = store.finding(id);
                return f == null ? null : entry(f, full);
            }
            case "investigation" -> {
                Investigation inv = store.investigation(id);
                if (inv == null) return null;
                ObjectNode e = M.createObjectNode();
                e.put("id", inv.id()); e.put("kind", "investigation"); e.put("state", inv.state().name());
                e.put("claim_type", "synthesis"); e.put("confidence", "medium");
                e.put("writer", inv.writer()); e.put("recorded_at", inv.recordedAt());
                e.put("title", inv.title());
                e.put("body", full ? inv.body() : Acquisitions.compress(inv.body(), 500));
                ArrayNode fs = e.putArray("findings");
                for (String fid : inv.findings()) fs.add(fid);
                ArrayNode srcs = e.putArray("sources");
                if (full) {
                    // the record as a reader or a program needs it (asked for by a patron runtime, 2026-09-11): the sections
                    // with their sizes, every reference as a row, the claims with their bodies, and the questions left open
                    ArrayNode secs = e.putArray("sections");
                    for (String[] sec : sectionSizes(inv.body())) { ObjectNode o = secs.addObject(); o.put("heading", sec[0]); o.put("chars", Integer.parseInt(sec[1])); }
                    for (ObjectNode o : investigationSources(inv.body())) srcs.add(o);
                    ArrayNode claims = e.putArray("claims");
                    for (String fid : inv.findings()) { Finding f = store.finding(fid); if (f != null) claims.add(entry(f, false)); }
                    ArrayNode open = e.putArray("open_questions");
                    for (String q : inv.open()) open.add(q);
                }
                return e;
            }
            case "article" -> {
                Path p = LibraryStore.under(store.articlesDir(), id + ".md");
                if (p == null) return null;
                if (!Files.exists(p)) return null;
                Map<String, String> meta = articleMeta(p);
                ObjectNode e = M.createObjectNode();
                e.put("id", id); e.put("kind", "article"); e.put("state", "generated");
                e.put("claim_type", "synthesis"); e.put("confidence", "medium");
                e.put("writer", "model:abstracts"); e.put("recorded_at", meta.getOrDefault("generated_at", ""));
                e.put("title", meta.getOrDefault("title", id));
                ArrayNode subs = e.putArray("subjects");
                if (meta.containsKey("subject")) subs.add(meta.get("subject"));
                ArrayNode srcs = e.putArray("sources");
                for (String fid : listOf(meta.get("findings"))) {
                    ObjectNode s = srcs.addObject(); s.put("locator", fid); s.putNull("edition"); s.put("why", "cited finding");
                }
                String text = Files.readString(p, StandardCharsets.UTF_8);
                int b = text.indexOf("\n---\n", 4);
                e.put("body", b < 0 ? text : text.substring(b + 5).strip());
                return e;
            }
            case "raw" -> {
                Path p = LibraryStore.under(store.rawDir(), id);
                if (p == null) return null;
                if (!Files.exists(p)) return null;
                String[] raw = RawCapture.read(p);
                ObjectNode e = M.createObjectNode();
                e.put("id", id); e.put("kind", "raw"); e.put("state", "captured");
                e.put("claim_type", "verbatim"); e.put("confidence", "n/a");
                e.put("untrusted_text", true);   // a page's own words: a consumer treats the body as data, never instruction
                Captions.Extract cx = Captions.extract(raw[2]);
                if (!cx.isEmpty()) {
                    ArrayNode figs = e.putArray("figures"); for (String c : cx.figures()) figs.add(c);
                    ArrayNode tabs = e.putArray("tables"); for (String c : cx.tables()) tabs.add(c);
                    ArrayNode rows = e.putArray("table_rows"); for (String r : cx.rows()) rows.add(r);
                }
                e.put("writer", fetchedBy(p)); e.put("recorded_at", capturedAt(p));
                e.put("title", raw[1]);
                e.put("locator", raw[0]);
                ArrayNode srcs = e.putArray("sources");
                ObjectNode s = srcs.addObject(); s.put("locator", raw[0]); s.putNull("edition"); s.put("why", "the document itself");
                e.put("body", full ? Acquisitions.compress(raw[2], 2000) : Acquisitions.compress(raw[2], 500));
                e.put("chars", raw[2].length());
                return e;
            }
            default -> { return null; }
        }
    }

    /** heading → chars, in order, for a write-up's "## " sections. */
    static List<String[]> sectionSizes(String body) {
        List<String[]> out = new ArrayList<>();
        for (String part : body.split("(?m)^(?=## )")) {
            if (!part.startsWith("## ")) continue;
            int nl = part.indexOf('\n');
            out.add(new String[]{(nl < 0 ? part : part.substring(0, nl)).substring(3).strip(), String.valueOf(part.strip().length())});
        }
        return out;
    }

    private static final java.util.regex.Pattern REF_LINE = java.util.regex.Pattern.compile("^\\[(\\d+)\\] (.*)$");
    private static final java.util.regex.Pattern REF_PUBLISHED = java.util.regex.Pattern.compile("\\(published (\\d{4}-\\d{2}-\\d{2})[^)]*\\)");
    private static final java.util.regex.Pattern REF_SAME = java.util.regex.Pattern.compile("\\(same text as \\[(\\d+)\\]\\)");
    private static final java.util.regex.Pattern NOTE_LINE = java.util.regex.Pattern.compile("^- (.+?) — source: (.+?)(?: — quote: \"(.*)\")?$", java.util.regex.Pattern.MULTILINE);

    /**
     * The references of a write-up as rows: {@code {n, locator, title, edition, published, fetched, language, same_as, tier, rule, why}}.
     * Read from the "## References" section the runner writes (one numbered line each); an older record without one
     * gives its "## Sources cited" list, unnumbered. {@code fetched} says whether the shelves hold the page's text; {@code language}
     * is the language of the notes the workers took from it.
     */
    List<ObjectNode> investigationSources(String body) {
        List<ObjectNode> out = new ArrayList<>();
        String refs = sectionOf(body, "references");
        SourceRules rules = SourceRules.load(store);
        // locator → language of the notes taken from it
        Map<String, String> langOf = new HashMap<>();
        java.util.regex.Matcher nm = NOTE_LINE.matcher(body);
        while (nm.find()) {
            String loc = nm.group(2).replaceAll("[),.;]+$", "");
            java.util.regex.Matcher um = java.util.regex.Pattern.compile("(?:https?|file)://\\S+").matcher(loc);
            String url = um.find() ? um.group().replaceAll("[),.;]+$", "") : loc;
            langOf.putIfAbsent(url, Lanes.languageOf(url, nm.group(3) == null ? "" : nm.group(3)));
        }
        List<String[]> rows = new ArrayList<>();   // [n, rest]
        if (refs != null) {
            for (String line : refs.split("\n")) { java.util.regex.Matcher m = REF_LINE.matcher(line.strip()); if (m.matches()) rows.add(new String[]{m.group(1), m.group(2)}); }
        } else {
            String cited = sectionOf(body, "sources cited");
            if (cited == null) cited = sectionOf(body, "sources");
            int n = 0;
            if (cited != null) for (String line : cited.split("\n")) { String t = line.strip(); if (t.startsWith("- ")) t = t.substring(2).strip(); if (t.contains("://") || t.startsWith("raw/")) rows.add(new String[]{String.valueOf(++n), t}); }
        }
        for (String[] row : rows) {
            String rest = row[1];
            // a URL, a raw capture, a shelved file by name, or a host and path a worker wrote without its scheme ("arxiv.org/html/2510.24011v1")
            java.util.regex.Matcher um = java.util.regex.Pattern.compile("(?:https?|file)://\\S+|raw/\\S+|(?<=^| )[^\\s]+\\.(?:md|pdf|txt|docx|pptx|odt|epub|html?)(?= |$)|(?<=^| )(?:[a-z0-9-]+\\.)+[a-z]{2,}/\\S*").matcher(rest);
            if (!um.find()) continue;
            String locator = um.group().replaceAll("[),.;]+$", "");
            String head = rest.substring(0, um.start()).strip();
            if (head.endsWith("—")) head = head.substring(0, head.length() - 1).strip();
            String tail = rest.substring(um.end());
            ObjectNode o = M.createObjectNode();
            o.put("n", Integer.parseInt(row[0]));
            o.put("locator", locator);
            // the head is an edition citation (authors, a journal, a year) or the page's title; a citation carries a year
            boolean isEdition = head.matches(".*\\b(1[5-9]\\d\\d|20\\d\\d)\\b.*") && (head.contains(",") || head.contains("("));
            o.put("title", isEdition ? "" : head);
            if (isEdition) o.put("edition", head); else o.putNull("edition");
            java.util.regex.Matcher pm = REF_PUBLISHED.matcher(tail);
            if (pm.find()) o.put("published", pm.group(1)); else o.putNull("published");
            boolean fetched = false;
            try { Path rp = RawCapture.find(store, locator); fetched = rp != null && org.researchzosho.tools.Fetch.wall(RawCapture.read(rp)[1], RawCapture.read(rp)[2]) == null; } catch (Exception ignored) { }
            o.put("fetched", fetched);
            String lang = langOf.get(locator);
            if (lang == null) for (var en : langOf.entrySet()) if (en.getKey().startsWith(locator) || locator.startsWith(en.getKey())) { lang = en.getValue(); break; }
            if (lang == null) o.putNull("language"); else o.put("language", lang);
            java.util.regex.Matcher sm = REF_SAME.matcher(tail);
            if (sm.find()) o.put("same_as", Integer.parseInt(sm.group(1))); else o.putNull("same_as");
            o.put("tier", SourceTier.of(locator).name());
            SourceRules.Rule rule = rules.ruleFor(locator);
            if (rule != null) o.put("rule", rule.kind()); else o.putNull("rule");
            o.put("why", "reference [" + row[0] + "] of the write-up");
            out.add(o);
        }
        return out;
    }

    ObjectNode entry(Finding f, boolean full) throws IOException {
        ObjectNode e = M.createObjectNode();
        e.put("id", f.id()); e.put("kind", "finding"); e.put("state", f.state().name());
        e.put("claim_type", f.claimType().name()); e.put("confidence", f.confidence().name());
        e.put("writer", f.writer()); e.put("recorded_at", f.recordedAt()); e.put("valid_as_of", f.validAsOf());
        e.put("volatility", f.volatility().name());
        if (!f.reviewBy().isEmpty()) e.put("review_by", f.reviewBy());
        e.put("title", f.title());
        e.put("body", f.body().strip());
        ArrayNode subs = e.putArray("subjects");
        for (String s : f.subjects()) subs.add(s);
        ArrayNode srcs = e.putArray("sources");
        SourceRules rules = SourceRules.load(store);
        List<String> locs = new ArrayList<>();
        for (var s : f.sources()) {
            ObjectNode o = srcs.addObject(); o.put("locator", s.locator()); edition(o, s.edition()); o.put("why", s.whyItMatters());
            // what the library knows about the source itself: its kind, the person's rule on it, and the date the page says it was published
            o.put("tier", SourceTier.of(s.locator()).name());
            SourceRules.Rule rule = rules.ruleFor(s.locator());
            if (rule != null) o.put("rule", rule.kind()); else o.putNull("rule");
            String published = "";
            try { Path rp = rawFor(s.locator()); if (rp != null) published = RawCapture.published(rp); } catch (Exception ignored) { }
            if (published.isEmpty()) o.putNull("published"); else o.put("published", published);
            locs.add(s.locator());
        }
        // how many sources stand behind the claim once copies of one text are counted once
        e.put("independent_sources", locs.isEmpty() ? 0 : Independence.independent(store, locs));
        // when the inventory last read the claim against its source, and what it found
        String lc = Inventory.lastChecks(store).get(f.id());
        if (lc == null) e.putNull("last_checked");
        else { String[] q = lc.split("\t", 2); ObjectNode c = e.putObject("last_checked"); c.put("at", q[0]); c.put("verdict", q.length > 1 ? q[1] : ""); }
        ArrayNode sup = e.putArray("supersedes");
        for (String s : f.supersedes()) sup.add(s);
        if (f.triple() != null) {
            ObjectNode t = e.putObject("triple");
            t.put("subject", f.triple().subject()); t.put("predicate", f.triple().predicate()); t.put("object", f.triple().object());
        } else e.putNull("triple");
        ArrayNode ns = e.putArray("notes");   // meta-facts about this finding
        for (var n : f.notes()) {
            ObjectNode o = ns.addObject(); o.put("kind", n.kind()); o.put("by", n.by()); o.put("date", n.date()); o.put("text", n.text());
        }
        if (full) {
            ArrayNode by = e.putArray("superseded_by");
            for (Finding g : store.scanFindings().findings()) if (g.supersedes().contains(f.id())) by.add(g.id());
            ArrayNode rel = e.putArray("related");
            for (var n : Related.of(store, f, 4)) {
                ObjectNode o = rel.addObject(); o.put("id", n.id());
                ArrayNode sh = o.putArray("shared"); for (String s : n.shared()) sh.add(s);
            }
        }
        if (f.review() != null) {
            ObjectNode rv = e.putObject("review");
            rv.put("round", f.review().round()); rv.put("reviewer", f.review().reviewer());
            rv.put("decision", f.review().decision()); rv.put("at", f.review().at());
            rv.put("stale", f.reviewStale());
        } else {
            e.putNull("review");
        }
        return e;
    }

    // ---- routing ----

    record Route(String kind, String arg) { }

    static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("^\\s*([FIA]-\\d{4}-[a-z0-9-]+)\\s*$");
    static final java.util.regex.Pattern URL = java.util.regex.Pattern.compile("^\\s*(https?://\\S+)\\s*$");
    static final java.util.regex.Pattern SINCE_DATE = java.util.regex.Pattern.compile("(?i)\\bsince\\s+(\\d{4}-\\d{2}-\\d{2})");
    static final java.util.regex.Pattern LAST_DAYS = java.util.regex.Pattern.compile("(?i)\\b(?:last|past)\\s+(\\d{1,3})\\s+days?\\b");
    static final java.util.regex.Pattern CHANGED = java.util.regex.Pattern.compile("(?i)\\b(what(?:'s| has| is)? (?:changed|new)|recent(?:ly)? (?:added|changed)|new since|変更|更新|最近)\\b|変更|更新|最近");

    /** Deterministic intent: id · locator · changes · subject · search. */
    Route route(String q) throws IOException {
        var m = ID.matcher(q);
        if (m.matches()) return new Route("id", m.group(1));
        m = URL.matcher(q);
        if (m.matches()) return new Route("locator", m.group(1));
        m = SINCE_DATE.matcher(q);
        if (m.find()) return new Route("changes", m.group(1));
        m = LAST_DAYS.matcher(q);
        if (m.find()) return new Route("changes", java.time.LocalDate.now().minusDays(Integer.parseInt(m.group(1))).toString());
        if (CHANGED.matcher(q).find()) return new Route("changes", java.time.LocalDate.now().minusDays(7).toString());
        String subject = subjectNamed(q);
        if (subject != null) return new Route("subject", subject);
        return new Route("search", q);
    }

    /** A subject or facet the question names outright — "everything on japanese--keigo", "keigo shelf", or the bare slug. */
    String subjectNamed(String q) throws IOException {
        if (!Files.exists(store.subjectsFile())) return null;
        String lower = q.toLowerCase(Locale.ROOT).strip();
        String bare = lower.replaceAll("^(everything|all|what do (?:you|we) (?:have|hold)) (?:on|about) ", "").replaceAll(" (shelf|subject)$", "").strip();
        for (String line : Files.readAllLines(store.subjectsFile(), StandardCharsets.UTF_8)) {
            if (!line.startsWith("- ")) continue;
            String slug = line.substring(2).split(" — ", 2)[0].strip().toLowerCase(Locale.ROOT);
            if (bare.equals(slug) || bare.equals(slug.replace("--", " ")) || bare.equals(slug.replace("--", " ").replace("-", " "))) return slug;
        }
        return null;
    }

    // ---- helpers ----

    private static List<String> ids(ArrayNode entries) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : entries) if (e.hasNonNull("id")) out.add(e.get("id").asText());
        return out;
    }

    /** The on-disk convention for "no edition" is {@code n/a}; the wire sends null (a patron prints editions in citations). */
    private static void edition(ObjectNode o, String edition) {
        if (edition == null || edition.isBlank() || "n/a".equalsIgnoreCase(edition.strip())) o.putNull("edition");
        else o.put("edition", edition);
    }

    static String reqStr(JsonNode args, String key) {
        JsonNode v = args == null ? null : args.get(key);
        if (v == null || v.isNull() || v.asText().isBlank()) throw ProtocolError.invalidArgs("The argument '" + key + "' is required.");
        return v.asText();
    }

    private static int cursor(JsonNode args) {
        if (!args.hasNonNull("cursor") || args.get("cursor").asText().isBlank()) return 0;
        try { return Math.max(0, Integer.parseInt(args.get("cursor").asText())); }
        catch (NumberFormatException e) { throw ProtocolError.invalidArgs("The cursor is not one this library issued."); }
    }

    String kindOf(String id) throws IOException {
        if (!LibraryStore.safeName(id)) return "";
        if (id.startsWith("F-")) return "finding";
        if (id.startsWith("I-")) return "investigation";
        if (id.startsWith("A-")) return "article";
        Path r = LibraryStore.under(store.rawDir(), id);
        if (r != null && Files.exists(r)) return "raw";
        r = LibraryStore.under(store.rawDir(), id + ".md");
        if (r != null && Files.exists(r)) return "raw";
        return "";
    }

    private List<String> subjectsOf(String id, String kind) throws IOException {
        if ("finding".equals(kind)) { Finding f = store.finding(id); return f == null ? List.of() : f.subjects(); }
        if ("article".equals(kind)) {
            Path p = LibraryStore.under(store.articlesDir(), id + ".md");
                if (p == null) return null;
            if (Files.exists(p)) { String s = articleMeta(p).get("subject"); if (s != null) return List.of(s); }
        }
        return List.of();
    }

    /** The captured document for a locator: raw/<file>, a bare file name, or the URL it was fetched from (newest capture wins). */
    Path rawFor(String locator) throws IOException { return RawCapture.find(store, locator); }

    private static String headField(Path p, String prefix) throws IOException {
        try (var lines = Files.lines(p, StandardCharsets.UTF_8)) {
            return lines.limit(8).filter(l -> l.startsWith(prefix)).map(l -> l.substring(prefix.length()).strip()).findFirst().orElse("");
        } catch (java.io.UncheckedIOException e) {
            return "";
        }
    }
    private static String capturedAt(Path p) throws IOException { return headField(p, "fetched_at: "); }
    private static String fetchedBy(Path p) throws IOException { return headField(p, "fetched_by: "); }

    static Map<String, String> articleMeta(Path p) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        String text = Files.readString(p, StandardCharsets.UTF_8);
        if (!text.startsWith("---\n")) return m;
        int end = text.indexOf("\n---", 4);
        if (end < 0) return m;
        for (String line : text.substring(4, end).split("\n")) {
            int c = line.indexOf(':');
            if (c > 0) m.put(line.substring(0, c).strip(), line.substring(c + 1).strip());
        }
        return m;
    }

    private static List<String> listOf(String inline) {
        List<String> out = new ArrayList<>();
        if (inline == null) return out;
        String s = inline.strip();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        for (String item : s.split(",")) if (!item.isBlank()) out.add(item.strip());
        return out;
    }

    /**
     * The OPEN questions, in queue order: a line the explorer researched or a person dropped carries an {@code ⇒ explored}
     * mark and is not open. Each carries its {@code type}, whether it is {@code parked}, its {@code position},
     * {@code tonight} when the explorer's next run takes it, and the facets a person filters on: the report that left it
     * ({@code report}, {@code report_title}, {@code report_fate}: kept, waiting, disputed, retired, none), the
     * {@code perspective} it was asked from, its {@code subjects}, its {@code language}, and {@code similar} (the first
     * question of a group that reads alike, with {@code similar_n}). With {@code hints}, {@code answered} names the
     * accepted or draft claim that already answers it, when the shelves hold one (a search per question).
     */
    List<ObjectNode> frontierList() throws IOException { return frontierList(false); }

    List<ObjectNode> frontierList(boolean hints) throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        List<Frontier.Line> open = new ArrayList<>();
        for (Frontier.Line l : Frontier.read(store)) if (l.open()) open.add(l);
        java.util.Set<String> tonight = new java.util.HashSet<>();
        for (Crews.Bundle b : Crews.plan(open.stream().filter(Frontier.Line::researchable).toList(), Crews.explorerBudget())) for (Frontier.Line l : b.all()) tonight.add(l.text());
        Map<String, List<Frontier.Line>> alike = Frontier.similar(open);
        Map<String, String> headOf = new HashMap<>();
        for (var e : alike.entrySet()) for (Frontier.Line l : e.getValue()) headOf.put(l.text(), e.getKey());
        Map<String, Investigation> reports = new HashMap<>();
        Map<String, String> fates = new HashMap<>();
        Map<String, List<String>> reportSubjects = new HashMap<>();
        Vocabulary vocab = Vocabulary.read(store.subjectsFile());
        LibrarianIndex index = hints && !open.isEmpty() ? new LibrarianIndex(store) : null;
        int pos = 0;
        for (Frontier.Line l : open) {
            ObjectNode o = M.createObjectNode();
            o.put("date", l.date()); o.put("kind", l.kind()); o.put("text", l.text());
            o.put("type", l.type()); o.put("parked", l.parked()); o.put("position", ++pos); o.put("tonight", tonight.contains(l.text()));
            if (l.type().equals("asked")) o.put("asked", Frontier.asks(l));
            String origin = l.origin();
            List<String> subjects = new ArrayList<>();
            if (origin != null) {
                Investigation inv = reports.computeIfAbsent(origin, id -> { try { return store.investigation(id); } catch (IOException e) { return null; } });
                o.put("report", origin);
                if (inv != null) o.put("report_title", inv.title());
                o.put("report_fate", fates.computeIfAbsent(origin, id -> fate(inv)));
                subjects.addAll(reportSubjects.computeIfAbsent(origin, id -> subjectsOf(inv)));
            }
            for (String slug : subjectsInText(vocab, Frontier.bare(l.text()))) if (!subjects.contains(slug)) subjects.add(slug);
            ArrayNode sj = o.putArray("subjects"); for (String x : subjects) sj.add(x);
            String who = Frontier.perspective(l.text());
            if (who != null) o.put("perspective", who);
            o.put("language", Frontier.language(l.text()));
            String head = headOf.get(l.text());
            if (head != null) { o.put("similar", head); o.put("similar_n", alike.get(head).size()); }
            if (index != null) {
                var hit = answeredBy(index, Frontier.bare(l.text()));
                if (hit != null) { ObjectNode a = o.putObject("answered"); a.put("id", hit.id()); a.put("title", hit.title()); a.put("state", hit.state()); }
            }
            out.add(o);
        }
        return out;
    }

    /**
     * library_inbox: the claims waiting for a decision — drafts, and accepted claims whose review went stale — with the
     * facets a person sorts them by, and the decisions themselves. op=list takes the filters; op=accept, dispute (with
     * {@code why}) and retire take {@code ids[]} (or one {@code id}), or {@code report} for every waiting claim of one
     * investigation, at write access. A decision is signed "person" as at the command line: the patron is acting for
     * the keeper, and the audit trail names them in {@code decided_by}.
     */
    public ObjectNode inbox(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("list").toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        switch (op) {
            case "list" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                Map<String, String> f = new HashMap<>();
                for (String k : List.of("report", "subject", "kind", "tier", "confidence", "writer", "state", "language", "q")) if (args.hasNonNull(k)) f.put(k, args.path(k).asText());
                ArrayNode items = r.putArray("items");
                for (var o : inboxList()) if (inboxMatches(o, f)) items.add(o);
            }
            case "accept", "dispute", "retire" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                List<String> ids = new ArrayList<>();
                for (var x : args.path("ids")) if (!x.asText("").isBlank()) ids.add(x.asText());
                if (args.hasNonNull("id") && !args.path("id").asText().isBlank()) ids.add(args.path("id").asText());
                String report = args.path("report").asText("");
                if (!report.isEmpty()) for (var o : inboxList()) if (o.path("report").asText("").startsWith(report)) ids.add(o.path("id").asText());
                if (ids.isEmpty()) throw ProtocolError.invalidArgs("Name what to decide: ids[], id, or report.");
                String why = args.path("why").asText("").strip();
                if (op.equals("dispute") && why.isEmpty()) throw ProtocolError.invalidArgs("A dispute needs why.");
                Council c = new Council(store);
                ArrayNode decided = r.putArray("decided");
                for (String id : new java.util.LinkedHashSet<>(ids)) {
                    if (store.finding(id) == null) throw ProtocolError.notFound("No claim " + id);
                    Finding f = switch (op) { case "accept" -> c.accept(id); case "retire" -> c.retire(id); default -> c.dispute(id, why); };
                    ObjectNode d = decided.addObject(); d.put("id", f.id()); d.put("state", f.state().name()); d.put("title", f.title());
                }
                r.put("decision", op.equals("accept") ? "accepted" : op.equals("retire") ? "retired" : "disputed");
                r.put("decided_by", patron.writer());
            }
            default -> throw ProtocolError.invalidArgs("op must be list, accept, dispute or retire.");
        }
        return r;
    }

    /**
     * The inbox rows with their facets: {@code id, title, state, stale, kind, tier, confidence, writer, date, subjects[],
     * language, sources}, and the report the claim came from ({@code report}, {@code report_title}) when one did.
     * Oldest first, as the Council lists them.
     */
    List<ObjectNode> inboxList() throws IOException {
        Map<String, Investigation> byFinding = new HashMap<>();
        for (Path ip : sorted(store.investigationsDir(), ".md")) {
            Investigation inv; try { inv = store.investigation(stem(ip)); } catch (Exception e) { continue; }
            if (inv != null) for (String fid : inv.findings()) byFinding.putIfAbsent(fid, inv);
        }
        List<ObjectNode> out = new ArrayList<>();
        for (Council.Row row : new Council(store).inbox()) {
            Finding f = store.finding(row.id());
            if (f == null) continue;
            ObjectNode o = M.createObjectNode();
            o.put("id", f.id()); o.put("title", f.title()); o.put("state", f.state().name()); o.put("stale", row.stale());
            o.put("kind", f.claimType().name()); o.put("tier", row.tier().name()); o.put("confidence", f.confidence().name());
            o.put("writer", f.writer()); o.put("date", f.recordedAt().length() >= 10 ? f.recordedAt().substring(0, 10) : f.recordedAt());
            ArrayNode sj = o.putArray("subjects"); for (String x : f.subjects()) sj.add(x);
            o.put("language", Frontier.language(f.title()));
            o.put("sources", f.sources().size());
            Investigation inv = byFinding.get(f.id());
            if (inv != null) { o.put("report", inv.id()); o.put("report_title", inv.title()); }
            out.add(o);
        }
        return out;
    }

    /**
     * Whether an inbox row passes a filter: {@code report} (an id or its prefix), {@code subject}, {@code kind} (the claim
     * type), {@code tier}, {@code confidence}, {@code writer} (matched as text), {@code state} (draft or stale),
     * {@code language}, {@code q} (words that must all appear in the title). Empty or missing keys match everything.
     */
    static boolean inboxMatches(ObjectNode o, Map<String, String> f) {
        String report = f.getOrDefault("report", "");
        if (!report.isEmpty() && !o.path("report").asText("").startsWith(report)) return false;
        String subject = f.getOrDefault("subject", "");
        if (!subject.isEmpty()) { boolean has = false; for (var x : o.path("subjects")) if (x.asText().equals(subject)) has = true; if (!has) return false; }
        for (String k : List.of("kind", "tier", "confidence", "language")) { String v = f.getOrDefault(k, ""); if (!v.isEmpty() && !o.path(k).asText("").equals(v)) return false; }
        String writer = f.getOrDefault("writer", "");
        if (!writer.isEmpty() && !o.path("writer").asText("").toLowerCase(Locale.ROOT).contains(writer.toLowerCase(Locale.ROOT))) return false;
        String state = f.getOrDefault("state", "");
        if (state.equals("stale") && !o.path("stale").asBoolean()) return false;
        if (state.equals("draft") && o.path("stale").asBoolean()) return false;
        String q = f.getOrDefault("q", "").strip().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) { String t = o.path("title").asText().toLowerCase(Locale.ROOT); for (String w : q.split("\\s+")) if (!t.contains(w)) return false; }
        return true;
    }

    /** What became of the report that left a question: kept (a claim of it was accepted), waiting (its claims sit in the inbox), disputed, retired, or none. */
    String fate(Investigation inv) {
        if (inv == null) return "gone";
        if (inv.findings().isEmpty()) return "none";
        boolean accepted = false, draft = false, disputed = false;
        for (String id : inv.findings()) {
            Finding f; try { f = store.finding(id); } catch (IOException e) { f = null; }
            if (f == null) continue;
            switch (f.state()) { case accepted -> accepted = true; case draft -> draft = true; case disputed -> disputed = true; default -> { } }
        }
        return accepted ? "kept" : draft ? "waiting" : disputed ? "disputed" : "retired";
    }

    private List<String> subjectsOf(Investigation inv) {
        List<String> out = new ArrayList<>();
        if (inv == null) return out;
        for (String id : inv.findings()) {
            try { Finding f = store.finding(id); if (f != null) for (String sj : f.subjects()) if (!out.contains(sj)) out.add(sj); } catch (IOException ignored) { }
        }
        return out;
    }

    /** Subjects named in a question: a vocabulary slug, its spaced form, or one of its other names appears in the text (four letters or more). */
    static List<String> subjectsInText(Vocabulary vocab, String text) {
        List<String> out = new ArrayList<>();
        if (vocab == null || vocab.isEmpty()) return out;
        String t = " " + Vocabulary.norm(text).replaceAll("[^\\p{L}\\p{N}]+", " ") + " ";
        for (Vocabulary.Term term : vocab.terms().values()) {
            List<String> names = new ArrayList<>(term.also());
            names.add(term.slug().replace("--", " ").replace('-', ' '));
            for (String n : names) {
                String nn = Vocabulary.norm(n).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
                if (nn.length() < 4 && !nn.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) continue;
                if (nn.isEmpty()) continue;
                if (t.contains(" " + nn + " ") || (nn.length() >= 4 && nn.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) && t.contains(nn))) { out.add(term.slug()); break; }
            }
        }
        return out;
    }

    /**
     * The accepted or draft claim that may already answer a question: among the floored search's top hits, the one whose
     * TITLE shares enough words with the question. The floor alone is too loose — on a live shelf it named a claim for 35
     * of 40 questions, a third of them about something else (2026-09-09); a title overlap of 0.08 kept the ones a person
     * would want to read first and dropped those.
     */
    static final double ANSWERED_OVERLAP = 0.08;

    private static LibrarianIndex.Hit answeredBy(LibrarianIndex index, String question) {
        try {
            LibrarianIndex.Hit best = null; double bestOverlap = 0;
            var qt = Frontier.terms(question);
            for (var h : index.searchStrict(question, 3, null, "finding")) {
                if (!"accepted".equals(h.state()) && !"draft".equals(h.state())) continue;
                double o = Frontier.jaccard(qt, Frontier.terms(h.title()));
                if (o >= ANSWERED_OVERLAP && o > bestOverlap) { best = h; bestOverlap = o; }
            }
            return best;
        } catch (IOException ignored) { }
        return null;
    }

    /**
     * Whether a listed question passes a filter: {@code type}, {@code show} (queued, parked, all), {@code report}
     * (an investigation id, or its prefix), {@code fate}, {@code who} (a perspective, matched as text), {@code subject},
     * {@code language}, {@code q} (words that must all appear). Empty or missing keys match everything.
     */
    static boolean matches(ObjectNode o, Map<String, String> f) {
        String type = f.getOrDefault("type", "");
        if (!type.isEmpty() && !o.path("type").asText().equals(type)) return false;
        String show = f.getOrDefault("show", "queued");
        if (show.equals("queued") && o.path("parked").asBoolean()) return false;
        if (show.equals("parked") && !o.path("parked").asBoolean()) return false;
        String report = f.getOrDefault("report", "");
        if (!report.isEmpty() && !o.path("report").asText("").startsWith(report)) return false;
        String fate = f.getOrDefault("fate", "");
        if (!fate.isEmpty() && !o.path("report_fate").asText("").equals(fate)) return false;
        String who = f.getOrDefault("who", "");
        if (!who.isEmpty() && !o.path("perspective").asText("").toLowerCase(Locale.ROOT).contains(who.toLowerCase(Locale.ROOT))) return false;
        String subject = f.getOrDefault("subject", "");
        if (!subject.isEmpty()) { boolean has = false; for (var x : o.path("subjects")) if (x.asText().equals(subject)) has = true; if (!has) return false; }
        String lang = f.getOrDefault("language", "");
        if (!lang.isEmpty() && !o.path("language").asText("").equals(lang)) return false;
        String q = f.getOrDefault("q", "").strip().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) { String t = o.path("text").asText().toLowerCase(Locale.ROOT); for (String w : q.split("\\s+")) if (!t.contains(w)) return false; }
        return true;
    }

    private static boolean touches(String text, String question) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 4 && lower.contains(w)) return true;
        }
        return false;
    }

    private static ObjectNode resource(String uri, String name, String mime) {
        ObjectNode o = M.createObjectNode();
        o.put("uri", uri); o.put("name", name); o.put("mimeType", mime);
        return o;
    }

    private static List<Path> sorted(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        }
    }
    private static int countFiles(Path dir, String suffix) throws IOException { return sorted(dir, suffix).size(); }
    private static String stem(Path p) { String n = p.getFileName().toString(); return n.endsWith(".md") ? n.substring(0, n.length() - 3) : n; }

    private String lastUpdated() throws IOException {
        Instant latest = Instant.EPOCH;
        for (Path dir : List.of(store.findingsDir(), store.investigationsDir(), store.articlesDir(), store.rawDir())) {
            for (Path p : sorted(dir, ".md")) {
                Instant t = Files.getLastModifiedTime(p).toInstant();
                if (t.isAfter(latest)) latest = t;
            }
        }
        return latest.equals(Instant.EPOCH) ? "" : latest.toString();
    }
}
