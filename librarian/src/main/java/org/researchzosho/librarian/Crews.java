package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

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
    }

    /** The real one: the library's own runner on the drive, submitted through the acquisitions gate. */
    public static Researcher driveResearcher(LibraryStore store, String driveUrl, String model, int maxTurns) {
        return new Researcher() {
            @Override public String research(String question, String writer) throws Exception { return research(question, List.of(), writer); }
            @Override public String research(String question, List<String> subQuestions, String writer) throws Exception {
                var runner = new org.researchzosho.librarian.Researcher(
                        org.researchzosho.librarian.Researcher.drive(driveUrl, model),
                        org.researchzosho.librarian.Researcher.judgeDrive(driveUrl, model),
                        org.researchzosho.librarian.Researcher.webTools(), line -> log(store, "explorer", line, 0), store);
                var ask = new org.researchzosho.librarian.Researcher.Ask(question, "broad", maxTurns, subQuestions, "both", List.of(), explorerMinutes());
                return org.researchzosho.librarian.Researcher.file(store, runner, ask, writer).investigationId();
            }
        };
    }

    /** Open questions (bundles) researched per night, read when the run starts so a change applies tonight; 0 turns the explorer off. */
    public static int explorerPerNight() { return org.researchzosho.Config.getInt("RESEARCHZOSHO_EXPLORER_PER_NIGHT", 2); }
    /** A one-night override: {@code explorer.tonight} in the config file, used once and cleared. 0 = none. */
    public static int explorerTonight() { return org.researchzosho.Config.getInt("explorer.tonight", 0); }
    /** What the next run will take: the override when set, else the standing number. */
    public static int explorerBudget() { int t = explorerTonight(); return t > 0 ? t : explorerPerNight(); }
    static void clearTonight() { try { if (explorerTonight() > 0) org.researchzosho.Config.set("explorer.tonight", "0"); } catch (Exception ignored) { } }
    static final int EXPLORER_PER_NIGHT = explorerPerNight();
    static final int EXPLORER_TURNS = org.researchzosho.Config.getInt("RESEARCHZOSHO_EXPLORER_TURNS", 30);
    /** A wall-clock ceiling per explorer run in minutes; 0 = none. */
    public static int explorerMinutes() { return org.researchzosho.Config.getInt("RESEARCHZOSHO_EXPLORER_MINUTES", 0); }
    /** The most questions one bundle carries: the head and up to this many related ones (the runner takes 8 sub-questions). */
    static final int BUNDLE_MAX = 8;

    /** Run every crew once, now. Returns what each step did. */
    public static List<Step> runAll(LibraryStore store, String driveUrl, String model) {
        return runAll(store, driveUrl, model, driveResearcher(store, driveUrl, model, EXPLORER_TURNS), explorerBudget());
    }

    /** Which extra cadences tonight carries: weekly on {@code RESEARCHZOSHO_CREWS_WEEKLY_DAY} (7 = Sunday), monthly on day 1. */
    public record Cadence(boolean weekly, boolean monthly) {
        public static Cadence tonight(java.time.LocalDate d) {
            int weeklyDay = org.researchzosho.Config.getInt("RESEARCHZOSHO_CREWS_WEEKLY_DAY", 7);
            return new Cadence(d.getDayOfWeek().getValue() == weeklyDay, d.getDayOfMonth() == 1);
        }
    }

    public static List<Step> runAll(LibraryStore store, String driveUrl, String model, Researcher researcher, int explorePerNight) {
        return runAll(store, driveUrl, model, researcher, explorePerNight, Cadence.tonight(java.time.LocalDate.now()));
    }

    public static List<Step> runAll(LibraryStore store, String driveUrl, String model, Researcher researcher, int explorePerNight, Cadence cadence) {
        List<Step> steps = new ArrayList<>();
        boolean drive = driveAnswers(driveUrl);
        steps.add(step(store, "collections", () -> Corpus.rescan(store)));
        steps.add(step(store, "serials", () -> { Serials.check(store); return "checked"; }));
        steps.add(step(store, "preprints", () -> {
            var o = Preprints.check(store, Preprints.live(), Preprints.PER_NIGHT, java.time.LocalDate.now());
            return o.checked() + " checked, " + o.revised() + " revised" + (o.notes().isEmpty() ? "" : " (" + String.join("; ", o.notes()) + ")");
        }));
        steps.add(step(store, "retractions", () -> {
            var o = Retractions.check(store, Retractions.live(), Retractions.PER_NIGHT, java.time.LocalDate.now());
            return o.checked() + " DOI(s) checked, " + o.retracted() + " retracted, " + o.concerns() + " concern(s)" + (o.notes().isEmpty() ? "" : " (" + String.join("; ", o.notes()) + ")");
        }));
        steps.add(step(store, "migrate-review-hashes", () -> store.migrateReviewHashes() + " carried"));
        if (drive) {
            steps.add(step(store, "explorer", () -> explore(store, researcher, explorePerNight)));
            steps.add(step(store, "review", () -> {
                var idx = new LibrarianIndex(store);
                var review = new LibrarianReview(store, idx,
                        LibrarianReview.driveJudge(new org.researchzosho.drive.DriveClient(driveUrl, model)), "librarian:" + model).searcher(LibrarianReview.liveSearcher());
                int reviewed = 0, accepted = 0, disputed = 0;
                try (var files = java.nio.file.Files.list(store.investigationsDir())) {
                    for (var p : files.sorted().toList()) {
                        if (!p.toString().endsWith(".md")) continue;
                        Investigation inv = Investigation.parse(java.nio.file.Files.readString(p, StandardCharsets.UTF_8));
                        if (inv.state() != Finding.State.draft) continue;
                        var out = review.review(inv);
                        reviewed++; accepted += out.accepted().size(); disputed += out.disputed().size();
                    }
                }
                return reviewed + " draft investigation(s) reviewed: " + accepted + " finding(s) accepted, " + disputed + " disputed";
            }));
            steps.add(step(store, "catalog", () -> {
                var o = Cataloger.run(store, Cataloger.driveJudge(new org.researchzosho.drive.DriveClient(driveUrl, model)), false);
                return o.grounded() + " claim(s) filed under subjects, " + o.proposals() + " new subject(s) proposed" + (o.problems().isEmpty() ? "" : "; " + o.problems().size() + " problem(s)");
            }));
            steps.add(step(store, "triples", () -> {
                var o = Triples.fill(store, Triples.driveExtractor(new org.researchzosho.drive.DriveClient(driveUrl, model)), Triples.PER_NIGHT);
                return o.asked() + " asked, " + o.filled() + " filled";
            }));
            steps.add(step(store, "inventory", () -> {
                var checks = Inventory.run(store, Inventory.driveChecker(new org.researchzosho.drive.DriveClient(driveUrl, model)), Inventory.PER_NIGHT);
                StringBuilder sb = new StringBuilder(checks.size() + " checked:");
                for (var c : checks) sb.append(' ').append(c.id()).append('=').append(c.verdict());
                return sb.toString();
            }));
            steps.add(step(store, "abstracts", () -> {
                var o = Abstracts.run(store, Abstracts.driveWriter(new org.researchzosho.drive.DriveClient(driveUrl, model)), List.of());
                return o.written() + " written, " + o.unchanged() + " unchanged" + (o.problems().isEmpty() ? "" : "; problems: " + String.join(" | ", o.problems()));
            }));
            steps.add(step(store, "enrich", () -> {
                var o = Enrichment.run(store, Enrichment.driveContextualizer(new org.researchzosho.drive.DriveClient(driveUrl, model)), 0);
                return o.chunksGenerated() + " context(s) across " + o.files() + " file(s)";
            }));
        } else {
            String why = "skipped — no drive answers at " + (driveUrl == null ? "(unset)" : driveUrl);
            for (String name : new String[]{"explorer", "review", "catalog", "triples", "inventory", "abstracts", "enrich"}) {
                steps.add(new Step(name, why, 0));
                log(store, name, why, 0);
            }
        }
        steps.add(step(store, "graph", () -> Graph.propose(store)));
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
    public record Bundle(Frontier.Line head, List<Frontier.Line> more) {
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
    public static List<Bundle> plan(List<Frontier.Line> open, int perNight) {
        List<Bundle> out = new ArrayList<>();
        List<Frontier.Line> left = new ArrayList<>(open);
        while (!left.isEmpty() && out.size() < perNight) {
            Frontier.Line head = left.remove(0);
            List<Frontier.Line> more = new ArrayList<>();
            for (var it = left.iterator(); it.hasNext() && more.size() < BUNDLE_MAX - 1; ) {
                Frontier.Line l = it.next();
                if (Frontier.related(head, l)) { more.add(l); it.remove(); }
            }
            out.add(new Bundle(head, more));
        }
        return out;
    }

    static String explore(LibraryStore store, Researcher researcher, int perNight) throws Exception {
        try {
            if (perNight <= 0) return "off (RESEARCHZOSHO_EXPLORER_PER_NIGHT=0)";
            List<Frontier.Line> open = new ArrayList<>();
            for (Frontier.Line l : Frontier.read(store)) if (l.researchable()) open.add(l);
            if (open.isEmpty()) return "nothing open on the frontier";
            List<Bundle> bundles = plan(open, perNight);
            int done = 0, admitted = 0, questions = 0;
            StringBuilder sb = new StringBuilder();
            for (Bundle b : bundles) {
                String id;
                try { id = b.more().isEmpty() ? researcher.research(b.question(), "crew:explorer") : researcher.research(b.question(), b.subQuestions(), "crew:explorer"); }
                catch (Exception e) { id = null; sb.append(" [").append(Acquisitions.compress(b.question(), 40)).append(": ").append(e.getMessage()).append("]"); }
                for (Frontier.Line l : b.all()) Frontier.markExplored(store, l.text(), id == null ? "(refused at intake)" : id);
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
        return driveState(driveUrl, org.researchzosho.Config.get("RESEARCHZOSHO_MODEL", "local-model"), java.time.Duration.ofSeconds(20));
    }

    static DriveState driveState(String driveUrl, String model, java.time.Duration timeout) {
        if (driveUrl == null || driveUrl.isBlank()) return DriveState.DOWN;
        try {
            var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
            var body = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            body.put("model", model == null || model.isBlank() ? "local-model" : model);
            body.putArray("messages").addObject().put("role", "user").put("content", "hi");
            body.put("max_tokens", 1);
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(driveUrl.replaceAll("/+$", "") + "/v1/chat/completions"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            var res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200 && res.body().contains("\"choices\"")) return DriveState.ANSWERS;
            return res.statusCode() == 503 ? DriveState.STARTING : DriveState.DOWN;
        } catch (java.net.http.HttpTimeoutException e) {
            return e instanceof java.net.http.HttpConnectTimeoutException ? DriveState.DOWN : DriveState.STARTING;
        } catch (Exception e) {
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
    static long millisUntil(int hour, ZonedDateTime now) {
        ZonedDateTime next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return java.time.Duration.between(now, next).toMillis();
    }

    /** The nightly scheduler thread; daemon, so it never keeps a JVM alive. {@code fire} runs (or enqueues) the crews. */
    public static Thread nightly(LibraryStore store, int hour, Runnable fire) { return nightly(store, hour, fire, () -> true); }

    /** As above; {@code idle} says whether no run is active, which is when the auto-update may swap the program. */
    public static Thread nightly(LibraryStore store, int hour, Runnable fire, java.util.function.BooleanSupplier idle) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(millisUntil(hour, ZonedDateTime.now()));
                    log(store, "nightly", "begin (" + LocalDateTime.now().withNano(0) + ")", 0);
                    Service.rotate(org.researchzosho.Config.home().resolve("logs").resolve("librarian-serve.log"), 20L * 1024 * 1024);
                    fire.run();
                    // the quiet moment: the housekeeping is done; in auto mode a newer release is swapped in and the service restarts
                    try { Updater.maybeAuto(store, idle.getAsBoolean()); } catch (Throwable e) { log(store, "update", "FAILED: " + e, 0); }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    log(store, "nightly", "FAILED: " + e, 0);
                }
            }
        }, "librarian-crews");
        t.setDaemon(true);
        return t;
    }
}
