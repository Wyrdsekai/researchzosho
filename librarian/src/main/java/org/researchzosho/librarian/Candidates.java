package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.VideoText;
import org.researchzosho.tools.WebSearchTool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The candidate pool for a video a person remembers but cannot name. The old pool was YouTube's own first twenty results for a query the
 * model wrote, which is the engine's ranking by popularity: four live runs chose well among a pool that never held the right video,
 * which sat at rank six for the plain Japanese name of the thing and dropped out as soon as any remembered feature joined the words
 * (2026-11-05). So the model names only the THING made or shown, in two to four words per language, and the program does the rest:
 * it ENUMERATES rather than ranks. Deep searches of its own plain phrasings (a hundred results each, not twenty); every upload of the
 * channels those surface, since a channel's list is complete and ranked by nothing; the YouTube Data API in date order over windows,
 * which never ranks by popularity, when a key is set; the other indexes' view of youtube.com; the pages where other people describe a
 * video in the words the person remembers, read for the videos they link ({@link Described}); and the person's own watch history
 * before all of these. Then it filters by what a machine can check (the dates, the length, the language of the title, the ids
 * already rejected) and orders by fit, with no popularity signal anywhere: no views, no subscribers, no likes, and no count of how
 * many sources found a video or how often a channel was seen, since what ranks everywhere is what is popular everywhere (the seventh
 * live run, 2026-11-05: the video sat at rank 3,026 under an order that counted sources). What the person remembers of the video
 * itself orders the pool where a machine can check it: a video whose own title or description, or the text beside its link on a page,
 * holds more of the remembered phrases comes before one that holds fewer. The sift then judges the pool's pictures,
 * and the model LOOKS at what survives; it never nominates from a ranked list.
 */
public final class Candidates {
    private Candidates() { }

    static final ObjectMapper M = new ObjectMapper();

    /** The Data API's name in the tally, its key's setting, and the setting for the most search pages one run makes. */
    public static final String TALLY = "YouTube Data API", KEY = "RESEARCHZOSHO_YOUTUBE_KEY", PAGES = "RESEARCHZOSHO_YOUTUBE_API_PAGES";
    /** A search page costs 100 of the day's 10,000 units; a videos.list call 1. Twenty pages a run unless the setting says otherwise; a hundred list calls (five thousand videos) a run. */
    public static final int PAGES_DEFAULT = 20, PAGE_UNITS = 100, LIST_UNITS = 1, QUOTA = 10_000, LIST_CALLS_MOST = 100;
    /** A deep search reads this many results per phrasing; this many candidates come back in one page. */
    public static final int DEEP = 100, SHOWN = 40;
    /** The Data API's address; a test points it at a server of its own. */
    public static volatile String apiBase = "https://www.googleapis.com/youtube/v3";
    /** The other indexes' search, through the web search's rungs; a test stands in for it. Null means no rung is set. */
    public interface Web { String search(String query) throws Exception; }
    public static volatile Web WEB = Candidates::webLive;
    /** How many results one question to the web search may give: every one SearXNG gathered, where an engine's first twenty are its ranking. */
    public static final int WEB_ROWS = 50;
    /** How many search pages one run may ask the Data API for. */
    public static int pagesPerRun() { return Math.max(0, Config.getInt(PAGES, PAGES_DEFAULT)); }
    /** The enumeration's own ceiling: twenty minutes, less in a run whose deadline must leave time for the sift and the looks that follow. */
    public static final long ENUMERATE_MS = 20 * 60_000L, KEEP_FOR_LOOKS_MS = 25 * 60_000L, KEEP_FOR_SIFT_MS = 20 * 60_000L;
    /** What the channel listings leave of the ceiling for the details that follow them, which take a minute. */
    static final long KEEP_FOR_DETAILS_MS = 2 * 60_000L;

    /**
     * The thing named in the other languages, by the model, once: a person who remembers a silent video seldom knows the language of its
     * title, and the four live runs' worker gave the thing's name in English only, whatever the words asked. The model names the thing,
     * and only the thing (never a search); the program makes the phrasings. A test hands in a stand-in; null means no model answers.
     */
    public interface Namer { List<Named> name(List<String> things) throws Exception; }
    public static volatile Namer NAMER = Candidates::nameLive;
    /** Where the model for the naming comes from: the configured drive; a test hands in its own. */
    public static volatile Supplier<Researcher.Drive> drives = Explain::drive;
    /** The languages YouTube is large in for videos of making and showing things, each with its code: the thing is named in each. */
    static final List<String[]> LANGUAGES = List.of(new String[]{"en", "English"}, new String[]{"ja", "Japanese"}, new String[]{"ko", "Korean"}, new String[]{"zh", "Chinese"},
            new String[]{"es", "Spanish"}, new String[]{"pt", "Portuguese"}, new String[]{"de", "German"}, new String[]{"fr", "French"}, new String[]{"ru", "Russian"});
    /** A name the thing is searched under: the name, its language, and whether the person gave it (the full table of phrasings) or the model named it (the plainest two). */
    public record Named(String name, String lang, boolean given) { }

    /**
     * What the model gives: the things; what the person remembers of the video itself, in short phrases of their own words (what happens,
     * what is seen or heard, who or what appears), which is data the program makes its own queries from and never a search; the dates, the
     * length, the ids rejected, the language of the title when the person said it, and the page of a pool already made.
     */
    public record Ask(List<String> things, List<String> remembered, String before, String after, int minutesMin, int minutesMax, Set<String> exclude, int page, String language) {
        public Ask(List<String> things, String before, String after, int minutesMin, int minutesMax, Set<String> exclude, int page, String language) { this(things, List.of(), before, after, minutesMin, minutesMax, exclude, page, language); }
        public static Ask of(JsonNode args) {
            List<String> things = new ArrayList<>();
            JsonNode t = args.path("things");
            if (t.isArray()) { for (JsonNode x : t) addThing(things, x.asText("")); }
            else for (String x : t.asText("").split("[,;|\\n]")) addThing(things, x);
            List<String> remembered = new ArrayList<>();
            JsonNode r = args.path("remembered");
            if (r.isArray()) { for (JsonNode x : r) addRemembered(remembered, x.asText("")); }
            else for (String x : r.asText("").split("[;|\\n]")) addRemembered(remembered, x);
            Set<String> exclude = new LinkedHashSet<>();
            JsonNode ex = args.path("exclude");
            if (ex.isArray()) { for (JsonNode x : ex) { String id = VideoText.videoId(x.asText("")); if (id != null) exclude.add(id); } }
            else for (String x : ex.asText("").split("[,;\\s]+")) { String id = VideoText.videoId(x); if (id != null) exclude.add(id); }
            return new Ask(things, remembered, args.path("before").asText("").strip(), args.path("after").asText("").strip(),
                    args.path("minutes_min").asInt(0), args.path("minutes_max").asInt(0), exclude, Math.max(1, args.path("page").asInt(1)), languageCode(args.path("language").asText("")));
        }
        static void addThing(List<String> things, String x) {
            String s = x == null ? "" : x.strip().replaceAll("\\s+", " ");
            if (s.isEmpty() || things.contains(s) || things.size() >= 6) return;
            things.add(s);
        }
        /** One remembered phrase: a line of the person's words, cut at 160 characters; no more than {@link Described#PHRASES} of them. */
        static void addRemembered(List<String> remembered, String x) {
            String s = x == null ? "" : x.strip().replaceAll("\\s+", " ");
            if (s.length() > 160) s = s.substring(0, 160).strip();
            if (s.isEmpty() || remembered.contains(s) || remembered.size() >= Described.PHRASES) return;
            remembered.add(s);
        }
        /** The key the kept pool is filed under: the things, what is remembered, the dates, the length and the language, never the page. */
        String key() {
            try {
                String s = String.join("|", things).toLowerCase(Locale.ROOT) + "|" + before + "|" + after + "|" + minutesMin + "|" + minutesMax + (language.isEmpty() ? "" : "|" + language) + (remembered.isEmpty() ? "" : "|" + String.join("|", remembered).toLowerCase(Locale.ROOT));
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 0, 8);
            } catch (Exception e) { return "pool"; }
        }
    }

    /** The code of a language the person names, by its code or its English name; "" when it is none the pool can filter by. */
    static String languageCode(String given) {
        String g = given == null ? "" : given.strip().toLowerCase(Locale.ROOT);
        if (g.isEmpty()) return "";
        for (String[] l : LANGUAGES) if (g.equals(l[0]) || g.equals(l[1].toLowerCase(Locale.ROOT))) return l[0];
        return switch (g) { case "jp", "日本語", "jpn" -> "ja"; case "kr", "한국어", "kor" -> "ko"; case "cn", "tw", "中文", "mandarin", "cantonese", "zh-hant", "zh-hans", "zh-tw", "zh-cn" -> "zh"; case "русский", "rus" -> "ru"; default -> ""; };
    }

    /** The scripts a language's titles are written in: Japanese is kana, or kanji alone, which reads as Chinese; the Latin-script languages share one script. */
    static Set<String> scriptsOf(String lang) {
        return switch (lang) { case "ja" -> Set.of("Japanese", "Chinese"); case "ko" -> Set.of("Korean"); case "zh" -> Set.of("Chinese"); case "ru" -> Set.of("Cyrillic"); case "" -> Set.of(); default -> Set.of("Latin"); };
    }

    /** One video in the pool, and which sources found it. */
    public static final class Hit {
        public final String id;
        /** The title as first read; {@code ownTitle} the uploader's own from the Data API when the tunnel's was a localisation of it. */
        public String title = "", ownTitle = "", channel = "", channelId = "", uploaded = "", watchedOn = "";
        public int seconds = -1;
        public final Set<String> sources = new LinkedHashSet<>();
        /** How many of the remembered phrases this video's own title or description, or the text beside its link on a page, holds; how many of their words; which phrases. */
        public int fit, fitWords;
        public final List<String> held = new ArrayList<>();
        /** The pages that led to it, for a video found through what other people wrote; its own description when it was read. */
        public final Set<String> pages = new LinkedHashSet<>();
        public String description = "";
        boolean described;
        /** What each page that led here holds, one reading a page: never added up across pages, since a video many pages link is a popular one. */
        final List<Described.Fit> byPage = new ArrayList<>();
        Hit(String id) { this.id = id; }
        /** A reading of how much of what is remembered a text about this video holds: the best reading stands. */
        void fits(Described.Fit f) {
            if (f == null || !(f.phrases() != fit ? f.phrases() > fit : f.words() > fitWords)) return;
            fit = f.phrases(); fitWords = f.words(); held.clear(); held.addAll(f.held());
        }
        public String link() { return "https://www.youtube.com/watch?v=" + id; }
        /** The script of the uploader's own title when it is known, else of the title as read. */
        public String script() { return scriptOf(ownTitle.isEmpty() ? title : ownTitle); }
        /** The title shown: the uploader's own, with the tunnel's reading after it when the two differ. */
        public String shownTitle() { return ownTitle.isEmpty() || ownTitle.equals(title) ? title : ownTitle + " (read as: " + title + ")"; }
        ObjectNode json() {
            ObjectNode o = M.createObjectNode().put("id", id).put("link", link()).put("title", title).put("own_title", ownTitle).put("channel", channel).put("channel_id", channelId).put("uploaded", uploaded).put("seconds", seconds).put("script", script()).put("watched_on", watchedOn);
            ArrayNode s = o.putArray("sources"); for (String x : sources) s.add(x);
            if (fit > 0 || fitWords > 0) { o.put("fit", fit).put("fit_words", fitWords); ArrayNode hs = o.putArray("held"); for (String x : held) hs.add(x); }
            if (!pages.isEmpty()) { ArrayNode ps = o.putArray("pages"); for (String x : pages) ps.add(x); }
            return o;
        }
        static Hit of(JsonNode j) {
            Hit h = new Hit(j.path("id").asText(""));
            h.title = j.path("title").asText(""); h.ownTitle = j.path("own_title").asText(""); h.channel = j.path("channel").asText(""); h.channelId = j.path("channel_id").asText(""); h.uploaded = j.path("uploaded").asText(""); h.seconds = j.path("seconds").asInt(-1); h.watchedOn = j.path("watched_on").asText("");
            for (JsonNode s : j.path("sources")) { h.sources.add(s.asText()); if (s.asText().startsWith(Described.SOURCE)) h.described = true; }
            h.fit = j.path("fit").asInt(0); h.fitWords = j.path("fit_words").asInt(0);
            for (JsonNode x : j.path("held")) h.held.add(x.asText());
            for (JsonNode x : j.path("pages")) h.pages.add(x.asText());
            return h;
        }
    }

    /** The names of the sources, as the lines and the counts say them. */
    public static final String SEARCH = "search", CHANNEL = "channel", API = "date-api", WEB_INDEX = "web", HISTORY = "history", DESCRIBED = "described";

    /**
     * What came back: the pool best fit first (whole), the counts per source, the sentences about what was skipped or dropped, the key the
     * pool is kept under (the sift takes it), whether the enumeration was cut short by the time, and the text for the model.
     */
    public record Result(List<Hit> pool, Map<String, Integer> bySource, List<String> notes, int excluded, int outsideDates, int outsideLength, int outsideLanguage, int page, String key, boolean cut, String text, ObjectNode described) {
        public Result(List<Hit> pool, Map<String, Integer> bySource, List<String> notes, int excluded, int outsideDates, int outsideLength, int outsideLanguage, int page, String key, boolean cut, String text) { this(pool, bySource, notes, excluded, outsideDates, outsideLength, outsideLanguage, page, key, cut, text, null); }
    }

    // ---- the phrasings, the program's own: a plain table per language, no feature word in any of them

    /** The phrasings a thing is searched in, by the script of its name; {} is the thing. The plainest first. */
    static final Map<String, List<String>> PHRASINGS = Map.ofEntries(
            Map.entry("ja", List.of("{} 作り方", "{}の作り方", "{}", "{} 作り方 簡単", "{} 手作り")),
            Map.entry("ko", List.of("{} 만들기", "{}", "{} 만드는 법", "{} 만드는 방법")),
            Map.entry("zh", List.of("{} 教程", "{}", "{} 制作", "{} 做法")),
            Map.entry("ru", List.of("{} своими руками", "{}", "как сделать {}", "{} мастер класс")),
            Map.entry("es", List.of("{} tutorial", "{}", "como hacer {}", "{} paso a paso")),
            Map.entry("pt", List.of("{} tutorial", "{}", "como fazer {}", "{} passo a passo")),
            Map.entry("de", List.of("{} anleitung", "{}", "{} selber machen", "{} tutorial")),
            Map.entry("fr", List.of("{} tutoriel", "{}", "comment faire {}", "{} diy")),
            Map.entry("en", List.of("{} tutorial", "{}", "how to make {}", "DIY {}")));

    /** The region and the language the Data API is asked with, by the language of the name. */
    static final Map<String, String[]> REGIONS = Map.ofEntries(Map.entry("ja", new String[]{"JP", "ja"}), Map.entry("ko", new String[]{"KR", "ko"}), Map.entry("zh", new String[]{"TW", "zh-Hant"}),
            Map.entry("ru", new String[]{"RU", "ru"}), Map.entry("es", new String[]{"ES", "es"}), Map.entry("pt", new String[]{"BR", "pt"}), Map.entry("de", new String[]{"DE", "de"}), Map.entry("fr", new String[]{"FR", "fr"}), Map.entry("en", new String[]{"US", "en"}));

    /** The language a thing's name is in, by its script: ja, ko, zh, ru, else en (Latin script tells nothing more). */
    static String languageOf(String thing) {
        if (thing.codePoints().anyMatch(c -> c >= 0x3040 && c <= 0x30ff)) return "ja";
        if (thing.codePoints().anyMatch(c -> c >= 0xac00 && c <= 0xd7af)) return "ko";
        if (thing.codePoints().anyMatch(c -> (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf))) return "zh";
        if (thing.codePoints().anyMatch(c -> c >= 0x0400 && c <= 0x04ff)) return "ru";
        return "en";
    }

    /** The phrasings for one thing, the plainest first, in the language its script gives away. */
    public static List<String> phrasings(String thing) { return phrasings(thing, languageOf(thing)); }

    /** The phrasings for one thing in a language, the plainest first. */
    public static List<String> phrasings(String thing, String lang) {
        List<String> out = new ArrayList<>();
        for (String p : PHRASINGS.getOrDefault(lang, PHRASINGS.get("en"))) out.add(p.replace("{}", thing));
        return out;
    }

    /** The names the thing is searched under: the given ones first, each in the language its script gives away, then the model's names in the other languages. */
    static List<Named> named(List<String> things, List<String> notes) {
        List<Named> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String t : things) if (seen.add(t.toLowerCase(Locale.ROOT))) out.add(new Named(t, languageOf(t), true));
        List<Named> more;
        try { more = NAMER == null ? null : NAMER.name(things); } catch (Exception e) { more = null; }
        if (more == null) { notes.add("No model answered to name the thing in other languages, so it was searched under the names given alone."); return out; }
        for (Named n : more) if (n != null && !n.name().isBlank() && seen.add(n.name().toLowerCase(Locale.ROOT))) out.add(new Named(n.name().strip(), n.lang(), false));
        return out;
    }

    /** The model names the thing itself, plainly, in each of the languages: one bounded call, the names read off its lines. */
    static List<Named> nameLive(List<String> things) {
        Researcher.Drive drive = drives.get();
        if (drive == null) return null;
        StringBuilder p = new StringBuilder("A person is looking for a video of a thing being made or shown, and names the thing like this: ");
        p.append(String.join("; ", things)).append(".\n\nName that thing itself, in its plainest form, without any material, colour, size or other feature (for \"canvas tote bag\" the thing is \"tote bag\"; for \"red wool scarf\" it is \"scarf\"), ");
        p.append("in each of these languages, as a maker would write it in a video's title: ");
        List<String> ls = new ArrayList<>(); for (String[] l : LANGUAGES) ls.add(l[1]);
        p.append(String.join(", ", ls)).append(".\nOne per line as language: name, in that language's own script, one to four words, nothing else.");
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", p.toString());
        String raw;
        try (var step = Declines.step("name the thing a remembered video makes in other languages")) { raw = drive.classify(msgs, 400); }
        if (raw == null) return null;
        List<Named> out = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String l = line.strip().replaceFirst("^[-*•·\\d.)\\]\\s]+", "");
            int at = l.indexOf(':'); if (at < 0) at = l.indexOf('：');
            if (at <= 0) continue;
            String lang = l.substring(0, at).strip().toLowerCase(Locale.ROOT), name = l.substring(at + 1).strip().replaceAll("^[\"“”'`]+|[\"“”'`.。]+$", "").strip();
            String code = null;
            for (String[] x : LANGUAGES) if (lang.equals(x[0]) || lang.equals(x[1].toLowerCase(Locale.ROOT))) code = x[0];
            if (code == null || name.isEmpty() || name.length() > 40 || name.split("\\s+").length > 5) continue;
            out.add(new Named(name, code, false));
        }
        return out;
    }

    /** The script a title is written in, for the line: Japanese, Korean, Chinese, Cyrillic, Latin, or other. */
    public static String scriptOf(String title) {
        if (title == null || title.isBlank()) return "";
        String t = title;
        if (t.codePoints().anyMatch(c -> c >= 0x3040 && c <= 0x30ff)) return "Japanese";
        if (t.codePoints().anyMatch(c -> c >= 0xac00 && c <= 0xd7af)) return "Korean";
        if (t.codePoints().anyMatch(c -> (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf))) return "Chinese";
        if (t.codePoints().anyMatch(c -> c >= 0x0400 && c <= 0x04ff)) return "Cyrillic";
        if (t.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06ff)) return "Arabic";
        if (t.codePoints().anyMatch(c -> c >= 0x0e00 && c <= 0x0e7f)) return "Thai";
        if (t.codePoints().anyMatch(Character::isLetter)) return "Latin";
        return "other";
    }

    /** Whether a title names one of the things: it carries the thing whole, or every word of it (case folded), in any language. */
    public static boolean names(String title, List<String> things) {
        if (title == null || things == null) return false;
        String hay = title.toLowerCase(Locale.ROOT);
        for (String thing : things) {
            String t = thing.toLowerCase(Locale.ROOT).strip();
            if (t.isEmpty()) continue;
            if (hay.contains(t)) return true;
            String[] words = t.split("\\s+");
            if (words.length > 1) {
                boolean all = true;
                for (String w : words) if (w.length() >= 2 && !hay.contains(w)) { all = false; break; }
                if (all) return true;
            }
        }
        return false;
    }

    // ---- the run

    /** The pool for an ask: made and kept under the enumeration's own ceiling, or read back from a whole pool kept today for the same ask. */
    public static Result run(LibraryStore store, Ask ask, Consumer<String> progress, BooleanSupplier stop) { return run(store, ask, progress, stop, System.currentTimeMillis() + ENUMERATE_MS); }

    /**
     * The same, with a time the enumeration stops at ({@code untilMs}, epoch milliseconds; 0 for none): what was gathered by then is the
     * pool, and the result says it was cut. A pool kept today for the same things, dates, length and language is read back without a
     * fetch when a later page is asked for, or when it was not cut: the same day's enumeration gives the same pool, and the Data API's
     * units are the day's.
     */
    public static Result run(LibraryStore store, Ask ask, Consumer<String> progress, BooleanSupplier stopWhen, long untilMs) { return run(store, ask, progress, stopWhen, untilMs, false); }

    /**
     * The pool from other people's words alone: the names, the pages that describe the video and the videos they link, in the same order
     * and kept the same way, with YouTube itself not searched and no channel listed. It takes minutes where the whole enumeration takes
     * a quarter of an hour, which is what a measurement of this one source needs.
     */
    public static Result describedAlone(LibraryStore store, Ask ask, Consumer<String> progress, BooleanSupplier stopWhen, long untilMs) { return run(store, ask, progress, stopWhen, untilMs, true); }

    static Result run(LibraryStore store, Ask ask, Consumer<String> progress, BooleanSupplier stopWhen, long untilMs, boolean alone) {
        if (ask.things().isEmpty()) throw new IllegalArgumentException("Give the name of the thing made or shown, in two to four words, in each language the video may be in: things.");
        if (store != null) {
            Result kept = kept(store, ask);
            if (kept != null && (ask.page() > 1 || !kept.cut())) return kept;
        }
        BooleanSupplier stop = () -> stopWhen.getAsBoolean() || (untilMs > 0 && System.currentTimeMillis() > untilMs);
        Map<String, Hit> pool = new LinkedHashMap<>();
        Map<String, Integer> by = new LinkedHashMap<>();
        for (String s : List.of(HISTORY, SEARCH, CHANNEL, API, WEB_INDEX, DESCRIBED)) by.put(s, 0);
        List<String> notes = new ArrayList<>();
        List<Named> names = named(ask.things(), notes);
        List<String> words = new ArrayList<>(); for (Named n : names) words.add(n.name());   // what a title must name: the given names and the model's

        // (f) what other people wrote about the video, asked of the web beside the searches below: it goes through neither the tunnel nor YouTube,
        // so it takes none of their time
        ExecutorService beside = null;
        Future<Described.Result> describing = null;
        if (ask.remembered().isEmpty()) notes.add("Nothing remembered of the video itself was given (remembered), so no page that describes it was looked for.");
        else {
            long now = System.currentTimeMillis(), until = untilMs > 0 ? Math.min(untilMs, now + Described.CEILING_MS) : now + Described.CEILING_MS;
            beside = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "described"); t.setDaemon(true); return t; });
            describing = beside.submit(Stopping.carried(() -> Described.run(names, ask.remembered(), stop, until)));
        }
        if (alone) notes.add("Only the pages that describe the video were asked for: YouTube itself was not searched and no channel was listed.");

        // (e) the person's own history first: a video they watched is the one source that ranks nothing
        if (alone) { }
        else if (store != null && WatchHistory.held(store)) {
            for (WatchHistory.Watched w : WatchHistory.matching(store, words)) {
                Hit h = pool.computeIfAbsent(w.id(), Hit::new);
                h.title = w.title(); h.channel = w.channel(); h.watchedOn = w.watchedOn(); h.sources.add(HISTORY);
                by.merge(HISTORY, 1, Integer::sum);
            }
            progress.accept("watch history: " + by.get(HISTORY) + " watched " + (by.get(HISTORY) == 1 ? "video names" : "videos name") + " the thing");
        } else notes.add("No watch history is filed in this library, so none was searched; researchzosho add <the watch-history.json from Google Takeout> files one.");

        // (a) deep searches of the program's own phrasings, a hundred results each
        Map<String, String> channelNames = new LinkedHashMap<>();
        boolean helper = !alone && Video.installed();
        if (!helper && !alone) notes.add("The video helper is not installed (researchzosho video install), so YouTube itself was not searched and no channel was listed.");
        List<String> phrasings = new ArrayList<>();
        for (Named n : names) for (String p : plainest(n, n.given() ? 9 : 2)) if (!phrasings.contains(p)) phrasings.add(p);
        if (helper) {
            for (String q : phrasings) {
                if (stop.getAsBoolean()) break;
                Video.Result r = Video.ytdlp(List.of("--flat-playlist", "--dump-single-json", "--playlist-end", String.valueOf(DEEP), "ytsearch" + DEEP + ":" + q), Duration.ofMinutes(5));
                if (r.code() != 0) { notes.add("The search \"" + q + "\" was not answered" + (Video.refused(r.out()) ? ": " + Video.refusal() : "") + "."); continue; }
                int n = 0;
                for (JsonNode e : listing(r.out()).path("entries")) {
                    Hit h = take(pool, e, SEARCH);
                    if (h == null) continue;
                    n++; by.merge(SEARCH, 1, Integer::sum);
                    if (!h.channelId.isEmpty()) channelNames.putIfAbsent(h.channelId, h.channel);
                }
                progress.accept("search \"" + q + "\": " + n + " results");
            }
        }

        // (b) the Data API in date order over windows, when a key is set: seconds, so before the channels, whose listings take the time
        String key = alone ? null : Config.get(KEY);
        if (alone) { }
        else if (key == null || key.isBlank()) notes.add("No YouTube Data API key is set (" + KEY + "), so YouTube was not asked for videos in date order.");
        else if (!stop.getAsBoolean()) dateApi(key.strip(), ask, names, pool, by, notes, progress, stop);

        // (f) joins here, before the channels, whose listings take what time is left: every video a page led to, with the page and what the text beside it holds
        Described.Result described = null;
        if (describing != null) {
            try { described = describing.get(Described.CEILING_MS + 60_000L, TimeUnit.MILLISECONDS); }
            catch (ExecutionException e) { if (e.getCause() instanceof Stopping.Requested requested) throw requested; notes.add("The pages that describe the video could not be looked for (" + e.getCause().getClass().getSimpleName() + ")."); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) { describing.cancel(true); notes.add("Looking for pages that describe the video did not end in its time, and was left."); }
            finally { beside.shutdownNow(); }
        }
        List<Described.Phrase> phrases = described != null ? described.phrases() : Described.phrases(ask.remembered());
        if (described != null) {
            for (String line : described.progress()) progress.accept(line);
            notes.addAll(described.notes());
            for (Described.Found f : described.found()) {
                Hit h = pool.computeIfAbsent(f.id(), Hit::new);
                if (h.title.isEmpty() && !f.title().isEmpty()) h.title = f.title();
                if (!h.described) { h.described = true; by.merge(DESCRIBED, 1, Integer::sum); }
                h.sources.add(Described.SOURCE + f.site());
                h.pages.add(f.page());
                h.byPage.add(f.fit());
                h.fits(f.fit());
            }
        }

        // (c) the channels, listed whole in the order they came and never by how often they were seen: the channel of every hit whose title
        // names the thing first, in the order the hits came, then every channel the channel search names; as many as the time allows
        if (helper && !stop.getAsBoolean()) {
            Set<String> toList = new LinkedHashSet<>();
            for (Named n : names) {
                for (String q : plainest(n, n.given() ? 2 : 1)) {
                    if (stop.getAsBoolean()) break;
                    String target = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&sp=EgIQAg%253D%253D";
                    Video.Result r = Video.ytdlp(List.of("--flat-playlist", "--dump-single-json", "--playlist-end", "20", target), Duration.ofMinutes(3));
                    if (r.code() != 0) continue;
                    for (JsonNode e : listing(r.out()).path("entries")) {
                        String id = channelIdOf(e);
                        if (id == null) continue;
                        toList.add(id);
                        channelNames.putIfAbsent(id, e.path("title").asText(e.path("channel").asText("")));
                    }
                }
            }
            // the channels of the hits naming the thing first, every one in the order the hits came, then the channel search's names: a hit's
            // own channel is evidence that the channel makes the thing, and the channel search's names are YouTube's ranking of channels, the
            // popular ones. Taken in turn (run 9), the meant video's channel, a search hit's, stood in the third round and was never listed;
            // the channel search's names first (run 8b: 890 to list, 92 reached) left the hits' own channels unreached
            List<String> bySearch = new ArrayList<>(toList), byHits = new ArrayList<>();
            for (Hit h : pool.values()) if (!h.channelId.isEmpty() && !byHits.contains(h.channelId) && (names(h.title, words) || names(h.ownTitle, words))) { byHits.add(h.channelId); channelNames.putIfAbsent(h.channelId, h.channel); }
            toList = new LinkedHashSet<>(byHits);
            toList.addAll(bySearch);
            int namedBySearch = toList.size() - byHits.size();
            int listed = 0, tried = 0;
            for (String id : toList) {
                if (stop.getAsBoolean() || (untilMs > 0 && System.currentTimeMillis() > untilMs - KEEP_FOR_DETAILS_MS)) break;
                tried++;
                Video.Result r = Video.ytdlp(List.of("--flat-playlist", "--dump-single-json", "https://www.youtube.com/channel/" + id + "/videos"), Duration.ofMinutes(10), Video.readPaceMs);
                if (r.code() != 0) continue;
                listed++;
                JsonNode l = listing(r.out());
                String name = l.path("channel").asText(channelNames.getOrDefault(id, id));
                int total = l.path("entries").size(), kept = 0;
                for (JsonNode e : l.path("entries")) {
                    if (!names(e.path("title").asText(""), words)) continue;
                    Hit h = take(pool, e, CHANNEL);
                    if (h == null) continue;
                    if (h.channel.isEmpty()) h.channel = name;
                    if (h.channelId.isEmpty()) h.channelId = id;
                    kept++; by.merge(CHANNEL, 1, Integer::sum);
                }
                progress.accept("channel " + name + ": " + kept + " of " + total + " uploads name the thing");
            }
            if (!toList.isEmpty()) notes.add(listed + (listed == 1 ? " channel's uploads were" : " channels' uploads were") + " listed whole, of " + toList.size() + " to list (the " + byHits.size() + " channels of hits whose title names the thing first, in the order the hits came, since a hit's own channel is evidence that the channel makes the thing; then the " + namedBySearch + " named by the channel search alone, which is YouTube's ranking of channels)"
                    + (tried < toList.size() ? "; " + (toList.size() - tried) + " were not reached before the time ran out." : "."));
        }

        // (d) the other indexes' view of youtube.com, through the web search's rungs
        if (!alone && !stop.getAsBoolean()) {
            int asked = 0;
            for (Named n : names) {
                for (String q : plainest(n, n.given() ? 2 : 1)) {
                    if (stop.getAsBoolean()) break;
                    String out;
                    try { out = WEB.search("site:youtube.com " + q); } catch (Exception e) { out = null; }
                    if (out == null) continue;
                    asked++;
                    for (String[] idTitle : videosInText(out)) {
                        Hit h = pool.computeIfAbsent(idTitle[0], Hit::new);
                        if (h.title.isEmpty()) h.title = idTitle[1];
                        if (h.sources.add(WEB_INDEX)) by.merge(WEB_INDEX, 1, Integer::sum);
                    }
                }
            }
            if (asked == 0) notes.add("No web search index is set up (a Serper, SerpApi or Brave key, or a SearXNG of your own), so none was asked for youtube.com pages.");
        }

        // every hit's day, length and own title from the Data API, one unit per fifty, so the filters below apply to the search and channel hits too, which come without a day
        if (key != null && !key.isBlank() && !stop.getAsBoolean()) details(key.strip(), pool, notes, progress, stop);
        // the videos the pages led to come without a title: the best fit of them are read through the helper, in the time the channels leave
        if (helper && described != null && !stopWhen.getAsBoolean()) describedDetails(pool, notes, progress, untilMs);
        // what is remembered, read against every hit's own title and description: a title that says what happens is fit a machine can check
        // and counted with what one page beside its link holds: the best single page with the video's own words, never the pages added together
        if (!phrases.isEmpty()) for (Hit h : pool.values()) {
            Described.Fit own = Described.fit(h.title + " " + h.ownTitle + " " + h.description, phrases);
            h.fits(own);
            for (Described.Fit page : h.byPage) h.fits(page.with(own));
        }

        // the machine's filters, then the order by fit
        int excluded = 0, outsideDates = 0, outsideLength = 0, outsideLanguage = 0, unknownLanguage = 0;
        List<Hit> kept = new ArrayList<>();
        String after = dayOf(ask.after(), false), before = dayOf(ask.before(), true);
        Set<String> scripts = scriptsOf(ask.language());
        for (Hit h : pool.values()) {
            if (ask.exclude().contains(h.id)) { excluded++; continue; }
            if (!h.uploaded.isEmpty() && ((!after.isEmpty() && h.uploaded.compareTo(after) < 0) || (!before.isEmpty() && h.uploaded.compareTo(before) > 0))) { outsideDates++; continue; }
            if (h.seconds > 0 && ((ask.minutesMin() > 0 && h.seconds < ask.minutesMin() * 60L) || (ask.minutesMax() > 0 && h.seconds > ask.minutesMax() * 60L))) { outsideLength++; continue; }
            if (!scripts.isEmpty() && !h.script().isEmpty() && !scripts.contains(h.script())) {
                // the tunnel reads a title in its own localisation: a Latin reading whose own title is not known may be the person's language read in English, and is kept
                if (h.ownTitle.isEmpty() && h.script().equals("Latin") && !scripts.contains("Latin")) unknownLanguage++;
                else { outsideLanguage++; continue; }
            }
            kept.add(h);
        }
        // the order: the person's own history; then by how many of the remembered phrases a machine found held, in the video's own title or
        // description or beside its link on a page, the most first, then by how many of their words, which is fit and never an engine's rank;
        // a title that names the thing; within those, one upload of each channel in turn (a channel's
        // whole list or the date-ordered API before a ranked search, nearer the person's window), then each channel's second, and so on, so no
        // channel's catalogue fills the top (run 8b, 2026-11-05: one channel's 1,239 uploads came before the first search-only hit, and the video
        // meant sat at 3,600). No count of sources or of appearances: what many sources find is what ranks everywhere
        kept.sort((a, b) -> {
            int c = Boolean.compare(b.sources.contains(HISTORY), a.sources.contains(HISTORY));
            if (c == 0) c = Integer.compare(b.fit, a.fit);
            if (c == 0) c = Integer.compare(b.fitWords, a.fitWords);
            if (c == 0) c = Boolean.compare(names(b.title, words) || names(b.ownTitle, words), names(a.title, words) || names(a.ownTitle, words));
            if (c == 0) c = Boolean.compare(enumerated(b), enumerated(a));
            if (c == 0) c = Integer.compare(nearness(a, before), nearness(b, before));
            return c;
        });
        int most = 0; for (Hit h : kept) most = Math.max(most, h.fit);
        int top = most;
        kept = inTurn(kept, h -> h.sources.contains(HISTORY) ? 0 : h.fit > 0 ? 1 + top - h.fit : h.fitWords > 0 ? top + 1 : names(h.title, words) || names(h.ownTitle, words) ? top + 2 : top + 3);
        if (!scripts.isEmpty()) notes.add("Titles in " + languageName(ask.language()) + " were kept" + (unknownLanguage > 0 ? ", and " + unknownLanguage + " whose own title the library could not read, since a title read through the tunnel may be a localisation" : "") + ".");
        notes.add(0, searchedAs(names));
        boolean cut = stop.getAsBoolean();
        if (cut) notes.add("The enumeration was cut short by the time, so the pool is what was gathered by then.");
        String stem = stem(ask);
        ObjectNode record = described == null ? null : described.record();
        Result res = new Result(kept, by, notes, excluded, outsideDates, outsideLength, outsideLanguage, 1, stem, cut, "", record);
        Result withText = new Result(kept, by, notes, excluded, outsideDates, outsideLength, outsideLanguage, 1, stem, cut, text(res, ask, 1), record);
        if (store != null) keep(store, ask, withText);
        return withText;
    }

    /** The English name of a language the pool filters by. */
    static String languageName(String code) { for (String[] l : LANGUAGES) if (l[0].equals(code)) return l[1]; return code; }

    /** The plainest {@code n} phrasings of a name in its language. */
    static List<String> plainest(Named n, int most) { List<String> ps = phrasings(n.name(), n.lang()); return ps.subList(0, Math.min(most, ps.size())); }

    /** The sentence that says which names the thing was searched under, the person's and the model's. */
    static String searchedAs(List<Named> names) {
        List<String> given = new ArrayList<>(), named = new ArrayList<>();
        for (Named n : names) (n.given() ? given : named).add(n.name());
        return "Searched as " + String.join(", ", given) + (named.isEmpty() ? " (the names given, in their own languages)." : ", and as the model named the thing in other languages: " + String.join(", ", named) + ".");
    }

    /**
     * The hits one channel at a time: within each bucket (the history, the titles naming the thing, the rest), the first hit of every channel
     * in the order the channels first appear, then every channel's second, and so on; a hit with no channel is a channel of its own. The
     * order within a channel is the order given.
     */
    static List<Hit> inTurn(List<Hit> sorted, ToIntFunction<Hit> bucketOf) {
        List<Hit> out = new ArrayList<>(sorted.size());
        int buckets = 0; for (Hit h : sorted) buckets = Math.max(buckets, bucketOf.applyAsInt(h) + 1);
        for (int b = 0; b < buckets; b++) {
            Map<String, List<Hit>> byChannel = new LinkedHashMap<>();
            for (Hit h : sorted) if (bucketOf.applyAsInt(h) == b) byChannel.computeIfAbsent(h.channelId.isEmpty() ? "~" + h.id : h.channelId, k -> new ArrayList<>()).add(h);
            boolean any = true;
            for (int round = 0; any; round++) {
                any = false;
                for (List<Hit> hs : byChannel.values()) if (round < hs.size()) { out.add(hs.get(round)); any = true; }
            }
        }
        return out;
    }

    /** A hit found by enumeration (a channel's whole list, the date-ordered API, the history) rather than by a ranked search. */
    static boolean enumerated(Hit h) { return h.sources.contains(CHANNEL) || h.sources.contains(API) || h.sources.contains(HISTORY); }

    /** Days between a hit's upload and the end of the person's window; a hit without a date, or without a window, sits after the dated ones. */
    static int nearness(Hit h, String before) {
        if (h.uploaded.isEmpty()) return Integer.MAX_VALUE;
        if (before.isEmpty()) return Integer.MAX_VALUE - 1;
        try { return (int) Math.abs(LocalDate.parse(before).toEpochDay() - LocalDate.parse(h.uploaded).toEpochDay()); } catch (Exception e) { return Integer.MAX_VALUE; }
    }

    /** A date as the person gives it, "2025-11" or "2025-11-05", as a day: the month's first day, or its last when it bounds from above; "" when none. */
    static String dayOf(String given, boolean end) {
        String g = given == null ? "" : given.strip();
        if (g.isEmpty()) return "";
        try {
            if (g.matches("\\d{4}-\\d{2}-\\d{2}")) return g;
            if (g.matches("\\d{4}-\\d{2}")) { YearMonth ym = YearMonth.parse(g); return (end ? ym.atEndOfMonth() : ym.atDay(1)).toString(); }
            if (g.matches("\\d{4}")) return end ? g + "-12-31" : g + "-01-01";
        } catch (Exception ignored) { }
        return "";
    }

    static JsonNode listing(String out) {
        try { int at = out.indexOf('{'); return at < 0 ? M.createObjectNode() : M.readTree(out.substring(at)); }
        catch (IOException e) { return M.createObjectNode(); }
    }

    /** One yt-dlp entry into the pool under a source; null when it is no video. */
    static Hit take(Map<String, Hit> pool, JsonNode e, String source) {
        String id = e.path("id").asText("");
        if (!id.matches("[A-Za-z0-9_-]{11}")) id = String.valueOf(VideoText.videoId(e.path("url").asText("")));
        if (!id.matches("[A-Za-z0-9_-]{11}")) return null;
        Hit h = pool.computeIfAbsent(id, Hit::new);
        if (h.title.isEmpty()) h.title = e.path("title").asText("");
        if (h.channel.isEmpty()) h.channel = e.path("channel").asText(e.path("uploader").asText(""));
        if (h.channelId.isEmpty()) h.channelId = e.path("channel_id").asText("");
        if (h.seconds < 0 && e.path("duration").asLong(-1) > 0) h.seconds = (int) e.path("duration").asLong();
        if (h.uploaded.isEmpty()) h.uploaded = VideoText.day(e.path("upload_date").asText(""));
        return h.sources.add(source) ? h : null;
    }

    static String channelIdOf(JsonNode e) {
        String id = e.path("id").asText(e.path("channel_id").asText(""));
        if (id.matches("UC[A-Za-z0-9_-]{22}")) return id;
        String url = e.path("url").asText(e.path("channel_url").asText(""));
        Matcher m = Pattern.compile("/channel/(UC[A-Za-z0-9_-]{22})").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static final Pattern VIDEO_LINE = Pattern.compile("(?m)^\\s*\\d+\\.\\s+(.*?)\\s*\\n\\s*(\\S+)");
    private static final Pattern VIDEO_ID = Pattern.compile("(?:v=|youtu\\.be/|/shorts/)([A-Za-z0-9_-]{11})");

    /** The videos a web search's text names: each result's address with its title, by the lines the search tools write. */
    static List<String[]> videosInText(String text) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = VIDEO_LINE.matcher(text);
        while (m.find()) {
            Matcher v = VIDEO_ID.matcher(m.group(2));
            if (v.find() && seen.add(v.group(1))) out.add(new String[]{v.group(1), m.group(1).strip()});
        }
        Matcher any = VIDEO_ID.matcher(text);
        while (any.find()) if (seen.add(any.group(1))) out.add(new String[]{any.group(1), ""});
        return out;
    }

    // ---- the Data API

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    static final String USER_AGENT = "ResearchZosho-research/1.0 (https://researchzosho.org)";

    /** One window of the search: the language, the phrasing, the bounds, and the page token when the window has more. */
    record Page(String lang, String q, String from, String to, String token) { }

    static void dateApi(String key, Ask ask, List<Named> names, Map<String, Hit> pool, Map<String, Integer> by, List<String> notes, Consumer<String> progress, BooleanSupplier stop) {
        int cap = pagesPerRun();
        List<String[]> windows = windows(ask);
        Deque<Page> queue = new ArrayDeque<>();
        for (String[] w : windows) for (Named n : names) queue.add(new Page(n.lang(), plainest(n, 1).get(0), w[0], w[1], ""));   // the newest window in every language before the next window: a cap cuts windows, never a language
        int pages = 0, found = 0;
        String stopped = "";
        while (!queue.isEmpty() && pages < cap && !stop.getAsBoolean()) {
            if (SearchTally.usedToday(TALLY) + PAGE_UNITS > QUOTA) { stopped = "the day's " + QUOTA + " units are used"; break; }
            Page p = queue.poll();
            String[] region = REGIONS.getOrDefault(p.lang(), REGIONS.get("en"));
            String url = apiBase + "/search?part=snippet&type=video&order=date&maxResults=50&q=" + URLEncoder.encode(p.q(), StandardCharsets.UTF_8)
                    + "&regionCode=" + region[0] + "&relevanceLanguage=" + region[1] + "&publishedAfter=" + p.from() + "T00:00:00Z&publishedBefore=" + p.to() + "T23:59:59Z"
                    + (p.token().isEmpty() ? "" : "&pageToken=" + p.token()) + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
            JsonNode j = api(url);
            pages++;
            SearchTally.countToday(TALLY, PAGE_UNITS);
            if (j == null) { stopped = "Google did not answer"; break; }
            if (j.has("error")) { stopped = apiError(j); break; }
            for (JsonNode item : j.path("items")) {
                String id = item.path("id").path("videoId").asText("");
                if (!id.matches("[A-Za-z0-9_-]{11}")) continue;
                Hit h = pool.computeIfAbsent(id, Hit::new);
                JsonNode s = item.path("snippet");
                if (h.title.isEmpty()) h.title = s.path("title").asText("");
                if (h.channel.isEmpty()) h.channel = s.path("channelTitle").asText("");
                if (h.channelId.isEmpty()) h.channelId = s.path("channelId").asText("");
                if (h.uploaded.isEmpty()) h.uploaded = s.path("publishedAt").asText("").length() >= 10 ? s.path("publishedAt").asText().substring(0, 10) : "";
                if (h.sources.add(API)) { found++; by.merge(API, 1, Integer::sum); }
            }
            String next = j.path("nextPageToken").asText("");
            if (!next.isEmpty() && j.path("items").size() >= 50) queue.add(new Page(p.lang(), p.q(), p.from(), p.to(), next));   // the window's next page goes to the back: breadth before depth
        }
        String unitsNote = pages + (pages == 1 ? " page" : " pages") + " of the Data API (" + SearchTally.usedToday(TALLY) + " of the day's " + QUOTA + " units used by " + SearchTally.pacificDay() + ", Pacific time)";
        progress.accept("date-ordered API: " + found + " videos from " + unitsNote);
        notes.add("The YouTube Data API was asked in date order: " + unitsNote + (stopped.isEmpty() ? (queue.isEmpty() ? "." : "; the run's " + cap + " pages were used with windows still unread (" + PAGES + " raises it).") : "; it stopped because " + stopped + "."));
    }

    /**
     * The day, the length and the uploader's own title of every hit that lacks one, from videos.list, fifty a call and a unit each, under
     * {@link #LIST_CALLS_MOST} calls a run and the day's allowance: a search's and a channel's flat listing carry no day, and the tunnel reads
     * a title in its own localisation, so without this the date filter would pass every such hit and the script of a title could be wrong.
     */
    static void details(String key, Map<String, Hit> pool, List<String> notes, Consumer<String> progress, BooleanSupplier stop) {
        List<String> ids = new ArrayList<>();
        for (Hit h : pool.values()) if (h.uploaded.isEmpty() || h.seconds <= 0 || h.ownTitle.isEmpty()) ids.add(h.id);
        int calls = 0, filled = 0;
        String stopped = "";
        for (int i = 0; i < ids.size() && !stop.getAsBoolean(); i += 50) {
            if (calls >= LIST_CALLS_MOST) { stopped = "the run's " + LIST_CALLS_MOST + " calls were used"; break; }
            if (SearchTally.usedToday(TALLY) + LIST_UNITS > QUOTA) { stopped = "the day's " + QUOTA + " units are used"; break; }
            List<String> batch = ids.subList(i, Math.min(i + 50, ids.size()));
            JsonNode j = api(apiBase + "/videos?part=contentDetails,snippet&id=" + String.join(",", batch) + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
            calls++;
            SearchTally.countToday(TALLY, LIST_UNITS);
            if (j == null) { stopped = "Google did not answer"; break; }
            if (j.has("error")) { stopped = apiError(j); break; }
            for (JsonNode item : j.path("items")) {
                Hit h = pool.get(item.path("id").asText(""));
                if (h == null) continue;
                JsonNode s = item.path("snippet");
                if (h.seconds <= 0) h.seconds = isoSeconds(item.path("contentDetails").path("duration").asText(""));
                if (h.uploaded.isEmpty() && s.path("publishedAt").asText("").length() >= 10) h.uploaded = s.path("publishedAt").asText().substring(0, 10);
                String own = s.path("title").asText("").strip();
                if (!own.isEmpty()) { h.ownTitle = own; if (h.title.isEmpty()) h.title = own; }
                if (h.channel.isEmpty()) h.channel = s.path("channelTitle").asText("");
                if (h.channelId.isEmpty()) h.channelId = s.path("channelId").asText("");
                filled++;
            }
        }
        progress.accept("details: " + filled + " videos' days and lengths from " + calls + " calls");
        if (calls > 0 || !stopped.isEmpty()) notes.add("The day, the length and the own title of " + filled + " videos came from the Data API in " + calls + (calls == 1 ? " call" : " calls") + " of a unit each" + (stopped.isEmpty() ? "." : "; it stopped because " + stopped + "."));
    }

    /** How many of the videos the pages led to are read through the helper for their title, day, length and description: the best fit first. */
    static final int DESCRIBED_DETAILS = 20;
    /** The helper's line for a video YouTube no longer has, or shows to nobody. */
    private static final Pattern GONE = Pattern.compile("^ERROR: \\[youtube\\] ([A-Za-z0-9_-]{11}): .*(?i:unavailable|private video|has been removed|been terminated|no longer available|does not exist)");

    /**
     * The title, the channel, the day, the length and the description of the videos other people's pages led to, through the helper in one
     * call: such a video comes with nothing but its id, so without this the dates and the length rule none of them out, and its own
     * description is never read against what is remembered. The best fit by their pages first, {@value #DESCRIBED_DETAILS} at most, and
     * only in the time the enumeration has left.
     */
    static void describedDetails(Map<String, Hit> pool, List<String> notes, Consumer<String> progress, long untilMs) {
        List<Hit> want = new ArrayList<>();
        for (Hit h : pool.values()) if (h.described && h.fit > 0 && (h.channelId.isEmpty() || h.seconds <= 0)) want.add(h);
        if (want.isEmpty()) return;
        want.sort((a, b) -> a.fit != b.fit ? Integer.compare(b.fit, a.fit) : Integer.compare(b.fitWords, a.fitWords));
        if (want.size() > DESCRIBED_DETAILS) want = want.subList(0, DESCRIBED_DETAILS);
        long left = untilMs > 0 ? untilMs - System.currentTimeMillis() - 5_000L : 120_000L;
        if (left < 20_000L) { notes.add("The videos the pages led to were not read for their titles and days: the time had run out."); return; }
        List<String> cmd = new ArrayList<>(List.of("--skip-download", "--no-playlist", "--ignore-errors", "--print", "%(.{id,title,description,channel,channel_id,duration,upload_date})j"));
        for (Hit h : want) cmd.add(h.link());
        Video.Result r = Video.ytdlp(cmd, Duration.ofMillis(Math.min(left, 120_000L)), Video.readPaceMs);
        int filled = 0, gone = 0;
        if (r.out() != null) for (String line : r.out().split("\\R")) {
            String l = line.strip();
            // a video YouTube no longer has cannot be looked at: it leaves the pool, and the pool says how many did
            Matcher unavailable = GONE.matcher(l);
            if (unavailable.find() && pool.containsKey(unavailable.group(1)) && pool.get(unavailable.group(1)).sources.stream().allMatch(x -> x.startsWith(Described.SOURCE))) { pool.remove(unavailable.group(1)); gone++; continue; }
            if (!l.startsWith("{")) continue;
            JsonNode j;
            try { j = M.readTree(l); } catch (IOException e) { continue; }
            Hit h = pool.get(j.path("id").asText(""));
            if (h == null) continue;
            String title = j.path("title").asText("").strip();
            if (!title.isEmpty()) h.title = title;
            if (h.channel.isEmpty()) h.channel = j.path("channel").asText("");
            if (h.channelId.isEmpty()) h.channelId = j.path("channel_id").asText("");
            if (h.seconds <= 0 && j.path("duration").asLong(-1) > 0) h.seconds = (int) j.path("duration").asLong();
            if (h.uploaded.isEmpty()) h.uploaded = VideoText.day(j.path("upload_date").asText(""));
            h.description = j.path("description").asText("");
            filled++;
        }
        progress.accept("described: " + filled + " of " + want.size() + " videos' titles, days and descriptions read");
        notes.add("The title, the day, the length and the description of " + filled + " of the " + want.size() + " videos the pages led to with the most of what is remembered were read through the helper."
                + (gone > 0 ? " " + gone + (gone == 1 ? " of them is" : " of them are") + " gone from YouTube, and left the pool." : ""));
    }

    /** The windows asked: the person's dates as one window; else the last three years in half-year windows, the newest first. Each as [from, to] days. */
    static List<String[]> windows(Ask ask) {
        String after = dayOf(ask.after(), false), before = dayOf(ask.before(), true);
        List<String[]> out = new ArrayList<>();
        LocalDate to = before.isEmpty() ? LocalDate.now() : LocalDate.parse(before);
        LocalDate from = after.isEmpty() ? to.minusYears(3) : LocalDate.parse(after);
        if (!from.plusMonths(8).isBefore(to)) { out.add(new String[]{from.toString(), to.toString()}); return out; }
        LocalDate end = to;
        while (end.isAfter(from)) {
            LocalDate start = YearMonth.from(end).minusMonths(5).atDay(1);   // six calendar months, the month of the end included
            if (start.isBefore(from)) start = from;
            out.add(new String[]{start.toString(), end.toString()});
            end = start.minusDays(1);
        }
        return out;
    }

    static JsonNode api(String url) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            return M.readTree(r.body());
        } catch (Exception e) { return null; }
    }

    /** Google's error, in words that name no key: the quota, a refused key, or the reason it gives. */
    static String apiError(JsonNode j) {
        String reason = "";
        for (JsonNode e : j.path("error").path("errors")) reason = e.path("reason").asText(reason);
        if (reason.isEmpty()) reason = j.path("error").path("status").asText("");
        return switch (reason) {
            case "quotaExceeded", "dailyLimitExceeded", "RESOURCE_EXHAUSTED" -> "the key's allowance for today is used up";
            case "keyInvalid", "API_KEY_INVALID", "PERMISSION_DENIED", "forbidden", "accessNotConfigured" -> "the key was refused (is the YouTube Data API v3 enabled for it?)";
            default -> "Google answered with an error" + (reason.isEmpty() ? "" : " (" + reason + ")");
        };
    }

    /** PT1H2M3S → seconds; -1 when it is no duration. */
    static int isoSeconds(String iso) {
        Matcher m = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(iso == null ? "" : iso);
        if (!m.matches()) return -1;
        int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1)), mi = m.group(2) == null ? 0 : Integer.parseInt(m.group(2)), s = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return h * 3600 + mi * 60 + s;
    }

    // ---- the other indexes

    /** Whether a web search index is set: a Google key (Serper, SerpApi), Brave, or a SearXNG of the keeper's own. The built-in fallback is not one: it has no YouTube. */
    public static boolean webIndexSet() {
        String brave = Config.get("RESEARCHZOSHO_BRAVE_KEY");
        return WebSearchTool.googleConfigured() || (brave != null && !brave.isBlank()) || WebSearchTool.searxConfigured();
    }

    static String webLive(String query) throws Exception {
        if (!webIndexSet()) return null;
        return new WebSearchTool().upTo(WEB_ROWS).execute(M.createObjectNode().put("query", query).put("limit", WEB_ROWS));
    }

    // ---- what is kept, and the text

    /** The key a kept pool goes under and the sift takes: the day and the ask's key. */
    static String stem(Ask ask) { return LocalDate.now() + "-" + ask.key(); }

    static Path poolDir(LibraryStore store) { return store.root().resolve("catalog").resolve("candidates"); }

    static Path poolFile(LibraryStore store, Ask ask) { return poolDir(store).resolve(stem(ask) + ".json"); }

    /** A kept pool by its key, as the sift takes it: the whole key (day and hash), or the hash alone for the newest pool under it; null when none is kept. */
    public static Path poolFileByKey(LibraryStore store, String key) {
        String k = key == null ? "" : key.strip().replaceAll("\\.json$", "");
        if (!k.matches("(\\d{4}-\\d{2}-\\d{2}-)?[0-9a-f]{16}")) return null;
        Path dir = poolDir(store);
        if (k.length() > 16) { Path f = dir.resolve(k + ".json"); return Files.isRegularFile(f) ? f : null; }
        if (!Files.isDirectory(dir)) return null;
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith("-" + k + ".json")).max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        } catch (IOException e) { return null; }
    }

    static void keep(LibraryStore store, Ask ask, Result r) {
        try {
            ObjectNode o = M.createObjectNode();
            ArrayNode things = o.putArray("things"); for (String t : ask.things()) things.add(t);
            ArrayNode remembered = o.putArray("remembered"); for (String t : ask.remembered()) remembered.add(t);
            if (r.described() != null) o.set("described", r.described());
            o.put("before", ask.before()).put("after", ask.after()).put("minutes_min", ask.minutesMin()).put("minutes_max", ask.minutesMax()).put("language", ask.language()).put("made", LocalDate.now().toString()).put("key", r.key()).put("cut", r.cut());
            ObjectNode by = o.putObject("by_source"); r.bySource().forEach(by::put);
            o.put("excluded", r.excluded()).put("outside_dates", r.outsideDates()).put("outside_length", r.outsideLength()).put("outside_language", r.outsideLanguage());
            ArrayNode notes = o.putArray("notes"); for (String n : r.notes()) notes.add(n);
            ArrayNode pool = o.putArray("pool"); for (Hit h : r.pool()) pool.add(h.json());
            Path f = poolFile(store, ask);
            Files.createDirectories(f.getParent());
            Files.writeString(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(o), StandardCharsets.UTF_8);
            // the whole pool on the shelves too, as the text of every line, so a later run finds it by the thing's name
            StringBuilder text = new StringBuilder(head(r, ask) + "\n");
            int i = 0;
            for (Hit h : r.pool()) text.append(++i).append(". ").append(line(h)).append('\n');
            RawCapture.capture(store, "researchzosho://candidates/" + ask.key(), text.toString(), "Candidates for " + String.join(", ", ask.things()), "researchzosho-candidates", "");
        } catch (IOException ignored) { }
    }

    /** The pool kept today for the same things, dates, length and language, at the page asked; null when none was kept. */
    static Result kept(LibraryStore store, Ask ask) {
        Path f = poolFile(store, ask);
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonNode o = M.readTree(f.toFile());
            List<Hit> pool = new ArrayList<>();
            for (JsonNode h : o.path("pool")) { Hit hit = Hit.of(h); if (!ask.exclude().contains(hit.id)) pool.add(hit); }
            Map<String, Integer> by = new LinkedHashMap<>();
            o.path("by_source").properties().forEach(e -> by.put(e.getKey(), e.getValue().asInt()));
            List<String> notes = new ArrayList<>(); for (JsonNode n : o.path("notes")) notes.add(n.asText());
            notes.add(0, "Read back from the pool kept today for the same ask; nothing was fetched.");
            ObjectNode described = o.path("described").isObject() ? (ObjectNode) o.path("described") : null;
            Result r = new Result(pool, by, notes, o.path("excluded").asInt() + (o.path("pool").size() - pool.size()), o.path("outside_dates").asInt(), o.path("outside_length").asInt(), o.path("outside_language").asInt(), ask.page(), stem(ask), o.path("cut").asBoolean(false), "", described);
            return new Result(pool, by, notes, r.excluded(), r.outsideDates(), r.outsideLength(), r.outsideLanguage(), ask.page(), r.key(), r.cut(), text(r, ask, ask.page()), described);
        } catch (IOException e) { return null; }
    }

    /** A kept pool's hits in their order and the things it was made for, read by the sift; null when the file cannot be read. */
    public record Kept(List<String> things, List<Hit> pool, String key) { }

    public static Kept read(Path poolFile) {
        try {
            JsonNode o = M.readTree(poolFile.toFile());
            List<String> things = new ArrayList<>(); for (JsonNode t : o.path("things")) things.add(t.asText());
            List<Hit> pool = new ArrayList<>(); for (JsonNode h : o.path("pool")) pool.add(Hit.of(h));
            return new Kept(things, pool, poolFile.getFileName().toString().replaceAll("\\.json$", ""));
        } catch (IOException | RuntimeException e) { return null; }
    }

    static String head(Result r, Ask ask) {
        Map<String, Integer> by = r.bySource();
        List<String> parts = new ArrayList<>();
        if (by.getOrDefault(HISTORY, 0) > 0) parts.add(by.get(HISTORY) + " from your own watch history");
        parts.add(by.getOrDefault(SEARCH, 0) + " by deep YouTube searches");
        parts.add(by.getOrDefault(CHANNEL, 0) + " by listing channels' uploads whole");
        parts.add(by.getOrDefault(API, 0) + " by the YouTube Data API in date order");
        parts.add(by.getOrDefault(WEB_INDEX, 0) + " by other search indexes");
        if (by.getOrDefault(DESCRIBED, 0) > 0 || !ask.remembered().isEmpty()) parts.add(by.getOrDefault(DESCRIBED, 0) + " through pages that describe a video in the words remembered");
        String dropped = "";
        if (r.excluded() + r.outsideDates() + r.outsideLength() + r.outsideLanguage() > 0) {
            List<String> d = new ArrayList<>();
            if (r.excluded() > 0) d.add(r.excluded() + " already rejected");
            if (r.outsideDates() > 0) d.add(r.outsideDates() + " uploaded outside the dates");
            if (r.outsideLength() > 0) d.add(r.outsideLength() + " outside the length");
            if (r.outsideLanguage() > 0) d.add(r.outsideLanguage() + " with a title in another language");
            dropped = " Left out: " + String.join(", ", d) + ".";
        }
        return "Candidates for " + String.join(", ", ask.things()) + ": " + r.pool().size() + " in the pool, best fit first, found " + String.join(", ", parts) + " (a video found by more than one is counted in each)." + dropped;
    }

    static String line(Hit h) {
        List<String> facts = new ArrayList<>();
        facts.add(h.link());
        facts.add(h.shownTitle().isEmpty() ? "(no title read)" : "\"" + h.shownTitle().replace("\n", " ") + "\"");
        if (!h.channel.isEmpty()) facts.add(h.channel);
        facts.add(h.uploaded.isEmpty() ? "upload day unknown" : "uploaded " + h.uploaded);
        facts.add(h.seconds > 0 ? VideoText.clock(h.seconds) : "length unknown");
        if (!h.script().isEmpty()) facts.add("title in " + h.script());
        facts.add("found by " + String.join(" + ", h.sources));
        if (h.fit > 0) facts.add((h.pages.isEmpty() ? "its title or description holds " : "the page or its own title holds ") + h.fit + " of the things remembered (" + String.join("; ", h.held).replace("\n", " ") + ")");
        if (!h.watchedOn.isEmpty()) facts.add("you watched this on " + h.watchedOn);
        else if (h.sources.contains(HISTORY)) facts.add("you watched this");
        return String.join(" · ", facts);
    }

    /** The text the model and the person read: the counts in one sentence, the notes, the page's lines fenced as YouTube's text, and how to go on. */
    static String text(Result r, Ask ask, int page) {
        StringBuilder b = new StringBuilder(head(r, ask));
        for (String n : r.notes()) b.append(' ').append(n);
        b.append('\n');
        int from = (page - 1) * SHOWN, to = Math.min(r.pool().size(), from + SHOWN);
        if (r.pool().isEmpty()) return b.append("Nothing names the thing in any source. Try the thing's name in another language or another word for it.").toString();
        if (from >= r.pool().size()) return b.append("Page ").append(page).append(" is past the end: the pool has ").append(r.pool().size()).append(" candidates, ").append(SHOWN).append(" a page.").toString();
        b.append("Candidates ").append(from + 1).append(" to ").append(to).append(" of ").append(r.pool().size()).append(" (YouTube's own titles, fenced):\n").append(Fence.open("CANDIDATES")).append('\n');
        for (int i = from; i < to; i++) b.append(i + 1).append(". ").append(line(r.pool().get(i))).append('\n');
        b.append(Fence.close("CANDIDATES")).append('\n').append(Fence.rule("CANDIDATES")).append('\n');
        b.append("The pool is kept under the key ").append(r.key()).append(". Next, sift it: sift with from=\"").append(r.key()).append("\" and what=the things remembered judges every candidate's thumbnail and then the first storyboard sheet of those that pass, and hands back the survivors best first; then the first five survivors go to one look together (videos), sparse, for every remembered thing at once, then the next five, while the budget lasts.");
        if (to < r.pool().size()) b.append(" The same call with page ").append(page + 1).append(" shows the next ").append(Math.min(SHOWN, r.pool().size() - to)).append(" of the pool without fetching anything.");
        return b.toString();
    }
}
