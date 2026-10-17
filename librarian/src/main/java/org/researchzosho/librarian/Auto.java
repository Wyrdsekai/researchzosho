package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Best judgement (0.6.2): which named model a run or a chat should use when nobody named one. The rule is plain and every pick says
 * why. Among the named models that are usable (a key kept where one is needed, the monthly cap not reached), drop those below the
 * tier the job needs, drop those slower than the wait the person accepts when somebody is waiting, then take the cheapest; a free
 * local model wins whenever it clears the tier and the wait. If nothing clears, the wait is relaxed first, then the cost, never the
 * tier below what is available. {@code auto.local_only} keeps every job on local models.
 *
 * <p>What it knows: each named model's tier (measured defaults by family, or {@code models set <name> --tier A|B|C}), its prices, and
 * the library's own ledger — what that model took and cost here, per kind of job, once it has run a few. The knobs: {@code auto} (on
 * by default: auto is the default once a second model is named), {@code auto.wait_minutes} (5), {@code auto.max_dollars} (none),
 * {@code auto.local_only} (off).
 */
public final class Auto {

    private Auto() { }

    public static final String NAME = "auto";

    /** What the job is: its kind (research, quick, chat), the tier it needs, whether somebody is waiting, and the wait they accept in minutes (0 = any). */
    public record Job(String kind, String tier, boolean interactive, double waitMinutes) {
        public static Job research(boolean quick) { return new Job(quick ? "quick" : "research", "A", quick, Config.getInt("RESEARCHZOSHO_AUTO_WAIT_MINUTES", 5)); }
        public static Job chat() { return new Job("chat", "B", true, Config.getInt("RESEARCHZOSHO_AUTO_WAIT_MINUTES", 5)); }
    }

    public record Candidate(ModelEntries.Entry entry, String tier, boolean usable, String whyNot, double minutes, double dollars, boolean free) { }

    public record Pick(ModelEntries.Entry entry, String why) { }

    /** Whether auto is the default when no model is named: the setting is on and there is a choice to make. */
    public static boolean isDefault() { return Config.isOn("RESEARCHZOSHO_AUTO", true) && ModelEntries.all().size() >= 2; }

    static int rank(String tier) { return switch (tier == null ? "C" : tier.toUpperCase(Locale.ROOT)) { case "A" -> 3; case "B" -> 2; default -> 1; }; }

    /** Typical tokens of a job of each kind, for a model with no history here: prompt, cached, completion. From the scouting runs of 2026-10-15/16. */
    static final Map<String, long[]> TYPICAL = Map.of("research", new long[]{3_000_000, 2_200_000, 70_000}, "quick", new long[]{200_000, 120_000, 10_000}, "chat", new long[]{40_000, 20_000, 3_000});

    public static Pick choose(LibraryStore store, Job job) {
        List<Candidate> all = new ArrayList<>();
        for (ModelEntries.Entry e : ModelEntries.all().values()) all.add(candidate(store, e, job));
        return pick(all, job);
    }

    public static Candidate candidate(LibraryStore store, ModelEntries.Entry e, Job job) {
        String whyNot = null;
        boolean hosted = e.hosted();
        if (Config.isOn("RESEARCHZOSHO_AUTO_LOCAL_ONLY", false) && hosted) whyNot = "auto.local_only is on";
        else if (hosted && org.researchzosho.drive.DriveClient.keyFor(e.drive()) == null) whyNot = "no key kept for " + e.host();
        else if (e.cap() > 0 && store != null && Spend.monthTotal(store, e.name()) >= e.cap()) whyNot = "its monthly cap is reached";
        double[] h = store == null ? null : Spend.average(store, e.model(), job.kind());
        boolean free = e.prices() == null ? !hosted : (e.prices()[0] == 0 && e.prices()[1] == 0);
        // measured 2026-10-15/16: a research run 8–12 minutes on a hosted model, 40 on the local 27B; a quick ask or a chat turn a minute hosted, a few local
        double minutes = h != null ? h[0] : job.kind().equals("research") ? (hosted ? 10 : 40) : (hosted ? 1 : 4);
        long[] t = TYPICAL.getOrDefault(job.kind(), TYPICAL.get("research"));
        double dollars = h != null ? h[1] : free ? 0 : e.cost(t[0], t[1], t[2]);
        return new Candidate(e, e.tier(), whyNot == null, whyNot, minutes, dollars, free);
    }

    /** The rule itself, pure. */
    public static Pick pick(List<Candidate> all, Job job) {
        List<Candidate> able = all.stream().filter(Candidate::usable).toList();
        if (able.isEmpty()) return new Pick(ModelEntries.defaultEntry(), "auto: no named model can take this job (" + String.join("; ", all.stream().map(c -> c.entry().name() + ": " + c.whyNot()).toList()) + "); using the default");
        int need = rank(job.tier());
        int best = able.stream().mapToInt(c -> rank(c.tier())).max().orElse(1);
        int floor = Math.min(need, best);
        List<Candidate> tiered = able.stream().filter(c -> rank(c.tier()) >= floor).toList();
        double maxDollars = Config.getInt("RESEARCHZOSHO_AUTO_MAX_DOLLARS", 0);
        List<Candidate> affordable = maxDollars > 0 ? tiered.stream().filter(c -> c.dollars() <= maxDollars).toList() : tiered;
        if (affordable.isEmpty()) affordable = tiered;
        List<Candidate> quick = job.interactive() && job.waitMinutes() > 0 ? affordable.stream().filter(c -> c.minutes() <= job.waitMinutes()).toList() : affordable;
        boolean relaxed = quick.isEmpty();
        List<Candidate> pool = relaxed ? affordable : quick;
        Candidate c = pool.stream().min(Comparator.comparingDouble(Candidate::dollars).thenComparingDouble(Candidate::minutes)).get();
        StringBuilder why = new StringBuilder("auto chose " + c.entry().name() + ": tier " + c.tier() + " for a tier-" + job.tier() + " " + job.kind() + " job, about " + minutes(c.minutes()) + ", " + (c.free() ? "free" : "about " + Spend.dollars(c.dollars())));
        if (floor < need) why.append("; nothing named is tier " + job.tier() + ", the best available is " + (floor >= 3 ? "A" : floor == 2 ? "B" : "C"));
        if (relaxed) why.append("; nothing clears the " + minutes(job.waitMinutes()) + " wait, so the wait is relaxed");
        for (Candidate o : all) if (o != c && (!o.usable() || rank(o.tier()) >= floor))
            why.append("; ").append(o.entry().name()).append(o.usable() ? (o.free() ? " is free" : " about " + Spend.dollars(o.dollars())) + (job.interactive() && job.waitMinutes() > 0 && o.minutes() > job.waitMinutes() ? " but about " + minutes(o.minutes()) : (o.dollars() > c.dollars() || (o.dollars() == c.dollars() && o.minutes() > c.minutes()) ? ", dearer or slower" : "")) : ": " + o.whyNot());
        return new Pick(c.entry(), why.toString());
    }

    static String minutes(double m) { return m < 1 ? "under a minute" : m < 2 ? "a minute" : String.format(Locale.ROOT, "%.0f minutes", m); }
}
