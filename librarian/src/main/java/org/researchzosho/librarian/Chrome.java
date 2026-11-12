package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;

import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The words of the pages, the chat's own sentences and the command line's fixed sentences, in the language the person reads: every
 * label, title, button, note and sentence the program says of its own is looked up here by its English text, and said in the person's
 * language when the program has a file for it, in English otherwise. One file per language under {@code chrome/} beside this class
 * ({@code ja.json} for Japanese, and {@code zh}, {@code ko}, {@code de}, {@code fr}, {@code es}, {@code pt}, {@code it}, {@code ru} when
 * present): English text to the sentence in that language, with {@code {0}}, {@code {1}}… where a name, a number or a link goes.
 * Another language is one more file and its name in {@link #FILES}. A text the file lacks is said in English, and the miss is written
 * down for the tests to read. Dates and numbers follow the language's own conventions through its locale; what the chat reads in a
 * person's own words in each language is {@link ChatWords}.
 *
 * <p>Which language: a request's is chosen once by {@link #choose} and held for its thread; the terminal's is {@link #DEFAULT}, set at the
 * command line's start from the environment. The model's own words are never translated here: a report comes in the language it was
 * written in and is read in another through {@link Translate}; this is the chrome around them.
 */
public final class Chrome {
    public static final String EN = "en";
    /** The browser's cookie that keeps the language the person switched to on a page. */
    static final String COOKIE = "rz_lang";
    /** The query that switches a page's language, and sets the cookie: {@code ?lang=ja}. */
    static final String QUERY = "lang";
    /** The seam a test task closes: the process's language, over the environment. */
    static final String PROPERTY = "researchzosho.lang";

    private static final ObjectMapper M = new ObjectMapper();
    private static final Map<String, Chrome> LOADED = new ConcurrentHashMap<>();
    private static final ThreadLocal<Chrome> CURRENT = new ThreadLocal<>();
    /** The language of the process itself, for the terminal and for whatever runs outside a request; English until the command line sets it. */
    static final Chrome ENGLISH = new Chrome(EN, Map.of());
    static volatile Chrome DEFAULT = ENGLISH;
    /** Every text asked for in a language whose file lacks it, as {@code code<tab>text}; the completeness test reads and empties it. */
    static final Set<String> MISSING = ConcurrentHashMap.newKeySet();
    /** The languages a file may exist for, in the order the switch offers them; English needs none. */
    static final List<String> FILES = List.of("ja", "zh", "ko", "de", "fr", "es", "pt", "it", "ru");
    private static volatile List<String> available;

    final String code;
    private final Map<String, String> words;

    private Chrome(String code, Map<String, String> words) { this.code = code; this.words = words; }

    /** The chrome of a language by its code or its name ("ja", "Japanese", "日本語", "pt-BR"); English for one with no file. */
    public static Chrome in(String language) {
        String c = codeOf(language);
        if (c.isEmpty() || c.equals(EN)) return ENGLISH;
        return LOADED.computeIfAbsent(c, Chrome::load);
    }

    private static Chrome load(String c) {
        try (InputStream in = Chrome.class.getResourceAsStream("chrome/" + c + ".json")) {
            if (in == null) return ENGLISH;
            JsonNode j = M.readTree(in);
            Map<String, String> m = new LinkedHashMap<>();
            j.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
            return new Chrome(c, Map.copyOf(m));
        } catch (IOException e) { return ENGLISH; }
    }

    /** The language of this thread's request, else the process's. */
    public static Chrome now() { Chrome c = CURRENT.get(); return c == null ? DEFAULT : c; }

    /** This thread speaks the language from here on: set by a request as it starts, and by a test. */
    static void here(String language) { CURRENT.set(in(language)); }
    static void clear() { CURRENT.remove(); }

    /** The languages the chrome is written in: English, then each language with a file. */
    public static List<String> available() {
        List<String> a = available;
        if (a == null) {
            a = new ArrayList<>(); a.add(EN);
            for (String c : FILES) if (Chrome.class.getResource("chrome/" + c + ".json") != null) a.add(c);
            available = List.copyOf(a);
        }
        return a;
    }

    /** A language's own name for the switch: 日本語, English. */
    public static String nativeName(String code) {
        return switch (code) { case "ja" -> "日本語"; case "zh" -> "中文"; case "ko" -> "한국어"; case "de" -> "Deutsch"; case "fr" -> "Français"; case "es" -> "Español"; case "pt" -> "Português"; case "it" -> "Italiano"; case "ru" -> "Русский"; default -> "English"; };
    }

    public boolean ja() { return code.equals("ja"); }
    public boolean english() { return code.equals(EN); }

    /** The language's locale, for its dates and numbers; English keeps the program's own forms. */
    public Locale locale() { return english() ? Locale.ENGLISH : Locale.forLanguageTag(code); }

    /** A whole number as the language writes it: 11,979 in English and Japanese, 11.979 in German, 11 979 in French. */
    public String number(long n) { return english() ? String.format(Locale.ROOT, "%,d", n) : NumberFormat.getIntegerInstance(locale()).format(n); }

    /** What separates the items of a list the program joins: 、 where the language writes it (Japanese, Chinese), a comma and a space elsewhere. */
    public String sep() { return code.equals("ja") || code.equals("zh") ? "、" : ", "; }

    /**
     * A language's name in this language, as it stands inside a sentence: "Japanese", 日本語, "japonais", "Japanisch" (the words file of the
     * language gives it, with its own capitals); else the interface's word; a language the table has no word for, as {@link ReadIn} names it.
     */
    public String languageName(String language) {
        String name = ReadIn.name(language), c = Translate.code(language);
        if (!Translate.LANGUAGES.containsKey(c)) {   // Dutch, Polish…: the interface's word for the languages the program tells apart
            if (!Languages.NAMES.containsKey(c)) return name;
            String w = t(name), ten = english() ? "" : ChatWords.nameIn(code, Translate.LANGUAGES.keySet().iterator().next());
            // the word is written for a list, with a capital; inside a sentence it takes a small letter where this language writes the ten so ("écrit en néerlandais")
            boolean small = !ten.isEmpty() && Character.isLowerCase(ten.codePointAt(0)) && !w.isEmpty();
            return small ? new StringBuilder().appendCodePoint(Character.toLowerCase(w.codePointAt(0))).append(w.substring(Character.charCount(w.codePointAt(0)))).toString() : w;
        }
        String own = english() ? "" : ChatWords.nameIn(code, c);
        return own.isEmpty() ? t(name) : own;
    }

    /** A question the person answers yes or no, Enter being no: the sentence, then its language's own hint from its words file, "(y/N)", "(s/N)", "（y/N）". */
    public String yesNo(String sentence) {
        String h = ChatWords.hint(code);
        return sentence + (h.startsWith("（") ? "" : " ") + h;   // a full-width hint closes up to its sentence, as Japanese and Chinese write it
    }

    /** The language file's word for an English text, or null when it has none; nothing is written down as missing. */
    String word(String en) { return code.equals(EN) ? en : words.get(en); }

    /** The text in this language; the English when the file lacks it, with the miss written down. */
    public String t(String en) {
        if (en == null) return "";
        if (code.equals(EN)) return en;
        String w = words.get(en);
        if (w != null) return w;
        MISSING.add(code + "\t" + en);
        return en;
    }

    /** The text with its places filled: {0}, {1}… each with the argument as a string, which the caller escaped where the page needs it. */
    public String t(String en, Object... args) {
        String s = t(en);
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    /**
     * A text whose English has two meanings, said with the meaning beside it: the file's key is the English and the meaning,
     * "Keep ⟦a button that saves the thing beside it⟧"; English shows the English. A miss is written down under that key.
     */
    public String tc(String en, String meaning, Object... args) {
        if (english()) return t(en, args);
        String key = en + " ⟦" + meaning + "⟧";
        String s = words.get(key);
        if (s == null) { MISSING.add(code + "\t" + key); s = en; }
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    /** A number with its decimals as the language writes it: 0.187 in English, 0,187 in German and French. */
    public String decimal(double d, int digits) {
        if (english()) return String.format(Locale.ROOT, "%." + digits + "f", d);
        NumberFormat f = NumberFormat.getNumberInstance(locale()); f.setMinimumFractionDigits(digits); f.setMaximumFractionDigits(digits); f.setGroupingUsed(false);
        return f.format(d);
    }

    /** Things listed as the language lists them: "a", "a and b", "a, b and c"; the last join its own words ({0} and {1}). */
    public String and(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        return t("{0} and {1}", String.join(sep(), items.subList(0, items.size() - 1)), items.get(items.size() - 1));
    }

    /** A language's name standing alone, as in a menu or a list to choose from (Japonais, Japanisch, 日本語): the interface's word for it. */
    public String languageLabel(String language) {
        String name = ReadIn.name(language);
        return Translate.LANGUAGES.containsKey(Translate.code(language)) ? t(name) : name;
    }

    /** A count with its noun: {@code n(3, "{0} claim", "{0} claims")}; the language with no plural keeps both texts to one sentence. */
    public String n(long n, String one, String many) { return t(n == 1 ? one : many, n); }

    /**
     * A moment as a person reads it: "today, 08:22", "yesterday, 05:08", "1 Nov, 05:08", "14 Oct 2025"; in another language its words for
     * today and yesterday and its locale's day (今日 08:22, 昨日 05:08, 11月1日 05:08, 2025年10月14日; heute 08:22, 1. Nov. 05:08). The text
     * itself when it is not a moment.
     */
    public String dayTime(String iso) {
        if (iso == null || iso.length() < 10) return iso == null ? "" : iso;
        try {
            ZonedDateTime t = iso.endsWith("Z") || iso.contains("+") ? Instant.parse(iso).atZone(ZoneId.systemDefault()) : LocalDateTime.parse(iso.length() > 19 ? iso.substring(0, 19) : iso.length() == 10 ? iso + "T00:00:00" : iso).atZone(ZoneId.systemDefault());
            LocalDate d = t.toLocalDate(), today = LocalDate.now();
            String hm = iso.length() == 10 ? "" : String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());
            if (!english()) {
                String day = d.equals(today) ? t("today") : d.equals(today.minusDays(1)) ? t("yesterday") : DateTimeFormatter.ofLocalizedPattern(d.getYear() == today.getYear() ? "MMMd" : "yMMMd").withLocale(locale()).format(d);
                return hm.isEmpty() ? day : day + " " + hm;
            }
            String sep = hm.isEmpty() ? "" : ", " + hm;
            if (d.equals(today)) return "today" + sep;
            if (d.equals(today.minusDays(1))) return "yesterday" + sep;
            String day = d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            return (d.getYear() == today.getYear() ? day : day + " " + d.getYear()) + sep;
        } catch (Exception e) { return iso; }
    }

    /** A day as a person reads it: 2026-11-05 stays so in English; in another language as its locale writes a day (2026年11月5日, 5. Nov. 2026). Anything else as it is. */
    public String day(String ymd) {
        if (english() || ymd == null || !ymd.matches("\\d{4}-\\d{2}-\\d{2}.*")) return ymd == null ? "" : ymd;
        try { return DateTimeFormatter.ofLocalizedPattern("yMMMd").withLocale(locale()).format(LocalDate.parse(ymd.substring(0, 10))) + ymd.substring(10); }
        catch (Exception e) { return ymd; }
    }

    /** An instant with its minute: "2026-11-05 10:22 UTC"; in another language its day as {@link #day} writes it, 2026年11月5日 10:22 UTC. */
    public String when(String iso) {
        if (iso == null || iso.length() < 16 || iso.charAt(10) != 'T') return iso == null ? "" : iso;
        return day(iso.substring(0, 10)) + " " + iso.substring(11, 16) + " UTC";
    }

    // ---------------------------------------------------------------- which language

    /**
     * The language of one request, in this order: the query that switches it, the cookie the switch set, a signed-in person's language to
     * read reports in, the browser's Accept-Language, the language the library writes its reports in, English. Each counts only when the
     * chrome is written in it.
     */
    static String choose(LibraryStore store, Patrons.Patron patron, String asked, String cookie, String acceptLanguage) {
        String c = pick(asked);
        if (c == null) c = pick(cookie);
        if (c == null && store != null && patron != null) c = pick(ReadIn.of(store, patron));
        if (c == null) c = fromAccept(acceptLanguage);
        if (c == null) c = pick(Config.get("RESEARCHZOSHO_LANGUAGE", ""));
        return c == null ? EN : c;
    }

    /** The code when the chrome is written in that language, else null. */
    static String pick(String language) {
        if (language == null || language.isBlank()) return null;
        String c = codeOf(language);
        return available().contains(c) ? c : null;
    }

    /** The code a Chinese written in its traditional characters is given: the Chinese file is in simplified characters, so it is not this one. */
    static final String TRADITIONAL = "zh-hant";

    /**
     * A language as a person, a browser or the system names it, as the code of its file: "ja", "Japanese", "日本語", "pt-BR", "pt_BR.UTF-8",
     * "zh-CN", "zh-Hans" → ja, pt, zh. Chinese named for its traditional characters or for where they are written (zh-Hant, zh-TW, zh-HK,
     * zh-MO) is {@link #TRADITIONAL}, which no file is: the Chinese file is simplified, and such a browser's next language is asked instead.
     */
    static String codeOf(String language) {
        if (language == null) return "";
        String s = language.strip();
        int cut = s.indexOf('.'); if (cut > 0) s = s.substring(0, cut);   // zh_CN.UTF-8
        cut = s.indexOf('@'); if (cut > 0) s = s.substring(0, cut);       // sr_RS@latin
        s = s.replace('_', '-').toLowerCase(Locale.ROOT);
        if (!s.contains("-")) return Translate.code(s);
        String[] p = s.split("-");
        if (p[0].equals("zh")) {
            List<String> rest = List.of(p).subList(1, p.length);
            if (!rest.contains("hans") && (rest.contains("hant") || rest.contains("tw") || rest.contains("hk") || rest.contains("mo"))) return TRADITIONAL;
        }
        return Translate.code(p[0]);
    }

    /** The first language of an Accept-Language header the chrome is written in, by the browser's order of preference; null for none. */
    static String fromAccept(String header) {
        if (header == null || header.isBlank()) return null;
        List<String[]> ranked = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] p = part.strip().split(";");
            if (p[0].isBlank()) continue;
            double q = 1;
            for (int i = 1; i < p.length; i++) { String v = p[i].strip(); if (v.startsWith("q=")) { try { q = Double.parseDouble(v.substring(2)); } catch (NumberFormatException ignored) { } } }
            ranked.add(new String[]{p[0].strip().toLowerCase(Locale.ROOT), String.valueOf(q)});
        }
        ranked.sort((a, b) -> Double.compare(Double.parseDouble(b[1]), Double.parseDouble(a[1])));   // stable: the header's order among equals
        for (String[] r : ranked) {
            if (Double.parseDouble(r[1]) <= 0) continue;
            String c = codeOf(r[0]);   // pt-BR is pt, zh-CN and zh-Hans zh; zh-TW and zh-Hant are not the simplified file's
            if (available().contains(c)) return c;
        }
        return null;
    }

    /** The process's own language, for the terminal: the test seam first, then the library's language setting, then LC_ALL, LC_MESSAGES and LANG when they name a language the chrome is written in; English otherwise. */
    static String fromEnvironment() {
        String p = System.getProperty(PROPERTY, "");
        if (!p.isBlank()) { String c = pick(p); return c == null ? EN : c; }
        String c = pick(Config.get("RESEARCHZOSHO_LANGUAGE", ""));
        if (c != null) return c;
        for (String var : List.of("LC_ALL", "LC_MESSAGES", "LANG")) {
            String v = System.getenv(var);
            if (v == null || v.isBlank() || v.equals("C") || v.equals("POSIX")) continue;
            c = pick(v);   // ja_JP.UTF-8, pt_BR, zh_CN; zh_TW is not the simplified file's
            if (c != null) return c;
            break;   // the first variable set decides, as the system does
        }
        return EN;
    }

    /** The command line's start: the process speaks the language of its environment from here on. */
    static void fromEnvironmentNow() { DEFAULT = in(fromEnvironment()); }

    /** The language switch's address for a page: the page's own address with {@code lang} replaced. */
    static String switchHref(String pathAndQuery, String code) {
        String base = pathAndQuery == null || pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        return base + (base.contains("?") ? "&" : "?") + QUERY + "=" + code;
    }
}
