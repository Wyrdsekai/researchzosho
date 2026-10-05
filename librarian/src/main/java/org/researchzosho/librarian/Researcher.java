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

import java.io.IOException;
import java.util.function.Supplier;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.Declined;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.records.RecordSource;
import org.researchzosho.records.RecordSources;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.RecordSearchTool;
import org.researchzosho.tools.ScholarSearchTool;
import org.researchzosho.tools.WebFetchTool;
import org.researchzosho.tools.VideoSearchTool;
import org.researchzosho.tools.ChannelUploadsTool;
import org.researchzosho.tools.ChannelsLikeTool;
import org.researchzosho.tools.VideoDetailsTool;
import org.researchzosho.tools.WebSearchTool;
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
        /** A prose completion that is itself the work (the writer's answer when no section was written): a watched seat reads it for a decline. */
        default String prose(ArrayNode messages, int maxTokens) { return classify(messages, maxTokens); }
        /** The judge a watched seat reads its model's replies with ({@link Declines}); null for a seat nobody watches. */
        default DeclineJudge declineJudge() { return null; }
    }

    /** A fresh set of research tools for one worker, focused on its sub-question. */
    public interface Tools {
        /** The web tools (search, fetch) for this worker. */
        List<Tool> web(String focus);
        /**
         * The same, under the run's content policy: what the run lets in, the check its fetched pages go through, where they are kept. A
         * set of tools that fetches nothing (a test's) ignores it.
         */
        default List<Tool> web(String focus, ContentPolicy policy) { return web(focus); }
        /** The search steerer's stop rule for this worker's tools, or {@code () -> false}. */
        BooleanSupplier exhausted();
    }

    /** What the daemon hands over. {@code subQuestions} may be empty (then the runner decomposes). */
    /**
     * {@code maxTurns} and {@code maxMinutes} are the person's ceilings, either, both or neither (0 = none):
     * "up to 600 turns", "two hours tops". With neither the run goes until the work is done — every planned
     * sub-question, the critic's rounds, every section, every cited sentence checked.
     */
    /**
     * {@code fields}: the fields somebody asked this run to be (the research command's {@code --genealogy}, the web page's box, {@code
     * field} in the tool, a yes in the chat, a field's own command). A field that joins only when asked joins only when named here.
     */
    /**
     * {@code allow}: what the person let in for this question's run only, having been asked or having asked ({@link ContentPolicy#EXPLICIT},
     * {@link ContentPolicy#HOWTO}; {@link ContentPolicy#SELF_HARM} is their yes to researching it at all). Empty for every other run, and
     * always for a nightly one.
     */
    public record Ask(String question, String mode, int maxTurns, List<String> subQuestions, String sources, List<String> collections, int maxMinutes, List<String> fields, List<String> allow) {
        public Ask {
            maxTurns = Math.max(0, maxTurns); maxMinutes = Math.max(0, maxMinutes);
            subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
            mode = mode == null || mode.isBlank() ? "broad" : mode;
            sources = sources == null || sources.isBlank() ? "both" : sources;   // both (shelves first) | shelves | web
            collections = collections == null ? List.of() : List.copyOf(collections);
            fields = fields == null ? List.of() : fields.stream().filter(f -> f != null && !f.isBlank()).map(f -> f.strip().toLowerCase(Locale.ROOT)).distinct().toList();
            allow = allow == null ? List.of() : allow.stream().filter(a -> a != null && !a.isBlank()).map(a -> a.strip().toLowerCase(Locale.ROOT)).filter(ContentPolicy.ALLOW_NAMES::contains).distinct().toList();
        }
        public Ask(String question, String mode, int maxTurns, List<String> subQuestions, String sources, List<String> collections, int maxMinutes, List<String> fields) { this(question, mode, maxTurns, subQuestions, sources, collections, maxMinutes, fields, List.of()); }
        public Ask(String question, String mode, int maxTurns, List<String> subQuestions, String sources, List<String> collections, int maxMinutes) { this(question, mode, maxTurns, subQuestions, sources, collections, maxMinutes, List.of()); }
        public Ask(String question, String mode, int maxTurns, List<String> subQuestions) { this(question, mode, maxTurns, subQuestions, "both", List.of(), 0); }
        public Ask(String question, String mode, int maxTurns, List<String> subQuestions, String sources, List<String> collections) { this(question, mode, maxTurns, subQuestions, sources, collections, 0); }
        /** The same ask, as a run of these fields. */
        public Ask withFields(List<String> f) { return new Ask(question, mode, maxTurns, subQuestions, sources, collections, maxMinutes, f, allow); }
        /** The same ask, letting in what the person let in for it. */
        public Ask withAllow(List<String> a) { return new Ask(question, mode, maxTurns, subQuestions, sources, collections, maxMinutes, fields, a); }
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
                         int rounds, List<String> log, List<String> openQuestions, ObjectNode stats, List<Declined> declines, Declined ended) {
        public Result {
            declines = declines == null ? List.of() : List.copyOf(declines);
        }
        public Result(boolean done, String answer, String evidence, int turnsUsed, int subQuestions, int rounds, List<String> log) {
            this(done, answer, evidence, turnsUsed, subQuestions, rounds, log, List.of(), null);
        }
        public Result(boolean done, String answer, String evidence, int turnsUsed, int subQuestions, int rounds, List<String> log, List<String> openQuestions) {
            this(done, answer, evidence, turnsUsed, subQuestions, rounds, log, openQuestions, null);
        }
        public Result(boolean done, String answer, String evidence, int turnsUsed, int subQuestions, int rounds, List<String> log, List<String> openQuestions, ObjectNode stats) {
            this(done, answer, evidence, turnsUsed, subQuestions, rounds, log, openQuestions, stats, List.of(), null);
        }
        /** A short account for the job ledger. */
        public String summary() {
            return "turns " + turnsUsed + " · " + subQuestions + " sub-question(s) · " + rounds + " round(s)"
                    + (ended != null ? " · declined by the model" : done ? "" : " · synthesis cut off at the deadline")
                    + (ended == null && !declines.isEmpty() ? " · " + declines.size() + " part(s) declined by the model" : "");
        }
        /** The statement a person reads for a run the model declined; "" when it did not. */
        public String declinedStatement() { return ended == null ? "" : ended.statement(WHOLE_RUN); }
        /** The statement for a run whose model declined parts of it and not the whole; "" when it declined none. */
        public String partsStatement() {
            if (declines.isEmpty() || ended != null) return "";
            List<String> models = declines.stream().map(Declined::model).filter(m -> !m.isEmpty()).distinct().toList();   // the workers' and the judge seat's may differ
            int n = declines.size();
            return (models.size() > 1 ? "The models this library uses (" + String.join(", ", models) + ")" : "The model this library uses" + (models.isEmpty() ? "" : " (" + models.get(0) + ")"))
                    + " declined " + (n == 1 ? "one part" : n + " parts") + " of this research. "
                    + "ResearchZosho did not try to get around it. The report's Declined section says which, which model declined each, and what it said.";
        }

        /**
         * The job's {@code declined} field: {@code run} (the model declined the whole run, and nothing was filed), {@code model}, {@code parts}
         * (each step it declined, the seat, how it was known, what it said) and {@code statement}, the sentence a person reads. Null when the
         * model declined nothing.
         */
        public ObjectNode declinedView() {
            if (declines.isEmpty() && ended == null) return null;
            ObjectNode o = J.createObjectNode();
            o.put("run", ended != null);
            o.put("model", (ended != null ? ended : declines.get(0)).model());
            ArrayNode parts = o.putArray("parts");
            for (Declined d : declines.isEmpty() ? List.of(ended) : declines) {
                ObjectNode p = parts.addObject();
                p.put("step", d.step()); p.put("seat", d.seat()); p.put("model", d.model()); p.put("how", d.how().name().toLowerCase(Locale.ROOT)); p.put("said", d.said());
            }
            o.put("statement", ended != null ? declinedStatement() : partsStatement());
            return o;
        }
    }

    private static final ObjectMapper J = new ObjectMapper();
    static final int WORKERS = Config.getInt("RESEARCHZOSHO_RESEARCH_WORKERS", 3);
    static final int WORKER_TURNS = Config.getInt("RESEARCHZOSHO_RESEARCH_WORKER_TURNS", 14);
    static final int ROUNDS = Config.getInt("RESEARCHZOSHO_RESEARCH_ROUNDS", 2);
    static final int SYNTH_TURNS = 8;
    /** The cite-check's own turns: with none reserved it read zero sentences on a spent budget (measured, J-0007). */
    static final int CHECK_TURNS = 6;
    /** Turns kept back from the workers: the synthesis, its closing turn and the cite-check. */
    static final int RESERVE = SYNTH_TURNS + 1 + CHECK_TURNS;
    static final int MIN_WORKER_TURNS = 4;
    /** A second-round worker needs this long to fetch and read, not only search. */
    static final int MIN_ROUND_TWO_MINUTES = 4;
    /** Second-round sub-question → pages the first round named and did not read; the worker starts by fetching them. */
    private final Map<String, List<String>> seedsOf = new ConcurrentHashMap<>();

    /**
     * Pages the evidence names beside the words of {@code sub}: a first-round worker that could not fetch a source
     * usually names it ("the PDF … https://… was not fetched"), and the critic then asks for exactly that. Up to four
     * URLs from the lines that share the most distinctive words with the sub-question.
     */
    static List<String> seedsFor(String sub, String evidence) {
        Set<String> st = new HashSet<>();
        for (String w : Frontier.terms(sub)) if (w.length() >= 5) st.add(w);
        Map<String, Integer> score = new LinkedHashMap<>();
        Pattern url = Pattern.compile("https?://[^\\s)\\]>\"']+");
        for (String line : evidence.split("\n")) {
            Matcher m = url.matcher(line);
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
    /** record_search: "on" offers it to a run whose field works from records, "always" to every run, "off" to none (RESEARCHZOSHO_RECORDS). */
    static final String RECORDS_MODE = Config.get("RESEARCHZOSHO_RECORDS", "on");
    static final boolean RECORDS = !"off".equalsIgnoreCase(RECORDS_MODE);
    static final boolean PERSPECTIVES = !"off".equalsIgnoreCase(Config.get("RESEARCHZOSHO_PERSPECTIVES", "on"));
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
    static final int WORKER_TIMEOUT_MIN = Config.getInt("RESEARCHZOSHO_RESEARCH_WORKER_MINUTES", 40);

    private final Drive drive;     // the workers' seat: search, fetch, note — local labour
    private final Drive judge;     // the judgment seat: plan, perspectives, critic, synthesis, cite-check — a frontier model when configured
    /** The language lanes of the current run: sub-question → lane, and which lanes were sent round again. */
    private final Map<String, Lanes.Lane> laneOf = new ConcurrentHashMap<>();
    private final List<Lanes.Lane> lanes = new CopyOnWriteArrayList<>();
    /** What record_search found this run, link → how the record is cited; and the searches of a named source that found nothing, as the harness saw them. */
    private final Map<String, String> recordCitations = new ConcurrentHashMap<>();
    private final List<String> nothingFound = new CopyOnWriteArrayList<>();
    /** Every search this run made, as the library saw it go by: the tool, the words, the years, how many results. The research log. */
    private final List<SearchLog.Entry> searches = new CopyOnWriteArrayList<>();
    public List<SearchLog.Entry> searches() { return List.copyOf(searches); }

    /**
     * A search is logged at the tool call: web_search, scholar_search and record_search, with what came back counted from the observation.
     * Only a call that reached its tool is a search; a collection that did not answer is logged as that, never as a search that found nothing.
     */
    void logSearch(String tool, JsonNode args, String observation) {
        String query = args.path("query").asText("").strip();
        if (query.isEmpty()) return;
        String status = SearchLog.outcome(observation);
        if (status == null) return;
        String where = switch (tool) { case "record_search" -> args.path("source").asText("records"); case "scholar_search" -> "scholar"; default -> "web"; };
        int found = status.equals(SearchLog.FAILED) ? 0 : (int) observation.lines().filter(l -> l.matches("\\d+\\. .*")).count();
        searches.add(new SearchLog.Entry(LocalDate.now().toString(), "", where, query, args.path("from_year").asInt(0), args.path("to_year").asInt(0), found, "", status));
    }

    /** The kinds of record collection searched this run (newspaper, book, archive…) and how often: "reasonably exhaustive" is a variety of kinds, not many searches of one. */
    private final Map<String, Integer> kindsSearched = new ConcurrentHashMap<>();

    /** One line for the critic, computed: which kinds of records were searched and which usable kinds were not; "" when record_search was never used and no field asks for records. */
    String recordKindsLine() {
        if (kindsSearched.isEmpty() && fields.stream().noneMatch(Profile::wantsRecords)) return "";
        Set<String> usable = new TreeSet<>();
        for (var src : RecordSources.forFields(RecordSources.all(), fieldNames())) if (RecordSources.usable(src) && src.holdsTheRecord()) usable.add(src.kind());
        List<String> done = new ArrayList<>();
        for (var e : new TreeMap<>(kindsSearched).entrySet()) done.add(e.getKey() + " ×" + e.getValue());
        usable.removeAll(kindsSearched.keySet());
        return "record collections searched by kind: " + (done.isEmpty() ? "none" : String.join(", ", done)) + (usable.isEmpty() ? "" : "; kinds not searched at all: " + String.join(", ", usable)) + "\n";
    }

    void recordSearched(RecordSearchTool.Searched s) {
        kindsSearched.merge(s.source().kind(), 1, Integer::sum);
        sourcesSearched.merge(s.source().name(), new int[]{1, s.hits().isEmpty() ? 0 : 1}, (a, b) -> new int[]{a[0] + b[0], a[1] + b[1]});
        log.accept("records: " + s.source().id() + " ← " + s.query() + " → " + s.hits().size() + " record(s)");
        for (var h : s.hits()) if (!h.link().isBlank() && !h.where().isBlank()) recordCitations.putIfAbsent(Fetch.canonical(h.link()), h.where());
        if (!s.hits().isEmpty()) return;
        String years = s.fromYear() > 0 || s.toYear() > 0 ? ", " + (s.fromYear() > 0 ? s.fromYear() : "…") + "-" + (s.toYear() > 0 ? s.toYear() : "…") : "";
        String line = s.source().name() + ": " + s.query() + years;
        if (!nothingFound.contains(line)) nothingFound.add(line);
    }

    /** The saved pages and the workers' notes: a quotation is backed when either carries it, as the checks section says. A page that
     *  was read and not saved (a site that blocks saving, a page served from a search) still has the note that quotes it. */
    static List<String> withNotes(List<String> sourceTexts, String evidence) {
        List<String> all = new ArrayList<>(sourceTexts);
        if (evidence != null && !evidence.isBlank()) all.add(evidence);
        return all;
    }

    /** How record_search said to cite one of these locators; "" when none of them came from it. */
    String citedAsRecord(List<String> locators) {
        for (String l : locators) { String c = l.contains("://") ? recordCitations.get(Fetch.canonical(l)) : null; if (c != null) return c; }
        return "";
    }

    /** Every record collection searched this run: how many searches, how many found something. The breadth of the search is part of the answer. */
    private final Map<String, int[]> sourcesSearched = new ConcurrentHashMap<>();

    /** Whether the answer already says what a note says, in other words: most of the note's distinctive words (names, numbers, long words) stand in it. */
    static boolean sameSaid(String answer, String note) {
        Set<String> words = new LinkedHashSet<>();
        Matcher m = Pattern.compile("[\\p{IsHan}\\p{IsKatakana}]{2,}|\\p{L}{6,}|\\d{3,}").matcher(note);
        while (m.find()) words.add(m.group().toLowerCase(Locale.ROOT));
        if (words.size() < 3) return false;
        String a = answer.toLowerCase(Locale.ROOT);
        long there = words.stream().filter(a::contains).count();
        return there * 10 >= words.size() * 7;
    }

    /** "## Searched and not found": written from what the tool returned, so it is complete whether or not a worker noted it. */
    String searchedSection() {
        if (sourcesSearched.isEmpty()) return "";
        StringBuilder b = new StringBuilder("## Record collections searched\n\nWritten by the library from what the searches returned, not by the model.\n\n");
        for (var e : new TreeMap<>(sourcesSearched).entrySet()) b.append("- ").append(e.getKey()).append(": ").append(e.getValue()[0]).append(e.getValue()[0] == 1 ? " search, " : " searches, ").append(e.getValue()[1]).append(" found records\n");
        List<String> never = new ArrayList<>();
        for (var src : RecordSources.forFields(RecordSources.all(), fieldNames())) if (RecordSources.usable(src) && !sourcesSearched.containsKey(src.name())) never.add(src.name());
        if (!never.isEmpty()) b.append("\nNot searched in this run: ").append(String.join("; ", never)).append(".\n");
        if (!nothingFound.isEmpty()) {
            b.append("\n### Searched and not found\n\nThese searches returned nothing. The words are the ones searched; another spelling, script or year range may still find a record.\n\n");
            for (String l : nothingFound) b.append("- ").append(l).append('\n');
        }
        return b.toString().stripTrailing();
    }

    /** How much of its words a critic's gap shares with a declined sub-question before it counts as that sub-question asked again. */
    static final double SAME_WORDS = 0.5;

    /** What a whole declined run is said to have declined. */
    static final String WHOLE_RUN = "to research this question";

    /**
     * Every decline of this run, in the order seen, each with the step it was at. ResearchZosho does not decide what may be researched; the
     * model the person chose does, and a decline is said, never worked around: a declined sub-question is not sent round again in other
     * words, a declined plan or write-up ends the run, and nothing is handed to another model because of it (decided 2026-09-23).
     */
    private final List<Declined> declines = new CopyOnWriteArrayList<>();
    /** The sub-questions the model declined: never researched again this run, whoever proposes them. */
    private final Set<String> declinedSubs = ConcurrentHashMap.newKeySet();
    /** Every sub-question this run sent to a worker, the plan's and the critic's, in order. */
    private final List<String> subsAsked = new CopyOnWriteArrayList<>();

    /** The sub-questions of the last run that the model did not decline. */
    List<String> subsNotDeclined() { return subsAsked.stream().filter(s -> !declinedSubs.contains(s)).distinct().toList(); }

    private void declined(Declined d) {
        declines.add(d);
        log.accept("declined: " + d.getMessage());
        ObjectNode o = J.createObjectNode(); o.put("step", d.step()); o.put("seat", d.seat()); o.put("model", d.model()); o.put("how", d.how().name().toLowerCase(Locale.ROOT));
        event("declined_step", o);
    }

    /**
     * "## Left out": the pages this run left out, each as its category and its address, nothing of what they said. "" when it left nothing
     * out.
     */
    String leftOutSection() {
        List<ContentPolicy.LeftOut> out = runPolicy.leftOut();
        if (out.isEmpty()) return "";
        StringBuilder b = new StringBuilder("## Left out\n\nWritten by the library. These pages were left out of this research: they were not saved, not shown to the model and not cited.\n\n");
        for (ContentPolicy.LeftOut l : out) b.append("- ").append(l.line()).append('\n');
        return b.toString().stripTrailing();
    }

    private static final Pattern NOTE_URL = Pattern.compile("https?://[^\\s)\\]>\"']+");

    /** A worker's notes without the notes that rest on a page this run left out: such a page is never cited, even from its search snippet. */
    String withoutLeftOut(String piece) {
        if (piece == null || runPolicy.leftOut().isEmpty()) return piece;
        StringBuilder b = new StringBuilder();
        for (String line : piece.split("\n", -1)) {
            if (line.startsWith("- ")) {
                Matcher m = NOTE_URL.matcher(line);
                boolean rests = false;
                while (m.find() && !rests) rests = runPolicy.wasLeftOut(m.group().replaceAll("[.,;:]+$", ""));
                if (rests) continue;
            }
            b.append(b.isEmpty() ? "" : "\n").append(line);
        }
        return b.toString();
    }

    /** "## Declined": written by the library from what the run saw, whenever the model declined part of the work. "" when it declined nothing. */
    String declinedSection() {
        if (declines.isEmpty()) return "";
        StringBuilder b = new StringBuilder("## Declined\n\nWritten by the library, not by the model. The model this library uses declined the parts of this research listed here. "
                + "ResearchZosho did not try to get around it: it did not ask again in other words, and it did not hand them to another model. What the report says "
                + "comes from the rest of the work.\n\n");
        for (Declined d : declines) {
            b.append("- ").append(d.model().isEmpty() ? "The model" : "The model " + d.model()).append(" declined ").append(d.step().isEmpty() ? "a step of the work" : d.step()).append('.');
            if (!d.howSaid().isEmpty()) b.append(' ').append(d.howSaid());
            b.append(d.said().isEmpty() ? " It gave no words with it." : " What it said: \"" + d.quoted() + "\"").append('\n');
        }
        return b.toString().stripTrailing();
    }

    /** The run ends here because the model declined {@code d}: nothing is written or filed, and the statement says so. */
    private Result declinedRun(Declined d, Budget budget, String evidence, int subQuestions, int rounds, List<String> notes) {
        if (!declines.contains(d)) declined(d);
        notes.add("declined: " + d.statement(WHOLE_RUN));
        runStats.put("declined", "run");
        runStats.put("declined_parts", declines.size());
        return new Result(false, "", evidence, budget.used(), subQuestions, rounds, List.copyOf(notes), List.of(), runStats.deepCopy(), List.copyOf(declines), d);
    }

    /** The profiles whose field the current question belongs to; their rules join the planner, the workers, the critic and the writer. */
    private volatile List<Profile> fields = List.of();
    private final Set<String> laneRetried = ConcurrentHashMap.newKeySet();
    private final Set<String> laneRan = ConcurrentHashMap.newKeySet();
    private final Tools tools;
    private final Consumer<String> log;
    /**
     * Set by the daemon for a job: true when a person asked for the run to stop. It is checked before every turn, on every worker, and
     * the whole run works under it ({@link Stopping}): a request to the model, a page fetch or a search in flight ends within a second of
     * the stop, its connection closed.
     */
    private volatile BooleanSupplier stopWhen = () -> false;
    public void stopWhen(BooleanSupplier s) { this.stopWhen = s == null ? () -> false : s; }

    /**
     * Asked just before the report is filed: false when a stop came first, and then nothing is filed. The daemon asks its job ledger
     * ({@link Jobs#beginFiling}), which marks the job as filing its report, so that a stop and the filing never cross; without it, the
     * run's own stop is enough.
     */
    private volatile BooleanSupplier beginFiling = () -> true;
    public void beginFiling(BooleanSupplier b) { this.beginFiling = b == null ? () -> true : b; }

    /** Whether a person asked for this run to stop. */
    boolean stopAsked() { return stopWhen.getAsBoolean() || Stopping.requested(); }
    /** Thrown when the run was stopped, at a turn boundary or in the middle of a call; carries no evidence, the job ledger says who stopped it. */
    public static class Stopped extends Stopping.Requested {
        public Stopped() { super("stopped by the person"); }
        protected Stopped(String statement) { super(statement); }
    }

    /** A stop seen in the middle of a call, as the run's own: the run ends there. */
    private Stopped stopped(Stopping.Requested r) {
        if (r instanceof Stopped s) return s;
        log.accept("stopped: a person stopped this run; what it was waiting for was given up, and nothing of it is filed");
        return new Stopped();
    }

    /**
     * Thrown at a turn boundary when the page check could not run on the last few pages in a row while the run's model answers
     * ({@link ContentPolicy#cannotCheck}): every page would be left out and nothing read, so the run stops, and its message is the plain
     * statement of what happened, why, and what to set.
     */
    public static final class CannotCheck extends Stopped {
        public CannotCheck(String statement) { super(statement); }
    }
    private final int workers;
    private final LibraryStore store;   // for the cite-check's raw captures and independence clusters; null in a bare unit test
    /** Where the run stands, for the job record: set by the daemon; a client reads it to know when to poll again. */
    private volatile Consumer<ObjectNode> onProgress = null;
    /** Whether this run may read the owner's databases: only a run the library owner filed. */
    private volatile boolean databases = false;
    public void allowDatabases(boolean yes) { this.databases = yes; }
    public void onProgress(Consumer<ObjectNode> sink) { this.onProgress = sink; }
    /** The run's trace, when the daemon opened one: the runner writes its own events (a compaction) to it. */
    private RunTrace trace = null;
    public void trace(RunTrace t) { this.trace = t; }
    private void event(String type, ObjectNode data) { if (trace != null) trace.event(type, data); }
    /** What the run counted, for the ledger: the cite-check, references, coverage flags, walls, sources noted, fetches, critic gaps. */
    private final ObjectNode runStats = J.createObjectNode();
    /** The evidence fitted to the window for a judgment step, with the cut recorded on the trace when there was one. */
    private String fitted(String step, List<String> pieces) {
        long before = 0; for (String p : pieces) before += p.length();
        String out = fitNotes(pieces, drive.contextWindow());
        if (out.length() < before) { ObjectNode o = J.createObjectNode(); o.put("step", step); o.put("pieces", pieces.size()); o.put("chars_before", before); o.put("chars_after", out.length()); o.put("context_tokens", drive.contextWindow()); event("compaction", o); }
        return out;
    }
    private volatile String phase = "";
    private volatile int round = 0, workersTotal = 0;
    private final AtomicInteger workersDone = new AtomicInteger();
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
        o.put("turns_used", b == null ? 0 : b.used()); o.put("turns_ceiling", b == null ? 0 : b.ceiling());   // 0 = no turn ceiling
        if (b != null && b.deadlineMs() > 0) o.put("deadline_at", Instant.ofEpochMilli(b.deadlineMs()).toString());
        o.put("at", Instant.now().toString());
        try { sink.accept(o); } catch (Exception ignored) { }
    }
    /** The turns count moves while the workers read, not only when one of them finishes: a first round of eight workers showed "turns 2" for half an hour. */
    static volatile long PROGRESS_EVERY_MS = 20_000;
    private volatile long lastProgressAt = 0;
    private void progressTick() {
        long now = System.currentTimeMillis();
        if (now - lastProgressAt < PROGRESS_EVERY_MS) return;
        lastProgressAt = now;
        progress(phase);
    }
    private final List<String> unaffordableFromPlan = Collections.synchronizedList(new ArrayList<>());
    private Set<String> wallsSeenAtStart = null;
    private final AtomicInteger readsInRun = new AtomicInteger();   // shelf pages read this run — sources too
    private final Set<String> fetchedInRun = Collections.synchronizedSet(new LinkedHashSet<>());   // every source a worker read this run
    private final AtomicInteger servedFromRun = new AtomicInteger();   // fetches answered from a page another reader read this run

    /**
     * A page another reader read this run, served from its capture instead of fetched again: the same text, centred on
     * {@code find} when given. Null when the page was not read this run or has no capture. (A third of all fetches were
     * repeats, measured over five runs on 2026-09-12; jmlon counted 2 of 5 citations as one URL fetched twice.)
     */
    String servedFromRun(JsonNode args) {
        if (store == null) return null;
        String url = args.path("url").asText("").strip();
        if (url.isEmpty()) return null;
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        String canon = Fetch.canonical(url);
        String hit = null;
        synchronized (fetchedInRun) { for (String f : fetchedInRun) if (Fetch.canonical(f).equals(canon)) { hit = f; break; } }
        if (hit == null) return null;
        try {
            Path rp = RawCapture.find(store, hit);
            if (rp == null) return null;
            String[] r = RawCapture.read(rp);
            String text = r[2] == null ? "" : r[2];
            String find = args.path("find").asText("").strip();
            int at = find.isEmpty() ? -1 : text.toLowerCase(Locale.ROOT).indexOf(find.toLowerCase(Locale.ROOT));
            int from = at < 0 ? 0 : Math.max(0, at - PAGE_CHARS / 3);
            String body = text.substring(from, Math.min(text.length(), from + PAGE_CHARS));
            return "source: " + hit + (r[1] == null || r[1].isEmpty() ? "" : " — " + r[1]) + "\n(read earlier this run by another reader; the same page, from the library's copy)\n" + body;
        } catch (Exception e) { return null; }
    }

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
        String[] seat = judgeSeat(workersDrive, workersModel);
        return drive(seat[0], seat[1]);
    }

    /** The judgment seat's drive and model: RESEARCHZOSHO_JUDGE_DRIVE and RESEARCHZOSHO_JUDGE_MODEL when set, else the workers'. */
    static String[] judgeSeat(String workersDrive, String workersModel) {
        String jd = Config.get("RESEARCHZOSHO_JUDGE_DRIVE");
        if (jd == null || jd.isBlank()) return new String[]{workersDrive, workersModel};
        return new String[]{jd, Config.get("RESEARCHZOSHO_JUDGE_MODEL", workersModel)};
    }

    /**
     * A seat's drive, watched for a model that declines ({@link Declines}): the seat's own model reads its suspect replies, and a decline
     * is said, never worked around. {@code notes} takes the replies the judge was unsure of: a run's trace, or the crews log.
     */
    public static Drive watched(String baseUrl, String model, String seat, Declines.Notes notes) {
        DriveClient c = new DriveClient(baseUrl, model);
        return Declines.watch(drive(c), seat, model, DeclineJudge.of(c), notes);
    }

    /** The judgment seat, watched. */
    public static Drive watchedJudge(String workersDrive, String workersModel, Declines.Notes notes) {
        String[] seat = judgeSeat(workersDrive, workersModel);
        return watched(seat[0], seat[1], "judge", notes);
    }

    /** The conversation's seat (calm, the judgment seat when one is set), watched. */
    public static Drive watchedChat(String workersDrive, String workersModel) {
        String[] seat = judgeSeat(workersDrive, workersModel);
        DriveClient c = new DriveClient(seat[0], seat[1]);
        return Declines.watch(calmDrive(c), "chat", seat[1], DeclineJudge.of(c), null);
    }

    // ---- the run ----

    /** The model that checks the pages this runner's runs fetch; null: the library's configured model. The daemon names the run's own. */
    private volatile ContentJudge contentJudge = null;
    public void contentJudge(ContentJudge j) { this.contentJudge = j; }

    /** The current run's content policy: what it lets in, the check its pages go through, and what it left out. */
    private volatile ContentPolicy runPolicy = ContentPolicy.run(List.of(), null, null);

    /** What the last run left out, by address and category. */
    List<ContentPolicy.LeftOut> leftOut() { return runPolicy.leftOut(); }

    /**
     * Run one ask. {@code known} is the library's context block for the question ("" when none). The run works under its stop: when a
     * person asks for it, whatever the run is waiting for ends, and the run ends as {@link Stopped} and returns nothing to file.
     */
    public Result run(Ask ask, String known) {
        BooleanSupplier stop = () -> stopWhen.getAsBoolean();
        Result r;
        try { r = Stopping.within(stop, () -> running(ask, known)); }
        catch (Stopping.Requested req) { throw stopped(req); }
        if (stop.getAsBoolean()) throw stopped(new Stopping.Requested());   // stopped while a step swallowed the stop: nothing of it is filed
        return r;
    }

    private Result running(Ask ask, String known) {
        Budget budget = new Budget(ask.maxTurns(), ask.maxMinutes());
        currentBudget = budget;
        runPolicy = ContentPolicy.run(ask.allow(), contentJudge, store).log(line -> { log.accept(line); ObjectNode o = J.createObjectNode(); o.put("line", line); event("left_out", o); });
        if (runPolicy.lets(ContentPolicy.EXPLICIT) || runPolicy.lets(ContentPolicy.HOWTO))
            log.accept("this run lets in what the library leaves out by default, because the person asked for it: " + String.join(", ", runPolicy.allow().stream().filter(a -> !a.equals(ContentPolicy.SELF_HARM)).toList()));
        synchronized (WebFetchTool.WALLS) { wallsSeenAtStart = new HashSet<>(WebFetchTool.WALLS); }
        List<String> notes = new ArrayList<>();
        String knownBlock = known == null ? "" : known;

        // 1. Plan
        currentBudgetForProgress = budget;
        round = 0; workersTotal = 0; workersDone.set(0);
        progress("planning");
        declines.clear(); declinedSubs.clear(); subsAsked.clear();
        List<String> open;
        try (var planning = Declines.step("plan research on a question: " + Acquisitions.compress(ask.question(), 300), true)) {   // a decline here ends the run: the higher bar
            askedFormat = formatAsked(ask, budget);
            if (!askedFormat.isEmpty()) log.accept("format asked for: " + askedFormat);
            open = plan(ask, knownBlock, budget);
        } catch (Declined d) {
            return declinedRun(d.at("to plan this research"), budget, "", 0, 0, notes);   // the plan is not tried another way
        }
        int subCount = open.size();
        subsAsked.addAll(open);
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
            // search each (a test box, I-0002, 2026-09-11).
            if (round == 1 && ROUNDS > 1 && budget.deadlineMs() > 0) budget.roundDeadline(System.currentTimeMillis() + (budget.deadlineMs() - System.currentTimeMillis()) * 3 / 5);
            else budget.roundDeadline(0);
            this.round = round; workersTotal = open.size(); workersDone.set(0);
            progress("workers");
            for (String piece : investigateAll(ask, open, budget)) evidence.add(withoutLeftOut(piece));   // a page left out is never cited
            open.clear();
            // the model declined every part that ran: nothing to review or write from, and no critic is asked to find other words for it
            if (evidence.isEmpty() && !declinedSubs.isEmpty()) return declinedRun(declines.get(0), budget, "", subCount, rounds, notes);
            if (round == ROUNDS) break;
            if (budget.workersTimeUp() || (!budget.unbounded() && budget.left() <= budget.reserve())) { runStats.put("rounds_cut", true); log.accept("rounds: no room for the critic and a second round within the ceiling"); break; }
            progress("critic");
            List<String> missing = critic(ask, evidence, budget);
            runStats.put("critic_gaps", runStats.path("critic_gaps").asInt(0) + missing.size());
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
            subsAsked.addAll(missing);
            subCount += missing.size();
            log.accept("critic: " + missing.size() + " gap(s) → round " + (round + 1));
        }

        // 4. Synthesis
        progress("synthesis");
        String evidenceText = String.join("\n\n", evidence);
        Synthesis syn;
        try { syn = synthesize(ask, knownBlock, evidence, budget); }
        catch (Declined d) { return declinedRun(d.at("to write the report"), budget, evidenceText, subCount, rounds, notes); }
        notes.add("synthesis: " + (syn.done ? "finished" : "cut off") + ", " + syn.sections.size() + " section(s)");
        log.accept(notes.get(notes.size() - 1));
        // 5. The harness's own sections: references numbered and clustered for independence, the evidence table,
        //    and the cite-check of the model's sentences against the captured sources.
        progress("cite-check");
        String answer = assemble(ask, syn, evidenceText, budget, notes);
        String declinedPart = declinedSection();
        if (!declinedPart.isEmpty()) { answer = answer + "\n\n" + declinedPart; notes.add("declined: " + declines.size() + " part(s) of the work"); log.accept(notes.get(notes.size() - 1)); }
        String leftOutPart = leftOutSection();
        if (!leftOutPart.isEmpty()) answer = answer + "\n\n" + leftOutPart;
        runStats.put("left_out", runPolicy.leftOut().size());
        runStats.put("declined", declines.isEmpty() ? "" : "part");
        runStats.put("declined_parts", declines.size());
        progress("filing");
        List<String> openAll = new ArrayList<>(unaffordableFromPlan); openAll.addAll(unaffordable);
        runStats.put("ceiling_cut", !syn.done || runStats.path("rounds_cut").asBoolean(false) || runStats.path("citecheck_cut").asBoolean(false));
        runStats.put("critic_ran", runStats.has("critic_gaps"));
        runStats.put("unaffordable_gaps", openAll.size());
        runStats.put("sources_noted", notedSources(evidenceText).size());
        runStats.put("fetches_distinct", fetchedInRun.size());
        runStats.put("fetches_served_from_run", servedFromRun.get());
        runStats.put("shelf_reads", readsInRun.get());
        return new Result(syn.done, answer, evidenceText, budget.used(), subCount, rounds, List.copyOf(notes), List.copyOf(openAll), runStats.deepCopy(), List.copyOf(declines), null);
    }

    // ---- the shelf ----

    /** What filing an ask produced: the investigation id when admitted, else the gate's reason. */
    public record Filed(String investigationId, String reason, Result result) {
        public boolean admitted() { return investigationId != null; }
        /** The model declined the run: nothing was filed, and {@code reason} is the statement that says so. */
        public boolean declined() { return result != null && result.ended() != null; }
    }

    /**
     * Run an ask and put it on the shelf through the acquisitions gate. The evidence goes into the
     * investigation with the answer, so a cut-off synthesis still leaves the work on record; a
     * refused run leaves a frontier gap. {@code writer} is who asked ("patron:…", "crew:explorer").
     */
    /** What the library already holds for a question, as a run is shown it: the claims, the searches that found nothing, the search log. */
    static String known(LibraryStore store, String question) throws IOException { return known(store, question, List.of()); }

    /** The same for a run of these fields: the search log leaves out other fields' runs, and each field adds what it works out. */
    static String known(LibraryStore store, String question, List<String> fields) throws IOException {
        return LibraryPush.block(store, question, 6) + Looked.block(store, question, 12, fields) + SearchLog.block(store, question, 40, fields);
    }

    public static Filed file(LibraryStore store, Researcher researcher, Ask ask, String writer) throws IOException { return file(store, researcher, ask, writer, "", "asked"); }

    /**
     * The nightly research of one open question, or a bundle of them, on the drive: a run of {@code field} when the question is that
     * field's ("" for an ordinary one). Returns the report's id, or null when the gate refused it.
     */
    static String forExplorer(LibraryStore store, String driveUrl, String model, String question, List<String> subQuestions, int maxTurns, int maxMinutes, String field, String writer, Consumer<String> log) throws IOException {
        Declines.Notes notes = Declines.toCrewsLog(store, "explorer");   // the nightly run has no trace: what the judge was unsure of goes on the crews log
        Researcher runner = new Researcher(watched(driveUrl, model, "workers", notes), watchedJudge(driveUrl, model, notes), webTools(), log, store);
        ContentJudge contentJudge = ContentJudge.of(new DriveClient(driveUrl, model));
        runner.contentJudge(contentJudge);
        // nobody is there at night to be shown where to find help, or to say yes: a question that reads as a person asking about harming
        // themselves is not researched at night
        if (ContentOffer.harm(question, contentJudge) == ContentOffer.Harm.SURE) throw new ContentOffer.NotStarted(ContentOffer.NOT_STARTED);
        Ask ask = nightlyAsk(question, subQuestions, maxTurns, maxMinutes, field);
        Filed filed = file(store, runner, ask, writer, "", "explorer-line");
        if (filed.declined()) throw filed.result().ended().at(WHOLE_RUN);   // the explorer marks the question as declined, not as refused
        return filed.investigationId();
    }

    /**
     * The ask of a nightly run. It never lets in what the library leaves out by default: nobody was asked, so nobody said yes, and there
     * is no argument here to say it with.
     */
    static Ask nightlyAsk(String question, List<String> subQuestions, int maxTurns, int maxMinutes, String field) {
        return new Ask(question, "broad", maxTurns, subQuestions, "both", List.of(), maxMinutes, field == null || field.isEmpty() ? List.of() : List.of(field), List.of());
    }

    /**
     * As above, for job {@code jobId}. A run of a field somebody asked for is written into the fields ledger ({@link Fields}) before its
     * report exists, with {@code how} the field was chosen, so the claims the review files from it are the field's work.
     */
    public static Filed file(LibraryStore store, Researcher researcher, Ask ask, String writer, String jobId, String how) throws IOException { return file(store, researcher, ask, writer, jobId, how, "asked"); }

    /**
     * As above; {@code allowHow}: how the person asked to let in what the run lets in ({@link Ask#allow}), for the ledger
     * {@code catalog/run-content.tsv}, written before the report, and for the sentence under the report's question.
     */
    public static Filed file(LibraryStore store, Researcher researcher, Ask ask, String writer, String jobId, String how, String allowHow) throws IOException {
        var snap = Acquisitions.Snapshot.take();
        Result r = researcher.run(ask, known(store, ask.question(), ask.fields()));
        // a stop that came after the research and before anything of it is written down: nothing is filed, as the stop says
        if (researcher.stopAsked()) { researcher.log.accept("stopped: a person stopped this run after its research, before its report was filed; nothing of it is filed"); throw new Stopped(); }
        // the model declined the run: said as that, never as a refusal at the gate, and never put back on the open questions to run again
        if (r.ended() != null) { store.circulate("declined", Acquisitions.compress(ask.question(), 120)); return new Filed(null, r.declinedStatement(), r); }
        boolean answered = r.done() || !r.answer().isBlank();
        // sources READ = web fetches this run + shelf documents read page by page (a shelves-only ask fetches nothing)
        var gate = Acquisitions.gate(answered, r.answer(), r.evidence(), snap.fetchesSince() + researcher.readsInRun.get(), snap.degradedSince());
        if (!gate.admitted()) {
            if (r.declines().isEmpty()) Acquisitions.refuse(store, ask.question(), gate.reason());
            // the model declined part of it: what goes back on the open questions is the parts it did not decline, never the question
            // whole, which would send the declined parts to the model again another night
            else for (String sub : researcher.subsNotDeclined())
                Acquisitions.refuse(store, sub, gate.reason() + "; a part of \"" + Acquisitions.compress(ask.question(), 100) + "\", whose other part(s) the model declined and which are not put back");
            return new Filed(null, gate.reason(), r);
        }
        String answer = r.done() ? r.answer()
                : "(the synthesis was cut off at its deadline — the sections it wrote, then the evidence)\n\n" + r.answer();
        List<String> asked = new ArrayList<>();
        for (String f : ask.fields()) if (Profiles.named(f) != null) asked.add(Profiles.named(f).name());
        List<String> letIn = ask.allow().stream().filter(a -> a.equals(ContentPolicy.EXPLICIT) || a.equals(ContentPolicy.HOWTO)).toList();
        // the report is filed now: a stop that came first files nothing; one that comes after leaves the report, and the stop says so
        if (researcher.stopAsked() || !researcher.beginFiling.getAsBoolean()) { researcher.log.accept("stopped: a person stopped this run before its report was filed; nothing of it is filed"); throw new Stopped(); }
        var inv = Acquisitions.admit(store, new LibrarianIndex(store), ask.question(), answer, writer, r.evidence(),
                id -> {
                    for (String f : asked) Fields.record(store, id, jobId, f, how);
                    if (!letIn.isEmpty()) ContentOffer.record(store, id, jobId, letIn, allowHow);   // before the report, as the fields ledger is
                }, ContentOffer.reportSentence(letIn));
        // the research log: every search this run made, under the question's subject, with the report it belongs to
        List<SearchLog.Entry> made = new ArrayList<>();
        for (SearchLog.Entry e : researcher.searches()) made.add(new SearchLog.Entry(e.date(), ask.question(), e.where(), e.query(), e.fromYear(), e.toYear(), e.found(), inv.id(), e.status()));
        SearchLog.add(store, made);
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
        Matcher m = Pattern.compile("— source: (\\S+?)(?: \\(|\\s—|\\n|$)").matcher(evidence);
        while (m.find()) { String loc = m.group(1).replaceAll("[),.;]+$", ""); if (!out.contains(loc)) out.add(loc); }
        for (String u : Acquisitions.urls(evidence)) if (!out.contains(u)) out.add(u);
        return out;
    }

    /** ", 6 days ago" for a page published within a month, so the reader sees how new it is; no verdict — for news, new is the point. */
    static String ageNote(String published) {
        try {
            long days = ChronoUnit.DAYS.between(LocalDate.parse(published), LocalDate.now());
            return days >= 0 && days <= 30 ? ", " + days + " day" + (days == 1 ? "" : "s") + " ago" : "";
        } catch (Exception e) { return ""; }
    }

    /** "## Languages of the sources": which languages the notes came from, and a lane that found nothing says so. */
    String languagesSection(String evidence) {
        Map<String, Integer> read = Lanes.languagesRead(evidence);
        if (read.isEmpty() && lanes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## Languages of the sources\n\n").append(Lanes.describe(read)).append(" (notes per language).");
        for (Lanes.Lane lane : lanes) {
            if (declinedSubs.stream().anyMatch(s -> laneOf.get(s) == lane)) { sb.append(" The model declined the ").append(lane.name()).append("-language lane (see Declined)."); continue; }
            if (read.getOrDefault(lane.code(), 0) > 0) sb.append(" ").append(lane.name()).append("-language sources were searched on their own lane.");
            else if (!laneRan.contains(lane.code())) sb.append(" The ").append(lane.name()).append("-language lane did not run within this ask's limits; what ").append(lane.name()).append(" sources say is an open question.");
            else sb.append(" No ").append(lane.name()).append("-language source was found").append(laneRetried.contains(lane.code()) ? " in two tries" : "").append("; what ").append(lane.name()).append(" sources say is an open question.");
        }
        return sb.toString();
    }

    /** The search counters when the run started; the differences are this run's. */
    final int unreachableAtStart = WebSearchTool.UNREACHABLE.get(),
            fallbackAtStart = WebSearchTool.FALLBACK_USED.get(),
            braveAtStart = WebSearchTool.BRAVE_USED.get(),
            searxAtStart = WebSearchTool.SEARXNG_USED.get();

    /** "## Web search": which backend the run searched through, when that is worth knowing: the fallback, or none at all. */
    String webSearchSection(Ask ask) {
        if (!ask.web()) return "";
        int none = WebSearchTool.UNREACHABLE.get() - unreachableAtStart;
        int fb = WebSearchTool.FALLBACK_USED.get() - fallbackAtStart;
        int brave = WebSearchTool.BRAVE_USED.get() - braveAtStart;
        int searx = WebSearchTool.SEARXNG_USED.get() - searxAtStart;
        if (fb > 0) return "## Web search\n\n" + fb + (fb == 1 ? " search" : " searches") + " went through the built-in fallback (Wikipedia, Crossref and OpenAlex: reference pages and papers, no web engine)"
                + (brave + searx > 0 ? ", " + (brave + searx) + " through " + (brave > 0 ? "Brave" : "SearXNG") : "")
                + ". A Brave Search API key or a SearXNG (`researchzosho search start`) searches the whole web.";
        if (none > 0 && brave + searx == 0) return "## Web search\n\nNo search backend answered at " + WebSearchTool.endpoint() + " (" + none
                + (none == 1 ? " search" : " searches") + " failed). This run read only the documents on the shelves. "
                + "`researchzosho setup` adds a Brave Search API key or starts SearXNG.";
        return "";
    }

    /** The retraction look-up the write-up's DOIs are checked against at assembly; Crossref by default, a fake in tests. Null = skip. */
    static volatile Retractions.Lookup retractionLookup = null;
    static Retractions.Lookup retractionLookup() { return retractionLookup != null ? retractionLookup : Retractions.live(); }
    static final int RETRACTION_LOOKUPS = 25;

    /** "[n] <doi>: retracted on <date> (notice <doi>)" for every cited DOI Crossref lists a retraction for, up to {@link #RETRACTION_LOOKUPS}. */
    static List<String> retractedAmong(List<CiteCheck.Ref> refs) {
        List<String> out = new ArrayList<>();
        int looked = 0;
        for (CiteCheck.Ref r : refs) {
            String id = Citations.identify(r.locator());
            if (id == null || !id.startsWith("doi:") || looked >= RETRACTION_LOOKUPS) continue;
            looked++;
            try {
                Retractions.Notice n = retractionLookup().notice(id.substring(4));
                if (n != null && Retractions.retracts(n.type())) out.add("[" + r.n() + "] " + r.locator() + ": " + n.type().replace('_', ' ') + " on " + n.date() + (n.noticeDoi().isEmpty() ? "" : " (notice " + n.noticeDoi() + ")"));
            } catch (Exception ignored) { }   // an unreachable Crossref is not a retraction
        }
        return out;
    }

    /**
     * The works a write-up rests on, in first-seen order: every source the workers noted, every page a worker read, then
     * every URL the text names; locators sharing a DOI / arXiv / PubMed id or a captured file fold into one work; a
     * capture that turned out to be a wall is not a reference. The order is stable across a call before the write-up
     * ({@code text} = "") and after it: the writer cites by the numbers it was shown, and the cite-check maps by them.
     */
    Map<String, List<String>> works(String evidence, String text) {
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
        Map<String, List<String>> byWork = new LinkedHashMap<>();
        if (locators.isEmpty()) return byWork;
        // one reference per WORK: locators sharing a DOI / arXiv / PubMed id fold into one entry (an article page,
        // its PDF and its citation line are one source — measured live as three)
        // a shelved file noted by its bare name ("source: guardrails.md") is the file:// capture of that name, not a
        // second reference (measured, J-0009: eleven references for five files, and the cite-check mapped the bare one)
        Map<String, String> bareToFile = new HashMap<>();
        for (String loc : locators) if (loc.startsWith("file://")) bareToFile.put(loc.substring(loc.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT), loc);
        for (String loc : locators) {
            boolean bare = !loc.contains("://") && !loc.startsWith("cite:") && !loc.startsWith("raw/") && loc.matches("[^/\\s]+\\.[A-Za-z0-9]{1,5}");
            String file = bare ? bareToFile.get(loc.toLowerCase(Locale.ROOT)) : null;
            String canon = loc.startsWith("http") ? Fetch.canonical(loc) : file != null ? file : loc;
            String ident = Citations.identifyCaptured(store, loc);
            List<String> group = byWork.computeIfAbsent(ident == null ? canon : ident, k -> new ArrayList<>());
            if (file != null && !group.contains(file)) group.add(0, file);   // the capture leads the group; the bare name follows
            if (!group.contains(loc)) group.add(loc);
        }
        // a capture that turned out to be a wall is not a reference
        byWork.values().removeIf(group -> {
            try {
                Path rp = RawCapture.find(store, group.get(0));
                if (rp == null) return false;
                String[] r = RawCapture.read(rp);
                return Fetch.wall(r[1], r[2]) != null;
            } catch (Exception e) { return false; }
        });
        return byWork;
    }

    /** The primary locator of each work: a URL when the group has one, else the first that is not a citation line. */
    static String primaryOf(List<String> group) {
        return group.stream().filter(l -> l.contains("://")).findFirst().orElse(group.stream().filter(l -> !l.startsWith("cite:")).findFirst().orElse(group.get(0)));
    }

    /**
     * "[n] title — locator" for every work the workers noted or read, numbered as the references will be: the writer
     * cites by number and the cite-check maps by number (surf-sense's harness-assigned source ids; measured on
     * 2026-09-12: four in five parentheticals named no mappable source, so the check read a fifth of the citations).
     */
    String sourcesForWriter(String evidence) {
        if (store == null) return "";
        Map<String, List<String>> byWork = works(evidence, "");
        if (byWork.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (List<String> group : byWork.values()) {
            n++;
            String loc = primaryOf(group);
            String title = "";
            try { Path rp = RawCapture.find(store, loc); if (rp != null) title = Pages.unentity(RawCapture.read(rp)[1]); } catch (Exception ignored) { }
            String edition = "";
            for (String l : group) if (l.startsWith("cite:")) { edition = l.substring(5); break; }
            b.append('[').append(n).append("] ").append(!edition.isEmpty() ? edition + " — " : !title.isEmpty() ? Acquisitions.compress(title, 90) + " — " : "").append(loc).append('\n');
        }
        return b.toString();
    }

    String assemble(Ask ask, Synthesis syn, String evidence, Budget budget, List<String> notes) {
        String text = syn.text();
        String languages = languagesSection(evidence);
        String web = webSearchSection(ask);
        if (!web.isEmpty()) { languages = languages.isEmpty() ? web : web + "\n\n" + languages; notes.add("web search: no backend answered"); log.accept(notes.get(notes.size() - 1)); }
        if (!languages.isEmpty()) { notes.add("languages: " + Lanes.describe(Lanes.languagesRead(evidence))); log.accept(notes.get(notes.size() - 1)); }
        String searched = searchedSection();
        if (!searched.isEmpty()) { languages = languages.isEmpty() ? searched : searched + "\n\n" + languages; notes.add("record searches with no result: " + nothingFound.size()); log.accept(notes.get(notes.size() - 1)); }
        if (store == null) return languages.isEmpty() ? text : text + "\n\n" + languages;
        Map<String, List<String>> byWork = works(evidence, text);
        if (byWork.isEmpty()) return searched.isEmpty() ? text : text + "\n\n" + searched;   // nothing read at all: what was searched is then the whole record of the work
        List<String> primary = new ArrayList<>();
        for (List<String> group : byWork.values()) primary.add(primaryOf(group));
        Set<String> noted = new HashSet<>(notedSources(evidence));
        Map<String, Integer> clusters = Independence.clusters(store, primary);
        Map<String, String> derivatives = Independence.derivatives(store, primary);
        int independent = Independence.independent(store, primary);
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
            try { Path rp = RawCapture.find(store, loc); if (rp != null) title = Pages.unentity(RawCapture.read(rp)[1]); } catch (Exception ignored) { }
            for (String l : group) { numberOfLocator.put(l, n); if (edition.isEmpty() && l.startsWith("cite:")) edition = l.substring(5); }
            // a page record_search found is cited as the record it is: the paper, the date, the page, the holder
            if (edition.isEmpty()) edition = citedAsRecord(group);
            refs.add(new CiteCheck.Ref(n, loc, edition, title));
            int c = clusters.getOrDefault(loc, n);
            // two places in one file of a cloned repository are one source too, and are said to be the same file
            String first = firstOfCluster.containsKey(c) ? primary.get(firstOfCluster.get(c) - 1) : null, file = CodeTool.fileOf(loc);
            String same = first == null ? "" : "  (" + (file != null && file.equals(CodeTool.fileOf(first)) ? "the same file as [" : "same text as [") + firstOfCluster.get(c) + "])";
            firstOfCluster.putIfAbsent(c, n);
            if (same.isEmpty() && derivatives.containsKey(loc)) { int to = primary.indexOf(derivatives.get(loc)) + 1; if (to > 0 && to != n) same = "  (cites [" + to + "]; not an independent voice for what it says)"; }
            String published = "";
            try { Path rp = RawCapture.find(store, loc); if (rp != null) published = RawCapture.published(rp); } catch (Exception ignored) { }
            if (published.isEmpty()) published = Citations.arxivPosted(loc);   // the id says when it was posted; no page date needed
            if (!published.isEmpty()) { dated.add(published); }
            SourceRules.Rule rule = rules.ruleFor(loc);
            boolean citedInText = text.contains("[" + n + "]") || group.stream().anyMatch(l -> l.contains("://") && text.contains(l));
            boolean notedByWorker = group.stream().anyMatch(noted::contains);
            references.append('[').append(n).append("] ").append(edition.isEmpty() ? (title.isEmpty() ? "" : title + " — ") : edition + " — ").append(loc)
                      .append(!citedInText && !notedByWorker ? "  (read by a worker, not noted, not cited)" : "")
                      .append(published.isEmpty() ? "" : "  (published " + published + ageNote(published) + ")")
                      .append(rule == null ? "" : rule.kind().equals("trust") ? "  (a source you trust)" : "  (ON YOUR REFUSED LIST)");
            for (String l : group) if (!l.equals(loc) && !l.startsWith("cite:") && l.contains("://")) references.append("  also ").append(l);
            references.append(same).append('\n');
        }
        references.append("\n").append(byWork.size()).append(" source(s), ").append(independent).append(" independent (copies of one text count once, and a source that cites another adds nothing to it).");
        if (!dated.isEmpty()) {
            Collections.sort(dated);
            references.append(" Dated sources run from ").append(dated.get(0)).append(" to ").append(dated.get(dated.size() - 1)).append(dated.size() < byWork.size() ? "; " + (byWork.size() - dated.size()) + " give no date" : "").append('.');
        }
        references.append('\n');
        // the evidence table: every note, mechanically, claim | source | quote
        StringBuilder table = new StringBuilder("## Evidence\n\n| claim | source | quote |\n|---|---|---|\n");
        int rows = 0;
        // what a worker noted, with its source, and the answer never brought in: the writer chooses what to say, and a choice it made
        // without saying so is a finding the person never sees. These are listed after the answer, by the library.
        List<String> leftOut = new ArrayList<>();
        Matcher nm = Pattern.compile("^- (.+?) — source: (.+?)(?: — quote: \"(.*)\")?$", Pattern.MULTILINE).matcher(evidence);
        while (nm.find() && rows < 120) {
            String loc = nm.group(2).replaceAll("[),.;]+$", "");
            int ref = 0;
            Matcher um = Pattern.compile("(?:https?|file)://\\S+").matcher(loc);
            String url = um.find() ? um.group().replaceAll("[),.;]+$", "") : null;
            if (url != null && numberOfLocator.containsKey(url)) ref = numberOfLocator.get(url);
            if (ref == 0 && numberOfLocator.containsKey(loc)) ref = numberOfLocator.get(loc);   // a bare file name, folded into its capture's group
            String noteId = Citations.identifyCaptured(store, url != null ? url : loc);
            if (ref == 0 && url != null && numberOfLocator.containsKey(Fetch.canonical(url))) ref = numberOfLocator.get(Fetch.canonical(url));
            if (ref == 0 && noteId != null) for (String l : numberOfLocator.keySet()) if (noteId.equals(Citations.identify(l))) { ref = numberOfLocator.get(l); break; }
            if (ref == 0) for (CiteCheck.Ref r : refs) if (loc.startsWith(r.locator())) { ref = r.n(); break; }
            table.append("| ").append(cell(nm.group(1))).append(" | ").append(ref > 0 ? "[" + ref + "]" : cell(loc)).append(" | ").append(nm.group(3) == null ? "" : cell(nm.group(3))).append(" |\n");
            if (ref > 0 && !text.contains("[" + ref + "]") && !sameSaid(text, nm.group(1))) leftOut.add(nm.group(1).strip() + " [" + ref + "]");
            rows++;
        }
        // the cite-check: the model's cited sentences against the captured sources, on the judge
        List<String> pieces = new ArrayList<>(Arrays.asList(evidence.split("(?m)^(?=SUB-QUESTION: )")));
        pieces.removeIf(String::isBlank);
        List<String> coverageFlags = coverageCheck(text, pieces);
        for (String f : coverageFlags) { notes.add("coverage: " + f); log.accept(notes.get(notes.size() - 1)); }
        Set<String> readCanon = new HashSet<>();
        for (String l : notedSources(evidence)) readCanon.add(l.startsWith("http") ? Fetch.canonical(l) : l);
        synchronized (fetchedInRun) { for (String l : fetchedInRun) readCanon.add(l.startsWith("http") ? Fetch.canonical(l) : l); }
        Set<Integer> unread = new HashSet<>();
        { int k = 0; for (List<String> group : byWork.values()) { k++; boolean read = false; for (String l : group) if (readCanon.contains(l.startsWith("http") ? Fetch.canonical(l) : l) || l.startsWith("cite:") || l.startsWith("file://")) { read = true; break; } if (!read) unread.add(k); } }
        // the check reads a finished write-up: whatever goes wrong inside it, the write-up is kept and the report says the check did not run
        CiteCheck.Outcome cc;
        try {
            cc = CiteCheck.run(store, text, refs, judge, budget, unread);
            if (cc.declined() != null) declined(cc.declined().at("to check the citation of a sentence of the report"));   // the marks placed before it stand
        } catch (Declined d) {
            declined(d.at("to check the report's citations"));
            cc = new CiteCheck.Outcome(text, 0, 0, 0, 0, List.of("the model declined to check the citations (see Declined); they are unchecked"), 0, 0, 0);
        }
        catch (Stopping.Requested stop) { throw stopped(stop); }
        catch (RuntimeException e) { cc = new CiteCheck.Outcome(text, 0, 0, 0, 0, List.of("the citation check could not run on this report (" + e + "); its citations are unchecked"), 0, 0, 0); }
        runStats.put("cite_checked", cc.checked()); runStats.put("cite_supported", cc.supported()); runStats.put("cite_unsupported", cc.unsupported());
        runStats.put("cite_unmapped", cc.unmapped()); runStats.put("references", byWork.size()); runStats.put("coverage_flags", coverageFlags.size());
        runStats.put("cite_mechanical", cc.mechanical()); runStats.put("cite_overruled", cc.overruled()); runStats.put("cite_unretrieved", cc.unretrieved());
        // the write-up against its own evidence, mechanically: numbers with a unit, licence names and CVE ids that no note or source states;
        // quotations that appear in no source; and a cited paper Crossref lists as retracted
        List<String> sourceTexts = new ArrayList<>();
        Map<Integer, String> textByRef = new HashMap<>();
        for (CiteCheck.Ref r : refs) { try { Path rp = RawCapture.find(store, r.locator()); if (rp != null) { String t = RawCapture.read(rp)[2]; sourceTexts.add(t); textByRef.put(r.n(), t); } } catch (Exception ignored) { } }
        List<String> checks = new ArrayList<>();
        List<String> numbersOff = WriteupChecks.numbersUnbacked(text, evidence, refs, textByRef), namesOff = WriteupChecks.namesUnbacked(text, evidence, refs, textByRef), quotesOff = WriteupChecks.quotesUnbacked(text, withNotes(sourceTexts, evidence));
        for (String l : numbersOff) checks.add("number " + l);
        for (String l : namesOff) checks.add("name " + l);
        for (String l : quotesOff) checks.add("quotation " + l);
        List<String> retracted = retractedAmong(refs);
        for (String l : retracted) checks.add("retracted " + l);
        runStats.put("numbers_unbacked", numbersOff.size()); runStats.put("names_unbacked", namesOff.size()); runStats.put("quotes_unbacked", quotesOff.size()); runStats.put("retracted_cited", retracted.size());
        if (!checks.isEmpty()) { notes.add("checks: " + checks.size() + " line(s) — " + numbersOff.size() + " number(s), " + namesOff.size() + " name(s), " + quotesOff.size() + " quotation(s) unbacked, " + retracted.size() + " retracted source(s) cited"); log.accept(notes.get(notes.size() - 1)); }
        String checked = "cite-check: " + cc.checked() + " cited sentence(s) read against their source — " + cc.supported() + " supported, "
                + cc.unsupported() + " not supported (marked), " + (cc.checked() - cc.supported() - cc.unsupported()) + " undecidable from the excerpt; "
                + cc.unmapped() + " parenthetical(s) named no source (an aside, not a citation, counts here); " + byWork.size() + " reference(s)";
        notes.add(checked);
        log.accept(checked);
        StringBuilder out = new StringBuilder(cc.text());
        if (!checks.isEmpty()) {
            out.append("\n\n## Checks\n\nThe write-up against the evidence it was written from, mechanically. A number with a unit, a licence, a CVE id or a "
                    + "quotation that no note or source read this run states is listed here; a cited paper Crossref lists as retracted is named. Read these before the prose.\n\n");
            for (String c : checks) out.append("- ").append(c).append('\n');
        }
        for (String pr : cc.problems()) if (pr.startsWith("cite-check stopped")) runStats.put("citecheck_cut", true);
        if (!cc.problems().isEmpty()) {
            out.append("\n\n## Cite-check\n\n").append(checked).append(".\n");
            for (String p : cc.problems()) out.append("- ").append(p).append('\n');
        }
        if (!coverageFlags.isEmpty()) {
            out.append("\n\n## Coverage check\n\nThe answer claims an absence that the sub-investigations do not support:\n\n");
            for (String f : coverageFlags) out.append("- ").append(f).append('\n');
        }
        if (!leftOut.isEmpty()) {
            out.append("\n\n## Found and not in the answer above\n\nWritten by the library, not by the model. Each of these was noted during the research, with its source, "
                    + "and the answer neither cites that source nor says the same thing. Read them with the answer: what was left out may matter to you.\n\n");
            for (String l : leftOut.stream().distinct().limit(40).toList()) out.append("- ").append(l).append('\n');
        }
        runStats.put("noted_left_out", leftOut.size());
        if (rows > 0) out.append("\n\n").append(table);
        references.append(checked).append(".\n");
        out.append("\n\n").append(references);
        if (!languages.isEmpty()) out.append("\n\n").append(languages);
        List<String> walls = new ArrayList<>();
        synchronized (WebFetchTool.WALLS) { for (String w : WebFetchTool.WALLS) if (wallsSeenAtStart == null || !wallsSeenAtStart.contains(w)) walls.add(w); }
        runStats.put("walls", walls.size());
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
        fields = Fields.forRun(store, ask);
        if (!fields.isEmpty()) log.accept("field: " + String.join(", ", fields.stream().map(Profile::name).toList()) + " — its rules join this run");
        if (fields.stream().anyMatch(p -> p.name().equals("youtube")) && !Video.installed()) log.accept("youtube field without the video helper: YouTube is read through web search; researchzosho video install adds the video tools");
        List<String> open = planCore(ask, known, budget);
        laneOf.clear(); lanes.clear(); laneRetried.clear(); laneRan.clear();
        recordCitations.clear(); nothingFound.clear(); kindsSearched.clear(); sourcesSearched.clear();
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
        String planRules = fieldRules(Profile::planRules);
        // a field with its own way of splitting the work is split that way; "who studies this" is for a question with no such field
        if (PERSPECTIVES && planRules.isEmpty() && budget.take()) {
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
                        + "say so in the sub-question. Skip anything the library block above already settles. " + planRules + recordsForThePlan() + codeForThePlan(ask)
                        + "Answer with a JSON array of strings and nothing else.\n\nQUESTION:\n" + ask.question());
                String raw;
                try (var step = Declines.step("break a research question into sub-questions, as a JSON list: " + Acquisitions.compress(ask.question(), 300))) { raw = decide(judge, msgs, 1200); }
                int a = raw.indexOf('['), b = raw.lastIndexOf(']');
                if (a >= 0 && b > a) {
                    for (JsonNode q : J.readTree(raw.substring(a, b + 1)))
                        if (q.isTextual() && !q.asText().isBlank() && open.size() < MAX_SUB) open.add(q.asText().strip());
                }
            } catch (Declined d) {
                throw d;   // the model declined to plan: the question is not sent on as its own sub-question
            } catch (Stopping.Requested stop) {
                throw stopped(stop);
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
            // each worker's thread works under the run's stop, as the run's own thread does
            for (String sub : open) futures.add(pool.submit(Stopping.carried(() -> { workersActive.incrementAndGet(); try { return investigate(ask, sub, budget); } finally { workersActive.decrementAndGet(); } })));
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
                    if (e.getCause() instanceof Stopping.Requested s) throw stopped(s);
                    if (e.getCause() instanceof Declined d) {
                        // this sub-question only: the others go on, and it is never sent round again in other words
                        declinedSubs.add(open.get(i));
                        declined(d.at("to research the sub-question \"" + Acquisitions.compress(open.get(i), 160) + "\""));
                        workersDone.incrementAndGet(); progress("workers");
                        continue;
                    }
                    workersDone.incrementAndGet(); progress("workers");
                    out.add("SUB-QUESTION: " + open.get(i) + "\nSUMMARY: unavailable (worker "
                            + (e instanceof TimeoutException ? "timed out" : "failed: " + e.getMessage()) + ")");
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
        if (store != null && ask.shelves()) { byName.put("shelf_search", new ShelfSearchTool(store, ask.collections())); if (Holdings.size(store) > 0) byName.put("holdings", new HoldingsTool(store)); }
        // record_search joins a run whose field works from records (or every run, RESEARCHZOSHO_RECORDS=always): its description of a
        // dozen collections is a cost and a distraction on a question about a compiler or a protein
        boolean records = "always".equalsIgnoreCase(RECORDS_MODE) || fields.stream().anyMatch(Profile::wantsRecords);
        if (ask.web()) for (Tool t : tools.web(sub, runPolicy)) {
            if (t instanceof RecordSearchTool r) {
                if (!records) continue;
                r = "always".equalsIgnoreCase(RECORDS_MODE) && fields.stream().noneMatch(Profile::wantsRecords) ? r : r.forFields(fieldNames());
                r = r.forYears(RecordSources.lived(sub, fields));   // the years are read by the run's own fields, and only when it is given the tool
                if (!r.any()) continue;
                t = r.listen(this::recordSearched);
            }
            byName.put(t.name(), t);
        }
        if (databases && Databases.any()) { byName.put("db_schema", new DbSchemaTool()); byName.put("db_query", new DbQueryTool(store)); }
        if (store != null) byName.put("read_pages", new PagesTool(store));
        List<String> repos = store == null ? List.of() : codeSettles(ask, CodeTool.reposIn(store, ask.question()));
        if (!repos.isEmpty()) byName.put("read_code", new CodeTool(store, repos));
        byName.put(notebook.name(), notebook);
        byName.put(done.name(), done);
        BooleanSupplier exhausted = ask.web() ? tools.exhausted() : () -> false;

        Lanes.Lane lane = laneOf.get(sub);
        if (lane != null) laneRan.add(lane.code());
        ArrayNode history = J.createArrayNode();
        history.addObject().put("role", "system").put("content", (lane == null ? "" : lane.register()) + workerRegister(ask, byName.containsKey("record_search")) + codeRule(repos) + fieldRules(Profile::register));
        List<String> seeds = seedsOf.getOrDefault(sub, List.of());
        String seedBlock = seeds.isEmpty() ? "" : "\n\nPAGES NAMED IN THE FIRST ROUND AND NOT YET READ — start with web_fetch on these, and search only for what they do not settle:\n- " + String.join("\n- ", seeds);
        history.addObject().put("role", "user").put("content",
                "RESEARCH QUESTION (the whole ask, for context):\n" + ask.question()
                + "\n\nYOUR SUB-QUESTION — research THIS, and only this:\n" + sub + seedBlock
                + "\n\n" + startWith(ask, sub, seeds.isEmpty(), byName.containsKey("record_search")) + " Note every fact the moment a fetched source shows it.");
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
            progressTick();
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
                try (var step = Declines.step("research this sub-question with the tools, note what the sources show, and finish with a summary: " + Acquisitions.compress(sub, 300), true)) {
                    assistant = chat(history, deadline ? (closingNote ? noteAndDone : onlyDone) : all, outBudget(history, nctx));
                }
            } catch (Stopped e) {
                throw e;   // a person stopped the run: out of the worker, out of the round, out of the run
            } catch (Declined e) {
                throw e;   // the model declined this sub-question: said, not nudged and not asked again
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
                boolean ran = false;   // the tool itself was called: a bounced call is no search
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
                    ran = true;
                    String served = "web_fetch".equals(name) ? servedFromRun(args) : null;
                    if (served != null) { observation = served; servedFromRun.incrementAndGet(); }
                    else try {
                        observation = t.execute(args);
                    } catch (Stopping.Requested e) {
                        throw stopped(e);   // a person stopped the run while the tool waited: the run ends here
                    } catch (Exception e) {
                        observation = "ERROR: " + name + " failed — " + e.getMessage();
                    }
                    if (stopWhen.getAsBoolean()) throw stopped(new Stopping.Requested());   // a tool that caught the stop itself
                }
                if (observation == null) observation = "";
                if (ran && (name.equals("web_search") || name.equals("scholar_search") || name.equals("record_search"))) logSearch(name, args, observation);
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
                } else if (("read_pages".equals(name) || "read_code".equals(name) && "read".equals(args.path("op").asText(""))) && !observation.startsWith("ERROR")) {
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

    /** The worker's first move. A sub-question that names record collections starts there, the name alone first: told only "web_search", five workers of eight never opened the records. */
    String startWith(Ask ask, String sub, boolean noSeeds, boolean records) {
        String usual = (store != null && ask.shelves() ? "shelf_search" + (ask.web() ? ", then web_search" : "") : "web_search");
        List<RecordSource> named = records ? RecordSources.named(sub, RecordSources.all()) : List.of();
        if (named.isEmpty()) return (noSeeds ? "Start with " : "Then, if needed, ") + usual + ".";
        return (noSeeds ? "Start with " : "Then ") + "record_search in " + String.join(", ", named.stream().map(RecordSource::id).toList())
                + ": the person's name alone first, as those records would write it. Then " + usual + " for what the records do not settle.";
    }

    /** For a run that works from records: the collections, so the plan can give each sub-question the ones to search. "" for any other run. */
    private String recordsForThePlan() {
        if (!RECORDS || fields.stream().noneMatch(Profile::wantsRecords)) return "";
        String brief = RecordSources.brief(fieldNames());
        if (brief.isBlank()) return "";
        return "THE COLLECTIONS a worker can search by name (record_search), each by its id:\n" + brief
                + "End each sub-question with the collections to search for it, by id, like: (search: loc-newspapers, internet-archive). A collection goes "
                + "with a sub-question only when its country, its kind of record and its years fit the people in it: French newspapers have nothing on a "
                + "family in Japan and New York, a Rust package index nothing on a Python question. Every collection that does fit is given to at least one sub-question.\n\n";
    }

    /** For a run about a repository the library holds: what the worker is told about read_code; "" for any other run. */
    static String codeRule(List<String> repos) {
        if (repos.isEmpty()) return "";
        return "\n\nTHE CODE: read_code reads the files of " + String.join(", ", repos) + " as they are on disk. A README, a design document or an issue says what "
                + "is planned as often as what is built, so settle what the software does from the code: grep for the feature, read the file, and note the claim with "
                + "the file and line numbers read_code showed as its source, written raw/repos/<repository>/<path>:<line>. A claim you found only in a document is "
                + "noted as what that document says.\n\n";
    }

    /**
     * The repositories this run reads the code of (read_code) and settles its answers from: the one a survey's run is about ("About the
     * repository NAME ("), the ones the ask's collections name, or every repository the question names when a field of the run reads code.
     * A question that only uses a repository's name as a word ("how do plants react to light" beside a clone of react) is not offered it.
     */
    List<String> codeSettles(Ask ask, List<String> repos) {
        if (repos.isEmpty() || fields.stream().anyMatch(Profile::readsCode)) return repos;
        String q = ask.question().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String n : repos) if (q.startsWith("about the repository " + n.toLowerCase(Locale.ROOT) + " (") || ask.collections().stream().anyMatch(c -> c.equalsIgnoreCase(n))) out.add(n);
        return out;
    }

    /** The planner's side of the same rule. */
    private String codeForThePlan(Ask ask) {
        if (store == null || codeSettles(ask, CodeTool.reposIn(store, ask.question())).isEmpty()) return "";
        return "The repository's own files can be read (read_code). Write each sub-question about what the software does so that its answer is settled from the code "
                + "and cites the file and line.\n\n";
    }

    private List<String> fieldNames() { return fields.stream().filter(Profile::wantsRecords).map(Profile::name).toList(); }

    /** One kind of rule from every profile this question belongs to, as a block the prompt can take as it is; "" when there is none. */
    private String fieldRules(Function<Profile, String> kind) {
        StringBuilder b = new StringBuilder();
        for (Profile p : fields) { String r = kind.apply(p); if (r != null && !r.isBlank()) b.append("\n\n").append(p.name().toUpperCase(Locale.ROOT)).append(": ").append(r.strip()); }
        return b.isEmpty() ? "" : b.append("\n\n").toString();
    }

    private String workerRegister(Ask ask, boolean records) {
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
        sb.append("You are a researcher working for a library. Read-only: you have ").append(shelves ? "shelf_search, " : "").append(web ? "web_search, scholar_search, " + (records ? "record_search, " : "") + "web_fetch, " : "").append("read_pages, note and done.\n\n").append(shelvesText);
        if (web) sb.append("scholar_search finds papers, books and chapters by DOI in Crossref and OpenAlex: the primary literature a web engine ranks low. Use it as well as web_search whenever the question touches a literature (medicine, science, history, law, the humanities), then web_fetch the DOI or landing page to read.\n\n");
        if (web && records) sb.append("record_search searches collections of records one at a time — newspapers, patents, scanned directories and local histories, archive catalogues — by the ids its description lists. What a person, a family or a firm did is in these, and a web engine cannot see inside them. A search there that finds nothing is a result: note it.\n\n");
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

    /** What the person asked for beyond the question — a table, a language, a length, an order — carried to the writer; "" when nothing. */
    private volatile String askedFormat = "";
    static final Pattern FORMAT_HINT = Pattern.compile("(?i)\\b(table|tabular|bullet|list|timeline|by year|per year|chronolog|in (japanese|english|german|french|spanish|chinese|korean|italian|portuguese)|under \\d+ words|at most \\d+ words|one page|two pages|short|brief|summary|compare|comparison|side by side|ranked|rank)\\b");
    String formatAsked(Ask ask, Budget budget) {
        if (!FORMAT_HINT.matcher(ask.question()).find() || !budget.take()) return "";
        try {
            ArrayNode msgs = J.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "Split this research request into the TASK (what to find out) and the FORMAT the person asked the answer to take "
                    + "(a table, a list, a language, a length, an order, a comparison). Copy the format words as written; say none when the request names none.\n\nREQUEST:\n"
                    + ask.question() + "\n\nAnswer with JSON only: {\"task\": \"…\", \"format\": \"…|none\"}");
            String raw;
            try (var step = Declines.step("say what format a research request asks its answer to take, as JSON: " + Acquisitions.compress(ask.question(), 300))) { raw = decide(judge, msgs, 300); }
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            if (a < 0 || b <= a) return "";
            String f = J.readTree(raw.substring(a, b + 1)).path("format").asText("").strip();
            return f.equalsIgnoreCase("none") || f.length() > 300 ? "" : f;
        } catch (Declined d) { throw d; }   // part of the plan: a decline here ends the run as declined
        catch (Exception e) { return ""; }
    }

    // ---- 3. critic ----

    List<String> critic(Ask ask, List<String> evidence, Budget budget) {
        List<String> missing = new ArrayList<>();
        // the lanes first, mechanically: a lane whose language no note came from goes round again, once
        Map<String, Integer> read = Lanes.languagesRead(String.join("\n", evidence));
        for (Lanes.Lane lane : lanes) {
            if (declinedSubs.stream().anyMatch(s -> laneOf.get(s) == lane)) continue;   // the model declined the lane: it is not sent round again
            if (read.getOrDefault(lane.code(), 0) > 0 || !laneRetried.add(lane.code())) continue;
            String again = lane.subQuestion(ask.question()) + " The first round read no " + lane.name() + "-language source at all; use the seed queries as they are, and read the " + lane.name() + " pages.";
            missing.add(again); laneOf.put(again, lane);
            log.accept("critic: no " + lane.name() + "-language source read (" + Lanes.describe(read) + ") → the lane goes round again");
        }
        // the numbers first, computed from the notes — not the model's impression of them (SearchClaw's stop hooks, deer-flow's
        // acceptance checks): a sub-question that noted no source at all goes round again without asking
        CoverageNumbers cn = coverageNumbers(evidence);
        for (String empty : cn.emptySubQuestions()) {
            if (laneRetried.add("empty:" + empty) && missing.size() < 4) {
                missing.add(empty + " The first round noted no source for this; search differently (other words, another language, a primary source) and read the pages.");
                log.accept("critic: no source noted for \"" + Acquisitions.compress(empty, 60) + "\" → goes round again");
            }
        }
        if (!budget.take()) return missing;
        try {
            ArrayNode msgs = J.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "You are reviewing research COVERAGE for a library, not writing the answer.\n\nTHE ASK:\n"
                    + ask.question() + "\n\nCOVERAGE NUMBERS (computed from the notes, not an impression):\n" + cn.lines() + recordKindsLine()
                    + declinedForThePrompt("SUB-QUESTIONS THE MODEL DECLINED (they stay as they are; name gaps only among the others)")
                    + "\nEVIDENCE SO FAR:\n" + fitted("critic", evidence)
                    + "\n\nIs this enough to answer the ask COMPLETELY, with sources, within its stated scope, from every "
                    + "perspective the sub-questions name? The default is sufficient: name a gap only when it is specific, critical to "
                    + "the ask, and easy to state as one further search; never a gap that leads away from the ask, never a sub-question "
                    + "already on the list above. " + fieldRules(Profile::criticRules) + "Answer with JSON only: {\"sufficient\": true} or {\"sufficient\": false, "
                    + "\"missing\": [{\"question\": \"<self-contained sub-question>\", \"type\": \"critical|contextual|detail|extension\", "
                    + "\"central\": true|false}, ...]} (at most 4; only gaps a further search could fill).");
            String raw;
            try (var step = Declines.step("check whether the research so far covers the question, and name the gaps, as JSON: " + Acquisitions.compress(ask.question(), 300))) { raw = decide(judge, msgs, 900); }
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            if (a < 0 || b <= a) return missing;
            JsonNode v = J.readTree(raw.substring(a, b + 1));
            if (!v.path("sufficient").asBoolean(true)) {
                // ranked: critical and central first (enterprise-deep-research's gap matrix); a four-minute second round spends on the top one
                List<JsonNode> gaps = new ArrayList<>();
                for (JsonNode q : v.path("missing")) if (q.isObject() ? !q.path("question").asText("").isBlank() : q.isTextual() && !q.asText().isBlank()) gaps.add(q);
                gaps.sort(Comparator.comparingInt(Researcher::gapPriority));
                for (JsonNode q : gaps) {
                    String text = (q.isObject() ? q.path("question").asText() : q.asText()).strip();
                    if (declinedSubs.stream().anyMatch(d -> d.equalsIgnoreCase(text) || Frontier.jaccard(Frontier.terms(d), Frontier.terms(text)) >= SAME_WORDS)) {
                        log.accept("critic: \"" + Acquisitions.compress(text, 60) + "\" asks what the model declined — not sent round");
                        continue;   // a declined sub-question is not proposed back, in its own words or mostly the same ones
                    }
                    String same = sameAsDeclined(text);
                    if (same != null) {
                        log.accept("critic: \"" + Acquisitions.compress(text, 60) + "\" asks for the same thing as the declined \"" + Acquisitions.compress(same, 60) + "\" — not sent round");
                        continue;   // in other words altogether: the typed judge was sure it asks the same thing
                    }
                    if (missing.size() < 5 && missing.stream().noneMatch(m -> m.equalsIgnoreCase(text))) missing.add(text);
                }
            }
        } catch (Declined d) {
            declined(d.at("to check whether the research covers the question"));
            log.accept("critic: declined by the model — stopping rounds");
        } catch (Stopping.Requested stop) {
            throw stopped(stop);
        } catch (Exception e) {
            log.accept("critic: unparseable — stopping rounds");
        }
        return missing;
    }

    /**
     * The declined sub-question that {@code gap} asks for the same thing as, in other words, by the typed judge of the judge seat (or the
     * workers'); null when none, or when no typed judge can run. Asked only when the run has declined sub-questions.
     */
    private String sameAsDeclined(String gap) {
        if (declinedSubs.isEmpty()) return null;
        DeclineJudge dj = judge.declineJudge() != null ? judge.declineJudge() : drive.declineJudge();
        if (dj == null) return null;
        for (String d : declinedSubs) if (dj.sameAsk(d, gap)) return d;
        return null;
    }

    /** The declined sub-questions as a block for a prompt, under {@code heading}; "" when none was declined. */
    private String declinedForThePrompt(String heading) {
        if (declinedSubs.isEmpty()) return "";
        StringBuilder b = new StringBuilder(heading).append(":\n");
        for (String s : declinedSubs) b.append("- ").append(Acquisitions.compress(s, 200)).append('\n');
        return b.append('\n').toString();
    }

    /** 1 = critical and central … 4 = an extension off to the side; a bare string is 2. */
    static int gapPriority(JsonNode q) {
        if (!q.isObject()) return 2;
        String type = q.path("type").asText("contextual").toLowerCase(Locale.ROOT);
        boolean central = q.path("central").asBoolean(true);
        int base = switch (type) { case "critical" -> 1; case "contextual" -> 2; case "detail" -> 3; default -> 4; };
        return central ? base : Math.min(4, base + 1);
    }

    /** What the notes hold, counted: per sub-question its notes and distinct sources; overall distinct sources and hosts; the sub-questions with none. */
    record CoverageNumbers(int subQuestions, int notes, int sources, int hosts, List<String> emptySubQuestions, String lines) { }

    static CoverageNumbers coverageNumbers(List<String> evidence) {
        StringBuilder b = new StringBuilder();
        Set<String> allSources = new LinkedHashSet<>(), hosts = new LinkedHashSet<>();
        List<String> empty = new ArrayList<>();
        int notes = 0, i = 0;
        for (String piece : evidence) {
            i++;
            String head = piece.startsWith("SUB-QUESTION: ") ? piece.substring(14, piece.indexOf('\n') < 0 ? piece.length() : piece.indexOf('\n')).strip() : "(unnamed)";
            Set<String> srcs = new LinkedHashSet<>();
            Matcher m = Pattern.compile("— source: (\\S+)").matcher(piece);
            int n = 0;
            while (m.find()) { n++; String src = m.group(1).replaceAll("[)\\].,;]+$", ""); srcs.add(src); String h = CiteCheck.hostOf(src); if (h != null) hosts.add(h); }
            notes += n; allSources.addAll(srcs);
            if (n == 0) empty.add(head);
            b.append("- #").append(i).append(" ").append(Acquisitions.compress(head, 100)).append(": ").append(n).append(" note(s), ").append(srcs.size()).append(" source(s)\n");
        }
        b.append("- overall: ").append(notes).append(" notes, ").append(allSources.size()).append(" distinct sources on ").append(hosts.size()).append(" host(s)")
         .append(empty.isEmpty() ? "" : "; " + empty.size() + " sub-question(s) noted NO source").append('\n');
        return new CoverageNumbers(evidence.size(), notes, allSources.size(), hosts.size(), empty, b.toString());
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
        if (ask.web()) for (Tool t : tools.web(ask.question(), runPolicy)) if ("web_fetch".equals(t.name())) byName.put(t.name(), t);
        if (store != null) { byName.put("read_pages", new PagesTool(store)); if (ask.shelves()) { byName.put("shelf_search", new ShelfSearchTool(store, ask.collections())); if (Holdings.size(store) > 0) byName.put("holdings", new HoldingsTool(store)); } }
        byName.put(sections.name(), sections);
        byName.put(done.name(), done);
        String notes = coverage(pieces) + "\n" + fitted("synthesis", pieces);
        String sourceList = sourcesForWriter(String.join("\n\n", pieces));

        ArrayNode history = J.createArrayNode();
        history.addObject().put("role", "system").put("content",
                (askedFormat.isEmpty() ? "" : "THE FORMAT THE PERSON ASKED FOR — the answer takes this shape: " + askedFormat + "\n\n")
                + "Date an arXiv paper by its id: YYMM.NNNNN was posted in 20YY, month MM (2606.09498 is June 2026). "
                + "Write to the question and nothing beside it: no section on hosting, cost, or advice the question did not ask for. "
                + "You are writing an investigation for a library's shelves, from evidence gathered by parallel "
                + "sub-investigations. Treat the evidence as your own notes; web_fetch only to verify something "
                + "doubtful. Write with write_section, ONE section per call, in this order: 'Answer' (the direct "
                + "answer to the ask, first), then one section per sub-question or theme with the supporting "
                + "detail and every claim followed by its source NUMBER in square brackets from the SOURCES list, like [3], right after "
                + "the clause it supports (several as [3][7]; a source not on the list: its URL in parentheses), then 'Conflicts and uncertainty' "
                + "when sources disagree, then 'Sources' — every URL or citation you relied on, one per line, with "
                + "edition or translation where it was noted. Keep each section under 1500 characters; use more "
                + "sections rather than longer ones. Then call done." + fieldRules(Profile::writerRules));
        history.addObject().put("role", "user").put("content",
                "THE ASK:\n" + ask.question() + "\n\n" + known + (sourceList.isEmpty() ? "" : "SOURCES (cite by number, [n]):\n" + sourceList + "\n")
                + declinedForThePrompt("SUB-QUESTIONS NOT RESEARCHED (the model declined them, and the library says so in its own section of the report; write from the rest)")
                + "EVIDENCE:\n" + notes
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
                try (var step = Declines.writeUp("write the sections of a research report from the research notes, answering: " + Acquisitions.compress(ask.question(), 300))) {
                    assistant = chat(history, deadline ? onlyDone : all, outBudget(history, nctx));
                }
            } catch (Stopped e) {
                throw e;
            } catch (Declined e) {
                throw e;   // the model declined to write: the run ends as declined, and nobody writes in its place
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
                    try { observation = t.execute(args); } catch (Stopping.Requested e) { throw stopped(e); } catch (Exception e) { observation = "ERROR: " + e.getMessage(); }
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
                String prose;
                try (var step = Declines.step("write the answer to a research question from the research notes: " + Acquisitions.compress(ask.question(), 300), true)) { prose = judge.prose(msgs, 2500); }
                if (!prose.isBlank()) sections.sections.add(prose.strip());
            } catch (Declined d) {
                throw d;   // the judge seat's model declined to write it: said, not replaced
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
            // an arXiv id carries its posting month; stamp it, or the writer dates every 26xx paper 2025 when the page had no date (measured, 2026-09-12)
            String posted = Citations.arxivPosted(source);
            if (posted.isEmpty()) posted = Citations.arxivPosted(claim);
            notes.add("- " + claim + " — source: " + source + (posted.isEmpty() ? "" : " (arXiv, posted " + posted + ")") + (quote.isEmpty() ? "" : " — quote: \"" + Acquisitions.compress(quote, 300) + "\""));
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
            Path p = RawCapture.find(store, url);
            if (p == null) return "ERROR: nothing captured for " + url + " — web_fetch it first.";
            String text = RawCapture.read(p)[2];
            if (RawCapture.looksBinary(text)) return "ERROR: the saved copy of " + url + " is not readable text (it was saved from a binary file). It cannot be read page by page. If it is a list the person owns, such as a book database, use the holdings tool instead.";
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
                    try { Path rp = LibraryStore.under(store.rawDir(), h.id()); if (rp != null && Files.exists(rp)) locator = RawCapture.read(rp)[0]; } catch (Exception ignored) { }
                }
                sb.append(n).append(". ").append(h.title()).append("  [").append(h.kind()).append(h.state().isEmpty() ? "" : ", " + h.state()).append("]\n   ").append(locator).append('\n');
                if (!h.snippet().isBlank()) sb.append("   ").append(Acquisitions.compress(h.snippet(), 240)).append('\n');
            }
            if (n == 0) return "the shelves hold nothing for: " + q;
            sb.append(Fence.close("SHELF RESULTS")).append('\n').append(Fence.rule("SHELF RESULTS")).append('\n');
            return sb.toString();
        }
    }

    /** The databases the owner gave access to, and the schema of one: tables, columns, row counts, sample rows. */
    static final class DbSchemaTool implements Tool {
        @Override public String name() { return "db_schema"; }
        @Override public String description() {
            return "The databases the person gave this library read access to. With no name: the list of databases. With a name: that database's tables, columns, row counts and a few sample rows. Read the schema before you write a query.";
        }
        @Override public ObjectNode parametersSchema(ObjectMapper j) {
            ObjectNode p = j.createObjectNode(); p.put("type", "object");
            p.putObject("properties").putObject("database").put("type", "string");
            p.putArray("required");
            return p;
        }
        @Override public String execute(JsonNode args) throws Exception {
            String name = args.path("database").asText("").strip();
            if (name.isEmpty()) { StringBuilder sb = new StringBuilder("databases:\n"); for (Databases.Db d : Databases.list()) sb.append("- ").append(d.name()).append(" (").append(DbDrivers.kind(d.kind()).label()).append(")\n"); return sb.toString(); }
            Databases.Db db = Databases.get(name);
            if (db == null) return "ERROR: no database is named " + name + ". Call db_schema with no name to list them.";
            try { return Fence.wrap("DATABASE SCHEMA", Databases.schema(db)) + "\n" + Fence.rule("DATABASE SCHEMA"); } catch (IOException e) { return "ERROR: " + e.getMessage(); }
        }
    }

    /** One reading query against one of the owner's databases. The rows come back as a table and are saved as a page to cite. */
    static final class DbQueryTool implements Tool {
        private final LibraryStore store;
        DbQueryTool(LibraryStore store) { this.store = store; }
        @Override public String name() { return "db_query"; }
        @Override public String description() {
            return "Run ONE reading query against a database the person gave access to. SQL databases: give `sql` (a single SELECT or WITH statement). MongoDB: give `collection` and a `filter` or a `pipeline` as JSON. "
                    + "Rows are capped (default " + Databases.DEFAULT_ROWS + ", at most " + Databases.MAX_ROWS + "): count and group in the query instead of reading every row. The result is saved as a page; cite its locator.";
        }
        @Override public ObjectNode parametersSchema(ObjectMapper j) {
            ObjectNode p = j.createObjectNode(); p.put("type", "object");
            ObjectNode props = p.putObject("properties");
            for (String k : List.of("database", "sql", "collection", "filter", "pipeline")) props.putObject(k).put("type", "string");
            props.putObject("limit").put("type", "integer");
            p.putArray("required").add("database");
            return p;
        }
        @Override public String execute(JsonNode args) throws Exception {
            Databases.Db db = Databases.get(args.path("database").asText(""));
            if (db == null) return "ERROR: no database is named " + args.path("database").asText("") + ". Call db_schema with no name to list them.";
            try {
                Databases.Result r = db.kind().equals("mongo")
                        ? Databases.queryMongo(store, db, args.path("collection").asText(""), args.path("filter").asText(""), args.path("pipeline").asText(""), args.path("limit").asInt(0))
                        : Databases.query(store, db, args.path("sql").asText(""), args.path("limit").asInt(0));
                return (r.saved().isEmpty() ? "" : "saved as " + r.saved() + " (cite this locator)\n") + Fence.wrap("QUERY RESULT", r.text()) + "\n" + Fence.rule("QUERY RESULT");
            } catch (IOException e) { return "ERROR: " + e.getMessage(); }
        }
    }

    /** What the person's own lists hold: an exact lookup, for telling owned from not owned. Offered only when a list is shelved. */
    static final class HoldingsTool implements Tool {
        private final LibraryStore store;
        HoldingsTool(LibraryStore store) { this.store = store; }
        @Override public String name() { return "holdings"; }
        @Override public String description() {
            return "What the person's own lists hold (a library of books, an inventory, a reading list): an exact lookup — every word of the query must appear in an entry. "
                    + "Ask it for each title you consider, with a word or two of the title and the author's surname. What it returns is owned; anything it does not return is not. A search snippet is not evidence of ownership.";
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
            List<Holdings.Match> m = Holdings.find(store, q, 10, null);
            if (m.isEmpty()) return "the person's lists hold nothing matching: " + q;
            StringBuilder sb = new StringBuilder("held, matching \"" + q + "\":\n" + Fence.open("HOLDINGS") + "\n");
            int n = 0;
            for (Holdings.Match x : m) sb.append(++n).append(". ").append(x.item()).append(x.note().isEmpty() ? "" : " — " + Acquisitions.compress(x.note(), 160)).append("   [").append(x.list()).append("]\n");
            sb.append(Fence.close("HOLDINGS")).append('\n').append(Fence.rule("HOLDINGS")).append('\n');
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
     * not retried: it is the drive's answer, not the wire's. Nor is a call that ran out of its time limit
     * ({@link #askAgain}): asked again with the same limit, it runs out again.
     */
    private ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens) {
        try { return chatTurn(history, tools, maxTokens); }
        catch (Stopping.Requested stop) { throw stopped(stop); }   // stopped while the model was asked: the call was given up
    }

    private ObjectNode chatTurn(ArrayNode history, ArrayNode tools, int maxTokens) {
        if (stopWhen.getAsBoolean()) { log.accept("stopped: a person stopped this run; ending at this turn"); throw new Stopped(); }
        if (runPolicy.cannotCheck()) { String why = runPolicy.cannotCheckStatement(); log.accept("stopped: " + why); throw new CannotCheck(why); }
        ResearchSettings.awaitUnpaused(log);
        try { throttle.enter(workersNow()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted"); }
        long t0 = System.currentTimeMillis();
        WAITED.get()[0] = 0;
        int in = 0;
        for (JsonNode m : history) in += estTokens(m.path("content").asText("")) + estTokens(m.path("tool_calls").toString()) + 8;
        ObjectNode reply = null;
        try {
            reply = chatOnce(history, tools, maxTokens);
            return reply;
        } finally {
            t0 += WAITED.get()[0];   // a wait for an absent server is not the turn's own time
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
    private final AtomicInteger workersActive = new AtomicInteger();

    private ObjectNode chatOnce(ArrayNode history, ArrayNode tools, int maxTokens) {
        // the tool descriptions travel with every request and count against the window like the conversation does: on a 131k window
        // that is nothing, on a 16k one it put a write-up 19 tokens over and the server refused it, every time
        int nctx = Math.max(drive.contextWindow(), 8000);
        maxTokens = fitReply(history, tools, maxTokens, nctx);
        try {
            int raw = rawEstimate(history, tools);
            DriveClient.forgetUsage();   // what the server says of THIS request, or nothing: never an earlier call's on this thread
            ObjectNode reply = drive.chat(history, tools, maxTokens, "required");
            long[] used = DriveClient.lastUsage();
            if (used != null) calibrate(raw, used[0]);
            return reply;
        } catch (Declined d) {
            throw d;   // the model's answer: never retried, whatever words it quoted
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage());
            if (tooLong(m)) {
                Matcher real = Pattern.compile("(?:n_prompt_tokens\\D{1,4}|request \\()(\\d{3,})").matcher(m);
                if (real.find()) calibrate(rawEstimate(history, tools), Long.parseLong(real.group(1)));
                // the estimate was short (a script that packs fewer characters into a token, a server that counts its template): clear
                // older observations down to two fifths of the window, leave the reply what is left, and try once more
                int cleared = trimHistory(history, nctx, 0.40);
                int cuts = cutLongest(history, tools, nctx);
                int reply = Math.max(256, fitReply(history, tools, maxTokens, nctx) * 3 / 4);
                log.accept("drive: the request was over the model's window of " + nctx + " tokens; " + cleared + " older observation(s) cleared, " + cuts + " long message(s) cut in the middle, reply " + reply + " tokens — trying once more");
                return drive.chat(history, tools, reply, "required");
            }
            final int asked = maxTokens;
            if (serverAway(e)) return whenBack(e, () -> drive.chat(history, tools, asked, "required"));
            boolean transport = m.startsWith("chat() failed") || e.getCause() instanceof IOException;
            if (transport && !askAgain(e))
                log.accept("drive: " + (e.getCause() == null ? m : e.getCause().getMessage()) + "; a call that ran out of its time limit is not asked again with the same limit");
            throw e;
        }
    }

    /**
     * Whether a failed call is the model server being away — not reachable, its connection cut, restarting, loading its model (502, 503,
     * 504) — rather than an answer about the request, or a request the server held until its time limit ran out, which would only run
     * out again. A server another program owns goes away for a minute or two when that program restarts it (Wyrdsekai moves its brain
     * between the card and RAM that way), and for longer at night.
     */
    static boolean serverAway(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (m.startsWith("drive HTTP 502") || m.startsWith("drive HTTP 503") || m.startsWith("drive HTTP 504")) return true;
        for (Throwable c = e instanceof IOException ? e : e.getCause(); c != null; c = c.getCause()) {
            if (c instanceof DriveClient.ErrorStatus s) return s.status == 502 || s.status == 503 || s.status == 504;
            if (c instanceof HttpConnectTimeoutException) return true;
            if (c instanceof HttpTimeoutException || c instanceof JsonProcessingException) return false;
            if (c instanceof IOException io) return !String.valueOf(io.getMessage()).contains(" answered HTTP ");   // a 5xx about this request is its answer
        }
        return false;
    }

    /** The waits between asking an absent model server again: 5 seconds, 10, then every 15. */
    static volatile long[] awayWaitsMs = {5_000, 10_000, 15_000};
    /** How long this thread waited for an absent server during its current turn, so the wait is not taken for the turn's own time. */
    private static final ThreadLocal<long[]> WAITED = ThreadLocal.withInitial(() -> new long[1]);

    /**
     * The call asked again once the model server answers again: for as long as the run's time allows, and with no time limit until it
     * answers or a person stops the run, as a run that has not started waits for its model. Said on the run's log when the wait starts and
     * when it ends.
     */
    private <T> T whenBack(RuntimeException first, Supplier<T> call) {
        long start = System.currentTimeMillis();
        String why = first.getCause() == null ? first.getMessage() : first.getCause().getMessage();
        log.accept("drive: the model server is not answering (" + why + "); the run waits for it and goes on when it answers");
        RuntimeException last = first;
        try {
            for (int i = 0; ; i++) {
                long wait = awayWaitsMs[Math.min(i, awayWaitsMs.length - 1)];
                long deadline = currentBudget.deadlineMs();
                if (deadline > 0 && System.currentTimeMillis() + wait >= deadline) {
                    log.accept("drive: the run's time ran out while it waited " + (System.currentTimeMillis() - start) / 1000 + " s for the model server");
                    throw last;
                }
                try { Stopping.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw last; }
                if (stopWhen.getAsBoolean()) { log.accept("stopped: a person stopped this run while it waited for the model server"); throw new Stopped(); }
                try {
                    T back = call.get();
                    log.accept("drive: the model server answers again after " + (System.currentTimeMillis() - start) / 1000 + " s; the run goes on");
                    return back;
                } catch (Declined | Stopping.Requested d) {
                    throw d;
                } catch (RuntimeException e) {
                    if (!serverAway(e)) throw e;
                    last = e;
                }
            }
        } finally {
            WAITED.get()[0] += System.currentTimeMillis() - start;
        }
    }

    /** A decision asked of {@code d}; when the server was away for it, asked again once it answers. "" when it never did in the run's time. */
    private String decide(Drive d, ArrayNode msgs, int maxTokens) {
        DriveClient.forgetUnanswered();
        String raw = d.classify(msgs, maxTokens);
        IOException none = DriveClient.lastClassifyUnanswered();
        if (!raw.isEmpty() || none == null || !serverAway(none)) return raw;
        try {
            return whenBack(new IllegalStateException(none.getMessage(), none), () -> {
                DriveClient.forgetUnanswered();
                String r = d.classify(msgs, maxTokens);
                IOException n = DriveClient.lastClassifyUnanswered();
                if (r.isEmpty() && n != null && serverAway(n)) throw new IllegalStateException(n.getMessage(), n);
                return r;
            });
        } catch (Stopping.Requested stop) {
            throw stop;
        } catch (RuntimeException gaveUp) {
            return "";
        }
    }

    /**
     * Whether a call that failed on the way is asked once more: yes for a request that never got through (a connection refused, reset or
     * not made in time), no for one that ran out of its time limit while the server held it, which would only run out again.
     */
    static boolean askAgain(RuntimeException e) {
        for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
            if (c instanceof HttpConnectTimeoutException) return true;
            if (c instanceof HttpTimeoutException) return false;
        }
        return true;
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

    /** Whether a drive's refusal says the request was longer than its window (llama.cpp's exceed_context_size_error, and the hosted APIs' wording). */
    static boolean tooLong(String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return m.contains("exceed_context") || m.contains("exceeds the available context") || m.contains("context_length_exceeded") || m.contains("maximum context length") || m.contains("prompt is too long");
    }

    /** The reply budget that really fits: the window less the conversation, the tool descriptions and a margin. Never below 256. */
    static int fitReply(ArrayNode history, ArrayNode tools, int wanted, int nctx) {
        int in = estTokens(tools == null ? "" : tools.toString());
        for (JsonNode m : history) in += estTokens(m.path("content").asText("")) + estTokens(m.path("tool_calls").toString()) + 8;
        return Math.max(256, Math.min(wanted, nctx - in - 256));
    }

    /** Keep the history inside ~55% of the window by blanking the OLDEST tool observations first. */
    static int trimHistory(ArrayNode history, int nctx) { return trimHistory(history, nctx, 0.55); }

    static int trimHistory(ArrayNode history, int nctx, double share) {
        int ctx = Math.max(nctx, 8000);
        int limit = (int) (ctx * share);
        int trimmed = 0;
        for (int i = 0; i < history.size(); i++) {
            int total = 0;
            for (JsonNode m : history) total += estTokens(m.path("content").asText("")) + estTokens(m.path("tool_calls").toString()) + 8;
            if (total <= limit) break;
            JsonNode m = history.get(i);
            if ("tool".equals(m.path("role").asText()) && m.path("content").asText("").length() > 200) {
                // cleared whole, never cut short (partial data reads as the whole — the 0.1.6 lesson); the call it answered stays named
                String call = "";
                if (i > 0) { JsonNode prev = history.get(i - 1); for (JsonNode c : prev.path("tool_calls")) if (c.path("id").asText("").equals(m.path("tool_call_id").asText("-"))) call = c.path("function").path("name").asText("") + " " + Acquisitions.compress(c.path("function").path("arguments").asText(""), 80); }
                ((ObjectNode) m).put("content", "[older observation" + (call.isEmpty() ? "" : " of " + call) + " cleared to fit the context — its facts are in your notes]");
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
    /**
     * How far the character count underestimates this model's tokens, measured from what the server says a request really was. A text of
     * addresses, numbers and citations packs far fewer characters into a token than prose does: the estimate put a write-up inside a 16k
     * window that the server counted 40 tokens over, twice. It only ever corrects upward within a run of requests, and eases back slowly.
     */
    static volatile double tokenScale = 1.0;

    static void calibrate(int estimatedAtScaleOne, long realPromptTokens) {
        if (estimatedAtScaleOne < 500 || realPromptTokens <= 0) return;
        double measured = Math.min(2.5, Math.max(0.8, realPromptTokens * 1.03 / estimatedAtScaleOne));
        tokenScale = Math.max(measured, tokenScale * 0.97);
    }

    /** The estimate before any correction: what the correction is measured against. Pure, because workers run side by side. */
    private static int rawEstimate(ArrayNode history, ArrayNode tools) {
        double n = rawTokens(tools == null ? "" : tools.toString());
        for (JsonNode m : history) n += rawTokens(m.path("content").asText("")) + rawTokens(m.path("tool_calls").toString()) + 8;
        return (int) n;
    }

    private static double rawTokens(String s) {
        if (s == null) return 0;
        int cjk = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3040 && c <= 0x30ff) || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0xac00 && c <= 0xd7af)) cjk++;
            else other++;
        }
        return cjk + other / 4.0;
    }

    /** When clearing old observations is not enough (a write-up has none): the longest message loses its middle until the request fits three fifths of the window. */
    static int cutLongest(ArrayNode history, ArrayNode tools, int nctx) {
        int cuts = 0;
        for (int guard = 0; guard < 6; guard++) {
            int total = estTokens(tools == null ? "" : tools.toString());
            for (JsonNode m : history) total += estTokens(m.path("content").asText("")) + estTokens(m.path("tool_calls").toString()) + 8;
            if (total <= nctx * 0.6) break;
            ObjectNode longest = null;
            for (JsonNode m : history) if (m.isObject() && m.path("content").isTextual() && (longest == null || m.path("content").asText().length() > longest.path("content").asText().length())) longest = (ObjectNode) m;
            if (longest == null || longest.path("content").asText().length() < 2_000) break;
            String c = longest.path("content").asText();
            int keep = (int) (c.length() * 0.75), head = keep * 3 / 5, tail = keep - head;
            longest.put("content", c.substring(0, head) + "\n\n[… " + (c.length() - keep) + " characters cut from the middle to fit the model's window …]\n\n" + c.substring(c.length() - tail));
            cuts++;
        }
        return cuts;
    }

    static int estTokens(String s) { return (int) Math.ceil(rawTokens(s) * tokenScale); }

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
     * reports held 17 sources (a test box, I-0002, 2026-09-11).
     */
    static String fitNotes(List<String> pieces, int ctxTokens) {
        int total = Math.max(2000, (int) (Math.max(ctxTokens, 8000) * 0.28));
        int n = Math.max(1, pieces.size());
        int[] size = new int[n], give = new int[n];
        for (int i = 0; i < n; i++) size[i] = estTokens(pieces.get(i));
        // water-filling: the smallest first, each taking what it needs up to an even share of what is left
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(size[a], size[b]));
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
     * sub-question always. Measured on the test box's record: a summary-first cut kept 2 of 57 sources.
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
        int c = 0; Matcher m = Pattern.compile("— source: ").matcher(piece);
        while (m.find()) c++;
        return c;
    }

    static final Pattern SAYS_EMPTY = Pattern.compile("(?i)[^.\\n]*\\b(no (direct |published |empirical )?evidence|found nothing|nothing (was )?found|no sources?|did not (find|surface|locate)|could not (find|locate)|remains? untested|not (been )?(tested|studied|examined))\\b[^.\\n]*");

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
        Map<String, Integer> df = new HashMap<>();
        for (String p : pieces) {
            String head = p.startsWith("SUB-QUESTION: ") ? p.substring(0, p.indexOf('\n') < 0 ? p.length() : p.indexOf('\n')) : p;
            Set<String> ht = Frontier.terms(head); heads.add(ht);
            for (String w : ht) df.merge(w, 1, Integer::sum);
        }
        Set<String> generic = Set.of("evidence", "found", "find", "sources", "source", "direct", "specific", "specifically", "question", "questions", "research",
                "gathered", "whether", "remains", "remain", "untested", "surveyed", "literature", "studies", "study", "exact", "exactly", "any", "the", "and",
                "how", "what", "which", "does", "did", "not", "nor", "was", "were", "been", "that", "this", "these", "those", "from", "with", "about", "into", "there");
        Matcher m = SAYS_EMPTY.matcher(answer);
        while (m.find()) {
            String sentence = m.group().strip();
            Set<String> st = new HashSet<>(Frontier.terms(sentence));
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
    /** The conversation's seat: thinking off and temperature 0, so the words come out instead of the thinking (the Librarian, 2026-09-13). */
    public static Drive calmDrive(String baseUrl, String model) { return calmDrive(new DriveClient(baseUrl, model)); }

    static Drive calmDrive(DriveClient c) {
        return new Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return c.chatOps(messages, tools, maxTokens, toolChoice); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return c.classify(messages, maxTokens); }
            @Override public int contextWindow() { return c.contextWindow(); }
        };
    }

    /** The judge seat when one is set, else the workers' drive — calm, for a conversation. */
    public static Drive calmJudgeDrive(String workersDrive, String workersModel) {
        String[] seat = judgeSeat(workersDrive, workersModel);
        return calmDrive(seat[0], seat[1]);
    }

    public static Drive drive(String baseUrl, String model) { return drive(new DriveClient(baseUrl, model)); }

    static Drive drive(DriveClient c) {
        return new Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                return c.chat(messages, tools, maxTokens, toolChoice);
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return c.classify(messages, maxTokens); }
            @Override public int contextWindow() { return c.contextWindow(); }
        };
    }

    /**
     * The live web tools: search (with its steerer) and fetch (which checks each page and captures its raw text to the library). Under a
     * run's content policy, every one of them applies the run's lists and the fetch checks each page for what the run leaves out.
     */
    public static Tools webTools() {
        return new Tools() {
            private final ThreadLocal<WebSearchTool> last = new ThreadLocal<>();
            @Override public List<Tool> web(String focus) { return web(focus, ContentPolicy.defaults()); }
            @Override public List<Tool> web(String focus, ContentPolicy policy) {
                ContentPolicy p = policy == null ? ContentPolicy.defaults() : policy;
                var search = new WebSearchTool().focus(focus).policy(p.fetchPolicy());
                var fetch = new WebFetchTool().focus(focus).policy(p);
                last.set(search);
                List<Tool> out = new ArrayList<>(List.of(search, new ScholarSearchTool().policy(p.fetchPolicy()), fetch));
                // the record sources, the ones in the languages this sub-question is about first
                List<String> languages = new ArrayList<>(List.of(Lanes.ownLanguage(focus == null ? "" : focus)));
                for (Lanes.Lane lane : Lanes.detect(focus, null)) languages.add(lane.code());
                var records = new RecordSearchTool(languages, focus).policy(p.fetchPolicy());
                if (RECORDS && records.any()) out.add(records);
                // YouTube, where the video helper is installed: videos and channels are sources like pages are
                if (Video.installed()) { out.add(new VideoSearchTool()); out.add(new ChannelUploadsTool()); out.add(new VideoDetailsTool().policy(p)); out.add(new ChannelsLikeTool()); }
                return out;
            }
            @Override public BooleanSupplier exhausted() {
                var s = last.get();
                return s == null ? () -> false : () -> s.steer().exhausted();
            }
        };
    }
}
