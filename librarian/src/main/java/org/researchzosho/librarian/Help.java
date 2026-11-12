package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.tools.WebSearchTool;
import org.researchzosho.Config;
import org.researchzosho.Version;
import org.researchzosho.drive.DriveClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.TreeMap;
import java.time.Instant;
import java.nio.file.Path;
import java.nio.file.Files;

/**
 * The library's knowledge of itself, so it can answer questions about ResearchZosho: what it is, which version is running, what it can
 * and cannot do, how to do something in it, and why something does not work. Two parts. Its own documentation, for the version that is
 * running, cut into sections by heading and found by the words of the question: a copy run from the source tree reads the tree's own; an
 * installed release fetches its release's from GitHub the first time help is needed and keeps it, so a change to the documentation never
 * needs the program built again. And its live state, read when asked: the version, what this library holds, the model and whether it
 * answers, the web search, the fields that are on, the chat's commands and the pages. What neither says, it says it does not know, and
 * where to ask.
 */
public final class Help {
    private Help() { }

    /** One section of the documentation: which document, its heading, its words. */
    public record Section(String doc, String heading, String text) { }

    /** Where to ask what the documentation does not answer. */
    public static final String ISSUES = "https://github.com/Wyrdsekai/researchzosho/issues";
    static final String REPO = "Wyrdsekai/researchzosho";
    /** The maintainers' notes, which are not the person's help. */
    static final Set<String> NOT_HELP = Set.of("RELEASING.md", "CONTRIBUTING.md", "AGENTS.md");
    /** The documents a release has, when GitHub cannot list them: at the repository's root, and under docs/. */
    static final List<String> ROOT_DOCS = List.of("README.md", "CHANGELOG.md", "SECURITY.md"),
            DOCS = List.of("LIBRARIAN_HOWTOUSE.md", "FAMILY_HISTORY.md", "MODELS.md", "BEDROCK.md", "YOUTUBE.md", "LIBRARY_PROTOCOL.md");
    private static volatile List<Section> sections;
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NORMAL).build();
    /** A source tree's documents, name to file, when the program runs from one; null for an installed release. A test sets it. */
    static volatile Map<String, Path> tree = treeDocs();
    /** What the documentation is fetched with: an address to its body, or null; a test puts a stand-in here. */
    static volatile Function<String, String> FETCH = Help::get;
    /** Which of the repository's versions to read: this release's tag, or main when this is the newest release; a test sets it. */
    static volatile Supplier<String> REF = Help::ref;

    /** The documents of the source tree the program runs from: docs/public in the private tree, docs and the root in the exported one. */
    static Map<String, Path> treeDocs() {
        try {
            Path at = Path.of(Help.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (int up = 0; up < 7 && at != null; up++, at = at.getParent()) {
                if (Files.isRegularFile(at.resolve("docs/public/LIBRARIAN_HOWTOUSE.md"))) return listed(at.resolve("docs/public"), null);
                if (Files.isRegularFile(at.resolve("docs/LIBRARIAN_HOWTOUSE.md")) && Files.isRegularFile(at.resolve("settings.gradle.kts"))) return listed(at.resolve("docs"), at);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static Map<String, Path> listed(Path docs, Path root) throws IOException {
        Map<String, Path> out = new TreeMap<>();
        try (Stream<Path> s = Files.list(docs)) { for (Path p : s.toList()) { String n = p.getFileName().toString(); if (n.endsWith(".md") && !NOT_HELP.contains(n)) out.put(n, p); } }
        if (root != null) for (String n : ROOT_DOCS) if (Files.isRegularFile(root.resolve(n))) out.put(n, root.resolve(n));
        return out;
    }

    static String ref() {
        if (!Version.isRelease()) return "main";
        String latest = Version.latestCached(), running = Version.string();
        return latest != null && latest.equals(running) ? "main" : "v" + running;
    }

    static Path kept(String ref) { return Config.home().resolve("help").resolve(ref.replaceAll("[^A-Za-z0-9._-]", "_")); }

    static String get(String url) {
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", "ResearchZosho/" + Version.string() + " (+https://researchzosho.org)")
                    .header("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "text/plain").GET().build();
            HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) { return null; }
    }

    /**
     * The documents of the version that is running, name to file: the source tree's, or the release's copy kept under the library's home,
     * fetched when there is none yet (and for main, when the copy is a day old). Offline, the copy kept; with none, nothing.
     */
    static synchronized Map<String, Path> files() {
        Map<String, Path> t = tree;
        if (t != null) return t;
        String ref = REF.get();
        Path dir = kept(ref), stamp = dir.resolve(".fetched");
        boolean fresh = Files.isRegularFile(stamp) && (!ref.equals("main") || age(stamp) < 24L * 3600 * 1000);
        if (!fresh) fetch(ref, dir);
        Map<String, Path> out = new TreeMap<>();
        if (Files.isDirectory(dir)) try (Stream<Path> s = Files.list(dir)) { for (Path p : s.toList()) { String n = p.getFileName().toString(); if (n.endsWith(".md")) out.put(n, p); } } catch (IOException ignored) { }
        return out;
    }

    private static long age(Path p) { try { return System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return Long.MAX_VALUE; } }

    /** The release's documents fetched from GitHub into {@code dir}: listed by GitHub where it can, else the known ones; what fails is kept as it was. */
    static void fetch(String ref, Path dir) {
        List<String> paths = new ArrayList<>();
        String listing = FETCH.apply("https://api.github.com/repos/" + REPO + "/contents/docs?ref=" + ref);
        try {
            JsonNode a = listing == null ? null : M.readTree(listing);
            if (a != null && a.isArray()) for (JsonNode f : a) { String n = f.path("name").asText(""); if (f.path("type").asText("").equals("file") && n.endsWith(".md") && !NOT_HELP.contains(n)) paths.add("docs/" + n); }
        } catch (IOException ignored) { }
        if (paths.isEmpty()) for (String n : DOCS) paths.add("docs/" + n);
        paths.addAll(ROOT_DOCS);
        int got = 0;
        try {
            Files.createDirectories(dir);
            for (String path : paths) {
                String body = FETCH.apply("https://raw.githubusercontent.com/" + REPO + "/" + ref + "/" + path);
                if (body == null || body.isBlank()) continue;
                Files.writeString(dir.resolve(path.substring(path.lastIndexOf('/') + 1)), body, StandardCharsets.UTF_8);
                got++;
            }
            if (got > 0) { Files.writeString(dir.resolve(".fetched"), Instant.now().toString(), StandardCharsets.UTF_8); sections = null; }
        } catch (IOException ignored) { }
    }

    /** Fetches the documentation now, off the caller's thread, so the first question finds it kept. */
    public static void prefetch() {
        if (tree != null) return;
        Thread t = new Thread(Help::files, "help-docs"); t.setDaemon(true); t.start();
    }

    /** Where the documentation of the version running is online. */
    static String online() { return "https://github.com/" + REPO + "/tree/" + REF.get() + "/docs"; }

    /** The documents there are, by name; empty when none could be read. */
    static List<String> docs() { return new ArrayList<>(files().keySet()); }

    /** One document's words, or "". */
    public static String doc(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]+\\.md")) return "";
        Path p = files().get(name);
        try { return p == null ? "" : Files.readString(p, StandardCharsets.UTF_8); } catch (IOException e) { return ""; }
    }

    /** Forgets the sections read, so the next question reads the documents again; for a test. */
    static void forget() { sections = null; }

    /** The documentation's sections, read once and kept until the documents change. */
    public static List<Section> sections() {
        List<Section> s = sections;
        if (s != null) return s;
        List<Section> out = new ArrayList<>();
        for (String d : docs()) out.addAll(split(d, doc(d)));
        return sections = List.copyOf(out);
    }

    /**
     * A document cut at its headings; a "#" inside a fenced block is not a heading. A section below the second level is named with the
     * heading above it ("0.6.6: Fixed", "2. Setup: Updating"), so a heading that says little on its own still says where it is.
     */
    static List<Section> split(String doc, String md) {
        List<Section> out = new ArrayList<>();
        String title = doc.replaceFirst("\\.md$", ""), heading = title;
        String[] above = new String[6];
        StringBuilder text = new StringBuilder();
        boolean fenced = false;
        for (String line : md.split("\\R")) {
            if (line.strip().startsWith("```")) fenced = !fenced;
            if (!fenced && line.matches("#{1,4} .+")) {
                if (text.toString().strip().length() >= 40) out.add(new Section(title, heading, text.toString().strip()));
                int level = line.indexOf(' ');
                String own = line.substring(level).strip();
                above[level] = own;
                for (int k = level + 1; k < above.length; k++) above[k] = null;
                String parent = null; for (int k = level - 1; k >= 1 && parent == null; k--) parent = above[k];
                heading = level >= 3 && parent != null ? parent + ": " + own : own;
                text.setLength(0);
                continue;
            }
            text.append(line).append('\n');
        }
        if (text.toString().strip().length() >= 40) out.add(new Section(title, heading, text.toString().strip()));
        return out;
    }

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "how", "what", "why", "when", "where", "which", "who", "does", "did", "are",
            "was", "can", "could", "should", "would", "you", "your", "its", "this", "that", "these", "those", "there", "here", "from", "about", "into", "than",
            "then", "not", "use", "using", "get", "make", "have", "has", "had", "will", "any", "all", "out", "one", "some", "want", "need", "way");

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3 && !STOP.contains(w)) out.add(w);
        return out;
    }

    /** The sections that answer a question best: its words weighed by how rare they are, three times over in a heading. */
    public static List<Section> find(String question, int max) {
        List<Section> all = sections();
        Set<String> q = new LinkedHashSet<>(words(question));
        if (q.isEmpty() || all.isEmpty()) return List.of();
        Map<String, Integer> df = new HashMap<>();
        List<List<String>> bodies = new ArrayList<>(), heads = new ArrayList<>();
        for (Section s : all) {
            List<String> b = words(s.text()), h = words(s.heading());
            bodies.add(b); heads.add(h);
            Set<String> seen = new LinkedHashSet<>(b); seen.addAll(h);
            for (String w : q) if (seen.contains(w)) df.merge(w, 1, Integer::sum);
        }
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            double score = 0;
            for (String w : q) {
                int n = df.getOrDefault(w, 0);
                if (n == 0) continue;
                double idf = Math.log(1 + (double) all.size() / n);
                long tf = bodies.get(i).stream().filter(w::equals).count(), hf = heads.get(i).stream().filter(w::equals).count();
                score += idf * (3 * Math.min(hf, 2) + (double) tf / (tf + 1.5));
            }
            if (score > 0) scored.add(new double[]{score, i});
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        List<Section> out = new ArrayList<>();
        for (double[] s : scored) { if (out.size() >= max) break; out.add(all.get((int) s[1])); }
        return out;
    }

    /** Whether a model server answers at this address, asked for its models within a few seconds. */
    static boolean answers(String base) {
        if (base == null || base.isBlank()) return false;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/v1/models")).timeout(Duration.ofSeconds(4)).GET();
            String key = DriveClient.keyFor(base);
            if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key.strip());
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) { return false; }
    }

    /** The web search as research runs meet it, in English: what a program reads (library_help). */
    static String webSearch() { return webSearch(Chrome.ENGLISH); }

    /** The web search as research runs meet it: Brave or not, whether a SearXNG answers a search, and what is left when neither does; in {@code c}'s language. */
    static String webSearch(Chrome c) {
        boolean brave = !Config.get("RESEARCHZOSHO_BRAVE_KEY", "").isBlank();
        String set = Config.get("RESEARCHZOSHO_SEARXNG", ""), base = set.isBlank() ? WebSearchTool.defaultEndpoint() : set.replaceAll("/+$", "");   // the address the search tool itself asks when none is set
        boolean searx = Searx.answers(base), fallback = !"off".equalsIgnoreCase(Config.get("RESEARCHZOSHO_FALLBACK_SEARCH", ""));
        List<String> said = new ArrayList<>();
        said.add(brave ? c.t("Web search: Brave is searched first, its key is set.") : c.t("Web search: there is no Brave key, so Brave is not searched."));
        if (searx) said.add(brave ? c.t("SearXNG at {0} answers searches, after Brave.", base) : c.t("SearXNG at {0} answers searches, so it is what research runs search with.", base));
        else {
            String own = Searx.state();
            said.add(own.equals("stopped") ? c.t("No SearXNG answers searches at {0}; the library's own is installed but stopped, and researchzosho search start starts it.", base)
                    : own.equals("running") ? c.t("No SearXNG answers searches at {0}; the library's own is running but did not answer a search.", base)
                    : c.t("No SearXNG answers searches at {0}; researchzosho search start installs the library's own.", base));
        }
        said.add(fallback ? c.t("Wikipedia's search is the last resort, on.") : c.t("Wikipedia's search is the last resort, off."));
        if (!brave && !searx) said.add(c.t("With only Wikipedia's search, research runs find much less of the web."));
        return String.join(" ", said);
    }

    /** The short form, in English: what a program reads. */
    public static String shortState() { return shortState(Chrome.ENGLISH); }

    /** The short form: the version, the model and whether it answers, the web search; in {@code c}'s language. */
    public static String shortState(Chrome c) {
        String drive = Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), model = Config.get("RESEARCHZOSHO_MODEL", "local-model");
        return c.t("ResearchZosho {0} is running here.", Version.string()) + " " + (answers(drive) ? c.t("The model is {0} at {1}, and it answers.", model, drive) : c.t("The model is {0} at {1}, and it does not answer now.", model, drive)) + " " + webSearch(c);
    }

    /** The live state, in English: what library_help and a program read. */
    public static String state(LibraryStore store) { return state(store, Chrome.ENGLISH); }

    /**
     * The live state, in sentences, in {@code c}'s language: version, holdings, model, search, fields, commands, pages. Each state is a whole
     * sentence of its own (the model answers or not, search by meaning on or off, each search place's state), so every language says it whole.
     */
    public static String state(LibraryStore store, Chrome c) {
        StringBuilder b = new StringBuilder(c.t("ResearchZosho {0} is running here.", Version.string()));
        try {
            ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "the person").put("runtime", "local");
            JsonNode st = new LibraryProtocol(store).status(a);
            int claims = st.path("counts").path("finding").path("total").asInt(0), runs = st.path("counts").path("investigation").asInt(0), pages = st.path("counts").path("raw").asInt(0);
            b.append(' ').append(c.t("This library holds claims: {0}, write-ups: {1}, pages kept from the web: {2}.", c.number(claims), c.number(runs), c.number(pages)));
            String meaning = switch (st.path("search_by_meaning").asText("")) {
                case "on" -> c.t("Search by meaning is on.");
                case "off" -> c.t("Search by meaning is off.");
                case "not answering" -> c.t("Search by meaning is not answering.");
                default -> "";
            };
            if (!meaning.isEmpty()) b.append(' ').append(meaning);
        } catch (Exception ignored) { }
        String drive = Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), model = Config.get("RESEARCHZOSHO_MODEL", "local-model");
        boolean up = answers(drive);
        b.append('\n').append(up ? c.t("The model it talks to by default is {0} at {1}, and it answers.", model, drive) : c.t("The model it talks to by default is {0} at {1}, and it does not answer now: research runs and the chat need it.", model, drive));
        List<String> named = new ArrayList<>(ModelEntries.all().keySet());
        if (!named.isEmpty()) b.append(' ').append(c.t("Named models it can also use: {0}.", String.join(c.sep(), named)));
        b.append('\n').append(webSearch(c));
        String semi = c.english() ? "; " : c.sep();   // the items of these lists hold commas of their own
        List<String> fields = new ArrayList<>();
        for (Profile p : Fields.enabled(store)) fields.add(c.tc("{0} ({1})", FIELD_ITEM, c.english() ? p.name() : c.tc(p.name(), Fields.NAME_MEANING), c.t(p.description())));
        if (!fields.isEmpty()) b.append('\n').append(c.t("Fields that are on: {0}.", String.join(semi, fields)));
        List<String> cmds = new ArrayList<>(List.of(c.t("/help, and words after it to look something up in the documentation"), "/model", c.t("/interview and a name"), "/suggest"));
        for (Profile p : Fields.enabled(store)) if (!p.chatCommandHelp().isBlank()) cmds.add(c.t(p.chatCommandHelp().strip()));
        b.append('\n').append(c.t("The chat's own commands: {0}.", String.join(semi, cmds)));
        List<String> pages = new ArrayList<>();
        for (String page : List.of("Home", "Ask", "Search", "Subjects", "Map", "Dossiers", "Inbox", "Open questions", "Runs", "Research", "Suggested", "Changes", "Chat", "Help", "Settings")) pages.add(c.t(page));
        b.append('\n').append(c.t("The pages: {0}.", c.and(pages)));
        List<String> own = new ArrayList<>(); for (Profile p : Fields.enabled(store)) for (Profile.Link l : p.menu()) own.add(c.t(l.label()));
        if (!own.isEmpty()) b.append(' ').append(c.t("The fields' own pages: {0}.", String.join(c.sep(), own)));
        return b.toString();
    }

    /** The meaning beside "{0} ({1})" in the list of fields: a field's name and what it does. */
    static final String FIELD_ITEM = "a field's name and what it does, in a list";

    /** What /help and some words answer, and the Help page, in English. */
    public static String lookup(LibraryStore store, String question) { return lookup(store, question, Chrome.ENGLISH); }

    /** What /help and some words answer, and the Help page, in {@code c}'s language: the documentation's sections first (as the documents are written), then what is running here, in short. */
    public static String lookup(LibraryStore store, String question, Chrome c) {
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) return answer(store, "", c);
        return sections(q, c) + "\n\n" + c.t("What is running here: {0}", shortState(c));
    }

    /** The sections that answer the question, with where to ask; or that the documentation says nothing on it. */
    static String sections(String q, Chrome c) {
        if (docs().isEmpty()) return missing(c);
        List<Section> found = find(q, 3);
        if (found.isEmpty()) return c.t("Its own documentation says nothing on \"{0}\". Questions it does not answer can be asked at {1}.", q, ISSUES);
        StringBuilder b = new StringBuilder(c.t("From its own documentation:"));
        for (Section s : found) {
            String t = s.text().length() > 1800 ? s.text().substring(0, 1800) + " …" : s.text();
            b.append("\n\n## ").append(s.doc()).append(": ").append(s.heading()).append("\n").append(t);
        }
        return b.append("\n\n").append(c.t("If this does not answer it, the documentation does not cover it: ask at {0}.", ISSUES)).toString();
    }

    /** What it says when it has no documentation to hand: why, and where it is online. */
    static String missing(Chrome c) {
        return c.t("This copy has no documentation to hand yet. An installed copy reads it from GitHub the first time it is needed and keeps it, and that has not worked from here; it is online at {0}. Questions it does not answer can be asked at {1}.", online(), ISSUES);
    }

    /** What library_help answers, in English: the whole live state, then the documentation's sections that answer the question, then where to ask. */
    public static String answer(LibraryStore store, String question) { return answer(store, question, Chrome.ENGLISH); }

    /** The same in {@code c}'s language: the Help page asked nothing. */
    public static String answer(LibraryStore store, String question, Chrome c) {
        StringBuilder b = new StringBuilder(state(store, c));
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) {
            List<String> names = new ArrayList<>(); for (String d : docs()) names.add(d.replaceFirst("\\.md$", ""));
            b.append("\n\n").append(names.isEmpty() ? missing(c) : c.t("Its documentation: {0}. Ask about anything in it.", String.join(c.sep(), names)));
            return b.toString();
        }
        return b.append("\n\n").append(sections(q, c)).toString();
    }
}
