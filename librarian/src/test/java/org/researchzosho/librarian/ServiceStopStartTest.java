package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `service stop` and `service start` (0.7.4) on each platform `service install` covers: the system's own way run (systemd's stop and start,
 * launchd's bootout and bootstrap, the Windows task ended and the server's recorded pid), a `serve` running in a terminal stopped by its
 * own pid record when no service is installed, and one line after each that says what was done and how it stands, as `service status`
 * begins. The system's commands are stood in; the server is a real process of this test.
 */
class ServiceStopStartTest {
    String home;
    Service.Exec exec;
    Duration settle;
    final List<List<String>> ran = new CopyOnWriteArrayList<>();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    Process server;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        exec = Service.EXEC; settle = Service.SETTLE;
        Service.EXEC = (cmd, o) -> { ran.add(cmd); return 0; };
        Service.SETTLE = Duration.ofSeconds(3);
    }
    @AfterEach void restore() {
        if (server != null) server.destroyForcibly();
        Service.EXEC = exec; Service.SETTLE = settle;
        System.setProperty("user.home", home); Config.invalidate();
    }

    PrintStream o() { return new PrintStream(out, true, StandardCharsets.UTF_8); }
    String said() { String s = out.toString(StandardCharsets.UTF_8).strip(); out.reset(); return s; }

    /** A server as `serve` leaves one: a Java process, its pid in the service's record. */
    long serverRunning(Path tmp) throws Exception {
        Path src = tmp.resolve("StandInServer.java");
        Files.writeString(src, "public class StandInServer { public static void main(String[] a) throws Exception { Thread.sleep(600_000); } }", StandardCharsets.UTF_8);
        server = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), src.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Files.createDirectories(Service.pidFile().getParent());
        Files.writeString(Service.pidFile(), Long.toString(server.pid()), StandardCharsets.UTF_8);
        return server.pid();
    }

    Service.Plan installed(Service.Os os) throws Exception {
        Service.Plan p = Service.plan(os, "/opt/researchzosho/bin/researchzosho", 4649, 3, Path.of(System.getProperty("user.home")));
        Files.createDirectories(p.definition().getParent());
        Files.writeString(p.definition(), p.text(), StandardCharsets.UTF_8);
        return p;
    }

    @Test void stopWithNoServiceInstalledEndsAServeRunningInATerminalByItsPidRecord(@TempDir Path tmp) throws Exception {
        Service.Plan plan = Service.plan(Service.Os.linux, "researchzosho", 4649, 3, Path.of(System.getProperty("user.home")));
        assertEquals(0, Service.run("stop", plan, o()));
        assertEquals("No service is installed here and no server was running, so there was nothing to stop. No server runs now.", said());
        long pid = serverRunning(tmp);
        assertEquals("No service is installed here; a server started with researchzosho serve is running (pid " + pid + ").", Service.stateSaid(plan));
        assertEquals(0, Service.run("stop", plan, o()));
        assertEquals("No service is installed here; stopped the server started with researchzosho serve (pid " + pid + "). No server runs now.", said());
        assertTrue(server.waitFor(10, TimeUnit.SECONDS), "the server process ended");
        assertTrue(ran.isEmpty(), "no system command, and nothing found by name: " + ran);
        assertEquals("No service is installed here, and no server is running.", Service.stateSaid(plan));
        assertEquals(1, Service.run("start", plan, o()));
        assertEquals("No service is installed here, so there is nothing to start. researchzosho service install installs the service and starts it; researchzosho serve runs the server in this terminal.", said());
    }

    @Test void onLinuxStopAndStartAreSystemdsAndEachSaysHowItStandsAfter(@TempDir Path tmp) throws Exception {
        Service.Plan plan = installed(Service.Os.linux);
        long[] pid = {0};
        Service.EXEC = (cmd, o) -> {   // systemd as it acts: stop ends the server, start runs one that records itself
            ran.add(cmd);
            if (cmd.equals(List.of("systemctl", "--user", "stop", Service.NAME)) && server != null) { server.destroy(); server.waitFor(5, TimeUnit.SECONDS); }
            if (cmd.equals(List.of("systemctl", "--user", "start", Service.NAME))) { try { pid[0] = serverRunning(tmp); } catch (Exception e) { return 1; } }
            return 0;
        };
        assertEquals(0, Service.run("start", plan, o()));
        assertEquals(List.of("systemctl", "--user", "start", Service.NAME), ran.get(0));
        assertEquals("Started the service. It is running now (pid " + pid[0] + ") and answers in a few seconds.", said());
        assertEquals(0, Service.run("start", plan, o()));
        assertEquals("The service is running already (pid " + pid[0] + ").", said());
        assertTrue(Service.stateSaid(plan).startsWith("The service is installed (systemd --user unit researchzosho") && Service.stateSaid(plan).endsWith("and running (pid " + pid[0] + ")."), Service.stateSaid(plan));
        assertEquals(0, Service.run("stop", plan, o()));
        assertEquals(List.of("systemctl", "--user", "stop", Service.NAME), ran.get(ran.size() - 1));
        assertEquals("Stopped the service. No server runs now; researchzosho service start starts it again, and it starts by itself at your next login.", said());
        assertEquals(0, Service.run("stop", plan, o()));
        assertEquals("The service was not running. No server runs now; researchzosho service start starts it again, and it starts by itself at your next login.", said());
        assertTrue(Service.stateSaid(plan).endsWith("and stopped: researchzosho service start starts it, and it starts by itself at your next login."), Service.stateSaid(plan));
    }

    @Test void onMacosLaunchdUnloadsAndLoadsTheAgentAndOnWindowsTheTaskEndsAndTheRecordedServerWith(@TempDir Path tmp) throws Exception {
        Service.Plan mac = installed(Service.Os.macos);
        serverRunning(tmp);
        Service.EXEC = (cmd, o) -> { ran.add(cmd); if (cmd.get(0).equals("launchctl") && cmd.get(1).equals("bootout")) { server.destroy(); server.waitFor(5, TimeUnit.SECONDS); } return 0; };
        assertEquals(0, Service.run("stop", mac, o()));
        assertEquals("launchctl", ran.get(0).get(0)); assertEquals("bootout", ran.get(0).get(1)); assertTrue(ran.get(0).get(2).endsWith("/" + Service.MAC_LABEL));
        assertTrue(said().startsWith("Stopped the service. No server runs now"));
        ran.clear();
        assertEquals(1, Service.run("start", mac, o()), "nothing records itself in this stand-in");
        assertEquals(List.of("launchctl", "bootstrap", ran.get(0).get(2), mac.definition().toString()), ran.get(0));
        assertTrue(said().startsWith("Asked the service to start, but no server is running yet."));
        ran.clear();
        Service.Plan win = installed(Service.Os.windows);
        long pid = serverRunning(tmp);
        Service.EXEC = (cmd, o) -> { ran.add(cmd); return 0; };
        assertEquals(0, Service.run("stop", win, o()));
        assertEquals(List.of("schtasks", "/End", "/TN", Service.WIN_TASK), ran.get(0));
        assertTrue(server.waitFor(10, TimeUnit.SECONDS), "the server is not the task's own process: it is stopped by its record, pid " + pid);
        assertTrue(said().startsWith("Stopped the service. No server runs now"));
        ran.clear();
        Service.run("start", win, o());
        assertEquals(Service.windowsStartNow(win.definition()), ran.get(0));
    }
}
