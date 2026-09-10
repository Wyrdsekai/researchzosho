package org.researchzosho.librarian;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What the housekeeping will do at its next run, before it runs: which kept searches are due, which open questions
 * the explorer will take and in what bundles, how many accepted claims get re-read, and which weekly or monthly extras
 * the date carries. Read from the same files and the same plan the housekeeping uses, so it is the plan, not a guess.
 * {@code researchzosho tonight} and the Runs page show it.
 */
public final class Tonight {

    private Tonight() { }

    /** The housekeeping hour: the service's --crew-hour, 3 unless RESEARCHZOSHO_CREW_HOUR says otherwise. */
    public static int hour() { return org.researchzosho.Config.getInt("RESEARCHZOSHO_CREW_HOUR", 3); }

    public record Plan(LocalDate date, int hour, List<Serials.Shelf> due, List<Serials.Shelf> later,
                       List<Crews.Bundle> bundles, int queued, int parked, int budget, int standing, int override,
                       int inventoryPerNight, boolean weekly, boolean monthly) {
        /** The open questions the plan takes tonight, in order. */
        public List<Frontier.Line> picks() { List<Frontier.Line> l = new ArrayList<>(); for (Crews.Bundle b : bundles) l.addAll(b.all()); return l; }
        public int stillOpen() { return queued - picks().size(); }
    }

    /** The next run's date: today if the hour has not passed yet, else tomorrow. */
    public static LocalDate nextRun(LocalDate today, LocalTime now) {
        return now.getHour() < hour() ? today : today.plusDays(1);
    }

    public static Plan plan(LibraryStore store) throws IOException {
        return plan(store, nextRun(LocalDate.now(), LocalTime.now()));
    }

    public static Plan plan(LibraryStore store, LocalDate date) throws IOException {
        List<Serials.Shelf> due = new ArrayList<>(), later = new ArrayList<>();
        for (Serials.Shelf s : Serials.shelves(store)) (s.due(date) ? due : later).add(s);
        List<Frontier.Line> open = new ArrayList<>(); int parked = 0;
        for (Frontier.Line l : Frontier.read(store)) { if (l.researchable()) open.add(l); else if (l.open() && l.parked()) parked++; }
        int budget = Crews.explorerBudget();
        Crews.Cadence c = Crews.Cadence.tonight(date);
        return new Plan(date, hour(), due, later, Crews.plan(open, budget), open.size(), parked, budget, Crews.explorerPerNight(), Crews.explorerTonight(),
                Inventory.PER_NIGHT, c.weekly(), c.monthly());
    }

    /** The plan as the command prints it. */
    public static String text(Plan p) {
        StringBuilder b = new StringBuilder();
        b.append("The housekeeping runs at ").append(String.format("%02d:00", p.hour())).append(" on ").append(p.date()).append(".\n\n");
        b.append("Searches you keep (researchzosho shelf add <name> <query> [days]):\n");
        if (p.due().isEmpty() && p.later().isEmpty()) b.append("  none yet\n");
        for (Serials.Shelf s : p.due()) b.append("  runs tonight   ").append(s.slug()).append(": ").append(s.query()).append("  (every ").append(s.everyDays()).append(" days, last ").append(s.lastChecked()).append(")\n");
        for (Serials.Shelf s : p.later()) b.append(s.parked() ? "  parked        " : "  not yet due    ").append(s.slug()).append(": ").append(s.query()).append("  (every ").append(s.everyDays()).append(" days, last ").append(s.lastChecked()).append(")\n");
        b.append("\nOpen questions the explorer will research: ").append(p.budget()).append(" run(s) tonight")
         .append(p.override() > 0 ? " (tonight's override; the standing number is " + p.standing() + ")" : " (the standing number; researchzosho questions budget <n> changes it, questions tonight <n> for one night)")
         .append(". Related questions share a run.\n");
        if (p.bundles().isEmpty()) b.append("  none: nothing queued that the explorer takes\n");
        int n = 0;
        for (Crews.Bundle bd : p.bundles()) {
            n++;
            b.append("  run ").append(n).append(": ").append(bd.question()).append("  [").append(bd.head().type()).append(bd.head().type().equals("asked") ? ", asked " + Frontier.asks(bd.head()) : "").append("]\n");
            for (Frontier.Line l : bd.more()) b.append("         + ").append(Frontier.strip(l.text())).append("  [").append(l.type()).append("]\n");
        }
        if (p.stillOpen() > 0) b.append("  … ").append(p.stillOpen()).append(" more queued for later nights\n");
        if (p.parked() > 0) b.append("  ").append(p.parked()).append(" parked (kept, not taken)\n");
        b.append("\nAlso: ").append(p.inventoryPerNight()).append(" accepted claims re-read against their sources; new write-ups reviewed and catalogued; preprints and retractions checked; the index refreshed; a backup kept.");
        if (p.weekly()) b.append("\nWeekly extras tonight: the duplicate-claims list and the unreferenced-documents list.");
        if (p.monthly()) b.append("\nMonthly extra tonight: the search index is rebuilt.");
        b.append("\n\nTo change it: researchzosho shelf add|every|park|unpark|remove for the searches; researchzosho questions next|later|park|drop|budget|tonight for the queue; researchzosho crews runs it now.\n");
        return b.toString();
    }
}
