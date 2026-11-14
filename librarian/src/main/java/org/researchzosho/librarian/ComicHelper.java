package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The comic helper (0.7.5): it reads the pages of a comic and sets a translation into the balloons, for {@link ComicTranslation}. A
 * Python package of the program's own ({@code comic/} beside this class, started through {@code comic_helper.py}) that finds the text and
 * the balloons on a page (the detector of ogkalu/comic-text-and-bubble-detector, on ONNX Runtime), says which writing system a comic is
 * lettered in, reads the lettering with the reader of the comic's language (manga-ocr for Japanese, with furigana and sideways numbers
 * read by geometry; the manifest's other readers for Chinese, Korean and the alphabets), tells speech from text outside a balloon and
 * from a drawn sound word, cuts a web comic's strip into pieces and joins them again, and sets text back into a balloon's inside at one
 * size a page, in lines or in columns. It runs on this machine's processor and calls no model server: no page leaves the machine
 * through it.
 *
 * <p>Kept under {@code <home>/comic}: the package's files, a Python environment of its own, and the files its manifest names (the
 * models, the fonts, the hyphenation patterns), each fetched from its source at a fixed revision and checked against its checksum.
 * Unlike the scorers and the likeness helper it is never set up in the background: it is set up on a person's yes to one question that
 * names each thing fetched with its size and its licence ({@link #question}), asked where a comic is first translated, or by
 * {@code researchzosho translate comics install}, which ends by loading every piece once ({@link #check}) and leaves its marker only
 * when each loaded; the same command, asked of a helper that is set up, loads them again and puts right what does not load.
 * {@code RESEARCHZOSHO_COMIC=off} leaves it out. Its files follow the release
 * ({@link #refresh}): at the daemon's start, by {@code translate comics status|install}, and each time its process is started. What
 * the question says, what {@code status --list} lists and what a set-up installs all come from two places: the manifest, and the one
 * list of Python packages ({@link #PYTHON_PACKAGES}); a manifest that grows needs no second edit here. A helper that an earlier release
 * set up fetches what this release adds without a second question, unless that is a large download or a new environment
 * ({@link Needs#asks}).
 *
 * <p>One process a job, modelled on MetricX-24's: started at the first page, one request a line on its stdin and one answer a line on
 * its stdout ({@code comic/serve.py}), the answers read by a thread of the process's own, a wait that always ends, one restart when the
 * process dies, ended when the job closes it or a stop is asked. The seam for a helper served by another machine is {@link Reader}:
 * the translation asks it and nothing else; only the local process stands behind it in this version.
 */
final class ComicHelper {
    private ComicHelper() { }

    private static final Logger log = LoggerFactory.getLogger(ComicHelper.class);
    private static final ObjectMapper M = new ObjectMapper();

    static final String SETTING = "RESEARCHZOSHO_COMIC", PYTHON_SETTING = "RESEARCHZOSHO_COMIC_PYTHON", DIR_SETTING = "RESEARCHZOSHO_COMIC_DIR", THREADS_SETTING = "RESEARCHZOSHO_COMIC_THREADS";
    static final String RESOURCES = "/org/researchzosho/librarian/comic/";
    /** The helper's files as the build ships them, by their place under {@link #RESOURCES} and under {@link #dir()}; a test holds this list against the folder. */
    static final List<String> FILES = List.of("comic_helper.py", "comic/__init__.py", "comic/__main__.py", "comic/balloon.py", "comic/check.py", "comic/columns.py", "comic/crops.py", "comic/detect.py", "comic/digits.py",
            "comic/furigana.py", "comic/geometry.py", "comic/hyphen.py", "comic/kinsoku.py", "comic/lines.py", "comic/manifest.json", "comic/models.py", "comic/picture.py", "comic/read.py", "comic/reader.py",
            "comic/script.py", "comic/serve.py", "comic/setter.py", "comic/soundwords.py", "comic/strip.py", "comic/vertical.py");
    static final String MANIFEST = "comic/manifest.json";
    /**
     * One Python package of the helper's environment: the name pip installs it by; what a person calls it; its licence in a few words,
     * and what else is to be said of it; and for a package that does not come from PyPI, its index and what the marker calls that.
     */
    record Package(String pip, String said, String licence, String aside, String index, String indexSaid) {
        Package(String pip, String said, String licence) { this(pip, said, licence, "", "", ""); }
        /** The name Python imports it by, which the check loads it by: pip's name with its hyphens as underscores, but for the three whose module is named otherwise. */
        String module() { return switch (pip) { case "opencv-python-headless" -> "cv2"; case "pillow" -> "PIL"; case "pyyaml" -> "yaml"; default -> pip.replace('-', '_'); }; }
    }

    /**
     * The Python packages the helper's environment holds: THE one list. The pip commands of a set-up, the marker that says what a
     * helper was set up with ({@link #PACKAGES}), the list of pieces ({@link #PACKAGES_SAID}) and the API's licences are all made from
     * it; a package the helper comes to need is one line here.
     */
    static final List<Package> PYTHON_PACKAGES = List.of(
            new Package("torch", "PyTorch for processors", "BSD-3-Clause", "", "https://download.pytorch.org/whl/cpu", "the CPU index"),
            new Package("manga-ocr", "manga-ocr", "Apache 2.0"),
            // the words of a Japanese line and the dictionary they are told by: the setter ends a line where a phrase ends by them. They come
            // with manga-ocr today; named here so that a manga-ocr that stops requiring them does not take the Japanese lettering's line ends away
            new Package("fugashi", "fugashi", "MIT", "the MeCab it carries is under BSD-3-Clause", "", ""),
            new Package("unidic-lite", "the Japanese dictionary unidic-lite", "MIT", "the dictionary itself is under BSD-3-Clause", "", ""),
            new Package("onnxruntime", "ONNX Runtime", "MIT"),
            new Package("opencv-python-headless", "OpenCV", "Apache 2.0", "its package carries FFmpeg libraries under the LGPL, which the helper does not use", "", ""),
            new Package("pillow", "Pillow", "MIT-CMU"),
            new Package("numpy", "NumPy", "BSD-3-Clause"),
            new Package("pyyaml", "PyYAML", "MIT"),   // the line readers' character lists are YAML; in the environment already as a requirement of transformers, and named so that it does not hang on that
            new Package("budoux", "BudouX", "Apache 2.0"));   // where a phrase ends in Chinese, so that a line ends there; pure Python, and pip takes the release that fits the environment's Python (0.7 on 3.9, 0.9 on 3.10)

    /** The packages as the marker names them, those from an index of their own first: a change of the list brings the environment to it at the next set-up. */
    static final String PACKAGES = packagesLine(PYTHON_PACKAGES);

    static String packagesLine(List<Package> packages) {
        List<String> own = new ArrayList<>(), pypi = new ArrayList<>();
        for (Package p : packages) { if (p.index().isEmpty()) pypi.add(p.pip()); else own.add(p.pip() + " (" + p.indexSaid() + ")"); }
        return String.join(", ", own) + (own.isEmpty() || pypi.isEmpty() ? "" : "; ") + String.join(" ", pypi);
    }

    /** The pip installs of a set-up, as the packages after {@code pip install …}: one for each index of its own, then one for PyPI's. */
    static List<List<String>> pipInstalls(List<Package> packages) {
        Map<String, List<String>> byIndex = new LinkedHashMap<>();
        for (Package p : packages) if (!p.index().isEmpty()) byIndex.computeIfAbsent(p.index(), x -> new ArrayList<>()).add(p.pip());
        List<List<String>> out = new ArrayList<>();
        for (var e : byIndex.entrySet()) { List<String> one = new ArrayList<>(e.getValue()); one.add("--index-url"); one.add(e.getKey()); out.add(one); }
        List<String> pypi = new ArrayList<>();
        for (Package p : packages) if (p.index().isEmpty()) pypi.add(p.pip());
        if (!pypi.isEmpty()) out.add(pypi);
        return out;
    }
    /**
     * What those packages take, measured in a fresh environment (Linux x86_64, Python 3.13, 2026-11-13): torch's processor build with
     * the environment itself 941 MB, the rest 769 MB (manga-ocr with transformers and the dictionary unidic-lite, ONNX Runtime,
     * OpenCV); pip's downloads for them 449 MB.
     */
    static final long PACKAGES_DOWNLOAD = 450_000_000L, PACKAGES_DISK = 1_700_000_000L;
    /** How the program names itself to the servers the files are fetched from. */
    static final String USER_AGENT = "ResearchZosho-research/1.0 (https://researchzosho.org)";
    /** The processor threads the helper takes unless {@link #THREADS_SETTING} says another number: the number its speed was measured with. */
    static final int THREADS = 4;

    // ---- the manifest ----

    /**
     * One thing the manifest names: its name there; its kind (model, font, hyphenation, words: a language's word list); what a person calls it ({@code title}, a proper
     * name, and {@code said}, in plain words); its licence in a few words; a hyphenation entry's language and a font's writing systems
     * (a list with spaces between: latin, cyrillic, greek, japanese, chinese-simplified, chinese-traditional, korean); the bytes of
     * its files together, and where they lie under the models folder.
     */
    record Item(String name, String kind, String title, String said, String licence, String language, String script, long bytes, List<String> paths) { }

    private static volatile List<Item> manifest;
    /** The files the manifest names, each with its checksum, in one string: what the marker's {@code files} line is the hash of. */
    private static volatile String fetched = "";
    /** Where the manifest is read from: the build's file. A test stands in one of its own with {@link #manifestFrom}, so that what is said of a manifest is held to a manifest the test wrote. */
    private static volatile Supplier<byte[]> manifestBytes = () -> resource(MANIFEST);

    /** The manifest read from {@code bytes} from now on (null: the build's again); what was read before is forgotten. For tests. */
    static void manifestFrom(Supplier<byte[]> bytes) { manifestBytes = bytes == null ? () -> resource(MANIFEST) : bytes; manifest = null; }

    /** The build's file, or null when it is not in this build. */
    static byte[] resource(String file) { try (InputStream in = ComicHelper.class.getResourceAsStream(RESOURCES + file)) { return in == null ? null : in.readAllBytes(); } catch (IOException e) { return null; } }

    /** The manifest as the build ships it. */
    static List<Item> manifest() {
        List<Item> m = manifest;
        if (m != null) return m;
        List<Item> out = new ArrayList<>();
        StringBuilder files = new StringBuilder();
        try {
            byte[] b = manifestBytes.get();
            for (JsonNode e : M.readTree(b == null ? "{}".getBytes(StandardCharsets.UTF_8) : b).path("models")) {
                long bytes = 0; List<String> paths = new ArrayList<>();
                for (JsonNode f : e.path("files")) { bytes += f.path("size").asLong(); paths.add(f.path("path").asText()); files.append(f.path("path").asText()).append(' ').append(f.path("sha256").asText()).append('\n'); }
                out.add(new Item(e.path("name").asText(), e.path("kind").asText("model"), e.path("title").asText(e.path("name").asText()), e.path("said").asText(""), e.path("licence_said").asText(e.path("licence").asText("")),
                        e.path("language").asText(""), e.path("script").asText(""), bytes, List.copyOf(paths)));
            }
        } catch (IOException e) { /* a build without it: nothing to fetch, and the install says the file is missing */ }
        fetched = files.toString();
        return manifest = List.copyOf(out);
    }

    // ---- its place on the disk ----

    static boolean on() { return !Config.get(SETTING, "").strip().equalsIgnoreCase("off"); }
    static Path dir() { String set = Config.get(DIR_SETTING, "").strip(); return set.isEmpty() ? Config.home().resolve("comic") : Path.of(set); }
    static Path script() { return dir().resolve("comic_helper.py"); }
    static Path models() { return dir().resolve("models"); }
    static Path ready() { return dir().resolve("ready"); }
    /** Where the helper's process writes what its libraries print, for the day it does not start. */
    static Path lastLog() { return dir().resolve("last-run.log"); }
    static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    static Path python() {
        String set = Config.get(PYTHON_SETTING, "").strip();
        if (!set.isEmpty()) return Path.of(set);
        return dir().resolve("venv").resolve(windows() ? "Scripts" : "bin").resolve(windows() ? "python.exe" : "python");
    }
    static int threads() { try { return Math.max(1, Integer.parseInt(Config.get(THREADS_SETTING, String.valueOf(THREADS)).strip())); } catch (NumberFormatException e) { return THREADS; } }

    /** The writing systems a font's manifest entry may name, by family: the characters of East Asia, and the alphabets. */
    static final List<String> CJK_SCRIPTS = List.of("japanese", "chinese-simplified", "chinese-traditional", "korean"), ALPHABETS = List.of("latin", "cyrillic", "greek");

    /**
     * The fonts for a target written in {@code script} (latin, cyrillic, greek, japanese, chinese-simplified, chinese-traditional,
     * korean), in the order the helper tries them for a block: the target's own first; then, for Chinese, the font of its other
     * character set; then the others of its family (a Japanese name in a Korean line, a Greek word in an English one); then the rest
     * (a Latin word in a Chinese line that its own font has no letters for). Within each step, the manifest's order.
     */
    static List<Path> fonts(String script) {
        List<String> family = CJK_SCRIPTS.contains(script) ? CJK_SCRIPTS : ALPHABETS;
        String sibling = script.equals("chinese-simplified") ? "chinese-traditional" : script.equals("chinese-traditional") ? "chinese-simplified" : "";
        List<List<Path>> steps = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (Item i : manifest()) {
            if (!i.kind().equals("font") || i.paths().isEmpty()) continue;
            List<String> has = List.of(i.script().split(" "));
            int step = has.contains(script) ? 0 : has.contains(sibling) ? 1 : has.stream().anyMatch(family::contains) ? 2 : 3;
            steps.get(step).add(models().resolve(i.paths().get(0)));
        }
        List<Path> out = new ArrayList<>();
        for (List<Path> s : steps) out.addAll(s);
        return out;
    }

    /** The languages the helper has hyphenation patterns for, by code. */
    static List<String> hyphenated() { List<String> out = new ArrayList<>(); for (Item i : manifest()) if (i.kind().equals("hyphenation")) out.add(i.language()); return out; }

    /**
     * What the marker says the helper was set up with: the packages, and the files fetched with their checksums. A release that names
     * other packages, another file or another revision of one asks again; one that only words its manifest otherwise does not.
     */
    static String markerText() { manifest(); return "packages " + PACKAGES + "\nfiles " + PdfReading.sha(fetched.getBytes(StandardCharsets.UTF_8)) + "\n"; }
    static boolean packagesCurrent() { try { return Files.isRegularFile(ready()) && Files.readString(ready(), StandardCharsets.UTF_8).startsWith("packages " + PACKAGES + "\n"); } catch (IOException e) { return false; } }
    /** Whether a thing the manifest names is in the models folder: each of its files there. (Its checksum was checked when it was fetched, and the helper checks the models' again when it loads them.) */
    static boolean have(Item i) { for (String p : i.paths()) if (!Files.isRegularFile(models().resolve(p))) return false; return true; }
    /** Whether the helper's environment is there, from whatever release: its Python and its starting file. */
    static boolean present() { return Files.isRegularFile(script()) && Files.exists(python()); }

    /** Whether the helper is set up as this release has it; a test stands it in. */
    static volatile BooleanSupplier INSTALLED = ComicHelper::installedNow;
    static boolean installed() { return INSTALLED.getAsBoolean(); }
    static boolean installedNow() {
        try { if (!present() || !Files.isRegularFile(ready()) || !Files.readString(ready(), StandardCharsets.UTF_8).equals(markerText())) return false; } catch (IOException e) { return false; }
        for (Item i : manifest()) if (!have(i)) return false;
        return true;
    }

    /**
     * Why the helper cannot be used on this machine whatever is fetched, in a sentence; "" when it can: it is off, the machine is set to
     * run no heavy helper, or its environment is still to be made and no Python on the machine is one its packages install on. Said
     * before the question and before anything is fetched, wherever a set-up is offered.
     */
    static String barred(Chrome c) {
        if (!on()) return c.t("The comic helper is off ({0}=off), and a comic's pages are read by nothing else: turn it on with researchzosho settings set comic on, and ask again.", SETTING);
        if (!Helpers.localAllowed()) return c.t("This machine is set to run no heavy helper (local_helpers off), and the comic helper runs only on the library's own machine in this version: researchzosho settings set local_helpers on allows it here.");
        if (Config.get(PYTHON_SETTING, "").isBlank() && !installed() && venvToMake()) {
            Pythons p = PYTHONS.get();
            if (p.chosen() == null) return pythonSaid(c, Os.now(), p.seen(), brew());
        }
        return "";
    }

    // ---- the Python its environment is made with ----

    /**
     * The Pythons the helper's packages install on, as minor versions of Python 3, read from the packages' own metadata on PyPI
     * (2026-11-13). The oldest is manga-ocr's own floor (Requires-Python &gt;=3.9); there pip takes the last release of each other
     * package that still installs (PyTorch 2.8, NumPy 2.0, ONNX Runtime 1.19, transformers 4.57), and with those the helper was set up
     * and a comic translated on macOS with Apple's Python 3.9.6. The newest is the newest every package with compiled code has a wheel
     * for: for 3.15 ONNX Runtime 1.32 has none and no source to build from, and fugashi and PyYAML have none, so pip would fail after
     * PyTorch's download. Of the Pythons a machine has, the newest in between is taken, which gets the packages' current releases.
     */
    static final int PYTHON_OLDEST = 9, PYTHON_NEWEST = 14;

    enum Os {
        LINUX, MAC, WINDOWS;
        static Os now() { String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT); return n.contains("mac") || n.contains("darwin") ? MAC : n.contains("win") ? WINDOWS : LINUX; }
    }

    /**
     * A Python found on this machine: the command that runs it, its version (3.13.1), its own file, and whether it makes an environment
     * (its venv and ensurepip modules load: Debian and Ubuntu ship them apart, as python3.12-venv).
     */
    record Python(List<String> command, String version, String executable, boolean venv) {
        int minor() { String[] v = version.split("\\."); try { return v.length > 1 && v[0].equals("3") ? Integer.parseInt(v[1]) : -1; } catch (NumberFormatException e) { return -1; } }
        /** A version the packages install on. */
        boolean supported() { return minor() >= PYTHON_OLDEST && minor() <= PYTHON_NEWEST; }
        boolean fits() { return venv && supported(); }
        /** What makes the environment: the Python's own file, which a launcher (py -3.13) names; the command itself when it said none. */
        List<String> venvCommand() { return executable.isBlank() ? command : List.of(executable); }
    }

    /** What the search found: the Python the environment is made with, null when none fits; and every other Python it saw, for the sentence that says why none fits. */
    record Pythons(Python chosen, List<Python> seen) { }

    /**
     * The search, stood in by tests. The suite's default ({@code researchzosho.comic.pythons=off}) finds a Python 3.13 it never runs, so
     * that no test reads the machine's Pythons; a test of the search stands in its own.
     */
    static volatile Supplier<Pythons> PYTHONS = "off".equals(System.getProperty("researchzosho.comic.pythons"))
            ? () -> new Pythons(new Python(List.of(windows() ? "python" : "python3"), "3.13.0", "", true), List.of()) : ComicHelper::pythonsNow;

    private static volatile Pythons lastFound;
    private static volatile long lastFoundAt;

    /** The machine's Pythons searched, the answer kept for a minute: the plan, the question and the set-up each ask, and a Python installed meanwhile is found at the next ask. */
    static Pythons pythonsNow() {
        Pythons p = lastFound;
        if (p != null && System.currentTimeMillis() - lastFoundAt < 60_000) return p;
        p = search(candidates(Os.now(), pathDirs(), System.getenv(), Files::isRegularFile), ComicHelper::probeNow);
        lastFound = p; lastFoundAt = System.currentTimeMillis();
        return p;
    }

    /** The folders of the PATH, in order; Windows writes some in quotes. */
    static List<Path> pathDirs() {
        List<Path> out = new ArrayList<>();
        for (String d : System.getenv().getOrDefault(windows() ? "Path" : "PATH", System.getenv().getOrDefault("PATH", "")).split(File.pathSeparator)) {
            String s = d.strip().replace("\"", "");
            if (s.isEmpty()) continue;
            try { out.add(Path.of(s)); } catch (InvalidPathException ignored) { }
        }
        return out;
    }

    /**
     * The commands the search tries, the newest Python first. On Windows the py launcher's {@code py -3.14} down to {@code py -3.9} with
     * python.org's own folders beside each, then each python.exe on the PATH, then the launcher's default (to name what is there). On
     * macOS and Linux {@code python3.14} down to {@code python3.9}, each on the PATH and then in the places a person's Python is put
     * (Homebrew's and python.org's on macOS, /usr/local/bin and /usr/bin on Linux), which a service's short PATH lacks; then
     * {@code python3} on the PATH.
     * Apple's /usr/bin/python3 is never run: without the developer tools it opens a dialog that offers them. The tools' own Python is
     * run instead, where they are there.
     */
    static List<List<String>> candidates(Os os, List<Path> path, Map<String, String> env, Predicate<Path> exists) {
        LinkedHashSet<List<String>> out = new LinkedHashSet<>();
        if (os == Os.WINDOWS) {
            Path py = null;
            List<Path> launchers = new ArrayList<>();
            for (Path d : path) launchers.add(d.resolve("py.exe"));
            String root = env.getOrDefault("SystemRoot", env.getOrDefault("WINDIR", "C:\\Windows")), local = env.getOrDefault("LOCALAPPDATA", ""), programs = env.getOrDefault("ProgramFiles", "C:\\Program Files"), drive = env.getOrDefault("SystemDrive", "C:");
            launchers.add(Path.of(root, "py.exe"));
            if (!local.isEmpty()) launchers.add(Path.of(local, "Programs", "Python", "Launcher", "py.exe"));
            for (Path l : launchers) if (exists.test(l)) { py = l; break; }
            for (int m = PYTHON_NEWEST; m >= PYTHON_OLDEST; m--) {
                if (py != null) out.add(List.of(py.toString(), "-3." + m));
                List<Path> own = new ArrayList<>();
                if (!local.isEmpty()) own.add(Path.of(local, "Programs", "Python", "Python3" + m, "python.exe"));
                own.add(Path.of(programs, "Python3" + m, "python.exe"));
                own.add(Path.of(drive + "\\", "Python3" + m, "python.exe"));
                for (Path p : own) if (exists.test(p)) out.add(List.of(p.toString()));
            }
            for (Path d : path) { Path p = d.resolve("python.exe"); if (exists.test(p)) out.add(List.of(p.toString())); }
            if (py != null) out.add(List.of(py.toString(), "-3"));
            return List.copyOf(out);
        }
        for (int m = PYTHON_NEWEST; m >= PYTHON_OLDEST; m--) {
            String name = "python3." + m;
            List<Path> dirs = new ArrayList<>(path);
            if (os == Os.MAC) dirs.addAll(List.of(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"), Path.of("/Library/Frameworks/Python.framework/Versions/3." + m + "/bin")));
            else dirs.addAll(List.of(Path.of("/usr/local/bin"), Path.of("/usr/bin")));
            for (Path d : dirs) { Path p = d.resolve(name); if (!appleShim(os, p) && exists.test(p)) out.add(List.of(p.toString())); }
        }
        for (Path d : path) { Path p = d.resolve("python3"); if (!appleShim(os, p) && exists.test(p)) out.add(List.of(p.toString())); }
        if (os == Os.MAC) for (Path p : List.of(Path.of("/Library/Developer/CommandLineTools/usr/bin/python3"), Path.of("/Applications/Xcode.app/Contents/Developer/usr/bin/python3"))) if (exists.test(p)) out.add(List.of(p.toString()));
        return List.copyOf(out);
    }

    /** Apple's stand-in for Python in /usr/bin, which asks to install the developer tools when they are not there. */
    private static boolean appleShim(Os os, Path p) { return os == Os.MAC && p.getParent() != null && p.getParent().toString().equals("/usr/bin"); }

    /** What a Python says of itself: its version, its own file, and then whether its venv and ensurepip load (the exit code). In UTF-8, so that a folder's name in any alphabet comes through on Windows. */
    static final String PROBE = "import sys; print('%d.%d.%d' % tuple(sys.version_info[:3])); print(sys.executable); sys.stdout.flush(); import venv, ensurepip";

    /** The Pythons tried in order: the first that fits is chosen; the others seen are kept for the sentence. A command that does not start, or says no version, is passed over. */
    static Pythons search(List<List<String>> candidates, Function<List<String>, Searx.Result> run) {
        List<Python> seen = new ArrayList<>();
        for (List<String> c : candidates) {
            List<String> cmd = new ArrayList<>(c); cmd.addAll(List.of("-I", "-X", "utf8", "-c", PROBE));
            Searx.Result r = run.apply(cmd);
            if (r == null) continue;
            String[] lines = r.out().strip().split("\\R");
            if (lines.length == 0 || !lines[0].strip().matches("\\d+\\.\\d+\\.\\d+")) continue;
            Python p = new Python(c, lines[0].strip(), lines.length > 1 ? lines[1].strip() : "", r.code() == 0);
            if (p.fits()) return new Pythons(p, List.copyOf(seen));
            seen.add(p);
        }
        return new Pythons(null, List.copyOf(seen));
    }

    /** A probe run: its exit code and what it printed, or null when it did not start or did not end within fifteen seconds. */
    static Searx.Result probeNow(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(15, TimeUnit.SECONDS)) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); return null; }
            return new Searx.Result(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) { return null; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }

    /** Whether Homebrew is on this machine, so that the sentence can name its command. */
    static boolean brew() { return Files.isRegularFile(Path.of("/opt/homebrew/bin/brew")) || Files.isRegularFile(Path.of("/usr/local/bin/brew")) || pathDirs().stream().anyMatch(d -> Files.isRegularFile(d.resolve("brew"))); }

    /**
     * The one sentence when no Python here fits: what the helper runs on, what this machine has, and what to install, by the machine's
     * kind: Homebrew's command where Homebrew is, python.org's installer on Windows and on a Mac without it, the system's packages on
     * Linux. Python 3.13 is the one named: the newest that python.org offers may be past what the packages install on.
     */
    static String pythonSaid(Chrome c, Os os, List<Python> seen, boolean brew) {
        String oldest = "3." + PYTHON_OLDEST, newest = "3." + PYTHON_NEWEST;
        Python noVenv = seen.stream().filter(p -> p.supported() && !p.venv()).findFirst().orElse(null);
        if (noVenv != null) return c.t("The comic helper runs on Python {0} to {1}, and this machine's Python {2} lacks its venv module: install it from the system's packages (python3.{3}-venv on Debian and Ubuntu), and ask again.", oldest, newest, noVenv.version(), noVenv.minor());
        String install = os == Os.LINUX ? c.t("install Python 3.13 with its venv module from the system's packages")
                : os == Os.MAC && brew ? c.t("install Python 3.13 with brew install python@3.13") : c.t("install Python 3.13 with the installer from python.org");
        List<String> versions = seen.stream().map(Python::version).distinct().toList();
        if (versions.isEmpty()) return c.t("The comic helper runs on Python {0} to {1}, and this machine has no Python 3: {2}, and ask again.", oldest, newest, install);
        return c.t("The comic helper runs on Python {0} to {1}, and this machine has only Python {2}: {3}, and ask again.", oldest, newest, c.and(versions), install);
    }

    /** The version of the Python the helper's environment was made with, from its pyvenv.cfg ({@code version = 3.9.6}, or {@code version_info = 3.13.1.final.0}); "" when that cannot be read. */
    static String venvVersion() {
        try {
            for (String l : Files.readAllLines(dir().resolve("venv").resolve("pyvenv.cfg"), StandardCharsets.UTF_8)) {
                Matcher m = VENV_VERSION.matcher(l);
                if (m.find()) return m.group(1);
            }
        } catch (IOException | RuntimeException e) { /* none there, or not readable */ }
        return "";
    }
    private static final Pattern VENV_VERSION = Pattern.compile("^\\s*version(?:_info)?\\s*=\\s*(\\d+\\.\\d+\\.\\d+)");

    /** Whether a set-up makes the helper's environment: none is there, or the one there was made by a Python its packages do not install on (one that cannot be read is kept). */
    static boolean venvToMake() {
        if (!Files.exists(python())) return true;
        String v = venvVersion();
        return !v.isEmpty() && !new Python(List.of(), v, "", true).supported();
    }

    /** A size as the question says it: whole kilobytes and megabytes, a gigabyte to one decimal (56 KB, 444 MB, 1.7 GB). */
    static String size(long bytes) {
        if (bytes < 1_000_000) return String.format(Locale.ROOT, "%.0f KB", Math.max(1, bytes / 1e3));
        if (bytes < 1_000_000_000) return String.format(Locale.ROOT, "%.0f MB", bytes / 1e6);
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
    }

    // ---- the one question ----

    /**
     * The most a helper that was set up before fetches without a second question, in bytes of download: what a release adds to the
     * manifest (fonts, a reader for another writing system) is fetched as the first step of the next comic, each piece said, when it is
     * under this; more is asked for as the first set-up was. Chosen, not measured: a third of what the first yes was given to.
     */
    static final long QUIET_FETCH = 300_000_000L;

    /**
     * What a set-up would do now: the manifest's things not yet in the models folder; whether pip is to be run (the environment is new,
     * or this release's list of packages is not the one it was brought to); whether the environment itself is to be made, which is the
     * large download; and the bytes to download and on the disk.
     */
    record Needs(List<Item> items, boolean packages, boolean environment, long download, long disk) {
        boolean nothing() { return items.isEmpty() && !packages; }
        long itemBytes() { long n = 0; for (Item i : items) n += i.bytes(); return n; }
        /**
         * Whether a set-up is the person's to say yes to. It is when the environment is to be made (the packages, a gigabyte and more on
         * the disk) or the files to fetch are more than {@link #QUIET_FETCH}: the first set-up, and a release that adds something large.
         * It is not when an earlier release's helper stands and this release only adds to it: then the run fetches what is missing first
         * and says each piece.
         */
        boolean asks() { return environment || itemBytes() > QUIET_FETCH; }
        /** What a quiet set-up says before it starts: what this release adds, by kind and size. */
        String addsSaid() {
            long models = 0, fonts = 0, patterns = 0, words = 0; int nm = 0, nf = 0, np = 0, nw = 0;
            for (Item i : items) { if (i.kind().equals("font")) { fonts += i.bytes(); nf++; } else if (i.kind().equals("hyphenation")) { patterns += i.bytes(); np++; } else if (i.kind().equals("words")) { words += i.bytes(); nw++; } else { models += i.bytes(); nm++; } }
            List<String> parts = new ArrayList<>();
            if (nm > 0) parts.add(nm + (nm == 1 ? " model (" : " models (") + size(models) + ")");
            if (nf > 0) parts.add(nf + (nf == 1 ? " font (" : " fonts (") + size(fonts) + ")");
            if (np > 0) parts.add(np + (np == 1 ? " set of hyphenation patterns (" : " sets of hyphenation patterns (") + size(patterns) + ")");
            if (nw > 0) parts.add(nw + (nw == 1 ? " word list (" : " word lists (") + size(words) + ")");
            if (packages) parts.add("its Python packages brought to this release's list");
            return Chrome.ENGLISH.and(parts);
        }
    }

    static Needs needs() {
        List<Item> items = new ArrayList<>();
        long bytes = 0;
        for (Item i : manifest()) if (!have(i)) { items.add(i); bytes += i.bytes(); }
        boolean own = Config.get(PYTHON_SETTING, "").isBlank(), environment = own && venvToMake(), packages = own && (environment || !packagesCurrent());
        return new Needs(List.copyOf(items), packages, environment, bytes + (environment ? PACKAGES_DOWNLOAD : 0), bytes + (environment ? PACKAGES_DISK : 0));
    }

    /** Whether a set-up was finished here before, by whatever release: its marker is there. A helper short of something with no marker is a first set-up that was interrupted, not an earlier release's. */
    static boolean setUpBefore() { return Files.isRegularFile(ready()); }

    /** Whether a comic can be translated with no question about the helper: it is set up, or what it lacks is fetched without one ({@link Needs#asks}). */
    static boolean readyOrQuiet() { return installed() || !needs().asks(); }

    /**
     * What the person is told before the one question, in three sentences for somebody with a comic to read and no wish to study a
     * list: what the helper is and that it runs here; what a set-up fetches, by group with its size (the models, the fonts and
     * patterns, the Python packages), the sum, where it is kept and what the machine must have; and that all of it is under open
     * licences, with the command that names each piece ({@link #pieces}). The question itself is {@link #ask}.
     */
    static List<String> question(Chrome c) {
        Needs n = needs();
        long models = 0, small = 0, words = 0;
        for (Item i : n.items()) { if (i.kind().equals("model")) models += i.bytes(); else if (i.kind().equals("words")) words += i.bytes(); else small += i.bytes(); }
        List<String> groups = new ArrayList<>();
        if (models > 0) groups.add(c.t("its models ({0})", size(models)));
        if (small > 0) groups.add(c.t("the lettering fonts and hyphenation patterns ({0})", small < 1_000_000 ? c.t("under 1 MB") : size(small)));
        if (words > 0) groups.add(c.t("its word lists ({0})", size(words)));   // a kind the manifest came to name: the words of a language, by which a line is not ended inside one
        if (n.environment()) groups.add(c.t("Python packages in an environment of its own (about {0} to download, {1} on the disk)", size(PACKAGES_DOWNLOAD), size(PACKAGES_DISK)));
        return List.of(c.t("Reading a comic's pages needs the comic helper, which is not set up on this machine yet. It reads the lettering on each page and puts the translation into the speech balloons; it runs on this machine's processor, and no page leaves the machine for it."),
                c.t("Setting it up fetches, once: {0}. In all {1} to download and {2} on the disk, kept under {3}. Python 3 must be on this machine.", c.and(groups), size(n.download()), size(n.disk()), dir()),
                c.t("All of it is under open licences; researchzosho translate comics status --list names each piece with its licence."));
    }

    /** The question after those sentences, with the language's own yes-or-no hint. */
    static String ask(Chrome c) { return c.yesNo(c.t("Set the comic helper up now?")); }

    /** The same sentences in one paragraph, for a reply that has no lines of its own (the chat's offer, a page's notice, an API's refusal). */
    static String questionSaid(Chrome c) { return PdfReading.sentences(c, question(c)); }

    /** What the packages of the list bring with them, said after them: the ones a person may want to know of. */
    static final String PACKAGES_NEED = "and what these need, among them transformers (Apache 2.0) and tqdm (MPL 2.0 and MIT)";
    /** The licences among them that are not permissive ones, for the API's one line. */
    static final String PACKAGES_ASIDE = "LGPL (FFmpeg libraries inside OpenCV's package, not used); MPL 2.0 (tqdm)";

    /** The Python packages by name with their licences, as the list of pieces says them (in English, as a status is). */
    static final String PACKAGES_SAID = packagesSaid(PYTHON_PACKAGES);

    static String packagesSaid(List<Package> packages) {
        List<String> out = new ArrayList<>();
        for (Package p : packages) out.add(p.said() + " (" + p.licence() + (p.aside().isEmpty() ? "" : "; " + p.aside()) + ")");
        return String.join(", ", out) + ", " + PACKAGES_NEED;
    }

    /** The packages' licences, each once, in the list's order. */
    static String packageLicences(List<Package> packages) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Package p : packages) out.add(p.licence());
        return String.join(", ", out) + "; " + PACKAGES_ASIDE;
    }

    /**
     * Every piece a set-up fetches, one line each, with its size, its licence and whether it is here already: the two models, each
     * language's hyphenation patterns, the fonts, and the Python packages. What {@code translate comics status --list} prints, in
     * English as a status is; the guide has the same table.
     */
    static List<String> pieces() {
        List<String> out = new ArrayList<>();
        for (Item i : manifest()) out.add((have(i) ? "here        " : "to fetch    ") + (i.kind().equals("hyphenation") ? "hyphenation patterns for " + ReadIn.name(i.language()) : i.said() + ": " + i.title()) + ", " + size(i.bytes()) + ", licence " + i.licence());
        Needs n = needs();
        String whole = "Python packages in an environment of the helper's own, about " + size(PACKAGES_DOWNLOAD) + " to download and " + size(PACKAGES_DISK) + " on the disk: " + PACKAGES_SAID;
        out.add(n.environment() ? "to install  " + whole : n.packages() ? "to add to   " + packagesAdded() : "here        " + whole);
        return out;
    }

    /** The packages the helper was set up with, by pip's names, as its marker's first line has them; none when there is no marker. */
    static List<String> markerPackages() {
        try {
            if (!Files.isRegularFile(ready())) return List.of();
            String first = Files.readString(ready(), StandardCharsets.UTF_8).lines().findFirst().orElse("");
            if (!first.startsWith("packages ")) return List.of();
            return Arrays.stream(first.substring("packages ".length()).replaceAll("\\([^)]*\\)", " ").split("[;,\\s]+")).filter(x -> !x.isEmpty()).toList();
        } catch (IOException e) { return List.of(); }
    }

    /**
     * The packages' line in the list of pieces for an environment that stands and is to be brought to this release's list. It names
     * what the list adds to the one the helper was set up with, which is all pip installs, and then only where the environment lacks
     * it; the line of a first set-up, with the whole environment's sizes, said 450 MB to download of a helper that was to get BudouX.
     */
    static String packagesAdded() {
        String stands = "Python packages in the helper's own environment, which stands: ";
        List<String> had = markerPackages();
        if (had.isEmpty()) return stands + "its set-up was not finished, and pip installs what the environment still lacks of this release's list";
        List<String> added = new ArrayList<>();
        for (Package p : PYTHON_PACKAGES) if (!had.contains(p.pip())) added.add(p.said() + " (" + p.licence() + (p.aside().isEmpty() ? "" : "; " + p.aside()) + ")");
        if (added.isEmpty()) return stands + "this release's list names no package it was not set up with, and pip looks through the list once";
        return stands + "this release's list adds " + Chrome.ENGLISH.and(added) + "; pip installs those the environment lacks, and its other packages stay as they are";
    }

    // ---- the set-up ----

    /** A command run to its end, each line it prints handed on as it comes; its input closed at once. A test stands it in. */
    interface Run { Searx.Result run(List<String> cmd, Consumer<String> line) throws Exception; }

    static volatile Run RUN = ComicHelper::runNow;

    /**
     * What the helper's processes are started with beside the machine's own environment: the libraries it loads its models through keep
     * what they keep under the helper's folder, not in the person's own cache folder, and are told not to look on the network (the models
     * are files in the folder, fetched and checked by the set-up).
     */
    static void environment(Map<String, String> env) {
        env.put("HF_HOME", dir().resolve("cache").resolve("hf").toString());
        // PyTorch makes a folder for its compiler's cache when the models load, in the machine's temporary folder unless told (seen on
        // macOS and on Windows, 2026-11-13); it is empty, and it is the helper's
        env.put("TORCHINDUCTOR_CACHE_DIR", dir().resolve("cache").resolve("torchinductor").toString());
        // and pip keeps no copy of what it downloads in the person's own cache folder: --no-cache-dir on its command line does not reach
        // the pip that builds a package from its source (unidic-lite) in a process of its own, and this does (macOS, 2026-11-13)
        env.put("PIP_NO_CACHE_DIR", "1");
        env.put("PIP_DISABLE_PIP_VERSION_CHECK", "1");
        // and what a Python started without -I compiles (the pip that venv runs to fill a new environment, the pip that builds a package
        // from its source) goes under the helper's folder: the Python of Apple's developer tools otherwise writes it to
        // ~/Library/Caches/com.apple.python, 133 MB for this environment that nothing ever read again (macOS, 2026-11-14)
        env.put("PYTHONPYCACHEPREFIX", pycache().toString());
        env.put("HF_HUB_OFFLINE", "1");
        env.put("TRANSFORMERS_OFFLINE", "1");
    }

    /** Where a Python started without -I keeps what it compiles during a set-up; removed when the set-up ends, since the helper runs with -I and never reads it. */
    static Path pycache() { return dir().resolve("cache").resolve("pycache"); }

    /** Where a set-up's commands keep their temporary files: under the helper's folder, made before the first of them and removed when the set-up ends, however it ends. */
    static Path setupTemp() { return dir().resolve("cache").resolve("tmp"); }

    /**
     * What a set-up's commands are started with: {@link #environment}, and the temporary folder of the set-up's own. The pip that the
     * Python of Apple's developer tools carries leaves a copy of its certificates in the temporary folder each time an environment is
     * made (macOS, 2026-11-14), and pip builds a package from its source there; none of it is the person's to find later.
     */
    static void setupEnvironment(Map<String, String> env) {
        environment(env);
        String tmp = setupTemp().toString();
        env.put("TMPDIR", tmp); env.put("TEMP", tmp); env.put("TMP", tmp);
    }

    static Searx.Result runNow(List<String> cmd, Consumer<String> line) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        setupEnvironment(pb.environment());   // RUN runs a set-up's commands and nothing else
        Process p = pb.start();
        p.getOutputStream().close();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) { out.append(l).append('\n'); line.accept(l); }
        }
        return new Searx.Result(p.waitFor(), out.toString());
    }

    /** The usable room on the disk under the library's home, or -1 when it cannot be read; a test stands it in. */
    static volatile LongSupplier FREE = TranslationScores::freeBytesNow;

    /** The set-up, stood in by tests: "ready", or "!" and the reason. */
    static volatile Function<Consumer<String>, String> INSTALL = ComicHelper::installNow;
    /** One set-up at a time: a job that sets the helper up and a set-up asked from a page wait for each other, and the second finds it done. */
    private static final Object SETTING_UP = new Object();
    static String install(Consumer<String> say) {
        synchronized (SETTING_UP) { return installed() ? "ready" : INSTALL.apply(say); }
    }

    private static volatile Thread setupThread;

    /**
     * The helper set up in the background, asked from a page: "started", "running" when one runs already, or "" when there is nothing to
     * set up here (it is set up, or this machine may not run it: {@link #barred}). What it fetches goes to the service's log.
     */
    static synchronized String setupInBackground() {
        Thread t = setupThread;
        if (t != null && t.isAlive()) return "running";
        if (installed() || !barred(Chrome.ENGLISH).isEmpty()) return "";
        t = new Thread(() -> { String r = install(line -> log.info("comic helper: {}", line)); log.info("comic helper: {}", r); }, "comic-helper-setup");
        t.setDaemon(true); setupThread = t; t.start();
        return "started";
    }

    /** Whether a set-up asked from a page runs now. */
    static boolean setupRunning() { Thread t = setupThread; return t != null && t.isAlive(); }

    /** Waits for a background set-up to end; for tests. */
    static void awaitSetup() throws InterruptedException { Thread t = setupThread; if (t != null) t.join(60_000); }

    /**
     * The command that runs the helper: its own environment in Python's isolated mode, or a Python the person named, with its own site
     * packages and no folder of its own on the path; and in Python's UTF-8 mode, so the lines it reads and writes are UTF-8 whatever the
     * machine's locale is (a service's is often none, and Windows' is a code page: the Japanese it read would not come through).
     */
    static List<String> command(Path py, String... more) {
        List<String> cmd = new ArrayList<>(List.of(py.toString()));
        if (Config.get(PYTHON_SETTING, "").isBlank()) cmd.add("-I"); else cmd.addAll(List.of("-E", "-P"));
        cmd.addAll(List.of("-X", "utf8"));
        cmd.add(script().toString());
        cmd.addAll(List.of(more));
        return cmd;
    }

    /** The command of the long-lived process: the models, the threads, the Latin font with the others after it (each request to set names its own target's fonts), the hyphenation patterns. */
    static List<String> serveCommand() {
        List<Path> fonts = fonts("latin");
        List<String> more = new ArrayList<>(List.of("serve", "--models", models().toString(), "--threads", String.valueOf(threads()), "--hyphenation", models().resolve("hyphenation").toString()));
        if (!fonts.isEmpty()) { more.add("--font"); more.add(fonts.get(0).toString()); }
        for (Path f : fonts.subList(Math.min(1, fonts.size()), fonts.size())) { more.add("--more-font"); more.add(f.toString()); }
        return command(python(), more.toArray(new String[0]));
    }

    /** Writes the build's files over the helper's; false when the build lacks one. */
    private static boolean writeFiles() throws IOException {
        Files.createDirectories(dir().resolve("comic"));
        for (String f : FILES) { byte[] b = resource(f); if (b == null) return false; Files.write(dir().resolve(f), b); }
        return true;
    }

    /**
     * Sets the helper up: its files, a Python environment with its packages, the manifest's files fetched and checked, and every piece
     * loaded once to be sure ({@link #check}); "ready", or "!" and what stopped it. {@code say} gets a line before each step, and one
     * for each file fetched. What is there already is kept: a second run fetches only what is missing. The marker is written only
     * when every piece loaded; when one did not, the sentence names it, and the next set-up installs anew the packages that sentence
     * named ({@link #unloaded}) and fetches again any file that is not as the manifest has it. The commands' temporary files, and what
     * a Python started without -I compiles, are kept in folders of the set-up's own, removed when it ends, however it ends.
     */
    static synchronized String installNow(Consumer<String> say) {
        try {
            String barred = barred(Chrome.ENGLISH);
            if (!barred.isEmpty()) return "!" + barred;
            Needs n = needs();
            long free = FREE.getAsLong();
            if (free >= 0 && free < n.disk() + (256L << 20)) return "!the disk under " + Config.home() + " has " + size(free) + " free and the comic helper needs about " + size(n.disk()) + ", so nothing was fetched";
            if (!writeFiles()) return "!the comic helper's files are not in this build";
            Files.createDirectories(setupTemp());
            try { return setUp(n, say); }
            finally { for (Path made : List.of(setupTemp(), pycache())) try { Checkpoint.deleteTree(made); } catch (IOException ignored) { /* left in the helper's own folder */ } }
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /**
     * pip as a set-up runs it: by the environment's own Python in its isolated mode, as the helper itself is run, so that what pip
     * compiles of the packages lies beside them in the environment, where the helper's Python reads it. Run as the environment's
     * {@code pip} script it was not isolated, and the Python of Apple's developer tools then wrote all of it to its own cache folder,
     * 133 MB outside the helper's that the helper never read: it compiled every module again at each start (macOS, 2026-11-14).
     * --no-cache-dir: pip keeps no copy of its downloads in the person's own cache folder, where they outlived the helper (565 MB on a
     * Mac, 2026-11-13) and were in no size the question says; everything the set-up leaves is under the helper's folder.
     */
    static List<String> pip(Path py, String... more) {
        List<String> cmd = new ArrayList<>(List.of(py.toString(), "-I", "-m", "pip", "install", "--quiet", "--no-cache-dir", "--disable-pip-version-check"));
        cmd.addAll(List.of(more));
        return cmd;
    }

    private static String setUp(Needs n, Consumer<String> say) throws Exception {
        Path py = python();
        if (n.packages()) {
            Path venv = dir().resolve("venv");
            List<String> anew = unloaded();
            if (venvToMake()) {
                Python base = PYTHONS.get().chosen();
                if (base == null) return "!" + barred(Chrome.ENGLISH);
                if (Files.exists(venv)) { say.accept("The helper's environment was made by Python " + venvVersion() + ", which its packages do not install on; making it again."); Checkpoint.deleteTree(venv); }
                say.accept("Making the helper's own Python environment in " + venv + ", with Python " + base.version() + (base.executable().isBlank() ? "" : " (" + base.executable() + ")") + ".");
                Searx.Result v;
                List<String> make = new ArrayList<>(base.venvCommand()); make.addAll(List.of("-m", "venv", venv.toString()));
                try { v = RUN.run(make, l -> { }); }
                catch (IOException e) { return "!Python 3 was not found on this machine: install Python 3 with its venv module, and ask again"; }
                if (v.code() != 0) return "!Python's venv could not make " + venv + ": " + TranslationScores.last(v.out()) + " (Python 3 with venv is needed)";
                anew = List.of();   // a new environment holds nothing of the one whose packages did not load
            }
            // one line for what is done to the packages, and a true one: a new environment gets them all; a helper an earlier release set up
            // (its marker names another list) is brought to this release's; a set-up that was interrupted is finished; a repair says its
            // own line below and no other, since no list changed
            String packagesSaid = n.environment() ? "Installing its Python packages (a download of about " + size(PACKAGES_DOWNLOAD) + "); this takes some minutes."
                    : setUpBefore() ? "Bringing its Python packages to this release's list." : anew.isEmpty() ? "Finishing the install of its Python packages." : "";
            if (!packagesSaid.isEmpty()) say.accept(packagesSaid);
            if (!anew.isEmpty()) {
                // what the last check found not loading is installed anew: pip takes a package whose record stands for installed and
                // leaves it, though its files are gone or do not load. One package by itself; every one, with what they need, where
                // the reason named no package of the list
                boolean all = anew.contains(ALL_PACKAGES);
                List<Package> which = all ? PYTHON_PACKAGES : PYTHON_PACKAGES.stream().filter(p -> unloadedHas(p)).toList();
                say.accept("Installing anew what did not load at the last set-up: " + (all ? "every Python package (a download of about " + size(PACKAGES_DOWNLOAD) + ")" : Chrome.ENGLISH.and(which.stream().map(Package::said).toList())) + ".");
                for (List<String> packages : pipInstalls(which)) {
                    List<String> cmd = all ? pip(py, "--force-reinstall") : pip(py, "--force-reinstall", "--no-deps");
                    cmd.addAll(packages);
                    Searx.Result p = RUN.run(cmd, l -> { });
                    if (p.code() != 0) return "!installing " + Chrome.ENGLISH.and(packages.stream().filter(x -> !x.startsWith("--") && !x.startsWith("http")).toList()) + " anew into " + venv + " failed: " + TranslationScores.last(p.out());
                }
            }
            // --upgrade only into an environment made now: in one an earlier release made, pip then installs what the list adds and
            // leaves the rest, where --upgrade would fetch every package's newest release again, a download nobody was told of
            for (List<String> packages : pipInstalls(PYTHON_PACKAGES)) {
                List<String> cmd = n.environment() ? pip(py, "--upgrade") : pip(py);
                cmd.addAll(packages);
                Searx.Result p = RUN.run(cmd, l -> { });
                if (p.code() != 0) return "!installing " + Chrome.ENGLISH.and(packages.stream().filter(x -> !x.startsWith("--") && !x.startsWith("http")).toList()) + " into " + venv + " failed: " + TranslationScores.last(p.out());
            }
        }
        long bytes = 0; for (Item i : n.items()) bytes += i.bytes();
        if (!n.items().isEmpty()) say.accept("Fetching " + fetchingSaid(n.items()) + " (" + size(bytes) + ").");
        Searx.Result f = RUN.run(command(py, "fetch", "--to", models().toString(), "--user-agent", USER_AGENT), l -> { String s = fetchedSaid(l); if (!s.isEmpty()) say.accept(s); });
        if (f.code() != 0) return "!the models could not be fetched: " + TranslationScores.last(f.out()) + ". What was fetched is kept; asking again fetches the rest";
        say.accept("Loading every piece once, to be sure it works.");
        Checked c = check();
        if (!c.passed()) { noteUnloaded(c); return "!" + c.said(); }
        Files.deleteIfExists(unloadedNote());
        if (c.seconds() >= SAID_FROM) say.accept("  Every piece loads; that took " + Math.round(c.seconds()) + " s.");
        Files.writeString(ready(), markerText(), StandardCharsets.UTF_8);
        return "ready";
    }

    // ---- the check: every piece loaded once ----

    /** A check that took this many seconds or more says how long it took: under it nobody waited. */
    static final double SAID_FROM = 5;

    /**
     * One piece that did not load: its name (a manifest thing's, or the name a Python package is imported by), its kind (model, font,
     * hyphenation, words, package), the helper's reason in one line, and the module an import failure named ("" for any other reason).
     */
    record Unloaded(String piece, String kind, String why, String module) {
        /** The piece as a person is told it: a package by its own name, a manifest's thing as the list of pieces says it. */
        String said() {
            if (kind.equals("package")) { for (Package p : PYTHON_PACKAGES) if (p.module().equals(piece)) return p.said(); return "the Python package " + piece; }
            Item i = byName().get(piece);
            if (i == null) return piece;
            return i.kind().equals("hyphenation") ? "hyphenation patterns for " + ReadIn.name(i.language()) : i.said() + " (" + i.title() + ")";
        }
        /** Whether a file of its own is the reason: missing, or not as it was fetched. The next set-up's fetch brings it again. */
        boolean file() { return why.startsWith("its file"); }
    }

    /**
     * What a check found: how many pieces it tried, the ones that did not load, the seconds it took, and the helper's last words when
     * it gave no answer at all (its process did not start, or ended before its last line).
     */
    record Checked(int tried, List<Unloaded> failed, double seconds, String broke) {
        boolean passed() { return broke.isEmpty() && failed.isEmpty(); }
        /**
         * What did not load, each piece with the helper's reason, in a clause with no command in it. A piece that failed on a module
         * of a package of the list is said as that package, once: with PyYAML gone, manga-ocr, which imports it, fails for it too,
         * and "manga-ocr does not load (No module named 'yaml'); PyYAML does not load (No module named 'yaml')" named one cause twice.
         */
        String what() {
            if (!broke.isEmpty()) return "the check of its pieces gave no answer (" + broke + ")";
            LinkedHashSet<String> each = new LinkedHashSet<>();
            for (Unloaded u : failed) {
                String module = u.module().split("\\.")[0], cause = u.said();
                for (Package p : PYTHON_PACKAGES) if (!module.isEmpty() && p.module().equals(module)) cause = p.said();
                each.add(cause + " does not load (" + u.why() + ")");
            }
            return String.join("; ", each);
        }
        /** Whether a set-up can put it right: always, but for a package of a Python the person named, which is theirs to install there. */
        boolean mendable() { return Config.get(PYTHON_SETTING, "").isBlank() || broke.isEmpty() && failed.stream().allMatch(Unloaded::file); }
        /** The same as {@link #what}, ending a sentence begun elsewhere ("The comic helper was not set up: …"), with what puts it right. */
        String said() {
            return what() + (!mendable() ? "; the helper runs on the Python that " + PYTHON_SETTING + " names, where its packages are yours to install"
                    : !broke.isEmpty() ? "; researchzosho translate comics install runs it again" : "; researchzosho translate comics install puts right what does not load");
        }
    }

    /** The command of the check: every package of the one list named by the module it is imported by, and the models folder, whose manifest names the rest. */
    static List<String> checkCommand() {
        List<String> more = new ArrayList<>(List.of("check", "--models", models().toString(), "--threads", String.valueOf(threads())));
        for (Package p : PYTHON_PACKAGES) { more.add("--import"); more.add(p.module()); }
        return command(python(), more.toArray(new String[0]));
    }

    /**
     * Every piece loaded once ({@code comic/check.py}): each Python package imported, then each thing of the manifest loaded as the
     * helper loads it at work (the page detector, every reader, every font, the patterns, the word list). Until 0.7.5's last days the
     * set-up started the helper and took its first line for proof; since the readers load at their first page, that line proved the
     * page detector alone, and a set-up whose PyYAML or PyTorch did not import said "set up" (macOS, 2026-11-14).
     */
    static Checked check() {
        // asked outside a set-up (translate comics install, of a helper that is set up), the check has the set-up's temporary folder for its own length
        boolean made = false;
        try { if (present() && !Files.isDirectory(setupTemp())) { Files.createDirectories(setupTemp()); made = true; } } catch (IOException ignored) { /* the machine's own is used */ }
        try { return checked(); }
        finally { if (made) try { Checkpoint.deleteTree(setupTemp()); } catch (IOException ignored) { /* left in the helper's own folder */ } }
    }

    private static Checked checked() {
        Searx.Result r;
        try { r = RUN.run(checkCommand(), l -> { }); }
        catch (Exception e) { return new Checked(0, List.of(), 0, String.valueOf(e.getMessage())); }
        List<Unloaded> failed = new ArrayList<>();
        int tried = -1; double seconds = 0;
        for (String line : r.out().split("\\R")) {
            if (!line.startsWith("{")) continue;
            JsonNode j;
            try { j = M.readTree(line); } catch (IOException e) { continue; }
            if (j.has("checked")) { tried = j.path("checked").asInt(); seconds = j.path("sec").asDouble(); }
            else if (j.has("piece") && !j.path("ok").asBoolean(true)) failed.add(new Unloaded(j.path("piece").asText(), j.path("kind").asText("model"), j.path("why").asText("no reason given"), j.path("module").asText("")));
        }
        if (tried < 0) { String last = TranslationScores.last(r.out()); return new Checked(0, List.of(), 0, last.isBlank() ? "exit code " + r.code() : last); }
        return new Checked(tried, List.copyOf(failed), seconds, "");
    }

    /** The note's word for every package of the list: the reason named none of them. */
    static final String ALL_PACKAGES = "*";

    /** Where a set-up whose check failed writes the Python packages to install anew, by pip's names, one a line; removed by the set-up whose check passes. */
    static Path unloadedNote() { return dir().resolve("unloaded"); }

    /** The packages the last check found not loading, as the note has them; none when no check failed, or none of what failed was a package. */
    static List<String> unloaded() {
        try { return Files.isRegularFile(unloadedNote()) ? Files.readAllLines(unloadedNote(), StandardCharsets.UTF_8).stream().map(String::strip).filter(l -> !l.isEmpty()).toList() : List.of(); }
        catch (IOException e) { return List.of(); }
    }

    private static boolean unloadedHas(Package p) { return unloaded().contains(p.pip()); }

    /**
     * The packages a failed check names, for the next set-up to install anew: the package whose module did not import (a failure that
     * names a module inside a package names the package); fugashi with its dictionary, which it loads; and every package where the
     * reason is neither a file of the manifest (the fetch brings that again) nor a module of the list.
     */
    static void noteUnloaded(Checked c) throws IOException {
        LinkedHashSet<String> pips = new LinkedHashSet<>();
        for (Unloaded u : c.failed()) {
            if (u.file()) continue;
            String module = (u.module().isEmpty() ? (u.kind().equals("package") ? u.piece() : "") : u.module()).split("\\.")[0];
            Package named = null;
            for (Package p : PYTHON_PACKAGES) if (p.module().equals(module)) named = p;
            if (named == null) { pips.add(ALL_PACKAGES); continue; }
            pips.add(named.pip());
            if (named.pip().equals("fugashi")) pips.add("unidic-lite");
        }
        if (pips.isEmpty()) Files.deleteIfExists(unloadedNote());
        else Files.writeString(unloadedNote(), String.join("\n", pips) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * A helper that was set up and whose check now fails is not set up: its marker is removed and what did not load is noted, so that
     * the set-up that follows installs and fetches it anew.
     */
    static void unsetUp(Checked c) throws IOException {
        noteUnloaded(c);
        Files.deleteIfExists(ready());
    }

    /** What a fetch brings, by the kinds of the things the manifest names among them: "the models, the fonts and the hyphenation patterns". */
    static String fetchingSaid(List<Item> items) {
        LinkedHashSet<String> kinds = new LinkedHashSet<>();
        for (String kind : List.of("model", "font", "hyphenation", "words")) for (Item i : items) if (i.kind().equals(kind)) kinds.add(switch (kind) { case "model" -> "the models"; case "font" -> "the fonts"; case "hyphenation" -> "the hyphenation patterns"; default -> "the word lists"; });
        for (Item i : items) if (!List.of("model", "font", "hyphenation", "words").contains(i.kind())) kinds.add("the other files it reads");
        return Chrome.ENGLISH.and(new ArrayList<>(kinds));
    }

    /**
     * A line of the fetch as a person reads it: "  fetched manga-ocr-base (444 MB)" for a file of a megabyte or more, and for a large
     * file on its way "  fetching manga-ocr-base, 128 MB of 444 MB"; "" for the rest.
     */
    static String fetchedSaid(String line) {
        String[] w = line.strip().split("\\s+");
        if (w.length < 3 || !(w[0].equals("fetched") || w[0].equals("fetching"))) return "";
        String title = w[1];
        Item thing = null;
        for (Item i : manifest()) if (i.paths().contains(w[1])) { title = i.title(); thing = i; }
        try {
            if (w[0].equals("fetching")) return w.length < 4 ? "" : "  fetching " + title + ", " + size(Long.parseLong(w[2])) + " of " + size(Long.parseLong(w[3]));
            long size = Long.parseLong(w[2]);
            // a thing of several files is one line, said when its large file has come, with the thing's own size: the figure the list of pieces gives for it
            return size < 1_000_000 ? "" : "  fetched " + title + " (" + size(thing != null && size * 2 >= thing.bytes() ? thing.bytes() : size) + ")";
        } catch (NumberFormatException e) { return ""; }
    }

    /**
     * The helper's files follow the release: each of the build's that differs from the one set up is copied over, so a correction in the
     * helper arrives with the program; each job starts its own process, so nothing is restarted. One line, "" when nothing was to do or
     * the helper is not set up. What a release fetches anew is not fetched here: when it names other packages, another file or another
     * revision of one, {@link #installed} is false and the question is asked again where a comic is next translated.
     */
    static synchronized String refresh() {
        if (!on() || !present()) return "";
        int changed = 0;
        try {
            for (String f : FILES) {
                byte[] now = resource(f);
                if (now == null) return "";
                Path at = dir().resolve(f);
                if (Files.isRegularFile(at) && Arrays.equals(now, Files.readAllBytes(at))) continue;
                Files.createDirectories(at.getParent());
                Files.write(at, now);
                changed++;
            }
        } catch (IOException e) { return "The comic helper's files are from an earlier release, and could not be refreshed: " + e.getMessage(); }
        return changed == 0 ? "" : "The comic helper's files are from an earlier release; refreshed.";
    }

    /**
     * The helper's state, for a person: one sentence, in English as the scorers' and the likeness helper's status are. From its marker
     * and its files alone; {@code translate comics status} adds the check of every piece to it ({@link #check}, {@link #broken}).
     */
    static String status() {
        String barred = barred(Chrome.ENGLISH);
        if (!barred.isEmpty()) return barred;
        if (installed()) {
            long bytes = 0; for (Item i : manifest()) bytes += i.bytes();
            return "The comic helper is set up at " + dir() + ": it reads a comic's pages (the lettering in the comic's own language, the balloons) and sets a translation into the balloons, on this machine's processor with " + threads() + " threads. Its models, fonts and patterns take " + size(bytes) + " there.";
        }
        Needs n = needs();
        if (!n.asks() && !setUpBefore()) return "The comic helper's set-up was begun at " + dir() + " and not finished. What is left (" + (n.items().isEmpty() ? "its last steps" : n.addsSaid()) + ") is done as the first step of the next comic translated, with no question, or now by researchzosho translate comics install.";
        if (!n.asks()) return "The comic helper was set up for an earlier release, at " + dir() + ". This release adds to it: " + n.addsSaid() + ". That is fetched as the first step of the next comic translated, with no question, or now by researchzosho translate comics install; researchzosho translate comics status --list names each piece with its licence.";
        return "The comic helper is not set up: a comic (a CBZ, a CBR, a ZIP or a folder of page pictures) cannot be translated until it is. researchzosho translate comics install sets it up, as the first translation of a comic offers to: about " + size(n.download()) + " to download and " + size(n.disk()) + " on the disk; researchzosho translate comics status --list names each thing it fetches with its licence.";
    }

    /** What {@code translate comics status} says of a helper that is set up and a piece of which does not load: the piece, the helper's reason, and what puts it right. */
    static String broken(Checked c) { return "The comic helper at " + dir() + " does not work as it stands: " + c.said() + ". A comic that needs that piece cannot be translated until it does."; }

    /** The helper's line in {@code researchzosho helpers}: from its marker and its files, with nothing loaded, since that list is every helper's and is read at a glance. */
    static String statusLine() {
        if (!on()) return "off (" + SETTING + "=off)";
        if (!Helpers.localAllowed()) return "not run here (local_helpers off); it runs only on the library's own machine in this version";
        return installed() ? "on this machine, set up at " + dir() : needs().asks() ? "on this machine, not set up yet (researchzosho translate comics install)"
                : setUpBefore() ? "on this machine, set up at " + dir() + " for an earlier release; what this release adds is fetched with the next comic" : "on this machine, its set-up begun and not finished; the next comic finishes it";
    }

    // ---- the seam: what the translation asks ----

    /**
     * A page the helper could not do: its own words for why ("cannot identify image file", "set needs a font"). The process lives on.
     * {@code type} is the helper's name for the kind of failure; "Unloadable" is a piece of the helper itself that does not load on
     * this machine (a reader's file missing or changed, a package that does not import).
     */
    static final class Failed extends IOException {
        private final String type;
        Failed(String message) { this(message, ""); }
        Failed(String message, String type) { super(message); this.type = type == null ? "" : type; }
        /** Whether it is no page's failure but the helper's own: every page would get it, and researchzosho translate comics install is what puts it right. */
        boolean unloadable() { return type.equals("Unloadable"); }
    }

    /** One piece of a web comic's strip: its file, and where its top edge lies in the strip. */
    record Piece(Path file, int top) { }

    /** One block of a page cut out for a model that sees: the helper's id for the block, and the picture's file, a PNG. */
    record Crop(String id, Path file) { }

    /** What reads a comic's pages and sets text into them. The local process in this version; a helper served by another machine can stand here later. */
    interface Reader extends AutoCloseable {
        /**
         * Which writing system the pictures given are lettered in: {@code script} (japanese, korean, chinese, latin, cyrillic, greek, or
         * none when no lettering was found), {@code vertical} (most blocks run in columns), {@code share} (the share of the letters read
         * that are in it), and {@code texts}, up to 40 blocks as read with that system's reader, from which the program tells one
         * language of an alphabet from another.
         */
        JsonNode script(List<Path> images) throws IOException;
        /**
         * One page read with the reader of {@code language} (a code: ja, ko, zh, en, fr…; "" for the helper's own default, Japanese),
         * which the helper loads at the first page that names it: its blocks in reading order, as {@code comic/read.py} gives a page,
         * with the {@code direction} it read them in (rtl, ltr). {@code order}: rtl or ltr when the program has decided the direction
         * for the whole comic (a Chinese comic, by whether its lettering runs in columns), "" to leave it to the helper. A language the
         * helper has no reader for is the page's failure.
         */
        JsonNode read(Path image, String language, String order) throws IOException;
        /** The same in the order the helper takes by itself ({@code order} ""): from the right for Japanese, a Chinese page by its own columns, from the left for everything else. */
        default JsonNode read(Path image, String language) throws IOException { return read(image, language, ""); }
        /** The same, in the helper's own default language: for a look that asks only where the balloons are. */
        default JsonNode read(Path image) throws IOException { return read(image, "", ""); }
        /**
         * A picture that is a web comic's strip (more than 2.2 times as high as wide), cut into pieces under {@code out}: {@code strip}
         * true, its {@code width} and {@code height}, {@code pieces}, each a file with its {@code top} and {@code height}, covering the
         * strip from top to bottom, and {@code cut_through}, what each cut runs through (gutter, art, balloon, lettering). A picture that
         * is an ordinary page answers {@code strip} false and nothing is written.
         */
        JsonNode cut(Path image, Path out) throws IOException;
        /** The pieces of a strip put back into one picture of {@code width} by {@code height}, written to {@code out} in the format its ending names. */
        void join(List<Piece> pieces, int width, int height, Path out) throws IOException;
        /**
         * The page with the texts set into its balloons, written to {@code out}; {@code texts} by block id ({"b01": {"text": …, "lang":
         * "zh", "vertical": true}}; vertical only where the block is to be set in columns), {@code script} the target's writing system
         * (latin, cyrillic, greek, japanese, chinese-simplified, korean), whose font is named first; {@code keep} the strings no line
         * or column may end inside (the names decided for the volume, as the translation writes them). The report: a size and lines
         * for each block set, {@code vertical} when it was set in columns, for Chinese, Japanese and Korean how many of its lines
         * end inside a phrase ({@code inside_phrase}), or why it was not set.
         */
        JsonNode set(Path image, JsonNode page, ObjectNode texts, String script, List<String> keep, Path out) throws IOException;
        /** The same, with no string to keep whole. */
        default JsonNode set(Path image, JsonNode page, ObjectNode texts, String script, Path out) throws IOException { return set(image, page, texts, script, List.of(), out); }
        /**
         * The blocks of a page cut out for a model that sees, for the second reading of the lettering: {@code page} is the page as
         * {@link #read} gave it, {@code ids} the blocks asked for, each written as a PNG under {@code out}. A crop is the one the
         * helper's own reader was given, made larger or smaller to the size a model reads well (the helper's side chose it). A helper
         * that cuts no crops says so as the page's failure, and the lettering stands as the helper read it.
         */
        default List<Crop> crops(Path image, JsonNode page, List<String> ids, Path out) throws IOException { throw new Failed("this helper cuts no blocks out of a page"); }
        /** The page as a model that sees is shown it: made smaller, each box drawn and numbered, written to {@code out} as a JPEG. {@code boxes}: a label and a box [x1, y1, x2, y2] each. */
        void picture(Path image, List<Map.Entry<String, double[]>> boxes, Path out) throws IOException;
        /** What the helper says of itself when it has loaded: its version and its models' revisions; "" before the first page. */
        String loaded();
        /** The languages the helper says it reads, as codes, from its first line; empty before the first request, and for a helper that names none. */
        default List<String> languages() { return List.of(); }
        /** A stop: nothing more is asked, the process is ended. */
        void abort();
        @Override void close();
    }

    /** What reads the pages; a test stands in one whose process is a stand-in. */
    static volatile Supplier<Reader> READER = () -> new Local(ComicHelper::serveCommand);
    static Reader reader() { return READER.get(); }

    /** No answer from the helper for this long on one request is a helper that has stopped answering; and the wait for a fresh process's first line, which comes after the models' load. */
    static volatile Duration STALL = Duration.ofMinutes(5), FIRST = Duration.ofMinutes(10);

    /**
     * The helper's process on this machine. One request is in flight at a time: its line written, its answer awaited. The answers are
     * read by a thread of the process's own, so neither pipe fills; the wait looks every quarter second whether the run was asked to stop
     * ({@link Stopping}), ends the process then, and ends in any case after {@link #STALL}. A process that ends under a request is
     * started once more and the request asked again; when the second ends too, the job fails with what the process's own log says.
     */
    static final class Local implements Reader {
        private static final String ENDED = "\0ended";
        private final Supplier<List<String>> command;
        private Process process;
        private Writer in;
        private BlockingQueue<String> replies;
        private volatile boolean aborted;
        private int starts;
        private long n;
        private String loaded = "";
        private List<String> languages = List.of();

        Local(Supplier<List<String>> command) { this.command = command; }

        @Override public synchronized JsonNode script(List<Path> images) throws IOException {
            ObjectNode req = M.createObjectNode(); req.put("op", "script");
            ArrayNode a = req.putArray("images"); for (Path image : images) a.add(image.toAbsolutePath().toString());
            return ask(req);
        }

        @Override public synchronized JsonNode read(Path image, String language, String order) throws IOException {
            ObjectNode req = M.createObjectNode(); req.put("op", "read"); req.putArray("images").add(image.toAbsolutePath().toString());
            if (language != null && !language.isBlank()) req.put("language", language);   // absent: the helper's default, Japanese
            if (order != null && !order.isBlank()) req.put("order", order);   // absent: the helper's own, by the language and for Chinese by the page
            JsonNode page = ask(req).path("pages").path(0);
            if (!page.isObject()) throw new Failed("the helper gave no page for " + image.getFileName());
            return page;
        }

        @Override public synchronized JsonNode cut(Path image, Path out) throws IOException {
            ObjectNode req = M.createObjectNode(); req.put("op", "cut"); req.put("image", image.toAbsolutePath().toString()); req.put("out", out.toAbsolutePath().toString());
            return ask(req);
        }

        @Override public synchronized void join(List<Piece> pieces, int width, int height, Path out) throws IOException {
            ObjectNode req = M.createObjectNode(); req.put("op", "join");
            ArrayNode a = req.putArray("pieces"); for (Piece p : pieces) a.addObject().put("file", p.file().toAbsolutePath().toString()).put("top", p.top());
            req.put("width", width); req.put("height", height); req.put("out", out.toAbsolutePath().toString());
            ask(req);
        }

        @Override public synchronized JsonNode set(Path image, JsonNode page, ObjectNode texts, String script, List<String> keep, Path out) throws IOException {
            List<Path> fonts = fonts(script);
            ObjectNode req = M.createObjectNode(); req.put("op", "set"); req.put("image", image.toAbsolutePath().toString()); req.set("page", page); req.set("texts", texts); req.put("out", out.toAbsolutePath().toString());
            if (keep != null && !keep.isEmpty()) { ArrayNode whole = req.putArray("keep"); for (String k : keep) whole.add(k); }
            if (!fonts.isEmpty()) { req.put("font", fonts.get(0).toString()); ArrayNode more = req.putArray("more_fonts"); for (Path f : fonts.subList(1, fonts.size())) more.add(f.toString()); }
            return ask(req).path("report");
        }

        @Override public synchronized void picture(Path image, List<Map.Entry<String, double[]>> boxes, Path out) throws IOException {
            ObjectNode req = M.createObjectNode(); req.put("op", "picture"); req.put("image", image.toAbsolutePath().toString()); req.put("out", out.toAbsolutePath().toString());
            ArrayNode bs = req.putArray("boxes");
            for (Map.Entry<String, double[]> b : boxes) { ArrayNode one = bs.addArray(); one.add(b.getKey()); ArrayNode box = one.addArray(); for (double v : b.getValue()) box.add(v); }
            ask(req);
        }

        @Override public synchronized List<Crop> crops(Path image, JsonNode page, List<String> ids, Path out) throws IOException {
            ObjectNode req = M.createObjectNode(); req.put("op", "crops"); req.put("image", image.toAbsolutePath().toString());
            ArrayNode which = req.putArray("ids"); for (String id : ids) which.add(id);
            req.put("out", out.toAbsolutePath().toString()); req.set("page", page);
            List<Crop> crops = new ArrayList<>();
            for (JsonNode c : ask(req).path("crops")) if (!c.path("id").asText("").isEmpty() && !c.path("file").asText("").isEmpty()) crops.add(new Crop(c.path("id").asText(), Path.of(c.path("file").asText())));
            return crops;
        }

        @Override public synchronized String loaded() { return loaded; }
        @Override public synchronized List<String> languages() { return languages; }

        /** One request and its answer; the process started when there is none, and once more when it ends under the request. */
        private JsonNode ask(ObjectNode req) throws IOException {
            while (true) {
                if (aborted) throw new IOException("The comic helper was stopped.");
                if (process == null) start();
                long id = ++n;
                req.put("id", id);
                try { in.write(req.toString()); in.write('\n'); in.flush(); }
                catch (IOException gone) { ended(); continue; }   // its input is closed: the process is gone
                String reply = await(STALL, "on one page");   // a wait that runs out is the job's failure, said as it is; never asked again
                if (reply.equals(ENDED)) { ended(); continue; }
                JsonNode j;
                try { j = M.readTree(reply); } catch (IOException e) { throw new Failed("the helper's answer could not be read"); }
                if (j.path("id").asLong(-1) != id) continue;   // a line that answers nothing asked: passed over, the request asked again
                if (!j.path("ok").asBoolean(false)) throw new Failed(j.path("error").path("message").asText("the helper gave no reason"), j.path("error").path("type").asText(""));
                return j;
            }
        }

        /** Starts the process and waits for its first line, which says it has loaded its models. */
        private void start() throws IOException {
            if (starts >= 2) throw new IOException("The comic helper's process ended twice" + logSaid() + ". researchzosho translate comics install sets it up again.");
            String refreshed = refresh();
            if (!refreshed.isEmpty()) log.info("comic: {}", refreshed);
            Process pr;
            try {
                ProcessBuilder pb = new ProcessBuilder(command.get());
                environment(pb.environment());
                Path logFile = lastLog();
                if (logFile.getParent() != null && Files.isDirectory(logFile.getParent())) pb.redirectError(ProcessBuilder.Redirect.to(logFile.toFile())); else pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                pr = pb.start();
            } catch (IOException e) { throw new IOException("The comic helper's process could not be started: " + e.getMessage() + ". researchzosho translate comics status says how it stands."); }
            starts++;
            BlockingQueue<String> q = new LinkedBlockingQueue<>();
            Thread t = new Thread(() -> pump(pr, q), "comic-helper");
            t.setDaemon(true);
            t.start();
            process = pr; replies = q; in = new OutputStreamWriter(pr.getOutputStream(), StandardCharsets.UTF_8);
            String first = await(FIRST, "after its process started");
            if (first.equals(ENDED)) { ended(); start(); return; }
            try {
                JsonNode j = M.readTree(first), m = j.path("models");
                List<String> with = new ArrayList<>();
                for (String[] lib : new String[][]{{"manga-ocr", "manga-ocr"}, {"onnxruntime", "ONNX Runtime"}, {"torch", "PyTorch"}, {"budoux", "BudouX"}, {"fugashi", "fugashi"}}) if (m.hasNonNull(lib[0])) with.add(lib[1] + " " + m.path(lib[0]).asText());
                loaded = "version " + j.path("version").asText("") + (with.isEmpty() ? "" : ", with " + String.join(", ", with)) + (j.has("threads") ? ", on " + j.path("threads").asInt() + " processor threads" : "");
                List<String> reads = new ArrayList<>(); for (JsonNode l : j.path("languages")) reads.add(l.asText());
                languages = List.copyOf(reads);
            } catch (IOException e) { loaded = ""; }
        }

        /** The next line of the process, or ENDED; the run's stop looked at every quarter second, the wait ended after {@code wait}. */
        private String await(Duration wait, String when) throws IOException {
            long until = System.currentTimeMillis() + wait.toMillis();
            while (true) {
                String reply;
                try { reply = replies.poll(250, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); abort(); throw new IOException("The wait for the comic helper was interrupted."); }
                if (reply != null) return reply;
                if (aborted) throw new IOException("The comic helper was stopped.");
                if (Stopping.requested()) { abort(); throw new Stopping.Requested(); }
                if (System.currentTimeMillis() >= until) {
                    end(process); process = null;
                    throw new IOException("The comic helper gave no answer for " + TranslationScores.Metricx.said(wait) + " " + when + logSaid() + ".");
                }
            }
        }

        /** The process ended under a request: gone, so that the next ask starts it once more. */
        private void ended() {
            Process pr = process; process = null;
            String how = "with its output closed";
            try { if (pr != null && pr.waitFor(2, TimeUnit.SECONDS)) how = "with exit code " + pr.exitValue(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            end(pr);
            log.warn("the comic helper's process ended {}{}", how, starts >= 2 ? "" : "; starting it once more");
        }

        /** Where the process's own last words are, when there are any. */
        private static String logSaid() {
            try { Path f = lastLog(); if (Files.isRegularFile(f) && Files.size(f) > 0) return " (its last words: " + TranslationScores.last(Files.readString(f, StandardCharsets.UTF_8)) + "; the whole of them in " + f + ")"; }
            catch (IOException | RuntimeException ignored) { }
            return "";
        }

        /** The process's own thread: every line that is a JSON object is an answer; anything else a library prints is passed over; ENDED at the end. */
        private static void pump(Process pr, BlockingQueue<String> replies) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) if (line.startsWith("{")) replies.put(line);
            } catch (IOException | InterruptedException ignored) { }
            replies.offer(ENDED);
        }

        private static void end(Process pr) {
            if (pr == null) return;
            pr.descendants().forEach(ProcessHandle::destroyForcibly);
            pr.destroyForcibly();
        }

        /** Asked to quit, as its protocol has it; ended by force when it has not within five seconds. */
        @Override public synchronized void close() {
            Process pr = process; process = null;
            if (pr == null) return;
            try { in.write("{\"op\": \"quit\"}\n"); in.flush(); in.close(); if (pr.waitFor(5, TimeUnit.SECONDS)) return; }
            catch (IOException ignored) { }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            end(pr);
        }

        @Override public void abort() {
            aborted = true;
            end(process);
        }
    }

    /** The manifest as an API gives it: each thing with its size and its licence, and whether it is here. */
    static ObjectNode json() {
        ObjectNode o = M.createObjectNode();
        o.put("set_up", installed());
        Needs n = needs();
        o.put("download_bytes", n.download()); o.put("disk_bytes", n.disk()); o.put("python_packages", n.packages());
        ArrayNode a = o.putArray("fetches");
        for (Item i : manifest()) { ObjectNode x = a.addObject(); x.put("name", i.title()); x.put("kind", i.kind()); x.put("what", i.said()); x.put("bytes", i.bytes()); x.put("licence", i.licence()); if (!i.language().isEmpty()) x.put("language", i.language()); x.put("here", have(i)); }
        ObjectNode py = a.addObject(); py.put("name", "Python packages"); py.put("kind", "packages"); py.put("what", PACKAGES_SAID); py.put("bytes", PACKAGES_DISK); py.put("download_bytes", PACKAGES_DOWNLOAD); py.put("licence", packageLicences(PYTHON_PACKAGES)); py.put("here", !n.packages());
        return o;
    }

    /** The things the manifest names, by name; for the docs' table and a test. */
    static Map<String, Item> byName() { Map<String, Item> m = new LinkedHashMap<>(); for (Item i : manifest()) m.put(i.name(), i); return m; }
}
