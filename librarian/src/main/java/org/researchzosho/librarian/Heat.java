package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Usage heat — "promote hot items" (kiroku-memory's take, 2026-09-05). Every entry the desk
 * returns or pushes is appended to {@code catalog/heat.log}; the nightly crew folds the last
 * {@code RESEARCHZOSHO_HEAT_DAYS} (30) days into {@code catalog/heat.tsv}, and the index applies a
 * small boost, {@code 1 + RESEARCHZOSHO_HEAT_BOOST (0.1) × ln(1 + uses)}, when fusing. The circulation
 * log records what patrons asked; this records what they were GIVEN, which is what ranking needs.
 */
public final class Heat {

    private Heat() { }

    static final double BOOST = org.researchzosho.Config.getDouble("RESEARCHZOSHO_HEAT_BOOST", 0.1);
    static final int DAYS = org.researchzosho.Config.getInt("RESEARCHZOSHO_HEAT_DAYS", 30);

    public static Path log(LibraryStore store) { return store.root().resolve("catalog").resolve("heat.log"); }
    public static Path table(LibraryStore store) { return store.root().resolve("catalog").resolve("heat.tsv"); }

    /** Record that these entries were handed to a patron (top-k of an answer, a push, a search page). */
    public static void used(LibraryStore store, List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        try {
            Files.createDirectories(log(store).getParent());
            StringBuilder sb = new StringBuilder();
            String now = Instant.now().toString();
            for (String id : ids) if (id != null && !id.isBlank()) sb.append(now).append('\t').append(id).append('\n');
            Files.writeString(log(store), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // heat is a ranking hint; it never breaks the answer it observes
        }
    }

    /** Fold the log's last {@code days} into the table; returns entries counted. */
    public static int fold(LibraryStore store, int days) throws IOException {
        Map<String, Integer> counts = new HashMap<>();
        if (Files.exists(log(store))) {
            Instant floor = Instant.now().minus(days, ChronoUnit.DAYS);
            for (String line : Files.readAllLines(log(store), StandardCharsets.UTF_8)) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                try { if (Instant.parse(line.substring(0, t)).isBefore(floor)) continue; } catch (Exception e) { continue; }
                counts.merge(line.substring(t + 1).strip(), 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder("# entry\tuses in the last " + days + " days (folded daily from heat.log)\n");
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n'));
        Files.createDirectories(table(store).getParent());
        Files.writeString(table(store), sb.toString(), StandardCharsets.UTF_8);
        return counts.size();
    }

    /** The table as a map; empty when there is none. */
    public static Map<String, Integer> load(LibraryStore store) {
        Map<String, Integer> m = new HashMap<>();
        try {
            if (!Files.exists(table(store))) return m;
            for (String line : Files.readAllLines(table(store), StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) continue;
                int t = line.indexOf('\t');
                if (t > 0) try { m.put(line.substring(0, t), Integer.parseInt(line.substring(t + 1).strip())); } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
        return m;
    }

    /** The multiplier for an entry: 1 when cold. */
    public static double boost(Map<String, Integer> heat, String id) {
        Integer n = heat.get(id);
        return n == null || n <= 0 || BOOST <= 0 ? 1.0 : 1.0 + BOOST * Math.log1p(n);
    }
}
