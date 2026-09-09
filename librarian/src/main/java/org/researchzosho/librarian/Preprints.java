package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preprint versions, tracked by the serials crew: a finding that cites arXiv:2504.00327 (or v1) is
 * checked against the arXiv record; when a newer version exists, the finding gets a {@code revised}
 * note naming the version and its date, and a {@code revised} change goes on the feed — so a patron
 * who cited v1 hears about v2 through the same recall notices that carry retirements and disputes.
 * The finding's state does not change: a new version is a reason to re-read, not a verdict.
 *
 * <p>Budgeted per night ({@code RESEARCHZOSHO_PREPRINTS_PER_NIGHT}); each id is re-checked no more than
 * every {@code RESEARCHZOSHO_PREPRINTS_DAYS} days, remembered in {@code catalog/preprints.tsv}.
 */
public final class Preprints {

    static final int PER_NIGHT = org.researchzosho.Config.getInt("RESEARCHZOSHO_PREPRINTS_PER_NIGHT", 20);
    static final int DAYS = org.researchzosho.Config.getInt("RESEARCHZOSHO_PREPRINTS_DAYS", 14);

    private Preprints() { }

    public record Outcome(int checked, int revised, List<String> notes) { }

    /** The latest version of an arXiv id and its date, e.g. {@code ["v3", "2026-05-01"]}, or null. */
    public interface Lookup { String[] latest(String arxivId) throws Exception; }

    /** The live lookup, through the same guarded fetch the citations use. */
    public static Lookup live() {
        return id -> {
            String atom = Citations.LIVE.get(Citations.arxivBase + "/api/query?id_list=" + id);
            Matcher idm = Pattern.compile("<id>\\s*https?://arxiv\\.org/abs/[\\d.]+(v\\d+)?\\s*</id>").matcher(atom);
            String v = idm.find() && idm.group(1) != null ? idm.group(1) : "v1";
            Matcher um = Pattern.compile("<updated>([^<]{10})").matcher(atom);
            String when = um.find() ? um.group(1) : "";
            return new String[]{v, when};
        };
    }

    public static Outcome check(LibraryStore store, Lookup lookup, int budget, LocalDate today) throws IOException {
        Map<String, String> seen = readLedger(store);
        List<String> notes = new ArrayList<>();
        int checked = 0, revised = 0;
        for (Finding f : store.scanFindings().findings()) {
            if (checked >= budget) break;
            if (f.state() == Finding.State.retired) continue;
            for (Finding.Source s : f.sources()) {
                String[] cited = Citations.arxivOf(s.locator() + " " + (s.edition() == null ? "" : s.edition()));
                if (cited == null) continue;
                String id = cited[0];
                String last = seen.get(id);
                if (last != null && LocalDate.parse(last.split("\t")[0]).plusDays(DAYS).isAfter(today)) continue;
                String[] latest;
                try { latest = lookup.latest(id); } catch (Exception e) { notes.add(id + ": lookup failed (" + e.getMessage() + ")"); continue; }
                checked++;
                if (latest == null) continue;
                record(store, id, today, latest[0]);
                seen.put(id, today + "\t" + latest[0]);
                int citedV = versionNumber(cited[1].isEmpty() ? (last == null ? "v1" : last.split("\t")[1]) : cited[1]);
                int latestV = versionNumber(latest[0]);
                boolean alreadyNoted = f.notes().stream().anyMatch(n -> "revised".equals(n.kind()) && n.text().contains("arXiv:" + id + latest[0]));
                if (latestV > citedV && !alreadyNoted) {
                    Finding withNote = f.withNote(new Finding.Note("revised", "serials", today.toString(),
                            "arXiv:" + id + latest[0] + " published" + (latest[1].isEmpty() ? "" : " " + latest[1]) + "; this finding cites " + (cited[1].isEmpty() ? "an earlier version" : cited[1]) + " — re-read before relying on it"));
                    store.write(withNote);
                    Changes.append(store, "finding", f.id(), "revised", "arXiv:" + id + latest[0]);
                    revised++;
                    notes.add(f.id() + " ← arXiv:" + id + latest[0]);
                }
                break;   // one arXiv source per finding is enough to track
            }
        }
        store.circulate("preprints", checked + " checked, " + revised + " revised");
        return new Outcome(checked, revised, notes);
    }

    static int versionNumber(String v) {
        try { return Integer.parseInt(v.replaceFirst("^v", "")); } catch (Exception e) { return 1; }
    }

    static Path ledger(LibraryStore store) { return store.root().resolve("catalog").resolve("preprints.tsv"); }

    static Map<String, String> readLedger(LibraryStore store) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Path f = ledger(store);
        if (!Files.exists(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", 3);
            if (p.length == 3) out.put(p[0], p[1] + "\t" + p[2]);
        }
        return out;
    }

    static void record(LibraryStore store, String id, LocalDate when, String version) throws IOException {
        Path f = ledger(store);
        Files.createDirectories(f.getParent());
        Files.writeString(f, id + "\t" + when + "\t" + version + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
