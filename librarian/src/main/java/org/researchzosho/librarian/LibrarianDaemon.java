package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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
import org.researchzosho.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.researchzosho.Stopping;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.mcp.McpServer;
import org.researchzosho.drive.Declined;
/**
 * The Librarian as a long-running process: the library protocol over HTTP (the transport the
 * SDKs speak), bearer tokens that PROVE a patron's did, overnight research jobs, and the crews.
 *
 * <p>Routes (JSON in, JSON out; see docs/LIBRARY_PROTOCOL.md §6):
 * <pre>
 *   POST /v1/{ask|search|get|read|established|submit|frontier|subjects|status}   body = the tool's arguments
 *   GET  /v1/status
 *   GET  /v1/resources?cursor=…   GET /v1/resources/templates   GET /v1/resource?uri=…
 *   POST /v1/research {question, mode?, max_turns?}  →  {job_id, state: queued}  (write; a research run)
 *   GET  /v1/jobs/{id}   GET /v1/jobs                                         (the ledger; one model worker)
 *   POST /v1/crews/run  →  {job_id}                                          (write)
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
    private final String model;
    private final Jobs jobs;
    private Thread crews;

    private LibrarianDaemon(LibraryStore store, HttpServer server, String driveUrl, String model) {
        this.store = store; this.server = server; this.driveUrl = driveUrl; this.model = model;
        this.jobs = new Jobs(store, this::runJob, Jobs.drives(driveUrl), Jobs.WORKERS);
    }

    /**
     * A worker's dispatch, on its own drive: research asks and crews runs. A research run works under its stop ({@link Stopping}): when a
     * person asks for it, a request to the model, a page fetch or a search in flight ends within a second, its connection closed. The
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

    private String dispatch(ObjectNode job, String drive) {
        String kind = job.path("kind").asText();
        ObjectNode a = (ObjectNode) job.path("args");
        String d = drive == null || drive.isEmpty() ? driveUrl : drive;
        switch (kind) {
            case "research" -> {
                if (!Crews.driveAnswers(d)) throw new IllegalStateException("No model is answering at " + d + ". The question was saved but cannot run. Send it again when a model is up.");
                String writer = job.path("patron").asText("").isEmpty() ? "patron:anonymous" : "patron:" + job.path("patron").asText();
                try {
                    return runResearch(job, a, d, writer);
                } catch (IOException e) {
                    throw new IllegalStateException("The library could not be written: " + e.getMessage(), e);
                }
            }
            case "crews" -> {
                String crewId = job.path("job_id").asText("");
                var steps = Crews.runAll(store, d, model, () -> !crewId.isEmpty() && jobs.stopRequested(crewId));
                StringBuilder sb = new StringBuilder();
                for (var s : steps) sb.append(s.name()).append(": ").append(s.outcome()).append(" (").append(s.ms()).append("ms)\n");
                return sb.toString();
            }
            default -> throw new IllegalArgumentException("unknown job kind " + kind);
        }
    }

    /**
     * A research run, on the library's OWN runner ({@link Researcher}): plan from the brief's
     * sub-questions, parallel workers, the critic, the synthesis — then the acquisitions gate and the
     * shelf. The run's evidence goes into the investigation with the answer, so a cut-off synthesis
     * still leaves the work on record. Progress lines land in catalog/crews.log under the job id.
     */
    private String runResearch(ObjectNode job, ObjectNode a, String drive, String writer) throws IOException {
        String jobId = job.path("job_id").asText("job");
        String question = a.path("question").asText();
        List<String> subs = new ArrayList<>();
        for (JsonNode s : a.path("sub_questions")) if (s.isTextual() && !s.asText().isBlank()) subs.add(s.asText());
        List<String> colls = new ArrayList<>();
        for (JsonNode c : a.path("collections")) if (c.isTextual()) colls.add(c.asText());
        var ask = new Researcher.Ask(question, a.path("mode").asText("broad"), a.path("max_turns").asInt(LibraryProtocol.DEFAULT_TURNS), subs, a.path("sources").asText("both"), colls, a.path("max_minutes").asInt(0), LibraryProtocol.fieldsOf(a), LibraryProtocol.allowOf(a));
        // the question looked like a field's that joins only when asked, and nobody asked: the run is ordinary, and the log says what was suggested where it was filed
        if (a.path("suggested").isObject()) Crews.log(store, "research " + jobId, "suggestion: " + a.path("suggested").path("field").asText() + " — " + a.path("suggested").path("offer").asText() + " The run is ordinary research.", 0);
        long t0 = System.currentTimeMillis();
        RunTrace trace = RunTrace.open(store, jobId);
        Researcher researcher = researcher(drive, trace);
        // a run has a model: the pages the person gave that wait for their check are checked first, a few at a time
        try {
            var later = UncheckedPages.recheck(store, ContentJudge.of(new DriveClient(drive, model)), UncheckedPages.PER_COMMAND);
            if (!later.sentence().isEmpty()) Crews.log(store, "research " + jobId, later.sentence(), 0);
        } catch (Exception ignored) { }
        String asker = job.path("patron").asText("");
        researcher.allowDatabases(asker.equals("person") || (asker.equals("web") && !WebAccess.signInRequired()));   // the owner's databases are for the owner's questions
        researcher.stopWhen(() -> jobs.stopRequested(jobId));
        researcher.beginFiling(() -> { try { return jobs.beginFiling(jobId); } catch (IOException e) { return !jobs.stopRequested(jobId); } });
        researcher.onProgress(p -> { try { jobs.progress(jobId, p); } catch (IOException ignored) { } });
        Researcher.Filed filed;
        try { filed = Researcher.file(store, researcher, ask, writer, jobId, a.path("field_how").asText("asked"), a.path("allow_how").asText("asked")); }
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
        // the ledger row: what this run cost and produced, beside every earlier run's
        ObjectNode row = RunLedger.row(jobId, ask, filed.result(), filed.admitted() ? "filed " + filed.investigationId() : filed.declined() ? "declined by the model" : "refused: " + filed.reason(),
                System.currentTimeMillis() - t0, drive, model, trace.totals());
        row.put("fetches_total", RunLedger.fetchCalls(store, jobId));
        RunLedger.record(store, row);
        if (filed.admitted()) settle(filed.investigationId(), drive, jobId);
        Crews.log(store, "research " + jobId, filed.result().summary() + (filed.admitted() ? " → " + filed.investigationId() : filed.declined() ? " → declined by the model" : " → refused: " + filed.reason()),
                System.currentTimeMillis() - t0);
        // the model declined the run: the statement is the result, not a refusal and not a failure of the machine
        if (filed.declined()) return filed.reason();
        return (filed.admitted() ? "investigation " + filed.investigationId()
                : "(not accepted — " + filed.reason() + "; see the open questions)") + "\n\n" + filed.result().summary()
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
     * (Measured: the Tokyo Vice write-ups sat a day with no claims, so `ask` and the map knew nothing about Tokyo.)
     */
    private void settle(String investigationId, String drive, String jobId) {
        long t0 = System.currentTimeMillis();
        try {
            Investigation inv = store.investigation(investigationId);
            if (inv == null) return;
            var client = new DriveClient(drive, model);
            var idx = new LibrarianIndex(store);
            // each step is model calls of up to RESEARCHZOSHO_DRIVE_TIMEOUT each; a stop asked meanwhile gives up the call in flight and
            // ends the settling at the next step, and the nightly housekeeping does the rest (a stopped run once sat here for minutes, J-0024)
            var out = new LibrarianReview(store, idx, LibrarianReview.driveJudge(client), "librarian:" + model).searcher(LibrarianReview.liveSearcher()).review(inv);
            if (settleStopped(jobId, investigationId, "review", t0)) return;
            var cat = Cataloger.run(store, Cataloger.driveJudge(client), false);   // subjects from the vocabulary; new ones are proposals for the person
            if (settleStopped(jobId, investigationId, "subjects", t0)) return;
            var triples = Triples.fill(store, Triples.driveExtractor(client), 40);
            if (settleStopped(jobId, investigationId, "triples", t0)) return;
            var ret = Retractions.check(store, Retractions.live(), 40, LocalDate.now());   // a retracted source disputes the claim, no model involved
            if (settleStopped(jobId, investigationId, "retractions", t0)) return;
            var abs = Abstracts.run(store, Abstracts.driveWriter(client), null);   // only a subject whose shelf changed is rewritten (hash-guarded)
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

    private Researcher researcher(String drive) { return researcher(drive, null); }

    /** The seat the Librarian talks from: the judge drive when one is set, else the workers' drive. */
    Researcher.Drive chatDrive() { return Researcher.watchedChat(driveUrl, model); }   // watched: when the model declines, the chat says so

    /** The runner for a job, its drives seen through the job's trace when there is one. */
    private Researcher researcher(String drive, RunTrace trace) {
        if (researcherFactory != null) { Researcher r = researcherFactory.apply(drive); if (trace != null) r.trace(trace); return r; }
        // both seats watched for a model that declines: it is said, never worked around (see Declines)
        Researcher.Drive workers = Researcher.watched(drive, model, "workers", trace), judge = Researcher.watchedJudge(drive, model, trace);
        if (trace != null) { workers = trace.wrap(workers, "workers"); judge = trace.wrap(judge, "judge"); }
        Researcher r = new Researcher(workers, judge, Researcher.webTools(), line -> Crews.log(store, "research", line, 0), store);
        r.contentJudge(ContentJudge.of(new DriveClient(drive, model)));   // the run's own model checks the pages it fetches
        if (trace != null) r.trace(trace);
        return r;
    }

    /** Bind and start. {@code port} 0 = ephemeral (tests). {@code crewHour} < 0 = no nightly crews. */
    public static LibrarianDaemon start(LibraryStore store, String host, int port, String driveUrl, String model, int crewHour) throws IOException {
        Lease lease = Lease.acquire(store);   // one daemon per library; refused with the holder's pid otherwise
        HttpServer s;
        try { s = HttpServer.create(new InetSocketAddress(host, port), 0); } catch (IOException e) { lease.close(); throw e; }
        // The Librarian's endpoint serves THE LIBRARY: a patron must not reach `fix` or `secure` through it
        // (seen 2026-09-03 — a Claude Code session registered against /rpc listed every codezaiku tool).
        McpServer.setToolFilter(n -> n.startsWith("library_"));
        LibrarianDaemon d = new LibrarianDaemon(store, s, driveUrl, model);
        d.lease = lease;
        d.followVault();
        d.watchStalls();
        Runtime.getRuntime().addShutdownHook(new Thread(lease::close, "serve-lease-release"));   // systemd stops us with SIGTERM
        s.createContext("/", d::handle);
        s.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "librarian-http"); t.setDaemon(true); return t; }));
        s.start();
        // once per library, before any run: the runs of fields that act only when asked, from before runs were recorded, go into the ledger
        try { Fields.Migrated m = Fields.migrate(store); if (m != null) Crews.log(store, "upgrade", "fields ledger: " + m.fromLog() + " run(s) from the research log, " + m.byCommand() + " filed by a field's own command, " + m.fromQuestions() + " that researched a question a field's own command filed" + (m.notes().isEmpty() ? "" : ". " + String.join(" ", m.notes())), 0); }
        catch (IOException e) { Crews.log(store, "upgrade", "the fields ledger could not be written: " + e.getMessage() + ". researchzosho profile runs upgrade tries again", 0); }
        d.jobs.start();   // re-queues what a previous daemon left behind
        if (crewHour >= 0) {
            d.crews = Crews.nightly(store, crewHour, () -> {
                try { d.jobs.submit("crews", "", M.createObjectNode()); } catch (IOException e) { Crews.log(store, "nightly", "could not start the nightly tasks: " + e, 0); }
            }, () -> { try { return d.jobs.active().isEmpty(); } catch (Exception e) { return false; } });   // the auto-update waits for an idle daemon
            d.crews.start();
        }
        return d;
    }

    public int port() { return server.getAddress().getPort(); }
    public String url() { return "http://" + server.getAddress().getHostString() + ":" + port(); }
    private Lease lease;
    public void stop() { if (crews != null) crews.interrupt(); if (stallWatch != null) stallWatch.interrupt(); jobs.stop(); server.stop(0); if (lease != null) lease.close(); }
    public Jobs jobs() { return jobs; }
    public String describeWorkers() {
        return jobs.workers() + " worker(s) on " + String.join(", ", jobs.drives().stream().map(d -> d.isEmpty() ? driveUrl : d).toList());
    }
    public void join() throws InterruptedException { Thread.currentThread().join(); }

    // ---- dispatch ----

    private void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String method = x.getRequestMethod();
        try {
            String bodyText = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (Pages.isPage(path)) {
                // the library in a browser: HTML in, form fields (not JSON) in, the reader proved by the cookie
                Map<String, String> form = Pages.form(bodyText, x.getRequestHeaders().getFirst("Content-Type"));
                Patrons.Patron who = patron(x, M.createObjectNode());
                if (who.anonymous()) who = Patrons.Patron.WEB;   // someone in the browser who has not signed in; Patrons.check decides what that may do
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
                if (req.path("params").path("arguments").isObject()) ((ObjectNode) req.path("params").path("arguments")).set("patron", patronNode(patron));
                // resources carry no arguments: the proven caller rides in the params, over whatever the request named there
                if (req.isObject() && req.path("method").asText("").startsWith("resources/")) {
                    if (!req.path("params").isObject()) ((ObjectNode) req).putObject("params");
                    ((ObjectNode) req.get("params")).set("patron", patronNode(patron));
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
            if (path.startsWith("/v1/jobs/") && "GET".equals(method)) {
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
                out = switch (path.substring(4)) {
                    case "ask" -> p.ask(body);
                    case "search" -> p.search(body);
                    case "get" -> p.get(body);
                    case "read" -> p.read(body);
                    case "established" -> p.established(body);
                    case "submit" -> p.submit(body);
                    case "add" -> p.add(body);
                    case "absorb" -> p.absorb(body);
                    case "survey" -> p.survey(body);
                    case "repo" -> p.repo(body);
                    case "items" -> p.items(body);
                    case "check" -> p.check(body);
                    case "reading" -> p.reading(body);
                    case "questions" -> p.questions(body);
                    case "bookmarks" -> p.bookmarks(body);
                    case "meeting" -> p.meeting(body);
                    case "holdings" -> p.holdings(body);
                    case "db" -> p.db(body);
                    case "remove" -> p.remove(body);
                    case "bridges" -> p.bridges(body);
                    case "frontier" -> p.frontier(body);
                    case "subjects" -> p.subjects(body);
                    case "status" -> p.status(body);
                    case "changes" -> p.changes(body);
                    case "request_access" -> p.requestAccess(body);
                    case "access" -> p.access(body);
                    case "subscribe" -> p.subscribe(body);
                    case "unsubscribe" -> p.unsubscribe(body);
                    case "map" -> p.map(body);
                    case "perspectives" -> p.perspectives(body);
                    case "sharpen" -> p.sharpen(body);
                    case "explain" -> p.explain(body);
                    case "serials" -> p.serials(body);
                    case "inbox" -> p.inbox(body);
                    default -> throw ProtocolError.notFound("route " + path);
                };
            } else {
                throw ProtocolError.notFound("route " + method + " " + path);
            }
            reply(x, 200, out);
        } catch (ProtocolError e) {
            reply(x, status(e.code), error(e.code, e.getMessage()));
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
        boolean any = false;
        for (String d : jobs.drives()) if (Crews.driveAnswers(d.isEmpty() ? driveUrl : d)) { any = true; break; }
        if (!any) throw ProtocolError.unavailable("No model is answering (" + String.join(", ", jobs.drives()) + "). Research needs one.");
        ObjectNode r = new LibraryProtocol(store).research(body, way);
        jobs.pickUp();
        r.put("queued_ahead", Math.max(0, jobs.queued() - 1) + jobs.running().size());
        r.put("workers", jobs.workers());
        return r;
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
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            Patrons.Entry e = Patrons.resolve(store, auth.substring(7));
            if (e == null) throw ProtocolError.forbidden("That token does not match any user. The library owner makes tokens with `researchzosho reader token <did>`.");
            if (claimed != null && claimed.isObject() && !claimed.path("did").asText("").isBlank()
                    && !claimed.path("did").asText().equals(e.did())) {
                throw ProtocolError.forbidden("The token belongs to " + e.did() + ", not to the did in the request.");
            }
            return new Patrons.Patron(e.did(), e.name(), runtime.isEmpty() ? "http" : runtime);
        }
        if (claimed != null && claimed.isObject() && !claimed.path("did").asText("").isBlank()) {
            throw ProtocolError.forbidden("A did sent over http needs a bearer token (Authorization: Bearer …). Leave out the patron to call anonymously.");
        }
        return Patrons.Patron.ANONYMOUS;
    }

    static ObjectNode patronNode(Patrons.Patron p) {
        ObjectNode o = M.createObjectNode();
        if (p.anonymous()) return o;
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
