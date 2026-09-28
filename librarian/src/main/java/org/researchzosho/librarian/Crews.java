package org.researchzosho.librarian;

import static org.researchzosho.librarian.Researcher.forExplorer;

import org.researchzosho.librarian.Researcher.CannotCheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.aws.Bedrock;
import org.slf4j.LoggerFactory;
import org.researchzosho.drive.Declined;
/**
 * The background crews — what librarians do when the desk is closed. Owned by the daemon
 * (the operator, 2026-09-03: "daemon owns the crews — a lot of the asks may need overnight runs"),
 * run nightly at a set hour or on demand, one step after another, each logged to
 * {@code catalog/crews.log} with its outcome and duration. A step that fails is logged and
 * the next step still runs: a dead drive must not stop the serials check.
 *
 * <p>Steps, in order: serials (living shelves, overdue reviews) · review-hash migration ·
 * explorer (the demand loop: research the open [demand]/[gap] questions, a few per night) ·
 * review (the librarian's pass over draft investigations — extractions from auto-promoting
 * source tiers become findings; syntheses stay drafts for the person; added after the first
 * overnight ask landed as an investigation with no findings, 2026-09-03) · inventory (shelf
 * reading: a few accepted findings re-read against their own captured sources) · abstracts (shelf
 * articles whose findings changed — the shelf hash decides) · enrichment (generated contexts
 * for raw chunks that have none) · heat (fold usage into the ranking table) · [weekly: duplicates
 * report · orphans report] · refresh (incremental re-index of what changed; a FULL rebuild on the
 * 1st of the month, re-embedding everything) · backup (a dated zip, a week kept). The model steps
 * need a drive and are skipped, logged, when none answers. Three cadences after kiroku-memory.
 */
public final class Crews {

    private Crews() { }

    public record Step(String name, String outcome, long ms) { }

    /** How the explorer researches one open question, or a bundle of related ones: returns the investigation id, or null when refused. */
    public interface Researcher {
        String research(String question, String writer) throws Exception;
        /** A bundle: one run whose workers take {@code subQuestions} (the head question first); by default the head alone. */
        default String research(String question, List<String> subQuestions, String writer) throws Exception { return research(question, writer); }
        /** The same as a run of {@code field}, the field whose open questions these are ({@link Fields#ofLine}); "" for ordinary questions. */
        default String research(String question, List<String> subQuestions, String writer, String field) throws Exception { return research(question, subQuestions, writer); }
    }

    /** The real one: the library's own runner on the drive, submitted through the acquisitions gate. */
    public static Researcher driveResearcher(LibraryStore store, String driveUrl, String model, int maxTurns) {
        return new Researcher() {
            @Override public String research(String question, String writer) throws Exception { return research(question, List.of(), writer); }
            @Override public String research(String question, List<String> subQuestions, String writer) throws Exception { return research(question, subQuestions, writer, ""); }
            @Override public String research(String question, List<String> subQuestions, String writer, String field) throws Exception {
                return forExplorer(store, driveUrl, model, question, subQuestions, maxTurns, explorerMinutes(), field, writer, line -> log(store, "explorer", line, 0));
            }
        };
    }

    /** Open questions (bundles) researched per night, read when the run starts so a change applies tonight; 0 turns the explorer off. */
    public static int explorerPerNight() { return Config.getInt("RESEARCHZOSHO_EXPLORER_PER_NIGHT", 2); }
    /** A one-night override: {@code explorer.tonight} in the config file, used once and cleared. 0 = none. */
    public static int explorerTonight() { return Config.getInt("explorer.tonight", 0); }
    /** What the next run will take: the override when set, else the standing number. */
    public static int explorerBudget() { int t = explorerTonight(); return t > 0 ? t : explorerPerNight(); }
    static void clearTonight() { try { if (explorerTonight() > 0) Config.set("explorer.tonight", "0"); } catch (Exception ignored) { } }
    static final int EXPLORER_PER_NIGHT = explorerPerNight();
    static final int EXPLORER_TURNS = Config.getInt("RESEARCHZOSHO_EXPLORER_TURNS", 30);
    /** A wall-clock ceiling per explorer run in minutes; 0 = none. */
    public static int explorerMinutes() { return Config.getInt("RESEARCHZOSHO_EXPLORER_MINUTES", 0); }
    /** The most questions one bundle carries: the head and up to this many related ones (the runner takes 8 sub-questions). */
    static final int BUNDLE_MAX = 8;

    /** Run every crew once, now. Returns what each step did. */
    public static List<Step> runAll(LibraryStore store, String driveUrl, String model) {
        return runAll(store, driveUrl, model, driveResearcher(store, driveUrl, model, EXPLORER_TURNS), explorerBudget());
    }

    /** As above, with {@code stop} read by the long steps: a stopped crews job ends at its next chunk, not its next night. */
    public static List<Step> runAll(LibraryStore store, String driveUrl, String model, BooleanSupplier stop) {
        STOP.set(stop);
        try { return runAll(store, driveUrl, model); } finally { STOP.remove(); }
    }
    private static final ThreadLocal<BooleanSupplier> STOP = new ThreadLocal<>();
    static BooleanSupplier stop() { BooleanSupplier s = STOP.get(); return s == null ? () -> false : s; }

    /** Which extra cadences tonight carries: weekly on {@code RESEARCHZOSHO_CREWS_WEEKLY_DAY} (7 = Sunday), monthly on day 1. */
    public record Cadence(boolean weekly, boolean monthly) {
        public static Cadence tonight(LocalDate d) {
            int weeklyDay = Config.getInt("RESEARCHZOSHO_CREWS_WEEKLY_DAY", 7);
            return new Cadence(d.getDayOfWeek().getValue() == weeklyDay, d.getDayOfMonth() == 1);
        }
    }

    public static List<Step> runAll(LibraryStore store, String driveUrl, String model, Researcher researcher, int explorePerNight) {
        return runAll(store, driveUrl, model, researcher, explorePerNight, Cadence.tonight(LocalDate.now()));
    }

    public static List<Step> runAll(LibraryStore store, String driveUrl, String model, Researcher researcher, int explorePerNight, Cadence cadence) {
        List<Step> steps = new ArrayList<>();
        boolean drive = driveAnswers(driveUrl);
        steps.add(step(store, "collections", () -> Corpus.rescan(store)));
        steps.add(step(store, "serials", () -> { Serials.check(store); return "checked"; }));
        steps.add(step(store, "preprints", () -> {
            var o = Preprints.check(store, Preprints.live(), Preprints.PER_NIGHT, LocalDate.now());
            return o.checked() + " checked, " + o.revised() + " revised" + (o.notes().isEmpty() ? "" : " (" + String.join("; ", o.notes()) + ")");
        }));
        steps.add(step(store, "retractions", () -> {
            var o = Retractions.check(store, Retractions.live(), Retractions.PER_NIGHT, LocalDate.now());
            return o.checked() + " DOI(s) checked, " + o.retracted() + " retracted, " + o.concerns() + " concern(s)" + (o.notes().isEmpty() ? "" : " (" + String.join("; ", o.notes()) + ")");
        }));
        steps.add(step(store, "migrate-review-hashes", () -> store.migrateReviewHashes() + " carried"));
        if (drive) {
            // the pages the person gave that were saved before a model could check them: checked now, and removed when the check finds them
            steps.add(step(store, "page-checks", () -> {
                var o = UncheckedPages.recheck(store, ContentJudge.of(new DriveClient(driveUrl, model)), 500);
                return o.checked() + " saved page(s) checked, " + o.removed().size() + " removed, " + o.waiting() + " still waiting" + (o.sentence().isEmpty() ? "" : ". " + o.sentence());
            }));
            steps.add(step(store, "explorer", () -> explore(store, researcher, explorePerNight)));
            steps.add(step(store, "review", () -> reviewDrafts(store, new LibrarianReview(store, new LibrarianIndex(store),
                    LibrarianReview.driveJudge(new DriveClient(driveUrl, model)), "librarian:" + model).searcher(LibrarianReview.liveSearcher()))));
            steps.add(step(store, "catalog", () -> {
                var o = Cataloger.run(store, Cataloger.driveJudge(new DriveClient(driveUrl, model)), false);
                return o.grounded() + " claim(s) filed under subjects, " + o.proposals() + " new subject(s) proposed" + (o.problems().isEmpty() ? "" : "; " + o.problems().size() + " problem(s)");
            }));
            steps.add(step(store, "triples", () -> {
                var o = Triples.fill(store, Triples.driveExtractor(new DriveClient(driveUrl, model)), Triples.PER_NIGHT);
                return o.asked() + " asked, " + o.filled() + " filled";
            }));
            steps.add(step(store, "concepts", () -> {
                var o = Concepts.fill(store, Concepts.driveExtractor(new DriveClient(driveUrl, model)), Concepts.PER_NIGHT);
                return o.asked() + " asked, " + o.filled() + " filled";
            }));
            steps.add(step(store, "inventory", () -> {
                var checks = Inventory.run(store, Inventory.driveChecker(new DriveClient(driveUrl, model)), Inventory.PER_NIGHT);
                StringBuilder sb = new StringBuilder(checks.size() + " checked:");
                for (var c : checks) sb.append(' ').append(c.id()).append('=').append(c.verdict());
                return sb.toString();
            }));
            steps.add(step(store, "abstracts", () -> {
                var o = Abstracts.run(store, Abstracts.driveWriter(new DriveClient(driveUrl, model)), List.of());
                return o.written() + " written, " + o.unchanged() + " unchanged" + (o.problems().isEmpty() ? "" : "; problems: " + String.join(" | ", o.problems()));
            }));
            steps.add(step(store, "enrich", () -> {
                var o = Enrichment.run(store, Enrichment.driveContextualizer(new DriveClient(driveUrl, model)), Enrichment.PER_NIGHT, stop());
                return o.chunksGenerated() + " context(s) across " + o.files() + " file(s) (up to " + Enrichment.PER_NIGHT + " a night)" + (o.problems().stream().anyMatch(x -> x.startsWith("stopped")) ? "; stopped" : "");
            }));
        } else {
            String why = "skipped — no drive answers at " + (driveUrl == null ? "(unset)" : driveUrl);
            for (String name : new String[]{"page-checks", "explorer", "review", "catalog", "triples", "inventory", "abstracts", "enrich"}) {
                steps.add(new Step(name, why, 0));
                log(store, name, why, 0);
            }
        }
        steps.add(step(store, "graph", () -> Graph.propose(store)));
        if (drive) steps.add(step(store, "bridges", () -> Bridges.nightly(store, driveUrl, model)));
        steps.add(step(store, "vault", () -> Vault.refresh(store)));
        steps.add(step(store, "heat", () -> Heat.fold(store, Heat.DAYS) + " entr(ies) with uses in the last " + Heat.DAYS + " days"));
        if (cadence.weekly()) {
            steps.add(step(store, "duplicates", () -> Reports.duplicates(store)));
            steps.add(step(store, "orphans", () -> Reports.orphans(store)));
        }
        steps.add(step(store, cadence.monthly() ? "rebuild" : "refresh", () -> {
            String blocker = LibrarianIndex.rebuildBlocker(Embeddings.configured());
            if (blocker != null) return "skipped — " + blocker;
            LibrarianIndex idx = new LibrarianIndex(store);
            // monthly: everything re-embedded; nightly: only what changed since the last stamp
            int n = cadence.monthly() ? idx.rebuild() : idx.refresh();
            store.regenerateIndex();
            return n + (cadence.monthly() ? " entries indexed (full, monthly)" : " changed entr(ies) re-indexed (incremental)");
        }));
        steps.add(step(store, "backup", () -> "wrote " + Backup.run(store, Backup.dir(store), Backup.KEEP) + " (keeping " + Backup.KEEP + ")"));
        return steps;
    }

    /**
     * The explorer: research the oldest open [demand]/[gap] questions, at most {@code perNight},
     * and mark each explored with what it produced. Today's gap is tomorrow's shelf.
     */
    /** A bundle: the head question and the related open questions that ride along in the same run. */
    public record Bundle(Frontier.Line head, List<Frontier.Line> more, String field) {
        public Bundle(Frontier.Line head, List<Frontier.Line> more) { this(head, more, ""); }
        public List<Frontier.Line> all() { List<Frontier.Line> l = new ArrayList<>(); l.add(head); l.addAll(more); return l; }
        /** The run's question: the head, without the note a report appended. */
        public String question() { return Frontier.strip(head.text()); }
        public List<String> subQuestions() { List<String> s = new ArrayList<>(); for (Frontier.Line l : all()) s.add(Frontier.strip(l.text())); return s; }
    }

    /**
     * The explorer's plan for {@code perNight} runs: the queue in file order, each head gathering the related questions
     * behind it (the same report left them open, or their terms overlap), up to {@link #BUNDLE_MAX} in a run. Bundled
     * questions leave the queue with their head, so one run answers several instead of each getting a thin one.
     */
    public static List<Bundle> plan(List<Frontier.Line> open, int perNight) { return plan(open, perNight, l -> ""); }

    /** As above; {@code fieldOf} tells each question's field, and a bundle holds the questions of one field only. */
    public static List<Bundle> plan(List<Frontier.Line> open, int perNight, Function<Frontier.Line, String> fieldOf) {
        List<Bundle> out = new ArrayList<>();
        List<Frontier.Line> left = new ArrayList<>(open);
        while (!left.isEmpty() && out.size() < perNight) {
            Frontier.Line head = left.remove(0);
            String field = fieldOf.apply(head);
            List<Frontier.Line> more = new ArrayList<>();
            for (var it = left.iterator(); it.hasNext() && more.size() < BUNDLE_MAX - 1; ) {
                Frontier.Line l = it.next();
                if (Frontier.related(head, l) && fieldOf.apply(l).equals(field)) { more.add(l); it.remove(); }
            }
            out.add(new Bundle(head, more, field));
        }
        return out;
    }

    static String explore(LibraryStore store, Researcher researcher, int perNight) throws Exception {
        try {
            if (perNight <= 0) return "off (RESEARCHZOSHO_EXPLORER_PER_NIGHT=0)";
            List<Frontier.Line> open = new ArrayList<>();
            for (Frontier.Line l : Frontier.read(store)) if (l.researchable()) open.add(l);
            if (open.isEmpty()) return "nothing open on the frontier";
            var runs = Fields.runs(store);
            List<Bundle> bundles = plan(open, perNight, l -> Fields.ofLine(l, runs));
            int done = 0, admitted = 0, questions = 0;
            StringBuilder sb = new StringBuilder();
            for (Bundle b : bundles) {
                String id;
                // a question a field filed is researched as that field's; an ordinary one that looks like a field's is ordinary, and the log says so
                if (b.field().isEmpty()) { Fields.Suggestion s = Fields.logged(store, b.question(), "explorer"); if (s != null) log(store, "explorer", "suggestion: " + s.field() + " — " + s.offer() + " The run is ordinary research, because the question was not filed by the " + s.field() + " command.", 0); }
                String fromSurvey = Surveys.runQuestionOf(store, b.head());   // a survey's direction runs as the survey's own run of it: it reads the surveyed thing
                String question = fromSurvey == null ? b.question() : fromSurvey;
                boolean declined = false, notTonight = false;
                try { id = b.field().isEmpty() && b.more().isEmpty() ? researcher.research(question, "crew:explorer") : researcher.research(question, b.more().isEmpty() ? List.of() : b.subQuestions(), "crew:explorer", b.field()); }
                catch (Declined d) { id = null; declined = true; sb.append(" [").append(Acquisitions.compress(b.question(), 40)).append(": ").append(d.statement()).append("]"); }
                catch (ContentOffer.NotStarted n) { id = null; notTonight = true; sb.append(" [a question was not researched: it reads as a person asking about harming themselves, and it is researched only when they ask for it]"); }
                catch (CannotCheck c) {
                    // the check cannot run on this server tonight: the question stays open, and so do the rest
                    sb.append(" [").append(c.getMessage()).append("]");
                    break;
                }
                catch (Exception e) { id = null; sb.append(" [").append(Acquisitions.compress(b.question(), 40)).append(": ").append(e.getMessage()).append("]"); }
                // a question the model declined is marked so, and the explorer does not take it again; so is one that is not researched at night
                for (Frontier.Line l : b.all()) Frontier.markExplored(store, l.text(), id != null ? id : declined ? "(declined by the model)" : notTonight ? "(not researched at night: it is researched only when the person asks for it)" : "(refused at intake)");
                done++; questions += b.all().size();
                if (id != null) { admitted++; sb.append(' ').append(id).append(b.more().isEmpty() ? "" : " (" + b.all().size() + " questions in one run)"); }
            }
            int stillOpen = open.size() - questions;
            return done + " run(s) for " + questions + " question(s), " + admitted + " admitted:" + sb + (stillOpen > 0 ? " (" + stillOpen + " still open)" : "");
        } finally {
            clearTonight();   // a one-night override is spent whether the run went well or not
        }
    }

    interface Work { String run() throws Exception; }

    private static Step step(LibraryStore store, String name, Work work) {
        long t0 = System.currentTimeMillis();
        String outcome;
        try { outcome = work.run(); }
        catch (Exception e) { outcome = "FAILED: " + e; }
        long ms = System.currentTimeMillis() - t0;
        log(store, name, outcome, ms);
        return new Step(name, outcome, ms);
    }

    /** The nightly review of every draft report, what it did in a line. A draft the model declined to review, unchanged since, is left. */
    static String reviewDrafts(LibraryStore store, LibrarianReview review) throws Exception {
        int reviewed = 0, accepted = 0, disputed = 0, leftDeclined = 0;
        try (var files = Files.list(store.investigationsDir())) {
            for (var p : files.sorted().toList()) {
                if (!p.toString().endsWith(".md")) continue;
                Investigation inv = Investigation.parse(Files.readString(p, StandardCharsets.UTF_8));
                if (inv.state() != Finding.State.draft) continue;
                // the model declined to review this report before, and the report has not changed: it is not sent again
                if (Declines.declinedBefore(store, "review", inv.id(), Conversations.hash8(inv.body()))) { leftDeclined++; continue; }
                var out = review.review(inv);
                reviewed++; accepted += out.accepted().size(); disputed += out.disputed().size();
            }
        }
        return reviewed + " draft investigation(s) reviewed: " + accepted + " finding(s) accepted, " + disputed + " disputed"
                + (leftDeclined == 0 ? "" : "; " + leftDeclined + " left as drafts because " + Declines.notAskedAgain("to review them"));
    }

    static void log(LibraryStore store, String name, String outcome, long ms) {
        try {
            var f = store.root().resolve("catalog").resolve("crews.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f, Instant.now() + "\t" + name + "\t" + ms + "ms\t" + outcome.replaceAll("\\s+", " ") + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the log is telemetry; it never breaks a crew
        }
    }

    /** The probe's client, one for the process: a client per probe leaked a selector thread every 30 s. */
    private static final HttpClient PROBE_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** What a probe of a drive found: it answered a completion; it is reachable but still loading; or nothing is there. */
    enum DriveState { ANSWERS, STARTING, DOWN }

    /** A functional probe, never a port check: llama.cpp answers 503 while loading. */
    static boolean driveAnswers(String driveUrl) { return driveState(driveUrl) == DriveState.ANSWERS; }

    /**
     * The probe names the model: a bare llama-server ignores the field, but a router (llama-swap, Ollama)
     * routes by it and answers 404 to a request that names none — which read as "does not answer" until 0.1.8.
     * {@code local-model} is the alias {@code model install} writes, so an install that set no model still routes.
     * A connection that succeeds but times out on the body, or a 503, is a server loading its model: STARTING.
     */
    static DriveState driveState(String driveUrl) {
        return driveState(driveUrl, Config.get("RESEARCHZOSHO_MODEL", "local-model"), Duration.ofSeconds(20));
    }

    static DriveState driveState(String driveUrl, String model, Duration timeout) {
        if (driveUrl == null || driveUrl.isBlank()) return DriveState.DOWN;
        if (Bedrock.is(driveUrl)) return bedrockState(driveUrl, model, timeout);
        try {
            var client = PROBE_HTTP;
            var body = new ObjectMapper().createObjectNode();
            body.put("model", model == null || model.isBlank() ? "local-model" : model);
            body.putArray("messages").addObject().put("role", "user").put("content", "hi");
            body.put("max_tokens", 1);
            var req = HttpRequest.newBuilder(URI.create(driveUrl.replaceAll("/+$", "") + "/v1/chat/completions"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            var res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200 && res.body().contains("\"choices\"")) return DriveState.ANSWERS;
            return res.statusCode() == 503 ? DriveState.STARTING : DriveState.DOWN;
        } catch (HttpTimeoutException e) {
            return e instanceof HttpConnectTimeoutException ? DriveState.DOWN : DriveState.STARTING;
        } catch (Exception e) {
            return DriveState.DOWN;
        }
    }

    /** Bedrock is asked for one token, and a yes is remembered for five minutes: each question there is on somebody's AWS bill. */
    private static volatile long bedrockAnsweredAt = 0;
    private static volatile String bedrockAnsweredFor = "";

    private static DriveState bedrockState(String driveUrl, String model, Duration timeout) {
        String key = driveUrl + "\t" + model;
        if (key.equals(bedrockAnsweredFor) && System.currentTimeMillis() - bedrockAnsweredAt < 300_000) return DriveState.ANSWERS;
        try {
            var bedrock = new Bedrock(Bedrock.settings(driveUrl, Config::get, System.getenv()));
            var body = new ObjectMapper().createObjectNode();
            body.putArray("messages").addObject().put("role", "user").put("content", "hi");
            body.put("max_tokens", 1);
            bedrock.chat(model, body, timeout);
            bedrockAnsweredFor = key; bedrockAnsweredAt = System.currentTimeMillis();
            return DriveState.ANSWERS;
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(Crews.class).warn("bedrock does not answer: {}", e.getMessage());
            return DriveState.DOWN;
        }
    }

    /** One phrase for the status line. */
    static String driveLine(String driveUrl) {
        return switch (driveState(driveUrl)) {
            case ANSWERS -> " answers";
            case STARTING -> " is starting (reachable, still loading its model) — research and the model crews start when it answers";
            case DOWN -> " does not answer — research and the model crews wait for it";
        };
    }

    /** Milliseconds until the next occurrence of {@code hour}:00 local time. */
    /** How long the scheduler waits after a failure before trying again, so nothing can spin. */
    static final long AFTER_FAILURE = 5L * 60 * 1000;

    static long millisUntil(int hour, ZonedDateTime now) {
        ZonedDateTime next = now.withHour(Math.floorMod(hour, 24)).withMinute(0).withSecond(0).withNano(0);   // 24 = midnight, not an exception
        if (!next.isAfter(now)) next = next.plusDays(1);
        return Duration.between(now, next).toMillis();
    }

    /** The nightly scheduler thread; daemon, so it never keeps a JVM alive. {@code fire} runs (or enqueues) the crews. */
    public static Thread nightly(LibraryStore store, int hour, Runnable fire) { return nightly(store, hour, fire, () -> true); }

    /** As above; {@code idle} says whether no run is active, which is when the auto-update may swap the program. */
    public static Thread nightly(LibraryStore store, int hour, Runnable fire, BooleanSupplier idle) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(millisUntil(hour, ZonedDateTime.now()));
                    log(store, "nightly", "begin (" + LocalDateTime.now().withNano(0) + ")", 0);
                    Service.rotate(Config.home().resolve("logs").resolve("librarian-serve.log"), 20L * 1024 * 1024);
                    fire.run();
                    // the quiet moment: the housekeeping is done; in auto mode a newer release is swapped in and the service restarts
                    try { Updater.maybeAuto(store, idle.getAsBoolean()); } catch (Throwable e) { log(store, "update", "FAILED: " + e, 0); }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    // never come straight back: a failure before the sleep (a bad hour once) turned this into a hot loop
                    // that wrote 58 GB to crews.log in half an hour (2026-09-15)
                    log(store, "nightly", "FAILED: " + e + "; the next try is in " + (AFTER_FAILURE / 60000) + " minutes", 0);
                    try { Thread.sleep(AFTER_FAILURE); } catch (InterruptedException ie) { return; }
                }
            }
        }, "librarian-crews");
        t.setDaemon(true);
        return t;
    }
}
