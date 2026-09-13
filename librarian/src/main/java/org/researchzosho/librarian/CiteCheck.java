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
    /** Abbreviations whose period ends no sentence. */
    private static final java.util.Set<String> ABBREV = java.util.Set.of("al", "e.g", "i.e", "cf", "vs", "etc", "no", "pp", "fig", "vol", "ca", "approx");

    /**
     * The sentences of a text, each with its citation parentheticals — split on . ! ? at paren depth zero followed by
     * space or the end, never inside a parenthetical (a cited URL carries dots) and never after "et al." or "e.g.".
     */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == '\n') { if (i > start) out.add(text.substring(start, i)); start = i + 1; depth = 0; }
            else if ((c == '.' || c == '!' || c == '?') && depth == 0) {
                boolean end = i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1));
                if (!end) continue;
                if (c == '.') {   // an abbreviation's period
                    int w = i; while (w > start && Character.isLetter(text.charAt(w - 1))) w--;
                    String word = text.substring(w, i).toLowerCase(Locale.ROOT);
                    if (word.length() == 1 || ABBREV.contains(word) || (w > start + 1 && text.charAt(w - 1) == '.' && ABBREV.contains(text.substring(Math.max(start, w - 2), i).toLowerCase(Locale.ROOT)))) continue;
                }
                out.add(text.substring(start, i + 1)); start = i + 1;
            }
        }
        if (start < text.length()) out.add(text.substring(start));
        return out;
    }
    private static final Pattern PAREN = Pattern.compile("\\(([^()]{3,200})\\)");
    /** "[3]", "[3][7]", "[3, 7]": the numbers the writer copied from the SOURCES list. */
    static final Pattern BRACKET = Pattern.compile("\\[(\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*)\\]");
    /** Every citation marker in a sentence, in order: a parenthetical or a bracketed number. */
    record Marker(int start, int end, String inner) { }
    static List<Marker> markers(String sentence) {
        List<Marker> out = new ArrayList<>();
        Matcher p = PAREN.matcher(sentence); while (p.find()) out.add(new Marker(p.start(), p.end(), p.group(1)));
        Matcher b = BRACKET.matcher(sentence); while (b.find()) out.add(new Marker(b.start(), b.end(), b.group(1)));
        out.sort(java.util.Comparator.comparingInt(Marker::start));
        return out;
    }
    /** The references a sentence cites, in order, mapped; empty when none maps. */
    static List<Ref> citedRefs(String sentence, List<Ref> refs) {
        List<Ref> out = new ArrayList<>();
        for (Marker m : markers(sentence)) { Ref r = map(m.inner(), refs); if (r != null && !out.contains(r)) out.add(r); }
        return out;
    }
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    /**
     * {@code mechanical}: clauses settled without the judge (their numbers, or an eight-word run, sit in the source);
     * {@code overruled}: the judge said unsupported but the source holds the clause's numbers and a long run of its words,
     * so the judge's verdict was set aside and counted as a false negative (judges under-reward correct citations —
     * arXiv 2607.08700 — and ours did on a true sentence, 2026-09-12); {@code unretrieved}: citations of a source no
     * worker read this run, marked and not judged.
     */
    public record Outcome(String text, int checked, int supported, int unsupported, int unmapped, List<String> problems, int mechanical, int overruled, int unretrieved) {
        public Outcome(String text, int checked, int supported, int unsupported, int unmapped, List<String> problems) { this(text, checked, supported, unsupported, unmapped, problems, 0, 0, 0); }
    }

    /** The numbers a clause states: two or more digits, with any decimal part; a year counts. */
    static List<String> numbers(String clause) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![\\d.])\\d{1,3}(?:[,.]\\d{3})+(?:\\.\\d+)?|(?<![\\d.])\\d{2,}(?:\\.\\d+)?(?![\\d.])").matcher(clause);
        while (m.find()) out.add(m.group().replace(",", ""));
        return out;
    }

    /** Whether every number the clause states appears in the source (commas dropped, so 1,266 meets 1266). */
    static boolean numbersInSource(String clause, String source) {
        List<String> nums = numbers(clause);
        if (nums.isEmpty()) return false;
        String src = source.replace(",", "");
        for (String n : nums) if (!src.contains(n)) return false;
        return true;
    }

    /** Whether some run of {@code words} consecutive words of the clause appears verbatim in the source (case and punctuation aside). */
    static boolean overlapInSource(String clause, String source, int words) {
        String[] w = norm(clause).split(" ");
        if (w.length < words) return false;
        String src = norm(source);
        for (int i = 0; i + words <= w.length; i++) {
            if (src.contains(String.join(" ", java.util.Arrays.copyOfRange(w, i, i + words)))) return true;
        }
        return false;
    }

    static String norm(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip(); }

    /** The mechanical verdict for a clause against its source, before any judge: supported when its numbers all sit in the source, or an eight-word run does. */
    static String mechanical(String clause, String source) {
        if (source == null || source.isEmpty()) return "";
        List<String> nums = numbers(clause);
        if (!nums.isEmpty()) return numbersInSource(clause, source) ? "supported" : "";   // a number the source lacks is for the judge, never a pass
        return overlapInSource(clause, source, 8) ? "supported" : "";
    }

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
        Matcher num = Pattern.compile("^\\s*\\[?(\\d{1,3})\\]?(?:\\s*[,;]\\s*\\d{1,3})*\\s*$").matcher(cite);
        if (num.matches()) { int n = Integer.parseInt(num.group(1)); for (Ref r : refs) if (r.n() == n) return r; }
        // an arXiv id or a DOI in the parenthetical names the work whose locator carries it — "(arXiv 2510.20168)", "(doi:10.1000/182)"
        Matcher ax = Pattern.compile("(?<![\\d.])(\\d{4}\\.\\d{4,5})(v\\d+)?(?![\\d.])").matcher(cite);
        if (ax.find()) { String id = ax.group(1); for (Ref r : refs) { String i = Citations.identify(r.locator()); if (i != null && i.toLowerCase(Locale.ROOT).contains(id)) return r; if (r.locator().contains(id)) return r; } }
        Matcher doi = Pattern.compile("(10\\.\\d{4,9}/[^\\s,;)]+)").matcher(cite);
        if (doi.find()) { String id = doi.group(1).toLowerCase(Locale.ROOT).replaceAll("[.]+$", ""); for (Ref r : refs) { String i = Citations.identify(r.locator()); if ((i != null && i.toLowerCase(Locale.ROOT).contains(id)) || r.locator().toLowerCase(Locale.ROOT).contains(id)) return r; } }
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
        // a rare word and a year, or two rare words — never one word. An acronym and a year ("SIGIR 2025") is a venue, not a work:
        // it named the wrong paper (2026-09-12), so it needs two rare words like anything else
        boolean acronymOnly = true;
        for (String tok : cite.split("[^\\p{L}]+")) if (tok.length() >= 3 && !tok.equals(tok.toUpperCase(Locale.ROOT))) { acronymOnly = false; break; }
        return bestScore >= (acronymOnly ? 4 : 3) && !tie ? best : null;
    }

    static String hostOf(String locator) {
        try { if (locator == null || !locator.contains("://")) return null; String h = java.net.URI.create(locator.strip()).getHost(); return h == null ? null : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""); }
        catch (Exception e) { return null; }
    }

    /**
     * Check {@code text} (the report's sections) against {@code refs}; returns the annotated text and the tally.
     *
     * <p>The unit read is the CLAUSE a citation closes — from the sentence's start, or the previous citation, up to the
     * parenthetical — not the whole sentence. A sentence that runs "(a) … (Read AI, 159,870 meetings); (b) no evidence
     * was found …; (c) …" cites one source for its first clause only; read whole against that source it was marked
     * unsupported for claims it never attributed to it (dolores, I-0002, 2026-09-11). A clause found unsupported is
     * marked right after its citation.
     */
    public static Outcome run(LibraryStore store, String text, List<Ref> refs, Researcher.Drive judge, Researcher.Budget budget) { return run(store, text, refs, judge, budget, java.util.Set.of()); }

    /** {@code unread}: the reference numbers no worker noted or read this run (the writer's own URLs); only those are marked "not read". */
    public static Outcome run(LibraryStore store, String text, List<Ref> refs, Researcher.Drive judge, Researcher.Budget budget, java.util.Set<Integer> unread) {
        List<String> problems = new ArrayList<>();
        int checked = 0, supported = 0, unsupported = 0, unmapped = 0, mechanical = 0, overruled = 0, unretrieved = 0;
        Map<String, String> marked = new LinkedHashMap<>();   // sentence → the sentence with its markers
        boolean stopped = false;
        boolean inSources = false;
        for (String raw : sentences(text)) {
            if (stopped || (MAX_CHECKS > 0 && checked >= MAX_CHECKS)) break;
            String sentence = raw.strip();
            if (sentence.startsWith("#")) { inSources = sentence.toLowerCase(Locale.ROOT).matches("#+\\s*(sources|references|bibliography|further reading)\\b.*"); continue; }
            if (inSources || sentence.length() < 25) continue;
            if (sentence.matches("^\\[\\d{1,3}\\]\\s*\\S*://.*") || sentence.matches("^(\\[\\d{1,3}\\]\\s*[^|]*\\|\\s*)+.*")) continue;   // a reference line, or a "[1] url | [2] url" list
            List<Marker> ms = markers(sentence);
            if (ms.isEmpty()) continue;
            // every marker that names a source closes a clause; the others are asides
            List<int[]> spans = new ArrayList<>();   // [start of the clause, end of the marker]
            List<Ref> mapped = new ArrayList<>();
            int clauseStart = 0;
            for (Marker mk : ms) {
                Ref ref = map(mk.inner(), refs);
                if (ref == null) continue;
                spans.add(new int[]{clauseStart, mk.end()});
                mapped.add(ref);
                clauseStart = mk.end();
            }
            if (mapped.isEmpty()) { unmapped++; continue; }
            StringBuilder out = new StringBuilder();
            int copied = 0;
            for (int i = 0; i < mapped.size(); i++) {
                Ref ref = mapped.get(i);
                int[] span = spans.get(i);
                String clause = sentence.substring(span[0], span[1]).replaceFirst("^[\\s;:,—–-]+", "").strip();   // the clause, not the joint before it
                if (clause.length() < 25 && mapped.size() > 1) clause = sentence.substring(0, span[1]).strip();   // a short clause reads with what leads to it
                String source;
                try {
                    Path p = RawCapture.find(store, ref.locator());
                    if (p == null) {
                        // no capture: a source a worker noted from a search result but never fetched cannot be checked — unmapped, unmarked
                        // (five of six "not read" marks fell on sources with quoted evidence rows, 2026-09-12). Only the writer's own URL,
                        // one no worker noted or read, is marked (jmlon's verify.py drops these before any model call).
                        if (!unread.contains(ref.n())) { unmapped++; continue; }
                        unretrieved++;
                        problems.add("cites a source that was not read this run: " + ref.locator() + " — " + Acquisitions.compress(clause, 100));
                        int at = span[1] < sentence.length() && span[1] == sentence.length() - 1 && ".!?".indexOf(sentence.charAt(span[1])) >= 0 ? span[1] + 1 : span[1];
                        out.append(sentence, copied, at).append(" [cites a source that was not read this run]");
                        copied = at;
                        continue;
                    }
                    source = RawCapture.read(p)[2];
                } catch (Exception e) { unmapped++; continue; }
                // the mechanical pass first: numbers that sit in the source, or an eight-word run of the clause, settle it at no cost
                String mech = mechanical(clause, source);
                if (mech.equals("supported")) { checked++; supported++; mechanical++; continue; }
                if (!budget.take()) { problems.add("cite-check stopped after " + checked + " check(s): the ask's budget is spent; " + (unmapped) + " unmapped so far"); stopped = true; break; }
                checked++;
                String verdict = judge(judge, clause, excerpt(source, clause));
                if (verdict.equals("unsupported") && !numbers(clause).isEmpty() && numbersInSource(clause, source) && overlapInSource(clause, source, 5)) {
                    // the judge's no against the source's own words: set aside, counted as a false negative, not marked
                    overruled++; verdict = "supported";
                    problems.add("judge overruled (the source holds the clause's numbers and words): " + Acquisitions.compress(clause, 100));
                } else if (verdict.equals("unsupported") && budget.take()) {
                    // a second opinion that must QUOTE: a paraphrase the first read missed is found by asking for the passage, and the
                    // passage is checked verbatim against the source — 8 of 20 marks were wrong, 4 on text on the page (2026-09-12)
                    String quote = supportingQuote(judge, clause, excerpt(source, clause));
                    if (!quote.isEmpty() && overlapInSource(quote, source, Math.min(5, Math.max(3, norm(quote).split(" ").length)))) {
                        overruled++; verdict = "supported";
                        problems.add("judge overruled on a second read (the source says: \"" + Acquisitions.compress(quote, 100) + "\"): " + Acquisitions.compress(clause, 80));
                    }
                }
                if (verdict.equals("supported")) supported++;
                else if (verdict.equals("unsupported")) {
                    unsupported++;
                    problems.add("not supported by " + ref.locator() + ": " + Acquisitions.compress(clause, 120));
                    // the marker follows the citation it failed; a citation that closes the sentence keeps its full stop first
                    int at = span[1] < sentence.length() && span[1] == sentence.length() - 1 && ".!?".indexOf(sentence.charAt(span[1])) >= 0 ? span[1] + 1 : span[1];
                    out.append(sentence, copied, at).append(" [not supported by the cited source on check]");
                    copied = at;
                }
                // cannot-tell: neither counted nor marked — an excerpt can miss the passage
            }
            if (copied > 0) { out.append(sentence.substring(copied)); marked.put(sentence, out.toString()); }
            // ") ." — a citation closing a sentence whose stop sits after it — has the marker right after the stop, so the old
            // shape ("(Foropoulos 2026). [not supported …]") is what a reader and the tests see
        }
        String out = text;
        for (Map.Entry<String, String> e : marked.entrySet()) out = out.replace(e.getKey(), e.getValue());
        return new Outcome(out, checked, supported, unsupported, unmapped, problems, mechanical, overruled, unretrieved);
    }

    /** The passage of the source that supports the sentence, verbatim, or "" when the judge finds none. Checked against the source by the caller. */
    static String supportingQuote(Researcher.Drive judge, String sentence, String source) {
        try {
            ArrayNode msgs = M.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "A report sentence cites the SOURCE below. If the source states or implies the sentence, copy the ONE passage (up to 40 words) "
                    + "that shows it, exactly as written. If nothing in the source does, answer none.\n\nSENTENCE:\n" + sentence
                    + "\n\nSOURCE (excerpt):\n" + Fence.wrap("SOURCE TEXT", source) + "\n" + Fence.rule("SOURCE TEXT")
                    + "\n\nAnswer with JSON only: {\"quote\": \"<the passage, or none>\"}");
            String raw = judge.classify(msgs, 160);
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            if (a < 0 || b <= a) return "";
            String q = M.readTree(raw.substring(a, b + 1)).path("quote").asText("").strip();
            return q.equalsIgnoreCase("none") || q.length() < 12 ? "" : q;
        } catch (Exception e) { return ""; }
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
