package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.Version;
import org.researchzosho.librarian.Helpers.Helper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A helpers set an update or a set-up has left behind is restarted (0.7.7). On the owner's helpers machine, `helpers install --on` updated
 * the program to 0.7.6 and set up the comic helper there, then found the service's unit unchanged and running and left it: the server
 * that ran was 0.7.5's, which served no comic helper, and the release's headline did not take effect until the service was restarted by
 * hand (2026-11-14). Now the server says its release in its health; an install restarts a set that runs an earlier release, or names
 * none, or does not serve a helper set up or served here, and says why in one line; a current set that serves everything is left alone;
 * and an update restarts every helpers set whose service runs. Through the seams: the machine, the systemctl calls and the health are
 * stood in, and the home is a scratch folder.
 */
class HelpersRestartTest {
    String home;
    HelpersInstall.Machine machine;
    Service.Exec exec;
    HelperCards.Probe probe;
    BooleanSupplier cuda, win;
    final List<String> ran = new ArrayList<>();
    final Set<String> active = new HashSet<>();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    HelpersInstallTest.Stand m;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        machine = HelpersInstall.MACHINE; exec = Service.EXEC; probe = HelperCards.PROBE; cuda = TranslationScores.CUDA_BUILD; win = TranslationScores.WINDOWS;
        // every command of the service's ways is stood in here, before anything else: no test may reach the machine's own systemctl
        Service.EXEC = (cmd, o) -> { ran.add(String.join(" ", cmd)); return cmd.contains("is-active") ? (active.contains(cmd.get(cmd.size() - 1)) ? 0 : 3) : 0; };
        m = new HelpersInstallTest.Stand(); HelpersInstall.MACHINE = m;
        HelperCards.PROBE = HelpersInstallTest.cards("0, GPU-aaaa0000-0000-0000-0000-000000000000, NVIDIA GeForce RTX 4060 Ti, 16380, 9100\n");
        TranslationScores.CUDA_BUILD = () -> true; TranslationScores.WINDOWS = () -> false;
    }
    @AfterEach void restore() {
        HelpersInstall.MACHINE = machine; Service.EXEC = exec; HelperCards.PROBE = probe; TranslationScores.CUDA_BUILD = cuda; TranslationScores.WINDOWS = win;
        System.setProperty("user.home", home); Config.invalidate();
    }

    int install(String typed) throws Exception {
        return HelpersInstall.install(new HelpersInstall.Options(HelperServer.all(), false, true, null, false, 0, Helpers.PORT), new BufferedReader(new StringReader(typed)), new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    String said() { String s = out.toString(StandardCharsets.UTF_8); out.reset(); return s; }

    /** A machine whose helpers are all set up, whose set's unit is written as this install writes it, and whose service runs. */
    void aSetThatRuns() throws Exception {
        m.set.addAll(List.of(Helper.METRICX, Helper.LABSE, Helper.LIKENESS, Helper.COMIC));
        assertEquals(0, install(""));   // its first install: the unit written and started
        said(); ran.clear();
        active.add(HelpersCli.UNIT);
    }

    @Test void theServerSaysItsReleaseInItsHealth() throws Exception {
        try (HelperServer h = HelperServer.serve("127.0.0.1", 0, "words", new LinkedHashMap<>(), List.of())) {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h.port() + "/health")).header("Authorization", "Bearer words").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(Version.string(), new ObjectMapper().readTree(r.body()).path("version").asText(), r.body());
            HelpersInstall.Running running = HelpersInstall.runningAt("http://127.0.0.1:" + h.port(), "words");
            assertNotNull(running); assertEquals(Version.string(), running.version());
        }
    }

    /** The helpers machine as the owner found it: the unit unchanged, the service running, and its server on the release before. */
    @Test void aSetThatRunsAnEarlierReleaseIsRestartedOntoThisOne() throws Exception {
        aSetThatRuns();
        m.version = "0.7.5";
        assertEquals(0, install(""));
        String s = said();
        assertTrue(s.contains("The service researchzosho-helpers ran 0.7.5; restarted onto " + Version.string() + ".\n"), s);
        assertFalse(s.contains("installed and running already"), s);
        assertTrue(ran.contains("systemctl --user restart researchzosho-helpers"), ran.toString());
        // a server from before its release was said: its health names none
        ran.clear(); m.version = "";
        assertEquals(0, install(""));
        s = said();
        assertTrue(s.contains("The service researchzosho-helpers ran an earlier release (its health names none); restarted onto " + Version.string() + ".\n"), s);
        assertTrue(ran.contains("systemctl --user restart researchzosho-helpers"), ran.toString());
    }

    /** A second library joining the machine: the set runs this release and serves everything set up here, and nothing interrupts it. */
    @Test void aCurrentSetThatServesEverythingIsLeftAlone() throws Exception {
        aSetThatRuns();
        assertEquals(0, install(""));
        String s = said();
        assertTrue(s.contains("The service researchzosho-helpers is installed and running already, as before.\n"), s);
        assertFalse(ran.stream().anyMatch(c -> c.contains("restart")), ran.toString());
        // one that does not answer now (its first start loads the models) and had nothing set up just now: left alone too
        m.answersNow = false;
        assertEquals(0, install(""));
        assertTrue(said().contains("is installed and running already, as before."));
        assertFalse(ran.stream().anyMatch(c -> c.contains("restart")), ran.toString());
    }

    /** A helper set up by this install: a server started before cannot serve it, whether or not it answers now. */
    @Test void aHelperSetUpNowOrMissingFromTheHealthIsServedByARestart() throws Exception {
        aSetThatRuns();
        m.set.remove(Helper.COMIC); m.notServed.add(Helper.COMIC);   // the set runs, and the comic helper is not set up yet
        assertEquals(0, install("y\n"));
        String s = said();
        assertTrue(s.contains("  the comic helper is ready.\n"), s);
        assertTrue(s.contains("The service researchzosho-helpers did not serve the comic helper yet; restarted, so that it does.\n"), s);
        assertTrue(ran.contains("systemctl --user restart researchzosho-helpers"), ran.toString());
        // the same when the set does not answer at the moment
        ran.clear(); m.set.remove(Helper.COMIC); m.answersNow = false;
        assertEquals(0, install("y\n"));
        assertTrue(said().contains("did not serve the comic helper yet; restarted, so that it does.\n"));
        assertTrue(ran.contains("systemctl --user restart researchzosho-helpers"), ran.toString());
        // a helper set up here before (by its own command) that the running set does not serve
        ran.clear(); m.answersNow = true; m.notServed.clear(); m.notServed.add(Helper.LIKENESS);
        assertEquals(0, install(""));
        assertTrue(said().contains("The service researchzosho-helpers did not serve the likeness helper yet; restarted, so that it does.\n"));
        assertTrue(ran.contains("systemctl --user restart researchzosho-helpers"), ran.toString());
    }

    /** `helpers service install`, the other way the unit is written: the same check. */
    @Test void theServiceCommandRestartsASetThatRunsAnEarlierRelease() throws Exception {
        assumeTrue(Service.os() == Service.Os.linux, "the helpers' service is a systemd unit");
        m.set.addAll(List.of(Helper.METRICX, Helper.LABSE, Helper.LIKENESS, Helper.COMIC));
        String[] args = {"helpers", "service", "install", "--gpu", "0"};
        int[] rc = {-9};
        TranslationDirectionTest.out(() -> rc[0] = HelpersCli.run(args, 1), false);
        assertEquals(0, rc[0]);
        ran.clear(); active.add(HelpersCli.UNIT); m.version = "0.7.5";
        String s = TranslationDirectionTest.out(() -> rc[0] = HelpersCli.run(args, 1), false);
        assertEquals(0, rc[0], s);
        assertTrue(s.contains("The service researchzosho-helpers ran 0.7.5; restarted onto " + Version.string() + ".\n"), s);
        assertTrue(ran.contains("systemctl --user restart researchzosho-helpers"), ran.toString());
        ran.clear(); m.version = Version.string();
        s = TranslationDirectionTest.out(() -> rc[0] = HelpersCli.run(args, 1), false);
        assertTrue(s.contains("is installed and running already, as before."), s);
        assertFalse(ran.stream().anyMatch(c -> c.contains("restart")), ran.toString());
    }

    /** Writes a set's unit as an install would, by its name and port. */
    static void unit(String name, int port) throws Exception {
        Path f = HelpersInstall.unitFile(name);
        Files.createDirectories(f.getParent());
        Files.writeString(f, HelpersInstall.unitText("/opt/rz/bin/researchzosho", port, HelpersInstall.secretFile(), name.endsWith("gpu3") ? " --gpu 3" : " --gpu 0", null, "/usr/lib/jvm/java"), StandardCharsets.UTF_8);
    }

    /**
     * The update restarts every helpers set whose service runs, after it has put the new release in place and before the library's own
     * service (whose restart ends the update's process when the service itself runs it), each said; a set whose service does not run is
     * left as it is. On a machine that serves helpers and holds no library, no library service is restarted, and none is said to have failed.
     */
    @Test void anUpdateRestartsTheHelpersSetsThatRunAndLeavesTheOthers() throws Exception {
        assumeTrue(Service.os() == Service.Os.linux, "the helpers' service is a systemd unit");
        unit(HelpersCli.UNIT, 8217); unit(HelpersCli.UNIT + "-gpu3", 8218);
        active.add(HelpersCli.UNIT);
        PrintStream p = new PrintStream(out, true, StandardCharsets.UTF_8);
        String note = Updater.restartOnto("9.9.9", p);
        String s = said();
        assertEquals(List.of("systemctl --user is-active researchzosho-helpers", "systemctl --user restart researchzosho-helpers", "systemctl --user is-active researchzosho-helpers-gpu3"), ran, "a helpers machine with no library: no library service is restarted");
        assertTrue(s.contains("researchzosho: the helpers service researchzosho-helpers restarted onto 9.9.9\n"), s);
        assertTrue(s.contains("researchzosho: the helpers service researchzosho-helpers-gpu3 is installed and not running; left as it is\n"), s);
        assertEquals("no library service is installed here; the helpers service researchzosho-helpers restarted onto 9.9.9", note);
        assertNull(Updater.restartingTo(), "no library service restarted: the mark for its jobs goes");
        // a machine with a library service too: the helpers first, then the library's own
        Path lib = Path.of(System.getProperty("user.home")).resolve(".config/systemd/user/" + Service.NAME + ".service");
        Files.writeString(lib, "[Unit]\n", StandardCharsets.UTF_8);
        ran.clear(); active.add(HelpersCli.UNIT + "-gpu3");
        note = Updater.restartOnto("9.9.9", p);
        said();
        assertEquals(List.of("systemctl --user is-active researchzosho-helpers", "systemctl --user restart researchzosho-helpers", "systemctl --user is-active researchzosho-helpers-gpu3", "systemctl --user restart researchzosho-helpers-gpu3",
                "systemctl --user restart researchzosho"), ran);
        assertEquals("the service restarted; the helpers service researchzosho-helpers restarted onto 9.9.9; the helpers service researchzosho-helpers-gpu3 restarted onto 9.9.9", note);
        assertEquals("9.9.9", Updater.restartingTo(), "the library's service restarted: its jobs say they resume after the update");
        // a machine with no helpers set: the library's service alone, as before
        Files.delete(HelpersInstall.unitFile(HelpersCli.UNIT)); Files.delete(HelpersInstall.unitFile(HelpersCli.UNIT + "-gpu3"));
        ran.clear();
        assertEquals("the service restarted", Updater.restartOnto("9.9.9", p));
        assertEquals(List.of("systemctl --user restart researchzosho"), ran);
        Updater.restartingTo();
    }
}
