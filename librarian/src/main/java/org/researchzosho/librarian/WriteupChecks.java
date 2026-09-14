package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mechanical checks of a write-up against the evidence it was written from — no model, a few
 * milliseconds. They exist because the 27B's prose contradicted its own evidence table (a benchmark
 * "58%" four screens above its 92.2% row; "MIT-licensed" beside an AGPL-3.0 row; "no CVEs" beside two;
 * measured 2026-09-12). Each check returns lines for a "## Checks" section; nothing is rewritten.
 */
public final class WriteupChecks {
    private WriteupChecks() { }

    static final Pattern NUMBER = Pattern.compile("(?<![\\w.])(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s?(%|percent|GB|MB|TB|B\\b|K\\b|M\\b|million|billion|ms|seconds?|s\\b|minutes?|min\\b|h\\b|hours?|days?|weeks?|months?|years?|tokens?|points?|pts|kg|g\\b|mg|mm|cm|km|m\\b|mi\\b|lbs?|oz|W\\b|kW|MW|Hz|kHz|MHz|GHz|fps|kb|kB|°C|°F)?");
    static final Pattern LICENSE = Pattern.compile("\\b(MIT|AGPL(?:-?3(?:\\.0)?)?|LGPL(?:-?[23](?:\\.\\d)?)?|GPL(?:-?[23](?:\\.\\d)?)?|Apache(?:-|\\s)?2(?:\\.0)?|BSD(?:-\\d-Clause)?|MPL(?:-?2(?:\\.0)?)?|CC[- ]BY(?:-[A-Z]{2})*(?:[- ]\\d\\.\\d)?|proprietary|closed[- ]source)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern CVE = Pattern.compile("\\bCVE-\\d{4}-\\d{4,7}\\b", Pattern.CASE_INSENSITIVE);

    /** The evidence flattened for lookup: commas out of numbers, case folded. */
    static String fold(String evidence) { return evidence == null ? "" : evidence.toLowerCase(Locale.ROOT).replaceAll("(?<=\\d),(?=\\d{3})", ""); }

    /**
     * Numbers with a unit or a percent sign that the answer states and the evidence (the workers' notes and the
     * captured sources) never does: one line each, with the sentence. Bare small integers are not checked
     * (a list count is the writer's own arithmetic); a number with a unit is a claim.
     */
    public static List<String> numbersUnbacked(String answer, String evidence) { return numbersUnbacked(answer, evidence, List.of(), java.util.Map.of()); }

    /**
     * As above, scoped: a sentence is checked against the notes and the text of the sources IT cites (by number, URL or
     * id); a sentence citing nothing is checked against the notes alone. Every source's full text would contain any
     * two-digit number somewhere (measured: zero findings over five 12,000-word write-ups).
     */
    public static List<String> numbersUnbacked(String answer, String notes, List<CiteCheck.Ref> refs, java.util.Map<Integer, String> textByRef) {
        List<String> out = new ArrayList<>();
        String notesFolded = fold(notes);
        Set<String> seen = new LinkedHashSet<>();
        for (String sentence : CiteCheck.sentences(answer)) {
            if (sentence.startsWith("#") || sentence.startsWith("|")) continue;
            String ev = notesFolded;
            for (CiteCheck.Ref r : CiteCheck.citedRefs(sentence, refs)) { String t = textByRef.get(r.n()); if (t != null) ev = ev + "\n" + fold(t); }
            // a range shares its unit: "38–58%" states 38% and 58%
            String expanded = sentence.replaceAll("(\\d+(?:\\.\\d+)?)\\s?[–\\-]\\s?(\\d+(?:\\.\\d+)?)\\s?(%|percent|GB|MB|TB|ms|seconds?|minutes?|hours?|days?|tokens?|points?|million|billion)(?![\\p{L}])", "$1 $3 $2 $3");
            Matcher m = NUMBER.matcher(expanded);
            while (m.find()) {
                String num = m.group(1).replace(",", ""), unit = m.group(2);
                if (unit == null) continue;                              // a bare number: not checked
                if (num.length() < 2 && !"%".equals(unit)) continue;     // "2 days", "1 h": too small to mean anything
                String key = num + (unit == null ? "" : unit.toLowerCase(Locale.ROOT));
                if (!seen.add(key)) continue;
                if (ev.contains(num)) continue;
                out.add(num + (unit == null ? "" : " " + unit) + " — not in any note or source read this run: \"" + Acquisitions.compress(sentence.strip(), 140) + "\"");
            }
        }
        return out;
    }

    /** Licence names and CVE ids the answer states that the evidence never does. */
    public static List<String> namesUnbacked(String answer, String evidence) { return namesUnbacked(answer, evidence, List.of(), java.util.Map.of()); }

    public static List<String> namesUnbacked(String answer, String notes, List<CiteCheck.Ref> refs, java.util.Map<Integer, String> textByRef) {
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
    static java.util.List<String> products(String sentence) {
        java.util.List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\b([A-Z][\\p{L}\\p{N}.-]{2,})\\b").matcher(sentence);
        while (m.find()) { String w = m.group(1); if (!Set.of("The", "This", "That", "These", "Those", "Its", "Both", "MIT", "AGPL", "GPL", "LGPL", "Apache", "BSD", "MPL", "CVE").contains(w) && !out.contains(w)) out.add(w); }
        return out;
    }

    /** The lines of the folded evidence that name any of {@code names} (case folded); "" when none does. */
    static String linesNaming(String evFolded, java.util.List<String> names) {
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
            boolean found = false;
            for (String n : norms) if (!n.isEmpty() && n.contains(nq)) { found = true; break; }
            if (!found) out.add("\"" + Acquisitions.compress(q, 120) + "\" — in no source read this run");
        }
        return out;
    }
}
