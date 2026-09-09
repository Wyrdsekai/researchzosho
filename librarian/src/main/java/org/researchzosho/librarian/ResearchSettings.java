package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * How research SHARES the model: settings a person changes while asks run, never caps baked into code.
 * Read live from the user config file (a value set in the environment is fixed for the process — put
 * these in the file, {@code researchzosho research …} does):
 * <ul>
 *   <li>{@code research.workers} — parallel workers per ask (default 3); a running ask follows at its next turn.</li>
 *   <li>{@code research.pause} — on: queued asks wait, a running ask holds at its next turn.</li>
 *   <li>{@code research.window} — {@code 22:00-07:00}: asks are picked up only inside the window; a running ask finishes.</li>
 * </ul>
 * And one rule that needs no setting: when the drive slows to twice the run's own turn time — someone else
 * is on the card — the run drops to one lane until it recovers ({@link Throttle}).
 */
public final class ResearchSettings {

    public static final String WORKERS = "RESEARCHZOSHO_RESEARCH_WORKERS";
    public static final String PAUSE = "RESEARCHZOSHO_RESEARCH_PAUSE";
    public static final String WINDOW = "RESEARCHZOSHO_RESEARCH_WINDOW";

    private ResearchSettings() { }

    public static int workers() { return Math.max(1, Config.liveInt(WORKERS, 3)); }
    public static boolean paused() { return Config.liveOn(PAUSE, false); }
    public static String window() { String w = Config.live(WINDOW); return w == null || w.isBlank() || w.equalsIgnoreCase("off") ? "" : w.strip(); }
    /** Whether an ask may be picked up now. */
    public static boolean openNow() { return inWindow(window(), LocalTime.now()); }

    /** {@code HH:MM-HH:MM}, wrapping midnight when the end is before the start; blank = always open; malformed = open. */
    public static boolean inWindow(String spec, LocalTime now) {
        if (spec == null || spec.isBlank()) return true;
        String[] p = spec.strip().split("-");
        if (p.length != 2) return true;
        try {
            LocalTime a = LocalTime.parse(p[0].strip().length() == 4 ? "0" + p[0].strip() : p[0].strip());
            LocalTime b = LocalTime.parse(p[1].strip().length() == 4 ? "0" + p[1].strip() : p[1].strip());
            if (a.equals(b)) return true;
            return a.isBefore(b) ? (!now.isBefore(a) && now.isBefore(b)) : (!now.isBefore(a) || now.isBefore(b));
        } catch (Exception e) {
            return true;
        }
    }

    /** Whether {@code spec} is a window this class will honour. */
    public static boolean validWindow(String spec) {
        if (spec == null) return false;
        String[] p = spec.strip().split("-");
        if (p.length != 2) return false;
        try { for (String x : p) LocalTime.parse(x.strip().length() == 4 ? "0" + x.strip() : x.strip()); return true; } catch (Exception e) { return false; }
    }

    /** Hold while paused; says so once on the log. */
    public static void awaitUnpaused(Consumer<String> log) {
        boolean said = false;
        while (paused()) {
            if (!said) { log.accept("paused: research.pause is on — holding at this turn until it is off"); said = true; }
            try { Thread.sleep(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (said) log.accept("resumed");
    }

    public static String describe() {
        return "workers " + workers() + " · " + (paused() ? "PAUSED" : "running") + " · window " + (window().isEmpty() ? "always" : window() + (openNow() ? " (open now)" : " (closed now)"));
    }

    /**
     * The turn gate: at most {@code lanes} model calls in flight, and one lane while the drive is slow. Slow
     * is judged by the run's OWN cost per token — ms per weighted token, the median of its first five turns —
     * because a turn's wall time grows with its history on its own; twice the per-token cost, sustained over
     * three turns, is someone else on the card. Recovery at 1.3×. Thread-safe; a monitor, not a semaphore, so
     * lanes can shrink live.
     */
    public static final class Throttle {
        static final int BASELINE_TURNS = 5;
        static final double SLOW = 2.0, RECOVERED = 1.3;
        private final Consumer<String> log;
        private final List<Long> first = new ArrayList<>();     // per-token costs (µs per weighted token) of the first turns
        private final java.util.ArrayDeque<Long> wall = new java.util.ArrayDeque<>();   // the last turns' wall times, for the wrap-up estimate
        private final double[] recent = new double[3];
        private int seen = 0;
        private double baseline = 0;
        private boolean slow = false;
        private int inFlight = 0;

        public Throttle(Consumer<String> log) { this.log = log == null ? s -> { } : log; }

        public synchronized void enter(int lanes) throws InterruptedException {
            while (inFlight >= Math.max(1, slow ? 1 : lanes)) wait(2_000);
            inFlight++;
        }

        public synchronized void leave() { inFlight = Math.max(0, inFlight - 1); notifyAll(); }

        /** One turn took {@code ms} for {@code tokens} weighted tokens (prompt + ~20× the reply). */
        public synchronized void observe(long ms, long tokens) {
            seen++;
            long cost = ms * 1000 / Math.max(200, tokens);   // µs per weighted token
            wall.addLast(ms); if (wall.size() > 40) wall.removeFirst();
            if (first.size() < BASELINE_TURNS) { first.add(cost); if (first.size() == BASELINE_TURNS) baseline = median(first); return; }
            recent[seen % 3] = cost;
            if (seen < BASELINE_TURNS + 3 || baseline <= 0) return;
            double[] r = recent.clone(); java.util.Arrays.sort(r);
            double med = r[1];
            if (!slow && med > baseline * SLOW) { slow = true; log.accept(String.format("drive slowed: %.1f× this run's own cost per token (%d s this turn) — one lane until it recovers", med / baseline, ms / 1000)); notifyAll(); }
            else if (slow && med < baseline * RECOVERED) { slow = false; log.accept(String.format("drive recovered: %.1f× — lanes restored", med / baseline)); notifyAll(); }
        }
        /** Wall-time form, for a caller that knows no token count: cost per a nominal 1000 tokens. */
        public void observe(long ms) { observe(ms, 1000); }

        public synchronized boolean slow() { return slow; }
        /** The run's typical turn now, ms: the median of its last forty turns (the first five are the cheapest — measured, J-0010). */
        public synchronized long typicalTurnMs() {
            if (wall.isEmpty()) return 0;
            return (long) median(new ArrayList<>(wall));
        }

        /** A long turn now, ms: the 80th percentile of the last forty — closing summaries and sections run several times the median (J-0011). */
        public synchronized long longTurnMs() {
            if (wall.isEmpty()) return 0;
            List<Long> c = new ArrayList<>(wall); java.util.Collections.sort(c);
            return c.get(Math.min(c.size() - 1, (int) Math.floor(c.size() * 0.8)));
        }

        private static double median(List<Long> xs) { List<Long> c = new ArrayList<>(xs); java.util.Collections.sort(c); return c.get(c.size() / 2); }
    }
}
