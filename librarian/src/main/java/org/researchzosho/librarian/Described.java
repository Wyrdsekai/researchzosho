package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Stopping;
import org.researchzosho.tools.Entities;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.WebFetchTool;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Other people's words about a video, as a source of candidates. A person who remembers a video remembers what happens in it, and a
 * title seldom says that: on the find-one bench's first measurement (2026-11-06) the pool enumerated from the thing's name held the
 * answer for one case of twelve, because a commercial is titled by its brand, a music video by its band and a sketch by its joke. What
 * happens in a video is written down elsewhere: by whoever posted it on a blog, listed it, reviewed it or asked about it. So the model
 * gives, as data, what the person remembers in short phrases of their own words; the program makes the queries from the thing's name and
 * each phrase, asks the web search for them, takes the YouTube videos among the results and reads the pages among them for the videos
 * they link or embed. Each video found this way records the page that led to it, and is placed by what a machine can check: how many of
 * the remembered phrases the text beside the link holds. No engine's rank and no count of views enters the order, and the model never
 * writes a query.
 */
public final class Described {
    private Described() { }

    static final ObjectMapper M = new ObjectMapper();

    /** One page read for the videos it links or embeds: its HTML, or null when it could not be read. A test stands in. */
    public interface Pages { String read(String url) throws Exception; }
    public static volatile Pages PAGES = Described::readLive;

    /**
     * What is remembered, said in other languages by the model, once: the language's code to the phrases in the order given. The model
     * says the person's words again in another language, which is data; the program makes the queries. Null means no model answers.
     */
    public interface Phraser { Map<String, List<String>> say(List<String> phrases, List<String> languages) throws Exception; }
    public static volatile Phraser PHRASER = Described::sayLive;

    /** The pause between two questions to the web search, and between two pages of one site; a test sets them to nothing. */
    public static volatile long paceMs = 1_500, sitePaceMs = 1_000;

    /** How many remembered phrases an ask carries; how many of them make queries in the person's language and in each other language; how many other languages. */
    public static final int PHRASES = 8, QUERY_PHRASES = 8, OTHER_PHRASES = 4, OTHER_LANGUAGES = 3;
    /** How many pages one run reads, how many of one site, how many videos one page may give, and how many are read at once. */
    public static final int PAGES_MOST = 100, PER_SITE = 3, PER_PAGE = 40, READERS = 8;
    /** The text taken either side of a link as what the page says about that video, in characters; the head of a page that links one video only. */
    static final int WINDOW = 700, WHOLE = 6_000, BYTES_MOST = 2_000_000;
    /** The longest this source may take within an enumeration, and what of that is left for reading the pages. */
    public static final long CEILING_MS = 6 * 60_000L, KEEP_FOR_PAGES_MS = 90_000L;
    /** How a video found this way names its source: "web: " and the site of the page that led to it. */
    public static final String SOURCE = "web: ";

    /** One result of a web search, as the search tools write it. */
    public record Row(String title, String url, String snippet) { }

    /** How much of what is remembered a text holds: how many phrases, how many of their words, and which phrases. */
    public record Fit(int phrases, int words, List<String> held) {
        public static final Fit NONE = new Fit(0, 0, List.of());
        public boolean better(Fit o) { return phrases != o.phrases ? phrases > o.phrases : words > o.words; }
        /** What two texts about one video hold together: the phrases either holds, each once. */
        public Fit with(Fit o) {
            if (o == null || (o.phrases == 0 && o.words == 0)) return this;
            List<String> both = new ArrayList<>(held);
            for (String h : o.held) if (!both.contains(h)) both.add(h);
            return new Fit(both.size(), Math.max(words, o.words), both);
        }
    }

    /** One video as one page gave it: the page, its site, the title when the page was the video's own, what the text beside it holds, and, for whoever reads the run, that text and the query that brought the page. */
    public record Found(String id, String site, String page, String title, Fit fit, String beside, String query) { }

    /** What the source came to: the videos, the record of every query and page for whoever reads the run, the sentences, and the progress lines. */
    public record Result(List<Found> found, ObjectNode record, List<String> notes, List<String> progress, List<Phrase> phrases, int asked, int answered) { }

    // ---- what is remembered, as words a machine can look for

    /** One remembered phrase: the person's words, and the words that count in each form of it, theirs and the other languages'. */
    public record Phrase(String words, List<List<String>> forms) {
        public static Phrase of(String words, List<String> alsoSaid) {
            List<List<String>> forms = new ArrayList<>();
            List<String> own = tokens(words);
            if (!own.isEmpty()) forms.add(own);
            for (String s : alsoSaid) { List<String> t = tokens(s); if (!t.isEmpty() && !forms.contains(t)) forms.add(t); }
            return new Phrase(words, forms);
        }
    }

    /** The phrases of an ask with nothing said in another language. */
    public static List<Phrase> phrases(List<String> remembered) { return phrases(remembered, Map.of()); }

    /** The phrases of an ask, each with what the model said for it in the other languages. */
    public static List<Phrase> phrases(List<String> remembered, Map<String, List<String>> inOther) {
        List<Phrase> out = new ArrayList<>();
        for (int i = 0; i < remembered.size(); i++) {
            List<String> also = new ArrayList<>();
            for (List<String> said : inOther.values()) if (i < said.size() && !said.get(i).isBlank()) also.add(said.get(i));
            out.add(Phrase.of(remembered.get(i), also));
        }
        return out;
    }

    /** Words that say nothing about one video: the small words of the Latin-script languages, and the words every page about a video has. A no, a not and a without stay: "nobody speaks" and "no people in it" are remembered by them. */
    static final Set<String> SMALL = Set.copyOf(List.of("a", "an", "the", "of", "in", "on", "at", "to", "is", "it", "as", "by", "be", "or", "so", "we", "he", "me", "my", "do", "up", "if", "us", "am", "and", "but", "for",
            "are", "was", "were", "his", "her", "him", "she", "you", "your", "its", "it's", "their", "they", "them", "then", "than", "that", "this", "these", "those", "there", "here", "has", "had", "have", "been", "being",
            "who", "what", "which", "when", "where", "while", "with", "from", "into", "onto", "out", "over", "about", "like", "just", "also", "very", "some", "any", "one", "all", "each", "every", "can", "could", "would",
            "will", "does", "did", "how", "why", "something", "someone", "thing", "things", "kind", "sort", "maybe", "think", "remember", "video", "videos", "youtube", "clip",
            "el", "la", "los", "las", "un", "una", "que", "con", "por", "para", "del", "de", "en", "es", "se", "su", "al", "lo", "como", "um", "uma", "com", "em", "da", "os", "na",
            "der", "die", "das", "und", "ein", "eine", "mit", "den", "dem", "ist", "im", "le", "les", "des", "une", "est", "et", "dans", "pour", "avec", "du", "au", "sur"));

    private static final Pattern RUN = Pattern.compile("[\\p{L}\\p{N}]+(?:'[\\p{L}]+)?");

    /** Whether a character is written without spaces between words: kana, Chinese characters, hangul. */
    static boolean unspaced(int c) { return (c >= 0x3040 && c <= 0x30ff) || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf) || (c >= 0xac00 && c <= 0xd7af); }

    /**
     * The words of a phrase that count: every word of two letters or more that is not a small word, without its ending ("w" before it);
     * and, where the script puts no space between words, every pair of neighbouring characters ("c" before it), since a machine cannot
     * tell where a Japanese or Korean word ends.
     */
    public static List<String> tokens(String phrase) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = RUN.matcher(phrase == null ? "" : phrase.toLowerCase(Locale.ROOT));
        while (m.find()) {
            StringBuilder spaced = new StringBuilder(), tight = new StringBuilder();
            String run = m.group();
            for (int i = 0; i < run.length(); ) {
                int c = run.codePointAt(i); i += Character.charCount(c);
                if (unspaced(c)) { word(out, spaced); tight.appendCodePoint(c); }
                else { pairs(out, tight); spaced.appendCodePoint(c); }
            }
            word(out, spaced); pairs(out, tight);
        }
        return new ArrayList<>(out);
    }

    private static void word(Set<String> out, StringBuilder w) {
        String s = w.toString(); w.setLength(0);
        if (s.length() >= 2 && !SMALL.contains(s)) out.add("w" + stem(s));
    }

    private static void pairs(Set<String> out, StringBuilder run) {
        String s = run.toString(); run.setLength(0);
        int[] cs = s.codePoints().toArray();
        for (int i = 0; i + 1 < cs.length; i++) out.add("c" + new String(cs, i, 2));
    }

    private static final String[] ENDINGS = {"'s", "ing", "ed", "es", "ly", "s"};

    /**
     * A word without its ending, so that "falls", "falling" and "fall" are one word and "candies" is "candy": the common English endings,
     * a final e, a doubled last letter; a long word of another language by its first five letters, which is where its inflection is not.
     */
    static String stem(String w) {
        if (w.chars().anyMatch(c -> c >= 128)) return w.length() >= 6 ? w.substring(0, 5) : w;
        String s = w;
        if (s.length() > 4 && s.endsWith("ies")) s = s.substring(0, s.length() - 3) + "y";
        else for (String end : ENDINGS) if (s.length() > end.length() + 2 && s.endsWith(end)) { s = s.substring(0, s.length() - end.length()); break; }
        if (s.length() > 3 && s.endsWith("e")) s = s.substring(0, s.length() - 1);
        if (s.length() > 3 && s.charAt(s.length() - 1) == s.charAt(s.length() - 2) && Character.isLetter(s.charAt(s.length() - 1))) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** A text as a machine looks through it: where each of its words stands, without its ending, and the text itself in lower case for the scripts without spaces. */
    record Text(Map<String, List<Integer>> at, String lower) {
        static Text of(String text) {
            String lower = (text == null ? "" : text).toLowerCase(Locale.ROOT);
            Map<String, List<Integer>> at = new LinkedHashMap<>();
            Matcher m = RUN.matcher(lower);
            while (m.find()) { String w = m.group(); if (w.length() >= 2 && !unspaced(w.codePointAt(0))) at.computeIfAbsent(stem(w), k -> new ArrayList<>()).add(m.start()); }
            return new Text(at, lower);
        }
        /** Every place a token stands, as offsets into the text. */
        List<Integer> places(String token) {
            if (token.charAt(0) == 'w') return at.getOrDefault(token.substring(1), List.of());
            List<Integer> out = new ArrayList<>();
            String pair = token.substring(1);
            for (int i = lower.indexOf(pair); i >= 0 && out.size() < 200; i = lower.indexOf(pair, i + 1)) out.add(i);
            return out;
        }
    }

    /**
     * How many of a phrase's words a text must have to hold the phrase: six in ten, and two at least. A phrase of one word is never held
     * by that word alone, since one word is in too many texts to say which video a text is about; it counts among the words.
     */
    static int needs(int words) { return Math.max(2, (int) Math.ceil(0.6 * words)); }

    /**
     * How far apart the words of a phrase may stand in a text that holds it, in characters: forty for each word that counts, a hundred
     * at least, where the script has spaces; four for each pair of characters, thirty at least, where it has none. Words of a phrase
     * scattered over a long page are a long page, not the phrase: a guest book held a remembered sentence by one of its words here and another there.
     */
    static int reach(List<String> form) { return form.get(0).charAt(0) == 'c' ? Math.max(30, 4 * form.size()) : Math.max(100, 40 * form.size()); }

    /** How many of a form's words stand within its reach of one another somewhere in the text, at most; and in {@code any[0]}, how many stand anywhere. */
    static int together(Text t, List<String> form, int[] any) {
        List<int[]> events = new ArrayList<>();   // place, which word
        int present = 0;
        for (int k = 0; k < form.size(); k++) { List<Integer> ps = t.places(form.get(k)); if (!ps.isEmpty()) present++; for (int p : ps) events.add(new int[]{p, k}); }
        any[0] = present;
        if (present < 2) return present;
        events.sort((x, y) -> Integer.compare(x[0], y[0]));
        int reach = reach(form), best = 0, distinct = 0, left = 0;
        int[] count = new int[form.size()];
        for (int[] e : events) {
            if (count[e[1]]++ == 0) distinct++;
            while (e[0] - events.get(left)[0] > reach) { if (--count[events.get(left)[1]] == 0) distinct--; left++; }
            best = Math.max(best, distinct);
        }
        return best;
    }

    /**
     * How much of what is remembered a text holds: a phrase is held when six in ten of its words, two at least, stand near one another
     * in the text, in any of its forms; the words count wherever they stand.
     */
    public static Fit fit(String text, List<Phrase> phrases) {
        if (text == null || text.isBlank() || phrases.isEmpty()) return Fit.NONE;
        Text t = Text.of(text);
        int held = 0, words = 0;
        List<String> which = new ArrayList<>();
        for (Phrase p : phrases) {
            int best = 0; boolean has = false;
            for (List<String> form : p.forms()) {
                int[] any = {0};
                if (together(t, form, any) >= needs(form.size())) has = true;
                best = Math.max(best, any[0]);
            }
            if (has) { held++; which.add(p.words()); }
            words += best;
        }
        return held == 0 && words == 0 ? Fit.NONE : new Fit(held, words, which);
    }

    // ---- the queries, the program's own

    /** One question to the web search, and the language of its words. */
    public record Query(String q, String language) { }

    /** A phrase as a query carries it: no quotation mark, no search operator, no more than a line. */
    static String plain(String phrase) {
        String s = phrase == null ? "" : phrase.replaceAll("[\"“”„«»]", " ").replaceAll("(?i)\\b(site|inurl|intitle|filetype|intext|cache|related)\\s*:", " ").replaceAll("(^|\\s)[-+]+(?=\\S)", " ").replaceAll("\\s+", " ").strip();
        return s.length() > 100 ? s.substring(0, 100).strip() : s;
    }

    /** The thing's name in a language: the first the person's call gave, else the model's. */
    static String thingIn(List<Candidates.Named> names, String language) {
        for (Candidates.Named n : names) if (n.lang().equals(language)) return n.name();
        return "";
    }

    /** The thing and a phrase as one query: the thing first, unless the phrase already names it. */
    static String with(String thing, String phrase) { return thing.isEmpty() || Candidates.names(phrase, List.of(thing)) ? phrase : thing + " " + phrase; }

    /**
     * The queries for an ask: in the person's language, the thing with each remembered phrase; each phrase with the one after it and
     * without the thing, since two remembered things together say more than the thing's name, and the name the model gave may be the
     * wrong one; the words that name somebody or something, a character, a brand, a year, together with the thing; each phrase with the
     * word youtube, which the engines read as where to look without leaving the rest of the web out (tried by hand on 2026-11-06: a phrase
     * that was nearly a video's title found it this way and not over youtube.com alone); and the first phrases and the names with the
     * thing again over youtube.com alone, where an index reads a video's own title and description.
     * The other names the call gave in the person's language go once each with the first. Then the same, shorter, in each other language
     * the phrases were said in. No query is the model's.
     */
    public static List<Query> queries(List<Candidates.Named> names, String own, List<String> remembered, Map<String, List<String>> inOther) {
        List<Query> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        compose(out, seen, thingIn(names, own), remembered, own, QUERY_PHRASES);
        // the other names the person's call gave in their language, each once with the first: when the call names what appears in the video
        // as a thing of its own, the two together are a question about the video
        String first = thingIn(names, own);
        int more = 0;
        for (Candidates.Named n : names) if (n.given() && n.lang().equals(own) && !n.name().equals(first) && more < 2) { more++; String q = first + " " + plain(n.name()); if (seen.add(q.toLowerCase(Locale.ROOT))) out.add(new Query(q, own)); }
        for (var e : inOther.entrySet()) compose(out, seen, thingIn(names, e.getKey()), e.getValue(), e.getKey(), OTHER_PHRASES);
        return out;
    }

    /**
     * The words of what is remembered that name somebody or something: a word with a capital that does not open its phrase, a word all in
     * capitals, a word with a digit in it; a word that opens a phrase when it stands with a capital elsewhere too. A character's name, a
     * brand, a year: the words a page about the video is most sure to have, put together as one question. Eight at most, each once.
     */
    static List<String> names(List<String> phrases) {
        Set<String> inside = new LinkedHashSet<>(), opening = new LinkedHashSet<>();
        for (String p : phrases) {
            String[] ws = p.split("[^\\p{L}\\p{N}'’-]+");
            boolean first = true;
            for (String w : ws) {
                if (w.isEmpty()) continue;
                boolean capital = Character.isUpperCase(w.codePointAt(0)), all = w.length() >= 2 && w.codePoints().anyMatch(Character::isUpperCase) && w.codePoints().noneMatch(Character::isLowerCase), digit = w.chars().anyMatch(Character::isDigit);
                boolean small = SMALL.contains(w.toLowerCase(Locale.ROOT)) || w.equals("I") || w.startsWith("I'") || w.startsWith("I’");
                if (!small && (digit || all || (capital && !first))) inside.add(w);
                else if (!small && capital) opening.add(w);
                first = false;
            }
        }
        List<String> out = new ArrayList<>();
        for (String w : inside) if (out.size() < 8) out.add(w);
        for (String w : opening) if (inside.contains(w) && !out.contains(w) && out.size() < 8) out.add(w);
        return out;
    }

    private static void compose(List<Query> out, Set<String> seen, String thing, List<String> phrases, String language, int most) {
        List<String> ps = new ArrayList<>();
        for (String p : phrases) { String s = plain(p); if (!s.isEmpty() && !ps.contains(s) && ps.size() < most) ps.add(s); }
        List<String> qs = new ArrayList<>();
        for (String p : ps) qs.add(with(thing, p));
        for (int i = 0; i + 1 < ps.size(); i++) qs.add(ps.get(i) + " " + ps.get(i + 1));
        List<String> named = names(ps);
        if (!named.isEmpty()) qs.add(with(thing, String.join(" ", named)));
        boolean own = most > OTHER_PHRASES;
        for (String p : ps.subList(0, Math.min(own ? ps.size() : 2, ps.size()))) qs.add(p + " youtube");
        for (String p : ps.subList(0, Math.min(own ? 3 : 2, ps.size()))) qs.add("site:youtube.com " + with(thing, p));
        if (!named.isEmpty()) qs.add("site:youtube.com " + with(thing, String.join(" ", named)));
        for (String q : qs) if (seen.add(q.toLowerCase(Locale.ROOT))) out.add(new Query(q, language));
    }

    /** The other languages the person's call named the thing in, by the script of each name: the phrases are said in these. */
    static List<String> otherLanguages(List<Candidates.Named> names, String own) {
        List<String> out = new ArrayList<>();
        for (Candidates.Named n : names) if (n.given() && !n.lang().equals(own) && !out.contains(n.lang()) && out.size() < OTHER_LANGUAGES) out.add(n.lang());
        return out;
    }

    /** The model's last answer as it wrote it, kept for the run's record when a language asked for could not be read out of it. */
    static volatile String lastSaid = "";

    /** The model says each remembered phrase in the other languages: one bounded call, the phrases read off its lines by language and number. */
    static Map<String, List<String>> sayLive(List<String> phrases, List<String> languages) {
        Researcher.Drive drive = Candidates.drives.get();
        if (drive == null) return null;
        List<String> names = new ArrayList<>();
        for (String code : languages) names.add(Candidates.languageName(code));
        StringBuilder p = new StringBuilder("A person remembers a video and says what they remember of it, one thing a line, between the marks:\n").append(Fence.open("REMEMBERED")).append('\n');
        for (int i = 0; i < phrases.size(); i++) p.append(i + 1).append(". ").append(phrases.get(i).replace("\n", " ")).append('\n');
        p.append(Fence.close("REMEMBERED")).append('\n').append(Fence.rule("REMEMBERED")).append("\n\nSay each line in ").append(String.join(" and in ", names))
         .append(", in a few plain words, as a person who writes that language would describe the same thing. One per line as language: number: the words, in that language's own script, and nothing else.");
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", p.toString());
        String raw;
        try (var step = Declines.step("say what a person remembers of a video in other languages")) { raw = drive.classify(msgs, 900); }
        if (raw == null) return null;
        lastSaid = raw;
        return said(raw, phrases.size(), languages);
    }

    private static final Pattern SAID = Pattern.compile("^([^:：\\d]{2,40}?)\\s*[:：]\\s*(\\d+)\\s*[:：.)、]\\s*(.+)$"), NUMBERED = Pattern.compile("^(\\d+)\\s*[:：.)、]\\s*(.+)$"), LABELLED = Pattern.compile("^([^:：\\d]{2,40}?)\\s*[:：]\\s*(.+)$");

    /** The language a label names, by any word of it: "Japanese", "ja", "Chinese (Simplified)", "한국어"; "" when it names none. */
    static String languageNamed(String label) {
        String s = label.replaceAll("\\(.*?\\)|（.*?）", " ").replaceAll("[^\\p{L}\\s-]", " ").strip();
        String code = Candidates.languageCode(s);
        if (!code.isEmpty()) return code;
        for (String w : s.split("\\s+")) { code = Candidates.languageCode(w); if (!code.isEmpty()) return code; }
        return "";
    }

    /**
     * The model's lines as phrases by language, each at its number, read as models write them: "language: number: words" a line, a
     * language on a line of its own with its numbered lines under it, or "number. language: words". A language not asked for and a number
     * past the end are left; a language with no line is absent, a number with none is blank.
     */
    static Map<String, List<String>> said(String raw, int count, List<String> languages) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String current = "";
        for (String line : raw.split("\\R")) {
            String l = line.strip().replace("**", "").replaceFirst("^[-*•·#>\\s]+", "").strip();
            if (l.isEmpty()) continue;
            if (l.length() <= 40 && !l.matches(".*\\d.*")) { String alone = languageNamed(l.replaceFirst("[:：]\\s*$", "")); if (!alone.isEmpty()) { current = alone; continue; } }
            String code, words; int n;
            Matcher m = SAID.matcher(l);
            if (m.matches() && !languageNamed(m.group(1)).isEmpty()) { code = languageNamed(m.group(1)); n = Integer.parseInt(m.group(2)); words = m.group(3); current = code; }
            else {
                Matcher numbered = NUMBERED.matcher(l);
                if (!numbered.matches()) continue;
                n = Integer.parseInt(numbered.group(1)); words = numbered.group(2); code = current;
                Matcher labelled = LABELLED.matcher(words);
                if (labelled.matches() && !languageNamed(labelled.group(1)).isEmpty()) { code = languageNamed(labelled.group(1)); words = labelled.group(2); }
            }
            words = words.strip().replaceAll("^[\"“”'`「]+|[\"“”'`.。」]+$", "").strip();
            if (code.isEmpty() || !languages.contains(code) || n < 1 || n > count || words.isEmpty() || words.length() > 160) continue;
            List<String> list = out.computeIfAbsent(code, k -> new ArrayList<>(Collections.nCopies(count, "")));
            if (list.get(n - 1).isEmpty()) list.set(n - 1, words);
        }
        return out;
    }

    // ---- the search's rows, and the videos a page links

    private static final Pattern ROW = Pattern.compile("^\\s*\\d+\\.\\s+(.*)$");

    /** The results in a web search's text, by the lines the search tools write: a numbered title, the address on the next line, the snippet after it. */
    public static List<Row> rows(String text) {
        List<Row> out = new ArrayList<>();
        if (text == null) return out;
        String[] lines = text.split("\\R");
        for (int i = 0; i + 1 < lines.length; i++) {
            Matcher m = ROW.matcher(lines[i]);
            if (!m.matches()) continue;
            String next = lines[i + 1].strip();
            if (!next.startsWith("http://") && !next.startsWith("https://")) continue;
            String snippet = "";
            if (i + 2 < lines.length && lines[i + 2].startsWith("   ") && !lines[i + 2].strip().startsWith("http") && !ROW.matcher(lines[i + 2]).matches()) snippet = lines[i + 2].strip();
            out.add(new Row(m.group(1).strip(), next.split("\\s+")[0], snippet));
        }
        return out;
    }

    /** Rows written as the search tools write them, for whoever stands between the search and this source. */
    public static String render(List<Row> rows) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (Row r : rows) {
            b.append(++i).append(". ").append(r.title().replace("\n", " ")).append('\n').append("   ").append(r.url()).append('\n');
            if (!r.snippet().isEmpty()) b.append("   ").append(r.snippet().replace("\n", " ")).append('\n');
        }
        return b.toString();
    }

    private static final String ID = "([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])";
    /** A video's address as a page writes it: a watch, embed, short or live address, the short host, or the address of its cover picture. */
    static final Pattern LINK = Pattern.compile("(?i)(?:youtube(?:-nocookie)?\\.com/(?:watch/?(?:\\?|%3F)(?:[^\"'\\s<>]{0,200}?(?:&amp;|&|%26))?v(?:=|%3D)|embed/|v/|shorts/|live/)|youtu\\.be/|ytimg\\.com/vi(?:_webp)?/)" + ID);
    /** A video's id as a player's own attribute carries it. */
    static final Pattern ATTRIBUTE = Pattern.compile("(?i)(?:data-(?:youtube|yt|video)-?id|videoid)\\s*=\\s*[\"']" + ID + "[\"']");
    private static final Pattern BLOCK = Pattern.compile("(?is)<(script|style|svg|template)\\b[^>]*>.*?</\\1\\s*>|<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("(?s)<[a-zA-Z/!?][^>]*>");
    private static final Pattern MARK = Pattern.compile("\u0001([A-Za-z0-9_-]{11})\u0002");

    /** The site of an address, without www; "" when it has none. */
    public static String site(String url) {
        try { String h = URI.create(url.strip()).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT).replaceFirst("^(www|m|mobile|amp)\\.", ""); }
        catch (Exception e) { return ""; }
    }

    static boolean youtube(String site) { return site.equals("youtube.com") || site.endsWith(".youtube.com") || site.equals("youtu.be") || site.equals("youtube-nocookie.com"); }

    /** The video an address names when it is one of YouTube's own; null otherwise. */
    public static String videoAt(String url) {
        if (url == null || !youtube(site(url))) return null;
        Matcher m = LINK.matcher(url);
        return m.find() && real(m.group(1)) ? m.group(1) : null;
    }

    /** An id that is a video's: "videoseries" is the embed of a playlist, eleven letters long. */
    private static boolean real(String id) { return !id.equalsIgnoreCase("videoseries") && id.chars().anyMatch(c -> !Character.isDigit(c)); }

    private static String marks(String html) {
        StringBuilder b = new StringBuilder();
        for (Pattern p : List.of(LINK, ATTRIBUTE)) { Matcher m = p.matcher(html); while (m.find()) if (real(m.group(1))) b.append(' ').append('\u0001').append(m.group(1)).append('\u0002'); }
        return b.toString();
    }

    /** One video a page links or embeds, with what the page says beside each place it stands. */
    public record Linked(String id, List<String> beside) { }

    /** A page read for its videos: its title, the videos in the order they stand, and its text. */
    public record Harvest(String title, List<Linked> videos, String text) { }

    /**
     * The videos a page links or embeds, each with the text beside it: a link, an embedded player, a player's attribute, a cover picture, an
     * address in a script or written out in the text. The text beside a link is {@value #WINDOW} characters either side of where it stands;
     * a page with one video only speaks of that video from its head.
     */
    public static Harvest harvest(String html) {
        String h = html == null ? "" : html.length() > BYTES_MOST ? html.substring(0, BYTES_MOST) : html;
        String title = WebFetchTool.pageTitle(h);
        // a script's own links stand where the script stood; the rest of it, the styles and the comments go
        StringBuilder noBlocks = new StringBuilder();
        Matcher blocks = BLOCK.matcher(h);
        while (blocks.find()) blocks.appendReplacement(noBlocks, Matcher.quoteReplacement(blocks.group(1) != null && blocks.group(1).equalsIgnoreCase("script") ? marks(blocks.group()) + " " : " "));
        blocks.appendTail(noBlocks);
        // every tag that carries a link leaves its mark where it stood, and the tags go
        StringBuilder noTags = new StringBuilder();
        Matcher tags = TAG.matcher(noBlocks);
        while (tags.find()) { String tag = tags.group(); tags.appendReplacement(noTags, Matcher.quoteReplacement((tag.matches("(?is)</?(p|div|br|li|tr|h[1-6]|td|th|section|article|blockquote)\\b.*") ? "\n" : " ") + marks(tag))); }
        tags.appendTail(noTags);
        String marked = Entities.decode(noTags.toString()).replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\s*\\n\\s*", "\n").strip();
        // the plain text, and where each mark stood in it; an address written out in the text is a link too
        StringBuilder plain = new StringBuilder();
        Map<String, List<Integer>> at = new LinkedHashMap<>();
        Matcher mark = MARK.matcher(marked);
        int from = 0;
        while (mark.find()) { plain.append(marked, from, mark.start()); from = mark.end(); at.computeIfAbsent(mark.group(1), k -> new ArrayList<>()).add(plain.length()); }
        plain.append(marked.substring(from));
        String text = plain.toString();
        Matcher written = LINK.matcher(text);
        while (written.find()) if (real(written.group(1))) at.computeIfAbsent(written.group(1), k -> new ArrayList<>()).add(written.start());
        List<Linked> videos = new ArrayList<>();
        for (var e : at.entrySet()) {
            List<String> beside = new ArrayList<>();
            if (at.size() == 1) beside.add(text.substring(0, Math.min(text.length(), WHOLE)));
            int last = -2 * WINDOW;
            for (int pos : e.getValue()) {
                if (pos - last < WINDOW || beside.size() >= 4) continue;
                beside.add(text.substring(Math.max(0, pos - WINDOW), Math.min(text.length(), pos + WINDOW)));
                last = pos;
            }
            videos.add(new Linked(e.getKey(), beside));
        }
        return new Harvest(title, videos, text);
    }

    /** A page as the library fetches any page, under its lists and its rules for addresses: the HTML, or null when it is no page of text. */
    static String readLive(String url) throws Exception {
        Fetch.Result r = Fetch.get(url, Duration.ofSeconds(12));
        if (r == null || r.status() != 200 || r.body() == null || r.body().length == 0) return null;
        String type = r.contentType() == null ? "" : r.contentType().toLowerCase(Locale.ROOT);
        if (!type.isEmpty() && !type.contains("html") && !type.contains("xml") && !type.contains("text/plain")) return null;
        byte[] b = r.body();
        return new String(b, 0, Math.min(b.length, BYTES_MOST), charset(type, b));
    }

    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]+)");

    /** The encoding a page says it is in, by its header or its own first lines; UTF-8 when it says none a machine knows. */
    static Charset charset(String contentType, byte[] body) {
        for (String where : new String[]{contentType, new String(body, 0, Math.min(body.length, 3_000), StandardCharsets.ISO_8859_1)}) {
            Matcher m = CHARSET.matcher(where == null ? "" : where);
            if (m.find()) { try { return Charset.forName(m.group(1)); } catch (Exception ignored) { } }
        }
        return StandardCharsets.UTF_8;
    }

    /** Sites that are another video's home or open only to someone signed in: a page there links no YouTube video a machine can read. */
    static final Set<String> ELSEWHERE = Set.of("tiktok.com", "vimeo.com", "dailymotion.com", "bilibili.com", "nicovideo.jp", "instagram.com", "facebook.com", "x.com", "twitter.com", "pinterest.com", "tenor.com", "giphy.com", "spotify.com", "linkedin.com");

    /** Whether an address is a page worth reading for its links: a web page, not YouTube's own, not another video site, not a site's front page, not a file by its ending. */
    static boolean readable(String url) {
        String s = site(url);
        if (s.isEmpty() || youtube(s) || !url.matches("(?i)https?://.*")) return false;
        for (String e : ELSEWHERE) if (s.equals(e) || s.endsWith("." + e)) return false;
        if (url.matches("(?i)https?://[^/]+/?([?#].*)?")) return false;   // a front page is about no one video
        if (url.matches("(?i).*[./](pdf|jpe?g|png|gif|webp|mp4|mp3|zip|docx?|xlsx?|pptx?)([?#].*)?$")) return false;
        return !url.matches("(?i)https?://(www\\.)?google\\.[a-z.]+/(goto|url)\\?.*");
    }

    // ---- the run

    private static final class Seen {
        final String url, site, query; Fit fit; final int order;
        Seen(String url, String site, Fit fit, int order, String query) { this.url = url; this.site = site; this.fit = fit; this.order = order; this.query = query; }
    }

    private static void pause(long ms) { if (ms > 0) try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    /**
     * The videos other people's words lead to, for the names the thing goes under and what the person remembers: the phrases said in the
     * other languages, the queries asked one after another through {@link Candidates#WEB}, the videos among the results taken with what
     * their own title and snippet hold, a video whose address a result's own words carry among them, and the pages among them read, the best fit by their snippets first, for the videos they link.
     * It stops at {@code untilMs} with what it has. When no web search index is set the first question comes back unanswered, and the
     * result says so in one sentence and holds nothing.
     */
    public static Result run(List<Candidates.Named> names, List<String> remembered, BooleanSupplier stop, long untilMs) {
        ObjectNode record = M.createObjectNode();
        List<String> notes = new ArrayList<>(), progress = new ArrayList<>();
        ArrayNode rem = record.putArray("remembered"); for (String r : remembered) rem.add(r);
        BooleanSupplier over = () -> stop.getAsBoolean() || (untilMs > 0 && System.currentTimeMillis() > untilMs);
        String own = Candidates.languageOf(String.join(" ", remembered));
        List<String> others = otherLanguages(names, own);
        Map<String, List<String>> inOther = new LinkedHashMap<>();
        if (!others.isEmpty()) {
            Map<String, List<String>> said;
            try { said = PHRASER == null ? null : PHRASER.say(remembered.subList(0, Math.min(OTHER_PHRASES, remembered.size())), others); } catch (Exception e) { said = null; }
            if (said == null) notes.add("No model answered to say what is remembered in the other languages, so pages were looked for in the person's own words alone.");
            else for (String code : others) if (said.containsKey(code)) inOther.put(code, said.get(code));
            ObjectNode sn = record.putObject("said"); inOther.forEach((code, list) -> { ArrayNode a = sn.putArray(code); for (String s : list) a.add(s); });
            if (said != null && inOther.size() < others.size()) {
                // a language asked for and not read: the model's own lines are kept, so a reader tells a model that said nothing from lines the program could not read
                record.put("said_as_written", lastSaid.length() > 1500 ? lastSaid.substring(0, 1500) : lastSaid);
                notes.add("What is remembered was asked for in " + others.size() + " other languages and read in " + inOther.size() + " of them.");
            }
        }
        List<Phrase> phrases = phrases(remembered, inOther);
        List<Query> queries = queries(names, own, remembered, inOther);
        Map<String, Found> found = new LinkedHashMap<>();   // by video and page
        Map<String, Seen> pages = new LinkedHashMap<>();
        ArrayNode qs = record.putArray("queries");
        int asked = 0, answered = 0, failed = 0;
        boolean unset = false;
        for (Query q : queries) {
            if (over.getAsBoolean() || (untilMs > 0 && System.currentTimeMillis() > untilMs - KEEP_FOR_PAGES_MS)) break;
            if (asked > 0) pause(paceMs);
            String text;
            try { text = Candidates.WEB.search(q.q()); } catch (Stopping.Requested s) { throw s; } catch (Exception e) { text = ""; }
            if (text == null && asked == 0) { unset = true; break; }   // no web search index is set: nothing is asked
            if (text == null) text = "";
            asked++;
            List<Row> rows = rows(text);
            ObjectNode qn = qs.addObject().put("q", q.q()).put("language", q.language()).put("rows", rows.size());
            if (rows.isEmpty()) {
                // a search that fails is not a search that found nothing: three failures with nothing answered yet, and the search is not asked thirty times more
                boolean failure = text.isBlank() || text.startsWith("ERROR") || text.startsWith(SearchLog.DEGRADED);
                if (failure && ++failed >= 3 && answered == 0) break;
                continue;
            }
            answered++;
            int videos = 0, toRead = 0;
            for (Row r : rows) {
                String site = site(r.url()), id = videoAt(r.url());
                Fit f = fit(r.title() + " " + r.snippet(), phrases);
                if (id != null) { videos++; keep(found, new Found(id, "youtube.com", r.url(), r.title().replaceFirst("\\s*[-–|]\\s*YouTube\\s*$", "").strip(), f, r.title() + " — " + r.snippet(), q.q())); continue; }
                // a result that is no video may write one's address in its own words, an answer on a question site, a line of a list: that video is the result's
                Matcher written = LINK.matcher(r.title() + " " + r.snippet());
                while (written.find()) if (real(written.group(1))) { videos++; keep(found, new Found(written.group(1), site, r.url(), "", f, r.title() + " — " + r.snippet(), q.q())); }
                if (!readable(r.url())) continue;
                String address = r.url().replaceFirst("#.*$", "");
                Seen seen = pages.get(address);
                if (seen == null) { pages.put(address, new Seen(address, site, f, pages.size(), q.q())); toRead++; }
                else if (f.better(seen.fit)) seen.fit = f;
            }
            qn.put("videos", videos).put("pages", toRead);
            progress.add("web \"" + q.q() + "\": " + rows.size() + " results, " + videos + " of them videos");
        }
        record.put("asked", asked).put("answered", answered);
        if (unset) {
            notes.add("No web search index is set up (a Serper, SerpApi or Brave key, or a SearXNG of your own), so no page that describes the video was looked for.");
            return new Result(List.of(), record, notes, progress, phrases, 0, 0);
        }
        if (asked == 0) return new Result(List.of(), record, notes, progress, phrases, 0, 0);   // the run was over before the first question
        if (answered == 0) {
            notes.add("The web search gave no result for any of the " + asked + " questions about what is remembered, so no page that describes the video was read.");
            return new Result(List.of(), record, notes, progress, phrases, asked, 0);
        }
        // the pages: the ones whose own title and snippet hold the most of what is remembered first, a few of each site, read several at once, one site one page at a time
        List<Seen> order = new ArrayList<>(pages.values());
        order.sort((a, b) -> a.fit.better(b.fit) ? -1 : b.fit.better(a.fit) ? 1 : Integer.compare(a.order, b.order));
        Map<String, List<Seen>> bySite = new LinkedHashMap<>();
        int chosen = 0;
        for (Seen s : order) {
            if (chosen >= PAGES_MOST) break;
            List<Seen> of = bySite.computeIfAbsent(s.site, k -> new ArrayList<>());
            if (of.size() >= PER_SITE) continue;
            of.add(s); chosen++;
        }
        ArrayNode pn = record.putArray("pages");
        int read = 0, linking = 0;
        if (chosen > 0 && !over.getAsBoolean()) {
            Map<Seen, Harvest> done = new ConcurrentHashMap<>();
            ExecutorService readers = Executors.newFixedThreadPool(Math.min(READERS, bySite.size()), r -> { Thread t = new Thread(r, "described-pages"); t.setDaemon(true); return t; });
            try {
                List<Future<Void>> reading = new ArrayList<>();
                for (List<Seen> of : bySite.values()) {
                    Callable<Void> work = () -> {
                        for (int i = 0; i < of.size() && !over.getAsBoolean(); i++) {
                            if (i > 0) pause(sitePaceMs);
                            String html;
                            try { html = PAGES.read(of.get(i).url); } catch (Stopping.Requested s) { throw s; } catch (Exception e) { html = null; }
                            if (html != null) done.put(of.get(i), harvest(html));
                        }
                        return null;
                    };
                    reading.add(readers.submit(Stopping.carried(work)));
                }
                for (Future<Void> f : reading) {
                    try { f.get(untilMs > 0 ? Math.max(1_000, untilMs - System.currentTimeMillis()) : 180_000, TimeUnit.MILLISECONDS); }
                    catch (Exception e) { f.cancel(true); }
                }
            } finally { readers.shutdownNow(); }
            for (List<Seen> of : bySite.values()) for (Seen s : of) {
                Harvest hv = done.get(s);
                ObjectNode p = pn.addObject().put("url", s.url).put("site", s.site).put("read", hv != null).put("snippet_fit", s.fit.phrases());
                if (hv == null) continue;
                read++;
                List<Found> here = new ArrayList<>();
                for (Linked v : hv.videos()) {
                    Fit best = Fit.NONE; String text = v.beside().isEmpty() ? "" : v.beside().get(0);
                    for (String beside : v.beside()) { Fit f = fit(hv.title() + " " + beside, phrases); if (f.better(best)) { best = f; text = beside; } }
                    here.add(new Found(v.id(), s.site, s.url, "", best, hv.title() + " — " + text, s.query));
                }
                here.sort((a, b) -> a.fit().better(b.fit()) ? -1 : b.fit().better(a.fit()) ? 1 : 0);
                if (here.size() > PER_PAGE) here = here.subList(0, PER_PAGE);
                if (!here.isEmpty()) linking++;
                for (Found f : here) keep(found, f);
                p.put("videos", here.size()).put("fit", here.isEmpty() ? 0 : here.get(0).fit().phrases());
            }
        }
        record.put("pages_named", pages.size()).put("pages_read", read).put("pages_linking", linking).put("found", (int) found.values().stream().map(Found::id).distinct().count());
        // every video with the page that led to it, the query that brought the page and the text beside the video there, so that a reader of the run judges each find
        ArrayNode fn = record.putArray("videos");
        for (Found f : found.values()) {
            ObjectNode o = fn.addObject().put("id", f.id()).put("site", f.site()).put("page", f.page()).put("query", f.query()).put("fit", f.fit().phrases()).put("words", f.fit().words());
            ArrayNode held = o.putArray("held"); for (String h : f.fit().held()) held.add(h);
            o.put("beside", f.beside().length() > 2 * WINDOW + 200 ? f.beside().substring(0, 2 * WINDOW + 200) : f.beside());
        }
        progress.add("web pages: " + read + " read of " + pages.size() + " named, " + linking + " link a video");
        int videos = (int) found.values().stream().map(Found::id).distinct().count();
        notes.add("The web was asked " + asked + (asked == 1 ? " question" : " questions") + " made from the thing's name and what is remembered" + (inOther.isEmpty() ? "" : ", in " + (1 + inOther.size()) + " languages") + ": " + videos
                + (videos == 1 ? " video was" : " videos were") + " found, among the results themselves and on " + linking + " of the " + read + " pages read for the videos they link (" + pages.size() + " pages were named)"
                + (answered < asked ? "; " + (asked - answered) + " of the questions came back empty." : "."));
        return new Result(new ArrayList<>(found.values()), record, notes, progress, phrases, asked, answered);
    }

    /** A video as a page gave it, once per video and page: the better fit of two readings, and the title when one of them had it. */
    private static void keep(Map<String, Found> found, Found f) {
        String key = f.id() + " " + f.page();
        Found had = found.get(key);
        if (had == null) { found.put(key, f); return; }
        boolean better = f.fit().better(had.fit());
        found.put(key, new Found(f.id(), had.site(), had.page(), had.title().isEmpty() ? f.title() : had.title(), better ? f.fit() : had.fit(), better ? f.beside() : had.beside(), had.query()));
    }
}
