package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.Version;
import org.researchzosho.librarian.profiles.YoutubeProfile;
import org.researchzosho.tools.DocText;
import org.researchzosho.tools.MyShelfTool;
import org.researchzosho.librarian.dna.Browse;
import org.researchzosho.librarian.dna.Dna;
import org.researchzosho.librarian.dna.PodcastDna;
import org.researchzosho.librarian.dna.Pool;
import org.researchzosho.librarian.dna.DnaCard;
import org.researchzosho.librarian.dna.Labels;
import org.researchzosho.librarian.dna.LikeX;
import org.researchzosho.librarian.dna.Prefs;
import org.researchzosho.librarian.dna.Reactions;
import org.researchzosho.librarian.dna.RecordFill;
import org.researchzosho.librarian.dna.ShelfDna;
import org.researchzosho.tools.Recalls;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.PageCheck;
import org.researchzosho.tools.Podcasts;
import java.util.function.BiFunction;
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
            throw ProtocolError.unavailable("This machine has no library yet. Create one with `researchzosho init`.");
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
        ObjectNode r = askUncapped(args);
        // a caller with a small window asks for at most max_chars of entry text; each entry gets an even share, cut with a word that says so
        int max = args.path("max_chars").asInt(0);
        if (max < 0) throw ProtocolError.invalidArgs("max_chars must be zero or a positive number.");
        if (max > 0 && r.path("entries").isArray() && r.path("entries").size() > 0) {
            ArrayNode entries = (ArrayNode) r.get("entries");
            int share = Math.max(200, max / entries.size());
            for (JsonNode e : entries) {
                if (!(e instanceof ObjectNode o) || !o.hasNonNull("body")) continue;
                String body = o.get("body").asText();
                if (body.length() <= share) continue;
                o.put("body", body.substring(0, share).stripTrailing() + " …");
                o.put("truncated", true);
            }
            r.put("max_chars", max);
        }
        return r;
    }

    private ObjectNode askUncapped(JsonNode args) throws IOException {
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
        List<LibrarianIndex.Hit> all = new LibrarianIndex(store).search(query, offset + k + 1, subject);
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
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String id = reqStr(args, "id");
        if (!LibraryStore.safeName(id)) throw ProtocolError.invalidArgs("An id is an entry name (F-…, I-…, A-…, or a saved page's file name), not a path.");
        String section = args.path("section").asText("").strip();
        int offset = args.path("offset").asInt(0);
        int max = args.path("max_chars").asInt(0);
        if (offset < 0 || max < 0) throw ProtocolError.invalidArgs("offset and max_chars must be zero or a positive number.");
        ObjectNode r = envelope();
        ObjectNode e = entry(id, kindOf(id), true);
        if (e == null) throw ProtocolError.notFound("No entry has the id " + id + ".");
        // format=claims: a report's claims as records — each with its source, confidence, state and what the checks made of it — for a
        // caller that folds a run into a table without parsing prose (0.5.4)
        if ("claims".equalsIgnoreCase(args.path("format").asText(""))) {
            if (!"investigation".equals(e.path("kind").asText())) throw ProtocolError.invalidArgs("format=claims is for a report (I-…); " + id + " is " + e.path("kind").asText() + ".");
            r.put("id", id); r.put("title", e.path("title").asText());
            ArrayNode claims = r.putArray("claims");
            for (JsonNode fid : e.path("findings")) {
                Finding f = store.finding(fid.asText());
                if (f == null) continue;
                ObjectNode c = claims.addObject();
                c.put("id", f.id()); c.put("title", f.title()); c.put("text", f.body().strip());
                c.put("confidence", f.confidence().name()); c.put("state", f.state().name());
                ArrayNode src = c.putArray("sources");
                for (Finding.Source so : f.sources()) { ObjectNode o = src.addObject(); o.put("locator", so.locator()); if (so.edition() != null && !so.edition().isEmpty()) o.put("edition", so.edition()); }
                c.put("check", checkOf(f));
            }
            r.put("count", claims.size());
            return r;
        }
        String in = args.path("in").asText("").strip();
        if (!in.isEmpty()) {   // a report read in another language (0.7.0): the translation kept when current, else made now by the library's model; one written in that language as it is
            if (!"investigation".equals(e.path("kind").asText())) throw ProtocolError.invalidArgs("in=<language> reads a report (I-…) in that language; " + id + " is " + e.path("kind").asText() + ".");
            String written = Translate.writtenIn(store.investigation(id));
            e.put("written_in", written);
            if (!Translate.code(in).equals(written)) {
                Translate.Translated t = Translate.reportIn(store, id, in, Translate.DRIVE.get());
                e.put("body", t.markdown()); e.put("title", t.words().getOrDefault(e.path("title").asText(""), e.path("title").asText("")));
                e.put("read_in", t.lang()); e.put("translated_fresh", t.fresh());
            } else e.put("read_in", written);
        }
        // A record can run past what a tool call may carry (an investigation of 150,000 characters, measured 2026-09-11):
        // a caller asks for one section, or a window of the body, and gets the whole list of sections either way.
        String body = e.path("body").asText("");
        if (!section.isEmpty()) {
            String cut = sectionOf(body, section);
            if (cut == null) throw ProtocolError.invalidArgs("No section \"" + section + "\" in " + id + ". The sections are: "
                    + String.join(", ", sectionHeadings(body)) + (sectionHeadings(body).isEmpty() ? "(none: the body has no headings)" : "") + ". \"answer\" is the report without the sections the research run added.");
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

    /**
     * What the run's checks made of a claim, read from the marks they left in its text: {@code not supported} (its cited source does not
     * say it), {@code source not read}, {@code number unbacked} (a number no note or source states), {@code cited} (it names a source and
     * no check marked it), or {@code unchecked} (no source named).
     */
    static String checkOf(Finding f) {
        String b = f.body() == null ? "" : f.body();
        if (b.contains("[not supported by the cited source on check]")) return "not supported";
        if (b.contains("[cites a source that was not read this run]")) return "source not read";
        if (b.contains("[number not in any note or source read this run")) return "number unbacked";
        return f.sources() == null || f.sources().isEmpty() ? "unchecked" : "cited";
    }

    /** The sections the harness itself appends to a write-up; "answer" is everything else. */
    static final Set<String> HARNESS_SECTIONS = Set.of("question", "sources", "caveats", "cite-check", "coverage check", "evidence",
            "references", "languages of the sources", "web search", "source requests", "worker findings", "sources cited", "answer (as submitted", "declined");
    /** Harness headings matched only exactly: a writer's own "Declined applications" or "Declines in trade" is the answer's. */
    static final Set<String> EXACT_HARNESS_SECTIONS = Set.of("declined");

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
                for (String x : HARNESS_SECTIONS) if (h.equals(x) || !EXACT_HARNESS_SECTIONS.contains(x) && (h.startsWith(x + " ") || h.startsWith(x + " (") || (x.endsWith("(") && h.startsWith(x)))) { harness = true; break; }
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
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String locator = reqStr(args, "locator");
        int max = args.path("max_chars").asInt(20_000);
        if (max <= 0) throw ProtocolError.invalidArgs("max_chars must be a positive number.");
        Path p = rawFor(locator);
        if (p == null) throw ProtocolError.notFound("No saved page matches " + locator + ".");
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
            if (!mode.equals("keep")) throw ProtocolError.invalidArgs("A url is always kept, because web pages change. Use mode=keep.");
            Object[] got;
            try { got = Corpus.addUrl(store, url, collection); }
            catch (PageCheck.NotKept notKept) { throw notKept.unchecked() ? ProtocolError.unavailable(notKept.getMessage()) : ProtocolError.invalidArgs(notKept.getMessage()); }
            catch (Exception e) { throw ProtocolError.unavailable("Could not read " + url + ": " + e.getMessage()); }
            Path raw = (Path) got[0];
            if (raw == null) throw ProtocolError.unavailable("The page was read but not saved to the library (no library, or the page was refused).");
            r.put("kind", "url"); r.put("mode", "keep"); r.put("url", url); r.put("title", String.valueOf(got[1])); r.put("format", String.valueOf(got[2]));
            r.put("raw", raw.getFileName().toString());
            notChecked(r, got.length > 3 && Boolean.TRUE.equals(got[3]));
            if (!collection.isEmpty()) r.put("collection", collection);
            r.put("added", 1);
            store.circulate("add", patron.label() + " :: " + url);
            return r;
        }
        Path target = Path.of(path).toAbsolutePath().normalize();
        // paths are the keeper's: the terminal, or the pages while they are open (the same rule Patrons.check applies to a browser); a file the browser posted is the person's own, whoever they are
        if (!Uploads.isUpload(target) && !patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library owner can add files and folders on this machine, from the terminal or the library's own pages. A url can be added from here, and a file of your own on the Add page.");
        if (!Files.exists(target)) throw ProtocolError.notFound("Nothing is at " + target + ". Is the drive mounted?");
        if (Files.isRegularFile(target) && Archives.isArchive(target.getFileName().toString())) {
            // an archive: unpacked into a folder of the library's own, shelved as a folder is, the folder removed after; the same rules as a folder posted from the browser
            if (!mode.equals("keep")) throw ProtocolError.invalidArgs("An archive is always kept: its files are unpacked into a folder of the library's own, which is removed after. link and survey are for a folder on this machine.");
            Archives.Added ad = Archives.add(store, target, collection);
            if (ad.outcome() == null) throw ProtocolError.invalidArgs(ad.line());
            Corpus.Outcome o = ad.outcome();
            r.put("kind", "archive"); r.put("path", target.toString()); r.put("collection", ad.collection()); r.put("mode", "keep");
            r.put("unpacked", ad.unpacked().files()); r.put("left_out", ad.unpacked().leftOut().size());
            r.put("seen", o.seen()); r.put("added", o.added()); r.put("unchanged", o.unchanged()); r.put("changed", o.changed()); r.put("skipped", o.skipped());
            ArrayNode pr = r.putArray("problems"); for (String x : ad.problems()) pr.add(x);
            r.put("summary", ad.line());
            r.put("next", "Research it with library_research {sources: \"shelves\", collections: [\"" + ad.collection() + "\"]}");
            store.circulate("add", patron.label() + " :: " + target + " (archive)");
            return r;
        }
        if (Files.isDirectory(target)) {
            if (collection.isEmpty()) collection = Corpus.nameFor(target);
            r.put("kind", "folder"); r.put("path", target.toString()); r.put("collection", collection); r.put("mode", mode);
            if (mode.equals("survey")) {
                Corpus.Survey sv = Corpus.survey(store, target, true);
                r.put("files", sv.files()); r.put("bytes", sv.bytes()); r.put("text_bytes_if_kept", sv.textBytes()); r.put("disk_free_bytes", sv.freeBytes()); r.put("unreadable_sampled", sv.unreadable());
                ObjectNode bt = r.putObject("by_type"); sv.byType().forEach(bt::put);
                r.put("summary", sv.line());
                r.put("next", "Add it with mode=keep (the text is copied into the library) or mode=link (read in place, nothing copied).");
                return r;
            }
            Corpus.Outcome o = Corpus.addFolder(store, target, collection, true, mode.equals("link"));
            if (register) Corpus.register(store, collection, target, mode.equals("link"));
            r.put("seen", o.seen()); r.put("added", o.added()); r.put("unchanged", o.unchanged()); r.put("changed", o.changed()); r.put("skipped", o.skipped());
            r.put("registered", register);
            ArrayNode pr = r.putArray("problems"); for (String x : o.problems()) pr.add(x);
            r.put("summary", "Collection " + collection + ": " + o.line() + "." + (mode.equals("link") ? " The files were read in place. Nothing was copied." : "") + (register ? " The folder is registered. Nightly maintenance rescans it." : ""));
            r.put("next", "Research it with library_research {sources: \"shelves\", collections: [\"" + collection + "\"]}");
            store.circulate("add", patron.label() + " :: " + target + " (" + mode + ")");
            return r;
        }
        if (mode.equals("survey")) throw ProtocolError.invalidArgs("survey is for a folder. Add a single file with keep or link.");
        if (WatchHistory.looksLike(target)) {   // Google Takeout's YouTube watch history: a shelf of watched videos, read first when a remembered video is looked for
            int added = WatchHistory.add(store, target);
            r.put("kind", "watch-history"); r.put("path", target.toString()); r.put("added", added);
            r.put("summary", "Filed " + added + (added == 1 ? " watched video" : " watched videos") + " from the YouTube watch history. " + WatchHistory.summary(store) + " When you look for a video you remember, the library reads this history first.");
            store.circulate("add", patron.label() + " :: " + target + " (watch history)");
            return r;
        }
        Path raw = Corpus.addFile(store, target, collection, mode.equals("link"));
        if (raw == null) throw ProtocolError.unavailable("No text could be read from " + target.getFileName() + "." + Corpus.whyNoText());
        r.put("kind", "file"); r.put("path", target.toString()); r.put("mode", mode); r.put("raw", raw.getFileName().toString());
        r.put("title", RawCapture.read(raw)[1]);
        if (!collection.isEmpty()) r.put("collection", collection);
        r.put("added", 1);
        r.put("next", "Ask about it with library_ask, or research it with library_research {sources: \"shelves\"}");
        store.circulate("add", patron.label() + " :: " + target + " (" + mode + ")");
        return r;
    }

    /**
     * library_images: pictures as a capability (0.7.0, the first increment). op=find: candidates for {@code words} from Brave (with its key)
     * and the library's SearXNG, merged and told apart, at most {@code count}; with {@code criteria} each is judged by the seeing model;
     * with {@code keep} (write access) each is fetched onto the shelves under {@code collection}. op=keep: {@code images[]} (addresses, or
     * found[] objects) kept. op=describe: the seeing model's words about each of {@code images[]} (an address, a kept picture's file or
     * entry, a path from the keeper), with {@code question} answered after. op=judge: {@code criteria} against each of {@code images[]},
     * one typed verdict each. {@code unavailable} with the sentence that says how a seeing model is added when none is set.
     */
    public ObjectNode images(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("find").strip().toLowerCase(Locale.ROOT);
        boolean keep = args.path("keep").asBoolean(false) || op.equals("keep");
        Patrons.check(store, patron, keep ? Patrons.Level.write : Patrons.Level.read);
        boolean paths = patron.person() || (patron.web() && !WebAccess.signInRequired());
        String collection = args.path("collection").asText("").strip();
        ObjectNode r = envelope();
        r.put("op", op);
        switch (op) {
            case "find" -> {
                String words = args.path("words").asText(args.path("query").asText("")).strip();
                if (words.isEmpty()) throw ProtocolError.invalidArgs("Say what pictures to find: words.");
                int count = Math.min(Math.max(args.path("count").asInt(Images.DEFAULT_COUNT), 1), Images.MOST);
                String criteria = args.path("criteria").asText("").strip();
                ModelEntries.Entry eyes = criteria.isEmpty() ? null : ModelEntries.seeing();
                if (!criteria.isEmpty() && eyes == null) throw ProtocolError.unavailable(ModelEntries.noEyes());
                Images.Result found = Images.find(store, words, count);
                r.put("words", words); r.put("count", found.found().size());
                ArrayNode fs = r.putArray("found"); for (Images.Found f : found.found()) fs.add(f.json(M));
                ArrayNode engines = r.putArray("engines"); for (String e : found.answered()) engines.add(e);
                ArrayNode silent = r.putArray("silent"); for (String e : found.silent()) silent.add(e);
                Map<String, Images.Judged> verdicts = new LinkedHashMap<>();
                if (!criteria.isEmpty()) {
                    r.put("criteria", criteria); r.put("model", eyes.model());
                    ArrayNode vs = r.putArray("verdicts");
                    for (Images.Judged j : Images.judge(eyes, criteria, found.found())) { vs.add(j.json(M)); verdicts.put(j.found().image(), j); }
                }
                String summary = found.said(words);
                if (!criteria.isEmpty()) { long yes = verdicts.values().stream().filter(Images.Judged::yes).count(); summary += " Judged against \"" + criteria + "\" by " + eyes.name() + ": " + yes + " of " + verdicts.size() + (yes == 1 ? " shows" : " show") + " it."; }
                if (keep) summary += " " + keepAll(r, found.found(), collection, verdicts);
                r.put("summary", summary);
                r.put("next", found.found().isEmpty() ? "Try other words, or fewer." : keep ? "The kept pictures are entries on the shelves; library_search finds them by their words, and each is shown at its url." : "op=keep with images (the addresses found) puts them on the shelves; op=judge with criteria judges them; op=describe reads one.");
            }
            case "keep" -> {
                List<Images.Found> found = foundArgs(args);
                if (found.isEmpty()) throw ProtocolError.invalidArgs("keep needs images: the addresses of the pictures to keep, or the found[] objects of a find.");
                r.put("count", found.size());
                r.put("summary", keepAll(r, found, collection, Map.of()));
            }
            case "describe" -> {
                ModelEntries.Entry eyes = ModelEntries.seeing();
                if (eyes == null) throw ProtocolError.unavailable(ModelEntries.noEyes());
                List<String> refs = refs(args);
                if (refs.isEmpty()) throw ProtocolError.invalidArgs("describe needs images: a web address, a kept picture's file or entry" + (paths ? ", or a path on this machine" : "") + ".");
                String question = args.path("question").asText("").strip();
                r.put("model", eyes.model()); r.put("entry", eyes.name());
                ArrayNode ds = r.putArray("descriptions");
                int said = 0;
                for (String ref : refs) {
                    ObjectNode d = ds.addObject(); d.put("image", ref);
                    Images.Fetched got;
                    try { got = Images.bytesOf(store, ref, paths); } catch (IOException e) { d.put("description", ""); d.put("why", e.getMessage()); continue; }
                    String words = Images.describe(eyes, got.bytes(), got.contentType(), question);
                    d.put("description", words); if (words.isEmpty()) d.put("why", "the model gave no description"); else said++;
                }
                r.put("summary", said + " of " + refs.size() + (refs.size() == 1 ? " picture" : " pictures") + " described by " + eyes.name() + " (" + eyes.model() + ").");
            }
            case "judge" -> {
                String criteria = args.path("criteria").asText("").strip();
                if (criteria.isEmpty()) throw ProtocolError.invalidArgs("judge needs criteria: what the pictures should show, in words.");
                ModelEntries.Entry eyes = ModelEntries.seeing();
                if (eyes == null) throw ProtocolError.unavailable(ModelEntries.noEyes());
                List<Images.Found> found = foundArgs(args);
                if (found.isEmpty()) throw ProtocolError.invalidArgs("judge needs images: web addresses, kept pictures' files or entries" + (paths ? ", paths on this machine" : "") + ", or the found[] objects of a find.");
                r.put("criteria", criteria); r.put("model", eyes.model()); r.put("entry", eyes.name());
                ArrayNode vs = r.putArray("verdicts");
                int yes = 0;
                // with a reference, each picture is judged beside it: the same thing (mode same, criteria naming what: a person, a place) or like it in the aspect the criteria name (mode concept)
                Images.Reference ref = referenceArg(args, paths, eyes, false);
                String mode = args.path("mode").asText(Images.SAME).strip().toLowerCase(Locale.ROOT);
                boolean two = ref != null && Vision.seesTwo(eyes);
                if (ref != null) { r.set("reference", ref.json(M)); r.put("mode", Images.CONCEPT.equals(mode) ? Images.CONCEPT : Images.SAME); r.put("two_pictures", two); }
                for (Images.Found f : found) {
                    Images.Judged j; LikenessHelper.Measure mm = null;
                    try {
                        Images.Fetched got = f.image().matches("(?i)^https?://.+") ? Images.fetch(store, f) : Images.bytesOf(store, f.image(), paths);
                        if (!Images.looksAtable(got)) j = new Images.Judged(f, Images.Verdict.UNJUDGED, Images.notLookable(got), eyes.model(), criteria);   // an SVG, a PDF: said, not sent to the model
                        else j = ref == null ? Images.judge(eyes, criteria, f, got.bytes(), got.contentType()) : two ? Images.judgeAgainst(eyes, ref, mode, criteria, Images.nameOf(args.path("name").asText(args.path("words").asText("")), ref), f, got.bytes(), got.contentType()) : Images.judgeByWords(eyes, ref, mode, criteria, Images.nameOf(args.path("name").asText(args.path("words").asText("")), ref), f, got.bytes(), got.contentType());
                        if (ref != null && j.verdict() != Images.Verdict.UNJUDGED) { mm = Images.measure(ref, got.bytes(), got.contentType()); String nm = Images.nameOf(args.path("name").asText(args.path("words").asText("")), ref); j = Images.measured(j, mm, Images.CONCEPT.equals(mode) ? Images.CONCEPT : Images.SAME, nm != null || !Images.kindWord(criteria).isEmpty(), nm); }   // the helper's number first (0.7.0)
                    } catch (IOException e) { j = new Images.Judged(f, Images.Verdict.UNJUDGED, e.getMessage(), eyes.model(), criteria); }
                    if (j.yes()) yes++;
                    ObjectNode vj = j.json(M); if (mm != null) vj.set("likeness", mm.json(M)); vs.add(vj);
                }
                r.put("summary", "Judged against \"" + criteria + "\" by " + eyes.name() + " (" + eyes.model() + "): " + yes + " of " + found.size() + (yes == 1 ? " shows" : " show") + " it.");
                if (ref != null) r.put("likeness_helper", LikenessHelper.said());
            }
            case "like" -> {   // a picture as the ask (0.7.0): the reference described, the candidates found for its words and the person's, each judged beside it
                ModelEntries.Entry eyes = ModelEntries.seeing();
                if (eyes == null) throw ProtocolError.unavailable(ModelEntries.noEyes());
                String mode = args.path("mode").asText(Images.SAME).strip().toLowerCase(Locale.ROOT);
                if (!mode.equals(Images.SAME) && !mode.equals(Images.CONCEPT)) throw ProtocolError.invalidArgs("mode is same (the same person, place or thing as the reference) or concept (like it in the aspect what names).");
                String words = args.path("words").asText("").strip(), what = args.path("what").asText("").strip();
                if (mode.equals(Images.CONCEPT) && what.isEmpty()) what = words;
                if (mode.equals(Images.CONCEPT) && what.isEmpty()) throw ProtocolError.invalidArgs("concept needs the aspect to match, in words: what (or words).");
                if (mode.equals(Images.SAME) && what.isEmpty()) what = "thing";
                int count = Math.min(Math.max(args.path("count").asInt(Images.DEFAULT_COUNT), 1), Images.MOST);
                Images.Reference ref = referenceArg(args, paths, eyes, Patrons.mayWrite(store, patron));
                if (ref == null) throw ProtocolError.invalidArgs("like needs the reference: a kept picture's entry or /images/ path, a web address" + (paths ? ", a path on this machine" : "") + ", or bytes (base64) with content_type.");
                if (ref.description().isEmpty()) throw ProtocolError.unavailable("The seeing model (" + eyes.name() + ", " + eyes.model() + ") gave no description of the reference, so there were no words to search by.");
                Images.Liked liked = Images.like(store, eyes, ref, mode, what, words, count, true);
                r.set("reference", ref.json(M)); r.put("mode", liked.mode()); r.put("mode_why", liked.modeWhy()); r.put("two_pictures", liked.twoPictures()); r.put("how", liked.how(eyes)); r.put("what", what);
                r.put("words", liked.words()); r.put("words_why", liked.wordsWhy()); r.put("count", liked.found().found().size()); r.put("skipped", liked.skipped() + liked.found().skipped()); r.put("unsure", liked.unsure());
                ArrayNode fs = r.putArray("found"); for (Images.Found f : liked.found().found()) fs.add(f.json(M));
                ArrayNode engines = r.putArray("engines"); for (String e : liked.found().answered()) engines.add(e);
                ArrayNode vs = r.putArray("verdicts"); Map<String, Images.Judged> verdicts = new LinkedHashMap<>();
                for (Images.Judged j : liked.judged()) { ObjectNode vj = j.json(M); LikenessHelper.Measure mm = liked.measures().get(j.found().image()); if (mm != null) vj.set("likeness", mm.json(M)); vs.add(vj); verdicts.put(j.found().image(), j); }
                r.put("likeness_helper", LikenessHelper.said());
                ArrayNode same = r.putArray("same_as_reference"); for (Images.Found f : liked.sameAsReference()) same.add(f.json(M));
                String summary = (ref.kept() == null ? "The reference was read" : "The reference is kept as " + ref.id()) + ". Searched for \"" + liked.words() + "\" (" + liked.wordsWhy() + "): " + liked.found().said(liked.words())
                        + (liked.skipped() == 0 ? "" : " " + liked.skipped() + (liked.skipped() == 1 ? " more was" : " more were") + " skipped after the fetch as not pictures the model can look at.") + " " + liked.how(eyes) + " " + liked.tally(Images.CONCEPT.equals(liked.mode()) ? "" : what) + (liked.appearsAlso().isEmpty() ? "" : " " + liked.appearsAlso());
                if (keep) { List<Images.Found> yes = new ArrayList<>(); for (Images.Judged j : liked.judged()) if (j.yes()) yes.add(j.found()); summary += yes.isEmpty() ? " Nothing to keep." : " " + keepAll(r, yes, collection, verdicts); }
                r.put("summary", summary);
                r.put("next", "op=keep with the images judged yes puts them on the shelves; op=judge with reference judges pictures you name against it; mode=concept with what=<an aspect> looks for pictures like it rather than the same one.");
            }
            default -> throw ProtocolError.invalidArgs("op is find, keep, describe, judge or like.");
        }
        return r;
    }

    /**
     * The reference a request names: {@code reference} (a kept picture's entry or /images/ path, a web address, a path from the keeper) or
     * {@code bytes} (base64) with {@code content_type} and {@code name}; kept on the shelves as the person's own when {@code keep} and it
     * came as bytes or a path; described by the seeing model when {@code describe}. Null when the request names none.
     */
    private Images.Reference referenceArg(JsonNode args, boolean paths, ModelEntries.Entry eyes, boolean keep) throws IOException {
        Images.Fetched got; Images.Kept kept = null; String name = args.path("name").asText("");
        if (args.hasNonNull("bytes") && !args.path("bytes").asText().isBlank()) {
            byte[] b; try { b = Base64.getDecoder().decode(args.path("bytes").asText().strip()); } catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs("bytes is not base64."); }
            String type = args.path("content_type").asText(""); if (type.isBlank()) { String ext = Images.extension("", b); if (ext == null) throw ProtocolError.invalidArgs("bytes is not a picture the library keeps (JPEG, PNG, GIF, WebP, BMP or TIFF)."); type = Images.contentType("x." + ext); }
            got = new Images.Fetched(b, type);
            if (keep) kept = Images.keepGiven(store, b, type, name, "", args.path("collection").asText(""));
        } else {
            String ref = args.path("reference").asText("").strip();
            if (ref.isEmpty()) return null;
            got = Images.bytesOf(store, ref, paths);
            Images.Shelved held = ref.matches("(?i)^https?://.+") ? null : Images.byHash(store, Images.sha256(got.bytes()));
            if (held != null) kept = new Images.Kept(held.raw(), held.id(), held.file(), held.url(), held.cited(null));
            else if (keep && !ref.matches("(?i)^https?://.+")) kept = Images.keepGiven(store, got.bytes(), got.contentType(), name.isEmpty() ? ref.replaceAll("^.*[\\\\/]", "") : name, "", args.path("collection").asText(""));
        }
        return Images.describeReference(eyes, got.bytes(), got.contentType(), kept);
    }

    /** The pictures a request names: {@code images[]} as strings or found objects, or one {@code image}. */
    private static List<Images.Found> foundArgs(JsonNode args) {
        List<Images.Found> out = new ArrayList<>();
        JsonNode imgs = args.path("images");
        if (imgs.isArray()) for (JsonNode n : imgs) { if (n.isTextual() && !n.asText().isBlank()) out.add(new Images.Found(n.asText().strip(), "", "", "", 0, 0, "")); else if (n.isObject() && !n.path("image").asText("").isBlank()) out.add(Images.Found.from(n)); }
        else if (imgs.isTextual() && !imgs.asText().isBlank()) out.add(new Images.Found(imgs.asText().strip(), "", "", "", 0, 0, ""));
        if (!args.path("image").asText("").isBlank()) out.add(new Images.Found(args.path("image").asText().strip(), "", "", "", 0, 0, ""));
        return out;
    }

    private static List<String> refs(JsonNode args) { List<String> out = new ArrayList<>(); for (Images.Found f : foundArgs(args)) out.add(f.image()); return out; }

    /** Every candidate kept, the outcome per picture on {@code r} (kept[], not_kept[]) and in one sentence. */
    private String keepAll(ObjectNode r, List<Images.Found> found, String collection, Map<String, Images.Judged> verdicts) {
        ArrayNode ks = r.putArray("kept"), nk = r.putArray("not_kept");
        for (Images.Found f : found) {
            try {
                Images.Kept k = Images.keep(store, f, "", collection, verdicts.get(f.image()));
                ks.addObject().put("image", f.image()).put("raw", k.id()).put("file", k.file()).put("url", k.url()).put("title", f.title());
            } catch (IOException e) { nk.addObject().put("image", f.image()).put("why", e.getMessage()); }
        }
        store.circulate("images", "kept " + ks.size() + " of " + found.size());
        return ks.size() + " of " + found.size() + (ks.size() == 1 ? " picture is" : " pictures are") + " on the shelves" + (collection.isEmpty() ? "" : " under " + collection) + (nk.isEmpty() ? "." : "; " + nk.size() + " not kept (not_kept says why each).");
    }

    /**
     * library_translate: a document the person has (txt, md, pdf, docx, epub) translated into a language and written out in a format, as
     * a job; op=glossary lists the glossary or, with term and rendering, adds to it. A path is the keeper's to give, or a file the
     * browser posted; raw names a document on the shelves by its entry. The plan comes back at once: the units, the characters, the time
     * at either tier; library_job follows the run, whose result names the file, its note and the shelves.
     */
    public ObjectNode translate(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String op = args.path("op").asText("translate").strip().toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        r.put("op", op);
        if (op.equals("glossary")) {
            String term = args.path("term").asText("").strip(), rendering = args.path("rendering").asText("").strip(), lang = args.path("lang").asText(args.path("to").asText("")).strip();
            if (!term.isEmpty() && !rendering.isEmpty()) r.put("summary", Glossary.add(store, term, lang, rendering));
            else if (!term.isEmpty() && args.path("remove").asBoolean(false)) r.put("summary", Glossary.remove(store, term, lang) ? "Removed \"" + term + "\" for " + ReadIn.name(lang) + " from the glossary." : "The glossary has no entry \"" + term + "\" for " + ReadIn.name(lang) + ".");
            else r.put("summary", Glossary.describe(store, lang));
            ArrayNode es = r.putArray("entries");
            for (Glossary.Entry e : Glossary.list(store)) if (lang.isEmpty() || e.lang().equals(Translate.code(lang))) es.addObject().put("term", e.term()).put("lang", e.lang()).put("rendering", e.rendering()).put("added", e.added());
            return r;
        }
        if (!op.equals("translate")) throw ProtocolError.invalidArgs("op must be translate or glossary.");
        String path = args.path("path").asText("").strip(), raw = args.path("raw").asText("").strip(), to = args.path("to").asText("").strip();
        if (to.isEmpty()) throw ProtocolError.invalidArgs("Say which language: to=Japanese, or a code such as ja.");
        Path file;
        if (!path.isEmpty()) {
            file = Path.of(path).toAbsolutePath().normalize();
            if (!Uploads.isUpload(file) && !patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library owner can name a file on this machine, from the terminal or the library's own pages. A document on the shelves can be named by its entry (raw).");
            if (!Files.isRegularFile(file)) throw ProtocolError.notFound("Nothing is at " + file + ".");
        } else if (!raw.isEmpty()) {
            Path capture = RawCapture.find(store, raw);
            if (capture == null) throw ProtocolError.notFound("No document on the shelves is " + raw + ".");
            String[] parts = RawCapture.read(capture);
            file = parts[0].startsWith("file://") && Files.isRegularFile(Path.of(parts[0].substring(7))) ? Path.of(parts[0].substring(7)) : null;
            if (file == null) {   // the file is not on this machine: the shelved text is translated, as Markdown
                file = Translation.inbox(store).resolve((parts[1].isBlank() ? raw : parts[1]).replaceAll("[^A-Za-z0-9._ ()\\-\\p{L}\\p{N}]", "_") + ".md");
                Files.createDirectories(file.getParent());
                Files.writeString(file, (parts[1].isBlank() ? "" : "# " + parts[1] + "\n\n") + parts[2], StandardCharsets.UTF_8);
            }
        } else throw ProtocolError.invalidArgs("Give the document: path (a file on this machine, or one the browser posted) or raw (an entry on the shelves).");
        String judge = args.path("judge").asText("").strip(), model = args.path("model").asText("").strip(), back = args.has("back_translation") ? (args.path("back_translation").asBoolean(false) ? "on" : "off") : "";
        if (!Translation.anyModel()) throw ProtocolError.unavailable(Translation.NO_MODEL);
        Translation.Plan plan;
        try { plan = Translation.plan(store, file, to, args.path("as").asText(""), args.path("quality").asText(Translation.DEFAULT), Translation.configuredSeat()[1], model, judge); }
        catch (IOException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
        Path out = args.path("out").asText("").isEmpty() ? null : Path.of(args.path("out").asText()).toAbsolutePath().normalize();
        if (out != null && !patron.person()) throw ProtocolError.forbidden("Only the library owner names where the file is written; without out it goes under the library's exports.");
        ObjectNode a = Translation.args(file, file.getFileName().toString(), plan.to(), plan.as(), plan.quality(), out, args.path("collection").asText(""), out == null ? "mcp" : "cli", judge, model, back);
        String id = new Jobs(store, j -> "").submit(Translation.JOB_KIND, patron.did(), a);
        r.put("job_id", id); r.put("file", file.getFileName().toString()); r.put("format", plan.format()); r.put("from", plan.from()); r.put("to", plan.to()); r.put("as", plan.as()); r.put("quality", plan.quality());
        r.put("units", plan.units()); r.put("characters", plan.chars()); r.put("estimate_seconds", plan.seconds()); r.put("estimate", plan.saidFor()); r.put("estimate_measured", plan.measured()); r.put("estimates", plan.said());
        ObjectNode secs = r.putObject("estimate_seconds_by_tier"); secs.put("quick", plan.quickSeconds()); secs.put("checked", plan.checkedSeconds()); secs.put("reviewed", plan.reviewedSeconds()); secs.put("thorough", plan.thoroughSeconds());
        if (!judge.isEmpty()) { r.put("judge", plan.judge()); r.put("judge_hosted", plan.judgeHosted()); if (plan.judgeHosted()) { r.put("judge_cost_dollars", Math.round(plan.judgeCost() * 100) / 100.0); r.put("judge_tokens", plan.judgeTokens()); } }
        if (!model.isEmpty()) { r.put("model", plan.translator()); r.put("model_hosted", plan.translatorHosted()); if (plan.translatorHosted()) { r.put("model_cost_dollars", Math.round(plan.translatorCost() * 100) / 100.0); r.put("model_tokens", plan.translatorTokens()); } }
        r.put("summary", "Filed " + id + ": \"" + file.getFileName() + "\" (" + plan.units() + " units, " + plan.chars() + " characters, written in " + ReadIn.name(plan.from()) + ") into " + ReadIn.name(plan.to()) + " as " + plan.as() + ", " + plan.quality() + ": " + plan.saidFor() + ".");
        r.put("next", "library_job with job_id follows it; its result names the file (under the library's exports, served at /exports/<name>), the translation note beside it, and both documents on the shelves.");
        store.circulate("translate", patron.label() + " :: " + file.getFileName() + " → " + plan.to() + " (" + plan.quality() + ")");
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
        String database = args.path("database").asText("").strip();
        if (path.startsWith("db:")) { database = path.substring(3); path = ""; }
        if (url.startsWith("db:")) { database = url.substring(3); url = ""; }
        if (!database.isEmpty() && !patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library owner can read the owner's databases.");
        if (!path.isEmpty() && !patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library owner can survey a file or a folder on this machine, from the terminal or the library's own pages. A url works from here.");
        String spec = !database.isEmpty() ? "db:" + database : path.isEmpty() ? url : path;
        ObjectNode r = envelope();
        r.put("op", op);
        if (op.equals("survey")) {
            if (spec.isEmpty()) throw ProtocolError.invalidArgs("Give the thing to read: a path on this machine (a repository folder, a paper, an export), or a url (a repository, a paper, a page, a GitHub issues page).");
            Surveys.Kind kind;
            String kindGiven = args.path("kind").asText("").strip().toLowerCase(Locale.ROOT);
            try { kind = kindGiven.isEmpty() ? Surveys.detect(spec) : Surveys.Kind.valueOf(kindGiven); } catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs("kind must be repo, paper, site or issues."); }
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
            String about = switch (kind) { case repo -> read.facts().getOrDefault("files", "?") + " files" + (read.facts().getOrDefault("languages", "").isEmpty() ? "" : "; " + read.facts().get("languages")); case paper, site -> read.facts().getOrDefault("characters", "?") + " characters"; case issues -> read.facts().getOrDefault("issues", "?") + " issues"; case db -> read.facts().getOrDefault("tables", "?") + " tables, " + read.facts().getOrDefault("engine", ""); };
            r.put("summary", "Read the " + Surveys.noun(kind) + " " + read.name() + " (" + about + "). The text is in the library (collection " + Surveys.collection(kind) + "). One draft claim says what it is (" + f.claimId() + "). " + f.options().size() + " direction(s) are offered on the open questions. Nothing runs until you pick one.");
            r.put("next", "op=pick with picks=\"1,3\" and name=\"" + read.name() + "\" files a research run per direction. op=do with question=\"…\" files your own.");
            return r;
        }
        if (name.isEmpty() && !spec.isEmpty()) name = Repos.nameOf(spec);
        if (name.isEmpty()) throw ProtocolError.invalidArgs("Give the name of the surveyed thing (name=…, as the survey called it).");
        Finding claim = Surveys.claimFor(store, name);
        if (claim == null && !spec.isEmpty()) {
            // a thing given by its path or url that was never read: read it first, then do what was asked
            ObjectNode first = ((ObjectNode) args).deepCopy(); first.put("op", "survey");
            ObjectNode read = survey(first);
            name = read.path("name").asText(name);
            claim = Surveys.claimFor(store, name);
            r.put("surveyed_first", read.path("summary").asText(""));
        }
        if (claim == null) throw ProtocolError.notFound("No survey is named " + name + ". Run op=survey first.");
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
            if (wanted.isEmpty()) throw ProtocolError.invalidArgs("picks must be numbers, like \"1,3\".");
            for (int n : wanted) {
                Surveys.Option o = options.stream().filter(x -> x.n() == n).findFirst().orElse(null);
                if (o == null) { runs.addObject().put("n", n).put("error", "no open direction numbered " + n + " for " + name); continue; }
                ObjectNode ask = M.createObjectNode();
                ask.put("question", Surveys.runQuestion(kind, name, origin, o.question())); ask.put("mode", "depth"); ask.put("sources", "both");
                ask.set("patron", args.path("patron").deepCopy());
                ObjectNode run = fileRun(ask, r);
                if (!filed(run)) { runs.addObject().put("n", n).put("question", o.question()).put("state", "not_started").put("help", run.path("help").asText()); continue; }   // not filed: the direction stays open
                String job = run.path("job_id").asText();
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
            ObjectNode run = fileRun(ask, r);
            if (!filed(run)) runs.addObject().put("question", q).put("state", "not_started").put("help", run.path("help").asText());   // not filed: the question stays open
            else {
                String job = run.path("job_id").asText();
                Frontier.markExplored(store, q, job);
                runs.addObject().put("question", q).put("job_id", job);
            }
        } else throw ProtocolError.invalidArgs("op must be survey, pick or do.");
        int filed = 0, notStarted = 0; for (JsonNode j : runs) { if (filed(j)) filed++; else if (j.has("help")) notStarted++; }
        ArrayNode left = r.putArray("options"); for (Surveys.Option o : Surveys.options(store, name)) left.addObject().put("n", o.n()).put("question", o.question());
        r.put("summary", filed + " research run(s) were filed about " + name + "." + (notStarted > 0 ? " " + notStarted + " question(s) were not filed, because they read as a person asking about harming themselves: each says where to find help." : "")
                + (left.size() > 0 ? " " + left.size() + " direction(s) are still open." : ""));
        r.put("next", "library_job follows each research run. op=pick or op=do files more.");
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
        if (id.isEmpty()) throw ProtocolError.invalidArgs("Give the id of a report (I-…) or a claim (F-…) to delete.");
        Removal.What what;
        try { what = Removal.What.valueOf(args.path("what").asText("all").strip().toLowerCase(Locale.ROOT)); } catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs("what must be all, report or claims."); }
        boolean dry = args.path("dry").asBoolean(false);
        Removal.Plan plan;
        try { plan = Removal.plan(store, id, what); } catch (IOException e) { throw ProtocolError.notFound("No entry has the id " + id + "."); }
        ObjectNode r = envelope();
        r.put("id", plan.id()); r.put("kind", plan.kind()); r.put("title", plan.title()); r.put("what", plan.what().name()); r.put("dry", dry);
        r.put("report_goes", plan.reportGoes());
        ArrayNode go = r.putArray("claims_go"); for (int i = 0; i < plan.claimsGo().size(); i++) go.addObject().put("id", plan.claimsGo().get(i)).put("title", plan.titles().get(i));
        ArrayNode stayOut = r.putArray("claims_stay"); plan.claimsStay().forEach(stayOut::add);
        r.put("plan", Removal.describe(plan));
        int stay = plan.claimsStay().size();
        if (dry) { r.put("summary", "Preview: " + plan.count() + (plan.count() == 1 ? " entry" : " entries") + " would be deleted." + (stay == 0 ? "" : " " + stay + (stay == 1 ? " claim would be kept because another report cites it." : " claims would be kept because another report cites them.")) + " Nothing was deleted."); r.put("next", "Run the same call with dry=false to delete them."); return r; }
        Removal.Done done = Removal.apply(store, plan, patron.writer());
        ArrayNode removed = r.putArray("removed"); done.removed().forEach(removed::add);
        r.put("summary", "Deleted " + done.removed().size() + (done.removed().size() == 1 ? " entry." : " entries.") + (stay == 0 ? "" : " " + stay + (stay == 1 ? " claim was kept because another report cites it." : " claims were kept because another report cites them.")) + " This is recorded in the change log.");
        r.put("next", "This cannot be undone. library_changes lists what was deleted.");
        return r;
    }

    static final int READ_IN_FILES = 3;

    /**
     * The files and Calibre libraries a question names, read into the library before the run: a Calibre library or
     * its metadata.db becomes a list (so the run has the holdings lookup); any other document is added. Only for the
     * library owner, whose paths these are. Returns one phrase per thing read in.
     */
    List<String> readInNamedFiles(String question, JsonNode patronNode, Patrons.Patron patron) {
        List<String> out = new ArrayList<>();
        if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (Path f : namedPaths(question)) {
            if (seen.size() >= READ_IN_FILES) break;
            if (!seen.add(f.toString())) continue;
            try {
                boolean calibre = Files.isDirectory(f) ? Calibre.isLibrary(f) : isCalibreDatabase(f);
                ObjectNode a = M.createObjectNode().put("path", f.toString());
                if (patronNode != null && patronNode.isObject()) a.set("patron", patronNode.deepCopy());
                if (calibre) {
                    ObjectNode r = items(a.put("as", "none"));
                    out.add("the Calibre library at " + f + " as the list \"" + r.path("title").asText() + "\" (" + r.path("items_found").asInt() + " books)");
                } else if (Files.isRegularFile(f) && (Corpus.EXTENSIONS.contains(Corpus.ext(f)) || Corpus.PICTURES.contains(Corpus.ext(f)))) {   // a file named by the person, a picture among them
                    add(a);
                    out.add("the document " + f);
                }
            } catch (Exception e) { out.add(f + " could not be read in (" + e.getMessage() + ")"); }
        }
        return out;
    }

    /**
     * The paths a text names that exist on this machine. A path may hold spaces ("Calibre Library" is Calibre's own
     * default), so from each place a path starts, the longest run of words that names something that exists is taken.
     */
    static List<Path> namedPaths(String text) {
        List<Path> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![\\w/.~])(~?/)").matcher(text);
        int from = 0;
        while (m.find()) {
            if (m.start() < from) continue;
            String rest = text.substring(m.start(), Math.min(text.length(), m.start() + 400));
            int nl = rest.indexOf('\n'); if (nl >= 0) rest = rest.substring(0, nl);
            String[] words = rest.split(" ");
            Path best = null; int bestLen = 0;
            StringBuilder cand = new StringBuilder();
            for (int k = 0; k < words.length && k < 12; k++) {
                if (k > 0) cand.append(' ');
                cand.append(words[k]);
                String c = cand.toString().replaceAll("[\"'`.,;:)\\]]+$", "");
                if (c.length() < 2) continue;
                String expanded = c.startsWith("~/") ? System.getProperty("user.home") + c.substring(1) : c;
                try { Path f = Path.of(expanded).toAbsolutePath().normalize(); if (f.getNameCount() >= 2 && Files.exists(f)) { best = f; bestLen = cand.length(); } } catch (Exception ignored) { }
            }
            if (best != null) { out.add(best); from = m.start() + bestLen; }
        }
        return out;
    }

    static boolean isCalibreDatabase(Path f) {
        if (!Files.isRegularFile(f)) return false;
        try (var in = Files.newInputStream(f)) {
            if (!DocText.isSqlite(in.readNBytes(16))) return false;
        } catch (IOException e) { return false; }
        try { Calibre.fromDatabase(f); return true; } catch (Exception e) { return false; }
    }

    /**
     * library_db: the databases the library owner gave read access to. op=list names them; op=schema with database
     * gives tables, columns, row counts and sample rows; op=query runs one reading statement (sql, or for MongoDB a
     * collection with a filter or a pipeline) and saves the result as a page to cite. Connections are added from the
     * command line only, by the owner. Only the owner may use this.
     */
    public ObjectNode db(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library owner can read the owner's databases.");
        String op = args.path("op").asText("list").strip().toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        r.put("op", op);
        String warn = Databases.hostedWarning();
        if (!warn.isEmpty()) r.put("warning", warn);
        if (op.equals("list")) {
            ArrayNode out = r.putArray("databases");
            for (Databases.Db d : Databases.list()) { ObjectNode n = out.addObject(); n.put("name", d.name()); n.put("kind", d.kind()); n.put("address", d.shown()); ArrayNode h = n.putArray("hidden_columns"); d.hide().forEach(h::add); }
            r.put("summary", out.size() == 0 ? "No database has been added. The owner adds one with: researchzosho db add <name> <address>" : out.size() + " database(s).");
            return r;
        }
        Databases.Db d = Databases.get(args.path("database").asText(""));
        if (d == null) throw ProtocolError.notFound("No database is named " + args.path("database").asText("") + ". op=list shows the names.");
        r.put("database", d.name()); r.put("kind", d.kind());
        try {
            if (op.equals("schema")) { r.put("schema", Databases.schema(d)); r.put("summary", "The schema of " + d.name() + "."); return r; }
            if (op.equals("query")) {
                Databases.Result res = d.kind().equals("mongo")
                        ? Databases.queryMongo(store, d, args.path("collection").asText(""), args.path("filter").asText(""), args.path("pipeline").asText(""), args.path("limit").asInt(0))
                        : Databases.query(store, d, args.path("sql").asText(""), args.path("limit").asInt(0));
                ArrayNode cols = r.putArray("columns"); res.columns().forEach(cols::add);
                ArrayNode rows = r.putArray("rows"); for (List<String> row : res.rows()) { ArrayNode rr = rows.addArray(); row.forEach(rr::add); }
                r.put("more", res.more()); r.put("saved", res.saved()); r.put("text", res.text());
                r.put("summary", res.rows().size() + " row(s)" + (res.more() ? ". More rows exist: count or group in the query, or raise limit." : ".") + (res.saved().isEmpty() ? "" : " Saved as " + res.saved() + "."));
                return r;
            }
        } catch (IOException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
        throw ProtocolError.invalidArgs("op must be list, schema or query.");
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
        r.put("summary", found.isEmpty() ? "No list entry matches \"" + q + "\" (" + r.path("lists_hold").asInt() + " entries in all)." : found.size() + (found.size() == 1 ? " entry matches \"" : " entries match \"") + q + "\"" + (found.size() >= limit ? " (the first " + limit + ")" : "") + ".");
        return r;
    }

    /**
     * library_who: a field's own tool (genealogy's: who, of the people the web shows under a name, is the person in the family), answered
     * by the field whose tool it is ({@link Profile#tool}).
     */
    public ObjectNode who(JsonNode args) throws IOException {
        for (Profile p : Profiles.known()) { ObjectNode r = p.tool("library_who", this, args); if (r != null) return r; }
        throw ProtocolError.invalidArgs("No field of this library answers library_who.");
    }

    /** The web search and the model behind library_who op=find, when a test puts its own in; null for the live ones. */
    public Supplier<?> whoSearch = null;
    public Supplier<?> whoModel = null;

    /**
     * library_absorb: a conversation the person had with another assistant, as a starting point. The
     * transcript is shelved as it is (collection "conversations" unless named); the person's questions join
     * the open questions; the assistant's claims come back as a list to check and are filed as nothing.
     * verify=true files one research run that checks the claims against sources. Input: path (the keeper's),
     * url (any write patron), or text (pasted). An export holding many conversations takes the first
     * {@code limit} (default 25) and says how many remain.
     */
    public ObjectNode absorb(JsonNode args) throws IOException {
        boolean unchecked = false;   // the person's url, saved before a model could check it
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String path = args.path("path").asText("").strip(), url = args.path("url").asText("").strip(), text = args.path("text").asText("");
        if (path.startsWith("http://") || path.startsWith("https://")) { url = path; path = ""; }
        int given = (path.isEmpty() ? 0 : 1) + (url.isEmpty() ? 0 : 1) + (text.isBlank() ? 0 : 1);
        if (given != 1) throw ProtocolError.invalidArgs("Give one of these: a file path (a saved transcript or an export file), a url, or the conversation text.");
        String collection = args.path("collection").asText("conversations").strip();
        String titleGiven = args.path("title").asText("").strip();
        int limit = Math.max(1, args.path("limit").asInt(Conversations.DEFAULT_THREADS));
        boolean verify = args.path("verify").asBoolean(false);
        byte[] bytes; String source, nameHint;
        if (!path.isEmpty()) {
            if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library owner can give a file path on this machine. Do that from the terminal or the library's web pages. From here, give a url or paste the text.");
            Path f = Path.of(path).toAbsolutePath().normalize();
            if (!Files.isRegularFile(f)) throw ProtocolError.notFound(f + " is not a file. For a folder of documents, use library_add.");
            bytes = Files.readAllBytes(f); source = f.toString(); nameHint = f.getFileName().toString();
        } else if (!url.isEmpty()) {
            try {
                var page = PageCheck.fetch(url, Duration.ofSeconds(60), ContentPolicy.person(null), "");   // the caller's own address: the always-dropped check
                var resp = page.fetched();
                if (resp.status() >= 400) throw new IOException("HTTP " + resp.status());
                String ct = resp.contentType() == null ? "" : resp.contentType();
                bytes = ct.contains("json") ? resp.body() : page.orThrow().doc().text().getBytes(StandardCharsets.UTF_8);
                page.orThrow();
                unchecked = page.unchecked();
                UncheckedPages.after(store, page, url, null, null);   // saved from it below even when no model could check it, and checked when one answers
            } catch (PageCheck.NotKept notKept) { throw notKept.unchecked() ? ProtocolError.unavailable(notKept.getMessage()) : ProtocolError.invalidArgs(notKept.getMessage()); }
            catch (Exception e) { throw ProtocolError.unavailable("Could not read " + url + ": " + e.getMessage()); }
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
        List<String> allClaims = new ArrayList<>(), declinedIn = new ArrayList<>(); String firstTitle = ""; String mainQuestion = ""; String declinedSaid = "";
        for (Conversations.Thread t : threads.subList(0, Math.min(limit, threads.size()))) {
            Conversations.Outcome o = Conversations.absorb(store, t, source, collection, patron.writer(), extractor);
            ObjectNode tn = out.addObject();
            tn.put("title", o.title()); tn.put("raw", o.raw()); tn.put("turns", o.turns());
            ArrayNode qf = tn.putArray("questions_filed"); o.questionsFiled().forEach(qf::add);
            ArrayNode qh = tn.putArray("questions_already_open"); o.questionsHeld().forEach(qh::add);
            ArrayNode cl = tn.putArray("claims_to_check"); o.claims().forEach(cl::add);
            if (!o.declined().isEmpty()) { tn.put("declined", o.declined()); declinedIn.add(o.title()); if (declinedSaid.isEmpty()) declinedSaid = o.declined(); }   // shelved and filed; the model declined its claims
            filed += o.questionsFiled().size(); claimsAll += o.claims().size();
            if (firstTitle.isEmpty()) { firstTitle = o.title(); mainQuestion = Conversations.mainQuestion(t); }
            if (allClaims.size() < Conversations.MAX_CLAIMS) for (String c : o.claims()) { if (allClaims.size() >= Conversations.MAX_CLAIMS) break; allClaims.add(c); }
        }
        r.put("absorbed", Math.min(limit, threads.size()));
        r.put("remaining", Math.max(0, threads.size() - limit));
        r.put("questions_filed", filed); r.put("claims_to_check", claimsAll);
        r.put("claims_by", extractor == null ? "mechanical" : "model");
        if (!declinedSaid.isEmpty()) r.put("declined", declinedSaid);
        if (verify && !allClaims.isEmpty()) {
            ObjectNode ask = M.createObjectNode();
            ask.put("question", Conversations.verifyQuestion(firstTitle, allClaims)); ask.put("mode", "depth"); ask.put("sources", "both");
            ask.set("patron", args.path("patron").deepCopy());
            if (args.hasNonNull("field")) ask.put("field", args.path("field").asText());   // the checking run in the field the person asked for
            if (args.hasNonNull("allow")) ask.set("allow", args.get("allow").deepCopy()); if (args.hasNonNull("locale")) ask.put("locale", args.path("locale").asText());   // what the person let in for these runs, as they named it
            ObjectNode job = fileRun(ask, r);
            if (filed(job)) r.put("verify_job_id", job.path("job_id").asText());
            else r.putArray("not_started").addObject().put("question", ask.path("question").asText("")).put("help", job.path("help").asText());
        }
        r.put("main_question", mainQuestion);
        r.put("summary", (threads.size() == 1 ? "Read \"" + Acquisitions.compress(firstTitle, 60) + "\"" : "Read " + Math.min(limit, threads.size()) + " of " + threads.size() + " conversations")
                + ". The transcript is saved in the library (collection " + collection + "). " + filed + " question(s) were added to the open questions. " + claimsAll + " claim(s) made by the assistant are listed for checking"
                + (verify && !allClaims.isEmpty() ? ". A research run is checking them (" + r.path("verify_job_id").asText() + ")." : ". None of them is saved as a claim.")
                + (declinedIn.isEmpty() ? "" : " " + (threads.size() == 1 ? "The model declined to list the claims in it, so there are none to check. "
                        : "The model declined to list the claims in " + declinedIn.size() + " of the conversations (" + String.join("; ", declinedIn.stream().map(x -> "\"" + Acquisitions.compress(x, 60) + "\"").toList()) + "); their transcripts are saved and their questions filed like the others. ")
                        + declinedSaid));
        notChecked(r, unchecked);
        r.put("next", verify ? "library_job shows the check's progress. To research the subject itself, use library_research {question: <the thread's main question>, sources: both}."
                : "verify=true starts one research run that checks the claims. To research the subject itself, use library_research {question: \"" + Acquisitions.compress(mainQuestion, 200).replace("\"", "'") + "\", sources: both}.");
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
        boolean unchecked = false;   // the person's url, saved before a model could check it
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String path = args.path("path").asText("").strip(), url = args.path("url").asText("").strip(), text = args.path("text").asText("");
        if (path.startsWith("http://") || path.startsWith("https://")) { url = path; path = ""; }
        int given = (path.isEmpty() ? 0 : 1) + (url.isEmpty() ? 0 : 1) + (text.isBlank() ? 0 : 1);
        if (given != 1) throw ProtocolError.invalidArgs("Give one of these: a file path (one item per line, a table, or a CSV), a url, or the list as text.");
        String as = args.path("as").asText("frontier").strip().toLowerCase(Locale.ROOT);
        if (!as.equals("frontier") && !as.equals("runs") && !as.equals("none")) throw ProtocolError.invalidArgs("as must be frontier (add one open question per item), runs (start research runs, one part per item) or none (only show what the library already has).");
        String lens = args.path("lens").asText("");
        String collection = args.path("collection").asText("lists").strip();
        String column = args.hasNonNull("column") ? args.get("column").asText() : null;
        int limit = Math.max(1, args.path("limit").asInt(Items.MAX_ITEMS));
        String title = args.path("title").asText("").strip(), source, body, readFrom = "";
        if (!path.isEmpty()) {
            if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library owner can give a file path on this machine. Do that from the terminal or the library's web pages. From here, give a url or paste the list.");
            Path f = Path.of(path).toAbsolutePath().normalize();
            if (Files.isDirectory(f)) {
                // a Calibre library: its books as the list, each with its authors, year, series and tags as the note
                if (!Calibre.isLibrary(f)) throw ProtocolError.notFound(f + " is a folder, but not a Calibre library: it has no metadata.db and no metadata.opf. For a folder of documents, use library_add.");
                Calibre.Shelf shelf;
                try { shelf = Calibre.read(f); } catch (IOException e) { throw ProtocolError.unavailable(e.getMessage()); }
                body = Calibre.csv(shelf.books()); readFrom = shelf.readFrom();
                source = f.toString(); if (title.isEmpty()) title = "Calibre library " + f.getFileName();
            } else {
                if (!Files.isRegularFile(f)) throw ProtocolError.notFound(f.toString());
                DocText.Doc doc = DocText.convert(Files.readAllBytes(f), f.getFileName().toString());
                body = doc.text();
                if (Corpus.ext(f).equals("csv") || Corpus.ext(f).equals("txt") || Corpus.ext(f).equals("md")) body = Files.readString(f, StandardCharsets.UTF_8);
                if ("calibre".equals(doc.kind())) { readFrom = "metadata.db"; if (title.isEmpty()) title = doc.title(); }   // a bare metadata.db is the library's books
                RawCapture.dropUnreadable(store, f.toUri().toString());   // an earlier version may have saved this file as unreadable text
                source = f.toString(); if (title.isEmpty()) title = Conversations.titleFrom(f.getFileName().toString());
            }
        } else if (!url.isEmpty()) {
            try {
                var page = PageCheck.fetch(url, Duration.ofSeconds(60), ContentPolicy.person(null), "");   // the caller's own address: the always-dropped check
                var resp = page.fetched();
                if (resp.status() >= 400) throw new IOException("HTTP " + resp.status());
                body = page.orThrow().doc().text();
                unchecked = page.unchecked();
                UncheckedPages.after(store, page, url, null, null);   // saved below even when no model could check it, and checked when one answers
            } catch (PageCheck.NotKept notKept) { throw notKept.unchecked() ? ProtocolError.unavailable(notKept.getMessage()) : ProtocolError.invalidArgs(notKept.getMessage()); }
            catch (Exception e) { throw ProtocolError.unavailable("Could not read " + url + ": " + e.getMessage()); }
            source = url; if (title.isEmpty()) title = Conversations.titleFrom(url);
        } else { body = text; source = "pasted"; if (title.isEmpty()) title = "list"; }
        List<Items.Item> all = Items.parse(body, column);
        if (all.isEmpty()) throw ProtocolError.invalidArgs("No items found. Give one item per line, a markdown table, or a CSV.");
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
                if (args.hasNonNull("field")) ask.put("field", args.path("field").asText());
                if (args.hasNonNull("allow")) ask.set("allow", args.get("allow").deepCopy()); if (args.hasNonNull("locale")) ask.put("locale", args.path("locale").asText());   // what the person let in for these runs, as they named it
                ObjectNode run = fileRun(ask, r);
                if (filed(run)) jobsOut.add(run.path("job_id").asText());
                else (r.has("not_started") ? (ArrayNode) r.get("not_started") : r.putArray("not_started")).addObject().put("question", ask.path("question").asText("")).put("help", run.path("help").asText());
            }
        }
        r.put("questions_filed", filed); r.put("questions_already_open", already);
        String what = items.size() + " item(s)" + (matched.size() < all.size() ? " of " + matched.size() + " matched" + (sample > 0 && matched.size() > items.size() ? ", a sample" : "") : all.size() > items.size() ? " of " + all.size() + (sample > 0 ? ", a sample" : "") : "")
                + (all.size() > items.size() ? " (all " + all.size() + " are saved in the library)" : "") + ", " + held + " already in the library";
        r.put("summary", "list \"" + Acquisitions.compress(title, 60) + "\": " + what
                + (as.equals("frontier") ? ". " + filed + " question(s) were added to the open questions" + (already > 0 ? ". " + already + " were already open" : "")
                : as.equals("runs") ? ". " + jobsOut.size() + " research run(s) started, one part per item" : ". Nothing was filed. The list is saved in the library")
                + (rawName.isEmpty() ? "" : ". The saved list is " + rawName));
        r.put("next", as.equals("frontier") ? "The nightly research works through them. To look one up now, use library_research on that question. as=runs starts research runs for all of them."
                : as.equals("runs") ? "library_job shows each run's progress. library_get opens the reports."
                : "as=frontier adds one open question per item. as=runs starts research runs.");
        store.circulate("items", patron.writer() + " :: " + Acquisitions.compress(title, 80) + " — " + what + " (" + as + ")");
        notChecked(r, unchecked);
        return r;
    }

    /** What a launching point was given: the bytes, where they came from, a name to title it by. */
    /** {@code unchecked}: a url of the person's that no model could check, saved all the same and checked when one answers. */
    private record Given(byte[] bytes, String source, String nameHint, boolean pasted, boolean unchecked) {
        Given(byte[] bytes, String source, String nameHint, boolean pasted) { this(bytes, source, nameHint, pasted, false); }
    }

    /** A result whose url of the person's was saved before a model could check it says so. */
    static void notChecked(ObjectNode r, boolean unchecked) {
        if (!unchecked) return;
        r.put("checked", false);
        r.put("note", "Saved, and " + PageCheck.NOT_CHECKED_YET + ". The library checks it when a model answers, and removes it then if the check finds " + PageCheck.Category.CHILD.said() + ".");
    }

    /** path (the keeper's), url, or text — exactly one. {@code pastedName} titles a pasted text. */
    private Given given(JsonNode args, Patrons.Patron patron, String pastedName, String what) throws IOException {
        String path = args.path("path").asText("").strip(), url = args.path("url").asText("").strip(), text = args.path("text").asText("");
        if (path.startsWith("http://") || path.startsWith("https://")) { url = path; path = ""; }
        int n = (path.isEmpty() ? 0 : 1) + (url.isEmpty() ? 0 : 1) + (text.isBlank() ? 0 : 1);
        if (n != 1) throw ProtocolError.invalidArgs("Give one of these: a file path (" + what + "), a url, or the text itself.");
        String title = args.path("title").asText("").strip();
        if (!path.isEmpty()) {
            if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library owner can give a file path on this machine. Do that from the terminal or the library's web pages. From here, give a url or paste the text.");
            Path f = Path.of(path).toAbsolutePath().normalize();
            if (!Files.isRegularFile(f)) throw ProtocolError.notFound(f.toString());
            return new Given(Files.readAllBytes(f), f.toString(), title.isEmpty() ? f.getFileName().toString() : title, false);
        }
        if (!url.isEmpty()) {
            try {
                var page = PageCheck.fetch(url, Duration.ofSeconds(60), ContentPolicy.person(null), "");   // the caller's own address: the always-dropped check
                var resp = page.fetched();
                if (resp.status() >= 400) throw new IOException("HTTP " + resp.status());
                page.orThrow();
                UncheckedPages.after(store, page, url, null, null);   // saved from it by the caller even when no model could check it, and checked when one answers
                return new Given(resp.body(), url, title.isEmpty() ? url : title, false, page.unchecked());
            } catch (PageCheck.NotKept notKept) { throw notKept.unchecked() ? ProtocolError.unavailable(notKept.getMessage()) : ProtocolError.invalidArgs(notKept.getMessage()); }
            catch (Exception e) { throw ProtocolError.unavailable("Could not read " + url + ": " + e.getMessage()); }
        }
        return new Given(text.getBytes(StandardCharsets.UTF_8), "pasted", title.isEmpty() ? pastedName : title, true);
    }

    /** Text out of whatever was given: a document is converted, a text file is read as it is. */
    private static String textOf(Given g) {
        String name = g.nameHint().toLowerCase(Locale.ROOT);
        if (g.pasted() || name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv") || name.endsWith(".bib") || name.endsWith(".ris") || name.endsWith(".vtt") || name.endsWith(".json") || name.endsWith(".html") || name.endsWith(".htm")) return new String(g.bytes(), StandardCharsets.UTF_8);
        return DocText.convert(g.bytes(), g.nameHint()).text();
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
        String text = DocText.convert(g.bytes(), g.nameHint()).text();
        if (text.isBlank()) text = new String(g.bytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) throw ProtocolError.invalidArgs("No text could be read from the draft.");
        String title = args.path("title").asText("").strip();
        if (title.isEmpty()) {
            var doc = DocText.convert(g.bytes(), g.nameHint());
            title = doc.title().isBlank() ? "" : doc.title();
            if (title.isEmpty()) { Matcher h = Pattern.compile("(?m)^#\\s+(.+)$").matcher(text); if (h.find()) title = h.group(1).strip(); }   // a markdown draft's first heading
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
            if (got.shelved()) { shelved++; c.put("raw", got.raw().getFileName().toString()); c.put("title", got.title()); c.put("state", "held".equals(got.kind()) ? "already held" : "shelved"); if (got.unchecked()) c.put("note", PageCheck.NOT_CHECKED_YET); }
            else { c.put("state", got.requested() ? "requested" : "not read"); c.put("problem", got.problem()); if (got.requested()) requested++; }
        }
        r.put("claims_by", Explain.drive() == null ? "mechanical" : "model");
        if (!o.declined().isEmpty()) r.put("declined", o.declined());   // the draft, its questions and its citations are saved; the model declined its claims
        if (args.path("verify").asBoolean(false) && !o.claims().isEmpty()) {
            ObjectNode ask = M.createObjectNode();
            ask.put("question", Drafts.verifyQuestion(title, o.claims())); ask.put("mode", "depth"); ask.put("sources", "both");
            ask.set("patron", args.path("patron").deepCopy());
            if (args.hasNonNull("field")) ask.put("field", args.path("field").asText());   // the checking run in the field the person asked for
            if (args.hasNonNull("allow")) ask.set("allow", args.get("allow").deepCopy()); if (args.hasNonNull("locale")) ask.put("locale", args.path("locale").asText());   // what the person let in for these runs, as they named it
            ObjectNode job = fileRun(ask, r);
            if (filed(job)) r.put("verify_job_id", job.path("job_id").asText());
            else r.putArray("not_started").addObject().put("question", ask.path("question").asText("")).put("help", job.path("help").asText());
        }
        r.put("summary", "draft \"" + Acquisitions.compress(title, 60) + "\": " + o.claims().size() + " claim(s) to check, " + o.citations().size() + " citation(s) found (" + shelved + " saved in the library" + (requested > 0 ? ", " + requested + " could not be read and are on the request list" : "") + "). " + o.questionsFiled().size() + " question(s) were added to the open questions"
                + (r.hasNonNull("verify_job_id") ? ". A research run is checking the claims (" + r.path("verify_job_id").asText() + ")." : ". Nothing is saved as a claim.")
                + (o.declined().isEmpty() ? "" : " The model declined to list the draft's claims, so there are none to check. " + o.declined()));
        r.put("next", r.hasNonNull("verify_job_id") ? "library_job shows the check's progress. library_get opens the report." : "verify=true starts one research run that checks the claims against sources, including the draft's own citations. If a citation could not be read, supply it with researchzosho add <file> --for <url>.");
        notChecked(r, g.unchecked());
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
        if (entries.isEmpty()) throw ProtocolError.invalidArgs("No entries found. Give BibTeX, RIS, a CSV with a title, doi or url column, or one DOI, url or title per line.");
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
            if (got.shelved()) { shelved++; n.put("raw", got.raw().getFileName().toString()); n.put("state", "held".equals(got.kind()) ? "already held" : "shelved"); if (!got.title().isEmpty()) n.put("title_read", got.title()); if (got.unchecked()) n.put("note", PageCheck.NOT_CHECKED_YET); }
            else { n.put("state", got.requested() ? "requested" : "not read"); n.put("problem", got.problem()); if (got.requested()) requested++; }
        }
        boolean watch = args.path("watch").asBoolean(false);
        if (watch && list != null) Corpus.registerUrls(store, collection, list.getFileName().toString());
        r.put("shelved", shelved); r.put("requested", requested); r.put("no_locator", unlocated); r.put("watching", watch && list != null);
        ArrayNode to = r.putArray("titles_without_locator"); titlesOnly.forEach(to::add);
        r.put("summary", "reading list \"" + Acquisitions.compress(title, 60) + "\": " + take.size() + " entr" + (take.size() == 1 ? "y" : "ies") + (entries.size() > take.size() ? " of " + entries.size() : "") + ", " + shelved + " saved in the library in collection " + collection
                + (requested > 0 ? ", " + requested + " could not be read and are on the request list" : "") + (unlocated > 0 ? ", " + unlocated + " have a title but no DOI or url" : "") + (watch ? ". Nightly maintenance re-reads the pages" : ""));
        r.put("next", "To research from them, use library_research {sources: \"shelves\", collections: [\"" + collection + "\"]}" + (unlocated > 0 ? ". Titles without a DOI or url can go through library_items to be researched by name" : "") + (requested > 0 ? ". If an entry could not be read, supply it with researchzosho add <file> --for <url>" : ""));
        store.circulate("reading", patron.writer() + " :: " + Acquisitions.compress(title, 80) + " — " + shelved + " shelved, " + requested + " requested, " + unlocated + " unlocated");
        notChecked(r, g.unchecked());
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
        if (qs.isEmpty()) throw ProtocolError.invalidArgs("No questions found. Put one question per line.");
        String as = args.path("as").asText("frontier").strip().toLowerCase(Locale.ROOT);
        if (!as.equals("frontier") && !as.equals("runs")) throw ProtocolError.invalidArgs("as must be frontier (add them to the open questions, in order) or runs (start a research run for each, up to " + Questions.MAX_RUNS + ". The rest go to the open questions).");
        String title = args.path("title").asText("").strip(); if (title.isEmpty()) title = Conversations.titleFrom(g.nameHint());
        int limit = Math.max(1, args.path("limit").asInt(Questions.MAX));
        List<String> take = qs.subList(0, Math.min(limit, qs.size()));
        List<Frontier.Line> open = Frontier.read(store);
        ObjectNode r = envelope();
        r.put("title", title); r.put("source", g.source()); r.put("as", as);
        r.put("questions_found", qs.size()); r.put("taken", take.size()); r.put("remaining", qs.size() - take.size());
        ArrayNode out = r.putArray("questions"); ArrayNode jobs = r.putArray("jobs");
        int filed = 0, already = 0, notStarted = 0;
        for (int i = 0; i < take.size(); i++) {
            String q = take.get(i);
            ObjectNode n = out.addObject(); n.put("question", q);
            if (as.equals("runs") && jobs.size() + notStarted < Questions.MAX_RUNS) {
                ObjectNode ask = M.createObjectNode(); ask.put("question", q); ask.put("mode", "broad"); ask.put("sources", args.path("sources").asText("both")); ask.set("patron", args.path("patron").deepCopy());
                if (args.hasNonNull("field")) ask.put("field", args.path("field").asText());
                if (args.hasNonNull("allow")) ask.set("allow", args.get("allow").deepCopy()); if (args.hasNonNull("locale")) ask.put("locale", args.path("locale").asText());   // what the person let in for these runs, as they named it
                ObjectNode run = fileRun(ask, r);
                if (filed(run)) { String id = run.path("job_id").asText(); jobs.add(id); n.put("job_id", id); }
                else { n.put("state", "not_started"); n.put("help", run.path("help").asText()); notStarted++; }   // not filed, and not put on the open questions either
                continue;
            }
            boolean ok = Items.file(store, open, q, patron.writer(), title);
            n.put("filed", ok); if (ok) filed++; else already++;
        }
        r.put("questions_filed", filed); r.put("questions_already_open", already);
        r.put("summary", "questions \"" + Acquisitions.compress(title, 60) + "\": " + take.size() + (qs.size() > take.size() ? " of " + qs.size() : "") + " question(s). " + (jobs.size() > 0 ? jobs.size() + " started as research runs. " : "")
                + (notStarted > 0 ? notStarted + " were not filed, because they read as a person asking about harming themselves: each says where to find help. " : "") + filed + " were added to the open questions, in order" + (already > 0 ? ". " + already + " were already open" : ""));
        r.put("next", jobs.size() > 0 ? "library_job shows each run's progress. The rest wait in the open questions for the nightly research." : "The nightly research works through them in this order. library_frontier op=next moves one to the front. as=runs starts the first " + Questions.MAX_RUNS + " now.");
        store.circulate("questions", patron.writer() + " :: " + Acquisitions.compress(title, 80) + " — " + filed + " filed, " + jobs.size() + " run(s)");
        notChecked(r, g.unchecked());
        return r;
    }

    /**
     * library_bookmarks: a browser's bookmarks (the exported bookmarks file, Chrome's Bookmarks JSON, or lines
     * of urls) fetched onto the shelves as a collection; watch=true registers the list so the housekeeping
     * re-reads the pages and keeps a new capture when one changes.
     */
    /**
     * sources (0.6.6, docs/DESIGN_SOURCES.md): the places the library mines. op=list (field?, for?, off=true to include the ones taken
     * off) and op=show (id) need read access; op=add (address, for?, field?, kind?, yields?, note?), op=remove (id) and op=restore (id)
     * need write access. Each answer has {@code sources[]} or {@code source}, {@code text} in sentences, and {@code note} for a change.
     */
    public ObjectNode sources(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("list").strip().toLowerCase();
        ObjectNode r = envelope();
        String how = args.path("patron").path("runtime").asText("").startsWith("web") ? "/sources" : "researchzosho sources";
        switch (op) {
            case "list" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                try { CareerBoards.seed(store); } catch (IOException seeding) { /* the list is shown as it is */ }   // the boards the library knows are on the list from the first look
                List<Sources.Source> all = Sources.all(store), out = new ArrayList<>();
                String field = args.path("field").asText("").strip(), words = args.path("for").asText("").strip();
                boolean off = args.path("off").asBoolean(false);
                for (Sources.Source x : all) {
                    if (!off && x.off()) continue;
                    if (!field.isEmpty() && !x.field().equalsIgnoreCase(field)) continue;
                    if (!words.isEmpty() && !(x.address() + " " + x.subject() + " " + x.place() + " " + x.forWhom() + " " + x.note()).toLowerCase().contains(words.toLowerCase())) continue;
                    out.add(x);
                }
                r.set("sources", Sources.json(out)); r.put("text", Sources.text(out, true, how));
            }
            case "show" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                Sources.Source x = Sources.get(store, args.path("id").asText(""));
                if (x == null) throw ProtocolError.invalidArgs("No source has the id " + args.path("id").asText("") + ". " + how + " lists them.");
                r.set("source", Sources.json(List.of(x)).get(0)); r.put("text", x.line());
            }
            case "add" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String address = args.path("address").asText("").strip();
                if (address.isEmpty()) throw ProtocolError.invalidArgs("A source needs an address, or the words of a search to run again.");
                Sources.Source x;
                try { x = Sources.add(store, args.path("kind").asText(""), address, args.path("yields").asText(""), args.path("field").asText(""), "", "", args.path("for").asText(""), "you", args.path("note").asText("")); }
                catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
                r.set("source", Sources.json(List.of(x)).get(0)); r.put("note", x.shelf() ? "Kept as " + x.id() + ", a search the nightly check runs again every 7 days; what it finds new lands on the arrival table for you to admit. researchzosho shelf every " + x.name() + " <days> changes how often."
                        : "Kept as " + x.id() + ", a " + x.kind() + (x.forWhom().isEmpty() ? "" : " for " + x.forWhom()) + ". The next search of " + (x.field().isEmpty() ? "any field" : "the " + x.field() + " field") + " reads it."); r.put("text", x.line());
            }
            case "remove", "restore" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String id = args.path("id").asText("").strip();
                boolean done = op.equals("remove") ? Sources.remove(store, id) : Sources.restore(store, id);
                if (!done) throw ProtocolError.invalidArgs("No source has the id " + id + ". " + how + " lists them.");
                boolean shelf = id.toLowerCase().startsWith(Sources.SHELF);
                r.put("note", op.equals("remove") ? (shelf ? "Parked: " + id + ". The nightly check does not run it until you bring it back." : "Taken off: " + id + ". No search reads it until you bring it back.") : (shelf ? "Back in the rotation: " + id + ". The nightly check runs it again on its days." : "Brought back: " + id + ". The next search reads it.")); r.put("text", r.path("note").asText());
            }
            default -> throw ProtocolError.invalidArgs("op is list, show, add, remove or restore.");
        }
        return r;
    }

    /**
     * library_crews: the housekeeping. op=plan (read): what the next run will do, from the same files and the same plan it uses — the
     * searches due and the ones not yet due, the open questions the explorer takes and in what bundles, the counts; op=run (write):
     * the tasks filed as a job now, which the service runs behind any run in progress.
     */
    public ObjectNode crews(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("plan").strip().toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        switch (op) {
            case "plan" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                Tonight.Plan p = Tonight.plan(store);
                r.put("date", p.date().toString()).put("hour", p.hour());
                for (Map.Entry<String, List<Serials.Shelf>> part : List.of(Map.entry("due", p.due()), Map.entry("later", p.later()))) {
                    ArrayNode a = r.putArray(part.getKey());
                    for (Serials.Shelf sh : part.getValue()) a.addObject().put("name", sh.slug()).put("query", sh.query()).put("every_days", sh.everyDays()).put("last", sh.lastChecked()).put("parked", sh.parked());
                }
                ArrayNode runs = r.putArray("runs");
                for (Crews.Bundle b : p.bundles()) {
                    ObjectNode o = runs.addObject().put("question", b.question()).put("type", b.head().type());
                    ArrayNode more = o.putArray("more"); for (Frontier.Line l : b.more()) more.add(Frontier.strip(l.text()));
                }
                r.put("queued", p.queued()).put("parked", p.parked()).put("budget", p.budget()).put("standing", p.standing()).put("override", p.override()).put("still_open", p.stillOpen()).put("weekly", p.weekly()).put("monthly", p.monthly());
                String text = Tonight.text(p);
                r.put("text", text);
                r.put("summary", text.lines().findFirst().orElse("") + " " + p.due().size() + (p.due().size() == 1 ? " kept search is due" : " kept searches are due") + " and the explorer takes " + p.picks().size() + (p.picks().size() == 1 ? " open question" : " open questions") + " in " + p.bundles().size() + (p.bundles().size() == 1 ? " run." : " runs."));
            }
            case "run" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
                jobs.migrate();
                String id = jobs.submit("crews", patron.did(), M.createObjectNode());
                r.put("job_id", id).put("state", "queued");
                r.put("summary", "The housekeeping's tasks are filed as " + id + "; the service runs them behind any run in progress. library_job reads how they go.");
            }
            default -> throw ProtocolError.invalidArgs("op is plan or run.");
        }
        return r;
    }

    /**
     * library_spend: what research runs cost, at each model's list prices — the library's arithmetic on the servers' usage counts, not
     * a bill — this month and in all, by host and by named model, with a cap where one is set (write access, as the settings).
     */
    public ObjectNode spend(JsonNode args) throws IOException {
        Patrons.check(store, Patrons.Patron.from(args), Patrons.Level.write);
        ObjectNode r = envelope();
        ObjectNode sum = Spend.summary(store);
        r.setAll(sum);
        StringBuilder t = new StringBuilder("What research runs cost, at each model's list prices (the library's arithmetic on the servers' usage counts, not a bill). This month is " + sum.path("month").asText() + ".\n");
        for (String part : new String[]{"hosts", "entries"}) {
            if (sum.path(part).isEmpty()) continue;
            t.append(part.equals("hosts") ? "  By host:\n" : "  By named model:\n");
            for (JsonNode row : sum.path(part)) t.append("    ").append(row.path("name").asText()).append(": ").append(Spend.dollars(row.path("month").asDouble())).append(" this month (").append(row.path("runs_month").asInt()).append(row.path("runs_month").asInt() == 1 ? " run" : " runs").append("), ").append(Spend.dollars(row.path("all").asDouble())).append(" in all (").append(row.path("runs_all").asInt()).append(")").append(row.has("cap") ? ", cap " + Spend.dollars(row.path("cap").asDouble()) + " a month" : "").append('\n');
        }
        if (sum.path("hosts").isEmpty()) t.append("  No run has been recorded yet. A run on a named model with prices is counted when it files its report.\n");
        r.put("text", t.toString());
        double month = 0; for (JsonNode row : sum.path("hosts")) month += row.path("month").asDouble();
        r.put("summary", sum.path("hosts").isEmpty() ? "No run has been recorded yet." : "Research runs have cost " + Spend.dollars(month) + " this month at list prices, over " + sum.path("hosts").size() + (sum.path("hosts").size() == 1 ? " host." : " hosts."));
        return r;
    }

    /** How a podcast feed is read for its show's title before it is followed: the feed itself; a test replaces it. Null when it did not answer or is not a podcast feed. */
    public static volatile Function<String, String> PODCAST_TITLE = feed -> { var fe = Podcasts.feed(feed, 1); return fe == null ? null : fe.getKey().title(); };

    /**
     * library_follow: the podcasts followed — a kept search whose query names the feed, so its new episodes land on the arrival table
     * and the housekeeping transcribes them at night. op=list (read); op=follow (write) with feed and every (days, 1 unless set);
     * op=unfollow (write) with which, the show's name as listed or its feed address. The transcripts already in the library stay.
     */
    public ObjectNode follow(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("list").strip().toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        switch (op) {
            case "list" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                List<Serials.Shelf> shows = PodcastsPage.followed(store);
                ArrayNode a = r.putArray("shows");
                for (Serials.Shelf sh : shows) a.addObject().put("name", sh.slug()).put("feed", sh.feedUrl()).put("every_days", sh.everyDays()).put("last", sh.lastChecked()).put("parked", sh.parked());
                r.put("count", shows.size()); r.put("summary", PodcastsPage.stateLine(shows));
            }
            case "follow" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String feed = reqStr(args, "feed").strip();
                int every = Math.max(1, args.path("every").asInt(1));
                List<Serials.Shelf> shelves = new ArrayList<>(Serials.shelves(store));
                for (Serials.Shelf sh : shelves) if (sh.podcast() && sh.feedUrl().equalsIgnoreCase(feed)) {
                    r.put("name", sh.slug()).put("feed", sh.feedUrl()).put("every_days", sh.everyDays()).put("already", true).put("summary", "Already following " + sh.slug() + " (" + sh.feedUrl() + ").");
                    return r;
                }
                String title = PODCAST_TITLE.apply(feed);
                if (title == null) throw ProtocolError.invalidArgs("The feed at " + feed + " did not answer or is not a podcast feed.");
                String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
                if (slug.isEmpty()) slug = "podcast";
                String base = slug; int n = 2;
                while (true) { String want = slug; if (shelves.stream().noneMatch(sh -> sh.slug().equals(want))) break; slug = base + "-" + n++; }
                shelves.add(new Serials.Shelf(slug, Serials.PODCAST + feed, every, "", false));
                Serials.writeShelves(store, shelves);
                r.put("name", slug).put("title", title).put("feed", feed).put("every_days", every).put("already", false);
                r.put("summary", "Following " + title + " as " + slug + ": its new episodes arrive on the arrival table every " + (every == 1 ? "day" : every + " days") + ", and the housekeeping transcribes them at night. op=unfollow with which=" + slug + " stops it.");
            }
            case "unfollow" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String which = reqStr(args, "which").strip();
                List<Serials.Shelf> shelves = new ArrayList<>(Serials.shelves(store));
                boolean removed = shelves.removeIf(sh -> sh.podcast() && (sh.slug().equalsIgnoreCase(which) || sh.feedUrl().equalsIgnoreCase(which)));
                if (!removed) throw ProtocolError.invalidArgs("No followed podcast is " + which + ". op=list names them.");
                Serials.writeShelves(store, shelves);
                r.put("name", which).put("removed", true).put("summary", "No longer following " + which + ". The transcripts already in the library stay.");
            }
            default -> throw ProtocolError.invalidArgs("op is list, follow or unfollow.");
        }
        return r;
    }

    /**
     * library_settings (0.5.5): op=list — every setting the library has, with its value as it may be shown (a secret only as set or not);
     * op=set with name and value writes one (a blank value unsets it). Both need write access: the settings say where the models and the
     * keys are. A value that cannot be the setting's is refused with the reason. op=read_in with value (0.7.0) sets the caller's own
     * language to read reports in, a blank unsets it; read access is enough, and list carries it as read_in.
     */
    public ObjectNode settings(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("list").strip().toLowerCase().replace('-', '_');
        ObjectNode r = envelope();
        if (op.equals("read_in")) {   // the caller's own language to read reports in (0.7.0): theirs to set, whatever they may do to the library
            Patrons.check(store, patron, Patrons.Level.read);
            r.put("note", ReadIn.set(store, patron, args.path("value").asText("")));
            r.put("read_in", ReadIn.of(store, patron));
            return r;
        }
        Patrons.check(store, patron, Patrons.Level.write);
        if (op.equals("list")) {
            ArrayNode settings = Settings.list(M);
            for (JsonNode s : settings) {   // a key's service: what it adds, its terms with the day they were read, the link to get one, this month's use, the last check
                OutsideServices.Service svc = OutsideServices.of(s.path("key").asText(""));
                if (svc == null) continue;
                ObjectNode o = (ObjectNode) s;
                o.put("service", svc.name()).put("adds", svc.adds()).put("terms", svc.terms()).put("checked_on", svc.checkedOn()).put("link", svc.link());
                for (JsonNode row : OutsideServices.json(store)) if (row.path("service").asText().equals(svc.name())) { o.put("use_this_month", row.path("use_this_month").asText()); if (row.has("last_check")) o.set("last_check", row.get("last_check")); }
            }
            r.set("settings", settings); r.set("services", OutsideServices.json(store)); r.put("config_file", Config.userConfigPath().toString()); r.put("read_in", ReadIn.of(store, patron)); return r;
        }
        if (op.equals("check")) {   // every service with a key asked whether it is accepted and what is left; the answers kept and returned
            List<OutsideServices.Result> rs = OutsideServices.check(store);
            ArrayNode a = r.putArray("checks");
            for (OutsideServices.Result c : rs) { ObjectNode o = a.addObject(); o.put("service", c.service()); if (c.accepted() != null) o.put("accepted", c.accepted()); o.put("said", c.said()); o.put("at", c.at()); }
            r.put("note", Pages.keysChecked(rs));
            return r;
        }
        if (!op.equals("set") && !op.equals("unset")) throw ProtocolError.invalidArgs("op is list, set, unset, read_in or check.");
        String name = args.path("name").asText("").strip();
        Settings.Setting s = Settings.named(name);
        if (s == null) throw ProtocolError.invalidArgs("No setting is named " + name + ". library_settings op=list names them all.");
        String value = op.equals("unset") ? "" : args.path("value").asText("");
        String why = Settings.refuse(s, value);
        if (!why.isEmpty()) throw ProtocolError.invalidArgs(why);
        Settings.set(s, value);
        r.put("key", s.key()); r.put("name", s.shortName()); r.put("set", !value.isBlank()); r.put("value", Settings.shown(s, value));
        r.put("note", value.isBlank() ? s.shortName() + " is unset." : s.shortName() + " is set." + (s.key().equals("RESEARCHZOSHO_JOB_WORKERS") ? " It takes effect at the service's next restart." : ""));
        return r;
    }

    /**
     * mcp (0.7.0): the other MCP servers whose tools the library's research may use as sources, the keeper's alone. op=list (each server,
     * how it is reached, whether it answers when check is true, the default, and its tools with whether research may call each); tools or
     * test (name); add (name, and command[] with env{} for a program this computer starts, or url with header_key, header and header_value
     * for a server at an address, or a preset's name alone: exa, tavily; readers, cap, timeout); remove (name); allow and forbid (name,
     * tool); readers (name, value on|off); cap (name, value: calls a month, or off); call (name, tool, arguments). A key is never returned.
     */
    public ObjectNode mcp(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        return McpSetup.op(store, patron, args, envelope());
    }

    /** How a model entry is probed for library_models op=test: a functional probe of its server, which a test replaces with a stand-in. */
    public static volatile Function<ModelEntries.Entry, Crews.DriveState> MODEL_PROBE = e -> Crews.driveState(e.drive(), e.model(), Duration.ofSeconds(30));
    /** Whether an entry sees pictures, probed with a small square ({@link Vision}); a test replaces it with a stand-in. */
    public static volatile Function<ModelEntries.Entry, Vision.Probe> VISION_PROBE = Vision::probe;

    /** What a hosted entry's add says about pictures when nothing probed it. */
    static String notProbedHosted(String name) { return "sees: not probed; researchzosho models test " + name + " asks it (one small paid call)."; }

    /**
     * library_models: the models a library can run on, by name — list; add (name, drive, model, prices, effort, cap, note); remove (name);
     * key (host, value — a blank value forgets it); test (name: whether the entry's server answers now, as the command line's
     * {@code models test} says). A key is never returned, only whether one is kept, as a word: kept, missing, or not needed for a local
     * server. The list states the default outright, as {@code default} (name, model, host, drive) and in a {@code summary} sentence,
     * because a model reading {@code key: true} on a local entry took it for the rule that picks the default (2026-11-08).
     */
    public ObjectNode models(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String op = args.path("op").asText("list").strip().toLowerCase();
        ObjectNode r = envelope();
        try {
            switch (op) {
                case "list" -> { r.set("default", ModelEntries.defaultNode(M)); r.set("models", ModelEntries.list(M)); r.put("summary", ModelEntries.summary()); r.put("config_file", Config.userConfigPath().toString()); }
                case "add" -> {
                    String name = args.path("name").asText("").strip();
                    ModelEntries.Entry e = ModelEntries.put(name, args.hasNonNull("drive") ? args.path("drive").asText() : null, args.hasNonNull("model") ? args.path("model").asText() : null,
                            args.hasNonNull("prices") ? args.path("prices").asText() : null, args.hasNonNull("effort") ? args.path("effort").asText() : null,
                            args.hasNonNull("cap") ? args.path("cap").asText() : null, args.hasNonNull("note") ? args.path("note").asText() : null, args.hasNonNull("tier") ? args.path("tier").asText() : null, args.hasNonNull("sees") ? args.path("sees").asText() : null);
                    // a local entry is probed for pictures at once (its calls are free) when its server answers now and nothing said; a hosted one is not, since the call is paid
                    String pictures;
                    if (e.sees() != null) pictures = e.seesText() + (args.hasNonNull("sees") ? ", as said" : "") + ".";
                    else if (e.hosted()) pictures = notProbedHosted(e.name());
                    else if (args.hasNonNull("drive") && !args.hasNonNull("model") && e.name().equals(ModelEntries.DEFAULT)) pictures = "";
                    else if (MODEL_PROBE.apply(e) == Crews.DriveState.ANSWERS) { Vision.Probe pr = VISION_PROBE.apply(e); ModelEntries.sees(e.name(), pr.sees()); e = e.withSees(pr.sees()); pictures = e.seesText() + " (" + pr.why() + ")."; }
                    else pictures = "sees: not probed, its server does not answer now; researchzosho models test " + e.name() + " asks it when it does.";
                    r.put("name", e.name()); r.put("line", e.line()); r.put("key", ModelEntries.keyWord(e));
                    if (e.sees() != null) r.put("sees", e.sees());
                    r.put("sees_text", e.seesText());
                    r.put("note", e.line() + (DriveClient.hostKey(e.host()) == null && e.hosted() && !e.name().equals(ModelEntries.DEFAULT) ? ". No key is kept for " + e.host() + " yet: researchzosho models key " + e.host() + " <key>" : "") + (pictures.isEmpty() ? "" : " " + pictures));
                }
                case "remove" -> { String name = args.path("name").asText("").strip(); ModelEntries.remove(name); r.put("name", name.toLowerCase(Locale.ROOT)); r.put("note", "The entry " + name + " is gone."); }
                case "test" -> {   // the entry's server asked for a completion now: answers, is starting, or does not answer
                    String name = args.path("name").asText("").strip();
                    if (name.isEmpty()) throw ProtocolError.invalidArgs("test needs the entry's name; op=list names them.");
                    ModelEntries.Entry e = ModelEntries.get(name);
                    if (e == null) throw ProtocolError.invalidArgs("No entry is named " + name + ". op=list names them.");
                    Crews.DriveState st = MODEL_PROBE.apply(e);
                    boolean noKey = e.hosted() && DriveClient.hostKey(e.host()) == null;
                    String state = st == Crews.DriveState.ANSWERS ? "answers" : st == Crews.DriveState.STARTING ? "starting" : "down";
                    r.put("name", e.name()); r.put("line", e.line()); r.put("answers", st == Crews.DriveState.ANSWERS); r.put("state", state);
                    String pictures = "";
                    if (st == Crews.DriveState.ANSWERS) {   // then whether it sees pictures, kept on the entry: one small call, two on a local entry
                        Vision.Probe pr = VISION_PROBE.apply(e);
                        ModelEntries.sees(e.name(), pr.sees());
                        if (pr.sees() != null) r.put("sees", pr.sees());
                        r.put("sees_text", pr.word()); r.put("sees_why", pr.why());
                        pictures = " " + Character.toUpperCase(pr.word().charAt(0)) + pr.word().substring(1) + " (" + pr.why() + ").";
                    }
                    r.put("note", e.line() + ": " + (st == Crews.DriveState.ANSWERS ? "answers." + pictures : st == Crews.DriveState.STARTING ? "is starting; ask again in a moment." : "does not answer." + (noKey ? " No key is kept for " + e.host() + ": op=key with host and value keeps one." : "")));
                }
                case "key" -> {
                    String host = args.path("host").asText("").strip();
                    if (host.isEmpty()) throw ProtocolError.invalidArgs("key needs the host (openrouter.ai, or a word: openrouter, anthropic, openai, gemini, nebius, huggingface).");
                    String h = DriveClient.hostOf(ModelEntries.hostAddress(host)); if (h == null) h = host.toLowerCase(Locale.ROOT);
                    String value = args.path("value").asText("").strip();
                    Config.set(DriveClient.hostKeySetting(h), value);
                    r.put("host", h); r.put("set", !value.isEmpty()); r.put("note", value.isEmpty() ? "The key for " + h + " is forgotten." : "A key is kept for " + h + " (ends with …" + value.substring(Math.max(0, value.length() - 3)) + "). It goes to that host and nowhere else.");
                }
                default -> throw ProtocolError.invalidArgs("op is list, add, remove, key or test.");
            }
        } catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
        return r;
    }

    public ObjectNode bookmarks(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        Given g = given(args, patron, "bookmarks", "a bookmarks export (.html), Chrome's Bookmarks file, or one url per line");
        List<Bookmarks.Mark> marks = Bookmarks.parse(new String(g.bytes(), StandardCharsets.UTF_8));
        String folder = args.path("folder").asText("").strip();
        if (!folder.isEmpty()) marks = marks.stream().filter(m -> m.folder().equalsIgnoreCase(folder)).toList();
        if (marks.isEmpty()) throw ProtocolError.invalidArgs(folder.isEmpty() ? "No bookmarks found. Give a browser's exported bookmarks file, Chrome's Bookmarks JSON, or one url per line." : "No bookmarks in a folder named " + folder + ".");
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
            if (got.shelved()) { shelved++; n.put("raw", got.raw().getFileName().toString()); n.put("state", "held".equals(got.kind()) ? "already held" : "shelved"); if (got.unchecked()) n.put("note", PageCheck.NOT_CHECKED_YET); }
            else { n.put("state", got.requested() ? "requested" : "not read"); n.put("problem", got.problem()); if (got.requested()) requested++; }
        }
        boolean watch = args.path("watch").asBoolean(false);
        if (watch && list != null) Corpus.registerUrls(store, collection, list.getFileName().toString());
        r.put("shelved", shelved); r.put("requested", requested); r.put("watching", watch && list != null);
        r.put("summary", "bookmarks" + (folder.isEmpty() ? "" : " in " + folder) + ": " + take.size() + (marks.size() > take.size() ? " of " + marks.size() : "") + " page(s), " + shelved + " on the shelves in collection " + collection + (requested > 0 ? ", " + requested + " could not be read and are on the request list" : "") + (watch ? ". Nightly maintenance re-reads them and keeps a new copy when one changes" : ""));
        r.put("next", "To research from them, use library_research {sources: \"shelves\", collections: [\"" + collection + "\"]}" + (watch ? "" : ". watch=true makes nightly maintenance re-read the pages"));
        store.circulate("bookmarks", patron.writer() + " :: " + title + " — " + shelved + " shelved, " + requested + " requested" + (watch ? ", watching" : ""));
        notChecked(r, g.unchecked());
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
        if (!o.declined().isEmpty()) r.put("declined", o.declined());   // the transcript, its questions and decisions are saved; the model declined its claims
        if (args.path("verify").asBoolean(false) && !o.claims().isEmpty()) {
            ObjectNode ask = M.createObjectNode();
            ask.put("question", Meetings.verifyQuestion(title, o.claims())); ask.put("mode", "depth"); ask.put("sources", "both");
            ask.set("patron", args.path("patron").deepCopy());
            if (args.hasNonNull("field")) ask.put("field", args.path("field").asText());   // the checking run in the field the person asked for
            if (args.hasNonNull("allow")) ask.set("allow", args.get("allow").deepCopy()); if (args.hasNonNull("locale")) ask.put("locale", args.path("locale").asText());   // what the person let in for these runs, as they named it
            ObjectNode job = fileRun(ask, r);
            if (filed(job)) r.put("verify_job_id", job.path("job_id").asText());
            else r.putArray("not_started").addObject().put("question", ask.path("question").asText("")).put("help", job.path("help").asText());
        }
        r.put("summary", "meeting \"" + Acquisitions.compress(title, 60) + "\": " + o.speakers().size() + " speaker(s). " + o.questionsFiled().size() + " question(s) raised were added to the open questions. " + o.claims().size() + " claim(s) to check, " + o.decisions().size() + " decision(s) were saved with the transcript"
                + (r.hasNonNull("verify_job_id") ? ". A research run is checking the claims (" + r.path("verify_job_id").asText() + ")." : ". Nothing is saved as a claim.")
                + (o.declined().isEmpty() ? "" : " The model declined to list the claims made in the meeting, so there are none to check. " + o.declined()));
        r.put("next", r.hasNonNull("verify_job_id") ? "library_job shows the check's progress." : "verify=true starts one research run that checks the claims. library_ask finds the decisions later.");
        notChecked(r, g.unchecked());
        return r;
    }

    /**
     * library_bridges: discovery by combination. op=run computes candidate pairs from an area (or the hottest) and
     * files up to per_night proposals as open questions of type bridge (dry=true only shows them); op=list the open
     * proposals; op=accept files the research run that tests one; op=dismiss drops it; op=settings reads or sets
     * an area's dials; op=measure reads the ledger: proposed, kept, dismissed, corroborated, by settings.
     */
    /**
     * Seeks (0.6.6): op=list the groups, each seek with its model, sources, state and report; op=read a seek's report (group, job_id) or the
     * merged one (group alone); op=merge files the merge run (write); op=drop removes a group's seeks and their shelves (write; the merged
     * report stays).
     */
    public ObjectNode seeks(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("list").strip().toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        switch (op) {
            case "list" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                ArrayNode groups = r.putArray("groups");
                for (ObjectNode g : Seeks.list(store)) {
                    ObjectNode o = groups.addObject();
                    o.put("group", g.path("group").asText("")); o.put("question", g.path("question").asText(""));
                    ArrayNode ss = o.putArray("seeks");
                    for (String job : Seeks.jobsOf(g)) {
                        JsonNode s = g.path("seeks").path(job);
                        ObjectNode e = ss.addObject();
                        e.put("job_id", job); e.put("model", s.path("model").asText("")); e.put("sources", s.path("sources").asText("")); e.put("investigation", s.path("investigation").asText(""));
                        ObjectNode j = jobs.get(job);
                        e.put("state", j == null ? "" : j.path("state").asText(""));
                    }
                    if (g.has("merged")) o.set("merged", g.get("merged").deepCopy());
                }
                return r;
            }
            case "read" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                String group = Seeks.checkedName(reqStr(args, "group"));
                String job = args.path("job_id").asText("").strip();
                Investigation inv = Seeks.read(store, group, job);
                if (inv == null) throw ProtocolError.notFound(job.isEmpty() ? "The seeks of " + group + " have not been merged yet. op=merge writes the merged report." : "Seek " + job + " of " + group + " has no report yet.");
                r.put("group", group); r.put("job_id", job); r.put("id", inv.id()); r.put("title", inv.title()); r.put("state", inv.state().name()); r.put("body", inv.body());
                ArrayNode f = r.putArray("findings"); for (String id : inv.findings()) f.add(id);
                return r;
            }
            case "merge" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String group = Seeks.checkedName(reqStr(args, "group"));
                ObjectNode g = Seeks.group(store, group);
                int landed = 0; for (String job : Seeks.jobsOf(g)) if (!g.path("seeks").path(job).path("investigation").asText("").isEmpty()) landed++;
                if (landed < 2) throw ProtocolError.invalidArgs("A merge needs at least two seeks that landed; " + group + " has " + landed + " so far.");
                Jobs filing = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
                String id = filing.submit(Seeks.KIND_MERGE, patron.did(), M.createObjectNode().put("group", group));
                r.put("job_id", id); r.put("state", "queued"); r.put("group", group);
                return r;
            }
            case "drop" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String group = Seeks.checkedName(reqStr(args, "group"));
                ObjectNode g = Seeks.group(store, group);
                for (String job : Seeks.jobsOf(g)) {
                    ObjectNode j = jobs.get(job);
                    String st = j == null ? "" : j.path("state").asText("");
                    if (st.equals("queued") || st.equals("running")) throw ProtocolError.invalidArgs("Seek " + job + " of " + group + " is " + st + ". Stop it first (library_job op=stop), or wait for it.");
                }
                if (!Seeks.drop(store, group)) throw ProtocolError.notFound("No seeks are named " + group + ".");
                r.put("group", group); r.put("dropped", true);
                return r;
            }
            default -> throw ProtocolError.invalidArgs("op is list, read, merge or drop.");
        }
    }

    public ObjectNode bridges(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("list").strip().toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        switch (op) {
            case "list" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                ArrayNode items = r.putArray("proposals");
                for (Frontier.Line l : Bridges.open(store)) {
                    String evidence = Bridges.evidenceFor(store, l.text());
                    ObjectNode o = items.addObject(); o.put("question", l.text()); o.put("date", l.date()); o.put("found_by", l.kind()); o.put("evidence", evidence);
                }
                r.put("count", items.size());
                r.put("next", items.size() == 0 ? "op=run suggests some. op=run with dry=true only shows the pairs." : "op=accept with the question starts the research run that tests it. op=dismiss drops it.");
            }
            case "run" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String area = args.path("area").asText("").strip();
                Bridges.Settings s = Bridges.settings(store, area.isEmpty() ? null : area);
                for (String k : new String[]{"sources", "reach", "toward", "away", "since", "via"}) if (args.hasNonNull(k) && !args.get(k).asText().isBlank()) s = s.with(k, args.get(k).asText());
                if (args.hasNonNull("strict")) s = s.with("strict", args.get("strict").asBoolean() ? "true" : "false");
                if (args.hasNonNull("propose")) s = s.with("propose", String.valueOf(args.get("propose").asInt()));
                boolean dry = args.path("dry").asBoolean(false);
                ObjectNode out = Bridges.propose(store, Explain.drive(), Researcher.webTools(), s, area.isEmpty() ? null : area, dry, patron.writer(), new Random());
                r.setAll(out);
                r.put("next", dry ? "The same call without dry adds the suggestions to the open questions." : "op=list shows them. op=accept starts the research run that tests one.");
            }
            case "accept", "dismiss" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String q = reqStr(args, "question").strip();
                Frontier.Line found = null;
                for (Frontier.Line l : Bridges.open(store)) if (Frontier.sameQuestion(l.text(), q) || l.text().toLowerCase(Locale.ROOT).startsWith(q.toLowerCase(Locale.ROOT))) { found = l; break; }
                if (found == null) throw ProtocolError.notFound("No open connection question matches \"" + Acquisitions.compress(q, 80) + "\"");
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
                    ObjectNode run = fileRun(ask, r);
                    if (!filed(run)) {
                        // not filed: the proposal stays open, and no kept bridge is written down without a run
                        r.put("question", found.text()); r.put("state", "not_started"); r.put("help", run.path("help").asText());
                        r.put("next", "Nothing was started. The proposal stays open.");
                    } else {
                        String job = run.path("job_id").asText();
                        Frontier.markExplored(store, found.text(), job);
                        Bridges.fate(store, found.text(), "kept", job);
                        r.put("accepted", found.text()); r.put("job_id", job);
                        r.put("next", "library_job shows the run's progress. The claims it verifies show whether the connection holds.");
                    }
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
        if (claim.length() < 20) throw ProtocolError.invalidArgs("The claim is too short. Write one to three complete sentences.");
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

    /** library_suggest: what the library suggests the person do next, from the shelves' own events (read; op=list, the default, refreshes with the rules); op=mark (write): id, outcome (taken, dismissed, later), note. */
    public ObjectNode suggest(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("list").toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        if (op.equals("mark")) {
            Patrons.check(store, patron, Patrons.Level.write);
            Suggestions.Entry e;
            try { e = Suggestions.mark(store, reqStr(args, "id"), reqStr(args, "outcome"), args.path("note").asText("")); }
            catch (IllegalArgumentException x) { throw ProtocolError.invalidArgs(x.getMessage()); }
            if (e == null) throw ProtocolError.notFound("suggestion " + args.path("id").asText());
            r.put("id", e.id()); r.put("outcome", e.outcome()); r.put("summary", "Marked " + e.id() + " " + e.outcome() + ".");
            Double brier = Suggestions.brier(Suggestions.all(store)); if (brier != null) r.put("brier", brier);
            return r;
        }
        Patrons.check(store, patron, Patrons.Level.read);
        List<Suggestions.Entry> open = Suggestions.refresh(store, null), all = Suggestions.all(store);
        ArrayNode a = r.putArray("suggestions");
        for (Suggestions.Entry e : open) {
            ObjectNode o = a.addObject(); o.put("id", e.id()); o.put("kind", e.kind()); o.put("subject", e.subject()); o.put("text", e.text()); o.put("reason", e.reason()); o.put("action", e.action()); o.put("cost", e.cost());
            o.put("made", e.made()); o.put("class", e.klass()); if (e.pTake() != null) o.put("p_take", e.pTake()); o.put("judge", e.judge());
        }
        r.put("count", open.size()); Double brier = Suggestions.brier(all); if (brier != null) r.put("brier", brier); r.put("summary", Suggestions.text(open, all));
        return r;
    }

    /**
     * library_dossier: everything the library holds on a person or an organisation, as one document (read); op=list, or no subject, lists
     * the dossiers the shelves hold (read); op=story writes the story of it as a report (write); op=claims files its reports' dated events (write).
     */
    public ObjectNode dossier(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("read").toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        if (op.equals("read") && !args.path("in").asText("").isBlank()) {   // the dossier read in another language: kept when current, else translated now by the library's model
            Translate.Translated t = Translate.dossierIn(store, reqStr(args, "subject"), args.path("in").asText(), Translate.DRIVE.get());
            r.put("subject", t.subject()); r.put("language", t.lang()); r.put("markdown", t.markdown()); r.put("fresh", t.fresh()); r.put("summary", t.summary());
            return r;
        }
        if (op.equals("list") || (op.equals("read") && args.path("subject").asText("").isBlank())) {
            Patrons.check(store, patron, Patrons.Level.read);
            List<Dossiers.Entry> all = Dossiers.list(store);
            ArrayNode a = r.putArray("dossiers");
            for (Dossiers.Entry e : all) { ObjectNode o = a.addObject(); o.put("subject", e.subject()); o.put("kind", e.kind()); o.put("claims", e.claims()); o.put("reports", e.reports()); o.put("last_run", e.lastRun()); o.put("story", e.story()); }
            r.put("count", all.size()); r.put("summary", Dossiers.text(all));
            return r;
        }
        if (op.equals("watch")) {   // a standing search on the subject, every seven days unless said; its arrivals land in the inbox
            Patrons.check(store, patron, Patrons.Level.write);
            String subject = reqStr(args, "subject").strip(); int every = Math.max(1, args.path("every").asInt(7));
            String slug = DossierPage.watchSlug(subject);
            Serials.add(store, slug, subject, every);
            r.put("subject", subject); r.put("slug", slug); r.put("every", every);
            r.put("summary", "Watching " + subject + ": a search for the subject every " + every + " days; what arrives lands in the inbox. researchzosho serials runs the searches that are due.");
            return r;
        }
        if (op.equals("forecasts")) {   // the ledger: every dated forecast the reports made, open or scored
            Patrons.check(store, patron, Patrons.Level.read);
            String subject = args.path("subject").asText("");
            List<Forecasts.Entry> all = subject.isBlank() ? Forecasts.all(store) : Forecasts.of(store, subject);
            ArrayNode a = r.putArray("forecasts");
            for (Forecasts.Entry e : all) {
                ObjectNode o = a.addObject(); o.put("id", e.id()); o.put("subject", e.subject()); o.put("report", e.report()); o.put("filed", e.filed()); o.put("by", e.by()); o.put("text", e.text());
                o.put("likelihood", e.likelihood()); if (e.percent() != null) o.put("percent", e.percent()); o.put("confidence", e.confidence()); o.put("indicators", e.indicators());
                o.put("outcome", e.outcome() == null ? "" : e.outcome()); o.put("scored", e.scored() == null ? "" : e.scored()); o.put("note", e.note() == null ? "" : e.note());
            }
            Double brier = Forecasts.brier(all); if (brier != null) r.put("brier", brier);
            r.put("count", all.size()); r.put("summary", Forecasts.text(all));
            return r;
        }
        if (op.equals("score")) {   // a forecast's outcome, once its date has passed
            Patrons.check(store, patron, Patrons.Level.write);
            Forecasts.Entry e;
            try { e = Forecasts.score(store, reqStr(args, "id"), reqStr(args, "outcome"), args.path("note").asText("")); }
            catch (IllegalArgumentException x) { throw ProtocolError.invalidArgs(x.getMessage()); }
            if (e == null) throw ProtocolError.notFound("forecast " + args.path("id").asText());
            r.put("id", e.id()); r.put("subject", e.subject()); r.put("outcome", e.outcome()); r.put("scored", e.scored()); r.put("summary", "Scored " + e.id() + " as " + e.outcome() + ".");
            Double brier = Forecasts.brier(Forecasts.of(store, e.subject())); if (brier != null) r.put("brier", brier);
            return r;
        }
        if (op.equals("story")) {
            Patrons.check(store, patron, Patrons.Level.write);
            r.setAll(Story.write(store, reqStr(args, "subject"), Story.DRIVE.get()));
            return r;
        }
        if (op.equals("claims")) {   // the reports' dated events onto the shelves, for the reports that landed before the step existed or read them wrongly
            Patrons.check(store, patron, Patrons.Level.write);
            Dossier.Gathered d = Dossier.gather(store, reqStr(args, "subject"));
            int filed = 0, reports = 0;
            for (Investigation inv : d.reports()) if (!inv.title().startsWith("The story of ")) { reports++; filed += TimelineClaims.file(store, inv, d.label()).size(); }
            r.put("subject", d.label()); r.put("reports", reports); r.put("filed", filed);
            r.put("summary", filed == 0 ? "Nothing new: every dated event of the " + reports + " report" + (reports == 1 ? "" : "s") + " on " + d.label() + " is on the shelves already." : filed + " dated event" + (filed == 1 ? "" : "s") + " filed as claims from " + reports + " report" + (reports == 1 ? "" : "s") + " on " + d.label() + ".");
            return r;
        }
        Patrons.check(store, patron, Patrons.Level.read);
        r.setAll(Dossier.of(store, reqStr(args, "subject")));
        return r;
    }

    /**
     * library_export (0.7.0, read): a report ({@code id}) or a dossier ({@code subject}) as a PDF or a Word file ({@code format} pdf or docx),
     * holding what its page shows, written under the library's catalog/exports/ and served by the web pages at /exports/&lt;name&gt;. A
     * report is in the language {@code in} names, else in the caller's own language to read in, when the library keeps that translation;
     * a dossier in the language {@code in} names. When no translation is kept the file is as written, and {@code note} says so.
     */
    public ObjectNode export(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String id = args.path("id").asText("").strip(), subject = args.path("subject").asText("").strip(), format = args.path("format").asText("pdf");
        if (id.isEmpty() == subject.isEmpty()) throw ProtocolError.invalidArgs("Name one thing to save as a file: id, a report's id (I-…), or subject, the person or organisation of a dossier.");
        if (Export.format(format) == null) throw ProtocolError.invalidArgs("format is pdf, docx (a Word file) or md (Markdown).");
        String in = args.path("in").asText("").strip();
        if (in.isEmpty() && !id.isEmpty()) in = ReadIn.of(store, patron);   // a report comes in the reader's own language, as its page does
        Export.Made m = id.isEmpty() ? Export.dossier(store, subject, format, in) : Export.report(store, id, format, in);
        Path p = Export.save(store, m.file());
        ObjectNode r = envelope();
        r.put("kind", m.kind());
        if (!id.isEmpty()) r.put("id", id); else r.put("subject", subject);
        r.put("format", Export.format(format)); r.put("file", m.file().name()); r.put("path", p.toString());
        r.put("url", "/exports/" + URLEncoder.encode(m.file().name(), StandardCharsets.UTF_8).replace("+", "%20"));
        r.put("bytes", m.file().bytes().length);
        if (!m.writtenIn().isEmpty()) r.put("written_in", m.writtenIn());
        if (m.translated()) r.put("read_in", m.readIn());
        if (!m.note().isEmpty()) r.put("note", m.note());
        if (m.unshown() > 0) r.put("unshown", m.unshown());
        String what = (m.kind().equals("report") ? "The report" : "The dossier") + (m.translated() ? " in " + ReadIn.name(m.readIn()) : "");
        r.put("summary", what + " is saved as a " + (Export.PDF.equals(Export.format(format)) ? "PDF" : Export.MARKDOWN.equals(Export.format(format)) ? "Markdown" : "Word") + " file at " + p + ". The web pages serve it at /exports/" + m.file().name() + "."
                + (m.note().isEmpty() ? "" : " " + m.note())
                + (m.unshown() > 0 ? " Some characters could not be shown, because no font on this computer has them; the first page says which." : ""));
        return r;
    }

    /** What the told claims' leads are listed by; tests put a stand-in here. */
    static volatile BiFunction<String, String, List<String>> LEADS = (subject, text) -> {
        var f = Conversations.modelLeads(Explain.drive(), subject);
        return f == null ? List.of() : f.apply(text);
    };

    /**
     * library_tell: what a person tells the library about a subject in conversation, filed as a claim of class told, in their words, dated,
     * with them as the writer (write). The names, dates, places and events it raises become open questions on the frontier, so an account
     * can open a search: the interview feeds the research (0.6.6).
     */
    public ObjectNode tell(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        String subject = reqStr(args, "subject").strip(), text = reqStr(args, "text").strip();
        if (subject.isEmpty()) throw ProtocolError.invalidArgs("Say who or what this is about: subject.");
        if (text.length() < 12) throw ProtocolError.invalidArgs("Say it in at least one sentence.");
        String today = LocalDate.now().toString();
        String locator = "told://interview/" + LibraryStore.slug(subject) + "/" + today;
        String title = subject + ": " + Acquisitions.compress(text, 70);
        String writer = patron.anonymous() ? "person" : patron.writer();
        String id = store.nextFindingId(title);
        Finding f = new Finding(id, title, List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, writer,
                Instant.now().toString(), today, Finding.Volatility.slow, "",
                List.of(new Finding.Source(locator, "n/a", "told to the library in conversation")), List.of(), null, text + "\n", null, List.of());
        store.write(f);
        new LibrarianIndex(store).upsert(f);
        store.circulate("tell", id + " by " + writer + " on " + subject);
        List<String> filed = new ArrayList<>();
        for (String q : LEADS.apply(subject, text)) if (Frontier.demand(store, q, "interview " + subject)) filed.add(q);
        ObjectNode r = envelope();
        r.put("id", id); r.put("noted", title); r.put("class", SourceClass.told.label()); r.put("locator", locator);
        ArrayNode qs = r.putArray("questions"); for (String q : filed) qs.add(q);
        r.put("summary", "Noted, in your words, as " + id + (filed.isEmpty() ? "." : ". It raises " + filed.size() + (filed.size() == 1 ? " question" : " questions") + " the record could answer, now on the open questions: " + String.join(" · ", filed)));
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
                if (q.length() < 12) throw ProtocolError.invalidArgs("The question is too short. Write a full sentence.");
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
                    case "park" -> Frontier.park(store, q, args.path("why").asText(""));
                    default -> Frontier.unpark(store, q);
                };
                boolean already = !ok && op.equals("park") && Frontier.isParked(store, q);
                if (!ok && !already) throw ProtocolError.notFound("No open question matches this text exactly: " + q + (op.equals("unpark") ? " (or it is not parked)" : ""));
                r.put(op.equals("drop") ? "dropped" : op.equals("park") ? "parked" : op.equals("unpark") ? "unparked" : "moved", true);
                if (already) r.put("already", true);   // it was parked before: it stays parked, and a why given now is its reason
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
    /**
     * The career field's record of a person looking for work (docs/DESIGN_CAREER.md): op=list the people; op=read (seeker) the reading,
     * what they want and what changed lately; op=evidence (seeker) the facts; op=provide (what: a file, a web address or words; name)
     * keeps what they hand over, and reads a file only for the library's owner on this machine; op=reading (seeker) has the model write
     * the reading and draft what they want; op=wants (seeker) drafts what they want again; op=want (seeker, dimension, state, want) sets
     * one topic in their own words; op=questions (seeker) the interview's questions with their answers; op=answers (seeker, answers: an
     * object of question numbers to words) answers them, read together for what they settle. The search (step 3): op=company (seeker,
     * words, drop) a company the search looks at; op=keep_out (seeker, words, drop) something it keeps out; op=search (seeker) files a
     * search as one of the library's runs, op=more (seeker) more readings; op=openings (seeker, show) what it found, in words and as
     * rows; op=react (seeker, id, reaction like|pass|"" to take it back, why); op=company_state (seeker, name, state kept|dropped|"");
     * op=cut (seeker; with any of titles, titles_also, titles_not, areas, places, remote, remote_where, prefer_titles, prefer_places it sets them, with
     * again=true the library makes it again); op=watch (seeker, on) the nightly watch on or off, or, without on, whether it is; op=resettle (seeker) reads every answer again for what it settles; op=companies (seeker, show) the companies of the place from the public records, each read against the wants; op=react with company (and reaction, why) reacts to one of them. {@code how}: the command the person types, for the hints in the text ("researchzosho
     * career" if none).
     */
    /**
     * Finding what was seen in a video: {@code saw} (query, k) answers with the moments in what was said or seen; {@code picture}
     * (description, kinds) files the search through the frames; {@code status} says what the library has read.
     */
    public ObjectNode video(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("status");
        ObjectNode r = envelope();
        switch (op) {
            case "saw" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                String q = args.path("query").asText("").strip();
                if (q.isEmpty()) throw ProtocolError.invalidArgs("Give the words you remember hearing or seeing (query).");
                List<VideoRecall.Hit> hits = VideoRecall.saw(store, q, Math.max(1, Math.min(50, args.path("k").asInt(8))));
                ArrayNode a = r.putArray("moments");
                for (VideoRecall.Hit h : hits) a.addObject().put("video", h.videoId()).put("title", h.title()).put("seconds", h.seconds()).put("at", h.clock()).put("kind", h.kind()).put("line", h.line()).put("link", h.link());
                r.put("summary", VideoRecall.sawText(hits, q));
            }
            case "look" -> {   // the look at a video at any address (DESIGN_BRAIN.md §4): the video field's reading once it lands, a plain sentence until then
                Patrons.check(store, patron, Patrons.Level.read);
                String video = args.path("video").asText("").strip(), what = args.path("what").asText("").strip();
                if (video.isEmpty()) throw ProtocolError.invalidArgs("Give the video's address (video), and what to look for in it (what).");
                try { r.setAll(VideoLook.look(store, video, what, args.path("sparse").asBoolean(false), patron.did())); }
                catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
                catch (IllegalStateException e) { throw ProtocolError.unavailable(e.getMessage()); }
            }
            case "picture" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                List<String> kinds = new ArrayList<>(); for (JsonNode k : args.path("kinds")) kinds.add(k.asText(""));
                try { r.put("summary", VideoRecall.file(store, args.path("description").asText(""), kinds, patron.did())); }
                catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
            }
            case "candidates" -> {   // the candidate pool for a remembered video: the thing's name per language in, the pool best fit first out (Since 0.7.0)
                Patrons.check(store, patron, Patrons.Level.read);
                Candidates.Ask ask = Candidates.Ask.of(args);
                if (ask.things().isEmpty()) throw ProtocolError.invalidArgs("Give the name of the thing made or shown, in two to four words, in each language the video may be in (things).");
                Candidates.Result res = Candidates.run(store, ask, s -> { }, Stopping.current());
                ArrayNode things = r.putArray("things"); for (String t : ask.things()) things.add(t);
                ArrayNode remembered = r.putArray("remembered"); for (String t : ask.remembered()) remembered.add(t);
                r.put("pool", res.pool().size()).put("page", res.page()).put("per_page", Candidates.SHOWN).put("excluded", res.excluded()).put("outside_dates", res.outsideDates()).put("outside_length", res.outsideLength());
                ObjectNode by = r.putObject("by_source"); res.bySource().forEach(by::put);
                ArrayNode notes = r.putArray("notes"); for (String n : res.notes()) notes.add(n);
                ArrayNode a = r.putArray("candidates");
                int from = (res.page() - 1) * Candidates.SHOWN;
                for (int i = from; i < Math.min(res.pool().size(), from + Candidates.SHOWN); i++) a.add(res.pool().get(i).json());
                r.put("key", res.key()).put("cut", res.cut()).put("outside_language", res.outsideLanguage());
                r.put("summary", res.text());
            }
            case "sift" -> {   // the funnel over a kept pool: every candidate's thumbnail and first sheet judged, the survivors best first (Since 0.7.0)
                Patrons.check(store, patron, Patrons.Level.read);
                Sift.Ask ask = Sift.Ask.of(args);
                if (ask.what().isEmpty()) throw ProtocolError.invalidArgs("Say what you remember of the video, one thing after another (what), and give the pool's key (from) as candidates said it, or the videos themselves (videos).");
                Sift.Result res;
                try { res = Sift.run(store, ask, s -> { }, Stopping.current()); }
                catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
                r.setAll(Sift.json(res));
            }
            case "status" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                List<VideoRecall.Held> held = VideoRecall.held(store);
                r.put("videos", held.size()).put("with_said", held.stream().filter(VideoRecall.Held::said).count()).put("with_shown", held.stream().filter(VideoRecall.Held::shown).count()).put("with_seen", held.stream().filter(VideoRecall.Held::seen).count()).put("vision", VideoRecall.visionAnswers()).put("model", VideoRecall.modelName());
                r.put("summary", "The library has read " + held.size() + (held.size() == 1 ? " video" : " videos") + ", " + held.stream().filter(VideoRecall.Held::said).count() + " with what was said, " + held.stream().filter(VideoRecall.Held::shown).count() + " with the words on screen and " + held.stream().filter(VideoRecall.Held::seen).count() + " with what is seen.");
            }
            default -> throw ProtocolError.invalidArgs("op must be saw, look, candidates, sift, picture or status.");
        }
        return r;
    }

    /**
     * Prior art from the patent office's own records (Since 0.7.0): {@code pool} (classes, from_year, to_year, features, exclude, page)
     * enumerates every publication of the classes in the years, one line a family, ordered by the features their titles and abstracts
     * name; {@code abstracts} (from, limit, page) has the model read the pool's abstracts, twenty to a call; {@code claims} (from, or
     * publications with features; limit, page) reads the claims from the office's full text and quotes each feature's closest claim.
     */
    public ObjectNode patents(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String op = args.path("op").asText("pool").toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        switch (op) {
            case "pool" -> {
                Patents.Ask ask = Patents.Ask.of(args);
                if (ask.classes().isEmpty()) throw ProtocolError.invalidArgs("Give the classification symbols the invention sits in (classes), each a group with its slash, like A47G19/22. " + Patents.HOW_TO_FIND_A_CLASS);
                r.setAll(Patents.json(Patents.run(store, ask, s -> { }, Stopping.current())));
            }
            case "abstracts" -> r.setAll(PatentReading.json(PatentReading.abstracts(store, PatentReading.AbstractsAsk.of(args), null, s -> { }, Stopping.current(), 0)));
            case "claims" -> r.setAll(PatentReading.json(PatentReading.claims(store, PatentReading.ClaimsAsk.of(args), null, s -> { }, Stopping.current(), 0)));
            default -> throw ProtocolError.invalidArgs("op must be pool, abstracts or claims.");
        }
        return r;
    }

    public ObjectNode career(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("read").toLowerCase(Locale.ROOT), how = args.path("how").asText("researchzosho career");
        ObjectNode r = envelope();
        switch (op) {
            case "list" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                ArrayNode a = r.putArray("seekers");
                List<String> names = new ArrayList<>();
                for (Career.Seeker s : Career.seekers(store)) { a.addObject().put("name", s.name()).put("slug", s.slug()).put("facts", Career.evidence(store, s).size()); names.add(s.name()); }
                r.put("summary", names.isEmpty() ? "Nobody's working life is kept here yet. Hand over a resume, a LinkedIn file or some words about what you want."
                        : "This library keeps the working life of " + Career.and(names) + ".");
            }
            case "provide" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                Career.Provided p;
                try { p = Career.provide(store, reqStr(args, "what"), args.path("name").asText(""), patron.person()); }   // a path is read only for the owner on this machine
                catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
                r.put("seeker", p.seeker().name()).put("slug", p.seeker().slug()).put("added", p.added()).put("kind", p.kind()).put("facts", Career.evidence(store, p.seeker()).size());
                String said = Career.providedText(store, p);
                if (p.kind().equals("told") && p.added() > 0) { String set = CareerInterview.settleTold(store, p.seeker(), reqStr(args, "what")); if (!set.isEmpty()) { said = said + "\n" + set; r.put("settled", set); } }   // the words read at once for what they decide
                r.put("summary", said);
            }
            case "evidence" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                Career.Seeker s = seekerOf(args);
                r.put("seeker", s.name()).set("facts", Career.evidence(store, s));
                String t = Career.evidenceText(store, s);
                r.put("text", t); r.put("summary", t);
            }
            case "reading" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                Career.Seeker s = seekerOf(args);
                String md = Career.reading(store, s);
                if (md.isBlank()) throw ProtocolError.unavailable("The model did not answer, so there is no reading yet. Check that the model server is running, then ask again.");
                ObjectNode w = Career.wants(store, s);
                r.put("seeker", s.name()).put("markdown", md).set("wants", w);
                r.put("text", Career.text(store, s, how));
                r.put("summary", "The library wrote its reading of " + s.name() + "'s working life and drafted what they want.");
            }
            case "shape" -> {   // how seats are filled for them, drawn by the model from the role and the place; with words, the person's own word on it, which outranks the model's
                Patrons.check(store, patron, Patrons.Level.write);
                Career.Seeker s = seekerOf(args);
                String words = args.path("words").asText("").strip();
                String t = words.isEmpty() ? CareerShape.text(CareerShape.current(store, s)) : CareerShape.tell(store, s, words);
                r.put("seeker", s.name()).put("text", t).put("summary", t);
            }
            case "wants" -> {   // drafted again from the reading as kept and from what was said; a topic the person set stays theirs
                Patrons.check(store, patron, Patrons.Level.write);
                Career.Seeker s = seekerOf(args);
                ObjectNode w;
                try { w = Career.wants(store, s); }
                catch (RuntimeException e) { throw new IllegalStateException("No model answered, so the library could not draft what " + s.name() + " wants: drafting needs the model. Check researchzosho doctor, or set the model server with researchzosho settings set drive <address>, then try again."); }
                r.put("seeker", s.name()).set("wants", w);
                r.put("text", Career.text(store, s, how));
                r.put("summary", "The library drafted again what " + s.name() + " wants.");
            }
            case "questions" -> {   // the interview: the reading's questions, each with any answer and what the library took from it
                Patrons.check(store, patron, Patrons.Level.read);
                Career.Seeker s = seekerOf(args);
                JsonNode o = CareerInterview.state(store, s);
                ArrayNode qs = r.putArray("questions");
                StringBuilder t = new StringBuilder();
                for (CareerInterview.Question q : CareerInterview.questions(o)) {
                    String a = CareerInterview.answerOf(o, q.n());
                    qs.addObject().put("n", q.n()).put("question", q.text()).put("about", q.tag()).put("followup_to", q.followupTo()).put("answer", a).put("taken", CareerInterview.takenOf(o, q.n()));
                    t.append(q.main() ? q.n() + ". " : "A follow-up to question " + q.followupTo() + ": ").append(q.text()).append(a.isEmpty() ? "" : "\n   You said: " + a).append('\n');
                }
                r.put("seeker", s.name());
                r.put("summary", qs.isEmpty() ? "There are no questions yet: they come from the reading." : t.toString().strip());
            }
            case "resettle" -> {   // every answer read again for what it settles; the topics set from answers set again
                Patrons.check(store, patron, Patrons.Level.write);
                Career.Seeker s = seekerOf(args);
                r.put("seeker", s.name()).put("summary", CareerInterview.resettle(store, s)).set("wants", Career.wantsKept(store, s));
            }
            case "answers" -> {   // answers to the interview's questions, by number, read together for what they settle
                Patrons.check(store, patron, Patrons.Level.write);
                Career.Seeker s = seekerOf(args);
                Map<String, String> byN = new LinkedHashMap<>();
                args.path("answers").properties().forEach(e -> byN.put(e.getKey(), e.getValue().asText("")));
                String said = CareerInterview.saveAnswers(store, s, byN);
                r.put("seeker", s.name());
                r.put("summary", said.isBlank() ? "No new answers were given." : said);
            }
            case "company", "keep_out" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                Career.Seeker s = seekerOf(args);
                String words = reqStr(args, "words"), list = op.equals("company") ? "companies" : "keep_out";
                boolean drop = args.path("drop").asBoolean(false), done;
                done = drop ? CareerSearch.remove(store, s, list, words) : op.equals("company") ? CareerSearch.addCompany(store, s, words, "") : CareerSearch.addKeepOut(store, s, words, "");
                r.put("seeker", s.name()).put("changed", done);
                r.put("summary", op.equals("company") ? (drop ? (done ? "The search no longer looks at " + words + "." : "That company is not on the list.") : (done ? "The search will look at " + words + "." : CareerSearch.kept(store, s, "companies", words) + " is on the list already."))
                        : (drop ? (done ? "The search no longer keeps out " + words + "." : "That is not kept out.") : (done ? "The search will keep out " + words + "." : CareerSearch.kept(store, s, "keep_out", words) + " is kept out already.")));
            }
            case "search", "more" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                Career.Seeker s = seekerOf(args);
                r.put("seeker", s.name()).put("summary", CareerFind.file(store, s, patron.did(), op.equals("more") ? "career-read" : "career-search", args.path("vary").asText("")));
            }
            case "openings" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                Career.Seeker s = seekerOf(args);
                CareerFind.View v = CareerFind.view(store, s);
                ArrayNode rows = r.putArray("openings");
                for (CareerFind.Row row : v.passing()) {
                    CareerSources.Opening o = row.opening();
                    ObjectNode x = rows.addObject().put("id", o.id()).put("title", o.title()).put("company", o.company()).put("place", o.location()).put("remote", o.remote()).put("url", o.url())
                            .put("posted", o.posted()).put("posted_note", o.postedNote()).put("pay", o.pay()).put("source", o.source()).put("last_seen", row.kept() == null ? "" : row.kept().path("last_seen").asText(""))
                            .put("reaction", row.reactionWord());
                    if (row.read()) x.set("reading", row.reading());
                }
                r.put("seeker", s.name()).put("cut", CareerCut.summary(v.cut())).put("searched", v.searched());
                String t = CareerFind.text(store, s, how, Math.max(1, args.path("show").asInt(10)));
                r.put("text", t); r.put("summary", t);
            }
            case "react" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                Career.Seeker s = seekerOf(args);
                String reaction = args.path("reaction").asText(""), why = args.path("why").asText("");
                try {
                    String said;
                    if (args.has("company")) said = CareerCompanies.react(store, s, reqStr(args, "company"), reaction, why);
                    else {
                        try { said = CareerFind.react(store, s, reqStr(args, "id"), reaction, why); }
                        catch (IllegalArgumentException noOpening) {   // not an opening's short name: a company of the place, which may spell like one
                            try { said = CareerCompanies.react(store, s, reqStr(args, "id"), reaction, why); } catch (IllegalArgumentException noCompany) { throw noOpening; }
                        }
                    }
                    r.put("seeker", s.name()).put("summary", said);
                }
                catch (IllegalArgumentException x) { throw ProtocolError.invalidArgs(x.getMessage()); }
            }
            case "companies" -> {   // the companies of the place, from the public records, each read against what the person wants
                Patrons.check(store, patron, Patrons.Level.read);
                Career.Seeker s = seekerOf(args);
                CareerCut.Cut cut = CareerCut.kept(store, s); if (cut == null) cut = CareerCut.Cut.NONE;
                ArrayNode rows = r.putArray("companies");
                for (ObjectNode e : CareerCompanies.standing(store, s, cut)) rows.add(e);
                String t = CareerCompanies.text(store, s, cut, how, Math.max(1, args.path("show").asInt(8)));
                r.put("seeker", s.name()).put("text", t).put("summary", t);
            }
            case "company_state" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                Career.Seeker s = seekerOf(args);
                try { r.put("seeker", s.name()).put("summary", CareerFind.companyState(store, s, reqStr(args, "name"), args.path("state").asText(""))); }
                catch (IllegalArgumentException x) { throw ProtocolError.invalidArgs(x.getMessage()); }
            }
            case "watch" -> {
                Career.Seeker s = seekerOf(args);
                if (args.has("on")) { Patrons.check(store, patron, Patrons.Level.write); r.put("summary", CareerWatch.set(store, s, args.path("on").asBoolean(false))); }
                else { Patrons.check(store, patron, Patrons.Level.read); r.put("summary", CareerWatch.on(store, s) ? "The nightly watch is on." : "The nightly watch is off."); }
                r.put("seeker", s.name()).put("on", CareerWatch.on(store, s));
            }
            case "cut" -> {
                Career.Seeker s = seekerOf(args);
                Map<String, String> f = new LinkedHashMap<>();
                for (String k : List.of("titles", "titles_also", "titles_not", "areas", "places", "remote", "remote_where", "max_people", "min_people", "prefer_titles", "prefer_places")) if (args.has(k)) f.put(k, args.path(k).isArray() ? String.join(", ", textList(args.path(k))) : args.path(k).asText(""));
                CareerCut.Cut c;
                if (args.path("again").asBoolean(false)) { Patrons.check(store, patron, Patrons.Level.write); c = CareerCut.make(store, s); }
                else if (!f.isEmpty()) { Patrons.check(store, patron, Patrons.Level.write); try { c = CareerCut.set(store, s, f); } catch (IllegalArgumentException x) { throw ProtocolError.invalidArgs(x.getMessage()); } }
                else { Patrons.check(store, patron, Patrons.Level.read); c = CareerCut.kept(store, s); }
                r.put("seeker", s.name()).put("summary", c == null ? "There is no cut yet: the first search makes it from what you decided." : "The cut: " + CareerCut.summary(c) + ".");
                if (c != null) r.put("by", c.by()).put("at", c.at());
            }
            case "want" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                Career.Seeker s = seekerOf(args);
                ObjectNode e;
                try { e = Career.setWant(store, s, reqStr(args, "dimension"), reqStr(args, "state"), args.path("want").asText("")); }
                catch (IllegalArgumentException x) { throw ProtocolError.invalidArgs(x.getMessage()); }
                r.put("seeker", s.name()).set("dimension", e);
                r.put("summary", "Saved. " + Career.line(e));
            }
            case "board" -> {   // an employer's or institution's own careers board and its openings (DESIGN_BRAIN.md §4)
                Patrons.check(store, patron, Patrons.Level.read);
                String employer = args.path("employer").asText("").strip();
                if (employer.isEmpty()) throw ProtocolError.invalidArgs("Give the employer's or institution's name (employer).");
                List<String> towns = new ArrayList<>(), words = new ArrayList<>();
                for (JsonNode t : args.path("towns")) if (t.isTextual() && !t.asText().isBlank()) towns.add(t.asText().strip());
                for (JsonNode w : args.path("words")) if (w.isTextual() && !w.asText().isBlank()) words.add(w.asText().strip());
                CareerSources.Found f = CareerPortals.inTowns(CareerPortals.find(employer, towns, words, towns), towns);   // cut to the towns, whichever way the board was found
                r.put("employer", employer);
                if (f != null && f.feed() != null) r.putObject("board").put("system", f.feed().kind()).put("how", f.feed().how() == null ? "" : f.feed().how());
                ArrayNode a = r.putArray("openings");
                if (f != null) for (CareerSources.Opening o : f.openings()) a.addObject().put("title", o.title()).put("location", o.location() == null ? "" : o.location()).put("remote", o.remote()).put("url", o.url() == null ? "" : o.url()).put("posted", o.posted() == null ? "" : o.posted());
                r.put("note", f == null || f.note() == null ? "" : f.note());
                r.put("summary", CareerPortals.boardText(employer, f, towns));
            }
            default -> {
                Patrons.check(store, patron, Patrons.Level.read);
                Career.Seeker s = seekerOf(args);
                r.put("seeker", s.name()).put("markdown", Career.readingKept(store, s)).set("wants", Career.wantsKept(store, s));
                ArrayNode log = r.putArray("log"); for (String l : Career.logTail(store, s, 12)) log.add(l);
                String t = Career.text(store, s, how);
                r.put("text", t); r.put("summary", t);
            }
        }
        return r;
    }

    private static List<String> textList(JsonNode a) { List<String> out = new ArrayList<>(); for (JsonNode x : a) out.add(x.asText("")); return out; }

    /** The home field (0.6.9): a person looking for a home. {@code op}: list, start, read, tell, want, keep_out, search, listings, react, card, add. */
    public ObjectNode home(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("read").toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        try {
            switch (op) {
                case "list" -> {
                    Patrons.check(store, patron, Patrons.Level.read);
                    ArrayNode a = r.putArray("searches");
                    List<String> lines = new ArrayList<>();
                    for (Home.Search s : Home.searches(store)) { a.addObject().put("name", s.name()).put("slug", s.slug()).put("listings", Home.listings(store, s).size()); lines.add(Home.standing(store, s)); }
                    r.put("summary", lines.isEmpty() ? "No home search is started yet." : String.join("\n", lines));
                }
                case "start" -> {
                    Patrons.check(store, patron, Patrons.Level.write);
                    Home.Search s = Home.start(store, reqStr(args, "name"));
                    r.put("search", s.name()).put("slug", s.slug()).put("summary", "The search \"" + s.name() + "\" is started. Say what you want, topic by topic or in your own words; then search.");
                }
                case "read" -> {
                    Patrons.check(store, patron, Patrons.Level.read);
                    Home.Search s = homeSearchOf(args);
                    r.put("search", s.name()).set("wants", Home.wantsKept(store, s));
                    ArrayNode out = r.putArray("keep_out"); for (String k : Home.keepOut(store, s)) out.add(k);
                    ArrayNode log = r.putArray("log"); for (String l : Home.lately(store, s, 12)) log.add(l);
                    String t = Home.standing(store, s) + "\n" + Home.wantsText(store, s);
                    r.put("text", t).put("summary", t);
                }
                case "tell" -> {
                    Patrons.check(store, patron, Patrons.Level.write);
                    Home.Search s = homeSearchOf(args);
                    String said = Home.tell(store, s, reqStr(args, "words"));
                    r.put("search", s.name()).put("summary", said.isEmpty() ? "Your words are kept. No topic was settled from them; set one yourself, or say it more plainly." : said);
                }
                case "want" -> {
                    Patrons.check(store, patron, Patrons.Level.write);
                    Home.Search s = homeSearchOf(args);
                    ObjectNode e = Home.setWant(store, s, reqStr(args, "dimension"), reqStr(args, "state"), args.path("want").asText(""), "you", "what you typed");
                    r.put("search", s.name()).set("topic", e);
                    r.put("summary", "Saved. " + Home.label(e.path("dimension").asText()) + ": " + (e.path("state").asText().equals("open") ? "open" : (e.path("state").asText().equals("decided") ? "decided, " : "a guess, ") + e.path("want").asText()) + ".");
                }
                case "keep_out" -> {
                    Patrons.check(store, patron, Patrons.Level.write);
                    Home.Search s = homeSearchOf(args);
                    String words = reqStr(args, "words");
                    boolean drop = args.path("drop").asBoolean(false);
                    r.put("search", s.name()).put("summary", drop ? (Home.removeKeepOut(store, s, words) ? "The search no longer keeps out " + words + "." : "That is not kept out.") : (Home.addKeepOut(store, s, words) ? "The search will keep out " + words + "." : words + " is kept out already."));
                }
                case "search" -> {
                    Patrons.check(store, patron, Patrons.Level.write);
                    Home.Search s = homeSearchOf(args);
                    r.put("search", s.name()).put("summary", HomeFind.file(store, s, patron.did(), args.path("more").asBoolean(false)));
                }
                case "listings" -> {
                    Patrons.check(store, patron, Patrons.Level.read);
                    Home.Search s = homeSearchOf(args);
                    HomeFind.View v = HomeFind.view(store, s);
                    ArrayNode rows = r.putArray("listings");
                    for (HomeFind.Row row : v.passing()) {
                        Home.Listing l = row.listing();
                        ObjectNode one = rows.addObject().put("id", l.id()).put("address", l.address()).put("city", l.city()).put("state", l.state()).put("price", l.price()).put("beds", l.beds()).put("baths", l.baths()).put("sqft", l.sqft())
                                .put("kind", l.kind()).put("tenure", l.tenure()).put("source", l.source()).put("url", l.url()).put("listed", l.listed()).put("first_seen", l.firstSeen()).put("last_seen", l.lastSeen())
                                .put("tier", row.tier()).put("reaction", row.reactionWord()).put("card", HomeCard.state(row.card()))
                                .put("link", l.link()).put("seen_on", l.seenOn());   // the home itself, its own page or a search for its address; the page of listings it was seen on
                        if (l.japan()) one.put("currency", "JPY").put("name", l.extra().name()).put("layout", l.extra().layout()).put("area_m2", l.extra().areaM2()).put("built", l.extra().built()).put("line_station", (l.extra().line().isEmpty() ? "" : l.extra().line() + " ") + l.extra().station()).put("walk", l.extra().walk()).put("floor", l.extra().floor()).put("price_text", l.priceText()).put("per_m2", l.perM2Text());
                        ArrayNode unknown = one.putArray("not_known");
                        for (String x : HomeCut.unknown(v.cut(), l)) unknown.add(x);
                    }
                    ArrayNode aside = r.putArray("aside");
                    for (HomeFind.Row row : v.aside()) aside.addObject().put("id", row.listing().id()).put("line", row.listing().line()).put("why", row.verdict().why());
                    ArrayNode portals = r.putArray("portals"); for (String[] p : v.portals()) portals.addObject().put("name", p[0]).put("url", p[1]);
                    r.put("search", s.name()).put("cut", HomeCut.summary(v.cut())).put("searched", v.searched());
                    if (v.more() != null) r.put("rentcast_total", v.more().total()).put("rentcast_fetched", v.more().fetched());
                    String t = HomeFind.text(store, s, Math.max(1, args.path("show").asInt(12)));
                    r.put("text", t).put("summary", t);
                }
                case "react" -> {
                    Patrons.check(store, patron, Patrons.Level.write);
                    Home.Search s = homeSearchOf(args);
                    r.put("search", s.name()).put("summary", Home.react(store, s, reqStr(args, "id"), args.path("reaction").asText(""), args.path("why").asText("")));
                }
                case "card" -> {
                    Patrons.check(store, patron, Patrons.Level.write);
                    Home.Search s = homeSearchOf(args);
                    r.put("search", s.name()).put("summary", HomeFind.card(store, s, reqStr(args, "id"), patron.did()));
                }
                case "add" -> {
                    Patrons.check(store, patron, Patrons.Level.write);
                    Home.Search s = homeSearchOf(args);
                    r.put("search", s.name()).put("summary", HomeFind.save(store, s, reqStr(args, "words"), args.path("url").asText(""), args.path("tenure").asText("")));
                }
                case "records" -> {   // the public records of one address, as a card reads them (DESIGN_BRAIN.md §4)
                    Patrons.check(store, patron, Patrons.Level.read);
                    String address = args.path("address").asText("").strip();
                    if (address.isEmpty()) throw ProtocolError.invalidArgs("Give the address (address), as street, city, state.");
                    HomeSensors.Records rec;
                    try { rec = HomeSensors.records(store, address); } catch (IllegalArgumentException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
                    r.put("address", address).put("place", rec.place()).put("line", rec.line());
                    ArrayNode sa = r.putArray("sales"); for (String x : rec.sales()) sa.add(x);
                    ArrayNode pa = r.putArray("permits"); for (String x : rec.permits()) pa.add(x);
                    if (!rec.census().isEmpty()) r.put("census", rec.census());
                    ArrayNode lk = r.putArray("looked"); for (String x : rec.looked()) lk.add(x);
                    r.put("summary", HomeSensors.recordsText(rec));
                }
                 default -> throw ProtocolError.invalidArgs("The home operations are list, start, read, tell, want, keep_out, search, listings, react, card, add and records.");
            }
        } catch (IllegalArgumentException x) { throw ProtocolError.invalidArgs(x.getMessage()); }
        return r;
    }

    private Home.Search homeSearchOf(JsonNode args) throws IOException {
        String name = args.path("search").asText(args.path("name").asText("")).strip();
        Home.Search s = Home.search(store, name);
        if (s != null) return s;
        List<String> names = new ArrayList<>(); for (Home.Search x : Home.searches(store)) names.add(x.name());
        if (names.isEmpty()) throw ProtocolError.notFound("No home search is started yet. Start one with a name first.");
        if (name.isEmpty()) throw ProtocolError.invalidArgs("This library holds more than one home search: " + String.join(", ", names) + ". Say which.");
        throw ProtocolError.notFound("There is no search called " + name + ". The searches are: " + String.join(", ", names) + ".");
    }

    private Career.Seeker seekerOf(JsonNode args) throws IOException {
        String name = args.path("seeker").asText(args.path("name").asText("")).strip();
        Career.Seeker s = Career.seeker(store, name);
        if (s != null) return s;
        List<String> names = new ArrayList<>(); for (Career.Seeker x : Career.seekers(store)) names.add(x.name());
        if (names.isEmpty()) throw ProtocolError.notFound("Nobody's working life is kept here yet. Hand over a resume, a LinkedIn file or some words about what you want first.");
        if (name.isEmpty()) throw ProtocolError.invalidArgs("This library keeps more than one person's working life: " + Career.and(names) + ". Say whose.");
        throw ProtocolError.notFound("There is nobody called " + name + " here. The people here are: " + Career.and(names) + ".");
    }

    /**
     * The library on itself (library_help): its live state, the version, holdings, model and whether it answers, web search, fields,
     * commands and pages, and the sections of the program's own documentation that answer {@code question}, with where to ask what
     * neither answers. Anyone who may read the library may ask.
     */
    public ObjectNode help(JsonNode args) throws IOException {
        Patrons.check(store, Patrons.Patron.from(args), Patrons.Level.read);
        String q = args.path("question").asText("").strip();
        ObjectNode r = envelope();
        r.put("version", Version.string());
        ArrayNode sections = r.putArray("sections");
        for (Help.Section s : Help.find(q, 3)) sections.addObject().put("doc", s.doc()).put("heading", s.heading()).put("text", s.text().length() > 1800 ? s.text().substring(0, 1800) + " …" : s.text());
        r.put("issues", Help.ISSUES);
        r.put("summary", Help.answer(store, q));
        return r;
    }

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
                    o.put("parked", sh.parked()); o.put("due", sh.due(LocalDate.now()));
                }
            }
            case "add" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String name = reqStr(args, "name").strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
                String query = reqStr(args, "query").strip();
                int every = args.path("every_days").asInt(7);
                if (name.isEmpty() || query.length() < 3) throw ProtocolError.invalidArgs("A saved search needs a name and a query.");
                if (every < 1 || every > 365) throw ProtocolError.invalidArgs("every_days must be between 1 and 365.");
                Serials.add(store, name, query, every);
                r.put("kept", true); r.put("name", name); r.put("query", query); r.put("every_days", every);
            }
            case "remove" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String name = reqStr(args, "name").strip();
                if (!Serials.remove(store, name)) throw ProtocolError.notFound("No saved search is named " + name);
                r.put("removed", true); r.put("name", name);
            }
            case "park", "unpark" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String name = reqStr(args, "name").strip();
                if (!Serials.setParked(store, name, op.equals("park"))) throw ProtocolError.notFound("No saved search is named " + name + (op.equals("park") ? " (or it is parked already)" : " (or it is not parked)"));
                r.put("name", name); r.put("parked", op.equals("park"));
            }
            case "every" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                String name = reqStr(args, "name").strip();
                int every = args.path("every_days").asInt(0);
                if (every < 1 || every > 365) throw ProtocolError.invalidArgs("every_days must be between 1 and 365.");
                if (!Serials.setEvery(store, name, every)) throw ProtocolError.notFound("No saved search is named " + name);
                r.put("name", name); r.put("every_days", every);
            }
            default -> throw ProtocolError.invalidArgs("op must be list, add, every, park, unpark or remove.");
        }
        return r;
    }

    /** library_subjects: the controlled vocabulary; broader = the facet before {@code --}. */
    public ObjectNode subjects(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
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
     * library_share: the library reached from outside the house, from a conversation (write access). op=status says how it stands;
     * op=public (the plain share: Funnel when Tailscale is logged in, else the quick tunnel now), op=tailscale (the lasting address), op=tailnet
     * (the tailnet only) and op=cloudflare (mail=…) turn the relay on when its program is on the machine; op=off turns it
     * off. Installing a program needs the person's yes on their own screen, so when one is missing the answer names the command that
     * asks: /share in the chat, researchzosho share in a terminal.
     */
    public ObjectNode share(JsonNode args) throws IOException {
        Patrons.check(store, Patrons.Patron.from(args), Patrons.Level.write);
        String op = args.path("op").asText("status").strip().toLowerCase(Locale.ROOT);
        List<String> a = new ArrayList<>();
        switch (op) {
            case "status" -> a.add("status");
            case "off" -> a.add("off");
            case "tailscale" -> a.add("--tailscale");
            case "tailnet" -> a.add("--tailnet");
            case "public", "on" -> { }
            case "cloudflare" -> { a.add("--cloudflare"); if (!args.path("mail").asText("").isBlank()) { a.add("--mail"); a.add(args.path("mail").asText()); } }
            default -> throw ProtocolError.invalidArgs("op is status, public, tailscale, tailnet, cloudflare or off.");
        }
        ByteArrayOutputStream steps = new ByteArrayOutputStream();
        Share.Outcome o = Share.share(store, a, new PrintStream(steps, true, StandardCharsets.UTF_8));   // never asks: a tool has no keyboard
        ObjectNode r = envelope();
        r.put("op", op); r.put("ok", o.code() == 0); r.put("kind", Share.kind()); r.put("address", Share.address());
        String text = (steps.toString(StandardCharsets.UTF_8) + o.text()).strip();
        if (o.code() != 0 && !op.equals("status")) text += "\n\nTo install what is missing, the person says /share in the chat, or runs researchzosho share in a terminal: it asks before each step.";
        r.put("text", text);
        return r;
    }

    /**
     * Ask to be let in. No access is needed to ask, and the did comes in its own field because the caller has
     * no token yet to prove a patron object with. Returns the request id and the claim secret, once.
     */
    public ObjectNode requestAccess(JsonNode args) throws IOException {
        String did = args.path("did").asText("").strip();
        if (did.isEmpty()) did = args.path("patron").path("did").asText("").strip();
        String name = args.path("name").asText("").strip();
        String note = args.path("note").asText("").strip();
        if (did.isEmpty()) throw ProtocolError.invalidArgs("Give the did of the user who is asking.");
        if (note.length() > 400 || name.length() > 120) throw ProtocolError.invalidArgs("Keep the name under 120 characters and the note under 400.");
        AccessRequests.Filed f;
        try { f = AccessRequests.request(store, did, name, note); }
        catch (IOException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
        ObjectNode r = envelope();
        r.put("request_id", f.id()); r.put("state", "pending");
        if (f.fresh()) { r.put("claim", f.claim()); r.put("note", "Keep this claim code secret. You need it to collect your token after the request is approved. It is shown only once."); }
        else r.put("note", "A request from this did is already waiting. The claim code was given when the request was made.");
        return r;
    }

    /**
     * What became of a request (op=collect, the default: approved and claimed with the right secret, the token, once); and the keeper's
     * side of access, each op a thin call over what the command line and the Keep page do: readers (the list and the default), allow
     * (did, level, name), remove (did), invite (name, level: a link that works once), requests (what waits), approve (request_id, level),
     * deny (request_id and reason, or did), keys (the keeper's keys for programs), key (label, level: a new key, shown once), revoke
     * (key_id). The keeper only: anyone else is refused with one sentence.
     */
    public ObjectNode access(JsonNode args) throws IOException {
        String op = args.path("op").asText("collect").strip().toLowerCase(Locale.ROOT).replace('-', '_');
        if (op.isEmpty() || op.equals("collect")) {
            String id = reqStr(args, "request_id").strip();
            String claim = args.path("claim").asText("");
            AccessRequests.Outcome o;
            try { o = AccessRequests.claim(store, id, claim); }
            catch (IOException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
            ObjectNode r = envelope();
            r.put("request_id", o.id()); r.put("state", o.state());
            if (!o.token().isEmpty()) { r.put("token", o.token()); r.put("level", o.level()); r.put("note", "Use it as Authorization: Bearer <token>. It is shown only once."); }
            if (!o.reason().isEmpty()) r.put("reason", o.reason());
            return r;
        }
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);   // a key that reads only is refused with its own sentence first
        if (!patron.person()) throw ProtocolError.forbidden("Only the person who keeps this library may see or change who reads it; a program acts for them with a key that writes.");
        ObjectNode r = envelope();
        r.put("op", op);
        try {
            switch (op) {
                case "readers" -> {
                    Patrons.Policy pol = Patrons.load(store);
                    r.put("default", pol.dflt().name());
                    ArrayNode a = r.putArray("readers");
                    for (Patrons.Entry e : pol.listed()) {
                        Accounts.Account acc = Accounts.byDid(store, e.did());
                        a.addObject().put("did", e.did()).put("name", e.name()).put("level", e.level().name())
                                .put("signs_in_with", acc == null ? (e.tokenHash() == null ? "nothing yet" : "a token (a program)") : acc.ways());
                    }
                    r.put("summary", (a.isEmpty() ? "Nobody is listed; " : a.size() + (a.size() == 1 ? " reader is listed; " : " readers are listed; ")) + "anyone unlisted gets " + pol.dflt().name() + ".");
                }
                case "allow" -> {
                    String did = reqStr(args, "did").strip();
                    Patrons.Level lvl = Patrons.levelOf(args.path("level").asText(""));
                    if (lvl == null) throw ProtocolError.invalidArgs("Say what " + did + " may do: level is deny, read or write.");
                    String name = args.path("name").asText("").strip();
                    Patrons.set(store, did, name, lvl);
                    r.put("did", did).put("name", name).put("level", lvl.name());
                    r.put("summary", did + (name.isEmpty() ? "" : " (" + name + ")") + (lvl == Patrons.Level.deny ? " is denied." : " may " + lvl + "."));
                }
                case "remove" -> {
                    String did = reqStr(args, "did").strip();
                    Patrons.remove(store, did); Accounts.remove(store, did);
                    r.put("did", did).put("removed", true).put("summary", did + " is removed from the readers and falls back to the default.");
                }
                case "invite" -> {
                    String name = reqStr(args, "name").strip();
                    Patrons.Level lvl = args.path("level").asText("").isBlank() ? Patrons.Level.read : Patrons.levelOf(args.path("level").asText(""));
                    if (lvl == null || lvl == Patrons.Level.deny) throw ProtocolError.invalidArgs("An invite lets a person read or write: level is read or write.");
                    Invites.Invite inv = Invites.make(store, name, lvl);
                    r.put("did", inv.did()).put("name", inv.name()).put("level", inv.level().name()).put("link", inv.link()).put("days", Invites.DAYS);
                    r.put("summary", inv.name() + " may " + inv.level() + ". Send them the link; it works once, for " + Invites.DAYS + " days, and they choose a password when they open it. It is shown only once.");
                }
                case "requests" -> {
                    ArrayNode a = r.putArray("requests");
                    long pending = 0;
                    for (AccessRequests.Request q : AccessRequests.list(store)) {
                        if (q.state().equals("pending")) pending++;
                        a.addObject().put("id", q.id()).put("date", q.date()).put("state", q.state()).put("did", q.did()).put("name", q.name()).put("note", q.note()).put("reason", q.reason());
                    }
                    r.put("pending", pending);
                    r.put("summary", a.isEmpty() ? "Nobody has asked to be let in." : pending + (pending == 1 ? " request waits" : " requests wait") + " for an answer, of " + a.size() + " on record.");
                }
                case "approve" -> {
                    String id = reqStr(args, "request_id").strip();
                    Patrons.Level lvl = args.path("level").asText("").isBlank() ? Patrons.Level.read : Patrons.levelOf(args.path("level").asText(""));
                    if (lvl == null || lvl == Patrons.Level.deny) throw ProtocolError.invalidArgs("level is read or write.");
                    AccessRequests.Request q = AccessRequests.approve(store, id, lvl);
                    r.put("request_id", q.id()).put("did", q.did()).put("level", lvl.name()).put("state", "approved");
                    r.put("summary", q.did() + " may " + lvl + ". They collect their token with the claim secret they were given.");
                }
                case "deny" -> {
                    String id = args.path("request_id").asText("").strip(), did = args.path("did").asText("").strip();
                    if (!id.isEmpty()) {
                        AccessRequests.Request q = AccessRequests.deny(store, id, args.path("reason").asText(""));
                        r.put("request_id", q.id()).put("did", q.did()).put("state", "denied").put("summary", "Request " + q.id() + " from " + q.did() + " is denied.");
                    } else if (!did.isEmpty()) {
                        Patrons.set(store, did, null, Patrons.Level.deny);
                        r.put("did", did).put("level", "deny").put("summary", did + " is denied.");
                    } else throw ProtocolError.invalidArgs("deny takes request_id (a request, with reason) or did (a reader).");
                }
                case "keys" -> {
                    ArrayNode a = r.putArray("keys");
                    for (ApiKeys.Key k : ApiKeys.list(store, Accounts.OWNER)) a.addObject().put("id", k.id()).put("prefix", k.prefix()).put("level", k.level().name()).put("label", k.label()).put("made", k.made()).put("used", k.used());
                    r.put("summary", a.isEmpty() ? "You have no keys. op=key with a label makes one." : a.size() + (a.size() == 1 ? " key; " : " keys; ") + "a program sends one as Authorization: Bearer <key>.");
                }
                case "key" -> {
                    String label = args.path("label").asText("").strip();
                    Patrons.Level lvl = args.path("level").asText("").isBlank() ? Patrons.Level.read : Patrons.levelOf(args.path("level").asText(""));
                    if (lvl == null || lvl == Patrons.Level.deny) throw ProtocolError.invalidArgs("level is read or write.");
                    ApiKeys.Minted m = ApiKeys.mint(store, Accounts.OWNER, label, lvl);
                    r.put("id", m.key().id()).put("prefix", m.key().prefix()).put("level", m.key().level().name()).put("label", m.key().label()).put("key", m.secret());
                    r.put("summary", "A new key for " + m.key().label() + ", which " + (m.key().reads() ? "reads" : "reads and writes") + ". It is shown this once; the library keeps only its hash.");
                }
                case "revoke" -> {
                    String which = reqStr(args, "key_id").strip();
                    if (!ApiKeys.revoke(store, Accounts.OWNER, which)) throw ProtocolError.invalidArgs("There is no key of yours named " + which + ". op=keys lists them.");
                    r.put("key_id", which).put("revoked", true).put("summary", "The key is revoked; a program that used it is refused from now on.");
                }
                default -> throw ProtocolError.invalidArgs("op is collect, readers, allow, remove, invite, requests, approve, deny, keys, key or revoke.");
            }
        } catch (IOException e) { throw ProtocolError.invalidArgs(e.getMessage()); }
        return r;
    }

    /** Register a webhook for this patron: every change is posted there, signed with the secret. Read access is enough. */
    public ObjectNode subscribe(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        if (patron.anonymous()) throw ProtocolError.forbidden("Sign in to subscribe. Notifications are signed for a named user.");
        String url = reqStr(args, "url").strip();
        String secret = args.path("secret").asText("").strip();
        if (secret.isEmpty()) { byte[] b = new byte[24]; new SecureRandom().nextBytes(b); secret = Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
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
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        long since = 0;
        if (args.hasNonNull("since") && !args.get("since").asText().isBlank()) {
            try { since = Long.parseLong(args.get("since").asText()); }
            catch (NumberFormatException e) { throw ProtocolError.invalidArgs("This cursor is not valid for this library."); }
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
    /** How long a program's submit waits for the model's one verdict before filing anyway (RESEARCHZOSHO_SUBMIT_CHECK_SECONDS, 45 unless set). */
    static volatile long SUBMIT_CHECK_MS = Math.max(1, Config.getInt("RESEARCHZOSHO_SUBMIT_CHECK_SECONDS", 45)) * 1000L;   // a test shortens it

    public static final int DEFAULT_TURNS = 0;
    /** The minutes a run about a video the person remembers has when they set none: the candidates, the sift over a thousand pictures, the looks. */
    public static final int FIND_ONE_MINUTES = 90;

    /**
     * Before the checks that refuse a question too short to research: a program's short question that reads as a person asking about
     * harming themselves is answered with where to find help ({@link ProtocolError#confirm}), not with "too short". A longer question
     * is read for it with the rest ({@link ContentOffer#detect}), once.
     */
    public static void helpBeforeTooShort(JsonNode args, Way way) {
        if (!Way.PROGRAM.where().equals(way.where())) return;   // the terminal, the web page and the chat ask the person themselves
        String question = args.path("question").asText("").strip();
        if (question.isEmpty() || question.length() >= 12 || allowOf(args).contains(ContentPolicy.SELF_HARM)) return;
        if (ContentOffer.harm(question, null) == ContentOffer.Harm.SURE) throw ProtocolError.confirm(ContentOffer.confirmMessage(localeOf(args)), helpData(args));
    }

    /** The argument checks, separately so a transport can run them before its own drive check. */
    public static void validateResearch(JsonNode args) {
        String question = reqStr(args, "question").strip();
        if (question.length() < 12) throw ProtocolError.invalidArgs("The question is too short. Write a full sentence.");
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
        JsonNode paid = args.path("paid");
        if (!paid.isMissingNode() && !paid.isNull() && !paid.isBoolean()) throw ProtocolError.invalidArgs("paid, when given, is true or false: false leaves the paid search places off for this run.");
        JsonNode colls = args.path("collections");
        if (!colls.isMissingNode() && !colls.isNull() && (!colls.isArray() || colls.size() > 8)) throw ProtocolError.invalidArgs("collections must be an array of at most 8 names.");
        JsonNode subs = args.path("sub_questions");
        if (!subs.isMissingNode() && !subs.isNull()) {
            if (!subs.isArray() || subs.size() > 8) throw ProtocolError.invalidArgs("sub_questions must be an array of at most 8 strings.");
            for (JsonNode s : subs) if (!s.isTextual() || s.asText().isBlank()) throw ProtocolError.invalidArgs("sub_questions must be an array of non-empty strings.");
        }
        JsonNode field = args.path("field");
        if (!field.isMissingNode() && !field.isNull() && (!field.isTextual() || field.asText().isBlank())) throw ProtocolError.invalidArgs("field, when given, is the name of one of the library's fields, for example \"science\".");
        validateAllow(args);
        ReportOptions.of(args);   // length, citations, shape (0.7.0): a value none of its option's is refused with what it takes
    }

    /** What {@code allow}, when given, may say: a list of the names in {@link ContentPolicy#ALLOW_NAMES}, once each. */
    static final String ALLOW_TAKES = "allow, when given, is a list of what to let into this run that the library leaves out by default: \"explicit\" (pornography, "
            + "and gore) and \"howto\" (step-by-step instructions for making a weapon or an explosive, making an illegal drug, or running working exploit code against a system); "
            + "and \"self-harm\", the yes to researching a question that reads as a person asking about harming themselves, after the help the library showed.";

    /** The check of {@code allow}, as {@code field} is checked: a list of known names, or a refusal that says what it takes. */
    public static void validateAllow(JsonNode args) {
        JsonNode allow = args.path("allow");
        if (allow.isMissingNode() || allow.isNull()) return;
        if (!allow.isArray() && !allow.isTextual()) throw ProtocolError.invalidArgs(ALLOW_TAKES);
        List<String> names = ContentOffer.names(allow);
        if (names.size() > ContentPolicy.ALLOW_NAMES.size()) throw ProtocolError.invalidArgs(ALLOW_TAKES);
        for (String n : names) if (!ContentPolicy.ALLOW_NAMES.contains(n)) throw ProtocolError.invalidArgs("This library cannot let in \"" + n + "\". " + ALLOW_TAKES);
    }

    /**
     * What a job's arguments let in for its run: {@code allow}, the names in {@link ContentPolicy#ALLOW_NAMES} (a job filed before 0.5.0
     * carries none, and a nightly one never does).
     */
    /**
     * The person's language and country, as a language tag a program sends with a question ({@code locale}: "es-MX", "ja-JP", "en"):
     * the help for a question about harming oneself comes in that language, with that country's services first. Null when there is
     * none, or it is not a language tag, and the machine's own locale is used.
     */
    static Locale localeOf(JsonNode args) {
        String t = args == null ? "" : args.path("locale").asText("").strip().replace('_', '-');
        if (t.isEmpty()) return null;
        Locale l = Locale.forLanguageTag(t);
        return l.getLanguage().isEmpty() ? null : l;
    }

    /** Where to find help as data, for the locale a program sent; the machine's own locale without one. */
    static ObjectNode helpData(JsonNode args) {
        Locale l = localeOf(args);
        return CrisisHelp.data(l == null ? Locale.getDefault() : l);
    }

    public static List<String> allowOf(JsonNode args) {
        List<String> out = new ArrayList<>();
        if (args != null) for (String a : ContentOffer.names(args.path("allow"))) if (ContentPolicy.ALLOW_NAMES.contains(a) && !out.contains(a)) out.add(a);
        return out;
    }

    /** The fields a job's arguments ask for: {@code field}, one name (older jobs carry none). */
    /** The goal a filed run serves by name, from its job's args: what the asker named, kept so the run's workers get its rules. */
    public static List<String> goalsOf(JsonNode args) {
        String g = args == null ? "" : args.path("goal").asText("").strip();
        return g.isEmpty() ? List.of() : List.of(g.toLowerCase(Locale.ROOT));
    }

    public static List<String> fieldsOf(JsonNode args) {
        String f = args == null ? "" : args.path("field").asText("").strip();
        return f.isEmpty() ? List.of() : List.of(f.toLowerCase(Locale.ROOT));
    }

    /**
     * Where a research ask came in, for the fields ledger and for the suggestion: {@code how} a field it names was chosen, {@code where}
     * the person would be told that the question looks like a field's, and whether the protocol tells them in its result. The chat and
     * the command line ask the person themselves and tell nothing here.
     */
    public record Way(String how, String where, boolean tell) {
        public static final Way PROGRAM = new Way("mcp-field", "program", true);
        public static final Way QUIET = new Way("asked", "", false);
    }

    public ObjectNode research(JsonNode args) throws IOException { return research(args, Way.PROGRAM); }

    /**
     * The field a goal's name resolves to, through the goals registry: a goal backed by one field names it; a goal backed by several (a
     * dossier on a person or an organisation) names none, and the run's fields choose by the question as they do; a name that is no goal
     * is refused with the goals' names.
     */
    static String goalField(String goal) {
        String g = goal == null ? "" : goal.strip();
        if (g.isEmpty()) return "";
        Goal known = Goals.named(g);
        if (known == null) throw ProtocolError.invalidArgs("This library has no goal called \"" + g + "\". Its goals are: " + String.join(", ", Goals.known().stream().map(Goal::name).toList()) + ".");
        return known.profiles().size() == 1 ? known.profiles().get(0).name() : "";
    }

    /**
     * A research run filed. {@code field} names a field the run is to be (a field that joins only when asked joins only this way); a name
     * the library does not know, or has turned off, is refused with a sentence that says which fields there are. With no field, a question
     * that looks like a field's that joins only when asked is filed as ordinary research, and the result carries {@code suggestion}: the
     * field, what its mode does, and how to ask for it. Once per question.
     */
    public ObjectNode research(JsonNode args, Way way) throws IOException { return research(args, way, (Jobs.Offer) null, 0); }

    /**
     * {@code offered} not null: the run is filed waiting for the person's answer to the chat's question about that field ({@link
     * Jobs#submitOffered}); nobody's answer by {@code offeredUntil} starts it as ordinary research.
     */
    ObjectNode research(JsonNode args, Way way, Fields.Suggestion offered, long offeredUntil) throws IOException { return research(args, way, offered, offeredUntil, false); }

    /** {@code onlyOnYes}: the offered run starts only on a yes; anything else stops it (a second run of a question that already runs). */
    ObjectNode research(JsonNode args, Way way, Fields.Suggestion offered, long offeredUntil, boolean onlyOnYes) throws IOException {
        return research(args, way, offered == null ? null : new Jobs.Offer(offered.field(), onlyOnYes, List.of()), offeredUntil);
    }

    /**
     * The run filed waiting for the person's answers to {@code offer}'s questions (null: filed at once). A run a program files (where
     * nobody can answer) has its question read for what the library leaves out by default: it goes with that left out, unless {@code allow}
     * lets it in, and the result carries {@code content_suggestion}: what may be needed and how to let it in.
     */
    ObjectNode research(JsonNode args, Way way, Jobs.Offer offer, long offeredUntil) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        if (offer == null) helpBeforeTooShort(args, way);
        validateResearch(args);
        if (args.path("choices").isArray() && args.path("choices").size() > 0) {   // a question with named choices: the run waits for one of them, or for the time to pass
            List<String> choices = new ArrayList<>();
            for (JsonNode c : args.path("choices")) if (c.isTextual() && !c.asText().isBlank() && choices.size() < 8 && !choices.contains(c.asText().strip())) choices.add(c.asText().strip());
            if (choices.size() < 2) throw ProtocolError.invalidArgs("choices needs two to eight names to choose among.");
            String cq = args.path("choice_question").asText("").strip();
            offer = (offer == null ? new Jobs.Offer(null, false, List.of()) : offer).withChoices(choices, cq.isEmpty() ? "Which of these is meant?" : cq);
            if (offeredUntil <= 0) offeredUntil = System.currentTimeMillis() + Librarian.OFFER_MINUTES * 60_000L;
        }
        String field = args.path("field").asText("").strip();
        if (field.isEmpty()) field = goalField(args.path("goal").asText("").strip());   // a goal names the run's shape (DESIGN_BRAIN.md §5)
        if (!field.isEmpty()) {
            Profile p = Profiles.named(field);
            List<String> on = Profiles.enabledProfiles(store).stream().map(Profile::name).toList();
            if (p == null) throw ProtocolError.invalidArgs("This library has no field called \"" + field + "\". Its fields are: " + String.join(", ", on) + ".");
            if (!on.contains(p.name())) throw ProtocolError.invalidArgs("The " + p.name() + " field is turned off in this library. The owner turns it on with the command researchzosho profile enable " + p.name() + ".");
            field = p.name();
        }
        String question = reqStr(args, "question").strip();
        List<String> allow = allowOf(args);
        // nobody can answer a program's run: its question is read for what it may need, and for a person asking about harming themselves,
        // which starts nothing: the caller is answered with the confirm error, whose message is where to find help and the sentence that
        // says to ask the person, so that a client that prints errors shows it to them
        // A program's run: its question is read for what it may need (what the library leaves out by default) and for a person asking
        // about harming themselves, which starts nothing — within a bound, so that submit returns. A model too busy to answer in time: the
        // service reads the question before the run starts, the job carries what it found, and a run that reads as a person asking about
        // harming themselves is held there for the person's yes. (A submit used to wait for three model calls behind running workers, time
        // out at the caller, and the caller's retry filed a second run, 2026-10-07.)
        ContentOffer.Detected detected = null;
        boolean pending = false;   // the model gave no verdict in time: the service reads the question before the run, and holds it if need be
        if (Way.PROGRAM.where().equals(way.where()) && offer == null) {
            detected = ContentOffer.detectWithin(question, SUBMIT_CHECK_MS);
            pending = detected == null;
            if (detected != null && detected.harm() == ContentOffer.Harm.SURE && !allow.contains(ContentPolicy.SELF_HARM)) throw ProtocolError.confirm(ContentOffer.confirmMessage(localeOf(args)), helpData(args));
        }
        // a run cannot open files on this machine: it reads the web and the library. So when the library owner's question
        // names a file that exists, the library reads it in first and tells the run where to look.
        List<String> readIn = readInNamedFiles(question, args.path("patron"), patron);
        if (!readIn.isEmpty()) question = question + "\n\n[Before this run the library read in: " + String.join("; ", readIn) + ". A research run cannot open files or run commands on this machine. Read these from the library instead: shelf_search finds documents, and the holdings tool says whether a title is in a list the person owns.]";
        String mode = args.path("mode").asText("broad");
        boolean quick = args.path("quick").asBoolean(false);   // "look it up now": short ceilings unless the ask names its own, and the front of the line
        int maxTurns = args.path("max_turns").asInt(quick ? Explain.QUICK_TURNS : DEFAULT_TURNS);
        int maxMinutes = args.path("max_minutes").asInt(quick ? Explain.QUICK_MINUTES : 0);
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
        String who = patron.anonymous() ? "anonymous" : patron.did();
        ObjectNode a = M.createObjectNode();
        a.put("question", question); a.put("mode", mode); a.put("max_turns", maxTurns); a.put("max_minutes", maxMinutes);
        if (!readIn.isEmpty()) { ArrayNode ri = a.putArray("read_in"); readIn.forEach(ri::add); }
        if (quick) a.put("quick", true);
        // the named model this run runs on (researchzosho models): the service looks it up when the run starts
        String entryName = args.path("model").asText("").strip().toLowerCase(Locale.ROOT);
        if (entryName.isEmpty() && Auto.isDefault()) entryName = Auto.NAME;   // nothing named, and there is a choice: best judgement, made when the run starts
        if (!entryName.isEmpty() && !entryName.equals(ModelEntries.DEFAULT)) {
            if (!entryName.equals(Auto.NAME) && ModelEntries.get(entryName) == null) throw ProtocolError.invalidArgs(ModelEntries.noMatch(entryName));
            a.put("model_entry", entryName);
        }
        // "this one first": a full run, with the full limits, ahead of the others waiting — quick is a lookup with short limits, not this
        boolean priority = args.path("priority").asBoolean(false);
        if (priority) a.put("priority", true);
        if (args.path("sub_questions").isArray() && args.path("sub_questions").size() > 0) {
            ArrayNode subs = a.putArray("sub_questions");
            for (JsonNode s : args.path("sub_questions")) subs.add(s.asText().strip());
        }
        a.put("sources", args.path("sources").asText("both"));
        if (!args.path("paid").asBoolean(true)) a.put("paid", false);   // no paid search place for this run: the tally's gate leaves them off (SearchTally)
        if (args.hasNonNull("seek") && !args.get("seek").asText().isBlank()) a.put("seek", Seeks.checkedName(args.get("seek").asText()));   // a seek: its own copy of the shelves
        if (args.path("collections").isArray() && args.path("collections").size() > 0) {
            ArrayNode cs = a.putArray("collections");
            for (JsonNode c : args.path("collections")) if (c.isTextual() && !c.asText().isBlank()) cs.add(c.asText().strip());
        }
        Fields.Suggestion told = null;
        if (!field.isEmpty()) { a.put("field", field); a.put("field_how", way.how()); }
        String goalAsked = args.path("goal").asText("").strip();   // a goal named by the asker travels with the run, so its workers get the goal's rules (a goal backed by several fields names no field)
        if (!goalAsked.isEmpty() && Goals.named(goalAsked) != null) a.put("goal", Goals.named(goalAsked).name());
        // a run about a video the person remembers plans for the funnel: the candidates, the sift over their thumbnails and first sheets, then the looks; ninety minutes when the person set no ceiling
        boolean findOneMinutes = maxMinutes == 0 && !quick && "choose".equals(a.path("goal").asText("")) && YoutubeProfile.findOne(question);
        if (findOneMinutes) { maxMinutes = FIND_ONE_MINUTES; a.put("max_minutes", maxMinutes); }
        else if (way.tell() && (told = Fields.tell(store, question, way.where())) != null) a.putObject("suggested").put("field", told.field()).put("offer", told.offer());
        // what this run lets in that the library leaves out by default: only what the caller named in allow, for this run alone
        if (!allow.isEmpty()) {
            ArrayNode al = a.putArray("allow"); allow.forEach(al::add);
            // how it was asked for, for the ledger: a program named it; the terminal and the web page say whether it was a flag, a box or a yes
            a.put("allow_how", Way.PROGRAM.where().equals(way.where()) ? "mcp-allow" : args.path("allow_how").asText(way.how()));
        }
        if (pending) a.put("content_check", "pending");
        // nobody can answer a program's run: its question is read for what it may need, and the result says how to let it in
        List<String> needs = List.of();
        if (detected != null) { needs = new ArrayList<>(detected.needs()); needs.removeAll(allow); }
        String key = args.path("idempotency_key").asText("").strip();
        if (!key.isEmpty()) a.put("idempotency_key", key);
        String title = args.path("title").asText("").strip();
        if (!title.isEmpty()) a.put("title", Acquisitions.compress(title, 160));
        ReportOptions options = ReportOptions.of(args);   // the report's length, citations and shape, as the ask named them; the run adds the library's defaults
        options.put(a);
        String deltaOf = args.path("delta_of").asText("").strip();
        if (!deltaOf.isEmpty()) {
            if (!deltaOf.startsWith("I-") || !LibraryStore.safeName(deltaOf)) throw ProtocolError.invalidArgs("delta_of names an earlier report by its id (I-…).");
            if (entry(deltaOf, kindOf(deltaOf), false) == null) throw ProtocolError.notFound("No report has the id " + deltaOf + ".");
            a.put("delta_of", deltaOf);
            if (title.isEmpty()) a.put("title", Acquisitions.compress("What is new since " + deltaOf + ": " + question, 160));
        }
        // a program's same ask filed again while the first still waits or runs — a retry after a timeout, or a program that lost the id —
        // is that run. A person asking again at the terminal or on the page means it: that is a new run, asked its questions again
        if (offer == null && Way.PROGRAM.where().equals(way.where())) {
            ObjectNode same = jobs.sameActive(who, key, a);
            if (same != null) {
                ObjectNode r = envelope();
                r.put("job_id", same.path("job_id").asText()); r.put("state", same.path("state").asText()); r.put("already_filed", true);
                return r;
            }
        }
        String id = offer != null ? jobs.submitOffered("research", who, a, offer, offeredUntil) : jobs.submit("research", who, a);
        store.circulate("research-job", id + " by " + patron.writer() + " :: " + Acquisitions.compress(question, 120));
        ObjectNode r = envelope();
        r.put("job_id", id);
        r.put("state", offer != null ? Jobs.OFFERED : "queued");
        if (offer != null && offer.asksChoice()) { ArrayNode oc = r.putArray("offered_choices"); offer.choices().forEach(oc::add); r.put("offered_question", offer.question()); }
        if (quick) r.put("quick", true);
        if (priority) r.put("priority", true);
        if (a.has("paid")) r.put("paid", false);
        if (findOneMinutes) { r.put("max_minutes", maxMinutes); r.put("note", "A run about a video you remember has " + FIND_ONE_MINUTES + " minutes by default: it gathers the candidates, sifts their thumbnails and first sheets, then looks at the survivors. --max-minutes sets another ceiling."); }
        if (!field.isEmpty()) r.put("field", field);
        if (!allow.isEmpty()) r.set("allow", a.get("allow").deepCopy());
        if (told != null) r.putObject("suggestion").put("field", told.field()).put("why", told.offer()).put("how", told.how());
        if (!needs.isEmpty()) r.set("content_suggestion", ContentOffer.suggestion(needs));
        if (pending) r.put("content_check", "pending");
        if (a.hasNonNull("title")) r.put("title", a.get("title").asText());
        if (!options.isEmpty()) r.set("options", options.json());
        // the judge could not tell whether the question is a person asking about harming themselves: the help is shown anyway, it costs nothing
        if (detected != null && detected.harm() == ContentOffer.Harm.UNSURE && !allow.contains(ContentPolicy.SELF_HARM)) {
            Locale l = localeOf(args);
            r.putObject("help").put("text", l == null ? CrisisHelp.text() : CrisisHelp.text(l)).setAll(helpData(args));
        }
        return r;
    }

    /** How a run is filed where a person can answer the library's question about a field: the chat files it waiting for their answer. */
    public interface Asker { ObjectNode file(ObjectNode ask) throws IOException; }

    /** Set by the chat for its own calls: the runs a batch starts are filed the chat's way. Null: a program's way, told in the result. */
    volatile Asker asker = null;

    /**
     * One run of a batch, filed as the program's ask. The batch's result carries one suggestion: the first question of it that looks like a
     * field's, with that question; it alone is written down as told, since it alone is shown. The later runs are filed without telling.
     */
    private ObjectNode fileRun(ObjectNode ask, ObjectNode batch) throws IOException {
        if (asker != null) return asker.file(ask);
        ObjectNode r;
        try { r = research(ask, batch.has("suggestion") ? new Way(Way.PROGRAM.how(), Way.PROGRAM.where(), false) : Way.PROGRAM); }
        catch (ProtocolError e) {
            if (!ProtocolError.CONFIRM.equals(e.code)) throw e;
            // its question reads as a person asking about harming themselves: nothing is filed for it, and the batch says so for that
            // question, with where to find help; the batch's result carries the help once, for the first such question
            ObjectNode n = M.createObjectNode();
            n.put("state", "not_started");
            Locale l = localeOf(ask);
            n.put("help", ContentOffer.confirmOne(l));
            if (!batch.has("help")) batch.putObject("help").put("text", l == null ? CrisisHelp.text() : CrisisHelp.text(l)).put("how", ContentOffer.CONFIRM_ONE).put("question", ask.path("question").asText("")).setAll(helpData(ask));
            return n;
        }
        if (r.has("suggestion") && !batch.has("suggestion")) { ObjectNode s = r.get("suggestion").deepCopy(); batch.set("suggestion", s.put("question", ask.path("question").asText(""))); }
        // what a run of the batch may need that the library leaves out by default, and how the person lets it in: once, for the first such question
        if (r.has("content_suggestion") && !batch.has("content_suggestion")) { ObjectNode s = r.get("content_suggestion").deepCopy(); batch.set("content_suggestion", s.put("question", ask.path("question").asText(""))); }
        // the judge could not tell whether the question is a person asking about harming themselves: the run starts, and the help goes on the batch's result
        if (r.has("help") && !batch.has("help")) { ObjectNode h = r.get("help").deepCopy(); batch.set("help", h.put("question", ask.path("question").asText(""))); }
        return r;
    }

    /** Whether a run a batch tool asked for was filed: it has a job id. One the library did not start has none. */
    static boolean filed(JsonNode run) { return run != null && !run.path("job_id").asText("").isBlank(); }

    /**
     * library_sharpen: a rough question in, a better one out — before anything runs. What the shelves hold
     * on it, who studies it and what each would ask, then one judge call: the question rewritten, its assumptions owned up
     * to, a brief (scope, sub-questions, sources wanted, deliverables), at most three questions back, a mode and a size.
     * {@code research_question} is the text to pass to library_research as it is, or after the person edits it. Read
     * access; needs a model drive. Two or three model calls.
     */
    public ObjectNode sharpen(JsonNode args) throws IOException {
        Patrons.check(store, Patrons.Patron.from(args), Patrons.Level.read);
        return sharpen(args, Explain.drive(), Researcher.webTools());
    }

    /** library_sharpen with the model and the web tools given. */
    ObjectNode sharpen(JsonNode args, Researcher.Drive judge, Researcher.Tools tools) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String question = reqStr(args, "question").strip();
        Sharpen.Sharpened s = Sharpen.run(store, patron, judge, tools, question);
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
        if (term.isEmpty() && id.isEmpty()) throw ProtocolError.invalidArgs("Give the id of an entry to explain, or a term. With a term, in = the entry it appears in.");
        Explain.Reading r = term.isEmpty()
                ? Explain.entry(store, rung == Explain.Rung.written ? null : Explain.drive(), id, rung, fresh, Explain.Progress.NONE)
                : Explain.term(store, Explain.drive(), term, id, rung, fresh, Explain.Progress.NONE);
        ObjectNode out = envelope();
        out.setAll(r.json());
        store.circulate("explain", patron.label() + " :: " + (term.isEmpty() ? id : term + (id.isEmpty() ? "" : " in " + id)) + " @ " + rung.name());
        return out;
    }

    /**
     * library_map: the graph around a focus — a node name (a person, a place, a work, a
     * concept) or an entry id — out to {@code depth} hops (default 1, max 3), at most {@code k} nodes
     * (default 25). Nodes carry kind, label, aliases, wikidata; edges ARE findings (id, state,
     * confidence, disputed).
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
        g.nodes().stream().sorted((x, y) -> Integer.compare(y.degree(), x.degree())).limit(12)
                .forEach(n -> suggestions.addObject().put("label", n.label()).put("kind", n.kind()).put("degree", n.degree()));
        if (focus.isEmpty()) { r.putNull("node"); r.putArray("nodes"); r.putArray("edges"); r.putArray("open"); r.put("holds_nothing", g.nodes().isEmpty()); return r; }
        Graph.Neighbourhood nb = g.around(focus, depth, k);
        if (nb.focus() == null) {
            // no node of exactly that name: the nearest names — a label or alias containing the words typed — most connected first;
            // the best one becomes the focus ("Tokyo" → "Tokyo Vice"; "Akasaka" → "Akasaka district"), and the rest are offered
            String needle = focus.toLowerCase(Locale.ROOT);
            List<Graph.Node> near = g.nodes().stream()
                    .filter(n -> n.label().toLowerCase(Locale.ROOT).contains(needle) || n.aliases().stream().anyMatch(al -> al.toLowerCase(Locale.ROOT).contains(needle)))
                    .sorted((x, y) -> Integer.compare(y.degree(), x.degree())).toList();
            if (!near.isEmpty()) {
                nb = g.around(near.get(0).label(), depth, k);
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
        String driveUrl = Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200");
        String model = Config.get("RESEARCHZOSHO_MODEL", "local-model");
        if (!Crews.driveAnswers(driveUrl)) throw ProtocolError.unavailable("No model answers at " + driveUrl + ". This needs a model.");
        return perspectives(args, perspectivesDrive(driveUrl, model), Researcher.webTools());
    }

    /** The seat library_perspectives and the perspectives command ask: the judgment seat, watched, so a decline is said, never "no perspectives". */
    static Researcher.Drive perspectivesDrive(String driveUrl, String model) { return Researcher.watchedJudge(driveUrl, model, Declines.toCrewsLog(null, "declines")); }

    /** library_perspectives with the model and the web tools given. */
    ObjectNode perspectives(JsonNode args, Researcher.Drive judge, Researcher.Tools tools) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.read);
        String question = reqStr(args, "question").strip();
        if (question.length() < 12) throw ProtocolError.invalidArgs("The question is too short.");
        List<Perspectives.Perspective> ps = Perspectives.discover(question, judge, tools, Math.min(8, Math.max(1, args.path("max").asInt(5))));
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
            Config.set(ResearchSettings.PAUSE, op.equals("pause") ? "on" : "off");
            r.put("paused", op.equals("pause"));
            return r;
        }
        if (op.equals("choose")) {   // the person's answer to a run that waits for one of the named choices: a program sends it only after asking them
            Patrons.check(store, patron, Patrons.Level.write);
            if (id.isEmpty()) throw ProtocolError.invalidArgs("choose needs job_id.");
            ObjectNode j = jobs.get(id);
            if (j == null || !Jobs.OFFERED.equals(j.path("state").asText()) || !Jobs.choiceWaits(j)) throw ProtocolError.invalidArgs("Research run " + id + " is not waiting for a choice.");
            String choice = Jobs.choiceOf(j, args.path("choice").asText(""));
            if (choice == null) { List<String> cs = new ArrayList<>(); int n = 0; for (JsonNode c : j.path("offered_choices")) cs.add(++n + " " + c.asText()); throw ProtocolError.invalidArgs("Say which, by number or by name: " + String.join(", ", cs) + "."); }
            if (!jobs.releaseChoice(id, choice)) throw ProtocolError.invalidArgs("Research run " + id + " is not waiting for a choice.");
            r.put("job_id", id); r.put("choice", choice); r.put("state", jobs.get(id).path("state").asText());
            return r;
        }
        if (op.equals("allow") || op.equals("decline")) {   // the person's answer to a run the service held: a program sends it only after asking them
            Patrons.check(store, patron, Patrons.Level.write);
            if (id.isEmpty()) throw ProtocolError.invalidArgs(op + " needs job_id.");
            if (!jobs.releaseHelp(id, op.equals("allow"))) throw ProtocolError.invalidArgs("Research run " + id + " is not waiting for an answer about help.");
            ObjectNode j = jobs.get(id);
            r.put("job_id", id); r.put("state", j == null ? "" : j.path("state").asText());
            return r;
        }
        if (op.equals("stop")) {   // one run: a queued one never starts, a running one ends within seconds, also in the middle of a call; the nightly tasks after their step
            Patrons.check(store, patron, Patrons.Level.write);
            if (id.isEmpty()) throw ProtocolError.invalidArgs("stop needs job_id.");
            ObjectNode j = jobs.get(id);
            if (j == null || !Jobs.visibleTo(patron, j)) throw ProtocolError.notFound("No research run has the id " + id + ".");
            Jobs.Stop now = jobs.stopRun(id, patron.writer());
            if (now == null) throw ProtocolError.invalidArgs("Research run " + id + " is not queued or running. It is " + j.path("state").asText() + ".");
            r.put("job_id", id); r.put("state", now.state()); r.put("kind", now.kind());
            if (now.filed()) r.put("filed", true);   // the run had begun filing its report: the report stays
            return r;
        }
        r.put("paused", ResearchSettings.paused());
        if (!id.isEmpty()) {
            ObjectNode j = jobs.get(id);
            ObjectNode v = j == null ? null : Jobs.view(j);
            if (v == null) throw ProtocolError.notFound("No research run has the id " + id + ".");
            r.set("job", v);
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
                if (!Jobs.visibleTo(patron, j)) continue;
                finished.add(Jobs.view(j));
                if (finished.size() >= limit) break;
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
        r.put("version", Version.string());
        counts.put("subject", (int) Related.counts(store).size());
        r.put("last_updated", lastUpdated());
        r.put("search_by_meaning", SearchByMeaning.standing().state());   // on | not answering | off — a silent words-only library is the failure this names
        return r;
    }

    // ---- resources ----

    /** resources/list — paged: finding://, article://, raw:// (locator = the captured URL). For a caller who does not name itself. */
    public ObjectNode resourcesList(String cursorText) throws IOException { return resourcesList(cursorText, Patrons.Patron.ANONYMOUS); }

    /** resources/list as {@code patron} may see it: read access. */
    public ObjectNode resourcesList(String cursorText, Patrons.Patron patron) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        int offset = 0;
        if (cursorText != null && !cursorText.isBlank()) {
            try { offset = Integer.parseInt(cursorText); } catch (NumberFormatException e) { throw ProtocolError.invalidArgs("This cursor is not valid for this library."); }
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
        b.put("uriTemplate", "raw://{locator}"); b.put("name", "The saved text of a source"); b.put("mimeType", "text/plain");
        ObjectNode c = t.addObject();
        c.put("uriTemplate", "article://{id}"); c.put("name", "An article in the library"); c.put("mimeType", "text/markdown");
        return r;
    }

    /** resources/read — the same body library_get / library_read return. For a caller who does not name itself. */
    public ObjectNode resourcesRead(String uri) throws IOException { return resourcesRead(uri, Patrons.Patron.ANONYMOUS); }

    /** resources/read as {@code patron} may see it: read access. */
    public ObjectNode resourcesRead(String uri, Patrons.Patron patron) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        if (uri == null || uri.isBlank()) throw ProtocolError.invalidArgs("A resource uri is required.");
        int scheme = uri.indexOf("://");
        String name = scheme < 0 ? uri : uri.substring(scheme + 3);
        // finding:// and article:// name a shelf entry; raw:// names a LOCATOR (a URL as cited, or a file
        // name) — its direct file hit is guarded in RawCapture.find, a URL is matched, never joined.
        if (!uri.startsWith("raw://") && !LibraryStore.safeName(name)) throw ProtocolError.invalidArgs("A resource name is an entry name, not a file path: " + uri);
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
                // a report one of its own checks holds back is a draft that waits for the keeper's eye: its confidence is low, and waits says who held it and why
                ReportStanding.Row hold = inv.state() == Finding.State.draft ? ReportStanding.held(store, inv) : null;
                e.put("claim_type", "synthesis"); e.put("confidence", hold != null ? "low" : "medium");
                e.put("writer", inv.writer()); e.put("recorded_at", inv.recordedAt());
                e.put("title", inv.title());
                if (!inv.language().isEmpty()) e.put("language", inv.language());   // the language it was written in, when the library's setting or the question decided it
                if (!inv.options().isEmpty()) e.set("options", inv.options().json());   // the length, citations and shape it was written with (0.7.0)
                if (hold != null) {
                    ObjectNode w = e.putObject("waits");
                    w.put("held_by", hold.by()); w.put("date", hold.date()); w.put("topic", hold.topic().equals("-") ? "" : hold.topic());
                    ArrayNode why = w.putArray("reasons"); for (String reason : hold.reasons()) why.add(reason);
                }
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

    private static final Pattern REF_LINE = Pattern.compile("^\\[(\\d+)\\] (.*)$");
    private static final Pattern REF_PUBLISHED = Pattern.compile("\\(published (\\d{4}-\\d{2}-\\d{2})[^)]*\\)");
    private static final Pattern REF_SAME = Pattern.compile("\\(same text as \\[(\\d+)\\]\\)");
    private static final Pattern NOTE_LINE = Pattern.compile("^- (.+?) — source: (.+?)(?: — quote: \"(.*)\")?$", Pattern.MULTILINE);

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
        Matcher nm = NOTE_LINE.matcher(body);
        while (nm.find()) {
            String loc = nm.group(2).replaceAll("[),.;]+$", "");
            Matcher um = Pattern.compile("(?:https?|file)://\\S+").matcher(loc);
            String url = um.find() ? um.group().replaceAll("[),.;]+$", "") : loc;
            langOf.putIfAbsent(url, Lanes.languageOf(url, nm.group(3) == null ? "" : nm.group(3)));
        }
        List<String[]> rows = new ArrayList<>();   // [n, rest]
        if (refs != null) {
            for (String line : refs.split("\n")) { Matcher m = REF_LINE.matcher(line.strip()); if (m.matches()) rows.add(new String[]{m.group(1), m.group(2)}); }
        } else {
            String cited = sectionOf(body, "sources cited");
            if (cited == null) cited = sectionOf(body, "sources");
            int n = 0;
            if (cited != null) for (String line : cited.split("\n")) { String t = line.strip(); if (t.startsWith("- ")) t = t.substring(2).strip(); if (t.contains("://") || t.startsWith("raw/")) rows.add(new String[]{String.valueOf(++n), t}); }
        }
        for (String[] row : rows) {
            String rest = row[1];
            // a URL, a raw capture, a shelved file by name, or a host and path a worker wrote without its scheme ("arxiv.org/html/2510.24011v1")
            Matcher um = Pattern.compile("(?:https?|file)://\\S+|raw/\\S+|(?<=^| )[^\\s]+\\.(?:md|pdf|txt|docx|pptx|odt|epub|html?)(?= |$)|(?<=^| )(?:[a-z0-9-]+\\.)+[a-z]{2,}/\\S*").matcher(rest);
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
            Matcher pm = REF_PUBLISHED.matcher(tail);
            if (pm.find()) o.put("published", pm.group(1)); else o.putNull("published");
            boolean fetched = false;
            try { Path rp = RawCapture.find(store, locator); fetched = rp != null && Fetch.wall(RawCapture.read(rp)[1], RawCapture.read(rp)[2]) == null; } catch (Exception ignored) { }
            o.put("fetched", fetched);
            String lang = langOf.get(locator);
            if (lang == null) for (var en : langOf.entrySet()) if (en.getKey().startsWith(locator) || locator.startsWith(en.getKey())) { lang = en.getValue(); break; }
            if (lang == null) o.putNull("language"); else o.put("language", lang);
            Matcher sm = REF_SAME.matcher(tail);
            if (sm.find()) o.put("same_as", Integer.parseInt(sm.group(1))); else o.putNull("same_as");
            o.put("tier", SourceTier.of(locator).name());
            SourceRules.Rule rule = rules.ruleFor(locator);
            if (rule != null) o.put("rule", rule.kind()); else o.putNull("rule");
            o.put("why", "reference [" + row[0] + "] of the report");
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

    static final Pattern ID = Pattern.compile("^\\s*([FIA]-\\d{4}-[a-z0-9-]+)\\s*$");
    static final Pattern URL = Pattern.compile("^\\s*(https?://\\S+)\\s*$");
    static final Pattern SINCE_DATE = Pattern.compile("(?i)\\bsince\\s+(\\d{4}-\\d{2}-\\d{2})");
    static final Pattern LAST_DAYS = Pattern.compile("(?i)\\b(?:last|past)\\s+(\\d{1,3})\\s+days?\\b");
    static final Pattern CHANGED = Pattern.compile("(?i)\\b(what(?:'s| has| is)? (?:changed|new)|recent(?:ly)? (?:added|changed)|new since|変更|更新|最近)\\b|変更|更新|最近");

    /** Deterministic intent: id · locator · changes · subject · search. */
    Route route(String q) throws IOException {
        var m = ID.matcher(q);
        if (m.matches()) return new Route("id", m.group(1));
        m = URL.matcher(q);
        if (m.matches()) return new Route("locator", m.group(1));
        m = SINCE_DATE.matcher(q);
        if (m.find()) return new Route("changes", m.group(1));
        m = LAST_DAYS.matcher(q);
        if (m.find()) return new Route("changes", LocalDate.now().minusDays(Integer.parseInt(m.group(1))).toString());
        if (CHANGED.matcher(q).find()) return new Route("changes", LocalDate.now().minusDays(7).toString());
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
        catch (NumberFormatException e) { throw ProtocolError.invalidArgs("This cursor is not valid for this library."); }
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
        } catch (UncheckedIOException e) {
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
        Set<String> tonight = new HashSet<>();
        var fieldRuns = Fields.runs(store);
        for (Crews.Bundle b : Crews.plan(open.stream().filter(Frontier.Line::researchable).toList(), Crews.explorerBudget(), l -> Fields.ofLine(l, fieldRuns))) for (Frontier.Line l : b.all()) tonight.add(l.text());
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
            o.put("date", l.date()); o.put("kind", Fields.unmarked(l.kind())); o.put("text", l.text());
            String field = Fields.ofLine(l, fieldRuns);
            if (!field.isEmpty()) o.put("field", field);
            o.put("type", l.type()); o.put("parked", l.parked()); o.put("position", ++pos); o.put("tonight", tonight.contains(l.text()));
            if (l.parked()) { String why = Frontier.whyParked(store, l.text()); if (!why.isEmpty()) o.put("parked_why", why); }
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
                // the reports that wait for the keeper's eye, beside the claims: each with why it waits; op=accept with its id accepts it as it stands
                ArrayNode reports = r.putArray("reports");
                for (ReportStanding.Waiting w : ReportStanding.waiting(store)) {
                    if (f.containsKey("report") && !w.report().id().startsWith(f.get("report"))) continue;
                    ObjectNode o = reports.addObject();
                    o.put("id", w.report().id()); o.put("title", w.report().title()); o.put("state", w.report().state().name());
                    o.put("held_by", w.hold().by()); o.put("date", w.hold().date()); o.put("topic", w.hold().topic().equals("-") ? "" : w.hold().topic());
                    ArrayNode why = o.putArray("reasons"); for (String reason : w.hold().reasons()) why.add(reason);
                }
                ArrayNode recalls = r.putArray("recalls");   // notices that may concern what the person owns, beside the claims
                for (Recalls.Hit h : Recalls.hits(store, true)) recalls.add(h.json());
            }
            case "accept", "dispute", "retire" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                List<String> ids = new ArrayList<>();
                for (var x : args.path("ids")) if (!x.asText("").isBlank()) ids.add(x.asText());
                if (args.hasNonNull("id") && !args.path("id").asText().isBlank()) ids.add(args.path("id").asText());
                String report = args.path("report").asText("");
                if (!report.isEmpty()) for (var o : inboxList()) if (o.path("report").asText("").startsWith(report)) ids.add(o.path("id").asText());
                if (ids.isEmpty()) throw ProtocolError.invalidArgs("Say which claims to decide: ids[], id, or report.");
                String why = args.path("why").asText("").strip();
                if (op.equals("dispute") && why.isEmpty()) throw ProtocolError.invalidArgs("A dispute needs a reason. Give why.");
                Council c = new Council(store);
                ArrayNode decided = r.putArray("decided");
                for (String id : new LinkedHashSet<>(ids)) {
                    // a report's own id: accept takes the report as it stands, the keeper having looked; the other two are for claims
                    if (id.matches("I-\\d+-[a-z0-9-]+")) {
                        if (!op.equals("accept")) throw ProtocolError.invalidArgs("A report can be accepted; dispute and retire are for claims. " + id + " is a report.");
                        if (store.investigation(id) == null) throw ProtocolError.notFound("No report has the id " + id + ".");
                        Investigation accepted = ReportStanding.accept(store, id, ReportStanding.PERSON);
                        ObjectNode d = decided.addObject(); d.put("id", accepted.id()); d.put("state", accepted.state().name()); d.put("title", accepted.title()); d.put("kind", "report");
                        continue;
                    }
                    if (store.finding(id) == null) throw ProtocolError.notFound("No claim has the id " + id + ".");
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
            o.put("language", Frontier.language(f.title(), String.join(" ", f.sources().stream().map(Finding.Source::locator).toList())));
            o.put("sources", f.sources().size());
            Investigation inv = byFinding.get(f.id());
            if (inv != null) { o.put("report", inv.id()); o.put("report_title", inv.title()); }
            String against = "";   // read from a picture: the reading its words were checked against
            for (Profile p : Profiles.known()) { String a = p.checkedAgainst(f); if (!a.isEmpty()) against = a; }
            if (!against.isEmpty()) o.put("checked_against", against);
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

    static LibrarianIndex.Hit answeredBy(LibrarianIndex index, String question) {
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

    /**
     * What the person owns (0.6.0): their purchases, from their own records. {@code op=import} reads a file on this machine into
     * the ledger (the owner); {@code find} and {@code list} show lines (read); {@code status} the counts and where each imported
     * file's records begin; {@code forget} drops what one file added (the owner). The ledger is a reference: nothing needs it.
     */
    /** The person's own shelf (0.6.0): op=profile what they rate highly; op=have title=… (author) whether a book is already here; op=liked words=… the rated books on a subject; op=refresh (the owner) reads the catalogues of the authors rated well now, for the "what next" pool. */
    public ObjectNode myShelf(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("profile").strip().toLowerCase(Locale.ROOT);
        if (!op.equals("refresh")) Patrons.check(store, patron, Patrons.Level.read);
        ObjectNode r = envelope();
        switch (op) {
            case "profile" -> {
                ObjectNode p = Shelf.profile(store);
                r.setAll(p);
                r.put("summary", !p.path("calibre").asBoolean() ? "No Calibre library is set: the setting calibre names its folder or its metadata.db."
                        : p.path("books").asInt() + " books on the shelf, " + p.path("chosen").asInt() + " added by you one by one (the profile), " + p.path("bulk").asInt() + " from bulk imports; " + p.path("ratings_given").asInt() + " rating(s) given by you" + (p.path("ratings_imported").asInt() > 0 ? ", " + p.path("ratings_imported").asInt() + " imported and not counted" : "")
                        + (p.path("podcasts_followed").isEmpty() ? "" : "; " + p.path("podcasts_followed").size() + " podcast(s) followed") + (p.path("owned").path("lines").asInt() == 0 ? "" : "; " + p.path("owned").path("purchases").asInt() + " purchases on record") + ".");
                r.put("text", MyShelfTool.profile(p));
            }
            case "have" -> {
                String title = reqStr(args, "title");
                List<Shelf.Have> h = Shelf.have(store, title, args.path("author").asText(""));
                ArrayNode arr = r.putArray("found"); for (Shelf.Have x : h) arr.add(x.json());
                r.put("count", h.size());
                r.put("summary", h.isEmpty() ? "Not on the shelf, not in the purchase ledger, not on a holdings list." : h.size() + " match(es): " + h.get(0).where() + " — " + h.get(0).detail());
            }
            case "liked" -> {
                int limit = Math.max(1, Math.min(60, args.path("limit").asInt(20)));
                List<Shelf.Book> l = Shelf.liked(args.path("words").asText(""), args.path("stars").asDouble(0), limit);
                ArrayNode arr = r.putArray("books"); for (Shelf.Book b : l) arr.add(Shelf.line(b));
                r.put("count", l.size());
                r.put("summary", Shelf.path() == null ? "No Calibre library is set: the setting calibre names its folder or its metadata.db." : l.isEmpty() ? "No book you went for carries those words." : l.size() + " book(s) you went for, best first.");
            }
            case "refresh" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library's owner reads the catalogues of their authors now; the night housekeeping does it for everyone else.");
                int minutes = Math.max(1, Math.min(30, args.path("minutes").asInt(2)));
                String said = ShelfAuthors.nightly(minutes * 60_000L);
                r.put("summary", said + ". The night housekeeping goes on from here, two minutes a night.");
            }
            case "dna" -> {
                String title = args.path("title").asText("");
                if (title.isBlank()) {   // the owner: the night's step now
                    Patrons.check(store, patron, Patrons.Level.write);
                    if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library's owner reads the shelf for its DNA now; the night housekeeping does it for everyone else.");
                    int asked = args.path("minutes").asInt(ShelfDna.MINUTES), minutes = Math.max(1, Math.min(240, asked));
                    String said = ShelfDna.nightly(minutes * 60_000L, ShelfDna.driveModel(), () -> false);
                    r.put("summary", said + shortened(asked) + ". The night housekeeping goes on from here, " + Config.getInt(ShelfDna.MINUTES_KEY, ShelfDna.MINUTES) + " minutes a night.");
                } else {
                    Shelf.Book b = Shelf.onShelf(title, args.path("author").asText(""));
                    if (b == null) { r.put("found", false); r.put("summary", Shelf.path() == null ? "No Calibre library is set: the setting calibre names its folder or its metadata.db." : "No book on the shelf has that title."); break; }
                    r.put("found", true);
                    Labels.Model m = args.path("label").asBoolean(true) ? ShelfDna.driveModel() : null;
                    ObjectNode d;
                    try { d = m == null ? ShelfDna.measure(b) : ShelfDna.label(b, m, null); } catch (Exception e) { throw new IOException("the book could not be read for its DNA: " + e.getMessage(), e); }
                    if (d == null) d = ShelfDna.doc(b);
                    if (d == null) { r.put("summary", "The shelf has \"" + b.title() + "\" but no EPUB file for it, so there is no text to read."); break; }
                    r.set("dna", d);
                    r.put("text", DnaCard.text(d));
                    r.put("summary", DnaCard.summary(d) + (m == null && !Dna.labelledCurrent(d) ? " No model answers now, so the labelled layer waits for the night housekeeping." : ""));
                }
            }
            case "like" -> {
                String title = reqStr(args, "title");
                int limit = Math.max(1, Math.min(40, args.path("limit").asInt(10)));
                LikeX.OnShelf o = LikeX.onShelf(title, args.path("author").asText(""), limit, args.path("label").asBoolean(true));
                r.put("found", o.book() != null);
                if (!o.ok()) { r.put("summary", o.message()); break; }
                LikeX.Result res = o.result();
                r.put("compared", res.compared()); r.put("left_out", res.leftOut());
                ArrayNode rows = r.putArray("rows");
                for (LikeX.Match m : res.rows()) {
                    ObjectNode row = rows.addObject(); row.put("title", m.title()); row.put("authors", LikeX.authors(m.doc())); row.put("year", m.doc().path("record").path("year").asText("")); row.put("key", m.doc().path("key").asText());
                    row.put("distance", Math.round(m.distance() * 1000) / 1000.0);
                    ArrayNode al = row.putArray("alike"); m.alike().forEach(al::add); ArrayNode un = row.putArray("unlike"); m.unlike().forEach(un::add); ArrayNode no = row.putArray("notes"); m.notes().forEach(no::add);
                    row.put("line", LikeX.line(m));
                }
                ArrayNode side = r.putArray("side"); for (ObjectNode s : res.side()) side.add(s.path("record").path("title").asText(""));
                ArrayNode more = r.putArray("more");
                for (var e : res.more().entrySet()) { ObjectNode m = more.addObject(); m.put("author", e.getKey()); ArrayNode ts = m.putArray("titles"); for (ObjectNode s : e.getValue()) ts.add(s.path("record").path("title").asText("")); }
                r.put("text", LikeX.text(res));
                r.put("summary", res.rows().isEmpty() ? "Nothing in the store compares with \"" + o.book().title() + "\" yet: " + (res.compared() == 0 ? "no book is labelled." : "every labelled book is by the same author or left out.") : res.rows().size() + " book(s) like \"" + o.book().title() + "\" among the " + res.compared() + " labelled on your shelf, the author's own works set aside.");
            }
            case "browse" -> {
                int limit = Math.max(1, Math.min(40, args.path("limit").asInt(10)));
                long seed = args.has("seed") ? args.path("seed").asLong() : System.currentTimeMillis() / 3_600_000L;   // the same list within the hour, a new one after
                List<ObjectNode> pool = Dna.labelled(Dna.BOOK);
                List<Browse.Pick> picks = Browse.browse(pool, Prefs.load(Dna.BOOK), limit, seed);
                ArrayNode rows = r.putArray("rows");
                for (Browse.Pick p : picks) { ObjectNode row = rows.addObject(); row.put("title", p.title()); row.put("authors", LikeX.authors(p.doc())); row.put("year", p.doc().path("record").path("year").asText("")); row.put("key", p.doc().path("key").asText()); row.put("why", p.why()); }
                r.put("pool", pool.size());
                r.put("text", Browse.text(picks, pool.size(), "book"));
                r.put("summary", picks.isEmpty() ? "Nothing to browse yet: no book is labelled, or every labelled one has had a reaction or is excluded." : picks.size() + " books to browse, a spread across the " + pool.size() + " labelled; react to any and the next list moves.");
            }
            default -> throw ProtocolError.invalidArgs("op is profile, have, liked, refresh, dna, like or browse.");
        }
        return r;
    }

    /**
     * The preferences file (0.6.0): op=show (default) the lines with their sources; op=draft (the owner) writes one from the shelf; op=set name=… value=…
     * and op=unset change a line; op=export path=… and op=import path=… carry the file; op=learn reads the reactions into it.
     */
    public ObjectNode prefs(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("show").strip().toLowerCase(Locale.ROOT);
        String kind = args.path("kind").asText(Dna.BOOK).strip().toLowerCase(Locale.ROOT);
        Patrons.check(store, patron, op.equals("show") ? Patrons.Level.read : Patrons.Level.write);
        if (!op.equals("show") && !patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library's owner changes the preferences file: it is the person's own.");
        ObjectNode r = envelope();
        Prefs p = Prefs.load(kind);
        String said;
        switch (op) {
            case "show" -> said = p.exists() ? "The preferences file for " + kind + "s, " + p.lines().size() + " lines." : "No preferences file yet; the defaults apply.";
            case "draft" -> { p = Prefs.draft(kind); said = "A preferences file drafted from your shelf: " + p.of("prefer").size() + " prefer line(s), each saying how many books it rests on. Change any with prefs set, drop any with prefs unset."; }
            case "set" -> said = p.set(reqStr(args, "name"), args.path("value").asText(""), patron.name().isEmpty() ? "you" : "you (" + patron.name() + ")");
            case "unset" -> said = p.unset(reqStr(args, "name"), args.path("value").asText(""));
            case "export" -> { Path to = Path.of(reqStr(args, "path")); p.export(to); said = "The preferences file is written to " + to.toAbsolutePath() + "."; }
            case "import" -> { Path from = Path.of(reqStr(args, "path")); if (!Files.isRegularFile(from)) throw ProtocolError.invalidArgs("no file at " + from.toAbsolutePath()); int n = p.importFrom(from); said = n + " line(s) came in from " + from.getFileName() + "; a line already here was kept once."; }
            case "learn" -> { p.learn(); said = "The reactions are read into the file: " + p.of("prefer").stream().filter(l -> l.from().startsWith("learned")).count() + " prefer line(s) and " + p.of("avoid").stream().filter(l -> l.from().startsWith("learned")).count() + " avoid line(s) learned."; }
            default -> throw ProtocolError.invalidArgs("op is show, draft, set, unset, export, import or learn.");
        }
        r.set("prefs", p.json());
        r.put("text", p.text());
        r.put("summary", said);
        return r;
    }

    /**
     * The DNA store beyond the shelf (0.6.0): op=status the counts and the sharing state; op=pack-export path=… (the owner) writes a pack of the labelled
     * documents; op=pack-import path=… (the owner) reads one; op=pack-refresh fetches the published pack when the site announces one; op=podcast-like
     * words=… the episodes like one among the transcripts held; op=podcast-read (the owner) reads the transcripts held for their DNA now.
     */
    public ObjectNode dna(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("status").strip().toLowerCase(Locale.ROOT);
        String kind = args.path("kind").asText(Dna.BOOK).strip().toLowerCase(Locale.ROOT);
        if (!kind.equals(Dna.BOOK) && !kind.equals(Dna.PODCAST)) throw ProtocolError.invalidArgs("kind is book or podcast.");
        boolean owners = op.equals("pack-export") || op.equals("pack-import") || op.equals("podcast-read") || op.equals("records");
        Patrons.check(store, patron, owners ? Patrons.Level.write : Patrons.Level.read);
        if (owners && !patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library's owner writes or reads packs and reads the transcripts for their DNA.");
        ObjectNode r = envelope();
        switch (op) {
            case "status" -> { r.put("text", Pool.status(Dna.BOOK) + "\n" + Pool.status(Dna.PODCAST)); r.put("summary", Pool.status(kind)); }
            case "pack-export" -> { Path to = Path.of(reqStr(args, "path")); int n = Pool.export(kind, to, args.path("contributor").asText(Config.get("RESEARCHZOSHO_DNA_CONTRIBUTOR", ""))); r.put("count", n); r.put("summary", n + " labelled " + kind + " document(s) written to " + to.toAbsolutePath() + " as a pack (" + Pool.FORMAT + "), with their layers and provenance and nothing of you."); }
            case "pack-import" -> { Path from = Path.of(reqStr(args, "path")); if (!Files.isRegularFile(from)) throw ProtocolError.invalidArgs("no file at " + from.toAbsolutePath()); Pool.Loaded l = Pool.importPack(from); r.put("added", l.added()); r.put("kept", l.kept()); r.put("set_aside", l.setAside()); r.put("summary", l.said()); }
            case "pack-refresh" -> r.put("summary", Pool.refresh(kind));
            case "records" -> { if (Shelf.path() == null) { r.put("summary", "No Calibre library is set: the setting calibre names its folder or its metadata.db."); break; } int minutes = Math.max(1, Math.min(240, args.path("minutes").asInt(2))); r.put("summary", RecordFill.nightly(minutes * 60_000L, () -> false) + ". The night housekeeping goes on from here, two minutes a night."); }
            case "podcast-like" -> {
                PodcastDna.Found f = PodcastDna.like(reqStr(args, "words"), Math.max(1, Math.min(40, args.path("limit").asInt(10))));
                r.put("found", f.ok());
                if (!f.ok()) { r.put("summary", f.message()); break; }
                ArrayNode rows = r.putArray("rows"); for (LikeX.Match m : f.result().rows()) { ObjectNode row = rows.addObject(); row.put("title", m.title()); row.put("show", LikeX.authors(m.doc())); row.put("key", m.doc().path("key").asText()); row.put("line", LikeX.line(m)); }
                r.put("text", PodcastDna.text(f.result()));
                r.put("summary", f.result().rows().size() + " episode(s) like \"" + f.x().path("record").path("title").asText() + "\" among the " + f.result().compared() + " labelled, the same show set aside.");
            }
            case "podcast-read" -> { int asked = args.path("minutes").asInt(5), minutes = Math.max(1, Math.min(240, asked)); r.put("summary", PodcastDna.nightly(store, minutes * 60_000L, ShelfDna.driveModel(), () -> false) + shortened(asked) + "."); }
            default -> throw ProtocolError.invalidArgs("op is status, pack-export, pack-import, pack-refresh, records, podcast-like or podcast-read.");
        }
        return r;
    }

    /** The sentence when a DNA read was asked for more than one call reads: one call reads for at most 240 minutes. */
    static String shortened(int askedMinutes) {
        return askedMinutes > 240 ? ". One call reads for at most 240 minutes, so this one was shortened to 240" : "";
    }

    /** A reaction (0.6.0): reaction=like|dislike|seen|own on a shelf book by title (and author), or any title; about=<title> when it is a row of a "like" list. The owner. */
    public ObjectNode react(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        Patrons.check(store, patron, Patrons.Level.write);
        if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library's owner writes reactions down: they are the person's own.");
        String reaction = reqStr(args, "reaction").strip().toLowerCase(Locale.ROOT), title = reqStr(args, "title");
        if (!Reactions.KINDS.contains(reaction)) throw ProtocolError.invalidArgs("reaction is like, dislike, seen or own.");
        ObjectNode r = envelope();
        String author = args.path("author").asText("");
        Shelf.Book b = Shelf.onShelf(title, author);
        ObjectNode doc = null, about = null;
        try { if (b != null) doc = ShelfDna.doc(b); } catch (IOException ignored) { }
        if (doc == null) doc = Dna.byTitle(Dna.BOOK, title, author);   // a row from the store that is not on the shelf (the pool, another library)
        String aboutTitle = args.path("about").asText("");
        if (!aboutTitle.isBlank()) { Shelf.Book ab = Shelf.onShelf(aboutTitle, ""); try { if (ab != null) about = ShelfDna.doc(ab); } catch (IOException ignored) { } if (about == null) about = Dna.byTitle(Dna.BOOK, aboutTitle, ""); if (about == null) { about = r.objectNode(); about.put("key", "title:" + Reactions.norm(aboutTitle)); about.putObject("record").put("title", aboutTitle); } }
        String keptTitle = b != null ? b.title() : doc != null ? doc.path("record").path("title").asText(title) : title, keptAuthors = b != null ? b.authors() : doc != null ? doc.path("record").path("authors").asText(author) : author;
        Reactions.Reaction x = Reactions.add(Dna.BOOK, reaction, doc, keptTitle, keptAuthors, about);
        Prefs.load(Dna.BOOK).learn();
        r.set("reaction", x.json());
        int n = Reactions.all(Dna.BOOK).size();
        r.put("summary", "Written down: " + reaction + " — " + x.title() + (x.authors().isEmpty() ? "" : " by " + x.authors()) + (doc != null ? "" : b == null ? " (not a book on the shelf; kept by its title)" : " (its DNA is not made yet; the reaction is kept by its title)") + (about == null ? "" : ", on the list like \"" + x.aboutTitle() + "\"") + ". " + n + " reaction(s) so far; the preferences file learns from them.");
        return r;
    }

    public ObjectNode owned(JsonNode args) throws IOException {
        Patrons.Patron patron = Patrons.Patron.from(args);
        String op = args.path("op").asText("status").strip().toLowerCase(Locale.ROOT);
        ObjectNode r = envelope();
        switch (op) {
            case "import" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library's owner imports purchase records: they are the person's own, and the file is on this machine.");
                Path path = Path.of(reqStr(args, "path"));
                Owned.Summary s = Owned.importPath(store, path, args.path("kind").asText("auto"), args.path("currency").asText(""));
                r.put("file", s.file()); r.put("kind", s.kind()); r.put("messages_read", s.messagesRead()); r.put("orders_found", s.ordersFound());
                r.put("lines_added", s.linesAdded()); r.put("lines_already", s.linesAlready()); r.put("shipments_seen", s.shipmentsSeen());
                r.put("records_begin", s.firstDate()); r.put("records_end", s.lastDate());
                ArrayNode notes = r.putArray("notes"); s.notes().forEach(notes::add);
                String what = switch (s.kind()) { case "mail" -> "Read " + s.messagesRead() + " messages, " + s.ordersFound() + " of them orders"; case "bank" -> "Read " + s.ordersFound() + " statement lines"; default -> "Read " + s.ordersFound() + " order lines"; };
                r.put("summary", what + ": " + s.linesAdded() + " added to what you own" + (s.linesAlready() > 0 ? ", " + s.linesAlready() + " already there" : "")
                        + (s.shipmentsSeen() > 0 ? "; " + s.shipmentsSeen() + " shipment notices seen" : "") + (s.ownMail() > 0 ? "; " + s.ownMail() + " sent to yourself passed over" : "") + "."
                        + (s.firstDate().isEmpty() ? "" : " The records in this file run from " + s.firstDate() + " to " + s.lastDate() + "."));
                r.put("next", s.linesAdded() == 0 && s.ordersFound() == 0 ? "Nothing in it read as a purchase. If it is a mailbox, it must hold the order confirmations themselves; if a statement, a CSV with a date, a description and an amount."
                        : "researchzosho owned find <words> looks something up; researchzosho owned shows what the ledger holds.");
            }
            case "find", "list" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                int limit = Math.max(1, Math.min(500, args.path("limit").asInt(50)));
                List<Owned.Line> found = Owned.find(store, args.path("words").asText(""), args.path("year").asText(""), args.path("shop").asText(""), limit);
                ArrayNode arr = r.putArray("lines");
                for (Owned.Line l : found) arr.add(l.json());
                r.put("count", found.size());
                r.put("summary", found.isEmpty() ? "Nothing in what you own matches." : found.size() + " line(s), newest first" + (found.size() == limit ? " (the first " + limit + "; limit takes more)" : "") + ".");
            }
            case "status" -> {
                Patrons.check(store, patron, Patrons.Level.read);
                ObjectNode st = Owned.status(store);
                r.setAll(st);
                int n = st.path("lines").asInt();
                StringBuilder sb = new StringBuilder(n == 0 ? "Nothing is imported yet." : n + " line(s): " + st.path("purchases").asInt() + " purchases, " + st.path("receipts").asInt() + " receipts for services and " + st.path("payments").asInt() + " statement payments, from " + st.path("sources").size() + " file(s).");
                for (JsonNode s : st.path("sources")) if (!s.path("records_begin").asText().isEmpty()) sb.append(" Records in ").append(Path.of(s.path("file").asText()).getFileName()).append(" begin ").append(s.path("records_begin").asText()).append(" — the file's first record, not when the account was opened.");
                r.put("summary", sb.toString());
                r.put("next", n == 0 ? "researchzosho owned import <file> reads the zip a shop's data request returns, a mailbox, or a statement as CSV." : "researchzosho owned find <words> looks something up.");
            }
            case "recalls" -> {
                boolean check = args.path("check").asBoolean(false); String dismiss = args.path("hit").asText("");
                Patrons.check(store, patron, check || !dismiss.isEmpty() ? Patrons.Level.write : Patrons.Level.read);
                if (!dismiss.isEmpty()) { if (!Recalls.dismiss(store, dismiss)) throw ProtocolError.notFound("No open recall hit has the id " + dismiss + "."); r.put("dismissed", dismiss); }
                if (check) { Recalls.Checked c = Recalls.check(store, true); r.put("feeds", c.feeds()); r.put("notices", c.notices()); r.put("new_hits", c.newHits()); }
                ArrayNode hits = r.putArray("hits");
                List<Recalls.Hit> open = Recalls.hits(store, true);
                for (Recalls.Hit h : open) hits.add(h.json());
                r.put("count", open.size());
                r.put("summary", (check ? "The feeds were read: " + r.path("feeds").asText() + ". " : "") + (open.isEmpty() ? "No recall notice on file concerns anything you own." : open.size() + " recall notice(s) may concern something you own; each says what matched and how surely. researchzosho owned recalls dismiss <id> puts one away."));
            }
            case "forget" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                if (!patron.person() && !(patron.web() && !WebAccess.signInRequired())) throw ProtocolError.forbidden("Only the library's owner changes what it holds about their purchases.");
                String file = reqStr(args, "file");
                int gone = Owned.forget(store, file);
                if (gone == 0 && Owned.sources(store).stream().noneMatch(s -> s.file().equals(file))) throw ProtocolError.notFound("No imported file is named " + file + "; researchzosho owned lists them.");
                r.put("file", file); r.put("removed", gone);
                r.put("summary", gone + " line(s) from " + Path.of(file).getFileName() + " forgotten.");
            }
            default -> throw ProtocolError.invalidArgs("op is import, find, list, status, recalls or forget.");
        }
        return r;
    }
}
