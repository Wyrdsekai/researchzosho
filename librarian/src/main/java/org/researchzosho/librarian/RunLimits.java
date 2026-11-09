package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A run's own settings as a person says them in the chat (0.7.0): a time limit, a limit in model turns, where the run reads, whether
 * the paid search places may be used, and the model it runs on. The command line takes the same as flags and the protocol as fields;
 * the chat reads them from the person's words, in English and Japanese, the way {@link ReportOptions} reads a report's form, and puts
 * them on every run it files that turn, over what the model passed. The parse is a program's: a phrase that is not clearly a setting
 * is left as part of the question. What was read is said back under the reply, recorded on the job's arguments, and written in the
 * report's first lines with whether the run finished within its limits.
 *
 * <p>{@code sources} is "" when the person said nothing of it, else shelves, web or both (the last for "search the web too"); {@code
 * model} is the name of one of the library's model entries, or "" when none was named.
 */
public record RunLimits(int maxMinutes, int maxTurns, String sources, boolean noPaid, String model) {

    public static final RunLimits NONE = new RunLimits(0, 0, "", false, "");

    public RunLimits {
        maxMinutes = Math.max(0, maxMinutes); maxTurns = Math.max(0, maxTurns);
        sources = sources == null ? "" : sources; model = model == null ? "" : model;
    }

    public boolean isEmpty() { return maxMinutes == 0 && maxTurns == 0 && sources.isEmpty() && !noPaid && model.isEmpty(); }

    /** The settings into a run's arguments, by the protocol's names, over what the arguments held. */
    public void put(ObjectNode args) {
        if (maxMinutes > 0) args.put("max_minutes", maxMinutes);
        if (maxTurns > 0) args.put("max_turns", maxTurns);
        if (!sources.isEmpty()) args.put("sources", sources);
        if (noPaid) args.put("paid", false);
        if (!model.isEmpty()) args.put("model", model);
    }

    /** The settings a job's arguments carry, as a run's record reads them: both as the sources is nothing to say, and the model as the run's entry. */
    public static RunLimits of(JsonNode args) {
        String s = args.path("sources").asText("");
        String m = args.path("model_entry").asText(args.path("model").asText(""));
        return new RunLimits(args.path("max_minutes").asInt(0), args.path("max_turns").asInt(0), s.equals("shelves") || s.equals("web") ? s : "", !args.path("paid").asBoolean(true), m.equalsIgnoreCase(ModelEntries.DEFAULT) ? "" : m);
    }

    // ---- the person's own words ----

    /** The number words a person writes a limit with. */
    private static final Map<String, Integer> NUMBERS = Map.ofEntries(Map.entry("a", 1), Map.entry("an", 1), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
            Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("fifteen", 15),
            Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("forty", 40), Map.entry("forty-five", 45), Map.entry("fifty", 50), Map.entry("sixty", 60), Map.entry("ninety", 90), Map.entry("a hundred", 100), Map.entry("hundred", 100));
    private static final String NUMBER = "\\d+(?:\\.\\d+)?|a hundred|forty-five|an|a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|ninety|hundred";

    /** A span of time: "30 minutes", "2 hours", "half an hour", "an hour and a half", "ten min", "1.5 hrs", "two and a half hours", "a quarter of an hour". */
    private static final String SPAN = "(?:(?<n>" + NUMBER + ")\\s*(?<nhalf>and a half\\s+)?(?<u>minutes?|mins?|hours?|hrs?)(?<uhalf>\\s+and a half)?"
            + "|(?<half>half an hour|a half[- ]hour|half[- ]hour)|(?<hour>an hour(?<hourhalf> and a half)?)|(?<quarter>a quarter of an hour))";
    /** The words before a span that make it a limit, not a mention: "don't exceed", "within", "up to", "at most", "no more than", "spend … on this", "limit it to", "max". */
    private static final Pattern TIME_LEAD = Pattern.compile("(?iu)\\b(?:within|in under|up to|at most|at the most|no more than|not more than|no longer than|not longer than"
            + "|don'?t (?:exceed|go over|go beyond|take more than|spend more than|take longer than)|do not (?:exceed|go over|go beyond|take more than|spend more than|take longer than)|never (?:exceed|more than)"
            + "|limit(?:ed)? (?:it |this |the run |yourself )?to|cap(?:ped)? (?:it |this |the run )?at|keep (?:it |this )?(?:under|within|to)|stop after|finish (?:within|in under)|wrap (?:it )?up (?:within|in under)"
            + "|spend(?: up to| at most| no more than)?|take (?:up to|at most|no more than)|give (?:it|this|the run)(?: up to| at most)?|max|a limit of|a cap of|a time limit of|time[- ]box(?:ed)? (?:it |this )?to)"
            + "\\s+(?:about |around |roughly |only |just |some )?" + SPAN + "\\b");
    /** The words after a span that make it a limit: "30 minutes max", "an hour tops", "2 hours at most", "20 minutes or less", "a 30-minute limit". */
    private static final Pattern TIME_TRAIL = Pattern.compile("(?iu)\\b" + SPAN + "\\s*(?:,\\s*)?(?:max(?:imum)?|tops|at (?:the )?most|or less|at the outside|at the very most|(?:time )?limit|cap|ceiling|budget|time[- ]box)\\b");
    /** Japanese: 30分以内, ２時間まで, 1時間半以内, 最大30分, 30分を超えない, 上限1時間, 半時間以内. */
    private static final Pattern TIME_JA = Pattern.compile("(?:最大|上限|最長|長くても)\\s*(?<jlead>(?:\\d+\\s*時間\\s*(?:半|\\d+\\s*分)?|\\d+\\s*分|半時間))"
            + "|(?<jtrail>(?:\\d+\\s*時間\\s*(?:半|\\d+\\s*分)?|\\d+\\s*分|半時間))\\s*(?:以内|まで|以下|が上限|を?超えない|を?超えず|に抑え|に収め|で終わ|で切り上げ|で打ち切)");

    private static final Pattern TURNS_LEAD = Pattern.compile("(?iu)\\b(?:within|in|in at most|up to|at most|no more than|not more than|don'?t exceed|do not exceed|limit(?:ed)? (?:it |this )?to|max(?:imum)?(?: of)?|a maximum of|a limit of|a cap of|a ceiling of|a budget of)"
            + "\\s+(?:about |around |roughly |only |just )?(?<n>" + NUMBER + ")\\s+(?:model )?(?:turns?|steps?)\\b");
    private static final Pattern TURNS_TRAIL = Pattern.compile("(?iu)\\b(?<n>" + NUMBER + ")\\s+(?:model )?(?:turns?|steps?)\\s*(?:,\\s*)?(?:max(?:imum)?|tops|at (?:the )?most|or fewer|or less|limit|cap|ceiling|budget)\\b");
    private static final Pattern TURNS_JA = Pattern.compile("(?:最大|上限|最長)\\s*(?<jlead>\\d+)\\s*(?:ターン|ステップ|手)|(?<jtrail>\\d+)\\s*(?:ターン|ステップ|手)\\s*(?:以内|まで|以下|が上限|を?超えない|を?超えず)");

    /** Where a phrase that names a place ends, so that "the shelves only hold two books" and "no web page says" are not settings: the sentence's end, a mark, or the words a request goes on with. */
    private static final String END = "(?=\\s*(?:$|[,.;:!?。、)]|for (?:this|it|this one|this run)\\b|this time\\b|please\\b|and\\b|then\\b|but\\b|-|—|–))";
    private static final Pattern SHELVES = Pattern.compile("(?iu)\\b(?:from the shelves only|only from the shelves|from the shelves alone|the shelves only|shelves only|only the shelves|from my shelves only|what the shelves hold only|from the library'?s shelves only"
            + "|without the web|with no web|no web(?: search(?:es)?| access| at all)?|not the web|nothing from the web|don'?t (?:use|search|touch|go to|read) the web|do not (?:use|search|touch|go to|read) the web|without (?:searching|using|going to) the web|stay off the web|keep off the web|off the web|skip the web|web off|no internet|without the internet|don'?t (?:use|search) the internet|do not (?:use|search) the internet)" + END
            + "|棚(?:だけ|のみ)|棚から(?:だけ|のみ)|蔵書(?:だけ|のみ|から(?:だけ|のみ))|(?:ウェブ|ウエブ|Web|web|ネット|インターネット)(?:検索)?(?:は|を)?(?:使わず|使わない|使用せず|使用しない|なし|抜き|見ず|見ない|せず|しない|に頼らず|を除いて)");
    private static final Pattern WEB_TOO = Pattern.compile("(?iu)\\b(?:search the web (?:too|as well|also)|use the web (?:too|as well|also)|the web (?:too|as well)|look on the web (?:too|as well|also)|(?:on|from) the web (?:too|as well|also)|(?:search|look|check) online (?:too|as well|also)|web too|include the web|the web as well as the shelves|the shelves and the web|the web and the shelves)" + END
            + "|(?:ウェブ|ウエブ|Web|web|ネット|インターネット)(?:検索)?も(?:使って|使う|含めて|調べて|見て|検索して|当たって)?");
    private static final Pattern WEB_ONLY = Pattern.compile("(?iu)\\b(?:from the web only|only from the web|the web only|web only|only the web|from the web alone|web alone|without the shelves|not the shelves|skip the shelves|don'?t (?:use|read|search) the shelves|do not (?:use|read|search) the shelves)" + END
            + "|(?:ウェブ|ウエブ|Web|web|ネット|インターネット)(?:検索)?(?:だけ|のみ|から(?:だけ|のみ))|棚(?:は|を)?(?:使わず|使わない|なし|抜き|見ず|見ない|除いて)");
    private static final Pattern NO_PAID = Pattern.compile("(?iu)\\b(?:(?:use |with |using )?no paid (?:services?|search(?:es)?|search services?|search places?|indexes|index|places|tools|apis?|readers?)|without (?:any )?paid (?:services?|search(?:es)?|search services?|search places?|indexes|index|places|tools|apis?|readers?)"
            + "|(?:don'?t|do not|never) (?:use|touch|spend on|call) (?:any |the )?paid (?:services?|search(?:es)?|search services?|search places?|indexes|index|places|tools|apis?|readers?)"
            + "|(?:only |just )?(?:the )?free (?:services?|search(?:es)?|search services?|search places?|places|tools) only|only (?:the )?free (?:services?|search(?:es)?|search services?|search places?|places|tools)|free (?:services?|search(?:es)?|places|tools) only"
            + "|nothing paid|nothing that costs (?:money|anything)|without spending (?:anything|money|a cent|credits)|(?:don'?t|do not) spend (?:any )?(?:money|credits)|no spending|at no cost|without (?:any )?cost)\\b"
            + "|有料(?:の)?(?:サービス|検索|検索サービス|ツール|API)?(?:は|を)?(?:使わず|使わない|使用せず|使用しない|なし|抜き|除いて|使わないで)|無料(?:の)?(?:サービス|検索|ツール)?(?:だけ|のみ)|お金(?:を|は)?(?:かけず|かけない|使わず|使わない)|課金(?:せず|しない|なし)");

    /** What a person's own words set for the run they ask for this turn; the library's model entries name the model. */
    public static RunLimits fromWords(String words) { return fromWords(words, ModelEntries.all()); }

    /**
     * The same over these model entries by name: "don't exceed 30 minutes", "within half an hour", "take up to 2 hours", "30分以内",
     * "2時間まで" set the time; "in at most 20 turns", "20ターン以内" the turns; "from the shelves only", "without the web", "棚だけ",
     * "search the web too", "ウェブも" where the run reads; "use no paid services for this run", "有料サービスは使わない" the paid places
     * off; "with deepseek", "deepseekで", "on the fable model", or "use the big model for this" when one entry's tier stands above the
     * rest, the model. A mention that is not a limit ("why does an eclipse take 30 minutes", "the web of life") sets nothing.
     */
    public static RunLimits fromWords(String words, Map<String, ModelEntries.Entry> entries) {
        String w = Normalizer.normalize(words == null ? "" : words, Normalizer.Form.NFKC).replace('’', '\'');
        int minutes = minutes(w), turns = turns(w);
        String sources = SHELVES.matcher(w).find() ? "shelves" : WEB_ONLY.matcher(w).find() ? "web" : WEB_TOO.matcher(w).find() ? "both" : "";
        boolean noPaid = NO_PAID.matcher(w).find();
        String model = model(w, entries == null ? Map.of() : entries);
        return new RunLimits(minutes, turns, sources, noPaid, model);
    }

    /** A lead that is an instruction wherever it stands: "don't exceed", "limit it to", "keep it under", "give it", "a time limit of". */
    private static final Pattern IMPERATIVE = Pattern.compile("(?iu)^(?:don'?t|do not|never|limit|cap|keep|stop|finish|wrap|give|time[- ]box|a time limit|a limit|a cap)\\b");
    /** A word that makes the clause before a limit phrase a question, where "at most 7 minutes" is a mention: "why does an eclipse take at most 7 minutes". */
    private static final Pattern QUESTION = Pattern.compile("(?iu)\\b(?:why|how|what|when|where|who|whom|whose|which|whether|does|do|did|is|are|was|were|can|could|would|will|should|has|have|had|am)\\b");
    /** Where a clause starts: a mark, or and, but, then. */
    private static final Pattern CLAUSE = Pattern.compile("(?iu)[,;:.!?。、，！？\\n—–-]|\\b(?:and|but|then)\\b");
    /** A Japanese clause that asks something: 30分以内に何が起きるか is a mention; a bare か is not one, since a polite request ends in it (調べてくれますか). */
    private static final Pattern QUESTION_JA = Pattern.compile("[？?]|何|どう|なぜ|いつ|どこ|誰|どれ|どの|どちら|いくら|いくつ");

    /**
     * Whether a limit phrase found at {@code at} is a setting and not a mention: an instruction's lead is, anywhere; any other form is
     * when the clause it stands in holds no question word ("find out what the Saros cycle is, within half an hour" sets the time, "why
     * does a total solar eclipse take at most 7 minutes" sets nothing).
     */
    private static boolean setting(String w, int at, int end, String lead) {
        if (lead != null && IMPERATIVE.matcher(lead.strip()).find()) return true;
        int from = 0;
        Matcher c = CLAUSE.matcher(w.substring(0, at));
        while (c.find()) from = c.end();
        String before = w.substring(from, at);
        if (QUESTION.matcher(before).find()) return false;
        // the sentence goes on to a question mark: "how long is it, 30 minutes at most?" asks, it does not set
        Matcher q = Pattern.compile("[.!?。！？\\n]").matcher(w);
        if (q.find(end) && (q.group().equals("?") || q.group().equals("？"))) return false;
        // the Japanese clause, whole: up to the next mark after the phrase
        int to = w.length();
        Matcher n = Pattern.compile("[、，,。！？!?\\n]").matcher(w);
        if (n.find(end)) to = n.start();
        String clause = w.substring(from, to);
        return !(clause.matches("(?s).*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*") && QUESTION_JA.matcher(clause).find());
    }

    private static int first(Pattern p, String w, ToIntFunction<Matcher> value) {
        Matcher m = p.matcher(w);
        while (m.find()) {
            String lead = m.groupCount() > 0 && leadOf(m) != null ? leadOf(m) : null;
            if (setting(w, m.start(), m.end(), lead)) return value.applyAsInt(m);
        }
        return 0;
    }

    /** The lead words of a match: what stands before its number or span, for the instruction test; null for a form with none. */
    private static String leadOf(Matcher m) {
        try { String n = m.group("n"); if (n != null) return m.group().substring(0, m.group().toLowerCase(Locale.ROOT).indexOf(n.toLowerCase(Locale.ROOT))); } catch (IllegalArgumentException ignored) { }
        for (String g : new String[]{"half", "hour", "quarter"}) { try { String v = m.group(g); if (v != null) return m.group().substring(0, m.group().indexOf(v)); } catch (IllegalArgumentException ignored) { } }
        return null;
    }

    /** The time limit the words set, in minutes; 0 for none. */
    static int minutes(String w) {
        int v = first(TIME_LEAD, w, RunLimits::span);
        if (v == 0) v = first(TIME_TRAIL, w, RunLimits::span);
        if (v == 0) v = first(TIME_JA, w, m -> spanJa(m.group("jlead") != null ? m.group("jlead") : m.group("jtrail")));
        return v;
    }

    private static int span(Matcher m) {
        if (m.group("half") != null) return 30;
        if (m.group("hour") != null) return m.group("hourhalf") != null ? 90 : 60;
        if (m.group("quarter") != null) return 15;
        double n = number(m.group("n"));
        boolean half = m.group("nhalf") != null || m.group("uhalf") != null;
        boolean hours = m.group("u").toLowerCase(Locale.ROOT).startsWith("h");
        double v = hours ? (n + (half ? 0.5 : 0)) * 60 : n + (half ? 0.5 : 0);
        return (int) Math.round(v);
    }

    private static int spanJa(String s) {
        if (s.startsWith("半時間")) return 30;
        Matcher h = Pattern.compile("(\\d+)\\s*時間\\s*(半|(\\d+)\\s*分)?").matcher(s);
        if (h.find()) return Integer.parseInt(h.group(1)) * 60 + (h.group(2) == null ? 0 : h.group(3) != null ? Integer.parseInt(h.group(3)) : 30);
        Matcher mm = Pattern.compile("(\\d+)\\s*分").matcher(s);
        return mm.find() ? Integer.parseInt(mm.group(1)) : 0;
    }

    private static double number(String s) {
        String t = s.toLowerCase(Locale.ROOT).strip();
        if (t.matches("\\d+(?:\\.\\d+)?")) return Double.parseDouble(t);
        return NUMBERS.getOrDefault(t, 0);
    }

    /** The turn limit the words set; 0 for none. */
    static int turns(String w) {
        int v = first(TURNS_LEAD, w, m -> (int) number(m.group("n")));
        if (v == 0) v = first(TURNS_TRAIL, w, m -> (int) number(m.group("n")));
        if (v == 0) v = first(TURNS_JA, w, m -> Integer.parseInt(m.group("jlead") != null ? m.group("jlead") : m.group("jtrail")));
        return v;
    }

    private static final Pattern BIG = Pattern.compile("(?iu)\\b(?:use|with|on|using|take|run (?:it |this )?on|run (?:it |this )?with)\\s+(?:the|your|our)\\s+(?:big|bigger|biggest|large|larger|largest|strong|stronger|strongest|best|most capable)\\s+model\\b|(?:大きい|大きな|一番(?:大きい|強い|良い)|最も(?:大きい|強い|良い)|最強の|最良の|より(?:大きい|強い))モデル(?:で|を使って|で調べて|を使い)");

    /**
     * The model entry the words name: "with deepseek", "on fable", "using the local-27b model", "use deepseek for this", "deepseekで",
     * "deepseekを使って"; "the big model" is the one entry whose tier stands above every other's, when there is one. The entry called
     * default, and auto, are named only with the word model beside them ("the default model", "the auto model", "モデル auto"), since the
     * bare words are ordinary ones. "" when none.
     */
    static String model(String w, Map<String, ModelEntries.Entry> entries) {
        if (entries.isEmpty()) return "";
        List<String> names = new ArrayList<>(entries.keySet());
        names.sort((a, b) -> b.length() - a.length());   // the longest first: local-27b before local
        for (String name : names) {
            String q = Pattern.quote(name);
            boolean plain = !name.equalsIgnoreCase(ModelEntries.DEFAULT) && !name.equalsIgnoreCase(Auto.NAME);
            String en = plain ? "(?iu)\\b(?:with|using|use|through|via|run (?:it |this )?(?:on|with)|try)\\s+(?:the\\s+)?(?:model\\s+)?" + q + "\\b(?:\\s+model)?|\\b(?:on|by)\\s+(?:the\\s+" + q + "\\s+model|model\\s+" + q + ")\\b"
                             : "(?iu)\\b(?:with|on|using|use|through|via|by)\\s+(?:the\\s+" + q + "\\s+model|model\\s+" + q + ")\\b";
            String ja = plain ? "(?iu)(?<![\\p{L}\\p{N}_-])" + q + "(?:\\s*モデル)?(?:で|を使って|を使い|に|にて)(?:調べ|研究|お願い|やって|進め|頼|\\s|$|[、。,.])"
                             : "(?iu)(?:モデル\\s*" + q + "|" + q + "\\s*モデル)(?:で|を使って|を使い)";
            if (Pattern.compile(en).matcher(w).find() || Pattern.compile(ja).matcher(w).find()) return name;
        }
        if (!BIG.matcher(w).find()) return "";
        String best = null; int ties = 0;
        for (ModelEntries.Entry e : entries.values()) {
            String t = e.tier();
            if (best == null || t.compareTo(entries.get(best).tier()) < 0) { best = e.name(); ties = 1; }
            else if (t.equals(entries.get(best).tier())) ties++;
        }
        return best != null && ties == 1 ? best : "";
    }

    // ---- said back, and recorded ----

    /** The settings as a person reads them, in the library's words for the chat: "up to 30 minutes, from the shelves only". */
    public String described() { return described(Chrome.now()); }

    public String described(Chrome c) {
        List<String> p = new ArrayList<>();
        if (maxMinutes > 0) p.add(maxMinutes % 60 == 0 ? (maxMinutes == 60 ? c.t("up to 1 hour") : c.t("up to {0} hours", maxMinutes / 60)) : c.t("up to {0} minutes", maxMinutes));
        if (maxTurns > 0) p.add(c.t("up to {0} turns", maxTurns));
        switch (sources) { case "shelves" -> p.add(c.t("from the shelves only")); case "web" -> p.add(c.t("from the web only")); case "both" -> p.add(c.t("the web too")); default -> { } }
        if (noPaid) p.add(c.t("no paid search services"));
        if (!model.isEmpty()) p.add(c.t("on the model {0}", model));
        return String.join(c.ja() ? "、" : ", ", p);
    }

    /**
     * The line in a report's first lines for a run that had limits: which, and whether the run finished within them ({@code cut}: a
     * ceiling cut the run before the work was done). "" for a run with none. The model is in the line that says who researched it.
     */
    public static String reportSentence(Researcher.Ask ask, boolean paid, boolean cut) {
        RunLimits l = new RunLimits(ask.maxMinutes(), ask.maxTurns(), ask.sources().equals("shelves") || ask.sources().equals("web") ? ask.sources() : "", !paid, "");
        if (l.isEmpty()) return "";
        boolean ceiling = l.maxMinutes > 0 || l.maxTurns > 0;
        return "Limits asked for: " + l.described(Chrome.ENGLISH) + ". " + (!ceiling ? "" : cut ? "The run reached its limit before the work was done: the sections written before it stand, and what it did not get to is on the open questions." : "The run finished within them.");
    }
}
