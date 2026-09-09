package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Run The Librarian as a user service on all three platforms: a systemd --user unit on Linux, a
 * LaunchAgent on macOS, a logon Scheduled Task on Windows. No root, no admin. The daemon writes
 * its own log ({@code --log}) so the three definitions stay small, and {@code status} asks the
 * platform, never a port. The launcher path comes from {@code --exec}, else
 * {@code RESEARCHZOSHO_LAUNCHER} (bin/researchzosho sets it), else {@code CODEZAIKU_LAUNCHER}'s sibling
 * for an install from before the split, else {@code researchzosho} / {@code zosho} / {@code codezaiku} on PATH.
 */
public final class Service {

    private Service() { }

    // ResearchZosho (研究蔵書 — the research holdings), named 2026-09-05; The Librarian is its voice.
    public static final String NAME = "researchzosho";
    public static final String MAC_LABEL = "org.researchzosho.librarian";
    public static final String WIN_TASK = "ResearchZosho";

    public enum Os { linux, macos, windows }

    public static Os os() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (n.contains("win")) return Os.windows;
        if (n.contains("mac") || n.contains("darwin")) return Os.macos;
        return Os.linux;
    }

    public record Plan(Os os, Path definition, List<List<String>> install, List<List<String>> uninstall, List<String> status, String text) { }

    // ---- the three definitions, as pure text (tested) ----

    static String systemdUnit(String exec, String host, int port, int crewHour, Path log) {
        return "[Unit]\nDescription=ResearchZosho — The Librarian: the library protocol over HTTP, research runs, the housekeeping\n"
                + "After=network-online.target\n\n[Service]\nType=simple\n"
                + "ExecStart=" + exec + " " + verbFor(exec) + "serve" + hostFlag(host, " --host ", "") + " --port " + port + " --crew-hour " + crewHour + " --log " + log + "\n"
                + "Restart=on-failure\nRestartSec=10\nEnvironment=RESEARCHZOSHO_SERVICE=1\n" + passthroughEnv("Environment=", "\n")
                + (heapOpts().isEmpty() ? "" : "Environment=\"RESEARCHZOSHO_JAVA_OPTS=" + heapOpts() + "\"\n")
                + "\n"
                + "[Install]\nWantedBy=default.target\n";
    }

    static String launchAgent(String exec, String host, int port, int crewHour, Path log) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\"><dict>\n"
                + "  <key>Label</key><string>" + MAC_LABEL + "</string>\n"
                + "  <key>ProgramArguments</key><array>\n"
                + "    <string>" + exec + "</string>" + (verbFor(exec).isEmpty() ? "" : "<string>librarian</string>") + "<string>serve</string>\n"
                + hostFlag(host, "    <string>--host</string><string>", "</string>\n")
                + "    <string>--port</string><string>" + port + "</string>\n"
                + "    <string>--crew-hour</string><string>" + crewHour + "</string>\n"
                + "    <string>--log</string><string>" + log + "</string>\n"
                + "  </array>\n"
                + "  <key>RunAtLoad</key><true/>\n  <key>KeepAlive</key><true/>\n"
                + "  <key>EnvironmentVariables</key><dict><key>RESEARCHZOSHO_SERVICE</key><string>1</string>\n" + passthroughEnv("    <key>", "</key><string>", "</string>\n")
                + (heapOpts().isEmpty() ? "" : "    <key>RESEARCHZOSHO_JAVA_OPTS</key><string>" + heapOpts() + "</string>\n")
                + "    <key>PATH</key><string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin</string></dict>\n"
                + "  <key>StandardOutPath</key><string>" + log + "</string>\n"
                + "  <key>StandardErrorPath</key><string>" + log + "</string>\n"
                + "</dict></plist>\n";
    }

    /**
     * Settings the service must inherit from the installing shell: which JVM to run (the sibling
     * project's session once `pkill java`'d ours — RESEARCHZOSHO_JAVA points at a renamed copy) and
     * the model/embedder/library locations when they are env-only rather than in the config file. Both
     * spellings: an install from before the split is configured in the CODEZAIKU_ names.
     */
    static final String[] PASSTHROUGH = {"JAVA_HOME",
            "RESEARCHZOSHO_JAVA", "RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_EMBED", "RESEARCHZOSHO_LIBRARY", "RESEARCHZOSHO_MODEL", "RESEARCHZOSHO_RERANK", "RESEARCHZOSHO_JAVA_OPTS",
            "CODEZAIKU_JAVA", "CODEZAIKU_LIBRARIAN_DRIVE", "CODEZAIKU_EMBED", "CODEZAIKU_LIBRARY", "CODEZAIKU_MODEL", "CODEZAIKU_RERANK", "CODEZAIKU_JAVA_OPTS"};

    /**
     * The daemon's heap cap. With no -Xmx a JVM takes a quarter of the box, and a research run
     * grew the daemon to 5 GB while the sibling project's embedder held 8 — the host ran low
     * (2026-09-03). 4 GB covers a rebuild of thousands of chunk vectors and a research turn with
     * a 22k-token prompt; RESEARCHZOSHO_XMX overrides. Emitted as RESEARCHZOSHO_JAVA_OPTS for
     * the launcher, keeping the two flags the launcher would otherwise default to.
     */
    static String heapOpts() {
        if (System.getenv("RESEARCHZOSHO_JAVA_OPTS") != null || System.getenv("CODEZAIKU_JAVA_OPTS") != null) return "";   // the person set their own; passed through above
        String xmx = org.researchzosho.Config.get("RESEARCHZOSHO_XMX", "4g");
        return "--add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xmx" + xmx;
    }

    static String passthroughEnv(String prefix, String sep) { return passthroughEnv(prefix, "=", sep); }

    static String passthroughEnv(String prefix, String eq, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String k : PASSTHROUGH) {
            String v = System.getenv(k);
            if (v != null && !v.isBlank()) sb.append(prefix).append(k).append(eq).append(v).append(sep);
        }
        return sb.toString();
    }

    /**
     * The Windows task runs a small PowerShell script, hidden — a console window at every logon
     * is not a service, and schtasks cannot take the settings inline (its /TR argument breaks on
     * the quotes and semicolons; measured on the Windows test box 2026-09-03). The script carries the
     * settings a logon environment would not have.
     */
    static String windowsScript(String exec, String host, int port, int crewHour, Path log) {
        return "# ResearchZosho (The Librarian) as a logon task - written by 'researchzosho service install' (ASCII only: PowerShell reads a BOM-less file as ANSI)\n"
                + passthroughEnv("$env:", " = '", "'\n").replace("\\'", "''")
                + (heapOpts().isEmpty() ? "" : "$env:RESEARCHZOSHO_JAVA_OPTS = '" + heapOpts() + "'\n")
                + "& '" + exec.replace("'", "''") + "' " + verbFor(exec) + "serve" + hostFlag(host, " --host ", "") + " --port " + port + " --crew-hour " + crewHour
                + " --log '" + log.toString().replace("'", "''") + "'\n";
    }

    /** Start the task's script now, detached, with no inherited handles: Start-Process from a throwaway shell. */
    static List<String> windowsStartNow(Path script) {
        String quoted = "'" + script.toString().replace("'", "''") + "'";
        return List.of("powershell", "-NoProfile", "-Command",
                "Start-Process -FilePath powershell -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File'," + quoted + ") -WindowStyle Hidden");
    }

    static String windowsCommand(Path script) {
        return "powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File " + script;
    }

    /** Loopback is the default and needs no flag; any other address is written out, so the unit says when the LAN can reach it. */
    static String hostFlag(String host, String before, String after) {
        return host == null || host.isBlank() || host.equals("127.0.0.1") || host.equals("localhost") ? "" : before + host + after;
    }

    public static Plan plan(Os os, String exec, int port, int crewHour, Path home) { return plan(os, exec, "127.0.0.1", port, crewHour, home); }

    public static Plan plan(Os os, String exec, String host, int port, int crewHour, Path home) {
        Path state = org.researchzosho.Config.stateDir(home);
        Path log = state.resolve("logs").resolve("librarian-serve.log");
        switch (os) {
            case linux -> {
                Path unit = home.resolve(".config").resolve("systemd").resolve("user").resolve(NAME + ".service");
                return new Plan(os, unit,
                        List.of(List.of("systemctl", "--user", "daemon-reload"), List.of("systemctl", "--user", "enable", "--now", NAME)),
                        List.of(List.of("systemctl", "--user", "disable", "--now", NAME), List.of("systemctl", "--user", "daemon-reload")),
                        List.of("systemctl", "--user", "is-active", NAME), systemdUnit(exec, host, port, crewHour, log));
            }
            case macos -> {
                Path plist = home.resolve("Library").resolve("LaunchAgents").resolve(MAC_LABEL + ".plist");
                String uid = uid();
                return new Plan(os, plist,
                        List.of(List.of("launchctl", "bootout", "gui/" + uid + "/" + MAC_LABEL), List.of("launchctl", "bootstrap", "gui/" + uid, plist.toString())),
                        List.of(List.of("launchctl", "bootout", "gui/" + uid + "/" + MAC_LABEL)),
                        List.of("launchctl", "print", "gui/" + uid + "/" + MAC_LABEL), launchAgent(exec, host, port, crewHour, log));
            }
            default -> {
                Path script = state.resolve(WIN_TASK + ".ps1");
                // ONLOGON tasks are "interactive only": schtasks /Run does nothing from a session with no
                // desktop (ssh). So install ALSO launches the script now — through Start-Process, which hands the
                // child NO inherited handles. A child started straight from this JVM inherits the caller's output
                // pipe, and whoever captured `service install`'s output then waits until the server exits
                // (measured over ssh on the Windows box, 2026-09-08: fifteen minutes and a timeout).
                return new Plan(os, script,
                        List.of(List.of("schtasks", "/Create", "/F", "/SC", "ONLOGON", "/TN", WIN_TASK, "/TR", windowsCommand(script), "/RL", "LIMITED"),
                                windowsStartNow(script)),
                        List.of(List.of("schtasks", "/End", "/TN", WIN_TASK), List.of("schtasks", "/Delete", "/F", "/TN", WIN_TASK)),
                        List.of("schtasks", "/Query", "/TN", WIN_TASK), windowsScript(exec, host, port, crewHour, log));
            }
        }
    }

    static String uid() {
        try {
            Process p = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor();
            return out.isEmpty() ? "501" : out;
        } catch (Exception e) { return "501"; }
    }

    /** The verb prefix the launcher needs: none for the researchzosho/zosho command, "librarian" for codezaiku. */
    static String verbFor(String exec) {
        String base = Path.of(exec).getFileName() == null ? exec : Path.of(exec).getFileName().toString().toLowerCase(Locale.ROOT);
        return base.startsWith("researchzosho") || base.startsWith("zosho") ? "" : "librarian ";
    }

    /** The launcher to run as the service: this launcher, else a pre-split codezaiku's sibling, else one on PATH. */
    public static String resolveExec(String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        String own = System.getenv("RESEARCHZOSHO_LAUNCHER");
        if (own != null && !own.isBlank()) return own;
        String env = System.getenv("CODEZAIKU_LAUNCHER");
        if (env != null && !env.isBlank()) {
            Path sibling = Path.of(env).resolveSibling("researchzosho");
            return Files.isRegularFile(sibling) ? sibling.toString() : env;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            String[] names = os() == Os.windows
                    ? new String[]{"researchzosho.bat", "researchzosho.cmd", "zosho.bat", "codezaiku.bat", "codezaiku.cmd"}
                    : new String[]{"researchzosho", "zosho", "codezaiku"};
            for (String dir : path.split(java.io.File.pathSeparator)) {
                for (String name : names) {
                    Path p = Path.of(dir, name);
                    if (Files.isRegularFile(p)) return p.toAbsolutePath().toString();
                }
            }
        }
        throw new IllegalStateException("cannot find the researchzosho launcher — pass --exec <path to researchzosho>");
    }

    /** install | uninstall | status. Returns the process exit code. */
    public static int run(String op, Plan plan, java.io.PrintStream out) throws IOException, InterruptedException {
        switch (op) {
            case "install" -> {
                Files.createDirectories(plan.definition().getParent());
                Files.createDirectories(org.researchzosho.Config.home().resolve("logs"));
                Files.writeString(plan.definition(), plan.text(), StandardCharsets.UTF_8);
                out.println("wrote " + plan.definition());
                int rc = 0;
                for (int i = 0; i < plan.install().size(); i++) {
                    var cmd = plan.install().get(i);
                    int r = exec(cmd, out);
                    boolean optional = plan.os() == Os.macos && i == 0;   // bootout of a not-yet-loaded agent fails harmlessly
                    if (r != 0 && !optional) rc = r;
                }
                if (rc == 0) {
                    out.println("installed: " + describe(plan));
                    if (plan.os() == Os.linux) out.println("  (to keep it running while you are logged out: loginctl enable-linger " + System.getProperty("user.name") + ")");
                }
                return rc;
            }
            case "uninstall" -> {
                for (var cmd : plan.uninstall()) exec(cmd, out);
                Files.deleteIfExists(plan.definition());
                out.println("removed " + plan.definition());
                // Windows: ending the task ends only the task's own process, and the server `install` started
                // now is not it (measured 2026-09-08: the port still answered after uninstall). The server
                // records its pid; stop that too, anywhere it is recorded.
                stopRecorded(pidFile(), out);
                return 0;
            }
            case "status" -> {
                out.println(describe(plan));
                int rc = exec(plan.status(), out);
                Long pid = recordedPid(pidFile());
                if (pid != null) out.println(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) ? "  server running (pid " + pid + ")" : "  server not running (stale pid " + pid + ")");
                return rc;
            }
            default -> { out.println("usage: researchzosho service install|uninstall|status [--exec <launcher>] [--host H] [--port N] [--crew-hour H]"); return 2; }
        }
    }

    /** Where a running server records its pid: the state dir, beside the logs. */
    public static Path pidFile() { return org.researchzosho.Config.home().resolve("researchzosho.pid"); }

    /** Called by `serve`: record this process so `service uninstall` can stop it on a platform that will not. */
    public static void recordPid() {
        try {
            Files.createDirectories(pidFile().getParent());
            Files.writeString(pidFile(), Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { Files.deleteIfExists(pidFile()); } catch (IOException ignored) { } }));
            System.out.println("  pid " + ProcessHandle.current().pid() + " recorded at " + pidFile());
        } catch (IOException e) {
            System.out.println("  pid not recorded (" + e + "): service uninstall will stop only what the platform stops");
        }
    }

    static Long recordedPid(Path pidFile) {
        try { return Files.exists(pidFile) ? Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).strip()) : null; }
        catch (IOException | NumberFormatException e) { return null; }
    }

    /** Stop the recorded server if it is alive and is a JVM (never a pid that was reused by something else). */
    static boolean stopRecorded(Path pidFile, java.io.PrintStream out) {
        Long pid = recordedPid(pidFile);
        if (pid == null) return false;
        var h = ProcessHandle.of(pid);
        if (h.isEmpty() || !h.get().isAlive()) { try { Files.deleteIfExists(pidFile); } catch (IOException ignored) { } return false; }
        String cmd = h.get().info().command().orElse("").toLowerCase(Locale.ROOT);
        if (!cmd.contains("java")) { out.println("  pid " + pid + " is not the server any more (" + cmd + "); left alone"); return false; }
        h.get().destroy();
        try { h.get().onExit().get(10, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { h.get().destroyForcibly(); }
        try { Files.deleteIfExists(pidFile); } catch (IOException ignored) { }
        out.println("stopped the running server (pid " + pid + ")");
        return true;
    }

    static String describe(Plan p) {
        return switch (p.os()) {
            case linux -> "systemd --user unit " + NAME + " (" + p.definition() + ")";
            case macos -> "LaunchAgent " + MAC_LABEL + " (" + p.definition() + ")";
            case windows -> "Scheduled Task " + WIN_TASK + " at logon";
        };
    }

    private static int exec(List<String> cmd, java.io.PrintStream out) throws IOException, InterruptedException {
        if (!cmd.isEmpty() && "@detach".equals(cmd.get(0))) {   // start and do not wait: the service itself
            List<String> real = cmd.subList(1, cmd.size());
            out.println("  $ " + String.join(" ", real) + "  (detached)");
            new ProcessBuilder(real).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return 0;
        }
        out.println("  $ " + String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int rc = p.waitFor();
        if (!o.isEmpty()) for (String line : o.split("\n")) out.println("    " + line);
        return rc;
    }

    /** Rotate a log file once it passes {@code maxBytes}: {@code .1} keeps the previous one. */
    public static void rotate(Path log, long maxBytes) {
        try {
            if (Files.exists(log) && Files.size(log) > maxBytes) {
                Files.move(log, log.resolveSibling(log.getFileName() + ".1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) { }
    }
}
