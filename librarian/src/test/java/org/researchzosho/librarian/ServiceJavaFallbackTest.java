package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A service installed from a terminal where Java was found only on that terminal's PATH. The service starts in an environment
 * of its own (a Windows logon, systemd's, launchd's), so it falls back to the Java `service install` ran under; JAVA_HOME or a
 * Java on the service's own PATH still wins. On Windows, a logon that finds no Java at all says so in the server's log and
 * exits 1, where it used to start nothing while Windows recorded success (measured on the Windows test box, 2026-09-25).
 */
class ServiceJavaFallbackTest {

    static final Path HOME = Path.of("C:\\Users\\x");
    static final String BAT = "C:\\Users\\x\\AppData\\Local\\Programs\\researchzosho\\bin\\researchzosho.bat";

    static String windowsScript(String javaHome) {
        return Service.plan(Service.Os.windows, BAT, "127.0.0.1", 4649, 3, HOME, javaHome).text();
    }

    @Test
    void theTaskScriptFallsBackToTheJavaItWasInstalledWithOnlyWhenTheLogonHasNone() {
        String s = windowsScript("C:\\tools\\jdk25");
        String fallback = "if (-not $env:JAVA_HOME -and -not (Get-Command java.exe -ErrorAction SilentlyContinue)) { $env:JAVA_HOME = 'C:\\tools\\jdk25' }\n";
        assertTrue(s.contains(fallback), s);
        int launcher = s.indexOf("& '" + BAT + "' ");
        assertTrue(s.contains("serve --port 4649 --crew-hour 3 --log $log\nexit $LASTEXITCODE\n"), s);
        assertTrue(launcher > s.indexOf(fallback), "the fallback is set before the launcher runs: " + s);
        assertTrue(s.indexOf("$env:RESEARCHZOSHO_JAVA_OPTS") < s.indexOf(fallback), "the settings copied from the installing shell, JAVA_HOME among them, come first and win");
        assertTrue(s.endsWith("exit $LASTEXITCODE\n"), "a launcher that fails is not recorded as success: " + s);
        assertTrue(s.chars().allMatch(c -> c < 128), "ASCII only: PowerShell reads a BOM-less file as ANSI");
        assertTrue(windowsScript("C:\\Users\\O'Hale\\jdk").contains("$env:JAVA_HOME = 'C:\\Users\\O''Hale\\jdk' }"), "a quote in the folder is doubled");
    }

    @Test
    void withNoJavaAtAllTheTaskScriptWritesOneSentenceToTheServerLogAndExitsOne() {
        String s = windowsScript("C:\\tools\\jdk25");
        Path log = HOME.resolve(".researchzosho").resolve("logs").resolve("librarian-serve.log");
        assertTrue(s.contains("$log = '" + log + "'\n"), s);
        int check = s.indexOf("if (-not (Test-Path -LiteralPath $own) -and -not ($java -and (Test-Path -LiteralPath $java))) {");
        assertTrue(check > 0, s);
        int write = s.indexOf("[IO.File]::AppendAllText($log, ", check);
        int exit = s.indexOf("    exit 1\n", check);
        assertTrue(write > check && exit > write && exit < s.indexOf("& '" + BAT + "'"), "written, then exit 1, before the launcher: " + s);
        // the launcher's own Java (the build that carries one) counts: it is what the launcher uses
        assertTrue(s.contains("$own = Join-Path (Split-Path (Split-Path '" + BAT + "')) 'jre\\bin\\java.exe'\n"), s);
        // the sentence, as PowerShell reads the single-quoted literal
        Matcher m = Pattern.compile("\\+ '  ((?:[^']|'')*)' \\+ \\[Environment\\]::NewLine").matcher(s);
        assertTrue(m.find(), s);
        String sentence = m.group(1).replace("''", "'");
        assertEquals(Service.NO_JAVA_AT_LOGON, sentence);
        assertTrue(sentence.startsWith("Java was not found when Windows started ResearchZosho at logon"), sentence);
        assertFalse(sentence.contains(". "), "one sentence: " + sentence);
        assertTrue(sentence.endsWith("$env:RESEARCHZOSHO_RUNTIME = '1'; irm https://researchzosho.org/install.ps1 | iex"), "the command that installs the build with its own Java: " + sentence);
    }

    @Test
    void aJavaNamedByResearchzoshoJavaBecomesJavaHomeForTheInstalledLauncher() {
        String s = windowsScript("C:\\tools\\jdk25");
        int named = s.indexOf("$named = if ($env:RESEARCHZOSHO_JAVA) { $env:RESEARCHZOSHO_JAVA } else { $env:CODEZAIKU_JAVA }\n");
        assertTrue(named > 0, s);
        assertTrue(s.indexOf("$env:JAVA_HOME = $h", named) > 0 && s.indexOf("$env:JAVA_HOME = $h") < s.indexOf("Get-Command java.exe"),
                "RESEARCHZOSHO_JAVA is read before the fallback, so a Java the person named wins over it: " + s);
    }

    @Test
    void installWarnsOnlyWhenALogonWouldFindNoJavaOfItsOwn() {
        Set<String> files = Set.of("C:\\tools\\jdk25\\bin\\java.exe", "C:\\Program Files\\Java\\jdk-21\\bin\\java.exe");
        Map<String, String> none = saved("C:\\WINDOWS\\system32;C:\\WINDOWS", "C:\\Users\\x\\AppData\\Local\\Programs\\researchzosho\\bin", "", "");
        // Java on this terminal's PATH only: the saved settings have none
        assertFalse(Service.logonFindsJava(k -> null, none, files::contains));
        // on the saved User PATH (a trailing backslash, quotes)
        assertTrue(Service.logonFindsJava(k -> null, saved("C:\\WINDOWS", "\"C:\\tools\\jdk25\\bin\\\";C:\\x", "", ""), files::contains));
        // on the saved Machine PATH
        assertTrue(Service.logonFindsJava(k -> null, saved("C:\\Program Files\\Java\\jdk-21\\bin;C:\\WINDOWS", "", "", ""), files::contains));
        // a saved JAVA_HOME, and one that points nowhere (the launcher then fails, whatever PATH has)
        assertTrue(Service.logonFindsJava(k -> null, saved("C:\\WINDOWS", "", "C:\\tools\\jdk25", ""), files::contains));
        assertFalse(Service.logonFindsJava(k -> null, saved("C:\\tools\\jdk25\\bin", "", "C:\\gone", ""), files::contains));
        // JAVA_HOME or RESEARCHZOSHO_JAVA in the installing shell: the task script carries it
        assertTrue(Service.logonFindsJava(k -> k.equals("JAVA_HOME") ? "C:\\tools\\jdk25" : null, none, files::contains));
        assertTrue(Service.logonFindsJava(k -> k.equals("RESEARCHZOSHO_JAVA") ? "C:\\tools\\jdk25\\bin\\java.exe" : null, none, files::contains));
        String w = Service.windowsJavaWarning("C:\\tools\\jdk25", HOME.resolve("librarian-serve.log"));
        assertTrue(w.contains("C:\\tools\\jdk25") && w.contains("librarian-serve.log") && w.contains(Service.WINDOWS_RUNTIME_INSTALL), w);
    }

    static Map<String, String> saved(String machinePath, String userPath, String userJavaHome, String machineJavaHome) {
        Map<String, String> m = new HashMap<>();
        m.put("Machine Path", machinePath); m.put("User Path", userPath);
        m.put("User JAVA_HOME", userJavaHome); m.put("Machine JAVA_HOME", machineJavaHome);
        m.put("User RESEARCHZOSHO_JAVA", ""); m.put("Machine RESEARCHZOSHO_JAVA", "");
        return m;
    }

    // ---- Linux and macOS: the same fallback, as a shell line in front of the launcher ----

    /** The script systemd hands to /bin/sh: the single-quoted word after ExecStart=/bin/sh -c, with systemd's $$ read as $. */
    static String systemdScript(String unit) {
        Matcher m = Pattern.compile("(?m)^ExecStart=/bin/sh -c '([^']*)' (.*)$").matcher(unit);
        assertTrue(m.find(), unit);
        assertFalse(m.group(1).replaceAll("\\$\\$", "").contains("$"), "every $ for the shell is written $$, or systemd substitutes it: " + m.group(1));
        assertFalse(m.group(1).contains("%") || m.group(1).contains("\\"), "no specifier and no escape for systemd to read: " + m.group(1));
        return m.group(1).replace("$$", "$");
    }

    /** The script launchd hands to /bin/sh: the string after -c, unescaped. */
    static String launchdScript(String plist) {
        Matcher m = Pattern.compile("<string>/bin/sh</string><string>-c</string><string>([^<]*)</string>").matcher(plist);
        assertTrue(m.find(), plist);
        return m.group(1).replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&");
    }

    @Test
    void theUnitAndTheLaunchAgentCarryTheJavaTheyWereInstalledWith() {
        String unit = Service.plan(Service.Os.linux, "/opt/rz/bin/researchzosho", "127.0.0.1", 4649, 3, Path.of("/home/x"), "/home/x/.sdkman/candidates/java/21.0.4-tem").text();
        assertTrue(unit.contains("Environment=\"RESEARCHZOSHO_SERVICE_JAVA_HOME=/home/x/.sdkman/candidates/java/21.0.4-tem\"\n"), unit);
        assertTrue(unit.contains("' /opt/rz/bin/researchzosho serve --port 4649 --crew-hour 3 --log /home/x/.researchzosho/logs/librarian-serve.log\n"), unit);
        String plist = Service.plan(Service.Os.macos, "/Users/x/rz/bin/researchzosho", "127.0.0.1", 4649, 3, Path.of("/Users/x"), "/opt/homebrew/Cellar/openjdk@21/21.0.4/libexec/openjdk.jdk/Contents/Home").text();
        assertTrue(plist.contains("<key>RESEARCHZOSHO_SERVICE_JAVA_HOME</key><string>/opt/homebrew/Cellar/openjdk@21/21.0.4/libexec/openjdk.jdk/Contents/Home</string>"), plist);
        assertTrue(plist.contains("<string>-c</string><string>" + Service.xml(Service.UNIX_JAVA_FALLBACK) + "</string>\n    <string>/Users/x/rz/bin/researchzosho</string><string>serve</string>"), plist);
        assertEquals(Service.UNIX_JAVA_FALLBACK, systemdScript(unit));
        assertEquals(Service.UNIX_JAVA_FALLBACK, launchdScript(plist));
    }

    /** The shell line, run by /bin/sh as the service would run it, in front of a stand-in launcher that prints JAVA_HOME and its arguments. */
    @Test
    void theShellLineUsesTheInstalledJavaOnlyWhenTheServiceFindsNoneThatRuns(@TempDir Path tmp) throws Exception {
        assumeFalse(Service.os() == Service.Os.windows, "a POSIX shell");
        String script = systemdScript(Service.plan(Service.Os.linux, "/opt/rz/bin/researchzosho", "127.0.0.1", 4649, 3, Path.of("/home/x"), "/opt/jdk-21").text());
        Path launcher = exe(tmp.resolve("launcher"), "#!/bin/sh\nprintf 'JAVA_HOME=[%s] args=[%s]' \"$JAVA_HOME\" \"$*\"\n");
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        Path works = Files.createDirectories(tmp.resolve("works"));
        exe(works.resolve("java"), "#!/bin/sh\nexit 0\n");
        Path stub = Files.createDirectories(tmp.resolve("stub"));   // macOS's /usr/bin/java with no Java installed
        exe(stub.resolve("java"), "#!/bin/sh\necho 'Unable to locate a Java Runtime.' >&2\nexit 1\n");

        assertEquals("JAVA_HOME=[/opt/jdk-21] args=[serve --port 4649]", run(script, launcher, empty, null), "no Java on the service's PATH: the installed one");
        assertEquals("JAVA_HOME=[/opt/jdk-21] args=[serve --port 4649]", run(script, launcher, stub, null), "a java that does not run counts as none");
        assertEquals("JAVA_HOME=[] args=[serve --port 4649]", run(script, launcher, works, null), "a Java on the service's PATH wins");
        assertEquals("JAVA_HOME=[/usr/lib/jvm/25] args=[serve --port 4649]", run(script, launcher, empty, "/usr/lib/jvm/25"), "JAVA_HOME wins");
    }

    static Path exe(Path p, String text) throws Exception {
        Files.writeString(p, text, StandardCharsets.UTF_8);
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }

    static String run(String script, Path launcher, Path pathDir, String javaHome) throws Exception {
        var pb = new ProcessBuilder(List.of("/bin/sh", "-c", script, launcher.toString(), "serve", "--port", "4649")).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().put("PATH", pathDir.toString());
        pb.environment().put("RESEARCHZOSHO_SERVICE_JAVA_HOME", "/opt/jdk-21");
        if (javaHome != null) pb.environment().put("JAVA_HOME", javaHome);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    // ---- a person who deleted their library can still see and remove the service ----

    @Test
    void serviceStatusAndUninstallNeedNoLibrary() {
        assertFalse(LibrarianCli.needsLibrary(new String[]{"librarian", "service", "uninstall"}));
        assertFalse(LibrarianCli.needsLibrary(new String[]{"librarian", "service", "status"}));
        assertFalse(LibrarianCli.needsLibrary(new String[]{"librarian", "service"}), "service alone is status");
        assertTrue(LibrarianCli.needsLibrary(new String[]{"librarian", "service", "install"}), "the service serves a library: install needs one");
        assertFalse(LibrarianCli.needsLibrary(new String[]{"librarian", "init"}));
        assertTrue(LibrarianCli.needsLibrary(new String[]{"librarian", "status"}));
    }
}
