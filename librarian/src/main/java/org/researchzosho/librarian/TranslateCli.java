package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code researchzosho translate <file> --to <lang> [--as md|txt|pdf|docx|epub] [--quality quick|checked] [--notes end|off] [--out <path>] [--no-wait]} and
 * {@code researchzosho glossary …}: a document translated from the command line; and a comic (a CBZ, a CBR, a ZIP or a folder of page
 * pictures, or a PDF with {@code --comic on}), whose pages the comic helper reads in the language {@code --from} names or the one it
 * finds on them: where the helper is not set up yet the command says what a set-up fetches and asks once ({@code --yes} answers for a
 * script), and {@code translate comics status|install} is its own command. The time is printed first, from the unit count and the
 * speed measured on the model. With the library's service running the translation is filed as a job the service runs, and the command
 * waits on it, printing its progress, or returns the job id with {@code --no-wait}; with no service it runs here, in this terminal.
 */
final class TranslateCli {
    private TranslateCli() { }

    static final String USAGE = "usage: researchzosho translate <file> --to <language> [--as md|txt|pdf|docx|epub|cbz] [--quality checked|quick|reviewed|thorough] [--model <entry>] [--judge <entry>|auto] [--back-translate on|off] [--notes end|off] [--from <language>] [--direction \"<your words>\"] [--comic on|off] [--reads right-to-left|left-to-right] [--yes] [--out <path>] [--collection <name>] [--no-wait]\n       researchzosho translate scorers [status|install [--cpu]|stop|serve [--bind ADDR] [--port N] [--secret-file FILE]]   ·   researchzosho translate scores [list | withdraw <job id | translation note> | withdraw --unnamed <from> <to>]   ·   researchzosho translate comics [status [--list] | install [--yes]]\n"
            + "  Translates a txt, md, pdf, docx or epub file into the language (" + Translation.languagesSaid() + ") and writes it beside the original, in the same format unless --as says another.\n"
            + "  checked, the default, is one pass with the program's checks and scores (LaBSE, and MetricX-24 when installed) and a revision of what they flag; quick is the one pass alone; reviewed adds a second reading by the judge seat of the flagged units and a sample of the rest; thorough reads every unit. --model names the entry that translates; --judge the entry that reads, or auto for the strongest other one; a hosted entry is a paid call, its cost said first, within the entry's monthly cap. A note beside the output says what was done and lists what the checks still doubt; the document holds the translation alone, and --notes end numbers those passages and lists them at its end too (the setting translate_notes makes either the default).\n"
            + "  --direction gives the translation a voice in your own words, up to " + Translation.DIRECTION_MAX + " characters (\"as if you were Shakespeare\", \"this is a comedy, keep it whimsical\"): the translator, the reviser and the reader are told it; numbers, dates, addresses, citations, names and glossary terms are kept and checked as in any run, a score or a length outside its band revises nothing, and the note lists where the translator could not keep it.\n"
            + "  A comic is translated too (manga, manhwa, manhua, a web comic's strips, a comic book, a graphic novel): a CBZ, a CBR, a ZIP or a folder of page pictures by itself, a PDF whose pages are pictures with --comic on (--comic off says a file is a document). Its pages are read by the comic helper, on this machine or on the one that serves its heavy helpers (researchzosho helpers), from Japanese, Chinese, Korean or a language of the Latin, Cyrillic or Greek alphabet: the language --from names, or the one the helper finds on a sample of the pages, which the command says before it starts. A Japanese comic is read from the right, a Chinese one from the right when its lettering runs in columns, any other from the left; --reads right-to-left says an edition of a manga in another language keeps its pages running from the right (--reads left-to-right the other way; rtl and ltr are taken too). The translation is set into the balloons, in any of those languages, and written as a CBZ beside the original (a PDF for a PDF; --as chooses). The helper is set up on your yes the first time, after the command has said what that fetches; --yes answers for a script.\n"
            + "  Both files go onto the shelves. The time is said first, from the number of units and the model's measured speed.";

    static int translate(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println(USAGE); return 2; }
        Path file = Path.of(args[2]).toAbsolutePath().normalize();
        if (args[2].equals("scorers") && !Files.isRegularFile(Path.of(args[2]))) return scorers(store, args);
        if (args[2].equals("scores") && !Files.isRegularFile(Path.of(args[2]))) return scores(store, args);
        if (args[2].equals("comics") && !Files.exists(Path.of(args[2]))) return comics(args);
        String to = "", as = "", quality = Translation.DEFAULT, collection = "", judge = "", modelEntry = "", back = "", notes = "", from = "", direction = "", comic = "", reads = ""; Path out = null; boolean wait = true, yes = false;
        try {
            for (int i = 3; i < args.length; i++) {
                switch (args[i]) {
                    case "--to", "--into" -> to = LibrarianCli.flagValue(args, i++);
                    case "--as", "--format" -> as = LibrarianCli.flagValue(args, i++).toLowerCase(Locale.ROOT);
                    case "--quality", "--tier" -> quality = LibrarianCli.flagValue(args, i++).toLowerCase(Locale.ROOT);
                    case "--checked" -> quality = Translation.CHECKED;
                    case "--quick" -> quality = Translation.QUICK;
                    case "--reviewed" -> quality = Translation.REVIEWED;
                    case "--thorough" -> quality = Translation.THOROUGH;
                    case "--out" -> out = Path.of(LibrarianCli.flagValue(args, i++)).toAbsolutePath().normalize();
                    case "--collection" -> collection = LibrarianCli.flagValue(args, i++);
                    case "--judge", "--checker" -> judge = LibrarianCli.flagValue(args, i++);
                    case "--model" -> modelEntry = LibrarianCli.flagValue(args, i++);
                    case "--back-translate" -> back = LibrarianCli.flagValue(args, i++);
                    case "--no-back-translate" -> back = "off";
                    case "--notes" -> notes = LibrarianCli.flagValue(args, i++);
                    case "--from" -> from = LibrarianCli.flagValue(args, i++);
                    case "--direction" -> direction = LibrarianCli.flagValue(args, i++);
                    case "--comic" -> { comic = LibrarianCli.flagValue(args, i++).toLowerCase(Locale.ROOT); if (!comic.equals("on") && !comic.equals("off")) { System.err.println("--comic is on (the file is a comic, whatever the program finds) or off (it is a document)."); return 2; } }
                    case "--reads", "--read" -> {   // the way a comic's pages are read, where the person knows better than the language says: an edition of a manga in English
                        reads = ComicTranslation.readsWord(LibrarianCli.flagValue(args, i++));
                        if (reads == null || reads.equals(ComicTranslation.READS_MANGA)) { System.err.println("--reads is right-to-left or left-to-right (rtl and ltr are taken too): the way a comic's pages are read."); return 2; }
                    }
                    case "--yes", "-y" -> yes = true;   // the yes to setting the comic helper up, for a command nobody sits at
                    case "--no-wait" -> wait = false;
                    default -> { System.err.println(USAGE); return 2; }
                }
            }
        } catch (IllegalArgumentException e) { System.err.println(USAGE); return 2; }
        if (Translation.notes(notes) == null) { System.err.println(Translation.NOTES_SAID); return 2; }
        notes = Translation.notes(notes);
        if (to.isEmpty()) { System.err.println("Which language? --to Japanese, --to ja, or any of: " + Translation.languagesSaid() + "."); return 2; }
        try { direction = Translation.direction(direction); } catch (Translation.Refused e) { System.err.println(e.getMessage()); return 2; }
        if (!Files.isRegularFile(file) && !Files.isDirectory(file)) { System.err.println("No file is at " + file + "."); return 1; }
        String[] seat = Translation.configuredSeat();
        if (!modelEntry.isEmpty() && !modelEntry.equalsIgnoreCase(Auto.NAME)) { ModelEntries.Entry e = ModelEntries.get(modelEntry); if (e == null) { e = ModelEntries.resolve(modelEntry); } if (e == null) { System.err.println(ModelEntries.noMatch(modelEntry)); return 2; } seat = new String[]{e.drive(), e.model()}; }
        Translation.Plan plan;
        try { plan = Translation.plan(store, file, to, as, quality, seat[1], modelEntry, judge, from, comic, reads).withDirection(direction); }
        catch (IOException e) { System.err.println(e.getMessage()); return 1; }
        String name = ReadIn.name(plan.to());
        if (!plan.readingSaid(Chrome.now()).isEmpty()) System.out.println(plan.readingSaid(Chrome.now()));   // a PDF: how its pages are read, before anything else
        if (plan.comic() != null) System.out.println("\"" + file.getFileName() + "\": a comic of " + plan.comic().pages() + (plan.comic().pages() == 1 ? " page" : " pages") + ", " + plan.comic().lettering(Chrome.ENGLISH) + ". Into " + name + " as " + plan.as() + ", " + plan.quality() + ": " + plan.saidFor() + ".");
        else System.out.println("\"" + file.getFileName() + "\": " + plan.units() + (plan.units() == 1 ? " unit" : " units") + " with words, " + String.format(Locale.ROOT, "%,d", plan.chars()) + " characters, written in " + ReadIn.name(plan.from()) + ". Into " + name + " as " + plan.as() + ", " + plan.quality() + ": " + plan.saidFor() + ".");
        System.out.println("  (" + plan.timesSaid(Chrome.ENGLISH) + ")");   // the four tiers' times: the rest is said once, on the line above
        ObjectNode a = Translation.args(file, file.getFileName().toString(), plan, out, collection, "cli", judge, modelEntry, back, notes, from);
        if (plan.reading() != null) a.set("pdf_preflight", plan.reading().json());   // the job's page says what the reader found
        ObjectNode filed = Translation.JOB_DRAFT.apply(a);
        System.out.println("  " + TranslationWho.seatsSaid(TranslationWho.facts(filed), Chrome.ENGLISH, false, !TranslationWho.planSaysScores(plan, Chrome.ENGLISH)));   // who translates, who reads, where the scores are made; a hosted model's cost is said on the plan's line
        Jobs jobs = new Jobs(store, j -> "");
        boolean served = servedByADaemon(store);
        // a comic on a machine whose comic helper is not set up: what a set-up fetches, each thing with its size and its licence, and the one
        // question; the yes goes with the job, whose first step is the set-up, so it is the same whether the service runs it or this terminal
        if (plan.comic() != null && plan.comic().helper().equals(ComicTranslation.NEEDS)) {
            if (!served && !Translation.DRIVE_ANSWERS.test(seat[0])) { System.err.println(noModelSaid(seat[0])); return 1; }   // nothing is fetched for a run that could not start
            Chrome c = Chrome.now();
            for (String line : ComicHelper.question(c)) System.out.println(line);
            if (!yes) {
                if (!Interaction.interactive()) { System.err.println("Nobody is at the keyboard to answer, so nothing was fetched and the comic was not translated. Add --yes to this command to set the comic helper up with it, or run researchzosho translate comics install first."); return 1; }
                if (!Interaction.yes(ComicHelper.ask(c))) { System.out.println("Nothing was fetched, and the comic was not translated. The same command asks again; researchzosho translate comics install sets the helper up on its own."); return 1; }
            }
            a.put("comic_setup", "yes");
        }
        if (served) {
            String id = jobs.submit(Translation.JOB_KIND, "person", a);
            if (!wait) { System.out.println("Filed as " + id + "; the service picks it up within seconds. researchzosho jobs " + id + " shows how it goes."); return 0; }
            System.out.println("Filed as " + id + "; the service runs it. Waiting (Ctrl-C leaves it running; researchzosho jobs " + id + " shows it).");
            String lastSaid = "";
            boolean foundSaid = !plan.from().isEmpty();   // a language the run finds at its start is said once, from the job's record, where the service keeps it
            long queuedSince = -1;   // since when this command has seen the job queued without a break: a job a restarted service put back in line is queued again
            boolean toldWaiting = false;
            while (true) {
                Thread.sleep(WAIT_POLL_MS);
                ObjectNode j = jobs.get(id);
                if (j == null) { System.err.println("The job " + id + " is gone from the library's records."); return 1; }
                String state = j.path("state").asText("");
                long now = System.currentTimeMillis();
                if (!state.equals("queued")) queuedSince = -1;
                else if (queuedSince < 0) queuedSince = now;
                else if (now - queuedSince > TAKE_OVER_MS) {
                    // queued a minute: run here only when no live process can be running it. A service that holds the library's lease has the job
                    // in its line (a busy one, or one that restarted and resumes it) and runs it; a pid recorded by a service that does not serve
                    // this library holds no lease here, and then this command takes the lease, claims the job under its lock, and runs it
                    Lease.Holder h = Lease.holder(store);
                    if (h != null && h.live()) {
                        if (!toldWaiting) { System.out.println("  The service (pid " + h.pid() + ") holds this library and has the job in its line; waiting for it to run it (Ctrl-C leaves it there)."); toldWaiting = true; }
                    } else if (takeOver(store, jobs, id)) { System.out.println("No service picked it up in a minute. Running it here, in this terminal."); served = false; break; }
                }
                if (!foundSaid && !j.path("ran").path("from").asText("").isEmpty()) { System.out.println("  " + foundSaid(j.path("ran").path("from").asText())); foundSaid = true; }
                if (state.equals("running")) { String said = progressWords(j.path("progress")); if (!said.isEmpty() && !said.equals(lastSaid)) { System.out.println("  " + said); lastSaid = said; } }
                if (state.equals("done")) { System.out.println(j.path("result").asText("")); return 0; }
                if (state.equals("failed") || state.equals("stopped")) { System.err.println((state.equals("failed") ? "Failed: " : "Stopped: ") + j.path("result").asText("")); return 1; }
            }
        }
        if (!Translation.DRIVE_ANSWERS.test(seat[0])) { System.err.println(noModelSaid(seat[0])); return 1; }
        Translation.Seats seats;
        try { seats = Translation.SEATS.make(seat[0], seat[1], judge, back).translatorNamed(modelEntry.isEmpty() ? null : ModelEntries.get(modelEntry)).withDirection(direction); } catch (Jobs.Failure e) { System.err.println(e.getMessage()); return 1; }
        String[] lastSaid = {""};
        try {
            Translation.Outcome o = Translation.run(store, file, plan.to(), plan.as(), plan.quality(), out, collection, seats, p -> { String said = progressWords(p); if (!said.equals(lastSaid[0])) { System.out.println("  " + said); lastSaid[0] = said; } }, () -> false, Checkpoint.NONE, notes, "", from, ComicTranslation.Said.of(a));
            ObjectNode sp = Translation.tally(store, "", seats, o.seconds());
            ObjectNode ran = Translation.JOB_DRAFT.apply(a);   // the run as a job's record would hold it: who did what, and what a hosted seat cost
            ran.set("ran", TranslationWho.ran(seats, seat[0], plan.quality()));
            if (o.comic() != null) ((ObjectNode) ran.get("ran")).put("second_reading", o.comic().twice().made()).put("from", o.from()).put("pages_on", o.comic().helperOn());
            ran.set("spent", sp);
            System.out.println(Translation.result(store, o, false, "cli", TranslationWho.said(TranslationWho.facts(ran), Chrome.ENGLISH)));
            return 0;
        } catch (IOException e) { System.err.println(e.getMessage()); return 1; }
    }

    /** How often the waiting command reads the job's record, and how long a job stays queued before it looks at running it itself; a test shortens them. */
    static volatile long WAIT_POLL_MS = 2_000, TAKE_OVER_MS = 60_000;

    /**
     * The job made this command's to run, when no live process can be running it: the library's lease taken (refused while a service
     * holds it: false, and the service runs the job), and under it the job stopped as filed, under the job's own lock, so that no service
     * can start it after; true when it was still queued and is now this command's. The lease is let go again before the run: the job's
     * record is no longer queued, and a service that starts during the run serves the library and leaves the job alone.
     */
    static boolean takeOver(LibraryStore store, Jobs jobs, String id) {
        Lease lease;
        try { lease = Lease.acquire(store); } catch (IOException held) { return false; }
        try (lease) { Jobs.Stop st = jobs.stopRun(id, "the terminal"); return st != null && st.state().equals("stopped"); }
        catch (IOException e) { return false; }
    }

    /** What is said when no model answers where a run in this terminal would ask one. */
    static String noModelSaid(String drive) { return "No model is answering at " + drive + ": a translation needs one. Start the model server, connect one (researchzosho setup), or name an entry with --model."; }

    /**
     * {@code translate comics status|install}: the comic helper, which reads a comic's pages and sets a translation into the balloons.
     * {@code status} says how it stands, loading every piece of a helper that is set up once and naming one that does not load
     * ({@code --list} names each thing a set-up fetches with its size and its licence); {@code install}
     * says the list, asks once ({@code --yes} answers) and sets it up. Its files are brought to this release's first, as the scorers' script is.
     * Where the machine that serves this library's heavy helpers serves it (0.7.6), {@code status} says what that machine says of it and
     * {@code install} says the command that sets it up there; nothing of it is loaded or fetched here.
     */
    static int comics(String[] args) throws Exception {
        String op = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "status";
        List<String> rest = Arrays.asList(args).subList(Math.min(args.length, 4), args.length);
        { String r = ComicHelper.refresh(); if (!r.isEmpty()) System.out.println(r); }
        ComicTranslation.sweepWork();   // what comic runs that were killed left in the temporary folder
        switch (op) {
            case "status" -> {
                // served by the machine that serves this library's heavy helpers: what that machine says of it, and nothing loaded here
                if (ComicHelper.remote() != null) { String barred = ComicHelper.barred(Chrome.now()); System.out.println(barred.isEmpty() ? ComicHelper.status() : barred); return barred.isEmpty() ? 0 : 1; }
                // of a helper that is set up, every piece is loaded once: the marker and the files' presence said "set up" of a helper
                // whose package or file had gone bad since, and the run's own sentences send a person here to see how it stands. The
                // status looks and changes nothing; install is the repair, and the sentence names it
                ComicHelper.Checked checked = null;
                if (ComicHelper.installed()) {
                    System.out.println("Loading every piece of the comic helper once, to be sure it works.");   // said before the wait: a quarter of a minute on a slow machine
                    checked = ComicHelper.check();
                }
                boolean works = checked == null || checked.passed();
                System.out.println(works ? ComicHelper.status() + (checked == null ? "" : " Every piece loads" + (checked.seconds() >= ComicHelper.SAID_FROM ? "; that took " + Math.round(checked.seconds()) + " s" : "") + ".") : ComicHelper.broken(checked));
                if (rest.contains("--list")) for (String piece : ComicHelper.pieces()) System.out.println("  " + piece);
                return works ? 0 : 1;
            }
            case "install" -> {
                String barred = ComicHelper.barred(Chrome.now());
                if (!barred.isEmpty()) { System.out.println(barred); return 1; }
                String at = ComicHelper.remote();
                if (at != null) {   // its set-up is the helpers machine's: said with the command, and nothing fetched here
                    String host = ComicHelper.hostSaid(at);
                    System.out.println("The comic helper of this library is served by " + host + " (" + at + "), where it is set up: nothing of it is set up on this machine. researchzosho helpers install --on " + host + " brings it to this release there with the other helpers.");
                    return 0;
                }
                boolean again = false;
                if (ComicHelper.installed()) {
                    // asked of a helper that is set up: every piece loaded once more. A helper whose package or file went bad after its
                    // set-up said "set up already" here and failed at the first comic, with nothing a person could run to mend it
                    System.out.println("The comic helper is set up already, at " + ComicHelper.dir() + ". Loading every piece once, to be sure it still works.");   // said before the wait: a quarter of a minute on a slow machine
                    ComicHelper.Checked checked = ComicHelper.check();
                    if (checked.passed()) { System.out.println("Every piece loads" + (checked.seconds() >= ComicHelper.SAID_FROM ? "; that took " + Math.round(checked.seconds()) + " s" : "") + ". Nothing was changed."); return 0; }
                    String what = checked.mendable() ? checked.what() : checked.said();
                    System.out.println(Character.toUpperCase(what.charAt(0)) + what.substring(1) + "." + (checked.mendable() ? " Putting that right now." : ""));
                    if (!checked.mendable()) return 1;
                    try { ComicHelper.unsetUp(checked); } catch (IOException e) { System.out.println("The comic helper's marker could not be removed: " + e.getMessage() + "."); return 1; }
                    again = true;
                }
                Chrome c = Chrome.now();
                ComicHelper.Needs needs = ComicHelper.needs();
                if (again && !needs.asks()) { /* said above: what did not load is installed or fetched anew, with nothing to ask */ }
                else if (needs.nothing()) System.out.println("Everything the comic helper needs is fetched already; finishing its set-up.");   // nothing to say yes to
                else if (!needs.asks()) System.out.println(ComicHelper.setUpBefore() ? "The comic helper was set up for an earlier release; fetching what this release adds: " + needs.addsSaid() + "." : "Finishing the comic helper's set-up.");   // an addition to a helper that stands, or a set-up that was interrupted: said, not asked
                else {
                    List<String> q = ComicHelper.question(c);
                    for (String line : q.subList(1, q.size())) System.out.println(line);   // what is fetched and under which licences; the sentence before them is a translation's
                    if (!rest.contains("--yes") && !rest.contains("-y")) {
                        if (!Interaction.interactive()) { System.err.println("Nobody is at the keyboard to answer, so nothing was fetched. Add --yes to set the comic helper up."); return 1; }
                        if (!Interaction.yes(ComicHelper.ask(c))) { System.out.println("Nothing was fetched or changed."); return 1; }
                    }
                }
                String r = ComicHelper.install(line -> System.out.println("  " + line));
                System.out.println(r.equals("ready") ? "The comic helper is set up; a comic can be translated now: researchzosho translate <volume.cbz> --to <language>." : "The comic helper was not set up: " + r.replaceFirst("^!", "") + ".");
                return r.equals("ready") ? 0 : 1;
            }
            default -> { System.err.println("usage: researchzosho translate comics [status [--list] | install [--yes]]"); return 2; }
        }
    }

    /** Whether a daemon serves this library: it recorded its pid in the library, or it is the machine's service and this is the machine's library. */
    static boolean servedByADaemon(LibraryStore store) {
        Long inLibrary = Service.recordedPid(Service.pidFileIn(store.root()));
        if (inLibrary != null && ProcessHandle.of(inLibrary).map(ProcessHandle::isAlive).orElse(false)) return true;
        boolean theDefault;
        try { theDefault = Acquisitions.libraryExists() && LibraryStore.open().root().toAbsolutePath().normalize().equals(store.root().toAbsolutePath().normalize()); } catch (RuntimeException e) { theDefault = false; }
        return theDefault && Share.serviceRunning.getAsBoolean();
    }

    /** What a person watching a comic's run is told when the run finds the language of its lettering itself, with the way to name another. */
    static String foundSaid(String code) { return "the comic helper read its lettering as " + ReadIn.name(code) + " (to name another language: --from <language>)"; }

    /** A translation's progress in words: "translating, 12 of 31 units". */
    static String progressWords(JsonNode p) {
        if (p == null || !p.isObject()) return "";
        String phase = p.path("phase").asText(""); int done = p.path("units_done").asInt(), total = p.path("units_total").asInt();
        return switch (phase) {
            case "reading" -> "reading the PDF's pages by eye, " + done + " of " + total;
            case "setting-up" -> p.path("said").asText("setting the comic helper up");
            case "strips" -> "cutting the web comic's strips into pieces, picture " + done + " of " + total;
            case "pages" -> (p.hasNonNull("found") ? foundSaid(p.path("found").asText()) + "; " : "") + "reading the pages, " + done + " of " + total;
            case "reading-twice" -> "reading the lettering a second time, page " + done + " of " + total;
            case "page-translating" -> "translating, page " + done + " of " + total;
            case "lettering" -> "setting the lines into the balloons, page " + done + " of " + total;
            case "fitting" -> "saying lines shorter that were too long for their balloons, " + done + " of " + total;
            case "translating" -> "translating, " + done + " of " + total + " units";
            case "scoring" -> "scoring, " + done + " of " + total + " units";
            case "checking" -> "checking, " + done + " of " + total + " units read side by side";
            case "revising" -> "revising, " + done + " of " + total + " flagged units";
            case "writing" -> "writing the file";
            default -> phase;
        };
    }

    /** {@code translate scorers status|install|stop}: the LaBSE server and the MetricX-24 helper, each size said first. */
    static int scorers(LibraryStore store, String[] args) throws Exception {
        String op = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "status";
        { String rm = TranslationScores.refreshMetricxScript(); if (!rm.isEmpty()) System.out.println(rm); }
        switch (op) {
            case "status" -> {
                // where each scores for THIS library, as a pair: at the helpers address with its front's own line, on this machine, or left out and why
                System.out.println("LaBSE (" + TranslationScores.LABSE_MODEL + "): " + TranslationScores.whereSaid(Helpers.Helper.LABSE) + ".");
                System.out.println("MetricX-24 (" + TranslationScores.METRICX_MODEL + "): " + TranslationScores.whereSaid(Helpers.Helper.METRICX) + ".");
                return 0;
            }
            case "serve" -> { return HelpersCli.serve(args, 4, List.of(Helpers.Helper.METRICX, Helpers.Helper.LABSE)); }
            case "install" -> {
                if (!Helpers.localAllowed()) { System.out.println(Helpers.localRefusedSaid("LaBSE and MetricX-24")); return 1; }
                boolean cpu = Arrays.asList(args).contains("--cpu");
                if (TranslationScores.labseSetupSaid().isEmpty()) System.out.println("LaBSE is set up already.");
                else {
                    System.out.println("Setting LaBSE up: " + TranslationScores.LABSE_MODEL + ", " + TranslationScores.LABSE_SIZE + " fetched once by the embeddings image through Docker" + (cpu ? ", on the CPU" : "") + ".");
                    String r = TranslationScores.startLabse(cpu);
                    System.out.println(r.startsWith("!") ? "LaBSE did not start: " + r.substring(1) : "LaBSE answers at " + r + ".");
                }
                if (TranslationScores.metricxInstalled()) { System.out.println("MetricX-24 is installed already."); return 0; }
                System.out.println("Installing MetricX-24: " + TranslationScores.METRICX_MODEL + ", " + TranslationScores.METRICX_SIZE + ". This takes a while.");
                String r = TranslationScores.installMetricx();
                System.out.println(r.startsWith("!") ? "MetricX-24 was not installed: " + r.substring(1) + ". Checked translations run on LaBSE alone meanwhile." : "MetricX-24 is ready; checked translations score with it from now on.");
                return r.startsWith("!") ? 1 : 0;
            }
            case "stop" -> { System.out.println("LaBSE: " + TranslationScores.stopLabse()); return 0; }
            default -> { System.err.println("usage: researchzosho translate scorers [status|install [--cpu]|stop|serve [--bind ADDR] [--port N] [--secret-file FILE]]"); return 2; }
        }
    }

    static final String SCORES_USAGE = "usage: researchzosho translate scores [list]   ·   withdraw <job id | translation note>   ·   withdraw --unnamed <from> <to>   (the entries an earlier version put there, which name no job)";

    /**
     * {@code translate scores}: the record the checks' thresholds and the length band are measured from. {@code list} (the default) says
     * what it holds by pair and by job, and apart from it the entries of earlier versions, which nothing is measured from; {@code withdraw
     * <job>} takes a job's units off (a translation note's path for a run with no job), {@code withdraw --unnamed <from> <to>} the entries
     * of a pair that name no job. {@code accept}, which put units a person chose as right on the record, is no more: the record takes every
     * paragraph a checked translation scored, and a choice of the right ones would cut that sample as the record of earlier versions was cut.
     */
    static int scores(LibraryStore store, String[] args) throws Exception {
        Chrome c = Chrome.now();
        String op = args.length > 3 ? args[3] : "list";
        if (op.equals("list")) { System.out.println(holdingsSaid(store, c)); return 0; }
        if (op.equals("withdraw")) {
            if (args.length > 6 && args[4].equals("--unnamed")) {
                String from = Translate.code(args[5]), to = Translate.code(args[6]);
                int n = TranslationScores.withdrawUnnamed(store, from, to);
                TranslationScores.Thresholds t = TranslationScores.thresholds(store, from, to);
                System.out.println(c.t(n == 1 ? "{0} unit that named no job left the record of {1} into {2}." : "{0} units that named no job left the record of {1} into {2}.", n, c.languageName(from), c.languageName(to)) + " "
                        + c.t("The record of {0} into {1} now: {2}; {3}.", c.languageName(from), c.languageName(to), t.said(c), TranslationLengths.shortSaid(TranslationLengths.band(store, from, to, null, null), c)));
                return 0;
            }
            if (args.length < 5) { System.err.println(SCORES_USAGE); return 2; }
            System.out.println(TranslationScores.withdrawnSaid(store, TranslationScores.withdraw(store, args[4]), c));
            return 0;
        }
        if (op.equals("accept")) {
            System.err.println("Units are no longer put on the record by hand: every paragraph a checked translation scores goes on it, flagged or not, and a choice of the right ones would cut that sample as the record of earlier versions was cut, tightening the thresholds at every run. A translation whose paragraphs did not go on runs again to put them there.");
            return 2;
        }
        System.err.println(SCORES_USAGE);
        return 2;
    }

    /** What the record holds, pair by pair and by the job or note each unit came from; apart from it, the entries of earlier versions, which nothing is measured from, and why. */
    static String holdingsSaid(LibraryStore store, Chrome c) {
        var all = TranslationScores.holdings(store);
        var earlier = TranslationScores.earlier(store);
        if (all.isEmpty() && earlier.isEmpty()) return c.t("The record the checks' thresholds and the length band are measured from is empty: no translation has put units on it yet.");
        StringBuilder b = new StringBuilder(c.t("The record the checks' thresholds and the length band are measured from, by language pair:"));
        for (var e : all.entrySet()) {
            String[] l = TranslationScores.languages(e.getKey());
            int total = 0; List<String> parts = new ArrayList<>();
            for (var by : e.getValue().entrySet()) { total += by.getValue(); parts.add(by.getKey().isEmpty() ? c.t("no job named: {0}", by.getValue()) : c.tc("{1}: {0}", "a job's id or a translation note, then how many of its units are on the record, in the list of what the record holds", by.getValue(), by.getKey())); }
            b.append("\n  ").append(c.t("{0} into {1}, units on the record: {2} ({3})", c.languageName(l[0]), c.languageName(l[1]), total, String.join(c.sep(), parts)));
        }
        if (!earlier.isEmpty()) {
            for (var e : earlier.entrySet()) {
                String[] l = TranslationScores.languages(e.getKey());
                int total = 0; List<String> parts = new ArrayList<>();
                for (var by : e.getValue().entrySet()) { total += by.getValue(); parts.add(by.getKey().isEmpty() ? c.t("no job named: {0}", by.getValue()) : c.tc("{1}: {0}", "a job's id or a translation note, then how many of its units are on the record, in the list of what the record holds", by.getValue(), by.getKey())); }
                b.append("\n  ").append(c.t("{0} into {1}, entries of earlier versions, not measured from: {2} ({3})", c.languageName(l[0]), c.languageName(l[1]), total, String.join(c.sep(), parts)));
            }
            b.append("\n").append(TranslationScores.earlierWhy(c));
        }
        return b.toString();
    }

    static int glossary(LibraryStore store, String[] args) throws Exception {
        String usage = "usage: researchzosho glossary [list [<language>] | add \"<term>\" <language> \"<rendering>\" | remove \"<term>\" <language>]\n  The terms and their chosen renderings, kept across every translation of a document.";
        String op = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
        switch (op) {
            case "list" -> { System.out.println(Glossary.describe(store, args.length > 3 ? args[3] : "")); return 0; }
            case "add" -> {
                if (args.length < 6) { System.err.println(usage); return 2; }
                try { System.out.println(Glossary.add(store, args[3], args[4], String.join(" ", Arrays.asList(args).subList(5, args.length)))); return 0; }
                catch (ProtocolError e) { System.err.println(e.getMessage()); return 2; }
            }
            case "remove", "forget" -> {
                if (args.length < 5) { System.err.println(usage); return 2; }
                boolean removed = Glossary.remove(store, args[3], args[4]);
                System.out.println(removed ? "Removed \"" + args[3] + "\" for " + ReadIn.name(args[4]) + " from the glossary." : "The glossary has no entry \"" + args[3] + "\" for " + ReadIn.name(args[4]) + ".");
                return removed ? 0 : 1;
            }
            default -> { System.err.println(usage); return 2; }
        }
    }
}
