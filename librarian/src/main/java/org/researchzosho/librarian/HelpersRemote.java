package org.researchzosho.librarian;

import org.researchzosho.Config;
import org.researchzosho.Version;
import org.researchzosho.librarian.Helpers.Helper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code researchzosho helpers install --on <ssh target>} (0.7.4): the heavy helpers set up on another machine of the person's from
 * this one, over ssh with the person's own keys. In order, each step said as it happens: what will be set up there and how big, and
 * one question; the program there (installed by the published installer when it is missing, updated when it is older than the first
 * release that knows {@code helpers install}, or than the first that knows what is asked of it: {@code --no-cpu-fallback}, the comic
 * helper); {@code helpers install --yes} there, its lines passed through; the secret read back
 * (never printed here), {@code helpers} and {@code helpers_secret} set on this machine, the health asked from here with it, and the
 * helpers' status as the last thing.
 *
 * <p>Every ssh call goes through {@link #SSH}, an argument list and never a shell line built here: the target is checked (letters,
 * digits, dot, dash and underscore, at most one {@code @}) and passed as its own argument, and the command it runs there is made of
 * this class's own words, a card's index, helpers' names and the installer's test hooks, each checked or quoted.
 */
final class HelpersRemote {
    private HelpersRemote() { }

    /** The first release that knows {@code helpers install}. */
    static final String KNOWS_INSTALL = "0.7.4";
    /**
     * The first release that can be handed {@code --no-cpu-fallback} and {@code --cpu-fallback}: 0.7.4 refuses the flag, and 0.7.5 takes it
     * but its helpers server runs the MetricX-24 script an earlier release installed, which does not know the option and ends with code 2.
     */
    static final String KNOWS_FALLBACK = "0.7.6";
    /** The first release that serves the comic helper to other machines: an earlier one refuses --only comic, and serves the others without it. */
    static final String KNOWS_COMIC = "0.7.6";
    /** The program there is found on the PATH its installer uses, also from a shell that read no profile. */
    static final String PATH_THERE = "PATH=\"$HOME/.local/bin:$PATH\"; export PATH; ";
    static final String INSTALLER = "https://researchzosho.org/install";

    /** What an ssh call gave: its exit code and its output; {@code lines} received each line as it came. */
    record Ran(int code, String out) { }

    /** One ssh call; a test stands it in. {@code lines}: each output line as it comes, or null to collect it only. */
    interface Ssh { Ran run(List<String> argv, Consumer<String> lines) throws IOException, InterruptedException; }

    static volatile Ssh SSH = (argv, lines) -> {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.PIPE).start();
        p.getOutputStream().close();   // nothing to read on that side: a question there gets an end of input, never a hang
        StringBuilder all = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) { all.append(line).append('\n'); if (lines != null) lines.accept(line); }
        }
        return new Ran(p.waitFor(), all.toString());
    };

    /** How long the health is asked from here after the install there. */
    static volatile Duration ANSWER_WAIT = Duration.ofMinutes(2);
    /** The health from here; a test stands it in. */
    static volatile HealthAsk HEALTH = HelpersInstall::health;
    interface HealthAsk { Map<String, String> ask(String base, String secret, Duration wait); }

    /** Whether a target is one this command passes to ssh: user@host or host, letters, digits, dot, dash, underscore, one @ at most. */
    static boolean validTarget(String t) { return t != null && t.matches("([A-Za-z0-9._-]+@)?[A-Za-z0-9._-]+") && !t.startsWith("-") && !t.contains("@-"); }

    /** The ssh argument list for {@code command} there: no password prompt, a bounded wait for the connection, the target its own argument. */
    static List<String> ssh(String target, String command) {
        return List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", target, command);
    }

    /** A word quoted for the shell there. */
    static String quoted(String v) { return "'" + v.replace("'", "'\\''") + "'"; }

    /** This machine's environment, where the hooks are read; a test stands it in. */
    static volatile Function<String, String> ENV = System::getenv;

    /** The installer's and the updater's test hooks from this machine's environment, as assignments for the command there: "" when none is set. */
    static String hooks() {
        StringBuilder b = new StringBuilder();
        String base = ENV.apply("RESEARCHZOSHO_DOWNLOAD_BASE"), ver = ENV.apply("RESEARCHZOSHO_VERSION");
        if (base != null && base.matches("https?://[A-Za-z0-9._~:/%+-]+")) b.append("RESEARCHZOSHO_DOWNLOAD_BASE=").append(quoted(base)).append(' ');
        if (ver != null && ver.matches("[0-9][0-9A-Za-z.+-]*")) b.append("RESEARCHZOSHO_VERSION=").append(quoted(ver)).append(' ');
        return b.toString();
    }

    /** The version the installer's hook names, for {@code update now <version>} there; null when none. */
    static String hookVersion() { String v = ENV.apply("RESEARCHZOSHO_VERSION"); return v != null && v.matches("\\d+\\.\\d+\\.\\d+") ? v : null; }

    /** The options of {@code helpers install --on}: what the install there takes, and whether to ask. */
    record Options(String target, List<Helper> only, boolean onlyGiven, boolean yes, String gpu, boolean cpu, boolean separate, boolean move, Boolean cpuFallback) {
        Options(String target, List<Helper> only, boolean onlyGiven, boolean yes, String gpu, boolean cpu) { this(target, only, onlyGiven, yes, gpu, cpu, false, false, null); }
        Options(String target, List<Helper> only, boolean onlyGiven, boolean yes, String gpu, boolean cpu, boolean separate, boolean move) { this(target, only, onlyGiven, yes, gpu, cpu, separate, move, null); }
    }

    /** {@code helpers install --on <target>}: the steps as the class says; the exit code. */
    static int installOn(Options o, BufferedReader in, PrintStream out) throws IOException, InterruptedException {
        String target = o.target();
        if (!validTarget(target)) { out.println("Not an ssh target: " + target + ". Name it as user@host or host (letters, digits, dot, dash and underscore)."); return 2; }
        if (o.gpu() != null && !HelperCards.cardWord(o.gpu())) { out.println("--gpu takes a card's index or UUID as nvidia-smi lists them, or --cpu: " + o.gpu() + " is neither."); return 2; }
        String host = hostOf(target);
        String base = "http://" + host + ":" + Helpers.PORT;   // the first set's; a separate set's port is read from that machine after its install
        // the model server beside them: when the library's main model is served from that machine, its card is the helpers' card there
        int beside = 0;
        ModelEntries.Entry main = ModelEntries.defaultEntry();
        String mainHost = Helpers.hostOf(main.drive());
        if (!main.hosted() && mainHost != null && (mainHost.equalsIgnoreCase(host) || mainHost.equalsIgnoreCase(target.replaceFirst("^.*@", "")))) beside = portOf(main.drive());
        String needs = KNOWS_INSTALL;   // the release the program there has to be, by what is asked of it
        if (o.cpuFallback() != null && Version.compare(KNOWS_FALLBACK, needs) > 0) needs = KNOWS_FALLBACK;
        if (o.only().contains(Helper.COMIC) && Version.compare(KNOWS_COMIC, needs) > 0) needs = KNOWS_COMIC;
        // a. what, and how big, and the one question
        List<String> what = new ArrayList<>();
        for (Helper h : o.only()) if (HelpersInstall.SET_UP.contains(h)) what.add(h.said + " (" + HelpersInstall.size(h, !o.cpu(), false) + ")");
        out.println("On " + target + ": " + (what.isEmpty() ? "" : "what is missing of " + String.join("; ", what) + "; ")
                + "the embeddings server and speech-to-text when they run there; served with a secret " + (o.separate() ? "as a set of its own, on a port of its own from " + (Helpers.PORT + 1) + " (or the set already on that card)," : "on port " + Helpers.PORT) + " by a user service"
                + (o.cpu() ? ", on the processor" : o.gpu() != null ? ", on card " + o.gpu() : beside > 0 ? ", on the card of the model server at port " + beside + " there" : "")
                + ". ResearchZosho itself is installed there first when it is missing (the published installer), or updated when it is older than " + needs + ".");
        if (!o.yes()) {
            out.print("Set them up on " + target + " now? [y/N] ");
            out.flush();
            String a = in.readLine();
            if (a == null || !a.strip().toLowerCase(Locale.ROOT).startsWith("y")) { out.println("Nothing was done on " + target + "."); return 1; }
        }
        // b. the program there
        out.println("Asking " + target + " which ResearchZosho it has…");
        Ran v = SSH.run(ssh(target, PATH_THERE + "researchzosho --version"), null);
        if (v.code() == 255) return cannotLogIn(target, host, v.out(), out);
        String have = versionIn(v.out());
        if (have == null) {
            out.println("ResearchZosho is not installed on " + target + ": installing it with the published installer (" + INSTALLER + ").");
            Ran r = SSH.run(ssh(target, PATH_THERE + "curl -fsSL " + INSTALLER + " | " + hooks() + "sh"), line -> out.println("  " + target + ": " + line));
            if (r.code() == 255) return cannotLogIn(target, host, r.out(), out);
            if (r.code() != 0) { out.println("The installer did not finish on " + target + " (exit " + r.code() + "); its lines are above."); return 1; }
        } else if (Version.compare(have, needs) < 0) {
            out.println(target + " has ResearchZosho " + have + ", older than " + needs + ": updating it there (researchzosho update now).");
            String ver = hookVersion();
            Ran r = SSH.run(ssh(target, PATH_THERE + hooks() + "researchzosho update now" + (ver == null ? "" : " " + ver)), line -> out.println("  " + target + ": " + line));
            if (r.code() == 255) return cannotLogIn(target, host, r.out(), out);
            if (r.code() != 0) { out.println("The update did not finish on " + target + " (exit " + r.code() + "); its lines are above."); return 1; }
        } else out.println(target + " has ResearchZosho " + have + ".");
        if (have == null || Version.compare(have, needs) < 0) {
            Ran again = SSH.run(ssh(target, PATH_THERE + "researchzosho --version"), null);
            String now = versionIn(again.out());
            if (now == null || Version.compare(now, needs) < 0) { out.println("ResearchZosho on " + target + " is still " + (now == null ? "not there" : now) + ", so the helpers cannot be set up there from here."); return 1; }
            out.println(target + " has ResearchZosho " + now + " now.");
        }
        // c. the install there, its lines passed through
        String select = (o.cpu() ? " --cpu" : o.gpu() != null ? " --gpu " + o.gpu() : "") + (beside > 0 && !o.cpu() && o.gpu() == null ? " --beside-port " + beside : "") + (o.separate() ? " --separate" : "");
        String flags = " --yes" + select + (o.move() ? " --move" : "") + (o.cpuFallback() == null ? "" : o.cpuFallback() ? " --cpu-fallback" : " --no-cpu-fallback") + (o.onlyGiven() ? " --only " + HelpersCli.flagNames(o.only()) : "");
        out.println("Setting the helpers up on " + target + " (researchzosho helpers install" + flags + "):");
        Ran r = SSH.run(ssh(target, PATH_THERE + "researchzosho helpers install" + flags), line -> out.println("  " + target + ": " + hidden(line)));
        if (r.code() == 255) return cannotLogIn(target, host, r.out(), out);
        if (r.code() != 0) { out.println("The helpers were not set up on " + target + " (exit " + r.code() + "); its lines are above."); return 1; }
        // d. the secret, read back and never printed here; the settings; the health from here; the status
        Ran s = SSH.run(ssh(target, PATH_THERE + "researchzosho helpers secret"), null);
        String secret = s.code() == 0 ? s.out().strip() : "";
        if (!secret.matches("[0-9A-Za-z._~+/=-]{16,256}")) { out.println("The secret could not be read back from " + target + " (exit " + s.code() + "): researchzosho helpers secret there prints it, and researchzosho settings set helpers_secret <it> sets it here."); return 1; }
        // the port of the set this library got there: the same flags select it (a separate set has a port of its own from 8218)
        Ran p = SSH.run(ssh(target, PATH_THERE + "researchzosho helpers port" + select), null);
        String port = p.code() == 0 ? p.out().strip() : "";
        if (port.matches("\\d{2,5}")) base = "http://" + host + ":" + port;
        else out.println("The port of the set could not be read back from " + target + " (exit " + p.code() + "), so the first set's, " + Helpers.PORT + ", is set here; researchzosho helpers port there says the right one.");
        Config.set(Helpers.HELPERS, base);
        Config.set(Helpers.SECRET_NAME, secret);
        Helpers.forget();
        out.println("Set here: helpers " + base + ", and its secret (helpers_secret), read from " + target + " and not shown.");
        Map<String, String> served = HEALTH.ask(base, secret, ANSWER_WAIT);
        out.println(served == null ? base + " does not answer from here yet: a firewall between the two machines, or the models still loading there; researchzosho helpers says how it stands."
                : base + " answers from here with the secret: it serves " + String.join(", ", served.keySet()) + ".");
        out.println(STATUS.get());
        return 0;
    }

    /** The helpers' status, printed last; a test stands it in, so that no machine of the test's own making is asked. */
    static volatile Supplier<String> STATUS = HelpersCli::statusSaid;

    /** A line of the install there, with the secret its last line gives left out: it is read back and set here, never printed. */
    static String hidden(String line) {
        return line.replaceAll("(researchzosho settings set helpers_secret )\\S+", "$1<the secret, read back and set here>");
    }

    /** ssh could not log in with the person's keys alone: one line, and the two commands to run by hand. */
    static int cannotLogIn(String target, String host, String said, PrintStream out) {
        String why = said == null ? "" : said.strip().replaceAll("\\s+", " ");
        if (why.length() > 160) why = why.substring(0, 160) + "…";
        out.println("ssh could not log in to " + target + " with your keys alone" + (why.isEmpty() ? "" : " (" + why + ")") + ". Run these two by hand instead:");
        out.println("  on " + host + ":  curl -fsSL " + INSTALLER + " | sh && researchzosho helpers install");
        out.println("  here:  researchzosho settings set helpers http://" + host + ":" + Helpers.PORT + " && researchzosho settings set helpers_secret <what researchzosho helpers secret prints on " + host + ">");
        return 1;
    }

    /** The machine a target names: what ssh's own configuration resolves it to (ssh -G, which connects nowhere), else its host part. */
    static String hostOf(String target) throws IOException, InterruptedException {
        String part = target.replaceFirst("^.*@", "");
        Ran g = SSH.run(List.of("ssh", "-G", target), null);
        if (g.code() == 0) for (String line : g.out().split("\\R")) {
            if (!line.startsWith("hostname ")) continue;
            String h = line.substring("hostname ".length()).strip();
            if (h.matches("[A-Za-z0-9._:-]+")) return h.contains(":") ? "[" + h + "]" : h;
        }
        return part;
    }

    private static final Pattern VERSION = Pattern.compile("^researchzosho (\\d+\\.\\d+\\.\\d+)");

    /** The version {@code researchzosho --version} printed, or null when the program did not answer. */
    static String versionIn(String out) {
        if (out == null) return null;
        for (String line : out.split("\\R")) { Matcher m = VERSION.matcher(line.strip()); if (m.find()) return m.group(1); }
        return null;
    }

    static int portOf(String address) {
        try { URI u = URI.create(address); return u.getPort() > 0 ? u.getPort() : u.getScheme() != null && u.getScheme().equalsIgnoreCase("https") ? 443 : 80; } catch (IllegalArgumentException e) { return 0; }
    }
}
