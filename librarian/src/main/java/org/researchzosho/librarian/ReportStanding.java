package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether a report waits for the keeper's eye, and why. A report is filed as a draft and the review accepts it; a report whose own checks
 * stand against it stays a draft until the keeper has looked: its write-up did not answer the question or answered part of it, its
 * checks flagged more sentences than they could send for revision, or most of the sources it cites were not read in its run. The
 * report's Checks section says why in sentences, for the person; this ledger says it for the library.
 *
 * <p>{@code catalog/report-standing.tsv}, one line per event, newest last: the date, the report (I-…), the hash of its text, who wrote
 * the line (run, review, person), what happened (clear: nothing stands against it; held: it waits for the keeper; reviewed: its claims
 * were read out while it waited; accepted: the keeper accepted it), what the check of the answer against the question said (yes, partly,
 * no, unchecked, or - when the line is not about it), and the reasons, one sentence each. A hold is on the text it was made for: a
 * report whose text changed is read again.
 */
public final class ReportStanding {
    private ReportStanding() { }

    public static final String RUN = "run", REVIEW = "review", PERSON = "person";
    static final String CLEAR = "clear", HELD = "held", REVIEWED = "reviewed", ACCEPTED = "accepted";

    /** One line of the ledger. */
    public record Row(String date, String report, String hash, String by, String what, String topic, List<String> reasons) {
        /** The reasons as one piece of prose. */
        public String why() { return String.join(" ", reasons); }
    }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("report-standing.tsv"); }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").strip(); }

    /** One line more: written under the file's own lock, as the other run ledgers are. */
    static void record(LibraryStore store, String report, String hash, String by, String what, String topic, List<String> reasons) throws IOException {
        List<String> said = new ArrayList<>();
        if (reasons != null) for (String r : reasons) if (r != null && !r.isBlank()) said.add(clean(r).replace(" | ", " / "));
        String line = LocalDate.now() + "\t" + clean(report) + "\t" + clean(hash) + "\t" + clean(by) + "\t" + clean(what) + "\t" + (topic == null || topic.isBlank() ? "-" : clean(topic)) + "\t" + String.join(" | ", said) + "\n";
        store.locked("report-standing", () -> {
            Files.createDirectories(file(store).getParent());
            if (!Files.exists(file(store))) Files.writeString(file(store), "# date\treport\thash of its text\tby\twhat\tthe answer against the question\twhy — which reports wait for the keeper's eye (researchzosho inbox)\n", StandardCharsets.UTF_8);
            Files.writeString(file(store), line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            return null;
        });
    }

    /** Every line, oldest first; none when the library has no ledger yet. */
    static List<Row> rows(LibraryStore store) {
        List<Row> out = new ArrayList<>();
        try {
            if (store == null || !Files.exists(file(store))) return out;
            for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t", -1);
                if (p.length < 6) continue;
                List<String> reasons = new ArrayList<>();
                if (p.length > 6) for (String r : p[6].split(" \\| ")) if (!r.isBlank()) reasons.add(r.strip());
                out.add(new Row(p[0], p[1], p[2], p[3], p[4], p[5], reasons));
            }
        } catch (IOException unreadable) {
            // a ledger that cannot be read holds nothing back: the review then reads the report itself
        }
        return out;
    }

    /** Where the write-up of a report is kept when it was set aside: beside the ledger, outside the shelves, read by nobody but the keeper. */
    public static Path setAsideFile(LibraryStore store, String report) {
        return store.root().resolve("catalog").resolve("set-aside").resolve(report.replaceAll("[^A-Za-z0-9._-]", "_") + ".md");
    }

    /**
     * Keep the write-ups that were set aside for a report, whole, each under what the check said of it. The file is not on the shelves:
     * it is not indexed, not searched, not cited and not shown to any run. It is there so that the keeper can read what was judged.
     */
    static void keepSetAside(LibraryStore store, String report, String question, List<String> writeUps) throws IOException {
        StringBuilder b = new StringBuilder("# The write-up set aside for ").append(report).append("\n\n")
                .append("The library set this write-up aside because the check of its answer against the question said it does not answer the question. ")
                .append("It is kept here for you to read and is not part of the library: nothing searches it, cites it or takes a claim from it.\n\n")
                .append("The question: ").append(question == null ? "" : question.strip()).append('\n');
        int n = 0;
        for (String w : writeUps) b.append("\n---\n\nWrite-up ").append(++n).append(writeUps.size() == 1 ? "" : " of " + writeUps.size()).append(". ").append(w.strip()).append('\n');
        Path f = setAsideFile(store, report);
        Files.createDirectories(f.getParent());
        Files.writeString(f, b.toString(), StandardCharsets.UTF_8);
    }

    /** The hash a hold is made on: the report's text. */
    static String hashOf(Investigation inv) { return Conversations.hash8(inv.body()); }

    /**
     * The hold in force on a report, for the text it has now: the newest line that held it, when no later line cleared it or the keeper
     * accepted it. Null when nothing holds it, or when the hold was on another text.
     */
    public static Row held(LibraryStore store, Investigation inv) {
        if (inv == null) return null;
        String hash = hashOf(inv);
        Row held = null;
        for (Row r : rows(store)) {
            if (!r.report().equals(inv.id())) continue;
            if (r.what().equals(HELD)) held = r.hash().equals(hash) ? r : null;
            else if (r.what().equals(CLEAR) || r.what().equals(ACCEPTED)) held = null;
        }
        return held;
    }

    /** What a check of the answer against the question said of this text of the report, the newest verdict: yes, partly or no; "" when none is on record. */
    static String topic(LibraryStore store, Investigation inv) {
        String hash = hashOf(inv), topic = "";
        for (Row r : rows(store)) if (r.report().equals(inv.id()) && r.hash().equals(hash) && (r.topic().equals("yes") || r.topic().equals("partly") || r.topic().equals("no"))) topic = r.topic();
        return topic;
    }

    /** Whether the report's claims were read out while it waited, for the text it has now: the nightly review then leaves it. */
    static boolean reviewedWhileHeld(LibraryStore store, Investigation inv) {
        String hash = hashOf(inv);
        boolean held = false, reviewed = false;
        for (Row r : rows(store)) {
            if (!r.report().equals(inv.id())) continue;
            switch (r.what()) {
                case HELD -> { held = r.hash().equals(hash); reviewed = false; }
                case CLEAR, ACCEPTED -> { held = false; reviewed = false; }
                case REVIEWED -> { if (held && r.hash().equals(hash)) reviewed = true; }
                default -> { }
            }
        }
        return held && reviewed;
    }

    /** Whether the keeper accepted the report with the text it has now: their word stands, and no check holds it again. */
    static boolean acceptedByKeeper(LibraryStore store, Investigation inv) {
        String hash = hashOf(inv);
        boolean accepted = false;
        for (Row r : rows(store)) if (r.report().equals(inv.id())) accepted = r.what().equals(ACCEPTED) && r.hash().equals(hash);
        return accepted;
    }

    /** A report that waits, with the line that holds it. */
    public record Waiting(Investigation report, Row hold) { }

    /** Every report that waits for the keeper's eye now: still on the shelves, still a draft, its text the one the hold was made on. Newest hold first. */
    public static List<Waiting> waiting(LibraryStore store) {
        Map<String, Row> last = new LinkedHashMap<>();
        for (Row r : rows(store)) if (r.what().equals(HELD)) { last.remove(r.report()); last.put(r.report(), r); }
        List<Waiting> out = new ArrayList<>();
        for (String id : last.keySet()) {
            Investigation inv;
            try { inv = store.investigation(id); } catch (Exception gone) { inv = null; }
            if (inv == null || inv.state() != Finding.State.draft) continue;
            Row held = held(store, inv);
            if (held != null) out.add(0, new Waiting(inv, held));
        }
        return out;
    }

    /**
     * The keeper accepts a report: it is accepted as it stands, signed by them in the ledger, whatever held it. Throws when the library has
     * no report of that id.
     */
    public static Investigation accept(LibraryStore store, String id, String by) throws IOException {
        Investigation inv = store.investigation(id);
        if (inv == null) throw new IOException("The library has no report with the id " + id + ". The command researchzosho inbox lists the reports that wait for you, each with its id.");
        Investigation accepted = inv.with(Finding.State.accepted, inv.findings(), inv.open());
        store.write(accepted);
        record(store, inv.id(), hashOf(inv), by == null || by.isBlank() ? PERSON : by, ACCEPTED, "", List.of());
        store.regenerateIndex();
        store.circulate("report-accepted", inv.id() + " by " + (by == null || by.isBlank() ? PERSON : by));
        return accepted;
    }
}
