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
 * {@code researchzosho translate <file> --to <lang> [--as md|txt|pdf|docx|epub] [--quality quick|checked] [--out <path>] [--no-wait]} and
 * {@code researchzosho glossary …}: a document translated from the command line. The time is printed first, from the unit count and the
 * speed measured on the model. With the library's service running the translation is filed as a job the service runs, and the command
 * waits on it, printing its progress, or returns the job id with {@code --no-wait}; with no service it runs here, in this terminal.
 */
final class TranslateCli {
    private TranslateCli() { }

    static final String USAGE = "usage: researchzosho translate <file> --to <language> [--as md|txt|pdf|docx|epub] [--quality checked|quick|reviewed|thorough] [--model <entry>] [--judge <entry>|auto] [--back-translate on|off] [--out <path>] [--collection <name>] [--no-wait]\n       researchzosho translate scorers [status|install [--cpu]|stop]   ·   researchzosho translate scores accept <translation note> [--units 1,3-5]\n"
            + "  Translates a txt, md, pdf, docx or epub file into the language (" + Translation.languagesSaid() + ") and writes it beside the original, in the same format unless --as says another.\n"
            + "  checked, the default, is one pass with the program's checks and scores (LaBSE, and MetricX-24 when installed) and a revision of what they flag; quick is the one pass alone; reviewed adds a second reading by the judge seat of the flagged units and a sample of the rest; thorough reads every unit. --model names the entry that translates; --judge the entry that reads, or auto for the strongest other one; a hosted entry is a paid call, its cost said first, within the entry's monthly cap. A note beside the output says what was done.\n"
            + "  Both files go onto the shelves. The time is said first, from the number of units and the model's measured speed.";

    static int translate(LibraryStore store, String[] args) throws Exception {
        if (args.length < 3) { System.err.println(USAGE); return 2; }
        Path file = Path.of(args[2]).toAbsolutePath().normalize();
        if (args[2].equals("scorers") && !Files.isRegularFile(Path.of(args[2]))) return scorers(store, args);
        if (args[2].equals("scores") && !Files.isRegularFile(Path.of(args[2]))) return scores(store, args);
        String to = "", as = "", quality = Translation.DEFAULT, collection = "", judge = "", modelEntry = "", back = ""; Path out = null; boolean wait = true;
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
                    case "--no-wait" -> wait = false;
                    default -> { System.err.println(USAGE); return 2; }
                }
            }
        } catch (IllegalArgumentException e) { System.err.println(USAGE); return 2; }
        if (to.isEmpty()) { System.err.println("Which language? --to Japanese, --to ja, or any of: " + Translation.languagesSaid() + "."); return 2; }
        if (!Files.isRegularFile(file)) { System.err.println("No file is at " + file + "."); return 1; }
        String[] seat = Translation.configuredSeat();
        if (!modelEntry.isEmpty() && !modelEntry.equalsIgnoreCase(Auto.NAME)) { ModelEntries.Entry e = ModelEntries.get(modelEntry); if (e == null) { e = ModelEntries.resolve(modelEntry); } if (e == null) { System.err.println(ModelEntries.noMatch(modelEntry)); return 2; } seat = new String[]{e.drive(), e.model()}; }
        Translation.Plan plan;
        try { plan = Translation.plan(store, file, to, as, quality, seat[1], modelEntry, judge); }
        catch (IOException e) { System.err.println(e.getMessage()); return 1; }
        String name = ReadIn.name(plan.to());
        System.out.println("\"" + file.getFileName() + "\": " + plan.units() + (plan.units() == 1 ? " unit" : " units") + " with words, " + String.format(Locale.ROOT, "%,d", plan.chars()) + " characters, written in " + ReadIn.name(plan.from()) + ". Into " + name + " as " + plan.as() + ", " + plan.quality() + ": " + plan.saidFor() + ".");
        System.out.println("  (" + plan.said() + ")");
        ObjectNode a = Translation.args(file, file.getFileName().toString(), plan.to(), plan.as(), plan.quality(), out, collection, "cli", judge, modelEntry, back);
        Jobs jobs = new Jobs(store, j -> "");
        boolean served = servedByADaemon(store);
        if (served) {
            String id = jobs.submit(Translation.JOB_KIND, "person", a);
            if (!wait) { System.out.println("Filed as " + id + "; the service picks it up within seconds. researchzosho jobs " + id + " shows how it goes."); return 0; }
            System.out.println("Filed as " + id + "; the service runs it. Waiting (Ctrl-C leaves it running; researchzosho jobs " + id + " shows it).");
            String lastSaid = "";
            long filedAt = System.currentTimeMillis();
            while (true) {
                Thread.sleep(2000);
                ObjectNode j = jobs.get(id);
                if (j == null) { System.err.println("The job " + id + " is gone from the library's records."); return 1; }
                String state = j.path("state").asText("");
                if (state.equals("queued") && System.currentTimeMillis() - filedAt > 60_000) {
                    // nothing picked it up in a minute: the service records a pid but does not serve this library, or is busy; it runs here instead
                    Jobs.Stop st = jobs.stopRun(id, "the terminal");
                    if (st != null && st.state().equals("stopped")) { System.out.println("No service picked it up in a minute. Running it here, in this terminal."); served = false; break; }
                }
                if (state.equals("running")) { String said = progressWords(j.path("progress")); if (!said.isEmpty() && !said.equals(lastSaid)) { System.out.println("  " + said); lastSaid = said; } }
                if (state.equals("done")) { System.out.println(j.path("result").asText("")); return 0; }
                if (state.equals("failed") || state.equals("stopped")) { System.err.println((state.equals("failed") ? "Failed: " : "Stopped: ") + j.path("result").asText("")); return 1; }
            }
        }
        if (!Translation.DRIVE_ANSWERS.test(seat[0])) { System.err.println("No model is answering at " + seat[0] + ": a translation needs one. Start the model server, connect one (researchzosho setup), or name an entry with --model."); return 1; }
        Translation.Seats seats;
        try { seats = Translation.SEATS.make(seat[0], seat[1], judge, back).translatorNamed(modelEntry.isEmpty() ? null : ModelEntries.get(modelEntry)); } catch (Jobs.Failure e) { System.err.println(e.getMessage()); return 1; }
        String[] lastSaid = {""};
        try {
            Translation.Outcome o = Translation.run(store, file, plan.to(), plan.as(), plan.quality(), out, collection, seats, p -> { String said = progressWords(p); if (!said.equals(lastSaid[0])) { System.out.println("  " + said); lastSaid[0] = said; } }, () -> false);
            Translation.tally(store, "", seats, o.seconds());
            System.out.println(Translation.result(store, o, false));
            return 0;
        } catch (IOException e) { System.err.println(e.getMessage()); return 1; }
    }

    /** Whether a daemon serves this library: it recorded its pid in the library, or it is the machine's service and this is the machine's library. */
    static boolean servedByADaemon(LibraryStore store) {
        Long inLibrary = Service.recordedPid(Service.pidFileIn(store.root()));
        if (inLibrary != null && ProcessHandle.of(inLibrary).map(ProcessHandle::isAlive).orElse(false)) return true;
        boolean theDefault;
        try { theDefault = Acquisitions.libraryExists() && LibraryStore.open().root().toAbsolutePath().normalize().equals(store.root().toAbsolutePath().normalize()); } catch (RuntimeException e) { theDefault = false; }
        return theDefault && Share.serviceRunning.getAsBoolean();
    }

    /** A translation's progress in words: "translating, 12 of 31 units". */
    static String progressWords(JsonNode p) {
        if (p == null || !p.isObject()) return "";
        String phase = p.path("phase").asText(""); int done = p.path("units_done").asInt(), total = p.path("units_total").asInt();
        return switch (phase) {
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
                String l = TranslationScores.labseSetupSaid(), m = TranslationScores.metricxSetupSaid();
                String at = TranslationScores.labseAddress(false);
                System.out.println("LaBSE (" + TranslationScores.LABSE_MODEL + "): " + (l.isEmpty() ? "set up, " + (at == null ? "the server is not answering now; a checked run starts it" : "answering at " + at) : l) + ".");
                System.out.println("MetricX-24 (" + TranslationScores.METRICX_MODEL + "): " + (m.isEmpty() ? "installed, in " + TranslationScores.metricxDir() : m) + ".");
                return 0;
            }
            case "install" -> {
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
            default -> { System.err.println("usage: researchzosho translate scorers [status|install [--cpu]|stop]"); return 2; }
        }
    }

    /** {@code translate scores accept <note> [--units 1,3-5]}: the scores of units read by hand and found right go on the pair's record, from a translation note's Scores table. */
    static int scores(LibraryStore store, String[] args) throws Exception {
        if (args.length < 5 || !args[3].equals("accept")) { System.err.println("usage: researchzosho translate scores accept <translation note> [--units 1,3-5]   (every scored unit of the note when --units is not given)"); return 2; }
        Path note = Path.of(args[4]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(note)) { System.err.println("No file is at " + note + "."); return 1; }
        Set<Integer> only = new TreeSet<>();
        for (int i = 5; i < args.length; i++) if (args[i].equals("--units") && i + 1 < args.length) for (String part : args[++i].split(",")) { String[] r = part.strip().split("-"); try { int a = Integer.parseInt(r[0].strip()), b = r.length > 1 ? Integer.parseInt(r[1].strip()) : a; for (int k = a; k <= b; k++) only.add(k); } catch (NumberFormatException e) { System.err.println("--units takes numbers and ranges, such as 1,3-5"); return 2; } }
        String text = Files.readString(note, StandardCharsets.UTF_8);
        Matcher pair = Pattern.compile("written in ([A-Za-z]+)\\)\\n- To: .*?\\(\\w+, ([A-Za-z]+)\\)").matcher(text);
        if (!pair.find()) { System.err.println("The note does not say its languages (From … written in X; To … (format, Y))."); return 1; }
        String from = Translate.code(pair.group(1)), to = Translate.code(pair.group(2));
        List<Double> labse = new ArrayList<>(), metricx = new ArrayList<>();
        int taken = 0;
        for (String line : text.split("\n")) {
            Matcher row = Pattern.compile("^\\| (\\d+) \\| ([0-9.]+|—) \\| ([0-9.]+|—) \\| ?(\\d*) ?\\|$").matcher(line.strip());
            if (!row.matches()) continue;
            int unit = Integer.parseInt(row.group(1));
            if (!only.isEmpty() && !only.contains(unit)) continue;
            if (!row.group(2).equals("—")) labse.add(Double.parseDouble(row.group(2)));
            if (!row.group(3).equals("—")) metricx.add(Double.parseDouble(row.group(3)));
            taken++;
        }
        if (taken == 0) { System.err.println("The note has no Scores table" + (only.isEmpty() ? "" : " rows for the units named") + "."); return 1; }
        TranslationScores.accept(store, from, to, labse, metricx);
        TranslationScores.Thresholds t = TranslationScores.thresholds(store, from, to);
        System.out.println(taken + (taken == 1 ? " unit" : " units") + " of " + ReadIn.name(from) + "→" + ReadIn.name(to) + " accepted onto the record (" + labse.size() + " LaBSE, " + metricx.size() + " MetricX-24). Now: " + t.said() + ".");
        return 0;
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
