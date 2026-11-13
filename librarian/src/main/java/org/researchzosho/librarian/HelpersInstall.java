package org.researchzosho.librarian;

import org.researchzosho.Config;
import org.researchzosho.librarian.Helpers.Helper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code researchzosho helpers install} (0.7.4): one command on the machine that serves the heavy helpers, for what a person did in five
 * steps. It sets up what is missing of MetricX-24, LaBSE and the likeness helper (their own install paths, each one's size said first,
 * one question before anything is fetched), serves an embeddings server and speech-to-text when they already answer here, keeps a
 * secret ({@code <home>/helpers.secret}), installs and starts the helpers' user service on every address of the machine, port 8217,
 * asks its own health, and ends with what it serves and the one line to paste on the library's machine. Run again, it adds what is
 * missing and keeps the secret and the service. {@code helpers uninstall} removes the service and leaves the models; {@code helpers
 * secret} prints the kept secret alone on a line.
 *
 * <p>The service is a systemd user unit, on Linux. macOS and Windows have none for the helpers yet: the program's own LaunchAgent and
 * logon task are written for {@code serve} (its label, its task name, its crew hour, its log), and a second one of each would be a
 * second copy of that code; there the helpers are set up and the secret kept, and the command says to run {@code helpers serve}.
 */
final class HelpersInstall {
    private HelpersInstall() { }

    /** The heavy helpers this command sets up itself, in the order it says them; the embeddings server and speech-to-text it serves when they answer. */
    static final List<Helper> SET_UP = List.of(Helper.METRICX, Helper.LABSE, Helper.LIKENESS);

    /** What the command reads of this machine and does to it; a test stands it in. */
    interface Machine {
        boolean installed(Helper h);
        /** Where the one LaBSE container of this machine sits: "card 2", "all cards", "the processor", or "" when that cannot be read. */
        String labseOn();
        /** Whether MetricX-24's torch sees the card {@code card} chose. */
        boolean torchSeesCard(HelperCards.Choice card);
        /** Sets {@code h} up on {@code card}'s choice, saying each step; "ready", or "!" and what stopped it. */
        String install(Helper h, HelperCards.Choice card, PrintStream out);
        /** Whether the embeddings server or speech-to-text answers on this machine. */
        boolean answers(Helper h);
        /** The usable room on the disk under the library's home, or -1 when it cannot be read. */
        long freeBytes();
        Service.Os os();
        /** The launcher the service runs. */
        String launcher();
        /** The front's own health on this machine, asked with the secret, waited for up to {@code wait}: what it serves, or null. */
        Map<String, String> ownHealth(int port, String secret, Duration wait);
    }

    static volatile Machine MACHINE = new Machine() {
        @Override public boolean installed(Helper h) {
            return switch (h) {
                case METRICX -> TranslationScores.metricxInstalled();
                case LABSE -> !TranslationScores.labseState().equals("absent") || Embed.answers("http://127.0.0.1:" + TranslationScores.LABSE_PORT);
                case LIKENESS -> LikenessHelper.installed();
                default -> answers(h);
            };
        }
        @Override public boolean torchSeesCard(HelperCards.Choice card) { return TranslationScores.TORCH_SEES.test(TranslationScores.metricxPython(), card); }
        @Override public String labseOn() { return TranslationScores.labseOn(); }
        @Override public String install(Helper h, HelperCards.Choice card, PrintStream out) {
            return switch (h) {
                case METRICX -> TranslationScores.installMetricx(card, out::println);
                case LABSE -> { String r = TranslationScores.startLabse(card.dockerGpus(), card.card() == null ? null : card.card().uuid(), out::println); yield r.startsWith("!") ? r : "ready"; }
                case LIKENESS -> LikenessHelper.INSTALL.get();
                default -> "!" + h.said + " is not set up by this command";
            };
        }
        @Override public boolean answers(Helper h) {
            String a = switch (h) {
                case EMBED -> "http://127.0.0.1:" + Embed.DEFAULT_PORT;
                case WHISPER -> "http://127.0.0.1:" + Video.WHISPER_PORT;
                case LABSE -> "http://127.0.0.1:" + TranslationScores.LABSE_PORT;
                default -> null;
            };
            if (a == null) return false;
            if (h == Helper.EMBED || h == Helper.LABSE) return Embed.answers(a);
            try { return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(a + "/health")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
            catch (Exception e) { return false; }
        }
        @Override public long freeBytes() { return TranslationScores.freeBytes(); }
        @Override public Service.Os os() { return Service.os(); }
        @Override public String launcher() { return Service.resolveExec(null); }
        @Override public Map<String, String> ownHealth(int port, String secret, Duration wait) { return health("http://127.0.0.1:" + port, secret, wait); }
    };

    /** A helpers server's health, asked with {@code secret} until it answers or {@code wait} passes: what it serves, or null. */
    static Map<String, String> health(String base, String secret, Duration wait) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        long until = System.currentTimeMillis() + wait.toMillis();
        do {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(5)).GET();
                if (secret != null && !secret.isEmpty()) b.header("Authorization", "Bearer " + secret);
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) { Map<String, String> m = Helpers.helpersIn(r.body()); if (m != null) return m; }
            } catch (IOException ignored) { /* not up yet */ }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            if (System.currentTimeMillis() >= until) return null;
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        } while (true);
    }

    /** How long the install waits for its service to answer: the first start loads the models. */
    static volatile Duration FIRST_START = Duration.ofMinutes(15);

    /** What one helper's setup fetches, as the install commands say it. */
    static String size(Helper h) { return size(h, false); }

    /** The same, MetricX-24's with torch's CUDA build when {@code cuda}. */
    static String size(Helper h, boolean cuda) {
        return switch (h) {
            case METRICX -> TranslationScores.METRICX_MODEL + ", " + TranslationScores.metricxSize(cuda);
            case LABSE -> TranslationScores.LABSE_MODEL + ", " + TranslationScores.LABSE_SIZE + ", fetched once by the embeddings image through Docker";
            case LIKENESS -> LikenessHelper.PICTURE_MODEL + " and " + LikenessHelper.FACE_MODEL + ", " + LikenessHelper.SIZES;
            default -> "";
        };
    }

    /** About what a helper's setup takes on the disk at most, for the check and the question: MetricX-24's by its torch build, LaBSE's model, the likeness helper's packages and weights. */
    static long bytes(Helper h, boolean cuda) { return switch (h) { case METRICX -> TranslationScores.metricxBytes(cuda); case LABSE -> (long) (1.9 * (1L << 30)); case LIKENESS -> 2L << 30; default -> 0; }; }

    /** Bytes as a person reads them: one decimal place of a gigabyte. */
    static String gb(long bytes) { long tenths = Math.round(bytes / (double) (1L << 30) * 10); return tenths % 10 == 0 ? String.valueOf(tenths / 10) : tenths / 10 + "." + tenths % 10; }

    /** The secret's file on this machine. */
    static Path secretFile() { return Config.home().resolve("helpers.secret"); }

    /** The kept secret, or "" when none is kept. */
    static String keptSecret() {
        try { return Files.isRegularFile(secretFile()) ? Files.readString(secretFile(), StandardCharsets.UTF_8).strip() : ""; } catch (IOException e) { return ""; }
    }

    /** The options of {@code helpers install} on this machine. */
    record Options(List<Helper> only, boolean onlyGiven, boolean yes, String gpu, boolean cpu, int besidePort, int port, boolean separate, boolean move) {
        Options(List<Helper> only, boolean onlyGiven, boolean yes, String gpu, boolean cpu, int besidePort, int port) { this(only, onlyGiven, yes, gpu, cpu, besidePort, port, false, false); }
    }

    /** The set a call works on: its unit and port, its card, whether it runs already, and whether it is the first set (port 8217, the one found by itself). */
    record Target(String unit, int port, HelperCards.Choice card, boolean exists, boolean first) { }

    /** The card a set's unit names, as a choice, with the line that says it runs there already; null when its unit names none. */
    static HelperCards.Choice choiceOf(HelperSets.HelperSet set) {
        if (set.card().equals("cpu")) return new HelperCards.Choice(null, true, "The helpers run on the processor, where they run already (port " + set.port() + ").", false);
        HelperCards.Card c = HelperCards.named(set.card(), HelperCards.cards());
        return c == null ? null : new HelperCards.Choice(c, false, "The helpers go on " + c.said() + ", where they run already (port " + set.port() + ").", false);
    }

    /** Where a choice puts the helpers, for a line: "card 3" or "the processor". */
    static String where(HelperCards.Choice c) { return c.cpu() ? "the processor" : "card " + c.card().index(); }

    /**
     * Which set this call works on, each step said ({@code out}); null when it stops (nothing changed). The default does not change: one
     * set, which a second library joins. With {@code separate}: the set on the card the usual rules give this call (the first set's kept
     * card not deciding), found when one runs there already, made beside the others when not, the first set when there is none. Without
     * it: a card named by --gpu, --cpu or --beside-port where a set runs already is that set; a card named by --gpu or --cpu where none
     * runs moves the first set there, which is said first (every library it serves is served from there from then on) and asked, or with
     * --yes stops unless --move is given; anything else is the first set, on the card its unit names.
     */
    static Target target(Options o, BufferedReader in, PrintStream out) throws IOException {
        List<HelperSets.HelperSet> sets = MACHINE.os() == Service.Os.linux ? HelperSets.sets() : List.of();
        HelperCards.Undecided undecided = o.yes() ? HelperCards.Undecided.STOP : HelperCards.Undecided.ASK;
        if (o.separate()) {
            HelperCards.Choice card = HelperCards.choose(o.gpu(), o.cpu(), o.besidePort(), false, undecided, in, out);
            if (!card.said().isEmpty()) out.println(card.said());
            if (card.stop()) return null;
            HelperSets.HelperSet there = HelperSets.on(sets, card);
            if (there != null) { out.println("A set of helpers runs on " + there.on() + " already (port " + there.port() + ", " + there.unit() + "): it is the one, as before."); return new Target(there.unit(), there.port(), card, true, there.first()); }
            if (sets.isEmpty()) { out.println("No set of helpers runs on this machine yet, so this one is the first: port " + o.port() + ", the one a library finds by itself."); return new Target(HelpersCli.UNIT, o.port(), card, false, true); }
            if (card.kept().isEmpty()) { out.println("nvidia-smi lists no card here, so a separate set has no card of its own: --cpu makes one on the processor."); return null; }
            return separate(sets, card, out);
        }
        if (!sets.isEmpty()) {
            HelperCards.Choice named = null;
            boolean explicit = o.cpu() || o.gpu() != null;
            if (explicit) named = HelperCards.choose(o.gpu(), o.cpu(), 0, false, HelperCards.Undecided.STOP, in, out);
            else if (o.besidePort() > 0) {
                List<HelperCards.Card> cards = HelperCards.cards();
                var beside = HelperCards.besidePort(o.besidePort(), cards);
                if (beside.size() == 1) { HelperCards.Card c = beside.iterator().next(); named = new HelperCards.Choice(c, false, "The helpers go on " + c.said() + ", the card of the model server at port " + o.besidePort() + ".", false); }
            }
            if (named != null && named.stop()) { out.println(named.said()); return null; }
            HelperSets.HelperSet there = named == null ? null : HelperSets.on(sets, named);
            if (there != null) {
                out.println(named.said());
                out.println("A set of helpers runs on " + there.on() + " already (port " + there.port() + ", " + there.unit() + "): it is the one, as before.");
                return new Target(there.unit(), there.port(), named, true, there.first());
            }
            HelperSets.HelperSet first = HelperSets.first(sets);
            if (named != null && explicit && first != null) {
                out.println(named.said());
                String said = "The helpers here run on " + first.on() + " (port " + first.port() + ") for every library that uses them, and " + where(named) + " has none: moving them there serves every one of those libraries from "
                        + where(named) + " from then on; --separate keeps them and adds a second set on " + where(named) + ".";
                if (o.move()) { out.println(said); out.println("Moving them to " + where(named) + " (--move)."); return new Target(first.unit(), first.port(), named, true, true); }
                if (o.yes()) { out.println(said + " Nothing was changed: --move moves them, --separate adds a set."); return null; }
                out.println(said);
                out.print("Move them to " + where(named) + ", make a separate set there, or nothing? [move/separate/N] ");
                out.flush();
                String a = in.readLine();
                a = a == null ? "" : a.strip().toLowerCase();
                if (a.startsWith("m")) { out.println("Moving them to " + where(named) + "."); return new Target(first.unit(), first.port(), named, true, true); }
                if (a.startsWith("s")) return separate(sets, named, out);
                out.println("Nothing was changed.");
                return null;
            }
            if (first != null) {
                HelperCards.Choice card = choiceOf(first);
                if (card != null) { out.println(card.said()); return new Target(first.unit(), first.port(), card, true, true); }
                card = HelperCards.choose(null, false, o.besidePort(), true, undecided, in, out);
                if (!card.said().isEmpty()) out.println(card.said());
                return card.stop() ? null : new Target(first.unit(), first.port(), card, true, true);
            }
        }
        HelperCards.Choice card = HelperCards.choose(o.gpu(), o.cpu(), o.besidePort(), undecided, in, out);
        if (!card.said().isEmpty()) out.println(card.said());
        return card.stop() ? null : new Target(HelpersCli.UNIT, o.port(), card, false, true);
    }

    /** A new set on {@code card}, beside the sets there are: the next free port, a unit of its own; said. */
    static Target separate(List<HelperSets.HelperSet> sets, HelperCards.Choice card, PrintStream out) {
        int port = HelperSets.nextPort(sets);
        String unit = HelperSets.unitFor(card);
        List<String> others = new ArrayList<>(); for (HelperSets.HelperSet s : sets) others.add("the one on " + s.on() + " (port " + s.port() + ")");
        out.println("A separate set on " + where(card) + ": port " + port + ", unit " + unit + ", beside " + String.join(" and ", others) + ".");
        return new Target(unit, port, card, false, false);
    }

    /** {@code helpers install}: the whole of it, as the class says; the exit code. */
    static int install(Options o, BufferedReader in, PrintStream out) throws IOException, InterruptedException {
        Machine m = MACHINE;
        if (!Helpers.localAllowed()) { out.println("This machine is set to run no heavy helper (local_helpers off), so none is set up or served here: researchzosho settings set local_helpers on allows it."); return 1; }
        // the set and its card first: what MetricX-24 fetches depends on the card
        out.println("The heavy helpers on this machine, to serve the libraries of other machines:");
        int setsBefore = m.os() == Service.Os.linux ? HelperSets.sets().size() : 0;
        Target t = target(o, in, out);
        if (t == null) return 1;
        HelperCards.Choice card = t.card();
        boolean several = setsBefore + (t.exists() ? 0 : 1) > 1;
        boolean cuda = TranslationScores.cudaTorchFor(card);
        // what is there, what is missing (or a torch that cannot see the card), what is served as it answers
        List<Helper> missing = new ArrayList<>(), serves = new ArrayList<>();
        Map<Helper, Long> need = new LinkedHashMap<>();
        for (Helper h : o.only()) {
            if (SET_UP.contains(h)) {
                if (h == Helper.LIKENESS && !LikenessHelper.on()) { out.println("  the likeness helper: off here (" + LikenessHelper.SETTING + "=off), left out"); continue; }
                if (!m.installed(h)) { out.println("  " + h.said + ": to set up (" + size(h, cuda) + ")"); missing.add(h); need.put(h, bytes(h, cuda)); }
                else if (h == Helper.METRICX && cuda && !m.torchSeesCard(card)) {
                    out.println("  MetricX-24: set up, but its torch cannot see a card, so it gets " + TranslationScores.torchSwapSize());
                    missing.add(h); need.put(h, TranslationScores.TORCH_SWAP_BYTES);
                }
                else if (h == Helper.LABSE && several) { String on = m.labseOn(); out.println("  LaBSE: one container on this machine" + (on.isEmpty() ? "" : ", on " + on) + "; every set uses it"); serves.add(h); }
                else if (h == Helper.LIKENESS && several) { out.println("  the likeness helper: set up already; it runs on the processor in each set"); serves.add(h); }
                else { out.println("  " + h.said + ": set up already"); serves.add(h); }
            } else if (m.answers(h)) { out.println("  " + h.said + ": answers here, so it is served too"); serves.add(h); }
            else out.println("  " + h.said + ": not running here, so it is left out" + (h == Helper.EMBED ? " (researchzosho embed start starts one)" : " (researchzosho video install sets it up)"));
        }
        if (!missing.isEmpty()) {
            long all = 0; for (long b : need.values()) all += b;
            long free = m.freeBytes();
            if (free >= 0 && free < all) { out.println("The disk has " + gb(free) + " GB free and these take about " + gb(all) + " GB at most, so nothing was fetched."); return 1; }
            if (!o.yes()) {
                List<String> parts = new ArrayList<>(); for (var e : need.entrySet()) parts.add(e.getKey().said + " (about " + gb(e.getValue()) + " GB at most)");
                out.print("Fetch and set up " + (parts.size() == 1 ? parts.get(0) : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1))
                        + " now" + (parts.size() > 1 ? ", about " + gb(all) + " GB at most in all" : "") + "? [y/N] ");
                out.flush();
                String a = in.readLine();
                if (a == null || !a.strip().toLowerCase().startsWith("y")) { out.println("Nothing was fetched or changed."); return 1; }
            }
            for (Helper h : missing) {
                out.println("Setting up " + h.said + "; this takes minutes.");
                String r = m.install(h, card, out);
                if (r.equals("ready")) { out.println("  " + h.said + " is ready."); serves.add(h); }
                else out.println("  " + h.said + " was not set up: " + r.replaceFirst("^!", "") + ". The others are served without it.");
            }
        }
        if (serves.isEmpty()) { out.println("Nothing is set up to serve, so no service was installed."); return 1; }
        // the secret, kept; the card, kept
        String secret = keptSecret();
        if (secret.isEmpty()) { secret = HelperServer.newSecret(); HelpersCli.keepSecret(secretFile(), secret); out.println("A secret was made and kept in " + secretFile() + ", which only you can read."); }
        else out.println("The secret is kept in " + secretFile() + ", as before.");
        if (t.first() && !card.kept().isEmpty() && !card.kept().equals(Config.get(Helpers.GPU_NAME, "").strip())) { Config.set(Helpers.GPU_NAME, card.kept()); out.println("The card is kept for the service and the next install (helpers_gpu " + card.kept() + ")."); }
        // the service
        if (m.os() != Service.Os.linux) {
            out.println("This system has no service for the helpers yet. To serve them, run this and leave it running:");
            out.println("researchzosho helpers serve --bind 0.0.0.0 --port " + t.port() + " --secret-file " + secretFile() + card.flag() + (o.onlyGiven() ? " --only " + HelpersCli.flagNames(o.only()) : ""));
            endLines(out, secret, t);
            return 0;
        }
        int rc = installUnit(t.unit(), m.launcher(), t.port(), card.flag(), o.onlyGiven() ? o.only() : null, out);
        if (rc != 0) return rc;
        // asked at once: a service that runs already answers, and the wait is said only when it does not
        Map<String, String> served = m.ownHealth(t.port(), secret, Duration.ZERO);
        if (served == null) {
            out.println("Waiting for it to answer (its first start loads the models, which takes minutes)…");
            served = m.ownHealth(t.port(), secret, FIRST_START);
        }
        if (served == null) out.println("It does not answer yet. researchzosho helpers service status says how it stands, and journalctl --user -u " + t.unit() + " what it said.");
        else {
            List<String> parts = new ArrayList<>(); for (var e : served.entrySet()) parts.add(e.getKey() + (e.getValue().isBlank() ? "" : " (" + e.getValue() + ")"));
            out.println("It serves " + String.join(", ", parts) + ".");
        }
        endLines(out, secret, t);
        return 0;
    }

    /** The last lines: that the library finds the first set, or names a separate one, and the one line for the secret, complete. */
    static void endLines(PrintStream out, String secret, Target t) {
        if (t.first()) out.println("A library whose model is served from this machine finds these helpers by itself. On the library's machine, this sets the secret:");
        else out.println("A library uses this set when it names it: researchzosho settings set helpers http://<this machine>:" + t.port() + " (helpers install --on <this machine> --separate does that for it). On the library's machine, this sets the secret:");
        out.println("researchzosho settings set helpers_secret " + secret);
    }

    /** The helpers' unit file: the first set's, or a set's by its unit. */
    static Path unitFile() { return unitFile(HelpersCli.UNIT); }
    static Path unitFile(String unit) { return Path.of(System.getProperty("user.home")).resolve(".config").resolve("systemd").resolve("user").resolve(unit + ".service"); }

    /**
     * The helpers' systemd user unit, as text: {@code helpers serve} on every address, port {@code port}, the kept secret, the card's
     * flag, the helpers named ({@code only}, or all of them, each served when it is set up), Java found as the program's own service
     * finds it (Service's fallback line and passthrough environment), and a stop by systemd counted as a stop, not a failure.
     */
    static String unitText(String exec, int port, Path secretFile, String cardFlag, List<Helper> only, String javaHome) {
        return "[Unit]\nDescription=ResearchZosho's heavy helpers, served to other machines\nAfter=network-online.target\n\n[Service]\nType=simple\n"
                + "ExecStart=/bin/sh -c '" + Service.UNIX_JAVA_FALLBACK.replace("$", "$$") + "' " + exec + " " + Service.verbFor(exec) + "helpers serve --bind 0.0.0.0 --port " + port
                + " --secret-file " + secretFile + cardFlag + (only == null ? "" : " --only " + HelpersCli.flagNames(only)) + "\n"
                + "Restart=on-failure\nRestartSec=10\nSuccessExitStatus=143\n"
                + Service.passthroughEnv("Environment=\"", "=", "\"\n", Service::systemdQuoted)
                + "Environment=\"" + Service.SERVICE_JAVA_HOME + "=" + Service.systemdQuoted(javaHome) + "\"\n"
                + "\n[Install]\nWantedBy=default.target\n";
    }

    /** Writes the first set's unit, as {@link #installUnit(String, String, int, String, List, PrintStream)} does. */
    static int installUnit(String exec, int port, String cardFlag, List<Helper> only, PrintStream out) throws IOException, InterruptedException {
        return installUnit(HelpersCli.UNIT, exec, port, cardFlag, only, out);
    }

    /** Writes a set's unit (when it says something new), and starts it, or restarts it onto the new text; one line says which. */
    static int installUnit(String name, String exec, int port, String cardFlag, List<Helper> only, PrintStream out) throws IOException, InterruptedException {
        Path unit = unitFile(name);
        String text = unitText(exec, port, secretFile(), cardFlag, only, Service.installedJavaHome());
        boolean had = Files.isRegularFile(unit), same = had && Files.readString(unit, StandardCharsets.UTF_8).equals(text);
        PrintStream quiet = new PrintStream(PrintStream.nullOutputStream());
        if (same && Service.exec(List.of("systemctl", "--user", "is-active", name), quiet) == 0) {
            out.println("The service " + name + " is installed and running already, as before.");
            return 0;
        }
        Files.createDirectories(unit.getParent());
        Files.writeString(unit, text, StandardCharsets.UTF_8);
        int rc = Service.exec(List.of("systemctl", "--user", "daemon-reload"), quiet);
        if (rc == 0) rc = Service.exec(List.of("systemctl", "--user", "enable", "--now", name), quiet);
        if (rc == 0 && had) rc = Service.exec(List.of("systemctl", "--user", "restart", name), quiet);
        if (rc != 0) { out.println("The unit was written to " + unit + " but systemctl could not start it (exit " + rc + ")."); return rc; }
        out.println(had && !same ? "The service " + name + " was installed with other flags; it was rewritten and restarted on port " + port + "."
                : had ? "The service " + name + " was stopped; it is started again on port " + port + "."
                : "Installed and started the service " + name + ": it serves on port " + port + " of every address of this machine, also after a restart (loginctl enable-linger keeps it running while you are logged out).");
        return 0;
    }

    /**
     * {@code helpers uninstall [--gpu N | --cpu]}: every set's service removed, each said, or the one on that card; the models, the
     * containers and the secret stay.
     */
    static int uninstall(String card, PrintStream out) throws IOException, InterruptedException {
        String stays = " The models stay as they are: researchzosho translate scorers status and researchzosho images likeness status say what is set up, and the secret stays in " + secretFile() + ".";
        List<HelperSets.HelperSet> sets = MACHINE.os() == Service.Os.linux ? HelperSets.sets() : List.of();
        if (card != null) sets = sets.stream().filter(x -> x.card().equals(card)).toList();
        if (sets.isEmpty()) { out.println((card == null ? "No helpers service is installed here." : "No set of helpers runs on " + (card.equals("cpu") ? "the processor" : "card " + card) + " here.") + stays); return 0; }
        PrintStream quiet = new PrintStream(PrintStream.nullOutputStream());
        for (HelperSets.HelperSet x : sets) {
            Service.exec(List.of("systemctl", "--user", "disable", "--now", x.unit()), quiet);
            Files.deleteIfExists(x.file());
            out.println("Removed the set on " + x.on() + " (port " + x.port() + ", " + x.unit() + "): its helpers are no longer served from this machine.");
        }
        Service.exec(List.of("systemctl", "--user", "daemon-reload"), quiet);
        out.println(stays.strip());
        return 0;
    }

    static int uninstall(PrintStream out) throws IOException, InterruptedException { return uninstall(null, out); }

    /** {@code helpers service status}: each set, its card, its port, whether it runs and what it serves. */
    static int status(PrintStream out) throws IOException, InterruptedException {
        List<HelperSets.HelperSet> sets = MACHINE.os() == Service.Os.linux ? HelperSets.sets() : List.of();
        if (sets.isEmpty()) { out.println("No helpers service is installed here. researchzosho helpers install serves them from this machine."); return 0; }
        PrintStream quiet = new PrintStream(PrintStream.nullOutputStream());
        String secret = keptSecret();
        out.println(sets.size() == 1 ? "One set of helpers on this machine:" : sets.size() + " sets of helpers on this machine:");
        for (HelperSets.HelperSet x : sets) {
            boolean runs = Service.exec(List.of("systemctl", "--user", "is-active", x.unit()), quiet) == 0;
            Map<String, String> served = runs ? MACHINE.ownHealth(x.port(), secret, Duration.ZERO) : null;
            List<String> parts = new ArrayList<>(); if (served != null) for (var e : served.entrySet()) parts.add(e.getKey() + (e.getValue().isBlank() ? "" : " (" + e.getValue() + ")"));
            out.println("  " + x.unit() + ": " + x.on() + ", port " + x.port() + (x.first() ? " (the one a library finds by itself)" : "") + ", "
                    + (!runs ? "not running" : served == null ? "running, and not answering yet" : "running; it serves " + String.join(", ", parts)) + ".");
        }
        return 0;
    }

    /**
     * {@code helpers port [--gpu N | --cpu | --beside-port P] [--separate]}: the port of the set those flags select, as {@code helpers
     * install} with them would work on it, alone on a line; exit 1 with one line when no such set runs.
     */
    static int port(Options o, PrintStream out, PrintStream err) throws IOException {
        Options asked = new Options(o.only(), o.onlyGiven(), true, o.gpu(), o.cpu(), o.besidePort(), o.port(), o.separate(), false);
        Target t = target(asked, null, new PrintStream(PrintStream.nullOutputStream()));
        if (t == null || !t.exists()) { err.println("No set of helpers here answers for these flags: researchzosho helpers install sets one up."); return 1; }
        out.println(t.port());
        return 0;
    }

    /** {@code helpers secret}: the kept secret alone on a line, for the library's machine (or an install over ssh) to read. */
    static int secret(PrintStream out, PrintStream err) {
        String s = keptSecret();
        if (s.isEmpty()) { err.println("No secret is kept here yet: researchzosho helpers install makes one."); return 1; }
        out.println(s);
        return 0;
    }

    /** The helpers {@code --only} names, kept in the order of {@link Helper}. */
    static List<Helper> all() { return new ArrayList<>(List.of(Helper.values())); }
}
