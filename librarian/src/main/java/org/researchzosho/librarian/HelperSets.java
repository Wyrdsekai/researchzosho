package org.researchzosho.librarian;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The sets of heavy helpers a machine serves (0.7.4). One set by default: port 8217, the unit researchzosho-helpers, which every
 * library joins and the find looks for. As an option ({@code helpers install --separate}) a set of its own per card, so that two
 * libraries whose models sit on different cards each have the helpers on their own card: the next free port from 8218, the unit
 * researchzosho-helpers-gpu&lt;index&gt; (or -cpu, the processor counting as one card), the machine's one secret. One set per card at
 * most. What sets there are is read from the unit files themselves, their {@code --port} and {@code --gpu} or {@code --cpu}, never from
 * a second list that could disagree with them.
 */
final class HelperSets {
    private HelperSets() { }

    /** A set: its unit, its port, the card its unit names (an index, "cpu", or "" for none named), its unit file. */
    record HelperSet(String unit, int port, String card, Path file) {
        boolean first() { return unit.equals(HelpersCli.UNIT); }
        /** "card 2", "the processor", or "the card it starts on" for a unit that names none. */
        String on() { return card.equals("cpu") ? "the processor" : card.isEmpty() ? "the card it starts on" : "card " + card; }
    }

    private static final Pattern PORT = Pattern.compile("--port\\s+(\\d+)"), GPU = Pattern.compile("--gpu\\s+(\\S+)");

    /** The folder the units are in. */
    static Path units() { return HelpersInstall.unitFile().getParent(); }

    /** The sets this machine has, the first set first, then by port. */
    static List<HelperSet> sets() {
        List<HelperSet> out = new ArrayList<>();
        if (!Files.isDirectory(units())) return out;
        try (Stream<Path> files = Files.list(units())) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (!name.startsWith(HelpersCli.UNIT) || !name.endsWith(".service")) continue;
                String unit = name.substring(0, name.length() - ".service".length());
                if (!unit.equals(HelpersCli.UNIT) && !unit.startsWith(HelpersCli.UNIT + "-")) continue;
                String text = Files.readString(f, StandardCharsets.UTF_8);
                String exec = text.lines().filter(l -> l.startsWith("ExecStart=")).findFirst().orElse("");
                Matcher p = PORT.matcher(exec), g = GPU.matcher(exec);
                int port = p.find() ? Integer.parseInt(p.group(1)) : Helpers.PORT;
                String card = exec.contains(" --cpu") ? "cpu" : g.find() ? g.group(1) : "";
                out.add(new HelperSet(unit, port, card, f));
            }
        } catch (IOException e) { return out; }
        out.sort(Comparator.comparing((HelperSet s) -> !s.first()).thenComparingInt(HelperSet::port));
        return out;
    }

    /** The set on {@code card}'s choice ("cpu", or the card's index), or null. */
    static HelperSet on(List<HelperSet> sets, HelperCards.Choice card) {
        String k = card.kept();
        if (k.isEmpty()) return null;
        for (HelperSet s : sets) if (s.card().equals(k)) return s;
        return null;
    }

    static HelperSet first(List<HelperSet> sets) { for (HelperSet s : sets) if (s.first()) return s; return null; }

    /** The unit of a separate set on {@code card}'s choice: researchzosho-helpers-gpu2, or -cpu. */
    static String unitFor(HelperCards.Choice card) { return HelpersCli.UNIT + (card.cpu() ? "-cpu" : "-gpu" + card.card().index()); }

    /** Whether a port is free on this machine; a test stands it in. */
    static volatile IntPredicate PORT_FREE = port -> {
        try (ServerSocket s = new ServerSocket()) { s.setReuseAddress(true); s.bind(new InetSocketAddress(port)); return true; }
        catch (IOException e) { return false; }
    };

    /** The port of a new separate set: the next one from 8218 that no set uses and nothing listens on. */
    static int nextPort(List<HelperSet> sets) {
        for (int p = Helpers.PORT + 1; p < Helpers.PORT + 100; p++) {
            int q = p;
            if (sets.stream().noneMatch(s -> s.port() == q) && PORT_FREE.test(p)) return p;
        }
        return Helpers.PORT + 100;
    }
}
