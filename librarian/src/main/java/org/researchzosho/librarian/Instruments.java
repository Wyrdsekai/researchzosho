package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.tools.CustomSearchTool;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.tools.LookTool;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.TavilySearchTool;
import org.researchzosho.tools.Tool;
import org.researchzosho.tools.VideoText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import java.io.IOException;
/**
 * The table: the grouped names over what exists. {@code search} carries the places to look in one description; {@code open} reads an
 * address as what it is; {@code look} reads a video's frames and transcript at any address; {@code candidates} and {@code sift} are the pool for a remembered video
 * and its funnel; {@code patents}, {@code patent_abstracts} and {@code patent_claims} the pool for prior art, its reading and its evidence,
 * on the table of a run about patents and of the chat; {@code records}, {@code homes}, {@code board},
 * {@code dossier} and {@code who} are the fields' instruments, on the table for the first time; {@code shelf}, {@code note}, {@code write}
 * and {@code ask} are the loop's own. The old tool names stay reachable beside these until the goals retire them.
 */
public final class Instruments {
    private Instruments() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** The question an instrument stopped on in this thread's last step, or null. */
    static final ThreadLocal<Brain.Needed> NEEDED = new ThreadLocal<>();
    private static final ThreadLocal<Researcher.Budget> BUDGET = new ThreadLocal<>();
    private static final ThreadLocal<BooleanSupplier> STOP = new ThreadLocal<>();

    /** The place {@code search} handed its query to in this thread's last step, or null. */
    private static final ThreadLocal<String> SEARCHED = new ThreadLocal<>();

    /** The model the run's workers read with, for an instrument that reads by itself (the abstracts and the claims of patents); null outside a run. */
    private static final ThreadLocal<Researcher.Drive> DRIVE = new ThreadLocal<>();
    /** When this thread's worker must be done, epoch milliseconds; 0 when it has no such time. */
    private static final ThreadLocal<Long> WORKER_UNTIL = new ThreadLocal<>();
    /** The sources an instrument read and kept in this thread's last step, by their addresses, and the pools of patents it worked on, by their keys. */
    private static final ThreadLocal<List<String>> READ = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> POOLS = new ThreadLocal<>();

    /** The loop says what a step may spend and when to stop, before it runs an instrument as a tool. */
    public static void step(Researcher.Budget budget, BooleanSupplier stop) { BUDGET.set(budget); STOP.set(stop); NEEDED.remove(); SEARCHED.remove(); READ.remove(); POOLS.remove(); }
    /** The same, with the model the run's workers read with. */
    public static void step(Researcher.Budget budget, BooleanSupplier stop, Researcher.Drive drive) { step(budget, stop); DRIVE.set(drive); }
    /** A worker says when it must be done (0 to forget it), so that an instrument that takes minutes ends before the worker is given up on. */
    public static void workerUntil(long atMs) { if (atMs <= 0) { WORKER_UNTIL.remove(); DRIVE.remove(); } else WORKER_UNTIL.set(atMs); }
    static long workerUntil() { Long u = WORKER_UNTIL.get(); return u == null ? 0 : u; }
    static Researcher.Drive drive() { return DRIVE.get(); }
    /** An instrument says it read and kept a source at this address in this step. */
    public static void read(String address) { List<String> r = READ.get(); if (r == null) { r = new ArrayList<>(); READ.set(r); } if (!r.contains(address)) r.add(address); }
    /** The addresses the last step read and kept, taken once. */
    public static List<String> readNow() { List<String> r = READ.get(); READ.remove(); return r == null ? List.of() : r; }
    static void pool(String key) { if (key == null || key.isEmpty()) return; List<String> p = POOLS.get(); if (p == null) { p = new ArrayList<>(); POOLS.set(p); } if (!p.contains(key)) p.add(key); }
    /** The pools of patents the last step worked on, by their keys, taken once. */
    public static List<String> poolsNow() { List<String> p = POOLS.get(); POOLS.remove(); return p == null ? List.of() : p; }
    static Researcher.Budget budget() { Researcher.Budget b = BUDGET.get(); return b != null ? b : new Researcher.Budget(0); }
    static BooleanSupplier stop() { BooleanSupplier s = STOP.get(); return s != null ? s : () -> false; }
    /** The question the last step stopped on, taken once. */
    public static Brain.Needed needed() { Brain.Needed n = NEEDED.get(); NEEDED.remove(); return n; }
    /**
     * The place the last step's {@code search} handed its query to, by its name on the table (web, pubmed, records, the keeper's own
     * service by its name), taken once; null when the step was no search, or when the search was turned away before any place was asked:
     * a place that is not on the table, or a paid place with no key or with its month's searches used.
     */
    public static String searched() { String s = SEARCHED.get(); SEARCHED.remove(); return s; }

    // ---- the programs behind the fields' instruments, each with a stand-in a test puts in its place
    public interface HomesRunner { String run(LibraryStore store, String name, String words, BooleanSupplier stop) throws Exception; }
    public interface BoardReader { String read(String employer, List<String> towns, List<String> words) throws Exception; }
    public interface RecordsReader { String read(LibraryStore store, String address) throws Exception; }
    public static volatile HomesRunner HOMES = Instruments::homesLive;
    public static volatile BoardReader BOARD = Instruments::boardLive;
    public static volatile RecordsReader RECORDS = Instruments::recordsLive;
    /** A stand-in for the look at a video, for tests; null means the video field's own reading. */
    public static volatile Tool LOOK = null;
    /** A stand-in for the candidate pool, for tests; null means {@link Candidates#run}. */
    public static volatile Tool CANDIDATES = null;
    /** A stand-in for the sift, for tests; null means {@link Sift#run}. */
    public static volatile Tool SIFT = null;
    /** Stand-ins for the pool of patents, the reading of its abstracts and the reading of claims, for tests; null means {@link Patents} and {@link PatentReading}. */
    public static volatile Tool PATENTS = null, PATENT_ABSTRACTS = null, PATENT_CLAIMS = null;

    /** The places {@code search} can look, each to the tool that looks there. The keeper's own search service is the place {@link Places#customName}. */
    static final Map<String, String> PLACES = new LinkedHashMap<>();
    static {
        PLACES.put("web", "web_search"); PLACES.put("scholar", "scholar_search"); PLACES.put("pubmed", "pubmed_search"); PLACES.put("semantic-scholar", "semantic_scholar_search");
        PLACES.put("exa", "exa_search"); PLACES.put("tavily", "tavily_search"); PLACES.put("records", "record_search"); PLACES.put("archives", "archive_search");
        PLACES.put("old-newspapers", "old_newspapers"); PLACES.put("japanese-fulltext", "ndl_fulltext"); PLACES.put("forums", "forum_search"); PLACES.put("videos", "video_search");
        PLACES.put("video-sites", "video_sites_search"); PLACES.put("podcasts", "podcast_search"); PLACES.put("shops", "product_search"); PLACES.put("books", "book_search");
        PLACES.put("software", "software_archive"); PLACES.put("shelves", "shelf_search");
    }
    static final List<String> OLD_NAMES = List.copyOf(PLACES.values());
    static final String CUSTOM_TOOL = "custom_search";
    /** The places of 0.7.0, reached only through {@code search}: a worker's list of tools does not grow by one for each. */
    public static final Set<String> PLACE_ONLY = Set.of("pubmed_search", "semantic_scholar_search", "exa_search", "tavily_search", CUSTOM_TOOL);
    /** The places a call is never made to while they are off the table: a key missing, or the month's searches used. */
    static final Set<String> PAID = Set.of("exa_search", "tavily_search", CUSTOM_TOOL);
    /** Other spellings of the places. */
    static final Map<String, String> ALIASES = Map.ofEntries(Map.entry("newspapers", "old-newspapers"), Map.entry("japanese", "japanese-fulltext"),
            Map.entry("pmc", "pubmed"), Map.entry("pubmed-central", "pubmed"), Map.entry("pubmed central", "pubmed"), Map.entry("medline", "pubmed"),
            Map.entry("semantic scholar", "semantic-scholar"), Map.entry("semanticscholar", "semantic-scholar"), Map.entry("s2", "semantic-scholar"));

    /** The instruments for a research worker, over the tools it already holds by name: the grouped names join the old ones. */
    public static List<Instrument> forRun(LibraryStore store, Map<String, Tool> byName, Researcher.Ask ask) { return forRun(store, byName, Map.of(), ask); }

    /** The same, with the places a worker reaches only through {@code search} ({@link #PLACE_ONLY}), which are on no list of its own. */
    public static List<Instrument> forRun(LibraryStore store, Map<String, Tool> byName, Map<String, Tool> places, Researcher.Ask ask) { return forRun(store, byName, places, ask, false); }

    /**
     * The same, with the pool of patents, the reading of its abstracts and the reading of claims when the run is about patents: its goal is
     * prior art, or the patents field joined it. They sit on no other run's table, as the collections of records sit only on the table of a
     * run that works from records: three more descriptions are a cost and a distraction on a question about a compiler.
     */
    public static List<Instrument> forRun(LibraryStore store, Map<String, Tool> byName, Map<String, Tool> places, Researcher.Ask ask, boolean patents) {
        Map<String, Tool> reach = new LinkedHashMap<>(byName);
        reach.putAll(places);
        List<Instrument> out = new ArrayList<>();
        out.add(search(reach));
        out.add(open(byName));
        out.add(look(store));
        out.add(candidates(store));
        out.add(sift(store));
        if (patents) { out.add(patents(store)); out.add(patentAbstracts(store)); out.add(patentClaims(store)); }
        if (store != null) { out.add(records(store)); out.add(homes(store)); out.add(dossier(store)); }   // no who here: the family module never changes an ordinary run; its own runs bring its tools through its hooks
        out.add(board());
        return out;
    }

    /** The instruments for the chat, which holds no search tools of its own: the web tools are made here, once per table. */
    public static List<Instrument> forChat(LibraryStore store) {
        Map<String, Tool> byName = new LinkedHashMap<>();
        try { for (Tool t : Researcher.webTools().web("")) byName.put(t.name(), t); } catch (RuntimeException e) { /* a box with no web tools: search says so */ }
        if (store != null) byName.put("shelf_search", new Researcher.ShelfSearchTool(store, List.of()));
        List<Instrument> out = new ArrayList<>();
        out.add(search(byName)); out.add(open(byName)); out.add(look(store)); out.add(candidates(store)); out.add(sift(store));
        out.add(patents(store)); out.add(patentAbstracts(store)); out.add(patentClaims(store));
        if (store != null) { out.add(records(store)); out.add(homes(store)); out.add(dossier(store)); if (familyOn(store)) out.add(who(store)); out.add(shelf(store)); }
        out.add(board());
        return out;
    }

    public static boolean isInstrument(List<Instrument> table, String name) { return table.stream().anyMatch(i -> i.name().equals(name)); }

    /** Run one of the table by name, as a loop does a tool; null when the name is none of them. */
    public static String call(List<Instrument> table, String name, JsonNode args) throws Exception {
        for (Instrument i : table) if (i.name().equals(name)) return i.asTool().execute(args);
        return null;
    }

    static boolean familyOn(LibraryStore store) {
        // the family module is on by default and never changes ordinary RZ: who is on the table once the library holds family work, as the chat's own tools do (reached through the profile hooks alone)
        try { return Librarian.chatFields(store).stream().anyMatch(p -> p.chatTools().contains("library_who")); } catch (RuntimeException e) { return false; }
    }

    static ObjectNode schema(String... namesAndKinds) {
        ObjectNode kinds = M.createObjectNode();
        List<String> required = new ArrayList<>();
        for (int i = 0; i + 1 < namesAndKinds.length; i += 2) {
            String n = namesAndKinds[i], k = namesAndKinds[i + 1];
            boolean req = n.endsWith("!"); if (req) n = n.substring(0, n.length() - 1);
            kinds.put(n, k); if (req) required.add(n);
        }
        return Brain.props(kinds, required);
    }

    static ObjectNode obj(String... kv) { ObjectNode o = M.createObjectNode(); for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]); return o; }

    // ---- search

    /** Whether a place is open this month: a paid place with its key and its month's searches not used; every other place is. */
    static boolean open(String place) {
        return switch (place) { case "exa" -> ExaSearchTool.open(); case "tavily" -> TavilySearchTool.open(); default -> true; };
    }

    /** The places on this table now: those whose tool it holds, the paid ones only while they are open, the keeper's own under its name. */
    static List<String> placesHere(Map<String, Tool> byName) {
        List<String> here = new ArrayList<>();
        for (var e : PLACES.entrySet()) if (byName.containsKey(e.getValue()) && open(e.getKey())) here.add(e.getKey());
        if (byName.containsKey(CUSTOM_TOOL) && CustomSearchTool.open()) here.add(Places.customName());
        return here;
    }

    static Instrument search(Map<String, Tool> byName) {
        return new Instrument() {
            @Override public String name() { return "search"; }
            @Override public String words() {
                List<String> here = placesHere(byName);
                String custom = byName.containsKey(CUSTOM_TOOL) && CustomSearchTool.open() ? Places.customName() : "";
                boolean paid = here.contains("exa") || here.contains("tavily") || (!custom.isEmpty() && SearchTally.limit(CustomSearchTool.TALLY) >= 0);
                return "Finds pages, records, videos, shows, books, products or threads by a query in one place at a time: where = " + String.join(" | ", here)
                        + " · the first move on anything not yet in hand; web for most things, scholar for studies, records for a person, a company, a hospital or a school in a register, for patents and applications in the offices' own collections and for statutes, regulations and court decisions in the law collections (name the collection in source and the years in from_year and to_year; page=2 takes the next page of the same search where the collection pages), videos for YouTube (video-sites only for Vimeo, Bilibili, niconico and the like), shelves for what the library has read or holds (\"in my read videos\", \"on my shelves\"), forums for what people say, shops for what is for sale, books for the catalogues"
                        + (here.contains("pubmed") ? ", pubmed for medicine and the life sciences (full_text=true searches inside the open articles)" : "")
                        + (here.contains("semantic-scholar") ? ", semantic-scholar for papers in any field with how often each is cited and a free PDF" : "")
                        + (here.contains("exa") ? ", exa for the web by meaning" : "") + (here.contains("tavily") ? ", tavily for the web with each page's passages" : "")
                        + (custom.isEmpty() ? "" : ", " + custom + " for the search service set up for this library")
                        + " · one call each; a place behind a paid key counts against its allowance" + (paid ? " and the month's limit, and leaves the table when that is used" : "")
                        + ". Example for the library's own holdings: {\"where\": \"shelves\", \"query\": \"tote bag\"}; for YouTube: {\"where\": \"videos\", \"query\": \"canvas tote bag tutorial\"}"
                        + (here.contains("pubmed") ? "; for medicine: {\"where\": \"pubmed\", \"query\": \"metformin gestational diabetes\"}" : "")
                        + (here.contains("semantic-scholar") ? "; for the papers others cite: {\"where\": \"semantic-scholar\", \"query\": \"retrieval augmented generation hallucination\"}" : "") + ".";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("where!", "string", "query!", "string", "limit", "integer", "language", "string", "full_text", "boolean"); }
            @Override public String example() { return "{\"where\": \"web\", \"query\": \"canvas tote bag sewing tutorial no voice\"}"; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                String where = args.path("where").asText("web").toLowerCase(Locale.ROOT).strip();
                where = ALIASES.getOrDefault(where, where);   // the old spellings and the other names of a place
                boolean custom = where.equals(Places.customName()) || where.equals("custom");
                String toolName = custom ? CUSTOM_TOOL : PLACES.getOrDefault(where, "");
                Tool t = toolName.isEmpty() ? null : byName.get(toolName);
                if (PAID.contains(toolName)) {
                    // a paid place with no key, or with its month's searches used: one sentence, and no call
                    Source place = Places.named(custom ? Places.customName() : where);
                    if (place != null && !place.available()) return Result.of("search", where, place.unavailableBecause());
                }
                if (t == null) return Result.of("search", "", "No search looks in \"" + where + "\" on this table. The places on it are: " + String.join(", ", placesHere(byName)) + ".");
                ObjectNode a = M.createObjectNode();
                a.put("query", args.path("query").asText(""));
                if (args.has("limit")) a.put("limit", args.path("limit").asInt(10));
                if (args.has("language") && !args.path("language").asText("").isBlank()) { a.put("language", args.path("language").asText()); a.put("lang", args.path("language").asText()); }
                if (args.has("full_text")) a.set("full_text", args.get("full_text"));
                // what a place reads beside the words, when the call gives it: the collection of records and the years, a forum's site, the kind of search, the page
                for (String k : PASSED_ON) if (args.hasNonNull(k)) a.set(k, args.get(k));
                SEARCHED.set(custom ? Places.customName() : where);   // the run's research log writes the search down under its place
                return Result.of("search", where, t.execute(a));
            }
        };
    }

    /** The arguments {@code search} hands on to the place as they came, beside the words, the limit, the language and full_text. */
    static final List<String> PASSED_ON = List.of("source", "from_year", "to_year", "site", "kind", "page");

    // ---- open

    static Instrument open(Map<String, Tool> byName) {
        return new Instrument() {
            @Override public String name() { return "open"; }
            @Override public String words() {
                return "Reads one address as what it is: a page, a PDF, an image's words, a YouTube video's page and transcript, a channel's uploads, a data file"
                        + " · after a search named it, or when the person gave the address · seconds; a wall or a refusal is said rather than worked around.";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("address!", "string", "as", "string", "find", "string"); }
            @Override public String example() { return "{\"address\": \"https://www.youtube.com/watch?v=dQw4w9WgXcQ\"}"; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                String address = args.path("address").asText(args.path("url").asText("")).strip();
                String as = args.path("as").asText("auto").toLowerCase(Locale.ROOT);
                if (address.isEmpty()) return Result.of("open", "", "Give the address to open.");
                boolean video = VideoText.videoId(address) != null, channel = VideoText.channelUrl(address) != null && !video;
                String toolName = as.equals("channel") || (as.equals("auto") && channel) ? "channel_uploads"
                        : as.equals("transcript") || (as.equals("auto") && video) ? "video_details" : "web_fetch";
                Tool t = byName.get(toolName);
                if (t == null) return Result.of("open", address, "This table cannot open that kind of address (" + toolName + " is not on it).");
                ObjectNode a = M.createObjectNode();
                switch (toolName) {
                    case "video_details" -> { a.put("video", address); a.put("transcript", true); }
                    case "channel_uploads" -> a.put("channel", address);
                    default -> { a.put("url", address); if (!args.path("find").asText("").isBlank()) a.put("find", args.path("find").asText()); }
                }
                return Result.of("open", address, t.execute(a));
            }
        };
    }

    // ---- look

    static Instrument look(LibraryStore store) {
        return new Instrument() {
            @Override public String name() { return "look"; }
            @Override public String words() {
                return "Reads a video's frames and transcript into what was said, the words shown on screen and what is seen, at any address, keeps them, and answers what you ask of it with found, maybe or no and the second"
                        + " · when the question is about what a video shows, not what its page says: a silent video, writing on screen, an object or a scene you remember; a feature one sees is checked here, never from a title or a description; a video the library has already read answers at once"
                        + " · about seven minutes per ten minutes of video on a 27B; sparse=true reads every fourth moment and the first minute whole, a third of that: give the likeliest candidates together in videos (up to five), each looked at sparse for every thing remembered at once, the answer best first by how many were found; then a full look on the best.";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("videos", "array", "video", "string", "what!", "string", "sparse", "boolean"); }
            @Override public String example() { return "{\"videos\": [\"https://www.youtube.com/watch?v=dQw4w9WgXcQ\", \"https://www.youtube.com/watch?v=9bZkp7q19f0\"], \"what\": \"no talking; measurements shown on screen in the first minute\", \"sparse\": true}"; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                List<String> many = new ArrayList<>();
                for (JsonNode v : args.path("videos")) if (!v.asText("").isBlank()) many.add(v.asText().strip());
                String video = many.isEmpty() ? args.path("video").asText("") : String.join(" ", many);
                if (LOOK != null) return Result.of("look", video, LOOK.execute(args));   // a test's stand-in
                if (store == null) return Result.of("look", video, "The look at a video's frames needs a library to keep what it reads; this table has none. Open the video's page and transcript with open instead, and say that the frames were not looked at.");
                if (!many.isEmpty()) return Result.of("look", video, LookTool.runMany(store, many, args.path("what").asText(""), args.path("sparse").asBoolean(true)));   // the candidates together, breadth first
                if (video.isBlank()) return Result.of("look", video, "ERROR: Give a video's address in video, or the candidates' addresses in videos.");
                return Result.of("look", video, LookTool.run(store, args));   // the video field's reading, at any address, kept on the shelves
            }
        };
    }

    // ---- candidates

    /** The literal call a small model copies: the thing's name per language, what is remembered of the video in the person's words, and the month the person watched before; another object than any test ask. */
    static final String CANDIDATES_EXAMPLE = "{\"things\": [\"apron\", \"エプロン\", \"앞치마\"], \"remembered\": [\"nobody speaks\", \"the measurements are written on screen at the start\", \"a pocket on the front\"], \"before\": \"2025-11\"}";

    static Instrument candidates(LibraryStore store) {
        return new Instrument() {
            @Override public String name() { return "candidates"; }
            @Override public String words() {
                return "The candidate pool for a video the person remembers but cannot name: give the name of the thing made or shown, two to four words, in each language the video may be in (things), and what the person remembers of the video itself as short phrases in their own words, one thing each, eight at most, the ones that tell this video from any other first: what happens, what is seen or heard, who or what appears, words said or shown (remembered); and the program enumerates for itself: it has the model name the thing plainly in the other languages YouTube is large in (Japanese, Korean, Chinese, Spanish, Portuguese, German, French, Russian), since the person seldom knows the language of a silent video; then deep YouTube searches of its own plain phrasings of every name, a hundred results each; the pages where other people describe a video in the words remembered, found by web searches the program makes from the thing's name and each phrase, and read for the videos they link or embed; the YouTube Data API in date order when a key is set; the other search indexes' view of youtube.com; every upload of the channels the channel search names and of the channels of hits naming the thing, listed whole in the order they came, as many as the time allows; and the person's own watch history before all of these; with the key, every hit's upload day, length and own title come from the Data API too, a unit per fifty videos. Then it drops what a machine can check (the ids already rejected in exclude, uploads outside after and before, lengths outside minutes_min and minutes_max, titles in another language than language when the person said the language of the video's title or on-screen text, as ja or ko) and hands back the pool best fit first, a video whose own title or description, or the page that links it, holds more of the remembered phrases before one that holds fewer, with no popularity in it: no views, no subscribers, no likes, no count of sources, no engine's rank"
                        + " · the first move for a remembered video, before any search and before any look: you give the things and what is remembered as the person said it, never a search; a remembered feature (a colour, a material, that nobody speaks, a pocket, how it closes) goes in remembered and never in things, since a title seldom names it and it is checked by looking; then the pool goes to sift by its key"
                        + " · about ten to twenty minutes through the tunnel; each Data API page is 100 of its day's 10,000 units; the whole pool is kept under a key, so page 2 and the sift fetch nothing. Example: " + CANDIDATES_EXAMPLE + ".";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("things!", "array", "remembered", "array", "before", "string", "after", "string", "minutes_min", "integer", "minutes_max", "integer", "language", "string", "exclude", "array", "page", "integer"); }
            @Override public String example() { return CANDIDATES_EXAMPLE; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                if (CANDIDATES != null) return Result.of("candidates", "", CANDIDATES.execute(args));   // a test's stand-in
                Candidates.Ask ask = Candidates.Ask.of(args);
                if (ask.things().isEmpty()) return Result.of("candidates", "", "Give the name of the thing made or shown, in two to four words, in each language the video may be in: things. Example: " + CANDIDATES_EXAMPLE);
                String address = String.join(", ", ask.things());
                // the enumeration's ceiling: twenty minutes, or less when the run's deadline must leave time for the sift and the looks that follow
                long until = System.currentTimeMillis() + Candidates.ENUMERATE_MS;
                if (budget != null && budget.deadlineMs() > 0) until = Math.min(until, Math.max(System.currentTimeMillis() + 5 * 60_000L, budget.deadlineMs() - Candidates.KEEP_FOR_SIFT_MS - Candidates.KEEP_FOR_LOOKS_MS));
                try { return Result.of("candidates", address, Candidates.run(store, ask, s -> { }, stop, until).text()); }
                catch (IllegalArgumentException e) { return Result.of("candidates", address, e.getMessage()); }
            }
        };
    }

    // ---- sift

    /** The literal call a small model copies: things one still can show, a thing about the start, and a pool's key; another object than any test ask. */
    static final String SIFT_EXAMPLE = "{\"what\": \"a striped apron with a pocket on the front; a red tag at the neck; the pattern pieces laid out at the start\", \"from\": \"2026-11-05-0a1b2c3d4e5f6071\"}";

    static Instrument sift(LibraryStore store) {
        return new Instrument() {
            @Override public String name() { return "sift"; }
            @Override public String words() {
                return "The funnel between the candidate pool and the five a look can afford: every candidate's thumbnail, then the first storyboard sheet of those that pass, each a picture the model that reads pictures judges yes, maybe or no, keeping yes and maybe; the thumbnail for the things remembered that one still can show (the thing itself, its colours, a tag or label, a person shown or not), the first sheet, the first minutes as small frames, for the things remembered about the start; what no picture shows (that nobody speaks, a step in the middle) is left to the look; the survivors come back best first, yes before maybe, with the model's line on each"
                        + " · after candidates and before any look: the pool's key (from) and the things remembered (what) in, or the videos themselves (videos); the first five survivors then go to one look together"
                        + " · the thumbnails go nine to a call as one numbered grid, under a second a thumbnail, and a sheet takes about ten seconds on a 27B, so a pool of thousands is sifted within a run; it stops cleanly at the run's deadline, says how many were not reached, and the same call with page=2 goes on from there; nothing is judged twice; one sift runs in a library at a time, and a second call while one runs comes back at once saying so. Example: " + SIFT_EXAMPLE + ".";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("what!", "string", "from", "string", "videos", "array", "things", "array", "limit", "integer", "page", "integer"); }
            @Override public String example() { return SIFT_EXAMPLE; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                if (SIFT != null) return Result.of("sift", "", SIFT.execute(args));   // a test's stand-in
                Sift.Ask ask = Sift.Ask.of(args);
                if (ask.what().isEmpty()) return Result.of("sift", "", "Say what you remember of the video, one thing after another (what), and give the pool's key (from). Example: " + SIFT_EXAMPLE);
                if (store == null) return Result.of("sift", ask.from(), "The sift needs a library to keep what it judges; this table has none. Give the first five of the pool to the look instead.");
                // the sift's ceiling: the run's time less what the looks that follow need; none outside a run, where the limit bounds it
                long until = 0;
                if (budget != null && budget.deadlineMs() > 0) until = Math.max(System.currentTimeMillis() + 5 * 60_000L, System.currentTimeMillis() + (long) (budget.minutesLeftForWorkers() * 60_000L) - Candidates.KEEP_FOR_LOOKS_MS);
                try { return Result.of("sift", ask.from(), Sift.run(store, ask, s -> { }, stop, until).text()); }
                catch (IllegalArgumentException e) { return Result.of("sift", ask.from(), e.getMessage()); }
            }
        };
    }

    // ---- patents: the pool, the abstracts, the claims

    /** The literal call a small model copies: a class, the years, and each feature as the words that would name it, in more than one language; another invention than any test ask. */
    static final String PATENTS_EXAMPLE = "{\"classes\": [\"A47G19/22\"], \"from_year\": 2005, \"to_year\": 2015, \"features\": [\"lid, cover, cap, Deckel, 蓋\", \"straw, drinking tube, Trinkhalm, ストロー\", \"changes colour with heat, thermochromic, colour-changing, 感温\"]}";
    static final String PATENT_ABSTRACTS_EXAMPLE = "{\"from\": \"2026-11-05-0a1b2c3d4e5f6071\"}";
    static final String PATENT_CLAIMS_EXAMPLE = "{\"from\": \"2026-11-05-0a1b2c3d4e5f6071\"}";

    /**
     * When a step of a run must end: the earliest of the run's deadline, the round's and the worker's own time, each less what is kept
     * for what follows, so that the worker still has turns for its notes when the step comes back; 0 when none is set.
     */
    static long endBy(Researcher.Budget budget, long keepMs) {
        long now = System.currentTimeMillis(), until = 0;
        if (budget != null && budget.deadlineMs() > 0) until = now + (long) (budget.minutesLeftForWorkers() * 60_000L) - keepMs;
        if (budget != null && budget.roundDeadlineMs() > 0) until = until == 0 ? budget.roundDeadlineMs() - keepMs : Math.min(until, budget.roundDeadlineMs() - keepMs);
        long worker = workerUntil();
        if (worker > 0) until = until == 0 ? worker - keepMs : Math.min(until, worker - keepMs);
        return until == 0 ? 0 : Math.max(now + 2 * 60_000L, until);
    }

    static Instrument patents(LibraryStore store) {
        return new Instrument() {
            @Override public String name() { return "patents"; }
            @Override public String words() {
                return "The pool for a question of prior art, whether something like an invention was already filed or granted: give the classification symbols the invention sits in (classes, each a CPC group with its slash, like A47G19/22), the years (from_year, to_year) and the invention's features (features), each a short phrase with the words and synonyms that would name it in a title or an abstract, in every language you can give, and the program enumerates for itself: every publication of each class in those years from the European Patent Office's own records, page by page to the end, the years split where the office counts more than the two thousand it gives of one query; it says the office's own count, keeps one line a patent family with the other members on it, counts which features each family's titles and abstracts name, drops the numbers already rejected (exclude), and hands back the pool ordered by more features named, then the rarer feature, then the older, with no popularity in it: no citation count, no family size, no engine's rank"
                        + " · the first move for prior art, once the class is known and before any patent is read: you give only the classes, the years and the features, never a query. " + Patents.HOW_TO_FIND_A_CLASS + " Then the pool goes to patent_abstracts by its key"
                        + " · a class of a thousand publications takes two to five minutes and about ten megabytes of the office's free four gigabytes a week; every page is kept, so page 2 and the reading fetch nothing, and a call the time cut goes on with page=2; it needs the office's free key. Example: " + PATENTS_EXAMPLE + ".";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("classes!", "array", "from_year", "integer", "to_year", "integer", "features!", "array", "exclude", "array", "page", "integer"); }
            @Override public String example() { return PATENTS_EXAMPLE; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                if (PATENTS != null) return Result.of("patents", "", PATENTS.execute(args));   // a test's stand-in
                Patents.Ask ask = Patents.Ask.of(args);
                String address = String.join(", ", ask.classes());
                if (ask.classes().isEmpty()) return Result.of("patents", "", "Give the classification symbols the invention sits in (classes), each a group with its slash, and its features. " + Patents.HOW_TO_FIND_A_CLASS + " Example: " + PATENTS_EXAMPLE);
                if (ask.features().isEmpty()) return Result.of("patents", address, "Give the invention's features (features), each a short phrase with the words and synonyms that would name it in a title or an abstract, in every language you can give: the pool is ordered by them and read for them. Example: " + PATENTS_EXAMPLE);
                long until = System.currentTimeMillis() + Patents.ENUMERATE_MS, end = endBy(budget, Patents.KEEP_FOR_READING_MS);
                if (end > 0) until = Math.min(until, end);
                Patents.Result r = Patents.run(store, ask, s -> { }, stop, until);
                pool(r.key());
                return Result.of("patents", address, r.text());
            }
        };
    }

    static Instrument patentAbstracts(LibraryStore store) {
        return new Instrument() {
            @Override public String name() { return "patent_abstracts"; }
            @Override public String words() {
                return "The cheap reading between a pool of patents and their claims: the model reads the abstract of every family in the pool, best fit first, twenty to a call, and says of each feature yes, maybe or no; the families that pass come back best first, more features described before fewer, with the counts"
                        + " · after patents and before patent_claims: give the pool's key (from) and nothing else, since the features are the pool's"
                        + " · about ten seconds for twenty abstracts on a 27B, so a pool of a thousand families is read in under ten minutes; it stops cleanly at the run's deadline, says how many were not read, and the same call with page=2 goes on from there; nothing is read twice; one reading runs in a library at a time. Example: " + PATENT_ABSTRACTS_EXAMPLE + ".";
            }
            // no limit on the table: the time bounds a run's reading, and a model offered one read five families and stopped (the live run of 2026-11-06)
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("from", "string", "page", "integer"); }
            @Override public String example() { return PATENT_ABSTRACTS_EXAMPLE; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                if (PATENT_ABSTRACTS != null) return Result.of("patent_abstracts", "", PATENT_ABSTRACTS.execute(args));   // a test's stand-in
                PatentReading.AbstractsAsk ask = PatentReading.AbstractsAsk.of(args);
                PatentReading.Abstracts r = PatentReading.abstracts(store, ask, drive(), s -> { }, stop, endBy(budget, PatentReading.KEEP_FOR_CLAIMS_MS));
                pool(r.key());
                return Result.of("patent_abstracts", ask.from(), r.text());
            }
        };
    }

    static Instrument patentClaims(LibraryStore store) {
        return new Instrument() {
            @Override public String name() { return "patent_claims"; }
            @Override public String words() {
                return "The claims of patents from the office's own full text: for a pool (from), the claims of the families that pass by their abstracts, best first, and for each feature the claim that comes closest, quoted with its number and the office's link, or that none that was read meets it; for publications given by number (publications), each one's record, its classes and its claims; where the office holds no text for a publication (it holds none for the United States, China, Japan or Korea) the family member whose text it does hold is read, and the answer says which; where no member has a text there, the claims are read from Google Patents' copy of the office's record, at the publication's own address there, and the answer says so; a publication with no claims at either is read by its abstract and said to be an abstract; every quotation is kept only when its words stand in the text as read, and a status worded on Google's page is given as Google's own reading"
                        + " · after patent_abstracts, for the evidence a prior-art answer rests on, and whenever the question is what a numbered patent claims; claims are read here, from the office and from Google Patents' copy alike, and never by opening a patent's page yourself"
                        + " · about twenty seconds a family, and a page of Google Patents every three seconds at most; it stops cleanly at the run's deadline, says what was not read, and the same call with page=2 goes on; nothing is read or fetched twice. Example: " + PATENT_CLAIMS_EXAMPLE + "; for one patent by its number: {\"publications\": [\"EP1000000A1\"]}.";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("from", "string", "publications", "array", "features", "array", "page", "integer"); }
            @Override public String example() { return PATENT_CLAIMS_EXAMPLE; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                if (PATENT_CLAIMS != null) return Result.of("patent_claims", "", PATENT_CLAIMS.execute(args));   // a test's stand-in
                PatentReading.ClaimsAsk ask = PatentReading.ClaimsAsk.of(args);
                PatentReading.Claims r = PatentReading.claims(store, ask, drive(), s -> { }, stop, endBy(budget, PatentReading.KEEP_FOR_NOTES_MS));
                pool(r.key());
                return Result.of("patent_claims", ask.from(), r.text());
            }
        };
    }

    // ---- records

    static Instrument records(LibraryStore store) {
        return new Instrument() {
            @Override public String name() { return "records"; }
            @Override public String words() {
                return "A home's public records by its street address in the United States: the assessor's values, the sales, the permits, from the city's and the county's own data, the state's layer or the library's readers, and where none answers, where the county keeps them"
                        + " · after a home is named and its figures matter · seconds to a minute; a county looked up for the first time may take longer.";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("address!", "string"); }
            @Override public String example() { return "{\"address\": \"1 City Hall Square, Boston, MA\"}"; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                String address = args.path("address").asText("").strip();
                if (address.isEmpty()) return Result.of("records", "", "Give the street address, with the city and the state.");
                return Result.of("records", address, RECORDS.read(store, address));
            }
        };
    }

    static String recordsLive(LibraryStore store, String address) {
        String[] parts = address.split("\\s*,\\s*");
        if (parts.length < 3) return "Give the address as street, city, state: \"901 Bagby St, Houston, TX\".";
        String street = parts[0], city = parts[1], stateZip = parts[2].strip();
        String state = stateZip.split("\\s+")[0].toUpperCase(Locale.ROOT);
        HomeSensors.Placed p = HomeSensors.place(street, city, state);
        String st = HomeSensors.street(street); int number = HomeSensors.houseNumber(street);
        HomeRecords.Found f = HomeRecords.find(store, city, state, p == null ? "" : p.county(), p == null ? 0 : p.lat(), p == null ? 0 : p.lon(), st, number, p == null ? "" : p.zip(), false);
        StringBuilder b = new StringBuilder(HomeRecordsLine.line(city, state, p == null ? "" : p.county(), "", f.sales(), f.permits(), f.looked(), f.lookup(), f.mapFile()));
        if (f.sales() != null) for (String l : HomeRecords.prices(f.sales(), st, number, city, p == null ? "" : p.zip())) b.append("\n  ").append(l);
        if (f.permits() != null) for (String l : HomeRecords.permits(f.permits(), st, number, city)) b.append("\n  ").append(l);
        return b.toString();
    }

    // ---- homes

    static Instrument homes(LibraryStore store) {
        return new Instrument() {
            @Override public String name() { return "homes"; }
            @Override public String words() {
                return "The home search: say where, whether to buy or rent, the budget, the rooms and how far, in words; the library makes the cut and files the search as a run it follows: it reads every source it can (RentCast with a key, the engines' leads, the agents' own sites from the map), ranks what fits in tiers with a link on each, files a report of the fits and keeps the search by name"
                        + " · when the person is looking for a home to buy or rent · the run takes minutes; RentCast calls count against its monthly allowance when a key is set.";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("words!", "string", "name", "string"); }
            @Override public String example() { return "{\"words\": \"a house to buy in Cambridge, Massachusetts, three to four bedrooms, under $1.8 million\"}"; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                String words = args.path("words").asText("").strip();
                if (words.isEmpty()) return Result.of("homes", "", "Say what home is wanted: where, buy or rent, the budget, the rooms.");
                String name = args.path("name").asText("").strip();
                if (name.isEmpty()) name = Acquisitions.compress(words, 40);
                return Result.of("homes", name, HOMES.run(store, name, words, stop));
            }
        };
    }

    static String homesLive(LibraryStore store, String name, String words, BooleanSupplier stop) throws Exception {
        Home.Search s;
        try { s = Home.search(store, name); } catch (RuntimeException | IOException notYet) { s = Home.start(store, name); }
        if (s == null) s = Home.start(store, name);
        String told = Home.tell(store, s, words);
        // the search is filed as a run the chat follows on its strip, never run inside a turn: minutes of sources, and the service's own ledger
        String filed = HomeFind.file(store, s, Patrons.Patron.PERSON.did());
        return (told.isBlank() ? "" : told + "\n") + filed + "\nThe search \"" + s.name() + "\" is kept; when the run lands, the Home page shows its listings in tiers with a link on each, and a report of the fits is filed on Past.";
    }

    // ---- board

    static Instrument board() {
        return new Instrument() {
            @Override public String name() { return "board"; }
            @Override public String words() {
                return "An employer's or an institution's own careers board, read whole, and the openings on it, found from its site or its hiring system; words, the title words wanted (any of them), keeps the openings whose title holds one and counts the rest, so a board of hundreds is cut by the program, never by its first page"
                        + " · when a company, a hospital, a university or a public body is named and its openings matter · seconds to a minute each.";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("employer!", "string", "town", "string", "words", "array"); }
            @Override public String example() { return "{\"employer\": \"Massachusetts General Hospital\", \"town\": \"Boston, MA\", \"words\": [\"nurse\", \"nursing\"]}"; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                String employer = args.path("employer").asText("").strip();
                if (employer.isEmpty()) return Result.of("board", "", "Name the employer.");
                String town = args.path("town").asText("").strip();
                List<String> words = new ArrayList<>();
                for (JsonNode w : args.path("words")) if (w.isTextual() && !w.asText().isBlank()) words.add(w.asText().strip());
                if (args.path("words").isTextual() && !args.path("words").asText().isBlank()) words.add(args.path("words").asText().strip());   // a small model writes one word as text
                return Result.of("board", employer, BOARD.read(employer, town.isEmpty() ? List.of() : List.of(town), words));
            }
        };
    }

    /** How many of a board's openings are listed in full; the rest are counted, and {@code words} cuts the board to the titles wanted. */
    static final int BOARD_SHOWN = 40;

    static String boardLive(String employer, List<String> towns, List<String> words) {
        CareerSources.Found f = CareerPortals.inTowns(CareerPortals.find(employer, towns, List.of(), towns), towns);   // the board whole, cut to the towns as the protocol's own op cuts it
        if (f == null || f.feed() == null) return "No careers board of " + employer + " answered.";
        return boardText(employer, f.openings(), f.note(), towns, words);
    }

    /**
     * The board's openings as the model reads them: those whose title holds one of the words first and in full, then the count of the rest;
     * with no words, the openings as the board lists them up to {@link #BOARD_SHOWN}, the rest counted, and the word that cuts them named.
     */
    static String boardText(String employer, List<CareerSources.Opening> openings, String note, List<String> towns, List<String> words) {
        List<CareerSources.Opening> kept = new ArrayList<>(), rest = new ArrayList<>();
        for (CareerSources.Opening o : openings) { boolean hit = false; for (String w : words) if (CareerCut.has(o.title(), w)) { hit = true; break; } (words.isEmpty() || hit ? kept : rest).add(o); }
        StringBuilder b = new StringBuilder(employer + ": " + openings.size() + " opening(s) on its board" + (towns.isEmpty() ? "" : " in " + String.join(", ", towns))
                + (words.isEmpty() ? "" : "; " + kept.size() + " with " + String.join(" or ", words) + " in the title, listed here; the other " + rest.size() + " are not of those words")
                + (note == null || note.isBlank() ? "" : " (" + note + ")") + ".");
        int n = 0;
        for (CareerSources.Opening o : kept) {
            if (n++ >= BOARD_SHOWN) { b.append("\n  … and ").append(kept.size() - BOARD_SHOWN).append(" more; give words to cut the board to the titles wanted"); break; }
            b.append("\n  ").append(o.title()).append(o.location() == null || o.location().isBlank() ? "" : " · " + o.location()).append(o.remote() ? " · remote" : "").append(o.posted() == null || o.posted().isBlank() ? "" : " · posted " + o.posted()).append(o.url() == null || o.url().isBlank() ? "" : " · " + o.url());
        }
        if (kept.isEmpty() && !words.isEmpty()) b.append("\n  none of the ").append(openings.size()).append(" titles holds ").append(String.join(" or ", words)).append("; ask again with other words, or with none to read the board as it lists them");
        return b.toString();
    }

    // ---- dossier

    static Instrument dossier(LibraryStore store) {
        return new Instrument() {
            @Override public String name() { return "dossier"; }
            @Override public String words() {
                return "The library's own dossier on a person or an organisation: everything it holds on them, by section, with each claim's source class"
                        + " · when the ask is about who or what someone or something is, before any web research · seconds; to build it from the web, pursue the dossier goal.";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("subject!", "string"); }
            @Override public String example() { return "{\"subject\": \"Verkada\"}"; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                String subject = args.path("subject").asText("").strip();
                if (subject.isEmpty()) return Result.of("dossier", "", "Name the person or the organisation.");
                ObjectNode d = Dossier.of(store, subject);
                String md = d.path("markdown").asText("");
                if (md.isBlank()) md = d.path("summary").asText(d.toString());
                return Result.of("dossier", subject, md);
            }
        };
    }

    // ---- who

    static Instrument who(LibraryStore store) {
        return new Instrument() {
            @Override public String name() { return "who"; }
            @Override public String words() {
                return "Who is who in the family: a name read against the people the library knows, the lines and the places · when a person's name is ambiguous or a relation is asked · seconds.";
            }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("person!", "string", "op", "string"); }
            @Override public String example() { return "{\"person\": \"the grandfather on the mother's side\"}"; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception {
                ObjectNode a = M.createObjectNode();
                a.put("person", args.path("person").asText("")); a.put("op", args.path("op").asText("read"));
                ObjectNode r = new LibraryProtocol(store).who(a);   // the module answers through its profile hook
                return Result.of("who", args.path("person").asText(""), r == null ? "The family module answered nothing for that." : r.path("summary").asText(r.toString()));
            }
        };
    }

    // ---- shelf

    static Instrument shelf(LibraryStore store) {
        Tool t = new Researcher.ShelfSearchTool(store, List.of());
        return new Instrument() {
            @Override public String name() { return "shelf"; }
            @Override public String words() { return "What the library already holds on a query: entries, claims and reports by their words · before any search outside, and again after · instant."; }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("query!", "string", "limit", "integer"); }
            @Override public String example() { return "{\"query\": \"canvas tote bag\"}"; }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) throws Exception { return Result.of("shelf", "", t.execute(args)); }
        };
    }
}
