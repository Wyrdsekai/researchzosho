package org.researchzosho.librarian;

import org.researchzosho.Config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reaching the library from outside the house (0.6.1, docs/AFTER_0_6.md §5). {@code researchzosho share} puts the shared door behind an
 * encrypted relay the library dials out to, so no port is opened at home and it works behind carrier NAT. Reachable now, and public (the
 * operator, 2026-10-13: the point of sharing is people without Tailscale, and the first run must work in a minute): the plain {@code share}
 * takes Tailscale Funnel when Tailscale is logged in, an HTTPS address that lasts, else the Cloudflare quick tunnel at once, saying that its
 * address changes and that {@code --tailscale} is the better long-term setup. Only the sign-in page is public and nobody unlisted gets in;
 * {@code --tailnet} uses {@code tailscale serve}, the same address on the tailnet only, where Tailscale names the caller on every request. The quick tunnel: {@code cloudflared tunnel --url}, a random trycloudflare.com address
 * that changes at every start, so the service runs it and keeps the address where {@code share status} and the Settings page show it;
 * {@code --mail} restricts it to listed addresses with a one-time code. Free in every case; a paid or business tailnet uses the same
 * commands, and each known refusal is answered with its next step. With neither program on the machine, {@code share} installs
 * what it needs, asking before each step: Tailscale the vendor's way (its install script on Linux, Homebrew on a Mac — Homebrew
 * itself first when the Mac has none — winget on Windows), then {@code tailscale up} with its login link; or cloudflared, one binary
 * fetched into the library's own folder, no root and no account. The person's password is asked on the screen by the installer, never
 * by the library, and nothing happens without a yes ({@code --yes} answers for a script).
 *
 * <p>The settings, all live for the running service: {@code RESEARCHZOSHO_SHARE} (off, tailscale, funnel, cloudflare: the service opens
 * the shared door and starts or stops the tunnel within half a minute), {@code RESEARCHZOSHO_DOOR} (the door's port on loopback, 4651),
 * {@code RESEARCHZOSHO_SHARE_ADDRESS} (where the library is reached from outside, as the relay reported it; join links are written with
 * it), {@code RESEARCHZOSHO_SHARE_MAIL} (the quick tunnel's mail list). With share off, none of this runs.
 */
public final class Share {
    private Share() { }

    public static final String SHARE = "RESEARCHZOSHO_SHARE", DOOR = "RESEARCHZOSHO_DOOR", ADDRESS = "RESEARCHZOSHO_SHARE_ADDRESS", MAIL = "RESEARCHZOSHO_SHARE_MAIL";
    public static final int DEFAULT_DOOR = 4651;
    public static final List<String> KINDS = List.of("off", "tailscale", "funnel", "cloudflare");

    /** Runs a command line; stdout+stderr and the exit code. Tests replace it. */
    public interface Runner { Video.Result run(List<String> cmd, Duration timeout) throws Exception; }
    public static volatile Runner runner = (cmd, timeout) -> Video.runner.run(cmd, timeout);
    /** Runs a command on the person's own screen and keyboard: an installer asks for the password there, `tailscale up` prints its link there. Tests replace it. */
    /** A command on the person's own screen, waited for; with a limit, ended when the limit runs out (TIMED_OUT), without one waited for as long as it takes. */
    public interface Interactive { int run(List<String> cmd, Duration limit) throws Exception; }
    /** The exit of a command the limit ended. */
    public static final int TIMED_OUT = -2;
    public static volatile Interactive interactive = (cmd, limit) -> {
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        if (limit == null || p.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) return p.waitFor();
        p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); return TIMED_OUT;
    };
    /** How long a script's yes waits for the login (nobody is there to end it): the link stays open after, held by tailscaled. */
    public static volatile Duration loginWait = Duration.ofMinutes(10);
    /** Fetches a web address to a file. Tests replace it. */
    public static volatile Video.Downloader downloader = (url, to) -> Video.downloader.fetch(url, to);
    /** The machine's kind (linux, darwin, windows) and its chip (amd64, arm64). Tests replace them. */
    public static volatile Supplier<String> os = Video::os, arch = Video::arch;
    /** Finds a program on this machine: its path, or null. Tests replace it. */
    public static volatile Function<String, String> which = Share::whichNow;
    /** Whether the service runs on this machine (its recorded pid is alive). Tests replace it. */
    public static volatile BooleanSupplier serviceRunning = Share::serviceRunningNow;
    /** The quick tunnel's command line for a door port. Tests replace it with a stand-in that prints an address. */
    public static volatile Function<Integer, List<String>> tunnelCommand = Share::tunnelCommandNow;
    /** How often the service looks at the share setting. Tests shorten it. */
    public static volatile long pollMs = 15_000;
    /** How long `share` waits for the service to report the quick tunnel's address before it says where to find it later. */
    public static volatile long addressWaitMs = 45_000;

    static final ObjectMapper M = new ObjectMapper();
    static final Pattern QUICK_URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    /** Where the library keeps a program it fetched itself (cloudflared). */
    static Path binDir() { return Config.home().resolve("share").resolve("bin"); }

    static String whichNow(String program) {
        List<String> dirs = new ArrayList<>();
        dirs.add(binDir().toString());
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) if (!dir.isBlank()) dirs.add(dir);
        dirs.add("/opt/homebrew/bin"); dirs.add("/usr/local/bin");   // Homebrew's, when the shell that started us had no PATH for it
        for (String dir : dirs) {
            Path p = Path.of(dir, program);
            if (Files.isExecutable(p)) return p.toString();
            if (Files.isExecutable(Path.of(dir, program + ".exe"))) return Path.of(dir, program + ".exe").toString();
        }
        // the App Store build on macOS keeps its command line inside the app
        if (program.equals("tailscale") && Files.isExecutable(Path.of("/Applications/Tailscale.app/Contents/MacOS/Tailscale"))) return "/Applications/Tailscale.app/Contents/MacOS/Tailscale";
        return null;
    }

    /** A yes from the person: {@code yes} answers for a script; nobody at the keyboard is no; Enter is yes. */
    static boolean ask(String question, boolean yes, PrintStream out) throws IOException {
        if (yes) return true;
        if (!Interaction.interactive()) return false;
        out.print(question + " (Y/n) "); out.flush();
        String line = Interaction.readLine();
        return line != null && (line.isBlank() || Librarian.yesWord(line) != null);
    }

    static int exec(PrintStream out, List<String> cmd) { return exec(out, cmd, null); }

    static int exec(PrintStream out, List<String> cmd, Duration limit) {
        out.println("  $ " + String.join(" ", cmd));
        try { return interactive.run(cmd, limit); } catch (Exception e) { out.println("  could not run: " + e.getMessage()); return -1; }
    }

    static String cloudflaredAsset() {
        String o = os.get(), a = arch.get();
        return o.equals("windows") ? "cloudflared-windows-amd64.exe" : o.equals("darwin") ? "cloudflared-darwin-" + a + ".tgz" : "cloudflared-linux-" + a;
    }

    /** cloudflared fetched into the library's own folder: one binary, no root, no account; GitHub's own "latest" link names the newest release. */
    static String fetchCloudflared(PrintStream out) throws IOException {
        Files.createDirectories(binDir());
        String asset = cloudflaredAsset();
        String url = "https://github.com/cloudflare/cloudflared/releases/latest/download/" + asset;
        Path target = binDir().resolve(os.get().equals("windows") ? "cloudflared.exe" : "cloudflared");
        out.println("  fetching cloudflared (" + asset + ") into " + binDir() + " — the library's own folder, no root, no account");
        try {
            if (asset.endsWith(".tgz")) {
                Path tgz = binDir().resolve(asset);
                downloader.fetch(url, tgz);
                Video.Result r = runner.run(List.of("tar", "-xzf", tgz.toString(), "-C", binDir().toString()), Duration.ofMinutes(2));
                Files.deleteIfExists(tgz);
                if (r.code() != 0) throw new IOException("the archive could not be opened: " + r.out());
            } else downloader.fetch(url, target);
        } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException("cloudflared could not be fetched from " + url + ": " + e.getMessage()); }
        if (!Files.isRegularFile(target)) throw new IOException("cloudflared was fetched but is not at " + target);
        target.toFile().setExecutable(true);
        return target.toString();
    }

    /** Tailscale installed the vendor's way, a yes before each step; the installer asks for the person's password on the screen, never the library. */
    static String installTailscale(PrintStream out, boolean yes) throws IOException {
        String o = os.get();
        switch (o) {
            case "linux" -> {
                if (!ask("Install Tailscale with its own install script? It asks for your password once.", yes, out)) return null;
                int rc = exec(out, List.of("sh", "-c", "curl -fsSL https://tailscale.com/install.sh | sh"));
                if (rc != 0) { out.println("The Tailscale install script ended with " + rc + ". https://tailscale.com/download has the steps for your system."); return null; }
            }
            case "darwin" -> {
                String brew = which.apply("brew");
                if (brew == null) {
                    if (!ask("On a Mac, Tailscale is installed with Homebrew, which this Mac does not have. Install Homebrew first? It asks for your password.", yes, out)) return null;
                    int rc = exec(out, List.of("/bin/bash", "-c", "/bin/bash -c \"$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""));
                    brew = which.apply("brew");
                    if (rc != 0 || brew == null) { out.println("Homebrew did not install" + (rc != 0 ? " (exit " + rc + ")" : "") + ". https://brew.sh has the one line to run; then researchzosho share again."); return null; }
                }
                if (!ask("Install Tailscale with Homebrew? Starting it asks for your password.", yes, out)) return null;
                int rc = exec(out, List.of(brew, "install", "tailscale"));
                if (rc != 0) { out.println("brew install tailscale ended with " + rc + "."); return null; }
                if (exec(out, List.of("sudo", brew, "services", "start", "tailscale")) != 0) out.println("Tailscale's service did not start; to try again: sudo brew services start tailscale");
            }
            default -> {
                if (!ask("Install Tailscale with winget?", yes, out)) return null;
                int rc = exec(out, List.of("winget", "install", "--id", "tailscale.tailscale", "-e", "--accept-package-agreements", "--accept-source-agreements"));
                if (rc != 0) { out.println("winget ended with " + rc + ". https://tailscale.com/download has the installer."); return null; }
            }
        }
        String ts = which.apply("tailscale");
        if (ts == null) out.println("Tailscale was installed but its command is not found yet; open a new terminal and run researchzosho share again.");
        return ts;
    }

    /** {@code tailscale up}: it prints a link to log in with and waits for the login; then this user may run serve without sudo. */
    static boolean login(String ts, PrintStream out, boolean yes) throws IOException {
        if (!ask("Log in to Tailscale now? A link to open appears; a personal tailnet is free.", yes, out)) return false;
        boolean sudo = !os.get().equals("windows");
        List<String> up = sudo ? List.of("sudo", ts, "up") : List.of(ts, "up");
        int rc = exec(out, up, yes ? loginWait : null);   // a person at the keyboard waits as long as they like; a script's yes gets the limit
        if (rc == TIMED_OUT) { out.println("Nobody logged in within " + loginWait.toMinutes() + " minutes. The link above still works: open it, log in, then researchzosho share again."); return false; }
        if (rc != 0) { out.println("tailscale up did not finish. Run it yourself, log in, then researchzosho share again."); return false; }
        if (sudo) exec(out, List.of("sudo", ts, "set", "--operator=" + System.getProperty("user.name")));   // serve and funnel without sudo from now on
        for (int i = 0; i < 60; i++) {
            try { Video.Result st = runner.run(List.of(ts, "status", "--json"), Duration.ofSeconds(30)); if (st.code() == 0 && dnsName(st.out()) != null) return true; } catch (Exception ignored) { }
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        out.println("Tailscale is not logged in yet. When it is, researchzosho share again.");
        return false;
    }

    static boolean serviceRunningNow() {
        Long pid = Service.recordedPid(Service.pidFile());
        return pid != null && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    static List<String> tunnelCommandNow(int door) {
        List<String> cmd = new ArrayList<>(List.of(which.apply("cloudflared"), "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:" + door));
        for (String m : mails()) cmd.add("--allowed-mail=" + m);
        return cmd;
    }

    public static String kind() { String k = Config.live(SHARE); return k == null || k.isBlank() ? "off" : k.strip().toLowerCase(Locale.ROOT); }
    public static int doorPort() { return Config.liveInt(DOOR, DEFAULT_DOOR); }
    public static String address() { String a = Config.live(ADDRESS); return a == null ? "" : a.strip(); }
    static List<String> mails() { String m = Config.live(MAIL); List<String> out = new ArrayList<>(); if (m != null) for (String x : m.split("[,\\s]+")) if (!x.isBlank()) out.add(x.strip()); return out; }

    /** What `share` comes to: lines for the person, and the exit code. */
    public record Outcome(int code, String text) { }

    public static Outcome share(LibraryStore store, List<String> args) throws IOException { return share(store, args, System.out); }

    /** {@code share [status | off | --tailscale | --tailnet | --cloudflare [--mail <address…>] | --door N | --yes]}; {@code out} hears the steps as they
     *  happen. The plain {@code share} makes the library reachable now: through Tailscale Funnel when Tailscale is on the machine and logged in
     *  (the lasting address), else through a Cloudflare quick tunnel at once, no account and nothing asked, saying that its address changes and
     *  that {@code --tailscale} is the better long-term setup. {@code --tailscale} installs Tailscale and walks the person through it, then Funnel;
     *  {@code --tailnet} the same on the tailnet only; {@code --cloudflare} the quick tunnel even when Tailscale is ready; {@code --public} is accepted
     *  and means the plain share. Public means: anyone with the link reaches the sign-in page and nothing else; nobody unlisted gets in. */
    public static Outcome share(LibraryStore store, List<String> args, PrintStream out) throws IOException {
        String hint = args.contains("status") || args.contains("off") ? "" : ownerStep(store, out);
        Outcome o = shareNow(store, args, out);
        return hint.isEmpty() || o.code() != 0 ? o : new Outcome(0, o.text() + "\n" + hint);
    }

    private static Outcome shareNow(LibraryStore store, List<String> args, PrintStream out) throws IOException {
        boolean pub = true, cloud = false, yes = false, wantTs = false; int door = 0; List<String> mail = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "status" -> { return new Outcome(0, status()); }
                case "off" -> { return off(); }
                case "--public" -> pub = true;
                case "--tailscale" -> wantTs = true;
                case "--tailnet", "--private" -> { pub = false; wantTs = true; }
                case "--cloudflare" -> cloud = true;
                case "--yes", "-y" -> yes = true;
                case "--mail" -> { if (i + 1 >= args.size()) return new Outcome(2, "--mail takes one or more addresses, or *@a-domain."); for (String m : args.get(++i).split(",")) if (!m.isBlank()) mail.add(m.strip()); }
                case "--door" -> { try { door = Integer.parseInt(args.get(++i)); } catch (Exception e) { return new Outcome(2, "--door takes a port number."); } }
                default -> { return new Outcome(2, "usage: researchzosho share [status | off | --tailscale | --tailnet | --cloudflare [--mail <address…>] | --door N | --yes]"); }
            }
        }
        if (door > 0) Config.set(DOOR, String.valueOf(door)); else door = doorPort();
        String ts = cloud ? null : which.apply("tailscale"), cf = which.apply("cloudflared");
        if (!cloud && !wantTs && (ts == null || !loggedIn(ts))) {
            // the plain share with no Tailscale ready: reachable now, through the quick tunnel, nothing asked; Tailscale, the better long-term setup, by its own flag
            if (cf == null) cf = fetchCloudflared(out);
            return cloudflare(store, mail, door, true);
        }
        if (!cloud && ts == null) {
            // --tailscale or --tailnet without the program: the library installs it on a yes, else offers the tunnel
            boolean install = ask("Install Tailscale, the better long-term setup? It is free; the library installs it for you and asks before each step.", yes, out);
            if (install) ts = installTailscale(out, yes);
            if (ts == null) {
                if (install || Interaction.interactive() || yes) {
                    if (ask(cf == null ? "Use a Cloudflare quick tunnel instead? No account, no password; the library fetches it into its own folder." : "Use the Cloudflare quick tunnel instead (cloudflared is on this computer)?", yes, out)) cloud = true;
                }
                if (!cloud) return new Outcome(1, "To share the library you need one of two free programs" + (cf == null ? ", and neither is on this computer" : "") + ".\n"
                        + "  Tailscale, the better long-term setup: researchzosho share --tailscale installs it for you when you say yes; or get it at https://tailscale.com/download, run `tailscale up`, then `researchzosho share` again.\n"
                        + "  cloudflared, works now: researchzosho share --cloudflare (the plain share does the same) fetches it into the library's own folder (no account), or get it at https://developers.cloudflare.com/cloudflare-one/connections/connect-apps/install-and-setup/installation/.\n"
                        + "Nothing is installed without your yes; a script says --yes.");
            }
        }
        if (cloud) {
            if (cf == null) cf = fetchCloudflared(out);
            return cloudflare(store, mail, door, false);
        }
        return tailscale(store, ts, pub, door, cf != null, out, yes);
    }

    /** Whether Tailscale is logged in on this machine: its status names the machine. */
    static boolean loggedIn(String ts) {
        try { Video.Result st = runner.run(List.of(ts, "status", "--json"), Duration.ofSeconds(30)); return st.code() == 0 && dnsName(st.out()) != null; } catch (Exception e) { return false; }
    }

    /** The quick tunnel's address once the service has started it and read it, waited for up to {@link #addressWaitMs}; empty when it is not there yet. */
    static String waitForAddress() {
        long until = System.currentTimeMillis() + addressWaitMs;
        while (true) {
            String a = address();
            if (!a.isEmpty() || System.currentTimeMillis() >= until) return a;
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return ""; }
        }
    }

    /** Before the first open: sign-in on, said in a sentence; the door's own rule (nobody unlisted) needs no setting. */
    static String firstOpen(LibraryStore store) throws IOException {
        if (WebAccess.signInRequired()) return "";
        Config.set(WebAccess.SIGNIN, "on");
        return "Sign-in is on now: through the door, only a reader you listed gets in; anyone else sees the sign-in page and can ask to be let in.\n";
    }

    /** Tests turn this off; the owner's account is asked for before the library is shared, so they are not locked out of their own pages from outside. */
    static boolean askOwnerAccount = true;

    /** The owner's account, before the first share: at the keyboard the walk-through; from a script, the one line to run, for the end of the outcome. */
    static String ownerStep(LibraryStore store, PrintStream out) throws IOException {
        if (!askOwnerAccount || Accounts.owner(store) != null) return "";
        if (Interaction.interactive()) { out.println("First, your own account, so you can sign in from outside with your name and password."); out.println(Accounts.setupOwner(store, List.of())); return ""; }
        return Accounts.setupOwner(store, List.of());
    }

    static Outcome tailscale(LibraryStore store, String ts, boolean pub, int door, boolean haveCloudflared, PrintStream out, boolean yes) throws IOException {
        String dns;
        try {
            Video.Result st = runner.run(List.of(ts, "status", "--json"), Duration.ofSeconds(30));
            dns = st.code() == 0 ? dnsName(st.out()) : null;
            if (dns == null) {
                String state = st.code() == 0 ? backendState(st.out()) : "";
                out.println("Tailscale is on this computer but not logged in" + (state.isEmpty() ? "" : " (" + state + ")") + ".");
                if (login(ts, out, yes)) { st = runner.run(List.of(ts, "status", "--json"), Duration.ofSeconds(30)); dns = st.code() == 0 ? dnsName(st.out()) : null; }
                if (dns == null) return new Outcome(1, "Tailscale is not logged in. Run `tailscale up`, log in, then `researchzosho share` again."
                        + (haveCloudflared ? "\nOr, without an account: researchzosho share --cloudflare" : ""));
            }
        } catch (IOException e) { throw e; } catch (Exception e) { return new Outcome(1, "Tailscale could not be asked: " + e.getMessage()); }
        StringBuilder text = new StringBuilder(firstOpen(store));
        Video.Result r;
        try { r = runner.run(List.of(ts, pub ? "funnel" : "serve", "--bg", String.valueOf(door)), Duration.ofMinutes(1)); }
        catch (Exception e) { return new Outcome(1, text + "tailscale could not be run: " + e.getMessage()); }
        if (r.code() != 0) return new Outcome(1, text + explain(r.out(), pub));
        String address = "https://" + dns;
        Config.set(SHARE, pub ? "funnel" : "tailscale");
        Config.set(ADDRESS, address);
        text.append(pub ? "Public at " + address + " (Tailscale Funnel): anyone with the link reaches the sign-in page; a reader gets in with their name and password.\n"
                        : "Shared on your tailnet at " + address + ": a person on the tailnet is named to the library as they open it, and a reader you listed under that name is signed in.\n")
            .append("  The door is 127.0.0.1:").append(door).append("; the service opens it within half a minute (researchzosho share status says so).\n")
            .append(pub ? "  To keep it to your tailnet only, where Tailscale names each visitor: researchzosho share --tailnet\n" : "  To let people without Tailscale in: researchzosho share\n")
            .append("  To invite a person: researchzosho reader invite \"<name>\" read   (the link is written with this address)\n")
            .append("  To stop sharing: researchzosho share off");
        return new Outcome(0, text.toString());
    }

    /** The quick tunnel; {@code plain} when the plain share chose it, which says that Tailscale is the better long-term setup. */
    static Outcome cloudflare(LibraryStore store, List<String> mail, int door, boolean plain) throws IOException {
        StringBuilder text = new StringBuilder(firstOpen(store));
        Config.set(SHARE, "cloudflare");
        Config.set(MAIL, String.join(",", mail));
        Config.set(ADDRESS, "");
        text.append("Shared through a Cloudflare quick tunnel").append(mail.isEmpty() ? "" : ", for " + String.join(", ", mail) + " (a one-time code by mail opens it)").append(".\n");
        if (serviceRunning.getAsBoolean()) {
            String address = waitForAddress();
            text.append(address.isEmpty() ? "  The service starts the tunnel within half a minute; researchzosho share status and the Settings page show the address.\n" : "  You are reachable now at " + address + ".\n")
                .append("  This address changes when the service restarts, so send an invite when the person can open it straight away.\n")
                .append(plain ? "  For an address that lasts, run researchzosho share --tailscale: it installs Tailscale and walks you through it, the better long-term setup.\n" : "")
                .append("  To invite a person: researchzosho reader invite \"<name>\" read   (the link is written with the address once there is one)\n")
                .append("  To stop sharing: researchzosho share off");
            return new Outcome(0, text.toString());
        }
        text.append("  No service runs here, so the tunnel runs in this terminal until you press Ctrl-C (researchzosho service install keeps it up instead).");
        System.out.println(text);
        Tunnel t = new Tunnel(store, door);
        t.runOnce(System.out);
        Config.set(ADDRESS, "");
        return new Outcome(0, "The tunnel ended.");
    }

    static Outcome off() throws IOException {
        String kind = kind();
        StringBuilder text = new StringBuilder();
        if (kind.equals("tailscale") || kind.equals("funnel")) {
            String ts = which.apply("tailscale");
            if (ts != null) {
                try {
                    if (kind.equals("funnel")) runner.run(List.of(ts, "funnel", "--https=443", "off"), Duration.ofSeconds(30));
                    runner.run(List.of(ts, "serve", "--https=443", "off"), Duration.ofSeconds(30));
                } catch (Exception e) { text.append("tailscale could not be run: ").append(e.getMessage()).append('\n'); }
            }
        }
        Config.set(SHARE, "off");
        Config.set(ADDRESS, "");
        text.append(kind.equals("off") ? "The library was not shared." : "Not shared any more: the relay is off, the service closes the door" + (kind.equals("cloudflare") ? " and ends the tunnel" : "") + " within half a minute. The readers stay as they are, and sign-in stays on (researchzosho web signin off turns it off).");
        return new Outcome(0, text.toString());
    }

    /** The share line for `status`, and the whole of `share status`. */
    public static String statusLine() {
        String kind = kind(), a = address();
        return switch (kind) {
            case "off" -> "shared: no (researchzosho share puts it behind a relay: reachable now; --tailscale for an address that lasts)";
            case "cloudflare" -> "shared: Cloudflare quick tunnel" + (a.isEmpty() ? ", no address yet (the service starts it; share status in a moment)" : " at " + a);
            case "funnel" -> "shared: public, Tailscale Funnel at " + a;
            default -> "shared: on the tailnet at " + a;
        };
    }

    public static String status() {
        StringBuilder b = new StringBuilder("  ").append(statusLine()).append('\n');
        String kind = kind();
        if (kind.equals("off")) return b.toString();
        b.append("  door: 127.0.0.1:").append(doorPort()).append(serviceRunning.getAsBoolean() ? " (the service is running)" : " (the service is not running: researchzosho service install)").append('\n');
        if (kind.equals("cloudflare")) {
            List<String> m = mails();
            b.append("  mail: ").append(m.isEmpty() ? "anyone with the address" : String.join(", ", m)).append('\n');
            b.append("  the address changes when the service restarts (send an invite when the person can open it straight away); for one that lasts: researchzosho share --tailscale\n");
            Path f = stateFile();
            if (Files.exists(f)) { try { JsonNode s = M.readTree(Files.readString(f)); b.append("  tunnel: ").append(s.path("state").asText()).append(" since ").append(s.path("since").asText()).append(s.path("restarts").asInt() > 0 ? ", restarted " + s.path("restarts").asInt() + " time(s)" : "").append('\n'); } catch (IOException ignored) { } }
        } else {
            String ts = which.apply("tailscale");
            if (ts != null) { try { Video.Result r = runner.run(List.of(ts, "serve", "status"), Duration.ofSeconds(30)); for (String line : r.out().strip().split("\n")) b.append("  ").append(line).append('\n'); } catch (Exception ignored) { } }
        }
        return b.toString();
    }

    /** A tailscale refusal, read for its cause, with the exact next step. */
    static String explain(String out, boolean funnel) {
        String o = out == null ? "" : out;
        String l = o.toLowerCase(Locale.ROOT);
        String step;
        if (l.contains("operator")) step = "Tailscale wants the operator permission once, so that this user may run serve: sudo tailscale set --operator=" + System.getProperty("user.name") + "   (then researchzosho share again).";
        else if (l.contains("funnel") && (l.contains("not enabled") || l.contains("not available") || l.contains("nodeattrs") || l.contains("policy") || l.contains("attr"))) step = "Funnel is not approved for this machine yet. Approve it once in the admin console (the link above), then researchzosho share --public again. On a tagged server, give the tag the funnel attribute in the policy file:\n  \"nodeAttrs\": [{ \"target\": [\"tag:server\"], \"attr\": [\"funnel\"] }]";
        else if (l.contains("serve is not enabled") || (l.contains("serve") && l.contains("not enabled"))) step = "Serve is not approved for this machine yet. Approve it once at the link tailscale printed (the admin console), then researchzosho share again.";
        else if (l.contains("https certificate") || l.contains("https is not enabled") || l.contains("enable https") || l.contains("magicdns")) step = "HTTPS certificates are not turned on for your tailnet yet. Turn on MagicDNS and HTTPS Certificates once at https://login.tailscale.com/admin/dns, then researchzosho share again.";
        else if (l.contains("not logged in") || l.contains("logged out") || l.contains("needslogin")) step = "Log in first: tailscale up";
        else if (l.contains("app store") || l.contains("not supported")) step = (funnel ? "Funnel" : "Serve") + " is not available in this build of Tailscale; on macOS the App Store build keeps its command line inside the app, and Funnel needs the open-source build (brew install tailscale).";
        else step = "Tailscale refused. The message above says why; researchzosho share status after you fix it.";
        return "tailscale said:\n  " + o.strip().replace("\n", "\n  ") + "\n" + step;
    }

    static String dnsName(String statusJson) {
        try { JsonNode j = M.readTree(statusJson); if (!"Running".equals(j.path("BackendState").asText(""))) return null; String d = j.path("Self").path("DNSName").asText(""); return d.isEmpty() ? null : d.replaceAll("\\.$", ""); }
        catch (Exception e) { return null; }
    }

    static String backendState(String statusJson) { try { return M.readTree(statusJson).path("BackendState").asText(""); } catch (Exception e) { return ""; } }

    // ── the service's side: the door, the tunnel ──────────────────────────────────────────────────

    static Path stateFile() { return Config.home().resolve("share.json"); }

    /**
     * The watcher the service runs: every {@link #pollMs} it reads the share setting, opens or closes the daemon's door, and runs the quick
     * tunnel while the setting says cloudflare. With share off nothing is open and nothing runs.
     */
    public static Thread supervise(LibraryStore store, LibrarianDaemon d) {
        Thread t = new Thread(() -> {
            Tunnel tunnel = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String kind = kind();
                    if (!kind.equals("off") && d.doorPort() < 0) { d.openDoor(doorPort()); Crews.log(store, "share", "the door is open at 127.0.0.1:" + d.doorPort() + " (" + kind + ")", 0); }
                    if (kind.equals("off") && d.doorPort() >= 0) { d.closeDoor(); Crews.log(store, "share", "the door is closed", 0); }
                    if (kind.equals("cloudflare") && (tunnel == null || !tunnel.alive())) { if (tunnel != null) tunnel.stop(); tunnel = new Tunnel(store, d.doorPort()); tunnel.start(); }
                    if (!kind.equals("cloudflare") && tunnel != null) { tunnel.stop(); tunnel = null; }
                    Thread.sleep(pollMs);
                } catch (InterruptedException e) {
                    if (tunnel != null) tunnel.stop();
                    return;
                } catch (Throwable e) {
                    Crews.log(store, "share", "FAILED: " + e, 0);
                    try { Thread.sleep(Math.max(pollMs, 10_000)); } catch (InterruptedException ie) { if (tunnel != null) tunnel.stop(); return; }
                }
            }
        }, "librarian-share");
        t.setDaemon(true);
        return t;
    }

    /** One quick tunnel: the process, its address read from its output, restarted with a growing wait when it ends. */
    static final class Tunnel {
        final LibraryStore store; final int door;
        volatile Process process; volatile Thread thread; volatile boolean stopping; int restarts;
        Tunnel(LibraryStore store, int door) { this.store = store; this.door = door; }

        boolean alive() { Thread t = thread; return t != null && t.isAlive(); }

        void start() {
            thread = new Thread(() -> {
                long wait = 10_000;
                while (!stopping) {
                    long began = System.currentTimeMillis();
                    runOnce(null);
                    if (stopping) break;
                    restarts++;
                    if (System.currentTimeMillis() - began > 5 * 60_000) wait = 10_000; else wait = Math.min(wait * 2, 5 * 60_000);
                    Crews.log(store, "share", "the tunnel ended; starting it again in " + wait / 1000 + " s", 0);
                    try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
                }
            }, "librarian-tunnel");
            thread.setDaemon(true);
            thread.start();
        }

        /** Runs the tunnel once and waits for it to end; {@code out} hears the lines when a person is watching. */
        void runOnce(PrintStream out) {
            List<String> cmd = tunnelCommand.apply(door);
            String mine = null;   // the address this run wrote, the only one its ending may take back
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                process = p;
                state("starting", "");
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (out != null) out.println("  " + line);
                        Matcher m = QUICK_URL.matcher(line);
                        if (m.find()) {
                            String url = m.group();
                            Config.set(ADDRESS, url);
                            mine = url;
                            state("up", url);
                            Crews.log(store, "share", "the quick tunnel is at " + url, 0);
                            if (out != null) out.println("The library is at " + url + " until this ends.");
                        }
                    }
                }
                p.waitFor();
            } catch (Exception e) {
                if (!stopping) Crews.log(store, "share", "the tunnel could not run: " + e.getMessage(), 0);
            } finally {
                process = null;
                // the address goes with this run, unless a newer tunnel has written its own since (a stopped tunnel ends while the next one starts)
                try { if (mine != null) Config.replace(ADDRESS, mine, ""); } catch (IOException ignored) { }
                state(stopping ? "stopped" : "ended", "");
            }
        }

        void stop() {
            stopping = true;
            Process p = process;
            if (p != null) {
                // the process and whatever it started (a shell's child keeps the output open otherwise), asked first, then made to
                p.descendants().forEach(ProcessHandle::destroy);
                p.destroy();
                try { if (!p.waitFor(5, TimeUnit.SECONDS)) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); } }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            Thread t = thread;
            if (t != null) t.interrupt();
        }

        void state(String state, String url) {
            try {
                ObjectNode o = M.createObjectNode();
                o.put("state", state); o.put("address", url); o.put("since", Instant.now().toString().substring(0, 19) + "Z"); o.put("restarts", restarts);
                Process p = process; if (p != null) o.put("pid", p.pid());
                Files.createDirectories(stateFile().getParent());
                Files.writeString(stateFile(), o.toString());
            } catch (IOException ignored) { }
        }
    }
}
