package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The weekly reports — what kiroku-memory's weekly job DOES to its memory (compress similar,
 * delete orphans), The Librarian only PROPOSES to the person: canon is not merged by a crew and
 * the raw tier is not pruned by one. Each report is a catalog file the inbox points at.
 */
public final class Reports {

    private Reports() { }

    static final double DUP_JACCARD = org.researchzosho.Config.getDouble("RESEARCHZOSHO_DUP_JACCARD", 0.6);
    static final int ORPHAN_DAYS = org.researchzosho.Config.getInt("RESEARCHZOSHO_ORPHAN_DAYS", 30);

    public static Path duplicatesFile(LibraryStore store) { return store.root().resolve("catalog").resolve("duplicates.md"); }
    public static Path orphansFile(LibraryStore store) { return store.root().resolve("catalog").resolve("orphans.md"); }

    /** Accepted findings that look like the same claim (term overlap on title + body ≥ {@link #DUP_JACCARD}), for the person to merge by supersession. */
    public static String duplicates(LibraryStore store) throws IOException {
        List<Finding> live = new ArrayList<>();
        for (Finding f : store.scanFindings().findings()) if (f.state() == Finding.State.accepted) live.add(f);
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < live.size(); i++) {
            Set<String> a = Frontier.terms(live.get(i).title() + " " + live.get(i).body());
            for (int j = i + 1; j < live.size(); j++) {
                Set<String> b = Frontier.terms(live.get(j).title() + " " + live.get(j).body());
                double sim = Frontier.jaccard(a, b);
                if (sim >= DUP_JACCARD) pairs.add(String.format("- %s ≈ %s  (%.2f)  — same claim? keep one, `librarian retire` the other (or supersede)", live.get(i).id(), live.get(j).id(), sim));
            }
        }
        StringBuilder sb = new StringBuilder("# Possible duplicates — weekly report (" + LocalDate.now() + ")\n\n"
                + "Accepted findings whose title and body overlap by at least " + DUP_JACCARD + " (Jaccard on terms). A crew never merges canon; you decide.\n\n");
        if (pairs.isEmpty()) sb.append("(none)\n"); else for (String p : pairs) sb.append(p).append('\n');
        Files.createDirectories(duplicatesFile(store).getParent());
        Files.writeString(duplicatesFile(store), sb.toString(), StandardCharsets.UTF_8);
        return pairs.size() + " candidate pair(s) → catalog/duplicates.md";
    }

    /** Raw captures cited by no finding and no investigation, older than {@link #ORPHAN_DAYS} days — listed, never deleted by a crew. */
    public static String orphans(LibraryStore store) throws IOException {
        Set<String> cited = new HashSet<>();
        for (Finding f : store.scanFindings().findings()) for (var s : f.sources()) cited.add(s.locator());
        if (Files.isDirectory(store.investigationsDir())) {
            try (var files = Files.list(store.investigationsDir())) {
                for (Path p : files.toList()) {
                    if (!p.toString().endsWith(".md")) continue;
                    cited.addAll(Acquisitions.urls(Files.readString(p, StandardCharsets.UTF_8)));
                }
            }
        }
        Instant floor = Instant.now().minus(ORPHAN_DAYS, ChronoUnit.DAYS);
        List<String> orphans = new ArrayList<>();
        long bytes = 0;
        if (Files.isDirectory(store.rawDir())) {
            try (var files = Files.list(store.rawDir())) {
                for (Path p : files.sorted().toList()) {
                    if (!p.toString().endsWith(".md")) continue;
                    if (Files.getLastModifiedTime(p).toInstant().isAfter(floor)) continue;
                    String[] r = RawCapture.read(p);
                    String url = r[0];
                    boolean isCited = cited.contains(url) || cited.contains("raw/" + p.getFileName()) || cited.stream().anyMatch(c -> !url.isEmpty() && (c.startsWith(url) || url.startsWith(c)));
                    if (!isCited) { orphans.add("- raw/" + p.getFileName() + "  " + (r[1].isEmpty() ? url : r[1]).replaceAll("\\s+", " ")); bytes += Files.size(p); }
                }
            }
        }
        StringBuilder sb = new StringBuilder("# Orphaned captures — weekly report (" + LocalDate.now() + ")\n\n"
                + "Raw captures older than " + ORPHAN_DAYS + " days that no finding or investigation cites. The raw tier is immutable to crews; "
                + "`researchzosho raw prune` deletes exactly this list, on your say.\n\n");
        if (orphans.isEmpty()) sb.append("(none)\n"); else for (String o : orphans) sb.append(o).append('\n');
        Files.createDirectories(orphansFile(store).getParent());
        Files.writeString(orphansFile(store), sb.toString(), StandardCharsets.UTF_8);
        return orphans.size() + " orphan(s), " + (bytes / 1024) + " KB → catalog/orphans.md";
    }

    /** The person's act: delete the captures the last orphans report listed. Returns how many. */
    public static int prune(LibraryStore store) throws IOException {
        if (!Files.exists(orphansFile(store))) return 0;
        int n = 0;
        for (String line : Files.readAllLines(orphansFile(store), StandardCharsets.UTF_8)) {
            if (!line.startsWith("- raw/")) continue;
            String name = line.substring(6).split("\\s+", 2)[0];
            Path p = LibraryStore.under(store.rawDir(), name);
            if (p == null) continue;   // a line naming raw/../.. deletes nothing (same guard as the desk)
            if (Files.deleteIfExists(p)) {
                Path ctx = LibraryStore.under(store.extractsDir(), name.replace(".md", ".ctx.jsonl"));
                if (ctx != null) Files.deleteIfExists(ctx);
                n++;
            }
        }
        if (n > 0) { store.circulate("raw-prune", n + " orphaned capture(s) deleted by the person"); }
        return n;
    }
}
