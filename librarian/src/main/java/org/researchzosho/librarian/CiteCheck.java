package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cite-check at synthesis time: every sentence the report supports with a citation is read
 * against the SOURCE it cites — the captured text, not the model's memory — before the report is
 * shelved. A sentence its source does not support is marked in place and listed in the caveats; the
 * inventory crew keeps doing the same check nightly on findings, this is the same discipline at the
 * moment it matters most. The judge answers supported | unsupported | cannot-tell inside a fence.
 *
 * <p>Mapping a citation to a source is mechanical: a URL in the parenthetical matches a reference's
 * locator; otherwise the parenthetical's tokens (a surname, a year, a host word) are matched against
 * the reference list. What cannot be mapped is counted, never guessed.
 */
public final class CiteCheck {

    private static final ObjectMapper M = new ObjectMapper();
    /** How many cited sentences to read at most; 0 (the default) = every one. A turn ceiling on the ask bounds it anyway. */
    static final int MAX_CHECKS = org.researchzosho.Config.getInt("RESEARCHZOSHO_CITECHECK_MAX", 0);
    private static final Pattern CITED = Pattern.compile("([^.!?\\n]*?\\(([^()]{3,200})\\)[^.!?\\n]*[.!?])");
    private static final Pattern PAREN = Pattern.compile("\\(([^()]{3,200})\\)");
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    public record Outcome(String text, int checked, int supported, int unsupported, int unmapped, List<String> problems) { }

    /** One numbered reference: locator, resolved edition (or ""), and the words a citation might use for it. */
    public record Ref(int n, String locator, String edition, String title) {
        public Ref(int n, String locator, String edition) { this(n, locator, edition, ""); }
        Set tokens() {
            String s = (edition + " " + title + " " + locator).toLowerCase(Locale.ROOT);
            java.util.Set<String> t = new java.util.HashSet<>();
            Matcher m = Pattern.compile("[\\p{L}]{4,}|\\b(1[5-9]\\d\\d|20\\d\\d)\\b").matcher(s);
            while (m.find()) t.add(m.group());
            try { String h = java.net.URI.create(locator).getHost(); if (h != null) for (String p : h.split("\\.")) if (p.length() > 3 && !p.equals("www")) t.add(p); } catch (Exception ignored) { }
            return new Set(t);
        }
        record Set(java.util.Set<String> words) { }
    }

    private CiteCheck() { }

    /** Which reference a parenthetical names, or null. */
    static Ref map(String cite, List<Ref> refs) {
        Matcher u = URL.matcher(cite);
        if (u.find()) {
            String url = u.group().replaceAll("[)\\].,;]+$", "");
            for (Ref r : refs) if (r.locator().equals(url) || r.locator().startsWith(url) || url.startsWith(r.locator())) return r;
        }
        // a shelved file is cited by its name — "(glossary.md)", "(guardrails.md, p.2)" — one word, but an exact one
        Matcher fn = Pattern.compile("([^\\s,;()]+\\.(?:md|pdf|txt|docx|pptx|odt|epub|html?|rst|tex))", Pattern.CASE_INSENSITIVE).matcher(cite);
        while (fn.find()) {
            String name = fn.group(1).toLowerCase(Locale.ROOT);
            for (Ref r : refs) {
                String loc = r.locator().toLowerCase(Locale.ROOT);
                if (loc.endsWith("/" + name) || loc.equals(name)) return r;
            }
        }
        Matcher num = Pattern.compile("^\\s*\\[?(\\d{1,3})\\]?\\s*$").matcher(cite);
        if (num.matches()) { int n = Integer.parseInt(num.group(1)); for (Ref r : refs) if (r.n() == n) return r; }
        // a source named by its site — "(soumu.go.jp)", "(the-decoder.de)", "(de.wikipedia:Belege)" — maps to the one reference on that
        // host, or to the wiki page it names (measured, I-0020: 51 parentheticals of this shape named no source)
        Matcher wiki = Pattern.compile("\\b([a-z]{2,3})\\.wikipedia:([^\\s,;()]+)").matcher(cite.toLowerCase(Locale.ROOT));
        if (wiki.find()) {
            String page = wiki.group(2).replace(' ', '_');
            for (Ref r : refs) { String loc = r.locator().toLowerCase(Locale.ROOT); if (loc.contains(wiki.group(1) + ".wikipedia.org/wiki/") && loc.contains(page)) return r; }
        }
        Matcher hostM = Pattern.compile("\\b([a-z0-9-]+(?:\\.[a-z0-9-]+)+\\.[a-z]{2,})\\b").matcher(cite.toLowerCase(Locale.ROOT));
        while (hostM.find()) {
            String h = hostM.group(1).replaceFirst("^www\\.", "");
            if (h.matches(".*\\.(md|pdf|txt|html?)$")) continue;
            Ref only = null; int hits = 0;
            for (Ref r : refs) {
                String rh = hostOf(r.locator());
                if (rh != null && (rh.equals(h) || rh.endsWith("." + h))) { hits++; only = r; }
            }
            if (hits == 1) return only;   // several references on one host: the words below must decide
        }
        java.util.Set<String> words = new java.util.HashSet<>();
        Matcher w = Pattern.compile("[\\p{L}]{4,}|\\b(1[5-9]\\d\\d|20\\d\\d)\\b").matcher(cite.toLowerCase(Locale.ROOT));
        while (w.find()) words.add(w.group());
        words.removeAll(java.util.Set.of("et al", "and", "the", "with"));
        // a token that most references share ("radiocarbon" across a radiocarbon question) identifies nothing
        Map<String, Integer> df = new java.util.HashMap<>();
        for (Ref r : refs) for (String t : r.tokens().words()) df.merge(t, 1, Integer::sum);
        Ref best = null; int bestScore = 0; boolean tie = false;
        for (Ref r : refs) {
            int score = 0;
            for (String t : r.tokens().words()) {
                if (!words.contains(t)) continue;
                if (refs.size() >= 4 && df.getOrDefault(t, 0) * 100 / refs.size() > 30) continue;
                score += t.matches("\\d{4}") ? 1 : 2;
            }
            if (score > bestScore) { bestScore = score; best = r; tie = false; }
            else if (score == bestScore && score > 0) tie = true;
        }
        return bestScore >= 3 && !tie ? best : null;   // a rare word and a year, or two rare words — never one word
    }

    static String hostOf(String locator) {
        try { if (locator == null || !locator.contains("://")) return null; String h = java.net.URI.create(locator.strip()).getHost(); return h == null ? null : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""); }
        catch (Exception e) { return null; }
    }

    /** Check {@code text} (the report's sections) against {@code refs}; returns the annotated text and the tally. */
    public static Outcome run(LibraryStore store, String text, List<Ref> refs, Researcher.Drive judge, Researcher.Budget budget) {
        List<String> problems = new ArrayList<>();
        int checked = 0, supported = 0, unsupported = 0, unmapped = 0;
        Map<String, String> verdictBySentence = new LinkedHashMap<>();
        Matcher m = CITED.matcher(text);
        while (m.find() && (MAX_CHECKS <= 0 || checked < MAX_CHECKS)) {
            String sentence = m.group(1).strip();
            if (sentence.startsWith("##") || sentence.length() < 25) continue;
            // the citation is the last parenthetical of the sentence; try it first, then the others
            List<String> cites = new ArrayList<>();
            Matcher pm = PAREN.matcher(sentence);
            while (pm.find()) cites.add(0, pm.group(1));
            Ref ref = null;
            for (String cite : cites) { ref = map(cite, refs); if (ref != null) break; }
            if (ref == null) { unmapped++; continue; }
            String source;
            try {
                Path p = RawCapture.find(store, ref.locator());
                if (p == null) { unmapped++; continue; }
                source = RawCapture.read(p)[2];
            } catch (Exception e) { unmapped++; continue; }
            if (!budget.take()) { problems.add("cite-check stopped after " + checked + " check(s): the ask's budget is spent; " + (unmapped) + " unmapped so far"); break; }
            checked++;
            String verdict = judge(judge, sentence, excerpt(source, sentence));
            if (verdict.equals("supported")) supported++;
            else if (verdict.equals("unsupported")) { unsupported++; verdictBySentence.put(sentence, "unsupported"); problems.add("not supported by " + ref.locator() + ": " + Acquisitions.compress(sentence, 120)); }
            // cannot-tell: neither counted nor marked — an excerpt can miss the passage
        }
        String out = text;
        for (Map.Entry<String, String> e : verdictBySentence.entrySet()) {
            out = out.replace(e.getKey(), e.getKey() + " [not supported by the cited source on check]");
        }
        return new Outcome(out, checked, supported, unsupported, unmapped, problems);
    }

    static String judge(Researcher.Drive judge, String sentence, String source) {
        try {
            ArrayNode msgs = M.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "A report sentence cites the SOURCE below. Does the source support the sentence as written?\n\nSENTENCE:\n" + sentence
                    + "\n\nSOURCE (excerpt):\n" + Fence.wrap("SOURCE TEXT", source) + "\n" + Fence.rule("SOURCE TEXT")
                    + "\n\nAnswer with JSON only: {\"verdict\": \"supported|unsupported|cannot-tell\"}. supported = the source states or "
                    + "clearly implies it; unsupported = the source contradicts it or says something materially different; "
                    + "cannot-tell = the excerpt does not cover it.");
            String raw = judge.classify(msgs, 60);
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            if (a < 0 || b <= a) return "cannot-tell";
            JsonNode j = M.readTree(raw.substring(a, b + 1));
            String v = j.path("verdict").asText("cannot-tell").toLowerCase(Locale.ROOT).strip();
            return v.equals("supported") || v.equals("unsupported") ? v : "cannot-tell";
        } catch (Exception e) {
            return "cannot-tell";
        }
    }

    /** The part of a long source most likely to bear on the sentence: windows around its rarest words, capped. */
    static String excerpt(String source, String sentence) {
        if (source.length() <= 6000) return source;
        java.util.List<String> words = new ArrayList<>();
        Matcher w = Pattern.compile("[\\p{L}\\p{N}]{5,}").matcher(sentence.toLowerCase(Locale.ROOT));
        while (w.find()) words.add(w.group());
        String lower = source.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(source.substring(0, 800)).append("\n…\n");
        java.util.Set<Integer> starts = new java.util.TreeSet<>();
        for (String word : words) {
            int i = lower.indexOf(word);
            int hits = 0;
            while (i >= 0 && hits++ < 2) { starts.add(Math.max(0, i - 400)); i = lower.indexOf(word, i + 1); }
        }
        int last = -1;
        for (int s : starts) {
            if (s < last) continue;
            sb.append(source, s, Math.min(source.length(), s + 900)).append("\n…\n");
            last = s + 900;
            if (sb.length() > 6000) break;
        }
        return sb.toString();
    }
}
