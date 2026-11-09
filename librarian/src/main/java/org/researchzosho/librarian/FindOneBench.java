package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.tools.LookTool;
import org.researchzosho.tools.WebSearchTool;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The find-one bench (FINDONEBENCH.md): cases from the public record where a person described a video they remembered, without
 * its title or channel, and later confirmed which one it was; the pipeline's stages run on each, as the run would run them, and
 * the artefacts are kept so the scoring is offline. A dev half and a holdout half, drawn by subject with a fixed seed: a change to
 * candidates, sift or look is judged on the dev half and reported on the holdout, whose answers live in a sealed file the runner
 * alone reads. The one question the pipeline was built on is not a case. Nothing here is in the help: a bench, not a thing a
 * library does for its reader.
 */
public final class FindOneBench {
    private FindOneBench() { }
    static final ObjectMapper M = new ObjectMapper();

    /** The seed the split is drawn with; the two sets. */
    public static final long SEED = 20261105L;
    public static final String DEV = "dev", HOLDOUT = "holdout";
    /**
     * The stages: the pool, then the sift over it, then the looks at the survivors and what the looks name; and, apart from those, the
     * pool from other people's words alone, which takes minutes where the whole pool takes a quarter of an hour.
     */
    public static final String CANDIDATES = "candidates", SIFT = "sift", FULL = "full", WEB = "web";
    /** The files beside the cases, as the repository keeps them. */
    public static final String CASES_FILE = "cases.jsonl", SEALED_FILE = "holdout-answers.json";
    /**
     * Whether the bench's web search is the library's own, with whatever index the library holds a key for, in place of the keyless one:
     * only the command's {@code --web library} sets it, since such a run spends a paid index's allowance.
     */
    public static volatile boolean libraryWeb = false;
    /** The minutes a case's enumeration and its sift get when the command says nothing: what a run gives them. */
    public static final int MINUTES_DEFAULT = 15, SIFT_MINUTES_DEFAULT = 20, LOOKS_DEFAULT = LookTool.MANY;

    /**
     * One case: the person's description verbatim (untrusted text, data for the pipeline, never a rule), the day they posted it, the
     * subject class, the language of the answer, where it came from, and the answer's video id for a dev case; "" for a holdout case,
     * whose answer is in the sealed file. A set may also say, for each case, the band the answer's views fell in and the band its channel's
     * subscribers fell in when the case was picked ("" where the set does not say): the band alone, never the count, so the results can
     * be read by how widely the video was seen without the case naming it.
     */
    public record Case(String id, String set, String subject, String language, String posted, String source, String description, String answer, String views, String subscribers) {
        public Case(String id, String set, String subject, String language, String posted, String source, String description, String answer) { this(id, set, subject, language, posted, source, description, answer, "", ""); }
        public boolean dev() { return DEV.equals(set); }
        ObjectNode json(boolean withAnswer) {
            ObjectNode o = M.createObjectNode().put("id", id).put("set", set).put("subject", subject).put("language", language).put("posted", posted).put("source", source).put("description", description);
            if (!views.isEmpty()) o.put("views", views);
            if (!subscribers.isEmpty()) o.put("subscribers", subscribers);
            if (withAnswer && !answer.isEmpty()) o.put("answer", answer);
            return o;
        }
        static Case of(JsonNode j) {
            return new Case(j.path("id").asText("").strip(), j.path("set").asText("").strip().toLowerCase(Locale.ROOT), j.path("subject").asText("").strip().toLowerCase(Locale.ROOT), j.path("language").asText("").strip(),
                    j.path("posted").asText("").strip(), j.path("source").asText("").strip(), j.path("description").asText(""), j.path("answer").asText("").strip(),
                    j.path("views").asText("").strip().toLowerCase(Locale.ROOT), j.path("subscribers").asText("").strip().toLowerCase(Locale.ROOT));
        }
    }

    /** The cases of a file, one a line; a blank line or a line beginning with # is skipped. */
    public static List<Case> load(Path file) throws IOException {
        List<Case> out = new ArrayList<>();
        int n = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            n++;
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            try { out.add(Case.of(M.readTree(l))); } catch (IOException e) { throw new IOException("line " + n + " of " + file.getFileName() + " is not a case: " + e.getMessage()); }
        }
        return out;
    }

    /** The sealed answers: case id to video id. The file is read by the runner and the scorer, never by a person. */
    public static Map<String, String> sealed(Path file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        JsonNode j = M.readTree(Files.readString(file, StandardCharsets.UTF_8));
        JsonNode answers = j.path("answers");
        if (answers.isTextual()) answers = M.readTree(new String(Base64.getDecoder().decode(answers.asText()), StandardCharsets.UTF_8));
        answers.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText("")));
        return out;
    }

    /** The answers of a set: a dev case's own, a holdout case's from the sealed file. */
    public static Map<String, String> answers(List<Case> cases, Map<String, String> sealedAnswers) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Case c : cases) out.put(c.id(), c.dev() ? c.answer() : sealedAnswers.getOrDefault(c.id(), ""));
        return out;
    }

    /** What is wrong with a set of cases and its sealed file: ids used twice, a set that is neither, a dev case without an answer or a holdout case with one, a description too short, the sealed file not matching the holdout. Empty when all is well. */
    public static List<String> check(List<Case> cases, Map<String, String> sealedAnswers) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (Case c : cases) {
            ids.merge(c.id(), 1, Integer::sum);
            if (!c.id().matches("[A-Za-z0-9_-]+")) out.add("the id " + c.id() + " is not a plain word");
            if (!c.set().equals(DEV) && !c.set().equals(HOLDOUT)) out.add(c.id() + " is in the set " + c.set() + ", which is neither dev nor holdout");
            if (c.subject().isBlank()) out.add(c.id() + " has no subject");
            if (c.language().isBlank()) out.add(c.id() + " has no language");
            if (!c.posted().matches("\\d{4}-\\d{2}-\\d{2}")) out.add(c.id() + " has no posted day");
            if (c.description().strip().length() < 40) out.add(c.id() + " has a description shorter than forty characters");
            if (c.dev() && !c.answer().matches("[A-Za-z0-9_-]{11}")) out.add(c.id() + " is a dev case without a video id as its answer");
            if (!c.dev() && !c.answer().isEmpty()) out.add(c.id() + " is a holdout case whose answer is written in the open");
            if (!c.dev() && !sealedAnswers.getOrDefault(c.id(), "").matches("[A-Za-z0-9_-]{11}")) out.add(c.id() + " is a holdout case with no answer in the sealed file");
        }
        ids.forEach((id, n) -> { if (n > 1) out.add("the id " + id + " is used " + n + " times"); });
        for (String id : sealedAnswers.keySet()) if (cases.stream().noneMatch(c -> c.id().equals(id) && !c.dev())) out.add("the sealed file names " + id + ", which is no holdout case");
        return out;
    }

    /**
     * The split, by subject and by whether the answer is in English: within each subject the cases in id order are shuffled with the seed
     * and half go to dev, half to holdout, a subject with an odd count giving its extra case to dev and the next such subject to holdout;
     * the cases whose answer is in another language are placed first, dealt to the two halves in turn across the subjects, so both halves
     * hold some (a split by subject alone put four of the five on one side). The subjects in name order, one random source for all, so
     * the same cases and seed give the same split every time, on every machine.
     */
    public static Map<String, String> split(List<Case> cases, long seed) {
        Map<String, List<Case>> bySubject = new TreeMap<>();
        for (Case c : cases) bySubject.computeIfAbsent(c.subject(), k -> new ArrayList<>()).add(c);
        Random rnd = new Random(seed);
        Map<String, String> out = new LinkedHashMap<>();
        boolean devLarger = true, otherToDev = true;
        for (List<Case> cs : bySubject.values()) {
            cs.sort((x, y) -> x.id().compareTo(y.id()));
            Collections.shuffle(cs, rnd);
            int dev = cs.size() / 2;
            if (cs.size() % 2 == 1) { if (devLarger) dev++; devLarger = !devLarger; }
            int holdout = cs.size() - dev, d = 0, h = 0;
            List<Case> order = new ArrayList<>();
            for (Case c : cs) if (!english(c)) order.add(c);
            for (Case c : cs) if (english(c)) order.add(c);
            for (Case c : order) {
                boolean toDev = english(c) ? d < dev : otherToDev;
                if (toDev && d >= dev) toDev = false;
                else if (!toDev && h >= holdout) toDev = true;
                if (!english(c)) otherToDev = !toDev;
                if (toDev) d++; else h++;
                out.put(c.id(), toDev ? DEV : HOLDOUT);
            }
        }
        return out;
    }

    static boolean english(Case c) { return c.language().toLowerCase(Locale.ROOT).startsWith("en"); }

    /**
     * The cases file and the sealed file from a file of picked cases with every answer in the open: the split drawn, the dev answers
     * left in the open, the holdout answers moved to the sealed file (base64, so that a glance at it shows nothing). The picked file
     * stays out of the repository.
     */
    public static void seal(Path picked, Path dir) throws IOException {
        List<Case> all = load(picked);
        Map<String, String> sets = split(all, SEED);
        StringBuilder cases = new StringBuilder();
        ObjectNode answers = M.createObjectNode();
        for (Case c : all) {
            String set = sets.get(c.id());
            Case placed = new Case(c.id(), set, c.subject(), c.language(), c.posted(), c.source(), c.description(), set.equals(DEV) ? c.answer() : "", c.views(), c.subscribers());
            cases.append(placed.json(true)).append('\n');
            if (set.equals(HOLDOUT)) answers.put(c.id(), c.answer());
        }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(CASES_FILE), cases.toString(), StandardCharsets.UTF_8);
        ObjectNode sealedJson = M.createObjectNode().put("note", "The holdout half's answers, sealed: the runner and the scorer read this file; nobody reads it by hand, and nothing is tuned on it.")
                .put("seed", SEED).put("answers", Base64.getEncoder().encodeToString(answers.toString().getBytes(StandardCharsets.UTF_8)));
        Files.writeString(dir.resolve(SEALED_FILE), M.writerWithDefaultPrettyPrinter().writeValueAsString(sealedJson) + "\n", StandardCharsets.UTF_8);
    }

    // ---- the runner

    /**
     * What a run is given: the drive and the model, where the artefacts go, the stage, the minutes a case's enumeration and sift get, how
     * many survivors the looks take, and whether a pool already under the out folder is taken as it is rather than made again, so the
     * sift and the looks are measured on the same pool as the candidate step was.
     */
    public record Settings(String drive, String model, Path out, String stage, int minutes, int siftMinutes, int looks, boolean reuse) {
        public Settings(String drive, String model, Path out, String stage, int minutes, int siftMinutes, int looks) { this(drive, model, out, stage, minutes, siftMinutes, looks, false); }
    }

    /** The naming step: what the run's brain gives candidates when it reads the person's words; a test stands in. */
    public interface Namer { ObjectNode name(String description) throws Exception; }

    /** The pieces of the pipeline the runner drives, each behind a seam so a test runs the runner with none of the network. */
    public interface Pipeline {
        /** A case begins: whatever the pipeline keeps per case, the guard above all, is set for it. */
        default void begin(Case c) { }
        /** What the guard left out for the case that last began; null when the pipeline has none. */
        default ObjectNode guarded() { return null; }
        /** The brain's candidates call for the description: its arguments, and what it said. */
        ObjectNode name(String description) throws Exception;
        /** The pool for those arguments, kept under the library. */
        Candidates.Result candidates(LibraryStore store, Candidates.Ask ask, Consumer<String> progress, long untilMs);
        /** The sift over the kept pool. */
        Sift.Result sift(LibraryStore store, Sift.Ask ask, Consumer<String> progress, long untilMs);
        /** The look at one video for the person's words. */
        VideoRecall.Look look(LibraryStore store, String videoId, String what) throws IOException;
    }

    /** The live pipeline on a drive: the same brain, the same instrument words and the same goal words the run gives its worker. */
    public static Pipeline live(Settings s) {
        Researcher.Drive drive = Researcher.drive(s.drive(), s.model());
        Candidates.drives = () -> drive;
        // the web is asked through what costs nobody anything, the library's own SearXNG and then the built-in fallback, whatever keys the
        // library holds: Serper, SerpApi and Brave are paid for, and the bench spends nobody's allowance. Every result and every page passes
        // the guard of the case that runs, since the cases' own threads are on the web with their answers
        Guard[] guard = {null};
        boolean paid = libraryWeb;
        Candidates.WEB = query -> { WebSearchTool search = new WebSearchTool().upTo(Candidates.WEB_ROWS); if (!paid) search.keyless(); String out = search.execute(M.createObjectNode().put("query", query).put("limit", Candidates.WEB_ROWS)); Guard g = guard[0]; return g == null ? out : g.results(out); };
        Described.Pages reader = Described.PAGES;
        Described.PAGES = url -> { String html = reader.read(url); Guard g = guard[0]; return g == null ? html : g.page(url, html); };
        VideoRecall.VISION = (image, ask) -> describe(s.drive(), s.model(), image, ask);
        VideoRecall.WORDS = ask -> {
            DriveClient c = new DriveClient(s.drive(), s.model());
            ArrayNode msgs = M.createArrayNode();
            msgs.addObject().put("role", "user").put("content", ask);
            try { return c.classify(msgs, 400); } catch (RuntimeException e) { return ""; }
        };
        return new Pipeline() {
            @Override public void begin(Case c) { guard[0] = new Guard(c); }
            @Override public ObjectNode guarded() { return guard[0] == null ? null : guard[0].json(); }
            @Override public ObjectNode name(String description) {
                LibraryStore none = null;
                Instrument candidates = Instruments.candidates(none);
                List<Instrument> table = List.of(candidates);
                Brain brain = new Brain(drive, null, s.model(), null, Brain.tierOf(s.model()), o -> { });
                ArrayNode tools = M.createArrayNode();
                tools.add(Brain.fn(candidates.name(), candidates.words(), candidates.schema(M)));
                Brain.Caller caller = (h, t, n) -> drive.chat(h, t, n, t.isEmpty() ? "none" : "required");
                Goal goal = Goals.named("choose");
                ArrayNode history = M.createArrayNode();
                history.addObject().put("role", "system").put("content", brain.preamble(table) + (goal == null ? "" : "\n\nTHIS RUN SERVES THE GOAL " + goal.name() + ": " + goal.words() + (goal.decisionRules().isBlank() ? "" : " " + goal.decisionRules())));
                history.addObject().put("role", "user").put("content", description + "\n\nTurns left: 20; minutes left: 60. Decide the next move.");
                Brain.Context ctx = new Brain.Context(new Researcher.Ask(description, "quick", 20, List.of()), goal == null ? "choose" : goal.name(), List.of(), table, new Researcher.Budget(20, 60), List.of(), history);
                // The room a run's worker has for its turn. This call once had 768 tokens of its own, and the model's thinking alone ran past that:
                // three cases of the first eight came back as a call with no arguments (2026-11-06), a fault of the bench and not of the model.
                int limit = Researcher.workerOutBudget(history, drive.contextWindow());
                DriveClient.forgetUsage();
                Brain.Decided d = brain.decide(ctx, Brain.Weight.HEAVY, caller, tools, limit, Set.of(), true);
                long[] used = DriveClient.lastUsage();
                ObjectNode out = M.createObjectNode();
                out.put("limit", limit);
                if (used != null) out.put("out_tokens", used[1]);
                String said = d.assistant() == null ? "" : d.assistant().path("content").asText("");
                out.put("said", said.length() > 600 ? said.substring(0, 600) + "…" : said);
                if (d.first() instanceof Brain.Run r && r.instrument().equals(candidates.name())) { out.put("called", candidates.name()); out.set("args", r.args()); }
                else out.put("called", d.first() instanceof Brain.Run r2 ? r2.instrument() : d.first().getClass().getSimpleName().toLowerCase(Locale.ROOT));
                return out;
            }
            @Override public Candidates.Result candidates(LibraryStore store, Candidates.Ask ask, Consumer<String> progress, long untilMs) {
                return s.stage().equals(WEB) ? Candidates.describedAlone(store, ask, progress, () -> false, untilMs) : Candidates.run(store, ask, progress, () -> false, untilMs);
            }
            @Override public Sift.Result sift(LibraryStore store, Sift.Ask ask, Consumer<String> progress, long untilMs) { return Sift.run(store, ask, progress, () -> false, untilMs); }
            @Override public VideoRecall.Look look(LibraryStore store, String videoId, String what) throws IOException { return LookTool.LOOKER.look(store, videoId, what, true, () -> false); }
        };
    }

    /** A picture put to the model at the drive, as the library's own reader does it. */
    static String describe(String driveUrl, String model, byte[] image, String ask) {
        DriveClient drive = new DriveClient(driveUrl, model);
        ArrayNode msgs = M.createArrayNode();
        ArrayNode content = msgs.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", ask);
        boolean png = image.length > 4 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G';
        content.addObject().put("type", "image_url").putObject("image_url").put("url", "data:image/" + (png ? "png" : "jpeg") + ";base64," + Base64.getEncoder().encodeToString(image));
        try { return drive.classify(msgs, 400); } catch (RuntimeException e) { return ""; }
    }

    /**
     * The guard against the bench's own leak. The cases come from r/tipofmytongue, and a solved thread there holds its answer: a web
     * search for a case's words can find the thread, a mirror of it, or a page that quotes the post, and a video taken from such a page
     * is the answer read off the answer key. So in the bench every search result and every page read passes here first, and is left out
     * when its address is a tipofmytongue thread on any host or the case's own thread, when its words carry the subreddit's name or its
     * tag, or when they quote the case's post ({@value #QUOTE} words of it in a row). What was left out is counted and kept with the run,
     * with the videos a guarded page held, so a reader sees what the guard did. A real library has no guard: there the person's own
     * earlier question is a page like any other.
     */
    public static final class Guard {
        /**
         * A page or a result quotes the post when it holds this many of its words in a row. Six took out honest pages: a stock picture's
         * caption shared six plain words with a made-up post the day the guard was first tried; a copy of a post shares whole sentences.
         */
        public static final int QUOTE = 8;
        static final int LISTED = 80;
        private final String source;
        private final List<String> runs = new ArrayList<>();
        private int results, pages;
        private final List<ObjectNode> left = new ArrayList<>();

        public Guard(Case c) {
            source = c.source();
            String[] words = plain(c.description()).split(" ");
            for (int i = 0; i + QUOTE <= words.length; i++) runs.add(" " + String.join(" ", List.of(words).subList(i, i + QUOTE)) + " ");
        }

        /** A text as its words alone, in lower case, one space between them. */
        static String plain(String text) { return (text == null ? "" : text).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip(); }

        private static final Pattern POST = Pattern.compile("/comments/([a-z0-9]{4,10})(?:/|$)");

        /** The id of a thread in its address, "" when the address has none. */
        static String postId(String source) { Matcher m = POST.matcher(source == null ? "" : source.toLowerCase(Locale.ROOT)); return m.find() ? m.group(1) : ""; }

        /** Why an address is guarded, or null: a tipofmytongue thread on any host, or the case's own thread by its address or its id. */
        public static String byAddress(String url, String source) {
            if (url == null || url.isBlank()) return null;
            String u = url.toLowerCase(Locale.ROOT);
            try { u = URLDecoder.decode(u, StandardCharsets.UTF_8); } catch (RuntimeException ignored) { }
            if (u.contains("tipofmytongue") || u.contains("tip_of_my_tongue") || u.contains("tip-of-my-tongue")) return "a tipofmytongue thread";
            String src = source == null ? "" : source.toLowerCase(Locale.ROOT), id = postId(src);
            if (!id.isEmpty() && Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(id) + "([^a-z0-9]|$)").matcher(u).find()) return "the case's own thread";
            if (!src.isEmpty() && u.replaceAll("/+$", "").equals(src.replaceAll("/+$", ""))) return "the case's own thread";
            return null;
        }

        /** Why a text is guarded, or null: it carries the subreddit's name or its tag, or it quotes the case's post. */
        public String byText(String text) {
            if (text == null || text.isBlank()) return null;
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("tipofmytongue") || lower.contains("[tomt]") || lower.contains("tomt]") || lower.contains("[tomt")) return "the words of a tipofmytongue thread";
            String words = " " + plain(text) + " ";
            for (String run : runs) if (words.contains(run)) return "it quotes the case's post";
            return null;
        }

        private void leave(String where, String url, String why, List<String> videos) {
            if (where.equals("result")) results++; else pages++;
            if (left.size() >= LISTED) return;
            ObjectNode o = M.createObjectNode().put("where", where).put("url", url).put("why", why);
            if (!videos.isEmpty()) { ArrayNode v = o.putArray("videos"); for (String id : videos) v.add(id); }
            left.add(o);
        }

        /** A web search's text with the guarded results left out, written as the search tools write it; a text with no results comes back as it is. */
        public synchronized String results(String text) {
            List<Described.Row> rows = Described.rows(text);
            if (rows.isEmpty()) return text;
            List<Described.Row> kept = new ArrayList<>();
            for (Described.Row r : rows) {
                String why = byAddress(r.url(), source);
                if (why == null) why = byText(r.title() + " " + r.snippet());
                if (why == null) kept.add(r);
                else { String id = Described.videoAt(r.url()); leave("result", r.url(), why, id == null ? List.of() : List.of(id)); }
            }
            return "results, the guarded ones left out:\n" + Described.render(kept);
        }

        /** A page's HTML, or null when the page is guarded: its address, or its words. The videos a guarded page held are kept with what was left out. */
        public synchronized String page(String url, String html) {
            if (html == null) return null;
            Described.Harvest hv = Described.harvest(html);
            String why = byAddress(url, source);
            if (why == null) why = byText(hv.title() + " " + hv.text());
            if (why == null) return html;
            List<String> ids = new ArrayList<>(); for (Described.Linked v : hv.videos()) ids.add(v.id());
            leave("page", url, why, ids);
            return null;
        }

        public synchronized ObjectNode json() {
            ObjectNode o = M.createObjectNode().put("results_left_out", results).put("pages_left_out", pages);
            ArrayNode a = o.putArray("left_out"); for (ObjectNode x : left) a.add(x);
            return o;
        }
    }

    /** The folder of one case's artefacts. */
    public static Path caseDir(Path out, String set, String id) { return out.resolve(set).resolve(id); }

    /**
     * Every case of the set through the stages, each on a fresh scratch library under its own folder, the artefacts kept: the case
     * (never its answer), what the brain named, the pool as kept, the sift's result and its ledger, the looks, and a record of the run
     * with the time each stage took and any error. The runner never reads the answers: scoring is the scorer's, offline.
     */
    public static void run(List<Case> cases, Settings s, Pipeline p, Consumer<String> say) throws IOException {
        for (Case c : cases) {
            Path dir = caseDir(s.out(), c.set(), c.id());
            Files.createDirectories(dir);
            JsonNode earlier = s.reuse() && Files.isRegularFile(dir.resolve("run.json")) && Files.isRegularFile(dir.resolve("pool.json")) ? M.readTree(Files.readString(dir.resolve("run.json"), StandardCharsets.UTF_8)) : null;
            if (earlier != null && !earlier.path("candidates").path("key").asText("").isEmpty()) { reuse(c, s, p, dir, earlier, say); continue; }
            Files.writeString(dir.resolve("case.json"), c.json(false).toString() + "\n", StandardCharsets.UTF_8);
            p.begin(c);
            ObjectNode record = M.createObjectNode().put("id", c.id()).put("set", c.set()).put("stage", s.stage()).put("day", LocalDate.now().toString()).put("model", s.model()).put("drive", s.drive())
                    .put("keyless", true).put("web_index", false).put("web", libraryWeb ? "library" : "keyless").put("minutes", s.minutes()).put("sift_minutes", s.siftMinutes());
            Path lib = dir.resolve("lib");
            deleteTree(lib);
            LibraryStore store = new LibraryStore(lib);
            store.init();
            List<String> trace = new ArrayList<>();
            Consumer<String> progress = line -> { trace.add(line); say.accept("  " + c.id() + ": " + line); };
            long t0 = System.currentTimeMillis();
            say.accept(c.id() + " (" + c.subject() + "): naming the thing.");
            try {
                ObjectNode named = p.name(c.description());
                named.put("ms", System.currentTimeMillis() - t0);
                Files.writeString(dir.resolve("named.json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(named) + "\n", StandardCharsets.UTF_8);
                if (!named.has("args")) { record.put("error", "the brain did not call candidates; it chose " + named.path("called").asText("nothing")); finish(dir, record, trace, t0); continue; }
                Candidates.Ask ask = Candidates.Ask.of(named.path("args"));
                if (ask.things().isEmpty()) { record.put("error", cutAtItsLimit(named) ? "the brain's candidates call was cut at its limit of " + named.path("limit").asLong() + " tokens before it named a thing" : "the brain's candidates call named no thing"); finish(dir, record, trace, t0); continue; }
                say.accept(c.id() + ": the things named are " + String.join(", ", ask.things()) + "; enumerating for up to " + s.minutes() + " minutes.");
                long t1 = System.currentTimeMillis();
                Candidates.Result pool = p.candidates(store, ask, progress, t1 + s.minutes() * 60_000L);
                ObjectNode cs = record.putObject("candidates");
                cs.put("ms", System.currentTimeMillis() - t1).put("pool", pool.pool().size()).put("key", pool.key()).put("cut", pool.cut());
                ObjectNode by = cs.putObject("by_source"); pool.bySource().forEach(by::put);
                ArrayNode notes = cs.putArray("notes"); for (String n : pool.notes()) notes.add(n);
                // what the web was asked and what the guard left out, beside the counts: whether the web answered is the first thing a reader checks
                if (pool.described() != null) {
                    JsonNode d = pool.described();
                    cs.putObject("described").put("asked", d.path("asked").asInt()).put("answered", d.path("answered").asInt()).put("pages_named", d.path("pages_named").asInt()).put("pages_read", d.path("pages_read").asInt()).put("pages_linking", d.path("pages_linking").asInt()).put("found", d.path("found").asInt());
                    record.put("web_index", d.path("answered").asInt() > 0);
                }
                ObjectNode left = p.guarded();
                if (left != null) record.set("guard", left);
                Path kept = Candidates.poolFileByKey(store, pool.key());
                if (kept != null) Files.copy(kept, dir.resolve("pool.json"), StandardCopyOption.REPLACE_EXISTING);
                else Files.writeString(dir.resolve("pool.json"), poolJson(ask, pool).toString() + "\n", StandardCharsets.UTF_8);
                Files.writeString(dir.resolve("candidates.txt"), pool.text() + "\n", StandardCharsets.UTF_8);
                say.accept(c.id() + ": " + pool.pool().size() + " in the pool after " + (System.currentTimeMillis() - t1) / 60_000 + " minutes.");
                if (s.stage().equals(CANDIDATES) || s.stage().equals(WEB) || pool.pool().isEmpty()) { finish(dir, record, trace, t0); continue; }
                // the sift, given the person's own words and the pool's key, as the run gives it
                long t2 = System.currentTimeMillis();
                Sift.Result sifted = p.sift(store, new Sift.Ask(c.description(), pool.key(), List.of(), List.of(), 0, 1), progress, t2 + s.siftMinutes() * 60_000L);
                ObjectNode ss = record.putObject("sift");
                ss.put("ms", System.currentTimeMillis() - t2).put("reached", sifted.reached()).put("survivors", sifted.survivors().size()).put("thumbs_judged", sifted.thumbsJudged()).put("thumbs_kept", sifted.thumbsKept()).put("sheets_judged", sifted.sheetsJudged()).put("sheets_kept", sifted.sheetsKept());
                Files.writeString(dir.resolve("sift.json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(Sift.json(sifted)) + "\n", StandardCharsets.UTF_8);
                Path ledger = Sift.ledgerFile(store);
                if (Files.isRegularFile(ledger)) Files.copy(ledger, dir.resolve("judged.jsonl"), StandardCopyOption.REPLACE_EXISTING);
                say.accept(c.id() + ": sifted " + sifted.reached() + " of " + sifted.pool() + ", " + sifted.survivors().size() + " survivors, after " + (System.currentTimeMillis() - t2) / 60_000 + " minutes.");
                if (s.stage().equals(SIFT) || sifted.survivors().isEmpty()) { finish(dir, record, trace, t0); continue; }
                looks(c, s, p, dir, store, record, Sift.ids(sifted), say);   // the looks at the first survivors, each for every remembered thing at once
            } catch (Exception e) {
                record.put("error", "the run failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
                say.accept(c.id() + ": " + record.path("error").asText());
            }
            finish(dir, record, trace, t0);
        }
    }

    /**
     * A case run again from its kept pool: the pool file goes under a fresh scratch library as the candidate step kept it, the earlier
     * record's candidate counts stand, and the sift and the looks run from there. The named.json, pool.json and candidates.txt stay.
     */
    static void reuse(Case c, Settings s, Pipeline p, Path dir, JsonNode earlier, Consumer<String> say) throws IOException {
        ObjectNode record = M.createObjectNode().put("id", c.id()).put("set", c.set()).put("stage", s.stage()).put("day", LocalDate.now().toString()).put("model", s.model()).put("drive", s.drive())
                .put("keyless", true).put("web_index", earlier.path("web_index").asBoolean(false)).put("minutes", earlier.path("minutes").asInt(0)).put("sift_minutes", s.siftMinutes());
        ObjectNode cs = earlier.path("candidates").deepCopy(); cs.put("reused_from", earlier.path("day").asText("")); record.set("candidates", cs);
        if (earlier.path("guard").isObject()) record.set("guard", earlier.path("guard").deepCopy());
        String key = cs.path("key").asText("");
        Path lib = dir.resolve("lib");
        deleteTree(lib);
        LibraryStore store = new LibraryStore(lib);
        store.init();
        Path kept = Candidates.poolDir(store).resolve(key + ".json");
        Files.createDirectories(kept.getParent());
        Files.copy(dir.resolve("pool.json"), kept, StandardCopyOption.REPLACE_EXISTING);
        List<String> trace = new ArrayList<>();
        Consumer<String> progress = line -> { trace.add(line); say.accept("  " + c.id() + ": " + line); };
        long t0 = System.currentTimeMillis();
        say.accept(c.id() + " (" + c.subject() + "): the pool kept on " + cs.path("reused_from").asText() + " is taken as it is, " + cs.path("pool").asInt() + " candidates.");
        try {
            if (s.stage().equals(CANDIDATES) || s.stage().equals(WEB) || cs.path("pool").asInt() == 0) { finish(dir, record, trace, t0); return; }
            long t2 = System.currentTimeMillis();
            Sift.Result sifted = p.sift(store, new Sift.Ask(c.description(), key, List.of(), List.of(), 0, 1), progress, t2 + s.siftMinutes() * 60_000L);
            ObjectNode ss = record.putObject("sift");
            ss.put("ms", System.currentTimeMillis() - t2).put("reached", sifted.reached()).put("survivors", sifted.survivors().size()).put("thumbs_judged", sifted.thumbsJudged()).put("thumbs_kept", sifted.thumbsKept()).put("sheets_judged", sifted.sheetsJudged()).put("sheets_kept", sifted.sheetsKept());
            Files.writeString(dir.resolve("sift.json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(Sift.json(sifted)) + "\n", StandardCharsets.UTF_8);
            Path ledger = Sift.ledgerFile(store);
            if (Files.isRegularFile(ledger)) Files.copy(ledger, dir.resolve("judged.jsonl"), StandardCopyOption.REPLACE_EXISTING);
            say.accept(c.id() + ": sifted " + sifted.reached() + " of " + sifted.pool() + ", " + sifted.survivors().size() + " survivors, after " + (System.currentTimeMillis() - t2) / 60_000 + " minutes.");
            if (s.stage().equals(SIFT) || sifted.survivors().isEmpty()) { finish(dir, record, trace, t0); return; }
            looks(c, s, p, dir, store, record, Sift.ids(sifted), say);
        } catch (Exception e) {
            record.put("error", "the run failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            say.accept(c.id() + ": " + record.path("error").asText());
        }
        finish(dir, record, trace, t0);
    }

    /** The looks at the first survivors, each for every remembered thing at once, kept as looks.json. */
    static void looks(Case c, Settings s, Pipeline p, Path dir, LibraryStore store, ObjectNode record, List<String> ids, Consumer<String> say) throws IOException {
        long t3 = System.currentTimeMillis();
        ArrayNode looks = M.createArrayNode();
        for (String id : ids.subList(0, Math.min(s.looks(), ids.size()))) {
            ObjectNode l = looks.addObject().put("id", id);
            try {
                VideoRecall.Look look = p.look(store, id, c.description());
                ArrayNode answers = l.putArray("answers");
                for (VideoRecall.Answer a : look.answers()) answers.addObject().put("feature", a.feature()).put("verdict", a.verdict()).put("seconds", a.seconds()).put("why", a.why());
                l.put("found", look.answers().stream().filter(a -> a.verdict().equals("found")).count()).put("maybe", look.answers().stream().filter(a -> a.verdict().equals("maybe")).count()).put("title", look.title());
            } catch (IOException | RuntimeException e) { l.put("error", String.valueOf(e.getMessage())); }
        }
        ObjectNode ls = record.putObject("looks");
        ls.put("ms", System.currentTimeMillis() - t3).put("looked", looks.size());   // which video the looks would name is the scorer's to read off looks.json: the record never carries a video id
        Files.writeString(dir.resolve("looks.json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(looks) + "\n", StandardCharsets.UTF_8);
        say.accept(c.id() + ": looked at " + looks.size() + "; the look's best is " + (best(looks).isEmpty() ? "none" : best(looks)) + ".");
    }

    /** The video the looks would name: the most things found, then the most maybe, and at least one thing found; "" when none. */
    static String best(ArrayNode looks) {
        String best = ""; long bf = 0, bm = 0;
        for (JsonNode l : looks) {
            long f = l.path("found").asLong(0), m = l.path("maybe").asLong(0);
            if (f >= 1 && (f > bf || (f == bf && m > bm))) { best = l.path("id").asText(""); bf = f; bm = m; }
        }
        return best;
    }

    /** Whether the naming call spent everything it was allowed: such a call ends mid-thought with empty arguments, which is the bench's limit and not the model's answer. */
    static boolean cutAtItsLimit(JsonNode named) { return named.has("limit") && named.has("out_tokens") && named.path("out_tokens").asLong() >= named.path("limit").asLong(); }

    static void finish(Path dir, ObjectNode record, List<String> trace, long t0) throws IOException {
        record.put("ms", System.currentTimeMillis() - t0);
        Files.writeString(dir.resolve("run.json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(record) + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("trace.txt"), String.join("\n", trace) + (trace.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
        deleteTree(dir.resolve("lib"));   // the scratch library is working space: the pool file, the sift's result and its ledger were copied beside
    }

    /** A pool as the library keeps it, for a result that was not kept (a test's stand-in). */
    static ObjectNode poolJson(Candidates.Ask ask, Candidates.Result r) {
        ObjectNode o = M.createObjectNode();
        ArrayNode things = o.putArray("things"); for (String t : ask.things()) things.add(t);
        ArrayNode remembered = o.putArray("remembered"); for (String t : ask.remembered()) remembered.add(t);
        if (r.described() != null) o.set("described", r.described());
        o.put("key", r.key()).put("cut", r.cut());
        ObjectNode by = o.putObject("by_source"); r.bySource().forEach(by::put);
        ArrayNode notes = o.putArray("notes"); for (String n : r.notes()) notes.add(n);
        ArrayNode pool = o.putArray("pool"); for (Candidates.Hit h : r.pool()) pool.add(h.json());
        return o;
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) { s.sorted((a, b) -> b.compareTo(a)).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) { } }); }
    }

    // ---- the scorer, offline, from the artefacts

    /** What one case came to, read from its artefacts and the answer: where the answer sat at each stage. A crash is counted apart, never as a miss. */
    public record Scored(String id, String subject, String error, int poolSize, Map<String, Integer> bySource, boolean cut, int rank, String foundBy, List<String> things,
                         int reached, String thumbVerdict, String sheetVerdict, int survivors, int survivorRank, boolean looked, boolean named, long ms, String stage, String views, String subscribers,
                         String ledBy, int leftOut, boolean guardHeld, boolean guardedOnly, int asked, int answered) {
        /** A case scored without the web: no page led to its answer and no guard stood before it. */
        public Scored(String id, String subject, String error, int poolSize, Map<String, Integer> bySource, boolean cut, int rank, String foundBy, List<String> things,
                      int reached, String thumbVerdict, String sheetVerdict, int survivors, int survivorRank, boolean looked, boolean named, long ms, String stage, String views, String subscribers) {
            this(id, subject, error, poolSize, bySource, cut, rank, foundBy, things, reached, thumbVerdict, sheetVerdict, survivors, survivorRank, looked, named, ms, stage, views, subscribers, "", 0, false, false, 0, 0);
        }
        public boolean crashed() { return error != null && !error.isBlank(); }
        /** In the pool, and not there through a guarded page alone: an answer read off the case's own thread is no find. */
        public boolean inPool() { return rank > 0 && !guardedOnly; }
        public boolean survived() { return survivorRank > 0; }
        /** Whether the sift reached the answer: it was judged on its thumbnail. */
        public boolean sifted() { return !thumbVerdict.isEmpty(); }
    }

    /** Every case of a set's folder scored against its answer; a case folder without a run record is skipped. */
    public static List<Scored> score(Path setDir, Map<String, String> answers) throws IOException {
        List<Scored> out = new ArrayList<>();
        if (!Files.isDirectory(setDir)) return out;
        List<Path> dirs;
        try (var s = Files.list(setDir)) { dirs = s.filter(Files::isDirectory).sorted().toList(); }
        for (Path dir : dirs) {
            Path rec = dir.resolve("run.json");
            if (!Files.isRegularFile(rec)) continue;
            JsonNode run = M.readTree(Files.readString(rec, StandardCharsets.UTF_8));
            String id = run.path("id").asText(dir.getFileName().toString());
            String answer = answers.getOrDefault(id, "");
            JsonNode kase = Files.isRegularFile(dir.resolve("case.json")) ? M.readTree(Files.readString(dir.resolve("case.json"), StandardCharsets.UTF_8)) : M.createObjectNode();
            String subject = kase.path("subject").asText(""), source = kase.path("source").asText("");
            // the web and the guard, from the run's record: what was asked and answered, how much the guard left out, whether a page it left out held the answer
            int asked = run.path("candidates").path("described").path("asked").asInt(0), answered = run.path("candidates").path("described").path("answered").asInt(0);
            int leftOut = run.path("guard").path("results_left_out").asInt(0) + run.path("guard").path("pages_left_out").asInt(0);
            boolean guardHeld = false, guardedOnly = false;
            if (!answer.isEmpty()) for (JsonNode x : run.path("guard").path("left_out")) for (JsonNode v : x.path("videos")) if (v.asText("").equals(answer)) guardHeld = true;
            String ledBy = "";
            String error = run.path("error").asText("");
            int poolSize = 0, rank = 0, reached = run.path("sift").path("reached").asInt(0), survivors = run.path("sift").path("survivors").asInt(0), survivorRank = 0;
            boolean cut = run.path("candidates").path("cut").asBoolean(false);
            String foundBy = "", thumb = "", sheet = "";
            Map<String, Integer> by = new LinkedHashMap<>();
            List<String> things = new ArrayList<>();
            Path pool = dir.resolve("pool.json");
            if (Files.isRegularFile(pool)) {
                JsonNode p = M.readTree(Files.readString(pool, StandardCharsets.UTF_8));
                for (JsonNode t : p.path("things")) things.add(t.asText());
                p.path("by_source").properties().forEach(e -> by.put(e.getKey(), e.getValue().asInt()));
                int i = 0;
                for (JsonNode h : p.path("pool")) {
                    i++;
                    if (!answer.isEmpty() && h.path("id").asText("").equals(answer)) {
                        rank = i;
                        List<String> srcs = new ArrayList<>(); for (JsonNode x : h.path("sources")) srcs.add(x.asText());
                        foundBy = String.join("+", srcs);
                        // the pages that led to it, and the guard applied again here, offline: an answer every page of which is a guarded address is no find,
                        // whatever the run's own guard did
                        Set<String> sites = new LinkedHashSet<>();
                        int guardedPages = 0, pages = 0;
                        for (JsonNode x : h.path("pages")) { pages++; sites.add(Described.site(x.asText(""))); if (Guard.byAddress(x.asText(""), source) != null) guardedPages++; }
                        ledBy = String.join(", ", sites);
                        guardedOnly = pages > 0 && guardedPages == pages && srcs.stream().allMatch(x -> x.startsWith(Described.SOURCE));
                    }
                }
                poolSize = i;
            }
            Path ledger = dir.resolve("judged.jsonl");
            if (Files.isRegularFile(ledger) && !answer.isEmpty()) {
                for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    JsonNode j = M.readTree(line);
                    if (!j.path("id").asText("").equals(answer)) continue;
                    if (j.path("pass").asText("").equals(Sift.THUMB)) thumb = j.path("verdict").asText("");
                    if (j.path("pass").asText("").equals(Sift.SHEET)) sheet = j.path("verdict").asText("");
                }
            }
            Path sift = dir.resolve("sift.json");
            if (Files.isRegularFile(sift) && !answer.isEmpty()) {
                int i = 0;
                for (JsonNode sv : M.readTree(Files.readString(sift, StandardCharsets.UTF_8)).path("survivors")) { i++; if (sv.path("id").asText("").equals(answer)) survivorRank = i; }
            }
            boolean looked = false, named = false;
            Path looks = dir.resolve("looks.json");
            if (Files.isRegularFile(looks) && !answer.isEmpty()) {
                ArrayNode ls = (ArrayNode) M.readTree(Files.readString(looks, StandardCharsets.UTF_8));
                for (JsonNode l : ls) if (l.path("id").asText("").equals(answer) && !l.has("error")) looked = true;
                named = best(ls).equals(answer);
            }
            out.add(new Scored(id, subject, error, poolSize, by, cut, rank, foundBy, things, reached, thumb, sheet, survivors, survivorRank, looked, named, run.path("ms").asLong(0), run.path("stage").asText(""), kase.path("views").asText(""), kase.path("subscribers").asText(""),
                    ledBy, leftOut, guardHeld, guardedOnly, asked, answered));
        }
        return out;
    }

    /** The counts of a set at every stage. */
    public record Counts(int n, int crashed, int inPool, int sifted, int survived, int looked, int named, double medianRank, double medianPool, Map<String, Integer> foundBy, int cut,
                         int webAnswered, double medianLeftOut, int guardHeld, int guardedOnly) {
        public static Counts of(List<Scored> all) {
            List<Scored> ok = all.stream().filter(s -> !s.crashed()).toList();
            List<Integer> ranks = new ArrayList<>(), pools = new ArrayList<>(), leftOut = new ArrayList<>();
            Map<String, Integer> foundBy = new TreeMap<>();
            int cut = 0;
            for (Scored s : ok) {
                pools.add(s.poolSize());
                leftOut.add(s.leftOut());
                if (s.cut()) cut++;
                if (s.inPool()) {
                    ranks.add(s.rank());
                    // a page's site is a case's own detail: the counts say only that a page led to the answer, once a case
                    Set<String> srcs = new LinkedHashSet<>();
                    for (String src : s.foundBy().split("\\+")) if (!src.isBlank()) srcs.add(src.startsWith(Described.SOURCE) ? Candidates.DESCRIBED : src);
                    for (String src : srcs) foundBy.merge(src, 1, Integer::sum);
                }
            }
            return new Counts(all.size(), all.size() - ok.size(), (int) ok.stream().filter(Scored::inPool).count(), (int) ok.stream().filter(Scored::sifted).count(), (int) ok.stream().filter(Scored::survived).count(),
                    (int) ok.stream().filter(Scored::looked).count(), (int) ok.stream().filter(Scored::named).count(), median(ranks), median(pools), foundBy, cut,
                    (int) ok.stream().filter(s -> s.answered() > 0).count(), median(leftOut), (int) ok.stream().filter(Scored::guardHeld).count(), (int) ok.stream().filter(Scored::guardedOnly).count());
        }
        public ObjectNode json() {
            ObjectNode o = M.createObjectNode().put("n", n).put("crashed", crashed).put("in_pool", inPool).put("sifted", sifted).put("survived", survived).put("looked", looked).put("named", named).put("median_rank", medianRank).put("median_pool", medianPool).put("cut", cut);
            ObjectNode f = o.putObject("found_by"); foundBy.forEach(f::put);
            o.put("web_answered", webAnswered).put("median_left_out_by_guard", medianLeftOut).put("guard_held_answer", guardHeld).put("only_through_guarded_page", guardedOnly);
            return o;
        }
    }

    static double median(List<Integer> xs) {
        if (xs.isEmpty()) return 0;
        List<Integer> s = new ArrayList<>(xs); Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    static String num(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d); }

    static String of(int a, int n) { return a + " of " + n; }

    /**
     * The results page for a set, in sentences: what was run and on what, the counts at every stage, the counts per subject, then for the
     * dev set the table of every case; for the holdout only the counts, never a case's row, so that nothing of its answers is read off.
     */
    public static String results(String set, List<Scored> scored, String day, String model, String stage, boolean webIndex) { return results("", set, scored, day, model, stage, webIndex, false); }

    /** The same, for a run whose web search was the library's own ({@code paidWeb}): the page says that an index's allowance was spent. */
    public static String results(String set, List<Scored> scored, String day, String model, String stage, boolean webIndex, boolean paidWeb) { return results("", set, scored, day, model, stage, webIndex, paidWeb); }

    /**
     * The same for a named set of cases (the second set is "titled"): the name stands in the heading and the first sentence, and where
     * the cases carry bands the counts are given once more by the answer's views and by its channel's subscribers, for both sets alike.
     */
    public static String results(String name, String set, List<Scored> scored, String day, String model, String stage, boolean webIndex) { return results(name, set, scored, day, model, stage, webIndex, false); }

    /** The page itself: a named set or the first, the web asked keyless, through the library's own search, or not at all. */
    public static String results(String name, String set, List<Scored> scored, String day, String model, String stage, boolean webIndex, boolean paidWeb) {
        Counts c = Counts.of(scored);
        boolean perCase = set.equals(DEV), banded = scored.stream().anyMatch(s -> !s.views().isEmpty() || !s.subscribers().isEmpty());
        StringBuilder b = new StringBuilder();
        b.append("# The find-one bench, ").append(name.isEmpty() ? "" : "the " + name + " cases, ").append("the ").append(set).append(" set, ").append(day).append("\n\n");
        b.append("The pipeline that finds a video a person remembers was run on the ").append(set).append(" set's ").append(c.n()).append(" cases").append(name.isEmpty() ? "" : " of the " + name + " cases").append(", through the ").append(stageWords(stage)).append(", with ").append(model.isEmpty() ? "the configured model" : model)
         .append(paidWeb ? ". Every case ran on a fresh scratch library and without a YouTube Data API key, and the web was asked through the library's own web search, the index the library holds a key for where it holds one, so this run spent that index's allowance and compares only with a run that asked the same index"
                 : ". Every case ran on a fresh scratch library, and the candidate step ran keyless: no YouTube Data API key and no paid search index, so none of anybody's allowance was spent and one run compares with another")
         .append(paidWeb ? "; every result and every page passed the guard against the cases' own threads"
                 : webIndex ? "; the web was asked through the library's own SearXNG and the built-in fallback alone, for the pages that describe a video and for the other indexes' view of youtube.com, and every result and every page passed the guard against the cases' own threads"
                 : "; the other indexes' rung, which needs a search index, did not run").append(".\n\n");
        b.append("These numbers are one run on one day. Each case was run once, so a case's result can change on another day with the same code: a search answers a little differently, a model's answer varies, a channel's list stops at the time. ")
         .append("What the set says is only as strong as its size, ").append(c.n()).append(" cases: one case is ").append(c.n() > 0 ? String.format(Locale.ROOT, "%.0f", 100.0 / c.n()) : "?").append(" percent, and a change of one or two cases between two runs is within what the noise gives.\n\n");
        b.append("## What the ").append(set).append(" set came to\n\n");
        if (c.crashed() > 0) b.append(c.crashed()).append(c.crashed() == 1 ? " case crashed" : " cases crashed").append(" (the run failed before a stage answered); a crash is counted apart from the misses below, which count the ").append(c.n() - c.crashed()).append(" that ran.\n\n");
        int ran = c.n() - c.crashed();
        b.append("- In the pool: ").append(of(c.inPool(), ran)).append(". The answer was among the candidates the enumeration gathered for ").append(c.inPool()).append(" of the ").append(ran).append(" cases that ran")
         .append(c.inPool() > 0 ? "; among those, its rank in the pool was " + num(c.medianRank()) + " in the middle case" : "").append(". The pool held ").append(num(c.medianPool())).append(" candidates in the middle case")
         .append(c.cut() > 0 ? ", and the time cut the enumeration short in " + c.cut() + (c.cut() == 1 ? " case" : " cases") : "").append(".\n");
        if (!c.foundBy().isEmpty()) { List<String> parts = new ArrayList<>(); c.foundBy().forEach((k, v) -> parts.add(v + " by " + sourceWords(k))); b.append("- Which source found it: ").append(String.join(", ", parts)).append(" (an answer found by two sources counts under each).\n"); }
        if (webIndex) {
            b.append("- The web: it answered in ").append(of(c.webAnswered(), ran)).append(" cases. A case in which every question came back empty says nothing about what the web holds.\n");
            b.append("- The guard: it left out ").append(num(c.medianLeftOut())).append(" results and pages in the middle case, the cases' own threads, their mirrors and the pages that quote a post. A page it left out held the answer in ")
             .append(c.guardHeld()).append(c.guardHeld() == 1 ? " case" : " cases").append(", which counts for nothing: such a case is in the pool only when another page or source led to its answer. The answer reached the pool through guarded addresses alone in ")
             .append(c.guardedOnly()).append(c.guardedOnly() == 1 ? " case" : " cases").append(", which is not counted as in the pool.\n");
        }
        if (!stage.equals(CANDIDATES) && !stage.equals(WEB)) {
            b.append("- Reached by the sift: ").append(of(c.sifted(), ran)).append(". The sift judged the answer's thumbnail in ").append(c.sifted()).append(" cases before its time ran out.\n");
            b.append("- Survived the sift: ").append(of(c.survived(), ran)).append(". The answer passed both the thumbnail and the first sheet in ").append(c.survived()).append(" cases.\n");
        }
        if (stage.equals(FULL)) {
            b.append("- Looked at: ").append(of(c.looked(), ran)).append(". A look reached the answer in ").append(c.looked()).append(" cases.\n");
            b.append("- Named: ").append(of(c.named(), ran)).append(". The looks' best, the video with the most of the remembered things found, was the answer in ").append(c.named()).append(" cases.\n");
        }
        b.append("\n## By subject\n\n");
        Map<String, List<Scored>> bySubject = new TreeMap<>();
        for (Scored s : scored) bySubject.computeIfAbsent(s.subject().isEmpty() ? "(no subject)" : s.subject(), k -> new ArrayList<>()).add(s);
        for (var e : bySubject.entrySet()) {
            Counts k = Counts.of(e.getValue());
            int kr = k.n() - k.crashed();
            b.append("- ").append(e.getKey()).append(": in the pool ").append(of(k.inPool(), kr));
            if (!stage.equals(CANDIDATES) && !stage.equals(WEB)) b.append(", survived the sift ").append(of(k.survived(), kr));
            if (stage.equals(FULL)) b.append(", named ").append(of(k.named(), kr));
            b.append(k.crashed() > 0 ? ", " + k.crashed() + " crashed" : "").append(".\n");
        }
        byBand(b, "By the answer's views when the case was picked", scored, Scored::views, stage);
        byBand(b, "By its channel's subscribers when the case was picked", scored, Scored::subscribers, stage);
        if (perCase) {
            b.append("\n## Every case\n\n");
            boolean pool = stage.equals(CANDIDATES) || stage.equals(WEB);
            b.append("| case | subject | things named | pool | the answer's rank | found by |").append(pool ? "" : " thumbnail | first sheet | survivor rank |").append(stage.equals(FULL) ? " looked | named |" : "").append(banded ? " views | subscribers |" : "").append(" minutes |").append(webIndex ? " the pages that led to it | web questions answered | left out by the guard |" : "").append("\n");
            b.append("|---|---|---|---|---|---|").append(pool ? "" : "---|---|---|").append(stage.equals(FULL) ? "---|---|" : "").append(banded ? "---|---|" : "").append("---|").append(webIndex ? "---|---|---|" : "").append("\n");
            for (Scored s : scored) {
                b.append("| ").append(s.id()).append(" | ").append(s.subject()).append(" | ").append(String.join(", ", s.things()).replace("|", "/")).append(" | ");
                if (s.crashed()) { b.append("crashed: ").append(s.error().replace("|", "/")).append(" |"); b.append(pool ? " | |" : " | | | | |").append(stage.equals(FULL) ? " | |" : ""); }
                else {
                    b.append(s.poolSize()).append(s.cut() ? " (cut by the time)" : "").append(" | ").append(s.inPool() ? String.valueOf(s.rank()) : s.guardedOnly() ? "only through a guarded page" : "absent").append(" | ").append(s.foundBy().isEmpty() ? "—" : s.foundBy().replace("+", " + ")).append(" |");
                    if (!pool) b.append(' ').append(s.thumbVerdict().isEmpty() ? (s.inPool() ? "not reached" : "—") : s.thumbVerdict()).append(" | ").append(s.sheetVerdict().isEmpty() ? "—" : s.sheetVerdict()).append(" | ").append(s.survived() ? s.survivorRank() + " of " + s.survivors() : "—").append(" |");
                    if (stage.equals(FULL)) b.append(' ').append(s.looked() ? "yes" : "no").append(" | ").append(s.named() ? "yes" : "no").append(" |");
                }
                if (banded) b.append(' ').append(bandWords(s.views())).append(" | ").append(bandWords(s.subscribers())).append(" |");
                b.append(' ').append(s.ms() / 60_000).append(" |");
                if (webIndex) b.append(' ').append(s.ledBy().isEmpty() ? "—" : s.ledBy().replace("|", "/")).append(" | ").append(s.answered()).append(" of ").append(s.asked()).append(" | ").append(s.leftOut()).append(s.guardHeld() ? " (one held the answer)" : "").append(" |");
                b.append("\n");
            }
        } else b.append("\nThe holdout's cases are not listed one by one: the page says only what the set came to, so that nothing of its answers is read off a row.\n");
        return b.toString();
    }

    /** The bands a set may carry, smallest first; a band this list does not know comes after them. */
    static final List<String> BANDS = List.of("under-10k", "under-50k", "10k-1m", "50k-1m", "over-1m", "not-shown");

    static String bandWords(String band) {
        return switch (band) { case "under-10k" -> "under 10,000"; case "under-50k" -> "under 50,000"; case "10k-1m" -> "10,000 to 1,000,000"; case "50k-1m" -> "50,000 to 1,000,000"; case "over-1m" -> "over 1,000,000"; case "not-shown" -> "not shown"; case "" -> "—"; default -> band; };
    }

    /** The cases grouped by a band, smallest band first; the cases without one are left out, and an empty map means the set carries none. */
    static Map<String, List<Scored>> grouped(List<Scored> scored, Function<Scored, String> band) {
        Map<String, List<Scored>> by = new TreeMap<>(Comparator.comparingInt((String k) -> BANDS.contains(k) ? BANDS.indexOf(k) : BANDS.size()).thenComparing(Comparator.naturalOrder()));
        for (Scored s : scored) if (!band.apply(s).isEmpty()) by.computeIfAbsent(band.apply(s), k -> new ArrayList<>()).add(s);
        return by;
    }

    /** The counts once more by a band the cases carry, a line a band; nothing when the set carries none. Counts only, so the holdout's page may hold it. */
    static void byBand(StringBuilder b, String heading, List<Scored> scored, Function<Scored, String> band, String stage) {
        Map<String, List<Scored>> by = grouped(scored, band);
        if (by.isEmpty()) return;
        b.append("\n## ").append(heading).append("\n\n");
        for (var e : by.entrySet()) {
            Counts k = Counts.of(e.getValue());
            int kr = k.n() - k.crashed();
            b.append("- ").append(bandWords(e.getKey())).append(": in the pool ").append(of(k.inPool(), kr));
            if (!stage.equals(CANDIDATES) && !stage.equals(WEB)) b.append(", survived the sift ").append(of(k.survived(), kr));
            if (stage.equals(FULL)) b.append(", named ").append(of(k.named(), kr));
            b.append(k.crashed() > 0 ? ", " + k.crashed() + " crashed" : "").append(".\n");
        }
    }

    /** The same counts by band for the counts file, under the key given; nothing when the set carries none. */
    static void bandCounts(ObjectNode counts, String key, List<Scored> scored, Function<Scored, String> band) {
        Map<String, List<Scored>> by = grouped(scored, band);
        if (by.isEmpty()) return;
        ObjectNode o = counts.putObject(key);
        by.forEach((k, v) -> o.set(k, Counts.of(v).json()));
    }

    /** The name of a set of cases, from its folder: bench/findone is the first set and has none; bench/findone-titled is "titled". */
    static String setName(Path casesDir) {
        Path folder = casesDir.toAbsolutePath().normalize().getFileName();
        String n = folder == null ? "" : folder.toString();
        return n.startsWith("findone-") ? n.substring("findone-".length()) : "";
    }

    static String stageWords(String stage) {
        return switch (stage) { case SIFT -> "candidate step and the sift"; case FULL -> "candidate step, the sift and the looks"; case WEB -> "pages that describe a video alone, with YouTube itself not searched"; default -> "candidate step alone"; };
    }

    static String sourceWords(String source) {
        return switch (source) { case Candidates.SEARCH -> "a deep search"; case Candidates.CHANNEL -> "a channel listed whole"; case Candidates.API -> "the Data API in date order"; case Candidates.WEB_INDEX -> "another index"; case Candidates.HISTORY -> "the watch history"; case Candidates.DESCRIBED -> "a page that describes the video"; default -> source; };
    }

    // ---- the command

    static final String USAGE = "usage: researchzosho bench findone --set dev|holdout [--stage candidates|sift|full|web] [--drive <url>] [--model <name>] [--out <dir>] [--cases <dir>] [--minutes 15] [--sift-minutes 20] [--looks 5] [--only id,id] [--reuse] [--web keyless|library]\n"
            + "       researchzosho bench findone --score <dir> [--cases <dir>]\n"
            + "       researchzosho bench findone --seal <picked.jsonl> [--cases <dir>]";

    /** {@code researchzosho bench findone …}: the runner, the scorer, or the sealing of a picked file into the cases and the sealed answers. */
    public static int cli(String[] args) throws IOException {
        String set = "", stage = CANDIDATES, drive = "", model = "", out = "", casesDir = "bench/findone", score = "", seal = "", only = "", web = "keyless";
        int minutes = MINUTES_DEFAULT, siftMinutes = SIFT_MINUTES_DEFAULT, looks = LOOKS_DEFAULT;
        boolean reuse = false;
        List<String> rest = new ArrayList<>();
        for (int i = 3; i < args.length; i++) { if (args[i].equals("--reuse")) reuse = true; else rest.add(args[i]); }
        args = rest.toArray(new String[0]);
        for (int i = 0; i + 1 < args.length; i += 2) switch (args[i]) {
            case "--set" -> set = args[i + 1].strip().toLowerCase(Locale.ROOT);
            case "--stage" -> stage = args[i + 1].strip().toLowerCase(Locale.ROOT);
            case "--drive" -> drive = args[i + 1].strip();
            case "--model" -> model = args[i + 1].strip();
            case "--out" -> out = args[i + 1];
            case "--cases" -> casesDir = args[i + 1];
            case "--minutes" -> minutes = Integer.parseInt(args[i + 1]);
            case "--sift-minutes" -> siftMinutes = Integer.parseInt(args[i + 1]);
            case "--looks" -> looks = Integer.parseInt(args[i + 1]);
            case "--only" -> only = args[i + 1];
            case "--web" -> web = args[i + 1].strip().toLowerCase(Locale.ROOT);
            case "--score" -> score = args[i + 1];
            case "--seal" -> seal = args[i + 1];
            default -> { System.err.println("The bench does not know the flag " + args[i] + ".\n" + USAGE); return 2; }
        }
        Path dir = Path.of(casesDir);
        if (!seal.isEmpty()) {
            seal(Path.of(seal), dir);
            List<Case> all = load(dir.resolve(CASES_FILE));
            System.out.println("Wrote " + all.size() + " cases to " + dir.resolve(CASES_FILE) + ", " + all.stream().filter(Case::dev).count() + " in the dev set with their answers in the open and " + all.stream().filter(c -> !c.dev()).count() + " in the holdout with their answers sealed in " + dir.resolve(SEALED_FILE) + ".");
            return 0;
        }
        List<Case> all = load(dir.resolve(CASES_FILE));
        Map<String, String> sealedAnswers = sealed(dir.resolve(SEALED_FILE));
        List<String> wrong = check(all, sealedAnswers);
        if (!wrong.isEmpty()) { System.err.println("The cases have " + wrong.size() + " fault(s):"); for (String w : wrong) System.err.println("  " + w); return 1; }
        Map<String, String> answers = answers(all, sealedAnswers);
        if (!score.isEmpty()) {
            Path o = Path.of(score);
            for (String s : List.of(DEV, HOLDOUT)) if (Files.isDirectory(o.resolve(s))) System.out.print(writeResults(setName(dir), o, s, answers, "", ""));
            return 0;
        }
        if (!set.equals(DEV) && !set.equals(HOLDOUT)) { System.err.println("Say which set, dev or holdout.\n" + USAGE); return 2; }
        if (!Set.of(CANDIDATES, SIFT, FULL, WEB).contains(stage)) { System.err.println("The stage is candidates, sift, full or web.\n" + USAGE); return 2; }
        if (!web.equals("keyless") && !web.equals("library")) { System.err.println("The web is keyless (the library's own SearXNG and the built-in fallback, which cost nothing) or library (the library's own web search, which spends a paid index's allowance where a key is set).\n" + USAGE); return 2; }
        libraryWeb = web.equals("library");
        String key = Config.get(Candidates.KEY);
        if (key != null && !key.isBlank()) { System.err.println("A YouTube Data API key is set (" + Candidates.KEY + "). The bench runs keyless, so that it never spends the key's quota and every run compares with another: run it where the key is not set."); return 1; }
        if (drive.isEmpty()) drive = Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200");
        if (model.isEmpty()) model = Config.get("RESEARCHZOSHO_MODEL", "local-model");
        Path o = out.isEmpty() ? dir.resolve("out-" + LocalDate.now()) : Path.of(out);
        List<Case> chosen = new ArrayList<>();
        Set<String> onlyIds = only.isEmpty() ? Set.of() : Set.of(only.split("\\s*,\\s*"));
        for (Case c : all) if (c.set().equals(set) && (onlyIds.isEmpty() || onlyIds.contains(c.id()))) chosen.add(c);
        Settings s = new Settings(drive, model, o, stage, minutes, siftMinutes, looks, reuse);
        System.out.println("Running the " + set + " set's " + chosen.size() + " cases through the " + stageWords(stage) + " with " + model + " at " + drive + (libraryWeb ? ", no YouTube key, the web through the library's own search (a paid index's allowance is spent where a key is set), " : ", keyless, ") + (reuse ? "a pool already kept under the out folder taken as it is, else " : "") + "up to " + minutes + " minutes of enumeration" + (stage.equals(CANDIDATES) || stage.equals(WEB) ? "" : " and " + siftMinutes + " of sifting") + " a case; the artefacts go under " + o + ".");
        run(chosen, s, live(s), System.out::println);
        System.out.print(writeResults(setName(dir), o, set, answers, model, stage));
        return 0;
    }

    /** The results page of a set written under the out folder as RESULTS-<set>.md, and returned. */
    public static String writeResults(Path out, String set, Map<String, String> answers, String model, String stage) throws IOException { return writeResults("", out, set, answers, model, stage); }

    /** The same for a named set of cases; the counts file gains the counts by band where the cases carry bands. */
    public static String writeResults(String name, Path out, String set, Map<String, String> answers, String model, String stage) throws IOException {
        List<Scored> scored = score(out.resolve(set), answers);
        String day = LocalDate.now().toString(), m = model, st = stage;
        boolean web = false, first = true, paid = false;
        for (Path dir : List.of(out.resolve(set))) {
            try (var s = Files.list(dir)) {
                for (Path c : s.sorted().toList()) {
                    Path rec = c.resolve("run.json");
                    if (!Files.isRegularFile(rec)) continue;
                    JsonNode r = M.readTree(Files.readString(rec, StandardCharsets.UTF_8));
                    if (first) { day = r.path("day").asText(day); if (m.isEmpty()) m = r.path("model").asText(""); if (st.isEmpty()) st = r.path("stage").asText(CANDIDATES); first = false; }
                    web = web || r.path("web_index").asBoolean(false) || r.path("guard").isObject();   // any case the web was asked for makes the page say so
                    paid = paid || r.path("web").asText("").equals("library");
                }
            }
        }
        String page = results(name, set, scored, day, m, st.isEmpty() ? CANDIDATES : st, web, paid);
        Files.createDirectories(out);
        Files.writeString(out.resolve("RESULTS-" + set + ".md"), page, StandardCharsets.UTF_8);
        ObjectNode counts = Counts.of(scored).json();
        if (!name.isEmpty()) counts.put("case_set", name);
        bandCounts(counts, "by_views", scored, Scored::views);
        bandCounts(counts, "by_subscribers", scored, Scored::subscribers);
        if (set.equals(DEV)) { ArrayNode rows = counts.putArray("cases"); for (Scored sc : scored) rows.add(M.createObjectNode().put("id", sc.id()).put("subject", sc.subject()).put("pool", sc.poolSize()).put("rank", sc.rank()).put("found_by", sc.foundBy()).put("thumbnail", sc.thumbVerdict()).put("first_sheet", sc.sheetVerdict()).put("survivor_rank", sc.survivorRank()).put("looked", sc.looked()).put("named", sc.named()).put("crashed", sc.crashed()).put("views", sc.views()).put("subscribers", sc.subscribers())); }
        Files.writeString(out.resolve("counts-" + set + ".json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(counts) + "\n", StandardCharsets.UTF_8);
        return page;
    }
}
