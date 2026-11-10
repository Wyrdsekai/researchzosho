package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/**
 * A research run's progress in plain words: the stage it is at, how long it has been going, and about
 * how far along it is. The percent is the larger of two honest numbers. One comes from the stage: reading
 * is most of a run and reports how many of its parts are done. The other is a floor from the clock: a run
 * with a time limit must finish by it, so time elapsed over the limit cannot overstate. It never reads
 * 100 until the run is done.
 */
public final class RunProgress {

    /** One run, ready to show. */
    /**
     * {@code declined}: the sentence that says the model declined the run or parts of it; "" when it declined nothing. {@code result}: what the
     * run ended with, the error's text when it failed, "" while it runs. {@code drive}: the model server's address the run's record names;
     * {@code model}: the entry the run named, "" for the library's default.
     */
    public record View(String id, String state, String stage, long elapsedSeconds, int percent, String question, String report, String declined, String result, String drive, String model) {
        public View(String id, String state, String stage, long elapsedSeconds, int percent, String question, String report, String declined) { this(id, state, stage, elapsedSeconds, percent, question, report, declined, "", "", ""); }
        public boolean active() { return state.equals("queued") || state.equals("running") || state.equals(Jobs.OFFERED); }
    }

    private RunProgress() { }

    /** The view of a job as library_job returns it. */
    public static View of(JsonNode job) { return of(job, System.currentTimeMillis()); }

    public static View of(JsonNode job, long nowMs) { return of(job, nowMs, 0); }

    /**
     * {@code typicalSeconds}: how long a run usually takes in this library (0 = not known). A run with no time limit
     * has no deadline for the clock to measure against, and its reading parts work side by side and finish together
     * near the end, so the stage alone reads "0 of 8 parts" for most of the run. The library's own history stands
     * in for the limit then.
     */
    public static View of(JsonNode job, long nowMs, long typicalSeconds) {
        String state = job.path("state").asText("");
        JsonNode p = job.path("progress");
        long started = millis(job.path("started_at").asText("")), ended = millis(job.path("ended_at").asText(""));
        long elapsed = started <= 0 ? 0 : Math.max(0, ((ended > 0 ? ended : nowMs) - started) / 1000);
        int percent = state.equals("done") ? 100 : state.equals("queued") || state.equals(Jobs.OFFERED) ? 0 : percent(p, started, nowMs, typicalSeconds);
        String stage = switch (state) {
            case "queued" -> "Waiting to start";
            case Jobs.OFFERED -> "Waiting for your answer";
            case "done" -> job.path("declined").path("run").asBoolean(false) ? "Declined by the model" : "Done";
            case "failed" -> "Failed";
            case "stopped" -> "Stopped";
            default -> stage(p);
        };
        return new View(job.path("job_id").asText(""), state, stage, elapsed, percent, job.path("question").asText(""), job.path("investigation").asText(""), job.path("declined").path("statement").asText(""),
                job.path("result").asText(""), job.path("drive").asText(""), job.path("model").asText(""));
    }

    /** The stage in plain words. */
    public static String stage(JsonNode p) {
        String phase = p.path("phase").asText("");
        int done = p.path("workers_done").asInt(), total = p.path("workers_total").asInt(), round = p.path("round").asInt();
        Chrome c = Chrome.now();
        return switch (phase) {
            case "planning" -> c.t("Planning");
            case "workers" -> c.t("Reading") + (total > 0 ? ", " + c.t("{0} of {1} parts done", done, total) : "") + (round > 1 ? " (" + c.t("second pass") + ")" : "");
            case "critic" -> c.t("Checking what is still missing");
            case "synthesis" -> c.t("Writing the report");
            case "cite-check" -> c.t("Checking citations");
            case "filing" -> c.t("Saving the report and its claims");
            case "translating" -> c.t("Translating, {0} of {1} units", p.path("units_done").asInt(), p.path("units_total").asInt());
            case "scoring" -> c.t("Scoring the translation, {0} of {1} units", p.path("units_done").asInt(), p.path("units_total").asInt());
            case "checking" -> c.t("Checking the translation, {0} of {1} units read side by side", p.path("units_done").asInt(), p.path("units_total").asInt());
            case "revising" -> c.t("Revising the flagged units, {0} of {1}", p.path("units_done").asInt(), p.path("units_total").asInt());
            case "writing" -> c.t("Writing the file");
            case "" -> c.t("Starting");
            default -> phase;
        };
    }

    /** About how far along, 1 to 99 while it runs. */
    static int percent(JsonNode p, long startedMs, long nowMs) { return percent(p, startedMs, nowMs, 0); }

    static int percent(JsonNode p, long startedMs, long nowMs, long typicalSeconds) {
        int done = p.path("workers_done").asInt(), total = p.path("workers_total").asInt(), round = Math.max(1, p.path("round").asInt());
        double f = total > 0 ? Math.min(1.0, done / (double) total) : 0;
        int ud = p.path("units_done").asInt(), ut = p.path("units_total").asInt();
        double uf = ut > 0 ? Math.min(1.0, ud / (double) ut) : 0;
        String tier = p.path("quality").asText("");   // a translation: one pass is most of a quick run, over half of a checked one, under half of a reviewed one
        boolean readTier = tier.equals("reviewed") || tier.equals("thorough"), quickTier = tier.equals("quick");
        double byStage = switch (p.path("phase").asText("")) {
            case "planning" -> 3;
            case "translating" -> 2 + (quickTier ? 92 : readTier ? 40 : 60) * uf;
            case "scoring" -> (readTier ? 42 : 62) + 8 * uf;
            case "checking" -> (readTier ? 55 : 75) + (readTier ? 25 : 10) * uf;
            case "revising" -> (readTier ? 82 : 86) + 10 * uf;
            case "writing" -> 96;
            case "workers" -> round <= 1 ? 5 + 50 * f : 57 + 13 * f;   // the first pass is most of the reading; a second pass is shorter and starts after the first check
            case "critic" -> round <= 1 ? 56 : 71;
            case "synthesis" -> 75;
            case "cite-check" -> 90;
            case "filing" -> 96;
            default -> 1;
        };
        double byClock = 0;
        long deadline = millis(p.path("deadline_at").asText(""));
        if (deadline > startedMs && startedMs > 0) byClock = 95.0 * Math.min(1.0, (nowMs - startedMs) / (double) (deadline - startedMs));   // saving goes on a little past the limit
        else if (typicalSeconds > 0 && startedMs > 0) byClock = 90.0 * Math.min(1.0, (nowMs - startedMs) / (typicalSeconds * 1000.0));   // no limit: measured against what runs here usually take; it holds at 90 when this one runs long
        return (int) Math.max(1, Math.min(99, Math.round(Math.max(byStage, byClock))));
    }

    private static volatile long typicalAt = 0, typicalValue = 0;

    /** How long a research run usually takes here: the median of the last finished ones, looked up at most every five minutes. 0 when there are fewer than three. */
    public static long typicalSeconds(LibraryProtocol protocol, JsonNode patron) {
        long now = System.currentTimeMillis();
        if (now - typicalAt < 300_000) return typicalValue;
        try {
            ObjectNode a = new ObjectMapper().createObjectNode().put("limit", 30);
            if (patron != null) a.set("patron", patron.deepCopy());
            List<Long> took = new ArrayList<>();
            for (JsonNode j : protocol.job(a).path("finished")) {
                if (!"research".equals(j.path("kind").asText()) || !"done".equals(j.path("state").asText())) continue;
                long st = millis(j.path("started_at").asText("")), en = millis(j.path("ended_at").asText(""));
                long sec = j.path("elapsed_s").asLong(st > 0 && en > st ? (en - st) / 1000 : 0);
                if (sec >= 60) took.add(sec);
            }
            Collections.sort(took);
            typicalValue = took.size() < 3 ? 0 : took.get(took.size() / 2);
        } catch (Exception e) { typicalValue = 0; }
        typicalAt = now;
        return typicalValue;
    }

    /**
     * Where a job a restart or an update interrupted picked up, or that it began again, in the reader's language: "Resumed after a restart,
     * at unit 4,312 of 11,637." {@code v} is the job's view ({@link Jobs#view}); "" for a job never interrupted.
     */
    public static String resumed(JsonNode v) {
        JsonNode r = v.path("resume");
        Chrome c = Chrome.now();
        if (c.english() || !r.isObject()) return v.path("resumed").asText("");
        String to = r.path("to").asText("");
        String why = switch (r.path("why").asText("")) {
            case Jobs.RESTART -> c.t("a restart");
            case Jobs.UPDATE -> to.isEmpty() ? c.t("an update") : c.t("an update to {0}", to);
            default -> c.t("a stop the service did not finish (a crash, or the computer went off)");
        };
        boolean queued = "queued".equals(v.path("state").asText(""));
        String said = r.path("said").asText("");
        if (r.hasNonNull("started_over")) return c.t("Started again after {0}.", why) + " " + r.path("started_over").asText("");
        if (said.isEmpty()) return queued ? c.t("Starts again after {0}; nothing had been kept yet.", why) : c.t("Started again after {0}; nothing had been kept yet.", why);
        return queued ? c.t("Resumes after {0}, at {1}.", why, said) : c.t("Resumed after {0}, at {1}.", why, said);
    }

    /** "1 hour 4 min", "12 min", "40 sec". */
    public static String elapsed(long seconds) {
        Chrome c = Chrome.now();
        if (seconds < 60) return c.t("{0} sec", seconds);
        long min = seconds / 60, h = min / 60;
        if (h == 0) return c.t("{0} min", min);
        return (h == 1 ? c.t("{0} hour", h) : c.t("{0} hours", h)) + " " + c.t("{0} min", min % 60);
    }

    /** One line for a terminal or a page: the id, the stage, the time, the percent. */
    public static String line(View v) {
        Chrome c = Chrome.now();
        if (v.state().equals("queued")) return v.id() + " · " + c.t("Waiting to start");
        if (!v.active()) return v.id() + " · " + c.t("{0} after {1}", v.stage(), elapsed(v.elapsedSeconds()));
        return v.id() + " · " + v.stage() + " · " + c.t("{0} elapsed", elapsed(v.elapsedSeconds())) + " · " + c.t("about {0}% done", v.percent());
    }

    static long millis(String iso) { try { return iso == null || iso.isBlank() ? 0 : Instant.parse(iso).toEpochMilli(); } catch (Exception e) { return 0; } }
}
