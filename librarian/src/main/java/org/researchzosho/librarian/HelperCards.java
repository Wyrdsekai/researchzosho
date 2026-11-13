package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The card the heavy helpers run on, on a machine that serves them (0.7.4). {@code --gpu <index or UUID>} names it and {@code --cpu}
 * keeps them off every card; the choice is kept ({@link Helpers#GPU_NAME}) and the helpers' service carries it. With neither, the
 * card is decided in this order, and one line says which rule chose it:
 * <ol>
 *   <li>the card of the model server beside them ({@code --beside-port <port>}): the process listening on that port and its
 *       descendants, and a card on which they hold memory (nvidia-smi's compute apps), else CUDA_VISIBLE_DEVICES in the environment
 *       of one of them, when it names one card, else {@code --gpus device=…} in a descendant's command line (a llama-swap proxy
 *       with its model unloaded starts {@code docker run … --gpus "device=GPU-…"}); one card found by all the ways that answer
 *       decides, two different cards or none do not;</li>
 *   <li>the card this program's own model server here was installed with, when it names one;</li>
 *   <li>the only card of the machine;</li>
 *   <li>else a question: each card's index, name and free memory, and the processor; with {@code --yes}, a stop that says to name one.</li>
 * </ol>
 * A Python helper started on a chosen card runs with CUDA_VISIBLE_DEVICES set to it (its own {@code cuda} is then that card); a
 * container started for LaBSE gets {@code --gpus device=<card>} in place of {@code --gpus all}.
 */
final class HelperCards {
    private HelperCards() { }

    /** A card as nvidia-smi lists it. */
    record Card(String index, String uuid, String name, long totalMb, long freeMb) {
        String said() { return "card " + index + " (" + name + ", " + gb(freeMb) + " GB free)"; }
        String brief() { return "card " + index + ", " + name; }
        static String gb(long mb) { long tenths = Math.round(mb / 102.4); return tenths % 10 == 0 ? String.valueOf(tenths / 10) : tenths / 10 + "." + tenths % 10; }
    }

    /**
     * Where the helpers run: on {@code card}, on the processor ({@code cpu}), or as before any choice existed (neither: one card or
     * none on the machine, nothing to choose). {@code said}: the one line that says it and which rule chose it; {@code stop}: no
     * rule decided and no question could be asked, {@code said} saying to name one.
     */
    record Choice(Card card, boolean cpu, String said, boolean stop) {
        static final Choice AS_BEFORE = new Choice(null, false, "", false);
        /** The flag the helpers' service and a remote install carry: {@code --gpu <index>}, {@code --cpu}, or none. */
        String flag() { return cpu ? " --cpu" : card != null ? " --gpu " + card.index() : ""; }
        /** What the setting keeps, or "" for nothing chosen. */
        String kept() { return cpu ? "cpu" : card != null ? card.index() : ""; }
        /**
         * CUDA_VISIBLE_DEVICES for a Python helper started now: the card's UUID, "" for none, or null to leave the environment as it is.
         * The UUID and never the index: CUDA numbers the fastest card first unless told otherwise, so on a machine with three cards of one
         * kind and a faster fourth, CUDA's 2 is not nvidia-smi's 2. The index is what a person reads and types; it is turned into the card's
         * UUID at the moment the card is handed to something that runs.
         */
        String visible() { return cpu ? "" : card != null ? card.uuid() : null; }
        /** Docker's {@code --gpus} value for a container started now: "device=<the card's UUID>", "all", or null for none (the processor). */
        String dockerGpus() { return cpu ? null : card != null ? "device=" + card.uuid() : "all"; }
    }

    /** What the rules read of the machine; a test stands it in with made-up readings. */
    interface Probe {
        /** A command's output, or null when it could not run or failed (nvidia-smi, ss). */
        String run(List<String> cmd);
        /** The processes below {@code pid}, children first. */
        List<Long> descendants(long pid);
        /** A process's environment, NUL-separated as /proc gives it, or "" when it cannot be read. */
        String environ(long pid);
        /** A process's command line, word by word, or none. */
        List<String> cmdline(long pid);
    }

    static volatile Probe PROBE = new Probe() {
        @Override public String run(List<String> cmd) {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return p.waitFor() == 0 ? out : null;
            } catch (IOException e) { return null; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        @Override public List<Long> descendants(long pid) { return ProcessHandle.of(pid).map(h -> h.descendants().map(ProcessHandle::pid).toList()).orElse(List.of()); }
        @Override public String environ(long pid) {
            try { return new String(Files.readAllBytes(Path.of("/proc", String.valueOf(pid), "environ")), StandardCharsets.UTF_8); } catch (IOException | RuntimeException e) { return ""; }
        }
        @Override public List<String> cmdline(long pid) {
            try { String c = new String(Files.readAllBytes(Path.of("/proc", String.valueOf(pid), "cmdline")), StandardCharsets.UTF_8); return c.isEmpty() ? List.of() : List.of(c.split("\0")); }
            catch (IOException | RuntimeException e) { return List.of(); }
        }
    };

    /** The machine's NVIDIA cards, as nvidia-smi lists them; none when it is not there. */
    static List<Card> cards() {
        String out = PROBE.run(List.of("nvidia-smi", "--query-gpu=index,uuid,name,memory.total,memory.free", "--format=csv,noheader,nounits"));
        List<Card> cards = new ArrayList<>();
        if (out == null) return cards;
        for (String line : out.split("\\R")) {
            String[] f = line.split("\\s*,\\s*");
            if (f.length < 5 || !f[0].strip().matches("\\d+") || !f[3].strip().matches("\\d+") || !f[4].strip().matches("\\d+")) continue;
            cards.add(new Card(f[0].strip(), f[1].strip(), f[2].strip(), Long.parseLong(f[3].strip()), Long.parseLong(f[4].strip())));
        }
        return cards;
    }

    /** The card an index or a UUID names, among {@code cards}; null for none. */
    static Card named(String id, List<Card> cards) {
        if (id == null) return null;
        String v = id.strip().replaceAll("^['\"]|['\"]$", "");
        for (Card c : cards) if (c.index().equals(v) || c.uuid().equalsIgnoreCase(v)) return c;
        return null;
    }

    /** Whether a choice the person typed or a setting holds is a card's name: an index or a UUID, nothing a shell would read. */
    static boolean cardWord(String v) { return v != null && v.matches("\\d{1,2}|GPU-[0-9A-Fa-f-]{8,64}|MIG-[0-9A-Za-z/-]{8,80}"); }

    // ---- rule 1: the card of the model server beside them ----

    /** The cards the model server listening on {@code port} holds or is started on, by the three ways; empty when none answers. */
    static Set<Card> besidePort(int port, List<Card> cards) {
        Set<Card> found = new LinkedHashSet<>();
        List<Long> pids = new ArrayList<>();
        for (long p : listeners(port)) { if (!pids.contains(p)) pids.add(p); for (long d : PROBE.descendants(p)) if (!pids.contains(d)) pids.add(d); }
        if (pids.isEmpty()) return found;
        // a card on which they hold memory
        String apps = PROBE.run(List.of("nvidia-smi", "--query-compute-apps=pid,gpu_uuid", "--format=csv,noheader"));
        if (apps != null) for (String line : apps.split("\\R")) {
            String[] f = line.split("\\s*,\\s*");
            if (f.length >= 2 && f[0].strip().matches("\\d+") && pids.contains(Long.parseLong(f[0].strip()))) { Card c = named(f[1], cards); if (c != null) found.add(c); }
        }
        // CUDA_VISIBLE_DEVICES in the environment of one of them, when it names one card
        for (long p : pids) for (String kv : PROBE.environ(p).split("\0")) {
            if (!kv.startsWith("CUDA_VISIBLE_DEVICES=")) continue;
            String v = kv.substring("CUDA_VISIBLE_DEVICES=".length()).strip();
            if (!v.isEmpty() && !v.contains(",")) { Card c = named(v, cards); if (c != null) found.add(c); }
        }
        // --gpus device=… in a descendant's command line (docker run), with or without quotes
        for (long p : pids) {
            List<String> words = PROBE.cmdline(p);
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i), v = null;
                if (w.equals("--gpus") && i + 1 < words.size()) v = words.get(i + 1);
                else if (w.startsWith("--gpus=")) v = w.substring("--gpus=".length());
                if (v == null) continue;
                v = v.strip().replaceAll("^['\"]+|['\"]+$", "");
                if (!v.startsWith("device=")) continue;
                String d = v.substring("device=".length()).replaceAll("^['\"]+|['\"]+$", "");
                if (!d.contains(",")) { Card c = named(d, cards); if (c != null) found.add(c); }
            }
        }
        return found;
    }

    /** The processes listening on {@code port}, as ss lists them with their pids. */
    static List<Long> listeners(int port) {
        String out = PROBE.run(List.of("ss", "-Hltnp"));
        List<Long> pids = new ArrayList<>();
        if (out == null) return pids;
        Pattern pid = Pattern.compile("pid=(\\d+)");
        for (String line : out.split("\\R")) {
            String[] f = line.strip().split("\\s+");
            if (f.length < 4 || !f[3].endsWith(":" + port)) continue;
            Matcher m = pid.matcher(line);
            while (m.find()) { long p = Long.parseLong(m.group(1)); if (!pids.contains(p)) pids.add(p); }
        }
        return pids;
    }

    // ---- rule 2: the card this program's model server was installed with ----

    /** The one card this program's own model server here was installed with, or null (none installed, all cards, or several). */
    static String modelServerCard() {
        try {
            if (!Files.exists(ModelServer.dir().resolve("plan.properties")) && !Files.exists(ModelServer.dir().resolve("config.yaml"))) return null;
            String g = ModelServer.readPlan(ModelServer.dir()).getOrDefault("gpus", "all").strip();
            return g.equals("all") || g.isEmpty() || g.contains(",") ? null : g;
        } catch (IOException | RuntimeException e) { return null; }
    }

    // ---- the choice ----

    /** What a command does when no rule decides on a machine with several cards: asks (helpers install), stops (--yes), or starts as before (helpers serve). */
    enum Undecided { ASK, STOP, AS_BEFORE }

    static Choice choose(String gpu, boolean cpu, int besidePort, boolean ask, BufferedReader in, PrintStream out) throws IOException {
        return choose(gpu, cpu, besidePort, ask ? Undecided.ASK : Undecided.STOP, in, out);
    }

    /**
     * The choice for a command: {@code gpu} and {@code cpu} as the person gave them (null and false: not given); the kept one next;
     * then the rules; then {@code undecided}.
     */
    static Choice choose(String gpu, boolean cpu, int besidePort, Undecided undecided, BufferedReader in, PrintStream out) throws IOException {
        return choose(gpu, cpu, besidePort, true, undecided, in, out);
    }

    /**
     * The same; {@code useKept} false for a separate set of helpers, whose card the first set's kept card does not decide. When the
     * model server beside them was asked and no way read its card, the line says why, in place of the general one: nothing listens on
     * that port here, or the server there runs no model right now (a proxy whose model is not loaded has no child holding a card), or
     * it runs on more than one card.
     */
    static Choice choose(String gpu, boolean cpu, int besidePort, boolean useKept, Undecided undecided, BufferedReader in, PrintStream out) throws IOException {
        if (cpu) return new Choice(null, true, "The helpers run on the processor (--cpu): none of them touches a card.", false);
        List<Card> cards = cards();
        if (gpu != null) {
            Card c = named(gpu, cards);
            if (c == null) return new Choice(null, false, "No card " + gpu + " is listed here (nvidia-smi" + (cards.isEmpty() ? " lists none" : ": " + indexes(cards)) + "): name one with --gpu <index>, or --cpu.", true);
            return new Choice(c, false, "The helpers go on " + c.said() + " (--gpu " + gpu + ").", false);
        }
        String kept = useKept ? Config.get(Helpers.GPU_NAME, "").strip() : "";
        if (kept.equalsIgnoreCase("cpu")) return new Choice(null, true, "The helpers run on the processor, as chosen before (helpers_gpu cpu).", false);
        if (!kept.isEmpty()) { Card c = named(kept, cards); if (c != null) return new Choice(c, false, "The helpers go on " + c.said() + ", as chosen before (helpers_gpu " + kept + ").", false); }
        if (cards.isEmpty()) return Choice.AS_BEFORE;
        String besideSaid = "";
        if (besidePort > 0) {
            Set<Card> beside = besidePort(besidePort, cards);
            if (beside.size() == 1) { Card c = beside.iterator().next(); return new Choice(c, false, "The helpers go on " + c.said() + ", the card of the model server at port " + besidePort + ".", false); }
            List<String> on = new ArrayList<>(); for (Card c : beside) on.add(c.index());
            besideSaid = listeners(besidePort).isEmpty() ? "Nothing listens on port " + besidePort + " here, so the card of a model server there cannot be read: name the card with --gpu <index>, or --cpu."
                    : beside.isEmpty() ? "The model server at port " + besidePort + " runs no model right now, so its card cannot be read: name the card with --gpu <index>, or use the model once and run this again."
                    : "The model server at port " + besidePort + " is on more than one card (" + String.join(", ", on) + "): name the one for the helpers with --gpu <index>.";
        }
        Card own = named(modelServerCard(), cards);
        if (own != null) return new Choice(own, false, "The helpers go on " + own.said() + ", the card this program's model server was installed with.", false);
        if (cards.size() == 1) return new Choice(cards.get(0), false, "The helpers go on " + cards.get(0).said() + ", the only card on this machine.", false);
        if (undecided == Undecided.AS_BEFORE) return new Choice(null, false, "This machine has " + cards.size() + " cards and none is named for the helpers, so they start as before (MetricX-24 on the first card it sees): --gpu <index> names one.", false);
        if (undecided == Undecided.STOP) return new Choice(null, false, !besideSaid.isEmpty() ? besideSaid : "This machine has " + cards.size() + " cards (" + indexes(cards) + ") and nothing says which the helpers go on: name one with --gpu <index>, or --cpu.", true);
        if (!besideSaid.isEmpty()) out.println(besideSaid);
        out.println("This machine has " + cards.size() + " cards. Which one do the heavy helpers go on?");
        for (Card c : cards) out.println("  " + c.index() + "    " + c.name() + ", " + Card.gb(c.freeMb()) + " GB free of " + Card.gb(c.totalMb()) + " GB");
        out.println("  cpu  the processor (no card)");
        out.print("Which? (an index, or cpu) ");
        out.flush();
        String a = in.readLine();
        a = a == null ? "" : a.strip().toLowerCase(Locale.ROOT);
        if (a.equals("cpu")) return new Choice(null, true, "The helpers run on the processor, as you chose.", false);
        Card c = named(a, cards);
        if (c == null) return new Choice(null, false, "No card was chosen, so nothing was changed. researchzosho helpers install --gpu <index> names one.", true);
        return new Choice(c, false, "The helpers go on " + c.said() + ", as you chose.", false);
    }

    static String indexes(List<Card> cards) { List<String> n = new ArrayList<>(); for (Card c : cards) n.add(c.index() + " " + c.name()); return String.join(", ", n); }

    /** The environment a Python helper starts with on this choice: CUDA_VISIBLE_DEVICES set, or left as it is. */
    static void into(Map<String, String> env, Choice choice) {
        String v = choice == null ? null : choice.visible();
        if (v == null) return;
        env.put("CUDA_VISIBLE_DEVICES", v);
        env.put("CUDA_DEVICE_ORDER", "PCI_BUS_ID");   // the order nvidia-smi lists them in, besides: an index anything reads later means nvidia-smi's
    }
}
