package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.drive.DriveClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The labelled layer of a book's DNA (version {@code labelled-0.1}): what the model says of the text, asked the settled way. One question
 * about the book from an excerpt (its kind, audience, demand, themes, setting and the rest), then one question per window of 1,500 words,
 * twelve windows spread over the whole text, each judged <em>relative to an ordinary book of its kind</em> with a vocabulary per kind:
 * the readers'-advisory appeal terms for fiction, tone words for nonfiction; pace, darkness, style, scale, humour and drive as relative
 * steps; two to four topics; one plain sentence. The windows are aggregated into the book's labels: each mood's share of windows, the
 * scales' mean, spread and arc, and <em>agreement between windows as the confidence</em>, never the model's own word for it; then the
 * signature — a frequency, recency and value triple for every value of every dimension and for the topics, the dynamics (speed, turns,
 * the largest jump and where, mood switches and what follows what). Thinking is off and the parser is tolerant of a dropped quote or a
 * trailing comma. Settled on the operator's shelf after three variants (docs/REC_BOOKS.md); a port of label_run_windowed4.py and
 * label_aggregate.py, tested against their output on the same replies.
 */
public final class Labels {
    private Labels() { }

    public static final String VERSION = "labelled-0.1";
    static final ObjectMapper M = new ObjectMapper();

    /** The appeal terms for fiction (Saricks, NoveList) and the tones for nonfiction: the two vocabularies a window chooses from. */
    public static final List<String> MOODS = List.of("menacing", "suspenseful", "heart-warming", "bleak", "bittersweet", "gentle", "darkly humorous", "lyrical", "gritty", "whimsical", "haunting", "upbeat", "sardonic", "nostalgic", "violent", "romantic");
    public static final List<String> TONES = List.of("dry", "wry", "polemical", "instructional", "conversational", "earnest", "playful", "urgent", "reflective", "technical", "warm", "scholarly", "indignant", "hopeful", "bleak", "intimate");
    /** Every mood and tone word the aggregator keeps a triple for: the settled vocabularies and the earlier variants', so older replies aggregate the same. */
    static final List<String> ALL_MOODS = List.of("dark", "hopeful", "tense", "funny", "reflective", "warm", "eerie", "playful", "melancholy", "wry", "earnest", "bleak", "romantic", "adventurous", "angry", "calm",
            "dry", "polemical", "instructional", "conversational", "urgent", "technical", "scholarly", "indignant", "intimate",
            "menacing", "suspenseful", "heart-warming", "bittersweet", "gentle", "darkly humorous", "lyrical", "gritty", "whimsical", "haunting", "upbeat", "sardonic", "nostalgic", "violent");
    static final Set<String> FICTION = Set.of("novel", "stories", "poetry");
    /** The kinds the book-level question offers. */
    public static final List<String> KINDS = List.of("novel", "stories", "memoir", "history", "science", "business", "self-help", "essay", "reference", "manual", "poetry", "other");

    /** The ordinal scales: each value's step. */
    static final Map<String, Map<String, Integer>> ORD = new LinkedHashMap<>();
    static {
        ORD.put("pace", ordered("slower", -1, "about the same", 0, "faster", 1));
        ORD.put("darkness", ordered("lighter", -1, "about the same", 0, "darker", 1));
        ORD.put("style", ordered("plainer", -1, "about the same", 0, "more ornate", 1));
        ORD.put("humour", ordered("none", 0, "a little", 1, "frequent", 2));
        ORD.put("scale", ordered("one person's inner life", 0, "a few people", 1, "a community or an institution", 2, "a nation, a world or an age", 3));
    }
    static Map<String, Integer> ordered(Object... kv) { Map<String, Integer> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]); return m; }
    static final List<String> VALUE_KEYS = List.of("pace", "darkness", "style", "humour", "scale", "drive");

    public static boolean fiction(String kind) { return FICTION.contains(kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT)); }

    // ---- the questions ----

    public static final String WINDOW_SYSTEM = "You read one passage from a book and describe only this passage, judged against an ordinary book of its kind. Answer with one JSON object and nothing else.";

    /** The window question for a book of this kind: the fiction vocabulary for a novel, stories or poetry, the tones for the rest. */
    public static String windowPrompt(String kind) {
        boolean fic = fiction(kind);
        List<String> vocab = fic ? MOODS : TONES;
        String what = fic ? "moods" : "tones", ordinary = fic ? "an ordinary novel" : "an ordinary book of this kind (nonfiction)";
        return "Describe this passage against " + ordinary + ". Return a JSON object with these keys:\n"
                + "  \"mood_most\": the TWO " + what + " MORE present in this passage than in " + ordinary + ", from this list only: " + String.join(", ", vocab) + "\n"
                + "  \"mood_least\": the ONE " + what + " from the same list that is LESS present here than in " + ordinary + "\n"
                + "  \"pace\": compared with " + ordinary + ": \"slower\", \"about the same\" or \"faster\"\n"
                + "  \"darkness\": compared with " + ordinary + ": \"lighter\", \"about the same\", \"darker\"\n"
                + "  \"scale\": what this passage is about: \"one person's inner life\", \"a few people\", \"a community or an institution\", \"a nation, a world or an age\"\n"
                + "  \"humour\": \"none\", \"a little\", \"frequent\"\n"
                + "  \"style\": compared with " + ordinary + ": \"plainer\", \"about the same\", \"more ornate\"\n"
                + "  \"drive\": what carries this passage: \"character\", \"plot\", \"idea\", \"description\"\n"
                + "  \"topics\": two to four short subject words for what this passage is about, the kind a library catalogue would use\n"
                + "  \"what_happens\": one sentence, plain words\n\nTHE PASSAGE:\n";
    }

    public static final String BOOK_SYSTEM = "You read a sample of a book — its opening, a slice from the middle and its close — and describe the book itself on fixed dimensions. Judge only from the text given. Answer with one JSON object and nothing else.";

    /** The book-level question over the excerpt: the fixed dimensions the windows do not ask. */
    public static String bookPrompt(String excerpt) {
        return "Describe this book. Return a JSON object with these keys:\n"
                + "  \"pace\": one of \"fast\", \"steady\", \"leisurely\"\n"
                + "  \"mood\": two to four words of the kind: dark, hopeful, tense, funny, reflective, bleak, warm, eerie, playful, melancholy, wry, earnest\n"
                + "  \"style\": one of \"spare\", \"plain\", \"middling\", \"rich\", \"ornate\"\n"
                + "  \"drive\": one of \"character\", \"both\", \"plot\", \"idea\"\n"
                + "  \"scale\": one of \"intimate\", \"modest\", \"broad\", \"epic\"\n"
                + "  \"setting\": place and era in a few words, or 'none' for a book with no setting\n"
                + "  \"hardness\": one of \"not fantastic\", \"soft\", \"middling\", \"hard\"\n"
                + "  \"darkness\": one of \"gentle\", \"mild\", \"dark\", \"disturbing\"\n"
                + "  \"violence\": one of \"none\", \"some\", \"much\"\n"
                + "  \"explicitness\": one of \"none\", \"some\", \"much\"\n"
                + "  \"humour\": one of \"none\", \"light\", \"frequent\", \"broad\"\n"
                + "  \"demand\": one of \"easy\", \"moderate\", \"demanding\"\n"
                + "  \"audience\": one of \"children\", \"young adult\", \"adult\", \"specialist\"\n"
                + "  \"kind\": one of " + quoted(KINDS) + "\n"
                + "  \"themes\": four to eight short subject words or phrases for what the book is about, the kind a library catalogue would use (e.g. \"artificial intelligence\", \"grief\", \"colonial Virginia\", \"startups\")\n"
                + "  \"what_it_feels_like\": one sentence, plain words, for a reader deciding whether to pick it up\n"
                + "  \"confidence\": \"low\", \"medium\" or \"high\" — how far the sample let you judge\n\nTHE SAMPLE:\n" + excerpt;
    }

    static String quoted(List<String> xs) { StringBuilder b = new StringBuilder(); for (String x : xs) { if (b.length() > 0) b.append(", "); b.append('"').append(x).append('"'); } return b.toString(); }

    // ---- the parser ----

    static final Pattern OBJECT = Pattern.compile("\\{.*\\}", Pattern.DOTALL);
    static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");
    static final List<String> RESCUE_STRINGS = List.of("mood_least", "pace", "darkness", "scale", "humour", "style", "drive", "what_happens");
    static final List<String> RESCUE_LISTS = List.of("mood_most", "topics");

    /** The JSON object in a reply, trailing commas forgiven; null when there is none or it does not parse. */
    public static ObjectNode object(String raw) {
        Matcher m = OBJECT.matcher(raw == null ? "" : raw);
        if (!m.find()) return null;
        String s = TRAILING_COMMA.matcher(m.group()).replaceAll("$1");
        try { JsonNode n = M.readTree(s); return n != null && n.isObject() ? (ObjectNode) n : null; } catch (Exception e) { return null; }
    }

    /** A window reply as fields: the object when it parses, else what can be rescued field by field (the 27B drops an opening quote now and then); null when not even the moods can be read. */
    public static ObjectNode parse(String raw) {
        Matcher m = OBJECT.matcher(raw == null ? "" : raw);
        if (!m.find()) return null;
        String s = TRAILING_COMMA.matcher(m.group()).replaceAll("$1");
        try { JsonNode n = M.readTree(s); if (n != null && n.isObject()) return (ObjectNode) n; } catch (Exception ignored) { }
        ObjectNode out = M.createObjectNode();
        for (String k : RESCUE_STRINGS) { Matcher mm = Pattern.compile("\"" + k + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(s); if (mm.find()) out.put(k, mm.group(1)); }
        for (String k : RESCUE_LISTS) {
            Matcher mm = Pattern.compile("\"" + k + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(s);
            if (mm.find()) { ArrayNode a = out.putArray(k); Matcher w = Pattern.compile("\"([^\"]+)\"").matcher(mm.group(1)); while (w.find()) a.add(w.group(1)); }
        }
        return out.has("mood_most") ? out : null;
    }

    // ---- Python's reading of a field ----

    /** {@code str(d.get(key, ""))}: the text of a string; a number or boolean as Python prints it; "None" for null; "" when absent. */
    static String str(JsonNode d, String key) { JsonNode v = d.get(key); return v == null ? "" : str(v); }

    static String str(JsonNode v) {
        if (v == null || v.isMissingNode()) return "";
        if (v.isNull()) return "None";
        if (v.isTextual()) return v.asText();
        if (v.isBoolean()) return v.asBoolean() ? "True" : "False";
        if (v.isIntegralNumber()) return v.bigIntegerValue().toString();
        if (v.isNumber()) return Double.toString(v.asDouble());
        if (v.isArray()) { StringBuilder b = new StringBuilder("["); for (JsonNode x : v) { if (b.length() > 1) b.append(", "); b.append(x.isTextual() ? "'" + x.asText() + "'" : str(x)); } return b.append(']').toString(); }
        return v.toString();
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }

    /** {@code (d.get("mood_most") or [])[:2]}, each as {@code str(x).lower()}: the first two of a list, the first two characters of a string, nothing otherwise. */
    static List<String> moodMost(JsonNode d) {
        JsonNode v = d.get("mood_most");
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull()) return out;
        if (v.isArray()) { for (JsonNode x : v) { if (out.size() == 2) break; out.add(lower(str(x))); } return out; }
        if (v.isTextual()) { String s = v.asText(); for (int i = 0; i < Math.min(2, s.length()); i++) out.add(lower(s.substring(i, i + 1))); }
        return out;
    }

    /** The topics of a window, as a sorted set of {@code x.strip().lower()}: a list's items, or a string split on commas, semicolons and slashes. */
    static List<String> topics(JsonNode d) {
        JsonNode v = d.get("topics");
        Set<String> set = new TreeSet<>();
        if (v == null || v.isNull()) return List.of();
        if (v.isTextual()) { if (v.asText().isEmpty()) return List.of(); for (String x : v.asText().split("[,;/]")) if (!x.strip().isEmpty()) set.add(lower(x.strip())); }
        else if (v.isArray()) { for (JsonNode x : v) { String s = str(x); if (!s.strip().isEmpty()) set.add(lower(s.strip())); } }
        else if (v.isObject()) { v.fieldNames().forEachRemaining(k -> { if (!k.strip().isEmpty()) set.add(lower(k.strip())); }); }
        return new ArrayList<>(set);
    }

    // ---- the aggregation ----

    /** One parsed window reply at its position in the book, 0 the start and 1 the end. */
    public record Reply(double pos, ObjectNode fields) { }

    /** A counter that keeps first-seen order, so that ties come out as the tooling's did. */
    static final class Counter {
        final LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        void add(String k) { m.merge(k, 1, Integer::sum); }
        List<Map.Entry<String, Integer>> mostCommon(int n) {
            List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
            l.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());   // a stable sort: equal counts keep their order
            return l.size() > n ? l.subList(0, n) : l;
        }
    }

    /**
     * The book's labels from its windows: mood shares, the least mood, the scales (mean, spread, agreement, arc), the drive, and the
     * signature. {@code windows} is the count of parsed replies; the caller adds the book-level answers and the model's name.
     */
    public static ObjectNode aggregate(List<Reply> replies) {
        List<Reply> wins = new ArrayList<>(replies);
        wins.sort(Comparator.comparingDouble(Reply::pos));
        int k = wins.size();
        ObjectNode agg = M.createObjectNode();
        agg.put("windows", k);
        if (k == 0) return agg;
        Counter most = new Counter(), least = new Counter();
        for (Reply r : wins) { for (String m : moodMost(r.fields())) most.add(m); least.add(lower(str(r.fields(), "mood_least"))); }
        ObjectNode mood = agg.putObject("mood");
        for (Map.Entry<String, Integer> e : most.mostCommon(4)) mood.put(e.getKey(), Py.round(e.getValue() / (double) k, 2));
        agg.put("least", least.m.isEmpty() ? "" : least.mostCommon(1).get(0).getKey());
        ObjectNode scales = agg.putObject("scales");
        for (Map.Entry<String, Map<String, Integer>> sc : ORD.entrySet()) {
            List<Integer> vals = new ArrayList<>();
            for (Reply r : wins) { Integer v = sc.getValue().get(lower(str(r.fields(), sc.getKey()))); if (v != null) vals.add(v); }
            if (vals.isEmpty()) continue;
            Counter c = new Counter(); for (int v : vals) c.add(Integer.toString(v));
            ObjectNode o = scales.putObject(sc.getKey());
            o.put("mean", Py.round(Py.fmean(vals), 2)); o.put("sd", Py.round(Py.pstdev(vals), 2)); o.put("agreement", Py.round(c.mostCommon(1).get(0).getValue() / (double) vals.size(), 2));
            ArrayNode arc = o.putArray("arc"); for (int v : vals) arc.add(v);
        }
        Counter drives = new Counter(); for (Reply r : wins) drives.add(lower(str(r.fields(), "drive")));
        ArrayNode drive = agg.putArray("drive");
        for (Map.Entry<String, Integer> e : drives.mostCommon(2)) { ArrayNode pair = drive.addArray(); pair.add(e.getKey()); pair.add(e.getValue()); }
        ObjectNode sig = agg.putObject("signature");
        // the signature, RFM-style: per mood word, frequency = share of windows naming it among the two most, recency = the mean position of
        // those windows, value = named most minus named least, per window
        for (String m : ALL_MOODS) {
            List<Double> at = new ArrayList<>(); int leastN = 0;
            for (Reply r : wins) { if (moodMost(r.fields()).contains(m)) at.add(r.pos()); if (lower(str(r.fields(), "mood_least")).equals(m)) leastN++; }
            if (at.isEmpty() && leastN == 0) continue;
            ObjectNode o = sig.putObject("mood." + m);
            o.put("f", Py.round(at.size() / (double) k, 2));
            if (at.isEmpty()) o.putNull("r"); else o.put("r", Py.round(Py.fmean(at), 2));
            o.put("v", Py.round((at.size() - leastN) / (double) k, 2));
        }
        // per scale: value = the mean, consistency = 1 − spread/range, trend = the slope over position, end = the last third's mean
        for (Map.Entry<String, Map<String, Integer>> sc : ORD.entrySet()) {
            List<Double> xs = new ArrayList<>(); List<Integer> ys = new ArrayList<>();
            for (Reply r : wins) { Integer v = sc.getValue().get(lower(str(r.fields(), sc.getKey()))); if (v != null) { xs.add(r.pos()); ys.add(v); } }
            if (xs.size() < 2) continue;
            double mx = Py.fmean(xs), my = Py.fmean(ys);
            List<Double> num = new ArrayList<>(), den = new ArrayList<>();
            for (int i = 0; i < xs.size(); i++) { num.add((xs.get(i) - mx) * (ys.get(i) - my)); den.add((xs.get(i) - mx) * (xs.get(i) - mx)); }
            double d = Py.fsum(den); double slope = Py.fsum(num) / (d != 0 ? d : 1);
            int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE; for (int v : sc.getValue().values()) { max = Math.max(max, v); min = Math.min(min, v); }
            int rng = max - min != 0 ? max - min : 1;
            List<Integer> late = new ArrayList<>(); for (int i = 0; i < xs.size(); i++) if (xs.get(i) >= 0.66) late.add(ys.get(i));
            ObjectNode o = sig.putObject("scale." + sc.getKey());
            o.put("v", Py.round(my, 2)); o.put("c", Py.round(1 - Py.pstdev(ys) / rng, 2)); o.put("t", Py.round(slope, 2)); o.put("end", Py.round(late.isEmpty() ? my : Py.fmean(late), 2));
        }
        // the triple for every value of every categorical dimension ("humour is not always the entire book"), and for the topics
        int lateWindows = 0, earlyWindows = 0; for (Reply r : wins) { if (r.pos() >= 0.66) lateWindows++; if (r.pos() < 0.34) earlyWindows++; }
        for (String key : VALUE_KEYS) {
            LinkedHashMap<String, List<Double>> vals = new LinkedHashMap<>();
            for (Reply r : wins) vals.computeIfAbsent(lower(str(r.fields(), key)).strip(), x -> new ArrayList<>()).add(r.pos());
            for (Map.Entry<String, List<Double>> e : vals.entrySet()) if (!e.getKey().isEmpty()) sig.set(key + "=" + e.getKey(), frv(e.getValue(), k, lateWindows, earlyWindows));
        }
        // the dynamics ("tempo, deltas, transitions are a big signature"): per scale the mean change between adjacent windows, the turning
        // points, the largest jump and where; for the mood, how often the leading mood switches and which follows which
        for (Map.Entry<String, Map<String, Integer>> sc : ORD.entrySet()) {
            List<Integer> seq = new ArrayList<>();
            for (Reply r : wins) { Integer v = sc.getValue().get(lower(str(r.fields(), sc.getKey()))); if (v != null) seq.add(v); }
            if (seq.size() < 3) continue;
            List<Integer> deltas = new ArrayList<>(); for (int i = 1; i < seq.size(); i++) deltas.add(seq.get(i) - seq.get(i - 1));
            int turns = 0; for (int i = 1; i < deltas.size(); i++) if (deltas.get(i - 1) * deltas.get(i) < 0) turns++;
            int jump = 0; for (int i = 1; i < deltas.size(); i++) if (Math.abs(deltas.get(i)) > Math.abs(deltas.get(jump))) jump = i;
            List<Integer> abs = new ArrayList<>(); for (int v : deltas) abs.add(Math.abs(v));
            ObjectNode o = sig.putObject("dyn." + sc.getKey());
            o.put("delta", Py.round(Py.fmean(abs), 2)); o.put("turns", turns); o.put("jump", deltas.get(jump)); o.put("jump_at", Py.round((jump + 1) / (double) seq.size(), 2));
        }
        List<String> dom = new ArrayList<>();
        for (Reply r : wins) { JsonNode mm = r.fields().get("mood_most"); if (mm != null && mm.isArray() && !mm.isEmpty()) { String s = lower(str(mm.get(0))); if (!s.isEmpty()) dom.add(s); } }
        if (dom.size() >= 2) {
            int switches = 0; Counter trans = new Counter();
            for (int i = 1; i < dom.size(); i++) if (!dom.get(i).equals(dom.get(i - 1))) { switches++; trans.add(dom.get(i - 1) + ">" + dom.get(i)); }
            ObjectNode o = sig.putObject("dyn.mood");
            o.put("switches", Py.round(switches / (double) (dom.size() - 1), 2));
            ArrayNode follows = o.putArray("follows"); for (Map.Entry<String, Integer> e : trans.mostCommon(3)) follows.add(e.getKey());
        }
        LinkedHashMap<String, List<Double>> topics = new LinkedHashMap<>();
        for (Reply r : wins) for (String w : topics(r.fields())) topics.computeIfAbsent(w, x -> new ArrayList<>()).add(r.pos());
        List<Map.Entry<String, List<Double>>> byCount = new ArrayList<>(topics.entrySet());
        byCount.sort(Comparator.comparingInt((Map.Entry<String, List<Double>> e) -> e.getValue().size()).reversed());
        int shown = 0;
        for (Map.Entry<String, List<Double>> e : byCount) { if (shown++ == 12) break; sig.set("topic." + e.getKey(), frv(e.getValue(), k, lateWindows, earlyWindows)); }
        ArrayNode all = agg.putArray("topics"); for (Map.Entry<String, List<Double>> e : byCount) all.add(e.getKey());   // every topic, most windows first, for the subject overlap
        return agg;
    }

    /** Frequency = the share of windows with the value, recency = their mean position, value = the last third's share minus the first third's. */
    static ObjectNode frv(List<Double> positions, int k, int lateWindows, int earlyWindows) {
        int late = 0, early = 0; for (double p : positions) { if (p >= 0.66) late++; if (p < 0.34) early++; }
        ObjectNode o = M.createObjectNode();
        o.put("f", Py.round(positions.size() / (double) k, 2)); o.put("r", Py.round(Py.fmean(positions), 2));
        o.put("v", Py.round(late / (double) Math.max(1, lateWindows) - early / (double) Math.max(1, earlyWindows), 2));
        return o;
    }

    // ---- the model, and labelling a text ----

    /** What answers a question: the drive, or a test's canned replies. */
    public interface Model { String complete(String system, String user, int maxTokens) throws Exception; }

    /** The research drive as the labeller: temperature 0, thinking off, as every typed decision has it. */
    public static Model drive(DriveClient drive) {
        return (system, user, maxTokens) -> {
            ArrayNode msgs = M.createArrayNode();
            msgs.addObject().put("role", "system").put("content", system);
            msgs.addObject().put("role", "user").put("content", user);
            return drive.classify(msgs, maxTokens);
        };
    }

    /** How many words a text must have for the shortest labelling: one window of it all. */
    public static final int MIN_WORDS = 200;

    /**
     * The labelled layer of a text: the book-level answers from the excerpt (when the text has 5,000 words), then the windows under the
     * vocabulary of the book's kind, aggregated. {@code progress} hears a line per step. Null when the text is too short to label.
     */
    public static ObjectNode label(List<String> chapters, Model model, Consumer<String> progress) throws Exception {
        List<String> words = Windows.words(chapters);
        if (words.size() < MIN_WORDS) return null;
        String excerpt = Windows.excerpt(words);
        ObjectNode book = null;
        if (excerpt != null) {
            if (progress != null) progress.accept("the book-level question over the excerpt");
            book = object(model.complete(BOOK_SYSTEM, bookPrompt(excerpt), 1200));
        }
        String kind = book == null ? "novel" : lower(str(book, "kind")).strip();
        if (kind.isEmpty()) kind = "novel";
        List<Windows.Window> windows = Windows.cut(words);
        if (windows.isEmpty()) windows = List.of(new Windows.Window(0, 1, 0.0, String.join(" ", words)));
        List<Reply> replies = new ArrayList<>(); int failed = 0;
        String prompt = windowPrompt(kind);
        for (Windows.Window w : windows) {
            if (progress != null) progress.accept("window " + (w.index() + 1) + " of " + w.of());
            ObjectNode fields = parse(model.complete(WINDOW_SYSTEM, prompt + w.text(), 400));
            if (fields == null) failed++; else replies.add(new Reply(w.pos(), fields));
        }
        ObjectNode out = aggregate(replies);
        out.put("version", VERSION);
        out.put("kind", kind);
        out.put("fiction", fiction(kind));
        out.put("windows_asked", windows.size());
        out.put("windows_unparsed", failed);
        out.put("words", words.size());
        if (book != null) out.set("book", book); else out.putNull("book");
        return out;
    }
}
