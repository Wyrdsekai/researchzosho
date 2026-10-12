package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The measured layer of a book's DNA (version {@code measured-0.1}): what a program counts from the whole text, no model. Length and
 * chapters; sentence length and its spread; the share of words inside quotation marks; the vocabulary (type–token, MTLD); a readability
 * grade; first against third person and past against present tense by small word lists; connectives, -ly adverbs and adjective
 * suffixes; content words against stop words; the share of strongly and of negatively valued words by the VADER lexicon (MIT, Hutto and
 * Gilbert); and the sentiment arc as the lexicon mean per hundredth of the book, with its mean, spread, extremes, start, ending, Hurst
 * exponent and approximate entropy. A Japanese text gets its own short set: sentence length in characters, kanji density, 「」 dialogue.
 * Measured over 74,690 books on the operator's shelf, the layer tells a book's form (98 % nearer its own author than a stranger) and
 * nothing of its subject; it serves "written like X" and stays out of "like X" (docs/REC_BOOKS.md). A port of dna_measured.py, tested
 * against its numbers.
 */
public final class Measured {
    private Measured() { }

    public static final String VERSION = "measured-0.1";
    static final ObjectMapper M = new ObjectMapper();

    static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z'’-]*|[\\u3040-\\u30FF\\u4E00-\\u9FFF]+");
    static final Pattern CJK_START = Pattern.compile("^[\\u3040-\\u30FF\\u4E00-\\u9FFF]");
    static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])[\"”’)]*\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    static final Pattern QUOTED = Pattern.compile("[“\"]([^”\"]{1,2000})[”\"]");
    static final Pattern JA_SENTENCE_END = Pattern.compile("[。！？]");
    static final Pattern KANJI = Pattern.compile("[\\u4E00-\\u9FFF]");
    static final Pattern CJK_CHAR = Pattern.compile("[\\u3040-\\u30FF\\u4E00-\\u9FFF]");
    static final Pattern JA_QUOTED = Pattern.compile("「([^」]*)」");
    static final Pattern VOWELS = Pattern.compile("[aeiouy]+");
    static final Pattern LATIN_START = Pattern.compile("^[A-Za-z]");

    static final Set<String> STOP = Set.of(("a an the and or but if while of at by for with about against between into through during before after above below to from up down in out on off over under "
            + "again further then once here there when where why how all any both each few more most other some such no nor not only own same so than too very s t can will just don should now "
            + "i me my myself we our ours ourselves you your yours yourself yourselves he him his himself she her hers herself it its itself they them their theirs themselves what which who whom this that these those "
            + "am is are was were be been being have has had having do does did doing would could ought shall might must as until because what's it's that's").split(" "));
    static final Set<String> FIRST = Set.of("i", "me", "my", "mine", "myself", "we", "us", "our", "ours", "ourselves");
    static final Set<String> THIRD = Set.of("he", "him", "his", "himself", "she", "her", "hers", "herself", "they", "them", "their", "theirs", "themselves");
    static final Set<String> PAST_IRREG = Set.of(("was were had did said went came saw took got made knew thought told felt left found gave began stood sat ran held heard kept brought put looked turned seemed asked became meant set spoke wrote fell lay led met paid read rose sent shook shut sought spent struck swung threw understood wore won drew drove ate chose flew forgot grew hid hung lost rode sang slept sold stole swam taught woke caught bought fought built cut hit let lit slid spun stuck swept bent bled bred dealt dug fed fled ground lent shone shot sped spread sprang stung strode tore trod wound wept bit blew broke froze laid leapt shed sank sewed tied").split(" "));
    static final Set<String> PRESENT = Set.of(("is are am has do does says goes comes takes makes knows thinks tells feels leaves finds gives begins stands sits runs holds hears keeps brings puts looks turns seems asks becomes means speaks writes falls lies leads meets pays reads rises sends shakes shuts spends strikes throws wears wins draws drives eats chooses walks wants sees gets lets").split(" "));
    static final Set<String> CONNECT = Set.of(("and but because although though however while since until unless whereas therefore thus moreover nevertheless yet so then if when whenever after before as whether nor either neither also besides meanwhile otherwise instead hence consequently furthermore").split(" "));
    static final String[] ADJ_SUF = {"ous", "ive", "ful", "less", "able", "ible", "al", "ic", "ish", "ent", "ant"};

    /** The VADER lexicon: a word, its mean valence (−4 … +4). Loaded once from the resource; empty when the resource is missing. */
    static final Map<String, Double> LEX = lexicon();

    static Map<String, Double> lexicon() {
        Map<String, Double> m = new HashMap<>();
        try (InputStream in = Measured.class.getResourceAsStream("/dna/vader_lexicon.txt")) {
            if (in == null) return m;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] f = line.strip().split("\t");
                if (f.length < 2) continue;
                try { m.put(f[0], Double.parseDouble(f[1])); } catch (NumberFormatException ignored) { }
            }
        } catch (Exception ignored) { }
        return m;
    }

    static List<String> tokens(String text) { List<String> out = new ArrayList<>(); Matcher m = WORD.matcher(text); while (m.find()) out.add(m.group()); return out; }
    static int count(String text) { int n = 0; Matcher m = WORD.matcher(text); while (m.find()) n++; return n; }

    /** The features of a text given as its chapters. Values the text cannot give are null, and a text under 500 words gets a note and no more. */
    public static ObjectNode features(List<String> chapters) {
        ObjectNode f = M.createObjectNode();
        String text = String.join("\n", chapters);
        List<String> toksAll = tokens(text);
        List<String> words = new ArrayList<>(toksAll.size());
        for (String t : toksAll) words.add(t.toLowerCase(Locale.ROOT));
        int n = words.size();
        f.put("words", n);
        List<Integer> chapWords = new ArrayList<>();
        for (String c : chapters) { int cw = count(c); if (cw > 500) chapWords.add(cw); }
        f.put("chapters", chapWords.size());
        if (n < 500) { f.put("note", "too little text"); return f; }
        int cjk = 0; for (String t : toksAll) if (CJK_START.matcher(t).find()) cjk++;
        f.put("cjk_share", Py.round(cjk / (double) n, 3));
        boolean japanese = cjk / (double) n > 0.3;
        int stops = 0; for (String w : words) if (STOP.contains(w)) stops++;
        f.put("language_guess", japanese ? "ja" : stops / (double) n > 0.25 ? "en" : "other");
        if (chapWords.isEmpty()) f.putNull("chapter_words_median"); else f.put("chapter_words_median", (int) Py.median(chapWords));
        if (japanese) {
            List<String> sents = new ArrayList<>();
            for (String s : JA_SENTENCE_END.split(text, -1)) if (!s.isBlank()) sents.add(s);
            f.put("sentences", sents.size());
            if (sents.isEmpty()) f.putNull("sentence_chars_mean");
            else { List<Integer> lens = new ArrayList<>(); for (String s : sents) lens.add(s.codePointCount(0, s.length())); f.put("sentence_chars_mean", Py.round(Py.fmean(lens), 1)); }
            int kanji = 0, chars = 0;
            Matcher km = KANJI.matcher(text); while (km.find()) kanji++;
            Matcher cm = CJK_CHAR.matcher(text); while (cm.find()) chars++;
            if (chars == 0) f.putNull("kanji_density"); else f.put("kanji_density", Py.round(kanji / (double) chars, 3));
            int quoted = 0; Matcher qm = JA_QUOTED.matcher(text); while (qm.find()) quoted += qm.group(1).codePointCount(0, qm.group(1).length());
            f.put("dialogue_share", Py.round(quoted / (double) Math.max(1, text.codePointCount(0, text.length())), 3));
            return f;
        }
        List<Integer> sl = new ArrayList<>();
        for (String s : SENTENCE_END.split(text, -1)) if (WORD.matcher(s).find()) sl.add(count(s));
        f.put("sentences", sl.size());
        f.put("sentence_words_mean", Py.round(Py.fmean(sl), 1));
        f.put("sentence_words_sd", Py.round(Py.pstdev(sl), 1));
        int longs = 0; for (int v : sl) if (v > 30) longs++;
        f.put("long_sentence_share", Py.round(longs / (double) sl.size(), 3));
        int qw = 0; Matcher qm = QUOTED.matcher(text); while (qm.find()) qw += count(qm.group(1));
        f.put("dialogue_share", Py.round(qw / (double) n, 3));
        List<String> first10k = words.subList(0, Math.min(10000, n));
        f.put("type_token_10k", Py.round(new HashSet<>(first10k).size() / (double) first10k.size(), 3));
        Double mtld = mtld(words.subList(0, Math.min(50000, n)));
        if (mtld == null) f.putNull("mtld_50k"); else f.put("mtld_50k", mtld);
        long syl = 0; for (String w : words) if (LATIN_START.matcher(w).find()) syl += syllables(w);
        f.put("flesch_kincaid_grade", Py.round(0.39 * (n / (double) sl.size()) + 11.8 * (syl / (double) n) - 15.59, 1));
        int fp = 0, tp = 0; for (String w : words) { if (FIRST.contains(w)) fp++; if (THIRD.contains(w)) tp++; }
        if (fp + tp == 0) f.putNull("first_person_share"); else f.put("first_person_share", Py.round(fp / (double) (fp + tp), 3));
        int past = 0, pres = 0; for (String w : words) { if (PAST_IRREG.contains(w) || (w.length() > 4 && w.endsWith("ed"))) past++; if (PRESENT.contains(w)) pres++; }
        if (past + pres == 0) f.putNull("past_tense_share"); else f.put("past_tense_share", Py.round(past / (double) (past + pres), 3));
        int conn = 0, ly = 0, adj = 0, content = 0;
        for (String w : words) {
            if (CONNECT.contains(w)) conn++;
            if (w.length() > 4 && w.endsWith("ly")) ly++;
            if (w.length() > 5) for (String s : ADJ_SUF) if (w.endsWith(s)) { adj++; break; }
            if (!STOP.contains(w)) content++;
        }
        f.put("connective_share", Py.round(conn / (double) n, 4));
        f.put("adverb_ly_share", Py.round(ly / (double) n, 4));
        f.put("adjective_suffix_share", Py.round(adj / (double) n, 4));
        double contentShare = Py.round(content / (double) n, 3);
        f.put("content_word_share", contentShare);
        f.put("stopword_share", Py.round(1 - contentShare, 3));
        if (!LEX.isEmpty()) {
            int extreme = 0, negative = 0;
            for (String w : words) { double v = LEX.getOrDefault(w, 0.0); if (Math.abs(v) >= 2.5) extreme++; if (v <= -1.5) negative++; }
            f.put("extreme_word_share", Py.round(extreme / (double) n, 4));
            f.put("negative_word_share", Py.round(negative / (double) n, 4));
            // the arc as the lexicon mean per hundredth of the book: Reagan and colleagues' window means over VADER's valences
            int step = Math.max(1, n / 100);
            List<Double> arc = new ArrayList<>();
            for (int i = 0; i + step <= n && arc.size() < 100; i += step) {
                List<Double> vals = new ArrayList<>();
                for (String w : words.subList(i, i + step)) { Double v = LEX.get(w); if (v != null) vals.add(v); }
                arc.add(Py.round(Py.fsum(vals) / step, 4));
            }
            if (!arc.isEmpty()) {
                double mean = Py.fmean(arc);
                f.put("arc_mean", Py.round(mean, 3)); f.put("arc_sd", Py.round(Py.pstdev(arc), 3));
                double min = Double.MAX_VALUE, max = -Double.MAX_VALUE; for (double v : arc) { min = Math.min(min, v); max = Math.max(max, v); }
                f.put("arc_min", Py.round(min, 3)); f.put("arc_max", Py.round(max, 3));
                f.put("arc_end_minus_mean", Py.round(Py.fmean(arc.subList(Math.max(0, arc.size() - 10), arc.size())) - mean, 3));
                f.put("arc_start_minus_mean", Py.round(Py.fmean(arc.subList(0, Math.min(10, arc.size()))) - mean, 3));
                Double h = hurst(arc); if (h == null) f.putNull("arc_hurst"); else f.put("arc_hurst", h);
                Double a = apen(arc); if (a == null) f.putNull("arc_apen"); else f.put("arc_apen", a);
            }
        }
        return f;
    }

    static int syllables(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        int s = 0, e = w.length();
        while (s < e && "'’-".indexOf(w.charAt(s)) >= 0) s++;
        while (e > s && "'’-".indexOf(w.charAt(e - 1)) >= 0) e--;
        w = w.substring(s, e);
        int n = 0; Matcher m = VOWELS.matcher(w); while (m.find()) n++;
        if (w.endsWith("e") && !(w.endsWith("le") || w.endsWith("ee")) && n > 1) n--;
        return Math.max(1, n);
    }

    /** The measure of textual lexical diversity, forward and back, averaged; null under fifty tokens. */
    static Double mtld(List<String> tokens) {
        if (tokens.size() < 50) return null;
        List<String> back = new ArrayList<>(tokens); java.util.Collections.reverse(back);
        return Py.round((mtldRun(tokens) + mtldRun(back)) / 2, 1);
    }

    private static double mtldRun(List<String> seq) {
        double threshold = 0.72, factors = 0.0; Set<String> types = new HashSet<>(); int count = 0;
        for (String t : seq) {
            count++; types.add(t);
            if (types.size() / (double) count <= threshold) { factors += 1; types = new HashSet<>(); count = 0; }
        }
        if (count > 0) factors += (1 - types.size() / (double) count) / (1 - threshold);
        return factors != 0 ? seq.size() / factors : seq.size();
    }

    /** The Hurst exponent of the arc by rescaled range over a few window sizes; null when the arc is too short for it. */
    static Double hurst(List<Double> arc) {
        int n = arc.size();
        if (n < 20) return null;
        double mean = Py.fmean(arc);
        double[] x = new double[n]; for (int i = 0; i < n; i++) x[i] = arc.get(i) - mean;
        List<double[]> rs = new ArrayList<>();
        for (int s : new int[]{8, 10, 12, 16, 20, 25, 33, 50}) {
            if (s > n / 2) continue;
            List<Double> vals = new ArrayList<>();
            for (int i = 0; i + s <= n; i += s) {
                List<Double> seg = new ArrayList<>(); for (int j = i; j < i + s; j++) seg.add(x[j]);
                double m = Py.fmean(seg), dev = 0, mx = -Double.MAX_VALUE, mn = Double.MAX_VALUE;
                for (double v : seg) { dev += v - m; mx = Math.max(mx, dev); mn = Math.min(mn, dev); }
                double sd = Py.pstdev(seg);
                if (sd > 0) vals.add((mx - mn) / sd);
            }
            if (!vals.isEmpty()) rs.add(new double[]{Math.log(s), Math.log(Py.fmean(vals))});
        }
        if (rs.size() < 3) return null;
        double[] xs = new double[rs.size()], ys = new double[rs.size()];
        for (int i = 0; i < rs.size(); i++) { xs[i] = rs.get(i)[0]; ys[i] = rs.get(i)[1]; }
        double mx = Py.fmean(xs), my = Py.fmean(ys);
        List<Double> num = new ArrayList<>(), den = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) { num.add((xs[i] - mx) * (ys[i] - my)); den.add((xs[i] - mx) * (xs[i] - mx)); }
        double d = Py.fsum(den);
        return d != 0 ? Py.round(Py.fsum(num) / d, 3) : null;
    }

    /** Approximate entropy of the arc (m = 2, r = 0.2 sd); null when too short. */
    static Double apen(List<Double> arc) {
        int n = arc.size(), m = 2;
        if (n < m + 2) return null;
        double sd = Py.pstdev(arc);
        double r = 0.2 * (sd != 0 ? sd : 1e-9);
        double[] x = new double[n]; for (int i = 0; i < n; i++) x[i] = arc.get(i);
        try { return Py.round(phi(x, m, r) - phi(x, m + 1, r), 3); } catch (Exception e) { return null; }
    }

    private static double phi(double[] x, int mm, double r) {
        int n = x.length, count = n - mm + 1;
        List<Double> logs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int cnt = 0;
            for (int j = 0; j < count; j++) {
                double maxd = 0; for (int k = 0; k < mm; k++) maxd = Math.max(maxd, Math.abs(x[i + k] - x[j + k]));
                if (maxd <= r) cnt++;
            }
            logs.add(Math.log(cnt / (double) count));
        }
        return Py.fsum(logs) / logs.size();
    }
}
