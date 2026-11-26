package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The move's steps that are each system's own, through stand-ins for launchctl, schtasks and the detached start: macOS loads its agents
 * again (the model's by its label; the library's own from inside it by a helper of its own process group); Windows points the logon task
 * at the script in the new folder (and puts the script back when it cannot), stops its model proxy before the rename and starts it again,
 * and gives a Python environment's .exe commands the new path; the path forms of each system.
 */
class HomeMoveSystemsTest {
    @TempDir Path home;
    String realHome;
    Searx.Runner realSearx;
    ModelServer.Runner realModel;
    ModelServer.Os realOs;
    ModelServer.Detacher realDetach;
    Predicate<String> realHealth;
    String realAgent;
    final List<List<String>> ran = new ArrayList<>(), detached = new ArrayList<>();
    int printCode = 0, createCode = 0, queryCode = 0;

    @BeforeEach void own() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate(); Config.forgetHome();
        realSearx = Searx.runner; realModel = ModelServer.runner; realOs = ModelServer.os; realDetach = ModelServer.detach; realHealth = ModelServer.health; realAgent = HomeMove.AGENT;
        Searx.runner = cmd -> new Searx.Result(1, "no such object");   // no containers here
        ModelServer.runner = cmd -> {
            ran.add(cmd);
            String c = String.join(" ", cmd);
            if (c.startsWith("launchctl print")) return new ModelServer.Result(printCode, "");
            if (c.startsWith("schtasks /Query") || c.startsWith("schtasks /query")) return new ModelServer.Result(queryCode, "");
            if (c.startsWith("schtasks /Create") || c.startsWith("schtasks /create")) return new ModelServer.Result(createCode, createCode == 0 ? "SUCCESS" : "ERROR: Access is denied.");
            return new ModelServer.Result(0, "");
        };
        ModelServer.detach = cmd -> detached.add(cmd);
        ModelServer.health = base -> false;
        HomeMove.AGENT = null;
        HomeMove.RELOAD_WAIT_MS = 0;
        HomeMove.reloading = false;
    }

    @AfterEach void back() {
        Searx.runner = realSearx; ModelServer.runner = realModel; ModelServer.os = realOs; ModelServer.detach = realDetach; ModelServer.health = realHealth; HomeMove.AGENT = realAgent;
        HomeMove.RELOAD_WAIT_MS = 30_000; HomeMove.reloading = false; HomeMove.PERL = Path.of("/usr/bin/perl");
        System.setProperty("user.home", realHome);
        Config.invalidate(); Config.forgetHome();
    }

    Path was() { return home.resolve(".codezaiku"); }
    Path now() { return home.resolve(".researchzosho"); }
    Path agents() { return home.resolve("Library").resolve("LaunchAgents"); }
    static void put(Path f, String t) throws IOException { Files.createDirectories(f.getParent()); Files.writeString(f, t); }

    String atStart() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        HomeMove.atStart("serve", new PrintStream(err, true, StandardCharsets.UTF_8));
        return err.toString(StandardCharsets.UTF_8);
    }

    /** The test machine's shape on a Mac: this program's model server in CodeZaiku's folder as a native program, its two launch agents. */
    void macShaped() throws IOException {
        put(was().resolve("config"), "drive = http://127.0.0.1:8211/v1\nweb.signin = on\n");
        put(was().resolve("logs").resolve("librarian-serve.log"), "x\n");
        put(was().resolve("chat").resolve("h"), "x");
        Path m = was().resolve("model");
        put(m.resolve("config.yaml"), "# ResearchZosho: the model server on demand.\nmodels:\n  \"q\":\n    cmd: " + m.resolve("llama").resolve("llama-server") + " --port ${PORT}\n");
        put(m.resolve("llama").resolve("llama-server"), "binary");
        put(m.resolve("llama-swap"), "binary");
        put(agents().resolve(ModelServer.LABEL + ".plist"), "<plist><dict><key>Label</key><string>" + ModelServer.LABEL + "</string><key>ProgramArguments</key><array><string>"
                + m.resolve("llama-swap") + "</string><string>--config</string><string>" + m.resolve("config.yaml") + "</string></array><key>StandardOutPath</key><string>" + m.resolve("llama-swap.log") + "</string></dict></plist>\n");
        put(agents().resolve(Service.MAC_LABEL + ".plist"), "<plist><dict><key>Label</key><string>" + Service.MAC_LABEL + "</string><key>ProgramArguments</key><array><string>/Users/x/.local/bin/researchzosho</string><string>serve</string><string>--log</string><string>"
                + was().resolve("logs").resolve("librarian-serve.log") + "</string></array><key>StandardOutPath</key><string>" + was().resolve("logs").resolve("librarian-serve.log") + "</string></dict></plist>\n");
    }

    List<String> launchctl() { return ran.stream().filter(c -> c.get(0).equals("launchctl")).map(c -> String.join(" ", c)).toList(); }

    @Test
    void onAMacTheModelsAgentIsLoadedAgainAndTheLibrarysToo() throws Exception {
        ModelServer.os = ModelServer.Os.macos;
        macShaped();
        String said = atStart();
        assertTrue(said.contains("They are now in " + now()), said);
        String uid = Service.uid();
        String plist = Files.readString(agents().resolve(Service.MAC_LABEL + ".plist"));
        assertTrue(plist.contains("<string>" + now().resolve("logs").resolve("librarian-serve.log") + "</string>") && !plist.contains(was().toString()), plist);
        assertTrue(Files.readString(now().resolve("model").resolve("config.yaml")).contains(now().resolve("model").resolve("llama").resolve("llama-server").toString()), "the proxy starts the server from the new folder");
        assertEquals(List.of("launchctl bootout gui/" + uid + "/" + ModelServer.LABEL, "launchctl bootstrap gui/" + uid + " " + agents().resolve(ModelServer.LABEL + ".plist"),
                "launchctl print gui/" + uid + "/" + Service.MAC_LABEL, "launchctl bootout gui/" + uid + "/" + Service.MAC_LABEL, "launchctl bootstrap gui/" + uid + " " + agents().resolve(Service.MAC_LABEL + ".plist")), launchctl(),
                "each agent by its exact label, the model's first; the library's from outside it, as it is loaded and not running");
        assertTrue(said.contains("its model server's launch agent (" + ModelServer.LABEL + ") was loaded again from its file") && said.contains("its service was loaded again from its file"), said);
        assertTrue(detached.isEmpty());
    }

    @Test
    void onAMacALibraryAgentNotLoadedIsLeftForServiceStart() throws Exception {
        ModelServer.os = ModelServer.Os.macos;
        macShaped();
        printCode = 113;   // launchctl print: no such service
        String said = atStart();
        assertTrue(said.contains("its service is not loaded now; researchzosho service start starts it from its file"), said);
        assertTrue(launchctl().stream().noneMatch(c -> c.contains(Service.MAC_LABEL) && (c.contains("bootout") || c.contains("bootstrap"))), launchctl().toString());
    }

    @Test
    void onAMacTheAgentThatMovedItselfIsLoadedAgainByAHelperOfItsOwnProcessGroup() throws Exception {
        ModelServer.os = ModelServer.Os.macos;
        macShaped();
        HomeMove.AGENT = Service.MAC_LABEL;   // launchd started this process as the library's agent
        Path perl = home.resolve("perl");   // a Mac's /usr/bin/perl, on any system: the helper is only written down here
        Files.writeString(perl, "#!/bin/sh\n"); perl.toFile().setExecutable(true);
        HomeMove.PERL = perl;
        String said = atStart();
        assertEquals(1, detached.size(), detached.toString());
        List<String> helper = detached.get(0);
        String uid = Service.uid();
        assertEquals(perl.toString(), helper.get(0));
        assertTrue(helper.get(3).startsWith("POSIX::setsid();") && helper.get(3).contains("'launchctl', 'bootout', $ARGV[0]") && helper.get(3).contains("system('launchctl', 'bootstrap', $ARGV[1], $ARGV[2]) == 0"), helper.get(3));
        assertEquals(List.of("gui/" + uid + "/" + Service.MAC_LABEL, "gui/" + uid, agents().resolve(Service.MAC_LABEL + ".plist").toString()), helper.subList(4, 7), "its own label, exactly, and its file");
        assertTrue(launchctl().stream().noneMatch(c -> c.contains(Service.MAC_LABEL)), "never from inside the agent itself: " + launchctl());
        assertTrue(said.contains("its service is loaded again with the new folder: it stops and starts once now"), said);
        assertTrue(said.contains("ResearchZosho's service was not loaded again with its new folder; it goes on"), "a helper that did nothing in time: the service goes on as it is, and says so: " + said);
    }

    @Test
    void onAMacTheWayBackLoadsTheAgentsAgainWithTheOldFolder() throws Exception {
        ModelServer.os = ModelServer.Os.macos;
        macShaped();
        String before = Files.readString(agents().resolve(Service.MAC_LABEL + ".plist")), model = Files.readString(agents().resolve(ModelServer.LABEL + ".plist"));
        atStart();
        ran.clear();
        assertTrue(HomeMove.back().startsWith("ResearchZosho's files are back in " + was()));
        assertEquals(before, Files.readString(agents().resolve(Service.MAC_LABEL + ".plist")));
        assertEquals(model, Files.readString(agents().resolve(ModelServer.LABEL + ".plist")));
        String uid = Service.uid();
        assertTrue(launchctl().containsAll(List.of("launchctl bootout gui/" + uid + "/" + ModelServer.LABEL, "launchctl bootstrap gui/" + uid + " " + agents().resolve(ModelServer.LABEL + ".plist"))), launchctl().toString());
    }

    // ---- Windows ----

    @Test
    void windowsPathFormsAreMatchedInEveryCaseAndCodeZaikusOwnPathsStay() {
        assertEquals(List.of("C:\\Users\\x\\.codezaiku\\", "C:/Users/x/.codezaiku/", "%USERPROFILE%\\.codezaiku\\", "$env:USERPROFILE\\.codezaiku\\"),
                HomeMove.forms("C:\\Users\\x\\.codezaiku", "C:\\Users\\x", true));
        assertEquals(List.of("/home/x/.codezaiku/", "%h/.codezaiku/", "$HOME/.codezaiku/"), HomeMove.forms("/home/x/.codezaiku", "/home/x", false));
        String script = "$log = 'C:\\Users\\x\\.codezaiku\\logs\\librarian-serve.log'\n& 'c:\\users\\x\\.CodeZaiku\\video\\venv\\Scripts\\python.exe'\n"
                + "$p = \"$env:USERPROFILE\\.codezaiku\\labse\"\nset V=%USERPROFILE%\\.codezaiku\\video\\venv\n& 'C:/Users/x/.codezaiku/logs/a.log'\n"
                + "$cz = 'C:\\Users\\x\\.codezaiku\\sandbox\\x'\n$l2 = 'C:\\Users\\x\\.codezaiku\\logsbook'\n";
        String moved = HomeMove.relocated(script, "C:\\Users\\x\\.codezaiku", "C:\\Users\\x\\.researchzosho", "C:\\Users\\x", true, List.of("logs", "video", "labse"));
        assertEquals("$log = 'C:\\Users\\x\\.researchzosho\\logs\\librarian-serve.log'\n& 'C:\\Users\\x\\.researchzosho\\video\\venv\\Scripts\\python.exe'\n"
                + "$p = \"$env:USERPROFILE\\.researchzosho\\labse\"\nset V=%USERPROFILE%\\.researchzosho\\video\\venv\n& 'C:/Users/x/.researchzosho/logs/a.log'\n"
                + "$cz = 'C:\\Users\\x\\.codezaiku\\sandbox\\x'\n$l2 = 'C:\\Users\\x\\.codezaiku\\logsbook'\n", moved, "CodeZaiku's sandbox and a longer name stay");
    }

    @Test
    void aWindowsLaunchersFirstLineIsGivenTheNewPathAndEveryOtherByteStays() {
        byte[] stub = new byte[300];
        for (int i = 0; i < stub.length; i++) stub[i] = (byte) (i % 251);   // the launcher program, with bytes of every kind
        String old = "#!C:\\Users\\x\\.codezaiku\\video\\venv\\Scripts\\python.exe\n";
        byte[] zip = "PK\u0003\u0004__main__.pyimport sys\nfrom yt_dlp import main\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] exe = concat(stub, old.getBytes(StandardCharsets.UTF_8), zip);
        byte[] out = HomeMove.launcher(exe, line -> HomeMove.relocated(line, "C:\\Users\\x\\.codezaiku", "C:\\Users\\x\\.researchzosho", "C:\\Users\\x", true, List.of("video")));
        assertArrayEquals(concat(stub, "#!C:\\Users\\x\\.researchzosho\\video\\venv\\Scripts\\python.exe\n".getBytes(StandardCharsets.UTF_8), zip), out);
        assertSame(exe, HomeMove.launcher(exe, line -> line), "nothing to change: the same bytes, not written");
        byte[] quoted = concat(stub, "#!\"C:\\Users\\a b\\.codezaiku\\video\\venv\\Scripts\\python.exe\"\n".getBytes(StandardCharsets.UTF_8), zip);
        assertArrayEquals(concat(stub, "#!\"C:\\Users\\a b\\.researchzosho\\video\\venv\\Scripts\\python.exe\"\n".getBytes(StandardCharsets.UTF_8), zip),
                HomeMove.launcher(quoted, line -> HomeMove.relocated(line, "C:\\Users\\a b\\.codezaiku", "C:\\Users\\a b\\.researchzosho", "C:\\Users\\a b", true, List.of("video"))), "a folder with a space: the quoted form");
    }

    static byte[] concat(byte[]... parts) {
        int n = 0; for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n]; int at = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, at, p.length); at += p.length; }
        return out;
    }

    void windowsShaped() throws IOException {
        put(was().resolve("config"), "drive = http://127.0.0.1:8211/v1\nweb.signin = on\n");
        put(was().resolve("logs").resolve("librarian-serve.log"), "x\n");
        put(was().resolve(HomeMove.TASK_SCRIPT), "$log = '" + was().resolve("logs").resolve("librarian-serve.log") + "'\n& 'C:\\rz\\bin\\researchzosho.bat' serve --log $log\n");
        Path m = was().resolve("model");
        put(m.resolve("config.yaml"), "# ResearchZosho: the model server on demand.\nmodels:\n  \"q\":\n    cmd: " + m.resolve("llama").resolve("llama-server.exe") + " --port ${PORT}\n");
        put(m.resolve("start.ps1"), "$kept = '" + m.resolve(ModelServer.SWAP_PID) + "'\nStart-Process '" + m.resolve("llama-swap.exe") + "'\n");
        put(m.resolve(ModelServer.SWAP_PID), "4194399\n");
        put(m.resolve("llama-swap.exe"), "binary");
    }

    @Test
    void onWindowsTheTaskIsPointedAtTheNewScriptAndTheModelProxyIsStoppedAndStartedAgain() throws Exception {
        ModelServer.os = ModelServer.Os.windows;
        windowsShaped();
        String said = atStart();
        assertTrue(said.contains("They are now in " + now()), said);
        assertTrue(Files.isRegularFile(now().resolve(HomeMove.TASK_SCRIPT)) && !Files.exists(was().resolve(HomeMove.TASK_SCRIPT)), "its script moved");
        assertTrue(Files.readString(now().resolve(HomeMove.TASK_SCRIPT)).contains(now().resolve("logs").resolve("librarian-serve.log").toString()));
        assertTrue(ran.contains(List.of("schtasks", "/Create", "/F", "/SC", "ONLOGON", "/TN", Service.WIN_TASK, "/TR", Service.windowsCommand(now().resolve(HomeMove.TASK_SCRIPT)), "/RL", "LIMITED")), ran.toString());
        assertTrue(said.contains("the logon task " + Service.WIN_TASK + " now starts the library from its script in the new folder"), said);
        assertTrue(Files.readString(now().resolve("model").resolve("start.ps1")).contains(now().resolve("model").resolve("llama-swap.exe").toString()));
        assertTrue(ran.stream().anyMatch(c -> c.get(0).equals("schtasks") && c.contains(ModelServer.TASK) && c.contains("/create") && String.join(" ", c).contains(now().resolve("model").resolve("start.ps1").toString())), ran.toString());
        assertEquals(List.of(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", now().resolve("model").resolve("start.ps1").toString())), detached, "the proxy the move stopped, started again from the new folder");
    }

    @Test
    void onWindowsATaskThatCannotBeChangedKeepsItsScriptWhereItLooksAndItIsSaid() throws Exception {
        ModelServer.os = ModelServer.Os.windows;
        windowsShaped();
        createCode = 1;
        String said = atStart();
        assertTrue(said.contains("the logon task " + Service.WIN_TASK + " could not be pointed at the new folder (ERROR: Access is denied.), so its script " + HomeMove.TASK_SCRIPT + " stays in " + was()), said);
        assertTrue(Files.isRegularFile(was().resolve(HomeMove.TASK_SCRIPT)), "where the task looks for it: the library starts at the next logon");
        assertTrue(Files.readString(was().resolve(HomeMove.TASK_SCRIPT)).contains(now().resolve("logs").resolve("librarian-serve.log").toString()), "and it names the log where the server writes now");
    }

    @Test
    void onWindowsWithNoTaskNothingIsRegistered() throws Exception {
        ModelServer.os = ModelServer.Os.windows;
        windowsShaped();
        queryCode = 1;
        atStart();
        assertTrue(ran.stream().noneMatch(c -> c.get(0).equals("schtasks") && (c.contains("/Create") || c.contains("/create"))), ran.toString());
    }

    @Test
    void onWindowsAFailureBeforeTheLastStepStartsTheModelProxyAgainWhereItWas() throws Exception {
        ModelServer.os = ModelServer.Os.windows;
        windowsShaped();
        HomeMove.STEP = s -> { if (s.equals("moved logs")) throw new IllegalStateException("a test's failure"); };
        try {
            String said = atStart();
            assertTrue(said.contains("did not move its files out of CodeZaiku's folder: a test's failure"), said);
        } finally { HomeMove.STEP = s -> { }; }
        assertTrue(Files.isRegularFile(was().resolve("model").resolve("start.ps1")) && !Files.exists(now()));
        assertEquals(List.of(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", was().resolve("model").resolve("start.ps1").toString())), detached, "started again from where it was");
    }

    @Test
    void onWindowsTheWayBackPointsTheTaskAtTheOldScriptAgain() throws Exception {
        ModelServer.os = ModelServer.Os.windows;
        windowsShaped();
        String script = Files.readString(was().resolve(HomeMove.TASK_SCRIPT)), start = Files.readString(was().resolve("model").resolve("start.ps1"));
        atStart();
        ran.clear(); detached.clear();
        assertTrue(HomeMove.back().startsWith("ResearchZosho's files are back in " + was()));
        assertEquals(script, Files.readString(was().resolve(HomeMove.TASK_SCRIPT)));
        assertEquals(start, Files.readString(was().resolve("model").resolve("start.ps1")));
        assertTrue(ran.contains(List.of("schtasks", "/Create", "/F", "/SC", "ONLOGON", "/TN", Service.WIN_TASK, "/TR", Service.windowsCommand(was().resolve(HomeMove.TASK_SCRIPT)), "/RL", "LIMITED")), ran.toString());
    }

    @Test
    void theWayBackIsRefusedWhileTheServiceRunsFromTheNewFolder() throws Exception {
        ModelServer.os = ModelServer.Os.linux;
        put(was().resolve("config"), "web.signin = on\n");
        put(was().resolve("logs").resolve("l"), "x");
        atStart();
        Process server = StandInServer.start(home.resolve("stand-in"));
        try {
            Files.writeString(now().resolve("researchzosho.pid"), Long.toString(server.pid()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(1, HomeMove.command(new String[]{"home", "move", "--back"}, 1, new PrintStream(out, true, StandardCharsets.UTF_8)));
            assertTrue(Files.isDirectory(now().resolve("logs")) && !Files.exists(was().resolve("logs")), "nothing moved back");
        } finally { server.destroyForcibly().waitFor(); }
    }
}
