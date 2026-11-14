package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Python the comic helper's environment is made with (0.7.5): found the way a person would look for one, the newest the packages
 * install on taken, and where the machine has none, one sentence before the question and before anything is fetched that says what the
 * helper runs on, what the machine has and what to install. Every machine here is a stand-in: its files are a set of paths and each
 * Python's answer is written by the test; nothing starts a Python.
 */
class ComicPythonTest {
    String home; ComicHelper.Run run; LongSupplier free; BooleanSupplier installed; Supplier<ComicHelper.Pythons> pythons; Function<Consumer<String>, String> install;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        run = ComicHelper.RUN; free = ComicHelper.FREE; installed = ComicHelper.INSTALLED; pythons = ComicHelper.PYTHONS; install = ComicHelper.INSTALL;
        ComicHelper.FREE = () -> 100L << 30;
    }

    @AfterEach void restore() {
        System.setProperty("user.home", home); Config.invalidate();
        ComicHelper.RUN = run; ComicHelper.FREE = free; ComicHelper.INSTALLED = installed; ComicHelper.PYTHONS = pythons; ComicHelper.INSTALL = install;
    }

    /** A Python's answer to the probe: its version and its file, exit 0; or, with no venv module, the traceback and exit 1. */
    static Searx.Result says(String version, String exe, boolean venv) {
        return new Searx.Result(venv ? 0 : 1, version + "\n" + exe + "\n" + (venv ? "" : "Traceback (most recent call last):\nModuleNotFoundError: No module named 'ensurepip'\n"));
    }

    /** A machine's Pythons by the first word of the command that runs them (the launcher's version flag after it); a command not here does not start. */
    static Function<List<String>, Searx.Result> machine(Map<String, Searx.Result> pythons, List<List<String>> asked) {
        return cmd -> {
            asked.add(cmd);
            String key = cmd.get(0) + (cmd.size() > 1 && cmd.get(1).startsWith("-3") ? " " + cmd.get(1) : "");
            return pythons.get(key);
        };
    }

    static String p(String first, String... more) { return Path.of(first, more).toString(); }

    @Test void onAMacTheNewestIsTakenFromThePathAndHomebrewAndApplesOwnIsNeverRun() {
        Path standalone = Path.of("/opt/rz/python/bin");
        List<Path> path = List.of(standalone, Path.of("/usr/bin"), Path.of("/bin"));   // a login shell's PATH with no Homebrew on it, as a service's is
        Set<String> files = Set.of(p("/opt/rz/python/bin/python3.12"), "/usr/bin/python3", "/opt/homebrew/bin/python3.13", "/opt/homebrew/bin/python3.12",
                "/Library/Frameworks/Python.framework/Versions/3.11/bin/python3.11", "/Applications/Xcode.app/Contents/Developer/usr/bin/python3");
        List<List<String>> c = ComicHelper.candidates(ComicHelper.Os.MAC, path, Map.of(), x -> files.contains(x.toString()));
        assertEquals(List.of(List.of("/opt/homebrew/bin/python3.13"), List.of(p("/opt/rz/python/bin/python3.12")), List.of("/opt/homebrew/bin/python3.12"),
                List.of("/Library/Frameworks/Python.framework/Versions/3.11/bin/python3.11"), List.of("/Applications/Xcode.app/Contents/Developer/usr/bin/python3")), c,
                "newest first, the PATH before Homebrew's and python.org's folders within one version; /usr/bin/python3, Apple's stand-in that offers the developer tools in a dialog, is never run");
        Map<String, Searx.Result> answers = new HashMap<>();
        answers.put("/opt/homebrew/bin/python3.13", says("3.13.9", "/opt/homebrew/opt/python@3.13/bin/python3.13", true));
        answers.put("/Applications/Xcode.app/Contents/Developer/usr/bin/python3", says("3.9.6", "/Applications/Xcode.app/Contents/Developer/usr/bin/python3", true));
        List<List<String>> asked = new ArrayList<>();
        ComicHelper.Pythons found = ComicHelper.search(c, machine(answers, asked));
        assertEquals("3.13.9", found.chosen().version());
        assertEquals(List.of("/opt/homebrew/opt/python@3.13/bin/python3.13"), found.chosen().venvCommand(), "the environment is made by the Python's own file");
        assertEquals(List.of("/opt/homebrew/bin/python3.13", "-I", "-X", "utf8", "-c", ComicHelper.PROBE), asked.get(0), "asked in isolated mode and UTF-8, so that a folder's name in any alphabet comes through");
        assertEquals(1, asked.size(), "the newest that fits ends the search");
        // a Mac with Apple's Python alone: 3.9 is one the packages install on (manga-ocr's floor), and it is taken
        answers.remove("/opt/homebrew/bin/python3.13");
        found = ComicHelper.search(c, machine(answers, new ArrayList<>()));
        assertEquals("3.9.6", found.chosen().version());
    }

    @Test void aPythonPastTheNewestThePackagesInstallOnIsPassedOverAndNamed() {
        List<List<String>> c = List.of(List.of("/opt/homebrew/bin/python3.15"), List.of("/opt/homebrew/bin/python3"));
        Map<String, Searx.Result> answers = Map.of("/opt/homebrew/bin/python3.15", says("3.15.0", "/opt/homebrew/bin/python3.15", true), "/opt/homebrew/bin/python3", says("3.15.0", "/opt/homebrew/bin/python3.15", true));
        ComicHelper.Pythons found = ComicHelper.search(c, machine(answers, new ArrayList<>()));
        assertNull(found.chosen());
        assertEquals("The comic helper runs on Python 3.9 to 3.14, and this machine has only Python 3.15.0: install Python 3.13 with brew install python@3.13, and ask again.",
                ComicHelper.pythonSaid(Chrome.ENGLISH, ComicHelper.Os.MAC, found.seen(), true));
        assertEquals("The comic helper runs on Python 3.9 to 3.14, and this machine has only Python 3.15.0: install Python 3.13 with the installer from python.org, and ask again.",
                ComicHelper.pythonSaid(Chrome.ENGLISH, ComicHelper.Os.MAC, found.seen(), false), "a Mac without Homebrew is sent to python.org");
        assertEquals("The comic helper runs on Python 3.9 to 3.14, and this machine has no Python 3: install Python 3.13 with the installer from python.org, and ask again.",
                ComicHelper.pythonSaid(Chrome.ENGLISH, ComicHelper.Os.WINDOWS, List.of(), false));
        assertEquals("The comic helper runs on Python 3.9 to 3.14, and this machine has no Python 3: install Python 3.13 with its venv module from the system's packages, and ask again.",
                ComicHelper.pythonSaid(Chrome.ENGLISH, ComicHelper.Os.LINUX, List.of(), false));
        String ja = ComicHelper.pythonSaid(Chrome.in("ja"), ComicHelper.Os.MAC, found.seen(), true);
        assertTrue(ja.contains("3.9") && ja.contains("3.14") && ja.contains("3.15.0") && ja.contains("brew install python@3.13") && !ja.contains("The comic helper"), ja);
        assertEquals(ja, ComicHelper.pythonSaid(Chrome.in("ja"), ComicHelper.Os.MAC, found.seen(), true));
    }

    @Test void onLinuxAPythonWithoutItsVenvModuleIsSaidAsSuch() {
        List<Path> path = List.of(Path.of("/usr/local/sbin"), Path.of("/usr/bin"));
        Set<String> files = Set.of("/usr/bin/python3.12", "/usr/bin/python3");
        List<List<String>> c = ComicHelper.candidates(ComicHelper.Os.LINUX, path, Map.of(), x -> files.contains(x.toString()));
        assertEquals(List.of(List.of("/usr/bin/python3.12"), List.of("/usr/bin/python3")), c, "the same file by the PATH and by the usual places is tried once");
        Map<String, Searx.Result> answers = Map.of("/usr/bin/python3.12", says("3.12.3", "/usr/bin/python3.12", false), "/usr/bin/python3", says("3.12.3", "/usr/bin/python3.12", false));
        ComicHelper.Pythons found = ComicHelper.search(c, machine(answers, new ArrayList<>()));
        assertNull(found.chosen());
        assertEquals("The comic helper runs on Python 3.9 to 3.14, and this machine's Python 3.12.3 lacks its venv module: install it from the system's packages (python3.12-venv on Debian and Ubuntu), and ask again.",
                ComicHelper.pythonSaid(Chrome.ENGLISH, ComicHelper.Os.LINUX, found.seen(), false));
    }

    @Test void onWindowsThePyLauncherIsAskedForEachVersionAndTheEnvironmentIsMadeByThePythonItNames() {
        Map<String, String> env = Map.of("SystemRoot", "C:\\Windows", "LOCALAPPDATA", "C:\\Users\\some one\\AppData\\Local", "ProgramFiles", "C:\\Program Files", "SystemDrive", "C:");
        String py = p("C:\\Windows", "py.exe");
        Set<String> files = Set.of(py, p("C:\\Python314", "python.exe"), p("C:\\Users\\some one\\AppData\\Local\\Microsoft\\WindowsApps", "python.exe"));
        List<Path> path = List.of(Path.of("C:\\Python314"), Path.of("C:\\Users\\some one\\AppData\\Local\\Microsoft\\WindowsApps"));
        List<List<String>> c = ComicHelper.candidates(ComicHelper.Os.WINDOWS, path, env, x -> files.contains(x.toString()));
        assertEquals(List.of(py, "-3.14"), c.get(0)); assertEquals(List.of(py, "-3.9"), c.get(5));
        assertEquals(List.of(List.of(p("C:\\Python314", "python.exe")), List.of(p("C:\\Users\\some one\\AppData\\Local\\Microsoft\\WindowsApps", "python.exe")), List.of(py, "-3")), c.subList(6, 9),
                "then each python.exe on the PATH, and the launcher's default, to name what is there");
        // the launcher has 3.14 only; the Store's stand-in python.exe prints no version
        Map<String, Searx.Result> answers = new HashMap<>();
        answers.put(py + " -3.14", says("3.14.7", "C:\\Python314\\python.exe", true));
        answers.put(p("C:\\Users\\some one\\AppData\\Local\\Microsoft\\WindowsApps", "python.exe"), new Searx.Result(9009, "Python was not found; run without arguments to install from the Microsoft Store, or disable this shortcut from Settings > Apps > Advanced app settings > App execution aliases.\n"));
        List<List<String>> asked = new ArrayList<>();
        ComicHelper.Pythons found = ComicHelper.search(c, machine(answers, asked));
        assertEquals("3.14.7", found.chosen().version());
        assertEquals(List.of(py, "-3.14", "-I", "-X", "utf8", "-c", ComicHelper.PROBE), asked.get(0));
        assertEquals(List.of("C:\\Python314\\python.exe"), found.chosen().venvCommand(), "the venv is made by python.exe itself, not through the launcher");
        // a Windows with 3.15 alone: every version asked, the Store's stand-in passed over, the default named
        answers.clear();
        answers.put(py + " -3", says("3.15.0", "C:\\Python315\\python.exe", true));
        answers.put(p("C:\\Python314", "python.exe"), says("3.15.0", "C:\\Python315\\python.exe", true));
        found = ComicHelper.search(c, machine(answers, new ArrayList<>()));
        assertNull(found.chosen());
        assertEquals("The comic helper runs on Python 3.9 to 3.14, and this machine has only Python 3.15.0: install Python 3.13 with the installer from python.org, and ask again.",
                ComicHelper.pythonSaid(Chrome.ENGLISH, ComicHelper.Os.WINDOWS, found.seen(), false));
    }

    static ComicHelper.Pythons none() { return new ComicHelper.Pythons(null, List.of(new ComicHelper.Python(List.of("/usr/bin/python3.15"), "3.15.0", "/usr/bin/python3.15", true))); }

    @Test void withNoPythonThatFitsTheSentenceComesBeforeTheQuestionAndNothingIsFetched(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ComicHelper.INSTALLED = () -> false;
        ComicHelper.PYTHONS = ComicPythonTest::none;
        List<List<String>> ran = new ArrayList<>();
        ComicHelper.RUN = (cmd, line) -> { ran.add(cmd); return new Searx.Result(0, ""); };
        String said = ComicHelper.barred(Chrome.ENGLISH);
        assertTrue(said.startsWith("The comic helper runs on Python 3.9 to 3.14, and this machine has only Python 3.15.0: install Python 3.13 "), said);
        int[] rc = {-9};
        // translate comics install --yes: the sentence alone, and nothing run or written
        String out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "install", "--yes"}), false);
        assertEquals(1, rc[0]);
        assertEquals(said + "\n", out, "no list of what is fetched and no question before it");
        assertTrue(ran.isEmpty(), ran.toString()); assertFalse(Files.exists(ComicHelper.dir()), "nothing is written");
        // the status says it too
        String status = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "status"}), false);
        assertEquals(said + "\n", status);
        // a comic's translation: refused at its plan, before its pages are counted or a question is asked
        Path cbz = tmp.resolve("volume.cbz"); ComicStandIn.volume(cbz, 2);
        List<String> installs = new ArrayList<>();
        ComicHelper.INSTALL = say -> { installs.add("install"); return "ready"; };
        Interaction.OVERRIDE = false;
        String[] args = {"researchzosho", "translate", cbz.toString(), "--to", "English", "--yes"};
        String err = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, args), true);
        assertEquals(1, rc[0]); assertEquals(said + "\n", err);
        out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, args), false);
        assertFalse(out.contains("Setting it up fetches") || out.contains("Set the comic helper up now?"), out);
        assertTrue(installs.isEmpty());
        // and the set-up itself, asked from anywhere, says the same and runs nothing
        ComicHelper.INSTALL = install;
        assertEquals("!" + said, ComicHelper.install(l -> { }));
        assertTrue(ran.isEmpty());
        // once the helper is set up, no Python is looked for: its own environment runs it
        ComicHelper.INSTALLED = () -> true;
        assertEquals("", ComicHelper.barred(Chrome.ENGLISH));
    }

    /** On a fresh install the helper's own commands answered "no library at … run researchzosho init" (macOS, 2026-11-13): the helper is the machine's, not a library's. */
    @Test void theComicsCommandsNeedNoLibrary(@TempDir Path tmp) throws Exception {
        assertFalse(LibrarianCli.needsLibrary(new String[]{"researchzosho", "translate", "comics", "install", "--yes"}));
        assertFalse(LibrarianCli.needsLibrary(new String[]{"researchzosho", "translate", "comics"}));
        assertTrue(LibrarianCli.needsLibrary(new String[]{"researchzosho", "translate", "volume.cbz", "--to", "English"}), "a translation puts both files on the shelves");
        Path none = tmp.resolve("no-library-here");
        Config.set("RESEARCHZOSHO_LIBRARY", none.toString());
        int[] rc = {-9};
        String out = TranslationDirectionTest.out(() -> rc[0] = LibrarianCli.dispatch(new String[]{"researchzosho", "translate", "comics", "status"}, "http://127.0.0.1:1", "stand-in"), false);
        assertEquals(0, rc[0], out);
        assertTrue(out.startsWith("The comic helper is not set up: "), out);
        assertFalse(Files.exists(none), "and no library is made for it");
    }

    @Test void theEnvironmentIsMadeByTheChosenPythonAndOneMadeByAPythonThePackagesDoNotInstallOnIsMadeAgain() throws Exception {
        ComicHelper.PYTHONS = () -> new ComicHelper.Pythons(new ComicHelper.Python(List.of("/opt/homebrew/bin/python3.13"), "3.13.9", "/opt/homebrew/opt/python@3.13/bin/python3.13", true), List.of());
        List<List<String>> ran = new ArrayList<>(); List<String> said = new ArrayList<>();
        ComicHelper.RUN = ComicHelperTest.setUp(ran);
        // an environment an earlier try left, made by Python 3.15: its packages never installed
        Path venv = ComicHelper.dir().resolve("venv");
        Files.createDirectories(ComicHelper.python().getParent()); Files.writeString(ComicHelper.python(), "#!/bin/sh\n");
        Files.writeString(venv.resolve("pyvenv.cfg"), "home = /opt/homebrew/bin\ninclude-system-site-packages = false\nversion = 3.15.0\n");
        assertEquals("3.15.0", ComicHelper.venvVersion()); assertTrue(ComicHelper.venvToMake()); assertTrue(ComicHelper.needs().packages());
        assertEquals("ready", ComicHelper.install(said::add));
        assertEquals(List.of("/opt/homebrew/opt/python@3.13/bin/python3.13", "-m", "venv", venv.toString()), ran.get(0));
        assertTrue(said.contains("The helper's environment was made by Python 3.15.0, which its packages do not install on; making it again."), said.toString());
        assertTrue(said.contains("Making the helper's own Python environment in " + venv + ", with Python 3.13.9 (/opt/homebrew/opt/python@3.13/bin/python3.13)."), said.toString());
        // one made by a Python they install on is kept, whatever its version; one whose file cannot be read is kept too
        Files.writeString(venv.resolve("pyvenv.cfg"), "home = /Applications/Xcode.app/Contents/Developer/usr/bin\nversion = 3.9.6\n");
        assertFalse(ComicHelper.venvToMake());
        Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr/bin\nversion_info = 3.13.1.final.0\n");
        assertEquals("3.13.1", ComicHelper.venvVersion()); assertFalse(ComicHelper.venvToMake());
        Files.delete(venv.resolve("pyvenv.cfg"));
        assertEquals("", ComicHelper.venvVersion()); assertFalse(ComicHelper.venvToMake());
    }
}
