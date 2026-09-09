package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language lanes. A question about a place is answered by the sources of that place, and a model left to
 * itself searches in the language it was mostly trained on (measured, I-0017: a question about a shoot in
 * Tokyo with a Japanese crew, 200-odd citations, not one Japanese page — after five nudges to search in
 * Japanese). So the lane is structural: when the question names a place or culture whose language is not
 * the question's own, the plan gets one sub-question per language, the worker on it is told its lane and
 * given seed queries in that language, a search it writes in the wrong language comes back with a note
 * saying so, the critic sends the lane round again when the first round read nothing in that language,
 * and the write-up says which languages its sources were in.
 */
public final class Lanes {

    private static final ObjectMapper M = new ObjectMapper();

    private Lanes() { }

    /** A language to search in: its code, its name, the script its text shows (or latin), and seed queries. */
    public record Lane(String code, String name, String script, List<String> seeds) {
        /** Whether a query is written in this lane's language, as far as its script tells (Latin-script languages cannot be told apart). */
        public boolean inLanguage(String text) {
            if (text == null || text.isBlank()) return false;
            String s = Lanes.script(text);
            return script.equals("latin") ? true : s.equals(script);
        }
        public String subQuestion(String question) {
            return "In " + name + "-language sources: " + Acquisitions.compress(question, 160) + " — what do sources written in " + name + " say? Search in " + name + ".";
        }
        public String register() {
            StringBuilder sb = new StringBuilder("YOUR LANE: sources written in ").append(name).append(". Write EVERY web_search query in ").append(name)
                    .append(" — a query in another language brings back the other language's sources, which other workers already cover. ");
            if (!seeds.isEmpty()) sb.append("Seed queries you may use as they are: ").append(String.join(" · ", seeds)).append(". ");
            sb.append("The local press often writes a foreign title or name in its original spelling or its own transliteration: when a query finds nothing, "
                    + "try the other form (TOKYO VICE and 東京ヴァイス are the same show). ")
              .append("Note the ").append(name).append(" text as the quote and add a short English gloss after it. Cite the ").append(name).append(" pages themselves.\n\n");
            return sb.toString();
        }
        public String wrongLanguageNote() {
            return "\n\nLANE: that query was not in " + name + ", so these are not " + name + "-language sources — other workers cover those. "
                    + "Write the next query in " + name + (seeds.isEmpty() ? "." : ", for example: " + seeds.get(0) + (seeds.size() > 1 ? " · " + seeds.get(1) : "") + ".");
        }
    }

    /** Places and cultures, and the language their sources are written in. Lowercase words of the question are matched whole. */
    static final Map<String, String[]> PLACES = new LinkedHashMap<>();
    static {
        put("ja Japanese cjk", "japan", "japanese", "tokyo", "osaka", "kyoto", "okinawa", "hokkaido", "nagoya", "fukuoka", "yokohama", "nihon", "nippon", "日本");
        put("zh Chinese cjk", "china", "chinese", "beijing", "shanghai", "guangzhou", "shenzhen", "taiwan", "taipei", "hong", "mandarin", "cantonese", "中国");
        put("ko Korean hangul", "korea", "korean", "seoul", "busan", "한국");
        put("ru Russian cyrillic", "russia", "russian", "moscow", "petersburg", "soviet");
        put("ar Arabic arabic", "arabic", "egypt", "cairo", "saudi", "riyadh", "iraq", "baghdad", "syria", "damascus", "lebanon", "beirut", "morocco", "algeria", "tunisia", "jordan", "gulf", "emirates", "dubai", "qatar");
        put("fr French latin", "france", "french", "paris", "lyon", "marseille", "quebec", "belgium", "brussels", "senegal", "francophone");
        put("de German latin", "germany", "german", "berlin", "munich", "hamburg", "frankfurt", "austria", "vienna", "zurich", "swiss");
        put("es Spanish latin", "spain", "spanish", "madrid", "barcelona", "mexico", "argentina", "buenos", "chile", "colombia", "peru", "cuba", "venezuela", "latin");
        put("it Italian latin", "italy", "italian", "rome", "milan", "naples", "venice", "florence", "sicily");
        put("pt Portuguese latin", "portugal", "portuguese", "lisbon", "brazil", "brazilian", "rio", "sao", "paulo");
        put("nl Dutch latin", "netherlands", "dutch", "amsterdam", "holland", "flemish");
        put("sv Swedish latin", "sweden", "swedish", "stockholm");
        put("pl Polish latin", "poland", "polish", "warsaw", "krakow");
        put("tr Turkish latin", "turkey", "turkish", "istanbul", "ankara");
        put("el Greek greek", "greece", "greek", "athens");
        put("he Hebrew hebrew", "israel", "hebrew", "jerusalem", "tel");
        put("fa Persian arabic", "iran", "persian", "tehran", "farsi");
        put("hi Hindi devanagari", "india", "hindi", "delhi", "mumbai", "bollywood");
        put("th Thai thai", "thailand", "thai", "bangkok");
        put("vi Vietnamese latin", "vietnam", "vietnamese", "hanoi", "saigon");
        put("id Indonesian latin", "indonesia", "indonesian", "jakarta", "bali");
        put("en English latin", "america", "american", "usa", "hollywood", "england", "english", "london", "britain", "british", "australia", "canada", "アメリカ", "英語", "ハリウッド");
    }
    private static void put(String spec, String... words) { String[] p = spec.split(" "); for (String w : words) PLACES.put(w, new String[]{p[0], p[1], p[2]}); }

    /** The lanes a question calls for, from the table — deterministic; the judge only writes the seed queries. At most two. */
    public static List<Lane> detect(String question, Researcher.Drive judge) {
        List<Lane> out = new ArrayList<>();
        if (question == null || question.isBlank()) return out;
        String own = ownLanguage(question);
        Map<String, String[]> found = new LinkedHashMap<>();
        for (String w : question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            String[] l = PLACES.get(w);
            if (l != null && !l[0].equals(own)) found.putIfAbsent(l[0], l);
        }
        // CJK words in a question do not split on spaces: look for the few place words that are written that way
        for (var e : PLACES.entrySet()) if (script(e.getKey()).equals("cjk") && question.contains(e.getKey()) && !e.getValue()[0].equals(own)) found.putIfAbsent(e.getValue()[0], e.getValue());
        for (String[] l : found.values()) {
            if (out.size() == 2) break;
            out.add(new Lane(l[0], l[1], l[2], judge == null ? List.of() : seeds(question, l[1], l[2], judge)));
        }
        return out;
    }

    /** Six search queries in the lane's language, from the judge; checked by script where the script tells. */
    static List<String> seeds(String question, String name, String script, Researcher.Drive judge) {
        List<String> out = new ArrayList<>();
        try {
            ArrayNode msgs = M.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "Write 6 web search queries IN " + name + " (not in English) that a native speaker would type to find " + name
                    + "-language press, interviews and records answering this question. Short, concrete, the names as they are written in "
                    + name + ". Answer with a JSON array of strings and nothing else.\n\nQUESTION:\n" + question);
            String raw = judge.classify(msgs, 400);
            int a = raw.indexOf('['), b = raw.lastIndexOf(']');
            if (a >= 0 && b > a) for (JsonNode q : M.readTree(raw.substring(a, b + 1))) {
                String s = q.asText("").strip();
                if (s.isEmpty() || out.size() >= 6) continue;
                if (!script.equals("latin") && !script(s).equals(script)) continue;   // a seed in the wrong script is no seed
                out.add(s);
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---- what language a text or a source is in ----

    public static String script(String text) {
        if (text == null) return "latin";
        if (text.codePoints().anyMatch(c -> (c >= 0x3040 && c <= 0x30ff) || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf))) return "cjk";
        if (text.codePoints().anyMatch(c -> c >= 0xac00 && c <= 0xd7af)) return "hangul";
        if (text.codePoints().anyMatch(c -> c >= 0x0400 && c <= 0x04ff)) return "cyrillic";
        if (text.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06ff)) return "arabic";
        if (text.codePoints().anyMatch(c -> c >= 0x0370 && c <= 0x03ff)) return "greek";
        if (text.codePoints().anyMatch(c -> c >= 0x0590 && c <= 0x05ff)) return "hebrew";
        if (text.codePoints().anyMatch(c -> c >= 0x0900 && c <= 0x097f)) return "devanagari";
        if (text.codePoints().anyMatch(c -> c >= 0x0e00 && c <= 0x0e7f)) return "thai";
        return "latin";
    }

    static boolean hasKana(String text) { return text != null && text.codePoints().anyMatch(c -> c >= 0x3040 && c <= 0x30ff); }

    /** The language the question itself is written in, by script: the lane is never the question's own language. */
    static String ownLanguage(String question) {
        return switch (script(question)) {
            case "cjk" -> hasKana(question) ? "ja" : "zh";
            case "hangul" -> "ko"; case "cyrillic" -> "ru"; case "arabic" -> "ar"; case "greek" -> "el"; case "hebrew" -> "he"; case "devanagari" -> "hi"; case "thai" -> "th";
            default -> "en";
        };
    }

    static String scriptOf(String code) {
        return switch (code) { case "ja", "zh" -> "cjk"; case "ko" -> "hangul"; case "ru", "uk", "bg", "sr" -> "cyrillic"; case "ar", "fa", "ur" -> "arabic";
            case "el" -> "greek"; case "he" -> "hebrew"; case "hi", "mr", "ne" -> "devanagari"; case "th" -> "thai"; default -> "latin"; };
    }

    static String languageName(String code) {
        for (String[] l : PLACES.values()) if (l[0].equals(code)) return l[1];
        return code;
    }

    static final Map<String, String> TLD = Map.ofEntries(Map.entry("jp", "ja"), Map.entry("cn", "zh"), Map.entry("tw", "zh"), Map.entry("hk", "zh"), Map.entry("kr", "ko"),
            Map.entry("ru", "ru"), Map.entry("fr", "fr"), Map.entry("de", "de"), Map.entry("at", "de"), Map.entry("es", "es"), Map.entry("mx", "es"), Map.entry("ar", "es"),
            Map.entry("it", "it"), Map.entry("pt", "pt"), Map.entry("br", "pt"), Map.entry("nl", "nl"), Map.entry("se", "sv"), Map.entry("pl", "pl"), Map.entry("tr", "tr"),
            Map.entry("gr", "el"), Map.entry("il", "he"), Map.entry("ir", "fa"), Map.entry("in", "hi"), Map.entry("th", "th"), Map.entry("vn", "vi"), Map.entry("id", "id"));

    /** The language a noted source is in: the quote's script when it has one, the host's country otherwise, English when nothing says. */
    public static String languageOf(String locator, String quote) {
        String s = script(quote);
        if (!s.equals("latin")) {
            return switch (s) { case "cjk" -> hasKana(quote) ? "ja" : "zh"; case "hangul" -> "ko"; case "cyrillic" -> "ru"; case "arabic" -> "ar"; case "greek" -> "el";
                case "hebrew" -> "he"; case "devanagari" -> "hi"; case "thai" -> "th"; default -> "en"; };
        }
        String host = null;
        try { host = locator == null || !locator.contains("://") ? null : java.net.URI.create(locator.strip()).getHost(); } catch (Exception ignored) { }
        if (host != null) {
            String h = host.toLowerCase(Locale.ROOT);
            if (h.contains("/en/") || h.endsWith("/en")) return "en";
            int dot = h.lastIndexOf('.');
            String tld = dot < 0 ? "" : h.substring(dot + 1);
            if (TLD.containsKey(tld) && !locator.contains("/en/") && !locator.contains("/english")) return TLD.get(tld);
        }
        return "en";
    }

    private static final Pattern NOTE = Pattern.compile("^- (.+?) — source: (.+?)(?: — quote: \"(.*)\")?$", Pattern.MULTILINE);

    /** How many notes came from sources in each language, from the evidence text the workers wrote. */
    public static Map<String, Integer> languagesRead(String evidence) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (evidence == null) return out;
        Matcher m = NOTE.matcher(evidence);
        while (m.find()) out.merge(languageOf(m.group(2), m.group(3) == null ? "" : m.group(3)), 1, Integer::sum);
        return out;
    }

    /** "English 40 · Japanese 6", for the write-up and the log. */
    public static String describe(Map<String, Integer> read) {
        StringBuilder sb = new StringBuilder();
        for (var e : read.entrySet()) { if (sb.length() > 0) sb.append(" · "); sb.append(languageName(e.getKey())).append(' ').append(e.getValue()); }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
