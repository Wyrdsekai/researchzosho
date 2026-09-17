package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * A research run's progress in plain words: the stage it is at, how long it has been going, and about
 * how far along it is. The percent is the larger of two honest numbers. One comes from the stage: reading
 * is most of a run and reports how many of its parts are done. The other is a floor from the clock: a run
 * with a time limit must finish by it, so time elapsed over the limit cannot overstate. It never reads
 * 100 until the run is done.
 */
public final class RunProgress {

    /** One run, ready to show. */
    public record View(String id, String state, String stage, long elapsedSeconds, int percent, String question, String report) {
        public boolean active() { return state.equals("queued") || state.equals("running"); }
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
        int percent = state.equals("done") ? 100 : state.equals("queued") ? 0 : percent(p, started, nowMs, typicalSeconds);
        String stage = switch (state) {
            case "queued" -> "Waiting to start";
            case "done" -> "Done";
            case "failed" -> "Failed";
            case "stopped" -> "Stopped";
            default -> stage(p);
        };
        return new View(job.path("job_id").asText(""), state, stage, elapsed, percent, job.path("question").asText(""), job.path("investigation").asText(""));
    }

    /** The stage in plain words. */
    public static String stage(JsonNode p) {
        String phase = p.path("phase").asText("");
        int done = p.path("workers_done").asInt(), total = p.path("workers_total").asInt(), round = p.path("round").asInt();
        return switch (phase) {
            case "planning" -> "Planning";
            case "workers" -> "Reading" + (total > 0 ? ", " + done + " of " + total + " parts done" : "") + (round > 1 ? " (second pass)" : "");
            case "critic" -> "Checking what is still missing";
            case "synthesis" -> "Writing the report";
            case "cite-check" -> "Checking citations";
            case "filing" -> "Saving the report and its claims";
            case "" -> "Starting";
            default -> phase;
        };
    }

    /** About how far along, 1 to 99 while it runs. */
    static int percent(JsonNode p, long startedMs, long nowMs) { return percent(p, startedMs, nowMs, 0); }

    static int percent(JsonNode p, long startedMs, long nowMs, long typicalSeconds) {
        int done = p.path("workers_done").asInt(), total = p.path("workers_total").asInt(), round = Math.max(1, p.path("round").asInt());
        double f = total > 0 ? Math.min(1.0, done / (double) total) : 0;
        double byStage = switch (p.path("phase").asText("")) {
            case "planning" -> 3;
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
            com.fasterxml.jackson.databind.node.ObjectNode a = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("limit", 30);
            if (patron != null) a.set("patron", patron.deepCopy());
            java.util.List<Long> took = new java.util.ArrayList<>();
            for (JsonNode j : protocol.job(a).path("finished")) {
                if (!"research".equals(j.path("kind").asText()) || !"done".equals(j.path("state").asText())) continue;
                long st = millis(j.path("started_at").asText("")), en = millis(j.path("ended_at").asText(""));
                long sec = j.path("elapsed_s").asLong(st > 0 && en > st ? (en - st) / 1000 : 0);
                if (sec >= 60) took.add(sec);
            }
            java.util.Collections.sort(took);
            typicalValue = took.size() < 3 ? 0 : took.get(took.size() / 2);
        } catch (Exception e) { typicalValue = 0; }
        typicalAt = now;
        return typicalValue;
    }

    /** "1 hour 4 min", "12 min", "40 sec". */
    public static String elapsed(long seconds) {
        if (seconds < 60) return seconds + " sec";
        long min = seconds / 60, h = min / 60;
        if (h == 0) return min + " min";
        return h + (h == 1 ? " hour " : " hours ") + (min % 60) + " min";
    }

    /** One line for a terminal or a page: the id, the stage, the time, the percent. */
    public static String line(View v) {
        if (v.state().equals("queued")) return v.id() + " · Waiting to start";
        if (!v.active()) return v.id() + " · " + v.stage() + " after " + elapsed(v.elapsedSeconds());
        return v.id() + " · " + v.stage() + " · " + elapsed(v.elapsedSeconds()) + " elapsed · about " + v.percent() + "% done";
    }

    static long millis(String iso) { try { return iso == null || iso.isBlank() ? 0 : Instant.parse(iso).toEpochMilli(); } catch (Exception e) { return 0; } }
}
