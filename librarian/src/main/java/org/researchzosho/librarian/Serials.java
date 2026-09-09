package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The SERIALS crew — living shelves (the architecture notes, §crews; the living-systematic-review
 * shape, and ktundwal/librarian's watchlists as UX): a shelf the person SUBSCRIBES gets a watcher
 * that re-searches on its cadence and presents what is NEW — not seen in raw/, not cited by any
 * investigation or finding — on the ARRIVAL TABLE ({@code catalog/arrivals.md}, wyrdsekai's name)
 * for the person to admit with {@code librarian add}. Model-free: a search and a set difference,
 * so it can run from cron on any box. Also the audit sweep's first form: accepted findings past
 * their {@code review_by} are listed as overdue.
 *
 * <p>{@code catalog/shelves.md}: one line per shelf — {@code - slug | query | every N days | last YYYY-MM-DD}.
 */
public final class Serials {

    private Serials() { }

    public record Shelf(String slug, String query, int everyDays, String lastChecked) {
        boolean due(LocalDate today) {
            if (lastChecked == null || lastChecked.isBlank() || lastChecked.equals("-")) return true;
            try { return !LocalDate.parse(lastChecked).plusDays(everyDays).isAfter(today); }
            catch (Exception e) { return true; }
        }
        String toLine() { return "- " + slug + " | " + query + " | every " + everyDays + " days | last " + lastChecked; }
        static Shelf fromLine(String line) {
            String l = line.strip();
            if (!l.startsWith("-")) return null;
            String[] p = l.substring(1).strip().split("\\s*\\|\\s*");
            if (p.length < 2) return null;
            int every = 7;
            String last = "-";
            for (int i = 2; i < p.length; i++) {
                var m = java.util.regex.Pattern.compile("every\\s+(\\d+)").matcher(p[i]);
                if (m.find()) every = Integer.parseInt(m.group(1));
                var d = java.util.regex.Pattern.compile("last\\s+(\\S+)").matcher(p[i]);
                if (d.find()) last = d.group(1);
            }
            return new Shelf(p[0].strip(), p[1].strip(), every, last);
        }
    }

    static Path shelvesFile(LibraryStore store) { return store.subjectsFile().resolveSibling("shelves.md"); }
    static Path arrivalsFile(LibraryStore store) { return store.subjectsFile().resolveSibling("arrivals.md"); }

    public static List<Shelf> shelves(LibraryStore store) {
        List<Shelf> out = new ArrayList<>();
        try {
            if (!Files.exists(shelvesFile(store))) return out;
            for (String line : Files.readAllLines(shelvesFile(store), StandardCharsets.UTF_8)) {
                Shelf s = Shelf.fromLine(line);
                if (s != null) out.add(s);
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** Read, change, write the shelves file under the cross-process lock. */
    static void updateShelves(LibraryStore store, java.util.function.UnaryOperator<List<Shelf>> change) throws IOException {
        store.locked("shelves", () -> { writeShelves(store, change.apply(new ArrayList<>(shelves(store)))); return null; });
    }

    static void writeShelves(LibraryStore store, List<Shelf> shelves) throws IOException {
        StringBuilder sb = new StringBuilder("# Living shelves — subscriptions the serials crew keeps current\n\n"
                + "One per line: `- slug | query | every N days | last YYYY-MM-DD`. `researchzosho serials` checks the due ones.\n\n");
        for (Shelf s : shelves) sb.append(s.toLine()).append('\n');
        Files.createDirectories(shelvesFile(store).getParent());
        Files.writeString(shelvesFile(store), sb.toString(), StandardCharsets.UTF_8);
    }

    public static void add(LibraryStore store, String slug, String query, int everyDays) throws IOException {
        updateShelves(store, cur -> {
            cur.removeIf(s -> s.slug().equals(slug));
            cur.add(new Shelf(slug, query, everyDays, "-"));
            return cur;
        });
    }

    /** Every locator the library already holds — raw captures and everything cited by findings/investigations. */
    public static Set<String> knownLocators(LibraryStore store) {
        Set<String> known = new HashSet<>();
        try {
            if (Files.isDirectory(store.rawDir())) {
                try (var l = Files.list(store.rawDir())) {
                    for (Path p : l.toList()) {
                        if (!p.toString().endsWith(".md")) continue;
                        known.add(norm(RawCapture.read(p)[0]));
                    }
                }
            }
            for (Finding f : store.scanFindings().findings()) for (var s : f.sources()) known.add(norm(s.locator()));
            if (Files.isDirectory(store.investigationsDir())) {
                try (var l = Files.list(store.investigationsDir())) {
                    for (Path p : l.toList()) {
                        if (!p.toString().endsWith(".md")) continue;
                        try { for (String u : Acquisitions.urls(Files.readString(p))) known.add(norm(u)); }
                        catch (Exception ignored) { }
                    }
                }
            }
        } catch (IOException ignored) { }
        return known;
    }

    static String norm(String u) {
        return u == null ? "" : u.strip().replaceFirst("^http://", "https://").replaceAll("/+$", "").toLowerCase();
    }

    public record Arrival(String shelf, String url, String title) { }

    /**
     * Check the due shelves with {@code search} (query → [title, url] pairs); new locators land on
     * the arrival table. Returns what arrived. Injectable search = testable without a backend.
     */
    public static List<Arrival> check(LibraryStore store, Function<String, List<String[]>> search,
                                      LocalDate today) throws IOException {
        List<Arrival> arrivals = new ArrayList<>();
        Set<String> known = knownLocators(store);
        List<Shelf> all = new ArrayList<>(shelves(store));
        boolean changed = false;
        for (int i = 0; i < all.size(); i++) {
            Shelf s = all.get(i);
            if (!s.due(today)) continue;
            for (String[] hit : search.apply(s.query())) {
                String url = hit.length > 1 ? hit[1] : hit[0];
                if (url == null || url.isBlank() || known.contains(norm(url))) continue;
                known.add(norm(url));
                arrivals.add(new Arrival(s.slug(), url, hit.length > 1 ? hit[0] : ""));
            }
            all.set(i, new Shelf(s.slug(), s.query(), s.everyDays(), today.toString()));
            changed = true;
        }
        if (!arrivals.isEmpty()) {
            Path a = arrivalsFile(store);
            if (!Files.exists(a)) {
                Files.writeString(a, "# Arrival table — new sources the living shelves found; admit with `researchzosho add <url>`\n\n",
                        StandardCharsets.UTF_8);
            }
            StringBuilder sb = new StringBuilder();
            for (Arrival ar : arrivals) {
                sb.append("- ").append(today).append(" [").append(ar.shelf()).append("] ").append(ar.url());
                if (!ar.title().isBlank()) sb.append(" — ").append(Acquisitions.compress(ar.title(), 90));
                sb.append('\n');
            }
            Files.writeString(a, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        if (changed) {
            // merge this run's `last` dates onto whatever the file holds NOW — a shelf added meanwhile survives
            List<Shelf> ran = all;
            updateShelves(store, cur -> {
                List<Shelf> out = new ArrayList<>();
                for (Shelf c : cur) {
                    Shelf mine = ran.stream().filter(r -> r.slug().equals(c.slug())).findFirst().orElse(null);
                    out.add(mine == null ? c : mine);
                }
                return out;
            });
        }
        store.circulate("serials", arrivals.size() + " arrival(s)");
        return arrivals;
    }

    /** Accepted findings whose review-by date has passed — the audit sweep's queue. */
    public static List<Finding> overdue(LibraryStore store, LocalDate today) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : store.scanFindings().findings()) {
            if (f.state() != Finding.State.accepted || f.reviewBy().isEmpty()) continue;
            try { if (LocalDate.parse(f.reviewBy()).isBefore(today)) out.add(f); } catch (Exception ignored) { }
        }
        return out;
    }

    // ---- CLI --------------------------------------------------------------------------

    static int shelfCli(LibraryStore store, String[] args) throws IOException {
        if (args.length < 3) { System.err.println("usage: researchzosho shelf add <slug> <query...> [days] | shelf list"); return 2; }
        switch (args[2]) {
            case "list" -> {
                var all = shelves(store);
                if (all.isEmpty()) { System.out.println("no living shelves — `librarian shelf add <slug> <query> [days]`"); return 0; }
                for (Shelf s : all) System.out.println("  " + s.toLine().substring(2));
                return 0;
            }
            case "add" -> {
                if (args.length < 5) { System.err.println("usage: researchzosho shelf add <slug> <query...> [days]"); return 2; }
                int every = 7;
                int end = args.length;
                if (args[end - 1].matches("\\d+")) { every = Integer.parseInt(args[end - 1]); end--; }
                String query = String.join(" ", java.util.Arrays.copyOfRange(args, 4, end));
                add(store, args[3], query, every);
                System.out.println("living shelf '" + args[3] + "': \"" + query + "\" every " + every + " days — `librarian serials` checks it");
                return 0;
            }
            default -> { System.err.println("usage: researchzosho shelf add|list"); return 2; }
        }
    }

    static void check(LibraryStore store) throws IOException {
        var tool = new org.researchzosho.tools.WebSearchTool();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Function<String, List<String[]>> search = q -> {
            List<String[]> hits = new ArrayList<>();
            try {
                String res = tool.execute(json.createObjectNode().put("query", q).put("limit", 10));
                String title = "";
                for (String line : res.split("\n")) {
                    if (line.matches("\\d+\\. .*")) title = line.replaceFirst("^\\d+\\. ", "");
                    else if (line.startsWith("   http")) hits.add(new String[]{title, line.strip()});
                }
            } catch (Exception ignored) { }
            return hits;
        };
        LocalDate today = LocalDate.now();
        var due = shelves(store).stream().filter(s -> s.due(today)).toList();
        var arrivals = check(store, search, today);
        System.out.println(due.size() + " shelf(s) due, " + arrivals.size() + " new arrival(s)"
                + (arrivals.isEmpty() ? "" : " → " + arrivalsFile(store)));
        for (Arrival a : arrivals) System.out.println("  [" + a.shelf() + "] " + a.url() + (a.title().isBlank() ? "" : " — " + Acquisitions.compress(a.title(), 70)));
        var over = overdue(store, today);
        if (!over.isEmpty()) {
            System.out.println(over.size() + " accepted finding(s) past review-by — re-verify or dispute:");
            for (Finding f : over) System.out.println("  " + f.id() + " (review_by " + f.reviewBy() + ") " + Acquisitions.compress(f.title(), 60));
        }
    }
}
