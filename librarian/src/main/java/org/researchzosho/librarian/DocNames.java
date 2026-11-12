package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.drive.Declined;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names and recurring terms of a document, made consistent across its translation after the first pass, by what the translation itself
 * mostly writes.
 *
 * <p>The program finds the candidates; the model does not nominate them. From the source units alone: in a text written without spaces
 * (Japanese, Chinese), the maximal repeated strings of Han characters and katakana, of two characters or more, cut at kana (the particles)
 * and punctuation; in a text written with spaces, the capitalised words and runs of them that recur and do not stand only where a
 * sentence starts (in a language that capitalises its nouns, German, a single word only when no article stands before it). A string that
 * stands on its own (outside every longer one that holds it) in fewer than two units is a piece of those and no candidate.
 *
 * <p>Nothing is decided before the units are translated: a model renders a whole paragraph well and a string alone badly (a list decided
 * cold, from a string and one sentence, gave one man another man's name and an office the name of its holder, and enforcing it made a good
 * translation worse; measured 2026-11-11, docs/DESIGN_TRANSLATION.md). After the first pass, for each string three units or more hold on their own, the model is shown
 * two of its passages with their translation and names the words of the translations that say it, and its kind, or none; it may not add a
 * string. The program then verifies each answer against every unit holding the string ({@link #verify}): it is decided only when more
 * than three in four of them write it where the string stands, and one form goes to one string. A unit that holds a decided string and
 * writes it otherwise is a stray, a quarter at most by construction: the checks flag it and the revision is told what the rest of the
 * document writes. No unit is pushed toward a form the document does not already mostly write. The model's answers are kept with the job,
 * and for the document and the language after a run (versioned; a list decided before the first pass is never read), and verified again
 * against each run's own first pass.
 */
final class DocNames {
    private DocNames() { }

    private static final ObjectMapper M = new ObjectMapper();
    public static final String PERSON = "person", ORGANISATION = "organisation", PLACE = "place", WORK = "work", TERM = "term", NONE = "none", NAME = "name";
    static final List<String> KINDS = List.of(PERSON, ORGANISATION, PLACE, WORK, TERM);
    /** The kinds that are names: rendered otherwise in a unit is a major flag, and the original stands once in parentheses beside one in another script. */
    static final Set<String> NAMES = Set.of(PERSON, ORGANISATION, PLACE, WORK, NAME);
    /** The most candidates a document's list holds; measured, see docs/DESIGN_TRANSLATION.md. */
    static final int MOST = 200;
    /** Of them, the most that are two Han characters alone, a quarter: see {@link #twoHan}. */
    static final int MOST_TWO_HAN = 50;
    /** Candidates asked about in one call. */
    static final int ASKED_AT_ONCE = 30;
    /** The longest string the finder counts, in characters, in a text written without spaces. */
    static final int LONGEST = 24;
    /** The units at the document's head whose strings are candidates once (the title's line, the author's, the affiliation): those before the first unit of {@link #BODY} characters, at most this many. */
    static final int HEAD = 12;
    /** A unit this long is the body's: the head ends before it. A repository's cover sheet, a title, an author's line and an affiliation are shorter (61 characters at most on the paper the finder was measured on, whose first paragraph has 293). */
    static final int BODY = 120;

    /** A string the program found: the string, one sentence of the document it stands in, the units that hold it, how often it stands, and whether it came from the head. */
    record Candidate(String text, String sentence, int units, int count, boolean head) { }

    /** A string decided: the source, its rendering, its kind. */
    record Entry(String source, String rendering, String kind) {
        boolean name() { return NAMES.contains(kind); }
    }


    // ---- the finder ----

    /** Whether a code point belongs to a name's string in a text written without spaces: Han (with 々 〆 ヶ), katakana, the long sound mark, the middle dot. */
    static boolean nameChar(int c) {
        if (c == '々' || c == '〆' || c == 'ヶ' || c == 'ー' || c == '・') return true;
        Character.UnicodeScript s = Character.UnicodeScript.of(c);
        return s == Character.UnicodeScript.HAN || (s == Character.UnicodeScript.KATAKANA && c != '゠');
    }

    /**
     * The runs of name characters in a text, cut at kana: at hiragana, which are the particles and endings of ordinary Japanese, and at a
     * stretch of one or two katakana beside a Han character, which are the particles and endings of the older katakana-mixed writing
     * ("議会ノ決定ヲ尊重シ", "関スル"). The middle dot and the long sound mark do not start a run, the middle dot does not end one.
     */
    static List<String> runs(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i <= text.length(); ) {
            int c = i < text.length() ? text.codePointAt(i) : ' ';
            if (i < text.length() && nameChar(c) && !(cur.length() == 0 && (c == '・' || c == 'ー'))) cur.appendCodePoint(c);
            else {
                for (String r : cutAtKatakanaParticles(cur.toString())) {
                    r = r.replaceAll("・+$", "").replaceAll("^[・ー]+", "");
                    if (r.codePointCount(0, r.length()) >= 2) out.add(r);
                }
                cur.setLength(0);
            }
            i += i < text.length() ? Character.charCount(c) : 1;
        }
        return out;
    }

    /** A run cut where a stretch of one or two katakana stands beside a Han character (the stretch is a particle or an ending, not part of a name), and at a middle dot not between katakana. */
    static List<String> cutAtKatakanaParticles(String run) {
        int[] cps = run.codePoints().toArray();
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int a = 0; a < cps.length; ) {
            if (cps[a] == '・' && !(a > 0 && katakana(cps[a - 1]) && a + 1 < cps.length && katakana(cps[a + 1]))) {   // a middle dot between Han lists two things (宮中・府中); between katakana it joins one foreign name
                if (a > start) out.add(new String(cps, start, a - start));
                start = ++a; continue;
            }
            if (!katakana(cps[a])) { a++; continue; }
            int b = a; while (b < cps.length && katakana(cps[b])) b++;
            boolean short2 = b - a <= 2, hanBefore = a > 0 && han(cps[a - 1]), hanAfter = b < cps.length && han(cps[b]);
            if (short2 && (hanBefore || hanAfter)) { if (a > start) out.add(new String(cps, start, a - start)); start = b; }
            a = b;
        }
        if (start < cps.length) out.add(new String(cps, start, cps.length - start));
        return out;
    }

    static boolean katakana(int c) { return Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA && c != 'ー' && c != '・' && c != 'ヶ'; }
    static boolean han(int c) { return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN || c == '々' || c == '〆' || c == 'ヶ'; }

    /** Whether a document is written without spaces: its letters Han, or Han with kana. */
    static boolean spaceless(String text) {
        TranslationLengths.Script s = TranslationLengths.scriptOf(text);
        return s == TranslationLengths.Script.HAN || s == TranslationLengths.Script.KANA;
    }

    /**
     * The candidates of a document, most likely names first, at most {@link #MOST}: the head's strings, then those that recur, by the
     * units that hold them. {@code units} are the source units' texts in order and {@code title} the document's. The head is the units
     * up to the last that is the title (a repository's cover sheet may stand before it, and the title again after) and the short ones after
     * it, the author's line, the affiliation; with no title among them, the units before the first of {@link TranslationLengths#SHORT}
     * characters; never one of {@link #BODY} characters or more, and at most {@link #HEAD}.
     */
    static List<Candidate> candidates(List<String> units, String title) {
        StringBuilder all = new StringBuilder(); for (String u : units) all.append(u).append('\n');
        String titled = title == null ? "" : title.strip();
        int lastTitle = -1;
        for (int i = 0; i < Math.min(units.size(), HEAD); i++) if (!titled.isEmpty() && DocUnits.plainText(units.get(i)).strip().equals(titled)) lastTitle = i;
        List<String> head = new ArrayList<>();
        for (int i = 0; i < units.size() && head.size() < HEAD; i++) {
            int len = DocUnits.plainText(units.get(i)).length();
            if (len >= BODY || (i > lastTitle && len >= TranslationLengths.SHORT)) break;
            head.add(units.get(i));
        }
        Map<String, int[]> found = spaceless(all.toString()) ? repeatsSpaceless(units) : repeatsSpaced(units, all.toString());
        Set<String> fromHead = new LinkedHashSet<>();
        String bare = Translate.URL.matcher(all).replaceAll(" ");
        boolean nouns = !spaceless(all.toString()) && capitalisesNouns(bare);
        for (String h : head) {
            String plain = DocUnits.plainText(h);
            if (spaceless(all.toString())) { for (String r : runs(plain)) if (!CrossLinks.STOP_JA.contains(r) && !dateOrNumber(r)) fromHead.add(r); }
            else if (!plain.equals(plain.toUpperCase(Locale.ROOT))) {   // a heading in capitals repeats the title; its words are not names
                Matcher m = Translate.NAME_ANY.matcher(plain);
                while (m.find()) { String t = headName(m.group(), bare, nouns); if (t != null) fromHead.add(t); }
            }
        }
        // a string that stands on its own (outside every longer one that holds it) in fewer than two units is a piece of those: 太郎 of
        // 山田太郎, a fragment of a heading; it is no candidate, and what the document writes for it is what it writes for the longer one
        Set<String> every = new LinkedHashSet<>(fromHead); every.addAll(found.keySet());
        Map<Integer, String> byUnit = new LinkedHashMap<>(); for (int i = 0; i < units.size(); i++) byUnit.put(i, units.get(i));
        Set<String> ownTwice = new HashSet<>();
        for (String x : every) if (holders(x, every, byUnit).size() >= 2) ownTwice.add(x);
        fromHead.retainAll(ownTwice);
        List<Candidate> out = new ArrayList<>();
        for (String h : fromHead) { int[] f = found.getOrDefault(h, count(units, h)); out.add(new Candidate(h, sentence(units, h), f[0], f[1], true)); }
        List<Map.Entry<String, int[]>> rest = new ArrayList<>(found.entrySet());
        rest.removeIf(e -> fromHead.contains(e.getKey()) || !ownTwice.contains(e.getKey()));
        rest.sort((a, b) -> a.getValue()[0] != b.getValue()[0] ? b.getValue()[0] - a.getValue()[0] : a.getValue()[1] != b.getValue()[1] ? b.getValue()[1] - a.getValue()[1] : b.getKey().length() - a.getKey().length());
        List<Map.Entry<String, int[]>> longer = new ArrayList<>(), two = new ArrayList<>();
        for (var e : rest) (twoHan(e.getKey()) ? two : longer).add(e);
        // the two-character ones take up to a quarter of the list, by the units that hold them; the longer ones the rest, and any room the others leave
        int roomTwo = Math.min(two.size(), MOST_TWO_HAN), roomLonger = Math.max(0, MOST - out.size() - roomTwo);
        List<Map.Entry<String, int[]>> chosen = new ArrayList<>(longer.subList(0, Math.min(longer.size(), roomLonger)));
        chosen.addAll(two.subList(0, Math.min(two.size(), Math.max(roomTwo, MOST - out.size() - chosen.size()))));
        chosen.sort((a, b) -> a.getValue()[0] != b.getValue()[0] ? b.getValue()[0] - a.getValue()[0] : a.getValue()[1] != b.getValue()[1] ? b.getValue()[1] - a.getValue()[1] : b.getKey().length() - a.getKey().length());
        for (var e : chosen) { if (out.size() >= MOST) break; out.add(new Candidate(e.getKey(), sentence(units, e.getKey()), e.getValue()[0], e.getValue()[1], false)); }
        return out;
    }

    /**
     * A string of two Han characters alone: as often an ordinary word (研究, 支援) as a name (田中, 佐藤), so it comes after the longer
     * strings and those with katakana, by the units that hold it, while the list has room.
     */
    static boolean twoHan(String s) { return s.codePointCount(0, s.length()) == 2 && s.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN); }

    /** How many units hold a string, and how often it stands in all. */
    static int[] count(List<String> units, String s) {
        int u = 0, n = 0;
        for (String t : units) { int k = 0, at = t.indexOf(s); while (at >= 0) { k++; at = t.indexOf(s, at + s.length()); } if (k > 0) { u++; n += k; } }
        return new int[]{u, n};
    }

    /** Whether a string recurs: in two units or more, or three times. */
    static boolean recurs(int units, int count) { return units >= 2 || count >= 3; }

    /**
     * The maximal repeated strings of name characters in a text written without spaces: every string of two to {@link #LONGEST}
     * characters inside a run that recurs, kept when no string one character longer that holds it stands as often (then it is a piece of
     * that one, "田太" of "山田太郎"), or four of its five appearances; a date or a number written in Han is left out. Counted level by
     * level, a string only when the strings a character shorter at its start and its end recur, so a long document costs what its
     * repeated strings do.
     */
    static Map<String, int[]> repeatsSpaceless(List<String> units) {
        List<List<String>> runsByUnit = new ArrayList<>();
        for (String u : units) runsByUnit.add(runs(DocUnits.plainText(u)));
        Map<String, int[]> frequent = new HashMap<>();   // string → {units, count}
        Set<String> previous = null;
        for (int len = 2; len <= LONGEST; len++) {
            Map<String, int[]> level = new HashMap<>();
            for (int ui = 0; ui < runsByUnit.size(); ui++) {
                Set<String> inUnit = new HashSet<>();
                for (String r : runsByUnit.get(ui)) {
                    int[] cps = r.codePoints().toArray();
                    for (int a = 0; a + len <= cps.length; a++) {
                        String s = new String(cps, a, len);
                        if (previous != null && (!previous.contains(new String(cps, a, len - 1)) || !previous.contains(new String(cps, a + 1, len - 1)))) continue;
                        int[] f = level.computeIfAbsent(s, x -> new int[2]);
                        f[1]++;
                        if (inUnit.add(s)) f[0]++;
                    }
                }
            }
            Set<String> kept = new HashSet<>();
            for (var e : level.entrySet()) if (recurs(e.getValue()[0], e.getValue()[1])) { kept.add(e.getKey()); frequent.put(e.getKey(), e.getValue()); }   // every one, for the next level: "ーヒー" leads to "コーヒー"
            if (kept.isEmpty()) break;
            previous = kept;
        }
        Set<String> pieces = new HashSet<>();   // a string one character shorter than a longer one that holds four of its five appearances or more: a piece of it
        for (var e : frequent.entrySet()) {
            String l = e.getKey();
            int[] cps = l.codePoints().toArray();
            if (cps.length < 3) continue;
            for (String part : new String[]{new String(cps, 0, cps.length - 1), new String(cps, 1, cps.length - 1)}) { int[] f = frequent.get(part); if (f != null && f[1] * 4 <= e.getValue()[1] * 5) pieces.add(part); }
        }
        Map<String, int[]> out = new LinkedHashMap<>();
        for (var e : frequent.entrySet()) {
            String s = e.getKey();
            if (pieces.contains(s) || s.startsWith("ー") || s.startsWith("・") || s.endsWith("・")) continue;
            if (CrossLinks.STOP_JA.contains(s) || dateOrNumber(s)) continue;
            out.put(s, e.getValue());
        }
        return out;
    }

    /** The characters of a date or a number written in Han: the numerals, and year, month, day. */
    static final String NUMERALS = "〇一二三四五六七八九十百千万億元年月日";
    /** The eras a Japanese date counts its years in. */
    static final Pattern ERA_DATE = Pattern.compile("^(?:明治|大正|昭和|平成|令和)[" + NUMERALS + "]+$");

    /** Whether a string is a date or a number written in Han: half its characters numerals or year, month and day ("昭和三〇年五月", "一八八"), or an era with a year. A name may hold a numeral ("田中健一", "鈴木三郎"); it holds fewer. */
    static boolean dateOrNumber(String s) {
        int n = 0, all = 0;
        for (int i = 0; i < s.length(); ) { int c = s.codePointAt(i); i += Character.charCount(c); all++; if (NUMERALS.indexOf(c) >= 0) n++; }
        return n * 2 >= all || ERA_DATE.matcher(s).matches();
    }

    /** The articles and determiners of German, after which a capitalised word is a noun of the language, not a name. */
    static final Set<String> ARTICLES = Set.of("der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines", "kein", "keine", "keinen", "keinem", "keiner", "keines",
            "dieser", "diese", "dieses", "diesem", "diesen", "jeder", "jede", "jedes", "jedem", "jeden", "sein", "seine", "seinen", "seinem", "seiner", "seines", "ihr", "ihre", "ihren", "ihrem", "ihrer", "ihres",
            "mein", "meine", "meinen", "meinem", "meiner", "unser", "unsere", "unseren", "unserem", "zum", "zur", "im", "am", "vom", "beim", "ins", "ans", "welche", "welcher", "welches", "alle", "allen", "aller", "viele", "vielen", "einige", "einigen", "mehrere");

    /**
     * Whether a text capitalises its nouns, as German does: a capital starts more than a ninth of the words that do not open a sentence.
     * Measured: 19.1% in Kafka's Die Verwandlung, 5.9% in Doyle's A Scandal in Bohemia, names and all.
     */
    static boolean capitalisesNouns(String text) {
        Matcher m = Pattern.compile("(?<=[\\p{L},;:] )(\\p{L})\\p{L}*").matcher(text);
        int words = 0, capital = 0;
        while (m.find()) { words++; if (Character.isUpperCase(m.group(1).codePointAt(0))) capital++; }
        return words >= 50 && capital * 9 > words;
    }

    /**
     * The repeated names of a text written with spaces: a capitalised word or a run of them ({@link Translate#NAME_ANY}), a sentence's
     * first word dropped from its front when the word stands in lowercase elsewhere or only where sentences start, kept when it recurs and
     * is not a heading in capitals; in a language that capitalises its nouns a single word only when no article or determiner stands just
     * before it anywhere in the text.
     */
    static Map<String, int[]> repeatsSpaced(List<String> units, String all) {
        String bare = Translate.URL.matcher(all).replaceAll(" ");
        boolean nouns = capitalisesNouns(bare);
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (String unit : units) {
            Set<String> inUnit = new HashSet<>();
            Matcher m = Translate.NAME_ANY.matcher(Translate.URL.matcher(DocUnits.plainText(unit)).replaceAll(" "));
            while (m.find()) {
                String t = name(m.group(), bare, nouns);
                if (t == null) continue;
                int[] f = counts.computeIfAbsent(t, x -> new int[2]);
                f[1]++;
                if (inUnit.add(t)) f[0]++;
            }
        }
        // in a language that capitalises its nouns a single word needs three appearances: the nouns used without an article (zu Hause, um
        // Hilfe) are many and each seldom; on Die Verwandlung, 25 of the 41 candidates were such nouns standing twice, none of them a name
        counts.entrySet().removeIf(e -> !recurs(e.getValue()[0], e.getValue()[1]) || (nouns && !e.getKey().contains(" ") && e.getValue()[1] < 3));
        return counts;
    }

    /**
     * A capitalised run as a name, or null: a sentence's first word dropped from its front when the word stands in lowercase elsewhere,
     * only where sentences start, or is an article in a language that capitalises its nouns; null for what is left when it is short, a
     * common word, a heading in capitals, a word that stands in lowercase elsewhere or only at sentences' starts, or (in such a language)
     * a single word an article stands before somewhere.
     */
    static String name(String found, String bare, boolean nouns) {
        String t = bareName(found);
        while (t.contains(" ")) {
            String first = t.substring(0, t.indexOf(' '));
            if (Translate.lowercaseElsewhere(bare, first) || Translate.onlyAtSentenceStarts(bare, first) || (nouns && ARTICLES.contains(first.toLowerCase(Locale.ROOT)))) t = t.substring(t.indexOf(' ') + 1).strip(); else break;
        }
        if (t.length() < 3 || CrossLinks.STOP.contains(t) || Translate.CLAIM_ID.matcher(t).find()) return null;
        if (t.equals(t.toUpperCase(Locale.ROOT)) && t.contains(" ")) return null;   // a heading in capitals
        if (!t.contains(" ") && !t.equals(t.toUpperCase(Locale.ROOT)) && (Translate.lowercaseElsewhere(bare, t) || Translate.onlyAtSentenceStarts(bare, t))) return null;
        if (nouns && !t.contains(" ") && afterAnArticle(bare, t)) return null;
        if (Character.isLowerCase(t.codePointAt(0))) return null;
        return t;
    }

    /** A name as it stands without a sentence's full stop or a possessive after it, and without the next sentence's first word: "Holmes." and "Majesty’s" are Holmes and Majesty, "Briony Lodge. The" is Briony Lodge; "Mr." keeps its own. */
    static String bareName(String found) {
        Matcher stop = Pattern.compile("(?<!\\b(?:Mr|Mrs|Ms|Dr|St|Jr|Sr|Prof|Hr|Fr|\\p{Lu}))\\.\\s+").matcher(found);   // a sentence ended inside the run: "Briony Lodge. The house"
        String t = (stop.find() ? found.substring(0, stop.start() + 1) : found).replaceAll("['’]s$", "");
        if (t.endsWith(".") && !t.matches("(?s).*\\b(?:Mr|Mrs|Ms|Dr|St|Jr|Sr|Prof|Hr|Fr)\\.$") && !t.matches(".*\\b\\p{Lu}\\.$")) t = t.substring(0, t.length() - 1);
        return t.strip();
    }

    /**
     * A capitalised run of the head (the title's line, the author's) as a name, or null: its first words dropped while they stand in
     * lowercase elsewhere (an article, "A Scandal…") or are articles; a line's first word is not a sentence's here, so a given name
     * stays ("Arthur Conan Doyle").
     */
    static String headName(String found, String bare, boolean nouns) {
        String t = bareName(found);
        while (t.contains(" ")) {
            String first = t.substring(0, t.indexOf(' '));
            if (Translate.lowercaseElsewhere(bare, first) || (nouns && ARTICLES.contains(first.toLowerCase(Locale.ROOT)))) t = t.substring(t.indexOf(' ') + 1).strip(); else break;
        }
        if (t.length() < 3 || CrossLinks.STOP.contains(t) || Translate.lowercaseElsewhere(bare, t) || Character.isLowerCase(t.codePointAt(0))) return null;
        if (nouns && !t.contains(" ") && afterAnArticle(bare, t)) return null;
        return t;
    }

    /**
     * Whether an article or a determiner stands before a word in a quarter of its appearances or more, just before it or with one word
     * between ("die Schwester", "dem großen Schlüssel"): a noun of the language. A name stands with one now and then ("der arme Gregor")
     * and is kept.
     */
    static boolean afterAnArticle(String text, String word) {
        Matcher m = Pattern.compile("(?<![\\p{L}])" + Pattern.quote(word) + "(?![\\p{L}])").matcher(text);
        int all = 0, after = 0;
        while (m.find()) {
            all++;
            String[] before = text.substring(Math.max(0, m.start() - 60), m.start()).split("[^\\p{L}]+");
            int n = before.length;
            String last = n >= 1 ? before[n - 1].toLowerCase(Locale.ROOT) : "", second = n >= 2 ? before[n - 2].toLowerCase(Locale.ROOT) : "";
            if (ARTICLES.contains(last) || (ARTICLES.contains(second) && n >= 1 && !before[n - 1].isEmpty() && Character.isLowerCase(before[n - 1].codePointAt(0)))) after++;
        }
        return all > 0 && after * 4 >= all;
    }

    /** One sentence of the document a string stands in, from the first unit that holds it, at most 200 characters around it. */
    static String sentence(List<String> units, String s) {
        for (String u : units) {
            String p = DocUnits.plainText(u).replaceAll("\\s+", " ").strip();
            int at = p.indexOf(s);
            if (at < 0) continue;
            int start = 0, end = p.length();
            for (int i = at - 1; i >= 0; i--) if ("。．！？!?".indexOf(p.charAt(i)) >= 0 || (p.charAt(i) == '.' && i + 1 < p.length() && p.charAt(i + 1) == ' ')) { start = i + 1; break; }
            for (int i = at + s.length(); i < p.length(); i++) if ("。．！？!?.".indexOf(p.charAt(i)) >= 0) { end = i + 1; break; }
            String out = p.substring(start, end).strip();
            if (out.length() > 200) { int a = Math.max(0, at - start - 90); out = (a > 0 ? "…" : "") + out.substring(a, Math.min(out.length(), a + 200)) + "…"; }
            return out;
        }
        return s;
    }

    // ---- the decision: from the document's own first pass, verified by the program ----

    /**
     * The share of the units holding a string whose translations must write one form for it to be decided: more than three in four, of
     * three units or more. Measured on a real paper's first pass (docs/DESIGN_TRANSLATION.md): two in three let a person of the same sentences through (another
     * man's name, written in 4 of a name's 6 units) and a longer string's rendering (a cabinet's for its prime minister, 3 of 4); and of two
     * units either is passed by anyone the two units both name.
     */
    static final double MAJORITY = 0.75;
    static final int FEWEST = 3;
    /** The units shown for a string in the call that finds its words: the shortest that hold it. */
    static final int SHOWN = 2;
    /** Strings asked about in one call. */
    static final int ASKED_TOGETHER = 8;
    /** A translation shown in that call is cut after this many characters. */
    static final int SHOWN_CHARS = 700;
    /** The kept file's form: a kept list in another form (the one decided before the first pass) is never read. */
    static final int KEPT_VERSION = 2;

    /** What the model said a string is in the translations: its kind (none to turn it down) and the words of the translations that stand for it. */
    record Answer(String kind, String words) { }

    /**
     * A candidate compared across the first pass: the string; the units that hold it on its own (not inside a longer candidate); the form
     * decided, or null; its kind ("" when the model gave no answer for it); how many of those units write it (k of n); the units that hold
     * the string and do not (the strays); and, when nothing is decided, the words the model named, how many of the units write them, and
     * the other string they are the words of, when that is why ("" otherwise).
     */
    record Compared(String source, List<Integer> holders, String rendering, String kind, int writes, List<Integer> strays, String leading, int leadingWrites, String theirs,
                    String by, Map<String, Integer> forms, String who, String said) {
        Compared(String source, List<Integer> holders, String rendering, String kind, int writes, List<Integer> strays, String leading, int leadingWrites) {
            this(source, holders, rendering, kind, writes, strays, leading, leadingWrites, "");
        }
        Compared(String source, List<Integer> holders, String rendering, String kind, int writes, List<Integer> strays, String leading, int leadingWrites, String theirs) {
            this(source, holders, rendering, kind, writes, strays, leading, leadingWrites, theirs, BY_UNITS, Map.of(), "", "");
        }
        boolean decided() { return rendering != null; }
        int held() { return holders.size(); }
        boolean name() { return NAMES.contains(kind); }
        boolean turnedDown() { return !decided() && kind.equals(NONE); }
        /** An answer that stands in none of the units: the model echoed the source, gave words the translations do not hold, or gave none. It says nothing of the translation. */
        boolean failed() { return !decided() && !kind.equals(NONE) && leadingWrites == 0 && !doubted(); }
        /** Left undecided by the reader: it found none of the forms the document writes right, or chose words no passage writes. */
        boolean doubted() { return !decided() && by.equals(BY_READER); }
    }

    /** How a string was decided: by more than three in four of its units, by the part of the name most of them share, or by the reader's call. */
    static final String BY_UNITS = "units", BY_PART = "part", BY_READER = "reader";

    /** The comparison of a document: every candidate asked about, how many the finder found, and whether the model's answers were kept from an earlier translation. */
    record Decided(List<Compared> compared, int found, boolean reused) {
        static final Decided NONE_FOUND = new Decided(List.of(), 0, false);
        /** The strings decided, each with the form most of its units write. */
        List<Entry> entries() { List<Entry> out = new ArrayList<>(); for (Compared c : compared) if (c.decided()) out.add(new Entry(c.source(), c.rendering(), c.kind())); return out; }
        Map<String, String> renderings() { Map<String, String> m = new LinkedHashMap<>(); for (Entry e : entries()) m.put(e.source(), e.rendering()); return m; }
        Set<String> names() { Set<String> s = new LinkedHashSet<>(); for (Entry e : entries()) if (e.name()) s.add(e.source()); return s; }
        /** Not decided, the words named standing in some of the units but in too few of them, or being another string's words; the reader not asked, or giving nothing. */
        List<Compared> tooFew() { return compared.stream().filter(c -> !c.decided() && !c.turnedDown() && !c.failed() && !c.doubted()).toList(); }
        /** The strings decided one way: {@link #BY_UNITS}, {@link #BY_PART}, {@link #BY_READER}. */
        List<Compared> decidedBy(String how) { return compared.stream().filter(c -> c.decided() && c.by().equals(how)).toList(); }
        /** The strings the reader left undecided. */
        List<Compared> doubted() { return compared.stream().filter(Compared::doubted).toList(); }
        /** The models that made the reader's calls, as the note names them. */
        String readers() { Set<String> w = new LinkedHashSet<>(); for (Compared c : compared) if (c.by().equals(BY_READER) && !c.who().isEmpty()) w.add(c.who()); return String.join(", ", w); }
        /** Not decided, the words named standing in none of the units (or none named): a failed answer. */
        List<Compared> failed() { return compared.stream().filter(Compared::failed).toList(); }
        int turnedDown() { return (int) compared.stream().filter(Compared::turnedDown).count(); }
        /** The decided entries a unit holds on its own: what its translation is checked against. */
        Map<String, String> heldBy(int unit) { Map<String, String> m = new LinkedHashMap<>(); for (Compared c : compared) if (c.decided() && c.holders().contains(unit)) m.put(c.source(), c.rendering()); return m; }
        /** The same, as a prompt gives them for the unit to be rendered: a part of a name is not the name's rendering, so it is left to the flag's line. */
        Map<String, String> renderingsFor(int unit) { Map<String, String> m = new LinkedHashMap<>(); for (Compared c : compared) if (c.decided() && !c.by().equals(BY_PART) && c.holders().contains(unit)) m.put(c.source(), c.rendering()); return m; }
        Compared of(String source) { for (Compared c : compared) if (c.source().equals(source)) return c; return null; }
    }

    /**
     * The units that hold a string on its own: where it stands outside every occurrence of a longer string that holds it ("山田太郎" holds
     * "太郎", "外務大臣" holds "大臣"). A unit where it stands only inside a longer one says the longer one.
     */
    static List<Integer> holders(String s, Collection<String> all, Map<Integer, String> sources) {
        List<String> longer = new ArrayList<>(); for (String l : all) if (l.length() > s.length() && l.contains(s)) longer.add(l);
        List<Integer> out = new ArrayList<>();
        for (var e : sources.entrySet()) if (standsOnItsOwn(e.getValue(), s, longer)) out.add(e.getKey());
        return out;
    }

    /** Whether a text holds a string somewhere outside every occurrence of the longer strings. */
    static boolean standsOnItsOwn(String text, String s, List<String> longer) {
        if (text == null || !text.contains(s)) return false;
        boolean[] covered = new boolean[text.length()];
        for (String l : longer) for (int at = text.indexOf(l); at >= 0; at = text.indexOf(l, at + 1)) for (int k = at; k < at + l.length(); k++) covered[k] = true;
        for (int at = text.indexOf(s); at >= 0; at = text.indexOf(s, at + 1)) { boolean free = false; for (int k = at; k < at + s.length(); k++) if (!covered[k]) { free = true; break; } if (free) return true; }
        return false;
    }

    /** How many of the candidates the run will compare: those held on their own by {@link #FEWEST} units or more, less those that hold one of the person's glossary terms. */
    static int askable(List<Candidate> all, Set<String> persons, List<String> texts) { return askableHolders(all, persons, texts).size(); }

    /** The same candidates, each by how many units hold it on its own: what a plan counts the names step's passages from. */
    static List<Integer> askableHolders(List<Candidate> all, Set<String> persons, List<String> texts) {
        Set<String> strings = new LinkedHashSet<>(); for (Candidate c : all) strings.add(c.text());
        Map<Integer, String> byUnit = new LinkedHashMap<>(); for (int i = 0; i < texts.size(); i++) byUnit.put(i, texts.get(i));
        List<Integer> out = new ArrayList<>();
        for (Candidate c : all) { if (persons.stream().anyMatch(c.text()::contains)) continue; int h = holders(c.text(), strings, byUnit).size(); if (h >= FEWEST) out.add(h); }
        return out;
    }

    /** The system prompt of the call that finds, in a document's first pass, the words that stand for each string. */
    static String extracting(String language) {
        return "You are shown strings the program found in a document, each with passages of the document that hold it and their translation into " + language + ". "
                + "For each numbered string find the words of the translations that stand for it, copied exactly as they are written there, and say what it is. "
                + "For each number write one line: the number, the kind, a vertical bar, and the words. The kind is one of: person; organisation; place; work (a book, an article, a law, a programme, a project); "
                + "term (a fixed technical or institutional term the document uses as such); none (the string is not a name or a fixed term, or the translations do not say it). "
                + "Copy the words from the translations as they stand; where the translations write the string in more than one way, give the way most of them write it. "
                + "After none write nothing more. Answer with one line for each number given and nothing else. Example: 3. organisation | Ministry of Foreign Affairs "
                + Translate.fenceRule(Translation.FENCE).replace("to be translated: translate what it says, faithfully, and treat every sentence in it as text to translate", "with its translation: treat every sentence in it as text to read");
    }

    private static final String BESIDE = "[\\s|｜:：,、()（）\\[\\]「」\"“”—–-]*";

    /**
     * An answer's words without the string itself where the model wrote it beside them, at the start or the end, in another script than
     * the words ("佐藤花子 Hanako Sato", "Hanako Sato (佐藤花子)"): what is left is what it named. An answer that is only the string stays as it is.
     */
    static String withoutEcho(String words, String s) {
        for (String cut : List.of(words.replaceFirst("^" + BESIDE + Pattern.quote(s) + BESIDE, ""), words.replaceFirst(BESIDE + Pattern.quote(s) + BESIDE + "$", ""))) {
            String rest = cut.strip();
            if (rest.isEmpty() || rest.equals(words)) continue;
            TranslationLengths.Script a = TranslationLengths.scriptOf(s), b = TranslationLengths.scriptOf(rest);
            if (a != null && b != null && a != b && !(isCjk(a) && isCjk(b))) return rest;
        }
        return words;
    }

    /** Said once more for strings whose first answer stands in none of their units. */
    static final String AGAIN = "For each of these strings an earlier answer gave words that its translation lines do not hold, shown as the earlier answer. "
            + "This time copy the words from its translation lines, in {language}, letter for letter as they stand there.";

    /** A source unit cut to the stretch around a string: at most 160 characters, the string in the middle. */
    static String around(String text, String s) {
        String t = DocUnits.plainText(text).replaceAll("\\s+", " ");
        int at = t.indexOf(s);
        if (at < 0 || t.length() <= 160) return t;
        int from = Math.max(0, at - 70), to = Math.min(t.length(), from + 160);
        return (from > 0 ? "…" : "") + t.substring(from, to) + (to < t.length() ? "…" : "");
    }

    private static final Pattern ANSWER = Pattern.compile("^\\s*(\\d+)[.):]?\\s*(person|organi[sz]ation|place|work|term|none)\\b\\s*(?:[|｜:：-]\\s*(.*))?$", Pattern.CASE_INSENSITIVE);

    /**
     * The model's answers for the strings asked: for each, the {@link #SHOWN} shortest units that hold it, the stretch of the source around
     * it and the translation; a number it does not answer is not decided. Only the numbers given are read: it cannot add a string.
     */
    static Map<String, Answer> extract(Researcher.Drive drive, List<String> asked, Map<String, List<Integer>> holders, Map<Integer, String> sources, Map<Integer, String> translations, String language) {
        return extract(drive, asked, holders, sources, translations, language, Map.of());
    }

    /**
     * The same, asked once more for strings whose first answer stands in none of their units ({@code earlier}: those answers): each is
     * shown with its earlier words, and the prompt says the words are to be copied from its translation lines.
     */
    static Map<String, Answer> extract(Researcher.Drive drive, List<String> asked, Map<String, List<Integer>> holders, Map<Integer, String> sources, Map<Integer, String> translations, String language,
                                       Map<String, Answer> earlier) {
        Map<String, Answer> out = new LinkedHashMap<>();
        for (int from = 0; from < asked.size(); from += ASKED_TOGETHER) {
            List<String> batch = asked.subList(from, Math.min(asked.size(), from + ASKED_TOGETHER));
            StringBuilder u = new StringBuilder();
            for (int k = 0; k < batch.size(); k++) {
                String s = batch.get(k);
                List<Integer> shown = new ArrayList<>(holders.get(s));
                shown.sort((x, y) -> Integer.compare(DocUnits.plainText(translations.get(x)).length(), DocUnits.plainText(translations.get(y)).length()));
                u.append(k + 1).append(". ").append(s).append('\n');
                Answer was = earlier.get(s);
                if (!earlier.isEmpty()) u.append("   earlier answer: ").append(was == null || was.words().isBlank() ? "(none given)" : was.words()).append('\n');
                for (int i : shown.subList(0, Math.min(SHOWN, shown.size()))) {
                    String tr = DocUnits.plainText(translations.get(i)).replaceAll("\\s+", " ");
                    u.append("   passage: ").append(around(sources.get(i), s)).append('\n').append("   translation: ").append(tr.length() > SHOWN_CHARS ? tr.substring(0, SHOWN_CHARS) + "…" : tr).append('\n');
                }
            }
            ArrayNode messages = M.createArrayNode();
            messages.addObject().put("role", "system").put("content", earlier.isEmpty() ? extracting(language) : extracting(language) + " " + AGAIN.replace("{language}", language));
            messages.addObject().put("role", "user").put("content", Fence.wrap(Translation.FENCE, u.toString().stripTrailing()));
            String answer;
            try (var step = Declines.step("find the " + language + " words a document's translation writes for its names and terms")) { answer = drive.prose(messages, Math.min(3000, 50 * batch.size() + 200)); }
            catch (Declined x) { continue; }
            if (answer == null) continue;
            Set<Integer> seen = new HashSet<>();
            for (String line : answer.split("\n")) {
                Matcher m = ANSWER.matcher(line.strip());
                if (!m.matches()) continue;
                int n = Integer.parseInt(m.group(1));
                if (n < 1 || n > batch.size() || !seen.add(n)) continue;
                String kind = m.group(2).toLowerCase(Locale.ROOT).replace("organization", ORGANISATION), words = m.group(3) == null ? "" : withoutEcho(m.group(3).strip().replaceAll("^[\"“「]|[\"”」]$", "").strip(), batch.get(n - 1));
                out.put(batch.get(n - 1), new Answer(kind, kind.equals(NONE) ? "" : words));
            }
        }
        return out;
    }

    /**
     * The model's answers checked by the program against the first pass. A string is decided only when its answer is written (by the
     * check's own rule: a word may be inflected), at the place of the sentence that holds the string ({@link #writesAt}), in the
     * translations of more than {@link #MAJORITY} of the units that hold it on its own, {@link #FEWEST} of them at least; when the words do
     * not go with another string of the same units ({@link #followsAnother}); and one form is decided for one string only (two strings
     * answered alike: the one more of whose units write it keeps it). The units that hold the
     * string and do not write it are the strays, a quarter at most by construction. An answer the translations do not write is never
     * decided, whatever the model said; one written in too few units leaves the string undecided, said with its count.
     */
    static List<Compared> verify(List<String> asked, Map<String, Answer> answers, Map<String, List<Integer>> holders, Map<Integer, String> sources, Map<Integer, String> translations) {
        Map<String, Compared> out = new LinkedHashMap<>();
        for (String s : asked) {
            List<Integer> h = holders.getOrDefault(s, List.of());
            Answer a = answers.get(s);
            if (a == null || a.kind().equals(NONE) || a.words().isBlank() || h.size() < FEWEST) { out.put(s, new Compared(s, h, null, a == null ? "" : a.kind(), 0, List.of(), "", 0)); continue; }
            List<Integer> strays = new ArrayList<>();
            for (int u : h) if (!writesAt(sources.get(u), s, translations.get(u), a.words())) strays.add(u);
            int writes = h.size() - strays.size();
            String theirs = writes > MAJORITY * h.size() ? followsAnother(s, a.words(), h, strays, holders, sources, translations) : null;
            out.put(s, writes > MAJORITY * h.size() && theirs == null ? new Compared(s, h, a.words(), a.kind(), writes, strays, a.words(), writes)
                    : new Compared(s, h, null, a.kind(), 0, List.of(), a.words(), writes, theirs == null ? "" : theirs));
        }
        // one form for one string: when two strings (neither holding the other) are decided as the same words, the one more of whose units
        // write them keeps them, by share and then by units; the other is left undecided
        for (Compared c : new ArrayList<>(out.values())) {
            if (!c.decided()) continue;
            for (Compared d : out.values()) {
                if (d == c || !d.decided() || d.source().contains(c.source()) || c.source().contains(d.source()) || !d.rendering().equalsIgnoreCase(c.rendering())) continue;
                double mine = c.writes() / (double) c.held(), theirs = d.writes() / (double) d.held();
                if (theirs > mine + 1e-9 || (Math.abs(theirs - mine) <= 1e-9 && d.held() > c.held())) { out.put(c.source(), new Compared(c.source(), c.holders(), null, c.kind(), 0, List.of(), c.rendering(), c.writes(), d.source())); break; }
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * The other string an answer's words are, or null: every unit that holds this string and writes them also holds the other string, and
     * the units that hold the other one without this one write them too, most of them. Then the words go with the other string (another
     * person of the same sentences: 山田太郎 named in every passage about 厚生労働省, and "Taro Yamada" given for the ministry), not this one.
     */
    static String followsAnother(String s, String words, List<Integer> holders, List<Integer> strays, Map<String, List<Integer>> all, Map<Integer, String> sources, Map<Integer, String> translations) {
        List<Integer> writers = new ArrayList<>(holders); writers.removeAll(strays);
        for (var e : all.entrySet()) {
            String t = e.getKey();
            if (t.equals(s) || t.contains(s) || s.contains(t) || !e.getValue().containsAll(writers)) continue;
            List<Integer> without = new ArrayList<>(e.getValue()); without.removeAll(holders);
            if (without.isEmpty()) continue;
            int w = 0; for (int u : without) if (writesAt(sources.get(u), t, translations.get(u), words)) w++;
            if (w * 2 > without.size()) return t;
        }
        return null;
    }

    // ---- A: the part of a name most of its units share ----

    /**
     * A part of an answer's words, measured: the words; how many of the units holding the string write them at its place, and the units
     * that do not (the strays); how many units unrelated to the string (holding neither it nor a candidate that holds it or that it holds)
     * write them anywhere, of how many such units; and how many related units write them.
     */
    record Part(String words, int writes, List<Integer> strays, int outside, int outsideUnits, int inside) { }

    /**
     * The parts of an answer, its runs of whole words shorter than the whole, longest first (left to right among equals), each measured
     * against the units: {@link Part}. A part is words with a letter in each, two letters at least.
     */
    static List<Part> parts(String s, String words, List<Integer> holders, Collection<String> candidates, Map<Integer, String> sources, Map<Integer, String> translations) {
        String[] w = words.strip().split("\\s+");
        List<String> related = new ArrayList<>(List.of(s));
        for (String c : candidates) if (!c.equals(s) && (c.contains(s) || s.contains(c))) related.add(c);
        List<Part> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int len = w.length - 1; len >= 1; len--) {
            for (int from = 0; from + len <= w.length; from++) {
                String part = String.join(" ", List.of(w).subList(from, from + len)).replaceAll("^[\\p{P}&&[^'’]]+|[\\p{P}&&[^'’]]+$", "");
                if (part.codePoints().filter(Character::isLetter).count() < 2 || !seen.add(part)) continue;
                List<Integer> strays = new ArrayList<>();
                for (int u : holders) if (!writesAt(sources.get(u), s, translations.get(u), part)) strays.add(u);
                int outside = 0, units = 0, inside = 0;
                for (var e : sources.entrySet()) {
                    String tr = translations.get(e.getKey());
                    if (tr == null) continue;
                    boolean rel = related.stream().anyMatch(e.getValue()::contains);
                    boolean writes = Translation.usesRendering(DocUnits.plainText(tr), part);
                    if (rel) { if (writes) inside++; }
                    else { units++; if (writes) outside++; }
                }
                out.add(new Part(part, holders.size() - strays.size(), strays, outside, units, inside));
            }
        }
        return out;
    }

    /**
     * A part passes where more than this share of the units holding the string write it at the string's place: two in three. Measured on two
     * runs of a 27-page paper: the family name of a person the first pass wrote in two word orders, and misread in a quarter of its units,
     * stood in 8 of the 11 units of the full name and 5 of the 7 of the family name alone, so three in four could not settle it.
     */
    static final double PART_SHARE = 2.0 / 3;
    /**
     * A part is specific to its string where the units unrelated to the string that write it are at most a quarter as many as the related
     * ones that do (a fifth of all the units writing it). Measured on the same runs: the parts of the persons' names stood in 0 to 2
     * unrelated units against 3 to 23 related; "Minister" (28 against 16), "Prince" (28 against 4), "Interior" (4 against 12), "Diary" (12
     * to 18 against 6 to 12) and a family name two people of the document share (4 against 3) did not pass.
     */
    static final int SPECIFIC = 4;

    /**
     * The part most units share, for a person whose whole name the model gave fails the three-in-four test: of its parts ({@link #parts}),
     * the one more than {@link #PART_SHARE} of the units holding the string write at its place, specific to it ({@link #SPECIFIC}), not
     * another string's words and not decided for an unrelated string; the longest, and among equals the one the document also writes where a
     * shorter candidate inside the string stands alone (the family name, written alone), then the one written in more related units. Only a
     * person's: a part of an organisation's or a work's name ("Club", "Diet", "Incident", "Issue") stood as specific as a family name on the
     * measured runs, so those are left to the reader. The units holding the string that lack the part are its strays.
     */
    static List<Compared> settleByParts(List<Compared> compared, Collection<String> candidates, Map<String, List<Integer>> holders, Map<Integer, String> sources, Map<Integer, String> translations) {
        List<Compared> out = new ArrayList<>(compared);
        for (int k = 0; k < out.size(); k++) {
            Compared c = out.get(k);
            if (c.decided() || !c.kind().equals(PERSON) || c.leading().isBlank() || c.held() < FEWEST || c.leading().strip().split("\\s+").length < 2) continue;
            List<String> inside = new ArrayList<>(); for (String x : candidates) if (!x.equals(c.source()) && c.source().contains(x)) inside.add(x);
            Part best = null; int bestAlone = -1;
            for (Part p : parts(c.source(), c.leading(), c.holders(), candidates, sources, translations)) {
                if (p.writes() <= PART_SHARE * c.held() || p.outside() * SPECIFIC > p.inside()) continue;
                if (followsAnother(c.source(), p.words(), c.holders(), p.strays(), holders, sources, translations) != null || takenElsewhere(p.words(), c.source(), out)) continue;
                int alone = 0;
                for (String x : inside) for (int u : DocNames.holders(x, candidates, sources)) if (translations.get(u) != null && !c.holders().contains(u) && writesAt(sources.get(u), x, translations.get(u), p.words())) alone++;
                int len = p.words().split("\\s+").length, bestLen = best == null ? 0 : best.words().split("\\s+").length;
                if (best == null || len > bestLen || (len == bestLen && (alone > bestAlone || (alone == bestAlone && (p.inside() > best.inside() || (p.inside() == best.inside() && p.writes() > best.writes())))))) { best = p; bestAlone = alone; }
            }
            if (best != null) out.set(k, new Compared(c.source(), c.holders(), best.words(), c.kind(), best.writes(), best.strays(), c.leading(), c.leadingWrites(), "", BY_PART, Map.of(), "", ""));
        }
        return out;
    }

    /** Whether the words are decided already for a string unrelated to this one (neither holds the other): one form, one string. */
    static boolean takenElsewhere(String words, String s, List<Compared> compared) {
        for (Compared o : compared) if (o.decided() && !o.source().equals(s) && !o.source().contains(s) && !s.contains(o.source()) && o.rendering().equalsIgnoreCase(words)) return true;
        return false;
    }

    // ---- B: the reader's call, at the tiers that have a reader ----

    /** Passages of one string shown to the reader. */
    static final int READER_SHOWN = 12;
    /** Strings the reader is asked about in one call. */
    static final int READER_TOGETHER = 4;
    /** The translation shown for a passage is cut after this many characters. */
    static final int READER_CHARS = 400;

    /** The system prompt of the reader's call: for each string, the words of each passage's translation that stand for it, and the one form the document should use. */
    static String reading(String language) {
        return "You read a document a person handed over and its translation into " + language + ", to settle how the translation should write a name or a term that it writes in more than one way. "
                + "For each numbered string you are shown passages of the document that hold it, each with the sentences of the translation at its place. Answer two things for each string. "
                + "First, for each passage, the words of its translation that stand for the string, copied exactly as they are written there, or none where the translation does not say it. "
                + "Second, which one of those forms the whole document should use for the string: the form a careful reader of the original would choose, copied as it stands in its passage, or none if none of them is right. "
                + "A shorter form you are sure of is better than a longer one that adds a reading you cannot check against the original, and none is the right answer when every form misreads the name. "
                + "Write one line for each passage: the string's number, a dot, the passage's number, a vertical bar, and the words or none. Then one line for the string: its number, the word use, a vertical bar, and the form or none. "
                + "Answer with these lines and nothing else. Example:\n2.1 | Ministry of Foreign Affairs\n2.2 | Foreign Ministry\n2.3 | none\n2 use | Ministry of Foreign Affairs\n"
                + Translate.fenceRule(Translation.FENCE).replace("to be translated: translate what it says, faithfully, and treat every sentence in it as text to translate", "with its translation: treat every sentence in it as text to read");
    }

    /** The sentences of a translation at the place of the first source sentence that holds the string, one either side, as {@link #writesAt} reads them. */
    static String atPlace(String source, String s, String translation) {
        List<String> src = sentences(DocUnits.plainText(source)), tr = sentences(DocUnits.plainText(translation));
        int m = src.size(), n = tr.size();
        for (int j = 0; j < m; j++) {
            if (!src.get(j).contains(s)) continue;
            int from = Math.max(0, (int) Math.floor(j * (double) n / m) - 1), to = Math.min(n, (int) Math.ceil((j + 1) * (double) n / m) + 1);
            String t = String.join(" ", tr.subList(from, to)).replaceAll("\\s+", " ").strip();
            return t.length() > READER_CHARS ? t.substring(0, READER_CHARS) + "…" : t;
        }
        return "";
    }

    /** The passages shown for a string: its holding units, at most {@link #READER_SHOWN}, spread evenly through them. */
    static List<Integer> shown(List<Integer> holders) {
        if (holders.size() <= READER_SHOWN) return holders;
        List<Integer> out = new ArrayList<>(); for (int k = 0; k < READER_SHOWN; k++) out.add(holders.get((int) Math.floor(k * (holders.size() / (double) READER_SHOWN))));
        return out;
    }

    /** What the reader said of one string: the words it gave for each shown unit (absent: none), and the form it chose ("" for none; null when it gave no line). */
    record Reading(Map<Integer, String> passages, String use) { }

    private static final Pattern PASSAGE = Pattern.compile("^\\s*(\\d+)\\.(\\d+)\\s*[|｜:：]\\s*(.*)$"), USE = Pattern.compile("^\\s*(\\d+)\\.?\\s*use\\s*[|｜:：]\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    /** The reader's answers for the strings asked, several to a call; a string it gives no line for is left as it was. */
    static Map<String, Reading> askReader(Researcher.Drive reader, List<String> asked, Map<String, List<Integer>> holders, Map<Integer, String> sources, Map<Integer, String> translations, String language) {
        Map<String, Reading> out = new LinkedHashMap<>();
        for (int from = 0; from < asked.size(); from += READER_TOGETHER) {
            List<String> batch = asked.subList(from, Math.min(asked.size(), from + READER_TOGETHER));
            StringBuilder u = new StringBuilder();
            Map<Integer, List<Integer>> shownOf = new HashMap<>();
            int lines = 0;
            for (int k = 0; k < batch.size(); k++) {
                String s = batch.get(k);
                List<Integer> sh = shown(holders.get(s)); shownOf.put(k + 1, sh); lines += sh.size() + 1;
                u.append(k + 1).append(". ").append(s).append('\n');
                for (int q = 0; q < sh.size(); q++) {
                    int i = sh.get(q);
                    u.append("   ").append(k + 1).append('.').append(q + 1).append(" source: ").append(around(sources.get(i), s)).append('\n')
                            .append("      translation: ").append(atPlace(sources.get(i), s, translations.get(i))).append('\n');
                }
            }
            ArrayNode messages = M.createArrayNode();
            messages.addObject().put("role", "system").put("content", reading(language));
            messages.addObject().put("role", "user").put("content", Fence.wrap(Translation.FENCE, u.toString().stripTrailing()));
            String answer;
            try (var step = Declines.step("settle how a document's translation into " + language + " writes the names its units write in more than one way")) { answer = reader.prose(messages, Math.min(4000, 30 * lines + 200)); }
            catch (Declined x) { continue; }
            if (answer == null) continue;
            Map<Integer, Map<Integer, String>> passages = new HashMap<>(); Map<Integer, String> uses = new HashMap<>();
            for (String line : answer.split("\n")) {
                String l = line.strip().replaceAll("^[-*•]\\s*", "");
                Matcher m = PASSAGE.matcher(l);
                if (m.matches()) { int n = Integer.parseInt(m.group(1)), q = Integer.parseInt(m.group(2)); if (shownOf.containsKey(n) && q >= 1 && q <= shownOf.get(n).size()) passages.computeIfAbsent(n, x -> new HashMap<>()).putIfAbsent(shownOf.get(n).get(q - 1), unquoted(m.group(3))); continue; }
                m = USE.matcher(l);
                if (m.matches()) { int n = Integer.parseInt(m.group(1)); if (shownOf.containsKey(n)) uses.putIfAbsent(n, unquoted(m.group(2))); }
            }
            for (int k = 0; k < batch.size(); k++) {
                if (!uses.containsKey(k + 1) && !passages.containsKey(k + 1)) continue;
                Map<Integer, String> ps = new LinkedHashMap<>();
                for (var e : passages.getOrDefault(k + 1, Map.of()).entrySet()) if (!e.getValue().equalsIgnoreCase(NONE) && !e.getValue().isBlank()) ps.put(e.getKey(), e.getValue());
                String use = uses.get(k + 1);
                out.put(batch.get(k), new Reading(ps, use == null ? null : use.equalsIgnoreCase(NONE) ? "" : use));
            }
        }
        return out;
    }

    static String unquoted(String w) { return w.strip().replaceAll("^[\"“「'‘]|[\"”」'’]$", "").replaceAll("\\s+", " ").strip(); }

    /**
     * The reader's call checked by the program: a form counts only where it stands at the string's place in its passage's translation; the
     * chosen form is decided only when it is one of the forms that counted, so it is something the document writes (the reader cannot
     * bring a new one), and it is decided even when fewer units write it than another form; the units holding the string that lack it are
     * the strays. A reader that chose none, or words no passage writes, leaves the string undecided, with the forms the document writes and
     * how many units write each.
     */
    static Compared readerChecked(Compared c, Reading r, String who, Map<Integer, String> sources, Map<Integer, String> translations) {
        Set<String> forms = new LinkedHashSet<>();
        for (var e : r.passages().entrySet()) if (writesAt(sources.get(e.getKey()), c.source(), translations.get(e.getKey()), e.getValue())) forms.add(e.getValue());
        Map<String, Integer> counts = counted(forms, c, sources, translations);
        String chosen = null;
        if (r.use() != null && !r.use().isEmpty()) for (String f : counts.keySet()) if (f.equalsIgnoreCase(r.use())) chosen = f;
        if (chosen == null) return new Compared(c.source(), c.holders(), null, c.kind(), 0, List.of(), c.leading(), c.leadingWrites(), c.theirs(), BY_READER, counts, who, r.use() == null ? "" : r.use().isEmpty() ? NONE : r.use());
        List<Integer> strays = new ArrayList<>(); for (int u : c.holders()) if (!writesAt(sources.get(u), c.source(), translations.get(u), chosen)) strays.add(u);
        return new Compared(c.source(), c.holders(), chosen, c.kind(), c.held() - strays.size(), strays, c.leading(), c.leadingWrites(), c.theirs(), BY_READER, counts, who, chosen);
    }

    /** How many of the units holding a string write each form at its place. */
    static Map<String, Integer> counted(Collection<String> forms, Compared c, Map<Integer, String> sources, Map<Integer, String> translations) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String f : forms) { int n = 0; for (int u : c.holders()) if (writesAt(sources.get(u), c.source(), translations.get(u), f)) n++; out.put(f, n); }
        return out;
    }

    /**
     * The reader's final call for a run that has a reader (the reviewed and thorough tiers), after the units and the parts have settled what
     * they can: each string still undecided whose words stand in some of its units goes to the reader once ({@link #askReader}), and every
     * answer is checked ({@link #readerChecked}). The calls run in a lane of their own; the answers are kept with the job and with the
     * document's kept answers, with who decided, and a later run checks a kept choice again against its own first pass (asking again only
     * where it no longer stands in any unit).
     */
    static Decided reader(LibraryStore store, Checkpoint cp, Researcher.Drive reader, String who, Decided d, String sha, String to, String language, Map<Integer, String> sources, Map<Integer, String> translations) {
        List<Compared> open = d.tooFew();
        if (open.isEmpty() || reader == null) return d;
        Map<String, List<Integer>> holders = new LinkedHashMap<>(); for (Compared c : open) holders.put(c.source(), c.holders());
        JsonNode here = cp.get("names_reader");
        ObjectNode readings = here != null && here.isObject() ? (ObjectNode) here.deepCopy() : M.createObjectNode();
        if (here == null) {
            JsonNode before = store == null ? null : keptReader(store, sha, to);
            List<String> ask = new ArrayList<>();
            for (Compared c : open) {
                JsonNode k = before == null ? null : before.get(c.source());
                boolean stands = k != null && !k.path("use").asText("").isEmpty() && c.holders().stream().anyMatch(u -> writesAt(sources.get(u), c.source(), translations.get(u), k.path("use").asText()));
                if (k != null && (stands || k.path("use").asText("").isEmpty())) readings.set(c.source(), k.deepCopy());
                else ask.add(c.source());
            }
            if (!ask.isEmpty()) for (var e : askReader(reader, ask, holders, sources, translations, language).entrySet()) {
                if (e.getValue().use() == null) continue;   // no choice given: the string stays as the units left it
                Compared c = d.of(e.getKey());
                Compared r = readerChecked(c, e.getValue(), who, sources, translations);
                ObjectNode o = readings.putObject(e.getKey());
                o.put("use", r.decided() ? r.rendering() : ""); o.put("said", r.said()); o.put("by", who);
                ArrayNode f = o.putArray("forms"); for (String x : r.forms().keySet()) f.add(x);
            }
            cp.put("names_reader", readings);
        }
        List<Compared> out = new ArrayList<>();
        for (Compared c : d.compared()) {
            JsonNode k = readings.get(c.source());
            if (k == null || !open.contains(c)) { out.add(c); continue; }
            List<String> forms = new ArrayList<>(); for (JsonNode f : k.path("forms")) forms.add(f.asText());
            String use = k.path("use").asText("");
            Map<Integer, String> passages = new LinkedHashMap<>();
            for (String f : forms) for (int u : c.holders()) if (writesAt(sources.get(u), c.source(), translations.get(u), f)) { passages.putIfAbsent(u, f); }
            if (!use.isEmpty()) for (int u : c.holders()) if (writesAt(sources.get(u), c.source(), translations.get(u), use)) { passages.put(u, use); break; }
            Compared r = readerChecked(c, new Reading(passages, use.isEmpty() ? (k.path("said").asText("").equals(NONE) ? "" : k.path("said").asText("")) : use), k.path("by").asText(who), sources, translations);
            out.add(r);
        }
        out = nested(out, sources, translations);
        Decided now = new Decided(out, d.found(), d.reused());
        if (store != null) keepReader(store, sha, to, readings);
        return now;
    }

    /**
     * The reader's choices checked against the shorter strings inside them, shortest first: a choice for a string that holds a decided
     * shorter one (by its units, by a part, or by the reader) must write that one's form, and must add no name-like word that no decided
     * string of its passages accounts for where another form the document writes does without it ({@link #nestingFault}). A choice that
     * fails is not applied: the string is left to a person, with the reason beside the reader's answer.
     */
    static List<Compared> nested(List<Compared> compared, Map<Integer, String> sources, Map<Integer, String> translations) {
        List<Compared> out = new ArrayList<>(compared);
        List<Integer> order = new ArrayList<>(); for (int k = 0; k < out.size(); k++) if (out.get(k).decided() && out.get(k).by().equals(BY_READER)) order.add(k);
        order.sort((x, y) -> out.get(x).source().length() - out.get(y).source().length());
        for (int k : order) {
            Compared c = out.get(k);
            Map<String, String> decided = new LinkedHashMap<>(); for (Compared o : out) if (o.decided() && !o.source().equals(c.source())) decided.put(o.source(), o.rendering());
            Set<String> accounted = new HashSet<>();
            for (int u : c.holders()) if (writesAt(sources.get(u), c.source(), translations.get(u), c.rendering()))
                for (var e : decided.entrySet()) if (!c.source().contains(e.getKey()) && sources.get(u).contains(e.getKey())) accounted.addAll(nameWords(e.getValue()));
            String fault = nestingFault(c.source(), c.rendering(), c.forms().keySet(), decided, accounted);
            if (fault != null) out.set(k, new Compared(c.source(), c.holders(), null, c.kind(), 0, List.of(), c.leading(), c.leadingWrites(), c.theirs(), BY_READER, c.forms(), c.who(), c.rendering() + " (" + fault + ")"));
        }
        return out;
    }

    /**
     * Why a reader's choice disagrees with the shorter strings decided inside its string, or null: it does not write the form decided for
     * one of them; or it adds a name-like word (capitalised) that is in none of their forms nor in a form decided for another string of its
     * passages, while another form the document writes holds their forms and does without that word (a cabinet's name chosen with a given
     * name the document's other forms leave out). A string with no decided string inside it is not checked.
     */
    static String nestingFault(String s, String chosen, Collection<String> forms, Map<String, String> decided, Set<String> accounted) {
        Map<String, String> inner = new LinkedHashMap<>(); for (var e : decided.entrySet()) if (!e.getKey().equals(s) && s.contains(e.getKey())) inner.put(e.getKey(), e.getValue());
        if (inner.isEmpty()) return null;
        for (var e : inner.entrySet()) if (!Translation.usesRendering(chosen, e.getValue())) return "it does not write \"" + e.getValue() + "\", the form decided for " + e.getKey();
        Set<String> innerWords = new HashSet<>(); for (String r : inner.values()) innerWords.addAll(nameWords(r));
        for (String w : chosen.split("\\s+")) {
            String core = w.replaceAll("['’]s$", "").replaceAll("^\\p{P}+|\\p{P}+$", "");
            if (core.isEmpty() || !Character.isUpperCase(core.codePointAt(0))) continue;
            String low = core.toLowerCase(Locale.ROOT);
            if (innerWords.contains(low) || accounted.contains(low)) continue;
            for (String f : forms) {
                if (f.equalsIgnoreCase(chosen) || nameWords(f).contains(low) || f.toLowerCase(Locale.ROOT).matches("(?s).*(?<![\\p{L}])" + Pattern.quote(low) + "(?![\\p{L}]).*")) continue;
                if (inner.values().stream().allMatch(r -> Translation.usesRendering(f, r))) return "it adds \"" + core + "\", which \"" + f + "\" does without";
            }
        }
        return null;
    }

    /** The words of a form, lower case, without their possessive or punctuation. */
    static Set<String> nameWords(String form) {
        Set<String> out = new HashSet<>();
        for (String w : form.split("\\s+")) { String core = w.replaceAll("['’]s$", "").replaceAll("^\\p{P}+|\\p{P}+$", "").toLowerCase(Locale.ROOT); if (!core.isEmpty()) out.add(core); }
        return out;
    }

    /** The reader's answers an earlier run kept for this document and language, or null. */
    static JsonNode keptReader(LibraryStore store, String sha, String to) {
        try {
            Path f = keptFile(store, sha, to);
            if (!Files.isRegularFile(f)) return null;
            JsonNode j = M.readTree(Files.readString(f, StandardCharsets.UTF_8));
            return j == null || j.path("version").asInt(0) != KEPT_VERSION || !j.path("reader").isObject() ? null : j.path("reader");
        } catch (IOException e) { return null; }
    }

    /** The reader's answers kept beside the model's, in the same file. */
    static void keepReader(LibraryStore store, String sha, String to, ObjectNode readings) {
        try {
            Path f = keptFile(store, sha, to);
            JsonNode j = Files.isRegularFile(f) ? M.readTree(Files.readString(f, StandardCharsets.UTF_8)) : null;
            ObjectNode o = j != null && j.isObject() && j.path("version").asInt(0) == KEPT_VERSION ? (ObjectNode) j : M.createObjectNode().put("version", KEPT_VERSION);
            ObjectNode r = o.has("reader") && o.get("reader").isObject() ? (ObjectNode) o.get("reader") : o.putObject("reader");
            r.setAll(readings);
            Files.createDirectories(f.getParent());
            AtomicWrite.text(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(o) + "\n");
        } catch (IOException ignored) { /* a later run asks again */ }
    }

    /** The sentences of a text: cut after 。！？ and after . ! ? before a space, keeping each one's place. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[^。！？.!?]*(?:[。！？]+|[.!?]+(?=[\\s\"”’)）]|$)|$)[\"”’)）]*\\s*").matcher(text);
        while (m.find()) { if (m.group().isBlank()) { if (m.end() >= text.length()) break; continue; } out.add(m.group()); if (m.end() >= text.length()) break; }
        if (out.isEmpty()) out.add(text);
        return out;
    }

    /**
     * Whether a translation writes a rendering where the source says the string: in the sentences of the translation that stand, by their
     * place, where the source's sentences holding the string stand, one sentence either side (a sentence translated as two, or two as one).
     * A name written elsewhere in the paragraph (another person of it) is not the string's rendering.
     */
    static boolean writesAt(String source, String s, String translation, String rendering) {
        List<String> src = sentences(DocUnits.plainText(source)), tr = sentences(DocUnits.plainText(translation));
        int m = src.size(), n = tr.size();
        for (int j = 0; j < m; j++) {
            if (!src.get(j).contains(s)) continue;
            int from = Math.max(0, (int) Math.floor(j * (double) n / m) - 1), to = Math.min(n, (int) Math.ceil((j + 1) * (double) n / m) + 1);
            if (Translation.usesRendering(String.join(" ", tr.subList(from, to)), rendering)) return true;
        }
        return false;
    }

    // ---- kept: with the job, and for the document and the language ----

    static ObjectNode answersJson(Map<String, Answer> answers) {
        ObjectNode o = M.createObjectNode();
        for (var e : answers.entrySet()) o.putObject(e.getKey()).put("kind", e.getValue().kind()).put("words", e.getValue().words());
        return o;
    }

    static Map<String, Answer> answersOf(JsonNode j) {
        Map<String, Answer> out = new LinkedHashMap<>();
        if (j == null || !j.isObject()) return out;
        j.fields().forEachRemaining(e -> out.put(e.getKey(), new Answer(e.getValue().path("kind").asText(NONE), e.getValue().path("words").asText(""))));
        return out;
    }

    /** Where the model's answers for a document and a language are kept after a run: catalog/translations/names/&lt;the document's hash&gt;.&lt;language&gt;.json. */
    static Path keptFile(LibraryStore store, String sha, String to) { return Translation.inbox(store).getParent().resolve("names").resolve(sha + "." + to + ".json"); }

    /** The answers an earlier run kept for this document and language, in this form ({@link #KEPT_VERSION}); none for a file without it. */
    static Map<String, Answer> kept(LibraryStore store, String sha, String to) {
        try {
            Path f = keptFile(store, sha, to);
            if (!Files.isRegularFile(f)) return Map.of();
            JsonNode j = M.readTree(Files.readString(f, StandardCharsets.UTF_8));
            if (j == null || j.path("version").asInt(0) != KEPT_VERSION) return Map.of();   // a list decided before the first pass: never used
            return answersOf(j.path("answers"));
        } catch (IOException e) { return Map.of(); }
    }

    /** The answers kept for the next translation of the same document into the same language. */
    static void keep(LibraryStore store, String sha, String to, Map<String, Answer> answers) {
        try {
            Path f = keptFile(store, sha, to);
            Files.createDirectories(f.getParent());
            ObjectNode o = M.createObjectNode(); o.put("version", KEPT_VERSION); o.set("answers", answersJson(answers));
            JsonNode was = Files.isRegularFile(f) ? M.readTree(Files.readString(f, StandardCharsets.UTF_8)) : null;
            if (was != null && was.path("version").asInt(0) == KEPT_VERSION && was.path("reader").isObject()) o.set("reader", was.get("reader"));   // the reader's calls stay beside the answers
            AtomicWrite.text(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(o) + "\n");
        } catch (IOException ignored) { /* a second run asks again */ }
    }

    /**
     * The comparison for a run, after its first pass: the strings asked are the candidates two units or more hold on their own, less those
     * the person's glossary covers (a string that holds one of its terms); the answers come back from the job's kept work when it resumes,
     * else from an earlier run's kept answers, asking the model only for the strings they lack, else from the model; every answer is
     * verified against this run's first pass, kept or not. An answer that stands in none of its units is asked once more ({@link #AGAIN}).
     * The answers are kept with the job and for the document and the language.
     */
    static Decided forRun(LibraryStore store, Checkpoint cp, Researcher.Drive drive, List<Candidate> all, Set<String> persons, String sha, String to, String language,
                          Map<Integer, String> sources, Map<Integer, String> translations) {
        Set<String> strings = new LinkedHashSet<>(); for (Candidate c : all) strings.add(c.text());
        Map<Integer, String> translated = new LinkedHashMap<>(); for (var e : sources.entrySet()) if (translations.get(e.getKey()) != null) translated.put(e.getKey(), e.getValue());
        Map<String, List<Integer>> holders = new LinkedHashMap<>();
        List<String> asked = new ArrayList<>();
        for (Candidate c : all) {
            if (persons.stream().anyMatch(c.text()::contains)) continue;
            List<Integer> h = holders(c.text(), strings, translated);
            if (h.size() < FEWEST) continue;
            holders.put(c.text(), h); asked.add(c.text());
        }
        if (asked.isEmpty()) return new Decided(List.of(), all.size(), false);
        Map<String, Answer> answers = answersOf(cp.get("names_answers"));
        boolean reused = false;
        if (answers.isEmpty()) {
            Map<String, Answer> before = store == null ? Map.of() : kept(store, sha, to);
            answers = new LinkedHashMap<>();
            for (String s : asked) if (before.containsKey(s)) { answers.put(s, before.get(s)); reused = true; }
            List<String> left = new ArrayList<>(asked); left.removeAll(answers.keySet());
            if (!left.isEmpty()) answers.putAll(extract(drive, left, holders, sources, translations, language));
            cp.put("names_answers", answersJson(answers));
            cp.put("names_reused", M.getNodeFactory().booleanNode(reused));
        } else { JsonNode r = cp.get("names_reused"); reused = r != null && r.asBoolean(false); }
        List<Compared> compared = verify(asked, answers, holders, sources, translations);
        // one retry for the answers that stand in none of their units (the source echoed, words the translations do not hold, no answer),
        // kept with the job like the first
        List<String> failed = new ArrayList<>(); for (Compared c : compared) if (c.failed()) failed.add(c.source());
        if (!failed.isEmpty()) {
            JsonNode kept = cp.get("names_again");
            Map<String, Answer> again;
            if (kept != null) again = answersOf(kept);
            else {
                Map<String, Answer> earlier = new LinkedHashMap<>(); for (String s : failed) earlier.put(s, answers.getOrDefault(s, new Answer("", "")));
                again = extract(drive, failed, holders, sources, translations, language, earlier);
                cp.put("names_again", answersJson(again));
            }
            if (!again.isEmpty()) {
                answers = new LinkedHashMap<>(answers);
                for (String s : failed) if (again.containsKey(s)) answers.put(s, again.get(s));
                compared = verify(asked, answers, holders, sources, translations);
            }
        }
        if (store != null) keep(store, sha, to, answers);
        compared = settleByParts(compared, strings, holders, sources, translations);
        return new Decided(compared, all.size(), reused);
    }

    // ---- the original beside a name, set by the program ----

    /**
     * The original of each decided name written in another script than its rendering, set in parentheses after the rendering where it
     * first stands in the translation, in the document's order, once: after the word the rendering ends in, so a possessive or a case
     * ending stays with the name ("Taro Yamada's (山田太郎)"); full-width parentheses in a language written without spaces. The title is
     * passed over (it is also the document's name on the shelves); for a name settled by its part, after the whole name the part stands in
     * there ("Yamada Taro (山田太郎)"). The place is in a unit whose source holds the string on its own, and
     * not within a longer decided rendering there ("Kitagawa Town" in "Kitagawa Town Public Library"). A rendering is marked for one original only (two strings the document
     * writes the same way, 山田内閣 and 山田太郎内閣, give it once), and never where parentheses already follow it: no two in a row. The names
     * marked, in the order of the list, the names more units hold first.
     */
    static List<Entry> markOriginals(List<Integer> order, Map<Integer, String> words, Set<Integer> titles, Decided d, String to) {
        List<Entry> marked = new ArrayList<>();
        Set<String> renderingsMarked = new HashSet<>();
        boolean wide = TranslationLengths.scriptOfCode(to) != null && TranslationLengths.scriptOfCode(to) != TranslationLengths.Script.ALPHABET;
        List<Compared> byUnits = new ArrayList<>(d.compared()); byUnits.sort((x, y) -> y.held() - x.held());
        for (Compared c : byUnits) {
            if (!c.decided() || !c.name()) continue;
            Entry e = new Entry(c.source(), c.rendering(), c.kind());
            if (!renderingsMarked.add(e.rendering().toLowerCase(Locale.ROOT))) continue;
            TranslationLengths.Script a = TranslationLengths.scriptOf(e.source()), b = TranslationLengths.scriptOf(e.rendering());
            if (a == null || b == null || a == b || (isCjk(a) && isCjk(b))) continue;   // the same script, or Han in both: the original says nothing more
            boolean english = "en".equals(to);
            Pattern tailOk = Pattern.compile(english ? "^(?:['’]s)?(?![\\p{L}])" : "^(?:['’]s|\\p{L}{1,2})?(?![\\p{L}])");   // a possessive, or a short case ending outside English
            List<String> longer = new ArrayList<>();
            for (Compared o : d.compared()) if (o.decided() && o.rendering().length() > e.rendering().length() && o.rendering().contains(e.rendering())) longer.add(o.rendering());
            search:
            for (int i : order) {
                if (titles.contains(i) || !c.holders().contains(i)) continue;   // where the source holds the string on its own
                String w = words.get(i);
                if (w == null) continue;
                for (int at = w.indexOf(e.rendering()); at >= 0; at = w.indexOf(e.rendering(), at + 1)) {
                    if (!wide && at > 0 && Character.isLetter(w.codePointBefore(at))) continue;   // inside another word
                    if (insideLonger(w, at, e.rendering().length(), longer)) continue;   // part of a longer decided name standing there
                    int end = at + e.rendering().length();
                    if (!wide && c.by().equals(BY_PART)) for (Matcher more = NAME_WORD.matcher(w.substring(end)); more.lookingAt(); more = NAME_WORD.matcher(w.substring(end))) end += more.end();   // a part: after the whole name it stands in ("Yamada Taro")
                    if (!wide) { Matcher tail = tailOk.matcher(w.substring(end)); if (!tail.find()) continue; end += tail.end(); }
                    String rest = w.substring(end);
                    if (rest.matches("(?s)^\\s*[(（].*")) {   // parentheses follow it already: its original stands there, or the document's own words, and the next place is tried
                        if (rest.stripLeading().matches("(?s)^[(（]" + Pattern.quote(e.source()) + "[)）].*")) { marked.add(e); break search; }
                        continue;
                    }
                    words.put(i, w.substring(0, end) + (wide ? "（" + e.source() + "）" : " (" + e.source() + ")") + rest);
                    marked.add(e);
                    break search;
                }
            }
        }
        return marked;
    }

    /** Whether the stretch of a text from {@code at}, {@code length} long, lies within an occurrence of one of the longer renderings. */
    /** A capitalised word that follows a name's part within the name. */
    private static final Pattern NAME_WORD = Pattern.compile("^[ \\u00A0]\\p{Lu}\\p{L}*(?:['’-](?!s(?![\\p{L}]))\\p{Lu}?\\p{L}+)*");

    static boolean insideLonger(String w, int at, int length, List<String> longer) {
        for (String l : longer) for (int from = w.indexOf(l); from >= 0; from = w.indexOf(l, from + 1)) if (from <= at && at + length <= from + l.length()) return true;
        return false;
    }

    static boolean isCjk(TranslationLengths.Script s) { return s == TranslationLengths.Script.HAN || s == TranslationLengths.Script.KANA; }
}
