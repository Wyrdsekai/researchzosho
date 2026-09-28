package org.researchzosho.librarian;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The written forms of one name: as the source wrote it, in modern and in old characters, the kana reading a source gave, each romaji
 * spelling of that reading (Endō, Endo, Endou, Endoh, Endoo), and both name orders. Forms of ONE name are one name; two names of one
 * person (遠藤健二 and 森田健二) are never forms of each other. Across scripts two forms meet only through a reading a source gave: kana
 * become romaji by rule, and a reading is never worked out for Chinese characters, because a reading you work out yourself is a guess at a
 * name (遠藤 and 円藤 are both えんどう). A romaji form never becomes a form in characters.
 */
public final class FamilyForms {

    private FamilyForms() { }

    /** The script a form is written in: "han" (Chinese characters, kana among them or not), "kana", "latin", or "" for none of these. */
    public static String script(String s) {
        boolean han = false, kana = false, latin = false;
        String t = s == null ? "" : s;
        for (int i = 0; i < t.length(); ) {
            int c = t.codePointAt(i);
            Character.UnicodeScript u = Character.UnicodeScript.of(c);
            if (u == Character.UnicodeScript.HAN) han = true;
            else if (u == Character.UnicodeScript.HIRAGANA || u == Character.UnicodeScript.KATAKANA || c == 0x30fc) kana = true;
            else if (u == Character.UnicodeScript.LATIN) latin = true;
            i += Character.charCount(c);
        }
        return han ? "han" : kana ? "kana" : latin ? "latin" : "";
    }

    /**
     * The BCP-47 tag of a form by its script alone: ja-Hani, ja-Hira, ja-Kana, ja-Hrkt for characters and kana; und-Latn for Latin letters,
     * which are romaji only when the name has a Japanese form or reading ({@link #lang(String, boolean)}); "" for any other script.
     */
    public static String lang(String s) { return lang(s, false); }

    /** The same, for a form of a name that has a form in Japanese characters or kana ({@code japanese}): its Latin letters are romaji, ja-Latn. */
    public static String lang(String s, boolean japanese) {
        String sc = script(s);
        if (sc.equals("han")) return "ja-Hani";
        if (sc.equals("latin")) return japanese ? "ja-Latn" : "und-Latn";
        if (!sc.equals("kana")) return "";
        boolean kata = s.codePoints().anyMatch(c -> c >= 0x30a1 && c <= 0x30fa);
        boolean hira = s.codePoints().anyMatch(c -> c >= 0x3041 && c <= 0x3096);
        return hira && kata ? "ja-Hrkt" : kata ? "ja-Kana" : "ja-Hira";
    }

    /** Whether a form is written in Japanese characters or kana. */
    public static boolean japanese(String s) { String sc = script(s); return sc.equals("han") || sc.equals("kana"); }

    /** An apostrophe inside a word: Ken'ichi, O'Hara. It joins, it does not part. */
    private static final String APOSTROPHE = "['’‘ʼ`´]";

    /**
     * A form in Latin letters as romaji is compared: no accents, lower case, long vowels written one way (ō, ou, oh, oo are o; ū and uu are u),
     * the apostrophe or hyphen of ん before a vowel left out (Ken'ichi, Ken-ichi and Kenichi are one), words sorted. For a form of a
     * Japanese name; any other name in Latin letters is compared by {@link #plainKey}, as Gould and Gold are two names.
     */
    public static String latinKey(String s) { return japanese().key(s); }

    /** The Japanese rules of how names are written in Latin letters ({@link NameSpellings}, spellings/ja.txt). */
    private static NameSpellings.Language japanese() { return NameSpellings.of("ja"); }

    /**
     * A form in letters of any alphabet as it is compared: no accents, lower case, an apostrophe inside a word left out, words sorted. Nothing
     * else is folded: Gould and Gold, Moore and More are two names. Cyrillic, Greek, Hangul and every other script are compared this way.
     */
    public static String plainKey(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        n = Normalizer.normalize(Normalizer.normalize(n, Normalizer.Form.NFKD).replaceAll("\\p{M}+", ""), Normalizer.Form.NFC);
        n = n.replaceAll("(?<=\\p{L})" + APOSTROPHE + "(?=\\p{L})", "");
        List<String> words = new ArrayList<>();
        for (String w : n.split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) words.add(w);
        words.sort(null);
        return String.join(" ", words);
    }

    /**
     * The syllables of romaji, Hepburn and Kunrei alike: a vowel after nothing or after a consonant of the syllabary (ka, shi, tsu, kyo, ja,
     * fu), a moraic n, and a consonant doubled before the next syllable (kk, ss, tch).
     */
    private static final Pattern ROMAJI = Pattern.compile("(?:(?:[kgsztdnhbpmrfjvw]y?|ch|sh|ts|y)?[aeiou]|n|([kgsztdbpfjc])(?=\\1)|t(?=ch))+");

    /**
     * Whether a word in Latin letters can be romanised Japanese: it is made of romaji syllables, long vowels marked or
     * not (Endō, Endou, Endoh). Morita, Shoichi, Takahashi and Ken'ichi can; Hart, Ellis and Mary cannot. A word that can is no proof that the
     * name is Japanese, only that its order cannot be read off its letters.
     */
    public static boolean romajiWord(String word) {
        String k = Normalizer.normalize(word == null ? "" : word, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("(?<=\\p{L})" + APOSTROPHE + "(?=\\p{L})", "").replaceAll("oh(?![aeiou])", "o");
        return k.length() >= 2 && k.chars().allMatch(Character::isLetter) && ROMAJI.matcher(k).matches();
    }

    /**
     * Whether a name in Latin letters is written like a romanised Japanese name: two words, no comma, each of them romaji ({@link #romajiWord}).
     * Such a name is written family name first as often as given name first (Morita Shoichi, Shoichi Morita), so which word is its family
     * part is known only from evidence: a form in characters, a reading, a family the library knows, the text's own "Family, Given".
     */
    public static boolean romajiName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.contains(",") || !script(n).equals("latin")) return false;
        String[] w = n.split("\\s+");
        return w.length == 2 && romajiWord(w[0]) && romajiWord(w[1]);
    }

    /** Whether a form in Latin letters marks a long vowel with a macron (ō, ū): the way romaji writes one, so the form is romaji. */
    public static boolean marksALongVowel(String s) { return s != null && s.matches("(?s).*[āēīōūĀĒĪŌŪ].*"); }

    /** A form in kana as it is compared: katakana as hiragana, no spaces or dots, the long-vowel mark kept. */
    public static String kanaKey(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC);
        StringBuilder b = new StringBuilder();
        n.codePoints().forEach(c -> { if (c >= 0x30a1 && c <= 0x30f6) b.appendCodePoint(c - 0x60); else if (!Character.isWhitespace(c) && c != 0x30fb && c != '・' && c != '=' && c != '＝') b.appendCodePoint(c); });
        return b.toString();
    }

    /** A form in characters as it is compared: modern characters, no spaces. */
    public static String hanKey(String s) { return KanjiForms.modern(Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC)).replaceAll("[\\s　・]+", ""); }

    /**
     * The key by which a form meets a form in another script: a Latin form's {@link #latinKey}, and a kana form's romaji read the same way.
     * Null for a form in characters, which meets another script only through a reading a source gave.
     */
    public static String crossKey(String s) {
        String sc = script(s);
        if (sc.equals("latin")) return latinKey(s);
        if (sc.equals("kana")) return latinKey(hepburn(s));
        return null;
    }

    /**
     * Whether two forms are forms of one name, by what they are and nothing else: two forms in characters equal in modern characters, two
     * in kana equal as readings, two in Latin letters equal but for accents and order, and kana against Latin letters through the kana's
     * romaji. The ways romaji write a long vowel count only for a form that marks one ({@link #sameForm(String, String, boolean)}). A form in
     * characters against one in another script: never, without a reading ({@link #sameForm(String, String, Collection)}).
     */
    public static boolean sameForm(String a, String b) { return sameForm(a, b, false); }

    /**
     * The same, for two forms of a name known to be Japanese ({@code romaji}: it has a form in characters or kana, or a reading): two forms in
     * Latin letters are then romaji, and meet across the ways a long vowel is written (Endo, Endou, Endoh). Without that, two forms in Latin
     * letters meet only when they are equal but for accents, case and order, or when one marks a long vowel as romaji does (Endō). Forms in
     * any other alphabet (Cyrillic, Greek, Hangul) are compared the same way as Latin letters without romaji; two scripts never meet.
     */
    public static boolean sameForm(String a, String b, boolean romaji) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        String sa = script(a), sb = script(b);
        if (sa.equals("han") || sb.equals("han")) return sa.equals(sb) && hanKey(a).equals(hanKey(b));
        if (sa.equals("kana") && sb.equals("kana")) return kanaKey(a).equals(kanaKey(b));
        if (sa.equals("kana") || sb.equals("kana")) {
            String ka = crossKey(a), kb = crossKey(b);
            return ka != null && !ka.isEmpty() && ka.equals(kb);
        }
        if (sa.equals("latin") && sb.equals("latin")) {
            String pa = plainKey(a);
            if (!pa.isEmpty() && pa.equals(plainKey(b))) return true;
            if (!romaji && !marksALongVowel(a) && !marksALongVowel(b)) return false;
            String ka = latinKey(a);
            return !ka.isEmpty() && ka.equals(latinKey(b));
        }
        if (sa.isEmpty() && sb.isEmpty() && a.codePoints().anyMatch(Character::isLetter) && b.codePoints().anyMatch(Character::isLetter)) {
            String pa = plainKey(a);
            return !pa.isEmpty() && pa.equals(plainKey(b)) && sameAlphabet(a, b);
        }
        return false;
    }

    /** Whether two forms in letters that are neither Latin nor Japanese are written in one alphabet: Хейл is no form of Χαλε. */
    private static boolean sameAlphabet(String a, String b) { return alphabet(a) == alphabet(b); }

    private static Character.UnicodeScript alphabet(String s) {
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            Character.UnicodeScript u = Character.UnicodeScript.of(c);
            if (u != Character.UnicodeScript.COMMON && u != Character.UnicodeScript.INHERITED) return u;
            i += Character.charCount(c);
        }
        return Character.UnicodeScript.COMMON;
    }

    /**
     * The same, with the readings a source gave for the form in characters ({@code readings}: kana or romaji): a form in characters meets a
     * form in another script through one of them, and only so. Two forms in characters, or two in other scripts, are compared as they are.
     */
    public static boolean sameForm(String a, String b, Collection<String> readings) {
        if (sameForm(a, b)) return true;
        if (readings == null || a == null || b == null) return false;
        boolean ha = script(a).equals("han"), hb = script(b).equals("han");
        if (ha == hb) return false;
        String other = ha ? b : a;
        // a reading a source gave for characters is the reading of a Japanese name: its romaji meet every spelling of a long vowel
        for (String r : readings) if (r != null && !r.isBlank() && !script(r).equals("han") && sameForm(r, other, true)) return true;
        return false;
    }

    // ── kana to romaji ─────────────────────────────────────────────────────────────────────────────────────────────────

    private static final Map<String, String> KANA = new HashMap<>();
    static {
        String[][] rows = {
                {"あ", "a"}, {"い", "i"}, {"う", "u"}, {"え", "e"}, {"お", "o"},
                {"か", "ka"}, {"き", "ki"}, {"く", "ku"}, {"け", "ke"}, {"こ", "ko"}, {"が", "ga"}, {"ぎ", "gi"}, {"ぐ", "gu"}, {"げ", "ge"}, {"ご", "go"},
                {"さ", "sa"}, {"し", "shi"}, {"す", "su"}, {"せ", "se"}, {"そ", "so"}, {"ざ", "za"}, {"じ", "ji"}, {"ず", "zu"}, {"ぜ", "ze"}, {"ぞ", "zo"},
                {"た", "ta"}, {"ち", "chi"}, {"つ", "tsu"}, {"て", "te"}, {"と", "to"}, {"だ", "da"}, {"ぢ", "ji"}, {"づ", "zu"}, {"で", "de"}, {"ど", "do"},
                {"な", "na"}, {"に", "ni"}, {"ぬ", "nu"}, {"ね", "ne"}, {"の", "no"},
                {"は", "ha"}, {"ひ", "hi"}, {"ふ", "fu"}, {"へ", "he"}, {"ほ", "ho"}, {"ば", "ba"}, {"び", "bi"}, {"ぶ", "bu"}, {"べ", "be"}, {"ぼ", "bo"},
                {"ぱ", "pa"}, {"ぴ", "pi"}, {"ぷ", "pu"}, {"ぺ", "pe"}, {"ぽ", "po"},
                {"ま", "ma"}, {"み", "mi"}, {"む", "mu"}, {"め", "me"}, {"も", "mo"},
                {"や", "ya"}, {"ゆ", "yu"}, {"よ", "yo"},
                {"ら", "ra"}, {"り", "ri"}, {"る", "ru"}, {"れ", "re"}, {"ろ", "ro"},
                {"わ", "wa"}, {"ゐ", "i"}, {"ゑ", "e"}, {"を", "o"}, {"ん", "n"}, {"ゔ", "vu"},
                {"ぁ", "a"}, {"ぃ", "i"}, {"ぅ", "u"}, {"ぇ", "e"}, {"ぉ", "o"},
                {"きゃ", "kya"}, {"きゅ", "kyu"}, {"きょ", "kyo"}, {"ぎゃ", "gya"}, {"ぎゅ", "gyu"}, {"ぎょ", "gyo"},
                {"しゃ", "sha"}, {"しゅ", "shu"}, {"しょ", "sho"}, {"じゃ", "ja"}, {"じゅ", "ju"}, {"じょ", "jo"},
                {"ちゃ", "cha"}, {"ちゅ", "chu"}, {"ちょ", "cho"}, {"ぢゃ", "ja"}, {"ぢゅ", "ju"}, {"ぢょ", "jo"},
                {"にゃ", "nya"}, {"にゅ", "nyu"}, {"にょ", "nyo"}, {"ひゃ", "hya"}, {"ひゅ", "hyu"}, {"ひょ", "hyo"},
                {"びゃ", "bya"}, {"びゅ", "byu"}, {"びょ", "byo"}, {"ぴゃ", "pya"}, {"ぴゅ", "pyu"}, {"ぴょ", "pyo"},
                {"みゃ", "mya"}, {"みゅ", "myu"}, {"みょ", "myo"}, {"りゃ", "rya"}, {"りゅ", "ryu"}, {"りょ", "ryo"},
                {"しぇ", "she"}, {"じぇ", "je"}, {"ちぇ", "che"}, {"ふぁ", "fa"}, {"ふぃ", "fi"}, {"ふぇ", "fe"}, {"ふぉ", "fo"}, {"てぃ", "ti"}, {"でぃ", "di"}};
        for (String[] r : rows) KANA.put(r[0], r[1]);
    }

    /**
     * Kana in Hepburn romaji, lower case, a long vowel with its macron: えんどう けんじ is "endō kenji", ゆうびん is "yūbin", おおさか
     * is "ōsaka". Words stay where the kana had a space. Anything that is not kana is left as it is.
     */
    public static String hepburn(String kana) {
        String k = kanaKey(Normalizer.normalize(kana == null ? "" : kana, Normalizer.Form.NFKC).replaceAll("[\\s　・]+", " ").strip().replace(' ', '\u0001')).replace('\u0001', ' ');
        StringBuilder out = new StringBuilder();
        boolean doubleNext = false;
        for (int i = 0; i < k.length(); ) {
            String two = i + 2 <= k.length() ? k.substring(i, i + 2) : null;
            String one = k.substring(i, i + 1);
            if (one.equals("っ")) { doubleNext = true; i++; continue; }
            if (one.equals("ー")) { macron(out); i++; continue; }
            String r = two != null && KANA.containsKey(two) ? KANA.get(two) : KANA.get(one);
            int used = two != null && KANA.containsKey(two) ? 2 : 1;
            if (r == null) { out.append(one); doubleNext = false; i++; continue; }
            // ん before a vowel or a y is written n' (けんいち is ken'ichi), so it is not read as the n of the syllable after it
            if (r.equals("n") && one.equals("ん")) {
                String next = romajiAt(k, i + 1);
                out.append(next != null && next.matches("[aeiouy].*") ? "n'" : "n");
                i++;
                continue;
            }
            // a small tsu doubles the consonant after it: まっちゃ is matcha
            if (doubleNext) { out.append(r.startsWith("ch") ? "t" : r.substring(0, 1)); doubleNext = false; }
            // a long vowel: おう, おお and うう are one long vowel; the vowel is written once, with its macron
            String last = out.length() == 0 ? "" : out.substring(out.length() - 1);
            if ((r.equals("u") && last.equals("o")) || (r.equals("o") && last.equals("o")) || (r.equals("u") && last.equals("u"))) { macron(out); i += used; continue; }
            out.append(r);
            i += used;
        }
        return out.toString();
    }

    /** The romaji of the kana at a place in a reading, or null for none there. */
    private static String romajiAt(String k, int i) {
        if (i >= k.length()) return null;
        String two = i + 2 <= k.length() ? k.substring(i, i + 2) : null;
        if (two != null && KANA.containsKey(two)) return KANA.get(two);
        return KANA.get(k.substring(i, i + 1));
    }

    private static void macron(StringBuilder out) {
        if (out.length() == 0) return;
        char c = out.charAt(out.length() - 1);
        String m = switch (c) { case 'a' -> "ā"; case 'i' -> "ī"; case 'u' -> "ū"; case 'e' -> "ē"; case 'o' -> "ō"; default -> null; };
        if (m != null) out.setCharAt(out.length() - 1, m.charAt(0));
    }

    /**
     * The romaji spellings of a kana reading, lower case: its Hepburn with macrons, then without them, then with the long vowels written ou
     * and uu, oh, and oo. えんどう けんじ gives endō kenji, endo kenji, endou kenji, endoh kenji, endoo kenji.
     */
    public static List<String> romaji(String kana) { return spellings(hepburn(kana)); }

    /** The spellings of one romaji form with macrons: as it is, without the macrons, and with each long vowel as ou/uu, oh/uh left out, and oo/uu. */
    public static List<String> spellings(String withMacrons) {
        String s = withMacrons == null ? "" : withMacrons.strip();
        return s.isEmpty() ? List.of() : japanese().spellings(s);
    }

    // ── romaji to kana ─────────────────────────────────────────────────────────

    /** Romaji → the plain hiragana of each syllable, from {@link #KANA} the other way round: ji is じ (not ぢ), zu is ず, i is い, o is お. */
    private static final Map<String, String> SYLLABLES = new HashMap<>();
    static {
        List<String> kana = new ArrayList<>(KANA.keySet());
        kana.sort(null);   // the plain kana of a syllable sort before the rarer one written the same: じ before ぢ, ず before づ
        for (String k : kana) if (!k.matches("[ぁぃぅぇぉゐゑをゔ]")) SYLLABLES.putIfAbsent(KANA.get(k), k);
        for (String k : kana) SYLLABLES.putIfAbsent(KANA.get(k), k);
    }

    /**
     * One word of romaji as hiragana: Kenkichi is けんきち, Endoh is えんどう, Shōichi is しょういち, Ken'ichi is けんいち, Hattori is はっとり. A long vowel
     * with a macron or an h after it is the vowel and う (the same vowel again for ā, ī and ē); a doubled consonant is っ; n before a consonant or at
     * the end, and m before b, m or p, is ん; an apostrophe or a hyphen parts ん from a vowel. Null when a part of the word is no romaji syllable
     * (Smith), so the caller asks nothing about it.
     */
    public static String hiragana(String romaji) {
        String s = Normalizer.normalize(romaji == null ? "" : romaji, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
        s = s.replace("ō", "ou").replace("ô", "ou").replace("ū", "uu").replace("û", "uu").replace("ā", "aa").replace("ī", "ii").replace("ē", "ee");
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        s = s.replaceAll("oh(?![aeiouy])", "ou").replaceAll("uh(?![aeiouy])", "uu");
        if (s.isEmpty() || !s.matches("[a-z'’‘ʼ`´-]+")) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if ("'’‘ʼ`´-".indexOf(c) >= 0) { i++; continue; }
            boolean last = i + 1 == s.length();
            if (c == 'n' && (last || "aeiouy".indexOf(s.charAt(i + 1)) < 0)) { out.append('ん'); i++; continue; }
            if (c == 'm' && !last && "bmp".indexOf(s.charAt(i + 1)) >= 0) { out.append('ん'); i++; continue; }
            if (!last && (c == s.charAt(i + 1) && "kstpgdbzjcfhrw".indexOf(c) >= 0 || s.startsWith("tch", i))) { out.append('っ'); i++; continue; }
            String syllable = null;
            for (int len = Math.min(3, s.length() - i); len >= 1 && syllable == null; len--) { String r = s.substring(i, i + len); if (!r.equals("n") && SYLLABLES.containsKey(r)) syllable = r; }
            if (syllable == null) return null;
            out.append(SYLLABLES.get(syllable));
            i += syllable.length();
        }
        return out.toString();
    }

    /** Each word with a capital first letter: "endō kenji" is "Endō Kenji". */
    public static String capitalised(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : (s == null ? "" : s).split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.appendCodePoint(Character.toUpperCase(w.codePointAt(0))).append(w.substring(Character.charCount(w.codePointAt(0))));
        }
        return b.toString();
    }

    /** The words of a Latin form in the other order: "Endō Kenji" is "Kenji Endō"; "Endo, Kenji" is "Kenji Endo". A form of one word, or of more than two, has no other order. */
    public static String otherOrder(String latin) {
        String s = latin == null ? "" : latin.strip();
        int comma = s.indexOf(',');
        if (comma > 0) return (s.substring(comma + 1).strip() + " " + s.substring(0, comma).strip()).strip();
        String[] w = s.split("\\s+");
        return w.length == 2 ? w[1] + " " + w[0] : null;
    }

    // ── old and new characters ────────────────────────────────────────────────────────────────────────────────────────

    /** Modern character → its older forms, found once by asking {@link KanjiForms#modern} of every character it could know. */
    private static volatile Map<Integer, List<Integer>> older;

    private static Map<Integer, List<Integer>> older() {
        Map<Integer, List<Integer>> o = older;
        if (o != null) return o;
        Map<Integer, List<Integer>> m = new HashMap<>();
        int[][] ranges = {{0x3400, 0x9fff}, {0xf900, 0xfaff}};
        for (int[] r : ranges) for (int c = r[0]; c <= r[1]; c++) {
            String one = new String(Character.toChars(c)), now = KanjiForms.modern(one);
            if (!now.equals(one) && now.codePointCount(0, now.length()) == 1) m.computeIfAbsent(now.codePointAt(0), k -> new ArrayList<>()).add(c);
        }
        older = m;
        return m;
    }

    /** The most forms in older characters {@link #olds} writes for one name. */
    private static final int OLDS = 8;

    /**
     * Every form in older characters, where characters of it have them (渡辺 → 渡邉, 渡邊), each older form of each character, up to
     * {@value #OLDS} forms; empty when no character has one.
     */
    public static List<String> olds(String name) {
        if (name == null || name.isEmpty()) return List.of();
        Map<Integer, List<Integer>> o = older();
        List<StringBuilder> out = new ArrayList<>(List.of(new StringBuilder()));
        boolean any = false;
        for (int c : name.codePoints().toArray()) {
            List<Integer> was = o.get(c);
            if (was == null) { for (StringBuilder b : out) b.appendCodePoint(c); continue; }
            any = true;
            List<StringBuilder> next = new ArrayList<>();
            for (StringBuilder b : out) for (int w : was) if (next.size() < OLDS) next.add(new StringBuilder(b).appendCodePoint(w));
            out = next;
        }
        if (!any) return List.of();
        List<String> forms = new ArrayList<>();
        for (StringBuilder b : out) forms.add(b.toString());
        return forms;
    }

    /** The form in older characters, where a character of it has one (高橋 → 髙橋); the same string when none has. The first older form of each is taken. */
    public static String old(String name) {
        if (name == null) return "";
        Map<Integer, List<Integer>> o = older();
        StringBuilder b = new StringBuilder();
        name.codePoints().forEach(c -> {
            List<Integer> was = o.get(c);
            b.appendCodePoint(was == null ? c : was.get(0));
        });
        return b.toString();
    }

    // ── every form of a name ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Every written form a search can use for one name: as written; in modern characters and in old ones; the kana reading a source gave;
     * the romaji spellings of that reading, family name first and given name first; and each Latin form the source gave, in its
     * spellings and both orders. A form in characters is never made from a romaji form. {@code forms}: the name's written forms, each as
     * the source wrote it.
     */
    public static List<String> forms(String written, List<String> given) {
        Set<String> out = new LinkedHashSet<>();
        List<String> all = new ArrayList<>();
        if (written != null && !written.isBlank()) all.add(written.strip());
        if (given != null) for (String g : given) if (g != null && !g.isBlank()) all.add(g.strip());
        for (String f : all) {
            out.add(f);
            String sc = script(f);
            if (sc.equals("han")) {
                String modern = KanjiForms.modern(f);
                out.add(modern);
                out.addAll(olds(modern));
            } else if (sc.equals("kana")) {
                for (String r : romaji(f)) { out.add(capitalised(r)); String other = otherOrder(capitalised(r)); if (other != null) out.add(other); }
            } else if (sc.equals("latin")) {
                String lower = f.toLowerCase(Locale.ROOT);
                boolean marked = !Normalizer.normalize(lower, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").equals(lower);
                List<String> spelt = marked ? spellings(f) : List.of(f);
                for (String s : spelt) { out.add(s); String other = otherOrder(s); if (other != null) out.add(other); }
            }
        }
        return new ArrayList<>(out);
    }

    /** Every form of a name ({@link #forms(String, List)}) from the name as the history reads it. */
    public static List<String> forms(FamilyNameHistory.Name n) {
        List<String> given = new ArrayList<>();
        for (FamilyNameHistory.Form f : n.forms()) given.add(f.text());
        return forms(n.written(), given);
    }
}
