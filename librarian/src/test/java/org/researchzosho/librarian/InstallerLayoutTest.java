package org.researchzosho.librarian;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one-line installer for Linux and macOS lays the install out as the updater keeps it: each version in versions/, 'current' pointing at
 * the one that runs, the launchers in bin/ the updater's own, two versions kept; an install of the first layout moves into versions/ first.
 * The installer for Windows writes the same launchers the updater writes, word for word.
 */
class InstallerLayoutTest {

    static Path script(String name) {
        for (String c : new String[]{"../scripts/" + name, "scripts/" + name}) if (Files.exists(Path.of(c))) return Path.of(c).toAbsolutePath();
        fail(name + " not found from " + Path.of("").toAbsolutePath());
        return null;
    }

    /**
     * The runtime each platform's build carries has the module Lucene reads the JVM's options through (com.sun.management, by reflection,
     * so jdeps never names it): without it every command that opened the shelves printed "WARNING: Lucene cannot optimize algorithms or
     * calculate object sizes for JVMs that are not based on Hotspot" under its answer, on macOS and on Windows (2026-11-13).
     */
    @Test
    void theRuntimeEachPlatformsBuildCarriesHasTheModuleLuceneReadsTheJvmThrough() throws Exception {
        String module = HotSpotDiagnosticMXBean.class.getModule().getName();
        assertEquals("jdk.management", module);
        Matcher m = Pattern.compile("(?m)^EXTRA=(\\S+)").matcher(Files.readString(script("package-runtime.sh")));
        assertTrue(m.find(), "package-runtime.sh names the modules no jar names on its EXTRA line");
        assertTrue(List.of(m.group(1).split(",")).contains(module), m.group(1));
    }

    static String install(Path prefix, String base, String version) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", script("install-remote.sh").toString()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("RESEARCHZOSHO_PREFIX", prefix.toString()); env.put("RESEARCHZOSHO_DOWNLOAD_BASE", base); env.put("RESEARCHZOSHO_VERSION", version);
        env.remove("RESEARCHZOSHO_RUNTIME");
        env.put("PATH", Path.of(System.getProperty("java.home"), "bin") + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));   // a Java 21 or newer: the plain tarball
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    @Test @DisabledOnOs(OS.WINDOWS)
    void theInstallerKeepsVersionsSideBySideAndMovesAFirstLayoutInstallIntoThem(@TempDir Path tmp) throws Exception {
        for (String tool : List.of("curl", "tar")) assumeTrue(new ProcessBuilder("sh", "-c", "command -v " + tool).start().waitFor() == 0, tool + " is on this machine");
        UpdaterTest.release(tmp, "0.1.2", false); UpdaterTest.release(tmp, "0.1.3", false);
        Path prefix = tmp.resolve("prefix"), root = prefix.resolve("share").resolve("researchzosho");
        UpdaterTest.fakeRoot(root, "0.1.1");   // as the installer before 0.7.2 left it
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String base = UpdaterTest.serve(tmp.resolve("release"), s);
            String out = install(prefix, base, "0.1.2");
            assertTrue(out.contains("checksum verified"), out);
            Updater.Layout l = new Updater.Layout(root, Service.Os.linux);
            assertTrue(l.versioned(), "the updater knows the layout: " + out);
            assertEquals("0.1.2", l.current());
            assertEquals(List.of("0.1.1", "0.1.2"), l.installed(), "the first layout's version is kept beside the new one");
            assertFalse(Files.exists(root.resolve("lib")), "nothing is left at the top but bin/, versions/ and current");
            for (String name : List.of("researchzosho", "zosho")) assertEquals(Updater.unixLauncher(name), Files.readString(root.resolve("bin").resolve(name)), "the updater's own launcher, word for word");
            assertEquals("0.1.2 hi", UpdaterTest.run(prefix.resolve("bin").resolve("researchzosho"), "hi"), "the wrapper on the PATH runs the version current points at");
            install(prefix, base, "0.1.3");
            assertEquals(List.of("0.1.2", "0.1.3"), l.installed(), "two versions are kept");
            assertEquals("0.1.3", Updater.installedVersion(l));
            assertEquals("zosho 0.1.3", UpdaterTest.run(prefix.resolve("bin").resolve("zosho")));
        } finally { s.stop(0); }
    }

    @Test
    void theWindowsInstallerWritesTheUpdatersOwnLaunchers() throws Exception {
        String ps = Files.readString(script("install.ps1"), StandardCharsets.UTF_8);
        assertTrue(ps.chars().allMatch(c -> c < 128), "ASCII only: Windows PowerShell reads a BOM-less script in the system code page");
        // the launcher the script builds, its PowerShell escapes undone and $name put in, is the updater's
        int at = ps.indexOf("$text = \"") + "$text = \"".length();
        String built = ps.substring(at, ps.indexOf("\"\n", at)).replace("`r`n", "\r\n").replace("`\"", "\"");
        for (String name : List.of("researchzosho", "zosho")) assertEquals(Updater.windowsLauncher(name), built.replace("$name", name));
        assertTrue(ps.contains("[System.IO.File]::WriteAllText((Join-Path $dest 'current.txt'), \"$ver`r`n\")"), "current.txt names the version as the updater writes it");
    }
}
