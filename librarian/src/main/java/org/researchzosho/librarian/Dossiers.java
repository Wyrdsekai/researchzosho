package org.researchzosho.librarian;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The dossiers the shelves hold (0.6.6): every person or organisation a report was written on, with what the library has on each. The
 * front door: a dossier was reachable only by its name before, typed into the page or the command, and nothing listed them.
 */
public final class Dossiers {
    private Dossiers() { }

    /** One dossier on the list: the subject, the field its questions had the shape of (person, organization, or "" when neither says), the counts, the latest run's day, whether its story is written. */
    public record Entry(String subject, String kind, int claims, int reports, String lastRun, boolean story) { }

    /** Every dossier, the latest run first. */
    public static List<Entry> list(LibraryStore store) throws IOException {
        List<Entry> out = new ArrayList<>();
        List<Profile> fields = Fields.enabled(store);
        Map<String, Set<String>> runs = Fields.runs(store);   // the fields each run was asked for
        for (var e : CrossLinks.subjects(store).entrySet()) {
            String kind = "", last = ""; int reports = 0;
            for (Investigation inv : e.getValue().reports()) {
                reports++;
                if (inv.recordedAt() != null && inv.recordedAt().compareTo(last) > 0) last = inv.recordedAt();
                Set<String> ran = runs.getOrDefault(inv.id(), Set.of());
                if (kind.isEmpty()) kind = ran.contains("person") ? "person" : ran.contains("organization") ? "organization" : kindOf(inv.title(), fields);
            }
            boolean story = Story.existing(store, e.getKey()) != null;
            if (kind.isEmpty() && !story) continue;   // a report whose question happened to open with a name is not a dossier
            out.add(new Entry(e.getKey(), kind, e.getValue().claims().size(), reports, last.length() >= 10 ? last.substring(0, 10) : last, story));
        }
        out.sort(Comparator.comparing(Entry::lastRun).reversed().thenComparing(Entry::subject));
        return out;
    }

    /** The field a question has the shape of: person, organization, or "" when neither says; "Who is …" is a person when both fields would take it, anything else an organisation. */
    static String kindOf(String title, List<Profile> fields) {
        boolean person = false, organization = false;
        for (Profile p : fields) {
            if (p.name().equals("person") && p.applies(title)) person = true;
            if (p.name().equals("organization") && p.applies(title)) organization = true;
        }
        if (person && organization) return title.matches("(?s)^\\s*[Ww]ho (?:is|was)\\b.*") ? "person" : "organization";
        return person ? "person" : organization ? "organization" : "";
    }

    /** The list as the command prints it: a sentence, then one line a dossier. */
    public static String text(List<Entry> all) {
        if (all.isEmpty()) return "No dossiers yet. A research run on a person or an organisation starts one: researchzosho research ask \"Who is …?\"";
        StringBuilder b = new StringBuilder(all.size() + (all.size() == 1 ? " dossier" : " dossiers") + " on the shelves, the latest run first. Open one with: researchzosho dossier \"<name>\"\n");
        for (Entry e : all) b.append("  ").append(e.subject()).append(" — ").append(e.kind().isEmpty() ? "" : e.kind() + ", ").append(e.claims()).append(e.claims() == 1 ? " claim, " : " claims, ")
                .append(e.reports()).append(e.reports() == 1 ? " report" : " reports").append(e.lastRun().isEmpty() ? "" : ", last run " + e.lastRun()).append(e.story() ? ", story written" : "").append('\n');
        return b.toString().stripTrailing();
    }
}
