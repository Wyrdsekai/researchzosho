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

    /** A worker's dispatch, on its own drive: research asks and crews runs. */
    private String runJob(ObjectNode job, String drive) {
        String kind = job.path("kind").asText();
        ObjectNode a = (ObjectNode) job.path("args");
        String d = drive == null || drive.isEmpty() ? driveUrl : drive;
        switch (kind) {
            case "research" -> {
                if (!Crews.driveAnswers(d)) throw new IllegalStateException("no model drive answers at " + d + " — the ask was filed but cannot run; ask again when a drive is up");
                String writer = job.path("patron").asText("").isEmpty() ? "patron:anonymous" : "patron:" + job.path("patron").asText();
                try {
                    return runResearch(job, a, d, writer);
                } catch (IOException e) {
                    throw new IllegalStateException("the shelf could not be written: " + e.getMessage(), e);
                }
            }
            case "crews" -> {
                var steps = Crews.runAll(store, d, model);
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
        java.util.List<String> subs = new java.util.ArrayList<>();
        for (JsonNode s : a.path("sub_questions")) if (s.isTextual() && !s.asText().isBlank()) subs.add(s.asText());
        java.util.List<String> colls = new java.util.ArrayList<>();
        for (JsonNode c : a.path("collections")) if (c.isTextual()) colls.add(c.asText());
        var ask = new Researcher.Ask(question, a.path("mode").asText("broad"), a.path("max_turns").asInt(LibraryProtocol.DEFAULT_TURNS), subs, a.path("sources").asText("both"), colls, a.path("max_minutes").asInt(0));
        long t0 = System.currentTimeMillis();
        Researcher researcher = researcher(drive);
        researcher.stopWhen(() -> jobs.stopRequested(jobId));
        Researcher.Filed filed;
        try { filed = Researcher.file(store, researcher, ask, writer); }
        catch (Researcher.Stopped s) { return "stopped at turn"; }   // the worker marks the job stopped from the marker
        try { jobs.recordTurns(job.path("patron").asText(""), filed.result().turnsUsed()); } catch (IOException ignored) { }
        if (filed.admitted()) settle(filed.investigationId(), drive, jobId);
        Crews.log(store, "research " + jobId, filed.result().summary() + (filed.admitted() ? " → " + filed.investigationId() : " → refused: " + filed.reason()),
                System.currentTimeMillis() - t0);
        return (filed.admitted() ? "investigation " + filed.investigationId()
                : "(refused at intake — " + filed.reason() + "; see the frontier)") + "\n\n" + filed.result().summary();
    }

    /** How often the service looks for changes the vault has not seen; a test shortens it. */
    static volatile long VAULT_FOLLOW_MS = org.researchzosho.Config.getInt("RESEARCHZOSHO_VAULT_FOLLOW_SECONDS", 10) * 1000L;

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
            var client = new org.researchzosho.drive.DriveClient(drive, model);
            var idx = new LibrarianIndex(store);
            // each step is model calls of up to RESEARCHZOSHO_DRIVE_TIMEOUT each; a stop asked meanwhile ends the settling at
            // the next step, and the nightly housekeeping does the rest (a stopped run once sat here for minutes, J-0024)
            var out = new LibrarianReview(store, idx, LibrarianReview.driveJudge(client), "librarian:" + model).searcher(LibrarianReview.liveSearcher()).review(inv);
            if (settleStopped(jobId, investigationId, "review", t0)) return;
            var cat = Cataloger.run(store, Cataloger.driveJudge(client), false);   // subjects from the vocabulary; new ones are proposals for the person
            if (settleStopped(jobId, investigationId, "subjects", t0)) return;
            var triples = Triples.fill(store, Triples.driveExtractor(client), 40);
            if (settleStopped(jobId, investigationId, "triples", t0)) return;
            var ret = Retractions.check(store, Retractions.live(), 40, java.time.LocalDate.now());   // a retracted source disputes the claim, no model involved
            if (settleStopped(jobId, investigationId, "retractions", t0)) return;
            var abs = Abstracts.run(store, Abstracts.driveWriter(client), null);   // only a subject whose shelf changed is rewritten (hash-guarded)
            Crews.log(store, "settle " + jobId, investigationId + ": " + out.accepted().size() + " claim(s) accepted, " + out.disputed().size() + " disputed, "
                    + out.keptDraft().size() + " kept as draft; subjects on " + cat.grounded() + " (" + cat.proposals() + " proposed); triples " + triples.filled() + "/" + triples.asked()
                    + "; retractions " + ret.retracted() + "/" + ret.checked() + " checked; summaries " + abs.written() + " rewritten"
                    + (out.problems().isEmpty() ? "" : "; review problems: " + out.problems().size() + " (see the review lines above)"), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            Crews.log(store, "settle " + jobId, investigationId + ": could not settle now (" + e.getMessage() + "); the housekeeping will", System.currentTimeMillis() - t0);
        }
    }

    /** True, and logged, when a stop was asked for the job while it was settling: the steps done stay done, the rest waits for the housekeeping. */
    private boolean settleStopped(String jobId, String investigationId, String after, long t0) {
        if (!jobs.stopRequested(jobId)) return false;
        Crews.log(store, "settle " + jobId, investigationId + ": stopped by the person after " + after + "; the housekeeping does the rest", System.currentTimeMillis() - t0);
        return true;
    }

    /** The kura, 64 px, for the tab: the mark's small form, bundled so the daemon needs no static files. */
    static byte[] favicon() {
        try (java.io.InputStream in = LibrarianDaemon.class.getResourceAsStream("favicon.png")) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** The runner for one drive. A test replaces this (package-private) to script the model. */
    java.util.function.Function<String, Researcher> researcherFactory;

    private Researcher researcher(String drive) {
        if (researcherFactory != null) return researcherFactory.apply(drive);
        return new Researcher(Researcher.drive(drive, model), Researcher.judgeDrive(drive, model), Researcher.webTools(), line -> Crews.log(store, "research", line, 0), store);
    }

    /** Bind and start. {@code port} 0 = ephemeral (tests). {@code crewHour} < 0 = no nightly crews. */
    public static LibrarianDaemon start(LibraryStore store, String host, int port, String driveUrl, String model, int crewHour) throws IOException {
        Lease lease = Lease.acquire(store);   // one daemon per library; refused with the holder's pid otherwise
        HttpServer s;
        try { s = HttpServer.create(new InetSocketAddress(host, port), 0); } catch (IOException e) { lease.close(); throw e; }
        // The Librarian's endpoint serves THE LIBRARY: a patron must not reach `fix` or `secure` through it
        // (seen 2026-09-03 — a Claude Code session registered against /rpc listed every codezaiku tool).
        org.researchzosho.mcp.McpServer.setToolFilter(n -> n.startsWith("library_"));
        LibrarianDaemon d = new LibrarianDaemon(store, s, driveUrl, model);
        d.lease = lease;
        d.followVault();
        Runtime.getRuntime().addShutdownHook(new Thread(lease::close, "serve-lease-release"));   // systemd stops us with SIGTERM
        s.createContext("/", d::handle);
        s.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "librarian-http"); t.setDaemon(true); return t; }));
        s.start();
        d.jobs.start();   // re-queues what a previous daemon left behind
        if (crewHour >= 0) {
            d.crews = Crews.nightly(store, crewHour, () -> {
                try { d.jobs.submit("crews", "", M.createObjectNode()); } catch (IOException e) { Crews.log(store, "nightly", "could not file the crews job: " + e, 0); }
            }, () -> { try { return d.jobs.active().isEmpty(); } catch (Exception e) { return false; } });   // the auto-update waits for an idle daemon
            d.crews.start();
        }
        return d;
    }

    public int port() { return server.getAddress().getPort(); }
    public String url() { return "http://" + server.getAddress().getHostString() + ":" + port(); }
    private Lease lease;
    public void stop() { if (crews != null) crews.interrupt(); jobs.stop(); server.stop(0); if (lease != null) lease.close(); }
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
                if (who.anonymous() && !WebAccess.signInRequired()) who = Patrons.Patron.WEB;   // the pages are open, as shipped
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
                ObjectNode env = org.researchzosho.mcp.McpServer.envelopeFor(req, store);
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
                out = job(path.substring("/v1/jobs/".length()));
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
                Patrons.check(store, patron, Patrons.Level.read);
                out = p.resourcesList(q.get("cursor"));
            } else if ("/v1/resources/templates".equals(path) && "GET".equals(method)) {
                out = p.resourceTemplates();
            } else if ("/v1/resource".equals(path) && "GET".equals(method)) {
                Patrons.check(store, patron, Patrons.Level.read);
                out = p.resourcesRead(q.get("uri"));
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
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            reply(x, 400, error("invalid_args", "The request body is not JSON."));
        } catch (Exception e) {
            reply(x, 503, error("unavailable", "The library could not answer: " + e.getMessage()));
        }
    }

    ObjectNode research(ObjectNode body, Patrons.Patron patron) throws IOException {
        // the protocol files the job; over HTTP we can also say NOW whether a drive answers
        Patrons.check(store, patron, Patrons.Level.write);
        LibraryProtocol.validateResearch(body);
        boolean any = false;
        for (String d : jobs.drives()) if (Crews.driveAnswers(d.isEmpty() ? driveUrl : d)) { any = true; break; }
        if (!any) throw ProtocolError.unavailable("No model drive answers (" + String.join(", ", jobs.drives()) + "); research needs one.");
        ObjectNode r = new LibraryProtocol(store).research(body);
        jobs.pickUp();
        r.put("queued_ahead", Math.max(0, jobs.queued() - 1) + jobs.running().size());
        r.put("workers", jobs.workers());
        return r;
    }

    private ObjectNode job(String id) throws IOException {
        ObjectNode j = jobs.get(id);
        if (j == null) throw ProtocolError.notFound("job " + id);
        return Jobs.view(j);
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
            if (e == null) throw ProtocolError.forbidden("That bearer token proves no listed patron; the person who keeps the library issues tokens with `researchzosho reader token <did>`.");
            if (claimed != null && claimed.isObject() && !claimed.path("did").asText("").isBlank()
                    && !claimed.path("did").asText().equals(e.did())) {
                throw ProtocolError.forbidden("The token proves " + e.did() + ", not the did the request names.");
            }
            return new Patrons.Patron(e.did(), e.name(), runtime.isEmpty() ? "http" : runtime);
        }
        if (claimed != null && claimed.isObject() && !claimed.path("did").asText("").isBlank()) {
            throw ProtocolError.forbidden("A did over http must be proved with a bearer token (Authorization: Bearer …); omit the patron to call anonymously.");
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
            case "no_sources" -> 422;
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
