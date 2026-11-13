package org.researchzosho.librarian;

import java.time.Duration;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.researchzosho.Config;
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

    static String systemdUnit(String exec, String host, int port, int crewHour, Path log, String javaHome) {
        return "[Unit]\nDescription=ResearchZosho — The Librarian: the library protocol over HTTP, research runs, the housekeeping\n"
                + "After=network-online.target\n\n[Service]\nType=simple\n"
                + "ExecStart=/bin/sh -c '" + UNIX_JAVA_FALLBACK.replace("$", "$$") + "' "
                + exec + " " + verbFor(exec) + "serve" + hostFlag(host, " --host ", "") + " --port " + port + " --crew-hour " + crewHour + " --log " + log + "\n"
                // Java ends with 143 when systemd stops it (SIGTERM): a normal stop, not a failure, or `status` would say failed after every stop
                + "Restart=on-failure\nRestartSec=10\nSuccessExitStatus=143\nEnvironment=RESEARCHZOSHO_SERVICE=1\n" + passthroughEnv("Environment=\"", "=", "\"\n", Service::systemdQuoted)
                + "Environment=\"" + SERVICE_JAVA_HOME + "=" + systemdQuoted(javaHome) + "\"\n"
                + (heapOpts().isEmpty() ? "" : "Environment=\"RESEARCHZOSHO_JAVA_OPTS=" + heapOpts() + "\"\n")
                + "\n"
                + "[Install]\nWantedBy=default.target\n";
    }

    static String launchAgent(String exec, String host, int port, int crewHour, Path log, String javaHome) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\"><dict>\n"
                + "  <key>Label</key><string>" + MAC_LABEL + "</string>\n"
                + "  <key>ProgramArguments</key><array>\n"
                + "    <string>/bin/sh</string><string>-c</string><string>" + xml(UNIX_JAVA_FALLBACK) + "</string>\n"
                + "    <string>" + exec + "</string>" + (verbFor(exec).isEmpty() ? "" : "<string>librarian</string>") + "<string>serve</string>\n"
                + hostFlag(host, "    <string>--host</string><string>", "</string>\n")
                + "    <string>--port</string><string>" + port + "</string>\n"
                + "    <string>--crew-hour</string><string>" + crewHour + "</string>\n"
                + "    <string>--log</string><string>" + log + "</string>\n"
                + "  </array>\n"
                + "  <key>RunAtLoad</key><true/>\n  <key>KeepAlive</key><true/>\n"
                + "  <key>EnvironmentVariables</key><dict><key>RESEARCHZOSHO_SERVICE</key><string>1</string>\n" + passthroughEnv("    <key>", "</key><string>", "</string>\n", v -> v)
                + "    <key>" + SERVICE_JAVA_HOME + "</key><string>" + xml(javaHome) + "</string>\n"
                + (heapOpts().isEmpty() ? "" : "    <key>RESEARCHZOSHO_JAVA_OPTS</key><string>" + heapOpts() + "</string>\n")
                + "    <key>PATH</key><string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin</string></dict>\n"
                + "  <key>StandardOutPath</key><string>" + log + "</string>\n"
                + "  <key>StandardErrorPath</key><string>" + log + "</string>\n"
                + "</dict></plist>\n";
    }

    /** Where the unit and the LaunchAgent keep the Java that `service install` ran under, for {@link #UNIX_JAVA_FALLBACK}. */
    static final String SERVICE_JAVA_HOME = "RESEARCHZOSHO_SERVICE_JAVA_HOME";

    /**
     * The shell line in front of the launcher on Linux and macOS. systemd and launchd start the service with a PATH of their own,
     * never the installing shell's: a Java found only there (SDKMAN, a JDK unpacked in the home folder, Homebrew's openjdk, which
     * is not linked into /opt/homebrew/bin) is not found by the service. JAVA_HOME, or a java on the service's PATH that runs,
     * still wins; the Java `service install` ran under is used only when there is neither. {@code java -version}, not
     * {@code command -v java}: macOS always has /usr/bin/java, which fails when no Java is installed.
     */
    static final String UNIX_JAVA_FALLBACK = "[ -n \"$JAVA_HOME\" ] || java -version >/dev/null 2>&1 || export JAVA_HOME=\"$" + SERVICE_JAVA_HOME + "\"; exec \"$0\" \"$@\"";

    static String xml(String v) { return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    /**
     * Settings the service must inherit from the installing shell: which JVM to run (the sibling
     * project's session once `pkill java`'d ours — RESEARCHZOSHO_JAVA points at a renamed copy) and
     * the model/embedder/library locations when they are env-only rather than in the config file. Both
     * spellings: an install from before the split is configured in the CODEZAIKU_ names. The config file the
     * install was made with, when RESEARCHZOSHO_CONFIG named one: without it the service read the machine's own
     * config instead. JAVA_OPTS, which the installed launcher hands to Java, so the service runs as the
     * installing shell's commands did.
     */
    static final String[] PASSTHROUGH = {"JAVA_HOME", "JAVA_OPTS", "RESEARCHZOSHO_CONFIG",
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
        String xmx = Config.get("RESEARCHZOSHO_XMX", "4g");
        return "--add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Xmx" + xmx;
    }

    static String passthroughEnv(String prefix, String eq, String sep, UnaryOperator<String> quote) {
        StringBuilder sb = new StringBuilder();
        for (String k : PASSTHROUGH) {
            String v = System.getenv(k);
            if (v != null && !v.isBlank()) sb.append(prefix).append(k).append(eq).append(quote.apply(v)).append(sep);
        }
        return sb.toString();
    }

    /** A value inside systemd's {@code Environment="K=V"}: a value with spaces (JAVA_OPTS) stays one setting. */
    static String systemdQuoted(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%");
    }

    /**
     * The Windows task runs a small PowerShell script, hidden — a console window at every logon
     * is not a service, and schtasks cannot take the settings inline (its /TR argument breaks on
     * the quotes and semicolons; measured on the Windows test box 2026-09-03). The script carries the
     * settings a logon environment would not have.
     *
     * A logon has only the saved settings, never the PATH of the terminal that ran `service install`. With Java found only
     * there, the task used to start nothing at the next logon while Windows recorded success: the launcher's error went to a
     * hidden window, and PowerShell exits 0 after a failed command (measured on the Windows test box, 2026-09-25). So the
     * script falls back to the Java `service install` ran under, when the logon environment names none; says in the server's
     * log when no Java can be found at all, and exits 1; and exits with the launcher's own code.
     */
    static String windowsScript(String exec, String host, int port, int crewHour, Path log, String javaHome) {
        String launcher = exec.replace("'", "''");
        return "# ResearchZosho (The Librarian) as a logon task - written by 'researchzosho service install' (ASCII only: PowerShell reads a BOM-less file as ANSI)\n"
                + "$env:RESEARCHZOSHO_SERVICE = '1'\n"   // the server knows it is the service's, as the unit and the LaunchAgent tell it: the pid record in the state dir is its own
                + passthroughEnv("$env:", " = '", "'\n", v -> v.replace("'", "''"))
                + (heapOpts().isEmpty() ? "" : "$env:RESEARCHZOSHO_JAVA_OPTS = '" + heapOpts() + "'\n")
                + "$log = '" + log.toString().replace("'", "''") + "'\n"
                + "# the installed launcher finds Java through JAVA_HOME or PATH: a Java named by RESEARCHZOSHO_JAVA becomes JAVA_HOME\n"
                + "$named = if ($env:RESEARCHZOSHO_JAVA) { $env:RESEARCHZOSHO_JAVA } else { $env:CODEZAIKU_JAVA }\n"
                + "if (-not $env:JAVA_HOME -and $named) {\n"
                + "    $j = Get-Command $named -ErrorAction SilentlyContinue | Select-Object -First 1\n"
                + "    if ($j -and $j.Source) { $h = Split-Path (Split-Path $j.Source); if (Test-Path -LiteralPath (Join-Path $h 'bin\\java.exe')) { $env:JAVA_HOME = $h } }\n"
                + "}\n"
                + "# the Java 'service install' ran under, when the logon environment has none: JAVA_HOME or a Java on PATH still wins\n"
                + "if (-not $env:JAVA_HOME -and -not (Get-Command java.exe -ErrorAction SilentlyContinue)) { $env:JAVA_HOME = '" + javaHome.replace("'", "''") + "' }\n"
                + "$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\\java.exe' } else { (Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source }\n"
                + OWN_JAVA.replace("{launcher}", launcher) + "\n"
                + "if (-not (Test-Path -LiteralPath $own) -and -not ($java -and (Test-Path -LiteralPath $java))) {\n"
                + "    New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null\n"
                + "    [IO.File]::AppendAllText($log, (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  " + NO_JAVA_AT_LOGON.replace("'", "''") + "' + [Environment]::NewLine)\n"
                + "    exit 1\n"
                + "}\n"
                + "& '" + launcher + "' " + verbFor(exec) + "serve" + hostFlag(host, " --host ", "") + " --port " + port + " --crew-hour " + crewHour + " --log $log\n"
                + "exit $LASTEXITCODE\n";
    }

    /**
     * Where the task script finds the Java the launcher brings: in the version {@code current.txt} names, under {@code versions\}, or
     * beside {@code bin\} in an install from before 0.7.2. {@code {launcher}} is the launcher's path, its quotes doubled.
     */
    static final String OWN_JAVA = "$rz = Split-Path (Split-Path '{launcher}'); $own = if (Test-Path -LiteralPath (Join-Path $rz 'current.txt')) "
            + "{ Join-Path $rz ('versions\\' + (Get-Content -LiteralPath (Join-Path $rz 'current.txt') -TotalCount 1).Trim() + '\\jre\\bin\\java.exe') } else { Join-Path $rz 'jre\\bin\\java.exe' }";

    /** A task script written before 0.7.2, its line for the launcher's own Java made to read the version {@code current.txt} names; the script as it was otherwise. */
    static String withVersionedJava(String script) {
        Matcher m = Pattern.compile("(?m)^\\$own = Join-Path \\(Split-Path \\(Split-Path '((?:[^']|'')*)'\\)\\) 'jre\\\\bin\\\\java\\.exe'(\r?)$").matcher(script);
        if (!m.find()) return script;
        return script.substring(0, m.start()) + OWN_JAVA.replace("{launcher}", m.group(1)) + m.group(2) + script.substring(m.end());
    }

    /** The command that installs the Windows build carrying its own Java. */
    static final String WINDOWS_RUNTIME_INSTALL = "$env:RESEARCHZOSHO_RUNTIME = '1'; irm https://researchzosho.org/install.ps1 | iex";

    /** What the task script writes to the server's log when a logon finds no Java. */
    static final String NO_JAVA_AT_LOGON = "Java was not found when Windows started ResearchZosho at logon, so the server did not start;"
            + " install Java 21 or newer, set JAVA_HOME, or install the build that carries its own Java with this PowerShell command: " + WINDOWS_RUNTIME_INSTALL;

    /**
     * Whether a Windows logon finds a Java without the fallback: the JAVA_HOME the task script copies from the installing shell,
     * else the saved one (the User setting over the Machine one); a Java named by RESEARCHZOSHO_JAVA; else java.exe in a folder
     * of the saved PATH, which a logon builds from the Machine PATH and then the User PATH. {@code saved} maps
     * "Machine Path", "User JAVA_HOME" and the like to the saved values; {@code shell} reads the installing shell's environment.
     */
    static boolean logonFindsJava(UnaryOperator<String> shell, Map<String, String> saved, Predicate<String> isFile) {
        String home = firstSet(shell.apply("JAVA_HOME"), saved.get("User JAVA_HOME"), saved.get("Machine JAVA_HOME"));
        if (home != null) return isFile.test(winJoin(home, "bin\\java.exe"));
        String named = firstSet(shell.apply("RESEARCHZOSHO_JAVA"), shell.apply("CODEZAIKU_JAVA"), saved.get("User RESEARCHZOSHO_JAVA"), saved.get("Machine RESEARCHZOSHO_JAVA"));
        if (named != null && named.contains("\\") && isFile.test(named)) return true;
        for (String dir : (saved.getOrDefault("Machine Path", "") + ";" + saved.getOrDefault("User Path", "")).split(";")) {
            String d = dir.strip().replace("\"", "");
            if (!d.isEmpty() && isFile.test(winJoin(d, "java.exe"))) return true;
        }
        return false;
    }

    private static String firstSet(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v.strip();
        return null;
    }

    private static String winJoin(String dir, String name) { return (dir.endsWith("\\") ? dir : dir + "\\") + name; }

    /** Printed by `service install` on Windows when a logon would find no Java of its own and the fallback will be what runs. */
    static String windowsJavaWarning(String javaHome, Path log) {
        return "  Note: when you log on, Windows will not find Java by itself. This terminal finds it, but your saved Windows settings do not.\n"
                + "  The service will use the Java this command ran with, " + javaHome + ". If that folder is moved or removed, the service\n"
                + "  will not start at logon, and its log says why:\n"
                + "    " + log + "\n"
                + "  To make the service independent of that folder, install Java 21 or newer for all programs, set JAVA_HOME in your\n"
                + "  Windows settings, or install the build that carries its own Java with this PowerShell command:\n"
                + "    " + WINDOWS_RUNTIME_INSTALL;
    }

    /**
     * The saved Machine and User settings a logon builds its environment from, read through PowerShell: "Machine Path",
     * "User JAVA_HOME" and so on. Base64 of UTF-8 on the way out, so a user folder in any script survives the console's code
     * page. Null when they cannot be read.
     */
    static Map<String, String> savedWindowsEnvironment() {
        String command = "foreach ($n in 'Path','JAVA_HOME','RESEARCHZOSHO_JAVA') { foreach ($t in 'Machine','User') { $v = [Environment]::GetEnvironmentVariable($n, $t); if ($null -eq $v) { $v = '' };"
                + " Write-Output ($t + ' ' + $n + '=' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($v))) } }";
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", command).redirectErrorStream(true).start();
            p.getOutputStream().close();   // Windows PowerShell can wait for its input to end before it exits
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            Map<String, String> saved = new HashMap<>();
            for (String line : o.split("\r?\n")) {
                int eq = line.indexOf('=');
                if (eq > 0 && (line.startsWith("Machine ") || line.startsWith("User "))) saved.put(line.substring(0, eq), new String(Base64.getDecoder().decode(line.substring(eq + 1).strip()), StandardCharsets.UTF_8));
            }
            return saved.size() == 6 ? saved : null;
        } catch (Exception e) { return null; }
    }

    /**
     * Start the task's script now, detached, with no inherited handles: Start-Process from a throwaway shell. Start-Process joins its
     * argument list with spaces and quotes nothing, so the script's path goes in double quotes of its own: a user folder with a space
     * (C:\Users\Ann Hale) is one argument. No double quote in the command itself, which the JVM's quoting would break; [char]34 writes it.
     */
    static List<String> windowsStartNow(Path script) {
        String quoted = "'" + script.toString().replace("'", "''") + "'";
        return List.of("powershell", "-NoProfile", "-Command",
                "Start-Process -FilePath powershell -ArgumentList ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File ' + [char]34 + " + quoted + " + [char]34) -WindowStyle Hidden");
    }

    /** The logon task's command line. The script's path is in double quotes, written \" so that they reach schtasks through the JVM's quoting. */
    static String windowsCommand(Path script) {
        return "powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File \\\"" + script + "\\\"";
    }

    /** Loopback is the default and needs no flag; any other address is written out, so the unit says when the LAN can reach it. */
    static String hostFlag(String host, String before, String after) {
        return host == null || host.isBlank() || host.equals("127.0.0.1") || host.equals("localhost") ? "" : before + host + after;
    }

    public static Plan plan(Os os, String exec, int port, int crewHour, Path home) { return plan(os, exec, "127.0.0.1", port, crewHour, home); }

    public static Plan plan(Os os, String exec, String host, int port, int crewHour, Path home) {
        return plan(os, exec, host, port, crewHour, home, installedJavaHome());
    }

    /** The Java this program runs under: the service's fallback when its own environment finds none. */
    static String installedJavaHome() { return System.getProperty("java.home"); }

    /** {@code javaHome}: the Java the service falls back to when the environment it starts in finds none. */
    static Plan plan(Os os, String exec, String host, int port, int crewHour, Path home, String javaHome) {
        Path state = Config.stateDir(home);
        Path log = state.resolve("logs").resolve("librarian-serve.log");
        switch (os) {
            case linux -> {
                Path unit = home.resolve(".config").resolve("systemd").resolve("user").resolve(NAME + ".service");
                return new Plan(os, unit,
                        List.of(List.of("systemctl", "--user", "daemon-reload"), List.of("systemctl", "--user", "enable", "--now", NAME)),
                        List.of(List.of("systemctl", "--user", "disable", "--now", NAME), List.of("systemctl", "--user", "daemon-reload")),
                        List.of("systemctl", "--user", "is-active", NAME), systemdUnit(exec, host, port, crewHour, log, javaHome));
            }
            case macos -> {
                Path plist = home.resolve("Library").resolve("LaunchAgents").resolve(MAC_LABEL + ".plist");
                String uid = uid();
                return new Plan(os, plist,
                        List.of(List.of("launchctl", "bootout", "gui/" + uid + "/" + MAC_LABEL), List.of("launchctl", "bootstrap", "gui/" + uid, plist.toString())),
                        List.of(List.of("launchctl", "bootout", "gui/" + uid + "/" + MAC_LABEL)),
                        List.of("launchctl", "print", "gui/" + uid + "/" + MAC_LABEL), launchAgent(exec, host, port, crewHour, log, javaHome));
            }
            default -> {
                Path script = windowsTaskScript(home);
                // ONLOGON tasks are "interactive only": schtasks /Run does nothing from a session with no
                // desktop (ssh). So install ALSO launches the script now — through Start-Process, which hands the
                // child NO inherited handles. A child started straight from this JVM inherits the caller's output
                // pipe, and whoever captured `service install`'s output then waits until the server exits
                // (measured over ssh on the Windows box, 2026-09-08: fifteen minutes and a timeout).
                return new Plan(os, script,
                        List.of(List.of("schtasks", "/Create", "/F", "/SC", "ONLOGON", "/TN", WIN_TASK, "/TR", windowsCommand(script), "/RL", "LIMITED"),
                                windowsStartNow(script)),
                        List.of(List.of("schtasks", "/End", "/TN", WIN_TASK), List.of("schtasks", "/Delete", "/F", "/TN", WIN_TASK)),
                        List.of("schtasks", "/Query", "/TN", WIN_TASK), windowsScript(exec, host, port, crewHour, log, javaHome));
            }
        }
    }

    /** The script the Windows logon task runs; it exists while the service is installed. */
    public static Path windowsTaskScript(Path home) { return Config.stateDir(home).resolve(WIN_TASK + ".ps1"); }

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

    /**
     * The launcher to run as the service: the one named, else this launcher (the dev script announces itself), else the
     * one beside this program's own jar (an installed program, whatever PATH says — on a fresh macOS install the shell
     * that ran the installer had no PATH entry yet, and `service install` could not find itself, 2026-09-09), else a
     * pre-split codezaiku's sibling, else one on PATH.
     */
    public static String resolveExec(String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        String own = System.getenv("RESEARCHZOSHO_LAUNCHER");
        if (own != null && !own.isBlank()) return own;
        Path beside = besideOwnJar();
        if (beside != null) return beside.toString();
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
            for (String dir : path.split(File.pathSeparator)) {
                for (String name : names) {
                    Path p = Path.of(dir, name);
                    if (Files.isRegularFile(p)) return p.toAbsolutePath().toString();
                }
            }
        }
        throw new IllegalStateException("cannot find the researchzosho launcher — pass --exec <path to researchzosho>");
    }

    /** {@code <root>/bin/researchzosho} (or {@code .bat}) next to the jar this program runs from, when it is an installed tree. */
    static Path besideOwnJar() {
        Path root = Updater.root();
        if (root == null) return null;
        Path p = root.resolve("bin").resolve(os() == Os.windows ? "researchzosho.bat" : "researchzosho");
        return Files.isRegularFile(p) ? p.toAbsolutePath() : null;
    }

    /** install | uninstall | status. Returns the process exit code. */
    public static int run(String op, Plan plan, PrintStream out) throws IOException, InterruptedException {
        switch (op) {
            case "install" -> {
                boolean changed = changes(plan);
                Files.createDirectories(plan.definition().getParent());
                Files.createDirectories(Config.home().resolve("logs"));
                Files.writeString(plan.definition(), plan.text(), StandardCharsets.UTF_8);
                out.println("wrote " + plan.definition());
                // a server already running keeps the settings it started with: on Windows, stop it so the start below uses the new ones
                if (changed && plan.os() == Os.windows) stopRecorded(pidFile(), out);
                int rc = 0;
                List<List<String>> steps = installSteps(plan, changed);
                for (int i = 0; i < steps.size(); i++) {
                    var cmd = steps.get(i);
                    int r = exec(cmd, out);
                    boolean optional = plan.os() == Os.macos && i == 0;   // bootout of a not-yet-loaded agent fails harmlessly
                    if (r != 0 && !optional) rc = r;
                }
                if (rc == 0) {
                    out.println("installed: " + describe(plan));
                    if (changed) out.println("  The settings changed, so the service was restarted onto them.");
                    if (plan.os() == Os.linux) out.println("  (to keep it running while you are logged out: loginctl enable-linger " + System.getProperty("user.name") + ")");
                    if (plan.os() == Os.windows) {
                        // the script always carries the fallback; say so when a logon would need it
                        Map<String, String> saved = savedWindowsEnvironment();
                        if (saved != null && !logonFindsJava(System::getenv, saved, f -> Files.isRegularFile(Path.of(f))))
                            out.println(windowsJavaWarning(installedJavaHome(), Config.home().resolve("logs").resolve("librarian-serve.log")));
                    }
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
            case "restart" -> {
                if (!Files.exists(plan.definition())) { out.println("The service is not installed, so there is nothing to restart. To install it: researchzosho service install"); return 1; }
                int rc = 0;
                switch (plan.os()) {
                    case linux -> rc = exec(List.of("systemctl", "--user", "restart", NAME), out);
                    case macos -> rc = exec(List.of("launchctl", "kickstart", "-k", "gui/" + uid() + "/" + MAC_LABEL), out);
                    default -> {
                        // the running server is not the task's own process (install starts it through Start-Process): stop it by its
                        // recorded pid, end the task, and start it again the way install does, which works from any session
                        stopRecorded(pidFile(), out);
                        exec(List.of("schtasks", "/End", "/TN", WIN_TASK), out);   // not running is fine
                        rc = exec(windowsStartNow(plan.definition()), out);
                    }
                }
                out.println(rc == 0 ? "Restarted the service. It answers again in a few seconds." : "The service could not be restarted (exit " + rc + "). researchzosho service status says how it stands.");
                return rc;
            }
            case "status" -> {
                out.println(stateSaid(plan));
                if (!Files.exists(plan.definition())) return 0;   // nothing installed: the system is not asked about a service of that name
                out.println("  " + describe(plan));
                int rc = exec(plan.status(), out);
                Long pid = recordedPid(pidFile());
                if (pid != null && !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) out.println("  (a stale pid record, " + pid + ", of a server that has ended)");
                return rc;
            }
            case "stop" -> { return stop(plan, out); }
            case "start" -> { return start(plan, out); }
            default -> { out.println(USAGE); return 2; }
        }
    }

    static final String USAGE = "usage: researchzosho service install|uninstall|start|stop [--stop-jobs]|restart [--yes]|status [--exec <launcher>] [--host H] [--port N] [--crew-hour H]";

    /** How long stop and start wait for the server to end or to record itself before they say how it stands; a test shortens it. */
    static volatile Duration SETTLE = Duration.ofSeconds(20);

    /** The server running now by its own pid record: the service's, else one a terminal's `serve` of the default library recorded in it. */
    static Long serverNow() {
        Long pid = runningServer(pidFile());
        return pid != null ? pid : runningServer(pidFileIn(defaultLibrary()));
    }

    /** The record that holds {@code pid}: the service's, or the default library's. */
    static Path recordOf(long pid) {
        Long a = recordedPid(pidFile());
        return a != null && a == pid ? pidFile() : pidFileIn(defaultLibrary());
    }

    /**
     * One line: whether the service is installed, and whether a server runs now and under what. The last line of `service stop`,
     * `service start` and the first of `service status`, so the three read as one family.
     */
    static String stateSaid(Plan plan) {
        boolean installed = Files.exists(plan.definition());
        Long pid = serverNow();
        if (installed) return pid != null ? "The service is installed (" + describe(plan) + ") and running (pid " + pid + ")."
                : "The service is installed (" + describe(plan) + ") and stopped: researchzosho service start starts it, and it starts by itself at your next login.";
        return pid != null ? "No service is installed here; a server started with researchzosho serve is running (pid " + pid + ")."
                : "No service is installed here, and no server is running.";
    }

    /**
     * `service stop`: the service stopped through the system's own way (systemd's stop, launchd's bootout, the end of the Windows task and
     * the server's recorded pid), and a server that is still running after it, or that runs with no service installed, one started with
     * `serve` in a terminal, stopped by its own pid record, never by its name. One line says what was done and how it stands now.
     */
    static int stop(Plan plan, PrintStream out) throws IOException, InterruptedException {
        boolean installed = Files.exists(plan.definition());
        Long before = serverNow();
        int rc = 0;
        if (installed) {
            rc = switch (plan.os()) {
                case linux -> quiet(List.of("systemctl", "--user", "stop", NAME), out);
                case macos -> { int r = quiet(List.of("launchctl", "bootout", "gui/" + uid() + "/" + MAC_LABEL), out); yield before == null ? 0 : r; }   // an agent not loaded is fine when nothing ran
                default -> { quiet(List.of("schtasks", "/End", "/TN", WIN_TASK), out); stopRecorded(pidFile(), new PrintStream(OutputStream.nullOutputStream())); yield 0; }   // the server is not the task's own process: by its record
            };
            if (before != null) waitEnded(before);
        }
        Long left = serverNow();   // a server not under the service, one started with serve in a terminal: ended by its own pid record
        if (left != null) stopRecorded(recordOf(left), new PrintStream(OutputStream.nullOutputStream()));
        Long now = serverNow();
        List<String> said = new ArrayList<>();
        if (installed) said.add(before == null ? "The service was not running." : now != null && rc != 0 ? "The service could not be stopped (exit " + rc + ")." : "Stopped the service.");
        if (left != null) said.add(installed ? "A server started with researchzosho serve was running too (pid " + left + "); it is stopped." : "No service is installed here; stopped the server started with researchzosho serve (pid " + left + ").");
        if (!installed && left == null) said.add("No service is installed here and no server was running, so there was nothing to stop.");
        said.add(now != null ? "A server is still running (pid " + now + ")." : installed ? "No server runs now; researchzosho service start starts it again, and it starts by itself at your next login." : "No server runs now.");
        out.println(String.join(" ", said));
        return now == null ? 0 : 1;
    }

    /** `service start`: the installed service started through the system's own way; one line says what was done and how it stands now. */
    static int start(Plan plan, PrintStream out) throws IOException, InterruptedException {
        Long running = serverNow();
        if (!Files.exists(plan.definition())) {
            out.println(running != null ? "No service is installed here; a server started with researchzosho serve is running already (pid " + running + ")."
                    : "No service is installed here, so there is nothing to start. researchzosho service install installs the service and starts it; researchzosho serve runs the server in this terminal.");
            return running != null ? 0 : 1;
        }
        if (running != null) { out.println("The service is running already (pid " + running + ")."); return 0; }
        int rc = switch (plan.os()) {
            case linux -> quiet(List.of("systemctl", "--user", "start", NAME), out);
            case macos -> { int r = quiet(List.of("launchctl", "bootstrap", "gui/" + uid(), plan.definition().toString()), out); yield r == 0 ? 0 : quiet(List.of("launchctl", "kickstart", "gui/" + uid() + "/" + MAC_LABEL), out); }
            default -> quiet(windowsStartNow(plan.definition()), out);
        };
        long until = System.currentTimeMillis() + SETTLE.toMillis();
        Long pid = serverNow();
        while (rc == 0 && pid == null && System.currentTimeMillis() < until) { Thread.sleep(250); pid = serverNow(); }
        Path log = Config.stateDir(Path.of(System.getProperty("user.home"))).resolve("logs").resolve("librarian-serve.log");
        out.println(pid != null ? "Started the service. It is running now (pid " + pid + ") and answers in a few seconds."
                : rc != 0 ? "The service could not be started (exit " + rc + "). Its log is " + log + "."
                : "Asked the service to start, but no server is running yet. researchzosho service status says how it stands, and its log is " + log + ".");
        return pid != null ? 0 : 1;
    }

    /** Waits up to {@link #SETTLE} for {@code pid} to end. */
    static void waitEnded(long pid) throws InterruptedException {
        long until = System.currentTimeMillis() + SETTLE.toMillis();
        while (System.currentTimeMillis() < until && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(100);
    }

    /** The commands the service's own ways run; a test stands them in. */
    interface Exec { int run(List<String> cmd, PrintStream out) throws IOException, InterruptedException; }
    static volatile Exec EXEC = Service::execNow;

    /** A command whose output is said only when it fails: stop and start say their one line, not the system's. */
    static int quiet(List<String> cmd, PrintStream out) throws IOException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int rc = EXEC.run(cmd, new PrintStream(b, true, StandardCharsets.UTF_8));
        if (rc != 0) out.print(b.toString(StandardCharsets.UTF_8));
        return rc;
    }

    /** Whether installing {@code plan} changes a service that is already installed: its definition exists and says something else. */
    public static boolean changes(Plan plan) {
        try {
            return Files.exists(plan.definition()) && !Files.readString(plan.definition(), StandardCharsets.UTF_8).equals(plan.text());
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * The commands install runs. On Linux {@code enable --now} starts the service only when it is not running, so a changed unit
     * would wait for the next reboot: a change restarts it. macOS unloads and loads the agent anyway; Windows stops the old server
     * before starting (in {@link #run}).
     */
    static List<List<String>> installSteps(Plan plan, boolean changed) {
        if (plan.os() != Os.linux || !changed) return plan.install();
        List<List<String>> steps = new ArrayList<>(plan.install());
        steps.add(List.of("systemctl", "--user", "restart", NAME));
        return steps;
    }

    /**
     * Where the service's server records its pid: the state dir, beside the logs. `service status`, `service restart` and `service
     * uninstall` (which stop it by this record on Windows), the update and `share` read it. Another daemon writes it only as
     * {@link #pidFileFor} says.
     */
    public static Path pidFile() { return Config.home().resolve("researchzosho.pid"); }

    /** Where a daemon of another library records its pid when the service's record is not its own: inside that library's catalog. */
    public static Path pidFileIn(Path library) { return library.resolve("catalog").resolve("researchzosho.pid"); }

    /**
     * Where a daemon records its pid. The service's record belongs to the daemon the service manager started (its definition sets
     * RESEARCHZOSHO_SERVICE=1) and to a daemon of the default library; any other daemon, as a test daemon on a scratch library or another
     * port, writes it only while no other running daemon holds it, and otherwise records its pid inside its own library's catalog, so the
     * service keeps its record. {@code heldByAnother}: the running daemon other than this one that the service's record holds, or null.
     */
    static Path pidFileFor(Path serviceRecord, Path library, boolean startedAsService, boolean defaultLibrary, Long heldByAnother) {
        return startedAsService || defaultLibrary || heldByAnother == null ? serviceRecord : pidFileIn(library);
    }

    /**
     * Called by `serve`: record this process so `service uninstall` can stop it on a platform that will not, where {@link #pidFileFor} says.
     * On exit the record is taken away only while it still holds this process's pid. The record's path, or null when none could be written.
     */
    public static Path recordPid(LibraryStore store) {
        long self = ProcessHandle.current().pid();
        Path service = pidFile();
        Long other = runningServer(service);
        Path f = pidFileFor(service, store.root(), "1".equals(System.getenv("RESEARCHZOSHO_SERVICE")), sameFolder(store.root(), defaultLibrary()), other);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, Long.toString(self), StandardCharsets.UTF_8);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> forget(f, self)));
            System.out.println("  pid " + self + " recorded at " + f + (f.equals(service) ? "" : ", inside this library, because the service's own record, " + service + ", belongs to the running pid " + other + " and is left alone"));
            return f;
        } catch (IOException e) {
            System.out.println("  pid not recorded (" + e + "): service uninstall will stop only what the platform stops");
            return null;
        }
    }

    /** Takes a pid record away only while it still holds {@code pid}: a daemon that has recorded itself there since keeps its record. */
    static boolean forget(Path file, long pid) {
        Long held = recordedPid(file);
        if (held == null || held != pid) return false;
        try { return Files.deleteIfExists(file); } catch (IOException e) { return false; }
    }

    /** The library a command opens when nothing names one for it: the one the config file names, else the folder in the home. */
    static Path defaultLibrary() {
        String stored = Config.stored("RESEARCHZOSHO_LIBRARY");
        return stored != null ? Path.of(stored) : LibraryStore.defaultRoot(Path.of(System.getProperty("user.home")));
    }

    static boolean sameFolder(Path a, Path b) {
        try { return a.toRealPath().equals(b.toRealPath()); }
        catch (IOException e) { return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize()); }
    }

    static Long recordedPid(Path pidFile) {
        try { return Files.exists(pidFile) ? Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).strip()) : null; }
        catch (IOException | NumberFormatException e) { return null; }
    }

    /** The recorded server's pid when it is running and is a JVM other than this one; else null. */
    static Long runningServer(Path pidFile) {
        Long pid = recordedPid(pidFile);
        if (pid == null || pid == ProcessHandle.current().pid()) return null;
        var h = ProcessHandle.of(pid);
        if (h.isEmpty() || !h.get().isAlive()) return null;
        return h.get().info().command().orElse("").toLowerCase(Locale.ROOT).contains("java") ? pid : null;
    }

    /** Stop the recorded server if it is alive and is a JVM (never a pid that was reused by something else). */
    static boolean stopRecorded(Path pidFile, PrintStream out) {
        Long pid = recordedPid(pidFile);
        if (pid == null) return false;
        var h = ProcessHandle.of(pid);
        if (h.isEmpty() || !h.get().isAlive()) { forget(pidFile, pid); return false; }
        String cmd = h.get().info().command().orElse("").toLowerCase(Locale.ROOT);
        if (!cmd.contains("java")) {
            // the server is gone and another program has its number now: that program is left alone, and the stale record goes
            out.println("  pid " + pid + " is not the server any more (" + cmd + "); left alone");
            forget(pidFile, pid);
            return false;
        }
        h.get().destroy();
        try { h.get().onExit().get(10, TimeUnit.SECONDS); } catch (Exception e) { h.get().destroyForcibly(); }
        forget(pidFile, pid);   // a server started since, which recorded itself, keeps its record
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

    static int exec(List<String> cmd, PrintStream out) throws IOException, InterruptedException { return EXEC.run(cmd, out); }

    static int execNow(List<String> cmd, PrintStream out) throws IOException, InterruptedException {
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
                Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) { }
    }
}
