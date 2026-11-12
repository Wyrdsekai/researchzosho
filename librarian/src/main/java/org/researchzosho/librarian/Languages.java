package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The language a text is written in, worked out from the text itself, with no model, no network and nothing outside the program.
 *
 * <p>The script decides where it is enough: kana with Han is Japanese, Han alone Chinese, Hangul Korean, and Hebrew, Thai, Greek,
 * Armenian, Georgian and the scripts of India are each one language here. Among the languages that share a script the words decide: the
 * function words each language uses most (articles, prepositions, conjunctions, pronouns, the verb to be), and the letters only it has
 * (ß, ñ, ł, ř, ő, ğ, ơ; і and ї in Ukrainian, ђ and ћ in Serbian, پ and گ in Persian). Each word scores for the languages that list it,
 * shared words less than a language's own; each letter likewise. The answer carries the runner-up and the margin between them, and is
 * {@link Guess#clear() clear} only when the margin and the words behind it are enough (measured: docs/DESIGN_TRANSLATION.md, part 5), so a
 * caller can say what it took the language to be when it is not sure, and refuse nothing on a guess.
 */
final class Languages {
    private Languages() { }

    /** What the text says of its language: the code, the runner-up ("" when there is none), the margin (0 to 1) between their scores, the words that scored, and how it was decided. */
    record Guess(String code, String second, double margin, int evidence, String by) {
        static final String SCRIPT = "script", WORDS = "words", FILE = "file", PERSON = "person", NONE = "none";
        /** Clear enough to act on alone, such as refusing a translation into the language the text is already in. */
        boolean clear() {
            return by.equals(SCRIPT) || by.equals(PERSON) || (by.equals(WORDS) && margin >= CLEAR_MARGIN && evidence >= CLEAR_WORDS) || (by.equals(FILE) && margin > 0);
        }
        Guess by(String how) { return new Guess(code, second, margin, evidence, how); }
    }

    /** The margin, and the function words scored, a guess by the words needs to be clear. */
    static final double CLEAR_MARGIN = 0.25;
    static final int CLEAR_WORDS = 4;

    /** The most of a long text read: its start, its middle and its end, so a long book costs no more than a chapter. */
    static final int SAMPLE = 60_000;

    // ---- the profiles: the function words and the letters of each language that shares a script ----

    static final Map<String, Set<String>> LATIN_WORDS = new LinkedHashMap<>();
    static final Map<String, String> LATIN_LETTERS = new LinkedHashMap<>();
    static final Map<String, Set<String>> CYRILLIC_WORDS = new LinkedHashMap<>();
    static final Map<String, String> CYRILLIC_LETTERS = new LinkedHashMap<>();
    static {
        latin("en", "the of and to in is that it for was on are with as by this be at from have or which not but an they has were their been will would there can its he she we you his her who also had than more", "");
        latin("de", "der die und in den von zu das mit sich des auf für ist im dem nicht ein eine als auch es an werden aus er hat dass sie nach wird bei einer um am sind noch wie einem über einen so zum war haben nur oder aber vor zur bis mehr durch man sein wurde", "äöüß");
        latin("fr", "de la le et les des en un du une que est pour qui dans par plus pas au sur ne se ce il sont avec son aux ou cette elle mais ont été comme nous vous leur être entre aussi", "éèêàçëîïôûùœ");
        latin("es", "de la que el en y los del se las por un para con no una su al es lo como más pero sus le ya fue este ha porque esta entre cuando muy sin sobre también hasta hay donde desde todo durante", "ñáéíóú¿¡");
        latin("pt", "de que do da em um para é com não uma os no se na por mais as dos como mas foi ao ele das tem à seu sua ou ser quando muito há nos já está também pelo pela até isso ela entre era depois sem", "ãõçáâêôàéíóú");
        latin("it", "di il la che in per un è del non le una della con si da al sono dei come alla più gli ma lo anche nel delle questo ha ed se nella sua suo essere dal tra degli loro cui quando molto era stato fu", "àèéìòù");
        latin("nl", "de van het een en in is dat op te zijn met voor niet die aan er maar om ook als dan bij nog wel worden door naar heeft werd hij ze wordt deze uit kan tot over meer geen zij hun haar dit wat", "");
        latin("pl", "i w na z się nie do to że jest o a jak od po przez dla ale co za tak czy ich jego jej być był była było może są tylko już oraz który która które tym tego także przy gdy jako", "ąćęłńóśźż");
        latin("sv", "och i att det som en på är av för med till den har de inte om ett var han men från så kan hon vid eller sig när efter under också mot då sin skulle utan bara alla vara detta dessa där blev hade finns", "åäö");
        latin("da", "og i at det er en til på af med for de som den har ikke et var han men om fra så kan sig hun efter også eller ved når over blev hvor havde skal være deres denne dette mod under kun selv man hvis mellem nogle hvad", "æøå");
        latin("no", "og i det er på en til som av for med at den har de ikke et var han men om fra så kan seg hun etter også eller ved når over ble hvor hadde skal være deres denne dette mot under bare selv man hvis mellom noen hva ikkje eit ein", "æøå");
        latin("fi", "ja on ei se että oli hän joka kun mutta myös tai sen ovat ole jo vain niin mukaan olla kanssa sekä jonka kuin sitä tämä nyt jos voi ollut olivat joiden hänen siitä jälkeen aikana vuonna välillä", "äö");
        latin("cs", "a se na v je to že s z do o ve k i jako pro by ale za jsou tak od po který která které jeho jejich také nebo byl byla bylo být jsem při než jen už aby mezi", "áčďéěíňóřšťúůýž");
        latin("ro", "de și în a la cu care pe din o un nu este se mai pentru ce să al fi ca sau prin au sunt după fost acest această despre între lui ei către foarte", "ăâîșțşţ");
        latin("hu", "a az és hogy nem is egy meg de van el ez csak már ki mint volt még azt fel ezt kell lehet pedig vagy után szerint között alatt nagy amely amelyek minden valamint illetve során", "áéíóöőúüű");
        latin("tr", "ve bir bu da de için ile olarak çok daha en gibi olan sonra kadar ama ya o ne değil var her ise olduğu tarafından ancak şu ki yıl yılında arasında üzerinde ilk iki", "çğıışöü");
        latin("id", "yang dan di ini itu dengan untuk dari dalam tidak ke pada akan adalah juga oleh ada atau sebagai saya telah karena bisa mereka kami kita sudah lebih tersebut dapat bahwa para setelah serta hingga sangat antara tahun menjadi", "");
        latin("hr", "i u je da se na za od su s kao koji koja koje bio bila iz ili ali kada još samo nakon između godine prema te što šta biti bilo bili sve ovaj ova ovo taj jer ga mu ih", "čćđšž");   // Croatian, and Serbian and Bosnian in Latin letters
        latin("vi", "và của là có các không những được trong cho một người với này đã để khi từ cũng như đến theo năm ra vào làm sẽ về nhiều nhưng tại hơn đó bị sau lại nên", "ăâđêôơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ");
        cyrillic("ru", "и в не на что с по он как это а к но из у за от о так же для его был она бы только уже мы были все или при их было также после который году", "ыэё");
        cyrillic("uk", "і в у на з що не до як та це за від по він для але його був вона також які який яка було або при вже ще після році між через", "іїєґ");
        cyrillic("bg", "и в на да се е не за от че с по са който които която това като но той тя бил беше към след при или също още години година", "ъ");
        cyrillic("sr", "и у је да се на за од са не што као из по али који која које био била то су до или када још само након између године", "ђћџљњј");
    }

    private static void latin(String code, String words, String letters) { LATIN_WORDS.put(code, Set.of(words.split(" "))); LATIN_LETTERS.put(code, letters); }
    private static void cyrillic(String code, String words, String letters) { CYRILLIC_WORDS.put(code, Set.of(words.split(" "))); CYRILLIC_LETTERS.put(code, letters); }

    /** The one language a script is written in here. */
    static final Map<Character.UnicodeScript, String> ONE = new EnumMap<>(Map.ofEntries(
            Map.entry(Character.UnicodeScript.HEBREW, "he"), Map.entry(Character.UnicodeScript.THAI, "th"), Map.entry(Character.UnicodeScript.GREEK, "el"),
            Map.entry(Character.UnicodeScript.ARMENIAN, "hy"), Map.entry(Character.UnicodeScript.GEORGIAN, "ka"), Map.entry(Character.UnicodeScript.DEVANAGARI, "hi"),
            Map.entry(Character.UnicodeScript.BENGALI, "bn"), Map.entry(Character.UnicodeScript.TAMIL, "ta"), Map.entry(Character.UnicodeScript.TELUGU, "te"),
            Map.entry(Character.UnicodeScript.GUJARATI, "gu"), Map.entry(Character.UnicodeScript.GURMUKHI, "pa"), Map.entry(Character.UnicodeScript.KANNADA, "kn"),
            Map.entry(Character.UnicodeScript.MALAYALAM, "ml"), Map.entry(Character.UnicodeScript.KHMER, "km"), Map.entry(Character.UnicodeScript.LAO, "lo"),
            Map.entry(Character.UnicodeScript.MYANMAR, "my"), Map.entry(Character.UnicodeScript.ETHIOPIC, "am"), Map.entry(Character.UnicodeScript.SINHALA, "si")));

    private static final Pattern WORD = Pattern.compile("\\p{L}+(?:['’]\\p{L}+)?");

    /** A long text's sample: its start, middle and end, {@link #SAMPLE} characters in all. */
    static String sample(String text) {
        if (text == null) return "";
        if (text.length() <= SAMPLE) return text;
        int third = SAMPLE / 3, mid = text.length() / 2;
        return text.substring(0, third) + "\n" + text.substring(mid - third / 2, mid + third / 2) + "\n" + text.substring(text.length() - third);
    }

    /** The language of a text from the text alone. */
    static Guess of(String text) {
        String t = sample(text);
        Map<Character.UnicodeScript, Integer> scripts = new EnumMap<>(Character.UnicodeScript.class);
        int letters = 0, kana = 0, han = 0, hangul = 0;
        for (int i = 0; i < t.length(); ) {
            int c = t.codePointAt(i); i += Character.charCount(c);
            if (!Character.isLetter(c)) continue;
            letters++;
            Character.UnicodeScript s = Character.UnicodeScript.of(c);
            if (s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA) kana++;
            else if (s == Character.UnicodeScript.HAN) han++;
            else if (s == Character.UnicodeScript.HANGUL) hangul++;
            else scripts.merge(s, 1, Integer::sum);
        }
        if (letters == 0) return new Guess("", "", 0, 0, Guess.NONE);
        // a fifth of the letters in Han, kana or Hangul makes the text theirs, as before: a Japanese paper full of English terms is Japanese.
        // Kana are 44% or more of the Han and kana of 1,000 characters of Japanese (16% in a paper's reference list), 1.6% at most of
        // Chinese (Japanese names quoted; 7.8% in a window of 200): past 8% the text is Japanese
        if ((kana + han) * 5 > letters) return new Guess(kana * 25 > (kana + han) * 2 ? "ja" : "zh", "", 1, kana + han, Guess.SCRIPT);
        if (hangul * 5 > letters) return new Guess("ko", "", 1, hangul, Guess.SCRIPT);
        Character.UnicodeScript most = null; int top = 0;
        for (var e : scripts.entrySet()) if (e.getValue() > top) { top = e.getValue(); most = e.getKey(); }
        if (most == null) return new Guess("", "", 0, 0, Guess.NONE);
        if (ONE.containsKey(most)) return new Guess(ONE.get(most), "", 1, top, Guess.SCRIPT);
        return switch (most) {
            case LATIN -> byWords(t, LATIN_WORDS, LATIN_LETTERS);
            case CYRILLIC -> byWords(t, CYRILLIC_WORDS, CYRILLIC_LETTERS);
            case ARABIC -> arabic(t);
            default -> new Guess("", "", 0, 0, Guess.NONE);   // a script the program has no language for
        };
    }

    /**
     * Arabic, Persian or Urdu, by the letters: only Urdu writes ٹ ڈ ڑ ں ے; Persian (and Urdu) write پ چ ژ گ, and ی and ک where Arabic writes
     * ي and ك; Arabic when none of theirs stands.
     */
    static Guess arabic(String t) {
        int fa = 0, ur = 0, arabicYehKaf = 0, persianYehKaf = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("پچژگ".indexOf(c) >= 0) fa++;
            if ("ٹڈڑںے".indexOf(c) >= 0) ur++;
            if (c == 'ی' || c == 'ک') persianYehKaf++;
            if (c == 'ي' || c == 'ك') arabicYehKaf++;
        }
        if (ur > 0 && ur * 2 >= fa) return new Guess("ur", "fa", 1, ur, Guess.SCRIPT);
        if (fa > 0 || persianYehKaf > arabicYehKaf) return new Guess("fa", "ar", 1, fa + persianYehKaf, Guess.SCRIPT);
        return new Guess("ar", "", 1, arabicYehKaf, Guess.SCRIPT);
    }

    /**
     * The language among those sharing a script, by its function words and its own letters: each word a language lists scores 1 divided
     * by how many of them list it, each of a language's own letters a quarter of that; the best two compared.
     */
    static Guess byWords(String t, Map<String, Set<String>> words, Map<String, String> letters) {
        Map<String, Integer> shared = new HashMap<>();
        for (Set<String> ws : words.values()) for (String w : ws) shared.merge(w, 1, Integer::sum);
        Map<Integer, Integer> letterShared = new HashMap<>();
        for (String ls : letters.values()) ls.codePoints().distinct().forEach(c -> letterShared.merge(c, 1, Integer::sum));
        Map<String, Double> score = new LinkedHashMap<>();
        for (String code : words.keySet()) score.put(code, 0.0);
        int hits = 0;
        Matcher m = WORD.matcher(t.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            Integer k = shared.get(w);
            if (k == null) continue;
            hits++;
            for (var e : words.entrySet()) if (e.getValue().contains(w)) score.merge(e.getKey(), 1.0 / k, Double::sum);
        }
        String lower = t.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); ) {
            int c = lower.codePointAt(i); i += Character.charCount(c);
            Integer k = letterShared.get(c);
            if (k == null) continue;
            for (var e : letters.entrySet()) if (e.getValue().indexOf(c) >= 0) score.merge(e.getKey(), 0.25 / k, Double::sum);
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(score.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        double best = ranked.get(0).getValue(), next = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
        if (best <= 0) return new Guess(ranked.get(0).getKey(), ranked.size() > 1 ? ranked.get(1).getKey() : "", 0, 0, Guess.WORDS);
        return new Guess(ranked.get(0).getKey(), ranked.size() > 1 ? ranked.get(1).getKey() : "", (best - next) / best, hits, Guess.WORDS);
    }

    /** A language as a file's own metadata names it (de-DE, en_US, ger, German): its two-letter code, or "" when it names none the program knows. */
    static String declared(String raw) {
        String s = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        String head = s.split("[-_ ,;]")[0];
        if (head.length() == 2 && head.matches("[a-z]{2}")) return head;
        String code = Translate.code(s);
        if (code.length() == 2) return code;
        return switch (head) { case "eng" -> "en"; case "ger", "deu" -> "de"; case "fre", "fra" -> "fr"; case "spa" -> "es"; case "por" -> "pt"; case "ita" -> "it"; case "dut", "nld" -> "nl"; case "pol" -> "pl"; case "swe" -> "sv"; case "dan" -> "da"; case "nor", "nob", "nno" -> "no"; case "fin" -> "fi"; case "cze", "ces" -> "cs"; case "rum", "ron" -> "ro"; case "hun" -> "hu"; case "tur" -> "tr"; case "ind" -> "id"; case "vie" -> "vi"; case "rus" -> "ru"; case "ukr" -> "uk"; case "bul" -> "bg"; case "srp" -> "sr"; case "jpn" -> "ja"; case "chi", "zho" -> "zh"; case "kor" -> "ko"; default -> ""; };
    }

    /**
     * The language of a document: the text's own answer, unless the file names one (an EPUB's dc:language, a Word file's, a PDF's Lang)
     * and the text agrees with it or is not clear enough to say otherwise; then the file's.
     */
    static Guess ofDocument(String text, String fileSays) {
        Guess g = of(text);
        String f = declared(fileSays);
        if (f.isEmpty()) return g;
        if (g.code().equals(f)) return g.clear() ? g : new Guess(f, g.second(), 1, g.evidence(), Guess.FILE);   // the text leans the file's way: the two together are clear
        if (g.clear()) return g;   // the text says plainly another language than the file: the file's tag is a template's
        return new Guess(f, g.code(), 0, g.evidence(), Guess.FILE);
    }

    /** The languages the program names, by code: the ten the translation offers by name and those it tells apart from a text. */
    static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        String[] all = {"en", "English", "de", "German", "fr", "French", "es", "Spanish", "pt", "Portuguese", "it", "Italian", "nl", "Dutch", "pl", "Polish", "sv", "Swedish", "da", "Danish",
                "no", "Norwegian", "fi", "Finnish", "cs", "Czech", "ro", "Romanian", "hu", "Hungarian", "tr", "Turkish", "id", "Indonesian", "vi", "Vietnamese", "ru", "Russian", "uk", "Ukrainian",
                "bg", "Bulgarian", "sr", "Serbian", "hr", "Croatian", "ja", "Japanese", "zh", "Chinese", "ko", "Korean", "ar", "Arabic", "fa", "Persian", "ur", "Urdu", "he", "Hebrew", "th", "Thai", "el", "Greek",
                "hy", "Armenian", "ka", "Georgian", "hi", "Hindi", "bn", "Bengali", "ta", "Tamil", "te", "Telugu", "gu", "Gujarati", "pa", "Punjabi", "kn", "Kannada", "ml", "Malayalam",
                "km", "Khmer", "lo", "Lao", "my", "Burmese", "am", "Amharic", "si", "Sinhala"};
        for (int i = 0; i < all.length; i += 2) NAMES.put(all[i], all[i + 1]);
    }

    /** The code of a language named in English or by its code ("Dutch", "nl"); "" for a name the program does not know. */
    static String codeOf(String name) {
        String t = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (NAMES.containsKey(t)) return t;
        for (var e : NAMES.entrySet()) if (e.getValue().toLowerCase(Locale.ROOT).equals(t)) return e.getKey();
        for (String lang : Chrome.available()) for (var e : NAMES.entrySet()) { String w = Chrome.in(lang).word(e.getValue()); if (w != null && w.toLowerCase(Locale.ROOT).equals(t)) return e.getKey(); }   // オランダ語, as the interface names it
        return switch (t) { case "nederlands", "flemish" -> "nl"; case "polski" -> "pl"; case "svenska" -> "sv"; case "dansk" -> "da"; case "norsk", "bokmål", "nynorsk" -> "no"; case "suomi" -> "fi"; case "čeština", "cestina" -> "cs"; case "română", "romana" -> "ro"; case "magyar" -> "hu"; case "türkçe", "turkce" -> "tr"; case "bahasa indonesia" -> "id"; case "tiếng việt" -> "vi"; case "українська" -> "uk"; case "български" -> "bg"; case "српски" -> "sr"; case "farsi" -> "fa"; default -> ""; };
    }

    /** The person's word: the language they say the document is written in, over anything the program worked out. */
    static Guess said(String language, Guess worked) {
        String c = Translate.code(language == null ? "" : language.strip());
        if (c.isEmpty()) return worked;
        return new Guess(c, worked == null || worked.code().equals(c) ? "" : worked.code(), 1, 0, Guess.PERSON);
    }
}
