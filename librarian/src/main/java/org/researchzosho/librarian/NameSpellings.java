package org.researchzosho.librarian;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.researchzosho.Config;

/**
 * How the names of a language are written in Latin letters, from rule files: one per language, shipped with the program
 * ({@code spellings/<code>.txt} beside this class) and added to by the person's own ({@code ~/.researchzosho/spellings/<code>.txt}). A rule
 * file says how the sounds of the language are written, never which name is which: Endō, Endoh, Endou and Endo are one Japanese name because
 * Japanese writes a long o all four ways, not because a list pairs them.
 *
 * <p>A file holds, one per line ({@code #} starts a comment):
 * <ul>
 *   <li>{@code language: ja}, {@code name: Japanese}, and {@code scripts: Hiragana, Katakana}: a name written in one of these scripts is of
 *       this language, and so are the Latin forms of a person who has such a name. {@code marks: ü ö ä} says the same of Latin letters only
 *       this language writes.</li>
 *   <li>{@code joins: ' -}: the characters left out between two letters when names are compared (Ken'ichi, Ken-ichi and Kenichi).</li>
 *   <li>{@code drop: '}: characters records write and leave out alike; each search spelling is tried again without them.</li>
 *   <li>{@code vowels: a e i o u y}: the letters a condition calls vowels, in Latin letters and in the language's own script.</li>
 *   <li>{@code fold <key>: <spelling>, <spelling> (<condition>)}: spellings compared as one; each is written as the key ({@code _} for
 *       nothing). The folds apply to each word in the file's order; a fold of a letter with an accent that no decomposition takes apart (ø, æ,
 *       ß, ŏ) applies before the accents are left out.</li>
 *   <li>{@code same <word>: <word>, <word>}: whole words compared as one: family names that records write in ways no sound rule reaches
 *       (Cantonese Cheung for Mandarin Zhang). The words are compared as the first one is, folds and syllables included.</li>
 *   <li>{@code style <name>: <sound>=<spelling> <sound>=<spelling>}: one consistent way of writing the language, as a record writes a whole
 *       name one way. The spellings of a name for a search are the name in each style.</li>
 *   <li>{@code letter <character(s)>: <latin> (<condition>)}: how the language's own script is written in Latin letters, the first way; the
 *       longest characters that match are written first, and of one character's rules the first whose condition holds. A form in the
 *       script is written in Latin letters before it is compared. {@code letter <style> <character(s)>: <latin>} writes the characters
 *       another way in that style; the characters the style does not name are written the first way.</li>
 *   <li>{@code decompose: yes}: the script is taken apart (Unicode NFD) before its letters are read, and accents the letters do not name
 *       are left out. A Hangul syllable becomes its jamo, which have one code point for a consonant that begins a syllable and another for
 *       one that ends it, so a flat letter table writes both; a Greek vowel loses its stress mark.</li>
 *   <li>{@code syllable <first way>: <spelling>, <spelling>}: for a language written syllable by syllable (Chinese: pinyin, Wade-Giles), one
 *       syllable and its other spellings. A word is read as syllables, the longest spelling first, going back when the rest of the word
 *       cannot be read; a join inside the word (Xi'an, Hsiao-p'ing) ends a syllable, unless a spelling has one there (the apostrophe of
 *       ch'ang). Each syllable is compared by what it can be read as: a spelling that reads as two syllables (Wade-Giles chang for pinyin
 *       zhang, and pinyin chang) makes them one, and so on through every spelling they share. A word that cannot be read as syllables is
 *       compared as a word. For a search, a name is also written the first way and the second (a word's syllables joined by hyphens, as
 *       Wade-Giles writes a given name).</li>
 * </ul>
 * A condition is one of {@code (not before a vowel)}, {@code (before a vowel)}, {@code (before b m p)}, {@code (not before b m p)},
 * {@code (at the end)}, {@code (at the start)}, {@code (after a vowel)}, {@code (not after a vowel)}, {@code (after s c)},
 * {@code (not after s c)}, {@code (between vowels)}. Start and end are of a word.
 */
public final class NameSpellings {

    private NameSpellings() { }

    /** Accents and other combining marks, left out when names are compared. */
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    /** What parts two words: anything that is not a letter. */
    private static final Pattern NOT_LETTERS = Pattern.compile("[^\\p{L}]+");

    /** One way of writing a sound, where the condition allows it. */
    record Spelling(String text, String condition) { }

    /** A fold rule: each spelling written as the key. {@code early}: a letter with an accent, folded before the accents go. */
    record Fold(String key, Spelling spelling, Pattern pattern, boolean early) {
        Fold(String key, Spelling spelling, String vowels) {
            this(key, spelling, compile(spelling, vowels), spelling.condition().isEmpty() && spelling.text().codePoints().anyMatch(c -> c > 0x7f));
        }

        /** The spelling where its condition holds, as a pattern. */
        static Pattern compile(Spelling spelling, String vowels) {
            String v = "[" + chars(vowels) + "]", q = Pattern.quote(spelling.text());
            String c = spelling.condition();
            String re = switch (c) {
                case "" -> q;
                case "not before a vowel" -> q + "(?!" + v + ")";
                case "before a vowel" -> q + "(?=" + v + ")";
                case "at the end" -> q + "$";
                case "at the start" -> "^" + q;
                case "after a vowel" -> "(?<=" + v + ")" + q;
                case "not after a vowel" -> "(?<!" + v + ")" + q;
                case "between vowels" -> "(?<=" + v + ")" + q + "(?=" + v + ")";
                default -> {
                    if (c.startsWith("not before ")) yield q + "(?![" + chars(listed(c, "not before ")) + "])";
                    if (c.startsWith("before ")) yield q + "(?=[" + chars(listed(c, "before ")) + "])";
                    if (c.startsWith("not after ")) yield "(?<![" + chars(listed(c, "not after ")) + "])" + q;
                    if (c.startsWith("after ")) yield "(?<=[" + chars(listed(c, "after ")) + "])" + q;
                    yield q;
                }
            };
            return Pattern.compile(re);
        }
    }

    /** The letters a condition lists after its words ("before b m p"): b, m and p. */
    static String listed(String condition, String words) { return condition.substring(words.length()).replaceAll("\\s+", ""); }

    /** The characters of a character class, each escaped. */
    static String chars(String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(c -> { if (!Character.isLetterOrDigit(c)) b.append('\\'); b.appendCodePoint(c); });
        return b.toString();
    }

    /** Where a letter rule of the language's own script holds: its condition, read once. */
    record Where(Kind kind, String set) {
        enum Kind { ANYWHERE, START, END, BEFORE, NOT_BEFORE, AFTER, NOT_AFTER, BETWEEN }

        static final Where ANYWHERE = new Where(Kind.ANYWHERE, "");

        static Where of(String c, String vowels) {
            return switch (c) {
                case "" -> ANYWHERE;
                case "at the start" -> new Where(Kind.START, "");
                case "at the end" -> new Where(Kind.END, "");
                case "before a vowel" -> new Where(Kind.BEFORE, vowels);
                case "not before a vowel" -> new Where(Kind.NOT_BEFORE, vowels);
                case "after a vowel" -> new Where(Kind.AFTER, vowels);
                case "not after a vowel" -> new Where(Kind.NOT_AFTER, vowels);
                case "between vowels" -> new Where(Kind.BETWEEN, vowels);
                default -> {
                    if (c.startsWith("not before ")) yield new Where(Kind.NOT_BEFORE, listed(c, "not before "));
                    if (c.startsWith("before ")) yield new Where(Kind.BEFORE, listed(c, "before "));
                    if (c.startsWith("not after ")) yield new Where(Kind.NOT_AFTER, listed(c, "not after "));
                    if (c.startsWith("after ")) yield new Where(Kind.AFTER, listed(c, "after "));
                    yield ANYWHERE;
                }
            };
        }

        /** Whether the condition holds for the characters of {@code s} from {@code from} to {@code to}; accents beside them are passed over. */
        boolean holds(String s, int from, int to) {
            if (kind == Kind.ANYWHERE) return true;
            int before = -1, after = -1;
            for (int i = from; i > 0; ) { int c = s.codePointBefore(i); i -= Character.charCount(c); if (!mark(c)) { before = c; break; } }
            for (int i = to; i < s.length(); ) { int c = s.codePointAt(i); i += Character.charCount(c); if (!mark(c)) { after = c; break; } }
            return switch (kind) {
                case START -> before < 0 || !Character.isLetter(before);
                case END -> after < 0 || !Character.isLetter(after);
                case BEFORE -> after >= 0 && set.indexOf(after) >= 0;
                case NOT_BEFORE -> after < 0 || set.indexOf(after) < 0;
                case AFTER -> before >= 0 && set.indexOf(before) >= 0;
                case NOT_AFTER -> before < 0 || set.indexOf(before) < 0;
                case BETWEEN -> before >= 0 && after >= 0 && set.indexOf(before) >= 0 && set.indexOf(after) >= 0;
                default -> true;
            };
        }

        private static boolean mark(int c) { int t = Character.getType(c); return t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK || t == Character.COMBINING_SPACING_MARK; }
    }

    /** A letter rule: characters of the language's own script, written in Latin letters where the condition holds. */
    record Letter(String text, String latin, Where where) { }

    /** The letters of a language's own script, found by the character they begin with: the longest first, a condition before none. */
    static final class Letters {
        static final Letters NONE = new Letters(List.of(), false);

        final boolean decompose;
        private final List<Letter> all;
        private final Map<Integer, List<Letter>> byFirst = new HashMap<>();

        Letters(List<Letter> letters, boolean decompose) { this(letters, decompose, ordered(letters)); }

        private Letters(List<Letter> letters, boolean decompose, List<Letter> order) {
            this.decompose = decompose;
            this.all = List.copyOf(letters);
            for (Letter l : order) byFirst.computeIfAbsent(l.text().codePointAt(0), k -> new ArrayList<>()).add(l);
        }

        /** Longest first; of one length, a rule with a condition before one without, else in the order given. */
        private static List<Letter> ordered(List<Letter> letters) {
            List<Letter> out = new ArrayList<>(letters);
            out.sort((a, b) -> a.text().length() != b.text().length() ? b.text().length() - a.text().length()
                    : Boolean.compare(a.where() == Where.ANYWHERE, b.where() == Where.ANYWHERE));
            return out;
        }

        boolean isEmpty() { return all.isEmpty(); }

        /** A style's letters over these: the style's rules for the characters it names come before the first way's. */
        Letters under(List<Letter> style) {
            List<Letter> order = new ArrayList<>(ordered(style));
            order.addAll(ordered(all));
            // stable: at one length the style's rules stay ahead of the first way's
            order.sort((a, b) -> b.text().length() - a.text().length());
            List<Letter> both = new ArrayList<>(style);
            both.addAll(all);
            return new Letters(both, decompose, order);
        }

        /** A form in the script in Latin letters, each word capitalised. */
        String write(String s) {
            String t = s.toLowerCase(Locale.ROOT);
            if (decompose) t = Normalizer.normalize(t, Normalizer.Form.NFD);
            StringBuilder out = new StringBuilder();
            int i = 0;
            outer:
            while (i < t.length()) {
                int c = t.codePointAt(i);
                List<Letter> candidates = byFirst.get(c);
                if (candidates != null) for (Letter l : candidates) {
                    if (t.startsWith(l.text(), i) && l.where().holds(t, i, i + l.text().length())) { out.append(l.latin()); i += l.text().length(); continue outer; }
                }
                if (!(decompose && Where.mark(c))) out.appendCodePoint(c);
                i += Character.charCount(c);
            }
            String w = decompose ? Normalizer.normalize(out, Normalizer.Form.NFC) : out.toString();
            StringBuilder b = new StringBuilder(w.length());
            boolean start = true;
            for (int k = 0; k < w.length(); ) {
                int c = w.codePointAt(k);
                k += Character.charCount(c);
                if (start && Character.isLetter(c)) { b.append(new String(Character.toChars(c)).toUpperCase(Locale.ROOT)); start = false; }
                else { b.appendCodePoint(c); if (c == ' ' || c == '-') start = true; }
            }
            return b.toString();
        }
    }

    /**
     * The syllables of a language written syllable by syllable, each with its spellings in the other ways. A spelling is held as compared:
     * lower case, no accents, each join character read as an apostrophe.
     */
    static final class Syllables {
        private final String joins;
        /** A word as a form writes it, joins and accents included; and what parts two such words. */
        private final Pattern word, notWord;
        /** Every spelling, and each without its apostrophes → the key of all the syllables it can be read as. */
        private final Map<String, String> keys = new HashMap<>();
        /** A spelling of the first way → the first way as the file writes it. */
        private final Map<String, String> first = new HashMap<>();
        /** A spelling of another way (with and without its apostrophes) → the first way it is read as. */
        private final Map<String, String> other = new HashMap<>();
        /** The first way → the second, as the file writes it. */
        private final Map<String, String> second = new HashMap<>();
        private final int longest;

        Syllables(List<String[]> rules, String joins) {
            this.joins = joins;
            this.word = Pattern.compile("[\\p{L}\\p{M}" + chars(joins) + "]+");
            this.notWord = Pattern.compile("[^\\p{L}" + chars(joins) + "]+");
            Map<String, String> parent = new HashMap<>();
            for (String[] r : rules) {
                String p = compared(r[0]);
                first.putIfAbsent(p, r[0]);
                if (r.length > 1) second.putIfAbsent(p, r[1]);
                for (int i = 1; i < r.length; i++) {
                    String w = compared(r[i]);
                    other.putIfAbsent(w, r[0]);
                    union(parent, p, w);
                    union(parent, p, w.replace("'", ""));
                }
                union(parent, p, p);
            }
            // a spelling's apostrophe is left out as often as it is written: without it, it is read as the syllable that has none first
            for (String[] r : rules) for (int i = 1; i < r.length; i++) other.putIfAbsent(compared(r[i]).replace("'", ""), r[0]);
            Map<String, String> named = new HashMap<>();
            for (String[] r : rules) named.putIfAbsent(root(parent, compared(r[0])), compared(r[0]));
            int max = 0;
            for (String s : parent.keySet()) { keys.put(s, named.get(root(parent, s))); max = Math.max(max, s.length()); }
            longest = max;
        }

        private static String root(Map<String, String> parent, String s) {
            String r = s;
            while (!parent.get(r).equals(r)) r = parent.get(r);
            return r;
        }

        private static void union(Map<String, String> parent, String a, String b) {
            parent.putIfAbsent(a, a);
            parent.putIfAbsent(b, b);
            String ra = root(parent, a), rb = root(parent, b);
            if (!ra.equals(rb)) parent.put(rb, ra);
        }

        /** A spelling as compared: lower case, no accents, a join read as an apostrophe. */
        String compared(String s) {
            String n = MARKS.matcher(Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)).replaceAll("");
            StringBuilder b = new StringBuilder(n.length());
            n.codePoints().forEach(c -> b.appendCodePoint(joins.indexOf(c) >= 0 ? '\'' : c));
            return b.toString();
        }

        /** A word as compared read as syllables of {@code spellings}, the longest first; null when it cannot be. */
        List<String> read(String word, Map<String, String> spellings) {
            int n = word.length();
            boolean[] ok = new boolean[n + 1];
            int[] next = new int[n + 1];
            ok[n] = true;
            for (int i = n - 1; i >= 0; i--) {
                if (word.charAt(i) == '\'') { ok[i] = i > 0 && i + 1 < n && ok[i + 1] && word.charAt(i + 1) != '\''; next[i] = i + 1; continue; }
                for (int len = Math.min(longest, n - i); len >= 1; len--) {
                    if (ok[i + len] && spellings.containsKey(word.substring(i, i + len))) { ok[i] = true; next[i] = i + len; break; }
                }
            }
            if (!ok[0]) return null;
            List<String> out = new ArrayList<>();
            for (int i = 0; i < n; i = next[i]) if (word.charAt(i) != '\'') out.add(word.substring(i, next[i]));
            return out;
        }

        /** The keys of the syllables a word as compared is read as; null when it cannot be read as syllables. */
        List<String> keys(String word) {
            List<String> read = read(compared(word), keys);
            if (read == null) return null;
            List<String> out = new ArrayList<>(read.size());
            for (String s : read) out.add(keys.get(s));
            return out;
        }

        /** A form written the other way: the first way's words in the second (a word's syllables hyphenated), and the other ways' in the first. */
        List<String> ways(String form) {
            List<String> out = new ArrayList<>();
            for (int way = 0; way < 2; way++) {
                Map<String, String> from = way == 0 ? first : other;
                StringBuilder b = new StringBuilder();
                Matcher m = word.matcher(form);
                int at = 0;
                boolean changed = false, all = true;
                while (m.find()) {
                    b.append(form, at, m.start());
                    at = m.end();
                    String w = m.group();
                    List<String> read = read(compared(w), from);
                    if (read == null) { b.append(w); if (keys(w) != null) all = false; continue; }
                    List<String> written = new ArrayList<>();
                    for (String s : read) written.add(way == 0 ? second.getOrDefault(s, first.get(s)) : other.get(s));
                    String t = String.join(way == 0 ? "-" : "", written);
                    b.append(Character.isUpperCase(w.codePointAt(0)) ? t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1) : t);
                    changed = true;
                }
                b.append(form.substring(at));
                if (changed && all) out.add(b.toString());
            }
            return out;
        }
    }

    /** A style: consistent spellings of sounds, applied to the whole name, and the letters it writes its own way (null when none). */
    record Style(String name, List<String[]> changes, Letters letters) { }

    public record Language(String code, String name, Set<Character.UnicodeScript> scripts, String marks, String joins, String vowels, String drop,
                           List<Fold> folds, Map<String, String> sameWords, List<Style> styles, Letters letters, Syllables syllables, Pattern joining) {

        /**
         * A form of a name as it is compared in this language: lower case, no accents, the joins left out, each word folded, the words sorted.
         * A form in the language's own script is first written in Latin letters; a language of syllables compares each syllable as a word.
         */
        public String key(String latin) {
            String s = latin == null ? "" : latin;
            if (!letters.isEmpty() && inScript(s)) s = letters.write(s);
            String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            n = foldLetters(n);
            n = MARKS.matcher(Normalizer.normalize(n, Normalizer.Form.NFKD)).replaceAll("");
            List<String> words = new ArrayList<>();
            if (syllables != null) syllableWords(n, words);
            else {
                if (joining != null) n = joining.matcher(n).replaceAll("");
                for (String w : NOT_LETTERS.split(n)) if (!w.isEmpty()) words.add(word(w));
            }
            words.sort(null);
            return String.join(" ", words);
        }

        /** A word as compared: the first word of its {@code same} line, or itself, folded. */
        private String word(String w) {
            String same = sameWords.get(w);
            return fold(same != null ? same : w);
        }

        /** The words of a language of syllables: each syllable of a word that reads as syllables, and any other word as a word. */
        private void syllableWords(String n, List<String> words) {
            for (String run : syllables.notWord.split(n)) {
                if (run.isEmpty()) continue;
                String joined = joining != null ? joining.matcher(run).replaceAll("") : run;
                String same = sameWords.get(joined);
                List<String> read = syllables.keys(same != null ? same : run);
                if (read != null) { words.addAll(read); continue; }
                if (same != null) { words.add(fold(same)); continue; }
                for (String w : NOT_LETTERS.split(joined)) if (!w.isEmpty()) words.add(word(w));
            }
        }

        /** The letters a fold names with its accent (ø, æ, ß, which no decomposition takes apart): folded before the accents go. */
        private String foldLetters(String n) {
            for (Fold f : folds) if (f.early()) n = n.replace(f.spelling().text(), f.key());
            return n;
        }

        /** One word with each fold rule applied in the file's order, each over the whole word, as a person reads the rules. */
        String fold(String word) {
            String out = word;
            for (Fold f : folds) out = f.pattern().matcher(out).replaceAll(Matcher.quoteReplacement(f.key()));
            return out;
        }

        /**
         * The ways records write a form, for a search: the form as written, then the form in each style of the language, each once. A form in
         * the language's own script is first written in Latin letters by its letters, and each style that has letters of its own writes it
         * again; a form in a language of syllables is also written in its other way.
         */
        public List<String> spellings(String form) {
            String s = form == null ? "" : form.strip();
            if (s.isEmpty()) return List.of();
            Set<String> out = new LinkedHashSet<>();
            if (!letters.isEmpty() && inScript(s)) {
                String firstWay = letters.write(s);
                out.add(firstWay);
                for (Style st : styles) out.add(st.letters() != null ? styled(st.letters().write(s), st) : styled(firstWay, st));
            } else {
                List<String> ways = new ArrayList<>();
                ways.add(s);
                if (syllables != null) ways.addAll(syllables.ways(s));
                out.addAll(ways);
                for (Style st : styles) for (String w : ways) out.add(styled(w, st));
            }
            // the characters records leave out as often as they write them (Ken'ichi and Kenichi): each spelling again without them
            if (!drop.isEmpty()) {
                Pattern dropped = Pattern.compile("[" + chars(drop) + "]");
                for (String x : new ArrayList<>(out)) if (x.codePoints().anyMatch(c -> drop.indexOf(c) >= 0)) out.add(dropped.matcher(x).replaceAll(""));
            }
            return new ArrayList<>(out);
        }

        /** A form written in one style: each sound the style names written its way, capitals kept at the start of a word. */
        static String styled(String s, Style st) {
            String out = s;
            for (String[] c : st.changes()) {
                out = out.replace(c[0], c[1]);
                String cap = capital(c[0]);
                // ß has no capital of its own length (SS), and a capital that is two letters is no start of a word
                if (!cap.equals(c[0]) && cap.length() == c[0].length()) out = out.replace(cap, capital(c[1]));
            }
            return out;
        }

        private static String capital(String s) { return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }

        /** A form in the language's own script in Latin letters, the first way, each word capitalised. */
        public String transliterate(String s) { return letters.write(s == null ? "" : s); }

        /** Whether a form has a character of one of the language's scripts. */
        private boolean inScript(String s) { return s.codePoints().anyMatch(c -> scripts.contains(Character.UnicodeScript.of(c))); }

        /** Whether a form is written in a script of this language, or with a letter only it writes. */
        public boolean writes(String form) {
            if (form == null) return false;
            if (inScript(form)) return true;
            String low = form.toLowerCase(Locale.ROOT);
            for (int i = 0; i < marks.length(); ) {
                int c = marks.codePointAt(i);
                if (c != ' ' && low.indexOf(c) >= 0) return true;
                i += Character.charCount(c);
            }
            return false;
        }
    }

    private static final Map<String, Language> CACHE = new ConcurrentHashMap<>();
    /** A language with no rules anywhere, kept so it is not looked for again. */
    private static final Language NONE = new Language("", "", Set.of(), "", "", "", "", List.of(), Map.of(), List.of(), Letters.NONE, null, null);

    /** The codes of the languages the program ships rules for. */
    public static final List<String> SHIPPED = List.of("ja", "zh", "ko", "ru", "el", "de", "nordic");

    /** A language's rules, the program's and the person's own together; null when neither has a file for it. */
    public static Language of(String code) {
        if (code == null || code.isBlank()) return null;
        // asked for on every comparison of two names: kept by the user's home as it is now, and the files read once
        String key = code + "\u0000" + System.getProperty("user.home", "");
        Language cached = CACHE.get(key);
        if (cached != null) return cached == NONE ? null : cached;
        Path own = Config.home().resolve("spellings").resolve(code + ".txt");
        List<String> lines = new ArrayList<>();
        try (InputStream in = NameSpellings.class.getResourceAsStream("spellings/" + code + ".txt")) {
            if (in != null) lines.addAll(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")));
        } catch (IOException unreadable) { }
        // the person's own rules, after the program's: a rule of theirs adds to the program's, and a style or a line of the same name replaces it
        try { if (Files.isRegularFile(own)) lines.addAll(Files.readAllLines(own, StandardCharsets.UTF_8)); } catch (IOException unreadable) { }
        if (lines.isEmpty()) { CACHE.put(key, NONE); return null; }
        Language l = parse(code, lines);
        CACHE.put(key, l);
        return l;
    }

    /** Every language there are rules for: the program's, then any the person added. */
    public static List<Language> all() {
        Set<String> codes = new LinkedHashSet<>(SHIPPED);
        Path dir = Config.home().resolve("spellings");
        if (Files.isDirectory(dir)) try (var s = Files.list(dir)) { s.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".txt")).sorted().forEach(f -> codes.add(f.substring(0, f.length() - 4))); }
        catch (IOException unreadable) { }
        List<Language> out = new ArrayList<>();
        for (String c : codes) { Language l = of(c); if (l != null) out.add(l); }
        return out;
    }

    /** Forget the rules read, so a changed file of the person's is read again (tests, and a long-running service). */
    public static void forget() { CACHE.clear(); }

    private static final Pattern RULE = Pattern.compile("^(fold|same|style|letter|syllable)\\s+(.+?)\\s*:\\s*(.*)$");
    private static final Pattern CONDITIONED = Pattern.compile("^(.*?)\\s*\\(([^)]*)\\)\\s*$");

    static Language parse(String code, List<String> lines) {
        String name = code, marks = "", joins = "'", vowels = "aeiouy", drop = "";
        boolean decompose = false;
        Set<Character.UnicodeScript> scripts = new LinkedHashSet<>();
        List<String[]> folded = new ArrayList<>();   // {key, spelling, condition}, made into rules once the vowels are known
        Map<String, String> same = new HashMap<>();
        Map<String, List<String[]>> changes = new LinkedHashMap<>();
        // {characters, latin, condition} by style ("" for the first way), each by its characters and condition, so a later line replaces it
        Map<String, Map<String, String[]>> letterLines = new LinkedHashMap<>();
        Map<String, String[]> syllableLines = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.replaceFirst("\\s+#.*$", "").strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = RULE.matcher(line);
            if (m.matches()) {
                String what = m.group(2).strip(), rest = m.group(3).strip();
                switch (m.group(1)) {
                    case "fold" -> {
                        String k = what.equals("_") ? "" : what.toLowerCase(Locale.ROOT);
                        for (String sp : rest.split(",")) { Spelling s = spelling(sp); if (!s.text().isEmpty()) folded.add(new String[]{k, s.text(), s.condition()}); }
                    }
                    case "same" -> {
                        String k = what.toLowerCase(Locale.ROOT);
                        same.put(plain(k), plain(k));
                        for (String w : rest.split(",")) if (!w.isBlank()) same.put(plain(w.strip().toLowerCase(Locale.ROOT)), plain(k));
                    }
                    case "style" -> {
                        List<String[]> cs = new ArrayList<>();
                        for (String c : rest.split("\\s+")) { int eq = c.indexOf('='); if (eq > 0) cs.add(new String[]{c.substring(0, eq), c.substring(eq + 1).replace("_", "")}); }
                        changes.put(what, cs);
                        letterLines.putIfAbsent(what, new LinkedHashMap<>());
                    }
                    case "letter" -> {
                        String[] parts = what.split("\\s+");
                        String style = parts.length > 1 ? parts[0] : "", text = parts[parts.length - 1].toLowerCase(Locale.ROOT);
                        Spelling s = spelling(rest);
                        String latin = s.text().equals("_") ? "" : s.text();
                        if (!style.isEmpty()) changes.putIfAbsent(style, new ArrayList<>());
                        letterLines.computeIfAbsent(style, k -> new LinkedHashMap<>()).put(text + "\u0000" + s.condition(), new String[]{text, latin, s.condition()});
                    }
                    case "syllable" -> {
                        List<String> r = new ArrayList<>();
                        r.add(what.toLowerCase(Locale.ROOT));
                        for (String w : rest.split(",")) if (!w.isBlank()) r.add(w.strip().toLowerCase(Locale.ROOT));
                        syllableLines.put(r.get(0), r.toArray(new String[0]));
                    }
                    default -> { }
                }
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String k = line.substring(0, colon).strip(), v = line.substring(colon + 1).strip();
            switch (k) {
                case "name" -> name = v;
                case "marks" -> marks = v.toLowerCase(Locale.ROOT);
                case "joins" -> joins = v.replaceAll("\\s+", "");
                case "vowels" -> vowels = v.replaceAll("\\s+", "");
                case "drop" -> drop = v.replaceAll("\\s+", "");
                case "decompose" -> decompose = v.equalsIgnoreCase("yes");
                case "scripts" -> { for (String s : v.split("[,\\s]+")) if (!s.isBlank()) try { scripts.add(Character.UnicodeScript.forName(s.strip())); } catch (IllegalArgumentException unknown) { } }
                default -> { }
            }
        }
        List<Fold> folds = new ArrayList<>();
        for (String[] f : folded) folds.add(new Fold(f[0], new Spelling(f[1], f[2]), vowels));
        Letters letters = new Letters(letterRules(letterLines.getOrDefault("", Map.of()), vowels, decompose), decompose);
        List<Style> styles = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> e : changes.entrySet()) {
            List<Letter> own = letterRules(letterLines.getOrDefault(e.getKey(), Map.of()), vowels, decompose);
            styles.add(new Style(e.getKey(), e.getValue(), own.isEmpty() || letters.isEmpty() ? null : letters.under(own)));
        }
        Syllables syllables = syllableLines.isEmpty() ? null : new Syllables(new ArrayList<>(syllableLines.values()), joins);
        Pattern joining = joins.isEmpty() ? null : Pattern.compile("(?<=\\p{L})[" + chars(joins) + "](?=\\p{L})");
        return new Language(code, name, Collections.unmodifiableSet(scripts), marks, joins, vowels, drop, List.copyOf(folds), Map.copyOf(same),
                List.copyOf(styles), letters, syllables, joining);
    }

    /** The letter lines of one style as rules; the characters taken apart as the script is when it is decomposed. */
    private static List<Letter> letterRules(Map<String, String[]> lines, String vowels, boolean decompose) {
        List<Letter> out = new ArrayList<>();
        for (String[] l : lines.values()) out.add(new Letter(decompose ? Normalizer.normalize(l[0], Normalizer.Form.NFD) : l[0], l[1], Where.of(l[2], vowels)));
        return out;
    }

    private static Spelling spelling(String s) {
        String t = s.strip();
        Matcher c = CONDITIONED.matcher(t);
        return c.matches() ? new Spelling(c.group(1).strip().toLowerCase(Locale.ROOT), c.group(2).strip()) : new Spelling(t.toLowerCase(Locale.ROOT), "");
    }

    /** A word without its accents, as words are compared. */
    private static String plain(String w) { return MARKS.matcher(Normalizer.normalize(w, Normalizer.Form.NFKD)).replaceAll(""); }

    /**
     * The languages a person's forms are of: each whose script or letters one of the forms is written in. A person known only in Latin
     * letters with no letter only one language writes has none: an English Lee is not a Korean Yi.
     */
    public static List<Language> languagesOf(Collection<String> forms) {
        List<Language> out = new ArrayList<>();
        for (Language l : all()) for (String f : forms) if (l.writes(f)) { out.add(l); break; }
        // a name in Chinese characters is of more than one language: where nothing else says which, it is Japanese, as the program has read
        // such names from the start. A Korean name in its characters beside its Hangul is Korean only
        if (out.isEmpty() && forms.stream().anyMatch(f -> f != null && f.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN))) {
            Language ja = of(HAN);
            if (ja != null) out.add(ja);
        }
        return out;
    }

    /** The language a name in Chinese characters is taken to be when none of its other forms says otherwise. */
    static final String HAN = "ja";

    /** Whether two Latin forms of a person's name are one name in one of the person's languages. */
    public static boolean same(Collection<Language> languages, String a, String b) {
        for (Language l : languages) { String ka = l.key(a); if (!ka.isEmpty() && ka.equals(l.key(b))) return true; }
        return false;
    }
}
