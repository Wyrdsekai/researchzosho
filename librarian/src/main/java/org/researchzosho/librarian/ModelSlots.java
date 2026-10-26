package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many research runs, and how many readers in each, the model servers can carry (0.6.1; every drive since 0.6.6). A llama.cpp
 * server says how many calls it answers at once ({@code total_slots} on its props page, also through llama-swap); Ollama and vLLM do
 * not, so they are guessed at one and eight. The rule: a run gets up to three readers, and each server carries one run per three of
 * its slots, at least one. With several servers in {@code job.drives} the runs add up, a server with more slots takes more of them
 * ({@link Fleet#lanes()}), and a run's readers follow its own server. A number the person set ({@code job.workers},
 * {@code research.workers}) wins over the rule. The plan is asked for when the service starts and again, at most every ten minutes,
 * when a run looks; a server that did not answer is asked again within a minute.
 */
public final class ModelSlots {
    private ModelSlots() { }

    static final int READERS_MAX = 3, VLLM_GUESS = 8, OLLAMA_GUESS = 1;
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

    /** What one server can carry and what follows from it. {@code slots} is 0 when the server did not answer. */
    public record Plan(int slots, String source, int runs, int readers) {
        public boolean known() { return slots > 0; }
        /** The sentence the status and the installer show. */
        public String describe() {
            String s = runs + (runs == 1 ? " run at a time, " : " runs at a time, ") + readers + (readers == 1 ? " reader each" : " readers each");
            return known() ? s + " (the model server answers " + slots + (slots == 1 ? " call" : " calls") + " at once, " + source + ")" : s + " (" + source + ")";
        }
    }

    /** Every drive the service runs on, each asked, and what follows for all of them together. */
    public record Fleet(List<String> drives, List<Plan> plans) {
        public int slots() { int s = 0; for (Plan p : plans) s += p.slots(); return s; }
        /** Whether any server answered. */
        public boolean known() { for (Plan p : plans) if (p.known()) return true; return false; }
        /** Whether every server answered; one that did not is asked again sooner. */
        public boolean allKnown() { for (Plan p : plans) if (!p.known()) return false; return true; }
        /** Runs at once: each server's own, added up. */
        public int runs() { int r = 0; for (Plan p : plans) r += Math.max(1, p.runs()); return r; }
        /** Readers for a run whose server is not known here: the most any server allows, else the usual three. */
        public int readers() { int r = 0; for (Plan p : plans) if (p.known()) r = Math.max(r, p.readers()); return r == 0 ? READERS_MAX : r; }
        /** Readers for a run on {@code drive}: that server's own share when it is one of the fleet's, else {@link #readers()}. */
        public int readers(String drive) { int i = indexOf(drive); return i < 0 ? readers() : plans.get(i).readers(); }
        /** The drive each worker takes, in order: a drive once per run it carries, so a server with eight slots gets two workers to a one-slot server's one. */
        public List<String> lanes() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < drives.size(); i++) for (int k = 0; k < Math.max(1, plans.get(i).runs()); k++) out.add(drives.get(i));
            return out;
        }
        private static String key(String drive) { return drive == null ? "" : drive.strip().replaceAll("/+$", ""); }
        int indexOf(String drive) { String d = key(drive); for (int i = 0; i < drives.size(); i++) if (key(drives.get(i)).equals(d)) return i; return -1; }
        /** The sentence the status shows: one server's own, or the sum over several with each server's share. */
        public String describe() {
            if (plans.size() == 1) return plans.get(0).describe();
            StringBuilder sb = new StringBuilder(runs() + (runs() == 1 ? " run at a time, " : " runs at a time, ") + readers() + (readers() == 1 ? " reader each" : " readers each"));
            sb.append(" (").append(plans.size()).append(" model servers");
            if (known()) sb.append(" answer ").append(slots()).append(slots() == 1 ? " call" : " calls").append(" at once between them");
            sb.append(": ");
            for (int i = 0; i < plans.size(); i++) {
                Plan p = plans.get(i);
                if (i > 0) sb.append("; ");
                sb.append(p.known() ? p.slots() + (p.slots() == 1 ? " call" : " calls") : "unknown").append(" at ").append(drives.get(i).isBlank() ? "the library's drive" : drives.get(i)).append(", ").append(p.source());
            }
            return sb.append(")").toString();
        }
    }

    /** The rule, from the slots: up to three readers a run, one run per three slots, at least one of each. */
    public static Plan plan(int slots, String source) {
        if (slots <= 0) return new Plan(0, source, 1, READERS_MAX);
        return new Plan(slots, source, Math.max(1, slots / READERS_MAX), Math.min(READERS_MAX, slots));
    }

    /** The server asked: llama.cpp's props page (at the address, or through llama-swap at /upstream/<model>/props), else a guess by what answers. */
    public static Plan probe(String drive, String model) {
        if (drive == null || drive.isBlank()) return plan(0, "no model server is set");
        String base = drive.strip().replaceAll("/+$", "");
        Integer slots = totalSlots(base + "/props");
        if (slots == null && model != null && !model.isBlank()) slots = totalSlots(base + "/upstream/" + URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20") + "/props");
        if (slots != null) return plan(slots, "llama.cpp says so");
        if (answers(base + "/api/tags")) return plan(OLLAMA_GUESS, "a guess for Ollama, which answers one call at a time unless OLLAMA_NUM_PARALLEL says more");
        if (answers(Config.v1(base) + "/models")) return plan(VLLM_GUESS, "a guess for a vLLM-style server");
        return plan(0, "the model server did not answer; the usual numbers until it does");
    }

    private static Integer totalSlots(String url) {
        try {
            HttpResponse<String> r = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            JsonNode n = M.readTree(r.body()).path("total_slots");
            return n.isInt() && n.asInt() > 0 ? n.asInt() : null;
        } catch (Exception e) { return null; }
    }

    private static boolean answers(String url) {
        try { return HTTP.send(request(url), HttpResponse.BodyHandlers.ofString()).statusCode() == 200; } catch (Exception e) { return false; }
    }

    private static HttpRequest request(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET();
        String key = Config.get("RESEARCHZOSHO_API_KEY");
        if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key.strip());
        return b.build();
    }

    // ---- the plan the service uses ----

    private record Cached(Fleet fleet, long at) { }
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    /** Tests set this to answer for every server. */
    static volatile Plan OVERRIDE;
    /** Tests: forget what the servers said. */
    static void forget() { CACHE.clear(); }

    /** The fleet for the drives as set now ({@code job.drives}, else the library's drive), probed at most every ten minutes (every minute while a server does not answer). */
    public static Fleet current() {
        List<String> drives = Jobs.drives(Config.get("RESEARCHZOSHO_DRIVE", ""));
        Plan o = OVERRIDE;
        if (o != null) { List<Plan> ps = new ArrayList<>(); for (int i = 0; i < drives.size(); i++) ps.add(o); return new Fleet(drives, ps); }
        String key = String.join(",", drives) + "|" + Config.get("RESEARCHZOSHO_MODEL", "local-model");
        long now = System.currentTimeMillis();
        Cached c = CACHE.get(key);
        if (c != null && now - c.at() < (c.fleet().allKnown() ? 600_000L : 60_000L)) return c.fleet();
        List<Plan> plans = new ArrayList<>();
        for (String d : drives) plans.add(probe(d, Config.get("RESEARCHZOSHO_MODEL", "local-model")));
        Fleet f = new Fleet(drives, plans);
        if (CACHE.size() >= 16) CACHE.clear();   // the drives change rarely; many keys is a test
        CACHE.put(key, new Cached(f, now));
        return f;
    }

    /** Whether a setting's value asks for the rule: unset, blank or {@code auto}. */
    public static boolean auto(String value) { return value == null || value.isBlank() || value.strip().equalsIgnoreCase("auto"); }

    /**
     * The drives as the workers take them: the listed ones, each once per run it carries ({@link Fleet#lanes()}); nothing listed, the
     * one drive given, which the service passes as the one it started with (a test's stub, an address on the command line) and which
     * is not probed here: the library's own drive is probed by {@link #current()} as the setting names it.
     */
    public static List<String> lanes(String fallback) {
        if (Jobs.listed().isEmpty()) return List.of(fallback == null ? "" : fallback);
        return current().lanes();
    }

    /** How many runs at once: the number set, else the rule over every drive. */
    public static int runs() {
        String v = Config.get("RESEARCHZOSHO_JOB_WORKERS");
        if (!auto(v)) { try { return Math.max(1, Integer.parseInt(v.strip())); } catch (NumberFormatException ignored) { } }
        return current().runs();
    }

    /** How many readers in a run: the number set, else the rule. Read live, so a run follows a change at its next turn. */
    public static int readers() {
        String v = Config.live("RESEARCHZOSHO_RESEARCH_WORKERS");
        if (!auto(v)) { try { return Math.max(1, Integer.parseInt(v.strip())); } catch (NumberFormatException ignored) { } }
        return current().readers();
    }

    /** How many readers a run on {@code drive} gets: the number set, else that server's own share of the rule. Read live. */
    public static int readers(String drive) {
        String v = Config.live("RESEARCHZOSHO_RESEARCH_WORKERS");
        if (!auto(v)) { try { return Math.max(1, Integer.parseInt(v.strip())); } catch (NumberFormatException ignored) { } }
        return current().readers(drive);
    }

    /** The line for the status and the installer: the numbers in use and where they come from. */
    public static String describe() {
        String j = Config.get("RESEARCHZOSHO_JOB_WORKERS"), r = Config.live("RESEARCHZOSHO_RESEARCH_WORKERS");
        Fleet f = current();
        if (auto(j) && auto(r)) return f.describe() + "; set job.workers or research.workers to choose";
        int runs = runs(), readers = readers();
        String servers = f.plans().size() == 1 ? "the model server answers " : f.plans().size() + " model servers answer ";
        return runs + (runs == 1 ? " run at a time, " : " runs at a time, ") + readers + (readers == 1 ? " reader each" : " readers each") + " (set by hand"
                + (f.known() ? "; " + servers + f.slots() + " calls at once, which would give " + f.runs() + (f.runs() == 1 ? " run and " : " runs and ") + f.readers() + " readers" : "") + ")";
    }
}
