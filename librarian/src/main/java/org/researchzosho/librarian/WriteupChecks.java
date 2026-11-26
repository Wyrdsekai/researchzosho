package org.researchzosho.librarian;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import org.researchzosho.tools.BookSearchTool;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.Map;
/**
 * Mechanical checks of a write-up against the evidence it was written from — no model, a few
 * milliseconds. They exist because the 27B's prose contradicted its own evidence table (a benchmark
 * "58%" four screens above its 92.2% row; "MIT-licensed" beside an AGPL-3.0 row; "no CVEs" beside two;
 * measured 2026-09-12). Each check returns lines for a "## Checks" section; nothing is rewritten.
 */
public final class WriteupChecks {
    private WriteupChecks() { }

    static final Pattern NUMBER = Pattern.compile("(?<![\\w.])(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)\\s?(%|percent|GB|MB|TB|B\\b|K\\b|M\\b|thousand|million|billion|ms|seconds?|s\\b|minutes?|min\\b|h\\b|hours?|days?|weeks?|months?|years?|tokens?|points?|pts|kg|g\\b|mg|mm|cm|km|m\\b|ft\\b|feet|mi\\b|lbs?|oz|W\\b|kW|MW|Hz|kHz|MHz|GHz|fps|kb|kB|°C|°F)?");
    static final Pattern LICENSE = Pattern.compile("\\b(MIT|AGPL(?:-?3(?:\\.0)?)?|LGPL(?:-?[23](?:\\.\\d)?)?|GPL(?:-?[23](?:\\.\\d)?)?|Apache(?:-|\\s)?2(?:\\.0)?|BSD(?:-\\d-Clause)?|MPL(?:-?2(?:\\.0)?)?|CC[- ]BY(?:-[A-Z]{2})*(?:[- ]\\d\\.\\d)?|proprietary|closed[- ]source)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern CVE = Pattern.compile("\\bCVE-\\d{4}-\\d{4,7}\\b", Pattern.CASE_INSENSITIVE);

    /** The evidence flattened for lookup: commas out of numbers, case folded. */
    static String fold(String evidence) { return evidence == null ? "" : evidence.toLowerCase(Locale.ROOT).replaceAll("(?<=\\d),(?=\\d{3})", ""); }

    /**
     * Numbers with a unit or a percent sign that the answer states and the evidence (the workers' notes and the
     * captured sources) never does: one line each, with the sentence. Bare small integers are not checked
     * (a list count is the writer's own arithmetic); a number with a unit is a claim.
     */
    public static List<String> numbersUnbacked(String answer, String evidence) { return numbersUnbacked(answer, evidence, List.of(), Map.of()); }

    /**
     * As above, scoped: a sentence is checked against the notes and the text of the sources IT cites (by number, URL or
     * id); a sentence citing nothing is checked against the notes alone. Every source's full text would contain any
     * two-digit number somewhere (measured: zero findings over five 12,000-word write-ups).
     */
    public static List<String> numbersUnbacked(String answer, String notes, List<CiteCheck.Ref> refs, Map<Integer, String> textByRef) {
        List<String> out = new ArrayList<>();
        for (Unbacked u : numbersUnbackedAt(answer, notes, refs, textByRef)) out.add(u.what() + " — not in any note or source read this run: \"" + Acquisitions.compress(u.sentence().strip(), 140) + "\"");
        return out;
    }

    /** A number the evidence never states, with the whole sentence that states it. */
    public record Unbacked(String sentence, String what) { }

    /** A bare number of four or more digits that is not a year: a count or an amount, which is a claim like one with a unit. */
    static boolean bareNumberCounts(String num) {
        if (num.contains(".") || num.length() < 4) return false;
        if (num.length() == 4) { int n = Integer.parseInt(num); return n < 1500 || n > 2100; }
        return true;
    }

    /** The text with a mark after each sentence of {@code items}: "{@code [<prefix><what>]}", the way the cite-check marks a sentence. */
    public static String markInline(String text, List<Unbacked> items, String prefix) {
        String out = text;
        for (Unbacked u : items) {
            String sentence = u.sentence().strip();
            int at = out.indexOf(sentence);
            if (at < 0) continue;
            int end = at + sentence.length();
            String mark = " [" + prefix + u.what() + "]";
            if (out.startsWith(mark.strip(), end) || out.startsWith(mark, end)) continue;
            out = out.substring(0, end) + mark + out.substring(end);
        }
        return out;
    }

    /**
     * The rows of the write-up's tables whose book no catalogue returned this run (0.6.0): the first cell's title, before " — " and
     * without its link, against the run's catalogue as {@code BookSearchTool} recorded it — the normalised title, the title before the
     * subtitle, or the record key in the link. A header or rule row, or a row with no catalogue at all, is not a row to check.
     */
    public static List<Unbacked> titlesUnbackedAt(String answer, Set<String> catalogue) {
        List<Unbacked> out = new ArrayList<>();
        if (catalogue == null || catalogue.isEmpty()) return out;
        for (String line : answer.split("\n")) {
            String row = line.strip();
            if (!row.startsWith("|")) continue;
            String[] cells = row.substring(1).split("\\|");
            if (cells.length < 2) continue;
            String first = cells[0].strip();
            if (first.isEmpty() || first.matches("[-: ]+") || first.equalsIgnoreCase("book") || first.equalsIgnoreCase("title") || first.equalsIgnoreCase("本")) continue;
            Matcher link = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)").matcher(first);
            String title = first, url = "";
            if (link.find()) { title = link.group(1); url = link.group(2); }
            String[] parts = title.replaceAll("\\*\\*", "").split("\\s+[—–]\\s+|,\\s+(?=[A-Z\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}][^,]*$)", 2);
            title = parts[0].strip();
            String author = parts.length > 1 ? parts[1].strip() : "";
            if (title.isEmpty()) continue;
            String key = url.replaceFirst("^https?://[^/]+", "");
            boolean catalogueLink = url.isEmpty() || url.matches("(?i)^https?://([^/]*\\.)?(openlibrary\\.org|ndlsearch\\.ndl\\.go\\.jp|ci\\.nii\\.ac\\.jp|openbd\\.jp)/.*");
            String sn = author.isEmpty() ? "" : BookSearchTool.surname(author.split("\\s+(?:and|&)\\s+|,")[0]);
            boolean known = (!key.isEmpty() && catalogue.contains(key))
                    || (sn.isEmpty() ? catalogue.contains(Shelf.norm(title)) || catalogue.contains(Shelf.shortTitle(title))
                                     : catalogue.contains(Shelf.norm(title) + "|" + sn) || catalogue.contains(Shelf.shortTitle(title) + "|" + sn));
            if (!catalogueLink) { out.add(new Unbacked(line, "\"" + Acquisitions.compress(title, 80) + "\" (its link is not a catalogue record)")); continue; }
            if (!known) { out.add(new Unbacked(line, "\"" + Acquisitions.compress(title, 80) + "\"" + (sn.isEmpty() ? "" : " by " + Acquisitions.compress(author, 40)))); continue; }
            // the row's year against the record's first-publication year, where the catalogue gave one for this title
            Matcher y = Pattern.compile("(?<![\\d/.-])((?:1[5-9]|20)\\d{2})(?![\\d])").matcher(row.substring(first.length() + 1));
            if (y.find()) {
                String year = y.group(1); String t1 = Shelf.norm(title), t2 = Shelf.shortTitle(title);
                boolean anyYear = catalogue.stream().anyMatch(k -> k.startsWith(t1 + "@") || k.startsWith(t2 + "@"));
                boolean near = false;
                for (int d = -1; d <= 1 && !near; d++) { String yy = String.valueOf(Integer.parseInt(year) + d); near = catalogue.contains(t1 + "@" + yy) || catalogue.contains(t2 + "@" + yy); }
                if (anyYear && !near) {
                    String rec = catalogue.stream().filter(k -> k.startsWith(t1 + "@") || k.startsWith(t2 + "@")).map(k -> k.substring(k.indexOf('@') + 1)).sorted().findFirst().orElse("");
                    out.add(new Unbacked(line, "\"" + Acquisitions.compress(title, 80) + "\": the year " + year + " is not the record's (" + rec + ")"));
                }
            }
        }
        return out;
    }

    /** As {@link #numbersUnbacked}, each with its whole sentence. A figure the evidence does not settle is not among them ({@link #numbersUnsettledAt}). */
    public static List<Unbacked> numbersUnbackedAt(String answer, String notes, List<CiteCheck.Ref> refs, Map<Integer, String> textByRef) {
        return numbersChecked(answer, notes, refs, textByRef).unbacked();
    }

    /**
     * The figures the check cannot settle either way, each with its whole sentence: one that only a reading of the evidence the evidence
     * itself does not decide would give (a lone 1.234 in a source that shows neither use of the full stop), or a whole number of one
     * significant digit with no word of approximation (400,000 where a source says 386,258). Such a sentence is left as the writer wrote
     * it, never marked or sent for revision, and the report names it ({@link Figures}).
     */
    public static List<Unbacked> numbersUnsettledAt(String answer, String notes, List<CiteCheck.Ref> refs, Map<Integer, String> textByRef) {
        return numbersChecked(answer, notes, refs, textByRef).unsettled();
    }

    /**
     * The figures that neither the notes nor the sources their sentence cites state, but another source read this run does: {@code said}
     * names the cited sources (not kept, or not stating it) and the ones that state it. Such a sentence is not marked and never revised: a
     * revision deleted a true "8,848.86 m (29,031.7 ft)" on 2026-11-25 whose cited page was never kept while two kept pages gave it.
     * {@code otherKept}: the pages read this run that are not among the references, by their address.
     */
    public static List<Elsewhere> numbersElsewhereAt(String answer, String notes, List<CiteCheck.Ref> refs, Map<Integer, String> textByRef, Map<String, String> otherKept) {
        return numbersChecked(answer, notes, refs, textByRef, otherKept).elsewhere();
    }

    /** A figure another source read this run states: the sentence, the figure, and what the Checks line says of where it is. */
    public record Elsewhere(String sentence, String what, String said) { }

    /** The figures no note or source states, those the check cannot settle, and those only an uncited source read this run states. */
    record Checked(List<Unbacked> unbacked, List<Unbacked> unsettled, List<Elsewhere> elsewhere) { }

    static Checked numbersChecked(String answer, String notes, List<CiteCheck.Ref> refs, Map<Integer, String> textByRef) { return numbersChecked(answer, notes, refs, textByRef, Map.of()); }

    /**
     * A decade written with an s, "1850s", "the 1990s", "'90s": years, which this check does not check (a year is no quantity: see
     * {@link #bareNumberCounts}), never 1,850 seconds ("trigonometric surveying (1850s)" was flagged as "1850 s" on 2026-11-25). A duration
     * in seconds is written with a space ("1850 s") or the word; the other languages' decades (1850年代, 1850년대, 1850er, 1850-е, les
     * années 1850) are a year followed by its word, which this check passes as a year already.
     */
    static boolean decade(String text, int numStart, int numEnd, String num, String unit) {
        if (unit == null || !unit.equals("s") || numEnd >= text.length() || text.charAt(numEnd) != 's') return false;   // the s right after the digits
        if (num.matches("1\\d{2}0|20\\d0")) return true;   // 1000s to 2090s
        if (!num.matches("[1-9]0")) return false;
        String before = text.substring(Math.max(0, numStart - 7), numStart).toLowerCase(Locale.ROOT);
        return before.endsWith("'") || before.endsWith("\u2019") || before.matches("(?s).*\\b(?:the|mid-|early|late)\\s*$");   // the '90s, the 90s, mid-90s
    }

    static Checked numbersChecked(String answer, String notes, List<CiteCheck.Ref> refs, Map<Integer, String> textByRef, Map<String, String> otherKept) {
        List<Unbacked> out = new ArrayList<>(), unsettled = new ArrayList<>();
        List<Elsewhere> elsewhere = new ArrayList<>();
        String notesFolded = fold(notes);
        // the numbers of the evidence, each text read as it writes its own numbers: the notes once, each cited source once
        Figures.Read[] notesRead = {null};
        Map<Integer, Figures.Read> refRead = new HashMap<>();
        // the report's own way of writing numbers, for the groupings its sentences use
        Figures.Convention reportConvention = Figures.convention(answer);
        boolean reportSpaces = Figures.spaceGroups(answer == null ? "" : answer);
        Set<String> seen = new LinkedHashSet<>();
        for (String sentence : CiteCheck.sentences(answer)) {
            if (sentence.startsWith("#") || sentence.startsWith("|")) continue;
            String ev = notesFolded;
            List<CiteCheck.Ref> cited = CiteCheck.citedRefs(sentence, refs);
            for (CiteCheck.Ref r : cited) { String t = textByRef.get(r.n()); if (t != null) ev = ev + "\n" + fold(t); }
            // a range shares its unit: "38–58%" states 38% and 58%
            // an address's digits are no number of the claim, and an escaped space before a year is not one number with it ("%201966" was read
            // as 201966, the live check of 2026-11-18): addresses out, escapes read, then the numbers, each read as one however it is grouped
            String plain = Figures.plainDigits(percentDecoded(sentence.replaceAll("https?://\\S+", " ")), reportConvention, reportSpaces);
            String expanded = plain.replaceAll("(\\d+(?:\\.\\d+)?)\\s?[–\\-]\\s?(\\d+(?:\\.\\d+)?)\\s?(%|percent|GB|MB|TB|ms|seconds?|minutes?|hours?|days?|tokens?|points?|thousand|million|billion)(?![\\p{L}])", "$1 $3 $2 $3");
            Matcher m = NUMBER.matcher(expanded);
            while (m.find()) {
                String num = m.group(1).replace(",", ""), unit = m.group(2);
                if (decade(expanded, m.start(1), m.end(1), num, unit)) continue;
                if (unit == null) {
                    // a small bare number is the writer's own arithmetic; a count of thousands is a claim. The digits of an id, a path, a
                    // date or a time (F-0012-a, 2026-09-14, 10:30, v1/2048) are none of these
                    char before = m.start(1) > 0 ? expanded.charAt(m.start(1) - 1) : ' ';
                    if ("-_/#:+".indexOf(before) >= 0 || num.startsWith("0") || !bareNumberCounts(num)) continue;
                }
                if (unit != null && num.length() < 2 && !"%".equals(unit) && Figures.scale(unit) == null) continue;     // "2 days", "1 h": too small to mean anything
                String key = num + (unit == null ? "" : unit.toLowerCase(Locale.ROOT));
                if (!seen.add(key)) continue;
                if (ev.contains(num)) continue;
                // the digits are not there as written: the numbers the evidence states, read as each text writes them, against the figure
                // as the report writes it (rounded, a bound, a scale), by the rule of Figures
                Figures.Read evidence = Figures.Read.empty();
                if (notesRead[0] == null) notesRead[0] = Figures.read(notes);
                evidence.addAll(notesRead[0]);
                for (CiteCheck.Ref r : cited) { String t = textByRef.get(r.n()); if (t != null) evidence.addAll(refRead.computeIfAbsent(r.n(), k -> Figures.read(t))); }
                Figures.Hedge hedge = Figures.hedge(expanded.substring(0, m.start(1)), expanded.substring(m.end(1)));
                Figures.Figure fig = Figures.figure(num, unit, hedge);
                Figures.Backing b = Figures.backs(fig, evidence);
                if (b == Figures.Backing.BACKED) continue;
                String what = num + (unit == null ? "" : " " + unit);
                // not in the notes nor the sources the sentence cites: every other source read this run, by the numbers each states
                List<String> stating = new ArrayList<>();
                for (CiteCheck.Ref r : refs) {
                    if (cited.contains(r)) continue;
                    String t = textByRef.get(r.n());
                    if (t != null && states(fig, unit, t, refRead.computeIfAbsent(r.n(), k -> Figures.read(t)))) stating.add("[" + r.n() + "]");
                }
                for (Map.Entry<String, String> k : otherKept.entrySet()) if (states(fig, unit, k.getValue(), Figures.read(k.getValue()))) stating.add(k.getKey());
                if (!stating.isEmpty()) {
                    List<String> notKept = new ArrayList<>(), silent = new ArrayList<>();
                    for (CiteCheck.Ref r : cited) (textByRef.containsKey(r.n()) ? silent : notKept).add("[" + r.n() + "]");
                    String citedSaid = cited.isEmpty() ? "the sentence cites no source"
                            : !notKept.isEmpty() && silent.isEmpty() ? "the sentence cites " + String.join(", ", notKept) + ", which " + (notKept.size() == 1 ? "was" : "were") + " not kept this run"
                            : notKept.isEmpty() ? "the sentence cites " + String.join(", ", silent) + ", which " + (silent.size() == 1 ? "does" : "do") + " not state it"
                            : "the sentence cites " + String.join(", ", notKept) + ", not kept this run, and " + String.join(", ", silent) + ", which " + (silent.size() == 1 ? "does" : "do") + " not state it";
                    elsewhere.add(new Elsewhere(sentence, what, citedSaid + "; " + String.join(", ", stating) + ", read this run, " + (stating.size() == 1 ? "states" : "state") + " it"));
                    continue;
                }
                (b == Figures.Backing.UNSETTLED ? unsettled : out).add(new Unbacked(sentence, what));
            }
        }
        return new Checked(out, unsettled, elsewhere);
    }

    /**
     * Whether a source the sentence does not cite states the figure: by the numbers it states, as Figures reads them (the same number, or
     * one the figure is a rounding of). A figure of one or two significant digits is a number any long page holds somewhere (measured:
     * every source's whole text backed every two-digit number, 2026-09-12), so it counts only written with its unit there: "58%", "12 GB".
     */
    static boolean states(Figures.Figure f, String unit, String text, Figures.Read read) {
        if (f == null || text == null || text.isEmpty()) return false;
        if (f.significant() >= 3) { for (BigDecimal e : read.certain()) if (Figures.gives(f, e)) return true; return false; }
        if (unit == null) return false;
        String u = unit.equals("%") || unit.equalsIgnoreCase("percent") ? "(?:%|\\s?per\\s?cent\\b|\\s?percent\\b)" : "\\s?" + Pattern.quote(unit) + "(?![\\p{L}])";
        String plain = Figures.plainDigits(text, Figures.convention(text), Figures.spaceGroups(text));
        Matcher m = Pattern.compile("(?<![\\d.,])(\\d+(?:\\.\\d+)?)" + u, Pattern.CASE_INSENSITIVE).matcher(plain);
        while (m.find()) { try { if (Figures.gives(f, Figures.norm(new BigDecimal(m.group(1)).multiply(Figures.scale(unit) == null ? BigDecimal.ONE : Figures.scale(unit))))) return true; } catch (NumberFormatException ignored) { } }
        return false;
    }

    /** A text with its %XX escapes read as the characters they stand for (UTF-8); an escape that names none stays as it is. */
    static String percentDecoded(String s) {
        if (s.indexOf('%') < 0) return s;
        try { return URLDecoder.decode(s.replace("+", "%2B").replaceAll("%(?![0-9A-Fa-f]{2})", "%25"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return s; }
    }

    /** Licence names and CVE ids the answer states that the evidence never does. */
    public static List<String> namesUnbacked(String answer, String evidence) { return namesUnbacked(answer, evidence, List.of(), Map.of()); }

    public static List<String> namesUnbacked(String answer, String notes, List<CiteCheck.Ref> refs, Map<Integer, String> textByRef) {
        List<String> out = new ArrayList<>();
        String notesFolded = fold(notes);
        Set<String> seen = new LinkedHashSet<>();
        for (String sentence : CiteCheck.sentences(answer)) {
            if (sentence.startsWith("#") || sentence.startsWith("|")) continue;
            String ev = notesFolded;
            for (CiteCheck.Ref r : CiteCheck.citedRefs(sentence, refs)) { String t = textByRef.get(r.n()); if (t != null) ev = ev + "\n" + fold(t); }
            for (Pattern p : List.of(LICENSE, CVE)) {
                Matcher m = p.matcher(sentence);
                while (m.find()) {
                    String name = m.group().replaceAll("\\s+", " ");
                    String key = name.toLowerCase(Locale.ROOT).replaceAll("[- ]", "").replace("3.0", "3").replace("2.0", "2");
                    if (!seen.add(key + "|" + products(sentence))) continue;
                    // a licence is a product's: "Khoj is MIT" is not backed by a note that says AnythingLLM is MIT — the lines that
                    // name the sentence's product must carry it (measured, 2026-09-12)
                    String scope = p == LICENSE ? linesNaming(ev, products(sentence)) : ev;
                    String evKey = scope.replaceAll("[- ]", "").replace("3.0", "3").replace("2.0", "2");
                    if (evKey.contains(key)) continue;
                    out.add(name + " — not in any note or source read this run: \"" + Acquisitions.compress(sentence.strip(), 140) + "\"");
                }
            }
        }
        return out;
    }

    /** The product names a sentence carries: capitalised words of three letters or more that are not sentence-initial function words. */
    static List<String> products(String sentence) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\b([A-Z][\\p{L}\\p{N}.-]{2,})\\b").matcher(sentence);
        while (m.find()) { String w = m.group(1); if (!Set.of("The", "This", "That", "These", "Those", "Its", "Both", "MIT", "AGPL", "GPL", "LGPL", "Apache", "BSD", "MPL", "CVE").contains(w) && !out.contains(w)) out.add(w); }
        return out;
    }

    /** The lines of the folded evidence that name any of {@code names} (case folded); "" when none does. */
    static String linesNaming(String evFolded, List<String> names) {
        if (names.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String line : evFolded.split("\\n")) for (String n : names) if (line.contains(n.toLowerCase(Locale.ROOT))) { b.append(line).append('\n'); break; }
        return b.toString();
    }

    /**
     * Quoted spans of five words or more that appear in no stored source: a quotation the writer made up. Text in
     * double quotes (straight or curly), matched case and punctuation aside against the captured sources.
     */
    public static List<String> quotesUnbacked(String answer, List<String> sourceTexts) {
        List<String> out = new ArrayList<>();
        List<String> norms = new ArrayList<>();
        for (String t : sourceTexts) norms.add(CiteCheck.norm(t == null ? "" : t));
        // an opening quote follows a space, a bracket or a colon and a closing one precedes a space or punctuation: the span
        // between two scare-quoted words ("one-shot" subagents … and "continuable") is not a quotation (three false marks, 2026-09-12)
        Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])[\"“]([\\p{L}\\p{N}][^\"”\\n]{19,400}?)[\"”](?![\\p{L}\\p{N}])").matcher(answer);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String q = m.group(1).strip();
            String nq = CiteCheck.norm(q);
            if (nq.split(" ").length < 5 || !seen.add(nq)) continue;
            // a quotation with a gap marked (… or ... or [...]) is several pieces of one source: each piece of three words or more must stand in it
            List<String> pieces = new ArrayList<>();
            for (String part : q.split("…|\\.\\.\\.|\\[\\s*\\.\\.\\.\\s*\\]")) { String np = CiteCheck.norm(part); if (np.split(" ").length >= 3) pieces.add(np); }
            if (pieces.isEmpty()) pieces.add(nq);
            boolean found = false;
            for (String n : norms) if (!n.isEmpty() && pieces.stream().allMatch(n::contains)) { found = true; break; }
            if (!found) out.add("\"" + Acquisitions.compress(q, 120) + "\" — in no source read this run");
        }
        return out;
    }
}
