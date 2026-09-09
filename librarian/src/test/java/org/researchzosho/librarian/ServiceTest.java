package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The three service definitions, as text: what each platform is told to run. */
class ServiceTest {

    @Test
    void linuxUnit() {
        var p = Service.plan(Service.Os.linux, "/opt/codezaiku/bin/codezaiku", 4649, 3, Path.of("/home/x"));
        assertEquals(Path.of("/home/x/.config/systemd/user/researchzosho.service"), p.definition());
        assertTrue(p.text().contains("ExecStart=/opt/codezaiku/bin/codezaiku librarian serve --port 4649 --crew-hour 3 --log /home/x/.researchzosho/logs/librarian-serve.log"));
        var z = Service.plan(Service.Os.linux, "/opt/codezaiku/bin/researchzosho", 4649, 3, Path.of("/home/x"));
        assertTrue(z.text().contains("ExecStart=/opt/codezaiku/bin/researchzosho serve --port 4649"), "the researchzosho command needs no verb");
        assertTrue(p.text().contains("Restart=on-failure"));
        assertTrue(p.text().contains("-Xmx4g"), "the daemon's heap is capped: " + p.text());
        assertEquals("systemctl --user enable --now researchzosho", String.join(" ", p.install().get(1)));
        assertEquals("systemctl --user is-active researchzosho", String.join(" ", p.status()));
    }

    @Test
    void macLaunchAgent() {
        var p = Service.plan(Service.Os.macos, "/usr/local/bin/codezaiku", 4649, 3, Path.of("/Users/x"));
        assertEquals(Path.of("/Users/x/Library/LaunchAgents/org.researchzosho.librarian.plist"), p.definition());
        assertTrue(p.text().contains("<string>/usr/local/bin/codezaiku</string><string>librarian</string><string>serve</string>"));
        assertTrue(p.text().contains("<key>KeepAlive</key><true/>"));
        assertTrue(String.join(" ", p.install().get(1)).startsWith("launchctl bootstrap gui/"));
    }

    @Test
    void windowsTaskRunsHidden() {
        var p = Service.plan(Service.Os.windows, "C:\\codezaiku\\bin\\codezaiku.bat", 4649, 3, Path.of("C:\\Users\\x"));
        String tr = p.install().get(0).get(p.install().get(0).indexOf("/TR") + 1);
        assertEquals("powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File " + p.definition(), tr);
        assertTrue(p.definition().toString().endsWith("ResearchZosho.ps1"));
        Path log = Path.of("C:\\Users\\x").resolve(".researchzosho").resolve("logs").resolve("librarian-serve.log");
        assertTrue(p.text().contains("& 'C:\\codezaiku\\bin\\codezaiku.bat' librarian serve --port 4649 --crew-hour 3 --log '" + log + "'"), p.text());
        // install starts the script now (the task covers the next logon) through Start-Process, so the server
        // inherits none of the caller's handles — a JVM-spawned child held an ssh session open for fifteen minutes
        var now = p.install().get(1);
        assertEquals(List.of("powershell", "-NoProfile", "-Command"), now.subList(0, 3));
        assertTrue(now.get(3).startsWith("Start-Process -FilePath powershell") && now.get(3).contains("-WindowStyle Hidden") && now.get(3).contains("ResearchZosho.ps1"), now.get(3));
        assertTrue(p.text().chars().allMatch(c -> c < 128), "ASCII only — PowerShell reads a BOM-less file as ANSI");
        assertEquals("schtasks /Query /TN ResearchZosho", String.join(" ", p.status()));
        assertEquals("ONLOGON", p.install().get(0).get(p.install().get(0).indexOf("/SC") + 1));
    }

    @Test
    void execResolutionPrefersTheExplicitPath() {
        assertEquals("/x/codezaiku", Service.resolveExec("/x/codezaiku"));
    }

    @Test
    void uninstallStopsTheRecordedServerAndLeavesAReusedPidAlone(@TempDir Path tmp) throws Exception {
        Path pid = tmp.resolve("researchzosho.pid");
        var out = new java.io.ByteArrayOutputStream(); var ps = new java.io.PrintStream(out);
        assertFalse(Service.stopRecorded(pid, ps), "no record, nothing to stop");
        // a JVM that is not this one: a child java that sleeps
        String jvm = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(jvm, "-cp", System.getProperty("java.class.path"), ServiceTest.class.getName() + "$Sleeper").redirectErrorStream(true).start();
        java.nio.file.Files.writeString(pid, Long.toString(child.pid()));
        Thread.sleep(300);
        assertTrue(child.isAlive());
        assertTrue(Service.stopRecorded(pid, ps), "the recorded JVM is stopped");
        assertFalse(child.isAlive());
        assertFalse(java.nio.file.Files.exists(pid), "the record is cleared");
        assertTrue(out.toString().contains("stopped the running server"), out.toString());
        // a recorded pid that is no longer a JVM is left alone
        Process other = new ProcessBuilder("sleep", "20").start();
        java.nio.file.Files.writeString(pid, Long.toString(other.pid()));
        assertFalse(Service.stopRecorded(pid, ps));
        assertTrue(other.isAlive(), "not ours; not touched");
        other.destroy();
    }

    /** A JVM that waits to be stopped. */
    public static final class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(60_000); } }

    @Test
    void theHostIsWrittenOnlyWhenItIsNotLoopback() {
        var lan = Service.plan(Service.Os.linux, "/opt/rz/bin/researchzosho", "0.0.0.0", 4649, 3, Path.of("/home/x"));
        assertTrue(lan.text().contains("serve --host 0.0.0.0 --port 4649"), lan.text());
        var mac = Service.plan(Service.Os.macos, "/usr/local/bin/researchzosho", "0.0.0.0", 4649, 3, Path.of("/Users/x"));
        assertTrue(mac.text().contains("<string>--host</string><string>0.0.0.0</string>"), mac.text());
        var win = Service.plan(Service.Os.windows, "C:\\rz\\bin\\researchzosho.cmd", "0.0.0.0", 4649, 3, Path.of("C:\\Users\\x"));
        assertTrue(win.text().contains("serve --host 0.0.0.0 --port 4649"), win.text());
        var local = Service.plan(Service.Os.linux, "/opt/rz/bin/researchzosho", "127.0.0.1", 4649, 3, Path.of("/home/x"));
        assertFalse(local.text().contains("--host"), "loopback is the default and leaves the unit as it was");
    }
}
