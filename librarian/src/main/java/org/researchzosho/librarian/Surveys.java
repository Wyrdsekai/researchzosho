package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A thing the person already has, read as a starting point for research: a code repository, a paper, a
 * website or product page, an issue tracker. The library reads it, writes one draft claim saying what
 * it is and what it rests on, and offers numbered directions — research questions the shelves and the
 * web can answer. Nothing runs until the person picks a direction or names their own.
 *
 * <p>Each kind has its own reader ({@link Repos} for a repository; the paper, site and issues readers
 * here); the description, the filing, the options and the runs are the same for all of them.
 */
public final class Surveys {

    private static final ObjectMapper M = new ObjectMapper();

    /** Directions offered per survey; characters of a document shown to the model. */
    static final int MAX_OPTIONS = org.researchzosho.Config.getInt("RESEARCHZOSHO_SURVEY_OPTIONS", 8);
    static final int TEXT_CHARS = 30000;
    static final int TAIL_CHARS = 8000;
    static final int ISSUES_PER_PAGE = 100;
    static final int ISSUE_BODY_CHARS = 600;
    static final Duration FETCH = Duration.ofSeconds(60);

    public enum Kind { repo, paper, site, issues, db }

    /** What was read: the kind, a short name, where it came from, the text the model sees (and the shelf keeps), and a few facts about it. */
    public record Read(Kind kind, String name, String origin, String title, String text, Map<String, String> facts) { }

    /** The reading: what it is, what it does or says, what it rests on, the claims it makes, what it leaves open, and the directions offered. */
    public record Description(String whatItIs, String summary, List<String> restsOn, List<String> claims, List<String> leavesOpen, List<String> options, boolean byModel) { }

    /** A direction on the open questions, numbered as the survey offered it. */
    public record Option(int n, String question, Frontier.Line line) { }

    /** What filing wrote: the raw file, the claim id, the directions open now, and the ones already open before. */
    public record Filed(String raw, String claimId, List<Option> options, List<String> alreadyOpen) { }

    private Surveys() { }

    // ---- what kind of thing this is ----

    static final Pattern GITHUB_ISSUES = Pattern.compile("^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/issues(?:[/?#].*)?$");
    static final Pattern GITHUB_REPO = Pattern.compile("^https?://(?:www\\.)?github\\.com/([^/]+)/([^/?#]+?)(?:\\.git)?/?$");
    static final Pattern PAPER_URL = Pattern.compile("(?i)^https?://(?:(?:dx\\.)?doi\\.org/.*|arxiv\\.org/.*|.*\\.pdf(?:[?#].*)?)$");
    static final Pattern DOC_FILE = Pattern.compile("(?i)\\.(pdf|docx|pptx|odt|epub|md|txt|rst|tex|html?)$");

    /** The kind from the thing itself: a folder or a git locator is a repo; a GitHub issues page is issues; a DOI, an arXiv page, a PDF or a document file is a paper; any other url is a site. */
    public static Kind detect(String spec) {
        String s = spec.strip();
        if (s.startsWith("db:")) return Kind.db;
        if (GITHUB_ISSUES.matcher(s).matches()) return Kind.issues;
        if (s.startsWith("git@") || s.endsWith(".git") || GITHUB_REPO.matcher(s).matches()) return Kind.repo;
        if (PAPER_URL.matcher(s).matches()) return Kind.paper;
        if (s.startsWith("http://") || s.startsWith("https://")) return Kind.site;
        Path p = Path.of(s);
        if (Files.isDirectory(p)) return Kind.repo;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json") || lower.endsWith(".jsonl") || lower.endsWith(".mbox")) return Kind.issues;
        return Kind.paper;
    }

    // ---- reading ----

    /** Read the thing by its kind. Throws with a plain message when it cannot be read. */
    public static Read read(LibraryStore store, Kind kind, String spec) throws IOException {
        return switch (kind) {
            case repo -> { Repos.Repo repo = Repos.obtain(store, spec); Repos.Survey s = Repos.survey(repo);
                yield new Read(Kind.repo, repo.name(), repo.origin(), repo.name(), Repos.render(s), Map.of("files", String.valueOf(s.files()), "languages", s.languages(), "cloned", String.valueOf(repo.cloned()), "dir", repo.dir().toString())); }
            case paper -> paper(spec);
            case site -> site(spec);
            case issues -> issues(spec);
            case db -> database(spec);
        };
    }

    static byte[] fetch(String url) throws IOException {
        try {
            var resp = org.researchzosho.tools.Fetch.get(url, FETCH);
            if (resp.status() >= 400) throw new IOException("HTTP " + resp.status());
            return resp.body();
        } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException("could not read " + url + ": " + e.getMessage()); }
    }

    /** A document: the head, and the tail where the references are, when it is long. */
    static String clip(String text) {
        String t = text.strip();
        if (t.length() <= TEXT_CHARS + TAIL_CHARS) return t;
        return t.substring(0, TEXT_CHARS) + "\n\n[… " + (t.length() - TEXT_CHARS - TAIL_CHARS) + " characters left out …]\n\n" + t.substring(t.length() - TAIL_CHARS);
    }

    /** A paper or a document: a file on this machine (PDF, Word, EPUB, text…) or a url (a DOI, an arXiv page, a PDF). */
    static Read paper(String spec) throws IOException {
        byte[] bytes; String origin, hint;
        if (spec.startsWith("http://") || spec.startsWith("https://")) { String u = paperUrl(spec); bytes = fetch(u); origin = spec; hint = u; }
        else {
            Path f = Path.of(spec).toAbsolutePath().normalize();
            if (!Files.isRegularFile(f)) throw new IOException(f + " is not a file");
            bytes = Files.readAllBytes(f); origin = f.toString(); hint = f.getFileName().toString();
        }
        org.researchzosho.tools.DocText.Doc doc = org.researchzosho.tools.DocText.convert(bytes, hint);
        if (doc.text() == null || doc.text().isBlank()) throw new IOException("no text could be read from " + origin);
        String title = doc.title() == null || doc.title().isBlank() ? firstLine(doc.text(), stem(hint)) : doc.title().strip();
        String name = Repos.nameOf(spec.startsWith("http") ? title : stem(hint));
        if (name.length() > 60) name = name.substring(0, 60).replaceAll("-+$", "");
        String text = "# " + title + "\n\nOrigin: " + origin + "\n\n" + clip(doc.text());
        return new Read(Kind.paper, name, origin, title, text, Map.of("characters", String.valueOf(doc.text().length()), "format", doc.kind() == null ? "" : doc.kind()));
    }

    /** A website or a product page: one url, read as a person would read the page. */
    static Read site(String spec) throws IOException {
        if (!(spec.startsWith("http://") || spec.startsWith("https://"))) throw new IOException("a site is a url: " + spec);
        byte[] bytes = fetch(spec);
        org.researchzosho.tools.DocText.Doc doc = org.researchzosho.tools.DocText.convert(bytes, spec);
        if (doc.text() == null || doc.text().isBlank()) throw new IOException("no text could be read from " + spec);
        String title = doc.title() == null || doc.title().isBlank() ? spec : doc.title().strip();
        String host = spec.replaceFirst("^https?://(www\\.)?", "").replaceAll("[/?#].*$", "");
        String tail = spec.replaceFirst("^https?://[^/]+", "").replaceAll("[?#].*$", "").replaceAll("/+$", "");
        String name = (host + (tail.isEmpty() ? "" : "-" + tail.substring(tail.lastIndexOf('/') + 1))).replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        String text = "# " + title + "\n\nOrigin: " + spec + "\n\n" + clip(doc.text());
        return new Read(Kind.site, name, spec, title, text, Map.of("characters", String.valueOf(doc.text().length())));
    }

    /** An issue tracker: a GitHub issues page (read through the API, the newest hundred), or an export file (a JSON list of issues, or plain text). */
    static Read issues(String spec) throws IOException {
        Matcher gh = GITHUB_ISSUES.matcher(spec.strip());
        if (gh.matches()) {
            String owner = gh.group(1), repo = gh.group(2);
            String api = "https://api.github.com/repos/" + owner + "/" + repo + "/issues?state=all&per_page=" + ISSUES_PER_PAGE + "&sort=updated";
            JsonNode list = M.readTree(fetch(api));
            if (!list.isArray()) throw new IOException("GitHub did not answer with a list of issues for " + owner + "/" + repo + (list.has("message") ? ": " + list.path("message").asText() : ""));
            return issuesFrom(list, owner + "/" + repo, spec.strip(), Repos.nameOf(owner + "-" + repo + "-issues"));
        }
        Path f = Path.of(spec).toAbsolutePath().normalize();
        if (!Files.isRegularFile(f)) throw new IOException(f + " is not a file (an issues export), and not a GitHub issues url");
        String raw = Files.readString(f, StandardCharsets.UTF_8);
        String head = raw.stripLeading();
        if (head.startsWith("[") || head.startsWith("{")) {
            try {
                JsonNode j = M.readTree(raw);
                JsonNode list = j.isArray() ? j : j.path("issues").isArray() ? j.get("issues") : j.path("items").isArray() ? j.get("items") : null;
                if (list != null) return issuesFrom(list, stem(f.getFileName().toString()), f.toString(), Repos.nameOf(stem(f.getFileName().toString())));
            } catch (IOException ignored) { }
        }
        String name = Repos.nameOf(stem(f.getFileName().toString()));
        return new Read(Kind.issues, name, f.toString(), name, "# Issues: " + name + "\n\nOrigin: " + f + "\n\n" + clip(raw), Map.of("characters", String.valueOf(raw.length())));
    }

    static Read issuesFrom(JsonNode list, String label, String origin, String name) {
        int open = 0, closed = 0, prs = 0;
        StringBuilder sb = new StringBuilder();
        for (JsonNode i : list) {
            if (i.has("pull_request")) { prs++; continue; }
            String state = i.path("state").asText("open");
            if (state.equals("open")) open++; else closed++;
            sb.append("## #").append(i.path("number").asText("?")).append(' ').append(i.path("title").asText("").strip())
              .append("  [").append(state).append(", ").append(i.path("comments").asInt(0)).append(" comment(s)");
            List<String> labels = new ArrayList<>(); for (JsonNode l : i.path("labels")) labels.add(l.isTextual() ? l.asText() : l.path("name").asText());
            if (!labels.isEmpty()) sb.append("; ").append(String.join(", ", labels));
            sb.append("]\n\n");
            String body = i.path("body").isTextual() ? i.get("body").asText().strip().replaceAll("\\s+", " ") : "";
            if (!body.isEmpty()) sb.append(Acquisitions.compress(body, ISSUE_BODY_CHARS)).append("\n\n");
        }
        String text = "# Issues: " + label + "\n\nOrigin: " + origin + "\nIssues read: " + (open + closed) + " (" + open + " open, " + closed + " closed" + (prs > 0 ? "; " + prs + " pull request(s) left out" : "") + ")\n\n" + clip(sb.toString());
        return new Read(Kind.issues, name, origin, "Issues: " + label, text, Map.of("issues", String.valueOf(open + closed), "open", String.valueOf(open), "closed", String.valueOf(closed)));
    }

    /** A database the owner added: its schema is what is read. {@code spec} is "db:<name>". */
    static Read database(String spec) throws IOException {
        String name = spec.replaceFirst("^db:", "").strip();
        Databases.Db d = Databases.get(name);
        if (d == null) throw new IOException("No database is named " + name + ". Add it first: researchzosho db add " + name + " <address>");
        String schema = Databases.schema(d);
        long tables = schema.lines().filter(l -> l.startsWith("## ")).count();
        return new Read(Kind.db, d.name(), "db://" + d.name(), "Database: " + d.name(), "# Database: " + d.name() + "\n\nOrigin: db://" + d.name() + "\n\n" + clip(schema), Map.of("tables", String.valueOf(tables), "engine", DbDrivers.kind(d.kind()).label()));
    }

    static String stem(String fileName) { int i = fileName.lastIndexOf('.'); return i > 0 ? fileName.substring(0, i) : fileName; }

    static String firstLine(String text, String fallback) {
        for (String line : text.split("\n")) { String l = line.strip().replaceAll("^#+\\s*", ""); if (l.length() >= 8 && l.length() <= 200) return l; }
        return fallback;
    }

    // ---- describing ----

    /** What the model is asked for, by kind: the headings are fixed; what each means is said for the kind. */
    static String instructions(Kind kind) {
        String common = "Write exactly these sections, in this order, each heading on its own line, nothing before the first heading:\n";
        String directions = "DIRECTIONS\nOne research question per line, no numbering, up to " + MAX_OPTIONS + " lines. Each is a question the library could answer with sources. Name the thing's own terms in each. Ask; do not answer.";
        return switch (kind) {
            case repo -> "You read a software repository and report on it for a research library. " + common
                    + "WHAT IT IS\nOne or two plain sentences: the kind of thing this is (a library, a service, a tool, an experiment) and who it is for.\n"
                    + "WHAT IT DOES\nOne short paragraph: what it does, in the order it does it, in the words its own README uses.\n"
                    + "RESTS ON\nOne item per line, no bullets: the techniques, algorithms, standards, data formats and notable libraries the code depends on, named as a paper or a reference would name them. Up to 12 lines.\n"
                    + directions + " Good directions: the published basis of a technique it uses; what else solves the same problem and how it differs; whether an assumption the design makes holds in the literature; what its licence or a standard it implements requires.";
            case paper -> "You read a paper or a document and report on it for a research library. " + common
                    + "WHAT IT IS\nOne or two plain sentences: what kind of work this is, its field, and who wrote it if the text says.\n"
                    + "WHAT IT SAYS\nOne short paragraph: the argument, in the order the text makes it.\n"
                    + "CLAIMS\nOne per line, no bullets: the central claims, each a short self-contained sentence with its number, date or name when the text gives one. Up to 8 lines.\n"
                    + "RESTS ON\nOne per line: the methods, data, and earlier works it builds on, named as the text names them. Up to 12 lines.\n"
                    + "LEAVES OPEN\nOne per line: what the text itself says is unsettled, untested, or for future work. Up to 6 lines; write NONE if it says nothing.\n"
                    + directions + " Good directions: whether a central claim holds against other sources; what the works it cites on a point actually say; a question it leaves open; what has been published on the same question since.";
            case site -> "You read a website or a product page and report on it for a research library. " + common
                    + "WHAT IT IS\nOne or two plain sentences: what this is (a company, a product, a tool, a standard, an organisation) and who it is for.\n"
                    + "WHAT IT DOES\nOne short paragraph: what it does or offers, in the page's own words.\n"
                    + "CLAIMS\nOne per line, no bullets: the definite claims the page makes — a number, a comparison, a capability, a result. Up to 8 lines; write NONE if it makes none.\n"
                    + "RESTS ON\nOne per line: the techniques, standards, materials, or other products it says it is built on. Up to 12 lines.\n"
                    + directions + " Good directions: whether a claim the page makes is backed by independent sources; what else does the same thing and how it compares; what a standard it names actually requires; who is behind it and what they have published.";
            case db -> "You read the schema of a database and report on it for a research library. " + common
                    + "WHAT IT IS\nOne or two plain sentences: what this database records and for what kind of work, judged from its tables, columns and sample rows.\n"
                    + "WHAT IT SAYS\nOne short paragraph: the main tables, what one row of each is, and how the tables relate.\n"
                    + "RESTS ON\nOne per line: the tables that hold the most, with their row counts. Up to 12 lines.\n"
                    + "LEAVES OPEN\nOne per line: what the schema does not make clear — a column whose meaning is not evident, a table with no rows, values that are hidden. Up to 6 lines; NONE if nothing.\n"
                    + directions + " Good directions: a count or a trend the data can give (name the table and the column); a comparison between groups in the data; how the data compares with a published figure; whether a pattern in the data matches what the literature reports. Each must be answerable by querying this database, alone or with sources.";
            case issues -> "You read an issue tracker or a discussion and report on it for a research library. " + common
                    + "WHAT IT IS\nOne or two plain sentences: what the project or the discussion is about, and who takes part.\n"
                    + "WHAT IT SAYS\nOne short paragraph: what people bring here, in order of how often it comes up.\n"
                    + "CLAIMS\nOne per line, no bullets: definite claims people make without citing a source — about causes, numbers, other software, what works. Up to 8 lines; NONE if there are none.\n"
                    + "RESTS ON\nOne per line: the techniques, components, standards and other projects the discussion turns on. Up to 12 lines.\n"
                    + "LEAVES OPEN\nOne per line: the questions that recur and are not answered in the thread. Up to 6 lines; NONE if none.\n"
                    + directions + " Good directions: whether a claim made without a source holds; what is known about a recurring problem; what other projects do about the same thing; what a standard or a specification people argue about actually says.";
        };
    }

    /** The reading by the model when there is one; mechanically from the text otherwise. */
    public static Description describe(Read r, Researcher.Drive drive) {
        if (drive != null) {
            try {
                ArrayNode messages = M.createArrayNode();
                messages.addObject().put("role", "system").put("content", instructions(r.kind()));
                messages.addObject().put("role", "user").put("content", Fence.open("TEXT") + "\n" + Acquisitions.compress(r.text(), TEXT_CHARS + TAIL_CHARS) + "\n" + Fence.close("TEXT"));
                String reply = drive.classify(messages, 1600);
                Description d = parse(reply);
                if (d != null) return d;
            } catch (Exception ignored) { }
        }
        return mechanical(r);
    }

    /** A bullet or a list number off the front of a line — "1. " or "- " — and nothing else: "10,000 businesses" keeps its ten thousand. */
    static String unnumbered(String line) { return line.strip().replaceAll("^(?:[-*•]+\\s*|\\d{1,2}[.)]\\s+)", "").strip(); }

    /** An arXiv abstract page is read as its PDF: the page holds the abstract alone. */
    static String paperUrl(String url) {
        Matcher m = Pattern.compile("^(https?://arxiv\\.org/)abs/([^?#]+)").matcher(url.strip());
        return m.find() ? m.group(1) + "pdf/" + m.group(2) : url.strip();
    }

    static final List<String> HEADINGS = List.of("WHAT IT IS", "WHAT IT DOES", "WHAT IT SAYS", "CLAIMS", "RESTS ON", "LEAVES OPEN", "DIRECTIONS");

    static Description parse(String reply) {
        if (reply == null || reply.isBlank()) return null;
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String current = null;
        for (String raw : reply.split("\n")) {
            String line = raw.strip().replaceAll("^#+\\s*", "").replaceAll("[*_]+", "");
            String up = line.toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]", "").strip();
            if (HEADINGS.contains(up)) { current = up; sections.put(current, new ArrayList<>()); continue; }
            if (current != null && !line.isBlank()) {
                String item = unnumbered(line);
                if (!item.equalsIgnoreCase("NONE")) sections.get(current).add(item);
            }
        }
        if (!sections.containsKey("WHAT IT IS") || !sections.containsKey("DIRECTIONS")) return null;
        List<String> options = new ArrayList<>();
        for (String q : sections.get("DIRECTIONS")) { if (q.length() >= 15 && !options.contains(q)) options.add(q.endsWith("?") || q.endsWith(".") ? q : q + "?"); if (options.size() >= MAX_OPTIONS) break; }
        if (options.isEmpty()) return null;
        List<String> summary = sections.containsKey("WHAT IT DOES") ? sections.get("WHAT IT DOES") : sections.getOrDefault("WHAT IT SAYS", List.of());
        return new Description(String.join(" ", sections.get("WHAT IT IS")), String.join(" ", summary),
                sections.getOrDefault("RESTS ON", List.of()).stream().limit(12).toList(),
                sections.getOrDefault("CLAIMS", List.of()).stream().limit(8).toList(),
                sections.getOrDefault("LEAVES OPEN", List.of()).stream().limit(6).toList(), options, true);
    }

    /** Without a model: the first real paragraph, what the text names as dependencies or references, and one question per kind's standing concerns. */
    static Description mechanical(Read r) {
        String first = "";
        for (String para : r.text().split("\\n\\s*\\n")) {
            String p = para.strip().replaceAll("^#+.*\\n?", "").replaceAll("\\s+", " ").strip();
            if (p.length() >= 40 && !p.startsWith("[![") && !p.startsWith("<") && !p.startsWith("Origin:") && !p.startsWith("```") && !p.startsWith("- ") && !p.startsWith("## ")) { first = p; break; }
        }
        String name = r.name();
        String whatItIs = first.isEmpty() ? r.title() : Acquisitions.compress(first, 400);
        List<String> restsOn = r.kind() == Kind.repo ? Repos.dependencies(r.text()) : List.of();
        List<String> options = new ArrayList<>();
        switch (r.kind()) {
            case repo -> {
                options.add("What problem does " + name + " solve, and what else solves it?");
                for (String d : restsOn) { if (options.size() >= MAX_OPTIONS) break; options.add("What is " + d + ", which " + name + " depends on, and what is its published basis?"); }
                if (options.size() < MAX_OPTIONS) options.add("What does the literature say about the approach " + name + " takes, and where has it been measured?");
            }
            case paper -> {
                options.add("Do the central claims of \"" + Acquisitions.compress(r.title(), 80) + "\" hold against other sources?");
                options.add("What do the works cited by \"" + Acquisitions.compress(r.title(), 80) + "\" say on its main point?");
                options.add("What has been published on the question of \"" + Acquisitions.compress(r.title(), 80) + "\" since it appeared?");
            }
            case site -> {
                options.add("Are the claims made at " + r.origin() + " backed by independent sources?");
                options.add("What else does what " + Acquisitions.compress(r.title(), 60) + " does, and how does it compare?");
                options.add("Who is behind " + Acquisitions.compress(r.title(), 60) + ", and what have they published?");
            }
            case db -> {
                options.add("What does the database " + name + " record, and how much of it is there in each table?");
                options.add("What changed over time in the data of " + name + "?");
                options.add("How do the figures in " + name + " compare with published figures for the same thing?");
            }
            case issues -> {
                options.add("Which problems recur in " + r.title() + ", and what is known about their causes?");
                options.add("Which claims made in " + r.title() + " without a source hold up?");
                options.add("What do other projects do about the problems raised in " + r.title() + "?");
            }
        }
        return new Description(whatItIs, "", restsOn, List.of(), List.of(), options, false);
    }

    // ---- filing ----

    static String collection(Kind k) { return switch (k) { case repo -> "repos"; case paper -> "papers"; case site -> "sites"; case issues -> "issues"; case db -> "databases"; }; }

    /** The marker in a frontier line that ties a direction to a survey; the number is the option as offered. */
    static String marker(String name) { return "(from a survey of " + name; }

    static String kind(String name, String who, int n) { return "person " + who + " " + marker(name) + (n > 0 ? ", option " + n + ")" : ", own)"); }

    /** The text onto the shelves, one draft claim, and the directions onto the open questions. */
    public static Filed file(LibraryStore store, Read r, Description d, String who) throws IOException {
        String locator = r.kind() + "://" + r.name() + "/" + Conversations.hash8(r.text());
        Path raw = RawCapture.capture(store, locator, r.text(), r.title(), r.kind() + ":" + Acquisitions.compress(r.origin(), 120), collection(r.kind()));
        String title = Acquisitions.compress(r.name() + ": " + firstSentence(d.whatItIs()), 110);
        String id = store.nextFindingId(title);
        StringBuilder text = new StringBuilder();
        text.append(d.whatItIs().strip()).append('\n');
        if (!d.summary().isBlank()) text.append('\n').append(d.summary().strip()).append('\n');
        if (!d.claims().isEmpty()) { text.append("\nIt claims:\n"); for (String c : d.claims()) text.append("- ").append(c).append('\n'); }
        if (!d.restsOn().isEmpty()) { text.append("\nIt rests on:\n"); for (String x : d.restsOn()) text.append("- ").append(x).append('\n'); }
        if (!d.leavesOpen().isEmpty()) { text.append("\nIt leaves open:\n"); for (String x : d.leavesOpen()) text.append("- ").append(x).append('\n'); }
        String today = LocalDate.now().toString();
        StringBuilder facts = new StringBuilder();
        for (var e : r.facts().entrySet()) if (!e.getValue().isBlank()) facts.append(facts.length() == 0 ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
        Finding claim = new Finding(id, title, List.of(), Finding.State.draft, Finding.ClaimType.interpretation, Finding.Confidence.medium, who, Instant.now().toString(), today,
                Finding.Volatility.stable, "", List.of(new Finding.Source(r.origin(), today, "the " + noun(r.kind()) + " itself, read " + today)), List.of(), null, text.toString(), null,
                List.of(new Finding.Note("survey", who, today, "kind=" + r.kind() + "; read by the library from the " + noun(r.kind()) + (facts.length() == 0 ? "" : " (" + facts + ")") + "; " + (d.byModel() ? "described by the model" : "described mechanically, no model") + "; the text is " + locator)));
        store.write(claim);
        List<String> already = new ArrayList<>();
        List<Frontier.Line> open = Frontier.read(store);
        int n = 0;
        for (String q : d.options()) {
            n++;
            boolean have = false;
            for (Frontier.Line l : open) if (l.open() && (Frontier.sameQuestion(l.text(), q) || Frontier.jaccard(Frontier.terms(q), Frontier.terms(l.text())) >= 0.6)) { have = true; break; }
            if (have) { already.add(q); continue; }
            store.frontier(kind(r.name(), who, n), q);
        }
        List<Option> options = options(store, r.name());
        store.circulate("survey", who + " :: " + r.kind() + " " + r.name() + " — 1 draft claim, " + options.size() + " direction(s) offered");
        return new Filed(raw == null ? "" : raw.getFileName().toString(), id, options, already);
    }

    static String noun(Kind k) { return switch (k) { case repo -> "repository"; case paper -> "document"; case site -> "page"; case issues -> "issue tracker"; case db -> "database"; }; }

    static String firstSentence(String s) {
        String t = s.strip();
        int i = t.indexOf(". ");
        return i > 20 ? t.substring(0, i + 1) : t;
    }

    /** The directions offered for a survey, in the order offered, that are still open. */
    public static List<Option> options(LibraryStore store, String name) throws IOException {
        List<Option> out = new ArrayList<>();
        Pattern p = Pattern.compile(Pattern.quote(marker(name)) + ", option (\\d+)\\)");
        for (Frontier.Line l : Frontier.read(store)) {
            if (!l.open()) continue;
            Matcher m = p.matcher(l.kind());
            if (m.find()) out.add(new Option(Integer.parseInt(m.group(1)), l.text(), l));
        }
        out.sort((a, b) -> a.n() - b.n());
        return out;
    }

    /** The claim a survey filed for a name, the newest, or null. */
    public static Finding claimFor(LibraryStore store, String name) throws IOException {
        Finding best = null;
        for (Finding f : store.scanFindings().findings()) {
            if (!f.title().startsWith(name + ": ")) continue;
            boolean ours = f.notes() != null && f.notes().stream().anyMatch(x -> "survey".equals(x.kind()));
            if (ours && (best == null || f.recordedAt().compareTo(best.recordedAt()) > 0)) best = f;
        }
        return best;
    }

    /** The kind a claim's survey note recorded, or repo when it does not say. */
    public static Kind kindOf(Finding claim) {
        for (Finding.Note n : claim.notes()) if ("survey".equals(n.kind())) { Matcher m = Pattern.compile("kind=(\\w+)").matcher(n.text()); if (m.find()) try { return Kind.valueOf(m.group(1)); } catch (IllegalArgumentException ignored) { } }
        return Kind.repo;
    }

    /** The question a run gets for a direction: the thing named, so the shelved text and the claim are found, then the question. */
    public static String runQuestion(Kind kind, String name, String origin, String question) {
        return "About the " + noun(kind) + " " + name + " (" + origin + "), whose text and description are on the shelves: " + question.strip();
    }
}
