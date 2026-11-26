package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import org.researchzosho.Config;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code researchzosho phone}: the library from a phone (2026-11-25). The phone door ({@link PhoneDoor}) behind the machine's Tailscale, on
 * port 8443 of its address (over the tailnet, or public through Funnel), or behind a relay of the person's own; pairing by a QR code;
 * the paired phones, each with its monthly cap on paid models. Only this command, on the library's machine, changes any of it: the
 * settings it writes are not in the settings registry, so no page, chat or program can open the door or point it elsewhere.
 */
final class PhoneCli {
    private PhoneCli() { }

    /** The port of the machine's Tailscale address the phone lives on: one of the three Funnel allows, beside share's 443, so neither moves the other. */
    static final int TAILSCALE_PORT = 8443;

    static final String USAGE = "usage: researchzosho phone [status | on [--public] [--door N] | on --own-relay --address URL [--sender HEADER] [--door N] | off | pair [name] [--cap DOLLARS|none] [--no-wait] | list | cap <name> <DOLLARS|none> | remove <name>]";

    static Share.Outcome run(LibraryStore store, List<String> args, PrintStream out) throws IOException {
        Hosted.machine();   // a library under an account service is reached through the service alone
        String op = args.isEmpty() ? "status" : args.get(0);
        List<String> rest = args.isEmpty() ? List.of() : args.subList(1, args.size());
        return switch (op) {
            case "status" -> new Share.Outcome(0, status());
            case "on" -> on(rest, out);
            case "off" -> off();
            case "pair" -> pair(rest, out);
            case "list" -> new Share.Outcome(0, list(store));
            case "cap" -> cap(store, rest);
            case "remove" -> remove(rest);
            default -> new Share.Outcome(2, USAGE);
        };
    }

    // ---------------------------------------------------------------- on, off, status

    static Share.Outcome on(List<String> args, PrintStream out) throws IOException {
        boolean pub = false, own = false; String address = null, sender = null; int door = 0;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--public" -> pub = true;
                case "--tailnet", "--private" -> pub = false;
                case "--own-relay" -> own = true;
                case "--address" -> { if (i + 1 >= args.size()) return new Share.Outcome(2, USAGE); address = args.get(++i); }
                case "--sender" -> { if (i + 1 >= args.size()) return new Share.Outcome(2, USAGE); sender = args.get(++i); }
                case "--door" -> { try { door = Integer.parseInt(args.get(++i)); } catch (RuntimeException e) { return new Share.Outcome(2, "--door takes a port number."); } }
                default -> { return new Share.Outcome(2, USAGE); }
            }
        }
        if (door <= 0) door = PhoneDoor.configuredPort();
        if (own) {
            String a = address == null ? "" : address.strip().replaceAll("/+$", "");
            if (!a.matches("(?i)https://[^/\\s]+")) return new Share.Outcome(2, "--own-relay needs the address your relay gives the door, an https address with no path: --address https://phone.example.org");
            Config.set(PhoneDoor.PORT, String.valueOf(door));
            Config.set(PhoneDoor.ADDRESS, a);
            Config.set(PhoneDoor.SENDER, sender == null || sender.isBlank() ? "" : sender.strip());
            Config.set(PhoneDoor.KIND, "own");
            return new Share.Outcome(0, "The phone door is on at 127.0.0.1:" + door + ". Point your relay at it; phones reach it at " + a + ".\n"
                    + "  The sender of a request is read from " + (sender == null || sender.isBlank() ? PhoneDoor.CLOUDFLARE_SENDER + " (a Cloudflare tunnel's header; --sender names another)" : sender.strip()) + ".\n"
                    + "  The service opens the door within half a minute" + (Share.serviceRunning.getAsBoolean() ? "." : "; no service runs here now (researchzosho service install).") + "\n"
                    + "  To pair a phone: researchzosho phone pair");
        }
        String ts = Share.which.apply("tailscale");
        if (ts == null) return new Share.Outcome(1, "Tailscale is not on this machine. Install it (https://tailscale.com/download), log in with tailscale up, then researchzosho phone on again. Or put a relay of your own in front of the door: researchzosho phone on --own-relay --address https://…");
        String dns;
        try {
            Video.Result st = Share.runner.run(List.of(ts, "status", "--json"), Duration.ofSeconds(30));
            dns = st.code() == 0 ? Share.dnsName(st.out()) : null;
        } catch (Exception e) { return new Share.Outcome(1, "Tailscale could not be asked: " + e.getMessage()); }
        if (dns == null) return new Share.Outcome(1, "Tailscale is not logged in on this machine. Log in with tailscale up, then researchzosho phone on again.");
        JsonNode sc = TailscaleServe.read(ts);
        if (sc == null) return new Share.Outcome(1, "Tailscale could not say what it serves (tailscale serve status --json), so nothing was changed. An older Tailscale cannot; update it, then researchzosho phone on again.");
        List<String> others = TailscaleServe.others(sc, door, TAILSCALE_PORT);
        if (!others.isEmpty()) return new Share.Outcome(1, "Tailscale on this machine already serves something on port " + TAILSCALE_PORT + " that is not the phone door:\n  " + String.join("\n  ", others)
                + "\nNothing was changed. The phone door lives on that port; take that off first (tailscale serve status shows it), or use a relay of your own: researchzosho phone on --own-relay --address https://…");
        Video.Result r;
        try { r = Share.runner.run(TailscaleServe.onCommand(ts, TAILSCALE_PORT, door, pub), Duration.ofMinutes(1)); }
        catch (Exception e) { return new Share.Outcome(1, "tailscale could not be run: " + e.getMessage()); }
        if (r.code() != 0) return new Share.Outcome(1, Share.explain(r.out(), pub).replace("researchzosho share", "researchzosho phone on"));
        String a = "https://" + dns + ":" + TAILSCALE_PORT;
        Config.set(PhoneDoor.PORT, String.valueOf(door));
        Config.set(PhoneDoor.ADDRESS, a);
        Config.set(PhoneDoor.KIND, pub ? "public" : "tailnet");
        return new Share.Outcome(0, (pub ? "The phone door is public at " + a + " (Tailscale Funnel): anyone can reach it, and only a paired phone gets in."
                        : "The phone door is on your tailnet at " + a + ": a phone with Tailscale on it reaches it, and only a paired phone gets in.") + "\n"
                + "  It serves the phone page and nothing else of the library; the door is 127.0.0.1:" + door + (Share.serviceRunning.getAsBoolean() ? ", opened by the service within half a minute." : "; no service runs here now (researchzosho service install), and the door opens with it.") + "\n"
                + (pub ? "  To keep it to your tailnet: researchzosho phone on\n" : "  To reach it without Tailscale on the phone: researchzosho phone on --public\n")
                + "  To pair a phone: researchzosho phone pair\n"
                + "  To close it: researchzosho phone off (the paired phones stay paired)");
    }

    static Share.Outcome off() throws IOException {
        String kind = PhoneDoor.kind();
        StringBuilder text = new StringBuilder();
        if (kind.equals("tailnet") || kind.equals("public")) {
            String ts = Share.which.apply("tailscale");
            if (ts != null) {
                JsonNode sc = TailscaleServe.read(ts);
                try {
                    if (sc == null) text.append("Tailscale could not say what it serves, so nothing was taken off at the relay; tailscale serve status shows it.\n");
                    else if (TailscaleServe.ours(sc, PhoneDoor.configuredPort(), TAILSCALE_PORT) != null) {
                        Video.Result r = Share.runner.run(TailscaleServe.offCommand(ts, TAILSCALE_PORT), Duration.ofSeconds(30));
                        if (r.code() != 0) text.append("tailscale said: ").append(r.out().strip()).append('\n');
                    } else text.append("Tailscale does not serve the phone door on port ").append(TAILSCALE_PORT).append(" now, so nothing was taken off at the relay.\n");
                } catch (Exception e) { text.append("tailscale could not be run: ").append(e.getMessage()).append('\n'); }
            }
        }
        Config.set(PhoneDoor.KIND, "off");
        text.append(kind.equals("off") ? "The phone door was not on." : "The phone door is off: the relay no longer forwards to it, and the service closes it within half a minute. The paired phones stay paired; researchzosho phone on opens it again at the same address.");
        return new Share.Outcome(0, text.toString());
    }

    /** The phone line for {@code status}, and the head of {@code phone status}. */
    static String statusLine() {
        String k = PhoneDoor.kind(), a = PhoneDoor.address();
        return switch (k) {
            case "tailnet" -> "phone: on your tailnet at " + a;
            case "public" -> "phone: public at " + a + " (Tailscale Funnel)";
            case "own" -> "phone: behind your own relay at " + a;
            default -> "phone: off (researchzosho phone on puts the phone page behind Tailscale)";
        };
    }

    static String status() throws IOException {
        StringBuilder b = new StringBuilder("  ").append(statusLine()).append('\n');
        if (!PhoneDoor.kind().equals("off")) b.append("  door: 127.0.0.1:").append(PhoneDoor.configuredPort()).append(Share.serviceRunning.getAsBoolean() ? " (the service is running)" : " (no service runs here: researchzosho service install)").append('\n');
        int n = Phones.list().size();
        b.append("  paired phones: ").append(n).append(n == 0 ? " (researchzosho phone pair)" : " (researchzosho phone list)").append('\n');
        return b.toString();
    }

    // ---------------------------------------------------------------- pairing

    static Share.Outcome pair(List<String> args, PrintStream out) throws IOException {
        String name = ""; Double cap = Phones.DEFAULT_CAP; boolean waitFor = Interaction.interactive();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--cap" -> { if (i + 1 >= args.size() || (cap = Phones.capOf(args.get(++i))) == null) return new Share.Outcome(2, "--cap takes dollars a month (10, 2.50), 0 for no paid model, or none for no limit."); }
                case "--no-wait" -> waitFor = false;
                case "--wait" -> waitFor = true;
                default -> { if (a.startsWith("--")) return new Share.Outcome(2, USAGE); name = name.isEmpty() ? a : name + " " + a; }
            }
        }
        if (PhoneDoor.kind().equals("off") || PhoneDoor.address().isEmpty()) return new Share.Outcome(1, "The phone door is off, so a phone could not reach the pairing link. Turn it on first: researchzosho phone on");
        Phones.Made m = Phones.pairingCode(name, cap);
        String link = PhoneDoor.address() + "/m/pair?c=" + m.code();
        StringBuilder b = new StringBuilder();
        b.append("Scan this with the phone's camera to pair it as \"").append(m.name()).append("\":\n\n").append(terminalQr(link)).append('\n');
        b.append("  ").append(link).append("\n");
        b.append("  The link works once, for ").append(Phones.CODE_MS / 60_000).append(" minutes. Its monthly cap on paid models: ").append(Phones.capSaid(m.cap(), Chrome.ENGLISH)).append(" (researchzosho phone cap changes it).\n");
        b.append("  Pair in the browser first; then the page shows how to put it on the Home Screen.");
        if (!Share.serviceRunning.getAsBoolean()) b.append("\n  No service runs here now: the phone door opens with the service (researchzosho service install).");
        if (!waitFor) return new Share.Outcome(0, b.toString());
        out.println(b);
        out.println("Waiting for the phone (Ctrl-C stops waiting; the link stays good until it runs out)…");
        long until = m.until();
        while (System.currentTimeMillis() < until + 1_000) {
            String used = Phones.usedBy(m.code());
            if (used == null) break;
            if (!used.isEmpty()) {
                Phones.Phone p = Phones.get(used);
                return new Share.Outcome(0, "Paired: " + (p == null ? m.name() : p.name()) + ". If that was not your phone, remove it: researchzosho phone remove \"" + (p == null ? m.name() : p.name()) + "\"");
            }
            try { Thread.sleep(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return new Share.Outcome(1, "The pairing link ran out unused. researchzosho phone pair makes a new one.");
    }

    /**
     * A QR code drawn with the terminal's half blocks, two rows of modules a line: a light module is a block, a dark one the terminal's own
     * background, with a quiet zone of light around it (as qrencode -t UTF8 draws it for a dark terminal). The link is printed under it
     * for a terminal whose colours turn it round.
     */
    static String terminalQr(String text) {
        boolean[][] m = QrCode.modules(text, -1);
        int n = m.length, q = 2;
        StringBuilder b = new StringBuilder();
        for (int y = -q; y < n + q; y += 2) {
            b.append("  ");
            for (int x = -q; x < n + q; x++) {
                boolean top = !dark(m, x, y), bottom = !dark(m, x, y + 1);   // light modules are drawn
                b.append(top && bottom ? '█' : top ? '▀' : bottom ? '▄' : ' ');
            }
            b.append('\n');
        }
        return b.toString();
    }

    private static boolean dark(boolean[][] m, int x, int y) { return y >= 0 && x >= 0 && y < m.length && x < m.length && m[y][x]; }

    // ---------------------------------------------------------------- the list

    static String list(LibraryStore store) throws IOException {
        List<Phones.Phone> all = Phones.list();
        if (all.isEmpty()) return "No phone is paired. researchzosho phone pair shows the code to scan.";
        StringBuilder b = new StringBuilder();
        for (Phones.Phone p : all) {
            double spent = Spend.phoneMonth(store, p.id());
            b.append("  ").append(p.name()).append("  paired ").append(Phones.shown(p.paired())).append(", last used ").append(Phones.shown(p.used()))
             .append(", ").append(p.keys().size() == 1 ? "1 browser" : p.keys().size() + " browsers")
             .append("; this month ").append(Spend.dollars(spent)).append(" on paid models, cap ").append(Phones.capSaid(p.cap(), Chrome.ENGLISH)).append('\n');
        }
        b.append("  To remove one: researchzosho phone remove <name>. To change a cap: researchzosho phone cap <name> <dollars|none>.");
        return b.toString();
    }

    static Share.Outcome cap(LibraryStore store, List<String> args) throws IOException {
        if (args.size() < 2) return new Share.Outcome(2, "usage: researchzosho phone cap <name> <dollars a month | 0 for no paid model | none for no limit>");
        Double dollars = Phones.capOf(args.get(args.size() - 1));
        if (dollars == null) return new Share.Outcome(2, "A cap is dollars a month (10, 2.50), 0 for no paid model, or none (or -1) for no limit.");
        String name = String.join(" ", args.subList(0, args.size() - 1));
        Phones.Phone p = Phones.cap(name, dollars);
        if (p == null) return new Share.Outcome(1, "No phone is called " + name + ". researchzosho phone list shows them.");
        return new Share.Outcome(0, p.name() + ": the cap on paid models is now " + Phones.capSaid(p.cap(), Chrome.ENGLISH) + "; this month so far " + Spend.dollars(Spend.phoneMonth(store, p.id())) + "."
                + (p.cap() < 0 ? " Nothing from this phone is refused or stopped for spending; a model entry's own cap still holds." : ""));
    }

    static Share.Outcome remove(List<String> args) throws IOException {
        if (args.isEmpty()) return new Share.Outcome(2, "usage: researchzosho phone remove <name>");
        String name = String.join(" ", args);
        Phones.Phone p = Phones.remove(name);
        return p == null ? new Share.Outcome(1, "No phone is called " + name + ". researchzosho phone list shows them.")
                : new Share.Outcome(0, "Removed: " + p.name() + ". Its next request is not paired; to use it again, pair it again.");
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
    static List<String> list(String... xs) { return new ArrayList<>(List.of(xs)); }
}
