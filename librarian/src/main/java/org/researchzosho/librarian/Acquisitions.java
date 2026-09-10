package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.researchzosho.tools.WebFetchTool;
import org.researchzosho.tools.WebSearchTool;

/**
 * The acquisitions desk — where research runs SUBMIT and the mechanical gates decide what may
 * even reach review (the operator: "we don't want garbage to occur"). Model judgment never runs here;
 * these gates are arithmetic over run evidence, which is why a refusal is trustworthy: it names
 * the substrate, not the model's opinion of itself.
 *
 * <p>A refused run is NOT deleted — the refusal lands on the frontier as a gap ("re-run X on a
 * healthy substrate"), because a failed measurement is a fact worth remembering too.
 *
 * <p><b>Consent:</b> ingest happens only when the library exists on disk. `codezaiku librarian
 * init` is the opt-in; with no library directory, every research run behaves exactly as before
 * this class existed. Writing to a person's long-term knowledge store is an act, and acts ask.
 */
public final class Acquisitions {

    private Acquisitions() { }

    /** Does the person have a library at all? The consent gate for every ingest path. A home
     *  that cannot even be resolved is an absent library, never a crash inside someone's turn. */
    public static boolean libraryExists() {
        try {
            return Files.isDirectory(LibraryStore.open().root());
        } catch (Exception e) {
            return false;
        }
    }

    /** Counter snapshot taken before a run; diffed after to judge THAT run's substrate. */
    public record Snapshot(int fetchesOk, int degradedEvents) {
        public static Snapshot take() {
            return new Snapshot(WebFetchTool.FETCHES_OK.get(), WebSearchTool.DEGRADED_EVENTS.get());
        }
        public int fetchesSince() { return WebFetchTool.FETCHES_OK.get() - fetchesOk; }
        public int degradedSince() { return WebSearchTool.DEGRADED_EVENTS.get() - degradedEvents; }
    }

    public record Gate(boolean admitted, String reason) { }

    /** Below this a summary is a status line, not an answer. */
    static final int MIN_SUMMARY_CHARS = 200;

    /**
     * The mechanical intake gates, in order of what they prove:
     * <ol>
     *   <li>ended by cap/timeout, not task_done — the run never concluded; its summary is a
     *       truncation artifact, not an answer;</li>
     *   <li>zero sources fetched — whatever the summary claims was answered from memory;</li>
     *   <li>search backend degraded AND thin fetching — the substrate was down; absence of
     *       results proves nothing.</li>
     * </ol>
     */
    public static Gate gate(boolean done, String summary, int fetchesOk, int degradedEvents) {
        return gate(done, summary, "", fetchesOk, degradedEvents);
    }

    /** With {@code workerNotes}: substance is judged over the synthesis AND the workers' findings —
     *  an empty synthesis on top of seven workers' real work is a thin record, not garbage. */
    public static Gate gate(boolean done, String summary, String workerNotes, int fetchesOk, int degradedEvents) {
        if (!done) {
            return new Gate(false, "run ended by turn cap or timeout, not task_done");
        }
        boolean urlsInSummary = summary != null && summary.contains("https://");
        if (fetchesOk == 0 && !urlsInSummary) {
            return new Gate(false, "zero sources fetched — answered from memory");
        }
        if (degradedEvents > 0 && fetchesOk < 2) {
            return new Gate(false, "search backend degraded during the run (" + degradedEvents
                    + " event(s)) with almost nothing fetched — substrate, not evidence");
        }
        // SUBSTANCE (after the substrate checks — a degraded backend is the more useful reason): a run can conclude with a summary that says nothing ("(done)") — caught
        // live 2026-09-01 when a stray process hit its deadline turn and submitted two words
        // on top of 64 queries of work. Sources fetched do not make an empty answer an answer.
        int sumLen = summary == null ? 0 : summary.strip().length();
        int notesLen = workerNotes == null ? 0 : workerNotes.strip().length();
        if (sumLen < MIN_SUMMARY_CHARS && notesLen < MIN_SUMMARY_CHARS) {
            return new Gate(false, "summary has no substance (" + sumLen + " chars)");
        }
        return new Gate(true, "");
    }

    /** Record a refusal: frontier gap + circulation. The question stays alive, attributed to substrate. */
    public static void refuse(LibraryStore store, String question, String reason) {
        try {
            store.frontier("report", "re-run on a healthy substrate — \"" + compress(question, 160)
                    + "\" (refused at intake: " + reason + ")");
        } catch (IOException ignored) {
            // the frontier is best-effort here; the refusal itself already happened
        }
        store.circulate("intake-refused", reason + " :: " + compress(question, 120));
    }

    /**
     * Admit a completed run: write the Investigation DRAFT (body = the run's own answer, sources
     * = the URLs it cited), index it, log circulation. Findings extraction and judgment belong to
     * the librarian REVIEW pass — this desk records, it does not interpret.
     */
    public static Investigation admit(LibraryStore store, LibrarianIndex index, String question,
                                      String summary, String writer) throws IOException {
        return admit(store, index, question, summary, writer, "");
    }

    /**
     * {@code workerNotes}: for a fan run, the sub-questions and each worker's findings — the
     * investigation carries the WORK, not only the synthesis. A synthesis lost to a cut-off
     * run (measured: an outer timeout at turn 13/15 with a 5k-char draft) must not lose what
     * six workers established across 64 queries.
     */
    public static Investigation admit(LibraryStore store, LibrarianIndex index, String question,
                                      String summary, String writer, String workerNotes) throws IOException {
        String id = store.nextInvestigationId(question);
        StringBuilder body = new StringBuilder();
        body.append("## Question\n\n").append(question.strip()).append("\n\n");
        boolean thin = summary == null || summary.strip().length() < MIN_SUMMARY_CHARS;
        body.append("## Answer (as submitted by the run — draft until reviewed)\n\n")
            .append(thin ? "(the synthesis step did not produce an answer — the worker findings below "
                    + "are the record; a later run or the person can synthesize them)"
                    : summary.strip()).append('\n');
        if (workerNotes != null && !workerNotes.isBlank()) {
            body.append("\n## Worker findings (fan sub-investigations, verbatim)\n\n")
                .append(workerNotes.strip()).append('\n');
        }
        List<String> urls = urls((summary == null ? "" : summary) + "\n" + (workerNotes == null ? "" : workerNotes));
        if (!urls.isEmpty()) {
            body.append("\n## Sources cited\n\n");
            for (String u : urls) body.append("- ").append(u).append('\n');
        }
        Investigation inv = new Investigation(id, compress(question, 120), Finding.State.draft,
                writer, Instant.now().toString(), List.of(), List.of(), body.toString());
        store.write(inv);
        index.upsert(inv);
        store.circulate("intake-admitted", id);
        return inv;
    }

    /**
     * Every source LOCATOR in the text: https URLs, plus bare scholarly identifiers resolved to
     * their canonical URLs — {@code arXiv 2608.01913} → {@code https://arxiv.org/abs/2608.01913},
     * {@code doi:10.1234/x} → {@code https://doi.org/10.1234/x}. Deduplicated, order kept.
     * Caught live 2026-09-01 (third citation shape in a day): workers read papers and cited
     * them by arXiv id in prose; a URL-only gate then dropped the record's three strongest
     * findings as "no source present". An identifier is as unmintable as a URL — it must
     * appear in the record — so anchoring on it keeps the rule and stops the loss.
     */
    public static List<String> urls(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("(?:https?|file)://\\S+|\\braw/\\d{4}-\\d{2}-\\d{2}-[0-9a-f]{12}\\.md").matcher(text);   // the shelves' locators count as sources too
        while (m.find()) {
            String u = m.group().replaceAll("[)\\]>,.;\"']+$", "");
            if (!out.contains(u)) out.add(u);
        }
        Matcher a = Pattern.compile("(?i)arxiv[: ]+(\\d{4}\\.\\d{4,5})(v\\d+)?").matcher(text);
        while (a.find()) {
            String u = "https://arxiv.org/abs/" + a.group(1);
            if (out.stream().noneMatch(x -> x.contains(a.group(1)))) out.add(u);
        }
        Matcher d = Pattern.compile("(?i)\\bdoi[: ]+(10\\.\\d{4,9}/[^\\s)\\]>,;\"']+)").matcher(text);
        while (d.find()) {
            String u = "https://doi.org/" + d.group(1);
            if (out.stream().noneMatch(x -> x.contains(d.group(1)))) out.add(u);
        }
        for (String c : editionCitations(text)) if (!out.contains(c)) out.add(c);
        return out;
    }

    /**
     * EDITION-SHAPED sources — the humanities requirement (the architecture notes): "Hosaka 2016,
     * p. 47", "Gilgamesh, tablet XI, trans. George (Penguin 2003)". No URL, no identifier — yet
     * they are the sources a classicist actually cites, and a locator-only gate would keep every
     * such finding out of canon forever. Mechanical shape: a line in the record's SOURCES section
     * that carries no URL, is at least 12 chars, and names a YEAR. Locator form: {@code cite:<line>}.
     */
    static List<String> editionCitations(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        // Only LIST ITEMS under the heading count, and the list ends at the first line that is not one. A worker's
        // "Sources: <url>" line followed by its prose used to turn every later sentence naming a year into a
        // citation — 36 of a write-up's 90 references were "SUMMARY: The sources establish…" (2026-09-10).
        boolean inSources = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.matches("(?i)^#*\\s*(sources( cited)?|references|参考文献|出典|引用文献)\\b:?\\s*$")) { inSources = true; continue; }
            if (!inSources) continue;
            if (line.isEmpty()) continue;
            boolean item = line.matches("^([-*•]|\\d+[.)]|\\[\\d+\\])\\s+.*");
            if (!item) { inSources = false; continue; }
            String c = line.replaceAll("^([-*•]|\\d+[.)]|\\[\\d+\\])\\s+", "").strip();
            if (c.startsWith("cite:")) { if (!out.contains(c)) out.add(c); continue; }   // the record's own "Sources cited" list, already in locator form
            if (c.length() < 12 || c.contains("http") || !c.matches(".*\\b(1[5-9]\\d\\d|20\\d\\d)\\b.*")) continue;
            out.add("cite:" + compress(c, 200));
        }
        return out;
    }

    /** Author/name tokens and years of a citation-shaped string — what two citations must share. */
    static java.util.Set<String> citationKeys(String s) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (s == null) return keys;
        Matcher y = Pattern.compile("\\b(1[5-9]\\d\\d|20\\d\\d)\\b").matcher(s);
        while (y.find()) keys.add("y:" + y.group(1));
        for (String w : s.split("[^\\p{L}]+")) {
            boolean cjk = w.codePoints().anyMatch(ch -> ch >= 0x3040 && ch <= 0x9fff);
            if ((cjk && w.length() >= 2) || (!cjk && w.length() >= 4 && Character.isUpperCase(w.charAt(0)))) {
                keys.add("n:" + w.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return keys;
    }

    public static String compress(String s, int max) {
        String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
