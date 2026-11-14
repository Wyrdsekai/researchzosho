package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What the chat reads in the person's own words, in every language: the short answers (a tier, where the notes go, yes, no, "this file")
 * and the asks the library answers itself (a translation, a report read in a language, a glossary line, pictures, the models). One file
 * per language under {@code chrome/words/} beside the interface's words: a meaning to the list of what a person types for it. Every file
 * present is read and the lists joined, English first, so a person is understood in their language whatever language the pages are in.
 *
 * <p>An entry is a phrase ("vérifié"), a phrase with places ("traduis {what} en {lang}"), or a regular expression after {@code re:}. A
 * phrase is matched without regard to case or width; a space in it stands for any run of spaces (or none, between letters that do not
 * space their words); a phrase in letters that space their words (Latin, Cyrillic, Greek) stands as a whole word, and one in kana, han
 * or hangul anywhere. How each meaning is matched is the reader's: a tier anywhere in the words, yes at their start, no as the whole
 * answer, an ask as the whole message.
 */
final class ChatWords {
    private ChatWords() { }

    private static final ObjectMapper M = new ObjectMapper();
    /** The languages a words file may exist for, in the order their lists are joined: English, then the interface's. */
    static final List<String> CODES;
    static {
        List<String> c = new ArrayList<>(); c.add(Chrome.EN); c.addAll(Chrome.FILES);
        CODES = List.copyOf(c);
    }
    /** The meanings every words file carries. */
    static final List<String> MEANINGS = List.of("tier.quick", "tier.checked", "tier.reviewed", "tier.thorough", "notes.end", "notes.off", "yes", "yes.alone", "no", "none", "this_file",
            "this_report", "lang.en", "lang.ja", "lang.zh", "lang.ko", "lang.de", "lang.fr", "lang.es", "lang.pt", "lang.it", "lang.ru",
            "translate.ask", "translate.mention", "translate.from", "translate.direction", "translate.filler", "format.as", "read_in.ask", "dossier.named", "glossary.add", "glossary.list",
            "pictures.find", "pictures.keep", "pictures.keep_all", "pictures.judge", "pictures.describe", "pictures.same", "pictures.like",
            "kind.picture", "kind.person", "kind.place", "kind.thing", "kind.animal",
            "models.model", "models.default", "models.hosted", "models.key", "models.ours", "models.using", "models.research", "models.setup", "models.thing", "models.change",
            "hint.yes_no");
    /** The tiers, in the order the words are read for them: the most thorough named wins. */
    static final List<String> TIERS = List.of(Translation.THOROUGH, Translation.REVIEWED, Translation.CHECKED, Translation.QUICK);

    private static volatile Map<String, Map<String, List<String>>> files;
    private static volatile String languageNames;
    private static volatile String sourceLanguageNames;
    private static volatile Map<String, String> sourceNames;
    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();
    private static final Pattern PLACE = Pattern.compile("\\{(\\w+)\\}");
    /** The blank a phrase leaves behind between two letters of writing that does not space its words; Korean spaces its words, so a blank between Hangul stays ("이 파일" is two words, and a document's name keeps its own). */
    private static final String CJK_GAP = "(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}、。，])\\s+(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}、。，])";

    /** Every words file present, by its language: meaning to entries, in the file's order. */
    static Map<String, Map<String, List<String>>> files() {
        Map<String, Map<String, List<String>>> f = files;
        if (f != null) return f;
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (String code : CODES) {
            Map<String, List<String>> m = read(code);
            if (m != null) out.put(code, m);
        }
        files = out;
        return out;
    }

    /** One language's file, or null when there is none or it cannot be read (the test of the files says which). */
    static Map<String, List<String>> read(String code) {
        try (InputStream in = ChatWords.class.getResourceAsStream("chrome/words/" + code + ".json")) {
            if (in == null) return null;
            JsonNode j = M.readTree(in);
            Map<String, List<String>> m = new LinkedHashMap<>();
            j.fields().forEachRemaining(e -> {
                if (e.getKey().startsWith("_") || !e.getValue().isArray()) return;   // "_how": a note for whoever writes the file
                List<String> l = new ArrayList<>();
                for (JsonNode x : e.getValue()) if (x.isTextual() && !x.asText().isBlank()) l.add(x.asText());
                m.put(e.getKey(), List.copyOf(l));
            });
            return m;
        } catch (IOException e) { return null; }
    }

    /** The entries for a meaning, every language joined, English first. */
    static List<String> entries(String meaning) {
        List<String> out = new ArrayList<>();
        for (Map<String, List<String>> f : files().values()) for (String e : f.getOrDefault(meaning, List.of())) if (!out.contains(e)) out.add(e);
        return out;
    }

    /** Text folded for a comparison: width and compatibility forms (ｙｅｓ, ｶﾀｶﾅ), case. */
    static String fold(String s) { return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT); }

    static String nfkc(String s) { return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFKC); }

    static boolean isPattern(String entry) { return entry.startsWith("re:"); }

    /** Whether a letter belongs to writing that spaces its words, so a phrase ending in it ends at a word's end. */
    static boolean spaced(int cp) {
        if (!Character.isLetterOrDigit(cp)) return false;
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s == Character.UnicodeScript.LATIN || s == Character.UnicodeScript.CYRILLIC || s == Character.UnicodeScript.GREEK || s == Character.UnicodeScript.COMMON;
    }

    /** A phrase's own regular expression, each place replaced by the group {@code places} gives it (a place not given stays as written). */
    static String phrase(String phrase, Map<String, String> places) {
        String p = phrase.strip();
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            Matcher m = PLACE.matcher(p);
            if (p.charAt(i) == '{' && m.find(i) && m.start() == i && places != null && places.containsKey(m.group(1))) {
                b.append("(?<").append(m.group(1)).append(">").append(places.get(m.group(1))).append(")");
                i = m.end();
            } else if (Character.isWhitespace(p.charAt(i))) {
                int j = i; while (j < p.length() && Character.isWhitespace(p.charAt(j))) j++;
                char before = p.charAt(i - 1), after = p.charAt(j);
                boolean words = (before == '}' || spaced(p.codePointBefore(i))) && (after == '{' || spaced(p.codePointAt(j)));
                b.append(words ? "\\s+" : "\\s*");
                i = j;
            } else { b.append(one(p.codePointAt(i))); i += Character.charCount(p.codePointAt(i)); }
        }
        String body = b.toString();
        if (!p.isEmpty() && p.charAt(0) != '{' && spaced(p.codePointAt(0))) body = "(?<![\\p{L}\\p{N}])" + body;   // a whole word where it begins and ends with spaced letters
        if (!p.isEmpty() && p.charAt(p.length() - 1) != '}' && spaced(p.codePointBefore(p.length()))) body = body + "(?![\\p{L}\\p{N}])";
        return body;
    }

    /** One character of a phrase as an expression: itself, and a digit or a stop also in its full width ("：" for ":", "（" for "("), either way round. */
    static String one(int cp) {
        int half = cp >= 0xFF01 && cp <= 0xFF5E ? cp - 0xFEE0 : cp, full = half >= 0x21 && half <= 0x7E ? half + 0xFEE0 : -1;
        if (full < 0 || Character.isLetter(half)) return Pattern.quote(new String(Character.toChars(cp)));
        return "[\\x{" + Integer.toHexString(half) + "}\\x{" + Integer.toHexString(full) + "}]";
    }

    /** An entry as a pattern: a regular expression as written; a phrase case- and width-blind. */
    static Pattern pattern(String entry, Map<String, String> places) {
        String key = entry + "\u0000" + (places == null ? "" : places.toString());
        return COMPILED.computeIfAbsent(key, k -> isPattern(entry) ? Pattern.compile(entry.substring(3)) : Pattern.compile("(?iu)" + phrase(entry, places)));
    }

    /** Whether one of the meaning's entries stands anywhere in the words. */
    static boolean has(String meaning, String words) {
        String w = nfkc(words);
        for (String e : entries(meaning)) if (pattern(e, null).matcher(w).find()) return true;
        return false;
    }

    /** Whether the words, a little punctuation aside, are one of the meaning's entries entire. */
    static boolean whole(String meaning, String words) {
        String w = nfkc(words).strip().replaceAll("[.!?。！？]+$", "").strip();
        for (String e : entries(meaning)) if (pattern(e, null).matcher(w).matches()) return true;
        return false;
    }

    /** The words with every entry of the meanings taken out, what is left tidied: "checked, with the notes at the end" → "checked". */
    static String without(String words, String... meanings) { return without(words, null, meanings); }

    /** The same, a phrase's places taking what {@code places} gives them ("en {fmt}" taken out of "ce fichier en pdf"). */
    static String without(String words, Map<String, String> places, String... meanings) {
        String w = " " + nfkc(words);
        // the phrases of every meaning named, the longest first whatever file or meaning it is from ("sem notas do tradutor" before
        // "sem notas", 译注不要 before another language's 注不要), then the expressions in the files' order
        List<String> phrases = new ArrayList<>(), patterns = new ArrayList<>();
        for (String meaning : meanings) for (String e : entries(meaning)) { List<String> l = isPattern(e) ? patterns : phrases; if (!l.contains(e)) l.add(e); }
        phrases.sort(Comparator.comparingInt(String::length).reversed());
        for (String e : phrases) w = pattern("re:(?iu)[\\s,;、，]*" + phrase(e, places), null).matcher(w).replaceAll(" ");
        for (String e : patterns) w = pattern(e, null).matcher(w).replaceAll(" ");
        return tidied(w);
    }

    /** Words a phrase was taken out of, tidied: no blank between two letters of writing that does not space its words, one blank between words, no comma left at an end. */
    static String tidied(String w) { return w.replaceAll(CJK_GAP, "").replaceAll("\\s{2,}", " ").replaceAll("^[\\s、，,;]+|[\\s、，,;]+$", ""); }

    /**
     * The words without the phrase of the meanings they END with, the longest such ("questo file con controllo" → "questo file"); the
     * words as they are when they end with none. Phrases only: an expression after re: says for itself where it may stand.
     */
    static String withoutAtEnd(String words, String... meanings) {
        String w = nfkc(words).strip();
        int cut = -1;
        for (String meaning : meanings) for (String e : entries(meaning)) {
            if (isPattern(e)) continue;
            Matcher m = pattern("re:(?iu)[\\s,;、，]*" + phrase(e, null) + "\\s*$", null).matcher(w);
            if (m.find() && m.start() > 0 && (cut < 0 || m.start() < cut)) cut = m.start();
        }
        return cut < 0 ? w : w.substring(0, cut).strip();
    }

    // ---------------------------------------------------------------- the short answers

    /**
     * The tier the words name ("thorough", "確認付き", "vérifié"), or "" when they name none: anywhere in the words (English's anywhere in
     * a word, as it always was). The longest phrase the words hold wins, so 전체 검토 is thorough though it holds 검토, and 全文査読 though
     * it holds 査読; between two of one length, the more thorough tier.
     */
    static String tier(String words) {
        String all = fold(words), best = "";
        int longest = 0;
        for (String t : TIERS) for (String e : entries("tier." + t)) {
            Matcher m = pattern(e, null).matcher(all);
            while (m.find()) if (m.end() - m.start() > longest) { longest = m.end() - m.start(); best = t; }
        }
        return best;
    }

    /** Which of the meanings the words hold the longest phrase of ("without the notes at the end" is notes.off though it holds the notes at the end); the first named between two of one length; "" for none. */
    static String longest(String words, String... meanings) {
        String w = nfkc(words), best = "";
        int longest = 0;
        for (String meaning : meanings) for (String e : entries(meaning)) {
            Matcher m = pattern(e, null).matcher(w);
            while (m.find()) if (m.end() - m.start() > longest) { longest = m.end() - m.start(); best = meaning; }
        }
        return best;
    }

    /** The hint a yes-or-no question ends with in a language, from its own words file: (y/N), (s/N), (j/N), (д/Н), （y/N）; English's for a language with none. */
    static String hint(String code) {
        for (String c : new String[]{code, Chrome.EN}) {
            Map<String, List<String>> f = files().get(c);
            if (f != null && !f.getOrDefault("hint.yes_no", List.of()).isEmpty()) return f.get("hint.yes_no").get(0);
        }
        return "(y/N)";
    }

    /** A language's name as its own words file writes it inside a sentence ("japonais", "Japanisch", "日本語"), or "" when the file has none. */
    static String nameIn(String code, String language) {
        Map<String, List<String>> f = files().get(code);
        List<String> names = f == null ? List.of() : f.getOrDefault("lang." + language, List.of());
        return names.isEmpty() || isPattern(names.get(0)) ? "" : names.get(0);
    }

    /**
     * The yes the words begin with, or null: "yes", "Yes, and also …", "ｙ", "はい、お願いします", "oui". The longest first, so the rest is
     * what follows it. A word under "yes" may be followed by more words ("yes please, and also …"). A word under "yes.alone" is a yes
     * only as the whole answer or before a comma or a stop ("sí, y también …"), because it can also open an ordinary sentence: a single
     * letter (the hint's "o" is Portuguese's "the"), "si" (if), "claro" in "claro que no", "да" in "да что же мне делать". Where one
     * file has a word under "yes" and another under "yes.alone", it stands alone.
     */
    static String yesWord(String words) {
        String w = fold(words);
        List<String> ys = new ArrayList<>(), alone = new ArrayList<>();
        for (String y : entries("yes.alone")) if (!isPattern(y) && !alone.contains(fold(y))) alone.add(fold(y));
        ys.addAll(alone);
        for (String y : entries("yes")) if (!isPattern(y) && !ys.contains(fold(y))) ys.add(fold(y));
        ys.sort(Comparator.comparingInt(String::length).reversed());
        for (String y : ys) {
            if (w.equals(y)) return y;
            if (!w.startsWith(y)) continue;
            int next = w.codePointAt(y.length());
            if (Character.isLetterOrDigit(next)) continue;
            if (Character.isWhitespace(next) && alone.contains(y)) continue;   // "claro que no", "o que eu faço?": a sentence, not an answer
            return y;
        }
        return null;
    }

    /** Whether the words are a no, or nothing (Enter): the whole answer, its closing stop aside. */
    static boolean isNo(String words) {
        String w = fold(words).replaceAll("[.!。！]+$", "");
        if (w.isEmpty()) return true;
        for (String n : entries("no")) if (isPattern(n) ? pattern(n, null).matcher(w).matches() : fold(n).equals(w)) return true;
        return false;
    }

    /** Whether the words choose none of the choices offered: a no, "none", "none of them", "aucun". */
    static boolean isNone(String words) { return isNo(words) || whole("none", words); }

    /** Whether the words only point at the file posted with them: "this file", "it", "ce fichier", "このファイル". */
    static boolean thisFile(String words) { return whole("this_file", words); }

    // ---------------------------------------------------------------- languages and asks

    /** The language a name stands for, in any language ("japonais", "Japanisch", "японский", "日本語", "ja"), or "" for none the library knows. */
    static String language(String name) {
        String n = fold(name);
        if (n.isEmpty()) return "";
        for (String code : Translate.LANGUAGES.keySet()) for (String e : entries("lang." + code)) if (!isPattern(e) && fold(e).equals(n)) return code;
        String c = Translate.code(n);
        return Translate.LANGUAGES.containsKey(c) ? c : "";
    }

    /** Every name of a language the files hold and the English ones, the longest first, as the expression a {lang} place takes. */
    static String languageNames() {
        String l = languageNames;
        if (l != null) return l;
        List<String> names = new ArrayList<>();
        for (String code : Translate.LANGUAGES.keySet()) {
            for (String e : entries("lang." + code)) if (!isPattern(e) && !names.contains(e)) names.add(e);
            if (!names.contains(Translate.LANGUAGES.get(code))) names.add(Translate.LANGUAGES.get(code));
        }
        names.sort(Comparator.comparingInt(String::length).reversed());
        List<String> quoted = new ArrayList<>();
        for (String n : names) quoted.add(Pattern.quote(nfkc(n)));
        l = "(?:" + String.join("|", quoted) + ")(?![\\p{IsLatin}\\p{IsCyrillic}])";
        languageNames = l;
        return l;
    }

    /**
     * Every name the program has for a language a document may be written in, the longest first, as the expression a {lang} place takes
     * where a person names it ("from Dutch", 「オランダ語から」): the ten of {@link #languageNames} in every file's words, and each language
     * the program tells apart from a text, in English and as each interface language names it.
     */
    static String sourceLanguageNames() {
        String l = sourceLanguageNames;
        if (l != null) return l;
        List<String> names = new ArrayList<>(sourceNames().keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        List<String> quoted = new ArrayList<>();
        for (String n : names) quoted.add(Pattern.quote(n));
        l = "(?:" + String.join("|", quoted) + ")(?![\\p{IsLatin}\\p{IsCyrillic}])";
        sourceLanguageNames = l;
        return l;
    }

    /** Each such name, folded, to its language's code: the words files' names of the ten first, then English's and each interface language's word for every language the program tells apart. */
    static Map<String, String> sourceNames() {
        Map<String, String> m = sourceNames;
        if (m != null) return m;
        m = new LinkedHashMap<>();
        for (String code : Translate.LANGUAGES.keySet()) for (String e : entries("lang." + code)) if (!isPattern(e)) m.putIfAbsent(fold(e), code);
        for (var e : Languages.NAMES.entrySet()) {
            m.putIfAbsent(fold(e.getValue()), e.getKey());
            for (String lang : Chrome.available()) { String w = Chrome.in(lang).word(e.getValue()); if (w != null && !w.isBlank()) m.putIfAbsent(fold(w), e.getKey()); }
        }
        sourceNames = m;
        return m;
    }

    /** The language such a name stands for, as its code; "" for none. */
    static String sourceLanguage(String name) {
        String c = sourceNames().get(fold(name));
        return c != null ? c : Languages.codeOf(nfkc(name));
    }

    /**
     * The language a declined name stands for, from the part of it that does not change ("Polnisch" of "aus dem Polnischen", "польск"
     * of "с польского"; a words file's expression takes it as the group "stem"): the one language a name of which begins with it, or
     * "" when none does or more than one.
     */
    static String sourceLanguageByStem(String stem) {
        String s = fold(stem), found = "";
        if (s.codePointCount(0, s.length()) < 4) return "";
        for (var e : sourceNames().entrySet()) if (e.getKey().startsWith(s)) { if (!found.isEmpty() && !found.equals(e.getValue())) return ""; found = e.getValue(); }
        return found;
    }

    /** The first language the words name, in any of the files' words, as its code; "" for none. */
    static String languageIn(String words) {
        Matcher m = pattern("re:(?iu)(?<![\\p{IsLatin}\\p{IsCyrillic}\\p{IsGreek}])(" + languageNames() + ")", null).matcher(nfkc(words));   // a whole word where words are spaced; 翻译成日语 and 日本語に have a letter before the name
        return m.find() ? language(m.group(1)) : "";
    }

    static boolean namesALanguage(String words) { return !languageIn(words).isEmpty(); }

    /**
     * An ask in the words: the first entry of {@code meaning} that matches the whole message, with what each named place took; null when
     * none does. {@code places}: the expression each place of a phrase takes; with {@code rest}, a phrase may be followed by more words
     * (the group "rest": after a stop or a space, or straight on where the phrase ends in letters that do not space their words), else
     * by a closing stop alone. {@code accept} turns a match down (a language the library does not know) so the next entry is tried. The
     * words are read as written, so what a place takes (a glossary's rendering) keeps its own characters.
     */
    static Map<String, String> ask(String meaning, String words, Map<String, String> places, boolean rest, Predicate<Map<String, String>> accept) {
        if (words == null) return null;
        String w = words.strip();
        List<String> ordered = new ArrayList<>(), phrases = new ArrayList<>();
        for (String e : entries(meaning)) (isPattern(e) ? ordered : phrases).add(e);
        phrases.sort(Comparator.comparingInt(String::length).reversed());   // the longest phrase first, never the files' order
        ordered.addAll(phrases);
        for (String e : ordered) {
            Pattern p;
            try {
                if (isPattern(e)) p = pattern(e, null);
                else {
                    String s = e.strip();
                    Matcher last = Pattern.compile("\\{(\\w+)\\}$").matcher(s);
                    boolean loose = last.find() && places != null && places.getOrDefault(last.group(1), "").endsWith("?");   // a place that takes as little as will do ({what}): what follows it starts at a stop
                    String tail = !rest ? "\\s*[.!?。！？]?\\s*" : loose ? "(?<rest>(?:[,;:!?、。，]|\\.(?=\\s|$)).*)?" : s.endsWith("}") || spaced(s.codePointBefore(s.length())) ? "(?<rest>[\\s,;:.!?、。，].*)?" : "(?<rest>.*)";
                    p = pattern("re:(?ius)^\\s*(?:" + phrase(e, places) + ")" + tail + "$", null);
                }
            } catch (PatternSyntaxException x) { continue; }
            Matcher m = p.matcher(w);
            if (!m.matches()) continue;
            Map<String, String> got = new LinkedHashMap<>();
            for (String g : groups(p.pattern())) { String v = m.group(g); if (v != null) got.put(g, v); }
            if (accept == null || accept.test(got)) return got;
        }
        return null;
    }

    static Map<String, String> ask(String meaning, String words, Map<String, String> places) { return ask(meaning, words, places, false, null); }

    /** The places of the first entry of {@code meaning} found anywhere in the words ("as a PDF", "en PDF", "PDFで"); null for none. */
    static Map<String, String> find(String meaning, String words, Map<String, String> places) {
        String w = words == null ? "" : words;
        for (String e : entries(meaning)) {
            Pattern p = pattern(e, places);
            Matcher m = p.matcher(w);
            if (!m.find()) continue;
            Map<String, String> got = new LinkedHashMap<>();
            for (String g : groups(p.pattern())) { String v = m.group(g); if (v != null) got.put(g, v); }
            return got;
        }
        return null;
    }

    /** The names of a pattern's groups. */
    static List<String> groups(String regex) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>").matcher(regex);
        while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    /** Which meaning under {@code prefix} a word is one of ("kind." and "personne" → "person"); "" for none. */
    static String whichOf(String prefix, String word) {
        String w = fold(word);
        for (Map<String, List<String>> f : files().values())
            for (var e : f.entrySet()) if (e.getKey().startsWith(prefix)) for (String x : e.getValue()) if (!isPattern(x) && fold(x).equals(w)) return e.getKey().substring(prefix.length());
        return "";
    }

    /** Every entry of every meaning under {@code prefix}, as the expression a place takes, the longest first. */
    static String alternation(String prefix) {
        List<String> all = new ArrayList<>();
        for (Map<String, List<String>> f : files().values()) for (var e : f.entrySet()) if (e.getKey().startsWith(prefix)) for (String x : e.getValue()) if (!isPattern(x) && !all.contains(x)) all.add(x);
        all.sort(Comparator.comparingInt(String::length).reversed());
        List<String> q = new ArrayList<>();
        for (String x : all) q.add(Pattern.quote(nfkc(x)));
        return q.isEmpty() ? "(?!)" : "(?:" + String.join("|", q) + ")";
    }
}
