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

/**
 * How many research runs, and how many readers in each, the model server can carry (0.6.1). A llama.cpp server says how many calls it
 * answers at once ({@code total_slots} on its props page, also through llama-swap); Ollama and vLLM do not, so they are guessed at one
 * and eight. The rule: a run gets up to three readers, and there is one run per three slots, at least one. A number the person set
 * ({@code job.workers}, {@code research.workers}) wins over the rule. The plan is asked for when the service starts and again, at
 * most every ten minutes, when a run looks; a server that did not answer is asked again within a minute.
 */
public final class ModelSlots {
    private ModelSlots() { }

    static final int READERS_MAX = 3, VLLM_GUESS = 8, OLLAMA_GUESS = 1;
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

    /** What the server can carry and what follows from it. {@code slots} is 0 when the server did not answer. */
    public record Plan(int slots, String source, int runs, int readers) {
        public boolean known() { return slots > 0; }
        /** The sentence the status and the installer show. */
        public String describe() {
            String s = runs + (runs == 1 ? " run at a time, " : " runs at a time, ") + readers + (readers == 1 ? " reader each" : " readers each");
            return known() ? s + " (the model server answers " + slots + (slots == 1 ? " call" : " calls") + " at once, " + source + ")" : s + " (" + source + ")";
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

    private static volatile Plan cached;
    private static volatile String cachedFor = "";
    private static volatile long cachedAt;
    /** Tests set this to answer for the server. */
    static volatile Plan OVERRIDE;

    /** The plan for the library's drive and model as set now, probed at most every ten minutes (every minute while the server does not answer). */
    public static Plan current() {
        Plan o = OVERRIDE;
        if (o != null) return o;
        String drive = Config.get("RESEARCHZOSHO_DRIVE", ""), model = Config.get("RESEARCHZOSHO_MODEL", "local-model");
        String key = drive + "|" + model;
        Plan p = cached;
        long age = System.currentTimeMillis() - cachedAt;
        if (p != null && key.equals(cachedFor) && age < (p.known() ? 600_000L : 60_000L)) return p;
        p = probe(drive, model);
        cached = p; cachedFor = key; cachedAt = System.currentTimeMillis();
        return p;
    }

    /** Whether a setting's value asks for the rule: unset, blank or {@code auto}. */
    public static boolean auto(String value) { return value == null || value.isBlank() || value.strip().equalsIgnoreCase("auto"); }

    /** How many runs at once: the number set, else the rule. */
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

    /** The line for the status and the installer: the numbers in use and where they come from. */
    public static String describe() {
        String j = Config.get("RESEARCHZOSHO_JOB_WORKERS"), r = Config.live("RESEARCHZOSHO_RESEARCH_WORKERS");
        if (auto(j) && auto(r)) return current().describe() + "; set job.workers or research.workers to choose";
        Plan p = current();
        return runs() + (runs() == 1 ? " run at a time, " : " runs at a time, ") + readers() + (readers() == 1 ? " reader each" : " readers each") + " (set by hand"
                + (p.known() ? "; the model server answers " + p.slots() + " calls at once, which would give " + p.runs() + (p.runs() == 1 ? " run and " : " runs and ") + p.readers() + " readers" : "") + ")";
    }
}
