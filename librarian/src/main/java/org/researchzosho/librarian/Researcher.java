package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.tools.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The library's own overnight research runner (2026-09-06). ResearchZosho stands alone after the
 * repo split, so an ask filed through {@code library_research} runs HERE, not through codezaiku's
 * agent loop — the operator: "we need to create one for researchzosho on its own … make sure its not a
 * weak one".
 *
 * <p>The shape is the one codezaiku's research verb MEASURED its way to, taken as mechanisms and
 * re-cut for a library:
 * <ol>
 *   <li><b>Plan.</b> The sub-questions come from the person's brief (the RRD the refine phase
 *       produced) when it has them; otherwise one deterministic decompose call. What the library
 *       already holds is pushed into the plan so the run works the GAPS (research is cumulative).</li>
 *   <li><b>Workers.</b> One fresh-context worker per sub-question, in parallel, each a small
 *       search → fetch → note loop. Every fact is NOTED as it is found, with its locator and a
 *       quote, so a worker cut off by its budget still leaves its evidence behind. The mechanisms
 *       that moved the numbers ride along: the deadline turn (last turn offers only the finishing
 *       tool — a warning is read and ignored, a reduced tool list is not), the search steerer's
 *       exhaustion as an early finish, the give-up rule, the list-page strategy for many-item
 *       questions, queries in the languages the question names, a spin guard on repeated calls.</li>
 *   <li><b>Critic.</b> No tools. It reads the evidence against the brief and ITS verdict decides
 *       whether another round runs on named gaps — never the workers' feeling of being done.</li>
 *   <li><b>Synthesis.</b> The investigation is written in SECTIONS through a tool, one call per
 *       section (a single huge completion is cut off and lost — measured on a 109-row table),
 *       with the evidence fitted to the context window by script-aware token count.</li>
 * </ol>
 *
 * <p>The ceilings are honest: {@code maxTurns}, when the person set one, is the total number of model turns the ask may spend,
 * across every worker, the critic and the synthesis. A worker that cannot take a turn from the
 * shared budget is on its deadline turn. The daemon charges that number to the patron's daily
 * budget at filing, so what is charged is what can be spent.
 *
 * <p>Everything the model sees from the web is untrusted text (page content, search results); it is
 * evidence, never instruction. The tools deliver it inside {@link Fence} markers carrying a per-process
 * nonce, and every fetch goes through {@code Fetch}'s address check. The runner takes its drive and its tools
 * through two small interfaces so a test can script both without a model or a network.
 */
public final class Researcher {

    /** One chat turn against the drive: the OpenAI-shaped assistant message, tool_calls and all. */
    public interface Drive {
        ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice);
        /** A deterministic prose completion (temperature 0, thinking off); "" on failure. */
        String classify(ArrayNode messages, int maxTokens);
        int contextWindow();
    }

    /** A fresh set of research tools for one worker, focused on its sub-question. */
    public interface Tools {
        /** The web tools (search, fetch) for this worker. */
        List<Tool> web(String focus);
        /** The search steerer's stop rule for this worker's tools, or {@code () -> false}. */
        BooleanSupplier exhausted();
    }

    /** What the daemon hands over. {@code subQuestions} may be empty (then the runner decomposes). */
    /**
     * {@code maxTurns} and {@code maxMinutes} are the person's ceilings, either, both or neither (0 = none):
     * "up to 600 turns", "two hours tops". With neither the run goes until the work is done — every planned
     * sub-question, the critic's rounds, every section, every cited sentence checked.
     */
    public record Ask(String question, String mode, int maxTurns, List<String> subQuestions, String sources, List<String> collections, int maxMinutes) {
        public Ask {
            maxTurns = Math.max(0, maxTurns); maxMinutes = Math.max(0, maxMinutes);
            subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
            mode = mode == null || mode.isBlank() ? "broad" : mode;
            sources = sources == null || sources.isBlank() ? "both" : sources;   // both (shelves first) | shelves | web
            collections = collections == null ? List.of() : List.copyOf(collections);
        }
        public Ask(String question, String mode, int maxTurns, List<String> subQuestions) { this(question, mode, maxTurns, subQuestions, "both", List.of(), 0); }
        public Ask(String question, String mode, int maxTurns, List<String> subQuestions, String sources, List<String> collections) { this(question, mode, maxTurns, subQuestions, sources, collections, 0); }
        String ceilings() {
            if (maxTurns == 0 && maxMinutes == 0) return "no ceiling (runs to completion)";
            return (maxTurns > 0 ? maxTurns + " turns" : "") + (maxTurns > 0 && maxMinutes > 0 ? ", " : "") + (maxMinutes > 0 ? maxMinutes + " min" : "");
        }
        boolean web() { return !sources.equals("shelves"); }
        boolean shelves() { return !sources.equals("web"); }
    }

    /**
     * The run's record. {@code answer} is the synthesis (sections joined); {@code evidence} is every
     * worker's sub-question, summary and notes verbatim — the WORK, kept even when the synthesis is
     * thin. {@code done} is whether the synthesis finished by its own hand (the acquisitions gate asks).
     */
    public record Result(boolean done, String answer, String evidence, int turnsUsed, int subQuestions,
                         int rounds, List<String> log, List<String> openQuestions) {
        public Result(boolean done, String answer, String evidence, int turnsUsed, int subQuestions, int rounds, List<String> log) {
            this(done, answer, evidence, turnsUsed, subQuestions, rounds, log, List.of());
        }
        /** A short account for the job ledger. */
        public String summary() {
            return "turns " + turnsUsed + " · " + subQuestions + " sub-question(s) · " + rounds + " round(s)"
                    + (done ? "" : " · synthesis cut off at the deadline");
        }
    }

    private static final ObjectMapper J = new ObjectMapper();
    static final int WORKERS = org.researchzosho.Config.getInt("RESEARCHZOSHO_RESEARCH_WORKERS", 3);
    static final int WORKER_TURNS = org.researchzosho.Config.getInt("RESEARCHZOSHO_RESEARCH_WORKER_TURNS", 14);
    static final int ROUNDS = org.researchzosho.Config.getInt("RESEARCHZOSHO_RESEARCH_ROUNDS", 2);
    static final int SYNTH_TURNS = 8;
    /** The cite-check's own turns: with none reserved it read zero sentences on a spent budget (measured, J-0007). */
    static final int CHECK_TURNS = 6;
    /** Turns kept back from the workers: the synthesis, its closing turn and the cite-check. */
    static final int RESERVE = SYNTH_TURNS + 1 + CHECK_TURNS;
    static final int MIN_WORKER_TURNS = 4;
    /** A second-round worker needs this long to fetch and read, not only search. */
    static final int MIN_ROUND_TWO_MINUTES = 4;
    /** Second-round sub-question → pages the first round named and did not read; the worker starts by fetching them. */
    private final Map<String, List<String>> seedsOf = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Pages the evidence names beside the words of {@code sub}: a first-round worker that could not fetch a source
     * usually names it ("the PDF … https://… was not fetched"), and the critic then asks for exactly that. Up to four
     * URLs from the lines that share the most distinctive words with the sub-question.
     */
    static List<String> seedsFor(String sub, String evidence) {
        Set<String> st = new java.util.HashSet<>();
        for (String w : Frontier.terms(sub)) if (w.length() >= 5) st.add(w);
        Map<String, Integer> score = new LinkedHashMap<>();
        java.util.regex.Pattern url = java.util.regex.Pattern.compile("https?://[^\\s)\\]>\"']+");
        for (String line : evidence.split("\n")) {
            java.util.regex.Matcher m = url.matcher(line);
            if (!m.find()) continue;
            int shared = 0; for (String w : Frontier.terms(line)) if (st.contains(w)) shared++;
            if (shared < 2) continue;
            m.reset();
            while (m.find()) { String u = m.group().replaceAll("[.,;:]+$", ""); score.merge(u, shared, Math::max); }
        }
        List<String> out = new ArrayList<>(score.keySet());
        out.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        return out.size() > 4 ? new ArrayList<>(out.subList(0, 4)) : out;
    }
    /** What a worker's bounces cost beyond its cap (the note bounce is two turns and most workers take it — J-0007). */
    static final int BOUNCE_TURNS = 2;
    static final boolean PERSPECTIVES = !"off".equalsIgnoreCase(org.researchzosho.Config.get("RESEARCHZOSHO_PERSPECTIVES", "on"));
    static final int PAGE_CHARS = 3000;   // search, fetch, note, done — less is a summary of snippets
    static final int MAX_SUB = 8;
    static final int OBSERVATION_CAP = 6_000;

    /**
     * A long observation keeps its head AND its tail: the conclusion of a page and the last line of an error
     * are at the end, and a tail-only cut lost both (taken from codex's truncate.rs, 2026-09-08).
     */
    static String cut(String observation) {
        if (observation.length() <= OBSERVATION_CAP) return observation;
        int half = OBSERVATION_CAP / 2;
        int lost = observation.length() - OBSERVATION_CAP;
        return observation.substring(0, half) + "\n…[" + lost + " characters cut from the middle]…\n" + observation.substring(observation.length() - half);
    }
    static final int WORKER_TIMEOUT_MIN = org.researchzosho.Config.getInt("RESEARCHZOSHO_RESEARCH_WORKER_MINUTES", 40);

    private final Drive drive;     // the workers' seat: search, fetch, note — local labour
    private final Drive judge;     // the judgment seat: plan, perspectives, critic, synthesis, cite-check — a frontier model when configured
    /** The language lanes of the current run: sub-question → lane, and which lanes were sent round again. */
    private final Map<String, Lanes.Lane> laneOf = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Lanes.Lane> lanes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.Set<String> laneRetried = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> laneRan = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Tools tools;
    private final Consumer<String> log;
    /** Set by the daemon for a job: true when a person asked for the run to stop; checked before every turn, on every worker. */
    private volatile java.util.function.BooleanSupplier stopWhen = () -> false;
    public void stopWhen(java.util.function.BooleanSupplier s) { this.stopWhen = s == null ? () -> false : s; }
    /** Thrown at a turn boundary when the run was stopped; carries no evidence, the job ledger says who stopped it. */
    public static final class Stopped extends RuntimeException { public Stopped() { super("stopped by the person"); } }
    private final int workers;
    private final LibraryStore store;   // for the cite-check's raw captures and independence clusters; null in a bare unit test
    /** Where the run stands, for the job record: set by the daemon; a client reads it to know when to poll again. */
    private volatile Consumer<ObjectNode> onProgress = null;
    public void onProgress(Consumer<ObjectNode> sink) { this.onProgress = sink; }
    private volatile String phase = "";
    private volatile int round = 0, workersTotal = 0;
    private final java.util.concurrent.atomic.AtomicInteger workersDone = new java.util.concurrent.atomic.AtomicInteger();
    private volatile Budget currentBudgetForProgress = null;
    /** Publish the run's state: the phase, the round, workers finished of this round, turns used of the ceiling. */
    private void progress(String phase) {
        this.phase = phase;
        Consumer<ObjectNode> sink = onProgress;
        if (sink == null) return;
        Budget b = currentBudgetForProgress;
        ObjectNode o = J.createObjectNode();
        o.put("phase", phase);
        o.put("round", round); o.put("rounds", ROUNDS);
        o.put("workers_done", workersDone.get()); o.put("workers_total", workersTotal);
        o.put("turns_used", b == null ? 0 : b.used()); o.put("turns_ceiling", b == null ? 0 : b.ceiling());
        o.put("at", java.time.Instant.now().toString());
        try { sink.accept(o); } catch (Exception ignored) { }
    }
    private final List<String> unaffordableFromPlan = java.util.Collections.synchronizedList(new ArrayList<>());
    private java.util.Set<String> wallsSeenAtStart = null;
    private final java.util.concurrent.atomic.AtomicInteger readsInRun = new java.util.concurrent.atomic.AtomicInteger();   // shelf pages read this run — sources too
    private final java.util.Set<String> fetchedInRun = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());   // every source a worker read this run

    public Researcher(Drive drive, Tools tools, Consumer<String> log) {
        this(drive, drive, tools, log, 0, null);
    }

    public Researcher(Drive drive, Drive judge, Tools tools, Consumer<String> log, LibraryStore store) {
        this(drive, judge, tools, log, 0, store);
    }

    Researcher(Drive drive, Tools tools, Consumer<String> log, int workers) {
        this(drive, drive, tools, log, workers, null);
    }

    Researcher(Drive drive, Drive judge, Tools tools, Consumer<String> log, int workers, LibraryStore store) {
        this.drive = drive; this.judge = judge == null ? drive : judge; this.tools = tools; this.log = log == null ? s -> { } : log;
        this.workers = Math.max(0, workers); this.store = store;
        this.throttle = new ResearchSettings.Throttle(this.log);
    }

    /** Parallel workers now: the constructor's number (tests), else the live setting {@code research.workers}. */
    int workersNow() { return workers > 0 ? workers : ResearchSettings.workers(); }
    private final ResearchSettings.Throttle throttle;

    /**
     * The judgment seat for a library: RESEARCHZOSHO_JUDGE_DRIVE (+ RESEARCHZOSHO_JUDGE_MODEL) when set — a
     * frontier endpoint for the steps where judgment is the work — else the workers' drive. The operator,
     * 2026-09-07: "remember we can use frontier".
     */
    public static Drive judgeDrive(String workersDrive, String workersModel) {
        String jd = org.researchzosho.Config.get("RESEARCHZOSHO_JUDGE_DRIVE");
        if (jd == null || jd.isBlank()) return drive(workersDrive, workersModel);
        return drive(jd, org.researchzosho.Config.get("RESEARCHZOSHO_JUDGE_MODEL", workersModel));
    }

    // ---- the run ----

    /** Run one ask. {@code known} is the library's context block for the question ("" when none). */
    public Result run(Ask ask, String known) {
        Budget budget = new Budget(ask.maxTurns(), ask.maxMinutes());
        currentBudget = budget;
        synchronized (org.researchzosho.tools.WebFetchTool.WALLS) { wallsSeenAtStart = new java.util.HashSet<>(org.researchzosho.tools.WebFetchTool.WALLS); }
        List<String> notes = new ArrayList<>();
        String knownBlock = known == null ? "" : known;

        // 1. Plan
        currentBudgetForProgress = budget;
        round = 0; workersTotal = 0; workersDone.set(0);
        progress("planning");
        List<String> open = plan(ask, knownBlock, budget);
        int subCount = open.size();
        log.accept("plan: " + open.size() + " sub-question(s), " + workersNow() + " worker(s), " + ask.ceilings());

        // 2/3. Rounds of workers, the critic between them
        List<String> evidence = new ArrayList<>();
        List<String> unaffordable = new ArrayList<>();
        unaffordableFromPlan.clear();
        int rounds = 0;
        seedsOf.clear();
        for (int round = 1; round <= ROUNDS && !open.isEmpty(); round++) {
            rounds = round;
            // With a deadline and a second round possible, the first round stops at three fifths of the time, so the
            // critic's questions can be READ, not just searched: on a 60-minute run the first round's six workers took
            // 27 minutes and the write-up's reserve then covered the rest, and the four second-round workers got one
            // search each (dolores, I-0002, 2026-09-11).
            if (round == 1 && ROUNDS > 1 && budget.deadlineMs() > 0) budget.roundDeadline(System.currentTimeMillis() + (budget.deadlineMs() - System.currentTimeMillis()) * 3 / 5);
            else budget.roundDeadline(0);
            this.round = round; workersTotal = open.size(); workersDone.set(0);
            progress("workers");
            evidence.addAll(investigateAll(ask, open, budget));
            open.clear();
            if (round == ROUNDS || budget.workersTimeUp() || (!budget.unbounded() && budget.left() <= budget.reserve())) break;
            progress("critic");
            List<String> missing = critic(ask, evidence, budget);
            if (missing.isEmpty()) { log.accept("critic: coverage sufficient after round " + round); break; }
            // A second round needs room to search AND read: measured (J-0011) three round-2 workers got two
            // turns each — one search, one summary from snippets — which is worse than an honest gap.
            int share = budget.unbounded() ? Integer.MAX_VALUE : (budget.left() - budget.reserve()) / Math.max(1, missing.size());
            if (share < MIN_WORKER_TURNS + BOUNCE_TURNS) {
                unaffordable.addAll(missing);
                log.accept("critic: " + missing.size() + " gap(s) but " + budget.left() + " turns left — recorded as open questions, not researched thinly");
                break;
            }
            double minutesEach = budget.minutesLeftForWorkers() / Math.max(1, (missing.size() + workersNow() - 1) / workersNow());
            if (minutesEach < MIN_ROUND_TWO_MINUTES) {
                unaffordable.addAll(missing);
                log.accept("critic: " + missing.size() + " gap(s) but " + String.format("%.0f", budget.minutesLeftForWorkers()) + " minute(s) left for workers — recorded as open questions, not researched thinly");
                break;
            }
            for (String q : missing) { List<String> seeds = seedsFor(q, String.join("\n", evidence)); if (!seeds.isEmpty()) { seedsOf.put(q, seeds); log.accept("round " + (round + 1) + ": \"" + Acquisitions.compress(q, 50) + "\" starts from " + seeds.size() + " page(s) named in round " + round); } }
            open.addAll(missing);
            subCount += missing.size();
            log.accept("critic: " + missing.size() + " gap(s) → round " + (round + 1));
        }

        // 4. Synthesis
        progress("synthesis");
        String evidenceText = String.join("\n\n", evidence);
        Synthesis syn = synthesize(ask, knownBlock, evidence, budget);
        notes.add("synthesis: " + (syn.done ? "finished" : "cut off") + ", " + syn.sections.size() + " section(s)");
        log.accept(notes.get(notes.size() - 1));
        // 5. The harness's own sections: references numbered and clustered for independence, the evidence table,
        //    and the cite-check of the model's sentences against the captured sources.
        progress("cite-check");
        String answer = assemble(ask, syn, evidenceText, budget, notes);
        progress("filing");
        List<String> openAll = new ArrayList<>(unaffordableFromPlan); openAll.addAll(unaffordable);
        return new Result(syn.done, answer, evidenceText, budget.used(), subCount, rounds, List.copyOf(notes), List.copyOf(openAll));
    }

    // ---- the shelf ----

    /** What filing an ask produced: the investigation id when admitted, else the gate's reason. */
    public record Filed(String investigationId, String reason, Result result) {
        public boolean admitted() { return investigationId != null; }
    }

    /**
     * Run an ask and put it on the shelf through the acquisitions gate. The evidence goes into the
     * investigation with the answer, so a cut-off synthesis still leaves the work on record; a
     * refused run leaves a frontier gap. {@code writer} is who asked ("patron:…", "crew:explorer").
     */
    public static Filed file(LibraryStore store, Researcher researcher, Ask ask, String writer) throws java.io.IOException {
        var snap = Acquisitions.Snapshot.take();
        Result r = researcher.run(ask, LibraryPush.block(store, ask.question(), 6));
        boolean answered = r.done() || !r.answer().isBlank();
        // sources READ = web fetches this run + shelf documents read page by page (a shelves-only ask fetches nothing)
        var gate = Acquisitions.gate(answered, r.answer(), r.evidence(), snap.fetchesSince() + researcher.readsInRun.get(), snap.degradedSince());
        if (!gate.admitted()) {
            Acquisitions.refuse(store, ask.question(), gate.reason());
            return new Filed(null, gate.reason(), r);
        }
        String answer = r.done() ? r.answer()
                : "(the synthesis was cut off at its deadline — the sections it wrote, then the evidence)\n\n" + r.answer();
        var inv = Acquisitions.admit(store, new LibrarianIndex(store), ask.question(), answer, writer, r.evidence());
        if (!r.openQuestions().isEmpty()) {
            store.write(new Investigation(inv.id(), inv.title(), inv.state(), inv.writer(), inv.recordedAt(), inv.findings(), r.openQuestions(), inv.body()));
            Frontier.fromReport(store, inv.id(), r.openQuestions());   // once; parked unless RESEARCHZOSHO_REPORT_QUESTIONS=queued
        }
        return new Filed(inv.id(), "", r);
    }

    // ---- 5. the harness's sections ----

    /** locator → the rest of a note line; every source the workers noted, in first-seen order. */
    static List<String> notedSources(String evidence) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("— source: (\\S+?)(?: \\(|\\s—|\\n|$)").matcher(evidence);
        while (m.find()) { String loc = m.group(1).replaceAll("[),.;]+$", ""); if (!out.contains(loc)) out.add(loc); }
        for (String u : Acquisitions.urls(evidence)) if (!out.contains(u)) out.add(u);
        return out;
    }

    /** ", 6 days ago" for a page published within a month, so the reader sees how new it is; no verdict — for news, new is the point. */
    static String ageNote(String published) {
        try {
            long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(published), java.time.LocalDate.now());
            return days >= 0 && days <= 30 ? ", " + days + " day" + (days == 1 ? "" : "s") + " ago" : "";
        } catch (Exception e) { return ""; }
    }

    /** "## Languages of the sources": which languages the notes came from, and a lane that found nothing says so. */
    String languagesSection(String evidence) {
        Map<String, Integer> read = Lanes.languagesRead(evidence);
        if (read.isEmpty() && lanes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## Languages of the sources\n\n").append(Lanes.describe(read)).append(" (notes per language).");
        for (Lanes.Lane lane : lanes) {
            if (read.getOrDefault(lane.code(), 0) > 0) sb.append(" ").append(lane.name()).append("-language sources were searched on their own lane.");
            else if (!laneRan.contains(lane.code())) sb.append(" The ").append(lane.name()).append("-language lane did not run within this ask's limits; what ").append(lane.name()).append(" sources say is an open question.");
            else sb.append(" No ").append(lane.name()).append("-language source was found").append(laneRetried.contains(lane.code()) ? " in two tries" : "").append("; what ").append(lane.name()).append(" sources say is an open question.");
        }
        return sb.toString();
    }

    /** The search counters when the run started; the differences are this run's. */
    final int unreachableAtStart = org.researchzosho.tools.WebSearchTool.UNREACHABLE.get(),
            fallbackAtStart = org.researchzosho.tools.WebSearchTool.FALLBACK_USED.get(),
            braveAtStart = org.researchzosho.tools.WebSearchTool.BRAVE_USED.get(),
            searxAtStart = org.researchzosho.tools.WebSearchTool.SEARXNG_USED.get();

    /** "## Web search": which backend the run searched through, when that is worth knowing: the fallback, or none at all. */
    String webSearchSection(Ask ask) {
        if (!ask.web()) return "";
        int none = org.researchzosho.tools.WebSearchTool.UNREACHABLE.get() - unreachableAtStart;
        int fb = org.researchzosho.tools.WebSearchTool.FALLBACK_USED.get() - fallbackAtStart;
        int brave = org.researchzosho.tools.WebSearchTool.BRAVE_USED.get() - braveAtStart;
        int searx = org.researchzosho.tools.WebSearchTool.SEARXNG_USED.get() - searxAtStart;
        if (fb > 0) return "## Web search\n\n" + fb + (fb == 1 ? " search" : " searches") + " went through the built-in fallback (Wikipedia, Crossref and OpenAlex: reference pages and papers, no web engine)"
                + (brave + searx > 0 ? ", " + (brave + searx) + " through " + (brave > 0 ? "Brave" : "SearXNG") : "")
                + ". A Brave Search API key or a SearXNG (`researchzosho search start`) searches the whole web.";
        if (none > 0 && brave + searx == 0) return "## Web search\n\nNo search backend answered at " + org.researchzosho.tools.WebSearchTool.endpoint() + " (" + none
                + (none == 1 ? " search" : " searches") + " failed). This run read only the documents on the shelves. "
                + "`researchzosho setup` adds a Brave Search API key or starts SearXNG.";
        return "";
    }

    String assemble(Ask ask, Synthesis syn, String evidence, Budget budget, List<String> notes) {
        String text = syn.text();
        String languages = languagesSection(evidence);
        String web = webSearchSection(ask);
        if (!web.isEmpty()) { languages = languages.isEmpty() ? web : web + "\n\n" + languages; notes.add("web search: no backend answered"); log.accept(notes.get(notes.size() - 1)); }
        if (!languages.isEmpty()) { notes.add("languages: " + Lanes.describe(Lanes.languagesRead(evidence))); log.accept(notes.get(notes.size() - 1)); }
        if (store == null) return languages.isEmpty() ? text : text + "\n\n" + languages;
        List<String> locators = new ArrayList<>();
        List<String> raw = notedSources(evidence);
        synchronized (fetchedInRun) { raw.addAll(fetchedInRun); }
        raw.addAll(Acquisitions.urls(text));
        for (String u : raw) {
            // "(file:///…/glossary.md):**" in the model's prose is the file, not a new locator; a file:// with no capture is nothing
            String loc = u.replaceAll("[)\\]*.,;:'\"]+$", "");
            if (loc.isEmpty() || locators.contains(loc)) continue;
            if (loc.startsWith("file://")) { try { if (RawCapture.find(store, loc) == null) continue; } catch (Exception e) { continue; } }
            locators.add(loc);
        }
        if (locators.isEmpty()) return text;
        // one reference per WORK: locators sharing a DOI / arXiv / PubMed id fold into one entry (an article page,
        // its PDF and its citation line are one source — measured live as three)
        // a shelved file noted by its bare name ("source: guardrails.md") is the file:// capture of that name, not a
        // second reference (measured, J-0009: eleven references for five files, and the cite-check mapped the bare one)
        Map<String, String> bareToFile = new HashMap<>();
        for (String loc : locators) if (loc.startsWith("file://")) bareToFile.put(loc.substring(loc.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT), loc);
        Map<String, List<String>> byWork = new LinkedHashMap<>();
        for (String loc : locators) {
            boolean bare = !loc.contains("://") && !loc.startsWith("cite:") && !loc.startsWith("raw/") && loc.matches("[^/\\s]+\\.[A-Za-z0-9]{1,5}");
            String file = bare ? bareToFile.get(loc.toLowerCase(java.util.Locale.ROOT)) : null;
            String canon = loc.startsWith("http") ? org.researchzosho.tools.Fetch.canonical(loc) : file != null ? file : loc;
            String ident = Citations.identifyCaptured(store, loc);
            List<String> group = byWork.computeIfAbsent(ident == null ? canon : ident, k -> new ArrayList<>());
            if (file != null && !group.contains(file)) group.add(0, file);   // the capture leads the group; the bare name follows
            if (!group.contains(loc)) group.add(loc);
        }
        // a capture that turned out to be a wall is not a reference
        byWork.values().removeIf(group -> {
            try {
                java.nio.file.Path rp = RawCapture.find(store, group.get(0));
                if (rp == null) return false;
                String[] r = RawCapture.read(rp);
                return org.researchzosho.tools.Fetch.wall(r[1], r[2]) != null;
            } catch (Exception e) { return false; }
        });
        List<String> primary = new ArrayList<>();
        for (List<String> group : byWork.values()) primary.add(group.stream().filter(l -> l.contains("://")).findFirst().orElse(group.stream().filter(l -> !l.startsWith("cite:")).findFirst().orElse(group.get(0))));
        Map<String, Integer> clusters = Independence.clusters(store, primary);
        int independent = Independence.independent(clusters);
        List<CiteCheck.Ref> refs = new ArrayList<>();
        StringBuilder references = new StringBuilder("## References\n\n");
        Map<Integer, Integer> firstOfCluster = new HashMap<>();
        Map<String, Integer> numberOfLocator = new HashMap<>();
        List<String> dated = new ArrayList<>();
        SourceRules rules = SourceRules.load(store);
        int n = 0;
        for (List<String> group : byWork.values()) {
            n++;
            String loc = primary.get(n - 1);
            Citations.Meta meta = Citations.resolve(store, loc, Citations.LIVE);
            String edition = meta == null ? "" : meta.edition();
            String title = "";
            try { java.nio.file.Path rp = RawCapture.find(store, loc); if (rp != null) title = Pages.unentity(RawCapture.read(rp)[1]); } catch (Exception ignored) { }
            for (String l : group) { numberOfLocator.put(l, n); if (edition.isEmpty() && l.startsWith("cite:")) edition = l.substring(5); }
            refs.add(new CiteCheck.Ref(n, loc, edition, title));
            int c = clusters.getOrDefault(loc, n);
            String same = firstOfCluster.containsKey(c) ? "  (same text as [" + firstOfCluster.get(c) + "])" : "";
            firstOfCluster.putIfAbsent(c, n);
            String published = "";
            try { java.nio.file.Path rp = RawCapture.find(store, loc); if (rp != null) published = RawCapture.published(rp); } catch (Exception ignored) { }
            if (!published.isEmpty()) { dated.add(published); }
            SourceRules.Rule rule = rules.ruleFor(loc);
            references.append('[').append(n).append("] ").append(edition.isEmpty() ? (title.isEmpty() ? "" : title + " — ") : edition + " — ").append(loc)
                      .append(published.isEmpty() ? "" : "  (published " + published + ageNote(published) + ")")
                      .append(rule == null ? "" : rule.kind().equals("trust") ? "  (a source you trust)" : "  (ON YOUR REFUSED LIST)");
            for (String l : group) if (!l.equals(loc) && !l.startsWith("cite:") && l.contains("://")) references.append("  also ").append(l);
            references.append(same).append('\n');
        }
        references.append("\n").append(byWork.size()).append(" source(s), ").append(independent).append(" independent (copies of one text count once).");
        if (!dated.isEmpty()) {
            java.util.Collections.sort(dated);
            references.append(" Dated sources run from ").append(dated.get(0)).append(" to ").append(dated.get(dated.size() - 1)).append(dated.size() < byWork.size() ? "; " + (byWork.size() - dated.size()) + " give no date" : "").append('.');
        }
        references.append('\n');
        // the evidence table: every note, mechanically, claim | source | quote
        StringBuilder table = new StringBuilder("## Evidence\n\n| claim | source | quote |\n|---|---|---|\n");
        int rows = 0;
        java.util.regex.Matcher nm = java.util.regex.Pattern.compile("^- (.+?) — source: (.+?)(?: — quote: \"(.*)\")?$", java.util.regex.Pattern.MULTILINE).matcher(evidence);
        while (nm.find() && rows < 120) {
            String loc = nm.group(2).replaceAll("[),.;]+$", "");
            int ref = 0;
            java.util.regex.Matcher um = java.util.regex.Pattern.compile("(?:https?|file)://\\S+").matcher(loc);
            String url = um.find() ? um.group().replaceAll("[),.;]+$", "") : null;
            if (url != null && numberOfLocator.containsKey(url)) ref = numberOfLocator.get(url);
            if (ref == 0 && numberOfLocator.containsKey(loc)) ref = numberOfLocator.get(loc);   // a bare file name, folded into its capture's group
            String noteId = Citations.identifyCaptured(store, url != null ? url : loc);
            if (ref == 0 && url != null && numberOfLocator.containsKey(org.researchzosho.tools.Fetch.canonical(url))) ref = numberOfLocator.get(org.researchzosho.tools.Fetch.canonical(url));
            if (ref == 0 && noteId != null) for (String l : numberOfLocator.keySet()) if (noteId.equals(Citations.identify(l))) { ref = numberOfLocator.get(l); break; }
            if (ref == 0) for (CiteCheck.Ref r : refs) if (loc.startsWith(r.locator())) { ref = r.n(); break; }
            table.append("| ").append(cell(nm.group(1))).append(" | ").append(ref > 0 ? "[" + ref + "]" : cell(loc)).append(" | ").append(nm.group(3) == null ? "" : cell(nm.group(3))).append(" |\n");
            rows++;
        }
        // the cite-check: the model's cited sentences against the captured sources, on the judge
        List<String> pieces = new ArrayList<>(java.util.Arrays.asList(evidence.split("(?m)^(?=SUB-QUESTION: )")));
        pieces.removeIf(String::isBlank);
        List<String> coverageFlags = coverageCheck(text, pieces);
        for (String f : coverageFlags) { notes.add("coverage: " + f); log.accept(notes.get(notes.size() - 1)); }
        CiteCheck.Outcome cc = CiteCheck.run(store, text, refs, judge, budget);
        String checked = "cite-check: " + cc.checked() + " cited sentence(s) read against their source — " + cc.supported() + " supported, "
                + cc.unsupported() + " not supported (marked), " + (cc.checked() - cc.supported() - cc.unsupported()) + " undecidable from the excerpt; "
                + cc.unmapped() + " parenthetical(s) named no source (an aside, not a citation, counts here); " + byWork.size() + " reference(s)";
        notes.add(checked);
        log.accept(checked);
        StringBuilder out = new StringBuilder(cc.text());
        if (!cc.problems().isEmpty()) {
            out.append("\n\n## Cite-check\n\n").append(checked).append(".\n");
            for (String p : cc.problems()) out.append("- ").append(p).append('\n');
        }
        if (!coverageFlags.isEmpty()) {
            out.append("\n\n## Coverage check\n\nThe answer claims an absence that the sub-investigations do not support:\n\n");
            for (String f : coverageFlags) out.append("- ").append(f).append('\n');
        }
        if (rows > 0) out.append("\n\n").append(table);
        references.append(checked).append(".\n");
        out.append("\n\n").append(references);
        if (!languages.isEmpty()) out.append("\n\n").append(languages);
        List<String> walls = new ArrayList<>();
        synchronized (org.researchzosho.tools.WebFetchTool.WALLS) { for (String w : org.researchzosho.tools.WebFetchTool.WALLS) if (wallsSeenAtStart == null || !wallsSeenAtStart.contains(w)) walls.add(w); }
        if (!walls.isEmpty()) {
            out.append("\n\n## Source requests\n\nThese answered with a wall instead of the page. If you hold the document or the access, "
                    + "`researchzosho add <file> --for <url>` supplies it and the shelves re-check against it.\n\n");
            for (String w : walls) out.append("- ").append(w).append('\n');
        }
        return out.toString();
    }

    private static String cell(String s) { return Acquisitions.compress(s == null ? "" : s.replace("|", "\\|").replace("\n", " "), 220); }

    // ---- 1. plan ----

    List<String> plan(Ask ask, String known, Budget budget) {
        List<String> open = planCore(ask, known, budget);
        laneOf.clear(); lanes.clear(); laneRetried.clear(); laneRan.clear();
        if (!ask.web()) return open;   // a shelves-only ask has no web to search in another language
        List<Lanes.Lane> found = Lanes.detect(ask.question(), null);   // the table decides; the judge only writes seeds, one turn, when there is a lane
        if (!found.isEmpty() && budget.take()) found = Lanes.detect(ask.question(), judge);
        for (Lanes.Lane lane : found) {
            String sub = lane.subQuestion(ask.question());
            if (open.stream().anyMatch(o -> o.equalsIgnoreCase(sub))) continue;
            if (open.size() >= MAX_SUB) open.remove(open.size() - 1);   // the lane outranks the last decomposition item
            open.add(0, sub);   // FIRST: a budget cut drops from the tail, and the lane is the one sub-question that must run (J-0015 dropped it)
            laneOf.put(sub, lane); lanes.add(lane);
            log.accept("lane: " + lane.name() + "-language sources" + (lane.seeds().isEmpty() ? " (no seed queries)" : " — seeds: " + String.join(" · ", lane.seeds())));
        }
        return open;
    }

    List<String> planCore(Ask ask, String known, Budget budget) {
        List<String> open = new ArrayList<>();
        for (String s : ask.subQuestions()) if (s != null && !s.isBlank() && open.size() < MAX_SUB) open.add(s.strip());
        if (!open.isEmpty()) return open;
        // STORM's move first: who studies this, and what would each of them insist on asking
        if (PERSPECTIVES && budget.take()) {
            List<Perspectives.Perspective> ps = Perspectives.discover(ask.question(), judge, tools, 5);
            if (!ps.isEmpty()) {
                open.addAll(Perspectives.questions(ps, MAX_SUB));
                log.accept("plan: " + ps.size() + " perspective(s): " + String.join(" · ", ps.stream().map(Perspectives.Perspective::name).toList()));
                return open;
            }
        }
        if (budget.take()) {
            try {
                ArrayNode msgs = J.createArrayNode();
                msgs.addObject().put("role", "user").put("content",
                        known
                        + "Decompose this research question into 3-8 SELF-CONTAINED sub-questions that could each "
                        + "be researched independently by someone who sees nothing else. Cover every facet; where "
                        + "the question asks the same facts about many items, group items into a few sub-questions "
                        + "rather than one each. When the question names languages, regions or a non-English "
                        + "literature, include sub-questions whose searches should be written in that language, and "
                        + "say so in the sub-question. Skip anything the library block above already settles. "
                        + "Answer with a JSON array of strings and nothing else.\n\nQUESTION:\n" + ask.question());
                String raw = judge.classify(msgs, 1200);
                int a = raw.indexOf('['), b = raw.lastIndexOf(']');
                if (a >= 0 && b > a) {
                    for (JsonNode q : J.readTree(raw.substring(a, b + 1)))
                        if (q.isTextual() && !q.asText().isBlank() && open.size() < MAX_SUB) open.add(q.asText().strip());
                }
            } catch (Exception e) {
                log.accept("plan: decompose unparseable (" + e.getMessage() + ") — the question is its own sub-question");
            }
        }
        if (open.isEmpty()) open.add(ask.question());
        return open;
    }

    // ---- 2. workers ----

    private List<String> investigateAll(Ask ask, List<String> open, Budget budget) {
        // The round's cap is fixed HERE, once: a cap read at each worker's start shrank as parallel workers
        // spent the shared budget, and the last-scheduled workers got two turns (measured, J-0004). What the
        // budget cannot afford at MIN_WORKER_TURNS each is not researched thinly — it goes to the open questions.
        int affordable = budget.unbounded() ? open.size() : Math.max(1, (budget.left() - budget.reserve()) / (MIN_WORKER_TURNS + BOUNCE_TURNS));
        if (open.size() > affordable) {
            List<String> dropped = new ArrayList<>(open.subList(affordable, open.size()));
            log.accept("plan: " + dropped.size() + " sub-question(s) beyond the budget — recorded as open questions");
            unaffordableFromPlan.addAll(dropped);
            open = new ArrayList<>(open.subList(0, affordable));
        }
        budget.roundCap(budget.unbounded() ? WORKER_TURNS : Math.min(WORKER_TURNS, Math.max(MIN_WORKER_TURNS, (budget.left() - budget.reserve()) / Math.max(1, open.size()) - BOUNCE_TURNS)));
        // every sub-question gets a thread; the THROTTLE decides how many turns run at once, live (research.workers, the drive's speed)
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(MAX_SUB, open.size())));
        long roundMinutes = (long) WORKER_TIMEOUT_MIN * Math.max(1, (open.size() + workersNow() - 1) / workersNow());
        List<Future<String>> futures = new ArrayList<>();
        List<String> out = new ArrayList<>();
        budget.expectWorkers(open.size());
        try {
            for (String sub : open) futures.add(pool.submit(() -> { workersActive.incrementAndGet(); try { return investigate(ask, sub, budget); } finally { workersActive.decrementAndGet(); } }));
            pool.shutdown();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    String found = futures.get(i).get(roundMinutes, TimeUnit.MINUTES);
                    if (found == null) {
                        // the budget was gone before its first turn: an open question, never a summary from memory
                        unaffordableFromPlan.add(open.get(i));
                        log.accept("worker \"" + Acquisitions.compress(open.get(i), 50) + "\": no turns left — recorded as an open question");
                    } else {
                        out.add(found);
                    }
                    workersDone.incrementAndGet(); progress("workers");
                } catch (Exception e) {
                    if (e.getCause() instanceof Stopped s) throw s;
                    workersDone.incrementAndGet(); progress("workers");
                    out.add("SUB-QUESTION: " + open.get(i) + "\nSUMMARY: unavailable (worker "
                            + (e instanceof java.util.concurrent.TimeoutException ? "timed out" : "failed: " + e.getMessage()) + ")");
                    log.accept("worker: " + Acquisitions.compress(open.get(i), 60) + " — " + e.getClass().getSimpleName());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /**
     * One worker: a fresh context, its own tools, notes as it goes. Never throws — the evidence survives.
     * Returns null when the budget was spent before its first turn: a worker with no turn to read anything
     * answered from memory (measured, J-0007: one turn, {@code done} only, a 444-character summary of nothing).
     */
    String investigate(Ask ask, String sub, Budget budget) {
        Notebook notebook = new Notebook();
        DoneTool done = new DoneTool("done",
                "Finish this sub-investigation. Pass a short SUMMARY of what the sources established and what "
                + "stayed unknown. Everything you noted is already kept — do not repeat it.");
        Map<String, Tool> byName = new LinkedHashMap<>();
        if (store != null && ask.shelves()) byName.put("shelf_search", new ShelfSearchTool(store, ask.collections()));
        if (ask.web()) for (Tool t : tools.web(sub)) byName.put(t.name(), t);
        if (store != null) byName.put("read_pages", new PagesTool(store));
        byName.put(notebook.name(), notebook);
        byName.put(done.name(), done);
        BooleanSupplier exhausted = ask.web() ? tools.exhausted() : () -> false;

        Lanes.Lane lane = laneOf.get(sub);
        if (lane != null) laneRan.add(lane.code());
        ArrayNode history = J.createArrayNode();
        history.addObject().put("role", "system").put("content", (lane == null ? "" : lane.register()) + workerRegister(ask));
        List<String> seeds = seedsOf.getOrDefault(sub, List.of());
        String seedBlock = seeds.isEmpty() ? "" : "\n\nPAGES NAMED IN THE FIRST ROUND AND NOT YET READ — start with web_fetch on these, and search only for what they do not settle:\n- " + String.join("\n- ", seeds);
        history.addObject().put("role", "user").put("content",
                "RESEARCH QUESTION (the whole ask, for context):\n" + ask.question()
                + "\n\nYOUR SUB-QUESTION — research THIS, and only this:\n" + sub + seedBlock
                + "\n\n" + (seeds.isEmpty() ? "Start with " : "Then, if needed, ") + (ask.shelves() && store != null ? "shelf_search" + (ask.web() ? ", then web_search" : "") : "web_search") + ". Note every fact the moment a fetched source shows it.");
        ArrayNode all = toolsArray(byName.values());
        ArrayNode onlyDone = toolsArray(List.of(done));
        ArrayNode noteAndDone = toolsArray(List.of(notebook, done));
        boolean closingNoteOffered = false;
        int cap = budget.roundCap() > 0 ? budget.roundCap() : Math.min(WORKER_TURNS, Math.max(2, budget.perWorkerCap()));
        Map<String, Integer> spins = new HashMap<>();
        Map<String, Integer> calls = new LinkedHashMap<>();
        boolean finished = false, bounced = false, noteBounced = false;
        int fetchedHere = 0;
        int turnsRun = 0;
        int nctx = drive.contextWindow();
        for (int turn = 1; turn <= cap && !finished; turn++) {
            boolean lastByCap = turn == cap;
            boolean lastByBudget = !budget.takeWorker();
            boolean early = turn >= 3 && exhausted.getAsBoolean();
            boolean deadline = lastByCap || lastByBudget || early;
            if (lastByBudget) {
                if (turn == 1) return null;
                // The shared budget is spent: this turn is a gift so the worker can close honestly.
                if (!budget.grace()) break;
            }
            // A worker cut off with sources read and nothing noted gets ONE closing turn with note beside done — the
            // notes are the evidence table, the references and the cite-check (measured, J-0011: seven of eight
            // workers under a time ceiling closed with zero notes, because the deadline turn offered only done).
            boolean closingNote = deadline && fetchedHere > 0 && notebook.isEmpty() && !closingNoteOffered;
            if (closingNote) { closingNoteOffered = true; if (turn == cap) cap++; }
            if (deadline) {
                history.addObject().put("role", "user").put("content", closingNote
                        ? "Your turns are ending. First call note for each fact you will rely on (claim, source, quote) — several notes in one turn are fine — then call done with your summary."
                        : early
                        ? "The searches stopped surfacing new sources. Call done now with your summary."
                        : "This is your last turn. Call done now with your summary of what the sources established.");
            }
            trimHistory(history, nctx);
            ObjectNode assistant;
            try {
                turnsRun++;
                assistant = chat(history, deadline ? (closingNote ? noteAndDone : onlyDone) : all, outBudget(history, nctx));
            } catch (Stopped e) {
                throw e;   // a person stopped the run: out of the worker, out of the round, out of the run
            } catch (Exception e) {
                log.accept("worker: drive failed on turn " + turn + " (" + e.getMessage() + ")");
                break;
            }
            if (assistant == null) break;
            history.add(assistant);
            JsonNode toolCalls = assistant.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                history.addObject().put("role", "user").put("content", "Act by calling a tool.");
                continue;
            }
            for (JsonNode call : toolCalls) {
                String name = call.path("function").path("name").asText();
                String id = call.path("id").asText("call_" + turn);
                JsonNode args = parseArgs(call.path("function").path("arguments"));
                String observation;
                Tool t = byName.get(name);
                if (deadline && !done.name().equals(name) && !(closingNote && notebook.name().equals(name))) {
                    observation = "Only `done` is available on this turn.";
                } else if (t == null) {
                    observation = "ERROR: no tool named " + name + ". Available: " + String.join(", ", byName.keySet());
                } else if (!done.name().equals(name) && spins.merge(name + " " + args, 1, Integer::sum) > 1) {
                    observation = "You already ran exactly this call; the result has not changed. Do something different: "
                            + "another query, another source, or note what you have and move on.";
                } else {
                    calls.merge(name, 1, Integer::sum);
                    try {
                        observation = t.execute(args);
                    } catch (Exception e) {
                        observation = "ERROR: " + name + " failed — " + e.getMessage();
                    }
                }
                if (observation == null) observation = "";
                observation = cut(observation);
                if (lane != null && "web_search".equals(name) && !observation.startsWith("ERROR") && !lane.inLanguage(args.path("query").asText(""))) {
                    observation += lane.wrongLanguageNote();   // evidence, not a gate: the results stand, and the worker is told what they are
                }
                if (done.name().equals(name)) {
                    // An empty summary on top of an empty notebook is a worker that leaves NOTHING (measured
                    // live: two of five workers, their tool call truncated). One bounce, one extra turn.
                    if (done.summary().isBlank() && notebook.isEmpty() && !bounced) {
                        bounced = true; cap++;
                        observation = "done needs the summary: write what the sources established, each fact with its "
                                + "source, and what stayed unknown. Call done again with it.";
                    } else if (notebook.isEmpty() && fetchedHere > 0 && !noteBounced && !deadline) {
                        // Read sources and noted nothing (measured live: five of eight workers) — the notes are what
                        // the evidence table, the references and the cite-check are built from. One bounce, two turns.
                        noteBounced = true; cap += 2;
                        observation = "You read " + fetchedHere + " source(s) and noted nothing. Call note for each fact you will rely on "
                                + "(claim, source URL, quote), then done again.";
                    } else {
                        finished = true;
                    }
                } else if ("read_pages".equals(name) && !observation.startsWith("ERROR")) {
                    fetchedHere++;   // a shelf document read is a source read: the note discipline and the gate count it
                    readsInRun.incrementAndGet();
                } else if ("web_fetch".equals(name) && !observation.startsWith("ERROR")) {
                    fetchedHere++;
                    String src = observation.startsWith("source: ") ? observation.substring(8).split(" — |\\n", 2)[0].strip() : "";
                    if (!src.isEmpty()) fetchedInRun.add(src);
                    observation += "\n\n(Before your next search: note each fact this source showed — note(claim, source, quote). "
                            + "A fact you do not note is lost when your turns run out. To read more of this page, fetch it again "
                            + "with a different `find`.)";
                }
                ObjectNode toolMsg = history.addObject();
                toolMsg.put("role", "tool"); toolMsg.put("tool_call_id", id); toolMsg.put("content", observation);
            }
        }
        log.accept("worker \"" + Acquisitions.compress(sub, 50) + "\": " + turnsRun + " turn(s), calls " + calls
                + ", " + notebook.size() + " note(s), summary " + done.summary().length() + " chars, "
                + (finished ? "finished" : "cut off"));
        StringBuilder sb = new StringBuilder();
        sb.append("SUB-QUESTION: ").append(sub).append('\n');
        sb.append("SUMMARY: ").append(finished ? done.summary() : "(the worker reached its turn budget before finishing; the evidence below is what it established)").append('\n');
        sb.append("EVIDENCE:\n").append(notebook.isEmpty() ? "(nothing noted)" : notebook.render());
        return sb.toString();
    }

    private String workerRegister(Ask ask) {
        boolean broad = !"depth".equalsIgnoreCase(ask.mode());
        boolean web = ask.web();
        boolean shelves = ask.shelves() && store != null;
        // every section names only the tools this ask offers: a worker told to web_search with the web closed
        // spends turns on a tool it does not have (chat-ui #2531 made this a rule; measured here on shelves-only asks)
        String search = web ? "web_search" : shelves ? "shelf_search" : "read_pages";
        String fetch = web ? "web_fetch" : "read_pages";
        String shelvesText = !shelves ? "" : "THE SHELVES: the library holds the person's own documents"
                + (ask.collections().isEmpty() ? "" : " — the collection(s) " + String.join(", ", ask.collections())) + " — and what earlier research established. "
                + "shelf_search finds them; read_pages reads one in full. " + (web ? "Search the shelves BEFORE the web: what the person shelved outranks what a search engine ranks. " : "The web is closed for this ask: the shelves are the whole corpus. ")
                + "Cite a shelved document by its title and its file:// or raw/ locator.\n\n";
        StringBuilder sb = new StringBuilder();
        sb.append("You are a researcher working for a library. Read-only: you have ").append(shelves ? "shelf_search, " : "").append(web ? "web_search, scholar_search, web_fetch, " : "").append("read_pages, note and done.\n\n").append(shelvesText);
        if (web) sb.append("scholar_search finds papers, books and chapters by DOI in Crossref and OpenAlex: the primary literature a web engine ranks low. Use it as well as web_search whenever the question touches a literature (medicine, science, history, law, the humanities), then web_fetch the DOI or landing page to read.\n\n");
        sb.append(broad
                ? "SURVEY: run several DIFFERENT " + search + " queries covering the facets and phrasings of your sub-question, then " + fetch
                  + " the most promising sources. Map the landscape — the positions, where sources agree and where they disagree."
                : "DEPTH: one or two precise " + search + " queries, then " + fetch + " and read the BEST sources thoroughly"
                  + (web ? ", following the sources they cite when it matters" : "") + ". Concrete, verified detail.");
        if (web) {
            sb.append("\n\nWhen the sub-question asks the SAME facts about MANY items, first look for ONE page that lists them "
                    + "all (search \"list of …\" or \"comparison of …\"); only if none exists, work through the items one "
                    + "query each — a query naming several items at once matches nothing useful. ");
        } else {
            sb.append("\n\n");
        }
        sb.append("After TWO failed tries on one fact, note it as not found and move on.\n\n");
        sb.append("When the question names a language, region or literature, write SOME of your queries IN that language — "
                + "English queries surface the English literature only.\n\n");
        sb.append("NOTE AS YOU GO: the moment a fetched source shows a fact, call note with the claim, the source's URL "
                + "or citation, and a short quote — BEFORE the next search. Your notes are the record; a fact you do not "
                + "note is lost when your turns run out. For texts, translations and editions, record WHICH edition or "
                + "translation the source is (translator, publisher, year) — a claim about a text without its edition "
                + "is half a claim. For a paper, a preprint, a dataset or software, record the VERSION the same way (arXiv vN, "
                + "the dataset release, the software version, the DOI) — a result without its version is half a result. "
                + "When sources conflict, note both sides as separate notes and say they conflict. "
                + "Base every claim on a source you actually fetched; nothing from memory.\n\n");
        if (web) {
            sb.append("READING: a search result carries its source tier in brackets — [scholarly] [primary] [reference] before [blog] "
                    + "[forum] [web]. web_fetch shows an excerpt; read_pages reads a fetched source page by page when the answer is "
                    + "inside a long document.\n\n");
            sb.append("SOURCES: prefer the primary and scholarly ones — the paper, the edition, the archive, the institution — "
                    + "over encyclopedias, forums and aggregators; when only a weak source supports a claim, say so in the "
                    + "note. A paywalled paper often has an open version (arXiv, a repository, the author's page): look once. ");
        } else {
            sb.append("READING: read_pages reads a shelved document page by page; read the pages that bear on your sub-question "
                    + "rather than the first page only.\n\n");
            sb.append("SOURCES: when only one shelved document supports a claim, say so in the note. ");
        }
        if (web) sb.append("web_fetch returns an EXCERPT centred on `find`; to read a long source properly, fetch it again with a "
                + "different `find` for each thing you need from it.\n\n");
        else sb.append("\n\n");
        sb.append("Finish with done and a summary of what the sources established and what remained unknown.");
        return sb.toString();
    }

    // ---- 3. critic ----

    List<String> critic(Ask ask, List<String> evidence, Budget budget) {
        List<String> missing = new ArrayList<>();
        // the lanes first, mechanically: a lane whose language no note came from goes round again, once
        Map<String, Integer> read = Lanes.languagesRead(String.join("\n", evidence));
        for (Lanes.Lane lane : lanes) {
            if (read.getOrDefault(lane.code(), 0) > 0 || !laneRetried.add(lane.code())) continue;
            String again = lane.subQuestion(ask.question()) + " The first round read no " + lane.name() + "-language source at all; use the seed queries as they are, and read the " + lane.name() + " pages.";
            missing.add(again); laneOf.put(again, lane);
            log.accept("critic: no " + lane.name() + "-language source read (" + Lanes.describe(read) + ") → the lane goes round again");
        }
        if (!budget.take()) return missing;
        try {
            ArrayNode msgs = J.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "You are reviewing research COVERAGE for a library, not writing the answer.\n\nTHE ASK:\n"
                    + ask.question() + "\n\nEVIDENCE SO FAR:\n" + fitNotes(evidence, drive.contextWindow())
                    + "\n\nIs this enough to answer the ask COMPLETELY, with sources, within its stated scope, from every "
                    + "perspective the sub-questions name? Answer with "
                    + "JSON only: {\"sufficient\": true} or {\"sufficient\": false, \"missing\": [\"<self-contained "
                    + "sub-question>\", ...]} (at most 4; only gaps a further search could fill).");
            String raw = judge.classify(msgs, 800);
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            if (a < 0 || b <= a) return missing;
            JsonNode v = J.readTree(raw.substring(a, b + 1));
            if (!v.path("sufficient").asBoolean(true)) {
                for (JsonNode q : v.path("missing")) if (q.isTextual() && !q.asText().isBlank() && missing.size() < 5) missing.add(q.asText().strip());
            }
        } catch (Exception e) {
            log.accept("critic: unparseable — stopping rounds");
        }
        return missing;
    }

    // ---- 4. synthesis ----

    record Synthesis(boolean done, List<String> sections) {
        String text() { return String.join("\n\n", sections); }
    }

    private Synthesis synthesize(Ask ask, String known, List<String> pieces, Budget budget) {
        SectionTool sections = new SectionTool();
        DoneTool done = new DoneTool("done",
                "Finish the investigation. Pass CAVEATS: what stayed uncertain or conflicting, in a sentence or two. "
                + "The sections you wrote are the answer; do not repeat them here.");
        Map<String, Tool> byName = new LinkedHashMap<>();
        if (ask.web()) for (Tool t : tools.web(ask.question())) if ("web_fetch".equals(t.name())) byName.put(t.name(), t);
        if (store != null) { byName.put("read_pages", new PagesTool(store)); if (ask.shelves()) byName.put("shelf_search", new ShelfSearchTool(store, ask.collections())); }
        byName.put(sections.name(), sections);
        byName.put(done.name(), done);
        String notes = coverage(pieces) + "\n" + fitNotes(pieces, drive.contextWindow());

        ArrayNode history = J.createArrayNode();
        history.addObject().put("role", "system").put("content",
                "You are writing an investigation for a library's shelves, from evidence gathered by parallel "
                + "sub-investigations. Treat the evidence as your own notes; web_fetch only to verify something "
                + "doubtful. Write with write_section, ONE section per call, in this order: 'Answer' (the direct "
                + "answer to the ask, first), then one section per sub-question or theme with the supporting "
                + "detail and every claim followed by its source in parentheses, then 'Conflicts and uncertainty' "
                + "when sources disagree, then 'Sources' — every URL or citation you relied on, one per line, with "
                + "edition or translation where it was noted. Keep each section under 1500 characters; use more "
                + "sections rather than longer ones. Then call done.");
        history.addObject().put("role", "user").put("content",
                "THE ASK:\n" + ask.question() + "\n\n" + known + "EVIDENCE:\n" + notes
                + "\n\nWrite the 'Answer' section first.");
        ArrayNode all = toolsArray(byName.values());
        ArrayNode onlyDone = toolsArray(List.of(done));
        int cap = budget.unbounded() ? SYNTH_TURNS * 3 : Math.min(SYNTH_TURNS + 4, Math.max(2, budget.left()));
        boolean finished = false;
        for (int turn = 1; turn <= cap && !finished; turn++) {
            boolean took = budget.takeSynthesis();
            boolean deadline = turn == cap || !took;
            if (deadline && turn > 1 && !sections.sections.isEmpty()) {
                history.addObject().put("role", "user").put("content", "Last turn: call done with the caveats.");
            } else if (deadline) {
                // Nothing written and no budget: one prose completion is better than an empty shelf.
                break;
            }
            ObjectNode assistant;
            int nctx = drive.contextWindow();
            trimHistory(history, nctx);
            try {
                assistant = chat(history, deadline ? onlyDone : all, outBudget(history, nctx));
            } catch (Stopped e) {
                throw e;
            } catch (Exception e) {
                log.accept("synthesis: drive failed on turn " + turn + " (" + e.getMessage() + ")");
                break;
            }
            if (assistant == null) break;
            history.add(assistant);
            JsonNode calls = assistant.path("tool_calls");
            if (!calls.isArray() || calls.isEmpty()) {
                String content = assistant.path("content").asText("");
                if (content.strip().length() > 200 && sections.sections.isEmpty()) {
                    sections.sections.add(content.strip());   // the model wrote prose instead — keep it
                }
                history.addObject().put("role", "user").put("content", "Continue with write_section, or call done.");
                continue;
            }
            for (JsonNode call : calls) {
                String name = call.path("function").path("name").asText();
                String id = call.path("id").asText("call_" + turn);
                JsonNode args = parseArgs(call.path("function").path("arguments"));
                Tool t = byName.get(name);
                String observation;
                if (deadline && !done.name().equals(name)) observation = "Only `done` is available on this turn.";
                else if (t == null) observation = "ERROR: no tool named " + name;
                else {
                    try { observation = t.execute(args); } catch (Exception e) { observation = "ERROR: " + e.getMessage(); }
                }
                observation = cut(observation);
                ObjectNode toolMsg = history.addObject();
                toolMsg.put("role", "tool"); toolMsg.put("tool_call_id", id); toolMsg.put("content", observation);
                if (done.name().equals(name)) finished = true;
            }
        }
        if (finished && !done.summary().isBlank()) sections.sections.add("## Caveats\n\n" + done.summary());
        if (sections.sections.isEmpty()) {
            // Fallback: a single prose answer, no tools. Cut-off synthesis must never mean no answer.
            try {
                ArrayNode msgs = J.createArrayNode();
                msgs.addObject().put("role", "user").put("content",
                        "Answer the ask from the evidence, directly, then the supporting detail with sources in "
                        + "parentheses, then a SOURCES list.\n\nTHE ASK:\n" + ask.question() + "\n\nEVIDENCE:\n" + notes);
                String prose = judge.classify(msgs, 2500);
                if (!prose.isBlank()) sections.sections.add(prose.strip());
            } catch (Exception ignored) {
                // the evidence is still on the record
            }
        }
        return new Synthesis(finished, sections.sections);
    }

    // ---- tools that belong to the runner ----

    /** The worker's notebook: claim + source + quote, kept outside the model's context. */
    static final class Notebook implements Tool {
        private final List<String> notes = new ArrayList<>();
        @Override public String name() { return "note"; }
        @Override public String description() {
            return "Record one fact the moment a fetched source shows it: the claim, the source (URL, or a citation "
                    + "with edition/translation and year), and a short quote that supports it. Notes are kept for you.";
        }
        @Override public ObjectNode parametersSchema(ObjectMapper j) {
            ObjectNode p = j.createObjectNode();
            p.put("type", "object");
            ObjectNode props = p.putObject("properties");
            props.putObject("claim").put("type", "string");
            props.putObject("source").put("type", "string");
            props.putObject("quote").put("type", "string");
            p.putArray("required").add("claim").add("source");
            return p;
        }
        @Override public synchronized String execute(JsonNode args) {
            String claim = args.path("claim").asText("").strip();
            String source = args.path("source").asText("").strip();
            String quote = args.path("quote").asText("").strip();
            if (claim.isEmpty() || source.isEmpty()) return "ERROR: a note needs both `claim` and `source`.";
            if (notes.size() >= 60) return "the notebook is full (60 notes) — call done with your summary.";
            notes.add("- " + claim + " — source: " + source + (quote.isEmpty() ? "" : " — quote: \"" + Acquisitions.compress(quote, 300) + "\""));
            return "noted (" + notes.size() + "). Keep going, or call done when your sub-question is answered.";
        }
        synchronized boolean isEmpty() { return notes.isEmpty(); }
        synchronized int size() { return notes.size(); }
        synchronized String render() { return String.join("\n", notes); }
    }

    /** The synthesis writes in sections; each lands the moment it is written. */
    static final class SectionTool implements Tool {
        final List<String> sections = new ArrayList<>();
        @Override public String name() { return "write_section"; }
        @Override public String description() {
            return "Write ONE section of the investigation: a heading and its text (under 1500 characters). "
                    + "Written sections are kept; never re-send one.";
        }
        @Override public ObjectNode parametersSchema(ObjectMapper j) {
            ObjectNode p = j.createObjectNode();
            p.put("type", "object");
            ObjectNode props = p.putObject("properties");
            props.putObject("heading").put("type", "string");
            props.putObject("text").put("type", "string");
            p.putArray("required").add("heading").add("text");
            return p;
        }
        @Override public String execute(JsonNode args) {
            String heading = args.path("heading").asText("").strip();
            String text = args.path("text").asText("").strip();
            if (text.isEmpty()) return "ERROR: the section has no text.";
            sections.add((heading.isEmpty() ? "" : "## " + heading + "\n\n") + text);
            return "written (" + sections.size() + " section(s) so far). Next section, or done.";
        }
    }

    /** Read a captured document page by page — the whole text, in order, without fetching it again. */
    static final class PagesTool implements Tool {
        private final LibraryStore store;
        PagesTool(LibraryStore store) { this.store = store; }
        @Override public String name() { return "read_pages"; }
        @Override public String description() {
            return "Read a source you already fetched, page by page (about " + PAGE_CHARS + " characters a page), from its captured text — for "
                    + "a paper or a long document that web_fetch only excerpted. Pass the URL and the page number (1 = the start).";
        }
        @Override public ObjectNode parametersSchema(ObjectMapper j) {
            ObjectNode p = j.createObjectNode();
            p.put("type", "object");
            ObjectNode props = p.putObject("properties");
            props.putObject("url").put("type", "string");
            props.putObject("page").put("type", "integer");
            p.putArray("required").add("url");
            return p;
        }
        @Override public String execute(JsonNode args) throws Exception {
            String url = args.path("url").asText("").strip();
            int page = Math.max(1, args.path("page").asInt(1));
            java.nio.file.Path p = RawCapture.find(store, url);
            if (p == null) return "ERROR: nothing captured for " + url + " — web_fetch it first.";
            String text = RawCapture.read(p)[2];
            int pages = Math.max(1, (text.length() + PAGE_CHARS - 1) / PAGE_CHARS);
            if (page > pages) return "ERROR: " + url + " has " + pages + " page(s).";
            String body = text.substring((page - 1) * PAGE_CHARS, Math.min(text.length(), page * PAGE_CHARS));
            return "page " + page + " of " + pages + " — " + url + "\n" + Fence.wrap("SOURCE TEXT", body) + "\n" + Fence.rule("SOURCE TEXT")
                    + (page < pages ? "\n(read_pages with page " + (page + 1) + " continues.)" : "");
        }
    }

    /** Search the shelves — the person's own corpus and what earlier research established — scoped to collections when asked. */
    static final class ShelfSearchTool implements Tool {
        private final LibraryStore store;
        private final List<String> collections;
        ShelfSearchTool(LibraryStore store, List<String> collections) { this.store = store; this.collections = collections; }
        @Override public String name() { return "shelf_search"; }
        @Override public String description() {
            return "Search the library's shelves: the person's own documents" + (collections.isEmpty() ? "" : " in " + String.join(", ", collections))
                    + ", captured pages, and established findings. Returns hits with a locator to read_pages. Try this before the web.";
        }
        @Override public ObjectNode parametersSchema(ObjectMapper j) {
            ObjectNode p = j.createObjectNode();
            p.put("type", "object");
            p.putObject("properties").putObject("query").put("type", "string");
            p.putArray("required").add("query");
            return p;
        }
        @Override public String execute(JsonNode args) throws Exception {
            String q = args.path("query").asText("").strip();
            if (q.isEmpty()) return "ERROR: empty query";
            LibrarianIndex idx = new LibrarianIndex(store);
            List<LibrarianIndex.Hit> hits = new ArrayList<>();
            if (collections.isEmpty()) hits.addAll(idx.searchIn(q, 8, null, null));
            else for (String c : collections) hits.addAll(idx.searchIn(q, 8, null, c));
            if (hits.isEmpty()) return "the shelves hold nothing for: " + q;
            StringBuilder sb = new StringBuilder("shelf results for \"" + q + "\":\n" + Fence.open("SHELF RESULTS") + "\n");
            int n = 0;
            for (LibrarianIndex.Hit h : hits) {
                if (n++ >= 10) break;
                String locator = h.id();
                if ("raw".equals(h.kind()) || "chunk".equals(h.kind())) {
                    try { java.nio.file.Path rp = LibraryStore.under(store.rawDir(), h.id()); if (rp != null && java.nio.file.Files.exists(rp)) locator = RawCapture.read(rp)[0]; } catch (Exception ignored) { }
                }
                sb.append(n).append(". ").append(h.title()).append("  [").append(h.kind()).append(h.state().isEmpty() ? "" : ", " + h.state()).append("]\n   ").append(locator).append('\n');
                if (!h.snippet().isBlank()) sb.append("   ").append(Acquisitions.compress(h.snippet(), 240)).append('\n');
            }
            sb.append(Fence.close("SHELF RESULTS")).append('\n').append(Fence.rule("SHELF RESULTS")).append('\n');
            return sb.toString();
        }
    }

    /** The finishing tool: records the summary/caveats the model passes. */
    static final class DoneTool implements Tool {
        private final String name, description;
        private volatile String summary = "";
        DoneTool(String name, String description) { this.name = name; this.description = description; }
        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public ObjectNode parametersSchema(ObjectMapper j) {
            ObjectNode p = j.createObjectNode();
            p.put("type", "object");
            p.putObject("properties").putObject("summary").put("type", "string");
            p.putArray("required").add("summary");
            return p;
        }
        @Override public String execute(JsonNode args) {
            summary = args.path("summary").asText("").strip();
            return "finished.";
        }
        String summary() { return summary; }
    }

    // ---- the shared budget ----

    /** Model turns the whole ask may spend, shared by every worker. Thread-safe. */
    static final class Budget {
        private final int total;          // 0 = no turn ceiling
        private final long deadlineMs;    // 0 = no time ceiling
        private volatile long wrapUpMs = 0;   // what the write-up needs, from the run's own turn time
        private final AtomicInteger used = new AtomicInteger();
        private final AtomicInteger graces = new AtomicInteger();
        private volatile int expectedWorkers = 1;
        private volatile int roundCap = 0;
        private volatile long roundDeadlineMs = 0;   // round one stops here when a second round may follow, so it has time left
        Budget(int total) { this(total, 0); }
        /** Stop the workers of this round at {@code atMs} (0 = no round deadline); the run's own deadline still stands. */
        void roundDeadline(long atMs) { roundDeadlineMs = atMs; }
        long deadlineMs() { return deadlineMs; }
        /** Minutes the workers still have before the write-up must start; a large number when there is no deadline. */
        double minutesLeftForWorkers() { return deadlineMs == 0 ? 1e9 : (deadlineMs - wrapUpMs - System.currentTimeMillis()) / 60_000.0; }
        Budget(int total, int maxMinutes) {
            this.total = total <= 0 ? 0 : Math.max(4, total);
            this.deadlineMs = maxMinutes <= 0 ? 0 : System.currentTimeMillis() + maxMinutes * 60_000L;
        }
        boolean unbounded() { return total == 0; }
        /** The workers' time is up when the write-up would no longer fit before the person's deadline. */
        boolean workersTimeUp() { return deadlineMs > 0 && System.currentTimeMillis() + wrapUpMs >= deadlineMs; }
        void wrapUp(long ms) { wrapUpMs = Math.max(0, ms); }
        void expectWorkers(int n) { expectedWorkers = Math.max(1, n); }
        void roundCap(int cap) { roundCap = cap; }
        int roundCap() { return roundCap; }
        /** Take one turn; false when nothing is left. */
        boolean take() { return take(0); }

        /** The turns kept from the workers — the synthesis, its close and the cite-check — scaled down for a tiny ask; none without a ceiling. */
        int reserve() { return unbounded() ? 0 : Math.min(RESERVE, total / 2); }
        /** The cite-check's share of the reserve. */
        int checkReserve() { return reserve() * CHECK_TURNS / RESERVE; }
        /** A WORKER's turn: refused once only the reserve is left (workers' bounces once ate it — measured, J-0006). */
        boolean takeWorker() { if (workersTimeUp()) return false; if (roundDeadlineMs > 0 && System.currentTimeMillis() >= roundDeadlineMs) return false; return take(reserve()); }
        /** A SYNTHESIS turn: refused once only the cite-check's turns are left (it read zero sentences once — J-0007). */
        boolean takeSynthesis() { return take(checkReserve()); }

        private boolean take(int keep) {
            if (unbounded()) { used.incrementAndGet(); return true; }
            while (true) {
                int u = used.get();
                if (u + keep >= total) return false;
                if (used.compareAndSet(u, u + 1)) return true;
            }
        }
        /** One unbudgeted closing turn per worker, so a spent budget still ends with a summary. */
        boolean grace() { return graces.incrementAndGet() <= MAX_SUB * ROUNDS; }
        int used() { return used.get() + graces.get(); }
        /** The turn ceiling, 0 when there is none. */
        int ceiling() { return total; }
        int left() { return unbounded() ? Integer.MAX_VALUE / 2 : Math.max(0, total - used.get()); }
        /** A fair share per worker of what is left after the synthesis reserve. */
        int perWorkerCap() { return Math.max(2, (left() - reserve()) / Math.max(1, expectedWorkers)); }
    }

    // ---- helpers ----

    /**
     * One turn, with ONE retry on a transport failure. Measured live (J-0008): a worker's send threw a
     * second after the request went out while the drive kept answering the other two workers — a stale
     * keep-alive, not the model — and the worker died at turn 5 with one note. An HTTP status error is
     * not retried: it is the drive's answer, not the wire's.
     */
    private ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens) {
        if (stopWhen.getAsBoolean()) { log.accept("stopped: a person stopped this run; ending at this turn"); throw new Stopped(); }
        ResearchSettings.awaitUnpaused(log);
        try { throttle.enter(workersNow()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted"); }
        long t0 = System.currentTimeMillis();
        int in = 0;
        for (JsonNode m : history) in += estTokens(m.path("content").asText("")) + estTokens(m.path("tool_calls").toString()) + 8;
        ObjectNode reply = null;
        try {
            reply = chatOnce(history, tools, maxTokens);
            return reply;
        } finally {
            throttle.leave();
            // a turn's cost is its tokens — a late turn carries a long history and is slower on its own, which is
            // not contention (measured, J-0009: "slowed" fired on the run's own growth). Decoding costs ~20× prefill.
            int out = reply == null ? 0 : estTokens(reply.path("content").asText("")) + estTokens(reply.path("tool_calls").toString());
            throttle.observe(System.currentTimeMillis() - t0, in + 20L * out);
            // the write-up: every running worker's two closing turns through the lanes, then the synthesis and the cite-check
            int lanes = throttle.slow() ? 1 : workersNow();
            long closing = (2L * workersActive.get() + lanes - 1) / lanes;
            currentBudget.wrapUp((SYNTH_TURNS + 1 + CHECK_TURNS + closing) * throttle.longTurnMs());
        }
    }

    private volatile Budget currentBudget = new Budget(0);
    private final java.util.concurrent.atomic.AtomicInteger workersActive = new java.util.concurrent.atomic.AtomicInteger();

    private ObjectNode chatOnce(ArrayNode history, ArrayNode tools, int maxTokens) {
        try {
            return drive.chat(history, tools, maxTokens, "required");
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage());
            boolean transport = m.startsWith("chat() failed") || e.getCause() instanceof java.io.IOException;
            if (!transport) throw e;
            log.accept("drive: transport failure (" + (e.getCause() == null ? m : e.getCause().getMessage()) + ") — retrying once");
            try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            return drive.chat(history, tools, maxTokens, "required");
        }
    }

    /**
     * The reply budget for one turn, the loop's rule: half the window at most, what the input leaves at least
     * 512. A FIXED budget was the first live defect — a thinking model spends its reasoning inside max_tokens,
     * and at 1500 two workers' closing tool call was cut off, leaving an empty summary.
     */
    static int outBudget(ArrayNode history, int nctx) {
        int ctx = Math.max(nctx, 8000);
        int in = 0;
        for (JsonNode m : history) in += estTokens(m.path("content").asText("")) + estTokens(m.path("tool_calls").toString()) + 8;
        return Math.max(512, Math.min(ctx / 2, ctx - in - 256));
    }

    /** Keep the history inside ~55% of the window by blanking the OLDEST tool observations first. */
    static int trimHistory(ArrayNode history, int nctx) {
        int ctx = Math.max(nctx, 8000);
        int limit = (int) (ctx * 0.55);
        int trimmed = 0;
        for (int i = 0; i < history.size(); i++) {
            int total = 0;
            for (JsonNode m : history) total += estTokens(m.path("content").asText("")) + estTokens(m.path("tool_calls").toString()) + 8;
            if (total <= limit) break;
            JsonNode m = history.get(i);
            if ("tool".equals(m.path("role").asText()) && m.path("content").asText("").length() > 200) {
                ((ObjectNode) m).put("content", "[older observation trimmed to fit the context — its facts are in your notes]");
                trimmed++;
            }
        }
        return trimmed;
    }

    static ArrayNode toolsArray(Iterable<Tool> ts) {
        ArrayNode arr = J.createArrayNode();
        for (Tool t : ts) {
            ObjectNode entry = arr.addObject();
            entry.put("type", "function");
            ObjectNode fn = entry.putObject("function");
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.set("parameters", t.parametersSchema(J));
        }
        return arr;
    }

    static JsonNode parseArgs(JsonNode raw) {
        try {
            if (raw.isObject()) return raw;
            String s = raw.asText("{}");
            JsonNode n = J.readTree(s.isBlank() ? "{}" : s);
            return n.isObject() ? n : J.createObjectNode();
        } catch (Exception e) {
            return J.createObjectNode();
        }
    }

    /** Rough token count by script: CJK ≈ 1 token per char, everything else ≈ 4 chars per token. */
    static int estTokens(String s) {
        if (s == null) return 0;
        int cjk = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3040 && c <= 0x30ff) || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0xac00 && c <= 0xd7af)) cjk++;
            else other++;
        }
        return cjk + other / 4;
    }

    static String trimTokens(String s, int maxTokens) {
        if (estTokens(s) <= maxTokens) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (estTokens(s.substring(0, mid)) <= maxTokens) lo = mid; else hi = mid - 1;
        }
        int nl = s.lastIndexOf('\n', lo);
        return s.substring(0, nl > lo / 2 ? nl : lo) + " …[trimmed to fit the context]";
    }

    /**
     * Fit the evidence into ~28% of the context window, FAIRLY: every piece (one worker's report) keeps its head —
     * the sub-question and the summary — and the pieces share the room, a short one giving its surplus to the long
     * ones. Before this the whole evidence was one piece cut from the tail, so on a five-lane run through a 32k slot the
     * fourth and fifth reports never reached the writer, and the answer called those lanes untested while their
     * reports held 17 sources (dolores, I-0002, 2026-09-11).
     */
    static String fitNotes(List<String> pieces, int ctxTokens) {
        int total = Math.max(2000, (int) (Math.max(ctxTokens, 8000) * 0.28));
        int n = Math.max(1, pieces.size());
        int[] size = new int[n], give = new int[n];
        for (int i = 0; i < n; i++) size[i] = estTokens(pieces.get(i));
        // water-filling: the smallest first, each taking what it needs up to an even share of what is left
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(size[a], size[b]));
        int left = total, remaining = n;
        for (int k : order) { int share = left / remaining; give[k] = Math.min(size[k], share); left -= give[k]; remaining--; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String t = fitPiece(pieces.get(i), Math.max(150, give[i]));
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * One report, trimmed to {@code maxTokens}. A report is the SUB-QUESTION line, a SUMMARY (a paragraph, often a
     * numbered list over several lines), then the NOTES — the lines with "— source:" that carry the evidence. The notes
     * come first in the room (up to three quarters), the summary takes what is left (a quarter at most), the
     * sub-question always. Measured on the dolores record: a summary-first cut kept 2 of 57 sources.
     */
    static String fitPiece(String piece, int maxTokens) {
        if (estTokens(piece) <= maxTokens) return piece;
        String[] lines = piece.split("\n");
        List<String> summary = new ArrayList<>(), notes = new ArrayList<>();
        String head = lines.length > 0 ? lines[0] : "";
        for (int i = 1; i < lines.length; i++) { String l = lines[i]; if (l.startsWith("- ") && l.contains("— source: ")) notes.add(l); else if (!l.isBlank()) summary.add(l); }
        int room = Math.max(60, maxTokens - estTokens(head) - 2);
        StringBuilder out = new StringBuilder(head);
        int notesRoom = notes.isEmpty() ? 0 : room * 3 / 4, usedNotes = 0, keptNotes = 0;
        List<String> keptNoteLines = new ArrayList<>();
        for (String l : notes) { int t = estTokens(l) + 1; if (usedNotes + t > notesRoom) break; keptNoteLines.add(l); usedNotes += t; keptNotes++; }
        int summaryRoom = Math.max(40, room - usedNotes);
        StringBuilder sum = new StringBuilder();
        for (String l : summary) { if (sum.length() > 0) sum.append('\n'); sum.append(l); }
        String summaryText = trimTokens(sum.toString(), summaryRoom);
        if (!summaryText.isBlank()) out.append('\n').append(summaryText);
        for (String l : keptNoteLines) out.append('\n').append(l);
        int dropped = notes.size() - keptNotes;
        if (dropped > 0) out.append("\n…[").append(dropped).append(" more note(s) of this report trimmed to fit; all of it is on the record]");
        return out.toString();
    }

    /** The coverage block the writer sees first: every sub-investigation with how many sources it noted — so no lane can be called empty unread. */
    static String coverage(List<String> pieces) {
        StringBuilder b = new StringBuilder("COVERAGE — what each sub-investigation found (its notes follow; a lane listed with sources here has evidence, whatever is trimmed below):\n");
        int i = 0;
        for (String p : pieces) {
            i++;
            String head = p.startsWith("SUB-QUESTION: ") ? p.substring(14, Math.min(p.length(), p.indexOf('\n') < 0 ? p.length() : p.indexOf('\n'))) : "(unnamed)";
            b.append("- #").append(i).append(" ").append(Acquisitions.compress(head, 140)).append(": ").append(sourcesNoted(p)).append(" source(s) noted\n");
        }
        return b.toString();
    }

    static int sourcesNoted(String piece) {
        int c = 0; java.util.regex.Matcher m = java.util.regex.Pattern.compile("— source: ").matcher(piece);
        while (m.find()) c++;
        return c;
    }

    static final java.util.regex.Pattern SAYS_EMPTY = java.util.regex.Pattern.compile("(?i)[^.\\n]*\\b(no (direct |published |empirical )?evidence|found nothing|nothing (was )?found|no sources?|did not (find|surface|locate)|could not (find|locate)|remains? untested|not (been )?(tested|studied|examined))\\b[^.\\n]*");

    /**
     * After synthesis: every sentence in which the answer says a thing was not found, matched by its words against the
     * sub-questions; when the matched sub-investigation noted three or more sources, that is a claim of absence over
     * evidence the writer did not read. Returns one line per such case.
     */
    static List<String> coverageCheck(String answer, List<String> pieces) {
        List<String> out = new ArrayList<>();
        if (answer == null || pieces.isEmpty()) return out;
        // the words that tell one sub-question from another: not the ones most heads share, and not the words of absence
        List<Set<String>> heads = new ArrayList<>();
        java.util.Map<String, Integer> df = new java.util.HashMap<>();
        for (String p : pieces) {
            String head = p.startsWith("SUB-QUESTION: ") ? p.substring(0, p.indexOf('\n') < 0 ? p.length() : p.indexOf('\n')) : p;
            Set<String> ht = Frontier.terms(head); heads.add(ht);
            for (String w : ht) df.merge(w, 1, Integer::sum);
        }
        Set<String> generic = Set.of("evidence", "found", "find", "sources", "source", "direct", "specific", "specifically", "question", "questions", "research",
                "gathered", "whether", "remains", "remain", "untested", "surveyed", "literature", "studies", "study", "exact", "exactly", "any", "the", "and",
                "how", "what", "which", "does", "did", "not", "nor", "was", "were", "been", "that", "this", "these", "those", "from", "with", "about", "into", "there");
        java.util.regex.Matcher m = SAYS_EMPTY.matcher(answer);
        while (m.find()) {
            String sentence = m.group().strip();
            Set<String> st = new java.util.HashSet<>(Frontier.terms(sentence));
            st.removeIf(w -> generic.contains(w) || df.getOrDefault(w, 0) > Math.max(1, pieces.size() / 2));
            List<String> hits = new ArrayList<>();
            for (int i = 0; i < pieces.size(); i++) {
                String p = pieces.get(i);
                int shared = 0; for (String w : st) if (heads.get(i).contains(w)) shared++;
                if (shared >= 3 && sourcesNoted(p) >= 3) hits.add("#" + (i + 1) + " (" + sourcesNoted(p) + " sources)");
            }
            if (!hits.isEmpty()) out.add("the answer says \"" + Acquisitions.compress(sentence, 160) + "\" while sub-investigation " + String.join(" and ", hits) + " noted sources on it (see Worker findings)");
        }
        return out;
    }

    // ---- the live adapters ----

    /** The daemon's drive: the OpenAI-compatible chat client. */
    public static Drive drive(String baseUrl, String model) {
        org.researchzosho.drive.DriveClient c = new org.researchzosho.drive.DriveClient(baseUrl, model);
        return new Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                return c.chat(messages, tools, maxTokens, toolChoice);
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return c.classify(messages, maxTokens); }
            @Override public int contextWindow() { return c.contextWindow(); }
        };
    }

    /** The live web tools: search (with its steerer) and fetch (which captures raw text to the library). */
    public static Tools webTools() {
        return new Tools() {
            private final ThreadLocal<org.researchzosho.tools.WebSearchTool> last = new ThreadLocal<>();
            @Override public List<Tool> web(String focus) {
                var search = new org.researchzosho.tools.WebSearchTool().focus(focus);
                var fetch = new org.researchzosho.tools.WebFetchTool().focus(focus);
                last.set(search);
                return List.of(search, new org.researchzosho.tools.ScholarSearchTool(), fetch);
            }
            @Override public BooleanSupplier exhausted() {
                var s = last.get();
                return s == null ? () -> false : () -> s.steer().exhausted();
            }
        };
    }
}
