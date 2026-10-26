package org.researchzosho.librarian;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The analysis layer of a dossier report (0.6.6): what the facts add up to, kept apart from the facts. A report on a person or an
 * organisation ends with Judgments (inference, each sentence citing its claims), Conjectures (what the record suggests, each with a
 * likelihood word and number from {@link Likelihood}, its confidence stated apart, the alternatives and what would change it), Forecasts
 * (dated, with a base rate), Implications and options, the questions that would change the view, and a source summary. This reads those
 * sections, files the forecasts on the ledger ({@link Forecasts}) and the questions on the frontier, and cuts the layer out of what the
 * extractor reads: a conjecture or a forecast never becomes a claim on the shelves.
 */
public final class Analysis {
    private Analysis() { }

    /** The layer's sections, in the order the writer is told. */
    public static final String[] SECTIONS = {"Judgments", "Conjectures", "Forecasts", "Implications and options", "What would change this view", "Source summary"};

    /** What the writer of a person's or an organisation's report is told about the layer. */
    public static final String WRITER_RULES = "After the facts, the analysis, in its own sections and apart from them, in this order: 'Judgments' (what the facts add up to, "
            + "each sentence citing the claims it rests on, no new facts); 'Conjectures' (what the record suggests but does not show, one a line: "
            + "C1. The conjecture. Likelihood: word (NN%). Confidence: high, moderate or low, because of what the sources are. Basis: [n]. Alternatives: the other "
            + "explanations considered. Would change this: what would raise or lower it); 'Forecasts' (what will be observable by when, one a line, where the record "
            + "supports one, else the line None the record supports: F1. By YYYY-MM: the observable event — one that will or will not have happened by that date, which a reader could bet against; a filing that is due "
            + "by law, or a report that will show whatever it shows, is no forecast. Likelihood: word (NN%). Confidence: as above. Base rate: how "
            + "often this happens in such cases [n]. Would change this: the indicators); 'Implications and options' (for the leaning of the question: the reader's "
            + "options and what each depends on, as research, the reader deciding); 'What would change this view' (the questions whose answers would most change the "
            + "Reading, one a line, each ending in a question mark); 'Source summary' (the sources by class, which carry the key judgments, how they agree, the gaps). "
            + "Likelihood words and their ranges: " + Likelihood.table() + "; write the word and the number together. Confidence is about the sources and goes in its own sentence.";

    public record Conjecture(String text, String likelihood, Integer percent, String confidence, String basis, String alternatives, String indicators) { }
    public record Forecast(String by, String text, String likelihood, Integer percent, String confidence, String baseRate, String indicators) { }

    static final Pattern ITEM = Pattern.compile("^\\s*(?:\\*\\*)?(?:[CFcf]\\d+[.)]|\\d+[.)]|[-*•])(?:\\*\\*)?\\s+");
    static final Pattern LABEL = Pattern.compile("(?i)\\b(Likelihood|Confidence|Basis|Alternatives|Would change this|Indicators|Base rate)\\s*:\\s*");
    static final Pattern PERCENT = Pattern.compile("(\\d{1,3})\\s*%");
    static final Pattern BY = Pattern.compile("^(?i)(?:by|before|during|in|within)\\s+([^:]{2,40}?)\\s*:\\s*");

    static String section(String body, String name) { return TimelineClaims.section(body, name); }

    /** The items of a section: one a line when the writer wrote them so (C1., F1., 1., -), else one a paragraph. */
    static List<String> items(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        List<String> lines = new ArrayList<>(); int marked = 0;
        for (String l : text.split("\n")) { if (l.isBlank()) { lines.add(""); continue; } lines.add(l); if (ITEM.matcher(l).find()) marked++; }
        if (marked >= 1) {
            StringBuilder cur = null;
            for (String l : lines) {
                if (ITEM.matcher(l).find()) { if (cur != null) out.add(cur.toString()); cur = new StringBuilder(ITEM.matcher(l).replaceFirst("")); }
                else if (cur != null && !l.isEmpty()) cur.append(' ').append(l.strip());
                else if (cur == null && !l.isEmpty()) { cur = new StringBuilder(l.strip()); }
            }
            if (cur != null) out.add(cur.toString());
        } else for (String p : text.split("\n\\s*\n")) if (!p.isBlank()) out.add(p.replace("\n", " ").strip());
        List<String> kept = new ArrayList<>();
        for (String s : out) { String t = s.strip(); if (t.length() >= 8 && !t.toLowerCase(Locale.ROOT).startsWith("none ")) kept.add(t); }
        return kept;
    }

    /** An item's statement and its labelled parts: "statement. Likelihood: … Confidence: …" → {"" → statement, "likelihood" → …}. */
    static Map<String, String> fields(String item) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = LABEL.matcher(item);
        List<int[]> at = new ArrayList<>(); List<String> names = new ArrayList<>();
        while (m.find()) { at.add(new int[]{m.start(), m.end()}); names.add(m.group(1).toLowerCase(Locale.ROOT)); }
        out.put("", trim(at.isEmpty() ? item : item.substring(0, at.get(0)[0])));
        for (int i = 0; i < at.size(); i++) {
            String v = item.substring(at.get(i)[1], i + 1 < at.size() ? at.get(i + 1)[0] : item.length());
            out.merge(names.get(i), trim(v), (a, b) -> a.isEmpty() ? b : a);
        }
        return out;
    }
    static String trim(String s) { return s == null ? "" : s.strip().replaceAll("^[\\s;,.—–-]+|[\\s;,.—–-]+$", "").strip(); }
    static String likelihoodOf(Map<String, String> f) {
        String v = f.getOrDefault("likelihood", "");
        Likelihood l = Likelihood.ofWords(v);
        if (l == null) { Integer p = percentOf(f); if (p != null) l = Likelihood.ofPercent(p); }
        return l == null ? v : l.term;
    }
    static Integer percentOf(Map<String, String> f) {
        Matcher p = PERCENT.matcher(f.getOrDefault("likelihood", ""));
        boolean hit = p.find();
        if (!hit) { p = PERCENT.matcher(f.getOrDefault("", "")); hit = p.find(); }
        if (!hit) return null;
        int n = Integer.parseInt(p.group(1)); return n < 0 || n > 100 ? null : n;
    }
    static String confidenceOf(Map<String, String> f) {
        String v = f.getOrDefault("confidence", "").toLowerCase(Locale.ROOT);
        for (String w : new String[]{"high", "moderate", "medium", "low"}) if (v.startsWith(w) || v.matches("(?s).*\\b" + w + "\\b.*")) return w.equals("medium") ? "moderate" : w;
        return f.getOrDefault("confidence", "");
    }
    static String indicatorsOf(Map<String, String> f) { String w = f.getOrDefault("would change this", ""); return w.isEmpty() ? f.getOrDefault("indicators", "") : w; }

    public static List<Conjecture> conjectures(String body) {
        List<Conjecture> out = new ArrayList<>();
        for (String item : items(section(body, "Conjectures"))) {
            Map<String, String> f = fields(item);
            if (f.get("").isEmpty()) continue;
            out.add(new Conjecture(f.get(""), likelihoodOf(f), percentOf(f), confidenceOf(f), f.getOrDefault("basis", ""), f.getOrDefault("alternatives", ""), indicatorsOf(f)));
        }
        return out;
    }

    public static List<Forecast> forecasts(String body) {
        List<Forecast> out = new ArrayList<>();
        for (String item : items(section(body, "Forecasts"))) {
            Map<String, String> f = fields(item);
            String text = f.get(""), by = "";
            Matcher b = BY.matcher(text);
            if (b.find()) { by = b.group(1).strip(); text = trim(text.substring(b.end())); }
            if (text.isEmpty()) continue;
            out.add(new Forecast(by, text, likelihoodOf(f), percentOf(f), confidenceOf(f), f.getOrDefault("base rate", ""), indicatorsOf(f)));
        }
        return out;
    }

    /** The questions that would change the view: the items of that section that end in a question mark. */
    public static List<String> questions(String body) {
        List<String> out = new ArrayList<>();
        for (String item : items(section(body, "What would change this view"))) {
            String q = item.replaceAll("\\s*\\[\\d+\\]", "").strip();
            if (q.endsWith("?") && !out.contains(q)) out.add(q);
        }
        return out;
    }

    static final Pattern REF_LINE = Pattern.compile("(?m)^\\[(\\d+)\\] (.*?)\\s*\\[(public record|own publication|third-party|supplied|told|canon)\\]");
    static final Pattern CITE_CHECK = Pattern.compile("cite-check: (\\d+) cited sentence\\(s\\) read against their source — (\\d+) supported, (\\d+) not supported");

    /** A source summary the library writes when the report carries none: the references by class, what the Reading rests on, how the cite-check went, what was looked for and not found. "" when the report has no classed references. */
    public static String sourceSummary(String body) {
        String refs = section(body, "References");
        if (refs.isEmpty()) return "";
        Map<String, Integer> byClass = new LinkedHashMap<>(); Map<String, String> titles = new LinkedHashMap<>(); int n = 0;
        Matcher m = REF_LINE.matcher(refs);
        while (m.find()) {
            n++; byClass.merge(m.group(3), 1, Integer::sum);
            String t = m.group(2); int dash = t.indexOf(" — "); if (dash > 0) t = t.substring(0, dash); else { int sp = t.indexOf("  "); if (sp > 0) t = t.substring(0, sp); }   // "Title — URL  (published …)" → the title
            titles.put(m.group(1), t.strip());
        }
        if (n == 0) return "";
        StringBuilder b = new StringBuilder(n + (n == 1 ? " reference" : " references"));
        List<String> parts = new ArrayList<>(); for (var e : byClass.entrySet()) parts.add(e.getValue() + " " + e.getKey());
        b.append(" (").append(String.join(", ", parts)).append(").");
        String answer = section(body, "Answer");
        List<String> cited = new ArrayList<>(); Matcher c = Pattern.compile("\\[(\\d+)\\]").matcher(answer);
        while (c.find()) if (!cited.contains(c.group(1)) && titles.containsKey(c.group(1))) cited.add(c.group(1));
        if (!cited.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String k : cited) { names.add("[" + k + "] " + Acquisitions.compress(titles.get(k), 60)); if (names.size() == 5) break; }
            b.append(" The Reading rests on ").append(String.join("; ", names)).append(cited.size() > 5 ? " and " + (cited.size() - 5) + " more." : ".");
        }
        Matcher k = CITE_CHECK.matcher(section(body, "Cite-check"));
        if (k.find()) b.append(" Of ").append(k.group(1)).append(" cited sentences, ").append(k.group(2)).append(" were supported by their source and ").append(k.group(3)).append(" were not.");
        long gaps = section(body, "Looked for and not found").lines().filter(l -> !l.isBlank()).count();
        if (gaps > 0) b.append(" Looked for and not found: ").append(gaps).append(gaps == 1 ? " search." : " searches.");
        return b.toString();
    }

    /** Whether a sentence stands in the analysis layer: the writer's own likelihoods and base rates are not numbers a source has to state. */
    public static boolean inLayer(String body, String sentence) {
        if (body == null || sentence == null || sentence.isBlank()) return false;
        String s = sentence.strip();
        for (String name : SECTIONS) if (section(body, name).contains(s)) return true;
        return section(body, "Caveats").contains(s);   // the writer's own account of the run is not a claim about the world
    }

    /** The report without its analysis layer: what the extractor reads, so that a conjecture or a forecast never becomes a claim. */
    public static String withoutAnalysis(String body) {
        if (body == null || body.isEmpty()) return body;
        String b = body;
        for (String name : SECTIONS) {
            int at = ("\n" + b).indexOf("\n## " + name + "\n");
            if (at < 0) continue;
            int end = b.indexOf("\n## ", at + 1);
            b = b.substring(0, at) + (end < 0 ? "" : b.substring(end));
        }
        return b;
    }

    /** At a report's landing: its forecasts to the ledger, its view-changing questions to the frontier; what was done, for the log. */
    public static List<String> file(LibraryStore store, Investigation inv, String subject) throws IOException {
        List<String> did = new ArrayList<>();
        List<String> ids = Forecasts.file(store, inv, subject, forecasts(inv.body()));
        if (!ids.isEmpty()) did.add(ids.size() + (ids.size() == 1 ? " forecast" : " forecasts") + " to the ledger (" + String.join(", ", ids) + ")");
        int q = 0;
        for (String question : questions(inv.body())) if (Frontier.demand(store, question, "analysis " + subject)) q++;
        if (q > 0) did.add(q + (q == 1 ? " question" : " questions") + " that would change the view, to the frontier");
        if (!did.isEmpty()) store.circulate("analysis", inv.id() + ": " + String.join("; ", did));
        return did;
    }
}
