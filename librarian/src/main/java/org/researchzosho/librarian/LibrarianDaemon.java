package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.core.JacksonException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.Config;
import org.researchzosho.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.researchzosho.Stopping;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.mcp.McpServer;
import org.researchzosho.drive.Declined;
import java.time.Duration;
/**
 * The Librarian as a long-running process: the library protocol over HTTP (the transport the
 * SDKs speak), bearer tokens that PROVE a patron's did, overnight research jobs, and the crews.
 *
 * <p>Routes (JSON in, JSON out; see docs/LIBRARY_PROTOCOL.md §6, which a test pins to {@link #v1Routes}):
 * <pre>
 *   POST /v1/{name}      body = the tool's arguments, for every tool of §3 by its name without library_ ({@link #V1}: ask, search, get,
 *                        read, established, submit, add, absorb, survey, repo, items, check, reading, questions, bookmarks, meeting,
 *                        holdings, who, db, remove, bridges, seeks, frontier, inbox, serials, subjects, status, help, changes, map,
 *                        perspectives, sharpen, explain, request_access, access, subscribe, unsubscribe, settings, models, mcp, share,
 *                        sources, owned, my_shelf, prefs, dna, follow, react, dossier, export, suggest, tell, video, look, candidates,
 *                        sift, patents, patent_abstracts, patent_claims, home, records, career, board, job, crews, spend)
 *   GET  /v1/status
 *   GET  /v1/resources?cursor=…   GET /v1/resources/templates   GET /v1/resource?uri=…
 *   POST /v1/research    the tool's arguments  →  {job_id, state: queued}  (write; a research run, the drive probed first)
 *   GET  /v1/jobs/{id}   GET /v1/jobs                                      (the ledger)
 *   POST /v1/jobs/{id}   {op: stop|pause|resume|choose|allow|decline, choice?}   (write; as library_job with that id)
 *   POST /v1/crews/run  →  {job_id}                                       (write; as library_crews op=run)
 *   GET  /healthz        no sign-in
 *   POST /rpc            MCP over Streamable HTTP (request/response subset): what `claude mcp add --transport http` speaks
 * </pre>
 * Errors: HTTP status by code (not_found 404, forbidden 403, no_sources 422, invalid_args 400,
 * unavailable 503) with body {@code {"error": {"code", "message"}}}.
 *
 * <p>Identity: {@code Authorization: Bearer <token>} resolves to a listed patron. Without a token
 * the caller is anonymous, and a body that ASSERTS a did without proving it is refused —
 * explicit beats a silent downgrade. JDK {@code HttpServer}, no dependency; binds loopback
 * unless told otherwise.
 */
public final class LibrarianDaemon {

    private static final ObjectMapper M = new ObjectMapper();

    /** The port the service takes when none is named: 4649, unassigned, and 4-6-4-9 reads "yoroshiku" in Japanese number-play. */
    public static final int DEFAULT_PORT = 4649;

    private final LibraryStore store;
    private final HttpServer server;
    private final String driveUrl;
    private final String driveSetting;   // what the drive setting said when the daemon started, or null
    private final String model;
    private final Jobs jobs;
    private Thread crews;
    /** The shared door: a second loopback listener that only a relay points at; null without one. */
    private HttpServer door;
    /** Request attributes: set on the door, so the pages know the cookie wants Secure and the identity headers may be read. */
    public static final String DOOR = "rz.door", IDENTITY_LOGIN = "rz.identity.login", IDENTITY_NAME = "rz.identity.name", BODY = "rz.body";

    private LibrarianDaemon(LibraryStore store, HttpServer server, String driveUrl, String model) {
        this.store = store; this.server = server; this.driveUrl = driveUrl; this.model = model;
        this.driveSetting = Config.get("RESEARCHZOSHO_DRIVE");
        this.jobs = new Jobs(store, this::runJob, () -> ModelSlots.lanes(driveNow()), Jobs.workersNow());
    }

    /**
     * The model server the library is set to now (`model use` changes it while the service runs); the one it started with until
     * the setting moves. The one it started with is the setting itself in the service, or an address given on the command line,
     * or a test's stub: none of them is to be swapped for whatever the config file held at the start.
     */
    String driveNow() {
        String d = Config.get("RESEARCHZOSHO_DRIVE");
        return d == null || d.isBlank() || d.equals(driveSetting) ? driveUrl : d;
    }

    /** The model a job asks for, fixed when the job starts so that one run never changes models halfway. */
    private static final ThreadLocal<String> JOB_MODEL = new ThreadLocal<>();

    /** The model: the job's own while one runs on this thread, else the one the library is set to now, else the one it started with. */
    String model() {
        String m = JOB_MODEL.get();
        if (m != null) return m;
        String c = Config.get("RESEARCHZOSHO_MODEL");
        return c == null || c.isBlank() ? model : c;
    }

    /**
     * A worker's dispatch, on its own drive: research asks and crews runs. A research run works under its stop ({@link Stopping}): when a
     * person asks for it, a request to the model(), a page fetch or a search in flight ends within a second, its connection closed. The
     * nightly tasks stop between their steps, as before: a step given up in the middle would write down the rest of its work as unanswered.
     */
    private String runJob(ObjectNode job, String drive) {
        String id = job.path("job_id").asText("");
        if (!"research".equals(job.path("kind").asText())) return dispatch(job, drive);
        BooleanSupplier stop = () -> !id.isEmpty() && jobs.stopRequested(id);
        runStops.put(id, stop);   // the run's threads are found by it, when the run shows no progress
        try { return Stopping.within(stop, () -> dispatch(job, drive)); }
        finally { runStops.remove(id); stallsSaid.remove(id); }
    }

    private static final Logger log = LoggerFactory.getLogger(LibrarianDaemon.class);

    /** Each running research run's stop, by job: the threads working under it are the run's. */
    private final Map<String, BooleanSupplier> runStops = new ConcurrentHashMap<>();
    /** The runs whose threads were written to the log, with the progress they had shown no progress since: once for each such time. */
    private final Map<String, String> stallsSaid = new ConcurrentHashMap<>();
    /** How often the service looks for a research run that shows no progress; a test shortens it. */
    static volatile long STALL_LOOK_MS = 60_000;
    /** Where the threads of a run that shows no progress are written: the service's log, unless a test reads them. */
    static volatile Consumer<String> stallLog = null;
    private Thread stallWatch;

    /**
     * A research run that has shown no progress for a quarter of an hour ({@link Jobs#STALL_MS}) has where each of its threads waits written
     * to the service's log, once, so that the next run that hangs shows where it hangs (the owner's run of 2026-09-24 sat six hours, and
     * nothing said on what). The crews log says it was written.
     */
    void lookForStalls(long now) throws IOException {
        for (ObjectNode j : jobs.active()) {
            String id = j.path("job_id").asText();
            String since = Jobs.noProgressSince(j, now);
            if (since == null || since.equals(stallsSaid.get(id))) continue;
            stallsSaid.put(id, since);
            List<Thread> threads = Stopping.threadsUnder(runStops.get(id));
            String text = "research " + id + " has shown no progress since " + since + ". Where each of its " + threads.size() + " thread(s) waits:\n" + Stopping.dump(threads);
            Consumer<String> out = stallLog;
            if (out != null) out.accept(text); else log.warn(text);
            Crews.log(store, "research " + id, "no progress since " + since + "; where each of the run's threads waits was written to the service's log", 0);
        }
    }

    private void watchStalls() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(STALL_LOOK_MS); } catch (InterruptedException e) { return; }
                try { lookForStalls(System.currentTimeMillis()); } catch (Exception ignored) { }
            }
        }, "stall-watch");
        t.setDaemon(true); t.start();
        stallWatch = t;
    }

    /** The named model the job runs on (researchzosho models), looked up when the job starts; the default entry when none was named. */
    private static final ThreadLocal<ModelEntries.Entry> JOB_ENTRY = new ThreadLocal<>();
    /** Why best judgement chose the job's model, for the report; empty when the model was named. */
    private static final ThreadLocal<String> JOB_WHY = new ThreadLocal<>();

    /** One job, on the model the library is set to when it starts — or on the named model its ask chose: `model use` meanwhile changes the next job's, never this one's halfway. */
    private String dispatch(ObjectNode job, String drive) {
        JOB_MODEL.remove(); JOB_ENTRY.remove(); JOB_WHY.remove();
        String named = job.path("args").path("model_entry").asText("");
        ModelEntries.Entry entry;
        if (named.equalsIgnoreCase(Auto.NAME)) {
            // best judgement, made now: the keys, caps and history as they stand when the run starts
            Auto.Pick pick = Auto.choose(store, Auto.Job.research(job.path("args").path("quick").asBoolean(false)));
            entry = pick.entry();
            JOB_WHY.set(pick.why());
            ((ObjectNode) job.path("args")).put("model_why", pick.why());
            log.info("{}: {}", job.path("id").asText(""), pick.why());
        } else entry = named.isEmpty() ? null : ModelEntries.get(named);
        if (!named.isEmpty() && entry == null) throw new IllegalStateException("No model is named " + named + " any more. researchzosho models lists the names; ask again with one of them.");
        JOB_MODEL.set(entry == null ? model() : entry.model());
        JOB_ENTRY.set(entry == null ? ModelEntries.defaultEntry() : entry);
        try { return dispatchNow(job, entry == null ? drive : entry.drive()); }
        finally { JOB_MODEL.remove(); JOB_ENTRY.remove(); JOB_WHY.remove(); }
    }

    private String dispatchNow(ObjectNode job, String drive) {
        String kind = job.path("kind").asText();
        ObjectNode a = (ObjectNode) job.path("args");
        String d = drive == null || drive.isEmpty() ? driveNow() : drive;
        switch (kind) {
            case "research" -> {
                // a cap on the named model: a run that would start past it is refused with the figure, before any call is made
                ModelEntries.Entry entry = JOB_ENTRY.get();
                if (entry != null && entry.cap() > 0) {
                    double spent = Spend.monthTotal(store, entry.name());
                    if (spent >= entry.cap()) throw new IllegalStateException("The model " + entry.name() + " has spent " + Spend.dollars(spent) + " this month against its cap of " + Spend.dollars(entry.cap()) + ". The run is not started. Raise the cap (researchzosho models set " + entry.name() + " --cap <dollars>) or ask again with another model.");
                }
                boolean answers = entry != null && !entry.name().equals(ModelEntries.DEFAULT) ? Crews.driveState(d, entry.model(), Duration.ofSeconds(20)) == Crews.DriveState.ANSWERS : Crews.driveAnswers(d);
                if (!answers) throw new IllegalStateException("No model is answering at " + d + (entry != null && !entry.name().equals(ModelEntries.DEFAULT) ? " for " + entry.name() + " (" + entry.model() + ")" : "") + ". The question was saved but cannot run. Send it again when a model is up.");
                try { contentCheckBeforeRun(job, a); }
                catch (IOException e) { throw new IllegalStateException("The library could not be written: " + e.getMessage(), e); }
                String writer = job.path("patron").asText("").isEmpty() ? "patron:anonymous" : "patron:" + job.path("patron").asText();
                try {
                    return runResearch(job, a, d, writer);
                } catch (IOException e) {
                    throw new IllegalStateException("The library could not be written: " + e.getMessage(), e);
                }
            }
            case Seeks.KIND_MERGE -> {
                // seeks: one report from a group's seeks; the judge reads the claim pairs, the writer seat writes the answer
                String group = a.path("group").asText("");
                if (!Crews.driveAnswers(d)) throw new IllegalStateException("No model is answering at " + d + ". The merge needs one to read the seeks' claims.");
                String writer = job.path("patron").asText("").isEmpty() ? "patron:anonymous" : "patron:" + job.path("patron").asText();
                // the judge's readings of the claim pairs are kept in order: a restart gives back the ones read, and a merge filed before it is not filed again
                try (Checkpoint mcp = checkpoint(job, Checkpoint.basisOf(group)); var lane = Checkpoint.lane("main")) {
                    if (mcp.get("merged") != null) return mcp.get("merged").asText();
                    Researcher.Drive judge = mcp.seat(judgeFactory != null ? judgeFactory.apply(d) : Researcher.judgeDrive(d, model()));
                    // a seek that landed with its claims unsettled (a stop, an outage) is settled now, on its own shelves, so the merge has claims to read
                    ObjectNode g = Seeks.group(store, group);
                    for (String seekJob : Seeks.jobsOf(g)) {
                        Investigation inv = Seeks.read(store, group, seekJob);
                        LibraryStore sc = Seeks.scratchOf(store, group, seekJob);
                        if (inv != null && sc != null && inv.findings().isEmpty()) settle(sc, inv.id(), d, seekJob);
                    }
                    Seeks.Merged m = Seeks.merge(store, group, judge, writer);
                    Crews.log(store, "merge " + job.path("job_id").asText(""), "seeks " + group + " → " + m.investigationId() + " (" + m.agreed() + " agreed, " + m.disputed() + " disputed, " + m.lone() + " by one seek)", 0);
                    String result = "investigation " + m.investigationId() + "\n\nMerged from the seeks of " + group + ": " + m.agreed() + " claim" + (m.agreed() == 1 ? "" : "s") + " agreed, " + m.disputed() + " disputed, " + m.lone() + " found by one seek.";
                    mcp.put("merged", M.getNodeFactory().textNode(result));
                    return result;
                } catch (IOException e) { throw new IllegalStateException("The library could not be written: " + e.getMessage(), e); }
            }
            case "crews" -> {
                String crewId = job.path("job_id").asText("");
                List<Crews.Step> steps;
                try (Checkpoint cp = checkpoint(job, Checkpoint.basisOf(job.path("queued_at").asText("")))) {   // step by step: a restart goes on from the step it was on
                    steps = Crews.runAll(store, d, model(), () -> !crewId.isEmpty() && jobs.stopRequested(crewId), cp);
                }
                StringBuilder sb = new StringBuilder();
                for (var s : steps) sb.append(s.name()).append(": ").append(s.outcome()).append(" (").append(s.ms()).append("ms)\n");
                return sb.toString();
            }
            case "career-search", "career-read" -> {   // the career field's search, or more of its readings (docs/DESIGN_CAREER.md, step 3)
                String jid = job.path("job_id").asText("");
                beganAgain(job, "This kind of job begins again from the start; the openings the model already read keep their readings and are not read again.");
                try { return CareerFind.runJob(store, job, p -> { try { jobs.progress(jid, p); } catch (IOException ignored) { } }, () -> !jid.isEmpty() && jobs.stopRequested(jid)); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
            case HomeFind.JOB_KIND -> {   // the home field's search: the listings from every source the library can read, the cut, the cards
                String jid = job.path("job_id").asText("");
                beganAgain(job, "This kind of job begins again from the start.");
                try { return HomeFind.runJob(store, job, p -> { try { jobs.progress(jid, p); } catch (IOException ignored) { } }, () -> !jid.isEmpty() && jobs.stopRequested(jid)); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
            case VideoRecall.JOB_KIND -> {   // the picture in your head: the frames of the videos held, described and judged by the model that reads pictures
                String jid = job.path("job_id").asText("");
                beganAgain(job, "This kind of job begins again from the start; the videos the model already looked through keep what it saw and are not looked through again.");
                try { return VideoRecall.runJob(store, job, p -> { try { jobs.progress(jid, p); } catch (IOException ignored) { } }, () -> !jid.isEmpty() && jobs.stopRequested(jid)); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
            case Translation.JOB_KIND -> {   // a document a person handed over, translated unit by unit and written out in a format, with its note; onto the shelves with the original
                String jid = job.path("job_id").asText("");
                try (Checkpoint cp = checkpoint(job, Translation.basis(job))) {   // unit by unit: a restart resumes it where it was
                    return Translation.runJob(store, job, d, model(), p -> { try { jobs.progress(jid, p); } catch (IOException ignored) { } }, () -> !jid.isEmpty() && jobs.stopRequested(jid), cp);
                }
            }
            case "translate" -> {   // a dossier or a report read in another language, made by the library's model and kept until it changes
                try (Checkpoint cp = checkpoint(job, Checkpoint.basisOf(a.path("report").asText(""), a.path("subject").asText(""), a.path("lang").asText("en")));
                     var lane = Checkpoint.lane("main")) {   // its calls in order: a restart gives back the parts translated
                    if (!a.path("report").asText("").isEmpty()) return Translate.reportIn(store, a.path("report").asText(), a.path("lang").asText("en"), cp.seat(Translate.DRIVE.get())).summary();
                    return Translate.dossierIn(store, a.path("subject").asText(""), a.path("lang").asText("en"), cp.seat(Translate.DRIVE.get())).summary();
                }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
            default -> throw new IllegalArgumentException("unknown job kind " + kind);
        }
    }

    /**
     * The job's kept work ({@link Checkpoint}), opened for its run. A job a restart or an update interrupted resumes from it, and the crews
     * log says where; when its kept work could not be used, its record says why it began again.
     */
    Checkpoint checkpoint(ObjectNode job, String basis) {
        String id = job.path("job_id").asText("");
        Checkpoint cp = Checkpoint.open(store, id, job.path("kind").asText(""), basis);
        if (job.path("interruptions").isArray()) {
            if (!cp.startedOver().isEmpty()) jobs.startedOver(id, cp.startedOver());
            Crews.log(store, job.path("kind").asText("job") + " " + id, cp.resumed() ? "resumes after the restart" + (cp.saidBefore().isEmpty() ? "" : ", at " + cp.saidBefore()) + ", from the work it kept"
                    : cp.startedOver().isEmpty() ? "starts again after the restart; it had kept nothing yet" : cp.startedOver(), 0);
        }
        return cp;
    }

    /** A job of a kind that keeps no work to resume from, interrupted: its record says it began again, in {@code sentence}. */
    void beganAgain(ObjectNode job, String sentence) {
        if (job.path("interruptions").isArray()) jobs.startedOver(job.path("job_id").asText(""), sentence);
    }

    /**
     * A research run, on the library's OWN runner ({@link Researcher}): plan from the brief's
     * sub-questions, parallel workers, the critic, the synthesis — then the acquisitions gate and the
     * shelf. The run's evidence goes into the investigation with the answer, so a cut-off synthesis
     * still leaves the work on record. Progress lines land in catalog/crews.log under the job id.
     */
    /**
     * What a program's submit left for the service to read, when the model gave no verdict in time ({@code content_check: pending}, 0.5.4):
     * what the library leaves out by default, whose suggestion goes onto the job for the caller to read; and whether the question reads as a
     * person asking about harming themselves — then the run is held for the person's yes ({@link Jobs.Hold}).
     */
    void contentCheckBeforeRun(ObjectNode job, ObjectNode a) throws IOException { contentCheckBeforeRun(store, jobs, job, a); }

    static void contentCheckBeforeRun(LibraryStore store, Jobs jobs, ObjectNode job, ObjectNode a) throws IOException {
        String check = a.path("content_check").asText("");
        if (check.isEmpty()) return;
        String jobId = job.path("job_id").asText("");
        List<String> allow = new ArrayList<>();
        for (JsonNode x : a.path("allow")) allow.add(x.asText());
        ContentOffer.Detected det = ContentOffer.detect(a.path("question").asText(""), null);
        List<String> needs = new ArrayList<>(det.needs());
        needs.removeAll(allow);
        a.remove("content_check");
        if (!needs.isEmpty()) a.set("content_suggestion", ContentOffer.suggestion(needs));
        jobs.saveArgs(jobId, a);
        if (det.harm() == ContentOffer.Harm.SURE && !allow.contains(ContentPolicy.SELF_HARM)) {
            Crews.log(store, "research " + jobId, "held for the person's yes: the question reads as a person asking about harming themselves", 0);
            throw new Jobs.Hold(ContentOffer.heldMessage(jobId), System.currentTimeMillis() + Jobs.HELD_MS);
        }
    }

    /** The question with what an earlier report answered, for a run asked for what is new since it ({@code delta_of}). */
    String withEarlier(String question, String earlierId) throws IOException { return withEarlierFor(store, question, earlierId); }

    static String withEarlierFor(LibraryStore store, String question, String earlierId) throws IOException {
        LibraryProtocol protocol = new LibraryProtocol(store);
        ObjectNode earlier = protocol.entry(earlierId, protocol.kindOf(earlierId), true);
        if (earlier == null) return question;
        String body = earlier.path("body").asText("");
        String said = LibraryProtocol.sectionOf(body, "answer");
        if (said == null || said.isBlank()) said = body;
        return question + "\n\nWHAT THE LIBRARY'S EARLIER REPORT " + earlierId + " (" + earlier.path("recorded_at").asText("").replaceAll("T.*", "")
                + ") ALREADY ANSWERED, for what is new since:\n" + Acquisitions.compress(said, 8_000)
                + "\n\nReport what is new, changed, or contradicts that report since it was written. Where nothing changed, say so in a "
                + "sentence and cite " + earlierId + " rather than repeating it.";
    }

    private String runResearch(ObjectNode job, ObjectNode a, String drive, String writer) throws IOException {
        String jobId = job.path("job_id").asText("job");
        // the run keeps its work as it goes: a restart or an update resumes it at the turn it was on
        try (Checkpoint cp = checkpoint(job, researchBasis(a))) { return runResearch(job, a, drive, writer, cp); }
    }

    /** What a research run's kept work is for: its question and what it was asked to be. Another of any of them is another run. */
    static String researchBasis(JsonNode a) {
        List<String> subs = new ArrayList<>(); for (JsonNode s : a.path("sub_questions")) subs.add(s.asText(""));
        return Checkpoint.basisOf(Jobs.sameAskKey(a), a.path("delta_of").asText(""), a.path("max_turns").asText(""), a.path("max_minutes").asText(""), String.join("\n", subs), a.path("seek").asText(""), a.path("title").asText(""));
    }

    private String runResearch(ObjectNode job, ObjectNode a, String drive, String writer, Checkpoint cp) throws IOException {
        String jobId = job.path("job_id").asText("job");
        // the report was filed before the restart: the job ends with it, and nothing is filed twice
        if (cp.get("filed") != null) {
            String id = cp.get("filed").asText();
            Crews.log(store, "research " + jobId, "the report " + id + " was filed before the restart; the job ends with it, and the nightly housekeeping reads its claims", 0);
            return "investigation " + id + "\n\nThe report was filed before the service restarted. The claims in it are read by the nightly housekeeping.";
        }
        String question = a.path("question").asText();
        if (a.hasNonNull("delta_of")) question = withEarlier(question, a.get("delta_of").asText());
        List<String> subs = new ArrayList<>();
        for (JsonNode s : a.path("sub_questions")) if (s.isTextual() && !s.asText().isBlank()) subs.add(s.asText());
        List<String> colls = new ArrayList<>();
        for (JsonNode c : a.path("collections")) if (c.isTextual()) colls.add(c.asText());
        var ask = new Researcher.Ask(question, a.path("mode").asText("broad"), a.path("max_turns").asInt(LibraryProtocol.DEFAULT_TURNS), subs, a.path("sources").asText("both"), colls, a.path("max_minutes").asInt(0), LibraryProtocol.fieldsOf(a), LibraryProtocol.allowOf(a), LibraryProtocol.goalsOf(a));
        // the question looked like a field's that joins only when asked, and nobody asked: the run is ordinary, and the log says what was suggested where it was filed
        if (a.path("suggested").isObject()) Crews.log(store, "research " + jobId, "suggestion: " + a.path("suggested").path("field").asText() + " — " + a.path("suggested").path("offer").asText() + " The run is ordinary research.", 0);
        long t0 = System.currentTimeMillis();
        RunTrace trace = RunTrace.open(store, jobId);
        if (cp.resumed()) { ObjectNode ev = M.createObjectNode(); ev.put("at", cp.saidBefore()); ev.put("worked_ms", cp.elapsedBeforeMs()); trace.event("resumed", ev); }
        Researcher researcher = researcher(drive, trace);
        researcher.checkpoint(cp);
        researcher.onFiled(id -> cp.put("filed", M.getNodeFactory().textNode(id)));
        researcher.entry(JOB_ENTRY.get() == null ? ModelEntries.defaultEntry() : JOB_ENTRY.get());   // the run's model, for its report and its cost
        researcher.why(JOB_WHY.get());
        researcher.paid(a.path("paid").asBoolean(true));   // the paid search places left off for this run when the ask said so
        if (a.hasNonNull("title")) researcher.title(a.get("title").asText());
        researcher.options(ReportOptions.forRun(a));   // the ask's length, citations and shape, and the library's defaults for the rest
        // a run has a model: the pages the person gave that wait for their check are checked first, a few at a time
        try {
            var later = UncheckedPages.recheck(store, ContentJudge.of(new DriveClient(drive, model())), UncheckedPages.PER_COMMAND);
            if (!later.sentence().isEmpty()) Crews.log(store, "research " + jobId, later.sentence(), 0);
        } catch (Exception ignored) { }
        String asker = job.path("patron").asText("");
        researcher.allowDatabases(asker.equals("person") || (asker.equals("web") && !WebAccess.signInRequired()));   // the owner's databases are for the owner's questions
        researcher.mcpFor(McpSources.patronOf(asker));   // the keeper's MCP servers a run may use: a reader's only those the keeper marked for readers
        researcher.stopWhen(() -> jobs.stopRequested(jobId));
        researcher.beginFiling(() -> { try { return jobs.beginFiling(jobId); } catch (IOException e) { return !jobs.stopRequested(jobId); } });
        researcher.onProgress(p -> { try { jobs.progress(jobId, p); } catch (IOException ignored) { } });
        // a seek reads and writes its own copy of the shelves, under seeks/<group>/<job>; nothing it writes reaches the library until the merge keeps it
        String seek = a.path("seek").asText("");
        LibraryStore shelves = store;
        if (!seek.isEmpty()) {
            ModelEntries.Entry entry = JOB_ENTRY.get();
            shelves = Seeks.scratchFor(store, seek, jobId, question, entry == null || entry.name().equals(ModelEntries.DEFAULT) ? model() : entry.model(), ask.sources());
            Crews.log(store, "research " + jobId, "seek " + seek + ": this run reads and writes its own copy of the shelves (seeks/" + seek + "/" + jobId + ")", 0);
        }
        Researcher.Filed filed;
        try { filed = Researcher.file(shelves, researcher, ask, writer, jobId, a.path("field_how").asText("asked"), a.path("allow_how").asText("asked")); }
        catch (Researcher.CannotCheck c) {
            // the page check cannot run on this server while its model answers: the run stops, and its result is the plain statement
            ObjectNode ev = new ObjectMapper().createObjectNode(); ev.put("by", "page check"); ev.put("statement", c.getMessage()); trace.event("stopped", ev);
            Crews.log(store, "research " + jobId, c.getMessage(), System.currentTimeMillis() - t0);
            throw new Jobs.Failure(c.getMessage());
        }
        catch (Stopping.Requested s) {
            // stopped between two turns or in the middle of a call: the worker marks the job stopped from the marker
            ObjectNode ev = new ObjectMapper().createObjectNode(); ev.put("by", "person"); trace.event("stopped", ev);
            Crews.log(store, "research " + jobId, "stopped by the person; nothing from this run was filed", System.currentTimeMillis() - t0);
            return "nothing from this run was filed";
        }
        try { jobs.recordTurns(job.path("patron").asText(""), filed.result().turnsUsed()); } catch (IOException ignored) { }
        ObjectNode declined = filed.result().declinedView();
        if (declined != null) { try { jobs.declined(jobId, declined); } catch (IOException ignored) { } }
        // what the run's checks made of the report: on the job, and in the result's own words when the report waits for the keeper
        ObjectNode checks = filed.admitted() ? filed.result().checksView() : null;
        if (checks != null) { try { jobs.checks(jobId, checks); } catch (IOException ignored) { } }
        String held = filed.admitted() ? filed.result().heldStatement() : "";
        // the ledger row: what this run cost and produced, beside every earlier run's
        ObjectNode row = RunLedger.row(jobId, ask, filed.result(), filed.admitted() ? "filed " + filed.investigationId() : filed.declined() ? "declined by the model" : "refused: " + filed.reason(),
                System.currentTimeMillis() - t0, drive, model(), trace.totals());
        row.put("fetches_total", RunLedger.fetchCalls(store, jobId));
        RunLedger.record(store, row);
        if (cp.resumed()) Crews.log(store, "research " + jobId, cp.resumedLine(), 0);
        if (filed.admitted()) settle(shelves, filed.investigationId(), drive, jobId);   // a seek settles on its own shelves: its claims become findings there
        if (!seek.isEmpty()) Seeks.landed(store, seek, jobId, filed.admitted() ? filed.investigationId() : null, filed.result().summary());
        Crews.log(store, "research " + jobId, filed.result().summary() + (filed.admitted() ? " → " + filed.investigationId() + (held.isEmpty() ? "" : " (a draft that waits for the keeper: " + held + ")") : filed.declined() ? " → declined by the model" : " → refused: " + filed.reason()),
                System.currentTimeMillis() - t0);
        // the model declined the run: the statement is the result, not a refusal and not a failure of the machine
        if (filed.declined()) return filed.reason();
        return (seek.isEmpty() ? "" : "seek " + seek + ": ") + (filed.admitted() ? "investigation " + filed.investigationId()
                : "(not accepted — " + filed.reason() + "; see the open questions)") + "\n\n" + filed.result().summary()
                + (held.isEmpty() ? "" : "\n\n" + held + " To accept it as it stands: researchzosho accept " + filed.investigationId())
                + (declined == null ? "" : "\n\n" + declined.path("statement").asText());
    }

    /** How often the service looks for changes the vault has not seen; a test shortens it. */
    static volatile long VAULT_FOLLOW_MS = Config.getInt("RESEARCHZOSHO_VAULT_FOLLOW_SECONDS", 10) * 1000L;

    /**
     * The vault follows the library within seconds, not at the housekeeping: once the person has made the folder (`researchzosho
     * vault`), the service refreshes it whenever anything in the record changes — a landed write-up, an accepted claim,
     * an added document. Only notes that changed are rewritten, so a refresh with nothing to do costs a walk.
     */
    private void followVault() {
        Thread t = new Thread(() -> {
            long seen = Vault.exists(store) ? Vault.newest(store) : 0;
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(VAULT_FOLLOW_MS); } catch (InterruptedException e) { return; }
                try {
                    long now = Vault.followUp(store, seen);
                    if (now != seen) { seen = now; }
                } catch (Exception ignored) { }
            }
        }, "vault-follow");
        t.setDaemon(true); t.start();
    }

    /**
     * A write-up that just landed is settled at once rather than at three in the morning: the librarian's review pass
     * turns its extractions into claims on the shelves, and the triples step gives those claims their place on the map.
     * (Measured: the Tokyo Vice write-ups sat a day with no claims, so `ask` and the map knew nothing about Tokyo.) {@code shelves} is the
     * store the write-up is on: the library, or a seek's own copy of it (0.6.6); the log is the library's either way.
     */
    private void settle(LibraryStore shelves, String investigationId, String drive, String jobId) {
        long t0 = System.currentTimeMillis();
        try {
            Investigation inv = shelves.investigation(investigationId);
            if (inv == null) return;
            var client = new DriveClient(drive, model());
            var idx = new LibrarianIndex(shelves);
            // each step is model calls of up to RESEARCHZOSHO_DRIVE_TIMEOUT each; a stop asked meanwhile gives up the call in flight and
            // ends the settling at the next step, and the nightly housekeeping does the rest (a stopped run once sat here for minutes, J-0024)
            var out = new LibrarianReview(shelves, idx, LibrarianReview.driveJudge(client), "librarian:" + model()).searcher(LibrarianReview.liveSearcher()).review(inv);
            if (settleStopped(jobId, investigationId, "review", t0)) return;
            // subjects from the list for this run's own claims only; the rest of the library is the nightly housekeeping's, a few at a time
            Investigation filed = shelves.investigation(investigationId);
            var cat = Cataloger.run(shelves, Cataloger.driveJudge(client), false, filed == null ? List.of() : filed.findings(), 0);
            if (settleStopped(jobId, investigationId, "subjects", t0)) return;
            var triples = Triples.fill(shelves, Triples.driveExtractor(client), 40);
            if (settleStopped(jobId, investigationId, "triples", t0)) return;
            var ret = Retractions.check(shelves, Retractions.live(), 40, LocalDate.now());   // a retracted source disputes the claim, no model involved
            if (settleStopped(jobId, investigationId, "retractions", t0)) return;
            var abs = Abstracts.run(shelves, Abstracts.driveWriter(client), null);   // only a subject whose shelf changed is rewritten (hash-guarded)
            Crews.log(store, "settle " + jobId, investigationId + ": " + out.accepted().size() + " claim(s) accepted, " + out.disputed().size() + " disputed, "
                    + out.keptDraft().size() + " kept as draft; subjects on " + cat.grounded() + " (" + cat.proposals() + " proposed); triples " + triples.filled() + "/" + triples.asked()
                    + "; retractions " + ret.retracted() + "/" + ret.checked() + " checked; summaries " + abs.written() + " rewritten"
                    + (out.problems().isEmpty() ? "" : "; review problems: " + out.problems().size() + " (see the review lines above)"), System.currentTimeMillis() - t0);
        } catch (Stopping.Requested stop) {
            Crews.log(store, "settle " + jobId, investigationId + ": stopped by the person in the middle of a step; nightly maintenance does the rest", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            Crews.log(store, "settle " + jobId, investigationId + ": could not settle now (" + e.getMessage() + "); nightly maintenance will", System.currentTimeMillis() - t0);
        }
    }

    /** True, and logged, when a stop was asked for the job while it was settling: the steps done stay done, the rest waits for the housekeeping. */
    private boolean settleStopped(String jobId, String investigationId, String after, long t0) {
        if (!jobs.stopRequested(jobId)) return false;
        Crews.log(store, "settle " + jobId, investigationId + ": stopped by the person after " + after + "; nightly maintenance does the rest", System.currentTimeMillis() - t0);
        return true;
    }

    /** The kura, 64 px, for the tab: the mark's small form, bundled so the daemon needs no static files. */
    static byte[] favicon() {
        try (InputStream in = LibrarianDaemon.class.getResourceAsStream("favicon.png")) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** The runner for one drive. A test replaces this (package-private) to script the model. */
    Function<String, Researcher> researcherFactory;
    /** Tests: the judge a merge run reads claim pairs with, in place of the library's judgment seat. */
    Function<String, Researcher.Drive> judgeFactory;

    private Researcher researcher(String drive) { return researcher(drive, null); }

    /** The seat the Librarian talks from: the judge drive when one is set, else the workers' drive. */
    Researcher.Drive chatDrive() { return Researcher.watchedChat(driveNow(), model()); }   // watched: when the model declines, the chat says so

    /** The seat for one chat session: the named model the session chose with /model, else the library's. */
    Researcher.Drive chatDrive(Librarian.Session session) {
        String named = session == null ? "" : session.model();
        ModelEntries.Entry e = named.isEmpty() ? null : named.equalsIgnoreCase(Auto.NAME) ? Auto.choose(store, Auto.Job.chat()).entry() : ModelEntries.get(named);
        return e == null ? chatDrive() : Researcher.watchedChat(e.drive(), e.model());
    }

    /** The runner for a job, its drives seen through the job's trace when there is one. */
    private Researcher researcher(String drive, RunTrace trace) {
        if (researcherFactory != null) { Researcher r = researcherFactory.apply(drive).onDrive(drive); if (trace != null) r.trace(trace); return r; }
        // both seats watched for a model that declines: it is said, never worked around (see Declines)
        Researcher.Drive workers = Researcher.watched(drive, model(), "workers", trace), judge = Researcher.watchedJudge(drive, model(), trace);
        if (trace != null) { workers = trace.wrap(workers, "workers"); judge = trace.wrap(judge, "judge"); }
        Researcher r = new Researcher(workers, judge, Researcher.webTools(), line -> Crews.log(store, "research", line, 0), store).onDrive(drive);
        r.contentJudge(ContentJudge.of(new DriveClient(drive, model())));   // the run's own model checks the pages it fetches
        if (trace != null) r.trace(trace);
        return r;
    }

    /** Bind and start. {@code port} 0 = ephemeral (tests). {@code crewHour} < 0 = no nightly crews. */
    public static LibrarianDaemon start(LibraryStore store, String host, int port, String driveUrl, String model, int crewHour) throws IOException {
        return start(store, host, port, driveUrl, model, crewHour, -1);
    }

    /**
     * The same, with the shared door: {@code doorPort} 0 = ephemeral (tests), -1 = none. The door is a second listener on this machine's
     * loopback that only a relay (tailscale serve, a cloudflared tunnel) points at; the identity headers a relay adds are trusted there and
     * nowhere else, since on the main port any program on the machine could write them (docs/AFTER_0_6.md §5).
     */
    public static LibrarianDaemon start(LibraryStore store, String host, int port, String driveUrl, String model, int crewHour, int doorPort) throws IOException {
        Lease lease = Lease.acquire(store);   // one daemon per library; refused with the holder's pid otherwise
        HttpServer s;
        try { s = HttpServer.create(new InetSocketAddress(host, port), 0); } catch (IOException e) { lease.close(); throw e; }
        // The Librarian's endpoint serves THE LIBRARY: a patron must not reach `fix` or `secure` through it
        // (seen 2026-09-03 — a Claude Code session registered against /rpc listed every codezaiku tool).
        McpServer.setToolFilter(n -> n.startsWith("library_"));
        McpServer.trustProven(true);   // over /rpc the patron is the one this daemon proved by a key or a session, the keeper included
        LibrarianDaemon d = new LibrarianDaemon(store, s, driveUrl, model);
        d.lease = lease;
        d.loopbackOnly = LocalName.loopbackOnly(s.getAddress());   // listening on the loopback only: it answers only to the loopback's names
        d.followVault();
        d.watchStalls();
        Runtime.getRuntime().addShutdownHook(new Thread(lease::close, "serve-lease-release"));   // systemd stops us with SIGTERM
        // the jobs it leaves running say why when they resume: an update, or a restart
        d.stopping = new Thread(() -> { String to = Updater.restartingTo(); d.jobs.stopping(to == null ? Jobs.RESTART : Jobs.UPDATE, to); }, "serve-stopping");
        Runtime.getRuntime().addShutdownHook(d.stopping);
        s.createContext("/", x -> d.handle(new PerRequest(x)));   // each request's marks are its own: the server's are shared by every request
        s.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "librarian-http"); t.setDaemon(true); return t; }));
        s.start();
        if (doorPort >= 0) { try { d.openDoor(doorPort); } catch (IOException e) { d.stop(); throw e; } }
        // once per library, before any run: the runs of fields that act only when asked, from before runs were recorded, go into the ledger
        try { Fields.Migrated m = Fields.migrate(store); if (m != null) Crews.log(store, "upgrade", "fields ledger: " + m.fromLog() + " run(s) from the research log, " + m.byCommand() + " filed by a field's own command, " + m.fromQuestions() + " that researched a question a field's own command filed" + (m.notes().isEmpty() ? "" : ". " + String.join(" ", m.notes())), 0); }
        catch (IOException e) { Crews.log(store, "upgrade", "the fields ledger could not be written: " + e.getMessage() + ". researchzosho profile runs upgrade tries again", 0); }
        d.jobs.start();   // re-queues what a previous daemon left behind
        Help.prefetch();   // an installed release fetches its documentation now, off this thread, so the first question finds it kept
        if (crewHour >= 0) {
            d.crews = Crews.nightly(store, crewHour, () -> {
                try { d.jobs.submit("crews", "", M.createObjectNode()); } catch (IOException e) { Crews.log(store, "nightly", "could not start the nightly tasks: " + e, 0); }
            });
            d.crews.start();
        }
        return d;
    }

    public int port() { return server.getAddress().getPort(); }
    /** The shared door's port, or -1 without one. */
    public int doorPort() { HttpServer d = door; return d == null ? -1 : d.getAddress().getPort(); }

    /** Opens the shared door at a port on this machine's loopback (0 = any free one); nothing when it is open. The service's watcher calls this when `share` is on. */
    public synchronized void openDoor(int port) throws IOException {
        if (door != null) return;
        HttpServer d2 = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        d2.createContext("/", x -> { HttpExchange own = new PerRequest(x); own.setAttribute(DOOR, Boolean.TRUE); handle(own); });
        d2.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "librarian-door"); t.setDaemon(true); return t; }));
        d2.start();
        door = d2;
    }

    /** Closes the shared door; nothing when it is closed. */
    public synchronized void closeDoor() { HttpServer d = door; if (d == null) return; door = null; d.stop(0); }
    /** Whether the shared door is open: the library is reached from outside, and its agent card is served ({@link A2a}). */
    public boolean doorOpen() { return door != null; }

    private volatile A2a a2a;
    A2a a2a() { A2a a = a2a; if (a == null) a2a = a = new A2a(this, store); return a; }

    /** The library as an agent (A2A): its card by GET, its calls by POST, both only while the door is open. A token the library refuses is said by the status. */
    private void a2a(HttpExchange x, String path, String method, String bodyText) throws IOException {
        if (A2a.CARD.equals(path)) {
            if (!"GET".equals(method)) { x.getResponseHeaders().set("Allow", "GET"); x.sendResponseHeaders(405, -1); x.close(); return; }
            A2a.Out o = a2a().card(); reply(x, o.status(), o.body()); return;
        }
        if (!"POST".equals(method)) { x.getResponseHeaders().set("Allow", "POST"); x.sendResponseHeaders(405, -1); x.close(); return; }
        JsonNode req;
        try { req = bodyText.isBlank() ? M.createObjectNode() : M.readTree(bodyText); }
        catch (JacksonException e) { A2a.Out o = A2a.parseError(); reply(x, o.status(), o.body()); return; }
        Patrons.Patron patron;
        try { patron = patron(x, M.createObjectNode()); }
        catch (ProtocolError e) { A2a.Out o = A2a.forbidden(e.getMessage()); reply(x, o.status(), o.body()); return; }
        A2a.Out o = a2a().rpc(req, patron); reply(x, o.status(), o.body());
    }
    public String url() { return "http://" + server.getAddress().getHostString() + ":" + port(); }
    private Lease lease;
    /** The hook that writes why the service stopped, for the jobs it leaves running; a daemon stopped in the program (a test) takes it away. */
    private Thread stopping;
    public void stop() { if (stopping != null) { try { Runtime.getRuntime().removeShutdownHook(stopping); } catch (IllegalStateException exiting) { } } if (crews != null) crews.interrupt(); if (stallWatch != null) stallWatch.interrupt(); jobs.stop(); server.stop(0); if (door != null) door.stop(0); McpConnections.closeFor(store); if (lease != null) lease.close(); }
    public Jobs jobs() { return jobs; }
    public String describeWorkers() {
        return ModelSlots.describe() + "; on " + String.join(", ", jobs.drives().stream().map(d -> d.isEmpty() ? driveNow() : d).toList());
    }
    public void join() throws InterruptedException { Thread.currentThread().join(); }

    // ---- dispatch ----

    /** One POST /v1 route: the protocol's method for the tool of that name, on the request's body (the patron already set). */
    private interface Route { ObjectNode apply(LibraryProtocol p, ObjectNode body) throws IOException; }

    /** A tool that stands for a protocol operation: the route puts the op in, as the MCP tool does. */
    private static Route op(String op, Route under) { return (p, b) -> { ObjectNode a = b.deepCopy(); a.put("op", op); return under.apply(p, a); }; }

    /**
     * The POST /v1/{name} routes: every tool of the protocol by its name without its library_ prefix, served by the protocol's method of
     * the same name; a tool that stands for one operation of a wider call (look, candidates, sift; patents and its readings; records;
     * board) is that call with its op put in. research is the daemon's own route (the drive is probed first), and so are jobs/{id} and
     * crews/run; GET routes are theirs. A test pins this map, the tool list and the protocol document to each other.
     */
    private static final Map<String, Route> V1 = new LinkedHashMap<>();
    /** The routes after which a job filed or released by the call is picked up at once. */
    private static final Set<String> PICKS_UP = Set.of("seeks", "job", "crews");
    static {
        V1.put("ask", LibraryProtocol::ask); V1.put("search", LibraryProtocol::search); V1.put("get", LibraryProtocol::get); V1.put("read", LibraryProtocol::read);
        V1.put("established", LibraryProtocol::established); V1.put("submit", LibraryProtocol::submit); V1.put("add", LibraryProtocol::add); V1.put("translate", LibraryProtocol::translate); V1.put("absorb", LibraryProtocol::absorb);
        V1.put("survey", LibraryProtocol::survey); V1.put("repo", LibraryProtocol::repo); V1.put("items", LibraryProtocol::items); V1.put("check", LibraryProtocol::check);
        V1.put("reading", LibraryProtocol::reading); V1.put("questions", LibraryProtocol::questions); V1.put("bookmarks", LibraryProtocol::bookmarks); V1.put("meeting", LibraryProtocol::meeting);
        V1.put("holdings", LibraryProtocol::holdings); V1.put("who", LibraryProtocol::who); V1.put("db", LibraryProtocol::db); V1.put("remove", LibraryProtocol::remove);
        V1.put("bridges", LibraryProtocol::bridges); V1.put("seeks", LibraryProtocol::seeks); V1.put("frontier", LibraryProtocol::frontier); V1.put("inbox", LibraryProtocol::inbox);
        V1.put("serials", LibraryProtocol::serials); V1.put("subjects", LibraryProtocol::subjects); V1.put("status", LibraryProtocol::status); V1.put("help", LibraryProtocol::help);
        V1.put("changes", LibraryProtocol::changes); V1.put("map", LibraryProtocol::map); V1.put("perspectives", LibraryProtocol::perspectives); V1.put("sharpen", LibraryProtocol::sharpen);
        V1.put("explain", LibraryProtocol::explain); V1.put("request_access", LibraryProtocol::requestAccess); V1.put("access", LibraryProtocol::access);
        V1.put("subscribe", LibraryProtocol::subscribe); V1.put("unsubscribe", LibraryProtocol::unsubscribe);
        V1.put("settings", LibraryProtocol::settings); V1.put("models", LibraryProtocol::models); V1.put("mcp", LibraryProtocol::mcp); V1.put("share", LibraryProtocol::share);
        V1.put("sources", LibraryProtocol::sources); V1.put("owned", LibraryProtocol::owned); V1.put("my_shelf", LibraryProtocol::myShelf); V1.put("prefs", LibraryProtocol::prefs);
        V1.put("dna", LibraryProtocol::dna); V1.put("follow", LibraryProtocol::follow); V1.put("react", LibraryProtocol::react); V1.put("dossier", LibraryProtocol::dossier);
        V1.put("export", LibraryProtocol::export); V1.put("suggest", LibraryProtocol::suggest); V1.put("tell", LibraryProtocol::tell);
        V1.put("video", LibraryProtocol::video); V1.put("look", op("look", LibraryProtocol::video)); V1.put("candidates", op("candidates", LibraryProtocol::video)); V1.put("sift", op("sift", LibraryProtocol::video));
        V1.put("patents", LibraryProtocol::patents); V1.put("patent_abstracts", op("abstracts", LibraryProtocol::patents)); V1.put("patent_claims", op("claims", LibraryProtocol::patents));
        V1.put("home", LibraryProtocol::home); V1.put("records", op("records", LibraryProtocol::home));
        V1.put("career", LibraryProtocol::career); V1.put("board", op("board", LibraryProtocol::career));
        V1.put("job", LibraryProtocol::job); V1.put("crews", LibraryProtocol::crews); V1.put("spend", LibraryProtocol::spend);
        V1.put("images", LibraryProtocol::images);
    }

    /** Every POST /v1 route this daemon serves, as the protocol document lists them: the map's names, then its own three. */
    public static Set<String> v1Routes() {
        Set<String> all = new LinkedHashSet<>(V1.keySet());
        all.add("research"); all.add("jobs/{id}"); all.add("crews/run");
        return Collections.unmodifiableSet(all);
    }

    /** Whether the main port listens on the loopback only. */
    private volatile boolean loopbackOnly;

    private void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String method = x.getRequestMethod();
        if (Proxy.behindTrusted(x)) x.setAttribute(Proxy.PROXIED, Boolean.TRUE);   // from a proxy the setting names: its forwarded headers are believed
        // on the loopback only, a request under another name came from a page elsewhere that pointed its own name here (LocalName)
        if (loopbackOnly && !Boolean.TRUE.equals(x.getAttribute(DOOR)) && !Proxy.proxied(x) && !LocalName.servedHere(x.getRequestHeaders().getFirst("Host"))) { LocalName.refuse(x); return; }
        if ("/healthz".equals(path)) { health(x, method); return; }   // a proxy's or a platform's check: no sign-in, no body
        try {
            byte[] bodyBytes = x.getRequestBody().readAllBytes();
            String contentType = x.getRequestHeaders().getFirst("Content-Type");
            boolean multipart = contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
            String bodyText = multipart ? "" : new String(bodyBytes, StandardCharsets.UTF_8);
            if (A2a.CARD.equals(path) || A2a.PATH.equals(path)) { a2a(x, path, method, bodyText); return; }   // the library as an agent for other agents
            if (path.startsWith("/static/") && "GET".equals(method)) { Pages.staticFile(x, path.substring("/static/".length())); return; }   // a file the program ships for its pages: no sign-in, as the icon
            if (Pages.isPage(path)) {
                // a form posted from a page of another site is refused before it is read, whoever the browser is here (OwnPage)
                if (!OwnPage.allowed(x)) { OwnPage.refuse(x); return; }
                // the library in a browser: HTML in, form fields (not JSON) in, the reader proved by the cookie; a file posted with a form is written to a folder of its own
                Map<String, String> form;
                if (multipart) {
                    if (bodyBytes.length > Uploads.MAX) throw ProtocolError.invalidArgs("The file is larger than the page takes, 64 MB. Add it at the command line on this machine instead.");
                    form = Uploads.multipart(bodyBytes, contentType, Files.createTempDirectory(Uploads.DIR));
                } else form = Pages.form(bodyText, contentType);
                x.setAttribute(BODY, bodyText);   // the passkey calls post JSON, not a form
                Patrons.Patron who;
                try { who = patron(x, M.createObjectNode()); }
                catch (ProtocolError e) {
                    // the browser's cookie carries a token the library refuses now (removed since): forget it and go on as a visitor,
                    // instead of refusing every page until the person finds the sign-out link (a browser stayed "Not allowed", 2026-10-09)
                    String c = Pages.cookie(x, Pages.COOKIE);
                    if (!"forbidden".equals(e.code) || c == null || c.isBlank() || x.getRequestHeaders().getFirst("Authorization") != null) throw e;
                    Pages.clearCookie(x);
                    who = Patrons.Patron.ANONYMOUS;
                }
                if (who.anonymous()) who = Boolean.TRUE.equals(x.getAttribute(DOOR)) ? Patrons.Patron.DOOR_WEB : Patrons.Patron.WEB;   // someone in the browser who has not signed in; Patrons.check decides what that may do
                // a visitor who asked to be let in: once the owner has let them in, their next load signs them in and loads again as them
                if (who.web()) { String ask = Pages.cookie(x, Pages.ASK); if (ask != null && !ask.isBlank() && Pages.collectAsk(x, store, ask)) { Pages.redirectTo(x, x.getRequestURI().toString()); return; } }
                Pages.handle(x, this, store, who, query(x), form);
                return;
            }
            JsonNode parsed = bodyText.isBlank() ? M.createObjectNode() : M.readTree(bodyText);
            if (!parsed.isObject()) throw ProtocolError.invalidArgs("The request body must be a JSON object.");
            ObjectNode body = (ObjectNode) parsed;
            Map<String, String> q = query(x);
            Patrons.Patron patron = patron(x, body);
            body.set("patron", patronNode(patron));
            LibraryProtocol p = new LibraryProtocol(store);
            JsonNode out;
            if ("/rpc".equals(path)) {
                // MCP over Streamable HTTP, the request/response subset: POST a JSON-RPC message, get JSON back;
                // a notification is accepted with 202 and no body; the server-push stream (GET) is not offered (405).
                if (!"POST".equals(method)) { x.getResponseHeaders().set("Allow", "POST"); x.sendResponseHeaders(405, -1); x.close(); return; }
                JsonNode req = M.readTree(bodyText);
                if (req.path("params").path("arguments").isObject()) ((ObjectNode) req.path("params").path("arguments")).set("patron", provenNode(patron));
                // resources carry no arguments: the proven caller rides in the params, over whatever the request named there
                if (req.isObject() && req.path("method").asText("").startsWith("resources/")) {
                    if (!req.path("params").isObject()) ((ObjectNode) req).putObject("params");
                    ((ObjectNode) req.get("params")).set("patron", provenNode(patron));
                }
                ObjectNode env = McpServer.envelopeFor(req, store);
                if (env == null) { x.sendResponseHeaders(202, -1); x.close(); return; }
                reply(x, 200, env);
                return;
            }
            if (("/favicon.ico".equals(path) || "/favicon.png".equals(path)) && "GET".equals(method)) {
                byte[] icon = favicon();
                x.getResponseHeaders().set("Content-Type", "image/png");
                x.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
                x.sendResponseHeaders(200, icon.length);
                try (OutputStream os = x.getResponseBody()) { os.write(icon); }
                return;
            }
            if (path.startsWith("/v1/jobs/") && "POST".equals(method)) {
                // job control by route: the op and the id go to library_job; the daemon then picks up a run the answer queued
                String id = path.substring("/v1/jobs/".length()).strip();
                if (id.isEmpty()) { Patrons.check(store, patron, Patrons.Level.read); throw ProtocolError.notFound("A run is controlled by its id, such as POST /v1/jobs/J-0001 with op stop, pause, resume, choose, allow or decline."); }
                if (body.path("op").asText("").isBlank()) throw ProtocolError.invalidArgs("Say what to do with the run: op is stop, pause, resume, choose, allow or decline.");
                body.put("job_id", id);
                out = p.job(body);
                jobs.pickUp();
            } else if (path.startsWith("/v1/jobs/") && "GET".equals(method)) {
                // as library_job: read access
                String id = path.substring("/v1/jobs/".length()).strip();
                if (id.isEmpty()) {
                    Patrons.check(store, patron, Patrons.Level.read);
                    throw ProtocolError.notFound("A research run is asked for by its id, such as GET /v1/jobs/J-0001. GET /v1/jobs lists the runs.");
                }
                ObjectNode a = M.createObjectNode();
                a.put("job_id", id);
                a.set("patron", patronNode(patron));
                out = p.job(a).path("job");
            } else if ("/v1/jobs".equals(path) && "GET".equals(method)) {
                ObjectNode a = M.createObjectNode();
                if (q.get("limit") != null) a.put("limit", Integer.parseInt(q.get("limit")));
                if (q.get("cursor") != null) a.put("cursor", q.get("cursor"));
                a.set("patron", patronNode(patron));
                ObjectNode r = p.job(a);
                r.put("running", jobs.current() == null ? "" : jobs.current());
                var runningArr = r.putArray("running_ids");
                for (String id : jobs.running()) runningArr.add(id);
                r.put("queued", jobs.queued());
                r.put("workers", jobs.workers());
                out = r;
            } else if ("/v1/research".equals(path) && "POST".equals(method)) {
                out = research(body, patron);
            } else if ("/v1/crews/run".equals(path) && "POST".equals(method)) {
                Patrons.check(store, patron, Patrons.Level.write);
                String id = jobs.submit("crews", patron.did(), M.createObjectNode());
                out = M.createObjectNode().put("job_id", id).put("state", "queued");
            } else if ("/v1/resources".equals(path) && "GET".equals(method)) {
                out = p.resourcesList(q.get("cursor"), patron);
            } else if ("/v1/resources/templates".equals(path) && "GET".equals(method)) {
                out = p.resourceTemplates();
            } else if ("/v1/resource".equals(path) && "GET".equals(method)) {
                out = p.resourcesRead(q.get("uri"), patron);
            } else if ("/v1/status".equals(path) && "GET".equals(method)) {
                out = p.status(body);
            } else if (path.startsWith("/v1/") && "POST".equals(method)) {
                String name = path.substring(4);
                Route route = V1.get(name);
                if (route == null) throw ProtocolError.notFound("route " + path);
                out = route.apply(p, body);
                if (PICKS_UP.contains(name)) jobs.pickUp();   // a job the call may have filed or released is taken up now, not at the next look
            } else {
                throw ProtocolError.notFound("route " + method + " " + path);
            }
            reply(x, 200, out);
        } catch (ProtocolError e) {
            ObjectNode body = error(e.code, e.getMessage());
            if (e.extra != null) ((ObjectNode) body.get("error")).setAll(e.extra.deepCopy());   // a confirm's helplines, beside the message
            reply(x, status(e.code), body);
        } catch (JacksonException e) {
            reply(x, 400, error("invalid_args", "The request body is not JSON."));
        } catch (Declined d) {
            reply(x, status("declined"), error("declined", d.statement()));   // the model declined: its own code, never an outage a client retries
        } catch (Exception e) {
            reply(x, 503, error("unavailable", "The library could not answer: " + e.getMessage()));
        }
    }

    ObjectNode research(ObjectNode body, Patrons.Patron patron) throws IOException { return research(body, patron, LibraryProtocol.Way.PROGRAM); }

    /** {@code way}: where the ask came in (the web page, a program), for the field it names and what it is told. */
    ObjectNode research(ObjectNode body, Patrons.Patron patron, LibraryProtocol.Way way) throws IOException {
        // the protocol files the job; over HTTP we can also say NOW whether a drive answers
        Patrons.check(store, patron, Patrons.Level.write);
        LibraryProtocol.helpBeforeTooShort(body, way);   // a short question about harming oneself gets the help, not "too short"
        LibraryProtocol.validateResearch(body);
        // a run on a named model is asked of that model's own server, by its own model id; otherwise of the library's drives
        String named = body.path("model").asText("").strip();
        if (named.isEmpty() && Auto.isDefault()) named = Auto.NAME;   // nothing named, and there is a choice: best judgement
        // auto: the model best judgement would pick now is the one probed; the pick itself is made again when the run starts
        ModelEntries.Entry entry = named.isEmpty() || named.equalsIgnoreCase(ModelEntries.DEFAULT) ? null : named.equalsIgnoreCase(Auto.NAME) ? Auto.choose(store, Auto.Job.research(body.path("quick").asBoolean(false))).entry() : ModelEntries.get(named);
        if (entry != null && entry.name().equals(ModelEntries.DEFAULT)) entry = null;
        if (!named.isEmpty() && !named.equalsIgnoreCase(ModelEntries.DEFAULT) && !named.equalsIgnoreCase(Auto.NAME) && entry == null) throw ProtocolError.invalidArgs("No model is named " + named + ". The names are: " + String.join(", ", ModelEntries.names()) + " (researchzosho models adds one).");
        if (entry != null) {
            if (Crews.driveState(entry.drive(), entry.model(), Duration.ofSeconds(20)) != Crews.DriveState.ANSWERS) throw ProtocolError.unavailable("No model answers at " + entry.drive() + " for " + entry.name() + " (" + entry.model() + ")." + (entry.hosted() && DriveClient.hostKey(entry.host()) == null ? " No key is kept for " + entry.host() + ": researchzosho models key " + entry.host() + " <key>." : " Research needs one."));
        } else {
            boolean any = false;
            for (String d : jobs.drives()) if (Crews.driveAnswers(d.isEmpty() ? driveNow() : d)) { any = true; break; }
            if (!any) throw ProtocolError.unavailable("No model is answering (" + String.join(", ", jobs.drives()) + "). Research needs one.");
        }
        ObjectNode r = new LibraryProtocol(store).research(body, way);
        jobs.pickUp();
        r.put("queued_ahead", Math.max(0, jobs.queued() - 1) + jobs.running().size());
        r.put("workers", jobs.workers());
        return r;
    }

    /**
     * GET /healthz: 200 with the version and the library's id and name when the library opens and this daemon holds its lease, 503 with
     * the reason when the library cannot be read. No sign-in: a proxy or a platform asks it to know whether to send people here.
     */
    private void health(HttpExchange x, String method) throws IOException {
        if (!"GET".equals(method)) { x.getResponseHeaders().set("Allow", "GET"); x.sendResponseHeaders(405, -1); x.close(); return; }
        ObjectNode o = M.createObjectNode();
        o.put("version", Version.string());
        try {
            LibraryStore.Identity id = store.identity();
            o.put("ok", true); o.put("library_id", id.id()); o.put("library_name", id.name());
            reply(x, 200, o);
        } catch (IOException | RuntimeException e) {
            o.put("ok", false); o.put("why", "The library could not be read: " + e.getMessage());
            reply(x, 503, o);
        }
    }

    // ---- identity ----

    private Patrons.Patron patron(HttpExchange x, ObjectNode body) throws IOException {
        String auth = x.getRequestHeaders().getFirst("Authorization");
        if (auth == null) { String c = Pages.cookie(x, Pages.COOKIE); if (c != null && !c.isBlank()) auth = "Bearer " + c; }   // a browser signed in on /login
        JsonNode claimed = body.get("patron");
        // over /rpc the client's patron rides inside the JSON-RPC envelope, in params.arguments; read it from there
        // too, or the runtime it names is lost and the circulation log says "via http" (Wyrdsekai, 2026-09-08)
        if ((claimed == null || !claimed.isObject()) && body.path("params").path("arguments").path("patron").isObject()) {
            claimed = body.path("params").path("arguments").path("patron");
        }
        String runtime = claimed != null && claimed.isObject() ? claimed.path("runtime").asText("") : "";
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) && auth.substring(7).strip().startsWith(ApiKeys.PREFIX)) {
            // a key a signed-in person made for a program of theirs: it acts as that person, a read key reading only, whatever the client claims
            ApiKeys.Key k = ApiKeys.resolve(store, auth.substring(7).strip());
            if (k == null) throw ProtocolError.forbidden("That key does not match any the library made, or it was revoked. The person it belonged to makes a new one on the Keep page or with researchzosho keys new.");
            String name;
            if (Accounts.OWNER.equals(k.did())) { Accounts.Account a = Accounts.owner(store); name = a == null ? "the person" : a.name(); }
            else {
                Patrons.Entry listed = Patrons.byDid(store, k.did());
                if (listed == null || listed.level() == Patrons.Level.deny) throw ProtocolError.forbidden("The person this key belongs to is no longer a reader of this library. Ask the person who keeps it.");
                name = listed.name();
            }
            return new Patrons.Patron(k.did(), name, k.reads() ? Patrons.KEY_READ : Patrons.KEY);
        }
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            Patrons.Entry e = Patrons.resolve(store, auth.substring(7));
            if (e == null) {
                // not a program's token: a browser signed in to an account (the cookie holds the session's secret)
                Accounts.Account a = Accounts.bySession(store, auth.substring(7));
                if (a != null) {
                    if (!a.owner()) {
                        Patrons.Entry listed = Patrons.byDid(store, a.did());
                        if (listed == null || listed.level() == Patrons.Level.deny) throw ProtocolError.forbidden("This account is no longer a reader of this library. Ask the person who keeps it.");
                    }
                    return new Patrons.Patron(a.did(), a.name(), runtime.isEmpty() ? (Boolean.TRUE.equals(x.getAttribute(DOOR)) ? "door" : Proxy.proxied(x) ? "proxy" : "web") : runtime);
                }
                throw ProtocolError.forbidden("That token does not match any user. The library owner makes tokens with `researchzosho reader token <did>`.");
            }
            if (claimed != null && claimed.isObject() && !claimed.path("did").asText("").isBlank()
                    && !claimed.path("did").asText().equals(e.did())) {
                throw ProtocolError.forbidden("The token belongs to " + e.did() + ", not to the did in the request.");
            }
            return new Patrons.Patron(e.did(), e.name(), runtime.isEmpty() ? "http" : runtime);
        }
        if (Boolean.TRUE.equals(x.getAttribute(DOOR)) || Proxy.proxied(x)) {
            // through the shared door a relay says who the caller is: tailscale serve adds the tailnet login and name, a cloudflared quick
            // tunnel with a mail list the address Cloudflare Access checked. A listed reader with that login is signed in; anyone else is a
            // visitor, who may ask to be let in with their name filled in. On the main port these headers mean nothing, unless the request
            // came from a proxy RESEARCHZOSHO_TRUSTED_PROXY names, which is the door's place in the cloud.
            String login = x.getRequestHeaders().getFirst("Tailscale-User-Login"), name = x.getRequestHeaders().getFirst("Tailscale-User-Name");
            if (login == null || login.isBlank()) { login = x.getRequestHeaders().getFirst("Cf-Access-Authenticated-User-Email"); name = null; }
            if (login != null && !login.isBlank()) {
                x.setAttribute(IDENTITY_LOGIN, login.strip()); x.setAttribute(IDENTITY_NAME, name == null || name.isBlank() ? login.strip() : name.strip());
                Patrons.Entry e = Patrons.byDid(store, Patrons.loginDid(login));
                if (e != null && e.level() != Patrons.Level.deny) return new Patrons.Patron(e.did(), e.name().isEmpty() ? login.strip() : e.name(), runtime.isEmpty() ? (Boolean.TRUE.equals(x.getAttribute(DOOR)) ? "door" : "proxy") : runtime);
            }
        }
        if (claimed != null && claimed.isObject() && !claimed.path("did").asText("").isBlank()) {
            throw ProtocolError.forbidden("A did sent over http needs a bearer token (Authorization: Bearer …). Leave out the patron to call anonymously.");
        }
        // a browser on the library's own computer, on the main port, by one of this computer's names, is the person who keeps it (the setting
        // web.local; never through the door, and never through a proxy: a proxy on this machine connects from this machine for everyone it forwards)
        if (!Boolean.TRUE.equals(x.getAttribute(DOOR)) && !Proxy.proxied(x) && Pages.isPage(x.getRequestURI().getPath()) && WebAccess.signInRequired() && WebAccess.localOwner()
                && LocalName.thisComputer(x.getRequestHeaders().getFirst("Host"))   // asked by this computer's own name, never one a page elsewhere pointed here
                && x.getRemoteAddress() != null && x.getRemoteAddress().getAddress() != null && x.getRemoteAddress().getAddress().isLoopbackAddress()) return Patrons.Patron.OWNER_WEB;
        return Boolean.TRUE.equals(x.getAttribute(DOOR)) ? Patrons.Patron.DOOR_ANONYMOUS : Patrons.Patron.ANONYMOUS;
    }

    /** The same, marked as proved by this daemon (a key, a session, a reader's token), which lets the keeper's own did through the MCP route. */
    static ObjectNode provenNode(Patrons.Patron p) { ObjectNode o = patronNode(p); if (!p.anonymous()) o.put(McpServer.PROVEN, true); return o; }

    static ObjectNode patronNode(Patrons.Patron p) {
        ObjectNode o = M.createObjectNode();
        if (p.anonymous()) { if (p.door()) o.put("runtime", "door"); return o; }   // the door's mark rides along: nobody unlisted gets in there
        o.put("did", p.did()); o.put("name", p.name()); o.put("runtime", p.runtime());
        return o;
    }

    // ---- plumbing ----

    static int status(String code) {
        return switch (code) {
            case "not_found" -> 404;
            case "forbidden" -> 403;
            case "no_sources", "declined", "confirm" -> 422;   // declined: the model's answer, which a retry would only ask for again; confirm: the person's yes, which a retry does not give
            case "invalid_args" -> 400;
            case "budget_exceeded" -> 429;
            default -> 503;
        };
    }

    private static ObjectNode error(String code, String message) {
        ObjectNode e = M.createObjectNode();
        ObjectNode inner = e.putObject("error");
        inner.put("code", code);
        inner.put("message", message);
        return e;
    }

    private static Map<String, String> query(HttpExchange x) {
        Map<String, String> m = new HashMap<>();
        String q = x.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static void reply(HttpExchange x, int status, JsonNode body) throws IOException {
        byte[] bytes = M.writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
    }
}
