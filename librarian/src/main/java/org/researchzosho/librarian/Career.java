package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.tools.DocText;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The career field's record of a person looking for work (docs/DESIGN_CAREER.md, step 1): what they hand over, read into numbered
 * facts with their source; the library's reading of their working life, every line tied to those facts; a draft of what they want,
 * one topic at a time, each decided, a guess or open; and a log of every change. Kept under {@code career/<person>/}. Nothing here
 * leaves the library: the reading and the draft are written by the library's own model.
 */
public final class Career {
    private Career() { }

    private static final ObjectMapper M = new ObjectMapper();
    /** The library's model; a test puts a stand-in here. */
    static Supplier<Researcher.Drive> DRIVE = Explain::drive;

    /** The topics of what a person wants, as the commands name them. */
    public static final List<String> DIMENSIONS = List.of("domain", "kind of work", "role", "kind of employer", "company size", "company stage", "culture", "place", "mobility", "remote", "arrangement", "right to work", "pace", "pay", "ownership");
    /**
     * The least the library needs to map a person before it can look for employers: what work and at what level, for what kind of
     * employer, where, whether they would move, remote or not, what arrangement, and whether a licence or a visa stands in the way. A
     * topic of these left open after the reading is asked first in the interview, in one plain question.
     */
    public static final List<String> MINIMUM = List.of("role", "kind of employer", "place", "mobility", "remote", "arrangement", "right to work");
    /** Each topic as a person reads it. */
    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("domain", "the area you work in"), Map.entry("kind of work", "the kind of work"), Map.entry("role", "your role"),
            Map.entry("kind of employer", "the kind of employer"),
            Map.entry("company size", "the size of the company"), Map.entry("company stage", "how young or established the company is"),
            Map.entry("culture", "the culture of the place"),
            Map.entry("place", "where you work"), Map.entry("mobility", "whether you would move or commute"), Map.entry("remote", "working remotely"),
            Map.entry("arrangement", "full-time, part-time, contract or temporary"), Map.entry("right to work", "a licence or a visa to sort out"),
            Map.entry("pace", "the pace of the work"),
            Map.entry("pay", "the pay"), Map.entry("ownership", "being an employee, a founder or an adviser"));
    /** The three states of a topic, as kept: decided (the person said it), leaning (a guess from their history), open. */
    public static final List<String> STATES = List.of("decided", "leaning", "open");
    /** Each state as a person reads it. */
    static final Map<String, String> STATE_WORDS = Map.of("decided", "decided", "leaning", "a guess", "open", "open");
    /** The kinds of change the reading keeps in front of the model, so none is skipped. */
    static final List<String> CHANGES = List.of(
            "no change: the same work somewhere better", "domain", "role: maker to manager, or back", "company size or stage", "place, or remote",
            "pace", "pay", "ownership: employee, founder, consultant", "out of the industry");
    /**
     * What may drive a person's choices: Schein's eight career anchors in everyday words, with teaching and invention added. The reading
     * works through each, so none the evidence suggests is left out.
     */
    static final List<String> DRIVES = List.of(
            "building something new of your own", "mastery of a craft", "leading people and running an organization", "freedom to work your own way",
            "security and stability", "a cause or a mission", "hard problems for their own sake", "a life outside work", "teaching and explaining", "invention");
    /** The kinds of workplace, after the four culture types of Cameron and Quinn's Competing Values Framework. */
    static final List<String> WORKPLACES = List.of(
            "one built around people and teamwork", "one built around new ideas, speed and risk", "one built around results and competition", "one built around process and stability");
    /** The reading's sections that ask, or that say what is missing, rather than tell: the library's marks leave them alone. */
    static final List<String> UNTOLD = List.of("Questions for you", "What the facts do not say", "Possible wants", "Gaps and unknowns");

    /** A document is read in pieces of about this many characters, so the reading can point at the piece a line rests on. */
    static final int PIECE = 500;
    /** At most this much of one document is read: a resume is a few pages. */
    static final int DOC_CHARS = 24_000, DOC_PIECES = 120;
    private static final Pattern FACT_ID = Pattern.compile("E(\\d+)");
    /** What a topic's basis cites: facts (E3) and the library's counts (C2). */
    private static final Pattern CITE_ID = Pattern.compile("[EC]\\d+");

    public record Seeker(String slug, String name) {
        public Path dir(LibraryStore store) { return Career.dir(store).resolve(slug); }
    }

    /** What one hand-over did: whose record it went to, how many facts it added, where they came from, and whether a long document was cut. */
    public record Provided(Seeker seeker, int added, String source, String kind, boolean shortened, boolean again) { }

    public static Path dir(LibraryStore store) { return store.root().resolve("career"); }

    /** The people this library holds a record for, by the folders with one. */
    public static List<Seeker> seekers(LibraryStore store) throws IOException {
        List<Seeker> out = new ArrayList<>();
        if (!Files.isDirectory(dir(store))) return out;
        try (Stream<Path> s = Files.list(dir(store))) {
            for (Path p : s.sorted().toList()) {
                Path rec = p.resolve("record.json");
                if (Files.isRegularFile(rec)) out.add(new Seeker(p.getFileName().toString(), M.readTree(rec.toFile()).path("name").asText(p.getFileName().toString())));
            }
        }
        return out;
    }

    /** The person named, by name or by the folder's name; with no name, the only one there is; null when there is none. */
    public static Seeker seeker(LibraryStore store, String name) throws IOException {
        List<Seeker> all = seekers(store);
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return all.size() == 1 ? all.get(0) : null;
        for (Seeker s : all) if (s.name().equalsIgnoreCase(n) || s.slug().equals(LibraryStore.slug(n))) return s;
        return null;
    }

    /** A state as a person types it: decided, leaning or guess, open; "" when it is none of them. */
    public static String state(String typed) {
        String t = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
        if (t.equals("guess") || t.equals("a guess")) return "leaning";
        return STATES.contains(t) ? t : "";
    }

    public static String label(String dimension) { return LABELS.getOrDefault(dimension, dimension); }

    /**
     * What a person hands over: a file (a LinkedIn profile saved as JSON, or a resume as PDF, Word or text), a web address, or words
     * about what they want. {@code mayReadFiles}: whether a path may be read at all, which is only for the library's owner on this
     * machine; for anyone else the disk is never looked at and the words are kept as words. A file is kept whole under {@code provided/}
     * and read into numbered facts; the same file handed over again replaces its own facts. A web address is kept as an address. Words
     * are kept as said, dated. {@code name} names the person; blank takes the name in a LinkedIn file, else the one person this library
     * holds, else "me".
     */
    public static Provided provide(LibraryStore store, String what, String name, boolean mayReadFiles) throws IOException {
        String w = what == null ? "" : what.strip();
        if (w.isEmpty()) throw new IllegalArgumentException("Hand over a file, a web address, or some words about what you want.");
        Path file = null;
        if (mayReadFiles) {
            try { Path p = Path.of(w.startsWith("~/") ? System.getProperty("user.home") + w.substring(1) : w); if (Files.isRegularFile(p)) file = p; }
            catch (RuntimeException ignored) { }
        }
        boolean link = file == null && w.matches("https?://\\S+");
        byte[] bytes = file == null ? null : Files.readAllBytes(file);
        JsonNode export = null;
        if (bytes != null && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            try { JsonNode j = M.readTree(bytes); if (j.has("experiences") || j.has("full_name")) export = j; } catch (IOException ignored) { }
        }
        // the facts first, so a file the library cannot read leaves nothing behind
        List<ObjectNode> fresh = new ArrayList<>();
        String source, kind; boolean shortened = false;
        if (export != null) { kind = "linkedin"; source = "the LinkedIn file " + file.getFileName(); fresh.addAll(linkedin(export, source)); }
        else if (bytes != null) {
            DocText.Doc doc = DocText.convert(bytes, file.getFileName().toString());
            String text = doc == null || doc.text() == null ? "" : doc.text().strip();
            if (text.isEmpty()) throw new IllegalArgumentException("There is nothing the library can read in " + file.getFileName() + ". A PDF, a Word file or a plain text file works.");
            kind = "document"; source = "the document " + file.getFileName();
            Pieces p = pieces(text);
            shortened = p.shortened();
            for (String piece : p.list()) fresh.add(item("document", source, piece));
        } else if (link) {
            kind = "link"; source = "the page " + w;
            fresh.add(item("link", source, "A page you gave: " + w + ". The library keeps the address but does not read the page yet."));
        } else { kind = "told"; source = "what you said on " + LocalDate.now(); fresh.add(item("told", source, w)); }

        String who = name == null ? "" : name.strip();
        if (who.isEmpty() && export != null) who = export.path("full_name").asText("").strip();
        if (who.isEmpty()) { List<Seeker> all = seekers(store); who = all.size() == 1 ? all.get(0).name() : "me"; }
        Seeker s = new Seeker(LibraryStore.slug(who), who);
        Path d = s.dir(store); Files.createDirectories(d.resolve("provided"));
        Path rec = d.resolve("record.json");
        ObjectNode record = Files.isRegularFile(rec) ? (ObjectNode) M.readTree(rec.toFile()) : M.createObjectNode().put("name", who).put("slug", s.slug()).put("created", LocalDate.now().toString());
        if (export != null && !export.path("location_str").asText("").isBlank()) record.put("location", export.path("location_str").asText().strip());

        ArrayNode items = evidence(store, s);
        int next = maxId(items) + 1;   // counted before anything is taken out: a number once given is never given again, so an old reading never points at a new fact
        boolean again = false;
        if (file != null || link) {   // the same file changed replaces its own facts; the same file unchanged, or the same address, adds nothing
            List<JsonNode> before = new ArrayList<>(), now = new ArrayList<>(fresh);   // whole facts, not only their words: read with more, they are not the same
            ArrayNode keep = M.createArrayNode();
            for (JsonNode it : items) {
                if (!it.path("source").asText().equals(source)) { keep.add(it); continue; }
                ObjectNode c = ((ObjectNode) it).deepCopy(); c.remove("id"); before.add(c);
            }
            again = !before.isEmpty();
            if (again && (link || before.equals(now))) return new Provided(s, 0, source, kind, shortened, true);
            items = keep;
        }
        for (ObjectNode it : fresh) { it.put("id", "E" + next++); items.add(it); }
        if (file != null) Files.copy(file, d.resolve("provided").resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        if (kind.equals("told")) Files.writeString(d.resolve("told.md"), "## " + LocalDate.now() + "\n\n" + w + "\n\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        ArrayNode sources = record.withArray("sources");
        boolean listed = false; for (JsonNode x : sources) if (x.asText().equals(source)) listed = true;
        if (!listed) sources.add(source);
        M.writerWithDefaultPrettyPrinter().writeValue(rec.toFile(), record);
        M.writerWithDefaultPrettyPrinter().writeValue(d.resolve("evidence.json").toFile(), items);
        String facts = fresh.size() + (fresh.size() == 1 ? " fact" : " facts");
        switch (kind) {
            case "told" -> log(store, s, "You said what you want, in your own words.");
            case "link" -> log(store, s, "You gave " + source + ". The library keeps the address.");
            default -> log(store, s, "You handed over " + source + (again ? " again" : "") + ". The library read " + facts + " from it"
                    + (again ? ", in place of the ones it read before." : ".") + (shortened ? " The document was long, so only its first part was read." : ""));
        }
        return new Provided(s, fresh.size(), source, kind, shortened, again);
    }

    /** What a hand-over did, as a sentence for the person. */
    public static String providedText(LibraryStore store, Provided p) throws IOException {
        int total = evidence(store, p.seeker()).size();
        String address = p.source().startsWith("the page ") ? p.source().substring("the page ".length()) : p.source();
        return switch (p.kind()) {
            case "told" -> "The library kept what you said, dated today.";
            case "link" -> p.added() == 0 ? "The library already had the address " + address + "." : "The library keeps the address " + address + ", but does not read pages yet.";
            default -> p.added() == 0 ? "The library already had " + p.source() + ", unchanged."
                    : "The library read " + p.added() + (p.added() == 1 ? " fact" : " facts") + " from " + p.source() + (p.again() ? ", in place of the ones it read from it before," : "") + " and now holds " + total + (total == 1 ? " fact" : " facts")
                    + " about " + p.seeker().name() + "'s work." + (p.shortened() ? " The document was long, so only its first part was read." : "");
        };
    }

    record Pieces(List<String> list, boolean shortened) { }

    /** A document's text in pieces the reading can point at: paragraphs, or runs of lines up to about {@link #PIECE} characters; a short heading stays with what follows it. */
    static Pieces pieces(String text) {
        List<String> all = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) { if (cur.length() >= 40) flush(all, cur); continue; }
            if (line.length() > PIECE * 2) line = line.substring(0, PIECE * 2) + " …";
            if (cur.length() > 0 && cur.length() + 1 + line.length() > PIECE) flush(all, cur);
            if (cur.length() > 0) cur.append(' ');
            cur.append(line);
        }
        flush(all, cur);
        List<String> kept = new ArrayList<>(); int total = 0;
        for (String p : all) {
            if (kept.size() >= DOC_PIECES || total + p.length() > DOC_CHARS) return new Pieces(kept, true);
            kept.add(p); total += p.length();
        }
        return new Pieces(kept, false);
    }

    private static void flush(List<String> out, StringBuilder cur) { if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); } }

    static int maxId(JsonNode items) {
        int max = 0;
        for (JsonNode it : items) { Matcher m = FACT_ID.matcher(it.path("id").asText("")); if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1))); }
        return max;
    }

    /** The facts, in the order they were handed over; empty when none. */
    public static ArrayNode evidence(LibraryStore store, Seeker s) throws IOException {
        Path f = s.dir(store).resolve("evidence.json");
        if (!Files.isRegularFile(f)) return M.createArrayNode();
        JsonNode j = M.readTree(f.toFile());
        return j.isArray() ? (ArrayNode) j : M.createArrayNode();
    }

    /** Where the facts came from, each once, in order: "the LinkedIn file x.json", "the document resume.pdf", "what you said on …". */
    public static List<String> sources(JsonNode items) {
        List<String> out = new ArrayList<>();
        for (JsonNode it : items) { String src = it.path("source").asText(); if (!src.isBlank() && !out.contains(src)) out.add(src); }
        return out;
    }

    /** A LinkedIn profile saved as JSON (the shape with experiences, education, skills and accomplishments), read into facts. */
    static List<ObjectNode> linkedin(JsonNode d, String source) {
        List<ObjectNode> out = new ArrayList<>();
        String head = d.path("headline").asText("").strip();
        String where = d.path("location_str").asText(d.path("city").asText("")).strip();
        if (!head.isEmpty() || !where.isEmpty()) out.add(item("headline", source, join(head, where.isEmpty() ? "" : "based in " + where)));
        if (!d.path("summary").asText("").isBlank()) out.add(item("summary", source, d.path("summary").asText()));
        for (JsonNode e : d.path("experiences")) {
            String span = when(e.path("starts_at")) + " to " + (e.path("ends_at").isNull() || e.path("ends_at").isMissingNode() ? "now" : when(e.path("ends_at")));
            String text = e.path("title").asText("") + " at " + e.path("company").asText("") + ", " + span + (e.path("location").asText("").isBlank() ? "" : ", " + e.path("location").asText())
                    + (e.path("description").asText("").isBlank() ? "" : ". " + e.path("description").asText().strip());
            out.add(spanned(item("role", source, text), e));
        }
        for (JsonNode e : d.path("education")) {
            String text = join(e.path("degree_name").asText(""), e.path("field_of_study").asText("")) + " at " + e.path("school").asText("") + ", " + when(e.path("starts_at")) + " to " + when(e.path("ends_at"))
                    + (e.path("description").asText("").isBlank() ? "" : ". " + e.path("description").asText().strip());
            out.add(spanned(item("education", source, text), e));
        }
        List<String> skills = new ArrayList<>(); for (JsonNode k : d.path("skills")) if (k.isTextual() && !k.asText().isBlank()) skills.add(k.asText());
        if (!skills.isEmpty()) out.add(item("skills", source, "Skills listed: " + String.join(", ", skills)));
        for (JsonNode p : d.path("accomplishment_publications")) out.add(item("publication", source, "Published: " + join(p.path("name").asText(""), p.path("publisher").asText("")) + (p.path("published_on").isMissingNode() ? "" : ", " + when(p.path("published_on")))));
        for (JsonNode p : d.path("accomplishment_patents")) out.add(item("patent", source, "Patent: " + join(p.path("title").asText(""), p.path("patent_number").asText("")) + (p.path("issued_on").isMissingNode() ? "" : ", " + when(p.path("issued_on")))));
        for (JsonNode p : d.path("accomplishment_honors_awards")) out.add(item("award", source, "Award: " + join(p.path("title").asText(""), p.path("issuer").asText(""))));
        for (JsonNode p : d.path("accomplishment_projects")) out.add(item("project", source, "Project: " + join(p.path("title").asText(""), p.path("description").asText(""))));
        for (JsonNode p : d.path("certifications")) out.add(item("certification", source, "Certified: " + join(p.path("name").asText(""), p.path("authority").asText(""))));
        for (JsonNode r : d.path("recommendations")) if (r.isTextual() && !r.asText().isBlank()) out.add(item("recommendation", source, "Someone wrote of them: " + r.asText().strip()));
        for (JsonNode a : d.path("activities")) if (!a.path("title").asText("").isBlank()) out.add(item("activity", source, "Posted or shared: " + a.path("title").asText().strip()));
        return out;
    }

    /**
     * The roles a person held, read out of their own words or a document by the model, as role facts shaped like a LinkedIn file's: "title at
     * employer, start to end, place". So a person who only typed has roles the reading, the cut and the search can use; without a model, or
     * when the words hold no role, nothing is added.
     */
    static List<ObjectNode> rolesFrom(String text, String source) {
        List<ObjectNode> out = new ArrayList<>();
        if (text == null || text.strip().length() < 20) return out;
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "Below is what a person wrote about their working life, in their own language. List the jobs they say they have held, each as an object: "
                + "{\"title\": the job title as they give it, \"employer\": the employer's name or \"\", \"start\": \"YYYY-MM\" or \"YYYY\" or \"\", \"end\": \"YYYY-MM\", \"YYYY\", \"now\" for the job they hold today, or \"\", "
                + "\"place\": the town or \"\"}. Today is " + LocalDate.now() + ": a job described as current ends \"now\"; \"7 years\" at it means a start about seven years ago, in the year. The job they hold "
                + "or held most recently comes first. Keep the title in their words. A wish for the next job is not a job held. Answer with a JSON array and nothing else, [] when they name no job.\n\n"
                + Fence.rule("the person's words") + "\n" + Fence.wrap("THEIR WORDS", text.strip()));
        String said;
        try { said = DRIVE.get().classify(msgs, 600); } catch (RuntimeException e) { return out; }
        for (JsonNode r : parseArray(said)) {
            String title = r.path("title").asText("").strip(), employer = r.path("employer").asText("").strip(), start = r.path("start").asText("").strip(), end = r.path("end").asText("").strip(), place = r.path("place").asText("").strip();
            if (title.isEmpty() || title.length() > 80) continue;
            String span = start.isEmpty() && end.isEmpty() ? "" : ", " + (start.isEmpty() ? "?" : start) + " to " + (end.isEmpty() ? "?" : end);
            ObjectNode it = item("role", source, title + (employer.isEmpty() ? "" : " at " + employer) + span + (place.isEmpty() ? "" : ", " + place));
            it.put("start", start); it.put("end", end); it.put("by", ROLES_BY);
            out.add(it);
            if (out.size() >= 12) break;
        }
        return out;
    }

    static final String ROLES_BY = "read out of your words by the library";

    /**
     * The roles held, read once out of each told text and each document by the model and kept as role facts beside the words they came
     * from, so a person who only typed has roles the reading, the cut and the search can use as a LinkedIn file's. A source already read
     * for roles is not read again; a source the model finds no role in stays as it is.
     */
    static ArrayNode withRolesRead(LibraryStore store, Seeker s, ArrayNode items) throws IOException {
        Map<String, StringBuilder> texts = new LinkedHashMap<>();
        Set<String> done = new HashSet<>();
        for (JsonNode it : items) {
            String kind = it.path("kind").asText(""), source = it.path("source").asText("");
            if (it.path("by").asText("").equals(ROLES_BY)) done.add(source);
            if (kind.equals("told") || kind.equals("document")) texts.computeIfAbsent(source, k -> new StringBuilder()).append(it.path("text").asText("")).append('\n');
        }
        int next = maxId(items) + 1; boolean added = false;
        for (var e : texts.entrySet()) {
            if (done.contains(e.getKey())) continue;
            for (ObjectNode r : rolesFrom(e.getValue().toString(), e.getKey())) { r.put("id", "E" + next++); items.add(r); added = true; }
        }
        if (added) Files.writeString(s.dir(store).resolve("evidence.json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(items), StandardCharsets.UTF_8);
        return items;
    }

    /** A role's or a school's start and end, kept beside its text, so its length is counted when it is read. */
    private static ObjectNode spanned(ObjectNode item, JsonNode e) {
        item.put("start", when(e.path("starts_at")));
        item.put("end", e.path("ends_at").isNull() || e.path("ends_at").isMissingNode() ? "now" : when(e.path("ends_at")));
        return item;
    }

    /**
     * How long a span lasted, counted the way LinkedIn counts it, both months included: "2 years 10 months", "1 year 2 months so far".
     * "" when the dates do not say.
     */
    static String length(String start, String end, LocalDate today) {
        Matcher a = YM.matcher(start == null ? "" : start), b = YM.matcher(end == null || end.equals("now") ? today.getYear() + "-" + String.format("%02d", today.getMonthValue()) : end);
        if (!a.matches() || !b.matches()) return "";
        boolean ongoing = "now".equals(end);
        String so = ongoing ? " so far" : "";
        int y1 = Integer.parseInt(a.group(1)), y2 = Integer.parseInt(b.group(1));
        if (a.group(2) == null || b.group(2) == null) { int years = y2 - y1; return years <= 0 ? "" : "about " + years + (years == 1 ? " year" : " years") + so; }
        int months = (y2 - y1) * 12 + Integer.parseInt(b.group(2)) - Integer.parseInt(a.group(2)) + 1;
        if (months <= 0) return "";
        int y = months / 12, m = months % 12;
        String out = (y > 0 ? y + (y == 1 ? " year" : " years") : "") + (y > 0 && m > 0 ? " " : "") + (m > 0 ? m + (m == 1 ? " month" : " months") : "");
        return out + so;
    }
    private static final Pattern YM = Pattern.compile("(\\d{4})(?:-(\\d{2}))?");

    /** A fact's text with its length counted in, after its dates: "…, 2021-09 to 2024-06 (2 years 10 months), …". */
    static String withLength(JsonNode it, LocalDate today) {
        String text = it.path("text").asText(), start = it.path("start").asText(""), end = it.path("end").asText("");
        if (start.isEmpty() || end.isEmpty()) return text;
        String span = start + " to " + end, len = length(start, end, today);
        return len.isEmpty() || !text.contains(span) ? text : text.replaceFirst(Pattern.quote(span), Matcher.quoteReplacement(span + " (" + len + ")"));
    }

    private static ObjectNode item(String kind, String source, String text) { return M.createObjectNode().put("kind", kind).put("source", source).put("text", text); }
    private static String join(String a, String b) { a = a == null ? "" : a.strip(); b = b == null ? "" : b.strip(); return a.isEmpty() ? b : b.isEmpty() ? a : a + ", " + b; }
    static String when(JsonNode at) {
        if (at == null || at.isNull() || at.isMissingNode() || !at.has("year")) return "?";
        int y = at.path("year").asInt(), m = at.path("month").asInt(0);
        return m > 0 ? String.format("%d-%02d", y, m) : String.valueOf(y);
    }

    static final Pattern FOUNDING = Pattern.compile("(?i)\\b(co-?founder|founder|founding)\\b"),
            ADVISING = Pattern.compile("(?i)\\b(advisor|adviser|advisory|mentor|board)\\b"),
            LEADING = Pattern.compile("(?i)\\b(vp|vice president|director|head|chief|cto|ceo|coo|cio|president|manager|lead)\\b"),
            MAKING = Pattern.compile("(?i)\\b(engineer|architect|developer|scientist|designer|programmer|analyst)\\b");

    /**
     * What the library counts from the dated roles by their titles, so the reading's patterns rest on numbers: how many roles and how
     * many still going; the founding, advising, leading and making roles, a role in more than one when its title says so; the longest.
     * Given to the model as C1, C2, …; empty when no role carries dates.
     */
    public static List<String> counted(JsonNode items, LocalDate today) {
        List<JsonNode> roles = new ArrayList<>();
        for (JsonNode it : items) if (it.path("kind").asText().equals("role") && it.has("start")) roles.add(it);
        if (roles.isEmpty()) return List.of();
        roles.sort((x, y) -> x.path("start").asText("?").compareTo(y.path("start").asText("?")));
        List<String> out = new ArrayList<>();
        long going = roles.stream().filter(r -> r.path("end").asText().equals("now")).count();
        out.add("Roles listed: " + roles.size() + ", the first from " + roles.get(0).path("start").asText() + "; " + going + " still going.");
        String[][] kinds = {{"Founding roles, by their titles", "f"}, {"Advising and mentoring roles, by their titles", "a"},
                {"Leading roles (manager, lead, director, head, VP, chief), by their titles", "l"},
                {"Making roles (engineer, architect, developer, scientist) with no leading, founding or advising word in the title", "m"}};
        for (String[] k : kinds) {
            List<String> hit = new ArrayList<>();
            for (JsonNode r : roles) {
                String t = title(r);
                boolean f = FOUNDING.matcher(t).find(), a = ADVISING.matcher(t).find(), l = LEADING.matcher(t).find(), m = MAKING.matcher(t).find();
                boolean in = switch (k[1]) { case "f" -> f; case "a" -> a; case "l" -> l; default -> m && !l && !f && !a; };
                if (in) hit.add(t + " at " + company(r) + " (" + r.path("start").asText() + " to " + r.path("end").asText() + ", " + length(r.path("start").asText(), r.path("end").asText(), today) + ")");
            }
            if (!hit.isEmpty()) out.add(k[0] + ": " + hit.size() + ". " + String.join("; ", hit) + ".");
        }
        Map<String, Integer> places = new LinkedHashMap<>();
        for (JsonNode r : roles) { String at = place(r); if (!at.isEmpty()) places.merge(at, 1, Integer::sum); }
        if (!places.isEmpty()) {
            List<Map.Entry<String, Integer>> byCount = new ArrayList<>(places.entrySet());
            byCount.sort((x, y) -> y.getValue() - x.getValue());
            List<String> said = new ArrayList<>(); for (Map.Entry<String, Integer> e : byCount) said.add(e.getKey() + " " + e.getValue());
            out.add("Where the roles were, by the place each gives: " + String.join("; ", said) + ".");
        }
        JsonNode longest = null; int most = -1; List<Integer> done = new ArrayList<>();
        for (JsonNode r : roles) {
            if (ADVISING.matcher(title(r)).find()) continue;
            int m = months(r.path("start").asText(), r.path("end").asText(), today);
            if (m > most) { most = m; longest = r; }
            if (m > 0 && !r.path("end").asText().equals("now")) done.add(m);
        }
        if (longest != null && most > 0) out.add("The longest role, leaving out advising and mentoring: " + title(longest) + " at " + company(longest) + ", " + length(longest.path("start").asText(), longest.path("end").asText(), today) + ".");
        if (done.size() >= 3) {
            done.sort(Integer::compare);
            int mid = done.get(done.size() / 2), y = mid / 12, mo = mid % 12;
            out.add("Of the " + done.size() + " finished roles, leaving out advising and mentoring, the middle one in length lasted "
                    + ((y > 0 ? y + (y == 1 ? " year" : " years") : "") + (y > 0 && mo > 0 ? " " : "") + (mo > 0 ? mo + (mo == 1 ? " month" : " months") : "")) + ": half lasted longer, half shorter.");
        }
        return out;
    }

    /** A role's title, from its fact: the words before " at ". */
    static String title(JsonNode role) { String t = role.path("text").asText(); int at = t.indexOf(" at "); return at < 0 ? t : t.substring(0, at).strip(); }
    /** A role's company, from its fact: the words between " at " and its dates. */
    static String company(JsonNode role) {
        String t = role.path("text").asText(); int at = t.indexOf(" at "); if (at < 0) return "";
        String rest = t.substring(at + 4); int comma = rest.indexOf(", " + role.path("start").asText());
        return (comma < 0 ? rest : rest.substring(0, comma)).strip();
    }
    /** Where a role was, by the first part of the place its fact gives after its dates: "Cambridge", "Greater Boston"; "" when none. */
    static String place(JsonNode role) {
        String t = role.path("text").asText(), span = role.path("start").asText() + " to " + role.path("end").asText();
        int i = t.indexOf(span); if (i < 0) return "";
        String after = t.substring(i + span.length());
        if (!after.startsWith(", ")) return "";
        after = after.substring(2); int stop = after.indexOf(". "); if (stop >= 0) after = after.substring(0, stop);
        return after.split(",")[0].strip();
    }

    /** The months a span covers, both months included; -1 when the dates do not say. */
    static int months(String start, String end, LocalDate today) {
        Matcher a = YM.matcher(start == null ? "" : start), b = YM.matcher(end == null || end.equals("now") ? today.getYear() + "-" + String.format("%02d", today.getMonthValue()) : end);
        if (!a.matches() || !b.matches() || a.group(2) == null || b.group(2) == null) return -1;
        return (Integer.parseInt(b.group(1)) - Integer.parseInt(a.group(1))) * 12 + Integer.parseInt(b.group(2)) - Integer.parseInt(a.group(2)) + 1;
    }

    /**
     * The facts as the model sees them: numbered, one a line, the dated ones (roles, schools) oldest first, as a life is told; a profile
     * lists them newest first, and read that way the model told the order of the roles backwards.
     */
    static String evidenceBlock(ArrayNode items) {
        StringBuilder b = new StringBuilder();
        LocalDate today = LocalDate.now();
        List<JsonNode> head = new ArrayList<>(), dated = new ArrayList<>(), rest = new ArrayList<>();
        for (JsonNode it : items) (it.has("start") ? dated : dated.isEmpty() ? head : rest).add(it);
        dated.sort((x, y) -> x.path("start").asText("?").compareTo(y.path("start").asText("?")));
        List<JsonNode> ordered = new ArrayList<>(head); ordered.addAll(dated); ordered.addAll(rest);
        for (JsonNode it : ordered) b.append(it.path("id").asText()).append(" (").append(it.path("kind").asText()).append("; ").append(it.path("source").asText()).append("): ").append(withLength(it, today).replace("\n", " ")).append('\n');
        List<String> counts = counted(items, today);
        for (int i = 0; i < counts.size(); i++) b.append("C").append(i + 1).append(" (counted by the library from the roles' titles): ").append(counts.get(i)).append('\n');
        return b.toString();
    }

    /**
     * The reading: the library's story of the person's working life, written by the model from the facts alone, every line tied to
     * its facts, ending in the possible wants and changes as questions. Saved as {@code reading.md}, dated. "" when the model gave none.
     */
    public static String reading(LibraryStore store, Seeker s) throws IOException {
        ArrayNode items = withRolesRead(store, s, evidence(store, s));
        if (items.isEmpty()) return "";
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "You are the library reading a person's working life for the person themselves, who is thinking about what work to look for next. "
                + "Today is " + LocalDate.now() + ". Each role's length is counted for you in brackets after its dates; use those lengths as given. "
                + "A document's 'present' or 'current' means the time it was written, which may be years ago; the person may have said when it was written. "
                + "The lines numbered C1, C2, … were counted by the library from the roles' titles; use those counts as given.\n\n"
                + "Read ONLY the evidence below, numbered E1, E2, … and C1, C2, …, and write the reading as markdown with exactly these sections. "
                + "Retelling the history is the smallest part of it. The reading's work is to say what the pattern of the person's choices suggests "
                + "about what drives them and what they would want next, each as a hypothesis for them to confirm or correct, and to ask the questions "
                + "a good career counsellor would ask this person next.\n\n"
                + "## The arc\nWhat changed over the career in scale, role and domain, and when, in three to five sentences.\n"
                + "## What you keep choosing\nThe patterns in the person's choices, the strongest first, each named in plain words, such as \"you keep "
                + "going back to startups\", and counted: the kinds of company and team they join or found, the roles they take, the domains, founding, "
                + "advising, teaching, writing, and the places. For each pattern, name the exceptions the evidence shows.\n"
                + "## What seems to drive you\nWhat those patterns, and what the person and others wrote of them, suggest about their motivations and "
                + "interests. Each is a hypothesis, said as one (\"you may be drawn to …\"), with the evidence for it. Consider each of these, so that "
                + "none the evidence suggests is left out: " + String.join("; ", DRIVES) + ".\n"
                + "## The places you work best in\nWhat the evidence suggests about the workplaces the person chooses and does well in: how decisions "
                + "are made, the pace, how much structure, how people work together. Consider four kinds of workplace: " + String.join("; ", WORKPLACES)
                + ". Say what the evidence suggests, as hypotheses, and what it does not give.\n"
                + "## What you moved away from\nOnly what the later evidence does not take up again; where a later role takes it up again, say so.\n"
                + "## Where you are now\nHow long you have been where you are, against your own pattern.\n"
                + "## On the side\nWhat you do beside the main work: a side project in a new area is a want the resume does not state.\n"
                + "## What the facts do not say\n"
                + "## Questions for you\nTen to twelve numbered questions, the ones a good career counsellor would ask this person next. Each is "
                + "open, asking what, which, why or how; none can be answered with yes or no. Each grows out of something in the evidence, names it, "
                + "and ends with its tag in parentheses, then the ids. In this order. First, one or two questions on what drives them: why they keep "
                + "making the choice their strongest pattern shows; tag (what drives you). Then one question for each of these four, asking which "
                + "suited them best and why, with examples from their own history: the size of the companies; how young or established the companies "
                + "were; making things or managing people; founding, being employed or advising; tag (preference). Then two questions on the "
                + "workplace: where they did their best work and what that place was like, and what made them leave the places they left; tag "
                + "(workplace). Then two or three questions on what they want next: what a good week in the next job would look like, what they "
                + "would refuse, what would have to be true for them to say yes; tag (what you want next). Last, at most two questions about a "
                + "change, only where the evidence points to one, each asking what the change would give them that they do not have now; the "
                + "kinds of change to consider: " + String.join("; ", CHANGES) + "; tag (a change).\n\n"
                + "Tie every line to its evidence by writing the ids in square brackets at the end of the line, like [E3] or [E2][C1]. "
                + "Give every role its own title and company exactly as its evidence gives them. The evidence does not give the size or the stage "
                + "of a company: where a line rests on what is generally known about a company, such as its size, write [general knowledge] after its "
                + "ids, as in \"…, a large company [E…][general knowledge].\" Where nothing is known, say that the evidence does not give it. Write "
                + "nothing the evidence does not support, and where it does not say, say so. Write for the person, plainly, in the second person "
                + "('you'). Where a date is given, use it.\n\nEVIDENCE\n" + evidenceBlock(items));
        String out = DRIVE.get().prose(msgs, 4500);
        if (out == null || out.isBlank()) return "";
        String md = "*Read on " + LocalDate.now() + " from " + items.size() + (items.size() == 1 ? " fact" : " facts") + ".*\n\n" + markGeneralKnowledge(out.strip(), items) + "\n";
        Files.writeString(s.dir(store).resolve("reading.md"), md, StandardCharsets.UTF_8);
        Path rec = s.dir(store).resolve("record.json");
        ObjectNode record = Files.isRegularFile(rec) ? (ObjectNode) M.readTree(rec.toFile()) : M.createObjectNode().put("name", s.name()).put("slug", s.slug());
        record.put("read_upto", maxId(items)).put("read_count", items.size());
        M.writerWithDefaultPrettyPrinter().writeValue(rec.toFile(), record);
        log(store, s, "The library wrote its reading from " + items.size() + (items.size() == 1 ? " fact." : " facts."));
        return md;
    }

    /** Words that size or stage a company. A profile's facts hardly ever say them, so a line that does usually rests on something else. */
    private static final Pattern SIZE_WORDS = Pattern.compile("(?i)\\b(large|larger|big(?! data)|bigger|small|smaller|mid-sized|mid-size|medium-sized|established|early-stage|late-stage|later-stage|growth-stage|start-?ups?|enterprises?|corporations?|conglomerates?|multinationals?)\\b");
    private static final Pattern CITED = Pattern.compile("\\[(E\\d+)\\]");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z])");
    private static final Pattern CITATION_TAIL = Pattern.compile("^(.*\\])(\\s*[.!?]?\\s*)$", Pattern.DOTALL);

    /**
     * The reading with [general knowledge] added to each sentence that sizes or stages a company where none of the facts it cites says
     * so, beside that sentence's own citations. The model is told to mark such sentences and often does not, so the library checks. The
     * questions at the end ask rather than tell, and the gaps say what is missing, so neither is marked.
     */
    public static String markGeneralKnowledge(String md, JsonNode items) {
        Map<String, String> facts = new HashMap<>();
        for (JsonNode it : items) facts.put(it.path("id").asText(), it.path("text").asText().toLowerCase(Locale.ROOT));
        StringBuilder out = new StringBuilder();
        boolean tells = true;
        String[] lines = md.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("## ")) { String h = line; tells = UNTOLD.stream().noneMatch(h::contains); }
            else if (tells && !line.isBlank() && !line.startsWith("*Read on")) {
                String[] sentences = SENTENCE_BREAK.split(line);
                StringBuilder marked = new StringBuilder();
                for (int k = 0; k < sentences.length; k++) {
                    String sen = sentences[k];
                    if (!sen.contains("[general knowledge]") && sizesUnsaid(sen, facts)) {
                        Matcher t = CITATION_TAIL.matcher(sen);
                        sen = t.matches() ? t.group(1) + "[general knowledge]" + t.group(2) : sen.stripTrailing() + " [general knowledge]";
                    }
                    if (k > 0) marked.append(' ');
                    marked.append(sen);
                }
                line = marked.toString();
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    /** Whether a sentence sizes or stages a company with a word none of the facts it cites says. */
    private static boolean sizesUnsaid(String sentence, Map<String, String> facts) {
        Matcher w = SIZE_WORDS.matcher(sentence);
        while (w.find()) {
            String word = w.group(1).toLowerCase(Locale.ROOT);
            boolean said = false;
            Matcher c = CITED.matcher(sentence);
            while (c.find()) if (facts.getOrDefault(c.group(1), "").contains(word)) said = true;
            if (!said) return true;
        }
        return false;
    }

    /** The reading as kept; "" when there is none yet. */
    public static String readingKept(LibraryStore store, Seeker s) throws IOException {
        Path f = s.dir(store).resolve("reading.md");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
    }

    /** Whether the facts changed since the reading was written, so the reading no longer covers them all. */
    public static boolean stale(LibraryStore store, Seeker s) throws IOException {
        Path rec = s.dir(store).resolve("record.json");
        if (readingKept(store, s).isBlank() || !Files.isRegularFile(rec)) return false;
        JsonNode r = M.readTree(rec.toFile());
        ArrayNode items = evidence(store, s);
        return r.path("read_upto").asInt(-1) != maxId(items) || r.path("read_count").asInt(-1) != items.size();
    }

    /**
     * Whether a keep-out the draft proposes is only the negative side of a topic the wants hold, "remote work", "a permanent post", "a team
     * to run", which the topic itself carries (remote decided on site, arrangement decided contract) and which, kept out, would cut by its
     * words what the person wants. Such words name the topic, not a thing outside it; the rule holds whatever the draft said.
     */
    static boolean topicsOwnWords(String keepOut) {
        String k = keepOut == null ? "" : keepOut.toLowerCase(Locale.ROOT);
        return k.matches("(?s).*\\b(remote|remotely|on[- ]site|onsite|office|hybrid|home ?office|homeoffice|télétravail|teletrabajo|teletrabalho|part[- ]time|full[- ]time|permanent|temporary|contract|freelance|a team|team to run|managing|management|relocat|commut)\\b.*")
                || k.matches("(?s).*(在宅|リモート|正社員|パート|契約|フルタイム|チーム|teilzeit|vollzeit|festanstellung|unbefristet|mit team|équipe|permanente|equipa|equipo|tiempo parcial|tempo parcial|remoto|presencial).*");
    }

    /**
     * What the person wants, drafted by the model from the reading and from what they said: one entry per topic, each decided (they
     * said it), leaning (a guess from their history, with the facts it rests on), or open. A topic the person set themselves is kept as
     * it is. Saved as {@code wants.json}; the model's own answer is kept beside it in {@code wants-draft.txt}.
     */
    public static ObjectNode wants(LibraryStore store, Seeker s) throws IOException {
        ObjectNode kept = wantsKept(store, s);
        String reading = readingKept(store, s);
        Path toldF = s.dir(store).resolve("told.md");
        String told = Files.isRegularFile(toldF) ? Files.readString(toldF, StandardCharsets.UTF_8) : "";
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "From the reading of a person's working life and what the person said about what they want, draft what they want, one entry per "
                + "dimension, as a JSON array and nothing else. The dimensions, in this order: " + String.join("; ", DIMENSIONS) + ". Each entry: "
                + "{\"dimension\": …, \"state\": …, \"want\": …, \"from\": …}.\n\n"
                + "What each dimension holds: domain, the field they work in; kind of work, making or leading, hands-on or managing; role, the title or seat and its "
                + "level; kind of employer, a company, a firm, a hospital or clinic, a lab or university, a school, a government or public body, an agency, or clients "
                + "of their own; company size and company stage, of the employer; culture; place, the town or region where the work is to be; mobility, whether they "
                + "would stay, commute some distance, move within the country or move abroad; remote; arrangement, full-time, part-time, contract or freelance, temporary "
                + "or seasonal, an apprenticeship; right to work, a licence or registration to transfer, a visa or sponsorship needed; pace; pay; ownership. A statement "
                + "about moving or commuting is mobility and never place; about part-time, contract or temporary work is arrangement and never kind of work; about a "
                + "licence or a visa is right to work; about a hospital, a lab, a firm or a school is kind of employer. A place is decided only when the person named the "
                + "town or region they want the work in. A licence or registration the person holds for that place makes right to work decided and in order; a licence "
                + "from elsewhere, or a visa needed, is right to work to sort out. What the person said is dated, oldest first: when they said two things on one topic, "
                + "the later is what they want now and the earlier is history. 'Anywhere within an hour of Lyon' names the place, Lyon, decided, and sets mobility to that "
                + "distance, decided; a radius with no town named leaves place open and sets mobility alone.\n\n"
                + "The state is one of three. DECIDED: the person said it plainly in WHAT THE PERSON SAID; 'want' is in their words and 'from' is 'told'. "
                + "LEANING: the reading speaks to the dimension, in What you keep choosing, What seems to drive you, The places you work best in, "
                + "Where you are now, On the side, or one of its questions, "
                + "so there is a hypothesis to put to the person; 'want' states the hypothesis in one sentence, as a hypothesis ('you have stayed in "
                + "one region for every role; the next role there too?'), and 'from' gives the evidence ids the reading cites for it. OPEN: the reading "
                + "says nothing about the dimension; 'want' and 'from' are \"\". A reading that mentions where the person has worked makes place "
                + "leaning; one that names the sizes or stages of their companies makes company size and company stage leaning; one that names "
                + "their domains or kinds of work makes domain and kind of work leaning; one that names their roles makes role leaning; one that "
                + "shows them founding, advising or employed makes ownership leaning; one that describes the places they work best in makes culture "
                + "leaning. Where the person has tried more than one kind, state the hypothesis as a preference to confirm ('you have worked at both "
                + "very small and very large companies; which suits you best?'). Most readings leave pay, pace, remote, arrangement, mobility and right to work open "
                + "unless the person said them.\n\n"
                + "One entry, as an example of the form: {\"dimension\": \"place\", \"state\": \"leaning\", \"want\": \"every role so far was in one "
                + "region; is the next one there too, or anywhere?\", \"from\": \"E1 E2 E3\"}\n\n"
                + "Then three more entries at the end of the array. {\"dimension\": \"keep out\", \"want\": [...]}: each thing the person said they do not want, a "
                + "company, an industry, a kind of work, a working condition such as night shifts or weekend work, a kind of contract such as zero-hours, a place, in a few of "
                + "their words, whether they said it as no, not, rather not, or would prefer to avoid; an empty list when they said none. The negative side of a dimension above "
                + "is that dimension and never a keep-out: 'remote does not suit me' settles remote, 'not a permanent post' settles arrangement. {\"dimension\": \"named\", "
                + "\"want\": [{\"name\": …, \"kind\": \"employer wanted\" | \"knows people at\" | \"person\"}, ...]}: the employers and people the person named as a way in or "
                + "as a target, an employer they would want as employer wanted, one where they know people as knows people at, a person by name as person; an employer where "
                + "they work or worked is their history and never goes here; an empty list when none. "
                + "{\"dimension\": \"ask\", \"want\": [{\"topic\": …, \"question\": …}, ...]}: for each of these topics that you left OPEN, role, kind of employer, "
                + "place, mobility, remote, arrangement, right to work, one plain question of one sentence in the person's own language asking what they want on it "
                + "(for place: where they want the work to be); none for a topic decided or leaning.\n\n"
                + Fence.rule("WHAT THE PERSON SAID") + "\n\n" + Fence.wrap("WHAT THE PERSON SAID", told.isBlank() ? "(nothing yet)" : told) + "\n\nTHE READING\n" + (reading.isBlank() ? "(none yet)" : reading));
        String out = DRIVE.get().classify(msgs, 1800);
        Files.writeString(s.dir(store).resolve("wants-draft.txt"), "# the model's draft, " + LocalDate.now() + "\n" + (out == null ? "" : out), StandardCharsets.UTF_8);   // kept, so a draft that could not be read is seen, not guessed at
        ArrayNode drafted = parseArray(out);
        ObjectNode doc = M.createObjectNode().put("at", LocalDate.now().toString());
        ArrayNode dims = doc.putArray("dimensions");
        int keptOut = 0, named = 0;   // what the person said they rule out, and whom they named, go straight to the search's profile
        ObjectNode ko = find(drafted, "keep out"), nm = find(drafted, "named"), ask = find(drafted, "ask");
        if (ko != null) for (JsonNode k : ko.path("want")) if (k.isTextual() && !k.asText().isBlank() && !topicsOwnWords(k.asText()) && CareerSearch.addKeepOut(store, s, k.asText().strip(), "what you handed over")) keptOut++;
        if (nm != null) for (JsonNode k : nm.path("want")) {
            String name = k.path("name").asText("").strip(), kind = k.path("kind").asText("").strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (CareerSearch.addNamed(store, s, name, kind, "what you handed over")) named++;
            if (kind.equals("employer wanted")) CareerSearch.addCompany(store, s, name, "what you handed over");
        }

        for (String name : DIMENSIONS) {
            ObjectNode was = find(kept.path("dimensions"), name), got = find(drafted, name);
            ObjectNode e = dims.addObject().put("dimension", name);
            if (was != null && (was.path("by").asText("").equals("seeker") || was.path("by").asText("").equals("answer"))) { e.setAll(was); continue; }   // what the person set, or their answers did, stays theirs
            String st = got == null ? "open" : state(got.path("state").asText("open"));
            if (st.isEmpty()) st = "open";
            e.put("state", st).put("want", got == null ? "" : got.path("want").asText("").strip()).put("from", got == null ? "" : got.path("from").asText("").strip()).put("by", "library");
        }
        ArrayNode asks = doc.putArray("ask");   // one plain question for each topic of the minimum still open, in the person's language, for the interview to ask first
        if (ask != null) for (JsonNode k : ask.path("want")) {
            String topic = k.path("topic").asText("").strip().toLowerCase(Locale.ROOT), q = k.path("question").asText("").strip();
            ObjectNode d = find(dims, topic);
            if (MINIMUM.contains(topic) && !q.isEmpty() && d != null && d.path("state").asText("open").equals("open")) asks.addObject().put("topic", topic).put("text", q);
        }
        M.writerWithDefaultPrettyPrinter().writeValue(s.dir(store).resolve("wants.json").toFile(), doc);
        if (drafted.isEmpty()) log(store, s, "The model's draft of what you want could not be read, so every topic was left open. Its answer is kept in " + s.dir(store).resolve("wants-draft.txt") + ".");
        else {
            List<String> parts = new ArrayList<>();
            int dn = count(dims, "decided"), ln = count(dims, "leaning"), on = count(dims, "open");
            if (dn > 0) parts.add(dn + " decided from what you said");
            if (ln > 0) parts.add(ln + (ln == 1 ? " guess" : " guesses") + " for you to confirm");
            if (on > 0) parts.add(on + " still open");
            if (keptOut > 0) parts.add(keptOut + (keptOut == 1 ? " thing" : " things") + " you rule out");
            if (named > 0) parts.add(named + (named == 1 ? " name" : " names") + " you gave");
            log(store, s, "The library drafted what you want: " + and(parts) + ".");
        }
        return doc;
    }

    /** The topics set from the person's answers go back to open, for the answers to be read again; what the person set themselves stays. */
    public static void clearAnswerWants(LibraryStore store, Seeker s) throws IOException {
        ObjectNode doc = wantsKept(store, s);
        for (JsonNode e : doc.path("dimensions")) if (e.isObject() && e.path("by").asText("").equals("answer")) ((ObjectNode) e).put("state", "open").put("want", "").put("from", "").put("by", "library");
        M.writerWithDefaultPrettyPrinter().writeValue(s.dir(store).resolve("wants.json").toFile(), doc);
    }

    /** The title of the person's latest role, from the record's role facts ("chef at Corvid, 2025-05 to now" gives "chef"); "" when the record has none. */
    /** The employers the person works for now: of every role that runs to now, the name after "at" ("Staff Engineer at Chewy, 2022-10 to now"). */
    public static List<String> currentEmployers(LibraryStore store, Seeker s) throws IOException {
        List<String> out = new ArrayList<>();
        for (JsonNode f : evidence(store, s)) {
            if (!f.path("kind").asText("").equals("role") || !f.path("end").asText("").equals("now")) continue;
            String text = f.path("text").asText("");
            int at = text.indexOf(" at ");
            if (at < 0) continue;
            String employer = text.substring(at + 4).replaceFirst(",\\s*\\d{4}.*$", "").strip();
            if (!employer.isEmpty() && out.stream().noneMatch(x -> x.equalsIgnoreCase(employer))) out.add(employer);
        }
        return out;
    }

    public static String latestTitle(LibraryStore store, Seeker s) throws IOException {
        String best = "", bestStart = "";
        for (JsonNode f : evidence(store, s)) {
            if (!f.path("kind").asText("").equals("role")) continue;
            String text = f.path("text").asText(""), start = f.path("start").asText(""), end = f.path("end").asText("");
            int at = text.indexOf(" at ");
            String title = (at > 0 ? text.substring(0, at) : text.replaceFirst(",.*$", "")).strip();
            if (title.isEmpty()) continue;
            if (end.equals("now") || start.compareTo(bestStart) > 0) { best = title; bestStart = end.equals("now") ? "9999" : start; }
        }
        return best;
    }

    /** Where the record puts the person, from what they handed over; "" when it says nothing. */
    public static String location(LibraryStore store, Seeker s) throws IOException {
        Path f = s.dir(store).resolve("record.json");
        if (!Files.isRegularFile(f)) return "";
        return M.readTree(f.toFile()).path("location").asText("").strip();
    }

    /** A sentence in the person's own words, for the model to take their language from: the first decided want, else the first line they told; "" when they wrote nothing. */
    public static String language(LibraryStore store, Seeker s) throws IOException {
        for (JsonNode d : wantsKept(store, s).path("dimensions")) if (d.path("state").asText("").equals("decided") && d.path("by").asText("").equals("told") || d.path("from").asText("").equals("told")) { String w = d.path("want").asText("").strip(); if (!w.isEmpty()) return w.length() > 120 ? w.substring(0, 120) : w; }
        Path toldF = s.dir(store).resolve("told.md");
        if (Files.isRegularFile(toldF)) for (String line : Files.readString(toldF, StandardCharsets.UTF_8).split("\\R")) { String l = line.replaceFirst("^[#>\\-*\\s]+", "").strip(); if (l.length() > 12 && !l.startsWith("<")) return l.length() > 120 ? l.substring(0, 120) : l; }
        return "";
    }

    /** What the person wants, as kept; a document with no topics when nothing is drafted yet. */
    public static ObjectNode wantsKept(LibraryStore store, Seeker s) throws IOException {
        Path f = s.dir(store).resolve("wants.json");
        if (!Files.isRegularFile(f)) { ObjectNode o = M.createObjectNode(); o.putArray("dimensions"); return o; }
        JsonNode j = M.readTree(f.toFile());
        return j.isObject() ? (ObjectNode) j : M.createObjectNode();
    }

    /** One topic set by the person themselves: its state and what they want, in their words; logged. */
    public static ObjectNode setWant(LibraryStore store, Seeker s, String dimension, String typedState, String want) throws IOException {
        return setWantFrom(store, s, dimension, typedState, want, "seeker", "");
    }

    /**
     * One topic set, by the person ({@code by} "seeker") or from one of their answers ("answer", {@code from} saying which): both are
     * the person's own word, and a new draft of what they want keeps them. Logged.
     */
    public static ObjectNode setWantFrom(LibraryStore store, Seeker s, String dimension, String typedState, String want, String by, String from) throws IOException {
        String dim = dimension == null ? "" : dimension.strip().toLowerCase(Locale.ROOT), st = state(typedState);
        if (!DIMENSIONS.contains(dim)) throw new IllegalArgumentException("There is no topic called \"" + (dimension == null ? "" : dimension.strip()) + "\". The topics are: " + String.join(", ", DIMENSIONS) + ".");
        if (st.isEmpty()) throw new IllegalArgumentException("Say decided, guess or open after the topic.");
        ObjectNode doc = wantsKept(store, s);
        if (!doc.has("dimensions")) doc.putArray("dimensions");
        ArrayNode dims = (ArrayNode) doc.get("dimensions");
        ObjectNode e = find(dims, dim);
        if (e == null) e = dims.addObject().put("dimension", dim);
        String w = want == null ? "" : want.strip();
        e.put("state", st).put("want", w).put("from", from == null ? "" : from).put("by", by).put("at", LocalDate.now().toString());
        doc.put("at", LocalDate.now().toString());
        M.writerWithDefaultPrettyPrinter().writeValue(s.dir(store).resolve("wants.json").toFile(), doc);
        String what = label(dim);
        if (by.equals("answer")) log(store, s, "From " + from + ", the library set " + what + " to " + STATE_WORDS.getOrDefault(st, st) + (w.isEmpty() ? "." : ": " + ending(w)));
        else log(store, s, switch (st) {
            case "decided" -> "You decided " + what + (w.isEmpty() ? "." : ": " + ending(w));
            case "leaning" -> "You marked " + what + " as a guess" + (w.isEmpty() ? "." : ": " + ending(w));
            default -> "You marked " + what + " as open" + (w.isEmpty() ? "." : ": " + ending(w));
        });
        return e;
    }

    /** An answer kept as a fact of its own, so the next reading uses it; the same question answered again replaces it, with a new number. */
    public static void answerFact(LibraryStore store, Seeker s, String source, String text) throws IOException {
        ArrayNode items = evidence(store, s), keep = M.createArrayNode();
        int next = maxId(items) + 1;
        for (JsonNode it : items) if (!it.path("source").asText().equals(source)) keep.add(it);
        keep.add(M.createObjectNode().put("id", "E" + next).put("kind", "answer").put("source", source).put("text", text));
        M.writerWithDefaultPrettyPrinter().writeValue(s.dir(store).resolve("evidence.json").toFile(), keep);
    }

    /** Words the person said, kept as said, dated, for the draft of what they want. */
    public static void told(LibraryStore store, Seeker s, String words) throws IOException {
        Files.writeString(s.dir(store).resolve("told.md"), "## " + LocalDate.now() + "\n\n" + words.strip() + "\n\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static ObjectNode find(JsonNode arr, String dimension) {
        if (arr == null) return null;
        for (JsonNode e : arr) if (e.isObject() && e.path("dimension").asText("").strip().equalsIgnoreCase(dimension)) return (ObjectNode) e;
        return null;
    }
    private static int count(ArrayNode dims, String state) { int n = 0; for (JsonNode e : dims) if (e.path("state").asText("").equals(state)) n++; return n; }
    /**
     * The JSON array in a model's answer: the array itself, or the objects of one written without the brackets ("{…}, {…}"), which a model
     * does when the prompt ends in quoted material; a code fence around it is passed over. Nothing readable gives an empty array.
     */
    static ArrayNode parseArray(String out) {
        if (out == null) return M.createArrayNode();
        String t = out.strip().replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)\\s*```\\s*$", "").strip();
        if (t.startsWith("{") && t.lastIndexOf('}') > 0) {   // a list of objects with no brackets round it: the brackets put back, the whole read as the array it was meant as
            try { JsonNode j = M.readTree("[" + t.substring(0, t.lastIndexOf('}') + 1) + "]"); if (j.isArray() && !j.isEmpty()) return (ArrayNode) j; }
            catch (IOException e) { /* then as any other answer */ }
        }
        int a = t.indexOf('['), z = t.lastIndexOf(']');
        if (a < 0 || z <= a) return M.createArrayNode();
        try { JsonNode j = M.readTree(t.substring(a, z + 1)); return j.isArray() ? (ArrayNode) j : M.createArrayNode(); }
        catch (IOException e) { return M.createArrayNode(); }
    }

    /** A line in the person's log, dated. */
    public static void log(LibraryStore store, Seeker s, String what) throws IOException {
        ObjectNode e = M.createObjectNode().put("at", Instant.now().toString()).put("what", what);
        Files.writeString(s.dir(store).resolve("log.jsonl"), e + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The last {@code n} lines of the log, oldest first, each "date: what happened". */
    public static List<String> logTail(LibraryStore store, Seeker s, int n) throws IOException {
        Path f = s.dir(store).resolve("log.jsonl");
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(f)) return out;
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        for (String l : lines.subList(Math.max(0, lines.size() - n), lines.size())) {
            try { JsonNode j = M.readTree(l); out.add(j.path("at").asText().substring(0, 10) + ": " + j.path("what").asText()); } catch (IOException | RuntimeException ignored) { }
        }
        return out;
    }

    /** One topic as a person reads it, for example "Where you work: decided. Boston, or remote. You set this on 2026-10-28." */
    public static String line(JsonNode e) { return line(e, true); }

    /** The same, with or without what it rests on: an answer's reply leaves out "from your answer", which is plain from where it stands. */
    public static String line(JsonNode e, boolean withBasis) {
        String dim = e.path("dimension").asText(), st = e.path("state").asText("open"), want = e.path("want").asText("").strip();
        StringBuilder b = new StringBuilder(cap(label(dim))).append(": ").append(STATE_WORDS.getOrDefault(st, st)).append('.');
        if (!want.isEmpty()) b.append(' ').append(sentence(want));
        String basis = withBasis ? basis(e) : "";
        if (!basis.isEmpty()) b.append(' ').append(basis);
        return b.toString();
    }

    /** Where a topic's state came from, as a sentence; "" when nothing is known. */
    public static String basis(JsonNode e) {
        if (e.path("by").asText("").equals("seeker")) return "You set this" + (e.path("at").asText("").isBlank() ? "." : " on " + e.path("at").asText() + ".");
        if (e.path("by").asText("").equals("answer")) return "From " + ending(e.path("from").asText("your answers"));
        String from = e.path("from").asText("").strip();
        if (from.isEmpty()) return "";
        if (from.equalsIgnoreCase("told")) return "From what you said.";
        List<String> facts = new ArrayList<>(), counts = new ArrayList<>();
        Matcher m = CITE_ID.matcher(from);
        while (m.find()) { String id = m.group(); List<String> to = id.startsWith("C") ? counts : facts; if (!to.contains(id)) to.add(id); }
        if (!facts.isEmpty() || !counts.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (!facts.isEmpty()) parts.add((facts.size() == 1 ? "fact " : "facts ") + and(facts));
            if (!counts.isEmpty()) parts.add((counts.size() == 1 ? "the library's count " : "the library's counts ") + and(counts));
            return "From " + String.join(" and ", parts) + ".";
        }
        return "From " + ending(from);
    }

    static String cap(String s) { return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    /** Words as a sentence of their own: a capital first, a stop last. */
    static String sentence(String s) { return cap(ending(s)); }
    /** Words with a stop at the end, as they follow a colon. */
    static String ending(String s) { s = s == null ? "" : s.strip(); return s.isEmpty() || ".?!".indexOf(s.charAt(s.length() - 1)) >= 0 ? s : s + "."; }
    public static String and(List<String> xs) {
        if (xs.isEmpty()) return "";
        if (xs.size() == 1) return xs.get(0);
        return String.join(", ", xs.subList(0, xs.size() - 1)) + " and " + xs.get(xs.size() - 1);
    }

    /** A real example of setting a topic, from the person's own record where it has one. */
    static String example(LibraryStore store, Seeker s) throws IOException {
        Path rec = s.dir(store).resolve("record.json");
        String where = Files.isRegularFile(rec) ? M.readTree(rec.toFile()).path("location").asText("").strip() : "";
        if (!where.isEmpty()) return "place decided " + where.split(",")[0].strip() + ", or remote";
        return "remote decided yes, fully remote";
    }

    /** The facts as a person reads them, grouped by where they came from. */
    public static String evidenceText(LibraryStore store, Seeker s) throws IOException {
        ArrayNode items = evidence(store, s);
        if (items.isEmpty()) return "The library holds no facts about " + s.name() + "'s work yet.\n";
        StringBuilder b = new StringBuilder("These are the ").append(items.size()).append(items.size() == 1 ? " fact" : " facts").append(" the library holds about ").append(s.name()).append("'s work. The reading points at them by number.\n");
        String last = null;
        for (JsonNode it : items) {
            String src = it.path("source").asText();
            if (!src.equals(last)) { b.append("\nFrom ").append(src).append(":\n"); last = src; }
            b.append(it.path("id").asText()).append(". ").append(withLength(it, LocalDate.now()).replace('\n', ' ').strip()).append('\n');
        }
        List<String> counts = counted(items, LocalDate.now());
        if (!counts.isEmpty()) { b.append("\nCounted by the library from the roles' titles:\n"); for (int i = 0; i < counts.size(); i++) b.append("C").append(i + 1).append(". ").append(counts.get(i)).append('\n'); }
        return b.toString();
    }

    /**
     * The record as the chat and the command print it: what is held, the reading, what the person wants, what changed lately.
     * {@code how} is the command the person types here: "/career" in the chat, "researchzosho career" at the command line.
     */
    public static String text(LibraryStore store, Seeker s, String how) throws IOException {
        StringBuilder b = new StringBuilder();
        ArrayNode items = evidence(store, s);
        List<String> sources = sources(items);
        b.append("The library holds ").append(items.size()).append(items.size() == 1 ? " fact" : " facts").append(" about ").append(s.name()).append("'s work").append(sources.isEmpty() ? "" : ", from " + and(sources)).append(".\n");
        String reading = readingKept(store, s);
        if (reading.isBlank()) b.append("\nThere is no reading yet. To have the library write one, type ").append(how).append(" reading. It takes a minute or two.\n");
        else {
            b.append('\n').append(reading.strip()).append('\n');
            b.append("\nThe numbers in square brackets, like [E3], point to the facts each line rests on. To list the facts, type ").append(how).append(" evidence.\n");
            if (reading.contains("[general knowledge]")) b.append("[general knowledge] marks a line that rests on what the model knew about a company from elsewhere, not on what you handed over.\n");
            if (stale(store, s)) b.append("The library has been given more since it wrote this reading. To have it read everything again, type ").append(how).append(" reading.\n");
            if (!CareerInterview.fromReading(reading).isEmpty())
                b.append(how.startsWith("/") ? "To answer the reading's questions one at a time, type /career interview.\n"
                        : "To answer the reading's questions one at a time, type researchzosho career interview, or /career interview in the chat; the Career page shows them all at once.\n");
        }
        ObjectNode w = wantsKept(store, s);
        if (!w.path("dimensions").isEmpty()) {
            b.append("\nWhat you want, as the library understands it on ").append(w.path("at").asText("")).append(".\n");
            b.append("Decided means you said it. A guess is what the library read from your history, for you to confirm or correct. Open means nothing is known yet.\n");
            for (JsonNode e : w.path("dimensions")) b.append("  ").append(line(e)).append('\n');
            b.append("To set one yourself, type for example: ").append(how).append(" want ").append(example(store, s))
                    .append(". After want comes the topic, then decided, guess or open, then what you want in your own words. The topics are: ").append(String.join(", ", DIMENSIONS)).append(".\n");
        }
        String search = CareerSearch.text(store, s, how);
        if (!search.isEmpty()) b.append('\n').append(search);
        List<String> tail = logTail(store, s, 6);
        if (!tail.isEmpty()) { b.append("\nWhat changed lately:\n"); for (String l : tail) b.append("  ").append(l).append('\n'); }
        return b.toString();
    }
}
