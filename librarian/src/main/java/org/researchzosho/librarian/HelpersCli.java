package org.researchzosho.librarian;

import org.researchzosho.Config;
import org.researchzosho.librarian.Helpers.Helper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code researchzosho helpers}: where the heavy helpers run for this library, and the serving of them to other machines (0.7.4).
 * <ul>
 *   <li>{@code helpers} or {@code helpers status}: each helper, where it runs for this library, and how it answers;</li>
 *   <li>{@code helpers serve [--bind ADDR] [--port N] [--secret WORDS | --secret-file FILE] [--only metricx,labse,…]}: serves the
 *       helpers this machine can carry, in the foreground, until it is stopped;</li>
 *   <li>{@code helpers service install|uninstall|status [the same flags]}: the same as a user service that starts with the session
 *       (a systemd user unit; on Linux, where a machine that serves the heavy helpers usually is).</li>
 * </ul>
 * {@code translate scorers serve} is {@code helpers serve --only metricx,labse}; {@code images likeness serve} is {@code --only likeness}.
 */
final class HelpersCli {
    private HelpersCli() { }

    static final String UNIT = "researchzosho-helpers";
    static final String USAGE = "usage: researchzosho helpers [status]   ·   helpers install [--on <user@host>] [--gpu <index> | --cpu] [--beside-port N] [--separate | --move] [--only metricx,labse,likeness,whisper,embed] [--yes]\n"
            + "       researchzosho helpers uninstall [--gpu <index> | --cpu]   ·   helpers port [--gpu <index> | --cpu | --beside-port N] [--separate]   ·   helpers secret   ·   helpers serve [--bind ADDR] [--port N] [--secret-file FILE] [--gpu <index> | --cpu] [--only …]\n"
            + "       researchzosho helpers service install|uninstall|status [the same flags]   (a machine that serves the heavy helpers to the libraries of other machines)";

    static int run(String[] args, int from) throws Exception {
        String op = args.length > from ? args[from].toLowerCase(Locale.ROOT) : "status";
        switch (op) {
            case "status" -> { System.out.println(statusSaid()); return 0; }
            case "install" -> { return install(args, from + 1); }
            case "uninstall" -> {
                Flags f;
                try { f = flags(args, from + 1, HelperServer.all()); } catch (IOException | NumberFormatException e) { System.err.println(e.getMessage()); return 2; }
                return HelpersInstall.uninstall(f.cpu() ? "cpu" : f.gpu(), System.out);
            }
            case "port" -> {
                Flags f;
                try { f = flags(args, from + 1, HelperServer.all()); } catch (IOException | NumberFormatException e) { System.err.println(e.getMessage()); return 2; }
                return HelpersInstall.port(new HelpersInstall.Options(f.only(), f.onlyGiven(), true, f.gpu(), f.cpu(), f.besidePort(), f.port(), f.separate(), false), System.out, System.err);
            }
            case "secret" -> { return HelpersInstall.secret(System.out, System.err); }
            case "serve" -> { return serve(args, from + 1, HelperServer.all()); }
            case "service" -> { return service(args, from + 1); }
            default -> { System.err.println(USAGE); return 2; }
        }
    }

    /** Each helper, where it runs for this library, in a line of its own (in English, as the scorers' and the likeness helper's status are). */
    static String statusSaid() {
        StringBuilder b = new StringBuilder(Helpers.localAllowed() ? "This machine may run the heavy helpers itself (local_helpers on)." : "This machine runs no heavy helper itself (local_helpers off): a helper no address names is left out, and the work says so.");
        String base = Helpers.base();
        if (base != null) b.append("\nThe machine that serves the helpers: ").append(base).append(Helpers.foundInUse() ? ", found on the machine that serves the model." : ".");
        String needs = Helpers.needsSecret();
        if (needs != null) b.append("\n").append(needsSecretSaid(needs));
        if ("off".equals(Helpers.explicitBase())) b.append("\nNo machine is asked for its helpers (helpers off).");
        for (Helper h : Helper.values()) {
            String at = Helpers.address(h), line;
            if (Helpers.off(h)) line = "off (" + h.setting + "=off)";
            else if (at != null) {
                String hl = Helpers.health(h, at);
                // what the helpers machine's front says of it, when it is served there: its line names the card by the index that machine
                // lists it under; a server whose own health says nothing (Text Embeddings Inference) gets that line too
                if (hl != null && base != null && at.equals(base + "/" + h.path)) { var serves = Helpers.SERVES.apply(base); String front = serves == null ? "" : serves.getOrDefault(h.path, ""); if (!front.isBlank() && !front.equals("answering")) hl = front; }
                line = hl == null ? "at " + at + ", which does not answer now" : "at " + at + " (" + (hl.isBlank() ? "answering" : hl) + ")";
            }
            else line = Helpers.localAllowed() ? "on this machine" : "not run here, and no address names it";
            b.append("\n  ").append(h.said).append(": ").append(line);
        }
        return b.toString();
    }

    /** The one line said where a helpers server on the model's machine needs this library's secret, with the line that sets it. */
    static String needsSecretSaid(String base) {
        return "A helpers server answers on the machine that serves the model (" + base + ") and needs its secret: researchzosho helpers secret prints it there, and researchzosho settings set helpers_secret <the secret> sets it here.";
    }

    /**
     * The flags; {@code secretFile} is the file the secret was read from, and {@code secretMade} whether this run made the secret in it;
     * {@code gpu} and {@code cpu} the card as given (null and false: not given); {@code besidePort} the port of the model server whose
     * card the helpers share; {@code on} an ssh target for {@code helpers install --on}; {@code onlyGiven} whether --only was given.
     */
    record Flags(String bind, int port, String secret, List<Helper> only, Path secretFile, boolean secretMade, String gpu, boolean cpu, int besidePort, boolean yes, String on, boolean onlyGiven, boolean separate, boolean move) {
        Flags(String bind, int port, String secret, List<Helper> only, Path secretFile, boolean secretMade) { this(bind, port, secret, only, secretFile, secretMade, null, false, 0, false, null, false, false, false); }
    }

    /** Keeps a secret in a file only its owner reads. */
    static void keepSecret(Path file, String secret) throws IOException {
        if (file.toAbsolutePath().getParent() != null) Files.createDirectories(file.toAbsolutePath().getParent());
        Files.deleteIfExists(file);
        try { Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch (UnsupportedOperationException e) { Files.createFile(file); }   // no POSIX permissions (Windows): the folder's own protection stands
        Files.writeString(file, secret + "\n", StandardCharsets.UTF_8);
    }

    static Flags flags(String[] args, int from, List<Helper> dflt) throws IOException {
        String bind = "127.0.0.1", secret = Config.get(Helpers.SECRET_NAME, "").strip(); int port = Helpers.PORT; List<Helper> only = dflt;
        Path secretFile = null; boolean made = false, cpu = false, yes = false, onlyGiven = false, separate = false, move = false; String gpu = null, on = null; int beside = 0;
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case "--bind" -> bind = LibrarianCli.flagValue(args, i++);
                case "--port" -> port = Integer.parseInt(LibrarianCli.flagValue(args, i++));
                case "--gpu" -> { gpu = LibrarianCli.flagValue(args, i++).strip(); if (!HelperCards.cardWord(gpu)) throw new IOException("--gpu takes a card's index or UUID as nvidia-smi lists them: " + gpu + " is neither"); }
                case "--cpu" -> cpu = true;
                case "--beside-port" -> beside = Integer.parseInt(LibrarianCli.flagValue(args, i++));
                case "--yes", "-y" -> yes = true;
                case "--separate" -> separate = true;   // a set of helpers of its own, on this call's card, beside the one there is
                case "--move" -> move = true;           // without --separate, with --yes: move the one set to the card named
                case "--on" -> on = LibrarianCli.flagValue(args, i++).strip();
                case "--secret" -> secret = LibrarianCli.flagValue(args, i++).strip();
                case "--secret-file" -> {   // the secret in a file; a file that is not there yet gets a new secret, so the same command works the first time and after
                    secretFile = Path.of(LibrarianCli.flagValue(args, i++));
                    if (Files.isRegularFile(secretFile)) secret = Files.readString(secretFile, StandardCharsets.UTF_8).strip();
                    else { secret = HelperServer.newSecret(); keepSecret(secretFile, secret); made = true; }
                }
                case "--only" -> { onlyGiven = true; only = new ArrayList<>(); for (String n : LibrarianCli.flagValue(args, i++).split(",")) { Helper h = Helper.of(n.strip()); if (h == null) throw new IOException("no helper is called " + n.strip() + ": metricx, labse, likeness, whisper or embed"); only.add(h); } }
                default -> throw new IOException("not a flag here: " + args[i]);
            }
        }
        if (cpu && gpu != null) throw new IOException("--gpu and --cpu say two things: give one");
        if (separate && move) throw new IOException("--separate and --move say two things: give one");
        return new Flags(bind, port, secret, only, secretFile, made, gpu, cpu, beside, yes, on, onlyGiven, separate, move);
    }

    /** {@code helpers install}: on this machine, or with {@code --on <target>} on another one over ssh. */
    static int install(String[] args, int from) throws Exception {
        Flags f;
        try { f = flags(args, from, HelperServer.all()); } catch (IOException | NumberFormatException e) { System.err.println(e.getMessage()); System.err.println(USAGE); return 2; }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (f.on() != null) return HelpersRemote.installOn(new HelpersRemote.Options(f.on(), f.only(), f.onlyGiven(), f.yes(), f.gpu(), f.cpu(), f.separate(), f.move()), in, System.out);
        return HelpersInstall.install(new HelpersInstall.Options(f.only(), f.onlyGiven(), f.yes(), f.gpu(), f.cpu(), f.besidePort(), f.port(), f.separate(), f.move()), in, System.out);
    }

    /** Serves the helpers in the foreground until the process is stopped; what it serves and the two settings a library elsewhere needs, said first. */
    static int serve(String[] args, int from, List<Helper> dflt) throws Exception {
        Flags f;
        try { f = flags(args, from, dflt); } catch (IOException | NumberFormatException e) { System.err.println(e.getMessage()); System.err.println(USAGE); return 2; }
        String secret = f.secret();
        boolean generated = false;
        if (secret.isEmpty() && !HelperServer.loopback(f.bind())) { secret = HelperServer.newSecret(); generated = true; }
        HelperCards.Choice card = HelperCards.choose(f.gpu(), f.cpu(), f.besidePort(), HelperCards.Undecided.AS_BEFORE, null, System.out);
        if (!card.said().isEmpty()) System.out.println(card.said());
        if (card.stop()) return 1;
        List<Process> started = new ArrayList<>();
        var backends = HelperServer.startBackends(f.only(), started, line -> System.out.println("  " + line), card);
        if (backends.isEmpty()) { System.out.println("Nothing to serve: none of " + names(f.only()) + " is set up on this machine."); for (Process p : started) p.destroyForcibly(); return 1; }
        HelperServer s;
        try { s = HelperServer.serve(f.bind(), f.port(), secret, backends, started); }
        catch (IOException e) { System.err.println(e.getMessage()); for (Process p : started) p.destroyForcibly(); return 1; }
        String host = HelperServer.loopback(f.bind()) ? f.bind() : f.bind().equals("0.0.0.0") || f.bind().equals("::") ? InetAddress.getLocalHost().getHostName() : f.bind();
        String at = "http://" + host + ":" + s.port();
        System.out.println("Serving " + String.join(", ", backends.keySet()) + " at " + at + " (pid " + ProcessHandle.current().pid() + "; Ctrl-C stops it).");
        System.out.println("On the library that uses them: researchzosho settings set helpers " + at + (secret.isEmpty() ? "" : "  and  researchzosho settings set helpers_secret " + (generated ? secret : f.secretFile() != null ? "<the secret in " + f.secretFile() + ">" : "<the secret>")));
        if (generated) System.out.println("  (a secret was made for this run; --secret-file <file> keeps one across runs)");
        if (f.secretMade()) System.out.println("  (a secret was made and kept in " + f.secretFile() + ", which only you can read)");
        Runtime.getRuntime().addShutdownHook(new Thread(s::close));
        Thread.currentThread().join();
        return 0;
    }

    static String names(List<Helper> hs) { List<String> n = new ArrayList<>(); for (Helper h : hs) n.add(h.path); return String.join(", ", n); }

    /** The helpers as {@code --only} takes them, one word: "metricx,labse" (with a space, a unit's command line split it in two). */
    static String flagNames(List<Helper> hs) { List<String> n = new ArrayList<>(); for (Helper h : hs) n.add(h.path); return String.join(",", n); }

    /** The user service that runs {@code helpers serve} with these flags (Linux). */
    static int service(String[] args, int from) throws Exception {
        String op = args.length > from ? args[from] : "status";
        if (Service.os() != Service.Os.linux) { System.out.println("The helpers' service is a systemd user unit, on Linux; on this system run researchzosho helpers serve when the machine starts."); return 1; }
        Path unit = Path.of(System.getProperty("user.home")).resolve(".config/systemd/user/" + UNIT + ".service");
        switch (op) {
            case "install" -> {
                Flags f;
                try { f = flags(args, from + 1, HelperServer.all()); } catch (IOException | NumberFormatException e) { System.err.println(e.getMessage()); return 2; }
                HelperCards.Choice card = HelperCards.choose(f.gpu(), f.cpu(), f.besidePort(), HelperCards.Undecided.AS_BEFORE, null, System.out);
                if (!card.said().isEmpty()) System.out.println(card.said());
                if (card.stop()) return 1;
                if (!card.kept().isEmpty()) Config.set(Helpers.GPU_NAME, card.kept());
                // the service listens on every address, so it always has a secret: the one given, else the one kept, else a new one
                String secret = !f.secret().isEmpty() ? f.secret() : HelpersInstall.keptSecret();
                if (secret.isEmpty()) secret = HelperServer.newSecret();
                if (!secret.equals(HelpersInstall.keptSecret())) keepSecret(HelpersInstall.secretFile(), secret);
                int rc = HelpersInstall.installUnit(Service.resolveExec(null), f.port(), card.flag(), f.onlyGiven() ? f.only() : null, System.out);
                if (rc == 0) System.out.println("Its secret is kept in " + HelpersInstall.secretFile() + "; on the library that uses them: researchzosho settings set helpers_secret <that secret> (researchzosho helpers secret prints it).");
                return rc;
            }
            case "uninstall" -> { return HelpersInstall.uninstall(System.out); }
            case "status" -> { return HelpersInstall.status(System.out); }   // every set: its card, its port, whether it runs, what it serves
            default -> { System.err.println(USAGE); return 2; }
        }
    }
}
