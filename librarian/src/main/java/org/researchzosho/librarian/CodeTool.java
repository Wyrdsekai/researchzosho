package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.tools.Tool;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * {@code read_code}: the files of a repository the library holds under {@code raw/repos/}, read as they are on disk: list a folder,
 * search the files for a pattern, read a file with its line numbers. Offered to a run about such a repository (a survey's own run, or a
 * run of a field that reads code whose question names it), because its shelved summary is the README and the top of a few files, and a
 * README or a design document says what is planned as often as what is built. Read only, and only inside the repository's own folder: a
 * path that leaves it, by {@code ..} or by a link, is refused on every way in. A search stops at a number of files, of matching lines and
 * of seconds, and says so.
 */
public final class CodeTool implements Tool {

    static final int LIST_MAX = 300, GREP_MAX = 80, READ_LINES = 300, READ_CHARS = 24_000, LINE_CHARS = 240;
    static final long FILE_BYTES = 1_000_000;
    /** What one grep may take: files searched, matching lines counted, and time. */
    static final int GREP_FILES = 5_000, GREP_HITS = 2_000;
    static final long GREP_MILLIS = 10_000;

    private final Path repos;
    private final List<String> names;

    /** {@code names}: the repositories under the library's raw/repos/ this run may read. */
    public CodeTool(LibraryStore store, List<String> names) { this.repos = store.rawDir().resolve("repos"); this.names = List.copyOf(names); }

    /**
     * The repositories under raw/repos/ a question names: the one Surveys writes its runs about ("About the repository NAME (…"), and any
     * other whose folder name the question has as a whole word. A name shorter than four letters is too common a word to count. Which of
     * them a run may read is the run's to say ({@link Researcher#codeSettles}): a question that uses a name as an ordinary word is not about
     * the repository.
     */
    public static List<String> reposIn(LibraryStore store, String question) {
        List<String> out = new ArrayList<>();
        Path dir = store.rawDir().resolve("repos");
        if (question == null || !Files.isDirectory(dir)) return out;
        String q = question.toLowerCase(Locale.ROOT);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                String n = p.getFileName().toString();
                if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) || n.startsWith(".")) continue;
                boolean surveyed = q.startsWith("about the repository " + n.toLowerCase(Locale.ROOT) + " (");
                boolean named = n.length() >= 4 && Pattern.compile("(?<![\\p{L}\\p{N}_.-])" + Pattern.quote(n.toLowerCase(Locale.ROOT)) + "(?![\\p{L}\\p{N}_-])").matcher(q).find();
                if (surveyed || named) out.add(n);
            }
        } catch (IOException e) { return out; }
        return out;
    }

    /** A citation of a repository's file: the path, then its lines, as {@code :12}, {@code :40-44} or a forge's {@code #L12-L20}. */
    private static final Pattern LOCATOR = Pattern.compile("^raw/repos/([A-Za-z0-9._-]+)/(.+?)(?:(?::|#L)(\\d+)(?:-L?(\\d+))?)?$");

    /** A citation read: the repository, the path inside it with {@code .} and {@code ..} worked out, and the lines (null when none is given). */
    private record Cited(String repo, String path, String from, String to) { }

    private static Cited cited(String locator) {
        Matcher m = LOCATOR.matcher(locator == null ? "" : locator.strip());
        if (!m.matches() || m.group(1).equals(".") || m.group(1).equals("..")) return null;
        List<String> parts = new ArrayList<>();
        for (String part : m.group(2).replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (!part.equals("..")) parts.add(part);
            else if (parts.isEmpty()) return null;   // it climbs out of the repository
            else parts.remove(parts.size() - 1);
        }
        return parts.isEmpty() ? null : new Cited(m.group(1), String.join("/", parts), m.group(3), m.group(4));
    }

    /**
     * The file a citation of a repository's code names, without its lines ("raw/repos/NAME/src/Main.java"), written one way whichever
     * way the citation wrote it ({@code ./src//Main.java#L12}); null when it is not such a citation.
     */
    public static String fileOf(String locator) {
        Cited c = cited(locator);
        return c == null ? null : "raw/repos/" + c.repo() + "/" + c.path();
    }

    /**
     * The text a citation of a repository's file points to ("raw/repos/NAME/src/Main.java:40-52"): those lines, a few around them for
     * context, or the whole file when no line is given; null when it is not such a citation or the file is not inside the repository.
     */
    public static String textOf(LibraryStore store, String locator) {
        Cited c = cited(locator);
        if (c == null) return null;
        try {
            Path root = store.rawDir().resolve("repos").resolve(c.repo());
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return null;
            Path real = root.toRealPath(), at = inside(real, c.path());
            if (at == null || !Files.isRegularFile(at)) return null;
            String text = text(at);
            if (text == null || c.from() == null) return text;
            String[] lines = text.split("\n", -1);
            int from = Integer.parseInt(c.from()), to = c.to() == null ? from : Integer.parseInt(c.to());
            int a = Math.max(0, Math.min(from, to) - 1 - 5), b = Math.min(lines.length, Math.max(from, to) + 5);
            return a >= b ? text : String.join("\n", List.of(lines).subList(a, b));
        } catch (IOException | RuntimeException e) { return null; }
    }

    /**
     * The cloned file a forge's page of it shows ("https://github.com/tide/tidebook/blob/main/src/cache.rs#L12" is
     * "raw/repos/tidebook/src/cache.rs"), and the README for the repository's own page: one text, whichever way a run reached it. Read on
     * github.com, gitlab.com and codeberg.org, and only for a repository the library cloned from that address, as the clone's own record
     * of where it came from says; null for anything else.
     */
    public static String clonedFileOf(LibraryStore store, String locator) {
        if (locator == null || !locator.strip().startsWith("http")) return null;
        URI u;
        try { u = URI.create(locator.strip()); } catch (IllegalArgumentException e) { return null; }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        List<String> seg = new ArrayList<>();
        for (String s : (u.getPath() == null ? "" : u.getPath()).split("/")) if (!s.isEmpty()) seg.add(s);
        String forge = host, owner, repo, path = null;   // path "" is the repository's own page
        switch (host) {
            case "github.com", "raw.githubusercontent.com" -> {
                if (seg.size() < 2) return null;
                forge = "github.com"; owner = seg.get(0); repo = seg.get(1);
                List<String> rest = seg.subList(2, seg.size());
                if (host.startsWith("raw.")) {
                    int skip = !rest.isEmpty() && rest.get(0).equals("refs") ? 3 : 1;   // refs/heads/main/README.md, or main/README.md
                    if (rest.size() > skip) path = String.join("/", rest.subList(skip, rest.size()));
                } else if (rest.isEmpty() || (rest.size() == 2 && rest.get(0).equals("tree"))) path = "";
                else if ((rest.get(0).equals("blob") || rest.get(0).equals("raw")) && rest.size() > 2) path = String.join("/", rest.subList(2, rest.size()));
            }
            case "gitlab.com" -> {
                int dash = seg.indexOf("-");
                if (dash < 0) { if (seg.size() != 2) return null; owner = seg.get(0); repo = seg.get(1); path = ""; }
                else {
                    if (dash < 2) return null;
                    owner = String.join("/", seg.subList(0, dash - 1)); repo = seg.get(dash - 1);
                    List<String> rest = seg.subList(dash + 1, seg.size());
                    if (rest.size() == 2 && rest.get(0).equals("tree")) path = "";
                    else if (rest.size() > 2 && (rest.get(0).equals("blob") || rest.get(0).equals("raw"))) path = String.join("/", rest.subList(2, rest.size()));
                }
            }
            case "codeberg.org" -> {
                if (seg.size() < 2) return null;
                owner = seg.get(0); repo = seg.get(1);
                List<String> rest = seg.subList(2, seg.size());
                if (rest.isEmpty() || (rest.size() == 3 && rest.get(0).equals("src"))) path = "";
                else if (rest.size() > 3 && (rest.get(0).equals("src") || rest.get(0).equals("raw")) && List.of("branch", "commit", "tag").contains(rest.get(1)))
                    path = String.join("/", rest.subList(3, rest.size()));
            }
            default -> { return null; }
        }
        if (path == null) return null;
        // A forge reads the owner and the name without regard to capitals, and the clone's folder is named as the address it came from was
        // written. So the clone is the folder in raw/repos/ with that name in any capitals whose own record says it came from this address,
        // named as it is on disk. It is found in the folder's list, never by opening the address's spelling: a file system that ignores
        // capitals (macOS, Windows) opens that too and gives it back in the address's capitals, and one file would be cited two ways.
        String name = Repos.nameOf(repo);
        String want = (forge + "/" + owner + "/" + repo).toLowerCase(Locale.ROOT).replaceFirst("\\.git$", "");
        Path repos = store.rawDir().resolve("repos"), clone;
        try (Stream<Path> s = Files.isDirectory(repos) ? Files.list(repos) : Stream.empty()) {
            clone = s.filter(p -> p.getFileName().toString().equalsIgnoreCase(name) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing((Path p) -> !p.getFileName().toString().equals(name)).thenComparing(p -> p.getFileName().toString()))
                    .filter(p -> want.equals(origin(p))).findFirst().orElse(null);
        } catch (IOException e) { return null; }
        if (clone == null) return null;
        if (path.isEmpty()) {
            // the README the forge shows on the repository's page: README.md before README.txt or a README with no extension
            Comparator<Path> shown = Comparator.comparing((Path p) -> !p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")).thenComparing(p -> p.getFileName().toString());
            try (Stream<Path> s = Files.list(clone)) {
                Path readme = s.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("readme"))
                        .sorted(shown).findFirst().orElse(null);
                if (readme == null) return null;
                path = readme.getFileName().toString();
            } catch (IOException e) { return null; }
        }
        return fileOf("raw/repos/" + clone.getFileName() + "/" + path);
    }

    /** Where a clone came from, as its own git record says, written host/owner/name in small letters ("github.com/tide/tidebook"); null when it does not say. */
    static String origin(Path clone) {
        Path config = clone.resolve(".git").resolve("config");
        String s;
        try { s = Files.isRegularFile(config) ? Files.readString(config, StandardCharsets.UTF_8) : null; } catch (IOException e) { return null; }
        if (s == null) return null;
        Matcher m = Pattern.compile("\\[remote \"origin\"\\][^\\[]*?\\burl\\s*=\\s*(\\S+)").matcher(s);
        if (!m.find()) return null;
        return m.group(1).toLowerCase(Locale.ROOT).replaceFirst("^[a-z+]+://", "").replaceFirst("^[^@/]+@", "").replaceFirst("^([^/:]+):", "$1/")
                .replaceFirst("^www\\.", "").replaceAll("/+$", "").replaceFirst("\\.git$", "");
    }

    @Override public String name() { return "read_code"; }

    @Override public String description() {
        return "Read the files of the repository " + String.join(", ", names) + " as they are on disk. op=list shows the files under a folder; "
                + "op=grep searches every file under a folder for a pattern and returns path:line: text for each hit; op=read shows a file with its line numbers, "
                + "from_line on. Use it for what the software actually does: find the code with grep, read it, and cite the file and lines it showed (raw/repos/<repository>/<path>:<line>).";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("op").put("type", "string").put("description", "list, grep or read.").putArray("enum").add("list").add("grep").add("read");
        ArrayNode repo = props.putObject("repo").put("type", "string").put("description", "The repository.").putArray("enum");
        names.forEach(repo::add);
        props.putObject("path").put("type", "string").put("description", "A folder (list, grep) or a file (read), relative to the repository; empty for the top.");
        props.putObject("pattern").put("type", "string").put("description", "For grep: a word or a regular expression, matched without regard to case.");
        props.putObject("from_line").put("type", "integer").put("description", "For read: the first line to show (default 1).");
        p.putArray("required").add("op");
        return p;
    }

    @Override public String execute(JsonNode args) throws IOException {
        String repo = args.path("repo").asText("").strip();
        if (repo.isEmpty() && names.size() == 1) repo = names.get(0);
        if (!names.contains(repo)) return "ERROR: give repo, one of: " + String.join(", ", names) + ".";
        Path root = repos.resolve(repo);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return "ERROR: the repository " + repo + " is not in the library's raw/repos/ folder.";
        Path real = root.toRealPath();
        String rel = args.path("path").asText("").strip().replace('\\', '/').replaceAll("^\\./", "");
        Path at = inside(real, rel);
        if (at == null) return "ERROR: " + rel + " is outside the repository " + repo + ". Paths are relative to its top folder.";
        String locator = "raw/repos/" + repo + "/";
        return switch (args.path("op").asText("")) {
            case "list" -> list(real, at, locator);
            case "grep" -> grep(real, at, locator, args.path("pattern").asText(""), GREP_FILES, GREP_HITS, GREP_MILLIS);
            case "read" -> read(real, at, locator, Math.max(1, args.path("from_line").asInt(1)));
            default -> "ERROR: op is list, grep or read.";
        };
    }

    /**
     * The path inside the repository, or null when it is not: an absolute path, a {@code ..} that climbs out, or a link whose target is
     * outside. A path that does not exist is returned as it is, and the operation says so.
     */
    static Path inside(Path real, String rel) {
        if (rel.contains("\0")) return null;
        Path p;
        try { p = rel.isEmpty() ? real : real.resolve(rel).normalize(); } catch (RuntimeException e) { return null; }
        if (Path.of(rel.isEmpty() ? "." : rel).isAbsolute() || !p.startsWith(real)) return null;
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return p;
        try { Path r = p.toRealPath(); return r.startsWith(real) ? r : null; } catch (IOException e) { return null; }
    }

    private static String relative(Path real, Path p) { return real.relativize(p).toString().replace('\\', '/'); }

    /** The files a walk may show: regular files, not links, not in a folder of build output or of the version history. */
    private static List<Path> files(Path real, Path from) throws IOException { return files(real, from, Integer.MAX_VALUE); }

    /** The same, at most {@code limit} of them: the walk stops there, and a folder it does not show is not entered. In order of path. */
    static List<Path> files(Path real, Path from, int limit) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path part : real.relativize(from)) if (Repos.SKIP_DIRS.contains(part.toString())) return out;
        Files.walkFileTree(from, new SimpleFileVisitor<>() {   // a walk does not follow links: a linked folder is not entered
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                return !dir.equals(from) && Repos.SKIP_DIRS.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                if (a.isRegularFile()) out.add(file);
                return out.size() >= limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) { return FileVisitResult.CONTINUE; }
        });
        out.sort(null);
        return out;
    }

    private static String list(Path real, Path at, String locator) throws IOException {
        if (!Files.isDirectory(at)) return Files.exists(at) ? "ERROR: " + relative(real, at) + " is a file: read it with op=read." : "ERROR: there is no folder " + relative(real, at) + ".";
        List<Path> all = files(real, at);
        StringBuilder b = new StringBuilder("files under " + locator + relative(real, at) + " (" + all.size() + "):\n");
        for (Path p : all.subList(0, Math.min(LIST_MAX, all.size()))) b.append(relative(real, p)).append("  (").append(Files.size(p)).append(" bytes)\n");
        if (all.size() > LIST_MAX) b.append("… and ").append(all.size() - LIST_MAX).append(" more: list a folder further down.\n");
        return b.toString();
    }

    /** A grep that stops at {@code maxFiles} files, {@code maxHits} matching lines or {@code millis} milliseconds, and says which. */
    static String grep(Path real, Path at, String locator, String pattern, int maxFiles, int maxHits, long millis) throws IOException {
        if (pattern.isBlank()) return "ERROR: grep needs a pattern.";
        Pattern re;
        try { re = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
        catch (PatternSyntaxException e) { re = Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
        if (!Files.exists(at)) return "ERROR: there is no folder or file " + relative(real, at) + ".";
        long deadline = System.nanoTime() + millis * 1_000_000L;
        List<Path> all = Files.isDirectory(at) ? files(real, at, maxFiles + 1) : List.of(at);
        boolean moreFiles = all.size() > maxFiles;
        if (moreFiles) all = all.subList(0, maxFiles);
        StringBuilder b = new StringBuilder();
        int hits = 0, filesHit = 0, searched = 0;
        String cut = null;
        search:
        for (Path p : all) {
            if (System.nanoTime() > deadline) { cut = "it had run for " + seconds(millis); break; }
            String text = text(p);
            searched++;
            if (text == null) continue;
            boolean any = false;
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                boolean found;
                try { found = re.matcher(new Timed(lines[i], deadline)).find(); }
                catch (TimeUp e) { cut = "it had run for " + seconds(millis); if (any) filesHit++; break search; }
                if (!found) continue;
                if (hits < GREP_MAX) b.append(relative(real, p)).append(':').append(i + 1).append(": ").append(clip(lines[i].strip())).append('\n');
                hits++; any = true;
                if (hits >= maxHits) { cut = "it had found " + maxHits + " matching lines"; filesHit++; break search; }
            }
            if (any) filesHit++;
        }
        if (cut == null && moreFiles) cut = "it had searched " + maxFiles + " files, and there are more";
        String stopped = cut == null ? "" : "\nThe search stopped before it had searched every file, because " + cut + " (" + searched + " files searched). "
                + "Search a folder further down (path), or with a pattern that matches fewer lines.";
        if (hits == 0) return "no line under " + locator + relative(real, at) + " matches " + pattern + " (" + searched + " files searched)." + stopped;
        return hits + " line(s) in " + filesHit + " file(s) under " + locator + relative(real, at) + " match " + pattern + (hits > GREP_MAX ? "; the first " + GREP_MAX + ":" : ":") + "\n"
                + Fence.wrap("SOURCE CODE", b.toString()) + "\n" + Fence.rule("SOURCE CODE") + stopped;
    }

    private static String seconds(long millis) { return millis >= 1000 && millis % 1000 == 0 ? millis / 1000 + " seconds" : millis + " milliseconds"; }

    /** A line a pattern is matched against that ends the match when the time is up: a pattern that backtracks without end cannot hold the run. */
    private static final class Timed implements CharSequence {
        private final CharSequence s;
        private final long deadline;
        private int reads;
        Timed(CharSequence s, long deadline) { this.s = s; this.deadline = deadline; }
        @Override public char charAt(int i) {
            if ((++reads & 0x3FF) == 0 && System.nanoTime() > deadline) throw new TimeUp();
            return s.charAt(i);
        }
        @Override public int length() { return s.length(); }
        @Override public CharSequence subSequence(int a, int b) { return new Timed(s.subSequence(a, b), deadline); }
        @Override public String toString() { return s.toString(); }
    }

    private static final class TimeUp extends RuntimeException { TimeUp() { super(null, null, false, false); } }

    private static String read(Path real, Path at, String locator, int from) throws IOException {
        if (!Files.exists(at)) return "ERROR: there is no file " + relative(real, at) + ". op=list shows the files.";
        if (Files.isDirectory(at)) return "ERROR: " + relative(real, at) + " is a folder: list it with op=list.";
        String text = text(at);
        if (text == null) return "ERROR: " + relative(real, at) + " is not text, or larger than " + FILE_BYTES / 1000 + " kB.";
        String[] lines = text.split("\n", -1);
        if (from > lines.length) return "ERROR: " + relative(real, at) + " has " + lines.length + " lines.";
        StringBuilder b = new StringBuilder();
        int last = from - 1;
        for (int i = from - 1; i < lines.length && i < from - 1 + READ_LINES && b.length() < READ_CHARS; i++) { b.append(String.format("%5d  ", i + 1)).append(lines[i]).append('\n'); last = i + 1; }
        return locator + relative(real, at) + " lines " + from + "-" + last + " of " + lines.length + ":\n" + Fence.wrap("SOURCE CODE", b.toString()) + "\n" + Fence.rule("SOURCE CODE")
                + (last < lines.length ? "\n(op=read with from_line " + (last + 1) + " continues.)" : "");
    }

    /** A file's text, or null when it is too large or not text. */
    private static String text(Path p) {
        try {
            if (Files.size(p) > FILE_BYTES) return null;
            byte[] bytes = Files.readAllBytes(p);
            for (int i = 0; i < Math.min(bytes.length, 8000); i++) if (bytes[i] == 0) return null;
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(bytes)).toString().replace("\r\n", "\n");
        } catch (CharacterCodingException e) { return null; } catch (IOException e) { return null; }
    }

    private static String clip(String s) { return s.length() <= LINE_CHARS ? s : s.substring(0, LINE_CHARS) + "…"; }
}
